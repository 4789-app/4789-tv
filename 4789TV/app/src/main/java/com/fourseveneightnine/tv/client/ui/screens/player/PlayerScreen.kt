package com.fourseveneightnine.tv.client.ui.screens.player

import com.fourseveneightnine.tv.client.ui.components.benchmarkDraw
import com.fourseveneightnine.tv.client.ui.screens.live.multiviewCapacity
import com.fourseveneightnine.tv.client.ui.screens.live.tvMultiviewDecoderSlots
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.fourseveneightnine.tv.client.ui.components.TvFocusableCard
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.produceState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.crossfade
import coil3.request.ImageRequest
import com.fourseveneightnine.tv.client.data.images.TmdbSize
import com.fourseveneightnine.tv.client.data.images.tmdbLogo
import com.fourseveneightnine.tv.client.iptv.iptvPlaybackHeaders
import com.fourseveneightnine.tv.client.data.images.PosterRequest
import com.fourseveneightnine.tv.protocol.NowPlayingArt
import com.fourseveneightnine.tv.protocol.NowPlayingArtwork
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.R
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.appGraph
import com.fourseveneightnine.tv.client.ui.screens.live.LiveTvLaunch
import com.fourseveneightnine.tv.client.ui.screens.live.LiveChannelOrder
import com.fourseveneightnine.tv.client.iptv.IptvChannel
import com.fourseveneightnine.tv.client.iptv.IptvProgram
import com.fourseveneightnine.tv.client.iptv.IptvIndex
import com.fourseveneightnine.tv.client.iptv.IptvRepository
import com.fourseveneightnine.tv.client.iptv.IptvState
import com.fourseveneightnine.tv.client.playback.isUnexpectedShortSource
import com.fourseveneightnine.tv.client.playback.NextUp
import com.fourseveneightnine.tv.client.playback.NextUpPlan
import com.fourseveneightnine.tv.client.playback.canonicalTrackLanguage
import com.fourseveneightnine.tv.client.playback.PlaybackSession
import com.fourseveneightnine.tv.client.playback.PlayRequest
import com.fourseveneightnine.tv.client.playback.PlayResult
import com.fourseveneightnine.tv.client.playback.WatchlistSource
import com.fourseveneightnine.tv.client.ui.LocalShellState
import com.fourseveneightnine.tv.client.ui.components.ButtonKind
import com.fourseveneightnine.tv.client.ui.components.PanelTransition
import com.fourseveneightnine.tv.client.ui.components.SidePanel
import com.fourseveneightnine.tv.client.ui.components.SidePanelRow as SharedSidePanelRow
import com.fourseveneightnine.tv.client.ui.components.TvArtwork
import com.fourseveneightnine.tv.client.ui.components.TvButton
import com.fourseveneightnine.tv.client.ui.components.TvFocusable
import com.fourseveneightnine.tv.client.ui.components.PivotSpec
import com.fourseveneightnine.tv.client.ui.components.TvProgressBar
import com.fourseveneightnine.tv.client.ui.components.tvFocusRing
import com.fourseveneightnine.tv.client.ui.components.tvGlassPill
import com.fourseveneightnine.tv.client.ui.screens.live.LiveFormField
import com.fourseveneightnine.tv.client.ui.theme.LocalReduceMotion
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvMotion
import com.fourseveneightnine.tv.client.ui.theme.TvShape
import com.fourseveneightnine.tv.client.ui.theme.TvSpace
import com.fourseveneightnine.tv.client.ui.theme.TvType
import com.fourseveneightnine.tv.player.PlaybackDiagnostics
import com.fourseveneightnine.tv.player.MpvSubtitleFontPolicy
import com.fourseveneightnine.tv.player.ReceiverPlaybackPhase
import com.fourseveneightnine.tv.player.ReceiverSubtitleStyle
import com.fourseveneightnine.tv.player.upscale.UpscaleMode
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics
import com.fourseveneightnine.tv.protocol.InstalledExternalPlayer
import com.fourseveneightnine.tv.protocol.OpenMediaRequest
import com.fourseveneightnine.tv.protocol.SubtitleSelection
import com.fourseveneightnine.tv.ui.PlayerControlsPolicy
import com.fourseveneightnine.tv.ui.TvRemoteCommand
import com.fourseveneightnine.tv.ui.VideoResizeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.Locale
import com.fourseveneightnine.tv.client.data.addons.SubtitleExtra
import com.fourseveneightnine.tv.client.data.addons.SubtitleSearchResult
import com.fourseveneightnine.tv.client.data.streams.StreamRow
import com.fourseveneightnine.tv.client.data.streams.SubtitleTrack
import com.fourseveneightnine.tv.protocol.SubtitleOptions

