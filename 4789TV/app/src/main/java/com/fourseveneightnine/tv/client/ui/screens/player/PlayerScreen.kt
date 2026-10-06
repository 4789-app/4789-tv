package com.fourseveneightnine.tv.client.ui.screens.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.fourseveneightnine.tv.client.iptv.iptvPlaybackHeaders
import com.fourseveneightnine.tv.client.data.images.PosterRequest
import com.fourseveneightnine.tv.protocol.NowPlayingArt
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.R
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.ui.screens.live.LiveVideoTile
import com.fourseveneightnine.tv.client.ui.screens.live.tvMultiviewDecoderSlots
import com.fourseveneightnine.tv.client.iptv.IptvChannel
import com.fourseveneightnine.tv.client.iptv.IptvProgram
import com.fourseveneightnine.tv.client.iptv.guideKind
import com.fourseveneightnine.tv.client.iptv.IptvIndex
import com.fourseveneightnine.tv.client.iptv.IptvRepository
import com.fourseveneightnine.tv.client.iptv.IptvState
import com.fourseveneightnine.tv.client.playback.NextUpPlan
import com.fourseveneightnine.tv.client.playback.PlaybackSession
import com.fourseveneightnine.tv.client.playback.PlayResult
import com.fourseveneightnine.tv.client.ui.LocalShellState
import com.fourseveneightnine.tv.client.ui.components.ButtonKind
import com.fourseveneightnine.tv.client.ui.components.SidePanel
import com.fourseveneightnine.tv.client.ui.components.SidePanelRow as SharedSidePanelRow
import com.fourseveneightnine.tv.client.ui.components.TvButton
import com.fourseveneightnine.tv.client.ui.components.TvFocusable
import com.fourseveneightnine.tv.client.ui.components.TvProgressBar
import com.fourseveneightnine.tv.client.ui.components.tvFocusRing
import com.fourseveneightnine.tv.client.ui.screens.live.LiveFormField
import com.fourseveneightnine.tv.client.ui.theme.LocalReduceMotion
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvShape
import com.fourseveneightnine.tv.client.ui.theme.TvSpace
import com.fourseveneightnine.tv.client.ui.theme.TvType
import com.fourseveneightnine.tv.player.PlaybackDiagnostics
import com.fourseveneightnine.tv.player.ReceiverPlaybackPhase
import com.fourseveneightnine.tv.player.ReceiverSubtitleStyle
import com.fourseveneightnine.tv.player.upscale.UpscaleMode
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics
import com.fourseveneightnine.tv.protocol.InstalledExternalPlayer
import com.fourseveneightnine.tv.protocol.OpenMediaRequest
import com.fourseveneightnine.tv.protocol.SubtitleSelection
import com.fourseveneightnine.tv.ui.ArtworkLoader
import com.fourseveneightnine.tv.ui.PlayerControlsPolicy
import com.fourseveneightnine.tv.ui.TvRemoteCommand
import com.fourseveneightnine.tv.ui.VideoResizeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

/** Which full-height picker is open over the video, spec §11.6. */
private enum class PlayerPanel {
    Audio,
    Subtitles,
    SubtitleStyle,
    More,
    Speed,
    Aspect,
    Upscale,
    PlaybackInfo,
    Chapters,
    Handoff,
}

private enum class LiveBrowseMode { Current, Groups, Favorites, Recents, Search }

private fun playerPanelParent(panel: PlayerPanel): PlayerPanel? = when (panel) {
    PlayerPanel.SubtitleStyle -> PlayerPanel.Subtitles
    PlayerPanel.Audio, PlayerPanel.Subtitles, PlayerPanel.More -> null
    PlayerPanel.Speed,
    PlayerPanel.Aspect,
    PlayerPanel.Upscale,
    PlayerPanel.PlaybackInfo,
    PlayerPanel.Chapters,
    PlayerPanel.Handoff,
    -> PlayerPanel.More
}

/**
 * The player, spec §11.
 *
 * Nothing is drawn over the video by default. LEFT and RIGHT seek and show only the top-right
 * badge; DOWN, UP or OK raises the control bar. Ended and Stopped are different plates and are
 * never swapped: the film running to its own end is a decision point, and a stop somebody asked
 * for is the "ready for your iPhone" screen.
 *
 * The `SurfaceView` this draws over is owned by the Activity, not by this route — see
 * [com.fourseveneightnine.tv.client.ui.VideoStage] for why.
 */
