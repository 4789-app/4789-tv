package com.fourseveneightnine.tv.player

import android.content.Context
import android.view.Surface
import android.view.SurfaceHolder
import com.fourseveneightnine.tv.protocol.OpenMediaRequest
import com.fourseveneightnine.tv.protocol.ReceiverAudioProfile
import com.fourseveneightnine.tv.protocol.ReceiverController
import com.fourseveneightnine.tv.protocol.ReceiverEvent
import com.fourseveneightnine.tv.protocol.ReceiverSnapshot
import com.fourseveneightnine.tv.protocol.ReceiverTracks
import com.fourseveneightnine.tv.protocol.ReceiverPreparationStage
import com.fourseveneightnine.tv.protocol.SeekCommand
import com.fourseveneightnine.tv.protocol.SubtitleSelection
import com.fourseveneightnine.tv.player.upscale.UpscaleMode
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A [ReceiverController] whose engine can be replaced while the process lives. The RPC dispatcher,
 * the transport's event fan-out, and the Activity's observers all hold THIS object; its flows are
 * proxy-owned and simply re-collect whichever inner controller is current, so an engine swap is
 * invisible to everything above it — transport, ports, and mDNS survive.
 *
 * It is also where automatic Exo→mpv failover lives: a fatal codec/unknown error from ExoPlayer
 * (no DTS/TrueHD/Dolby-Vision decoder on Fire OS) rewrites the outgoing X4789.OnPlaybackError to
 * willRetry=true, rebuilds onto libmpv (software decode — its config already pins hwdec=no, the
 * 0.1.16 deadlock never engages), reopens the same media, and resumes near the failure position.
 * One attempt per open ([EngineFailoverPolicy]); a second failure surfaces to the phone.
 */
