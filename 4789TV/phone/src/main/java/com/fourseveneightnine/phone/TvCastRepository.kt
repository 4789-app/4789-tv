package com.fourseveneightnine.phone

import com.fourseveneightnine.contract.DiscoverItem
import java.io.BufferedInputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal object TvCastPolicy {
    const val MAXIMUM_RESPONSE_BYTES = 64 * 1024
    private val json = Json { ignoreUnknownKeys = true }

    fun acceptsResponse(payload: ByteArray, expectedID: Int): Boolean = runCatching {
        require(payload.size in 1..MAXIMUM_RESPONSE_BYTES)
        val objectValue = json.parseToJsonElement(payload.decodeToString()).jsonObject
        objectValue["jsonrpc"] == JsonPrimitive("2.0") &&
            objectValue["id"] == JsonPrimitive(expectedID) &&
            objectValue["error"] == null &&
            objectValue["result"] != null
    }.getOrDefault(false)
}

internal class TvCastRepository(
    private val post: (suspend (TvCastTarget, ByteArray) -> ByteArray) = ::postJsonRpc,
) {
    suspend fun cast(target: TvCastTarget, item: DiscoverItem, streamURL: String): Boolean {
        if (TvCastTargetPolicy.parse(target.host) != target) return false
        if (!RemoteStreamPolicy.isPlayableURL(streamURL)) return false
        val nowPlaying = request(
            id = 1,
            method = "X4789.NowPlaying",
            params = buildJsonObject {
                put("title", item.title)
                put("isLive", false)
                item.backdropURL?.let { put("artworkURL", it) }
                item.posterURL?.let { put("posterURL", it) }
            },
        )
        if (!TvCastPolicy.acceptsResponse(post(target, nowPlaying), expectedID = 1)) return false
        val open = request(
            id = 2,
            method = "Player.Open",
            params = buildJsonObject {
                put("item", buildJsonObject { put("file", streamURL) })
            },
        )
        return TvCastPolicy.acceptsResponse(post(target, open), expectedID = 2)
    }

    suspend fun playPause(target: TvCastTarget): Boolean = command(
        target = target,
        id = 3,
        method = "Player.PlayPause",
        params = playerParams(),
    )

    suspend fun seek(target: TvCastTarget, forward: Boolean): Boolean = command(
        target = target,
        id = if (forward) 4 else 5,
        method = "Player.Seek",
        params = buildJsonObject {
            put("playerid", 1)
            put(
                "value",
                buildJsonObject { put("step", if (forward) "smallforward" else "smallbackward") },
            )
        },
    )

    suspend fun stop(target: TvCastTarget): Boolean = command(
        target = target,
        id = 6,
        method = "Player.Stop",
        params = playerParams(),
    )

    private suspend fun command(
        target: TvCastTarget,
        id: Int,
        method: String,
        params: JsonObject,
    ): Boolean {
        if (TvCastTargetPolicy.parse(target.host) != target) return false
        return TvCastPolicy.acceptsResponse(post(target, request(id, method, params)), id)
    }

    private fun playerParams(): JsonObject = buildJsonObject { put("playerid", 1) }

    private fun request(id: Int, method: String, params: JsonObject): ByteArray =
        Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            },
        ).encodeToByteArray()

    private companion object {
        suspend fun postJsonRpc(target: TvCastTarget, body: ByteArray): ByteArray = withContext(Dispatchers.IO) {
            require(body.size in 1..TvCastPolicy.MAXIMUM_RESPONSE_BYTES)
            Socket().use { socket ->
                socket.connect(InetSocketAddress(target.host, target.port), 3_000)
                socket.soTimeout = 5_000
                val output = socket.getOutputStream()
                output.write(
                    buildString {
                        append("POST /jsonrpc HTTP/1.1\r\n")
                        append("Host: ${target.host}:${target.port}\r\n")
                        append("Content-Type: application/json\r\n")
                        append("Accept: application/json\r\n")
                        append("Connection: close\r\n")
                        append("Content-Length: ${body.size}\r\n\r\n")
                    }.encodeToByteArray(),
                )
                output.write(body)
                output.flush()

                val input = BufferedInputStream(socket.getInputStream())
                val status = readLine(input)
                require(status.startsWith("HTTP/1.1 200 ") || status.startsWith("HTTP/1.0 200 "))
                var contentLength: Int? = null
                var headerCount = 0
                while (true) {
                    require(headerCount++ < 100) { "header_count" }
                    val line = readLine(input)
                    if (line.isEmpty()) break
                    val separator = line.indexOf(':')
                    require(separator > 0) { "header" }
                    if (line.substring(0, separator).equals("Content-Length", ignoreCase = true)) {
                        contentLength = line.substring(separator + 1).trim().toIntOrNull()
                    }
                }
                val count = requireNotNull(contentLength)
                require(count in 1..TvCastPolicy.MAXIMUM_RESPONSE_BYTES)
                val payload = ByteArray(count)
                var offset = 0
                while (offset < count) {
                    val read = input.read(payload, offset, count - offset)
                    require(read > 0) { "unexpected_eof" }
                    offset += read
                }
                payload
            }
        }

        private fun readLine(input: BufferedInputStream): String {
            val bytes = ArrayList<Byte>()
            while (true) {
                require(bytes.size <= 8_192) { "header_line" }
                val value = input.read()
                require(value >= 0) { "unexpected_eof" }
                if (value == '\n'.code) break
                if (value != '\r'.code) bytes += value.toByte()
            }
            return bytes.toByteArray().decodeToString()
        }
    }
}
