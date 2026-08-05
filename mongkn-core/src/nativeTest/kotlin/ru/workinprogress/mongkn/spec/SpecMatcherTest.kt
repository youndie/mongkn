package ru.workinprogress.mongkn.spec

import ru.workinprogress.mongkn.bson.BsonArray
import ru.workinprogress.mongkn.bson.BsonBoolean
import ru.workinprogress.mongkn.bson.BsonDocument
import ru.workinprogress.mongkn.bson.BsonInt32
import ru.workinprogress.mongkn.bson.BsonNull
import ru.workinprogress.mongkn.bson.BsonString
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Проверка самого сопоставления (M-35).
 *
 * Раннер spec-тестов зелёный ровно настолько, насколько строг его матчер. До этого теста
 * строгость держалась на слове: все сценарии проходили и до ужесточения, и после, потому что
 * ни один из них не содержит лишнего поля во вложенном документе.
 */
class SpecMatcherTest {
    private val doc = BsonDocument("a" to BsonInt32(1), "b" to BsonString("x"))

    @Test
    fun `identical documents match`() {
        assertTrue(SpecMatcher.matches(doc, doc))
    }

    @Test
    fun `extra field at the root is allowed only when told so`() {
        val actual = BsonDocument("a" to BsonInt32(1), "b" to BsonString("x"), "_id" to BsonInt32(9))

        // Результат операции: сервер вправе добавить своё.
        assertTrue(SpecMatcher.matches(doc, actual, root = true))
        // Содержимое коллекции: лишнее поле — расхождение.
        assertFalse(SpecMatcher.matches(doc, actual))
    }

    @Test
    fun `extra field inside a nested document is always a mismatch`() {
        val expected = BsonDocument("outer" to BsonDocument("a" to BsonInt32(1)))
        val actual = BsonDocument("outer" to BsonDocument("a" to BsonInt32(1), "sneaky" to BsonInt32(2)))

        // Именно это раньше проходило незамеченным: послабление действовало на любой глубине.
        assertFalse(SpecMatcher.matches(expected, actual))
        assertFalse(SpecMatcher.matches(expected, actual, root = true))
    }

    @Test
    fun `missing and differing fields never match`() {
        assertFalse(SpecMatcher.matches(doc, BsonDocument("a" to BsonInt32(1))))
        assertFalse(SpecMatcher.matches(doc, BsonDocument("a" to BsonInt32(2), "b" to BsonString("x"))))
        assertFalse(SpecMatcher.matches(doc, null))
    }

    @Test
    fun `arrays compare by length and elementwise`() {
        val expected = BsonArray(listOf(BsonInt32(1), BsonInt32(2)))
        assertTrue(SpecMatcher.matches(expected, BsonArray(listOf(BsonInt32(1), BsonInt32(2)))))
        assertFalse(SpecMatcher.matches(expected, BsonArray(listOf(BsonInt32(1)))))
        assertFalse(SpecMatcher.matches(expected, BsonArray(listOf(BsonInt32(2), BsonInt32(1)))))
    }

    @Test
    fun `unsetOrMatches accepts both absence and a match`() {
        val expected = BsonDocument("\$\$unsetOrMatches" to BsonInt32(1))
        assertTrue(SpecMatcher.matches(expected, null))
        assertTrue(SpecMatcher.matches(expected, BsonInt32(1)))
        assertFalse(SpecMatcher.matches(expected, BsonInt32(2)))
    }

    @Test
    fun `type operator checks the BSON type rather than the value`() {
        val expectedInt = BsonDocument("\$\$type" to BsonString("int"))
        assertTrue(SpecMatcher.matches(expectedInt, BsonInt32(42)))
        assertFalse(SpecMatcher.matches(expectedInt, BsonString("42")))

        val either = BsonDocument("\$\$type" to BsonArray(listOf(BsonString("string"), BsonString("null"))))
        assertTrue(SpecMatcher.matches(either, BsonString("x")))
        assertTrue(SpecMatcher.matches(either, BsonNull))
        assertFalse(SpecMatcher.matches(either, BsonBoolean(true)))
    }
}