@Composable
internal fun PlayerScreen(
    session: PlaybackSession,
    artworkLoader: ArtworkLoader,
    receiverName: String,
    receiverAddress: String?,
    onResizeMode: (VideoResizeMode) -> Unit,
    onControlBarVisible: (Boolean) -> Unit,
    onLocalControl: (String) -> Unit,
    onLeave: () -> Unit,
    /**
     * Open the Streams list for what is playing, spec §11.4 pill 6 and §11.6.
     *
     * Null when nothing has wired it, and the pill is then MISSING rather than disabled: a pill
     * that opens nothing is worse than one that is not there. `AppRoot` owns navigation and this
     * screen has no `ClientNav`, so the shell passes the callback in when it wires this route.
     */
    onOpenSources: (() -> Unit)? = null,
) {
    val shell = LocalShellState.current
    val appContext = LocalContext.current
    val playFlow = remember(appContext) { appContext.clientGraph.playFlow }
    val iptv = remember(appContext) { appContext.clientGraph.iptv }
    val iptvState by iptv.state.collectAsState()
    val liveChannel by session.liveChannel.collectAsState()
    val currentIptvVod by session.iptvVod.collectAsState()
    val scope = rememberCoroutineScope()
    // The local next episode. It carries no URL — §11.7 resolves its sources while the plate
    // counts down — so it cannot ride on `OpenMediaRequest.nextUp`, which the phone still owns.
    val localNextUp by playFlow.nextUp.collectAsState()
    val localRequest by playFlow.current.collectAsState()
    val phase by session.phase.collectAsState()
    val origin by session.origin.collectAsState()
    val art by session.nowPlayingArt.collectAsState()

    var chromeVisible by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf<PlayerPanel?>(null) }
    var liveGuideVisible by remember { mutableStateOf(false) }
    var liveGroup by remember { mutableStateOf<String?>(null) }
    var liveStatus by remember { mutableStateOf<String?>(null) }
    var favoriteNotice by remember { mutableStateOf<String?>(null) }
    var focusedGuideChannel by remember { mutableStateOf<IptvChannel?>(null) }
    var tuning by remember { mutableStateOf(false) }
    var lastWorkingChannel by remember { mutableStateOf<IptvChannel?>(null) }
    var leavingPlayer by remember { mutableStateOf(false) }
    var seekBadgeMillis by remember { mutableStateOf(0L) }
    var seekBadgeShownAt by remember { mutableStateOf(0L) }
    var pendingSeekMillis by remember { mutableStateOf<Long?>(null) }
    var lastSeekPressAt by remember { mutableStateOf(0L) }
    var speed by remember { mutableStateOf(1.0f) }
    var resizeMode by remember { mutableStateOf(VideoResizeMode.Fit) }
    var upscaleMode by remember { mutableStateOf(session.upscaleMode()) }
    var chromeTick by remember { mutableStateOf(0) }

    LaunchedEffect(favoriteNotice) {
        if (favoriteNotice != null) {
            delay(2_500)
            favoriteNotice = null
        }
    }

    val media = remember(phase) { session.lastOpenMedia() }
    // media3 delivers STATE_IDLE synchronously after `player.stop()`, and the engine's IDLE
    // handler overwrites Stopped with Idle a moment later — so Stopped is a value that exists for
    // one instant and Idle is what a stopped receiver actually rests in. The plate follows what
    // the viewer sees, not the instant: once this title has left Idle, Idle means "it stopped".
    var hasLeftIdle by remember { mutableStateOf(false) }
    LaunchedEffect(phase) { if (phase !is ReceiverPlaybackPhase.Idle) hasLeftIdle = true }
    val stopped = phase is ReceiverPlaybackPhase.Stopped ||
        (phase is ReceiverPlaybackPhase.Idle && hasLeftIdle)
    // PackageManager queries are Binder calls and are slow on Fire OS. The rescue buttons are
    // resolved once, off the main thread, and only when an error plate or More menu needs them.
    var externalPlayers by remember { mutableStateOf(emptyList<InstalledExternalPlayer>()) }
    var externalPlayersLoaded by remember { mutableStateOf(false) }
    val plateVisible = phase is ReceiverPlaybackPhase.Ended ||
        stopped ||
        phase is ReceiverPlaybackPhase.Error

    // What is drawn decides who owns the D-pad. Nothing else may.
    DisposableEffect(Unit) {
        shell.playerVisible = true
        onDispose {
            shell.playerVisible = false
            shell.chromeVisible = false
            shell.overlayVisible = false
            shell.playerCommands = null
            shell.releaseLivePicture?.invoke()
            onControlBarVisible(false)
        }
    }
    LaunchedEffect(chromeVisible, panel, liveGuideVisible, plateVisible) {
        shell.chromeVisible = chromeVisible
        shell.overlayVisible = panel != null || liveGuideVisible || plateVisible
        onControlBarVisible(chromeVisible)
    }

    // The bar hides after 5200 ms with no key press. It does not hide while paused, and it does
    // not hide while a panel is open (spec §11.4).
    LaunchedEffect(chromeVisible, chromeTick, panel, liveGuideVisible, phase) {
        if (!chromeVisible || panel != null || liveGuideVisible) return@LaunchedEffect
        if (phase is ReceiverPlaybackPhase.Paused) return@LaunchedEffect
        delay(com.fourseveneightnine.tv.client.ui.theme.TvMotion.ControlsAutoHideMillis)
        chromeVisible = false
    }

    LaunchedEffect(seekBadgeShownAt) {
        if (seekBadgeShownAt == 0L) return@LaunchedEffect
        delay(com.fourseveneightnine.tv.client.ui.theme.TvMotion.SeekBadgeMillis)
        seekBadgeMillis = 0L
        seekBadgeShownAt = 0L
        pendingSeekMillis = null
        lastSeekPressAt = 0L
    }

    // A poll only while the bar is up: the playhead is a socket round trip on the mpv path.
    LaunchedEffect(chromeVisible) {
        while (chromeVisible) {
            session.refreshSnapshot()
            delay(PlayerControlsPolicy.POSITION_POLL_MILLIS)
        }
    }

    LaunchedEffect(panel) {
        if (panel == PlayerPanel.Audio || panel == PlayerPanel.Subtitles) session.refreshTracks()
    }
    // The bar's "Audio · …" and "Subtitles · …" labels read the same track list. Refreshed only for
    // the panels, the bar said "Subtitles · Off" over subtitles the engine had picked itself.
    LaunchedEffect(chromeVisible) {
        if (chromeVisible) session.refreshTracks()
    }

    // A replacement open is a new title. Its prior panel and raised bar must not survive it.
    LaunchedEffect(media?.url) {
        panel = null
        liveGuideVisible = false
        leavingPlayer = false
        chromeVisible = false
        seekBadgeMillis = 0L
        seekBadgeShownAt = 0L
    }

    LaunchedEffect(phase, panel) {
        val needsPlayers = phase is ReceiverPlaybackPhase.Error ||
            panel == PlayerPanel.More || panel == PlayerPanel.Handoff
        if (needsPlayers && !externalPlayersLoaded) {
            externalPlayers = withContext(Dispatchers.Default) { session.installedPlayers() }
            externalPlayersLoaded = true
        }
    }

    val scrub = remember { ScrubAccelerator() }
    val nextLabel = media?.nextUp?.subtitle
        ?: media?.nextUp?.title
        ?: localNextUp?.line?.takeIf { origin == PlaybackSession.Origin.Local }
    val continuationOrigin = origin ?: PlaybackSession.Origin.Local
    val playNext: () -> Unit = {
        val phoneNext = media?.nextUp
        if (phoneNext != null) {
            session.launchOn {
                open(
                    OpenMediaRequest(
                        url = phoneNext.url,
                        title = phoneNext.title,
                        subtitle = phoneNext.subtitle,
                        headers = phoneNext.headers,
                    ),
                    continuationOrigin,
                )
            }
        } else if (localNextUp != null) {
            // No URL yet: the flow searches, ranks and resolves the next episode with the same
            // picking rules, then opens it (§11.7).
            session.uiScope.launch { playFlow.playNextUp() }
        }
    }
    // `playFlow.current` intentionally survives local playback. Origin is therefore the authority:
    // a later iPhone cast must not expose the previous local title's Sources list.
    val openSources = onOpenSources?.takeIf { origin == PlaybackSession.Origin.Local && localRequest != null }
    val lastTunedChannel = remember(liveChannel?.id, iptvState.accounts.recentIds,
        iptvState.catalog.channels, iptvState.accounts.hiddenGroups, iptvState.accounts.lockedGroups) {
        val byId = iptvState.channels.associateBy(IptvChannel::id)
        iptvState.accounts.recentIds.firstNotNullOfOrNull { id -> byId[id]?.takeIf { candidate ->
            candidate.id != liveChannel?.id && candidate.group !in iptvState.accounts.hiddenGroups &&
                candidate.group !in iptvState.accounts.lockedGroups
        } }
    }

    LaunchedEffect(phase, liveChannel?.id, tuning, iptvState.catalog.channels) {
        if (!tuning && phase is ReceiverPlaybackPhase.Playing) {
            lastWorkingChannel = iptvState.channels.firstOrNull { it.id == liveChannel?.id }
        }
    }

    fun tune(channel: IptvChannel, force: Boolean = false) {
        if (tuning) return
        if (!force && channel.id == liveChannel?.id && phase is ReceiverPlaybackPhase.Playing) {
            liveGuideVisible = false
            return
        }
        tuning = true
        liveStatus = "Connecting to ${channel.name}…"
        scope.launch {
            try {
                // Let composition release the muted preview before opening a new provider stream.
                withFrameNanos { }
                val result = runCatching {
                    val url = iptv.playUrl(channel)
                    shell.holdingLivePicture = shell.holdLivePicture?.invoke() == true
                    session.openLiveChannel(OpenMediaRequest(url = url, title = channel.name,
                        isLive = true, headers = iptvPlaybackHeaders(channel.headers)),
                        PlaybackSession.LiveChannel(channel.id, channel.sourceId, channel.group)).getOrThrow()
                    when (val outcome = session.awaitLiveOutcome()) {
                        is ReceiverPlaybackPhase.Playing -> {
                            if (session.liveChannel.value?.id != channel.id) error("Playback changed")
                            iptv.markPlayed(channel.id)
                            lastWorkingChannel = channel
                        }
                        is ReceiverPlaybackPhase.Error -> error(outcome.message)
                        null -> error("The channel is taking too long to connect")
                        else -> error("Playback stopped before this channel started")
                    }
                }
                result.exceptionOrNull()?.let { failure ->
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                    ReceiverDiagnostics.record("iptv.tune.failed", "type=${failure.javaClass.simpleName}")
                }
                liveStatus = result.exceptionOrNull()?.let { "Couldn't tune ${channel.name}. Try again or return to the previous channel." }
                if (result.isSuccess) liveGuideVisible = false
                else liveGuideVisible = true
            } finally {
                shell.releaseLivePicture?.invoke()
                tuning = false
            }
        }
    }

    fun adjacent(direction: Int): IptvChannel? {
        val active = liveChannel ?: return null
        val visible = iptvState.channels.filter { it.sourceId == active.sourceId &&
            it.group !in iptvState.accounts.hiddenGroups && it.group !in iptvState.accounts.lockedGroups }
        val group = visible.filter { it.group == active.group }
        val candidates = group
        if (candidates.size < 2) return null
        val index = candidates.indexOfFirst { it.id == active.id }
        if (index < 0) return null
        return candidates[(index + direction + candidates.size) % candidates.size]
    }

    fun toggleFavorite(id: String?): Boolean {
        if (id == null) return false
        scope.launch {
            favoriteNotice = when (iptv.toggleFavorite(id)) {
                true -> "Added to Favorites"
                false -> "Removed from Favorites"
                null -> "Couldn't save Favorite"
            }
        }
        return true
    }

    fun stopAndLeave() {
        if (leavingPlayer) return
        leavingPlayer = true
        session.launchOn {
            runCatching { playFlow.captureStopProgress() }
            val stopped = stop()
            if (stopped.isSuccess) onLeave() else {
                leavingPlayer = false
                liveStatus = "Couldn't stop playback. Try again."
                ReceiverDiagnostics.record("player.stop.failed",
                    "type=${stopped.exceptionOrNull()?.javaClass?.simpleName ?: "Unknown"}")
            }
        }
    }

    shell.playerCommands = handler@{ command ->
        val snapshot = session.snapshot.value
        when (command) {
            TvRemoteCommand.ShowControls -> {
                chromeVisible = true
                chromeTick++
                true
            }
            TvRemoteCommand.HideControls -> {
                chromeVisible = false
                true
            }
            TvRemoteCommand.TogglePlayPause -> {
                chromeVisible = true
                chromeTick++
                session.launchOn { togglePlayPause() }
                onLocalControl(if (snapshot.speed == 0) "resume" else "pause")
                true
            }
            TvRemoteCommand.Play -> {
                session.launchOn { if (snapshot.active && snapshot.speed == 0) togglePlayPause() }
                true
            }
            TvRemoteCommand.Pause -> {
                session.launchOn { if (snapshot.active && snapshot.speed != 0) togglePlayPause() }
                true
            }
            TvRemoteCommand.SeekBackward, TvRemoteCommand.SeekForward -> {
                // Live channels have no seekable timeline. On a bare video surface, the D-pad
                // steps through channels; with chrome raised, focus owns LEFT/RIGHT instead.
                if (liveChannel != null) {
                    adjacent(if (command == TvRemoteCommand.SeekForward) 1 else -1)?.let { tune(it) }
                    return@handler true
                }
                val forward = command == TvRemoteCommand.SeekForward
                val step = shell.heldSeekDeltaMillis ?: (scrub.stepMillis() * if (forward) 1 else -1)
                val now = android.os.SystemClock.elapsedRealtime()
                seekBadgeMillis += step
                seekBadgeShownAt = now
                val target = PlayerControlsPolicy.scrubTarget(
                    currentMillis = pendingSeekMillis?.takeIf { now - lastSeekPressAt < 1_500L }
                        ?: (snapshot.positionSeconds * 1_000).toLong(),
                    stepMillis = step,
                    durationMillis = (snapshot.durationSeconds * 1_000).toLong(),
                )
                pendingSeekMillis = target
                lastSeekPressAt = now
                // The controller coalesces a burst into one seek (SeekCoalescingPolicy), so a held
                // key is one decoder flush rather than one per repeat.
                session.launchOn { seekTo(target) }
                true
            }
            TvRemoteCommand.Stop -> {
                stopAndLeave()
                true
            }
            TvRemoteCommand.ChannelPrevious, TvRemoteCommand.ChannelNext -> {
                if (liveChannel == null || liveGuideVisible || tuning) false else {
                    adjacent(if (command == TvRemoteCommand.ChannelNext) 1 else -1)?.let { tune(it) }
                    true
                }
            }
            TvRemoteCommand.OpenGuide -> {
                if (liveChannel == null) false else {
                    liveGroup = liveChannel?.group
                    liveGuideVisible = true
                    true
                }
            }
            TvRemoteCommand.ToggleFavorite -> {
                val id = (if (liveGuideVisible) focusedGuideChannel?.id else null)
                    ?: liveChannel?.id
                toggleFavorite(id)
            }
            else -> false
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Opening and Buffering, spec §11.11.
        when (phase) {
            is ReceiverPlaybackPhase.Opening -> if (!shell.holdingLivePicture) OpeningOverlay(art, media?.title, artworkLoader)
            is ReceiverPlaybackPhase.Buffering -> BufferingOverlay()
            else -> Unit
        }

        if (chromeVisible && origin == PlaybackSession.Origin.Phone && !plateVisible) CastChip()

        if (seekBadgeMillis != 0L && !chromeVisible) SeekBadge(seekBadgeMillis)

        if (chromeVisible && !plateVisible) {
            PlayingControlBar(
                session = session,
                title = media?.title.orEmpty(),
                subtitle = media?.subtitle,
                chapterSeconds = media?.chapterSeconds.orEmpty(),
                speedLabel = PlayerControlsPolicy.formatSpeed(speed),
                aspectLabel = stringResource(resizeMode.labelRes()),
                upscaleLabel = upscaleLabel(upscaleMode),
                onInteraction = { chromeTick++ },
                onScrub = { forward ->
                    val snap = session.snapshot.value
                    val step = scrub.stepMillis() * if (forward) 1 else -1
                    val now = android.os.SystemClock.elapsedRealtime()
                    val target = PlayerControlsPolicy.scrubTarget(
                        currentMillis = pendingSeekMillis?.takeIf { now - lastSeekPressAt < 1_500L }
                            ?: (snap.positionSeconds * 1_000).toLong(),
                        stepMillis = step,
                        durationMillis = (snap.durationSeconds * 1_000).toLong(),
                    )
                    pendingSeekMillis = target
                    lastSeekPressAt = now
                    session.launchOn { seekTo(target) }
                    chromeTick++
                },
                onTogglePlay = {
                    session.launchOn { togglePlayPause() }
                    onLocalControl(if (session.snapshot.value.speed == 0) "resume" else "pause")
                    chromeTick++
                },
                onPanel = { panel = it; chromeTick++ },
                liveChannel = liveChannel,
                lastChannelAvailable = lastTunedChannel != null,
                onPreviousChannel = { adjacent(-1)?.let { tune(it) } },
                onNextChannel = { adjacent(1)?.let { tune(it) } },
                onLastChannel = { lastTunedChannel?.let { tune(it) } },
                onLiveGuide = { liveGroup = liveChannel?.group; liveGuideVisible = true },
                isFavorite = liveChannel?.id in iptvState.accounts.favoriteIds,
                onToggleFavorite = { toggleFavorite(liveChannel?.id) },
            )
        }

        (favoriteNotice ?: liveStatus)?.takeIf { !liveGuideVisible }?.let { status ->
            Text(status, style = TvType.Meta, color = TvColor.TextPrimary,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 80.dp))
        }

        when (val current = phase) {
            // A local series with a known next episode gets the countdown card (§11.7). Everything
            // else — a film, a cast, a last episode — gets the Ended plate, which is a different
            // plate and is never swapped for it.
            is ReceiverPlaybackPhase.Ended -> {
                val plan = localNextUp
                    ?.takeIf { origin == PlaybackSession.Origin.Local && media?.nextUp == null }
                if (plan != null) {
                    NextUpPlate(
                        plan = plan,
                        onNext = { session.uiScope.launch { playFlow.playNextUp() } },
                        onReplay = {
                            media?.let { m -> session.launchOn { open(m, continuationOrigin) } }
                        },
                        onDone = ::stopAndLeave,
                    )
                } else {
                    EndedPlate(
                        title = media?.title,
                        nextTitle = media?.nextUp?.title,
                        nextSubtitle = media?.nextUp?.subtitle,
                        onReplay = {
                            media?.let { m -> session.launchOn { open(m, continuationOrigin) } }
                        },
                        onNext = media?.nextUp?.let { next ->
                            {
                                session.launchOn {
                                    open(
                                        OpenMediaRequest(
                                            url = next.url,
                                            title = next.title,
                                            subtitle = next.subtitle,
                                            headers = next.headers,
                                        ),
                                        continuationOrigin,
                                    )
                                }
                            }
                        },
                        onDone = ::stopAndLeave,
                    )
                }
            }

            is ReceiverPlaybackPhase.Error -> ErrorPlate(
                message = liveStatus ?: current.message,
                players = externalPlayers,
                onRetry = {
                    session.launchOn {
                        val position = session.resumePositionMillis()
                        val vod = currentIptvVod
                        val channel = liveChannel?.let { active -> iptvState.channels.firstOrNull { it.id == active.id } }
                        if (channel != null) tune(channel, force = true)
                        else if (vod != null) {
                            val renewed = try { iptv.renewVod(vod) }
                            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                            catch (_: Exception) { null }
                            if (renewed != null && media != null) openIptvVod(
                                media.copy(url = renewed.second, headers = iptvPlaybackHeaders(renewed.first.headers), startPositionMs = position), renewed.first)
                            else liveStatus = "Couldn't refresh this video link. Refresh the source and try again."
                        }
                        else if (origin == PlaybackSession.Origin.Local && localRequest != null) {
                            when (playFlow.retryCurrent(position)) {
                                PlayResult.Opened -> Unit
                                else -> if (openSources != null) openSources() else retryLastOpen()
                            }
                        } else retryLastOpen()
                    }
                },
                onHandoff = { player -> session.launchOn { externalHandoff(player) } },
                onDone = ::stopAndLeave,
            )

            else -> if (stopped) StoppedPlate(receiverName, receiverAddress, onLeave)
        }

        panel?.let { open ->
            PlayerSidePanel(
                panel = open,
                session = session,
                speed = speed,
                resizeMode = resizeMode,
                upscaleMode = upscaleMode,
                chapterSeconds = media?.chapterSeconds.orEmpty(),
                showSources = openSources != null,
                liveSourceStatus = liveChannel?.let { active ->
                    iptvState.sources.firstOrNull { it.id == active.sourceId }?.let { source ->
                        source.maxConnections?.let { "$it ${if (it == 1) "stream" else "streams"} allowed" }
                            ?: "Stream limit unknown"
                    }
                },
                nextLabel = nextLabel,
                externalPlayers = externalPlayers,
                onSpeed = { speed = it; session.launchOn { setSpeedMultiplier(it) } },
                onResize = { resizeMode = it; onResizeMode(it) },
                onUpscale = { upscaleMode = it; session.setUpscale(it) },
                onChapter = { chapterSeconds ->
                    session.launchOn { seekTo((chapterSeconds * 1_000.0).toLong()) }
                    panel = PlayerPanel.More
                },
                onNavigate = { panel = it },
                onSources = { openSources?.invoke() },
                onRestartLive = {
                    panel = null
                    liveChannel?.let { active -> iptvState.channels.firstOrNull { it.id == active.id } }
                        ?.let { tune(it, force = true) }
                },
                onNext = playNext,
                onHandoff = { player -> session.launchOn { externalHandoff(player) } },
                onClose = {
                    panel = playerPanelParent(open)
                    chromeTick++
                },
            )
        }

        if (liveGuideVisible) (if (tuning || liveStatus != null) lastWorkingChannel?.let {
            PlaybackSession.LiveChannel(it.id, it.sourceId, it.group)
        } ?: liveChannel else liveChannel)?.let { active ->
            LiveGuidePanel(iptv, iptvState, active, liveGroup, liveStatus, favoriteNotice,
                onGroup = { liveGroup = it }, onTune = { tune(it) },
                returnChannel = lastWorkingChannel?.takeIf { it.id != liveChannel?.id },
                allowPreview = !tuning,
                onFocusedChannel = { focusedGuideChannel = it },
                onClose = { liveGuideVisible = false })
        }

    }

    // BACK closes one modal panel at a time; from the player itself it stops and returns.
    BackHandler(enabled = true) {
        val snapshot = session.snapshot.value
        ReceiverDiagnostics.record(
            "player.back",
            "panel=${panel != null} plate=$plateVisible chrome=$chromeVisible " +
                "active=${snapshot.active}",
        )
        when {
            liveGuideVisible -> liveGuideVisible = false
            panel != null -> panel = panel?.let(::playerPanelParent)
            else -> stopAndLeave()
        }
    }
}

