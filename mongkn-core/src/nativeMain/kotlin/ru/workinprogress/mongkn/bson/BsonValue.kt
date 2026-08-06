package ru.workinprogress.mongkn.bson

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Значение BSON.
 *
 * Иерархия намеренно типизированная, а не `Map<String, Any>`: BSON различает int32 и int64,
 * а `ObjectId` и `DateTime` в `Any` вообще не выражаются — без этого `Document → bson_t → Document`
 * не возвращает исходный документ. Обоснование — решение Р4 ресёрча.
 *
 * Покрыты все типы BSON, кроме двух устаревших — `dbpointer` и `code with scope`. Их чтение
 * падает с [UnsupportedBsonTypeException]: первый удалён из спецификации, второй объявлен
 * устаревшим, а полезной нагрузки у обоих столько же, сколько реальных данных с ними.
 */
public sealed interface BsonValue

public data class BsonString(
    public val value: String,
) : BsonValue

public data class BsonInt32(
    public val value: Int,
) : BsonValue

public data class BsonInt64(
    public val value: Long,
) : BsonValue

public data class BsonDouble(
    public val value: Double,
) : BsonValue

public data class BsonBoolean(
    public val value: Boolean,
) : BsonValue

public data object BsonNull : BsonValue

/** Момент времени как число миллисекунд от эпохи — так BSON его и хранит (`int64`). */
public data class BsonDateTime(
    public val epochMillis: Long,
) : BsonValue

/**
 * 12-байтовый ObjectId.
 *
 * Не `data class`: у `ByteArray` равенство ссылочное, а этот тип обязан сравниваться по
 * содержимому — иначе round-trip-тест из M-04 будет ложно падать.
 */
@kotlinx.serialization.Serializable(with = BsonObjectIdSerializer::class)
public class BsonObjectId(
    bytes: ByteArray,
) : BsonValue {
    init {
        require(bytes.size == SIZE) { "ObjectId должен быть $SIZE байт, получено ${bytes.size}" }
    }

    private val bytes: ByteArray = bytes.copyOf()

    public fun toByteArray(): ByteArray = bytes.copyOf()

    /** Каноническое 24-символьное шестнадцатеричное представление. */
    public val hex: String
        get() =
            bytes.joinToString("") { b ->
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

        /** Случайная часть — общая на процесс: см. [generate]. */
        private val processRandom: ByteArray by lazy { Random.nextBytes(5) }

        @OptIn(ExperimentalAtomicApi::class)
        private val counter = AtomicInt(Random.nextInt())

        /**
         * Новый `_id` — то, что официальный драйвер делает конструктором `ObjectId()`.
         *
         * Формат по действующей спецификации MongoDB (ревизия 2020 года): 4 байта — секунды
         * от эпохи, 5 байт — случайное значение, общее на процесс, 3 байта — счётчик, тоже общий
         * и растущий по кругу. Старый вариант с machine id и pid спецификация оставила: он давал
         * коллизии при рестарте процесса на той же машине.
         *
         * Случайные 5 байт фиксируются **один раз**, а не выбираются на каждый вызов: иначе два
         * id, выпущенные в одну секунду, различались бы только счётчиком, и главная защита
         * от совпадения между процессами исчезла бы.
         */
        @OptIn(ExperimentalAtomicApi::class, ExperimentalTime::class)
        public fun generate(): BsonObjectId {
            val bytes = ByteArray(SIZE)

            val epochMillis = Clock.System.now().toEpochMilliseconds()
            val seconds = (epochMillis / 1000).toInt()
            bytes[0] = (seconds ushr 24).toByte()
            bytes[1] = (seconds ushr 16).toByte()
            bytes[2] = (seconds ushr 8).toByte()
            bytes[3] = seconds.toByte()

            processRandom.copyInto(bytes, destinationOffset = 4)

            val count = counter.fetchAndAdd(1) and 0xFFFFFF
            bytes[9] = (count ushr 16).toByte()
            bytes[10] = (count ushr 8).toByte()
            bytes[11] = count.toByte()

            return BsonObjectId(bytes)
        }

        /** Разбирает каноническое 24-символьное представление. */
        public fun parse(hex: String): BsonObjectId {
            require(hex.length == SIZE * 2) { "ObjectId должен быть ${SIZE * 2} символов, получено ${hex.length}" }
            val bytes =
                ByteArray(SIZE) { i ->
                    val hi = HEX.indexOf(hex[i * 2].lowercaseChar())
                    val lo = HEX.indexOf(hex[i * 2 + 1].lowercaseChar())
                    require(hi >= 0 && lo >= 0) { "ObjectId содержит не-шестнадцатеричный символ: $hex" }
                    ((hi shl 4) or lo).toByte()
                }
            return BsonObjectId(bytes)
        }
    }
}

