package io.github.mongkn

import io.github.mongkn.support.TestServer
import io.github.mongkn.bson.BsonDateTime
import io.github.mongkn.bson.BsonDocument
import io.github.mongkn.bson.BsonInt64
import io.github.mongkn.bson.BsonObjectId
import io.github.mongkn.bson.Document
import io.github.mongkn.bson.document
import io.github.mongkn.bson.toDocument
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mongkn.cinterop.bson_destroy
import mongkn.cinterop.bson_error_t
import mongkn.cinterop.bson_init
import mongkn.cinterop.bson_json_reader_destroy
import mongkn.cinterop.bson_json_reader_new_from_file
import mongkn.cinterop.bson_json_reader_read
import mongkn.cinterop.bson_t
import platform.posix.getenv
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Дифференциальный тест против официального JVM-драйвера (M-28).
 *
 * Официальный драйвер живёт только на JVM, mongkn — только на Native, в одном процессе их
 * не свести. Поэтому круг замкнут через общий mongod и файл-фикстуру, тремя фазами:
 *
 * 1. `:mongkn-difftest:seedDiffReference` — эталон пишет документ в `mongkn_diff.reference`
 *    и выгружает его canonical extended JSON;
 * 2. **этот тест** — mongkn читает тот же документ с сервера и сверяет с фикстурой
 *    (проверка **декодера**), затем пишет свою копию в `mongkn_diff.written`;
 * 3. `:mongkn-difftest:verifyDiffWritten` — эталон читает написанное и сверяет с собой
 *    (проверка **кодировщика**).
 *
 * Ценность именно в том, что эталон здесь не мои ожидания, а работающая реализация MongoDB.
 * Обычные интеграционные тесты проверяют, что `deletedCount` равен единице, потому что я так
 * решил; этот — что наш BSON неотличим от эталонного.
 *
 * [expected] намеренно **дублирует** `ReferenceDocument` из JVM-модуля: смысл дифференциального
 * теста в том, что две независимые реализации сошлись, а не в том, что одна переиспользовала
 * данные другой.
 */
@OptIn(ExperimentalForeignApi::class)
class MongoDifferentialTest {

    private val uri = TestServer.uri("serverSelectionTimeoutMS=3000")

    private val clients = mutableListOf<MongoClient>()

    @AfterTest
    fun tearDown() {
        clients.forEach { it.close() }
        clients.clear()
    }

    private fun connect(): MongoClient = MongoClient(uri).also { clients += it }

    /** Тот же документ, что собирает официальный драйвер, — но средствами mongkn. */
    private val expected: Document = document {
        put("_id", BsonObjectId.parse("6a71efcbb173221a58058212"))
        put("string", "kotlin-native")
        put("emptyString", "")
        put("unicode", "документ ✓")
        put("embeddedNul", "a\u0000b")
        put("int32", 42)
        put("int32Negative", -1)
        put("int64", 9_000_000_000L)
        put("double", 3.5)
        put("boolTrue", true)
        put("boolFalse", false)
        putNull("nothing")
        put("when", BsonDateTime(1_700_000_000_000L))
        put("oid", BsonObjectId.parse("000000000000000000000001"))
        putDocument("nested") {
            put("a", 1)
            putDocument("deeper") { put("b", "two") }
        }
        putArray("array") {
            add(1)
            add("two")
            add(3.0)
            addDocument { put("four", true) }
            addArray { add(5L) }
        }
        putArray("emptyArray") {}
        putDocument("emptyDocument") {}
    }

    @Test
    fun `mongkn reads exactly what the official driver wrote`() = runTest {
        val collection = connect().getDatabase(DATABASE).getCollection(REFERENCE)

        val fromServer = collection.find().first()

        // Сверка с фикстурой — это сверка с тем, что официальный драйвер сам считает
        // каноническим представлением своего документа.
        assertEquals(readFixture(), fromServer, "документ с сервера разошёлся с фикстурой эталона")
        assertEquals(expected, fromServer, "документ с сервера разошёлся с ожидаемым в mongkn")
    }

    @Test
    fun `mongkn writes a document for the official driver to verify`() = runTest {
        val collection = connect().getDatabase(DATABASE).getCollection(WRITTEN)

        val result = collection.insertOne(expected)

        // _id задан явно, поэтому сервер обязан вернуть его же — заодно проверка Р3.
        assertEquals(BsonObjectId.parse("6a71efcbb173221a58058212"), result.insertedId)
        // Сверку выполнит фаза C: `:mongkn-difftest:verifyDiffWritten`.
    }

    @Test
    fun `int64 does not degrade to int32 through a real server round trip`() = runTest {
        val collection = connect().getDatabase(DATABASE).getCollection(REFERENCE)

        val fromServer = collection.find().first()

        // Самое ценное различие, ради которого заведена sealed-иерархия (решение Р4):
        // на Map<String, Any> оно бы не пережило ни один из двух переходов.
        assertEquals(BsonInt64(9_000_000_000L), fromServer["int64"])
        assertEquals(io.github.mongkn.bson.BsonInt32(42), fromServer["int32"])
    }

    /**
     * Читает фикстуру, выгруженную эталоном.
     *
     * JSON разбирает сама libbson (`bson_json_reader_*`) — парсер тащить не надо, он уже
     * слинкован. Побочная выгода: сравнение получается по значениям, а не по тексту, так что
     * различия в пробелах и экранировании ничего не ломают.
     */
    private fun readFixture(): BsonDocument = memScoped {
        val path = getenv(FIXTURE_ENV)?.toKString()
            ?: error(
                "не задана переменная $FIXTURE_ENV: тест запущен в обход " +
                    ":mongkn-difftest:seedDiffReference"
            )
        val error = alloc<bson_error_t>()
        val reader = bson_json_reader_new_from_file(path, error.ptr)
            ?: error("не открылась фикстура $path: ${error.message.toKString()}")
        try {
            val target = alloc<bson_t>()
            bson_init(target.ptr)
            try {
                when (bson_json_reader_read(reader, target.ptr, error.ptr)) {
                    1 -> target.ptr.toDocument()
                    0 -> error("фикстура $path пуста")
                    else -> error("фикстура $path не разобралась: ${error.message.toKString()}")
                }
            } finally {
                bson_destroy(target.ptr)
            }
        } finally {
            bson_json_reader_destroy(reader)
        }
    }

    private companion object {
        const val DATABASE = "mongkn_diff"
        const val REFERENCE = "reference"
        const val WRITTEN = "written"
        const val FIXTURE_ENV = "MONGKN_DIFF_FIXTURE"
    }
}
