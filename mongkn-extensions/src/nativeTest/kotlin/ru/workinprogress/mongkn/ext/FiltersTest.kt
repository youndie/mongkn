package ru.workinprogress.mongkn.ext

import ru.workinprogress.mongkn.bson.BsonArray
import ru.workinprogress.mongkn.bson.BsonBoolean
import ru.workinprogress.mongkn.bson.BsonDocument
import ru.workinprogress.mongkn.bson.BsonInt32
import ru.workinprogress.mongkn.bson.BsonNull
import ru.workinprogress.mongkn.bson.BsonString
import ru.workinprogress.mongkn.bson.document
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * DSL фильтров и обновлений (M-22).
 *
 * Проверяется **форма документа**, а не только «не упало»: DSL обязан давать ровно тот запрос,
 * который написали бы руками, иначе он превращается в способ незаметно спросить у базы не то.
 */
class FiltersTest {

    private data class Person(val name: String, val born: Int, val tags: List<String>)

    @Test
    fun `equality is written without an operator`() {
        // MongoDB понимает {name: "Ada"} как равенство; $eq лишний, и официальный драйвер
        // его тоже не пишет.
        assertEquals(document { put("name", "Ada") }, Person::name eq "Ada")
        assertEquals(document { put("name", "Ada") }, "name" eq "Ada")
    }

    @Test
    fun `comparisons wrap the value into an operator document`() {
        assertEquals(
            BsonDocument("born" to BsonDocument("\$gt" to BsonInt32(1900))),
            Person::born gt 1900,
        )
        assertEquals(BsonDocument("born" to BsonDocument("\$lte" to BsonInt32(1815))), Person::born lte 1815)
        assertEquals(BsonDocument("born" to BsonDocument("\$ne" to BsonInt32(0))), Person::born ne 0)
    }

    @Test
    fun `property reference and string spell the same filter`() {
        assertEquals("born" gt 1900, Person::born gt 1900)
    }

    @Test
    fun `membership and existence`() {
        assertEquals(
            BsonDocument("name" to BsonDocument("\$in" to BsonArray(listOf(BsonString("Ada"), BsonString("Grace"))))),
            Person::name within listOf("Ada", "Grace"),
        )
        assertEquals(
            BsonDocument("tags" to BsonDocument("\$exists" to BsonBoolean(true))),
            Person::tags exists true,
        )
    }

    @Test
    fun `and does not merge conditions into one document`() {
        // Два условия на одно поле при слиянии затёрли бы друг друга — ключи совпадают.
        // Поэтому $and явный.
        val filter = and(Person::born gt 1900, Person::born lt 2000)

        assertEquals(
            BsonDocument(
                "\$and" to BsonArray(
                    listOf(
                        BsonDocument("born" to BsonDocument("\$gt" to BsonInt32(1900))),
                        BsonDocument("born" to BsonDocument("\$lt" to BsonInt32(2000))),
                    )
                )
            ),
            filter,
        )
    }

    @Test
    fun `null becomes BSON null rather than a missing field`() {
        assertEquals(BsonDocument("name" to BsonNull), Person::name.let { "name" eq null })
    }

    @Test
    fun `an untranslatable value fails loudly`() {
        class Custom

        val failure = assertFailsWith<IllegalArgumentException> { "field" eq Custom() }
        assertEquals(true, failure.message!!.contains("Custom"), "message=${failure.message}")
    }

    @Test
    fun `updates carry their operator`() {
        assertEquals(
            BsonDocument("\$set" to BsonDocument("born" to BsonInt32(1816))),
            Person::born setTo 1816,
        )
        assertEquals(BsonDocument("\$inc" to BsonDocument("born" to BsonInt32(1))), Person::born incBy 1)
        assertEquals(BsonDocument("\$unset" to BsonDocument("tags" to BsonString(""))), unset(Person::tags))
    }

    @Test
    fun `combine merges same-named operators instead of dropping one`() {
        // Документ с двумя ключами "$set" MongoDB не примет, а наш BsonDocument их допускает —
        // без слияния ошибка вылезла бы только на сервере.
        val update = combine(Person::name setTo "Ada", Person::born setTo 1815, Person::born incBy 1)

        assertEquals(
            BsonDocument(
                "\$set" to BsonDocument("name" to BsonString("Ada"), "born" to BsonInt32(1815)),
                "\$inc" to BsonDocument("born" to BsonInt32(1)),
            ),
            update,
        )
    }
}
