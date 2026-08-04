package io.github.mongkn

/**
 * Логическая ссылка на базу. Собственных C-ресурсов не держит: `mongoc_database_t` привязан
 * к конкретному `mongoc_client_t`, а клиент у нас живёт только на время операции.
 */
public class MongoDatabase internal constructor(
    internal val client: MongoClient,
    public val name: String,
) {
    public fun getCollection(name: String): MongoCollection = MongoCollection(client, this.name, name)
}
