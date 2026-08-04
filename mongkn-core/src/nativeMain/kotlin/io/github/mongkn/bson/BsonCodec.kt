package io.github.mongkn.bson

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
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
    val ok = when (value) {
        is BsonString -> bson_append_utf8(target, key, -1, value.value, -1)
        is BsonInt32 -> bson_append_int32(target, key, -1, value.value)
        is BsonInt64 -> bson_append_int64(target, key, -1, value.value)
        is BsonDouble -> bson_append_double(target, key, -1, value.value)
        is BsonBoolean -> bson_append_bool(target, key, -1, value.value)
        BsonNull -> bson_append_null(target, key, -1)
        is BsonDateTime -> bson_append_date_time(target, key, -1, value.epochMillis)

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
        BSON_TYPE_UTF8 ->
            BsonString(bson_iter_utf8(iter, null)?.toKString() ?: error("bson_iter_utf8 вернул NULL"))

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
