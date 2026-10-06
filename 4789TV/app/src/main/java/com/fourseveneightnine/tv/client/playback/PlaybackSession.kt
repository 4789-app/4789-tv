package com.fourseveneightnine.tv.client.playback

import com.fourseveneightnine.tv.client.iptv.IptvVod
import android.content.Context
import android.view.SurfaceHolder
import com.fourseveneightnine.tv.player.ExternalPlayerIntentPolicy
import com.fourseveneightnine.tv.player.PlaybackDiagnostics
import com.fourseveneightnine.tv.player.ReceiverPlaybackPhase
import com.fourseveneightnine.tv.player.ReceiverSubtitleStyle
import com.fourseveneightnine.tv.player.SwappableReceiverController
import com.fourseveneightnine.tv.player.upscale.UpscaleMode
import com.fourseveneightnine.tv.protocol.InstalledExternalPlayer
import com.fourseveneightnine.tv.protocol.NowPlayingArt
import com.fourseveneightnine.tv.protocol.NowPlayingArtwork
import com.fourseveneightnine.tv.protocol.OpenMediaRequest
import com.fourseveneightnine.tv.protocol.ReceiverController
import com.fourseveneightnine.tv.protocol.ReceiverEvent
import com.fourseveneightnine.tv.protocol.ReceiverSnapshot
import com.fourseveneightnine.tv.protocol.ReceiverTracks
import com.fourseveneightnine.tv.protocol.SeekCommand
import com.fourseveneightnine.tv.protocol.SubtitleSelection
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import com.fourseveneightnine.tv.client.ui.screens.settings.SettingsKeys

/**
 * The single owner of the [ReceiverController].
 *
 * Both routes into playback go through here: the local Play action and the phone's `Player.Open`.
 * That is the whole point — the old Activity let the RPC dispatcher reach the controller directly
 * while the on-TV controls reached it by another path, so nothing could answer "who started this
 * and is the player on screen?" without a flag that could get stuck.
 *
 * The dispatcher holds this object as its [ReceiverController], so a phone open lands on
 * [open] with [Origin.Phone] and raises [openRequests], which is what navigates the shell to the
 * player from wherever it was — including from inside Settings (plan §8).
 */
