package ru.workinprogress.mongkn

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import ru.workinprogress.mongkn.bson.BsonBoolean
import ru.workinprogress.mongkn.bson.BsonDocument
import ru.workinprogress.mongkn.bson.BsonInt32
import ru.workinprogress.mongkn.bson.BsonInt64
import ru.workinprogress.mongkn.bson.BsonString
import ru.workinprogress.mongkn.bson.BsonValue
import ru.workinprogress.mongkn.bson.Document

/**
 * Результат `aggregate` с чейнингом опций.
 *
 * Отдельный тип, а не переиспользованный [FindFlow], — и это решение, а не копирование
 * официального драйвера ради формы (вопрос M-55). Наборы опций у операций разные и почти
 * не пересекаются: у `find` есть `skip`, `sort`, `projection`, `min`/`max`, `returnKey`,
 * у агрегации — `bypassDocumentValidation` и `toCollection`. Общий тип предлагал бы половину
 * методов, которые сервер на этой операции отвергнет, — то есть ошибку времени выполнения там,
 * где сейчас ошибка компиляции.
 *
 * Цена — дублирование девяти однострочников, общих у двух операций. Устройство при этом одно:
 * `Flow` реализуется делегированием, объект неизменяемый, каждый вызов возвращает копию.
 *
 * ```
 * collection
 *     .aggregate(listOf(document { put("${'$'}match", document { put("born", 1815) }) }))
 *     .allowDiskUse()
 *     .toList()
 * ```
 */
public class AggregateFlow<T> internal constructor(
    private val source: (List<Document>, Document) -> Flow<T>,
    private val pipeline: List<Document>,
    private val opts: Document,
) : Flow<T> by source(pipeline, opts) {
    /** Размер порции, которую сервер отдаёт за один раз. На результат не влияет. */
    public fun batchSize(size: Int): AggregateFlow<T> = withOption("batchSize", BsonInt32(size))

    /** Разрешить серверу использовать диск на стадиях, не помещающихся в память. */
    public fun allowDiskUse(enabled: Boolean = true): AggregateFlow<T> =
        withOption("allowDiskUse", BsonBoolean(enabled))

    /**
     * Не проверять записываемые документы валидатором коллекции.
     *
     * Имеет смысл только для конвейеров со стадией `$out` или `$merge`: без них агрегация
     * ничего не пишет.
     */
    public fun bypassDocumentValidation(enabled: Boolean = true): AggregateFlow<T> =
        withOption("bypassDocumentValidation", BsonBoolean(enabled))

    /** Правила сравнения строк — язык, регистр, диакритика. */
    public fun collation(rules: Document): AggregateFlow<T> = withOption("collation", rules)

    /** Комментарий, видимый в профайлере и логах сервера. */
    public fun comment(text: String): AggregateFlow<T> = withOption("comment", BsonString(text))

    /**
     * Комментарий произвольного типа BSON — например документ.
     *
     * Сервер с версии 4.4 принимает здесь не только строку, и официальный драйвер это допускает.
     * Отдельная перегрузка, а не замена строковой: строка — обычный случай, и заставлять
     * оборачивать её в `BsonString` было бы шагом назад.
     */
    public fun comment(value: BsonValue): AggregateFlow<T> = withOption("comment", value)

    /** Индекс, которым выполнять первую стадию: документ ключей. */
    public fun hint(index: Document): AggregateFlow<T> = withOption("hint", index)

    /** Индекс по имени. */
    public fun hintString(indexName: String): AggregateFlow<T> = withOption("hint", BsonString(indexName))

    /** Переменные, доступные выражениям конвейера. */
    public fun let(variables: Document): AggregateFlow<T> = withOption("let", variables)

    /**
     * Сколько сервер вправе выполнять конвейер.
     *
     * Ограничение **на стороне сервера**: отмена корутины уже начатый вызов не прерывает
     * (риск 2 ресёрча), а это указание прекратить работу.
     */
    public fun maxTime(millis: Long): AggregateFlow<T> = withOption("maxTimeMS", BsonInt64(millis))

    /** Сколько ждать новых документов у tailable-курсора — для конвейеров над change stream. */
    public fun maxAwaitTime(millis: Long): AggregateFlow<T> = withOption("maxAwaitTimeMS", BsonInt64(millis))

    /**
     * Выполняет конвейер, ничего не возвращая.
     *
     * Для конвейеров, заканчивающихся `$out` или `$merge`: они пишут результат в коллекцию
     * и наружу не отдают ни одного документа. Собирать такой поток `toList()` можно, но это
     * вводит в заблуждение — пустой список читается как «ничего не нашлось», хотя работа сделана.
     *
     * Важно, что это именно вызов, а не свойство: поток холодный, и без сбора конвейер
     * не выполнится вовсе.
     */
    public suspend fun toCollection(): Unit = collect()

    /**
     * Заменяет опцию, а не добавляет вторую с тем же именем.
     *
     * `BsonDocument` допускает повторяющиеся ключи, а mongoc в опциях их не ждёт.
     */
    private fun withOption(
        name: String,
        value: BsonValue,
    ): AggregateFlow<T> =
        AggregateFlow(
            source = source,
            pipeline = pipeline,
            opts = BsonDocument(opts.entries.filterNot { it.first == name } + (name to value)),
        )
}
