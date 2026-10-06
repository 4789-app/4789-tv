@file:Suppress("OPT_IN_USAGE")

package com.fourseveneightnine.tv.client.ui.screens.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.fourseveneightnine.contract.DiscoverItem
import com.fourseveneightnine.tv.client.ClientGraph
import com.fourseveneightnine.tv.client.appGraph
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.data.addons.CatalogExtra
import com.fourseveneightnine.tv.client.data.images.PosterRequest
import com.fourseveneightnine.tv.client.iptv.IptvIndex
import com.fourseveneightnine.tv.client.search.SystemSearchPolicy
import com.fourseveneightnine.tv.client.ui.LocalShellState
import com.fourseveneightnine.tv.client.ui.components.EmptyState
import com.fourseveneightnine.tv.client.ui.components.KeyStroke
import com.fourseveneightnine.tv.client.ui.components.Skeleton
import com.fourseveneightnine.tv.client.ui.components.StateBlock
import com.fourseveneightnine.tv.client.ui.components.TvArtwork
import com.fourseveneightnine.tv.client.ui.components.TvFocusable
import com.fourseveneightnine.tv.client.ui.components.TvKeyboard
import com.fourseveneightnine.tv.client.ui.components.accessibilityLabel
import com.fourseveneightnine.tv.client.ui.nav.ClientNav
import com.fourseveneightnine.tv.client.ui.screens.settings.SettingsKeys
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvGeom
import com.fourseveneightnine.tv.client.ui.theme.TvShape
import com.fourseveneightnine.tv.client.ui.theme.TvSpace
import com.fourseveneightnine.tv.client.ui.theme.TvType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What the results side is showing, spec §12.5. */
private sealed interface SearchPhase {
    /** No query yet: the invitation and the recent list. */
    data object Idle : SearchPhase

    /** No settings document, so no add-on can be asked. */
    data object NoAddons : SearchPhase
    data object Searching : SearchPhase
    data class Loaded(val sections: List<SearchSection>) : SearchPhase
    data object Failed : SearchPhase
}

/** At most this many catalogs are asked, so one query is never forty HTTP calls. */
private const val MAX_CATALOGS = 12
private const val PER_CATALOG_MILLIS = 6_000L
private const val SET_DEADLINE_MILLIS = 9_000L

/**
 * Search, spec §12.
 *
 * The keyboard is a fixed grid that never moves or reflows — that is what makes a remote fast —
 * and the results are a readout of it. Typing is held for 400 ms before a query is sent, and the
 * results never take focus on their own: focus reaches them only when RIGHT is pressed.
 */
