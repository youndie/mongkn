package ru.workinprogress.mongkn.benchmark

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import mongkn.cinterop.bson_destroy
import mongkn.cinterop.bson_error_t
import mongkn.cinterop.bson_t
import mongkn.cinterop.mongoc_client_get_collection
import mongkn.cinterop.mongoc_collection_destroy
import mongkn.cinterop.mongoc_collection_find_with_opts
import mongkn.cinterop.mongoc_collection_insert_one
import mongkn.cinterop.mongoc_cursor_destroy
import mongkn.cinterop.mongoc_cursor_next
import ru.workinprogress.mongkn.MongoClient
import ru.workinprogress.mongkn.bson.BsonDocument
import ru.workinprogress.mongkn.bson.Document
import ru.workinprogress.mongkn.bson.decodeFromDocument
import ru.workinprogress.mongkn.bson.document
import ru.workinprogress.mongkn.bson.encodeToDocument
import ru.workinprogress.mongkn.bson.toDocument
import ru.workinprogress.mongkn.bson.toNativeBson
import ru.workinprogress.mongkn.support.TestServer

/**
 * Замер производительности (M-76).
 *
 * Отвечает на один вопрос: **сколько стоит обвязка поверх C-драйвера**. Сравнивать mongkn
 * с официальным JVM-драйвером было бы эффектнее, но бессмысленнее: там другая среда исполнения,
 * прогрев JIT и сборщик мусора, и в сумме мерилась бы разница платформ, а не наша работа.
 * Здесь же обе стороны — один процесс, один драйвер, одна машина, и разница целиком наша.
 *
 * Запуск (обязательно release, см. `build.gradle.kts`):
 *
 * ```
 * ./gradlew :mongkn-core:runBenchmarkReleaseExecutableMacosArm64
 * ```
 *
 * Что здесь **не** измеряется, чтобы числа не читались шире, чем есть: крупные документы,
 * потребление памяти, сеть с задержкой. Сервер — локальный, в контейнере, то есть сетевая часть
 * занижена относительно любой настоящей установки.
 */
@Serializable
private data class Person(
    val name: String,
    val born: Int,
    val city: String,
)

private const val DATABASE = "mongkn_bench"

/**
 * Аргумент — имя секции; без него выполняются все.
 *
 * Заведено не для удобства, а ради профилировщика (M-77): чтение занимает несколько процентов
 * общего времени прогона, и профиль всего бенчмарка описывал бы вставки и конкурентную нагрузку.
 * `benchmark.kexe read` даёт профиль, в котором видно только то, что разбирается.
 */
@OptIn(ExperimentalForeignApi::class)
fun main(args: Array<String>) {
    val only = args.firstOrNull()
    println("mongkn: замер надбавки поверх libmongoc")
    println(
        "сервер: ${TestServer.host}, сборка: release, раундов: ${Bench.ROUNDS} + прогрев" +
            if (only == null) "" else ", только секция: $only",
    )

    MongoClient(TestServer.uri()).use { client ->
        val sample =
            document {
                put("name", "Ада")
                put("born", 1815)
                put("city", "Лондон")
            }

        runBlocking { client.getDatabase(DATABASE).drop() }
        if (only == null) insertBenchmarks(client, sample)
        // Чтению нужны засеянные документы, поэтому засев идёт и в режиме одной секции.
        if (only == null || only == "read") {
            findBenchmarks(client, sample)
            readPathBenchmarks(client)
        }
        if (only == null) codecBenchmarks(sample)
        runBlocking { client.getDatabase(DATABASE).drop() }
    }
    if (only == null) {
        concurrencyBenchmarks()
        MongoClient(TestServer.uri()).use { client ->
            runBlocking { client.getDatabase(DATABASE).drop() }
        }
    }
}

