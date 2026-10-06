@file:Suppress("OPT_IN_USAGE")
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.fourseveneightnine.tv.client.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
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
import androidx.compose.ui.unit.dp
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.data.images.PosterRequest
import com.fourseveneightnine.tv.client.data.meta.Ratings
import com.fourseveneightnine.tv.client.playback.PlayRequest
import com.fourseveneightnine.tv.client.playback.PlayResult
import com.fourseveneightnine.tv.client.ui.LocalShellState
import com.fourseveneightnine.tv.client.ui.components.EmptyState
import com.fourseveneightnine.tv.client.ui.components.Skeleton
import com.fourseveneightnine.tv.client.ui.components.ShelfHeader
import com.fourseveneightnine.tv.client.ui.components.TvButton
import com.fourseveneightnine.tv.client.ui.components.StateBlock
import com.fourseveneightnine.tv.client.ui.nav.ClientNav
import com.fourseveneightnine.tv.client.ui.screens.detail.DetailPreview
import com.fourseveneightnine.tv.client.ui.screens.collections.AddToCollectionSheet
import com.fourseveneightnine.tv.client.ui.screens.collections.CollectionCandidate
import com.fourseveneightnine.tv.client.ui.theme.TvGeom
import com.fourseveneightnine.tv.ui.throttleDpadRepeats
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Home, spec §3. A hero that follows the ring, and every shelf below it.
 *
 * Three rules shape the whole file, and each one was a bug on this fleet before it was a rule.
 *
 *  - The ring and title answer the key press. The backdrop waits 140 ms of rest, so holding RIGHT
 *    moves focus without decoding every image the ring passes.
 *  - No focus requester points into a lazy row. The two that exist point at the hero button and at
 *    the shelf column itself, both of which are always composed.
 *  - Focus moves because a key was pressed. The one exception is the first placement on entry, and
 *    it is cancelled the moment the viewer touches the remote.
 */
