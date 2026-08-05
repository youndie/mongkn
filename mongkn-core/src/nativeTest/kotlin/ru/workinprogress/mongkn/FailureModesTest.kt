package ru.workinprogress.mongkn

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ru.workinprogress.mongkn.bson.Document
import ru.workinprogress.mongkn.bson.document
import ru.workinprogress.mongkn.support.TestServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * Как выглядят сбои со стороны потребителя (M-68, M-69).
 *
 * Это не проверка функций, а проверка **краёв**: что видит вызывающий, когда операцию отменяют
 * посреди работы и когда сервер обрывает связь посреди курсора. Обе области до сих пор
 * не проверялись, а именно с ними человек сталкивается первыми при неполадках.
 *
 * `runBlocking`, а не `runTest`: здесь измеряется настоящее время, а виртуальное показало бы
 * заведомо неверную картину.
 *
 * Сбои заказываются серверу через `failCommand` — тестовый mongod поднят
 * с `enableTestCommands=1`.
 */
class FailureModesTest {
    private val clients = mutableListOf<MongoClient>()

    @AfterTest
    fun tearDown() {
        clients.forEach { it.close() }
        clients.clear()
    }

    private fun connect(options: String = ""): MongoClient =
        MongoClient(TestServer.uri("serverSelectionTimeoutMS=3000$options")).also { clients += it }

    private suspend fun seeded(
        client: MongoClient,
        hint: String,
        count: Int = 200,
    ): MongoCollection<Document> {
        val collection = client.getDatabase(DATABASE).getCollection("${hint}_${counter++}")
        collection.drop()
        collection.insertMany((0 until count).map { n -> document { put("n", n) } })
        return collection
    }

    private suspend fun failPoint(
        client: MongoClient,
        body: Document,
    ) {
        client.getDatabase("admin").runCommand(body)
    }

    private suspend fun clearFailPoint(client: MongoClient) {
        failPoint(
            client,
            document {
                put("configureFailPoint", "failCommand")
                put("mode", "off")
            },
        )
    }

    @Test
    fun `cancelling a call in flight does not lose the client`() =
        runBlocking {
            val client = connect()
            val collection = seeded(client, "cancel_leak", count = 1)

            // Сервер держит команду BLOCK_MILLIS, отмена приходит раньше. Прервать вызов внутри C
            // нечем (риск 2 ресёрча) — но клиент обязан вернуться в пул, когда вызов всё же
            // закончится. Иначе исчерпание пула означало бы неотменяемую блокировку навсегда.
            failPoint(
                client,
                document {
                    put("configureFailPoint", "failCommand")
                    putDocument("mode") { put("times", 1) }
                    putDocument("data") {
                        putArray("failCommands") { add("find") }
                        put("blockConnection", true)
                        put("blockTimeMS", BLOCK_MILLIS)
                    }
                },
            )
            try {
                assertFailsWith<TimeoutCancellationException> {
                    withTimeout(300) { collection.find().toList() }
                }
            } finally {
                clearFailPoint(client)
            }

            // Главное здесь: после отменённой операции клиент жив и пул не отравлен. Операций
            // берётся больше, чем разрешений, — если бы отменённая не вернула клиента,
            // прогон встал бы здесь навсегда.
            repeat(MongoClient.DEFAULT_MAX_CONCURRENT_CLIENTS + 5) {
                assertEquals(1L, collection.countDocuments())
            }
        }

    @Test
    fun `a timeout is not honoured until the blocking call returns`() =
        runBlocking {
            val client = connect()
            val collection = seeded(client, "cancel_latency", count = 1)

            failPoint(
                client,
                document {
                    put("configureFailPoint", "failCommand")
                    putDocument("mode") { put("times", 1) }
                    putDocument("data") {
                        // Именно `aggregate`, а не `count`: `countDocuments` идёт агрегацией
                        // с `$group`, а не одноимённой командой. Первая версия теста блокировала
                        // `count` и не срабатывала вовсе.
                        putArray("failCommands") { add("aggregate") }
                        put("blockConnection", true)
                        put("blockTimeMS", BLOCK_MILLIS)
                    }
                },
            )
            val start = TimeSource.Monotonic.markNow()
            try {
                assertFailsWith<TimeoutCancellationException> {
                    withTimeout(300) { collection.countDocuments() }
                }
            } finally {
                clearFailPoint(client)
            }
            val elapsed = start.elapsedNow()

            // Документированное ограничение (риск 2), а теперь и проверенное: отмена не прерывает
            // вызов, уже ушедший в C. `withTimeout` возвращает управление не через 300 мс,
            // а когда сервер отпустит команду. Тест существует, чтобы это перестало быть
            // утверждением и стало фактом — и чтобы изменение поведения было замечено.
            assertTrue(
                elapsed.inWholeMilliseconds >= BLOCK_MILLIS / 2,
                "отмена вернулась через $elapsed — похоже, вызов всё-таки прерывается",
            )
        }

    @Test
    fun `a broken cursor fails the flow instead of truncating it`() =
        runBlocking {
            val client = connect()
            val collection = seeded(client, "cursor_break")

            // Курсор отдаёт документы порциями; обрываем связь на добирающей команде `getMore`.
            failPoint(
                client,
                document {
                    put("configureFailPoint", "failCommand")
                    putDocument("mode") { put("times", 1) }
                    putDocument("data") {
                        putArray("failCommands") { add("getMore") }
                        put("closeConnection", true)
                    }
                },
            )
            try {
                // Самое опасное поведение здесь — молча отдать часть выборки: потребитель
                // получил бы неполные данные и не узнал об этом. Поток обязан упасть.
                assertFailsWith<MongoException> {
                    collection.find().batchSize(10).toList()
                }
            } finally {
                clearFailPoint(client)
            }
            Unit
        }

    @Test
    fun `the client still works after a broken cursor`() =
        runBlocking {
            val client = connect()
            val collection = seeded(client, "cursor_recover")

            failPoint(
                client,
                document {
                    put("configureFailPoint", "failCommand")
                    putDocument("mode") { put("times", 1) }
                    putDocument("data") {
                        putArray("failCommands") { add("getMore") }
                        put("closeConnection", true)
                    }
                },
            )
            try {
                assertFailsWith<MongoException> { collection.find().batchSize(10).toList() }
            } finally {
                clearFailPoint(client)
            }

            // Оборванный курсор должен освободить и клиента, и разрешение — иначе каждая
            // сетевая неполадка навсегда отъедала бы кусок пула.
            assertEquals(200, collection.find().toList().size)
        }

    @Test
    fun `an error midway through a flow does not deliver a partial list`() =
        runBlocking {
            val client = connect()
            val collection = seeded(client, "partial")

            failPoint(
                client,
                document {
                    put("configureFailPoint", "failCommand")
                    putDocument("mode") { put("times", 1) }
                    putDocument("data") {
                        putArray("failCommands") { add("getMore") }
                        put("closeConnection", true)
                    }
                },
            )
            val collected = mutableListOf<Document>()
            try {
                assertFailsWith<MongoException> {
                    collection.find().batchSize(10).collect { collected += it }
                }
            } finally {
                clearFailPoint(client)
            }

            // Часть документов до обрыва потребитель увидел — это неизбежно и правильно для
            // потока. Важно другое: он узнал об обрыве исключением, а не получил короткий
            // список как полный результат.
            assertTrue(collected.size < 200, "поток отдал всё, хотя связь обрывалась")
        }

    private companion object {
        const val DATABASE = "mongkn_failures"

        /** Сколько сервер держит команду, изображая долгую работу. */
        const val BLOCK_MILLIS = 2_000

        var counter = 0
    }
}
