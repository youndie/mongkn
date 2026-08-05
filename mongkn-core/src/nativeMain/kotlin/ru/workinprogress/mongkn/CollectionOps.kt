package ru.workinprogress.mongkn

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import mongkn.cinterop.MONGOC_QUERY_NONE
import mongkn.cinterop.bson_destroy
import mongkn.cinterop.bson_error_t
import mongkn.cinterop.bson_free
import mongkn.cinterop.bson_oid_init
import mongkn.cinterop.bson_oid_t
import mongkn.cinterop.bson_t
import mongkn.cinterop.mongoc_bulk_operation_destroy
import mongkn.cinterop.mongoc_bulk_operation_execute
import mongkn.cinterop.mongoc_bulk_operation_insert_with_opts
import mongkn.cinterop.mongoc_bulk_operation_remove_many_with_opts
import mongkn.cinterop.mongoc_bulk_operation_remove_one_with_opts
import mongkn.cinterop.mongoc_bulk_operation_replace_one_with_opts
import mongkn.cinterop.mongoc_bulk_operation_t
import mongkn.cinterop.mongoc_bulk_operation_update_many_with_opts
import mongkn.cinterop.mongoc_bulk_operation_update_one_with_opts
import mongkn.cinterop.mongoc_client_get_collection
import mongkn.cinterop.mongoc_collection_aggregate
import mongkn.cinterop.mongoc_collection_count_documents
import mongkn.cinterop.mongoc_collection_create_bulk_operation_with_opts
import mongkn.cinterop.mongoc_collection_create_indexes_with_opts
import mongkn.cinterop.mongoc_collection_delete_many
import mongkn.cinterop.mongoc_collection_delete_one
import mongkn.cinterop.mongoc_collection_destroy
import mongkn.cinterop.mongoc_collection_drop_index_with_opts
import mongkn.cinterop.mongoc_collection_drop_with_opts
import mongkn.cinterop.mongoc_collection_estimated_document_count
import mongkn.cinterop.mongoc_collection_find_indexes_with_opts
import mongkn.cinterop.mongoc_collection_find_with_opts
import mongkn.cinterop.mongoc_collection_insert_many
import mongkn.cinterop.mongoc_collection_insert_one
import mongkn.cinterop.mongoc_collection_keys_to_index_string
import mongkn.cinterop.mongoc_collection_read_command_with_opts
import mongkn.cinterop.mongoc_collection_read_write_command_with_opts
import mongkn.cinterop.mongoc_collection_rename_with_opts
import mongkn.cinterop.mongoc_collection_replace_one
import mongkn.cinterop.mongoc_collection_t
import mongkn.cinterop.mongoc_collection_update_many
import mongkn.cinterop.mongoc_collection_update_one
import mongkn.cinterop.mongoc_collection_watch
import mongkn.cinterop.mongoc_cursor_destroy
import mongkn.cinterop.mongoc_cursor_error
import mongkn.cinterop.mongoc_cursor_next
import mongkn.cinterop.mongoc_index_model_destroy
import mongkn.cinterop.mongoc_index_model_new
import mongkn.cinterop.mongoc_index_model_t
import ru.workinprogress.mongkn.bson.BsonArray
import ru.workinprogress.mongkn.bson.BsonBoolean
import ru.workinprogress.mongkn.bson.BsonDocument
import ru.workinprogress.mongkn.bson.BsonInt32
import ru.workinprogress.mongkn.bson.BsonInt64
import ru.workinprogress.mongkn.bson.BsonObjectId
import ru.workinprogress.mongkn.bson.BsonString
import ru.workinprogress.mongkn.bson.BsonValue
import ru.workinprogress.mongkn.bson.Document
import ru.workinprogress.mongkn.bson.toDocument
import ru.workinprogress.mongkn.bson.toNativeBson

