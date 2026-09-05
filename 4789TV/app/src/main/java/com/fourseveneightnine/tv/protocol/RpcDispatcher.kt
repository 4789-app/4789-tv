package com.fourseveneightnine.tv.protocol

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import com.fourseveneightnine.tv.player.ReceiverEnginePolicy
import com.fourseveneightnine.tv.transport.ReceiverPorts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * A deliberately small Kodi JSON-RPC 2.0 surface for the first-party receiver.
 *
 * The phone speaks Kodi's wire protocol, but this receiver has one video player and no playlist.
 * Keeping the dispatch table explicit means a new phone-side RPC cannot accidentally acquire
 * receiver behavior without a corresponding controller operation and test.
 */
class RpcDispatcher(
    private val controller: ReceiverController,
    receiverIdentity: ReceiverIdentity? = null,
    private val externalPlayers: ExternalPlayerPort? = null,
    private val engineOverrides: EngineOverridePort? = null,
) {
    /**
     * Capabilities are discovered from MediaCodec/AudioManager on a background dispatcher by the
     * TV Activity. Keeping the whole identity as one volatile value means the HTTP/WebSocket
     * handlers either see the old (unknown) snapshot or the complete new one — never a partially
     * updated list — while allowing the first frame to appear without waiting for a vendor codec
     * service that can take seconds on Fire OS.
     */
    @Volatile
    private var receiverIdentity: ReceiverIdentity? = receiverIdentity

    fun updateReceiverCapabilities(
        hardwareVideoCodecs: List<String>,
        audioDecodeCodecs: List<String>,
        audioPassthroughCodecs: List<String>,
    ) {
        receiverIdentity = receiverIdentity?.copy(
            hardwareVideoCodecs = hardwareVideoCodecs,
            audioDecodeCodecs = audioDecodeCodecs,
            audioPassthroughCodecs = audioPassthroughCodecs,
        )
    }

    private val metadataMutex = Mutex()
    private val openMutex = Mutex()

    // The token lets an Open consume only the metadata it actually used. A newer NowPlaying that
    // lands while an Open is in flight remains staged for the following Open.
    private var nextMetadataToken = 0L
    private var stagedMetadata: StagedMetadata? = null

    suspend fun dispatch(payload: String): String {
        val request = when (val parsed = parseRequest(payload)) {
            ParseOutcome.ParseError -> return rpcError(null, PARSE_ERROR, "Parse error")
            ParseOutcome.InvalidRequest -> return rpcError(null, INVALID_REQUEST, "Invalid Request")
            is ParseOutcome.Request -> parsed.value
        }

        // Method lookup intentionally precedes per-method parameter validation.
        if (request.method !in supportedMethods) {
            return methodNotFound(request.id)
        }

        val params = when (val rawParams = request.params) {
            null -> EMPTY_PARAMS
            is JsonObject -> rawParams
            else -> return invalidParams(request.id)
        }

        return when (request.method) {
            "JSONRPC.Ping" -> dispatchPing(request.id, params)
            "Player.GetActivePlayers" -> dispatchGetActivePlayers(request.id, params)
            "Player.Open" -> dispatchOpen(request.id, params)
            "Player.GetProperties" -> dispatchGetProperties(request.id, params)
            "Player.Seek" -> dispatchSeek(request.id, params)
            "Player.PlayPause" -> dispatchPlayPause(request.id, params)
            "Player.Stop" -> dispatchStop(request.id, params)
            "Player.SetSpeed" -> dispatchSetSpeed(request.id, params)
            "Player.SetAudioStream" -> dispatchSetAudioStream(request.id, params)
            "Player.SetSubtitle" -> dispatchSetSubtitle(request.id, params)
            "Player.AddSubtitle" -> dispatchAddSubtitle(request.id, params)
            "Application.GetProperties" -> dispatchApplicationGetProperties(request.id, params)
            "Application.SetVolume" -> dispatchSetVolume(request.id, params)
            "X4789.GetReceiverInfo" -> dispatchReceiverInfo(request.id, params)
            "X4789.GetExternalPlayers" -> dispatchGetExternalPlayers(request.id, params)
            "X4789.OpenExternal" -> dispatchOpenExternal(request.id, params)
            "X4789.NowPlaying" -> dispatchNowPlaying(request.id, params)
            "X4789.AudioProfile" -> dispatchAudioProfile(request.id, params)
            "X4789.SubtitleStyle" -> dispatchSubtitleStyle(request.id, params)
            "X4789.SubtitleOptions" -> dispatchSubtitleOptions(request.id, params)
            "X4789.SetSessionEngine" -> dispatchSetSessionEngine(request.id, params)
            "X4789.SetRecents" -> dispatchSetRecents(request.id, params)
            "Settings.SetSettingValue" -> dispatchSetSettingValue(request.id, params)
            "Input.ExecuteAction" -> dispatchExecuteAction(request.id, params)
            "Player.GoTo" -> dispatchGoTo(request.id, params)
            // Kept exhaustive with supportedMethods above so a future addition cannot silently
            // reach this path.
            else -> methodNotFound(request.id)
        }
    }

    private fun dispatchPing(id: JsonElement?, params: JsonObject): String =
        if (params.isEmpty()) {
            rpcResult(id, JsonPrimitive("pong"))
        } else {
            invalidParams(id)
        }

    private suspend fun dispatchGetActivePlayers(id: JsonElement?, params: JsonObject): String {
        if (params.isNotEmpty()) return invalidParams(id)

        return when (val snapshot = invokeController { controller.snapshot() }) {
            ControllerCall.Failure -> controllerFailure(id)
            is ControllerCall.Success -> {
                val players = buildJsonArray {
                    if (snapshot.value.active) {
                        add(
                            buildJsonObject {
                                put("playerid", PLAYER_ID)
                                put("type", "video")
                            },
                        )
                    }
                }
                rpcResult(id, players)
            }
        }
    }

    private suspend fun dispatchOpen(id: JsonElement?, params: JsonObject): String {
        val media = parseOpenMedia(params) ?: return invalidParams(id)

        // Serialize Open calls, not NowPlaying calls. This establishes a total order for "next
        // successful Open" while allowing a new title to arrive during a slow controller open.
        return openMutex.withLock {
            val metadata = metadataMutex.withLock { stagedMetadata }
            val request = OpenMediaRequest(
                url = media.url,
                title = metadata?.title,
                subtitle = metadata?.subtitle,
                isLive = metadata?.isLive ?: false,
                headers = media.headers,
                nextUp = metadata?.nextUp,
                chapterSeconds = metadata?.chapterSeconds.orEmpty(),
                castId = metadata?.castId,
                startPositionMs = metadata?.startPositionMs,
            )

            when (invokeResult { controller.open(request) }) {
                ControllerCall.Failure -> controllerFailure(id)
                is ControllerCall.Success -> {
                    if (metadata != null) {
                        metadataMutex.withLock {
                            if (stagedMetadata?.token == metadata.token) {
                                stagedMetadata = null
                            }
                        }
                    }
                    ok(id)
                }
            }
        }
    }

    private suspend fun dispatchGetProperties(id: JsonElement?, params: JsonObject): String {
        if (!params.hasReceiverPlayerId()) return invalidParams(id)
        val requested = params["properties"].stringArrayOrNull() ?: return invalidParams(id)

        return when (val state = activePlayer()) {
            ActivePlayer.ControllerFailure -> controllerFailure(id)
            ActivePlayer.Inactive -> inactivePlayer(id)
            is ActivePlayer.Available -> {
                val needsTracks = requested.any(TRACK_PROPERTIES::contains)
                val tracks = if (needsTracks) {
                    when (val result = invokeResult { controller.tracks() }) {
                        ControllerCall.Failure -> return controllerFailure(id)
                        is ControllerCall.Success -> result.value
                    }
                } else {
                    ReceiverTracks()
                }
                rpcResult(id, playerProperties(requested, state.snapshot, tracks))
            }
        }
    }

    private suspend fun dispatchSeek(id: JsonElement?, params: JsonObject): String {
        if (!params.hasReceiverPlayerId()) return invalidParams(id)
        val command = params["value"].toSeekCommandOrNull() ?: return invalidParams(id)

        return when (activePlayer()) {
            ActivePlayer.ControllerFailure -> controllerFailure(id)
            ActivePlayer.Inactive -> inactivePlayer(id)
            is ActivePlayer.Available -> {
                when (invokeResult { controller.seek(command) }) {
                    ControllerCall.Failure -> controllerFailure(id)
                    is ControllerCall.Success -> ok(id)
                }
            }
        }
    }

    private suspend fun dispatchPlayPause(id: JsonElement?, params: JsonObject): String {
        if (!params.hasReceiverPlayerId()) return invalidParams(id)
        val requestedPlay = params["play"]?.strictBooleanOrNull()
        if (params.containsKey("play") && requestedPlay == null) return invalidParams(id)

        return when (val state = activePlayer()) {
            ActivePlayer.ControllerFailure -> controllerFailure(id)
            ActivePlayer.Inactive -> inactivePlayer(id)
            is ActivePlayer.Available -> {
                // Kodi accepts either a toggle (the sender's normal form) or an explicit desired
                // state. The ReceiverController only exposes a toggle, so do not toggle when the
                // requested state is already true.
                if (requestedPlay != null && requestedPlay == (state.snapshot.speed != 0)) {
                    speedResult(id, state.snapshot.speed)
                } else {
                    when (val speed = invokeResult { controller.playPause() }) {
                        ControllerCall.Failure -> controllerFailure(id)
                        is ControllerCall.Success -> speedResult(id, speed.value)
                    }
                }
            }
        }
    }

    private suspend fun dispatchStop(id: JsonElement?, params: JsonObject): String {
        if (!params.hasReceiverPlayerId()) return invalidParams(id)

        return when (activePlayer()) {
            ActivePlayer.ControllerFailure -> controllerFailure(id)
            ActivePlayer.Inactive -> inactivePlayer(id)
            is ActivePlayer.Available -> {
                when (invokeResult { controller.stop() }) {
                    ControllerCall.Failure -> controllerFailure(id)
                    is ControllerCall.Success -> ok(id)
                }
            }
        }
    }

    private suspend fun dispatchSetSpeed(id: JsonElement?, params: JsonObject): String {
        if (!params.hasReceiverPlayerId()) return invalidParams(id)
        val speed = params["speed"].strictIntOrNull() ?: return invalidParams(id)

        return when (activePlayer()) {
            ActivePlayer.ControllerFailure -> controllerFailure(id)
            ActivePlayer.Inactive -> inactivePlayer(id)
            is ActivePlayer.Available -> {
                when (val result = invokeResult { controller.setSpeed(speed) }) {
                    ControllerCall.Failure -> controllerFailure(id)
                    is ControllerCall.Success -> speedResult(id, result.value)
                }
            }
        }
    }

    private suspend fun dispatchSetAudioStream(id: JsonElement?, params: JsonObject): String {
        if (!params.hasReceiverPlayerId()) return invalidParams(id)
        val index = params["stream"].strictIntOrNull()?.takeIf { it >= 0 } ?: return invalidParams(id)
        return withActivePlayerResult(id) { controller.selectAudio(index) }
    }

    private suspend fun dispatchSetSubtitle(id: JsonElement?, params: JsonObject): String {
        if (!params.hasReceiverPlayerId()) return invalidParams(id)
        val enable = params.optionalBooleanOrDefault("enable", default = true) ?: return invalidParams(id)
        val selection = when (val value = params["subtitle"]) {
            is JsonPrimitive -> {
                value.strictIntOrNull()?.takeIf { it >= 0 }?.let { SubtitleSelection.Index(it, enable) }
                    ?: value.stringOrNull()?.let {
                        when (it.lowercase(Locale.ROOT)) {
                            "off" -> SubtitleSelection.Off
                            "on" -> SubtitleSelection.On
                            "next" -> SubtitleSelection.Latest
                            else -> null
                        }
                    }
            }

            else -> null
        } ?: return invalidParams(id)
        return withActivePlayerResult(id) { controller.selectSubtitle(selection) }
    }

    private suspend fun dispatchAddSubtitle(id: JsonElement?, params: JsonObject): String {
        if (!params.hasReceiverPlayerId()) return invalidParams(id)
        val subtitle = params["subtitle"].stringOrNull()?.takeIf(String::isHttpUrl)
            ?: return invalidParams(id)
        return withActivePlayerResult(id) { controller.addSubtitle(subtitle) }
    }

    private suspend fun withActivePlayerResult(
        id: JsonElement?,
        operation: suspend () -> Result<Unit>,
    ): String = when (activePlayer()) {
        ActivePlayer.ControllerFailure -> controllerFailure(id)
        ActivePlayer.Inactive -> inactivePlayer(id)
        is ActivePlayer.Available -> when (invokeResult(operation)) {
            ControllerCall.Failure -> controllerFailure(id)
            is ControllerCall.Success -> ok(id)
        }
    }

    private suspend fun dispatchApplicationGetProperties(id: JsonElement?, params: JsonObject): String {
        val requested = params["properties"].stringArrayOrNull() ?: return invalidParams(id)

        return when (val snapshot = invokeController { controller.snapshot() }) {
            ControllerCall.Failure -> controllerFailure(id)
            is ControllerCall.Success -> {
                val result = buildJsonObject {
                    for (property in requested) {
                        when (property) {
                            "volume" -> put("volume", snapshot.value.volume)
                            "muted" -> put("muted", snapshot.value.muted)
                        }
                    }
                }
                rpcResult(id, result)
            }
        }
    }

    private suspend fun dispatchSetVolume(id: JsonElement?, params: JsonObject): String {
        val volume = params["volume"].strictIntOrNull()?.takeIf { it in 0..100 }
            ?: return invalidParams(id)

        return when (val result = invokeResult { controller.setVolume(volume) }) {
            ControllerCall.Failure -> controllerFailure(id)
            is ControllerCall.Success -> rpcResult(id, JsonPrimitive(result.value))
        }
    }

    private fun dispatchReceiverInfo(id: JsonElement?, params: JsonObject): String {
        if (params.isNotEmpty()) return invalidParams(id)
        val identity = receiverIdentity ?: return methodNotFound(id)
        return rpcResult(
            id,
            buildJsonObject {
                put("name", identity.name)
                put("uuid", identity.uuid)
                put("x4789", true)
                put("httpPort", ReceiverPorts.HTTP)
                identity.manufacturer?.let { put("manufacturer", it) }
                identity.model?.let { put("model", it) }
                identity.deviceName?.let { put("deviceName", it) }
                identity.deviceKind?.let { put("deviceKind", it) }
                put("externalPlayers", installedPlayersJson())
                put(
                    "hardwareVideoCodecs",
                    buildJsonArray { identity.hardwareVideoCodecs.forEach { add(JsonPrimitive(it)) } },
                )
                engineOverrides?.let { put("engine", it.currentEngineName()) }
                // Capability handshake (capsVersion 1): lets the phone stop preferring audio
                // tracks this box provably can't decode, and stop guessing the push port.
                put("wsPort", ReceiverPorts.WEB_SOCKET)
                put("engines", buildJsonArray { add(JsonPrimitive("exo")) })
                put(
                    "audioCodecs",
                    buildJsonObject {
                        put(
                            "decode",
                            buildJsonArray { identity.audioDecodeCodecs.forEach { add(JsonPrimitive(it)) } },
                        )
                        put(
                            "passthrough",
                            buildJsonArray { identity.audioPassthroughCodecs.forEach { add(JsonPrimitive(it)) } },
                        )
                        // What the bundled libmpv engine adds in software: ffmpeg decodes
                        // effectively every audio codec a release ships with.
                        put(
                            "software",
                            buildJsonArray { SOFTWARE_AUDIO_CODECS.forEach { add(JsonPrimitive(it)) } },
                        )
                    },
                )
                put("capsVersion", 1)
            },
        )
    }

    private fun dispatchGetExternalPlayers(id: JsonElement?, params: JsonObject): String {
        if (params.isNotEmpty()) return invalidParams(id)
        if (externalPlayers == null) return methodNotFound(id)
        return rpcResult(id, buildJsonObject { put("players", installedPlayersJson()) })
    }

    private suspend fun dispatchOpenExternal(id: JsonElement?, params: JsonObject): String {
        val port = externalPlayers ?: return methodNotFound(id)
        val url = params["url"].stringOrNull()?.takeIf(String::isHttpUrl) ?: return invalidParams(id)
        if (!params.hasOptionalString("title") || !params.hasOptionalString("player")) {
            return invalidParams(id)
        }
        val title = params["title"].stringOrNull()
        val targetPackage = params["player"].stringOrNull()?.takeIf { it.isNotBlank() }

        // Resume where the viewer actually is. The phone MAY send an explicit position (it knows
        // its own scrubber); otherwise take the receiver's own playhead. Either way the external
        // player starts at the frame the handoff happened on instead of at 0:00.
        val requestedPosition = params["position"]?.let { element ->
            (element as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
        }
        val livePosition = requestedPosition
            ?: runCatching { controller.snapshot().positionSeconds }.getOrDefault(0.0)
        val handoff = ExternalHandoffContext(
            positionMillis = (livePosition * 1_000).toLong().coerceAtLeast(0),
            subtitleURL = params["subtitleUrl"].stringOrNull()?.takeIf { it.isNotBlank() },
            subtitleName = params["subtitleName"].stringOrNull()?.takeIf { it.isNotBlank() },
            headers = (params["headers"] as? JsonObject)
                ?.mapNotNull { (key, value) -> value.stringOrNull()?.let { key to it } }
                ?.toMap()
                .orEmpty(),
        )

        val launched = when (
            val call = invokeController { port.launch(url, title, targetPackage, handoff) }
        ) {
            ControllerCall.Failure -> false
            is ControllerCall.Success<Boolean> -> call.value
        }
        if (!launched) return rpcError(id, CONTROLLER_FAILURE, "External player launch failed")

        // TWO-WAY SYNC (2026-08-03). An external player now owns the screen, so THIS receiver's
        // player is no longer authoritative. The handoff used to return `ok` and emit nothing —
        // notifications are only produced from `ReceiverEvent.Play/Stop` inside the internal player
        // — so the phone never learned it had stopped casting. It kept showing live cast controls
        // that silently did nothing, and only a disconnect/reconnect (or an app restart) cleared it.
        // That is the Dolby-Vision path: the DV stream fails, the user picks Just Player / Kodi /
        // TiviMate, and the two sides drift apart. Stopping here emits `Player.OnStop`, which the
        // phone already handles, so the handoff is reported the same way any other stop is.
        //
        // Best-effort on purpose: the handoff has ALREADY succeeded. A stop failure — normally just
        // "nothing was playing", the common case when the user picks an external player from the
        // playback-error overlay before a player ever started — must not turn a successful launch
        // into an RPC error the phone would surface as a failed cast.
        // Announce the handoff BEFORE the stop. The stop alone told the phone "cast over", which
        // is wrong: the video is still on this TV, just in another app. A phone that understands
        // `X4789.OnExternalHandoff` keeps its session and shows where the video went instead of
        // dropping the cast bar and stranding the viewer with no controls and no way back.
        val label = externalPlayers?.installedPlayers()
            ?.firstOrNull { targetPackage == null || it.packageName == targetPackage }
            ?.label
            ?: targetPackage
            ?: "another player"
        // Best-effort like the stop below: the launch has already succeeded, so a failure to
        // announce must never turn it into an RPC error the phone reports as a failed cast.
        runCatching { controller.announceExternalHandoff(label, targetPackage.orEmpty()) }

        invokeResult { controller.stop() }
        return ok(id)
    }

    private fun installedPlayersJson(): JsonArray = buildJsonArray {
        externalPlayers?.installedPlayers()?.forEach { player ->
            add(
                buildJsonObject {
                    put("label", player.label)
                    put("package", player.packageName)
                },
            )
        }
    }

    private suspend fun dispatchNowPlaying(id: JsonElement?, params: JsonObject): String {
        val title = params["title"].stringOrNull() ?: return invalidParams(id)
        if (!params.hasOptionalString("subtitle")) return invalidParams(id)
        val subtitle = params["subtitle"].stringOrNull()
        val isLive = params.optionalBooleanOrDefault("isLive", default = false) ?: return invalidParams(id)
        if (!params.hasOptionalString("artworkURL")) return invalidParams(id)
        if (!params.hasOptionalString("posterURL")) return invalidParams(id)
        if (!params.hasOptionalString("stage")) return invalidParams(id)
        if (!params.hasOptionalString("castID")) return invalidParams(id)
        val stageValue = params["stage"].stringOrNull()
        val preparationStage = ReceiverPreparationStage.fromWire(stageValue)
        if (stageValue != null && preparationStage == null) return invalidParams(id)
        val castId = params["castID"].stringOrNull()?.takeIf { it.length in 1..128 }
        if (params.containsKey("castID") && params["castID"] !is kotlinx.serialization.json.JsonNull && castId == null) {
            return invalidParams(id)
        }
        // Artwork is a screen concern, not a player one — it goes straight to the Activity. Staged
        // before the controller call so the overlay is already dressed when Player.Open arrives.
        NowPlayingArtwork.stage(
            landscapeURL = params["artworkURL"].stringOrNull(),
            posterURL = params["posterURL"].stringOrNull(),
        )
        // A new title's staged metadata invalidates the previous title's subtitle shortlist.
        SubtitleOptions.clear()
        // Says which side is missing when the waiting screen comes up bare: a phone too old to
        // send the fields at all looks identical to a fetch that failed, without this.
        com.fourseveneightnine.tv.startup.ReceiverDiagnostics.record(
            "nowplaying.art",
            "landscape=${params.containsKey("artworkURL")} poster=${params.containsKey("posterURL")}",
        )

        return when (
            invokeController {
                metadataMutex.withLock {
                    controller.stageNowPlaying(title, subtitle, isLive, preparationStage, castId)
                    nextMetadataToken += 1
                    stagedMetadata = StagedMetadata(
                        token = nextMetadataToken,
                        title = title,
                        subtitle = subtitle,
                        isLive = isLive,
                        nextUp = parseNextUp(params),
                        chapterSeconds = parseChapterSeconds(params),
                        castId = castId,
                        startPositionMs = parseResumeMillis(params),
                    )
                }
            }
        ) {
            ControllerCall.Failure -> controllerFailure(id)
            is ControllerCall.Success -> ok(id)
        }
    }

    private suspend fun dispatchAudioProfile(id: JsonElement?, params: JsonObject): String {
        if (params.size != 1) return invalidParams(id)
        val profile = ReceiverAudioProfile.fromWire(params["profile"].stringOrNull())
            ?: return invalidParams(id)
        return when (val result = invokeResult { controller.applyAudioProfile(profile) }) {
            ControllerCall.Failure -> controllerFailure(id)
            is ControllerCall.Success -> rpcResult(id, JsonPrimitive(result.value))
        }
    }

    /**
     * The phone's Continue-Watching list, mirrored onto the box's home rail.
     *
     * PUSH, NOT PULL: the box cannot ask — it has no idea which phones exist, and the phone is the
     * only side that knows its watch history. The phone sends the whole list when a first-party
     * receiver session connects; reconnecting refreshes that snapshot. The receiver replaces its
     * mirrored rows wholesale (`RecentsStore.replacePhoneRecents`), so a cleared phone list is
     * reflected at the next successful first-party handshake.
     *
     * NOTHING IS FETCHED HERE. A poster URL is only pulled if the rail actually draws that tile,
     * and `PhoneRecents.sanitize` has already rejected any scheme that is not http(s) — the box
     * must never be talked into reading its own storage by a row on a shelf.
     *
     * ONE BAD ROW COSTS ONE ROW. Malformed entries are dropped individually rather than failing the
     * call: a phone one version ahead must not be able to blank the user's rail.
     */
    private fun dispatchSetRecents(id: JsonElement?, params: JsonObject): String {
        val raw = params["items"] as? JsonArray ?: return invalidParams(id)
        val entries = raw.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            PhoneRecents.sanitize(
                url = row["url"].stringOrNull(),
                title = row["title"].stringOrNull(),
                subtitle = row["subtitle"].stringOrNull(),
                posterUrl = row["posterURL"].stringOrNull() ?: row["posterUrl"].stringOrNull(),
                landscapeUrl = row["landscapeURL"].stringOrNull()
                    ?: row["backdropURL"].stringOrNull()
                    ?: row["backdropUrl"].stringOrNull(),
                overview = row["overview"].stringOrNull(),
                positionMillis = row["positionMillis"].longOrZero(),
                durationMillis = row["durationMillis"].longOrZero(),
                timestamp = row["timestamp"].longOrZero(),
            )
        }
        PhoneRecents.publish(entries)
        com.fourseveneightnine.tv.startup.ReceiverDiagnostics.record(
            "recents.push",
            "received=${raw.size} kept=${entries.size}",
        )
        return ok(id)
    }

    private suspend fun dispatchSubtitleStyle(id: JsonElement?, params: JsonObject): String =
        when (invokeController { controller.stageSubtitleStyle(params) }) {
            ControllerCall.Failure -> controllerFailure(id)
            is ControllerCall.Success -> ok(id)
        }

    /**
     * The phone's addon subtitle shortlist for the current title, so the TV's own Subs picker can
     * offer more than the file's embedded tracks. Nothing is fetched here — a URL is only pulled if
     * a viewer picks it on the television.
     */
    private fun dispatchSubtitleOptions(id: JsonElement?, params: JsonObject): String {
        val raw = params["options"] as? JsonArray ?: return invalidParams(id)
        val options = raw.map { element ->
            val option = element as? JsonObject ?: return invalidParams(id)
            val url = option["url"].stringOrNull() ?: return invalidParams(id)
            if (!option.hasOptionalString("label")) return invalidParams(id)
            if (!option.hasOptionalString("language")) return invalidParams(id)
            SubtitleOption(
                label = option["label"].stringOrNull().orEmpty(),
                url = url,
                language = option["language"].stringOrNull().orEmpty(),
            )
        }
        SubtitleOptions.stage(options)
        com.fourseveneightnine.tv.startup.ReceiverDiagnostics.record(
            "subtitle.options",
            "sent=${options.size} kept=${SubtitleOptions.options.value.size}",
        )
        return ok(id)
    }

    /** Live engine switch (user action: wrong colors / dead decode). Reopens the current title. */
    private suspend fun dispatchSetSessionEngine(id: JsonElement?, params: JsonObject): String {
        val port = engineOverrides ?: return methodNotFound(id)
        val engine = params["engine"].stringOrNull()
            ?.lowercase()
            ?.takeIf { it == "exo" || it == "mpv" }
            ?: return invalidParams(id)
        val applied = port.switchNow(engine)
        return rpcResult(
            id,
            buildJsonObject {
                put("applied", applied)
                put("engine", port.currentEngineName())
            },
        )
    }

    private suspend fun dispatchSetSettingValue(id: JsonElement?, params: JsonObject): String {
        val setting = params["setting"].stringOrNull()?.takeIf { it.isNotBlank() }
        val value = params["value"] as? JsonPrimitive
        if (setting == null || value == null || !value.isSupportedSettingValue()) return invalidParams(id)

        // The engine choice belongs to the Activity, not to a controller — a controller cannot
        // replace itself. Handle it here so neither engine has to carry a copy of this branch.
        if (setting.equals(ReceiverEnginePolicy.SETTING_NAME, ignoreCase = true)) {
            val port = engineOverrides ?: return rpcResult(id, JsonPrimitive(false))
            val normalized = value.takeIf { it.isString }
                ?.content
                ?.let(ReceiverEnginePolicy::normalizedOverride)
                ?: return invalidParams(id)
            port.setOverride(normalized)
            return rpcResult(id, JsonPrimitive(true))
        }

        return when (val result = invokeResult { controller.applySetting(setting, value) }) {
            ControllerCall.Failure -> controllerFailure(id)
            is ControllerCall.Success -> rpcResult(id, JsonPrimitive(result.value))
        }
    }

    private suspend fun dispatchExecuteAction(id: JsonElement?, params: JsonObject): String {
        val action = params["action"].stringOrNull()?.takeIf { it.isNotBlank() }
            ?: return invalidParams(id)

        return when (invokeResult { controller.executeAction(action) }) {
            ControllerCall.Failure -> controllerFailure(id)
            is ControllerCall.Success -> ok(id)
        }
    }

    private suspend fun dispatchGoTo(id: JsonElement?, params: JsonObject): String {
        if (!params.hasReceiverPlayerId()) return invalidParams(id)
        if (!params["to"].isSupportedGoToTarget()) return invalidParams(id)

        return when (activePlayer()) {
            ActivePlayer.ControllerFailure -> controllerFailure(id)
            ActivePlayer.Inactive -> inactivePlayer(id)
            is ActivePlayer.Available -> ok(id)
        }
    }

    private suspend fun activePlayer(): ActivePlayer =
        when (val snapshot = invokeController { controller.snapshot() }) {
            ControllerCall.Failure -> ActivePlayer.ControllerFailure
            is ControllerCall.Success -> {
                if (snapshot.value.active) ActivePlayer.Available(snapshot.value) else ActivePlayer.Inactive
            }
        }

    private fun playerProperties(
        requested: List<String>,
        snapshot: ReceiverSnapshot,
        tracks: ReceiverTracks,
    ): JsonObject {
        val position = snapshot.positionSeconds.finiteNonNegative()
        val duration = snapshot.durationSeconds.finiteNonNegative()
        val percentage = if (duration > 0.0) {
            (position / duration * 100.0).coerceIn(0.0, 100.0)
        } else {
            0.0
        }

        return buildJsonObject {
            for (property in requested) {
                when (property) {
                    "time" -> put("time", kodiTime(position))
                    "totaltime" -> put("totaltime", kodiTime(duration))
                    "percentage" -> put("percentage", percentage)
                    "speed" -> put("speed", snapshot.speed)
                    "audiostreams" -> put("audiostreams", trackArray(tracks.audio))
                    "currentaudiostream" -> tracks.currentAudio?.let { put("currentaudiostream", trackObject(it)) }
                    "subtitles" -> put("subtitles", trackArray(tracks.subtitles))
                    "currentsubtitle" -> tracks.currentSubtitle?.let { put("currentsubtitle", trackObject(it)) }
                    "subtitleenabled" -> put("subtitleenabled", tracks.subtitleEnabled)
                }
            }
        }
    }

    private fun trackArray(tracks: List<ReceiverTrack>): JsonArray = buildJsonArray {
        tracks.forEach { add(trackObject(it)) }
    }

    private fun trackObject(track: ReceiverTrack): JsonObject = buildJsonObject {
        put("index", track.index)
        put("language", track.language)
        put("name", track.name)
        track.codec?.let { put("codec", it) }
        track.channels?.let { put("channels", it) }
        track.isOriginal?.let { put("isoriginal", it) }
    }

    private fun parseOpenMedia(params: JsonObject): ParsedMedia? {
        val item = params["item"] as? JsonObject ?: return null
        val file = item["file"].stringOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val separator = file.indexOf('|')
        val url = if (separator >= 0) file.substring(0, separator) else file
        val headerSuffix = if (separator >= 0) file.substring(separator + 1) else null

        if (!url.isHttpUrl()) return null
        val headers = parseKodiHeaders(headerSuffix) ?: return null
        return ParsedMedia(url = url, headers = headers)
    }

    private fun parseKodiHeaders(suffix: String?): Map<String, String>? {
        if (suffix == null || suffix.isEmpty()) return emptyMap()

        val headers = LinkedHashMap<String, String>()
        val caseInsensitiveNames = HashSet<String>()
        for (pair in suffix.split('&')) {
            val equals = pair.indexOf('=')
            if (pair.isEmpty() || equals <= 0) return null

            val name = decodeHeaderPart(pair.substring(0, equals)) ?: return null
            val value = decodeHeaderPart(pair.substring(equals + 1)) ?: return null
            val normalizedName = name.lowercase(Locale.ROOT)
            if (!name.isHttpHeaderName() || value.hasUnsafeHeaderCharacters()) return null
            if (!caseInsensitiveNames.add(normalizedName)) return null

            headers[name] = value
        }
        return headers
    }

    private fun parseRequest(payload: String): ParseOutcome {
        val objectValue = try {
            kodiJson.parseToJsonElement(payload) as? JsonObject
        } catch (_: Exception) {
            return ParseOutcome.ParseError
        } ?: return ParseOutcome.InvalidRequest

        val version = objectValue["jsonrpc"].stringOrNull()
        val method = objectValue["method"].stringOrNull()?.takeIf { it.isNotBlank() }
        val id = objectValue["id"]
        if (version != "2.0" || method == null || (id != null && !id.isValidRequestId())) {
            return ParseOutcome.InvalidRequest
        }

        return ParseOutcome.Request(RpcRequest(id = id, method = method, params = objectValue["params"]))
    }

    private suspend fun <T> invokeController(block: suspend () -> T): ControllerCall<T> =
        try {
            ControllerCall.Success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            ControllerCall.Failure
        }

    private suspend fun <T> invokeResult(block: suspend () -> Result<T>): ControllerCall<T> =
        when (val call = invokeController(block)) {
            ControllerCall.Failure -> ControllerCall.Failure
            is ControllerCall.Success -> call.value.fold(
                onSuccess = { ControllerCall.Success(it) },
                onFailure = { ControllerCall.Failure },
            )
        }

    private fun ok(id: JsonElement?): String = rpcResult(id, JsonPrimitive("OK"))

    private fun speedResult(id: JsonElement?, speed: Int): String =
        rpcResult(
            id,
            buildJsonObject {
                put("speed", speed)
            },
        )

    private fun invalidParams(id: JsonElement?): String = rpcError(id, INVALID_PARAMS, "Invalid params")

    private fun methodNotFound(id: JsonElement?): String =
        rpcError(id, METHOD_NOT_FOUND, "Method not found")

    private fun inactivePlayer(id: JsonElement?): String =
        rpcError(id, INACTIVE_PLAYER, "Player not found")

    private fun controllerFailure(id: JsonElement?): String =
        rpcError(id, CONTROLLER_FAILURE, "Controller failure")

    private data class RpcRequest(
        val id: JsonElement?,
        val method: String,
        val params: JsonElement?,
    )

    private data class ParsedMedia(
        val url: String,
        val headers: Map<String, String>,
    )

    private data class StagedMetadata(
        val token: Long,
        val title: String,
        val subtitle: String?,
        val isLive: Boolean,
        val nextUp: NextUpItem? = null,
        val chapterSeconds: List<Double> = emptyList(),
        val castId: String? = null,
        val startPositionMs: Long? = null,
    )

    /**
     * Optional extras on `X4789.NowPlaying`: what follows this title, and where its chapters fall.
     *
     * Both are things only the phone can know — the receiver has no catalogue and ExoPlayer does
     * not surface container chapters. Both are OPTIONAL, and a MALFORMED one is IGNORED rather than
     * rejected: refusing the whole call would cost the viewer their film to protect a tick mark on
     * a seek bar.
     */
    private fun parseNextUp(params: JsonObject): NextUpItem? {
        val next = params["nextUp"] as? JsonObject ?: return null
        val url = next["url"].stringOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return NextUpItem(
            url = url,
            title = next["title"].stringOrNull(),
            subtitle = next["subtitle"].stringOrNull(),
            posterUrl = next["posterURL"].stringOrNull(),
        )
    }

    private fun parseChapterSeconds(params: JsonObject): List<Double> {
        val array = params["chapters"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            (element as? JsonPrimitive)?.doubleOrNull?.takeIf { it >= 0.0 }
        }.sorted().take(MAX_CHAPTERS)
    }

    /**
     * Where the phone wants this title to start, in seconds. OPTIONAL, and MALFORMED IS IGNORED for
     * the same reason as the two above: a bad number must cost the viewer a slower start, never
     * their film.
     *
     * The phone still sends its own `Player.Seek` after `Player.Open`, so a receiver that ignores
     * this field behaves exactly as it always did. Supplying it lets the engine prepare the source
     * at the right byte range the first time instead of buffering position 0 and throwing it away.
     */
    private fun parseResumeMillis(params: JsonObject): Long? =
        (params["resumeSeconds"] as? JsonPrimitive)
            ?.doubleOrNull
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?.let { (it * 1000.0).toLong() }

    private sealed interface ParseOutcome {
        data object ParseError : ParseOutcome

        data object InvalidRequest : ParseOutcome

        data class Request(val value: RpcRequest) : ParseOutcome
    }

    private sealed interface ControllerCall<out T> {
        data object Failure : ControllerCall<Nothing>

        data class Success<T>(val value: T) : ControllerCall<T>
    }

    private sealed interface ActivePlayer {
        data object ControllerFailure : ActivePlayer

        data object Inactive : ActivePlayer

        data class Available(val snapshot: ReceiverSnapshot) : ActivePlayer
    }

    private companion object {
        const val PARSE_ERROR = -32_700
        const val INVALID_REQUEST = -32_600
        const val METHOD_NOT_FOUND = -32_601
        const val INVALID_PARAMS = -32_602
        const val INACTIVE_PLAYER = -32_100
        const val CONTROLLER_FAILURE = -32_000

        const val PLAYER_ID = 1

        /** A rail cannot show more marks than this legibly, and a bad sender must not flood it. */
        const val MAX_CHAPTERS = 64
        val EMPTY_PARAMS = JsonObject(emptyMap())

        val supportedMethods = setOf(
            "JSONRPC.Ping",
            "Player.GetActivePlayers",
            "Player.Open",
            "Player.GetProperties",
            "Player.Seek",
            "Player.PlayPause",
            "Player.Stop",
            "Player.SetSpeed",
            "Player.SetAudioStream",
            "Player.SetSubtitle",
            "Player.AddSubtitle",
            "Application.GetProperties",
            "Application.SetVolume",
            "X4789.GetReceiverInfo",
            "X4789.GetExternalPlayers",
            "X4789.OpenExternal",
            "X4789.NowPlaying",
            "X4789.AudioProfile",
            "X4789.SubtitleOptions",
            "X4789.SubtitleStyle",
            "X4789.SetSessionEngine",
            "X4789.SetRecents",
            "Settings.SetSettingValue",
            "Input.ExecuteAction",
            "Player.GoTo",
        )

        val TRACK_PROPERTIES = setOf(
            "audiostreams",
            "currentaudiostream",
            "subtitles",
            "currentsubtitle",
            "subtitleenabled",
        )
    }
}

