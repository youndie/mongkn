package io.github.mongkn

import io.github.mongkn.bson.BsonInt32
import io.github.mongkn.bson.BsonObjectId
import io.github.mongkn.bson.BsonString
import io.github.mongkn.bson.document
import io.github.mongkn.support.TestServer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import mongkn.cinterop.bson_error_t
import mongkn.cinterop.mongoc_client_get_database
import mongkn.cinterop.mongoc_database_destroy
import mongkn.cinterop.mongoc_database_drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Типизированные коллекции против настоящего mongod (M-21).
 *
 * Главное здесь не «класс сохранился и прочитался», а что **типизированная и нетипизированная
 * коллекции видят один и тот же документ**. Иначе маппинг жил бы в своей вселенной: тесты
 * зелёные, а данные несовместимы с остальным миром.
 */
@OptIn(ExperimentalForeignApi::class)
class TypedCollectionTest {

    @Serializable
    data class Person(val name: String, val born: Int, val tags: List<String> = emptyList())

    @Serializable
    data class WithId(val _id: BsonObjectId, val name: String)

    private val uri = TestServer.uri("serverSelectionTimeoutMS=3000&socketTimeoutMS=5000")

    private val clients = mutableListOf<MongoClient>()

    @AfterTest
    fun tearDown() {
        clients.forEach { it.close() }
        clients.clear()
    }

    private suspend fun connect(): MongoClient = MongoClient(uri).also { client ->
        clients += client
        // mongod живёт дольше прогона, а счётчик имён начинается с нуля каждый раз: без чистки
        // второй запуск видит документы первого. Ровно на этом уже спотыкались интеграционные
        // тесты — грабля общая для всего набора.
        if (!databaseCleaned) {
            client.dropTestDatabase()
            databaseCleaned = true
        }
    }

    private suspend fun MongoClient.dropTestDatabase() = withClient { handle ->
        val database = mongoc_client_get_database(handle, DATABASE)
            ?: error("mongoc_client_get_database вернул NULL")
        try {
            memScoped { mongoc_database_drop(database, alloc<bson_error_t>().ptr) }
        } finally {
            mongoc_database_destroy(database)
        }
    }

    private fun name(hint: String) = "${hint}_${counter++}"

    @Test
    fun `data class survives a real round trip`() = runTest {
        val collection = connect().getDatabase(DATABASE).getCollection<Person>(name("typed"))
        val person = Person("Ada", 1815, listOf("math", "engine"))

        collection.insertOne(person)

        assertEquals(person, collection.find().first())
    }

    @Test
    fun `typed and untyped views agree on the stored document`() = runTest {
        val client = connect()
        val collectionName = name("agree")
        val typed = client.getDatabase(DATABASE).getCollection<Person>(collectionName)
        val untyped = client.getDatabase(DATABASE).getCollection(collectionName)

        typed.insertOne(Person("Grace", 1906))

        // Тот же документ, прочитанный без маппинга: поля должны лежать ровно там, где их
        // ожидал бы человек, писавший документ руками.
        val raw = untyped.find().first()
        assertEquals(BsonString("Grace"), raw["name"])
        assertEquals(BsonInt32(1906), raw["born"])
        assertTrue("_id" in raw, "сервер должен был проставить _id: $raw")
    }

    @Test
    fun `a document written untyped reads back as a class`() = runTest {
        val client = connect()
        val collectionName = name("reverse")
        client.getDatabase(DATABASE).getCollection(collectionName)
            .insertOne(document { put("name", "Ada"); put("born", 1815) })

        val typed = client.getDatabase(DATABASE).getCollection<Person>(collectionName)

        // Поля tags в документе нет — сработает значение по умолчанию, а не падение.
        assertEquals(Person("Ada", 1815), typed.find().first())
    }

    @Test
    fun `filters stay untyped documents`() = runTest {
        val collection = connect().getDatabase(DATABASE).getCollection<Person>(name("filter"))
        collection.insertMany(listOf(Person("Ada", 1815), Person("Grace", 1906)))

        // Фильтр описывает запрос, а не хранимую сущность, — поэтому он Document и здесь.
        val found = collection.find(document { put("born", 1906) }).toList()

        assertEquals(listOf(Person("Grace", 1906)), found)
    }

    @Test
    fun `ObjectId in a class stays an ObjectId in the database`() = runTest {
        val client = connect()
        val collectionName = name("oid")
        val id = BsonObjectId.parse("6a71efcbb173221a58058212")

        client.getDatabase(DATABASE).getCollection<WithId>(collectionName).insertOne(WithId(id, "Ada"))

        // Если бы ObjectId уехал строкой, _id перестал бы быть ObjectId — и запросы по нему
        // из любого другого драйвера сломались бы.
        val raw = client.getDatabase(DATABASE).getCollection(collectionName).find().first()
        assertEquals(id, raw["_id"])
        assertEquals(WithId(id, "Ada"), client.getDatabase(DATABASE).getCollection<WithId>(collectionName).find().first())
    }

    private companion object {
        const val DATABASE = "mongkn_typed"
        var counter = 0
        var databaseCleaned = false
    }
}
