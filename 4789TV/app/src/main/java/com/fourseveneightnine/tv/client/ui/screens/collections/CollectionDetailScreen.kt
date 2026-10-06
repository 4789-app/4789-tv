@file:Suppress("OPT_IN_USAGE")

package com.fourseveneightnine.tv.client.ui.screens.collections

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.client.ClientGraph
import com.fourseveneightnine.tv.client.appGraph
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.data.images.PosterRequest
import com.fourseveneightnine.tv.client.data.library.CollectionDetail
import com.fourseveneightnine.tv.client.data.refresh.Freshness
import com.fourseveneightnine.tv.client.playback.PlayRequest
import com.fourseveneightnine.tv.client.playback.PlayResult
import com.fourseveneightnine.tv.client.ui.components.ButtonKind
import com.fourseveneightnine.tv.client.ui.components.EmptyState
import com.fourseveneightnine.tv.client.ui.components.SidePanel
import com.fourseveneightnine.tv.client.ui.components.SidePanelRow
import com.fourseveneightnine.tv.client.ui.components.Skeleton
import com.fourseveneightnine.tv.client.ui.components.TvArtwork
import com.fourseveneightnine.tv.client.ui.components.TvButton
import com.fourseveneightnine.tv.client.ui.components.accessibilityLabel
import com.fourseveneightnine.tv.client.ui.nav.ClientNav
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvGeom
import com.fourseveneightnine.tv.client.ui.theme.TvShape
import com.fourseveneightnine.tv.client.ui.theme.TvSpace
import com.fourseveneightnine.tv.client.ui.theme.TvType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One collection's titles, spec §6.
 *
 * The header names where you are and never collapses. The grid matches Discover's geometry, so a
 * poster is the same size and the same distance apart wherever you meet it.
 */
@Composable
internal fun CollectionDetailScreen(id: String, nav: ClientNav) {
    val context = LocalContext.current
    val graph = remember(context) { context.clientGraph }
    val scope = rememberCoroutineScope()
    val collectionId = remember(id) { id.toLongOrNull() }

    if (collectionId == null) {
        MissingCollection(nav)
        return
    }

    val detail by graph.activeLibrary.collection(collectionId).collectAsState(initial = null)
    var loadedOnce by remember(collectionId) { mutableStateOf(false) }
    // Keyed on a boolean, not on `detail`: `CollectionDetail` holds the whole item list, so an
    // effect keyed on it pays a deep list compare on every emission (plan §12).
    val missing = detail == null

    // Two ways a row goes missing, and both must end with the viewer somewhere they can press a
    // button. `collection(id)` emits null after a delete, and it emits null for ever for an id that
    // never existed — which is reachable today, because changing a folder's source deletes the old
    // row and makes a new one, so BACK lands on a dead id.
    LaunchedEffect(collectionId, missing) {
        if (!missing) {
            loadedOnce = true
            return@LaunchedEffect
        }
        if (loadedOnce) {
            nav.back()
            return@LaunchedEffect
        }
        delay(DEAD_ID_GRACE_MILLIS)
        if (detail == null) {
            nav.toast("That collection is gone.")
            nav.back()
        }
    }

    val current = detail
    if (current == null) {
        LoadingCollection(nav)
        return
    }

    when (FolderGrid.roleOf(current.name, current.kind)) {
        FolderRole.ContinueWatching -> ContinueWatchingFolder(graph, current, nav)
        FolderRole.Watchlist -> UserFolder(graph, current, nav, scope)
        FolderRole.MyCloud -> MyCloudFolder(current, nav)
        FolderRole.User -> UserFolder(graph, current, nav, scope)
    }
}

// ------------------------------------------------------------------ the normal case

