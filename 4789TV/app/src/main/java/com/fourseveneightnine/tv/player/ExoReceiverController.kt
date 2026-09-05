package com.fourseveneightnine.tv.player

import android.app.ActivityManager
import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Surface
import android.view.SurfaceHolder
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Effect
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.fourseveneightnine.tv.protocol.OpenMediaRequest
import com.fourseveneightnine.tv.protocol.ReceiverController
import com.fourseveneightnine.tv.protocol.ReceiverAudioProfile
import com.fourseveneightnine.tv.protocol.ReceiverEvent
import com.fourseveneightnine.tv.protocol.ReceiverPreparationStage
import com.fourseveneightnine.tv.protocol.ReceiverSnapshot
import com.fourseveneightnine.tv.protocol.ReceiverTrack
import com.fourseveneightnine.tv.protocol.ReceiverTracks
import com.fourseveneightnine.tv.protocol.SeekCommand
import com.fourseveneightnine.tv.protocol.SubtitleSelection
import com.fourseveneightnine.tv.player.upscale.SgsrVideoEffect
import com.fourseveneightnine.tv.player.upscale.UpscaleMode
import com.fourseveneightnine.tv.player.upscale.UpscalePolicy
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.OkHttpClient
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Phone-pushed subtitle appearance. Mirrors the `X4789.SubtitleStyle` payload the mpv path already
 * honours (`family`, `colorHex`, `size`, `lift`) so both receivers read the same sidechannel.
 */
