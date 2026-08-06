package ru.workinprogress.mongkn.bson

import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Кодировщик, умеющий принять готовый [BsonValue].
 *
 * Точный аналог `JsonEncoder.encodeJsonElement` из `kotlinx-serialization-json` и нужен ровно
 * за тем же: чтобы **свой** сериализатор мог отдать значение в родном виде хранилища, а не
 * пересказывать его через примитивы.
 *
 * Без этого тип, у которого в BSON есть точное представление, приходится кодировать окольно.
 * Деньги — самый частый случай: `decimal128` в BSON есть, но добраться до него из обычного
 * `Encoder` нечем, и остаётся либо строка (теряется арифметика на стороне сервера), либо
 * пара «мантисса + порядок» (теряется читаемость документа и совместимость с чужими
 * инструментами).
 *
 * ```
 * object MoneySerializer : KSerializer<Money> {
 *     override val descriptor = PrimitiveSerialDescriptor("Money", PrimitiveKind.STRING)
 *
 *     override fun serialize(encoder: Encoder, value: Money) {
 *         val bson = encoder as? BsonEncoder
 *             ?: throw SerializationException("Money сериализуется только в BSON")
 *         bson.encodeBsonValue(BsonDecimal128(value.toPlainString()))
 *     }
 *
 *     override fun deserialize(decoder: Decoder): Money {
 *         val bson = decoder as? BsonDecoder
 *             ?: throw SerializationException("Money читается только из BSON")
 *         return Money(bson.decodeBsonValue().asDecimalString())
 *     }
 * }
 * ```
 *
 * Проверять тип и падать с внятным сообщением — часть договора, а не перестраховка: тот же
 * сериализатор могут применить к JSON, и молчаливое приведение дало бы данные, которые
 * не читаются обратно.
 *
 * **[descriptor] всё равно нужен, и он не обязан описывать правду.** Формат древесный: сюда
 * приходит готовое значение, а описание используется только фреймворком для обхода. Годится
 * любой примитивный дескриптор — он ни на что в BSON не влияет.
 */
public interface BsonEncoder : Encoder {
    /** Записывает значение как есть, минуя разбор на примитивы. */
    public fun encodeBsonValue(value: BsonValue)
}

/**
 * Декодировщик, отдающий текущее значение как [BsonValue].
 *
 * Обратная половина [BsonEncoder]; аналог `JsonDecoder.decodeJsonElement`. Возвращает ровно то,
 * что лежит в документе, — включая типы, которых в мире примитивов нет: `decimal128`,
 * `ObjectId`, `timestamp`, `binary` с подтипом.
 */
public interface BsonDecoder : Decoder {
    /** Читает текущее значение как есть. */
    public fun decodeBsonValue(): BsonValue
}
