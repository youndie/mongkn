package ru.workinprogress.mongkn

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import mongkn.cinterop.MONGOC_READ_NEAREST
import mongkn.cinterop.MONGOC_READ_PRIMARY
import mongkn.cinterop.MONGOC_READ_PRIMARY_PREFERRED
import mongkn.cinterop.MONGOC_READ_SECONDARY
import mongkn.cinterop.MONGOC_READ_SECONDARY_PREFERRED
import mongkn.cinterop.bson_destroy
import mongkn.cinterop.mongoc_read_prefs_destroy
import mongkn.cinterop.mongoc_read_prefs_new
import mongkn.cinterop.mongoc_read_prefs_set_max_staleness_seconds
import mongkn.cinterop.mongoc_read_prefs_set_tags
import mongkn.cinterop.mongoc_read_prefs_t
import ru.workinprogress.mongkn.bson.BsonArray
import ru.workinprogress.mongkn.bson.BsonDocument
import ru.workinprogress.mongkn.bson.BsonInt64
import ru.workinprogress.mongkn.bson.BsonString
import ru.workinprogress.mongkn.bson.Document
import ru.workinprogress.mongkn.bson.toNativeBson

/**
 * Собирает `mongoc_read_prefs_t` из описания, отдаёт его в [body] и уничтожает.
 *
 * `null` на входе даёт `null` на выходе — libmongoc понимает это как «умолчание из строки
 * подключения», и подставлять `primary` от себя было бы подменой настройки пользователя.
 */
@OptIn(ExperimentalForeignApi::class)
internal inline fun <T> withReadPrefs(
    description: Document?,
    body: (CPointer<mongoc_read_prefs_t>?) -> T,
): T {
    if (description == null) return body(null)
    val mode =
        when ((description["mode"] as? BsonString)?.value) {
            "primary" -> MONGOC_READ_PRIMARY
            "primaryPreferred" -> MONGOC_READ_PRIMARY_PREFERRED
            "secondary" -> MONGOC_READ_SECONDARY
            "secondaryPreferred" -> MONGOC_READ_SECONDARY_PREFERRED
            "nearest" -> MONGOC_READ_NEAREST
            else -> error("неизвестный режим чтения: ${description["mode"]}")
        }
    val prefs = mongoc_read_prefs_new(mode) ?: error("mongoc_read_prefs_new вернул NULL")
    try {
        (description["tags"] as? BsonArray)?.let { tags ->
            // Метки уходят массивом документов; libmongoc копирует его себе.
            val native =
                BsonDocument(tags.values.mapIndexed { index, value -> index.toString() to value })
                    .toNativeBson()
            try {
                mongoc_read_prefs_set_tags(prefs, native)
            } finally {
                bson_destroy(native)
            }
        }
        (description["maxStalenessSeconds"] as? BsonInt64)?.let {
            mongoc_read_prefs_set_max_staleness_seconds(prefs, it.value)
        }
        return body(prefs)
    } finally {
        mongoc_read_prefs_destroy(prefs)
    }
}
