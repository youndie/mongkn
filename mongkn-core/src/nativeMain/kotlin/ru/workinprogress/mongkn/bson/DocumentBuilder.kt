package ru.workinprogress.mongkn.bson

/**
 * Минимальный билдер документов.
 *
 * Существует потому, что [BsonDocument] сам по себе многословен: фильтр без билдера пишется как
 * `BsonDocument("age" to BsonDocument("\$gt" to BsonInt32(18)))`. Это прямая цена решения Р4
 * (отказ от `Map<String, Any>`), а не начало языка запросов.
 *
 * Здесь намеренно **нет** infix-операторов вроде `"age" gt 18`: набор операторов диктуется тем,
 * что реально понадобится, а это станет видно только после рабочего `find`. Типобезопасный DSL —
 * веха M7, отдельным модулем (решение Р7).
 */
public fun document(build: DocumentBuilder.() -> Unit): BsonDocument = DocumentBuilder().apply(build).build()

@DslMarker
public annotation class BsonDsl

@BsonDsl
public class DocumentBuilder internal constructor() {
    private val entries = mutableListOf<Pair<String, BsonValue>>()

    public fun put(
        key: String,
        value: BsonValue,
    ) {
        entries += key to value
    }

    public fun put(
        key: String,
        value: String,
    ) {
        put(key, BsonString(value))
    }

    public fun put(
        key: String,
        value: Int,
    ) {
        put(key, BsonInt32(value))
    }

    public fun put(
        key: String,
        value: Long,
    ) {
        put(key, BsonInt64(value))
    }

    public fun put(
        key: String,
        value: Double,
    ) {
        put(key, BsonDouble(value))
    }

    public fun put(
        key: String,
        value: Boolean,
    ) {
        put(key, BsonBoolean(value))
    }

    public fun putNull(key: String) {
        put(key, BsonNull)
    }

    public fun putDocument(
        key: String,
        build: DocumentBuilder.() -> Unit,
    ) {
        put(key, DocumentBuilder().apply(build).build())
    }

    public fun putArray(
        key: String,
        build: ArrayBuilder.() -> Unit,
    ) {
        put(key, ArrayBuilder().apply(build).build())
    }

    internal fun build(): BsonDocument = BsonDocument(entries.toList())
}

@BsonDsl
public class ArrayBuilder internal constructor() {
    private val values = mutableListOf<BsonValue>()

    public fun add(value: BsonValue) {
        values += value
    }

    public fun add(value: String) {
        add(BsonString(value))
    }

    public fun add(value: Int) {
        add(BsonInt32(value))
    }

    public fun add(value: Long) {
        add(BsonInt64(value))
    }

    public fun add(value: Double) {
        add(BsonDouble(value))
    }

    public fun add(value: Boolean) {
        add(BsonBoolean(value))
    }

    public fun addDocument(build: DocumentBuilder.() -> Unit) {
        add(DocumentBuilder().apply(build).build())
    }

    public fun addArray(build: ArrayBuilder.() -> Unit) {
        add(ArrayBuilder().apply(build).build())
    }

    internal fun build(): BsonArray = BsonArray(values.toList())
}
