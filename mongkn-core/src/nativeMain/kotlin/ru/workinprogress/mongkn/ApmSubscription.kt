package ru.workinprogress.mongkn

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import mongkn.cinterop.bson_error_t
import mongkn.cinterop.mongoc_apm_callbacks_destroy
import mongkn.cinterop.mongoc_apm_callbacks_new
import mongkn.cinterop.mongoc_apm_command_failed_get_command_name
import mongkn.cinterop.mongoc_apm_command_failed_get_context
import mongkn.cinterop.mongoc_apm_command_failed_get_duration
import mongkn.cinterop.mongoc_apm_command_failed_get_error
import mongkn.cinterop.mongoc_apm_command_failed_get_operation_id
import mongkn.cinterop.mongoc_apm_command_failed_get_request_id
import mongkn.cinterop.mongoc_apm_command_started_get_command
import mongkn.cinterop.mongoc_apm_command_started_get_command_name
import mongkn.cinterop.mongoc_apm_command_started_get_context
import mongkn.cinterop.mongoc_apm_command_started_get_database_name
import mongkn.cinterop.mongoc_apm_command_started_get_operation_id
import mongkn.cinterop.mongoc_apm_command_started_get_request_id
import mongkn.cinterop.mongoc_apm_command_succeeded_get_command_name
import mongkn.cinterop.mongoc_apm_command_succeeded_get_context
import mongkn.cinterop.mongoc_apm_command_succeeded_get_duration
import mongkn.cinterop.mongoc_apm_command_succeeded_get_operation_id
import mongkn.cinterop.mongoc_apm_command_succeeded_get_reply
import mongkn.cinterop.mongoc_apm_command_succeeded_get_request_id
import mongkn.cinterop.mongoc_apm_set_command_failed_cb
import mongkn.cinterop.mongoc_apm_set_command_started_cb
import mongkn.cinterop.mongoc_apm_set_command_succeeded_cb
import mongkn.cinterop.mongoc_client_pool_set_apm_callbacks
import mongkn.cinterop.mongoc_client_pool_t
import ru.workinprogress.mongkn.bson.BsonDocument
import ru.workinprogress.mongkn.bson.toDocument

/**
 * Подписка пула на события команд.
 *
 * Три вещи, которые здесь легко сделать неправильно:
 *
 * * **контекст.** Коллбэки C не могут ничего захватывать, поэтому наблюдатель передаётся через
 *   `void *context` пула и достаётся обратно из события. Указатель обязан пережить пул, значит
 *   это [StableRef], и его надо освободить — иначе наблюдатель не соберётся никогда;
 * * **различие версий.** Имя базы доступно только у started-события: у succeeded и failed
 *   `..._get_database_name` в libmongoc 1.26 отсутствует (в 1.30 уже есть). Поймано
 *   двухплатформенным CI, а не чтением заголовков, — ровно ради такого он и держится;
 * * **порядок освобождения.** `mongoc_apm_callbacks_t` копируется пулом, поэтому уничтожается
 *   сразу после установки. А вот `StableRef` живёт до закрытия клиента: события приходят,
 *   пока пул жив;
 * * **исключения.** Коллбэк вызывается из C, и исключение, вылетевшее через границу, — это
 *   крах процесса, а не ошибка операции. Поэтому каждый вызов наблюдателя обёрнут.
 */
@OptIn(ExperimentalForeignApi::class)
internal class ApmSubscription private constructor(
    private val reference: StableRef<CommandListener>,
) {
    fun dispose() {
        reference.dispose()
    }

    companion object {
        fun install(
            pool: CPointer<mongoc_client_pool_t>,
            listener: CommandListener,
        ): ApmSubscription {
            val reference = StableRef.create(listener)
            val callbacks = mongoc_apm_callbacks_new() ?: error("mongoc_apm_callbacks_new вернул NULL")
            try {
                mongoc_apm_set_command_started_cb(
                    callbacks,
                    staticCFunction { event ->
                        val listener =
                            mongoc_apm_command_started_get_context(event).toListener() ?: return@staticCFunction
                        guard {
                            listener.started(
                                CommandStartedEvent(
                                    commandName =
                                        mongoc_apm_command_started_get_command_name(
                                            event,
                                        )?.toKString().orEmpty(),
                                    databaseName =
                                        mongoc_apm_command_started_get_database_name(
                                            event,
                                        )?.toKString().orEmpty(),
                                    command =
                                        mongoc_apm_command_started_get_command(event)?.toDocument() ?: BsonDocument(),
                                    requestId = mongoc_apm_command_started_get_request_id(event),
                                    operationId = mongoc_apm_command_started_get_operation_id(event),
                                ),
                            )
                        }
                    },
                )
                mongoc_apm_set_command_succeeded_cb(
                    callbacks,
                    staticCFunction { event ->
                        val listener =
                            mongoc_apm_command_succeeded_get_context(event).toListener() ?: return@staticCFunction
                        guard {
                            listener.succeeded(
                                CommandSucceededEvent(
                                    commandName =
                                        mongoc_apm_command_succeeded_get_command_name(
                                            event,
                                        )?.toKString().orEmpty(),
                                    reply =
                                        mongoc_apm_command_succeeded_get_reply(event)?.toDocument() ?: BsonDocument(),
                                    durationMicros = mongoc_apm_command_succeeded_get_duration(event),
                                    requestId = mongoc_apm_command_succeeded_get_request_id(event),
                                    operationId = mongoc_apm_command_succeeded_get_operation_id(event),
                                ),
                            )
                        }
                    },
                )
                mongoc_apm_set_command_failed_cb(
                    callbacks,
                    staticCFunction { event ->
                        val listener =
                            mongoc_apm_command_failed_get_context(event).toListener() ?: return@staticCFunction
                        guard {
                            memScoped {
                                val error = alloc<bson_error_t>()
                                mongoc_apm_command_failed_get_error(event, error.ptr)
                                listener.failed(
                                    CommandFailedEvent(
                                        commandName =
                                            mongoc_apm_command_failed_get_command_name(
                                                event,
                                            )?.toKString().orEmpty(),
                                        failure =
                                            MongoException(error.domain, error.code, error.message.toKString()),
                                        durationMicros = mongoc_apm_command_failed_get_duration(event),
                                        requestId = mongoc_apm_command_failed_get_request_id(event),
                                        operationId = mongoc_apm_command_failed_get_operation_id(event),
                                    ),
                                )
                            }
                        }
                    },
                )
                mongoc_client_pool_set_apm_callbacks(pool, callbacks, reference.asCPointer())
            } finally {
                // Пул копирует набор коллбэков себе — держать наш экземпляр незачем.
                mongoc_apm_callbacks_destroy(callbacks)
            }
            return ApmSubscription(reference)
        }

        private fun COpaquePointer?.toListener(): CommandListener? = this?.asStableRef<CommandListener>()?.get()

        /**
         * Не даёт исключению уйти через границу C.
         *
         * Наблюдение не должно ломать операцию, за которой наблюдает, — а исключение, прошедшее
         * сквозь кадр C, роняет процесс целиком.
         */
        private inline fun guard(body: () -> Unit) {
            try {
                body()
            } catch (_: Throwable) {
                // Намеренно молча: см. KDoc CommandListener.
            }
        }
    }
}
