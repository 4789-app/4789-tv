package com.fourseveneightnine.tv.client.ui.screens.collections

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics
import com.fourseveneightnine.tv.client.ClientGraph
import com.fourseveneightnine.tv.client.appGraph
import com.fourseveneightnine.tv.client.data.library.CollectionDetail
import com.fourseveneightnine.tv.client.data.library.CollectionItem
import com.fourseveneightnine.tv.client.data.library.ContinueItem
import com.fourseveneightnine.tv.client.data.library.LibraryRepository
import com.fourseveneightnine.tv.client.data.library.SystemCollections
import com.fourseveneightnine.tv.client.home.LauncherRails
import com.fourseveneightnine.tv.client.playback.PlayRequest
import com.fourseveneightnine.tv.client.playback.PlayResult
import com.fourseveneightnine.tv.client.playback.WatchlistSource
import com.fourseveneightnine.tv.client.ui.components.ButtonKind
import com.fourseveneightnine.tv.client.ui.components.EmptyState
import com.fourseveneightnine.tv.client.ui.components.GridRowFocus
import com.fourseveneightnine.tv.client.ui.components.SidePanel
import com.fourseveneightnine.tv.client.ui.components.SidePanelRow
import com.fourseveneightnine.tv.client.ui.components.Skeleton
import com.fourseveneightnine.tv.client.ui.components.TvButton
import com.fourseveneightnine.tv.client.ui.components.TvSearchField
import com.fourseveneightnine.tv.client.ui.nav.ClientNav
import com.fourseveneightnine.tv.client.ui.screens.detail.DetailPreview
import com.fourseveneightnine.tv.client.ui.screens.discover.CatalogFilterPanel
import com.fourseveneightnine.tv.client.ui.screens.discover.CatalogFilters
import com.fourseveneightnine.tv.client.ui.screens.discover.CatalogHistory
import com.fourseveneightnine.tv.client.ui.screens.discover.CatalogSort
import com.fourseveneightnine.tv.client.ui.screens.discover.WatchlistFilterOptions
import com.fourseveneightnine.tv.client.ui.screens.discover.WatchlistFilterPage
import com.fourseveneightnine.tv.client.ui.screens.discover.catalogPreferenceSuffix
import com.fourseveneightnine.tv.client.ui.screens.discover.rememberCatalogBrowse
import com.fourseveneightnine.tv.client.ui.screens.discover.rememberCatalogFilters
import com.fourseveneightnine.tv.client.ui.screens.home.HomeCard
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvGeom
import com.fourseveneightnine.tv.client.ui.theme.TvType
import com.fourseveneightnine.tv.ui.throttleDpadRepeats
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

internal enum class WatchlistType(val label: String) {
    All("All"), Movies("Movies"), Series("Series"), Other("Other");

    companion object {
        fun of(type: String): WatchlistType = when (type) {
            "movie" -> Movies
            "series", "anime" -> Series
            else -> Other
        }
    }
}

internal enum class WatchlistDate(val label: String, val days: Long) {
    Any("Any time", 0), Week("Last 7 days", 7), Month("Last 30 days", 30);

