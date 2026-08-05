package com.fourseveneightnine.tv.protocol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.fourseveneightnine.tv.transport.ReceiverPorts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RpcDispatcherTest {
    @Test
    fun malformedJsonReturnsParseError() {
        runTest {
            val response = parse(RpcDispatcher(FakeController()).dispatch("{\"jsonrpc\":"))

            assertError(response, -32_700)
            assertEquals(JsonNull, response["id"])
        }
    }

    @Test
    fun malformedEnvelopesReturnInvalidRequestWithNullId() {
        runTest {
            val dispatcher = RpcDispatcher(FakeController())
            val malformedRequests = listOf(
                "[]",
                "{\"jsonrpc\":\"1.0\",\"id\":7,\"method\":\"JSONRPC.Ping\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":true,\"method\":\"JSONRPC.Ping\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":7}",
            )

            for (payload in malformedRequests) {
                val response = parse(dispatcher.dispatch(payload))
                assertError(response, -32_600)
                assertEquals(JsonNull, response["id"])
            }
        }
    }

    @Test
    fun pingReturnsPongAndPreservesStringId() {
        runTest {
            val response = rpc(
                RpcDispatcher(FakeController()),
                method = "JSONRPC.Ping",
                params = "{}",
                id = "\"phone-17\"",
            )

            assertEquals(JsonPrimitive("phone-17"), response["id"])
            assertEquals(JsonPrimitive("pong"), response["result"])
        }
    }

    @Test
    fun receiverInfoIdentifiesOnlyTheFirstPartyReceiver() {
        runTest {
            val identified = RpcDispatcher(
                FakeController(),
                ReceiverIdentity(
                    name = "4789 TV",
                    uuid = "receiver-123",
                    hardwareVideoCodecs = listOf("h264", "hevc", "av1"),
                    audioDecodeCodecs = listOf("aac", "ac3", "eac3"),
                    audioPassthroughCodecs = listOf("ac3"),
                ),
            )
            val result = rpc(identified, "X4789.GetReceiverInfo", "{}")["result"]!!.jsonObject
            assertEquals(JsonPrimitive("4789 TV"), result["name"])
            assertEquals(JsonPrimitive("receiver-123"), result["uuid"])
            assertEquals(JsonPrimitive(true), result["x4789"])
            assertEquals(JsonPrimitive(ReceiverPorts.HTTP), result["httpPort"])
            // The phone ranks sources against THIS television's decoders.
            assertEquals(
                listOf("h264", "hevc", "av1").map(::JsonPrimitive),
                result["hardwareVideoCodecs"]!!.jsonArray.toList(),
            )
            // Capability handshake v1: push port + engines + audio decode/passthrough/software.
            assertEquals(JsonPrimitive(ReceiverPorts.WEB_SOCKET), result["wsPort"])
            assertEquals(
                listOf("exo", "mpv").map(::JsonPrimitive),
                result["engines"]!!.jsonArray.toList(),
            )
            val audio = result["audioCodecs"]!!.jsonObject
            assertEquals(
                listOf("aac", "ac3", "eac3").map(::JsonPrimitive),
                audio["decode"]!!.jsonArray.toList(),
            )
            assertEquals(listOf("ac3").map(::JsonPrimitive), audio["passthrough"]!!.jsonArray.toList())
            // mpv's software set always includes the REMUX killers.
            val software = audio["software"]!!.jsonArray.toList()
            listOf("dts", "dtshd", "truehd").forEach {
                assertEquals(true, software.contains(JsonPrimitive(it)))
            }
            assertEquals(JsonPrimitive(1), result["capsVersion"])

            assertError(rpc(RpcDispatcher(FakeController()), "X4789.GetReceiverInfo", "{}"), -32_601)
        }
    }

    @Test
    fun invalidParamsAndUnsupportedSchemesReturnInvalidParams() {
        runTest {
            val dispatcher = RpcDispatcher(FakeController())

            assertError(rpc(dispatcher, "Player.Open", "{\"item\":{}}"), -32_602)
            assertError(
                rpc(dispatcher, "Player.Open", "{\"item\":{\"file\":\"ftp://media.example/movie.mkv\"}}"),
                -32_602,
            )
            assertError(rpc(dispatcher, "Player.Open", "{\"item\":{\"file\":\"https:///missing-host\"}}"), -32_602)
            assertError(rpc(dispatcher, "Player.GetProperties", "{\"playerid\":0,\"properties\":[\"time\"]}"), -32_602)
            assertError(rpc(dispatcher, "Application.SetVolume", "{\"volume\":101}"), -32_602)

            val arrayParams = parse(
                dispatcher.dispatch("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"Player.Open\",\"params\":[]}"),
            )
            assertError(arrayParams, -32_602)
        }
    }

    @Test
    fun getActivePlayersReflectsCurrentSnapshot() {
        runTest {
            val controller = FakeController(snapshotValue = ReceiverSnapshot(active = false))
            val dispatcher = RpcDispatcher(controller)

            assertEquals(emptyList<JsonElement>(), rpc(dispatcher, "Player.GetActivePlayers", "{}")["result"]!!.jsonArray)

            controller.snapshotValue = ReceiverSnapshot(active = true)
            assertEquals(
                buildJsonObject {
                    put("playerid", 1)
                    put("type", "video")
                },
                rpc(dispatcher, "Player.GetActivePlayers", "{}")["result"]!!.jsonArray.single(),
            )
            assertError(rpc(dispatcher, "Player.GetActivePlayers", "{\"unexpected\":true}"), -32_602)
        }
    }

    @Test
    fun openParsesKodiPipeHeadersAndConsumesMetadataAfterSuccess() {
        runTest {
            val controller = FakeController()
            val dispatcher = RpcDispatcher(controller)

            assertOk(
                rpc(
                    dispatcher,
                    "X4789.NowPlaying",
                    "{\"title\":\"A Movie\",\"subtitle\":\"Episode 2\",\"isLive\":true}",
                ),
            )
            assertEquals(StagedNowPlaying("A Movie", "Episode 2", true), controller.stagedNowPlaying.single())

            assertOk(
                rpc(
                    dispatcher,
                    "Player.Open",
                    "{\"item\":{\"file\":\"https://media.example/video.mkv|User-Agent=Example%20Player&Referer=https%3A%2F%2Fapp.example%2Fwatch\"}}",
                ),
            )
            assertEquals(
                OpenMediaRequest(
                    url = "https://media.example/video.mkv",
                    title = "A Movie",
                    subtitle = "Episode 2",
                    isLive = true,
                    headers = mapOf(
                        "User-Agent" to "Example Player",
                        "Referer" to "https://app.example/watch",
                    ),
                ),
                controller.openRequests.single(),
            )

            assertOk(rpc(dispatcher, "Player.Open", "{\"item\":{\"file\":\"http://media.example/next.mkv\"}}"))
            assertNull(controller.openRequests[1].title)
            assertNull(controller.openRequests[1].subtitle)
            assertFalse(controller.openRequests[1].isLive)
        }
    }

    @Test
    fun malformedOrUnsafePipeHeadersReturnInvalidParams() {
        runTest {
            val dispatcher = RpcDispatcher(FakeController())

            assertError(
                rpc(dispatcher, "Player.Open", "{\"item\":{\"file\":\"https://media.example/a|User-Agent\"}}"),
                -32_602,
            )
            assertError(
                rpc(
                    dispatcher,
                    "Player.Open",
                    "{\"item\":{\"file\":\"https://media.example/a|User-Agent=one&user-agent=two\"}}",
                ),
                -32_602,
            )
            assertError(
                rpc(
                    dispatcher,
                    "Player.Open",
                    "{\"item\":{\"file\":\"https://media.example/a|X-Test=hello%0D%0Aworld\"}}",
                ),
                -32_602,
            )
        }
    }

    @Test
    fun failedOpenRetainsMetadataUntilOneSuccessfulOpenConsumesIt() {
        runTest {
            val controller = FakeController(openResult = Result.failure(IllegalStateException("stream token should not leak")))
            val dispatcher = RpcDispatcher(controller)
            assertOk(rpc(dispatcher, "X4789.NowPlaying", "{\"title\":\"Retry me\"}"))

            val failed = rpc(dispatcher, "Player.Open", "{\"item\":{\"file\":\"https://media.example/retry.mkv\"}}")
            assertError(failed, -32_000)
            assertFalse(failed.toString().contains("stream token should not leak"))
            assertEquals("Retry me", controller.openRequests.single().title)

            controller.openResult = Result.success(Unit)
            assertOk(rpc(dispatcher, "Player.Open", "{\"item\":{\"file\":\"https://media.example/retry.mkv\"}}"))
            assertEquals("Retry me", controller.openRequests[1].title)

            assertOk(rpc(dispatcher, "Player.Open", "{\"item\":{\"file\":\"https://media.example/after.mkv\"}}"))
            assertNull(controller.openRequests[2].title)
        }
    }

    @Test
    fun metadataArrivingDuringOpenIsReservedForTheFollowingOpen() {
        runTest {
            val controller = FakeController()
            controller.openStarted = CompletableDeferred()
            controller.allowOpen = CompletableDeferred()
            val dispatcher = RpcDispatcher(controller)
            assertOk(rpc(dispatcher, "X4789.NowPlaying", "{\"title\":\"First\"}"))

            val firstOpen = async {
                rpc(dispatcher, "Player.Open", "{\"item\":{\"file\":\"https://media.example/first.mkv\"}}")
            }
            controller.openStarted!!.await()
            assertOk(rpc(dispatcher, "X4789.NowPlaying", "{\"title\":\"Second\"}"))
            controller.allowOpen!!.complete(Unit)
            assertOk(firstOpen.await())

            assertOk(rpc(dispatcher, "Player.Open", "{\"item\":{\"file\":\"https://media.example/second.mkv\"}}"))
            assertEquals(listOf("First", "Second"), controller.openRequests.map { it.title })
        }
    }

    @Test
    fun getPropertiesReturnsRequestedKodiFields() {
        runTest {
            val controller = FakeController(
                snapshotValue = ReceiverSnapshot(
                    active = true,
                    positionSeconds = 3_661.5,
                    durationSeconds = 7_200.0,
                    speed = 2,
                ),
            )

            val response = rpc(
                RpcDispatcher(controller),
                "Player.GetProperties",
                "{\"playerid\":1,\"properties\":[\"time\",\"totaltime\",\"percentage\",\"speed\",\"audiostreams\"]}",
            )

            assertEquals(
                buildJsonObject {
                    put("time", kodiTime(3_661.5))
                    put("totaltime", kodiTime(7_200.0))
                    put("percentage", 3_661.5 / 7_200.0 * 100.0)
                    put("speed", 2)
                    put("audiostreams", buildJsonArray { })
                },
                response["result"],
            )
        }
    }

    @Test
    fun activePlayerMethodsReturnInactivePlayerError() {
        runTest {
            val dispatcher = RpcDispatcher(FakeController(snapshotValue = ReceiverSnapshot(active = false)))

            assertError(
                rpc(dispatcher, "Player.GetProperties", "{\"playerid\":1,\"properties\":[\"time\"]}"),
                -32_100,
            )
            assertError(
                rpc(dispatcher, "Player.Seek", "{\"playerid\":1,\"value\":{\"percentage\":50}}"),
                -32_100,
            )
            assertError(rpc(dispatcher, "Player.PlayPause", "{\"playerid\":1}"), -32_100)
            assertError(rpc(dispatcher, "Player.Stop", "{\"playerid\":1}"), -32_100)
            assertError(rpc(dispatcher, "Player.SetSpeed", "{\"playerid\":1,\"speed\":2}"), -32_100)
            assertError(rpc(dispatcher, "Player.GoTo", "{\"playerid\":1,\"to\":\"next\"}"), -32_100)
        }
    }

    @Test
    fun seekAcceptsPercentageRelativeAndAbsoluteKodiForms() {
        runTest {
            val controller = FakeController()
            val dispatcher = RpcDispatcher(controller)
            val values = listOf(
                "12.5" to SeekCommand.Percentage(12.5),
                "{\"percentage\":25}" to SeekCommand.Percentage(25.0),
                "\"smallforward\"" to SeekCommand.RelativeSeconds(10.0),
                "{\"step\":\"smallbackward\"}" to SeekCommand.RelativeSeconds(-10.0),
                "{\"step\":\"bigforward\"}" to SeekCommand.RelativeSeconds(30.0),
                "{\"step\":\"bigbackward\"}" to SeekCommand.RelativeSeconds(-30.0),
                "{\"seconds\":-7.5}" to SeekCommand.RelativeSeconds(-7.5),
                "{\"time\":{\"hours\":1,\"minutes\":2,\"seconds\":3,\"milliseconds\":400}}" to
                    SeekCommand.AbsoluteSeconds(3_723.4),
                "{\"hours\":1,\"minutes\":0,\"seconds\":1}" to SeekCommand.AbsoluteSeconds(3_601.0),
            )

            for ((value, expected) in values) {
                assertOk(rpc(dispatcher, "Player.Seek", "{\"playerid\":1,\"value\":$value}"))
            }

            assertEquals(values.map { it.second }, controller.seekCommands)
        }
    }

    @Test
    fun seekRejectsOutOfRangeAndMalformedValues() {
        runTest {
            val dispatcher = RpcDispatcher(FakeController())

            assertError(rpc(dispatcher, "Player.Seek", "{\"playerid\":1,\"value\":101}"), -32_602)
            assertError(
                rpc(dispatcher, "Player.Seek", "{\"playerid\":1,\"value\":{\"time\":{\"minutes\":60}}}"),
                -32_602,
            )
            assertError(rpc(dispatcher, "Player.Seek", "{\"playerid\":1,\"value\":{\"step\":\"sideways\"}}"), -32_602)
            assertError(rpc(dispatcher, "Player.Seek", "{\"playerid\":1}"), -32_602)
        }
    }

    @Test
    fun playerTransportCommandsUseControllerResults() {
        runTest {
            val controller = FakeController(
                snapshotValue = ReceiverSnapshot(active = true, speed = 1),
                playPauseResult = Result.success(0),
                setSpeedResult = Result.success(3),
            )
            val dispatcher = RpcDispatcher(controller)

            assertEquals(
                buildJsonObject { put("speed", 0) },
                rpc(dispatcher, "Player.PlayPause", "{\"playerid\":1}")["result"],
            )
            assertEquals(1, controller.playPauseCalls)

            assertEquals(
                buildJsonObject { put("speed", 1) },
                rpc(dispatcher, "Player.PlayPause", "{\"playerid\":1,\"play\":true}")["result"],
            )
            assertEquals(1, controller.playPauseCalls)

            assertOk(rpc(dispatcher, "Player.Stop", "{\"playerid\":1}"))
            assertEquals(1, controller.stopCalls)

            assertEquals(
                buildJsonObject { put("speed", 3) },
                rpc(dispatcher, "Player.SetSpeed", "{\"playerid\":1,\"speed\":2}")["result"],
            )
            assertEquals(listOf(2), controller.speedRequests)
        }
    }

    @Test
    fun applicationPropertiesAndVolumeAreControllerBacked() {
        runTest {
            val controller = FakeController(
                snapshotValue = ReceiverSnapshot(volume = 42, muted = true),
                setVolumeResult = Result.success(37),
            )
            val dispatcher = RpcDispatcher(controller)

            assertEquals(
                buildJsonObject {
                    put("volume", 42)
                    put("muted", true)
                },
                rpc(dispatcher, "Application.GetProperties", "{\"properties\":[\"volume\",\"muted\"]}")["result"],
            )
            assertEquals(JsonPrimitive(37), rpc(dispatcher, "Application.SetVolume", "{\"volume\":35}")["result"])
            assertEquals(listOf(35), controller.volumeRequests)
        }
    }

    @Test
    fun sideChannelAndCompatibilityMethodsAcknowledgeExpectedRequests() {
        runTest {
            val controller = FakeController()
            val dispatcher = RpcDispatcher(controller)
            val style = "{\"family\":\"Inter\",\"colorHex\":\"#FFFFFF\",\"size\":24,\"lift\":8}"

            assertOk(rpc(dispatcher, "X4789.SubtitleStyle", style))
            assertEquals(parse(style), controller.stagedSubtitleStyle)

            assertEquals(JsonPrimitive(true), rpc(dispatcher, "Settings.SetSettingValue", "{\"setting\":\"audiooutput.passthrough\",\"value\":true}")["result"])
            assertEquals("audiooutput.passthrough" to JsonPrimitive(true), controller.settings.single())
            assertOk(rpc(dispatcher, "Input.ExecuteAction", "{\"action\":\"showsubtitles\"}"))
            assertEquals(listOf("showsubtitles"), controller.actions)
            assertOk(rpc(dispatcher, "Player.GoTo", "{\"playerid\":1,\"to\":\"next\"}"))
        }
    }

    @Test
    fun theEngineOverrideIsHandledByTheActivityNotByTheController() {
        runTest {
            val controller = FakeController()
            val engine = RecordingEngineOverridePort()
            val dispatcher = RpcDispatcher(
                controller,
                ReceiverIdentity(name = "4789 TV", uuid = "receiver-123"),
                engineOverrides = engine,
            )

            suspend fun set(value: String) =
                rpc(dispatcher, "Settings.SetSettingValue", "{\"setting\":\"x4789.engine\",\"value\":\"$value\"}")

            assertEquals(JsonPrimitive(true), set("mpv")["result"])
            assertEquals(listOf("mpv"), engine.overrides)
            // A controller cannot replace itself, so this must never reach one.
            assertTrue(controller.settings.isEmpty())

            // `auto` is accepted and clears the override.
            assertEquals(JsonPrimitive(true), set("AUTO")["result"])
            assertEquals(listOf("mpv", "auto"), engine.overrides)

            // An unknown engine is a bad parameter, not a silent no-op.
            assertError(set("kodi"), -32_602)
            assertEquals(listOf("mpv", "auto"), engine.overrides)

            // The running engine is reported so the phone shows what IS, not what was requested.
            val info = rpc(dispatcher, "X4789.GetReceiverInfo", "{}")["result"]!!.jsonObject
            assertEquals(JsonPrimitive("exo"), info["engine"])
        }
    }

    @Test
    fun aReceiverWithoutAnEnginePortReportsTheOverrideAsUnhandled() {
        runTest {
            val dispatcher = RpcDispatcher(FakeController())
            assertEquals(
                JsonPrimitive(false),
                rpc(dispatcher, "Settings.SetSettingValue", "{\"setting\":\"x4789.engine\",\"value\":\"mpv\"}")["result"],
            )
        }
    }

    private class RecordingEngineOverridePort : EngineOverridePort {
        val overrides = mutableListOf<String>()
        override fun setOverride(normalizedOverride: String) {
            overrides += normalizedOverride
        }

        override fun currentEngineName(): String = "exo"
    }

    @Test
    fun audioAndSubtitleTracksAreReportedAndSelectable() {
        runTest {
            val controller = FakeController(
                tracksResult = Result.success(
                    ReceiverTracks(
                        audio = listOf(
                            ReceiverTrack(0, "eng", "English", "aac", 2, selected = true),
                            ReceiverTrack(1, "mal", "Malayalam", "eac3", 6, isOriginal = true),
                        ),
                        subtitles = listOf(
                            ReceiverTrack(0, "eng", "English SDH", "subrip", selected = true),
                        ),
                    ),
                ),
            )
            val dispatcher = RpcDispatcher(controller)
            val properties = rpc(
                dispatcher,
                "Player.GetProperties",
                "{\"playerid\":1,\"properties\":[\"audiostreams\",\"currentaudiostream\",\"subtitles\",\"currentsubtitle\",\"subtitleenabled\"]}",
            )["result"]!!.jsonObject

            assertEquals(2, properties["audiostreams"]!!.jsonArray.size)
            assertEquals(JsonPrimitive(0), properties["currentaudiostream"]!!.jsonObject["index"])
            assertEquals(JsonPrimitive(true), properties["subtitleenabled"])
            assertEquals(JsonPrimitive("English SDH"), properties["currentsubtitle"]!!.jsonObject["name"])

            assertOk(rpc(dispatcher, "Player.SetAudioStream", "{\"playerid\":1,\"stream\":1}"))
            assertOk(rpc(dispatcher, "Player.SetSubtitle", "{\"playerid\":1,\"subtitle\":0,\"enable\":true}"))
            assertOk(rpc(dispatcher, "Player.SetSubtitle", "{\"playerid\":1,\"subtitle\":\"off\"}"))
            assertOk(rpc(dispatcher, "Player.SetSubtitle", "{\"playerid\":1,\"subtitle\":\"on\"}"))
            assertOk(rpc(dispatcher, "Player.SetSubtitle", "{\"playerid\":1,\"subtitle\":\"next\"}"))
            assertOk(
                rpc(
                    dispatcher,
                    "Player.AddSubtitle",
                    "{\"playerid\":1,\"subtitle\":\"https://subs.example/english.srt\"}",
                ),
            )

            assertEquals(listOf(1), controller.audioSelections)
            assertEquals(
                listOf(
                    SubtitleSelection.Index(0, true),
                    SubtitleSelection.Off,
                    SubtitleSelection.On,
                    SubtitleSelection.Latest,
                ),
                controller.subtitleSelections,
            )
            assertEquals(listOf("https://subs.example/english.srt"), controller.addedSubtitles)

            assertError(rpc(dispatcher, "Player.SetAudioStream", "{\"playerid\":1,\"stream\":-1}"), -32_602)
            assertError(rpc(dispatcher, "Player.SetSubtitle", "{\"playerid\":1,\"subtitle\":\"sideways\"}"), -32_602)
            assertError(
                rpc(dispatcher, "Player.AddSubtitle", "{\"playerid\":1,\"subtitle\":\"file:///secret.srt\"}"),
                -32_602,
            )
        }
    }

    @Test
    fun controllerFailuresUseGenericErrorWithoutLeakingCause() {
        runTest {
            val controller = FakeController()
            controller.snapshotException = IllegalStateException("private media URL")
            val dispatcher = RpcDispatcher(controller)

            val snapshotFailure = rpc(dispatcher, "Player.GetActivePlayers", "{}")
            assertError(snapshotFailure, -32_000)
            assertFalse(snapshotFailure.toString().contains("private media URL"))

            controller.snapshotException = null
            controller.executeActionResult = Result.failure(IllegalStateException("private action"))
            val actionFailure = rpc(dispatcher, "Input.ExecuteAction", "{\"action\":\"back\"}")
            assertError(actionFailure, -32_000)
            assertFalse(actionFailure.toString().contains("private action"))
        }
    }

    private fun parse(payload: String): JsonObject = kodiJson.parseToJsonElement(payload).jsonObject

    private suspend fun rpc(
        dispatcher: RpcDispatcher,
        method: String,
        params: String,
        id: String = "1",
    ): JsonObject =
        parse(dispatcher.dispatch("{\"jsonrpc\":\"2.0\",\"id\":$id,\"method\":\"$method\",\"params\":$params}"))

    private fun assertOk(response: JsonObject) {
        assertEquals(JsonPrimitive("OK"), response["result"])
    }

    private fun assertError(response: JsonObject, code: Int) {
        val error = response["error"]!!.jsonObject
        assertEquals(JsonPrimitive(code), error["code"])
    }

    private data class StagedNowPlaying(
        val title: String?,
        val subtitle: String?,
        val isLive: Boolean,
    )

    private class FakeController(
        var snapshotValue: ReceiverSnapshot = ReceiverSnapshot(
            active = true,
            positionSeconds = 30.0,
            durationSeconds = 120.0,
            speed = 1,
            volume = 42,
            muted = false,
        ),
        var openResult: Result<Unit> = Result.success(Unit),
        var playPauseResult: Result<Int> = Result.success(0),
        var seekResult: Result<Unit> = Result.success(Unit),
        var stopResult: Result<Unit> = Result.success(Unit),
        var setSpeedResult: Result<Int> = Result.success(1),
        var setVolumeResult: Result<Int> = Result.success(100),
        var tracksResult: Result<ReceiverTracks> = Result.success(ReceiverTracks()),
        var executeActionResult: Result<Unit> = Result.success(Unit),
    ) : ReceiverController {
        override val events: Flow<ReceiverEvent> = emptyFlow()

        var snapshotException: Throwable? = null
        var openStarted: CompletableDeferred<Unit>? = null
        var allowOpen: CompletableDeferred<Unit>? = null

        val openRequests = mutableListOf<OpenMediaRequest>()
        val stagedNowPlaying = mutableListOf<StagedNowPlaying>()
        val seekCommands = mutableListOf<SeekCommand>()
        val speedRequests = mutableListOf<Int>()
        val volumeRequests = mutableListOf<Int>()
        val audioSelections = mutableListOf<Int>()
        val subtitleSelections = mutableListOf<SubtitleSelection>()
        val addedSubtitles = mutableListOf<String>()
        val actions = mutableListOf<String>()
        val settings = mutableListOf<Pair<String, JsonPrimitive>>()
        var stagedSubtitleStyle: JsonObject? = null
        var playPauseCalls = 0
        var stopCalls = 0

        override suspend fun snapshot(): ReceiverSnapshot {
            snapshotException?.let { throw it }
            return snapshotValue
        }

        override suspend fun open(request: OpenMediaRequest): Result<Unit> {
            openRequests += request
            openStarted?.complete(Unit)
            allowOpen?.await()
            return openResult
        }

        override suspend fun playPause(): Result<Int> {
            playPauseCalls += 1
            return playPauseResult
        }

        override suspend fun seek(command: SeekCommand): Result<Unit> {
            seekCommands += command
            return seekResult
        }

        override suspend fun stop(): Result<Unit> {
            stopCalls += 1
            return stopResult
        }

        override suspend fun setSpeed(speed: Int): Result<Int> {
            speedRequests += speed
            return setSpeedResult
        }

        override suspend fun setVolume(volume: Int): Result<Int> {
            volumeRequests += volume
            return setVolumeResult
        }

        override suspend fun tracks(): Result<ReceiverTracks> = tracksResult

        override suspend fun selectAudio(index: Int): Result<Unit> {
            audioSelections += index
            return Result.success(Unit)
        }

        override suspend fun selectSubtitle(selection: SubtitleSelection): Result<Unit> {
            subtitleSelections += selection
            return Result.success(Unit)
        }

        override suspend fun addSubtitle(url: String): Result<Unit> {
            addedSubtitles += url
            return Result.success(Unit)
        }

        override suspend fun stageNowPlaying(title: String?, subtitle: String?, isLive: Boolean) {
            stagedNowPlaying += StagedNowPlaying(title, subtitle, isLive)
        }

        override suspend fun stageSubtitleStyle(params: JsonObject) {
            stagedSubtitleStyle = params
        }

        override suspend fun applySetting(name: String, value: JsonPrimitive): Result<Boolean> {
            settings += name to value
            return Result.success(true)
        }

        override suspend fun executeAction(action: String): Result<Unit> {
            actions += action
            return executeActionResult
        }
    }
}
