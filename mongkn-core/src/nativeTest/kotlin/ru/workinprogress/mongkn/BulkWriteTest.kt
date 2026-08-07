package ru.workinprogress.mongkn

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import ru.workinprogress.mongkn.bson.BsonInt32
import ru.workinprogress.mongkn.bson.BsonString
import ru.workinprogress.mongkn.bson.Document
import ru.workinprogress.mongkn.bson.document
import ru.workinprogress.mongkn.support.AppNames
import ru.workinprogress.mongkn.support.TestServer
import ru.workinprogress.mongkn.support.bindableUri
import ru.workinprogress.mongkn.support.boundTo
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Пакетная запись (M-61) против настоящего mongod. */
class BulkWriteTest {
    private val uri = TestServer.uri("serverSelectionTimeoutMS=3000&socketTimeoutMS=5000")

    private val clients = mutableListOf<MongoClient>()

    @AfterTest
    fun tearDown() {
        clients.forEach { it.close() }
        clients.clear()
    }

    private val appNames = AppNames("bulkwrite")

    private suspend fun connect(): MongoClient {
        val appName = appNames.assign()
        val client = MongoClient(bindableUri(uri, appName))
        clients += client
        appNames.remember(client, appName)
        if (!cleaned) {
            client.getDatabase(DATABASE).drop()
            cleaned = true
        }
        return client
    }

    /** Коллекция с документами `{n: 0..count-1}`. */
    private suspend fun seeded(
        hint: String,
        count: Int = 3,
    ): MongoCollection<Document> {
        val collection = connect().getDatabase(DATABASE).getCollection("${hint}_${counter++}")
        if (count > 0) collection.insertMany((0 until count).map { n -> document { put("n", n) } })
        return collection
    }

    private suspend fun numbers(collection: MongoCollection<Document>): List<Int> =
        collection
            .find()
            .toList()
            .map { (it["n"] as BsonInt32).value }
            .sorted()

    @Test
    fun `an empty request list is refused before reaching the server`() =
        runTest {
            val collection = seeded("empty", count = 0)

            assertFailsWith<IllegalArgumentException> { collection.bulkWrite(emptyList()) }
        }

    @Test
    fun `mixed operations are all applied and counted`() =
        runTest {
            val collection = seeded("mixed")

            val result =
                collection.bulkWrite(
                    listOf(
                        InsertOneModel(document { put("n", 100) }),
                        UpdateOneModel(document { put("n", 0) }, document { put("\$set", document { put("n", 10) }) }),
                        UpdateManyModel(
                            document { put("n", document { put("\$gte", 10) }) },
                            document { put("\$inc", document { put("n", 1) }) },
                        ),
                        DeleteOneModel(document { put("n", 1) }),
                    ),
                )

            assertEquals(1, result.insertedCount)
            assertEquals(1, result.deletedCount)
            // updateOne задел один документ, updateMany — два (n=10 и n=100).
            assertEquals(3, result.matchedCount)
            assertEquals(3, result.modifiedCount)
            assertContentEquals(listOf(2, 11, 101), numbers(collection))
        }

    @Test
    fun `inserted ids are reported by request position`() =
        runTest {
            val collection = seeded("ids", count = 0)

            val result =
                collection.bulkWrite(
                    listOf(
                        DeleteManyModel(document { put("n", -1) }),
                        InsertOneModel(document { put("n", 1) }),
                        InsertOneModel(document { put("n", 2) }),
                    ),
                )

            // Ключ — позиция в списке запросов, а не номер среди вставленных: удаление занимает
            // нулевую позицию, поэтому вставки начинаются с первой.
            assertEquals(setOf(1, 2), result.insertedIds.keys)
            val stored =
                collection
                    .find()
                    .toList()
                    .map { it["_id"] }
                    .toSet()
            assertEquals(stored, result.insertedIds.values.toSet())
        }

    @Test
    fun `an explicit id is kept instead of a generated one`() =
        runTest {
            val collection = seeded("explicit_id", count = 0)

            val result =
                collection.bulkWrite(
                    listOf(
                        InsertOneModel(
                            document {
                                put("_id", "свой")
                                put("n", 1)
                            },
                        ),
                    ),
                )

            assertEquals(BsonString("свой"), result.insertedIds[0])
        }

