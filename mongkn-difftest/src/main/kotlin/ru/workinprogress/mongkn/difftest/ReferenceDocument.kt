package ru.workinprogress.mongkn.difftest

import org.bson.BsonArray
import org.bson.BsonBinary
import org.bson.BsonBoolean
import org.bson.BsonDateTime
import org.bson.BsonDecimal128
import org.bson.BsonDocument
import org.bson.BsonDouble
import org.bson.BsonInt32
import org.bson.BsonInt64
import org.bson.BsonMaxKey
import org.bson.BsonMinKey
import org.bson.BsonNull
import org.bson.BsonObjectId
import org.bson.BsonRegularExpression
import org.bson.BsonString
import org.bson.BsonTimestamp
import org.bson.types.Decimal128
import org.bson.types.ObjectId

/**
 * Эталонный документ: по одному полю на каждый тип BSON, который поддерживает mongkn.
 *
 * Собран средствами **официального** драйвера — в этом весь смысл. Если наш кодек разойдётся
 * с эталоном хоть в одном типе (например, положит int64 там, где должен быть int32),
 * дифференциальный тест это увидит.
 *
 * `_id` фиксированный, а не сгенерированный: документ пишут и читают два разных процесса,
 * и им нужно договориться, что сравнивать.
 */
public object ReferenceDocument {
    public const val DATABASE: String = "mongkn_diff"

    /** Куда пишет эталон, а mongkn читает. */
    public const val REFERENCE: String = "reference"

    /** Куда пишет mongkn, а эталон читает. */
    public const val WRITTEN: String = "written"

    public val ID: ObjectId = ObjectId("6a71efcbb173221a58058212")

    public fun build(): BsonDocument =
        BsonDocument()
            .append("_id", BsonObjectId(ID))
            .append("string", BsonString("kotlin-native"))
            .append("emptyString", BsonString(""))
            .append("unicode", BsonString("документ ✓"))
            // Строка с NUL внутри: BSON её допускает, а наивный кодек обрезает по strlen.
            // Эталон подтверждает, что именно так это и должно храниться.
            .append("embeddedNul", BsonString("a\u0000b"))
            .append("int32", BsonInt32(42))
            .append("int32Negative", BsonInt32(-1))
            .append("int64", BsonInt64(9_000_000_000L))
            .append("double", BsonDouble(3.5))
            .append("boolTrue", BsonBoolean(true))
            .append("boolFalse", BsonBoolean(false))
            .append("nothing", BsonNull.VALUE)
            .append("when", BsonDateTime(1_700_000_000_000L))
            .append("oid", BsonObjectId(ObjectId("000000000000000000000001")))
            .append(
                "nested",
                BsonDocument()
                    .append("a", BsonInt32(1))
                    .append("deeper", BsonDocument().append("b", BsonString("two"))),
            ).append(
                "array",
                BsonArray(
                    listOf(
                        BsonInt32(1),
                        BsonString("two"),
                        BsonDouble(3.0),
                        BsonDocument().append("four", BsonBoolean(true)),
                        BsonArray(listOf(BsonInt64(5L))),
                    ),
                ),
            )
            // Типы за пределами MVP (M-24). Именно здесь реализации обычно и расходятся:
            // подтип binary легко потерять, а decimal128 — записать не в каноническом виде.
            .append("binaryGeneric", BsonBinary(byteArrayOf(1, 2, 3)))
            .append("binaryUuid", BsonBinary(0x04, ByteArray(16) { it.toByte() }))
            .append("decimal", BsonDecimal128(Decimal128.parse("1234567890.123456789")))
            .append("timestamp", BsonTimestamp(1_700_000_000, 7))
            .append("regex", BsonRegularExpression("^a.*z$", "im"))
            .append("minKey", BsonMinKey())
            .append("maxKey", BsonMaxKey())
            .append("emptyArray", BsonArray(emptyList()))
            .append("emptyDocument", BsonDocument())
}