    fun matches(added: Long, today: LocalDate, zone: ZoneId): Boolean =
        days == 0L || (added > 0L &&
            added >= today.minusDays(days - 1).atStartOfDay(zone).toInstant().toEpochMilli() &&
            added < today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
}

internal enum class WatchlistKind(val label: String) {
    All("All items"), Titles("Titles"), Sources("Saved sources");
}

internal const val LATEST_WATCHLIST_SORT = "latest-added"
internal const val OLDEST_WATCHLIST_SORT = "oldest-added"

private val sortLabels = linkedMapOf(
    LATEST_WATCHLIST_SORT to "Latest added",
    OLDEST_WATCHLIST_SORT to "Oldest added",
    CatalogSort.Title.name to "Title A–Z",
    CatalogSort.TitleDescending.name to "Title Z–A",
    CatalogSort.Original.name to "Catalog order",
) + CatalogSort.entries.filterNot { it in listOf(CatalogSort.Original, CatalogSort.Title, CatalogSort.TitleDescending) }
    .associate { it.name to when (it) {
        CatalogSort.Newest -> "Newest release year"
        CatalogSort.Oldest -> "Oldest release year"
        else -> it.label
    } }

internal fun sortWatchlist(items: List<CollectionItem>, sort: String): List<CollectionItem> {
    val stable = compareBy(CollectionItem::sortIndex, CollectionItem::canonicalId)
    val latest = compareBy<CollectionItem> { it.addedAtMillis <= 0 }
        .thenByDescending { it.addedAtMillis.takeIf { date -> date > 0 } }
    val comparator = when (sort) {
        OLDEST_WATCHLIST_SORT -> compareBy<CollectionItem> { it.addedAtMillis <= 0 }
            .thenBy { it.addedAtMillis.takeIf { date -> date > 0 } }
        LATEST_WATCHLIST_SORT -> latest
        CatalogSort.Title.name -> compareBy(String.CASE_INSENSITIVE_ORDER) { row: CollectionItem -> titleWithoutYear(row.title) }
            .then(latest)
        CatalogSort.TitleDescending.name -> compareByDescending(String.CASE_INSENSITIVE_ORDER) { row: CollectionItem -> titleWithoutYear(row.title) }
            .then(latest)
        else -> stable
    }
    return items.sortedWith(comparator.then(stable))
}

private val accentMarks = Regex("\\p{M}+")
internal fun watchlistSearchKey(text: String): String =
    Normalizer.normalize(text.trim(), Normalizer.Form.NFD).replace(accentMarks, "").lowercase(Locale.ROOT)

private val CollectionItem.isSource: Boolean
    get() = canonicalId.startsWith(WatchlistSource.PREFIX) || canonicalId.startsWith("saved-torbox:")

private data class WatchlistCard(
    val item: CollectionItem,
    val artwork: HomeCard,
    val source: WatchlistSource?,
    val history: ContinueItem?,
    val dateLabel: String,
) {
    val id get() = item.canonicalId
    val group get() = WatchlistType.of(item.mediaType)
    val candidate get() = CollectionCandidate(id, item.mediaType, artwork.title, artwork.posterUrl)
    val resume get() = history?.resumePositionMs
    val cell: CollectionCell get() = CollectionCell(
        id, item.mediaType, artwork.title, artwork.posterUrl, artwork.year,
        continuationLabel = listOfNotNull(
            if (item.isSource) "Saved source" else null,
            if (resume != null) "Resume" else null,
            (source?.season ?: history?.season)?.let { season ->
                (source?.episode ?: history?.episode)?.let { episode -> "S$season E$episode" }
            },
        ).joinToString(" · ").ifEmpty { null },
        progress = if (resume != null) history?.progressFraction ?: 0f else 0f,
    )
}

private data class WatchlistProjection(val groups: Map<WatchlistType, List<WatchlistCard>>) {
    val cards = groups.values.flatten()
    val positions = buildMap {
        var index = 0
        groups.values.forEach { group ->
            index++
            group.forEach { card -> put(card.id, index++) }
        }
    }
    val rows = groups.values.flatMap { it.chunked(CollectionGrid.COLUMNS) }
    val rowIndices = buildMap {
        rows.forEachIndexed { row, cards -> cards.forEachIndexed { col, card -> put(card.id, row to col) } }
    }
}

private enum class WatchlistPanel { Sort, Filters, More, Item, Remove }
private enum class WatchlistControl { All, Movies, Series, Sort, Filters, Search, ClearSearch, More, Done, Remove, Retry }

@Composable
internal fun WatchlistScreen(
    graph: ClientGraph,
    detail: CollectionDetail,
    nav: ClientNav,
    initialType: String?,
    readFailed: Boolean,
    placed: Boolean,
    retryRead: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val library = graph.activeLibrary
    val profile = graph.activeProfileId()
    val prefs = context.appGraph.presentationPreferences
    val preferenceKey = remember(profile) { "watchlist_v1_${catalogPreferenceSuffix(profile, SystemCollections.WATCHLIST_REF)}" }
    val legacy = rememberCatalogFilters(graph, "collection:${detail.id}") {
        val stored = prefs.getString("collection_filter_sort_${detail.id}", null)
        CatalogFilters(sort = CatalogSort.entries.firstOrNull { it.name == stored } ?: when (
            CollectionSort.read(prefs.getString(CollectionSort.key(detail.id), null))
        ) {
            SortMode.Added -> CatalogSort.Original
            SortMode.Title -> CatalogSort.Title
            SortMode.Year -> CatalogSort.Newest
        })
    }
    var filters by rememberCatalogFilters(graph, SystemCollections.WATCHLIST_REF) {
        legacy.value.copy(sort = CatalogSort.Original, mediaType = null)
    }
    var sort by remember(preferenceKey) { mutableStateOf(
        prefs.getString("${preferenceKey}_sort", null)?.takeIf(sortLabels::containsKey)
            ?: legacy.value.sort.takeUnless { it == CatalogSort.Original }?.name ?: LATEST_WATCHLIST_SORT,
    ) }
    var date by remember(preferenceKey) { mutableStateOf(
        WatchlistDate.entries.firstOrNull { it.name == prefs.getString("${preferenceKey}_date", null) } ?: WatchlistDate.Any,
    ) }
    var kind by remember(preferenceKey) { mutableStateOf(
        WatchlistKind.entries.firstOrNull { it.name == prefs.getString("${preferenceKey}_kind", null) } ?: WatchlistKind.All,
    ) }
    SideEffect {
        if (prefs.getString("${preferenceKey}_sort", null) != sort ||
            prefs.getString("${preferenceKey}_date", null) != date.name ||
            prefs.getString("${preferenceKey}_kind", null) != kind.name
        ) prefs.edit().putString("${preferenceKey}_sort", sort)
            .putString("${preferenceKey}_date", date.name).putString("${preferenceKey}_kind", kind.name).apply()
    }
    var type by rememberSaveable(initialType) { mutableStateOf(
        if (initialType == null) WatchlistType.All else WatchlistType.of(initialType),
    ) }
    var anime by rememberSaveable(initialType) { mutableStateOf(initialType == "anime") }
    var query by rememberSaveable { mutableStateOf("") }
    var focusedIds by rememberSaveable { mutableStateOf(ArrayList<String>().apply { repeat(3) { add("") } }) }
    var lastIndex by rememberSaveable { mutableStateOf(0) }
    var panel by rememberSaveable { mutableStateOf<WatchlistPanel?>(null) }
    var origin by rememberSaveable { mutableStateOf<WatchlistControl?>(null) }
    var itemId by rememberSaveable { mutableStateOf<String?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var adding by remember { mutableStateOf<CollectionCandidate?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selecting by remember { mutableStateOf(false) }
    var writing by remember { mutableStateOf(false) }
    var writeFailed by remember { mutableStateOf(false) }
    var afterRemove by remember { mutableStateOf<Set<String>>(emptySet()) }
    var focusCard by remember { mutableStateOf<Pair<Int, String?>?>(null) }
    var focusControl by remember { mutableStateOf<Pair<Int, WatchlistControl>?>(null) }
    var focusOrder by remember { mutableStateOf(0) }
    var gridOwnsFocus by remember { mutableStateOf(false) }
    val history by remember(library) { library.playbackHistory() }.collectAsState(initial = emptyList())
    val favoriteLists by remember(library) { library.savedCollections() }.collectAsState(initial = emptyList())
    val favorites = remember(favoriteLists) {
        favoriteLists.firstOrNull { it.sourceRef == SystemCollections.FAVORITES_REF }
    }
    val recipes by produceState(emptyMap<String, WatchlistSource>(), library, detail.items) {
        try {
            val stored = library.watchlistSources(detail.id)
            value = withContext(Dispatchers.Default) {
                stored.mapNotNull { (id, json) ->
                    runCatching { Json.decodeFromString<WatchlistSource>(json) }.getOrNull()?.let { id to it }
                }.toMap()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            value = emptyMap()
        }
    }
    val base by produceState(emptyList<HomeCard>(), detail.items, recipes) {
        value = withContext(Dispatchers.Default) {
            detail.items.map {
                HomeCard(it.canonicalId, it.mediaType, titleWithoutYear(it.title), posterUrl = it.posterUrl,
                    year = yearIn(it.title), posterLookupId = recipes[it.canonicalId]?.catalogId ?: it.canonicalId)
            }
        }
    }
    val lookupSort = CatalogSort.entries.firstOrNull { it.name == sort } ?: CatalogSort.Original
    val catalog = filters.copy(
        sort = if (lookupSort in listOf(CatalogSort.Title, CatalogSort.TitleDescending)) CatalogSort.Original else lookupSort,
        history = CatalogHistory.Any,
        mediaType = null,
    )
    val browse = rememberCatalogBrowse(base, graph, catalog,
        enabled = catalog.active || panel == WatchlistPanel.Filters, scopeKey = SystemCollections.WATCHLIST_REF, benchmarkSurface = "watchlist")
    val projection by produceState<WatchlistProjection?>(null, detail.items, browse.items, catalog, sort, history,
        type, anime, query, date, kind, recipes, filters.history, base) {
        if (base.isEmpty() && detail.items.isNotEmpty()) return@produceState
        val started = if (com.fourseveneightnine.tv.BuildConfig.BENCHMARK_LOGGING) System.nanoTime() else 0L
        ReceiverDiagnostics.benchmark("watchlist.projection.start") {
            "request=$started sort=${lookupSort.name} type=${type.name} date=${date.name} kind=${kind.name}"
        }
        value = withContext(Dispatchers.Default) {
            val items = detail.items.associateBy(CollectionItem::canonicalId)
            val progress = history.associateBy { it.mediaType to it.canonicalId }
            val artwork = browse.items.associateBy(HomeCard::id)
            val order = if (lookupSort !in listOf(CatalogSort.Original, CatalogSort.Title, CatalogSort.TitleDescending)) {
                browse.items.mapNotNull { items[it.id] }
            } else sortWatchlist(browse.items.mapNotNull { items[it.id] }, sort)
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(zone)
            val search = watchlistSearchKey(query)
            val grouped = order.mapNotNull { entry ->
                val id = entry.canonicalId
                val media = entry.mediaType
                val group = WatchlistType.of(media)
                if ((type != WatchlistType.All && group != type) || (anime && media != "anime")) return@mapNotNull null
                if (!date.matches(entry.addedAtMillis, today, zone)) return@mapNotNull null
                if ((kind == WatchlistKind.Sources && !entry.isSource) || (kind == WatchlistKind.Titles && entry.isSource)) return@mapNotNull null
                val card = artwork.getValue(id)
                if (search.isNotEmpty() && !watchlistSearchKey(card.title).contains(search)) return@mapNotNull null
                val source = recipes[id]
                val direct = progress[media to id] ?: progress[media to (source?.catalogId ?: id)]
                    ?: card.imdbID?.let { progress[media to it] }
                    ?: card.tmdbID?.let { progress[media to "tmdb:$it"] }
                val matching = direct?.takeIf { row ->
                    !entry.isSource || row.canonicalId == id || source?.let { it.season == row.season && it.episode == row.episode } == true
                }
                val finished = media == "movie" && (matching?.progressFraction ?: 0f) >= LibraryRepository.FINISHED_FRACTION
                val matches = when (filters.history) {
                    CatalogHistory.Any -> true
                    CatalogHistory.Unplayed -> matching == null
                    CatalogHistory.Continuing -> matching?.resumePositionMs != null
                    CatalogHistory.Finished -> finished
                }
                if (!matches) return@mapNotNull null
                val dateLabel = if (entry.addedAtMillis > 0L) "Added ${formatter.format(Instant.ofEpochMilli(entry.addedAtMillis))}"
                    else "Date unavailable"
                WatchlistCard(entry, card, source, matching, dateLabel)
            }.groupBy(WatchlistCard::group)
            WatchlistProjection(WatchlistType.entries.mapNotNull { group -> grouped[group]?.let { group to it } }.toMap())
        }
        ReceiverDiagnostics.benchmark("watchlist.projection.result") {
            "request=$started input=${detail.items.size} rows=${value?.cards?.size ?: 0} elapsedMs=${(System.nanoTime() - started) / 1_000_000}"
        }
    }
    val visible = projection?.cards.orEmpty()
    val current = visible.firstOrNull { it.id == itemId }
    val grid = rememberLazyGridState()
    val rows = remember(grid, scope) { GridRowFocus(grid, scope, CollectionGrid.COLUMNS) }
    val toolbar = remember { WatchlistControl.entries.associateWith { FocusRequester() } }
    val empty = remember { FocusRequester() }
    val mediaControl = when (type) {
        WatchlistType.Movies -> WatchlistControl.Movies
        WatchlistType.Series -> WatchlistControl.Series
        else -> WatchlistControl.All
    }
    var movingFocus by remember { mutableStateOf<Job?>(null) }
    fun poster(id: String? = null) { focusCard = ++focusOrder to id }
    fun control(target: WatchlistControl) { focusControl = ++focusOrder to target }
    fun restore() {
        val target = origin
        if (target != null) control(target) else poster(itemId)
    }
    fun close() {
        panel = null
        writeFailed = false
        restore()
    }
    fun open(which: WatchlistPanel, from: WatchlistControl? = null) {
        origin = from
        panel = which
    }
    fun clearFilters() {
        filters = CatalogFilters()
        date = WatchlistDate.Any
        kind = WatchlistKind.All
        anime = false
    }
    fun details(card: WatchlistCard) {
        val source = card.source
        val target = source?.catalogId ?: card.id
        DetailPreview.stage(card.artwork.copy(id = target))
        nav.openDetail(card.item.mediaType, target, source?.season, source?.episode)
    }
    fun play(card: WatchlistCard) {
        if (card.id.startsWith(WatchlistSource.PREFIX)) {
            nav.openDetail(card.item.mediaType, card.id)
            return
        }
        val progress = card.history
        if (!card.item.isSource && card.group == WatchlistType.Series && progress?.episode == null) {
            details(card)
            return
        }
        scope.launch {
            val request = PlayRequest(type = card.item.mediaType, id = card.id, title = card.artwork.title,
                season = progress?.season, episode = progress?.episode, posterUrl = card.artwork.posterUrl,
                resumeFromMs = card.resume)
            when (val result = graph.playFlow.play(request)) {
                PlayResult.Opened -> Unit
                PlayResult.ShowList -> nav.openStreams(request.type, request.id, request.season, request.episode)
                is PlayResult.Failed -> nav.toast(result.message)
            }
        }
    }
    fun remove() {
        if (writing || graph.activeProfileId() != profile) return
        val ids = if (selecting) selected else setOfNotNull(itemId)
        if (ids.isEmpty()) return
        writing = true
        scope.launch(Dispatchers.Main) {
            try {
                library.removeWatchlistItems(detail.id, ids)
                afterRemove = ids
                panel = null
                writeFailed = false
                selected = emptySet()
                selecting = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                writeFailed = true
            } finally {
                writing = false
            }
        }
    }
    fun doneSelecting() {
        selecting = false
        selected = emptySet()
        control(WatchlistControl.More)
    }
    BackHandler(enabled = adding == null && (panel != null && panel != WatchlistPanel.Filters || selecting)) {
        if (writing) return@BackHandler
        when {
            panel == WatchlistPanel.Remove && !selecting -> panel = WatchlistPanel.Item
            panel != null -> close()
            else -> doneSelecting()
        }
    }
    LaunchedEffect(focusControl) {
        val target = focusControl?.second ?: return@LaunchedEffect
        repeat(5) {
            withFrameNanos { }
            if (requestWatchlistFocus(toolbar.getValue(target))) return@LaunchedEffect
        }
    }
    var firstFocus by remember { mutableStateOf(true) }
    LaunchedEffect(placed, projection, focusCard, panel, searching, adding, afterRemove) {
        if (!placed) {
            firstFocus = true
            return@LaunchedEffect
        }
        if (projection == null || panel != null || searching || adding != null) return@LaunchedEffect
        val removed = afterRemove.isNotEmpty()
        if (removed && visible.any { it.id in afterRemove }) return@LaunchedEffect
        val last = focusedIds[type.ordinal]
        val lost = last.isNotEmpty() && projection?.positions?.containsKey(last) == false
        if (!firstFocus && focusCard == null && !removed && !(lost && gridOwnsFocus)) return@LaunchedEffect
        val requested = focusCard?.second
        val target = visible.firstOrNull { it.id == (requested ?: last) }
            ?: visible.getOrNull(if (removed || (lost && gridOwnsFocus)) lastIndex.coerceIn(0, (visible.size - 1).coerceAtLeast(0)) else 0)
        firstFocus = false
        val request = focusCard
        withFrameNanos { }
        if (target == null) requestWatchlistFocus(empty)
        else {
            val index = projection?.positions?.get(target.id) ?: return@LaunchedEffect
            if (grid.layoutInfo.visibleItemsInfo.none { it.key == target.id }) grid.scrollToItem(index)
            for (attempt in 1..5) {
                withFrameNanos { }
                if (rows.request(target.id)) break
            }
        }
        if (focusCard == request) focusCard = null
        if (removed) afterRemove = emptySet()
    }
    val filterCount = remember(filters, anime, date, kind) {
        val normalized = filters.copy(sort = CatalogSort.Original, mediaType = null,
            matchAllGenres = filters.includedGenres.isEmpty() || filters.matchAllGenres)
        Json.encodeToJsonElement(CatalogFilters.serializer(), normalized).jsonObject.size +
            listOf(anime, date != WatchlistDate.Any, kind != WatchlistKind.All).count { it }
    }
    val count = detail.items.size
    val typeIsEmpty = remember(detail.items, type) {
        type != WatchlistType.All && detail.items.none { WatchlistType.of(it.mediaType) == type }
    }
    val countLabel = if (type == WatchlistType.All && filterCount == 0 && query.isBlank()) {
        "$count saved ${if (count == 1) "item" else "items"}"
    } else "${visible.size} of $count saved items"

    DrillInFrame(modifier = Modifier.throttleDpadRepeats()) {
        val backgroundSemantics = if (panel != null || adding != null) {
            Modifier.semantics { hideFromAccessibility() }
        } else Modifier
        Column(Modifier.fillMaxSize().then(backgroundSemantics)) {
            Row(Modifier.width(CollectionGrid.BAND_WIDTH.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Watchlist", style = TvType.ScreenTitle, color = TvColor.TextPrimary,
                    modifier = Modifier.weight(1f).semantics { heading() })
                Text(countLabel, style = TvType.Body, color = TvColor.TextSecondary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            Row(
                Modifier.padding(top = 14.dp, bottom = 14.dp).onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                        poster()
                        true
                    } else false
                },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                fun Modifier.target(control: WatchlistControl) = focusRequester(toolbar.getValue(control)).onFocusChanged {
                    if (it.isFocused) { gridOwnsFocus = false; movingFocus?.cancel() }
                }
                if (selecting) {
                    TvButton("Done", ::doneSelecting, Modifier.target(WatchlistControl.Done), height = 60.dp)
                    TvButton("Remove selected", { open(WatchlistPanel.Remove, WatchlistControl.Remove) },
                        Modifier.target(WatchlistControl.Remove), enabled = selected.isNotEmpty(), height = 60.dp)
                    Text("${selected.size} selected", style = TvType.Body, color = TvColor.TextSecondary)
                } else {
                    listOf(WatchlistType.All, WatchlistType.Movies, WatchlistType.Series).forEach { option ->
                        val control = WatchlistControl.valueOf(option.name)
                        TvButton(option.label, {
                            type = option
                            if (option != WatchlistType.Series) anime = false
                        }, Modifier.target(control).semantics { this.selected = type == option },
                            kind = if (type == option) ButtonKind.Primary else ButtonKind.Secondary, height = 60.dp,
                            glyph = if (type == option) {
                                { Text("✓", style = TvType.ControlLabel, color = TvColor.OnAccent) }
                            } else null)
                    }
                    TvButton(sortLabels.getValue(sort), { open(WatchlistPanel.Sort, WatchlistControl.Sort) },
                        Modifier.target(WatchlistControl.Sort).padding(start = 12.dp), height = 60.dp)
                    TvButton(if (filterCount == 0) "Filters" else "Filters ($filterCount)",
                        { open(WatchlistPanel.Filters, WatchlistControl.Filters) }, Modifier.target(WatchlistControl.Filters), height = 60.dp)
                    TvButton(if (searching) "Close search" else "Search", { searching = !searching },
                        Modifier.target(WatchlistControl.Search), height = 60.dp)
                    if (!searching && query.isNotBlank()) TvButton("Clear search", { query = ""; control(WatchlistControl.Search) },
                        Modifier.target(WatchlistControl.ClearSearch), height = 60.dp)
                    TvButton("More", { open(WatchlistPanel.More, WatchlistControl.More) },
                        Modifier.target(WatchlistControl.More), height = 60.dp)
                }
            }
            if (searching && !selecting) TvSearchField("Search saved titles", query, { query = it },
                modifier = Modifier.width(560.dp), openKeyboard = true, enabled = panel == null && adding == null,
                onClose = { searching = false; control(WatchlistControl.Search) })
            if (anime) Text("Anime filter", style = TvType.Meta, color = TvColor.TextSecondary)
            if (readFailed) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Couldn't load Watchlist", style = TvType.Body, color = TvColor.Warning)
                TvButton("Retry", retryRead, Modifier.focusRequester(toolbar.getValue(WatchlistControl.Retry)))
            }
            val content = projection
            if (content == null || (count > 0 && base.isEmpty())) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    repeat(CollectionGrid.COLUMNS) { Skeleton(Modifier.size(TvGeom.PosterWidth, TvGeom.PosterHeight)) }
                }
            } else if (visible.isEmpty()) {
                val emptyModifier = Modifier.focusRequester(empty).onFocusChanged { if (it.isFocused) gridOwnsFocus = false }
                    .onPreviewKeyEvent { event ->
                        event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp &&
                            requestWatchlistFocus(toolbar.getValue(mediaControl))
                    }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when {
                        count == 0 -> EmptyState("Your Watchlist is empty", "Hold OK on a title in Discover and choose Watchlist.",
                            actionLabel = "Browse Discover", onAction = nav::openDiscover, actionModifier = emptyModifier)
                        typeIsEmpty -> EmptyState(if (type == WatchlistType.Movies) "No saved movies" else "No saved series",
                            "", actionLabel = "Show all", onAction = { type = WatchlistType.All; anime = false; control(WatchlistControl.All) }, actionModifier = emptyModifier)
                        query.isNotBlank() -> EmptyState("No matching items", "Try a different search.",
                            actionLabel = "Clear search", onAction = { query = ""; control(WatchlistControl.Search) }, actionModifier = emptyModifier)
                        else -> EmptyState("No matching items", "Try a different filter.",
                            actionLabel = "Clear filters", onAction = { clearFilters(); control(WatchlistControl.Filters) }, actionModifier = emptyModifier)
                    }
                }
            } else LazyVerticalGrid(
                columns = GridCells.Fixed(CollectionGrid.COLUMNS),
                state = grid,
                contentPadding = PaddingValues(bottom = TvGeom.SafeTop),
                horizontalArrangement = Arrangement.spacedBy(CollectionGrid.COLUMN_GAP.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.width(CollectionGrid.BAND_WIDTH.dp).onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || panel != null) return@onPreviewKeyEvent false
                    val id = focusedIds[type.ordinal]
                    val (row, col) = content.rowIndices[id] ?: return@onPreviewKeyEvent false
                    val target = when (event.key) {
                        Key.DirectionLeft -> if (col == 0) {
                            return@onPreviewKeyEvent requestWatchlistFocus(toolbar.getValue(
                                if (selecting) WatchlistControl.Done else WatchlistControl.Filters))
                        } else content.rows[row][col - 1]
                        Key.DirectionRight -> content.rows[row].getOrNull(col + 1)
                        Key.DirectionUp -> if (row == 0) {
                            return@onPreviewKeyEvent requestWatchlistFocus(toolbar.getValue(
                                if (selecting) WatchlistControl.Done else mediaControl))
                        } else content.rows[row - 1].let { it[col.coerceAtMost(it.lastIndex)] }
                        Key.DirectionDown -> content.rows.getOrNull(row + 1)?.let { it[col.coerceAtMost(it.lastIndex)] }
                        Key.Menu -> if (!selecting) {
                            itemId = id
                            open(WatchlistPanel.Item)
                            return@onPreviewKeyEvent true
                        } else return@onPreviewKeyEvent true
                        else -> return@onPreviewKeyEvent false
                    } ?: return@onPreviewKeyEvent true
                    if (movingFocus?.isActive == true) return@onPreviewKeyEvent true
                    if (!rows.request(target.id)) {
                        movingFocus = scope.launch {
                            grid.scrollToItem(content.positions.getValue(target.id))
                            repeat(5) {
                                withFrameNanos { }
                                if (rows.request(target.id)) return@launch
                            }
                        }
                    }
                    true
                },
            ) {
                content.groups.entries.forEachIndexed { index, (group, cards) ->
                    item(key = "watchlist-heading:${group.name}", span = { GridItemSpan(maxLineSpan) }) {
                        Text("${group.label} · ${cards.size}", style = TvType.data(28), color = TvColor.TextPrimary,
                            modifier = Modifier.fillMaxWidth().padding(top = if (index == 0) 8.dp else 24.dp)
                                .semantics { heading() })
                    }
                    items(cards.size, key = { cards[it].id }) { i ->
                        val card = cards[i]
                        val action = {
                            itemId = card.id
                            open(WatchlistPanel.Item)
                        }
                        PosterCell(card.cell, card.artwork, rows.card(card.id).onFocusChanged {
                            if (it.isFocused) {
                                gridOwnsFocus = true
                                if (focusedIds[type.ordinal] != card.id) {
                                    focusedIds = ArrayList(focusedIds).apply { set(type.ordinal, card.id) }
                                }
                                lastIndex = content.positions.getValue(card.id) -
                                    content.groups.entries.takeWhile { it.key != group }.size - 1
                            }
                        }, dimmed = false, picked = card.id in selected, onSelectPressed = {},
                            onClick = {
                                if (selecting) selected = if (card.id in selected) selected - card.id else selected + card.id
                                else if (card.item.isSource) play(card) else details(card)
                            }, onLongClick = if (selecting) null else action,
                            onPlay = { if (!selecting) play(card) }, addedLabel = card.dateLabel,
                            actionLabel = "Watchlist item actions", selectionMode = selecting,
                            playLabel = when {
                                selecting -> null
                                card.item.isSource -> "Play saved source"
                                card.resume != null -> "Resume"
                                else -> null
                            })
                    }
                }
            }
        }
    }
    when (panel) {
        WatchlistPanel.Sort -> WatchlistMenu("Sort by", sortLabels.entries.map { (key, label) ->
            WatchlistOption(label, key == sort) { sort = key; close() }
        }, ::close, sortLabels.keys.indexOf(sort))
        WatchlistPanel.Filters -> CatalogFilterPanel(
            filters, browse, { filters = it }, ::close, watchlistOptions = WatchlistFilterOptions(
                pages = listOfNotNull(
                    WatchlistFilterPage("Date added", WatchlistDate.entries.map { it.label }, date.ordinal) { date = WatchlistDate.entries[it] },
                    WatchlistFilterPage("Saved as", WatchlistKind.entries.map { it.label }, kind.ordinal) { kind = WatchlistKind.entries[it] },
                    if (type == WatchlistType.Series) WatchlistFilterPage("Series type", listOf("All series", "Anime"), if (anime) 1 else 0) { anime = it == 1 } else null,
                ), onClear = ::clearFilters,
            ),
        )
        WatchlistPanel.More -> WatchlistMenu("Watchlist", buildList {
            if (visible.isNotEmpty()) {
                add(WatchlistOption("Pick for me") {
                    val pool = visible.filter { it.id != focusedIds[type.ordinal] }.ifEmpty { visible }
                    panel = null
                    poster(pool.random().id)
                })
                add(WatchlistOption("Select items") {
                    panel = null
                    selecting = true
                    poster()
                })
            }
            add(WatchlistOption("Edit Watchlist") { panel = null; nav.openCollectionEditor(detail.id.toString()) })
            LauncherRails.collectionUri(context, detail.id)?.let { uri ->
                add(WatchlistOption("Add to Den TV Home") { close(); LauncherRails.pin(context, uri, nav::toast) })
            }
        }, ::close)
        WatchlistPanel.Item -> current?.let { card ->
            val canFavorite = !card.item.isSource
            val isFavorite = favorites?.items?.any { it.canonicalId == card.id && it.mediaType == card.item.mediaType } == true
            WatchlistMenu(card.artwork.title, buildList {
                if (!card.item.isSource || card.source != null) add(WatchlistOption("Open details") { panel = null; details(card) })
                if (card.item.isSource || card.resume != null) {
                    add(WatchlistOption(if (card.item.isSource) "Play saved source" else "Resume") { close(); play(card) })
                }
                if (canFavorite) add(WatchlistOption(if (isFavorite) "Remove from Favorites" else "Add to Favorites") {
                    close()
                    scope.launch(Dispatchers.Main) {
                        try {
                            if (graph.activeProfileId() != profile) return@launch
                            if (isFavorite) library.removeItem(favorites.id, card.id)
                            else library.saveToFavorites(card.id, card.item.mediaType, card.artwork.title, card.artwork.posterUrl)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            nav.toast("Favorites could not be updated. Try again.")
                        }
                    }
                })
                add(WatchlistOption("Add to another collection") { panel = null; adding = card.candidate })
                add(WatchlistOption("Remove from Watchlist") { panel = WatchlistPanel.Remove })
            }, ::close)
        }
        WatchlistPanel.Remove -> WatchlistMenu(
            when {
                writeFailed -> "Couldn't remove items"
                selecting -> "Remove ${selected.size} selected?"
                else -> "Remove ${current?.artwork?.title ?: "this item"}?"
            },
            listOf(
                WatchlistOption("Cancel") {
                    if (writing) return@WatchlistOption
                    writeFailed = false
                    if (selecting) close() else panel = WatchlistPanel.Item
                },
                WatchlistOption(if (writeFailed) "Retry" else "Remove", onPick = ::remove),
            ),
            { if (!writing) { if (selecting) close() else { writeFailed = false; panel = WatchlistPanel.Item } } },
        )
        null -> Unit
    }
    adding?.let { candidate -> AddToCollectionSheet(candidate, onDismiss = { adding = null; poster(itemId) }) }
}

private data class WatchlistOption(val label: String, val selected: Boolean = false, val onPick: () -> Unit)

@Composable
private fun WatchlistMenu(title: String, options: List<WatchlistOption>, close: () -> Unit, initial: Int = 0) {
    val focus = remember(title) { FocusRequester() }
    val state = rememberLazyListState(initial)
    SidePanel(header = title, onClose = close) {
        LazyColumn(state = state, modifier = Modifier.heightIn(max = 780.dp)) {
            items(options.size) { index ->
                val option = options[index]
                SidePanelRow(option.label, option.selected, option.onPick,
                    modifier = if (index == initial) Modifier.focusRequester(focus) else Modifier)
            }
        }
    }
    LaunchedEffect(title, initial) {
        state.scrollToItem(initial)
        repeat(5) {
            withFrameNanos { }
            if (requestWatchlistFocus(focus)) return@LaunchedEffect
        }
    }
}

private fun requestWatchlistFocus(requester: FocusRequester): Boolean =
    runCatching { requester.requestFocus() }.getOrDefault(false)
