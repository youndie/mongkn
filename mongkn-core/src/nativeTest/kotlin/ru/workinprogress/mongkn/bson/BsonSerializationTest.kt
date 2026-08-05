package ru.workinprogress.mongkn.bson

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Отображение `@Serializable`-классов в BSON и обратно (M-21).
 *
 * Проверяется не только round-trip, но и **форма документа**: класс должен ложиться в те же
 * поля, что положил бы человек руками. Иначе типизированная коллекция и нетипизированная
 * читали бы разные документы из одной коллекции.
 */
class BsonSerializationTest {
    @Serializable
    data class Person(
        val name: String,
        val born: Int,
        val alias: String? = null,
    )

    @Serializable
    enum class Kind { PRIMARY, SECONDARY }

    @Serializable
    data class Nested(
        val id: Long,
        val person: Person,
        val tags: List<String>,
        val kind: Kind,
    )

    @Serializable
    data class Numbers(
        val i: Int,
        val l: Long,
        val d: Double,
        val f: Float,
        val b: Byte,
        val s: Short,
    )

    @Serializable
    data class WithMap(
        val labels: Map<String, String>,
        val counts: Map<String, Int>,
    )

    @Serializable
    data class WithRaw(
        val name: String,
        val id: BsonObjectId,
    )

    private inline fun <reified T> roundTrip(value: T): T =
        decodeFromDocument(serializer<T>(), encodeToDocument(serializer<T>(), value))

    @Test
    fun `flat class maps onto the fields a human would write`() {
        val document = encodeToDocument(serializer<Person>(), Person("Ada", 1815))

        assertEquals(
            BsonDocument("name" to BsonString("Ada"), "born" to BsonInt32(1815), "alias" to BsonNull),
            document,
        )
    }

    @Test
    fun `flat class survives the round trip`() {
        val person = Person("Ada", 1815, alias = "графиня Лавлейс")
        assertEquals(person, roundTrip(person))
    }

    @Test
    fun `nulls survive`() {
        val person = Person("Grace", 1906, alias = null)
        val result = roundTrip(person)
        assertEquals(person, result)
        assertEquals(null, result.alias)
    }

    @Test
    fun `nested classes lists and enums survive`() {
        val value =
            Nested(
                id = 9_000_000_000L,
                person = Person("Ada", 1815),
                tags = listOf("math", "engine", ""),
                kind = Kind.SECONDARY,
            )

        assertEquals(value, roundTrip(value))
    }

    @Test
    fun `enum goes by name rather than ordinal`() {
        val document = encodeToDocument(serializer<Nested>(), Nested(1, Person("x", 1), emptyList(), Kind.SECONDARY))

        // Порядковый номер сломался бы при перестановке констант; имя — нет.
        assertEquals(BsonString("SECONDARY"), document["kind"])
    }

    @Test
    fun `unknown enum constant fails loudly`() {
        val document =
            BsonDocument(
                "id" to BsonInt64(1),
                "person" to BsonDocument("name" to BsonString("x"), "born" to BsonInt32(1)),
                "tags" to BsonArray(emptyList()),
                "kind" to BsonString("TERTIARY"),
            )

        val failure =
            assertFailsWith<SerializationException> {
                decodeFromDocument(serializer<Nested>(), document)
            }
        assertTrue(failure.message!!.contains("TERTIARY"), "message=${failure.message}")
    }

    @Test
    fun `numeric widths land on the right BSON types`() {
        val document = encodeToDocument(serializer<Numbers>(), Numbers(1, 2L, 3.5, 4.5f, 6, 7))

        assertEquals(BsonInt32(1), document["i"])
        assertEquals(BsonInt64(2L), document["l"])
        assertEquals(BsonDouble(3.5), document["d"])
        // Float в BSON нет — едет double, как и у официального драйвера.
        assertEquals(BsonDouble(4.5), document["f"])
        // Byte и Short тоже: BSON не различает мелкие целые.
        assertEquals(BsonInt32(6), document["b"])
        assertEquals(BsonInt32(7), document["s"])
        assertEquals(Numbers(1, 2L, 3.5, 4.5f, 6, 7), decodeFromDocument(serializer<Numbers>(), document))
    }

    @Test
    fun `int32 from the server fits a Long field`() {
        // Сервер и другие драйверы вольны положить int32 туда, где в классе объявлен Long.
        val document =
            BsonDocument(
                "i" to BsonInt32(1),
                "l" to BsonInt32(2),
                "d" to BsonInt32(3),
                "f" to BsonInt32(4),
                "b" to BsonInt32(6),
                "s" to BsonInt32(7),
            )

        assertEquals(Numbers(1, 2L, 3.0, 4.0f, 6, 7), decodeFromDocument(serializer<Numbers>(), document))
    }

    @Test
    fun `narrowing int64 into an Int field is refused rather than silently truncated`() {
        val document =
            BsonDocument(
                "i" to BsonInt64(Long.MAX_VALUE),
                "l" to BsonInt64(1),
                "d" to BsonDouble(1.0),
                "f" to BsonDouble(1.0),
                "b" to BsonInt32(1),
                "s" to BsonInt32(1),
            )

        assertFailsWith<SerializationException> { decodeFromDocument(serializer<Numbers>(), document) }
    }

    @Test
    fun `maps survive as documents`() {
        val value = WithMap(labels = mapOf("a" to "one", "b" to ""), counts = mapOf("x" to 1, "y" to 2))

        val document = encodeToDocument(serializer<WithMap>(), value)

        assertEquals(BsonDocument("a" to BsonString("one"), "b" to BsonString("")), document["labels"])
        assertEquals(value, roundTrip(value))
    }

    @Test
    fun `BsonValue fields pass through without going via serialization`() {
        // ObjectId нельзя выразить обычным data-классом, поэтому он проходит как есть —
        // иначе типизированная коллекция теряла бы _id.
        val value = WithRaw("Ada", BsonObjectId.parse("6a71efcbb173221a58058212"))

        val document = encodeToDocument(serializer<WithRaw>(), value)

        assertEquals(BsonObjectId.parse("6a71efcbb173221a58058212"), document["id"])
        assertEquals(value, roundTrip(value))
    }

    @Test
    fun `missing optional field falls back to the default`() {
        val document = BsonDocument("name" to BsonString("Ada"), "born" to BsonInt32(1815))

        assertEquals(Person("Ada", 1815, alias = null), decodeFromDocument(serializer<Person>(), document))
    }

    @Test
    fun `extra fields in the document are ignored`() {
        // Документ в базе живёт дольше класса: новое поле не должно ронять чтение старым кодом.
        val document =
            BsonDocument(
                "name" to BsonString("Ada"),
                "born" to BsonInt32(1815),
                "addedLater" to BsonString("что-то ещё"),
            )

        assertEquals(Person("Ada", 1815), decodeFromDocument(serializer<Person>(), document))
    }

    @Test
    fun `a non-document top level value is refused`() {
        // В коллекцию можно класть только документ — список или число туда не положить.
        assertFailsWith<SerializationException> {
            encodeToDocument(serializer<List<String>>(), listOf("a"))
        }
    }
}
