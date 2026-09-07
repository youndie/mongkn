package io.github.youndie.mongkn.ext

import io.github.youndie.mongkn.bson.BsonDecoder
import io.github.youndie.mongkn.bson.BsonEncoder
import io.github.youndie.mongkn.bson.BsonValue
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/*
 * Общая часть для сериализаторов, которые пишут не примитив, а собранный BsonValue
 * (см. [InstantAsBsonDateTime], [StringAsBsonObjectId]).
 *
 * Отказ внятный, а не ClassCastException: такие сериализаторы работают только с BSON и в любом
 * другом формате — скажем, в JSON ответа API — применяться не должны. Проверка типа кодировщика
 * и понятное сообщение о несовпадении входят в договор точки расширения.
 */

internal fun bsonEncoderOf(encoder: Encoder): BsonEncoder =
    encoder as? BsonEncoder
        ?: throw SerializationException("mongkn.ext: этот сериализатор пишет BSON-значение и работает только с mongkn")

internal fun bsonDecoderOf(decoder: Decoder): BsonDecoder =
    decoder as? BsonDecoder
        ?: throw SerializationException("mongkn.ext: этот сериализатор читает BSON-значение и работает только с mongkn")

internal fun <T : BsonValue> BsonValue.expect(
    describe: String,
    cast: (BsonValue) -> T?,
): T = cast(this) ?: throw SerializationException("mongkn.ext: ожидался $describe, пришло ${this::class.simpleName}")
