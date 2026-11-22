@file:Suppress("OPT_IN_USAGE")

package com.fourseveneightnine.tv.client.ui.screens.streams

import com.fourseveneightnine.tv.startup.ReceiverDiagnostics
import com.fourseveneightnine.tv.client.ui.components.benchmarkDraw
import com.fourseveneightnine.tv.client.ui.components.PanelTransition
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import com.fourseveneightnine.tv.protocol.NowPlayingArt
import com.fourseveneightnine.tv.client.ui.screens.player.PlaybackLoadingSurface
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.R
import com.fourseveneightnine.tv.client.ClientGraph
import com.fourseveneightnine.tv.client.playback.StreamIds
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.data.meta.Episode
import com.fourseveneightnine.tv.client.data.streams.RankedRow
import com.fourseveneightnine.tv.client.playback.NextUp
import com.fourseveneightnine.tv.client.playback.PlayRequest
import com.fourseveneightnine.tv.client.playback.PlayResult
import com.fourseveneightnine.tv.client.ui.LocalShellState
import com.fourseveneightnine.tv.client.ui.components.ButtonKind
import com.fourseveneightnine.tv.client.ui.components.SidePanel
import com.fourseveneightnine.tv.client.ui.components.SidePanelRow
import com.fourseveneightnine.tv.client.ui.components.StateBlock
import com.fourseveneightnine.tv.client.ui.components.TvButton
import com.fourseveneightnine.tv.client.ui.components.TvFocusable
import com.fourseveneightnine.tv.client.ui.components.TvGlassArtwork
import com.fourseveneightnine.tv.client.ui.components.tvSourcesGlass
import com.fourseveneightnine.tv.client.ui.nav.ClientNav
import com.fourseveneightnine.tv.client.ui.theme.LocalReduceMotion
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvMotion
import com.fourseveneightnine.tv.client.ui.theme.TvGeom
import com.fourseveneightnine.tv.client.ui.theme.TvShape
import com.fourseveneightnine.tv.client.ui.theme.TvSpace
import com.fourseveneightnine.tv.client.ui.theme.TvType
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Pick which copy of a title to play, and see why the top one is on top (`TV_DESIGN_SPEC.md` §10).
 *
 * Two rules do most of the work here. New rows land below the focused one and a re-sort is held
 * until focus leaves the list, so the D-pad never loses its place mid-search (§10.7).
 * Technical details open on hold OK; Left and Right keep focus on the source.
 */
private fun StreamsUiState.benchmarkSurface(surface: String): String = when {
    isError && search.timeouts == search.failed -> "${surface}_timeout"
    isError -> "${surface}_unreachable"
    noAddons -> "${surface}_no_addons"
    isFilteredEmpty -> "${surface}_filtered_empty"
    isEmpty -> "${surface}_empty"
    visible.isNotEmpty() -> "${surface}_ready"
    else -> "${surface}_loading"
}

