package ru.workinprogress.mongkn.support

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * Адрес mongod для тестов.
 *
 * Хост берётся из `MONGKN_TEST_HOST`, потому что в CI и в Linux-контейнере сервер живёт не на
 * `127.0.0.1`, а под своим именем в сети docker. Захардкоженный адрес означал бы, что тесты
 * запускаются только на машине разработчика.
 */
@OptIn(ExperimentalForeignApi::class)
object TestServer {

    val host: String = getenv("MONGKN_TEST_HOST")?.toKString()?.takeIf { it.isNotBlank() } ?: "127.0.0.1:27017"

    /** @param options параметры строки подключения без ведущего `?`. */
    fun uri(options: String = ""): String =
        "mongodb://$host" + if (options.isEmpty()) "" else "/?$options"
}
