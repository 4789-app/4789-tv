package com.fourseveneightnine.tv.client.ui.screens.discover

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics
import com.fourseveneightnine.tv.client.data.library.LibraryRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.catalog.TVLetterboxdClient
import com.fourseveneightnine.tv.client.ClientGraph
import com.fourseveneightnine.tv.client.appGraph
import com.fourseveneightnine.tv.client.ui.components.TvSearchField
import com.fourseveneightnine.tv.client.ui.components.SidePanel
import com.fourseveneightnine.tv.client.ui.components.SidePanelRow
import com.fourseveneightnine.tv.client.ui.screens.home.HomeCard
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvType
import java.time.Year
import java.security.MessageDigest
import java.util.UUID
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal val CatalogFiltersSaver = Saver<CatalogFilters, String>(
    save = { Json.encodeToString(CatalogFilters.serializer(), it) },
    restore = { Json.decodeFromString(CatalogFilters.serializer(), it) },
)

internal fun catalogPreferenceSuffix(profile: UUID?, key: String): String {
    // Provider catalog identities can include credentialed manifest URLs.
    val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        .joinToString("") { "%02x".format(it) }
    return "${profile}_$digest"
}

/** Keep the viewer's choices with the catalog, including when its route is popped. */
@Composable
internal fun rememberCatalogFilters(
    graph: ClientGraph,
    catalogKey: String?,
    initial: () -> CatalogFilters = { CatalogFilters() },
): androidx.compose.runtime.MutableState<CatalogFilters> {
    val preferences = LocalContext.current.appGraph.presentationPreferences
    val profile = graph.activeProfileId()
    val key = remember(profile, catalogKey) {
        catalogKey?.let { "catalog_filters_v1_${catalogPreferenceSuffix(profile, it)}" }
    }
    val state = remember(preferences, key) {
        val saved = key?.let { preferences.getString(it, null) }
        mutableStateOf(saved?.let { runCatching { Json.decodeFromString<CatalogFilters>(it) }.getOrNull() } ?: initial())
    }
    val filters = state.value
    val encoded = remember(filters) { Json.encodeToString(CatalogFilters.serializer(), filters) }
    SideEffect {
        if (key != null && preferences.getString(key, null) != encoded) preferences.edit().putString(key, encoded).apply()
    }
    return state
}

internal class CatalogBrowseResult(
    val all: List<HomeCard>, val items: List<HomeCard>, val pending: Int,
    val facets: Map<String, List<String>> = emptyMap(),
    val savedLists: List<Pair<Long, String>> = emptyList(),
    val updating: Boolean = false,
    internal val source: List<HomeCard>? = null,
    internal val query: CatalogFilters = CatalogFilters(),
    internal val index: CatalogFacetIndex? = null,
    internal val owner: Any? = null,
) {
    fun status(hasMore: Boolean = false): String = buildList {
        add("${items.size} of ${all.size} loaded titles")
        if (updating) add("Updating results")
        if (pending > 0) add("Metadata: $pending remaining")
        if (hasMore) add("More pages available")
    }.joinToString(" · ")
}

private class CatalogEnrichment(val original: HomeCard, val card: HomeCard)
private class CatalogLibraryFacts(
    val names: List<Pair<Long, String>> = emptyList(),
    val memberships: Map<String, Set<Long>> = emptyMap(),
    val history: Set<String> = emptySet(), val continuing: Set<String> = emptySet(), val finished: Set<String> = emptySet(),
)
private val emptyLibraryFacts = CatalogLibraryFacts()
private class IndexedCatalog(val source: List<HomeCard>, val index: CatalogFacetIndex, val library: CatalogLibraryFacts,
    val owner: LibraryRepository, val enriched: Map<String, CatalogEnrichment>)

