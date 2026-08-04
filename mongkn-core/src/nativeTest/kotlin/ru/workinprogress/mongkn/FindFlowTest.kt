package ru.workinprogress.mongkn

import ru.workinprogress.mongkn.bson.BsonInt32
import ru.workinprogress.mongkn.bson.Document
import ru.workinprogress.mongkn.bson.document
import ru.workinprogress.mongkn.support.TestServer
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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Чейнинг опций у `find` и новые опции операций (M-34).
 *
 * Проверяется против настоящего сервера: `limit` и `skip` реализует он, а не мы, — проверять
 * их конструированием документа опций значило бы проверять собственную сборку строки.
 */
@OptIn(ExperimentalForeignApi::class)
class FindFlowTest {

    private val uri = TestServer.uri("serverSelectionTimeoutMS=3000&socketTimeoutMS=5000")

    private val clients = mutableListOf<MongoClient>()

    @AfterTest
    fun tearDown() {
        clients.forEach { it.close() }
        clients.clear()
    }

    private suspend fun connect(): MongoClient = MongoClient(uri).also { client ->
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

    private suspend fun seeded(hint: String, count: Int = 10): MongoCollection<Document> {
        val collection = connect().getDatabase(DATABASE).getCollection("${hint}_${counter++}")
        collection.insertMany((0 until count).map { n -> document { put("n", n) } })
        return collection
    }

    private fun numbers(documents: List<Document>) = documents.map { (it["n"] as BsonInt32).value }

    @Test
    fun `limit and skip narrow the result`() = runTest {
        val collection = seeded("paging")
        val ascending = document { put("n", 1) }

        assertEquals(listOf(0, 1, 2), numbers(collection.find().sort(ascending).limit(3).toList()))
        assertEquals(listOf(7, 8, 9), numbers(collection.find().sort(ascending).skip(7).toList()))
        assertEquals(listOf(4, 5), numbers(collection.find().sort(ascending).skip(4).limit(2).toList()))
    }

    @Test
    fun `sort works both ways`() = runTest {
        val collection = seeded("sorting", count = 4)

        assertEquals(listOf(0, 1, 2, 3), numbers(collection.find().sort(document { put("n", 1) }).toList()))
        assertEquals(listOf(3, 2, 1, 0), numbers(collection.find().sort(document { put("n", -1) }).toList()))
    }

    @Test
    fun `projection drops the fields it was not asked for`() = runTest {
        val collection = seeded("projection", count = 1)

        val document = collection.find().projection(document { put("n", 1); put("_id", 0) }).toList().single()

        assertEquals(listOf("n"), document.keys)
    }

    @Test
    fun `repeating an option replaces it instead of sending two`() = runTest {
        val collection = seeded("replace")

        // BsonDocument допускает повторяющиеся ключи, а mongoc в опциях их не ждёт: без замены
        // сюда уехал бы документ с двумя limit.
        assertEquals(2, collection.find().limit(5).limit(2).toList().size)
    }

    @Test
    fun `the chain is reusable because every step returns a new flow`() = runTest {
        val collection = seeded("reuse")
        val base = collection.find().sort(document { put("n", 1) })

        assertEquals(listOf(0, 1), numbers(base.limit(2).toList()))
        // Если бы limit менял base на месте, здесь осталось бы два документа.
        assertEquals(10, base.toList().size)
    }

    @Test
    fun `unordered insertMany keeps going after a duplicate key`() = runTest {
        val collection = connect().getDatabase(DATABASE).getCollection("unordered_${counter++}")
        collection.insertOne(document { put("_id", 2) })

        val documents = (1..3).map { n -> document { put("_id", n) } }
        // Один из трёх — дубликат. При ordered = false остальные всё равно должны лечь.
        runCatching { collection.insertMany(documents, ordered = false) }

        assertEquals(3L, collection.countDocuments())
    }

    @Test
    fun `upsert creates a document when nothing matched`() = runTest {
        val collection = connect().getDatabase(DATABASE).getCollection("upsert_${counter++}")

        val without = collection.updateOne(document { put("_id", 1) }, document { putDocument("\$set") { put("x", 1) } })
        assertEquals(0L, without.matchedCount)
        assertEquals(null, without.upsertedId)
        assertEquals(0L, collection.countDocuments())

        val with = collection.updateOne(
            document { put("_id", 1) },
            document { putDocument("\$set") { put("x", 1) } },
            upsert = true,
        )
        assertEquals(BsonInt32(1), with.upsertedId)
        assertEquals(1L, collection.countDocuments())
    }

    private companion object {
        const val DATABASE = "mongkn_find"
        var counter = 0
        var cleaned = false
    }
}
