package ru.workinprogress.mongkn

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import ru.workinprogress.mongkn.bson.BsonDocument
import ru.workinprogress.mongkn.bson.BsonString
import ru.workinprogress.mongkn.bson.Document
import ru.workinprogress.mongkn.bson.document
import ru.workinprogress.mongkn.support.AppNames
import ru.workinprogress.mongkn.support.TestServer
import ru.workinprogress.mongkn.support.boundTo
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Привязка инсценированного сбоя к своему клиенту работает (M-82).
 *
 * Проверяется не то, что `appName` попал в строку подключения, — это проверило бы само себя.
 * Проверяется **исход**: сбой, заказанный для клиента A, обязан сработать у A и **не** сработать
 * у B, хотя команда та же самая и сервер один. Без этого привязка выглядела бы работающей,
 * будучи молча проигнорированной: неизвестный параметр строки подключения драйвер не отвергает,
 * а `data.appName` для сервера, который его не понимает, — просто лишнее поле.
 */
class FailPointBindingTest {
    private val clients = mutableListOf<MongoClient>()
    private val appNames = AppNames("binding")

    @AfterTest
    fun tearDown() {
        runBlocking { clients.firstOrNull()?.let { clearFailPoint(it) } }
        clients.forEach { it.close() }
        clients.clear()
    }

    private fun connect(): Pair<MongoClient, String> {
        val appName = appNames.assign()
        val client =
            MongoClient(TestServer.uri("appName=$appName&serverSelectionTimeoutMS=3000"))
                .also { clients += it }
        return client to appName
    }

    private suspend fun clearFailPoint(client: MongoClient) {
        client.getDatabase("admin").runCommand(
            document {
                put("configureFailPoint", "failCommand")
                put("mode", "off")
            },
        )
    }

    private fun failCommandOnce(command: String): Document =
        document {
            put("configureFailPoint", "failCommand")
            put("mode", document { put("times", 1) })
            put(
                "data",
                document {
                    putArray("failCommands") { add(command) }
                    put("errorCode", UNKNOWN_ERROR)
                },
            )
        }

    @Test
    fun `binding puts the name into the data of the failpoint`() {
        val bound = failCommandOnce("find").boundTo("mongkn-probe")

        val data = bound["data"] as? BsonDocument

        assertEquals(BsonString("mongkn-probe"), data?.get("appName"), "форма: $bound")
        assertEquals(
            "configureFailPoint",
            bound.entries.first().first,
            "имя команды обязано остаться первым: $bound",
        )
    }

    @Test
    fun `a failure ordered for one client does not fire for another`() {
        runBlocking {
            val (mine, myAppName) = connect()
            val (other, _) = connect()

            mine.getDatabase("admin").runCommand(failCommandOnce("find").boundTo(myAppName))

            val collection = "bound_${counter++}"

            // Чужой клиент идёт первым — именно он съел бы срабатывание, будь сбой глобальным.
            assertEquals(
                emptyList(),
                other
                    .getDatabase(DATABASE)
                    .getCollection(collection)
                    .find()
                    .toList(),
                "сбой сработал у постороннего клиента: привязка по appName не действует",
            )

            assertFailsWith<MongoException> {
                mine
                    .getDatabase(DATABASE)
                    .getCollection(collection)
                    .find()
                    .toList()
            }
        }
    }

    @Test
    fun `an unbound failure still fires for anyone`() {
        runBlocking {
            // Контроль к предыдущему тесту: без привязки сбой действительно достаётся первому
            // подошедшему. Без этой проверки первый тест мог бы проходить оттого, что сбой
            // не сработал вовсе.
            val (mine, _) = connect()
            val (other, _) = connect()

            mine.getDatabase("admin").runCommand(failCommandOnce("find"))

            assertFailsWith<MongoException> {
                other
                    .getDatabase(DATABASE)
                    .getCollection("unbound_${counter++}")
                    .find()
                    .toList()
            }
        }
    }

    private companion object {
        /**
         * Обычная ошибка команды — намеренно **не** из тех, что меняют состояние узла.
         *
         * `InterruptedAtShutdown` (11600) и прочие «узел выключается» помечают сервер как
         * недоступный, после чего по спецификации SDAM драйвер выдерживает паузу перед повторной
         * проверкой. На одиночной топологии это дольше, чем `serverSelectionTimeoutMS` теста,
         * и следующий вызов падает не сбоем, который заказывали, а тайм-аутом выбора сервера —
         * что выглядит как неработающая привязка, хотя привязка ни при чём.
         */
        const val UNKNOWN_ERROR = 8

        const val DATABASE = "mongkn_failpoint_binding"
        var counter = 0
    }
}
