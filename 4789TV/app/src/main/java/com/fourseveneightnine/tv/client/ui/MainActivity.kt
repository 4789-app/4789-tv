package com.fourseveneightnine.tv.client.ui

import com.fourseveneightnine.tv.client.playback.PlaybackSession
import android.app.SearchManager
import android.content.Intent
import android.media.AudioAttributes
import android.media.SoundPool
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.fourseveneightnine.tv.R
import com.fourseveneightnine.tv.client.AppGraph
import com.fourseveneightnine.tv.client.appGraph
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.playback.ReceiverHost
import com.fourseveneightnine.tv.client.search.SystemSearchCommand
import com.fourseveneightnine.tv.client.search.SystemSearchIntentParser
import com.fourseveneightnine.tv.client.ui.screens.settings.SettingsKeys
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.BuildConfig
import com.fourseveneightnine.tv.startup.BenchmarkFrameMetrics
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics
import com.fourseveneightnine.tv.ui.TvRemoteCommand
import com.fourseveneightnine.tv.ui.PlayerControlOpeningGesture
import com.fourseveneightnine.tv.ui.PlayerControlsPolicy
import com.fourseveneightnine.tv.ui.TvRemoteKeyPolicy
import com.fourseveneightnine.tv.ui.RemoteButtonAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one Activity.
 *
 * It owns two things and no more: the persistent video layer (see [VideoStage] for why the
 * `SurfaceView` cannot live inside the Compose tree), and the remote. Everything else is Compose
 * above it or [ReceiverHost] beside it.
 *
 * `playbackOwnsDpad` is derived from what [ShellState] says is drawn. The old shell derived it
 * from a flag fed by one event stream, and any ending that failed to deliver a final event left it
 * stuck at `true` — on the home screen UP and DOWN then raised player controls for a film that was
 * not playing and the remote looked broken.
 */