/**
 * Persists the requested playback engine. The change takes effect the next time the receiver
 * Activity starts, because the engine is chosen once at creation and owns the video surface.
 */
interface EngineOverridePort {
    fun setOverride(normalizedOverride: String)
    fun currentEngineName(): String

    /**
     * Switch the RUNNING engine now, reopening the current title at its position when one is
     * playing. Explicit user action (wrong colors / broken decode) — distinct from a settings
     * push, which must never tear down live playback. Default: unsupported.
     */
    suspend fun switchNow(engineName: String): Boolean = false
}

data class ReceiverIdentity(
    val name: String,
    val uuid: String,
    val manufacturer: String? = null,
    val model: String? = null,
    val deviceName: String? = null,
    val deviceKind: String? = null,
    /**
     * Video codecs this box decodes in hardware, so the phone can rank sources for THIS television
     * rather than guessing. Empty means "unknown, assume nothing" — an older receiver omits the
     * field entirely, so the phone must treat absent and empty the same way.
     */
    val hardwareVideoCodecs: List<String> = emptyList(),
    /**
     * Audio codecs a MediaCodec decoder exists for on this box (hardware or software — decode is
     * decode). Empty means "unknown" and the phone must not gate on it. Passthrough is what the
     * active audio route accepts bit-exact; below API 29 Android cannot probe that, so an empty
     * passthrough list also means UNKNOWN, never "none" — both sides honor that.
     */
    val audioDecodeCodecs: List<String> = emptyList(),
    val audioPassthroughCodecs: List<String> = emptyList(),
)

