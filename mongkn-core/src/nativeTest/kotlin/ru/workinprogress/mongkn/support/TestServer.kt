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

    /**
     * Адрес **отдельного** сервера с включённой аутентификацией.
     *
     * Отдельный, а не тот же самый, по одной причине: включи мы `--auth` на основном, каждому
     * из полутора сотен остальных тестов понадобились бы креды, и проверка аутентификации
     * растворилась бы в общем шуме вместо того, чтобы быть предметом теста.
     */
    val authHost: String =
        getenv("MONGKN_TEST_AUTH_HOST")?.toKString()?.takeIf { it.isNotBlank() } ?: "127.0.0.1:27019"

    /**
     * Учётные данные тестового сервера.
     *
     * Это **фикстура локального контейнера**, а не секрет: сервер поднимается тут же и живёт
     * только для прогона. Настоящие креды в репозиторий не кладутся — те, что нужны публикации,
     * лежат в `~/.zshrc` и берутся из окружения.
     */
    const val USER: String = "mongkn_test"
    const val PASSWORD: String = "mongkn_secret"

    /** Пользователь, пароль которого требует процентного кодирования в URI. */
    const val ODD_USER: String = "mongkn_odd"
    const val ODD_PASSWORD: String = "p@ss:w/rd?#1"

    /** @param options параметры строки подключения без ведущего `?`. */
    fun uri(options: String = ""): String = "mongodb://$host" + if (options.isEmpty()) "" else "/?$options"

    /**
     * Строка подключения к серверу с аутентификацией.
     *
     * @param user имя пользователя как есть.
     * @param password пароль **уже закодированный** для URI, если этого требуют символы.
     */
    fun authUri(
        user: String = USER,
        password: String = PASSWORD,
        options: String = "authSource=admin",
    ): String = "mongodb://$user:$password@$authHost/" + if (options.isEmpty()) "" else "?$options"
}
