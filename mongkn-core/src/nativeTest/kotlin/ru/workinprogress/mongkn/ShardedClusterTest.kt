package ru.workinprogress.mongkn

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import ru.workinprogress.mongkn.bson.BsonArray
import ru.workinprogress.mongkn.bson.BsonDocument
import ru.workinprogress.mongkn.bson.BsonInt32
import ru.workinprogress.mongkn.bson.BsonInt64
import ru.workinprogress.mongkn.bson.BsonString
import ru.workinprogress.mongkn.bson.Document
import ru.workinprogress.mongkn.bson.document
import ru.workinprogress.mongkn.support.TestServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Шардированный кластер (M-66) — четвёртый тестовый контур.
 *
 * Заводится ради двух вещей, которых нет больше нигде.
 *
 * **Первая — `readPreference` здесь исполняется, а не только выбирает сервер.** На реплика-сете
 * предпочтение живёт целиком на стороне клиента: драйвер выбирает узел и на этом всё, поэтому
 * `OptionsAndDatabaseTest` может доказать только, что настройка доехала до libmongoc. Через
 * mongos она уезжает **в самой команде** (`$readPreference`), и решение принимает кластер.
 * Разница видна и в отказе: неудача выбора вторичного узла приходит от сервера
 * (`FailedToSatisfyReadPreference`), а не от клиента, как на одном узле.
 *
 * **Вторая — есть что сливать.** Ответ на `find` собирается из двух шардов, и сортировка,
 * подсчёт и агрегация обязаны это учитывать. Одноузловые контуры такой ошибки не поймали бы:
 * там слияние тривиально.
 *
 * Контур поднимается вместе с остальными (`ci/dev-servers.sh up`) и стоит около 410 МБ;
 * без него эти тесты падают, а не пропускаются, — как и весь остальной интеграционный набор.
 */
class ShardedClusterTest {
    /**
     * `replicaSet` в строке подключения быть не должно: по нему драйвер решил бы, что перед ним
     * реплика-сет, и пошёл бы к mongos не тем путём. Топологию он определяет сам.
     */
    private val uri = TestServer.shardUri("serverSelectionTimeoutMS=5000&socketTimeoutMS=15000")

    private val clients = mutableListOf<MongoClient>()

    @AfterTest
    fun tearDown() {
        clients.forEach { it.close() }
        clients.clear()
    }

    /**
     * Клиент к mongos; при первом обращении база чистится.
     *
     * Чистка обязательна и не является перестраховкой: имена коллекций складываются из счётчика,
     * который у каждого прогона начинается с нуля, а `_id` документов здесь — числа. Оставшаяся
     * от прошлого прогона коллекция даёт `duplicate key`, и тест падает на второй раз, пройдя
     * на первый. Ровно так и случилось.
     */
    private suspend fun connect(listener: CommandListener? = null): MongoClient =
        MongoClient(uri, commandListener = listener).also { client ->
            clients += client
            if (!cleaned) {
                client.getDatabase(DATABASE).drop()
                cleaned = true
            }
        }

    /**
     * Шардированная коллекция с **хешированным** ключом.
     *
     * Хешированный, а не по диапазону: диапазонный ключ на свежей коллекции даёт один чанк
     * на одном шарде, и «распределение» пришлось бы устраивать руками — разрезать чанк
     * и двигать его на второй шард. Хешированный ключ сервер сам раскладывает по всем шардам
     * при создании коллекции, и данные оказываются на обоих без единой служебной команды.
     */
    private suspend fun sharded(
        client: MongoClient,
        hint: String,
    ): MongoCollection<Document> {
        val database = client.getDatabase(DATABASE)
        val admin = client.getDatabase("admin")
        val name = "${hint}_${counter++}"

        admin.runCommand(document { put("enableSharding", DATABASE) })
        // Коллекция создаётся заранее: `shardCollection` создал бы её сам, но подписке (`watch`)
        // нужна существующая коллекция, и один порядок для всех тестов надёжнее двух.
        database.createCollection(name)
        admin.runCommand(
            document {
                put("shardCollection", "$DATABASE.$name")
                putDocument("key") { put("_id", "hashed") }
            },
        )
        return database.getCollection(name)
    }

    private suspend fun seed(
        collection: MongoCollection<Document>,
        count: Int = DOCUMENTS,
    ) {
        collection.insertMany(
            (0 until count).map { n ->
                document {
                    put("_id", n)
                    put("n", n)
                }
            },
        )
    }