class SwappableReceiverController(
    private val applicationContext: Context,
    initialEngine: ReceiverEngine,
) : ReceiverController {

    private val proxyScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val swapMutex = Mutex()
    private val failoverAttemptedForOpen = AtomicBoolean(false)

    @Volatile var engine: ReceiverEngine = initialEngine
        private set

    @Volatile private var inner: ReceiverController = create(initialEngine)

    private val _events = MutableSharedFlow<ReceiverEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<ReceiverEvent> = _events.asSharedFlow()

    private val _surfaceReady = MutableStateFlow(false)
    override val surfaceReady: Flow<Boolean> = _surfaceReady.asStateFlow()

    private val _playbackPhase = MutableStateFlow<ReceiverPlaybackPhase>(ReceiverPlaybackPhase.Idle)
    override val playbackPhase: Flow<ReceiverPlaybackPhase> = _playbackPhase.asStateFlow()

    private val _diagnostics = MutableStateFlow(PlaybackDiagnostics())
    override val diagnostics: Flow<PlaybackDiagnostics> = _diagnostics.asStateFlow()

    // Exo-specific side channels the Activity draws with. The Activity used to reach them by
    // downcasting the controller — against this proxy that cast returns null and subtitles
    // silently vanish (live regression, 2026-08-04). mpv renders its own subtitles in-video, so
    // these simply reset when the mpv engine is current.
    private val _cues = MutableStateFlow<List<androidx.media3.common.text.Cue>>(emptyList())
    val cues: kotlinx.coroutines.flow.StateFlow<List<androidx.media3.common.text.Cue>> = _cues.asStateFlow()

    private val _videoAspectRatio = MutableStateFlow(0f)
    val videoAspectRatio: kotlinx.coroutines.flow.StateFlow<Float> = _videoAspectRatio.asStateFlow()

    private val _subtitleStyle = MutableStateFlow(ReceiverSubtitleStyle())
    val subtitleStyle: kotlinx.coroutines.flow.StateFlow<ReceiverSubtitleStyle> = _subtitleStyle.asStateFlow()

    // The last surface the Activity handed us, so a swapped-in engine can re-attach without any
    // Activity involvement. Holder preferred (Exo's holder path carries resize callbacks).
    @Volatile private var lastHolder: SurfaceHolder? = null
    @Volatile private var lastSurface: Surface? = null
    @Volatile private var lastWidth = 0
    @Volatile private var lastHeight = 0

    private var bridgeJobs: List<Job> = bind(inner)

    private fun create(target: ReceiverEngine): ReceiverController = when (target) {
        ReceiverEngine.Exo -> ExoReceiverController(applicationContext)
        ReceiverEngine.Mpv -> MpvReceiverController(applicationContext)
    }

    private fun bind(c: ReceiverController): List<Job> {
        val jobs = mutableListOf(
            proxyScope.launch { c.events.collect { handleInnerEvent(it) } },
            proxyScope.launch { c.surfaceReady.collect { _surfaceReady.value = it } },
            proxyScope.launch { c.playbackPhase.collect { _playbackPhase.value = it } },
            proxyScope.launch { c.diagnostics.collect { _diagnostics.value = it } },
        )
        if (c is ExoReceiverController) {
            jobs += proxyScope.launch { c.cues.collect { _cues.value = it } }
            jobs += proxyScope.launch { c.videoAspectRatio.collect { _videoAspectRatio.value = it } }
            jobs += proxyScope.launch { c.subtitleStyle.collect { _subtitleStyle.value = it } }
        } else {
            // mpv burns subtitles into the video and manages its own aspect; stale Exo cues must
            // not linger over the new engine's frames.
            _cues.value = emptyList()
            _videoAspectRatio.value = 0f
        }
        return jobs
    }

    private suspend fun handleInnerEvent(event: ReceiverEvent) {
        if (event is ReceiverEvent.Error) {
            val media = inner.lastOpenMedia()
            val decision = EngineFailoverPolicy.decide(
                category = event.category,
                fatal = event.fatal,
                engine = engine,
                alreadyAttempted = failoverAttemptedForOpen.get(),
                mediaKnown = media != null,
            )
            if (decision == EngineFailoverDecision.RetryWithMpv && media != null) {
                failoverAttemptedForOpen.set(true)
                _events.emit(event.copy(willRetry = true, retryEngine = "mpv"))
                val resume = event.snapshot.positionSeconds
                proxyScope.launch { performFailover(media, resume, event.category) }
                return
            }
        }
        _events.emit(event)
    }

    private suspend fun performFailover(media: OpenMediaRequest, resumeSeconds: Double, reason: String) {
        swapMutex.withLock {
            ReceiverDiagnostics.record(
                "engine.failover",
                "from=${engine.name.lowercase(Locale.US)} to=mpv reason=$reason " +
                    "pos=${String.format(Locale.US, "%.1f", resumeSeconds)}",
            )
            swapLocked(ReceiverEngine.Mpv)
            reopenWithResume(media, resumeSeconds, diagnosticKey = "engine.failover.openFailed")
        }
    }

    /**
     * User-requested live engine switch (X4789.SetSessionEngine): wrong colors on a Dolby-Vision
     * stream disguised as plain HEVC decode "fine" on hardware and trip no error — only human
     * eyes catch it. Reopens the current title at its position on the other engine.
     */
    suspend fun requestEngine(target: ReceiverEngine): Boolean = swapMutex.withLock {
        if (target == engine) return@withLock true
        val media = inner.lastOpenMedia()
        val snapshot = runCatching { inner.snapshot() }.getOrNull()
        val position = snapshot?.positionSeconds ?: 0.0
        val active = snapshot?.active == true
        ReceiverDiagnostics.record(
            "engine.switch",
            "to=${target.name.lowercase(Locale.US)} active=$active " +
                "pos=${String.format(Locale.US, "%.1f", position)}",
        )
        swapLocked(target)
        if (active && media != null) {
            failoverAttemptedForOpen.set(false)
            reopenWithResume(media, position, diagnosticKey = "engine.switch.openFailed")
        } else {
            true
        }
    }

    /** Shared reopen: wait for the fresh engine's surface, open, then resume past the intro. */
    private suspend fun reopenWithResume(
        media: OpenMediaRequest,
        resumeSeconds: Double,
        diagnosticKey: String,
    ): Boolean {
        // The fresh engine attaches its surface on its own worker thread; opening before the
        // attach completes is rejected with "surface is not ready" (seen live 2026-08-04).
        val surfaceUp = withTimeoutOrNull(SURFACE_WAIT_MILLIS) { _surfaceReady.first { it } }
        if (surfaceUp == null) {
            ReceiverDiagnostics.record(diagnosticKey, "surface never became ready")
            return false
        }
        return inner.open(media).onSuccess {
            if (resumeSeconds > RESUME_MIN_SECONDS) {
                proxyScope.launch {
                    val landed = withTimeoutOrNull(RESUME_WAIT_MILLIS) {
                        _playbackPhase.first {
                            it is ReceiverPlaybackPhase.Playing || it is ReceiverPlaybackPhase.Paused
                        }
                    }
                    if (landed != null) inner.seek(SeekCommand.AbsoluteSeconds(resumeSeconds))
                }
            }
        }.onFailure {
            ReceiverDiagnostics.record(diagnosticKey, it.message ?: "?")
        }.isSuccess
    }

    private fun swapLocked(target: ReceiverEngine) {
        val old = inner
        bridgeJobs.forEach(Job::cancel)
        runCatching { old.detachSurface() }
        runCatching { old.close() }
        val fresh = create(target)
        inner = fresh
        engine = target
        bridgeJobs = bind(fresh)
        reattachSurfaceTo(fresh)
    }

    private fun reattachSurfaceTo(c: ReceiverController) {
        val holder = lastHolder
        val surface = lastSurface
        when {
            holder != null -> attach(c, holder)
            surface != null -> c.attachSurface(surface, lastWidth, lastHeight)
        }
    }

    private fun attach(c: ReceiverController, holder: SurfaceHolder) {
        (c as? ExoReceiverController)?.attachSurfaceHolder(holder)
            ?: c.attachSurface(holder.surface, lastWidth, lastHeight)
    }

    /** Holder-aware attach so Exo keeps its resize callbacks; other engines get the raw Surface. */
    fun attachSurfaceHolder(holder: SurfaceHolder, width: Int, height: Int) {
        lastHolder = holder
        lastSurface = null
        lastWidth = width
        lastHeight = height
        attach(inner, holder)
    }

    override fun attachSurface(surface: Surface, width: Int, height: Int) {
        lastSurface = surface
        lastHolder = null
        lastWidth = width
        lastHeight = height
        inner.attachSurface(surface, width, height)
    }

    override fun detachSurface() {
        lastHolder = null
        lastSurface = null
        inner.detachSurface()
    }

    override fun updateSurfaceSize(width: Int, height: Int) {
        lastWidth = width
        lastHeight = height
        inner.updateSurfaceSize(width, height)
    }

    override fun upscaleMode(): UpscaleMode = inner.upscaleMode()

    override fun setUpscaleMode(mode: UpscaleMode) = inner.setUpscaleMode(mode)

    override suspend fun open(request: OpenMediaRequest): Result<Unit> {
        failoverAttemptedForOpen.set(false)
        return inner.open(request)
    }

    override suspend fun snapshot(): ReceiverSnapshot = inner.snapshot()
    override suspend fun playPause(): Result<Int> = inner.playPause()
    override suspend fun seek(command: SeekCommand): Result<Unit> = inner.seek(command)
    override suspend fun stop(): Result<Unit> = inner.stop()
    override suspend fun setSpeed(speed: Int): Result<Int> = inner.setSpeed(speed)
    override suspend fun setSpeedMultiplier(speed: Float): Result<Float> =
        inner.setSpeedMultiplier(speed)
    override suspend fun setVolume(volume: Int): Result<Int> = inner.setVolume(volume)
    override suspend fun tracks(): Result<ReceiverTracks> = inner.tracks()
    override suspend fun selectAudio(index: Int): Result<Unit> = inner.selectAudio(index)
    override suspend fun selectSubtitle(selection: SubtitleSelection): Result<Unit> =
        inner.selectSubtitle(selection)
    override suspend fun addSubtitle(url: String): Result<Unit> = inner.addSubtitle(url)
    override suspend fun stageNowPlaying(
        title: String?,
        subtitle: String?,
        isLive: Boolean,
        preparationStage: ReceiverPreparationStage?,
        castId: String?,
    ) = inner.stageNowPlaying(title, subtitle, isLive, preparationStage, castId)
    override suspend fun stageSubtitleStyle(params: JsonObject) = inner.stageSubtitleStyle(params)
    override suspend fun applySetting(name: String, value: JsonPrimitive): Result<Boolean> =
        inner.applySetting(name, value)
    override suspend fun applyAudioProfile(profile: ReceiverAudioProfile): Result<Boolean> =
        inner.applyAudioProfile(profile)
    override suspend fun executeAction(action: String): Result<Unit> = inner.executeAction(action)
    override suspend fun announceExternalHandoff(playerLabel: String, playerPackage: String) =
        inner.announceExternalHandoff(playerLabel, playerPackage)
    override fun handleMemoryPressure(level: Int) = inner.handleMemoryPressure(level)
    override fun consumePendingSoftwareOpen(): OpenMediaRequest? = inner.consumePendingSoftwareOpen()
    override fun lastOpenMedia(): OpenMediaRequest? = inner.lastOpenMedia()
    override suspend fun openWithSoftware(request: OpenMediaRequest): Result<Unit> =
        inner.openWithSoftware(request)
    override suspend fun retryLastOpen(): Result<Unit> = inner.retryLastOpen()
    override fun refreshAudioRoute() = inner.refreshAudioRoute()

    override fun close() {
        bridgeJobs.forEach(Job::cancel)
        proxyScope.cancel()
        runCatching { inner.close() }
    }

    private companion object {
        /** Don't bother resuming inside the first few seconds — a fresh open lands there anyway. */
        const val RESUME_MIN_SECONDS = 5.0
        const val RESUME_WAIT_MILLIS = 20_000L
        const val SURFACE_WAIT_MILLIS = 10_000L
    }
}
