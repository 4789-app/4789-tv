package com.fourseveneightnine.tv.transport

import com.fourseveneightnine.tv.protocol.rpcError
import java.util.concurrent.CancellationException

/**
 * Keeps transport-level error handling separate from the JSON-RPC dispatcher.
 *
 * A successful dispatcher response is returned unchanged. Unexpected failures are represented by
 * a generic JSON-RPC error so neither HTTP nor WebSocket peers receive implementation details.
 */
internal class KodiRequestHandler(
    private val dispatchPayload: suspend (String) -> String,
) {
    suspend fun dispatch(payload: String): String =
        try {
            dispatchPayload(payload)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            rpcError(id = null, code = INTERNAL_ERROR_CODE, message = INTERNAL_ERROR_MESSAGE)
        }

    private companion object {
        const val INTERNAL_ERROR_CODE = -32_603
        const val INTERNAL_ERROR_MESSAGE = "Internal error"
    }
}
