package io.github.mongkn

import kotlin.test.Test
import kotlin.test.assertTrue

class MongknInitTest {

    /**
     * [Mongkn.shutdown] здесь намеренно не вызывается: он терминальный на весь процесс, а тесты
     * живут в одном процессе — вызов отсюда сломал бы интеграционные тесты, причём в зависимости
     * от порядка запуска. Именно так этот баг и был найден.
     */
    @Test
    fun `initialize is idempotent and exposes runtime versions`() {
        Mongkn.initialize()
        Mongkn.initialize()

        // Версия читается из слинкованной библиотеки, а не из заголовков — так тест ловит
        // рассинхрон «собрались с 2.1.1, а в рантайме подхватилась другая».
        assertTrue(Mongkn.driverVersion.first().isDigit(), "driverVersion=${Mongkn.driverVersion}")
        assertTrue(Mongkn.bsonVersion.first().isDigit(), "bsonVersion=${Mongkn.bsonVersion}")
    }
}