@Composable
internal fun StreamsScreen(type: String, id: String, season: Int?, episode: Int?, nav: ClientNav) {
    val context = LocalContext.current
    val client = remember(context) { context.clientGraph }
    val scope = rememberCoroutineScope()
    var chosenSeason by rememberSaveable(type, id, season, episode) { mutableStateOf(season) }
    var chosenEpisode by rememberSaveable(type, id, season, episode) { mutableStateOf(episode) }
    var browseSeason by rememberSaveable(type, id, season, episode) { mutableStateOf(season ?: 1) }
    var picker by remember { mutableStateOf<EpisodePicker?>(null) }
    var retainedFilters by rememberSaveable(type, id, stateSaver = SourceFiltersSaver) { mutableStateOf(StreamFilters()) }
    var retainedSourceId by rememberSaveable(type, id) { mutableStateOf<String?>(null) }
    val viewModel = remember(type, id, chosenSeason, chosenEpisode) {
        StreamsViewModel(type, id, chosenSeason, chosenEpisode, client, scope, initialFilters = retainedFilters)
    }
    DisposableEffect(viewModel) {
        viewModel.setPickerVisible(true)
        onDispose { viewModel.stop() }
    }
    val state by viewModel.state.collectAsState()
    LaunchedEffect(state.filters) { retainedFilters = state.filters }
    val shell = LocalShellState.current

    val chipFocus = remember { FocusRequester() }
    val listFocus = remember { FocusRequester() }
    var menuRow by remember { mutableStateOf<RankedRow?>(null) }
    var finding by remember { mutableStateOf<Job?>(null) }
    var mediaTitle by remember(type, id) { mutableStateOf(id) }
    var mediaArt by remember(type, id) { mutableStateOf<NowPlayingArt?>(null) }
    var episodes by remember(type, id) { mutableStateOf<List<Episode>>(emptyList()) }

    val visible = state.visible
    if (com.fourseveneightnine.tv.BuildConfig.BENCHMARK_LOGGING) {
        LaunchedEffect(state.filters) {
            ReceiverDiagnostics.benchmark("sources.filter.result") { "screen=page rows=${visible.size}" }
        }
    }

    DisposableEffect(viewModel) {
        val restore = { runCatching { if (viewModel.state.value.visible.isNotEmpty()) listFocus.requestFocus() else chipFocus.requestFocus() }; Unit }
        shell.restoreContentFocus = restore
        onDispose { if (shell.restoreContentFocus === restore) shell.restoreContentFocus = null }
    }

    // The sub line names what is being searched for. Meta is already cached from Detail, so this
    // is a map lookup in the normal case and never holds the list up.
    LaunchedEffect(type, id) {
        val services = client.services.value ?: client.services.filterNotNull().first()
        val meta = runCatching { services.meta.meta(type, id) }.getOrNull() ?: return@LaunchedEffect
        mediaTitle = meta.title
        mediaArt = NowPlayingArt(meta.backdrop, meta.poster, meta.logo)
        episodes = meta.videos
    }
    val episodeTitle = episodes.firstOrNull { it.season == chosenSeason && it.episode == chosenEpisode }?.title
    val subtitle = listOfNotNull(
        mediaTitle,
        NextUp.subtitle(chosenSeason, chosenEpisode, episodeTitle).takeIf(String::isNotEmpty),
    ).joinToString(" · ")

    // Filters are the loading target. Once rows exist the shared list owns entry and return focus.
    val entryReady = StreamListOrder.canRequestEntryFocus(visible, state.search.done, retainedSourceId)
    LaunchedEffect(viewModel, picker, visible.isNotEmpty(), entryReady) {
        if (picker == null && (visible.isEmpty() || !entryReady)) {
            withFrameNanos { }
            runCatching { chipFocus.requestFocus() }
        }
    }

    fun chooseEpisode(value: Episode) {
        retainedSourceId = null
        chosenSeason = value.season
        chosenEpisode = value.episode
    }

    fun play(ranked: RankedRow) {
        val request = PlayRequest(
            type = type,
            id = id,
            title = mediaTitle,
            season = chosenSeason,
            episode = chosenEpisode,
        )
        finding?.cancel()
        finding = scope.launch {
            when (val result = client.playFlow.playRow(ranked.row, request)) {
                // The shell navigates to the player on `PlaybackSession.openRequests`.
                PlayResult.Opened -> finding = null
                PlayResult.ShowList -> finding = null
                is PlayResult.Failed -> {
                    finding = null
                    viewModel.refreshAvailability(ranked.row)
                    nav.toast(result.message)
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().benchmarkDraw(state.benchmarkSurface("sources_page"), revision = System.identityHashCode(state.filters))
        .background(TvColor.Background)) {
        Column(Modifier.fillMaxSize().padding(start = TvGeom.ContentLeft, top = TvGeom.SafeTop)) {
            Row(Modifier.fillMaxWidth().padding(end = 96.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("Sources", style = TvType.ScreenTitle, color = TvColor.TextPrimary, maxLines = 1)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = subtitle,
                        style = TvType.CardTitle,
                        color = TvColor.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                CountChip(state)
            }

            if (type == "series" && episodes.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                EpisodeNavigationBar(
                    videos = episodes, season = chosenSeason, episode = chosenEpisode, picker = picker,
                    modifier = Modifier.padding(end = 96.dp),
                    onPrevious = ::chooseEpisode,
                    onNext = ::chooseEpisode,
                    onShowSeasons = { browseSeason = chosenSeason ?: browseSeason; picker = EpisodePicker.Seasons },
                    onShowEpisodes = { browseSeason = chosenSeason ?: browseSeason; picker = EpisodePicker.Episodes },
                )
            }
            Spacer(Modifier.height(24.dp))
            if (picker != null) {
                Box(Modifier.width(StreamsGeometry.ListWidth).fillMaxHeight()) {
                    EpisodePickerList(
                        videos = episodes, picker = picker!!, browseSeason = browseSeason,
                        selectedSeason = chosenSeason, selectedEpisode = chosenEpisode,
                        onSeason = { browseSeason = it; picker = EpisodePicker.Episodes },
                        onEpisode = { chooseEpisode(it); picker = null },
                    )
                }
            } else {
            FilterChips(
                state = state,
                // The requester sits on the row, never on an item inside it (plan §7.4 rule 1).
                // With enough language chips the row scrolls, "All" leaves the window and a
                // request aimed at it lands on a detached node — the dead-remote fault in
                // `tv-dpad-focus-destroyed-by-loading-shelf.md`. `focusRestorer()` sends the
                // request on to the chip the viewer last used, or to the first one.
                modifier = Modifier.focusRequester(chipFocus).focusRestorer().focusProperties {
                    down = if (visible.isEmpty()) FocusRequester.Default else listFocus
                },
                onToggle = { retainedSourceId = null; viewModel.toggle(it) },
                onLeaveList = { viewModel.onFocus(null) },
            )
            Spacer(Modifier.height(18.dp))

            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(StreamsGeometry.ListWidth).fillMaxHeight()) {
                    when {
                        state.noAddons -> NoAddons { nav.openSettings("addons") }
                        state.isError -> Failure(state.failureMessage, viewModel::start)
                        state.isEmpty -> Empty(state.emptyMessage, viewModel::start) { nav.openSettings("addons") }
                        state.isFilteredEmpty -> FilteredEmpty(viewModel::clearFilters)
                        else -> SourceList(
                            rows = visible,
                            retainedSourceId = { retainedSourceId },
                            preferredLanguages = state.filters.languages + state.preferredAudioLanguages,
                            originalLanguage = state.originalLanguage,
                            posterUrl = mediaArt?.posterURL,
                            releaseDate = state.releaseDate,
                            pending = state.search.pending,
                            listModifier = Modifier.focusRequester(listFocus),
                            aboveListFocus = chipFocus,
                            focusRequestKey = viewModel,
                            filterKey = state.filters,
                            requestEntryFocus = entryReady && finding == null && menuRow == null,
                            onFocused = {
                                retainedSourceId = it?.row?.id
                                viewModel.onFocus(it?.row?.id)
                            },
                            onPlay = ::play,
                            onMenu = { menuRow = it },
                        )
                    }
                }
            }
            }
        }

        PanelTransition(finding) { job ->
            PlaybackLoadingSurface(art = mediaArt, title = subtitle,
                request = PlayRequest(type, id, mediaTitle, chosenSeason, chosenEpisode),
                onCancel = { job.cancel(); finding = null; runCatching { listFocus.requestFocus() } })
        }

        PanelTransition(menuRow) { ranked ->
            SourceOptions(
                ranked, client, PlayRequest(type, id, mediaTitle, chosenSeason, chosenEpisode),
                onPlay = { menuRow = null; play(ranked) },
                onClose = { menuRow = null },
                onToast = nav::toast,
            )
        }
    }

    BackHandler(enabled = menuRow != null || finding != null || picker != null) {
        when {
            finding != null -> {
                finding?.cancel()
                finding = null
                runCatching { listFocus.requestFocus() }
            }
            menuRow != null -> menuRow = null
            picker != null -> picker = null
        }
    }
}

/** The Detail action keeps its backdrop in place while the viewer chooses a copy. */
@Composable
internal fun StreamsDrawer(
    type: String,
    id: String,
    season: Int?,
    episode: Int?,
    title: String,
    subtitle: String? = null,
    episodes: List<Episode> = emptyList(),
    resumeFromMs: Long? = null,
    posterUrl: String?,
    backdropUrl: String?,
    prefetched: StreamsViewModel? = null,
    onDismiss: () -> Unit,
    onSettings: () -> Unit,
    onToast: (String) -> Unit,
    glassArtwork: TvGlassArtwork? = null,
) {
    val context = LocalContext.current
    val client = remember(context) { context.clientGraph }
    val scope = rememberCoroutineScope()
    var chosenSeason by rememberSaveable(type, id, season, episode) { mutableStateOf(season) }
    var chosenEpisode by rememberSaveable(type, id, season, episode) { mutableStateOf(episode) }
    var browseSeason by rememberSaveable(type, id, season, episode) { mutableStateOf(season ?: episodes.firstOrNull()?.season ?: 1) }
    var picker by remember { mutableStateOf<EpisodePicker?>(null) }
    var retainedFilters by rememberSaveable(type, id, stateSaver = SourceFiltersSaver) { mutableStateOf(StreamFilters.DrawerDefault) }
    var retainedSourceId by rememberSaveable(type, id) { mutableStateOf<String?>(null) }
    var pendingSourceId by remember { mutableStateOf(retainedSourceId) }
    var menuRow by remember { mutableStateOf<RankedRow?>(null) }
    val originalTarget = chosenSeason == season && chosenEpisode == episode
    // A recreated warm search starts with default filters; do not overwrite restored choices.
    val reusablePrefetch = remember(prefetched) {
        prefetched?.takeIf { it.state.value.filters == retainedFilters }
    }
    val viewModel = if (originalTarget && reusablePrefetch != null) reusablePrefetch else remember(type, id, chosenSeason, chosenEpisode) {
        StreamsViewModel(type, id, chosenSeason, chosenEpisode, client, scope, initialFilters = retainedFilters)
    }
    DisposableEffect(viewModel) {
        viewModel.setPickerVisible(true)
        onDispose {
            viewModel.setPickerVisible(false)
            if (viewModel !== prefetched) viewModel.stop()
        }
    }
    LaunchedEffect(viewModel) {
        if (viewModel === prefetched) viewModel.refreshIfStale()
    }
    val state by viewModel.state.collectAsState()
    LaunchedEffect(state.filters) { retainedFilters = state.filters }
    val rows = state.visible
    if (com.fourseveneightnine.tv.BuildConfig.BENCHMARK_LOGGING) {
        LaunchedEffect(state.filters) {
            ReceiverDiagnostics.benchmark("sources.filter.result") { "screen=drawer rows=${rows.size}" }
        }
    }
    val focusSourceId = pendingSourceId ?: retainedSourceId
    val returnTargetReady = pendingSourceId == null || rows.any { it.row.id == pendingSourceId } || state.search.done
    LaunchedEffect(viewModel, pendingSourceId, rows.isNotEmpty()) {
        val target = pendingSourceId ?: return@LaunchedEffect
        if (rows.isEmpty() || rows.any { it.row.id == target }) return@LaunchedEffect
        // Signed links can return with a new row ID. Give an exact return a short chance
        // to land before letting the best current result take focus.
        kotlinx.coroutines.delay(350)
        if (pendingSourceId == target && viewModel.state.value.visible.none { it.row.id == target }) {
            pendingSourceId = null
            if (retainedSourceId == target) retainedSourceId = null
        }
    }
    val drawerListState = rememberLazyListState()
    LaunchedEffect(state.filters) { drawerListState.scrollToItem(0) }
    val filterFocus = remember { FocusRequester() }
    val listFocus = remember { FocusRequester() }
    var finding by remember { mutableStateOf<Job?>(null) }
    val chosenTitle = episodes.firstOrNull { it.season == chosenSeason && it.episode == chosenEpisode }?.title
    val currentSubtitle = if (chosenSeason != null && chosenEpisode != null) {
        listOfNotNull("S$chosenSeason E$chosenEpisode", chosenTitle).joinToString(" · ")
    } else subtitle

    fun chooseEpisode(value: Episode) {
        retainedSourceId = null
        pendingSourceId = null
        chosenSeason = value.season
        chosenEpisode = value.episode
    }

    fun play(ranked: RankedRow) {
        retainedSourceId = ranked.row.id
        pendingSourceId = ranked.row.id
        finding?.cancel()
        finding = scope.launch {
            when (val result = client.playFlow.playRow(
                ranked.row,
                PlayRequest(type, id, title, chosenSeason, chosenEpisode, posterUrl, backdropUrl, resumeFromMs.takeIf { originalTarget }),
            )) {
                PlayResult.Opened -> finding = null
                PlayResult.ShowList -> finding = null
                is PlayResult.Failed -> { finding = null; viewModel.refreshAvailability(ranked.row); onToast(result.message) }
            }
        }
    }

    SidePanel(header = "Sources", onClose = {
        if (finding != null) { finding?.cancel(); finding = null } else if (picker != null) picker = null else onDismiss()
    }, modifier = Modifier.benchmarkDraw(state.benchmarkSurface("sources_drawer"), revision = System.identityHashCode(state.filters)),
        width = 920.dp, closeOnLeft = false,
        scrimAlpha = 0.30f, floatingGlass = true, floatingVerticalInset = 66.dp,
        panelSurface = { progress -> Modifier.tvSourcesGlass(glassArtwork, progress) }) {
        Text(listOfNotNull(currentSubtitle, state.countChip).joinToString(" · "), style = TvType.SourceMeta,
            color = TvColor.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (type == "series" && episodes.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            EpisodeNavigationBar(
                videos = episodes, season = chosenSeason, episode = chosenEpisode, picker = picker,
                onPrevious = ::chooseEpisode,
                onNext = ::chooseEpisode,
                onShowSeasons = { browseSeason = chosenSeason ?: browseSeason; picker = EpisodePicker.Seasons },
                onShowEpisodes = { browseSeason = chosenSeason ?: browseSeason; picker = EpisodePicker.Episodes },
            )
        }
        Spacer(Modifier.height(12.dp))
        if (picker != null) {
            EpisodePickerList(
                videos = episodes, picker = picker!!, browseSeason = browseSeason,
                selectedSeason = chosenSeason, selectedEpisode = chosenEpisode,
                onSeason = { browseSeason = it; picker = EpisodePicker.Episodes },
                onEpisode = { chooseEpisode(it); picker = null },
            )
        } else {
        DrawerFilterDropdowns(
            state = state,
            modifier = Modifier.focusRequester(filterFocus).focusRestorer().focusProperties {
                down = if (rows.isEmpty()) FocusRequester.Default else listFocus
            },
            onSelect = { filters ->
                if (filters != state.filters) {
                    retainedSourceId = null
                    pendingSourceId = null
                    viewModel.setFilters(filters)
                }
            },
            onLeaveList = { viewModel.onFocus(null) },
        )
        Spacer(Modifier.height(12.dp))
        when {
            state.noAddons -> DrawerEmpty("No stream add-ons are configured.", "Open Add-ons", onSettings)
            state.isError -> DrawerEmpty(state.failureMessage, "Retry", viewModel::start)
            state.isFilteredEmpty -> DrawerEmpty("No copies match these filters.", "Clear filters", viewModel::clearFilters)
            state.isEmpty -> DrawerEmpty(
                if (state.search.failed > 0) state.emptyMessage else "No copies found for this title.",
                "Retry", viewModel::start,
            )
            else -> SourceList(
                rows = rows,
                retainedSourceId = { focusSourceId },
                preferredLanguages = state.filters.languages + state.preferredAudioLanguages,
                originalLanguage = state.originalLanguage,
                posterUrl = posterUrl,
                releaseDate = state.releaseDate,
                pending = state.search.pending,
                listModifier = Modifier.focusRequester(listFocus),
                aboveListFocus = filterFocus,
                focusRequestKey = viewModel,
                filterKey = state.filters,
                listState = drawerListState,
                stageFirstViewport = true,
                rowWidth = 816.dp,
                translucentRows = true,
                showAllSources = true,
                requestEntryFocus = returnTargetReady && finding == null && menuRow == null &&
                    StreamListOrder.canRequestEntryFocus(rows, state.search.done, focusSourceId),
                onFocused = { ranked ->
                    if (ranked != null) {
                        retainedSourceId = ranked.row.id
                        pendingSourceId = null
                        viewModel.onFocus(ranked.row.id)
                    }
                },
                onPlay = ::play,
                onMenu = { menuRow = it },
            )
        }
        }
    }

    val drawerEntryReady = StreamListOrder.canRequestEntryFocus(rows, state.search.done, focusSourceId)
    LaunchedEffect(viewModel, picker, rows.isNotEmpty(), drawerEntryReady) {
        if (picker == null && (rows.isEmpty() || !drawerEntryReady)) {
            withFrameNanos { }
            runCatching { filterFocus.requestFocus() }
        }
    }
    PanelTransition(menuRow) { ranked ->
        SourceOptions(
            ranked, client, PlayRequest(type, id, title, chosenSeason, chosenEpisode),
            onPlay = { menuRow = null; play(ranked) },
            onClose = { menuRow = null },
            onToast = onToast,
        )
    }
    PanelTransition(finding) { job ->
        val services by client.services.collectAsState()
        val metadata = services?.meta?.peek(type, id.substringAfter("::"))
        PlaybackLoadingSurface(
            request = PlayRequest(type, id, title, chosenSeason, chosenEpisode),
            art = NowPlayingArt(backdropUrl ?: metadata?.backdrop, posterUrl ?: metadata?.poster, metadata?.logo),
            title = title,
            onCancel = { job.cancel(); finding = null; runCatching { listFocus.requestFocus() } },
        )
    }
    BackHandler {
        when {
            finding != null -> { finding?.cancel(); finding = null; runCatching { listFocus.requestFocus() } }
            menuRow != null -> menuRow = null
            picker != null -> picker = null
            else -> onDismiss()
        }
    }
}

private data class DrawerFilterOption(val label: String, val selected: Boolean, val filters: StreamFilters)

@Composable
private fun DrawerFilterDropdowns(
    state: StreamsUiState,
    modifier: Modifier,
    onSelect: (StreamFilters) -> Unit,
    onLeaveList: () -> Unit,
) {
    val filters = state.filters
    val delivery = listOf(
        "All sources" to SourceKind.ALL,
        "Cached Debrid" to SourceKind.DEBRID,
        "Direct" to SourceKind.HTTPS,
    ).map { (label, kind) -> DrawerFilterOption(label, filters.kind == kind, filters.copy(kind = kind, cached = false)) }
    val quality = listOf(
        Triple("Any quality", false, false), Triple("4K", true, false),
        Triple("1080p", false, true), Triple("4K and 1080p", true, true),
    ).map { (label, fourK, fullHd) ->
        DrawerFilterOption(label, filters.fourK == fourK && filters.fullHd == fullHd, filters.copy(fourK = fourK, fullHd = fullHd))
    }
    val languageOptions = buildList {
        val any = filters.languages.isEmpty() && !filters.otherLanguages && !filters.unknownLanguage
        add(DrawerFilterOption("Any language", any,
            filters.copy(languages = emptySet(), otherLanguages = false, unknownLanguage = false)))
        (state.languageChips + filters.languages).distinct().forEach { language ->
            val selected = language in filters.languages
            val names = if (selected) filters.languages - language else filters.languages + language
            add(DrawerFilterOption(language, selected, filters.copy(languages = names)))
        }
        if (filters.otherLanguages || StreamFilterRules.hasOtherLanguages(state.displayed.map { it.row }, state.languageChips)) {
            add(DrawerFilterOption("Other languages", filters.otherLanguages, filters.copy(otherLanguages = !filters.otherLanguages)))
        }
        if (filters.unknownLanguage) {
            add(DrawerFilterOption("Unspecified audio", filters.unknownLanguage, filters.copy(unknownLanguage = !filters.unknownLanguage)))
        }
    }
    val selectedLanguages = languageOptions.filter { it.selected }
    val languageLabel = selectedLanguages.singleOrNull()?.label ?: "${selectedLanguages.size} audio filters"
    Row(
        modifier.fillMaxWidth().onFocusChanged { if (it.hasFocus) onLeaveList() }.focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DrawerFilterDropdown("Delivery", delivery.first { it.selected }.label, delivery, Modifier.weight(1f), onSelect)
        DrawerFilterDropdown("Quality", quality.first { it.selected }.label, quality, Modifier.weight(1f), onSelect)
        DrawerFilterDropdown("Language", languageLabel, languageOptions, Modifier.weight(1f), onSelect)
    }
}

@Composable
private fun DrawerFilterDropdown(
    category: String,
    label: String,
    options: List<DrawerFilterOption>,
    modifier: Modifier,
    onSelect: (StreamFilters) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val opener = remember { FocusRequester() }
    val first = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val shape = TvShape.Control
    fun dismiss() {
        expanded = false
        scope.launch {
            withFrameNanos { }
            runCatching { opener.requestFocus() }
        }
    }
    Box(modifier) {
        TvFocusable(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().focusRequester(opener),
            accessibleLabel = "$category: $label", focusRing = false, focusScale = 1f) { focused ->
            Row(
                Modifier.fillMaxWidth().height(54.dp).clip(shape)
                    .background(Color.White.copy(alpha = if (focused || expanded) 0.13f else 0.07f))
                    .border(if (focused) 2.dp else 1.dp, if (focused) TvColor.Focus else TvColor.Border, shape)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = TvType.ControlLabel, color = TvColor.TextPrimary, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Image(painterResource(R.drawable.ic_source_expand_more), contentDescription = null,
                    modifier = Modifier.size(32.dp), colorFilter = ColorFilter.tint(TvColor.TextSecondary))
            }
        }
        if (expanded) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, with(density) { 62.dp.roundToPx() }),
                onDismissRequest = ::dismiss,
                properties = PopupProperties(focusable = true),
            ) {
                val focusIndex = options.indexOfFirst { it.selected }.coerceAtLeast(0)
                CompositionLocalProvider(LocalDensity provides density) {
                    Column(Modifier.width(278.dp).clip(shape).background(TvColor.Canvas.copy(alpha = 0.91f))
                        .border(1.dp, TvColor.Border, shape).padding(8.dp).focusGroup()) {
                        options.forEachIndexed { index, option ->
                            key(option.label) {
                                SidePanelRow(option.label, option.selected, onClick = {
                                    onSelect(option.filters)
                                    dismiss()
                                }, modifier = if (index == focusIndex) Modifier.focusRequester(first) else Modifier,
                                    width = 262.dp, height = 52.dp)
                            }
                        }
                    }
                }
                LaunchedEffect(Unit) {
                    withFrameNanos { }
                    runCatching { first.requestFocus() }
                }
            }
        }
    }
}

