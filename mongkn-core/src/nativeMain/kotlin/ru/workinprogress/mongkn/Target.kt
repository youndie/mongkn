package ru.workinprogress.mongkn

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineDispatcher
import mongkn.cinterop.mongoc_client_t

/**
 * Откуда операция берёт клиента: из пула или у закреплённой сессии.
 *
 * Появилась ради сессий (M14) и существует, чтобы это различие было **в одном месте**, а не
 * в каждой из двух десятков операций. Разница между режимами не косметическая:
 *
 * | | без сессии | в сессии |
 * |---|---|---|
 * | клиент | берётся из пула на время операции | закреплён за сессией до её закрытия |
 * | разрешение семафора | берётся на операцию | взято сессией один раз |
 * | поток | общий пул потоков клиента | собственный поток сессии |
 * | одновременность | операции идут параллельно | сериализованы мьютексом сессии |
 *
 * Имена методов совпадают с одноимёнными у [MongoClient] намеренно: код операций от появления
 * сессий не изменился ни строкой.
 */
@OptIn(ExperimentalForeignApi::class)
internal class Target
    @PublishedApi
    internal constructor(
        @PublishedApi internal val client: MongoClient,
        @PublishedApi internal val session: ClientSession?,
    ) {
        /** Куда уходят блокирующие вызовы. У сессии — её собственный поток. */
        val dispatcher: CoroutineDispatcher get() = session?.dispatcher ?: client.dispatcher

        /** Полный цикл операции: разрешение, поток, клиент. */
        suspend fun <T> withClient(block: (CPointer<mongoc_client_t>) -> T): T =
            if (session == null) client.withClient(block) else session.onClient(block)

        /**
         * Держит право на клиента всё время [block] — для курсоров и подписок.
         *
         * У сессии разрешение уже взято при её создании, поэтому здесь берётся только её мьютекс:
         * длинный курсор внутри сессии обязан исключать другие её операции, иначе они пошли бы
         * по одному `mongoc_client_t` одновременно.
         */
        suspend fun <T> withPermit(block: suspend () -> T): T =
            if (session == null) client.withPermit(block) else session.withLock(block)

        /** Берёт клиента под уже взятое право. `inline` — внутрь попадает `emit`, а он suspend. */
        inline fun <T> useClient(block: (CPointer<mongoc_client_t>) -> T): T {
            val pinned = session?.handle
            return if (pinned == null) client.useClient(block) else block(pinned)
        }
    }