/**
 * The step a held D-pad takes: 10 s, then 30 s after three repeats, then 60 s after eight
 * (spec §11.5). The ramp reads from the burst, not from the remote's repeat rate, so a fast
 * remote and a slow one scrub at the same speed.
 */
private class ScrubAccelerator {
    private var pressCount = 0
    private var lastPressAt = 0L

    fun stepMillis(): Long {
        val now = android.os.SystemClock.elapsedRealtime()
        pressCount = if (now - lastPressAt > BURST_GAP_MILLIS) 1 else pressCount + 1
        lastPressAt = now
        return when {
            pressCount > 8 -> 60_000L
            pressCount > 3 -> 30_000L
            else -> PlayerControlsPolicy.BASE_SCRUB_MILLIS
        }
    }

    private companion object { const val BURST_GAP_MILLIS = 600L }
}

// ---------------------------------------------------------------------------- chrome

@Composable
private fun SeekBadge(totalMillis: Long) {
    Box(
        Modifier
            .offset(x = 1584.dp, y = 96.dp)
            .width(200.dp)
            .height(72.dp)
            .clip(TvShape.Control)
            .background(TvColor.Canvas.copy(alpha = 0.78f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = formatSeekDelta(totalMillis),
            style = TvType.data(32),
            color = TvColor.TextPrimary,
        )
    }
}

/**
 * The running total, spec §11.3: "+10s", then "+20s", then "+1m 30s". A held LEFT reads as one
 * number rather than a flicker of tens, so this is a total and never a step.
 */
private fun formatSeekDelta(totalMillis: Long): String {
    val sign = if (totalMillis < 0) "-" else "+"
    val seconds = kotlin.math.abs(totalMillis) / 1_000
    return if (seconds < 60) {
        "$sign${seconds}s"
    } else {
        "$sign${seconds / 60}m ${seconds % 60}s"
    }
}

/**
 * The playhead lives here, not on [PlayerScreen]. A position poll every 500 ms used to
 * recompose the whole player, including the surface the film is drawn under.
 */
@Composable
private fun PlayingControlBar(
    session: PlaybackSession,
    title: String,
    subtitle: String?,
    chapterSeconds: List<Double>,
    speedLabel: String,
    aspectLabel: String,
    upscaleLabel: String,
    onInteraction: () -> Unit,
    onScrub: (Boolean) -> Unit,
    onTogglePlay: () -> Unit,
    onPanel: (PlayerPanel) -> Unit,
    liveChannel: PlaybackSession.LiveChannel?,
    lastChannelAvailable: Boolean,
    onPreviousChannel: () -> Unit,
    onNextChannel: () -> Unit,
    onLastChannel: () -> Unit,
    onLiveGuide: () -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
) {
    val snapshot by session.snapshot.collectAsState()
    val tracks by session.tracks.collectAsState()
    ControlBar(
        title = title,
        subtitle = subtitle,
        positionMillis = (snapshot.positionSeconds * 1_000).toLong(),
        durationMillis = (snapshot.durationSeconds * 1_000).toLong(),
        bufferedMillis = (snapshot.bufferedSeconds * 1_000).toLong(),
        chapterSeconds = chapterSeconds,
        isPlaying = snapshot.speed != 0,
        audioLabel = tracks.currentAudio?.let {
            PlayerControlsPolicy.trackLabel(it.language, it.name, it.codec, it.channels, "Track ${it.index + 1}")
        } ?: "Auto",
        subtitleLabel = tracks.currentSubtitle?.let {
            PlayerControlsPolicy.trackLabel(it.language, it.name, null, null, "Track ${it.index + 1}")
        } ?: "Off",
        speedLabel = speedLabel,
        aspectLabel = aspectLabel,
        upscaleLabel = upscaleLabel,
        onInteraction = onInteraction,
        onScrub = onScrub,
        onTogglePlay = onTogglePlay,
        onPanel = onPanel,
        liveChannel = liveChannel,
        lastChannelAvailable = lastChannelAvailable,
        onPreviousChannel = onPreviousChannel,
        onNextChannel = onNextChannel,
        onLastChannel = onLastChannel,
        onLiveGuide = onLiveGuide,
        isFavorite = isFavorite,
        onToggleFavorite = onToggleFavorite,
    )
}

@Composable
private fun CastChip() {
    Row(
        Modifier
            .offset(x = 220.dp, y = 96.dp)
            .width(340.dp)
            .height(60.dp)
            .clip(TvShape.Control)
            .background(TvColor.Canvas.copy(alpha = 0.78f))
            .border(1.dp, TvColor.Focus.copy(alpha = 0.4f), TvShape.Control)
            .semantics(mergeDescendants = true) {
                contentDescription = "Cast status"
                stateDescription = "iPhone is driving playback"
            }
            .padding(horizontal = TvSpace.M),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(TvColor.Focus))
        Spacer(Modifier.width(12.dp))
        Text("iPhone is driving", style = TvType.CardTitle, color = TvColor.TextPrimary)
    }
}

