package ru.workinprogress.mongkn

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import mongkn.cinterop.mongoc_log
import mongkn.cinterop.mongoc_log_level_t
import ru.workinprogress.mongkn.bson.document
import ru.workinprogress.mongkn.support.TestServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Диагностика: коды ошибок (M-63) и логирование драйвера (M-64).
 *
 * Домены проверяются на **настоящих** ошибках, а не подстановкой чисел в перечисление: смысл
 * не в том, что `of(15)` даёт `SERVER_SELECTION`, а в том, что недоступный сервер приводит
 * именно к этому домену.
 */
class DiagnosticsTest {
    private val clients = mutableListOf<MongoClient>()

    @AfterTest
    fun tearDown() {
        clients.forEach { it.close() }
        clients.clear()
        MongknLog.setHandler(null)
    }

    private fun connect(uri: String = TestServer.uri()): MongoClient = MongoClient(uri).also { clients += it }

    @Test
    fun `a server error carries the server domain and the MongoDB code`() =
        runTest {
            val collection = connect().getDatabase(DATABASE).getCollection("dup_${counter++}")
            collection.drop()
            collection.insertOne(document { put("_id", 1) })

            val failure =
                assertFailsWith<MongoException> {
                    collection.insertOne(document { put("_id", 1) })
                }

            assertEquals(MongoErrorDomain.SERVER, failure.errorDomain)
            assertEquals(DUPLICATE_KEY, failure.code, "ждали код MongoDB, а не внутренний код драйвера")
        }

    @Test
    fun `an unreachable server gives the server selection domain`() =
        runTest {
            // Порт, на котором заведомо никто не слушает, и короткий таймаут выбора сервера.
            val client = connect("mongodb://127.0.0.1:1/?serverSelectionTimeoutMS=300")

            val failure =
                assertFailsWith<MongoException> {
                    client.getDatabase(DATABASE).listCollectionNames()
                }

            assertEquals(MongoErrorDomain.SERVER_SELECTION, failure.errorDomain)
            assertTrue(failure.isConnectivity, "отказ выбора сервера — это про связность")
        }

    @Test
    fun `a data error is not reported as a connectivity problem`() =
        runTest {
            val collection = connect().getDatabase(DATABASE).getCollection("data_${counter++}")
            collection.drop()
            collection.insertOne(document { put("_id", 1) })

            val failure = assertFailsWith<MongoException> { collection.insertOne(document { put("_id", 1) }) }

            // Различие практическое: связность имеет смысл переживать повтором, дубликат ключа — нет.
            assertTrue(!failure.isConnectivity)
        }

    @Test
    fun `an unknown domain does not break the mapping`() =
        runTest {
            // Список доменов libmongoc растёт от версии к версии. Падать на новом значении —
            // худшее, что может сделать библиотека.
            assertEquals(MongoErrorDomain.UNKNOWN, MongoErrorDomain.of(9999u))
            assertNotEquals(MongoErrorDomain.UNKNOWN, MongoErrorDomain.of(MongoErrorDomain.SERVER.value))
        }

    @Test
    fun `the raw domain number stays available`() =
        runTest {
            val client = connect("mongodb://127.0.0.1:1/?serverSelectionTimeoutMS=300")

            val failure = assertFailsWith<MongoException> { client.getDatabase(DATABASE).listCollectionNames() }

            // Перечисление ничего не прячет: число доступно и совпадает.
            assertEquals(failure.domain, failure.errorDomain.value)
        }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun `the log handler receives messages from the driver`() =
        runTest {
            val seen = mutableListOf<Triple<MongknLogLevel, String, String>>()
            MongknLog.setHandler { level, domain, message -> seen += Triple(level, domain, message) }

            // Сообщение отправляется через сам libmongoc, а не подставляется в обработчик:
            // иначе тест проверял бы вызов лямбды, а не то, что мы подключились к драйверу.
            mongoc_log(mongoc_log_level_t.MONGOC_LOG_LEVEL_WARNING, "mongkn-test", "проверка обработчика")

            assertEquals(1, seen.size, "обработчик не получил сообщение")
            assertEquals(MongknLogLevel.WARNING, seen.single().first)
            assertEquals("mongkn-test", seen.single().second)
            assertTrue("проверка обработчика" in seen.single().third)
        }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun `removing the handler stops delivery`() =
        runTest {
            val seen = mutableListOf<String>()
            MongknLog.setHandler { _, _, message -> seen += message }
            MongknLog.setHandler(null)

            mongoc_log(mongoc_log_level_t.MONGOC_LOG_LEVEL_WARNING, "mongkn-test", "после снятия")

            assertEquals(emptyList(), seen)
        }

    private companion object {
        const val DATABASE = "mongkn_diag"

        /** Код MongoDB для дубликата ключа. */
        const val DUPLICATE_KEY: UInt = 11000u

        var counter = 0
    }
}