@Composable
private fun DrawerFilter(label: String, selected: Boolean, icon: Int? = null, onClick: () -> Unit) {
    TvFocusable(onClick = onClick, accessibleLabel = label, selected = selected, focusRing = false, focusScale = 1f) { focused ->
        Box(
            Modifier.height(54.dp).clip(CircleShape)
                .background(if (selected) TvColor.Selection.copy(alpha = 0.14f) else TvColor.Elevated2)
                .border(if (focused) 3.dp else 1.dp, if (focused) TvColor.Focus else if (selected) TvColor.Selection else TvColor.Border, CircleShape)
                .padding(horizontal = 22.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                icon?.let {
                    Image(painterResource(it), contentDescription = null, colorFilter = ColorFilter.tint(if (it == R.drawable.ic_source_bolt) TvColor.Cached else TvColor.TextSecondary), modifier = Modifier.size(36.dp))
                }
                Text((if (selected) "✓ " else "") + label, style = TvType.ControlLabel, color = TvColor.TextPrimary)
            }
        }
    }
}

@Composable
private fun DrawerEmpty(message: String, action: String, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 36.dp)) {
        Text(message, style = TvType.ControlLabel, color = TvColor.TextSecondary)
        Spacer(Modifier.height(24.dp))
        TvButton(action, onAction, kind = ButtonKind.Secondary)
    }
}

