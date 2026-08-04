package ru.workinprogress.mongkn.bson

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.cstr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import mongkn.cinterop.BSON_TYPE_BINARY
import mongkn.cinterop.BSON_TYPE_CODE
import mongkn.cinterop.BSON_TYPE_DECIMAL128
import mongkn.cinterop.BSON_TYPE_MAXKEY
import mongkn.cinterop.BSON_TYPE_MINKEY
import mongkn.cinterop.BSON_TYPE_REGEX
import mongkn.cinterop.BSON_TYPE_SYMBOL
import mongkn.cinterop.BSON_TYPE_TIMESTAMP
import mongkn.cinterop.BSON_TYPE_UNDEFINED
import mongkn.cinterop.bson_append_binary
import mongkn.cinterop.bson_append_code
import mongkn.cinterop.bson_append_decimal128
import mongkn.cinterop.bson_append_maxkey
import mongkn.cinterop.bson_append_minkey
import mongkn.cinterop.bson_append_regex
import mongkn.cinterop.bson_append_symbol
import mongkn.cinterop.bson_append_timestamp
import mongkn.cinterop.bson_append_undefined
import mongkn.cinterop.bson_decimal128_from_string
import mongkn.cinterop.bson_decimal128_t
import mongkn.cinterop.bson_decimal128_to_string
import mongkn.cinterop.bson_iter_binary
import mongkn.cinterop.bson_iter_code
import mongkn.cinterop.bson_iter_decimal128
import mongkn.cinterop.bson_iter_regex
import mongkn.cinterop.bson_iter_symbol
import mongkn.cinterop.bson_iter_timestamp
import mongkn.cinterop.bson_append_array
import mongkn.cinterop.bson_append_bool
import mongkn.cinterop.bson_append_date_time
import mongkn.cinterop.bson_append_document
import mongkn.cinterop.bson_append_double
import mongkn.cinterop.bson_append_int32
import mongkn.cinterop.bson_append_int64
import mongkn.cinterop.bson_append_null
import mongkn.cinterop.bson_append_oid
import mongkn.cinterop.bson_append_utf8
import mongkn.cinterop.bson_destroy
import mongkn.cinterop.bson_iter_bool
import mongkn.cinterop.bson_iter_date_time
import mongkn.cinterop.bson_iter_double
import mongkn.cinterop.bson_iter_init
import mongkn.cinterop.bson_iter_int32
import mongkn.cinterop.bson_iter_int64
import mongkn.cinterop.bson_iter_key
import mongkn.cinterop.bson_iter_next
import mongkn.cinterop.bson_iter_oid
import mongkn.cinterop.bson_iter_recurse
import mongkn.cinterop.bson_iter_t
import mongkn.cinterop.bson_iter_type
import mongkn.cinterop.bson_iter_utf8
import mongkn.cinterop.bson_new
import mongkn.cinterop.bson_oid_t
import mongkn.cinterop.bson_t
import mongkn.cinterop.BSON_TYPE_ARRAY
import mongkn.cinterop.BSON_TYPE_BOOL
import mongkn.cinterop.BSON_TYPE_DATE_TIME
import mongkn.cinterop.BSON_TYPE_DOCUMENT
import mongkn.cinterop.BSON_TYPE_DOUBLE
import mongkn.cinterop.BSON_TYPE_INT32
import mongkn.cinterop.BSON_TYPE_INT64
import mongkn.cinterop.BSON_TYPE_NULL
import mongkn.cinterop.BSON_TYPE_OID
import mongkn.cinterop.BSON_TYPE_UTF8

/**
 * Перевод между [BsonValue] и структурами `libbson`.
 *
 * Правило владения: функция, вернувшая `CPointer<bson_t>`, передаёт владение вызывающему —
 * тот обязан вызвать `bson_destroy`. Ни один указатель отсюда не покидает `mongkn-core`.
 */

