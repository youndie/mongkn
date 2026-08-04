package ru.workinprogress.mongkn

import ru.workinprogress.mongkn.bson.BsonArray
import ru.workinprogress.mongkn.bson.BsonBoolean
import ru.workinprogress.mongkn.bson.BsonDocument
import ru.workinprogress.mongkn.bson.BsonInt32
import ru.workinprogress.mongkn.bson.BsonInt64
import ru.workinprogress.mongkn.bson.BsonObjectId
import ru.workinprogress.mongkn.bson.BsonValue
import ru.workinprogress.mongkn.bson.Document
import ru.workinprogress.mongkn.bson.toDocument
import ru.workinprogress.mongkn.bson.toNativeBson
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import mongkn.cinterop.bson_destroy
import mongkn.cinterop.bson_error_t
import mongkn.cinterop.bson_oid_init
import mongkn.cinterop.bson_oid_t
import mongkn.cinterop.bson_t
import mongkn.cinterop.mongoc_client_get_collection
import mongkn.cinterop.mongoc_collection_count_documents
import mongkn.cinterop.mongoc_collection_delete_one
import mongkn.cinterop.mongoc_collection_destroy
import mongkn.cinterop.mongoc_collection_find_with_opts
import mongkn.cinterop.mongoc_collection_insert_many
import mongkn.cinterop.mongoc_collection_insert_one
import mongkn.cinterop.mongoc_collection_t
import mongkn.cinterop.mongoc_collection_update_one
import mongkn.cinterop.mongoc_cursor_destroy
import mongkn.cinterop.mongoc_cursor_error
import mongkn.cinterop.mongoc_cursor_next

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
@OptIn(ExperimentalForeignApi::class)
internal object CollectionOps {

    suspend fun insertOne(
        client: MongoClient,
        databaseName: String,
        name: String,
        document: Document,
    ): InsertOneResult = execute(client, databaseName, name) { collection ->
        // `_id` берём из документа, который сами и отправили, а не из ответа драйвера.
        // Причина не в стиле: libmongoc 1.26 кладёт в reply только `insertedCount`, тогда как
        // 2.1.1 добавляет `insertedId` (ресёрч §1.19). Опираться на ответ значило бы работать
        // на одной ветке драйвера и падать на другой.
        val prepared = withGeneratedId(document)
        withBson(prepared) { payload ->
            withReply { reply, error ->
                if (!mongoc_collection_insert_one(collection, payload, null, reply, error)) fail(error)
                InsertOneResult(prepared.required("_id"))
            }
        }
    }

    suspend fun insertMany(
        client: MongoClient,
        databaseName: String,
        name: String,
        documents: List<Document>,
        ordered: Boolean,
    ): InsertManyResult = execute(client, databaseName, name) { collection ->
        require(documents.isNotEmpty()) { "insertMany: список документов пуст" }
        // `_id` проставляем на клиенте, как это делают все официальные драйверы.
        // Без этого вернуть insertedIds невозможно: reply от mongoc_collection_insert_many
        // их не содержит, в отличие от insert_one. Обнаружено spec-тестом
        // «InsertMany with non-existing documents» (M-30).
        val prepared = documents.map { withGeneratedId(it) }
        withBsonArray(prepared) { payload ->
            // `ordered: false` — продолжать после ошибки. Опции передаются документом, как их
            // и ждёт mongoc; по умолчанию драйвер считает вставку упорядоченной.
            withBson(BsonDocument("ordered" to BsonBoolean(ordered))) { opts ->
            withReply { reply, error ->
                val ok = mongoc_collection_insert_many(
                    collection, payload, prepared.size.convert(), opts, reply, error,
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
        client: MongoClient,
        databaseName: String,
        name: String,
        filter: Document,
        update: Document,
        upsert: Boolean,
    ): UpdateResult = execute(client, databaseName, name) { collection ->
        withBson(filter) { selector ->
            withBson(update) { modification ->
                withBson(BsonDocument("upsert" to BsonBoolean(upsert))) { opts ->
                withReply { reply, error ->
                    val ok = mongoc_collection_update_one(
                        collection, selector, modification, opts, reply, error,
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
        client: MongoClient,
        databaseName: String,
        name: String,
        filter: Document,
    ): DeleteResult = execute(client, databaseName, name) { collection ->
        withBson(filter) { selector ->
            withReply { reply, error ->
                if (!mongoc_collection_delete_one(collection, selector, null, reply, error)) fail(error)
                DeleteResult(reply.toDocument().count("deletedCount"))
            }
        }
    }

    suspend fun countDocuments(
        client: MongoClient,
        databaseName: String,
        name: String,
        filter: Document,
    ): Long = execute(client, databaseName, name) { collection ->
        withBson(filter) { selector ->
            val error = alloc<bson_error_t>()
            // Единственная операция, отдающая результат возвращаемым значением, а не в reply.
            // Признак ошибки — отрицательное число, а не false.
            val count = mongoc_collection_count_documents(
                collection, selector, null, null, null, error.ptr,
            )
            if (count < 0) fail(error.ptr)
            count
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
        client: MongoClient,
        databaseName: String,
        name: String,
        filter: Document,
        opts: Document,
    ): Flow<Document> = flow {
        client.withPermit {
        client.useClient { handle ->
            val collection = mongoc_client_get_collection(handle, databaseName, name)
                ?: error("mongoc_client_get_collection вернул NULL")
            try {
                val nativeFilter = filter.toNativeBson()
                val nativeOpts = opts.toNativeBson()
                try {
                    val cursor = mongoc_collection_find_with_opts(collection, nativeFilter, nativeOpts, null)
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
                            if (mongoc_cursor_error(cursor, error.ptr)) fail(error.ptr)
                        }
                    } finally {
                        mongoc_cursor_destroy(cursor)
                    }
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

    // --- обвязка, общая для всех операций ---------------------------------------------------

    /**
     * Общий каркас операции: проверка клиента, уход на пул потоков, взятие клиента из пула,
     * получение дескриптора коллекции и его освобождение.
     *
     * Ради этого каркаса операции и собраны в одном объекте: без него каждая новая операция
     * тащила бы за собой те же четыре вложенных `try/finally`.
     */
    private suspend inline fun <T> execute(
        client: MongoClient,
        databaseName: String,
        name: String,
        crossinline body: MemScope.(CPointer<mongoc_collection_t>) -> T,
    ): T = client.withClient { handle ->
        // Переключение на пул потоков и ожидание свободного клиента — внутри withClient.
        val collection = mongoc_client_get_collection(handle, databaseName, name)
            ?: error("mongoc_client_get_collection вернул NULL")
        try {
            memScoped { body(collection) }
        } finally {
            mongoc_collection_destroy(collection)
        }
    }

    /** Собирает `bson_t`, отдаёт в [body] и уничтожает при любом исходе. */
    private inline fun <T> withBson(document: Document, body: (CPointer<bson_t>) -> T): T {
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
    private inline fun <T> MemScope.withReply(
        body: (reply: CPointer<bson_t>, error: CPointer<bson_error_t>) -> T,
    ): T {
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
    private fun BsonDocument.count(key: String): Long = when (val value = this[key]) {
        is BsonInt32 -> value.value.toLong()
        is BsonInt64 -> value.value
        null -> 0L
        else -> error("в ответе сервера поле \"$key\" не число: $value")
    }

    private fun BsonDocument.required(key: String): BsonValue =
        this[key] ?: error("в ответе сервера нет поля \"$key\": $this")
}
