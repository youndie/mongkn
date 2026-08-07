package ru.workinprogress.mongkn.bson

import kotlinx.cinterop.Arena
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
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
import mongkn.cinterop.bson_t

/**
 * Читает документ **прямо из курсора** в пользовательский тип, минуя [Document] (M-83).
 *
 * Зачем отдельный декодировщик, если один уже есть. Обычный путь чтения строит из `bson_t`
 * дерево [BsonValue] и только потом разбирает его сериализатором. После M-84 (батчи) это
 * построение осталось **единственным** заметным расходом пути чтения: переснятая лестница
 * даёт 0.66–0.94 мкс на документ из 1.30–1.47, то есть больше половины. Причём дерево
 * выбрасывается сразу после разбора — это чистые аллокации ради посредника.
 *
 * Здесь посредника нет: `decodeString` читает строку из той же позиции итератора, куда
 * встал `decodeElementIndex`.
 *
 * ## Что читается напрямую, а что — по-старому
 *
 * Напрямую — **классы, списки и скаляры**, то есть то, из чего состоят почти все модели
 * хранения. Всё остальное (`Map`, полиморфизм, поля типа [BsonValue], свои сериализаторы через
 * [BsonDecoder]) уходит в [BsonValueDecoder] по уже собранному поддереву.
 *
 * Это не полумера, а осознанная граница. Выигрыш даёт частая форма, а редкая получает **ту же**
 * реализацию, что и до M-83, — то есть проверенную. Плата за откат — построение поддерева, но
 * только его, а не всего документа.
 *
 * ## Владение памятью
 *
 * `bson_iter_t` вложенного уровня обязан жить, пока по нему читают, — то есть дольше вызова,
 * который его завёл. Поэтому итераторы берутся из [Arena], а не из `memScoped`, и арена
 * освобождается один раз в [decodeFromNative], когда документ разобран целиком.
 *
 * Сам `bson_t` при этом принадлежит **курсору**: указатель действителен только до следующего
 * `mongoc_cursor_next`. Отсюда правило вызова: разбирать документ надо до перехода к следующему,
 * и [decodeFromNative] именно так и вызывается.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun <T> decodeFromNative(
    deserializer: DeserializationStrategy<T>,
    document: CPointer<bson_t>,
    serializersModule: SerializersModule = EmptySerializersModule(),
): T {
    val arena = Arena()
    try {
        val iter = arena.alloc<bson_iter_t>()
        check(bson_iter_init(iter.ptr, document)) { "mongkn: bson_iter_init не смог открыть документ" }
        return deserializer.deserialize(NativeRootDecoder(iter.ptr, arena, serializersModule))
    } finally {
        arena.clear()
    }
}

/**
 * Корень: сам ничего не читает, только отдаёт составной декодировщик документа.
 *
 * Отдельный класс нужен потому, что верхний уровень — единственное место, где итератор уже
 * открыт, а не получен рекурсией.
 */
@OptIn(ExperimentalForeignApi::class)
private class NativeRootDecoder(
    private val iter: CPointer<bson_iter_t>,
    private val arena: Arena,
    override val serializersModule: SerializersModule,
) : AbstractDecoder() {
    override fun decodeValue(): Any = throw SerializationException("mongkn: на верхнем уровне ожидался документ")

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int =
        error("mongkn: элементы читает составной декодировщик")

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder =
        when (descriptor.kind) {
            StructureKind.CLASS, StructureKind.OBJECT -> NativeDocumentDecoder(iter, arena, serializersModule)

            else -> throw SerializationException(
                "mongkn: корнем документа может быть только класс, получено ${descriptor.kind}",
            )
        }
}

/**
 * Общая часть двух составных декодировщиков: чтение скаляра из текущей позиции итератора.
 *
 * Устройство продиктовано `AbstractDecoder`, ровно как у декодировщика по дереву: его
 * `decode*Element` объявлены `final` и сводятся к `decodeString()` / `decodeInt()` / … **на самом
 * составном декодировщике**. Значит держать позицию должен он, а переопределять надо скалярные
 * методы, а не поэлементные.
 *
 * Правила приведения типов повторяют [BsonValueDecoder] дословно — это условие, а не совпадение:
 * два декодировщика на один формат обязаны читать одинаково, иначе смена пути меняла бы
 * поведение. Проверяется дифференциальным тестом.
 */
