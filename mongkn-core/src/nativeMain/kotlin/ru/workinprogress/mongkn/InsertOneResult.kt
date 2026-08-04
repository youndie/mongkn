package ru.workinprogress.mongkn

import ru.workinprogress.mongkn.bson.BsonValue

/**
 * Результат [MongoCollection.insertOne].
 *
 * Драфт предлагал возвращать `Boolean`, но сервер и так кладёт `insertedId` в ответ
 * (`{ "insertedCount" : 1, "insertedId" : { "$oid" : … } }`, проверено прогоном — ресёрч §1.3),
 * а неуспех здесь всегда исключение, так что `false` был бы недостижим. Решение Р3.
 *
 * @property insertedId `_id` вставленного документа: сгенерированный сервером [BsonValue] типа
 *   `BsonObjectId` либо то значение `_id`, которое задал вызывающий.
 */
public class InsertOneResult(
    public val insertedId: BsonValue,
) {
    override fun toString(): String = "InsertOneResult(insertedId=$insertedId)"
}
