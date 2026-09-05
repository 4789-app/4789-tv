package com.fourseveneightnine.tv.player

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.view.Surface
import com.fourseveneightnine.tv.protocol.OpenMediaRequest
import com.fourseveneightnine.tv.protocol.ReceiverAudioProfile
import com.fourseveneightnine.tv.protocol.ReceiverController
import com.fourseveneightnine.tv.protocol.ReceiverEvent
import com.fourseveneightnine.tv.protocol.ReceiverSnapshot
import com.fourseveneightnine.tv.protocol.ReceiverTrack
import com.fourseveneightnine.tv.protocol.ReceiverTracks
import com.fourseveneightnine.tv.protocol.ReceiverPreparationStage
import com.fourseveneightnine.tv.protocol.SeekCommand
import com.fourseveneightnine.tv.protocol.SubtitleSelection
import com.fourseveneightnine.tv.transport.MpvStreamRelay
import com.fourseveneightnine.tv.transport.ReceiverPorts
import com.fourseveneightnine.tv.player.upscale.UpscaleMode
import com.fourseveneightnine.tv.player.upscale.UpscalePolicy
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics
import dev.jdtech.mpv.MPVLib
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

sealed interface ReceiverPlaybackPhase {
    data object Idle : ReceiverPlaybackPhase
    data class Opening(
        val title: String?,
        val stage: ReceiverPreparationStage = ReceiverPreparationStage.PREPARING,
        val castId: String? = null,
    ) : ReceiverPlaybackPhase
    data object Playing : ReceiverPlaybackPhase
    data object Paused : ReceiverPlaybackPhase
    data object Buffering : ReceiverPlaybackPhase

    /**
     * The film ran to its own end.
     *
     * Distinct from [Stopped], which is what a viewer or the phone asked for. Reaching the end is a
     * decision point — replay, next episode, or leave — and it used to fall through to the generic
     * "ready for your iPhone" screen, which acknowledged nothing.
     */
    data object Ended : ReceiverPlaybackPhase
    data object Stopped : ReceiverPlaybackPhase
    data class Error(val message: String) : ReceiverPlaybackPhase
}

internal object ReceiverPlaybackPhasePolicy {
    /**
     * Does this phase mean a picture is on the screen?
     *
     * The receiver keeps a safety net that draws the home screen whenever nothing else is showing,
     * because a hidden overlay with no picture behind it is a black screen that swallows the D-pad.
     * The net used to answer this from `playbackActive` alone, which is fed asynchronously from the
     * controller's event stream — so on a perfectly healthy start it was still false for a moment
     * after the phase had already gone to `Playing` and the overlay had already been hidden. The net
     * fired and drew the home screen OVER a film that had just started. Seen live on the onn 4K Pro,
     * 2026-08-28 14:43:51.524: `overlay.rescued phase=Playing startup=Ready`.
     *
     * These four phases are only ever set once a frame has actually been rendered for the current
     * title, so each one is direct evidence of a picture. `Stopped`, `Ended`, `Error` and `Idle` are
     * not, and the net must still fire for them — that is the black screen it exists to catch.
     */
    fun showsPicture(phase: ReceiverPlaybackPhase): Boolean = when (phase) {
        is ReceiverPlaybackPhase.Playing,
        is ReceiverPlaybackPhase.Paused,
        is ReceiverPlaybackPhase.Buffering,
        -> true

        is ReceiverPlaybackPhase.Opening,
        is ReceiverPlaybackPhase.Idle,
        is ReceiverPlaybackPhase.Ended,
        is ReceiverPlaybackPhase.Stopped,
        is ReceiverPlaybackPhase.Error,
        -> false
    }

    fun loaded(speed: Int): ReceiverPlaybackPhase =
        if (speed == 0) ReceiverPlaybackPhase.Paused else ReceiverPlaybackPhase.Playing

    fun ended(prior: ReceiverPlaybackPhase, openErrorMessage: String): ReceiverPlaybackPhase =
        when (prior) {
            is ReceiverPlaybackPhase.Opening -> ReceiverPlaybackPhase.Error(openErrorMessage)
            is ReceiverPlaybackPhase.Error -> prior
            else -> ReceiverPlaybackPhase.Stopped
        }

    fun active(pausedForCache: Boolean, speed: Int): ReceiverPlaybackPhase =
        when {
            pausedForCache -> ReceiverPlaybackPhase.Buffering
            speed == 0 -> ReceiverPlaybackPhase.Paused
            else -> ReceiverPlaybackPhase.Playing
        }

    fun preparation(
        prior: ReceiverPlaybackPhase,
        title: String?,
        stage: ReceiverPreparationStage,
        castId: String?,
        activeCastId: String?,
    ): ReceiverPlaybackPhase? = when (stage) {
        ReceiverPreparationStage.RESOLVING,
        ReceiverPreparationStage.PREPARING -> {
            val sameCast = castId == null || activeCastId == null || castId == activeCastId
            val activePlayback = prior is ReceiverPlaybackPhase.Playing ||
                prior is ReceiverPlaybackPhase.Paused || prior is ReceiverPlaybackPhase.Ended
            val wouldRegress = prior is ReceiverPlaybackPhase.Opening && sameCast &&
                prior.stage == ReceiverPreparationStage.PREPARING &&
                stage == ReceiverPreparationStage.RESOLVING
            if ((activePlayback && sameCast) || wouldRegress) null
            else ReceiverPlaybackPhase.Opening(title, stage, castId)
        }

        ReceiverPreparationStage.FAILED -> {
            if (prior is ReceiverPlaybackPhase.Error ||
                !terminalBelongsToActiveCast(prior, castId, activeCastId)
            ) null
            else ReceiverPlaybackPhase.Error("The phone could not prepare this stream. Try it again or choose another source.")
        }

        ReceiverPreparationStage.CANCELLED -> {
            if (prior is ReceiverPlaybackPhase.Error ||
                !terminalBelongsToActiveCast(prior, castId, activeCastId)
            ) null
            else ReceiverPlaybackPhase.Stopped
        }
    }

    private fun terminalBelongsToActiveCast(
        prior: ReceiverPlaybackPhase,
        incomingCastId: String?,
        activeCastId: String?,
    ): Boolean {
        if (prior is ReceiverPlaybackPhase.Playing || prior is ReceiverPlaybackPhase.Paused ||
            prior is ReceiverPlaybackPhase.Ended
        ) return false
        return incomingCastId == null || activeCastId == null || incomingCastId == activeCastId
    }
}

/**
 * A single-player Android TV receiver backed by libmpv.
 *
 * MPVLib lifecycle and property access happens on [nativeDispatcher]. Commands that can wedge the
 * AAR's blocking JNI bridge use libmpv JSON IPC instead. Property callbacks only update lock-free
 * state or queue a serialized refresh. The independent watchdog remains responsive even when a
 * native lifecycle/property call stalls.
 */
