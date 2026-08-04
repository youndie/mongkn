package io.github.mongkn

import io.github.mongkn.bson.BsonValue

/**
 * Результаты операций записи.
 *
 * Форма — как у официального драйвера (`com.mongodb.client.result.*`), но без полей, которых
 * `libmongoc` не отдаёт в `reply`. Каждое поле здесь читается из ответа сервера, а не считается
 * на нашей стороне.
 */

/** Результат [MongoCollection.insertMany]. */
public class InsertManyResult(
    public val insertedCount: Long,
    public val insertedIds: List<BsonValue>,
) {
    override fun toString(): String = "InsertManyResult(insertedCount=$insertedCount)"
}

/**
 * Результат `updateOne`.
 *
 * @property upsertedId `_id` документа, созданного апсертом, либо `null`, если апсерта не было.
 */
public class UpdateResult(
    public val matchedCount: Long,
    public val modifiedCount: Long,
    public val upsertedId: BsonValue?,
) {
    override fun toString(): String =
        "UpdateResult(matchedCount=$matchedCount, modifiedCount=$modifiedCount, upsertedId=$upsertedId)"
}

/** Результат `deleteOne`. */
public class DeleteResult(
    public val deletedCount: Long,
) {
    override fun toString(): String = "DeleteResult(deletedCount=$deletedCount)"
}
