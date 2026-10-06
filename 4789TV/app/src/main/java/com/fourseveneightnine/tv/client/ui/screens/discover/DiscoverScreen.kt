@file:Suppress("OPT_IN_USAGE")
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.fourseveneightnine.tv.client.ui.screens.discover

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.client.appGraph
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.ui.LocalShellState
import com.fourseveneightnine.tv.client.ui.components.EmptyState
import com.fourseveneightnine.tv.client.ui.components.StateBlock
import com.fourseveneightnine.tv.client.ui.nav.ClientNav
import com.fourseveneightnine.tv.client.ui.screens.collections.AddToCollectionSheet
import com.fourseveneightnine.tv.client.ui.screens.collections.CollectionCandidate
import com.fourseveneightnine.tv.client.ui.screens.home.HomeCard
import com.fourseveneightnine.tv.client.ui.screens.home.HomePosterCard
import com.fourseveneightnine.tv.client.ui.screens.home.RowGap
import com.fourseveneightnine.tv.client.ui.screens.home.TvCardRow
import com.fourseveneightnine.tv.client.ui.theme.LocalReduceMotion
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvGeom
import com.fourseveneightnine.tv.client.ui.theme.TvMotion
import com.fourseveneightnine.tv.client.ui.theme.TvType
import com.fourseveneightnine.tv.ui.throttleDpadRepeats
import kotlin.math.roundToInt

/** Settings → Look → Discover layout. Absent means the grid, which is the default (plan §13). */
internal const val DISCOVER_ROWS_PREFERENCE = "discover_rows"

/**
 * Discover, spec §4. One catalog at a time, filtered by type and genre.
 *
 * The screen owns LEFT for the rail the same way Home does — and for the same reason. It reaches
 * this frame only when the grid did not answer it, which is exactly at column 1.
 */
