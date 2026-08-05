package com.fourseveneightnine.tv.player

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import dev.jdtech.mpv.MPVLib
import java.io.Closeable
import java.io.File
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Fire-and-forget command path through libmpv's own JSON IPC server.
 *
 * The third-party AAR exposes only blocking `mpv_command`. A real Fire OS stream proved that its
 * JNI call can remain blocked after FILE_LOADED, wedging every request serialized behind it. JSON
 * IPC queues the same command in mpv without holding the receiver's native control executor.
 */
internal class MpvIpcCommandClient(context: Context) : Closeable {
    private val closed = AtomicBoolean(false)
    private val requestID = AtomicLong(0)
    private val socketFile = File(
        context.cacheDir,
        "mpv-${android.os.Process.myPid()}-${nextID.incrementAndGet()}.sock",
    )

    fun configure(mpv: MPVLib) {
        socketFile.delete()
        if (mpv.setOptionString(OPTION_INPUT_IPC_SERVER, socketFile.absolutePath) < 0) {
            throw MpvReceiverFailure("Playback command channel could not be configured.")
        }
    }

    @Synchronized
    fun dispatch(command: Array<String>): Result<Unit> {
        val nextRequestID = requestID.incrementAndGet()
        val payload = MpvIpcCommandPolicy.payload(command, nextRequestID)
            ?: return Result.failure(MpvReceiverFailure("Invalid playback command."))
        return dispatchPayload(payload, nextRequestID)
    }

    @Synchronized
    fun dispatch(command: JsonArray): Result<Unit> {
        val nextRequestID = requestID.incrementAndGet()
        val payload = MpvIpcCommandPolicy.payload(command, nextRequestID)
            ?: return Result.failure(MpvReceiverFailure("Invalid playback command."))
        return dispatchPayload(payload, nextRequestID)
    }

    /** Sends a bounded JSON-IPC request and returns its result without touching MPV JNI. */
    @Synchronized
    fun request(command: JsonArray): Result<JsonElement> {
        val nextRequestID = requestID.incrementAndGet()
        val payload = MpvIpcCommandPolicy.payload(command, nextRequestID)
            ?: return Result.failure(MpvReceiverFailure("Invalid playback command."))
        return try {
            ensureConnected()
            val response = dispatchPayloadConnected(payload, nextRequestID)
            val objectValue = Json.parseToJsonElement(response).jsonObject
            Result.success(
                objectValue["data"]
                    ?: throw MpvReceiverFailure("Playback command returned no result."),
            )
        } catch (error: Throwable) {
            disconnect()
            Result.failure(MpvReceiverFailure("Playback command channel is unavailable (${error.javaClass.simpleName})."))
        }
    }

    /**
     * Sends one logical playback transaction without allowing another command to interleave it.
     * Header setup and loadfile must be one IPC critical section: a second title arriving between
     * those commands can otherwise inherit the first title's headers (or load the wrong URL).
     */
    @Synchronized
    fun dispatchAll(commands: List<JsonArray>): Result<Unit> {
        if (commands.isEmpty()) return Result.success(Unit)
        if (closed.get()) {
            return Result.failure(MpvReceiverFailure("Playback command channel is closed."))
        }
        return try {
            ensureConnected()
            commands.forEach { command ->
                val nextRequestID = requestID.incrementAndGet()
                val payload = MpvIpcCommandPolicy.payload(command, nextRequestID)
                    ?: throw MpvReceiverFailure("Invalid playback command.")
                dispatchPayloadConnected(payload, nextRequestID)
            }
            Result.success(Unit)
        } catch (error: Throwable) {
            disconnect()
            Result.failure(MpvReceiverFailure("Playback command channel is unavailable (${error.javaClass.simpleName})."))
        }
    }

    private fun dispatchPayload(payload: ByteArray, requestID: Long): Result<Unit> {
        if (closed.get()) {
            return Result.failure(MpvReceiverFailure("Playback command channel is closed."))
        }
        return try {
            ensureConnected()
            dispatchPayloadConnected(payload, requestID)
            Result.success(Unit)
        } catch (error: Throwable) {
            disconnect()
            Result.failure(MpvReceiverFailure("Playback command channel is unavailable (${error.javaClass.simpleName})."))
        }
    }

    private fun dispatchPayloadConnected(payload: ByteArray, requestID: Long): String {
        val output = writer ?: throw MpvReceiverFailure("Playback command channel has no writer.")
        val input = reader ?: throw MpvReceiverFailure("Playback command channel has no reader.")
        output.write(payload.toString(Charsets.UTF_8))
        output.flush()
        var response: String? = null
        while (response == null) {
            val line = input.readLine()
                ?: throw MpvReceiverFailure("Playback command channel closed without a reply.")
            if (MpvIpcCommandPolicy.hasRequestID(line, requestID)) response = line
        }
        if (!MpvIpcCommandPolicy.isSuccessfulResponse(response, requestID)) {
            throw MpvReceiverFailure("Playback command was rejected.")
        }
        // Do not opportunistically drain the stream. Fire OS can report a partially-arrived JSON
        // line as ready; consuming it here can block until the socket timeout and make mpv report a
        // broken pipe during the next title. The request-id loop safely ignores unrelated lines on
        // the next command instead.
        return response
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        disconnect()
        socketFile.delete()
    }

    private var socket: LocalSocket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    private fun ensureConnected() {
        if (socket?.isConnected == true && reader != null && writer != null) return

        var lastFailure: Throwable? = null
        repeat(CONNECT_ATTEMPTS) { attempt ->
            if (closed.get()) throw MpvReceiverFailure("Playback command channel is closed.")
            val candidate = LocalSocket()
            try {
                // Fire OS 7 inherits Android 9's LocalSocket implementation, whose timeout
                // connect overload rejects filesystem sockets. A local Unix-domain connect fails
                // immediately when absent, and the bounded retry loop covers server startup.
                candidate.connect(
                    LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM),
                )
                candidate.soTimeout = COMMAND_REPLY_TIMEOUT_MILLIS
                socket = candidate
                reader = BufferedReader(InputStreamReader(candidate.inputStream, Charsets.UTF_8))
                writer = BufferedWriter(OutputStreamWriter(candidate.outputStream, Charsets.UTF_8))
                return
            } catch (error: Throwable) {
                lastFailure = error
                runCatching { candidate.close() }
                if (attempt + 1 < CONNECT_ATTEMPTS) Thread.sleep(CONNECT_RETRY_MILLIS)
            }
        }
        throw MpvReceiverFailure(
            "Playback command channel could not connect (${lastFailure?.javaClass?.simpleName ?: "unknown"}).",
        )
    }

    private fun disconnect() {
        runCatching { reader?.close() }
        runCatching { writer?.close() }
        runCatching { socket?.close() }
        reader = null
        writer = null
        socket = null
    }

    private companion object {
        const val OPTION_INPUT_IPC_SERVER = "input-ipc-server"
        const val CONNECT_ATTEMPTS = 20
        const val CONNECT_RETRY_MILLIS = 25L
        const val COMMAND_REPLY_TIMEOUT_MILLIS = 10_000
        val nextID = AtomicLong(0)
    }
}
