package ru.workinprogress.mongkn

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
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
 * Повторы операций при сбоях (M-62).
 *
 * Запись в бэклоге утверждала, что ретраев у нас нет и это молчаливое отличие от официального
 * драйвера. Проверка показала обратное: **retryable reads и writes реализованы в самом libmongoc**
 * и включены по умолчанию, то есть mongkn получает их даром. Эти тесты существуют, чтобы
 * утверждение перестало быть предположением в обе стороны.
 *
 * Сбой не имитируется на нашей стороне, а **заказывается серверу** через `failCommand` — тот же
 * механизм, которым пользуются официальные spec-тесты. Поэтому тестовый mongod поднят
 * с `enableTestCommands=1`: без этого параметра сервер откажется ставить failpoint.
 *
 * Каждая пара тестов устроена одинаково: с ретраями операция переживает сбой, без ретраев —
 * падает. Без второй половины первая ничего не доказывала бы: операция могла бы проходить
 * просто потому, что сбой не сработал.
 */
class RetryTest {
    private val clients = mutableListOf<MongoClient>()

    @AfterTest
    fun tearDown() {
        clients.forEach { it.close() }
        clients.clear()
    }

    private val appNames = AppNames("retry")

    private fun connect(options: String = ""): MongoClient {
        val appName = appNames.assign()
        val client =
            MongoClient(TestServer.uri("appName=$appName&serverSelectionTimeoutMS=3000&socketTimeoutMS=5000$options"))
        clients += client
        return appNames.remember(client, appName)
    }

    /**
     * Заказывает серверу ровно один сбой указанных команд и снимает заказ после [body].
     *
     * `mode: {times: 1}` — сбой срабатывает один раз, поэтому повтор обязан пройти. Если бы
     * failpoint был бессрочным, тест не отличал бы «повтора не было» от «повтор тоже упал».
     */
    private suspend fun withFailPoint(
        client: MongoClient,
        command: String,
        errorCode: Int,
        labels: List<String>,
        body: suspend () -> Unit,
    ) {
        val admin = client.getDatabase("admin")
        // boundTo сужает сбой до этого клиента: failpoint глобален для сервера, а `times: 1`
        // расходуется первой подошедшей командой от кого угодно (M-82).
        admin.runCommand(
            document {
                put("configureFailPoint", "failCommand")
                putDocument("mode") { put("times", 1) }
                putDocument("data") {
                    putArray("failCommands") { add(command) }
                    put("errorCode", errorCode)
                    if (labels.isNotEmpty()) {
                        putArray("errorLabels") { labels.forEach(::add) }
                    }
                }
            }.boundTo(appNames.of(client)),
        )
        try {
            body()
        } finally {
            admin.runCommand(
                document {
                    put("configureFailPoint", "failCommand")
                    put("mode", "off")
                },
            )
        }
    }

    private suspend fun freshCollection(
        client: MongoClient,
        hint: String,
    ): MongoCollection<Document> = client.getDatabase(DATABASE).getCollection("${hint}_${counter++}").also { it.drop() }

    @Test
    fun `a retryable write survives one injected failure`() =
        runTest {
            val client = connect()
            val collection = freshCollection(client, "write_retry")

            withFailPoint(client, "insert", RETRYABLE_WRITE_CODE, listOf("RetryableWriteError")) {
                collection.insertOne(document { put("n", 1) })
            }

            assertEquals(1L, collection.countDocuments(), "повтор не выполнился или выполнился дважды")
        }

    @Test
    fun `the same failure is fatal when retryWrites is off`() =
        runTest {
            val client = connect("&retryWrites=false")
            val collection = freshCollection(client, "write_no_retry")

            withFailPoint(client, "insert", RETRYABLE_WRITE_CODE, listOf("RetryableWriteError")) {
                assertFailsWith<MongoException> { collection.insertOne(document { put("n", 1) }) }
            }

            // Показывает, что предыдущий тест проходил благодаря повтору, а не потому,
            // что failpoint не сработал.
            assertEquals(0L, collection.countDocuments())
        }

    @Test
    fun `a retryable read survives one injected failure`() =
        runTest {
            val client = connect()
            val collection = freshCollection(client, "read_retry")
            collection.insertOne(document { put("n", 1) })

            val found =
                withFailPointReturning(client, "find", RETRYABLE_READ_CODE) {
                    collection.find().toList()
                }

            assertEquals(1, found.size)
        }

    @Test
    fun `the same failure is fatal when retryReads is off`() =
        runTest {
            val client = connect("&retryReads=false")
            val collection = freshCollection(client, "read_no_retry")
            collection.insertOne(document { put("n", 1) })

            withFailPoint(client, "find", RETRYABLE_READ_CODE, emptyList()) {
                assertFailsWith<MongoException> { collection.find().toList() }
            }
        }

    @Test
    fun `an error that is not retryable is not retried`() =
        runTest {
            val client = connect()
            val collection = freshCollection(client, "not_retryable")

            // Дубликат ключа — ошибка данных, а не связи. Повторять её бессмысленно, и драйвер
            // не должен: иначе он маскировал бы ошибки приложения задержкой.
            withFailPoint(client, "insert", DUPLICATE_KEY_CODE, emptyList()) {
                assertFailsWith<MongoException> { collection.insertOne(document { put("n", 1) }) }
            }
        }

    /** Вариант [withFailPoint] для операций, у которых важен результат. */
    private suspend fun <T> withFailPointReturning(
        client: MongoClient,
        command: String,
        errorCode: Int,
        body: suspend () -> T,
    ): T {
        var result: T? = null
        withFailPoint(client, command, errorCode, emptyList()) { result = body() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private companion object {
        const val DATABASE = "mongkn_retry"

        /** `InterruptedDueToReplStateChange` — сервер объявляет её повторяемой. */
        const val RETRYABLE_WRITE_CODE = 11602

        /** `HostUnreachable` — входит в список повторяемых для чтения. */
        const val RETRYABLE_READ_CODE = 6

        /** `DuplicateKey` — ошибка данных, повторять нечего. */
        const val DUPLICATE_KEY_CODE = 11000

        var counter = 0
    }
}
