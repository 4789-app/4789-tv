@file:Suppress("OPT_IN_USAGE")
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.fourseveneightnine.tv.client.ui.screens.discover

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import com.fourseveneightnine.tv.client.ui.components.benchmarkDraw
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics
import com.fourseveneightnine.tv.client.ui.components.TvSearchField
import com.fourseveneightnine.tv.client.ui.components.ContentTransition
import com.fourseveneightnine.tv.client.ui.components.PanelTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.tv.material3.Text
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fourseveneightnine.tv.R
import com.fourseveneightnine.tv.catalog.TVLetterboxdClient
import com.fourseveneightnine.tv.client.appGraph
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.home.LauncherRails
import com.fourseveneightnine.tv.client.ui.LocalShellState
import com.fourseveneightnine.tv.client.ui.components.EmptyState
import com.fourseveneightnine.tv.client.ui.components.CatalogAction
import com.fourseveneightnine.tv.client.ui.components.CatalogActionRail
import com.fourseveneightnine.tv.client.ui.components.CatalogRailFocus
import com.fourseveneightnine.tv.client.ui.components.CatalogRailGutter
import com.fourseveneightnine.tv.client.ui.components.StateBlock
import com.fourseveneightnine.tv.client.ui.nav.ClientNav
import com.fourseveneightnine.tv.client.ui.screens.collections.rememberLoveTitle
import com.fourseveneightnine.tv.client.ui.screens.collections.CollectionCandidate
import com.fourseveneightnine.tv.client.ui.screens.collections.filterCollectionSearch
import com.fourseveneightnine.tv.client.ui.screens.detail.DetailPreview
import com.fourseveneightnine.tv.client.ui.screens.home.HomeCard
import com.fourseveneightnine.tv.client.ui.screens.home.HomePosterCard
import com.fourseveneightnine.tv.client.ui.screens.home.RowGap
import com.fourseveneightnine.tv.client.ui.screens.home.TvCardRow
import com.fourseveneightnine.tv.client.ui.screens.settings.SettingsKeys
import com.fourseveneightnine.tv.client.ui.theme.LocalReduceMotion
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvGeom
import com.fourseveneightnine.tv.client.ui.theme.TvMotion
import com.fourseveneightnine.tv.client.ui.theme.TvType
import com.fourseveneightnine.tv.ui.throttleDpadRepeats
import kotlin.math.roundToInt

/**
 * Discover, spec §4. One catalog at a time, filtered by type and genre.
 *
 * The screen owns LEFT for the rail the same way Home does — and for the same reason. It reaches
 * this frame only when the grid did not answer it, which is exactly at column 1.
 */
