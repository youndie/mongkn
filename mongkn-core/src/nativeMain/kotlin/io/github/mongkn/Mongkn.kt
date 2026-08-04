package io.github.mongkn

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import mongkn.cinterop.bson_get_version
import mongkn.cinterop.mongoc_cleanup
import mongkn.cinterop.mongoc_get_version
import mongkn.cinterop.mongoc_init

/**
 * Глобальная инициализация libmongoc.
 *
 * Жизненный цикл драйвера **одноразовый на процесс**: «Call `mongoc_init()` exactly once at the
 * beginning of your program… Note that `mongoc_init()` does not reinitialize the driver after
 * `mongoc_cleanup()`» — [документация mongoc](https://mongoc.org/libmongoc/current/mongoc_init.html).
 *
 * Первая версия этого объекта считала ссылки и звала `mongoc_cleanup()` на нуле, а `mongoc_init()`
 * — на каждом подъёме с нуля. Это выглядело аккуратно и разваливалось на втором цикле: следующий
 * же сетевой вызов падал в
 * `_mongoc_handshake_freeze(): assertion failed: pthread_mutex_lock ((&gHandshakeLock)) == 0`,
 * потому что глобальный мьютекс драйвера уже уничтожен, а `mongoc_init()` его не восстанавливает.
 *
 * Поэтому здесь автомат без возврата: `NEW → INITIALIZING → READY → SHUT_DOWN`.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
public object Mongkn {

    private const val NEW = 0
    private const val INITIALIZING = 1
    private const val READY = 2
    private const val SHUT_DOWN = 3

    private val state = AtomicInt(NEW)

    /**
     * Инициализирует драйвер, если он ещё не инициализирован. Идемпотентен и безопасен из
     * нескольких потоков. Обычно звать не нужно: [MongoClient] делает это сам.
     *
     * @throws IllegalStateException если [shutdown] уже был вызван — восстановить драйвер нельзя.
     */
    public fun initialize() {
        while (true) {
            when (state.load()) {
                READY -> return
                SHUT_DOWN -> error(
                    "Mongkn.shutdown() уже вызван: libmongoc не поддерживает повторный " +
                        "mongoc_init() после mongoc_cleanup()"
                )
                NEW -> if (state.compareAndSet(NEW, INITIALIZING)) {
                    mongoc_init()
                    state.store(READY)
                    return
                }
                // INITIALIZING: другой поток внутри mongoc_init(), ждём его. Ожидание активное
                // намеренно — инициализация занимает микросекунды и случается один раз.
                else -> Unit
            }
        }
    }

    /**
     * Освобождает глобальные ресурсы драйвера. **Операция необратимая и терминальная**: после неё
     * ни [initialize], ни [MongoClient] работать не будут.
     *
     * Звать имеет смысл только при завершении процесса и только после того, как закрыты все
     * [MongoClient]. Если не позвать вовсе — ничего страшного не случится: ОС заберёт память
     * при выходе. Именно поэтому [MongoClient.close] этого **не** делает.
     */
    public fun shutdown() {
        if (state.compareAndSet(READY, SHUT_DOWN)) {
            mongoc_cleanup()
        }
    }

    /** Версия libmongoc, с которой процесс слинкован в рантайме (не та, с которой компилировались). */
    public val driverVersion: String
        get() = mongoc_get_version()?.toKString() ?: "unknown"

    /** Версия libbson в рантайме. */
    public val bsonVersion: String
        get() = bson_get_version()?.toKString() ?: "unknown"
}
