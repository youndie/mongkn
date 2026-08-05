package ru.workinprogress.mongkn.spec

import ru.workinprogress.mongkn.CommandListener
import ru.workinprogress.mongkn.CommandStartedEvent

/**
 * Записывает команды, уходящие на сервер, — для проверки `expectEvents` в spec-тестах.
 *
 * Хранит только `commandStartedEvent`: остальные типы событий сценарии из нашего набора
 * не проверяют, и делать вид, что мы их сверяем, незачем.
 *
 * Команды рукопожатия и служебные отсеиваются здесь, а не в сравнении: они не относятся
 * к операциям сценария, приходят в непредсказуемом количестве и сорвали бы любое сопоставление
 * по порядку.
 */
internal class SpecEventRecorder : CommandListener {
    private val events = mutableListOf<CommandStartedEvent>()

    override fun started(event: CommandStartedEvent) {
        if (event.commandName !in IGNORED) events += event
    }

    fun clear() {
        events.clear()
    }

    fun started(): List<CommandStartedEvent> = events.toList()

    private companion object {
        /** Рукопожатие, мониторинг топологии и уборка сессий — не часть сценария. */
        val IGNORED =
            setOf(
                "hello",
                "isMaster",
                "ismaster",
                "ping",
                "buildInfo",
                "getLastError",
                "endSessions",
                "configureFailPoint",
            )
    }
}
