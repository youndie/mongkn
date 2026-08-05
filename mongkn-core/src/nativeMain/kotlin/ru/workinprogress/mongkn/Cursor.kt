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
import kotlinx.coroutines.flow.FlowCollector
import mongkn.cinterop.bson_error_t
import mongkn.cinterop.bson_t
import mongkn.cinterop.mongoc_cursor_destroy
import mongkn.cinterop.mongoc_cursor_error
import mongkn.cinterop.mongoc_cursor_next
import mongkn.cinterop.mongoc_cursor_t
import ru.workinprogress.mongkn.bson.BsonArray
import ru.workinprogress.mongkn.bson.BsonDocument
import ru.workinprogress.mongkn.bson.Document
import ru.workinprogress.mongkn.bson.toDocument

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
