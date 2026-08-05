package ru.workinprogress.mongkn

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import ru.workinprogress.mongkn.bson.BsonDocument
import ru.workinprogress.mongkn.bson.BsonInt32
import ru.workinprogress.mongkn.bson.BsonString
import ru.workinprogress.mongkn.bson.Document
import ru.workinprogress.mongkn.bson.document
import ru.workinprogress.mongkn.support.TestServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Потоки изменений (M-60) против настоящего mongod.
 *
 * **Требуют replica set.** Change streams на standalone-сервере не работают вовсе — это правило
 * MongoDB, а не наше ограничение. Поэтому тестовый сервер поднят как одноузловой replica set;
 * без него эти тесты падают, а не пропускаются, как и весь остальной интеграционный набор.
 *
 * Устроены иначе прочих: поток бесконечен, поэтому собирать его приходится в отдельной корутине
 * и всегда под таймаутом. Тест, который просто позвал `toList()`, здесь висел бы вечно.
 *
 * Отсюда же `runBlocking`, а не `runTest`, как во всём остальном наборе. `runTest` подменяет время
 * виртуальным: `delay` возвращается мгновенно, а `withTimeout` может сработать, пока подписка ещё
 * идёт. Подписка живёт на настоящем потоке и настоящих миллисекундах, и сверять её с виртуальными
 * часами значило бы получать зелёные тесты при сломанном коде и красные при исправном.
 */
class ChangeStreamTest {
    private val uri = TestServer.uri("serverSelectionTimeoutMS=3000&socketTimeoutMS=5000")

    private val clients = mutableListOf<MongoClient>()

    @AfterTest
    fun tearDown() {
        clients.forEach { it.close() }
        clients.clear()
    }

    private suspend fun connect(ioThreads: Int = MongoClient.DEFAULT_IO_THREADS): MongoClient =
        MongoClient(uri, ioThreads = ioThreads).also { client ->
            clients += client
            if (!cleaned) {
                client.getDatabase(DATABASE).drop()
                cleaned = true
            }
        }

    private suspend fun collection(hint: String): MongoCollection<Document> {
        val database = connect().getDatabase(DATABASE)
        val name = "${hint}_${counter++}"
        // Коллекция должна существовать до подписки: подписка на несуществующую коллекцию
        // событий не увидит, а тест повиснет на таймауте и будет выглядеть как поломка кода.
        database.createCollection(name)
        return database.getCollection(name)
    }

    @Test
    fun `an insert shows up as an event`() =
        runBlocking {
            val collection = collection("insert")

            val events =
                async {
                    withTimeout(TIMEOUT) { collection.watch().take(1).toList() }
                }
            delay(SETTLE)
            collection.insertOne(document { put("n", 1) })

            val event = events.await().single()
            assertEquals(BsonString("insert"), event["operationType"])
            assertEquals(BsonInt32(1), (event["fullDocument"] as BsonDocument)["n"])
        }

    @Test
    fun `several changes arrive in order`() =
        runBlocking {
            val collection = collection("order")

            val events =
                async {
                    withTimeout(TIMEOUT) { collection.watch().take(3).toList() }
                }
            delay(SETTLE)
            collection.insertOne(document { put("n", 1) })
            collection.updateOne(document { put("n", 1) }, document { put("\$set", document { put("n", 2) }) })
            collection.deleteOne(document { put("n", 2) })

            assertEquals(
                listOf("insert", "update", "delete"),
                events.await().map { (it["operationType"] as BsonString).value },
            )
        }

    @Test
    fun `a pipeline filters events server side`() =
        runBlocking {
            val collection = collection("filtered")

            val events =
                async {
                    withTimeout(TIMEOUT) {
                        collection
                            .watch(listOf(document { put("\$match", document { put("operationType", "delete") }) }))
                            .take(1)
                            .toList()
                    }
                }
            delay(SETTLE)
            collection.insertOne(document { put("n", 1) })
            collection.deleteOne(document { put("n", 1) })

            // Вставка отфильтрована сервером — первым и единственным событием пришло удаление.
            assertEquals(BsonString("delete"), events.await().single()["operationType"])
        }

