package ru.workinprogress.mongkn.spec

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import mongkn.cinterop.bson_destroy
import mongkn.cinterop.bson_error_t
import mongkn.cinterop.bson_t
import mongkn.cinterop.mongoc_client_command_simple
import ru.workinprogress.mongkn.MongoClient
import ru.workinprogress.mongkn.bson.BsonDocument
import ru.workinprogress.mongkn.bson.BsonInt32
import ru.workinprogress.mongkn.bson.BsonString
import ru.workinprogress.mongkn.bson.toDocument
import ru.workinprogress.mongkn.bson.toNativeBson

/**
 * Версия сервера — для `runOnRequirements` в spec-тестах.
 *
 * Публичного API для произвольных команд у mongkn нет и в скоуп он не входит, поэтому
 * `buildInfo` вызывается через cinterop напрямую: тестовому source set внутренности модуля видны.
 */
@OptIn(ExperimentalForeignApi::class)
internal suspend fun serverVersion(client: MongoClient): List<Int> =
    client.withClient { handle ->
        memScoped {
            val command = BsonDocument("buildInfo" to BsonInt32(1)).toNativeBson()
            val reply = alloc<bson_t>()
            val error = alloc<bson_error_t>()
            try {
                check(mongoc_client_command_simple(handle, "admin", command, null, reply.ptr, error.ptr)) {
                    "buildInfo не выполнился"
                }
                val version =
                    (reply.ptr.toDocument()["version"] as? BsonString)?.value
                        ?: error("в ответе buildInfo нет version")
                version.split('.').mapNotNull { part -> part.takeWhile { it.isDigit() }.toIntOrNull() }
            } finally {
                bson_destroy(reply.ptr)
                bson_destroy(command)
            }
        }
    }

/**
 * Сравнение версий покомпонентно.
 *
 * Строковое сравнение здесь неверно: «8.0» меньше «2.6» лексикографически, но больше по существу.
 */
internal fun compareVersions(
    left: List<Int>,
    right: List<Int>,
): Int {
    for (i in 0 until maxOf(left.size, right.size)) {
        val diff = (left.getOrNull(i) ?: 0) - (right.getOrNull(i) ?: 0)
        if (diff != 0) return diff
    }
    return 0
}

internal fun parseVersion(text: String): List<Int> =
    text.split('.').mapNotNull { part -> part.takeWhile { it.isDigit() }.toIntOrNull() }