@Composable
private fun UserFolder(
    graph: ClientGraph,
    detail: CollectionDetail,
    nav: ClientNav,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    // Spec §6.7.3: the sort choice is stored per collection, so a list you always read by year
    // stays that way. It used to reset to Added on every open.
    val context = LocalContext.current
    val preferences = remember(context) { context.appGraph.presentationPreferences }
    var sort by remember(detail.id) {
        mutableStateOf(CollectionSort.read(preferences.getString(CollectionSort.key(detail.id), null)))
    }
    var panel by remember(detail.id) { mutableStateOf<CollectionPanel?>(null) }
    var moving by remember(detail.id) { mutableStateOf<ItemMove?>(null) }
    var refreshFailed by remember(detail.id) { mutableStateOf(false) }
    var adding by remember(detail.id) { mutableStateOf<CollectionCandidate?>(null) }

    val shelves by graph.snapshots.shelves().collectAsState()
    val sourceName = remember(detail.sourceRef, detail.kind, shelves.size) {
        CollectionSources.sourceName(graph, detail)
    }

    // A sourced folder refreshes when it is opened, and again whenever the snapshot that feeds it
    // is promoted. Keyed on the shelf generation, never on the shelf: a shelf is a deep compare of
    // up to 2,000 items (API-B, catalog).
    val generation = remember(shelves) { shelves.joinToString("|") { it.generation } }
    LaunchedEffect(detail.id, detail.kind, detail.sourceRef, generation) {
        if (!detail.kind.isSourced) return@LaunchedEffect
        refreshFailed = !CollectionSources.refresh(graph, detail)
    }

    // The editor's "Reorder items" row leaves for this screen already in move mode.
    LaunchedEffect(detail.id) {
        if (PendingMoveMode.consume(detail.id) && detail.items.isNotEmpty()) {
            moving = ItemMove(detail.items.first().canonicalId)
        }
    }

    val cells = remember(detail.items, sort) { CollectionGrid.cells(detail.items, sort) }
    val gridFocus = remember { FocusRequester() }
    val sortFocus = remember { FocusRequester() }
    val emptyFocus = remember { FocusRequester() }

    CollectionFrame(
        detail = detail,
        sourceName = sourceName,
        staleLabel = Freshness.label(detail.updatedAtMillis, System.currentTimeMillis()),
        refreshFailed = refreshFailed,
        moving = moving != null,
        sortLabel = sort.label,
        sortModifier = Modifier.focusRequester(sortFocus),
        onSort = { panel = CollectionPanel.Sort },
        onEdit = { nav.openCollectionEditor(detail.id.toString()) },
        onRetry = {
            scope.launch { refreshFailed = !CollectionSources.refresh(graph, detail) }
        },
    ) {
        if (cells.isEmpty()) {
            Box(Modifier.fillMaxSize()) {
                if (detail.kind.isSourced) {
                    EmptyState(
                        headline = "That list is empty",
                        line = "The source has no titles right now.",
                        actionLabel = "Refresh",
                        onAction = {
                            scope.launch { refreshFailed = !CollectionSources.refresh(graph, detail) }
                        },
                        actionModifier = Modifier.focusRequester(emptyFocus),
                    )
                } else {
                    EmptyState(
                        headline = "This collection is empty",
                        line = "Add titles from any detail screen, or hold OK on a poster.",
                        actionLabel = "Browse Discover",
                        onAction = nav::openDiscover,
                        actionModifier = Modifier.focusRequester(emptyFocus),
                    )
                }
            }
        } else {
            PosterGrid(
                cells = cells,
                gridFocus = gridFocus,
                movingId = moving?.canonicalId,
                onOpen = { nav.openDetail(it.mediaType, it.canonicalId) },
                onLongClick = { cell ->
                    panel = CollectionPanel.Item(cell)
                },
                onPlay = { cell ->
                    scope.launch {
                        val result = graph.playFlow.play(
                            PlayRequest(
                                type = cell.mediaType,
                                id = cell.canonicalId,
                                title = cell.title,
                                posterUrl = cell.posterUrl,
                            ),
                        )
                        when (result) {
                            PlayResult.ShowList -> nav.openStreams(cell.mediaType, cell.canonicalId)
                            is PlayResult.Failed -> nav.toast(result.message)
                            PlayResult.Opened -> Unit
                        }
                    }
                },
                onMove = { direction ->
                    val state = moving ?: return@PosterGrid
                    val delta = MoveMode.delta(direction, CollectionGrid.COLUMNS)
                    scope.launch {
                        if (graph.activeLibrary.moveItem(detail.id, state.canonicalId, delta)) {
                            moving = state.copy(applied = state.applied + delta)
                        }
                    }
                },
                onCommit = {
                    moving = null
                    nav.toast("Order saved.")
                },
                onCancelMove = {
                    val state = moving ?: return@PosterGrid
                    moving = null
                    val undo = MoveMode.undoDelta(state.applied)
                    if (undo != 0) {
                        scope.launch { graph.activeLibrary.moveItem(detail.id, state.canonicalId, undo) }
                    }
                },
            )
        }
    }

    // One effect, keyed on Unit, for the whole screen. Three separate effects that each fired when
    // their data arrived meant a viewer who opened the rail while the grid loaded had the ring
    // pulled back out of it (plan §7.4 rule 7).
    LaunchedEffect(Unit) { placeEntryFocus { if (cells.isEmpty()) emptyFocus else gridFocus } }

    when (val open = panel) {
        CollectionPanel.Sort -> SortPanel(
            current = sort,
            onPick = { mode ->
                sort = mode
                panel = null
                preferences.edit()
                    .putString(CollectionSort.key(detail.id), CollectionSort.write(mode))
                    .apply()
            },
            onClose = { panel = null },
        )
        is CollectionPanel.Item -> ItemMenu(
            cell = open.cell,
            allowMove = !detail.kind.isSourced,
            onRemove = {
                panel = null
                scope.launch { graph.activeLibrary.removeItem(detail.id, open.cell.canonicalId) }
            },
            onAddToAnother = {
                panel = null
                adding = CollectionCandidate(
                    canonicalId = open.cell.canonicalId,
                    mediaType = open.cell.mediaType,
                    title = open.cell.title,
                    posterUrl = open.cell.posterUrl,
                )
            },
            onOpenDetails = {
                panel = null
                nav.openDetail(open.cell.mediaType, open.cell.canonicalId)
            },
            onMove = {
                panel = null
                moving = ItemMove(open.cell.canonicalId)
            },
            onClose = { panel = null },
        )
        null -> Unit
    }

    adding?.let { candidate ->
        AddToCollectionSheet(candidate = candidate, onDismiss = { adding = null })
    }

    BackHandler(enabled = panel != null || moving != null) {
        when {
            panel != null -> panel = null
            else -> {
                val state = moving
                moving = null
                val undo = MoveMode.undoDelta(state?.applied.orEmpty())
                if (state != null && undo != 0) {
                    scope.launch { graph.activeLibrary.moveItem(detail.id, state.canonicalId, undo) }
                }
            }
        }
    }
}

