package ru.workinprogress.mongkn

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import ru.workinprogress.mongkn.bson.BsonInt32
import ru.workinprogress.mongkn.bson.BsonString
import ru.workinprogress.mongkn.bson.Document
import ru.workinprogress.mongkn.bson.document
import ru.workinprogress.mongkn.support.TestServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Опции операций (M10) и операции базы (M11) против настоящего mongod.
 *
 * Опции проверяются по **наблюдаемому эффекту**, а не по собранному документу: собрать документ
 * можно и с неверным именем ключа — сервер его молча проигнорирует, и тест на форму этого
 * не заметит.
 */
class OptionsAndDatabaseTest {
    private val uri = TestServer.uri("serverSelectionTimeoutMS=3000&socketTimeoutMS=5000")

    private val clients = mutableListOf<MongoClient>()

    @AfterTest
    fun tearDown() {
        clients.forEach { it.close() }
        clients.clear()
    }

    private suspend fun connect(): MongoClient =
        MongoClient(uri).also { client ->
            clients += client
            // mongod живёт дольше прогона, а счётчик имён начинается с нуля: без чистки второй
            // запуск видит коллекции первого. Грабля общая для всего набора.
            if (!cleaned) {
                client.getDatabase(DATABASE).drop()
                cleaned = true
            }
        }

    private suspend fun seeded(
        hint: String,
        count: Int = 5,
    ): MongoCollection<Document> {
        val collection = connect().getDatabase(DATABASE).getCollection("${hint}_${counter++}")
        // insertMany отвергает пустой список до обращения к серверу — это его контракт,
        // а не повод чинить его ради теста.
        if (count > 0) collection.insertMany((0 until count).map { n -> document { put("n", n) } })
        return collection
    }

    @Test
    fun `hint by index name reaches the server`() =
        runTest {
            val collection = seeded("hint")

            // Несуществующий индекс — сервер обязан возразить. Если бы ключ назывался неверно,
            // он бы его проигнорировал и запрос прошёл: так проверяется, что опция доехала.
            assertFailsWith<MongoException> {
                collection.find().hintString("нет_такого_индекса").toList()
            }
        }

    @Test
    fun `returnKey gives keys instead of documents`() =
        runTest {
            val collection = seeded("return_key", count = 1)

            val document =
                collection
                    .find()
                    .returnKey()
                    .toList()
                    .single()

            // При returnKey сервер отдаёт только ключи индекса; поля n там нет.
            assertTrue("n" !in document, "ожидались только ключи индекса, получено $document")
        }

    @Test
    fun `showRecordId adds the record id`() =
        runTest {
            val collection = seeded("record_id", count = 1)

            val document =
                collection
                    .find()
                    .showRecordId()
                    .toList()
                    .single()

            assertTrue("\$recordId" in document, "recordId не появился: $document")
        }

    @Test
    fun `comment and allowDiskUse are accepted`() =
        runTest {
            val collection = seeded("comment")

            val found =
                collection
                    .find()
                    .comment("mongkn-test")
                    .allowDiskUse()
                    .sort(document { put("n", 1) })
                    .toList()

            assertEquals(5, found.size)
        }

    @Test
    fun `maxTime is passed through and rejected when negative`() =
        runTest {
            val collection = seeded("max_time")

            assertEquals(
                5,
                collection
                    .find()
                    .maxTime(10_000)
                    .toList()
                    .size,
            )
            // Отрицательное значение сервер не примет — значит опция действительно доехала.
            assertFailsWith<MongoException> { collection.find().maxTime(-1).toList() }
        }

    @Test
    fun `collection level write concern is applied to operations`() =
        runTest {
            val collection = seeded("write_concern", count = 0)
            val strict = collection.withWriteConcern(majorityWriteConcern())

            strict.insertOne(document { put("n", 1) })

            assertEquals(1L, collection.countDocuments())
        }

    @Test
    fun `an unknown write concern mode is refused by the server`() =
        runTest {
            val collection = seeded("write_concern_bad", count = 0)

            // Именованный режим, которого на сервере нет. Проверяет, что настройка коллекции
            // действительно уезжает в опции операции, а не остаётся украшением.
            //
            // Числовое `w: 50` для этого не годится: standalone-сервер его принимает молча,
            // и тест зеленел бы независимо от того, доехала опция или нет.
            assertFailsWith<MongoException> {
                collection
                    .withWriteConcern(writeConcern(BsonString("нетТакогоРежима")))
                    .insertOne(document { put("n", 1) })
            }
        }

    @Test
    fun `with methods return a copy and leave the original alone`() =
        runTest {
            val collection = seeded("copy", count = 0)
            val strict = collection.withWriteConcern(majorityWriteConcern())

            assertTrue(collection !== strict, "with* обязан возвращать копию")
            collection.insertOne(document { put("n", 1) })
            assertEquals(1L, strict.countDocuments())
        }

