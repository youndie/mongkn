package io.github.mongkn.bson

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule

/**
 * Превращает `@Serializable`-значение в [BsonValue].
 *
 * Формат «древесный», как `Json.encodeToJsonElement`: промежуточным представлением служит наш
 * же [BsonValue], а не поток байт. Это единственный разумный выбор здесь — байты всё равно
 * соберёт libbson, и дублировать её работу незачем.
 *
 * Что **не** поддержано и почему: полиморфизм (`sealed`-иерархии с дискриминатором) —
 * для этого нужен договор об имени поля-дискриминатора, а его форма должна совпадать
 * с официальным драйвером, что отдельная сверка; контекстная сериализация — нет сценария.
 * И то и другое роняет сериализацию с внятным сообщением, а не пишет мусор.
 */
@OptIn(ExperimentalSerializationApi::class)
internal class BsonValueEncoder(
    override val serializersModule: SerializersModule = EmptySerializersModule(),
) : AbstractEncoder() {

    /** Куда складывать очередное значение. Меняется при входе в структуру. */
    private var sink: (BsonValue) -> Unit = { result = it }

    private var result: BsonValue = BsonNull

    fun encoded(): BsonValue = result

    override fun encodeValue(value: Any) {
        throw SerializationException("mongkn: тип ${value::class.simpleName} не поддержан в BSON")
    }

    override fun encodeString(value: String) = sink(BsonString(value))
    override fun encodeInt(value: Int) = sink(BsonInt32(value))
    override fun encodeLong(value: Long) = sink(BsonInt64(value))
    override fun encodeDouble(value: Double) = sink(BsonDouble(value))
    override fun encodeBoolean(value: Boolean) = sink(BsonBoolean(value))
    override fun encodeFloat(value: Float) = sink(BsonDouble(value.toDouble()))
    // BSON не различает мелкие целые: и Byte, и Short едут как int32 — так же делает
    // официальный драйвер.
    override fun encodeByte(value: Byte) = sink(BsonInt32(value.toInt()))
    override fun encodeShort(value: Short) = sink(BsonInt32(value.toInt()))
    override fun encodeChar(value: Char) = sink(BsonString(value.toString()))
    override fun encodeNull() = sink(BsonNull)

    /** Enum кладётся именем, а не порядковым номером: номер ломается при правке порядка констант. */
    override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) =
        sink(BsonString(enumDescriptor.getElementName(index)))

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        val parentSink = sink
        return when (descriptor.kind) {
            StructureKind.LIST -> ArrayEncoder(serializersModule) { parentSink(it) }
            StructureKind.MAP -> MapEncoder(serializersModule) { parentSink(it) }
            StructureKind.CLASS, StructureKind.OBJECT ->
                DocumentEncoder(serializersModule) { parentSink(it) }
            is PolymorphicKind -> throw SerializationException(
                "mongkn: полиморфная сериализация не поддержана (${descriptor.serialName}). " +
                    "Форма дискриминатора должна совпадать с официальным драйвером — это отдельная сверка."
            )
            else -> throw SerializationException("mongkn: структура ${descriptor.kind} не поддержана")
        }
    }

    /** Собирает документ из именованных полей. */
    private class DocumentEncoder(
        override val serializersModule: SerializersModule,
        private val onEnd: (BsonValue) -> Unit,
    ) : AbstractEncoder() {

        private val entries = mutableListOf<Pair<String, BsonValue>>()
        private var key: String? = null

        override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean {
            key = descriptor.getElementName(index)
            return true
        }

        override fun encodeValue(value: Any) = put(scalar(value))

        override fun encodeNull() = put(BsonNull)

        override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) =
            put(BsonString(enumDescriptor.getElementName(index)))

        override fun <T> encodeSerializableElement(
            descriptor: SerialDescriptor,
            index: Int,
            serializer: kotlinx.serialization.SerializationStrategy<T>,
            value: T,
        ) {
            key = descriptor.getElementName(index)
            // Значения BsonValue пропускаем через себя без сериализации: иначе ObjectId и прочие
            // наши типы поехали бы как обычные data-классы.
            if (value is BsonValue) put(value) else put(encodeToBsonValue(serializer, value))
        }

        override fun beginStructure(descriptor: SerialDescriptor) =
            error("mongkn: структуры внутри документа идут через encodeSerializableElement")

        private fun put(value: BsonValue) {
            entries += (key ?: error("mongkn: значение без ключа")) to value
        }

        override fun endStructure(descriptor: SerialDescriptor) = onEnd(BsonDocument(entries.toList()))
    }

    /** Собирает массив. */
    private class ArrayEncoder(
        override val serializersModule: SerializersModule,
        private val onEnd: (BsonValue) -> Unit,
    ) : AbstractEncoder() {

        private val values = mutableListOf<BsonValue>()

        override fun encodeValue(value: Any) { values += scalar(value) }
        override fun encodeNull() { values += BsonNull }
        override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) {
            values += BsonString(enumDescriptor.getElementName(index))
        }

        override fun <T> encodeSerializableElement(
            descriptor: SerialDescriptor,
            index: Int,
            serializer: kotlinx.serialization.SerializationStrategy<T>,
            value: T,
        ) {
            values += if (value is BsonValue) value else encodeToBsonValue(serializer, value)
        }

        override fun beginStructure(descriptor: SerialDescriptor) =
            error("mongkn: структуры внутри массива идут через encodeSerializableElement")

        override fun endStructure(descriptor: SerialDescriptor) = onEnd(BsonArray(values.toList()))
    }

    /**
     * Собирает `Map` как документ.
     *
     * Ключи BSON — строки, поэтому нестроковый ключ отвергается сразу: молчаливое приведение
     * к строке сделало бы round-trip невозможным.
     */
    private class MapEncoder(
        override val serializersModule: SerializersModule,
        private val onEnd: (BsonValue) -> Unit,
    ) : AbstractEncoder() {

        private val entries = mutableListOf<Pair<String, BsonValue>>()
        private var key: String? = null

        override fun encodeValue(value: Any) = accept(scalar(value))
        override fun encodeNull() = accept(BsonNull)
        override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) =
            accept(BsonString(enumDescriptor.getElementName(index)))

        override fun <T> encodeSerializableElement(
            descriptor: SerialDescriptor,
            index: Int,
            serializer: kotlinx.serialization.SerializationStrategy<T>,
            value: T,
        ) = accept(if (value is BsonValue) value else encodeToBsonValue(serializer, value))

        override fun beginStructure(descriptor: SerialDescriptor) =
            error("mongkn: структуры внутри Map идут через encodeSerializableElement")

        /** Элементы Map приходят парами: сначала ключ, потом значение. */
        private fun accept(value: BsonValue) {
            val pending = key
            if (pending == null) {
                key = (value as? BsonString)?.value
                    ?: throw SerializationException("mongkn: ключ Map должен быть строкой, получено $value")
            } else {
                entries += pending to value
                key = null
            }
        }

        override fun endStructure(descriptor: SerialDescriptor) = onEnd(BsonDocument(entries.toList()))
    }

    private companion object {
        fun scalar(value: Any): BsonValue = when (value) {
            is String -> BsonString(value)
            is Int -> BsonInt32(value)
            is Long -> BsonInt64(value)
            is Double -> BsonDouble(value)
            is Float -> BsonDouble(value.toDouble())
            is Boolean -> BsonBoolean(value)
            is Byte -> BsonInt32(value.toInt())
            is Short -> BsonInt32(value.toInt())
            is Char -> BsonString(value.toString())
            is BsonValue -> value
            else -> throw SerializationException("mongkn: тип ${value::class.simpleName} не поддержан в BSON")
        }
    }
}

/** Кодирует значение в [BsonValue]. */
internal fun <T> encodeToBsonValue(
    serializer: kotlinx.serialization.SerializationStrategy<T>,
    value: T,
): BsonValue = BsonValueEncoder().also { serializer.serialize(it, value) }.encoded()

/**
 * Кодирует значение в документ.
 *
 * @throws SerializationException если на верхнем уровне получился не документ — например, при
 *   попытке положить в коллекцию список или число.
 */
public fun <T> encodeToDocument(serializer: KSerializer<T>, value: T): Document =
    encodeToBsonValue(serializer, value) as? BsonDocument
        ?: throw SerializationException("mongkn: в коллекцию можно класть только документ, а не скаляр или массив")
