package ru.workinprogress.mongkn.ext

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.workinprogress.mongkn.bson.BsonArray
import ru.workinprogress.mongkn.bson.BsonDocument
import ru.workinprogress.mongkn.bson.BsonInt32
import ru.workinprogress.mongkn.bson.BsonInt64
import ru.workinprogress.mongkn.bson.BsonObjectId
import ru.workinprogress.mongkn.bson.BsonString
import ru.workinprogress.mongkn.bson.document
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Значение фильтра кодируется сериализатором **поля**, а не по рантайм-типу Kotlin.
 *
 * Заведён по отчёту первого потребителя, и находка там была самой дорогой: фильтр
 * по полю с `StringAsBsonObjectId` уходил на сервер строкой, тогда как в документе лежит
 * `ObjectId`. Для MongoDB это разные типы — условие не совпадало никогда, запрос не падал,
 * и `deleteMany` тихо переставал удалять.
 *
 * Поэтому проверяется **тип значения в собранном документе**, а не только то, что фильтр
 * построился: именно на «построился» баг и держался.
 */
class FieldCodecTest {
    private val id = "64b7f1c2a4e8d9b0c1a2e3f4"

    @Serializable
    private data class Landing(
        @SerialName("_id")
        @Serializable(with = StringAsBsonObjectId::class)
        val id: String,
        @Serializable(with = StringAsBsonObjectId::class)
        val shopId: String,
        val version: Int,
    )

    @Test
    fun `a property reference filter encodes the value as ObjectId`() {
        val built = filter<Landing> { Landing::shopId eq id }

        assertIs<BsonObjectId>(built["shopId"], "в фильтр уехала строка вместо ObjectId: $built")
        assertEquals(BsonObjectId.parse(id), built["shopId"])
    }

    @Test
    fun `a string form filter encodes the value as ObjectId too`() {
        // Строковая форма — не запасной путь, а единственный доступный для поля с @SerialName:
        // ссылка на свойство `id` не совпадает с serial-именем `_id`. Именно так и написан
        // фильтр по `_id` у потребителя, поэтому защита обязана работать и здесь.
        val built = filter<Landing> { "_id" eq id }

        assertIs<BsonObjectId>(built["_id"], "в фильтр уехала строка вместо ObjectId: $built")
    }

    @Test
    fun `an in filter encodes every element as ObjectId`() {
        val other = "64b7f1c2a4e8d9b0c1a2e3f5"

        val built = filter<Landing> { Landing::shopId within listOf(id, other) }

        val values = (built["shopId"] as BsonDocument)["\$in"]
        assertIs<BsonArray>(values)
        assertEquals(listOf(BsonObjectId.parse(id), BsonObjectId.parse(other)), values.values)
    }

    @Test
    fun `a comparison filter encodes the value as ObjectId`() {
        val built = filter<Landing> { Landing::shopId ne id }

        assertEquals(BsonObjectId.parse(id), (built["shopId"] as BsonDocument)["\$ne"])
    }

    @Test
    fun `an update encodes the value as ObjectId as well`() {
        // Тот же дефект на записи, и он опаснее чтения: неверный тип не «ничего не находит»,
        // а ложится в документ и остаётся там.
        val built = update<Landing> { Landing::shopId setTo id }

        assertEquals(BsonObjectId.parse(id), (built["\$set"] as BsonDocument)["shopId"])
    }

    @Test
    fun `a plain field keeps its ordinary encoding`() {
        val built = filter<Landing> { Landing::version eq 3 }

        assertEquals(BsonInt32(3), built["version"])
    }

    @Test
    fun `a field the class does not know falls back to the old behaviour`() {
        // Составной путь в фильтре — законное дело, и класс о нём ничего не знает.
        // Такое значение обязано кодироваться как раньше, а не падать.
        val built = filter<Landing> { "meta.author" eq "Ada" }

        assertEquals(BsonString("Ada"), built["meta.author"])
    }

    @Test
    fun `a value of another type does not break the string form`() {
        // Строковая форма принимает Any?, и совпадение типа значения с типом поля ничем
        // не гарантировано. `"version" eq 3L` для поля Int работал раньше — MongoDB сравнивает
        // числа между типами, — и правка ради ObjectId не вправе это ломать.
        val built = filter<Landing> { "version" eq 3L }

        assertEquals(BsonInt64(3), built["version"])
    }

    @Test
    fun `exists still asks about presence and not about the field type`() {
        // Значение здесь — признак наличия, а не значение поля: применить к нему сериализатор
        // поля значило бы отправить на сервер ObjectId вместо true.
        val built = filter<Landing> { Landing::shopId exists true }

        assertEquals(document { put("shopId", document { put("\$exists", true) }) }, built)
    }

    @Test
    fun `a generated ObjectId is unique and has the canonical shape`() {
        val first = BsonObjectId.generate()
        val second = BsonObjectId.generate()

        assertEquals(24, first.hex.length)
        assertEquals(BsonObjectId.SIZE, first.toByteArray().size)
        assertEquals(false, first == second, "два подряд выданных ObjectId совпали: ${first.hex}")
        // Счётчик растёт, а случайная часть общая на процесс — значит, средние 5 байт совпадают.
        assertEquals(
            first.toByteArray().slice(4..8),
            second.toByteArray().slice(4..8),
            "случайная часть обязана фиксироваться один раз на процесс",
        )
    }
}
