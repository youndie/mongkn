package ru.workinprogress.mongkn.benchmark

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.flow.count
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
 * Что здесь **не** измеряется, чтобы числа не читались шире, чем есть: конкурентная нагрузка,
 * поведение под многими клиентами, крупные документы, сеть с задержкой. Сервер — локальный,
 * в контейнере, то есть сетевая часть занижена относительно любой настоящей установки.
 */
@Serializable
private data class Person(
    val name: String,
    val born: Int,
    val city: String,
)

private const val DATABASE = "mongkn_bench"

@OptIn(ExperimentalForeignApi::class)
fun main() {
    println("mongkn: замер надбавки поверх libmongoc")
    println("сервер: ${TestServer.host}, сборка: release, раундов: ${Bench.ROUNDS} + прогрев")

    MongoClient(TestServer.uri()).use { client ->
        val sample =
            document {
                put("name", "Ада")
                put("born", 1815)
                put("city", "Лондон")
            }

        runBlocking { client.getDatabase(DATABASE).drop() }
        insertBenchmarks(client, sample)
        findBenchmarks(client, sample)
        codecBenchmarks(sample)
        runBlocking { client.getDatabase(DATABASE).drop() }
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
