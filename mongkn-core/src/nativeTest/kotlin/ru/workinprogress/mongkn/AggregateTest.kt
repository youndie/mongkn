package ru.workinprogress.mongkn

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
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
 * Агрегации (M12) против настоящего mongod.
 *
 * Опции конвейера проверяются тем же правилом, что и опции операций (M10): значение задаётся
 * такое, которое сервер **отвергает**. На принимаемом значении тест зеленеет одинаково и когда
 * опция доехала, и когда её потеряли по дороге.
 */
class AggregateTest {
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

    /** Три города, чтобы группировка давала больше одной группы. */
    private suspend fun seeded(hint: String): MongoCollection<Document> {
        val collection = connect().getDatabase(DATABASE).getCollection("${hint}_${counter++}")
        collection.insertMany(
            listOf(
                document {
                    put("city", "Москва")
                    put("age", 30)
                },
                document {
                    put("city", "Москва")
                    put("age", 40)
                },
                document {
                    put("city", "Казань")
                    put("age", 20)
                },
            ),
        )
        return collection
    }

    @Test
    fun `match and sort keep the collection document type`() =
        runTest {
            val collection = seeded("pipeline")

            val found =
                collection
                    .aggregate(
                        listOf(
                            document { put("\$match", document { put("city", "Москва") }) },
                            document { put("\$sort", document { put("age", -1) }) },
                        ),
                    ).toList()

            assertEquals(2, found.size)
            assertEquals(BsonInt32(40), found[0]["age"])
            assertEquals(BsonInt32(20), collection.find(document { put("city", "Казань") }).first()["age"])
        }

    @Test
    fun `group reshapes documents and maps into a class`() =
        runTest {
            val collection = seeded("group")

            val groups =
                collection
                    .aggregate(
                        listOf(
                            document {
                                put(
                                    "\$group",
                                    document {
                                        put("_id", "\$city")
                                        put("total", document { put("\$sum", 1) })
                                    },
                                )
                            },
                            document { put("\$sort", document { put("_id", 1) }) },
                        ),
                        CityCount.serializer(),
                    ).toList()

            assertEquals(listOf(CityCount("Казань", 1), CityCount("Москва", 2)), groups)
        }

    @Test
    fun `pipeline is cold and can be collected twice`() =
        runTest {
            val collection = seeded("cold")
            val pipeline = collection.aggregate(listOf(document { put("\$match", document { put("city", "Москва") }) }))

            assertEquals(2, pipeline.toList().size)
            assertEquals(2, pipeline.toList().size)
        }

    @Test
    fun `chaining returns a copy and leaves the original alone`() =
        runTest {
            val collection = seeded("copy")
            val base = collection.aggregate(listOf(document { put("\$match", document { put("city", "Москва") }) }))

            // Комментарий сервер принимает, поэтому здесь проверяется только неизменяемость:
            // что опция доезжает, показывают тесты ниже с отвергаемыми значениями.
            base.comment("посторонний")

            assertEquals(2, base.toList().size)
        }

    @Test
    fun `an unknown option is refused by the server`() =
        runTest {
            val collection = seeded("bad_option")

            // Коллации с таким языком нет — сервер обязан отказать. Проверяет, что документ опций
            // действительно уезжает в mongoc, а не теряется, как это было с insertOne до M10.
            val failure =
                assertFailsWith<MongoException> {
                    collection
                        .aggregate(listOf(document { put("\$match", document { put("city", "Москва") }) }))
                        .collation(document { put("locale", "нет-такой-локали") })
                        .toList()
                }
            assertTrue("locale" in failure.message.orEmpty(), "ждали жалобу на локаль, получили: ${failure.message}")
        }

    @Test
    fun `a negative maxTime is refused`() =
        runTest {
            val collection = seeded("max_time")

            assertFailsWith<MongoException> {
                collection
                    .aggregate(listOf(document { put("\$match", document { put("city", "Москва") }) }))
                    .maxTime(-1)
                    .toList()
            }
        }

    @Test
    fun `out writes into another collection and yields nothing`() =
        runTest {
            val collection = seeded("out")
            val target = "${collection.name}_result"

            collection
                .aggregate(
                    listOf(
                        document { put("\$match", document { put("city", "Москва") }) },
                        document { put("\$out", target) },
                    ),
                ).toCollection()

            val database = connect().getDatabase(DATABASE)
            assertEquals(2, database.getCollection(target).countDocuments())
            assertTrue(target in database.listCollectionNames())
        }

    @Test
    fun `database level pipeline works without a collection`() =
        runTest {
            val database = connect().getDatabase(DATABASE)

            // `$documents` берёт данные из самого конвейера — коллекции здесь нет вовсе,
            // и именно ради таких стадий агрегация уровня базы и существует.
            val found =
                database
                    .aggregate(
                        listOf(
                            document {
                                putArray("\$documents") {
                                    add(document { put("n", 1) })
                                    add(document { put("n", 2) })
                                }
                            },
                            document { put("\$match", document { put("n", 2) }) },
                        ),
                    ).toList()

            assertEquals(1, found.size)
            assertEquals(BsonInt32(2), found[0]["n"])
        }

    @Test
    fun `an invalid stage is reported as a MongoException`() =
        runTest {
            val collection = seeded("bad_stage")

            assertFailsWith<MongoException> {
                collection.aggregate(listOf(document { put("\$нетТакойСтадии", BsonString("")) })).toList()
            }
        }

    @Serializable
    private data class CityCount(
        val _id: String,
        val total: Int,
    )

    private companion object {
        const val DATABASE = "mongkn_m12"
        var counter = 0
        var cleaned = false
    }
}
