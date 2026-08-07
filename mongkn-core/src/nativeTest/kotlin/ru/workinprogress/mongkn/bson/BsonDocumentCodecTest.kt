package ru.workinprogress.mongkn.bson

import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Форма документа для `@Serializable`-классов: вложенность, пустые значения, коллекции.
 *
 * Заведён после отчёта первого потребителя. До него `encodeToDocument`
 * проверялся только косвенно — через настоящие коллекции в интеграционных тестах, где
 * модели оказались без единого nullable-поля со структурой внутри. Поэтому целый путь
 * кодировщика — `encodeNullableSerializableElement` — не исполнялся ни разу и падал
 * у потребителя на первом же `ShippingOptions?`.
 *
 * Здесь проверяются именно **формы**, а не типы значений: у каждой формы в кодировщике
 * свой метод, и «работает соседняя» ничего не говорит о разбираемой.
 */
class BsonDocumentCodecTest {
    @Serializable
    private data class Address(
        val city: String,
        val zip: String,
    )

    @Serializable
    private data class Order(
        val id: String,
        val shipTo: Address,
    )

    @Serializable
    private data class OptionalOrder(
        val id: String,
        val shipTo: Address?,
    )

    @Serializable
    private data class Basket(
        val items: List<Address>,
        val fallback: List<Address>?,
    )

    @Serializable
    private data class Stops(
        val stops: List<Address?>,
    )

    @Test
    fun `a nested structure becomes a nested document`() {
        val document = encodeToDocument(Order.serializer(), Order("A-1", Address("Тбилиси", "0101")))

        val shipTo = document["shipTo"]
        assertIs<BsonDocument>(shipTo)
        assertEquals(BsonString("Тбилиси"), shipTo["city"])
    }

    @Test
    fun `a nullable structure with a value becomes a nested document too`() {
        // Путь у nullable-поля другой: фреймворк зовёт encodeNullableSerializableElement,
        // и его стандартная реализация передаёт сериализатору **этот** составной кодировщик.
        // Вложенный класс на нём зовёт beginStructure — а тот у нас намеренно падает.
        // То есть без перехвата ломается не край, а обычное поле вида `Address?`.
        val document = encodeToDocument(OptionalOrder.serializer(), OptionalOrder("A-2", Address("Батуми", "6000")))

        val shipTo = document["shipTo"]
        assertIs<BsonDocument>(shipTo)
        assertEquals(BsonString("Батуми"), shipTo["city"])
    }

    @Test
    fun `a nullable structure survives the round trip in both states`() {
        val filled = OptionalOrder("A-3", Address("Кутаиси", "4600"))
        val empty = OptionalOrder("A-4", null)

        val serializer = OptionalOrder.serializer()
        assertEquals(filled, decodeFromDocument(serializer, encodeToDocument(serializer, filled)))
        assertEquals(empty, decodeFromDocument(serializer, encodeToDocument(serializer, empty)))
    }

    @Test
    fun `an empty nullable structure stays null in the document`() {
        val document = encodeToDocument(OptionalOrder.serializer(), OptionalOrder("A-5", null))

        assertEquals(BsonNull, document["shipTo"])
    }

    @Test
    fun `a list of structures and a nullable list both survive the round trip`() {
        val basket =
            Basket(
                items = listOf(Address("Тбилиси", "0101"), Address("Батуми", "6000")),
                fallback = null,
            )

        val document = encodeToDocument(Basket.serializer(), basket)

        val items = document["items"]
        assertIs<BsonArray>(items)
        assertIs<BsonDocument>(items.values.first())
        assertEquals(BsonNull, document["fallback"])
        assertEquals(basket, decodeFromDocument(Basket.serializer(), document))
    }

    @Test
    fun `a null inside a list of structures survives the round trip`() {
        // Тот же метод, но у массива: элемент-null идёт через encodeNullableSerializableElement
        // на ArrayEncoder, а не на DocumentEncoder. Метод один, кодировщика два.
        val stops = Stops(listOf(Address("Тбилиси", "0101"), null))

        val document = encodeToDocument(Stops.serializer(), stops)

        val list = document["stops"]
        assertIs<BsonArray>(list)
        assertIs<BsonDocument>(list.values[0])
        assertEquals(BsonNull, list.values[1])
        assertEquals(stops, decodeFromDocument(Stops.serializer(), document))
    }
}
