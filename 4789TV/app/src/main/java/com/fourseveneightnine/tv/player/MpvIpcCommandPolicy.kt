package com.fourseveneightnine.tv.player

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Pure JSON-IPC framing kept separate so command escaping is covered by local JVM tests. */
internal object MpvIpcCommandPolicy {
    fun payload(command: Array<String>, requestID: Long): ByteArray? {
        if (command.isEmpty() || command.any(String::isEmpty)) return null
        return payload(JsonArray(command.map(::JsonPrimitive)), requestID)
    }

    fun payload(command: JsonArray, requestID: Long): ByteArray? {
        if (command.isEmpty()) return null

        val request = buildJsonObject {
            put("command", command)
            put("request_id", requestID)
        }
        return "${request}\n".toByteArray(Charsets.UTF_8)
    }

    fun isSuccessfulResponse(response: String, requestID: Long): Boolean =
        runCatching {
            val objectValue = Json.parseToJsonElement(response).jsonObject
            hasRequestID(objectValue, requestID) &&
                objectValue["error"]?.jsonPrimitive?.content == "success"
        }.getOrDefault(false)

    fun hasRequestID(response: String, requestID: Long): Boolean =
        runCatching {
            hasRequestID(Json.parseToJsonElement(response).jsonObject, requestID)
        }.getOrDefault(false)

    private fun hasRequestID(objectValue: kotlinx.serialization.json.JsonObject, requestID: Long): Boolean =
        objectValue["request_id"]?.jsonPrimitive?.content?.toLongOrNull() == requestID
}

/** A reused MPV instance can deliver a late file event without a request id. Replacements therefore
 * use a fresh callback gate whenever an instance is already present; only the first open reuses
 * the not-yet-loaded instance. */
internal object MpvOpenPlayerPolicy {
    fun requiresFreshPlayer(
        playerPresent: Boolean,
        freshMarkerGeneration: Long,
        generation: Long,
    ): Boolean = playerPresent || (freshMarkerGeneration >= 0 && freshMarkerGeneration < generation)
}

/** Bounds how long a blocked native teardown may accumulate behind later title replacements. */
internal object MpvRetirementPolicy {
    const val STUCK_RETIREMENT_MILLIS = 10_000L

    fun shouldRecover(pendingCount: Int, oldestAgeMillis: Long): Boolean =
        pendingCount > 0 && oldestAgeMillis >= STUCK_RETIREMENT_MILLIS
}
