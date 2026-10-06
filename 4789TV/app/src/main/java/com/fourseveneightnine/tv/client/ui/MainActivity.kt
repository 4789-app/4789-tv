package com.fourseveneightnine.tv.client.ui

import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.fourseveneightnine.tv.client.AppGraph
import com.fourseveneightnine.tv.client.appGraph
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.data.catalog.SnapshotSource
import com.fourseveneightnine.tv.client.data.catalog.SnapshotState
import com.fourseveneightnine.tv.client.home.TvHomeChannelPublisher
import com.fourseveneightnine.tv.client.playback.ReceiverHost
import com.fourseveneightnine.tv.client.search.SystemSearchCommand
import com.fourseveneightnine.tv.client.search.SystemSearchIntentParser
import com.fourseveneightnine.tv.settings.TVSettingsFileReader
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics
import com.fourseveneightnine.tv.ui.TvRemoteCommand
import com.fourseveneightnine.tv.ui.PlayerControlOpeningGesture
import com.fourseveneightnine.tv.ui.PlayerControlsPolicy
import com.fourseveneightnine.tv.ui.TvRemoteKeyPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    private lateinit var stage: VideoStage
    private val shell = ShellState()
    private val playerControlOpeningGesture = PlayerControlOpeningGesture()
    private var lastHeldSeekAt = 0L
    private val pendingSystemSearchCommands = Channel<SystemSearchCommand>(Channel.BUFFERED)
    private val systemSearchCommands = pendingSystemSearchCommands.receiveAsFlow()

    private val settingsFilePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importSettingsFile(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handOffSystemSearch(intent)
        ReceiverDiagnostics.record("activity.onCreate.begin")
        clientGraph.start()
        lifecycleScope.launch(Dispatchers.IO) {
            clientGraph.snapshots.status()
                .map { it.of(SnapshotSource.PUBLIC_TMDB) }
                .distinctUntilChanged()
                .collect { status ->
                    if (status?.state == SnapshotState.READY && status.complete && status.itemCount > 0) {
                        TvHomeChannelPublisher.reconcile(applicationContext)
                    }
                }
        }
        graph = appGraph
        host = ReceiverHost(this, graph)

        stage = VideoStage(this)
        host.bindSurfaceView(stage.surfaceView)
        stage.surfaceView.holder.addCallback(this)

        val compose = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AppRoot(
                    graph = graph,
                    host = host,
                    shell = shell,
                    onResizeMode = stage::applyResizeMode,
                    onControlBarVisible = stage::setControlBarVisible,
                    onImportSettingsFile = {
                        // Fire OS often reports a file copied into /Download with a vendor MIME
                        // type, or with none at all. Filtering to JSON made a valid settings export
                        // disappear from DocumentsUI, so the bytes are validated after the pick.
                        if (!importLocalSettingsFile()) settingsFilePicker.launch(arrayOf("*/*"))
                    },
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
        setContentView(root)
        // The window has no background drawable (a full-screen fill per frame saved), so the
        // Compose layer paints the canvas itself on every browse screen; it goes transparent only
        // while the player is up and the video surface must show through.
        val canvas = android.graphics.Color.parseColor("#0C0E12")
        compose.setBackgroundColor(canvas)
        lifecycleScope.launch {
            androidx.compose.runtime.snapshotFlow { shell.playerVisible }.collect { visible ->
                stage.setPlayerVisible(visible)
                compose.setBackgroundColor(if (visible) android.graphics.Color.TRANSPARENT else canvas)
            }
        }
        shell.holdLivePicture = { stage.holdCurrentPicture() }
        shell.releaseLivePicture = { stage.releaseHeldPicture(); shell.holdingLivePicture = false }
        lifecycleScope.launch {
            graph.playback.phase.collect { phase ->
                if (phase is com.fourseveneightnine.tv.player.ReceiverPlaybackPhase.Playing ||
                    phase is com.fourseveneightnine.tv.player.ReceiverPlaybackPhase.Paused ||
                    phase is com.fourseveneightnine.tv.player.ReceiverPlaybackPhase.Stopped ||
                    phase is com.fourseveneightnine.tv.player.ReceiverPlaybackPhase.Error)
                    shell.releaseLivePicture?.invoke()
            }
        }
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
        host.onStart()
    }

    override fun onStop() {
        playerControlOpeningGesture.reset()
        host.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        host.onDestroy()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        host.onTrimMemory(level)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars() else playerControlOpeningGesture.reset()
    }

    override fun surfaceCreated(holder: SurfaceHolder) = host.onSurfaceCreated(holder)

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
        host.onSurfaceChanged(holder, width, height)

    override fun surfaceDestroyed(holder: SurfaceHolder) = host.onSurfaceDestroyed()

    /**
     * The remote.
     *
     * [TvRemoteKeyPolicy] decides what a key means from who owns the D-pad and whether the control
     * bar is up. When the player is not drawn the policy returns `PassThrough` for every direction
     * and Compose's own focus search moves the ring, which is exactly what a browse screen wants.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && playerControlOpeningGesture.consumeRelease(event.keyCode)) return true
        if (shell.playbackOwnsDpad && TvRemoteKeyPolicy.isFavoriteKey(event.keyCode) &&
            (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0)) return true
        val seekDirection = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> 1
            else -> 0
        }
        if (event.action == KeyEvent.ACTION_UP && seekDirection != 0) lastHeldSeekAt = 0L
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
        // and the rail on browse screens. Google TV reserves the hardware GUIDE key here.
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
            TvRemoteCommand.PassThrough -> super.dispatchKeyEvent(event)
            // The paste-a-URL dialog went with the old shell; Search owns discovery from the next
            // wave on, so the key falls through rather than opening nothing.
            TvRemoteCommand.PasteUrl -> super.dispatchKeyEvent(event)
            TvRemoteCommand.ToggleDiagnostics -> super.dispatchKeyEvent(event)
            else -> {
                val consumed = shell.playerCommands?.invoke(command) ?: false
                if (consumed) {
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
        lifecycleScope.launch { session.cues.collect { stage.setCues(it) } }
        lifecycleScope.launch { session.videoAspectRatio.collect { stage.setVideoAspectRatio(it) } }
        lifecycleScope.launch { session.subtitleStyle.collect { stage.applySubtitleStyle(it) } }
    }

    private fun importSettingsFile(uri: Uri) {
        lifecycleScope.launch {
            val bytes = runCatching {
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use(TVSettingsFileReader::read)
                        ?: error("settings_file_unavailable")
                }
            }.getOrNull()
            graph.pairing.stageFile(bytes ?: ByteArray(0))
        }
    }

    private fun importLocalSettingsFile(): Boolean {
        // Some TV builds have no DocumentsUI. A file copied into this app's Downloads directory
        // is readable without broad storage permission and follows the same staged approval flow.
        val file = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?.resolve("4789-settings.json")?.takeIf { it.isFile } ?: return false
        lifecycleScope.launch {
            val bytes = runCatching {
                withContext(Dispatchers.IO) { file.inputStream().use(TVSettingsFileReader::read) }
            }.getOrNull()
            graph.pairing.stageFile(bytes ?: ByteArray(0))
        }
        return true
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

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
