package ru.workinprogress.mongkn

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import mongkn.cinterop.bson_destroy
import mongkn.cinterop.bson_error_t
import mongkn.cinterop.bson_init
import mongkn.cinterop.bson_t
import mongkn.cinterop.mongoc_client_session_abort_transaction
import mongkn.cinterop.mongoc_client_session_append
import mongkn.cinterop.mongoc_client_session_commit_transaction
import mongkn.cinterop.mongoc_client_session_destroy
import mongkn.cinterop.mongoc_client_session_in_transaction
import mongkn.cinterop.mongoc_client_session_start_transaction
import mongkn.cinterop.mongoc_client_session_t
import mongkn.cinterop.mongoc_client_t
import ru.workinprogress.mongkn.bson.Document
import ru.workinprogress.mongkn.bson.toDocument

/**
 * Логическая сессия — область, внутри которой операции связаны между собой.
 *
 * Нужна ради транзакций и причинной согласованности. Сама по себе, без транзакции, сессия
 * гарантирует, что чтение увидит собственную предыдущую запись (`causal consistency`).
 *
 * **Сессия — это не то же, что коллекция, полученная от клиента.** Операции внутри сессии надо
 * брать у неё самой:
 *
 * ```
 * client.startSession().use { session ->
 *     session.withTransaction {
 *         val accounts = session.getDatabase("bank").getCollection("accounts")
 *         accounts.updateOne(…)
 *         accounts.updateOne(…)
 *     }
 * }
 * ```
 *
 * ## Почему сессия дороже обычной операции
 *
 * Сессия **закреплена за конкретным `mongoc_client_t`**: libmongoc откажется выполнять с ней
 * операцию через другого клиента. Обычная операция берёт клиента из пула на время вызова
 * и возвращает; сессия обязана держать одного и того же клиента от начала до [close]. Это ровно
 * тот случай, ради которого решение Р2 оговаривало исключения.
 *
 * Отсюда две платы, обе неизбежные:
 *
 * * сессия занимает разрешение семафора (то есть одного клиента из пула) на всё своё время;
 * * сессия занимает **собственный поток**. Клиент не потокобезопасен, а операции сессии
 *   растянуты во времени и могли бы попадать на разные потоки общего пула. Свой поток снимает
 *   вопрос целиком, а не «скорее всего обойдётся» — так же сделано у подписок
 *   ([ChangeStreamFlow]).
 *
 * Операции внутри сессии дополнительно сериализованы мьютексом: сессия последовательна
 * по смыслу, и две параллельные операции в ней всё равно были бы ошибкой.
 *
 * Транзакции работают **только на replica set** — на standalone сервер откажет.
 */