@Composable
internal fun HomeScreen(nav: ClientNav) {
    val context = LocalContext.current
    val client = remember(context) { context.clientGraph }
    val scope = rememberCoroutineScope()
    val viewModel = remember(client) { HomeViewModel(scope, client).also { it.start() } }
    val state by viewModel.state.collectAsState()
    val shell = LocalShellState.current

    val heroFocus = remember { FocusRequester() }
    val rowsFocus = remember { FocusRequester() }
    val emptyFocus = remember { FocusRequester() }
    // These four are held as State OBJECTS, never as `by` delegates read in this scope.
    //
    // A focus move writes the focused card, and a `by` read here would recompose the whole screen —
    // the hero, the backdrop and every shelf — once per key press. Measured on the onn 4K Pro that
    // was 57% janky frames at a 40 ms median across a 30-press sweep. Reading them only inside the
    // hero, through the lambdas below, leaves a move costing the two cards whose ring changed.
    val focusedCard = remember { mutableStateOf<HomeCard?>(null) }
    val focusedContinue = remember { mutableStateOf<HomeContinueCard?>(null) }
    val settledCard = remember { mutableStateOf<HomeCard?>(null) }
    val settledContinue = remember { mutableStateOf<HomeContinueCard?>(null) }
    val warmedTitles = remember { mutableSetOf<String>() }
    val titleRatings = remember { mutableStateMapOf<String, Ratings>() }
    val prefetchedPosters = remember { LinkedHashSet<String>() }
    // The row the ring last sat on, as a plain holder for the same reason: it is read only by the
    // rescue net below, and a State here would recompose the screen on every vertical move.
    val focusedRow = remember { intArrayOf(-1) }
    val listState = rememberLazyListState()
    var seeAll by remember { mutableStateOf<HomeCatalogRef?>(null) }
    var addTo by remember { mutableStateOf<CollectionCandidate?>(null) }
    var interacted by remember { mutableStateOf(false) }
    var placed by remember { mutableStateOf(false) }

    /**
     * The backdrop rests 140 ms before it swaps, and a move inside that window cancels the
     * pending swap rather than queueing another one. The title follows focus immediately.
     *
     * Keyed on Unit and reading the focused card through `snapshotFlow`, so the read happens in the
     * coroutine instead of in this composable's scope. `collectLatest` is what cancels a pending
     * swap: nothing queues up.
     */
    LaunchedEffect(Unit) {
        snapshotFlow { focusedCard.value }
            .collectLatest { card ->
                if (card == null) return@collectLatest
                delay(com.fourseveneightnine.tv.client.ui.theme.TvMotion.HeroSettleMillis.toLong())
                settledCard.value = card
                settledContinue.value = focusedContinue.value
                // A second pause keeps a rapid D-pad sweep from launching one metadata request
                // per poster. The client scope lets Detail share work after Home disappears.
                delay(260)
                if (warmedTitles.add(card.key)) {
                    client.scope.launch {
                        val services = client.services.filterNotNull().first()
                        val meta = services.meta.meta(card.type, card.id) ?: return@launch
                        val imdb = meta.imdbID ?: return@launch
                        val ratings = runCatching { services.ratings.ratings(imdb, meta.type == "series") }.getOrNull()
                            ?: return@launch
                        withContext(Dispatchers.Main) { titleRatings[card.key] = ratings }
                    }
                }
            }
    }

    // Seed the fixed-height hero before the initial focus request. Without this, a Home whose
    // first visible row was Collections painted a blank billboard for the first 140 ms.
    LaunchedEffect(state.rows, interacted) {
        if (interacted || settledCard.value != null) return@LaunchedEffect
        settledCard.value = HomePlan.featuredCard(state.rows)
    }

    /**
     * What the rail's RIGHT and the shell's rescue net call when they hand the remote back.
     *
     * It must not scroll the band. The column's `focusRestorer()` puts the ring back on the card
     * it saved, and that leaves the band exactly where it was — but only while that card is still
     * composed. A plain `rowsFocus.requestFocus()` on a band scrolled ten rows down landed on item
     * 0 and snapped the whole screen to row 1 (F03). Scrolling the remembered row back under the
     * band top first means the restored card is composed, and the band ends where the pivot would
     * have put it anyway.
     */
    DisposableEffect(state.hasContent) {
        val restore = {
            if (!state.hasContent) {
                runCatching { heroFocus.requestFocus() }
            } else {
                scope.launch {
                    val row = HomePlan.restoreRow(focusedRow[0], state.rows.size)
                    if (row > 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == row }) {
                        runCatching { listState.scrollToItem(row) }
                    }
                    runCatching { rowsFocus.requestFocus() }
                }
            }
            Unit
        }
        shell.restoreContentFocus = restore
        onDispose { if (shell.restoreContentFocus === restore) shell.restoreContentFocus = null }
    }

    // Initial focus, placed once. The hero button holds the remote alive until the first row has
    // items; after that the viewer's own first key press wins and nothing moves focus again.
    LaunchedEffect(state.hasContent, interacted) {
        if (placed) return@LaunchedEffect
        if (!state.hasContent) {
            // Whatever is drawn instead of a shelf still has to hold the remote: an empty screen
            // with nothing focused trips the shell's rescue net and opens the rail on its own.
            if (!runCatching { emptyFocus.requestFocus() }.isSuccess) {
                runCatching { heroFocus.requestFocus() }
            }
            return@LaunchedEffect
        }
        if (interacted) { placed = true; return@LaunchedEffect }
        // The first row's requester attaches a frame or two after `hasContent` flips. A single
        // swallowed failure here left the screen with no focus at all, so the shell's rescue net
        // opened the rail on every cold start. Retry briefly; mark placed only on success.
        withFrameNanos { }
        for (attempt in 0 until 12) {
            if (runCatching { rowsFocus.requestFocus() }.isSuccess) { placed = true; break }
            delay(100)
        }
    }

    fun play(card: HomeCard, continueCard: HomeContinueCard?) {
        scope.launch {
            val request = PlayRequest(
                type = card.type,
                id = card.id,
                title = card.title,
                season = continueCard?.season,
                episode = continueCard?.episode,
                posterUrl = card.posterUrl,
                backdropUrl = card.backdropUrl,
                resumeFromMs = continueCard?.resumeFromMs,
            )
            when (val result = client.playFlow.play(request)) {
                PlayResult.Opened -> Unit
                PlayResult.ShowList -> nav.openStreams(card.type, card.id, continueCard?.season, continueCard?.episode)
                is PlayResult.Failed -> nav.toast(result.message)
            }
        }
    }

    fun prefetchAhead(cards: List<HomeCard>, focusedIndex: Int) {
        // LazyRow loads the visible cards. Warm only the next few beyond that window, so a fast
        // RIGHT sweep lands on cached pixels instead of asking the network after focus arrives.
        val ahead = cards.drop(focusedIndex + 3).take(4)
            .mapNotNull(HomeCard::posterUrl).filter(String::isNotBlank)
            .filter { prefetchedPosters.add(it) }
        while (prefetchedPosters.size > 512) prefetchedPosters.remove(prefetchedPosters.first())
        if (ahead.isEmpty()) return
        val appContext = context.applicationContext
        client.scope.launch {
            ahead.forEach { url ->
                client.imageLoader.enqueue(PosterRequest.poster(url).toImageRequest(appContext))
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .throttleDpadRepeats()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (event.key == Key.Back) {
                    return@onPreviewKeyEvent when {
                        addTo != null -> { addTo = null; true }
                        seeAll != null -> { seeAll = null; true }
                        else -> false
                    }
                }
                interacted = true
                false
            }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                // The grid overlay owns the remote while it is up: LEFT there is its own, not the
                // rail's, and PLAY has no focused card behind it (F01).
                if (seeAll != null) return@onKeyEvent false
                when (event.key) {
                    // LEFT reaches this frame only when the row did not answer it, which is card 1.
                    Key.DirectionLeft, Key.Menu -> { nav.openRail(); true }
                    // Spec §3.5: PLAY or PAUSE starts the picking flow wherever focus sits.
                    Key.MediaPlay, Key.MediaPlayPause, Key.MediaPause -> {
                        focusedCard.value?.let { play(it, focusedContinue.value) }
                        true
                    }
                    else -> false
                }
            },
    ) {
        HomeBackdrop(card = { settledCard.value })

        // F01: while the grid overlay is up, everything under it stops being a focus target. The
        // rows stayed focusable behind an opaque box, so the ring sat on a card nobody could see
        // and the D-pad scrolled a hidden row.
        val overlayUp = seeAll != null
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = TvGeom.ContentLeft, top = 54.dp)
                // The documented way to switch a whole subtree off: a deactivated focus group.
                // Only ever applied to turn it OFF — a group told it CAN focus becomes a focus
                // target of its own and the ring lands on the column instead of a card.
                .then(if (overlayUp) Modifier.focusProperties { canFocus = false } else Modifier)
                .focusGroup(),
        ) {
            when {
                // Loaded, spec §3.2. The hero follows the ring; the shelves scroll under it.
                state.hasContent -> {
                    // Text follows focus immediately; only the expensive backdrop waits for rest.
                    HomeHero(
                        card = { focusedCard.value ?: settledCard.value },
                        ratings = { card -> titleRatings[card.key] ?: Ratings.NONE },
                    )
                    ShelfBand(
                        state = state,
                        rowsFocus = rowsFocus,
                        listState = listState,
                        onCardFocused = { card, continueCard ->
                            focusedCard.value = card
                            focusedContinue.value = continueCard
                        },
                        onPosterFocused = ::prefetchAhead,
                        onRowFocused = { index, key ->
                            focusedRow[0] = index
                            viewModel.onRowFocused(key)
                        },
                        onOpenDetail = {
                            DetailPreview.stage(it)
                            nav.openDetail(it.type, it.id)
                        },
                        onResume = { item -> play(item.card, item) },
                        onOpenCollection = { nav.openCollection(it.toString()) },
                        onSeeAll = { seeAll = it },
                        onAllCatalogs = nav::openAllCatalogs,
                        onAddToCollection = { addTo = it.toCollectionCandidate() },
                    )
                }

                // Loading, spec §3.7. The skeleton mirrors the loaded layout, so nothing jumps.
                state.loading -> {
                    HomeHeroSkeleton(
                        actionModifier = Modifier.focusRequester(heroFocus),
                        onOpenMenu = nav::openRail,
                    )
                    HomeRowsSkeleton(Modifier.fillMaxWidth())
                }

                // Error, spec §3.7. Every catalog answered and none of them had a row.
                state.failed -> StatePlate {
                    StateBlock(
                        headline = "Couldn't load your shelves",
                        line = "Check the TV's network, then try again.",
                        actionLabel = "Retry",
                        onAction = viewModel::retry,
                        secondaryLabel = "Open Jobs",
                        onSecondary = { nav.openSettings("jobs") },
                        actionModifier = Modifier.focusRequester(emptyFocus),
                        announceAsError = true,
                    )
                }

                // Empty, spec §3.7. The copy names what would fill the screen and gives one way.
                !state.settingsLoaded -> StatePlate {
                    EmptyState(
                        headline = "Nothing to show yet",
                        line = "Pair your iPhone to fill this screen with your add-ons, collections and Continue Watching.",
                        actionLabel = "Open Pair and Sync",
                        onAction = { nav.openSettings("pair") },
                        actionModifier = Modifier.focusRequester(emptyFocus),
                    )
                }

                else -> StatePlate {
                    EmptyState(
                        headline = "Nothing to show yet",
                        line = "Add a catalog add-on to fill your home screen.",
                        actionLabel = "Open Add-ons",
                        onAction = { nav.openSettings("addons") },
                        actionModifier = Modifier.focusRequester(emptyFocus),
                    )
                }
            }
        }

        // The overlay owns the remote, so the shell stops treating this as a plain browse screen.
        DisposableEffect(overlayUp) {
            shell.overlayVisible = overlayUp
            onDispose { shell.overlayVisible = false }
        }

        seeAll?.let { ref ->
            CatalogGridOverlay(
                catalog = ref,
                onOpen = {
                    DetailPreview.stage(it)
                    nav.openDetail(it.type, it.id)
                },
                onLongOpen = { addTo = it.toCollectionCandidate() },
            )
        }
    }

    addTo?.let { candidate ->
        AddToCollectionSheet(candidate = candidate, onDismiss = { addTo = null })
    }
}

