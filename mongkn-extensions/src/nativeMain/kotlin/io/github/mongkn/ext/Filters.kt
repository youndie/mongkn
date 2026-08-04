package io.github.mongkn.ext

import io.github.mongkn.bson.BsonArray
import io.github.mongkn.bson.BsonBoolean
import io.github.mongkn.bson.BsonDocument
import io.github.mongkn.bson.BsonDouble
import io.github.mongkn.bson.BsonInt32
import io.github.mongkn.bson.BsonInt64
import io.github.mongkn.bson.BsonNull
import io.github.mongkn.bson.BsonString
import io.github.mongkn.bson.BsonValue
import io.github.mongkn.bson.Document
import kotlin.reflect.KProperty1

/**
 * Фильтры в инфиксной записи.
 *
 * Отдельный модуль, а не часть ядра, — решение Р7 ресёрча: зеркало официального API отвечает
 * за форму, эргономика кладётся сверху. К той же схеме пришёл вендор, вынеся
 * `mongodb-driver-kotlin-extensions` из основного драйвера.
 *
 * ```
 * collection.find(Person::born gt 1900)
 * collection.find(and(Person::born gt 1900, Person::name eq "Grace"))
 * ```
 *
 * **Ограничение, которое надо знать.** Имя поля берётся из имени свойства Kotlin
 * (`KProperty1.name`). Если класс размечен `@SerialName`, имя в базе отличается, и фильтр
 * не найдёт документ — молча, потому что несуществующее поле для MongoDB это просто «не
 * совпало». Сопоставить одно с другим без рефлексии над дескриптором нельзя, а на Kotlin/Native
 * связи «свойство → элемент дескриптора» нет. До решения — не используйте `@SerialName` вместе
 * с этим DSL либо задавайте поле строкой: строковые перегрузки есть у каждой операции.
 */

// --- сравнения ---------------------------------------------------------------------------

/** Равенство. На проводе это не `$eq`, а просто значение — так же делает официальный драйвер. */
public infix fun String.eq(value: Any?): Document = BsonDocument(this to bsonOf(value))

public infix fun <T, R> KProperty1<T, R>.eq(value: R): Document = name eq value

public infix fun String.ne(value: Any?): Document = compare("\$ne", value)
public infix fun <T, R> KProperty1<T, R>.ne(value: R): Document = name ne value

public infix fun String.gt(value: Any?): Document = compare("\$gt", value)
public infix fun <T, R> KProperty1<T, R>.gt(value: R): Document = name gt value

public infix fun String.gte(value: Any?): Document = compare("\$gte", value)
public infix fun <T, R> KProperty1<T, R>.gte(value: R): Document = name gte value

public infix fun String.lt(value: Any?): Document = compare("\$lt", value)
public infix fun <T, R> KProperty1<T, R>.lt(value: R): Document = name lt value

public infix fun String.lte(value: Any?): Document = compare("\$lte", value)
public infix fun <T, R> KProperty1<T, R>.lte(value: R): Document = name lte value

/** Вхождение в набор значений. */
public infix fun String.within(values: Collection<Any?>): Document =
    compare("\$in", BsonArray(values.map(::bsonOf)))

public infix fun <T, R> KProperty1<T, R>.within(values: Collection<R>): Document = name within values

/** Наличие или отсутствие поля. */
public infix fun String.exists(present: Boolean): Document = compare("\$exists", present)
public infix fun <T, R> KProperty1<T, R>.exists(present: Boolean): Document = name exists present

// --- композиция --------------------------------------------------------------------------

/**
 * Логическое «и».
 *
 * Не склеиваем фильтры в один документ: у двух условий на **одно** поле совпали бы ключи,
 * и второе молча вытеснило бы первое. `$and` явный и потому безопасный.
 */
public fun and(vararg filters: Document): Document =
    BsonDocument("\$and" to BsonArray(filters.toList()))

public fun or(vararg filters: Document): Document =
    BsonDocument("\$or" to BsonArray(filters.toList()))

public fun not(filter: Document): Document = BsonDocument("\$nor" to BsonArray(listOf(filter)))

// --- внутреннее --------------------------------------------------------------------------

private fun String.compare(operator: String, value: Any?): Document =
    BsonDocument(this to BsonDocument(operator to bsonOf(value)))

/**
 * Переводит значение фильтра в BSON.
 *
 * Здесь `Any?` допустим, в отличие от документов (решение Р4): фильтр живёт один запрос
 * и обратно не читается, поэтому потери типа при round-trip взяться неоткуда. Незнакомый тип
 * роняет вызов сразу — молча превратить его в строку было бы хуже.
 */
internal fun bsonOf(value: Any?): BsonValue = when (value) {
    null -> BsonNull
    is BsonValue -> value
    is String -> BsonString(value)
    is Int -> BsonInt32(value)
    is Long -> BsonInt64(value)
    is Double -> BsonDouble(value)
    is Float -> BsonDouble(value.toDouble())
    is Boolean -> BsonBoolean(value)
    is Short -> BsonInt32(value.toInt())
    is Byte -> BsonInt32(value.toInt())
    is Enum<*> -> BsonString(value.name)
    is Collection<*> -> BsonArray(value.map(::bsonOf))
    else -> throw IllegalArgumentException(
        "mongkn: значение типа ${value::class.simpleName} не переводится в BSON. " +
            "Соберите его как BsonValue явно."
    )
}