class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var graph: AppGraph
    private lateinit var host: ReceiverHost
    private lateinit var pictures: PictureStage
    private lateinit var stage: VideoStage
    private val shell = ShellState()
    private var benchmarkFrames: BenchmarkFrameMetrics? = null
    private var feedbackPool: SoundPool? = null
    private var feedbackSamples = emptyMap<Int, Int>()
    private val readyFeedbackSamples = mutableSetOf<Int>()
    private var canvasPreferenceListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null
    private val playerControlOpeningGesture = PlayerControlOpeningGesture()
    private var lastHeldSeekAt = 0L
    private val channelHoldGesture = com.fourseveneightnine.tv.ui.ChannelHoldGesture()
    private var heldMovieShortcut: com.fourseveneightnine.tv.ui.RemoteButtonAction? = null
    private var customRemoteKey: Int? = null
    private var remoteBindings = emptyList<com.fourseveneightnine.tv.ui.RemoteButtonBinding>()
    private val pendingSystemSearchCommands = Channel<SystemSearchCommand>(Channel.BUFFERED)
    private val systemSearchCommands = pendingSystemSearchCommands.receiveAsFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handOffSystemSearch(intent)
        ReceiverDiagnostics.record("activity.onCreate.begin")
        ReceiverDiagnostics.benchmark("activity.create") { "restored=${savedInstanceState != null}" }
        clientGraph.start()
        graph = appGraph
        remoteBindings = com.fourseveneightnine.tv.ui.RemoteButtonMappings.read(graph.presentationPreferences)
        host = ReceiverHost(this, graph)

        stage = VideoStage(this)
        stage.surfaceView.holder.addCallback(this)

        val compose = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AppRoot(
                    graph = graph,
                    host = host,
                    shell = shell,
                    onResizeMode = { pictures.setFit(it) },
                    onControlBarVisible = { pictures.setControlBarVisible(it) },
                    onOpenPictureSettings = ::requestFloatingPermission,
                    onBrowseX = ::browseXWithPinnedPicture,
                    systemSearchCommands = systemSearchCommands,
                    onExit = { finish() },
                )
            }
        }

        val root = FrameLayout(this)
        root.addView(
            stage,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            compose,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // The theme sets no window background (one full-screen fill per frame saved). Without a
        // background the window would default to a translucent format, which pushes it off the
        // hardware overlay onto GPU composition; opaque keeps the overlay.
        window.setFormat(android.graphics.PixelFormat.OPAQUE)
        if (BuildConfig.BENCHMARK_LOGGING) benchmarkFrames = BenchmarkFrameMetrics(window)
        setContentView(root)
        // The window has no background drawable (a full-screen fill per frame saved), so the
        // Compose layer paints the canvas itself on every browse screen; it goes transparent only
        // while the player is up and the video surface must show through.
        val canvas = browseCanvasColor()
        compose.setBackgroundColor(canvas)
        lifecycleScope.launch {
            val pip = graph.pictureInPicture
            androidx.compose.runtime.snapshotFlow {
                shell.playerVisible to (pip.browseOnly && !pip.pictureHidden)
            }.collect { (visible, pinned) ->
                stage.setPlayerVisible(visible || pip.nativeBrowse)
                compose.setBackgroundColor(if (visible || pinned) android.graphics.Color.TRANSPARENT else browseCanvasColor())
            }
        }
        pictures = PictureStage(root, stage, compose, graph.pictureInPicture)
        host.bindSurfaceView(stage.surfaceView, pictures::movieHolder)
        shell.livePreviewViewport = pictures::guideViewport
        lifecycleScope.launch {
            androidx.compose.runtime.snapshotFlow {
                val pip = graph.pictureInPicture
                listOf(shell.playerVisible, pip.channel?.id, pip.liveLarge, pip.corner, pip.size, pip.aspectRatio, pip.nativeBrowse, pip.browseOnly)
            }.collect { pictures.layout(shell.playerVisible) }
        }
        lifecycleScope.launch {
            graph.playback.openRequests.collect {
                val pip = graph.pictureInPicture
                if (graph.playback.liveChannel.value != null) pip.clear() else pip.movieOpened()
            }
        }
        lifecycleScope.launch { clientGraph.profileSession.state.collect { graph.pictureInPicture.validateAccess() } }
        lifecycleScope.launch {
            val pip = graph.pictureInPicture
            androidx.compose.runtime.snapshotFlow { pip.channel?.id }.collectLatest { pinned ->
                if (pinned != null) clientGraph.iptv.state.collect { pip.validateAccess() }
            }
        }
        shell.holdLivePicture = {
            if (graph.playback.liveChannel.value == null ||
                graph.playback.origin.value != com.fourseveneightnine.tv.client.playback.PlaybackSession.Origin.Local
            ) false else stage.holdCurrentPicture()
        }
        shell.releaseLivePicture = { stage.releaseHeldPicture(); shell.holdingLivePicture = false }
        lifecycleScope.launch {
            graph.playback.phase.collect { phase ->
                if (graph.pictureInPicture.movieInCorner) when (phase) {
                    com.fourseveneightnine.tv.player.ReceiverPlaybackPhase.Ended,
                    is com.fourseveneightnine.tv.player.ReceiverPlaybackPhase.Error -> shell.returnToPinnedVideo?.invoke()
                    com.fourseveneightnine.tv.player.ReceiverPlaybackPhase.Stopped -> graph.pictureInPicture.clear()
                    else -> Unit
                }
                if (phase is com.fourseveneightnine.tv.player.ReceiverPlaybackPhase.Playing ||
                    phase is com.fourseveneightnine.tv.player.ReceiverPlaybackPhase.Paused ||
                    phase is com.fourseveneightnine.tv.player.ReceiverPlaybackPhase.Stopped ||
                    phase is com.fourseveneightnine.tv.player.ReceiverPlaybackPhase.Error)
                    shell.releaseLivePicture?.invoke()
            }
        }
        lifecycleScope.launch {
            graph.playback.sourceFeedback.collect { cue ->
                val sound = when (cue) {
                    PlaybackSession.SourceFeedback.Started -> R.raw.feedback_source_started
                    PlaybackSession.SourceFeedback.Failed -> R.raw.feedback_source_failed
                }
                if (playFeedbackSound(sound)) ReceiverDiagnostics.record("ui.feedback.source", cue.name)
            }
        }
        val canvasListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (changed == com.fourseveneightnine.tv.ui.RemoteButtonMappings.PREFERENCE_KEY || changed == null)
                remoteBindings = com.fourseveneightnine.tv.ui.RemoteButtonMappings.read(graph.presentationPreferences)
            if (changed == SettingsKeys.CANVAS)
                compose.setBackgroundColor(if (shell.playerVisible) android.graphics.Color.TRANSPARENT else browseCanvasColor())
            if ((changed == SettingsKeys.FEEDBACK_SOUNDS || changed == null) &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            ) refreshFeedbackSounds()
        }
        canvasPreferenceListener = canvasListener
        graph.presentationPreferences.registerOnSharedPreferenceChangeListener(canvasListener)
        observeVideoSideChannels()
        hideSystemBars()
        ReceiverDiagnostics.record("activity.onCreate.complete")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handOffSystemSearch(intent)
    }

    override fun onStart() {
        super.onStart()
        graph.pictureInPicture.returning()
        pictures.started(true)
        host.onStart()
    }

    override fun onUserLeaveHint() {
        val pip = graph.pictureInPicture
        if (!pip.offerPinnedPicture(this)) pip.offerHome(shell.playerVisible)
        super.onUserLeaveHint()
    }

    override fun onResume() {
        super.onResume()
        refreshFeedbackSounds()
        RemoteButtonAccessibilityService.attach(this)
    }

    private fun requestFloatingPermission() {
        val pip = graph.pictureInPicture
        if (!pip.supportsCrossApp() || pip.canFloatOverApps()) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Allow PiP over other apps")
            .setMessage("Allow 4789 TV to display over other apps. The same PiP button will then work while you switch apps.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Open settings") { _, _ ->
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.fromParts("package", packageName, null)))
                } catch (_: android.content.ActivityNotFoundException) {
                    android.widget.Toast.makeText(this, "Display over other apps is unavailable on this TV.",
                        android.widget.Toast.LENGTH_LONG).show()
                }
            }.show()
    }

    internal fun browseXWithPinnedPicture() {
        if (graph.pictureInPicture.offerPinnedPicture(this)) {
            startActivity(Intent(this, XBrowseActivity::class.java))
        } else {
            requestFloatingPermission()
        }
    }

    override fun onPause() {
        RemoteButtonAccessibilityService.detach(this)
        customRemoteKey = null
        super.onPause()
    }

    override fun onStop() {
        releaseFeedbackSounds()
        heldMovieShortcut = null
        pictures.started(false)
        graph.pictureInPicture.leaving()
        playerControlOpeningGesture.reset()
        host.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        benchmarkFrames?.close()
        benchmarkFrames = null
        canvasPreferenceListener?.let(graph.presentationPreferences::unregisterOnSharedPreferenceChangeListener)
        canvasPreferenceListener = null
        host.onDestroy()
        super.onDestroy()
    }

    private fun refreshFeedbackSounds() {
        if (!graph.presentationPreferences.getBoolean(SettingsKeys.FEEDBACK_SOUNDS, false)) {
            releaseFeedbackSounds()
            return
        }
        if (feedbackPool != null) return
        val pool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        ).build()
        feedbackPool = pool
        pool.setOnLoadCompleteListener { loadedPool, sample, status ->
            if (loadedPool === feedbackPool && status == 0) readyFeedbackSamples.add(sample)
        }
        feedbackSamples = listOf(R.raw.feedback_browse, R.raw.feedback_source_started, R.raw.feedback_source_failed)
            .associateWith { pool.load(this, it, 1) }
    }

    private fun releaseFeedbackSounds() {
        val pool = feedbackPool
        feedbackPool = null
        pool?.release()
        feedbackSamples = emptyMap()
        readyFeedbackSamples.clear()
    }

    private fun playFeedbackSound(resource: Int): Boolean {
        val sample = feedbackSamples[resource] ?: return false
        if (sample !in readyFeedbackSamples || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            return false
        val priority = if (resource == R.raw.feedback_browse) 1 else 2
        return feedbackPool?.play(sample, 1f, 1f, priority, 0, 1f)?.let { it != 0 } == true
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        graph.pictureInPicture.onMemoryPressure(level)
        host.onTrimMemory(level)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars() else {
            customRemoteKey = null
            playerControlOpeningGesture.reset()
            shell.playerCommands?.invoke(TvRemoteCommand.CommitChannelSelection)
            channelHoldGesture.reset()
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        host.onSurfaceCreated(holder)
        pictures.updateSurfaces()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
        host.onSurfaceChanged(holder, width, height)

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        pictures.surfaceLost(holder)
        host.onSurfaceDestroyed()
    }

    /**
     * The remote.
     *
     * [TvRemoteKeyPolicy] decides what a key means from who owns the D-pad and whether the control
     * bar is up. When the player is not drawn the policy returns `PassThrough` for every direction
     * and Compose's own focus search moves the ring, which is exactly what a browse screen wants.
     */
    internal fun handleReservedRemoteButton(event: KeyEvent): Boolean {
        if (!hasWindowFocus() || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return false
        return dispatchConfiguredRemoteButton(event)
    }

    private fun dispatchConfiguredRemoteButton(event: KeyEvent): Boolean {
        if (shell.remoteButtonCapture?.invoke(event) == true) return true
        val movie = shell.playerVisible && graph.playback.origin.value == PlaybackSession.Origin.Local &&
            graph.playback.liveChannel.value == null
        val channelUp = event.keyCode == KeyEvent.KEYCODE_CHANNEL_UP
        val held = heldMovieShortcut
        if (channelUp && held != null) {
            if (event.action == KeyEvent.ACTION_UP) {
                heldMovieShortcut = null
                if (!event.isCanceled && movie) {
                    val command = if (event.eventTime - event.downTime >= android.view.ViewConfiguration.getLongPressTimeout())
                        TvRemoteCommand.PictureInPicture else held.command
                    command?.let(::runRemotePlayerAction)
                }
            }
            return true
        }
        if (customRemoteKey == event.keyCode) {
            if (event.action == KeyEvent.ACTION_UP) customRemoteKey = null
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val handled = when (event.keyCode) {
                KeyEvent.KEYCODE_GUIDE -> when {
                    shell.togglePinnedControls?.invoke() == true -> true
                    shell.playerVisible && graph.playback.origin.value == PlaybackSession.Origin.Local -> {
                        runRemotePlayerAction(TvRemoteCommand.PictureInPicture)
                        true
                    }
                    else -> false
                }
                KeyEvent.KEYCODE_PROFILE_SWITCH ->
                    !shell.playerVisible && graph.pictureInPicture.toggleBrowseVisibility()
                else -> false
            }
            if (handled) {
                customRemoteKey = event.keyCode
                return true
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
            (shell.playerVisible || shell.remoteShortcutsAllowed)) {
            val custom = com.fourseveneightnine.tv.ui.RemoteButtonMappings.action(remoteBindings,
                event.keyCode, shell.playerVisible, shell.chromeVisible, shell.overlayVisible)
            val pipKey = com.fourseveneightnine.tv.ui.RemoteButtonMappings.moviePipKey(remoteBindings,
                com.fourseveneightnine.tv.ui.RemoteButtonAccessibilityService.connected.value)
            if (custom != null) {
                if (movie && channelUp && pipKey == null) {
                    // No spare key. Preserve its assigned tap and use a hold to pin the movie.
                    heldMovieShortcut = custom
                    return true
                }
                customRemoteKey = event.keyCode
                if (custom.command != null) runRemotePlayerAction(custom.command) else shell.remoteShortcut?.invoke(custom)
                return true
            }
            val channelButton = event.keyCode == KeyEvent.KEYCODE_CHANNEL_UP || event.keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN
            if (movie && (!shell.chromeVisible || channelButton) && !shell.overlayVisible &&
                pipKey == event.keyCode && shell.playerCommands?.invoke(TvRemoteCommand.PictureInPicture) == true) {
                customRemoteKey = event.keyCode
                return true
            }
        }
        return false
    }

    private fun runRemotePlayerAction(command: TvRemoteCommand) {
        if (shell.playerCommands?.invoke(command) == true) return
        val message = if (command == TvRemoteCommand.PictureInPicture) "Play a video in 4789 to use picture in picture."
            else "This action needs live TV playback."
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!BuildConfig.BENCHMARK_LOGGING || !benchmarkRemoteKey(event.keyCode)) return dispatchRemoteKey(event)
        val startedAt = android.os.SystemClock.uptimeMillis()
        val key = "key=${event.keyCode} action=${event.action} repeat=${event.repeatCount} " +
            "eventUptimeMs=${event.eventTime} downUptimeMs=${event.downTime}"
        ReceiverDiagnostics.recordBenchmark("input.begin", key)
        val handled = dispatchRemoteKey(event)
        ReceiverDiagnostics.recordBenchmark("input.end",
            "$key handled=$handled dispatchMs=${android.os.SystemClock.uptimeMillis() - startedAt}")
        return handled
    }

    private fun benchmarkRemoteKey(code: Int): Boolean = code in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_CENTER ||
        when (code) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_BUTTON_MODE,
            KeyEvent.KEYCODE_BOOKMARK, KeyEvent.KEYCODE_TV, KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> true
            else -> false
        }

    private fun dispatchRemoteKey(event: KeyEvent): Boolean {
        if (dispatchConfiguredRemoteButton(event)) return true
        if (event.action == KeyEvent.ACTION_UP && channelHoldGesture.release(event.keyCode)) {
            shell.playerCommands?.invoke(TvRemoteCommand.CommitChannelSelection)
            return true
        }
        if (event.action == KeyEvent.ACTION_UP && playerControlOpeningGesture.consumeRelease(event.keyCode)) return true
        if (shell.playbackOwnsDpad && TvRemoteKeyPolicy.isFavoriteKey(event.keyCode) &&
            (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0)) return true
        val seekDirection = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> 1
            else -> 0
        }
        if (event.action == KeyEvent.ACTION_UP && seekDirection != 0) lastHeldSeekAt = 0L
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0 &&
            shell.playbackOwnsDpad && graph.playback.liveChannel.value != null) {
            val command = TvRemoteKeyPolicy.command(event.keyCode, true, shell.chromeVisible, true)
            if (command == TvRemoteCommand.ChannelPrevious || command == TvRemoteCommand.ChannelNext) {
                if (channelHoldGesture.repeat(event.keyCode, event.eventTime)) {
                    shell.playerCommands?.invoke(if (command == TvRemoteCommand.ChannelNext)
                        TvRemoteCommand.SelectNextChannel else TvRemoteCommand.SelectPreviousChannel)
                }
                return true
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0 && seekDirection != 0 &&
            shell.playbackOwnsDpad && !shell.chromeVisible && graph.playback.lastOpenMedia()?.isLive != true) {
            val elapsed = event.eventTime - (lastHeldSeekAt.takeIf { it > 0 } ?: event.downTime)
            if (elapsed >= PlayerControlsPolicy.SCRUB_TICK_MILLIS) {
                lastHeldSeekAt = event.eventTime
                shell.heldSeekDeltaMillis = PlayerControlsPolicy.scrubTickMillis(event.eventTime - event.downTime, elapsed) * seekDirection
                try { shell.playerCommands?.invoke(if (seekDirection > 0) TvRemoteCommand.SeekForward else TvRemoteCommand.SeekBackward) }
                finally { shell.heldSeekDeltaMillis = null }
            }
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) {
            return super.dispatchKeyEvent(event)
        }
        // BACK belongs to Compose's own back handlers, which know whether a panel, a plate or a
        // dialog is up. Routing it through the key policy as well would tear the bar down
        // underneath the list a viewer is reading.
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        // MENU opens the Live guide while a channel plays, the player bar for other media,
        // and the rail on browse screens. Guide's PiP handling runs before this policy.
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            // A modal sheet/grid owns every key until it closes. Opening the rail here puts focus
            // behind an opaque overlay and leaves the visible surface impossible to navigate.
            if (shell.overlayVisible) return true
            val consumed = shell.playerCommands?.takeIf { shell.playerVisible }
                ?.invoke(if (graph.playback.liveChannel.value != null) TvRemoteCommand.OpenGuide
                    else TvRemoteCommand.ShowControls)
                ?: run { shell.openRail?.invoke(); true }
            if (consumed) return true
        }
        val command = TvRemoteKeyPolicy.command(
            keyCode = event.keyCode,
            playbackActive = shell.playbackOwnsDpad,
            controlsVisible = shell.chromeVisible,
            livePlayback = graph.playback.liveChannel.value != null,
        )
        return when (command) {
            TvRemoteCommand.PassThrough -> {
                val browseDirection = feedbackPool != null && !shell.playbackOwnsDpad &&
                    event.keyCode in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT
                val handled = super.dispatchKeyEvent(event)
                if (handled && browseDirection) playFeedbackSound(R.raw.feedback_browse)
                handled
            }
            // The paste-a-URL dialog went with the old shell; Search owns discovery from the next
            // wave on, so the key falls through rather than opening nothing.
            TvRemoteCommand.PasteUrl -> super.dispatchKeyEvent(event)
            TvRemoteCommand.ToggleDiagnostics -> super.dispatchKeyEvent(event)
            else -> {
                val consumed = shell.playerCommands?.invoke(command) ?: false
                if (consumed) {
                    if (command == TvRemoteCommand.ChannelPrevious || command == TvRemoteCommand.ChannelNext) {
                        channelHoldGesture.begin(event.keyCode, event.eventTime)
                    }
                    playerControlOpeningGesture.openedWith(event.keyCode, command)
                    if (seekDirection != 0) lastHeldSeekAt = event.eventTime
                }
                if (!consumed) {
                    // The one line worth writing: a key the policy turned into a real command and
                    // nothing took. That is what "the remote does nothing" looks like in a log,
                    // and it is rare enough not to flood the 96 KB diagnostics file.
                    ReceiverDiagnostics.record(
                        "ui.key.unhandled",
                        "code=${event.keyCode} cmd=$command player=${shell.playerVisible} " +
                            "chrome=${shell.chromeVisible} overlay=${shell.overlayVisible}",
                    )
                }
                consumed || super.dispatchKeyEvent(event)
            }
        }
    }

    /**
     * Cues, decoded aspect and the phone's subtitle style all belong to the picture layer, not to
     * a Compose route: they must survive a navigation away from the player while a film is still
     * on screen behind a panel.
     */
    private fun observeVideoSideChannels() {
        val session = graph.playback
        lifecycleScope.launch { session.cues.collect { pictures.updateCues() } }
        lifecycleScope.launch { session.videoAspectRatio.collect { pictures.setRatio(it) } }
        lifecycleScope.launch { session.subtitleStyle.collect { pictures.updateSubtitleStyle() } }
    }

    private fun handOffSystemSearch(intent: Intent) {
        val command = SystemSearchIntentParser.parse(
            action = intent.action,
            query = runCatching { intent.getStringExtra(SearchManager.QUERY) }.getOrNull(),
            dataUri = intent.dataString,
            expectedAuthority = "${applicationContext.packageName}.search",
        ) ?: return
        pendingSystemSearchCommands.trySend(command)
    }

    private fun browseCanvasColor(): Int {
        val black = graph.presentationPreferences.getString(
            com.fourseveneightnine.tv.client.ui.screens.settings.SettingsKeys.CANVAS,
            com.fourseveneightnine.tv.client.ui.screens.settings.SettingsKeys.CANVAS_SLATE,
        ) == com.fourseveneightnine.tv.client.ui.screens.settings.SettingsKeys.CANVAS_BLACK
        return (if (black) TvColor.CanvasBlack else TvColor.Canvas).toArgb()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