/**
 * Where a state block sits, spec §3.7: x 660..1260 on the screen.
 *
 * It was centred inside the column, which already starts at x 220, so it landed at x 770 and read
 * as "pushed right" against everything else on the screen (F33).
 */
@Composable
private fun StatePlate(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.align(Alignment.CenterStart).offset(x = StatePlateLeft)) { content() }
    }
}

/** x 660 on the screen, less the 220 px this column is already inset by. */
private val StatePlateLeft = 440.dp

/**
 * The scrolling shelf band, spec §3.2. The focused row's top pins at y 578, which is the top of
 * this band, so the column pivot is simply "offset zero".
 *
 * The 36 px rest zone between rows is each row's own bottom padding, not the column's
 * `spacedBy`. A reserved row with nothing in it has to take no height AT ALL — `spacedBy` would
 * still give it a gap, and two reserved rows would hold 72 px of nothing above row 1 (F04).
 */
@Composable
private fun ShelfBand(
    state: HomeState,
    rowsFocus: FocusRequester,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onCardFocused: (HomeCard?, HomeContinueCard?) -> Unit,
    onPosterFocused: (List<HomeCard>, Int) -> Unit,
    onRowFocused: (Int, String) -> Unit,
    onOpenDetail: (HomeCard) -> Unit,
    onResume: (HomeContinueCard) -> Unit,
    onOpenCollection: (Long) -> Unit,
    onSeeAll: (HomeCatalogRef) -> Unit,
    onAllCatalogs: () -> Unit,
    onAddToCollection: (HomeCard) -> Unit,
) {
    val spec = rememberShelfColumnPivot()
    val scope = rememberCoroutineScope()
    val rowStates = remember { mutableMapOf<String, LazyListState>() }
    val rowEntryFocus = remember { mutableMapOf<String, FocusRequester>() }

    fun stateFor(key: String) = rowStates.getOrPut(key) { LazyListState() }
    fun focusFor(key: String) = rowEntryFocus.getOrPut(key) { FocusRequester() }

    fun moveToFirstCard(from: Int, direction: Int): Boolean {
        val candidates = if (direction < 0) (from - 1 downTo 0) else (from + 1 until state.rows.size)
        val targetIndex = candidates.firstOrNull { state.rows[it].itemCount > 0 } ?: return false
        val targetRow = state.rows[targetIndex]
        val targetState = if (targetRow is HomeRow.AllCatalogs) null else stateFor(targetRow.key)
        targetState?.requestScrollToItem(0)
        scope.launch {
            listState.scrollToItem(targetIndex)
            val ready = withTimeoutOrNull(500) {
                snapshotFlow {
                    listState.layoutInfo.visibleItemsInfo.any { it.index == targetIndex } &&
                        (targetState == null || targetState.layoutInfo.visibleItemsInfo.any { it.index == 0 })
                }.first { it }
            } != null
            if (ready) {
                withFrameNanos { }
                runCatching { focusFor(targetRow.key).requestFocus() }
            }
        }
        return true
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = 54.dp),
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(rowsFocus)
                .focusRestorer()
                .focusGroup(),
        ) {
            itemsIndexed(state.rows) { index, row ->
                // A reserved Continue or Collections row that is still empty draws nothing and
                // takes no height. It keeps its key, so the day it fills it grows in place
                // instead of being inserted above everything (F04).
                if (row.itemCount == 0 && row !is HomeRow.Posters) return@itemsIndexed
                Box(Modifier.animateItem().padding(bottom = RowGap)) {
                    when (row) {
                        is HomeRow.AllCatalogs -> {
                            Column {
                                ShelfHeader(row.title)
                                Spacer(Modifier.height(16.dp))
                                TvButton(
                                    label = "Browse all catalogs",
                                    onClick = onAllCatalogs,
                                    modifier = Modifier
                                        .focusRequester(focusFor(row.key))
                                        .onFocusChanged { if (it.hasFocus) onRowFocused(index, row.key) }
                                        .onKeyEvent { event ->
                                            event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp &&
                                                moveToFirstCard(index, -1)
                                        },
                                )
                            }
                        }
                        is HomeRow.Continue -> TvCardRow(
                            title = row.title,
                            items = row.items,
                            key = { it.card.key },
                            rowState = stateFor(row.key),
                            firstCardFocus = focusFor(row.key),
                            onVertical = { direction -> moveToFirstCard(index, direction) },
                            restoreFocus = false,
                        ) { _, item ->
                            val report = LocalCardFocusReport.current
                            HomeContinueCardView(
                                item = item,
                                onClick = { onResume(item) },
                                onLongClick = { onAddToCollection(item.card) },
                                onFocused = {
                                    report()
                                    onRowFocused(index, row.key)
                                    onCardFocused(item.card, item)
                                },
                            )
                        }

                        is HomeRow.Collections -> TvCardRow(
                            title = row.title,
                            items = row.items,
                            key = { "folder:${it.id}" },
                            rowState = stateFor(row.key),
                            firstCardFocus = focusFor(row.key),
                            onVertical = { direction -> moveToFirstCard(index, direction) },
                            restoreFocus = false,
                        ) { _, folder ->
                            val report = LocalCardFocusReport.current
                            HomeFolderCardView(
                                folder = folder,
                                onClick = { onOpenCollection(folder.id) },
                                // The hero keeps the playable card it settled on while collection
                                // focus moves below it; empty collections never enter this row.
                                onFocused = {
                                    report()
                                    onRowFocused(index, row.key)
                                },
                            )
                        }

                        is HomeRow.Posters -> if (row.items.isEmpty()) {
                            // Spec §3.7: a row that has not answered keeps its skeleton and does
                            // not move. A row that answered empty holds the same block, quietly,
                            // until the ring is above it. Only the first two rows sweep.
                            ShelfSkeletonRow(sweep = index < 2 && !row.removed)
                        } else {
                            TvCardRow(
                                title = row.title,
                                items = row.items,
                                key = { it.key },
                                staleLabel = row.staleLabel,
                                rowState = stateFor(row.key),
                                firstCardFocus = focusFor(row.key),
                                onVertical = { direction -> moveToFirstCard(index, direction) },
                                restoreFocus = false,
                                trailingCard = row.catalog?.let { ref ->
                                    {
                                        val report = LocalCardFocusReport.current
                                        SeeAllCard(
                                            shelfTitle = row.title,
                                            onClick = { onSeeAll(ref) },
                                            onFocused = {
                                                report()
                                                onRowFocused(index, row.key)
                                            },
                                        )
                                    }
                                },
                            ) { cardIndex, card ->
                                val report = LocalCardFocusReport.current
                                HomePosterCard(
                                    card = card,
                                    onClick = { onOpenDetail(card) },
                                    onLongClick = { onAddToCollection(card) },
                                    onFocused = {
                                        report()
                                        onRowFocused(index, row.key)
                                        onCardFocused(card, null)
                                        onPosterFocused(row.items, cardIndex)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShelfSkeletonRow(sweep: Boolean) {
    Column {
        Skeleton(Modifier.size(240.dp, 32.dp), sweep = sweep)
        Spacer(Modifier.height(ShelfHeaderGap))
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            repeat(6) { Skeleton(Modifier.size(HomePosterWidth, HomePosterHeight), sweep = sweep) }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexed(
    rows: List<HomeRow>,
    content: @Composable androidx.compose.foundation.lazy.LazyItemScope.(Int, HomeRow) -> Unit,
) = items(count = rows.size, key = { rows[it].key }) { index -> content(index, rows[index]) }

internal fun HomeCard.toCollectionCandidate(): CollectionCandidate =
    CollectionCandidate(canonicalId = id, mediaType = type, title = title, posterUrl = posterUrl)
