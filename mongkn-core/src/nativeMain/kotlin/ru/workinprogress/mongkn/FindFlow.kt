package ru.workinprogress.mongkn

import kotlinx.coroutines.flow.Flow
import ru.workinprogress.mongkn.bson.BsonDocument
import ru.workinprogress.mongkn.bson.BsonInt32
import ru.workinprogress.mongkn.bson.BsonInt64
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
) : Flow<T> by source(filter, opts) {
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

    /**
     * Заменяет опцию, а не добавляет вторую с тем же именем.
     *
     * `BsonDocument` допускает повторяющиеся ключи, а mongoc в опциях их не ждёт: без замены
     * `limit(1).limit(2)` отправил бы документ с двумя `limit`.
     */
    private fun withOption(
        name: String,
        value: BsonValue,
    ): FindFlow<T> =
        FindFlow(
            source = source,
            filter = filter,
            opts = BsonDocument(opts.entries.filterNot { it.first == name } + (name to value)),
        )
}