private enum class EpisodePicker { Seasons, Episodes }

@Composable
private fun EpisodeNavigationBar(
    videos: List<Episode>,
    season: Int?,
    episode: Int?,
    picker: EpisodePicker?,
    modifier: Modifier = Modifier,
    onPrevious: (Episode) -> Unit,
    onNext: (Episode) -> Unit,
    onShowSeasons: () -> Unit,
    onShowEpisodes: () -> Unit,
) {
    val previous = SourceEpisodeNavigation.adjacent(videos, season, episode, -1)
    val next = SourceEpisodeNavigation.adjacent(videos, season, episode, 1)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        EpisodeNavButton(
            label = if (previous != null && previous.season != season) "Prev season" else "Previous",
            icon = R.drawable.ic_source_skip_previous,
            enabled = previous != null,
            modifier = Modifier.width(154.dp),
        ) { previous?.let(onPrevious) }
        EpisodeNavButton(
            label = "Season ${season ?: "–"}",
            icon = R.drawable.ic_source_expand_more,
            selected = picker == EpisodePicker.Seasons,
            modifier = Modifier.width(164.dp),
            onClick = onShowSeasons,
        )
        EpisodeNavButton(
            label = "Episode ${episode ?: "–"}",
            icon = R.drawable.ic_source_expand_more,
            selected = picker == EpisodePicker.Episodes,
            modifier = Modifier.width(210.dp),
            onClick = onShowEpisodes,
        )
        Spacer(Modifier.weight(1f))
        EpisodeNavButton(
            label = if (next != null && next.season != season) "Next season" else "Next",
            icon = R.drawable.ic_source_skip_next,
            enabled = next != null,
            modifier = Modifier.width(142.dp),
        ) { next?.let(onNext) }
    }
}