internal class PlaybackSession(
    private val controller: SwappableReceiverController,
    private val appContext: Context,
    private val scope: CoroutineScope,
) : ReceiverController by controller {

    /** Who asked for this title. The cast chip and the D-pad routing both read it. */
    enum class Origin { Phone, Local }

    /** TV-local context for channel navigation. Never leaves this process or enters the wire DTO. */
    data class LiveChannel(val id: String, val sourceId: String, val group: String)

    /** The scope the shell fires player commands on, so a key press never blocks on the engine. */
    val uiScope: CoroutineScope get() = scope

    private val _origin = MutableStateFlow<Origin?>(null)
    val origin: StateFlow<Origin?> = _origin.asStateFlow()

    private val _liveChannel = MutableStateFlow<LiveChannel?>(null)
    val liveChannel: StateFlow<LiveChannel?> = _liveChannel.asStateFlow()
    private val _iptvVod = MutableStateFlow<IptvVod?>(null)
    val iptvVod: StateFlow<IptvVod?> = _iptvVod.asStateFlow()

    private val _phase = MutableStateFlow<ReceiverPlaybackPhase>(ReceiverPlaybackPhase.Idle)
    val phase: StateFlow<ReceiverPlaybackPhase> = _phase.asStateFlow()

    private val _snapshot = MutableStateFlow(ReceiverSnapshot())
    val snapshot: StateFlow<ReceiverSnapshot> = _snapshot.asStateFlow()
    private var lastPositionMillis = 0L
    private val trackPrefs = appContext.getSharedPreferences("tv_player_preferences", Context.MODE_PRIVATE)
    private val presentationPrefs = appContext.getSharedPreferences("receiver_presentation", Context.MODE_PRIVATE)
    private var openGeneration = 0L
    private var restoredGeneration = -1L
    private var trackChoiceVersion = 0L

    private val _tracks = MutableStateFlow(ReceiverTracks())
    val tracks: StateFlow<ReceiverTracks> = _tracks.asStateFlow()

    private val _diagnostics = MutableStateFlow(PlaybackDiagnostics())
    val diagnosticsState: StateFlow<PlaybackDiagnostics> = _diagnostics.asStateFlow()

    private val _surfaceReady = MutableStateFlow(false)
    val surfaceReadyState: StateFlow<Boolean> = _surfaceReady.asStateFlow()

    /** Artwork the phone staged for this title (`X4789.NowPlaying`), or a local card's poster. */
    val nowPlayingArt: StateFlow<NowPlayingArt?> = NowPlayingArtwork.art

    /** Exo side channels the player screen draws with. mpv renders its own subtitles in-video. */
    val cues: StateFlow<List<androidx.media3.common.text.Cue>> = controller.cues
    val videoAspectRatio: StateFlow<Float> = controller.videoAspectRatio
    val subtitleStyle: StateFlow<ReceiverSubtitleStyle> = controller.subtitleStyle

    /** One event per accepted open. The shell navigates to the player on it. */
    private val _openRequests = MutableSharedFlow<Origin>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val openRequests: SharedFlow<Origin> = _openRequests.asSharedFlow()

    init {
        trackPrefs.getString("subtitleStyle", null)?.let { saved ->
            runCatching { Json.parseToJsonElement(saved) as? JsonObject }.getOrNull()?.let { style ->
                scope.launch { controller.stageSubtitleStyle(style) }
            }
        }
        scope.launch { controller.playbackPhase.collect { phase ->
            _phase.value = phase
            if (phase is ReceiverPlaybackPhase.Playing && _origin.value == Origin.Local && restoredGeneration != openGeneration) {
                restoredGeneration = openGeneration
                val generation = openGeneration
                val choices = trackChoiceVersion
                scope.launch { restoreLocalTrackChoices(generation, choices) }
            }
        } }
        scope.launch { controller.surfaceReady.collect { _surfaceReady.value = it } }
        scope.launch { controller.diagnostics.collect { _diagnostics.value = it } }
        scope.launch {
            controller.events.collect { event ->
                _snapshot.value = event.snapshot()
                rememberPosition(_snapshot.value)
                if (!event.snapshot().active) _tracks.value = ReceiverTracks()
            }
        }
    }

    /**
     * The wire path. `RpcDispatcher` calls this, so an open the phone asked for is recorded as
     * such without the dispatcher knowing anything about origins.
     */
    override suspend fun open(request: OpenMediaRequest): Result<Unit> = open(request, Origin.Phone)

    suspend fun open(request: OpenMediaRequest, origin: Origin): Result<Unit> =
        open(request, origin, null)

    suspend fun openLiveChannel(request: OpenMediaRequest, channel: LiveChannel): Result<Unit> =
        open(request, Origin.Local, channel)

    suspend fun openIptvVod(request: OpenMediaRequest, item: IptvVod): Result<Unit> =
        open(request, Origin.Local, null, item)

    /** `open` prepares asynchronously; a channel is recent only after media actually starts. */
    suspend fun awaitLiveOutcome(timeoutMillis: Long = 45_000L): ReceiverPlaybackPhase? =
        withTimeoutOrNull(timeoutMillis) {
            controller.playbackPhase.first { phase ->
                phase is ReceiverPlaybackPhase.Playing || phase is ReceiverPlaybackPhase.Error ||
                    phase is ReceiverPlaybackPhase.Stopped || phase is ReceiverPlaybackPhase.Ended
            }
        }

    private suspend fun open(request: OpenMediaRequest, origin: Origin, channel: LiveChannel?, vod: IptvVod? = null): Result<Unit> {
        openGeneration++
        lastPositionMillis = request.startPositionMs ?: 0L
        _snapshot.value = ReceiverSnapshot(positionSeconds = lastPositionMillis / 1_000.0)
        _tracks.value = ReceiverTracks()
        _iptvVod.value = vod.takeIf { origin == Origin.Local }
        _origin.value = origin
        _liveChannel.value = channel.takeIf { origin == Origin.Local && request.isLive }
        _openRequests.tryEmit(origin)
        ReceiverDiagnostics.record("playback.open", "origin=$origin live=${request.isLive}")
        val result = controller.open(request)
        if (result.isFailure) {
            // Keep attempted IPTV ownership for Retry, Guide and Return-to-previous.
            // It clears on Stop or the next open, not on an engine prepare failure.
            ReceiverDiagnostics.record("playback.open.failed", "origin=$origin")
        }
        return result
    }

    override suspend fun stop(): Result<Unit> {
        openGeneration++
        val result = controller.stop()
        _iptvVod.value = null
        _origin.value = null
        _liveChannel.value = null
        return result
    }

    fun attachSurfaceHolder(holder: SurfaceHolder, width: Int, height: Int) =
        controller.attachSurfaceHolder(holder, width, height)

    suspend fun refreshSnapshot(): ReceiverSnapshot {
        val value = runCatching { controller.snapshot() }.getOrNull() ?: return _snapshot.value
        _snapshot.value = value
        rememberPosition(value)
        return value
    }

    fun resumePositionMillis(): Long = maxOf(
        lastPositionMillis,
        (_snapshot.value.positionSeconds * 1_000).toLong().coerceAtLeast(0L),
    )

    private fun rememberPosition(value: ReceiverSnapshot) {
        val position = (value.positionSeconds * 1_000).toLong()
        if (position > 0L) lastPositionMillis = position
    }

    suspend fun refreshTracks(): ReceiverTracks {
        val value = controller.tracks().getOrNull() ?: return _tracks.value
        _tracks.value = value
        return value
    }

    suspend fun togglePlayPause(): Result<Int> = controller.playPause()

    suspend fun seekTo(positionMillis: Long): Result<Unit> =
        controller.seek(SeekCommand.AbsoluteSeconds(positionMillis.coerceAtLeast(0L) / 1_000.0))

    suspend fun seekRelative(offsetSeconds: Double): Result<Unit> =
        controller.seek(SeekCommand.RelativeSeconds(offsetSeconds))

    suspend fun selectAudioTrack(index: Int): Result<Unit> {
        trackChoiceVersion++
        val result = controller.selectAudio(index)
        if (result.isSuccess && _origin.value == Origin.Local) {
            val track = refreshTracks().audio.firstOrNull { it.index == index }
            canonicalTrackLanguage(track?.language)?.let { language ->
                trackPrefs.edit().putString("audioLanguage", language)
                    .putString("audioSetting", presentationPrefs.getString(SettingsKeys.AUDIO_LANGUAGE, "Original")).apply()
            }
        }
        return result
    }

    suspend fun selectSubtitleTrack(selection: SubtitleSelection): Result<Unit> {
        trackChoiceVersion++
        val result = controller.selectSubtitle(selection)
        if (result.isSuccess && _origin.value == Origin.Local) {
            val editor = trackPrefs.edit().putBoolean("subtitleOff", selection is SubtitleSelection.Off)
            if (selection is SubtitleSelection.Index) {
                val track = refreshTracks().subtitles.firstOrNull { it.index == selection.value }
                canonicalTrackLanguage(track?.language)?.let { editor.putString("subtitleLanguage", it) }
            }
            editor.apply()
        }
        return result
    }

    override suspend fun stageSubtitleStyle(params: JsonObject) {
        controller.stageSubtitleStyle(params)
        trackPrefs.edit().putString("subtitleStyle", params.toString()).apply()
    }

    private suspend fun restoreLocalTrackChoices(generation: Long, choiceVersion: Long) {
        val audioLanguage = trackPrefs.getString("audioLanguage", null).takeIf {
            trackPrefs.getString("audioSetting", null) == presentationPrefs.getString(SettingsKeys.AUDIO_LANGUAGE, "Original")
        }
        val subtitleLanguage = trackPrefs.getString("subtitleLanguage", "en")
        val subtitleOff = trackPrefs.getBoolean("subtitleOff", false)
        var audioDone = audioLanguage == null
        var subtitleDone = false
        repeat(30) {
            if (generation != openGeneration || choiceVersion != trackChoiceVersion || _origin.value != Origin.Local) return
            val available = refreshTracks()
            if (generation != openGeneration || choiceVersion != trackChoiceVersion) return
            if (!audioDone && available.audio.isNotEmpty()) {
                available.audio.firstOrNull { canonicalTrackLanguage(it.language) == audioLanguage }?.let {
                    controller.selectAudio(it.index)
                }
                audioDone = true
            }
            if (!subtitleDone && (subtitleOff || available.subtitles.isNotEmpty())) {
                if (subtitleOff) controller.selectSubtitle(SubtitleSelection.Off)
                else {
                    val preferred = available.subtitles.firstOrNull { canonicalTrackLanguage(it.language) == subtitleLanguage }
                        ?: available.currentSubtitle ?: available.subtitles.first()
                    controller.selectSubtitle(SubtitleSelection.Index(preferred.index, enable = true))
                }
                subtitleDone = true
            }
            if (audioDone && subtitleDone) { refreshTracks(); return }
            delay(200)
        }
    }

    fun setUpscale(mode: UpscaleMode) = controller.setUpscaleMode(mode)

    /** Handoff targets on this box, ranked by how much state each dialect keeps (CONTEXT §7). */
    fun installedPlayers(): List<InstalledExternalPlayer> =
        ExternalPlayerIntentPolicy.installedPlayers(appContext)
            .sortedBy { ExternalPlayerIntentPolicy.dialect(it.packageName).ordinal }

    /**
     * Give the stream to another app on this television.
     *
     * This is not a stop and not a cast: the video is still on that television, so the phone is
     * told where it went BEFORE playback here ends, and a phone that understands the announcement
     * keeps its cast session instead of leaving the viewer with no controls.
     */
    suspend fun externalHandoff(player: InstalledExternalPlayer): Boolean {
        val media = controller.lastOpenMedia() ?: return false
        val position = runCatching { controller.snapshot().positionSeconds }.getOrDefault(0.0)
        val handoff = ExternalPlayerIntentPolicy.HandoffContext(
            positionMillis = (position * 1_000).toLong().coerceAtLeast(0L),
            headers = media.headers,
        )
        val launched = ExternalPlayerIntentPolicy.launch(
            context = appContext,
            url = media.url,
            title = media.title,
            targetPackage = player.packageName,
            handoff = handoff,
        )
        if (!launched) return false
        runCatching { controller.announceExternalHandoff(player.label, player.packageName) }
        controller.stop()
        _iptvVod.value = null
        _origin.value = null
        _liveChannel.value = null
        return true
    }

    private fun ReceiverEvent.snapshot(): ReceiverSnapshot = when (this) {
        is ReceiverEvent.Play -> snapshot
        is ReceiverEvent.Pause -> snapshot
        is ReceiverEvent.Seek -> snapshot
        is ReceiverEvent.SpeedChanged -> snapshot
        is ReceiverEvent.Stop -> snapshot
        is ReceiverEvent.ExternalHandoff -> snapshot
        is ReceiverEvent.Error -> snapshot
        is ReceiverEvent.VolumeChanged -> snapshot
        is ReceiverEvent.LinkRefreshRequested -> snapshot
    }
}

/** Normalize ISO-639 aliases across provider files; an unknown language is not a preference. */
internal fun canonicalTrackLanguage(value: String?): String? = when (val tag = value?.lowercase(java.util.Locale.ROOT)
    ?.substringBefore('-')?.substringBefore('_')?.trim()) {
    null, "", "und" -> null
    "eng" -> "en"
    "tel" -> "te"
    "hin" -> "hi"
    "tam" -> "ta"
    "mal" -> "ml"
    "kan" -> "kn"
    "jpn" -> "ja"
    "kor" -> "ko"
    "spa" -> "es"
    "fre", "fra" -> "fr"
    "ger", "deu" -> "de"
    else -> tag
}
