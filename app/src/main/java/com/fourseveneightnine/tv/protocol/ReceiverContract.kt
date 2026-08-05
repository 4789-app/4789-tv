package com.fourseveneightnine.tv.protocol

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class ReceiverSnapshot(
    val active: Boolean = false,
    val positionSeconds: Double = 0.0,
    val durationSeconds: Double = 0.0,
    val speed: Int = 0,
    val volume: Int = 100,
    val muted: Boolean = false,
    /**
     * How far the stream is downloaded, in seconds from the start. Local to the receiver's own UI
     * (the seek bar's buffered shade) — the Kodi wire protocol has no such property and never
     * carries it, so an engine that cannot report it simply leaves it at zero.
     */
    val bufferedSeconds: Double = 0.0,
)

data class ReceiverTrack(
    val index: Int,
    val language: String = "",
    val name: String = "",
    val codec: String? = null,
    val channels: Int? = null,
    val isOriginal: Boolean? = null,
    val selected: Boolean = false,
)

data class ReceiverTracks(
    val audio: List<ReceiverTrack> = emptyList(),
    val subtitles: List<ReceiverTrack> = emptyList(),
) {
    val currentAudio: ReceiverTrack? get() = audio.firstOrNull { it.selected }
    val currentSubtitle: ReceiverTrack? get() = subtitles.firstOrNull { it.selected }
    val subtitleEnabled: Boolean get() = currentSubtitle != null
}

sealed interface SubtitleSelection {
    data class Index(val value: Int, val enable: Boolean = true) : SubtitleSelection
    data object Off : SubtitleSelection
    data object On : SubtitleSelection
    data object Latest : SubtitleSelection
}

data class OpenMediaRequest(
    val url: String,
    val title: String? = null,
    val subtitle: String? = null,
    val isLive: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
    /**
     * What to offer when this title finishes. OPTIONAL, like every vendor field — a phone that
     * never sends it, and a stock Kodi client that cannot, both leave the end-of-film screen
     * offering Replay alone, which is what it did before this existed.
     */
    val nextUp: NextUpItem? = null,
    /**
     * Chapter boundaries, in seconds from the start, for the seek bar's tick marks. OPTIONAL.
     *
     * The phone supplies these because the receiver cannot: ExoPlayer does not surface a
     * container's chapter list, and the receiver has no metadata source of its own. An empty list
     * — which is what every phone sends today — draws exactly the rail it always drew.
     */
    val chapterSeconds: List<Double> = emptyList(),
)

/**
 * The episode after this one, as the phone described it.
 *
 * The receiver never works this out for itself: it has no catalogue, no season order and no idea
 * what the viewer is part-way through. The phone owns all three.
 */
