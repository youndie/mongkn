package ru.workinprogress.mongkn

import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import ru.workinprogress.mongkn.bson.BsonDocument
import ru.workinprogress.mongkn.bson.Document

/**
 * Логическая ссылка на базу. Собственных C-ресурсов не держит: `mongoc_database_t` привязан
 * к конкретному `mongoc_client_t`, а клиент у нас живёт только на время операции.
 */
public class MongoDatabase internal constructor(
    internal val client: MongoClient,
    public val name: String,
) {
    /** Коллекция документов без маппинга. */
    public fun getCollection(name: String): MongoCollection<Document> =
        MongoCollection(client, this.name, name, codec = null)

    /**
     * Коллекция, отображаемая в `@Serializable`-класс.
     *
     * ```
     * val people = database.getCollection<Person>("people")
     * people.insertOne(Person(name = "Ada", born = 1815))
     * ```
     */
    public inline fun <reified T> getCollection(name: String): MongoCollection<T> = getCollection(name, serializer<T>())

    /** То же, но с явным сериализатором — когда `reified` недоступен. */
    public fun <T> getCollection(
        name: String,
        codec: KSerializer<T>,
    ): MongoCollection<T> = MongoCollection(client, this.name, name, codec)

    /**
     * Выполняет произвольную команду и возвращает ответ сервера.
     *
     * Через неё делается всё, чего нет в типизированном API: индексы, статистика,
     * административные команды. Первый ключ документа — имя команды, и порядок здесь значим
     * (решение Р4 — ровно тот случай, ради которого документ упорядоченный).
     */
    public suspend fun runCommand(command: Document): Document = DatabaseOps.runCommand(client, name, command)

    /** Создаёт коллекцию явно — например capped или с валидатором. */
    public suspend fun createCollection(
        name: String,
        options: Document = BsonDocument(),
    ): Unit = DatabaseOps.createCollection(client, this.name, name, options)

    /** Удаляет базу целиком вместе со всеми коллекциями. */
    public suspend fun drop(): Unit = DatabaseOps.dropDatabase(client, name)

    /**
     * Агрегационный конвейер уровня базы.
     *
     * Нужен стадиям, которым не с чего начинать в коллекции: `${'$'}currentOp`,
     * `${'$'}listLocalSessions`, `${'$'}documents`.
     */
    public fun aggregate(pipeline: List<Document>): AggregateFlow<Document> =
        AggregateFlow(
            source = { stages, opts -> DatabaseOps.aggregate(client, name, stages, opts) },
            pipeline = pipeline,
            opts = BsonDocument(),
        )

    /**
     * Подписка на изменения всех коллекций этой базы.
     *
     * Бесконечный поток со всеми оговорками из [ChangeStreamFlow].
     */
    public fun watch(pipeline: List<Document> = emptyList()): ChangeStreamFlow<Document> =
        ChangeStreamFlow(
            source = { stages, options -> DatabaseOps.watch(client, name, stages, options) },
            pipeline = pipeline,
            opts = BsonDocument(),
        )

    /** Имена коллекций в этой базе. */
    public suspend fun listCollectionNames(): List<String> = DatabaseOps.listCollectionNames(client, name)
}
