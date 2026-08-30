package ru.workinprogress.mongkn.bson

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.value
import mongkn.cinterop.bson_mem_restore_vtable
import mongkn.cinterop.bson_mem_set_vtable
import mongkn.cinterop.bson_mem_vtable_t
import platform.posix.calloc
import platform.posix.free
import platform.posix.malloc
import platform.posix.posix_memalign
import platform.posix.realloc
import platform.posix.size_t
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Считающий аллокатор libbson — единственный способ увидеть утечку.
 *
 * `bson_destroy` расставлен руками в каждой операции, и **ни один поведенческий тест утечку
 * не заметит**: документ прочитается, счётчик совпадёт, тест позеленеет. Здесь подменяется
 * сам аллокатор libbson (`bson_mem_set_vtable`), и тогда «сколько выделено — столько
 * освобождено» становится проверяемым утверждением. Риск 3 ресёрча.
 *
 * Ограничение, которое надо понимать: подмена **глобальная на процесс**, а фоновые потоки
 * мониторинга топологии, которые заводит `mongoc_client_pool_t`, тоже аллоцируют через libbson.
 * Поэтому точный баланс имеет смысл проверять только там, где живого [ru.workinprogress.mongkn.MongoClient]
 * нет вовсе — то есть на слое кодека. Для сетевых операций это дало бы мигающий тест, а не
 * проверку.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
object BsonAllocations {
    /** Сколько блоков libbson сейчас держит невозвращёнными. */
    val live: Long get() = liveAllocations.load()

    /**
     * Подменяет аллокатор и возвращает счётчик к нулю.
     *
     * Обязателен парный [restore] в `finally`: без него следующие тесты будут считать чужие
     * аллокации в наш счётчик.
     */
    fun install() {
        liveAllocations.store(0)
        bson_mem_set_vtable(vtable.ptr)
    }

    fun restore() {
        bson_mem_restore_vtable()
    }

    /** Выполняет [block] со счётчиком и возвращает прирост невозвращённых блоков. */
    inline fun delta(block: () -> Unit): Long {
        install()
        try {
            block()
            return live
        } finally {
            restore()
        }
    }

    /**
     * Таблица живёт в native heap, а не в `memScoped`: libbson копирует её себе, но полагаться
     * на это в тесте не хочется — дешевле держать её вечно.
     *
     * `aligned_alloc` заполнять **обязательно**: libbson 2.x его вызывает, и NULL в этом поле
     * означает падение на первом же выравненном выделении, а не мягкую деградацию.
     */
    private val vtable: bson_mem_vtable_t =
        nativeHeap.alloc<bson_mem_vtable_t>().apply {
            malloc = countingMalloc
            calloc = countingCalloc
            realloc = countingRealloc
            free = countingFree
            aligned_alloc = countingAlignedAlloc
        }
}

/**
 * Счётчик top-level и атомарный: `staticCFunction` не умеет захватывать контекст, а звать
 * эти функции будут в том числе не из главного потока.
 */
@OptIn(ExperimentalAtomicApi::class)
private val liveAllocations = AtomicLong(0)

@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
private val countingMalloc =
    staticCFunction { bytes: size_t ->
        malloc(bytes)?.also { liveAllocations.fetchAndAdd(1) }
    }

@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
private val countingCalloc =
    staticCFunction { count: size_t, bytes: size_t ->
        calloc(count, bytes)?.also { liveAllocations.fetchAndAdd(1) }
    }

/**
 * `realloc` — единственный, где учёт неочевиден: с нулевым указателем это выделение,
 * с нулевым размером — освобождение, в остальных случаях число блоков не меняется.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
private val countingRealloc =
    staticCFunction { mem: COpaquePointer?, bytes: size_t ->
        val result = realloc(mem, bytes)
        when {
            mem == null && result != null -> liveAllocations.fetchAndAdd(1)

            // `size_t` уже ULong на этой платформе — `toULong()` здесь ничего не делал.
            mem != null && bytes == 0uL -> liveAllocations.fetchAndAdd(-1)
        }
        result
    }

@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
private val countingFree =
    staticCFunction { mem: COpaquePointer? ->
        // free(NULL) законен и ничего не освобождает — считать его нельзя.
        if (mem != null) {
            liveAllocations.fetchAndAdd(-1)
            free(mem)
        }
    }

@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
private val countingAlignedAlloc =
    staticCFunction { alignment: size_t, bytes: size_t ->
        alignedAlloc(alignment, bytes)?.also { liveAllocations.fetchAndAdd(1) }
    }

/**
 * `aligned_alloc` из C11 на разных платформах ведёт себя по-разному в краевых случаях,
 * а `posix_memalign` доступен везде и освобождается обычным `free`.
 */
@OptIn(ExperimentalForeignApi::class)
private fun alignedAlloc(
    alignment: size_t,
    bytes: size_t,
): COpaquePointer? =
    memScoped {
        val slot = alloc<COpaquePointerVar>()
        if (posix_memalign(slot.ptr, alignment, bytes) == 0) slot.value else null
    }
