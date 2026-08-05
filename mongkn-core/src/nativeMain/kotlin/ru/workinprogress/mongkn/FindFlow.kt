package ru.workinprogress.mongkn

import kotlinx.coroutines.flow.Flow
import ru.workinprogress.mongkn.bson.BsonBoolean
import ru.workinprogress.mongkn.bson.BsonDocument
import ru.workinprogress.mongkn.bson.BsonInt32
import ru.workinprogress.mongkn.bson.BsonInt64
import ru.workinprogress.mongkn.bson.BsonString
import ru.workinprogress.mongkn.bson.BsonValue
import ru.workinprogress.mongkn.bson.Document

/**
 * Результат [MongoCollection.find] с чейнингом опций.
 *
 * Закрывает вторую половину решения Р8. Там было сказано: `FindFlow` **реализует** `Flow`,
 * поэтому переход от голого `Flow<Document>` к нему source-совместим — и это оказалось верно,
 * ни один существовавший вызов вида `find().toList()` править не пришлось.
 *
 * ```
 * collection.find(filter).sort(document { put("born", 1) }).skip(10).limit(5).toList()
 * ```
 *
 * Реализация `Flow` — **делегированием**, как у официального драйвера. Наследоваться от `Flow`
 * напрямую нельзя без opt-in во внутренний API корутин, а `AbstractFlow` дал бы лишний слой.
 *
 * Объект неизменяемый: каждый вызов возвращает новый [FindFlow], поэтому промежуточную заготовку
 * можно переиспользовать, не боясь, что её настройки поменяет кто-то ещё.
 */