@Composable
private fun EpisodeNavButton(
    label: String,
    icon: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    TvFocusable(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        accessibleLabel = label,
        selected = selected,
        focusRing = false,
        focusScale = 1f,
    ) { focused ->
        val ink = TvColor.TextPrimary
        Row(
            Modifier.fillMaxWidth().height(66.dp).clip(TvShape.Control)
                .background(
                    when {
                        focused -> TvColor.Elevated2
                        !enabled -> TvColor.Elevated2
                        selected -> TvColor.Selection.copy(alpha = 0.14f)
                        else -> TvColor.Elevated
                    },
                )
                .border(if (focused) 3.dp else 1.dp, if (focused) TvColor.Focus else if (selected) TvColor.Selection else TvColor.Border, TvShape.Control)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Image(
                painter = painterResource(icon), contentDescription = null,
                colorFilter = ColorFilter.tint(ink), modifier = Modifier.size(25.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(label, style = TvType.ControlLabel.copy(fontSize = 21.5.sp), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun EpisodePickerList(
    videos: List<Episode>,
    picker: EpisodePicker,
    browseSeason: Int,
    selectedSeason: Int?,
    selectedEpisode: Int?,
    onSeason: (Int) -> Unit,
    onEpisode: (Episode) -> Unit,
) {
    val ordered = remember(videos) { SourceEpisodeNavigation.ordered(videos) }
    val seasons = remember(ordered) { ordered.map(Episode::season).distinct() }
    val choices = if (picker == EpisodePicker.Episodes) ordered.filter { it.season == browseSeason } else emptyList()
    val selectedIndex = if (picker == EpisodePicker.Seasons) {
        seasons.indexOf(browseSeason).coerceAtLeast(0)
    } else choices.indexOfFirst { it.season == selectedSeason && it.episode == selectedEpisode }.coerceAtLeast(0)
    val listState = rememberLazyListState()
    val entryFocus = remember { FocusRequester() }
    LaunchedEffect(picker, browseSeason, selectedIndex) {
        listState.scrollToItem(selectedIndex)
        kotlinx.coroutines.delay(32)
        runCatching { entryFocus.requestFocus() }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().fillMaxHeight().focusRestorer(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 44.dp),
    ) {
        if (picker == EpisodePicker.Seasons) {
            itemsIndexed(seasons, key = { _, value -> value }) { index, value ->
                EpisodePickerTile(
                    title = "Season $value",
                    detail = "${ordered.count { it.season == value }} episodes",
                    selected = value == selectedSeason,
                    modifier = if (index == selectedIndex) Modifier.focusRequester(entryFocus) else Modifier,
                ) { onSeason(value) }
            }
        } else {
            itemsIndexed(choices, key = { _, value -> "${value.season}:${value.episode}" }) { index, value ->
                EpisodePickerTile(
                    title = "E${value.episode} · ${value.title}",
                    detail = "Season ${value.season}",
                    selected = value.season == selectedSeason && value.episode == selectedEpisode,
                    modifier = if (index == selectedIndex) Modifier.focusRequester(entryFocus) else Modifier,
                ) { onEpisode(value) }
            }
        }
    }
}

@Composable
private fun EpisodePickerTile(
    title: String,
    detail: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    TvFocusable(
        onClick = onClick, modifier = modifier,
        accessibleLabel = "$title, $detail", selected = selected,
        focusScale = 1f, focusRing = false,
    ) { focused ->
        Column(
            Modifier.fillMaxWidth().height(90.dp).clip(TvShape.CardProminent)
                .background(if (selected) TvColor.Selection.copy(alpha = 0.14f) else TvColor.Elevated2)
                .border(if (focused) 3.dp else 1.dp, if (focused) TvColor.Focus else if (selected) TvColor.Selection else TvColor.Border, TvShape.CardProminent)
                .padding(horizontal = 22.dp, vertical = 12.dp),
        ) {
            Text(title, style = TvType.ControlLabel.copy(fontSize = 26.sp), color = TvColor.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = TvType.SourceMeta.copy(fontSize = 21.5.sp), color = TvColor.TextSecondary, maxLines = 1)
        }
    }
}

// ---------------------------------------------------------------------------- pieces

@Composable
private fun CountChip(state: StreamsUiState) {
    val failed = StreamCount.namesAFailure(state.search)
    // Sized to its words. A fixed 380 px box around "63 sources" read as an empty text field.
    Box(
        Modifier
            .widthIn(max = 380.dp)
            .height(40.dp)
            .clip(TvShape.Chip)
            .background(TvColor.Elevated)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = state.countChip,
            style = TvType.data(20),
            // §10.7: "failed" is the one word in this chip that draws in `warning`.
            color = if (failed) TvColor.Warning else TvColor.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FilterChips(
    state: StreamsUiState,
    modifier: Modifier,
    onToggle: (StreamChip) -> Unit,
    onLeaveList: () -> Unit,
) {
    val filters = state.filters
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .horizontalScroll(rememberScrollState())
            // A bounded chip group is cheaper to measure without lazy subcomposition.
            .onFocusChanged { if (it.hasFocus) onLeaveList() }
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DrawerFilter("All", filters.isAll) { onToggle(StreamChip.All) }
        DrawerFilter("Cached Debrid", filters.kind == SourceKind.DEBRID) { onToggle(StreamChip.Debrid) }
        DrawerFilter("Direct", filters.kind == SourceKind.HTTPS) { onToggle(StreamChip.Https) }
        DrawerFilter("4K", filters.fourK, R.drawable.ic_source_4k) { onToggle(StreamChip.FourK) }
        DrawerFilter("1080p", filters.fullHd, R.drawable.ic_source_hd) { onToggle(StreamChip.FullHd) }
        state.languageChips.forEach { language ->
            key(language) {
                DrawerFilter(language, language in filters.languages) { onToggle(StreamChip.Language(language)) }
            }
        }
        if (filters.otherLanguages || StreamFilterRules.hasOtherLanguages(state.displayed.map { it.row }, state.languageChips)) {
            DrawerFilter("Other", filters.otherLanguages) { onToggle(StreamChip.Other) }
        }
        if (filters.unknownLanguage || StreamFilterRules.hasUnknownLanguages(state.displayed.map { it.row })) {
            DrawerFilter("Unknown language", filters.unknownLanguage) { onToggle(StreamChip.Unknown) }
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun SourceList(
    rows: List<RankedRow>,
    retainedSourceId: () -> String?,
    preferredLanguages: Set<String>,
    originalLanguage: String?,
    pending: Int,
    listModifier: Modifier,
    aboveListFocus: FocusRequester,
    focusRequestKey: Any?,
    posterUrl: String? = null,
    releaseDate: String? = null,
    filterKey: Any? = null,
    listState: LazyListState = rememberLazyListState(),
    rowWidth: Dp = ROW_WIDTH,
    stageFirstViewport: Boolean = false,
    translucentRows: Boolean = false,
    showAllSources: Boolean = false,
    requestEntryFocus: Boolean = true,
    onFocused: (RankedRow?) -> Unit,
    onPlay: (RankedRow) -> Unit,
    onMenu: (RankedRow) -> Unit,
) {
    val retainedId = retainedSourceId()
    val reduceMotion = LocalReduceMotion.current
    val picks = remember(rows, retainedId) { SourceQualityGroups.from(rows, retainedId = retainedId) }
    val all = remember(rows) { SourceQualityGroups.from(rows, expanded = true) }
    val hasMore = !showAllSources && all.sumOf { it.rows.size } > picks.sumOf { it.rows.size }
    var expanded by rememberSaveable(focusRequestKey, filterKey) {
        val retained = retainedSourceId()
        mutableStateOf(retained != null && all.any { group -> group.rows.any { it.row.id == retained } } &&
            picks.none { group -> group.rows.any { it.row.id == retained } })
    }
    var revealId by remember(focusRequestKey, filterKey) { mutableStateOf<String?>(null) }
    val groups = if (showAllSources || expanded) all else picks
    LaunchedEffect(focusRequestKey, filterKey) { listState.scrollToItem(0) }
    val ordered = remember(groups) { groups.flatMap { it.rows } }
    val entryFocus = remember { FocusRequester() }
    val restoredID = retainedId?.takeIf { id -> ordered.any { it.row.id == id } }
    val targetID = SourceQualityGroups.entryId(ordered, revealId ?: restoredID)
    var entryPlaced by remember(focusRequestKey) { mutableStateOf(false) }
    LaunchedEffect(requestEntryFocus) {
        if (!requestEntryFocus) entryPlaced = false
    }
    var rowBudget by remember(focusRequestKey) {
        mutableIntStateOf(if (stageFirstViewport && retainedSourceId() == null) 1 else Int.MAX_VALUE)
    }
    LaunchedEffect(focusRequestKey, rows.isNotEmpty()) {
        if (rows.isEmpty() || rowBudget == Int.MAX_VALUE) return@LaunchedEffect
        // Focus can land on the first source immediately. Mount one more row per frame
        // while the drawer enters, with stable keys for rows already on screen.
        repeat(5) {
            withFrameNanos { }
            if (rowBudget == Int.MAX_VALUE) return@LaunchedEffect
            rowBudget++
        }
        withFrameNanos { }
        rowBudget = Int.MAX_VALUE
    }
    val entryReady = requestEntryFocus && !entryPlaced && (targetID != null || hasMore)
    LaunchedEffect(focusRequestKey, targetID, entryReady) {
        if (!entryReady) return@LaunchedEffect
        // A fresh drawer can take focus as soon as a ranked row is available. Other add-ons
        // can finish in the background without moving the viewer off the chosen row.
        if (targetID == null || (revealId == null && restoredID == null && targetID == ordered.firstOrNull()?.row?.id)) {
            if (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0) {
                listState.scrollToItem(0)
            }
        } else {
            rowBudget = Int.MAX_VALUE
            if (listState.layoutInfo.visibleItemsInfo.none { it.key == "source:$targetID" }) {
                listState.scrollToItem(SourceQualityGroups.listIndex(groups, targetID))
            }
        }
        withFrameNanos { }
        repeat(10) {
            if (runCatching { entryFocus.requestFocus() }.getOrDefault(false)) {
                android.util.Log.d("4789Sources", "Entry focus with $pending providers pending")
                entryPlaced = true
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(50)
        }
    }
    Column(Modifier.fillMaxHeight().width(rowWidth + 48.dp)) {
        LazyColumn(
            state = listState,
            modifier = listModifier.weight(1f).fillMaxWidth()
                .focusProperties { enter = { entryFocus } }.focusRestorer(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp, vertical = if (showAllSources) 8.dp else 20.dp),
        ) {
            var previousRows = 0
            groups.forEach { group ->
                val firstIndex = previousRows
                previousRows += group.rows.size
                item(key = "delivery:${group.label}", contentType = "quality") {
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(group.label, style = TvType.ShelfHeader, color = TvColor.TextPrimary)
                        Text("${group.rows.size} ${if (group.rows.size == 1) "source" else "sources"}",
                            style = TvType.SourceMeta, color = if (translucentRows) TvColor.TextSecondary else TvColor.TextMuted)
                    }
                }
                itemsIndexed(group.rows, key = { _, ranked -> "source:${ranked.row.id}" },
                    contentType = { _, _ -> "source" }) { index, ranked ->
                    val mounted by remember(focusRequestKey, firstIndex + index) {
                        derivedStateOf { firstIndex + index < rowBudget }
                    }
                    if (!mounted) {
                        Spacer(Modifier.width(rowWidth).height(ROW_HEIGHT))
                        return@itemsIndexed
                    }
                    StreamListRow(
                        row = ranked.row,
                        width = rowWidth,
                        preferredLanguages = preferredLanguages,
                        originalLanguage = originalLanguage,
                        posterUrl = posterUrl,
                        releaseDate = releaseDate,
                        translucent = translucentRows,
                        reason = ranked.cautions.firstOrNull()?.text ?: if (!ranked.eligible) "Outside your playback rules" else null,
                        caution = ranked.cautions.isNotEmpty() || !ranked.eligible,
                        onPlay = { onPlay(ranked) },
                        onMenu = { onMenu(ranked) },
                        modifier = Modifier
                            .then(if (reduceMotion) Modifier else Modifier.animateItem(
                                fadeInSpec = tween(TvMotion.ContentMillis, easing = TvMotion.Out),
                                placementSpec = null,
                                fadeOutSpec = null,
                            ))
                            .then(if (ranked.row.id == targetID) Modifier.focusRequester(entryFocus) else Modifier)
                            .focusProperties {
                                left = FocusRequester.Cancel
                                right = FocusRequester.Cancel
                                if (ranked.row.id == ordered.firstOrNull()?.row?.id) up = aboveListFocus
                            }
                            .onFocusChanged {
                                if (it.isFocused) {
                                    entryPlaced = true
                                    revealId = null
                                    onFocused(ranked)
                                }
                            },
                    )
                }
            }
            if (pending > 0) {
                if (ordered.isEmpty()) {
                    // Reserve the same space as the first delivery heading when results land.
                    item(key = "pending-source-heading", contentType = "skeleton-heading") {
                        Spacer(Modifier.height(TvGeom.ShelfHeaderHeight))
                    }
                }
                items(
                    count = (3 - ordered.size).coerceAtLeast(1),
                    key = { index -> "pending-source:$index" },
                    contentType = { "skeleton" },
                ) { index ->
                    StreamSkeletonRow(sweep = index < 2, modifier = Modifier.width(rowWidth))
                }
            }
        }
        if (hasMore) {
            SidePanelRow(if (expanded) "Show less" else "Show more", false, {
                val next = if (expanded) picks.firstOrNull()?.rows?.firstOrNull() else {
                    val shown = picks.flatMap { it.rows }.mapTo(hashSetOf()) { it.row.id }
                    all.asSequence().flatMap { it.rows.asSequence() }.firstOrNull { it.row.id !in shown }
                }
                expanded = !expanded
                revealId = next?.row?.id
                entryPlaced = false
            }, modifier = Modifier.padding(horizontal = 24.dp)
                .then(if (targetID == null) Modifier.focusRequester(entryFocus) else Modifier), width = rowWidth)
        }
        if (!showAllSources) {
            Text("OK plays · Hold OK for details · Back returns", style = TvType.SourceMeta, color = TvColor.TextSecondary,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp))
        }
    }
}

// ---------------------------------------------------------------------------- states, §10.9

@Composable
private fun Empty(message: String, onRetry: () -> Unit, onAddons: () -> Unit) {
    val focus = remember { FocusRequester() }
    StateBlock(
        headline = "No sources for this title",
        line = message,
        actionLabel = "Retry",
        onAction = onRetry,
        secondaryLabel = "Open Add-ons",
        onSecondary = onAddons,
        actionModifier = Modifier.focusRequester(focus),
    )
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
private fun FilteredEmpty(onClear: () -> Unit) {
    val focus = remember { FocusRequester() }
    StateBlock(
        headline = "No sources match these filters",
        line = "Clear a filter to see the rest.",
        actionLabel = "Clear filters",
        onAction = onClear,
        actionModifier = Modifier.focusRequester(focus),
    )
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
private fun Failure(message: String, onRetry: () -> Unit) {
    val focus = remember { FocusRequester() }
    StateBlock(
        headline = "Couldn't reach your add-ons",
        line = message,
        actionLabel = "Retry",
        onAction = onRetry,
        actionModifier = Modifier.focusRequester(focus),
        announceAsError = true,
    )
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
private fun NoAddons(onAddons: () -> Unit) {
    val focus = remember { FocusRequester() }
    StateBlock(
        headline = "No add-ons answer streams",
        line = "Sync your iPhone, or add one in Settings.",
        actionLabel = "Open Add-ons",
        onAction = onAddons,
        actionModifier = Modifier.focusRequester(focus),
    )
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

private val SourceFiltersSaver = listSaver<StreamFilters, Any>(
    save = { listOf(it.cached, it.fourK, it.fullHd, it.otherLanguages, it.unknownLanguage, "kind:" + it.kind.name) + it.languages.sorted() },
    restore = {
        val family = (it.getOrNull(5) as? String)?.takeIf { value -> value.startsWith("kind:") }
        StreamFilters(fourK = it[1] as Boolean, fullHd = it[2] as Boolean,
            kind = family?.removePrefix("kind:")?.let { name -> SourceKind.entries.firstOrNull { kind -> kind.name == name } } ?: SourceKind.ALL,
            otherLanguages = it[3] as Boolean, unknownLanguage = it[4] as Boolean,
            languages = it.drop(if (family == null) 5 else 6).map { value -> value as String }.toSet())
    },
)

@Composable
internal fun SourceOptions(
    ranked: RankedRow,
    client: ClientGraph,
    request: PlayRequest,
    onPlay: () -> Unit,
    onClose: () -> Unit,
    onToast: (String) -> Unit,
    playerOverlay: Boolean = false,
) {
    val first = remember { FocusRequester() }
    val source = ranked.row
    val scope = rememberCoroutineScope()
    val canFavorite = source.playable && com.fourseveneightnine.tv.catalog.EinthusanSource.parse(source.url) == null &&
        StreamIds.favoriteScopes(request, null).isNotEmpty()
    val selected = remember(source, request) { canFavorite && client.favoriteSource(request)?.matches(source) == true }
    var saving by remember(source, request) { mutableStateOf(false) }
    val details = remember(source) {
        com.fourseveneightnine.tv.client.data.streams.StreamRanker.facts(source) + listOfNotNull(
            sourceReleaseType(source.releaseName)?.let { "Release" to it },
            "Availability" to if (com.fourseveneightnine.tv.catalog.EinthusanSource.parse(source.url) != null)
                "Instant" else com.fourseveneightnine.tv.client.data.streams.StreamRanker.deliveryLabel(source),
        )
    }
    SidePanel("Source details", onClose = onClose, width = if (playerOverlay) 640.dp else 760.dp, scrimAlpha = if (playerOverlay) 0f else 0.52f, floatingGlass = playerOverlay) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 40.dp)) {
            item { SidePanelRow("Play this source", false, onPlay, Modifier.focusRequester(first)) }
            if (canFavorite) item {
                SidePanelRow(if (selected) "Remove Favorite" else "Favorite for Play", selected, onClick = {
                    if (!saving) {
                        saving = true
                        scope.launch {
                            val saved = client.toggleFavoriteSource(request, source)
                            saving = false
                            when (saved) {
                                true -> { onToast("Favorite for Play saved."); onClose() }
                                false -> { onToast("Favorite removed."); onClose() }
                                null -> onToast("Couldn’t save the favorite. Try again.")
                            }
                        }
                    }
                })
            }
            item { Text(ranked.row.releaseName.ifBlank { UNNAMED }, style = TvType.ControlLabel, color = TvColor.TextPrimary) }
            items(ranked.reasons.sortedByDescending { it.caution }) { reason ->
                Text((if (reason.caution) "! " else "") + reason.text, style = TvType.ControlLabel,
                    color = if (reason.caution) TvColor.Warning else TvColor.TextSecondary)
            }
            items(details) { (label, value) ->
                var focused by remember(source.id, label) { mutableStateOf(false) }
                Text("$label · $value", style = TvType.SourceMeta,
                    color = if (focused) TvColor.TextPrimary else TvColor.TextSecondary,
                    modifier = Modifier.fillMaxWidth()
                        .background(if (focused) TvColor.Elevated else androidx.compose.ui.graphics.Color.Transparent, TvShape.Panel)
                        .border(1.dp, if (focused) TvColor.Accent else androidx.compose.ui.graphics.Color.Transparent, TvShape.Panel)
                        .padding(12.dp).onFocusChanged { focused = it.isFocused }.focusable())
            }
            item { Text("Format and cache labels describe the source. They do not confirm TV or sound-system output.", style = TvType.SourceMeta, color = TvColor.TextMuted) }
            item { SidePanelRow("Back to sources", false, onClose) }
        }
    }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
}