/**
 * Вставка: три уровня, чтобы надбавка была разложена, а не свалена в одно число.
 *
 * 1. голый C с переиспользованием дескриптора и готовым bson — абсолютный пол;
 * 2. тот же C, но так, **как его зовёт mongkn**: дескриптор берётся на каждую операцию,
 *    bson собирается из [Document] каждый раз;
 * 3. публичный API mongkn.
 *
 * Разница 1→2 — цена выбранного способа звать драйвер, разница 2→3 — цена самого Kotlin-слоя
 * (корутины, семафор, переключение на пул потоков, разбор ответа).
 */
@OptIn(ExperimentalForeignApi::class)
private fun insertBenchmarks(
    client: MongoClient,
    sample: Document,
) {
    val operations = 2_000
    Bench.section("Вставка одного документа ($operations операций в раунде)")

    val floor =
        Bench.measure(operations) { count ->
            runBlocking {
                client.withClient { handle ->
                    val collection = mongoc_client_get_collection(handle, DATABASE, "floor")!!
                    val payload = sample.toNativeBson()
                    memScoped {
                        val error = alloc<bson_error_t>()
                        repeat(count) {
                            mongoc_collection_insert_one(collection, payload, null, null, error.ptr)
                        }
                    }
                    bson_destroy(payload)
                    mongoc_collection_destroy(collection)
                }
            }
        }

    val asCalled =
        Bench.measure(operations) { count ->
            runBlocking {
                client.withClient { handle ->
                    repeat(count) {
                        val collection = mongoc_client_get_collection(handle, DATABASE, "as_called")!!
                        val payload = sample.toNativeBson()
                        memScoped {
                            val error = alloc<bson_error_t>()
                            mongoc_collection_insert_one(collection, payload, null, null, error.ptr)
                        }
                        bson_destroy(payload)
                        mongoc_collection_destroy(collection)
                    }
                }
            }
        }

    val collection = client.getDatabase(DATABASE).getCollection("mongkn")
    val viaApi =
        Bench.measure(operations) { count ->
            runBlocking { repeat(count) { collection.insertOne(sample) } }
        }

    Bench.compare("способ вызова", "голый C", floor, "C как зовёт mongkn", asCalled)
    Bench.compare("слой Kotlin", "C как зовёт mongkn", asCalled, "mongkn", viaApi)
    Bench.compare("итого", "голый C", floor, "mongkn", viaApi)
}

/**
 * Чтение: курсор против `Flow`.
 *
 * Пол считается честно — с переводом каждого документа в Kotlin. Курсор, который только считает
 * документы и не трогает их содержимое, был бы полом другой задачи: mongkn обязан отдать
 * [Document], и не сравнивать это не с чем.
 */
@OptIn(ExperimentalForeignApi::class)
private fun findBenchmarks(
    client: MongoClient,
    sample: Document,
) {
    val documents = 5_000
    val source = client.getDatabase(DATABASE).getCollection("read")
    runBlocking {
        source.drop()
        source.insertMany(List(documents) { sample })
    }

    Bench.section("Чтение $documents документов курсором (1 проход = 1 операция раунда)")

    val rounds = 20
    val floor =
        Bench.measure(rounds) { count ->
            runBlocking {
                client.withClient { handle ->
                    repeat(count) {
                        val collection = mongoc_client_get_collection(handle, DATABASE, "read")!!
                        val filter = BsonDocument().toNativeBson()
                        val opts = BsonDocument().toNativeBson()
                        val cursor = mongoc_collection_find_with_opts(collection, filter, opts, null)!!
                        memScoped {
                            val current = allocPointerTo<bson_t>()
                            var seen = 0
                            while (mongoc_cursor_next(cursor, current.ptr)) {
                                current.value?.toDocument()
                                seen++
                            }
                            check(seen == documents) { "прочитано $seen вместо $documents" }
                        }
                        mongoc_cursor_destroy(cursor)
                        bson_destroy(opts)
                        bson_destroy(filter)
                        mongoc_collection_destroy(collection)
                    }
                }
            }
        }

    val viaApi =
        Bench.measure(rounds) { count ->
            runBlocking {
                repeat(count) {
                    val seen = source.find().count()
                    check(seen == documents) { "прочитано $seen вместо $documents" }
                }
            }
        }

    println("  (на операцию раунда приходится $documents документов)")
    Bench.compare("проход по $documents документам", "курсор C + toDocument", floor, "mongkn Flow", viaApi)
}

