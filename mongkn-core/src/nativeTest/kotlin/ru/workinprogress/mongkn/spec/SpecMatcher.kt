package ru.workinprogress.mongkn.spec

import ru.workinprogress.mongkn.bson.BsonArray
import ru.workinprogress.mongkn.bson.BsonBoolean
import ru.workinprogress.mongkn.bson.BsonDateTime
import ru.workinprogress.mongkn.bson.BsonDocument
import ru.workinprogress.mongkn.bson.BsonDouble
import ru.workinprogress.mongkn.bson.BsonInt32
import ru.workinprogress.mongkn.bson.BsonInt64
import ru.workinprogress.mongkn.bson.BsonNull
import ru.workinprogress.mongkn.bson.BsonObjectId
import ru.workinprogress.mongkn.bson.BsonString
import ru.workinprogress.mongkn.bson.BsonValue

/**
 * Сопоставление по правилам unified test format.
 *
 * Вынесено из [SpecTestRunner] отдельно, чтобы его можно было проверить напрямую: строгость
 * сравнения — это то, от чего зависит ценность всего раннера, и верить ей на слово нельзя
 * (см. [SpecMatcherTest]).
 */
internal object SpecMatcher {

    /**
     * Сравнение по правилам unified test format — в том объёме, который нужен выбранным файлам.
     *
     * @param root лишние поля допускаются только в корне результата операции: там драйвер вправе
     *   вернуть больше, чем перечислено в сценарии. Во вложенных документах и в `outcome`
     *   сравнение строгое.
     */
    fun matches(expected: BsonValue, actual: BsonValue?, root: Boolean = false): Boolean {
        if (expected is BsonDocument && expected.size == 1) {
            val (operator, argument) = expected.entries.single()
            when (operator) {
                "\$\$unsetOrMatches" -> return actual == null || matches(argument, actual, root)
                "\$\$type" -> return matchesType(argument, actual)
                "\$\$exists" -> return (argument as? BsonBoolean)?.value == (actual != null)
            }
        }
        return when (expected) {
            is BsonDocument -> {
                if (actual !is BsonDocument) return false
                if (!expected.entries.all { (key, value) -> matches(value, actual[key]) }) return false
                // Лишние поля разрешены **только** в корне документа результата: там сервер
                // и драйвер вправе добавить своё (`_id`, служебные счётчики). Во вложенных
                // документах лишнее поле — расхождение, и раньше оно проходило незамеченным.
                root || extraKeys(expected, actual).isEmpty()
            }

            is BsonArray -> actual is BsonArray && expected.size == actual.size &&
                expected.values.indices.all { matches(expected[it], actual[it]) }

            else -> expected == actual
        }
    }

    /** Ключи, которые есть в фактическом документе и которых нет в ожидаемом. */
    private fun extraKeys(expected: BsonDocument, actual: BsonDocument): List<String> =
        actual.keys.filterNot { it in expected }

    private fun matchesType(argument: BsonValue, actual: BsonValue?): Boolean {
        val names = when (argument) {
            is BsonString -> listOf(argument.value)
            is BsonArray -> argument.values.filterIsInstance<BsonString>().map { it.value }
            else -> return false
        }
        return names.any { name ->
            when (name) {
                "int" -> actual is BsonInt32
                "long" -> actual is BsonInt64
                "double" -> actual is BsonDouble
                "string" -> actual is BsonString
                "bool" -> actual is BsonBoolean
                "null" -> actual is BsonNull
                "objectId" -> actual is BsonObjectId
                "date" -> actual is BsonDateTime
                "object" -> actual is BsonDocument
                "array" -> actual is BsonArray
                else -> false
            }
        }
    }

}
