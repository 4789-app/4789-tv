@file:Suppress("OPT_IN_USAGE")
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.fourseveneightnine.tv.client.ui.screens.home

import com.fourseveneightnine.tv.client.ui.components.benchmarkDraw
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics
import com.fourseveneightnine.tv.client.playback.WatchlistSource
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
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
import com.fourseveneightnine.tv.client.ui.components.PanelTransition
import com.fourseveneightnine.tv.client.ui.nav.ClientNav
import com.fourseveneightnine.tv.client.ui.screens.detail.DetailPreview
import com.fourseveneightnine.tv.client.ui.screens.detail.DetailPlay
import com.fourseveneightnine.tv.client.ui.screens.collections.rememberLoveTitle
import com.fourseveneightnine.tv.client.ui.screens.collections.CollectionCandidate
import com.fourseveneightnine.tv.client.ui.theme.TvGeom
import com.fourseveneightnine.tv.client.ui.theme.LocalReduceMotion
import com.fourseveneightnine.tv.ui.TvTokens
import com.fourseveneightnine.tv.ui.throttleDpadRepeats
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// A rapid tap sequence can still arrive 200 ms apart on a TV remote.
private const val HOME_HERO_SETTLE_MILLIS = 260L
internal const val HOME_BROWSE_TAG = "home-browse"

/**
 * Home, spec §3. A hero that follows the ring, and every shelf below it.
 *
 * Three rules shape the whole file, and each one was a bug on this fleet before it was a rule.
 *
 *  - The ring answers each key press. The hero waits for a pause so a fast sweep can move focus
 *    without decoding artwork or laying out text for every card.
 *  - Exact return requesters belong to composed cells; the model keeps only stable keys.
 *  - Focus moves because a key was pressed. The one exception is the first placement on entry, and
 *    it is cancelled the moment the viewer touches the remote.
 */
