package ru.workinprogress.mongkn.bson

/**
 * Значение BSON.
 *
 * Иерархия намеренно типизированная, а не `Map<String, Any>`: BSON различает int32 и int64,
 * а `ObjectId` и `DateTime` в `Any` вообще не выражаются — без этого `Document → bson_t → Document`
 * не возвращает исходный документ. Обоснование — решение Р4 ресёрча.
 *
 * Покрыты типы, нужные MVP. Встретив в ответе сервера что-то ещё (binary, decimal128, regex, code),
 * чтение упадёт с [UnsupportedBsonTypeException] — это осознанная граница прототипа, а не недосмотр.
 */
public sealed interface BsonValue

public data class BsonString(public val value: String) : BsonValue

public data class BsonInt32(public val value: Int) : BsonValue

public data class BsonInt64(public val value: Long) : BsonValue

public data class BsonDouble(public val value: Double) : BsonValue

public data class BsonBoolean(public val value: Boolean) : BsonValue

public data object BsonNull : BsonValue

/** Момент времени как число миллисекунд от эпохи — так BSON его и хранит (`int64`). */
public data class BsonDateTime(public val epochMillis: Long) : BsonValue

/**
 * 12-байтовый ObjectId.
 *
 * Не `data class`: у `ByteArray` равенство ссылочное, а этот тип обязан сравниваться по
 * содержимому — иначе round-trip-тест из M-04 будет ложно падать.
 */
@kotlinx.serialization.Serializable(with = BsonObjectIdSerializer::class)
public class BsonObjectId(bytes: ByteArray) : BsonValue {

    init {
        require(bytes.size == SIZE) { "ObjectId должен быть $SIZE байт, получено ${bytes.size}" }
    }

    private val bytes: ByteArray = bytes.copyOf()

    public fun toByteArray(): ByteArray = bytes.copyOf()

    /** Каноническое 24-символьное шестнадцатеричное представление. */
    public val hex: String
        get() = bytes.joinToString("") { b ->
            val v = b.toInt() and 0xFF
            HEX[v shr 4].toString() + HEX[v and 0x0F]
        }

    override fun equals(other: Any?): Boolean =
        this === other || (other is BsonObjectId && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "BsonObjectId($hex)"

    public companion object {
        public const val SIZE: Int = 12
        private const val HEX: String = "0123456789abcdef"

        /** Разбирает каноническое 24-символьное представление. */
        public fun parse(hex: String): BsonObjectId {
            require(hex.length == SIZE * 2) { "ObjectId должен быть ${SIZE * 2} символов, получено ${hex.length}" }
            val bytes = ByteArray(SIZE) { i ->
                val hi = HEX.indexOf(hex[i * 2].lowercaseChar())
                val lo = HEX.indexOf(hex[i * 2 + 1].lowercaseChar())
                require(hi >= 0 && lo >= 0) { "ObjectId содержит не-шестнадцатеричный символ: $hex" }
                ((hi shl 4) or lo).toByte()
            }
            return BsonObjectId(bytes)
        }
    }
}

/** Массив BSON. На проводе это документ с ключами `"0"`, `"1"`, … — см. [BsonCodec]. */
public class BsonArray(public val values: List<BsonValue>) : BsonValue, Iterable<BsonValue> {

    override fun iterator(): Iterator<BsonValue> = values.iterator()

    public val size: Int get() = values.size

    public operator fun get(index: Int): BsonValue = values[index]

    override fun equals(other: Any?): Boolean =
        this === other || (other is BsonArray && values == other.values)

    override fun hashCode(): Int = values.hashCode()

    override fun toString(): String = values.joinToString(prefix = "[", postfix = "]")
}

/**
 * Документ BSON — **упорядоченный** список пар.
 *
 * Порядок значим: BSON его хранит, и в командах сервера первый ключ определяет саму команду.
 * Поэтому здесь список пар, а не `Map`.
 *
 * Дубликаты ключей формально допустимы в BSON, и конструктор их не запрещает; [get] вернёт
 * первое вхождение.
 */
public class BsonDocument(
    public val entries: List<Pair<String, BsonValue>>,
) : BsonValue, Iterable<Pair<String, BsonValue>> {

    public constructor(vararg entries: Pair<String, BsonValue>) : this(entries.toList())

    override fun iterator(): Iterator<Pair<String, BsonValue>> = entries.iterator()

    public val size: Int get() = entries.size

    public val keys: List<String> get() = entries.map { it.first }

    public fun isEmpty(): Boolean = entries.isEmpty()

    /** Первое значение с этим ключом или `null`. */
    public operator fun get(key: String): BsonValue? = entries.firstOrNull { it.first == key }?.second

    public operator fun contains(key: String): Boolean = entries.any { it.first == key }

    override fun equals(other: Any?): Boolean =
        this === other || (other is BsonDocument && entries == other.entries)

    override fun hashCode(): Int = entries.hashCode()

    override fun toString(): String =
        entries.joinToString(prefix = "{", postfix = "}") { (k, v) -> "$k: $v" }
}

/** Документ MongoDB. Отдельного типа нет — это тот же [BsonDocument]. */
public typealias Document = BsonDocument

/** Значение типа, который прототип пока не умеет читать. */
public class UnsupportedBsonTypeException(
    public val typeCode: UInt,
    key: String,
) : RuntimeException("Тип BSON 0x${typeCode.toString(16)} в поле \"$key\" пока не поддерживается")

/**
 * Сериализатор [BsonObjectId].
 *
 * Нужен прежде всего компилятору: без него `@Serializable`-класс с полем `BsonObjectId`
 * не соберётся, сколько бы наш кодировщик ни умел в рантайме.
 *
 * Описывает себя строкой (шестнадцатеричное представление) — это разумно для JSON и прочих
 * форматов. В BSON строка не используется: [BsonValueEncoder] пропускает значения [BsonValue]
 * мимо сериализации, чтобы `_id` остался настоящим ObjectId, а не превратился в текст.
 * На чтении сериализатор всё-таки работает, и `decodeString()` отдаёт ему шестнадцатеричную
 * запись — асимметрия намеренная и описана в [BsonValueDecoder].
 */
public object BsonObjectIdSerializer : kotlinx.serialization.KSerializer<BsonObjectId> {
    override val descriptor: kotlinx.serialization.descriptors.SerialDescriptor =
        kotlinx.serialization.descriptors.PrimitiveSerialDescriptor(
            "ru.workinprogress.mongkn.bson.BsonObjectId",
            kotlinx.serialization.descriptors.PrimitiveKind.STRING,
        )

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: BsonObjectId) {
        encoder.encodeString(value.hex)
    }

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): BsonObjectId =
        BsonObjectId.parse(decoder.decodeString())
}
