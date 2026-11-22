@file:Suppress("OPT_IN_USAGE")
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.fourseveneightnine.tv.client.ui.screens.collections

import androidx.compose.runtime.produceState
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics
import com.fourseveneightnine.tv.client.OfflineLetterboxd
import com.fourseveneightnine.tv.catalog.TVLetterboxdClient
import com.fourseveneightnine.tv.catalog.TVLetterboxdListRefresh
import com.fourseveneightnine.tv.client.awaitingPhoneImport
import com.fourseveneightnine.tv.client.playback.episodeFiles
import com.fourseveneightnine.tv.client.playback.movieBundleFiles
import com.fourseveneightnine.tv.client.playback.TamilMVReleaseFile
import com.fourseveneightnine.tv.client.ui.components.TvSearchField
import com.fourseveneightnine.tv.client.ui.components.PanelTransition
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.R
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.home.LauncherRails
import com.fourseveneightnine.tv.client.ui.LocalShellState
import com.fourseveneightnine.tv.client.ui.components.BadgeSpec
import com.fourseveneightnine.tv.client.ui.components.CatalogAction
import com.fourseveneightnine.tv.client.ui.components.CatalogActionRail
import com.fourseveneightnine.tv.client.ui.components.CatalogRailFocus
import com.fourseveneightnine.tv.client.ui.components.CatalogRailGutter
import com.fourseveneightnine.tv.client.ui.components.SidePanel
import com.fourseveneightnine.tv.client.ui.components.SidePanelRow
import com.fourseveneightnine.tv.client.ui.components.EmptyState
import com.fourseveneightnine.tv.client.ui.components.GridRowFocus
import com.fourseveneightnine.tv.client.ui.components.TvBadge
import com.fourseveneightnine.tv.client.ui.components.TvButton
import com.fourseveneightnine.tv.client.data.streams.debrid.TorboxLibraryState
import com.fourseveneightnine.tv.client.ui.components.Skeleton
import com.fourseveneightnine.tv.client.ui.components.accessibilityLabel
import com.fourseveneightnine.tv.client.ui.nav.ClientNav
import com.fourseveneightnine.tv.client.ui.screens.TopLevelScaffold
import com.fourseveneightnine.tv.client.data.catalog.Shelf
import com.fourseveneightnine.tv.client.data.catalog.ShelfKind
import com.fourseveneightnine.tv.client.data.catalog.TamilMVCatalogRepository
import com.fourseveneightnine.tv.client.data.streams.StreamFacts
import com.fourseveneightnine.tv.client.data.streams.StreamRow
import com.fourseveneightnine.tv.client.playback.PlayRequest
import com.fourseveneightnine.tv.client.playback.PlayResult
import com.fourseveneightnine.tv.client.playback.probeTamilMVMovie
import com.fourseveneightnine.tv.client.ui.screens.discover.DiscoverPlan
import com.fourseveneightnine.tv.client.ui.screens.discover.rememberCatalogFilters
import com.fourseveneightnine.tv.client.ui.screens.discover.CatalogFilters
import com.fourseveneightnine.tv.client.ui.screens.discover.CatalogFilterPanel
import com.fourseveneightnine.tv.client.ui.screens.discover.rememberCatalogBrowse
import com.fourseveneightnine.tv.client.ui.screens.discover.PosterGrid
import com.fourseveneightnine.tv.client.ui.screens.detail.DetailPreview
import com.fourseveneightnine.tv.client.ui.screens.home.toHomeCard
import com.fourseveneightnine.tv.client.ui.screens.home.HomeCard
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvGeom
import com.fourseveneightnine.tv.client.ui.theme.TvShape
import com.fourseveneightnine.tv.client.ui.theme.TvSpace
import com.fourseveneightnine.tv.client.ui.theme.TvType
import com.fourseveneightnine.tv.ui.throttleDpadRepeats
import com.fourseveneightnine.tv.client.ui.theme.LocalReduceMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The Collections folder grid, spec §5.
 *
 * Three columns of poster stacks, with local folders before synced lists.
 */
