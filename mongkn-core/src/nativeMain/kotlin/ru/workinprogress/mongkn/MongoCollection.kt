package ru.workinprogress.mongkn

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import ru.workinprogress.mongkn.bson.BsonDocument
import ru.workinprogress.mongkn.bson.BsonValue
import ru.workinprogress.mongkn.bson.Document
import ru.workinprogress.mongkn.bson.decodeFromDocument
import ru.workinprogress.mongkn.bson.encodeToDocument

/**
 * Коллекция MongoDB.
 *
 * Форма снята с `com.mongodb.kotlin.client.coroutine.MongoCollection` (mongodb-driver-kotlin-coroutine
 * 5.9.1): те же имена операций и параметров, та же `suspend`-ность, тот же смысл результата.
 * Раньше этот файл печатал генератор; от генерации отказались (решение Р9 ресёрча), потому что
 * она гарантировала **форму** API, а не **поведение**, — а форма стоила двух JVM-модулей
 * на критическом пути сборки.
 *
 * Теперь совпадение с официальным драйвером держится не на компиляции, а на двух вещах:
 * на таблице соответствия в [api-collection](../../../../../../../docs/api/api-collection.md)
 * (раздел 3) и на дифференциальных тестах против самого драйвера (`:mongkn-difftest`).
 * Вторые сильнее: они проверяют, что мы делаем то же самое, а не что мы так же назвали параметр.
 *
 * Правила, по которым форма снималась, — чтобы при добавлении операций не гадать заново:
 *
 * * из перегрузок берётся базовая — **без** `ClientSession` (транзакции вне скоупа) и **без**
 *   `*Options`;
 * * `org.bson.conversions.Bson` и параметр типа документа отображаются в [Document];
 * * `FindFlow<T>` отображается в `Flow<Document>`: `FindFlow` **реализует** `Flow`, поэтому
 *   расширение до чейнинга будет source-совместимым (решение Р8);
 * * **осторожно с одноимёнными перегрузками одинаковой длины.** У `updateOne` их две:
 *   `(Bson filter, Bson update)` и `(Bson filter, List<Bson> pipeline)` — обновление документом
 *   и агрегационным конвейером. Здесь взята первая. Это ровно та ловушка, которую генератор
 *   разрешал механически, а теперь её ловит только дифференциальный тест.
 *
 * Параметр [T] — класс документа, как у официального `MongoCollection<T>`. Фильтры и документы
 * обновления остаются [Document] и там: они описывают запрос, а не хранимую сущность.
 *
 * Все операции блокирующие внутри и уходят на пул потоков клиента — см. [CollectionOps].
 * Расширять эту поверхность снаружи можно функциями-расширениями: параметры-фильтры принимают
 * [Document], точки расширения не закрыты (требование решения Р7).
 */
