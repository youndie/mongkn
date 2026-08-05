package ru.workinprogress.mongkn

import ru.workinprogress.mongkn.bson.BsonDocument
import ru.workinprogress.mongkn.bson.Document

/**
 * Одна операция записи внутри [MongoCollection.bulkWrite].
 *
 * Форма — как у `com.mongodb.client.model.WriteModel` официального драйвера. Иерархия
 * запечатана: список поддержанных операций задан сервером (команды `insert`, `update`, `delete`),
 * а не открыт для расширения снаружи.
 *
 * Параметр ковариантен, поэтому операции без документа коллекции — обновления и удаления —
 * объявлены как `WriteModel<Nothing>` и годятся в список любого `bulkWrite`.
 */
public sealed interface WriteModel<out T>

/** Вставить документ. */
public class InsertOneModel<out T>(
    public val document: T,
) : WriteModel<T>

/**
 * Обновить **один** документ операторами обновления.
 *
 * [update] — документ вида `{"${'$'}set": …}`, а не агрегационный конвейер: та же граница,
 * что и у [MongoCollection.updateOne].
 */
public class UpdateOneModel(
    public val filter: Document,
    public val update: Document,
    public val upsert: Boolean = false,
    public val options: Document = BsonDocument(),
) : WriteModel<Nothing>

/** Обновить **все** подходящие документы. */
public class UpdateManyModel(
    public val filter: Document,
    public val update: Document,
    public val upsert: Boolean = false,
    public val options: Document = BsonDocument(),
) : WriteModel<Nothing>

/** Заменить документ целиком. `${'$'}`-ключи в [replacement] сервер отвергнет. */
public class ReplaceOneModel<out T>(
    public val filter: Document,
    public val replacement: T,
    public val upsert: Boolean = false,
    public val options: Document = BsonDocument(),
) : WriteModel<T>

/** Удалить **один** подходящий документ. */
public class DeleteOneModel(
    public val filter: Document,
    public val options: Document = BsonDocument(),
) : WriteModel<Nothing>

/** Удалить **все** подходящие документы. */
public class DeleteManyModel(
    public val filter: Document,
    public val options: Document = BsonDocument(),
) : WriteModel<Nothing>
