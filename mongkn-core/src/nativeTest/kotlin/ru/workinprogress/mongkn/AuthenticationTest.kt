package ru.workinprogress.mongkn

import kotlinx.coroutines.test.runTest
import ru.workinprogress.mongkn.bson.document
import ru.workinprogress.mongkn.support.TestServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Аутентификация (M-65) против сервера с включённым `--auth`.
 *
 * До этой вехи аутентификация не проверялась **ни разу**: весь прогон шёл против сервера без неё.
 * То есть в самом обычном сценарии использования — база с паролем — мы не знали, работает
 * библиотека или нет.
 *
 * Сервер тут отдельный, на своём порту. Включи мы `--auth` на основном, креды понадобились бы
 * каждому из полутора сотен остальных тестов, и предмет проверки растворился бы в общем шуме.
 *
 * Отказ проверяется **по причине**, а не по факту падения: неверный пароль и недоступный сервер
 * оба дают исключение, но это разные вещи, и тест, который их не различает, зеленел бы при
 * выключенном сервере.
 */
class AuthenticationTest {
    private val clients = mutableListOf<MongoClient>()

    @AfterTest
    fun tearDown() {
        clients.forEach { it.close() }
        clients.clear()
    }

    private fun connect(uri: String): MongoClient = MongoClient(uri).also { clients += it }

    /**
     * Проба: операция, которая **требует авторизации**.
     *
     * Не `ping`: он на сервере с `--auth` проходит и без учётных данных — это одна из немногих
     * команд, разрешённых до аутентификации. Проба на нём выглядела бы работающей, а проверяла бы
     * ровно ничего. Выяснилось прогоном, а не из документации.
     */
    private suspend fun probe(client: MongoClient): List<String> = client.getDatabase(DATABASE).listCollectionNames()

    @Test
    fun `correct credentials let the operation through`() =
        runTest {
            // Отсутствие исключения и есть результат: операция дошла до сервера и была разрешена.
            probe(connect(TestServer.authUri()))
        }

    @Test
    fun `a wrong password is refused as an authentication failure`() =
        runTest {
            val failure =
                assertFailsWith<MongoException> {
                    probe(connect(TestServer.authUri(password = "не_тот_пароль")))
                }

            // Код 18 — AuthenticationFailed. Проверяем именно его: без этого тест был бы зелёным
            // и при выключенном сервере, и при опечатке в адресе.
            assertAuthFailure(failure)
        }

    @Test
    fun `an unknown user is refused the same way`() =
        runTest {
            val failure =
                assertFailsWith<MongoException> {
                    probe(connect(TestServer.authUri(user = "нет_такого")))
                }

            assertAuthFailure(failure)
        }

    @Test
    fun `no credentials at all are refused by the server`() =
        runTest {
            val failure =
                assertFailsWith<MongoException> {
                    probe(connect("mongodb://${TestServer.authHost}/"))
                }

            // Здесь сервер отвечает не «не тот пароль», а «команда требует аутентификации»:
            // соединение установлено, отказ пришёл на самой команде.
            assertTrue(
                failure.message.orEmpty().contains("auth", ignoreCase = true) ||
                    failure.message.orEmpty().contains("Unauthorized", ignoreCase = true),
                "ждали отказ по аутентификации, получили: ${failure.message}",
            )
        }

    @Test
    fun `authSource points at the database that holds the user`() =
        runTest {
            // Пользователь заведён в admin, а работаем мы в другой базе. Без authSource драйвер
            // пошёл бы искать его в рабочей базе и не нашёл.
            probe(connect(TestServer.authUri(options = "authSource=admin")))
        }

    @Test
    fun `a wrong authSource cannot find the user`() =
        runTest {
            val failure =
                assertFailsWith<MongoException> {
                    probe(connect(TestServer.authUri(options = "authSource=$DATABASE")))
                }

            assertAuthFailure(failure)
        }

    @Test
    fun `a password with special characters works when percent encoded`() =
        runTest {
            // `p@ss:w/rd?#1` — каждый из этих символов в URI значащий, и без кодирования строка
            // подключения распадётся не там. Это самая частая причина «пароль верный, а не пускает».
            val encoded = "p%40ss%3Aw%2Frd%3F%231"

            probe(connect(TestServer.authUri(user = TestServer.ODD_USER, password = encoded)))
        }

    @Test
    fun `the same password without encoding does not authenticate`() =
        runTest {
            // Оборотная сторона предыдущего теста: тот же пароль, вставленный как есть, к успеху
            // не приводит. Как именно не приводит — решает разбор URI в libmongoc, поэтому здесь
            // подходит любой отказ, лишь бы операция не прошла.
            assertFailsWith<Throwable> {
                probe(connect(TestServer.authUri(user = TestServer.ODD_USER, password = TestServer.ODD_PASSWORD)))
            }
        }

    @Test
    fun `an explicit SCRAM-SHA-256 mechanism works`() =
        runTest {
            probe(connect(TestServer.authUri(options = "authSource=admin&authMechanism=SCRAM-SHA-256")))
        }

    @Test
    fun `an unsupported mechanism is refused`() =
        runTest {
            assertFailsWith<Throwable> {
                probe(connect(TestServer.authUri(options = "authSource=admin&authMechanism=НЕТ-ТАКОГО")))
            }
        }

    @Test
    fun `writes and reads both work over an authenticated connection`() =
        runTest {
            val client = connect(TestServer.authUri())
            val collection = client.getDatabase(DATABASE).getCollection("auth_${counter++}")
            collection.drop()

            collection.insertOne(document { put("n", 1) })

            assertEquals(1L, collection.countDocuments())
        }

    private fun assertAuthFailure(failure: MongoException) {
        val text = failure.message.orEmpty()
        assertTrue(
            failure.code == AUTHENTICATION_FAILED || text.contains("Authentication failed", ignoreCase = true),
            "ждали отказ аутентификации (код $AUTHENTICATION_FAILED), получили код ${failure.code}: $text",
        )
    }

    private companion object {
        const val DATABASE = "mongkn_auth"

        /** `AuthenticationFailed` из кодов ошибок MongoDB. */
        const val AUTHENTICATION_FAILED: UInt = 18u

        var counter = 0
    }
}