@Composable
private fun ControlBar(
    title: String,
    subtitle: String?,
    positionMillis: Long,
    durationMillis: Long,
    bufferedMillis: Long,
    chapterSeconds: List<Double>,
    isPlaying: Boolean,
    audioLabel: String,
    subtitleLabel: String,
    speedLabel: String,
    aspectLabel: String,
    upscaleLabel: String,
    onInteraction: () -> Unit,
    onScrub: (forward: Boolean) -> Unit,
    onTogglePlay: () -> Unit,
    onPanel: (PlayerPanel) -> Unit,
    liveChannel: PlaybackSession.LiveChannel?,
    lastChannelAvailable: Boolean,
    onPreviousChannel: () -> Unit,
    onNextChannel: () -> Unit,
    onLastChannel: () -> Unit,
    onLiveGuide: () -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
) {
    val playFocus = remember { FocusRequester() }
    val audioWidth = if (liveChannel == null) 360.dp else 420.dp
    val subtitleWidth = if (liveChannel == null) 420.dp else 360.dp
    val moreWidth = if (liveChannel == null) 400.dp else 300.dp
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(460.dp)
                .align(Alignment.BottomStart)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to TvColor.Canvas.copy(alpha = 0.92f),
                    ),
                ),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(start = 220.dp, bottom = 44.dp)) {
            Text(
                text = listOfNotNull(title.takeIf(String::isNotBlank), subtitle).joinToString(" · "),
                style = TvType.PlateTitle,
                color = TvColor.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(1480.dp),
            )
            Spacer(Modifier.height(28.dp))
            if (liveChannel == null) {
                SeekTrack(
                    positionMillis = positionMillis,
                    durationMillis = durationMillis,
                    bufferedMillis = bufferedMillis,
                    chapterSeconds = chapterSeconds,
                    onScrub = onScrub,
                )
                Spacer(Modifier.height(14.dp))
                Row(Modifier.width(1480.dp)) {
                    Text(
                        text = "${PlayerControlsPolicy.formatTime(positionMillis)} / " +
                            PlayerControlsPolicy.formatTime(durationMillis),
                        style = TvType.data(24),
                        color = TvColor.TextPrimary,
                    )
                    Spacer(Modifier.weight(1f))
                    endsAt(positionMillis, durationMillis)?.let {
                        Text("Ends at $it", style = TvType.Meta, color = TvColor.TextSecondary)
                    }
                }
            } else {
                Text("LIVE", style = TvType.ControlLabel, color = TvColor.Cached)
            }
            Spacer(Modifier.height(28.dp))
            if (liveChannel != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(TvSpace.S)) {
                    Pill(if (isFavorite) "★ Saved" else "☆ Favorite", null, Modifier.width(210.dp)) {
                        onToggleFavorite(); onInteraction()
                    }
                    Pill("Previous", null, Modifier.width(180.dp)) { onPreviousChannel(); onInteraction() }
                    if (lastChannelAvailable) Pill("Last", null, Modifier.width(125.dp)) {
                        onLastChannel(); onInteraction()
                    }
                    Pill("Guide", null, Modifier.width(150.dp)) { onLiveGuide(); onInteraction() }
                    Pill("Next", null, Modifier.width(150.dp)) { onNextChannel(); onInteraction() }
                }
                Spacer(Modifier.height(12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(TvSpace.S)) {
                Pill(
                    label = if (isPlaying) "Pause" else "Play",
                    value = null,
                    onClick = { onTogglePlay(); onInteraction() },
                    modifier = Modifier.width(156.dp).focusRequester(playFocus),
                )
                Pill("Audio", audioLabel, Modifier.width(audioWidth)) { onPanel(PlayerPanel.Audio) }
                Pill("Subtitles", subtitleLabel, Modifier.width(subtitleWidth)) { onPanel(PlayerPanel.Subtitles) }
                Pill(
                    "More",
                    playerMenuLabel(speedLabel, "$aspectLabel · $upscaleLabel", maxChars = 22),
                    Modifier.width(moreWidth),
                ) { onPanel(PlayerPanel.More) }
            }
        }
    }
    // Initial focus when the bar is raised is the play or pause pill (spec §11.13).
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }
}

/**
 * Every pill carries its LABEL AND ITS CURRENT VALUE. A viewer cannot check a setting they have to
 * change in order to read — that is the `CONTEXT.md` §6 rule, and it holds here.
 */
