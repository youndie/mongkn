package ru.workinprogress.mongkn

import ru.workinprogress.mongkn.bson.Document
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

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
    public inline fun <reified T> getCollection(name: String): MongoCollection<T> =
        getCollection(name, serializer<T>())

    /** То же, но с явным сериализатором — когда `reified` недоступен. */
    public fun <T> getCollection(name: String, codec: KSerializer<T>): MongoCollection<T> =
        MongoCollection(client, this.name, name, codec)
}