@Composable
internal fun CollectionsScreen(
    nav: ClientNav,
    active: Boolean,
    restoringPoster: Boolean = false,
    onPosterRestored: () -> Unit = {},
) {
    val context = LocalContext.current
    val graph = remember(context) { context.clientGraph }
    val shell = LocalShellState.current
    val scope = rememberCoroutineScope()

    val library = graph.activeLibrary
    val summaries by remember(library) { library.collections() }.collectAsState(initial = null)
    val continuePreview by remember(library) {
        library.continueWatching().map { rows ->
            rows.size to rows.asSequence().mapNotNull { it.posterUrl }
                .filter(String::isNotBlank).distinct().take(FolderGrid.POSTER_COUNT).toList()
        }.distinctUntilChanged()
    }.collectAsState(initial = 0 to emptyList())
    val synced by graph.collectionShelves.collectAsState()
    val offlineRevision by graph.offlineLetterboxd.revision.collectAsState()
    val offline by produceState(OfflineLetterboxd.Directory.Empty, graph, offlineRevision) {
        value = graph.offlineLetterboxd.directory()
    }
    val shelves = remember(synced, offline) {
        val bundledKeys = offline.shelves.mapTo(HashSet()) { it.id.removePrefix(OfflineLetterboxd.PREFIX) }
        synced.filterNot { TVLetterboxdClient.savedList(it)?.let { list -> list.key in bundledKeys } == true } + offline.shelves
    }
    val posterDownload by graph.offlineLetterboxd.download.collectAsState()
    val publicLists by graph.publicLists.state.collectAsState()
    val lists = remember(shelves, publicLists.stamps, offline.counts) { SyncedLists.visible(shelves, publicLists.stamps, offline.counts) }
    val publicKeys = remember(shelves) { shelves.mapNotNull { TVLetterboxdClient.savedList(it)?.key }.toSet() }
    LaunchedEffect(publicKeys) {
        if (publicKeys.isNotEmpty()) {
            graph.publicLists.hydrate()
            graph.publicLists.refresh(lists, bulk = true, resume = true)
        }
    }
    val services by graph.services.collectAsState()
    val account by remember(services) { services?.debrid?.libraryState ?: kotlinx.coroutines.flow.MutableStateFlow(TorboxLibraryState()) }.collectAsState()
    val hasDebridKey = remember(services) {
        val document = services?.document
        !document?.torboxAPIKey.isNullOrBlank()
    }

    // Safe to call every time; a second call writes nothing (API-B, library).
    LaunchedEffect(Unit) { runCatching { graph.activeLibrary.ensureSystemCollections() } }
    LaunchedEffect(services) { if (services != null) graph.scheduler.onForeground() }

    val gridFocus = remember { FocusRequester() }
    var moving by remember { mutableStateOf<MoveState?>(null) }
    val moveGate = remember { Mutex() }
    var naming by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf<FolderCardModel?>(null) }
    var openedShelfId by rememberSaveable { mutableStateOf<String?>(null) }
    var directoryQuery by rememberSaveable { mutableStateOf("") }
    var collectionsView by rememberSaveable { mutableStateOf(CollectionsView.All) }
    var showDirectoryFilters by remember { mutableStateOf(false) }
    var informationShelfId by rememberSaveable { mutableStateOf<String?>(null) }

    val cards = remember(summaries, hasDebridKey, account, continuePreview) {
        summaries?.let { FolderGrid.cards(it, hasDebridKey = true) }?.map { card ->
            when (card.role) {
                FolderRole.ContinueWatching -> card.copy(
                    count = continuePreview.first,
                    posters = continuePreview.second,
                )
                FolderRole.MyCloud -> card.copy(
                    count = account.rows.size,
                    countNotice = when {
                        !hasDebridKey -> "Add TorBox key"
                        !account.loaded && account.loading -> "Loading…"
                        account.message != null -> if (account.loaded) "${account.rows.size} saved · refresh failed" else "Could not load"
                        !account.loaded -> "Not loaded yet"
                        else -> null
                    },
                )
                else -> card
            }
        }
    }
    val matchingCards = remember(cards, directoryQuery, collectionsView) {
        cards?.let { if (collectionsView == CollectionsView.All) it.filterCollectionSearch(directoryQuery, FolderCardModel::name) else emptyList() }
    }
    val matchingLists = remember(lists, directoryQuery, collectionsView, offline.information, publicLists.stamps) {
        SyncedLists.filtered(lists, collectionsView, offline.information, publicLists.stamps)
            .filterCollectionSearch(directoryQuery) { shelf ->
                val key = shelf.researchKey()
                listOfNotNull(shelf.title, key?.substringBefore('/'), offline.information[key]?.description)
                    .joinToString(" ")
            }
    }

    if (com.fourseveneightnine.tv.BuildConfig.BENCHMARK_LOGGING) {
        LaunchedEffect(active, collectionsView, matchingLists, matchingCards) {
            if (active) ReceiverDiagnostics.benchmark("collections.directory.result") {
                "filter=${collectionsView.name} folders=${matchingCards?.size ?: 0} lists=${matchingLists.size}"
            }
        }
    }
    // The rail borrows focus and must be able to give it back (spec §1.5). The target follows what
    // is drawn: on the empty screen the grid does not exist, and handing focus to a node that is
    // not there strands the ring in a rail that has just closed.
    val directoryActive = active && openedShelfId == null && options == null && !naming &&
        !showDirectoryFilters && informationShelfId == null
    DisposableEffect(directoryActive) {
        if (!directoryActive) return@DisposableEffect onDispose {}
        val restore = { runCatching { gridFocus.requestFocus() }; Unit }
        shell.restoreContentFocus = restore
        onDispose { if (shell.restoreContentFocus === restore) shell.restoreContentFocus = null }
    }

    fun finishMove(cancel: Boolean) {
        val session = moving ?: return
        session.cancelled = cancel
        moving = null // Commit drains accepted arrows; Cancel retires queued arrows before undo.
        graph.scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
            moveGate.withLock {
                try {
                    if (cancel) {
                        val undo = MoveMode.undoDelta(session.applied)
                        if (undo != 0) session.library.moveCollection(session.id, undo)
                    } else nav.toast("Order saved.")
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { nav.toast("Order could not be saved. Try again.") }
            }
        }
    }

    TopLevelScaffold(title = "Collections", onOpenRail = nav::openRail) {
        // The scaffold ends its title at y 102 and rests 36 px, so its content starts at y 138.
        // Spec §5.2 puts the count line at y 112 and the grid band at y 160. The scaffold is shared
        // with five other screens and is not this group's to change, so the shift is taken here.
        Column(Modifier.fillMaxSize().offset(y = -SCAFFOLD_GAP_TRIM)) {
            when {
                matchingCards == null -> LoadingGrid()
                else -> LoadedGrid(
                    cards = matchingCards,
                    syncedLists = matchingLists,
                    collectionsView = collectionsView,
                    onFilter = { showDirectoryFilters = true },
                    onResetView = { collectionsView = CollectionsView.All },
                    onInformation = { informationShelfId = it.id },
                    offlineCounts = offline.counts,
                    posterDownload = posterDownload,
                    onPosterDownload = graph.offlineLetterboxd::toggleDownload,
                    query = directoryQuery,
                    onSearch = { directoryQuery = it },
                    listRefresh = publicLists,
                    cachedPortrait = remember(graph) { graph.letterboxd::cachedPortrait },
                    canRefreshLists = lists.any { TVLetterboxdClient.savedList(it) != null },
                    onRefreshLists = { graph.publicLists.refresh(lists, bulk = true) },
                    moving = moving,
                    gridFocus = gridFocus,
                    active = directoryActive,
                    onOpen = { nav.openCollection(it.id.toString()) },
                    onOpenSynced = { openedShelfId = it.id },
                    onEdit = { options = it },
                    onNew = { naming = true },
                    onStartMove = { card ->
                        if (!card.isSystem) {
                            directoryQuery = ""
                            moving = MoveState(card.id, graph.activeLibrary)
                            nav.toast("Move mode. Arrows move the folder. OK drops it.")
                        }
                    },
                    onMove = { direction ->
                        val session = moving ?: return@LoadedGrid
                        val delta = MoveMode.delta(direction, FolderGrid.COLUMNS)
                        graph.scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
                            moveGate.withLock {
                                if (session.cancelled) return@withLock
                                try {
                                    if (session.library.moveCollection(session.id, delta)) {
                                        // Record every committed write even if Back retired the UI while Room awaited.
                                        session.applied = session.applied + delta
                                    }
                                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                catch (_: Exception) { nav.toast("Order could not be saved. Try again.") }
                            }
                        }
                    },
                    onCommit = { finishMove(cancel = false) },
                    onCancelMove = { finishMove(cancel = true) },
                )
            }
        }
    }

    if (naming) {
        KeyboardOverlay(
            reason = "Name this collection",
            initial = "",
            onCancel = { naming = false },
            onDone = { name ->
                naming = false
                scope.launch {
                    val id = graph.activeLibrary.create(name, Accents.DEFAULT)
                    nav.openCollectionEditor(id.toString())
                }
            },
        )
    }

    PanelTransition(lists.firstOrNull { it.id == openedShelfId }, fade = true) { shelf ->
        SnapshotShelfGridOverlay(
            shelf = shelf,
            offlineCount = offline.counts[shelf.id],
            active = active && openedShelfId == shelf.id && informationShelfId == null,
            restoringPoster = restoringPoster && openedShelfId == shelf.id && informationShelfId == null,
            onPosterRestored = onPosterRestored,
            onInformation = { informationShelfId = shelf.id },
            onDismiss = { openedShelfId = null },
            nav = nav,
        )
    }

    PanelTransition(visible = showDirectoryFilters) {
        CollectionsViewPanel(collectionsView, active && showDirectoryFilters, onClose = { showDirectoryFilters = false }, onSelect = {
            collectionsView = it
            showDirectoryFilters = false
        })
    }

    PanelTransition(lists.firstOrNull { it.id == informationShelfId }) { shelf ->
        val key = shelf.researchKey()
        val count = offline.counts[shelf.id] ?: publicLists.stamps[key]?.takeIf { SyncedLists.usesPublicCopy(shelf, it) }?.count
        CollectionResearchPanel(shelf, count, offline.information[key], active && informationShelfId == shelf.id) {
            informationShelfId = null
        }
    }

    PanelTransition(options) { card ->
        val optionFocus = remember { FocusRequester() }
        fun closeOptions() {
            options = null
            runCatching { if (!gridFocus.restoreFocusedChild()) gridFocus.requestFocus() }
        }
        androidx.activity.compose.BackHandler(enabled = active && options != null) { closeOptions() }
        DisposableEffect(Unit) {
            shell.overlayVisible = true
            onDispose { shell.overlayVisible = false }
        }
        SidePanel(header = card.name, onClose = ::closeOptions) {
            Text("On this TV · editable collection", style = TvType.Meta, color = TvColor.TextSecondary)
            SidePanelRow("Open collection", selected = false, modifier = Modifier.focusRequester(optionFocus),
                onClick = { options = null; nav.openCollection(card.id.toString()) })
            SidePanelRow("Edit collection", selected = false,
                onClick = { options = null; nav.openCollectionEditor(card.id.toString()) })
            SidePanelRow("Move collection", selected = false, onClick = {
                options = null
                directoryQuery = ""
                moving = MoveState(card.id, graph.activeLibrary)
                runCatching { if (!gridFocus.restoreFocusedChild()) gridFocus.requestFocus() }
                nav.toast("Move mode. Arrows move the folder. OK drops it. Back cancels.")
            })
        }
        LaunchedEffect(card.id) { runCatching { optionFocus.requestFocus() } }
    }

}

