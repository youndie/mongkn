package ru.workinprogress.mongkn

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import mongkn.cinterop.mongoc_log_level_t
import mongkn.cinterop.mongoc_log_set_handler
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Уровень сообщения libmongoc — `mongoc_log_level_t`. */
public enum class MongknLogLevel {
    ERROR,
    CRITICAL,
    WARNING,
    MESSAGE,
    INFO,
    DEBUG,
    UNKNOWN,
}

/**
 * Диагностика самого драйвера: libmongoc пишет о выборе сервера, обрывах и повторах.
 *
 * ```
 * MongknLog.setHandler { level, domain, message -> println("[$level/$domain] $message") }
 * ```
 *
 * Три особенности, каждая из которых способна удивить:
 *
 * * **обработчик один на процесс**, а не на клиента. Так устроен `mongoc_log_set_handler`;
 *   привязать его к [MongoClient] нельзя, и обёртка не делает вид, что можно;
 * * **вызывается из любого потока** драйвера, в том числе не из корутины. Обработчик обязан
 *   быть потокобезопасным и быстрым: пока он работает, стоит операция, которая его вызвала;
 * * **это не мониторинг команд.** Здесь нет ни самих команд, ни их времени выполнения — только
 *   сообщения драйвера. Мониторинг команд (APM) не реализован, из-за него же пропускается
 *   часть официальных spec-сценариев.
 *
 * Структурированное логирование (`mongoc_structured_log_opts_t`), которое даёт события
 * с полями вместо строк, здесь **не используется намеренно**: заголовка
 * `mongoc-structured-log.h` нет в ветке 1.x, а публикуемая платформа — `linuxX64`, где
 * стоит именно 1.26. Обвязка, работающая только на машине разработчика, хуже её отсутствия.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
public object MongknLog {
    /**
     * Текущий обработчик.
     *
     * `AtomicReference`, а не обычная переменная: libmongoc зовёт обработчик из своих потоков,
     * и замена его на ходу не должна давать полуустановленное состояние.
     */
    private val handler = AtomicReference<((MongknLogLevel, String, String) -> Unit)?>(null)

    /**
     * Устанавливает обработчик или снимает его (`null` — вернуть поведение libmongoc
     * по умолчанию, то есть вывод в stderr).
     */
    public fun setHandler(handler: ((level: MongknLogLevel, domain: String, message: String) -> Unit)?) {
        this.handler.store(handler)
        if (handler == null) {
            mongoc_log_set_handler(null, null)
        } else {
            // staticCFunction не может ничего захватывать — обработчик берётся из объекта.
            mongoc_log_set_handler(
                staticCFunction { level, domain, message, _ ->
                    MongknLog.dispatch(level, domain?.toKString(), message?.toKString())
                },
                null,
            )
        }
    }

    private fun dispatch(
        level: mongoc_log_level_t,
        domain: String?,
        message: String?,
    ) {
        handler.load()?.invoke(levelOf(level), domain.orEmpty(), message.orEmpty())
    }

    private fun levelOf(level: mongoc_log_level_t): MongknLogLevel =
        when (level) {
            mongoc_log_level_t.MONGOC_LOG_LEVEL_ERROR -> MongknLogLevel.ERROR
            mongoc_log_level_t.MONGOC_LOG_LEVEL_CRITICAL -> MongknLogLevel.CRITICAL
            mongoc_log_level_t.MONGOC_LOG_LEVEL_WARNING -> MongknLogLevel.WARNING
            mongoc_log_level_t.MONGOC_LOG_LEVEL_MESSAGE -> MongknLogLevel.MESSAGE
            mongoc_log_level_t.MONGOC_LOG_LEVEL_INFO -> MongknLogLevel.INFO
            mongoc_log_level_t.MONGOC_LOG_LEVEL_DEBUG -> MongknLogLevel.DEBUG
            else -> MongknLogLevel.UNKNOWN
        }
}
