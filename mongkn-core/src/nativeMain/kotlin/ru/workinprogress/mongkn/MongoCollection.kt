package ru.workinprogress.mongkn

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import ru.workinprogress.mongkn.bson.BsonDocument
import ru.workinprogress.mongkn.bson.BsonInt64
import ru.workinprogress.mongkn.bson.BsonValue
import ru.workinprogress.mongkn.bson.Document
import ru.workinprogress.mongkn.bson.decodeFromDocument
import ru.workinprogress.mongkn.bson.decodeFromNative
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
    /**
     * Настройки уровня коллекции, вливаемые в опции **каждой** операции.
     *
     * Пустой документ по умолчанию: мы ничего не навязываем сверх того, что задано строкой
     * подключения. Заполняется методами `with*`, и каждый из них возвращает **копию** —
     * как у официального драйвера, где коллекция неизменяема.
     */
    private val defaults: Document = BsonDocument(),
    /**
     * Сессия, в которой идут операции этой коллекции.
     *
     * Влияет на две вещи сразу: операции уходят на закреплённого за сессией клиента (иначе
     * libmongoc откажется) и получают её `sessionId` в опциях. Второе делается через тот же
     * механизм [opts], что и все остальные настройки, поэтому ни одна операция не понадобилась
     * переписывать под сессии.
     */
    internal val session: ClientSession? = null,
    /**
     * Куда направлять чтение.
     *
     * Отдельным полем, а не ключом [defaults], потому что libmongoc принимает предпочтение
     * **параметром**, а не в документе опций: положенный в опции ключ `readPreference` уезжает
     * на сервер дословно, и тот отвечает «unknown field». Проверено пробой.
     */
    private val readPreference: Document? = null,
) {
    /** Через что идут операции: пул клиента либо закреплённый клиент [session]. */
    private val target = Target(client, session)

    private fun withDefaults(extra: Document): MongoCollection<T> =
        MongoCollection(
            client,
            databaseName,
            name,
            codec,
            BsonDocument(defaults.entries.filterNot { it.first in extra } + extra.entries),
            session,
            readPreference,
        )

    /** Коллекция с заданной гарантией записи — см. [writeConcern]. */
    public fun withWriteConcern(concern: Document): MongoCollection<T> =
        withDefaults(BsonDocument("writeConcern" to concern))

    /** Коллекция с заданной гарантией чтения — см. [readConcern]. */
    public fun withReadConcern(concern: Document): MongoCollection<T> =
        withDefaults(BsonDocument("readConcern" to concern))

    /**
     * Коллекция, читающая с указанным предпочтением — см. [ReadPreference].
     *
     * Влияет только на чтение: запись всегда идёт на первичный узел, это правило сервера.
     * На одноузловом развёртывании [ReadPreferenceMode.SECONDARY] приведёт к неуспеху выбора
     * сервера — вторичных узлов там нет, и это не ошибка настройки, а её следствие.
     */
    public fun withReadPreference(preference: ReadPreference): MongoCollection<T> =
        MongoCollection(client, databaseName, name, codec, defaults, session, preference.describe())

    /**
     * Коллекция с ограничением времени операции на стороне сервера.
     *
     * Не то же самое, что отмена корутины: та не прерывает уже начатый вызов (риск 2),
     * а это указание серверу прекратить работу.
     */
    public fun withTimeout(millis: Long): MongoCollection<T> =
        withDefaults(BsonDocument("maxTimeMS" to BsonInt64(millis)))

    /**
     * Опции операции: принадлежность сессии, настройки коллекции и то, что передали в сам вызов.
     *
     * Порядок важен по возрастанию приоритета — позже перекрывает раньше, — но `sessionId`
     * перекрыть нельзя ничем: он идёт последним. Задать его руками через `options` было бы
     * способом отправить операцию в чужую сессию.
     */
    private fun opts(extra: Document): Document {
        val own = defaults.entries.filterNot { it.first in extra } + extra.entries
        val sessionOpts = session?.opts?.entries.orEmpty()
        return if (own.isEmpty() && sessionOpts.isEmpty()) {
            defaults
        } else {
            BsonDocument(own.filterNot { entry -> sessionOpts.any { it.first == entry.first } } + sessionOpts)
        }
    }

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
    public suspend fun insertOne(
        document: T,
        options: Document = BsonDocument(),
    ): InsertOneResult = CollectionOps.insertOne(target, databaseName, name, toDocument(document), opts(options))

    /**
     * Вставляет несколько документов. Пустой список отвергается до обращения к серверу.
     *
     * @param ordered `false` — продолжать вставку после первой ошибки.
     */
    public suspend fun insertMany(
        documents: List<T>,
        ordered: Boolean = true,
        options: Document = BsonDocument(),
    ): InsertManyResult =
        CollectionOps.insertMany(target, databaseName, name, documents.map(::toDocument), ordered, opts(options))

    /**
     * Выполняет несколько разнородных операций записи одним обращением к серверу.
     *
     * ```
     * collection.bulkWrite(
     *     listOf(
     *         InsertOneModel(Person(name = "Ada", born = 1815)),
     *         UpdateOneModel(document { put("name", "Ada") }, document { put("${'$'}set", …) }),
     *         DeleteManyModel(document { put("born", document { put("${'$'}lt", 1800) }) }),
     *     ),
     * )
     * ```
     *
     * @param ordered `true` — выполнять по порядку и остановиться на первой ошибке; `false` —
     *   продолжать после ошибки, и тогда сервер вправе переставить операции местами.
     *
     * Неуспех — [MongoBulkWriteException]: он наследует [MongoException], поэтому обычный
     * `catch (e: MongoException)` его ловит, но дополнительно несёт счётчики того, что **всё-таки
     * применилось**, и список отказов по позициям запросов. При `ordered = false` это
     * единственный способ узнать, какая часть пакета уже в базе, не перечитывая коллекцию.
     */
    public suspend fun bulkWrite(
        requests: List<WriteModel<T>>,
        ordered: Boolean = true,
        options: Document = BsonDocument(),
    ): BulkWriteResult {
        require(requests.isNotEmpty()) { "bulkWrite: список операций пуст" }
        return CollectionOps.bulkWrite(target, databaseName, name, requests.map(::prepare), ordered, opts(options))
    }

    /**
     * Переводит документы модели в [Document], оставляя остальные операции как есть.
     *
     * Операции без документа коллекции объявлены как `WriteModel<Nothing>`, поэтому годятся
     * в список любого типа и переводить их не нужно.
     */
    private fun prepare(request: WriteModel<T>): WriteModel<Document> =
        when (request) {
            is InsertOneModel<T> -> {
                InsertOneModel(toDocument(request.document))
            }

            is ReplaceOneModel<T> -> {
                ReplaceOneModel(
                    request.filter,
                    toDocument(request.replacement),
                    request.upsert,
                    request.options,
                )
            }

            is UpdateOneModel, is UpdateManyModel, is DeleteOneModel, is DeleteManyModel -> {
                request
            }
        }

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
        options: Document = BsonDocument(),
    ): UpdateResult = CollectionOps.updateOne(target, databaseName, name, filter, update, upsert, opts(options))

    /**
     * Обновляет **все** документы, подходящие под фильтр.
     *
     * @param upsert `true` — создать документ, если под фильтр ничего не подошло.
     */
    public suspend fun updateMany(
        filter: Document,
        update: Document,
        upsert: Boolean = false,
        options: Document = BsonDocument(),
    ): UpdateResult = CollectionOps.updateMany(target, databaseName, name, filter, update, upsert, opts(options))

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
        options: Document = BsonDocument(),
    ): UpdateResult =
        CollectionOps.replaceOne(target, databaseName, name, filter, toDocument(replacement), upsert, opts(options))

    /** Удаляет **все** документы, подходящие под фильтр. */
    public suspend fun deleteMany(
        filter: Document,
        options: Document = BsonDocument(),
    ): DeleteResult = CollectionOps.deleteMany(target, databaseName, name, filter, opts(options))

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
        options: Document = BsonDocument(),
    ): T? =
        CollectionOps
            .findOneAndUpdate(
                target,
                databaseName,
                name,
                filter,
                update,
                returnDocument,
                upsert,
                sort,
                projection,
                opts(options),
            )?.let(::fromDocument)

    /** То же, но документ заменяется целиком. */
    public suspend fun findOneAndReplace(
        filter: Document,
        replacement: T,
        returnDocument: ReturnDocument = ReturnDocument.BEFORE,
        upsert: Boolean = false,
        sort: Document? = null,
        projection: Document? = null,
        options: Document = BsonDocument(),
    ): T? =
        CollectionOps
            .findOneAndReplace(
                target,
                databaseName,
                name,
                filter,
                toDocument(replacement),
                returnDocument,
                upsert,
                sort,
                projection,
                opts(options),
            )?.let(::fromDocument)

    /** Находит документ, удаляет его и возвращает. `null` — ничего не подошло. */
    public suspend fun findOneAndDelete(
        filter: Document,
        sort: Document? = null,
        projection: Document? = null,
        options: Document = BsonDocument(),
    ): T? =
        CollectionOps
            .findOneAndDelete(target, databaseName, name, filter, sort, projection, opts(options))
            ?.let(::fromDocument)

    /**
     * Оценка числа документов по метаданным коллекции.
     *
     * Быстрее [countDocuments], но неточна и фильтр не принимает — так устроена сама операция.
     *
     * Опции и настройки коллекции она принимает наравне с остальными чтениями. До M-80 это было
     * не так: параметра не было вовсе, и `withTimeout`, `withReadConcern`, `withReadPreference`
     * молча не действовали — единственная операция чтения, которая их теряла. Нашлось
     * официальным сценарием `estimatedDocumentCount with maxTimeMS`, который до того пропускался.
     */
    public suspend fun estimatedDocumentCount(options: Document = BsonDocument()): Long =
        CollectionOps.estimatedDocumentCount(target, databaseName, name, opts(options), readPreference)

    /** Уникальные значения поля среди подходящих документов. */
    public suspend fun distinct(
        field: String,
        filter: Document = BsonDocument(),
    ): List<BsonValue> = CollectionOps.distinct(target, databaseName, name, field, filter, readPreference)

    /**
     * Создаёт индекс и возвращает его имя.
     *
     * @param keys `{"поле": 1}` по возрастанию, `-1` по убыванию; значением может быть и строка
     *   (`"text"`, `"2dsphere"`, `"hashed"`).
     * @param options настройки индекса — `unique`, `sparse`, `expireAfterSeconds`, `name`
     *   и прочие ключи команды `createIndexes`.
     */
    public suspend fun createIndex(
        keys: Document,
        options: Document = BsonDocument(),
    ): String = createIndexes(listOf(IndexModel(keys, options))).single()

    /**
     * Создаёт несколько индексов за один заход и возвращает их имена в том же порядке.
     *
     * Индексы с одинаковыми ключами, но разными опциями сервер считает конфликтом и отвергает
     * весь вызов целиком: частичного успеха здесь не бывает.
     */
    public suspend fun createIndexes(
        indexes: List<IndexModel>,
        options: Document = BsonDocument(),
    ): List<String> = CollectionOps.createIndexes(target, databaseName, name, indexes, opts(options))

    /** Удаляет индекс по имени. */
    public suspend fun dropIndex(
        indexName: String,
        options: Document = BsonDocument(),
    ): Unit = CollectionOps.dropIndex(target, databaseName, name, indexName, opts(options))

    /**
     * Удаляет индекс по его ключам.
     *
     * Имя выводится из ключей по правилу сервера. Если индекс создавался с явным `name`,
     * этот вызов его **не найдёт** — удаляйте по имени.
     */
    public suspend fun dropIndexByKeys(
        keys: Document,
        options: Document = BsonDocument(),
    ): Unit = dropIndex(CollectionOps.defaultIndexName(keys), options)

    /**
     * Удаляет все индексы коллекции, кроме обязательного по `_id`.
     *
     * Его снять нельзя — это ограничение сервера, а не наше.
     */
    public suspend fun dropIndexes(options: Document = BsonDocument()): Unit = dropIndex("*", options)

    /** Перечисляет индексы коллекции — по документу на каждый, как их отдаёт сервер. */
    public fun listIndexes(options: Document = BsonDocument()): Flow<Document> =
        CollectionOps.listIndexes(target, databaseName, name, opts(options))

    /**
     * Подписка на изменения коллекции.
     *
     * Возвращает **бесконечный** поток: он ждёт следующего события, пока его не отменят.
     * `toList()` на нём почти всегда ошибка — см. [ChangeStreamFlow].
     *
     * Требует replica set: на standalone-сервере сервер откажет. Каждая подписка занимает
     * собственный поток на всё своё время — это цена блокирующего C-API, а не наш выбор.
     *
     * Элементы потока — **события**, а не документы коллекции, поэтому [Document], а не [T]:
     * событие описывает изменение (`operationType`, `documentKey`, `fullDocument`), и типом
     * коллекции не является.
     */
    public fun watch(pipeline: List<Document> = emptyList()): ChangeStreamFlow<Document> =
        ChangeStreamFlow(
            source = { stages, options ->
                CollectionOps.watch(target, databaseName, name, stages, options)
            },
            pipeline = pipeline,
            opts = opts(BsonDocument()),
        )

    /** Удаляет коллекцию целиком. */
    public suspend fun drop(): Unit = CollectionOps.drop(target, databaseName, name)

    /**
     * Переименовывает коллекцию **в той же базе**.
     *
     * @param dropTarget `true` — снести коллекцию с новым именем, если она есть.
     */
    public suspend fun renameCollection(
        newName: String,
        dropTarget: Boolean = false,
    ): Unit = CollectionOps.rename(target, databaseName, name, newName, dropTarget)

    /** Удаляет **один** документ, подходящий под фильтр. */
    public suspend fun deleteOne(
        filter: Document,
        options: Document = BsonDocument(),
    ): DeleteResult = CollectionOps.deleteOne(target, databaseName, name, filter, opts(options))

    /**
     * Считает документы по фильтру.
     *
     * Параметр опций появился в M-40 — последняя операция, которая его не имела. Через него
     * проходит всё, что libmongoc берёт документом опций: `comment`, `hint`, `collation`,
     * `maxTimeMS`. До этого сюда нельзя было передать даже комментарий, хотя соседние операции
     * его принимали.
     */
    public suspend fun countDocuments(
        filter: Document = BsonDocument(),
        options: Document = BsonDocument(),
    ): Long = CollectionOps.countDocuments(target, databaseName, name, filter, opts(options), readPreference)

    /**
     * Выполняет агрегационный конвейер.
     *
     * Результат типизирован тем же [T], что и коллекция, — как у официального драйвера.
     * Это удобно для конвейеров, которые документ фильтруют (`${'$'}match`, `${'$'}sort`), и неверно
     * для тех, что его перестраивают (`${'$'}group`, `${'$'}project`): под них берите перегрузку
     * с сериализатором или коллекцию `Document`.
     */
    public fun aggregate(pipeline: List<Document>): AggregateFlow<T> =
        AggregateFlow(
            source = { stages, opts ->
                CollectionOps
                    .aggregate(target, databaseName, name, stages, opts, readPreference)
                    .map(::fromDocument)
            },
            pipeline = pipeline,
            opts = opts(BsonDocument()),
        )

    /** То же, но результат отображается в другой класс — обычный случай для `${'$'}group`. */
    public fun <R> aggregate(
        pipeline: List<Document>,
        codec: KSerializer<R>,
    ): AggregateFlow<R> =
        AggregateFlow(
            source = { stages, opts ->
                CollectionOps
                    .aggregate(target, databaseName, name, stages, opts, readPreference)
                    .map { decodeFromDocument(codec, it) }
            },
            pipeline = pipeline,
            opts = opts(BsonDocument()),
        )

    /**
     * Читает документы по фильтру.
     *
     * Курсор освобождается при любом исходе сбора потока, включая отмену, — см. [CollectionOps].
     *
     * Возвращает [FindFlow] — он **является** `Flow`, поэтому `find().toList()` работает как
     * прежде, а `find().sort(…).limit(…)` добавился сверху (решение Р8).
     */
    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    public fun find(filter: Document = BsonDocument()): FindFlow<T> =
        FindFlow(
            source = { query, opts ->
                // Типизированная коллекция читает документ **сразу** в свой тип, минуя Document
                // (M-83): промежуточное дерево тут же выбрасывалось бы, а стоит оно больше
                // половины пути чтения. Коллекции без кодека Document и нужен — она идёт прежним
                // путём и ничего не теряет.
                if (codec == null) {
                    @Suppress("UNCHECKED_CAST")
                    CollectionOps.find(target, databaseName, name, query, opts, readPreference) as Flow<T>
                } else {
                    CollectionOps.findAs(target, databaseName, name, query, opts, readPreference) {
                        decodeFromNative(codec, it)
                    }
                }
            },
            filter = filter,
            opts = opts(BsonDocument()),
        )
}
