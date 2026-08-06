package ru.workinprogress.mongkn.ext

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import ru.workinprogress.mongkn.bson.BsonDateTime
import ru.workinprogress.mongkn.bson.BsonObjectId
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * `_id` и ссылки на другие документы: `ObjectId` на проводе, `String` в коде.
 *
 * Прикладной код обычно уже говорит на `id: String` — это контракт HTTP-роутов и общих модулей,
 * а не деталь хранения. Официальный JVM-драйвер требовал `ObjectId(id)` и `.toHexString()`
 * на каждой границе репозитория; с этим сериализатором граница исчезает, а на проводе всё равно
 * лежит `ObjectId` — то есть уже записанные документы читаются без миграции.
 *
 * `BsonObjectId.parse` отвергает не-hex строку, и опечатка в id ловится до отправки на сервер.
 *
 * **Одного сериализатора мало, чтобы поле стало настоящим `_id`.** Имя поля в BSON mongkn берёт
 * из `descriptor.getElementName`, то есть из имени свойства Kotlin. `id` в `_id` не превращается
 * сам — нужен `@SerialName("_id")`. Без него документ уходит с обычным полем `id`, а `_id`
 * генерирует сама MongoDB: тихо, без ошибки и с посторонним значением.
 *
 * ```kotlin
 * @Serializable
 * data class Doc(
 *     @SerialName("_id") @Serializable(with = StringAsBsonObjectId::class) val id: String,
 * )
 * ```
 */
public object StringAsBsonObjectId : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ru.workinprogress.mongkn.ext.StringAsBsonObjectId", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: String,
    ) {
        bsonEncoderOf(encoder).encodeBsonValue(BsonObjectId.parse(value))
    }

    override fun deserialize(decoder: Decoder): String =
        bsonDecoderOf(decoder).decodeBsonValue().expect("BSON ObjectId") { it as? BsonObjectId }.hex
}

/**
 * Момент времени как **BSON dateTime**, а не как строка или число.
 *
 * Это не вопрос вкуса: TTL-индекс в MongoDB работает только по полю с датой. Запиши туда
 * ISO-строку — индекс не сломается с ошибкой, он просто перестанет удалять документы. Такой
 * отказ не виден ни в логах, ни в тесте, который проверяет только круговой обход значения.
 */
@OptIn(ExperimentalTime::class)
public object InstantAsBsonDateTime : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ru.workinprogress.mongkn.ext.InstantAsBsonDateTime", PrimitiveKind.LONG)

    override fun serialize(
        encoder: Encoder,
        value: Instant,
    ) {
        bsonEncoderOf(encoder).encodeBsonValue(BsonDateTime(value.toEpochMilliseconds()))
    }

    override fun deserialize(decoder: Decoder): Instant =
        Instant.fromEpochMilliseconds(
            bsonDecoderOf(decoder).decodeBsonValue().expect("BSON-дата") { it as? BsonDateTime }.epochMillis,
        )
}
