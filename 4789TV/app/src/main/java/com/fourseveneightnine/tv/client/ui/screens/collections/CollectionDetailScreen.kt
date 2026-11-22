@file:Suppress("OPT_IN_USAGE")

package com.fourseveneightnine.tv.client.ui.screens.collections

import com.fourseveneightnine.tv.client.ui.components.TvSearchField
import com.fourseveneightnine.tv.client.ui.components.PanelTransition
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import com.fourseveneightnine.tv.client.data.library.SystemCollections
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.R
import com.fourseveneightnine.tv.client.ClientGraph
import com.fourseveneightnine.tv.client.appGraph
import com.fourseveneightnine.tv.client.home.LauncherRails
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.data.images.PosterRequest
import com.fourseveneightnine.tv.client.data.library.CollectionDetail
import com.fourseveneightnine.tv.client.data.library.CollectionKind
import com.fourseveneightnine.tv.client.data.refresh.Freshness
import com.fourseveneightnine.tv.client.playback.WatchlistSource
import kotlinx.serialization.json.Json
import com.fourseveneightnine.tv.client.playback.PlayRequest
import com.fourseveneightnine.tv.client.playback.PlayResult
import com.fourseveneightnine.tv.client.ui.components.ButtonKind
import com.fourseveneightnine.tv.client.ui.components.EmptyState
import com.fourseveneightnine.tv.client.ui.components.GridRowFocus
import com.fourseveneightnine.tv.client.ui.components.SidePanel
import com.fourseveneightnine.tv.client.ui.components.SidePanelRow
import com.fourseveneightnine.tv.client.ui.components.Skeleton
import com.fourseveneightnine.tv.client.ui.components.tvGlassPanel
import com.fourseveneightnine.tv.client.ui.components.TvProgressBar
import com.fourseveneightnine.tv.client.ui.screens.home.HomePlan
import com.fourseveneightnine.tv.client.ui.components.TvArtwork
import com.fourseveneightnine.tv.client.ui.components.TvButton
import com.fourseveneightnine.tv.client.ui.components.TvFocusableCard
import com.fourseveneightnine.tv.client.data.streams.StreamRow
import com.fourseveneightnine.tv.client.data.streams.debrid.DebridFile
import com.fourseveneightnine.tv.client.data.streams.debrid.TorboxLibraryTorrent
import com.fourseveneightnine.tv.client.data.streams.debrid.TorboxLibraryState
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import com.fourseveneightnine.tv.client.ui.components.accessibilityLabel
import com.fourseveneightnine.tv.client.ui.components.CatalogAction
import com.fourseveneightnine.tv.client.ui.components.CatalogActionRail
import com.fourseveneightnine.tv.client.ui.components.CatalogRailFocus
import com.fourseveneightnine.tv.client.ui.components.CatalogRailGutter
import com.fourseveneightnine.tv.client.ui.nav.ClientNav
import com.fourseveneightnine.tv.client.ui.screens.detail.DetailPreview
import com.fourseveneightnine.tv.client.data.catalog.CatalogItem
import com.fourseveneightnine.tv.client.ui.screens.discover.rememberCatalogFilters
import com.fourseveneightnine.tv.client.ui.screens.discover.CatalogFilters
import com.fourseveneightnine.tv.client.ui.screens.discover.CatalogSort
import com.fourseveneightnine.tv.client.ui.screens.discover.CatalogFilterPanel
import com.fourseveneightnine.tv.client.ui.screens.discover.rememberCatalogBrowse
import com.fourseveneightnine.tv.client.ui.screens.home.HomeCard
import com.fourseveneightnine.tv.client.ui.screens.home.toHomeCard
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvGeom
import com.fourseveneightnine.tv.client.ui.theme.TvShape
import com.fourseveneightnine.tv.client.ui.theme.TvSpace
import com.fourseveneightnine.tv.client.ui.theme.TvType
import com.fourseveneightnine.tv.ui.throttleDpadRepeats
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One collection's titles, spec §6.
 *
 * The header names where you are and never collapses. The grid matches Discover's geometry, so a
 * poster is the same size and the same distance apart wherever you meet it.
 */
