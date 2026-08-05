package ru.workinprogress.mongkn

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import ru.workinprogress.mongkn.bson.BsonBoolean
import ru.workinprogress.mongkn.bson.BsonString
import ru.workinprogress.mongkn.bson.Document
import ru.workinprogress.mongkn.bson.document
import ru.workinprogress.mongkn.support.TestServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Индексы (M13) против настоящего mongod. */
class IndexTest {
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
            if (!cleaned) {
                client.getDatabase(DATABASE).drop()
                cleaned = true
            }
        }

    private suspend fun collection(hint: String): MongoCollection<Document> {
        val collection = connect().getDatabase(DATABASE).getCollection("${hint}_${counter++}")
        collection.insertOne(document { put("city", "Москва") })
        return collection
    }

    private suspend fun names(collection: MongoCollection<Document>): List<String> =
        collection.listIndexes().toList().map { (it["name"] as BsonString).value }

    @Test
    fun `a new collection has only the id index`() =
        runTest {
            assertEquals(listOf("_id_"), names(collection("bare")))
        }

    @Test
    fun `createIndex returns the name the server used`() =
        runTest {
            val collection = collection("simple")

            val name = collection.createIndex(document { put("city", 1) })

            assertEquals("city_1", name)
            assertTrue(name in names(collection))
        }

    @Test
    fun `a compound index is named after every key in order`() =
        runTest {
            val collection = collection("compound")

            val name =
                collection.createIndex(
                    document {
                        put("city", 1)
                        put("age", -1)
                    },
                )

            assertEquals("city_1_age_-1", name)
            assertTrue(name in names(collection))
        }

    @Test
    fun `an explicit name wins over the derived one`() =
        runTest {
            val collection = collection("named")

            val name = collection.createIndex(document { put("city", 1) }, document { put("name", "по_городу") })

            assertEquals("по_городу", name)
            assertTrue(name in names(collection))
        }

    @Test
    fun `createIndexes makes several at once and keeps the order`() =
        runTest {
            val collection = collection("many")

            val created =
                collection.createIndexes(
                    listOf(
                        IndexModel(document { put("city", 1) }),
                        IndexModel(document { put("age", -1) }, document { put("name", "по_возрасту") }),
                    ),
                )

            assertEquals(listOf("city_1", "по_возрасту"), created)
            assertEquals(setOf("_id_", "city_1", "по_возрасту"), names(collection).toSet())
        }

    @Test
    fun `a unique index is actually enforced by the server`() =
        runTest {
            val collection = collection("unique")
            collection.createIndex(document { put("city", 1) }, document { put("unique", true) })

            // Опция проверяется по наблюдаемому эффекту: если бы `unique` потерялся по дороге,
            // вторая вставка прошла бы, и тест на «индекс создался» этого не заметил бы.
            assertFailsWith<MongoException> {
                collection.insertOne(document { put("city", "Москва") })
            }
        }

    @Test
    fun `index options reach the server and are reported back`() =
        runTest {
            val collection = collection("options")
            collection.createIndex(
                document { put("age", 1) },
                document {
                    put("unique", true)
                    put("sparse", true)
                },
            )

            val described = collection.listIndexes().toList().single { it["name"] == BsonString("age_1") }
            assertEquals(BsonBoolean(true), described["unique"])
            assertEquals(BsonBoolean(true), described["sparse"])
        }

    @Test
    fun `dropIndex removes one index by name`() =
        runTest {
            val collection = collection("drop_one")
            collection.createIndex(document { put("city", 1) })
            collection.createIndex(document { put("age", 1) })

            collection.dropIndex("city_1")

            assertEquals(setOf("_id_", "age_1"), names(collection).toSet())
        }

    @Test
    fun `dropIndexByKeys derives the name from the keys`() =
        runTest {
            val collection = collection("drop_keys")
            collection.createIndex(document { put("city", 1) })

            collection.dropIndexByKeys(document { put("city", 1) })

            assertEquals(listOf("_id_"), names(collection))
        }

    @Test
    fun `dropIndexes removes everything but the id index`() =
        runTest {
            val collection = collection("drop_all")
            collection.createIndex(document { put("city", 1) })
            collection.createIndex(document { put("age", 1) })

            collection.dropIndexes()

            assertEquals(listOf("_id_"), names(collection))
        }

    @Test
    fun `dropping an index that does not exist is an error`() =
        runTest {
            val collection = collection("drop_missing")

            assertFailsWith<MongoException> { collection.dropIndex("нет_такого") }
        }

    @Test
    fun `conflicting options on the same keys are refused`() =
        runTest {
            val collection = collection("conflict")
            collection.createIndex(document { put("city", 1) })

            // Тот же ключ с другими опциями — сервер обязан отказать, а не переписать индекс молча.
            assertFailsWith<MongoException> {
                collection.createIndex(document { put("city", 1) }, document { put("unique", true) })
            }
        }

    private companion object {
        const val DATABASE = "mongkn_m13"
        var counter = 0
        var cleaned = false
    }
}