@Composable
internal fun DiscoverScreen(
    nav: ClientNav,
    openAllCatalogs: Boolean = false,
    onAllCatalogsOpened: () -> Unit = {},
    visible: Boolean = true,
) {
    val context = LocalContext.current
    val client = remember(context) { context.clientGraph }
    val preferences = remember(context) { context.appGraph.presentationPreferences }
    val rowsVariant = remember(preferences, visible) {
        preferences.getBoolean(SettingsKeys.DISCOVER_ROWS, false)
    }
    // The NavHost entry owns this model while Detail is above it. Catalog keys can contain
    // credentialed manifest URLs, so keep the state in memory rather than a saved-state Bundle.
    val viewModel: DiscoverViewModel = viewModel { DiscoverViewModel(client, preferences).also { it.start() } }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val services by client.services.collectAsStateWithLifecycle()
    var usernameOverride by remember(preferences, visible) {
        mutableStateOf(preferences.getString(SettingsKeys.LETTERBOXD_LIKES_USERNAME, null))
    }
    val username = usernameOverride ?: services?.document?.letterboxdUsernames?.firstOrNull().orEmpty()
    var filters by rememberCatalogFilters(client, state.selectedCatalogKey)
    var titleQuery by rememberSaveable(state.selectedCatalogKey) { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val actions = remember(state.selectedCatalogKey) { CatalogRailFocus() }
    var filtersFromActions by remember { mutableStateOf(false) }
    val useRowsVariant = rowsVariant && !viewModel.directoryActive && state.selected?.likedList == null &&
        !filters.active && titleQuery.isEmpty()
    val shell = LocalShellState.current
    val reduceMotion = LocalReduceMotion.current

    val chipFocuses = remember { List(DiscoverType.entries.size + 3) { FocusRequester() } }
    var focusedChip by rememberSaveable { mutableStateOf(state.type.ordinal) }
    val chipFocus = chipFocuses[focusedChip]
    var headerFocused by remember { mutableStateOf(false) }
    var contentFocused by remember { mutableStateOf(false) }
    var contentWasLastFocused by remember { mutableStateOf(viewModel.lastContentCardKey != null) }
    var returnCardKey by remember { mutableStateOf(viewModel.detailReturnCardKey) }
    var returnRailKey by remember { mutableStateOf(viewModel.detailReturnRailKey) }

    var panel by remember { mutableStateOf<ChipSlot?>(if (viewModel.directoryOpen) ChipSlot.Catalog else null) }
    val browse = rememberCatalogBrowse(state.items, client, filters, enabled = filters.active || panel == ChipSlot.Genre, scopeKey = state.selectedCatalogKey.orEmpty(), benchmarkSurface = "discover")
    val visibleItems = remember(browse.items, titleQuery) {
        browse.items.filterCollectionSearch(titleQuery, HomeCard::title)
    }
    val showActions = !useRowsVariant && state.selected != null
    val catalogListState = rememberLazyListState()
    val likedListState = rememberLazyListState()
    LaunchedEffect(panel == ChipSlot.Letterboxd, username) {
        if (panel == ChipSlot.Letterboxd) viewModel.refreshLikedLists(username)
    }

    val allCatalogTypes = viewModel.directoryFromHome
    LaunchedEffect(openAllCatalogs) {
        if (openAllCatalogs) {
            viewModel.directoryActive = true
            viewModel.directoryFromHome = true
            viewModel.directoryOpen = true
            focusedChip = DiscoverType.entries.size
            panel = ChipSlot.Catalog
            onAllCatalogsOpened()
        }
    }
    val loveTitle = rememberLoveTitle(nav)
    val focusedSummary = remember { mutableStateOf<HomeCard?>(null) }
    val contentFocus = remember { FocusRequester() }
    fun openCard(card: HomeCard, railKey: String? = null) {
        viewModel.detailReturnCardKey = card.key
        viewModel.detailReturnRailKey = railKey
        DetailPreview.stage(card)
        nav.openDetail(card.type, card.id)
    }
    fun finishContentReturn(restored: Boolean) {
        viewModel.detailReturnCardKey = null
        viewModel.detailReturnRailKey = null
        returnCardKey = null
        returnRailKey = null
        if (!restored) runCatching { chipFocus.requestFocus() }
    }
    LaunchedEffect(visible) {
        if (!visible || returnCardKey != null) return@LaunchedEffect
        val target = viewModel.detailReturnCardKey ?: return@LaunchedEffect
        if (shell.restorePosterFocus(target)) {
            finishContentReturn(true)
        } else {
            returnCardKey = target
            returnRailKey = viewModel.detailReturnRailKey
        }
    }
    fun loveCard(card: HomeCard) { loveTitle(card.toCandidate()) }
    val scope = rememberCoroutineScope()
    val screenVisible by rememberUpdatedState(visible)
    var filmLookup by remember { mutableStateOf<Job?>(null) }
    fun useGridCard(card: HomeCard, save: Boolean) {
        filmLookup?.cancel()
        if (!save) {
            openCard(card)
            return
        }
        if (!card.id.startsWith(TVLetterboxdClient.FILM_ID_PREFIX)) {
            loveCard(card)
            return
        }
        val catalogKey = state.selectedCatalogKey
        filmLookup = scope.launch {
            val lookup = runCatching { client.resolveLetterboxdCard(card) }
                .onFailure { if (it is CancellationException) throw it }
            if (!screenVisible || panel != null || viewModel.state.value.selectedCatalogKey != catalogKey) return@launch
            val resolved = lookup.getOrNull()
            if (resolved == null) {
                nav.toast(TVLetterboxdClient.titleLookupMessage(lookup.exceptionOrNull()))
            } else {
                loveCard(resolved)
            }
        }
    }
    var wantsChipFocus by remember { mutableStateOf(false) }
    // UP from grid row 0 has to expand the band BEFORE it can ask for chip focus. Without this the
    // band stays collapsed because the grid still holds focus, the chips are never composed, and
    // the focus request lands on a requester with no node behind it — so UP did nothing at all.
    var forceExpanded by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()

    // The rail borrows focus and must be able to give it back (spec §1.5).
    DisposableEffect(visible) {
        if (!visible) return@DisposableEffect onDispose {}
        val restore = {
            if (contentWasLastFocused && viewModel.lastContentCardKey != null) {
                returnCardKey = viewModel.lastContentCardKey
                returnRailKey = viewModel.lastContentRailKey
            } else { forceExpanded = true; wantsChipFocus = true }
            Unit
        }
        shell.restoreContentFocus = restore
        onDispose { if (shell.restoreContentFocus === restore) shell.restoreContentFocus = null }
    }
    // Initial focus: the selected Type chip, a node that is always composed (plan §7.4 rule 2).
    // Placed once, on entry. Settings landing later must never pull the ring back out of the grid.
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (placed) return@LaunchedEffect
        placed = true
        // A cold model has no return target. Normal Back lets the exact lazy cell restore itself.
        if (returnCardKey == null && panel == null) runCatching { chipFocus.requestFocus() }
    }
    // Asking for chip focus in the same frame the band expands would hit a requester that is not
    // attached yet, so the request waits one composition.
    LaunchedEffect(useRowsVariant) {
        if (useRowsVariant) viewModel.enableRails()
    }

    val collapsed = contentWasLastFocused && !forceExpanded && !useRowsVariant && panel == null && !searching
    // The offset is animated in pixels and read inside `offset { }`, which runs in the layout pass
    // and never invalidates composition. The `Dp` overload read the value in the composable body,
    // so every frame of the 180 ms collapse re-measured a grid holding thirty-odd cells.
    val density = LocalDensity.current
    val bandTop = animateFloatAsState(
        targetValue = with(density) { DiscoverPlan.gridTopDp(collapsed).dp.toPx() },
        animationSpec = tween(if (reduceMotion) 0 else TvMotion.ScrollMillis, easing = TvMotion.Std),
        label = "discoverBand",
    )
    // The height is taken from the settled state, not the tween, so the grid ends at the safe
    // bottom and is re-measured once per collapse rather than once per frame.
    val gridHeight = DiscoverPlan.gridHeightDp(collapsed).dp
    val countLine = (if (titleQuery.isNotEmpty()) "${visibleItems.size} matching titles"
        else if (filters.active) browse.status(state.hasMore)
        else DiscoverPlan.countLine(state.loading, state.items.size, state.hasMore)
    )?.let { if (visibleItems.isNotEmpty() && showActions) "$it · Right for catalog actions" else it }
    if (com.fourseveneightnine.tv.BuildConfig.BENCHMARK_LOGGING) {
        LaunchedEffect(visible, state.loading, state.failed, visibleItems.size, browse.updating) {
            if (visible) ReceiverDiagnostics.benchmark("discover.content") {
                "loading=${state.loading} failed=${state.failed} rows=${visibleItems.size} projecting=${browse.updating}"
            }
        }
    }
    val retryFocus = remember { FocusRequester() }
    val emptyFocus = remember { FocusRequester() }
    val focusEmptyAction = !searching && DiscoverPlan.shouldFocusEmptyAction(
        settingsLoaded = state.settingsLoaded,
        loading = state.loading,
        failed = state.failed,
        itemCount = visibleItems.size,
        hasGenre = state.genre != null || filters.active || titleQuery.isNotEmpty(),
        savedList = state.selected?.isLocal == true,
    )

    LaunchedEffect(wantsChipFocus, collapsed, focusedChip, panel) {
        if (!wantsChipFocus || collapsed || panel != null) return@LaunchedEffect
        withFrameNanos { }
        repeat(10) {
            if (runCatching { chipFocus.requestFocus() }.getOrDefault(false)) {
                wantsChipFocus = false
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(50)
        }
    }
    // The band collapses again on its own once the ring is back in the grid.
    // Spec §4.9: the error state's Retry button is focused. Without this the screen has nothing
    // focusable under the chips and the shell's rescue net opens the rail on its own.
    LaunchedEffect(state.failed, panel) {
        if (state.failed && panel == null && contentWasLastFocused && !wantsChipFocus) runCatching { retryFocus.requestFocus() }
    }
    // Spec §4.9: once a filtered catalog settles empty, its recovery action is the only useful
    // target below the chips. Request after composition so the button's requester is attached.
    LaunchedEffect(focusEmptyAction, panel) {
        if (focusEmptyAction && panel == null && contentWasLastFocused && !wantsChipFocus) runCatching { emptyFocus.requestFocus() }
    }
    // A new catalog is a new list. Keeping the old scroll offset leaves the viewer looking at row
    // fourteen of something they have never seen, with the first row cut off above the band.
    val selection = listOf(state.selectedCatalogKey, state.type, state.genre)
    var previousSelection by remember { mutableStateOf(selection) }
    LaunchedEffect(selection) {
        if (previousSelection != selection) {
            runCatching { gridState.scrollToItem(0) }
            previousSelection = selection
        }
    }

    fun resetCatalogFilters() {
        filters = CatalogFilters()
        focusedChip = DiscoverType.entries.size + 1
        forceExpanded = true
        wantsChipFocus = true
    }

    fun closeFilterPanel() {
        val closingPanel = panel
        panel = null
        viewModel.directoryOpen = false
        if (closingPanel == ChipSlot.Genre && filtersFromActions) {
            filtersFromActions = false
            scope.launch { withFrameNanos { }; actions.restoreAction() }
            return
        }
        if (closingPanel == ChipSlot.Letterboxd) viewModel.likedCatalogActive = false
        if (closingPanel == ChipSlot.Catalog && viewModel.directoryActive) {
            viewModel.directoryActive = false
            if (viewModel.directoryFromHome) {
                viewModel.directoryFromHome = false
                nav.back()
                return
            }
        }
        forceExpanded = true
        wantsChipFocus = true
    }
    BackHandler(enabled = visible && (panel != null || viewModel.directoryActive || viewModel.likedCatalogActive)) {
        when {
            panel != null -> closeFilterPanel()
            viewModel.likedCatalogActive -> {
                filmLookup?.cancel()
                focusedChip = DiscoverType.entries.size + 2
                panel = ChipSlot.Letterboxd
            }
            viewModel.directoryActive -> {
                focusedChip = DiscoverType.entries.size
                viewModel.directoryOpen = true
                panel = ChipSlot.Catalog
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(TvColor.Background)
            .throttleDpadRepeats()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> if (contentFocused || (headerFocused && focusedChip == 0)) {
                        nav.openRail(); true
                    } else false
                    Key.Menu -> { nav.openRail(); true }
                    else -> false
                }
            }
            .padding(start = TvGeom.ContentLeft, top = 54.dp, end = 96.dp),
    ) {
        // Spec §4.8: the band collapses over 180 ms, so its text may not swap in one frame.
        Crossfade(
            targetState = collapsed,
            animationSpec = tween(if (reduceMotion) 0 else TvMotion.ScrollMillis, easing = TvMotion.Std),
            label = "discoverBandText",
        ) { isCollapsed ->
            if (isCollapsed) {
                CollapsedChipsBand(
                    focusedCard = { focusedSummary.value },
                    parts = DiscoverPlan.summaryParts(
                        type = state.type,
                        catalogName = state.selected?.catalogName,
                        genre = filters.label.takeIf { filters.active } ?: state.genre,
                        countLine = countLine,
                    ),
                )
            } else {
                Box(Modifier.fillMaxWidth()) {
                    Text("Discover", style = TvType.ScreenTitle, color = TvColor.TextPrimary, maxLines = 1)
                    DiscoverChips(
                        state = state,
                        filterLabel = filters.label,
                        filtersActive = filters.active,
                        onType = {
                            focusedChip = it.ordinal
                            viewModel.likedCatalogActive = false
                            viewModel.selectType(it)
                        },
                        onOpenCatalogPanel = { focusedChip = DiscoverType.entries.size; panel = ChipSlot.Catalog },
                        onOpenGenrePanel = { focusedChip = DiscoverType.entries.size + 1; panel = ChipSlot.Genre },
                        onOpenLetterboxd = { focusedChip = DiscoverType.entries.size + 2; panel = ChipSlot.Letterboxd },
                        chipFocuses = chipFocuses,
                        onFocused = { if (!wantsChipFocus && panel == null) focusedChip = it },
                        modifier = Modifier.offset(y = 62.dp).onFocusChanged {
                            headerFocused = it.hasFocus
                            if (it.hasFocus) {
                                contentWasLastFocused = false
                                forceExpanded = false
                            }
                        },
                    )
                    // Spec §4.2: the count line sits at y 182, which is 128 below the safe top.
                    if (countLine != null) {
                        Text(
                            countLine,
                            style = TvType.data(20),
                            color = TvColor.TextSecondary,
                            maxLines = 1,
                            modifier = Modifier.offset(y = 128.dp),
                        )
                    }
                }
            }
        }

        if (searching) TvSearchField("Search titles", titleQuery, { titleQuery = it },
            modifier = Modifier.offset(x = 240.dp).width(560.dp).focusProperties { down = contentFocus },
            openKeyboard = true, enabled = visible && panel == null, onClose = {
                searching = false
                scope.launch { withFrameNanos { }; actions.restoreAction() }
            })

        ContentTransition(
            targetKey = listOf(state.selectedCatalogKey, state.genre, state.loading, filters),
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = if (showActions) CatalogRailGutter else 0.dp)
                .height(gridHeight)
                .offset { IntOffset(0, bandTop.value.roundToInt()) }
                .focusRequester(contentFocus)
                .onFocusChanged {
                    contentFocused = it.hasFocus
                    if (it.hasFocus) contentWasLastFocused = true
                }
                .benchmarkDraw("discover_content", visible, System.identityHashCode(visibleItems))
                .then(if (returnCardKey == null) Modifier.focusRestorer() else Modifier)
                .focusGroup(),
        ) {
            when {
                !state.settingsLoaded && state.selected?.isLocal != true -> EmptyState(
                    headline = "No titles in this catalog",
                    line = "Add sources in Settings → Setup to load your catalogs.",
                )
                state.failed -> StateBlock(
                    headline = "Couldn't load this catalog",
                    modifier = Modifier.benchmarkDraw("discover_catalog_error"),
                    line = state.failureMessage ?: "The add-on couldn't load this catalog.",
                    actionLabel = "Retry",
                    onAction = viewModel::retry,
                    secondaryLabel = "Pick another catalog",
                    onSecondary = { focusedChip = DiscoverType.entries.size; panel = ChipSlot.Catalog },
                    actionModifier = Modifier.focusRequester(retryFocus),
                    announceAsError = true,
                )
                // Keep the grid's geometry while loading. Old catalog cards must stop accepting
                // actions immediately; the bounded content fade bridges the replacement.
                state.loading && state.items.isEmpty() -> GridSkeleton()
                state.selected?.collectionId != null && state.items.isEmpty() -> EmptyState(
                    headline = "No saved titles here",
                    line = "Hold Select on a title to add it to Favorites or Watchlist.",
                    actionLabel = "Pick another catalog",
                    onAction = { focusedChip = DiscoverType.entries.size; panel = ChipSlot.Catalog },
                    actionModifier = Modifier.focusRequester(emptyFocus),
                )
                state.selected?.shelfId != null && state.items.isEmpty() -> EmptyState(
                    headline = "No titles saved for this list",
                    line = "Try Settings → Catalog refresh, or send the list from your iPhone.",
                    actionLabel = "Pick another catalog",
                    onAction = { focusedChip = DiscoverType.entries.size; panel = ChipSlot.Catalog },
                    actionModifier = Modifier.focusRequester(emptyFocus),
                )
                titleQuery.isNotEmpty() && visibleItems.isEmpty() -> StateBlock(
                    headline = "No matching titles",
                    line = "Search matches titles loaded in this catalog.",
                    actionLabel = if (state.hasMore) "Load more titles" else "Clear search",
                    onAction = if (state.hasMore) viewModel::loadNextPage else ({ titleQuery = "" }),
                    secondaryLabel = if (state.hasMore) "Clear search" else null,
                    onSecondary = if (state.hasMore) ({ titleQuery = "" }) else null,
                    actionModifier = Modifier.focusRequester(emptyFocus),
                )
                filters.active && visibleItems.isEmpty() -> StateBlock(
                    headline = if ((browse.pending > 0 || browse.updating)) "Checking title metadata…" else "No matching titles",
                    line = browse.status(state.hasMore),
                    actionLabel = if (state.hasMore) "Load more titles" else "Reset filters",
                    onAction = if (state.hasMore) ({ viewModel.loadNextPage() }) else ({ resetCatalogFilters() }),
                    secondaryLabel = if (state.hasMore) "Reset filters" else "Edit filters",
                    onSecondary = if (state.hasMore) ({ resetCatalogFilters() }) else ({ focusedChip = DiscoverType.entries.size + 1; panel = ChipSlot.Genre }),
                    actionModifier = Modifier.focusRequester(emptyFocus),
                )
                useRowsVariant -> DiscoverRails(
                    viewModel = viewModel,
                    onCardFocused = { focusedSummary.value = it },
                    onOpen = { card, railKey -> openCard(card, railKey) },
                    returnCardKey = returnCardKey,
                    returnRailKey = returnRailKey,
                    onReturnFinished = ::finishContentReturn,
                    onLongOpen = { card, _ -> loveCard(card) },
                )
                state.items.isEmpty() -> EmptyState(
                    headline = "No titles in this catalog",
                    line = "Try another genre, or pick a different catalog.",
                    actionLabel = if (state.genre != null) "Clear genre" else null,
                    onAction = if (state.genre != null) ({ viewModel.selectGenre(null) }) else null,
                    actionModifier = Modifier.focusRequester(emptyFocus),
                )
                else -> PosterGrid(
                    items = visibleItems,
                    gridState = gridState,
                    onFocusedIndex = { index ->
                        viewModel.maybeLoadMore(index, visibleItems.size)
                        visibleItems.getOrNull(index)?.let { focusedSummary.value = it; viewModel.lastContentCardKey = it.key; viewModel.lastContentRailKey = null }
                    },
                    onClick = { useGridCard(it, save = false) },
                    returnFocusKey = returnCardKey,
                    onReturnFinished = ::finishContentReturn,
                    onLongClick = { useGridCard(it, save = true) },
                    trailingPlaceholders = if (state.loadingMore) 3 else 0,
                    actions = actions.takeIf { showActions && panel == null },
                    onFiltersFocus = {
                        focusedChip = DiscoverType.entries.size + 1
                        forceExpanded = true
                        wantsChipFocus = true
                        true
                    },
                )
            }
        }

        if (showActions) {
            val choice = requireNotNull(state.selected)
            CatalogActionRail(
                actions,
                actions = buildList {
                    add(CatalogAction(R.drawable.ic_rail_search, if (titleQuery.isEmpty()) "Search titles" else "Search: $titleQuery") {
                        searching = true
                    })
                    add(CatalogAction(R.drawable.ic_catalog_filter, "Filters & sort") {
                        filtersFromActions = true
                        panel = ChipSlot.Genre
                    })
                    add(CatalogAction(R.drawable.ic_catalog_pin, "Add to Den TV Home") {
                        when {
                            choice.collectionId != null -> LauncherRails.collection(context, choice.collectionId, nav::toast)
                            choice.shelfId != null -> LauncherRails.snapshot(context, choice.shelfId, nav::toast)
                            else -> LauncherRails.catalog(context, choice.key, state.genre, nav::toast)
                        }
                    })
                    if (choice.collectionId == null) add(CatalogAction(R.drawable.ic_catalog_refresh, "Refresh catalog", !state.loading && !state.loadingMore) {
                        viewModel.refreshCatalog()
                    })
                },
                onLeave = { key ->
                    if (key != null && visibleItems.any { it.key == key }) returnCardKey = key
                    else runCatching { if (!contentFocus.requestFocus()) chipFocus.requestFocus() }
                },
                modifier = Modifier.align(Alignment.CenterEnd),
                enabled = visible && panel == null && !searching,
            )
        }

        PanelTransition(panel) { open -> when (open) {
            ChipSlot.Catalog -> CatalogPanel(
                query = viewModel.directoryQuery,
                onQuery = { viewModel.directoryQuery = it },
                source = viewModel.directorySource,
                onSource = { viewModel.directorySource = it },
                listState = catalogListState,
                focusKey = viewModel.directoryFocusKey,
                onFocused = { viewModel.directoryFocusKey = it },
                choices = DiscoverPlan.catalogPanelChoices(
                    state.catalogs.filter { it.likedList == null && it.phoneProfiles == null }, state.type, viewModel.directorySource, allCatalogTypes,
                ),
                selectedKey = state.selectedCatalogKey,
                showTypes = allCatalogTypes,
                active = panel == ChipSlot.Catalog,
                homeActionFor = { choice ->
                    val uri = when {
                        choice.collectionId != null -> LauncherRails.collectionUri(context, choice.collectionId)
                        choice.shelfId != null -> LauncherRails.snapshotUri(context, choice.shelfId)
                        else -> LauncherRails.catalogUri(context, choice.key, choice.requiredGenre)
                    }
                    if (uri == null) {
                        nav.toast("Den TV Home can show collections from profiles without a PIN.")
                        null
                    } else {
                        val pinned = LauncherRails.pinnedRows(context, uri)
                        if (pinned.isEmpty()) CatalogHomeAction("Add to Den TV Home") {
                            LauncherRails.pin(context, uri, nav::toast)
                        } else CatalogHomeAction("Remove from Den TV Home") {
                            LauncherRails.remove(context, pinned)
                            nav.toast("Removed from Den TV Home.")
                        }
                    }
                },
                onPick = pick@{ key ->
                    val choice = state.catalogs.firstOrNull { it.key == key }
                    if (choice?.isWatchlist == true && choice.collectionId != null) {
                        viewModel.directoryOpen = true
                        nav.openWatchlist(choice.collectionId.toString(), choice.type)
                        return@pick
                    }
                    panel = null
                    viewModel.directoryOpen = false
                    viewModel.directoryActive = true
                    wantsChipFocus = true
                    viewModel.likedCatalogActive = false
                    viewModel.selectCatalog(key)
                },
                onClose = ::closeFilterPanel,
            )
            ChipSlot.Letterboxd -> LetterboxdLikedPanel(
                username = username,
                loading = viewModel.likedIndexLoading,
                message = viewModel.likedIndexMessage,
                choices = state.catalogs,
                selectedKey = state.selectedCatalogKey,
                listState = likedListState,
                active = panel == ChipSlot.Letterboxd,
                onSetUsername = { value ->
                    preferences.edit().putString(SettingsKeys.LETTERBOXD_LIKES_USERNAME, value).apply()
                    usernameOverride = value
                },
                onRefresh = { viewModel.refreshLikedLists(username, force = true) },
                onPick = {
                    viewModel.likedCatalogActive = true
                    viewModel.selectCatalog(it)
                    panel = null
                    wantsChipFocus = true
                },
                onClose = ::closeFilterPanel,
            )
            ChipSlot.Genre -> CatalogFilterPanel(
                filters = filters,
                browse = browse,
                providerGenres = state.genres,
                providerGenre = state.genre,
                onProviderGenre = viewModel::selectGenre,
                allowAllProviderGenres = state.selected?.requiredGenre == null,
                onChange = { filters = it },
                onClose = ::closeFilterPanel,
                hasMore = state.hasMore,
                loadingMore = state.loadingMore,
                onLoadMore = viewModel::loadNextPage,
                active = visible && panel == ChipSlot.Genre,
            )
            else -> Unit
        } }
    }

}