@Composable
internal fun SearchScreen(
    nav: ClientNav,
    initialQuery: String = "",
    initialQueryKey: Long = 0L,
) {
    val context = LocalContext.current
    val client = remember(context) { context.clientGraph }
    val prefs = remember(context) { context.appGraph.presentationPreferences }
    val services by client.services.collectAsState()
    val shelves by client.snapshots.shelves().collectAsState()
    val iptv by client.iptv.state.collectAsState()
    val shell = LocalShellState.current

    var query by remember { mutableStateOf(SystemSearchPolicy.boundedQuery(initialQuery).orEmpty()) }
    var phase by remember { mutableStateOf<SearchPhase>(SearchPhase.Idle) }
    var retryToken by remember { mutableIntStateOf(0) }
    // F78: seeded empty and filled off the main thread. Reading the preference file inside a
    // `remember` initializer blocks the frame that first draws the screen.
    var recents by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(prefs) {
        val stored = withContext(Dispatchers.IO) {
            SettingsKeys.readStringList(prefs, SettingsKeys.RECENT_SEARCHES)
        }
        // A search opened while the file was still being read has already written a newer list.
        if (recents.isEmpty()) recents = SearchSections.cleanRecents(stored)
    }
    var resultsFocused by remember { mutableStateOf(false) }

    // System and voice search enter through the same query state as the on-screen keyboard, so
    // debounce, add-on fan-out, result grouping and recents all keep one implementation.
    LaunchedEffect(initialQueryKey) {
        SystemSearchPolicy.boundedQuery(initialQuery)?.let { query = it }
    }

    // The one node this screen ever asks for focus: the "A" key, which is always composed
    // (plan §7.4 rule 2). Nothing here requests focus because data arrived.
    val firstKey = remember { FocusRequester() }
    DisposableEffect(Unit) {
        val restore = { runCatching { firstKey.requestFocus() }; Unit }
        shell.restoreContentFocus = restore
        onDispose { if (shell.restoreContentFocus === restore) shell.restoreContentFocus = null }
    }
    LaunchedEffect(Unit) { runCatching { firstKey.requestFocus() } }

    // Typing is held for 400 ms before a search runs (spec §12.3). A new keystroke cancels this
    // effect, so a viewer walking the keyboard issues one call, not one per letter.
    LaunchedEffect(query, services, shelves, iptv.catalog, iptv.sources, iptv.accounts.hiddenGroups,
        iptv.accounts.lockedGroups, retryToken) {
        val trimmed = query.trim()
        if (!SearchSections.shouldSearch(trimmed)) {
            phase = SearchPhase.Idle
            return@LaunchedEffect
        }
        val ready = services
        delay(SearchSections.DEBOUNCE_MILLIS)
        phase = SearchPhase.Searching
        // The complete cached phone/server list directory is searched off-main. Add-ons are then
        // queried when available; local results remain useful offline or when one add-on fails.
        val local = withContext(Dispatchers.Default) {
            val cached = SearchSections.local(shelves, trimmed)
            cached + SearchSections.iptv(iptv, trimmed)
        }
        val answered = SearchSections.progressiveResults(local,
            remote = { ready?.let { withContext(Dispatchers.IO) { searchEveryCatalog(it, trimmed) } } },
            publish = { phase = SearchPhase.Loaded(it) })
        if (!answered) phase = if (ready == null) SearchPhase.NoAddons else SearchPhase.Failed
    }

    // F83: the recent list is written when a result is opened. Writing it on every query change
    // stored "DU", "DUN" and "DUNE" as three searches, which is what the box showed.
    val scope = rememberCoroutineScope()
    fun keepRecent(term: String) {
        val next = SearchSections.recordRecent(recents, term)
        if (next == recents) return
        recents = next
        scope.launch {
            withContext(Dispatchers.IO) {
                SettingsKeys.writeStringList(prefs, SettingsKeys.RECENT_SEARCHES, next)
            }
        }
    }

    // BACK from the results goes back to the keyboard; BACK from the keyboard is the shell's,
    // which goes Home (spec §12.3).
    BackHandler(enabled = resultsFocused) { runCatching { firstKey.requestFocus() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TvColor.Canvas)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Menu) {
                    nav.openRail()
                    true
                } else {
                    false
                }
            }
            // No end padding: spec §12.2 runs the results band from x 940 to x 1920 so the fourth
            // poster shows a wider slice. The keyboard column is a fixed 680 px and needs none.
            .padding(start = TvGeom.ContentLeft, top = TvGeom.SafeTop),
    ) {
        Text("Search", style = TvType.ScreenTitle, color = TvColor.TextPrimary, maxLines = 1)
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxSize()) {
            // LEFT at keyboard column 1 opens the rail (spec §12.3). A 2 px focusable strip is
            // what makes that work without the screen consuming every LEFT: consuming LEFT at the
            // root would stop Compose's directional focus search and the keyboard could not be
            // walked at all.
            RailEdge(onOpenRail = nav::openRail)
            Column(Modifier.width(680.dp)) {
                QueryField(query)
                Spacer(Modifier.height(24.dp))
                TvKeyboard(
                    onKey = { stroke ->
                        query = when (stroke) {
                            is KeyStroke.Character -> query + stroke.value
                            KeyStroke.Space -> "$query "
                            KeyStroke.Backspace -> query.dropLast(1)
                            KeyStroke.Commit -> ""
                        }
                    },
                    firstKeyModifier = Modifier.focusRequester(firstKey),
                )
            }
            Spacer(Modifier.width(38.dp))
            Box(
                Modifier
                    .fillMaxHeight()
                    .weight(1f)
                    .onFocusChanged { resultsFocused = it.hasFocus },
            ) {
                ResultsBand(
                    phase = phase,
                    query = query.trim(),
                    recents = recents,
                    onOpen = {
                        keepRecent(query.trim())
                        when (it.type) {
                            "channel" -> nav.openLiveItem("channel", it.id)
                            "iptv-program" -> nav.openLiveItem("channel", it.id.substringBeforeLast(':'))
                            "iptv-channel-category" -> nav.openLiveItem("channel-group", it.id)
                            "iptv-movie-category" -> nav.openLiveItem("movie-group", it.id)
                            "iptv-series-category" -> nav.openLiveItem("series-group", it.id)
                            "iptv-movie", "iptv-series" -> nav.openLiveItem("vod", it.id)
                            else -> nav.openDetail(it.type, it.id)
                        }
                    },
                    onRecent = { query = it },
                    onRetry = { retryToken++ },
                    onPair = { nav.openSettings("pair") },
                )
            }
        }
    }
}

