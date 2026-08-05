package com.fourseveneightnine.tv.transport

import com.fourseveneightnine.tv.protocol.KodiNotifications
import com.fourseveneightnine.tv.protocol.ReceiverEvent
import com.fourseveneightnine.tv.protocol.RpcDispatcher
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.application.serverConfig
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.http.Headers
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics

/**
 * Lifecycle-bound Kodi JSON-RPC transport. HTTP requests and WebSocket requests share the same
 * dispatcher, while receiver events are pushed to every currently connected WebSocket peer.
 */
class KodiTransport(
    dispatcher: RpcDispatcher,
    private val events: Flow<ReceiverEvent>,
    private val httpPort: Int = DEFAULT_HTTP_PORT,
    private val webSocketPort: Int = DEFAULT_WEB_SOCKET_PORT,
) {
    private val requestHandler = KodiRequestHandler(dispatcher::dispatch)
    private val clients = WebSocketClientRegistry()
    private val lifecycleMutex = Mutex()
    private var runtime: Runtime? = null

    suspend fun start() {
        lifecycleMutex.withLock {
            if (runtime != null) {
                return@withLock
            }

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            var httpServer: EmbeddedServer<*, *>? = null
            var webSocketServer: EmbeddedServer<*, *>? = null

            try {
                // Fail fast, on this coroutine, if either port is already owned (e.g. another app
                // grabbed it). Ktor CIO can surface a late bind failure on an engine worker thread,
                // which would crash the whole process; this probe turns the common case into an
                // ordinary start() exception the Activity renders as a retryable error.
                probePort(httpPort)
                probePort(webSocketPort)

                httpServer = createHttpServer()
                webSocketServer = createWebSocketServer()
                httpServer.start(wait = false)
                // CIO starts its accept loop asynchronously. Waiting for resolved connectors turns
                // an occupied port into a normal start() failure instead of an uncaught worker-thread
                // exception that terminates the whole Activity.
                httpServer.engine.resolvedConnectors()
                webSocketServer.start(wait = false)
                webSocketServer.engine.resolvedConnectors()

                runtime = Runtime(
                    scope = scope,
                    eventsJob = scope.launch { collectReceiverEvents() },
                    httpServer = httpServer,
                    webSocketServer = webSocketServer,
                )
            } catch (error: Throwable) {
                ReceiverDiagnostics.record("transport.start.failure", error::class.java.name)
                scope.cancel()
                webSocketServer?.stopQuietly()
                httpServer?.stopQuietly()
                throw error
            }
        }
    }

    suspend fun stop() {
        withContext(NonCancellable) {
            lifecycleMutex.withLock {
                val activeRuntime = runtime ?: return@withLock
                runtime = null

                activeRuntime.eventsJob.cancelAndJoin()
                activeRuntime.scope.cancel()
                clients.closeAll()
                activeRuntime.webSocketServer.stopQuietly()
                activeRuntime.httpServer.stopQuietly()
            }
        }
    }

    /** Binds and releases the port so an occupied port fails here, not on a Ktor worker thread. */
    private fun probePort(port: Int) {
        java.net.ServerSocket().use { socket ->
            socket.reuseAddress = true
            socket.bind(java.net.InetSocketAddress(port))
        }
    }

    // A failure on a Ktor engine coroutine (late bind loss, accept-loop error) must degrade the
    // transport, never kill the process. resolvedConnectors() catches most bind failures at
    // start(); this handler is the backstop for the race it can lose.
    private val engineExceptionHandler = CoroutineExceptionHandler { _, error ->
        ReceiverDiagnostics.record("transport.engine.uncaught", error::class.java.name)
    }

    private fun createHttpServer(): EmbeddedServer<*, *> =
        embeddedServer(
            CIO,
            serverConfig {
                parentCoroutineContext = Dispatchers.IO + engineExceptionHandler
                module {
                    intercept(ApplicationCallPipeline.ApplicationPhase.Call) {
                        if (call.request.path() == JSON_RPC_PATH && call.request.httpMethod != HttpMethod.Post) {
                            call.response.headers.append(HttpHeaders.Allow, HttpMethod.Post.value)
                            call.respond(HttpStatusCode.MethodNotAllowed)
                            finish()
                        }
                    }

                    routing {
                        post(JSON_RPC_PATH) {
                            val response = requestHandler.dispatch(call.receiveText())
                            call.respondText(response, ContentType.Application.Json)
                        }
                        // mpv's loopback window to the outside world — see MpvStreamRelay.
                        get("${MpvStreamRelay.PATH}/{token}") {
                            respondRelay(call)
                        }
                    }
                }
            },
        ) {
            connector {
                port = httpPort
                host = LAN_HOST
            }
        }

    private fun createWebSocketServer(): EmbeddedServer<*, *> =
        embeddedServer(
            CIO,
            serverConfig {
                parentCoroutineContext = Dispatchers.IO + engineExceptionHandler
                module {
                    install(WebSockets)

                    routing {
                        webSocket(JSON_RPC_PATH) {
                            val client = KtorWebSocketClient(this)
                            val registration = clients.register(client)

                            try {
                                for (frame in incoming) {
                                    if (frame is Frame.Text) {
                                        val response = requestHandler.dispatch(frame.readText())
                                        if (!clients.send(registration, response)) {
                                            break
                                        }
                                    }
                                }
                            } finally {
                                clients.unregister(registration)
                            }
                        }
                    }
                }
            },
        ) {
            connector {
                port = webSocketPort
                host = LAN_HOST
            }
        }

    /** Stream one registered upstream through okhttp, mirroring status/range/length to mpv. */
    private suspend fun respondRelay(call: ApplicationCall) {
        val upstream = call.parameters["token"]?.let(MpvStreamRelay::lookup)
        if (upstream == null) {
            call.respond(HttpStatusCode.NotFound)
            return
        }
        val builder = okhttp3.Request.Builder().url(upstream.url)
        upstream.headers.forEach { (name, value) -> builder.header(name, value) }
        call.request.headers[HttpHeaders.Range]?.let { builder.header("Range", it) }
        val upstreamResponse = try {
            withContext(Dispatchers.IO) { MpvStreamRelay.client.newCall(builder.build()).execute() }
        } catch (error: Exception) {
            ReceiverDiagnostics.record("mpv.relay.upstreamError", error::class.java.simpleName)
            call.respond(HttpStatusCode.BadGateway)
            return
        }
        val body = upstreamResponse.body
        if (!upstreamResponse.isSuccessful || body == null) {
            val code = upstreamResponse.code
            upstreamResponse.close()
            ReceiverDiagnostics.record("mpv.relay.upstreamStatus", "code=$code")
            call.respond(HttpStatusCode.fromValue(code))
            return
        }
        val upstreamType = upstreamResponse.header("Content-Type")
            ?.let { runCatching { ContentType.parse(it) }.getOrNull() }
            ?: ContentType.Application.OctetStream
        call.respond(object : OutgoingContent.WriteChannelContent() {
            override val status = HttpStatusCode.fromValue(upstreamResponse.code)
            override val contentType = upstreamType
            override val contentLength = body.contentLength().takeIf { it >= 0 }
            override val headers = Headers.build {
                upstreamResponse.header("Content-Range")?.let { append(HttpHeaders.ContentRange, it) }
                append(HttpHeaders.AcceptRanges, upstreamResponse.header("Accept-Ranges") ?: "bytes")
            }

            override suspend fun writeTo(channel: ByteWriteChannel) {
                upstreamResponse.use { response ->
                    response.body?.byteStream()?.use { input ->
                        val buffer = ByteArray(RELAY_CHUNK_BYTES)
                        while (true) {
                            val read = withContext(Dispatchers.IO) { input.read(buffer) }
                            if (read <= 0) break
                            channel.writeFully(buffer, 0, read)
                        }
                    }
                }
            }
        })
    }

    private suspend fun collectReceiverEvents() {
        try {
            events.collect { event ->
                val notification = encodeNotification(event) ?: return@collect
                clients.broadcast(notification)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // Do not let an upstream flow failure terminate either server or leak its details.
        }
    }

    private fun encodeNotification(event: ReceiverEvent): String? =
        try {
            KodiNotifications.encode(event)
        } catch (_: Throwable) {
            null
        }

    private fun EmbeddedServer<*, *>.stopQuietly() {
        try {
            stop(gracePeriodMillis = 0, timeoutMillis = STOP_TIMEOUT_MILLIS)
        } catch (_: Throwable) {
            // Stop the sibling engine even if this engine was already stopped by Ktor.
        }
    }

    private data class Runtime(
        val scope: CoroutineScope,
        val eventsJob: Job,
        val httpServer: EmbeddedServer<*, *>,
        val webSocketServer: EmbeddedServer<*, *>,
    )

    private class KtorWebSocketClient(
        private val session: WebSocketSession,
    ) : WebSocketClient {
        override suspend fun send(payload: String) {
            session.send(Frame.Text(payload))
        }

        override suspend fun close() {
            session.close(CloseReason(CloseReason.Codes.NORMAL, SERVER_STOPPING_REASON))
        }
    }

    private companion object {
        const val DEFAULT_HTTP_PORT = ReceiverPorts.HTTP
        const val DEFAULT_WEB_SOCKET_PORT = ReceiverPorts.WEB_SOCKET
        const val JSON_RPC_PATH = "/jsonrpc"
        const val RELAY_CHUNK_BYTES = 64 * 1024
        const val LAN_HOST = "0.0.0.0"
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val SERVER_STOPPING_REASON = "Server stopping"
    }
}