data class ReceiverSubtitleStyle(
    val family: String = "",
    val colorHex: String = DEFAULT_COLOR,
    val size: Double = DEFAULT_SIZE,
    val lift: Double = MIN_LIFT,
) {
    /** SubtitleView sizes text as a fraction of view height; mpv sizes it in its own units. */
    val fractionalTextSize: Float get() = (size / SIZE_TO_VIEW_FRACTION_DIVISOR).toFloat()

    /** Extra bottom inset so a lifted subtitle clears burned-in credits, matching mpv's margin-y. */
    val bottomPaddingFraction: Float
        get() = (BASE_BOTTOM_FRACTION + lift / LIFT_TO_FRACTION_DIVISOR).toFloat()

    fun foregroundColor(): Int =
        try {
            android.graphics.Color.parseColor(colorHex)
        } catch (_: IllegalArgumentException) {
            android.graphics.Color.WHITE
        }

    companion object {
        const val DEFAULT_COLOR = "#FFFFFF"
        const val DEFAULT_SIZE = 20.0
        private const val MIN_SIZE = 12.0
        private const val MAX_SIZE = 46.0
        const val MIN_LIFT = 0.0
        private const val MAX_LIFT = 280.0
        private const val MAX_FAMILY_LENGTH = 64
        // 20.0 (the shared default) → 0.0533, ExoPlayer's own DEFAULT_TEXT_SIZE_FRACTION.
        private const val SIZE_TO_VIEW_FRACTION_DIVISOR = 375.0
        private const val BASE_BOTTOM_FRACTION = 0.08
        private const val LIFT_TO_FRACTION_DIVISOR = 1_000.0
        private val COLOR = Regex("#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?")

        fun from(params: JsonObject): ReceiverSubtitleStyle {
            val family = (params["family"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                ?.takeIf { it.length <= MAX_FAMILY_LENGTH }
                .orEmpty()
            val color = (params["colorHex"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                ?.takeIf(COLOR::matches)
                ?: DEFAULT_COLOR
            val size = (params["size"] as? JsonPrimitive)?.doubleOrNull
                ?.takeIf(Double::isFinite)
                ?.coerceIn(MIN_SIZE, MAX_SIZE)
                ?: DEFAULT_SIZE
            val lift = (params["lift"] as? JsonPrimitive)?.doubleOrNull
                ?.takeIf(Double::isFinite)
                ?.coerceIn(MIN_LIFT, MAX_LIFT)
                ?: MIN_LIFT
            return ReceiverSubtitleStyle(family = family, colorHex = color, size = size, lift = lift)
        }
    }
}

@OptIn(UnstableApi::class)
class ExoReceiverController(
    context: Context,
) : ReceiverController, Closeable {

    private val applicationContext = context.applicationContext
    private val audioManager: AudioManager =
        applicationContext.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val closed = AtomicBoolean(false)

    private var exoPlayer: ExoPlayer? = null
    /** Increments for every constructed player; retired players are invalidated before release. */
    private var nextPlayerInstanceGeneration = 0L
    private var activePlayerInstanceGeneration = 0L
    private var playerView: PlayerView? = null
    private var attachedSurface: Surface? = null
    private var attachedSurfaceHolder: SurfaceHolder? = null

    private var currentTitle: String? = null
    private var currentSubtitle: String? = null
    private var currentCastId: String? = null
    private var stagedCastId: String? = null
    private var isLiveStream: Boolean = false
    private var lastOpenRequest: OpenMediaRequest? = null
    private var pendingRestoreAudioTrackIndex: Int? = null
    private var pendingRestoreSubtitleTrackIndex: Int? = null
    private var pendingRecoverySubtitleSelection: RecoverySubtitleSelection? = null

    private val okHttpClient = OkHttpClient.Builder()
        .connectionPool(okhttp3.ConnectionPool(5, 5, TimeUnit.MINUTES))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
        .build()
    private val externalSubtitleLoader = ExternalSubtitleLoader(okHttpClient)

    private val _events = MutableSharedFlow<ReceiverEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<ReceiverEvent> = _events.asSharedFlow()

    private val _surfaceReady = MutableStateFlow(false)
    override val surfaceReady: StateFlow<Boolean> = _surfaceReady.asStateFlow()

    private val _playbackPhase = MutableStateFlow<ReceiverPlaybackPhase>(ReceiverPlaybackPhase.Idle)
    override val playbackPhase: StateFlow<ReceiverPlaybackPhase> = _playbackPhase.asStateFlow()

    private val _diagnostics = MutableStateFlow(PlaybackDiagnostics())
    override val diagnostics: StateFlow<PlaybackDiagnostics> = _diagnostics.asStateFlow()
    private val playbackGenerationPolicy = ExoPlaybackGenerationPolicy()
    private var lastBandwidthLogAtMs = 0L

    /** Rendered by the Activity's SubtitleView — ExoPlayer decodes cues but draws nothing itself. */
    private val _cues = MutableStateFlow<List<Cue>>(emptyList())
    val cues: StateFlow<List<Cue>> = _cues.asStateFlow()

    /** Drives the Activity's AspectRatioFrameLayout; 0 = unknown, leave the frame unconstrained. */
    private val _videoAspectRatio = MutableStateFlow(0f)
    val videoAspectRatio: StateFlow<Float> = _videoAspectRatio.asStateFlow()

    /**
     * Set when the box can play NO audio track in the current file — playback continues silently
     * rather than failing. Null whenever audio is playing normally.
     */
    private val _unplayableAudioCodec = MutableStateFlow<String?>(null)
    val unplayableAudioCodec: StateFlow<String?> = _unplayableAudioCodec.asStateFlow()

    /** Phone-pushed subtitle appearance (`X4789.SubtitleStyle`), applied to the SubtitleView. */
    private val _subtitleStyle = MutableStateFlow(ReceiverSubtitleStyle())
    val subtitleStyle: StateFlow<ReceiverSubtitleStyle> = _subtitleStyle.asStateFlow()

    // Auto-start applies to the first READY of an open only. Without this, every rebuffer
    // re-asserted playWhenReady=true and silently un-paused the user.
    private var autoPlayPending = false

    // External subtitles are parsed once and rendered by the Activity-owned SubtitleView. Keeping
    // them out of MediaItem means a subtitle transfer never rebuilds the active video graph.
    private val externalSubtitleTracks = mutableListOf<ExternalSubtitleTrack>()
    private var selectedExternalSubtitleIndex: Int? = null
    private var externalSubtitleEnabled = false
    private var externalSubtitleTickerJob: Job? = null
    /** All sidecar loads are owned here so an open/stop/close can cancel them as one lifecycle. */
    private val externalSubtitleLoadJobs = mutableSetOf<Job>()

    // Set when the television refuses app volume writes; then WE own the level via player gain.
    //
    // Unlike STREAM_MUSIC — which is the television's own level and survives anything — this one
    // lives in our process. Without persistence, closing the app and resuming played the film at
    // FULL volume on exactly the sets that need the fallback (Fire TV Edition panels with a fixed
    // output route), because a fresh player starts at gain 1.0 and nothing remembered otherwise.
    private val audioPreferences = applicationContext
        .getSharedPreferences(AUDIO_PREFERENCES_NAME, Context.MODE_PRIVATE)
    private var playerGainFallback: Float? =
        audioPreferences.getFloat(GAIN_FALLBACK_KEY, -1f).takeIf { it in 0f..1f }

    // Seek coalescing state. Main thread only.
    private var pendingSeekTargetMs: Long? = null
    private var activeSeekTargetMs: Long? = null
    private var lastSeekIssuedAtMillis: Long? = null
    private val pendingSeekRunnable = Runnable { issuePendingSeek() }
    private val seekTimeoutRunnable = Runnable { handleSeekTimeout() }

    // Speaker-profile state pushed by the phone. ExoPlayer has no live passthrough switch, so these
    // are resolved into the AudioCapabilities the sink is built with (see audioCapabilities()).
    // Automatic is the default and matches the previous behaviour: the platform decides.
    private val automaticAudioRouting = AtomicBoolean(true)
    private val passthroughRequested = AtomicBoolean(false)
    private val passthroughCodecRequests = ConcurrentHashMap<String, Boolean>()

    /** The route the currently-built audio sink was created for; null until the first build. */
    @Volatile
    private var activeAudioRoute: ExoAudioRoute? = null
    /** The sink owns immutable route capabilities; rebuild it at the next Open, never mid-title. */
    private var audioRouteRebuildPending = false

    /** Name of the current video decoder, retained for recovery diagnostics. */
    private var lastVideoDecoderName: String? = null

    /** Cast the audio selection below belongs to. A different cast's track numbers mean nothing. */
    private var lastAudioSelectionCastId: String? = null

    /** Pure progress and once-per-public-open recovery budget for video-decoder stalls. */
    private val decoderStallRecoveryPolicy = DecoderStallRecoveryPolicy()

    /** Increments only for public opens, so a stale watchdog recovery cannot replace a newer title. */
    private var publicOpenGeneration = 0L

    /** The current player terminally stalled and must never be reused by a later public open. */
    private var terminalDecoderStallPlayer = false

    private val audioClockWatchdogPolicy = AudioClockWatchdogPolicy()
    private var watchdogTickerJob: Job? = null
    private var lastWatchdogPosMs: Long = 0L
    private var lastWatchdogWallMs: Long = 0L

    // SGSR upscaling, pushed by the phone as x4789.upscale, cycled by the TV pill, and remembered
    // across restarts. Opt-in: stick-class GPUs pay ~14 ms/frame for a 2x pass, fine for film,
    // fatal for 60 fps.
    private val upscaleMode = AtomicReference(
        UpscalePolicy.loadMode(
            applicationContext.getSharedPreferences(UpscalePolicy.PREFERENCES_NAME, Context.MODE_PRIVATE),
        ),
    )

    /**
     * Whether the CURRENT player instance carries the effects pipeline. Main thread only.
     * Attached lazily — only once the decoded size proves the title would actually be enlarged —
     * because the pipeline reroutes every frame through an OpenGL video graph whose colour
     * conversion differs from the zero-copy MediaCodec-to-surface path (that difference is what
     * made native-4K titles look wrong on the Fire TV with the upscaler on).
     */
    private var videoEffectsAttached = false

    /** Last known decoded size and HDR-ness of the current title. Main thread only. */
    private var lastVideoWidth = 0
    private var lastVideoHeight = 0
    private var lastVideoHdr = false
    private val seekAudioLifecycle = ExoSeekAudioLifecycle()

    /** How many times the audio sink has run dry this session, fed by the analytics listener. */
    private val audioUnderrunCount = AtomicInteger(0)

    /**
     * Container frame rate of the current title, from `Format.frameRate`. Main thread only.
     *
     * This is the STATED rate, never a measured or estimated one. Auto frame rate matching reads
     * it, and an estimate that wobbles by a hundredth of a hertz would re-request a display mode
     * mid-film — an HDMI re-link the viewer sees as a black flash.
     */
    private var lastVideoFrameRate = 0.0
    private var lastVideoCodec = ""

    /**
     * Rendered-frame counting, used only when the container did not state a rate.
     *
     * Measured on both verified boxes: a progressive MP4 reaches `onVideoInputFormatChanged` with
     * `Format.frameRate == NO_VALUE`, so the stated-rate path alone leaves auto frame rate matching
     * exactly as dead as it was before. Frames are counted over at least [FRAME_RATE_WINDOW_MS]
     * and the result is snapped to a standard rate, so the wobble never reaches a mode request.
     */
    @Volatile
    private var frameWindowStartUs = 0L

    @Volatile
    private var frameWindowCount = 0

    /**
     * Presentation timestamp of the previous rendered frame, or [NO_FRAME_US] before the first.
     *
     * The window is only meaningful across CONTIGUOUS frames. Media3 renders a frame from position
     * 0 before an initial seek lands, and every seek leaves a jump behind it — averaging across
     * either turns 24fps into nonsense.
     */
    @Volatile
    private var frameWindowLastFrameUs = NO_FRAME_US

    /**
     * Where this open asked the engine to start, or null when it started from the beginning.
     *
     * Kept so the phone's follow-up `Player.Seek` to the SAME place — which every phone still sends
     * for receivers too old to accept a start position — can be recognised as redundant and
     * skipped, instead of flushing the buffer the open just filled.
     */
    private var openStartPositionMs: Long? = null

    // The television's own remote moves STREAM_MUSIC too. Mirror that back so the phone's slider
    // does not drift out of sync with the set.
    private val volumeObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            scope.launch { _events.emit(ReceiverEvent.VolumeChanged(snapshotOnMain())) }
        }
    }

    init {
        // Do not build Media3 while the viewer is only browsing the TV library. On Fire OS the
        // renderer/codec graph can take seconds on a cold boot and blocks the UI looper even
        // though no title is playing. `open()` and the audio-route recovery path both call the
        // idempotent initializer immediately before they need a player.
        runCatching {
            applicationContext.contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI,
                true,
                volumeObserver,
            )
        }
    }

    private fun initExoPlayer() {
        if (exoPlayer != null) return
        // Generous runway against a shaky Wi-Fi hop, but byte-capped, and deliberately quick to
        // show the first frame — see ExoStartupBufferPolicy for why those are separate knobs.
        val largeMemoryClassMb = applicationContext
            .getSystemService(ActivityManager::class.java)
            .largeMemoryClass
        val targetBufferBytes = ExoStartupBufferPolicy.targetBufferBytes(largeMemoryClassMb)
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ ExoStartupBufferPolicy.MIN_BUFFER_MS,
                /* maxBufferMs = */ ExoStartupBufferPolicy.MAX_BUFFER_MS,
                /* bufferForPlaybackMs = */ ExoStartupBufferPolicy.BUFFER_FOR_PLAYBACK_MS,
                /* bufferForPlaybackAfterRebufferMs = */
                ExoStartupBufferPolicy.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            .setTargetBufferBytes(targetBufferBytes)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

        // AFTDCT31 (Fire OS 7) vendor decoders deadlock in MediaCodec asynchronous callback mode:
        // the playback thread blocks forever inside codec init and the player never leaves
        // STATE_BUFFERING (same platform bug that froze mpv's hwdec path, README 0.1.15).
        // Media3 defaults to the async adapter on API 23+. Keep the synchronous workaround only
        // for the affected Amazon family; applying it globally was needless startup latency on
        // current Google/Android TV hardware.
        //
        // Synchronous queueing is also why the affected Fire TV needs a wider AudioTrack buffer than
        // Media3's default: every codec input/output hop now runs inline on the playback thread,
        // so a 4K video dequeue that takes longer than usual delays the audio renderer's next
        // feed. Media3's DefaultAudioTrackBufferSizeProvider caps PCM at 750ms and passthrough at
        // 250ms, which is not enough runway on this device — the mpv path needed `audio-buffer=2.0`
        // on the exact same hardware for the exact same reason (see MpvReceiverStartupPolicy).
        // Underruns present as short clicks/pops at any loudness, including quiet dialogue.
        // The sink's capabilities are fixed at construction, so the speaker profile has to be
        // resolved here. Null keeps the platform's own capabilities (the automatic profile).
        val route = activeAudioRoute ?: resolveAudioRoute().also { activeAudioRoute = it }
        val renderersFactory = WideAudioBufferRenderersFactory(
            applicationContext,
            audioCapabilities(route),
        )
            .setEnableDecoderFallback(true)
            // ON, not PREFER: MediaCodec still wins whenever the chip can handle the format, and
            // this factory registers FFmpeg only for audio fallback (DTS/DTS-HD/TrueHD).
            .setExtensionRendererMode(
                androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
            )
        val forceSynchronousQueueing = ExoMediaCodecQueueingPolicy.forceSynchronous(
            Build.MANUFACTURER,
            Build.MODEL,
        )
        if (forceSynchronousQueueing) {
            renderersFactory.forceDisableMediaCodecAsynchronousQueueing()
        }

        // FALLBACK, NOT FAILURE. ExoPlayer's default selector "exceeds renderer capabilities when
        // necessary": with a TrueHD Atmos 7.1 track and no TrueHD decoder or passthrough on the
        // panel, it selects that track anyway, MediaCodecAudioRenderer throws
        // `format_supported=NO_UNSUPPORTED_TYPE`, and the whole title dies — including video that
        // would have played perfectly (Hisense SmartTV 4K FFM, 2026-08-04).
        //
        // Turning that off makes the selector honest, and it degrades in the order a viewer wants:
        //   1. another audio track the box CAN handle (most Atmos releases ship an AC3/E-AC3
        //      compatibility track) — the picture and sound both survive;
        //   2. if there is none, no audio track at all — the film plays silently instead of
        //      refusing, and onTracksChanged reports it so the phone can say why.
        // An undecodable VIDEO track still raises the overlay + external-player handoff below;
        // silent video is a reasonable degradation, a black screen is not.
        val trackSelector = DefaultTrackSelector(applicationContext).apply {
            parameters = buildUponParameters()
                .setExceedRendererCapabilitiesIfNecessary(false)
                .setAllowVideoMixedMimeTypeAdaptiveness(true)
                .build()
        }

        val player = ExoPlayer.Builder(applicationContext, renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .build()
        val installedPlayerInstanceGeneration = ++nextPlayerInstanceGeneration

        ReceiverDiagnostics.record(
            "exo.build",
            "queueing=${if (forceSynchronousQueueing) "synchronous" else "asynchronous"} " +
                "seekMode=exact targetBufferBytes=$targetBufferBytes largeHeapMb=$largeMemoryClassMb " +
                "device=${Build.MANUFACTURER}/${Build.MODEL} sdk=${Build.VERSION.SDK_INT}",
        )

        // Audio glitches are otherwise invisible: ExoPlayer keeps playing through an underrun, so a
        // viewer hears a click and the receiver reports a perfectly healthy "Playing". Record the
        // sink's own accounting instead of guessing — `bufferSize`/`elapsedSinceLastFeedMs` on an
        // underrun says whether the cushion is too small or the playback thread stalled, and the
        // initialized config proves the widened buffer above actually took on this route.
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioUnderrun(
                eventTime: AnalyticsListener.EventTime,
                bufferSize: Int,
                bufferSizeMs: Long,
                elapsedSinceLastFeedMs: Long,
            ) {
                val count = audioUnderrunCount.incrementAndGet()
                val readableBufferMs = if (bufferSizeMs == C.TIME_UNSET) "unset" else "$bufferSizeMs"
                ReceiverDiagnostics.record(
                    "exo.audio.underrun",
                    "count=$count bufferSize=$bufferSize bufferMs=$readableBufferMs sinceLastFeedMs=$elapsedSinceLastFeedMs",
                )
            }

            override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
                ReceiverDiagnostics.record(
                    "exo.audio.sinkError",
                    "${audioSinkError::class.java.simpleName}: ${audioSinkError.message}",
                )
            }

            // Which decoder actually ran. This is the only honest way to tell hardware from
            // software after adding the ffmpeg extension: an `ff`-prefixed name is the CPU path.
            // Video MUST stay on a MediaCodec name here — software HEVC on this stick is ~4fps.
            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                ReceiverDiagnostics.record("exo.decoder.audio", decoderName)
            }

            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                val callbackGeneration = playbackGeneration(eventTime)
                val accepted = callbackGeneration != null &&
                    playbackGenerationPolicy.videoDecoderInitialized(callbackGeneration)
                if (accepted) lastVideoDecoderName = decoderName
                ReceiverDiagnostics.record(
                    "exo.decoder.video",
                    "$decoderName generation=${callbackGeneration ?: "unknown"} accepted=$accepted",
                )
            }

            override fun onRenderedFirstFrame(
                eventTime: AnalyticsListener.EventTime,
                output: Any,
                renderTimeMs: Long,
            ) {
                val callbackGeneration = playbackGeneration(eventTime)
                val disposition = callbackGeneration?.let(playbackGenerationPolicy::renderedFrame)
                    ?: ExoRenderedFrameDisposition.STALE
                if (disposition == ExoRenderedFrameDisposition.STALE) {
                    ReceiverDiagnostics.record(
                        "exo.firstFrame.ignored",
                        "callbackGeneration=${callbackGeneration ?: "unknown"} " +
                            "currentGeneration=${playbackGenerationPolicy.currentGeneration}",
                    )
                    return
                }
                // Media3 reports another first frame after a seek/flush. That current-generation
                // callback is the synchronization boundary for removing the temporary seek mute,
                // even when the title's original first frame was already published. Previously the
                // duplicate guard returned above this block and left resumed titles silent until a
                // viewer touched the volume control.
                val seekTarget = activeSeekTargetMs
                val reportedPosition = eventTime.eventPlaybackPositionMs.takeUnless { it == C.TIME_UNSET }
                val currentPosition = exoPlayer?.currentPosition
                val belongsToLatestSeek = SeekCallbackAcceptancePolicy.acceptsRenderedFrame(
                    activeTargetMs = seekTarget,
                    reportedPositionMs = reportedPosition,
                    currentPositionMs = currentPosition,
                    toleranceMs = SEEK_FRAME_POSITION_TOLERANCE_MS,
                )
                if (belongsToLatestSeek && (reportedPosition ?: currentPosition) != null) {
                    decoderStallRecoveryPolicy.frameRendered(
                        positionMs = reportedPosition ?: currentPosition ?: 0L,
                        nowMs = SystemClock.elapsedRealtime(),
                    )
                    ReceiverDiagnostics.record(
                        "exo.seek.frame.accepted",
                        "targetMs=${seekTarget ?: "none"} frameMs=${reportedPosition ?: currentPosition}",
                    )
                }
                if (seekAudioLifecycle.renderedFrame(disposition, belongsToLatestSeek)) {
                    mainHandler.removeCallbacks(seekTimeoutRunnable)
                    activeSeekTargetMs = null
                    if (pendingSeekTargetMs != null) {
                        // Keep gain at zero while the newest target replaces the frame that just
                        // settled. Serialising the two seeks makes generation-less Media3 frame
                        // callbacks unambiguous without playing a burst of intermediate audio.
                        issuePendingSeek()
                    } else {
                        restorePlayerGainAfterSeek("frame")
                    }
                } else if (seekAudioLifecycle.isMuted) {
                    ReceiverDiagnostics.record(
                        "exo.seek.frameWaiting",
                        "generation=$callbackGeneration targetMs=$seekTarget frameMs=$reportedPosition currentMs=$currentPosition",
                    )
                }
                if (disposition == ExoRenderedFrameDisposition.CURRENT_REPEAT) {
                    ReceiverDiagnostics.record(
                        "exo.firstFrame.repeat",
                        "generation=$callbackGeneration renderTimeMs=$renderTimeMs",
                    )
                    return
                }
                val playing = exoPlayer?.playWhenReady == true
                _playbackPhase.value = if (playing) ReceiverPlaybackPhase.Playing else ReceiverPlaybackPhase.Paused
                ReceiverDiagnostics.record(
                    "exo.firstFrameRendered",
                    "cast=${currentCastId?.take(8) ?: "none"} generation=$callbackGeneration " +
                        "renderTimeMs=$renderTimeMs",
                )
                scope.launch(Dispatchers.Main) {
                    val snap = snapshotOnMain()
                    _events.emit(if (playing) ReceiverEvent.Play(snap) else ReceiverEvent.Pause(snap))
                }
            }

            override fun onBandwidthEstimate(
                eventTime: AnalyticsListener.EventTime,
                totalLoadTimeMs: Int,
                totalBytesLoaded: Long,
                bitrateEstimate: Long,
            ) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastBandwidthLogAtMs < BANDWIDTH_LOG_INTERVAL_MILLIS) return
                lastBandwidthLogAtMs = now
                val p = exoPlayer
                ReceiverDiagnostics.record(
                    "exo.bandwidth",
                    "cast=${currentCastId?.take(8) ?: "none"} estimateBps=$bitrateEstimate " +
                        "sampleBytes=$totalBytesLoaded sampleMs=$totalLoadTimeMs " +
                        "bufferAheadMs=${p?.totalBufferedDuration ?: 0}",
                )
            }

            override fun onAudioTrackInitialized(
                eventTime: AnalyticsListener.EventTime,
                audioTrackConfig: AudioSink.AudioTrackConfig,
            ) {
                ReceiverDiagnostics.record(
                    "exo.audio.trackInit",
                    "encoding=${audioTrackConfig.encoding} rate=${audioTrackConfig.sampleRate} " +
                        "channelMask=${audioTrackConfig.channelConfig} bufferSize=${audioTrackConfig.bufferSize} " +
                        "offload=${audioTrackConfig.offload} tunneling=${audioTrackConfig.tunneling} " +
                        // The position tells a start apart from a re-build. A cast that prepares at
                        // 0 and seeks afterwards builds one sink for position 0, throws it away, and
                        // builds a second at the real position; both lines otherwise look identical.
                        "posMs=${exoPlayer?.currentPosition ?: -1}",
                )
            }

            // The SGSR shader assumes an SDR 0..1 signal; an HDR title must never enter the
            // pipeline at all, not just skip the shader pass — the GL colour conversion alone
            // changes the picture on this box.
            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
            ) {
                val info = format.colorInfo
                val isDolbyVision = format.sampleMimeType?.contains("dolby-vision", ignoreCase = true) == true ||
                    format.containerMimeType?.contains("dolby-vision", ignoreCase = true) == true
                lastVideoHdr = isDolbyVision || (info != null && info.colorSpace == C.COLOR_SPACE_BT2020 &&
                    (info.colorTransfer == C.COLOR_TRANSFER_ST2084 || info.colorTransfer == C.COLOR_TRANSFER_HLG))
                lastVideoFrameRate = format.frameRate
                    .takeIf { it != Format.NO_VALUE.toFloat() && it > 0f }
                    ?.toDouble()
                    ?: 0.0
                lastVideoCodec = format.sampleMimeType.orEmpty()
                updateSeekParameters(player, format.width, format.height)
                reconcileVideoEffects()
                publishDiagnostics()
            }

        })

        // The measured frame-rate fallback, for the containers that state no rate.
        //
        // Measured on the AFTDCT31: BOTH a progressive MP4 and a Matroska file reached the player
        // with `Format.frameRate == NO_VALUE`, so the stated rate alone left auto frame rate
        // matching exactly as dead as it was before. `onVideoFrameProcessingOffset` looked like the
        // answer and is not — Media3 flushes it on stop and position reset, never periodically, so
        // it produced nothing across a minute of playback. This callback fires per rendered frame.
        //
        // The deltas are CONTAINER presentation timestamps, not wall clock, so the result is exact
        // rather than an estimate — but it is still snapped, because variable-frame-rate content
        // exists and a mode request must never chase it.
        player.setVideoFrameMetadataListener { presentationTimeUs, _, _, _ ->
            if (!PlayerCallbackOwnershipPolicy.accepts(
                    installedPlayerGeneration = installedPlayerInstanceGeneration,
                    activePlayerGeneration = activePlayerInstanceGeneration,
                    isCurrentPlayerInstance = exoPlayer === player,
                )
            ) {
                return@setVideoFrameMetadataListener
            }
            if (lastVideoFrameRate > 0.0) return@setVideoFrameMetadataListener
            // A window must never span a discontinuity. Media3 renders a frame from position 0
            // before an initial seek lands, and every seek leaves a jump behind it; the gap that
            // introduces is not a frame interval. Live on the onn 4K Pro (2026-08-28 13:13:10) one
            // such window measured 0.030fps, was discarded as unmatched, and auto frame rate
            // matching did not fire until 6.3 seconds into the film — long after the picture was
            // up, so the viewer got the HDMI re-link as a black flash mid-scene.
            val previousUs = frameWindowLastFrameUs
            val contiguous = previousUs != NO_FRAME_US &&
                presentationTimeUs > previousUs &&
                presentationTimeUs - previousUs <= FRAME_RATE_MAX_FRAME_GAP_US
            frameWindowLastFrameUs = presentationTimeUs
            if (!contiguous) {
                frameWindowStartUs = presentationTimeUs
                frameWindowCount = 1
                return@setVideoFrameMetadataListener
            }
            frameWindowCount++
            val spanUs = presentationTimeUs - frameWindowStartUs
            if (frameWindowCount < FRAME_RATE_WINDOW_FRAMES || spanUs <= 0L) {
                return@setVideoFrameMetadataListener
            }

            val measured = (frameWindowCount - 1) * 1_000_000.0 / spanUs
            frameWindowCount = 0
            frameWindowStartUs = presentationTimeUs
            val snapped = SurfaceFrameRatePolicy.snapToStandardRate(measured)
            if (snapped == null) {
                ReceiverDiagnostics.record(
                    "exo.frameRate.unmatched",
                    "measured=${"%.3f".format(measured)}",
                )
                return@setVideoFrameMetadataListener
            }
            lastVideoFrameRate = snapped
            ReceiverDiagnostics.record(
                "exo.frameRate.measured",
                "measured=${"%.3f".format(measured)} snapped=${"%.3f".format(snapped)}",
            )
            // This callback runs on the playback thread; publishing reads player.duration.
            mainHandler.post { publishDiagnostics() }
        }

        player.addListener(object : Player.Listener {
            private fun ownsCurrentPlayer(callback: String): Boolean {
                val accepted = PlayerCallbackOwnershipPolicy.accepts(
                    installedPlayerGeneration = installedPlayerInstanceGeneration,
                    activePlayerGeneration = activePlayerInstanceGeneration,
                    isCurrentPlayerInstance = exoPlayer === player,
                )
                if (!accepted) {
                    ReceiverDiagnostics.record(
                        "exo.playerCallback.ignored",
                        "callback=$callback installed=$installedPlayerInstanceGeneration " +
                            "active=$activePlayerInstanceGeneration current=${exoPlayer === player}",
                    )
                }
                return accepted
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!ownsCurrentPlayer("isPlaying")) return
                if (isPlaying) {
                    startWatchdogLoop()
                }
                if (!playbackGenerationPolicy.hasRenderedFirstFrame) {
                    ReceiverDiagnostics.record(
                        "exo.state.preFirstFrame",
                        "callback=isPlaying value=$isPlaying generation=${playbackGenerationPolicy.currentGeneration}",
                    )
                    return
                }
                val generation = playbackGenerationPolicy.currentGeneration
                scope.launch(Dispatchers.Main) {
                    if (playbackGenerationPolicy.currentGeneration != generation) return@launch
                    // A stop, an error, or the end of the film is the final word on what is on
                    // screen; this callback runs a coroutine later and must not overwrite it.
                    if (!ExoPhaseOverwritePolicy.allowsTransportPhase(_playbackPhase.value)) {
                        return@launch
                    }
                    val snap = snapshotOnMain()
                    _playbackPhase.value = if (isPlaying) ReceiverPlaybackPhase.Playing else ReceiverPlaybackPhase.Paused
                    val evt = if (isPlaying) ReceiverEvent.Play(snap) else ReceiverEvent.Pause(snap)
                    _events.emit(evt)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (!ownsCurrentPlayer("playbackState")) return
                val p = exoPlayer
                ReceiverDiagnostics.record(
                    "exo.stateChanged",
                    "state=$playbackState playWhenReady=${p?.playWhenReady} " +
                        "bufferedEndMs=${p?.bufferedPosition} bufferAheadMs=${p?.totalBufferedDuration} " +
                        "posMs=${p?.currentPosition} cast=${currentCastId?.take(8) ?: "none"}",
                )
                when (playbackState) {
                    Player.STATE_ENDED -> {
                        stopWatchdogLoop()
                        if (!playbackGenerationPolicy.hasRenderedFirstFrame) {
                            when (decoderStallRecoveryPolicy.videoEndedWithoutFrame()) {
                                DecoderStallAction.RECOVER -> {
                                    val retryPositionMs = lastOpenRequest?.startPositionMs ?: 0L
                                    ReceiverDiagnostics.record(
                                        "exo.decoder.noFrameRecovery",
                                        "positionMs=${p?.currentPosition ?: 0L} retryMs=$retryPositionMs",
                                    )
                                    executeDecoderStallRecovery(
                                        expectedPublicOpenGeneration = publicOpenGeneration,
                                        resumePositionMs = retryPositionMs,
                                    )
                                }
                                DecoderStallAction.SURFACE_FAILURE -> surfaceDecoderStallFailure(
                                    humanText = "The TV video decoder produced no picture for this release. " +
                                        "Try another source or restart the TV.",
                                    mimeType = lastVideoCodec.takeIf { it.isNotBlank() },
                                )
                                DecoderStallAction.NONE -> Unit
                            }
                            return
                        }
                        scope.launch(Dispatchers.Default) {
                            // Ended, not Stopped: the film ran out on its own, which is a decision
                            // point for the viewer rather than a teardown. The wire event stays
                            // `Stop`, so a phone that knows nothing of this still sees what it
                            // always saw.
                            _playbackPhase.value = ReceiverPlaybackPhase.Ended
                            _events.emit(ReceiverEvent.Stop(snapshotOnMain()))
                        }
                    }
                    Player.STATE_BUFFERING -> {
                        startWatchdogLoop()
                        _playbackPhase.value = if (playbackGenerationPolicy.hasRenderedFirstFrame) {
                            ReceiverPlaybackPhase.Buffering
                        } else {
                            ReceiverPlaybackPhase.Opening(
                                currentTitle,
                                ReceiverPreparationStage.PREPARING,
                                currentCastId,
                            )
                        }
                    }
                    Player.STATE_READY -> {
                        startWatchdogLoop()
                        // A seek still WAITING — coalesced, or submitted before the player was
                        // ready — is issued through the normal path, so it is muted, recorded, and
                        // de-duplicated against the deferred runnable. Sending it raw left
                        // `pendingSeekTargetMs` set, so the runnable then seeked to the same place a
                        // second time, and every one of those flushes the audio sink and builds
                        // another AudioTrack.
                        val waiting = pendingSeekTargetMs
                        if (waiting != null && waiting > 0L) {
                            mainHandler.removeCallbacks(pendingSeekRunnable)
                            issuePendingSeek()
                        } else {
                            // A seek already SENT is not re-sent. Media3 queues a seek from any
                            // state, so re-issuing it bought nothing but another flush. It stays as
                            // a net for the one case that would be visible — the player sitting
                            // somewhere we did not ask for — and that case is now recorded.
                            val issued = activeSeekTargetMs
                            if (issued != null && issued > 0L &&
                                kotlin.math.abs(player.currentPosition - issued) >
                                SEEK_FRAME_POSITION_TOLERANCE_MS
                            ) {
                                ReceiverDiagnostics.record(
                                    "exo.seek.reissue",
                                    "targetMs=$issued posMs=${player.currentPosition}",
                                )
                                player.seekTo(issued)
                            }
                        }
                        if (autoPlayPending) {
                            autoPlayPending = false
                            player.playWhenReady = true
                        }
                        // Duration is only known once the source is prepared, and the format
                        // callback usually lands before that — so republish here or a VOD title
                        // would look like a live stream to the non-seamless-switch policy.
                        publishDiagnostics()
                        val durMs = player.duration
                        if (durMs in 1L..60_000L) {
                            ReceiverDiagnostics.record("exo.ready.debridErrorCard", "durationMs=$durMs")
                            val msg = "Stream unavailable on Debrid provider — pick another link."
                            _playbackPhase.value = ReceiverPlaybackPhase.Error(msg)
                            emitPlaybackError(
                                category = PlaybackErrorTaxonomy.SOURCE,
                                mimeType = null,
                                humanText = msg,
                                fatal = false,
                            )
                        } else {
                            _playbackPhase.value = if (!playbackGenerationPolicy.hasRenderedFirstFrame) {
                                ReceiverPlaybackPhase.Opening(
                                    currentTitle,
                                    ReceiverPreparationStage.PREPARING,
                                    currentCastId,
                                )
                            } else if (player.playWhenReady) {
                                ReceiverPlaybackPhase.Playing
                            } else {
                                ReceiverPlaybackPhase.Paused
                            }
                        }
                        if (playbackGenerationPolicy.hasRenderedFirstFrame) {
                            scope.launch(Dispatchers.Main) {
                                val snap = snapshotOnMain()
                                _events.emit(
                                    if (player.playWhenReady) ReceiverEvent.Play(snap) else ReceiverEvent.Pause(snap),
                                )
                            }
                        }
                    }
                    Player.STATE_IDLE -> {
                        // A codec failure drives the player to IDLE right after onPlayerError.
                        // Overwriting Error here dismissed the error overlay (and its external
                        // player buttons) straight back to the home screen.
                        val phase = _playbackPhase.value
                        if (phase !is ReceiverPlaybackPhase.Error && phase !is ReceiverPlaybackPhase.Opening) {
                            // IDLE out of live playback with no preceding error and no stop() is
                            // an unexplained death — say so instead of letting the phone see an
                            // anonymous player-gone. stop() sets Stopped and open() sets Opening
                            // before their IDLE arrives, so neither trips this.
                            val unexplained = phase is ReceiverPlaybackPhase.Playing ||
                                phase is ReceiverPlaybackPhase.Paused ||
                                phase is ReceiverPlaybackPhase.Buffering
                            if (unexplained) {
                                ReceiverDiagnostics.record("exo.idle.unexplained", "phase=$phase")
                                emitPlaybackError(
                                    category = PlaybackErrorTaxonomy.UNKNOWN,
                                    mimeType = null,
                                    humanText = "Playback stopped unexpectedly on the TV.",
                                    fatal = true,
                                )
                            }
                            _playbackPhase.value = ReceiverPlaybackPhase.Idle
                        }
                    }
                }
            }

            override fun onCues(cueGroup: CueGroup) {
                if (!ownsCurrentPlayer("cues")) return
                // An external sidecar is rendered on the Activity overlay. Ignore any delayed
                // renderer callback from the disabled native text track so it cannot overwrite the
                // sidecar while the video keeps playing.
                if (externalSubtitleEnabled) return
                _cues.value = cueGroup.cues
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (!ownsCurrentPlayer("videoSize")) return
                // Honour non-square pixels (anamorphic SD/DVD sources) — ignoring the PAR is what
                // stretched a 4:3-stored 16:9 title across the full panel.
                val height = videoSize.height
                _videoAspectRatio.value = if (height == 0 || videoSize.width == 0) {
                    0f
                } else {
                    videoSize.width * videoSize.pixelWidthHeightRatio / height
                }
                // The decoded size is the one the shader would enlarge — decide now whether the
                // effects pipeline is worth existing for this title.
                lastVideoWidth = videoSize.width
                lastVideoHeight = height
                updateSeekParameters(player, videoSize.width, height)
                reconcileVideoEffects()
                publishDiagnostics()
                ReceiverDiagnostics.record(
                    "exo.videoSize",
                    "${videoSize.width}x$height par=${videoSize.pixelWidthHeightRatio}",
                )
            }

            // playWhenReady changes while BUFFERING never reach onIsPlayingChanged, so a pause
            // issued mid-rebuffer was invisible to the phone (its scrubber kept running).
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!ownsCurrentPlayer("playWhenReady")) return
                decoderStallRecoveryPolicy.playWhenReadyChanged(
                    positionMs = exoPlayer?.currentPosition ?: 0L,
                    nowMs = SystemClock.elapsedRealtime(),
                )
                if (!playbackGenerationPolicy.hasRenderedFirstFrame) return
                val generation = playbackGenerationPolicy.currentGeneration
                scope.launch(Dispatchers.Main) {
                    if (playbackGenerationPolicy.currentGeneration != generation) return@launch
                    // Same rule as `onIsPlayingChanged`: a player that has already stopped, errored
                    // or ended must not be reported to the phone as merely paused.
                    if (!ExoPhaseOverwritePolicy.allowsTransportPhase(_playbackPhase.value)) {
                        return@launch
                    }
                    val snap = snapshotOnMain()
                    _events.emit(if (playWhenReady) ReceiverEvent.Play(snap) else ReceiverEvent.Pause(snap))
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (!ownsCurrentPlayer("tracks")) return
                val textCount = embeddedTextTrackCount(tracks) + externalSubtitleTracks.size
                pendingRecoverySubtitleSelection?.let { selection ->
                    when (selection) {
                        RecoverySubtitleSelection.NoTextTracks -> pendingRecoverySubtitleSelection = null
                        RecoverySubtitleSelection.ExplicitlyOff -> {
                            if (RecoverySubtitleSelectionPolicy.isReady(selection, textCount)) {
                                pendingRecoverySubtitleSelection = null
                                scope.launch { selectSubtitle(SubtitleSelection.Off) }
                            }
                        }
                        is RecoverySubtitleSelection.Index -> {
                            if (RecoverySubtitleSelectionPolicy.isReady(selection, textCount)) {
                                pendingRecoverySubtitleSelection = null
                                scope.launch { selectSubtitle(SubtitleSelection.Index(selection.value, enable = true)) }
                            }
                        }
                    }
                }
                pendingRestoreSubtitleTrackIndex?.let { subIdx ->
                    if (textCount > subIdx) {
                        pendingRestoreSubtitleTrackIndex = null
                        scope.launch { selectSubtitle(SubtitleSelection.Index(subIdx, enable = true)) }
                    }
                }
                pendingRestoreAudioTrackIndex?.let { audioIdx ->
                    val audioCount = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.sumOf { it.length }
                    if (audioCount > audioIdx) {
                        pendingRestoreAudioTrackIndex = null
                        scope.launch { selectAudio(audioIdx) }
                    }
                }

                // Audio fell back to nothing: the box has no decoder and no passthrough for any
                // audio track in this file. Playback CONTINUES silently by design (see the track
                // selector), but say so — silent video with no explanation reads as a bug.
                val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                if (audioGroups.isNotEmpty() && audioGroups.none { it.isSelected }) {
                    val codec = audioGroups.firstOrNull()
                        ?.takeIf { it.length > 0 }
                        ?.getTrackFormat(0)
                        ?.sampleMimeType
                    ReceiverDiagnostics.record("exo.audioFallback.none", codec ?: "unknown")
                    if (_unplayableAudioCodec.value != codec) {
                        emitPlaybackError(
                            category = PlaybackErrorTaxonomy.CODEC_AUDIO,
                            mimeType = codec,
                            humanText = "This TV can't decode this release's audio" +
                                (codec?.let { " (${it.removePrefix("audio/").uppercase()})" } ?: "") +
                                " — playing without sound. Pick another audio track or release.",
                            fatal = false,
                        )
                    }
                    _unplayableAudioCodec.value = codec
                } else if (audioGroups.any { it.isSelected }) {
                    _unplayableAudioCodec.value = null
                }

                // ExoPlayer's default selector silently DROPS an undecodable video track and plays
                // audio-only — a black screen with no error. Treat "media has video but none is
                // selectable" as a codec failure so the overlay + external-player handoff appear.
                val videoGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                if (videoGroups.isEmpty() || videoGroups.any { it.isSelected }) return
                val mime = videoGroups.firstOrNull()
                    ?.takeIf { it.length > 0 }
                    ?.getTrackFormat(0)
                    ?.sampleMimeType
                ReceiverDiagnostics.record("exo.videoTrackUnsupported", mime ?: "unknown")
                // Phase BEFORE stop(): the synchronous STATE_IDLE must observe Error, not race
                // the unexplained-IDLE detector (same ordering law as stop()).
                val text = describeUnsupportedVideo(mime)
                _playbackPhase.value = ReceiverPlaybackPhase.Error(text)
                exoPlayer?.stop()
                emitPlaybackError(
                    category = PlaybackErrorTaxonomy.CODEC_VIDEO,
                    mimeType = mime,
                    humanText = text,
                    fatal = true,
                )
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!ownsCurrentPlayer("error")) return
                ReceiverDiagnostics.record("exoplayer.error", error.message ?: error::class.java.simpleName)
                val mime = decoderFailureMime(error)
                if (isVideoDecoderInitializationFailure(error, mime) &&
                    !playbackGenerationPolicy.hasRenderedFirstFrame
                ) {
                    when (decoderStallRecoveryPolicy.videoDecoderInitializationFailed()) {
                        DecoderStallAction.RECOVER -> {
                            ReceiverDiagnostics.record(
                                "exo.decoder.initRecovery",
                                "positionMs=${exoPlayer?.currentPosition ?: 0L} mime=${mime ?: "unknown"}",
                            )
                            executeDecoderStallRecovery(
                                expectedPublicOpenGeneration = publicOpenGeneration,
                                resumePositionMs = exoPlayer?.currentPosition ?: 0L,
                            )
                        }
                        DecoderStallAction.SURFACE_FAILURE -> {
                            surfaceDecoderStallFailure(
                                humanText = describeVideoDecoderInitializationFailure(mime),
                                mimeType = mime,
                            )
                        }
                        DecoderStallAction.NONE -> Unit
                    }
                    return
                }
                cancelPendingSeek()
                val humanText = describePlayerError(error)
                // Synchronous on the player's (main) thread: the STATE_IDLE callback follows
                // immediately and must observe the Error phase, not race a launched coroutine.
                _playbackPhase.value = ReceiverPlaybackPhase.Error(humanText)
                emitPlaybackError(
                    category = PlaybackErrorTaxonomy.categorize(error.errorCode, mime),
                    mimeType = mime,
                    humanText = humanText,
                    fatal = true,
                )
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (!ownsCurrentPlayer("positionDiscontinuity")) return
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    val target = activeSeekTargetMs
                    if (!SeekCallbackAcceptancePolicy.acceptsDiscontinuity(
                            activeTargetMs = target,
                            newPositionMs = newPosition.positionMs,
                            toleranceMs = SEEK_FRAME_POSITION_TOLERANCE_MS,
                        )
                    ) {
                        ReceiverDiagnostics.record(
                            "exo.seek.discontinuity.ignored",
                            "targetMs=${target ?: "none"} newMs=${newPosition.positionMs}",
                        )
                        return
                    }
                    decoderStallRecoveryPolicy.seekDiscontinuity(
                        positionMs = newPosition.positionMs,
                        nowMs = SystemClock.elapsedRealtime(),
                    )
                    ReceiverDiagnostics.record(
                        "exo.seek.discontinuity",
                        "oldMs=${oldPosition.positionMs} newMs=${newPosition.positionMs}",
                    )
                    refreshExternalSubtitleCues()
                    scope.launch(Dispatchers.Default) {
                        val snap = snapshotOnMain()
                        val offset = (newPosition.positionMs - oldPosition.positionMs) / 1000.0
                        _events.emit(ReceiverEvent.Seek(snap, offset))
                    }
                }
            }

            override fun onVolumeChanged(volume: Float) {
                if (!ownsCurrentPlayer("volume")) return
                scope.launch(Dispatchers.Default) {
                    _events.emit(ReceiverEvent.VolumeChanged(snapshotOnMain()))
                }
            }
        })

        // A restored fallback level has to reach the NEW player, or the first frame after a
        // reopen is full-volume regardless of what was remembered.
        playerGainFallback?.let { player.volume = it }
        videoEffectsAttached = false
        activePlayerInstanceGeneration = installedPlayerInstanceGeneration
        exoPlayer = player
        playerView?.player = player
        attachedSurfaceHolder?.let { player.setVideoSurfaceHolder(it) }
            ?: attachedSurface?.let { player.setVideoSurface(it) }
    }

    /** Mirror every on-TV failure to the phone. The overlay keeps rendering locally; this frame is
     *  what lets the phone say "no DTS decoder" instead of "connection lost". */
    private fun emitPlaybackError(
        category: String,
        mimeType: String?,
        humanText: String,
        fatal: Boolean,
        snapshotOverride: ReceiverSnapshot? = null,
    ) {
        ReceiverDiagnostics.record(
            "notify.playbackError",
            "category=$category mime=${mimeType ?: "-"} fatal=$fatal",
        )
        scope.launch(Dispatchers.Default) {
            _events.emit(
                ReceiverEvent.Error(
                    snapshot = snapshotOverride ?: snapshotOnMain(),
                    category = category,
                    mimeType = mimeType,
                    humanText = humanText,
                    engine = "exo",
                    fatal = fatal,
                ),
            )
        }
    }

    private fun decoderInitializationException(error: PlaybackException):
        androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.DecoderInitializationException? =
        generateSequence<Throwable>(error) { it.cause }
            .filterIsInstance<androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.DecoderInitializationException>()
            .firstOrNull()

    private fun decoderFailureMime(error: PlaybackException): String? = decoderInitializationException(error)?.mimeType

    private fun isVideoDecoderInitializationFailure(error: PlaybackException, mime: String?): Boolean =
        decoderInitializationException(error) != null && mime?.startsWith("video/") == true

    private fun describeVideoDecoderInitializationFailure(mime: String?): String {
        val label = mime?.removePrefix("video/")?.uppercase() ?: "this video format"
        return "This TV could not initialize its video decoder for $label. Try another source or restart the TV."
    }

    /** Names the failing track so the overlay explains itself and the handoff buttons make sense. */
    private fun describePlayerError(error: PlaybackException): String {
        val mime = decoderFailureMime(error)
        if (isVideoDecoderInitializationFailure(error, mime)) {
            return describeVideoDecoderInitializationFailure(mime)
        }
        val codecLabel = when (mime) {
            androidx.media3.common.MimeTypes.AUDIO_TRUEHD -> "TrueHD audio"
            androidx.media3.common.MimeTypes.AUDIO_DTS -> "DTS audio"
            androidx.media3.common.MimeTypes.AUDIO_DTS_HD -> "DTS-HD audio"
            androidx.media3.common.MimeTypes.AUDIO_DTS_X -> "DTS:X audio"
            androidx.media3.common.MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision video"
            null -> null
            else -> if (mime.startsWith("audio/")) {
                "this release's audio (${mime.removePrefix("audio/").uppercase()})"
            } else {
                "this release's video (${mime.removePrefix("video/").uppercase()})"
            }
        }
        return if (codecLabel != null) {
            if (mime?.startsWith("video/") == true) {
                "This TV can't decode $codecLabel. Open the stream in another player below, " +
                    "or pick an HEVC/H.264 (non-Dolby-Vision) release."
            } else {
                "This TV can't decode $codecLabel. Open the stream in another player below, " +
                    "or pick a release with AC3/EAC3/AAC audio."
            }
        } else {
            error.message ?: "Playback failed. Try again, open another player below, or pick a different release."
        }
    }

    private fun describeUnsupportedVideo(mime: String?): String {
        val label = when (mime) {
            androidx.media3.common.MimeTypes.VIDEO_DOLBY_VISION ->
                "Dolby Vision video (likely profile 5, which has no fallback layer)"
            null -> "this release's video codec"
            else -> "this release's video (${mime.removePrefix("video/").uppercase()})"
        }
        return "This TV can't decode $label. Open the stream in another player below, " +
            "or pick an HEVC/H.264 (non-Dolby-Vision) release."
    }

    fun attachSurfaceHolder(holder: SurfaceHolder) {
        mainHandler.post {
            attachedSurfaceHolder = holder
            exoPlayer?.setVideoSurfaceHolder(holder)
            _surfaceReady.value = holder.surface.isValid
        }
    }

    override fun attachSurface(surface: Surface, width: Int, height: Int) {
        mainHandler.post {
            attachedSurface = surface
            exoPlayer?.setVideoSurface(surface)
            _surfaceReady.value = surface.isValid
        }
    }

    override fun detachSurface() {
        mainHandler.post {
            attachedSurface = null
            attachedSurfaceHolder = null
            exoPlayer?.setVideoSurface(null)
            _surfaceReady.value = false
        }
    }

    override fun updateSurfaceSize(width: Int, height: Int) {}

    fun attachPlayerView(view: PlayerView?) {
        mainHandler.post {
            playerView = view
            view?.player = exoPlayer
        }
    }

    private suspend fun snapshotOnMain(): ReceiverSnapshot = withContext(Dispatchers.Main) {
        snapshotNowOnMain()
    }

    /** Main-thread snapshot used when an event must retain the terminal player's exact state. */
    private fun snapshotNowOnMain(): ReceiverSnapshot {
        val p = exoPlayer
        return if (p == null) {
            ReceiverSnapshot()
        } else {
            val dur = if (p.duration != C.TIME_UNSET) p.duration / 1000.0 else 0.0
            // Report the coalesced target, not the stale playhead: otherwise the phone's scrubber
            // (and the on-TV bar) snap back to where the scrub started for up to a window's worth
            // of polls, which reads as "my seek was ignored".
            val pos = (pendingSeekTargetMs ?: activeSeekTargetMs ?: p.currentPosition) / 1000.0
            // Kodi's wire contract: speed 0 IS "paused". `playbackParameters.speed` stays 1.0 while
            // paused, so reporting it made the phone believe playback continued — its scrubber ran
            // on and then snapped back on the next poll.
            val paused = !p.playWhenReady ||
                p.playbackState == Player.STATE_IDLE ||
                p.playbackState == Player.STATE_ENDED
            val speed = if (paused) 0 else p.playbackParameters.speed.toInt().coerceAtLeast(1)
            val vol = effectiveVolumePercent()
            ReceiverSnapshot(
                active = p.playbackState != Player.STATE_IDLE && p.playbackState != Player.STATE_ENDED,
                positionSeconds = pos,
                durationSeconds = dur,
                speed = speed,
                volume = vol,
                muted = vol == 0,
                bufferedSeconds = p.bufferedPosition / 1000.0,
            )
        }
    }

    override suspend fun snapshot(): ReceiverSnapshot = snapshotOnMain()

    override suspend fun open(request: OpenMediaRequest): Result<Unit> = withContext(Dispatchers.Main) {
        try {
            // A sidecar belongs to exactly one public open. Cancel any in-flight fetch before the
            // generation changes so a late response cannot land on the next title.
            resetExternalSubtitleState()
            // This is deliberately distinct from an internal decoder recovery. A user-driven
            // open is the only operation allowed to replenish the one-recovery budget.
            publicOpenGeneration += 1
            // Carry the outgoing title's audio track into the incoming one when this is the SAME
            // cast being re-opened. Live on Black Widow the selection wandered 7.1 -> stereo -> 7.1
            // across generations of one cast, and nobody asked it to.
            val outgoingAudioIndex = currentAudioTrackIndexOnMain()
            AudioTrackContinuityPolicy.restoreIndex(
                previousIndex = outgoingAudioIndex,
                previousCastId = lastAudioSelectionCastId,
                incomingCastId = request.castId,
                // Same cast means the same file, so the outgoing count IS the incoming count. The
                // restore is checked against the REAL list again in `onTracksChanged` before it is
                // applied, so a title that somehow arrives with fewer tracks still cannot be asked
                // for one that is not there.
                incomingAudioTrackCount = audioTrackCountOnMain(),
            )?.let { restore ->
                pendingRestoreAudioTrackIndex = restore
                ReceiverDiagnostics.record("exo.audio.trackCarried", "index=$restore")
            }

            // Device evidence shows supported H.264/HEVC formats can fail after rapid links until
            // the app restarts. Every public source replacement therefore gets the same clean
            // player/surface boundary before this new stream is prepared.
            if (PublicStreamReplacementPolicy.requiresFreshPlayer(exoPlayer != null)) {
                releaseCurrentPlayer()
                ReceiverDiagnostics.record(
                    "exo.open.freshPlayer",
                    "publicOpen=$publicOpenGeneration terminal=$terminalDecoderStallPlayer",
                )
            }
            if (terminalDecoderStallPlayer) {
                terminalDecoderStallPlayer = false
                ReceiverDiagnostics.record("exo.decoder.terminalPlayerReleased", "publicOpen=$publicOpenGeneration")
            }
            if (audioRouteRebuildPending) {
                audioRouteRebuildPending = false
                ReceiverDiagnostics.record("exo.audioroute.rebuild", "cast=${request.castId?.take(8) ?: "none"}")
            }
            lastVideoDecoderName = null
            initExoPlayer()
            val player = exoPlayer ?: return@withContext Result.failure(IllegalStateException("ExoPlayer not initialized"))

            currentTitle = request.title
            currentSubtitle = request.subtitle
            currentCastId = request.castId
            stagedCastId = request.castId ?: stagedCastId
            isLiveStream = request.isLive
            lastOpenRequest = request
            val openGeneration = playbackGenerationPolicy.beginOpen()
            autoPlayPending = true
            audioClockWatchdogPolicy.resetSession()
            lastWatchdogPosMs = 0L
            lastWatchdogWallMs = 0L
            lastAudioSelectionCastId = request.castId
            decoderStallRecoveryPolicy.beginPublicOpen(
                positionMs = request.startPositionMs ?: 0L,
                nowMs = SystemClock.elapsedRealtime(),
            )
            // A seek queued against the outgoing title must never land on the incoming one.
            cancelPendingSeek()
            // Never let the previous title's cues or letterbox survive.
            pendingRecoverySubtitleSelection = null
            _cues.value = emptyList()
            _videoAspectRatio.value = 0f
            _playbackPhase.value = ReceiverPlaybackPhase.Opening(
                request.title,
                ReceiverPreparationStage.PREPARING,
                request.castId,
            )
            openStartPositionMs = request.startPositionMs?.takeIf { it > 0L }
            ReceiverDiagnostics.record(
                "exo.open.begin",
                "cast=${request.castId?.take(8) ?: "none"} generation=$openGeneration " +
                    "startPositionMs=${openStartPositionMs ?: 0L}",
            )

            val mediaSource = buildMediaSource(request)
            // Carry the chosen level across titles when we own it (fixed-output televisions).
            playerGainFallback?.let { player.volume = it }

            val holder = attachedSurfaceHolder
            if (holder != null && holder.surface.isValid) {
                player.setVideoSurfaceHolder(holder)
            } else if (attachedSurface != null && attachedSurface!!.isValid) {
                player.setVideoSurface(attachedSurface)
            }
            ReceiverDiagnostics.record("exo.open.surface", "holderValid=${holder?.surface?.isValid} surfaceValid=${attachedSurface?.isValid}")

            // The effects pipeline attaches lazily from onVideoSizeChanged / onVideoInputFormatChanged,
            // never here: at open the decoded size is unknown, and a pipeline attached to a title it
            // cannot enlarge only changes the picture's colours for nothing.
            lastVideoWidth = 0
            lastVideoHeight = 0
            lastVideoHdr = false
            lastVideoFrameRate = 0.0
            lastVideoCodec = ""
            frameWindowStartUs = 0L
            frameWindowCount = 0
            frameWindowLastFrameUs = NO_FRAME_US
            _diagnostics.value = PlaybackDiagnostics()

            // Prepare AT the resume position when the phone told us one. Preparing at 0 and
            // seeking afterwards fills a buffer nobody watches, throws it away, re-fetches, and
            // re-initialises the decoder: 1.19s of a measured 2.07s cold start on the onn 4K Pro
            // (Alpha, 4K HEVC, 2026-08-28). It also renders a frame from position 0 on the way
            // past, which is a flash of the wrong scene.
            val startPositionMs = openStartPositionMs
            if (startPositionMs != null) {
                player.setMediaSource(mediaSource, startPositionMs)
            } else {
                player.setMediaSource(mediaSource)
            }
            player.playWhenReady = true
            player.prepare()

            Result.success(Unit)
        } catch (e: Throwable) {
            ReceiverDiagnostics.record("exoplayer.open.error", "${e::class.java.simpleName}: ${e.message}")
            _playbackPhase.value = ReceiverPlaybackPhase.Error(e.message ?: "Open error")
            Result.failure(e)
        }
    }

    /**
     * Decides whether the effects pipeline should exist for the CURRENT title and calls
     * setVideoEffects only when that answer changes. Attached lazily from onVideoSizeChanged /
     * onVideoInputFormatChanged, so a native-4K or native-1080p title never touches the GL colour
     * path at all; detached the moment the answer flips (HDR, native size, or the setting going
     * off). A failure here degrades to plain playback — an enhancement must never be the reason a
     * title does not play.
     */
    /**
     * Publish what this engine knows about the running picture.
     *
     * `PlaybackDiagnostics` existed since the field was declared but NOTHING ever wrote it on this
     * path, so every consumer read the zero defaults. Auto frame rate matching was the visible
     * casualty: `applyContentFrameRate` reads `framesPerSecond`, a constant 0.0 fails
     * `SurfaceFrameRatePolicy.validRate`, and the whole feature returned early on every title.
     *
     * Main thread only — `player.duration` requires it, and every caller is already a player
     * callback on the app thread.
     */
    private fun publishDiagnostics() {
        val player = exoPlayer ?: return
        // C.TIME_UNSET until the source is prepared; a live stream never gets one.
        val durationSeconds = player.duration
            .takeIf { it != C.TIME_UNSET && it > 0 }
            ?.let { it / 1_000.0 }
            ?: 0.0
        val next = PlaybackDiagnostics(
            active = true,
            videoCodec = lastVideoCodec,
            width = lastVideoWidth,
            height = lastVideoHeight,
            framesPerSecond = lastVideoFrameRate,
            durationSeconds = durationSeconds,
        )
        if (_diagnostics.value == next) return
        _diagnostics.value = next
        // Recorded because a zero frame rate here is indistinguishable on screen from a working
        // one, and a zero is what silently disabled auto frame rate matching for the whole life
        // of the feature. If AFR does nothing on a box, this line says whether the rate was even
        // known. Only on change, so it cannot spam the ring buffer.
        ReceiverDiagnostics.record(
            "exo.diagnostics",
            "fps=${next.framesPerSecond} size=${next.width}x${next.height} " +
                "duration=${next.durationSeconds} codec=${next.videoCodec.ifBlank { "?" }}",
        )
    }

    private fun reconcileVideoEffects() {
        val player = exoPlayer ?: return
        // No size yet (opening, or between titles): keep the current answer until one exists.
        if (lastVideoWidth <= 0 || lastVideoHeight <= 0) return
        val mode = upscaleMode.get()
        val metrics = applicationContext.resources.displayMetrics
        val apply = UpscalePolicy.shouldApply(
            inputWidth = lastVideoWidth,
            inputHeight = lastVideoHeight,
            displayWidth = metrics.widthPixels,
            displayHeight = metrics.heightPixels,
            hdr = lastVideoHdr,
            mode = mode,
        )
        if (apply == videoEffectsAttached) return
        val effects =
            if (apply) listOf<Effect>(SgsrVideoEffect(metrics.widthPixels, metrics.heightPixels, mode))
            else emptyList()
        try {
            player.setVideoEffects(effects)
            videoEffectsAttached = apply
            ReceiverDiagnostics.record(
                "exo.upscale",
                if (apply) {
                    "attach sgsr input=${lastVideoWidth}x$lastVideoHeight display=${metrics.widthPixels}x${metrics.heightPixels}"
                } else {
                    "detach input=${lastVideoWidth}x$lastVideoHeight hdr=$lastVideoHdr"
                },
            )
        } catch (e: Throwable) {
            ReceiverDiagnostics.record("exo.upscale.error", "${e::class.java.simpleName}: ${e.message}")
        }
    }

    override fun upscaleMode(): UpscaleMode = upscaleMode.get()

    override fun setUpscaleMode(mode: UpscaleMode) {
        upscaleMode.set(mode)
        val prefs = applicationContext
            .getSharedPreferences(UpscalePolicy.PREFERENCES_NAME, Context.MODE_PRIVATE)
        UpscalePolicy.saveMode(prefs, mode)
        ReceiverDiagnostics.record(
            "exo.upscale.setting",
            if (mode == UpscaleMode.OFF) "mode=off" else "mode=${mode.name} appliesOn=nextOpen",
        )
        // Turning OFF tears the pipeline down RIGHT NOW, so the current picture's colours return
        // without reopening the title. Enabling waits for the next size/format event (next open)
        // to attach — rebuilding the video graph under live video is a visible interruption.
        if (mode == UpscaleMode.OFF && Looper.myLooper() == Looper.getMainLooper()) {
            reconcileVideoEffects()
        } else if (mode == UpscaleMode.OFF) {
            mainHandler.post { reconcileVideoEffects() }
        }
    }

    override suspend fun playPause(): Result<Int> = withContext(Dispatchers.Main) {
        try {
            val p = exoPlayer ?: return@withContext Result.failure(IllegalStateException("Player null"))
            // Toggle intent, not `isPlaying`: while buffering `isPlaying` is false even though the
            // user is "playing", so reading it back flipped the reported state the wrong way.
            val willPlay = !p.playWhenReady
            autoPlayPending = false
            p.playWhenReady = willPlay
            Result.success(if (willPlay) 1 else 0)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun seek(command: SeekCommand): Result<Unit> = withContext(Dispatchers.Main) {
        try {
            val p = exoPlayer ?: return@withContext Result.failure(IllegalStateException("Player null"))
            val durMs = p.duration
            val targetMs = when (command) {
                is SeekCommand.AbsoluteSeconds -> (command.value * 1000).toLong()
                // Chain off the pending target, not the playhead: while a burst is coalescing the
                // player has not moved yet, so ten ±10s presses off `currentPosition` would all
                // resolve to the same ±10s.
                is SeekCommand.RelativeSeconds ->
                    (pendingSeekTargetMs ?: p.currentPosition) + (command.value * 1000).toLong()
                is SeekCommand.Percentage -> {
                    // Without a known duration a percentage is meaningless; failing loudly beats
                    // the old silent seek-to-current-position no-op the phone read as success.
                    if (durMs == C.TIME_UNSET) {
                        return@withContext Result.failure(IllegalStateException("Duration unknown; percentage seek unavailable"))
                    }
                    ((command.value / 100.0) * durMs).toLong()
                }
            }
            val clamped = targetMs.coerceIn(0, if (durMs != C.TIME_UNSET) durMs else Long.MAX_VALUE)
            submitSeek(clamped)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * One seek per burst. See [SeekCoalescingPolicy] — a scrub used to re-initialise the audio
     * track once per intermediate position (six times in a second, live on the box).
     *
     * Main thread only: `pendingSeekTargetMs` is read by `seek`, the runnable, and `snapshotOnMain`,
     * all of which run there.
     */
    private fun submitSeek(targetMs: Long) {
        // Every phone still sends `Player.Seek` after `Player.Open`, because a receiver too old to
        // accept a start position needs it. When this open already started there, honouring it
        // would flush the buffer the open just filled and re-initialise the decoder for nothing.
        val openedAtMs = openStartPositionMs
        if (openedAtMs != null &&
            !playbackGenerationPolicy.hasRenderedFirstFrame &&
            kotlin.math.abs(targetMs - openedAtMs) <= REDUNDANT_STARTUP_SEEK_TOLERANCE_MS
        ) {
            openStartPositionMs = null
            ReceiverDiagnostics.record(
                "exo.seek.redundant",
                "targetMs=$targetMs openedAtMs=$openedAtMs",
            )
            return
        }
        decoderStallRecoveryPolicy.seekSubmitted()
        pendingSeekTargetMs = targetMs
        val decision = SeekCoalescingPolicy.decide(
            lastSeekIssuedAtMillis,
            android.os.SystemClock.uptimeMillis(),
        )
        val delayMs = (decision as? SeekCoalescingPolicy.Decision.Defer)?.delayMillis
        ReceiverDiagnostics.record(
            "exo.seek.submit",
            "targetMs=$targetMs decision=${decision::class.simpleName}${delayMs?.let { " delayMs=$it" } ?: ""}",
        )
        when (decision) {
            SeekCoalescingPolicy.Decision.IssueNow -> {
                mainHandler.removeCallbacks(pendingSeekRunnable)
                issuePendingSeek()
            }

            is SeekCoalescingPolicy.Decision.Defer -> {
                mainHandler.removeCallbacks(pendingSeekRunnable)
                mainHandler.postDelayed(pendingSeekRunnable, decision.delayMillis)
            }
        }
    }

    private fun issuePendingSeek() {
        if (!SeekCoalescingPolicy.canIssue(activeSeekTargetMs)) return
        val target = pendingSeekTargetMs ?: return
        activeSeekTargetMs = target
        pendingSeekTargetMs = null
        lastSeekIssuedAtMillis = android.os.SystemClock.uptimeMillis()
        ReceiverDiagnostics.record("exo.seek.issue", "targetMs=$target")
        audioClockWatchdogPolicy.resetConsecutiveHits()
        lastWatchdogPosMs = target
        exoPlayer?.let { p ->
            decoderStallRecoveryPolicy.seekIssued(
                positionMs = p.currentPosition,
                nowMs = SystemClock.elapsedRealtime(),
            )
            seekAudioLifecycle.begin()
            p.volume = 0f
            p.seekTo(target)
            mainHandler.removeCallbacks(seekTimeoutRunnable)
            mainHandler.postDelayed(seekTimeoutRunnable, SEEK_SETTLE_TIMEOUT_MILLIS)
        }
    }

    /** A missing vendor first-frame callback must not strand a newer seek or leave audio muted. */
    private fun handleSeekTimeout() {
        val target = activeSeekTargetMs ?: return
        activeSeekTargetMs = null
        ReceiverDiagnostics.record(
            "exo.seek.timeout",
            "targetMs=$target pendingMs=${pendingSeekTargetMs ?: "none"}",
        )
        if (pendingSeekTargetMs != null) {
            issuePendingSeek()
        } else if (seekAudioLifecycle.cancel()) {
            restorePlayerGainAfterSeek("timeout")
        }
    }

    /** Applies fast seeking only after this open has proved it is non-live UHD video. */
    private fun updateSeekParameters(player: ExoPlayer, width: Int, height: Int) {
        val parameters = SeekCoalescingPolicy.parameters(isLiveStream, width, height)
        // One open can briefly report 0x0 during a surface transition. Once non-live UHD has
        // enabled its bounded window, do not let that teardown-shaped callback switch it back.
        if (parameters == androidx.media3.exoplayer.SeekParameters.EXACT ||
            player.seekParameters == parameters
        ) return
        player.setSeekParameters(parameters)
        ReceiverDiagnostics.record(
            "exo.seek.mode",
            "mode=sync2s size=${width}x$height live=$isLiveStream",
        )
    }

    /** Drops a queued seek that belongs to media which is no longer playing. */
    private fun cancelPendingSeek() {
        mainHandler.removeCallbacks(pendingSeekRunnable)
        mainHandler.removeCallbacks(seekTimeoutRunnable)
        pendingSeekTargetMs = null
        activeSeekTargetMs = null
        lastSeekIssuedAtMillis = null
        if (seekAudioLifecycle.cancel()) {
            restorePlayerGainAfterSeek("cancel")
        }
    }

    private fun restorePlayerGainAfterSeek(reason: String) {
        val gain = playerGainFallback ?: 1f
        exoPlayer?.volume = gain
        ReceiverDiagnostics.record(
            "exo.seek.audioRestored",
            "generation=${playbackGenerationPolicy.currentGeneration} reason=$reason gain=$gain",
        )
    }

    override suspend fun stop(): Result<Unit> = withContext(Dispatchers.Main) {
        try {
            val p = exoPlayer
            // Phase BEFORE p.stop(): media3 delivers the resulting STATE_IDLE callback
            // synchronously on this thread, and the IDLE handler treats a Playing→IDLE with no
            // recorded reason as an unexplained death — which fired a spurious engine failover
            // in the middle of an ordinary title switch (live, 2026-08-04 09:36).
            _playbackPhase.value = ReceiverPlaybackPhase.Stopped
            stopWatchdogLoop()
            cancelPendingSeek()
            resetExternalSubtitleState()
            p?.stop()
            p?.clearMediaItems()
            _events.emit(ReceiverEvent.Stop(snapshotOnMain()))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun setSpeed(speed: Int): Result<Int> = withContext(Dispatchers.Main) {
        try {
            val p = exoPlayer ?: return@withContext Result.failure(IllegalStateException("Player null"))
            // Kodi encodes pause as speed 0; ExoPlayer has no 0x rate, so map it to playWhenReady.
            if (speed == 0) {
                autoPlayPending = false
                p.playWhenReady = false
                _events.emit(ReceiverEvent.Pause(snapshotOnMain()))
                return@withContext Result.success(0)
            }
            val spd = speed.coerceIn(1, 4).toFloat()
            p.playbackParameters = PlaybackParameters(spd)
            p.playWhenReady = true
            _events.emit(ReceiverEvent.SpeedChanged(snapshotOnMain()))
            Result.success(speed)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun setSpeedMultiplier(speed: Float): Result<Float> = withContext(Dispatchers.Main) {
        try {
            val p = exoPlayer ?: return@withContext Result.failure(IllegalStateException("Player null"))
            val rate = speed.coerceIn(MIN_SPEED_MULTIPLIER, MAX_SPEED_MULTIPLIER)
            p.playbackParameters = PlaybackParameters(rate)
            _events.emit(ReceiverEvent.SpeedChanged(snapshotOnMain()))
            Result.success(rate)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun setVolume(volume: Int): Result<Int> = withContext(Dispatchers.Main) {
        try {
            // Drive the DEVICE volume, not ExoPlayer's own attenuation. Player gain only scaled the
            // app's mix, so the television/soundbar stayed wherever its own remote left it — the
            // phone's slider appeared dead. STREAM_MUSIC is what the TV's own volume keys move, and
            // it is what Fire OS forwards over HDMI-CEC to an AVR or soundbar.
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (max <= 0) return@withContext Result.failure(IllegalStateException("No adjustable output"))
            val requested = volume.coerceIn(0, 100)
            val index = Math.round(requested / 100f * max).coerceIn(0, max)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
            // Fire OS ignores absolute setStreamVolume from third-party apps (its own control app
            // only ever issues relative adjustments), so converge with ADJUST_RAISE/LOWER when the
            // absolute write silently does nothing. Bounded so a fixed-output route cannot spin.
            var guard = 0
            while (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) != index && guard < max + 1) {
                val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val direction = if (current < index) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
                if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == current) break
                guard++
            }
            // Fire TV Edition sets refuse both writes for their fixed output route — AudioService
            // receives the call and does nothing. Rather than leave the phone's slider inert, make
            // up the difference with player gain so the level the user drags is the level they hear.
            val applied = if (deviceVolumePercent() == requested) {
                storeGainFallback(null)
                exoPlayer?.volume = 1f
                requested
            } else {
                val gain = requested / 100f
                storeGainFallback(gain)
                exoPlayer?.volume = gain
                requested
            }
            _events.emit(ReceiverEvent.VolumeChanged(snapshotOnMain()))
            Result.success(applied)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Remembers the fallback level across process death. `commit()`, not `apply()`: the write that
     * matters most happens as the Activity is going away, and Fire OS SIGKILLs a stopped process
     * before an async write flushes (same trap as the engine override, 0.1.16).
     */
    private fun storeGainFallback(gain: Float?) {
        playerGainFallback = gain
        val editor = audioPreferences.edit()
        if (gain == null) editor.remove(GAIN_FALLBACK_KEY) else editor.putFloat(GAIN_FALLBACK_KEY, gain)
        editor.commit()
    }

    /** What the viewer actually hears: the device level, or our gain when the route is fixed. */
    private fun effectiveVolumePercent(): Int =
        playerGainFallback?.let { Math.round(it * 100f).coerceIn(0, 100) } ?: deviceVolumePercent()

    /** Device output level as the 0–100 the Kodi wire protocol uses. */
    private fun deviceVolumePercent(): Int {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return 0
        return Math.round(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat() * 100f)
            .coerceIn(0, 100)
    }

    override suspend fun tracks(): Result<ReceiverTracks> = withContext(Dispatchers.Main) {
        try {
            val p = exoPlayer ?: return@withContext Result.success(ReceiverTracks())
            val currentTracks: Tracks = p.currentTracks

            val audioList = mutableListOf<ReceiverTrack>()
            val subList = mutableListOf<ReceiverTrack>()

            var audioIdx = 0
            var subIdx = 0
            val externalSelected = externalSubtitleEnabled &&
                selectedExternalSubtitleIndex?.let { it in externalSubtitleTracks.indices } == true

            for (group in currentTracks.groups) {
                val type = group.type
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val isSelected = !externalSelected && group.isTrackSelected(i)
                    val lang = format.language ?: ""
                    val name = format.label ?: format.id ?: "Track ${i + 1}"
                    val codec = format.sampleMimeType

                    if (type == C.TRACK_TYPE_AUDIO) {
                        audioList.add(
                            ReceiverTrack(
                                index = audioIdx++,
                                language = lang,
                                name = name,
                                codec = codec,
                                channels = format.channelCount.takeIf { it != Format_NO_VALUE },
                                selected = isSelected,
                            )
                        )
                    } else if (type == C.TRACK_TYPE_TEXT) {
                        subList.add(
                            ReceiverTrack(
                                index = subIdx++,
                                language = lang,
                                name = name,
                                codec = codec,
                                selected = isSelected,
                            )
                        )
                    }
                }
            }

            externalSubtitleTracks.forEachIndexed { index, track ->
                subList.add(
                    ReceiverTrack(
                        index = subIdx++,
                        language = EXTERNAL_SUBTITLE_LANGUAGE,
                        name = if (externalSubtitleTracks.size == 1) {
                            EXTERNAL_SUBTITLE_LABEL
                        } else {
                            "$EXTERNAL_SUBTITLE_LABEL ${index + 1}"
                        },
                        codec = track.mimeType,
                        selected = externalSelected && selectedExternalSubtitleIndex == index,
                    ),
                )
            }

            Result.success(ReceiverTracks(audio = audioList, subtitles = subList))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private companion object {
        const val Format_NO_VALUE = -1

        /**
         * How long to count frames before trusting a measured rate. Two seconds is ~48 frames of
         * film — enough that one dropped frame cannot move the answer past the snap tolerance.
         */
        /**
         * Frames to observe before trusting a measured rate. 48 is two seconds of film — enough
         * that one irregular timestamp cannot move the answer past the snap tolerance.
         */
        const val FRAME_RATE_WINDOW_FRAMES = 48

        /**
         * Largest gap between two rendered frames that can still be a frame interval, in
         * microseconds. 250ms is four frames at the slowest content anyone ships; anything wider is
         * a seek, a discontinuity, or the position-0 frame that precedes an initial seek.
         */
        const val FRAME_RATE_MAX_FRAME_GAP_US = 250_000L

        /** No frame has been rendered yet in the current measuring window. */
        const val NO_FRAME_US = Long.MIN_VALUE

        /**
         * How far the phone's follow-up seek may sit from the position this open already started
         * at and still count as the same request. The phone derives both from one value, so they
         * are normally identical; the tolerance only absorbs rounding through the wire's seconds.
         */
        const val REDUNDANT_STARTUP_SEEK_TOLERANCE_MS = 2_000L
        // The phone sends a sub it already matched to this title; tag it so it is distinguishable
        // from the container's own tracks in the list the phone reads back.
        const val EXTERNAL_SUBTITLE_LANGUAGE = "en"
        const val EXTERNAL_SUBTITLE_LABEL = "Cast subtitle"
        const val EXTERNAL_SUBTITLE_POLL_INTERVAL_MS = 100L

        // Below 0.5x ExoPlayer's time-stretcher starts sounding like a fault; above 2x nobody is
        // watching, they are skimming, and the seek bar is the better tool for that.
        const val MIN_SPEED_MULTIPLIER = 0.25f
        const val MAX_SPEED_MULTIPLIER = 4.0f

        const val AUDIO_PREFERENCES_NAME = "receiver-audio"
        const val GAIN_FALLBACK_KEY = "gainFallback"
        const val BANDWIDTH_LOG_INTERVAL_MILLIS = 5_000L
        const val PLAYBACK_MEDIA_ID_PREFIX = "x4789-open-"
        const val SEEK_FRAME_POSITION_TOLERANCE_MS = SeekCoalescingPolicy.SYNC_TOLERANCE_MILLIS
        const val SEEK_SETTLE_TIMEOUT_MILLIS = DecoderStarvationPolicy.STARVED_FOR_MS

        const val SETTING_AUTOMATIC_AUDIO = "x4789.audio.automatic"
        const val SETTING_AUDIO_PASSTHROUGH = "audiooutput.passthrough"
    }

    /** How many audio tracks the current title has. Main thread only. */
    private fun audioTrackCountOnMain(): Int = exoPlayer
        ?.currentTracks
        ?.groups
        ?.filter { it.type == C.TRACK_TYPE_AUDIO }
        ?.sumOf { it.length }
        ?: 0

    /** Index of the audio track the player is currently using, in the same flat numbering the
     * phone uses, or null when there is no player or nothing selected. Main thread only. */
    private fun currentAudioTrackIndexOnMain(): Int? {
        val p = exoPlayer ?: return null
        var index = 0
        for (group in p.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                if (group.isTrackSelected(i)) return index
                index++
            }
        }
        return null
    }

    /** Selected subtitle index in the same flattened numbering exposed to the phone. */
    private fun currentTextTrackIndexOnMain(): Int? {
        val p = exoPlayer ?: return null
        val embeddedCount = embeddedTextTrackCount(p.currentTracks)
        if (externalSubtitleEnabled) {
            selectedExternalSubtitleIndex
                ?.takeIf { it in externalSubtitleTracks.indices }
                ?.let { return embeddedCount + it }
        }
        var index = 0
        for (group in p.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until group.length) {
                if (group.isTrackSelected(i)) return index
                index++
            }
        }
        return null
    }

    private fun currentTextTrackCountOnMain(): Int {
        val embeddedCount = exoPlayer?.currentTracks?.let(::embeddedTextTrackCount) ?: 0
        return embeddedCount + externalSubtitleTracks.size
    }

    override suspend fun selectAudio(index: Int): Result<Unit> = withContext(Dispatchers.Main) {
        try {
            val p = exoPlayer
            if (p == null) {
                pendingRestoreAudioTrackIndex = index
                return@withContext Result.success(Unit)
            }
            val tracks = p.currentTracks
            var currIdx = 0
            for (group in tracks.groups) {
                if (group.type == C.TRACK_TYPE_AUDIO) {
                    for (i in 0 until group.length) {
                        if (currIdx == index) {
                            pendingRestoreAudioTrackIndex = null
                            p.trackSelectionParameters = p.trackSelectionParameters
                                .buildUpon()
                                .setOverrideForType(
                                    TrackSelectionOverride(group.mediaTrackGroup, i)
                                )
                                .build()
                            return@withContext Result.success(Unit)
                        }
                        currIdx++
                    }
                }
            }
            pendingRestoreAudioTrackIndex = index
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun selectSubtitle(selection: SubtitleSelection): Result<Unit> = withContext(Dispatchers.Main) {
        try {
            val p = exoPlayer
            if (p == null) {
                when (selection) {
                    is SubtitleSelection.Off -> {
                        pendingRestoreSubtitleTrackIndex = null
                        pendingRecoverySubtitleSelection = RecoverySubtitleSelection.ExplicitlyOff
                        externalSubtitleEnabled = false
                        stopExternalSubtitleTicker()
                        _cues.value = emptyList()
                    }
                    is SubtitleSelection.Index -> {
                        pendingRestoreSubtitleTrackIndex = selection.value
                    }
                    is SubtitleSelection.On, is SubtitleSelection.Latest -> Unit
                }
                return@withContext Result.success(Unit)
            }
            val embeddedCount = embeddedTextTrackCount(p.currentTracks)
            val builder = p.trackSelectionParameters.buildUpon()
            when (selection) {
                is SubtitleSelection.Off -> {
                    pendingRestoreSubtitleTrackIndex = null
                    externalSubtitleEnabled = false
                    stopExternalSubtitleTicker()
                    _cues.value = emptyList()
                    builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                }
                is SubtitleSelection.On -> {
                    if (selectedExternalSubtitleIndex?.let { it in externalSubtitleTracks.indices } == true) {
                        externalSubtitleEnabled = true
                        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        p.trackSelectionParameters = builder.build()
                        startExternalSubtitleTicker()
                        refreshExternalSubtitleCues()
                        return@withContext Result.success(Unit)
                    }
                    externalSubtitleEnabled = false
                    builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                }
                is SubtitleSelection.Latest -> {
                    if (externalSubtitleTracks.isNotEmpty()) {
                        selectedExternalSubtitleIndex = externalSubtitleTracks.lastIndex
                        externalSubtitleEnabled = true
                        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        p.trackSelectionParameters = builder.build()
                        startExternalSubtitleTicker()
                        refreshExternalSubtitleCues()
                        return@withContext Result.success(Unit)
                    }
                    externalSubtitleEnabled = false
                    builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                }
                is SubtitleSelection.Index -> {
                    if (selection.value >= embeddedCount) {
                        val externalIndex = selection.value - embeddedCount
                        if (externalIndex !in externalSubtitleTracks.indices) {
                            pendingRestoreSubtitleTrackIndex = selection.value
                            p.trackSelectionParameters = builder.build()
                            return@withContext Result.success(Unit)
                        }
                        pendingRestoreSubtitleTrackIndex = null
                        selectedExternalSubtitleIndex = externalIndex
                        externalSubtitleEnabled = selection.enable
                        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        p.trackSelectionParameters = builder.build()
                        if (selection.enable) {
                            startExternalSubtitleTicker()
                            refreshExternalSubtitleCues()
                        } else {
                            stopExternalSubtitleTicker()
                            _cues.value = emptyList()
                        }
                        return@withContext Result.success(Unit)
                    }

                    externalSubtitleEnabled = false
                    selectedExternalSubtitleIndex = null
                    stopExternalSubtitleTicker()
                    _cues.value = emptyList()
                    builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !selection.enable)
                    val target = textTrackAt(p.currentTracks, selection.value)
                    if (target == null) {
                        pendingRestoreSubtitleTrackIndex = selection.value
                        p.trackSelectionParameters = builder.build()
                        return@withContext Result.success(Unit)
                    }
                    pendingRestoreSubtitleTrackIndex = null
                    builder.setOverrideForType(
                        TrackSelectionOverride(target.first.mediaTrackGroup, target.second),
                    )
                }
            }
            p.trackSelectionParameters = builder.build()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Builds the primary video source from the original open request. External subtitles are
     * deliberately not part of this MediaItem: they are parsed and rendered by the overlay so
     * adding one cannot reset the active video decoder.
     */
    private fun buildMediaSource(request: OpenMediaRequest): androidx.media3.exoplayer.source.MediaSource {
        val httpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
        if (request.headers.isNotEmpty()) {
            httpDataSourceFactory.setDefaultRequestProperties(request.headers)
        }
        val mediaItem = MediaItem.Builder()
            .setMediaId("$PLAYBACK_MEDIA_ID_PREFIX${playbackGenerationPolicy.currentGeneration}")
            .setUri(Uri.parse(request.url))
            .build()
        val extractorsFactory = androidx.media3.extractor.DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
        return DefaultMediaSourceFactory(httpDataSourceFactory, extractorsFactory).createMediaSource(mediaItem)
    }

    /** Flattens the text tracks the way `tracks()` numbers them → (group, indexInGroup). */
    private fun textTrackAt(tracks: Tracks, index: Int): Pair<Tracks.Group, Int>? {
        var current = 0
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until group.length) {
                if (current == index) return group to i
                current++
            }
        }
        return null
    }

    private fun embeddedTextTrackCount(tracks: Tracks): Int = tracks.groups
        .filter { it.type == C.TRACK_TYPE_TEXT }
        .sumOf { it.length }

    override suspend fun addSubtitle(url: String): Result<Unit> {
        val loadContext = withContext(Dispatchers.Main) {
            if (closed.get()) return@withContext null
            val player = exoPlayer ?: return@withContext null
            val request = lastOpenRequest ?: return@withContext null
            Triple(player, publicOpenGeneration, request)
        } ?: return Result.failure(IllegalStateException("No active playback"))

        val (playerAtRequest, openGenerationAtRequest, request) = loadContext
        val loadJob = withContext(Dispatchers.Main) {
            scope.async(Dispatchers.IO) {
                externalSubtitleLoader.load(url, request.headers)
            }.also(externalSubtitleLoadJobs::add)
        }

        return try {
            val track = loadJob.await()
            withContext(Dispatchers.Main) {
                if (closed.get() || publicOpenGeneration != openGenerationAtRequest ||
                    exoPlayer !== playerAtRequest || lastOpenRequest !== request
                ) {
                    Result.failure(IllegalStateException("Playback changed while subtitle was loading"))
                } else {
                    externalSubtitleTracks += track
                    selectedExternalSubtitleIndex = externalSubtitleTracks.lastIndex
                    externalSubtitleEnabled = true
                    val player = exoPlayer ?: return@withContext Result.failure(IllegalStateException("Player null"))
                    player.trackSelectionParameters = player.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                    startExternalSubtitleTicker()
                    refreshExternalSubtitleCues()
                    ReceiverDiagnostics.record(
                        "exo.subtitle.external.loaded",
                        "mime=${track.mimeType} windows=${track.cueWindows.size}",
                    )
                    Result.success(Unit)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ReceiverDiagnostics.record(
                "exo.subtitle.external.error",
                "${e::class.java.simpleName}: ${e.message}",
            )
            Result.failure(e)
        } finally {
            loadJob.cancel()
            withContext(NonCancellable + Dispatchers.Main) { externalSubtitleLoadJobs.remove(loadJob) }
        }
    }

    private fun startExternalSubtitleTicker() {
        if (externalSubtitleTickerJob?.isActive == true) return
        externalSubtitleTickerJob = scope.launch(Dispatchers.Main.immediate) {
            while (isActive && externalSubtitleEnabled) {
                refreshExternalSubtitleCues()
                delay(EXTERNAL_SUBTITLE_POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopExternalSubtitleTicker() {
        externalSubtitleTickerJob?.cancel()
        externalSubtitleTickerJob = null
    }

    /** Updates only when the visible cue set changes, so the Activity is not invalidated every tick. */
    private fun refreshExternalSubtitleCues() {
        val selected = selectedExternalSubtitleIndex
            ?.takeIf { externalSubtitleEnabled && it in externalSubtitleTracks.indices }
            ?.let(externalSubtitleTracks::get)
        val cues = selected?.cuesAt(exoPlayer?.currentPosition ?: 0L) ?: emptyList()
        if (_cues.value != cues) _cues.value = cues
    }

    private fun resetExternalSubtitleState() {
        externalSubtitleLoadJobs.toList().forEach(Job::cancel)
        externalSubtitleLoadJobs.clear()
        stopExternalSubtitleTicker()
        externalSubtitleTracks.clear()
        selectedExternalSubtitleIndex = null
        externalSubtitleEnabled = false
        _cues.value = emptyList()
    }

    /** Resolves an analytics callback to the exact open that produced it. */
    private fun playbackGeneration(eventTime: AnalyticsListener.EventTime): Long? {
        val timeline = eventTime.timeline
        val windowIndex = eventTime.windowIndex
        if (timeline.isEmpty || windowIndex !in 0 until timeline.windowCount) return null
        val mediaId = timeline.getWindow(windowIndex, Timeline.Window()).mediaItem.mediaId
        if (!mediaId.startsWith(PLAYBACK_MEDIA_ID_PREFIX)) return null
        return mediaId.removePrefix(PLAYBACK_MEDIA_ID_PREFIX).toLongOrNull()
    }

    override suspend fun stageNowPlaying(
        title: String?,
        subtitle: String?,
        isLive: Boolean,
        preparationStage: ReceiverPreparationStage?,
        castId: String?,
    ) {
        currentTitle = title
        currentSubtitle = subtitle
        isLiveStream = isLive
        if (preparationStage != null) {
            val next = ReceiverPlaybackPhasePolicy.preparation(
                prior = _playbackPhase.value,
                title = title,
                stage = preparationStage,
                castId = castId,
                activeCastId = stagedCastId,
            )
            if (preparationStage == ReceiverPreparationStage.RESOLVING ||
                preparationStage == ReceiverPreparationStage.PREPARING
            ) {
                stagedCastId = castId
            }
            if (next != null) {
                _playbackPhase.value = next
                ReceiverDiagnostics.record(
                    "exo.prepare",
                    "cast=${castId?.take(8) ?: "none"} stage=${preparationStage.name.lowercase()}",
                )
            }
        }
    }

    override suspend fun stageSubtitleStyle(params: JsonObject) {
        _subtitleStyle.value = ReceiverSubtitleStyle.from(params)
    }

    /** The phone's speaker profile intersected with what this HDMI/ARC route actually accepts. */
    private fun resolveAudioRoute(): ExoAudioRoute = ExoAudioRoutePolicy.resolve(
        automaticRouting = automaticAudioRouting.get(),
        passthroughRequested = passthroughRequested.get(),
        routeSupported = AndroidDirectAudioProbe.supportedMpvCodecs(applicationContext),
        profileRequested = passthroughCodecRequests,
        routeProbeAvailable = AndroidDirectAudioProbe.routeProbeAvailable,
    )

    /**
     * Null means "use the platform's own capabilities" — the automatic profile. Otherwise the sink
     * is offered PCM plus only the encodings the profile asked for and the route confirmed, so
     * everything else is decoded. The device's real channel count is preserved either way, because
     * a decode-to-PCM profile still wants multichannel output when the receiver has the speakers.
     */
    private fun audioCapabilities(route: ExoAudioRoute): AudioCapabilities? {
        if (audioClockWatchdogPolicy.forcedPcmDemote) {
            val platform = AudioCapabilities.getCapabilities(applicationContext)
            return AudioCapabilities(intArrayOf(C.ENCODING_PCM_16BIT), platform.maxChannelCount)
        }
        return when (route) {
            is ExoAudioRoute.Automatic -> null
            is ExoAudioRoute.Constrained -> {
                val platform = AudioCapabilities.getCapabilities(applicationContext)
                val encodings = (
                    listOf(C.ENCODING_PCM_16BIT) + ExoAudioRoutePolicy.encodings(route.codecs)
                    ).distinct().toIntArray()
                AudioCapabilities(encodings, platform.maxChannelCount)
            }
        }
    }

    override suspend fun applySetting(name: String, value: JsonPrimitive): Result<Boolean> {
        val key = name.lowercase()
        when {
            key == UpscalePolicy.SETTING_NAME -> {
                val enabled = value.booleanOrNull ?: return Result.success(false)
                // The phone speaks a boolean; the TV pill's FORCE_1080P survives untouched.
                setUpscaleMode(UpscalePolicy.modeFromBoolean(enabled))
                return Result.success(true)
            }

            key == SETTING_AUTOMATIC_AUDIO -> {
                val enabled = value.booleanOrNull ?: return Result.success(false)
                automaticAudioRouting.set(enabled)
            }

            key == SETTING_AUDIO_PASSTHROUGH -> {
                val enabled = value.booleanOrNull ?: return Result.success(false)
                automaticAudioRouting.set(false)
                passthroughRequested.set(enabled)
                // A profile update sends the master switch first. Clear stale codec choices so a new
                // lossy profile cannot briefly inherit TrueHD/DTS-HD from the previous profile.
                passthroughCodecRequests.clear()
            }

            DirectAudioPolicy.codecSettings.containsKey(key) -> {
                val enabled = value.booleanOrNull ?: return Result.success(false)
                val codec = DirectAudioPolicy.codecSettings.getValue(key)
                passthroughCodecRequests[codec] = enabled
            }

            // Kodi's remaining profile keys describe policy around its own audio engine. Report them
            // as unhandled rather than claiming success for a setting this path never applies.
            else -> return Result.success(false)
        }

        return try {
            applyAudioRouteIfChanged()
            Result.success(true)
        } catch (error: CancellationException) {
            throw error
        } catch (e: Throwable) {
            ReceiverDiagnostics.record("exo.audioroute.error", "${e::class.java.simpleName}: ${e.message}")
            Result.failure(e)
        }
    }

    override suspend fun applyAudioProfile(profile: ReceiverAudioProfile): Result<Boolean> {
        val plan = ReceiverAudioProfilePolicy.plan(profile)
        automaticAudioRouting.set(plan.automatic)
        passthroughRequested.set(plan.passthrough)
        passthroughCodecRequests.clear()
        passthroughCodecRequests.putAll(plan.codecRequests)
        return try {
            applyAudioRouteIfChanged()
            ReceiverDiagnostics.record(
                "exo.audioProfile",
                "profile=${profile.name.lowercase()} codecs=${plan.codecRequests.filterValues { it }.keys.sorted()}",
            )
            Result.success(true)
        } catch (error: CancellationException) {
            throw error
        } catch (e: Throwable) {
            ReceiverDiagnostics.record("exo.audioProfile.error", e.javaClass.simpleName)
            Result.failure(e)
        }
    }

    /**
     * Records the newly-resolved route so the NEXT open builds its sink with it.
     *
     * An earlier revision released and rebuilt the player here to apply the change immediately.
     * That is not worth it: a settings push routinely arrives while a title is playing, so the
     * "improvement" tore down live playback. The sink's capabilities are fixed at construction, so
     * a deferred apply is the only non-destructive option — and the phone sends the speaker profile
     * before an open in the normal flow, which is exactly when it takes effect anyway.
     */
    private suspend fun applyAudioRouteIfChanged() = withContext(Dispatchers.Main) {
        val route = withContext(Dispatchers.Default) { resolveAudioRoute() }
        if (route == activeAudioRoute) return@withContext
        ReceiverDiagnostics.record("exo.audioroute", "route=$route appliesOn=nextOpen")
        activeAudioRoute = route
        audioRouteRebuildPending = exoPlayer != null
    }

    override suspend fun executeAction(action: String): Result<Unit> = withContext(Dispatchers.Main) {
        when (action.lowercase()) {
            "playpause", "toggle" -> playPause().map { }
            "stop" -> stop()
            else -> Result.success(Unit)
        }
    }

    override suspend fun announceExternalHandoff(playerLabel: String, playerPackage: String) {
        _events.emit(
            ReceiverEvent.ExternalHandoff(
                snapshot = snapshot(),
                playerLabel = playerLabel,
                playerPackage = playerPackage,
            ),
        )
    }

    override suspend fun openWithSoftware(request: OpenMediaRequest): Result<Unit> = open(request)

    override suspend fun retryLastOpen(): Result<Unit> =
        lastOpenRequest?.let {
            _events.emit(ReceiverEvent.LinkRefreshRequested(snapshot(), "user_retry"))
            open(it)
        } ?: Result.failure(IllegalStateException("No stream to retry"))

    override fun lastOpenMedia(): OpenMediaRequest? = lastOpenRequest

    private fun startWatchdogLoop() {
        if (watchdogTickerJob?.isActive == true) return
        lastWatchdogWallMs = android.os.SystemClock.elapsedRealtime()
        lastWatchdogPosMs = exoPlayer?.currentPosition ?: 0L
        watchdogTickerJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                delay(500)
                val p = exoPlayer ?: break
                val nowWallMs = android.os.SystemClock.elapsedRealtime()
                val nowPosMs = p.currentPosition
                val deltaWallMs = nowWallMs - lastWatchdogWallMs
                val deltaPosMs = nowPosMs - lastWatchdogPosMs

                lastWatchdogWallMs = nowWallMs
                lastWatchdogPosMs = nowPosMs

                evaluateDecoderStarvation(p, nowWallMs, nowPosMs)

                val action = audioClockWatchdogPolicy.evaluateSample(
                    deltaPosMs = deltaPosMs,
                    deltaWallMs = deltaWallMs,
                    playWhenReady = p.playWhenReady,
                    playbackSpeed = p.playbackParameters.speed,
                )

                when (action) {
                    AudioClockRaceAction.NONE -> {}
                    AudioClockRaceAction.REOPEN -> executeClockRaceReopen(nowPosMs)
                    AudioClockRaceAction.DEMOTE_TO_PCM -> executeClockRacePcmDemote(nowPosMs)
                    AudioClockRaceAction.SURFACE_FAILURE -> executeClockRaceFailure()
                }
            }
        }
    }

    /** Acts only on a full-buffer frozen-playhead decoder/render-pipeline stall. */
    private fun evaluateDecoderStarvation(player: ExoPlayer, nowWallMs: Long, nowPosMs: Long) {
        when (decoderStallRecoveryPolicy.evaluate(
            playWhenReady = player.playWhenReady,
            bufferedAheadMs = player.totalBufferedDuration,
            positionMs = nowPosMs,
            nowMs = nowWallMs,
        )) {
            DecoderStallAction.NONE -> Unit
            DecoderStallAction.RECOVER -> executeDecoderStallRecovery(
                expectedPublicOpenGeneration = publicOpenGeneration,
                resumePositionMs = nowPosMs,
            )
            DecoderStallAction.SURFACE_FAILURE -> surfaceDecoderStallFailure()
        }
    }

    /**
     * Replaces the player exactly once for this public open. This runs on the main watchdog
     * coroutine: the old player is detached and fully released before a replacement is built.
     */
    private fun executeDecoderStallRecovery(
        expectedPublicOpenGeneration: Long,
        resumePositionMs: Long,
    ) {
        if (closed.get() || expectedPublicOpenGeneration != publicOpenGeneration) return
        val request = lastOpenRequest ?: return
        val outgoing = exoPlayer ?: return
        val failureSnapshot = runCatching { snapshotNowOnMain() }.getOrDefault(ReceiverSnapshot())
        var outgoingReleased = false

        try {
            val wasPlaying = outgoing.playWhenReady
            val playbackParameters = outgoing.playbackParameters
            val outgoingAudioIndex = currentAudioTrackIndexOnMain()
            val outgoingSubtitleSelection = RecoverySubtitleSelectionPolicy.captureForRecovery(
                // External cues deliberately disable Media3's native text renderer. They are
                // still active subtitle state and must be captured by their flattened index.
                textRendererDisabled = outgoing.trackSelectionParameters.disabledTrackTypes
                    .contains(C.TRACK_TYPE_TEXT) && !externalSubtitleEnabled,
                pendingRecoverySelection = pendingRecoverySubtitleSelection,
                pendingRestoreIndex = pendingRestoreSubtitleTrackIndex,
                textTrackCount = currentTextTrackCountOnMain(),
                selectedIndex = currentTextTrackIndexOnMain(),
            )
            ReceiverDiagnostics.record(
                "exo.decoder.recover",
                "publicGeneration=$expectedPublicOpenGeneration resumeMs=$resumePositionMs " +
                    "decoder=${lastVideoDecoderName ?: "unknown"}",
            )
            stopWatchdogLoop()
            stopExternalSubtitleTicker()
            _cues.value = emptyList()
            _playbackPhase.value = ReceiverPlaybackPhase.Opening(
                currentTitle,
                ReceiverPreparationStage.PREPARING,
                currentCastId,
            )

            // Retire both views of old ownership before release. Do not construct the replacement
            // until release returns: Media3 release is the only completion boundary available here.
            playerView?.player = null
            if (exoPlayer === outgoing) activePlayerInstanceGeneration = 0L
            outgoing.clearVideoSurface()
            outgoing.release()
            // Keep controller ownership until both release calls have completed. If either throws,
            // the catch path retains this reference and marks it terminal for a later retry.
            if (exoPlayer === outgoing) exoPlayer = null
            outgoingReleased = true

            // A public open cannot interleave on the main thread, but retain this guard for any
            // future asynchronous scheduling and never replace a newer title.
            if (closed.get() || expectedPublicOpenGeneration != publicOpenGeneration) return

            outgoingAudioIndex?.let { pendingRestoreAudioTrackIndex = it }
            pendingRestoreSubtitleTrackIndex = null
            pendingRecoverySubtitleSelection = outgoingSubtitleSelection
            lastVideoDecoderName = null
            autoPlayPending = false
            openStartPositionMs = null
            val replacementGeneration = playbackGenerationPolicy.beginOpen()
            decoderStallRecoveryPolicy.beginRecoveryOpen(
                positionMs = resumePositionMs,
                nowMs = SystemClock.elapsedRealtime(),
            )
            initExoPlayer()
            val replacement = exoPlayer
                ?: throw IllegalStateException("ExoPlayer recovery initialization failed")
            if (externalSubtitleEnabled) startExternalSubtitleTicker()
            lastVideoWidth = 0
            lastVideoHeight = 0
            lastVideoHdr = false
            lastVideoFrameRate = 0.0
            lastVideoCodec = ""
            frameWindowStartUs = 0L
            frameWindowCount = 0
            frameWindowLastFrameUs = NO_FRAME_US
            replacement.setMediaSource(buildMediaSource(request), resumePositionMs)
            replacement.playbackParameters = playbackParameters
            replacement.playWhenReady = wasPlaying
            replacement.prepare()
            ReceiverDiagnostics.record(
                "exo.decoder.recover.reopened",
                "publicGeneration=$expectedPublicOpenGeneration playerGeneration=$replacementGeneration " +
                    "resumeMs=$resumePositionMs playWhenReady=$wasPlaying",
            )
        } catch (e: Throwable) {
            ReceiverDiagnostics.record(
                "exo.decoder.recover.error",
                "${e::class.java.simpleName}: ${e.message}",
            )
            surfaceDecoderStallFailure(
                failureSnapshot = failureSnapshot,
                releaseImmediately = outgoingReleased,
            )
        }
    }

    /** The second full-buffer stall is terminal; the policy has already latched this once. */
    private fun surfaceDecoderStallFailure(
        failureSnapshot: ReceiverSnapshot? = null,
        releaseImmediately: Boolean = true,
        humanText: String = "The TV video decoder stopped. Try another source or restart the TV.",
        mimeType: String? = null,
    ) {
        terminalDecoderStallPlayer = true
        val snapshot = failureSnapshot ?: runCatching { snapshotNowOnMain() }.getOrDefault(ReceiverSnapshot())
        stopWatchdogLoop()
        _playbackPhase.value = ReceiverPlaybackPhase.Error(humanText)
        ReceiverDiagnostics.record(
            "exo.decoder.terminal",
            "releaseImmediately=$releaseImmediately playerPresent=${exoPlayer != null}",
        )
        emitPlaybackError(
            category = PlaybackErrorTaxonomy.CODEC_VIDEO,
            mimeType = mimeType ?: lastVideoCodec.takeIf { it.isNotBlank() },
            humanText = humanText,
            fatal = true,
            snapshotOverride = snapshot,
        )
        if (releaseImmediately) {
            try {
                releaseCurrentPlayer()
                terminalDecoderStallPlayer = false
            } catch (e: Throwable) {
                ReceiverDiagnostics.record(
                    "exo.decoder.terminal.releaseError",
                    "${e::class.java.simpleName}: ${e.message}",
                )
            }
        }
    }

    private fun stopWatchdogLoop() {
        watchdogTickerJob?.cancel()
        watchdogTickerJob = null
    }

    /** Retires surface and player ownership before any replacement player is constructed. */
    private fun releaseCurrentPlayer() {
        stopWatchdogLoop()
        stopExternalSubtitleTicker()
        _cues.value = emptyList()
        playerView?.player = null
        val outgoing = exoPlayer
        if (outgoing == null) return
        if (exoPlayer === outgoing) activePlayerInstanceGeneration = 0L
        outgoing.clearVideoSurface()
        outgoing.release()
        if (exoPlayer === outgoing) exoPlayer = null
    }

    private fun executeClockRaceReopen(resumePosMs: Long) {
        ReceiverDiagnostics.record(
            "exo.audio.clock.race",
            "action=reopen attempts=${audioClockWatchdogPolicy.recoveryAttempts} resumePosMs=$resumePosMs",
        )
        val req = lastOpenRequest ?: return
        val p = exoPlayer ?: return
        val wasPlaying = p.playWhenReady
        p.setMediaSource(buildMediaSource(req), resumePosMs)
        p.playWhenReady = wasPlaying
        p.prepare()
    }

    private fun executeClockRacePcmDemote(resumePosMs: Long) {
        ReceiverDiagnostics.record(
            "exo.audio.clock.race",
            "action=demoteToPcm attempts=${audioClockWatchdogPolicy.recoveryAttempts} resumePosMs=$resumePosMs",
        )
        val req = lastOpenRequest ?: return
        stopWatchdogLoop()
        releaseCurrentPlayer()
        initExoPlayer()
        val p = exoPlayer ?: return
        if (externalSubtitleEnabled) {
            startExternalSubtitleTicker()
            refreshExternalSubtitleCues()
        }
        p.setMediaSource(buildMediaSource(req), resumePosMs)
        p.playWhenReady = true
        p.prepare()
        val pv = playerView
        if (pv != null) {
            pv.player = p
        } else {
            val holder = attachedSurfaceHolder
            if (holder != null && holder.surface.isValid) {
                p.setVideoSurfaceHolder(holder)
            } else if (attachedSurface != null && attachedSurface!!.isValid) {
                p.setVideoSurface(attachedSurface)
            }
        }
        startWatchdogLoop()
    }

    private fun executeClockRaceFailure() {
        ReceiverDiagnostics.record(
            "exo.audio.clock.failed",
            "attempts=${audioClockWatchdogPolicy.recoveryAttempts}",
        )
        stopWatchdogLoop()
        val msg = "Audio hardware clock failure on TV. Please reboot the TV."
        _playbackPhase.value = ReceiverPlaybackPhase.Error(msg)
        emitPlaybackError(
            category = PlaybackErrorTaxonomy.CODEC_AUDIO,
            mimeType = null,
            humanText = msg,
            // All bounded recovery stages are exhausted. The phone must replace stale
            // playing/buffering state with this terminal error instead of ignoring it.
            fatal = true,
        )
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { applicationContext.contentResolver.unregisterContentObserver(volumeObserver) }
            mainHandler.post {
                stopWatchdogLoop()
                cancelPendingSeek()
                resetExternalSubtitleState()
                releaseCurrentPlayer()
                playerView = null
                attachedSurface = null
                attachedSurfaceHolder = null
            }
        }
    }
}

/**
 * The stock renderers factory with a deeper AudioTrack cushion.
 *
 * Media3's [DefaultAudioTrackBufferSizeProvider] sizes PCM output between 250ms and 750ms and
 * passthrough at a flat 250ms. On Fire OS that is the whole margin the audio path has, because the
 * receiver runs MediaCodec synchronously (vendor decoders deadlock in async mode) — so a slow video
 * dequeue, a bitrate spike, or a background system task pushes the next audio feed past the sink's
 * remaining milliseconds and AudioTrack plays a gap. The gap is heard as a click or pop, at any
 * loudness, which is what separates it from downmix clipping (that only bites on loud passages).
 *
 * The libmpv path solved the same platform behaviour with `audio-buffer=2.0`. These values are the
 * ExoPlayer equivalent, kept a little under 2s so the allocation stays modest on a low-RAM stick and
 * pause/seek latency does not become audible.
 */
@OptIn(UnstableApi::class)
private class WideAudioBufferRenderersFactory(
    context: Context,
    /**
     * Null keeps the platform's real capabilities, so passthrough happens wherever the route allows
     * it. A non-null value is the phone's speaker profile: PCM plus only the encodings it asked for.
     */
    private val audioCapabilities: AudioCapabilities?,
    // FFmpeg video is intentionally excluded: the vendor extension also registers a CPU video
    // renderer, which device evidence showed decoding 4K AVC at ~15fps. Keep only its audio
    // fallback so unsupported video is surfaced instead of rendered as broken frames.
) : androidx.media3.exoplayer.DefaultRenderersFactory(context) {

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: androidx.media3.exoplayer.audio.AudioRendererEventListener,
        out: ArrayList<androidx.media3.exoplayer.Renderer>,
    ) {
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out,
        )
        if (!FfmpegExtensionPolicy.ENABLE_AUDIO_RENDERER ||
            extensionRendererMode == androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
        ) {
            return
        }
        var insertIndex = out.size
        if (extensionRendererMode == androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER) {
            insertIndex -= 1
        }
        out.add(
            insertIndex,
            io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegAudioRenderer(
                eventHandler,
                eventListener,
                audioSink,
            ),
        )
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink = DefaultAudioSink.Builder(context)
        .apply { audioCapabilities?.let { setAudioCapabilities(it) } }
        .setEnableFloatOutput(enableFloatOutput)
        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        .setAudioTrackBufferSizeProvider(
            DefaultAudioTrackBufferSizeProvider.Builder()
                .setMinPcmBufferDurationUs(2_500_000)
                .setMaxPcmBufferDurationUs(2_500_000)
                .setPassthroughBufferDurationUs(1_500_000)
                .build(),
        )
        .build()
}