/** Which full-height picker is open over the video, spec §11.6. */
private enum class PlayerPanel {
    Episodes,
    Sources,
    Audio,
    Subtitles,
    SubtitleStyle,
    AddChannel,
    Pip,
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
    PlayerPanel.Episodes, PlayerPanel.Sources, PlayerPanel.Audio, PlayerPanel.Subtitles, PlayerPanel.AddChannel, PlayerPanel.Pip, PlayerPanel.More -> null
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
    onResizeMode: (VideoResizeMode) -> Unit,
    onControlBarVisible: (Boolean) -> Unit,
    onLocalControl: (String) -> Unit,
    onLeave: () -> Unit,
    onStopped: () -> Unit = onLeave,
    onBrowseWithLive: () -> Unit = onLeave,
    onMultiview: ((String) -> Unit)? = null,

) {
    val shell = LocalShellState.current
    val reduceMotion = LocalReduceMotion.current
    val controlsRise = with(LocalDensity.current) { 24.dp.roundToPx() }
    val appContext = LocalContext.current
    val playFlow = remember(appContext) { appContext.clientGraph.playFlow }
    val liveChannel by session.liveChannel.collectAsState()
    val currentIptvVod by session.iptvVod.collectAsState()
    val iptv by remember(appContext) { lazy { appContext.clientGraph.iptv } }
    // Ordinary casts and catalog video do not need to load or observe the IPTV catalog.
    val iptvState = if (liveChannel != null || currentIptvVod != null) {
        iptv.state.collectAsState().value
    } else remember { IptvState() }
    val scope = rememberCoroutineScope()
    // The local next episode. It carries no URL — §11.7 resolves its sources while the plate
    // counts down — so it cannot ride on `OpenMediaRequest.nextUp`, which the phone still owns.
    val localNextUp by playFlow.nextUp.collectAsState()
    val localRequest by playFlow.current.collectAsState()
    val activeSource by playFlow.activeSource.collectAsState()
    val sourceSaved by playFlow.playingSourceSaved.collectAsState(initial = false)
    val preparation by playFlow.preparationLabel.collectAsState()
    val phase by session.phase.collectAsState()
    val origin by session.origin.collectAsState()
    val art by session.nowPlayingArt.collectAsState()

    var nextJob by remember { mutableStateOf<Job?>(null) }
    var retryJob by remember { mutableStateOf<Job?>(null) }
    var retryOwnerGeneration by remember { mutableStateOf<Long?>(null) }
    val autoNextEnabled = remember(appContext) {
        appContext.appGraph.presentationPreferences.getBoolean(
            com.fourseveneightnine.tv.client.ui.screens.settings.SettingsKeys.AUTO_NEXT, true)
    }
    var nextFailure by remember { mutableStateOf<String?>(null) }
    var nextFinding by remember { mutableStateOf(false) }
    var handoffEpisode by remember { mutableStateOf<NextUpPlan?>(null) }
    // Key dispatch and Back read the same state, including during a closing animation.
    var chromeVisible by shell::chromeVisible
    var panel by remember { mutableStateOf<PlayerPanel?>(null) }
    if (com.fourseveneightnine.tv.BuildConfig.BENCHMARK_LOGGING) {
        LaunchedEffect(panel, chromeVisible) {
            ReceiverDiagnostics.benchmark("player.controls") {
                "visible=$chromeVisible panel=${panel?.name ?: "none"}"
            }
        }
    }
    var pipCategory by remember { mutableStateOf<com.fourseveneightnine.tv.client.ui.screens.live.PipCategory?>(null) }
    var pipParent by remember { mutableStateOf<PlayerPanel?>(null) }
    val pip = appContext.appGraph.pictureInPicture
    var browseRequest by remember { mutableStateOf<com.fourseveneightnine.tv.client.playback.PlayRequest?>(null) }
    var drawerPreparation by remember { mutableStateOf<DrawerPreparation?>(null) }
    val browsePreparing = drawerPreparation != null
    var subtitleStyleReturn by remember { mutableStateOf<PlayerPanel?>(null) }
    fun parentOf(open: PlayerPanel): PlayerPanel? = when (open) {
        PlayerPanel.AddChannel, PlayerPanel.Pip -> pipParent
        PlayerPanel.SubtitleStyle -> subtitleStyleReturn
        else -> playerPanelParent(open)
    }

    fun pinCurrentMovie(): Boolean {
        if (!pip.pinMovie()) return false
        panel = null
        chromeVisible = false
        onLeave()
        return true
    }
    fun openPip() {
        if (origin != PlaybackSession.Origin.Local) return
        val live = liveChannel
        if (live != null && pip.channel == null) {
            val channel = iptv.state.value.channels.firstOrNull { it.id == live.id } ?: return
            pip.pin(channel, fromLive = true) { panel = null; chromeVisible = false; onBrowseWithLive() }
        } else {
            pipCategory = null
            pipParent = panel
            panel = if (pip.channel == null) PlayerPanel.AddChannel else PlayerPanel.Pip
        }
    }
    var liveGuideVisible by remember { mutableStateOf(LiveTvLaunch.consumePreview()) }
    val liveGuideState = rememberSaveableStateHolder()
    var liveGroup by remember { mutableStateOf(session.liveChannel.value?.browseGroup) }
    var liveStatus by remember { mutableStateOf<String?>(null) }
    var favoriteNotice by remember { mutableStateOf<String?>(null) }
    var focusedGuideChannel by remember { mutableStateOf<IptvChannel?>(null) }
    var tuning by remember { mutableStateOf(false) }
    var tuningChannel by remember { mutableStateOf<IptvChannel?>(null) }
    var tuningJob by remember { mutableStateOf<Job?>(null) }
    var holdingJob by remember { mutableStateOf<Job?>(null) }
    var heldSelection by remember { mutableStateOf(false) }
    var tuningBrowse by remember { mutableStateOf<PlaybackSession.LiveChannel?>(null) }
    var channelBanner by remember { mutableStateOf(false) }
    val tuningGeneration = remember { longArrayOf(0L) }
    var lastWorkingChannel by remember { mutableStateOf<IptvChannel?>(null) }
    var leavingPlayer by remember { mutableStateOf(false) }
    var seekBadgeMillis by remember { mutableStateOf(0L) }
    var seekBadgeShownAt by remember { mutableStateOf(0L) }
    var pendingSeekMillis by remember { mutableStateOf<Long?>(null) }
    var lastSeekPressAt by remember { mutableStateOf(0L) }
    var seekJob by remember { mutableStateOf<Job?>(null) }
    var seekPreviewPending by remember { mutableStateOf(false) }
    val seekInputMutex = remember { Mutex() }
    var resizeMode by remember { mutableStateOf(VideoResizeMode.Fit) }
    var upscaleMode by remember { mutableStateOf(session.upscaleMode()) }
    var chromeTick by remember { mutableStateOf(0) }

    LaunchedEffect(favoriteNotice) {
        if (favoriteNotice != null) {
            delay(2_500)
            favoriteNotice = null
        }
    }

    val media = session.lastOpenMedia()
    val mediaGeneration = session.generation
    val phoneRecoveryOwner = media?.castId?.takeIf { it.isNotBlank() } ?: mediaGeneration
    var phonePlaybackWanted by remember(phoneRecoveryOwner) { mutableStateOf(false) }
    var playbackStarted by remember(mediaGeneration) { mutableStateOf(false) }
    LaunchedEffect(mediaGeneration, phase) {
        if (phase is ReceiverPlaybackPhase.Playing || phase is ReceiverPlaybackPhase.Paused) {
            playbackStarted = true
        }
        when (phase) {
            ReceiverPlaybackPhase.Playing -> phonePlaybackWanted = true
            ReceiverPlaybackPhase.Paused -> phonePlaybackWanted = false
            else -> Unit
        }
    }
    fun submitSeek(targetMillis: Long, settleDelayMillis: Long = 0L) {
        val generation = session.generation
        pendingSeekMillis = targetMillis
        seekJob?.cancel()
        seekPreviewPending = settleDelayMillis > 0L
        seekJob = scope.launch {
            // A held remote previews every tick, but only its final target needs media I/O.
            if (settleDelayMillis > 0L) delay(settleDelayMillis)
            if (session.generation != generation) return@launch
            seekPreviewPending = false
            android.util.Log.i("4789Seek",
                "remote.seek.commit | targetMs=$targetMillis previewDelayMs=$settleDelayMillis")
            val result = session.seekTo(targetMillis)
            if (session.generation != generation || pendingSeekMillis != targetMillis) return@launch
            if (result.isFailure) {
                pendingSeekMillis = null
                seekBadgeMillis = 0L
                favoriteNotice = "Seeking is unavailable for this source. Try another source or player."
            }
        }
    }
    fun scrubBy(stepMillis: Long, settleDelayMillis: Long = 0L) {
        val owner = session.generation
        // Serialize inputs across a suspended engine read so rapid taps retain every step.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            seekInputMutex.withLock {
                if (session.generation != owner) return@withLock
                val now = android.os.SystemClock.elapsedRealtime()
                val pending = pendingSeekMillis?.takeIf {
                    mediaGeneration == owner && now - lastSeekPressAt < 1_500L
                }
                val target = try {
                    com.fourseveneightnine.tv.ui.SeekPresentationPolicy.targetForStep(
                        stepMillis = stepMillis,
                        pendingTargetMillis = pending,
                        expectedGeneration = owner,
                        generation = { session.generation },
                        readSnapshot = { session.snapshot() },
                    )
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (session.generation == owner) {
                        favoriteNotice = "Couldn't read playback position. Try again."
                        seekBadgeMillis = 0L
                    }
                    return@withLock
                }
                lastSeekPressAt = android.os.SystemClock.elapsedRealtime()
                submitSeek(target, settleDelayMillis)
            }
        }
    }
    var livePictureReady by remember(mediaGeneration) { mutableStateOf(false) }
    val waitingForLiveClock = liveChannel != null && !livePictureReady && phase is ReceiverPlaybackPhase.Playing
    LaunchedEffect(mediaGeneration, liveChannel?.id, phase) {
        if (liveChannel == null || livePictureReady) return@LaunchedEffect
        if (phase is ReceiverPlaybackPhase.Paused) {
            livePictureReady = true
            return@LaunchedEffect
        }
        if (phase !is ReceiverPlaybackPhase.Playing) return@LaunchedEffect
        // A decoder can show one frame while the audio clock is still starting. Reveal motion,
        // rather than exposing that frame followed by a startup freeze. No fixed delay is added.
        var previous = session.refreshSnapshot()
        while (session.generation == mediaGeneration) {
            // The ordinary playhead poll is off while controls are hidden. Poll only for startup.
            delay(100)
            val current = session.refreshSnapshot()
            if (current.active && (current.speed == 0 ||
                    (previous.active && current.positionSeconds > previous.positionSeconds + 0.05))) {
                if (session.generation != mediaGeneration) return@LaunchedEffect
                livePictureReady = true
                ReceiverDiagnostics.record("iptv.picture.ready",
                    "generation=$mediaGeneration uptimeMs=${android.os.SystemClock.elapsedRealtime()} positionMs=${(current.positionSeconds * 1000).toLong()}")
                break
            }
            previous = current
        }
    }
    // media3 delivers STATE_IDLE synchronously after `player.stop()`, and the engine's IDLE
    // handler overwrites Stopped with Idle a moment later — so Stopped is a value that exists for
    // one instant and Idle is what a stopped receiver actually rests in. The plate follows what
    // the viewer sees, not the instant: once this title has left Idle, Idle means "it stopped".
    var hasLeftIdle by remember { mutableStateOf(false) }
    LaunchedEffect(phase) { if (phase !is ReceiverPlaybackPhase.Idle) hasLeftIdle = true }
    val stopped = phase is ReceiverPlaybackPhase.Stopped ||
        (phase is ReceiverPlaybackPhase.Idle && hasLeftIdle)
    LaunchedEffect(stopped, liveGuideVisible, leavingPlayer) {
        if (liveGuideVisible || leavingPlayer) return@LaunchedEffect
        // A restored player route without a session has nothing to render.
        if (phase is ReceiverPlaybackPhase.Idle && session.generation == 0L) {
            onStopped()
        } else if (stopped) {
            val generation = session.generation
            // A decoder or display-mode change can report Idle briefly.
            delay(STOP_SETTLE_MILLIS)
            val current = session.phase.value
            if (session.generation == generation &&
                (current is ReceiverPlaybackPhase.Stopped || current is ReceiverPlaybackPhase.Idle)
            ) onStopped()
        }
    }
    // PackageManager queries are Binder calls and are slow on Fire OS. The rescue buttons are
    // resolved once, off the main thread, and only when an error plate or More menu needs them.
    var externalPlayers by remember { mutableStateOf(emptyList<InstalledExternalPlayer>()) }
    var externalPlayersLoaded by remember { mutableStateOf(false) }
    var shortSource by remember(mediaGeneration) { mutableStateOf(false) }
    LaunchedEffect(session, mediaGeneration, origin, localRequest, liveChannel, currentIptvVod) {
        val request = localRequest ?: return@LaunchedEffect
        if (origin != PlaybackSession.Origin.Local || liveChannel != null || currentIptvVod != null) return@LaunchedEffect
        val regularEpisode = request.type == "series" && (request.season ?: 0) > 0 && request.episode != null
        // Duration arrives after opening. Read the flow until the first rejection instead of
        // caching a plain StateFlow.value read inside Compose's derivedStateOf.
        val rejected = session.snapshot.first {
            isUnexpectedShortSource(it.durationSeconds, playFlow.expectedRuntimeMinutes, regularEpisode)
        }
        if (session.generation == mediaGeneration) {
            shortSource = true
            ReceiverDiagnostics.record("streams.short_clip", "durationSeconds=${rejected.durationSeconds}")
        }
    }
    val displayPhase = if (shortSource && !stopped) {
        ReceiverPlaybackPhase.Error("This source returned a short clip. Choose another source.", code = "short_source")
    } else if (phase == ReceiverPlaybackPhase.Ended && (liveChannel != null || media?.isLive == true)) {
        ReceiverPlaybackPhase.Error("Live channel disconnected.", code = "live_end_of_input")
    } else phase
    val plateVisible = displayPhase is ReceiverPlaybackPhase.Ended ||
        stopped ||
        displayPhase is ReceiverPlaybackPhase.Error

    val localSeries = origin == PlaybackSession.Origin.Local && media?.nextUp == null &&
        liveChannel == null && currentIptvVod == null && localNextUp != null
    var countdownCancelled by remember(mediaGeneration) { mutableStateOf(false) }
    var keepWatching by remember(mediaGeneration) { mutableStateOf(false) }
    var previewSeen by remember(mediaGeneration) { mutableStateOf(false) }
    var remainingInEpisode by remember(mediaGeneration) { mutableStateOf<Int?>(null) }
    val currentSeason = localRequest?.season
    val currentEpisode = localRequest?.episode
    val creditLookupId = localNextUp?.creditLookupId
    var creditsStart by remember(mediaGeneration, creditLookupId, currentSeason, currentEpisode) { mutableStateOf<Double?>(null) }
    LaunchedEffect(mediaGeneration, localSeries, creditLookupId, currentSeason, currentEpisode) {
        if (!localSeries || creditLookupId == null || currentSeason == null || currentEpisode == null) return@LaunchedEffect
        session.snapshot.map { sample ->
            sample.durationSeconds.takeIf { it.isFinite() && it > 0 }?.let { kotlin.math.round(it).toLong() * 1000 }
        }.distinctUntilChanged().collectLatest { runtime ->
            creditsStart = null
            if (runtime != null) creditsStart = NextUp.creditsForEpisode(appContext.clientGraph.okHttp,
                creditLookupId, currentSeason, currentEpisode, runtime)
        }
    }
    LaunchedEffect(mediaGeneration, localSeries, displayPhase, keepWatching, creditsStart) {
        if (!localSeries || keepWatching || (displayPhase != ReceiverPlaybackPhase.Playing &&
                displayPhase != ReceiverPlaybackPhase.Paused)) return@LaunchedEffect
        while (session.generation == mediaGeneration) {
            val sample = session.refreshSnapshot()
            if (session.generation != mediaGeneration) break
            val remaining = NextUp.remainingSeconds(sample.positionSeconds, sample.durationSeconds, creditsStart)
            val wasInPreviewWindow = remainingInEpisode != null
            remainingInEpisode = remaining
            val untilPreview = (NextUp.previewStart(sample.durationSeconds, creditsStart) - sample.positionSeconds) /
                sample.speedMultiplier.coerceAtLeast(1f)
            if (autoNextEnabled && sample.durationSeconds > 0 && untilPreview <= 30 && !nextFinding && nextFailure == null &&
                displayPhase == ReceiverPlaybackPhase.Playing) {
                playFlow.warmNextUp()
            } else if (remaining == null && wasInPreviewWindow) {
                previewSeen = false
                playFlow.cancelNextWarmup()
            }
            delay(if (remaining == null && (sample.durationSeconds <= 0 || untilPreview > 3)) 2_000 else 250)
        }
    }
    DisposableEffect(mediaGeneration) { onDispose { playFlow.cancelNextWarmup() } }
    val earlyPrompt = localSeries && !keepWatching && remainingInEpisode != null &&
        !chromeVisible && panel == null && !nextFinding && !plateVisible
    LaunchedEffect(earlyPrompt) { if (earlyPrompt) previewSeen = true }
    fun dismissEarlyPrompt() {
        keepWatching = true
        playFlow.cancelNextWarmup()
    }

    val fullscreenPlateVisible = plateVisible && !liveGuideVisible && !(liveChannel != null && tuning)

    // What is drawn decides who owns the D-pad. Nothing else may.
    DisposableEffect(Unit) {
        shell.playerVisible = true
        onDispose {
            nextJob?.cancel()
            retryJob?.cancel()
            tuningJob?.cancel()
            holdingJob?.cancel()
            shell.playerVisible = false
            shell.chromeVisible = false
            shell.overlayVisible = false
            shell.playerCommands = null
            shell.releaseLivePicture?.invoke()
            onResizeMode(VideoResizeMode.Fit)
            onControlBarVisible(false)
        }
    }
    val preparing = nextFinding || browsePreparing || retryJob?.isActive == true
    LaunchedEffect(chromeVisible, panel, liveGuideVisible, fullscreenPlateVisible, earlyPrompt, preparing) {
        shell.overlayVisible = panel != null || liveGuideVisible || fullscreenPlateVisible || earlyPrompt || preparing
        onControlBarVisible(chromeVisible)
    }

    // Idle controls hide during play or pause; the small pause icon remains. Drawers stay open.
    LaunchedEffect(chromeVisible, chromeTick, panel, liveGuideVisible, phase) {
        if (!chromeVisible || panel != null || liveGuideVisible) return@LaunchedEffect
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
        favoriteNotice = null
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
    LaunchedEffect(mediaGeneration, origin) {
        // The drawer owns preparation until PlayFlow accepts it. A new phone session retires it.
        if ((panel != PlayerPanel.Sources && panel != PlayerPanel.Episodes) || origin != PlaybackSession.Origin.Local) panel = null
        nextFailure = null
        leavingPlayer = false
        chromeVisible = false
        seekBadgeMillis = 0L
        seekBadgeShownAt = 0L
        pendingSeekMillis = null
        lastSeekPressAt = 0L
    }
    LaunchedEffect(mediaGeneration, origin, liveChannel?.id) {
        if (retryOwnerGeneration?.let { it != session.generation } == true) {
            retryJob?.cancel()
            retryJob = null
            retryOwnerGeneration = null
        }
    }
    // Both visible-control scrubs and hidden scrubs settle against the same media snapshot.
    LaunchedEffect(pendingSeekMillis, mediaGeneration, seekPreviewPending) {
        // A near-target old frame cannot confirm a preview that has not reached the engine yet.
        if (seekPreviewPending) return@LaunchedEffect
        val target = pendingSeekMillis ?: return@LaunchedEffect
        val owner = session.generation
        kotlinx.coroutines.withTimeoutOrNull(com.fourseveneightnine.tv.ui.SeekPresentationPolicy.SETTLE_WINDOW_MILLIS) {
            session.snapshot.first { snapshot ->
                session.generation != owner || kotlin.math.abs((snapshot.positionSeconds * 1000).toLong() - target) <=
                    com.fourseveneightnine.tv.ui.SeekPresentationPolicy.LANDED_TOLERANCE_MILLIS
            }
        }
        if (pendingSeekMillis == target) pendingSeekMillis = null
    }
    LaunchedEffect(origin) {
        // A phone takeover retires a local next-episode lookup before it can reclaim playback.
        if (origin != PlaybackSession.Origin.Local) {
            nextJob?.cancel()
            handoffEpisode = null
            nextJob = null
            nextFinding = false
            nextFailure = null
        }
    }

    // A phone/non-live replacement can reuse the same URL. Ownership, not URL change, retires live work.
    LaunchedEffect(liveChannel == null, origin) {
        if (liveChannel == null || origin != PlaybackSession.Origin.Local) {
            shell.releaseLivePicture?.invoke()
            liveGuideVisible = false
            liveGuideState.removeState("live-guide")
            tuningGeneration[0]++
            tuningJob?.cancel()
            holdingJob?.cancel()
            heldSelection = false
            tuning = false
            tuningChannel = null
            tuningBrowse = null
            liveStatus = null
        }
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
        if (phoneNext != null && !nextFinding && !leavingPlayer && retryJob?.isActive != true) {
            nextFinding = true
            val expectedGeneration = session.generation
            val job = session.uiScope.launch(start = CoroutineStart.LAZY) {
                try {
                    if (session.generation != expectedGeneration) return@launch
                    NowPlayingArtwork.stage(null, phoneNext.posterUrl, owner = phoneNext.title)
                    session.open(
                    OpenMediaRequest(
                        url = phoneNext.url,
                        title = phoneNext.title,
                        subtitle = phoneNext.subtitle,
                        headers = phoneNext.headers,
                    ),
                    continuationOrigin,
                ) } finally {
                    if (nextJob === coroutineContext[Job]) {
                        handoffEpisode = null
                        nextFinding = false
                        nextJob = null
                    }
                }
            }
            nextJob = job
            job.start()
        } else if (phoneNext == null && localNextUp != null && !leavingPlayer && retryJob?.isActive != true) {
            // No URL yet: the flow searches, ranks and resolves the next episode with the same
            // picking rules, then opens it (§11.7).
            if (!nextFinding) {
                handoffEpisode = localNextUp
                nextFinding = true
                nextFailure = null
                val job = session.uiScope.launch(start = CoroutineStart.LAZY) {
                    try {
                        when (val result = playFlow.playNextUp()) {
                            PlayResult.Opened -> Unit
                            PlayResult.ShowList -> {
                                browseRequest = playFlow.sourcesRequest.value ?: localNextUp?.request()
                                panel = PlayerPanel.Sources
                                chromeVisible = true
                                chromeTick++
                            }
                            is PlayResult.Failed -> nextFailure = result.message
                        }
                    } finally {
                        if (nextJob === coroutineContext[Job]) {
                            handoffEpisode = null
                            nextFinding = false
                            nextJob = null
                        }
                    }
                }
                nextJob = job
                job.start()
            }
        }
    }
    // `playFlow.current` intentionally survives local playback. Origin is therefore the authority:
    // a later iPhone cast must not expose the previous local title's Sources list.
    fun showSources(target: PlayRequest?) {
        if (target == null) return
        browseRequest = target
        panel = PlayerPanel.Sources
        chromeVisible = true
        chromeTick++
    }
    val openSources: (() -> Unit)? = if (origin == PlaybackSession.Origin.Local && localRequest != null)
        ({ showSources(localRequest) }) else null
    val recoverySources: (() -> Unit)? = openSources?.let {
        { showSources(playFlow.sourcesRequest.value ?: localRequest) }
    }
    val openEpisodes: (() -> Unit)? = if (origin == PlaybackSession.Origin.Local && localRequest?.type == "series") ({
        browseRequest = localRequest
        panel = PlayerPanel.Episodes
        chromeVisible = true
        chromeTick++
    }) else null
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

    fun category(active: PlaybackSession.LiveChannel): List<IptvChannel> {
        val visible = iptvState.channels.filter { it.group !in iptvState.accounts.hiddenGroups &&
            (it.group !in iptvState.accounts.lockedGroups || it.group == active.group) }
        return LiveChannelOrder.category(visible, active.channelIds, active.browseGroup)
    }

    fun tune(channel: IptvChannel, force: Boolean = false, commit: Boolean = true,
             browse: PlaybackSession.LiveChannel? = tuningBrowse ?: liveChannel,
             expand: Boolean = !liveGuideVisible) {
        val owner = session.liveChannel.value
        val target = (browse ?: PlaybackSession.LiveChannel(channel.id, channel.sourceId, channel.group))
            .copy(id = channel.id, sourceId = channel.sourceId, group = channel.group)
        if (commit && !force && !heldSelection && channel.id == session.liveChannel.value?.id &&
            phase is ReceiverPlaybackPhase.Playing) {
            tuningGeneration[0]++
            tuningJob?.cancel()
            tuningChannel = null
            tuningBrowse = null
            tuning = false
            liveStatus = null
            liveGuideVisible = !expand
            return
        }
        // The first tap starts immediately. While it is connecting, briefly collect fast
        // follow-up taps so only the settled target starts another decoder.
        val settle = !force && commit && tuningJob?.isActive == true
        val generation = ++tuningGeneration[0]
        liveGuideVisible = !expand
        tuningJob?.cancel()
        tuningChannel = channel
        tuningBrowse = target
        tuning = true
        heldSelection = !commit
        liveStatus = if (commit) "Connecting…" else "Selecting · release to play"
        if (!commit) {
            // One owned stop retires the old loading stream. Repeats only update selection.
            if (holdingJob == null && owner != null) holdingJob = scope.launch { session.suspendLiveSelection(owner) }
            return
        }
        val retire = holdingJob
        holdingJob = null
        ReceiverDiagnostics.record("iptv.tune.requested", "mode=player coalesced=$settle")
        tuningJob = scope.launch {
            try {
                if (settle) delay(LIVE_TUNE_SETTLE_MILLIS)
                retire?.join()
                coroutineContext.ensureActive()
                if (generation != tuningGeneration[0]) return@launch
                val result = runCatching {
                    val url = iptv.playUrl(channel)
                    coroutineContext.ensureActive()
                    if (generation != tuningGeneration[0] || session.liveChannel.value != owner) return@launch
                    session.openLiveChannel(OpenMediaRequest(url = url, title = channel.name,
                        isLive = true, headers = iptvPlaybackHeaders(channel.headers)), target).getOrThrow()
                    coroutineContext.ensureActive()
                    if (generation != tuningGeneration[0]) return@launch
                    when (val outcome = session.awaitLiveOutcome()) {
                        is ReceiverPlaybackPhase.Playing -> {
                            coroutineContext.ensureActive()
                            if (generation != tuningGeneration[0] || session.liveChannel.value?.id != channel.id)
                                return@launch
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
                if (generation != tuningGeneration[0]) return@launch
                liveStatus = result.exceptionOrNull()?.let { failure ->
                    ReceiverDiagnostics.playbackFailure(
                        stage = "live_tune_failed", title = channel.name, source = "Live TV",
                        positionMs = session.resumePositionMillis(), attempt = session.generation,
                        code = (session.phase.value as? ReceiverPlaybackPhase.Error)?.code ?: "live_tune_failed",
                        reason = failure.message ?: "Couldn't tune this channel",
                        causeClass = failure.javaClass.simpleName,
                    )
                    "Couldn't tune ${channel.name}. Retry or choose another channel."
                }
            } finally {
                if (generation == tuningGeneration[0]) {
                    tuning = false
                    tuningChannel = null
                    tuningBrowse = null
                }
            }
        }
    }

    fun adjacent(direction: Int): IptvChannel? {
        val active = tuningBrowse ?: liveChannel ?: return null
        return LiveChannelOrder.adjacent(category(active), tuningChannel?.id ?: active.id, direction)
    }

    fun commitSelection() {
        if (!heldSelection) return
        tuningChannel?.let { tune(it, force = true, browse = tuningBrowse) }
    }

    fun openGuide() {
        commitSelection()
        liveGroup = (tuningBrowse ?: liveChannel)?.browseGroup
        panel = null
        chromeVisible = false
        liveGuideVisible = true
    }

    LaunchedEffect(liveChannel?.id, liveGuideVisible, tuning) {
        channelBanner = true
        if (!liveGuideVisible && !tuning) {
            delay(3_500)
            channelBanner = false
        }
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

    fun retryPlayback(automatic: Boolean = false, restart: Boolean = false) {
        if (session.stopRequested && !restart) {
            if (!automatic) session.launchOn { stop() }
            return
        }
        if (restart) {
            nextJob?.cancel()
            handoffEpisode = null
            nextJob = null
            nextFinding = false
            nextFailure = null
        }
        retryJob?.cancel()
        val expectedGeneration = session.generation
        val expectedUrl = session.lastOpenMedia()?.url
        val expectedOrigin = session.origin.value
        retryOwnerGeneration = expectedGeneration
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val position = if (restart) 0L else if (shortSource) localRequest?.resumeFromMs ?: 0L
                    else session.resumePositionMillis()
                val vod = currentIptvVod
                val channel = liveChannel?.let { active -> iptvState.channels.firstOrNull { it.id == active.id } }
                if (channel != null) {
                    retryOwnerGeneration = null
                    tune(channel, force = true)
                } else if (vod != null) {
                    val renewed = try { iptv.renewVod(vod) }
                        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                    coroutineContext.ensureActive()
                    if (session.generation != expectedGeneration || session.origin.value != expectedOrigin ||
                        session.lastOpenMedia()?.url != expectedUrl) return@launch
                    if (renewed != null && media != null) {
                        retryOwnerGeneration = null
                        val retryArt = NowPlayingArtwork.forPlayback(expectedGeneration)
                        NowPlayingArtwork.stage(retryArt?.landscapeURL, retryArt?.posterURL ?: renewed.first.image,
                            retryArt?.logoURL, owner = media.castId ?: media.title)
                        session.openIptvVod(media.copy(url = renewed.second,
                            headers = iptvPlaybackHeaders(renewed.first.headers), startPositionMs = position), renewed.first)
                    } else liveStatus = "Couldn't refresh this video link. Refresh the source and try again."
                } else if (origin == PlaybackSession.Origin.Local && localRequest != null) {
                    // PlayFlow owns supersession during its accepted resolve/open phase.
                    retryOwnerGeneration = null
                    when (val result = playFlow.retryCurrent(position, automatic)) {
                        PlayResult.Opened -> Unit
                        else -> {
                            coroutineContext.ensureActive()
                            if (automatic || session.generation != expectedGeneration) return@launch
                            if (recoverySources != null) recoverySources() else if (restart) {
                                liveStatus = (result as? PlayResult.Failed)?.message ?: "Couldn't restart this video. Try another source."
                            } else {
                                retryOwnerGeneration = null
                                session.retryLastOpen()
                            }
                        }
                    }
                } else if (session.generation == expectedGeneration) {
                    retryOwnerGeneration = null
                    if (restart) media?.let {
                        val retryArt = NowPlayingArtwork.forPlayback(expectedGeneration)
                        NowPlayingArtwork.stage(retryArt?.landscapeURL, retryArt?.posterURL, retryArt?.logoURL,
                            owner = it.castId ?: it.title)
                        session.open(it.copy(startPositionMs = 0L), continuationOrigin)
                    } else session.retryLastOpen()
                }
            } finally {
                if (retryJob === coroutineContext[Job]) {
                    retryJob = null
                    retryOwnerGeneration = null
                }
            }
        }
        retryJob = job
        job.start()
    }

    fun recordRetry(stage: String, error: ReceiverPlaybackPhase.Error) {
        ReceiverDiagnostics.playbackFailure(
            stage = stage,
            title = localRequest?.title ?: media?.title ?: session.resumeTitle,
            source = activeSource?.addonName ?: when {
                liveChannel != null -> "Live TV"
                currentIptvVod != null -> "IPTV video"
                origin == PlaybackSession.Origin.Phone -> "Phone"
                else -> null
            },
            positionMs = session.resumePositionMillis(), attempt = error.engineAttempt ?: mediaGeneration,
            code = error.code ?: "player_error", reason = error.message, httpStatus = error.httpStatus,
            afterFirstFrame = error.afterFirstFrame ?: playbackStarted,
        )
    }

    LaunchedEffect(mediaGeneration, displayPhase) {
        val error = displayPhase as? ReceiverPlaybackPhase.Error ?: return@LaunchedEffect
        if (session.generation == mediaGeneration) recordRetry("retry_available", error)
    }
    LaunchedEffect(mediaGeneration, nextFailure) {
        val failure = nextFailure ?: return@LaunchedEffect
        if (session.generation != mediaGeneration) return@LaunchedEffect
        ReceiverDiagnostics.playbackFailure(
            stage = "next_episode", title = (handoffEpisode ?: localNextUp)?.let { "${it.showTitle} · ${it.line}" },
            source = activeSource?.addonName, positionMs = 0, attempt = mediaGeneration,
            code = if (failure == "Source selection cancelled.") "next_episode_cancelled" else "next_episode_failed",
            reason = failure,
        )
    }

    LaunchedEffect(displayPhase, mediaGeneration, origin, retryJob, nextFinding) {
        if (displayPhase !is ReceiverPlaybackPhase.Error || session.stopRequested ||
            session.generation != mediaGeneration || retryJob != null || nextFinding
        ) return@LaunchedEffect
        if (displayPhase.retryVod && currentIptvVod == null && liveChannel == null) {
            if (origin == PlaybackSession.Origin.Local && localRequest != null && playFlow.canRecoverSource) {
                retryPlayback(automatic = true)
                return@LaunchedEffect
            }
            if (origin == PlaybackSession.Origin.Phone && phonePlaybackWanted && session.claimPhoneSourceRecovery()) {
                ReceiverDiagnostics.record("source.cast.reconnect",
                    "generation=$mediaGeneration attempt=1 positionMs=${session.resumePositionMillis()}")
                retryPlayback(automatic = true)
                return@LaunchedEffect
            }
        }
        session.reportSourceFailure(mediaGeneration)
    }

    fun stopAndLeave() {
        if (leavingPlayer) return
        nextJob?.cancel()
        retryJob?.cancel()
        retryJob = null
        retryOwnerGeneration = null
        handoffEpisode = null
        nextJob = null
        nextFinding = false
        tuningJob?.cancel()
        holdingJob?.cancel()
        leavingPlayer = true
        val expectedGeneration = session.generation
        session.launchOn {
            if (session.generation != expectedGeneration) {
                leavingPlayer = false
                return@launchOn
            }
            val stopped = stopForBrowse()
            if (session.generation != expectedGeneration + 1 || session.origin.value != null) {
                leavingPlayer = false
            } else if (stopped.isSuccess) onLeave() else {
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
            TvRemoteCommand.PictureInPicture -> when {
                origin != PlaybackSession.Origin.Local -> false
                leavingPlayer || pip.browseOnly -> true
                panel == PlayerPanel.Pip -> {
                    panel = pipParent
                    chromeTick++
                    true
                }
                pip.channel != null || liveChannel != null -> {
                    openPip()
                    true
                }
                else -> pinCurrentMovie()
            }
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
                // Keep the badge immediate. While held, replace the preview until repeats stop,
                // instead of fetching and decoding intermediate positions throughout the hold.
                scrubBy(step, settleDelayMillis = if (shell.heldSeekDeltaMillis != null)
                    com.fourseveneightnine.tv.player.SeekCoalescingPolicy.WINDOW_MILLIS else 0L)
                true
            }
            TvRemoteCommand.Stop -> {
                stopAndLeave()
                true
            }
            TvRemoteCommand.ChannelPrevious, TvRemoteCommand.ChannelNext -> {
                if (liveChannel == null || liveGuideVisible) false else {
                    adjacent(if (command == TvRemoteCommand.ChannelNext) 1 else -1)?.let { tune(it) }
                    true
                }
            }
            TvRemoteCommand.SelectPreviousChannel, TvRemoteCommand.SelectNextChannel -> {
                if (liveChannel == null || liveGuideVisible) false else {
                    adjacent(if (command == TvRemoteCommand.SelectNextChannel) 1 else -1)
                        ?.let { tune(it, commit = false) }
                    true
                }
            }
            TvRemoteCommand.CommitChannelSelection -> { commitSelection(); true }
            TvRemoteCommand.OpenGuide -> {
                if (liveChannel == null) false else { openGuide(); true }
            }
            TvRemoteCommand.ToggleFavorite -> {
                val id = (if (liveGuideVisible) focusedGuideChannel?.id else null)
                    ?: liveChannel?.id
                toggleFavorite(id)
            }
            else -> false
        }
    }

    val preparingEpisode = handoffEpisode?.takeIf { nextFinding && origin == PlaybackSession.Origin.Local }
    val preparingRequest = (drawerPreparation?.request ?: localRequest?.takeIf { retryJob?.isActive == true })
        ?.takeIf { it.type == "series" && origin == PlaybackSession.Origin.Local && liveChannel == null && currentIptvVod == null }
    val preparingSeries = preparingEpisode != null || preparingRequest != null
    Box(Modifier.fillMaxSize().benchmarkDraw(if (phase is ReceiverPlaybackPhase.Error) "player_error" else "player")) {
        // Keep the same artwork through the first buffer so the opening never flashes a spinner.
        val showPlaybackStatus = !liveGuideVisible && !preparingSeries
        val buffering = phase is ReceiverPlaybackPhase.Buffering
        val opening = showPlaybackStatus && (waitingForLiveClock || (!shell.holdingLivePicture &&
            (phase is ReceiverPlaybackPhase.Opening || (buffering && !playbackStarted))))
        key(mediaGeneration) {
            BufferingOverlay(
                buffering = showPlaybackStatus && buffering && !opening,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (opening) {
            val openingTitle = (phase as? ReceiverPlaybackPhase.Opening)?.title?.takeIf(String::isNotBlank)
                ?: media?.title?.takeIf(String::isNotBlank)
            PlaybackLoadingSurface(art, openingTitle, request = localRequest?.takeIf {
                origin == PlaybackSession.Origin.Local && liveChannel == null && currentIptvVod == null && it.title == media?.title
            })
        }

        val controlsShown = chromeVisible && !plateVisible && !preparingSeries
        if (!plateVisible && !preparingSeries && !liveGuideVisible &&
            liveChannel == null && media?.isLive != true &&
            (controlsShown || phase is ReceiverPlaybackPhase.Paused)) {
            PlayingTitleHeader(
                session = session,
                title = media?.title.orEmpty(),
                releaseName = activeSource?.takeIf { origin == PlaybackSession.Origin.Local }
                    ?.let { listOfNotNull(it.releaseName, it.filename).joinToString(" ") },
                panelOpen = panel != null,
                pictureCorner = pip.corner.takeIf { pip.channel != null },
                modifier = Modifier.align(Alignment.TopStart).fillMaxWidth(),
            )
        }
        if (seekBadgeMillis != 0L && !chromeVisible) SeekBadge(seekBadgeMillis, pendingSeekMillis)

        if (phase is ReceiverPlaybackPhase.Paused && !plateVisible && panel == null) {
            Image(painterResource(R.drawable.ic_player_pause), contentDescription = "Paused",
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(TvColor.Success),
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 60.dp, end = 80.dp).size(32.dp))
        }
        AnimatedVisibility(
            visible = controlsShown,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(if (panel != null) 0.66f else 1f)
                .then(if (controlsShown) Modifier else Modifier.clearAndSetSemantics {}),
            enter = if (reduceMotion) EnterTransition.None else
                fadeIn(tween(TvMotion.PanelSlideMillis, easing = TvMotion.Out)) +
                    slideInVertically(tween(TvMotion.PanelSlideMillis, easing = TvMotion.Out)) { controlsRise },
            exit = if (reduceMotion) ExitTransition.None else
                fadeOut(tween(TvMotion.PanelSlideMillis, easing = TvMotion.Out)) +
                    slideOutVertically(tween(TvMotion.PanelSlideMillis, easing = TvMotion.Out)) { controlsRise },
        ) {
            PlayingControlBar(
                interactive = controlsShown && panel == null,
                session = session,
                title = media?.title.orEmpty(),
                subtitle = media?.subtitle,
                chapterSeconds = media?.chapterSeconds.orEmpty(),
                isLive = media?.isLive == true || liveChannel != null,
                onInteraction = { chromeTick++ },
                onScrub = { forward ->
                    val step = scrub.stepMillis() * if (forward) 1 else -1
                    scrubBy(step)
                    chromeTick++
                },
                onTogglePlay = {
                    session.launchOn { togglePlayPause() }
                    onLocalControl(if (session.snapshot.value.speed == 0) "resume" else "pause")
                    chromeTick++
                },
                panelOpen = panel != null,
                onPanel = { subtitleStyleReturn = null; panel = it; chromeTick++ },
                onPip = if (origin == PlaybackSession.Origin.Local) ({
                    if (liveChannel == null && pip.channel == null) pinCurrentMovie() else openPip()
                    Unit
                }) else null,
                pipActive = pip.channel != null,
                sourceSaved = sourceSaved,
                onSaveSource = if (origin == PlaybackSession.Origin.Local && localRequest != null &&
                    activeSource != null && liveChannel == null && currentIptvVod == null) ({
                    chromeTick++
                    scope.launch {
                        favoriteNotice = try {
                            if (playFlow.savePlayingSource()) "Source saved to Watchlist."
                            else "This source has no stable file identity."
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { "Couldn’t save the source. Try again." }
                    }
                }) else null,
                onSources = openSources,
                onEpisodes = openEpisodes,
                nextEpisodeLabel = if (nextFinding) "Finding sources…" else nextLabel,
                onNextEpisode = playNext.takeIf { nextLabel != null || nextFinding },
                seekTargetMillis = pendingSeekMillis,
                liveChannel = liveChannel,
                lastChannelAvailable = lastTunedChannel != null,
                onPreviousChannel = { adjacent(-1)?.let { tune(it) } },
                onNextChannel = { adjacent(1)?.let { tune(it) } },
                onLastChannel = { lastTunedChannel?.let { tune(it) } },
                onLiveGuide = ::openGuide,
                isFavorite = liveChannel?.id in iptvState.accounts.favoriteIds,
                onToggleFavorite = { toggleFavorite(liveChannel?.id) },
            )
        }

        if (origin == PlaybackSession.Origin.Phone && !plateVisible && !preparingSeries && !liveGuideVisible &&
            (controlsShown || phase is ReceiverPlaybackPhase.Paused)) {
            CastChip(Modifier.align(Alignment.BottomStart).padding(start = 96.dp, bottom = 22.dp))
        }

        if (chromeVisible && panel == null && liveChannel == null && !plateVisible) {
            nextFailure?.let { message ->
                Text(message, style = TvType.Meta, color = TvColor.TextPrimary,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 160.dp)
                        .width(1200.dp).background(TvColor.Canvas.copy(alpha = 0.92f))
                        .padding(20.dp))
            }
        }

        if (!liveGuideVisible && liveChannel != null && (channelBanner || tuning || liveStatus != null)) {
            val active = tuningBrowse ?: liveChannel
            val target = tuningChannel ?: iptvState.channels.firstOrNull { it.id == active?.id }
            val ordered = active?.let(::category).orEmpty()
            val number = ordered.indexOfFirst { it.id == target?.id }.takeIf { it >= 0 }?.plus(1)
            Column(Modifier.align(Alignment.TopStart).padding(start = 96.dp, top = 72.dp)
                .background(TvColor.Canvas.copy(alpha = 0.9f), TvShape.Card).padding(24.dp)) {
                Text(listOfNotNull(number?.let { "$it / ${ordered.size}" }, target?.name).joinToString(" · "),
                    style = TvType.ControlLabel, color = TvColor.TextPrimary)
                Text(if (tuning) "Tuning" else if (phase is ReceiverPlaybackPhase.Playing) "Playing" else "Selected",
                    style = TvType.Badge, color = if (!tuning && phase is ReceiverPlaybackPhase.Playing) TvColor.Success else TvColor.InProgress)
                if (tuning && shell.holdingLivePicture) Text(lastWorkingChannel?.name?.let { "Previous picture · $it" }
                    ?: "Previous picture held while loading", style = TvType.Meta, color = TvColor.TextSecondary)
                (favoriteNotice ?: liveStatus)?.let { Text(it, style = TvType.Meta, color = TvColor.TextSecondary) }
            }
        } else (favoriteNotice ?: liveStatus)?.takeIf { !liveGuideVisible }?.let { status ->
            Text(status, style = TvType.Meta, color = TvColor.TextPrimary,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 80.dp)
                    .background(TvColor.Canvas.copy(alpha = 0.9f), TvShape.Control)
                    .padding(horizontal = 24.dp, vertical = 12.dp))
        }

        if (earlyPrompt) localNextUp?.let { target ->
            val focus = remember { FocusRequester() }
            EpisodePrompt(target, modifier = Modifier.align(Alignment.BottomEnd).onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) countdownCancelled = true
                false
            }, status = if (creditsStart != null) "Credits · Up next" else "Up next",
                seconds = remainingInEpisode?.takeIf { it > 0 && autoNextEnabled && !countdownCancelled && nextFailure == null },
                message = nextFailure) {
                EpisodeAction(if (nextFailure == null) "Play now" else "Try again", playNext,
                    Modifier.focusRequester(focus), primary = true,
                    icon = if (nextFailure == null) R.drawable.ic_player_play else R.drawable.ic_player_last)
                if (nextFailure != null && recoverySources != null) EpisodeAction("Sources", recoverySources, icon = R.drawable.ic_player_layers)
                EpisodeAction("Keep watching", ::dismissEarlyPrompt)
            }
            LaunchedEffect(target.season, target.episode) { runCatching { focus.requestFocus() } }
        }

        if (fullscreenPlateVisible && !preparingSeries) when (val current = displayPhase) {
            // A local series with a known next episode gets the countdown card (§11.7). Everything
            // else — a film, a cast, a last episode — gets the Ended plate, which is a different
            // plate and is never swapped for it.
            is ReceiverPlaybackPhase.Ended -> {
                val plan = localNextUp
                    ?.takeIf { origin == PlaybackSession.Origin.Local && media?.nextUp == null }
                if (plan != null) {
                    NextUpPlate(
                        plan = plan,
                        autoAdvance = autoNextEnabled && !keepWatching && !countdownCancelled,
                        initialSeconds = if (previewSeen) 0 else NEXT_UP_SECONDS,
                        onNext = playNext,
                        busy = nextFinding || retryJob?.isActive == true,
                        preparation = preparation,
                        failure = nextFailure,
                        onSources = recoverySources,
                        onReplay = { retryPlayback(restart = true) },
                        onDone = ::stopAndLeave,
                    )
                } else {
                    EndedPlate(
                        episode = localRequest?.takeIf { origin == PlaybackSession.Origin.Local && it.type == "series" &&
                            media?.nextUp == null && liveChannel == null && currentIptvVod == null },
                        title = media?.title,
                        nextTitle = media?.nextUp?.title,
                        nextSubtitle = media?.nextUp?.subtitle,
                        onReplay = { retryPlayback(restart = true) },
                        onNext = media?.nextUp?.let { next ->
                            {
                                session.launchOn {
                                    NowPlayingArtwork.stage(null, next.posterUrl, owner = next.title)
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
                episode = localRequest?.takeIf { origin == PlaybackSession.Origin.Local && it.type == "series" &&
                    liveChannel == null && currentIptvVod == null },
                message = liveStatus ?: current.message,
                title = listOfNotNull(media?.title ?: session.resumeTitle, media?.subtitle).joinToString(" · "),
                onSources = recoverySources?.let { open -> { retryJob?.cancel(); open() } },
                preferSources = shortSource,
                onGuide = if (liveChannel != null) ::openGuide else null,
                players = externalPlayers,
                retrying = retryJob?.isActive == true,
                onRetry = { recordRetry("retry_pressed", current); retryPlayback() },
                onHandoff = { player -> retryJob?.cancel(); session.launchOn { externalHandoff(player) } },
                onDone = ::stopAndLeave,
            )

            else -> Unit
        }

        PanelTransition(value = panel) { open ->
            val target = browseRequest
            if ((open == PlayerPanel.Episodes || open == PlayerPanel.Sources) && target != null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
                    PlayerBrowsePanel(target, open == PlayerPanel.Episodes, session,
                        onPreparing = { drawerPreparation = it },
                        onClose = { panel = null; chromeTick++ },
                        onSources = { browseRequest = it; panel = PlayerPanel.Sources })
                }
            } else if (open == PlayerPanel.AddChannel) {
                val channels by iptv.state.collectAsState()
                com.fourseveneightnine.tv.client.ui.screens.live.PipChannelPicker(
                    channels, pipCategory, onCategory = { pipCategory = it },
                    onBack = { if (pipCategory != null) pipCategory = null else panel = pipParent }
                ) { channel ->
                    pip.pin(channel, fromLive = false) { panel = null; chromeVisible = false }
                }
            } else PlayerSidePanel(
                panel = open,
                session = session,
                subtitleRequest = localRequest.takeIf { origin == PlaybackSession.Origin.Local },
                subtitleSource = activeSource.takeIf { origin == PlaybackSession.Origin.Local },
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
                onMultiview = liveChannel?.takeIf { active ->
                    open == PlayerPanel.More && onMultiview != null && origin == PlaybackSession.Origin.Local &&
                        iptvState.multiviewCapacity(active.sourceId, remember { tvMultiviewDecoderSlots() }) >= 2
                }?.let { active -> { panel = null; onMultiview?.invoke(active.id); Unit } },
                nextLabel = nextLabel,
                externalPlayers = externalPlayers,
                onSpeed = { session.launchOn { setSpeedMultiplier(it) } },
                onResize = { resizeMode = it; onResizeMode(it) },
                onUpscale = { upscaleMode = it; session.setUpscale(it) },
                onChapter = { chapterSeconds ->
                    submitSeek((chapterSeconds * 1_000.0).toLong())
                    panel = PlayerPanel.More
                },
                onNavigate = { if (it == PlayerPanel.SubtitleStyle) subtitleStyleReturn = open; panel = it },
                onSources = { openSources?.invoke() },
                pip = pip.takeIf { origin == PlaybackSession.Origin.Local },
                onAddMatch = ::openPip,
                onRestartLive = {
                    panel = null
                    liveChannel?.let { active -> iptvState.channels.firstOrNull { it.id == active.id } }
                        ?.let { tune(it, force = true) }
                },
                onNext = playNext,
                onHandoff = { player -> session.launchOn { externalHandoff(player) } },
                onClose = {
                    panel = parentOf(open)
                    chromeTick++
                },
            )
        }

        PanelTransition(value = liveChannel?.takeIf { liveGuideVisible }, fade = true) { active ->
            liveGuideState.SaveableStateProvider("live-guide") {
            LiveGuidePanel(iptv, iptvState, active, tuningBrowse ?: active, liveGroup, liveStatus, favoriteNotice, phase, tuning, livePictureReady,
                onGroup = { liveGroup = it },
                onTune = { channel, context -> tune(channel, force = true, browse = context, expand = false) },
                returnChannel = lastWorkingChannel?.takeIf { it.id != active.id },
                retainedPictureName = lastWorkingChannel?.name.takeIf { shell.holdingLivePicture },
                onFocusedChannel = { channel ->
                    focusedGuideChannel = channel
                    if (liveGuideVisible && channel != null && tuning && tuningBrowse?.id != channel.id) {
                        tuningGeneration[0]++
                        tuningJob?.cancel()
                        tuningJob = null
                        tuningChannel = null
                        tuningBrowse = null
                        tuning = false
                        liveStatus = null
                    }
                },
                onClose = { liveGuideVisible = false; chromeVisible = false },
                onExit = ::stopAndLeave)
            }
        }

        if (browsePreparing || nextFinding || retryJob?.isActive == true) {
            val phoneNext = media?.nextUp?.takeIf { nextFinding }
            val fallbackRequest by playFlow.sourcesRequest.collectAsState()
            val target = if (browsePreparing) drawerPreparation?.request else fallbackRequest?.takeIf {
                nextFinding && phoneNext == null && origin == PlaybackSession.Origin.Local
            }
            val services by appContext.clientGraph.services.collectAsState()
            val targetMeta = target?.let { services?.meta?.peek(it.type, it.id.substringAfter("::")) }
            val preparingArt = when {
                phoneNext != null -> NowPlayingArt(posterURL = phoneNext.posterUrl)
                target != null -> NowPlayingArt(
                    landscapeURL = target.backdropUrl ?: targetMeta?.backdrop,
                    posterURL = target.posterUrl ?: targetMeta?.poster, logoURL = targetMeta?.logo,
                )
                else -> art
            }
            val cancelLoading: () -> Unit = {
                retryJob?.cancel()
                if (nextFinding) nextFailure = "Source selection cancelled."
                nextJob?.cancel()
                handoffEpisode = null
                nextJob = null
                nextFinding = false
                if (browsePreparing) {
                    drawerPreparation?.cancel?.invoke()
                    panel = null
                }
            }
            if (preparingSeries) {
                val cancelFocus = remember { FocusRequester() }
                val action: @Composable () -> Unit = {
                    EpisodeAction("Cancel", cancelLoading, Modifier.focusRequester(cancelFocus))
                }
                val placement = Modifier.align(Alignment.BottomEnd)
                if (preparingEpisode != null) EpisodePrompt(preparingEpisode, placement,
                    status = preparation?.takeIf(String::isNotBlank) ?: "Preparing next episode", actions = action)
                else if (preparingRequest != null) PlayerGlassCard(preparingRequest.title,
                    episodeCoordinates(preparingRequest.season, preparingRequest.episode),
                    preparation?.takeIf(String::isNotBlank) ?: "Preparing episode", placement,
                    image = preparingRequest.backdropUrl ?: preparingRequest.posterUrl,
                    portrait = preparingRequest.backdropUrl == null, actions = action)
                LaunchedEffect(Unit) { withFrameNanos { }; cancelFocus.requestFocus() }
                BackHandler(onBack = cancelLoading)
            } else PlaybackLoadingSurface(preparingArt, phoneNext?.title ?: target?.title ?: media?.title,
                request = target ?: localRequest?.takeIf {
                    phoneNext == null && origin == PlaybackSession.Origin.Local && liveChannel == null &&
                        currentIptvVod == null && it.title == media?.title
                }, onCancel = cancelLoading)
        }

    }

    // Back dismisses the deepest visible layer before a VOD stop-and-return. Live opens its guide.
    BackHandler(enabled = !liveGuideVisible) {
        val snapshot = session.snapshot.value
        ReceiverDiagnostics.record(
            "player.back",
            "panel=${panel != null} plate=$plateVisible chrome=$chromeVisible " +
                "active=${snapshot.active}",
        )
        when {
            browsePreparing -> { drawerPreparation?.cancel?.invoke(); panel = null }
            retryJob?.isActive == true -> retryJob?.cancel()
            nextFinding -> { nextJob?.cancel(); handoffEpisode = null; nextJob = null; nextFinding = false; nextFailure = "Source selection cancelled." }
            panel == PlayerPanel.AddChannel && pipCategory != null -> pipCategory = null
            panel != null -> panel = panel?.let(::parentOf)
            liveChannel != null -> openGuide()
            earlyPrompt -> dismissEarlyPrompt()
            chromeVisible && !plateVisible -> chromeVisible = false
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
private fun SeekBadge(totalMillis: Long, targetMillis: Long?) {
    Box(
        Modifier
            .offset(x = 1450.dp, y = 96.dp)
            .width(334.dp)
            .height(84.dp)
            .clip(TvShape.Control)
            .background(TvColor.Canvas.copy(alpha = 0.78f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = listOfNotNull(formatSeekDelta(totalMillis), targetMillis?.let(PlayerControlsPolicy::formatTime)).joinToString("  →  "),
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
    interactive: Boolean,
    session: PlaybackSession,
    panelOpen: Boolean,
    title: String,
    subtitle: String?,
    chapterSeconds: List<Double>,
    isLive: Boolean,
    onInteraction: () -> Unit,
    onScrub: (Boolean) -> Unit,
    onTogglePlay: () -> Unit,
    onPanel: (PlayerPanel) -> Unit,
    onPip: (() -> Unit)?,
    pipActive: Boolean,
    sourceSaved: Boolean,
    onSaveSource: (() -> Unit)?,
    onSources: (() -> Unit)?,
    onEpisodes: (() -> Unit)?,
    nextEpisodeLabel: String?,
    onNextEpisode: (() -> Unit)?,
    seekTargetMillis: Long?,
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
    val diagnostics by session.diagnosticsState.collectAsState()
    val formats = remember(diagnostics, tracks.currentAudio) { PlayerControlsPolicy.sourceFormats(diagnostics, tracks.currentAudio) }
    ControlBar(
        interactive = interactive,
        panelOpen = panelOpen,
        formats = formats,
        title = title,
        subtitle = subtitle,
        positionMillis = seekTargetMillis ?: (snapshot.positionSeconds * 1_000).toLong(),
        durationMillis = (snapshot.durationSeconds * 1_000).toLong(),
        bufferedMillis = (snapshot.bufferedSeconds * 1_000).toLong(),
        chapterSeconds = chapterSeconds,
        audioLabel = tracks.currentAudio?.let {
            readableTrackLabel(it.language, it.name, null, null, "Track ${it.index + 1}")
        } ?: "Auto",
        subtitlesEnabled = tracks.currentSubtitle != null,
        subtitleLabel = tracks.currentSubtitle?.let {
            readableTrackLabel(it.language, it.name, null, null, "Track ${it.index + 1}")
        } ?: "Off",
        isLive = isLive,
        audioLanguage = tracks.currentAudio?.language?.let { language ->
            readableTrackLabel(language, null, null, null, "").takeIf(String::isNotBlank)
        },
        onInteraction = onInteraction,
        onScrub = onScrub,
        onTogglePlay = onTogglePlay,
        onPanel = onPanel,
        onPip = onPip,
        pipActive = pipActive,
        sourceSaved = sourceSaved,
        onSaveSource = onSaveSource,
        onSources = onSources,
        onEpisodes = onEpisodes,
        nextEpisodeLabel = nextEpisodeLabel,
        onNextEpisode = onNextEpisode,
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

/** Fixed header space prevents late, wide or tall logos from moving or covering controls. */
@Composable
private fun PlayerTitleLogo(
    title: String,
    logoURL: String?,
    compact: Boolean,
    titleStyle: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val maxWidth = if (compact) 490.dp else 710.dp
    val height = 136.dp
    val widthPx = with(density) { maxWidth.roundToPx() }
    val heightPx = with(density) { height.roundToPx() }
    key(title, logoURL) {
        var logoReady by remember { mutableStateOf(false) }
        val request = remember(context, logoURL, widthPx, heightPx) {
            logoURL?.let {
                ImageRequest.Builder(context).data(TmdbSize.logo(it, widthPx)).tmdbLogo()
                    .size(widthPx, heightPx).crossfade(false).build()
            }
        }
        Box(
            modifier.height(height).clipToBounds().semantics(mergeDescendants = true) {
                contentDescription = title
            },
            contentAlignment = Alignment.CenterStart,
        ) {
            if (!logoReady) Text(title, style = titleStyle, color = TvColor.TextPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clearAndSetSemantics { })
            if (request != null) AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart,
                onLoading = { logoReady = false },
                onSuccess = { logoReady = true },
                onError = { logoReady = false },
                modifier = Modifier.widthIn(max = maxWidth).fillMaxSize().graphicsLayer {
                    alpha = if (logoReady) 1f else 0f
                },
            )
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PlayingTitleHeader(
    session: PlaybackSession,
    title: String,
    releaseName: String?,
    panelOpen: Boolean,
    pictureCorner: Int?,
    modifier: Modifier = Modifier,
) {
    val art by session.nowPlayingArt.collectAsState()
    val tracks by session.tracks.collectAsState()
    val diagnostics by session.diagnosticsState.collectAsState()
    val pictureWidthFraction = LocalContext.current.appGraph.pictureInPicture.size.widthFraction
    val formats = remember(diagnostics, tracks.currentAudio, releaseName) {
        PlayerControlsPolicy.headerFormats(diagnostics, tracks.currentAudio, releaseName)
    }
    BoxWithConstraints(modifier) {
        val compact = maxWidth < 1400.dp
        val topLeftPicture = pictureCorner == 1
        val side = if (compact) 36.dp else 96.dp
        val headerWidth = minOf(960.dp, maxWidth * (if (panelOpen) 0.66f else 1f) - side * 2)
        val top = if (topLeftPicture) {
            maxWidth / 32f + maxWidth * pictureWidthFraction * 9f / 16f + 24.dp
        } else if (compact) 36.dp else 64.dp
        Column(Modifier.padding(start = side, top = top).width(headerWidth)) {
            PlayerTitleLogo(title, art?.logoURL, compact,
                TvType.ControlLabel.copy(fontSize = if (compact) 42.sp else 54.sp,
                    lineHeight = if (compact) 50.sp else 64.sp),
                Modifier.widthIn(max = if (compact) 490.dp else 710.dp).fillMaxWidth())
            if (formats.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.clearAndSetSemantics {
                        contentDescription = formats.joinToString(", ") { if (it == "DD+") "Dolby Digital Plus" else it }
                    },
                ) {
                    formats.forEach { PlayerFormatMark(it, if (compact) 20.dp else 24.dp) }
                }
            }
        }
    }
}

@Composable
private fun PlayerFormatMark(label: String, height: Dp) {
    val logo = when (label) {
        "4K" -> R.drawable.player_format_4k
        "1080p" -> R.drawable.player_format_1080p
        "720p" -> R.drawable.player_format_720p
        "Dolby Vision" -> R.drawable.brand_dolby_vision
        "Dolby Atmos" -> R.drawable.brand_dolby_atmos
        "DD+", "Dolby Digital+", "Dolby Digital Plus" -> R.drawable.brand_dolby_digital_plus
        "Dolby Digital" -> R.drawable.brand_dolby_digital
        "HDR10+" -> R.drawable.player_format_hdr10plus
        "HDR10" -> R.drawable.brand_hdr10
        "HDR" -> R.drawable.brand_hdr
        "IMAX" -> R.drawable.player_format_imax
        "AV1" -> R.drawable.player_format_av1
        "DTS" -> R.drawable.player_format_dts
        "Opus" -> R.drawable.player_format_opus
        "FLAC" -> R.drawable.player_format_flac
        else -> null
    }
    if (logo != null) {
        val painter = painterResource(logo)
        Image(painter, contentDescription = null, contentScale = ContentScale.Fit,
            colorFilter = if (label == "AV1" || label == "HDR10+" || label == "FLAC") null
                else androidx.compose.ui.graphics.ColorFilter.tint(TvColor.TextPrimary),
            modifier = Modifier.height(height)
                .aspectRatio(painter.intrinsicSize.width / painter.intrinsicSize.height))
    } else {
        Box(Modifier.height(height).border(1.5.dp, TvColor.TextPrimary, RoundedCornerShape(5.dp))
            .padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
            Text(label, style = TvType.Badge.copy(fontSize = if (height <= 20.dp) 14.sp else 16.sp,
                lineHeight = if (height <= 20.dp) 18.sp else 22.sp, letterSpacing = (-0.5).sp),
                color = TvColor.TextPrimary, maxLines = 1)
        }
    }
}

@Composable
private fun CastChip(modifier: Modifier = Modifier) {
    Row(
        modifier.semantics(mergeDescendants = true) { stateDescription = "Casting from phone" },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(TvColor.Success))
        Text("Casting from phone", style = TvType.Meta.copy(fontSize = 20.sp, lineHeight = 24.sp),
            color = TvColor.TextPrimary)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun ControlBar(
    interactive: Boolean,
    panelOpen: Boolean,
    formats: List<String>,
    title: String,
    subtitle: String?,
    positionMillis: Long,
    durationMillis: Long,
    bufferedMillis: Long,
    chapterSeconds: List<Double>,
    audioLabel: String,
    subtitlesEnabled: Boolean,
    subtitleLabel: String,
    isLive: Boolean,
    audioLanguage: String?,
    onInteraction: () -> Unit,
    onScrub: (forward: Boolean) -> Unit,
    onTogglePlay: () -> Unit,
    onPanel: (PlayerPanel) -> Unit,
    onPip: (() -> Unit)?,
    pipActive: Boolean,
    sourceSaved: Boolean,
    onSaveSource: (() -> Unit)?,
    onSources: (() -> Unit)?,
    onEpisodes: (() -> Unit)?,
    nextEpisodeLabel: String?,
    onNextEpisode: (() -> Unit)?,
    liveChannel: PlaybackSession.LiveChannel?,
    lastChannelAvailable: Boolean,
    onPreviousChannel: () -> Unit,
    onNextChannel: () -> Unit,
    onLastChannel: () -> Unit,
    onLiveGuide: () -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
) {
    val primaryFocus = remember { FocusRequester() }
    val actionFocus = remember { List(7) { FocusRequester() } }
    var opener by remember { mutableStateOf(-1) }
    val timelineFocus = remember { FocusRequester() }
    val showTimeline = !isLive && durationMillis > 0L
    var hadPanel by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 1400.dp
        val side = if (compact) 36.dp else 96.dp
        val sectionGap = if (compact) 12.dp else 22.dp
        val dockHeight = 112.dp
        val timelineWidth = minOf(1244.dp, maxWidth * 0.84f)
        val titleStyle = TvType.HeroTitle.copy(
            fontSize = if (compact) 32.sp else 54.sp,
            lineHeight = if (compact) 40.sp else 62.sp,
        )
        val contextStyle = TvType.ControlLabel.copy(fontSize = if (compact) 17.sp else 27.sp)
        Box(
            Modifier.fillMaxWidth()
                .height(if (compact) 250.dp else if (isLive) 372.dp else 452.dp)
                .align(Alignment.BottomStart)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.50f to Color.Black.copy(alpha = 0.52f),
                        1f to Color.Black.copy(alpha = 0.92f),
                    ),
                ),
        )
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .padding(start = side, end = side, bottom = 56.dp),
        ) {
            val contextSubtitle = PlayerControlsPolicy.sourceContextSubtitle(subtitle, formats)
            if (isLive) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(title, style = titleStyle, color = TvColor.TextPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("LIVE", style = TvType.Badge, color = TvColor.TextPrimary)
                }
                val liveContext = listOfNotNull(audioLanguage, formats.firstOrNull()).distinct().joinToString(" · ")
                if (liveContext.isNotEmpty()) Text(liveContext, style = contextStyle, color = TvColor.TextPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (liveChannel != null) LiveProgramSummary(liveChannel)
            if (showTimeline) {
                Column(Modifier.width(timelineWidth).align(Alignment.CenterHorizontally)) {
                    if (contextSubtitle != null) {
                        Text(contextSubtitle, style = contextStyle.copy(lineHeight = if (compact) 26.sp else 34.sp),
                            color = TvColor.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp))
                        Spacer(Modifier.height(12.dp))
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        val timeStyle = TvType.data(if (compact) 18 else 26)
                        Text(PlayerControlsPolicy.formatTime(positionMillis), style = timeStyle, color = TvColor.TextPrimary)
                        Text(PlayerControlsPolicy.formatTime(durationMillis), style = timeStyle, color = TvColor.TextPrimary)
                    }
                    SeekTrack(
                        enabled = interactive, positionMillis = positionMillis, durationMillis = durationMillis,
                        bufferedMillis = bufferedMillis,
                        modifier = Modifier.fillMaxWidth().focusRequester(timelineFocus)
                            .focusProperties { down = primaryFocus; up = FocusRequester.Cancel },
                        onScrub = onScrub, onInteraction = onInteraction, onTogglePlay = onTogglePlay,
                    )
                }
                Spacer(Modifier.height(if (compact) 12.dp else 20.dp))
            } else Spacer(Modifier.height(sectionGap))

            Row(
                Modifier.align(Alignment.CenterHorizontally)
                    .focusProperties { up = if (showTimeline) timelineFocus else FocusRequester.Cancel }
                    .focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onPip != null) DockAction(interactive, "PiP", R.drawable.ic_player_pip, dockHeight, compact,
                    Modifier.focusRequester(actionFocus[6]), active = pipActive, onInteraction = onInteraction) {
                    opener = 6
                    onPip()
                }
                if (liveChannel != null) {
                    DockAction(interactive, "Guide", R.drawable.ic_player_guide, dockHeight, compact,
                        Modifier.focusRequester(primaryFocus), onInteraction = onInteraction, onClick = onLiveGuide)
                    DockAction(interactive, "Previous", R.drawable.ic_source_skip_previous, dockHeight, compact,
                        Modifier, onInteraction = onInteraction, onClick = onPreviousChannel)
                    if (lastChannelAvailable) DockAction(interactive, "Last", R.drawable.ic_player_last, dockHeight, compact,
                        Modifier, onInteraction = onInteraction, onClick = onLastChannel)
                    DockAction(interactive, "Next", R.drawable.ic_source_skip_next, dockHeight, compact,
                        Modifier, onInteraction = onInteraction, onClick = onNextChannel)
                    DockAction(interactive, if (isFavorite) "Saved" else "Favorite", R.drawable.ic_player_favorite,
                        dockHeight, compact, Modifier, checked = isFavorite,
                        onInteraction = onInteraction, onClick = onToggleFavorite)
                    Box(Modifier.width(1.dp).height(dockHeight / 2).background(Color.White.copy(alpha = 0.28f)))
                } else {
                    if (onSaveSource != null) DockAction(interactive,
                        if (sourceSaved) "Source saved" else "Save source",
                        if (sourceSaved) R.drawable.ic_player_bookmark_added else R.drawable.ic_player_bookmark,
                        dockHeight, compact, Modifier.width(168.dp).focusRequester(primaryFocus),
                        checked = sourceSaved, onInteraction = onInteraction, onClick = onSaveSource)
                    if (onEpisodes != null) DockAction(interactive, "All episodes", R.drawable.ic_player_sources,
                        dockHeight, compact, Modifier.focusRequester(actionFocus[0]).then(if (onSaveSource == null) Modifier.focusRequester(primaryFocus) else Modifier),
                        onInteraction = onInteraction, onClick = { opener = 0; onEpisodes() })
                    if (!isLive && onSources != null) DockAction(interactive, "Sources", R.drawable.ic_player_layers,
                        dockHeight, compact, Modifier.focusRequester(actionFocus[1]).then(if (onEpisodes == null && onSaveSource == null) Modifier.focusRequester(primaryFocus) else Modifier),
                        onInteraction = onInteraction, onClick = { opener = 1; onSources() })
                }
                DockAction(interactive, "Audio", R.drawable.ic_player_audio, dockHeight, compact,
                    Modifier.focusRequester(actionFocus[2]).then(if (liveChannel == null && onSaveSource == null && onEpisodes == null && onSources == null) Modifier.focusRequester(primaryFocus) else Modifier),
                    value = audioLabel, onInteraction = onInteraction) { opener = 2; onPanel(PlayerPanel.Audio) }
                DockAction(interactive, "Subtitles", R.drawable.ic_player_subtitles, dockHeight, compact,
                    Modifier.focusRequester(actionFocus[3]), value = subtitleLabel, active = subtitlesEnabled, onInteraction = onInteraction) { opener = 3; onPanel(PlayerPanel.Subtitles) }
                if (!isLive && onNextEpisode != null) DockAction(interactive, "Next episode", R.drawable.ic_source_skip_next,
                    dockHeight, compact, Modifier.focusRequester(actionFocus[4]), value = nextEpisodeLabel,
                    onInteraction = onInteraction, onClick = { opener = 4; onNextEpisode() })
                DockAction(interactive, "More", R.drawable.ic_player_more, dockHeight, compact,
                    Modifier.focusRequester(actionFocus[5]), onInteraction = onInteraction) { opener = 5; onPanel(PlayerPanel.More) }
            }
        }


    }
    LaunchedEffect(panelOpen, interactive) {
        if (panelOpen) hadPanel = true
        else if (interactive) {
            withFrameNanos { }
            runCatching { if (hadPanel && opener in actionFocus.indices) actionFocus[opener].requestFocus() else primaryFocus.requestFocus() }
            hadPanel = false
        }
    }
}

/** Guide text is prepared off the UI thread and updates only while live controls are visible. */
@Composable
private fun LiveProgramSummary(channel: PlaybackSession.LiveChannel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val iptv = remember(context) { context.clientGraph.iptv }
    val state by iptv.state.collectAsState()
    val programs = state.catalog.programs
    val schedule by produceState<List<IptvProgram>>(emptyList(), programs, channel.id) {
        value = withContext(Dispatchers.Default) {
            programs.filter { it.channelKey == channel.id }.sortedBy(IptvProgram::startMillis)
        }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(channel.id) {
        now = System.currentTimeMillis()
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L)
            now = System.currentTimeMillis()
        }
    }
    val current = remember(schedule, now) { schedule.firstOrNull { it.startMillis <= now && it.endMillis > now } }
    val next = remember(schedule, now) { schedule.firstOrNull { it.startMillis > now } }
    if (current != null || next != null) Text(
        listOfNotNull(current?.let { "Now · ${it.title}" }, next?.let { "Next · ${it.title}" }).joinToString("     "),
        style = TvType.Meta, color = TvColor.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun DockAction(
    enabled: Boolean,
    label: String,
    icon: Int,
    height: Dp,
    compact: Boolean,
    modifier: Modifier = Modifier,
    value: String? = null,
    checked: Boolean? = null,
    active: Boolean = checked == true,
    onInteraction: () -> Unit,
    onClick: () -> Unit,
) {
    val caption = when (label) {
        "All episodes" -> "Episodes"
        "Next episode" -> "Next"
        else -> label
    }
    TvFocusable(
        onClick = { onInteraction(); onClick() },
        enabled = enabled,
        modifier = modifier.width(112.dp)
            .onFocusChanged { if (it.isFocused) onInteraction() },
        accessibleLabel = listOfNotNull(label, value).joinToString(", "),
        checked = checked,
        focusScale = 1f,
        focusRing = false,
    ) { focused ->
        Column(
            Modifier.height(height),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(80.dp), contentAlignment = Alignment.Center) {
                val tint = when {
                    active -> TvColor.Success
                    focused -> TvColor.Accent
                    else -> TvColor.TextPrimary
                }
                val iconSize = if (compact) 54.dp else 60.dp
                Image(
                    painter = painterResource(icon),
                    contentDescription = null,
                    modifier = Modifier.size(iconSize),
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint),
                )
            }
            // Reserve the caption line so remote focus never moves or resizes the icon row.
            Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.Center) {
                if (focused) Text(caption, style = TvType.ControlLabel.copy(
                    fontSize = 24.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.Medium,
                ), color = TvColor.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SeekTrack(
    enabled: Boolean,
    positionMillis: Long,
    durationMillis: Long,
    bufferedMillis: Long,
    modifier: Modifier,
    onScrub: (forward: Boolean) -> Unit,
    onInteraction: () -> Unit,
    onTogglePlay: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val fraction = if (durationMillis > 0) positionMillis.toFloat() / durationMillis else 0f
    val buffered = if (durationMillis > 0) bufferedMillis.toFloat() / durationMillis else 0f
    Box(
        modifier
            .height(44.dp)
            .onFocusChanged { if (it.isFocused) onInteraction() }
            .semantics {
                contentDescription = "Playback position"
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = positionMillis.coerceIn(0L, durationMillis.coerceAtLeast(0L)).toFloat(),
                    range = 0f..durationMillis.coerceAtLeast(1L).toFloat(),
                )
                stateDescription = "${PlayerControlsPolicy.formatTime(positionMillis)} of " +
                    PlayerControlsPolicy.formatTime(durationMillis)
                customActions = if (!enabled) emptyList() else listOf(
                    CustomAccessibilityAction("Rewind") { onScrub(false); true },
                    CustomAccessibilityAction("Fast forward") { onScrub(true); true },
                )
            }
            .focusable(enabled = enabled, interactionSource = interaction)
            .onKeyEvent { event ->
                if (!enabled) return@onKeyEvent false
                if (event.key in listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)) {
                    if (event.type == KeyEventType.KeyUp) { onInteraction(); onTogglePlay() }
                    true
                } else if (event.type == KeyEventType.KeyDown) when (event.key) {
                    Key.DirectionLeft -> { onScrub(false); true }
                    Key.DirectionRight -> { onScrub(true); true }
                    else -> false
                } else false
            },
    ) {
        Canvas(Modifier.fillMaxWidth().height(44.dp).align(Alignment.Center)) {
            val y = center.y
            // Reserve the focused halo at both ends. Focus changes size without shifting time.
            val inset = 22.dp.toPx().coerceAtMost(size.width / 2f)
            val width = (size.width - 2f * inset).coerceAtLeast(0f)
            val start = Offset(inset, y)
            val thumb = Offset(inset + width * fraction.coerceIn(0f, 1f), y)
            drawLine(Color.White.copy(alpha = 0.18f), start, Offset(inset + width, y), 12.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(Color.White.copy(alpha = 0.32f), start, Offset(inset + width * buffered.coerceIn(0f, 1f), y), 12.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(TvColor.Success, start, thumb, 12.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            if (focused) {
                drawCircle(TvColor.Accent.copy(alpha = 0.18f), 22.dp.toPx(), thumb)
                drawCircle(TvColor.Accent.copy(alpha = 0.32f), 18.dp.toPx(), thumb)
            }
            drawCircle(TvColor.Accent, (if (focused) 14.dp else 9.dp).toPx(), thumb)
        }
    }
}
// ---------------------------------------------------------------------------- panels

/** Browse over the Activity-owned video surface; only selecting a channel changes the stream. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun LiveGuidePanel(
    repo: IptvRepository,
    state: IptvState,
    active: PlaybackSession.LiveChannel,
    browseContext: PlaybackSession.LiveChannel,
    group: String?,
    status: String?,
    favoriteNotice: String?,
    phase: ReceiverPlaybackPhase,
    tuning: Boolean,
    pictureReady: Boolean,
    onGroup: (String?) -> Unit,
    onTune: (IptvChannel, PlaybackSession.LiveChannel) -> Unit,
    returnChannel: IptvChannel?,
    retainedPictureName: String?,
    onFocusedChannel: (IptvChannel?) -> Unit,
    onClose: () -> Unit,
    onExit: () -> Unit,
) {
    var highlighted by remember { mutableStateOf<IptvChannel?>(null) }
    var focusedPreviewID by remember { mutableStateOf<String?>(null) }
    val shell = LocalShellState.current
    val playback = LocalContext.current.appGraph.playback
    var viewport by remember { mutableStateOf<Rect?>(null) }
    var canvasOrigin by remember { mutableStateOf(Offset.Zero) }
    val rowFocus = remember { FocusRequester() }
    val groupFocus = remember { FocusRequester() }
    val groupListState = rememberLazyListState()
    var groupEntryRequest by remember { mutableStateOf(0) }
    var browsingGroups by remember { mutableStateOf(false) }
    var focusTarget by remember { mutableStateOf(browseContext.id) }
    var restoringFocus by remember { mutableStateOf(true) }
    val channelFocusScroll = remember { PivotSpec.nearestEdge() }
    var channelEntryRequest by remember { mutableStateOf(0) }
    DisposableEffect(Unit) { onDispose { shell.livePreviewViewport?.invoke(null) } }
    var mode by rememberSaveable { mutableStateOf(LiveBrowseMode.Current) }
    var playbackCategory by remember { mutableStateOf<PlaybackSession.LiveChannel?>(browseContext) }
    var favoriteList by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var matchIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
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
    val groups = remember(state.orderedGroups, visible) {
        val available = visible.map(IptvChannel::group).toSet()
        state.orderedGroups.filter { it in available }
    }
    val selectedGroup = group?.takeIf { it in groups }
    val visibleById = remember(visible) { visible.associateBy(IptvChannel::id) }
    // Preview playback still saves history, but must not move the row under the remote.
    val recentIds = rememberSaveable(mode) { state.accounts.recentIds }
    val channels = remember(mode, group, playbackCategory, visible, matchIds, favoriteList,
        state.accounts.favoriteIds, state.accounts.favoriteLists, recentIds) { when (mode) {
        LiveBrowseMode.Current -> LiveChannelOrder.category(visible,
            playbackCategory?.takeIf { it.browseGroup == group }?.channelIds.orEmpty(), group)
        LiveBrowseMode.Favorites -> visible.filter { it.id in (favoriteList?.let { name -> state.accounts.favoriteLists[name].orEmpty() }
            ?: state.accounts.favoriteIds) }
        LiveBrowseMode.Recents -> recentIds.mapNotNull(visibleById::get)
        LiveBrowseMode.Search -> matchIds.mapNotNull(visibleById::get)
        LiveBrowseMode.Groups -> emptyList()
    } }
    val listState = rememberLazyListState()
    val displayedChannelIDs = remember(channels) { channels.map(IptvChannel::id) }
    val currentTargetAvailable = channels.any { it.id == focusTarget }
    fun showChannels() {
        focusedPreviewID = null
        playbackCategory = null
        browsingGroups = false
        mode = LiveBrowseMode.Current
        channelEntryRequest++
    }
    fun showGroups() {
        focusedPreviewID = null
        playbackCategory = null
        mode = LiveBrowseMode.Current
        browsingGroups = true
        groupEntryRequest++
    }
    LaunchedEffect(groupEntryRequest, groups) {
        if (groupEntryRequest == 0 || !browsingGroups) return@LaunchedEffect
        val index = selectedGroup?.let { groups.indexOf(it) + 1 } ?: 0
        kotlinx.coroutines.withTimeoutOrNull(1_000) {
            val layout = snapshotFlow { groupListState.layoutInfo }.first { it.totalItemsCount > 0 }
            val item = layout.visibleItemsInfo.firstOrNull { it.index == index }
            if (item == null || item.offset < layout.viewportStartOffset ||
                item.offset + item.size > layout.viewportEndOffset) groupListState.scrollToItem(index)
            do {
                withFrameNanos { }
                if (!browsingGroups) return@withTimeoutOrNull
            } while (!runCatching { groupFocus.requestFocus() }.getOrDefault(false))
        }
    }
    LaunchedEffect(mode, group, favoriteList, channels.isNotEmpty(), currentTargetAvailable, browsingGroups, channelEntryRequest,
        if (mode == LiveBrowseMode.Current) playbackCategory?.channelIds else null) {
        if (mode == LiveBrowseMode.Groups || (mode == LiveBrowseMode.Search && !restoringFocus)) return@LaunchedEffect
        val target = highlighted?.id?.takeIf { id -> channels.any { it.id == id } }
            ?: focusTarget.takeIf { id -> channels.any { it.id == id } }
            ?: active.id.takeIf { id -> channels.any { it.id == id } } ?: channels.firstOrNull()?.id
        if (target != null) focusTarget = target
        if (target != null) {
            // Wait for the restored viewport before deciding whether the active row needs a scroll.
            val layout = snapshotFlow { listState.layoutInfo }.first { it.totalItemsCount > 0 }
            val item = layout.visibleItemsInfo.firstOrNull { it.key == target }
            val fullyVisible = item != null && item.offset >= layout.viewportStartOffset &&
                item.offset + item.size <= layout.viewportEndOffset
            if (!fullyVisible) listState.scrollToItem(channels.indexOfFirst { it.id == target }.coerceAtLeast(0))
            if (!browsingGroups) kotlinx.coroutines.withTimeoutOrNull(1_000) {
                do {
                    withFrameNanos { }
                    if (browsingGroups) return@withTimeoutOrNull
                } while (!runCatching { rowFocus.requestFocus() }.getOrDefault(false))
            }
        } else if (!browsingGroups) runCatching { first.requestFocus() }
        // Keep an already visible row still through the first focus/layout pass only.
        repeat(2) { withFrameNanos { } }
        restoringFocus = false
    }
    LaunchedEffect(query, mode, state.catalog, state.sources,
        state.accounts.hiddenGroups, state.accounts.lockedGroups) {
        if (mode != LiveBrowseMode.Search || query.isBlank()) { matchIds = emptyList(); return@LaunchedEffect }
        delay(250)
        matchIds = withContext(Dispatchers.Default) {
            IptvIndex.search(visible, state.catalog.programs, query, limit = 100,
                groupOrder = state.orderedGroups).map(IptvChannel::id)
        }
    }
    BackHandler {
        focusedPreviewID = null
        if (!browsingGroups) showGroups() else onExit()
    }

    val categoryLabel = when (mode) {
        LiveBrowseMode.Current -> playbackCategory?.takeIf { it.browseGroup == group }?.browseLabel ?: group ?: "All channels"
        LiveBrowseMode.Favorites -> favoriteList ?: "Favorites"
        LiveBrowseMode.Recents -> "Recent channels"
        LiveBrowseMode.Search -> "Search results"
        LiveBrowseMode.Groups -> "Choose a category"
    }

    fun preview(channel: IptvChannel) {
        val inDisplayedList = channels.any { it.id == channel.id }
        val mixed = inDisplayedList && (mode == LiveBrowseMode.Favorites ||
            mode == LiveBrowseMode.Recents || mode == LiveBrowseMode.Search)
        val chosenGroup = if (mixed) null else if (inDisplayedList) group else channel.group
        val chosen = if (inDisplayedList) channels else visible.filter { it.group == channel.group }
        val label = if (inDisplayedList) categoryLabel else channel.group
        val context = PlaybackSession.LiveChannel(channel.id, channel.sourceId, channel.group,
            chosenGroup, if (inDisplayedList) displayedChannelIDs else chosen.map(IptvChannel::id), label)
        // Adopt the actual displayed order immediately, while its URL is still resolving.
        playbackCategory = context
        highlighted = channel
        if (!inDisplayedList) mode = LiveBrowseMode.Current
        onGroup(chosenGroup)
        if (!tuning && (phase is ReceiverPlaybackPhase.Playing || phase is ReceiverPlaybackPhase.Paused) &&
            playback.updateLiveBrowseContext(context)) return
        onTune(channel, context)
    }

    LaunchedEffect(focusedPreviewID, channels, browsingGroups, group, categoryLabel) {
        val id = focusedPreviewID ?: return@LaunchedEffect
        val sameCategory = browseContext.browseGroup == group &&
            browseContext.channelIds == displayedChannelIDs && browseContext.browseLabel == categoryLabel
        if (browsingGroups || (id == browseContext.id && sameCategory)) return@LaunchedEffect
        val channel = channels.firstOrNull { it.id == id } ?: return@LaunchedEffect
        if (playback.origin.value != PlaybackSession.Origin.Local || playback.liveChannel.value == null)
            return@LaunchedEffect
        preview(channel)
    }

    val returnToGroups = Modifier.onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
            showGroups()
            true
        } else false
    }
    val playing = state.channels.firstOrNull { it.id == active.id }
    val now = repo.now(active.id, guideNow)
    val schedule = now?.let { "Now · ${it.title}" } ?: "Program information unavailable"

    val previewRadius = com.fourseveneightnine.tv.client.ui.GuidePictureRadius
    Box(Modifier.fillMaxSize().onGloballyPositioned { canvasOrigin = it.positionInWindow() }.drawBehind {
        val canvas = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(Offset.Zero, size))
            viewport?.let { addRoundRect(RoundRect(Rect(it.topLeft - canvasOrigin, it.size), CornerRadius(previewRadius.toPx()))) }
        }
        drawPath(canvas, TvColor.Canvas)
    }) {
        Row(
            Modifier.fillMaxSize().padding(start = 96.dp, end = 80.dp, top = 54.dp, bottom = 54.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Column(Modifier.width(264.dp)) {
                Text("Groups", style = TvType.ShelfHeader, color = TvColor.TextSecondary)
                Spacer(Modifier.height(18.dp))
                val groupRow: @Composable (String?) -> Unit = { value ->
                    val selected = group == value && mode == LiveBrowseMode.Current
                    TvFocusableCard(
                        onClick = {
                            playbackCategory = null
                            onGroup(value)
                            mode = LiveBrowseMode.Current
                            browsingGroups = false
                        },
                        onFocused = {
                            browsingGroups = true
                            if (group != value || mode != LiveBrowseMode.Current) {
                                playbackCategory = null
                                onGroup(value)
                                mode = LiveBrowseMode.Current
                            }
                        },
                        selected = selected,
                        modifier = (if (value == selectedGroup) Modifier.focusRequester(groupFocus) else Modifier)
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                                    browsingGroups = false
                                    true
                                } else false
                            },
                        focusScale = 1f,
                        focusRing = false,
                    ) { focused ->
                        Text(
                            value ?: "All channels",
                            style = TvType.Meta,
                            color = if (selected || focused) TvColor.TextPrimary else TvColor.TextSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clip(CircleShape)
                                .then(if (selected || focused) Modifier.tvGlassPill({ null }, focused, selected) else Modifier)
                                .background(if (focused) Color.White.copy(alpha = 0.22f) else Color.Transparent)
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                        )
                    }
                }
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f).focusGroup(),
                    state = groupListState,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item(key = "all") { groupRow(null) }
                    items(groups, key = { "group:$it" }) { groupRow(it) }
                }
            }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.aspectRatio(16f / 9f)
                            .onGloballyPositioned { coordinates ->
                                val bounds = coordinates.boundsInWindow()
                                viewport = bounds
                                shell.livePreviewViewport?.invoke(android.graphics.Rect(
                                    bounds.left.roundToInt(), bounds.top.roundToInt(),
                                    bounds.right.roundToInt(), bounds.bottom.roundToInt(),
                                ))
                            }
                            .clip(RoundedCornerShape(previewRadius))
                            .background(if (pictureReady) Color.Transparent else TvColor.Elevated),
                    ) {
                        val message = when {
                            tuning || phase is ReceiverPlaybackPhase.Opening || phase is ReceiverPlaybackPhase.Buffering ||
                                (!pictureReady && phase is ReceiverPlaybackPhase.Playing) -> "Loading live stream…"
                            phase is ReceiverPlaybackPhase.Error -> "This stream is unavailable. Choose another channel or retry."
                            phase is ReceiverPlaybackPhase.Stopped || phase is ReceiverPlaybackPhase.Idle -> "Connecting…"
                            else -> null
                        }
                        if (message != null && phase !is ReceiverPlaybackPhase.Error && !shell.holdingLivePicture) {
                            PlaybackLoadingSurface(art = null, title = "Live stream")
                        } else if (message != null && phase is ReceiverPlaybackPhase.Error) {
                            Box(Modifier.fillMaxSize().background(TvColor.Canvas), contentAlignment = Alignment.Center) {
                                Text(message, style = TvType.Meta, color = TvColor.TextPrimary,
                                    modifier = Modifier.padding(24.dp))
                            }
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val detail = favoriteNotice ?: status ?: schedule
                    Column(Modifier.weight(1f)) {
                        Text(retainedPictureName ?: playing?.name ?: categoryLabel,
                            style = TvType.ControlLabel, color = TvColor.TextPrimary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(detail, style = TvType.Meta,
                                color = if (favoriteNotice != null) TvColor.Cached else TvColor.TextSecondary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (detail == schedule && now != null && now.endMillis > now.startMillis) {
                                TvProgressBar(
                                    ((guideNow - now.startMillis).toFloat() / (now.endMillis - now.startMillis)).coerceIn(0f, 1f),
                                    height = 5.dp, modifier = Modifier.width(48.dp),
                                )
                            }
                        }
                    }
                    LiveGuidePill("Channels", onClick = ::showChannels,
                        modifier = Modifier.focusRequester(first).then(returnToGroups),
                        selected = mode == LiveBrowseMode.Current)
                    LiveGuidePill("Favorites", onClick = { mode = LiveBrowseMode.Favorites },
                        selected = mode == LiveBrowseMode.Favorites)
                    LiveGuidePill("Recent", onClick = { mode = LiveBrowseMode.Recents },
                        selected = mode == LiveBrowseMode.Recents)
                    LiveGuidePill("Search", onClick = { mode = LiveBrowseMode.Search },
                        selected = mode == LiveBrowseMode.Search)
                    LiveGuidePill("Full screen", onClick = { focusedPreviewID = null; onClose() })
                }

                if (status?.startsWith("Couldn't tune") == true && returnChannel != null) {
                    LiveGuidePill("Return to ${returnChannel.name}", onClick = { preview(returnChannel) })
                }
                if (phase is ReceiverPlaybackPhase.Error && playing != null) {
                    LiveGuidePill("Retry live stream", onClick = { preview(playing) })
                }
                if (mode == LiveBrowseMode.Favorites && state.accounts.favoriteLists.isNotEmpty()) {
                    LiveGuidePill(favoriteList ?: "All favorites", onClick = {
                        val lists = listOf<String?>(null) + state.accounts.favoriteLists.keys
                        favoriteList = lists[(lists.indexOf(favoriteList) + 1) % lists.size]
                    })
                }
                if (mode == LiveBrowseMode.Search) {
                    LiveFormField("Search channels and programs", query, onChange = { query = it })
                }

                CompositionLocalProvider(LocalBringIntoViewSpec provides channelFocusScroll) {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().height(316.dp).then(returnToGroups)
                            .semantics { paneTitle = categoryLabel },
                        state = listState,
                        contentPadding = PaddingValues(vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(channels, key = IptvChannel::id) { channel ->
                            LiveGuideChannelRow(
                                channel,
                                playing = channel.id == active.id && phase is ReceiverPlaybackPhase.Playing,
                                now = repo.now(channel.id, guideNow),
                                next = repo.next(channel.id, guideNow),
                                modifier = (if (channel.id == focusTarget) Modifier.focusRequester(rowFocus) else Modifier)
                                    .onFocusChanged {
                                        if (!it.isFocused && focusedPreviewID == channel.id) focusedPreviewID = null
                                    },
                                onFocused = {
                                    browsingGroups = false
                                    highlighted = channel
                                    onFocusedChannel(channel)
                                    focusedPreviewID = channel.id
                                },
                                onClick = {
                                    focusedPreviewID = null
                                    if (!tuning || channel.id != browseContext.id) preview(channel)
                                    onClose()
                                },
                            )
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
        }
    }
}

@Composable
private fun LiveGuidePill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean? = null,
) {
    TvFocusableCard(
        onClick = onClick,
        modifier = modifier,
        accessibleLabel = label,
        selected = selected,
        focusScale = 1f,
        focusRing = false,
    ) { focused ->
        Box(
            Modifier.height(54.dp).clip(CircleShape)
                .tvGlassPill({ null }, focused, selected == true)
                .background(if (focused) Color.White.copy(alpha = 0.22f) else Color.Transparent)
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = TvType.ControlLabel, color = TvColor.TextPrimary, maxLines = 1)
        }
    }
}

@Composable
private fun LiveGuideChannelRow(
    channel: IptvChannel,
    playing: Boolean,
    now: IptvProgram?,
    next: IptvProgram?,
    modifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    TvFocusableCard(
        onClick = onClick,
        modifier = modifier,
        stateDescription = if (playing) "Playing" else null,
        clickLabel = "Watch full screen",
        onFocused = onFocused,
        focusScale = 1f,
        focusRing = false,
    ) { focused ->
        Row(
            Modifier.fillMaxWidth().height(90.dp).clip(CircleShape)
                .tvGlassPill({ null }, focused, playing)
                .background(if (focused) Color.White.copy(alpha = 0.22f) else Color.Transparent)
                .padding(horizontal = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Box(Modifier.size(width = 96.dp, height = 52.dp), contentAlignment = Alignment.Center) {
                if (!channel.logo.isNullOrBlank()) {
                    TvArtwork(PosterRequest(channel.logo, 192), channel.name,
                        Modifier.fillMaxSize(), showTitleWhenMissing = false)
                }
            }
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (playing) Box(Modifier.size(8.dp).background(TvColor.Cached, CircleShape))
                Text(channel.name, style = TvType.ControlLabel, color = TvColor.TextPrimary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Column(Modifier.weight(1.5f)) {
                Text("Now", style = TvType.Meta, color = TvColor.TextSecondary)
                Text(now?.title ?: "No program information", style = TvType.Meta, color = TvColor.TextPrimary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Column(Modifier.weight(1.25f)) {
                next?.let {
                    Text("Next", style = TvType.Meta, color = TvColor.TextSecondary)
                    Text(it.title, style = TvType.Meta, color = TvColor.TextPrimary,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
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
    SharedSidePanelRow(label, selected, onClick, modifier, height = 94.dp, focusRing = true)
}

@Composable
private fun pictureSizeLabel(mode: VideoResizeMode): String =
    if (mode.zoomPercent > 100) stringResource(mode.labelRes(), mode.zoomPercent)
    else stringResource(mode.labelRes())

@Composable
private fun PlayerSidePanel(
    panel: PlayerPanel,
    session: PlaybackSession,
    subtitleRequest: com.fourseveneightnine.tv.client.playback.PlayRequest?,
    subtitleSource: StreamRow?,
    resizeMode: VideoResizeMode,
    upscaleMode: UpscaleMode,
    chapterSeconds: List<Double>,
    showSources: Boolean,
    liveSourceStatus: String?,
    onMultiview: (() -> Unit)?,
    nextLabel: String?,
    externalPlayers: List<InstalledExternalPlayer>,
    onSpeed: (Float) -> Unit,
    onResize: (VideoResizeMode) -> Unit,
    onUpscale: (UpscaleMode) -> Unit,
    onChapter: (Double) -> Unit,
    onNavigate: (PlayerPanel) -> Unit,
    onSources: () -> Unit,
    pip: com.fourseveneightnine.tv.client.playback.PictureInPicture?,
    onAddMatch: () -> Unit,
    onRestartLive: () -> Unit,
    onNext: () -> Unit,
    onHandoff: (InstalledExternalPlayer) -> Unit,
    onClose: () -> Unit,
) {
    val tracks by session.tracks.collectAsState()
    val diagnostics by session.diagnosticsState.collectAsState()
    val subtitleStyle by session.subtitleStyle.collectAsState()
    val context = LocalContext.current
    val services by context.clientGraph.services.collectAsState()
    val phoneSubtitles by SubtitleOptions.options.collectAsState()
    val generation = session.generation
    val scope = rememberCoroutineScope()
    var searchAttempt by remember(generation) { mutableStateOf(0) }
    var subtitleSearch by remember(generation, services, subtitleRequest) { mutableStateOf<SubtitleSearchResult?>(null) }
    var subtitleSearchMessage by remember(generation) { mutableStateOf<String?>(null) }
    var subtitleLoadJob by remember(generation) { mutableStateOf<Job?>(null) }
    var subtitleLoadVersion by remember(generation) { mutableStateOf(0) }
    var loadingSubtitle by remember(generation) { mutableStateOf<String?>(null) }
    var loadedSubtitle by remember(generation) { mutableStateOf<Pair<String, Int>?>(null) }
    var subtitleLoadMessage by remember(generation) { mutableStateOf<String?>(null) }
    val subtitlePanelOpen = panel == PlayerPanel.Subtitles
    LaunchedEffect(subtitlePanelOpen, generation, services, subtitleRequest, searchAttempt) {
        if (!subtitlePanelOpen) return@LaunchedEffect
        val request = subtitleRequest
        val data = services
        subtitleSearch = null
        subtitleSearchMessage = when {
            request == null -> "Open a movie or episode on the TV to search its subtitle add-ons."
            data == null -> "Add a subtitle add-on in Settings, then search again."
            request.type == "series" && (request.season == null || request.episode == null) -> "Choose an episode to search for subtitles."
            else -> null
        }
        if (subtitleSearchMessage != null || request == null || data == null) return@LaunchedEffect
        val videoID = context.clientGraph.subtitleVideoId(request, data)
        if (videoID == null) {
            subtitleSearchMessage = "This title has no supported subtitle search ID."
            return@LaunchedEffect
        }
        val extra = SubtitleExtra(subtitleSource?.filename, subtitleSource?.facts?.videoHash, subtitleSource?.sizeBytes)
        data.registry.searchSubtitles(request.type, videoID, extra).collect { result ->
            if (session.generation == generation) subtitleSearch = result
        }
    }
    DisposableEffect(generation) {
        onDispose { subtitleLoadJob?.cancel() }
    }
    val addonSubtitles = (subtitleSearch?.tracks.orEmpty() + phoneSubtitles
        .takeIf { session.origin.value == PlaybackSession.Origin.Phone }.orEmpty().map { option ->
        SubtitleTrack(option.url, option.url, option.language, option.label)
    }).filter { SubtitleTrack.isEnglishLanguage(it.language) }.distinctBy(SubtitleTrack::url)
    val snapshot = session.snapshot.collectAsState()
    // Position ticks keep the timeline current; this panel only needs changes to the rate.
    val speed by remember(snapshot) { derivedStateOf { snapshot.value.speedMultiplier } }
    val header = when (panel) {
        PlayerPanel.Episodes -> "All episodes"
        PlayerPanel.Sources -> "Sources"
        PlayerPanel.Audio -> "Audio"
        PlayerPanel.Subtitles -> "Subtitles"
        PlayerPanel.SubtitleStyle -> "Subtitle appearance"
        PlayerPanel.AddChannel -> "Add live channel"
        PlayerPanel.Pip -> "Picture in picture"
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
    val appearanceFocus = remember { FocusRequester() }
    var previousPanel by remember { mutableStateOf<PlayerPanel?>(null) }
    val initialChapterIndex = remember(panel, chapterSeconds, session) {
        PlayerControlsPolicy.chapterIndexAt(chapterSeconds, session.snapshot.value.positionSeconds)
    }
    val entryIndex = when (panel) {
        PlayerPanel.Audio -> tracks.audio.indexOfFirst { it.selected }
        PlayerPanel.Subtitles ->
            if (tracks.currentSubtitle == null) 0 else tracks.subtitles.indexOfFirst { it.selected } + 1
        PlayerPanel.Speed ->
            PlayerControlsPolicy.SPEEDS.indexOfFirst { kotlin.math.abs(it - speed) < 0.01f }
        PlayerPanel.Aspect -> remember(panel) { resizeMode.ordinal }
        PlayerPanel.Upscale -> UpscaleMode.entries.indexOf(upscaleMode)
        PlayerPanel.Chapters -> initialChapterIndex
        PlayerPanel.SubtitleStyle,
        PlayerPanel.AddChannel,
        PlayerPanel.Pip,
        PlayerPanel.More,
        PlayerPanel.Episodes,
        PlayerPanel.Sources,
        PlayerPanel.PlaybackInfo,
        PlayerPanel.Handoff,
        -> 0
    }.coerceAtLeast(0)
    // `session.refreshTracks()` answers after the panel is drawn, so Audio can open on an empty
    // Column. An empty Column has no focus target and the panel then covers the screen with
    // nothing the remote can reach. One focusable row always exists.
    val tracksPending = panel == PlayerPanel.Audio && tracks.audio.isEmpty()
    val listState = rememberLazyListState()
    SidePanel(header = header, onClose = onClose, width = 640.dp, scrimAlpha = 0f) {
        if (panel == PlayerPanel.Subtitles) {
            PlayerMenuRow(playerMenuLabel("Appearance", subtitleStyleSummary(subtitleStyle)), false,
                onClick = { onNavigate(PlayerPanel.SubtitleStyle) }, modifier = Modifier.focusRequester(appearanceFocus))
            PlayerMenuRow(if (subtitleSearch?.searching == true) "Searching subtitle add-ons…" else "Search subtitles", false,
                onClick = { if (subtitleSearch?.searching != true) searchAttempt++ })
            Spacer(Modifier.height(12.dp))
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().height(if (panel == PlayerPanel.Subtitles) 614.dp else 820.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(TvSpace.XS),
            contentPadding = PaddingValues(bottom = 60.dp),
        ) {
            if (pip != null && (panel == PlayerPanel.Pip || panel == PlayerPanel.More)) {
                if (pip.channel == null && liveSourceStatus == null) item(key = "pip-add") {
                    PlayerMenuRow("Add live channel", false, onAddMatch)
                } else if (pip.channel != null) {
                    item(key = "pip-swap") {
                        PlayerMenuRow("Swap pictures", false, pip::swap,
                            if (panel == PlayerPanel.Pip) Modifier.focusRequester(entryRow) else Modifier)
                    }
                    item(key = "pip-sound") {
                        PlayerMenuRow(if (pip.liveSound) "Switch sound to movie" else "Switch sound to match",
                            false, pip::switchSound)
                    }
                    item(key = "pip-move") { PlayerMenuRow("Move small picture", false, pip::move) }
                    item(key = "pip-close") {
                        PlayerMenuRow(if (pip.liveLarge) "Close movie" else "Close match", false, {
                            if (panel == PlayerPanel.Pip) onClose()
                            pip.closeSmall()
                        })
                    }
                }
            }
            when (panel) {
                PlayerPanel.Episodes, PlayerPanel.Sources, PlayerPanel.AddChannel, PlayerPanel.Pip -> Unit
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
                                readableTrackLabel(
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
                                subtitleLoadJob?.cancel()
                                subtitleLoadVersion++
                                loadingSubtitle = null
                                subtitleLoadMessage = null
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
                                readableTrackLabel(
                                    track.language, track.name, null, null, "Track ${index + 1}",
                                ),
                                null,
                            ),
                            selected = track.selected,
                            onClick = {
                                subtitleLoadJob?.cancel()
                                subtitleLoadVersion++
                                loadingSubtitle = null
                                subtitleLoadMessage = null
                                session.launchOn {
                                    selectSubtitleTrack(SubtitleSelection.Index(track.index, enable = true))
                                    refreshTracks()
                                }
                            },
                            modifier = if (index + 1 == entryIndex) Modifier.focusRequester(entryRow) else Modifier,
                        )
                    }
                    item(key = "subtitle-search-status") {
                        val search = subtitleSearch
                        val status = subtitleLoadMessage ?: subtitleSearchMessage ?: when {
                            loadingSubtitle != null -> "Loading subtitle…"
                            search == null -> "Finding subtitle add-ons…"
                            search.total == 0 -> "Add a subtitle add-on in Settings, then search again."
                            search.searching -> "Searching add-ons · ${search.completed} of ${search.total} finished"
                            search.failed == search.total -> "Subtitle add-ons could not be reached. Search again."
                            addonSubtitles.isEmpty() -> "No English subtitles found."
                            search.failed > 0 -> "${search.failed} add-on(s) did not answer. You can search again."
                            else -> "English subtitles · ${addonSubtitles.size}"
                        }
                        Text(status, style = TvType.Meta, color = TvColor.TextSecondary,
                            modifier = Modifier.padding(vertical = TvSpace.S))
                    }
                    itemsIndexed(addonSubtitles, key = { _, track -> "addon-subtitle:${track.url}" }) { index, track ->
                        PlayerMenuRow(
                            label = playerMenuLabel(readableTrackLabel(track.language, null, null, null, "Unknown language"),
                                "${track.addonName} · ${index + 1}"),
                            selected = loadedSubtitle?.let { it.first == track.url && it.second == tracks.currentSubtitle?.index } == true,
                            onClick = {
                                subtitleLoadJob?.cancel()
                                val loadVersion = ++subtitleLoadVersion
                                loadingSubtitle = track.url
                                subtitleLoadMessage = null
                                subtitleLoadJob = scope.launch {
                                    try {
                                        val result = session.addSubtitleTrack(track.url, track.language)
                                        if (session.generation == generation && subtitleLoadVersion == loadVersion) {
                                            subtitleLoadMessage = if (result.isSuccess) "Subtitle loaded" else "Could not load this subtitle. Try another result."
                                            if (result.isSuccess) session.tracks.value.currentSubtitle?.index?.let {
                                                loadedSubtitle = track.url to it
                                            }
                                        }
                                    } finally {
                                        if (session.generation == generation && subtitleLoadVersion == loadVersion) loadingSubtitle = null
                                    }
                                }
                            },
                        )
                    }
                }

                PlayerPanel.SubtitleStyle -> {
                    item(key = "style-preview") {
                        Box(Modifier.fillMaxWidth().height(150.dp).clip(TvShape.Card).background(TvColor.Elevated2), Alignment.Center) {
                            val typeface = MpvSubtitleFontPolicy.resolvedTypeface(context, subtitleStyle.family)
                            Text("This is how your subtitles look", style = TvType.Body.copy(
                                fontSize = subtitleStyle.size.sp, lineHeight = (subtitleStyle.size * 1.4).sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily(typeface),
                                fontWeight = if (subtitleStyle.bold) FontWeight.Bold else FontWeight.Normal,
                                shadow = if (subtitleStyle.shadow)
                                    androidx.compose.ui.graphics.Shadow(Color.Black, Offset(1f, 1f), 3f) else null),
                                color = runCatching { Color(android.graphics.Color.parseColor(subtitleStyle.colorHex)) }.getOrDefault(Color.White),
                                modifier = Modifier.padding(bottom = (subtitleStyle.lift / 10).dp).padding(8.dp))
                        }
                        Text("Preview · ${subtitlePositionLabel(subtitleStyle.lift)} position", style = TvType.Meta, color = TvColor.TextMuted)
                    }
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
                    item(key = "style-shadow") {
                        PlayerMenuRow(
                            label = playerMenuLabel(
                                "Soft shadow",
                                if (subtitleStyle.shadow) "On" else "Off",
                            ),
                            selected = false,
                            onClick = {
                                session.launchOn {
                                    stageSubtitleStyle(
                                        subtitleStyle.copy(
                                            shadow = !subtitleStyle.shadow,
                                        ).toParams(),
                                    )
                                }
                            },
                        )
                    }
                    item(key = "style-weight") {
                        PlayerMenuRow(
                            label = playerMenuLabel(
                                "Text weight",
                                if (subtitleStyle.bold) "Bold" else "Standard",
                            ),
                            selected = false,
                            onClick = {
                                session.launchOn {
                                    stageSubtitleStyle(
                                        subtitleStyle.copy(
                                            bold = !subtitleStyle.bold,
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
                    item(key = "style-reset") {
                        PlayerMenuRow("Reset to default", selected = false, onClick = {
                            session.launchOn { stageSubtitleStyle(ReceiverSubtitleStyle().toParams()) }
                        })
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
                    VideoResizeMode.entries,
                    key = { _, mode -> "aspect:${mode.name}" },
                ) { index, mode ->
                    PlayerMenuRow(
                        label = pictureSizeLabel(mode),
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
                        if (onMultiview != null) item(key = "more-live-multiview") {
                            PlayerMenuRow("Open in Multiview", selected = false, onClick = onMultiview)
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
                            label = playerMenuLabel("Picture size", pictureSizeLabel(resizeMode)),
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
        val returnToAppearance = panel == PlayerPanel.Subtitles && previousPanel == PlayerPanel.SubtitleStyle
        previousPanel = panel
        if (returnToAppearance) {
            withFrameNanos { }
            runCatching { appearanceFocus.requestFocus() }
        } else {
            listState.scrollToItem(entryIndex)
            withFrameNanos { }
            runCatching { entryRow.requestFocus() }
        }
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
    return full
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

/** Shared by every route that prepares a new video. No modal card or placeholder title. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun PlaybackLoadingSurface(
    art: NowPlayingArt?, title: String?, modifier: Modifier = Modifier, onCancel: (() -> Unit)? = null,
    request: com.fourseveneightnine.tv.client.playback.PlayRequest? = null,
) {
    val context = LocalContext.current
    val pip = context.appGraph.pictureInPicture
    val coverModifier = modifier.benchmarkDraw("playback_cover")
    if (pip.channel != null) {
        // Keep the live plane visible while the on-demand decoder reopens. Full-window
        // artwork would cover a match that is already rendering on its new surface.
        androidx.compose.foundation.layout.BoxWithConstraints(coverModifier.fillMaxSize()) {
            val corner = when (pip.corner) {
                1 -> Alignment.TopStart
                2 -> Alignment.BottomStart
                3 -> Alignment.BottomEnd
                else -> Alignment.TopEnd
            }
            val placement = if (pip.liveLarge) Modifier.align(corner).padding(maxWidth / 32f)
                .width(maxWidth * pip.size.widthFraction).aspectRatio(16f / 9f)
            else Modifier.align(Alignment.Center)
            Column(placement.background(Color.Black).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text(title?.let { "Opening $it" } ?: "Opening video", style = TvType.Meta,
                    color = TvColor.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (onCancel != null) com.fourseveneightnine.tv.client.ui.components.TvButton("Cancel", onClick = onCancel)
            }
        }
        return
    }
    var metadataArt by remember(request?.type, request?.id) { mutableStateOf<NowPlayingArt?>(null) }
    // A memory-only peek misses cold starts and saved releases. Resolve this title while video
    // preparation continues, and discard the lookup when the incoming title changes.
    // A Watchlist card must resolve its recipe to a catalog ID before metadata lookup.
    if (request != null && !request.id.startsWith(WatchlistSource.PREFIX) && art?.logoURL.isNullOrBlank()) {
        LaunchedEffect(request.type, request.id) {
            val client = context.clientGraph
            val saved = client.savedCatalogItem(request.type, request.id)
            val id = (saved?.imdbId ?: saved?.tmdbId?.let { "tmdb:$it" } ?: request.id).substringAfter("::")
            if (id.startsWith("saved:")) return@LaunchedEffect
            val services = client.services.first { it != null } ?: return@LaunchedEffect
            val meta = services.meta.meta(request.type, id, onBase = {
                metadataArt = NowPlayingArt(it.backdrop, it.poster, it.logo)
            })
            if (meta != null) metadataArt = NowPlayingArt(meta.backdrop, meta.poster, meta.logo)
        }
    }
    val loadingArt = NowPlayingArt(art?.landscapeURL ?: metadataArt?.landscapeURL,
        art?.posterURL ?: metadataArt?.posterURL, art?.logoURL ?: metadataArt?.logoURL)
    val logoURL = NowPlayingArt.sanitize(art?.logoURL) ?: NowPlayingArt.sanitize(metadataArt?.logoURL)
    val reduceMotion = LocalReduceMotion.current
    val focus = remember { FocusRequester() }
    if (onCancel != null) {
        BackHandler(onBack = onCancel)
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }
    val focusModifier = if (onCancel == null) Modifier else Modifier.focusRequester(focus)
        .focusProperties {
            exit = { direction ->
                if (direction == FocusDirection.Enter || direction == FocusDirection.Exit)
                    FocusRequester.Default else FocusRequester.Cancel
            }
        }.focusable()
    // Activity feedback must survive Android's animation scale 0. A shared monotonic phase
    // also keeps Detail, shell and Player replacements from restarting at full brightness.
    val pulse = produceState(if (reduceMotion) 1f else loadingLogoOpacity(System.nanoTime()), reduceMotion) {
        value = if (reduceMotion) 1f else loadingLogoOpacity(System.nanoTime())
        if (reduceMotion) return@produceState
        while (true) {
            withInfiniteAnimationFrameNanos { value = loadingLogoOpacity(it) }
        }
    }
    Box(coverModifier.fillMaxSize().then(focusModifier).background(TvColor.Canvas).semantics(mergeDescendants = true) {
        paneTitle = "Loading player"
        stateDescription = "Loading video"
        contentDescription = title ?: "Video"
        liveRegion = LiveRegionMode.Polite
    }, contentAlignment = Alignment.Center) {
        loadingArt.best?.let { url ->
            AsyncImage(
                model = remember(context, url) {
                    PosterRequest(url, 1920).toImageRequest(context).newBuilder().crossfade(false).build()
                },
                contentDescription = null,
                contentScale = if (loadingArt.landscapeURL != null) ContentScale.Crop else ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.48f },
            )
        }
        key(title, logoURL) {
            var logoFailed by remember { mutableStateOf(false) }
            Box(Modifier.size(850.dp, 260.dp).graphicsLayer { alpha = pulse.value },
                contentAlignment = Alignment.Center) {
                // A new route can draw its cached logo immediately. Do not insert the plain
                // title for one frame while an asynchronous success callback catches up.
                if (logoURL == null || logoFailed) {
                    if (!title.isNullOrBlank()) Text(title, style = TvType.HeroTitle,
                        color = TvColor.TextPrimary, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    else Image(painterResource(R.drawable.brand_mark), contentDescription = null,
                        modifier = Modifier.size(220.dp))
                }
                logoURL?.let { url ->
                    AsyncImage(
                        model = remember(context, url) {
                            ImageRequest.Builder(context).data(TmdbSize.logo(url, 850)).tmdbLogo()
                                .size(850, 260).crossfade(false).build()
                        },
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        onSuccess = { logoFailed = false },
                        onError = { logoFailed = true },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

private fun loadingLogoOpacity(frameTimeNanos: Long): Float {
    val halfCycle = 2_000_000_000L
    val elapsed = frameTimeNanos % (halfCycle * 2)
    val progress = (if (elapsed <= halfCycle) elapsed else halfCycle * 2 - elapsed).toFloat() / halfCycle
    return 1f - 0.45f * FastOutSlowInEasing.transform(progress)
}

/** The comet fades over the retained video frame after a sustained buffer. */
@Composable
private fun BufferingOverlay(buffering: Boolean, modifier: Modifier) {
    val visibility = remember { MutableTransitionState(false) }
    val reduceMotion = LocalReduceMotion.current
    LaunchedEffect(buffering) {
        if (buffering && visibility.isIdle) delay(TvMotion.BufferingEnterMillis)
        visibility.targetState = buffering
    }
    AnimatedVisibility(
        visibleState = visibility,
        modifier = modifier,
        enter = if (reduceMotion) EnterTransition.None else fadeIn(tween(TvMotion.PanelSlideMillis, easing = TvMotion.Out)),
        exit = if (reduceMotion) ExitTransition.None else fadeOut(tween(TvMotion.PanelSlideMillis, easing = TvMotion.Out)),
    ) {
        val angle = if (reduceMotion) null else rememberInfiniteTransition(label = "buffering").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1_200, easing = LinearEasing), RepeatMode.Restart),
            label = "comet",
        )
        val color = TvColor.InProgress
        val trail = remember(color) {
            Brush.sweepGradient(
                0f to color.copy(alpha = 0f),
                0.30f to color.copy(alpha = 0.18f),
                0.56f to color.copy(alpha = 0.64f),
                0.72f to color,
                0.80f to color,
                1f to color.copy(alpha = 0f),
            )
        }
        Canvas(
            Modifier.size(80.dp).graphicsLayer { rotationZ = angle?.value ?: 0f }.semantics {
                contentDescription = "Buffering"
                liveRegion = LiveRegionMode.Polite
            },
        ) {
            val width = 10.dp.toPx()
            val inset = width / 2f
            drawArc(
                brush = trail,
                startAngle = 0f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - width, size.height - width),
                style = Stroke(width = width, cap = StrokeCap.Round),
            )
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
    autoAdvance: Boolean,
    initialSeconds: Int,
    busy: Boolean,
    preparation: String?,
    failure: String?,
    onSources: (() -> Unit)?,
    onNext: () -> Unit,
    onReplay: () -> Unit,
    onDone: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    var cancelled by remember(plan.canonicalId, plan.season, plan.episode, autoAdvance) { mutableStateOf(!autoAdvance) }
    var remaining by remember(plan.canonicalId, plan.season, plan.episode) { mutableStateOf(initialSeconds) }

    LaunchedEffect(plan.canonicalId, plan.season, plan.episode, cancelled, busy, failure, autoAdvance) {
        if (!autoAdvance || cancelled || busy || failure != null) return@LaunchedEffect
        while (remaining > 0) {
            delay(1_000L)
            if (cancelled) return@LaunchedEffect
            remaining -= 1
        }
        onNext()
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        EpisodePrompt(plan, modifier = Modifier.onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown) cancelled = true
            false
        }, status = if (busy) preparation ?: "Preparing next episode" else "Up next",
            seconds = remaining.takeIf { autoAdvance && !cancelled && !busy && failure == null },
            message = failure) {
            EpisodeAction(if (failure == null) "Play now" else "Try again", onNext,
                Modifier.focusRequester(focus), primary = true,
                icon = if (failure == null) R.drawable.ic_player_play else R.drawable.ic_player_last)
            if (failure != null && onSources != null) EpisodeAction("Sources", onSources, icon = R.drawable.ic_player_layers)
            EpisodeAction("Replay", { cancelled = true; onReplay() }, icon = R.drawable.ic_player_last)
            EpisodeAction("Done", onDone)
        }
    }
    LaunchedEffect(plan.canonicalId, plan.season, plan.episode) { runCatching { focus.requestFocus() } }
}

private const val NEXT_UP_SECONDS = 10
private const val LIVE_TUNE_SETTLE_MILLIS = 500L

@Composable
private fun EndedPlate(
    episode: com.fourseveneightnine.tv.client.playback.PlayRequest?,
    title: String?,
    nextTitle: String?,
    nextSubtitle: String?,
    onReplay: () -> Unit,
    onNext: (() -> Unit)?,
    onDone: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    if (episode != null) {
        Box(Modifier.fillMaxSize(), Alignment.BottomEnd) {
            PlayerGlassCard(episode.title, episodeCoordinates(episode.season, episode.episode), "Finished",
                image = episode.backdropUrl ?: episode.posterUrl, portrait = episode.backdropUrl == null) {
                EpisodeAction("Done", onDone, Modifier.focusRequester(focus), primary = true)
                EpisodeAction("Replay", onReplay, icon = R.drawable.ic_player_last)
            }
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
        return
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(TvColor.Canvas.copy(alpha = 0.94f))
            .semantics {
                paneTitle = "Playback finished"
                isTraversalGroup = true
                liveRegion = LiveRegionMode.Polite
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
 * The taxonomy sentence, never "the cast failed" and never "connection lost" (CONTEXT §10).
 * With no player installed the rescue buttons are missing, not disabled.
 */
@Composable
private fun ErrorPlate(
    episode: com.fourseveneightnine.tv.client.playback.PlayRequest?,
    message: String,
    title: String,
    onSources: (() -> Unit)?,
    preferSources: Boolean,
    onGuide: (() -> Unit)?,
    players: List<InstalledExternalPlayer>,
    retrying: Boolean,
    onRetry: () -> Unit,
    onHandoff: (InstalledExternalPlayer) -> Unit,
    onDone: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    if (episode != null) {
        Box(Modifier.fillMaxSize().background(TvColor.Canvas), contentAlignment = Alignment.BottomEnd) {
            PlayerGlassCard(episode.title, episodeCoordinates(episode.season, episode.episode), "Playback interrupted",
                image = episode.backdropUrl ?: episode.posterUrl, portrait = episode.backdropUrl == null,
                message = message.ifBlank { "This episode could not start." }) {
                if (preferSources && onSources != null) EpisodeAction("More sources", onSources,
                    Modifier.focusRequester(focus), primary = true, icon = R.drawable.ic_player_layers)
                EpisodeAction("Try again", onRetry, if (preferSources && onSources != null) Modifier else Modifier.focusRequester(focus),
                    primary = !preferSources || onSources == null, icon = R.drawable.ic_player_last)
                if (!preferSources && onSources != null) EpisodeAction("More sources", onSources, icon = R.drawable.ic_player_layers)
                players.firstOrNull()?.let { player -> EpisodeAction("Open in ${player.label}", { onHandoff(player) }) }
                EpisodeAction("Done", onDone)
            }
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
        return
    }
    Box(Modifier.fillMaxSize().background(TvColor.Canvas), Alignment.Center) {
        Column(
            modifier = Modifier
                .width(1000.dp)
                .clip(TvShape.Panel)
                .background(TvColor.Elevated)
                .border(1.dp, TvColor.Border, TvShape.Panel)
                .padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = TvType.CardTitle, color = TvColor.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(TvSpace.S))
            Text(
                text = if (retrying) "Reconnecting to the source…" else message.ifBlank { "This stream could not start" },
                style = TvType.ScreenTitle,
                color = TvColor.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(TvSpace.S))
            Text(
                when { onGuide != null -> "Retry this channel or choose another in Guide."
                    onSources != null -> "Retry this source or choose another source."
                    players.isNotEmpty() -> "Try again, or open it in another app."
                    else -> "Try again or return to browsing." },
                style = TvType.Body,
                color = TvColor.TextSecondary,
            )
            Spacer(Modifier.height(TvSpace.M))
            Row(horizontalArrangement = Arrangement.spacedBy(TvSpace.S)) {
                val sourcesFirst = preferSources && onSources != null
                if (sourcesFirst) {
                    TvButton("More sources", requireNotNull(onSources), kind = ButtonKind.Primary,
                        modifier = Modifier.focusRequester(focus))
                }
                TvButton(if (retrying) "Reconnecting…" else "Try again", onRetry, enabled = !retrying,
                    kind = if (sourcesFirst) ButtonKind.Secondary else ButtonKind.Primary,
                    modifier = if (sourcesFirst) Modifier else Modifier.focusRequester(focus))
                if (!sourcesFirst) onSources?.let { TvButton("More sources", it, kind = ButtonKind.Secondary) }
                onGuide?.let { TvButton("Guide", it, kind = ButtonKind.Secondary) }
                players.take(1).forEach { player ->
                    TvButton("Open in ${player.label}", { onHandoff(player) }, kind = ButtonKind.Secondary)
                }
                TvButton("Done", onDone, kind = ButtonKind.Ghost, modifier = Modifier.width(140.dp))
            }
        }
    }
    LaunchedEffect(retrying) { if (!retrying) runCatching { focus.requestFocus() } }
}

private const val STOP_SETTLE_MILLIS = 400L

// ---------------------------------------------------------------------------- helpers

/** Fire-and-forget on the session's own scope; a key press never waits on the player thread. */
private fun PlaybackSession.launchOn(block: suspend PlaybackSession.() -> Unit) {
    uiScope.launch { block() }
}

private fun readableTrackLabel(language: String?, name: String?, codec: String?, channels: Int?, fallback: String): String {
    val normalized = canonicalTrackLanguage(language)
    val spoken = normalized?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()) }
        ?.takeIf { it.isNotBlank() && !it.equals("und", true) }
    val details = PlayerControlsPolicy.trackLabel("", name.orEmpty().takeUnless { it.equals(language, true) }.orEmpty(), codec, channels, "")
    return listOfNotNull(spoken, details.takeIf(String::isNotBlank)).distinct().joinToString(" · ").ifBlank { fallback }
}
