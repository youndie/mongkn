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
    fun matches(
        expected: BsonValue,
        actual: BsonValue?,
        root: Boolean = false,
    ): Boolean {
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

            is BsonArray -> {
                actual is BsonArray && expected.size == actual.size &&
                    expected.values.indices.all { matches(expected[it], actual[it]) }
            }

            else -> {
                expected == actual || sameNumber(expected, actual)
            }
        }
    }

    /**
     * Числа сравниваются по значению, а не по типу BSON.
     *
     * Требование unified test format: `Int32`, `Int64` и `Double` считаются равными, если равны
     * их значения. Без этого сценарий на пакетную выборку падал на `batchSize: 2`, отправленном
     * как `int64` вместо ожидаемого `int32`, — то есть на совпадающем значении.
     *
     * Целые сравниваются как целые: перевод в `Double` терял бы точность на больших `int64`,
     * а это ровно тот случай, где ложное совпадение опаснее ложного расхождения.
     */
    private fun sameNumber(
        expected: BsonValue,
        actual: BsonValue?,
    ): Boolean {
        val expectedLong = integerOf(expected)
        val actualLong = integerOf(actual)
        if (expectedLong != null && actualLong != null) return expectedLong == actualLong
        val expectedDouble = numberOf(expected) ?: return false
        val actualDouble = numberOf(actual) ?: return false
        return expectedDouble == actualDouble
    }

    private fun integerOf(value: BsonValue?): Long? =
        when (value) {
            is BsonInt32 -> value.value.toLong()
            is BsonInt64 -> value.value
            else -> null
        }

    private fun numberOf(value: BsonValue?): Double? =
        when (value) {
            is BsonInt32 -> value.value.toDouble()
            is BsonInt64 -> value.value.toDouble()
            is BsonDouble -> value.value
            else -> null
        }

    /** Ключи, которые есть в фактическом документе и которых нет в ожидаемом. */
    private fun extraKeys(
        expected: BsonDocument,
        actual: BsonDocument,
    ): List<String> = actual.keys.filterNot { it in expected }

    private fun matchesType(
        argument: BsonValue,
        actual: BsonValue?,
    ): Boolean {
        val names =
            when (argument) {
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
