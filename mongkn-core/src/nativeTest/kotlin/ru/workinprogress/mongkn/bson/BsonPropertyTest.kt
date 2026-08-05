package ru.workinprogress.mongkn.bson

import kotlinx.cinterop.ExperimentalForeignApi
import mongkn.cinterop.bson_destroy
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Краевые случаи и случайные документы для кодека (M-32).
 *
 * [BsonRoundTripTest] гоняет значения, подобранные руками, — то есть ровно те, о которых автор
 * подумал. Здесь проверяются те, о которых он обычно не думает: пустые строки, юникод в ключах,
 * `$` и точки, предельные числа, `NaN` и `-0.0`, дубликаты ключей, документ на мегабайты,
 * а поверх — случайная генерация с фиксированным зерном.
 */
@OptIn(ExperimentalForeignApi::class)
class BsonPropertyTest {
    private fun roundTrip(source: BsonDocument): BsonDocument {
        val native = source.toNativeBson()
        try {
            return native.toDocument()
        } finally {
            bson_destroy(native)
        }
    }

    private fun assertSurvives(
        source: BsonDocument,
        hint: String = "",
    ) {
        assertEquals(source, roundTrip(source), hint.ifEmpty { "round-trip изменил документ" })
    }

    @Test
    fun `unusual keys survive`() {
        // BSON хранит ключи как C-строки, но точки и доллары в них допустимы: ограничения
        // на них — правила сервера для отдельных операций, а не свойство формата.
        assertSurvives(
            document {
                put("", "пустой ключ")
                put("with.dot", 1)
                put("\$dollar", 2)
                put("\$", 3)
                put("ключ на кириллице", 4)
                put("emoji 🐘", 5)
                put("with space", 6)
                put("a".repeat(1_000), 7)
            },
        )
    }

    @Test
    fun `unusual string values survive`() {
        assertSurvives(
            document {
                put("empty", "")
                put("spaces", "   ")
                put("newlines", "a\nb\r\nc")
                put("tab", "a\tb")
                put("quote", "он сказал \"да\"")
                put("backslash", "C:\\path\\to")
                put("unicode", "документ ✓ 🐘 ﷽")
                put("surrogate", "\uD83D\uDE00")
                put("long", "x".repeat(100_000))
            },
        )
    }

    @Test
    fun `strings containing NUL survive`() {
        // BSON-строки длиннопрефиксные, поэтому NUL внутри значения формат допускает.
        // Если кодек передаёт длину как -1, libbson посчитает её через strlen и молча обрежет.
        assertSurvives(
            document {
                put("embedded", "a\u0000b")
                put("leading", "\u0000tail")
                put("onlyNul", "\u0000")
            },
        )
    }

    @Test
    fun `a key containing NUL is rejected rather than silently truncated`() {
        // Ключ в BSON — C-строка, NUL в ней не представим. Обрезать молча значило бы записать
        // не тот документ, который просили, без единого признака ошибки.
        val failure =
            assertFailsWith<IllegalArgumentException> {
                BsonDocument("bad\u0000key" to BsonInt32(1)).toNativeBson()
            }
        assertTrue(failure.message!!.contains("NUL"), "message=${failure.message}")
    }

    @Test
    fun `numeric extremes survive`() {
        assertSurvives(
            document {
                put("intMax", Int.MAX_VALUE)
                put("intMin", Int.MIN_VALUE)
                put("longMax", Long.MAX_VALUE)
                put("longMin", Long.MIN_VALUE)
                put("zero", 0)
                put("doubleMax", Double.MAX_VALUE)
                put("doubleMin", Double.MIN_VALUE)
                put("epsilon", 2.220446049250313e-16)
            },
        )
    }

    @Test
    fun `special doubles survive`() {
        val source =
            document {
                put("nan", Double.NaN)
                put("posInf", Double.POSITIVE_INFINITY)
                put("negInf", Double.NEGATIVE_INFINITY)
                put("negZero", -0.0)
                put("posZero", 0.0)
            }

        val result = roundTrip(source)

        // У Kotlin тотальный порядок для Double: NaN равен NaN, а -0.0 не равен 0.0.
        // BSON хранит биты, поэтому обе тонкости обязаны пережить переход.
        assertEquals(BsonDouble(Double.NaN), result["nan"])
        assertEquals(BsonDouble(Double.NEGATIVE_INFINITY), result["negInf"])
        assertEquals(BsonDouble(-0.0), result["negZero"])
        assertNotEquals<BsonValue?>(result["negZero"], result["posZero"])
        assertEquals(source, result)
    }

    @Test
    fun `date time extremes survive`() {
        assertSurvives(
            document {
                put("epoch", BsonDateTime(0))
                put("beforeEpoch", BsonDateTime(-1_000_000_000_000L))
                put("far", BsonDateTime(Long.MAX_VALUE))
                put("farBack", BsonDateTime(Long.MIN_VALUE))
            },
        )
    }

