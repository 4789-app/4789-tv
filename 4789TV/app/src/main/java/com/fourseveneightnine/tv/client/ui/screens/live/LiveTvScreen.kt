package com.fourseveneightnine.tv.client.ui.screens.live

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import kotlinx.serialization.json.Json
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.client.appGraph
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.iptv.IptvChannel
import com.fourseveneightnine.tv.client.iptv.iptvPlaybackHeaders
import com.fourseveneightnine.tv.client.iptv.IptvProgram
import com.fourseveneightnine.tv.client.iptv.IptvRecording
import com.fourseveneightnine.tv.client.iptv.IptvRecordingStatus
import com.fourseveneightnine.tv.client.iptv.IptvVod
import com.fourseveneightnine.tv.client.playback.PlaybackSession
import com.fourseveneightnine.tv.player.ReceiverPlaybackPhase
import com.fourseveneightnine.tv.client.ui.LocalShellState
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
import kotlinx.coroutines.launch

internal enum class LiveTab(val label: String) {
    Favorites("Favorites"), Guide("Guide"), Channels("Channels"), Movies("Movies"),
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
    val scope = rememberCoroutineScope()
    // Playback outlives this browse screen: opening the player removes LiveTvScreen from
    // composition, which cancels rememberCoroutineScope while first-frame work is still running.
    val playbackScope = graph.playback.uiScope
    val shell = LocalShellState.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val tabFocus = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }
    var firstPlacement by remember { mutableStateOf(true) }
    var tabsHaveFocus by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(LiveTab.Favorites) }
    var addSource by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf(false) }
    var channelMenu by remember { mutableStateOf<IptvChannel?>(null) }
    var sourceMenu by remember { mutableStateOf<String?>(null) }
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

    val hasOverlay = addSource || search || channelMenu != null || sourceMenu != null || editingSourceId != null ||
        seriesMenu != null || recordingMenu != null || groupPicker || parentalPanel || decisionKeyPanel ||
        backupPanel || manageGroupsPanel || favoriteListsPanel || pendingUnlock != null
    DisposableEffect(hasOverlay) {
        shell.overlayVisible = hasOverlay
        onDispose { shell.overlayVisible = false }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) unlockedGroups = emptySet()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun withAccess(group: String, action: () -> Unit) {
        if (group in state.accounts.lockedGroups && group !in unlockedGroups) {
            pendingUnlock = LockedAction(group, action)
        } else action()
    }

    DisposableEffect(Unit) {
        val restore = { runCatching { tabFocus.requestFocus() }; Unit }
        shell.restoreContentFocus = restore
        onDispose { if (shell.restoreContentFocus === restore) shell.restoreContentFocus = null }
    }
    LaunchedEffect(Unit) { runCatching { tabFocus.requestFocus() } }
    LaunchedEffect(tab, selectedGroup, selectedVodGroup) {
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

    fun play(channel: IptvChannel) {
        if (channel.group in state.accounts.lockedGroups && channel.group !in unlockedGroups) {
            pendingUnlock = LockedAction(channel.group) { play(channel) }
            return
        }
        playbackScope.launch {
            var stage = "resolve"
            val result = runCatching {
                val url = repo.playUrl(channel)
                NowPlayingArtwork.stage(landscapeURL = null, posterURL = null)
                context.clientGraph.playFlow.clearCurrent()
                LiveTvLaunch.markPlayback()
                stage = "open"
                graph.playback.openLiveChannel(OpenMediaRequest(url = url, title = channel.name,
                    isLive = true, headers = iptvPlaybackHeaders(channel.headers)),
                    PlaybackSession.LiveChannel(channel.id, channel.sourceId, channel.group)).getOrThrow()
                stage = "first-frame"
                when (graph.playback.awaitLiveOutcome()) {
                    is ReceiverPlaybackPhase.Playing -> if (graph.playback.liveChannel.value?.id == channel.id)
                        repo.markPlayed(channel.id)
                    null -> nav.toast("Still connecting to ${channel.name}…")
                    else -> Unit // The player shows the actual source error and its retry action.
                }
            }
            result.exceptionOrNull()?.let {
                if (it is CancellationException) throw it
                Log.e("4789IptvPlay", "kind=live stage=$stage error=${it.javaClass.simpleName}")
                ReceiverDiagnostics.record("iptv.play.failed", "kind=live stage=$stage type=${it.javaClass.simpleName}")
                LiveTvLaunch.clearPlayback()
                nav.toast(it.message?.take(100) ?: "Couldn't play this channel")
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
        if (item.kind == "series") { seriesMenu = item; return }
        playbackScope.launch {
            var stage = "resolve"
            val result = runCatching {
                val url = repo.vodUrl(item) ?: error("No playable video")
                NowPlayingArtwork.stage(landscapeURL = null, posterURL = item.image)
                context.clientGraph.playFlow.clearCurrent()
                LiveTvLaunch.markPlayback()
                stage = "open"
                graph.playback.openIptvVod(OpenMediaRequest(url = url, title = item.title,
                    headers = iptvPlaybackHeaders(item.headers)), item).getOrThrow()
            }
            result.exceptionOrNull()?.let {
                if (it is CancellationException) throw it
                Log.e("4789IptvPlay", "kind=vod stage=$stage error=${it.javaClass.simpleName}")
                ReceiverDiagnostics.record("iptv.play.failed", "kind=vod stage=$stage type=${it.javaClass.simpleName}")
                LiveTvLaunch.clearPlayback()
                nav.toast(it.message?.take(100) ?: "Couldn't play this video")
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
                    result.exceptionOrNull()?.let {
                        if (it is CancellationException) throw it
                        Log.e("4789IptvPlay", "kind=catchup stage=open error=${it.javaClass.simpleName}")
                        ReceiverDiagnostics.record("iptv.play.failed", "kind=catchup stage=open type=${it.javaClass.simpleName}")
                        LiveTvLaunch.clearPlayback()
                        nav.toast("Couldn't open catch-up playback.")
                    }
                }
            }
        }
    }

    LaunchedEffect(state.catalog.channels, state.catalog.vod) {
        val target = LiveTvLaunch.peek() ?: return@LaunchedEffect
        when (target.kind) {
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

    BackHandler(enabled = addSource || search || channelMenu != null || sourceMenu != null || editingSourceId != null ||
        seriesMenu != null || recordingMenu != null || groupPicker || parentalPanel || decisionKeyPanel || backupPanel ||
        manageGroupsPanel || favoriteListsPanel || pendingUnlock != null) {
        when {
            addSource -> addSource = false
            search -> search = false
            channelMenu != null -> channelMenu = null
            sourceMenu != null -> sourceMenu = null
            editingSourceId != null -> editingSourceId = null
            seriesMenu != null -> seriesMenu = null
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
    BackHandler(enabled = (selectedGroup != null || selectedVodGroup != null) && !addSource && !search && channelMenu == null &&
        sourceMenu == null && editingSourceId == null && seriesMenu == null && recordingMenu == null &&
        !groupPicker && !parentalPanel &&
        !decisionKeyPanel && !backupPanel && !manageGroupsPanel && !favoriteListsPanel && pendingUnlock == null) {
        selectedGroup = null
        selectedVodGroup = null
    }

    Column(Modifier.fillMaxSize().background(TvColor.Canvas)
        .onKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Menu) { nav.openRail(); true } else false
        }
        .padding(start = TvGeom.ContentLeft, end = 80.dp, top = TvGeom.SafeTop)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
            TvButton("Search", onClick = { search = true })
            Spacer(Modifier.width(12.dp))
            TvButton("Add source", onClick = { addSource = true })
            Spacer(Modifier.width(12.dp))
            TvButton(if (state.refreshing) "Refreshing…" else "Refresh",
                onClick = { scope.launch { repo.refresh() } }, enabled = !state.refreshing)
        }
        Spacer(Modifier.height(25.dp))
        Row(Modifier.onFocusChanged { tabsHaveFocus = it.hasFocus },
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LiveTab.entries.forEachIndexed { index, item ->
                TvFocusableCard(onClick = { if (tab != item) selectedVodGroup = null; tab = item }, selected = item == tab,
                    modifier = if (index == 0) Modifier.focusRequester(tabFocus) else Modifier,
                    focusScale = 1f, focusRing = false) { focused ->
                    LiveTabLabel(item.label, active = item == tab, focused = focused)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        if (state.loading) LiveLoadingHero() else LiveContent(tab, if (tab == LiveTab.Favorites && selectedFavoriteList != null)
            state.copy(accounts = state.accounts.copy(favoriteIds = state.accounts.favoriteLists[selectedFavoriteList].orEmpty()))
            else state, repo, selectedGroup,
            selectedVodGroup, contentFocus, unlockedGroups,
            onGroup = { groupPicker = true },
            onGroupSelect = { group ->
                if (group == null || group == ALL_CHANNELS) selectedGroup = group
                else withAccess(group) { selectedGroup = group }
            },
            onTab = { if (tab != it) selectedVodGroup = null; tab = it },
            onVodGroupSelect = { chosen ->
                selectedVodGroup = chosen
                if (chosen != null && chosen != ALL_VOD && tab in setOf(LiveTab.Movies, LiveTab.Series)) {
                    val kind = if (tab == LiveTab.Movies) "movie" else "series"
                    if (state.catalog.vod.none { it.kind == kind && it.category == chosen }) scope.launch {
                        if (repo.loadVodCategory(kind, chosen)) {
                            kotlinx.coroutines.delay(40)
                            runCatching { contentFocus.requestFocus() }
                        } else nav.toast("Couldn't open this category. Try again shortly.")
                    }
                }
            },
            onAdd = { addSource = true }, onChannel = ::play,
            onChannelMenu = { channel -> withAccess(channel.group) { channelMenu = channel } },
            onFavorite = ::favorite,
            onGuideProgram = ::chooseProgram, onVod = ::playVod,
            onRecording = { recordingMenu = it }, onSource = { sourceMenu = it },
            onParental = { parentalPanel = true }, onDecisionKey = { decisionKeyPanel = true },
            onBackup = { backupPanel = true }, onManageGroups = { manageGroupsPanel = true })
    }

    if (addSource) LiveAddSourcePanel(repo, onClose = { addSource = false }, onMessage = nav::toast)
    if (search) LiveSearchPanel(state, onClose = { search = false }, onChannel = ::play,
        onFavorite = ::favorite,
        onVod = ::playVod, unlockedGroups = unlockedGroups,
        onChannelGroup = { selectedGroup = it; tab = LiveTab.Channels },
        onVodGroup = { kind, category ->
            selectedVodGroup = category
            tab = if (kind == "movie") LiveTab.Movies else LiveTab.Series
        })
    channelMenu?.let { channel -> LiveChannelPanel(channel, state, repo, onClose = { channelMenu = null },
        onPlay = { channelMenu = null; play(it) },
        onProgram = { program -> chooseProgram(channel, program) }, onMessage = nav::toast,
        onMultiview = { channelMenu = null; nav.openMultiview(channel.id) }) }
    sourceMenu?.let { id -> LiveSourceActions(id, state, repo, onClose = { sourceMenu = null },
        onEdit = { sourceMenu = null; editingSourceId = id }) }
    editingSourceId?.let { id -> state.sources.firstOrNull { it.id == id }?.let { source ->
        LiveEditSourcePanel(source, repo, onClose = { editingSourceId = null }, onMessage = nav::toast)
    } }
    seriesMenu?.let { LiveEpisodesPanel(it, repo, onClose = { seriesMenu = null },
        onPlay = { episode -> playVod(episode) }) }
    recordingMenu?.let { selected -> state.accounts.recordings.firstOrNull { it.id == selected.id }?.let { item ->
        LiveRecordingPanel(item, repo, onClose = { recordingMenu = null },
            onPlay = { recordingMenu = null; playRecording(item) }, onMessage = nav::toast)
    } }
    if (groupPicker) LiveGroupPicker(state.channels.map(IptvChannel::group).distinct(), selectedGroup,
        onPick = { group ->
            groupPicker = false
            if (group == null || group == ALL_CHANNELS) selectedGroup = group
            else withAccess(group) { selectedGroup = group }
        }, onClose = { groupPicker = false })
    if (parentalPanel) LiveParentalPanel(state, repo, onClose = { parentalPanel = false }, onMessage = nav::toast)
    if (decisionKeyPanel) LiveDecisionKeyPanel(repo, onClose = { decisionKeyPanel = false },
        onMessage = nav::toast)
    if (backupPanel) LiveBackupPanel(repo, onClose = { backupPanel = false }, onMessage = nav::toast)
    if (favoriteListsPanel) LiveFavoriteListsPanel(state, repo, selectedFavoriteList,
        onSelect = { selectedFavoriteList = it }, onClose = { favoriteListsPanel = false }, onMessage = nav::toast)
    if (manageGroupsPanel) LiveManageGroupsPanel(state, repo, onClose = { manageGroupsPanel = false },
        onMessage = nav::toast)
    pendingUnlock?.let { pending -> LivePinPanel(pending.group, repo,
        onClose = { pendingUnlock = null }, onUnlock = {
            unlockedGroups = unlockedGroups + pending.group
            pendingUnlock = null
            pending.run()
        }, onMessage = nav::toast) }
}
