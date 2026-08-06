package ru.workinprogress.mongkn.bson

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Публичные [BsonEncoder] и [BsonDecoder] — точка расширения для чужих типов.
 *
 * Проверяется не «интерфейс существует», а то, ради чего он заведён: пользовательский тип
 * доезжает до документа **родным типом BSON**, а не пересказом через строку или пару чисел.
 * Поэтому в тестах смотрим на конкретный `BsonValue` в документе, а не только на round-trip:
 * round-trip прошёл бы и на строке, и вся затея была бы напрасной.
 */
class BsonSerializationTest {
    /**
     * Денежная сумма — тот самый случай, ради которого всё и делается.
     *
     * Хранится как `decimal128`: в BSON для этого есть точный тип, и только он даёт и точность,
     * и арифметику на стороне сервера, и читаемость документа сторонними инструментами.
     */
    private data class Money(
        val amount: String,
    )

    private object MoneySerializer : KSerializer<Money> {
        // Формат древесный, поэтому дескриптор нужен фреймворку для обхода и на представление
        // в BSON не влияет — сюда приходит уже готовое значение.
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Money", PrimitiveKind.STRING)

        override fun serialize(
            encoder: Encoder,
            value: Money,
        ) {
            val bson =
                encoder as? BsonEncoder
                    ?: throw SerializationException("Money сериализуется только в BSON")
            bson.encodeBsonValue(BsonDecimal128(value.amount))
        }

        override fun deserialize(decoder: Decoder): Money {
            val bson =
                decoder as? BsonDecoder
                    ?: throw SerializationException("Money читается только из BSON")
            val value = bson.decodeBsonValue()
            return Money((value as? BsonDecimal128)?.value ?: throw SerializationException("ждали decimal128: $value"))
        }
    }

    @Serializable
    private data class Order(
        val id: String,
        @Serializable(with = MoneySerializer::class)
        val total: Money,
    )

    @Serializable
    private data class Refund(
        val id: String,
        @Serializable(with = MoneySerializer::class)
        val compensation: Money?,
    )

    @Serializable
    private data class Basket(
        @Serializable(with = MoneySerializer::class)
        val cheapest: Money,
        val prices: List<
            @Serializable(with = MoneySerializer::class)
            Money,
        >,
    )

    @Test
    fun `a custom serializer puts a native BSON type into the document`() {
        val document = encodeToDocument(Order.serializer(), Order("A-1", Money("10.25")))

        // Главная проверка: в документе именно decimal128, а не строка. Round-trip прошёл бы
        // и на строке — и тогда точка расширения была бы бесполезной.
        val total = document["total"]
        assertIs<BsonDecimal128>(total, "ждали decimal128, получили ${total?.let { it::class.simpleName }}")
        assertEquals("10.25", total.value)
    }

    @Test
    fun `the value comes back through the same serializer`() {
        val order = Order("A-2", Money("0.10"))

        val restored = decodeFromDocument(Order.serializer(), encodeToDocument(Order.serializer(), order))

        assertEquals(order, restored)
    }

    @Test
    fun `it works inside a list as well as in a field`() {
        val basket = Basket(Money("1.00"), listOf(Money("1.00"), Money("2.50")))

        val document = encodeToDocument(Basket.serializer(), basket)

        // Внутри массива путь другой (ArrayEncoder вместо DocumentEncoder), и он тоже обязан
        // доводить значение до пользовательского сериализатора.
        val prices = document["prices"]
        assertIs<BsonArray>(prices)
        assertTrue(prices.values.all { it is BsonDecimal128 }, "в массиве не decimal128: ${prices.values}")
        assertEquals(basket, decodeFromDocument(Basket.serializer(), document))
    }

    @Test
    fun `a nullable field reaches the custom serializer too`() {
        // Путь у nullable-поля другой: фреймворк зовёт encodeNullableSerializableElement,
        // а не encodeSerializableElement. Если его не перехватить, сериализатор получит
        // составной кодировщик вместо корневого — и точка расширения просто не сработает.
        val document = encodeToDocument(Refund.serializer(), Refund("R-1", Money("3.50")))

        val compensation = document["compensation"]
        assertIs<BsonDecimal128>(
            compensation,
            "ждали decimal128, получили ${compensation?.let { it::class.simpleName }}",
        )
        assertEquals("3.50", compensation.value)
    }

    @Test
    fun `a nullable field survives the round trip in both states`() {
        val filled = Refund("R-2", Money("1.00"))
        val empty = Refund("R-3", null)

        assertEquals(filled, decodeFromDocument(Refund.serializer(), encodeToDocument(Refund.serializer(), filled)))
        assertEquals(empty, decodeFromDocument(Refund.serializer(), encodeToDocument(Refund.serializer(), empty)))
    }

    @Test
    fun `a null in a nullable field stays null in the document`() {
        val document = encodeToDocument(Refund.serializer(), Refund("R-4", null))

        assertEquals(BsonNull, document["compensation"])
    }

    @Test
    fun `a serializer that demands BSON refuses another format`() {
        // Договор точки расширения: проверять тип кодировщика и падать внятно. Без этого
        // тот же сериализатор в JSON молча записал бы данные, которые не читаются обратно.
        val failure =
            assertFailsWith<SerializationException> {
                MoneySerializer.serialize(RefusingEncoder, Money("1.00"))
            }
        assertTrue("BSON" in failure.message.orEmpty(), "ждали внятную причину, получили: ${failure.message}")
    }

    @Test
    fun `values without a native BSON type still go through as before`() {
        // Точка расширения не должна менять поведение обычных полей.
        val document = encodeToDocument(Order.serializer(), Order("A-3", Money("7.00")))

        assertEquals(BsonString("A-3"), document["id"])
    }

    /** Кодировщик другого формата — нужен, чтобы проверить отказ, а не воображать его. */
    private object RefusingEncoder : Encoder {
        override val serializersModule = kotlinx.serialization.modules.EmptySerializersModule()

        override fun beginStructure(descriptor: SerialDescriptor) = error("не нужно для теста")

        override fun encodeBoolean(value: Boolean) = Unit

        override fun encodeByte(value: Byte) = Unit

        override fun encodeChar(value: Char) = Unit

        override fun encodeDouble(value: Double) = Unit

        override fun encodeEnum(
            enumDescriptor: SerialDescriptor,
            index: Int,
        ) = Unit

        override fun encodeFloat(value: Float) = Unit

        override fun encodeInline(descriptor: SerialDescriptor): Encoder = this

        override fun encodeInt(value: Int) = Unit

        override fun encodeLong(value: Long) = Unit

        @kotlinx.serialization.ExperimentalSerializationApi
        override fun encodeNull() = Unit

        override fun encodeShort(value: Short) = Unit

        override fun encodeString(value: String) = Unit
    }
}