    @Test
    fun `upserted ids are reported by request position`() =
        runTest {
            val collection = seeded("upsert", count = 0)

            val result =
                collection.bulkWrite(
                    listOf(
                        UpdateOneModel(
                            document { put("n", 7) },
                            document { put("\$set", document { put("tag", "новый") }) },
                            upsert = true,
                        ),
                        UpdateOneModel(
                            document { put("n", 8) },
                            document { put("\$set", document { put("tag", "тоже") }) },
                            upsert = false,
                        ),
                    ),
                )

            assertEquals(1, result.upsertedCount)
            assertEquals(setOf(0), result.upsertedIds.keys)
        }

    @Test
    fun `replaceOne swaps the whole document`() =
        runTest {
            val collection = seeded("replace")

            collection.bulkWrite(
                listOf(
                    ReplaceOneModel(
                        document { put("n", 0) },
                        document {
                            put("n", 0)
                            put("заменён", true)
                        },
                    ),
                ),
            )

            val replaced = collection.find(document { put("n", 0) }).toList().single()
            assertTrue("заменён" in replaced)
        }

    @Test
    fun `an ordered batch stops at the first failure`() =
        runTest {
            val collection = seeded("ordered", count = 0)

            assertFailsWith<MongoException> {
                collection.bulkWrite(
                    listOf(
                        InsertOneModel(
                            document {
                                put("_id", 1)
                                put("n", 1)
                            },
                        ),
                        InsertOneModel(
                            document {
                                put("_id", 1)
                                put("n", 2)
                            },
                        ),
                        InsertOneModel(
                            document {
                                put("_id", 3)
                                put("n", 3)
                            },
                        ),
                    ),
                )
            }

            // Первая операция уже применена, третья — нет: ordered останавливается на ошибке.
            assertContentEquals(listOf(1), numbers(collection))
        }

    @Test
    fun `an unordered batch keeps going after a failure`() =
        runTest {
            val collection = seeded("unordered", count = 0)

            assertFailsWith<MongoException> {
                collection.bulkWrite(
                    listOf(
                        InsertOneModel(
                            document {
                                put("_id", 1)
                                put("n", 1)
                            },
                        ),
                        InsertOneModel(
                            document {
                                put("_id", 1)
                                put("n", 2)
                            },
                        ),
                        InsertOneModel(
                            document {
                                put("_id", 3)
                                put("n", 3)
                            },
                        ),
                    ),
                    ordered = false,
                )
            }

            assertContentEquals(listOf(1, 3), numbers(collection))
        }

    @Test
    fun `a malformed update is refused before reaching the server`() =
        runTest {
            val collection = seeded("malformed")

            // Документ без операторов — это замена, а не обновление. libmongoc ловит это
            // на наборе операции, ещё до отправки; проверяем, что мы этот отказ не проглатываем.
            assertFailsWith<MongoException> {
                collection.bulkWrite(
                    listOf(UpdateOneModel(document { put("n", 0) }, document { put("n", 99) })),
                )
            }
        }

    @Test
    fun `collection level write concern reaches the batch`() =
        runTest {
            val collection = seeded("concern", count = 0)

            assertFailsWith<MongoException> {
                collection
                    .withWriteConcern(writeConcern(BsonString("нетТакогоРежима")))
                    .bulkWrite(listOf(InsertOneModel(document { put("n", 1) })))
            }
        }

    @Test
    fun `an unordered failure reports what did apply`() =
        runTest {
            val collection = seeded("partial", count = 0)

            val failure =
                assertFailsWith<MongoBulkWriteException> {
                    collection.bulkWrite(
                        listOf(
                            InsertOneModel(
                                document {
                                    put("_id", 1)
                                    put("n", 1)
                                },
                            ),
                            InsertOneModel(
                                document {
                                    put("_id", 1)
                                    put("n", 2)
                                },
                            ),
                            InsertOneModel(
                                document {
                                    put("_id", 3)
                                    put("n", 3)
                                },
                            ),
                        ),
                        ordered = false,
                    )
                }

            // Две операции из трёх применились, и об этом теперь можно узнать из самого
            // исключения, а не перечитыванием коллекции.
            assertEquals(2, failure.result.insertedCount)
            assertContentEquals(listOf(1, 3), numbers(collection))

            val error = failure.writeErrors.single()
            assertEquals(1, error.index, "позиция отказавшего запроса в списке")
            assertEquals(DUPLICATE_KEY, error.code)
        }