public class FindFlow<T> internal constructor(
    private val source: (Document, Document) -> Flow<T>,
    private val filter: Document,
    private val opts: Document,
) : Flow<T> by source(filter, adjustBatchSize(opts)) {
    /** Не больше указанного числа документов. */
    public fun limit(count: Int): FindFlow<T> = withOption("limit", BsonInt64(count.toLong()))

    /** Пропустить первые документы. */
    public fun skip(count: Int): FindFlow<T> = withOption("skip", BsonInt64(count.toLong()))

    /** Порядок: документ вида `{"поле": 1}` по возрастанию, `-1` по убыванию. */
    public fun sort(order: Document): FindFlow<T> = withOption("sort", order)

    /** Какие поля вернуть. */
    public fun projection(fields: Document): FindFlow<T> = withOption("projection", fields)

    /** Размер порции, которую сервер отдаёт за один раз. На результат не влияет. */
    public fun batchSize(size: Int): FindFlow<T> = withOption("batchSize", BsonInt32(size))

    /** Индекс, которым выполнять запрос: документ ключей. */
    public fun hint(index: Document): FindFlow<T> = withOption("hint", index)

    /** Индекс по имени. */
    public fun hintString(indexName: String): FindFlow<T> = withOption("hint", BsonString(indexName))

    /** Правила сравнения строк — язык, регистр, диакритика. */
    public fun collation(rules: Document): FindFlow<T> = withOption("collation", rules)

    /** Комментарий, видимый в профайлере и логах сервера. */
    public fun comment(text: String): FindFlow<T> = withOption("comment", BsonString(text))

    /**
     * Комментарий произвольного типа BSON — например документ.
     *
     * Сервер с версии 4.4 принимает здесь не только строку, и официальный драйвер это допускает.
     * Отдельная перегрузка, а не замена строковой: строка — обычный случай, и заставлять
     * оборачивать её в `BsonString` было бы шагом назад.
     */
    public fun comment(value: BsonValue): FindFlow<T> = withOption("comment", value)

    /** Переменные, доступные выражениям запроса. */
    public fun let(variables: Document): FindFlow<T> = withOption("let", variables)

    /** Верхняя граница индекса (исключительно). */
    public fun max(bound: Document): FindFlow<T> = withOption("max", bound)

    /** Нижняя граница индекса (включительно). */
    public fun min(bound: Document): FindFlow<T> = withOption("min", bound)

    /**
     * Сколько сервер вправе выполнять запрос.
     *
     * Это ограничение **на стороне сервера**: отмена корутины уже начатый вызов не прерывает
     * (риск 2 ресёрча), а это указание прекратить работу.
     */
    public fun maxTime(millis: Long): FindFlow<T> = withOption("maxTimeMS", BsonInt64(millis))

    /** Сколько ждать новых документов у tailable-курсора. Смысл имеет только с [CursorType.TAILABLE_AWAIT]. */
    public fun maxAwaitTime(millis: Long): FindFlow<T> = withOption("maxAwaitTimeMS", BsonInt64(millis))

    /** Не закрывать курсор по бездействию. */
    public fun noCursorTimeout(enabled: Boolean = true): FindFlow<T> =
        withOption("noCursorTimeout", BsonBoolean(enabled))

    /** Отдавать частичный результат, если часть шардов недоступна. */
    public fun partial(enabled: Boolean = true): FindFlow<T> = withOption("allowPartialResults", BsonBoolean(enabled))

    /** Вернуть только ключи индекса вместо документов. */
    public fun returnKey(enabled: Boolean = true): FindFlow<T> = withOption("returnKey", BsonBoolean(enabled))

    /** Добавить к каждому документу его позицию в хранилище. */
    public fun showRecordId(enabled: Boolean = true): FindFlow<T> = withOption("showRecordId", BsonBoolean(enabled))

    /** Разрешить серверу использовать диск при сортировке больших выборок. */
    public fun allowDiskUse(enabled: Boolean = true): FindFlow<T> = withOption("allowDiskUse", BsonBoolean(enabled))

    /**
     * Тип курсора.
     *
     * На проводе это не одно поле, а два флага — `tailable` и `awaitData`; перечисление собирает
     * их в осмысленные сочетания, как это делает официальный драйвер.
     */
    public fun cursorType(type: CursorType): FindFlow<T> =
        withOption("tailable", BsonBoolean(type != CursorType.NON_TAILABLE))
            .withOption("awaitData", BsonBoolean(type == CursorType.TAILABLE_AWAIT))

    /**
     * Заменяет опцию, а не добавляет вторую с тем же именем.
     *
     * `BsonDocument` допускает повторяющиеся ключи, а mongoc в опциях их не ждёт: без замены
     * `limit(1).limit(2)` отправил бы документ с двумя `limit`.
     */
    internal fun withOption(
        name: String,
        value: BsonValue,
    ): FindFlow<T> =
        FindFlow(
            source = source,
            filter = filter,
            opts = BsonDocument(opts.entries.filterNot { it.first == name } + (name to value)),
        )
}

/**
 * Поднимает `batchSize` на единицу, когда он равен `limit`.
 *
 * Требование спецификации CRUD, и не косметическое: при равных `limit` и `batchSize` сервер
 * отдаёт ровно запрошенное число документов и **оставляет курсор открытым**, не зная, что
 * клиенту больше ничего не нужно. Лишняя единица позволяет серверу увидеть конец выборки сразу
 * и закрыть курсор, экономя и `getMore`, и `killCursors`.
 *
 * Делается здесь, потому что libmongoc этого не делает: официальный сценарий
 * `find.json :: Find with batchSize equal to limit` ждёт `batchSize: 5` при `limit: 4`,
 * а драйвер отправляет `batchSize: 4`. Поправка на нашей стороне — единственное место,
 * где она возможна.
 *
 * Считается при построении потока, а не в [FindFlow.limit] и [FindFlow.batchSize]: значение
 * зависит от **обоих** ключей, а задавать их можно в любом порядке.
 */
private fun adjustBatchSize(opts: Document): Document {
    val limit = (opts["limit"] as? BsonInt64)?.value ?: return opts
    val batchSize = (opts["batchSize"] as? BsonInt32)?.value?.toLong() ?: return opts
    if (limit != batchSize) return opts
    return BsonDocument(
        opts.entries.map { (key, value) ->
            if (key == "batchSize") key to BsonInt32((batchSize + 1).toInt()) else key to value
        },
    )
}
