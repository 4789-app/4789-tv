package com.fourseveneightnine.tv.client.ui.screens.live

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.Saver
import kotlinx.serialization.json.Json
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.client.ui.screens.search.RailEdge
import com.fourseveneightnine.tv.client.appGraph
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.iptv.IptvChannel
import com.fourseveneightnine.tv.client.iptv.iptvPlaybackHeaders
import com.fourseveneightnine.tv.client.iptv.recentChannels
import com.fourseveneightnine.tv.client.iptv.IptvProgram
import com.fourseveneightnine.tv.client.iptv.IptvRecording
import com.fourseveneightnine.tv.client.iptv.IptvRecordingStatus
import com.fourseveneightnine.tv.client.iptv.IptvVod
import com.fourseveneightnine.tv.client.playback.PlaybackSession
import com.fourseveneightnine.tv.player.ReceiverPlaybackPhase
import com.fourseveneightnine.tv.client.ui.LocalShellState
import com.fourseveneightnine.tv.client.ui.components.ContentTransition
import com.fourseveneightnine.tv.client.ui.components.PanelTransition
import com.fourseveneightnine.tv.client.ui.components.TvButton
import com.fourseveneightnine.tv.client.ui.components.TvFocusableCard
import com.fourseveneightnine.tv.client.ui.nav.ClientNav
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvGeom
import com.fourseveneightnine.tv.client.ui.theme.TvType
import com.fourseveneightnine.tv.protocol.NowPlayingArtwork
import com.fourseveneightnine.tv.protocol.OpenMediaRequest
import android.net.Uri
import android.util.Log
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

internal enum class LiveTab(val label: String) {
    Favorites("Favorites"), Recent("Recents"), Guide("Guide"), Channels("Channels"), Movies("Movies"),
    Series("Series"), Recordings("Recordings"), Sources("Sources"),
}

private data class LockedAction(val group: String, val run: () -> Unit)