    @Test
    fun `ids of failed inserts are not reported as inserted`() =
        runTest {
            val collection = seeded("partial_ids", count = 0)
            collection.insertOne(
                document {
                    put("_id", 7)
                    put("n", 7)
                },
            )

            val failure =
                assertFailsWith<MongoBulkWriteException> {
                    collection.bulkWrite(
                        listOf(
                            InsertOneModel(
                                document {
                                    put("_id", 7)
                                    put("n", 70)
                                },
                            ),
                            InsertOneModel(
                                document {
                                    put("_id", 8)
                                    put("n", 8)
                                },
                            ),
                        ),
                        ordered = false,
                    )
                }

            // `insertedIds` обязан описывать то, что в базе, а не то, что мы пытались вставить:
            // иначе по нему нельзя было бы понять, какие документы существуют.
            assertEquals(setOf(1), failure.result.insertedIds.keys)
        }

    @Test
    fun `a bulk failure is still an ordinary MongoException`() =
        runTest {
            val collection = seeded("compat", count = 0)

            // Расширение не должно менять поведение того, кто о нём не знает.
            assertFailsWith<MongoException> {
                collection.bulkWrite(
                    listOf(
                        InsertOneModel(document { put("_id", 1) }),
                        InsertOneModel(document { put("_id", 1) }),
                    ),
                )
            }
        }

    @Test
    fun `insertMany reports what did apply when unordered`() =
        runTest {
            val collection = seeded("insert_many_partial", count = 0)

            // `insertMany` — такая же пакетная запись, и при `ordered = false` сервер точно так же
            // продолжает после отказа. До M-80 отсюда летело обычное [MongoException],
            // и применённая часть терялась: одинаковый случай, два разных исключения.
            val failure =
                assertFailsWith<MongoBulkWriteException> {
                    collection.insertMany(
                        listOf(
                            document { put("_id", 1) },
                            document { put("_id", 1) },
                            document { put("_id", 3) },
                        ),
                        ordered = false,
                    )
                }

            assertEquals(2, failure.result.insertedCount)
            assertContentEquals(listOf(1, 3), collection.find().toList().map { (it["_id"] as BsonInt32).value })

            val error = failure.writeErrors.single()
            assertEquals(1, error.index, "позиция отказавшего документа в списке")
            assertEquals(DUPLICATE_KEY, error.code)

            // Идентификаторы отказавших вставок в применённые не попадают — то же правило,
            // что и у `bulkWrite`.
            assertEquals(
                listOf(0, 2),
                failure.result.insertedIds.keys
                    .sorted(),
            )
        }

    @Test
    fun `a bulk failure carries the labels the server sent`() =
        runTest {
            val client = connect()
            val collection = client.getDatabase(DATABASE).getCollection("labels_${counter++}")
            val admin = client.getDatabase("admin")

            // Метки — единственный надёжный признак того, что операцию стоит повторить, и по ним
            // же `withTransaction` решает судьбу транзакции. Пакетное исключение их не несло,
            // и заметить это можно было только здесь: обычное исключение метки несёт с M-73.
            admin.runCommand(
                document {
                    put("configureFailPoint", "failCommand")
                    putDocument("mode") { put("times", 1) }
                    putDocument("data") {
                        putArray("failCommands") { add("insert") }
                        put("errorCode", WRITE_CONFLICT)
                        putArray("errorLabels") { add("TransientTransactionError") }
                    }
                }.boundTo(appNames.of(client)),
            )
            try {
                val failure =
                    assertFailsWith<MongoBulkWriteException> {
                        collection.bulkWrite(listOf(InsertOneModel(document { put("_id", 1) })))
                    }
                assertTrue(failure.isTransientTransaction, "метки потеряны: ${failure.labels}")
            } finally {
                admin.runCommand(
                    document {
                        put("configureFailPoint", "failCommand")
                        put("mode", "off")
                    },
                )
            }
        }

    private companion object {
        const val DATABASE = "mongkn_bulk"

        /** Код MongoDB для дубликата ключа. */
        const val DUPLICATE_KEY: UInt = 11000u

        /** `WriteConflict` — код, который сервер сам помечает как временный. */
        const val WRITE_CONFLICT = 112
        var counter = 0
        var cleaned = false
    }
}
