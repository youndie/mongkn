package ru.workinprogress.mongkn

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import mongkn.cinterop.bson_error_t
import mongkn.cinterop.mongoc_client_get_database
import mongkn.cinterop.mongoc_database_destroy
import mongkn.cinterop.mongoc_database_drop
import ru.workinprogress.mongkn.bson.BsonInt32
import ru.workinprogress.mongkn.bson.Document
import ru.workinprogress.mongkn.bson.document
import ru.workinprogress.mongkn.support.TestServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Стресс-тест ресурсной модели (M-29, риск 5).
 *
 * Решение Р2 — пул `mongoc_client_pool_t` вместо одного `mongoc_client_t` — принято потому, что
 * клиент не потокобезопасен. До этого теста оно **не было покрыто ничем**: все остальные тесты
 * последовательные, и ни spec-, ни дифференциальные сюда не дотягиваются — они про поведение,
 * а здесь UB под нагрузкой.
 *
 * Что именно проверяется:
 *
 * * одновременные операции на **одном** `MongoClient` не портят память и не теряют записи;
 * * `withClient` возвращает клиента в пул и на пути исключения — иначе пул утёк бы и следующие
 *   операции повисли на `mongoc_client_pool_pop`;
 * * курсоры, живущие одновременно, не мешают друг другу;
 * * одновременное создание клиентов не ломает одноразовую инициализацию драйвера.
 *
 * Число потоков поднято до [IO_THREADS]: с дефолтными четырьмя одновременных вызовов в C было бы
 * слишком мало, чтобы гонка успела проявиться.
 *
 * **Как этот тест сигнализирует о поломке — проверено намеренно.** Если убрать возврат клиента
 * в `finally` у `withClient`, пул вычерпывается и прогон **зависает**, а не краснеет: таймаут
 * `runTest` не спасает, потому что поток стоит внутри `mongoc_client_pool_pop` — это C-вызов,
 * а не точка приостановки, и отменить его нечем (риск 2 ресёрча в чистом виде). Проверка
 * упиралась в 10-минутный лимит внешнего прогона.
 *
 * Практический вывод: **зависший прогон этих тестов — это и есть их красный результат.**
 * Указанный ниже таймаут ловит только те дедлоки, что случаются между вызовами C, но не внутри.
 */
@OptIn(ExperimentalForeignApi::class)
class MongoConcurrencyTest {
    private val uri = TestServer.uri("serverSelectionTimeoutMS=3000&socketTimeoutMS=10000")

    private val clients = mutableListOf<MongoClient>()

    private suspend fun connect(
        ioThreads: Int = IO_THREADS,
        maxConcurrentClients: Int = MongoClient.DEFAULT_MAX_CONCURRENT_CLIENTS,
    ): MongoClient = MongoClient(uri, ioThreads, maxConcurrentClients).also { clients += it }

    @AfterTest
    fun tearDown() {
        clients.forEach { it.close() }
        clients.clear()
    }

    private fun MongoClient.freshCollection(hint: String): MongoCollection<Document> =
        getDatabase(DATABASE).getCollection("${hint}_${counter++}")

    private suspend fun MongoClient.dropTestDatabase() =
        withClient { handle ->
            val database =
                mongoc_client_get_database(handle, DATABASE)
                    ?: error("mongoc_client_get_database вернул NULL")
            try {
                memScoped { mongoc_database_drop(database, alloc<bson_error_t>().ptr) }
            } finally {
                mongoc_database_destroy(database)
            }
        }

    @Test
    fun `two hundred concurrent inserts all land on one client`() =
        runTest(timeout = TIMEOUT) {
            val client = connect()
            client.dropTestDatabase()
            val collection = client.freshCollection("insert")

            coroutineScope {
                (0 until OPERATIONS)
                    .map { n ->
                        async { collection.insertOne(document { put("n", n) }) }
                    }.awaitAll()
            }

            // Потерянная запись означала бы, что операции затоптали друг друга внутри C.
            assertEquals(OPERATIONS.toLong(), collection.countDocuments())
            // Каждое значение уникально — так видно не только количество, но и отсутствие дублей.
            assertEquals(
                OPERATIONS,
                collection
                    .find()
                    .toList()
                    .mapTo(mutableSetOf()) { it["n"] }
                    .size,
            )
        }

    @Test
    fun `mixed concurrent operations do not corrupt the client`() =
        runTest(timeout = TIMEOUT) {
            val client = connect()
            val collection = client.freshCollection("mixed")
            collection.insertMany((0 until 50).map { n -> document { put("n", n) } })

            coroutineScope {
                (0 until OPERATIONS)
                    .map { n ->
                        async {
                            when (n % 4) {
                                0 -> collection.insertOne(document { put("extra", n) })
                                1 -> collection.find(document { put("n", n % 50) }).count()
                                2 -> collection.countDocuments()
                                else -> collection.find().first()
                            }
                        }
                    }.awaitAll()
            }

            // 50 исходных + по одной вставке на каждый четвёртый номер.
            assertEquals(50L + (OPERATIONS + 3) / 4, collection.countDocuments())
        }