/** One bounded catalog cache per navigation entry, retained while Detail is above it. */
private class CatalogBrowseSession(cards: List<HomeCard>, owner: LibraryRepository) {
    val enriched = mutableStateOf<Map<String, CatalogEnrichment>>(emptyMap(), referentialEqualityPolicy())
    val indexed = mutableStateOf<IndexedCatalog?>(null, referentialEqualityPolicy())
    val output = mutableStateOf(CatalogBrowseResult(cards, cards, 0, source = cards, owner = owner), referentialEqualityPolicy())
    var lastLibraryFacts = emptyLibraryFacts
}
private class CatalogBrowseMemory : ViewModel() {
    private var key: String? = null
    private var services: Any? = null
    private var owner: LibraryRepository? = null
    private var current: CatalogBrowseSession? = null
    fun session(scope: String, services: Any?, owner: LibraryRepository, cards: List<HomeCard>): CatalogBrowseSession {
        val previous = current
        if (previous != null && key == scope && this.services === services && this.owner === owner) return previous
        return CatalogBrowseSession(cards, owner).also {
            key = scope; this.services = services; this.owner = owner; current = it
        }
    }
}

/** Hydration, index maintenance and queries have separate lifetimes. A chip never restarts I/O. */
@Composable
internal fun rememberCatalogBrowse(
    cards: List<HomeCard>, graph: ClientGraph, filters: CatalogFilters, enabled: Boolean, scopeKey: String,
    allowNetworkMetadata: Boolean = true,
    benchmarkSurface: String = "catalog",
): CatalogBrowseResult {
    val services by graph.services.collectAsState()
    val library = graph.activeLibrary
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val memory: CatalogBrowseMemory = viewModel { CatalogBrowseMemory() }
    val session = remember(memory, scopeKey, services, library, cards) { memory.session(scopeKey, services, library, cards) }
    val libraryFlow = remember(library, enabled) {
        if (!enabled) flowOf(emptyLibraryFacts) else combine(library.savedCollections(), library.playbackHistory(), library.continueWatching()) { lists, history, continuing ->
            val memberships = HashMap<String, MutableSet<Long>>()
            lists.forEach { list -> list.items.forEach { item -> memberships.getOrPut("${item.mediaType}:${item.canonicalId}") { HashSet() }.add(list.id) } }
            CatalogLibraryFacts(lists.map { it.id to it.name }, memberships,
                history.mapTo(HashSet()) { "${it.mediaType}:${it.canonicalId}" },
                continuing.mapTo(HashSet()) { "${it.mediaType}:${it.canonicalId}" },
                history.filter { it.mediaType == "movie" && it.durationMs > 0 && it.progressFraction > LibraryRepository.FINISHED_FRACTION }
                    .mapTo(HashSet()) { "${it.mediaType}:${it.canonicalId}" })
        }.flowOn(Dispatchers.Default)
    }
    val localFacts by key(library, enabled) { libraryFlow.collectAsStateWithLifecycle(initialValue = session.lastLibraryFacts) }
    SideEffect { session.lastLibraryFacts = localFacts }
    var enriched by session.enriched
    var pending by remember { mutableStateOf(0) }
    var indexed by session.indexed
    var output by session.output

    LaunchedEffect(session, cards, services, enabled, lifecycle, allowNetworkMetadata) {
        if (!enabled || !allowNetworkMetadata) { pending = 0; return@LaunchedEffect }
        val repository = services?.meta ?: run { pending = 0; return@LaunchedEffect }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val previous = enriched
            val remaining = withContext(Dispatchers.Default) {
                val keys = cards.mapTo(HashSet()) { it.key }
                if (previous.keys.all(keys::contains)) previous else previous.filterKeys(keys::contains)
            }
            enriched = remaining
            val missing = withContext(Dispatchers.Default) { cards.filter { it.metadataLookupAllowed && it.key !in remaining } }
            pending = missing.size
            for (batch in missing.chunked(4)) {
                val previousBatch = enriched
                val found = withContext(Dispatchers.IO) {
                    val updates = coroutineScope {
                        batch.map { card -> async {
                            var base: com.fourseveneightnine.tv.client.data.meta.Meta? = null
                            var letterboxdIdentity = card.letterboxdIdentity
                            val meta = try {
                                withTimeoutOrNull(8_000) {
                                    val storedSource = if (card.id.startsWith(com.fourseveneightnine.tv.client.playback.WatchlistSource.PREFIX))
                                        library.savedSource(card.id) else null
                                    val providerId = catalogMetadataId(card.id, storedSource)
                                    val saved = graph.savedCatalogItem(card.type, providerId)
                                    val lookup = when {
                                        card.id.startsWith(TVLetterboxdClient.FILM_ID_PREFIX) ->
                                            graph.resolveLetterboxdIdentity(card)?.let {
                                                letterboxdIdentity = it
                                                it.type to it.id
                                            }
                                        else -> card.type to (saved?.imdbId ?: card.posterLookupId ?: saved?.tmdbId?.let { "tmdb:$it" }
                                            ?: providerId.substringAfterLast("::"))
                                    }
                                    lookup?.let { (type, id) -> repository.meta(type, id, card.tmdbID ?: saved?.tmdbId, onBase = { base = it }) }
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { null }
                            val details = (meta ?: base)?.let(card::withCatalogMetadata) ?: card
                            val resolved = letterboxdIdentity?.let { details.copy(letterboxdIdentity = it) } ?: details
                            card.key to CatalogEnrichment(card, resolved)
                        } }.awaitAll().toMap()
                    }
                    previousBatch + updates
                }
                enriched = found
                pending -= batch.size
            }
        }
    }

    LaunchedEffect(session, enabled, cards, enriched, localFacts, library) {
        if (!enabled) return@LaunchedEffect
        if (indexed?.let { it.source === cards && it.library === localFacts && it.enriched === enriched } == true) return@LaunchedEffect
        // Coalesce fast cache arrivals; filter queries have no debounce.
        if (indexed?.source === cards) kotlinx.coroutines.delay(48)
        val cache = enriched
        val facts = localFacts
        val started = if (com.fourseveneightnine.tv.BuildConfig.BENCHMARK_LOGGING) System.nanoTime() else 0L
        indexed = withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            val prepared = cards.mapIndexed { row, card ->
                if (row % 256 == 0) context.ensureActive()
                val entry = cache[card.key]
                // A refreshed source can replace artwork/title without being hidden by an older lookup.
                val known = if (entry == null) card else if (entry.original === card) entry.card else card.copy(
                    genres = (card.genres + entry.card.genres).distinct(), year = card.year ?: entry.card.year,
                    rating = card.rating ?: entry.card.rating, runtimeMinutes = card.runtimeMinutes ?: entry.card.runtimeMinutes,
                    originalLanguage = card.originalLanguage ?: entry.card.originalLanguage,
                    countries = entry.card.countries, castNames = entry.card.castNames, directors = entry.card.directors,
                    studios = entry.card.studios, keywords = entry.card.keywords, spokenLanguages = entry.card.spokenLanguages,
                    certification = entry.card.certification, releaseStatus = entry.card.releaseStatus, releaseDate = entry.card.releaseDate,
                    tmdbVotes = entry.card.tmdbVotes, popularity = entry.card.popularity, imdbRating = entry.card.imdbRating,
                    tmdbRating = entry.card.tmdbRating, hasTrailer = entry.card.hasTrailer,
                    imdbID = entry.card.imdbID, tmdbID = card.tmdbID ?: entry.card.tmdbID,
                    letterboxdIdentity = card.letterboxdIdentity ?: entry.card.letterboxdIdentity)
                val type = known.catalogMediaType
                val aliases = listOfNotNull(
                    known.key,
                    known.letterboxdIdentity?.let { "${it.type}:${it.id}" },
                    known.imdbID?.let { id -> type?.let { "$it:$id" } },
                    known.tmdbID?.let { id -> type?.let { "$it:tmdb:$id" } },
                )
                val lists = aliases.flatMap { facts.memberships[it].orEmpty() }.toSet()
                val recorded = aliases.any(facts.history::contains)
                val continuing = aliases.any(facts.continuing::contains)
                val finished = aliases.any(facts.finished::contains)
                if (known.collectionIds == lists && known.hasPlaybackHistory == recorded && known.continuing == continuing && known.finishedMovie == finished) known
                else known.copy(collectionIds = lists, hasPlaybackHistory = recorded, continuing = continuing, finishedMovie = finished)
            }
            IndexedCatalog(cards, CatalogFacetIndex(prepared) { context.ensureActive() }, facts, library, cache)
        }
        ReceiverDiagnostics.benchmark("catalog.index") {
            "screen=$benchmarkSurface input=${cards.size} elapsedMs=${(System.nanoTime() - started) / 1_000_000}"
        }
    }
    LaunchedEffect(session, enabled, indexed, filters) {
        if (!enabled) return@LaunchedEffect
        val current = indexed ?: return@LaunchedEffect
        val started = if (com.fourseveneightnine.tv.BuildConfig.BENCHMARK_LOGGING) System.nanoTime() else 0L
        ReceiverDiagnostics.benchmark("catalog.projection.start") {
            "screen=$benchmarkSurface request=$started sort=${filters.sort.name} filtersActive=${filters.active}"
        }
        output = withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            val matches = current.index.query(filters) { context.ensureActive() }
            CatalogBrowseResult(current.index.cards, matches, 0, current.index.options, current.library.names,
                source = current.source, query = filters, index = current.index, owner = current.owner)
        }
        ReceiverDiagnostics.benchmark("catalog.projection.result") {
            "screen=$benchmarkSurface request=$started input=${current.index.cards.size} rows=${output.items.size} sort=${filters.sort.name} " +
                "elapsedMs=${(System.nanoTime() - started) / 1_000_000}"
        }
    }
    if (!enabled) {
        return remember(cards, indexed, library) {
            val ready = indexed?.takeIf { it.source === cards && it.owner === library }?.index?.cards ?: cards
            CatalogBrowseResult(ready, ready, 0, source = cards, owner = library)
        }
    }
    val appended = remember(output, cards, library) {
        val previous = output.source
        output.owner === library && previous != null && previous.isNotEmpty() &&
            previous.size < cards.size && cards.subList(0, previous.size) == previous
    }
    return remember(output, pending, cards, indexed, filters, localFacts, library, appended) {
        val sameSource = output.source === cards && output.owner === library
        val keepMatches = sameSource || (appended && output.query == filters)
        CatalogBrowseResult(if (sameSource) output.all else cards,
            if (keepMatches) output.items else if (!filters.active) cards else emptyList(), pending,
            if (keepMatches) output.facets else emptyMap(), localFacts.names,
            updating = !sameSource || output.query != filters || indexed?.index !== output.index,
            source = cards, query = output.query, index = output.index, owner = library)
    }
}