@Composable
internal fun HomeScreen(nav: ClientNav, explicitEntry: Int = 0, visible: Boolean = true) {
    val context = LocalContext.current
    val posterWidthPx = with(LocalDensity.current) { TvGeom.CatalogPosterWidth.roundToPx().coerceAtLeast(1) }
    val client = remember(context) { context.clientGraph }
    val scope = rememberCoroutineScope()
    val viewModel: HomeViewModel = viewModel { HomeViewModel(client).also { it.start() } }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val latestState = rememberUpdatedState(state)
    val shell = LocalShellState.current

    val loadingMenuFocus = remember { FocusRequester() }
    val rowsFocus = remember { FocusRequester() }
    val catalogFocus = remember { FocusRequester() }
    val emptyFocus = remember { FocusRequester() }
    // These are held as State OBJECTS, never as `by` delegates read in this scope.
    //
    // A focus move writes the focused card, and a `by` read here would recompose the whole screen —
    // the hero, the backdrop and every shelf — once per key press. Measured on the onn 4K Pro that
    // was 57% janky frames at a 40 ms median across a 30-press sweep. Reading them only inside the
    // hero, through the lambdas below, leaves a move costing the two cards whose ring changed.
    val focusedCard = viewModel.focusedHeroCard
    val focusedContinue = viewModel.focusedHeroContinue
    val settledCard = viewModel.settledHeroCard
    val titleRatings = remember { mutableStateMapOf<String, Ratings>() }
    val prefetchedPosters = remember { LinkedHashSet<String>() }
    // The row the ring last sat on, as a plain holder for the same reason: it is read only by the
    // rescue net below, and a State here would recompose the screen on every vertical move.
    val focusedRow = remember { intArrayOf(-1) }
    val focusedRowKey = remember { arrayOfNulls<String>(1) }
    val listState = rememberLazyListState()
    val rowEntryFocus = remember { mutableMapOf<String, ShelfEntryFocus>() }
    val catalogState by viewModel.catalog.collectAsStateWithLifecycle()
    val seeAll = catalogState?.catalog
    if (com.fourseveneightnine.tv.BuildConfig.BENCHMARK_LOGGING) {
        LaunchedEffect(visible, state.hasContent) {
            if (visible && state.hasContent) {
                ReceiverDiagnostics.benchmark("home.shelf_available") { "rows=${state.rows.size}" }
            }
        }
    }
    var returnRowKey by remember { mutableStateOf(viewModel.detailReturnRowKey) }
    var returnCardKey by remember { mutableStateOf(viewModel.detailReturnCardKey) }

    val loveTitle = rememberLoveTitle(nav)
    var interacted by remember { mutableStateOf(false) }
    var playJob by remember { mutableStateOf<Job?>(null) }
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(explicitEntry) {
        if (explicitEntry == viewModel.appliedHomeEntry) return@LaunchedEffect
        viewModel.appliedHomeEntry = explicitEntry
        viewModel.closeCatalog()
        viewModel.detailReturnRowKey = null
        viewModel.detailReturnCardKey = null
        returnRowKey = null
        returnCardKey = null
        focusedRow[0] = -1
        focusedRowKey[0] = null
        interacted = false
        placed = false
        listState.scrollToItem(0)
        withFrameNanos { }
        runCatching { rowsFocus.requestFocus() }
    }
    fun finishReturn(restored: Boolean) {
        viewModel.detailReturnRowKey = null
        viewModel.detailReturnCardKey = null
        returnRowKey = null
        returnCardKey = null
        placed = restored
        if (!restored) runCatching { rowsFocus.requestFocus() }
    }
    LaunchedEffect(visible) {
        if (!visible || returnCardKey != null) return@LaunchedEffect
        val target = viewModel.detailReturnCardKey ?: return@LaunchedEffect
        if (shell.restorePosterFocus(target)) {
            finishReturn(true)
        } else {
            returnCardKey = target
            returnRowKey = viewModel.detailReturnRowKey
        }
    }
    // The last saved row can disappear after the panel closes and its database write settles.
    LaunchedEffect(visible, state.hasContent, state.loading, seeAll != null) {
        if (visible && !state.hasContent && !state.loading && seeAll == null) {
            runCatching { emptyFocus.requestFocus() }
        }
    }

    /**
     * The hero waits for focus to settle, and a move inside that window cancels the
     * pending swap rather than queueing another one. The ring follows focus immediately.
     *
     * Keyed on Unit and reading the focused card through `snapshotFlow`, so the read happens in the
     * coroutine instead of in this composable's scope. `collectLatest` is what cancels a pending
     * swap: nothing queues up.
     */
    LaunchedEffect(Unit) {
        var metadataJob: Job? = null
        snapshotFlow {
            val card = focusedCard.value
            if (card != null && focusedContinue.value?.card?.key == card.key) {
                latestState.value.continuationFor(card)?.card ?: card
            } else card
        }.collectLatest { card ->
            if (card == null) return@collectLatest
            metadataJob?.cancel()
            if (settledCard.value?.key != card.key) {
                delay(HOME_HERO_SETTLE_MILLIS)
            }
            settledCard.value = card
            ReceiverDiagnostics.benchmark("home.hero_settled") { "type=${card.type} hasBackdrop=${card.backdropUrl != null}" }
            // The focus-settle pause already bounds rapid sweeps. Warm pixels with metadata,
            // and let Detail share the client-owned request after Home disappears.
            if (card.metadataLookupAllowed) client.warmDetail(card.type, card.id, card.tmdbID,
                context.resources.displayMetrics.widthPixels)
            if (card.metadataLookupAllowed) {
                val library = if (focusedContinue.value?.card?.key == card.key) client.activeLibrary else null
                metadataJob = client.scope.launch {
                    val services = client.services.filterNotNull().first()
                    fun showHero(meta: com.fourseveneightnine.tv.client.data.meta.Meta) {
                        settledCard.value?.takeIf { it.key == card.key }?.let { shown ->
                            settledCard.value = shown.copy(
                                posterUrl = meta.poster ?: shown.posterUrl,
                                backdropUrl = meta.backdrop ?: shown.backdropUrl,
                                logoUrl = meta.logo ?: shown.logoUrl,
                                overview = meta.description ?: shown.overview,
                                runtimeMinutes = meta.runtimeMinutes ?: shown.runtimeMinutes,
                            )
                        }
                    }
                    val meta = viewModel.metadataFor(card, services, library, onBase = { preview ->
                        launch(Dispatchers.Main) { showHero(preview) }
                    }) ?: return@launch
                    withContext(Dispatchers.Main) { showHero(meta) }
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
    LaunchedEffect(state.rows, state.libraryReady, interacted) {
        if (!state.libraryReady || interacted || settledCard.value != null) return@LaunchedEffect
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
    DisposableEffect(visible, state.hasContent, seeAll != null) {
        if (!visible) return@DisposableEffect onDispose {}
        val restore = {
            val current = latestState.value
            if (seeAll != null) {
                runCatching { if (!catalogFocus.restoreFocusedChild()) catalogFocus.requestFocus() }
            } else if (!current.hasContent || !current.libraryReady) {
                runCatching { (if (current.loading) loadingMenuFocus else emptyFocus).requestFocus() }
            } else {
                scope.launch {
                    val keyed = current.rows.indexOfFirst { it.key == focusedRowKey[0] }
                    val row = HomePlan.restoreRow(if (keyed >= 0) keyed else focusedRow[0], current.rows.size)
                    if (listState.layoutInfo.visibleItemsInfo.none { it.index == row }) {
                        runCatching { listState.scrollToItem(row) }
                    }
                    val entry = current.rows.getOrNull(row)?.key?.let(rowEntryFocus::get)
                    val target = entry?.attachedCards?.get(entry.lastCardKey)
                    val restored = runCatching { target?.requestFocus() == true }.getOrDefault(false)
                    if (!restored) runCatching { if (!rowsFocus.restoreFocusedChild()) rowsFocus.requestFocus() }
                }
            }
            Unit
        }
        shell.restoreContentFocus = restore
        onDispose { if (shell.restoreContentFocus === restore) shell.restoreContentFocus = null }
    }

    // Initial focus, placed once. The loading menu holds focus until the first row has
    // items; after that the viewer's own first key press wins and nothing moves focus again.
    LaunchedEffect(visible, state.hasContent, state.libraryReady, interacted, returnCardKey, seeAll) {
        if (!visible || returnCardKey != null || seeAll != null) return@LaunchedEffect
        if (!state.libraryReady) {
            withFrameNanos { }
            runCatching { loadingMenuFocus.requestFocus() }
            return@LaunchedEffect
        }
        if (placed) return@LaunchedEffect
        if (!state.hasContent) {
            // Whatever is drawn instead of a shelf still has to hold the remote: an empty screen
            // with nothing focused trips the shell's rescue net and opens the rail on its own.
            if (!runCatching { emptyFocus.requestFocus() }.getOrDefault(false)) {
                runCatching { loadingMenuFocus.requestFocus() }
            }
            return@LaunchedEffect
        }
        if (interacted) { placed = true; return@LaunchedEffect }
        // The first row's requester attaches a frame or two after `hasContent` flips. A single
        // swallowed failure here left the screen with no focus at all, so the shell's rescue net
        // opened the rail on every cold start. Retry briefly; mark placed only on success.
        repeat(12) {
            withFrameNanos { }
            if (runCatching { rowsFocus.requestFocus() }.getOrDefault(false)) {
                placed = true
                return@LaunchedEffect
            }
        }
    }

    fun play(card: HomeCard, continueCard: HomeContinueCard?) {
        playJob?.cancel()
        playJob = viewModel.viewModelScope.launch {
            if (card.id.startsWith(WatchlistSource.PREFIX)) {
                nav.openDetail(card.type, card.id)
                return@launch
            }
            val progress = client.activeLibrary.playbackHistory().first()
                .firstOrNull { it.canonicalId == card.id && it.mediaType == card.type }
            val exactFile = card.id.startsWith("saved-torbox:") ||
                client.savedCatalogItem(card.type, card.id)?.playbackSources?.singleOrNull()?.fileID != null
            val saved = latestState.value.continuationFor(card) ?: continueCard
            val meta = client.services.value?.takeIf { card.type == "series" && !exactFile && saved?.episode == null }?.let { services ->
                try { services.meta.meta(card.type, card.id) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            }
            val request = if (saved != null && (card.type != "series" || saved.episode != null)) {
                PlayRequest(card.type, card.id, card.title, saved.season, saved.episode,
                    card.posterUrl, card.backdropUrl, saved.resumeFromMs)
            } else if (meta != null) {
                DetailPlay.pick(meta, progress, java.time.LocalDate.now().toString()).request(meta)
                    .copy(type = card.type, id = card.id, title = card.title)
            } else PlayRequest(type = card.type, id = card.id, title = card.title,
                season = saved?.season ?: progress?.season,
                episode = saved?.episode ?: progress?.episode,
                posterUrl = card.posterUrl, backdropUrl = card.backdropUrl,
                resumeFromMs = saved?.resumeFromMs ?: progress?.resumePositionMs)
            if (card.type == "series" && !exactFile && (request.season == null || request.episode == null)) {
                DetailPreview.stage(card)
                nav.openDetail(card.type, card.id)
                nav.toast("Choose an available episode.")
                return@launch
            }
            when (val result = client.playFlow.play(request)) {
                PlayResult.Opened -> Unit
                PlayResult.ShowList -> nav.openStreams(request.type, request.id, request.season, request.episode)
                is PlayResult.Failed -> {
                    if (viewModel.detailReturnCardKey == card.key) {
                        viewModel.detailReturnRowKey = null
                        viewModel.detailReturnCardKey = null
                    }
                    nav.toast(result.message)
                    if (saved != null && card.type == "series" && request.season != null && request.episode != null)
                        nav.openStreams(request.type, request.id, request.season, request.episode)
                }
            }
        }
    }

    fun prefetchAhead(cards: List<HomeCard>, focusedIndex: Int) {
        // LazyRow loads the visible cards. Warm only the next few beyond that window, so a fast
        // RIGHT sweep lands on cached pixels instead of asking the network after focus arrives.
        val start = (focusedIndex + 3).coerceIn(0, cards.size)
        val ahead = cards.subList(start, (start + 4).coerceAtMost(cards.size))
            .mapNotNull(HomeCard::posterUrl).filter(String::isNotBlank)
            .filter { prefetchedPosters.add(it) }
        while (prefetchedPosters.size > 512) prefetchedPosters.remove(prefetchedPosters.first())
        if (ahead.isEmpty()) return
        val appContext = context.applicationContext
        client.scope.launch {
            ahead.forEach { url ->
                client.imageLoader.enqueue(PosterRequest.poster(url, posterWidthPx).toImageRequest(appContext))
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .benchmarkDraw("home", visible)
            .then(if (visible) Modifier.testTag(HOME_BROWSE_TAG) else Modifier)
            .throttleDpadRepeats()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (event.key == Key.Back) return@onPreviewKeyEvent false
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
                state.hasContent && state.libraryReady -> {
                    // The ring is immediate. Updating the title, ratings and artwork together
                    // after a brief pause keeps fast browsing from laying out every synopsis.
                    HomeHero(
                        card = { settledCard.value },
                        ratings = { card -> titleRatings[card.key] ?: Ratings.NONE },
                    )
                    ShelfBand(
                        state = state,
                        rowsFocus = rowsFocus,
                        listState = listState,
                        rowEntryFocus = rowEntryFocus,
                        onCardFocused = { card, continueCard ->
                            focusedCard.value = card
                            focusedContinue.value = continueCard
                        },
                        onPosterFocused = ::prefetchAhead,
                        onRowFocused = { index, key ->
                            focusedRow[0] = index
                            focusedRowKey[0] = key
                            viewModel.onRowFocused(key)
                        },
                        returnRowKey = if (seeAll == null) returnRowKey else null,
                        returnCardKey = if (seeAll == null) returnCardKey else null,
                        onReturnFinished = ::finishReturn,
                        onOpenDetail = { card, rowKey ->
                            viewModel.detailReturnRowKey = rowKey
                            viewModel.detailReturnCardKey = card.key
                            settledCard.value = card
                            DetailPreview.stage(card)
                            nav.openDetail(card.type, card.id)
                        },
                        onResume = { item ->
                            viewModel.detailReturnRowKey = HomeRow.Continue.KEY
                            viewModel.detailReturnCardKey = item.card.key
                            play(item.card, item)
                        },
                        onOpenCollection = { id, rowKey ->
                            if (rowKey != null) {
                                viewModel.detailReturnRowKey = rowKey
                                viewModel.detailReturnCardKey = "see-all"
                            }
                            nav.openCollection(id.toString())
                        },
                        onSeeAll = { ref, rowKey -> rowsFocus.saveFocusedChild(); viewModel.openCatalog(ref, rowKey) },
                        onAllCatalogs = nav::openAllCatalogs,
                        onLoveTitle = {
                            loveTitle(it.toCollectionCandidate())
                        },
                    )
                }

                // Loading, spec §3.7. The skeleton mirrors the loaded layout, so nothing jumps.
                state.loading -> {
                    HomeHeroSkeleton(
                        actionModifier = Modifier.focusRequester(loadingMenuFocus),
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
                    StateBlock(
                        headline = "Nothing to show yet",
                        line = "Add a catalog, import your settings, or set up Live TV.",
                        actionLabel = "Open Add-ons",
                        onAction = { nav.openSettings("addons") },
                        secondaryLabel = "Open Setup",
                        onSecondary = { nav.openSettings("pair") },
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

        PanelTransition(catalogState, fade = true) { catalog ->
            CatalogGridOverlay(
                state = catalog,
                active = visible && seeAll != null,
                returnCardKey = if (seeAll != null) returnCardKey else null,
                onReturnFinished = ::finishReturn,
                onLoadMore = viewModel::loadMoreCatalog,
                onToast = nav::toast,
                onClose = {
                    returnRowKey = viewModel.catalogOriginRowKey
                    returnCardKey = "see-all"
                    viewModel.closeCatalog()
                },
                gridFocus = catalogFocus,
                savePanelOpen = false,
                onOpen = {
                    viewModel.detailReturnRowKey = null
                    viewModel.detailReturnCardKey = it.key
                    DetailPreview.stage(it)
                    nav.openDetail(it.type, it.id)
                },
                onLongOpen = {
                    loveTitle(it.toCollectionCandidate())
                },
            )
        }
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
    rowEntryFocus: MutableMap<String, ShelfEntryFocus>,
    onCardFocused: (HomeCard?, HomeContinueCard?) -> Unit,
    onPosterFocused: (List<HomeCard>, Int) -> Unit,
    onRowFocused: (Int, String) -> Unit,
    returnRowKey: String?,
    returnCardKey: String?,
    onReturnFinished: (Boolean) -> Unit,
    onOpenDetail: (HomeCard, String) -> Unit,
    onResume: (HomeContinueCard) -> Unit,
    onOpenCollection: (Long, String?) -> Unit,
    onSeeAll: (HomeCatalogRef, String) -> Unit,
    onAllCatalogs: () -> Unit,
    onLoveTitle: (HomeCard) -> Unit,
) {
    val spec = rememberShelfVisibility()
    val density = LocalDensity.current
    val reduceMotion = LocalReduceMotion.current
    val scope = rememberCoroutineScope()
    val navigationJob = remember { arrayOfNulls<Job>(1) }
    val pendingRowKey = remember { arrayOfNulls<String>(1) }
    val latestRows = rememberUpdatedState(state.rows)
    val rowStates = remember { mutableMapOf<String, LazyListState>() }

    fun stateFor(key: String) = rowStates.getOrPut(key) { LazyListState() }
    fun focusFor(key: String) = rowEntryFocus.getOrPut(key) { ShelfEntryFocus() }

    LaunchedEffect(returnRowKey, returnCardKey, state.rows) {
        if (returnRowKey == null || returnCardKey == null) return@LaunchedEffect
        val index = state.rows.indexOfFirst { row -> row.key == returnRowKey && when (row) {
            is HomeRow.Posters -> row.items.any { it.key == returnCardKey } ||
                (returnCardKey == "see-all" && (row.catalog != null || row.savedCollectionId != null))
            is HomeRow.Continue -> row.items.any { it.card.key == returnCardKey }
            else -> false
        } }
        if (index < 0) { onReturnFinished(false); return@LaunchedEffect }
        if (listState.layoutInfo.visibleItemsInfo.none { it.key == returnRowKey }) listState.scrollToItem(index)
    }

    fun moveToRow(from: Int, direction: Int): Boolean {
        val targetIndex = HomePlan.verticalTarget(state.rows, from,
            if (navigationJob[0]?.isActive == true) pendingRowKey[0] else null, direction)
            ?: return true // The hero is a readout. Keep focus at the shelf boundary.
        val targetRow = state.rows[targetIndex]
        navigationJob[0]?.cancel()

        fun focusTarget(): Boolean = runCatching {
            val target = focusFor(targetRow.key)
            val cards = target.attachedCards
            val visible = rowStates[targetRow.key]?.layoutInfo?.visibleItemsInfo
            val visibleCard = visible?.firstNotNullOfOrNull { cards[it.key] }
            (cards[target.lastCardKey] ?: visibleCard ?: target.row).requestFocus()
        }.getOrDefault(false)

        val viewport = listState.layoutInfo
        val visible = viewport.visibleItemsInfo.firstOrNull { it.key == targetRow.key }
        val rowGapPx = with(density) { RowGap.roundToPx() }
        val fullyVisible = visible != null && visible.offset >= viewport.viewportStartOffset &&
            visible.offset + visible.size - rowGapPx <= viewport.viewportEndOffset
        val alreadyFocused = state.rows.getOrNull(from)?.key == targetRow.key
        // An attached shelf can take focus right away. The list only scrolls if the target
        // needs more room, and Compose retargets that scroll on the next focus change.
        // A reversal can target the still-focused card after an interrupted scroll. Requesting
        // its focus again does not trigger bring-into-view, so finish positioning the shelf.
        if (visible != null && (fullyVisible || (!reduceMotion && !alreadyFocused)) && focusTarget()) {
            return true
        }

        // Bring an offscreen short shelf in at the bottom instead of moving it past the
        // posters still on screen. A tall shelf can use the whole browse band.
        val viewportHeight = viewport.viewportEndOffset - viewport.viewportStartOffset
        val shelfHeight = with(density) { (rowBlockHeight(targetRow) + 24.dp).roundToPx() }
        val scrollOffset = if (direction > 0) -(viewportHeight - shelfHeight).coerceAtLeast(0) else 0
        pendingRowKey[0] = targetRow.key
        val job = scope.launch {
            // A refresh can move the row while scrolling. Replace that animation using the new
            // index of the same key; a removed destination cannot receive focus.
            val ready = snapshotFlow { latestRows.value.indexOfFirst { it.key == targetRow.key && it.itemCount > 0 } }
                .mapLatest { index ->
                    if (index < 0) false else {
                        if (reduceMotion) listState.scrollToItem(index, scrollOffset)
                        else listState.animateScrollToItem(index, scrollOffset)
                        withTimeoutOrNull(500) {
                            snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.key == targetRow.key } }
                                .first { it }
                        } != null
                    }
                }.first()
            if (ready) {
                withFrameNanos { }
                focusTarget()
            }
        }
        navigationJob[0] = job
        job.invokeOnCompletion {
            if (navigationJob[0] === job) { navigationJob[0] = null; pendingRowKey[0] = null }
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
                .then(if (returnCardKey == null) Modifier.focusRestorer() else Modifier)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key != Key.DirectionUp && event.key != Key.DirectionDown) {
                        navigationJob[0]?.cancel()
                    }
                    false
                },
        ) {
            itemsIndexed(state.rows) { index, row ->
                // A reserved Continue or Collections row that is still empty draws nothing and
                // takes no height. It keeps its key, so the day it fills it grows in place
                // instead of being inserted above everything (F04).
                if (row.itemCount == 0 && row !is HomeRow.Posters) return@itemsIndexed
                Box(Modifier.then(if (reduceMotion) Modifier else Modifier.animateItem()).padding(bottom = RowGap)) {
                    when (row) {
                        is HomeRow.AllCatalogs -> {
                            Column {
                                ShelfHeader(row.title)
                                Spacer(Modifier.height(16.dp))
                                TvButton(
                                    label = "Browse all catalogs",
                                    onClick = onAllCatalogs,
                                    modifier = Modifier
                                        .focusRequester(focusFor(row.key).row)
                                        .onFocusChanged { if (it.hasFocus) onRowFocused(index, row.key) }
                                        .onKeyEvent { event ->
                                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                                            when (event.key) {
                                                Key.DirectionUp -> moveToRow(index, -1)
                                                Key.DirectionDown -> moveToRow(index, 1)
                                                else -> false
                                            }
                                        },
                                )
                            }
                        }
                        is HomeRow.Continue -> {
                            val rowState = stateFor(row.key)
                            val returnFocus = remember { FocusRequester() }
                            val restoresHere = returnRowKey == row.key && returnCardKey != null
                            LaunchedEffect(restoresHere, returnCardKey, row.items) {
                                if (!restoresHere) return@LaunchedEffect
                                val index = row.items.indexOfFirst { it.card.key == returnCardKey }
                                if (index < 0) { onReturnFinished(false); return@LaunchedEffect }
                                rowState.scrollToItem(index)
                                snapshotFlow { rowState.layoutInfo.visibleItemsInfo.any { it.key == returnCardKey } }.first { it }
                                withFrameNanos { }
                                onReturnFinished(runCatching { returnFocus.requestFocus() }.getOrDefault(false))
                            }
                            TvCardRow(
                            title = row.title,
                            items = row.items,
                            key = { it.card.key },
                            rowState = rowState,
                            entryFocus = focusFor(row.key),
                            onVertical = { direction -> moveToRow(index, direction) },
                            restoreFocus = false,
                        ) { _, item ->
                            val report = LocalCardFocusReport.current
                            HomeContinueCardView(
                                item = item,
                                modifier = if (restoresHere && item.card.key == returnCardKey) Modifier.focusRequester(returnFocus) else Modifier,
                                onClick = { onResume(item) },
                                onLongClick = { onLoveTitle(item.card) },
                                onFocused = {
                                    report()
                                    onRowFocused(index, row.key)
                                    onCardFocused(item.card, item)
                                },
                            )
                        }

                        }
                        is HomeRow.Collections -> TvCardRow(
                            title = row.title,
                            items = row.items,
                            key = { "folder:${it.id}" },
                            rowState = stateFor(row.key),
                            entryFocus = focusFor(row.key),
                            onVertical = { direction -> moveToRow(index, direction) },
                            restoreFocus = false,
                        ) { _, folder ->
                            val report = LocalCardFocusReport.current
                            HomeFolderCardView(
                                folder = folder,
                                onClick = { onOpenCollection(folder.id, null) },
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
                            val openAll: (() -> Unit)? = row.savedCollectionId?.let { id -> { onOpenCollection(id, row.key) } }
                                ?: row.catalog?.let { ref -> { onSeeAll(ref, row.key) } }
                            val returnFocus = remember { FocusRequester() }
                            val restoresHere = returnRowKey == row.key && returnCardKey != null
                            val rowState = stateFor(row.key)
                            LaunchedEffect(restoresHere, returnCardKey, row.items) {
                                if (!restoresHere) return@LaunchedEffect
                                val target = if (returnCardKey == "see-all") row.items.size
                                    else row.items.indexOfFirst { it.key == returnCardKey }
                                if (target < 0) { onReturnFinished(false); return@LaunchedEffect }
                                if (rowState.layoutInfo.visibleItemsInfo.none { it.key == returnCardKey }) rowState.scrollToItem(target)
                                snapshotFlow { rowState.layoutInfo.visibleItemsInfo.any { it.key == returnCardKey } }.first { it }
                                withFrameNanos { }
                                onReturnFinished(runCatching { returnFocus.requestFocus() }.getOrDefault(false))
                            }
                            TvCardRow(
                                title = row.title,
                                items = row.items,
                                key = { it.key },
                                staleLabel = row.staleLabel,
                                rowState = stateFor(row.key),
                                entryFocus = focusFor(row.key),
                                onVertical = { direction -> moveToRow(index, direction) },
                                restoreFocus = false,
                                trailingCard = openAll?.let { action ->
                                    {
                                        val report = LocalCardFocusReport.current
                                        SeeAllCard(
                                            shelfTitle = row.title,
                                            onClick = action,
                                            modifier = if (restoresHere && returnCardKey == "see-all") Modifier.focusRequester(returnFocus) else Modifier,
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
                                    modifier = if (restoresHere && card.key == returnCardKey) Modifier.focusRequester(returnFocus) else Modifier,
                                    onClick = { onOpenDetail(card, row.key) },
                                    onLongClick = { onLoveTitle(card) },
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
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.padding(start = TvGeom.RowEdgeInset),
            horizontalArrangement = Arrangement.spacedBy(TvGeom.CatalogPosterGap),
        ) {
            repeat(TvTokens.Geometry.CatalogPosterColumns) {
                Skeleton(Modifier.size(TvGeom.CatalogPosterWidth, TvGeom.CatalogPosterHeight), sweep = sweep)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexed(
    rows: List<HomeRow>,
    content: @Composable androidx.compose.foundation.lazy.LazyItemScope.(Int, HomeRow) -> Unit,
) = items(count = rows.size, key = { rows[it].key }) { index -> content(index, rows[index]) }

private fun HomeState.continuationFor(card: HomeCard): HomeContinueCard? =
    rows.filterIsInstance<HomeRow.Continue>().firstOrNull()?.items?.firstOrNull { it.card.key == card.key }

internal fun HomeCard.toCollectionCandidate(): CollectionCandidate =
    CollectionCandidate(canonicalId = id, mediaType = type, title = title, posterUrl = posterUrl)
