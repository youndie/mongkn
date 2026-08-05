package ru.workinprogress.mongkn

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import mongkn.cinterop.bson_error_t
import mongkn.cinterop.bson_t
import mongkn.cinterop.mongoc_error_has_label

/**
 * Метки, которые сервер повесил на ошибку.
 *
 * Спрашиваются у самого драйвера (`mongoc_error_has_label`), а не вычитываются из ответа руками:
 * разбирать `errorLabels` самостоятельно значило бы завести второй источник истины о том,
 * что считается повторяемым.
 *
 * Список меток фиксирован — драйвер умеет отвечать только «есть или нет», перечислить их нельзя.
 * Здесь ровно те две, которыми управляются повторы транзакций.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun labelsOf(reply: CPointer<bson_t>?): Set<String> {
    if (reply == null) return emptySet()
    return setOf(MongoException.TRANSIENT_TRANSACTION, MongoException.UNKNOWN_COMMIT)
        .filterTo(mutableSetOf()) { mongoc_error_has_label(reply, it) }
}

/** Поднимает [MongoException] из `bson_error_t`, добавляя метки из ответа, если он есть. */
@OptIn(ExperimentalForeignApi::class)
internal fun raise(
    error: CPointer<bson_error_t>,
    reply: CPointer<bson_t>? = null,
): Nothing {
    val value = error.pointed
    throw MongoException(value.domain, value.code, value.message.toKString(), labelsOf(reply))
}