/** The TV-owned IPTV door always opens on Favorites, including when the list is empty. */
@Composable
internal fun LiveTvScreen(nav: ClientNav) {
    val context = LocalContext.current
    val graph = remember(context) { context.appGraph }
    val repo = remember(context) { context.clientGraph.iptv }
    val state by repo.state.collectAsState()
    val launchTarget by LiveTvLaunch.target.collectAsState()
    val scope = rememberCoroutineScope()
    // Playback outlives this browse screen: opening the player removes LiveTvScreen from
    // composition, which cancels rememberCoroutineScope while first-frame work is still running.
    val playbackScope = graph.playback.uiScope
    val shell = LocalShellState.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
    val tabFocus = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }
    var firstPlacement by remember { mutableStateOf(true) }
    var liveOpenJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var focusedPreview by remember { mutableStateOf<IptvChannel?>(null) }
    val liveOpenGeneration = remember { longArrayOf(0L) }
    var liveOpenHandedOff by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose {
            if (!liveOpenHandedOff) {
                liveOpenGeneration[0]++
                liveOpenJob?.cancel()
            }
        }
    }
    var tabsHaveFocus by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(LiveTab.Favorites) }
    var addSource by remember { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf(false) }
    val searchState = rememberSaveableStateHolder()
    val searchButtonFocus = remember { FocusRequester() }
    var channelMenu by remember { mutableStateOf<IptvChannel?>(null) }
    var sourceMenu by remember { mutableStateOf<String?>(null) }
    var editParentSourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingSourceId by remember { mutableStateOf<String?>(null) }
    var seriesMenu by rememberSaveable(stateSaver = Saver<IptvVod?, String>(
        save = { item -> item?.let { Json.encodeToString(it) } },
        restore = { saved -> Json.decodeFromString<IptvVod>(saved) },
    )) { mutableStateOf<IptvVod?>(null) }
    var recordingMenu by remember { mutableStateOf<IptvRecording?>(null) }
    var selectedGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedVodGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var groupPicker by remember { mutableStateOf(false) }
    var parentalPanel by remember { mutableStateOf(false) }
    var decisionKeyPanel by remember { mutableStateOf(false) }
    var backupPanel by remember { mutableStateOf(false) }
    var manageGroupsPanel by remember { mutableStateOf(false) }
    var favoriteListsPanel by remember { mutableStateOf(false) }
    var selectedFavoriteList by rememberSaveable { mutableStateOf<String?>(null) }
    var unlockedGroups by remember { mutableStateOf(emptySet<String>()) }
    var pendingUnlock by remember { mutableStateOf<LockedAction?>(null) }

    fun vodGroup(item: IptvVod): String? {
        if (item.kind != "episode") return item.category
        val parentId = item.seriesId ?: item.category
        val parent = seriesMenu?.takeIf { it.sourceId == item.sourceId &&
            (it.id == parentId || item.seriesId == null && it.title == item.category) }
            ?: state.catalog.vod.firstOrNull { it.kind == "series" && it.sourceId == item.sourceId &&
                it.id == parentId }
        return parent?.category
    }

    fun canAccessVod(item: IptvVod, unlocked: Set<String> = unlockedGroups): Boolean {
        val group = vodGroup(item) ?: return false
        return state.sources.any { it.id == item.sourceId && it.enabled } &&
            group !in state.accounts.hiddenGroups && (group !in state.accounts.lockedGroups || group in unlocked)
    }

    val hasOverlay = addSource || channelMenu != null || sourceMenu != null || editingSourceId != null ||
        seriesMenu != null || recordingMenu != null || groupPicker || parentalPanel || decisionKeyPanel ||
        backupPanel || manageGroupsPanel || favoriteListsPanel || pendingUnlock != null
    DisposableEffect(hasOverlay) {
        shell.overlayVisible = hasOverlay
        // This route can outlive the incoming player's first frame during navigation.
        onDispose { if (!shell.playerVisible) shell.overlayVisible = false }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                if (seriesMenu?.let { !canAccessVod(it, emptySet()) } == true) seriesMenu = null
                if (selectedVodGroup?.let { it in state.accounts.lockedGroups || it in state.accounts.hiddenGroups } == true)
                    selectedVodGroup = null
                pendingUnlock = null
                unlockedGroups = emptySet()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun withAccess(group: String, action: () -> Unit) {
        if (group in state.accounts.hiddenGroups) return
        if (group in state.accounts.lockedGroups && group !in unlockedGroups) {
            pendingUnlock = LockedAction(group, action)
        } else action()
    }

    DisposableEffect(Unit) {
        val restore = {
            runCatching { (if (search) searchButtonFocus else tabFocus).requestFocus() }
            Unit
        }
        shell.restoreContentFocus = restore
        onDispose { if (shell.restoreContentFocus === restore) shell.restoreContentFocus = null }
    }
    LaunchedEffect(Unit) { runCatching { tabFocus.requestFocus() } }
    // Channel groups select on focus. Refocusing their container after each selection enters
    // its first row (All channels), immediately undoing the viewer's category change.
    LaunchedEffect(tab, selectedVodGroup) {
        if (firstPlacement) { firstPlacement = false; return@LaunchedEffect }
        kotlinx.coroutines.delay(40)
        if (addSource || search || channelMenu != null || sourceMenu != null || editingSourceId != null ||
            seriesMenu != null || recordingMenu != null ||
            groupPicker || parentalPanel || decisionKeyPanel || backupPanel || manageGroupsPanel || favoriteListsPanel ||
            pendingUnlock != null) return@LaunchedEffect
        runCatching { contentFocus.requestFocus() }
    }
    LaunchedEffect(state.catalog.vod.isNotEmpty(), state.catalog.vodCategories.isNotEmpty()) {
        if ((state.catalog.vod.isEmpty() && state.catalog.vodCategories.isEmpty()) ||
            tab !in setOf(LiveTab.Movies, LiveTab.Series)) return@LaunchedEffect
        kotlinx.coroutines.delay(40)
        if (tabsHaveFocus && !addSource && !search && seriesMenu == null) {
            runCatching { contentFocus.requestFocus() }
        }
    }

    fun play(channel: IptvChannel, focusPreview: Boolean = false, resume: Boolean = false) {
        if (focusPreview && (graph.pictureInPicture.channel != null || graph.pictureInPicture.movieInCorner)) return
        if (!focusPreview) focusedPreview = null
        if (channel.group in state.accounts.lockedGroups && channel.group !in unlockedGroups) {
            pendingUnlock = LockedAction(channel.group) { play(channel) }
            return
        }
        if (graph.pictureInPicture.movieInCorner) {
            if (!resume) graph.pictureInPicture.pin(channel, fromLive = false) { nav.openPlayer() }
            return
        }
        val browseGroup = when (tab) {
            LiveTab.Channels -> selectedGroup?.takeUnless { it == ALL_CHANNELS }
            LiveTab.Favorites, LiveTab.Recent -> null
            else -> if (resume) null else channel.group
        }
        val allowed = state.channels.filter { it.group !in state.accounts.hiddenGroups &&
            (it.group !in state.accounts.lockedGroups || it.group in unlockedGroups) }
        val favoriteIds = if (tab == LiveTab.Favorites) selectedFavoriteList?.let {
            state.accounts.favoriteLists[it]
        } ?: state.accounts.favoriteIds else null
        val recents = if (tab == LiveTab.Recent || resume) state.recentChannels(unlockedGroups) else emptyList()
        val ordered = if (recents.isNotEmpty()) recents else
            LiveChannelOrder.category(allowed.filter { favoriteIds == null || it.id in favoriteIds }, emptyList(), browseGroup)
        val browse = PlaybackSession.LiveChannel(channel.id, channel.sourceId, channel.group,
            browseGroup, ordered.map { it.id },
            when {
                recents.isNotEmpty() -> "Recent channels"
                tab == LiveTab.Favorites -> selectedFavoriteList ?: "Favorites"
                else -> browseGroup ?: "All channels"
            })
        val generation = ++liveOpenGeneration[0]
        liveOpenHandedOff = false
        val priorRequest = graph.playback.lastOpenMedia()
        val playbackGeneration = graph.playback.generation
        liveOpenJob?.cancel()
        ReceiverDiagnostics.record("iptv.tune.requested", if (focusPreview) "mode=focus" else "mode=selection")
        liveOpenJob = playbackScope.launch {
            var stage = "resolve"
            val result = runCatching {
                val url = repo.playUrl(channel)
                coroutineContext.ensureActive()
                if (focusPreview && focusedPreview != channel) return@launch
                if (generation != liveOpenGeneration[0] || graph.playback.generation != playbackGeneration || graph.playback.lastOpenMedia() != priorRequest) return@launch
                NowPlayingArtwork.stage(landscapeURL = null, posterURL = null)
                context.clientGraph.playFlow.clearCurrent()
                LiveTvLaunch.markPlayback(preview = !resume)
                stage = "open"
                // Native ownership emits the player navigation event synchronously before suspending.
                liveOpenHandedOff = true
                graph.playback.openLiveChannel(OpenMediaRequest(url = url, title = channel.name,
                    isLive = true, headers = iptvPlaybackHeaders(channel.headers)),
                    browse).getOrThrow()
                stage = "first-frame"
                when (graph.playback.awaitLiveOutcome()) {
                    is ReceiverPlaybackPhase.Playing -> Unit
                    null -> nav.toast("Still connecting to ${channel.name}…")
                    else -> Unit // The player shows the actual source error and its retry action.
                }
            }
            result.exceptionOrNull()?.let {
                if (it is CancellationException) throw it
                if (focusPreview && !liveOpenHandedOff && focusedPreview != channel) return@launch
                Log.e("4789IptvPlay", "kind=live stage=$stage error=${it.javaClass.simpleName}")
                ReceiverDiagnostics.record("iptv.play.failed", "kind=live stage=$stage type=${it.javaClass.simpleName}")
                if (generation != liveOpenGeneration[0] || graph.playback.generation !=
                    playbackGeneration + if (liveOpenHandedOff) 1 else 0) return@launch
                if (graph.playback.liveChannel.value?.id != channel.id) LiveTvLaunch.clearPlayback()
                nav.toast(it.message?.take(100) ?: "Couldn't play this channel")
            }
        }
    }

    LaunchedEffect(tab, selectedGroup, hasOverlay, search, state.loading) {
        if (tab != LiveTab.Channels || hasOverlay || search || state.loading) return@LaunchedEffect
        snapshotFlow { focusedPreview }.collectLatest { channel ->
            if (channel == null || shell.playerVisible) return@collectLatest
            if (selectedGroup != null && selectedGroup != ALL_CHANNELS && channel.group != selectedGroup)
                return@collectLatest
            if (focusedPreview != channel || liveOpenJob?.isActive == true) return@collectLatest
            play(channel, focusPreview = true)
            val job = liveOpenJob
            try {
                job?.join()
            } finally {
                if (liveOpenJob === job && !liveOpenHandedOff) job?.cancel()
            }
        }
    }

    fun favorite(channel: IptvChannel) {
        scope.launch {
            val list = selectedFavoriteList.takeIf { tab == LiveTab.Favorites }
            nav.toast(when (if (list == null) repo.toggleFavorite(channel.id) else repo.toggleListFavorite(list, channel.id)) {
                true -> "Added ${channel.name} to ${list ?: "Favorites"}"
                false -> "Removed ${channel.name} from ${list ?: "Favorites"}"
                null -> "Couldn't save Favorite"
            })
        }
    }

    fun playVod(item: IptvVod) {
        val group = vodGroup(item) ?: return
        if (state.sources.none { it.id == item.sourceId && it.enabled }) return
        withAccess(group) {
            if (!canAccessVod(item)) return@withAccess
            if (item.kind == "series") seriesMenu = item
            else {
                val generation = ++liveOpenGeneration[0]
                liveOpenJob?.cancel()
                liveOpenHandedOff = false
                val priorRequest = graph.playback.lastOpenMedia()
                val playbackGeneration = graph.playback.generation
                liveOpenJob = playbackScope.launch {
                    var stage = "resolve"
                    val result = runCatching {
                        val url = repo.vodUrl(item) ?: error("No playable video")
                        coroutineContext.ensureActive()
                        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || !canAccessVod(item)) return@launch
                        if (generation != liveOpenGeneration[0] || graph.playback.generation != playbackGeneration || graph.playback.lastOpenMedia() != priorRequest) return@launch
                        NowPlayingArtwork.stage(landscapeURL = null, posterURL = item.image, owner = item.title)
                        context.clientGraph.playFlow.clearCurrent()
                        LiveTvLaunch.markPlayback()
                        stage = "open"
                        liveOpenHandedOff = true
                        graph.playback.openIptvVod(OpenMediaRequest(url = url, title = item.title,
                            headers = iptvPlaybackHeaders(item.headers)), item).getOrThrow()
                    }
                    result.exceptionOrNull()?.let { error ->
                        if (error is CancellationException) {
                            throw error
                        }
                        Log.e("4789IptvPlay", "kind=vod stage=$stage error=${error.javaClass.simpleName}")
                        ReceiverDiagnostics.record("iptv.play.failed", "kind=vod stage=$stage type=${error.javaClass.simpleName}")
                        if (generation != liveOpenGeneration[0] || graph.playback.generation !=
                            playbackGeneration + if (liveOpenHandedOff) 1 else 0) return@launch
                        LiveTvLaunch.clearPlayback()
                        nav.toast(error.message?.take(100) ?: "Couldn't play this video")
                    }
                }
            }
        }
    }

    fun playRecording(item: IptvRecording) {
        if (item.status != IptvRecordingStatus.DONE) { nav.toast("This recording is not ready yet."); return }
        val file = repo.recordingFile(item) ?: run { nav.toast("Recording file is missing."); return }
        playbackScope.launch {
            context.clientGraph.playFlow.clearCurrent()
            LiveTvLaunch.markPlayback()
            val result = graph.playback.open(OpenMediaRequest(url = Uri.fromFile(file).toString(), title = item.title),
                PlaybackSession.Origin.Local)
            result.exceptionOrNull()?.let {
                if (it is CancellationException) throw it
                Log.e("4789IptvPlay", "kind=recording stage=open error=${it.javaClass.simpleName}")
                ReceiverDiagnostics.record("iptv.play.failed", "kind=recording stage=open type=${it.javaClass.simpleName}")
                LiveTvLaunch.clearPlayback()
                nav.toast("Couldn't play recording.")
            }
        }
    }

    fun chooseProgram(channel: IptvChannel, program: IptvProgram) {
        if (channel.group in state.accounts.lockedGroups && channel.group !in unlockedGroups) {
            pendingUnlock = LockedAction(channel.group) { chooseProgram(channel, program) }
            return
        }
        val now = System.currentTimeMillis()
        when {
            program.startMillis > now -> {
                val source = state.sources.firstOrNull { it.id == channel.sourceId }
                if ((source?.maxConnections ?: 0) < 2) nav.toast("Recording is unavailable for this source.")
                else scope.launch {
                    nav.toast(if (repo.scheduleRecording(channel, program)) "Recording scheduled on this TV."
                        else "Couldn't schedule this recording.")
                }
            }
            program.endMillis > now -> { channelMenu = null; play(channel) }
            else -> {
                val url = repo.catchupUrl(channel, program)
                if (url == null) nav.toast("Catch-up is unavailable for this program.")
                else playbackScope.launch {
                    channelMenu = null
                    context.clientGraph.playFlow.clearCurrent()
                    LiveTvLaunch.markPlayback()
                    val result = graph.playback.open(OpenMediaRequest(url = url, title = program.title,
                        headers = channel.headers), PlaybackSession.Origin.Local)
                    result.exceptionOrNull()?.let { error ->
                        if (error is CancellationException) {
                            throw error
                        }
                        Log.e("4789IptvPlay", "kind=catchup stage=open error=${error.javaClass.simpleName}")
                        ReceiverDiagnostics.record("iptv.play.failed", "kind=catchup stage=open type=${error.javaClass.simpleName}")
                        LiveTvLaunch.clearPlayback()
                        nav.toast("Couldn't open catch-up playback.")
                    }
                }
            }
        }
    }

    LaunchedEffect(launchTarget, lifecycleState, state.catalog.channels, state.catalog.vod, state.loading, state.catalogLoading, state.refreshing) {
        val target = launchTarget ?: return@LaunchedEffect
        when (target.kind) {
            LiveTvLaunch.SOURCE_SETUP -> {
                tab = LiveTab.Sources
                addSource = true
                LiveTvLaunch.consume(target)
            }
            "guide" -> {
                tab = LiveTab.Guide
                LiveTvLaunch.consume(target)
            }
            "resume-channel" -> {
                tab = LiveTab.Recent
                if (state.loading || lifecycleState != Lifecycle.State.RESUMED) return@LaunchedEffect
                val channel = state.channels.firstOrNull { it.id == target.id }
                if (channel == null && (state.catalogLoading || state.refreshing)) return@LaunchedEffect
                LiveTvLaunch.consume(target)
                if (channel != null && channel.group !in state.accounts.hiddenGroups &&
                    channel.group !in state.accounts.lockedGroups && graph.playback.origin.value == null) {
                    play(channel, resume = true)
                }
            }
            "channel-group" -> {
                withAccess(target.id) { selectedGroup = target.id; tab = LiveTab.Channels }
                LiveTvLaunch.consume(target)
            }
            "movie-group", "series-group" -> {
                val kind = if (target.kind == "movie-group") "movie" else "series"
                withAccess(target.id) {
                    selectedVodGroup = target.id
                    tab = if (kind == "movie") LiveTab.Movies else LiveTab.Series
                    scope.launch { repo.loadVodCategory(kind, target.id) }
                }
                LiveTvLaunch.consume(target)
            }
            "channel" -> state.channels.firstOrNull { it.id == target.id }?.let {
                val channel = it
                withAccess(channel.group) {
                    tab = LiveTab.Channels
                    selectedGroup = channel.group
                    channelMenu = channel
                }
                LiveTvLaunch.consume(target)
            }
            "vod" -> state.catalog.vod.firstOrNull { it.id == target.id }?.let {
                tab = if (it.kind == "series") LiveTab.Series else LiveTab.Movies
                LiveTvLaunch.consume(target)
                playVod(it)
            }
        }
    }

    fun closeSearch() {
        search = false
        searchState.removeState("live-search")
        scope.launch { androidx.compose.runtime.withFrameNanos { }; searchButtonFocus.requestFocus() }
    }

    fun closeSeries() {
        seriesMenu = null
        if (!search) scope.launch {
            androidx.compose.runtime.withFrameNanos { }
            if (seriesMenu == null && !search) runCatching { contentFocus.requestFocus() }
        }
    }

    BackHandler(enabled = addSource || search || channelMenu != null || sourceMenu != null || editingSourceId != null ||
        seriesMenu != null || recordingMenu != null || groupPicker || parentalPanel || decisionKeyPanel || backupPanel ||
        manageGroupsPanel || favoriteListsPanel || pendingUnlock != null) {
        when {
            addSource -> addSource = false
            editingSourceId != null -> { editingSourceId = null; sourceMenu = editParentSourceId; editParentSourceId = null }
            seriesMenu != null -> closeSeries()
            channelMenu != null -> channelMenu = null
            sourceMenu != null -> sourceMenu = null
            search -> closeSearch()
            recordingMenu != null -> recordingMenu = null
            groupPicker -> groupPicker = false
            parentalPanel -> parentalPanel = false
            decisionKeyPanel -> decisionKeyPanel = false
            backupPanel -> backupPanel = false
            manageGroupsPanel -> manageGroupsPanel = false
            favoriteListsPanel -> favoriteListsPanel = false
            pendingUnlock != null -> pendingUnlock = null
        }
    }
    BackHandler(enabled = ((tab == LiveTab.Channels && selectedGroup != null) ||
        (tab in setOf(LiveTab.Movies, LiveTab.Series) && selectedVodGroup != null)) && !addSource && !search && channelMenu == null &&
        sourceMenu == null && editingSourceId == null && seriesMenu == null && recordingMenu == null &&
        !groupPicker && !parentalPanel &&
        !decisionKeyPanel && !backupPanel && !manageGroupsPanel && !favoriteListsPanel && pendingUnlock == null) {
        if (tab == LiveTab.Channels) selectedGroup = null else selectedVodGroup = null
    }

    BackHandler(enabled = liveOpenJob?.isActive == true && !liveOpenHandedOff) {
        liveOpenGeneration[0]++
        liveOpenJob?.cancel()
        liveOpenJob = null
        nav.toast("Opening cancelled.")
    }

    RailEdge(onOpenRail = nav::openRail,
        modifier = Modifier.wrapContentSize(Alignment.TopStart).offset(x = TvGeom.ContentLeft - 2.dp))
    Column(Modifier.fillMaxSize().background(TvColor.Background)
        .onKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Menu) { nav.openRail(); true } else false
        }
        .padding(start = TvGeom.ContentLeft, end = 80.dp, top = TvGeom.SafeTop)) {
        Row(Modifier.onPreviewKeyEvent { event ->
            if (!search && !state.loading && event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                runCatching { contentFocus.requestFocus() }.getOrDefault(false)
            } else false
        }, verticalAlignment = Alignment.CenterVertically) {
            Text("Live TV", style = TvType.ScreenTitle, color = TvColor.TextPrimary)
            Spacer(Modifier.width(24.dp))
            Text(if (state.loading) "Opening saved library · ${state.sources.size} sources"
                else "${state.channels.size} channels · ${state.sources.size} sources", style = TvType.Meta,
                color = TvColor.TextSecondary)
            Spacer(Modifier.weight(1f))
            if (tab == LiveTab.Favorites) {
                TvButton(selectedFavoriteList ?: "Favorite lists", onClick = { favoriteListsPanel = true })
                Spacer(Modifier.width(12.dp))
            }
            TvButton(if (search) "Close search" else "Search",
                onClick = { if (search) closeSearch() else search = true },
                modifier = Modifier.focusRequester(searchButtonFocus))
            Spacer(Modifier.width(12.dp))
            if (tab == LiveTab.Sources) TvButton("Add source", onClick = { addSource = true })
            Spacer(Modifier.width(12.dp))
            TvButton(if (state.refreshing) "Refreshing…" else "Refresh",
                onClick = { scope.launch { repo.refresh() } }, enabled = !state.refreshing)
        }
        Spacer(Modifier.height(25.dp))
        if (search) {
            searchState.SaveableStateProvider("live-search") {
                LiveSearchContent(state, onClose = ::closeSearch, onChannel = { play(it) },
                    onFavorite = ::favorite,
                    onVod = ::playVod, unlockedGroups = unlockedGroups,
                    onChannelGroup = { group -> withAccess(group) { selectedGroup = group; tab = LiveTab.Channels } },
                    onVodGroup = { kind, category ->
                        withAccess(category) {
                            selectedVodGroup = category
                            tab = if (kind == "movie") LiveTab.Movies else LiveTab.Series
                        }
                    })
            }
            return@Column
        }
        Row(Modifier.focusRequester(tabFocus).focusRestorer().focusGroup()
            .onFocusChanged { tabsHaveFocus = it.hasFocus }
            .onPreviewKeyEvent { event ->
                if (!state.loading && event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                    runCatching { contentFocus.requestFocus() }.getOrDefault(false)
                } else false
            },
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LiveTab.entries.forEach { item ->
                TvFocusableCard(onClick = { if (tab != item) selectedVodGroup = null; tab = item }, selected = item == tab,
                    focusScale = 1f, focusRing = true) { focused ->
                    LiveTabLabel(item.label, active = item == tab, focused = focused)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        // Only explicit tab/category changes fade. Group focus remains immediate.
        ContentTransition(
            targetKey = Triple(tab, selectedVodGroup.takeIf { tab == LiveTab.Movies || tab == LiveTab.Series }, state.loading),
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(Modifier.fillMaxSize()) {
                if (state.loading) LiveLoadingHero() else LiveContent(tab, if (tab == LiveTab.Favorites && selectedFavoriteList != null)
                    state.copy(accounts = state.accounts.copy(favoriteIds = state.accounts.favoriteLists[selectedFavoriteList].orEmpty()))
                    else state, repo, selectedGroup,
                    selectedVodGroup, contentFocus, tabFocus, unlockedGroups,
                    onGroup = { groupPicker = true },
                    onGroupSelect = { group ->
                        if (group == null || group == ALL_CHANNELS) selectedGroup = group
                        else withAccess(group) { selectedGroup = group }
                    },
                    onTab = { if (tab != it) selectedVodGroup = null; tab = it },
                    onVodGroupSelect = { chosen ->
                        if (chosen == null || chosen == ALL_VOD) selectedVodGroup = chosen
                        else withAccess(chosen) {
                            selectedVodGroup = chosen
                            if (tab in setOf(LiveTab.Movies, LiveTab.Series)) {
                                val intentTab = tab
                                val kind = if (intentTab == LiveTab.Movies) "movie" else "series"
                                if (state.catalog.vod.none { it.kind == kind && it.category == chosen }) scope.launch {
                                    if (repo.loadVodCategory(kind, chosen)) {
                                        kotlinx.coroutines.delay(40)
                                        if (tab == intentTab && selectedVodGroup == chosen && !addSource && !search && channelMenu == null && sourceMenu == null &&
                                            editingSourceId == null && seriesMenu == null && recordingMenu == null && !groupPicker &&
                                            !parentalPanel && !decisionKeyPanel && !backupPanel && !manageGroupsPanel && !favoriteListsPanel && pendingUnlock == null)
                                            runCatching { contentFocus.requestFocus() }
                                    } else if (tab == intentTab && selectedVodGroup == chosen) nav.toast("Couldn't open this category. Try again shortly.")
                                }
                            }
                        }
                    },
                    onAdd = { addSource = true }, onChannel = { play(it) },
                    onChannelFocus = { channel, focused ->
                        if (focused && tab == LiveTab.Channels) focusedPreview = channel
                        else if (focusedPreview?.id == channel.id) focusedPreview = null
                    },
                    onChannelMenu = { channel -> withAccess(channel.group) { channelMenu = channel } },
                    onFavorite = ::favorite,
                    onGuideProgram = ::chooseProgram, onVod = ::playVod,
                    onRecording = { recordingMenu = it }, onSource = { sourceMenu = it },
                    onParental = { parentalPanel = true }, onDecisionKey = { decisionKeyPanel = true },
                    onBackup = { backupPanel = true }, onManageGroups = { manageGroupsPanel = true })
            }
        }
    }

    PanelTransition(visible = addSource) { LiveAddSourcePanel(repo, onClose = { addSource = false }, onMessage = nav::toast) }
    PanelTransition(value = channelMenu) { channel -> LiveChannelPanel(channel, state, repo, onClose = { channelMenu = null },
        onPlay = { channelMenu = null; play(it) },
        onProgram = { program -> chooseProgram(channel, program) }, onMessage = nav::toast,
        onMultiview = { channelMenu = null; nav.openMultiview(channel.id) }) }
    PanelTransition(value = sourceMenu) { id -> LiveSourceActions(id, state, repo, onClose = { sourceMenu = null },
        onEdit = { editParentSourceId = id; sourceMenu = null; editingSourceId = id }) }
    PanelTransition(value = editingSourceId?.let { id -> state.sources.firstOrNull { it.id == id } }) { source ->
        LiveEditSourcePanel(source, repo, onClose = { editingSourceId = null; sourceMenu = editParentSourceId; editParentSourceId = null }, onMessage = nav::toast)
    }
    PanelTransition(value = seriesMenu) { series ->
        if (canAccessVod(series)) LiveEpisodesPanel(series, repo, onClose = ::closeSeries,
            onPlay = { episode -> playVod(episode) })
    }
    PanelTransition(value = recordingMenu?.let { selected -> state.accounts.recordings.firstOrNull { it.id == selected.id } }) { item ->
        LiveRecordingPanel(item, repo, onClose = { recordingMenu = null },
            onPlay = { recordingMenu = null; playRecording(item) }, onMessage = nav::toast)
    }
    PanelTransition(visible = groupPicker) { LiveGroupPicker(state.channels.map(IptvChannel::group).distinct(), selectedGroup,
        onPick = { group ->
            groupPicker = false
            if (group == null || group == ALL_CHANNELS) selectedGroup = group
            else withAccess(group) { selectedGroup = group }
        }, onClose = { groupPicker = false }) }
    PanelTransition(visible = parentalPanel) { LiveParentalPanel(state, repo, onClose = { parentalPanel = false }, onMessage = nav::toast) }
    PanelTransition(visible = decisionKeyPanel) { LiveDecisionKeyPanel(repo, onClose = { decisionKeyPanel = false },
        onMessage = nav::toast) }
    PanelTransition(visible = backupPanel) { LiveBackupPanel(repo, onClose = { backupPanel = false }, onMessage = nav::toast) }
    PanelTransition(visible = favoriteListsPanel) { LiveFavoriteListsPanel(state, repo, selectedFavoriteList,
        onSelect = { selectedFavoriteList = it }, onClose = { favoriteListsPanel = false }, onMessage = nav::toast) }
    PanelTransition(visible = manageGroupsPanel) { LiveManageGroupsPanel(state, repo, onClose = { manageGroupsPanel = false },
        onMessage = nav::toast) }
    PanelTransition(value = pendingUnlock) { pending -> LivePinPanel(pending.group, repo,
        onClose = { pendingUnlock = null }, onUnlock = {
            unlockedGroups = unlockedGroups + pending.group
            pendingUnlock = null
            withAccess(pending.group, pending.run)
        }, onMessage = nav::toast) }
}
