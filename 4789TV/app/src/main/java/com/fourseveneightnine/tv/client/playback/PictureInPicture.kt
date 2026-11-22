package com.fourseveneightnine.tv.client.playback

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.fourseveneightnine.tv.client.ui.FloatingPictureWindow
import com.fourseveneightnine.tv.client.ui.MainActivity
import com.fourseveneightnine.tv.client.ui.PictureSize
import com.fourseveneightnine.tv.client.ui.pictureCorner
import com.fourseveneightnine.tv.client.ui.pictureGravity
import android.os.SystemClock
import android.view.Surface
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.fourseveneightnine.tv.client.appGraph
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.iptv.IptvChannel
import com.fourseveneightnine.tv.client.iptv.IptvRecordingStatus
import com.fourseveneightnine.tv.client.iptv.iptvPlaybackHeaders
import com.fourseveneightnine.tv.player.ReceiverPlaybackPhase
import com.fourseveneightnine.tv.protocol.OpenMediaRequest
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics
import com.fourseveneightnine.tv.player.forLivePlayback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The receiver owns both sources. Den TV Home lends it only a visible video surface. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PictureInPicture(private val app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val session get() = app.appGraph.playback
    private val repo get() = app.clientGraph.iptv
    private var owner: UUID? = null
    var channel by mutableStateOf<IptvChannel?>(null)
        private set
    var aspectRatio by mutableStateOf(16f / 9f)
        private set
    var liveLarge by mutableStateOf(false)
        private set
    var liveSound by mutableStateOf(false)
        private set
    private val preferences get() = app.appGraph.presentationPreferences
    var corner by mutableStateOf(pictureCorner(preferences.getInt("floating_picture_position", 0)))
        private set
    var size by mutableStateOf(PictureSize.entries.firstOrNull {
        it.name == preferences.getString("floating_picture_size", null)
    } ?: PictureSize.Large)
        private set
    var openPinnedPlayer by mutableStateOf(false)
        private set
    var nativeBrowse by mutableStateOf(false)
        private set
    var browseOnly by mutableStateOf(false)
        private set
    val movieInCorner: Boolean get() = nativeBrowse && channel == null
    var pictureHidden by mutableStateOf(false)
        private set

    /** Keep the activity-owned on-demand player on its current Surface while the catalog opens. */
    fun pinMovie(): Boolean {
        val profile = app.clientGraph.activeProfileId() ?: return false
        val media = session.lastOpenMedia() ?: return false
        if (session.origin.value != PlaybackSession.Origin.Local || session.liveChannel.value != null || media.isLive ||
            (session.phase.value != ReceiverPlaybackPhase.Playing && session.phase.value != ReceiverPlaybackPhase.Paused)) return false
        if (channel != null) clear()
        owner = profile
        nativeBrowse = true
        browseOnly = true
        ReceiverDiagnostics.record("pip.movie.pin", "generation=${session.generation}")
        return true
    }

    private var player: ExoPlayer? = null
    private var selectJob: Job? = null
    private var timeout: Job? = null
    private var screen: Surface? = null
    private var home: Surface? = null
    private var offeredAt = 0L
    private var soloGeneration: Long? = null
    private var pausedForTransfer = false
    val retainsSolo: Boolean get() = soloGeneration == session.generation
    val soloOnHome: Boolean get() = retainsSolo && home != null
    private var soloLiveId: String? = null
    private var away = false
    private var homeAccepted = false
    private var homeFailed: (() -> Unit)? = null
    private var crossAppWindow: FloatingPictureWindow? = null
    private var fullscreenRequested = false
    private var firstFrameTimeout: Job? = null
    private var crossAppJob: Job? = null
    private var watchingScreenPower = false

    private val screenPower = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) endCrossApp()
        }
    }

    private fun profileMatches() = owner != null && owner == app.clientGraph.activeProfileId()
    private fun permitted(item: IptvChannel): Boolean {
        val state = repo.state.value
        return profileMatches() && state.channels.any { it.id == item.id && it.sourceId == item.sourceId } &&
            item.group !in state.accounts.hiddenGroups && item.group !in state.accounts.lockedGroups
    }

    fun pin(item: IptvChannel, fromLive: Boolean, onPinned: () -> Unit) {
        selectJob?.cancel()
        owner = app.clientGraph.activeProfileId() ?: return
        if (!permitted(item)) return notice("This channel is unavailable for this profile.")
        if (fromLive && session.liveChannel.value?.id == item.id) {
            channel = item
            nativeBrowse = true
            browseOnly = true
            liveLarge = false
            liveSound = true
            ReceiverDiagnostics.record("pip.pin.warm", "generation=${session.generation}")
            onPinned()
            return
        }
        val state = repo.state.value
        val source = state.sources.find { it.id == item.sourceId }
        val recordings = state.accounts.recordings.count { record ->
            record.status == IptvRecordingStatus.RECORDING &&
                state.channels.any { it.id == record.channelId && it.sourceId == item.sourceId }
        }
        val movieUsesSource = session.iptvVod.value?.sourceId == item.sourceId
        val needed = recordings + (if (movieUsesSource) 2 else 1)
        if (source?.maxConnections?.takeIf { it > 0 }?.let { it < needed } == true)
            return notice("This provider has no free stream for the match.")
        val keepMovieSmall = movieInCorner && !fromLive
        val generation = session.generation
        selectJob = scope.launch {
            try {
                val request = OpenMediaRequest(repo.playUrl(item), title = item.name, isLive = true,
                    headers = iptvPlaybackHeaders(item.headers))
                if (session.generation != generation || away || !permitted(item)) return@launch
                if (fromLive && session.stop().isFailure) return@launch notice("Couldn’t move this channel into the corner.")
                nativeBrowse = false
                channel = item
                browseOnly = fromLive
                liveLarge = keepMovieSmall
                liveSound = keepMovieSmall || fromLive
                play(request)
                applySound()
                onPinned()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice("Couldn’t open this channel. Try another channel.") }
        }
    }

    fun moveLiveToCompanion(movieSource: String?) {
        val pinned = channel ?: return
        if (!nativeBrowse) return
        if (!permitted(pinned) || (pinned.sourceId == movieSource &&
                repo.state.value.sources.find { it.id == movieSource }?.maxConnections == 1)) {
            clear()
            notice("This channel cannot play alongside the selected movie.")
            return
        }
        val warm = app.appGraph.controller.takeLivePlayer()
        nativeBrowse = false
        if (warm == null) {
            clear()
            notice("The current player cannot keep a second picture.")
            return
        }
        player = warm
        liveSound = false
        warm.volume = 0f
        warm.setVideoSurface(screen)
        observePlayer(warm, true)
        warm.play()
        ReceiverDiagnostics.record("pip.pair.warm", "state=${warm.playbackState} bufferedMs=${warm.totalBufferedDuration}")
    }

    fun expandBrowse(openPlayer: () -> Unit) {
        if (movieInCorner) {
            if (!profileMatches()) { closeBrowse(); return }
            clear()
            openPlayer()
            return
        }
        val pinned = channel ?: return
        if (!permitted(pinned)) { closeBrowse(); return }
        if (!nativeBrowse || pinned.id != session.liveChannel.value?.id) {
            promoteChannel(pinned)
            return
        }
        nativeBrowse = false
        clear()
        openPlayer()
    }

    fun closeBrowse() {
        val stop = nativeBrowse && (movieInCorner || channel?.id == session.liveChannel.value?.id)
        clear()
        if (stop) scope.launch { session.stop() }
    }

    fun returnToBrowse() {
        val pinned = channel ?: return
        if (player == null || !permitted(pinned)) { clear(); return }
        browseOnly = true
        liveLarge = false
        liveSound = true
        applySound()
    }

    fun movieOpened() {
        if (movieInCorner) { clear(); return }
        if (channel == null || !browseOnly) return
        val movieSource = session.iptvVod.value?.sourceId
        if (movieSource == channel?.sourceId &&
            repo.state.value.sources.find { it.id == movieSource }?.maxConnections == 1) {
            clear()
            return notice("This provider allows only one stream.")
        }
        browseOnly = false
        liveSound = false
        applySound()
    }

    private fun recordFrames(transition: String) {
        val counters = player?.videoDecoderCounters ?: return
        counters.ensureUpdated()
        ReceiverDiagnostics.record("pip.frames", "at=$transition rendered=${counters.renderedOutputBufferCount} " +
            "dropped=${counters.droppedBufferCount} skipped=${counters.skippedOutputBufferCount} " +
            "decoderStarts=${counters.decoderInitCount} decoderReleases=${counters.decoderReleaseCount} " +
            "state=${player?.playbackState} playing=${player?.isPlaying} bufferedMs=${player?.totalBufferedDuration}")
    }

    fun onMemoryPressure(level: Int) {
        if (level != android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) return
        if (crossAppWindow != null) {
            endCrossApp()
            return
        }
        if (channel == null) return
        val notify = homeFailed
        closeBrowse()
        notify?.invoke()
        notice("Closed the match to free TV memory.")
    }

    fun swap() {
        recordFrames("swap")
        liveLarge = !liveLarge
        ReceiverDiagnostics.record("pip.swap", "liveLarge=$liveLarge")
    }
    fun switchSound() {
        liveSound = !liveSound
        applySound()
        ReceiverDiagnostics.record("pip.sound", "matchGain=${player?.volume} movieMuted=$liveSound")
    }
    fun move() { place((corner + 1) % 4) }

    fun resize(value: PictureSize) {
        size = value
        preferences.edit().putString("floating_picture_size", value.name).apply()
    }

    fun place(value: Int, save: Boolean = true) {
        corner = value
        if (save) preferences.edit().putInt("floating_picture_position", pictureGravity(value)).apply()
    }

    fun goToVideo() {
        fullscreenRequested = true
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun consumeOpenPinnedPlayer(): Boolean = openPinnedPlayer.also { openPinnedPlayer = false }

    fun closeSmall() {
        val pinned = channel ?: return
        if (!liveLarge || !permitted(pinned)) { clear(); return }
        promoteChannel(pinned)
    }

    private fun promoteChannel(pinned: IptvChannel) {
        selectJob?.cancel()
        selectJob = scope.launch {
            try {
                val url = repo.playUrl(pinned)
                if (channel != pinned || !permitted(pinned) || away) return@launch
                app.clientGraph.playFlow.captureStopProgress()
                if (channel != pinned || away) return@launch
                // Free the existing channel connection before opening it in the full-screen player.
                selectJob = null
                clear()
                val live = PlaybackSession.LiveChannel(pinned.id, pinned.sourceId, pinned.group)
                val result = session.openLiveChannel(OpenMediaRequest(url, title = pinned.name, isLive = true,
                    headers = iptvPlaybackHeaders(pinned.headers)), live)
                if (result.isFailure) notice("Couldn’t open the match full-screen.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice("Couldn’t open the match full-screen.") }
        }
    }

    fun setScreen(surface: Surface?) {
        screen = surface
        if (home == null) player?.setVideoSurface(surface)
    }

    fun toggleBrowseVisibility(): Boolean {
        if (!browseOnly || !profileMatches()) return false
        pictureHidden = !pictureHidden
        crossAppWindow?.setPictureHidden(pictureHidden)
        applySound()
        ReceiverDiagnostics.record("pip.visibility", "hidden=$pictureHidden")
        notice(if (pictureHidden) "PiP hidden and muted" else "PiP visible")
        return true
    }

    private fun applySound() {
        app.appGraph.controller.setPictureAudioSuppressed(
            (channel != null && liveSound && !browseOnly) || (browseOnly && nativeBrowse && pictureHidden),
        )
        player?.volume = if ((home != null || liveSound) && !(browseOnly && pictureHidden)) 1f else 0f
    }

    private fun play(request: OpenMediaRequest) {
        releasePlayer()
        val client = app.clientGraph.okHttp
        val mediaClient = request.url.toHttpUrlOrNull()?.let(client::forLivePlayback) ?: client
        val http = OkHttpDataSource.Factory(mediaClient).setDefaultRequestProperties(request.headers)
        request.headers.entries.find { it.key.equals("User-Agent", true) }?.let { http.setUserAgent(it.value) }
        val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(app, http))
        // Live PiP needs a short forward buffer and no rewind cache. Bound compressed media
        // separately from the main movie. Codec and graphics allocations are outside this cap.
        val buffer = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(2_000, 8_000, 500, 1_000)
            .setTargetBufferBytes(8 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val next = ExoPlayer.Builder(app, com.fourseveneightnine.tv.player.TvVideoRenderersFactory(app))
            .setMediaSourceFactory(sources).setLoadControl(buffer).build()
        player = next
        next.trackSelectionParameters = next.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(1280, 720).build()
        next.volume = if ((home != null || liveSound) && !(browseOnly && pictureHidden)) 1f else 0f
        next.setVideoSurface(home ?: screen)
        observePlayer(next, request.isLive)
        next.setMediaItem(MediaItem.fromUri(request.url), request.startPositionMs ?: androidx.media3.common.C.TIME_UNSET)
        next.prepare()
        next.play()
    }

    private fun observePlayer(next: ExoPlayer, live: Boolean) {
        next.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
                if (player === next && size.width > 0 && size.height > 0)
                    aspectRatio = size.width.toFloat() * size.pixelWidthHeightRatio / size.height
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (player === next) ReceiverDiagnostics.record("pip.state",
                    "state=$state ready=${next.playWhenReady} suppression=${next.playbackSuppressionReason} bufferedMs=${next.totalBufferedDuration}")
            }
            private var lastLiveRecovery: Long? = null
            override fun onRenderedFirstFrame() {
                ReceiverDiagnostics.record("pip.firstFrame", "home=${home != null} live=$live")
            }
            override fun onPlayerError(error: PlaybackException) {
                if (player !== next) return
                val now = SystemClock.elapsedRealtime()
                val previous = lastLiveRecovery
                if (live && error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW &&
                    (previous == null || now - previous > 15_000)) {
                    lastLiveRecovery = now
                    ReceiverDiagnostics.record("pip.live.recover", "reason=behind-live-window")
                    next.seekToDefaultPosition()
                    next.prepare()
                    next.play()
                    return
                }
                ReceiverDiagnostics.record("pip.stream.failed", "code=${error.errorCode}")
                notice("The small picture could not play. You can select the channel again.")
                val failed = homeFailed
                clear()
                failed?.invoke()
            }
        })
    }

    fun offerHome(playerVisible: Boolean) {
        if (crossAppWindow != null || !offerExternalPicture(playerVisible)) return
        // Home binds without auto-create, so opening the launcher with no video never
        // starts a second app or retains its caches. The receiver offers this short lease.
        try {
            app.startService(Intent(app, com.fourseveneightnine.tv.client.home.HomePictureService::class.java))
        } catch (_: IllegalStateException) {
            offeredAt = 0
            soloGeneration = null
        }
    }

    private fun offerExternalPicture(playerVisible: Boolean): Boolean {
        val primary = session.lastOpenMedia()
        if (channel == null && ((!playerVisible && !movieInCorner) || session.origin.value != PlaybackSession.Origin.Local ||
            session.phase.value !is ReceiverPlaybackPhase.Playing || primary == null)) return false
        owner = app.clientGraph.activeProfileId() ?: return false
        soloGeneration = session.generation.takeIf { channel == null || nativeBrowse }
        soloLiveId = session.liveChannel.value?.id
        offeredAt = SystemClock.elapsedRealtime()
        if (canShowExternalPicture()) return true
        offeredAt = 0
        soloGeneration = null
        soloLiveId = null
        return false
    }

    fun canShowHome(): Boolean = crossAppWindow == null && canShowExternalPicture()

    private fun canShowExternalPicture(): Boolean {
        if (!publicHomeProfile() || !profileMatches() || offeredAt == 0L) return false
        if (!homeAccepted && SystemClock.elapsedRealtime() - offeredAt > OFFER_MS) return false
        val live = channel ?: soloLiveId?.let { id -> repo.state.value.channels.find { it.id == id } ?: return false }
        return (live == null || permitted(live)) && (live != null || retainsSolo)
    }

    private fun publicHomeProfile(): Boolean {
        val profile = app.clientGraph.profiles.value?.profiles?.singleOrNull() ?: return false
        return !profile.hasPin && profile.id == owner
    }

    fun supportsCrossApp(): Boolean = Build.VERSION.SDK_INT >= 34 &&
        app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().contains(android.Manifest.permission.SYSTEM_ALERT_WINDOW)

    fun canFloatOverApps(): Boolean = supportsCrossApp() && Settings.canDrawOverlays(app)

    @android.annotation.TargetApi(34)
    fun offerPinnedPicture(activity: Activity): Boolean {
        if (crossAppWindow != null) return true
        if (!browseOnly || !nativeBrowse || !canFloatOverApps() ||
            app.appGraph.controller.engine != com.fourseveneightnine.tv.player.ReceiverEngine.Exo ||
            session.origin.value != PlaybackSession.Origin.Local || !canFloatVod() ||
            !offerExternalPicture(playerVisible = false)) return false

        val context = activity.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        val floating = FloatingPictureWindow(
            context, corner, size, ::resize, { place(it) }, ::goToVideo,
            onSurfaceAvailable = { window, surface ->
                if (crossAppWindow === window && away) attachCrossApp(window, surface)
            },
            onSurfaceLost = { window -> if (crossAppWindow === window) endCrossApp() },
        )
        val stage = floating.stage
        stage.setVideoAspectRatio(session.videoAspectRatio.value)
        crossAppWindow = floating
        floating.setPictureHidden(pictureHidden)
        try {
            floating.show()
        } catch (_: WindowManager.BadTokenException) {
            closeCrossAppWindow()
            returning()
            notice("This TV did not allow the floating video.")
            return false
        } catch (_: SecurityException) {
            closeCrossAppWindow()
            returning()
            notice("Allow 4789 TV to display over other apps.")
            return false
        }
        ContextCompat.registerReceiver(app, screenPower, IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        watchingScreenPower = true
        crossAppJob = scope.launch {
            launch { session.cues.collect(stage::setCues) }
            launch { session.videoAspectRatio.collect(stage::setVideoAspectRatio) }
            launch { session.subtitleStyle.collect(stage::applySubtitleStyle) }
            launch {
                session.phase.collect { phase ->
                    if (phase == ReceiverPlaybackPhase.Ended || phase == ReceiverPlaybackPhase.Stopped ||
                        phase is ReceiverPlaybackPhase.Error) endCrossApp()
                }
            }
            if (soloLiveId != null || session.iptvVod.value != null) launch {
                repo.state.collect { if (!canShowExternalPicture() || !canFloatVod()) endCrossApp() }
            }
        }
        return true
    }

    internal fun toggleCrossAppControls(): Boolean {
        val window = crossAppWindow ?: return false
        if (!away || !soloOnHome || !window.isAttachedToWindow || !Settings.canDrawOverlays(app)) return false
        return pictureHidden || window.toggleControls()
    }

    internal fun toggleCrossAppVisibility(): Boolean {
        if (!away || !soloOnHome || crossAppWindow?.isAttachedToWindow != true) return false
        return toggleBrowseVisibility()
    }

    private fun canFloatVod(): Boolean {
        val vod = session.iptvVod.value ?: return true
        val state = repo.state.value
        val group = if (vod.kind == "episode") {
            state.catalog.vod.firstOrNull { it.kind == "series" && it.sourceId == vod.sourceId &&
                it.id == (vod.seriesId ?: vod.category) }?.category ?: return false
        } else vod.category
        return state.sources.any { it.id == vod.sourceId && it.enabled } &&
            group !in state.accounts.hiddenGroups && group !in state.accounts.lockedGroups
    }

    private fun attachCrossApp(window: FloatingPictureWindow, surface: Surface) {
        if (!app.getSystemService(PowerManager::class.java).isInteractive ||
            !Settings.canDrawOverlays(app) || !attachExternalPicture(surface, ::endCrossApp, window)) {
            endCrossApp()
        }
    }

    private fun endCrossApp() {
        if (crossAppWindow == null) return
        if (away) {
            detachExternalPicture()
            clear()
        } else {
            returning()
        }
    }

    private fun closeCrossAppWindow() {
        val window = crossAppWindow ?: return
        crossAppWindow = null
        crossAppJob?.cancel()
        crossAppJob = null
        if (watchingScreenPower) app.unregisterReceiver(screenPower)
        watchingScreenPower = false
        firstFrameTimeout?.cancel()
        firstFrameTimeout = null
        // Controller surface operations are queued on Main. Let the decoder detach before
        // WindowManager destroys the buffers for the old window.
        Handler(Looper.getMainLooper()).post(window::remove)
    }

    fun leaving() {
        away = true
        selectJob?.cancel()
        if (home != null) return
        if (!canShowExternalPicture()) { clear(); return }
        val window = crossAppWindow
        val surface = window?.videoSurface
        if (window != null && surface?.isValid == true) {
            attachCrossApp(window, surface)
            return
        }
        if (retainsSolo) pauseSolo() else player?.volume = 0f
        expireWhenHidden()
    }

    fun attachHome(surface: Surface, onFailure: () -> Unit): Boolean =
        canShowHome() && attachExternalPicture(surface, onFailure)

    private fun attachExternalPicture(surface: Surface, onFailure: () -> Unit, window: FloatingPictureWindow? = null): Boolean {
        if (!surface.isValid || !canShowExternalPicture()) return false
        val companion = channel != null && !nativeBrowse
        val active = player
        if (companion && active == null) return false
        timeout?.cancel()
        recordFrames("home")
        homeAccepted = true
        homeFailed = onFailure
        home = surface
        if (companion && active != null) {
            active.setVideoSurface(surface)
            active.volume = if (pictureHidden) 0f else 1f
            active.play()
        } else {
            val controller = app.appGraph.controller
            if (window != null) {
                firstFrameTimeout?.cancel()
                firstFrameTimeout = scope.launch {
                    delay(OFFER_MS)
                    notice("Couldn’t show the picture over this app.")
                    endCrossApp()
                }
                controller.attachSurface(surface, window.videoWidth, window.videoHeight) {
                    if (crossAppWindow === window && home === surface) {
                        firstFrameTimeout?.cancel()
                        firstFrameTimeout = null
                        window.revealPicture()
                        ReceiverDiagnostics.record("pip.cross-app.frame", "generation=${session.generation}")
                    }
                }
            } else {
                controller.attachSurface(surface, 0, 0)
            }
            resumeSolo()
        }
        val target = if (window != null) "cross-app" else "home"
        ReceiverDiagnostics.record("pip.$target.attached", "match=${channel != null || soloLiveId != null}")
        return true
    }

    fun detachHome() {
        if (crossAppWindow == null) detachExternalPicture()
    }

    private fun detachExternalPicture() {
        if (home == null) return
        if (retainsSolo && away) {
            app.appGraph.controller.detachSurface()
            pauseSolo()
            scope.launch { session.checkpointResume() }
        } else if (channel != null) {
            // Home may still own the last visible frame while the receiver lays out its stage.
            // Keep the same codec and change its output directly when the new surface exists.
            if (screen?.isValid == true) player?.setVideoSurface(screen)
            else player?.setVideoSurface(null)
            if (away) player?.volume = 0f
        }
        home = null
        homeFailed = null
        if (away) expireWhenHidden()
    }

    private fun pauseSolo() {
        if (!retainsSolo || pausedForTransfer || session.phase.value == ReceiverPlaybackPhase.Paused) return
        pausedForTransfer = true
        scope.launch { session.setSpeed(0) }
    }

    private fun resumeSolo() {
        if (!retainsSolo || !pausedForTransfer) return
        pausedForTransfer = false
        scope.launch { session.togglePlayPause() }
    }

    private fun expireWhenHidden() {
        timeout?.cancel()
        timeout = scope.launch {
            delay(OFFER_MS)
            if (away && home == null) clear()
        }
    }

    fun returning() {
        away = false
        openPinnedPlayer = fullscreenRequested
        fullscreenRequested = false
        timeout?.cancel()
        // Home may destroy its Surface before the receiver creates one. Let Media3
        // park the live decoder until PictureStage supplies the new output.
        val hadFloatingPicture = crossAppWindow != null
        if (hadFloatingPicture && soloOnHome) app.appGraph.controller.detachSurface()
        detachExternalPicture()
        closeCrossAppWindow()
        timeout?.cancel()
        val pinned = channel
        if (pinned != null && !permitted(pinned)) { clear(); return }
        if (homeAccepted) recordFrames("return")
        val target = if (hadFloatingPicture) "cross-app" else "home"
        if (homeAccepted) ReceiverDiagnostics.record("pip.$target.return",
            "dual=${channel != null && !browseOnly} liveLarge=$liveLarge liveSound=$liveSound")
        resumeSolo()
        offeredAt = 0
        homeAccepted = false
        soloGeneration = null
        pausedForTransfer = false
        soloLiveId = null
        if (screen?.isValid == true) setScreen(screen)
        player?.play()
        applySound()
    }

    fun clear() {
        selectJob?.cancel()
        val heldGeneration = soloGeneration
        if (crossAppWindow != null && soloOnHome) app.appGraph.controller.detachSurface()
        closeCrossAppWindow()
        timeout?.cancel()
        releasePlayer()
        channel = null
        nativeBrowse = false
        liveLarge = false
        liveSound = false
        browseOnly = false
        pictureHidden = false
        home = null
        homeFailed = null
        offeredAt = 0
        homeAccepted = false
        soloGeneration = null
        pausedForTransfer = false
        soloLiveId = null
        owner = null
        applySound()
        if (away && heldGeneration == session.generation) scope.launch {
            session.stopForVisibilityLoss { away && soloGeneration == null }
        }
    }

    fun validateAccess() {
        val pinned = channel
        if (crossAppWindow != null && !canShowExternalPicture()) {
            endCrossApp()
            return
        }
        if (owner != null && (!profileMatches() || (pinned != null && !permitted(pinned)))) {
            val notify = homeFailed
            closeBrowse()
            notify?.invoke()
        }
    }

    private fun releasePlayer() { player?.release(); player = null }
    private fun notice(message: String) = Toast.makeText(app, message, Toast.LENGTH_SHORT).show()

    private companion object { const val OFFER_MS = 8_000L }
}
