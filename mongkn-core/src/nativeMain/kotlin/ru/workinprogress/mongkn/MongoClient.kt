package ru.workinprogress.mongkn

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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import mongkn.cinterop.bson_error_t
import mongkn.cinterop.mongoc_client_pool_destroy
import mongkn.cinterop.mongoc_client_pool_max_size
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
 *
 * @param ioThreads сколько потоков отдаётся под блокирующие вызовы драйвера.
 * @param maxConcurrentClients сколько операций могут одновременно держать клиента. Перекрывает
 *   `maxPoolSize` из строки подключения.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class, DelicateCoroutinesApi::class)
public class MongoClient(
    connectionString: String,
    ioThreads: Int = DEFAULT_IO_THREADS,
    maxConcurrentClients: Int = DEFAULT_MAX_CONCURRENT_CLIENTS,
) : AutoCloseable {

    private val closed = AtomicBoolean(false)

    internal val pool: CPointer<mongoc_client_pool_t>

    private val poolSize: Int = maxConcurrentClients.coerceAtLeast(1)

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

    /**
     * Пропускной билет на клиента из пула.
     *
     * Существует ради одного: **`mongoc_client_pool_pop` блокирует поток, когда пул исчерпан,
     * и эта блокировка неотменяема** — она внутри C, а не в точке приостановки корутины
     * (ресёрч §1.12). До семафора исчерпание пула означало зависший намертво прогон, снаружи
     * неотличимый от повисшей сборки.
     *
     * Теперь ожидание клиента — ожидание **семафора**, то есть обычная приостановка: она
     * отменяема, вокруг неё работает `withTimeout`, и ни один поток при этом не занят.
     *
     * Число разрешений в точности равно размеру пула libmongoc (выставляется в `init`), поэтому
     * взявший разрешение гарантированно получает клиента без блокировки.
     *
     * **Граница гарантии.** Инвариант «есть разрешение — есть клиент» держится ровно до тех пор,
     * пока [useClient] возвращает клиента в пул при любом исходе. Если этот `finally` сломать,
     * разрешения продолжат выдаваться, а клиентов не останется — и мы снова получим
     * неотменяемую блокировку из §1.12. Семафор защищает от честной перегрузки, а не от бага
     * в возврате.
     */
    private val permits = Semaphore(poolSize)

    init {
        Mongkn.initialize()
        pool = try {
            memScoped {
                val error = alloc<bson_error_t>()
                val uri = mongoc_uri_new_with_error(connectionString, error.ptr)
                    ?: throw MongoException(error.domain, error.code, error.message.toKString())
                try {
                    val created = mongoc_client_pool_new_with_error(uri, error.ptr)
                        ?: throw MongoException(error.domain, error.code, error.message.toKString())
                    // Выставляем **до** первого pop и ровно в размер семафора: договорённость
                    // «есть разрешение — есть клиент» держится только при их равенстве.
                    mongoc_client_pool_max_size(created, poolSize.toUInt())
                    created
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
     * Проверяет, что клиент ещё жив.
     *
     * Зовётся **до** переключения на [dispatcher]: тот закрывается в [close] вместе с пулом,
     * и попытка отправить на него работу дала бы невнятную ошибку вместо понятной.
     */
    internal fun checkOpen() {
        check(!closed.load()) { "MongoClient уже закрыт" }
    }

    /**
     * Выполняет [block] с клиентом из пула на [dispatcher]. Точка входа для одиночных операций.
     *
     * Ожидание свободного клиента — приостановка на семафоре, а не блокировка потока.
     */
    internal suspend fun <T> withClient(block: (CPointer<mongoc_client_t>) -> T): T {
        checkOpen()
        return permits.withPermit {
            withContext(dispatcher) { useClient(block) }
        }
    }

    /**
     * Берёт клиента под уже полученное разрешение — без переключения контекста.
     *
     * Нужно для `find`: внутри `flow { }` нельзя звать `withContext`, это нарушает инвариант
     * потока. Контекст там задаёт `flowOn`, а разрешение берётся отдельно, [withPermit].
     *
     * Звать **только** удерживая разрешение: иначе `pop` может заблокировать поток намертво.
     *
     * `inline` не для скорости: внутри `find` в этот блок попадает `emit`, а он suspend —
     * в неинлайновой лямбде его вызвать нельзя.
     */
    internal inline fun <T> useClient(block: (CPointer<mongoc_client_t>) -> T): T {
        val client = mongoc_client_pool_pop(pool)
            ?: error("mongoc_client_pool_pop вернул NULL при взятом разрешении")
        try {
            return block(client)
        } finally {
            mongoc_client_pool_push(pool, client)
        }
    }

    /**
     * Держит разрешение на клиента всё время [block].
     *
     * Отдельно от [withClient], потому что курсор `find` живёт всё время сбора потока: вернуть
     * клиента в пул, пока курсор открыт, нельзя — курсор принадлежит клиенту. Это ограничение
     * libmongoc, и снять его невозможно. Что удалось снять — неотменяемость ожидания.
     */
    internal suspend fun <T> withPermit(block: suspend () -> T): T {
        checkOpen()
        return permits.withPermit { block() }
    }

    public companion object {
        /**
         * Сколько потоков отдаётся под блокирующие вызовы драйвера.
         *
         * Намеренно меньше, чем [DEFAULT_MAX_CONCURRENT_CLIENTS]: клиент занят всё время жизни
         * курсора, а поток — только пока идёт сам вызов, так что клиентов нужно больше.
         */
        public const val DEFAULT_IO_THREADS: Int = 4

        /** Совпадает с `maxPoolSize` по умолчанию у libmongoc. */
        public const val DEFAULT_MAX_CONCURRENT_CLIENTS: Int = 100
    }
}
