package ru.workinprogress.mongkn

import ru.workinprogress.mongkn.bson.Document

/**
 * Событие «команда отправлена».
 *
 * @property command сам документ команды — то, что ушло на сервер. Тяжёлый: для крупной вставки
 *   это весь пакет документов. Если нужно только имя, берите [commandName] и не трогайте его.
 * @property requestId идентификатор запроса; совпадает у started и последующего succeeded/failed.
 * @property operationId идентификатор логической операции; у пакетной записи он один
 *   на несколько запросов.
 */
public class CommandStartedEvent(
    public val commandName: String,
    public val databaseName: String,
    public val command: Document,
    public val requestId: Long,
    public val operationId: Long,
) {
    override fun toString(): String = "CommandStartedEvent($commandName, $databaseName, request=$requestId)"
}

/**
 * Событие «команда выполнена». [durationMicros] измерил сам драйвер.
 *
 * Имени базы здесь **нет**, в отличие от [CommandStartedEvent], и это не упущение:
 * `mongoc_apm_command_succeeded_get_database_name` отсутствует в libmongoc **1.26** — версии
 * из Ubuntu 24.04 LTS, на которую мы держим нижнюю границу. Это ограничение конкретной версии,
 * а не ветки 1.x: в 1.30 функция уже есть.
 *
 * Достроить поле, запоминая базу из started-события по [requestId], технически можно, но это
 * означало бы изменяемое состояние, разделяемое между потоками и читаемое из C-коллбэка, —
 * цена, несоразмерная удобству. Связывайте события по [requestId] сами, если имя базы нужно.
 */
public class CommandSucceededEvent(
    public val commandName: String,
    public val reply: Document,
    public val durationMicros: Long,
    public val requestId: Long,
    public val operationId: Long,
) {
    override fun toString(): String = "CommandSucceededEvent($commandName, ${durationMicros}мкс, request=$requestId)"
}

/** Событие «команда не выполнена». Имени базы здесь нет по той же причине, что и в [CommandSucceededEvent]. */
public class CommandFailedEvent(
    public val commandName: String,
    public val failure: MongoException,
    public val durationMicros: Long,
    public val requestId: Long,
    public val operationId: Long,
) {
    override fun toString(): String = "CommandFailedEvent($commandName, ${durationMicros}мкс, request=$requestId)"
}

/**
 * Наблюдатель команд, уходящих на сервер, — то, что в спецификации MongoDB зовётся APM.
 *
 * Отличается от [MongknLog] предметом: там сообщения самого драйвера, здесь — команды, их
 * содержимое и время выполнения. Это то, из чего строят метрики и трассировку.
 *
 * ```
 * val client = MongoClient(uri, commandListener = object : CommandListener {
 *     override fun succeeded(event: CommandSucceededEvent) {
 *         metrics.record(event.commandName, event.durationMicros)
 *     }
 * })
 * ```
 *
 * **Вызывается синхронно, из потока драйвера, посреди операции.** Пока метод работает, операция
 * ждёт: тяжёлая обработка здесь замедлит всё. Складывайте события в очередь и разбирайте
 * отдельно.
 *
 * Исключение из метода наблюдателя **проглатывается**: наблюдение не должно ломать операцию,
 * за которой наблюдает. Это осознанный выбор, а не недосмотр — иначе включение метрик меняло бы
 * поведение приложения.
 */
public interface CommandListener {
    /** Команда отправлена на сервер. */
    public fun started(event: CommandStartedEvent) {}

    /** Сервер ответил успехом. */
    public fun succeeded(event: CommandSucceededEvent) {}

    /** Сервер ответил ошибкой либо связь оборвалась. */
    public fun failed(event: CommandFailedEvent) {}
}