@Composable
internal fun CollectionDetailScreen(
    id: String,
    nav: ClientNav,
    initialWatchlistType: String? = null,
    active: Boolean = true,
    placed: Boolean = active,
) {
    val context = LocalContext.current
    val graph = remember(context) { context.clientGraph }
    val scope = rememberCoroutineScope()
    val collectionId = remember(id) { id.toLongOrNull() }

    if (collectionId == null) {
        MissingCollection(nav)
        return
    }

    val library = graph.activeLibrary
    var attempt by remember(library, collectionId) { mutableStateOf(0) }
    var readFailed by remember(library, collectionId) { mutableStateOf(false) }
    val detail by key(library, collectionId) {
        produceState<CollectionDetail?>(null, attempt) {
            readFailed = false
            try {
                library.collection(collectionId).collect { value = it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                readFailed = true
            }
        }
    }
    var loadedOnce by remember(library, collectionId) { mutableStateOf(false) }
    val reorder = rememberSaveable(collectionId) { PendingMoveMode.consume(collectionId) }
    // Keyed on a boolean, not on `detail`: `CollectionDetail` holds the whole item list, so an
    // effect keyed on it pays a deep list compare on every emission (plan §12).
    val missing = detail == null

    // Two ways a row goes missing, and both must end with the viewer somewhere they can press a
    // button. `collection(id)` emits null after a delete, and it emits null for ever for an id that
    // never existed — which is reachable today, because changing a folder's source deletes the old
    // row and makes a new one, so BACK lands on a dead id.
    LaunchedEffect(library, collectionId, missing, readFailed, attempt, active) {
        if (readFailed || !active) return@LaunchedEffect
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
        if (readFailed) DrillInFrame {
            val retry = remember { FocusRequester() }
            EmptyState("Couldn't load saved list", "Try opening the saved list again.",
                actionLabel = "Retry", onAction = { attempt++ }, actionModifier = Modifier.focusRequester(retry))
            LaunchedEffect(Unit) { placeEntryFocus { retry } }
        } else LoadingCollection(nav)
        return
    }

    if (current.sourceRef == SystemCollections.WATCHLIST_REF && !reorder) {
        key(library) { WatchlistScreen(graph, current, nav, initialWatchlistType, readFailed, placed) { attempt++ } }
        return
    }

    when (FolderGrid.roleOf(current.name, current.kind)) {
        FolderRole.ContinueWatching -> ContinueWatchingFolder(graph, current, nav, placed)
        FolderRole.MyCloud -> MyCloudFolder(graph, current, nav)
        FolderRole.Watchlist, FolderRole.Favorites, FolderRole.User ->
            UserFolder(graph, current, nav, scope, reorder, active, placed)
    }
}

// ------------------------------------------------------------------ the normal case

@Composable
private fun UserFolder(
    graph: ClientGraph,
    detail: CollectionDetail,
    nav: ClientNav,
    scope: kotlinx.coroutines.CoroutineScope,
    startReordering: Boolean,
    active: Boolean,
    placed: Boolean,
) {
    // Spec §6.7.3: the sort choice is stored per collection, so a list you always read by year
    // stays that way. It used to reset to Added on every open.
    val context = LocalContext.current
    val preferences = remember(context) { context.appGraph.presentationPreferences }
    var filters by rememberCatalogFilters(graph, "collection:${detail.id}") {
        val sort = CollectionSort.read(preferences.getString(CollectionSort.key(detail.id), null))
        val saved = preferences.getString("collection_filter_sort_${detail.id}", null)
        CatalogFilters(sort = CatalogSort.entries.firstOrNull { it.name == saved } ?: when (sort) {
            SortMode.Added -> CatalogSort.Original
            SortMode.Title -> CatalogSort.Title
            SortMode.Year -> CatalogSort.Newest
        })
    }
    var panel by remember(detail.id) { mutableStateOf<CollectionPanel?>(null) }
    val loveTitle = rememberLoveTitle(nav)
    var moving by remember(detail.id) { mutableStateOf<ItemMove?>(null) }
    val moveGate = remember(detail.id) { Mutex() }
    var refreshFailed by remember(detail.id) { mutableStateOf(false) }
    var adding by remember(detail.id) { mutableStateOf<CollectionCandidate?>(null) }

    val shelves by graph.collectionShelves.collectAsState()
    val sourceShelf = remember(shelves, detail.sourceRef) { shelves.firstOrNull { it.id == detail.sourceRef } }
    val sourceName = remember(detail.sourceRef, detail.kind, sourceShelf?.title) {
        CollectionSources.sourceName(graph, detail)
    }

    // Observe the one shelf this folder follows. A completed phone copy can update the folder
    // while it is open, including when the preview has the same catalog ID.
    LaunchedEffect(detail.id, detail.kind, detail.sourceRef, sourceShelf) {
        if (!detail.kind.isSourced) return@LaunchedEffect
        refreshFailed = !CollectionSources.refresh(graph, detail)
    }

    // The editor's "Reorder items" row leaves for this screen already in move mode.
    LaunchedEffect(detail.id, detail.kind) {
        if (startReordering && !detail.kind.isSourced && detail.items.isNotEmpty()) {
            moving = ItemMove(detail.items.first().canonicalId, graph.activeLibrary)
        }
    }

    val letterboxdItems = remember(detail.kind, sourceShelf) {
        sourceShelf?.takeIf { detail.kind == CollectionKind.LETTERBOXD }
            ?.items?.associateBy(CatalogItem::canonicalId).orEmpty()
    }
    fun stageDetail(cell: CollectionCell) {
        DetailPreview.stage(letterboxdItems[cell.canonicalId]?.toHomeCard(recoverPoster = true, preserveIdentity = true)
            ?: HomeCard(cell.canonicalId, cell.mediaType, cell.title, posterUrl = cell.posterUrl, year = cell.year))
    }

    val baseCards = remember(detail.items, letterboxdItems) {
        CollectionGrid.cells(detail.items, SortMode.Added).map { cell ->
            letterboxdItems[cell.canonicalId]?.toHomeCard(recoverPoster = true, preserveIdentity = true)
                ?: HomeCard(cell.canonicalId, cell.mediaType, cell.title, posterUrl = cell.posterUrl, year = cell.year)
        }
    }
    val appliedFilters = if (moving != null) CatalogFilters() else filters
    val browse = rememberCatalogBrowse(baseCards, graph, appliedFilters, moving == null && (filters.active || panel == CollectionPanel.Sort), scopeKey = "collection:${detail.id}", benchmarkSurface = "collection")
    var titleQuery by rememberSaveable(detail.id) { mutableStateOf("") }
    var titleSearchOpen by remember(detail.id) { mutableStateOf(false) }
    val actionOrigin = rememberSaveable(detail.id) { mutableStateOf<String?>(null) }
    val actions = remember(actionOrigin) { CatalogRailFocus(actionOrigin) }
    var actionReturnKey by remember(detail.id) { mutableStateOf<String?>(null) }
    var filtersFromRail by remember(detail.id) { mutableStateOf(false) }
    val appliedQuery = if (moving == null) titleQuery else ""
    val cells = remember(browse.items, appliedQuery) {
        browse.items.filterCollectionSearch(appliedQuery, HomeCard::title).map { card ->
            CollectionCell(card.id, card.type, card.title, card.posterUrl, card.year)
        }
    }
    val gridFocus = remember { FocusRequester() }
    val sortFocus = remember { FocusRequester() }
    val emptyFocus = remember { FocusRequester() }

    fun finishMove(cancel: Boolean) {
        val session = moving ?: return
        session.cancelled = cancel
        moving = null
        graph.scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
            moveGate.withLock {
                try {
                    if (cancel) {
                        val undo = MoveMode.undoDelta(session.applied)
                        if (undo != 0) session.library.moveItem(detail.id, session.canonicalId, undo)
                    } else nav.toast("Order saved.")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { nav.toast("Order could not be saved. Try again.") }
            }
            if (startReordering && detail.sourceRef == SystemCollections.WATCHLIST_REF) nav.back()
        }
    }

    CollectionFrame(
        detail = detail,
        sourceName = sourceName,
        staleLabel = Freshness.label(detail.updatedAtMillis, System.currentTimeMillis()),
        refreshFailed = refreshFailed,
        countOverride = cells.size,
        moving = moving != null,
        sortLabel = if (moving != null) "Catalog order" else filters.label,
        sortModifier = Modifier.focusRequester(sortFocus).focusProperties {
            down = if (cells.isEmpty()) emptyFocus else gridFocus
        },
        onSort = { panel = CollectionPanel.Sort },
        onEdit = { nav.openCollectionEditor(detail.id.toString()) },
        searchField = {
            if (titleSearchOpen) TvSearchField("Search titles", titleQuery, { titleQuery = it },
                Modifier.width(560.dp).focusProperties { this.down = if (cells.isEmpty()) emptyFocus else gridFocus },
                openKeyboard = true, enabled = panel == null && adding == null, onClose = {
                    titleSearchOpen = false
                    scope.launch { withFrameNanos { }; actions.restoreAction() }
                })
        },
        actionRail = {
            CatalogActionRail(
                actions,
                actions = buildList {
                    add(CatalogAction(R.drawable.ic_rail_search, if (titleQuery.isEmpty()) "Search titles" else "Search: $titleQuery") { titleSearchOpen = true })
                    add(CatalogAction(R.drawable.ic_catalog_filter, "Filters & sort") {
                        filtersFromRail = true
                        panel = CollectionPanel.Sort
                    })
                    add(CatalogAction(R.drawable.ic_catalog_pin, "Add to Den TV Home") {
                        LauncherRails.collection(context, detail.id, nav::toast)
                    })
                    if (detail.kind.isSourced) add(CatalogAction(R.drawable.ic_catalog_refresh, "Refresh list") {
                        scope.launch { refreshFailed = !CollectionSources.refresh(graph, detail) }
                    })
                },
                onLeave = { key ->
                    if (key != null && cells.any { it.canonicalId == key }) actionReturnKey = key
                    else runCatching { (if (cells.isEmpty()) emptyFocus else gridFocus).requestFocus() }
                },
                modifier = Modifier.align(Alignment.CenterEnd),
                enabled = panel == null && adding == null && !titleSearchOpen,
            )
        },
        onRetry = {
            scope.launch { refreshFailed = !CollectionSources.refresh(graph, detail) }
        },
    ) {
        if (cells.isEmpty()) {
            Box(Modifier.fillMaxSize()) {
                if (titleQuery.isNotEmpty()) {
                    EmptyState("No matching titles", "Try a different title.", actionLabel = "Clear search",
                        onAction = { titleQuery = ""; runCatching { sortFocus.requestFocus() } },
                        actionModifier = Modifier.focusRequester(emptyFocus))
                } else if (filters.active) {
                    EmptyState(headline = if ((browse.pending > 0 || browse.updating)) "Checking title metadata…" else "No matching titles",
                        line = browse.status(), actionLabel = "Reset filters", onAction = {
                            filters = CatalogFilters()
                            runCatching { sortFocus.requestFocus() }
                        },
                        actionModifier = Modifier.focusRequester(emptyFocus))
                } else if (detail.kind.isSourced) {
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
                filterFocus = sortFocus,
                placed = placed,
                restoreOnEntry = panel == null && adding == null && !titleSearchOpen,
                movingId = moving?.canonicalId,
                actions = actions,
                actionReturnKey = actionReturnKey,
                onActionReturned = { actionReturnKey = null },
                letterboxdItems = letterboxdItems,
                onOpen = {
                    stageDetail(it)
                    nav.openDetail(it.mediaType, it.canonicalId)
                },
                onLongClick = { cell ->
                    loveTitle(CollectionCandidate(cell.canonicalId, cell.mediaType, cell.title, cell.posterUrl))
                },
                onItemOptions = { cell -> panel = CollectionPanel.Item(cell) },
                onPlay = { cell ->
                    val exactFile = cell.canonicalId.startsWith(WatchlistSource.PREFIX) || cell.canonicalId.startsWith("saved-torbox:") ||
                        graph.savedCatalogItem(cell.mediaType, cell.canonicalId)?.playbackSources?.singleOrNull()?.fileID != null
                    if (cell.mediaType == "series" && !exactFile) {
                        stageDetail(cell)
                        nav.openDetail(cell.mediaType, cell.canonicalId)
                        nav.toast("Choose an episode to play.")
                    } else scope.launch {
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
                    val session = moving ?: return@PosterGrid
                    val delta = MoveMode.delta(direction, CollectionGrid.COLUMNS)
                    graph.scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
                        moveGate.withLock {
                            if (session.cancelled) return@withLock
                            try {
                                if (session.library.moveItem(detail.id, session.canonicalId, delta))
                                    session.applied = session.applied + delta
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { nav.toast("Order could not be saved. Try again.") }
                        }
                    }
                },
                onCommit = { finishMove(cancel = false) },
                onCancelMove = { finishMove(cancel = true) },
            )
        }
    }

    // One effect, keyed on Unit, for the whole screen. Three separate effects that each fired when
    // their data arrived meant a viewer who opened the rail while the grid loaded had the ring
    // pulled back out of it (plan §7.4 rule 7).
    LaunchedEffect(cells.isEmpty(), placed) {
        if (placed && cells.isEmpty() && panel == null && !titleSearchOpen) placeEntryFocus { emptyFocus }
    }

    PanelTransition(panel) { open -> when (open) {
        CollectionPanel.Sort -> CatalogFilterPanel(
            filters = filters,
            browse = browse,
            onChange = { filters = it },
            onClose = {
                panel = null
                if (filtersFromRail) {
                    filtersFromRail = false
                    scope.launch { withFrameNanos { }; actions.restoreAction() }
                } else runCatching { sortFocus.requestFocus() }
            },
            active = panel == CollectionPanel.Sort,
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
                if (open.cell.canonicalId.startsWith(WatchlistSource.PREFIX)) {
                    scope.launch {
                        val record = graph.activeLibrary.savedSource(open.cell.canonicalId)
                        val source = record?.let { runCatching { Json.decodeFromString<WatchlistSource>(it) }.getOrNull() }
                        if (source != null) nav.openDetail(open.cell.mediaType, source.catalogId, source.season, source.episode)
                        else nav.toast("This saved source is unavailable.")
                    }
                } else {
                    stageDetail(open.cell)
                    nav.openDetail(open.cell.mediaType, open.cell.canonicalId)
                }
            },
            onMove = {
                panel = null
                if (!detail.kind.isSourced) moving = ItemMove(open.cell.canonicalId, graph.activeLibrary)
            },
            onClose = { panel = null },
        )
    } }

    PanelTransition(adding) { candidate ->
        AddToCollectionSheet(candidate = candidate, onDismiss = { adding = null })
    }

    BackHandler(enabled = active && (panel != null && panel != CollectionPanel.Sort || moving != null)) {
        when {
            panel != null -> panel = null
            else -> finishMove(cancel = true)
        }
    }
}

private class ItemMove(val canonicalId: String, val library: com.fourseveneightnine.tv.client.data.library.LibraryRepository,
    var applied: List<Int> = emptyList(), var cancelled: Boolean = false)

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
private fun ContinueWatchingFolder(graph: ClientGraph, detail: CollectionDetail, nav: ClientNav, placed: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val library = graph.activeLibrary
    val history = remember(library) { library.continueWatching() }
    val rows by history.collectAsState(initial = emptyList())
    val gridFocus = remember { FocusRequester() }
    val emptyFocus = remember { FocusRequester() }
    var filters by rememberCatalogFilters(graph, "collection:${detail.id}")
    var filterOpen by remember(detail.id) { mutableStateOf(false) }
    val filterFocus = remember { FocusRequester() }
    val allCells = remember(rows) {
        rows.map { row ->
            CollectionCell(
                canonicalId = row.canonicalId,
                mediaType = row.mediaType,
                title = titleWithoutYear(row.title),
                posterUrl = row.posterUrl,
                year = yearIn(row.title),
                continuationLabel = HomePlan.continueLine(row).takeIf(String::isNotBlank),
                progress = row.progressFraction,
            )
        }
    }

    val cards = remember(allCells) { allCells.map { HomeCard(it.canonicalId, it.mediaType, it.title, posterUrl = it.posterUrl, year = it.year) } }
    val browse = rememberCatalogBrowse(cards, graph, filters, filterOpen || filters.active, scopeKey = "continue:${detail.id}", benchmarkSurface = "continue_watching")
    var titleQuery by rememberSaveable(detail.id) { mutableStateOf("") }
    var titleSearchOpen by remember(detail.id) { mutableStateOf(false) }
    val actionOrigin = rememberSaveable(detail.id) { mutableStateOf<String?>(null) }
    val actions = remember(actionOrigin) { CatalogRailFocus(actionOrigin) }
    var actionReturnKey by remember(detail.id) { mutableStateOf<String?>(null) }
    var filtersFromRail by remember(detail.id) { mutableStateOf(false) }
    val cells = remember(browse.items, allCells, titleQuery) {
        val original = allCells.associateBy { "${it.mediaType}:${it.canonicalId}" }
        browse.items.filterCollectionSearch(titleQuery, HomeCard::title).mapNotNull { card ->
            original[card.key]?.copy(year = card.year, posterUrl = card.posterUrl)
        }
    }

    fun resume(cell: CollectionCell) {
        val row = rows.firstOrNull { it.canonicalId == cell.canonicalId } ?: return
        scope.launch {
            val result = graph.playFlow.play(
                PlayRequest(
                    type = cell.mediaType,
                    id = cell.canonicalId,
                    title = cell.title,
                    season = row.season,
                    episode = row.episode,
                    posterUrl = cell.posterUrl,
                    backdropUrl = row.backdropUrl,
                    resumeFromMs = row.positionMs,
                ),
            )
            when (result) {
                PlayResult.ShowList -> nav.openStreams(
                    cell.mediaType,
                    cell.canonicalId,
                    row.season,
                    row.episode,
                )
                is PlayResult.Failed -> nav.toast(result.message)
                PlayResult.Opened -> Unit
            }
        }
    }

    CollectionFrame(
        detail = detail,
        sourceName = null,
        staleLabel = null,
        refreshFailed = false,
        moving = false,
        sortLabel = filters.label,
        sortModifier = Modifier.focusRequester(filterFocus).focusProperties {
            down = if (cells.isEmpty()) emptyFocus else gridFocus
        },
        onSort = { filterOpen = true },
        onEdit = { nav.openCollectionEditor(detail.id.toString()) },
        searchField = {
            if (titleSearchOpen) TvSearchField("Search titles", titleQuery, { titleQuery = it },
                Modifier.width(560.dp).focusProperties { this.down = if (cells.isEmpty()) emptyFocus else gridFocus },
                openKeyboard = true, enabled = !filterOpen, onClose = {
                    titleSearchOpen = false
                    scope.launch { withFrameNanos { }; actions.restoreAction() }
                })
        },
        actionRail = {
            CatalogActionRail(
                actions,
                actions = listOf(
                    CatalogAction(R.drawable.ic_rail_search, if (titleQuery.isEmpty()) "Search titles" else "Search: $titleQuery") { titleSearchOpen = true },
                    CatalogAction(R.drawable.ic_catalog_filter, "Filters & sort") { filtersFromRail = true; filterOpen = true },
                    CatalogAction(R.drawable.ic_catalog_pin, "Add to Den TV Home") {
                        LauncherRails.collection(context, detail.id, nav::toast)
                    },
                ),
                onLeave = { key ->
                    if (key != null && cells.any { it.canonicalId == key }) actionReturnKey = key
                    else runCatching { (if (cells.isEmpty()) emptyFocus else gridFocus).requestFocus() }
                },
                modifier = Modifier.align(Alignment.CenterEnd),
                enabled = !filterOpen && !titleSearchOpen,
            )
        },
        onRetry = {},
        countOverride = cells.size,
    ) {
        if (cells.isEmpty() && titleQuery.isNotEmpty()) {
            EmptyState("No matching titles", "Try a different title.", actionLabel = "Clear search",
                onAction = { titleQuery = ""; runCatching { filterFocus.requestFocus() } },
                actionModifier = Modifier.focusRequester(emptyFocus))
        } else if (cells.isEmpty() && filters.active) {
            EmptyState(if (browse.pending > 0 || browse.updating) "Checking title metadata…" else "No matching titles",
                browse.status(), actionLabel = "Reset filters", onAction = { filters = CatalogFilters(); runCatching { filterFocus.requestFocus() } },
                actionModifier = Modifier.focusRequester(emptyFocus))
        } else if (cells.isEmpty()) {
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
                filterFocus = filterFocus,
                placed = placed,
                restoreOnEntry = !filterOpen && !titleSearchOpen,
                movingId = null,
                actions = actions,
                actionReturnKey = actionReturnKey,
                onActionReturned = { actionReturnKey = null },
                onOpen = ::resume,
                onLongClick = null,
                onPlay = ::resume,
                onMove = {},
                onCommit = {},
                onCancelMove = {},
            )
        }
    }
    if (filterOpen) CatalogFilterPanel(filters, browse, { filters = it }, onClose = {
        filterOpen = false
        if (filtersFromRail) {
            filtersFromRail = false
            scope.launch { withFrameNanos { }; actions.restoreAction() }
        } else runCatching { filterFocus.requestFocus() }
    })
    LaunchedEffect(cells.isEmpty(), placed) {
        if (placed && cells.isEmpty() && !filterOpen && !titleSearchOpen) placeEntryFocus { emptyFocus }
    }
}

/**
 * User-opened TorBox account browse. Complete paginated results and actual file IDs stay
 * independent of phone connectivity; unreadiness never starts an acquisition.
 */
@Composable
private fun MyCloudFolder(graph: ClientGraph, detail: CollectionDetail, nav: ClientNav) {
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val services by graph.services.collectAsState()
    val account by remember(services) { services?.debrid?.libraryState ?: kotlinx.coroutines.flow.MutableStateFlow(TorboxLibraryState()) }.collectAsState()
    var fileQuery by rememberSaveable(detail.id) { mutableStateOf("") }
    var fileSearchOpen by remember(detail.id) { mutableStateOf(false) }
    val actions = remember(detail.id) { CatalogRailFocus() }
    val rows = remember(account.rows, fileQuery) {
        if (fileQuery.isEmpty()) account.rows else account.rows.filter { torrent ->
            torrent.name.contains(fileQuery, ignoreCase = true) || torrent.files.any { file ->
                file.isPlayableVideo() && file.name.contains(fileQuery, ignoreCase = true)
            }
        }
    }
    val loading = account.loading
    var message by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<TorboxLibraryTorrent?>(null) }
    var request by remember { mutableStateOf(0) }
    var playJob by remember { mutableStateOf<Job?>(null) }
    val opening = playJob != null
    var refreshFromRail by remember { mutableStateOf(false) }
    LaunchedEffect(services, request) {
        val ready = services ?: return@LaunchedEffect
        message = null
        ready.debrid.refreshTorboxLibrary(force = request > 0)
        if (!refreshFromRail) {
            withFrameNanos { }
            runCatching { focus.requestFocus() }
        }
        refreshFromRail = false
    }
    fun play(torrent: TorboxLibraryTorrent, file: DebridFile) {
        if (!torrent.ready) { message = "Not ready in TorBox. Wait for it to finish, then Refresh."; return }
        if (playJob != null) return
        message = null
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val filename = file.name.replace('\\', '/').substringAfterLast('/')
            val row = StreamRow("saved-torbox:${torrent.infoHash}:${file.id}:${torrent.id}", "TorBox Library", torrent.name, file.name,
                infoHash = torrent.infoHash, torboxTorrentID = torrent.id, torboxFileID = file.id, exactSource = true,
                filename = file.name, sizeBytes = file.sizeBytes, fileIdx = file.torrentIndex)
            try {
                when (val result = graph.playFlow.playRow(row, PlayRequest("movie", row.id, filename))) {
                    PlayResult.Opened -> selected = null
                    is PlayResult.Failed -> message = result.message
                    PlayResult.ShowList -> message = "TorBox could not open this video. Try again."
                }
            } finally {
                if (playJob === coroutineContext[Job]) playJob = null
            }
        }
        playJob = job
        job.start()
    }
    CollectionFrame(
        detail = detail.copy(name = "My Library"),
        sourceName = "Your complete TorBox torrent account · newest additions first",
        staleLabel = null,
        refreshFailed = false,
        moving = false,
        sortLabel = null,
        sortModifier = Modifier,
        onSort = {},
        onEdit = { nav.openSettings() },
        onRetry = { request++ },
        countOverride = rows.size,
        searchField = {
            if (fileSearchOpen) TvSearchField("Search library", fileQuery, { fileQuery = it },
                Modifier.width(560.dp).focusProperties { this.down = focus },
                openKeyboard = true, enabled = !opening && selected == null, onClose = {
                    fileSearchOpen = false
                    scope.launch { withFrameNanos { }; actions.restoreAction() }
                })
        },
        actionRail = {
            CatalogActionRail(
                actions,
                listOf(
                    CatalogAction(R.drawable.ic_rail_search, if (fileQuery.isEmpty()) "Search library" else "Search: $fileQuery") {
                        fileSearchOpen = true
                    },
                    CatalogAction(R.drawable.ic_catalog_refresh, if (loading) "Refreshing…" else "Refresh library", !loading) {
                        refreshFromRail = true
                        request++
                    },
                ),
                onLeave = { if (!actions.restoreTarget()) runCatching { focus.requestFocus() } },
                modifier = Modifier.align(Alignment.CenterEnd),
                enabled = !opening && selected == null && !fileSearchOpen,
            )
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvButton("TV settings", { nav.openSettings() }, kind = ButtonKind.Secondary)
            }
            if (selected == null) message?.let { Text(it, style = TvType.Meta, color = TvColor.Warning) }
            account.message?.let { Text(it, style = TvType.Meta, color = TvColor.Warning) }
            Spacer(Modifier.height(18.dp))
            if (!loading && rows.isEmpty() && fileQuery.isNotEmpty()) EmptyState(
                headline = "No matching files", line = "Try a different name.", actionLabel = "Clear search",
                onAction = { fileQuery = "" }, actionModifier = Modifier.focusRequester(focus),
            )
            else if (!loading && rows.isEmpty()) EmptyState(headline = if (account.message != null) "My Library could not load" else "No TorBox items", line = message ?: account.message ?: "No torrents are in this account yet.",
                actionLabel = "Refresh", onAction = { request++ }, actionModifier = Modifier.focusRequester(focus))
            else LazyColumn(Modifier.fillMaxSize().focusRequester(focus).focusRestorer().focusGroup(),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(rows, key = { it.id }) { torrent ->
                    val itemFocus = remember(torrent.id) { FocusRequester() }
                    TvFocusableCard(onClick = {
                        if (!torrent.ready) message = "Not ready in TorBox. Wait for it to finish, then Refresh."
                        else if (!opening) {
                            message = null
                            val files = torrent.files.filter(DebridFile::isPlayableVideo)
                            if (files.size == 1) play(torrent, files.first()) else selected = torrent
                        }
                    }, modifier = Modifier.focusRequester(itemFocus).onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown || event.key != Key.DirectionRight) return@onKeyEvent false
                        actions.enter(torrent.id.toString()) { itemFocus.requestFocus() }
                    }, focusScale = 1f, focusRing = true) { focused ->
                        Column(Modifier.fillMaxWidth().background(if (focused) TvColor.Elevated2 else TvColor.Elevated)
                            .padding(20.dp)) {
                            Text(torrent.name, style = TvType.CardTitle,
                                color = TvColor.TextPrimary, maxLines = 2)
                            Text((if (torrent.ready) "Ready" else "Not ready") + " · ${torrent.files.count(DebridFile::isPlayableVideo)} video files" +
                                torrent.createdAt?.let { " · Added ${it.take(10)}" }.orEmpty(),
                                style = TvType.Meta, color = TvColor.TextSecondary)
                        }
                    }
                }
            }
        }
    }
    selected?.let { torrent ->
        val matches = if (torrent.name.contains(fileQuery, ignoreCase = true)) "" else fileQuery
        val files = torrent.files.filter(DebridFile::isPlayableVideo).filterCollectionSearch(matches, DebridFile::name)
        TorboxFilePanel(torrent.name, files, opening, onSelect = { play(torrent, it) }, onClose = {
            playJob?.cancel()
            playJob = null
            selected = null
            scope.launch { withFrameNanos { }; runCatching { focus.requestFocus() } }
        }, error = message)
    }
}

