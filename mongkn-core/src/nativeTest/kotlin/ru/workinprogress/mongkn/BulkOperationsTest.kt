package ru.workinprogress.mongkn

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import mongkn.cinterop.bson_error_t
import mongkn.cinterop.mongoc_client_get_database
import mongkn.cinterop.mongoc_database_destroy
import mongkn.cinterop.mongoc_database_drop
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
 * Операции вехи M9 против настоящего mongod.
 *
 * Все они реализованы «по образцу» соседних, и именно поэтому проверяются отдельно: похожий
 * код легко написать с перепутанным аргументом, и компилятор такого не заметит — `filter`
 * и `update` оба `Document`.
 */
@OptIn(ExperimentalForeignApi::class)
class BulkOperationsTest {
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
                client.withClient { handle ->
                    val database = mongoc_client_get_database(handle, DATABASE)!!
                    try {
                        memScoped { mongoc_database_drop(database, alloc<bson_error_t>().ptr) }
                    } finally {
                        mongoc_database_destroy(database)
                    }
                }
                cleaned = true
            }
        }

    private suspend fun seeded(
        hint: String,
        count: Int = 5,
    ): MongoCollection<Document> {
        val collection = connect().getDatabase(DATABASE).getCollection("${hint}_${counter++}")
        collection.insertMany(
            (0 until count).map { n ->
                document {
                    put("n", n)
                    put("tag", "x")
                }
            },
        )
        return collection
    }

    @Test
    fun `updateMany touches every match while updateOne touches one`() =
        runTest {
            val collection = seeded("update_many")
            val set = document { putDocument("\$set") { put("tag", "y") } }

            val one = collection.updateOne(document { put("tag", "x") }, set)
            assertEquals(1L, one.modifiedCount)

            val many = collection.updateMany(document { put("tag", "x") }, set)
            assertEquals(4L, many.matchedCount)
            assertEquals(4L, many.modifiedCount)
            assertEquals(5L, collection.countDocuments(document { put("tag", "y") }))
        }

    @Test
    fun `deleteMany removes every match`() =
        runTest {
            val collection = seeded("delete_many")

            assertEquals(5L, collection.deleteMany(document { put("tag", "x") }).deletedCount)
            assertEquals(0L, collection.countDocuments())
        }

    @Test
    fun `replaceOne swaps the whole document but keeps the id`() =
        runTest {
            val collection = seeded("replace", count = 1)
            val before = collection.find().toList().single()

            collection.replaceOne(document { put("n", 0) }, document { put("replaced", true) })

            val after = collection.find().toList().single()
            assertEquals(before["_id"], after["_id"], "_id должен сохраниться")
            // Поля tag и n были в исходном документе и обязаны исчезнуть: это замена, а не $set.
            assertEquals(listOf("_id", "replaced"), after.keys)
        }

    @Test
    fun `replaceOne rejects update operators`() =
        runTest {
            val collection = seeded("replace_bad", count = 1)

            // Ошибка сервера, а не наша: проверяем, что она доходит до вызывающего как есть.
            assertFailsWith<MongoException> {
                collection.replaceOne(document { put("n", 0) }, document { putDocument("\$set") { put("x", 1) } })
            }
        }

    @Test
    fun `findOneAndUpdate returns the document before or after the change`() =
        runTest {
            val collection = seeded("find_and_update")
            val set = document { putDocument("\$set") { put("tag", "z") } }

            val before = collection.findOneAndUpdate(document { put("n", 0) }, set)
            assertEquals(BsonString("x"), before!!["tag"], "по умолчанию возвращается документ до изменения")

            val after =
                collection.findOneAndUpdate(document { put("n", 1) }, set, ReturnDocument.AFTER)
            assertEquals(BsonString("z"), after!!["tag"])
        }

    @Test
    fun `findOneAnd operations return null when nothing matched`() =
        runTest {
            val collection = seeded("find_and_miss")
            val absent = document { put("n", 999) }

            assertEquals(null, collection.findOneAndUpdate(absent, document { putDocument("\$set") { put("a", 1) } }))
            assertEquals(null, collection.findOneAndDelete(absent))
            assertEquals(null, collection.findOneAndReplace(absent, document { put("a", 1) }))
        }

    @Test
    fun `findOneAndDelete removes what it returns`() =
        runTest {
            val collection = seeded("find_and_delete")

            val removed = collection.findOneAndDelete(document { put("n", 2) })

            assertEquals(BsonInt32(2), removed!!["n"])
            assertEquals(4L, collection.countDocuments())
            assertEquals(0L, collection.countDocuments(document { put("n", 2) }))
        }

    @Test
    fun `findOneAndReplace swaps the document`() =
        runTest {
            val collection = seeded("find_and_replace")

            val after =
                collection.findOneAndReplace(
                    document { put("n", 0) },
                    document { put("replaced", true) },
                    ReturnDocument.AFTER,
                )

            assertEquals(listOf("_id", "replaced"), after!!.keys)
        }

    @Test
    fun `distinct returns unique values`() =
        runTest {
            val collection = connect().getDatabase(DATABASE).getCollection("distinct_${counter++}")
            collection.insertMany(listOf(1, 1, 2, 3, 3).map { n -> document { put("n", n) } })

            val values = collection.distinct("n").map { (it as BsonInt32).value }.sorted()

            assertEquals(listOf(1, 2, 3), values)
            assertEquals(listOf(1), collection.distinct("n", document { put("n", 1) }).map { (it as BsonInt32).value })
        }

    @Test
    fun `estimatedDocumentCount sees the documents`() =
        runTest {
            val collection = seeded("estimated")

            // Оценка, а не точный подсчёт: на пустой коллекции без нагрузки она совпадает,
            // и именно это здесь проверяется — что операция вообще работает.
            assertEquals(5L, collection.estimatedDocumentCount())
        }

    @Test
    fun `drop removes the collection`() =
        runTest {
            val collection = seeded("dropped")
            assertEquals(5L, collection.countDocuments())

            collection.drop()

            assertEquals(0L, collection.countDocuments())
        }

    @Test
    fun `renameCollection moves the documents`() =
        runTest {
            val client = connect()
            val source = seeded("rename_source")
            val target = "rename_target_${counter++}"

            source.renameCollection(target)

            assertEquals(5L, client.getDatabase(DATABASE).getCollection(target).countDocuments())
            assertTrue(source.countDocuments() == 0L, "по старому имени документов остаться не должно")
        }

    private companion object {
        const val DATABASE = "mongkn_m9"
        var counter = 0
        var cleaned = false
    }
}
