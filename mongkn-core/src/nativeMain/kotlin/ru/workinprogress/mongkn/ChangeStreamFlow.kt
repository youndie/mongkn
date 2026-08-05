package ru.workinprogress.mongkn

import kotlinx.coroutines.flow.Flow
import ru.workinprogress.mongkn.bson.BsonDocument
import ru.workinprogress.mongkn.bson.BsonInt32
import ru.workinprogress.mongkn.bson.BsonInt64
import ru.workinprogress.mongkn.bson.BsonString
import ru.workinprogress.mongkn.bson.BsonValue
import ru.workinprogress.mongkn.bson.Document

/**
 * Поток изменений — результат `watch`.
 *
 * Отличается от [FindFlow] и [AggregateFlow] не набором методов, а поведением: этот поток
 * **не заканчивается**. Он ждёт следующего события столько, сколько живёт подписка, и завершится
 * только отменой, ошибкой или закрытием клиента. Поэтому `toList()` на нём — почти всегда ошибка;
 * собирать его надо `collect`, `take(n)` или в отдельной корутине.
 *
 * Две вещи, которые стоит знать до первого использования, — обе следствия того, что libmongoc
 * блокирующий, а подписка бесконечная:
 *
 * * **подписка занимает собственный поток на всё своё время.** Клиент из пула вернуть нельзя —
 *   курсор принадлежит ему, — а блокирующий вызов нельзя делить с другими операциями. Поэтому
 *   каждый `watch` получает свой поток, а не берёт его из общего пула клиента: иначе четыре
 *   подписки (`MongoClient.DEFAULT_IO_THREADS`) остановили бы всю остальную работу;
 * * **отмена срабатывает не мгновенно.** Прервать начатый вызов C нельзя, поэтому отмена
 *   замечается между витками ожидания. Ради предсказуемости этой задержки mongkn задаёт
 *   `maxAwaitTimeMS` по умолчанию ([DEFAULT_MAX_AWAIT_MILLIS]) — официальный драйвер значения
 *   не навязывает, но там и нет блокирующего потока, который надо освободить.
 *
 * Change streams работают **только на replica set**: на standalone-сервере сервер откажет.
 * Это ограничение MongoDB, а не mongkn.
 */
public class ChangeStreamFlow<T> internal constructor(
    private val source: (List<Document>, Document) -> Flow<T>,
    private val pipeline: List<Document>,
    private val opts: Document,
) : Flow<T> by source(pipeline, opts) {
    /** Размер порции, которую сервер отдаёт за один раз. */
    public fun batchSize(size: Int): ChangeStreamFlow<T> = withOption("batchSize", BsonInt32(size))

    /**
     * Сколько сервер ждёт новых событий, прежде чем вернуть управление.
     *
     * Задаёт заодно и верхнюю границу задержки отмены — см. KDoc класса.
     */
    public fun maxAwaitTime(millis: Long): ChangeStreamFlow<T> = withOption("maxAwaitTimeMS", BsonInt64(millis))

    /**
     * Что класть в `fullDocument` для обновлений.
     *
     * По умолчанию сервер присылает только описание изменения (`updateDescription`), а самого
     * документа в событии нет. `"updateLookup"` заставляет его дочитать текущую версию.
     */
    public fun fullDocument(mode: String): ChangeStreamFlow<T> = withOption("fullDocument", BsonString(mode))

    /** Что класть в `fullDocumentBeforeChange`. Требует включённого на коллекции preImages. */
    public fun fullDocumentBeforeChange(mode: String): ChangeStreamFlow<T> =
        withOption("fullDocumentBeforeChange", BsonString(mode))

    /** Продолжить с места, обозначенного токеном из ранее полученного события (`_id`). */
    public fun resumeAfter(token: Document): ChangeStreamFlow<T> = withOption("resumeAfter", token)

    /** То же, но включая события, начиная с самого токена, — для потоков, начатых заново. */
    public fun startAfter(token: Document): ChangeStreamFlow<T> = withOption("startAfter", token)

    /** Начать с указанного момента времени кластера. */
    public fun startAtOperationTime(timestamp: BsonValue): ChangeStreamFlow<T> =
        withOption("startAtOperationTime", timestamp)

    /** Правила сравнения строк для стадий конвейера. */
    public fun collation(rules: Document): ChangeStreamFlow<T> = withOption("collation", rules)

    /** Комментарий, видимый в профайлере и логах сервера. */
    public fun comment(text: String): ChangeStreamFlow<T> = withOption("comment", BsonString(text))

    /**
     * Комментарий произвольного типа BSON — например документ.
     *
     * Сервер с версии 4.4 принимает здесь не только строку, и официальный драйвер это допускает.
     * Отдельная перегрузка, а не замена строковой: строка — обычный случай, и заставлять
     * оборачивать её в `BsonString` было бы шагом назад.
     */
    public fun comment(value: BsonValue): ChangeStreamFlow<T> = withOption("comment", value)

    /** Присылать расширенный набор событий — DDL и прочее, чего в базовом наборе нет. */
    public fun showExpandedEvents(enabled: Boolean = true): ChangeStreamFlow<T> =
        withOption(
            "showExpandedEvents",
            ru.workinprogress.mongkn.bson
                .BsonBoolean(enabled),
        )

    private fun withOption(
        name: String,
        value: BsonValue,
    ): ChangeStreamFlow<T> =
        ChangeStreamFlow(
            source = source,
            pipeline = pipeline,
            opts = BsonDocument(opts.entries.filterNot { it.first == name } + (name to value)),
        )

    public companion object {
        /**
         * Значение `maxAwaitTimeMS`, если его не задали явно.
         *
         * Существует не ради производительности, а ради отменяемости: именно им ограничена сверху
         * задержка между отменой корутины и остановкой подписки.
         */
        public const val DEFAULT_MAX_AWAIT_MILLIS: Long = 1000
    }
}
