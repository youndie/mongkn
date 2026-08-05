package ru.workinprogress.mongkn.bson

import kotlinx.cinterop.ExperimentalForeignApi
import mongkn.cinterop.bson_destroy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Проверка отсутствия утечек в кодеке BSON (M-06, риск 3).
 *
 * Единственный класс багов, который не ловится **ничем** остальным: документ прочитается,
 * значения совпадут, поведенческий тест позеленеет — а память останется. Здесь подменён
 * аллокатор libbson, см. [BsonAllocations].
 *
 * Сетевые операции сюда не входят намеренно: `mongoc_client_pool_t` заводит фоновые потоки
 * мониторинга, которые аллоцируют через ту же libbson, и точный баланс превратился бы
 * в мигающий тест. Проверяется слой, где живого клиента нет.
 */
@OptIn(ExperimentalForeignApi::class)
class BsonLeakTest {
    private val sample: Document =
        document {
            put("string", "kotlin-native")
            put("int32", 42)
            put("int64", 9_000_000_000L)
            put("double", 3.5)
            put("bool", true)
            putNull("nothing")
            put("oid", BsonObjectId.parse("6a71efcbb173221a58058212"))
            put("when", BsonDateTime(1_700_000_000_000L))
            putDocument("nested") {
                put("a", 1)
                putDocument("deeper") { put("b", "two") }
            }
            putArray("array") {
                add(1)
                add("two")
                addDocument { put("three", true) }
                addArray { add(4L) }
            }
        }

    @Test
    fun `the counter itself notices a deliberate leak`() {
        // Без этой проверки все остальные тесты в файле ничего не стоят: счётчик, который
        // никогда не растёт, показывает ноль и на исправном, и на дырявом коде.
        val leaked =
            BsonAllocations.delta {
                repeat(10) { sample.toNativeBson() } // bson_destroy намеренно не зовём
            }

        assertTrue(leaked >= 10, "счётчик не заметил 10 невозвращённых документов: $leaked")
    }

    @Test
    fun `round trip returns every allocation`() {
        val leaked =
            BsonAllocations.delta {
                repeat(100) {
                    val native = sample.toNativeBson()
                    try {
                        native.toDocument()
                    } finally {
                        bson_destroy(native)
                    }
                }
            }

        assertEquals(0L, leaked, "кодек не вернул $leaked блоков за 100 проходов")
    }

    @Test
    fun `empty and deeply nested documents leak nothing`() {
        val deep =
            document {
                putDocument("l1") {
                    putDocument("l2") {
                        putDocument("l3") {
                            putArray("l4") { addDocument { put("bottom", "reached") } }
                        }
                    }
                }
            }

        val leaked =
            BsonAllocations.delta {
                repeat(50) {
                    for (source in listOf(BsonDocument(), deep)) {
                        val native = source.toNativeBson()
                        try {
                            native.toDocument()
                        } finally {
                            bson_destroy(native)
                        }
                    }
                }
            }

        assertEquals(0L, leaked)
    }

    @Test
    fun `a failure midway through building does not leak the partial document`() {
        // Ровно тот путь, о котором говорит риск 3: исключение между bson_new и bson_destroy.
        //
        // Первая версия этого теста ронялась на `BsonObjectId(ByteArray(0))` — и была пустышкой:
        // конструктор бросает при **сборке документа**, до того как кодек вообще начнёт работу,
        // так что ни одного bson_new не случалось. Поэтому исключение впрыскивается там, где оно
        // и должно быть, — в середине обхода, уже после нескольких удачных append.
        val leaked =
            BsonAllocations.delta {
                repeat(50) {
                    assertFailsWith<IllegalStateException> {
                        explodingDocument().toNativeBson()
                    }
                }
            }

        assertEquals(0L, leaked, "путь исключения оставил $leaked блоков")
    }

    /**
     * Документ, обход которого падает внутри **вложенного** документа.
     *
     * Так задеваются оба места очистки сразу: `finally { bson_destroy(child) }` в `withChild`
     * и `catch { bson_destroy(bson); throw }` в `toNativeBson`.
     */
    private fun explodingDocument(): Document =
        BsonDocument(
            listOf(
                "ok" to BsonInt32(1),
                "text" to BsonString("value"),
                "nested" to
                    BsonDocument(
                        ExplodingList(
                            listOf(
                                "alsoOk" to BsonString("yes"),
                                "second" to BsonInt32(2),
                                "never" to BsonInt32(3),
                            ),
                            failAt = 2,
                        ),
                    ),
            ),
        )

    /** Список, обход которого бросает на [failAt]-м элементе. */
    private class ExplodingList(
        private val delegate: List<Pair<String, BsonValue>>,
        private val failAt: Int,
    ) : List<Pair<String, BsonValue>> by delegate {
        override fun iterator(): Iterator<Pair<String, BsonValue>> =
            object : Iterator<Pair<String, BsonValue>> {
                private var index = 0

                override fun hasNext(): Boolean = index < delegate.size

                override fun next(): Pair<String, BsonValue> {
                    check(index != failAt) { "boom на элементе $failAt" }
                    return delegate[index++]
                }
            }
    }
}
