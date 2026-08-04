package ru.workinprogress.mongkn.bson

import kotlinx.cinterop.ExperimentalForeignApi
import mongkn.cinterop.bson_destroy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Критерий приёмки M-04: `Document → bson_t → Document` возвращает исходный документ.
 *
 * Именно этот тест — причина, по которой документ не `Map<String, Any>` (решение Р4):
 * на `Any` различие int32/int64 и типы `ObjectId` / `DateTime` не переживают обратный путь.
 */
@OptIn(ExperimentalForeignApi::class)
class BsonRoundTripTest {

    private fun roundTrip(source: BsonDocument): BsonDocument {
        val native = source.toNativeBson()
        try {
            return native.toDocument()
        } finally {
            bson_destroy(native)
        }
    }

    @Test
    fun `scalars survive the round trip`() {
        val source = document {
            put("string", "kotlin-native")
            put("int32", 42)
            put("int64", 9_000_000_000L)
            put("double", 3.5)
            put("boolTrue", true)
            put("boolFalse", false)
            putNull("nothing")
            put("when", BsonDateTime(1_700_000_000_000L))
            put("id", BsonObjectId.parse("6a71efcbb173221a58058212"))
        }

        assertEquals(source, roundTrip(source))
    }

    @Test
    fun `int32 and int64 do not collapse into one type`() {
        val source = document {
            put("small", 1)
            put("big", 1L)
        }

        val result = roundTrip(source)

        assertEquals(BsonInt32(1), result["small"])
        assertEquals(BsonInt64(1L), result["big"])
        // Ради этого различия и заведена sealed-иерархия: на Map<String, Any> обе единицы
        // приехали бы одним и тем же Kotlin-значением.
        assertNotEquals<BsonValue?>(result["small"], result["big"])
    }

    @Test
    fun `nested documents and arrays survive the round trip`() {
        val source = document {
            put("name", "outer")
            putDocument("nested") {
                put("a", 1)
                putDocument("deeper") { put("b", "two") }
            }
            putArray("mixed") {
                add(1)
                add("two")
                add(3.0)
                addDocument { put("four", true) }
                addArray { add(5L) }
            }
            putArray("empty") {}
        }

        assertEquals(source, roundTrip(source))
    }

    @Test
    fun `key order is preserved`() {
        val source = BsonDocument(
            "z" to BsonInt32(1),
            "a" to BsonInt32(2),
            "m" to BsonInt32(3),
        )

        // Порядок значим: в командах сервера первый ключ определяет саму команду.
        assertEquals(listOf("z", "a", "m"), roundTrip(source).keys)
    }

    @Test
    fun `empty document survives the round trip`() {
        val source = BsonDocument()

        val result = roundTrip(source)

        assertTrue(result.isEmpty())
        assertEquals(source, result)
    }

    @Test
    fun `ObjectId compares by content and renders as canonical hex`() {
        val hex = "6a71efcbb173221a58058212"
        val id = BsonObjectId.parse(hex)

        assertEquals(hex, id.hex)
        // Не data class: у ByteArray равенство ссылочное, а round-trip-тест обязан сравнивать
        // по содержимому.
        assertEquals(id, BsonObjectId(id.toByteArray()))
        assertEquals(id.hashCode(), BsonObjectId(id.toByteArray()).hashCode())
        assertNotEquals(id, BsonObjectId.parse("000000000000000000000000"))
    }

    @Test
    fun `malformed ObjectId is rejected`() {
        assertFailsWith<IllegalArgumentException> { BsonObjectId.parse("abc") }
        assertFailsWith<IllegalArgumentException> { BsonObjectId.parse("zz71efcbb173221a58058212") }
        assertFailsWith<IllegalArgumentException> { BsonObjectId(ByteArray(11)) }
    }

    @Test
    fun `document lookup returns first match and reports membership`() {
        val doc = document {
            put("a", 1)
            put("b", "two")
        }

        assertEquals(BsonInt32(1), doc["a"])
        assertEquals(BsonString("two"), doc["b"])
        assertEquals(null, doc["missing"])
        assertTrue("a" in doc)
        assertTrue("missing" !in doc)
    }
}