private class FacetMenu(val field: String, val selected: String?, val pick: (String?) -> Unit)
private data class FilterRow(val key: String, val label: String, val selected: Boolean = false, val pick: () -> Unit)

internal data class WatchlistFilterPage(
    val title: String,
    val choices: List<String>,
    val selected: Int,
    val onPick: (Int) -> Unit,
)

internal data class WatchlistFilterOptions(val pages: List<WatchlistFilterPage>, val onClear: () -> Unit)

@Composable
internal fun CatalogFilterPanel(
    filters: CatalogFilters,
    browse: CatalogBrowseResult,
    onChange: (CatalogFilters) -> Unit,
    onClose: () -> Unit,
    hasMore: Boolean = false,
    loadingMore: Boolean = false,
    onLoadMore: () -> Unit = {},
    providerGenres: List<String> = emptyList(),
    providerGenre: String? = null,
    onProviderGenre: ((String?) -> Unit)? = null,
    allowAllProviderGenres: Boolean = true,
    active: Boolean = true,
    watchlistOptions: WatchlistFilterOptions? = null,
) {
    val root = if (watchlistOptions == null) "Filters & sort" else "Watchlist filters"
    var sections by remember { mutableStateOf(listOf(root)) }
    val section = sections.last()
    var searchQuery by remember(section) { mutableStateOf("") }
    fun close() {
        when {
            sections.size > 1 -> sections = sections.dropLast(1)
            else -> onClose()
        }
    }
    val facet = when (section) {
        "Original language" -> FacetMenu("language", filters.language) { onChange(filters.copy(language = it)) }
        "Spoken language" -> FacetMenu("spoken", filters.spokenLanguage) { onChange(filters.copy(spokenLanguage = it)) }
        "Country" -> FacetMenu("country", filters.country) { onChange(filters.copy(country = it)) }
        "Cast" -> FacetMenu("actor", filters.actor) { onChange(filters.copy(actor = it)) }
        "Director / creator" -> FacetMenu("director", filters.director) { onChange(filters.copy(director = it)) }
        "Studio" -> FacetMenu("studio", filters.studio) { onChange(filters.copy(studio = it)) }
        "Keyword" -> FacetMenu("keyword", filters.keyword) { onChange(filters.copy(keyword = it)) }
        "Age rating" -> FacetMenu("certification", filters.certification) { onChange(filters.copy(certification = it)) }
        "Release status" -> FacetMenu("status", filters.releaseStatus) { onChange(filters.copy(releaseStatus = it)) }
        else -> null
    }
    val years = remember(browse.facets) {
        (browse.facets["year"].orEmpty().mapNotNull(String::toIntOrNull) + (Year.now().value + 3 downTo 1870)).distinct().sortedDescending()
    }
    val rows = if (facet != null) emptyList() else buildList {
        fun row(key: String, label: String, selected: Boolean = false, pick: () -> Unit) {
            add(FilterRow(key, label, selected, pick))
        }
        fun page(label: String) = row(label, "$label ▸") { sections = sections + label }
        when (section) {
            root -> {
                row("done", "Show results") { onClose() }
                row("clear", if (watchlistOptions == null) "Reset filters & sort" else "Clear filters") {
                    onChange(CatalogFilters())
                    onProviderGenre?.invoke(null)
                    watchlistOptions?.onClear?.invoke()
                }
                if (onProviderGenre != null && providerGenres.isNotEmpty()) page("Catalog category")
                if (watchlistOptions == null) page("Sort") else {
                    watchlistOptions.pages.forEach { page(it.title) }
                    page("Playback history")
                }
                page("Year"); page("Decade")
                page("Include genres"); page("Exclude genres")
                row("match", if (filters.matchAllGenres) "Genres: match all selected" else "Genres: match any selected") {
                    onChange(filters.copy(matchAllGenres = !filters.matchAllGenres))
                }
                page("More filters"); page("Saved list"); if (watchlistOptions == null) page("Playback history")
                row("trailer", "Has a trailer", filters.withTrailer) { onChange(filters.copy(withTrailer = !filters.withTrailer)) }
                if (hasMore) row("more", if (loadingMore) "Loading more…" else "Load next catalog page") { if (!loadingMore) onLoadMore() }
            }
            "More filters" -> {
                page("Languages & country"); page("People & studios"); page("Ratings"); page("Runtime filters")
                page("Year range"); if (watchlistOptions == null) page("Media type"); page("Release status"); page("Keyword"); page("Age rating")
            }
            "Languages & country" -> { page("Original language"); page("Spoken language"); page("Country") }
            "People & studios" -> { page("Cast"); page("Director / creator"); page("Studio") }
            "Ratings" -> { page("Minimum rating"); page("Maximum rating"); page("Rating source"); page("Minimum votes") }
            "Runtime filters" -> { page("Runtime"); page("Runtime from"); page("Runtime to") }
            "Year range" -> { page("Year from"); page("Year to") }
            "Catalog category" -> {
                if (allowAllProviderGenres) row("all", "All categories", providerGenre == null) { onProviderGenre?.invoke(null) }
                providerGenres.forEach { value -> row(value, value, providerGenre == value) { onProviderGenre?.invoke(value) } }
            }
            "Sort" -> CatalogSort.entries.forEach { value -> row(value.name, value.label, filters.sort == value) { onChange(filters.copy(sort = value)) } }
            "Year" -> {
                row("all", "All years", filters.year == null) { onChange(filters.copy(year = null)) }
                years.forEach { value -> row("$value", "$value", filters.year == value) { onChange(filters.copy(year = value, decade = null, yearFrom = null, yearTo = null)) } }
            }
            "Decade" -> {
                row("all", "All decades", filters.decade == null) { onChange(filters.copy(decade = null)) }
                years.map { it / 10 * 10 }.distinct().forEach { value -> row("$value", "${value}s", filters.decade == value) { onChange(filters.copy(decade = value, year = null, yearFrom = null, yearTo = null)) } }
            }
            "Include genres", "Exclude genres" -> {
                val include = section == "Include genres"
                row("all", if (include) "Clear included genres" else "Clear excluded genres") {
                    onChange(if (include) filters.copy(includedGenres = emptySet()) else filters.copy(excludedGenres = emptySet()))
                }
                val genres = catalogGenres(browse.facets["genre"].orEmpty() +
                    filters.includedGenres + filters.excludedGenres + listOf("Action", "Adventure", "Animation", "Comedy", "Crime", "Documentary", "Drama", "Family", "Fantasy", "History", "Horror", "Music", "Mystery", "Romance", "Science Fiction", "Thriller", "War", "Western"))
                genres.sorted().forEach { value -> row(value, value, value in if (include) filters.includedGenres else filters.excludedGenres) {
                    onChange(if (include) filters.include(value) else filters.exclude(value))
                } }
            }
            "Minimum rating" -> {
                row("all", "Any rating", filters.minimumRating == null) { onChange(filters.copy(minimumRating = null)) }
                (1..9).forEach { value -> row("$value", "$value / 10 or higher", filters.minimumRating == value) { onChange(filters.copy(minimumRating = value, maximumRating = filters.maximumRating?.coerceAtLeast(value))) } }
            }
            "Runtime" -> CatalogRuntime.entries.forEach { value -> row(value.name, value.label, filters.runtime == value) { onChange(filters.copy(runtime = value, runtimeFrom = null, runtimeTo = null)) } }
            "Year from", "Year to" -> {
                val from = section == "Year from"
                val selected = if (from) filters.yearFrom else filters.yearTo
                row("all", if (from) "No earliest year" else "No latest year", selected == null) {
                    onChange(if (from) filters.copy(yearFrom = null) else filters.copy(yearTo = null))
                }
                years.forEach { value -> row("$value", "$value", selected == value) {
                    onChange(if (from) filters.copy(yearFrom = value, yearTo = filters.yearTo?.coerceAtLeast(value), year = null, decade = null)
                        else filters.copy(yearTo = value, yearFrom = filters.yearFrom?.coerceAtMost(value), year = null, decade = null))
                } }
            }
            "Maximum rating" -> {
                row("all", "No maximum rating", filters.maximumRating == null) { onChange(filters.copy(maximumRating = null)) }
                (1..10).forEach { value -> row("$value", "$value / 10 or lower", filters.maximumRating == value) {
                    onChange(filters.copy(maximumRating = value, minimumRating = filters.minimumRating?.coerceAtMost(value)))
                } }
            }
            "Rating source" -> CatalogRatingSource.entries.forEach { value -> row(value.name, value.label, filters.ratingSource == value) { onChange(filters.copy(ratingSource = value)) } }
            "Runtime from", "Runtime to" -> {
                val from = section == "Runtime from"
                val selected = if (from) filters.runtimeFrom else filters.runtimeTo
                row("all", if (from) "No minimum runtime" else "No maximum runtime", selected == null) {
                    onChange(if (from) filters.copy(runtimeFrom = null) else filters.copy(runtimeTo = null))
                }
                listOf(15, 30, 45, 60, 90, 120, 150, 180, 240, 300).forEach { value -> row("$value", "$value minutes", selected == value) {
                    onChange(if (from) filters.copy(runtimeFrom = value, runtimeTo = filters.runtimeTo?.coerceAtLeast(value), runtime = CatalogRuntime.Any)
                        else filters.copy(runtimeTo = value, runtimeFrom = filters.runtimeFrom?.coerceAtMost(value), runtime = CatalogRuntime.Any))
                } }
            }
            "Media type" -> {
                row("all", "All media types", filters.mediaType == null) { onChange(filters.copy(mediaType = null)) }
                DiscoverType.entries.forEach { type -> row(type.wire, type.label, filters.mediaType == type.wire) { onChange(filters.copy(mediaType = type.wire)) } }
            }
            "Minimum votes" -> {
                row("all", "Any vote count", filters.minimumVotes == null) { onChange(filters.copy(minimumVotes = null)) }
                listOf(10, 50, 100, 500, 1000, 5000, 10000, 50000).forEach { value -> row("$value", "$value TMDB votes or more", filters.minimumVotes == value) { onChange(filters.copy(minimumVotes = value)) } }
            }
            "Saved list" -> {
                row("all", "All titles", filters.collectionId == null) { onChange(filters.copy(collectionId = null)) }
                browse.savedLists.forEach { (id, name) -> row("$id", name, filters.collectionId == id) { onChange(filters.copy(collectionId = id)) } }
            }
            "Playback history" -> CatalogHistory.entries.forEach { value -> row(value.name, value.label, filters.history == value) { onChange(filters.copy(history = value)) } }
            else -> watchlistOptions?.pages?.firstOrNull { it.title == section }?.let { page ->
                page.choices.forEachIndexed { index, label ->
                    row(index.toString(), label, index == page.selected) { page.onPick(index) }
                }
            }

        }
    }
    val facetOptions = facet?.let { browse.facets[it.field] }.orEmpty()
    var searchedOptions by remember(section, browse.index) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(section, browse.index, searchQuery) {
        if (searchQuery.isNotBlank()) searchedOptions = withContext(Dispatchers.Default) {
            facetOptions.filter { it.contains(searchQuery.trim(), ignoreCase = true) }
        }
    }
    val visibleOptions = if (searchQuery.isBlank()) facetOptions else searchedOptions
    val first = remember(section) { FocusRequester() }
    val listState = rememberLazyListState()
    val showSearch = facetOptions.size > 20 || searchQuery.isNotEmpty()
    SidePanel(header = section, onClose = ::close) {
        Text(browse.status(hasMore), style = TvType.Meta, color = TvColor.TextSecondary)
        Text(if (hasMore) "Filters and sort cover loaded pages. Load more to search further." else "Titles with missing filter data do not match that filter.",
            style = TvType.Meta, color = TvColor.TextMuted)
        Spacer(Modifier.height(16.dp))
        if (showSearch) {
            TvSearchField("Search ${section.lowercase(Locale.ROOT)}", searchQuery,
                onQuery = { searchQuery = it }, modifier = Modifier.fillMaxWidth(), focus = first)
            Spacer(Modifier.height(12.dp))
        }
        LazyColumn(state = listState, modifier = Modifier.heightIn(max = 730.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (facet != null) {
                item(key = "clear", contentType = "filterChoice") {
                    SidePanelRow("Any", facet.selected == null, { facet.pick(null) },
                        modifier = if (showSearch) Modifier else Modifier.focusRequester(first))
                }
                items(visibleOptions, key = { "$section:$it" }, contentType = { "filterChoice" }) { value ->
                    val label = if (facet.field == "language" || facet.field == "spoken")
                        com.fourseveneightnine.tv.client.data.streams.OriginalAudioLanguage.displayName(value) else value
                    SidePanelRow(label, facet.selected == value, { facet.pick(value) })
                }
            } else itemsIndexed(rows, key = { _, row -> "$section:${row.key}" }, contentType = { _, _ -> "filterChoice" }) { index, row ->
                SidePanelRow(row.label, row.selected, row.pick, modifier = if (index == 0) Modifier.focusRequester(first) else Modifier)
            }
        }
    }
    BackHandler(enabled = active, onBack = ::close)
    LaunchedEffect(section, searchQuery) { listState.scrollToItem(0) }
    LaunchedEffect(section) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
}
