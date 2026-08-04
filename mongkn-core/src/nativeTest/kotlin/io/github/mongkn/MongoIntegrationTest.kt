package io.github.mongkn

import io.github.mongkn.bson.BsonInt32
import io.github.mongkn.bson.BsonObjectId
import io.github.mongkn.bson.BsonString
import io.github.mongkn.bson.Document
import io.github.mongkn.bson.document
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import mongkn.cinterop.bson_error_t
import mongkn.cinterop.mongoc_client_get_database
import mongkn.cinterop.mongoc_database_destroy
import mongkn.cinterop.mongoc_database_drop
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Сценарии из [feature-crud-mvp](../../../../../../../docs/features/feature-crud-mvp.md)
 * против настоящего mongod.
 *
 * Требует запущенный сервер на 127.0.0.1:27017:
 * ```
 * docker run -d --name mongkn-it -p 27017:27017 mongo:8
 * ```
 * Без него тесты падают, а не молча зеленеют: интеграционный тест, который проходит без
 * сервера, ничего не проверяет.
 */
@OptIn(ExperimentalForeignApi::class)
class MongoIntegrationTest {

    private val uri = "mongodb://127.0.0.1:27017/?serverSelectionTimeoutMS=3000&socketTimeoutMS=5000"

    private val clients = mutableListOf<MongoClient>()

    private fun connect(uri: String = this.uri): MongoClient =
        MongoClient(uri).also { client ->
            clients += client
            // mongod живёт дольше прогона, а счётчик коллекций начинается с нуля каждый раз —
            // без этого второй прогон видит документы первого и падает на assertEquals(1, …).
            if (!databaseCleaned && uri == this.uri) {
                client.dropTestDatabase()
                databaseCleaned = true
            }
        }

    /**
     * Сносит тестовую базу целиком. Публичного API для этого нет и в скоуп прототипа он не входит,
     * поэтому дёргаем cinterop напрямую: тестовому source set внутренности модуля видны.
     */
    private fun MongoClient.dropTestDatabase() = withClient { handle ->
        val database = mongoc_client_get_database(handle, DATABASE)
            ?: error("mongoc_client_get_database вернул NULL")
        try {
            memScoped {
                val error = alloc<bson_error_t>()
                mongoc_database_drop(database, error.ptr)
            }
        } finally {
            mongoc_database_destroy(database)
        }
    }

    /** Уникальное имя коллекции на каждый тест — иначе тесты видят чужие документы. */
    private fun MongoClient.freshCollection(hint: String): MongoCollection =
        getDatabase(DATABASE).getCollection("${hint}_${collectionCounter++}")

    @AfterTest
    fun tearDown() {
        clients.forEach { it.close() }
        clients.clear()
    }

    @Test
    fun `inserts a document and reads it back`() = runTest {
        val collection = connect().freshCollection("crud")

        val result = collection.insertOne(document { put("name", "kotlin-native") })

        // Сервер сам генерирует _id и возвращает его в reply — ради этого insertOne
        // отдаёт InsertOneResult, а не Boolean (решение Р3).
        assertTrue(result.insertedId is BsonObjectId, "insertedId=${result.insertedId}")

        val documents = collection.find().toList()

        assertEquals(1, documents.size)
        assertEquals(BsonString("kotlin-native"), documents.single()["name"])
        assertEquals(result.insertedId, documents.single()["_id"])
    }

    @Test
    // Имена тестов на Kotlin/Native не могут содержать запятую и точку — компилятор
    // отвергает их как illegal characters в имени.
    fun `explicit id is returned as given rather than regenerated`() = runTest {
        val collection = connect().freshCollection("explicit_id")

        val result = collection.insertOne(document { put("_id", 7) })

        assertEquals(BsonInt32(7), result.insertedId)
    }

    @Test
    fun `filter selects a subset`() = runTest {
        val collection = connect().freshCollection("filter")
        repeat(3) { i -> collection.insertOne(document { put("n", i) }) }

        val matched = collection.find(document { put("n", 1) }).toList()

        assertEquals(1, matched.size)
        assertEquals(BsonInt32(1), matched.single()["n"])
        assertEquals(3, collection.find().count())
    }

    @Test
    fun `duplicate key raises MongoException with the server code`() = runTest {
        val collection = connect().freshCollection("dup")
        val doc = document { put("_id", 1) }
        collection.insertOne(doc)

        val failure = assertFailsWith<MongoException> { collection.insertOne(doc) }

        // Значения не выдуманы: сняты с прогона против mongo:8 при ресёрче (§1.3).
        assertEquals(12u, failure.domain, "ожидался MONGOC_ERROR_COLLECTION")
        assertEquals(11000u, failure.code)
        assertTrue(
            failure.message!!.contains("E11000 duplicate key error collection"),
            "message=${failure.message}",
        )
    }

    @Test
    fun `unreachable server fails within the selection timeout`() = runTest {
        // Порт, на котором заведомо никто не слушает.
        val collection = connect("mongodb://127.0.0.1:1/?serverSelectionTimeoutMS=1000")
            .freshCollection("unreachable")

        val failure = assertFailsWith<MongoException> {
            collection.insertOne(document { put("a", 1) })
        }

        // domain выведен из mongoc_error_domain_t, а не из прогона, — тест и есть та самая сверка,
        // о которой просит M-11.
        assertEquals(15u, failure.domain, "ожидался MONGOC_ERROR_SERVER_SELECTION: ${failure.message}")
    }

    @Test
    fun `cancelling the flow does not break the client`() = runTest {
        val collection = connect().freshCollection("cancel")
        repeat(5) { i -> collection.insertOne(document { put("n", i) }) }

        // take(1) отменяет сбор после первого документа — курсор обязан закрыться в finally.
        val first = collection.find().take(1).toList()
        assertEquals(1, first.size)

        // Если бы курсор или клиент утекли, следующая операция на том же клиенте сломалась бы.
        assertEquals(5, collection.find().count())
    }

    @Test
    fun `empty collection yields an empty flow`() = runTest {
        val collection = connect().freshCollection("empty")

        assertEquals(0, collection.find().count())
    }

    @Test
    fun `nested documents and arrays survive a real round trip through the server`() = runTest {
        val collection = connect().freshCollection("nested")
        val source: Document = document {
            put("name", "outer")
            putDocument("nested") { put("a", 1) }
            putArray("list") {
                add(1)
                add("two")
                addDocument { put("three", true) }
            }
        }

        collection.insertOne(source)
        val stored = collection.find().first()

        // _id сервер добавил сам, поэтому сравниваем поля, а не документ целиком.
        for ((key, value) in source) {
            assertEquals(value, stored[key], "поле \"$key\"")
        }
    }

    @Test
    fun `closed client rejects further operations`() = runTest {
        val client = connect()
        val collection = client.freshCollection("closed")
        collection.insertOne(document { put("a", 1) })

        client.close()

        assertFailsWith<IllegalStateException> { collection.insertOne(document { put("b", 2) }) }
    }

    @Test
    fun `malformed connection string is rejected`() {
        assertFailsWith<MongoException> { MongoClient("not-a-mongodb-uri") }
    }

    private companion object {
        const val DATABASE = "mongkn_it"
        var collectionCounter = 0
        var databaseCleaned = false
    }
}