/** The provider's actual files; no guessed episode binding or largest-file substitution. */
@Composable
internal fun TorboxFilePanel(title: String, files: List<DebridFile>, opening: Boolean,
    onSelect: (DebridFile) -> Unit, onClose: () -> Unit, error: String? = null) {
    val focus = remember { FocusRequester() }
    Dialog(onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Column(Modifier.width(1080.dp).height(760.dp).background(TvColor.Background).padding(32.dp)) {
            Text("Choose a file · $title", style = TvType.PanelHeader, color = TvColor.TextPrimary, maxLines = 2)
            Spacer(Modifier.height(18.dp))
            TvButton(if (opening) "Opening… · Back cancels" else "Back", onClose, kind = ButtonKind.Secondary,
                modifier = if (files.isEmpty()) Modifier.focusRequester(focus) else Modifier)
            error?.let { Text(it, style = TvType.Meta, color = TvColor.Warning) }
            Spacer(Modifier.height(18.dp))
            if (files.isEmpty()) Text("No playable video files were returned by TorBox.", style = TvType.Body, color = TvColor.TextSecondary)
            else LazyColumn(Modifier.fillMaxWidth().weight(1f).focusRequester(focus).focusRestorer().focusGroup(),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(files, key = { it.id ?: it.name }) { file ->
                    SidePanelRow(file.name, selected = false, onClick = { if (!opening) onSelect(file) })
                }
            }
        }
        LaunchedEffect(Unit) {
            repeat(12) { withFrameNanos { }; if (runCatching { focus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect }
        }
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

/** The shared collection header, content and optional action rail. */
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
    actionRail: (@Composable BoxScope.() -> Unit)? = null,
    searchField: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    // Parsed once per accent, not on every recomposition of the header.
    val accent = remember(detail.accent) { accentColor(detail.accent) }
    DrillInFrame(modifier = Modifier.throttleDpadRepeats()) {
        Column(Modifier.fillMaxSize().padding(end = if (actionRail != null && !moving) CatalogRailGutter else 0.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(8.dp, 72.dp).background(accent))
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        detail.name,
                        style = TvType.ScreenTitle.copy(fontFamily = TvType.HeroTitle.fontFamily),
                        color = TvColor.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        CollectionGrid.metaLine(countOverride ?: detail.items.size, staleLabel) +
                            if (sortLabel != null && !moving && (countOverride ?: detail.items.size) > 0)
                                " · Right for catalog actions" else "",
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
                        sortLabel,
                        onSort,
                        kind = ButtonKind.Secondary,
                        modifier = sortModifier.width(360.dp),
                    )
                    Spacer(Modifier.width(24.dp))
                }
                TvButton("Edit", onEdit, kind = ButtonKind.Secondary, modifier = Modifier.width(200.dp))
            }
            Spacer(Modifier.height(TvSpace.M))
            searchField()
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
        if (!moving) actionRail?.invoke(this)
    }
}

@Composable
private fun PosterGrid(
    cells: List<CollectionCell>,
    gridFocus: FocusRequester,
    filterFocus: FocusRequester,
    placed: Boolean,
    restoreOnEntry: Boolean,
    movingId: String?,
    actions: CatalogRailFocus? = null,
    actionReturnKey: String? = null,
    onActionReturned: () -> Unit = {},
    onOpen: (CollectionCell) -> Unit,
    onLongClick: ((CollectionCell) -> Unit)?,
    onPlay: (CollectionCell) -> Unit,
    onMove: (MoveDirection) -> Unit,
    onCommit: () -> Unit,
    onCancelMove: () -> Unit,
    letterboxdItems: Map<String, CatalogItem> = emptyMap(),
    onItemOptions: ((CollectionCell) -> Unit)? = null,
) {
    val state = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val rows = remember(state, scope) { GridRowFocus(state, scope, CollectionGrid.COLUMNS) }
    var lastFocusedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var restoreKey by remember { mutableStateOf<String?>(null) }
    val returnFocus = remember { FocusRequester() }
    LaunchedEffect(actionReturnKey) {
        val key = actionReturnKey ?: return@LaunchedEffect
        val index = cells.indexOfFirst { it.canonicalId == key }
        if (index >= 0) {
            if (state.layoutInfo.visibleItemsInfo.none { it.key == key }) state.scrollToItem(index)
            repeat(FOCUS_ATTEMPTS) {
                withFrameNanos { }
                if (rows.request(key) && lastFocusedKey == key) {
                    onActionReturned()
                    return@LaunchedEffect
                }
            }
        }
        placeEntryFocus { gridFocus }
        onActionReturned()
    }
    LaunchedEffect(placed) {
        if (!placed || !restoreOnEntry) return@LaunchedEffect
        val key = lastFocusedKey
        val index = cells.indexOfFirst { it.canonicalId == key }
        restoreKey = key.takeIf { index >= 0 }
        if (index >= 0 && state.layoutInfo.visibleItemsInfo.none { it.key == key }) state.scrollToItem(index)
        withFrameNanos { }
        placeEntryFocus { if (index >= 0) returnFocus else gridFocus }
        restoreKey = null
    }
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
            .onKeyEvent { event ->
                if (movingId != null || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                if (event.key != Key.DirectionUp && event.key != Key.DirectionDown) rows.cancel()
                val index = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == lastFocusedKey }?.index
                    ?: return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> index % CollectionGrid.COLUMNS == 0 && filterFocus.requestFocus()
                    Key.DirectionRight -> {
                        val key = cells[index].canonicalId
                        (index == cells.lastIndex || (index + 1) % CollectionGrid.COLUMNS == 0) &&
                            actions?.enter(key) { rows.request(key) } == true
                    }
                    Key.DirectionUp -> if (index < CollectionGrid.COLUMNS) filterFocus.requestFocus()
                        else rows.move(index, down = false, cells.size) { cells[it].canonicalId }
                    Key.DirectionDown -> rows.move(index, down = true, cells.size) { cells[it].canonicalId }
                    else -> false
                }
            }
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
            val source = letterboxdItems[cell.canonicalId]
            val artwork = remember(source) { source?.toHomeCard(recoverPoster = true, preserveIdentity = true) }
            PosterCell(
                cell = cell,
                artwork = artwork,
                modifier = rows.card(cell.canonicalId)
                    .then(if (cell.canonicalId == restoreKey) Modifier.focusRequester(returnFocus) else Modifier)
                    .onFocusChanged { if (it.isFocused) lastFocusedKey = cell.canonicalId }
                    .onPreviewKeyEvent { event ->
                        if (event.key == Key.Menu && onItemOptions != null) {
                            if (event.type == KeyEventType.KeyDown) onItemOptions(cell)
                            true
                        } else false
                    },
                dimmed = movingId != null && movingId != cell.canonicalId,
                picked = movingId == cell.canonicalId,
                onSelectPressed = rows::cancel,
                onClick = { lastFocusedKey = cell.canonicalId; onOpen(cell) },
                onLongClick = onLongClick?.let { action -> { lastFocusedKey = cell.canonicalId; action(cell) } },
                onPlay = { lastFocusedKey = cell.canonicalId; onPlay(cell) },
            )
        }
    }
}

