package ru.workinprogress.mongkn.benchmark

import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Минимальная обвязка для замеров.
 *
 * Намеренно простая: это не JMH и не претендует на его место. Того, что JMH делает всерьёз —
 * защиты от выкидывания мёртвого кода, статистики по нескольким форкам, — здесь нет, и выводы
 * из чисел надо делать соответствующие. Что есть: прогрев, несколько итераций, медиана вместо
 * среднего.
 *
 * Медиана, а не среднее, потому что мешает не шум, а редкие выбросы: одна пауза сборщика мусора
 * или планировщика сдвигает среднее и не трогает медиану.
 */
internal object Bench {
    /** Сколько раундов замеряем после прогрева. */
    const val ROUNDS: Int = 5

    /**
     * Прогоняет [body] [rounds] раз по [operations] операций и возвращает медианное время раунда.
     *
     * Прогревочный раунд обязателен и не считается: первый проход оплачивает установку
     * соединения, выбор сервера и ленивую инициализацию, а это не то, что мы измеряем.
     */
    inline fun measure(
        operations: Int,
        rounds: Int = ROUNDS,
        body: (Int) -> Unit,
    ): Result {
        body(operations)
        val samples =
            (0 until rounds).map {
                val start = TimeSource.Monotonic.markNow()
                body(operations)
                start.elapsedNow()
            }
        return Result(operations, samples.sorted()[rounds / 2], samples.min(), samples.max())
    }

    internal class Result(
        val operations: Int,
        val median: Duration,
        val best: Duration,
        val worst: Duration,
    ) {
        /** Микросекунд на операцию по медиане. */
        val perOperation: Double get() = median.inWholeMicroseconds.toDouble() / operations

        override fun toString(): String =
            "${format(perOperation)} мкс/оп (медиана ${median.inWholeMilliseconds} мс, " +
                "разброс ${best.inWholeMilliseconds}–${worst.inWholeMilliseconds} мс)"
    }

    /** Печатает строку сравнения двух замеров с надбавкой в процентах. */
    fun compare(
        title: String,
        baselineName: String,
        baseline: Result,
        subjectName: String,
        subject: Result,
    ) {
        val overhead = (subject.perOperation - baseline.perOperation) / baseline.perOperation * 100
        println("  $title")
        println("    $baselineName: $baseline")
        println("    $subjectName: $subject")
        println(
            "    надбавка: ${format(subject.perOperation - baseline.perOperation)} мкс/оп " +
                "(${format(overhead)} %)",
        )
    }

    fun format(value: Double): String {
        val rounded = (value * 100).toLong() / 100.0
        return rounded.toString()
    }

    fun section(title: String) {
        println()
        println(title)
    }
}