data class NextUpItem(
    val url: String,
    val title: String? = null,
    val subtitle: String? = null,
    val posterUrl: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

sealed interface SeekCommand {
    data class Percentage(val value: Double) : SeekCommand
    data class RelativeSeconds(val value: Double) : SeekCommand
    data class AbsoluteSeconds(val value: Double) : SeekCommand
}

/** A handoff-capable player installed on this device, keyed by launchable package. */
data class InstalledExternalPlayer(
    val label: String,
    val packageName: String,
)

/**
 * Boundary through which the RPC layer reaches external player apps without depending on
 * Android framework types. The Activity supplies the implementation.
 */
/** What the receiver knows about the live playback, so a handoff resumes instead of restarting. */
data class ExternalHandoffContext(
    val positionMillis: Long = 0,
    val subtitleURL: String? = null,
    val subtitleName: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

interface ExternalPlayerPort {
    fun installedPlayers(): List<InstalledExternalPlayer>
    suspend fun launch(
        url: String,
        title: String?,
        targetPackage: String?,
        handoff: ExternalHandoffContext = ExternalHandoffContext(),
    ): Boolean
}

sealed interface ReceiverEvent {
    data class Play(val snapshot: ReceiverSnapshot) : ReceiverEvent
    data class Pause(val snapshot: ReceiverSnapshot) : ReceiverEvent
    data class Seek(
        val snapshot: ReceiverSnapshot,
        val offsetSeconds: Double,
    ) : ReceiverEvent

    data class SpeedChanged(val snapshot: ReceiverSnapshot) : ReceiverEvent
    data class Stop(val snapshot: ReceiverSnapshot) : ReceiverEvent

    /**
     * Playback failed on this TV (X4789.OnPlaybackError). Vendor event: stock Kodi never emits
     * it, and a phone that does not understand it still sees the Player.OnStop / poll transition
     * that follows. Carries the same explanation the on-TV overlay shows so the phone can name
     * the real failure — a DTS decoder that doesn't exist is not a lost connection.
     */
    data class Error(
        val snapshot: ReceiverSnapshot,
        val category: String,
        val mimeType: String? = null,
        val humanText: String,
        val engine: String,
        val fatal: Boolean,
        val willRetry: Boolean = false,
        val retryEngine: String? = null,
    ) : ReceiverEvent

    /**
     * The stream was handed to another app on this TV. NOT a stop: the cast is still meaningful —
     * the phone should keep its session and say where the video went, instead of tearing the cast
     * bar down and leaving the viewer with no controls and no way back.
     */
    data class ExternalHandoff(
        val snapshot: ReceiverSnapshot,
        val playerLabel: String,
        val playerPackage: String,
    ) : ReceiverEvent
    data class VolumeChanged(val snapshot: ReceiverSnapshot) : ReceiverEvent
    data class LinkRefreshRequested(
        val snapshot: ReceiverSnapshot,
        val reason: String = "user_retry",
    ) : ReceiverEvent
}

interface ReceiverController {
    val events: Flow<ReceiverEvent>

    /**
     * Tell the phone the stream moved to another app on this TV. Emitted BEFORE the stop that
     * follows, so a phone that understands it can hold its cast session open instead of tearing
     * down; one that does not simply ignores the frame and behaves as before.
     */
    suspend fun announceExternalHandoff(playerLabel: String, playerPackage: String) {}
    val surfaceReady: Flow<Boolean> get() = kotlinx.coroutines.flow.flowOf(true)
    val playbackPhase: Flow<com.fourseveneightnine.tv.player.ReceiverPlaybackPhase> get() = kotlinx.coroutines.flow.flowOf(com.fourseveneightnine.tv.player.ReceiverPlaybackPhase.Idle)
    val diagnostics: Flow<com.fourseveneightnine.tv.player.PlaybackDiagnostics> get() = kotlinx.coroutines.flow.flowOf(com.fourseveneightnine.tv.player.PlaybackDiagnostics())

    suspend fun snapshot(): ReceiverSnapshot
    suspend fun open(request: OpenMediaRequest): Result<Unit>
    suspend fun playPause(): Result<Int>
    suspend fun seek(command: SeekCommand): Result<Unit>
    suspend fun stop(): Result<Unit>
    suspend fun setSpeed(speed: Int): Result<Int>

    /**
     * Fractional playback rate for the ON-TV controls only. The Kodi wire protocol the phone speaks
     * has integer speeds, so [setSpeed] stays the contract; this is the local player UI's path to
     * 0.75x / 1.25x, which is what viewers actually reach for. Engines that cannot do it fall back
     * to the nearest integer rate.
     */
    suspend fun setSpeedMultiplier(speed: Float): Result<Float> =
        setSpeed(speed.toInt().coerceAtLeast(1)).map { it.toFloat() }
    suspend fun setVolume(volume: Int): Result<Int>
    suspend fun tracks(): Result<ReceiverTracks>
    suspend fun selectAudio(index: Int): Result<Unit>
    suspend fun selectSubtitle(selection: SubtitleSelection): Result<Unit>
    suspend fun addSubtitle(url: String): Result<Unit>
    suspend fun stageNowPlaying(title: String?, subtitle: String?, isLive: Boolean)
    suspend fun stageSubtitleStyle(params: JsonObject)
    suspend fun applySetting(name: String, value: JsonPrimitive): Result<Boolean>
    suspend fun executeAction(action: String): Result<Unit>

    /**
     * Receiver-level picture-enhancement preference (SGSR on the Exo engine). Persisted; the
     * default implementation keeps whatever the engine itself remembers, so the mpv engine can
     * persist the choice without pretending to apply it.
     */
    fun upscaleMode(): com.fourseveneightnine.tv.player.upscale.UpscaleMode =
        com.fourseveneightnine.tv.player.upscale.UpscaleMode.OFF
    fun setUpscaleMode(mode: com.fourseveneightnine.tv.player.upscale.UpscaleMode) {}

    fun attachSurface(surface: android.view.Surface, width: Int = 0, height: Int = 0) {}
    fun detachSurface() {}
    fun updateSurfaceSize(width: Int, height: Int) {}
    fun handleMemoryPressure(level: Int) {}
    fun consumePendingSoftwareOpen(): OpenMediaRequest? = null
    fun lastOpenMedia(): OpenMediaRequest? = null
    suspend fun openWithSoftware(request: OpenMediaRequest): Result<Unit> = open(request)
    suspend fun retryLastOpen(): Result<Unit> = Result.success(Unit)
    fun refreshAudioRoute() {}
    fun close() {}
}