    @Test
    fun `updateLookup adds the current document to update events`() =
        runBlocking {
            val collection = collection("lookup")
            collection.insertOne(document { put("n", 1) })

            val events =
                async {
                    withTimeout(TIMEOUT) {
                        collection
                            .watch()
                            .fullDocument("updateLookup")
                            .take(1)
                            .toList()
                    }
                }
            delay(SETTLE)
            collection.updateOne(document { put("n", 1) }, document { put("\$set", document { put("n", 5) }) })

            val event = events.await().single()
            // Без updateLookup сервер прислал бы только описание изменения, без самого документа.
            assertEquals(BsonInt32(5), (event["fullDocument"] as BsonDocument)["n"])
        }

    @Test
    fun `a resume token lets a new stream continue where the old one stopped`() =
        runBlocking {
            val collection = collection("resume")

            val first =
                async {
                    withTimeout(TIMEOUT) { collection.watch().take(1).toList() }
                }
            delay(SETTLE)
            collection.insertOne(document { put("n", 1) })
            val token = first.await().single()["_id"] as BsonDocument

            collection.insertOne(document { put("n", 2) })

            // Событие про n=2 случилось, пока никто не слушал: продолжение по токену обязано
            // его увидеть, иначе токен бесполезен.
            val resumed =
                withTimeout(TIMEOUT) {
                    collection
                        .watch()
                        .resumeAfter(token)
                        .take(1)
                        .toList()
                }
            assertEquals(BsonInt32(2), (resumed.single()["fullDocument"] as BsonDocument)["n"])
        }

    @Test
    fun `the stream waits instead of ending when nothing happens`() =
        runBlocking {
            val collection = collection("idle")

            // Курсор не должен закончиться на пустом окне ожидания: `next` возвращает false
            // и когда событий нет, и это самая вероятная ошибка реализации.
            val result = withTimeoutOrNull(IDLE) { collection.watch().take(1).toList() }

            assertNull(result, "поток завершился сам, хотя изменений не было")
        }

    @Test
    fun `cancelling the collector stops the subscription`() =
        runBlocking {
            val collection = collection("cancel")

            val started = launch { collection.watch().collect { } }
            delay(SETTLE)

            // Отмена замечается между витками ожидания, то есть не позже maxAwaitTimeMS.
            // Без этой проверки корутина висела бы до первого события — то есть вечно.
            val stopped = withTimeoutOrNull(TIMEOUT) { started.cancelAndJoin() }
            assertNotNull(stopped, "подписка не остановилась после отмены")
        }

    @Test
    fun `subscriptions do not starve regular operations`() =
        runBlocking {
            // Потоков в общем пуле намеренно **меньше**, чем подписок, и число задано явно,
            // а не взято из умолчания: иначе тест ослабевал бы каждый раз, когда умолчание
            // растёт, — ровно это и случилось бы после M-78, где оно поднялось с 4 до 32.
            val client = connect(ioThreads = 2)
            val database = client.getDatabase(DATABASE)
            val names = (0 until 5).map { "starve_${counter++}" }
            names.forEach { database.createCollection(it) }

            // Если бы подписки жили на общем пуле, они заняли бы его целиком, обычная операция
            // ниже не получила бы потока и тест повис бы.
            val watchers = names.map { name -> launch { database.getCollection(name).watch().collect { } } }
            delay(SETTLE)

            val alive = withTimeoutOrNull(TIMEOUT) { database.getCollection(names.first()).countDocuments() }
            assertEquals(0L, alive, "обычная операция не прошла, пока живы подписки")

            watchers.forEach { it.cancelAndJoin() }
        }

    @Test
    fun `a database level stream sees changes in any collection`() =
        runBlocking {
            val database = connect().getDatabase(DATABASE)
            val name = "db_level_${counter++}"
            database.createCollection(name)

            val events =
                async {
                    withTimeout(TIMEOUT) { database.watch().take(1).toList() }
                }
            delay(SETTLE)
            database.getCollection(name).insertOne(document { put("n", 1) })

            val event = events.await().single()
            assertEquals(BsonString("insert"), event["operationType"])
            assertTrue("ns" in event, "событие уровня базы обязано нести пространство имён")
        }

