package ru.workinprogress.mongkn

import ru.workinprogress.mongkn.bson.BsonDocument
import ru.workinprogress.mongkn.bson.Document
import ru.workinprogress.mongkn.bson.decodeFromDocument
import ru.workinprogress.mongkn.bson.encodeToDocument
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer

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

    /** Вставляет несколько документов. Пустой список отвергается до обращения к серверу. */
    public suspend fun insertMany(documents: List<T>): InsertManyResult =
        CollectionOps.insertMany(client, databaseName, name, documents.map(::toDocument))

    /**
     * Обновляет **один** документ, подходящий под фильтр.
     *
     * [update] — документ операторов обновления (`{"${'$'}set": …}`), а не агрегационный конвейер:
     * у официального драйвера это разные перегрузки, см. KDoc класса.
     */
    public suspend fun updateOne(filter: Document, update: Document): UpdateResult =
        CollectionOps.updateOne(client, databaseName, name, filter, update)

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
     */
    public fun find(filter: Document = BsonDocument()): Flow<T> =
        CollectionOps.find(client, databaseName, name, filter).map(::fromDocument)
}
