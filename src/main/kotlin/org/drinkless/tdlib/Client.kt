package org.drinkless.tdlib

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Desktop implementation of TDLib's Java `Client`, backed by the prebuilt tdjson library.
 *
 * Same public API as the official JNI Client.java (used as-is on Android with libtdjni.so),
 * so the app code and TdApi.java are shared: here every request is encoded to JSON by
 * [TdJsonCodec], sent with td_send, and responses/updates from td_receive are decoded back.
 */
class Client private constructor(
    updateHandler: ResultHandler?,
    updateExceptionHandler: ExceptionHandler?,
    defaultExceptionHandler: ExceptionHandler?
) {
    /** Handler for query results and incoming updates. */
    fun interface ResultHandler {
        fun onResult(obj: TdApi.Object)
    }

    /** Handler for exceptions thrown by a [ResultHandler]. */
    fun interface ExceptionHandler {
        fun onException(e: Throwable)
    }

    /** Thrown by [execute] when TDLib returns an error. */
    class ExecutionException(@JvmField val error: TdApi.Error) : Exception("${error.code}: ${error.message}")

    private class Handler(val resultHandler: ResultHandler, val exceptionHandler: ExceptionHandler?)

    private val clientId: Int = TdJsonNative.lib.td_create_client_id()

    init {
        clientCount.incrementAndGet()
        if (updateHandler != null) updateHandlers[clientId] = Handler(updateHandler, updateExceptionHandler)
        if (defaultExceptionHandler != null) defaultExceptionHandlers[clientId] = defaultExceptionHandler
        // TDLib starts the instance on its first request
        send(TdApi.GetOption("version"), null, null)
    }

    /**
     * Sends a request. [resultHandler] receives the result or a TdApi.Error; null ignores the result.
     */
    @JvmOverloads
    fun send(query: TdApi.Function<*>, resultHandler: ResultHandler?, exceptionHandler: ExceptionHandler? = null) {
        val queryId = currentQueryId.incrementAndGet()
        if (resultHandler != null) handlers[queryId] = Handler(resultHandler, exceptionHandler)
        TdJsonNative.lib.td_send(clientId, TdJsonCodec.encode(query, queryId))
    }

    companion object {
        private val defaultExceptionHandlers = ConcurrentHashMap<Int, ExceptionHandler>()
        private val updateHandlers = ConcurrentHashMap<Int, Handler>()
        private val handlers = ConcurrentHashMap<Long, Handler>()
        private val currentQueryId = AtomicLong()
        private val clientCount = AtomicLong()

        @Volatile
        private var receiverStarted = false

        /** Creates a client and starts the shared receive thread on first use. */
        @JvmStatic
        fun create(
            updateHandler: ResultHandler?,
            updateExceptionHandler: ExceptionHandler?,
            defaultExceptionHandler: ExceptionHandler?
        ): Client {
            val client = Client(updateHandler, updateExceptionHandler, defaultExceptionHandler)
            synchronized(this) {
                if (!receiverStarted) {
                    receiverStarted = true
                    Thread(::receiveLoop, "TDLib thread").apply { isDaemon = true }.start()
                }
            }
            return client
        }

        /** Synchronously executes a request that TDLib allows to run synchronously. */
        @JvmStatic
        @Suppress("UNCHECKED_CAST")
        @Throws(ExecutionException::class)
        fun <T : TdApi.Object> execute(query: TdApi.Function<T>): T {
            val response = TdJsonNative.lib.td_execute(TdJsonCodec.encode(query))
                ?: throw ExecutionException(TdApi.Error(400, "Empty response"))
            val result = TdJsonCodec.decode(response)
                ?: throw ExecutionException(TdApi.Error(500, "Unknown response type"))
            if (result is TdApi.Error) throw ExecutionException(result)
            return result as T
        }

        private fun receiveLoop() {
            val lib = TdJsonNative.lib
            while (true) {
                val json = lib.td_receive(100.0) ?: continue
                try {
                    process(json)
                } catch (e: Throwable) {
                    System.err.println("TDLib: failed to process event: ${e.message}")
                }
            }
        }

        private fun process(json: String) {
            val element = Json.parseToJsonElement(json).jsonObject
            val clientId = (element["@client_id"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return
            val queryId = (element["@extra"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
            val obj = TdJsonCodec.decode(element) ?: return

            val isClosed = queryId == 0L && obj is TdApi.UpdateAuthorizationState &&
                obj.authorizationState is TdApi.AuthorizationStateClosed

            val handler = if (queryId == 0L) updateHandlers[clientId] else handlers.remove(queryId)
            if (handler != null) {
                try {
                    handler.resultHandler.onResult(obj)
                } catch (cause: Throwable) {
                    val exceptionHandler = handler.exceptionHandler ?: defaultExceptionHandlers[clientId]
                    try {
                        exceptionHandler?.onException(cause)
                    } catch (_: Throwable) {
                    }
                }
            }

            if (isClosed) {
                updateHandlers.remove(clientId)
                defaultExceptionHandlers.remove(clientId)
                clientCount.decrementAndGet()
            }
        }
    }
}
