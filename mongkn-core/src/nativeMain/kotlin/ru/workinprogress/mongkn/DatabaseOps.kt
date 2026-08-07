package ru.workinprogress.mongkn

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import mongkn.cinterop.bson_destroy
import mongkn.cinterop.bson_error_t
import mongkn.cinterop.bson_strfreev
import mongkn.cinterop.bson_t
import mongkn.cinterop.mongoc_client_get_database
import mongkn.cinterop.mongoc_client_get_database_names_with_opts
import mongkn.cinterop.mongoc_client_watch
import mongkn.cinterop.mongoc_database_aggregate
import mongkn.cinterop.mongoc_database_command_simple
import mongkn.cinterop.mongoc_database_create_collection
import mongkn.cinterop.mongoc_database_destroy
import mongkn.cinterop.mongoc_database_drop_with_opts
import mongkn.cinterop.mongoc_database_get_collection_names_with_opts
import mongkn.cinterop.mongoc_database_t
import mongkn.cinterop.mongoc_database_watch
import ru.workinprogress.mongkn.bson.BsonDocument
import ru.workinprogress.mongkn.bson.Document
import ru.workinprogress.mongkn.bson.toDocument
import ru.workinprogress.mongkn.bson.toNativeBson

/**
 * Операции уровня базы и клиента.
 *
 * Отдельно от [CollectionOps] по той же причине, по которой те отдельно от `MongoCollection`:
 * весь cinterop собран в одном месте, наружу уходят только Kotlin-значения.
 */
@OptIn(ExperimentalForeignApi::class, DelicateCoroutinesApi::class)
internal object DatabaseOps {
    /**
     * Выполняет произвольную команду.
     *
     * Самая ценная из операций базы: через неё делается всё, чего нет в типизированном API —
     * индексы, статистика, административные команды. До неё тесты лезли в cinterop напрямую.
     */
    suspend fun runCommand(
        client: Target,
        databaseName: String,
        command: Document,
    ): Document =
        withDatabase(client, databaseName) { database ->
            withBson(command) { payload ->
                val reply = alloc<bson_t>()
                val error = alloc<bson_error_t>()
                try {
                    if (!mongoc_database_command_simple(database, payload, null, reply.ptr, error.ptr)) {
                        fail(error.ptr)
                    }
                    reply.ptr.toDocument()
                } finally {
                    bson_destroy(reply.ptr)
                }
            }
        }

    suspend fun createCollection(
        client: Target,
        databaseName: String,
        name: String,
        options: Document,
    ) {
        withDatabase(client, databaseName) { database ->
            withBson(options) { opts ->
                val error = alloc<bson_error_t>()
                val created =
                    mongoc_database_create_collection(database, name, opts, error.ptr)
                        ?: fail(error.ptr)
                // Дескриптор нам не нужен — коллекция уже создана на сервере.
                mongkn.cinterop.mongoc_collection_destroy(created)
            }
        }
    }

    suspend fun dropDatabase(
        client: Target,
        databaseName: String,
    ) {
        withDatabase(client, databaseName) { database ->
            val error = alloc<bson_error_t>()
            if (!mongoc_database_drop_with_opts(database, null, error.ptr)) fail(error.ptr)
        }
    }

    suspend fun listCollectionNames(
        client: Target,
        databaseName: String,
    ): List<String> =
        withDatabase(client, databaseName) { database ->
            val error = alloc<bson_error_t>()
            val names =
                mongoc_database_get_collection_names_with_opts(database, null, error.ptr)
                    ?: fail(error.ptr)
            readStringArray(names)
        }

    suspend fun listDatabaseNames(client: Target): List<String> =
        client.withClient { handle ->
            memScoped {
                val error = alloc<bson_error_t>()
                val names =
                    mongoc_client_get_database_names_with_opts(handle, null, error.ptr)
                        ?: fail(error.ptr)
                readStringArray(names)
            }
        }