/** Audio codecs the bundled libmpv (ffmpeg) engine decodes in software on any box. */
private val SOFTWARE_AUDIO_CODECS = listOf(
    "aac", "ac3", "eac3", "dts", "dtshd", "truehd", "flac", "opus", "vorbis", "mp3", "pcm",
)

private fun JsonObject.hasReceiverPlayerId(): Boolean = this["playerid"].strictIntOrNull() == 1

private fun JsonElement?.stringOrNull(): String? {
    val primitive = this as? JsonPrimitive ?: return null
    return primitive.takeIf { it.isString }?.content
}

private fun JsonObject.hasOptionalString(name: String): Boolean =
    !containsKey(name) || this[name] === JsonNull || this[name].stringOrNull() != null

private fun JsonObject.optionalBooleanOrDefault(name: String, default: Boolean): Boolean? {
    if (!containsKey(name)) return default
    return this[name].strictBooleanOrNull()
}

private fun JsonElement?.strictBooleanOrNull(): Boolean? {
    val primitive = this as? JsonPrimitive ?: return null
    if (this === JsonNull || primitive.isString) return null
    return primitive.booleanOrNull
}

private fun JsonElement?.strictIntOrNull(): Int? {
    val primitive = this as? JsonPrimitive ?: return null
    if (this === JsonNull || primitive.isString || primitive.booleanOrNull != null) return null
    return primitive.intOrNull
}

