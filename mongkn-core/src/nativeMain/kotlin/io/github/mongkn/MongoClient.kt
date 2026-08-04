package io.github.mongkn

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.newFixedThreadPoolContext
import mongkn.cinterop.bson_error_t
import mongkn.cinterop.mongoc_client_pool_destroy
import mongkn.cinterop.mongoc_client_pool_new_with_error
import mongkn.cinterop.mongoc_client_pool_pop
import mongkn.cinterop.mongoc_client_pool_push
import mongkn.cinterop.mongoc_client_pool_t
import mongkn.cinterop.mongoc_client_t
import mongkn.cinterop.mongoc_uri_destroy
import mongkn.cinterop.mongoc_uri_new_with_error

/**
 * Точка входа в MongoDB.
 *
 * Владеет `mongoc_client_pool_t`, а **не** одним `mongoc_client_t`: последний не потокобезопасен,
 * а операции уходят на многопоточный диспетчер. Подробности — решение Р2 ресёрча.
 *
 * Экземпляр можно свободно делить между корутинами: на время операции клиент берётся из пула
 * и возвращается обратно, так что одновременного доступа к одному `mongoc_client_t` не возникает.
 *
 * Драйвер инициализируется в конструкторе и освобождается в [close] — звать [Mongkn.initialize]
 * снаружи не нужно.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class, DelicateCoroutinesApi::class)
public class MongoClient(
    connectionString: String,
    ioThreads: Int = DEFAULT_IO_THREADS,
) : AutoCloseable {

    private val closed = AtomicBoolean(false)

    internal val pool: CPointer<mongoc_client_pool_t>

    /**
     * Диспетчер для блокирующих вызовов драйвера.
     *
     * Пришлось завести свой, а не брать `Dispatchers.IO`: на Kotlin/Native он объявлен `internal`
     * (проверено по klib `kotlinx-coroutines-core-macosArm64Main` 1.10.2 и 1.11.0 — ресёрч §1.8),
     * хотя документация утверждает, что он доступен на Native. `Dispatchers.Default` не подходит
     * принципиально: он процессорный, и занимать его блокирующим вводом-выводом нельзя.
     *
     * Побочная выгода — размер пула потоков явный и привязан к времени жизни клиента.
     */
    internal val dispatcher: CloseableCoroutineDispatcher =
        newFixedThreadPoolContext(ioThreads.coerceAtLeast(1), "mongkn-io")

    init {
        Mongkn.initialize()
        pool = try {
            memScoped {
                val error = alloc<bson_error_t>()
                val uri = mongoc_uri_new_with_error(connectionString, error.ptr)
                    ?: throw MongoException(error.domain, error.code, error.message.toKString())
                try {
                    mongoc_client_pool_new_with_error(uri, error.ptr)
                        ?: throw MongoException(error.domain, error.code, error.message.toKString())
                } finally {
                    // Пул копирует URI себе, так что наш экземпляр больше не нужен —
                    // и он утечёт, если не уничтожить его здесь.
                    mongoc_uri_destroy(uri)
                }
            }
        } catch (e: Throwable) {
            // Конструктор не завершится, а значит close() никто не вызовет — прибираем сами.
            // Mongkn.shutdown() здесь звать нельзя: он терминальный на весь процесс.
            dispatcher.close()
            throw e
        }
    }

    public fun getDatabase(name: String): MongoDatabase = MongoDatabase(this, name)

    /**
     * Уничтожает пул и снимает инициализацию драйвера.
     *
     * `mongoc_client_pool_destroy` — единственная непотокобезопасная операция пула, поэтому
     * закрывать клиента можно только из одного потока и только после того, как завершены все
     * операции. Повторный вызов ничего не делает.
     *
     * [Mongkn.shutdown] отсюда **не** вызывается: он терминальный на весь процесс, и после него
     * ни один новый [MongoClient] не заработает. Закрытие одного клиента не должно ронять
     * остальные.
     */
    override fun close() {
        if (closed.compareAndSet(expectedValue = false, newValue = true)) {
            dispatcher.close()
            mongoc_client_pool_destroy(pool)
        }
    }

    /**
     * Берёт клиента из пула на время [block] и возвращает обратно при любом исходе.
     *
     * `mongoc_client_pool_pop` **блокирует** поток, когда пул исчерпан (по умолчанию 100 клиентов),
     * и эта блокировка не прерывается отменой корутины — риск 2 ресёрча. Поэтому звать только
     * на [io].
     */
    /**
     * Проверяет, что клиент ещё жив.
     *
     * Зовётся **до** переключения на [dispatcher]: тот закрывается в [close] вместе с пулом,
     * и попытка отправить на него работу дала бы невнятную ошибку вместо понятной.
     */
    internal fun checkOpen() {
        check(!closed.load()) { "MongoClient уже закрыт" }
    }

    internal inline fun <T> withClient(block: (CPointer<mongoc_client_t>) -> T): T {
        checkOpen()
        val client = mongoc_client_pool_pop(pool) ?: error("mongoc_client_pool_pop вернул NULL")
        try {
            return block(client)
        } finally {
            mongoc_client_pool_push(pool, client)
        }
    }

    public companion object {
        /**
         * Сколько потоков отдаётся под блокирующие вызовы драйвера.
         *
         * Намеренно меньше, чем размер пула клиентов libmongoc (по умолчанию 100): пул держит
         * клиента всё время жизни курсора, а поток — только пока идёт сам вызов, так что
         * клиентов нужно больше, чем потоков.
         */
        public const val DEFAULT_IO_THREADS: Int = 4
    }
}