/** Spec §12.2. A readout; it never takes focus, and the caret does not blink. */
@Composable
private fun QueryField(query: String) {
    Row(
        modifier = Modifier
            .width(680.dp)
            .height(72.dp)
            .clip(TvShape.Control)
            .background(TvColor.Elevated)
            .border(1.dp, Color.White.copy(alpha = 0.10f), TvShape.Control)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (query.isEmpty()) {
            Text("Type a title", style = TvType.data(32), color = TvColor.TextMuted, maxLines = 1)
        } else {
            Text(
                query,
                style = TvType.data(32),
                color = TvColor.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(4.dp))
            Box(Modifier.size(width = 3.dp, height = 36.dp).background(TvColor.Focus))
        }
    }
}

@Composable
private fun ResultsBand(
    phase: SearchPhase,
    query: String,
    recents: List<String>,
    onOpen: (SearchResult) -> Unit,
    onRecent: (String) -> Unit,
    onRetry: () -> Unit,
    onPair: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(60.dp))
        val count = when (phase) {
            SearchPhase.Searching -> "Searching"
            is SearchPhase.Loaded -> SearchSections.countLabel(phase.sections)
            else -> null
        }
        Box(Modifier.height(22.dp)) {
            if (count != null) {
                Text(count, style = TvType.data(20), color = TvColor.TextMuted, maxLines = 1)
            }
        }
        Spacer(Modifier.height(14.dp))
        when (phase) {
            SearchPhase.Idle -> IdleSide(recents, onRecent)
            SearchPhase.Searching -> SearchingSide()
            SearchPhase.NoAddons -> EmptyState(
                headline = "Nothing to search yet",
                line = "Pair your iPhone to sync lists, or add a search-capable add-on.",
                actionLabel = "Open Pair and Sync",
                onAction = onPair,
            )
            SearchPhase.Failed -> StateBlock(
                headline = "Couldn't search right now",
                line = "Check the TV's network, then try again.",
                actionLabel = "Retry",
                onAction = onRetry,
                announceAsError = true,
            )
            is SearchPhase.Loaded ->
                if (phase.sections.isEmpty()) {
                    EmptyState(
                        headline = "No results for $query",
                        line = "Check the spelling, or try fewer words.",
                    )
                } else {
                    Sections(phase.sections, onOpen)
                }
        }
    }
}

