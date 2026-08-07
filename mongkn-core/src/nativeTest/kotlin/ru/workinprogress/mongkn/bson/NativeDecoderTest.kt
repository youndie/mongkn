package ru.workinprogress.mongkn.bson

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import mongkn.cinterop.bson_destroy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Декодировщик прямо из курсора (M-83) читает **то же самое**, что декодировщик по дереву.
 *
 * Это дифференциальный тест, а не проверка отдельных значений, и так он устроен намеренно:
 * у формата теперь две реализации чтения, и опасность здесь не «одна из них неверна», а
 * «они разошлись в частном случае». Такое расхождение не поймать примерами — его ловит только
 * сверка двух путей на одном и том же документе.
 *
 * Поэтому каждая проверка идёт по одной схеме: собрать документ, прочитать обоими способами,
 * сравнить между собой **и** с исходным значением. Сравнения только с исходным было бы мало:
 * оба пути могли бы ошибиться одинаково, и тест бы этого не заметил.
 */
@OptIn(ExperimentalForeignApi::class)
class NativeDecoderTest {
    @Serializable
    private data class Address(
        val city: String,
        val zip: String,
    )

    @Serializable
    private enum class Status { NEW, PAID }

    @Serializable
    private data class Order(
        @SerialName("_id")
        val id: String,
        val total: Int,
        val weight: Double,
        val paid: Boolean,
        val big: Long,
        val status: Status,
        val shipTo: Address,
        val stops: List<Address>,
        val tags: List<String>,
        val comment: String?,
        val discount: Address?,
    )

    private val order =
        Order(
            id = "A-1",
            total = 42,
            weight = 1.5,
            paid = true,
            big = 9_000_000_000L,
            status = Status.PAID,
            shipTo = Address("Тбилиси", "0101"),
            stops = listOf(Address("Батуми", "6000"), Address("Гори", "1400")),
            tags = listOf("срочно", "хрупкое"),
            comment = null,
            discount = Address("Кутаиси", "4600"),
        )

    /** Разбирает документ обоими путями и требует совпадения. */
    private fun <T> bothWays(
        serializer: KSerializer<T>,
        document: Document,
    ): T {
        val viaTree = decodeFromDocument(serializer, document)
        val native = document.toNativeBson()
        val viaCursor =
            try {
                decodeFromNative(serializer, native)
            } finally {
                bson_destroy(native)
            }

        assertEquals(viaTree, viaCursor, "пути чтения разошлись на документе $document")
        return viaCursor
    }

    @Test
    fun `a whole model reads the same both ways`() {
        assertEquals(order, bothWays(Order.serializer(), encodeToDocument(Order.serializer(), order)))
    }

    @Test
    fun `a field the model does not know is skipped instead of failing`() {
        // Документ в базе шире модели сплошь и рядом: старые поля, чужие версии.
        val document = encodeToDocument(Order.serializer(), order)
        val wider = BsonDocument(document.entries + ("legacyFlag" to BsonBoolean(true)))

        assertEquals(order, bothWays(Order.serializer(), wider))
    }

    @Test
    fun `field order in the document does not matter`() {
        // Порядок полей в базе не обязан совпадать с порядком в классе — индекс ищется по имени.
        val document = encodeToDocument(Order.serializer(), order)

        assertEquals(order, bothWays(Order.serializer(), BsonDocument(document.entries.reversed())))
    }

    @Serializable
    private data class WithDefaults(
        val name: String,
        val note: String = "нет",
    )

    @Test
    fun `a missing field falls back to its default`() {
        assertEquals(
            WithDefaults("Ada"),
            bothWays(WithDefaults.serializer(), BsonDocument("name" to BsonString("Ada"))),
        )
    }

    @Serializable
    private data class Money(
        val amount: String,
    )

    private object MoneySerializer : KSerializer<Money> {
        override val descriptor = PrimitiveSerialDescriptor("Money", PrimitiveKind.STRING)

        override fun serialize(
            encoder: Encoder,
            value: Money,
        ) {
            (encoder as BsonEncoder).encodeBsonValue(BsonDecimal128(value.amount))
        }

        override fun deserialize(decoder: Decoder): Money =
            Money((decoder as BsonDecoder).decodeBsonValue().let { (it as BsonDecimal128).value })
    }

    @Serializable
    private data class Invoice(
        @Serializable(with = MoneySerializer::class)
        val total: Money,
        @Serializable(with = MoneySerializer::class)
        val refund: Money?,
    )

    @Test
    fun `a custom serializer gets its native BSON value from the cursor too`() {
        // Точка расширения обязана работать на обоих путях: сериализатор просит BsonValue,
        // и новый декодировщик собирает поддерево ровно для него.
        val invoice = Invoice(Money("10.25"), Money("0.50"))

        assertEquals(invoice, bothWays(Invoice.serializer(), encodeToDocument(Invoice.serializer(), invoice)))
    }

    @Serializable
    private data class WithMap(
        val name: String,
        val labels: Map<String, String>,
    )

    @Test
    fun `a map field falls back to the tree decoder and still reads`() {
        val value = WithMap("Ada", mapOf("a" to "1", "b" to "2"))

        assertEquals(value, bothWays(WithMap.serializer(), encodeToDocument(WithMap.serializer(), value)))
    }

    @Serializable
    private data class WithObjectId(
        @SerialName("_id")
        val id: BsonObjectId,
        val name: String,
    )

    @Test
    fun `an ObjectId field survives both paths`() {
        val value = WithObjectId(BsonObjectId.parse("64b7f1c2a4e8d9b0c1a2e3f4"), "Ada")

        assertEquals(value, bothWays(WithObjectId.serializer(), encodeToDocument(WithObjectId.serializer(), value)))
    }

    @Serializable
    private data class Narrow(
        val small: Int,
    )

    @Test
    fun `narrowing a too large int64 fails on both paths`() {
        // Молчаливая потеря данных — худший исход, и оба пути обязаны отвергать её одинаково.
        val document = BsonDocument("small" to BsonInt64(Long.MAX_VALUE))

        assertFailsWith<SerializationException> { decodeFromDocument(Narrow.serializer(), document) }

        val native = document.toNativeBson()
        try {
            assertFailsWith<SerializationException> { decodeFromNative(Narrow.serializer(), native) }
        } finally {
            bson_destroy(native)
        }
    }

    @Test
    fun `a wrong type is rejected on both paths`() {
        val document = BsonDocument("small" to BsonString("не число"))

        assertFailsWith<SerializationException> { decodeFromDocument(Narrow.serializer(), document) }

        val native = document.toNativeBson()
        try {
            assertFailsWith<SerializationException> { decodeFromNative(Narrow.serializer(), native) }
        } finally {
            bson_destroy(native)
        }
    }
}