@Composable
private fun Pill(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    TvFocusable(
        onClick = onClick,
        modifier = modifier,
        accessibleLabel = listOfNotNull(label, value).joinToString(", "),
        cornerRadius = 10.dp,
        focusScale = 1f,
        focusRing = false,
    ) { focused ->
        Row(
            Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(TvShape.Control)
                .background(if (focused) TvColor.TextPrimary else TvColor.Elevated.copy(alpha = 0.92f))
                .border(1.dp, Color.White.copy(alpha = if (focused) 0f else 0.12f), TvShape.Control)
                .padding(horizontal = TvSpace.M),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = TvType.ControlLabel, color = if (focused) TvColor.Canvas else TvColor.TextPrimary, maxLines = 1)
            if (value != null) {
                Text(" · ", style = TvType.ControlLabel, color = if (focused) TvColor.Canvas else TvColor.TextMuted)
                Text(
                    value,
                    style = TvType.ControlLabel,
                    color = if (focused) TvColor.Canvas else TvColor.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SeekTrack(
    positionMillis: Long,
    durationMillis: Long,
    bufferedMillis: Long,
    chapterSeconds: List<Double>,
    onScrub: (forward: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val fraction = if (durationMillis > 0) positionMillis.toFloat() / durationMillis else 0f
    val buffered = if (durationMillis > 0) bufferedMillis.toFloat() / durationMillis else 0f
    val chapterFractions = remember(chapterSeconds, durationMillis) {
        PlayerControlsPolicy.chapterFractions(chapterSeconds, durationMillis / 1_000.0)
    }
    Box(
        Modifier
            .width(1480.dp)
            .height(12.dp)
            .tvFocusRing(focused, 6.dp)
            .semantics {
                contentDescription = "Playback position"
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = positionMillis.coerceIn(0L, durationMillis.coerceAtLeast(0L)).toFloat(),
                    range = 0f..durationMillis.coerceAtLeast(1L).toFloat(),
                )
                stateDescription = "${PlayerControlsPolicy.formatTime(positionMillis)} of " +
                    PlayerControlsPolicy.formatTime(durationMillis)
                customActions = listOf(
                    CustomAccessibilityAction("Rewind") { onScrub(false); true },
                    CustomAccessibilityAction("Fast forward") { onScrub(true); true },
                )
            }
            .focusable(interactionSource = interaction)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { onScrub(false); true }
                    Key.DirectionRight -> { onScrub(true); true }
                    else -> false
                }
            },
    ) {
        TvProgressBar(
            progress = fraction,
            buffered = buffered,
            height = 12.dp,
            modifier = Modifier.fillMaxWidth(),
        )
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 3.dp.toPx()
            chapterFractions.forEach { chapter ->
                val x = size.width * chapter
                drawLine(
                    color = TvColor.TextPrimary.copy(alpha = 0.72f),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = stroke,
                )
            }
        }
    }
}

/** "Ends at 22:41", on the television's own clock (spec §11.17.3). */
private fun endsAt(positionMillis: Long, durationMillis: Long): String? {
    if (durationMillis <= 0L) return null
    val remaining = (durationMillis - positionMillis).coerceAtLeast(0L)
    val calendar = Calendar.getInstance().apply { timeInMillis = System.currentTimeMillis() + remaining }
    return String.format(
        Locale.US,
        "%02d:%02d",
        calendar.get(Calendar.HOUR_OF_DAY),
        calendar.get(Calendar.MINUTE),
    )
}

// ---------------------------------------------------------------------------- panels

/** Browse over the Activity-owned video surface; only selecting a channel changes the stream. */
@Composable
private fun LiveGuidePanel(
    repo: IptvRepository,
    state: IptvState,
    active: PlaybackSession.LiveChannel,
    group: String?,
    status: String?,
    favoriteNotice: String?,
    onGroup: (String?) -> Unit,
    onTune: (IptvChannel) -> Unit,
    returnChannel: IptvChannel?,
    onFocusedChannel: (IptvChannel?) -> Unit,
    allowPreview: Boolean,
    onClose: () -> Unit,
) {
    var highlighted by remember { mutableStateOf<IptvChannel?>(null) }
    var preview by remember { mutableStateOf<IptvChannel?>(null) }
    val decoderSlots = remember { tvMultiviewDecoderSlots() }
    LaunchedEffect(highlighted?.id, allowPreview) {
        preview = null
        if (allowPreview) {
            delay(650)
            preview = highlighted
        }
    }
    var mode by remember { mutableStateOf(LiveBrowseMode.Current) }
    var favoriteList by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf(emptyList<IptvChannel>()) }
    var guideNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L)
            guideNow = System.currentTimeMillis()
        }
    }
    val first = remember { FocusRequester() }
    val visible = remember(state.catalog.channels, state.sources, state.accounts.hiddenGroups, state.accounts.lockedGroups, active.group) {
        state.channels.filter { it.group !in state.accounts.hiddenGroups &&
            (it.group !in state.accounts.lockedGroups || it.group == active.group) }
    }
    val source = state.sources.firstOrNull { it.id == active.sourceId }
    val sourceChannels = remember(visible, active.sourceId) { visible.filter { it.sourceId == active.sourceId } }
    val sourceGroups = remember(sourceChannels) { sourceChannels.map(IptvChannel::group).toSet() }
    val groups = remember(state.orderedGroups, sourceGroups) { state.orderedGroups.filter { it in sourceGroups } }
    val visibleById = remember(visible) { visible.associateBy(IptvChannel::id) }
    val channels = remember(mode, group, sourceChannels, visible, matches, favoriteList,
        state.accounts.favoriteIds, state.accounts.favoriteLists, state.accounts.recentIds) { when (mode) {
        LiveBrowseMode.Current -> sourceChannels.filter { group == null || it.group == group }
        LiveBrowseMode.Favorites -> visible.filter { it.id in (favoriteList?.let { name -> state.accounts.favoriteLists[name].orEmpty() }
            ?: state.accounts.favoriteIds) }
        LiveBrowseMode.Recents -> state.accounts.recentIds.mapNotNull(visibleById::get)
        LiveBrowseMode.Search -> matches
        LiveBrowseMode.Groups -> emptyList()
    } }
    val listState = rememberLazyListState()
    LaunchedEffect(mode, group, active.id, favoriteList) {
        onFocusedChannel(null)
        highlighted = null
        val index = if (mode == LiveBrowseMode.Current) channels.indexOfFirst { it.id == active.id } else -1
        listState.scrollToItem(index.coerceAtLeast(0))
    }
    LaunchedEffect(query, mode, state.catalog, state.sources,
        state.accounts.hiddenGroups, state.accounts.lockedGroups) {
        if (mode != LiveBrowseMode.Search || query.isBlank()) { matches = emptyList(); return@LaunchedEffect }
        delay(250)
        matches = withContext(Dispatchers.Default) {
            IptvIndex.search(visible, state.catalog.programs, query, limit = 100,
                groupOrder = state.orderedGroups)
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    SidePanel("Live guide", onClose, width = 1500.dp, closeOnLeft = false) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(Modifier.weight(1f)) {
        Text("${source?.name ?: "Source"} · ${active.group}", style = TvType.Meta,
            color = TvColor.TextSecondary)
        state.channels.firstOrNull { it.id == active.id }?.let { playing ->
            Text("Playing: ${playing.name}", style = TvType.ControlLabel, color = TvColor.TextPrimary)
            Text(listOfNotNull(repo.now(active.id, guideNow)?.let {
                    "Now · ${it.guideKind(playing).label}: ${it.title}"
                }, repo.next(active.id, guideNow)?.let {
                    "Next · ${it.guideKind(playing).label}: ${it.title}"
                }).joinToString("  ·  ")
                .ifBlank { "Program information unavailable" },
                style = TvType.Meta.copy(fontSize = 23.sp, lineHeight = 30.sp),
                color = TvColor.TextSecondary)
        }
        repo.now(active.id, guideNow)?.let { program ->
            val duration = program.endMillis - program.startMillis
            if (duration > 0) {
                Spacer(Modifier.height(10.dp))
                TvProgressBar(((guideNow - program.startMillis).toFloat() / duration).coerceIn(0f, 1f),
                    height = 6.dp, modifier = Modifier.fillMaxWidth())
            }
        }
        status?.let { Text(it, style = TvType.Meta, color = TvColor.TextPrimary) }
        favoriteNotice?.let { Text(it, style = TvType.ControlLabel, color = TvColor.Cached) }
        if (status?.startsWith("Couldn't tune") == true && returnChannel != null) {
            Spacer(Modifier.height(12.dp))
            TvButton("Return to ${returnChannel.name}", onClick = { onTune(returnChannel) }, height = 64.dp)
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvButton("Channels", onClick = { mode = LiveBrowseMode.Current },
                modifier = Modifier.focusRequester(first), height = 60.dp)
            TvButton("Groups", onClick = { mode = LiveBrowseMode.Groups }, height = 60.dp)
            TvButton("Favorites", onClick = { mode = LiveBrowseMode.Favorites }, height = 60.dp)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvButton("Recent", onClick = { mode = LiveBrowseMode.Recents }, height = 60.dp)
            TvButton("Search", onClick = { mode = LiveBrowseMode.Search }, height = 60.dp)
        }
        Spacer(Modifier.height(16.dp))
        if (mode == LiveBrowseMode.Favorites && state.accounts.favoriteLists.isNotEmpty()) {
            TvButton("List: ${favoriteList ?: "All favorites"}", onClick = {
                val lists = listOf<String?>(null) + state.accounts.favoriteLists.keys
                favoriteList = lists[(lists.indexOf(favoriteList) + 1) % lists.size]
            }, height = 60.dp)
            Spacer(Modifier.height(12.dp))
        }
        if (mode == LiveBrowseMode.Search) {
            LiveFormField("Search channels and programs", query, onChange = { query = it })
            Spacer(Modifier.height(12.dp))
        }
        if (mode == LiveBrowseMode.Current) {
            Text(group ?: "All channels in source", style = TvType.Meta, color = TvColor.TextSecondary)
            Spacer(Modifier.height(12.dp))
        }
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (mode == LiveBrowseMode.Groups) {
                item(key = "all-groups") {
                    SharedSidePanelRow("All channels", selected = group == null,
                        onClick = { onGroup(null); mode = LiveBrowseMode.Current })
                }
                items(groups, key = { "group:$it" }) { value ->
                    SharedSidePanelRow(value, selected = value == group,
                        onClick = { onGroup(value); mode = LiveBrowseMode.Current })
                }
            } else {
                items(channels, key = IptvChannel::id) { channel ->
                    LiveGuideChannelRow(channel, selected = channel.id == active.id,
                        now = repo.now(channel.id, guideNow),
                        next = repo.next(channel.id, guideNow),
                        onFocused = { highlighted = channel; onFocusedChannel(channel) },
                        onClick = { onTune(channel) })
                }
                if (channels.isEmpty()) item(key = "empty") {
                    Text(when (mode) {
                        LiveBrowseMode.Search -> if (query.isBlank()) "Type a channel or show name." else "No matching channels."
                        LiveBrowseMode.Favorites -> "No favorite channels yet."
                        LiveBrowseMode.Recents -> "No recently played channels yet."
                        else -> "No channels in this group."
                    }, style = TvType.Meta, color = TvColor.TextSecondary)
                }
            }
        }
        }
        Column(Modifier.width(580.dp)) {
            Text(highlighted?.name ?: "Channel preview", style = TvType.ControlLabel, color = TvColor.TextPrimary)
            Spacer(Modifier.height(16.dp))
            val candidate = preview
            val candidateSource = state.sources.firstOrNull { it.id == candidate?.sourceId }
            val canPreview = allowPreview && candidate != null && candidate.id != active.id &&
                decoderSlots >= 2 && (source?.maxConnections ?: 0) >= 2 &&
                (candidateSource?.maxConnections ?: 0) >= (if (candidate.sourceId == active.sourceId) 2 else 1)
            if (canPreview) {
                LiveVideoTile(candidate, repo, audible = false,
                    modifier = Modifier.fillMaxWidth().height(326.dp),
                    onChooseAudio = {}, onRemove = {}, previewOnly = true)
                Text("Muted preview · Select to watch", style = TvType.Meta, color = TvColor.TextSecondary)
            } else {
                Text(if (highlighted?.id == active.id) "This channel is playing behind the guide."
                    else "Preview needs two provider streams and two TV decoders. Your current channel keeps playing.",
                    style = TvType.Body, color = TvColor.TextSecondary)
            }
            highlighted?.let { channel ->
                Spacer(Modifier.height(20.dp))
                repo.now(channel.id, guideNow)?.let { program ->
                    Text(program.title, style = TvType.CardTitle, color = TvColor.TextPrimary)
                    program.description?.let { Text(it, style = TvType.Body, color = TvColor.TextSecondary) }
                }
            }
        }
        }
    }
}

@Composable
private fun LiveGuideChannelRow(channel: IptvChannel, selected: Boolean,
                                now: IptvProgram?, next: IptvProgram?,
                                onFocused: () -> Unit,
                                onClick: () -> Unit) {
    val detail = listOfNotNull(now?.let { "Now · ${it.guideKind(channel).label}: ${it.title}" },
        next?.let { "Next · ${it.guideKind(channel).label}: ${it.title}" })
        .joinToString(" · ").ifBlank { channel.group }
    TvFocusable(onClick = onClick, selected = selected, modifier = Modifier.onFocusChanged {
        if (it.isFocused) onFocused()
    },
        accessibleLabel = "${channel.name}, $detail", focusScale = 1f, focusRing = false) { focused ->
        Column(Modifier.width(700.dp).heightIn(min = 116.dp).clip(TvShape.Control)
            .background(if (focused) TvColor.TextPrimary else TvColor.Elevated)
            .padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(channel.name + if (selected) "  ✓ Playing" else "", style = TvType.ControlLabel,
                color = if (focused) TvColor.Canvas else TvColor.TextPrimary)
            Text(detail, style = TvType.Meta.copy(fontSize = 23.sp, lineHeight = 30.sp),
                color = if (focused) TvColor.Canvas else TvColor.TextSecondary)
        }
    }
}

@Composable
private fun PlayerMenuRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SharedSidePanelRow(label, selected, onClick, modifier, focusRing = false)
}