/**
 * Строит новый `bson_t` из документа. **Владение переходит вызывающему**: обязателен
 * `bson_destroy` в `finally`.
 *
 * При исключении на середине сборки уже созданный `bson_t` освобождается здесь же — иначе
 * это была бы ровно та утечка, о которой говорит риск 3 ресёрча.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun BsonDocument.toNativeBson(): CPointer<bson_t> {
    val bson = bson_new() ?: error("bson_new вернул NULL: не хватило памяти")
    try {
        memScoped { appendEntries(bson, this@toNativeBson) }
    } catch (e: Throwable) {
        bson_destroy(bson)
        throw e
    }
    return bson
}

/** Читает `bson_t` в документ. Указатель остаётся во владении вызывающего. */
@OptIn(ExperimentalForeignApi::class)
internal fun CPointer<bson_t>.toDocument(): BsonDocument = memScoped {
    val iter = alloc<bson_iter_t>()
    check(bson_iter_init(iter.ptr, this@toDocument)) { "bson_iter_init: документ повреждён" }
    BsonDocument(readEntries(iter.ptr))
}

@OptIn(ExperimentalForeignApi::class)
private fun MemScope.appendEntries(target: CPointer<bson_t>, document: BsonDocument) {
    for ((key, value) in document.entries) {
        appendValue(target, key, value)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun MemScope.appendValue(target: CPointer<bson_t>, key: String, value: BsonValue) {
    // key_length = -1 означает «посчитай strlen сам»; то же для длины строкового значения.
    // Ключ в BSON — C-строка, и NUL в ней не представим. Молча обрезать нельзя: пользователь
    // получил бы не тот документ, который просил, без единого признака ошибки.
    require(!key.contains('\u0000')) { "ключ BSON не может содержать NUL: \"$key\"" }

    val ok = when (value) {
        is BsonString -> appendUtf8(target, key, value.value)
        is BsonInt32 -> bson_append_int32(target, key, -1, value.value)
        is BsonInt64 -> bson_append_int64(target, key, -1, value.value)
        is BsonDouble -> bson_append_double(target, key, -1, value.value)
        is BsonBoolean -> bson_append_bool(target, key, -1, value.value)
        BsonNull -> bson_append_null(target, key, -1)
        is BsonDateTime -> bson_append_date_time(target, key, -1, value.epochMillis)
        is BsonTimestamp -> bson_append_timestamp(target, key, -1, value.seconds, value.increment)
        is BsonRegex -> bson_append_regex(target, key, -1, value.pattern, value.options)
        // У bson_append_code нет параметра длины — NUL внутри кода непредставим средствами
        // самой libbson, не только нашими.
        is BsonCode -> bson_append_code(target, key, -1, value.code)
        is BsonSymbol -> bson_append_symbol(target, key, -1, value.value, -1)
        BsonUndefined -> bson_append_undefined(target, key, -1)
        BsonMinKey -> bson_append_minkey(target, key, -1)
        BsonMaxKey -> bson_append_maxkey(target, key, -1)

        is BsonBinary -> {
            val bytes = value.toByteArray()
            if (bytes.isEmpty()) {
                bson_append_binary(target, key, -1, value.subtype.convert(), null, 0u)
            } else {
                bytes.usePinned { pinned ->
                    bson_append_binary(
                        target, key, -1, value.subtype.convert(),
                        pinned.addressOf(0).reinterpret<UByteVar>(), bytes.size.convert(),
                    )
                }
            }
        }

        is BsonDecimal128 -> {
            val decimal = alloc<bson_decimal128_t>()
            require(bson_decimal128_from_string(value.value, decimal.ptr)) {
                "не разбирается как decimal128: \"${'$'}{value.value}\""
            }
            bson_append_decimal128(target, key, -1, decimal.ptr)
        }

        is BsonObjectId -> {
            val oid = alloc<bson_oid_t>()
            val bytes = value.toByteArray()
            for (i in bytes.indices) oid.bytes[i] = bytes[i].toUByte()
            bson_append_oid(target, key, -1, oid.ptr)
        }

        is BsonDocument -> withChild(value.entries) { child ->
            bson_append_document(target, key, -1, child)
        }

        // Массив на проводе — документ с ключами "0", "1", … Строит его libbson не сам,
        // индексы проставляем мы.
        is BsonArray -> withChild(value.values.mapIndexed { i, v -> i.toString() to v }) { child ->
            bson_append_array(target, key, -1, child)
        }
    }
    check(ok) { "bson_append_* отказался добавить поле \"$key\": документ переполнен или ключ некорректен" }
}

/**
 * Приводит запись decimal128 к каноническому виду средствами libbson.
 *
 * Нужно потому, что `0.000000000000000000000000000001` и `1E-30` — одно число, но разные строки,
 * и после round-trip libbson отдаёт второе. Без приведения два равных числа оказывались бы
 * неравными значениями, что заметили тесты M-24.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun canonicalDecimal128(text: String): String = memScoped {
    val decimal = alloc<bson_decimal128_t>()
    require(bson_decimal128_from_string(text, decimal.ptr)) { "не разбирается как decimal128: \"$text\"" }
    // 43 байта — BSON_DECIMAL128_STRING из bson-decimal128.h.
    val buffer = allocArray<ByteVar>(43)
    bson_decimal128_to_string(decimal.ptr, buffer)
    buffer.toKString()
}

/**
 * Кладёт строку с **явной** длиной в байтах.
 *
 * `-1` вместо длины означало бы «посчитай `strlen`», и строка обрезалась бы на первом NUL.
 * BSON-строки длиннопрефиксные, NUL внутри значения формат допускает, — обрезание было
 * молчаливой потерей данных. Найдено property-тестом (M-32).
 */
@OptIn(ExperimentalForeignApi::class)
private fun MemScope.appendUtf8(target: CPointer<bson_t>, key: String, value: String): Boolean {
    val bytes = value.encodeToByteArray()
    // Пустую строку нельзя пинить — addressOf(0) на пустом массиве бросает.
    if (bytes.isEmpty()) return bson_append_utf8(target, key.cstr.getPointer(this), -1, "".cstr.getPointer(this), 0)
    return bytes.usePinned { pinned ->
        bson_append_utf8(target, key.cstr.getPointer(this), -1, pinned.addressOf(0), bytes.size)
    }
}

/** Собирает временный дочерний `bson_t`, отдаёт его в [use] и гарантированно уничтожает. */
@OptIn(ExperimentalForeignApi::class)
private fun MemScope.withChild(
    entries: List<Pair<String, BsonValue>>,
    use: (CPointer<bson_t>) -> Boolean,
): Boolean {
    val child = bson_new() ?: error("bson_new вернул NULL: не хватило памяти")
    try {
        for ((k, v) in entries) appendValue(child, k, v)
        return use(child)
    } finally {
        bson_destroy(child)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun MemScope.readEntries(iter: CPointer<bson_iter_t>): List<Pair<String, BsonValue>> {
    val entries = mutableListOf<Pair<String, BsonValue>>()
    while (bson_iter_next(iter)) {
        val key = bson_iter_key(iter)?.toKString() ?: error("bson_iter_key вернул NULL")
        entries += key to readValue(iter, key)
    }
    return entries
}

@OptIn(ExperimentalForeignApi::class)
private fun MemScope.readValue(iter: CPointer<bson_iter_t>, key: String): BsonValue =
    // `bson_type_t` пришёл из cinterop не Kotlin-енумом, а typealias'ом на UInt с набором
    // top-level-констант: в C-энуме есть BSON_TYPE_MINKEY = 0xFF, и в Kotlin-enum он не лёг.
    // Поэтому здесь `when` по UInt, а не по значениям перечисления.
    when (val type = bson_iter_type(iter)) {
        // Длину берём у libbson, а не через toKString(): та остановилась бы на первом NUL
        // и потеряла хвост — зеркало той же проблемы, что и при записи.
        BSON_TYPE_UTF8 -> {
            val length = alloc<UIntVar>()
            val chars = bson_iter_utf8(iter, length.ptr) ?: error("bson_iter_utf8 вернул NULL")
            BsonString(chars.readBytes(length.value.toInt()).decodeToString())
        }

        BSON_TYPE_INT32 -> BsonInt32(bson_iter_int32(iter))
        BSON_TYPE_INT64 -> BsonInt64(bson_iter_int64(iter))
        BSON_TYPE_DOUBLE -> BsonDouble(bson_iter_double(iter))
        BSON_TYPE_BOOL -> BsonBoolean(bson_iter_bool(iter))
        BSON_TYPE_NULL -> BsonNull
        BSON_TYPE_DATE_TIME -> BsonDateTime(bson_iter_date_time(iter))

        BSON_TYPE_OID -> {
            val oid = bson_iter_oid(iter) ?: error("bson_iter_oid вернул NULL")
            BsonObjectId(oid.pointed.bytes.readBytes(BsonObjectId.SIZE))
        }

        BSON_TYPE_TIMESTAMP -> {
            val seconds = alloc<UIntVar>()
            val increment = alloc<UIntVar>()
            bson_iter_timestamp(iter, seconds.ptr, increment.ptr)
            BsonTimestamp(seconds.value, increment.value)
        }

        BSON_TYPE_REGEX -> {
            val options = allocPointerTo<ByteVar>()
            val pattern = bson_iter_regex(iter, options.ptr) ?: error("bson_iter_regex вернул NULL")
            BsonRegex(pattern.toKString(), options.value?.toKString().orEmpty())
        }

        BSON_TYPE_CODE -> {
            val length = alloc<UIntVar>()
            val code = bson_iter_code(iter, length.ptr) ?: error("bson_iter_code вернул NULL")
            BsonCode(code.readBytes(length.value.toInt()).decodeToString())
        }

        BSON_TYPE_SYMBOL -> {
            val length = alloc<UIntVar>()
            val symbol = bson_iter_symbol(iter, length.ptr) ?: error("bson_iter_symbol вернул NULL")
            BsonSymbol(symbol.readBytes(length.value.toInt()).decodeToString())
        }

        BSON_TYPE_UNDEFINED -> BsonUndefined
        BSON_TYPE_MINKEY -> BsonMinKey
        BSON_TYPE_MAXKEY -> BsonMaxKey

        BSON_TYPE_BINARY -> {
            val subtype = alloc<UIntVar>()
            val length = alloc<UIntVar>()
            val data = allocPointerTo<UByteVar>()
            bson_iter_binary(iter, subtype.ptr.reinterpret(), length.ptr, data.ptr)
            val bytes = data.value?.readBytes(length.value.toInt()) ?: ByteArray(0)
            BsonBinary(subtype.value.toUByte(), bytes)
        }

        BSON_TYPE_DECIMAL128 -> {
            val decimal = alloc<bson_decimal128_t>()
            check(bson_iter_decimal128(iter, decimal.ptr)) { "bson_iter_decimal128 не прочитал значение" }
            // 43 байта — BSON_DECIMAL128_STRING из bson-decimal128.h.
            val buffer = allocArray<ByteVar>(43)
            bson_decimal128_to_string(decimal.ptr, buffer)
            BsonDecimal128(buffer.toKString())
        }

        BSON_TYPE_DOCUMENT -> BsonDocument(readEntries(recurse(iter)))

        // Массив читается тем же обходом, просто ключи "0", "1", … выбрасываются.
        BSON_TYPE_ARRAY -> BsonArray(readEntries(recurse(iter)).map { it.second })

        else -> throw UnsupportedBsonTypeException(type, key)
    }

@OptIn(ExperimentalForeignApi::class)
private fun MemScope.recurse(iter: CPointer<bson_iter_t>): CPointer<bson_iter_t> {
    val child = alloc<bson_iter_t>()
    check(bson_iter_recurse(iter, child.ptr)) { "bson_iter_recurse: вложенное значение повреждено" }
    return child.ptr
}
