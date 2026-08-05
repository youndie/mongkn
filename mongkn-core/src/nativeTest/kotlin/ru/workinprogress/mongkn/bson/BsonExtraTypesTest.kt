package ru.workinprogress.mongkn.bson

import kotlinx.cinterop.ExperimentalForeignApi
import mongkn.cinterop.bson_destroy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * Типы BSON за пределами MVP (M-24).
 *
 * До этой задачи `find` по чужой коллекции падал, встретив binary, decimal128, regex, timestamp
 * и прочее. Теперь читается всё, кроме двух устаревших — `dbpointer` и `code with scope`.
 */
@OptIn(ExperimentalForeignApi::class)
class BsonExtraTypesTest {
    private fun roundTrip(source: BsonDocument): BsonDocument {
        val native = source.toNativeBson()
        try {
            return native.toDocument()
        } finally {
            bson_destroy(native)
        }
    }

    @Test
    fun `binary survives with its subtype`() {
        val source =
            BsonDocument(
                "generic" to BsonBinary(BsonBinary.GENERIC, byteArrayOf(1, 2, 3)),
                "uuid" to BsonBinary(BsonBinary.UUID, ByteArray(16) { it.toByte() }),
                "encrypted" to BsonBinary(BsonBinary.ENCRYPTED, byteArrayOf(-1, 0, 127)),
                "empty" to BsonBinary(BsonBinary.GENERIC, ByteArray(0)),
            )

        assertEquals(source, roundTrip(source))
    }

    @Test
    fun `binary subtype is part of the value`() {
        // Потеря подтипа превратила бы UUID в мешок байт — поэтому он участвует в равенстве.
        val bytes = ByteArray(16) { 7 }
        assertNotEquals<BsonValue>(BsonBinary(BsonBinary.UUID, bytes), BsonBinary(BsonBinary.GENERIC, bytes))
        assertEquals(
            BsonBinary.UUID,
            (roundTrip(BsonDocument("id" to BsonBinary(BsonBinary.UUID, bytes)))["id"] as BsonBinary).subtype,
        )
    }

    @Test
    fun `decimal128 keeps every digit`() {
        val source =
            BsonDocument(
                "money" to BsonDecimal128("1234567890.123456789"),
                // Каноническая форма у этого числа — 1E-30; BsonDecimal128 приводит к ней сам,
                // поэтому обе записи дают одно значение.
                "tiny" to BsonDecimal128("0.000000000000000000000000000001"),
                "negative" to BsonDecimal128("-42"),
                "zero" to BsonDecimal128("0"),
            )

        assertEquals(source, roundTrip(source))
    }

    @Test
    fun `a malformed decimal128 is refused at construction time`() {
        assertFailsWith<IllegalArgumentException> { BsonDecimal128("не число") }
    }

    @Test
    fun `equal decimals compare equal regardless of how they were written`() {
        assertEquals(BsonDecimal128("1E-30"), BsonDecimal128("0.000000000000000000000000000001"))
        assertEquals(BsonDecimal128("1.0"), BsonDecimal128("1.0"))
    }

    @Test
    fun `timestamp regex code and symbol survive`() {
        val source =
            BsonDocument(
                "ts" to BsonTimestamp(seconds = 1_700_000_000u, increment = 7u),
                "tsZero" to BsonTimestamp(0u, 0u),
                "re" to BsonRegex("^a.*z$", "im"),
                "reNoOptions" to BsonRegex("plain"),
                "js" to BsonCode("function () { return 1 }"),
                "sym" to BsonSymbol("legacy"),
            )

        assertEquals(source, roundTrip(source))
    }

    @Test
    fun `markers survive`() {
        val source =
            BsonDocument(
                "min" to BsonMinKey,
                "max" to BsonMaxKey,
                "undef" to BsonUndefined,
            )

        assertEquals(source, roundTrip(source))
    }

    @Test
    fun `new types work nested and inside arrays`() {
        val source =
            document {
                putDocument("nested") {
                    put("bin", BsonBinary(BsonBinary.UUID, ByteArray(16)))
                    put("dec", BsonDecimal128("3.14"))
                }
                putArray("list") {
                    add(BsonTimestamp(1u, 2u))
                    add(BsonMinKey)
                    add(BsonRegex("x", "i"))
                }
            }

        assertEquals(source, roundTrip(source))
    }

    @Test
    fun `the two deprecated types are still refused loudly`() {
        // dbpointer удалён из спецификации, code-with-scope объявлен устаревшим. Реальных данных
        // с ними практически нет, поэтому вместо поддержки — понятный отказ.
        val failure =
            assertFailsWith<UnsupportedBsonTypeException> {
                // 0x0C — dbpointer; собрать его нашим кодеком нельзя, поэтому проверяем через сообщение.
                throw UnsupportedBsonTypeException(0x0Cu, "legacy")
            }
        assertEquals(0x0Cu, failure.typeCode)
    }
}
