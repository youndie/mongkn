package ru.workinprogress.mongkn.ext

import ru.workinprogress.mongkn.bson.BsonBoolean
import ru.workinprogress.mongkn.bson.BsonDocument
import ru.workinprogress.mongkn.bson.BsonInt32
import ru.workinprogress.mongkn.bson.BsonInt64
import ru.workinprogress.mongkn.bson.BsonString
import ru.workinprogress.mongkn.bson.BsonValue
import ru.workinprogress.mongkn.bson.Document

/**
 * Ключи индекса — зеркало `com.mongodb.client.model.Indexes`.
 *
 * `createIndex`/`createIndexes` принимают документ напрямую, поэтому билдер ничего не открывает
 * заново — он лишь избавляет от кода, который иначе пишется в каждом репозитории одинаково.
 */
public object Indexes {
    public fun ascending(vararg fields: String): Document = BsonDocument(fields.map { it to BsonInt32(1) })

    public fun descending(vararg fields: String): Document = BsonDocument(fields.map { it to BsonInt32(-1) })
}

/**
 * Ключи сортировки для `.sort()` — зеркало `com.mongodb.client.model.Sorts`.
 *
 * Отдельный объект от [Indexes], хотя документ получается той же формы: так же разведено
 * и в официальном драйвере. У составного индекса набор допустимых значений шире (`"text"`,
 * `"2dsphere"` и прочие), у сортировки — нет.
 */
public object Sorts {
    public fun ascending(vararg fields: String): Document = BsonDocument(fields.map { it to BsonInt32(1) })

    public fun descending(vararg fields: String): Document = BsonDocument(fields.map { it to BsonInt32(-1) })
}

/**
 * Опции индекса: `unique`, `sparse`, TTL, явное имя.
 *
 * Не билдер, а функция с именованными параметрами: набор опций узкий и намеренно неполный —
 * расширять по мере надобности, а не заранее.
 *
 * **`sparse` и пустые поля.** Разреженный индекс считает отсутствующим только реально
 * отсутствующий ключ, а кодировщик mongkn пишет пустое поле явным `null`. Поэтому
 * `sparse = true` сам по себе **не** снимает конфликт уникальности по пустым значениям —
 * поле должно исчезнуть из документа, для чего служит `@EncodeDefault(NEVER)` на свойстве
 * с умолчанием `null`. Подробности — в `docs/api/serialization.md`.
 */
public fun indexOptions(
    unique: Boolean = false,
    sparse: Boolean = false,
    expireAfterSeconds: Long? = null,
    name: String? = null,
): Document {
    val entries = mutableListOf<Pair<String, BsonValue>>()
    if (unique) entries += "unique" to BsonBoolean(true)
    if (sparse) entries += "sparse" to BsonBoolean(true)
    expireAfterSeconds?.let { entries += "expireAfterSeconds" to BsonInt64(it) }
    name?.let { entries += "name" to BsonString(it) }
    return BsonDocument(entries)
}