private data class ItemMove(val canonicalId: String, val applied: List<Int> = emptyList())

private sealed interface CollectionPanel {
    data object Sort : CollectionPanel
    data class Item(val cell: CollectionCell) : CollectionPanel
}

// ------------------------------------------------------------------ the two system folders

/**
 * Continue Watching is a view of the progress table, not a folder of stored rows, so it reads
 * `continueWatching()` rather than the folder's items. Play resumes where the viewer stopped.
 */
@Composable
private fun ContinueWatchingFolder(graph: ClientGraph, detail: CollectionDetail, nav: ClientNav) {
    val scope = rememberCoroutineScope()
    val rows by graph.activeLibrary.continueWatching().collectAsState(initial = emptyList())
    val gridFocus = remember { FocusRequester() }
    val emptyFocus = remember { FocusRequester() }
    val cells = remember(rows) {
        rows.map { row ->
            CollectionCell(
                canonicalId = row.canonicalId,
                mediaType = row.mediaType,
                title = titleWithoutYear(row.title),
                posterUrl = row.posterUrl,
                year = yearIn(row.title),
            )
        }
    }

    CollectionFrame(
        detail = detail,
        sourceName = null,
        staleLabel = null,
        refreshFailed = false,
        moving = false,
        sortLabel = null,
        sortModifier = Modifier,
        onSort = {},
        onEdit = { nav.openCollectionEditor(detail.id.toString()) },
        onRetry = {},
        countOverride = cells.size,
    ) {
        if (cells.isEmpty()) {
            EmptyState(
                headline = "Nothing to continue",
                line = "Start a title and it waits for you here.",
                actionLabel = "Browse Home",
                onAction = nav::openHome,
                actionModifier = Modifier.focusRequester(emptyFocus),
            )
        } else {
            PosterGrid(
                cells = cells,
                gridFocus = gridFocus,
                movingId = null,
                onOpen = { nav.openDetail(it.mediaType, it.canonicalId) },
                onLongClick = {},
                onPlay = { cell ->
                    val row = rows.firstOrNull { it.canonicalId == cell.canonicalId }
                    scope.launch {
                        val result = graph.playFlow.play(
                            PlayRequest(
                                type = cell.mediaType,
                                id = cell.canonicalId,
                                title = cell.title,
                                season = row?.season,
                                episode = row?.episode,
                                posterUrl = cell.posterUrl,
                                backdropUrl = row?.backdropUrl,
                                resumeFromMs = row?.positionMs,
                            ),
                        )
                        when (result) {
                            PlayResult.ShowList -> nav.openStreams(
                                cell.mediaType,
                                cell.canonicalId,
                                row?.season,
                                row?.episode,
                            )
                            is PlayResult.Failed -> nav.toast(result.message)
                            PlayResult.Opened -> Unit
                        }
                    }
                },
                onMove = {},
                onCommit = {},
                onCancelMove = {},
            )
        }
    }
    LaunchedEffect(Unit) { placeEntryFocus { if (rows.isEmpty()) emptyFocus else gridFocus } }
}