@OptIn(ExperimentalForeignApi::class, DelicateCoroutinesApi::class)
public class ClientSession internal constructor(
    internal val client: MongoClient,
    @PublishedApi internal val handle: CPointer<mongoc_client_t>,
    private val session: CPointer<mongoc_client_session_t>,
    private val release: () -> Unit,
) : AutoCloseable {
    private val lock = Mutex()

    private var closed = false

    /** Свой поток: клиент закреплён за сессией, и делить его с общим пулом потоков нельзя. */
    internal val dispatcher: CloseableCoroutineDispatcher = newSingleThreadContext("mongkn-session")

    /**
     * Опции, которыми операция объявляет свою принадлежность сессии.
     *
     * Документ не собирается вручную, а **читается у самого libmongoc**: пустой bson отдаётся
     * `mongoc_client_session_append`, и что тот записал, то и уходит в опции каждой операции.
     * Сегодня это единственный ключ `sessionId` с целым числом, но полагаться на это не нужно —
     * копируется всё, что драйвер положил. Если формат когда-нибудь изменится, здесь ничего
     * менять не придётся.
     */
    internal val opts: Document =
        memScoped {
            val error = alloc<bson_error_t>()
            val document = alloc<bson_t>()
            bson_init(document.ptr)
            try {
                if (!mongoc_client_session_append(session, document.ptr, error.ptr)) fail(error.ptr)
                document.ptr.toDocument()
            } finally {
                bson_destroy(document.ptr)
            }
        }

    /** База, операции которой пойдут в этой сессии. */
    public fun getDatabase(name: String): MongoDatabase = MongoDatabase(client, name, session = this)

    /** Идёт ли транзакция прямо сейчас. */
    public val inTransaction: Boolean
        get() = mongoc_client_session_in_transaction(session)

    /**
     * Начинает транзакцию.
     *
     * Настройки транзакции (`readConcern`, `writeConcern`, `maxCommitTimeMS`) задать пока нельзя,
     * и параметра под них здесь намеренно **нет**. У libmongoc они передаются структурой
     * `mongoc_transaction_opt_t` с сеттерами, а не документом опций, — то есть требуют отдельной
     * обвязки (M-69). Параметр-заглушка, который молча не доезжает до сервера, был бы хуже
     * его отсутствия: именно такую потерю пришлось чинить в M10.
     *
     * Внутри транзакции гарантии уровня операции задавать всё равно нельзя — сервер отвергает
     * `writeConcern` у отдельной операции в транзакции. Так что до M-69 действуют умолчания
     * из строки подключения.
     */
    public suspend fun startTransaction() {
        onSession {
            memScoped {
                val error = alloc<bson_error_t>()
                if (!mongoc_client_session_start_transaction(session, null, error.ptr)) fail(error.ptr)
            }
        }
    }

    /** Фиксирует транзакцию. */
    public suspend fun commitTransaction() {
        onSession {
            memScoped {
                val error = alloc<bson_error_t>()
                val reply = alloc<bson_t>()
                try {
                    if (!mongoc_client_session_commit_transaction(session, reply.ptr, error.ptr)) fail(error.ptr)
                } finally {
                    bson_destroy(reply.ptr)
                }
            }
        }
    }

    /** Откатывает транзакцию. */
    public suspend fun abortTransaction() {
        onSession {
            memScoped {
                val error = alloc<bson_error_t>()
                if (!mongoc_client_session_abort_transaction(session, error.ptr)) fail(error.ptr)
            }
        }
    }

    /**
     * Выполняет [body] в транзакции: фиксирует при нормальном завершении, откатывает при любом
     * исключении.
     *
     * Откат делается «по возможности»: если транзакция уже развалилась на стороне сервера,
     * `abortTransaction` сам отдаст ошибку, и она не должна подменить собой исходную причину.
     * Поэтому неуспех отката подавляется — наружу уходит то исключение, из-за которого мы
     * вообще откатываемся.
     *
     * Повторов **нет**. Официальный драйвер умеет перезапускать транзакцию по меткам
     * `TransientTransactionError` и `UnknownTransactionCommitResult`; у нас этого пока не будет —
     * см. M-68 в бэклоге. Пока считайте, что упавшую транзакцию перезапускает вызывающий.
     */
    public suspend fun <T> withTransaction(body: suspend () -> T): T {
        startTransaction()
        val result =
            try {
                body()
            } catch (e: Throwable) {
                runCatching { abortTransaction() }
                throw e
            }
        commitTransaction()
        return result
    }

    /**
     * Выполняет [block] на закреплённом клиенте.
     *
     * Мьютекс и собственный поток — не перестраховка: `mongoc_client_t` не потокобезопасен,
     * а операции сессии растянуты во времени.
     */
    internal suspend fun <T> onClient(block: (CPointer<mongoc_client_t>) -> T): T = onSession { block(handle) }

    /**
     * Держит мьютекс сессии всё время [block] — для курсоров, живущих дольше одной операции.
     *
     * Поток здесь не переключается: у потоковых операций контекст задаёт `flowOn`, а внутри
     * `flow { }` менять его нельзя.
     */
    internal suspend fun <T> withLock(block: suspend () -> T): T {
        check(!closed) { "ClientSession уже закрыта" }
        return lock.withLock { block() }
    }

    private suspend fun <T> onSession(block: () -> T): T {
        check(!closed) { "ClientSession уже закрыта" }
        return lock.withLock { withContext(dispatcher) { block() } }
    }

    /**
     * Закрывает сессию и возвращает клиента в пул.
     *
     * Незакрытая транзакция откатывается сервером сама по таймауту, но клиент и разрешение
     * не вернулись бы никогда — поэтому закрывать сессию обязательно.
     */
    override fun close() {
        if (closed) return
        closed = true
        mongoc_client_session_destroy(session)
        dispatcher.close()
        release()
    }

    private fun fail(error: CPointer<bson_error_t>): Nothing {
        val value = error.pointed
        throw MongoException(value.domain, value.code, value.message.toKString())
    }
}