    /**
     * Заказывает серверу один сбой команды `getMore` и снимает заказ после [body].
     *
     * `getMore` — то, чем поток изменений добирает события после первого ответа; сломать надо
     * именно её, а не создание потока.
     *
     * @param labels метки ошибки. `ResumableChangeStreamError` — единственное, что отличает
     *   возобновляемый сбой от смертельного на сервере 4.4 и новее.
     */
    private suspend fun withGetMoreFailure(
        client: MongoClient,
        errorCode: Int,
        labels: List<String>,
        body: suspend () -> Unit,
    ) {
        val admin = client.getDatabase("admin")
        admin.runCommand(
            document {
                put("configureFailPoint", "failCommand")
                putDocument("mode") { put("times", 1) }
                putDocument("data") {
                    putArray("failCommands") { add("getMore") }
                    put("errorCode", errorCode)
                    if (labels.isNotEmpty()) putArray("errorLabels") { labels.forEach(::add) }
                }
            },
        )
        try {
            body()
        } finally {
            admin.runCommand(
                document {
                    put("configureFailPoint", "failCommand")
                    put("mode", "off")
                },
            )
        }
    }

    @Test
    fun `the stream resumes itself after a resumable failure`() =
        runBlocking {
            val collection = collection("resumable")
            val client = clients.last()

            val events = async { withTimeout(TIMEOUT) { collection.watch().take(2).toList() } }
            delay(SETTLE)
            collection.insertOne(document { put("n", 1) })
            delay(SETTLE)

            withGetMoreFailure(client, RESUMABLE_CODE, listOf("ResumableChangeStreamError")) {
                delay(SETTLE)
                collection.insertOne(document { put("n", 2) })

                // Возобновление делает **сам libmongoc** — вопреки записи M-72, утверждавшей
                // обратное. Наш код здесь ничего не предпринимает, и это проверка, что он и не
                // должен: событие после сбоя приходит, поток не заканчивается.
                val seen = events.await().map { (it["fullDocument"] as BsonDocument)["n"] }
                assertEquals(listOf(BsonInt32(1), BsonInt32(2)), seen)
            }
        }

    @Test
    fun `a failure without the resumable label ends the stream`() =
        runBlocking {
            val collection = collection("not_resumable")
            val client = clients.last()

            val events = async { runCatching { withTimeout(TIMEOUT) { collection.watch().take(2).toList() } } }
            delay(SETTLE)
            collection.insertOne(document { put("n", 1) })
            delay(SETTLE)

            withGetMoreFailure(client, RESUMABLE_CODE, emptyList()) {
                delay(SETTLE)
                collection.insertOne(document { put("n", 2) })

                // Оборотная сторона предыдущего теста: без метки сбой смертелен, и поток отдаёт
                // ошибку. Без этой проверки первый тест был бы зелёным и в случае, если бы
                // failpoint не сработал вовсе.
                val outcome = events.await()
                assertTrue(outcome.isFailure, "ждали ошибку потока, получили ${outcome.getOrNull()}")
                assertTrue(outcome.exceptionOrNull() is MongoException, "ждали MongoException")
            }
        }

    private companion object {
        const val DATABASE = "mongkn_watch"

        /** `NotWritablePrimary` — сервер помечает её возобновляемой, когда сам того хочет. */
        const val RESUMABLE_CODE = 10107

        /** Сколько ждём события, прежде чем считать это поломкой. */
        const val TIMEOUT = 15_000L

        /** Сколько ждём, убеждаясь, что поток **не** закончился сам. */
        const val IDLE = 2_000L

        /** Пауза, чтобы подписка успела дойти до сервера раньше изменений. */
        const val SETTLE = 700L

        var counter = 0
        var cleaned = false
    }
}