/**
 * Из чего складывается надбавка пути чтения (M-77).
 *
 * [findBenchmarks] отвечает, **сколько** стоит путь чтения целиком; здесь — **где** это тратится.
 *
 * Лестница из пяти уровней, каждый добавляет ровно один слой к предыдущему, и разность соседей
 * и есть цена слоя:
 *
 * 1. `курсор` — только `mongoc_cursor_next`, документ не трогаем. Пол абсолютный: столько стоит
 *    сам драйвер и сеть до локального сервера;
 * 2. `+ toDocument` — перевод каждого документа в Kotlin. Это пол [findBenchmarks];
 * 3. `+ flow` — та же работа внутри `flow { }`, собираемого **в том же контексте**. Разность
 *    со второй ступенью — цена машинерии потока: приостановка на `emit` и возобновление;
 * 4. `+ flowOn` — то же с `.flowOn(dispatcher)`, как в `CollectionOps.find`. Здесь между
 *    производителем и потребителем появляется канал, и каждый документ пересекает границу
 *    контекстов;
 * 5. `mongkn.find()` — публичный API целиком: плюс семафор, аренда клиента из пула, разбор опций.
 *
 * Профилировщик отвечал бы на тот же вопрос стеками, но на Kotlin/Native половина кадров там —
 * машинерия корутин, и «где время» пришлось бы додумывать. Лестница отвечает разностями замеров:
 * каждое число получено тем же способом, что и остальные, и сравнивать их между собой законно.
 */