    /**
     * Агрегационный конвейер уровня базы.
     *
     * Нужен ради стадий, которым коллекция не с чего начинать: `$currentOp`, `$listLocalSessions`,
     * `$documents`. На проводе это команда `aggregate: 1`.
     *
     * Через [withDatabase] не идёт: тот `suspend`, а здесь нужен холодный `Flow`, внутри которого
     * происходит эмиссия. Поэтому дескриптор базы открывается и закрывается прямо здесь, а клиент
     * и разрешение семафора удерживаются всё время сбора — как в [CollectionOps.find].
     *
     * Флагов, в отличие от коллекционной агрегации, эта функция не принимает вовсе.
     */
    fun aggregate(
        client: Target,
        databaseName: String,
        pipeline: List<Document>,
        opts: Document,
    ): Flow<Document> =
        flow {
            client.withPermit {
                client.useClient { handle ->
                    val database =
                        mongoc_client_get_database(handle, databaseName)
                            ?: error("mongoc_client_get_database вернул NULL")
                    try {
                        val nativePipeline = pipelineDocument(pipeline).toNativeBson()
                        val nativeOpts = opts.toNativeBson()
                        try {
                            val cursor =
                                mongoc_database_aggregate(database, nativePipeline, nativeOpts, null)
                                    ?: error("mongoc_database_aggregate вернул NULL")
                            drainCursor(cursor, batchOf(opts)) { it.toDocument() }
                        } finally {
                            bson_destroy(nativeOpts)
                            bson_destroy(nativePipeline)
                        }
                    } finally {
                        mongoc_database_destroy(database)
                    }
                }
            }
        }.flowOn(client.dispatcher).flattenDocuments()

    /**
     * Подписка на изменения всей базы или всего развёртывания.
     *
     * Устроена как [CollectionOps.watch] — свой поток на подписку и `channelFlow`, — и по той же
     * причине: подписка бесконечна, а вызов libmongoc блокирующий.
     *
     * @param databaseName `null` — подписка уровня клиента, то есть на все базы сразу.
     */
    fun watch(
        client: Target,
        databaseName: String?,
        pipeline: List<Document>,
        opts: Document,
    ): Flow<Document> =
        channelFlow {
            val dispatcher = newSingleThreadContext("mongkn-watch")
            try {
                withContext(dispatcher) {
                    client.withPermit {
                        client.useClient { handle ->
                            val database = databaseName?.let { mongoc_client_get_database(handle, it) }
                            try {
                                withChangeStream(pipeline, opts) { nativePipeline, nativeOpts ->
                                    if (database == null) {
                                        mongoc_client_watch(handle, nativePipeline, nativeOpts)
                                    } else {
                                        mongoc_database_watch(database, nativePipeline, nativeOpts)
                                    }
                                }
                            } finally {
                                database?.let(::mongoc_database_destroy)
                            }
                        }
                    }
                }
            } finally {
                dispatcher.close()
            }
        }

    /**
     * Читает `char**`, завершённый NULL, и освобождает его.
     *
     * libmongoc отдаёт такие массивы во владение вызывающему; освобождать надо `bson_strfreev`,
     * а не `free` — она пройдёт по всем строкам.
     */
    private fun readStringArray(array: CPointer<CPointerVar<ByteVar>>): List<String> =
        try {
            buildList {
                var index = 0
                while (true) {
                    val item = array[index] ?: break
                    add(item.toKString())
                    index++
                }
            }
        } finally {
            bson_strfreev(array)
        }

    private suspend fun <T> withDatabase(
        client: Target,
        databaseName: String,
        block: kotlinx.cinterop.MemScope.(CPointer<mongoc_database_t>) -> T,
    ): T =
        client.withClient { handle ->
            val database =
                mongoc_client_get_database(handle, databaseName)
                    ?: error("mongoc_client_get_database вернул NULL")
            try {
                memScoped { block(database) }
            } finally {
                mongoc_database_destroy(database)
            }
        }

    private inline fun <T> withBson(
        document: Document,
        body: (CPointer<bson_t>) -> T,
    ): T {
        val native = document.toNativeBson()
        try {
            return body(native)
        } finally {
            bson_destroy(native)
        }
    }

    private fun fail(error: CPointer<bson_error_t>): Nothing {
        val value = error.pointed
        throw MongoException(value.domain, value.code, value.message.toKString())
    }
}
