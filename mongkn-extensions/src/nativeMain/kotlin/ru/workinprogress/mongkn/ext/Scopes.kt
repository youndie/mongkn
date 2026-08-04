package ru.workinprogress.mongkn.ext

import ru.workinprogress.mongkn.FindFlow
import ru.workinprogress.mongkn.MongoCollection
import ru.workinprogress.mongkn.bson.Document
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.serializer
import kotlin.reflect.KProperty1

/**
 * Область, в которой фильтр можно писать ссылками на свойства.
 *
 * Существует ради проверки, которой раньше не было (M-36). Имя поля берётся из
 * `KProperty1.name`, то есть из имени свойства **Kotlin**, а в документе лежит **serial-имя**.
 * Обычно они совпадают, но `@SerialName("born_year")` их разводит — и фильтр по такому полю
 * раньше молча не находил ничего: для MongoDB несуществующее поле это просто «не совпало».
 *
 * Восстановить serial-имя по ссылке на свойство нельзя: `@SerialName` до дескриптора
 * не доживает как аннотация, а рефлексии, которая связала бы свойство с элементом дескриптора,
 * на Kotlin/Native нет. Но **обнаружить** расхождение можно — дескриптор здесь есть, и если
 * имени свойства среди его элементов нет, вызов падает с перечислением того, что есть.
 *
 * То есть неразрешимое превращается из тихой пропажи данных в понятную ошибку.
 */
public class FilterScope<T> @PublishedApi internal constructor(
    // @PublishedApi, потому что конструктор зовут публичные inline-функции ниже: обычный
    // internal им недоступен.
    private val descriptor: SerialDescriptor,
) {

    public infix fun <R> KProperty1<T, R>.eq(value: R): Document = field() eq value
    public infix fun <R> KProperty1<T, R>.ne(value: R): Document = field() ne value
    public infix fun <R> KProperty1<T, R>.gt(value: R): Document = field() gt value
    public infix fun <R> KProperty1<T, R>.gte(value: R): Document = field() gte value
    public infix fun <R> KProperty1<T, R>.lt(value: R): Document = field() lt value
    public infix fun <R> KProperty1<T, R>.lte(value: R): Document = field() lte value
    public infix fun <R> KProperty1<T, R>.within(values: Collection<R>): Document = field() within values
    public infix fun <R> KProperty1<T, R>.exists(present: Boolean): Document = field() exists present

    private fun KProperty1<T, *>.field(): String = checkedField(descriptor, name)
}

/** То же для документов обновления. */
public class UpdateScope<T> @PublishedApi internal constructor(
    private val descriptor: SerialDescriptor,
) {

    public infix fun <R> KProperty1<T, R>.setTo(value: R): Document = field() setTo value
    public infix fun <R : Number> KProperty1<T, R>.incBy(delta: Number): Document = field() incBy delta
    public fun <R> unset(property: KProperty1<T, R>): Document = unset(checkedField(descriptor, property.name))

    private fun KProperty1<T, *>.field(): String = checkedField(descriptor, name)
}

/**
 * Проверяет, что поле вообще существует в сериализованном виде класса.
 *
 * Ловит сразу два случая: `@SerialName`, из-за которого имя в базе другое, и опечатку
 * в рефакторинге, когда свойство переименовали, а фильтр — нет.
 */
internal fun checkedField(descriptor: SerialDescriptor, name: String): String {
    if (name in descriptor.elementNames) return name
    throw IllegalArgumentException(
        "mongkn: у ${descriptor.serialName} нет поля \"$name\". В документе лежат " +
            "${descriptor.elementNames.toList()}. Если поле переименовано через @SerialName, " +
            "укажите его строкой: \"нужное_имя\" eq …"
    )
}

/** Собирает фильтр со ссылками на свойства и проверкой имён. */
public inline fun <reified T> filter(block: FilterScope<T>.() -> Document): Document =
    FilterScope<T>(serializer<T>().descriptor).block()

/** Собирает документ обновления со ссылками на свойства и проверкой имён. */
public inline fun <reified T> update(block: UpdateScope<T>.() -> Document): Document =
    UpdateScope<T>(serializer<T>().descriptor).block()

/**
 * `collection.find { Person::born gt 1900 }`.
 *
 * Дескриптор берётся из `reified T` в точке вызова, а не из коллекции: так расширение
 * не требует от ядра открывать наружу свой сериализатор.
 */
public inline fun <reified T> MongoCollection<T>.find(
    block: FilterScope<T>.() -> Document,
): FindFlow<T> = find(filter(block))

/** `collection.deleteOne { Person::name eq "Ada" }`. */
public suspend inline fun <reified T> MongoCollection<T>.deleteOne(
    block: FilterScope<T>.() -> Document,
): ru.workinprogress.mongkn.DeleteResult = deleteOne(filter(block))

/** `collection.countDocuments { Person::born gt 1900 }`. */
public suspend inline fun <reified T> MongoCollection<T>.countDocuments(
    block: FilterScope<T>.() -> Document,
): Long = countDocuments(filter(block))