@Composable
private fun PlayerSidePanel(
    panel: PlayerPanel,
    session: PlaybackSession,
    speed: Float,
    resizeMode: VideoResizeMode,
    upscaleMode: UpscaleMode,
    chapterSeconds: List<Double>,
    showSources: Boolean,
    liveSourceStatus: String?,
    nextLabel: String?,
    externalPlayers: List<InstalledExternalPlayer>,
    onSpeed: (Float) -> Unit,
    onResize: (VideoResizeMode) -> Unit,
    onUpscale: (UpscaleMode) -> Unit,
    onChapter: (Double) -> Unit,
    onNavigate: (PlayerPanel) -> Unit,
    onSources: () -> Unit,
    onRestartLive: () -> Unit,
    onNext: () -> Unit,
    onHandoff: (InstalledExternalPlayer) -> Unit,
    onClose: () -> Unit,
) {
    val tracks by session.tracks.collectAsState()
    val diagnostics by session.diagnosticsState.collectAsState()
    val subtitleStyle by session.subtitleStyle.collectAsState()
    val snapshot by session.snapshot.collectAsState()
    val header = when (panel) {
        PlayerPanel.Audio -> "Audio"
        PlayerPanel.Subtitles -> "Subtitles"
        PlayerPanel.SubtitleStyle -> "Subtitle appearance"
        PlayerPanel.More -> "More"
        PlayerPanel.Speed -> "Speed"
        PlayerPanel.Aspect -> "Picture size"
        PlayerPanel.Upscale -> "Upscaling"
        PlayerPanel.PlaybackInfo -> "Playback info"
        PlayerPanel.Chapters -> "Chapters"
        PlayerPanel.Handoff -> "Play in another app"
    }
    // Spec §11.6: the panel opens with the ring on the row that is already chosen, so a viewer
    // reads what is playing and changes it in one press. `entryRow` is attached to that row, or
    // to row 0 when nothing matches — never to a row that is not drawn.
    val entryRow = remember { FocusRequester() }
    val initialChapterIndex = remember(panel, chapterSeconds) {
        PlayerControlsPolicy.chapterIndexAt(chapterSeconds, snapshot.positionSeconds)
    }
    val entryIndex = when (panel) {
        PlayerPanel.Audio -> tracks.audio.indexOfFirst { it.selected }
        PlayerPanel.Subtitles ->
            if (tracks.currentSubtitle == null) 0 else tracks.subtitles.indexOfFirst { it.selected } + 1
        PlayerPanel.Speed ->
            PlayerControlsPolicy.SPEEDS.indexOfFirst { kotlin.math.abs(it - speed) < 0.01f }
        PlayerPanel.Aspect -> VideoResizeMode.entries.indexOf(resizeMode)
        PlayerPanel.Upscale -> UpscaleMode.entries.indexOf(upscaleMode)
        PlayerPanel.Chapters -> initialChapterIndex
        PlayerPanel.SubtitleStyle,
        PlayerPanel.More,
        PlayerPanel.PlaybackInfo,
        PlayerPanel.Handoff,
        -> 0
    }.coerceAtLeast(0)
    // `session.refreshTracks()` answers after the panel is drawn, so Audio can open on an empty
    // Column. An empty Column has no focus target and the panel then covers the screen with
    // nothing the remote can reach. One focusable row always exists.
    val tracksPending = panel == PlayerPanel.Audio && tracks.audio.isEmpty()
    val listState = rememberLazyListState()
    SidePanel(header = header, onClose = onClose) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().height(820.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(TvSpace.XS),
            contentPadding = PaddingValues(bottom = 60.dp),
        ) {
            when (panel) {
                PlayerPanel.Audio -> if (tracksPending) {
                    item(key = "audio-loading") {
                        PlayerMenuRow(
                            label = "Loading tracks",
                            selected = false,
                            onClick = {},
                            modifier = Modifier.focusRequester(entryRow),
                        )
                    }
                } else {
                    itemsIndexed(tracks.audio, key = { _, track -> "audio:${track.index}" }) { index, track ->
                        PlayerMenuRow(
                            label = playerMenuLabel(
                                PlayerControlsPolicy.trackLabel(
                                    track.language, track.name, track.codec, track.channels, "Track ${index + 1}",
                                ),
                                null,
                            ),
                            selected = track.selected,
                            onClick = {
                                session.launchOn {
                                    selectAudioTrack(track.index)
                                    refreshTracks()
                                }
                            },
                            modifier = if (index == entryIndex) Modifier.focusRequester(entryRow) else Modifier,
                        )
                    }
                }

                PlayerPanel.Subtitles -> {
                    item(key = "subtitle-off") {
                        PlayerMenuRow(
                            label = "Off",
                            selected = tracks.currentSubtitle == null,
                            onClick = {
                                session.launchOn {
                                    selectSubtitleTrack(SubtitleSelection.Off)
                                    refreshTracks()
                                }
                            },
                            modifier = if (entryIndex == 0) Modifier.focusRequester(entryRow) else Modifier,
                        )
                    }
                    itemsIndexed(tracks.subtitles, key = { _, track -> "subtitle:${track.index}" }) { index, track ->
                        PlayerMenuRow(
                            label = playerMenuLabel(
                                PlayerControlsPolicy.trackLabel(
                                    track.language, track.name, null, null, "Track ${index + 1}",
                                ),
                                null,
                            ),
                            selected = track.selected,
                            onClick = {
                                session.launchOn {
                                    selectSubtitleTrack(SubtitleSelection.Index(track.index, enable = true))
                                    refreshTracks()
                                }
                            },
                            modifier = if (index + 1 == entryIndex) Modifier.focusRequester(entryRow) else Modifier,
                        )
                    }
                    item(key = "subtitle-appearance") {
                        PlayerMenuRow(
                            label = playerMenuLabel("Appearance", subtitleStyleSummary(subtitleStyle)),
                            selected = false,
                            onClick = { onNavigate(PlayerPanel.SubtitleStyle) },
                        )
                    }
                }

                PlayerPanel.SubtitleStyle -> {
                    item(key = "style-font") {
                        PlayerMenuRow(
                            label = playerMenuLabel("Font", subtitleFontLabel(subtitleStyle.family)),
                            selected = false,
                            onClick = {
                                session.launchOn {
                                    stageSubtitleStyle(
                                        subtitleStyle.copy(family = nextSubtitleFont(subtitleStyle.family)).toParams(),
                                    )
                                }
                            },
                            modifier = Modifier.focusRequester(entryRow),
                        )
                    }
                    item(key = "style-size") {
                        PlayerMenuRow(
                            label = playerMenuLabel("Text size", subtitleSizeLabel(subtitleStyle.size)),
                            selected = false,
                            onClick = {
                                session.launchOn {
                                    stageSubtitleStyle(
                                        subtitleStyle.copy(size = nextSubtitleSize(subtitleStyle.size)).toParams(),
                                    )
                                }
                            },
                        )
                    }
                    item(key = "style-color") {
                        PlayerMenuRow(
                            label = playerMenuLabel("Text color", subtitleColorLabel(subtitleStyle.colorHex)),
                            selected = false,
                            onClick = {
                                session.launchOn {
                                    stageSubtitleStyle(
                                        subtitleStyle.copy(
                                            colorHex = nextSubtitleColor(subtitleStyle.colorHex),
                                        ).toParams(),
                                    )
                                }
                            },
                        )
                    }
                    item(key = "style-background") {
                        PlayerMenuRow(
                            label = playerMenuLabel(
                                "Background",
                                if (subtitleStyle.backgroundEnabled) "Black" else "Off",
                            ),
                            selected = false,
                            onClick = {
                                session.launchOn {
                                    stageSubtitleStyle(
                                        subtitleStyle.copy(
                                            backgroundEnabled = !subtitleStyle.backgroundEnabled,
                                        ).toParams(),
                                    )
                                }
                            },
                        )
                    }
                    item(key = "style-outline") {
                        PlayerMenuRow(
                            label = playerMenuLabel(
                                "Outline",
                                if (subtitleStyle.outlineEnabled) "On" else "Off",
                            ),
                            selected = false,
                            onClick = {
                                session.launchOn {
                                    stageSubtitleStyle(
                                        subtitleStyle.copy(
                                            outlineEnabled = !subtitleStyle.outlineEnabled,
                                        ).toParams(),
                                    )
                                }
                            },
                        )
                    }
                    item(key = "style-position") {
                        PlayerMenuRow(
                            label = playerMenuLabel("Position", subtitlePositionLabel(subtitleStyle.lift)),
                            selected = false,
                            onClick = {
                                session.launchOn {
                                    stageSubtitleStyle(
                                        subtitleStyle.copy(lift = nextSubtitleLift(subtitleStyle.lift)).toParams(),
                                    )
                                }
                            },
                        )
                    }
                }

                PlayerPanel.Speed -> itemsIndexed(
                    PlayerControlsPolicy.SPEEDS,
                    key = { _, value -> "speed:$value" },
                ) { index, value ->
                    PlayerMenuRow(
                        label = PlayerControlsPolicy.formatSpeed(value),
                        selected = kotlin.math.abs(value - speed) < 0.01f,
                        onClick = { onSpeed(value) },
                        modifier = if (index == entryIndex) Modifier.focusRequester(entryRow) else Modifier,
                    )
                }

                PlayerPanel.Aspect -> itemsIndexed(
                    VideoResizeMode.entries.toList(),
                    key = { _, mode -> "aspect:${mode.name}" },
                ) { index, mode ->
                    PlayerMenuRow(
                        label = stringResource(mode.labelRes()),
                        selected = mode == resizeMode,
                        onClick = { onResize(mode) },
                        modifier = if (index == entryIndex) Modifier.focusRequester(entryRow) else Modifier,
                    )
                }

                PlayerPanel.Upscale -> itemsIndexed(
                    UpscaleMode.entries.toList(),
                    key = { _, mode -> "upscale:${mode.name}" },
                ) { index, mode ->
                    PlayerMenuRow(
                        label = upscaleLabel(mode),
                        selected = mode == upscaleMode,
                        onClick = { onUpscale(mode) },
                        modifier = if (index == entryIndex) Modifier.focusRequester(entryRow) else Modifier,
                    )
                }

                PlayerPanel.More -> {
                    if (liveSourceStatus != null) {
                        item(key = "more-live-audio") {
                            PlayerMenuRow("Audio tracks", selected = false,
                                onClick = { onNavigate(PlayerPanel.Audio) },
                                modifier = Modifier.focusRequester(entryRow))
                        }
                        item(key = "more-live-subtitles") {
                            PlayerMenuRow("Subtitles", selected = false,
                                onClick = { onNavigate(PlayerPanel.Subtitles) })
                        }
                    }
                    item(key = "more-speed") {
                        PlayerMenuRow(
                            label = playerMenuLabel("Speed", PlayerControlsPolicy.formatSpeed(speed)),
                            selected = false,
                            onClick = { onNavigate(PlayerPanel.Speed) },
                            modifier = if (liveSourceStatus == null) Modifier.focusRequester(entryRow) else Modifier,
                        )
                    }
                    item(key = "more-aspect") {
                        PlayerMenuRow(
                            label = playerMenuLabel("Picture size", stringResource(resizeMode.labelRes())),
                            selected = false,
                            onClick = { onNavigate(PlayerPanel.Aspect) },
                        )
                    }
                    item(key = "more-upscale") {
                        PlayerMenuRow(
                            label = playerMenuLabel("Upscaling", upscaleLabel(upscaleMode)),
                            selected = false,
                            onClick = { onNavigate(PlayerPanel.Upscale) },
                        )
                    }
                    if (liveSourceStatus != null) {
                        item(key = "more-live-restart") {
                            PlayerMenuRow("Restart channel", selected = false, onClick = onRestartLive)
                        }
                        item(key = "more-live-source-status") {
                            PlayerMenuRow(playerMenuLabel("Source status", liveSourceStatus),
                                selected = false, onClick = { onNavigate(PlayerPanel.PlaybackInfo) })
                        }
                    }
                    item(key = "more-info") {
                        PlayerMenuRow(
                            label = playerMenuLabel(
                                "Playback info",
                                PlayerControlsPolicy.formatQualityBadge(diagnostics).ifBlank { "Waiting" },
                            ),
                            selected = false,
                            onClick = { onNavigate(PlayerPanel.PlaybackInfo) },
                        )
                    }
                    if (chapterSeconds.isNotEmpty()) {
                        item(key = "more-chapters") {
                            PlayerMenuRow(
                                label = playerMenuLabel("Chapters", chapterSeconds.size.toString()),
                                selected = false,
                                onClick = { onNavigate(PlayerPanel.Chapters) },
                            )
                        }
                    }
                    if (showSources) {
                        item(key = "more-sources") {
                            PlayerMenuRow("Sources", selected = false, onClick = onSources)
                        }
                    }
                    if (nextLabel != null) {
                        item(key = "more-next") {
                            PlayerMenuRow(
                                playerMenuLabel("Next", nextLabel),
                                selected = false,
                                onClick = onNext,
                            )
                        }
                    }
                    if (externalPlayers.isNotEmpty()) {
                        item(key = "more-handoff") {
                            PlayerMenuRow(
                                playerMenuLabel("Play in another app", "${externalPlayers.size} installed"),
                                selected = false,
                                onClick = { onNavigate(PlayerPanel.Handoff) },
                            )
                        }
                    }
                }

                PlayerPanel.PlaybackInfo -> {
                    item(key = "info-back") {
                        PlayerMenuRow(
                            label = "Back to More",
                            selected = false,
                            onClick = onClose,
                            modifier = Modifier.focusRequester(entryRow),
                        )
                    }
                    item(key = "info-values") { PlaybackInfo(diagnostics) }
                }

                PlayerPanel.Chapters -> itemsIndexed(
                    chapterSeconds,
                    key = { index, seconds -> "chapter:$index:$seconds" },
                ) { index, seconds ->
                    PlayerMenuRow(
                        label = "Chapter ${index + 1} · ${PlayerControlsPolicy.formatChapterTime(seconds)}",
                        selected = index == entryIndex,
                        onClick = { onChapter(seconds) },
                        modifier = if (index == entryIndex) Modifier.focusRequester(entryRow) else Modifier,
                    )
                }

                PlayerPanel.Handoff -> {
                    item(key = "handoff-back") {
                        PlayerMenuRow(
                            label = "Back to More",
                            selected = false,
                            onClick = onClose,
                            modifier = Modifier.focusRequester(entryRow),
                        )
                    }
                    itemsIndexed(externalPlayers, key = { _, player -> player.packageName }) { _, player ->
                        PlayerMenuRow(
                            label = "Open in ${player.label}",
                            selected = false,
                            onClick = { onHandoff(player) },
                        )
                    }
                }
            }
        }
    }
    // Keyed on the content, not on the panel alone: the tracks land after the panel opens, and the
    // request has to run again once the row it names exists.
    LaunchedEffect(
        panel,
        entryIndex,
        tracksPending,
        tracks.audio.size,
        tracks.subtitles.size,
        chapterSeconds.size,
        externalPlayers.size,
    ) {
        listState.scrollToItem(entryIndex)
        runCatching { entryRow.requestFocus() }
    }
}

