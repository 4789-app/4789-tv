package com.fourseveneightnine.tv.transport

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSocketClientRegistryTest {
    @Test
    fun broadcastRemovesDeadClientsWithoutDroppingHealthyClients() = runTest {
        val registry = WebSocketClientRegistry()
        val received = mutableListOf<String>()
        var deadSendAttempts = 0
        registry.register(
            object : WebSocketClient {
                override suspend fun send(payload: String) {
                    received += payload
                }

                override suspend fun close() = Unit
            },
        )
        registry.register(
            object : WebSocketClient {
                override suspend fun send(payload: String) {
                    deadSendAttempts += 1
                    error("socket closed")
                }

                override suspend fun close() = Unit
            },
        )

        registry.broadcast("first")
        registry.broadcast("second")

        assertEquals(listOf("first", "second"), received)
        assertEquals(1, deadSendAttempts)
        assertEquals(1, registry.clientCount())
    }

    @Test
    fun closeAllClosesEveryClientAndClearsMembership() = runTest {
        val registry = WebSocketClientRegistry()
        var healthyClientClosed = false
        registry.register(
            object : WebSocketClient {
                override suspend fun send(payload: String) = Unit

                override suspend fun close() {
                    healthyClientClosed = true
                }
            },
        )
        registry.register(
            object : WebSocketClient {
                override suspend fun send(payload: String) = Unit

                override suspend fun close() {
                    error("already closed")
                }
            },
        )

        registry.closeAll()

        assertTrue(healthyClientClosed)
        assertEquals(0, registry.clientCount())
    }

    @Test
    fun broadcastFansOutBeforeNeverCompletingPeerTimesOut() = runTest {
        val registry = WebSocketClientRegistry(clientOperationTimeoutMillis = 1_000L)
        val neverCompletingSendStarted = CompletableDeferred<Unit>()
        val healthyPeerReceived = CompletableDeferred<String>()

        registry.register(
            object : WebSocketClient {
                override suspend fun send(payload: String) {
                    neverCompletingSendStarted.complete(Unit)
                    awaitCancellation()
                }

                override suspend fun close() = Unit
            },
        )
        registry.register(
            object : WebSocketClient {
                override suspend fun send(payload: String) {
                    healthyPeerReceived.complete(payload)
                }

                override suspend fun close() = Unit
            },
        )

        val broadcastJob = launch {
            registry.broadcast("notification")
        }

        withTimeout(100L) {
            neverCompletingSendStarted.await()
            assertEquals("notification", healthyPeerReceived.await())
        }
        broadcastJob.join()

        assertTrue(broadcastJob.isCompleted)
        assertEquals(1, registry.clientCount())
    }

    @Test
    fun closeAllFansOutAndClearsMembershipWhenPeerNeverCloses() = runTest {
        val registry = WebSocketClientRegistry(clientOperationTimeoutMillis = 1_000L)
        val neverCompletingCloseStarted = CompletableDeferred<Unit>()
        val healthyPeerClosed = CompletableDeferred<Unit>()

        registry.register(
            object : WebSocketClient {
                override suspend fun send(payload: String) = Unit

                override suspend fun close() {
                    neverCompletingCloseStarted.complete(Unit)
                    awaitCancellation()
                }
            },
        )
        registry.register(
            object : WebSocketClient {
                override suspend fun send(payload: String) = Unit

                override suspend fun close() {
                    healthyPeerClosed.complete(Unit)
                }
            },
        )

        val closeAllJob = launch {
            registry.closeAll()
        }

        withTimeout(100L) {
            neverCompletingCloseStarted.await()
            healthyPeerClosed.await()
        }
        withTimeout(2_000L) {
            closeAllJob.join()
        }

        assertTrue(closeAllJob.isCompleted)
        assertEquals(0, registry.clientCount())
    }

    @Test
    fun callerCancellationIsNotConvertedIntoClientFailure() = runTest {
        val registry = WebSocketClientRegistry(clientOperationTimeoutMillis = 1_000L)
        val sendStarted = CompletableDeferred<Unit>()
        registry.register(
            object : WebSocketClient {
                override suspend fun send(payload: String) {
                    sendStarted.complete(Unit)
                    awaitCancellation()
                }

                override suspend fun close() = Unit
            },
        )

        val broadcastJob = launch {
            registry.broadcast("notification")
        }
        withTimeout(100L) {
            sendStarted.await()
        }

        broadcastJob.cancel()
        broadcastJob.join()

        assertEquals(1, registry.clientCount())
    }

    @Test
    fun concurrentRegistrationBroadcastAndUnregistrationRemainConsistent() = runTest {
        val registry = WebSocketClientRegistry()
        val deliveryCount = AtomicInteger()

        coroutineScope {
            repeat(100) { index ->
                launch {
                    val registration = registry.register(
                        object : WebSocketClient {
                            override suspend fun send(payload: String) {
                                deliveryCount.incrementAndGet()
                            }

                            override suspend fun close() = Unit
                        },
                    )

                    registry.broadcast("notification-$index")
                    registry.unregister(registration)
                }
            }
        }

        assertTrue(deliveryCount.get() >= 100)
        assertEquals(0, registry.clientCount())
    }
}
