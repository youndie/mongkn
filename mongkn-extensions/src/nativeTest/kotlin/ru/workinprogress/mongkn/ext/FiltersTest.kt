package ru.workinprogress.mongkn.ext

import ru.workinprogress.mongkn.bson.BsonArray
import ru.workinprogress.mongkn.bson.BsonBoolean
import ru.workinprogress.mongkn.bson.BsonDocument
import ru.workinprogress.mongkn.bson.BsonInt32
import ru.workinprogress.mongkn.bson.BsonNull
import ru.workinprogress.mongkn.bson.BsonString
import ru.workinprogress.mongkn.bson.document
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * DSL фильтров и обновлений (M-22, M-36).
 *
 * Проверяется **форма документа**, а не только «не упало»: DSL обязан давать ровно тот запрос,
 * который написали бы руками, иначе он превращается в способ незаметно спросить у базы не то.
 */
class FiltersTest {

    @Serializable
    private data class Person(val name: String, val born: Int, val tags: List<String>)

    @Serializable
    private data class Renamed(val name: String, @SerialName("born_year") val born: Int)

    @Test
    fun `equality is written without an operator`() {
        // MongoDB понимает {name: "Ada"} как равенство; $eq лишний, и официальный драйвер
        // его тоже не пишет.
        assertEquals(document { put("name", "Ada") }, filter<Person> { Person::name eq "Ada" })
        assertEquals(document { put("name", "Ada") }, "name" eq "Ada")
    }

    @Test
    fun `comparisons wrap the value into an operator document`() {
        assertEquals(
            BsonDocument("born" to BsonDocument("\$gt" to BsonInt32(1900))),
            filter<Person> { Person::born gt 1900 },
        )
        assertEquals(
            BsonDocument("born" to BsonDocument("\$lte" to BsonInt32(1815))),
            filter<Person> { Person::born lte 1815 },
        )
    }

    @Test
    fun `property reference and string spell the same filter`() {
        assertEquals("born" gt 1900, filter<Person> { Person::born gt 1900 })
    }

    @Test
    fun `membership and existence`() {
        assertEquals(
            BsonDocument("name" to BsonDocument("\$in" to BsonArray(listOf(BsonString("Ada"), BsonString("Grace"))))),
            filter<Person> { Person::name within listOf("Ada", "Grace") },
        )
        assertEquals(
            BsonDocument("tags" to BsonDocument("\$exists" to BsonBoolean(true))),
            filter<Person> { Person::tags exists true },
        )
    }

    @Test
    fun `and does not merge conditions into one document`() {
        // Два условия на одно поле при слиянии затёрли бы друг друга — ключи совпадают.
        val result = filter<Person> { and(Person::born gt 1900, Person::born lt 2000) }

        assertEquals(
            BsonDocument(
                "\$and" to BsonArray(
                    listOf(
                        BsonDocument("born" to BsonDocument("\$gt" to BsonInt32(1900))),
                        BsonDocument("born" to BsonDocument("\$lt" to BsonInt32(2000))),
                    )
                )
            ),
            result,
        )
    }

    @Test
    fun `null becomes BSON null rather than a missing field`() {
        assertEquals(BsonDocument("name" to BsonNull), "name" eq null)
    }

    @Test
    fun `an untranslatable value fails loudly`() {
        class Custom

        val failure = assertFailsWith<IllegalArgumentException> { "field" eq Custom() }
        assertTrue(failure.message!!.contains("Custom"), "message=${failure.message}")
    }

    @Test
    fun `a renamed field is refused instead of quietly matching nothing`() {
        // Ради этого и заведён scope (M-36). До него `Renamed::born gt 1900` строил фильтр
        // по полю "born", которого в документе нет, — и запрос молча возвращал пусто.
        val failure = assertFailsWith<IllegalArgumentException> {
            filter<Renamed> { Renamed::born gt 1900 }
        }

        assertTrue(failure.message!!.contains("born"), "message=${failure.message}")
        // Сообщение подсказывает, что искать: перечисляет реальные имена полей.
        assertTrue(failure.message!!.contains("born_year"), "message=${failure.message}")
    }

    @Test
    fun `the renamed field is reachable by its real name`() {
        assertEquals(
            BsonDocument("born_year" to BsonDocument("\$gt" to BsonInt32(1900))),
            "born_year" gt 1900,
        )
    }

    @Test
    fun `updates carry their operator`() {
        assertEquals(
            BsonDocument("\$set" to BsonDocument("born" to BsonInt32(1816))),
            update<Person> { Person::born setTo 1816 },
        )
        assertEquals(
            BsonDocument("\$inc" to BsonDocument("born" to BsonInt32(1))),
            update<Person> { Person::born incBy 1 },
        )
        assertEquals(
            BsonDocument("\$unset" to BsonDocument("tags" to BsonString(""))),
            update<Person> { unset(Person::tags) },
        )
    }

    @Test
    fun `updates check field names too`() {
        assertFailsWith<IllegalArgumentException> { update<Renamed> { Renamed::born setTo 1900 } }
    }

    @Test
    fun `combine merges same-named operators instead of dropping one`() {
        // Документ с двумя ключами "$set" MongoDB не примет, а наш BsonDocument их допускает —
        // без слияния ошибка вылезла бы только на сервере.
        val result = combine(
            update<Person> { Person::name setTo "Ada" },
            update<Person> { Person::born setTo 1815 },
            update<Person> { Person::born incBy 1 },
        )

        assertEquals(
            BsonDocument(
                "\$set" to BsonDocument("name" to BsonString("Ada"), "born" to BsonInt32(1815)),
                "\$inc" to BsonDocument("born" to BsonInt32(1)),
            ),
            result,
        )
    }
}
