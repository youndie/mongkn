package ru.workinprogress.mongkn

/**
 * Ошибка, поднятая из `bson_error_t`.
 *
 * Различать причины удобнее по [errorDomain], а не по числу: `domain` и `code` оставлены
 * как есть, потому что перечисление не обязано покрывать всё, что когда-нибудь появится
 * в libmongoc.
 *
 * Открыт для наследования ради [MongoBulkWriteException]: пакетная запись обязана донести
 * счётчики частично выполненного пакета, но ловиться должна тем же `catch (e: MongoException)`,
 * что и всё остальное. Отдельный, не связанный с этим типом класс тихо менял бы поведение
 * существующих обработчиков.
 *
 * @property domain значение `mongoc_error_domain_t` — см. `mongoc/mongoc-error.h`.
 * @property code код ошибки. Для ошибок сервера ([MongoErrorDomain.SERVER]) совпадает с кодом
 *   MongoDB (например `11000` — duplicate key), для клиентских — со значением
 *   из `mongoc_error_code_t`.
 */
public open class MongoException(
    public val domain: UInt,
    public val code: UInt,
    message: String,
    /**
     * Метки ошибки, присланные сервером, — `errorLabels` из ответа.
     *
     * Единственный **надёжный** способ понять, стоит ли операцию повторять: коды ошибок
     * для этого не годятся, потому что одна и та же ошибка бывает и повторяемой, и нет
     * в зависимости от того, что происходило на сервере. Так решает спецификация, и так же
     * решаем мы — см. [isTransientTransaction] и [isUnknownTransactionCommitResult].
     *
     * Пусто, если ответа сервера не было вовсе (обрыв связи, отказ выбора сервера).
     */
    public val labels: Set<String> = emptySet(),
) : RuntimeException("[$domain/$code] $message") {
    /** Транзакция не удалась целиком, но повторить её с начала имеет смысл. */
    public val isTransientTransaction: Boolean get() = TRANSIENT_TRANSACTION in labels

    /** Исход фиксации неизвестен: повторить надо **фиксацию**, а не всю транзакцию. */
    public val isUnknownTransactionCommitResult: Boolean get() = UNKNOWN_COMMIT in labels

    /** Область ошибки в читаемом виде. Неизвестный домен даёт [MongoErrorDomain.UNKNOWN]. */
    public val errorDomain: MongoErrorDomain get() = MongoErrorDomain.of(domain)

    /**
     * Отказало ли **соединение или выбор сервера**, а не сама операция.
     *
     * Отличие практическое: такие ошибки имеет смысл переживать повтором на уровне приложения,
     * а ошибку данных — нет. Повторы отдельных операций драйвер делает сам (см. `RetryTest`),
     * это признак для случаев, которые он не покрывает: транзакции, свои пакеты работы.
     */
    public val isConnectivity: Boolean
        get() =
            errorDomain in
                setOf(
                    MongoErrorDomain.STREAM,
                    MongoErrorDomain.SERVER_SELECTION,
                    MongoErrorDomain.POOL,
                )

    public companion object {
        internal const val TRANSIENT_TRANSACTION: String = "TransientTransactionError"
        internal const val UNKNOWN_COMMIT: String = "UnknownTransactionCommitResult"
    }
}
