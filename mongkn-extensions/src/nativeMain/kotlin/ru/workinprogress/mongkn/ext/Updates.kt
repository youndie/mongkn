package ru.workinprogress.mongkn.ext

import ru.workinprogress.mongkn.bson.BsonDocument
import ru.workinprogress.mongkn.bson.Document
import kotlin.reflect.KProperty1

/**
 * Документы обновления в инфиксной записи.
 *
 * ```
 * collection.updateOne(Person::name eq "Ada", Person::born setTo 1816)
 * ```
 *
 * Про имена полей действует то же ограничение, что и у фильтров, — см. KDoc [Filters.kt].
 */

/** `$set` — установить значение поля. */
public infix fun String.setTo(value: Any?): Document = update("\$set", this, value)

public infix fun <T, R> KProperty1<T, R>.setTo(value: R): Document = name setTo value

/** `$inc` — увеличить числовое поле. */
public infix fun String.incBy(delta: Number): Document = update("\$inc", this, delta)

public infix fun <T, R : Number> KProperty1<T, R>.incBy(delta: Number): Document = name incBy delta

/** `$unset` — убрать поле. Значение оператору безразлично, договорённость — пустая строка. */
public fun unset(field: String): Document = update("\$unset", field, "")

public fun <T, R> unset(property: KProperty1<T, R>): Document = unset(property.name)

/**
 * Складывает несколько обновлений в один документ.
 *
 * Операторы с одинаковым именем сливаются: `{"${'$'}set": {a: 1}}` и `{"${'$'}set": {b: 2}}` дают
 * `{"${'$'}set": {a: 1, b: 2}}`. Без слияния второй `$set` вытеснил бы первый — MongoDB не
 * принимает документ с повторяющимися операторами.
 */
public fun combine(vararg updates: Document): Document {
    val merged = linkedMapOf<String, MutableList<Pair<String, ru.workinprogress.mongkn.bson.BsonValue>>>()
    for (update in updates) {
        for ((operator, body) in update.entries) {
            val fields = (body as? BsonDocument)?.entries
                ?: throw IllegalArgumentException("mongkn: тело оператора $operator не документ: $body")
            merged.getOrPut(operator) { mutableListOf() } += fields
        }
    }
    return BsonDocument(merged.map { (operator, fields) -> operator to BsonDocument(fields.toList()) })
}

private fun update(operator: String, field: String, value: Any?): Document =
    BsonDocument(operator to BsonDocument(field to bsonOf(value)))
