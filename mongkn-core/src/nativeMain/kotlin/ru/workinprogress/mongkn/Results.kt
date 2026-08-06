package ru.workinprogress.mongkn

import ru.workinprogress.mongkn.bson.BsonValue

/*
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
    /**
     * Запрос нашёл документ или создал его апсертом.
     *
     * Намеренно **не** `wasAcknowledged` из официального драйвера: то свойство отвечает на
     * другой вопрос — подтвердил ли сервер запись вообще (при `w: 0` он не подтверждает,
     * и остальные поля тогда бессмысленны). Здесь речь о результате запроса.
     *
     * Заведено потому, что иначе каждый потребитель пишет
     * `matchedCount > 0 || modifiedCount > 0 || upsertedId != null` заново — и однажды забудет
     * третье слагаемое, а с ним и все апсерты.
     */
    public val matchedAny: Boolean
        get() = matchedCount > 0 || modifiedCount > 0 || upsertedId != null

    override fun toString(): String =
        "UpdateResult(matchedCount=$matchedCount, modifiedCount=$modifiedCount, upsertedId=$upsertedId)"
}

/** Результат `deleteOne`. */
public class DeleteResult(
    public val deletedCount: Long,
) {
    override fun toString(): String = "DeleteResult(deletedCount=$deletedCount)"
}

/**
 * Результат [MongoCollection.bulkWrite].
 *
 * Счётчики читаются из ответа сервера (`nInserted`, `nMatched`, `nModified`, `nRemoved`,
 * `nUpserted`), а вот [insertedIds] считается на нашей стороне: в ответе на bulk их нет вовсе.
 * Причина та же, по которой `_id` генерируется клиентом в `insertOne` и `insertMany` (решение Р3):
 * опираться на `reply` значило бы работать на одной ветке драйвера и терять данные на другой.
 *
 * Ключ обеих карт — **позиция операции в списке запросов**, а не порядковый номер среди
 * вставленных или созданных апсертом. Так же устроен и официальный драйвер: иначе по результату
 * нельзя было бы понять, какой именно запрос что сделал.
 */
public class BulkWriteResult(
    public val insertedCount: Long,
    public val matchedCount: Long,
    public val modifiedCount: Long,
    public val deletedCount: Long,
    public val upsertedCount: Long,
    public val insertedIds: Map<Int, BsonValue>,
    public val upsertedIds: Map<Int, BsonValue>,
) {
    override fun toString(): String =
        "BulkWriteResult(insertedCount=$insertedCount, matchedCount=$matchedCount, " +
            "modifiedCount=$modifiedCount, deletedCount=$deletedCount, upsertedCount=$upsertedCount)"
}