@Composable
internal fun DiscoverScreen(nav: ClientNav, openAllCatalogs: Boolean = false, onAllCatalogsOpened: () -> Unit = {}) {
    val context = LocalContext.current
    val client = remember(context) { context.clientGraph }
    val rowsVariant = remember(context) {
        context.appGraph.presentationPreferences.getBoolean(DISCOVER_ROWS_PREFERENCE, false)
    }
    val scope = rememberCoroutineScope()
    val viewModel = remember(client) { DiscoverViewModel(scope, client).also { it.start() } }
    val state by viewModel.state.collectAsState()
    val shell = LocalShellState.current
    val reduceMotion = LocalReduceMotion.current

    val chipFocus = remember { FocusRequester() }
    var chipSlot by remember { mutableStateOf(ChipSlot.Type) }
    var gridFocused by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf<ChipSlot?>(null) }
    var allCatalogTypes by remember { mutableStateOf(false) }
    LaunchedEffect(openAllCatalogs) {
        if (openAllCatalogs) {
            allCatalogTypes = true
            panel = ChipSlot.Catalog
        }
    }
    var addTo by remember { mutableStateOf<CollectionCandidate?>(null) }
    var wantsChipFocus by remember { mutableStateOf(false) }
    // UP from grid row 0 has to expand the band BEFORE it can ask for chip focus. Without this the
    // band stays collapsed because the grid still holds focus, the chips are never composed, and
    // the focus request lands on a requester with no node behind it — so UP did nothing at all.
    var forceExpanded by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()

    // The rail borrows focus and must be able to give it back (spec §1.5).
    DisposableEffect(Unit) {
        val restore = { runCatching { chipFocus.requestFocus() }; Unit }
        shell.restoreContentFocus = restore
        onDispose { if (shell.restoreContentFocus === restore) shell.restoreContentFocus = null }
    }
    // Initial focus: the selected Type chip, a node that is always composed (plan §7.4 rule 2).
    // Placed once, on entry. Settings landing later must never pull the ring back out of the grid.
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (placed) return@LaunchedEffect
        placed = true
        runCatching { chipFocus.requestFocus() }
    }
    // Asking for chip focus in the same frame the band expands would hit a requester that is not
    // attached yet, so the request waits one composition.
    LaunchedEffect(rowsVariant, state.selectedCatalogKey, state.type, state.catalogs.size) {
        if (rowsVariant) viewModel.loadRails()
    }

    val collapsed = gridFocused && !forceExpanded && !rowsVariant && panel == null
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
    val countLine = DiscoverPlan.countLine(state.loading, state.items.size, state.hasMore)
    val retryFocus = remember { FocusRequester() }
    val emptyFocus = remember { FocusRequester() }
    val focusEmptyAction = DiscoverPlan.shouldFocusEmptyAction(
        settingsLoaded = state.settingsLoaded,
        loading = state.loading,
        failed = state.failed,
        itemCount = state.items.size,
        hasGenre = state.genre != null,
    )

    LaunchedEffect(wantsChipFocus, collapsed) {
        if (!wantsChipFocus || collapsed) return@LaunchedEffect
        wantsChipFocus = false
        runCatching { chipFocus.requestFocus() }
    }
    // The band collapses again on its own once the ring is back in the grid.
    LaunchedEffect(gridFocused) { if (!gridFocused) forceExpanded = false }
    // Spec §4.9: the error state's Retry button is focused. Without this the screen has nothing
    // focusable under the chips and the shell's rescue net opens the rail on its own.
    LaunchedEffect(state.failed) {
        if (state.failed) runCatching { retryFocus.requestFocus() }
    }
    // Spec §4.9: once a filtered catalog settles empty, its recovery action is the only useful
    // target below the chips. Request after composition so the button's requester is attached.
    LaunchedEffect(focusEmptyAction) {
        if (focusEmptyAction) runCatching { emptyFocus.requestFocus() }
    }
    // A new catalog is a new list. Keeping the old scroll offset leaves the viewer looking at row
    // fourteen of something they have never seen, with the first row cut off above the band.
    LaunchedEffect(state.selectedCatalogKey, state.type, state.genre) {
        runCatching { gridState.scrollToItem(0) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(TvColor.Canvas)
            .throttleDpadRepeats()
            .onPreviewKeyEvent { event ->
                // An overlay owns BACK before the shell's "press BACK again to leave" handler.
                if (event.type != KeyEventType.KeyDown || event.key != Key.Back) return@onPreviewKeyEvent false
                when {
                    addTo != null -> { addTo = null; true }
                    panel != null -> { panel = null; allCatalogTypes = false; onAllCatalogsOpened(); wantsChipFocus = true; true }
                    gridFocused -> { forceExpanded = true; wantsChipFocus = true; true }
                    else -> false
                }
            }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft, Key.Menu -> { nav.openRail(); true }
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
                    parts = DiscoverPlan.summaryParts(
                        type = state.type,
                        catalogName = state.selected?.catalogName,
                        genre = state.genre,
                        countLine = countLine,
                    ),
                )
            } else {
                Box(Modifier.fillMaxWidth()) {
                    Text("Discover", style = TvType.ScreenTitle, color = TvColor.TextPrimary, maxLines = 1)
                    DiscoverChips(
                        state = state,
                        onType = { chipSlot = ChipSlot.Type; viewModel.selectType(it) },
                        onOpenCatalogPanel = { chipSlot = ChipSlot.Catalog; panel = ChipSlot.Catalog },
                        onOpenGenrePanel = { chipSlot = ChipSlot.Genre; panel = ChipSlot.Genre },
                        chipFocus = chipFocus,
                        focusSlot = chipSlot,
                        modifier = Modifier.offset(y = 62.dp),
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

        Box(
            Modifier
                .fillMaxWidth()
                .height(gridHeight)
                .offset { IntOffset(0, bandTop.value.roundToInt()) },
        ) {
            when {
                !state.settingsLoaded -> EmptyState(
                    headline = "No titles in this catalog",
                    line = "Catalogs come from your add-ons. Pair your iPhone to send them to this TV.",
                )
                state.failed -> StateBlock(
                    headline = "Couldn't load this catalog",
                    line = "The add-on didn't answer.",
                    actionLabel = "Retry",
                    onAction = viewModel::retry,
                    secondaryLabel = "Pick another catalog",
                    onSecondary = { chipSlot = ChipSlot.Catalog; panel = ChipSlot.Catalog },
                    actionModifier = Modifier.focusRequester(retryFocus),
                    announceAsError = true,
                )
                // A chip change keeps the old cells until the new page lands, so the skeleton is
                // only ever the first load of a screen (spec §4.8).
                state.loading && state.items.isEmpty() -> GridSkeleton()
                rowsVariant -> DiscoverRails(
                    viewModel = viewModel,
                    onFocusEnter = { gridFocused = it },
                    onOpen = { nav.openDetail(it.type, it.id) },
                    onLongOpen = { addTo = it.toCandidate() },
                )
                state.items.isEmpty() -> EmptyState(
                    headline = "No titles in this catalog",
                    line = "Try another genre, or pick a different catalog.",
                    actionLabel = if (state.genre != null) "Clear genre" else null,
                    onAction = if (state.genre != null) ({ viewModel.selectGenre(null) }) else null,
                    actionModifier = Modifier.focusRequester(emptyFocus),
                )
                else -> PosterGrid(
                    items = state.items,
                    gridState = gridState,
                    onFocusedIndex = viewModel::maybeLoadMore,
                    onClick = { nav.openDetail(it.type, it.id) },
                    onLongClick = { addTo = it.toCandidate() },
                    trailingPlaceholders = if (state.loadingMore) 3 else 0,
                    onUpFromFirstRow = { forceExpanded = true; wantsChipFocus = true; true },
                    modifier = Modifier.onGridFocusChanged { gridFocused = it },
                )
            }
        }

        when (panel) {
            ChipSlot.Catalog -> CatalogPanel(
                choices = if (allCatalogTypes) state.catalogs else DiscoverPlan.forType(state.catalogs, state.type),
                selectedKey = state.selectedCatalogKey,
                showTypes = allCatalogTypes,
                onPick = { panel = null; allCatalogTypes = false; onAllCatalogsOpened(); wantsChipFocus = true; viewModel.selectCatalog(it) },
                onClose = { panel = null; allCatalogTypes = false; onAllCatalogsOpened(); wantsChipFocus = true },
            )
            ChipSlot.Genre -> GenrePanel(
                genres = state.genres,
                selected = state.genre,
                onPick = { panel = null; wantsChipFocus = true; viewModel.selectGenre(it) },
                onClose = { panel = null; wantsChipFocus = true },
            )
            else -> Unit
        }
    }

    addTo?.let { candidate ->
        AddToCollectionSheet(candidate = candidate, onDismiss = { addTo = null })
    }
}

/** Spec §4.6: rail geometry matches Home exactly, and the chips band does not collapse. */
@Composable
private fun DiscoverRails(
    viewModel: DiscoverViewModel,
    onFocusEnter: (Boolean) -> Unit,
    onOpen: (HomeCard) -> Unit,
    onLongOpen: (HomeCard) -> Unit,
) {
    val rails by viewModel.rails.collectAsState()
    val listState = rememberLazyListState()
    // Hoisted out of the content lambda: filtering there allocated a fresh list on every
    // recomposition of the column, which is once per focus move.
    val visibleRails = remember(rails) { rails.filter { it.items.isNotEmpty() } }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().onGridFocusChanged(onFocusEnter),
    ) {
        items(visibleRails, key = { it.key }) { rail ->
            Column {
                TvCardRow(
                    title = rail.title,
                    items = rail.items,
                    key = { it.key },
                ) { _, card ->
                    HomePosterCard(
                        card = card,
                        onClick = { onOpen(card) },
                        onLongClick = { onLongOpen(card) },
                        onFocused = {},
                    )
                }
                Spacer(Modifier.height(RowGap))
            }
        }
    }
}

internal fun HomeCard.toCandidate(): CollectionCandidate =
    CollectionCandidate(canonicalId = id, mediaType = type, title = title, posterUrl = posterUrl)