/** Full, lazy six-column view of a synced phone/server list. */
@Composable
private fun SnapshotShelfGridOverlay(
    shelf: Shelf,
    offlineCount: Int?,
    active: Boolean,
    restoringPoster: Boolean,
    onPosterRestored: () -> Unit,
    onDismiss: () -> Unit,
    onInformation: () -> Unit,
    nav: ClientNav,
) {
    val context = LocalContext.current
    val graph = remember(context) { context.clientGraph }
    val scope = rememberCoroutineScope()
    val tamilStatus = remember(graph) {
        graph.tamilCatalog.state.map { Triple(it.loading, it.updatedAtMillis, it.message) }.distinctUntilChanged()
    }
    val status by tamilStatus.collectAsState(Triple(false, 0L, null))
    val (tamilLoading, tamilUpdatedAt, tamilMessage) = status
    val services by graph.services.collectAsState()
    val isTamil = shelf.id == "tamilmv:collection"
    val snapshots by graph.snapshots.status().collectAsState()
    val source = if (shelf.kind == ShelfKind.TMDB_MOVIES || shelf.kind == ShelfKind.TMDB_SERIES)
        com.fourseveneightnine.tv.client.data.catalog.SnapshotSource.PUBLIC_TMDB
        else com.fourseveneightnine.tv.client.data.catalog.SnapshotSource.PRIVATE_CATALOG
    val snapshot = snapshots.sources.firstOrNull { it.source == source }
    val list = remember(shelf.id, offlineCount) { if (offlineCount == null) TVLetterboxdClient.savedList(shelf) else null }
    val listState by graph.publicLists.state.collectAsState()
    val stamp = listState.stamps[list?.key]?.takeIf { SyncedLists.usesPublicCopy(shelf, it) }
    val needsPreview = SyncedLists.needsPublicPreview(shelf)
    var openingList by remember(shelf.id) { mutableStateOf(false) }
    var previewFailed by remember(shelf.id) { mutableStateOf(false) }
    var retryPreview by remember(shelf.id) { mutableIntStateOf(0) }
    val refreshMessage = when {
        offlineCount != null -> null
        isTamil -> tamilMessage
        list != null -> when {
            openingList || list.key == listState.currentKey -> "Loading this list from Letterboxd…"
            (previewFailed && stamp == null) || list.key in listState.failedKeys ->
                "Couldn’t finish this list. Retry here or sync all profiles in 4789 on your paired iPhone."
            else -> null
        }
        else -> snapshot?.message
    }
    val refreshing = when {
        offlineCount != null -> false
        isTamil -> tamilLoading
        list != null -> openingList || listState.running
        else -> snapshot?.state == com.fourseveneightnine.tv.client.data.catalog.SnapshotState.LOADING
    }
    fun refresh() {
        when {
            offlineCount != null -> Unit
            list != null && stamp == null && needsPreview -> retryPreview++
            list != null -> graph.publicLists.refresh(listOf(shelf), bulk = false)
            isTamil -> scope.launch { graph.refreshReceiverCollections(force = true) }
            else -> graph.scheduler.manual()
        }
    }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val resumed = active && lifecycle == Lifecycle.State.RESUMED
    val shell = LocalShellState.current
    var focusedKey by rememberSaveable(shelf.id) { mutableStateOf<String?>(null) }
    var detailReturnKey by rememberSaveable(shelf.id) { mutableStateOf<String?>(null) }
    var returnFocusKey by remember(shelf.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(shelf.id) {
        if (isTamil && focusedKey == null) graph.refreshReceiverCollections()
    }
    var adding by remember { mutableStateOf<CollectionCandidate?>(null) }
    val loveTitle = rememberLoveTitle(nav)
    var filePickerKey by rememberSaveable(shelf.id) { mutableStateOf<String?>(null) }
    var playJob by remember { mutableStateOf<Job?>(null) }
    var preparingTitle by remember { mutableStateOf<String?>(null) }
    var playFailure by remember(shelf.id) { mutableStateOf<TamilMVPlayFailure?>(null) }
    val recoverPoster = isTamil || shelf.kind == ShelfKind.LETTERBOXD || shelf.kind == ShelfKind.LETTERBOXD_FRIENDS
    val savedCards = remember(shelf.items, recoverPoster, isTamil, offlineCount) {
        shelf.items.mapIndexed { index, item ->
            val card = item.toHomeCard(recoverPoster = recoverPoster, preserveIdentity = true)
            when {
                isTamil -> card.copy(collectionFocusKey = TamilMVCatalogRepository.titleIdentity(item))
                offlineCount != null -> card.copy(collectionFocusKey = "${shelf.id}:$index", metadataLookupAllowed = false)
                else -> card
            }
        }
    }
    var liveCards by remember(shelf.id) { mutableStateOf<List<HomeCard>?>(null) }
    LaunchedEffect(list?.key, stamp, savedCards, needsPreview, retryPreview) {
        if (list == null) return@LaunchedEffect
        if (stamp != null) {
            liveCards = withContext(Dispatchers.IO) {
                graph.publicLists.films(list)?.let { SyncedLists.publicCards(it, savedCards, graph.letterboxd::cachedPortrait) }
            } ?: liveCards
            return@LaunchedEffect
        }
        if (!needsPreview) {
            liveCards = null
            return@LaunchedEffect
        }
        openingList = true
        previewFailed = false
        try {
            graph.publicLists.hydrate()
            val cached = graph.publicLists.state.value.stamps[list.key]
            if (cached != null && SyncedLists.usesPublicCopy(shelf, cached)) return@LaunchedEffect
            val first = graph.publicLists.firstPage(list)
            liveCards = withContext(Dispatchers.Default) {
                SyncedLists.publicCards(first.entries, savedCards, graph.letterboxd::cachedPortrait)
            }
            graph.publicLists.refresh(listOf(shelf), bulk = false, firstPage = first)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            previewFailed = true
        } finally {
            openingList = false
        }
    }
    val previewCards = remember(stamp, savedCards) {
        stamp?.preview?.takeIf { savedCards.isEmpty() }?.let { SyncedLists.publicCards(it, emptyList()) }
    }
    val publicCards = liveCards?.takeIf { stamp != null || needsPreview }
    val downloadState by graph.offlineLetterboxd.download.collectAsState()
    val offlineRevision by graph.offlineLetterboxd.revision.collectAsState()
    val offlineCards by produceState<List<HomeCard>?>(null, shelf.id, offlineCount, downloadState.running, offlineRevision) {
        if (offlineCount != null) value = graph.offlineLetterboxd.cards(shelf.id)
    }
    val cards = if (offlineCount != null) offlineCards ?: savedCards else publicCards ?: previewCards ?: savedCards
    var filters by rememberCatalogFilters(graph, "snapshot:${shelf.id}")
    var filterOpen by remember(shelf.id) { mutableStateOf(false) }
    var titleQuery by rememberSaveable(shelf.id) { mutableStateOf("") }
    var titleSearchOpen by remember(shelf.id) { mutableStateOf(false) }
    val actions = remember(shelf.id) { CatalogRailFocus() }
    var filtersFromRail by remember(shelf.id) { mutableStateOf(false) }
    val browse = rememberCatalogBrowse(cards, graph, filters,
        (filterOpen || filters.active) && (offlineCount == null || offlineCards != null),
        scopeKey = "snapshot:${shelf.id}", allowNetworkMetadata = offlineCount == null)
    val visibleCards = remember(browse.items, titleQuery) {
        browse.items.filterCollectionSearch(titleQuery, HomeCard::title)
    }
    val filterFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    val cardsByKey = remember(cards) { cards.associateBy(HomeCard::key) }
    val focused = cardsByKey[focusedKey]
    var availability by remember(focused?.id, services, shelf.generatedAtMillis, tamilLoading) {
        mutableStateOf<TamilMVAvailability?>(null)
    }
    var returningFromFilter by remember(shelf.id) { mutableStateOf(false) }
    val openedRelease = cardsByKey[filePickerKey]
    val bundleOpen = openedRelease?.isTamilMVMovieBundle() == true
    var releaseFiles by remember(filePickerKey, openedRelease?.id) { mutableStateOf(emptyList<TamilMVReleaseFile>()) }
    var fileStatus by remember(filePickerKey, openedRelease?.id) { mutableStateOf<String?>(null) }
    var retryFiles by remember(filePickerKey) { mutableIntStateOf(0) }
    val fileFocus = remember { FocusRequester() }
    LaunchedEffect(openedRelease?.id, retryFiles, resumed) {
        if (openedRelease == null || !resumed) return@LaunchedEffect
        fileStatus = null
        try {
            val debrid = graph.services.value?.debrid
                ?: error("Set up TorBox in TV Settings or sync it from your iPhone.")
            val recent = availability?.takeIf { retryFiles == 0 && it.isFresh && openedRelease.id == focused?.id }
            releaseFiles = recent?.releaseFiles?.takeIf { it.isNotEmpty() } ?: run {
                val release = graph.resolveCatalogItem(openedRelease.type, openedRelease.id)
                    ?: error("This release is unavailable. Refresh TamilMV Collection.")
                if (bundleOpen) release.movieBundleFiles(debrid) else release.episodeFiles(debrid, includeDeclared = true)
            }
            fileStatus = when {
                releaseFiles.any { it.cached } -> null
                bundleOpen -> "TorBox has not confirmed any playable movie files."
                else -> "TorBox has not confirmed any playable episode files."
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            fileStatus = failure.message ?: "TorBox files could not load. Try again."
        }
        repeat(3) {
            withFrameNanos { }
            if (runCatching { fileFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }

    fun withIdentity(card: HomeCard, action: (HomeCard) -> Unit) {
        if (!card.id.startsWith(TVLetterboxdClient.FILM_ID_PREFIX)) {
            action(card)
            return
        }
        playJob?.cancel()
        playJob = scope.launch {
            val lookup = runCatching { graph.resolveLetterboxdCard(card) }
                .onFailure { if (it is CancellationException) throw it }
            val resolved = lookup.getOrNull()
            if (resolved != null) action(resolved)
            else nav.toast(TVLetterboxdClient.titleLookupMessage(lookup.exceptionOrNull()))
        }
    }

    fun details(card: HomeCard) {
        playJob?.cancel()
        focusedKey = card.key
        detailReturnKey = card.key
        DetailPreview.stage(card)
        nav.openDetail(card.type, card.id)
    }

    fun play(card: HomeCard, episode: TamilMVReleaseFile? = null) {
        if (playJob?.isActive == true) return
        if (!card.id.startsWith("saved:tamilmv:")) {
            nav.toast("No saved TorBox source for this release.")
            return
        }
        val recentMovie = availability?.takeIf {
            it.isFresh && focused?.id == card.id && playFailure?.card?.id != card.id &&
                graph.services.value === services && graph.tamilCatalog.state.value.updatedAtMillis == tamilUpdatedAt
        }?.movie
        playFailure = null
        playJob = scope.launch {
            preparingTitle = episode?.label ?: card.title
            try {
                val bundlePart = episode?.takeIf { card.isTamilMVMovieBundle() }
                val request = PlayRequest(
                    card.type, bundlePart?.stream?.id ?: card.id, bundlePart?.filename?.substringBeforeLast('.') ?: card.title,
                    episode?.season, episode?.episode, posterUrl = card.posterUrl, backdropUrl = card.backdropUrl,
                )
                val result = if (episode == null) {
                    if (recentMovie != null) graph.playFlow.playRow(recentMovie, request)
                    else graph.playFlow.play(request)
                } else {
                    val selected = if (episode.cached) episode else {
                        val debrid = graph.services.value?.debrid ?: error("Set up TorBox in TV Settings.")
                        val release = graph.resolveCatalogItem(card.type, card.id)
                            ?: error("This release is unavailable. Refresh TamilMV Collection.")
                        release.episodeFiles(debrid).firstOrNull {
                            it.season == episode.season && it.episode == episode.episode
                        } ?: error("TorBox has not confirmed this episode is cached.")
                    }
                    graph.playFlow.playRow(selected.stream, request)
                }
                when (result) {
                    PlayResult.Opened -> Unit
                    PlayResult.ShowList -> playFailure = TamilMVPlayFailure(card, episode, "No saved TorBox source for this release.")
                    is PlayResult.Failed -> playFailure = TamilMVPlayFailure(card, episode, result.message)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                playFailure = TamilMVPlayFailure(card, episode, failure.message ?: "Could not prepare this release. Try again.")
            } finally {
                preparingTitle = null
            }
        }
    }

    fun closeFiles() {
        playJob?.cancel()
        filePickerKey = null
    }

    val state = rememberLazyGridState()
    val first = remember { FocusRequester() }
    val gridActive = resumed && openedRelease == null && adding == null && !filterOpen
    val latestCardsByKey by rememberUpdatedState(cardsByKey)
    LaunchedEffect(isTamil, gridActive, tamilLoading, preparingTitle) {
        if (!isTamil || !gridActive || tamilLoading || preparingTitle != null) return@LaunchedEffect
        snapshotFlow {
            state.layoutInfo.visibleItemsInfo.mapNotNull { latestCardsByKey[it.key as? String]?.id }.toSet()
        }.collectLatest { ids ->
            if (ids.isNotEmpty()) {
                delay(300)
                graph.tamilCatalog.enrichVisiblePosters(ids)
            }
        }
    }
    LaunchedEffect(isTamil, gridActive, tamilLoading, preparingTitle, focusedKey) {
        if (!isTamil || !gridActive || tamilLoading || preparingTitle != null || focusedKey == null) return@LaunchedEffect
        delay(1_500)
        val visibleIds = state.layoutInfo.visibleItemsInfo.mapNotNull { latestCardsByKey[it.key as? String]?.id }.toSet()
        graph.tamilCatalog.backfillPosters(visibleIds)
    }
    suspend fun focusFirst() {
        repeat(FOCUS_ATTEMPTS) {
            withFrameNanos { }
            if (runCatching { first.requestFocus() }.getOrDefault(false)) return
        }
    }
    val latestCards by rememberUpdatedState(visibleCards)
    fun restoreGridFocus() {
        val key = detailReturnKey ?: focusedKey
        if (key != null && latestCards.any { it.key == key }) returnFocusKey = key
        else scope.launch { focusFirst() }
    }
    DisposableEffect(gridActive) {
        if (!gridActive) return@DisposableEffect onDispose {}
        val restore = { restoreGridFocus() }
        shell.restoreContentFocus = restore
        onDispose { if (shell.restoreContentFocus === restore) shell.restoreContentFocus = null }
    }
    val canRestoreGrid = gridActive || (restoringPoster && openedRelease == null && adding == null && !filterOpen)
    LaunchedEffect(canRestoreGrid, visibleCards.isEmpty(), publicCards != null) {
        if (!canRestoreGrid) {
            returnFocusKey = null
            return@LaunchedEffect
        }
        if (titleSearchOpen) return@LaunchedEffect
        if (returningFromFilter) {
            returningFromFilter = false
            runCatching { filterFocus.requestFocus() }
            return@LaunchedEffect
        }
        // Player navigation starts before Play returns. Restore after this destination resumes,
        // when the saved poster can actually take focus.
        restoreGridFocus()
    }
    LaunchedEffect(focused?.id, services, shelf.generatedAtMillis, tamilLoading, gridActive, preparingTitle) {
        if (!isTamil || tamilLoading || !gridActive || preparingTitle != null ||
            focused == null || !focused.id.startsWith("saved:tamilmv:")) return@LaunchedEffect
        val debrid = services?.debrid ?: return@LaunchedEffect
        // A quick remote sweep only updates the title. Check TorBox after the viewer settles.
        val existing = availability?.takeIf { it.isFresh }
        if (existing == null) {
            delay(450)
            availability = try {
                val release = graph.resolveCatalogItem(focused.type, focused.id) ?: return@LaunchedEffect
                if (focused.isTamilMVMovieBundle() || focused.type == "series") {
                    val confirmed = if (focused.isTamilMVMovieBundle()) release.movieBundleFiles(debrid)
                        else release.episodeFiles(debrid, includeDeclared = true)
                    if (confirmed.any { it.cached }) TamilMVAvailability(TorboxReadyBadge, releaseFiles = confirmed) else null
                } else release.probeTamilMVMovie(debrid)?.let { row ->
                    val quality = if (TamilMVCatalogRepository.printTier(row.releaseName) == 0) "CAM"
                        else StreamFacts.normalizedQuality(null, row.releaseName)
                    val badge = if (quality == null) TorboxReadyBadge else
                        TorboxReadyBadge.copy(label = "${TorboxReadyBadge.label} · $quality")
                    TamilMVAvailability(badge, movie = row)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
        availability?.let { result ->
            delay((result.expiresAt - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0))
            availability = null
        }
    }
    val movieFailure = playFailure?.takeIf { it.episode == null && it.card.id == focused?.id }
    val fileFailure = playFailure?.takeIf { it.episode != null && it.card.id == openedRelease?.id }
    androidx.activity.compose.BackHandler(enabled = active && !filterOpen && adding == null) {
        when {
            playJob?.isActive == true -> playJob?.cancel()
            filePickerKey != null -> closeFiles()
            else -> onDismiss()
        }
    }
    val showingShelf = active || restoringPoster
    DisposableEffect(showingShelf) {
        if (!showingShelf) return@DisposableEffect onDispose {}
        shell.overlayVisible = true
        onDispose { shell.overlayVisible = false }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(TvColor.Background)
            .focusProperties {
                if (adding != null) canFocus = false
                exit = { direction ->
                if (direction == androidx.compose.ui.focus.FocusDirection.Enter ||
                    direction == androidx.compose.ui.focus.FocusDirection.Exit) FocusRequester.Default else FocusRequester.Cancel
            } }
            .focusGroup()
            .throttleDpadRepeats()
            .padding(start = TvGeom.ContentLeft, top = 54.dp, end = 96.dp),
    ) {
        Column {
            Row(Modifier.padding(end = CatalogRailGutter), verticalAlignment = Alignment.CenterVertically) {
              Text(
                shelf.title,
                modifier = Modifier.weight(1f, fill = false),
                style = TvType.ScreenTitle.copy(fontFamily = TvType.HeroTitle.fontFamily),
                color = TvColor.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              Spacer(Modifier.width(24.dp))
              TvButton(filters.label, onClick = { filterOpen = true }, modifier = Modifier.focusRequester(filterFocus)
                  .focusProperties { down = first })
              if (titleSearchOpen) TvSearchField("Search titles", titleQuery, { titleQuery = it },
                  Modifier.padding(start = 18.dp).width(520.dp).focusProperties { down = first },
                  openKeyboard = true, enabled = gridActive, onClose = {
                      titleSearchOpen = false
                      scope.launch { withFrameNanos { }; actions.restoreAction() }
                  })
            }
            Spacer(Modifier.height(8.dp))
            Text(
                (when {
                    titleQuery.isNotEmpty() -> "${visibleCards.size} matching titles"
                    filters.active -> browse.status()
                    offlineCount != null -> "$offlineCount ${plural(offlineCount, "title")} · Offline"
                    stamp != null -> "${stamp.count} ${plural(stamp.count, "title")}"
                    publicCards != null -> "${cards.size} ${plural(cards.size, "title")}"
                    isTamil -> "${SyncedLists.countLabel(shelf)} · Hold OK to save to Favorites"
                    else -> SyncedLists.countLabel(shelf)
                }) + if (visibleCards.isNotEmpty()) " · Right for catalog actions" else "",
                style = TvType.data(20),
                color = TvColor.TextMuted,
                maxLines = 1,
            )
            if (isTamil && visibleCards.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().height(52.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(TvSpace.M),
                ) {
                    val title = focused?.let { card -> listOfNotNull(card.title, card.year?.toString()).joinToString(" · ") }
                    Text(
                        title.orEmpty(),
                        modifier = Modifier.weight(1f, fill = false),
                        style = TvType.ScreenTitle.copy(fontSize = 32.sp, lineHeight = 40.sp),
                        color = TvColor.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    when {
                        preparingTitle != null && openedRelease == null ->
                            Text("Preparing playback…", style = TvType.Meta, color = TvColor.TextSecondary, maxLines = 1)
                        movieFailure != null -> {
                            Text(movieFailure.message, modifier = Modifier.weight(1f, fill = false),
                                style = TvType.Meta, color = TvColor.Warning, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            TvButton("Retry", onClick = {
                                returnFocusKey = movieFailure.card.key
                                play(movieFailure.card)
                            }, modifier = Modifier.focusRequester(retryFocus).focusProperties { down = first })
                        }
                        refreshMessage != null ->
                            Text(refreshMessage, modifier = Modifier.weight(1f, fill = false),
                                style = TvType.Meta, color = TvColor.Warning, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        else -> availability?.badge?.let { TvBadge(it) }
                    }
                }
            } else if (cards.isNotEmpty()) refreshMessage?.let {
                Text(it, style = TvType.Meta, color = if (list != null) TvColor.TextMuted else TvColor.Warning, maxLines = 2)
            }
        }
        if (visibleCards.isEmpty() && titleQuery.isNotEmpty()) {
            EmptyState("No matching titles", "Try a different title.", actionLabel = "Clear search",
                onAction = { titleQuery = ""; runCatching { filterFocus.requestFocus() } },
                actionModifier = Modifier.focusRequester(first), modifier = Modifier.align(Alignment.Center))
        } else if (visibleCards.isEmpty() && filters.active) {
            EmptyState(headline = if ((browse.pending > 0 || browse.updating)) "Checking title metadata…" else "No matching titles",
                line = browse.status(), actionLabel = "Reset filters", onAction = { filters = CatalogFilters(); runCatching { filterFocus.requestFocus() } },
                actionModifier = Modifier.focusRequester(first), modifier = Modifier.align(Alignment.Center))
        } else if (cards.isEmpty()) {
            EmptyState(
                headline = when {
                    isTamil -> "No TamilMV titles on this TV yet"
                    publicCards != null -> "This list is empty"
                    shelf.awaitingPhoneImport -> "This list hasn’t been imported yet"
                    else -> "No titles saved on this TV"
                },
                line = if (shelf.id == "tamilmv:collection")
                    tamilMessage ?: if (tamilLoading) "Loading current releases on this receiver…" else "Press Refresh to load current releases on this receiver."
                    else if (list != null) refreshMessage ?: "Press Refresh for this list, or Refresh all lists in Collections."
                    else refreshMessage ?: if (refreshing) "Loading the current catalog…"
                    else "This saved list has no titles yet. Press Refresh to load its current catalog.",
                actionLabel = if (refreshing) "Refreshing…" else "Refresh",
                onAction = ::refresh,
                actionModifier = Modifier.focusRequester(first),
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            PosterGrid(
                items = visibleCards,
                gridState = state,
                onFiltersFocus = {
                    if (movieFailure != null) retryFocus.requestFocus() else filterFocus.requestFocus()
                },
                actions = actions.takeIf { gridActive },
                smoothScroll = true,
                onFocusedIndex = {
                    focusedKey = visibleCards.getOrNull(it)?.key
                },
                onClick = { card ->
                    if (openedRelease == null && (!isTamil || playJob?.isActive != true)) {
                        focusedKey = card.key
                        when {
                            !isTamil -> details(card)
                            card.type == "series" || card.isTamilMVMovieBundle() -> filePickerKey = card.key
                            else -> play(card)
                        }
                    }
                },
                returnFocusKey = returnFocusKey.takeIf { canRestoreGrid },
                onReturnFinished = { restored ->
                    returnFocusKey = null
                    detailReturnKey = null
                    if (restored && restoringPoster) onPosterRestored()
                    if (!restored && canRestoreGrid) scope.launch { focusFirst() }
                },
                clickLabel = { card ->
                    when {
                        !isTamil -> "Open details"
                        card.isTamilMVMovieBundle() -> "Open movie files"
                        card.type == "series" -> "Open episodes"
                        else -> "Play movie"
                    }
                },
                longClickLabel = "Love; save to Favorites",
                posterOnly = isTamil,
                onLongClick = { card ->
                    if (openedRelease == null) {
                        withIdentity(card) { resolved ->
                            loveTitle(CollectionCandidate(resolved.id, resolved.type, resolved.title, resolved.posterUrl))
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = CatalogRailGutter)
                    .height(DiscoverPlan.gridHeightDp(collapsed = false).dp)
                    .offset(y = DiscoverPlan.gridTopDp(collapsed = false).dp)
                    .focusRequester(first),
            )
        }
        CatalogActionRail(
            actions,
            listOfNotNull(
                CatalogAction(R.drawable.ic_rail_search, if (titleQuery.isEmpty()) "Search titles" else "Search: $titleQuery") {
                    titleSearchOpen = true
                },
                CatalogAction(R.drawable.ic_catalog_filter, "Filters & sort") { filtersFromRail = true; filterOpen = true },
                if (shelf.researchKey() != null) CatalogAction(R.drawable.ic_detail_layers, "List information", run = onInformation) else null,
                CatalogAction(R.drawable.ic_catalog_pin, "Add to Den TV Home") {
                    LauncherRails.snapshot(context, shelf.id, nav::toast)
                },
                if (offlineCount == null) CatalogAction(R.drawable.ic_catalog_refresh, if (refreshing) "Refreshing…" else "Refresh list", !refreshing, ::refresh) else null,
            ),
            onLeave = { key ->
                if (key != null && visibleCards.any { it.key == key }) returnFocusKey = key
                else scope.launch { focusFirst() }
            },
            modifier = Modifier.align(Alignment.CenterEnd),
            enabled = gridActive && !titleSearchOpen,
        )
    }
    if (filterOpen) CatalogFilterPanel(filters, browse, { filters = it },
        onClose = {
            filterOpen = false
            if (filtersFromRail) {
                filtersFromRail = false
                scope.launch { withFrameNanos { }; actions.restoreAction() }
            } else returningFromFilter = true
        }, active = resumed)
    PanelTransition(openedRelease) { card ->
        val movieBundle = card.isTamilMVMovieBundle()
        val heading = if (movieBundle) "Movies" else "Episodes"
        SidePanel(header = "$heading · ${card.title}", onClose = ::closeFiles) {
            val status = fileStatus
            if (releaseFiles.isEmpty() || status != null) {
                Text(status ?: "Checking TorBox for cached files…", style = TvType.Body, color = TvColor.TextSecondary)
                if (status != null) {
                    Spacer(Modifier.height(TvSpace.M))
                    TvButton("Retry", { retryFiles++ },
                        modifier = if (releaseFiles.isEmpty()) Modifier.focusRequester(fileFocus) else Modifier)
                }
            }
            if (preparingTitle != null)
                Text("Preparing playback…", style = TvType.Meta, color = TvColor.TextSecondary, maxLines = 1)
            fileFailure?.let { failure ->
                Text(failure.message, style = TvType.Meta, color = TvColor.Warning, maxLines = 2)
                TvButton("Retry playback", { play(card, failure.episode) })
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 48.dp)) {
                itemsIndexed(releaseFiles, key = { _, episode ->
                    if (episode.season != null && episode.episode != null) "${episode.season}:${episode.episode}"
                    else "${episode.stream.id}:${episode.stream.torboxFileID}"
                }) { index, episode ->
                    val label = if (movieBundle) "Part ${index + 1} · ${episode.filename}" else episode.label
                    SidePanelRow(label, selected = false, onClick = { play(card, episode) },
                        modifier = if (index == 0) Modifier.focusRequester(fileFocus) else Modifier)
                }
            }
        }
    }
    PanelTransition(adding) { candidate ->
        AddToCollectionSheet(candidate = candidate, onDismiss = {
            adding = null
        })
    }
}

private data class TamilMVPlayFailure(val card: HomeCard, val episode: TamilMVReleaseFile?, val message: String)

private data class TamilMVAvailability(
    val badge: BadgeSpec,
    val movie: StreamRow? = null,
    val releaseFiles: List<TamilMVReleaseFile> = emptyList(),
    val expiresAt: Long = android.os.SystemClock.elapsedRealtime() + 60_000L,
) {
    val isFresh: Boolean get() = android.os.SystemClock.elapsedRealtime() < expiresAt
}

private fun HomeCard.isTamilMVMovieBundle(): Boolean =
    type == "movie" && id.startsWith("saved:tamilmv:") && TamilMVCatalogRepository.movieBundleTitle(title) != null

private val TorboxReadyBadge = BadgeSpec("✓ TorBox cached", TvColor.Canvas.copy(alpha = 0.92f), TvColor.Cached, TvColor.Cached)

private const val FOCUS_ATTEMPTS = 10

/**
 * How far the grid is pulled up from where [TopLevelScaffold] leaves it.
 *
 * The scaffold rests `TvSpace.L` (36) under its title; spec §5.2 rests 10.
 */
private val SCAFFOLD_GAP_TRIM = 26.dp

private val FolderNameStyle = TvType.HeroTitle.copy(
    fontSize = 38.sp, lineHeight = 43.sp, lineBreak = LineBreak.Heading,
)
private val FolderCountStyle = TvType.Meta.copy(fontSize = 23.sp, lineHeight = 28.sp)

/** What move mode is holding, and how far it has travelled so BACK can put it back. */
private class MoveState(val id: Long, val library: com.fourseveneightnine.tv.client.data.library.LibraryRepository,
    var applied: List<Int> = emptyList(), var cancelled: Boolean = false)

@Composable
private fun LoadedGrid(
    cards: List<FolderCardModel>,
    syncedLists: List<Shelf>,
    collectionsView: CollectionsView,
    onFilter: () -> Unit,
    onResetView: () -> Unit,
    onInformation: (Shelf) -> Unit,
    offlineCounts: Map<String, Int>,
    posterDownload: OfflineLetterboxd.PosterDownload,
    onPosterDownload: () -> Unit,
    query: String,
    onSearch: (String) -> Unit,
    listRefresh: TVLetterboxdListRefresh.State,
    cachedPortrait: (String) -> String?,
    canRefreshLists: Boolean,
    onRefreshLists: () -> Unit,
    moving: MoveState?,
    gridFocus: FocusRequester,
    active: Boolean,
    onOpen: (FolderCardModel) -> Unit,
    onOpenSynced: (Shelf) -> Unit,
    onEdit: (FolderCardModel) -> Unit,
    onNew: () -> Unit,
    onStartMove: (FolderCardModel) -> Unit,
    onMove: (MoveDirection) -> Unit,
    onCommit: () -> Unit,
    onCancelMove: () -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    val state = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val animateScroll = !LocalReduceMotion.current
    val rows = remember(state, scope, animateScroll) {
        GridRowFocus(state, scope, FolderGrid.COLUMNS, smoothScroll = animateScroll)
    }
    val focusManager = LocalFocusManager.current
    var focusedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var restoreKey by remember { mutableStateOf<String?>(null) }
    val returnFocus = remember { FocusRequester() }
    val actions = remember { CatalogRailFocus() }
    var searchOpen by remember { mutableStateOf(false) }
    val emptyFocus = remember { FocusRequester() }
    val showNew = query.isEmpty() && collectionsView == CollectionsView.All
    val syncedStart = cards.size + if (showNew) 1 else 0
    val resultCount = syncedStart + syncedLists.size
    fun keyAt(index: Int): String = when {
        index < cards.size -> "folder:${cards[index].id}"
        showNew && index == cards.size -> NEW_CARD_KEY
        else -> "snapshot:${syncedLists[index - syncedStart].id}"
    }
    var previousView by remember { mutableStateOf(collectionsView) }
    LaunchedEffect(active, collectionsView) {
        if (!active) return@LaunchedEffect
        if (previousView != collectionsView) {
            previousView = collectionsView
            focusedKey = null
        }
        val key = focusedKey
        val folderIndex = cards.indexOfFirst { "folder:${it.id}" == key }
        val syncedIndex = syncedLists.indexOfFirst { "snapshot:${it.id}" == key }
        val index = when {
            folderIndex >= 0 -> folderIndex
            key == NEW_CARD_KEY && showNew -> cards.size
            syncedIndex >= 0 -> syncedStart + syncedIndex
            else -> -1
        }
        restoreKey = key.takeIf { index >= 0 }
        if (index >= 0) {
            if (state.layoutInfo.visibleItemsInfo.none { it.key == key }) state.scrollToItem(index)
        } else if (resultCount > 0) {
            state.scrollToItem(0)
        }
        repeat(FOCUS_ATTEMPTS) {
            withFrameNanos { }
            val target = when {
                resultCount == 0 -> emptyFocus
                restoreKey != null && it < FOCUS_ATTEMPTS - 1 -> returnFocus
                else -> gridFocus
            }
            if (runCatching { target.requestFocus() }.getOrDefault(false)) {
                restoreKey = null
                return@LaunchedEffect
            }
        }
        restoreKey = null
    }
    Box(modifier) {
      Column(Modifier.fillMaxSize().padding(end = CatalogRailGutter)) {
        val posterMessage = if (posterDownload.running && posterDownload.total > 0)
            "Offline posters: ${posterDownload.done} of ${posterDownload.total}" else posterDownload.message
        Row(Modifier.fillMaxWidth().height(TvGeom.ButtonHeight), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TvSpace.M)) {
            Text(
                SyncedLists.directoryLabel(cards.size, syncedLists.size) +
                    if (collectionsView == CollectionsView.All) "" else " · ${collectionsView.label}",
                style = TvType.ControlLabel,
                color = TvColor.TextSecondary,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            if (searchOpen) TvSearchField("Search collections", query, onSearch,
                Modifier.width(560.dp).focusProperties { down = if (resultCount == 0) emptyFocus else gridFocus },
                openKeyboard = true, enabled = active, onClose = {
                    searchOpen = false
                    scope.launch { withFrameNanos { }; actions.restoreAction() }
                })
            if (!searchOpen) (posterMessage ?: listRefresh.message)?.let {
                Text(it, style = TvType.Meta, color = TvColor.TextSecondary, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(14.dp))
        if (moving != null) {
            MoveBanner("Move mode. Arrows move the folder. OK drops it. BACK cancels.")
            Spacer(Modifier.height(TvSpace.S))
        }
        if (resultCount == 0) {
            EmptyState(
                headline = "No matching collections",
                line = if (query.isNotEmpty()) "Try a different search." else "Choose another collections filter.",
                actionLabel = if (query.isNotEmpty()) "Clear search" else "Show all collections",
                onAction = {
                    if (query.isNotEmpty()) onSearch("") else onResetView()
                    scope.launch { withFrameNanos { }; gridFocus.requestFocus() }
                },
                actionModifier = Modifier.focusRequester(emptyFocus),
            )
        } else LazyVerticalGrid(
            columns = GridCells.Fixed(FolderGrid.COLUMNS),
            state = state,
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp),
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(gridFocus)
                .focusRestorer()
                .throttleDpadRepeats()
                // Move mode owns the arrows. It must consume them on the DOWN event, before the
                // focus search runs, or the ring leaves the card the viewer is carrying.
                .onPreviewKeyEvent { event ->
                    if (moving == null) {
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        if (event.key != Key.DirectionUp && event.key != Key.DirectionDown) rows.cancel()
                        val index = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == focusedKey }?.index
                            ?: return@onPreviewKeyEvent false
                        if (event.key == Key.DirectionUp || event.key == Key.DirectionDown) {
                            return@onPreviewKeyEvent rows.move(index, event.key == Key.DirectionDown,
                                resultCount, repeating = event.nativeKeyEvent.repeatCount > 0, keyAt = ::keyAt)
                        }
                        if (event.key == Key.DirectionRight && (index == resultCount - 1 || (index + 1) % FolderGrid.COLUMNS == 0)) {
                            val key = keyAt(index)
                            return@onPreviewKeyEvent actions.enter(key) { rows.request(key) }
                        }
                        // LEFT means "previous card" everywhere but column 1, where it means the
                        // rail. The screen frame claims LEFT from an ancestor and Compose moves
                        // focus last of all, so the move has to happen here or the rail always
                        // wins and the grid can only be walked rightwards.
                        if (event.key == Key.DirectionLeft && index % FolderGrid.COLUMNS != 0) {
                            focusManager.moveFocus(FocusDirection.Left)
                            return@onPreviewKeyEvent true
                        }
                        return@onPreviewKeyEvent false
                    }
                    if (event.type != KeyEventType.KeyDown) {
                        return@onPreviewKeyEvent event.key in MOVE_KEYS
                    }
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
            itemsIndexed(cards, key = { _, card -> "folder:${card.id}" }, contentType = { _, _ -> "folder" }) { _, card ->
                val key = "folder:${card.id}"
                FolderTile(
                    card = card,
                    modifier = rows.card(key).then(if (restoreKey == key) Modifier.focusRequester(returnFocus) else Modifier),
                    dimmed = moving != null && moving.id != card.id,
                    picked = moving?.id == card.id,
                    onFocused = { focusedKey = key },
                    onClick = { focusedKey = key; onOpen(card) },
                    onEdit = { focusedKey = key; onEdit(card) },
                    onStartMove = { onStartMove(card) },
                )
            }
            if (showNew) item(key = NEW_CARD_KEY, contentType = "create") {
                NewCollectionTile(
                    modifier = rows.card(NEW_CARD_KEY)
                        .then(if (restoreKey == NEW_CARD_KEY) Modifier.focusRequester(returnFocus) else Modifier),
                    onFocused = { focusedKey = NEW_CARD_KEY },
                    onClick = { focusedKey = NEW_CARD_KEY; onNew() },
                )
            }
            items(
                count = syncedLists.size,
                key = { index -> "snapshot:${syncedLists[index].id}" },
                contentType = { "synced" },
            ) { listIndex ->
                val shelf = syncedLists[listIndex]
                val key = "snapshot:${shelf.id}"
                SyncedListTile(
                    shelf = shelf,
                    offlineCount = offlineCounts[shelf.id],
                    cachedPortrait = cachedPortrait,
                    publicStamp = listRefresh.stamps[TVLetterboxdClient.savedList(shelf)?.key],
                    refreshFailed = TVLetterboxdClient.savedList(shelf)?.key in listRefresh.failedKeys,
                    modifier = rows.card(key).then(if (restoreKey == key) Modifier.focusRequester(returnFocus) else Modifier),
                    dimmed = moving != null,
                    onFocused = { focusedKey = key },
                    onClick = { focusedKey = key; onOpenSynced(shelf) },
                    onInformation = if (shelf.researchKey() != null) ({ focusedKey = key; onInformation(shelf) }) else null,
                )
            }
        }
      }
      CatalogActionRail(
          actions,
          buildList {
              add(CatalogAction(R.drawable.ic_rail_search, if (query.isEmpty()) "Search collections" else "Search: $query") {
                  searchOpen = true
              })
              add(CatalogAction(R.drawable.ic_catalog_filter, "Collections filters", run = onFilter))
              if (offlineCounts.isNotEmpty()) {
                  val complete = posterDownload.total > 0 && posterDownload.done == posterDownload.total
                  val label = when {
                      posterDownload.running -> "Pause poster download"
                      complete -> "Offline posters downloaded"
                      posterDownload.message != null -> "Retry offline posters"
                      else -> "Download offline posters"
                  }
                  add(CatalogAction(R.drawable.ic_catalog_refresh, label, !complete, onPosterDownload))
              }
              if (canRefreshLists) add(CatalogAction(R.drawable.ic_catalog_refresh, when {
                  listRefresh.running -> "Refreshing…"
                  listRefresh.pending -> "Retry unfinished"
                  else -> "Refresh all lists"
              }, !listRefresh.running, onRefreshLists))
          },
          onLeave = { key ->
              if (key == null || !rows.request(key)) runCatching {
                  (if (resultCount == 0) emptyFocus else gridFocus).requestFocus()
              }
          },
          modifier = Modifier.align(Alignment.CenterEnd),
          enabled = active && moving == null && !searchOpen,
      )
    }
}

private val MOVE_KEYS = setOf(
    Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown,
    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Back,
)

private const val NEW_CARD_KEY = "new-collection"

/** A local folder uses the same cover and caption as a synced list. */
@Composable
private fun FolderTile(
    card: FolderCardModel,
    modifier: Modifier = Modifier,
    dimmed: Boolean,
    picked: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onStartMove: () -> Unit,
) {
    val posters = remember(card.posters) {
        card.posters.take(FolderGrid.POSTER_COUNT).mapIndexed { index, url ->
            HomeCard("collection-preview-$index", "movie", "", posterUrl = url)
        }
    }
    LongPressFocusable(
        onClick = onClick,
        modifier = modifier.moveModeDim(dimmed),
        // Long OK exposes Edit and Move through ordinary remote controls. MENU remains a
        // shell shortcut; system folders cannot be edited or moved.
        onLongClick = if (card.isSystem) null else onEdit,
        onMenu = if (card.isSystem) null else onStartMove,
        accessibleLabel = accessibilityLabel(
            card.name,
            card.countLabel,
            if (card.pinned) "Pinned to Home" else null,
        ),
        clickLabel = "Open collection",
        longClickLabel = "Collection options",
        customActions = if (card.isSystem) emptyList() else listOf(
            CustomAccessibilityAction("Move collection") { onStartMove(); true },
        ),
        selected = picked,
        focusScale = 1f,
        focusRing = false,
        onFocusedChange = { if (it) onFocused() },
    ) { focused ->
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box {
                CollectionCover(if (picked) TvColor.Selection else card.accent, posters, focused = focused) {
                    SystemGlyph(card, Modifier.align(Alignment.Center))
                }
                if (card.pinned) {
                    Image(
                        imageVector = PinGlyph,
                        contentDescription = null,
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(TvColor.TextPrimary),
                        modifier = Modifier.align(Alignment.TopCenter).offset(x = 51.dp, y = 20.dp)
                            .clip(TvShape.Tile).background(TvColor.Canvas.copy(alpha = 0.84f))
                            .padding(8.dp).size(24.dp),
                    )
                }
            }
            CollectionCaption(
                title = card.name,
                count = card.countLabel,
                source = null,
            )
        }
    }
}

/** A virtual synced shelf: light directory card here, full-resolution lazy grid after opening. */
@Composable
private fun SyncedListTile(
    shelf: Shelf,
    offlineCount: Int? = null,
    publicStamp: TVLetterboxdListRefresh.Stamp?,
    cachedPortrait: (String) -> String?,
    refreshFailed: Boolean,
    modifier: Modifier = Modifier,
    dimmed: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onInformation: (() -> Unit)?,
) {
    val isLetterboxd = shelf.kind == ShelfKind.LETTERBOXD
    val posters = remember(shelf, publicStamp, cachedPortrait) {
        SyncedLists.directoryPosters(shelf, publicStamp, cachedPortrait)
    }
    val count = remember(shelf, publicStamp, refreshFailed, offlineCount) {
        val label = offlineCount?.let { "$it ${plural(it, "title")}" } ?: SyncedLists.directoryCountLabel(shelf, publicStamp)
        if (refreshFailed) "$label · Retry" else label
    }
    val source = SyncedLists.sourceLabel(shelf)
    LongPressFocusable(
        onClick = onClick,
        onLongClick = onInformation,
        longClickLabel = "List information",
        modifier = modifier.moveModeDim(dimmed),
        enabled = !dimmed,
        accessibleLabel = accessibilityLabel(shelf.title, count, source),
        clickLabel = "Open synced list",
        focusScale = 1f,
        focusRing = false,
        onFocusedChange = { if (it) onFocused() },
    ) { focused ->
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            CollectionCover(TvColor.TextSecondary, posters, focused = focused) {
                Image(
                    painter = painterResource(if (isLetterboxd) R.drawable.ic_rating_letterboxd else R.drawable.ic_rail_collections),
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.Center).width(62.dp).height(52.dp),
                    colorFilter = if (isLetterboxd) null
                        else androidx.compose.ui.graphics.ColorFilter.tint(TvColor.TextSecondary),
                )
            }
            // An artless Letterboxd list already has the source mark in the cover.
            val showCaptionMark = isLetterboxd && posters.isNotEmpty()
            CollectionCaption(shelf.title, count, source.takeUnless { isLetterboxd && !showCaptionMark },
                letterboxd = showCaptionMark)
        }
    }
}

@Composable
private fun CollectionCaption(title: String, count: String, source: String?, letterboxd: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 7.dp)
        .clearAndSetSemantics { }, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = FolderNameStyle, color = TvColor.TextPrimary, maxLines = 2,
            textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Text(count, style = FolderCountStyle, color = TvColor.TextSecondary, maxLines = 2,
                modifier = Modifier.weight(1f, fill = false),
                textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
            if (source != null) Spacer(Modifier.width(12.dp))
            if (letterboxd) {
                Image(painterResource(R.drawable.ic_rating_letterboxd), contentDescription = null,
                    modifier = Modifier.width(36.dp).height(14.dp))
            } else if (source == "TMDB") {
                Image(painter = painterResource(R.drawable.ic_rating_tmdb), contentDescription = null,
                    modifier = Modifier.width(86.dp).height(18.dp))
            } else if (source != null) {
                Text(source, style = TvType.Badge.copy(letterSpacing = 0.sp), color = TvColor.TextSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A role glyph keeps folders recognizable before their first saved title. */
@Composable
private fun SystemGlyph(card: FolderCardModel, modifier: Modifier = Modifier) {
    // Spec §5.3: a system folder's mark is `textMuted`. My Cloud used the green `cached` token,
    // which spec §0.5 reserves for "this stream is already on the debrid box".
    val tint: Color = when (card.role) {
        FolderRole.ContinueWatching -> TvColor.TextSecondary
        FolderRole.Watchlist, FolderRole.Favorites, FolderRole.MyCloud, FolderRole.User -> TvColor.TextMuted
    }
    // A real glyph per folder. The old mark was an empty rounded square, which read as a
    // poster that failed to load.
    if (card.role == FolderRole.User) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(com.fourseveneightnine.tv.R.drawable.ic_rail_collections),
            contentDescription = null,
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint),
            modifier = modifier.size(56.dp),
        )
    } else {
        androidx.compose.foundation.Image(
            imageVector = folderGlyph(card.role),
            contentDescription = null,
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint),
            modifier = modifier.size(56.dp),
        )
    }
}

private val glyphCache = HashMap<String, androidx.compose.ui.graphics.vector.ImageVector>()

/** Material filled paths, 24 x 24. Built once and kept. */
private fun glyph(name: String, path: String): androidx.compose.ui.graphics.vector.ImageVector =
    glyphCache.getOrPut(name) {
        androidx.compose.ui.graphics.vector.ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = androidx.compose.ui.graphics.vector.PathParser().parsePathString(path).toNodes(),
            fill = androidx.compose.ui.graphics.SolidColor(Color.White),
        ).build()
    }

private fun folderGlyph(role: FolderRole): androidx.compose.ui.graphics.vector.ImageVector = when (role) {
    FolderRole.ContinueWatching -> glyph(
        "history",
        "M13,3c-4.97,0 -9,4.03 -9,9L1,12l3.89,3.89 0.07,0.14L9,12L6,12c0,-3.87 3.13,-7 7,-7s7,3.13 7,7 -3.13,7 -7,7c-1.93,0 -3.68,-0.79 -4.94,-2.06l-1.42,1.42C8.27,19.99 10.51,21 13,21c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9zM12,8v5l4.28,2.54 0.72,-1.21 -3.5,-2.08L13.5,8L12,8z",
    )
    FolderRole.MyCloud -> glyph(
        "cloud",
        "M19.35,10.04C18.67,6.59 15.64,4 12,4 9.11,4 6.6,5.64 5.35,8.04 2.34,8.36 0,10.91 0,14c0,3.31 2.69,6 6,6h13c2.76,0 5,-2.24 5,-5 0,-2.64 -2.05,-4.78 -4.65,-4.96z",
    )
    FolderRole.Favorites -> glyph(
        "favorite",
        "M12,21.35l-1.45,-1.32C5.4,15.36 2,12.28 2,8.5 2,5.42 4.42,3 7.5,3c1.74,0 3.41,0.81 4.5,2.09C13.09,3.81 14.76,3 16.5,3 19.58,3 22,5.42 22,8.5c0,3.78 -3.4,6.86 -8.55,11.54z",
    )
    FolderRole.Watchlist, FolderRole.User -> glyph(
        "bookmark",
        "M17,3H7c-1.1,0 -1.99,0.9 -1.99,2L5,21l7,-3 7,3V5c0,-1.1 -0.9,-2 -2,-2z",
    )
}

private val PinGlyph: androidx.compose.ui.graphics.vector.ImageVector
    get() = glyph(
        "pin",
        "M16,9V4l1,0c0.55,0 1,-0.45 1,-1v0c0,-0.55 -0.45,-1 -1,-1H7C6.45,2 6,2.45 6,3v0c0,0.55 0.45,1 1,1l1,0v5c0,1.66 -1.34,3 -3,3h0v2h5.97v7l1,1l1,-1v-7H19v-2h0C17.34,12 16,10.66 16,9z",
    )

/** The create action stays after the local folders. */
@Composable
private fun NewCollectionTile(modifier: Modifier = Modifier, onFocused: () -> Unit, onClick: () -> Unit) {
    LongPressFocusable(
        onClick = onClick,
        modifier = modifier,
        accessibleLabel = "New collection",
        focusScale = 1f,
        focusRing = false,
        onFocusedChange = { if (it) onFocused() },
    ) { focused ->
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            CollectionCover(TvColor.TextSecondary, emptyList(), focused = focused) {
                Text("+", style = TvType.HeroTitle, color = TvColor.TextPrimary,
                    modifier = Modifier.align(Alignment.Center), maxLines = 1)
            }
            CollectionCaption("New collection", "Create a list", source = null)
        }
    }
}

/** The sweep runs on the first row only (spec §15.4). */
@Composable
private fun LoadingGrid() {
    Column(Modifier.fillMaxSize()) {
        Skeleton(Modifier.size(300.dp, 22.dp), shape = TvShape.Badge)
        Spacer(Modifier.height(26.dp))
        FolderSkeletonRow(sweep = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(32.dp))
        FolderSkeletonRow(sweep = false, modifier = Modifier.fillMaxWidth())
    }
}