/** Spec §4.6: rail geometry matches Home exactly, and the chips band does not collapse. */
@Composable
private fun DiscoverRails(
    viewModel: DiscoverViewModel,
    onCardFocused: (HomeCard) -> Unit,
    onOpen: (HomeCard, String) -> Unit,
    onLongOpen: (HomeCard, String) -> Unit,
    returnCardKey: String?,
    returnRailKey: String?,
    onReturnFinished: (Boolean) -> Unit,
) {
    val rails by viewModel.rails.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    // Hoisted out of the content lambda: filtering there allocated a fresh list on every
    // recomposition of the column, which is once per focus move.
    val visibleRails = remember(rails) { rails.filter { it.items.isNotEmpty() } }
    LaunchedEffect(returnCardKey, returnRailKey, visibleRails) {
        if (returnCardKey == null) return@LaunchedEffect
        val index = visibleRails.indexOfFirst { it.key == returnRailKey && it.items.any { card -> card.key == returnCardKey } }
        if (index < 0) { onReturnFinished(false); return@LaunchedEffect }
        if (listState.layoutInfo.visibleItemsInfo.none { it.key == returnRailKey }) listState.scrollToItem(index)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
    ) {
        items(visibleRails, key = { it.key }) { rail ->
            val rowState = rememberLazyListState()
            val returnFocus = remember { FocusRequester() }
            val restoresHere = returnCardKey != null && rail.key == returnRailKey
            LaunchedEffect(restoresHere, returnCardKey) {
                if (!restoresHere) return@LaunchedEffect
                val index = rail.items.indexOfFirst { it.key == returnCardKey }
                if (index < 0) { onReturnFinished(false); return@LaunchedEffect }
                if (rowState.layoutInfo.visibleItemsInfo.none { it.key == returnCardKey }) rowState.scrollToItem(index)
                snapshotFlow { rowState.layoutInfo.visibleItemsInfo.any { it.key == returnCardKey } }.first { it }
                withFrameNanos { }
                onReturnFinished(runCatching { returnFocus.requestFocus() }.getOrDefault(false))
            }
            Column {
                TvCardRow(
                    title = rail.title,
                    items = rail.items,
                    key = { it.key },
                    rowState = rowState,
                    restoreFocus = !restoresHere,
                ) { _, card ->
                    HomePosterCard(
                        card = card,
                        onClick = { onOpen(card, rail.key) },
                        onLongClick = { onLongOpen(card, rail.key) },
                        onFocused = { onCardFocused(card); viewModel.lastContentCardKey = card.key; viewModel.lastContentRailKey = rail.key },
                        modifier = if (restoresHere && card.key == returnCardKey) Modifier.focusRequester(returnFocus) else Modifier,
                    )
                }
                Spacer(Modifier.height(RowGap))
            }
        }
    }
}

internal fun HomeCard.toCandidate(): CollectionCandidate =
    CollectionCandidate(canonicalId = id, mediaType = type, title = title, posterUrl = posterUrl)
