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
 * `mongoc_init()` обязан быть вызван ровно один раз на процесс до любого другого вызова драйвера,
 * а `mongoc_cleanup()` — один раз после того, как уничтожены все клиенты и курсоры. Повторный
 * `mongoc_init()` без парного `mongoc_cleanup()` — undefined behaviour, поэтому счётчик здесь
 * атомарный, а не просто `Boolean`: [initialize] безопасно звать из нескольких потоков.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
public object Mongkn {

    private val refCount = AtomicInt(0)

    /**
     * Инициализирует драйвер, если он ещё не инициализирован. Идемпотентен по счётчику ссылок:
     * каждому вызову должен соответствовать один [shutdown].
     */
    public fun initialize() {
        if (refCount.addAndFetch(1) == 1) {
            mongoc_init()
        }
    }

    /**
     * Освобождает глобальные ресурсы драйвера, когда снят последний [initialize].
     *
     * ВАЖНО: вызывать только после того, как уничтожены все клиенты, курсоры и коллекции —
     * libmongoc не отслеживает их за нас.
     */
    public fun shutdown() {
        if (refCount.addAndFetch(-1) == 0) {
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
