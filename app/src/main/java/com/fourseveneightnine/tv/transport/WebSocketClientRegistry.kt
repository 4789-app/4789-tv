package com.fourseveneightnine.tv.transport

import java.util.concurrent.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** A WebSocket peer that can receive Kodi JSON-RPC notifications. */
internal interface WebSocketClient {
    suspend fun send(payload: String)

    suspend fun close()
}

/** Opaque identifier used to remove the exact client that was registered. */
internal class WebSocketClientRegistration internal constructor(
    internal val id: Long,
)

/**
 * Synchronizes membership only; client I/O always happens after taking a snapshot so a slow or
 * dead peer cannot block registration, unregistration, or other peers' cleanup. Every client
 * operation is bounded by [clientOperationTimeoutMillis]. `closeAll` clears membership before
 * I/O and fans out closes concurrently, so its client-I/O phase is bounded by one timeout.
 */
internal class WebSocketClientRegistry(
    private val clientOperationTimeoutMillis: Long = DEFAULT_CLIENT_OPERATION_TIMEOUT_MILLIS,
) {
    init {
        require(clientOperationTimeoutMillis > 0) {
            "clientOperationTimeoutMillis must be positive"
        }
    }

    private val clientsMutex = Mutex()
    private val clients = mutableMapOf<Long, WebSocketClient>()
    private var nextId = 0L

    suspend fun register(client: WebSocketClient): WebSocketClientRegistration =
        clientsMutex.withLock {
            WebSocketClientRegistration(nextId++).also { registration ->
                clients[registration.id] = client
            }
        }

    suspend fun unregister(registration: WebSocketClientRegistration) {
        remove(registration.id)
    }

    suspend fun broadcast(payload: String) {
        val snapshot = clientsMutex.withLock {
            clients.map { (id, client) -> id to client }
        }

        coroutineScope {
            snapshot.forEach { (id, client) ->
                launch {
                    send(id, client, payload)
                }
            }
        }
    }

    suspend fun closeAll() {
        val snapshot = clientsMutex.withLock {
            clients.values.toList().also {
                clients.clear()
            }
        }

        // Every close has the same finite timeout and all closes run concurrently, so the client
        // I/O phase is bounded by one client-operation timeout even if a peer never completes.
        coroutineScope {
            snapshot.forEach { client ->
                launch {
                    runClientOperation { client.close() }
                }
            }
        }
    }

    /** Sends a response to the registered peer, removing that exact registration on failure. */
    internal suspend fun send(
        registration: WebSocketClientRegistration,
        payload: String,
    ): Boolean {
        val client = clientsMutex.withLock {
            clients[registration.id]
        } ?: return false

        return send(registration.id, client, payload)
    }

    internal suspend fun clientCount(): Int = clientsMutex.withLock { clients.size }

    private suspend fun remove(id: Long) {
        clientsMutex.withLock {
            clients.remove(id)
        }
    }

    private suspend fun send(id: Long, client: WebSocketClient, payload: String): Boolean {
        val sent = runClientOperation { client.send(payload) }
        if (!sent) {
            remove(id)
        }
        return sent
    }

    private suspend fun runClientOperation(operation: suspend () -> Unit): Boolean =
        try {
            withTimeoutOrNull(clientOperationTimeoutMillis) {
                operation()
                true
            } ?: false
        } catch (error: Throwable) {
            if (error is CancellationException) {
                // withTimeoutOrNull consumes only its own timeout. Any cancellation reaching this
                // point belongs to the caller (or a client), and caller cancellation must escape.
                currentCoroutineContext().ensureActive()
            }
            false
        }

    private companion object {
        /** closeAll's client-I/O phase is bounded by this value because closes fan out together. */
        const val DEFAULT_CLIENT_OPERATION_TIMEOUT_MILLIS = 5_000L
    }
}