/**
 * A millisecond field off the wire, defaulting to 0 for anything that is not a plain number.
 *
 * ZERO RATHER THAN NULL because every caller is a display quantity (a resume position, a runtime,
 * a timestamp) whose "unknown" and "none" render identically — a rail tile with no progress bar.
 * Failing the whole row over a missing duration would lose the poster too.
 */
private fun JsonElement?.longOrZero(): Long {
    val primitive = this as? JsonPrimitive ?: return 0L
    if (this === JsonNull || primitive.isString || primitive.booleanOrNull != null) return 0L
    return primitive.longOrNull ?: 0L
}

private fun JsonElement?.finiteNumberOrNull(): Double? {
    val primitive = this as? JsonPrimitive ?: return null
    if (this === JsonNull || primitive.isString || primitive.booleanOrNull != null) return null
    return primitive.doubleOrNull?.takeIf { it.isFinite() }
}

private fun JsonElement?.stringArrayOrNull(): List<String>? {
    val array = this as? JsonArray ?: return null
    val strings = ArrayList<String>(array.size)
    for (element in array) {
        strings += element.stringOrNull() ?: return null
    }
    return strings
}

private fun JsonElement?.isSupportedSettingValue(): Boolean {
    val primitive = this as? JsonPrimitive ?: return false
    return this !== JsonNull
}