    @Test
    fun `failing operations still return their client to the pool`() =
        runTest(timeout = TIMEOUT) {
            val client = connect()
            val collection = client.freshCollection("failing")
            collection.insertOne(document { put("_id", 1) })

            // Каждая из этих операций падает на дубликате ключа. Если `withClient` не возвращал бы
            // клиента в `finally`, пул (100 клиентов) вычерпался бы, и прогон повис бы навсегда —
            // проверено намеренной поломкой, см. KDoc класса.
            coroutineScope {
                (0 until OPERATIONS)
                    .map {
                        async {
                            assertFailsWith<MongoException> { collection.insertOne(document { put("_id", 1) }) }
                        }
                    }.awaitAll()
            }

            assertEquals(1L, collection.countDocuments())
            assertEquals(BsonInt32(1), collection.find().first()["_id"])
        }

    @Test
    fun `many cursors live at the same time without stepping on each other`() =
        runTest(timeout = TIMEOUT) {
            val client = connect()
            val collection = client.freshCollection("cursors")
            collection.insertMany((0 until 100).map { n -> document { put("n", n) } })

            // Каждый поток держит своего клиента из пула всё время сбора. Держим заведомо меньше,
            // чем размер пула libmongoc (100 по умолчанию): за этой границей `pop` заблокирует
            // поток и получится дедлок — это известное ограничение, задача M-23.
            val collected =
                coroutineScope {
                    (0 until CONCURRENT_CURSORS)
                        .map {
                            async { collection.find().toList().size }
                        }.awaitAll()
                }

            assertTrue(collected.all { it == 100 }, "курсоры вернули разное число документов: ${collected.distinct()}")
        }

    @Test
    fun `concurrent client creation does not break one-shot driver init`() =
        runTest(timeout = TIMEOUT) {
            // `Mongkn.initialize()` вызывается из каждого конструктора. Автомат состояний должен
            // выдержать одновременный вход — второй раз `mongoc_init()` звать нельзя.
            val created =
                coroutineScope {
                    (0 until 16).map { async { connect(ioThreads = 2) } }.awaitAll()
                }

            assertEquals(16, created.size)
            assertEquals(16, created.distinct().size)
            // Клиенты рабочие, а не просто созданные.
            assertEquals(0L, created.first().freshCollection("init").countDocuments())
        }

    @Test
    fun `waiting for a client is cancellable instead of blocking a thread`() =
        runTest(timeout = TIMEOUT) {
            // Пул из одного клиента: второй операции придётся ждать.
            val client = connect(ioThreads = 4, maxConcurrentClients = 1)
            val collection = client.freshCollection("cancellable")
            collection.insertMany((0 until 20).map { n -> document { put("n", n) } })

            val holding = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()

            val holder =
                launch {
                    // Курсор держит единственного клиента всё время сбора — это и есть M-23.
                    collection.find().collect {
                        if (!holding.isCompleted) {
                            holding.complete(Unit)
                            release.await()
                        }
                    }
                }
            holding.await()

            // До семафора здесь был бы дедлок навсегда: поток встал бы внутри
            // mongoc_client_pool_pop, а его отменить нечем (§1.12). Теперь это приостановка,
            // и withTimeout её снимает.
            //
            // Почему прохождение теста вообще что-то доказывает: `runTest` крутит виртуальное
            // время, и `withTimeout` срабатывает, только когда планировщику нечего выполнять,
            // то есть когда корутина действительно **припаркована**. Будь поток заблокирован
            // внутри C, виртуальное время не сдвинулось бы и тест завис — ровно как в §1.12.
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(2.seconds) { collection.countDocuments() }
            }

            release.complete(Unit)
            holder.join()

            // Клиент вернулся в пул — операции снова проходят.
            assertEquals(20L, collection.countDocuments())
        }

    @Test
    fun `more concurrent cursors than the pool allows still complete`() =
        runTest(timeout = TIMEOUT) {
            // Курсоров вчетверо больше, чем клиентов: лишние ждут на семафоре и дожидаются.
            val client = connect(ioThreads = 4, maxConcurrentClients = 4)
            val collection = client.freshCollection("oversubscribed")
            collection.insertMany((0 until 30).map { n -> document { put("n", n) } })

            val sizes =
                coroutineScope {
                    (0 until 16).map { async { collection.find().toList().size } }.awaitAll()
                }

            assertTrue(sizes.all { it == 30 }, "курсоры вернули разное: ${sizes.distinct()}")
        }

    private companion object {
        const val DATABASE = "mongkn_stress"
        const val OPERATIONS = 200
        const val CONCURRENT_CURSORS = 32
        const val IO_THREADS = 16
        val TIMEOUT = 90.seconds
        var counter = 0
    }
}
