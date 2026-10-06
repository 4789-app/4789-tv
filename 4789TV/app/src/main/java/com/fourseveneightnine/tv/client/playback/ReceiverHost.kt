package com.fourseveneightnine.tv.client.playback

import android.app.Activity
import android.hardware.display.DisplayManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import com.fourseveneightnine.tv.client.AppGraph
import com.fourseveneightnine.tv.client.receiver.ReceiverService
import com.fourseveneightnine.tv.player.AndroidDirectAudioProbe
import com.fourseveneightnine.tv.player.AndroidMediaCapabilities
import com.fourseveneightnine.tv.player.AudioCodecProbe
import com.fourseveneightnine.tv.player.DisplayModeInfo
import com.fourseveneightnine.tv.player.PlaybackDiagnostics
import com.fourseveneightnine.tv.player.ReceiverEnginePolicy
import com.fourseveneightnine.tv.player.ReceiverPlaybackPhase
import com.fourseveneightnine.tv.player.ResumePoint
import com.fourseveneightnine.tv.player.ResumePolicy
import com.fourseveneightnine.tv.player.SurfaceFrameRatePolicy
import com.fourseveneightnine.tv.player.VideoCodecProbe
import com.fourseveneightnine.tv.protocol.EngineOverridePort
import com.fourseveneightnine.tv.protocol.ExternalHandoffContext
import com.fourseveneightnine.tv.protocol.ExternalPlayerPort
import com.fourseveneightnine.tv.protocol.InstalledExternalPlayer
import com.fourseveneightnine.tv.protocol.NowPlayingArtwork
import com.fourseveneightnine.tv.protocol.PhoneRecents
import com.fourseveneightnine.tv.protocol.ReceiverEvent
import com.fourseveneightnine.tv.protocol.ReceiverIdentity
import com.fourseveneightnine.tv.protocol.ReceiverSnapshot
import com.fourseveneightnine.tv.protocol.RpcDispatcher
import com.fourseveneightnine.tv.client.data.RecentItem
import com.fourseveneightnine.tv.discovery.NsdAdvertisementStatus
import com.fourseveneightnine.tv.player.ExternalPlayerIntentPolicy
import com.fourseveneightnine.tv.player.SwappableReceiverController
import com.fourseveneightnine.tv.settings.TVSettingsPairingState
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics
import com.fourseveneightnine.tv.transport.KodiTransport
import com.fourseveneightnine.tv.ui.ReceiverLifecycleAction
import com.fourseveneightnine.tv.ui.ReceiverLifecyclePlanner
import com.fourseveneightnine.tv.ui.ReceiverLifecycleState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the receiver half of the app is doing, for the rail's cast chip and About. */
internal sealed interface ReceiverState {
    data object Starting : ReceiverState
    data object Ready : ReceiverState
    data class Error(val message: String) : ReceiverState
}

/**
 * Everything the old Activity did that was not drawing.
 *
 * Ports, transport, discovery, the RPC dispatcher, the media session, auto frame rate, resume
 * points, the recents mirror and the capability probe all live here, tied to one Activity's
 * visibility. Nothing in this file knows what the screen looks like, and nothing in the Compose
 * tree reaches past [PlaybackSession] to the engine.
 *
 * The lifecycle rule is unchanged and is the reason this class exists at all: **a receiver session
 * is valid only while the Activity is visible and its video Surface is ready.** A phone must never
 * reach a television that cannot show the video it asks for.
 */