@OptIn(ExperimentalForeignApi::class)
private abstract class NativeScalarDecoder(
    protected val iter: CPointer<bson_iter_t>,
    protected val arena: Arena,
) : AbstractDecoder(),
    BsonDecoder {
    private fun type(): UInt = bson_iter_type(iter)

    private fun key(): String = bson_iter_key(iter)?.toKString() ?: "?"

    private fun unexpected(expected: String): Nothing =
        throw SerializationException("mongkn: ожидалось $expected, в поле \"${key()}\" лежит тип ${type()}")

    override fun decodeBsonValue(): BsonValue = memScoped { readValue(iter, key()) }

    override fun decodeValue(): Any =
        when (type()) {
            BSON_TYPE_UTF8 -> decodeString()
            BSON_TYPE_INT32 -> bson_iter_int32(iter)
            BSON_TYPE_INT64 -> bson_iter_int64(iter)
            BSON_TYPE_DOUBLE -> bson_iter_double(iter)
            BSON_TYPE_BOOL -> bson_iter_bool(iter)
            else -> unexpected("скаляр")
        }

    override fun decodeString(): String =
        when (type()) {
            // Длина берётся у libbson, а не через toKString(): та остановилась бы на первом NUL.
            BSON_TYPE_UTF8 -> {
                memScoped {
                    val length = alloc<UIntVar>()
                    val chars = bson_iter_utf8(iter, length.ptr) ?: error("bson_iter_utf8 вернул NULL")
                    chars.readBytes(length.value.toInt()).decodeToString()
                }
            }

            // ObjectId просит себя строкой — см. BsonObjectIdSerializer.
            BSON_TYPE_OID -> {
                (decodeBsonValue() as BsonObjectId).hex
            }

            else -> {
                unexpected("строка")
            }
        }

    override fun decodeInt(): Int =
        when (type()) {
            BSON_TYPE_INT32 -> {
                bson_iter_int32(iter)
            }

            // Сужение int64 → Int молча потеряло бы данные.
            BSON_TYPE_INT64 -> {
                bson_iter_int64(iter).let { wide ->
                    wide.toInt().also {
                        if (it.toLong() != wide) throw SerializationException("mongkn: $wide не помещается в Int")
                    }
                }
            }

            else -> {
                unexpected("целое")
            }
        }

    override fun decodeLong(): Long =
        when (type()) {
            BSON_TYPE_INT64 -> bson_iter_int64(iter)
            BSON_TYPE_INT32 -> bson_iter_int32(iter).toLong()
            BSON_TYPE_DATE_TIME -> bson_iter_date_time(iter)
            else -> unexpected("целое")
        }

    override fun decodeDouble(): Double =
        when (type()) {
            BSON_TYPE_DOUBLE -> bson_iter_double(iter)
            BSON_TYPE_INT32 -> bson_iter_int32(iter).toDouble()
            BSON_TYPE_INT64 -> bson_iter_int64(iter).toDouble()
            else -> unexpected("число")
        }

    override fun decodeFloat(): Float = decodeDouble().toFloat()

    override fun decodeShort(): Short = decodeInt().toShort()

    override fun decodeByte(): Byte = decodeInt().toByte()

    override fun decodeBoolean(): Boolean =
        if (type() == BSON_TYPE_BOOL) bson_iter_bool(iter) else unexpected("boolean")

    override fun decodeChar(): Char =
        decodeString().singleOrNull() ?: throw SerializationException("mongkn: ожидался один символ")

    override fun decodeNotNullMark(): Boolean = type() != BSON_TYPE_NULL

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

    /**
     * Спуск во вложенное значение.
     *
     * Класс и список читаются дальше напрямую; всё прочее — по уже собранному поддереву тем же
     * декодировщиком, что и до M-83. Поддерево строится **только для этого значения**, а не для
     * всего документа.
     */
    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder =
        when (descriptor.kind) {
            StructureKind.CLASS, StructureKind.OBJECT -> NativeDocumentDecoder(recurseHere(), arena, serializersModule)
            StructureKind.LIST -> NativeArrayDecoder(recurseHere(), arena, serializersModule)
            else -> BsonValueDecoder(decodeBsonValue(), serializersModule).beginStructure(descriptor)
        }

    private fun recurseHere(): CPointer<bson_iter_t> {
        val child = arena.alloc<bson_iter_t>()
        check(bson_iter_recurse(iter, child.ptr)) { "mongkn: bson_iter_recurse — вложенное значение повреждено" }
        return child.ptr
    }
}

/** Документ: поля сопоставляются с элементами класса по именам. */
@OptIn(ExperimentalForeignApi::class)
private class NativeDocumentDecoder(
    iter: CPointer<bson_iter_t>,
    arena: Arena,
    override val serializersModule: SerializersModule,
) : NativeScalarDecoder(iter, arena) {
    /**
     * Идёт по документу и отдаёт индекс поля, которое классу известно.
     *
     * Неизвестные поля **пропускаются молча**, и это не поблажка: документ в базе шире модели
     * сплошь и рядом — старые поля, поля чужих версий, `_id` там, где он не объявлен. Падать
     * на них значило бы требовать, чтобы модель описывала базу целиком.
     *
     * Порядок полей в документе не обязан совпадать с порядком в классе, поэтому индекс берётся
     * поиском по имени, а не счётчиком.
     */
    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        while (bson_iter_next(iter)) {
            val key = bson_iter_key(iter)?.toKString() ?: error("mongkn: bson_iter_key вернул NULL")
            val index = descriptor.getElementIndex(key)
            if (index != CompositeDecoder.UNKNOWN_NAME) return index
        }
        return CompositeDecoder.DECODE_DONE
    }
}

/** Массив: ключи `"0"`, `"1"`, … не читаются, позиция считается сама. */
@OptIn(ExperimentalForeignApi::class)
private class NativeArrayDecoder(
    iter: CPointer<bson_iter_t>,
    arena: Arena,
    override val serializersModule: SerializersModule,
) : NativeScalarDecoder(iter, arena) {
    private var index = 0

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int =
        if (bson_iter_next(iter)) index++ else CompositeDecoder.DECODE_DONE
}
