package io.github.mongkn

import io.github.mongkn.bson.BsonDocument
import io.github.mongkn.bson.Document
import io.github.mongkn.bson.toDocument
import io.github.mongkn.bson.toNativeBson
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import mongkn.cinterop.bson_destroy
import mongkn.cinterop.bson_error_t
import mongkn.cinterop.bson_t
import mongkn.cinterop.mongoc_client_get_collection
import mongkn.cinterop.mongoc_collection_destroy
import mongkn.cinterop.mongoc_collection_find_with_opts
import mongkn.cinterop.mongoc_collection_insert_one
import mongkn.cinterop.mongoc_cursor_destroy
import mongkn.cinterop.mongoc_cursor_error
import mongkn.cinterop.mongoc_cursor_next

/**
 * Коллекция MongoDB.
 *
 * Все операции блокирующие внутри — у `libmongoc` асинхронного API нет вовсе, — поэтому уходят
 * на собственный пул потоков клиента ([MongoClient.dispatcher]) и **никогда** на
 * `Dispatchers.Default`: тот процессорный, и он же многопоточный на Kotlin/Native, что при
 * общем `mongoc_client_t` дало бы гонку (ресёрч §1.4). `Dispatchers.IO` использовать нельзя —
 * на Kotlin/Native он `internal` (ресёрч §1.8).
 *
 * Собственных C-ресурсов между вызовами не держит: `mongoc_collection_t` привязан к клиенту,
 * а клиент берётся из пула на время операции.
 */
@OptIn(ExperimentalForeignApi::class)
public class MongoCollection internal constructor(
    private val client: MongoClient,
    private val databaseName: String,
    public val name: String,
) {

    /**
     * Вставляет документ и возвращает его `_id`.
     *
     * Неуспех — всегда [MongoException]; признака неуспеха в возвращаемом значении нет (решение Р3).
     *
     * Отмена корутины не прервёт уже начатый сетевой вызов: драйвер синхронный. Верхняя граница
     * ожидания задаётся только таймаутами в строке подключения — риск 2 ресёрча.
     */
    public suspend fun insertOne(document: Document): InsertOneResult {
        client.checkOpen()
        return withContext(client.dispatcher) {
            client.withClient { handle ->
                val collection = mongoc_client_get_collection(handle, databaseName, name)
                    ?: error("mongoc_client_get_collection вернул NULL")
                try {
                    val payload = document.toNativeBson()
                    try {
                        memScoped {
                            val reply = alloc<bson_t>()
                            val error = alloc<bson_error_t>()
                            // reply инициализируется драйвером и при неуспехе тоже — проверено
                            // прогоном на дубликате ключа, — поэтому destroy стоит в finally
                            // без оглядки на результат.
                            try {
                                val ok = mongoc_collection_insert_one(
                                    collection, payload, null, reply.ptr, error.ptr,
                                )
                                if (!ok) {
                                    throw MongoException(error.domain, error.code, error.message.toKString())
                                }
                                val insertedId = reply.ptr.toDocument()["insertedId"]
                                    ?: error("в ответе сервера нет insertedId: ${reply.ptr.toDocument()}")
                                InsertOneResult(insertedId)
                            } finally {
                                bson_destroy(reply.ptr)
                            }
                        }
                    } finally {
                        bson_destroy(payload)
                    }
                } finally {
                    mongoc_collection_destroy(collection)
                }
            }
        }
    }

    /**
     * Читает документы по фильтру.
     *
     * Курсор, клиент и дескриптор коллекции живут ровно столько, сколько идёт сбор потока,
     * и освобождаются в `finally` при любом исходе — включая отмену сбора и исключение
     * у потребителя. Это критично: `mongoc_cursor_destroy` — единственное, что закрывает курсор
     * на сервере.
     *
     * Каждый документ переводится в Kotlin **до** эмиссии: указатель, который отдаёт
     * `mongoc_cursor_next`, действителен только до следующего вызова `next`, а между эмиссией
     * и следующим витком потребитель успевает поработать.
     */
    public fun find(filter: Document = BsonDocument()): Flow<Document> = flow {
        client.withClient { handle ->
            val collection = mongoc_client_get_collection(handle, databaseName, name)
                ?: error("mongoc_client_get_collection вернул NULL")
            try {
                val nativeFilter = filter.toNativeBson()
                try {
                    val cursor = mongoc_collection_find_with_opts(collection, nativeFilter, null, null)
                        ?: error("mongoc_collection_find_with_opts вернул NULL")
                    try {
                        memScoped {
                            val current = allocPointerTo<bson_t>()
                            while (mongoc_cursor_next(cursor, current.ptr)) {
                                val document = current.value?.toDocument()
                                    ?: error("mongoc_cursor_next отдал NULL при true")
                                emit(document)
                            }
                            // Курсор заканчивается и по исчерпанию, и по ошибке — различить
                            // их можно только здесь.
                            val error = alloc<bson_error_t>()
                            if (mongoc_cursor_error(cursor, error.ptr)) {
                                throw MongoException(error.domain, error.code, error.message.toKString())
                            }
                        }
                    } finally {
                        mongoc_cursor_destroy(cursor)
                    }
                } finally {
                    bson_destroy(nativeFilter)
                }
            } finally {
                mongoc_collection_destroy(collection)
            }
        }
    }.flowOn(client.dispatcher)
}
