package ru.workinprogress.mongkn

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import mongkn.cinterop.mongoc_read_concern_destroy
import mongkn.cinterop.mongoc_read_concern_new
import mongkn.cinterop.mongoc_read_concern_set_level
import mongkn.cinterop.mongoc_transaction_opt_t
import mongkn.cinterop.mongoc_transaction_opts_destroy
import mongkn.cinterop.mongoc_transaction_opts_new
import mongkn.cinterop.mongoc_transaction_opts_set_max_commit_time_ms
import mongkn.cinterop.mongoc_transaction_opts_set_read_concern
import mongkn.cinterop.mongoc_transaction_opts_set_read_prefs
import mongkn.cinterop.mongoc_transaction_opts_set_write_concern
import mongkn.cinterop.mongoc_write_concern_destroy
import mongkn.cinterop.mongoc_write_concern_new
import mongkn.cinterop.mongoc_write_concern_set_journal
import mongkn.cinterop.mongoc_write_concern_set_w
import mongkn.cinterop.mongoc_write_concern_set_wtag
import mongkn.cinterop.mongoc_write_concern_set_wtimeout_int64
import ru.workinprogress.mongkn.bson.BsonBoolean
import ru.workinprogress.mongkn.bson.BsonInt32
import ru.workinprogress.mongkn.bson.BsonInt64
import ru.workinprogress.mongkn.bson.BsonString
import ru.workinprogress.mongkn.bson.Document

/**
 * Настройки транзакции.
 *
 * Форма нарочно та же, что у настроек коллекции: [Concerns.writeConcern] и [Concerns.readConcern]
 * дают ровно те документы, которые здесь принимаются. Внутри они разбираются в структуры
 * libmongoc (`mongoc_transaction_opt_t` с сеттерами) — документа опций для транзакции драйвер
 * не принимает, и это единственная причина, по которой типа не было до M-74.
 *
 * @param readConcern гарантия чтения: `{"level": "majority"}` и подобное.
 * @param writeConcern гарантия записи: `{"w": …, "j": …, "wtimeout": …}`.
 * @param maxCommitTimeMillis сколько сервер вправе фиксировать транзакцию.
 * @param readPreference куда направлять чтения внутри транзакции.
 */
public class TransactionOptions(
    public val readConcern: Document? = null,
    public val writeConcern: Document? = null,
    public val maxCommitTimeMillis: Long? = null,
    public val readPreference: ReadPreference? = null,
)

/**
 * Собирает `mongoc_transaction_opt_t`, отдаёт в [body] и уничтожает вместе со всем, что создал.
 *
 * Владение здесь тройное — сами настройки, гарантия чтения и гарантия записи, — и libmongoc
 * копирует их себе, поэтому освобождать надо всё и в обратном порядке.
 *
 * Незнакомый ключ гарантии — **ошибка**, а не пропуск. Молча не доехавшая настройка ровно того
 * рода, что чинилась в M10: она выглядит применённой и не применяется.
 */
@OptIn(ExperimentalForeignApi::class)
internal inline fun <T> withTransactionOpts(
    options: TransactionOptions?,
    body: (CPointer<mongoc_transaction_opt_t>?) -> T,
): T {
    if (options == null) return body(null)
    val opts = mongoc_transaction_opts_new() ?: error("mongoc_transaction_opts_new вернул NULL")
    val readConcern =
        options.readConcern?.let { document ->
            val concern = mongoc_read_concern_new() ?: error("mongoc_read_concern_new вернул NULL")
            val level =
                (document["level"] as? BsonString)?.value
                    ?: error("readConcern: ждали ключ 'level' со строкой, получили $document")
            mongoc_read_concern_set_level(concern, level)
            mongoc_transaction_opts_set_read_concern(opts, concern)
            concern
        }
    val writeConcern =
        options.writeConcern?.let { document ->
            val concern = mongoc_write_concern_new() ?: error("mongoc_write_concern_new вернул NULL")
            for ((key, value) in document.entries) {
                when (key) {
                    // Число узлов и именованный режим — разные сеттеры, а не одно поле с двумя
                    // смыслами: в C они и типизированы по-разному.
                    "w" -> {
                        when (value) {
                            is BsonInt32 -> mongoc_write_concern_set_w(concern, value.value)
                            is BsonInt64 -> mongoc_write_concern_set_w(concern, value.value.toInt())
                            is BsonString -> mongoc_write_concern_set_wtag(concern, value.value)
                            else -> error("writeConcern: 'w' должен быть числом или строкой, получили $value")
                        }
                    }

                    "j" -> {
                        mongoc_write_concern_set_journal(concern, (value as? BsonBoolean)?.value == true)
                    }

                    "wtimeout" -> {
                        mongoc_write_concern_set_wtimeout_int64(
                            concern,
                            (value as? BsonInt64)?.value ?: (value as? BsonInt32)?.value?.toLong()
                                ?: error("writeConcern: 'wtimeout' должен быть числом, получили $value"),
                        )
                    }

                    else -> {
                        error("writeConcern: ключ '$key' не поддержан")
                    }
                }
            }
            mongoc_transaction_opts_set_write_concern(opts, concern)
            concern
        }
    try {
        options.maxCommitTimeMillis?.let { mongoc_transaction_opts_set_max_commit_time_ms(opts, it) }
        return withReadPrefs(options.readPreference?.describe()) { prefs ->
            if (prefs != null) mongoc_transaction_opts_set_read_prefs(opts, prefs)
            body(opts)
        }
    } finally {
        writeConcern?.let(::mongoc_write_concern_destroy)
        readConcern?.let(::mongoc_read_concern_destroy)
        mongoc_transaction_opts_destroy(opts)
    }
}
