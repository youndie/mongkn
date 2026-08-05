package ru.workinprogress.mongkn.support

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import mongkn.cinterop.bson_destroy
import mongkn.cinterop.bson_error_t
import mongkn.cinterop.bson_init
import mongkn.cinterop.bson_json_reader_destroy
import mongkn.cinterop.bson_json_reader_new_from_file
import mongkn.cinterop.bson_json_reader_read
import mongkn.cinterop.bson_t
import platform.posix.getenv
import ru.workinprogress.mongkn.bson.BsonDocument
import ru.workinprogress.mongkn.bson.toDocument

/**
 * Чтение JSON-файлов в тестах.
 *
 * Парсер тащить не пришлось: libbson уже слинкована и умеет extended JSON сама. Побочная выгода —
 * сравнение идёт по значениям, а не по тексту, так что пробелы и экранирование ничего не ломают.
 */
@OptIn(ExperimentalForeignApi::class)
fun readJsonDocument(path: String): BsonDocument =
    memScoped {
        val error = alloc<bson_error_t>()
        val reader =
            bson_json_reader_new_from_file(path, error.ptr)
                ?: error("не открылся $path: ${error.message.toKString()}")
        try {
            val target = alloc<bson_t>()
            bson_init(target.ptr)
            try {
                when (bson_json_reader_read(reader, target.ptr, error.ptr)) {
                    1 -> target.ptr.toDocument()
                    0 -> error("$path пуст")
                    else -> error("$path не разобрался: ${error.message.toKString()}")
                }
            } finally {
                bson_destroy(target.ptr)
            }
        } finally {
            bson_json_reader_destroy(reader)
        }
    }

/** Путь, переданный тесту из Gradle. Отсутствие переменной — ошибка, а не повод пропустить тест. */
@OptIn(ExperimentalForeignApi::class)
fun requiredPath(
    variable: String,
    hint: String,
): String =
    getenv(variable)?.toKString()
        ?: error("не задана переменная окружения $variable: $hint")
