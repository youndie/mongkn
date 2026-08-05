package ru.workinprogress.mongkn

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.FlowCollector
import mongkn.cinterop.bson_destroy
import mongkn.cinterop.bson_error_t
import mongkn.cinterop.bson_t
import mongkn.cinterop.mongoc_change_stream_destroy
import mongkn.cinterop.mongoc_change_stream_error_document
import mongkn.cinterop.mongoc_change_stream_next
import mongkn.cinterop.mongoc_change_stream_t
import mongkn.cinterop.mongoc_cursor_destroy
import mongkn.cinterop.mongoc_cursor_error
import mongkn.cinterop.mongoc_cursor_next
import mongkn.cinterop.mongoc_cursor_t
import ru.workinprogress.mongkn.bson.BsonArray
import ru.workinprogress.mongkn.bson.BsonDocument
import ru.workinprogress.mongkn.bson.BsonInt64
import ru.workinprogress.mongkn.bson.Document
import ru.workinprogress.mongkn.bson.toDocument
import ru.workinprogress.mongkn.bson.toNativeBson

/**
 * Вычерпывает курсор в поток и уничтожает его.
 *
 * Общий кусок для `find` и обеих агрегаций: у libmongoc все они возвращают один и тот же
 * `mongoc_cursor_t`, различаясь только тем, как курсор создан.
 *
 * Три вещи, ради которых это отдельная функция, а не три копии:
 *
 * * `mongoc_cursor_destroy` в `finally` — единственное, что закрывает курсор **на сервере**,
 *   и звать его надо при любом исходе, включая отмену сбора и исключение у потребителя;
 * * документ переводится в Kotlin **до** эмиссии: указатель от `mongoc_cursor_next` действителен
 *   только до следующего `next`, а между эмиссией и следующим витком работает потребитель;
 * * курсор заканчивается и по исчерпанию, и по ошибке — одинаково, `next` возвращает `false`.
 *   Различить их можно только `mongoc_cursor_error` после цикла.
 *
 * `inline`, потому что внутри вызывается [FlowCollector.emit].
 */
@OptIn(ExperimentalForeignApi::class)
internal suspend inline fun FlowCollector<Document>.drainCursor(cursor: CPointer<mongoc_cursor_t>) {
    try {
        memScoped {
            val current = allocPointerTo<bson_t>()
            while (mongoc_cursor_next(cursor, current.ptr)) {
                val document = current.value?.toDocument() ?: error("mongoc_cursor_next отдал NULL при true")
                emit(document)
            }
            val error = alloc<bson_error_t>()
            if (mongoc_cursor_error(cursor, error.ptr)) {
                val value = error.ptr.pointed
                throw MongoException(value.domain, value.code, value.message.toKString())
            }
        }
    } finally {
        mongoc_cursor_destroy(cursor)
    }
}

/**
 * Заворачивает стадии конвейера в документ `{"pipeline": [...]}`.
 *
 * libmongoc принимает и голый BSON-массив, но массив верхнего уровня у нас не выразим:
 * [Document] — это документ. Форму с ключом драйвер понимает наравне.
 */
internal fun pipelineDocument(stages: List<Document>): Document = BsonDocument("pipeline" to BsonArray(stages))

/**
 * Вычерпывает поток изменений и отдаёт события наружу.
 *
 * Отличается от [drainCursor] тем, чем change stream отличается от курсора: **он не кончается**.
 * `mongoc_change_stream_next` возвращает `false` и когда произошла ошибка, и когда события просто
 * не пришли за отведённое время. Различить два случая можно только через
 * `mongoc_change_stream_error_document`: пустая ошибка означает «ничего нового, продолжай».
 * Принять `false` за конец потока — самая естественная и самая неверная реализация здесь.
 *
 * Отмена корутины проверяется на каждом витке. Прервать сам вызов C нельзя (риск 2), поэтому
 * задержка отмены ограничена сверху `maxAwaitTimeMS` — ради этого mongkn и задаёт ему значение
 * по умолчанию, см. [ChangeStreamFlow].
 *
 * @param emit вызывается на каждое событие; документ переводится в Kotlin до вызова, потому что
 *   указатель действителен только до следующего `next`.
 */
@OptIn(ExperimentalForeignApi::class)
internal suspend fun drainChangeStream(
    stream: CPointer<mongoc_change_stream_t>,
    emit: suspend (Document) -> Unit,
) {
    memScoped {
        val current = allocPointerTo<bson_t>()
        val error = alloc<bson_error_t>()
        val reply = allocPointerTo<bson_t>()
        while (true) {
            currentCoroutineContext().ensureActive()
            if (mongoc_change_stream_next(stream, current.ptr)) {
                val document = current.value?.toDocument() ?: error("mongoc_change_stream_next отдал NULL при true")
                emit(document)
                continue
            }
            // false — это либо ошибка, либо «за это окно ничего не случилось».
            if (mongoc_change_stream_error_document(stream, error.ptr, reply.ptr)) {
                val value = error.ptr.pointed
                throw MongoException(value.domain, value.code, value.message.toKString())
            }
        }
    }
}

/**
 * Открывает поток изменений, вычерпывает его в канал и освобождает всё, что открыл.
 *
 * Общая часть для подписки на коллекцию, базу и клиент: у libmongoc это три разные функции
 * (`mongoc_collection_watch`, `mongoc_database_watch`, `mongoc_client_watch`), но дальше —
 * один и тот же `mongoc_change_stream_t`. Различие вынесено в параметр [open].
 *
 * Работает с `ProducerScope`, а не с `FlowCollector`, потому что подписка живёт на своём потоке,
 * а `flow` эмиссию из чужого контекста запрещает.
 */
@OptIn(ExperimentalForeignApi::class)
internal suspend fun ProducerScope<Document>.withChangeStream(
    pipeline: List<Document>,
    opts: Document,
    open: (CPointer<bson_t>, CPointer<bson_t>) -> CPointer<mongoc_change_stream_t>?,
) {
    val nativePipeline = pipelineDocument(pipeline).toNativeBson()
    val nativeOpts = withAwaitDefault(opts).toNativeBson()
    try {
        val stream = open(nativePipeline, nativeOpts) ?: error("watch вернул NULL")
        try {
            drainChangeStream(stream) { send(it) }
        } finally {
            mongoc_change_stream_destroy(stream)
        }
    } finally {
        bson_destroy(nativeOpts)
        bson_destroy(nativePipeline)
    }
}

/**
 * Подставляет `maxAwaitTimeMS`, если его не задали.
 *
 * Не оптимизация, а условие отменяемости: без него ожидание событий не имеет верхней границы,
 * и отмена корутины повисла бы до первого события. Явно заданное значение не трогается.
 */
private fun withAwaitDefault(opts: Document): Document =
    if ("maxAwaitTimeMS" in opts) {
        opts
    } else {
        BsonDocument(opts.entries + ("maxAwaitTimeMS" to BsonInt64(ChangeStreamFlow.DEFAULT_MAX_AWAIT_MILLIS)))
    }
