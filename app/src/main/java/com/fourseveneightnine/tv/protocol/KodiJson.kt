package com.fourseveneightnine.tv.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

val kodiJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = false
}

fun kodiTime(seconds: Double): JsonObject {
    require(seconds.isFinite()) { "seconds must be finite" }

    val clampedSeconds = seconds.coerceAtLeast(0.0)
    val wholeSeconds = clampedSeconds.toLong()
    val milliseconds = ((clampedSeconds - wholeSeconds) * 1_000.0).toLong()

    return buildJsonObject {
        put("hours", wholeSeconds / 3_600)
        put("minutes", (wholeSeconds % 3_600) / 60)
        put("seconds", wholeSeconds % 60)
        put("milliseconds", milliseconds)
    }
}

fun rpcResult(id: JsonElement?, result: JsonElement): String =
    encodeJson(
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: JsonNull)
            put("result", result)
        },
    )

fun rpcError(id: JsonElement?, code: Int, message: String): String =
    encodeJson(
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: JsonNull)
            put(
                "error",
                buildJsonObject {
                    put("code", code)
                    put("message", message)
                },
            )
        },
    )

fun notification(method: String, data: JsonObject): String =
    encodeJson(
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            put(
                "params",
                buildJsonObject {
                    put("sender", "xbmc")
                    put("data", data)
                },
            )
        },
    )

private fun encodeJson(element: JsonElement): String =
    kodiJson.encodeToString(JsonElement.serializer(), element)