@Composable
private fun PlaybackInfo(diagnostics: PlaybackDiagnostics) {
    Column(Modifier.width(560.dp).padding(top = TvSpace.S)) {
        InfoLine(
            "Picture",
            PlayerControlsPolicy.formatQualityBadge(diagnostics).ifBlank { "Waiting for video" },
        )
        diagnostics.hardwareDecoder.takeIf(String::isNotBlank)?.let {
            InfoLine("Decoder", it)
        }
        val audio = listOfNotNull(
                diagnostics.audioCodec.takeIf(String::isNotBlank)?.substringAfter('/')?.uppercase(Locale.US),
                diagnostics.audioChannels.takeIf { it > 0 }?.let { "$it ch" },
            ).joinToString(" · ")
        if (audio.isNotBlank()) InfoLine("Audio", audio)
        if (diagnostics.cacheSeconds > 0.0) {
            InfoLine("Buffer", String.format(Locale.US, "%.1f s", diagnostics.cacheSeconds))
        }
        if (diagnostics.droppedFrames > 0) {
            InfoLine("Dropped frames", diagnostics.droppedFrames.toString())
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = TvType.CardTitle, color = TvColor.TextMuted, modifier = Modifier.width(180.dp))
        Text(
            value,
            style = TvType.ControlLabel,
            color = TvColor.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun upscaleLabel(mode: UpscaleMode): String = when (mode) {
    UpscaleMode.OFF -> "Off"
    UpscaleMode.AUTO -> "Automatic"
    UpscaleMode.FORCE_1080P -> "Force 1080p"
}

/** Keeps dynamic track/source values inside the 560 px menu row. */
internal fun playerMenuLabel(label: String, value: String?, maxChars: Int = 38): String {
    val full = listOfNotNull(label.takeIf(String::isNotBlank), value?.takeIf(String::isNotBlank))
        .joinToString(" · ")
    if (full.length <= maxChars) return full
    return full.take((maxChars - 1).coerceAtLeast(0)) + "…"
}

private val SUBTITLE_SIZE_PRESETS = listOf(16.0, 20.0, 26.0, 32.0)
private val SUBTITLE_LIFT_PRESETS = listOf(0.0, 80.0, 160.0)
private val SUBTITLE_COLOR_PRESETS = listOf("#FFFFFF", "#FFE082", "#80D8FF")
private val SUBTITLE_FONT_PRESETS = listOf(
    "system" to "Rounded",
    "atkinson" to "Atkinson",
    "inter" to "Inter",
    "noto" to "Noto Sans",
    "roboto" to "Roboto",
    "avenir" to "Avenir",
    "georgia" to "Georgia",
    "menlo" to "Monospace",
)

private fun subtitleFontLabel(family: String): String =
    SUBTITLE_FONT_PRESETS.firstOrNull { it.first.equals(family, ignoreCase = true) }?.second ?: "Rounded"

private fun nextSubtitleFont(family: String): String {
    val index = SUBTITLE_FONT_PRESETS.indexOfFirst { it.first.equals(family, ignoreCase = true) }
    return SUBTITLE_FONT_PRESETS[(index + 1) % SUBTITLE_FONT_PRESETS.size].first
}

internal fun nextSubtitleSize(current: Double): Double =
    SUBTITLE_SIZE_PRESETS.firstOrNull { it > current + 0.1 } ?: SUBTITLE_SIZE_PRESETS.first()

private fun nextSubtitleLift(current: Double): Double =
    SUBTITLE_LIFT_PRESETS.firstOrNull { it > current + 0.1 } ?: SUBTITLE_LIFT_PRESETS.first()

private fun nextSubtitleColor(current: String): String {
    val index = SUBTITLE_COLOR_PRESETS.indexOfFirst { it.equals(current, ignoreCase = true) }
    return SUBTITLE_COLOR_PRESETS[(index + 1).coerceAtLeast(0) % SUBTITLE_COLOR_PRESETS.size]
}

private fun subtitleSizeLabel(size: Double): String = when {
    size < 18.0 -> "Small"
    size < 23.0 -> "Standard"
    size < 29.0 -> "Large"
    else -> "Extra large"
}

private fun subtitleColorLabel(color: String): String = when {
    color.equals("#FFE082", ignoreCase = true) -> "Warm yellow"
    color.equals("#80D8FF", ignoreCase = true) -> "Light blue"
    color.equals("#FFFFFF", ignoreCase = true) -> "White"
    else -> "Custom"
}

private fun subtitlePositionLabel(lift: Double): String = when {
    lift < 40.0 -> "Low"
    lift < 120.0 -> "Raised"
    else -> "High"
}

private fun subtitleStyleSummary(style: ReceiverSubtitleStyle): String = listOfNotNull(
    subtitleFontLabel(style.family),
    subtitleSizeLabel(style.size),
).joinToString(" · ")

// ---------------------------------------------------------------------------- overlays

@Composable
private fun OpeningOverlay(art: NowPlayingArt?, title: String?, loader: ArtworkLoader) {
    val context = LocalContext.current
    val artworkURL = art?.best
    var bitmap by remember(artworkURL) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(artworkURL) {
        val url = artworkURL ?: return@LaunchedEffect
        bitmap = withContext(Dispatchers.IO) {
            loader.load(url, maxOf(1920, context.resources.displayMetrics.widthPixels))
        }
    }
    val reduceMotion = LocalReduceMotion.current
    val titleAlpha = if (reduceMotion) 1f else {
        val pulse = rememberInfiniteTransition(label = "openingPulse")
        val alpha by pulse.animateFloat(
            initialValue = 1f,
            targetValue = 0.72f,
            animationSpec = infiniteRepeatable(tween(1250, easing = LinearEasing), RepeatMode.Reverse),
            label = "openingTitleAlpha",
        )
        alpha
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(TvColor.Canvas)
            .semantics(mergeDescendants = true) {
                paneTitle = "Opening player"
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                alpha = 0.72f,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to TvColor.Canvas.copy(alpha = 0.25f),
                    0.55f to TvColor.Canvas.copy(alpha = 0.42f),
                    1f to TvColor.Canvas.copy(alpha = 0.84f),
                ),
            ),
        )
        if (art?.logoURL != null) {
            AsyncImage(
                model = remember(art.logoURL) { PosterRequest(art.logoURL, 800).toImageRequest(context) },
                contentDescription = title,
                contentScale = ContentScale.Fit,
                modifier = Modifier.align(Alignment.Center).size(850.dp, 260.dp)
                    .graphicsLayer { alpha = titleAlpha },
            )
        } else {
            Text(
                title ?: "Loading video",
                style = TvType.HeroTitle.copy(fontSize = 72.sp, lineHeight = 80.sp, fontWeight = FontWeight.Bold),
                color = TvColor.TextPrimary,
                maxLines = 2,
                modifier = Modifier.align(Alignment.Center).width(1160.dp)
                    .graphicsLayer { alpha = titleAlpha },
            )
        }
    }
}

/** The video stays on screen; a 72 px arc turns once every 2 s, after 180 ms (spec §11.11). */
@Composable
private fun BufferingOverlay() {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(com.fourseveneightnine.tv.client.ui.theme.TvMotion.BufferingEnterMillis)
        visible = true
    }
    if (!visible) return
    val reduceMotion = LocalReduceMotion.current
    val transition = rememberInfiniteTransition(label = "buffering")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = if (reduceMotion) 0f else 360f,
        animationSpec = infiniteRepeatable(tween(2_000, easing = LinearEasing), RepeatMode.Restart),
        label = "arc",
    )
    Box(
        Modifier.fillMaxSize().semantics(mergeDescendants = true) {
            contentDescription = "Buffering"
            liveRegion = LiveRegionMode.Polite
        },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(72.dp).rotate(angle)) {
                drawArc(
                    color = TvColor.Focus,
                    startAngle = 0f,
                    sweepAngle = 270f,
                    useCenter = false,
                    style = Stroke(width = 6.dp.toPx()),
                )
            }
            Spacer(Modifier.height(TvSpace.M))
            Text("Buffering", style = TvType.ControlLabel, color = TvColor.TextPrimary)
        }
    }
}