public class MongoCollection<T> internal constructor(
    internal val client: MongoClient,
    internal val databaseName: String,
    public val name: String,
    private val codec: KSerializer<T>?,
) {
    /**
     * Переводит документ в [T] и обратно.
     *
     * `codec == null` означает `MongoCollection<Document>` — тождественное преобразование.
     * Отдельная ветка, а не сериализатор для `Document`, потому что документ и так уже документ:
     * гонять его через кодек значило бы платить за ничего и терять типы, которые сериализация
     * не различает (например `BsonObjectId`).
     */
    @Suppress("UNCHECKED_CAST")
    private fun toDocument(value: T): Document =
        if (codec == null) value as Document else encodeToDocument(codec, value)

    @Suppress("UNCHECKED_CAST")
    private fun fromDocument(document: Document): T =
        if (codec == null) document as T else decodeFromDocument(codec, document)

    /** Вставляет документ и возвращает его `_id`. Неуспех — всегда [MongoException]. */
    public suspend fun insertOne(document: T): InsertOneResult =
        CollectionOps.insertOne(client, databaseName, name, toDocument(document))

    /**
     * Вставляет несколько документов. Пустой список отвергается до обращения к серверу.
     *
     * @param ordered `false` — продолжать вставку после первой ошибки.
     */
    public suspend fun insertMany(
        documents: List<T>,
        ordered: Boolean = true,
    ): InsertManyResult = CollectionOps.insertMany(client, databaseName, name, documents.map(::toDocument), ordered)

    /**
     * Обновляет **один** документ, подходящий под фильтр.
     *
     * [update] — документ операторов обновления (`{"${'$'}set": …}`), а не агрегационный конвейер:
     * у официального драйвера это разные перегрузки, см. KDoc класса.
     *
     * @param upsert `true` — создать документ, если под фильтр ничего не подошло.
     */
    public suspend fun updateOne(
        filter: Document,
        update: Document,
        upsert: Boolean = false,
    ): UpdateResult = CollectionOps.updateOne(client, databaseName, name, filter, update, upsert)

    /**
     * Обновляет **все** документы, подходящие под фильтр.
     *
     * @param upsert `true` — создать документ, если под фильтр ничего не подошло.
     */
    public suspend fun updateMany(
        filter: Document,
        update: Document,
        upsert: Boolean = false,
    ): UpdateResult = CollectionOps.updateMany(client, databaseName, name, filter, update, upsert)

    /**
     * Заменяет документ целиком.
     *
     * [replacement] — новое содержимое, а не операторы обновления: `$`-ключи здесь отвергнет
     * сервер. У официального драйвера это тоже отдельная операция, а не режим `updateOne`.
     */
    public suspend fun replaceOne(
        filter: Document,
        replacement: T,
        upsert: Boolean = false,
    ): UpdateResult = CollectionOps.replaceOne(client, databaseName, name, filter, toDocument(replacement), upsert)

    /** Удаляет **все** документы, подходящие под фильтр. */
    public suspend fun deleteMany(filter: Document): DeleteResult =
        CollectionOps.deleteMany(client, databaseName, name, filter)

    /**
     * Находит документ, изменяет его и возвращает — до или после изменения.
     *
     * `null` означает, что под фильтр ничего не подошло.
     */
    public suspend fun findOneAndUpdate(
        filter: Document,
        update: Document,
        returnDocument: ReturnDocument = ReturnDocument.BEFORE,
        upsert: Boolean = false,
        sort: Document? = null,
        projection: Document? = null,
    ): T? =
        CollectionOps
            .findOneAndUpdate(client, databaseName, name, filter, update, returnDocument, upsert, sort, projection)
            ?.let(::fromDocument)

    /** То же, но документ заменяется целиком. */
    public suspend fun findOneAndReplace(
        filter: Document,
        replacement: T,
        returnDocument: ReturnDocument = ReturnDocument.BEFORE,
        upsert: Boolean = false,
        sort: Document? = null,
        projection: Document? = null,
    ): T? =
        CollectionOps
            .findOneAndReplace(
                client,
                databaseName,
                name,
                filter,
                toDocument(replacement),
                returnDocument,
                upsert,
                sort,
                projection,
            )?.let(::fromDocument)

    /** Находит документ, удаляет его и возвращает. `null` — ничего не подошло. */
    public suspend fun findOneAndDelete(
        filter: Document,
        sort: Document? = null,
        projection: Document? = null,
    ): T? = CollectionOps.findOneAndDelete(client, databaseName, name, filter, sort, projection)?.let(::fromDocument)

    /**
     * Оценка числа документов по метаданным коллекции.
     *
     * Быстрее [countDocuments], но неточна и фильтр не принимает — так устроена сама операция.
     */
    public suspend fun estimatedDocumentCount(): Long = CollectionOps.estimatedDocumentCount(client, databaseName, name)

    /** Уникальные значения поля среди подходящих документов. */
    public suspend fun distinct(
        field: String,
        filter: Document = BsonDocument(),
    ): List<BsonValue> = CollectionOps.distinct(client, databaseName, name, field, filter)

    /** Удаляет коллекцию целиком. */
    public suspend fun drop(): Unit = CollectionOps.drop(client, databaseName, name)

    /**
     * Переименовывает коллекцию **в той же базе**.
     *
     * @param dropTarget `true` — снести коллекцию с новым именем, если она есть.
     */
    public suspend fun renameCollection(
        newName: String,
        dropTarget: Boolean = false,
    ): Unit = CollectionOps.rename(client, databaseName, name, newName, dropTarget)

    /** Удаляет **один** документ, подходящий под фильтр. */
    public suspend fun deleteOne(filter: Document): DeleteResult =
        CollectionOps.deleteOne(client, databaseName, name, filter)

    /** Считает документы по фильтру. */
    public suspend fun countDocuments(filter: Document = BsonDocument()): Long =
        CollectionOps.countDocuments(client, databaseName, name, filter)

    /**
     * Читает документы по фильтру.
     *
     * Курсор освобождается при любом исходе сбора потока, включая отмену, — см. [CollectionOps].
     *
     * Возвращает [FindFlow] — он **является** `Flow`, поэтому `find().toList()` работает как
     * прежде, а `find().sort(…).limit(…)` добавился сверху (решение Р8).
     */
    public fun find(filter: Document = BsonDocument()): FindFlow<T> =
        FindFlow(
            source = { query, opts -> CollectionOps.find(client, databaseName, name, query, opts).map(::fromDocument) },
            filter = filter,
            opts = BsonDocument(),
        )
}