/** Spec §12.5, empty query: the invitation, then up to five recent searches. */
@Composable
private fun IdleSide(recents: List<String>, onRecent: (String) -> Unit) {
    Column {
        Spacer(Modifier.height(62.dp))
        Text("Type a title to search", style = TvType.Body, color = TvColor.TextSecondary, maxLines = 1)
        if (recents.isEmpty()) return@Column
        Spacer(Modifier.height(36.dp))
        Text("Recent searches", style = TvType.ShelfHeader, color = TvColor.TextPrimary, maxLines = 1)
        Spacer(Modifier.height(22.dp))
        Column(verticalArrangement = Arrangement.spacedBy(TvSpace.XS)) {
            recents.forEach { term ->
                key(term) {
                    TvFocusable(
                        onClick = { onRecent(term) },
                        accessibleLabel = "Recent search, $term",
                        cornerRadius = 10.dp,
                        focusScale = 1f,
                    ) { focused ->
                        Row(
                            modifier = Modifier
                                .width(560.dp)
                                .height(64.dp)
                                .clip(TvShape.Control)
                                .background(if (focused) TvColor.Elevated2 else TvColor.Elevated)
                                .padding(horizontal = TvSpace.M),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                term,
                                style = TvType.ControlLabel,
                                color = TvColor.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Spec §12.5: six skeleton posters in one row, sweep on the first three. */
@Composable
private fun SearchingSide() {
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        repeat(6) { index ->
            Skeleton(
                modifier = Modifier.size(TvGeom.PosterWidth, TvGeom.PosterHeight),
                sweep = index < 3,
            )
        }
    }
}

@Composable
private fun Sections(sections: List<SearchSection>, onOpen: (SearchResult) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().focusRestorer(),
        verticalArrangement = Arrangement.spacedBy(36.dp),
    ) {
        items(count = sections.size, key = { sections[it].type }) { index ->
            val section = sections[index]
            Column {
                Text(section.title, style = TvType.ShelfHeader, color = TvColor.TextPrimary, maxLines = 1)
                Spacer(Modifier.height(TvSpace.S))
                LazyRow(
                    modifier = Modifier.fillMaxWidth().focusRestorer(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    contentPadding = PaddingValues(start = TvGeom.RowEdgeInset, end = 48.dp),
                ) {
                    items(count = section.items.size, key = { section.items[it].key }) { cardIndex ->
                        ResultCard(section.items[cardIndex], onOpen)
                    }
                }
            }
        }
    }
}

/** Spec §12.6. The card here carries a title, because the screen has no hero. */
@Composable
private fun ResultCard(result: SearchResult, onOpen: (SearchResult) -> Unit) {
    Column(Modifier.width(TvGeom.PosterWidth)) {
        TvFocusable(
            onClick = { onOpen(result) },
            accessibleLabel = accessibilityLabel(result.title, result.year?.toString(), SearchSections.label(result.type)),
            clickLabel = if (result.type.endsWith("-category")) "Browse category" else "Open result",
            cornerRadius = 12.dp,
        ) { _ ->
            TvArtwork(
                request = PosterRequest.poster(result.posterURL),
                title = result.title,
                modifier = Modifier.size(TvGeom.PosterWidth, TvGeom.PosterHeight),
            )
        }
        Spacer(Modifier.height(TvGeom.FocusLabelGap))
        Text(
            result.title,
            style = TvType.CardTitle,
            color = TvColor.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (result.year != null) {
            Text("${result.year}", style = TvType.Meta, color = TvColor.TextMuted, maxLines = 1)
        }
    }
}

/**
 * The 2 px strip that makes LEFT mean "open the rail" at the left edge of a screen with columns.
 *
 * The rail's own items are drawn under every top-level screen, so a plain LEFT would drop focus
 * into the collapsed rail by geometry and never open it. This sits nearer, takes the focus, opens
 * the rail and hands focus straight on. Consuming LEFT at the screen root is not an option here:
 * Compose runs its directional focus search after the key handlers, so a root that swallows LEFT
 * would stop the keyboard, the grid and the settings list from being walked at all.
 */
@Composable
internal fun RailEdge(onOpenRail: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .width(2.dp)
            .fillMaxHeight()
            .onFocusChanged { if (it.isFocused) onOpenRail() }
            .focusable(),
    )
}

/**
 * Every catalog that says it can answer a search, asked at once.
 *
 * Null means nothing could be asked or nothing answered at all — that is the error state. An
 * empty page list from add-ons that did answer is a real result and shows the empty state.
 */
private suspend fun searchEveryCatalog(
    services: ClientGraph.DataServices,
    query: String,
): List<List<DiscoverItem>>? {
    val catalogs = services.registry.catalogs()
        .filter { (_, catalog) ->
            catalog.extraSupported.any { it.equals(SearchSections.SEARCH_EXTRA, ignoreCase = true) }
        }
        .take(MAX_CATALOGS)
    if (catalogs.isEmpty()) return null
    val pages = withTimeoutOrNull(SET_DEADLINE_MILLIS) {
        coroutineScope {
            catalogs.map { (addon, catalog) ->
                async(Dispatchers.IO) {
                    withTimeoutOrNull(PER_CATALOG_MILLIS) {
                        runCatching {
                            services.client.catalog(
                                addon = addon,
                                type = catalog.type,
                                id = catalog.id,
                                extra = CatalogExtra(search = query),
                            ).items
                        }.getOrNull()
                    }
                }
            }.awaitAll()
        }
    } ?: return null
    val answered = pages.filterNotNull()
    return answered.ifEmpty { null }
}