// ---------------------------------------------------------------------------- plates

/**
 * The Next Up plate, spec §11.7.
 *
 * The countdown ring is THE ONLY element in this app that animates every frame, because it is a
 * countdown and a still countdown is a lie. Everything else on this plate is drawn once.
 *
 * Any key press cancels the countdown and leaves the plate up with "Play now" focused (§11.17.13),
 * including a key that does something else — so the focus landing on a button is itself the
 * cancel, and a viewer who reaches for Replay never has Next start underneath them.
 */
@Composable
private fun NextUpPlate(
    plan: NextUpPlan,
    onNext: () -> Unit,
    onReplay: () -> Unit,
    onDone: () -> Unit,
) {
    val reduceMotion = LocalReduceMotion.current
    val focus = remember { FocusRequester() }
    var cancelled by remember(plan) { mutableStateOf(false) }
    var remaining by remember(plan) { mutableStateOf(NEXT_UP_SECONDS) }

    LaunchedEffect(plan, cancelled) {
        if (cancelled) return@LaunchedEffect
        while (remaining > 0) {
            delay(1_000L)
            if (cancelled) return@LaunchedEffect
            remaining -= 1
        }
        onNext()
    }

    Box(
        Modifier
            .fillMaxSize()
            .onKeyEvent { event ->
                // Not consumed: the press still moves focus. It only stops the clock.
                if (event.type == KeyEventType.KeyDown) cancelled = true
                false
            },
    ) {
        Row(
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = (-96).dp, y = (-120).dp)
                .size(960.dp, 300.dp)
                .clip(TvShape.Panel)
                .background(TvColor.Elevated.copy(alpha = 0.96f))
                .border(1.dp, TvColor.Border, TvShape.Panel)
                .padding(32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(300.dp, 169.dp)
                    .clip(TvShape.Card)
                    .background(TvColor.PosterPlaceholder),
            )
            Spacer(Modifier.width(32.dp))
            Column(Modifier.weight(1f)) {
                Text("Up next", style = TvType.Meta, color = TvColor.TextMuted)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = plan.line.take(28),
                    style = TvType.PlateTitle,
                    color = TvColor.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                plan.runtimeLine?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = TvType.CardTitle, color = TvColor.TextSecondary, maxLines = 1)
                }
                Spacer(Modifier.height(TvSpace.S))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!cancelled) {
                        CountdownRing(remaining = remaining, reduceMotion = reduceMotion)
                        Spacer(Modifier.width(TvSpace.S))
                    }
                    TvButton(
                        "Play now",
                        onNext,
                        kind = ButtonKind.Primary,
                        height = TvGeom2.PlateButtonHeight,
                        modifier = Modifier.width(200.dp).focusRequester(focus),
                    )
                    Spacer(Modifier.width(TvSpace.S))
                    TvButton("Replay", onReplay, kind = ButtonKind.Secondary, modifier = Modifier.width(160.dp))
                    Spacer(Modifier.width(TvSpace.S))
                    TvButton("Done", onDone, kind = ButtonKind.Ghost, modifier = Modifier.width(132.dp))
                }
            }
        }
    }
    LaunchedEffect(plan) { runCatching { focus.requestFocus() } }
}

/** A 4 px `accent` arc that empties over 10 s, with the seconds in the middle (§11.7). */
@Composable
private fun CountdownRing(remaining: Int, reduceMotion: Boolean) {
    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
        if (!reduceMotion) {
            val sweep = 360f * (remaining.toFloat() / NEXT_UP_SECONDS)
            Canvas(Modifier.size(56.dp)) {
                drawArc(
                    color = TvColor.Accent,
                    startAngle = -90f,
                    sweepAngle = -sweep,
                    useCenter = false,
                    style = Stroke(width = 4.dp.toPx()),
                )
            }
        }
        // Reduce Motion: the ring becomes a number that ticks each second (§11.14).
        Text(remaining.toString(), style = TvType.data(24), color = TvColor.TextPrimary)
    }
}

/** §11.7: the plate auto-advances in ten seconds. */
private const val NEXT_UP_SECONDS = 10

/** The plate's buttons are 56 tall, which is the dense height, not the standard 60. */
private object TvGeom2 {
    val PlateButtonHeight = 56.dp
}

@Composable
private fun EndedPlate(
    title: String?,
    nextTitle: String?,
    nextSubtitle: String?,
    onReplay: () -> Unit,
    onNext: (() -> Unit)?,
    onDone: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    Box(
        Modifier
            .fillMaxSize()
            .background(TvColor.Canvas.copy(alpha = 0.94f))
            .semantics {
                paneTitle = "Playback error"
                isTraversalGroup = true
                liveRegion = LiveRegionMode.Assertive
            },
        Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Finished", style = TvType.ScreenTitle, color = TvColor.TextPrimary)
            Spacer(Modifier.height(10.dp))
            Text(
                text = listOfNotNull(nextTitle?.let { "Up next · $it" } ?: title, nextSubtitle).joinToString(" · "),
                style = TvType.Body,
                color = TvColor.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(TvSpace.L))
            Row(horizontalArrangement = Arrangement.spacedBy(TvSpace.S)) {
                if (onNext != null) {
                    TvButton("Next episode", onNext, kind = ButtonKind.Primary, modifier = Modifier.focusRequester(focus))
                    TvButton("Replay", onReplay, kind = ButtonKind.Secondary, modifier = Modifier.width(200.dp))
                    TvButton("Done", onDone, kind = ButtonKind.Ghost, modifier = Modifier.width(200.dp))
                } else {
                    TvButton("Replay", onReplay, kind = ButtonKind.Secondary, modifier = Modifier.width(200.dp))
                    TvButton(
                        "Done",
                        onDone,
                        kind = ButtonKind.Primary,
                        modifier = Modifier.width(200.dp).focusRequester(focus),
                    )
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/**
 * A viewer or the phone asked for the stop. The plate waits 400 ms before drawing: a black frame
 * right after a stop is the frame-rate resync window, not a fault, and the plate must not flash
 * over it (spec §11.9).
 */
@Composable
private fun StoppedPlate(receiverName: String, receiverAddress: String?, onDone: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(STOPPED_PLATE_DELAY_MILLIS)
        visible = true
    }
    if (!visible) return
    val focus = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(TvColor.Canvas), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(R.drawable.mark_4789),
                contentDescription = null,
                modifier = Modifier.size(96.dp),
            )
            Spacer(Modifier.height(TvSpace.M))
            Text("Ready for your iPhone", style = TvType.ScreenTitle, color = TvColor.TextPrimary)
            Spacer(Modifier.height(TvSpace.S))
            Text(
                text = listOfNotNull(receiverName, receiverAddress).joinToString(" · "),
                style = TvType.data(22),
                color = TvColor.TextMuted,
            )
            Spacer(Modifier.height(TvSpace.M))
            TvButton(
                "Back to Home",
                onDone,
                kind = ButtonKind.Ghost,
                modifier = Modifier.width(260.dp).focusRequester(focus),
            )
        }
    }
    LaunchedEffect(visible) { runCatching { focus.requestFocus() } }
}

/**
 * The taxonomy sentence, never "the cast failed" and never "connection lost" (CONTEXT §10).
 * With no player installed the rescue buttons are missing, not disabled.
 */
@Composable
private fun ErrorPlate(
    message: String,
    players: List<InstalledExternalPlayer>,
    onRetry: () -> Unit,
    onHandoff: (InstalledExternalPlayer) -> Unit,
    onDone: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(TvColor.Canvas.copy(alpha = 0.94f)), Alignment.Center) {
        Column(
            modifier = Modifier
                .width(1000.dp)
                .clip(TvShape.Panel)
                .background(TvColor.Elevated)
                .border(1.dp, TvColor.Border, TvShape.Panel)
                .padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = message.ifBlank { "This stream could not start" },
                style = TvType.ScreenTitle,
                color = TvColor.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(TvSpace.S))
            Text(
                "Try again, or open it in another app.",
                style = TvType.Body,
                color = TvColor.TextSecondary,
            )
            Spacer(Modifier.height(TvSpace.M))
            Row(horizontalArrangement = Arrangement.spacedBy(TvSpace.S)) {
                TvButton("Try again", onRetry, kind = ButtonKind.Primary, modifier = Modifier.focusRequester(focus))
                players.take(2).forEach { player ->
                    TvButton("Open in ${player.label}", { onHandoff(player) }, kind = ButtonKind.Secondary)
                }
                TvButton("Done", onDone, kind = ButtonKind.Ghost, modifier = Modifier.width(140.dp))
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

private const val STOPPED_PLATE_DELAY_MILLIS = 400L

// ---------------------------------------------------------------------------- helpers

/** Fire-and-forget on the session's own scope; a key press never waits on the player thread. */
private fun PlaybackSession.launchOn(block: suspend PlaybackSession.() -> Unit) {
    uiScope.launch { block() }
}