/**
 * My Cloud lists the files in your debrid account. That list is not built yet, and a folder that
 * pretends to be empty would be a lie — so it says what it is waiting for.
 */
@Composable
private fun MyCloudFolder(detail: CollectionDetail, nav: ClientNav) {
    val focus = remember { FocusRequester() }
    CollectionFrame(
        detail = detail,
        sourceName = null,
        staleLabel = null,
        refreshFailed = false,
        moving = false,
        sortLabel = null,
        sortModifier = Modifier,
        onSort = {},
        onEdit = { nav.openCollectionEditor(detail.id.toString()) },
        onRetry = {},
        countOverride = 0,
    ) {
        EmptyState(
            headline = "My Cloud is not ready",
            line = "File list comes in a later build. Your debrid key is already saved.",
            actionLabel = "Back to collections",
            onAction = nav::back,
            actionModifier = Modifier.focusRequester(focus),
        )
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }
}

@Composable
private fun MissingCollection(nav: ClientNav) {
    val focus = remember { FocusRequester() }
    DrillInFrame {
        Column {
            Text("Collection", style = TvType.ScreenTitle, color = TvColor.TextPrimary, maxLines = 1)
            Spacer(Modifier.height(TvSpace.L))
            EmptyState(
                headline = "That collection is gone",
                line = "It was deleted, or it never existed on this box.",
                actionLabel = "Back to collections",
                onAction = nav::back,
                actionModifier = Modifier.focusRequester(focus),
            )
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

// ------------------------------------------------------------------ frame and grid

/** The header every variant shares: accent bar, name, meta line, source line, Sort and Edit. */
@Composable
private fun CollectionFrame(
    detail: CollectionDetail,
    sourceName: String?,
    staleLabel: String?,
    refreshFailed: Boolean,
    moving: Boolean,
    sortLabel: String?,
    sortModifier: Modifier,
    onSort: () -> Unit,
    onEdit: () -> Unit,
    onRetry: () -> Unit,
    countOverride: Int? = null,
    content: @Composable () -> Unit,
) {
    // Parsed once per accent, not on every recomposition of the header.
    val accent = remember(detail.accent) { accentColor(detail.accent) }
    DrillInFrame {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(8.dp, 72.dp).background(accent))
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        clip(detail.name, 30),
                        style = TvType.ScreenTitle,
                        color = TvColor.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        CollectionGrid.metaLine(countOverride ?: detail.items.size, staleLabel),
                        style = TvType.Body,
                        color = TvColor.TextSecondary,
                        maxLines = 1,
                    )
                    CollectionGrid.sourceLine(detail.kind, detail.sourceRef, sourceName)?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = TvType.data(20), color = TvColor.TextMuted, maxLines = 1)
                    }
                }
                if (sortLabel != null) {
                    TvButton(
                        "Sort: $sortLabel",
                        onSort,
                        kind = ButtonKind.Secondary,
                        modifier = sortModifier.width(240.dp),
                    )
                    Spacer(Modifier.width(24.dp))
                }
                TvButton("Edit", onEdit, kind = ButtonKind.Secondary, modifier = Modifier.width(200.dp))
            }
            Spacer(Modifier.height(TvSpace.M))
            if (refreshFailed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Couldn't refresh this list. Showing what was saved.",
                        style = TvType.Meta,
                        color = TvColor.Warning,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(TvSpace.S))
                    TvButton("Retry", onRetry, kind = ButtonKind.Ghost)
                }
                Spacer(Modifier.height(TvSpace.S))
            }
            if (moving) {
                MoveBanner("Move mode. Arrows move the title. OK drops it. BACK cancels.")
                Spacer(Modifier.height(TvSpace.S))
            }
            content()
        }
    }
}