    /**
     * Сколько документов лежит на каждом шарде.
     *
     * `$collStats` на шардированной коллекции возвращает **по записи на шард**, и в каждой есть
     * поле `shard`. Это и ответ на вопрос о распределении, и заодно доказательство, что наша
     * агрегация читает слитый ответ, а не первый попавшийся.
     */
    private suspend fun perShard(collection: MongoCollection<Document>): Map<String, Long> =
        collection
            .aggregate(listOf(document { putDocument("\$collStats") { putDocument("count") {} } }))
            .toList()
            .associate { stats ->
                val shard = (stats["shard"] as? BsonString)?.value ?: error("в \$collStats нет shard: $stats")
                // Тип счётчика сервер выбирает сам; молча считать неизвестный тип нулём значило бы
                // получать зелёный тест на пустом шарде.
                val count =
                    when (val value = stats["count"]) {
                        is BsonInt32 -> value.value.toLong()
                        is BsonInt64 -> value.value
                        else -> error("неожиданный count в \$collStats: $stats")
                    }
                shard to count
            }

    private fun numbers(documents: List<Document>): List<Int> = documents.map { (it["n"] as BsonInt32).value }

    @Test
    fun `the entry point really is a mongos`() =
        runTest {
            val client = connect()
            val admin = client.getDatabase("admin")

            // Без этой проверки весь файл мог бы зеленеть против обычного mongod: команды те же,
            // ответы похожи, и «шардированные» тесты молча проверяли бы одноузловой сервер.
            // `isdbgrid` — то, чем mongos представляется в ответе `hello`, и единственный
            // признак топологии, доступный клиенту.
            val hello = admin.runCommand(document { put("hello", 1) })
            assertEquals(BsonString("isdbgrid"), hello["msg"])

            val shards = admin.runCommand(document { put("listShards", 1) })["shards"] as BsonArray
            assertEquals(2, shards.values.size, "шардов должно быть два: $shards")
        }

    @Test
    fun `documents land on both shards`() =
        runTest {
            val collection = sharded(connect(), "spread")
            seed(collection)

            val distribution = perShard(collection)

            // Два шарда, и на каждом что-то есть: иначе кластер собран, но данные лежат
            // как на одиночном сервере, и всё остальное в этом файле ничего не проверяет.
            assertEquals(2, distribution.size, "распределение: $distribution")
            assertTrue(distribution.values.all { it > 0 }, "шард без документов: $distribution")
            assertEquals(DOCUMENTS.toLong(), distribution.values.sum(), "распределение: $distribution")
        }

    @Test
    fun `reads see the whole collection and not one shard`() =
        runTest {
            val collection = sharded(connect(), "whole")
            seed(collection)

            // Каждый путь чтения собирает ответ из двух источников по-своему, поэтому проверяются
            // все, а не один: у `find` это курсор поверх слияния, у `countDocuments` —
            // суммирование, у `distinct` — объединение множеств.
            assertEquals(DOCUMENTS, collection.find().toList().size)
            assertEquals(DOCUMENTS.toLong(), collection.countDocuments())
            assertEquals(DOCUMENTS, collection.distinct("n").size)
        }

    @Test
    fun `a sorted read merges the shards in order`() =
        runTest {
            val collection = sharded(connect(), "sorted")
            seed(collection)

            val ascending = collection.find().sort(document { put("n", 1) }).toList()

            // Порядок — единственное, что нельзя получить, просто склеив ответы шардов: сливать
            // их обязан mongos, а вычитывать слияние по батчам — наш курсор.
            assertEquals((0 until DOCUMENTS).toList(), numbers(ascending))
            assertEquals(
                (0 until 5).toList(),
                numbers(
                    collection
                        .find()
                        .sort(document { put("n", 1) })
                        .limit(5)
                        .toList(),
                ),
            )
        }

    @Test
    fun `a read preference travels inside the command`() =
        runTest {
            val recorder = RecordedCommands()
            val collection = sharded(connect(recorder), "preference_wire")
            seed(collection, count = 2)

            recorder.clear()
            collection
                .withReadPreference(ReadPreference(ReadPreferenceMode.SECONDARY_PREFERRED))
                .find()
                .toList()

            // Вот оно, наблюдаемое поведение: к mongos предпочтение уходит **в команде**,
            // потому что выбирать узел будет кластер, а не драйвер. На реплика-сете этого поля
            // в команде нет вовсе — там предпочтение расходуется на выбор сервера у клиента.
            val preference = recorder.commandOf("find")["\$readPreference"] as? BsonDocument
            assertNotNull(preference, "в команде find нет \$readPreference: ${recorder.commandOf("find")}")
            assertEquals(BsonString("secondaryPreferred"), preference["mode"])

            recorder.clear()
            collection.withReadPreference(ReadPreference(ReadPreferenceMode.PRIMARY)).find().toList()

            // Обратная сторона, без которой предыдущая проверка ничего не стоит: `primary` —
            // умолчание, и спецификация запрещает его отправлять. Поле, появляющееся всегда,
            // доказывало бы не работу настройки, а её игнорирование.
            assertNull(
                recorder.commandOf("find")["\$readPreference"],
                "для primary поле отправлять не следует: ${recorder.commandOf("find")}",
            )
        }

