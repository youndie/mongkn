package ru.workinprogress.mongkn.bson

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule

/**
 * Читает `@Serializable`-значение из [BsonValue].
 *
 * Зеркало [BsonValueEncoder]. Числа приводятся мягко: BSON не обещает, что int32 не приедет там,
 * где в классе объявлен `Long`, — сервер и другие драйверы вольны положить любое целочисленное
 * представление. Поэтому [asLong] принимает и int32, и int64, а [asDouble] — ещё и целые.
 * Обратное приведение (int64 → Int с потерей) запрещено: это была бы тихая порча данных.
 */
@OptIn(ExperimentalSerializationApi::class)
internal class BsonValueDecoder(
    private val value: BsonValue,
    override val serializersModule: SerializersModule = EmptySerializersModule(),
) : AbstractDecoder(),
    BsonDecoder {
    /**
     * Отдаёт текущее значение как есть.
     *
     * Свой десериализатор всегда получает **корневой** декодировщик: и документ, и массив
     * отдают сериализуемые элементы, заводя под них новый `BsonValueDecoder`.
     */
    override fun decodeBsonValue(): BsonValue = value

    override fun decodeValue(): Any =
        when (value) {
            is BsonString -> value.value
            is BsonInt32 -> value.value
            is BsonInt64 -> value.value
            is BsonDouble -> value.value
            is BsonBoolean -> value.value
            else -> throw SerializationException("mongkn: $value не скаляр")
        }

/*
 * Строка — и, отдельным случаем, ObjectId.
 *
 * [BsonObjectIdSerializer] описывает себя как строку, поэтому на чтении просит именно её.
 * Отдаём шестнадцатеричную запись, из которой он соберёт обратно тот же ObjectId. Записью
 * при этом занимается не он: [BsonValueEncoder] пропускает [BsonValue] мимо сериализации,
 * чтобы `_id` в базе остался ObjectId, а не текстом.
 */
    override fun decodeString(): String =
        when (value) {
            is BsonString -> value.value
            is BsonObjectId -> value.hex
            else -> throw SerializationException("mongkn: ожидалась строка, получено $value")
        }

    override fun decodeInt(): Int =
        when (value) {
            is BsonInt32 -> {
                value.value
            }

            // Сужение int64 → Int молча потеряло бы данные.
            is BsonInt64 -> {
                value.value.toInt().also {
                    if (it.toLong() != value.value) {
                        throw SerializationException("mongkn: ${value.value} не помещается в Int")
                    }
                }
            }

            else -> {
                throw SerializationException("mongkn: ожидалось целое, получено $value")
            }
        }

    override fun decodeLong(): Long = asLong()

    override fun decodeShort(): Short = decodeInt().toShort()

    override fun decodeByte(): Byte = decodeInt().toByte()

    override fun decodeFloat(): Float = asDouble().toFloat()

    override fun decodeDouble(): Double = asDouble()

    override fun decodeBoolean(): Boolean =
        (value as? BsonBoolean)?.value
            ?: throw SerializationException("mongkn: ожидался boolean, получено $value")

    override fun decodeChar(): Char =
        decodeString().singleOrNull()
            ?: throw SerializationException("mongkn: ожидался один символ, получено $value")

    override fun decodeNotNullMark(): Boolean = value != BsonNull

    override fun decodeNull(): Nothing? = null

    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int {
        val name = decodeString()
        val index = enumDescriptor.getElementIndex(name)
        if (index == CompositeDecoder.UNKNOWN_NAME) {
            throw SerializationException(
                "mongkn: '$name' не входит в ${enumDescriptor.serialName}; " +
                    "известны ${(0 until enumDescriptor.elementsCount).map(enumDescriptor::getElementName)}",
            )
        }
        return index
    }

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int =
        error("mongkn: элементы читает составной декодер")

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder =
        when (descriptor.kind) {
            StructureKind.CLASS, StructureKind.OBJECT -> {
                DocumentDecoder(
                    document =
                        value as? BsonDocument
                            ?: throw SerializationException(
                                "mongkn: ожидался документ для ${descriptor.serialName}, получено $value",
                            ),
                    descriptor = descriptor,
                    serializersModule = serializersModule,
                )
            }

            StructureKind.LIST -> {
                SequenceDecoder(
                    values =
                        (value as? BsonArray)?.values
                            ?: throw SerializationException("mongkn: ожидался массив, получено $value"),
                    serializersModule = serializersModule,
                )
            }

            // Map на проводе — документ; разворачиваем его в плоскую последовательность
            // ключ, значение, ключ, значение — именно так его ждёт kotlinx.serialization.
            StructureKind.MAP -> {
                SequenceDecoder(
                    values =
                        (value as? BsonDocument)?.entries?.flatMap { (key, item) -> listOf(BsonString(key), item) }
                            ?: throw SerializationException("mongkn: ожидался документ для Map, получено $value"),
                    serializersModule = serializersModule,
                    valuesPerEntry = 2,
                )
            }

            is PolymorphicKind -> {
                throw SerializationException(
                    "mongkn: полиморфная десериализация не поддержана (${descriptor.serialName})",
                )
            }

            else -> {
                throw SerializationException("mongkn: структура ${descriptor.kind} не поддержана")
            }
        }

    private fun asLong(): Long =
        when (value) {
            is BsonInt64 -> value.value
            is BsonInt32 -> value.value.toLong()
            is BsonDateTime -> value.epochMillis
            else -> throw SerializationException("mongkn: ожидалось целое, получено $value")
        }

    private fun asDouble(): Double =
        when (value) {
            is BsonDouble -> value.value
            is BsonInt32 -> value.value.toDouble()
            is BsonInt64 -> value.value.toDouble()
            else -> throw SerializationException("mongkn: ожидалось число, получено $value")
        }

    /**
     * Читает документ по описанию класса, сопоставляя поля по именам.
     *
     * Устройство продиктовано `AbstractDecoder`: его `decode*Element` объявлены `final`
     * и сводятся к `decodeString()` / `decodeInt()` / … **на самом составном декодере**.
     * Значит переопределять надо не поэлементные методы, а скалярные, и держать указатель
     * на текущее поле. Первая версия пыталась переопределить `decode*Element` и не собралась.
     */
    private class DocumentDecoder(
        private val document: BsonDocument,
        private val descriptor: SerialDescriptor,
        override val serializersModule: SerializersModule,
    ) : AbstractDecoder(),
        BsonDecoder {
        /** Куда дошёл обход описания. */
        private var scan = 0

        /** Поле, которое читают прямо сейчас. */
        private var current = 0

        override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
            // Поля, которых нет в документе, пропускаем: сработает значение по умолчанию,
            // а если его нет — kotlinx.serialization сам сообщит о недостающем поле.
            while (scan < descriptor.elementsCount) {
                val index = scan++
                if (descriptor.getElementName(index) in document) {
                    current = index
                    return index
                }
            }
            return CompositeDecoder.DECODE_DONE
        }

        override fun decodeString(): String = field().decodeString()

        override fun decodeInt(): Int = field().decodeInt()

        override fun decodeLong(): Long = field().decodeLong()

        override fun decodeDouble(): Double = field().decodeDouble()

        override fun decodeFloat(): Float = field().decodeFloat()

        override fun decodeBoolean(): Boolean = field().decodeBoolean()

        override fun decodeShort(): Short = field().decodeShort()

        override fun decodeByte(): Byte = field().decodeByte()

        override fun decodeChar(): Char = field().decodeChar()

        // decodeNullableSerializableElement в AbstractDecoder тоже final: он опирается
        // на decodeNotNullMark() составного декодера, поэтому достаточно правильно ответить здесь.
        override fun decodeNotNullMark(): Boolean = field().decodeNotNullMark()

        override fun <T> decodeSerializableElement(
            descriptor: SerialDescriptor,
            index: Int,
            deserializer: kotlinx.serialization.DeserializationStrategy<T>,
            previousValue: T?,
        ): T {
            current = index
            return deserializer.deserialize(field())
        }

        /**
         * Отдаёт текущее поле как есть — зеркало `DocumentEncoder.encodeBsonValue`.
         *
         * Нужно ровно для nullable-полей: `decodeNullableSerializableElement` передаёт
         * десериализатору **этот** объект, а не отдельный декодировщик поля.
         */
        override fun decodeBsonValue(): BsonValue = raw()

        private fun raw(): BsonValue = document[descriptor.getElementName(current)] ?: BsonNull

        private fun field(): BsonValueDecoder = BsonValueDecoder(raw(), serializersModule)
    }

    /**
     * Читает плоскую последовательность значений — массив или развёрнутую Map.
     *
     * `decodeSequentially() = true`: длина известна заранее, и тогда фреймворк обходит элементы
     * по индексу, не спрашивая `decodeElementIndex`. Позиция берётся из индекса, а не из
     * собственного счётчика, — так не разъезжаются скалярный и сериализуемый пути.
     */
    private class SequenceDecoder(
        private val values: List<BsonValue>,
        override val serializersModule: SerializersModule,
        /**
         * Сколько элементов [values] приходится на одну «единицу» коллекции.
         *
         * Для массива — один. Для `Map` — **два**: фреймворк ждёт от `decodeCollectionSize`
         * число пар и сам читает вдвое больше элементов. Вернуть ему плоскую длину значит
         * попросить прочитать вдвое больше, чем есть, — ровно так и получался
         * `IndexOutOfBounds: index 4, size 4`.
         */
        private val valuesPerEntry: Int = 1,
    ) : AbstractDecoder(),
        BsonDecoder {
        private var current = 0

        /**
         * Отдаёт текущий элемент и сдвигает позицию — так же, как это делает [next].
         *
         * Позицию двигать обязательно: для nullable-элемента фреймворк зовёт этот путь вместо
         * `decodeSerializableElement`, и без сдвига последовательность встала бы на месте.
         */
        override fun decodeBsonValue(): BsonValue = values[current++]

        override fun decodeSequentially(): Boolean = true

        override fun decodeCollectionSize(descriptor: SerialDescriptor): Int = values.size / valuesPerEntry

        override fun decodeElementIndex(descriptor: SerialDescriptor): Int =
            if (current < values.size) current else CompositeDecoder.DECODE_DONE

        override fun decodeString(): String = next().decodeString()

        override fun decodeInt(): Int = next().decodeInt()

        override fun decodeLong(): Long = next().decodeLong()

        override fun decodeDouble(): Double = next().decodeDouble()

        override fun decodeFloat(): Float = next().decodeFloat()

        override fun decodeBoolean(): Boolean = next().decodeBoolean()

        override fun decodeShort(): Short = next().decodeShort()

        override fun decodeByte(): Byte = next().decodeByte()

        override fun decodeChar(): Char = next().decodeChar()

        // Границу проверяем явно: фреймворк успевает спросить про null уже после того,
        // как все элементы прочитаны, и без проверки это IndexOutOfBounds на пустом месте.
        override fun decodeNotNullMark(): Boolean = current < values.size && values[current] != BsonNull

        override fun <T> decodeSerializableElement(
            descriptor: SerialDescriptor,
            index: Int,
            deserializer: kotlinx.serialization.DeserializationStrategy<T>,
            previousValue: T?,
        ): T = deserializer.deserialize(next())

        private fun next(): BsonValueDecoder = BsonValueDecoder(values[current++], serializersModule)
    }
}

/** Читает значение из документа. */
public fun <T> decodeFromDocument(
    deserializer: KSerializer<T>,
    document: Document,
): T = deserializer.deserialize(BsonValueDecoder(document))
