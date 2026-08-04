package ru.workinprogress.mongkn.bson

import kotlinx.cinterop.ExperimentalForeignApi
import mongkn.cinterop.bson_destroy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Как именно decimal128 представлен в Kotlin и что из этого следует.
 *
 * Тест документирующий: он фиксирует каноническую форму, которую отдаёт libbson, — от неё
 * зависит и равенство значений, и то, можно ли вообще положить сюда мост в BigDecimal
 * (решение Р13, задача M-38).
 */
@OptIn(ExperimentalForeignApi::class)
class BsonDecimalShapeTest {

    private fun roundTrip(value: BsonDecimal128): BsonDecimal128 {
        val native = BsonDocument("d" to value).toNativeBson()
        try {
            return native.toDocument()["d"] as BsonDecimal128
        } finally {
            bson_destroy(native)
        }
    }

    @Test
    fun `canonical form is what libbson decides`() {
        // Слева — как написал человек, справа — что реально хранится.
        assertEquals("1E-30", BsonDecimal128("0.000000000000000000000000000001").value)
        assertEquals("1234567890.123456789", BsonDecimal128("1234567890.123456789").value)
        assertEquals("-42", BsonDecimal128("-42").value)
    }

    @Test
    fun `trailing zeros are significant and survive`() {
        // В decimal128 1.0 и 1.00 — разные представления одного числа. Это ровно то, что
        // теряется при переводе в произвольный BigDecimal без указания scale.
        assertEquals("1.0", BsonDecimal128("1.0").value)
        assertEquals("1.00", BsonDecimal128("1.00").value)
        assertNotEquals(BsonDecimal128("1.0"), BsonDecimal128("1.00"))
        assertEquals(BsonDecimal128("1.00"), roundTrip(BsonDecimal128("1.00")))
    }

    @Test
    fun `special values exist and survive`() {
        for (text in listOf("NaN", "Infinity", "-Infinity", "-0")) {
            val value = BsonDecimal128(text)
            assertEquals(value, roundTrip(value), "не пережило round-trip: $text -> ${value.value}")
            println("  $text -> ${value.value}")
        }
    }

    @Test
    fun `finite values can be read as Double with the usual loss`() {
        // Единственный способ получить число сегодня: разобрать каноническую строку.
        // Научная запись Kotlin понимает, поэтому для конечных значений это работает.
        assertEquals(1e-30, BsonDecimal128("0.000000000000000000000000000001").value.toDouble())
        assertEquals(-42.0, BsonDecimal128("-42").value.toDouble())
        assertTrue(BsonDecimal128("1234567890.123456789").value.toDouble() > 1.2e9)
    }
}