private fun JsonElement?.isSupportedGoToTarget(): Boolean =
    when (this) {
        is JsonPrimitive -> {
            if (isString) content == "next" || content == "previous"
            else strictIntOrNull()?.let { it >= 0 } == true
        }

        else -> false
    }

private fun JsonElement?.isValidRequestId(): Boolean {
    if (this === JsonNull) return true
    val primitive = this as? JsonPrimitive ?: return false
    if (primitive.isString) return true
    if (primitive.booleanOrNull != null) return false
    return primitive.doubleOrNull?.isFinite() == true
}

private fun JsonElement?.toSeekCommandOrNull(): SeekCommand? =
    when (this) {
        is JsonPrimitive -> {
            when {
                this === JsonNull -> null
                isString -> seekStep(content)
                else -> finiteNumberOrNull()?.takeIf { it in 0.0..100.0 }?.let(SeekCommand::Percentage)
            }
        }

        is JsonObject -> toObjectSeekCommandOrNull()
        else -> null
    }

private fun JsonObject.toObjectSeekCommandOrNull(): SeekCommand? {
    return when {
        keys == setOf("percentage") ->
            this["percentage"].finiteNumberOrNull()?.takeIf { it in 0.0..100.0 }?.let(SeekCommand::Percentage)

        keys == setOf("step") -> this["step"].stringOrNull()?.let(::seekStep)

        // This is Kodi's explicit relative-seconds form. A direct absolute-time object remains
        // available through {time:{...}} or with another time component such as {minutes:1}.
        keys == setOf("seconds") -> this["seconds"].finiteNumberOrNull()?.let(SeekCommand::RelativeSeconds)

        keys == setOf("time") -> (this["time"] as? JsonObject)?.toAbsoluteSecondsOrNull()?.let(SeekCommand::AbsoluteSeconds)

        keys.isNotEmpty() && keys.all { it in TIME_KEYS } ->
            toAbsoluteSecondsOrNull()?.let(SeekCommand::AbsoluteSeconds)

        else -> null
    }
}