    @Test
    fun `runCommand executes an arbitrary command`() =
        runTest {
            val database = connect().getDatabase(DATABASE)

            val reply = database.runCommand(document { put("ping", 1) })

            assertEquals(1.0, (reply["ok"] as ru.workinprogress.mongkn.bson.BsonDouble).value)
        }

    @Test
    fun `runCommand surfaces server errors`() =
        runTest {
            val database = connect().getDatabase(DATABASE)

            assertFailsWith<MongoException> { database.runCommand(document { put("нетТакойКоманды", 1) }) }
        }

    @Test
    fun `createCollection and listCollectionNames see each other`() =
        runTest {
            val database = connect().getDatabase(DATABASE)
            val name = "created_${counter++}"

            database.createCollection(name)

            assertTrue(name in database.listCollectionNames(), "коллекция не появилась в списке")
        }

    @Test
    fun `listDatabaseNames includes the test database`() =
        runTest {
            val client = connect()
            client.getDatabase(DATABASE).getCollection("touch_${counter++}").insertOne(document { put("n", 1) })

            assertTrue(DATABASE in client.listDatabaseNames())
        }

    @Test
    fun `dropping a database removes its collections`() =
        runTest {
            val client = connect()
            val name = "mongkn_drop_${counter++}"
            val database = client.getDatabase(name)
            database.getCollection("some").insertOne(document { put("n", 1) })
            assertTrue(name in client.listDatabaseNames())

            database.drop()

            assertTrue(name !in client.listDatabaseNames())
        }

    @Test
    fun `createCollection accepts options`() =
        runTest {
            val database = connect().getDatabase(DATABASE)
            val name = "capped_${counter++}"

            database.createCollection(
                name,
                document {
                    put("capped", true)
                    put("size", 4096)
                },
            )

            val stats = database.runCommand(document { put("collStats", name) })
            assertEquals(
                BsonString(name),
                (stats["ns"] as? BsonString)?.let { BsonString(it.value.substringAfter('.')) },
            )
        }

    /**
     * Коллекция на клиенте, который **обнаруживает реплика-сет**.
     *
     * Без `replicaSet` в строке подключения драйвер считает топологию `Single` и, по правилу
     * спецификации SDAM, при выборе сервера предпочтение чтения **игнорирует** — единственный
     * узел годится под любое. Проверять `readPreference` на таком клиенте бессмысленно: тест
     * зеленел бы и с полностью потерянной настройкой. Выяснилось прогоном.
     */
    private suspend fun replicaSetCollection(hint: String): MongoCollection<Document> {
        val client = MongoClient(TestServer.uri("replicaSet=rs0&serverSelectionTimeoutMS=3000"))
        clients += client
        val collection = client.getDatabase(DATABASE).getCollection("${hint}_${counter++}")
        collection.insertMany((0 until 5).map { n -> document { put("n", n) } })
        return collection
    }

    @Test
    fun `a read preference of secondary finds no server on a single node`() =
        runTest {
            val collection = replicaSetCollection("pref_secondary")

            // Вторичных узлов в одноузловом реплика-сете нет, поэтому выбор сервера обязан
            // не удаться. Это и есть доказательство, что предпочтение доехало.
            val failure =
                assertFailsWith<MongoException> {
                    collection.withReadPreference(ReadPreference(ReadPreferenceMode.SECONDARY)).find().toList()
                }

            assertEquals(MongoErrorDomain.SERVER_SELECTION, failure.errorDomain)
        }

    @Test
    fun `a read preference that allows the primary works`() =
        runTest {
            val collection = replicaSetCollection("pref_primary")

            // Оборотная сторона: с тем же механизмом, но разрешающим первичный узел, чтение идёт.
            // Без этого предыдущий тест был бы зелёным и при полностью сломанном чтении.
            val modes =
                listOf(
                    ReadPreferenceMode.PRIMARY,
                    ReadPreferenceMode.PRIMARY_PREFERRED,
                    ReadPreferenceMode.NEAREST,
                )
            for (mode in modes) {
                assertEquals(
                    5,
                    collection
                        .withReadPreference(ReadPreference(mode))
                        .find()
                        .toList()
                        .size,
                    "режим $mode",
                )
            }
        }

    @Test
    fun `a read preference applies to counting and distinct too`() =
        runTest {
            val collection =
                replicaSetCollection("pref_other").withReadPreference(ReadPreference(ReadPreferenceMode.SECONDARY))

            // Предпочтение должно доходить до всех операций чтения, а не только до find.
            assertFailsWith<MongoException> { collection.countDocuments() }
            assertFailsWith<MongoException> { collection.distinct("n") }
            assertFailsWith<MongoException> { collection.aggregate(emptyList()).toList() }
        }

    @Test
    fun `withReadPreference returns a copy`() =
        runTest {
            val collection = replicaSetCollection("pref_copy")

            collection.withReadPreference(ReadPreference(ReadPreferenceMode.SECONDARY))

            // Исходная коллекция настройку не унаследовала и продолжает читать.
            assertEquals(5, collection.find().toList().size)
        }

    private companion object {
        const val DATABASE = "mongkn_m10"
        var counter = 0
        var cleaned = false
    }
}
