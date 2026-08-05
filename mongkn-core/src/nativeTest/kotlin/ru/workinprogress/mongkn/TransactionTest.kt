package ru.workinprogress.mongkn

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import ru.workinprogress.mongkn.bson.BsonInt32
import ru.workinprogress.mongkn.bson.BsonString
import ru.workinprogress.mongkn.bson.Document
import ru.workinprogress.mongkn.bson.document
import ru.workinprogress.mongkn.support.TestServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Сессии и транзакции (M14) против настоящего mongod.
 *
 * **Требуют replica set** — на standalone транзакций нет вовсе.
 *
 * Ключевая проверка здесь не «команда не упала», а **изоляция**: пока транзакция не зафиксирована,
 * посторонний читатель не должен видеть её записей. Тест, который проверяет только результат после
 * коммита, одинаково зелёный и с транзакцией, и без неё — то есть не проверяет ничего.
 */
class TransactionTest {
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
                client.getDatabase(DATABASE).drop()
                cleaned = true
            }
        }

    /**
     * Имя коллекции, созданной заранее.
     *
     * Создавать коллекцию **внутри** транзакции нельзя на старых серверах, а на новых это лишняя
     * переменная в тесте про изоляцию.
     */
    private suspend fun prepared(
        client: MongoClient,
        hint: String,
    ): String {
        val name = "${hint}_${counter++}"
        client.getDatabase(DATABASE).createCollection(name)
        return name
    }

    private suspend fun numbers(collection: MongoCollection<Document>): List<Int> =
        collection
            .find()
            .toList()
            .map { (it["n"] as BsonInt32).value }
            .sorted()

    @Test
    fun `a session works without any transaction`() =
        runTest {
            val client = connect()
            val name = prepared(client, "plain")

            client.startSession().use { session ->
                val collection = session.getDatabase(DATABASE).getCollection(name)
                collection.insertOne(document { put("n", 1) })

                assertFalse(session.inTransaction)
                assertEquals(listOf(1), numbers(collection))
            }
        }

    @Test
    fun `writes are invisible outside until the transaction commits`() =
        runTest {
            val client = connect()
            val name = prepared(client, "isolation")
            val outside = client.getDatabase(DATABASE).getCollection(name)

            client.startSession().use { session ->
                val inside = session.getDatabase(DATABASE).getCollection(name)
                session.startTransaction()
                inside.insertOne(document { put("n", 1) })

                // Главная проверка вехи: запись сделана, но снаружи её ещё нет.
                assertTrue(session.inTransaction)
                assertEquals(listOf(1), numbers(inside), "своя же запись внутри транзакции не видна")
                assertEquals(emptyList(), numbers(outside), "незафиксированная запись видна снаружи")

                session.commitTransaction()
                assertFalse(session.inTransaction)
            }

            assertEquals(listOf(1), numbers(outside))
        }

    @Test
    fun `an aborted transaction leaves nothing behind`() =
        runTest {
            val client = connect()
            val name = prepared(client, "abort")
            val outside = client.getDatabase(DATABASE).getCollection(name)

            client.startSession().use { session ->
                val inside = session.getDatabase(DATABASE).getCollection(name)
                session.startTransaction()
                inside.insertOne(document { put("n", 1) })
                inside.insertOne(document { put("n", 2) })
                session.abortTransaction()
            }

            assertEquals(emptyList(), numbers(outside))
        }

    @Test
    fun `withTransaction commits when the body returns`() =
        runTest {
            val client = connect()
            val name = prepared(client, "with_ok")
            val outside = client.getDatabase(DATABASE).getCollection(name)

            val result =
                client.startSession().use { session ->
                    val inside = session.getDatabase(DATABASE).getCollection(name)
                    session.withTransaction {
                        inside.insertOne(document { put("n", 1) })
                        inside.insertOne(document { put("n", 2) })
                        "готово"
                    }
                }

            assertEquals("готово", result)
            assertEquals(listOf(1, 2), numbers(outside))
        }

    @Test
    fun `withTransaction rolls back when the body throws`() =
        runTest {
            val client = connect()
            val name = prepared(client, "with_fail")
            val outside = client.getDatabase(DATABASE).getCollection(name)

            val failure =
                assertFailsWith<IllegalStateException> {
                    client.startSession().use { session ->
                        val inside = session.getDatabase(DATABASE).getCollection(name)
                        session.withTransaction {
                            inside.insertOne(document { put("n", 1) })
                            error("своя ошибка внутри транзакции")
                        }
                    }
                }

            // Наружу уходит исходная причина, а не то, чем закончился откат.
            assertEquals("своя ошибка внутри транзакции", failure.message)
            assertEquals(emptyList(), numbers(outside))
        }

    @Test
    fun `several operations share one transaction`() =
        runTest {
            val client = connect()
            val from = prepared(client, "from")
            val to = prepared(client, "to")
            client.getDatabase(DATABASE).getCollection(from).insertOne(document { put("n", 10) })

            client.startSession().use { session ->
                val database = session.getDatabase(DATABASE)
                session.withTransaction {
                    database.getCollection(from).deleteOne(document { put("n", 10) })
                    database.getCollection(to).insertOne(document { put("n", 10) })
                }
            }

            assertEquals(emptyList(), numbers(client.getDatabase(DATABASE).getCollection(from)))
            assertEquals(listOf(10), numbers(client.getDatabase(DATABASE).getCollection(to)))
        }

    @Test
    fun `a cursor inside a transaction sees the transaction's own writes`() =
        runTest {
            val client = connect()
            val name = prepared(client, "cursor")

            client.startSession().use { session ->
                val inside = session.getDatabase(DATABASE).getCollection(name)
                session.withTransaction {
                    inside.insertMany((0 until 5).map { n -> document { put("n", n) } })

                    // Курсор внутри сессии идёт по закреплённому клиенту и держит её мьютекс;
                    // если бы он ушёл в общий пул, сервер не показал бы незафиксированных записей.
                    assertEquals(listOf(0, 1, 2, 3, 4), numbers(inside))
                }
            }
        }

    @Test
    fun `closing a session returns its client to the pool`() =
        runTest {
            val client = connect()
            val name = prepared(client, "release")

            // Сессий подряд больше, чем размер пула, был бы дедлок, если бы close не возвращал
            // клиента. Берём с запасом от DEFAULT_MAX_CONCURRENT_CLIENTS.
            repeat(MongoClient.DEFAULT_MAX_CONCURRENT_CLIENTS + 5) {
                client.startSession().use { session ->
                    session.getDatabase(DATABASE).getCollection(name).countDocuments()
                }
            }
        }

    @Test
    fun `a closed session refuses further work`() =
        runTest {
            val client = connect()
            val session = client.startSession()
            session.close()

            assertFailsWith<IllegalStateException> { session.startTransaction() }
        }

    @Test
    fun `closing twice is harmless`() =
        runTest {
            val client = connect()
            val session = client.startSession()
            session.close()
            session.close()
        }

    @Test
    fun `transaction options reach the server`() =
        runTest {
            val client = connect()
            val name = prepared(client, "opts")
            val outside = client.getDatabase(DATABASE).getCollection(name)

            client.startSession().use { session ->
                val inside = session.getDatabase(DATABASE).getCollection(name)
                session.withTransaction(
                    TransactionOptions(
                        readConcern = readConcern("snapshot"),
                        writeConcern = majorityWriteConcern(timeoutMillis = 5_000),
                        maxCommitTimeMillis = 10_000,
                    ),
                ) {
                    inside.insertOne(document { put("n", 1) })
                }
            }

            assertEquals(listOf(1), numbers(outside))
        }

    @Test
    fun `an impossible write concern is refused when the transaction commits`() =
        runTest {
            val client = connect()
            val name = prepared(client, "opts_bad")

            // Именованного режима записи с таким именем на сервере нет. Проверяет, что настройки
            // действительно доезжают: на принимаемом значении тест был бы зелёным и с потерянными.
            assertFailsWith<MongoException> {
                client.startSession().use { session ->
                    val inside = session.getDatabase(DATABASE).getCollection(name)
                    session.withTransaction(TransactionOptions(writeConcern = writeConcern(BsonString("нетТакого")))) {
                        inside.insertOne(document { put("n", 1) })
                    }
                }
            }
        }

    @Test
    fun `an unknown write concern key is refused before reaching the server`() =
        runTest {
            val client = connect()

            // Ключ, которого мы не умеем переводить в структуру libmongoc, — ошибка, а не
            // молчаливый пропуск: настройка, выглядящая применённой и не применяемая, —
            // ровно то, что чинилось в M10.
            val failure =
                assertFailsWith<IllegalStateException> {
                    client.startSession().use { session ->
                        session.startTransaction(
                            TransactionOptions(writeConcern = document { put("нетТакогоКлюча", 1) }),
                        )
                    }
                }
            assertTrue("нетТакогоКлюча" in failure.message.orEmpty())
        }

    private companion object {
        const val DATABASE = "mongkn_m14"
        var counter = 0
        var cleaned = false
    }
}
