package ru.workinprogress.mongkn

/**
 * Область, к которой относится ошибка, — `mongoc_error_domain_t` в читаемом виде.
 *
 * Нужна, чтобы обработка ошибок не сводилась к сравнению магических чисел. Номер по-прежнему
 * доступен как [MongoException.domain]: перечисление ничего не прячет, оно только называет.
 *
 * Значения совпадают на обеих ветках драйвера (1.26 и 2.1.1) — список сверялся по заголовкам,
 * а не по одному из них. Неизвестный номер отображается в [UNKNOWN], а не роняет разбор:
 * список доменов у libmongoc растёт от версии к версии, и падать на новом значении было бы
 * худшим из возможных поведений для библиотеки.
 */
public enum class MongoErrorDomain(
    public val value: UInt,
) {
    CLIENT(1u),
    STREAM(2u),
    PROTOCOL(3u),
    CURSOR(4u),
    QUERY(5u),
    INSERT(6u),
    SASL(7u),
    BSON(8u),
    MATCHER(9u),
    NAMESPACE(10u),
    COMMAND(11u),
    COLLECTION(12u),
    GRIDFS(13u),
    SCRAM(14u),
    SERVER_SELECTION(15u),
    WRITE_CONCERN(16u),

    /** Ошибка **сервера**, а не драйвера: [MongoException.code] здесь — код MongoDB. */
    SERVER(17u),
    TRANSACTION(18u),
    CLIENT_SIDE_ENCRYPTION(19u),
    POOL(20u),
    AZURE(21u),
    GCP(22u),

    /** Домен, которого не было в заголовках на момент сборки. */
    UNKNOWN(0u),
    ;

    public companion object {
        private val byValue = entries.associateBy { it.value }

        /** Отображает номер домена; неизвестный даёт [UNKNOWN]. */
        public fun of(value: UInt): MongoErrorDomain = byValue[value] ?: UNKNOWN
    }
}