internal class ReceiverHost(
    private val activity: Activity,
    private val graph: AppGraph,
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)
    private val sessionMutex = Mutex()

    /**
     * Wire delivery has its own ordered queue. A dead phone can take the bounded eviction window
     * without holding the television's own phase collector behind network I/O.
     */
    private val playbackStatusNotifications = Channel<Pair<String, String>>(
        capacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val controller: SwappableReceiverController get() = graph.controller
    private val session: PlaybackSession get() = graph.playback

    private val dispatcher: RpcDispatcher
    private val transport: KodiTransport
    private val mediaSession: MediaSession
    private val audioManager: AudioManager = activity.getSystemService(AudioManager::class.java)

    private var surfaceView: SurfaceView? = null

    private var activityStarted = false
    private var surfaceAttached = false
    private var surfaceReady = false
    private var transportRunning = false
    private var advertisingRequested = false
    private var playbackActive = false
    private var frameRateEligible = false

    private var currentDiagnostics = PlaybackDiagnostics()
    private var mediaCapabilities = AndroidMediaCapabilities(emptyList(), emptyList())
    private var appliedFrameRate = 0f
    private var originalDisplayModeId = 0
    private var autoFrameRateEnabled = true

    private var surfaceReadyTimeout: Job? = null
    private var capabilityProbeJob: Job? = null
    private var networkMonitorStartJob: Job? = null
    private var settingsSyncJob: Job? = null
    private var resumeSaveJob: Job? = null

    /** Written once per open; the position rides the slow resume tick, not the controls poll. */
    private var recentsRowUrl: String? = null

    /** When the picture for the current title first reached the screen, or 0 while nothing is up. */
    private var pictureUpAtMillis = 0L
    private var playbackHasStarted = false

    private val _state = MutableStateFlow<ReceiverState>(ReceiverState.Starting)
    val state: StateFlow<ReceiverState> = _state.asStateFlow()

    private val _settingsConfigured = MutableStateFlow(false)
    val settingsConfigured: StateFlow<Boolean> = _settingsConfigured.asStateFlow()

    private val _settingsLoaded = MutableStateFlow(false)
    val settingsLoaded: StateFlow<Boolean> = _settingsLoaded.asStateFlow()

    /** The crumb from the last session, if it is still worth offering. */
    var resumeOffer: ResumePoint? = null
        private set

    val receiverName: String = graph.deviceIdentity.displayName

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refreshMediaCapabilities()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refreshMediaCapabilities()
    }

    init {
        autoFrameRateEnabled = graph.presentationPreferences.getBoolean(AUTO_FRAME_RATE_KEY, true)
        dispatcher = RpcDispatcher(
            session,
            ReceiverIdentity(
                name = graph.deviceIdentity.displayName,
                uuid = graph.advertiser.receiverUuid,
                manufacturer = graph.deviceIdentity.manufacturer,
                model = graph.deviceIdentity.model,
                deviceName = graph.deviceIdentity.deviceName,
                deviceKind = graph.deviceIdentity.kind,
            ),
            engineOverrides = object : EngineOverridePort {
                override fun setOverride(normalizedOverride: String) {
                    // commit(), NOT apply(). The setting only takes effect on the next start, so
                    // the very next thing that happens is a restart — and an async write does not
                    // survive `am force-stop`, which SIGKILLs the process before it flushes.
                    val stored = graph.enginePreferences.edit()
                        .putString(ENGINE_OVERRIDE_KEY, ReceiverEnginePolicy.storedValue(normalizedOverride))
                        .commit()
                    ReceiverDiagnostics.record("engine.override", "$normalizedOverride persisted=$stored")
                }

                override fun currentEngineName(): String =
                    controller.engine.name.lowercase(java.util.Locale.US)

                override suspend fun switchNow(engineName: String): Boolean = when (
                    engineName.lowercase(java.util.Locale.US)
                ) {
                    "exo" -> controller.requestEngine(com.fourseveneightnine.tv.player.ReceiverEngine.Exo)
                    else -> false
                }
            },
            externalPlayers = object : ExternalPlayerPort {
                override fun installedPlayers(): List<InstalledExternalPlayer> =
                    ExternalPlayerIntentPolicy.installedPlayers(activity.applicationContext)

                override suspend fun launch(
                    url: String,
                    title: String?,
                    targetPackage: String?,
                    handoff: ExternalHandoffContext,
                ): Boolean = withContext(Dispatchers.Main) {
                    ExternalPlayerIntentPolicy.launch(
                        context = activity,
                        url = url,
                        title = title,
                        targetPackage = targetPackage,
                        handoff = ExternalPlayerIntentPolicy.HandoffContext(
                            positionMillis = handoff.positionMillis,
                            subtitleURL = handoff.subtitleURL,
                            subtitleName = handoff.subtitleName,
                            headers = handoff.headers,
                        ),
                    )
                }
            },
        )
        transport = KodiTransport(
            dispatcher = dispatcher,
            events = controller.events,
            settingsPairingEndpoint = graph.pairing,
        )
        mediaSession = createMediaSession()
        ReceiverDiagnostics.record("host.created", "engine=${controller.engine}")
        observeReceiverState()
    }

    // ------------------------------------------------------------------ lifecycle

    fun bindSurfaceView(view: SurfaceView) {
        surfaceView = view
    }

    fun onStart() {
        ReceiverDiagnostics.record("host.onStart.begin")
        activityStarted = true
        scope.launch {
            graph.pairing.restorePersistedState()
            _settingsLoaded.value = true
            _settingsConfigured.value = graph.pairing.state.value is TVSettingsPairingState.Saved
        }
        // A sync from the phone changes which add-ons and catalogs this box should use. Collect it
        // for as long as the Activity is started, so a later sync is applied rather than ignored
        // until the next launch.
        settingsSyncJob?.cancel()
        settingsSyncJob = scope.launch {
            graph.pairing.settingsApplied.collect {
                _settingsConfigured.value = graph.pairing.state.value is TVSettingsPairingState.Saved
                ReceiverDiagnostics.record("settings.applied", "configured=${_settingsConfigured.value}")
            }
        }
        // Network callback registration is a synchronous Binder round-trip on some Fire OS builds
        // and is not needed to draw the first screen.
        networkMonitorStartJob?.cancel()
        networkMonitorStartJob = scope.launch(Dispatchers.Default) { graph.networkMonitor.start() }
        refreshReceiverCapabilities()
        mediaSession.isActive = true
        refreshMediaCapabilities()
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        _state.value = ReceiverState.Starting
        loadResumeOffer()
        attachCurrentSurfaceIfAvailable()
        reconcile()
        ReceiverDiagnostics.record("host.onStart.complete")
    }

    fun onStop() {
        activityStarted = false
        capabilityProbeJob?.cancel()
        capabilityProbeJob = null
        mediaSession.isActive = false
        runCatching { audioManager.unregisterAudioDeviceCallback(audioDeviceCallback) }
        clearContentFrameRate()
        // Withdraw discovery synchronously before transport shutdown begins.
        advertisingRequested = false
        graph.advertiser.stop()
        // Revoke readiness before the mutex-bound teardown so a saved-IP Player.Open racing Home
        // cannot retain a usable video target while transport shutdown is still pending.
        controller.detachSurface()
        surfaceReadyTimeout?.cancel()
        surfaceReadyTimeout = null
        // Teardown is exactly the moment worth remembering.
        saveResumePoint()
        stopResumeSaving()
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        stopReceiverForVisibilityLoss()
    }

    fun onDestroy() {
        ReceiverDiagnostics.record("host.onDestroy")
        settingsSyncJob?.cancel()
        networkMonitorStartJob?.cancel()
        capabilityProbeJob?.cancel()
        surfaceReadyTimeout?.cancel()
        graph.networkMonitor.close()
        graph.advertiser.close()
        controller.detachSurface()
        mediaSession.release()
        playbackStatusNotifications.close()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { graph.pairing.close() }
        }
        ReceiverService.stop(activity)
        scope.cancel()
    }

    fun onTrimMemory(level: Int) = controller.handleMemoryPressure(level)

    // ------------------------------------------------------------------ surface

    fun onSurfaceCreated(holder: SurfaceHolder) {
        ReceiverDiagnostics.record("surface.created", "valid=${holder.surface.isValid}")
        if (!activityStarted) return
        attachSurface(holder)
        reconcile()
    }

    fun onSurfaceChanged(holder: SurfaceHolder, width: Int, height: Int) {
        ReceiverDiagnostics.record("surface.changed", "valid=${holder.surface.isValid} size=${width}x$height")
        controller.updateSurfaceSize(width, height)
        if (activityStarted && !surfaceAttached) {
            attachSurface(holder)
            reconcile()
        }
    }

    fun onSurfaceDestroyed() {
        ReceiverDiagnostics.record("surface.destroyed")
        clearContentFrameRate()
        surfaceAttached = false
        surfaceReady = false
        surfaceReadyTimeout?.cancel()
        surfaceReadyTimeout = null
        // Do not leave a discoverable receiver behind after Android invalidates its video target.
        advertisingRequested = false
        graph.advertiser.stop()
        controller.detachSurface()
        if (activityStarted) _state.value = ReceiverState.Starting
        stopReceiverForVisibilityLoss()
    }

    private fun attachCurrentSurfaceIfAvailable() {
        surfaceView?.holder?.let { attachSurface(it) }
    }

    private fun attachSurface(holder: SurfaceHolder) {
        if (surfaceAttached || !holder.surface.isValid) {
            ReceiverDiagnostics.record(
                "surface.attach.skipped",
                "alreadyAttached=$surfaceAttached valid=${holder.surface.isValid}",
            )
            return
        }
        surfaceAttached = true
        surfaceReady = false
        val frame = holder.surfaceFrame
        ReceiverDiagnostics.record("surface.attach.requested", "size=${frame.width()}x${frame.height()}")
        session.attachSurfaceHolder(holder, frame.width(), frame.height())
        resumePendingSoftwareOpenIfNeeded()
        scheduleSurfaceReadyTimeout()
    }

    /** Resumes a cast that was restarted after a hardware-decode stall, using software decode. */
    private fun resumePendingSoftwareOpenIfNeeded() {
        val pending = controller.consumePendingSoftwareOpen() ?: return
        scope.launch {
            controller.surfaceReady.first { it }
            controller.openWithSoftware(pending)
        }
    }

    private fun scheduleSurfaceReadyTimeout() {
        surfaceReadyTimeout?.cancel()
        surfaceReadyTimeout = scope.launch {
            delay(SURFACE_READY_TIMEOUT_MILLIS)
            if (activityStarted && surfaceAttached && !surfaceReady) {
                _state.value = ReceiverState.Error(SURFACE_START_ERROR)
            }
        }
    }

    // ------------------------------------------------------------------ session reconciliation

    private fun reconcile() {
        scope.launch { sessionMutex.withLock { reconcileLocked() } }
    }

    private suspend fun reconcileLocked() {
        while (true) {
            attachSurfaceForReconciliationIfNeeded()
            when (
                ReceiverLifecyclePlanner.next(
                    ReceiverLifecycleState(
                        activityStarted = activityStarted,
                        surfaceReady = surfaceReady,
                        transportRunning = transportRunning,
                        advertisingRequested = advertisingRequested,
                    ),
                )
            ) {
                ReceiverLifecycleAction.StartTransport -> {
                    _state.value = ReceiverState.Starting
                    if (!startTransportLocked()) return
                }

                ReceiverLifecycleAction.StartAdvertising -> {
                    advertisingRequested = true
                    if (!graph.advertiser.start()) {
                        advertisingRequested = false
                        _state.value = ReceiverState.Error(NETWORK_START_ERROR)
                    }
                    return
                }

                ReceiverLifecycleAction.StopAll -> {
                    stopReceiverForVisibilityLossLocked()
                    // A rapid onStop → onStart can leave a valid holder after teardown; loop so
                    // the reconciliation above reattaches it without another Surface callback.
                    continue
                }

                ReceiverLifecycleAction.Idle -> return
            }
        }
    }

    private fun attachSurfaceForReconciliationIfNeeded() {
        val holder = surfaceView?.holder ?: return
        val state = ReceiverLifecycleState(
            activityStarted = activityStarted,
            surfaceReady = surfaceReady,
            transportRunning = transportRunning,
            advertisingRequested = advertisingRequested,
            surfaceAttached = surfaceAttached,
            surfaceAvailable = holder.surface.isValid,
        )
        if (ReceiverLifecyclePlanner.shouldAttachSurface(state)) attachSurface(holder)
    }

    private suspend fun startTransportLocked(): Boolean {
        // Treat an in-flight bind as running for teardown purposes: if destruction cancels this
        // coroutine after Ktor bound its ports, the idempotent stop still has to run.
        transportRunning = true
        return try {
            withContext(Dispatchers.IO) { transport.start() }
            // The foreground service exists so the bound ports survive whatever else the
            // television decides to do while the viewer is on a browse screen.
            ReceiverService.start(activity)
            true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            transportRunning = false
            _state.value = ReceiverState.Error(NETWORK_START_ERROR)
            false
        }
    }

    /** Runs teardown even if destruction cancels ordinary jobs during Ktor shutdown. */
    private fun stopReceiverForVisibilityLoss() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                sessionMutex.withLock { stopReceiverForVisibilityLossLocked() }
            }
        }
    }

    /** Required stop order: revoke Surface readiness, discovery, transport, then player. */
    private suspend fun stopReceiverForVisibilityLossLocked() {
        controller.detachSurface()
        surfaceAttached = false
        surfaceReady = false
        advertisingRequested = false
        graph.advertiser.stop()
        stopTransportLocked()
        stopPlaybackBestEffortLocked()
        playbackActive = false
    }

    private suspend fun stopTransportLocked() {
        if (!transportRunning) return
        try {
            withContext(Dispatchers.IO) { transport.stop() }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // Ktor stop is best effort; no server is retained after it.
        } finally {
            transportRunning = false
            ReceiverService.stop(activity)
        }
    }

    private suspend fun stopPlaybackBestEffortLocked() {
        try {
            controller.stop()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // The controller can already be closing when Android tears down a Surface.
        }
        playbackActive = false
    }

    // ------------------------------------------------------------------ observers

    private fun observeReceiverState() {
        scope.launch {
            for ((status, message) in playbackStatusNotifications) {
                transport.notifyPlaybackStatus(status, message, controller.snapshot())
            }
        }

        scope.launch {
            controller.surfaceReady.collect { ready ->
                surfaceReady = ready
                if (ready) {
                    surfaceReadyTimeout?.cancel()
                    surfaceReadyTimeout = null
                    if (activityStarted && _state.value !is ReceiverState.Error) {
                        _state.value = ReceiverState.Starting
                    }
                } else {
                    if (transportRunning || advertisingRequested) {
                        advertisingRequested = false
                        graph.advertiser.stop()
                    }
                    if (activityStarted && surfaceAttached) scheduleSurfaceReadyTimeout()
                }
                reconcile()
            }
        }

        scope.launch {
            controller.playbackPhase.collect { phase ->
                if (phase is ReceiverPlaybackPhase.Playing || phase is ReceiverPlaybackPhase.Paused) {
                    if (!playbackHasStarted) pictureUpAtMillis = android.os.SystemClock.elapsedRealtime()
                    playbackHasStarted = true
                } else if (phase is ReceiverPlaybackPhase.Opening) {
                    playbackHasStarted = false
                    pictureUpAtMillis = 0L
                }
                val wireStatus = when (phase) {
                    is ReceiverPlaybackPhase.Opening -> when (phase.stage) {
                        com.fourseveneightnine.tv.protocol.ReceiverPreparationStage.RESOLVING ->
                            "resolving" to "Resolving source"
                        com.fourseveneightnine.tv.protocol.ReceiverPreparationStage.PREPARING ->
                            "preparing" to "Preparing on TV"
                        else -> "opening" to "Opening on TV"
                    }
                    is ReceiverPlaybackPhase.Buffering -> "buffering" to "TV is buffering"
                    is ReceiverPlaybackPhase.Playing -> "playing" to "Playing on TV"
                    else -> null
                }
                if (wireStatus != null) playbackStatusNotifications.trySend(wireStatus)
            }
        }

        scope.launch {
            controller.events.collect { event ->
                playbackActive = event.isPlaybackActive()
                frameRateEligible = event.playbackSpeed() == 1
                updateMediaSession(event)
                if (autoFrameRateEnabled && frameRateEligible) {
                    applyContentFrameRate(currentDiagnostics)
                } else {
                    clearContentFrameRate()
                }
                if (playbackActive) {
                    resumeOffer = null
                    startResumeSaving()
                    pushRecentRow()
                } else {
                    stopResumeSaving()
                    // The next open gets its own row; without this a re-open of the same title
                    // would never be re-pushed after the phone had rewritten the shelf.
                    recentsRowUrl = null
                }
            }
        }

        // The phone's Continue-Watching list, mirrored onto this box (`X4789.SetRecents`).
        // Phone rows replace phone rows wholesale; rows the box played itself survive.
        scope.launch {
            PhoneRecents.entries.collect { entries ->
                if (entries == null) return@collect
                withContext(Dispatchers.IO) {
                    graph.recentsStore.replacePhoneRecents(
                        entries.map { entry ->
                            RecentItem(
                                url = entry.url,
                                title = entry.title,
                                subtitle = entry.subtitle,
                                posterUrl = entry.posterUrl,
                                backdropUrl = entry.landscapeUrl,
                                overview = entry.overview,
                                positionMillis = entry.positionMillis,
                                durationMillis = entry.durationMillis,
                                timestamp = entry.timestamp,
                                fromPhone = true,
                            )
                        },
                    )
                }
                ReceiverDiagnostics.record("recents.phone", "rows=${entries.size}")
            }
        }

        scope.launch {
            controller.diagnostics.collect { diagnostics ->
                currentDiagnostics = diagnostics
                if (autoFrameRateEnabled && diagnostics.active && frameRateEligible) {
                    applyContentFrameRate(diagnostics)
                } else if (!diagnostics.active) {
                    clearContentFrameRate()
                }
            }
        }

        scope.launch {
            graph.advertiser.status.collect { status ->
                if (!activityStarted) return@collect
                var shouldReconcile = false
                when (status) {
                    is NsdAdvertisementStatus.Advertising ->
                        if (surfaceReady && transportRunning && advertisingRequested) {
                            _state.value = ReceiverState.Ready
                        }

                    is NsdAdvertisementStatus.Error ->
                        if (advertisingRequested) _state.value = ReceiverState.Error(NETWORK_START_ERROR)

                    NsdAdvertisementStatus.Starting, NsdAdvertisementStatus.Stopping ->
                        if (advertisingRequested && _state.value !is ReceiverState.Error) {
                            _state.value = ReceiverState.Starting
                        }

                    NsdAdvertisementStatus.Stopped -> {
                        // Ignore a stale emission if a synchronous start already advanced the
                        // advertiser; a Ready state must never outlive its registration.
                        if (advertisingRequested &&
                            graph.advertiser.status.value is NsdAdvertisementStatus.Stopped
                        ) {
                            advertisingRequested = false
                            _state.value = ReceiverState.Starting
                            shouldReconcile = true
                        } else if (_state.value is ReceiverState.Ready) {
                            _state.value = ReceiverState.Starting
                        }
                    }
                }
                if (shouldReconcile) reconcile()
            }
        }
    }

    /** Tells the phone a local press moved the film, so its remote does not drift. */
    fun notifyLocalControl(action: String) {
        scope.launch { transport.notifyLocalControl(action, controller.snapshot()) }
    }

    // ------------------------------------------------------------------ recents and resume

    private fun pushRecentRow() {
        val media = controller.lastOpenMedia() ?: return
        if (media.url == recentsRowUrl) return
        recentsRowUrl = media.url
        val artwork = NowPlayingArtwork.art.value
        val title = media.title?.takeIf(String::isNotBlank) ?: "Untitled stream"
        scope.launch(Dispatchers.IO) {
            graph.recentsStore.push(
                RecentItem(
                    url = media.url,
                    title = title,
                    subtitle = media.subtitle,
                    posterUrl = artwork?.posterURL,
                    backdropUrl = artwork?.landscapeURL,
                ),
            )
        }
    }

    /**
     * Keeps a crumb of the live playback on disk, on a slow tick. This is a "the app closed by
     * accident" safety net, not a progress database: the phone still owns Continue Watching.
     */
    private fun startResumeSaving() {
        if (resumeSaveJob?.isActive == true) return
        resumeSaveJob = scope.launch {
            while (true) {
                delay(RESUME_SAVE_INTERVAL_MILLIS)
                saveResumePoint()
            }
        }
    }

    private fun stopResumeSaving() {
        resumeSaveJob?.cancel()
        resumeSaveJob = null
    }

    private fun saveResumePoint() {
        val media = controller.lastOpenMedia() ?: return
        scope.launch {
            val snapshot = runCatching { controller.snapshot() }.getOrNull() ?: return@launch
            val positionMillis = (snapshot.positionSeconds * 1_000).toLong()
            val durationMillis = (snapshot.durationSeconds * 1_000).toLong()
            if (!ResumePolicy.shouldStore(positionMillis, durationMillis)) return@launch
            val art = NowPlayingArtwork.art.value
            withContext(Dispatchers.IO) {
                if (media.url == recentsRowUrl) {
                    graph.recentsStore.updatePosition(media.url, positionMillis, durationMillis)
                }
                graph.resumeStore.save(
                    ResumePoint(
                        url = media.url,
                        title = media.title,
                        subtitle = media.subtitle,
                        headers = media.headers,
                        positionMillis = positionMillis,
                        durationMillis = durationMillis,
                        savedAtMillis = System.currentTimeMillis(),
                        artworkURL = art?.landscapeURL,
                        posterURL = art?.posterURL,
                    ),
                )
            }
        }
    }

    private fun loadResumeOffer() {
        val stored = graph.resumeStore.load()
        resumeOffer = stored?.takeIf { ResumePolicy.shouldOffer(it, System.currentTimeMillis()) }
        resumeOffer?.let {
            ReceiverDiagnostics.record("resume.offer", "pos=${it.positionMillis / 1000}s")
        }
    }

    // ------------------------------------------------------------------ media session

    private fun createMediaSession(): MediaSession =
        MediaSession(activity, MEDIA_SESSION_TAG).apply {
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS,
            )
            setCallback(
                object : MediaSession.Callback() {
                    override fun onPlay() { scope.launch { session.togglePlayPauseIfPaused() } }
                    override fun onPause() { scope.launch { session.togglePlayPauseIfPlaying() } }
                    override fun onStop() { scope.launch { session.stop() } }
                    override fun onRewind() { scope.launch { session.seekRelative(-REMOTE_SEEK_SECONDS) } }
                    override fun onFastForward() { scope.launch { session.seekRelative(REMOTE_SEEK_SECONDS) } }
                    override fun onSeekTo(pos: Long) { scope.launch { session.seekTo(pos) } }
                },
            )
            setPlaybackState(
                PlaybackState.Builder()
                    .setActions(MEDIA_SESSION_ACTIONS)
                    .setState(PlaybackState.STATE_NONE, 0L, 0f)
                    .build(),
            )
        }

    private fun updateMediaSession(event: ReceiverEvent) {
        val snapshot = event.snapshotOf()
        val state = when {
            !snapshot.active -> PlaybackState.STATE_STOPPED
            snapshot.speed == 0 -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_PLAYING
        }
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(MEDIA_SESSION_ACTIONS)
                .setState(
                    state,
                    (snapshot.positionSeconds.coerceAtLeast(0.0) * 1_000.0).toLong(),
                    if (state == PlaybackState.STATE_PLAYING) snapshot.speed.toFloat() else 0f,
                )
                .build(),
        )
    }

    // ------------------------------------------------------------------ capabilities

    private fun refreshMediaCapabilities() {
        val activeDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay
        }
        mediaCapabilities = AndroidMediaCapabilities.probe(activity, activeDisplay)
        controller.refreshAudioRoute()
    }

    fun mediaCapabilitiesSummary(): String = mediaCapabilities.summary()

    /**
     * Populate the phone-facing decoder profile without holding up window creation. MediaCodecList
     * and direct-playback support are vendor IPC calls; the onn 4K box has repeatedly shown them
     * on the critical path during cold start. An empty list is the protocol's explicit "unknown".
     */
    private fun refreshReceiverCapabilities() {
        capabilityProbeJob?.cancel()
        capabilityProbeJob = scope.launch(Dispatchers.Default) {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            val hardwareVideoCodecs = VideoCodecProbe.hardwareDecoded()
            val audioDecodeCodecs = AudioCodecProbe.decoded()
            val audioPassthroughCodecs = AndroidDirectAudioProbe
                .supportedMpvCodecs(activity.applicationContext)
                .map { if (it == "dts-hd") "dtshd" else it }
            dispatcher.updateReceiverCapabilities(
                hardwareVideoCodecs = hardwareVideoCodecs,
                audioDecodeCodecs = audioDecodeCodecs,
                audioPassthroughCodecs = audioPassthroughCodecs,
            )
            ReceiverDiagnostics.record(
                "receiver.capabilities.ready",
                "video=${hardwareVideoCodecs.size} audio=${audioDecodeCodecs.size} " +
                    "passthrough=${audioPassthroughCodecs.size} " +
                    "elapsedMs=${android.os.SystemClock.elapsedRealtime() - startedAt}",
            )
        }
    }

    // ------------------------------------------------------------------ auto frame rate

    var autoFrameRate: Boolean
        get() = autoFrameRateEnabled
        set(value) {
            autoFrameRateEnabled = value
            graph.presentationPreferences.edit().putBoolean(AUTO_FRAME_RATE_KEY, value).apply()
            if (!value) clearContentFrameRate() else if (currentDiagnostics.active && frameRateEligible) {
                applyContentFrameRate(currentDiagnostics)
            }
        }

    /** Null until the picture is up; afterwards, how long it has been up. */
    private fun millisSincePictureUp(): Long? = pictureUpAtMillis
        .takeIf { it > 0L }
        ?.let { android.os.SystemClock.elapsedRealtime() - it }

    private fun applyContentFrameRate(diagnostics: PlaybackDiagnostics) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            applyContentFrameRateLegacy(diagnostics)
            return
        }
        val holder = surfaceView?.holder ?: return
        if (!holder.surface.isValid) return
        val rate = SurfaceFrameRatePolicy.validRate(diagnostics.framesPerSecond) ?: return
        if (kotlin.math.abs(appliedFrameRate - rate) < FRAME_RATE_EPSILON) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val strategy = if (
                    SurfaceFrameRatePolicy.allowNonSeamlessSwitch(diagnostics.durationSeconds) &&
                    SurfaceFrameRatePolicy.withinNonSeamlessWindow(millisSincePictureUp())
                ) {
                    val displayManager = activity.getSystemService(DisplayManager::class.java)
                    if (displayManager?.matchContentFrameRateUserPreference ==
                        DisplayManager.MATCH_CONTENT_FRAMERATE_ALWAYS
                    ) {
                        Surface.CHANGE_FRAME_RATE_ALWAYS
                    } else {
                        Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
                    }
                } else {
                    Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
                }
                holder.surface.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, strategy)
            } else {
                holder.surface.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            }
            appliedFrameRate = rate
        }
    }

    /**
     * Auto frame rate for televisions below API 30, where `Surface.setFrameRate` does not exist.
     * `preferredDisplayModeId` is the only refresh-rate control Fire OS 7 and the Hisense box have.
     * A mode change re-links HDMI, so it is gated on a ten-minute floor and on the picture not yet
     * being up: nobody wants a black flash mid-scene.
     */
    @Suppress("DEPRECATION")
    private fun applyContentFrameRateLegacy(diagnostics: PlaybackDiagnostics) {
        val rate = SurfaceFrameRatePolicy.validRate(diagnostics.framesPerSecond) ?: return
        if (!SurfaceFrameRatePolicy.allowNonSeamlessSwitch(diagnostics.durationSeconds)) return
        if (kotlin.math.abs(appliedFrameRate - rate) < FRAME_RATE_EPSILON) return
        if (!SurfaceFrameRatePolicy.withinNonSeamlessWindow(millisSincePictureUp())) {
            ReceiverDiagnostics.record("afr.legacy.tooLate", "fps=$rate")
            return
        }
        val activeDisplay = activity.windowManager.defaultDisplay ?: return
        val current = activeDisplay.mode ?: return
        val target = SurfaceFrameRatePolicy.selectModeId(
            modes = activeDisplay.supportedModes.map {
                DisplayModeInfo(
                    modeId = it.modeId,
                    width = it.physicalWidth,
                    height = it.physicalHeight,
                    refreshRate = it.refreshRate,
                )
            },
            currentModeId = current.modeId,
            currentWidth = current.physicalWidth,
            currentHeight = current.physicalHeight,
            framesPerSecond = diagnostics.framesPerSecond,
        )
        if (target == null) {
            // Not a failure: the panel is already on the best mode, or has nothing closer.
            ReceiverDiagnostics.record(
                "afr.legacy.skip",
                "fps=$rate current=${current.refreshRate}",
            )
            appliedFrameRate = rate
            return
        }
        runCatching {
            if (originalDisplayModeId == 0) originalDisplayModeId = current.modeId
            activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = target }
            appliedFrameRate = rate
            ReceiverDiagnostics.record("afr.legacy.applied", "fps=$rate modeId=$target")
        }
    }

    private fun clearContentFrameRate() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            clearContentFrameRateLegacy()
            return
        }
        if (appliedFrameRate == 0f) return
        runCatching {
            val holder = surfaceView?.holder
            if (holder != null && holder.surface.isValid) {
                holder.surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            }
        }
        appliedFrameRate = 0f
    }

    /**
     * Hand the panel back to the mode it was on. Leaving a 24 Hz mode applied after a film ends
     * makes the television's own interface stutter, and the viewer has no idea we caused it.
     */
    @Suppress("DEPRECATION")
    private fun clearContentFrameRateLegacy() {
        if (appliedFrameRate == 0f) return
        runCatching {
            activity.window.attributes = activity.window.attributes.apply {
                preferredDisplayModeId = originalDisplayModeId
            }
            ReceiverDiagnostics.record("afr.legacy.cleared", "modeId=$originalDisplayModeId")
        }
        appliedFrameRate = 0f
        originalDisplayModeId = 0
    }

    // ------------------------------------------------------------------ event helpers

    private fun ReceiverEvent.isPlaybackActive(): Boolean = when (this) {
        is ReceiverEvent.Play -> snapshot.active
        is ReceiverEvent.Pause -> snapshot.active
        is ReceiverEvent.Seek -> snapshot.active
        is ReceiverEvent.SpeedChanged -> snapshot.active
        is ReceiverEvent.Stop -> false
        // The video moved to another app on this TV: this receiver is no longer playing.
        is ReceiverEvent.ExternalHandoff -> false
        // A non-fatal error (silent-audio fallback) leaves playback running.
        is ReceiverEvent.Error -> !fatal && snapshot.active
        is ReceiverEvent.VolumeChanged -> snapshot.active
        is ReceiverEvent.LinkRefreshRequested -> snapshot.active
    }

    private fun ReceiverEvent.playbackSpeed(): Int = when (this) {
        is ReceiverEvent.Play -> snapshot.speed
        is ReceiverEvent.Pause -> snapshot.speed
        is ReceiverEvent.Seek -> snapshot.speed
        is ReceiverEvent.SpeedChanged -> snapshot.speed
        is ReceiverEvent.Stop -> 0
        is ReceiverEvent.ExternalHandoff -> 0
        is ReceiverEvent.Error -> if (fatal) 0 else snapshot.speed
        is ReceiverEvent.VolumeChanged -> snapshot.speed
        is ReceiverEvent.LinkRefreshRequested -> snapshot.speed
    }

    private fun ReceiverEvent.snapshotOf(): ReceiverSnapshot = when (this) {
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

    private companion object {
        const val ENGINE_OVERRIDE_KEY = "override"
        const val AUTO_FRAME_RATE_KEY = "auto_frame_rate_enabled"
        const val REMOTE_SEEK_SECONDS = 10.0
        const val RESUME_SAVE_INTERVAL_MILLIS = 15_000L
        const val SURFACE_READY_TIMEOUT_MILLIS = 6_000L
        const val FRAME_RATE_EPSILON = 0.001f
        const val MEDIA_SESSION_TAG = "4789 TV"
        val MEDIA_SESSION_ACTIONS =
            PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_STOP or
                PlaybackState.ACTION_REWIND or
                PlaybackState.ACTION_FAST_FORWARD or
                PlaybackState.ACTION_SEEK_TO

        const val NETWORK_START_ERROR =
            "The TV receiver could not start on this network. Check the connection, then try again."
        const val SURFACE_START_ERROR =
            "Video output could not start. Try again, or reopen 4789 TV."
    }
}

/** Play only when the film is paused; the media button must not restart a running title. */
private suspend fun PlaybackSession.togglePlayPauseIfPaused() {
    val snapshot = refreshSnapshot()
    if (snapshot.active && snapshot.speed == 0) togglePlayPause()
}

private suspend fun PlaybackSession.togglePlayPauseIfPlaying() {
    val snapshot = refreshSnapshot()
    if (snapshot.active && snapshot.speed != 0) togglePlayPause()
}