/**
 * Реализация операций коллекции — весь cinterop живёт здесь.
 *
 * Публичный `MongoCollection` **генерируется** (`:mongkn-codegen`): его форма снимается
 * с официального драйвера, а тела делегируют сюда. Разделение намеренное — генератор отвечает
 * за поверхность API, а опасный код с указателями остаётся рукописным и под тестами
 * (решение Р5, требование расширяемости из Р7).
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
@OptIn(ExperimentalForeignApi::class, DelicateCoroutinesApi::class)
internal object CollectionOps {
    suspend fun insertOne(
        client: Target,
        databaseName: String,
        name: String,
        document: Document,
        opts: Document,
    ): InsertOneResult =
        execute(client, databaseName, name) { collection ->
            // `_id` берём из документа, который сами и отправили, а не из ответа драйвера.
            // Причина не в стиле: libmongoc 1.26 кладёт в reply только `insertedCount`, тогда как
            // 2.1.1 добавляет `insertedId` (ресёрч §1.19). Опираться на ответ значило бы работать
            // на одной ветке драйвера и падать на другой.
            val prepared = withGeneratedId(document)
            withBson(prepared) { payload ->
                withBson(opts) { options ->
                    withReply { reply, error ->
                        if (!mongoc_collection_insert_one(collection, payload, options, reply, error)) fail(error)
                        InsertOneResult(prepared.required("_id"))
                    }
                }
            }
        }

    suspend fun insertMany(
        client: Target,
        databaseName: String,
        name: String,
        documents: List<Document>,
        ordered: Boolean,
        extraOpts: Document,
    ): InsertManyResult =
        execute(client, databaseName, name) { collection ->
            require(documents.isNotEmpty()) { "insertMany: список документов пуст" }
            // `_id` проставляем на клиенте, как это делают все официальные драйверы.
            // Без этого вернуть insertedIds невозможно: reply от mongoc_collection_insert_many
            // их не содержит, в отличие от insert_one. Обнаружено spec-тестом
            // «InsertMany with non-existing documents» (M-30).
            val prepared = documents.map { withGeneratedId(it) }
            withBsonArray(prepared) { payload ->
                // `ordered: false` — продолжать после ошибки. Опции передаются документом, как их
                // и ждёт mongoc; по умолчанию драйвер считает вставку упорядоченной.
                withBson(withExtra("ordered" to BsonBoolean(ordered), extra = extraOpts)) { opts ->
                    withReply { reply, error ->
                        val ok =
                            mongoc_collection_insert_many(
                                collection,
                                payload,
                                prepared.size.convert(),
                                opts,
                                reply,
                                error,
                            )
                        if (!ok) fail(error)
                        InsertManyResult(
                            insertedCount = reply.toDocument().count("insertedCount"),
                            insertedIds = prepared.map { it.required("_id") },
                        )
                    }
                }
            }
        }

    /**
     * Возвращает документ с гарантированным `_id`: свой, если он уже есть, иначе новый ObjectId.
     *
     * `_id` ставится **первым полем** — так его кладут официальные драйверы, и так он выглядит
     * в ответе сервера, что важно для сравнения документов целиком.
     */
    private fun MemScope.withGeneratedId(document: Document): Document =
        if ("_id" in document) {
            document
        } else {
            val oid = alloc<bson_oid_t>()
            bson_oid_init(oid.ptr, null)
            val bytes = oid.ptr.reinterpret<ByteVar>().readBytes(BsonObjectId.SIZE)
            BsonDocument(listOf<Pair<String, BsonValue>>("_id" to BsonObjectId(bytes)) + document.entries)
        }

    suspend fun updateOne(
        client: Target,
        databaseName: String,
        name: String,
        filter: Document,
        update: Document,
        upsert: Boolean,
        extraOpts: Document,
    ): UpdateResult =
        execute(client, databaseName, name) { collection ->
            withBson(filter) { selector ->
                withBson(update) { modification ->
                    withBson(withExtra("upsert" to BsonBoolean(upsert), extra = extraOpts)) { opts ->
                        withReply { reply, error ->
                            val ok =
                                mongoc_collection_update_one(
                                    collection,
                                    selector,
                                    modification,
                                    opts,
                                    reply,
                                    error,
                                )
                            if (!ok) fail(error)
                            val answer = reply.toDocument()
                            UpdateResult(
                                matchedCount = answer.count("matchedCount"),
                                modifiedCount = answer.count("modifiedCount"),
                                upsertedId = answer["upsertedId"],
                            )
                        }
                    }
                }
            }
        }

    suspend fun deleteOne(
        client: Target,
        databaseName: String,
        name: String,
        filter: Document,
        opts: Document,
    ): DeleteResult =
        execute(client, databaseName, name) { collection ->
            withBson(filter) { selector ->
                withBson(opts) { nativeOpts ->
                    withReply { reply, error ->
                        if (!mongoc_collection_delete_one(collection, selector, nativeOpts, reply, error)) fail(error)
                        DeleteResult(reply.toDocument().count("deletedCount"))
                    }
                }
            }
        }

    suspend fun updateMany(
        client: Target,
        databaseName: String,
        name: String,
        filter: Document,
        update: Document,
        upsert: Boolean,
        extraOpts: Document,
    ): UpdateResult =
        execute(client, databaseName, name) { collection ->
            withBson(filter) { selector ->
                withBson(update) { modification ->
                    withBson(withExtra("upsert" to BsonBoolean(upsert), extra = extraOpts)) { opts ->
                        withReply { reply, error ->
                            val ok =
                                mongoc_collection_update_many(
                                    collection,
                                    selector,
                                    modification,
                                    opts,
                                    reply,
                                    error,
                                )
                            if (!ok) fail(error)
                            reply.toDocument().toUpdateResult()
                        }
                    }
                }
            }
        }

    /**
     * Заменяет документ целиком.
     *
     * От [updateOne] отличается тем, что второй аргумент — не операторы обновления, а новое
     * содержимое. Документ с `$`-ключами сервер здесь отвергнет, и это его правило, а не наше:
     * ошибку отдаём как есть, не подменяя своей.
     */
    suspend fun replaceOne(
        client: Target,
        databaseName: String,
        name: String,
        filter: Document,
        replacement: Document,
        upsert: Boolean,
        extraOpts: Document,
    ): UpdateResult =
        execute(client, databaseName, name) { collection ->
            withBson(filter) { selector ->
                withBson(replacement) { document ->
                    withBson(withExtra("upsert" to BsonBoolean(upsert), extra = extraOpts)) { opts ->
                        withReply { reply, error ->
                            val ok =
                                mongoc_collection_replace_one(
                                    collection,
                                    selector,
                                    document,
                                    opts,
                                    reply,
                                    error,
                                )
                            if (!ok) fail(error)
                            reply.toDocument().toUpdateResult()
                        }
                    }
                }
            }
        }

    suspend fun deleteMany(
        client: Target,
        databaseName: String,
        name: String,
        filter: Document,
        opts: Document,
    ): DeleteResult =
        execute(client, databaseName, name) { collection ->
            withBson(filter) { selector ->
                withBson(opts) { nativeOpts ->
                    withReply { reply, error ->
                        if (!mongoc_collection_delete_many(collection, selector, nativeOpts, reply, error)) fail(error)
                        DeleteResult(reply.toDocument().count("deletedCount"))
                    }
                }
            }
        }

    /**
     * Оценка числа документов по метаданным коллекции.
     *
     * Не то же самое, что [countDocuments]: точного подсчёта не делает, зато не сканирует.
     * Официальный драйвер держит обе операции по той же причине.
     */
    suspend fun estimatedDocumentCount(
        client: Target,
        databaseName: String,
        name: String,
    ): Long =
        execute(client, databaseName, name) { collection ->
            withReply { reply, error ->
                val count = mongoc_collection_estimated_document_count(collection, null, null, reply, error)
                if (count < 0) fail(error)
                count
            }
        }

    suspend fun drop(
        client: Target,
        databaseName: String,
        name: String,
    ) {
        execute(client, databaseName, name) { collection ->
            val error = alloc<bson_error_t>()
            if (!mongoc_collection_drop_with_opts(collection, null, error.ptr)) fail(error.ptr)
        }
    }

    suspend fun rename(
        client: Target,
        databaseName: String,
        name: String,
        newName: String,
        dropTarget: Boolean,
    ) {
        execute(client, databaseName, name) { collection ->
            val error = alloc<bson_error_t>()
            val ok =
                mongoc_collection_rename_with_opts(
                    collection,
                    databaseName,
                    newName,
                    dropTarget,
                    null,
                    error.ptr,
                )
            if (!ok) fail(error.ptr)
        }
    }

    /**
     * Общая реализация `findOneAnd*`.
     *
     * Идёт командой `findAndModify`, а не через `mongoc_find_and_modify_opts_t`: структура опций
     * потребовала бы отдельного набора сеттеров под каждый флаг, тогда как команда — это тот же
     * документ, который мы и так умеем собирать.
     *
     * Возвращает `null`, когда под фильтр ничего не подошло: сервер кладёт в `value` BSON-null,
     * и отличить «не нашли» от «нашли пустой документ» можно только так.
     */
    private suspend fun findAndModify(
        client: Target,
        databaseName: String,
        name: String,
        body: List<Pair<String, BsonValue>>,
        opts: Document,
    ): Document? =
        execute(client, databaseName, name) { collection ->
            val command = BsonDocument(listOf<Pair<String, BsonValue>>("findAndModify" to BsonString(name)) + body)
            withBson(command) { payload ->
                withBson(opts) { options ->
                    withReply { reply, error ->
                        val ok =
                            mongoc_collection_read_write_command_with_opts(
                                collection,
                                payload,
                                null,
                                options,
                                reply,
                                error,
                            )
                        if (!ok) fail(error)
                        reply.toDocument()["value"] as? BsonDocument
                    }
                }
            }
        }

    suspend fun findOneAndUpdate(
        client: Target,
        databaseName: String,
        name: String,
        filter: Document,
        update: Document,
        returnDocument: ReturnDocument,
        upsert: Boolean,
        sort: Document?,
        projection: Document?,
        opts: Document,
    ): Document? =
        findAndModify(
            client,
            databaseName,
            name,
            listOfNotNull(
                "query" to filter,
                "update" to update,
                "new" to BsonBoolean(returnDocument == ReturnDocument.AFTER),
                "upsert" to BsonBoolean(upsert),
                sort?.let { "sort" to it },
                // Команда зовёт проекцию `fields`, а не `projection`: имя историческое.
                projection?.let { "fields" to it },
            ),
            opts,
        )

    suspend fun findOneAndReplace(
        client: Target,
        databaseName: String,
        name: String,
        filter: Document,
        replacement: Document,
        returnDocument: ReturnDocument,
        upsert: Boolean,
        sort: Document?,
        projection: Document?,
        opts: Document,
    ): Document? =
        findAndModify(
            client,
            databaseName,
            name,
            listOfNotNull(
                "query" to filter,
                "update" to replacement,
                "new" to BsonBoolean(returnDocument == ReturnDocument.AFTER),
                "upsert" to BsonBoolean(upsert),
                sort?.let { "sort" to it },
                // Команда зовёт проекцию `fields`, а не `projection`: имя историческое.
                projection?.let { "fields" to it },
            ),
            opts,
        )

    suspend fun findOneAndDelete(
        client: Target,
        databaseName: String,
        name: String,
        filter: Document,
        sort: Document?,
        projection: Document?,
        opts: Document,
    ): Document? =
        findAndModify(
            client,
            databaseName,
            name,
            listOfNotNull(
                "query" to filter,
                "remove" to BsonBoolean(true),
                sort?.let { "sort" to it },
                projection?.let { "fields" to it },
            ),
            opts,
        )

    /**
     * Уникальные значения поля.
     *
     * Отдельной функции у libmongoc нет — только команда `distinct`. Поэтому здесь первый
     * в проекте вызов через `read_command_with_opts`; на нём же потом встанет `runCommand` (M-51).
     */
    suspend fun distinct(
        client: Target,
        databaseName: String,
        name: String,
        field: String,
        filter: Document,
    ): List<BsonValue> =
        execute(client, databaseName, name) { collection ->
            val command =
                BsonDocument(
                    "distinct" to BsonString(name),
                    "key" to BsonString(field),
                    "query" to filter,
                )
            withBson(command) { payload ->
                withReply { reply, error ->
                    if (!mongoc_collection_read_command_with_opts(collection, payload, null, null, reply, error)) {
                        fail(error)
                    }
                    (reply.toDocument()["values"] as? BsonArray)?.values.orEmpty()
                }
            }
        }

    suspend fun countDocuments(
        client: Target,
        databaseName: String,
        name: String,
        filter: Document,
        opts: Document,
    ): Long =
        execute(client, databaseName, name) { collection ->
            withBson(filter) { selector ->
                withBson(opts) { nativeOpts ->
                    val error = alloc<bson_error_t>()
                    // Единственная операция, отдающая результат возвращаемым значением,
                    // а не в reply. Признак ошибки — отрицательное число, а не false.
                    val count =
                        mongoc_collection_count_documents(
                            collection,
                            selector,
                            nativeOpts,
                            null,
                            null,
                            error.ptr,
                        )
                    if (count < 0) fail(error.ptr)
                    count
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
     *
     * Через [execute] не идёт: тот `suspend`, а `find` обязан вернуть холодный `Flow`, внутри
     * которого происходит эмиссия.
     *
     * **Клиент занят всё время сбора потока** — курсор принадлежит клиенту, и вернуть того в пул
     * раньше нельзя. Это ограничение libmongoc, а не наш выбор. Смягчено тем, что разрешение
     * берётся на семафоре: превышение числа одновременных курсоров теперь приостанавливает
     * корутину (отменяемо, `withTimeout` работает), а не вешает поток в C навсегда (§1.12).
     */
    fun find(
        client: Target,
        databaseName: String,
        name: String,
        filter: Document,
        opts: Document,
    ): Flow<Document> =
        flow {
            client.withPermit {
                client.useClient { handle ->
                    val collection =
                        mongoc_client_get_collection(handle, databaseName, name)
                            ?: error("mongoc_client_get_collection вернул NULL")
                    try {
                        val nativeFilter = filter.toNativeBson()
                        val nativeOpts = opts.toNativeBson()
                        try {
                            val cursor =
                                mongoc_collection_find_with_opts(collection, nativeFilter, nativeOpts, null)
                                    ?: error("mongoc_collection_find_with_opts вернул NULL")
                            drainCursor(cursor)
                        } finally {
                            bson_destroy(nativeOpts)
                            bson_destroy(nativeFilter)
                        }
                    } finally {
                        mongoc_collection_destroy(collection)
                    }
                }
            }
        }.flowOn(client.dispatcher)

    /**
     * Агрегационный конвейер над коллекцией.
     *
     * Устроена как [find] — тот же курсор, то же удержание клиента и разрешения на всё время
     * сбора, — и отличается только тем, что уходит на сервер: вместо фильтра конвейер стадий.
     *
     * Конвейер передаётся документом — см. [pipelineDocument].
     *
     * `MONGOC_QUERY_NONE`: флаги здесь — наследие протокола OP_QUERY, а всё, что через них
     * задавалось, сегодня задаётся ключами `opts`.
     */
    fun aggregate(
        client: Target,
        databaseName: String,
        name: String,
        pipeline: List<Document>,
        opts: Document,
    ): Flow<Document> =
        flow {
            client.withPermit {
                client.useClient { handle ->
                    val collection =
                        mongoc_client_get_collection(handle, databaseName, name)
                            ?: error("mongoc_client_get_collection вернул NULL")
                    try {
                        val nativePipeline = pipelineDocument(pipeline).toNativeBson()
                        val nativeOpts = opts.toNativeBson()
                        try {
                            val cursor =
                                mongoc_collection_aggregate(
                                    collection,
                                    MONGOC_QUERY_NONE,
                                    nativePipeline,
                                    nativeOpts,
                                    null,
                                ) ?: error("mongoc_collection_aggregate вернул NULL")
                            drainCursor(cursor)
                        } finally {
                            bson_destroy(nativeOpts)
                            bson_destroy(nativePipeline)
                        }
                    } finally {
                        mongoc_collection_destroy(collection)
                    }
                }
            }
        }.flowOn(client.dispatcher)

    /**
     * Создаёт индексы и возвращает их имена.
     *
     * Идёт через `mongoc_index_model_t`, а не командой `createIndexes` через [DatabaseOps.runCommand],
     * — вопреки записи в бэклоге, что выделенных функций у libmongoc нет. Они есть, и на обеих
     * ветках драйвера (1.26 и 2.1.1) сигнатуры совпадают дословно.
     *
     * Имя индекса считается **на нашей стороне**: ответ сервера его не содержит, а официальный
     * драйвер имя возвращает. Правило то же, что у сервера, — либо явное `name` из опций, либо
     * склейка ключей, которую собирает сам libmongoc ([defaultIndexName]).
     */
    suspend fun createIndexes(
        client: Target,
        databaseName: String,
        name: String,
        models: List<IndexModel>,
        opts: Document,
    ): List<String> =
        execute(client, databaseName, name) { collection ->
            // Владение тройное: bson ключей, bson опций и сама модель. Модель копирует документы
            // внутрь себя, но освобождать всё равно надо всё, и в обратном порядке.
            val keyDocuments = models.map { it.keys.toNativeBson() }
            val optionDocuments = models.map { it.options.toNativeBson() }
            val handles = mutableListOf<CPointer<mongoc_index_model_t>>()
            try {
                for (index in models.indices) {
                    handles +=
                        mongoc_index_model_new(keyDocuments[index], optionDocuments[index])
                            ?: error("mongoc_index_model_new вернул NULL")
                }
                val array = allocArray<CPointerVar<mongoc_index_model_t>>(handles.size)
                handles.forEachIndexed { index, handle -> array[index] = handle }
                withBson(opts) { options ->
                    withReply { reply, error ->
                        val ok =
                            mongoc_collection_create_indexes_with_opts(
                                collection,
                                array,
                                handles.size.convert(),
                                options,
                                reply,
                                error,
                            )
                        if (!ok) fail(error)
                    }
                }
                models.map { model ->
                    (model.options["name"] as? BsonString)?.value ?: defaultIndexName(model.keys)
                }
            } finally {
                handles.forEach(::mongoc_index_model_destroy)
                optionDocuments.forEach(::bson_destroy)
                keyDocuments.forEach(::bson_destroy)
            }
        }

    /** Удаляет индекс по имени. `*` удаляет все, кроме обязательного индекса по `_id`. */
    suspend fun dropIndex(
        client: Target,
        databaseName: String,
        name: String,
        indexName: String,
        opts: Document,
    ) {
        execute(client, databaseName, name) { collection ->
            withBson(opts) { options ->
                val error = alloc<bson_error_t>()
                if (!mongoc_collection_drop_index_with_opts(collection, indexName, options, error.ptr)) {
                    fail(error.ptr)
                }
            }
        }
    }

    /**
     * Перечисляет индексы коллекции.
     *
     * Курсор, поэтому устроено как [find] и [aggregate], а не как обычная операция.
     */
    fun listIndexes(
        client: Target,
        databaseName: String,
        name: String,
        opts: Document,
    ): Flow<Document> =
        flow {
            client.withPermit {
                client.useClient { handle ->
                    val collection =
                        mongoc_client_get_collection(handle, databaseName, name)
                            ?: error("mongoc_client_get_collection вернул NULL")
                    try {
                        val nativeOpts = opts.toNativeBson()
                        try {
                            val cursor =
                                mongoc_collection_find_indexes_with_opts(collection, nativeOpts)
                                    ?: error("mongoc_collection_find_indexes_with_opts вернул NULL")
                            drainCursor(cursor)
                        } finally {
                            bson_destroy(nativeOpts)
                        }
                    } finally {
                        mongoc_collection_destroy(collection)
                    }
                }
            }
        }.flowOn(client.dispatcher)

    /**
     * Имя, которое сервер даст индексу без явного `name`.
     *
     * Собирается той же функцией libmongoc, которой пользуется и сам драйвер, — повторять
     * правило склейки (`поле_1_другое_-1`) руками значило бы завести второй источник истины.
     * Строка приходит во владение вызывающему, освобождается `bson_free`.
     */
    fun defaultIndexName(keys: Document): String {
        val native = keys.toNativeBson()
        try {
            val text = mongoc_collection_keys_to_index_string(native) ?: error("не удалось собрать имя индекса")
            try {
                return text.toKString()
            } finally {
                bson_free(text)
            }
        } finally {
            bson_destroy(native)
        }
    }

    /**
     * Пакетная запись: несколько разнородных операций одним обращением к серверу.
     *
     * Устроена иначе всех прочих операций. У libmongoc это не функция, а объект
     * `mongoc_bulk_operation_t`, который сначала **набирают** вызовами `*_with_opts`, и только
     * потом исполняют. Отсюда две особенности:
     *
     * * набирающие функции возвращают `bool` и заполняют `error` — они проверяют операцию
     *   на нашей стороне (например, что документ обновления состоит из операторов) **до**
     *   похода на сервер. Игнорировать их результат нельзя: ошибка вылезла бы позже и не там;
     * * `mongoc_bulk_operation_execute` возвращает не `bool`, а `uint32_t` — идентификатор
     *   сервера, на котором операция выполнена. Признак неуспеха здесь **ноль**, и перепутать
     *   его с `false` легко.
     *
     * `_id` для вставок генерируется на нашей стороне, как в [insertOne] и [insertMany]
     * (решение Р3): в ответе на bulk сервер их не возвращает вовсе.
     */
    suspend fun bulkWrite(
        client: Target,
        databaseName: String,
        name: String,
        requests: List<WriteModel<Document>>,
        ordered: Boolean,
        opts: Document,
    ): BulkWriteResult =
        execute(client, databaseName, name) { collection ->
            val bulk =
                withBson(withExtra("ordered" to BsonBoolean(ordered), extra = opts)) { options ->
                    mongoc_collection_create_bulk_operation_with_opts(collection, options)
                } ?: error("mongoc_collection_create_bulk_operation_with_opts вернул NULL")
            try {
                val insertedIds = mutableMapOf<Int, BsonValue>()
                requests.forEachIndexed { index, request ->
                    if (request is InsertOneModel<Document>) {
                        val prepared = withGeneratedId(request.document)
                        insertedIds[index] = prepared.required("_id")
                        stage(bulk, InsertOneModel(prepared))
                    } else {
                        stage(bulk, request)
                    }
                }
                withReply { reply, error ->
                    // Ноль, а не false: execute отдаёт идентификатор сервера.
                    if (mongoc_bulk_operation_execute(bulk, reply, error) == 0u) fail(error)
                    val document = reply.toDocument()
                    BulkWriteResult(
                        insertedCount = document.count("nInserted"),
                        matchedCount = document.count("nMatched"),
                        modifiedCount = document.count("nModified"),
                        deletedCount = document.count("nRemoved"),
                        upsertedCount = document.count("nUpserted"),
                        insertedIds = insertedIds,
                        upsertedIds = document.upsertedIds(),
                    )
                }
            } finally {
                mongoc_bulk_operation_destroy(bulk)
            }
        }

    /**
     * Добавляет одну операцию в набор.
     *
     * Каждая ветка проверяет возвращённый `bool`: набирающие функции ловят ошибки формы
     * до обращения к серверу.
     */
    private fun MemScope.stage(
        bulk: CPointer<mongoc_bulk_operation_t>,
        request: WriteModel<Document>,
    ) {
        val error = alloc<bson_error_t>()
        val ok =
            when (request) {
                is InsertOneModel<Document> -> {
                    withBson(request.document) { document ->
                        mongoc_bulk_operation_insert_with_opts(bulk, document, null, error.ptr)
                    }
                }

                is UpdateOneModel -> {
                    withBson(request.filter) { filter ->
                        withBson(request.update) { update ->
                            withBson(upsertOpts(request.upsert, request.options)) { options ->
                                mongoc_bulk_operation_update_one_with_opts(bulk, filter, update, options, error.ptr)
                            }
                        }
                    }
                }

                is UpdateManyModel -> {
                    withBson(request.filter) { filter ->
                        withBson(request.update) { update ->
                            withBson(upsertOpts(request.upsert, request.options)) { options ->
                                mongoc_bulk_operation_update_many_with_opts(bulk, filter, update, options, error.ptr)
                            }
                        }
                    }
                }

                is ReplaceOneModel<Document> -> {
                    withBson(request.filter) { filter ->
                        withBson(request.replacement) { replacement ->
                            withBson(upsertOpts(request.upsert, request.options)) { options ->
                                mongoc_bulk_operation_replace_one_with_opts(
                                    bulk,
                                    filter,
                                    replacement,
                                    options,
                                    error.ptr,
                                )
                            }
                        }
                    }
                }

                is DeleteOneModel -> {
                    withBson(request.filter) { filter ->
                        withBson(request.options) { options ->
                            mongoc_bulk_operation_remove_one_with_opts(bulk, filter, options, error.ptr)
                        }
                    }
                }

                is DeleteManyModel -> {
                    withBson(request.filter) { filter ->
                        withBson(request.options) { options ->
                            mongoc_bulk_operation_remove_many_with_opts(bulk, filter, options, error.ptr)
                        }
                    }
                }
            }
        if (!ok) fail(error.ptr)
    }

    private fun upsertOpts(
        upsert: Boolean,
        options: Document,
    ): Document = withExtra("upsert" to BsonBoolean(upsert), extra = options)

    /**
     * Читает `upserted` из ответа: массив документов `{index, _id}`.
     *
     * `index` — позиция операции в списке запросов, поэтому именно он и становится ключом.
     */
    private fun BsonDocument.upsertedIds(): Map<Int, BsonValue> =
        (this["upserted"] as? BsonArray)
            ?.values
            .orEmpty()
            .filterIsInstance<BsonDocument>()
            .mapNotNull { entry ->
                val index = (entry["index"] as? BsonInt32)?.value ?: (entry["index"] as? BsonInt64)?.value?.toInt()
                val id = entry["_id"]
                if (index == null || id == null) null else index to id
            }.toMap()

    /**
     * Подписка на изменения коллекции.
     *
     * Устроена не как [find] и [aggregate], хотя тоже отдаёт поток. Отличий два, и оба вынуждены
     * тем, что подписка бесконечна:
     *
     * * **`channelFlow`, а не `flow`.** События уходят наружу с отдельного потока, а `flow`
     *   запрещает эмиссию из чужого контекста. `channelFlow` это разрешает — ровно его случай;
     * * **свой поток на подписку**, а не общий диспетчер клиента. Блокирующий вызов здесь длится
     *   всё время жизни подписки, поэтому на общем пуле четыре `watch` остановили бы все
     *   остальные операции: их столько же, сколько [MongoClient.DEFAULT_IO_THREADS].
     *   Поток создаётся на подписку
     *   и закрывается вместе с ней.
     *
     * Разрешение семафора берётся как обычно: клиент занят, и пул должен об этом знать.
     */
    fun watch(
        client: Target,
        databaseName: String,
        name: String,
        pipeline: List<Document>,
        opts: Document,
    ): Flow<Document> =
        channelFlow {
            val dispatcher = newSingleThreadContext("mongkn-watch")
            try {
                withContext(dispatcher) {
                    client.withPermit {
                        client.useClient { handle ->
                            val collection =
                                mongoc_client_get_collection(handle, databaseName, name)
                                    ?: error("mongoc_client_get_collection вернул NULL")
                            try {
                                withChangeStream(pipeline, opts) { nativePipeline, nativeOpts ->
                                    mongoc_collection_watch(collection, nativePipeline, nativeOpts)
                                }
                            } finally {
                                mongoc_collection_destroy(collection)
                            }
                        }
                    }
                }
            } finally {
                dispatcher.close()
            }
        }

    // --- обвязка, общая для всех операций ---------------------------------------------------

    /**
     * Общий каркас операции: проверка клиента, уход на пул потоков, взятие клиента из пула,
     * получение дескриптора коллекции и его освобождение.
     *
     * Ради этого каркаса операции и собраны в одном объекте: без него каждая новая операция
     * тащила бы за собой те же четыре вложенных `try/finally`.
     */
    private suspend inline fun <T> execute(
        client: Target,
        databaseName: String,
        name: String,
        crossinline body: MemScope.(CPointer<mongoc_collection_t>) -> T,
    ): T =
        client.withClient { handle ->
            // Переключение на пул потоков и ожидание свободного клиента — внутри withClient.
            val collection =
                mongoc_client_get_collection(handle, databaseName, name)
                    ?: error("mongoc_client_get_collection вернул NULL")
            try {
                memScoped { body(collection) }
            } finally {
                mongoc_collection_destroy(collection)
            }
        }

    /** Собирает `bson_t`, отдаёт в [body] и уничтожает при любом исходе. */
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

    /** То же для списка: `mongoc_collection_insert_many` ждёт `const bson_t **`. */
    private inline fun <T> MemScope.withBsonArray(
        documents: List<Document>,
        body: (CPointer<CPointerVar<bson_t>>) -> T,
    ): T {
        val natives = documents.map { it.toNativeBson() }
        try {
            val array = allocArray<CPointerVar<bson_t>>(natives.size)
            natives.forEachIndexed { index, pointer -> array[index] = pointer }
            return body(array)
        } finally {
            natives.forEach { bson_destroy(it) }
        }
    }

    /**
     * Выделяет `reply` и `bson_error_t` и обязательно уничтожает `reply`.
     *
     * `reply` инициализируется драйвером и при неуспехе тоже — проверено прогоном на дубликате
     * ключа (ресёрч §1.3), — поэтому `bson_destroy` стоит в `finally` без оглядки на результат.
     */
    private inline fun <T> MemScope.withReply(body: (reply: CPointer<bson_t>, error: CPointer<bson_error_t>) -> T): T {
        val reply = alloc<bson_t>()
        val error = alloc<bson_error_t>()
        try {
            return body(reply.ptr, error.ptr)
        } finally {
            bson_destroy(reply.ptr)
        }
    }

    private fun fail(error: CPointer<bson_error_t>): Nothing {
        val value = error.pointed
        throw MongoException(value.domain, value.code, value.message.toKString())
    }

    /**
     * Читает счётчик из ответа сервера.
     *
     * Тип числа в `reply` не фиксирован: сервер волен вернуть и int32, и int64, — поэтому
     * принимаем оба, а не гадаем.
     */
    private fun BsonDocument.count(key: String): Long =
        when (val value = this[key]) {
            is BsonInt32 -> value.value.toLong()
            is BsonInt64 -> value.value
            null -> 0L
            else -> error("в ответе сервера поле \"$key\" не число: $value")
        }

    /**
     * Собирает документ опций: своя опция операции плюс то, что пришло снаружи.
     *
     * Снаружи приходят настройки уровня коллекции (`withWriteConcern` и соседи) и опции самого
     * вызова. Порядок такой, что внешнее идёт последним, — но дубли ключей mongoc не ждёт,
     * поэтому собственная опция выбрасывается, если её задали и снаружи.
     */
    private fun withExtra(
        own: Pair<String, BsonValue>,
        extra: Document,
    ): Document = if (own.first in extra) extra else BsonDocument(listOf(own) + extra.entries)

    /** Ответы `update_one`, `update_many` и `replace_one` устроены одинаково. */
    private fun BsonDocument.toUpdateResult(): UpdateResult =
        UpdateResult(
            matchedCount = count("matchedCount"),
            modifiedCount = count("modifiedCount"),
            upsertedId = this["upsertedId"],
        )

    private fun BsonDocument.required(key: String): BsonValue =
        this[key] ?: error("в ответе сервера нет поля \"$key\": $this")
}
