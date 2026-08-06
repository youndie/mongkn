package ru.workinprogress.mongkn.ext

import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.serializer
import ru.workinprogress.mongkn.FindFlow
import ru.workinprogress.mongkn.MongoCollection
import ru.workinprogress.mongkn.bson.BsonArray
import ru.workinprogress.mongkn.bson.BsonDocument
import ru.workinprogress.mongkn.bson.BsonValue
import ru.workinprogress.mongkn.bson.Document
import kotlin.reflect.KProperty1

/**
 * Область, в которой фильтр знает класс, по которому строится.
 *
 * Знание это даёт две разные защиты, и обе — от **тихой** пропажи данных, той самой, при которой
 * MongoDB не возвращает ошибку, а просто ничего не находит:
 *
 * * **имя поля.** Берётся из `KProperty1.name`, то есть из имени свойства **Kotlin**, а
 *   в документе лежит **serial**-имя. Обычно они совпадают, но `@SerialName("born_year")` их
 *   разводит. Восстановить serial-имя по ссылке на свойство нельзя: `@SerialName` до дескриптора
 *   не доживает как аннотация, а рефлексии, которая связала бы свойство с элементом дескриптора,
 *   на Kotlin/Native нет. Но **обнаружить** расхождение можно — дескриптор здесь есть, и если
 *   имени свойства среди его элементов нет, вызов падает с перечислением того, что есть
 *   ([checkedField]);
 * * **тип значения.** Значение кодируется сериализатором **поля**, а не по своему рантайм-типу
 *   ([FieldCodec]). Без этого `"shopId" eq id` для поля с `StringAsBsonObjectId` уходил на сервер
 *   строкой, тогда как в документе лежит `ObjectId`, — и не совпадал никогда.
 *
 * Вторая защита работает и в **строковой** форме: одноимённые функции ниже перекрывают
 * глобальные из [Filters] внутри этой области. Для поля, известного классу, значение кодируется
 * правильно; для неизвестного (составной путь `"a.b"`, поле не из модели) поведение прежнее.
 */
public class FilterScope<T>
    @PublishedApi
    internal constructor(
        // @PublishedApi, потому что конструктор зовут публичные inline-функции ниже: обычный
        // internal им недоступен.
        @PublishedApi
        internal val codec: FieldCodec<T>,
    ) {
        public infix fun <R> KProperty1<T, R>.eq(value: R): Document = BsonDocument(field() to strict(value))

        public infix fun <R> KProperty1<T, R>.ne(value: R): Document = field().compare("\$ne", strict(value))

        public infix fun <R> KProperty1<T, R>.gt(value: R): Document = field().compare("\$gt", strict(value))

        public infix fun <R> KProperty1<T, R>.gte(value: R): Document = field().compare("\$gte", strict(value))

        public infix fun <R> KProperty1<T, R>.lt(value: R): Document = field().compare("\$lt", strict(value))

        public infix fun <R> KProperty1<T, R>.lte(value: R): Document = field().compare("\$lte", strict(value))

        public infix fun <R> KProperty1<T, R>.within(values: Collection<R>): Document =
            field().compare("\$in", BsonArray(values.map { strict(it) }))

        /** Значение здесь — признак наличия, а не значение поля, поэтому сериализатор поля ни при чём. */
        public infix fun <R> KProperty1<T, R>.exists(present: Boolean): Document =
            field().compare("\$exists", bsonOf(present))

        public infix fun String.eq(value: Any?): Document = BsonDocument(this to lenient(value))

        public infix fun String.ne(value: Any?): Document = compare("\$ne", lenient(value))

        public infix fun String.gt(value: Any?): Document = compare("\$gt", lenient(value))

        public infix fun String.gte(value: Any?): Document = compare("\$gte", lenient(value))

        public infix fun String.lt(value: Any?): Document = compare("\$lt", lenient(value))

        public infix fun String.lte(value: Any?): Document = compare("\$lte", lenient(value))

        public infix fun String.within(values: Collection<Any?>): Document =
            compare("\$in", BsonArray(values.map { lenient(it) }))

        public infix fun String.exists(present: Boolean): Document = compare("\$exists", bsonOf(present))

        private fun KProperty1<T, *>.field(): String = checkedField(codec.descriptor, name)

        private fun <R> KProperty1<T, R>.strict(value: R): BsonValue =
            codec.encode(codec.indexOf(checkedField(codec.descriptor, name)), value, strict = true)

        private fun String.lenient(value: Any?): BsonValue = codec.encode(codec.indexOf(this), value, strict = false)
    }

/** То же для документов обновления. */
public class UpdateScope<T>
    @PublishedApi
    internal constructor(
        @PublishedApi
        internal val codec: FieldCodec<T>,
    ) {
        public infix fun <R> KProperty1<T, R>.setTo(value: R): Document =
            BsonDocument("\$set" to BsonDocument(field() to strict(value)))

        public infix fun String.setTo(value: Any?): Document =
            BsonDocument("\$set" to BsonDocument(this to codec.encode(codec.indexOf(this), value, strict = false)))

        /** Приращение — всегда число, а не значение поля: сериализатор поля здесь не применяется. */
        public infix fun <R : Number> KProperty1<T, R>.incBy(delta: Number): Document = field() incBy delta

        public fun <R> unset(property: KProperty1<T, R>): Document =
            unset(checkedField(codec.descriptor, property.name))

        private fun KProperty1<T, *>.field(): String = checkedField(codec.descriptor, name)

        private fun <R> KProperty1<T, R>.strict(value: R): BsonValue =
            codec.encode(codec.indexOf(checkedField(codec.descriptor, name)), value, strict = true)
    }

/**
 * Проверяет, что поле вообще существует в сериализованном виде класса.
 *
 * Ловит сразу два случая: `@SerialName`, из-за которого имя в базе другое, и опечатку
 * в рефакторинге, когда свойство переименовали, а фильтр — нет.
 */
internal fun checkedField(
    descriptor: SerialDescriptor,
    name: String,
): String {
    if (name in descriptor.elementNames) return name
    throw IllegalArgumentException(
        "mongkn: у ${descriptor.serialName} нет поля \"$name\". В документе лежат " +
            "${descriptor.elementNames.toList()}. Если поле переименовано через @SerialName, " +
            "укажите его строкой: \"нужное_имя\" eq …",
    )
}

/** Собирает фильтр со ссылками на свойства и проверкой имён. */
public inline fun <reified T> filter(block: FilterScope<T>.() -> Document): Document =
    FilterScope<T>(FieldCodec(serializer<T>())).block()

/** Собирает документ обновления со ссылками на свойства и проверкой имён. */
public inline fun <reified T> update(block: UpdateScope<T>.() -> Document): Document =
    UpdateScope<T>(FieldCodec(serializer<T>())).block()

/**
 * `collection.find { Person::born gt 1900 }`.
 *
 * Дескриптор берётся из `reified T` в точке вызова, а не из коллекции: так расширение
 * не требует от ядра открывать наружу свой сериализатор.
 */
public inline fun <reified T> MongoCollection<T>.find(block: FilterScope<T>.() -> Document): FindFlow<T> =
    find(filter(block))

/** `collection.deleteOne { Person::name eq "Ada" }`. */
public suspend inline fun <reified T> MongoCollection<T>.deleteOne(
    block: FilterScope<T>.() -> Document,
): ru.workinprogress.mongkn.DeleteResult = deleteOne(filter(block))

/** `collection.countDocuments { Person::born gt 1900 }`. */
public suspend inline fun <reified T> MongoCollection<T>.countDocuments(block: FilterScope<T>.() -> Document): Long =
    countDocuments(filter(block))
