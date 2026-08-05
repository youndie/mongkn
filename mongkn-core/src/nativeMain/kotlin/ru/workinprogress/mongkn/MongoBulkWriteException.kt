package ru.workinprogress.mongkn

/**
 * Отказ одной операции внутри пакета.
 *
 * @property index позиция операции в списке запросов — та же нумерация, что у
 *   [BulkWriteResult.insertedIds]. По ней и только по ней можно понять, что именно не прошло.
 * @property code код MongoDB (например `11000` — дубликат ключа).
 */
public class BulkWriteError(
    public val index: Int,
    public val code: UInt,
    public val message: String,
) {
    override fun toString(): String = "BulkWriteError(#$index, код $code: $message)"
}

/**
 * Неуспех [MongoCollection.bulkWrite] со счётчиками того, что **всё-таки применилось**.
 *
 * Существует ради `ordered = false`. При неупорядоченном пакете сервер продолжает после ошибки,
 * поэтому «операция не удалась» — неполная правда: часть записей уже в базе. Обычное исключение
 * эту часть теряло, и узнать её можно было только повторным чтением коллекции.
 *
 * ```
 * try {
 *     collection.bulkWrite(requests, ordered = false)
 * } catch (e: MongoBulkWriteException) {
 *     log.warn("применилось ${e.result.insertedCount}, отказов ${e.writeErrors.size}")
 *     e.writeErrors.forEach { log.warn("запрос #${it.index}: ${it.message}") }
 * }
 * ```
 *
 * Наследует [MongoException], поэтому существующий `catch (e: MongoException)` продолжает
 * ловить пакетные ошибки — расширение не меняет поведение того, кто о нём не знает.
 *
 * @property result счётчики применённого: те же поля, что при успехе. `insertedIds` содержит
 *   только удавшиеся вставки.
 * @property writeErrors отказы по отдельным операциям, с позицией каждой в списке запросов.
 */
public class MongoBulkWriteException(
    domain: UInt,
    code: UInt,
    message: String,
    public val result: BulkWriteResult,
    public val writeErrors: List<BulkWriteError>,
) : MongoException(domain, code, message)