@OptIn(ExperimentalForeignApi::class)
private fun readPathBenchmarks(client: MongoClient) {
    val documents = 5_000
    val source = client.getDatabase(DATABASE).getCollection("read")
    val rounds = 20

    Bench.section("Из чего состоит надбавка чтения (проход по $documents документам)")

    // Курсор открывается и освобождается на каждом уровне одинаково; вынести это в общую функцию
    // нельзя: на третьем и четвёртом уровнях внутрь попадает `emit`, а он suspend, и обычная
    // лямбда его не примет. Ровно по этой причине `MongoClient.useClient` объявлен `inline`.
    val cursorOnly =
        Bench.measure(rounds) { count ->
            runBlocking {
                repeat(count) {
                    client.withClient { handle ->
                        val collection = mongoc_client_get_collection(handle, DATABASE, "read")!!
                        val filter = BsonDocument().toNativeBson()
                        val opts = BsonDocument().toNativeBson()
                        val cursor = mongoc_collection_find_with_opts(collection, filter, opts, null)!!
                        memScoped {
                            val current = allocPointerTo<bson_t>()
                            var seen = 0
                            while (mongoc_cursor_next(cursor, current.ptr)) seen++
                            check(seen == documents) { "прочитано $seen вместо $documents" }
                        }
                        mongoc_cursor_destroy(cursor)
                        bson_destroy(opts)
                        bson_destroy(filter)
                        mongoc_collection_destroy(collection)
                    }
                }
            }
        }

    val withConversion =
        Bench.measure(rounds) { count ->
            runBlocking {
                repeat(count) {
                    client.withClient { handle ->
                        val collection = mongoc_client_get_collection(handle, DATABASE, "read")!!
                        val filter = BsonDocument().toNativeBson()
                        val opts = BsonDocument().toNativeBson()
                        val cursor = mongoc_collection_find_with_opts(collection, filter, opts, null)!!
                        memScoped {
                            val current = allocPointerTo<bson_t>()
                            var seen = 0
                            while (mongoc_cursor_next(cursor, current.ptr)) {
                                current.value?.toDocument()
                                seen++
                            }
                            check(seen == documents) { "прочитано $seen вместо $documents" }
                        }
                        mongoc_cursor_destroy(cursor)
                        bson_destroy(opts)
                        bson_destroy(filter)
                        mongoc_collection_destroy(collection)
                    }
                }
            }
        }

    /** Тот же цикл, что в `Cursor.drainCursor`, — уровни должны отличаться ровно одним слоем. */
    fun cursorFlow(): Flow<Document> =
        flow {
            client.withPermit {
                client.useClient { handle ->
                    val collection = mongoc_client_get_collection(handle, DATABASE, "read")!!
                    val filter = BsonDocument().toNativeBson()
                    val opts = BsonDocument().toNativeBson()
                    val cursor = mongoc_collection_find_with_opts(collection, filter, opts, null)!!
                    try {
                        memScoped {
                            val current = allocPointerTo<bson_t>()
                            while (mongoc_cursor_next(cursor, current.ptr)) {
                                emit(current.value?.toDocument() ?: error("NULL при true"))
                            }
                        }
                    } finally {
                        mongoc_cursor_destroy(cursor)
                        bson_destroy(opts)
                        bson_destroy(filter)
                        mongoc_collection_destroy(collection)
                    }
                }
            }
        }

    val viaFlow =
        Bench.measure(rounds) { count ->
            runBlocking {
                repeat(count) {
                    val seen = cursorFlow().count()
                    check(seen == documents) { "прочитано $seen вместо $documents" }
                }
            }
        }

    val viaFlowOn =
        Bench.measure(rounds) { count ->
            runBlocking {
                repeat(count) {
                    val seen = cursorFlow().flowOn(client.dispatcher).count()
                    check(seen == documents) { "прочитано $seen вместо $documents" }
                }
            }
        }

    val viaApi =
        Bench.measure(rounds) { count ->
            runBlocking {
                repeat(count) {
                    val seen = source.find().count()
                    check(seen == documents) { "прочитано $seen вместо $documents" }
                }
            }
        }

    fun perDocument(result: Bench.Result): String = Bench.format(result.perOperation / documents)

    println("  на документ, мкс:")
    println("    1. курсор без перевода:       ${perDocument(cursorOnly)}")
    println("    2. + toDocument:              ${perDocument(withConversion)}")
    println("    3. + flow (тот же контекст):  ${perDocument(viaFlow)}")
    println("    4. + flowOn(dispatcher):      ${perDocument(viaFlowOn)}")
    println("    5. mongkn.find():             ${perDocument(viaApi)}")
    println("  цена слоёв, мкс на документ:")
    println(
        "    перевод в Document: ${
            Bench.format((withConversion.perOperation - cursorOnly.perOperation) / documents)
        }",
    )
    println(
        "    машинерия Flow:     ${
            Bench.format((viaFlow.perOperation - withConversion.perOperation) / documents)
        }",
    )
    println(
        "    переход контекста:  ${
            Bench.format((viaFlowOn.perOperation - viaFlow.perOperation) / documents)
        }",
    )
    println(
        "    остальное в API:    ${
            Bench.format((viaApi.perOperation - viaFlowOn.perOperation) / documents)
        }",
    )
}

/**
 * Конкурентная нагрузка: как пропускная способность зависит от числа корутин и потоков.
 *
 * Проверяет гипотезу, которую больше нечем проверить: разрешений семафора по умолчанию **сто**
 * ([MongoClient.DEFAULT_MAX_CONCURRENT_CLIENTS]), а потоков под блокирующие вызовы — **четыре**
 * ([MongoClient.DEFAULT_IO_THREADS]). Вызов libmongoc блокирующий, значит одновременно их может
 * идти столько, сколько потоков, а не сколько разрешений. Если это так, конкурентность выше
 * четырёх при умолчаниях не даёт ничего, и настоящая ручка — `ioThreads`, а не пул клиентов.
 *
 * Отдельно проверяется, что превышение числа разрешений не ломает ничего: до появления семафора
 * исчерпание пула означало неотменяемую блокировку внутри C (§1.12 ресёрча).
 *
 * Корутины-заказчики крутятся на одном потоке `runBlocking`. Это не искажает результат:
 * они почти всё время ждут, а работа уходит на пул потоков клиента.
 */