class MpvReceiverController(
    context: Context,
) : ReceiverController, Closeable {
    private val applicationContext = context.applicationContext
    private val controllerJob = SupervisorJob()
    private val nativeDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "4789-mpv").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val surfaceReaper = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "4789-mpv-surface-reaper").apply { isDaemon = true }
    }
    private val nativeReaper = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "4789-mpv-reaper").apply { isDaemon = true }
    }
    private val controllerScope = CoroutineScope(controllerJob + nativeDispatcher)
    private val watchdogJob = SupervisorJob()
    private val watchdogScope = CoroutineScope(watchdogJob + Dispatchers.Default)

    private val closed = AtomicBoolean(false)
    private val playerTerminated = AtomicBoolean(false)
    private val mediaLoaded = AtomicBoolean(false)
    private val surfaceGeneration = AtomicLong(0)
    private val surfaceBinding = AtomicReference<SurfaceBinding?>(null)
    private val snapshotStore = MpvSnapshotStore()
    private val pendingSeek = AtomicReference<PendingSeek?>(null)
    private val stagedNowPlaying = AtomicReference(StagedNowPlaying())
    private val stagedSubtitleStyle = AtomicReference<JsonObject?>(null)
    private val lastSubtitleId = AtomicReference<Int?>(null)
    private val passthroughRequested = AtomicBoolean(false)
    private val automaticAudioRouting = AtomicBoolean(true)
    private val passthroughCodecRequests = ConcurrentHashMap<String, Boolean>()
    private val appliedAudioSpdif = AtomicReference<String?>(null)
    private val lastOpenRequest = AtomicReference<OpenMediaRequest?>(null)
    private val commandClient = AtomicReference<MpvIpcCommandClient?>(null)
    /** Serializes the complete header + loadfile transaction across every RPC transport. */
    private val openTransactionMutex = Mutex()
    /** Short ownership transitions stay synchronized without blocking the native/JNI executor. */
    private val openOwnershipLock = Any()
    private val activeOpenGeneration = AtomicLong(0)
    /** A failed generation marks the next still-current open for a clean native instance. */
    private val forceFreshPlayerAfterGeneration = AtomicLong(-1)
    /** The path query validates native events because MPV callbacks carry no request id. */
    private val activeOpenURL = AtomicReference<String?>(null)
    private val openingTimeoutJob = AtomicReference<Job?>(null)
    private val openingTimeoutGeneration = AtomicLong(-1)
    /** A wedged native destroy must never turn every subsequent title into another retained MPV. */
    private val retiredPlayerCount = AtomicInteger(0)
    /** Elapsed realtime at which the oldest currently pending native teardown began. */
    private val oldestRetirementStartedAtMillis = AtomicLong(0)
    /** Process restart is a last-resort boundary for a genuinely stuck native teardown. */
    private val processRecoveryRequested = AtomicBoolean(false)
    /** Replacements get a new callback gate; only the initial open can reuse this instance. */
    private val playerPresent = AtomicBoolean(false)
    /** When set, freshly created players configure libmpv with hwdec=no (software decode). */
    private val softwareDecodeOverride = AtomicBoolean(false)
    /** One hardware probe per open; a stalled MediaCodec init falls back instead of failing. */
    private val hardwareFallbackScheduled = AtomicBoolean(false)

    private val _events = MutableSharedFlow<ReceiverEvent>(
        extraBufferCapacity = EVENT_BUFFER_SIZE,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<ReceiverEvent> = _events.asSharedFlow()

    private val _surfaceReady = MutableStateFlow(false)
    override val surfaceReady: StateFlow<Boolean> = _surfaceReady.asStateFlow()

    private val _diagnostics = MutableStateFlow(PlaybackDiagnostics())
    override val diagnostics: StateFlow<PlaybackDiagnostics> = _diagnostics.asStateFlow()

    private val _playbackPhase = MutableStateFlow<ReceiverPlaybackPhase>(ReceiverPlaybackPhase.Idle)
    override val playbackPhase: StateFlow<ReceiverPlaybackPhase> = _playbackPhase.asStateFlow()

    // Accessed only from nativeDispatcher after construction.
    private var player: MPVLib? = null
    private val callbackLock = Any()
    private var playerCallbacks: PlayerCallbacks? = null
    private var pendingSurfaceRelease: CountDownLatch? = null
    /** Native-thread-only generation of the Surface currently attached to [player]. */
    private var attachedPlayerSurfaceGeneration = -1L

    /** Each native instance gets its own gate so a retired stream can never update a new title. */
    private class PlayerCallbacks(owner: MpvReceiverController) {
        private val gate = AtomicBoolean(true)
        val observer: MPVLib.EventObserver = GatedEventObserver(owner, this)
        val logObserver: MPVLib.LogObserver = GatedLogObserver(owner, this)

        fun isActive(): Boolean = gate.get()

        fun deactivate(owner: MpvReceiverController) {
            synchronized(owner.callbackLock) { gate.set(false) }
        }
    }

    private class GatedEventObserver(
        private val owner: MpvReceiverController,
        private val callbacks: PlayerCallbacks,
    ) : MPVLib.EventObserver {
        override fun eventProperty(property: String) = Unit
        override fun eventProperty(property: String, value: Long) = owner.withCallback(callbacks) {
            owner.handleProperty(property, value)
        }
        override fun eventProperty(property: String, value: Double) = owner.withCallback(callbacks) {
            owner.handleProperty(property, value)
        }
        override fun eventProperty(property: String, value: Boolean) = owner.withCallback(callbacks) {
            owner.handleProperty(property, value)
        }
        override fun eventProperty(property: String, value: String) = Unit
        override fun event(eventId: Int) = owner.withCallback(callbacks) { owner.handleEvent(eventId) }
    }

    private class GatedLogObserver(
        private val owner: MpvReceiverController,
        private val callbacks: PlayerCallbacks,
    ) : MPVLib.LogObserver {
        override fun logMessage(prefix: String, level: Int, text: String) = owner.withCallback(callbacks) {
            if (level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_WARN) {
                ReceiverDiagnostics.record(
                    "mpv.native",
                    "level=$level prefix=${MpvNativeLogPolicy.safe(prefix)} message=${MpvNativeLogPolicy.safe(text)}",
                )
            }
        }
    }

    private inline fun withCallback(callbacks: PlayerCallbacks, block: () -> Unit) {
        synchronized(callbackLock) {
            // The native callback does not carry a player pointer. Identity is therefore part of
            // the gate: a late event from a retired MPV instance must not change the replacement
            // title's state even if the old gate was still true when the callback began.
            if (playerCallbacks === callbacks && callbacks.isActive()) block()
        }
    }

    private fun handleProperty(property: String, value: Long) {
        when (property) {
            PROPERTY_TIME_POSITION -> updateState { MpvSnapshotReducer.position(it, value.toDouble()) }
            PROPERTY_DURATION -> updateState { MpvSnapshotReducer.duration(it, value.toDouble()) }
            PROPERTY_VOLUME -> updateState { MpvSnapshotReducer.volume(it, value.toDouble()) }
        }
    }

    private fun handleProperty(property: String, value: Double) {
        when (property) {
            PROPERTY_TIME_POSITION -> updateState { MpvSnapshotReducer.position(it, value) }
            PROPERTY_DURATION -> updateState { MpvSnapshotReducer.duration(it, value) }
            PROPERTY_SPEED -> updateState { MpvSnapshotReducer.playbackRate(it, value) }
            PROPERTY_VOLUME -> updateState { MpvSnapshotReducer.volume(it, value) }
        }
    }

    private fun handleProperty(property: String, value: Boolean) {
        when (property) {
            PROPERTY_IDLE_ACTIVE -> {
                if (value) mediaLoaded.set(false)
                updateState {
                    MpvSnapshotReducer.active(
                        it,
                        active = MpvLoadStatePolicy.isPlayable(idleActive = value, fileLoaded = mediaLoaded.get()),
                    )
                }
            }
            PROPERTY_PAUSE -> updateState { MpvSnapshotReducer.paused(it, value) }
            PROPERTY_MUTED -> updateState { MpvSnapshotReducer.muted(it, value) }
        }
    }

    private fun handleEvent(eventId: Int) {
        when (eventId) {
            MPVLib.MpvEvent.MPV_EVENT_START_FILE -> {
                mediaLoaded.set(false)
                ReceiverDiagnostics.record("mpv.event.startFile")
            }

            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                validateCurrentFileEvent(loaded = true)
            }

            MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                validateCurrentFileEvent(loaded = false)
            }

            MPVLib.MpvEvent.MPV_EVENT_SHUTDOWN -> {
                mediaLoaded.set(false)
                updateState { MpvSnapshotReducer.active(it, active = false) }
                stopDiagnosticsPolling()
                if (_playbackPhase.value !is ReceiverPlaybackPhase.Stopped) {
                    _playbackPhase.value = ReceiverPlaybackPhase.Error(PLAYBACK_ENGINE_ERROR_MESSAGE)
                    emitPlaybackError(PlaybackErrorTaxonomy.UNKNOWN, PLAYBACK_ENGINE_ERROR_MESSAGE)
                }
                invalidateTerminatedPlayer()
            }

            MPVLib.MpvEvent.MPV_EVENT_SEEK -> refreshSeekFromPlayer()
        }
    }

    /** MPV events have no request id. Confirm the current native path over JSON IPC before mutating
     * state, so a delayed event from the previous title cannot cancel this title's watchdog. The
     * idle flag is checked too: a stale FILE_LOADED cannot make a replacement appear ready while
     * its new path is only queued, and opening END_FILE is left to the independent watchdog. */
    private fun validateCurrentFileEvent(loaded: Boolean) {
        val generation = activeOpenGeneration.get()
        val expectedURL = activeOpenURL.get() ?: return
        val client = commandClient.get() ?: return
        watchdogScope.launch {
            val currentFile = withContext(Dispatchers.IO) {
                val pathResult = client.request(buildJsonArray {
                    add(JsonPrimitive(COMMAND_GET_PROPERTY))
                    add(JsonPrimitive(PROPERTY_PATH))
                })
                val actualURL = pathResult.getOrNull()?.jsonPrimitive?.contentOrNull
                val idleResult = client.request(buildJsonArray {
                    add(JsonPrimitive(COMMAND_GET_PROPERTY))
                    add(JsonPrimitive(PROPERTY_IDLE_ACTIVE))
                })
                val idleActive = idleResult.getOrNull()?.jsonPrimitive?.booleanOrNull
                if (pathResult.isFailure || idleResult.isFailure) {
                    ReceiverDiagnostics.record(
                        "mpv.event.validationUnavailable",
                        "loaded=$loaded path=${actualURL != null} idle=${idleActive != null}",
                    )
                }
                actualURL to idleActive
            }
            val pathConfirmed = currentFile.first != null &&
                MpvPathPolicy.matches(expectedURL, currentFile.first)
            val pathMismatch = currentFile.first != null && !pathConfirmed
            if (pathMismatch) {
                ReceiverDiagnostics.record(
                    "mpv.event.pathMismatch",
                    "loaded=$loaded expected=${MpvNativeLogPolicy.safe(expectedURL)} " +
                        "actual=${MpvNativeLogPolicy.safe(currentFile.first.orEmpty())}",
                )
                return@launch
            }
            synchronized(openOwnershipLock) {
                if (generation != activeOpenGeneration.get() ||
                    expectedURL != activeOpenURL.get()
                ) return@synchronized

                if (loaded) {
                    // IPC property reads are a best-effort confirmation on Fire OS. A failed read
                    // must never strand a genuinely loaded file in the 30-second opening window:
                    // the callback gate already scopes events to this native instance, so accept
                    // the event when the read is unavailable. Only an explicit idle=true (with a
                    // confirmed path) means the file is still queued rather than loaded.
                    if (pathConfirmed && currentFile.second == true) {
                        ReceiverDiagnostics.record(
                            "mpv.event.idleWhileLoaded",
                            "generation=$generation",
                        )
                        return@synchronized
                    }
                    cancelOpeningTimeout(generation)
                    mediaLoaded.set(true)
                    ReceiverDiagnostics.record("mpv.event.fileLoaded")
                    updateState { MpvSnapshotReducer.active(it, active = true) }
                    _playbackPhase.value = ReceiverPlaybackPhasePolicy.loaded(snapshotStore.snapshot().speed)
                    controllerScope.launch {
                        applyColorSafePolicyForLoadedFile(generation)
                        recordAudioOutputParams()
                    }
                } else {
                    if (_playbackPhase.value is ReceiverPlaybackPhase.Opening ||
                        (pathConfirmed && currentFile.second != true)
                    ) return@synchronized
                    cancelOpeningTimeout(generation)
                    val priorPhase = _playbackPhase.value
                    mediaLoaded.set(false)
                    ReceiverDiagnostics.record("mpv.event.endFile")
                    updateState { MpvSnapshotReducer.active(it, active = false) }
                    stopDiagnosticsPolling()
                    val endedPhase = ReceiverPlaybackPhasePolicy.ended(
                        priorPhase,
                        PLAYBACK_OPEN_ERROR_MESSAGE,
                    )
                    _playbackPhase.value = endedPhase
                    if (endedPhase is ReceiverPlaybackPhase.Error) {
                        emitPlaybackError(PlaybackErrorTaxonomy.SOURCE, PLAYBACK_OPEN_ERROR_MESSAGE)
                    }
                }
            }
        }
    }

    /**
     * Called by a SurfaceView Activity once its Surface is valid. The caller keeps ownership of
     * the Surface and must call [detachSurface] before it is released.
     */
    override fun attachSurface(surface: Surface, width: Int, height: Int) {
        val generation = surfaceGeneration.incrementAndGet()
        surfaceBinding.set(SurfaceBinding(surface, width, height, generation))
        _surfaceReady.value = false
        ReceiverDiagnostics.record(
            "mpv.attachSurface.requested",
            "generation=$generation valid=${surface.isValid} size=${width}x$height",
        )
        if (closed.get() || !surface.isValid) {
            ReceiverDiagnostics.record(
                "mpv.attachSurface.skipped",
                "closed=${closed.get()} valid=${surface.isValid}",
            )
            return
        }

        controllerScope.launch {
            if (closed.get()) return@launch

            try {
                ReceiverDiagnostics.record("mpv.attachSurface.worker.begin", "generation=$generation")
                val mpv = ensurePlayer(
                    surface,
                    width,
                    height,
                    expectedGeneration = generation,
                )
                if (attachedPlayerSurfaceGeneration != generation) {
                    // Existing initialized player: the Surface changed after init, so wid cannot
                    // be re-set as an option. Refresh the Android output size/force-window the
                    // same way mpv-android does for surfaceCreated on a live instance.
                    attachPlayerToSurface(
                        mpv,
                        surface,
                        width,
                        height,
                        expectedGeneration = generation,
                    )
                }
                if (
                    !closed.get() &&
                    !playerTerminated.get() &&
                    surfaceGeneration.get() == generation
                ) {
                    _surfaceReady.value = true
                    ReceiverDiagnostics.record("mpv.attachSurface.ready", "generation=$generation")
                }
            } catch (error: Throwable) {
                ReceiverDiagnostics.record("mpv.attachSurface.failure", error::class.java.name)
                if (surfaceGeneration.get() == generation) _surfaceReady.value = false
            }
        }
    }

    /**
     * Stops advertising readiness immediately, then detaches on the serialized native thread.
     */
    override fun detachSurface() {
        detachSurface(null)
    }

    fun detachSurface(surfaceToRelease: Surface? = null): CountDownLatch {
        val detached = CountDownLatch(1)
        surfaceGeneration.incrementAndGet()
        surfaceBinding.set(null)
        _surfaceReady.value = false
        if (closed.get()) {
            detached.countDown()
            return detached
        }

        controllerScope.launch {
            try {
                val mpv = player
                if (mpv != null) {
                    runCatching { mpv.detachSurface() }
                    attachedPlayerSurfaceGeneration = -1L
                    runCatching { mpv.setPropertyString(OPTION_FORCE_WINDOW, "no") }
                }
            } finally {
                detached.countDown()
            }
        }
        return detached
    }

    override fun updateSurfaceSize(width: Int, height: Int) {
        val size = MpvSurfaceSizePolicy.value(width, height) ?: return
        surfaceBinding.updateAndGet { binding ->
            binding?.copy(width = width, height = height)
        }
        if (closed.get()) return
        controllerScope.launch {
            player?.setPropertyString(PROPERTY_ANDROID_SURFACE_SIZE, size)
        }
    }

    override fun refreshAudioRoute() {
        if (closed.get()) return
        controllerScope.launch {
            val command = withContext(Dispatchers.Default) { directAudioRouteCommand() }
            val value = command[2]
            if (appliedAudioSpdif.get() == value) return@launch
            val result = withContext(Dispatchers.IO) { commandClient.get()?.dispatch(command) }
            if (result?.isSuccess == true) {
                appliedAudioSpdif.set(value)
            }
        }
    }

    /**
     * Fire OS calls this before its low-memory killer terminates foreground applications. A low
     * warning shrinks libmpv's live cache immediately. A title replacement can retire the native
     * player asynchronously; the valid Android Surface remains bound logically and the next
     * Player.Open waits for the bounded surface handoff before creating a clean engine.
     */
    override fun handleMemoryPressure(level: Int) {
        val action = MpvMemoryPressurePolicy.action(level, active = mediaLoaded.get())
        if (closed.get() || action == MpvMemoryPressureAction.None) return

        ReceiverDiagnostics.record("mpv.memoryPressure", "level=$level action=${action.name}")
        controllerScope.launch {
            when (action) {
                MpvMemoryPressureAction.None -> Unit
                MpvMemoryPressureAction.TrimCache -> trimPlayerCache(active = mediaLoaded.get())
                MpvMemoryPressureAction.RecyclePlayer -> discardPlayerKeepingSurface("memory-critical")
            }
        }
    }

    override suspend fun snapshot(): ReceiverSnapshot {
        // Status RPCs must never wait behind the JNI executor. FILE_LOADED/END_FILE and observed
        // playback properties keep this lock-free snapshot current.
        return snapshotStore.snapshot()
    }

    override suspend fun open(request: OpenMediaRequest): Result<Unit> =
        open(request, forceSoftware = false)

    /** Reopens a previously stalled cast with software decode after a receiver process restart. */
    override suspend fun announceExternalHandoff(playerLabel: String, playerPackage: String) {
        _events.emit(
            ReceiverEvent.ExternalHandoff(
                snapshot = snapshot(),
                playerLabel = playerLabel,
                playerPackage = playerPackage,
            ),
        )
    }

    override suspend fun openWithSoftware(request: OpenMediaRequest): Result<Unit> =
        open(request, forceSoftware = true)

    private suspend fun open(request: OpenMediaRequest, forceSoftware: Boolean): Result<Unit> {
        if (request.url.isBlank()) return failure("Invalid media request.")
        // ffmpeg's native DNS hangs on this box; hostname URLs stream through the on-box okhttp
        // relay instead (see MpvStreamRelay). Headers travel with the relay's upstream request,
        // never through mpv. lastOpenRequest keeps the ORIGINAL request so a retry after a
        // process restart re-registers a fresh token instead of chasing a dead loopback URL.
        val wireURL: String
        val wireHeaders: Map<String, String>
        if (MpvStreamRelay.needsRelay(request.url)) {
            val token = MpvStreamRelay.register(request.url, request.headers)
            wireURL = MpvStreamRelay.relayURL(token, ReceiverPorts.HTTP)
            wireHeaders = emptyMap()
            ReceiverDiagnostics.record(
                "mpv.relay",
                "host=${runCatching { java.net.URI(request.url).host }.getOrNull() ?: "?"}",
            )
        } else {
            wireURL = request.url
            wireHeaders = request.headers
        }
        softwareDecodeOverride.set(forceSoftware)
        hardwareFallbackScheduled.set(forceSoftware)
        if (!forceSoftware) clearPendingSoftwareOpen()
        val openGeneration = captureReadySurfaceGeneration()
            ?: return failure(SURFACE_UNAVAILABLE_MESSAGE)
        val nowPlaying = StagedNowPlaying(
            title = request.title?.takeIf { it.isNotBlank() },
            // This remains display metadata; no native subtitle command is issued here.
            subtitle = request.subtitle?.takeIf { it.isNotBlank() },
            isLive = request.isLive,
            castId = request.castId,
        )
        val startSeconds = request.startPositionMs
            ?.takeIf { it > 0L }
            ?.let { it / 1000.0 }
        val loadPlan = MpvReceiverRequestPolicy.loadPlan(
            title = nowPlaying.title,
            headers = wireHeaders,
            startSeconds = startSeconds,
        ).getOrElse { return Result.failure(it) }
        pendingSeek.set(null)
        mediaLoaded.set(false)
        lastOpenRequest.set(request)
        val requestGeneration = beginOpenOwnership(nowPlaying.title, request.castId)
        // Read, but do not consume, the recovery marker until this generation owns the IPC
        // transaction. A newer title may supersede this request while native preparation runs.
        val freshPlayerForOpen = shouldCreateFreshPlayer(requestGeneration)

        val prepared = withPlayerResult(
            failureMessage = "Unable to open media.",
            requiredSurfaceGeneration = openGeneration,
            requestGeneration = requestGeneration,
            freshPlayerForOpen = freshPlayerForOpen,
        ) { mpv ->
            // The generation must still name the exact ready Surface accepted by this open.
            // Per-file options leave the currently playing file untouched until replacement.
            requireSurfaceReady(openGeneration)
            updateState(MpvSnapshotReducer::opening)
            ReceiverDiagnostics.record(
                "mpv.open.dispatch",
                "scheme=${request.url.substringBefore(':')} title=${nowPlaying.title ?: "untitled"}",
            )
            commandClient.get()
                ?: throw MpvReceiverFailure("Playback command channel is unavailable.")
        }
        if (requestGeneration != activeOpenGeneration.get()) {
            return failure("Playback request was replaced by a newer title.")
        }
        val result = prepared.fold(
            onSuccess = { client ->
                withContext(Dispatchers.IO) {
                    openTransactionMutex.withLock {
                        if (requestGeneration != activeOpenGeneration.get()) {
                            failure<Unit>("Playback request was replaced by a newer title.")
                        } else {
                            // Start the watchdog only after this title owns the transaction. A
                            // queued replacement must not cancel or inherit the prior title's timer.
                            scheduleOpeningTimeout(requestGeneration, client)
                            activeOpenURL.set(wireURL)
                            val widBeforeLoad = client.request(buildJsonArray {
                                add(JsonPrimitive(COMMAND_GET_PROPERTY))
                                add(JsonPrimitive(PROPERTY_WID))
                            }).getOrNull()?.jsonPrimitive?.contentOrNull
                            ReceiverDiagnostics.record(
                                "mpv.open.wid",
                                "wid=$widBeforeLoad generation=$requestGeneration",
                            )
                            // Crash-visible breadcrumb: SIGABRT in the vo thread can kill the
                            // process before the async diagnostics writer drains its queue.
                            ReceiverDiagnostics.flush()
                            // Create the window only when a file is actually loading. Setting
                            // force-window at attach time schedules a VO on an idle player, and a
                            // later retirement can rewrite wid to 0 before that VO finishes its
                            // mediacodec_embed preinit (native assert).
                            client.dispatch(arrayOf(COMMAND_SET_PROPERTY, OPTION_FORCE_WINDOW, "yes"))
                            client.dispatchAll(loadPlan.loadFileIpcCommands(wireURL)).also {
                                if (it.isFailure) activeOpenURL.compareAndSet(wireURL, null)
                            }
                        }
                    }
                }.fold(
                    onSuccess = {
                        if (requestGeneration != activeOpenGeneration.get()) {
                            failure("Playback request was replaced by a newer title.")
                        } else if (!isSurfaceReady(openGeneration)) {
                            // The command can have been accepted immediately before Android revoked
                            // the Surface. Stop through IPC so recovery never waits on JNI.
                            pendingSeek.set(null)
                            mediaLoaded.set(false)
                            withContext(Dispatchers.IO) {
                                openTransactionMutex.withLock {
                                    if (claimOpenOwnership(requestGeneration)) {
                                        client.dispatch(arrayOf(COMMAND_STOP))
                                    } else {
                                        Result.success(Unit)
                                    }
                                }
                            }
                            updateState { MpvSnapshotReducer.active(it, active = false) }
                            failure(SURFACE_UNAVAILABLE_MESSAGE)
                        } else {
                            Result.success(Unit)
                        }
                    },
                    onFailure = { error ->
                        // A rejected or wedged IPC load belongs to this native instance. The next
                        // title must retire it and create a clean engine instead of queueing behind
                        // the failed socket/player pair.
                        markFreshPlayerRequired(requestGeneration)
                        ReceiverDiagnostics.record(
                            "mpv.ipc.dispatch.failure",
                            "type=${error.javaClass.simpleName} message=${MpvNativeLogPolicy.safe(error.message.orEmpty())}",
                        )
                        failure("Unable to open media.")
                    },
                )
            },
            onFailure = { failure("Unable to open media.") },
        )
        if (result.isSuccess) {
            if (claimOpenOwnership(requestGeneration)) {
                stagedNowPlaying.set(nowPlaying)
                consumeFreshPlayerMarker(requestGeneration)
                ReceiverDiagnostics.record(
                    "mpv.open.accepted",
                    "generation=$requestGeneration transport=ipc",
                )
            }
        } else {
            cancelOpeningTimeout(requestGeneration)
            ReceiverDiagnostics.record("mpv.open.failure")
            markOpenFailure(requestGeneration)
        }
        return result
    }

    override suspend fun retryLastOpen(): Result<Unit> =
        lastOpenRequest.get()?.let { open(it) }
            ?: failure("There is no stream to retry.")

    override fun lastOpenMedia(): OpenMediaRequest? = lastOpenRequest.get()

    override suspend fun playPause(): Result<Int> {
        val wasPlaying = snapshotStore.snapshot().speed > 0
        return dispatchCommand(arrayOf(COMMAND_CYCLE, PROPERTY_PAUSE)).map {
            updateState { MpvSnapshotReducer.paused(it, wasPlaying) }
            _playbackPhase.value = if (wasPlaying) {
                ReceiverPlaybackPhase.Paused
            } else {
                ReceiverPlaybackPhase.Playing
            }
            if (wasPlaying) 0 else 1
        }
    }

    override suspend fun seek(command: SeekCommand): Result<Unit> {
        val plan = MpvReceiverRequestPolicy.seek(command, snapshotStore.snapshot())
            ?: return failure("Invalid seek request.")

        val pending = PendingSeek(expectedOffsetSeconds = plan.expectedOffsetSeconds)
        pendingSeek.set(pending)
        return dispatchCommand(plan.command).onFailure {
            pendingSeek.compareAndSet(pending, null)
        }
    }

    override suspend fun stop(): Result<Unit> {
        if (closed.get()) return failure("Player is no longer available.")

        val stopGeneration = beginStopOwnership()
        val stopResult = withContext(Dispatchers.IO) {
            openTransactionMutex.withLock {
                if (!claimStopOwnership(stopGeneration)) {
                    Result.success(Unit)
                } else {
                    commandClient.get()?.dispatch(arrayOf(COMMAND_STOP)) ?: Result.success(Unit)
                }
            }
        }
        if (stopResult.isSuccess) {
            // Keep the initialized player idle for the next title. Retiring on every ordinary Stop
            // creates a second native engine before a wedged detach/destroy has finished and makes
            // repeated phone/remote Stop→Open cycles accumulate native memory.
            controllerScope.launch {
                discardTerminatedPlayerIfNeeded()
            }
        }
        return stopResult.map { Unit }
    }

    override suspend fun setSpeed(speed: Int): Result<Int> {
        if (!MpvReceiverRequestPolicy.isSupportedSpeed(speed)) {
            return failure("Unsupported playback speed.")
        }

        return dispatchCommand(arrayOf(COMMAND_SET_PROPERTY, PROPERTY_SPEED, speed.toString())).map {
            updateState { MpvSnapshotReducer.playbackRate(it, speed.toDouble()) }
            snapshotStore.snapshot().speed
        }.recoverCatching { throw MpvReceiverFailure("Unable to change playback speed.") }
    }

    override suspend fun setVolume(volume: Int): Result<Int> {
        if (!MpvReceiverRequestPolicy.isSupportedVolume(volume)) {
            return failure("Unsupported volume.")
        }

        return dispatchCommand(arrayOf(COMMAND_SET_PROPERTY, PROPERTY_VOLUME, volume.toString())).map {
            updateState { MpvSnapshotReducer.volume(it, volume.toDouble()) }
            snapshotStore.snapshot().volume
        }.recoverCatching { throw MpvReceiverFailure("Unable to change volume.") }
    }

    override suspend fun tracks(): Result<ReceiverTracks> =
        queryTrackList().map(NativeTracks::publicSnapshot)

    override suspend fun selectAudio(index: Int): Result<Unit> {
        val tracks = queryTrackList().getOrElse { return Result.failure(it) }
        val track = tracks.audio.getOrNull(index)
            ?: return failure("Audio track is unavailable.")
        return dispatchCommand(arrayOf(COMMAND_SET_PROPERTY, PROPERTY_AUDIO_ID, track.nativeId.toString()))
    }

    override suspend fun selectSubtitle(selection: SubtitleSelection): Result<Unit> {
        val tracks = queryTrackList().getOrElse { return Result.failure(it) }.subtitles
        val nativeID: String = when (selection) {
            is SubtitleSelection.Index -> {
                if (!selection.enable) "no"
                else tracks.getOrNull(selection.value)?.nativeId?.toString()
                    ?: return failure("Subtitle track is unavailable.")
            }
            SubtitleSelection.Off -> "no"
            SubtitleSelection.On -> {
                val selected = tracks.firstOrNull { it.track.selected }
                val track = selected
                    ?: lastSubtitleId.get()?.let { id -> tracks.firstOrNull { it.nativeId == id } }
                    ?: tracks.firstOrNull()
                    ?: return failure("Subtitle track is unavailable.")
                lastSubtitleId.set(track.nativeId)
                track.nativeId.toString()
            }
            SubtitleSelection.Latest -> {
                val track = tracks.lastOrNull()
                    ?: return failure("Subtitle track is unavailable.")
                lastSubtitleId.set(track.nativeId)
                track.nativeId.toString()
            }
        }
        return dispatchCommand(arrayOf(COMMAND_SET_PROPERTY, PROPERTY_SUBTITLE_ID, nativeID))
    }

    override suspend fun addSubtitle(url: String): Result<Unit> =
        dispatchCommand(arrayOf(COMMAND_SUB_ADD, url, "select", "Cast subtitle"))

    override suspend fun stageNowPlaying(
        title: String?,
        subtitle: String?,
        isLive: Boolean,
        preparationStage: ReceiverPreparationStage?,
        castId: String?,
    ) {
        val prior = stagedNowPlaying.get()
        val staged = StagedNowPlaying(
            title = title?.takeIf { it.isNotBlank() },
            subtitle = subtitle?.takeIf { it.isNotBlank() },
            isLive = isLive,
            castId = castId,
        )
        stagedNowPlaying.set(staged)
        if (preparationStage != null) {
            ReceiverPlaybackPhasePolicy.preparation(
                prior = _playbackPhase.value,
                title = staged.title,
                stage = preparationStage,
                castId = castId,
                activeCastId = prior.castId,
            )?.let { _playbackPhase.value = it }
        }
    }

    override suspend fun stageSubtitleStyle(params: JsonObject) {
        stagedSubtitleStyle.set(params)
        val client = commandClient.get() ?: return
        val commands = subtitleStyleCommands(params)
        withContext(Dispatchers.IO) {
            client.dispatchAll(commands)
        }
    }

    // SGSR is an Exo-engine effect; on mpv the toggle only persists the choice so an engine swap
    // back to Exo honours it (and the TV pill reads the truth either way).
    override fun upscaleMode(): UpscaleMode =
        UpscalePolicy.loadMode(
            applicationContext.getSharedPreferences(UpscalePolicy.PREFERENCES_NAME, Context.MODE_PRIVATE),
        )

    override fun setUpscaleMode(mode: UpscaleMode) {
        UpscalePolicy.saveMode(
            applicationContext.getSharedPreferences(UpscalePolicy.PREFERENCES_NAME, Context.MODE_PRIVATE),
            mode,
        )
        ReceiverDiagnostics.record("exo.upscale.setting", "mode=${mode.name} engine=mpv persistsOnly")
    }

    override suspend fun applySetting(name: String, value: JsonPrimitive): Result<Boolean> {
        if (name == SETTING_AUTOMATIC_AUDIO) {
            val enabled = value.booleanOrNull ?: return Result.success(false)
            automaticAudioRouting.set(enabled)
            return try {
                val command = withContext(Dispatchers.Default) { directAudioRouteCommand() }
                command?.let { dispatchCommand(it).map { true } } ?: Result.success(true)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                failure("Unable to apply automatic audio routing.")
            }
        }
        if (name == SETTING_AUDIO_PASSTHROUGH) {
            val enabled = value.booleanOrNull ?: return Result.success(false)
            passthroughRequested.set(enabled)
            // A profile update sends the master switch first. Clear stale codec choices so a new
            // lossy profile cannot briefly inherit TrueHD/DTS-HD from the previous profile.
            passthroughCodecRequests.clear()
            return try {
                val command = withContext(Dispatchers.Default) { directAudioRouteCommand() }
                command?.let { dispatchCommand(it).map { true } } ?: Result.success(true)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                failure("Unable to apply the audio route.")
            }
        }

        AUDIO_CODEC_SETTINGS[name]?.let { codec ->
            val enabled = value.booleanOrNull ?: return Result.success(false)
            passthroughCodecRequests[codec] = enabled
            return try {
                val command = withContext(Dispatchers.Default) { directAudioRouteCommand() }
                command?.let { dispatchCommand(it).map { true } } ?: Result.success(true)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                failure("Unable to apply the audio route.")
            }
        }

        // Kodi's remaining profile keys describe policy around its own audio engine. They are safe
        // compatibility hints here; libmpv retains decoded PCM unless passthrough was explicitly
        // requested above and Android confirms direct support on the active route.
        return Result.success(name in COMPATIBILITY_SETTING_NAMES)
    }

    override suspend fun applyAudioProfile(profile: ReceiverAudioProfile): Result<Boolean> {
        val plan = ReceiverAudioProfilePolicy.plan(profile)
        automaticAudioRouting.set(plan.automatic)
        passthroughRequested.set(plan.passthrough)
        passthroughCodecRequests.clear()
        passthroughCodecRequests.putAll(plan.codecRequests)
        return try {
            val command = withContext(Dispatchers.Default) { directAudioRouteCommand() }
                ?: return Result.success(true)
            val result = commandClient.get()?.let { client ->
                withContext(Dispatchers.IO) { client.dispatch(command) }
            } ?: Result.success(Unit)
            result.map {
                appliedAudioSpdif.set(command[2])
                ReceiverDiagnostics.record(
                    "mpv.audioProfile",
                    "profile=${profile.name.lowercase()} codecs=${plan.codecRequests.filterValues { it }.keys.sorted()}",
                )
                true
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            failure("Unable to apply the audio profile.")
        }
    }

    override suspend fun executeAction(action: String): Result<Unit> {
        val command = MpvReceiverRequestPolicy.action(action)
            ?: return failure("Unsupported player action.")

        return dispatchCommand(command)
    }

    /** Idempotent, non-blocking teardown suitable for Activity.onDestroy(). */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        surfaceGeneration.incrementAndGet()
        surfaceBinding.set(null)
        _surfaceReady.value = false
        pendingSeek.set(null)
        mediaLoaded.set(false)
        cancelOpeningTimeout()
        watchdogJob.cancel()
        _playbackPhase.value = ReceiverPlaybackPhase.Idle
        stopDiagnosticsPolling()
        controllerScope.launch {
            retireCurrentPlayer("close")
            commandClient.getAndSet(null)?.close()
            controllerJob.cancel()
            nativeDispatcher.close()
            surfaceReaper.shutdown()
            nativeReaper.shutdown()
        }
    }

    /** Alias for lifecycle owners that use destroy terminology. */
    fun destroy() = close()

    private fun ensurePlayer(
        surface: Surface? = null,
        width: Int = 0,
        height: Int = 0,
        expectedGeneration: Long? = null,
    ): MPVLib {
        discardTerminatedPlayerIfNeeded()
        player?.let {
            ReceiverDiagnostics.record("mpv.ensurePlayer.reuse")
            return it
        }
        if (closed.get()) throw MpvReceiverFailure("Player is no longer available.")
        if (retiredPlayerCount.get() > 0) {
            ReceiverDiagnostics.record(
                "mpv.create.whileRetiring",
                "pending=${retiredPlayerCount.get()}",
            )
        }

        ReceiverDiagnostics.record("mpv.create.begin")
        val created = MPVLib.create(applicationContext)
            ?: throw MpvReceiverFailure("Playback engine could not be created.")
        val createdCommandClient = MpvIpcCommandClient(applicationContext)
        val callbacks = PlayerCallbacks(this)
        ReceiverDiagnostics.record("mpv.create.ok")
        try {
            ReceiverDiagnostics.record("mpv.configure.begin")
            configureBeforeInit(created)
            createdCommandClient.configure(created)
            ReceiverDiagnostics.record("mpv.configure.ok")
            if (surface != null) {
                // The AAR's native attachSurface sets libmpv's "wid" option, which is read when
                // the video output is created. It must be set before mpv_initialize; calling it
                // after init silently fails and leaves WinID=0, which makes vo=mediacodec_embed
                // abort with its native assert. This mirrors mpv-android's surfaceCreated order:
                // create -> attachSurface -> init.
                if (expectedGeneration != null && surfaceGeneration.get() != expectedGeneration) {
                    throw MpvReceiverFailure(SURFACE_UNAVAILABLE_MESSAGE)
                }
                ReceiverDiagnostics.record(
                    "mpv.native.attachSurface.beforeInit",
                    "generation=${expectedGeneration ?: surfaceGeneration.get()}",
                )
                created.attachSurface(surface)
                ReceiverDiagnostics.record("mpv.native.attachSurface.beforeInit.ok")
            }
            ReceiverDiagnostics.record("mpv.init.begin")
            created.addLogObserver(callbacks.logObserver)
            created.init()
            ReceiverDiagnostics.record("mpv.init.ok")
            recordEffectiveVideoOutput(createdCommandClient)
            if (surface != null) {
                MpvSurfaceSizePolicy.value(width, height)?.let { size ->
                    created.setPropertyString(PROPERTY_ANDROID_SURFACE_SIZE, size)
                }
                attachedPlayerSurfaceGeneration = expectedGeneration ?: surfaceGeneration.get()
            }
            stagedSubtitleStyle.get()?.let { applySubtitleStyle(created, it) }
            created.addObserver(callbacks.observer)
            observePlaybackProperties(created)
            player = created
            synchronized(callbackLock) {
                playerCallbacks = callbacks
            }
            commandClient.set(createdCommandClient)
            playerPresent.set(true)
            ReceiverDiagnostics.record("mpv.ensurePlayer.ready")
            return created
        } catch (error: Throwable) {
            ReceiverDiagnostics.record("mpv.ensurePlayer.failure", error::class.java.name)
            callbacks.deactivate(this)
            retirePlayer(created, "create-failure")
            createdCommandClient.close()
            throw error
        }
    }

    /**
     * A shutdown instance cannot accept another loadfile. It is discarded on the native
     * dispatcher, and readiness is revoked first so an open cannot create invisible playback.
     */
    private fun invalidateTerminatedPlayer() {
        if (!playerTerminated.compareAndSet(false, true)) return

        _surfaceReady.value = false
        pendingSeek.set(null)
        if (!closed.get()) {
            controllerScope.launch {
                discardTerminatedPlayerIfNeeded()
            }
        }
    }

    /** Must only run on [nativeDispatcher]. */
    private fun discardTerminatedPlayerIfNeeded() {
        if (!playerTerminated.compareAndSet(true, false)) return

        retireCurrentPlayer("terminated")
    }

    /** Must only run on [nativeDispatcher]. Never wait for native teardown here. */
    private fun retireCurrentPlayer(reason: String) {
        val mpv = player ?: return
        player = null
        playerPresent.set(false)
        attachedPlayerSurfaceGeneration = -1L
        playerTerminated.set(false)
        synchronized(callbackLock) {
            playerCallbacks?.deactivate(this)
            playerCallbacks = null
        }
        val retiredCommandClient = commandClient.getAndSet(null)
        pendingSurfaceRelease = retirePlayer(mpv, reason, retiredCommandClient)
    }

    /** Deactivates callbacks immediately and performs potentially blocking native teardown elsewhere. */
    private fun retirePlayer(
        mpv: MPVLib,
        reason: String,
        retiredCommandClient: MpvIpcCommandClient? = null,
    ): CountDownLatch {
        val surfaceReleased = CountDownLatch(1)
        val pendingRetirements = retiredPlayerCount.incrementAndGet()
        if (pendingRetirements == 1) {
            oldestRetirementStartedAtMillis.compareAndSet(0, SystemClock.elapsedRealtime())
        }
        runCatching {
            nativeReaper.execute {
                try {
                    // Do not call mpv.detachSurface() here: it rewrites "wid" to 0 and a VO whose
                    // preinit is still pending can abort with the mediacodec_embed assert. Instead,
                    // the handoff waits for destroy() itself, which joins the core (releasing the
                    // Surface/EGL) without mutating wid. The caller bounds this wait, so a wedged
                    // destroy (hardware-decode stall) cannot block the next cast.
                    runCatching { mpv.destroy() }
                    surfaceReleased.countDown()
                    // Closing the IPC socket before native destruction makes libmpv report a
                    // client removal while a start-file hook is still running. Keep the old
                    // command channel alive until the instance has crossed its native boundary.
                    retiredCommandClient?.close()
                    ReceiverDiagnostics.record("mpv.reaper.finished", "reason=$reason")
                } finally {
                    surfaceReleased.countDown()
                    markRetirementFinished()
                }
            }
        }.onFailure {
            surfaceReleased.countDown()
            markRetirementFinished()
        }
        return surfaceReleased
    }

    private fun markRetirementFinished() {
        if (retiredPlayerCount.decrementAndGet() <= 0) {
            retiredPlayerCount.set(0)
            oldestRetirementStartedAtMillis.set(0)
        }
    }

    /** Must only run on [nativeDispatcher]. */
    private fun createFreshPlayerForOpen(generation: Long): MPVLib {
        requireSurfaceReady(generation)
        val binding = surfaceBinding.get()
            ?.takeIf { it.generation == generation && it.surface.isValid }
            ?: throw MpvReceiverFailure(SURFACE_UNAVAILABLE_MESSAGE)

        val oldestRetirement = oldestRetirementStartedAtMillis.get()
        val oldestRetirementAge = if (oldestRetirement == 0L) {
            0L
        } else {
            (SystemClock.elapsedRealtime() - oldestRetirement).coerceAtLeast(0L)
        }
        if (MpvRetirementPolicy.shouldRecover(retiredPlayerCount.get(), oldestRetirementAge)) {
            requestReceiverProcessRecovery("stuck-native-retirement")
            throw MpvReceiverFailure("Receiver is restarting the playback engine.")
        }

        // A failed HTTPS load can leave JNI teardown blocked forever. Do not destroy the old
        // instance on this executor: detach/terminate it on the reaper, and wait only for the
        // bounded surface handoff. A blocked native destroy must not reject the next title.
        retireCurrentPlayer("new-title")
        pendingSurfaceRelease?.let { release ->
            val released = runCatching {
                release.await(SURFACE_HANDOFF_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            }.getOrDefault(false)
            if (!released) {
                // A wedged destroy (stuck network open) must not reject the next title. Proceed
                // with the fresh player; the old instance drains on the serialized reaper. A
                // player that never created a VO (open-time stall) cannot conflict with the new
                // EGL surface, and normal replacements finish their destroy well inside the bound.
                ReceiverDiagnostics.record(
                    "mpv.handoff.timeout",
                    "proceedingWithFreshPlayer=true pending=${retiredPlayerCount.get()}",
                )
            }
            pendingSurfaceRelease = null
            // The old instance is now detached from the only Surface. Its destroy() may still be
            // blocked on the reaper, but keeping the receiver unavailable until that native call
            // completes would make every post-timeout recovery fail. A later title can repeat the
            // same bounded detach handoff while the serialized reaper drains old instances.
        }
        playerTerminated.set(false)
        stopDiagnosticsPolling()
        ReceiverDiagnostics.record("mpv.recycle", "reason=new-title generation=$generation transport=async")

        // A fresh native instance attaches the Surface before init so the "wid" option is valid
        // for vo=mediacodec_embed; ensurePlayer retires the created player itself on failure.
        val created = ensurePlayer(
            binding.surface,
            binding.width,
            binding.height,
            expectedGeneration = generation,
        )
        requireSurfaceReady(generation)
        return created
    }

    /**
     * A wedged JNI destroy cannot be repaired in-process. Schedule the launcher before killing
     * this process so the receiver comes back without requiring the iOS app to remain foregrounded.
     */
    private fun requestReceiverProcessRecovery(reason: String) {
        if (!processRecoveryRequested.compareAndSet(false, true)) return

        ReceiverDiagnostics.record(
            "mpv.processRecovery",
            "reason=$reason pending=${retiredPlayerCount.get()}",
        )
        val launchIntent = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                    or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_IMMUTABLE
                } else {
                    0
                }
            val pendingIntent = PendingIntent.getActivity(
                applicationContext,
                4789,
                launchIntent,
                pendingIntentFlags,
            )
            applicationContext.getSystemService(AlarmManager::class.java)?.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 500L,
                pendingIntent,
            )
        }
        Process.killProcess(Process.myPid())
    }

    /** Must only run on [nativeDispatcher]. */
    private fun discardPlayerKeepingSurface(reason: String) {
        pendingSeek.set(null)
        mediaLoaded.set(false)
        stopDiagnosticsPolling()
        retireCurrentPlayer(reason)
        updateState { MpvSnapshotReducer.active(it, active = false) }
        ReceiverDiagnostics.record("mpv.recycle", "reason=$reason")
    }

    /** Must only run on [nativeDispatcher]. */
    private suspend fun trimPlayerCache(active: Boolean) {
        val client = commandClient.get() ?: return
        withContext(Dispatchers.IO) {
            listOf(
                arrayOf(COMMAND_SET_PROPERTY, PROPERTY_CACHE_REAHEAD_SECONDS, MpvMemoryPressurePolicy.cacheSeconds(active).toString()),
                arrayOf(COMMAND_SET_PROPERTY, PROPERTY_DEMUXER_MAX_BYTES, MpvMemoryPressurePolicy.cacheBytes(active)),
                arrayOf(COMMAND_SET_PROPERTY, PROPERTY_DEMUXER_MAX_BACK_BYTES, MpvMemoryPressurePolicy.backBytes(active)),
            ).forEach { command -> client.dispatch(command) }
        }
    }

    /** Must only run on [nativeDispatcher]. */
    private fun attachPlayerToSurface(
        mpv: MPVLib,
        surface: Surface,
        width: Int,
        height: Int,
        expectedGeneration: Long? = null,
    ) {
        if (
            closed.get() ||
            !surface.isValid ||
            (expectedGeneration != null && surfaceGeneration.get() != expectedGeneration)
        ) {
            throw MpvReceiverFailure(SURFACE_UNAVAILABLE_MESSAGE)
        }
        ReceiverDiagnostics.record("mpv.native.attachSurface.begin")
        mpv.attachSurface(surface)
        ReceiverDiagnostics.record("mpv.native.attachSurface.ok")
        MpvSurfaceSizePolicy.value(width, height)?.let { size ->
            mpv.setPropertyString(PROPERTY_ANDROID_SURFACE_SIZE, size)
        }
        attachedPlayerSurfaceGeneration = surfaceGeneration.get()
    }

    private fun configureBeforeInit(mpv: MPVLib) {
        val softwareDecode = softwareDecodeOverride.get()
        MpvReceiverStartupPolicy.options.forEach { (name, value) ->
            if (softwareDecode && name == "vd-lavc-o") return@forEach
            val resolvedValue = if (softwareDecode && name == "hwdec") "no" else value
            if (mpv.setOptionString(name, resolvedValue) < 0) {
                throw MpvReceiverFailure("Playback engine configuration failed.")
            }
        }
        MpvSubtitleFontStore.prepare(applicationContext)?.let { directory ->
            if (mpv.setOptionString(OPTION_SUB_FONTS_DIR, directory.absolutePath) < 0) {
                throw MpvReceiverFailure("Subtitle font configuration failed.")
            }
        }
    }

    private fun observePlaybackProperties(mpv: MPVLib) {
        mpv.observeProperty(PROPERTY_IDLE_ACTIVE, MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty(PROPERTY_TIME_POSITION, MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty(PROPERTY_DURATION, MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty(PROPERTY_PAUSE, MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty(PROPERTY_SPEED, MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty(PROPERTY_VOLUME, MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty(PROPERTY_MUTED, MPVLib.MpvFormat.MPV_FORMAT_FLAG)
    }

    /** Records the effective VO so a field report can distinguish a VO-selection regression (the
     * mediacodec_embed WinID assert class) from a demuxer failure without a native tombstone.
     * Must never affect playback: the IPC server socket can be unavailable immediately after init,
     * so every failure is absorbed. */
    private fun recordEffectiveVideoOutput(client: MpvIpcCommandClient) {
        runCatching {
            fun property(name: String): String? {
                val element = client.request(buildJsonArray {
                    add(JsonPrimitive(COMMAND_GET_PROPERTY))
                    add(JsonPrimitive(name))
                }).getOrNull() ?: return null
                return (element as? JsonPrimitive)?.contentOrNull
            }
            ReceiverDiagnostics.record(
                "mpv.vo",
                "vo=${property("vo") ?: "?"} hwdec=${property("hwdec") ?: "?"} wid=${property("wid") ?: "?"}",
            )
        }.onFailure { error ->
            ReceiverDiagnostics.record(
                "mpv.vo.query",
                "type=${error.javaClass.simpleName} message=${MpvNativeLogPolicy.safe(error.message.orEmpty())}",
            )
        }
    }

    /**
     * The AFTDCT31 has an SDR panel. Dolby Vision/HDR10 sources must be tone-mapped to BT.709
     * instead of being presented as untagged HDR frames, which renders pink/green and can make the
     * display flicker while the compositor renegotiates. Applies libplacebo-compatible targets only
     * when the loaded video is actually HDR, and records what was detected for field reports.
     *
     * It ALSO publishes [PlaybackDiagnostics], because this is the one property read that happens
     * after `FILE_LOADED` on this path. The publish deliberately runs BEFORE the not-HDR early
     * return: auto frame rate matching needs the frame rate of every title, not only HDR ones.
     */
    private suspend fun applyColorSafePolicyForLoadedFile(generation: Long) {
        if (closed.get()) return
        val client = commandClient.get() ?: return
        if (activeOpenGeneration.get() != generation) return

        val detected = withContext(Dispatchers.IO) {
            runCatching {
                fun stringProperty(name: String): String? =
                    client.request(buildJsonArray {
                        add(JsonPrimitive(COMMAND_GET_PROPERTY))
                        add(JsonPrimitive(name))
                    }).getOrNull()?.jsonPrimitive?.contentOrNull

                val tracks = client.request(buildJsonArray {
                    add(JsonPrimitive(COMMAND_GET_PROPERTY))
                    add(JsonPrimitive(PROPERTY_TRACK_LIST))
                }).getOrNull()
                val dvProfile = (tracks as? JsonArray).orEmpty().firstNotNullOfOrNull { element ->
                    (element as? JsonObject)?.takeIf { track ->
                        track["type"]?.jsonPrimitive?.contentOrNull == "video"
                    }?.get("dolby-vision-profile")?.jsonPrimitive?.intOrNull
                }
                ColorSafeDetection(
                    dolbyVisionProfile = dvProfile ?: 0,
                    primaries = stringProperty("video-params/primaries").orEmpty(),
                    gamma = stringProperty("video-params/gamma").orEmpty(),
                    // `container-fps` is the STATED rate. `estimated-vf-fps` is a running measure
                    // that wobbles, and feeding a wobbling rate to a display-mode request would
                    // re-link HDMI mid-film — a black flash the viewer sees.
                    framesPerSecond = stringProperty("container-fps")?.toDoubleOrNull() ?: 0.0,
                    durationSeconds = stringProperty("duration")?.toDoubleOrNull() ?: 0.0,
                    width = stringProperty("video-params/w")?.toIntOrNull() ?: 0,
                    height = stringProperty("video-params/h")?.toIntOrNull() ?: 0,
                    videoCodec = stringProperty("video-format").orEmpty(),
                )
            }.getOrDefault(ColorSafeDetection())
        }
        if (closed.get() || activeOpenGeneration.get() != generation) return

        // Before the HDR check on purpose — every title needs its frame rate published.
        _diagnostics.value = PlaybackDiagnostics(
            active = true,
            videoCodec = detected.videoCodec,
            width = detected.width,
            height = detected.height,
            framesPerSecond = detected.framesPerSecond,
            durationSeconds = detected.durationSeconds,
            dolbyVisionProfile = detected.dolbyVisionProfile,
        )

        val isHdr = detected.dolbyVisionProfile > 0 ||
            detected.gamma.equals("pq", ignoreCase = true) ||
            detected.gamma.equals("hlg", ignoreCase = true) ||
            detected.primaries.equals("bt.2020", ignoreCase = true)
        if (!isHdr) return

        val label = if (detected.dolbyVisionProfile > 0) {
            DolbyVisionColorPolicy.SAFE_MODE
        } else {
            "HDR → color-safe SDR"
        }
        val commands = listOf(
            arrayOf(COMMAND_SET_PROPERTY, "target-prim", "bt.709"),
            arrayOf(COMMAND_SET_PROPERTY, "target-trc", "bt.1886"),
            arrayOf(COMMAND_SET_PROPERTY, "tone-mapping", "bt.2446a"),
            arrayOf(COMMAND_SET_PROPERTY, "gamut-mapping", "perceptual"),
            arrayOf(COMMAND_SET_PROPERTY, "target-colorspace-hint", "no"),
        )
        withContext(Dispatchers.IO) {
            commands.forEach { command -> client.dispatch(command) }
        }
        ReceiverDiagnostics.record(
            "mpv.colorPolicy",
            "label=$label dv=${detected.dolbyVisionProfile} gamma=${detected.gamma.ifBlank { "?" }} " +
                "primaries=${detected.primaries.ifBlank { "?" }}",
        )
    }

    private data class ColorSafeDetection(
        val dolbyVisionProfile: Int = 0,
        val primaries: String = "",
        val gamma: String = "",
        val framesPerSecond: Double = 0.0,
        val durationSeconds: Double = 0.0,
        val width: Int = 0,
        val height: Int = 0,
        val videoCodec: String = "",
    )

    /**
     * What the speakers ACTUALLY receive on the mpv path — the counterpart of the Exo engine's
     * `exo.audio.trackInit` line. Source layout vs output layout is the only way to see a silent
     * stereo downmix, and `hr-seek`-style guesswork is not evidence.
     */
    private suspend fun recordAudioOutputParams() {
        val client = commandClient.get() ?: return
        suspend fun property(name: String): String? = withContext(Dispatchers.IO) {
            runCatching {
                client.request(buildJsonArray {
                    add(JsonPrimitive(COMMAND_GET_PROPERTY))
                    add(JsonPrimitive(name))
                }).getOrNull()?.jsonPrimitive?.contentOrNull
            }.getOrNull()?.takeIf { it.isNotBlank() && it != "null" }
        }
        // The audio output is negotiated asynchronously after FILE_LOADED, so the first read can
        // legitimately be empty. Poll briefly; a file with no audio track simply reports none.
        var outLayout: String? = null
        for (attempt in 0 until AUDIO_PARAM_POLL_ATTEMPTS) {
            outLayout = property("audio-out-params/channels")
            if (outLayout != null) break
            delay(AUDIO_PARAM_POLL_INTERVAL_MILLIS)
        }
        ReceiverDiagnostics.record(
            "mpv.audio.output",
            "src=${property("audio-params/channels") ?: "-"} " +
                "out=${outLayout ?: "-"} " +
                "fmt=${property("audio-out-params/format") ?: "-"} " +
                "spdif=${property(PROPERTY_AUDIO_SPDIF) ?: "-"} " +
                "codec=${property("audio-codec-name") ?: "-"}",
        )
    }

    private suspend fun queryTrackList(): Result<NativeTracks> {
        val client = commandClient.get()
            ?: return failure("Playback command channel is unavailable.")
        val result = withContext(Dispatchers.IO) {
            client.request(buildJsonArray {
                add(JsonPrimitive(COMMAND_GET_PROPERTY))
                add(JsonPrimitive(PROPERTY_TRACK_LIST))
            })
        }
        return result.fold(
            onSuccess = { element -> runCatching { parseTrackList(element) }
                .fold({ Result.success(it) }, { failure("Unable to read media tracks.") }) },
            onFailure = { failure("Unable to read media tracks.") },
        )
    }

    private fun parseTrackList(element: JsonElement): NativeTracks {
        val audio = mutableListOf<NativeTrack>()
        val subtitles = mutableListOf<NativeTrack>()

        element.jsonArray.forEach { entry ->
            val objectValue = entry as? JsonObject ?: return@forEach
            val type = (objectValue["type"] as? JsonPrimitive)?.content ?: return@forEach
            val nativeId = (objectValue["id"] as? JsonPrimitive)?.intOrNull ?: return@forEach
            val selected = (objectValue["selected"] as? JsonPrimitive)?.booleanOrNull == true
            val destination = when (type) {
                "audio" -> audio
                "sub" -> subtitles
                else -> return@forEach
            }
            val publicIndex = destination.size
            val language = (objectValue["lang"] as? JsonPrimitive)?.content.orEmpty()
            val title = (objectValue["title"] as? JsonPrimitive)?.content.orEmpty()
            val kind = if (type == "audio") "Audio" else "Subtitle"
            val displayName = title.ifBlank { language.ifBlank { "$kind ${publicIndex + 1}" } }
            val track = ReceiverTrack(
                index = publicIndex,
                language = language,
                name = displayName,
                codec = (objectValue["codec"] as? JsonPrimitive)?.content,
                channels = (objectValue["demux-channel-count"] as? JsonPrimitive)?.intOrNull,
                isOriginal = (objectValue["original"] as? JsonPrimitive)?.booleanOrNull,
                selected = selected,
            )
            destination += NativeTrack(nativeId = nativeId, track = track)
            if (type == "sub" && selected) lastSubtitleId.set(nativeId)
        }
        return NativeTracks(audio = audio, subtitles = subtitles)
    }

    private fun subtitleStyleCommands(params: JsonObject): List<JsonArray> {
        val family = (params["family"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.takeIf { it.length <= MAX_SUBTITLE_FONT_LENGTH }
            .orEmpty()
        val color = (params["colorHex"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.takeIf(SUBTITLE_COLOR::matches)
            ?: DEFAULT_SUBTITLE_COLOR
        val size = (params["size"] as? JsonPrimitive)?.doubleOrNull
            ?.takeIf(Double::isFinite)
            ?.coerceIn(MIN_SUBTITLE_SIZE, MAX_SUBTITLE_SIZE)
            ?: DEFAULT_SUBTITLE_SIZE
        val lift = (params["lift"] as? JsonPrimitive)?.doubleOrNull
            ?.takeIf(Double::isFinite)
            ?.coerceIn(MIN_SUBTITLE_LIFT, MAX_SUBTITLE_LIFT)
            ?: MIN_SUBTITLE_LIFT
        fun set(name: String, value: String): JsonArray = buildJsonArray {
            add(JsonPrimitive(COMMAND_SET_PROPERTY)); add(JsonPrimitive(name)); add(JsonPrimitive(value))
        }
        return listOf(
            set("sub-ass-override", "force"),
            set("sub-font", MpvSubtitleFontPolicy.resolvedFamily(family)),
            set("sub-bold", "no"),
            set("sub-font-size", (size * SUBTITLE_SIZE_SCALE).coerceAtMost(MAX_MPV_SUBTITLE_SIZE).toString()),
            set("sub-color", color),
            set("sub-border-size", "0.0"),
            set("sub-shadow-offset", "0.0"),
            set("sub-use-margins", "yes"),
            set("sub-margin-y", (BASE_SUBTITLE_MARGIN + lift).toInt().toString()),
        )
    }

    private fun applySubtitleStyle(mpv: MPVLib, params: JsonObject) {
        val family = (params["family"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.takeIf { it.length <= MAX_SUBTITLE_FONT_LENGTH }
            .orEmpty()
        val color = (params["colorHex"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.takeIf(SUBTITLE_COLOR::matches)
            ?: DEFAULT_SUBTITLE_COLOR
        val size = (params["size"] as? JsonPrimitive)?.doubleOrNull
            ?.takeIf(Double::isFinite)
            ?.coerceIn(MIN_SUBTITLE_SIZE, MAX_SUBTITLE_SIZE)
            ?: DEFAULT_SUBTITLE_SIZE
        val lift = (params["lift"] as? JsonPrimitive)?.doubleOrNull
            ?.takeIf(Double::isFinite)
            ?.coerceIn(MIN_SUBTITLE_LIFT, MAX_SUBTITLE_LIFT)
            ?: MIN_SUBTITLE_LIFT

        mpv.setPropertyString("sub-ass-override", "force")
        mpv.setPropertyString("sub-font", MpvSubtitleFontPolicy.resolvedFamily(family))
        mpv.setPropertyString("sub-bold", "no")
        mpv.setPropertyDouble("sub-font-size", (size * SUBTITLE_SIZE_SCALE).coerceAtMost(MAX_MPV_SUBTITLE_SIZE))
        mpv.setPropertyString("sub-color", color)
        mpv.setPropertyDouble("sub-border-size", 0.0)
        mpv.setPropertyDouble("sub-shadow-offset", 0.0)
        mpv.setPropertyString("sub-use-margins", "yes")
        mpv.setPropertyInt("sub-margin-y", (BASE_SUBTITLE_MARGIN + lift).toInt())
    }

    private fun directAudioRouteCommand(): Array<String> {
        val routeSupported = AndroidDirectAudioProbe.supportedMpvCodecs(applicationContext)
        val codecs = if (automaticAudioRouting.get()) {
            routeSupported
        } else {
            DirectAudioPolicy.allowedCodecs(
                passthroughRequested = passthroughRequested.get(),
                routeSupported = routeSupported,
                profileRequested = passthroughCodecRequests,
            )
        }
        // An empty list is the supported mpv state for decoded PCM. It is important to send the
        // empty transition when HDMI/eARC is removed; otherwise a previously selected passthrough
        // codec remains active on the next title even though the route no longer supports it.
        return arrayOf(COMMAND_SET_PROPERTY, PROPERTY_AUDIO_SPDIF, codecs.joinToString(","))
    }

    private fun stopDiagnosticsPolling() {
        _diagnostics.value = PlaybackDiagnostics()
    }

    private fun scheduleOpeningTimeout(generation: Long, clientForGeneration: MpvIpcCommandClient) {
        cancelOpeningTimeout()
        openingTimeoutGeneration.set(generation)
        val job = watchdogScope.launch {
            val hardwareProbeEligible = MpvReceiverStartupPolicy.HARDWARE_DECODE_ENABLED &&
                !softwareDecodeOverride.get() &&
                !hardwareFallbackScheduled.get()
            val probeMilliseconds = if (hardwareProbeEligible) {
                HARDWARE_OPEN_PROBE_MILLIS
            } else {
                OPENING_TIMEOUT_MILLIS
            }
            delay(probeMilliseconds)
            val timeoutOwned = synchronized(openOwnershipLock) {
                if (generation != activeOpenGeneration.get() ||
                    _playbackPhase.value !is ReceiverPlaybackPhase.Opening
                ) {
                    false
                } else if (hardwareProbeEligible) {
                    // Keep this open alive: the hardware probe stalled, so switch the player to
                    // software decode and re-load the same URL on a fresh instance.
                    hardwareFallbackScheduled.set(true)
                    ReceiverDiagnostics.record(
                        "mpv.open.hardwareStall",
                        "generation=$generation probe=${HARDWARE_OPEN_PROBE_MILLIS}ms",
                    )
                    true
                } else {
                    activeOpenGeneration.set(generation + 1)
                    activeOpenURL.set(null)
                    forceFreshPlayerAfterGeneration.set(generation)
                    _playbackPhase.value = ReceiverPlaybackPhase.Error(PLAYBACK_OPEN_TIMEOUT_MESSAGE)
                    emitPlaybackError(PlaybackErrorTaxonomy.SOURCE, PLAYBACK_OPEN_TIMEOUT_MESSAGE)
                    true
                }
            }
            if (!timeoutOwned) return@launch

            if (hardwareFallbackScheduled.get()) {
                // FFmpeg's MediaCodec init runs on mpv's core thread and is wedged, so no IPC stop
                // can reach it and destroy() would hang. Persist the cast, restart the receiver
                // process to release the Surface, and resume with hwdec=no on the next launch.
                persistPendingSoftwareOpen()
                ReceiverDiagnostics.record(
                    "mpv.open.hwFallback",
                    "generation=$generation restartingReceiver=true",
                )
                ReceiverDiagnostics.flush()
                requestReceiverProcessRecovery("hardware-decode-stall")
                return@launch
            }

            pendingSeek.set(null)
            mediaLoaded.set(false)
            updateState { MpvSnapshotReducer.active(it, active = false) }
            val stopResult = withContext(Dispatchers.IO) {
                // The timeout belongs to this player. Serialize its stop with the next load and
                // re-check ownership inside the mutex: a replacement may have started while this
                // watchdog was waiting for the command channel.
                openTransactionMutex.withLock {
                    val ownsTimeout = synchronized(openOwnershipLock) {
                        activeOpenGeneration.get() == generation + 1L &&
                            _playbackPhase.value is ReceiverPlaybackPhase.Error
                    }
                    if (!ownsTimeout) {
                        false
                    } else {
                        clientForGeneration.dispatch(arrayOf(COMMAND_STOP)).isSuccess
                    }
                }
            }
            ReceiverDiagnostics.record(
                "mpv.open.timeout",
                "generation=$generation stopped=$stopResult",
            )
        }
        openingTimeoutJob.set(job)
    }

    /**
     * Persists the currently owned cast so the receiver can resume it with software decode after
     * a process restart. Cleared when a new user open starts.
     */
    private fun persistPendingSoftwareOpen() {
        val request = lastOpenRequest.get() ?: return
        val preferences = applicationContext.getSharedPreferences(
            PENDING_SOFTWARE_OPEN_PREFERENCES,
            Context.MODE_PRIVATE,
        )
        val headersJson = buildJsonObject {
            request.headers.forEach { (name, value) -> put(name, JsonPrimitive(value)) }
        }.toString()
        // commit() (not apply()): the process is killed immediately after persisting, so the
        // pending cast must already be on disk before the watchdog returns.
        preferences.edit()
            .putString(PENDING_URL_KEY, request.url)
            .putString(PENDING_TITLE_KEY, request.title)
            .putString(PENDING_SUBTITLE_KEY, request.subtitle)
            .putBoolean(PENDING_LIVE_KEY, request.isLive)
            .putString(PENDING_HEADERS_KEY, headersJson)
            .commit()
        ReceiverDiagnostics.record("mpv.open.pendingSoftwarePersisted", "url=${request.url.substringBefore(':')}")
    }

    /** Returns and clears a persisted software-open from a previous hardware stall. */
    override fun consumePendingSoftwareOpen(): OpenMediaRequest? {
        val preferences = applicationContext.getSharedPreferences(
            PENDING_SOFTWARE_OPEN_PREFERENCES,
            Context.MODE_PRIVATE,
        )
        val url = preferences.getString(PENDING_URL_KEY, null) ?: return null
        val headers = runCatching {
            Json.parseToJsonElement(preferences.getString(PENDING_HEADERS_KEY, "{}").orEmpty())
                .jsonObject.entries.associate { (name, element) -> name to element.jsonPrimitive.content }
        }.getOrDefault(emptyMap())
        val request = OpenMediaRequest(
            url = url,
            title = preferences.getString(PENDING_TITLE_KEY, null),
            subtitle = preferences.getString(PENDING_SUBTITLE_KEY, null),
            isLive = preferences.getBoolean(PENDING_LIVE_KEY, false),
            headers = headers,
        )
        clearPendingSoftwareOpen()
        ReceiverDiagnostics.record("mpv.open.pendingSoftwareConsumed", "url=${url.substringBefore(':')}")
        return request
    }

    private fun clearPendingSoftwareOpen() {
        applicationContext.getSharedPreferences(
            PENDING_SOFTWARE_OPEN_PREFERENCES,
            Context.MODE_PRIVATE,
        ).edit().clear().apply()
    }

    private fun beginOpenOwnership(title: String?, castId: String?): Long = synchronized(openOwnershipLock) {
        val generation = activeOpenGeneration.incrementAndGet()
        activeOpenURL.set(null)
        _playbackPhase.value = ReceiverPlaybackPhase.Opening(
            title,
            ReceiverPreparationStage.PREPARING,
            castId,
        )
        generation
    }

    private fun beginStopOwnership(): Long = synchronized(openOwnershipLock) {
        val generation = activeOpenGeneration.incrementAndGet()
        activeOpenURL.set(null)
        pendingSeek.set(null)
        mediaLoaded.set(false)
        cancelOpeningTimeout()
        _playbackPhase.value = ReceiverPlaybackPhase.Stopped
        updateState { MpvSnapshotReducer.active(it, active = false) }
        generation
    }

    private fun claimOpenOwnership(generation: Long): Boolean = synchronized(openOwnershipLock) {
        activeOpenGeneration.get() == generation
    }

    private fun claimStopOwnership(generation: Long): Boolean = synchronized(openOwnershipLock) {
        activeOpenGeneration.get() == generation &&
            _playbackPhase.value is ReceiverPlaybackPhase.Stopped
    }

    private fun shouldCreateFreshPlayer(generation: Long): Boolean = synchronized(openOwnershipLock) {
        MpvOpenPlayerPolicy.requiresFreshPlayer(
            playerPresent = playerPresent.get(),
            freshMarkerGeneration = forceFreshPlayerAfterGeneration.get(),
            generation = generation,
        )
    }

    private fun consumeFreshPlayerMarker(generation: Long) = synchronized(openOwnershipLock) {
        if (activeOpenGeneration.get() == generation &&
            forceFreshPlayerAfterGeneration.get() >= 0 &&
            forceFreshPlayerAfterGeneration.get() < generation
        ) {
            forceFreshPlayerAfterGeneration.set(-1)
        }
    }

    private fun markFreshPlayerRequired(generation: Long) = synchronized(openOwnershipLock) {
        if (activeOpenGeneration.get() == generation) {
            forceFreshPlayerAfterGeneration.set(generation)
        }
    }

    private fun markOpenFailure(generation: Long) = synchronized(openOwnershipLock) {
        if (activeOpenGeneration.get() == generation) {
            _playbackPhase.value = ReceiverPlaybackPhase.Error(PLAYBACK_OPEN_ERROR_MESSAGE)
            emitPlaybackError(PlaybackErrorTaxonomy.SOURCE, PLAYBACK_OPEN_ERROR_MESSAGE)
            activeOpenURL.set(null)
        }
    }

    private fun cancelOpeningTimeout(generation: Long? = null) {
        if (generation != null && !openingTimeoutGeneration.compareAndSet(generation, -1)) return
        if (generation == null) openingTimeoutGeneration.set(-1)
        openingTimeoutJob.getAndSet(null)?.cancel()
    }

    private fun refreshSeekFromPlayer() {
        if (closed.get()) return

        controllerScope.launch {
            if (closed.get()) return@launch

            val pending = pendingSeek.getAndSet(null)
            val offset = pending?.expectedOffsetSeconds
            offset?.takeIf { it.isFinite() }
                ?.let { safeOffset -> snapshotStore.seekEvent(safeOffset) }
                ?.let(::emitEvent)
        }
    }

    private fun updateState(reducer: (MpvPlaybackState) -> MpvStateChange) {
        snapshotStore.update(reducer)?.let(::emitEvent)
    }

    private fun emitEvent(event: ReceiverEvent) {
        _events.tryEmit(event)
    }

    /** Mirror an mpv-side failure to the phone (X4789.OnPlaybackError), same as the Exo engine. */
    private fun emitPlaybackError(category: String, humanText: String) {
        ReceiverDiagnostics.record("notify.playbackError", "category=$category mime=- fatal=true")
        _events.tryEmit(
            ReceiverEvent.Error(
                snapshot = snapshotStore.snapshot(),
                category = category,
                mimeType = null,
                humanText = humanText,
                engine = "mpv",
                fatal = true,
            ),
        )
    }

    private suspend fun <T> withPlayerResult(
        failureMessage: String,
        requiredSurfaceGeneration: Long? = null,
        requestGeneration: Long? = null,
        freshPlayerForOpen: Boolean = false,
        block: (MPVLib) -> T,
    ): Result<T> {
        if (closed.get()) return failure("Player is no longer available.")

        return try {
            Result.success(
                withContext(nativeDispatcher) {
                    if (closed.get()) throw MpvReceiverFailure("Player is no longer available.")
                    requestGeneration?.let { generation ->
                        if (generation != activeOpenGeneration.get()) {
                            throw MpvReceiverFailure("Playback request was replaced by a newer title.")
                        }
                    }
                    requiredSurfaceGeneration?.let(::requireSurfaceReady)
                    val mpv = if (freshPlayerForOpen) {
                        createFreshPlayerForOpen(
                            requiredSurfaceGeneration
                                ?: throw MpvReceiverFailure(SURFACE_UNAVAILABLE_MESSAGE),
                        )
                    } else {
                        ensurePlayer()
                    }
                    requiredSurfaceGeneration?.let { generation ->
                        if (attachedPlayerSurfaceGeneration != generation) {
                            val binding = surfaceBinding.get()
                                ?.takeIf { it.generation == generation && it.surface.isValid }
                                ?: throw MpvReceiverFailure(SURFACE_UNAVAILABLE_MESSAGE)
                            attachPlayerToSurface(
                                mpv,
                                binding.surface,
                                binding.width,
                                binding.height,
                                expectedGeneration = generation,
                            )
                        }
                    }
                    block(mpv)
                },
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            ReceiverDiagnostics.record(
                "mpv.native.operation.failure",
                "type=${error.javaClass.simpleName} message=${MpvNativeLogPolicy.safe(error.message.orEmpty())}",
            )
            failure(failureMessage)
        }
    }

    private fun captureReadySurfaceGeneration(): Long? =
        surfaceGeneration.get().takeIf(::isSurfaceReady)

    private fun requireSurfaceReady(generation: Long) {
        if (!isSurfaceReady(generation)) throw MpvReceiverFailure(SURFACE_UNAVAILABLE_MESSAGE)
    }

    private fun isSurfaceReady(generation: Long): Boolean =
        MpvSurfaceGenerationPolicy.isReadyForLoad(
            openGeneration = generation,
            currentGeneration = surfaceGeneration.get(),
            surfaceReady = _surfaceReady.value,
        )

    /** Commands use MPV's JSON socket so a stalled decoder cannot block receiver RPCs. */
    private suspend fun dispatchCommand(command: Array<String>): Result<Unit> =
        withContext(Dispatchers.IO) {
            commandClient.get()?.dispatch(command)
                ?: failure("Playback command channel is unavailable.")
        }

    private fun <T> failure(message: String): Result<T> =
        Result.failure(MpvReceiverFailure(message))

    private data class PendingSeek(
        val expectedOffsetSeconds: Double?,
    )

    private data class SurfaceBinding(
        val surface: Surface,
        val width: Int,
        val height: Int,
        val generation: Long,
    )

    private data class StagedNowPlaying(
        val title: String? = null,
        val subtitle: String? = null,
        val isLive: Boolean = false,
        val castId: String? = null,
    )

    private data class NativeTrack(
        val nativeId: Int,
        val track: ReceiverTrack,
    )

    private data class NativeTracks(
        val audio: List<NativeTrack>,
        val subtitles: List<NativeTrack>,
    ) {
        fun publicSnapshot(): ReceiverTracks = ReceiverTracks(
            audio = audio.map { it.track },
            subtitles = subtitles.map { it.track },
        )
    }

    private companion object {
        private const val EVENT_BUFFER_SIZE = 32
        private const val SURFACE_UNAVAILABLE_MESSAGE = "Playback surface is not ready."
        private const val PLAYBACK_OPEN_ERROR_MESSAGE =
            "This source could not be opened. Try it again, or choose another source on your iPhone."
        private const val PLAYBACK_ENGINE_ERROR_MESSAGE =
            "Playback stopped unexpectedly. Try the stream again from your iPhone."
        private const val PLAYBACK_OPEN_TIMEOUT_MESSAGE =
            "The source did not respond in time. Try it again, or choose another source on your iPhone."
        private const val OPENING_TIMEOUT_MILLIS = 60_000L
        private const val HARDWARE_OPEN_PROBE_MILLIS = 10_000L
        private const val SURFACE_HANDOFF_TIMEOUT_MILLIS = 3_000L
        private const val PENDING_SOFTWARE_OPEN_PREFERENCES = "pending_software_open"
        private const val PENDING_URL_KEY = "url"
        private const val PENDING_TITLE_KEY = "title"
        private const val PENDING_SUBTITLE_KEY = "subtitle"
        private const val PENDING_LIVE_KEY = "live"
        private const val PENDING_HEADERS_KEY = "headers"
        private const val DEFAULT_SUBTITLE_COLOR = "#FFFFFF"
        private const val DEFAULT_SUBTITLE_SIZE = 20.0
        private const val MIN_SUBTITLE_SIZE = 12.0
        private const val MAX_SUBTITLE_SIZE = 46.0
        private const val MIN_SUBTITLE_LIFT = 0.0
        private const val MAX_SUBTITLE_LIFT = 280.0
        private const val SUBTITLE_SIZE_SCALE = 2.75
        private const val MAX_MPV_SUBTITLE_SIZE = 96.0
        private const val BASE_SUBTITLE_MARGIN = 22.0
        private const val MAX_SUBTITLE_FONT_LENGTH = 100
        private val SUBTITLE_COLOR = Regex("#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?")

        private const val COMMAND_CYCLE = "cycle"
        private const val COMMAND_GET_PROPERTY = "get_property"
        private const val COMMAND_SET_PROPERTY = "set_property"
        private const val COMMAND_SUB_ADD = "sub-add"
        private const val COMMAND_STOP = "stop"

        private const val OPTION_FORCE_WINDOW = MpvReceiverStartupPolicy.FORCE_WINDOW
        private const val OPTION_SUB_FONTS_DIR = "sub-fonts-dir"

        private const val PROPERTY_DURATION = "duration"
        private const val PROPERTY_IDLE_ACTIVE = "idle-active"
        private const val PROPERTY_MUTED = "mute"
        private const val PROPERTY_PAUSE = "pause"
        private const val PROPERTY_PATH = "path"
        private const val PROPERTY_SPEED = "speed"
        private const val PROPERTY_AUDIO_ID = "aid"
        private const val PROPERTY_AUDIO_SPDIF = "audio-spdif"
        private const val PROPERTY_ANDROID_SURFACE_SIZE = "android-surface-size"
        private const val PROPERTY_CACHE_REAHEAD_SECONDS = "cache-secs"
        private const val PROPERTY_DEMUXER_MAX_BYTES = "demuxer-max-bytes"
        private const val PROPERTY_DEMUXER_MAX_BACK_BYTES = "demuxer-max-back-bytes"
        private const val PROPERTY_SUBTITLE_ID = "sid"
        private const val PROPERTY_TIME_POSITION = "time-pos"
        private const val PROPERTY_TRACK_LIST = "track-list"
        private const val PROPERTY_VOLUME = "volume"
        private const val PROPERTY_WID = "wid"

        private const val AUDIO_PARAM_POLL_ATTEMPTS = 6
        private const val AUDIO_PARAM_POLL_INTERVAL_MILLIS = 500L

        private const val SETTING_AUDIO_PASSTHROUGH = "audiooutput.passthrough"
        private const val SETTING_AUTOMATIC_AUDIO = "x4789.audio.automatic"
        private val COMPATIBILITY_SETTING_NAMES = setOf(
            "audiooutput.maintainoriginalvolume",
            "audiooutput.channels",
            "audiooutput.stereoupmix",
            "audiooutput.ac3passthrough",
            "audiooutput.eac3passthrough",
            "audiooutput.dtspassthrough",
            "audiooutput.dtshdpassthrough",
            "audiooutput.truehdpassthrough",
            "audiooutput.dtshdcorefallback",
            "audiooutput.ac3transcode",
            "audiooutput.guisoundmode",
            "audiooutput.streamsilence",
            "videoplayer.adjustrefreshrate",
            "subtitles.fontsize",
            "subtitles.align",
            "subtitles.style",
            "subtitles.fontname",
        )

        // Shared with the ExoPlayer path so the two engines cannot disagree about which Kodi keys
        // are real passthrough switches.
        private val AUDIO_CODEC_SETTINGS = DirectAudioPolicy.codecSettings

    }
}

internal object MpvSurfaceSizePolicy {
    fun value(width: Int, height: Int): String? =
        if (width > 0 && height > 0) "${width}x$height" else null
}

/** Keeps native diagnostics useful without persisting bearer URLs or unbounded log messages. */
internal object MpvNativeLogPolicy {
    private val url = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    private const val MAX_LENGTH = 320

    fun safe(value: String): String = url.replace(value, "<url>")
        .replace('\r', ' ')
        .replace('\n', ' ')
        .take(MAX_LENGTH)
}

/** MPV may canonicalize a URL's percent escapes while exposing its current `path` property. */
internal object MpvPathPolicy {
    fun matches(expected: String, actual: String?): Boolean =
        actual != null && (expected == actual ||
            runCatching { java.net.URI(expected).normalize() == java.net.URI(actual).normalize() }
                .getOrDefault(false))
}

/**
 * Options supplied before init, as required by libmpv. On the tested AFTDCT31, software decode is
 * the verified path: MediaCodec init (H.264 and HEVC) deadlocks mpv's core thread before
 * FILE_LOADED, and the fallback restart loop broke phone cast sessions. If hardware decode is ever
 * re-enabled, the Surface ("wid") must stay attached before mpv_initialize; see
 * [MpvReceiverController.ensurePlayer]. Persistent idle keeps one initialized player ready for the
 * next cast.
 */
internal object MpvReceiverStartupPolicy {
    const val FORCE_WINDOW = "force-window"
    /**
     * The AFTDCT31's MediaCodec wrapper deadlocks mpv's core thread before FILE_LOADED for both
     * H.264 and HEVC (direct and copy-back modes), so the hardware probe/restart loop made every
     * cast black-screen the receiver. Software decode is the verified path on this device; keep
     * the flag so a future libmpv build with a fixed FFmpeg wrapper can re-enable hardware.
     */
    const val HARDWARE_DECODE_ENABLED = false

    val options: List<Pair<String, String>> = listOf(
        // This is a headless libmpv receiver. The phone resolves URLs and owns the control UI,
        // so desktop Lua clients only add load hooks and can disappear during a replacement while
        // a hook is active. Keep the receiver's native JSON-IPC/event path as the sole control
        // plane; no built-in script is needed for playback or casting.
        "load-scripts" to "no",
        "osc" to "no",
        "ytdl" to "no",
        "load-stats-overlay" to "no",
        "load-console" to "no",
        "load-auto-profiles" to "no",
        "load-select" to "no",
        "load-positioning" to "no",
        "load-commands" to "no",
        "load-context-menu" to "no",
        "profile" to "fast",
        // vo=gpu renders 1080p but presents 4K frames as black on this Mali GPU. gpu-next is the
        // renderer every shipped 0.1.9-0.1.13 APK used and handles 4K; keep it for the current
        // software-decode path (MediaCodec init still deadlocks on this device).
        "vo" to "gpu-next",
        "gpu-context" to "android",
        "opengl-es" to "yes",
        // Ask libplacebo/EGL to propagate each source's colorspace to Android's compositor. Without
        // this an HDR file can decode correctly but arrive at the Sony TV as an untagged SDR surface.
        "target-colorspace-hint" to "auto",
        // Re-testing the exact 0.1.11 hardware pair (gpu-next + mediacodec,mediacodec-copy) still
        // wedges mpv's core thread before FILE_LOADED on this Fire TV, with the Surface fix in
        // place. Software decode is the only path that reliably reaches playback here; gpu-next
        // makes it render 4K (vo=gpu presents 4K as black on this Mali GPU).
        "hwdec" to "no",
        "video-sync" to "audio",
        "framedrop" to "vo",
        "ao" to "audiotrack,opensles",
        // mpv's default (auto-safe) hands AudioTrack a STEREO layout, so every decoded 5.1/7.1
        // track was downmixed to 2.0 on this path — the AVR/soundbar received two channels while
        // the ExoPlayer path was bitstreaming E-AC3 5.1 to the same HDMI port. `auto` asks for the
        // source's own layout and lets the audiotrack AO negotiate down when the route can't take
        // it, so a stereo TV speaker still works.
        "audio-channels" to "auto",
        // Fire OS audiotrack can underrun during bitrate spikes or transient CPU load; a 2s output
        // buffer absorbs those gaps instead of cutting audio while video keeps playing.
        "audio-buffer" to "2.0",
        FORCE_WINDOW to "no",
        "idle" to "yes",
        "cache" to "yes",
        "demuxer-readahead-secs" to "15",
        "cache-secs" to "15",
        "demuxer-max-bytes" to "134217728",
        "demuxer-max-back-bytes" to "8388608",
        "demuxer-hysteresis-secs" to "5",
        "cache-pause" to "yes",
        "cache-pause-wait" to "2",
        "network-timeout" to "30",
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    )
}

internal enum class MpvMemoryPressureAction {
    None,
    TrimCache,
    RecyclePlayer,
}

/** Pure mapping of Android's visible-process trim levels (5/10/15) to bounded mpv actions. */
internal object MpvMemoryPressurePolicy {
    const val LOW_CACHE_SECONDS = 5.0
    const val LOW_CACHE_BYTES = "33554432"
    const val LOW_BACK_BYTES = "2097152"
    const val ACTIVE_CACHE_SECONDS = 8.0
    const val ACTIVE_CACHE_BYTES = "67108864"
    const val ACTIVE_BACK_BYTES = "4194304"

    private const val RUNNING_LOW = 10
    private const val RUNNING_CRITICAL = 15

    fun cacheSeconds(active: Boolean): Double =
        if (active) ACTIVE_CACHE_SECONDS else LOW_CACHE_SECONDS

    fun cacheBytes(active: Boolean): String =
        if (active) ACTIVE_CACHE_BYTES else LOW_CACHE_BYTES

    fun backBytes(active: Boolean): String =
        if (active) ACTIVE_BACK_BYTES else LOW_BACK_BYTES

    fun action(level: Int, active: Boolean): MpvMemoryPressureAction = when {
        // Fire OS reports RUNNING_CRITICAL while a visible decoder is healthy. Destroying the
        // active player here drops the cast and makes the phone briefly report the receiver as
        // offline. Shrink the cache first; title replacement only retires a player when recovery
        // requires a fresh native instance, and the retirement gate prevents unbounded buildup.
        level >= RUNNING_CRITICAL -> MpvMemoryPressureAction.TrimCache
        // RUNNING_LOW fires constantly during 4K playback. Trimming a live decoder into a
        // rebuffer loop makes audio underrun and video stutter, so leave the active pipeline alone
        // until the warning becomes critical; idle players can still shrink immediately.
        level >= RUNNING_LOW -> if (active) MpvMemoryPressureAction.None else MpvMemoryPressureAction.TrimCache
        else -> MpvMemoryPressureAction.None
    }
}

/**
 * Pure visibility-generation guard for a queued open. JVM tests cover this predicate only;
 * native libmpv scheduling and authenticated HLS playback remain manual Android TV test gates.
 */
internal object MpvSurfaceGenerationPolicy {
    fun isReadyForLoad(
        openGeneration: Long,
        currentGeneration: Long,
        surfaceReady: Boolean,
    ): Boolean = surfaceReady && openGeneration == currentGeneration
}

/** Internal request shaping kept separate from libmpv so it can be JVM-tested. */
internal object MpvReceiverRequestPolicy {
    private val headerName = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
    private const val OPTION_FORCE_MEDIA_TITLE = "force-media-title"
    private const val OPTION_HTTP_HEADER_FIELDS = "http-header-fields"
    private const val OPTION_START = "start"

    /** mpv's own word for "play from the beginning". */
    private const val START_NONE = "none"

    /**
     * @param startSeconds where the phone asked this title to begin, or null for the beginning.
     *   Passing it here is what stops mpv opening at 0 and being seeked afterwards — the same waste
     *   the ExoPlayer engine used to pay: a buffer filled at the wrong position and thrown away,
     *   plus a frame of the wrong scene on the way past.
     */
    fun loadPlan(
        title: String?,
        headers: Map<String, String>,
        startSeconds: Double? = null,
    ): Result<MpvLoadPlan> {
        val fields = headers.entries
            .sortedBy { (name, _) -> name.lowercase(Locale.ROOT) }
            .map { (name, value) ->
                if (!headerName.matches(name) || value.contains('\r') || value.contains('\n') || value.contains('\u0000')) {
                    return Result.failure(MpvReceiverFailure("Invalid media headers."))
                }

                // http-header-fields is an MPV list option. Escape separators so a legal HTTP
                // value cannot be interpreted as an extra field by the option parser.
                "$name: ${value.replace("\\", "\\\\").replace(",", "\\,")}"
            }
        val httpHeaderFields = fields.joinToString(",")
        return Result.success(
            MpvLoadPlan(
                title = title.orEmpty(),
                httpHeaderFields = httpHeaderFields,
                startValue = startValue(startSeconds),
                options = listOf(
                    "$OPTION_FORCE_MEDIA_TITLE=${fixedLengthValue(title.orEmpty())}",
                    "$OPTION_HTTP_HEADER_FIELDS=${fixedLengthValue(httpHeaderFields)}",
                    "$OPTION_START=${fixedLengthValue(startValue(startSeconds))}",
                ).joinToString(","),
            ),
        )
    }

    /**
     * mpv's `start` value for this title: a whole number of seconds, or `none` for the beginning.
     *
     * `none` is written explicitly rather than omitted. On the IPC path `start` is a GLOBAL
     * property, so a title that leaves it set would hand its resume point to the next title. Every
     * open stating its own answer makes that impossible, with no cleanup step to forget.
     */
    internal fun startValue(startSeconds: Double?): String = startSeconds
        ?.takeIf { it.isFinite() && it > 0.0 }
        ?.let { "%.3f".format(Locale.ROOT, it) }
        ?: START_NONE

    /** Prevent values from splitting loadfile's outer comma-delimited options map. */
    internal fun fixedLengthValue(value: String): String =
        "%${value.toByteArray(Charsets.UTF_8).size}%$value"

    fun seek(command: SeekCommand, snapshot: ReceiverSnapshot): MpvSeekPlan? =
        when (command) {
            is SeekCommand.Percentage -> command.value
                .takeIf { it.isFinite() && it in 0.0..100.0 }
                ?.let { percentage ->
                    val expectedOffset = snapshot.durationSeconds
                        .takeIf { it.isFinite() && it > 0.0 }
                        ?.let { duration -> duration * (percentage / 100.0) - snapshot.positionSeconds }
                    MpvSeekPlan(
                        command = arrayOf("seek", percentage.toString(), "absolute-percent"),
                        expectedOffsetSeconds = expectedOffset,
                    )
                }

            is SeekCommand.RelativeSeconds -> command.value
                .takeIf { it.isFinite() }
                ?.let { offset ->
                    MpvSeekPlan(
                        // Remote and phone skips are explicit user requests. Exact mode avoids
                        // sparse keyframes turning a 10-second skip into a one-second no-op.
                        command = arrayOf("seek", offset.toString(), "relative+exact"),
                        expectedOffsetSeconds = offset,
                    )
                }

            is SeekCommand.AbsoluteSeconds -> command.value
                .takeIf { it.isFinite() && it >= 0.0 }
                ?.let { position ->
                    MpvSeekPlan(
                        command = arrayOf("seek", position.toString(), "absolute"),
                        expectedOffsetSeconds = position - snapshot.positionSeconds,
                    )
                }
        }

    fun isSupportedSpeed(speed: Int): Boolean = speed in 1..32

    fun isSupportedVolume(volume: Int): Boolean = volume in 0..100

    fun action(action: String): Array<String>? =
        when (action.trim().lowercase(Locale.ROOT)) {
            "left" -> arrayOf("keypress", "LEFT")
            "right" -> arrayOf("keypress", "RIGHT")
            "up" -> arrayOf("keypress", "UP")
            "down" -> arrayOf("keypress", "DOWN")
            "select", "enter" -> arrayOf("keypress", "ENTER")
            "back", "escape" -> arrayOf("keypress", "ESC")
            "osd", "toggleosd" -> arrayOf("cycle", "osd-level")
            "showsubtitles" -> arrayOf("cycle", "sid")
            else -> null
        }
}

/**
 * Per-open native options. Its string representation intentionally redacts every value so a
 * future diagnostic cannot expose a stream title or credential by accident.
 */
internal class MpvLoadPlan(
    private val title: String,
    private val httpHeaderFields: String,
    /** mpv's `start` for this title: seconds, or `none`. Never absent — see `startValue`. */
    private val startValue: String,
    val options: String,
) {
    fun loadFileCommand(url: String): Array<String> =
        arrayOf(COMMAND_LOAD_FILE, url, LOAD_FILE_REPLACE, LOAD_FILE_AUTO_INDEX, options)

    /**
     * JSON IPC does not reliably accept MPV's fifth loadfile option map on the Fire OS AAR.
     * Set the request-scoped HTTP header list first, then issue the minimal loadfile command. The
     * empty assignment is intentional: it prevents a panel User-Agent from leaking into the next
     * headerless title.
     */
    fun loadFileIpcCommands(url: String): List<JsonArray> = listOf(
        buildJsonArray {
            add(JsonPrimitive(COMMAND_SET_PROPERTY))
            add(JsonPrimitive(PROPERTY_HTTP_HEADER_FIELDS))
            add(JsonPrimitive(httpHeaderFields))
        },
        // Set BEFORE the load: mpv reads `start` when it opens the file. Every open states its own
        // value, including `none`, so one title's resume point can never reach the next one.
        buildJsonArray {
            add(JsonPrimitive(COMMAND_SET_PROPERTY))
            add(JsonPrimitive(PROPERTY_START))
            add(JsonPrimitive(startValue))
        },
        loadFileIpcCommand(url),
    )

    fun loadFileIpcCommand(url: String): JsonArray = buildJsonArray {
        add(JsonPrimitive(COMMAND_LOAD_FILE))
        add(JsonPrimitive(url))
        add(JsonPrimitive(LOAD_FILE_REPLACE))
        add(JsonPrimitive(LOAD_FILE_AUTO_INDEX))
    }

    override fun toString(): String = "MpvLoadPlan(options=<redacted>)"

    private companion object {
        private const val COMMAND_LOAD_FILE = "loadfile"
        private const val COMMAND_SET_PROPERTY = "set_property"
        private const val LOAD_FILE_REPLACE = "replace"
        private const val LOAD_FILE_AUTO_INDEX = "-1"
        private const val PROPERTY_HTTP_HEADER_FIELDS = "http-header-fields"
        private const val PROPERTY_START = "start"
        private const val OPTION_FORCE_MEDIA_TITLE = "force-media-title"
        private const val OPTION_HTTP_HEADER_FIELDS = "http-header-fields"
    }
}

internal data class MpvSeekPlan(
    val command: Array<String>,
    val expectedOffsetSeconds: Double?,
)

/** A deliberately generic failure so Result callers never receive stream URLs or header values. */
internal class MpvReceiverFailure(message: String) : IllegalStateException(message)
