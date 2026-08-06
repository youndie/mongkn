package ru.workinprogress.mongkn.ext

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.internal.GeneratedSerializer
import ru.workinprogress.mongkn.bson.BsonNull
import ru.workinprogress.mongkn.bson.BsonValue
import ru.workinprogress.mongkn.bson.encodeToBsonValue

/**
 * Кодирует значение фильтра **тем же сериализатором, каким кодируется само поле**.
 *
 * Заведён по отчёту первого потребителя, и находка там была самой дорогой из всех: `eq` кодировал
 * значение по рантайм-типу Kotlin. Для поля с собственным сериализатором — скажем,
 * `@Serializable(with = StringAsBsonObjectId::class) val shopId: String` — это значит, что
 * в фильтр уходит `BsonString`, а в документе лежит `BsonObjectId`. Для MongoDB это **разные
 * типы**, а не «то же значение в другом виде»: условие не совпадает никогда. Запрос при этом
 * не падает и не предупреждает — просто ничего не находит, и `deleteMany` тихо перестаёт удалять.
 *
 * То есть это ровно тот класс тихой пропажи данных, ради которого в [FilterScope] уже был
 * [checkedField] — только на уровне значения, а не имени поля, где защиты не было вообще.
 *
 * ## Откуда берётся сериализатор поля
 *
 * Из `childSerializers()` сгенерированного сериализатора класса. Это `@InternalSerializationApi`,
 * и выбор осознанный: из `SerialDescriptor` сериализатор элемента достать нечем — дескриптор
 * описывает форму, но не хранит того, кто её пишет. Проверено на Kotlin/Native
 * (kotlinx.serialization 1.11): отдаются и пользовательские сериализаторы, и nullable-обёртки.
 *
 * Отсутствие `childSerializers` — не ошибка: так выглядят, например, написанные руками
 * сериализаторы. Тогда кодирование откатывается к [bsonOf], то есть к прежнему поведению.
 */
@PublishedApi
internal class FieldCodec<T>
    @PublishedApi
    internal constructor(
        private val serializer: KSerializer<T>,
    ) {
        @OptIn(InternalSerializationApi::class)
        private val children: Array<out KSerializer<*>>? =
            (serializer as? GeneratedSerializer<*>)?.childSerializers()

        val descriptor get() = serializer.descriptor

        /** Индекс поля по его **serial**-имени или `-1`, если такого поля в классе нет. */
        fun indexOf(name: String): Int = descriptor.elementNames.indexOf(name)

        /**
         * Кодирует значение поля с индексом [index].
         *
         * [strict] разделяет два случая, и разница между ними — статическая гарантия типа:
         *
         * * форма со ссылкой на свойство (`Doc::shopId eq value`) типизирована: `value` объявлен
         *   типом свойства, поэтому сериализатор поля ему заведомо подходит, и ошибка
         *   кодирования — настоящая ошибка, её надо показать;
         * * строковая форма (`"shopId" eq value`) принимает `Any?`, и совпадение типа значения
         *   с типом поля ничем не гарантировано. Там несовпадение — не обязательно ошибка:
         *   `"count" eq 5L` для поля `Int` работал и раньше (MongoDB сравнивает числа между
         *   типами), и ломать такой код правкой ради ObjectId было бы нечестным обменом.
         *   Поэтому нестрогий путь откатывается к [bsonOf] — ровно к прежнему поведению.
         */
        fun encode(
            index: Int,
            value: Any?,
            strict: Boolean,
        ): BsonValue {
            // Готовый BsonValue берём как есть — так же поступает кодировщик документа.
            // Через эту ветку проходят и внутренние вызовы: уже собранное значение не должно
            // проходить сериализатор второй раз.
            if (value is BsonValue) return value
            if (value == null) return BsonNull

            val child = children?.getOrNull(index) ?: return bsonOf(value)

            @Suppress("UNCHECKED_CAST")
            val typed = child as KSerializer<Any?>
            if (strict) return encodeToBsonValue(typed, value)

            // Ловим только несовпадение типа значения с типом поля. Ошибку самого сериализатора
            // (скажем, «в id не hex») глушить нельзя: она говорит о настоящей проблеме
            // и до правки тоже была видна.
            return try {
                encodeToBsonValue(typed, value)
            } catch (_: ClassCastException) {
                bsonOf(value)
            }
        }
    }
