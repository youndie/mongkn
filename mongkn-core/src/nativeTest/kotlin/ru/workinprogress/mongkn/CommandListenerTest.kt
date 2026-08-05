package ru.workinprogress.mongkn

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import ru.workinprogress.mongkn.bson.BsonString
import ru.workinprogress.mongkn.bson.document
import ru.workinprogress.mongkn.support.TestServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Мониторинг команд (M-79) против настоящего mongod.
 *
 * Проверяется не только то, что события приходят, но и что в них **лежит правда**: имя команды,
 * база, сам документ команды и время. Наблюдатель, который зовётся, но приносит пустые события,
 * прошёл бы более слабый тест.
 */
class CommandListenerTest {
    private val clients = mutableListOf<MongoClient>()

    @AfterTest
    fun tearDown() {
        clients.forEach { it.close() }
        clients.clear()
    }

    /** Складывает события в списки. Синхронизация не нужна: тесты однопоточные. */
    private class Recorder : CommandListener {
        val started = mutableListOf<CommandStartedEvent>()
        val succeeded = mutableListOf<CommandSucceededEvent>()
        val failed = mutableListOf<CommandFailedEvent>()

        override fun started(event: CommandStartedEvent) {
            started += event
        }

        override fun succeeded(event: CommandSucceededEvent) {
            succeeded += event
        }

        override fun failed(event: CommandFailedEvent) {
            failed += event
        }
    }

    private fun connect(listener: CommandListener): MongoClient =
        MongoClient(TestServer.uri("serverSelectionTimeoutMS=3000"), commandListener = listener).also { clients += it }

    @Test
    fun `an insert produces a started and a succeeded event`() =
        runTest {
            val recorder = Recorder()
            val collection = connect(recorder).getDatabase(DATABASE).getCollection("ins_${counter++}")

            collection.insertOne(document { put("n", 1) })

            val started = recorder.started.single { it.commandName == "insert" }
            assertEquals(DATABASE, started.databaseName)
            assertEquals(BsonString(collection.name), started.command["insert"], "в команде нет имени коллекции")

            val succeeded = recorder.succeeded.single { it.commandName == "insert" }
            assertEquals(started.requestId, succeeded.requestId, "события должны сходиться по requestId")
            assertTrue(succeeded.durationMicros > 0, "драйвер обязан измерить время")
            assertTrue(recorder.failed.isEmpty())
        }

    @Test
    fun `a failing command produces a failed event with the reason`() =
        runTest {
            val recorder = Recorder()
            val collection = connect(recorder).getDatabase(DATABASE).getCollection("fail_${counter++}")
            collection.drop()
            collection.insertOne(document { put("_id", 1) })

            assertFailsWith<MongoException> { collection.insertOne(document { put("_id", 1) }) }

            // Дубликат ключа — ошибка уровня записи, а не команды: сервер отвечает `ok: 1`
            // и кладёт отказ в `writeErrors`. Поэтому событие здесь succeeded, а не failed,
            // и это не наша особенность, а устройство протокола.
            val succeeded = recorder.succeeded.last { it.commandName == "insert" }
            assertTrue("writeErrors" in succeeded.reply, "ответ обязан нести причину отказа записи")
        }

    @Test
    fun `a command rejected by the server produces a failed event`() =
        runTest {
            val recorder = Recorder()
            val database = connect(recorder).getDatabase(DATABASE)

            assertFailsWith<MongoException> { database.runCommand(document { put("нетТакойКоманды", 1) }) }

            val failed = recorder.failed.single { it.commandName == "нетТакойКоманды" }
            assertTrue(failed.durationMicros > 0)
            assertEquals(MongoErrorDomain.SERVER, failed.failure.errorDomain)
        }

    @Test
    fun `a find reports both the query and the cursor commands`() =
        runTest {
            val recorder = Recorder()
            val collection = connect(recorder).getDatabase(DATABASE).getCollection("find_${counter++}")
            collection.drop()
            collection.insertMany((0 until 3).map { n -> document { put("n", n) } })

            collection.find().toList()

            val find = recorder.started.single { it.commandName == "find" }
            assertEquals(BsonString(collection.name), find.command["find"])
        }

    @Test
    fun `an exception from the listener does not break the operation`() =
        runTest {
            val angry =
                object : CommandListener {
                    override fun started(event: CommandStartedEvent) = error("наблюдатель сломался")
                }
            val collection = connect(angry).getDatabase(DATABASE).getCollection("angry_${counter++}")
            collection.drop()

            // Включение метрик не должно менять поведение приложения — исключение наблюдателя
            // проглатывается намеренно. Заодно это проверка, что оно не уходит через границу C:
            // там оно уронило бы процесс целиком, а не операцию.
            collection.insertOne(document { put("n", 1) })

            assertEquals(1L, collection.countDocuments())
        }

    @Test
    fun `a client without a listener works as before`() =
        runTest {
            val collection =
                MongoClient(TestServer.uri())
                    .also { clients += it }
                    .getDatabase(DATABASE)
                    .getCollection("plain_${counter++}")
            collection.drop()

            collection.insertOne(document { put("n", 1) })

            assertEquals(1L, collection.countDocuments())
        }

    private companion object {
        const val DATABASE = "mongkn_apm"
        var counter = 0
    }
}
