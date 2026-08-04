package ru.workinprogress.mongkn

/**
 * Ошибка, поднятая из `bson_error_t`.
 *
 * @property domain значение `mongoc_error_domain_t` — см. `mongoc/mongoc-error.h`.
 *   Наблюдалось: `12` = `MONGOC_ERROR_COLLECTION` для ошибок записи.
 * @property code код ошибки. Для ошибок сервера совпадает с кодом MongoDB
 *   (например `11000` — duplicate key), для клиентских — со значением из `mongoc_error_code_t`.
 */
public class MongoException(
    public val domain: UInt,
    public val code: UInt,
    message: String,
) : RuntimeException("[$domain/$code] $message")