/**
 * Двоичные данные с подтипом.
 *
 * Подтип — не украшение: `0x04` это UUID, `0x00` — просто байты, `0x06` — зашифрованное поле.
 * Потерять его значит превратить UUID в мешок байт, поэтому он часть значения, а не деталь
 * кодирования.
 *
 * Не `data class`: у `ByteArray` равенство ссылочное.
 */
public class BsonBinary(
    public val subtype: UByte,
    bytes: ByteArray,
) : BsonValue {
    private val bytes: ByteArray = bytes.copyOf()

    public fun toByteArray(): ByteArray = bytes.copyOf()

    public val size: Int get() = bytes.size

    override fun equals(other: Any?): Boolean =
        this === other || (other is BsonBinary && subtype == other.subtype && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * subtype.hashCode() + bytes.contentHashCode()

    override fun toString(): String = "BsonBinary(subtype=0x${subtype.toString(16)}, ${bytes.size} байт)"

    public companion object {
        /** Обычные байты. */
        public const val GENERIC: UByte = 0x00u

        /** UUID в каноническом представлении. */
        public const val UUID: UByte = 0x04u

        /** Поле, зашифрованное client-side field level encryption. */
        public const val ENCRYPTED: UByte = 0x06u
    }
}

/**
 * `decimal128` — 128-битное десятичное число.
 *
 * Хранится строкой, а не парой `int64`: libbson сама переводит туда и обратно
 * (`bson_decimal128_from_string` / `_to_string`), а собственная арифметика по 128-битному
 * десятичному формату — отдельная библиотека, которой у Kotlin/Native нет.
 *
 * **Запись приводится к каноническому виду при создании.** Без этого `BsonDecimal128("0.0…01")`
 * и прочитанное обратно `BsonDecimal128("1E-30")` были бы разными значениями при одном и том же
 * числе — ровно это и поймали тесты M-24.
 *
 * @throws IllegalArgumentException если строка не разбирается как decimal128.
 */
public class BsonDecimal128 private constructor(
    public val value: String,
) : BsonValue {
    override fun equals(other: Any?): Boolean = this === other || (other is BsonDecimal128 && value == other.value)

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "BsonDecimal128($value)"

    public companion object {
        public operator fun invoke(text: String): BsonDecimal128 = BsonDecimal128(canonicalDecimal128(text))
    }
}

/**
 * Внутренний тип MongoDB для оплога и репликации: пара «секунды от эпохи» и «счётчик внутри
 * секунды». С [BsonDateTime] не путать — у них разное назначение и разное представление.
 */
public data class BsonTimestamp(
    public val seconds: UInt,
    public val increment: UInt,
) : BsonValue

/** Регулярное выражение: шаблон и флаги (`i`, `m`, `s`, `x`, `u`). */
public data class BsonRegex(
    public val pattern: String,
    public val options: String = "",
) : BsonValue

/** Хранимый JavaScript. */
public data class BsonCode(
    public val code: String,
) : BsonValue

/** Устаревший строковый тип. Читается, потому что встречается в старых коллекциях. */
public data class BsonSymbol(
    public val value: String,
) : BsonValue

/** Устаревший маркер «значение не определено». */
public data object BsonUndefined : BsonValue

/** Меньше любого другого значения при сравнении. Используется в диапазонах и шардировании. */
public data object BsonMinKey : BsonValue

/** Больше любого другого значения при сравнении. */
public data object BsonMaxKey : BsonValue

/** Массив BSON. На проводе это документ с ключами `"0"`, `"1"`, … — см. [BsonCodec]. */
public class BsonArray(
    public val values: List<BsonValue>,
) : BsonValue,
    Iterable<BsonValue> {
    override fun iterator(): Iterator<BsonValue> = values.iterator()

    public val size: Int get() = values.size

    public operator fun get(index: Int): BsonValue = values[index]

    override fun equals(other: Any?): Boolean = this === other || (other is BsonArray && values == other.values)

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
) : BsonValue,
    Iterable<Pair<String, BsonValue>> {
    public constructor(vararg entries: Pair<String, BsonValue>) : this(entries.toList())

    override fun iterator(): Iterator<Pair<String, BsonValue>> = entries.iterator()

    public val size: Int get() = entries.size

    public val keys: List<String> get() = entries.map { it.first }

    public fun isEmpty(): Boolean = entries.isEmpty()

    /** Первое значение с этим ключом или `null`. */
    public operator fun get(key: String): BsonValue? = entries.firstOrNull { it.first == key }?.second

    public operator fun contains(key: String): Boolean = entries.any { it.first == key }

    override fun equals(other: Any?): Boolean = this === other || (other is BsonDocument && entries == other.entries)

    override fun hashCode(): Int = entries.hashCode()

    override fun toString(): String = entries.joinToString(prefix = "{", postfix = "}") { (k, v) -> "$k: $v" }
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

    override fun serialize(
        encoder: kotlinx.serialization.encoding.Encoder,
        value: BsonObjectId,
    ) {
        encoder.encodeString(value.hex)
    }

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): BsonObjectId =
        BsonObjectId.parse(decoder.decodeString())
}