@Composable
internal fun PosterCell(
    cell: CollectionCell,
    artwork: HomeCard? = null,
    modifier: Modifier = Modifier,
    dimmed: Boolean,
    picked: Boolean,
    onSelectPressed: () -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onPlay: () -> Unit,
    addedLabel: String? = null,
    actionLabel: String? = null,
    selectionMode: Boolean = false,
    playLabel: String? = "Play",
) {
    Column(Modifier.width(TvGeom.PosterWidth).moveModeDim(dimmed)) {
        LongPressFocusable(
            onClick = onClick,
            modifier = modifier,
            onSelectPressed = onSelectPressed,
            onLongClick = onLongClick,
            onPlay = onPlay,
            accessibleLabel = accessibilityLabel(cell.title, cell.year?.toString(), cell.mediaType, cell.continuationLabel, addedLabel),
            clickLabel = when {
                selectionMode -> if (picked) "Deselect item" else "Select item"
                cell.canonicalId.startsWith(WatchlistSource.PREFIX) || onLongClick == null -> "Play"
                else -> "Open details"
            },
            longClickLabel = actionLabel ?: "Love; save to Favorites",
            selected = picked,
            customActions = listOfNotNull(playLabel?.let { CustomAccessibilityAction(it) { onPlay(); true } },
                if (actionLabel != null && onLongClick != null)
                    CustomAccessibilityAction(actionLabel) { onLongClick(); true } else null),
            cornerRadius = 12.dp,
            focusScale = if (addedLabel != null) 1.05f else TvGeom.FocusScale,
        ) { _ ->
            Column {
                Box(
                    modifier = Modifier
                        .size(TvGeom.PosterWidth, TvGeom.PosterHeight)
                        .clip(TvShape.Card)
                        .background(if (picked) TvColor.Selection.copy(alpha = 0.10f) else Color.Transparent),
                ) {
                    // `TvArtwork`, not `Poster`: a title with no poster url used to draw a bare
                    // rectangle with nothing in it. The shared block puts the title inside the
                    // placeholder and drops it the moment the image paints (spec §16.1).
                    TvArtwork(
                        request = PosterRequest.poster(artwork?.posterUrl ?: cell.posterUrl),
                        title = cell.title,
                        modifier = Modifier.fillMaxSize(),
                        posterLookupId = artwork?.posterLookupId,
                        posterLookupType = artwork?.type,
                        posterLookupTmdbID = artwork?.tmdbID,
                    )
                    cell.continuationLabel?.let { label ->
                        Text(label, style = TvType.Meta, color = TvColor.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp).tvGlassPanel(12.dp)
                                .padding(horizontal = 10.dp, vertical = 7.dp))
                    }
                    if (selectionMode && picked) {
                        Text("✓", style = TvType.ControlLabel, color = TvColor.TextPrimary,
                            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).tvGlassPanel(12.dp)
                                .padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                    if (cell.progress > 0f) TvProgressBar(cell.progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth(), height = 6.dp)
                }
                if (addedLabel != null) {
                    Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
                        PosterCaption(cell, addedLabel)
                    }
                }
            }
        }
        if (addedLabel == null) PosterCaption(cell, null)
    }
}

@Composable
private fun PosterCaption(cell: CollectionCell, addedLabel: String?) {
    Spacer(Modifier.height(TvGeom.FocusLabelGap))
    Text(
        cell.title,
        style = TvType.CardTitle,
        color = TvColor.TextPrimary,
        maxLines = if (addedLabel == null) 1 else 2,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        addedLabel ?: cell.year?.toString().orEmpty(),
        style = TvType.data(if (addedLabel != null) 22 else 20),
        color = TvColor.TextMuted,
        maxLines = if (addedLabel == null) 1 else 2,
        overflow = TextOverflow.Ellipsis,
    )
}

// ------------------------------------------------------------------ panels

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
    SidePanel(header = cell.title, onClose = onClose) {
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
        if (runCatching { target().requestFocus() }.getOrDefault(false)) return
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
