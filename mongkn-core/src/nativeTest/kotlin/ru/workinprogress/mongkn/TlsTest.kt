package ru.workinprogress.mongkn

import kotlinx.coroutines.test.runTest
import ru.workinprogress.mongkn.bson.BsonArray
import ru.workinprogress.mongkn.bson.BsonDocument
import ru.workinprogress.mongkn.bson.BsonString
import ru.workinprogress.mongkn.bson.document
import ru.workinprogress.mongkn.support.TestServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * TLS и аутентификация по сертификату (M-75) против сервера с `--tlsMode requireTLS`.
 *
 * До этой вехи ни то, ни другое не проверялось ни разу — как и SCRAM до M-65.
 *
 * Сервер отдельный, третий по счёту: `requireTLS` отвергает любое соединение без шифрования,
 * то есть включить его на общем сервере значило бы переписать подключение во всех остальных
 * тестах ради одного.
 *
 * Сертификаты не лежат в репозитории, а генерируются `ci/tls/generate.sh`: закоммиченный
 * сертификат протухает и через год превращает зелёный тест в красный без единой правки кода.
 *
 * Отказы проверяются **по причине**: недоверенный сертификат, отсутствие TLS и неизвестный
 * пользователь дают разные ошибки, и тест, который их не различает, зеленел бы при выключенном
 * сервере.
 */
class TlsTest {
    private val clients = mutableListOf<MongoClient>()

    @AfterTest
    fun tearDown() {
        clients.forEach { it.close() }
        clients.clear()
    }

    private fun connect(uri: String): MongoClient = MongoClient(uri).also { clients += it }

    private val ca get() = "${TestServer.tlsDirectory}/ca.pem"
    private val clientCertificate get() = "${TestServer.tlsDirectory}/client.pem"
    private val unknownCertificate get() = "${TestServer.tlsDirectory}/unknown.pem"

    private suspend fun probe(client: MongoClient): List<String> = client.getDatabase(DATABASE).listCollectionNames()

    /** Кем сервер считает это соединение. Пустой список — соединение не аутентифицировано. */
    private suspend fun authenticatedAs(client: MongoClient): List<String> {
        val status = client.getDatabase("admin").runCommand(document { put("connectionStatus", 1) })
        val users = ((status["authInfo"] as? BsonDocument)?.get("authenticatedUsers") as? BsonArray)?.values.orEmpty()
        return users.filterIsInstance<BsonDocument>().mapNotNull { (it["user"] as? BsonString)?.value }
    }

    @Test
    fun `a trusted certificate lets the connection through`() =
        runTest {
            probe(connect(TestServer.tlsUri("tlsCAFile=$ca")))
        }

    @Test
    fun `an untrusted certificate is refused`() =
        runTest {
            // Без нашего удостоверяющего центра сертификат сервера самоподписан для клиента,
            // и проверка обязана провалиться. Это оборотная сторона предыдущего теста: без неё
            // тот проходил бы и при полностью отключённой проверке сертификатов.
            val failure =
                assertFailsWith<MongoException> {
                    probe(connect(TestServer.tlsUri("serverSelectionTimeoutMS=3000")))
                }

            assertTrue(failure.isConnectivity, "ждали отказ на уровне соединения, получили: ${failure.message}")
        }

    @Test
    fun `an untrusted certificate is accepted when validation is turned off`() =
        runTest {
            // Показывает, что предыдущий тест падал именно на проверке сертификата,
            // а не потому, что сервер недоступен.
            probe(connect(TestServer.tlsUri("tlsAllowInvalidCertificates=true")))
        }

    @Test
    fun `a plaintext connection to a TLS-only server fails`() =
        runTest {
            val failure =
                assertFailsWith<MongoException> {
                    probe(connect("mongodb://${TestServer.tlsHost}/?serverSelectionTimeoutMS=3000"))
                }

            assertTrue(failure.isConnectivity, "ждали отказ на уровне соединения, получили: ${failure.message}")
        }

    @Test
    fun `x509 authenticates the connection as the certificate subject`() =
        runTest {
            val client =
                connect(
                    TestServer.tlsUri(
                        "tlsCAFile=$ca&tlsCertificateKeyFile=$clientCertificate" +
                            "&authMechanism=MONGODB-X509&authSource=\$external",
                    ),
                )

            // Не «операция прошла», а «сервер считает нас именно этим пользователем». Сервер
            // поднят без `--auth`, поэтому без этой проверки тест был бы одинаково зелёным
            // и с работающей аутентификацией, и без неё.
            assertEquals(listOf(TestServer.x509User), authenticatedAs(client))
        }

    @Test
    fun `a certificate unknown to the server is refused`() =
        runTest {
            val failure =
                assertFailsWith<MongoException> {
                    probe(
                        connect(
                            TestServer.tlsUri(
                                "tlsCAFile=$ca&tlsCertificateKeyFile=$unknownCertificate" +
                                    "&authMechanism=MONGODB-X509&authSource=\$external&serverSelectionTimeoutMS=3000",
                            ),
                        ),
                    )
                }

            // Сертификат подписан тем же центром, то есть TLS проходит; отказывает именно
            // аутентификация — такого пользователя на сервере нет.
            assertTrue(
                failure.message.orEmpty().contains("ould not find user", ignoreCase = true) ||
                    failure.code == AUTHENTICATION_FAILED,
                "ждали отказ аутентификации, получили код ${failure.code}: ${failure.message}",
            )
        }

    @Test
    fun `without x509 credentials the connection stays anonymous`() =
        runTest {
            val client = connect(TestServer.tlsUri("tlsCAFile=$ca"))

            // Соединение по TLS есть, аутентификации нет — то есть предыдущий тест проверял
            // именно вход по сертификату, а не сам факт подключения.
            assertEquals(emptyList(), authenticatedAs(client))
        }

    @Test
    fun `reads and writes work over TLS`() =
        runTest {
            val client =
                connect(
                    TestServer.tlsUri(
                        "tlsCAFile=$ca&tlsCertificateKeyFile=$clientCertificate" +
                            "&authMechanism=MONGODB-X509&authSource=\$external",
                    ),
                )
            val collection = client.getDatabase(DATABASE).getCollection("tls_${counter++}")
            collection.drop()

            collection.insertOne(document { put("n", 1) })

            assertEquals(1L, collection.countDocuments())
        }

    private companion object {
        const val DATABASE = "mongkn_tls"

        /** `AuthenticationFailed` из кодов ошибок MongoDB. */
        const val AUTHENTICATION_FAILED: UInt = 18u

        var counter = 0
    }
}
