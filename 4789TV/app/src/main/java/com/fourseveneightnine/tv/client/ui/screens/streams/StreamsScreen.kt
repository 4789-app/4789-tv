@file:Suppress("OPT_IN_USAGE")

package com.fourseveneightnine.tv.client.ui.screens.streams

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.R
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
import com.fourseveneightnine.tv.client.ui.nav.ClientNav
import com.fourseveneightnine.tv.client.ui.theme.TvColor
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
 * until focus leaves the list, so the D-pad never loses its place mid-search (§10.7). And the
 * right pane is a readout, not a control: it is never focusable, so RIGHT cannot walk off the list.
 */
@Composable
internal fun StreamsScreen(type: String, id: String, season: Int?, episode: Int?, nav: ClientNav) {
    val context = LocalContext.current
    val client = remember(context) { context.clientGraph }
    val preparation by client.playFlow.preparationLabel.collectAsState()
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
    DisposableEffect(viewModel) { onDispose { viewModel.stop() } }
    val state by viewModel.state.collectAsState()
    LaunchedEffect(state.filters) { retainedFilters = state.filters }
    val shell = LocalShellState.current

    val chipFocus = remember { FocusRequester() }
    val listFocus = remember { FocusRequester() }
    // A state object, not a `by` read. The focused row is read inside `ReasonPane` and nowhere
    // else, so moving the ring down the list recomposes the pane and the two rows that swap their
    // fill — not the title, the count chip, the filter row and all seven rows. Home leaves the same
    // note at `HomeScreen.kt:85-90`; Streams did not have it.
    val focused = remember { mutableStateOf<RankedRow?>(null) }
    var menuRow by remember { mutableStateOf<RankedRow?>(null) }
    var finding by remember { mutableStateOf<Job?>(null) }
    var findingRow by remember { mutableStateOf<RankedRow?>(null) }
    var mediaTitle by remember(type, id) { mutableStateOf(id) }
    var episodes by remember(type, id) { mutableStateOf<List<Episode>>(emptyList()) }
    var placedInList by remember { mutableStateOf(false) }

    val visible = state.visible

    DisposableEffect(Unit) {
        val restore = { runCatching { chipFocus.requestFocus() }; Unit }
        shell.restoreContentFocus = restore
        onDispose { if (shell.restoreContentFocus === restore) shell.restoreContentFocus = null }
    }

    // The sub line names what is being searched for. Meta is already cached from Detail, so this
    // is a map lookup in the normal case and never holds the list up.
    LaunchedEffect(type, id) {
        val services = client.services.value ?: client.services.filterNotNull().first()
        val meta = runCatching { services.meta.meta(type, id) }.getOrNull() ?: return@LaunchedEffect
        mediaTitle = meta.title
        episodes = meta.videos
    }
    val episodeTitle = episodes.firstOrNull { it.season == chosenSeason && it.episode == chosenEpisode }?.title
    val subtitle = listOfNotNull(
        mediaTitle,
        NextUp.subtitle(chosenSeason, chosenEpisode, episodeTitle).takeIf(String::isNotEmpty),
    ).joinToString(" · ")

    // Initial focus goes on the chip row, which is always composed (plan §7.4 rule 2). It moves
    // into the list once — and only once rows exist, because a row that is not there is not a
    // focus target (rule 4) and a requester on an absent item is the dead-remote fault.
    LaunchedEffect(Unit) { runCatching { chipFocus.requestFocus() } }
    LaunchedEffect(picker) {
        if (picker == null) {
            kotlinx.coroutines.delay(32)
            runCatching { chipFocus.requestFocus() }
        }
    }
    LaunchedEffect(chosenSeason, chosenEpisode) {
        placedInList = false
        focused.value = null
        if (picker == null) runCatching { chipFocus.requestFocus() }
    }
    LaunchedEffect(visible.isNotEmpty(), viewModel, picker) {
        if (picker != null) return@LaunchedEffect
        if (placedInList || visible.isEmpty()) return@LaunchedEffect
        placedInList = true
        runCatching { listFocus.requestFocus() }
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
        findingRow = ranked
        finding = scope.launch {
            when (val result = client.playFlow.playRow(ranked.row, request)) {
                // The shell navigates to the player on `PlaybackSession.openRequests`.
                PlayResult.Opened -> finding = null
                PlayResult.ShowList -> finding = null
                is PlayResult.Failed -> {
                    finding = null
                    nav.toast(result.message)
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(TvColor.Canvas)) {
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
                    onPrevious = { chosenSeason = it.season; chosenEpisode = it.episode },
                    onNext = { chosenSeason = it.season; chosenEpisode = it.episode },
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
                        onEpisode = { chosenSeason = it.season; chosenEpisode = it.episode; picker = null },
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
                modifier = Modifier.focusRequester(chipFocus).focusRestorer(),
                onToggle = viewModel::toggle,
                onLeaveList = viewModel::applyPendingSort,
            )
            Spacer(Modifier.height(18.dp))

            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(StreamsGeometry.ListWidth).fillMaxHeight()) {
                    when {
                        state.noAddons -> NoAddons { nav.openSettings("addons") }
                        state.isError -> Failure(viewModel::start)
                        state.isEmpty -> Empty(viewModel::start) { nav.openSettings("addons") }
                        state.isFilteredEmpty -> FilteredEmpty(viewModel::clearFilters)
                        else -> SourceList(
                            rows = visible,
                            retainedSourceId = { retainedSourceId },
                            preferredLanguages = state.filters.languages,
                            pending = state.search.pending,
                            listModifier = Modifier.focusRequester(listFocus),
                            onFocused = {
                                focused.value = it
                                retainedSourceId = it?.row?.id
                                viewModel.onFocus(it?.row?.id)
                            },
                            onPlay = ::play,
                            onMenu = { menuRow = it },
                        )
                    }
                }
                PaneGutter()
                ReasonPane(selected = { focused.value }, pending = state.search.pending)
            }
            }
        }

        finding?.let { job ->
            FindingCard(row = findingRow, title = subtitle, phase = preparation, onCancel = { job.cancel(); finding = null; runCatching { listFocus.requestFocus() } })
        }

        menuRow?.let { ranked ->
            SourceOptions(ranked, onPlay = { menuRow = null; play(ranked) }, onClose = { menuRow = null })
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
) {
    val context = LocalContext.current
    val client = remember(context) { context.clientGraph }
    val preparation by client.playFlow.preparationLabel.collectAsState()
    val scope = rememberCoroutineScope()
    var chosenSeason by rememberSaveable(type, id, season, episode) { mutableStateOf(season) }
    var chosenEpisode by rememberSaveable(type, id, season, episode) { mutableStateOf(episode) }
    var browseSeason by rememberSaveable(type, id, season, episode) { mutableStateOf(season ?: episodes.firstOrNull()?.season ?: 1) }
    var picker by remember { mutableStateOf<EpisodePicker?>(null) }
    var retainedFilters by rememberSaveable(type, id, stateSaver = SourceFiltersSaver) { mutableStateOf(StreamFilters()) }
    var retainedSourceId by rememberSaveable(type, id) { mutableStateOf<String?>(null) }
    var menuRow by remember { mutableStateOf<RankedRow?>(null) }
    val originalTarget = chosenSeason == season && chosenEpisode == episode
    val viewModel = if (originalTarget && prefetched != null) prefetched else remember(type, id, chosenSeason, chosenEpisode) {
        StreamsViewModel(type, id, chosenSeason, chosenEpisode, client, scope, initialFilters = retainedFilters)
    }
    DisposableEffect(viewModel) { onDispose { if (viewModel !== prefetched) viewModel.stop() } }
    LaunchedEffect(viewModel) {
        if (viewModel === prefetched) viewModel.refreshIfStale()
    }
    val state by viewModel.state.collectAsState()
    LaunchedEffect(state.filters) { retainedFilters = state.filters }
    val rows = state.visible
    val drawerListState = rememberLazyListState()
    val filterFocus = remember { FocusRequester() }
    val listFocus = remember { FocusRequester() }
    var finding by remember { mutableStateOf<Job?>(null) }
    var findingRow by remember { mutableStateOf<RankedRow?>(null) }
    val chosenTitle = episodes.firstOrNull { it.season == chosenSeason && it.episode == chosenEpisode }?.title
    val currentSubtitle = if (chosenSeason != null && chosenEpisode != null) {
        listOfNotNull("S$chosenSeason E$chosenEpisode", chosenTitle).joinToString(" · ")
    } else subtitle

    fun play(ranked: RankedRow) {
        finding?.cancel()
        findingRow = ranked
        finding = scope.launch {
            when (val result = client.playFlow.playRow(
                ranked.row,
                PlayRequest(type, id, title, chosenSeason, chosenEpisode, posterUrl, backdropUrl, resumeFromMs.takeIf { originalTarget }),
            )) {
                PlayResult.Opened -> finding = null
                PlayResult.ShowList -> finding = null
                is PlayResult.Failed -> { finding = null; onToast(result.message) }
            }
        }
    }

    SidePanel(header = "Choose a source", onClose = {
        if (finding != null) { finding?.cancel(); finding = null } else if (picker != null) picker = null else onDismiss()
    }, width = 900.dp, slideIn = true, panelColor = TvColor.Elevated, closeOnLeft = false) {
        Text(title, style = TvType.ShelfHeader, color = TvColor.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        currentSubtitle?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = TvType.ControlLabel, color = TvColor.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(8.dp))
        Text(state.countChip, style = TvType.Meta, color = TvColor.TextSecondary)
        if (type == "series" && episodes.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            EpisodeNavigationBar(
                videos = episodes, season = chosenSeason, episode = chosenEpisode, picker = picker,
                onPrevious = { chosenSeason = it.season; chosenEpisode = it.episode },
                onNext = { chosenSeason = it.season; chosenEpisode = it.episode },
                onShowSeasons = { browseSeason = chosenSeason ?: browseSeason; picker = EpisodePicker.Seasons },
                onShowEpisodes = { browseSeason = chosenSeason ?: browseSeason; picker = EpisodePicker.Episodes },
            )
        }
        Spacer(Modifier.height(20.dp))
        if (picker != null) {
            EpisodePickerList(
                videos = episodes, picker = picker!!, browseSeason = browseSeason,
                selectedSeason = chosenSeason, selectedEpisode = chosenEpisode,
                onSeason = { browseSeason = it; picker = EpisodePicker.Episodes },
                onEpisode = { chosenSeason = it.season; chosenEpisode = it.episode; picker = null },
            )
        } else {
        FilterChips(state, Modifier.focusRequester(filterFocus).focusRestorer(), viewModel::toggle, viewModel::applyPendingSort)
        Spacer(Modifier.height(20.dp))
        when {
            state.noAddons -> DrawerEmpty("No stream add-ons are configured.", "Open Add-ons", onSettings)
            state.isError -> DrawerEmpty("Your add-ons did not answer.", "Try again", viewModel::start)
            state.isFilteredEmpty -> DrawerEmpty("No copies match these filters.", "Clear filters", viewModel::clearFilters)
            state.isEmpty -> DrawerEmpty("No copies found for this title.", "Try again", viewModel::start)
            else -> LazyColumn(
                state = drawerListState,
                modifier = Modifier.fillMaxWidth().fillMaxHeight().focusRestorer(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp, bottom = 44.dp),
            ) {
                items(rows, key = { it.row.id }) { ranked ->
                    StreamListRow(
                        row = ranked.row,
                        width = 820.dp,
                        reason = ranked.reasons.firstOrNull { it.caution }?.text ?: ranked.reasons.firstOrNull()?.text,
                        caution = ranked.cautions.isNotEmpty() || !ranked.eligible,
                        preferredLanguages = state.filters.languages,
                        onPlay = { play(ranked) },
                        onMenu = { menuRow = ranked },
                        modifier = Modifier
                            .then(if (ranked.row.id == (retainedSourceId?.takeIf { key -> rows.any { it.row.id == key } } ?: rows.firstOrNull()?.row?.id)) Modifier.focusRequester(listFocus) else Modifier)
                            .onFocusChanged {
                                if (it.isFocused) {
                                    retainedSourceId = ranked.row.id
                                    viewModel.onFocus(ranked.row.id)
                                }
                            },
                    )
                }
                if (state.search.pending > 0) item {
                    com.fourseveneightnine.tv.client.ui.components.Skeleton(
                        Modifier.width(820.dp).height(ROW_REASON_HEIGHT), sweep = rows.isEmpty(),
                    )
                }
            }
        }
        }
    }
    LaunchedEffect(rows.isNotEmpty(), picker, viewModel) {
        if (picker != null) return@LaunchedEffect
        if (rows.isNotEmpty()) {
            drawerListState.scrollToItem(rows.indexOfFirst { it.row.id == retainedSourceId }.coerceAtLeast(0))
            // The first result can arrive before LazyColumn has attached its focus node.
            repeat(10) {
                if (runCatching { listFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                kotlinx.coroutines.delay(50)
            }
        } else runCatching { filterFocus.requestFocus() }
    }
    menuRow?.let { ranked ->
        SourceOptions(ranked, onPlay = { menuRow = null; play(ranked) }, onClose = { menuRow = null })
    }
    finding?.let { job -> FindingCard(row = findingRow, title = "$title · ${currentSubtitle.orEmpty()}", phase = preparation) { job.cancel(); finding = null; runCatching { listFocus.requestFocus() } } }
    BackHandler {
        when {
            finding != null -> { finding?.cancel(); finding = null; runCatching { listFocus.requestFocus() } }
            menuRow != null -> menuRow = null
            picker != null -> picker = null
            else -> onDismiss()
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
        Text(message, style = TvType.Body, color = TvColor.TextSecondary)
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
            Text(detail, style = TvType.Meta.copy(fontSize = 21.5.sp), color = TvColor.TextSecondary, maxLines = 1)
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
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            // Focus reaching the chip row is focus leaving the list, which is when a held re-sort
            // is allowed to land in one step (§10.7).
            .onFocusChanged { if (it.hasFocus) onLeaveList() },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "all") {
            DrawerFilter("All", filters.isAll) { onToggle(StreamChip.All) }
        }
        item(key = "cached") {
            DrawerFilter("Cached", filters.cached, R.drawable.ic_source_bolt) { onToggle(StreamChip.Cached) }
        }
        item(key = "4k") {
            DrawerFilter("4K", filters.fourK, R.drawable.ic_source_4k) { onToggle(StreamChip.FourK) }
        }
        item(key = "1080p") {
            DrawerFilter("1080p", filters.fullHd, R.drawable.ic_source_hd) { onToggle(StreamChip.FullHd) }
        }
        items(state.languageChips, key = { "lang:$it" }) { language ->
            DrawerFilter(language, language in filters.languages) { onToggle(StreamChip.Language(language)) }
        }
        if (filters.otherLanguages || StreamFilterRules.hasOtherLanguages(state.search.rows, state.languageChips)) {
            item(key = "other") {
                DrawerFilter("Other", filters.otherLanguages) {
                    onToggle(StreamChip.Other)
                }
            }
        }
        if (filters.unknownLanguage || StreamFilterRules.hasUnknownLanguages(state.search.rows)) {
            item(key = "unknown") { DrawerFilter("Unknown language", filters.unknownLanguage) { onToggle(StreamChip.Unknown) } }
        }
    }
}

@Composable
private fun SourceList(
    rows: List<RankedRow>,
    retainedSourceId: () -> String?,
    preferredLanguages: Set<String>,
    pending: Int,
    listModifier: Modifier,
    onFocused: (RankedRow?) -> Unit,
    onPlay: (RankedRow) -> Unit,
    onMenu: (RankedRow) -> Unit,
) {
    // Keep every edge of the focused outline clear of the list viewport.
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val entryFocus = remember { FocusRequester() }
    val entryIndex = rows.indexOfFirst { it.row.id == retainedSourceId() }.coerceAtLeast(0)
    LaunchedEffect(rows.isNotEmpty()) {
        if (rows.isNotEmpty()) { listState.scrollToItem(entryIndex); withFrameNanos { }; runCatching { entryFocus.requestFocus() } }
    }
    Box(Modifier.fillMaxHeight().width(StreamsGeometry.ListWidth)) {
        LazyColumn(
            state = listState,
            modifier = listModifier
                .fillMaxHeight()
                .width(StreamsGeometry.ListWidth)
                .focusRestorer(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp, bottom = 24.dp),
        ) {
            // Keyed by row id, so live arrival keeps focus on the row a viewer was reading (§10.7).
            items(rows, key = { it.row.id }) { ranked ->
                StreamListRow(
                    row = ranked.row,
                    preferredLanguages = preferredLanguages,
                    reason = ranked.cautions.firstOrNull()?.text ?: if (!ranked.eligible) "Outside your playback rules" else null,
                    caution = ranked.cautions.isNotEmpty() || !ranked.eligible,
                    onPlay = { onPlay(ranked) },
                    onMenu = { onMenu(ranked) },
                    modifier = Modifier
                        .then(if (ranked == rows.getOrNull(entryIndex)) Modifier.focusRequester(entryFocus) else Modifier)
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                        .onFocusChanged { if (it.isFocused) onFocused(ranked) },
                )
            }
            // §10.9 Partial: real rows plus two skeletons at the tail while add-ons are pending.
            if (pending > 0) {
                items(listOf(0, 1), key = { "skeleton:$it" }) { index ->
                    StreamSkeletonRow(sweep = rows.isEmpty() && index < 2)
                }
            }
        }

    }

}

@Composable
private fun FindingCard(row: RankedRow?, title: String, phase: String?, onCancel: () -> Unit) {
    val focus = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(TvColor.Canvas.copy(alpha = 0.6f)), Alignment.Center) {
        Column(
            modifier = Modifier
                .width(840.dp)
                .clip(TvShape.Panel)
                .background(TvColor.Elevated)
                .border(1.dp, TvColor.Border, TvShape.Panel)
                .padding(horizontal = 40.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(phase ?: if (row?.row?.needsResolve == true) "Resolving source" else "Opening source", style = TvType.ShelfHeader, color = TvColor.TextPrimary)
            Spacer(Modifier.height(12.dp))
            Text(
                title,
                style = TvType.CardTitle,
                color = TvColor.TextSecondary,
                maxLines = 1,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Text(listOfNotNull(row?.row?.addonName, row?.row?.cachedHint?.service).joinToString(" · "), style = TvType.Meta, color = TvColor.TextMuted)
            Spacer(Modifier.height(TvSpace.S))
            TvButton(
                "Cancel",
                onCancel,
                kind = ButtonKind.Ghost,
                height = TvGeom.ButtonHeightDense,
                modifier = Modifier.width(280.dp).focusRequester(focus),
            )
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

// ---------------------------------------------------------------------------- states, §10.9

@Composable
private fun Empty(onRetry: () -> Unit, onAddons: () -> Unit) {
    val focus = remember { FocusRequester() }
    StateBlock(
        headline = "No sources for this title",
        line = "Try again, or check your add-ons in Settings.",
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
private fun Failure(onRetry: () -> Unit) {
    val focus = remember { FocusRequester() }
    StateBlock(
        headline = "Couldn't reach your add-ons",
        line = "Check the TV's network, then try again.",
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
    save = { listOf(it.cached, it.fourK, it.fullHd, it.otherLanguages, it.unknownLanguage) + it.languages.sorted() },
    restore = { StreamFilters(cached = it[0] as Boolean, fourK = it[1] as Boolean, fullHd = it[2] as Boolean,
        otherLanguages = it[3] as Boolean, unknownLanguage = it[4] as Boolean, languages = it.drop(5).map { value -> value as String }.toSet()) },
)

@Composable
private fun SourceOptions(ranked: RankedRow, onPlay: () -> Unit, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    SidePanel("Source details", onClose = onClose, width = 760.dp) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 40.dp)) {
            item { SidePanelRow("Play this source", false, onPlay, Modifier.focusRequester(first)) }
            item { Text(ranked.row.releaseName.ifBlank { UNNAMED }, style = TvType.Body, color = TvColor.TextPrimary) }
            items(ranked.reasons.sortedByDescending { it.caution }) { reason ->
                Text((if (reason.caution) "! " else "") + reason.text, style = TvType.Body,
                    color = if (reason.caution) TvColor.Warning else TvColor.TextSecondary)
            }
            items(com.fourseveneightnine.tv.client.data.streams.StreamRanker.facts(ranked.row)) { (label, value) ->
                Text("$label · $value", style = TvType.Meta, color = TvColor.TextSecondary)
            }
            item { Text("Cache information is an add-on claim. It does not confirm compatibility.", style = TvType.Meta, color = TvColor.TextMuted) }
            item { SidePanelRow("Back to sources", false, onClose) }
        }
    }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
}
