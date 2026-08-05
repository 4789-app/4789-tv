package com.fourseveneightnine.tv.player

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
import com.fourseveneightnine.tv.protocol.ReceiverEvent
import com.fourseveneightnine.tv.protocol.ReceiverSnapshot
import com.fourseveneightnine.tv.protocol.ReceiverTrack
import com.fourseveneightnine.tv.protocol.ReceiverTracks
import com.fourseveneightnine.tv.protocol.SeekCommand
import com.fourseveneightnine.tv.protocol.SubtitleSelection
import com.fourseveneightnine.tv.player.upscale.SgsrVideoEffect
import com.fourseveneightnine.tv.player.upscale.UpscaleMode
import com.fourseveneightnine.tv.player.upscale.UpscalePolicy
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
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
    private var playerView: PlayerView? = null
    private var attachedSurface: Surface? = null
    private var attachedSurfaceHolder: SurfaceHolder? = null

    private var currentTitle: String? = null
    private var currentSubtitle: String? = null
    private var isLiveStream: Boolean = false
    private var lastOpenRequest: OpenMediaRequest? = null

    private val okHttpClient = OkHttpClient.Builder()
        .connectionPool(okhttp3.ConnectionPool(5, 5, TimeUnit.MINUTES))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
        .build()

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

    // Sideloaded subtitle tracks, replayed into every rebuild of the source.
    private val externalSubtitles = mutableListOf<MediaItem.SubtitleConfiguration>()
    private var selectAddedSubtitle = false

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
    private var lastSeekIssuedAtMillis: Long? = null
    private val pendingSeekRunnable = Runnable { issuePendingSeek() }

    // Speaker-profile state pushed by the phone. ExoPlayer has no live passthrough switch, so these
    // are resolved into the AudioCapabilities the sink is built with (see audioCapabilities()).
    // Automatic is the default and matches the previous behaviour: the platform decides.
    private val automaticAudioRouting = AtomicBoolean(true)
    private val passthroughRequested = AtomicBoolean(false)
    private val passthroughCodecRequests = ConcurrentHashMap<String, Boolean>()

    /** The route the currently-built audio sink was created for; null until the first build. */
    @Volatile
    private var activeAudioRoute: ExoAudioRoute? = null

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
    private var frameWindowStartMs = 0L
    private var frameWindowCount = 0

    // The television's own remote moves STREAM_MUSIC too. Mirror that back so the phone's slider
    // does not drift out of sync with the set.
    private val volumeObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            scope.launch { _events.emit(ReceiverEvent.VolumeChanged(snapshotOnMain())) }
        }
    }

    init {
        mainHandler.post {
            initExoPlayer()
        }
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
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ ExoStartupBufferPolicy.MIN_BUFFER_MS,
                /* maxBufferMs = */ ExoStartupBufferPolicy.MAX_BUFFER_MS,
                /* bufferForPlaybackMs = */ ExoStartupBufferPolicy.BUFFER_FOR_PLAYBACK_MS,
                /* bufferForPlaybackAfterRebufferMs = */
                ExoStartupBufferPolicy.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            .setTargetBufferBytes(ExoStartupBufferPolicy.TARGET_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

        // AFTDCT31 (Fire OS 7) vendor decoders deadlock in MediaCodec asynchronous callback mode:
        // the playback thread blocks forever inside codec init and the player never leaves
        // STATE_BUFFERING (same platform bug that froze mpv's hwdec path, README 0.1.15).
        // Media3 1.5.1 defaults to the async adapter on API 23+, so force synchronous operation.
        //
        // Synchronous queueing is also why this player needs a wider AudioTrack buffer than
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
            .forceDisableMediaCodecAsynchronousQueueing()
            .setEnableDecoderFallback(true)
            // ON, not PREFER: MediaCodec still wins whenever the chip can handle the format, so
            // hardware video and bit-exact AC3/E-AC3 passthrough to an AVR are untouched. The
            // ffmpeg renderers are consulted only for what MediaCodec refuses — which on this box
            // is DTS, DTS-HD and TrueHD, the formats that killed playback outright before.
            .setExtensionRendererMode(
                androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
            )

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
                ReceiverDiagnostics.record(
                    "exo.audio.underrun",
                    "bufferSize=$bufferSize bufferMs=$bufferSizeMs sinceLastFeedMs=$elapsedSinceLastFeedMs",
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
                ReceiverDiagnostics.record("exo.decoder.video", decoderName)
            }

            override fun onAudioTrackInitialized(
                eventTime: AnalyticsListener.EventTime,
                audioTrackConfig: AudioSink.AudioTrackConfig,
            ) {
                ReceiverDiagnostics.record(
                    "exo.audio.trackInit",
                    "encoding=${audioTrackConfig.encoding} rate=${audioTrackConfig.sampleRate} " +
                        "channels=${audioTrackConfig.channelConfig} bufferSize=${audioTrackConfig.bufferSize} " +
                        "offload=${audioTrackConfig.offload} tunneling=${audioTrackConfig.tunneling}",
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
                lastVideoHdr = info != null && info.colorSpace == C.COLOR_SPACE_BT2020 &&
                    (info.colorTransfer == C.COLOR_TRANSFER_ST2084 || info.colorTransfer == C.COLOR_TRANSFER_HLG)
                lastVideoFrameRate = format.frameRate
                    .takeIf { it != Format.NO_VALUE.toFloat() && it > 0f }
                    ?.toDouble()
                    ?: 0.0
                lastVideoCodec = format.sampleMimeType.orEmpty()
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
            if (lastVideoFrameRate > 0.0) return@setVideoFrameMetadataListener
            if (frameWindowCount == 0) {
                frameWindowStartMs = presentationTimeUs
                frameWindowCount = 1
                return@setVideoFrameMetadataListener
            }
            frameWindowCount++
            val spanUs = presentationTimeUs - frameWindowStartMs
            if (frameWindowCount < FRAME_RATE_WINDOW_FRAMES || spanUs <= 0L) {
                return@setVideoFrameMetadataListener
            }

            val measured = (frameWindowCount - 1) * 1_000_000.0 / spanUs
            frameWindowCount = 0
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
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                scope.launch(Dispatchers.Default) {
                    val snap = snapshotOnMain()
                    _playbackPhase.value = if (isPlaying) ReceiverPlaybackPhase.Playing else ReceiverPlaybackPhase.Paused
                    val evt = if (isPlaying) ReceiverEvent.Play(snap) else ReceiverEvent.Pause(snap)
                    _events.emit(evt)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                ReceiverDiagnostics.record("exo.stateChanged", "state=$playbackState playWhenReady=${exoPlayer?.playWhenReady}")
                when (playbackState) {
                    Player.STATE_ENDED -> {
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
                        _playbackPhase.value = ReceiverPlaybackPhase.Buffering
                    }
                    Player.STATE_READY -> {
                        if (autoPlayPending) {
                            autoPlayPending = false
                            player.playWhenReady = true
                        }
                        // Duration is only known once the source is prepared, and the format
                        // callback usually lands before that — so republish here or a VOD title
                        // would look like a live stream to the non-seamless-switch policy.
                        publishDiagnostics()
                        _playbackPhase.value = if (player.playWhenReady) {
                            ReceiverPlaybackPhase.Playing
                        } else {
                            ReceiverPlaybackPhase.Paused
                        }
                        scope.launch(Dispatchers.Main) {
                            val snap = snapshotOnMain()
                            _events.emit(
                                if (player.playWhenReady) ReceiverEvent.Play(snap) else ReceiverEvent.Pause(snap),
                            )
                        }
                    }
                    Player.STATE_IDLE -> {
                        // A codec failure drives the player to IDLE right after onPlayerError.
                        // Overwriting Error here dismissed the error overlay (and its external
                        // player buttons) straight back to the home screen.
                        val phase = _playbackPhase.value
                        if (phase !is ReceiverPlaybackPhase.Error) {
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

            override fun onRenderedFirstFrame() {
                ReceiverDiagnostics.record("exo.firstFrameRendered", "true")
            }

            override fun onCues(cueGroup: CueGroup) {
                _cues.value = cueGroup.cues
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
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
                scope.launch(Dispatchers.Main) {
                    val snap = snapshotOnMain()
                    _events.emit(if (playWhenReady) ReceiverEvent.Play(snap) else ReceiverEvent.Pause(snap))
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                // A freshly sideloaded subtitle is appended last. Select it explicitly — a DEFAULT
                // selection flag alone does not override the selector's language preferences.
                if (selectAddedSubtitle) {
                    val textCount = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.sumOf { it.length }
                    if (textCount > 0) {
                        selectAddedSubtitle = false
                        scope.launch { selectSubtitle(SubtitleSelection.Index(textCount - 1, enable = true)) }
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
                ReceiverDiagnostics.record("exoplayer.error", error.message ?: error::class.java.simpleName)
                val mime = decoderFailureMime(error)
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
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    scope.launch(Dispatchers.Default) {
                        val snap = snapshotOnMain()
                        val offset = (newPosition.positionMs - oldPosition.positionMs) / 1000.0
                        _events.emit(ReceiverEvent.Seek(snap, offset))
                    }
                }
            }

            override fun onVolumeChanged(volume: Float) {
                scope.launch(Dispatchers.Default) {
                    _events.emit(ReceiverEvent.VolumeChanged(snapshotOnMain()))
                }
            }
        })

        // A restored fallback level has to reach the NEW player, or the first frame after a
        // reopen is full-volume regardless of what was remembered.
        playerGainFallback?.let { player.volume = it }
        videoEffectsAttached = false
        exoPlayer = player
        playerView?.player = player
        attachedSurfaceHolder?.let { player.setVideoSurfaceHolder(it) }
            ?: attachedSurface?.let { player.setVideoSurface(it) }
    }

    /** Mirror every on-TV failure to the phone. The overlay keeps rendering locally; this frame is
     *  what lets the phone say "no DTS decoder" instead of "connection lost". */
    private fun emitPlaybackError(category: String, mimeType: String?, humanText: String, fatal: Boolean) {
        ReceiverDiagnostics.record(
            "notify.playbackError",
            "category=$category mime=${mimeType ?: "-"} fatal=$fatal",
        )
        scope.launch(Dispatchers.Default) {
            _events.emit(
                ReceiverEvent.Error(
                    snapshot = snapshotOnMain(),
                    category = category,
                    mimeType = mimeType,
                    humanText = humanText,
                    engine = "exo",
                    fatal = fatal,
                ),
            )
        }
    }

    private fun decoderFailureMime(error: PlaybackException): String? =
        generateSequence<Throwable>(error) { it.cause }
            .filterIsInstance<androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.DecoderInitializationException>()
            .firstOrNull()
            ?.mimeType

    /** Names the failing track so the overlay explains itself and the handoff buttons make sense. */
    private fun describePlayerError(error: PlaybackException): String {
        val mime = decoderFailureMime(error)
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
            "This TV can't decode $codecLabel. Open the stream in another player below, " +
                "or pick a release with AC3/EAC3/AAC audio."
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
        val p = exoPlayer
        if (p == null) {
            ReceiverSnapshot()
        } else {
            val dur = if (p.duration != C.TIME_UNSET) p.duration / 1000.0 else 0.0
            // Report the coalesced target, not the stale playhead: otherwise the phone's scrubber
            // (and the on-TV bar) snap back to where the scrub started for up to a window's worth
            // of polls, which reads as "my seek was ignored".
            val pos = (pendingSeekTargetMs ?: p.currentPosition) / 1000.0
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
            initExoPlayer()
            val player = exoPlayer ?: return@withContext Result.failure(IllegalStateException("ExoPlayer not initialized"))

            currentTitle = request.title
            currentSubtitle = request.subtitle
            isLiveStream = request.isLive
            lastOpenRequest = request
            autoPlayPending = true
            // A seek queued against the outgoing title must never land on the incoming one.
            cancelPendingSeek()
            // Never let the previous title's cues, sideloaded subtitles, or letterbox survive.
            externalSubtitles.clear()
            selectAddedSubtitle = false
            _cues.value = emptyList()
            _videoAspectRatio.value = 0f
            _playbackPhase.value = ReceiverPlaybackPhase.Opening(request.title)

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
            frameWindowStartMs = 0L
            frameWindowCount = 0
            _diagnostics.value = PlaybackDiagnostics()

            player.setMediaSource(mediaSource)
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
        pendingSeekTargetMs = targetMs
        when (val decision = SeekCoalescingPolicy.decide(lastSeekIssuedAtMillis, android.os.SystemClock.uptimeMillis())) {
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
        val target = pendingSeekTargetMs ?: return
        pendingSeekTargetMs = null
        lastSeekIssuedAtMillis = android.os.SystemClock.uptimeMillis()
        exoPlayer?.seekTo(target)
    }

    /** Drops a queued seek that belongs to media which is no longer playing. */
    private fun cancelPendingSeek() {
        mainHandler.removeCallbacks(pendingSeekRunnable)
        pendingSeekTargetMs = null
        lastSeekIssuedAtMillis = null
    }

    override suspend fun stop(): Result<Unit> = withContext(Dispatchers.Main) {
        try {
            val p = exoPlayer
            // Phase BEFORE p.stop(): media3 delivers the resulting STATE_IDLE callback
            // synchronously on this thread, and the IDLE handler treats a Playing→IDLE with no
            // recorded reason as an unexplained death — which fired a spurious engine failover
            // in the middle of an ordinary title switch (live, 2026-08-04 09:36).
            _playbackPhase.value = ReceiverPlaybackPhase.Stopped
            cancelPendingSeek()
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

            for (group in currentTracks.groups) {
                val type = group.type
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val isSelected = group.isTrackSelected(i)
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
        // The phone sends a sub it already matched to this title; tag it so it is distinguishable
        // from the container's own tracks in the list the phone reads back.
        const val EXTERNAL_SUBTITLE_LANGUAGE = "en"

        // Below 0.5x ExoPlayer's time-stretcher starts sounding like a fault; above 2x nobody is
        // watching, they are skimming, and the seek bar is the better tool for that.
        const val MIN_SPEED_MULTIPLIER = 0.25f
        const val MAX_SPEED_MULTIPLIER = 4.0f

        const val AUDIO_PREFERENCES_NAME = "receiver-audio"
        const val GAIN_FALLBACK_KEY = "gainFallback"

        const val SETTING_AUTOMATIC_AUDIO = "x4789.audio.automatic"
        const val SETTING_AUDIO_PASSTHROUGH = "audiooutput.passthrough"
    }

    override suspend fun selectAudio(index: Int): Result<Unit> = withContext(Dispatchers.Main) {
        try {
            val p = exoPlayer ?: return@withContext Result.failure(IllegalStateException("Player null"))
            val tracks = p.currentTracks
            var currIdx = 0
            for (group in tracks.groups) {
                if (group.type == C.TRACK_TYPE_AUDIO) {
                    for (i in 0 until group.length) {
                        if (currIdx == index) {
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
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun selectSubtitle(selection: SubtitleSelection): Result<Unit> = withContext(Dispatchers.Main) {
        try {
            val p = exoPlayer ?: return@withContext Result.failure(IllegalStateException("Player null"))
            val builder = p.trackSelectionParameters.buildUpon()
            when (selection) {
                is SubtitleSelection.Off -> {
                    builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                }
                is SubtitleSelection.On, is SubtitleSelection.Latest -> {
                    builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                }
                is SubtitleSelection.Index -> {
                    builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    // The inner `break` used to leave `currIdx` un-incremented on a match, so every
                    // later text group matched too and overwrote the override — picking any track
                    // landed on the last one. Resolve the target first, then apply it once.
                    val target = textTrackAt(p.currentTracks, selection.value)
                        ?: return@withContext Result.failure(
                            IllegalArgumentException("No subtitle track at index ${selection.value}"),
                        )
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
     * Builds the playing source from the original open request so every rebuild (an added external
     * subtitle) keeps the stream's authenticated data source, headers, and sideloaded tracks.
     */
    private fun buildMediaSource(request: OpenMediaRequest): androidx.media3.exoplayer.source.MediaSource {
        val httpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
        if (request.headers.isNotEmpty()) {
            httpDataSourceFactory.setDefaultRequestProperties(request.headers)
        }
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(request.url))
            .setSubtitleConfigurations(externalSubtitles.toList())
            .build()
        return DefaultMediaSourceFactory(httpDataSourceFactory).createMediaSource(mediaItem)
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

    override suspend fun addSubtitle(url: String): Result<Unit> = withContext(Dispatchers.Main) {
        try {
            val p = exoPlayer ?: return@withContext Result.failure(IllegalStateException("Player null"))
            val request = lastOpenRequest
                ?: return@withContext Result.failure(IllegalStateException("No stream is open"))

            externalSubtitles += MediaItem.SubtitleConfiguration.Builder(Uri.parse(url))
                .setMimeType(subtitleMimeType(url))
                .setLanguage(EXTERNAL_SUBTITLE_LANGUAGE)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()

            // Rebuild from the ORIGINAL request, not `currentMediaItem`: the media item derived from
            // a MediaSource carries none of the authenticated OkHttp factory or request headers, so
            // re-preparing from it dropped the stream's credentials and the sideload failed.
            val resumeAt = p.currentPosition
            val wasPlaying = p.playWhenReady
            selectAddedSubtitle = true
            autoPlayPending = false
            p.setMediaSource(buildMediaSource(request), resumeAt)
            p.playWhenReady = wasPlaying
            p.prepare()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun subtitleMimeType(url: String): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".vtt") -> androidx.media3.common.MimeTypes.TEXT_VTT
            path.endsWith(".ass") || path.endsWith(".ssa") -> androidx.media3.common.MimeTypes.TEXT_SSA
            path.endsWith(".ttml") || path.endsWith(".xml") -> androidx.media3.common.MimeTypes.APPLICATION_TTML
            else -> androidx.media3.common.MimeTypes.APPLICATION_SUBRIP
        }
    }

    override suspend fun stageNowPlaying(title: String?, subtitle: String?, isLive: Boolean) {
        currentTitle = title
        currentSubtitle = subtitle
        isLiveStream = isLive
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
    private fun audioCapabilities(route: ExoAudioRoute): AudioCapabilities? = when (route) {
        is ExoAudioRoute.Automatic -> null
        is ExoAudioRoute.Constrained -> {
            val platform = AudioCapabilities.getCapabilities(applicationContext)
            val encodings = (
                listOf(C.ENCODING_PCM_16BIT) + ExoAudioRoutePolicy.encodings(route.codecs)
                ).distinct().toIntArray()
            AudioCapabilities(encodings, platform.maxChannelCount)
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

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { applicationContext.contentResolver.unregisterContentObserver(volumeObserver) }
            mainHandler.post {
                cancelPendingSeek()
                exoPlayer?.release()
                exoPlayer = null
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
    // NextRenderersFactory is a drop-in DefaultRenderersFactory that also registers the ffmpeg
    // software decoders (AC3/EAC3/DTS/DTS-HD/TrueHD/MLP). This is the Kodi split, and the same
    // library Just Player uses: video stays on the hardware MediaCodec, audio the chip has no
    // decoder for is decoded on the CPU instead of killing the title. Costs ~1% CPU.
) : io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory(context) {

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
                .setMinPcmBufferDurationUs(1_500_000)
                .setMaxPcmBufferDurationUs(1_500_000)
                .setPassthroughBufferDurationUs(1_000_000)
                .build(),
        )
        .build()
}