    @Test
    fun `object id extremes survive`() {
        assertSurvives(
            document {
                put("zeroes", BsonObjectId.parse("000000000000000000000000"))
                put("ffff", BsonObjectId.parse("ffffffffffffffffffffffff"))
                put("mixed", BsonObjectId.parse("0123456789abcdef01234567"))
            },
        )
    }

    @Test
    fun `duplicate keys are preserved in order`() {
        // BSON формально допускает повторяющиеся ключи, и наш BsonDocument их не запрещает.
        val source =
            BsonDocument(
                "same" to BsonInt32(1),
                "same" to BsonInt32(2),
                "other" to BsonString("x"),
                "same" to BsonInt32(3),
            )

        val result = roundTrip(source)

        assertEquals(source, result)
        assertEquals(listOf("same", "same", "other", "same"), result.keys)
        assertEquals(BsonInt32(1), result["same"], "get должен отдавать первое вхождение")
    }

    @Test
    fun `wide and deep documents survive`() {
        val wide = BsonDocument((0 until 5_000).map { "key$it" to BsonInt32(it) })
        assertSurvives(wide, "широкий документ")

        // Вложенность растим итеративно, чтобы не упереться в стек при сборке.
        var deep: BsonValue = BsonString("bottom")
        repeat(200) { level -> deep = BsonDocument("level$level" to deep) }
        assertSurvives(BsonDocument("root" to deep), "глубокий документ")
    }

    @Test
    fun `a multi megabyte document survives`() {
        // Заодно прогоняет пути роста буфера в libbson: bson_t перевыделяется по мере записи.
        val chunk = "x".repeat(64 * 1024)
        val big = BsonDocument((0 until 32).map { "chunk$it" to BsonString(chunk) })

        val result = roundTrip(big)

        assertEquals(big, result)
        assertEquals(32, result.size)
    }

    @Test
    fun `randomly generated documents survive`() {
        // Зерно фиксировано: падение обязано воспроизводиться, а не «иногда мигать».
        val random = Random(SEED)

        repeat(300) { attempt ->
            val source = randomDocument(random, depth = 0)
            assertEquals(source, roundTrip(source), "проход $attempt, зерно $SEED")
        }
    }

    @Test
    fun `randomly generated documents leak nothing`() {
        val random = Random(SEED)
        val documents = List(200) { randomDocument(random, depth = 0) }

        val leaked =
            BsonAllocations.delta {
                for (source in documents) {
                    val native = source.toNativeBson()
                    try {
                        native.toDocument()
                    } finally {
                        bson_destroy(native)
                    }
                }
            }

        assertEquals(0L, leaked, "случайные документы оставили $leaked блоков")
    }

    private fun randomDocument(
        random: Random,
        depth: Int,
    ): BsonDocument =
        BsonDocument((0 until random.nextInt(0, 8)).map { randomKey(random) to randomValue(random, depth) })

    private fun randomKey(random: Random): String = KEY_ALPHABET.random(random).toString() + random.nextInt(0, 100)

    private fun randomValue(
        random: Random,
        depth: Int,
    ): BsonValue {
        // Глубину ограничиваем, иначе генератор изредка строит документ на десятки уровней
        // и тест начинает измерять стек, а не кодек.
        val leafOnly = depth >= 3
        return when (random.nextInt(0, if (leafOnly) 8 else 10)) {
            0 -> BsonString(randomString(random))
            1 -> BsonInt32(random.nextInt())
            2 -> BsonInt64(random.nextLong())
            3 -> BsonDouble(random.nextDouble())
            4 -> BsonBoolean(random.nextBoolean())
            5 -> BsonNull
            6 -> BsonDateTime(random.nextLong())
            7 -> BsonObjectId(ByteArray(BsonObjectId.SIZE) { random.nextInt().toByte() })
            8 -> randomDocument(random, depth + 1)
            else -> BsonArray((0 until random.nextInt(0, 5)).map { randomValue(random, depth + 1) })
        }
    }

    private fun randomString(random: Random): String =
        (0 until random.nextInt(0, 12)).map { VALUE_ALPHABET.random(random) }.joinToString("")

    private companion object {
        const val SEED = 20260804

        /** Ключи держим печатаемыми: NUL в ключе BSON не представим — это C-строка. */
        val KEY_ALPHABET: List<Char> = ('a'..'z') + ('А'..'Я') + listOf('.', '$', '_', '-', ' ')

        val VALUE_ALPHABET: List<Char> =
            // NUL здесь не для красоты: на нём кодек однажды молча терял хвост строки,
            // и генератор обязан продолжать это проверять.
            ('a'..'z') + ('0'..'9') + listOf('ё', '✓', '\n', '\t', '"', '\\', ' ', '\u0000')
    }
}