@Composable
private fun PosterGrid(
    cells: List<CollectionCell>,
    gridFocus: FocusRequester,
    movingId: String?,
    onOpen: (CollectionCell) -> Unit,
    onLongClick: (CollectionCell) -> Unit,
    onPlay: (CollectionCell) -> Unit,
    onMove: (MoveDirection) -> Unit,
    onCommit: () -> Unit,
    onCancelMove: () -> Unit,
) {
    val state = rememberLazyGridState()
    LazyVerticalGrid(
        columns = GridCells.Fixed(CollectionGrid.COLUMNS),
        state = state,
        horizontalArrangement = Arrangement.spacedBy(CollectionGrid.COLUMN_GAP.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
        modifier = Modifier
            // Pinned to the band width, not the screen. `fillMaxSize` gave six 250.67 px slots
            // over a 1604 px band and walked every poster up to 27 px off its column (spec §6.2).
            .width(CollectionGrid.BAND_WIDTH.dp)
            .fillMaxHeight()
            .focusRequester(gridFocus)
            .focusRestorer()
            .onPreviewKeyEvent { event ->
                if (movingId == null) return@onPreviewKeyEvent false
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                when (event.key) {
                    Key.DirectionLeft -> { onMove(MoveDirection.Left); true }
                    Key.DirectionRight -> { onMove(MoveDirection.Right); true }
                    Key.DirectionUp -> { onMove(MoveDirection.Up); true }
                    Key.DirectionDown -> { onMove(MoveDirection.Down); true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { onCommit(); true }
                    Key.Back -> { onCancelMove(); true }
                    else -> false
                }
            },
    ) {
        items(cells, key = { it.canonicalId }) { cell ->
            PosterCell(
                cell = cell,
                dimmed = movingId != null && movingId != cell.canonicalId,
                picked = movingId == cell.canonicalId,
                onClick = { onOpen(cell) },
                onLongClick = { onLongClick(cell) },
                onPlay = { onPlay(cell) },
            )
        }
    }
}

@Composable
private fun PosterCell(
    cell: CollectionCell,
    dimmed: Boolean,
    picked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPlay: () -> Unit,
) {
    Column(Modifier.width(TvGeom.PosterWidth).moveModeDim(dimmed)) {
        LongPressFocusable(
            onClick = onClick,
            onLongClick = onLongClick,
            onPlay = onPlay,
            accessibleLabel = accessibilityLabel(cell.title, cell.year?.toString(), cell.mediaType),
            clickLabel = "Open details",
            longClickLabel = "More options",
            selected = picked,
            customActions = listOf(CustomAccessibilityAction("Play") { onPlay(); true }),
            cornerRadius = 12.dp,
        ) { _ ->
            Box(
                modifier = Modifier
                    .size(TvGeom.PosterWidth, TvGeom.PosterHeight)
                    .clip(TvShape.Card)
                    .background(if (picked) TvColor.Focus.copy(alpha = 0.06f) else Color.Transparent),
            ) {
                // `TvArtwork`, not `Poster`: a title with no poster url used to draw a bare
                // rectangle with nothing in it. The shared block puts the title inside the
                // placeholder and drops it the moment the image paints (spec §16.1).
                TvArtwork(
                    request = PosterRequest.poster(cell.posterUrl),
                    title = cell.title,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.height(TvGeom.FocusLabelGap))
        Text(
            cell.title,
            style = TvType.CardTitle,
            color = TvColor.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            cell.year?.toString().orEmpty(),
            style = TvType.data(20),
            color = TvColor.TextMuted,
            maxLines = 1,
        )
    }
}

// ------------------------------------------------------------------ panels

@Composable
private fun SortPanel(current: SortMode, onPick: (SortMode) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    SidePanel(header = "Sort", onClose = onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(TvSpace.XS)) {
            SortMode.entries.forEach { mode ->
                SidePanelRow(
                    label = mode.label,
                    selected = mode == current,
                    onClick = { onPick(mode) },
                    modifier = if (mode == current) Modifier.focusRequester(focus) else Modifier,
                )
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BackHandler(enabled = true) { onClose() }
}

/** long-OK on a title, spec §6.3. A sourced folder cannot be reordered, so "Move" is absent. */
@Composable
private fun ItemMenu(
    cell: CollectionCell,
    allowMove: Boolean,
    onRemove: () -> Unit,
    onAddToAnother: () -> Unit,
    onOpenDetails: () -> Unit,
    onMove: () -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    SidePanel(header = clip(cell.title, 26), onClose = onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(TvSpace.XS)) {
            SidePanelRow(
                label = "Remove from this collection",
                selected = false,
                onClick = onRemove,
                modifier = Modifier.focusRequester(focus),
            )
            SidePanelRow("Add to another collection", selected = false, onClick = onAddToAnother)
            SidePanelRow("Open details", selected = false, onClick = onOpenDetails)
            if (allowMove) SidePanelRow("Move", selected = false, onClick = onMove)
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BackHandler(enabled = true) { onClose() }
}

/** How long a collection id gets to resolve before the screen gives up on it. */
private const val DEAD_ID_GRACE_MILLIS = 3_000L

/** About a second of frames. Long enough for a local database read, short enough to give up on. */
private const val FOCUS_ATTEMPTS = 60

/**
 * Puts the ring somewhere, once, on entry — plan §7.4 rule 7.
 *
 * [target] is re-read each attempt, so a screen that opens on a skeleton and settles into a grid
 * still lands on the grid. It stops at the first node that takes the focus and never asks again,
 * which is what stops arriving data from dragging the ring out of the navigation rail.
 */
private suspend fun placeEntryFocus(target: () -> FocusRequester) {
    repeat(FOCUS_ATTEMPTS) {
        if (runCatching { target().requestFocus() }.isSuccess) return
        withFrameNanos { }
    }
}

/**
 * 12 skeleton cells; the sweep runs on the first row (spec §6.5).
 *
 * The ghost button is not decoration. This frame draws no rail, so without a focusable node the
 * remote has nothing to move to and nothing to press: a skeleton that never resolves used to be a
 * dead remote until the viewer force-quit the app.
 */
@Composable
private fun LoadingCollection(nav: ClientNav) {
    val backFocus = remember { FocusRequester() }
    DrillInFrame {
        Column(Modifier.fillMaxSize()) {
            Skeleton(Modifier.size(520.dp, 50.dp), shape = TvShape.Badge)
            Spacer(Modifier.height(TvSpace.S))
            TvButton(
                "Back to collections",
                nav::back,
                kind = ButtonKind.Ghost,
                modifier = Modifier.focusRequester(backFocus),
            )
            Spacer(Modifier.height(TvSpace.M))
            repeat(2) { row ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    repeat(CollectionGrid.COLUMNS) {
                        Skeleton(
                            Modifier.size(TvGeom.PosterWidth, TvGeom.PosterHeight),
                            sweep = row == 0,
                        )
                    }
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { backFocus.requestFocus() } }
}