private fun JsonObject.toAbsoluteSecondsOrNull(): Double? {
    val hours = timeComponentOrNull("hours") ?: return null
    val minutes = timeComponentOrNull("minutes") ?: return null
    val seconds = timeComponentOrNull("seconds") ?: return null
    val milliseconds = timeComponentOrNull("milliseconds") ?: return null
    if (hours < 0 || minutes !in 0..59 || seconds !in 0..59 || milliseconds !in 0..999) return null

    return hours * 3_600.0 + minutes * 60.0 + seconds + milliseconds / 1_000.0
}

private fun JsonObject.timeComponentOrNull(name: String): Int? =
    if (!containsKey(name)) 0 else this[name].strictIntOrNull()

private fun seekStep(value: String): SeekCommand.RelativeSeconds? =
    when (value) {
        "smallforward" -> SeekCommand.RelativeSeconds(10.0)
        "smallbackward" -> SeekCommand.RelativeSeconds(-10.0)
        "bigforward" -> SeekCommand.RelativeSeconds(30.0)
        "bigbackward" -> SeekCommand.RelativeSeconds(-30.0)
        else -> null
    }

private fun Double.finiteNonNegative(): Double = if (isFinite()) coerceAtLeast(0.0) else 0.0

private fun String.isHttpUrl(): Boolean =
    try {
        val uri = URI(this)
        uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && !uri.host.isNullOrBlank()
    } catch (_: Exception) {
        val lower = lowercase(Locale.ROOT)
        (lower.startsWith("http://") || lower.startsWith("https://")) && length > 10
    }

private fun decodeHeaderPart(value: String): String? =
    try {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    } catch (_: IllegalArgumentException) {
        null
    }

private fun String.isHttpHeaderName(): Boolean =
    isNotEmpty() && all { character ->
        character.isLetterOrDigit() || character in "!#$%&'*+-.^_`|~"
    }

private fun String.hasUnsafeHeaderCharacters(): Boolean =
    any { it == '\r' || it == '\n' || it == '\u0000' }

private val TIME_KEYS = setOf("hours", "minutes", "seconds", "milliseconds")