private fun concurrencyBenchmarks() {
    val operations = 2_000
    Bench.section("Конкурентная нагрузка ($operations вставок, распределённых по корутинам)")

    fun run(
        label: String,
        ioThreads: Int,
        concurrency: Int,
        maxClients: Int = MongoClient.DEFAULT_MAX_CONCURRENT_CLIENTS,
    ) {
        MongoClient(TestServer.uri(), ioThreads = ioThreads, maxConcurrentClients = maxClients).use { client ->
            val collection = client.getDatabase(DATABASE).getCollection("load")
            val perCoroutine = operations / concurrency
            val result =
                Bench.measure(perCoroutine * concurrency, rounds = 3) {
                    runBlocking {
                        coroutineScope {
                            (0 until concurrency)
                                .map {
                                    async { repeat(perCoroutine) { collection.insertOne(sampleDocument()) } }
                                }.awaitAll()
                        }
                    }
                }
            val perSecond = (1_000_000.0 / result.perOperation).toLong()
            println("    $label: $result -> $perSecond оп/с")
        }
    }

    println("  потоков 4 (умолчание), растёт число корутин:")
    for (concurrency in listOf(1, 2, 4, 8, 16, 32)) {
        run("корутин ${concurrency.toString().padStart(2)}", ioThreads = 4, concurrency = concurrency)
    }

    println("  корутин 64, растёт число потоков (ищем, где рост кончается):")
    for (threads in listOf(1, 2, 4, 8, 16, 32, 48, 64, 96)) {
        run("потоков ${threads.toString().padStart(3)}", ioThreads = threads, concurrency = 64)
    }

    // Потоки создаются в конструкторе клиента, а не лениво: если их много, за это платит
    // каждое создание MongoClient. Число нужно для выбора умолчания (M-78).
    println("  цена создания клиента при разном числе потоков:")
    for (threads in listOf(4, 16, 64, 96)) {
        val creation =
            Bench.measure(20, rounds = 3) { count ->
                repeat(count) { MongoClient(TestServer.uri(), ioThreads = threads).close() }
            }
        println("    потоков ${threads.toString().padStart(3)}: $creation")
    }

    println("  корутин 64 при 8 разрешениях — семафор обязан выстроить очередь, а не сломаться:")
    run("разрешений 8", ioThreads = 16, concurrency = 64, maxClients = 8)
}

private fun sampleDocument(): Document =
    document {
        put("name", "Ада")
        put("born", 1815)
        put("city", "Лондон")
    }

/** Работа с BSON без сервера: здесь надбавка видна в чистом виде, а не тонет в сетевом времени. */
@OptIn(ExperimentalForeignApi::class)
private fun codecBenchmarks(sample: Document) {
    val operations = 200_000
    Bench.section("BSON без сервера ($operations операций в раунде)")

    val roundTrip =
        Bench.measure(operations) { count ->
            repeat(count) {
                val native = sample.toNativeBson()
                native.toDocument()
                bson_destroy(native)
            }
        }
    println("  Document → bson_t → Document: $roundTrip")

    val person = Person(name = "Ада", born = 1815, city = "Лондон")
    val encode =
        Bench.measure(operations) { count ->
            repeat(count) { encodeToDocument(Person.serializer(), person) }
        }
    println("  класс → Document (kotlinx.serialization): $encode")

    val encoded = encodeToDocument(Person.serializer(), person)
    val decode =
        Bench.measure(operations) { count ->
            repeat(count) { decodeFromDocument(Person.serializer(), encoded) }
        }
    println("  Document → класс: $decode")

    Bench.compare(
        "цена типизированной коллекции против Document",
        "сборка Document вручную",
        Bench.measure(operations) { count ->
            repeat(count) {
                document {
                    put("name", "Ада")
                    put("born", 1815)
                    put("city", "Лондон")
                }
            }
        },
        "кодек класса",
        encode,
    )
}
