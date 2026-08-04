package ru.workinprogress.mongkn.ext

import ru.workinprogress.mongkn.bson.BsonArray
import ru.workinprogress.mongkn.bson.BsonBoolean
import ru.workinprogress.mongkn.bson.BsonDocument
import ru.workinprogress.mongkn.bson.BsonDouble
import ru.workinprogress.mongkn.bson.BsonInt32
import ru.workinprogress.mongkn.bson.BsonInt64
import ru.workinprogress.mongkn.bson.BsonNull
import ru.workinprogress.mongkn.bson.BsonString
import ru.workinprogress.mongkn.bson.BsonValue
import ru.workinprogress.mongkn.bson.Document

/**
 * Фильтры в инфиксной записи.
 *
 * Отдельный модуль, а не часть ядра, — решение Р7 ресёрча: зеркало официального API отвечает
 * за форму, эргономика кладётся сверху. К той же схеме пришёл вендор, вынеся
 * `mongodb-driver-kotlin-extensions` из основного драйвера.
 *
 * ```
 * collection.find { Person::born gt 1900 }
 * collection.find { and(Person::born gt 1900, Person::name eq "Grace") }
 * ```
 *
 * Здесь только строковые перегрузки — поле задаётся именем как есть. Варианты со ссылками
 * на свойства (`Person::born gt 1900`) живут в [FilterScope]: там известен дескриптор класса,
 * и опечатку или `@SerialName` можно поймать, а не проглотить (M-36).
 */

// --- сравнения ---------------------------------------------------------------------------

/** Равенство. На проводе это не `$eq`, а просто значение — так же делает официальный драйвер. */
public infix fun String.eq(value: Any?): Document = BsonDocument(this to bsonOf(value))


public infix fun String.ne(value: Any?): Document = compare("\$ne", value)

public infix fun String.gt(value: Any?): Document = compare("\$gt", value)

public infix fun String.gte(value: Any?): Document = compare("\$gte", value)

public infix fun String.lt(value: Any?): Document = compare("\$lt", value)

public infix fun String.lte(value: Any?): Document = compare("\$lte", value)

/** Вхождение в набор значений. */
public infix fun String.within(values: Collection<Any?>): Document =
    compare("\$in", BsonArray(values.map(::bsonOf)))


/** Наличие или отсутствие поля. */
public infix fun String.exists(present: Boolean): Document = compare("\$exists", present)

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