    @Test
    fun `a read preference is enforced by the cluster and not by server selection`() =
        runTest {
            val collection = sharded(connect(), "preference_enforced")
            seed(collection, count = 2)

            val failure =
                assertFailsWith<MongoException> {
                    collection.withReadPreference(ReadPreference(ReadPreferenceMode.SECONDARY)).find().toList()
                }

            // Вторичных узлов у наших одноузловых шардов нет, поэтому чтение обязано не удаться.
            // Важно **где**: на реплика-сете тот же запрос падает у клиента, доменом
            // SERVER_SELECTION (см. OptionsAndDatabaseTest), а здесь отказ приходит от кластера
            // ответом на команду. Это и есть разница между «настройка доехала» и «настройка
            // исполнена»: сравнение доменов — самая точная её формулировка, доступная тесту.
            assertEquals(MongoErrorDomain.SERVER, failure.errorDomain, "ошибка: ${failure.message}")
            assertEquals(FAILED_TO_SATISFY_READ_PREFERENCE, failure.code, "ошибка: ${failure.message}")

            // Тот же механизм, но с разрешённым первичным узлом, читает: иначе тест был бы
            // зелёным и при полностью сломанном чтении через mongos.
            assertEquals(
                2,
                collection
                    .withReadPreference(ReadPreference(ReadPreferenceMode.SECONDARY_PREFERRED))
                    .find()
                    .toList()
                    .size,
            )
        }

    /**
     * Подписка через mongos.
     *
     * `runBlocking`, а не `runTest`, по той же причине, что и во всём `ChangeStreamTest`:
     * подписка живёт на настоящих миллисекундах, а `runTest` подменяет время виртуальным.
     */
    @Test
    fun `a change stream works through mongos`() =
        runBlocking {
            val collection = sharded(connect(), "watch")

            val events =
                async {
                    withTimeout(WATCH_TIMEOUT) { collection.watch().take(2).toList() }
                }
            delay(SETTLE)
            // Два документа, а не один: на шардированном кластере поток изменений собирается
            // из потоков всех шардов, и событие с «чужого» шарда — ровно то, что здесь новое.
            seed(collection, count = 2)

            val operations = events.await().map { (it["operationType"] as BsonString).value }
            assertEquals(listOf("insert", "insert"), operations)
        }

    @Test
    fun `a transaction spans both shards`() =
        runBlocking {
            val client = connect()
            val collection = sharded(client, "transaction")
            val reader = connect().getDatabase(DATABASE).getCollection(collection.name)

            client.startSession().use { session ->
                val inside = session.getDatabase(DATABASE).getCollection(collection.name)
                session.withTransaction {
                    seed(inside, count = TRANSACTION_DOCUMENTS)

                    // Проверяется изоляция, а не «коммит не упал»: тест, смотрящий только
                    // на результат после фиксации, одинаково зелен и с транзакцией, и без неё.
                    assertEquals(0, reader.find().toList().size)
                }
            }

            assertEquals(TRANSACTION_DOCUMENTS, reader.find().toList().size)

            // Записи легли на оба шарда, то есть фиксация была распределённой (два участника
            // и протокол в две фазы), а не обычной однонодовой. Ради этого весь тест и написан.
            val distribution = perShard(reader)
            assertEquals(2, distribution.size, "распределение: $distribution")
            assertTrue(distribution.values.all { it > 0 }, "транзакция задела один шард: $distribution")
        }

    /** Наблюдатель, помнящий последнюю команду каждого имени. */
    private class RecordedCommands : CommandListener {
        private val last = mutableMapOf<String, Document>()

        override fun started(event: CommandStartedEvent) {
            last[event.commandName] = event.command
        }

        fun clear() {
            last.clear()
        }

        fun commandOf(name: String): Document = last[name] ?: error("команда '$name' не отправлялась: ${last.keys}")
    }

    private companion object {
        const val DATABASE = "mongkn_m66"

        /** Столько документов хватает, чтобы хеш разложил их по обоим шардам с запасом. */
        const val DOCUMENTS = 200

        /**
         * Внутри транзакции документов меньше: распределённая фиксация небесплатна, а двадцати
         * хеш-ключей достаточно, чтобы оба шарда получили свою долю (вероятность обратного —
         * порядка одной миллионной).
         */
        const val TRANSACTION_DOCUMENTS = 20

        /** `FailedToSatisfyReadPreference` — код сервера, а не драйвера. */
        const val FAILED_TO_SATISFY_READ_PREFERENCE: UInt = 133u

        const val WATCH_TIMEOUT = 30_000L
        const val SETTLE = 1_000L

        var counter = 0
        var cleaned = false
    }
}
