package ru.workinprogress.mongkn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.workinprogress.mongkn.bson.document
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Недоступный сервер обязан давать **исключение**, а не падение процесса.
 *
 * Заведён по отчёту первого потребителя: на нативной сборке внутренний сервис недоступная на старте
 * MongoDB роняла процесс целиком. В [DiagnosticsTest] недоступный сервер уже проверялся, но
 * ровно на одном вызове (`listCollectionNames`), и этого оказалось мало — путей, на которых
 * исключение может не доехать до вызывающего, у обвязки несколько, и каждый устроен по-своему:
 *
 * * `Flow` собирается на своём диспетчере, и исключение проходит через границу потока;
 * * `watch` держит **отдельный** поток на подписку;
 * * сессия — тоже отдельный поток, привязанный к клиенту;
 * * `close()` после неудачи возвращает в пул клиента, которым ни разу не пользовались.
 *
 * На Kotlin/Native непойманное исключение в рабочем потоке завершает **процесс**, а не поток —
 * поэтому «упало с ошибкой» и «упало насмерть» здесь разные исходы, и разница видна только
 * на таком тесте: если процесс умрёт, весь тестовый бинарник не доживёт до отчёта.
 */
class UnreachableServerTest {
    private val clients = mutableListOf<MongoClient>()

    @AfterTest
    fun tearDown() {
        clients.forEach { it.close() }
        clients.clear()
    }

    /** Порт, на котором заведомо никто не слушает, и короткий таймаут выбора сервера. */
    private fun connect(): MongoClient =
        MongoClient("mongodb://127.0.0.1:1/?serverSelectionTimeoutMS=300").also { clients += it }

    private fun collection() = connect().getDatabase("mongkn_unreachable").getCollection("probe")

    @Test
    fun `a find flow reports the failure to the collector`() =
        runTest {
            assertFailsWith<MongoException> { collection().find().toList() }
        }

    @Test
    fun `an insert reports the failure to the caller`() =
        runTest {
            assertFailsWith<MongoException> { collection().insertOne(document { put("_id", 1) }) }
        }

    @Test
    fun `creating an index at startup reports the failure to the caller`() =
        runTest {
            // Самый частый вызов на старте сервиса — и самый обидный, если он валит процесс.
            assertFailsWith<MongoException> { collection().createIndex(document { put("name", 1) }) }
        }

    @Test
    fun `an aggregation reports the failure to the collector`() =
        runTest {
            assertFailsWith<MongoException> {
                collection().aggregate(listOf(document { put("\$match", document { put("x", 1) }) })).toList()
            }
        }

    @Test
    fun `opening a session reports the failure to the caller`() =
        runTest {
            val client = connect()

            // Сессия живёт на своём потоке; исключение обязано вернуться сюда, а не остаться там.
            assertFailsWith<MongoException> {
                client.startSession().use { session ->
                    session.getDatabase("mongkn_unreachable").getCollection("probe").insertOne(
                        document { put("_id", 1) },
                    )
                }
            }
        }

    @Test
    fun `a watch subscription reports the failure instead of killing the process`() =
        runTest {
            val collection = collection()

            // Время здесь нужно **настоящее**: runTest живёт в виртуальном, и таймаут вокруг
            // блокирующей подписки сработал бы мгновенно, ничего не проверив.
            val outcome =
                withContext(Dispatchers.Default.limitedParallelism(1)) {
                    withTimeoutOrNull(10_000) {
                        runCatching { collection.watch().first() }.exceptionOrNull()
                    }
                }

            assertNotNull(outcome, "подписка на недоступный сервер не завершилась за 10 с")
            assertIs<MongoException>(outcome, "ждали MongoException, получили ${outcome::class.simpleName}")
        }

    @Test
    fun `closing a client that never reached the server is safe`() =
        runTest {
            val client = connect()
            runCatching { client.getDatabase("mongkn_unreachable").listCollectionNames() }

            client.close()
            clients.remove(client)
        }
}
