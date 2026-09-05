package com.fourseveneightnine.tv.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object KodiNotifications {
    fun encodeLocalControl(action: String, snapshot: ReceiverSnapshot): String =
        notification(
            "X4789.OnLocalControl",
            buildJsonObject {
                put("action", action)
                put("position", snapshot.positionSeconds)
                put("duration", snapshot.durationSeconds)
            },
        )

    fun encodePlaybackStatus(
        status: String,
        message: String,
        snapshot: ReceiverSnapshot,
    ): String =
        notification(
            "X4789.OnPlaybackStatus",
            buildJsonObject {
                put("status", status)
                put("message", message)
                put("position", snapshot.positionSeconds)
                put("duration", snapshot.durationSeconds)
            },
        )

    fun encode(event: ReceiverEvent): String {
        val (method, data) = when (event) {
            is ReceiverEvent.Play -> "Player.OnPlay" to playerData(event.snapshot)
            is ReceiverEvent.Pause -> "Player.OnPause" to playerData(event.snapshot)
            is ReceiverEvent.Seek -> "Player.OnSeek" to seekData(event)
            is ReceiverEvent.SpeedChanged -> "Player.OnSpeedChanged" to playerData(event.snapshot)
            is ReceiverEvent.Stop -> "Player.OnStop" to playerData(event.snapshot)
            is ReceiverEvent.VolumeChanged -> "Application.OnVolumeChanged" to volumeData(event.snapshot)
            // Vendor event: stock Kodi has no equivalent, and a phone that does not understand it
            // simply ignores the frame and still receives the Player.OnStop that follows.
            is ReceiverEvent.ExternalHandoff -> "X4789.OnExternalHandoff" to handoffData(event)
            is ReceiverEvent.Error -> "X4789.OnPlaybackError" to errorData(event)
            is ReceiverEvent.LinkRefreshRequested -> "X4789.OnLinkRefreshRequested" to linkRefreshData(event)
        }

        return notification(method, data)
    }

    private fun linkRefreshData(event: ReceiverEvent.LinkRefreshRequested): JsonObject =
        buildJsonObject {
            put("reason", event.reason)
            put("position", event.snapshot.positionSeconds)
            put("duration", event.snapshot.durationSeconds)
        }

    private fun errorData(event: ReceiverEvent.Error): JsonObject =
        buildJsonObject {
            put("category", event.category)
            event.mimeType?.let { put("mimeType", it) }
            put("humanText", event.humanText)
            put("position", event.snapshot.positionSeconds)
            put("duration", event.snapshot.durationSeconds)
            put("engine", event.engine)
            put("fatal", event.fatal)
            put("willRetry", event.willRetry)
            event.retryEngine?.let { put("retryEngine", it) }
        }

    private fun handoffData(event: ReceiverEvent.ExternalHandoff): JsonObject =
        buildJsonObject {
            put("player", event.playerLabel)
            put("package", event.playerPackage)
        }

    private fun playerData(snapshot: ReceiverSnapshot): JsonObject =
        buildJsonObject {
            put(
                "player",
                buildJsonObject {
                    put("playerid", 1)
                    put("speed", snapshot.speed)
                },
            )
        }

    private fun seekData(event: ReceiverEvent.Seek): JsonObject =
        buildJsonObject {
            put(
                "player",
                buildJsonObject {
                    put("playerid", 1)
                    put("speed", event.snapshot.speed)
                    put("time", kodiTime(event.snapshot.positionSeconds))
                    put("seekoffset", kodiTime(event.offsetSeconds))
                },
            )
        }

    private fun volumeData(snapshot: ReceiverSnapshot): JsonObject =
        buildJsonObject {
            put("volume", snapshot.volume)
            put("muted", snapshot.muted)
        }
}
