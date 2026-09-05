package com.fourseveneightnine.tv.protocol

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class KodiNotificationsTest {
    @Test
    fun playEncodesPlayerNotification() {
        val snapshot = ReceiverSnapshot(speed = 1)

        assertEquals(
            expectedNotification("Player.OnPlay", playerData(speed = 1)),
            parse(KodiNotifications.encode(ReceiverEvent.Play(snapshot))),
        )
    }

    @Test
    fun pauseEncodesPlayerNotification() {
        val snapshot = ReceiverSnapshot(speed = 0)

        assertEquals(
            expectedNotification("Player.OnPause", playerData(speed = 0)),
            parse(KodiNotifications.encode(ReceiverEvent.Pause(snapshot))),
        )
    }

    @Test
    fun seekEncodesTimeAndClampedOffset() {
        val snapshot = ReceiverSnapshot(positionSeconds = 3_661.5, speed = 1)
        val expectedData = buildJsonObject {
            put(
                "player",
                buildJsonObject {
                    put("playerid", 1)
                    put("speed", 1)
                    put("time", kodiTime(3_661.5))
                    put("seekoffset", kodiTime(-2.0))
                },
            )
        }

        assertEquals(
            expectedNotification("Player.OnSeek", expectedData),
            parse(KodiNotifications.encode(ReceiverEvent.Seek(snapshot, offsetSeconds = -2.0))),
        )
    }

    @Test
    fun speedChangedEncodesCurrentSpeed() {
        val snapshot = ReceiverSnapshot(speed = 2)

        assertEquals(
            expectedNotification("Player.OnSpeedChanged", playerData(speed = 2)),
            parse(KodiNotifications.encode(ReceiverEvent.SpeedChanged(snapshot))),
        )
    }

    @Test
    fun stopEncodesPlayerNotification() {
        val snapshot = ReceiverSnapshot(speed = 0)

        assertEquals(
            expectedNotification("Player.OnStop", playerData(speed = 0)),
            parse(KodiNotifications.encode(ReceiverEvent.Stop(snapshot))),
        )
    }

    @Test
    fun volumeChangedEncodesVolumeAndMuteState() {
        val snapshot = ReceiverSnapshot(volume = 42, muted = true)

        assertEquals(
            expectedNotification(
                "Application.OnVolumeChanged",
                buildJsonObject {
                    put("volume", 42)
                    put("muted", true)
                },
            ),
            parse(KodiNotifications.encode(ReceiverEvent.VolumeChanged(snapshot))),
        )
    }

    @Test
    fun localResumeIdentifiesTvOriginAndCarriesSnapshot() {
        val snapshot = ReceiverSnapshot(positionSeconds = 61.25, durationSeconds = 3_600.0, speed = 1)
        val data = parse(KodiNotifications.encodeLocalControl("resume", snapshot))["params"]!!
            .jsonObject["data"]!!.jsonObject

        assertEquals("resume", data["action"]?.toString()?.trim('"'))
        assertEquals("61.25", data["position"]?.toString())
        assertEquals("3600.0", data["duration"]?.toString())
    }

    @Test
    fun bufferingStatusCarriesHumanMessageAndPosition() {
        val snapshot = ReceiverSnapshot(positionSeconds = 90.0, durationSeconds = 7_200.0)
        val data = parse(
            KodiNotifications.encodePlaybackStatus("buffering", "TV is buffering", snapshot),
        )["params"]!!.jsonObject["data"]!!.jsonObject

        assertEquals("buffering", data["status"]?.toString()?.trim('"'))
        assertEquals("TV is buffering", data["message"]?.toString()?.trim('"'))
        assertEquals("90.0", data["position"]?.toString())
    }

    @Test
    fun kodiTimeSplitsSecondsIntoKodiFields() {
        assertEquals(
            buildJsonObject {
                put("hours", 1)
                put("minutes", 1)
                put("seconds", 1)
                put("milliseconds", 500)
            },
            kodiTime(3_661.5),
        )
    }

    @Test
    fun kodiTimeClampsNegativeSeconds() {
        assertEquals(
            buildJsonObject {
                put("hours", 0)
                put("minutes", 0)
                put("seconds", 0)
                put("milliseconds", 0)
            },
            kodiTime(-1.0),
        )
    }

    @Test
    fun rpcResultPreservesJsonIdAndResult() {
        assertEquals(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", buildJsonObject { put("request", 7) })
                put("result", buildJsonObject { put("ok", true) })
            },
            parse(rpcResult(id = buildJsonObject { put("request", 7) }, result = buildJsonObject { put("ok", true) })),
        )
    }

    @Test
    fun rpcErrorUsesJsonNullWhenIdIsAbsent() {
        assertEquals(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", JsonNull)
                put(
                    "error",
                    buildJsonObject {
                        put("code", -32_602)
                        put("message", "Invalid params")
                    },
                )
            },
            parse(rpcError(id = null, code = -32_602, message = "Invalid params")),
        )
    }

    @Test
    fun notificationUsesKodiSenderAndSuppliedData() {
        val data = buildJsonObject { put("ready", true) }

        assertEquals(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", "X4789.Ready")
                put(
                    "params",
                    buildJsonObject {
                        put("sender", "xbmc")
                        put("data", data)
                    },
                )
            },
            parse(notification("X4789.Ready", data)),
        )
    }

    @Test
    fun playbackErrorEncodesEveryField() {
        val snapshot = ReceiverSnapshot(positionSeconds = 12.4, durationSeconds = 8_114.0)
        val event = ReceiverEvent.Error(
            snapshot = snapshot,
            category = "codec-audio",
            mimeType = "audio/vnd.dts",
            humanText = "This TV can't decode DTS audio.",
            engine = "exo",
            fatal = true,
            willRetry = true,
            retryEngine = "mpv",
        )
        val expectedData = buildJsonObject {
            put("category", "codec-audio")
            put("mimeType", "audio/vnd.dts")
            put("humanText", "This TV can't decode DTS audio.")
            put("position", 12.4)
            put("duration", 8_114.0)
            put("engine", "exo")
            put("fatal", true)
            put("willRetry", true)
            put("retryEngine", "mpv")
        }

        assertEquals(
            expectedNotification("X4789.OnPlaybackError", expectedData),
            parse(KodiNotifications.encode(event)),
        )
    }

    @Test
    fun playbackErrorOmitsAbsentOptionalFields() {
        val event = ReceiverEvent.Error(
            snapshot = ReceiverSnapshot(),
            category = "unknown",
            mimeType = null,
            humanText = "Playback stopped unexpectedly on the TV.",
            engine = "mpv",
            fatal = true,
        )
        val data = parse(KodiNotifications.encode(event))["params"]!!
            .jsonObject["data"]!!.jsonObject

        assertEquals(null, data["mimeType"])
        assertEquals(null, data["retryEngine"])
        assertEquals("unknown", data["category"]?.toString()?.trim('"'))
        assertEquals("false", data["willRetry"]?.toString())
    }

    private fun parse(payload: String): JsonObject = kodiJson.parseToJsonElement(payload).jsonObject

    private fun expectedNotification(method: String, data: JsonObject): JsonObject =
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
        }

    private fun playerData(speed: Int): JsonObject =
        buildJsonObject {
            put("player", playerObject(speed))
        }

    private fun playerObject(speed: Int): JsonObject =
        buildJsonObject {
            put("playerid", 1)
            put("speed", speed)
        }
}
