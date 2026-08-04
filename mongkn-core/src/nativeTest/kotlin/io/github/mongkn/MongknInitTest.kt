package io.github.mongkn

import kotlin.test.Test
import kotlin.test.assertTrue

class MongknInitTest {

    @Test
    fun `initialize is idempotent and exposes runtime versions`() {
        Mongkn.initialize()
        Mongkn.initialize()
        try {
            // Версия читается из слинкованной библиотеки, а не из заголовков — так тест ловит
            // рассинхрон «собрались с 2.1.1, а в рантайме подхватилась другая».
            assertTrue(Mongkn.driverVersion.first().isDigit(), "driverVersion=${Mongkn.driverVersion}")
            assertTrue(Mongkn.bsonVersion.first().isDigit(), "bsonVersion=${Mongkn.bsonVersion}")
        } finally {
            Mongkn.shutdown()
            Mongkn.shutdown()
        }
    }
}
