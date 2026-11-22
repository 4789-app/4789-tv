package com.fourseveneightnine.tv.client.ui.screens.search

import com.fourseveneightnine.contract.DiscoverItem
import com.fourseveneightnine.tv.client.data.catalog.CatalogItem
import com.fourseveneightnine.tv.client.data.catalog.Shelf
import com.fourseveneightnine.tv.client.data.catalog.ShelfKind
import com.fourseveneightnine.tv.client.iptv.IptvState
import com.fourseveneightnine.tv.client.iptv.IptvIndex
import com.fourseveneightnine.tv.client.iptv.IptvChannel
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/** One card in a results row, spec §12.6: poster, title, year, type. */
internal data class SearchResult(
    val id: String,
    val type: String,
    val title: String,
    val year: Int?,
    val posterURL: String?,
    val chartPosition: Int? = null,
) {
    /** Stable `key()` for the row, so a card that survives a new query keeps its focus. */
    val key: String get() = "$type/$id"
}

/** One labelled row of results. A section with nothing in it is never built. */
internal data class SearchSection(val type: String, val title: String, val items: List<SearchResult>)

/**
 * What the Search screen does with add-on answers, spec §12.
 *
 * The screen owns network requests. The matching, merge and recents can run on the JVM.
 */
internal object SearchSections {

    /** A query under two characters is not sent (spec §12.7.7). */
    const val MIN_QUERY_LENGTH = 2

    /** Typing is held for this long before a search runs (spec §12.3). */
    const val DEBOUNCE_MILLIS = 250L

    /** Recent searches are kept on the TV, capped at five (spec §12.7.6). */
    const val RECENT_CAP = 5

    /** More than this per row is scrolling no viewer does with a D-pad. */
    const val MAX_PER_SECTION = 24

    /** The extra key a catalog must declare for the TV to send it a search. */
    const val SEARCH_EXTRA = "search"

    private val SECTION_ORDER = listOf("channel", "iptv-channel-category", "iptv-program", "movie", "series",
        "iptv-movie", "iptv-movie-category", "iptv-series", "iptv-series-category", "anime")

    private const val TYPO_MATCH = 6

    private fun isIptv(type: String): Boolean = type == "channel" || type.startsWith("iptv-")

    private fun railType(type: String): String = when {
        isIptv(type) -> "iptv"
        type == "movie" -> "movie"
        else -> "series"
    }

    private sealed interface Answer {
        data class Local(val items: List<SearchResult>?) : Answer
        data class Iptv(val items: List<SearchResult>?) : Answer
        data class Remote(val page: IndexedValue<List<DiscoverItem>>) : Answer
    }

    /** Preview each rail as soon as a source supplies a title match. */
    suspend fun progressiveResults(
        query: String,
        local: suspend () -> List<SearchResult>?,
        remote: Flow<IndexedValue<List<DiscoverItem>>>,
        iptv: suspend () -> List<SearchResult>? = { null },
        retained: List<SearchResult> = emptyList(),
        publish: suspend (List<SearchSection>) -> Unit,
    ): Boolean {
        val pages = sortedMapOf<Int, List<DiscoverItem>>()
        var cached: List<SearchResult>? = null
        var live: List<SearchResult>? = null
        var preview = emptyList<SearchSection>()
        var previewLeads = emptyMap<String, RankedTitle>()
        val matcher = TitleQuery(query)
        fun canPreview(result: SearchResult, allowTypo: Boolean): Boolean {
            val previous = previewLeads[railType(result.type)]
            if (isIptv(result.type)) return previous == null
            val candidate = matcher.ranked(result)
            val limit = if (allowTypo) TYPO_MATCH else TYPO_MATCH - 1
            return candidate.rank <= limit && (previous == null || titleOrder.compare(candidate, previous) < 0)
        }
        merge(
            flow<Answer> { emit(Answer.Local(local())) },
            flow<Answer> { emit(Answer.Iptv(iptv())) },
            remote.map { Answer.Remote(it) },
        ).collect { answer ->
            val canPreview = when (answer) {
                is Answer.Local -> {
                    cached = answer.items
                    answer.items.orEmpty().any { canPreview(it, allowTypo = true) }
                }
                is Answer.Iptv -> {
                    live = answer.items
                    "iptv" !in previewLeads && !live.isNullOrEmpty()
                }
                is Answer.Remote -> {
                    pages[answer.page.index] = answer.page.value
                    answer.page.value.any { item ->
                        item.searchResult()?.let { canPreview(it, matcher.isMultiword) } == true
                    }
                }
            }
            if (canPreview) {
                // Provider-only guesses wait for the completed search.
                val next = merge(pages.values.toList(), cached.orEmpty() + live.orEmpty() + retained, query)
                    .mapNotNull { section ->
                        if (isIptv(section.type)) section
                        else section.items.filter { matcher.matches(it.title, it.year) }
                            .takeIf { it.isNotEmpty() }?.let { section.copy(items = it) }
                    }
                if (next.isNotEmpty() && next != preview) {
                    preview = next
                    previewLeads = resultRails(preview, query).associate { it.type to matcher.ranked(it.items.first()) }
                    publish(preview)
                }
            }
        }
        if (pages.isEmpty() && cached == null && live == null && retained.isEmpty()) return false
        val combined = merge(pages.values.toList(), cached.orEmpty() + live.orEmpty() + retained, query)
        if (combined.isEmpty() || combined != preview) publish(combined)
        return true
    }

    suspend fun iptv(state: IptvState, query: String, now: Long = System.currentTimeMillis()): List<SearchResult> {
        val matcher = TitleQuery(query)
        if (!shouldSearch(query) || matcher.tokens.isEmpty()) return emptyList()
        val enabled = state.sources.filter { it.enabled }.map { it.id }.toSet()
        val blocked = state.accounts.lockedGroups + state.accounts.hiddenGroups
        val channels = state.channels.filterNot { it.group in blocked }
        val byId = channels.associateBy(IptvChannel::id)
        val matches = IptvIndex.search(channels, state.catalog.programs, query,
            limit = MAX_PER_SECTION, now = now, groupOrder = state.orderedGroups).map {
            SearchResult(it.id, "channel", it.name, null, it.logo)
        }
        val programs = state.catalog.programs.asSequence().filter { program ->
            program.channelKey in byId && program.endMillis > now &&
                program.title.contains(query, ignoreCase = true)
        }.take(MAX_PER_SECTION).map { program ->
            val channel = byId.getValue(program.channelKey)
            SearchResult("${channel.id}:${program.startMillis}", "iptv-program",
                "${program.title} · ${channel.name}", null, channel.logo)
        }.toList()
        val groups = channels.asSequence().filter { matcher.couldMatch(it.group) }
            .groupingBy(IptvChannel::group).eachCount().entries.take(MAX_PER_SECTION)
            .map { SearchResult(it.key, "iptv-channel-category", "${it.key} · ${it.value} channels", null, null) }
        val bestVideos = LinkedHashMap<String, MutableList<RankedTitle>>()
        val comparator = compareBy<RankedTitle> { it.rank }
        val counts = LinkedHashMap<Pair<String, String>, Int>()
        val matchingCategories = HashMap<String, Boolean>()
        for ((index, item) in state.catalog.vod.withIndex()) {
            if (index % 256 == 0) currentCoroutineContext().ensureActive()
            if (item.sourceId !in enabled || item.category in blocked) continue
            if (matchingCategories.getOrPut(item.category) { matcher.couldMatch(item.category) }) {
                val key = item.kind to item.category
                counts[key] = (counts[key] ?: 0) + 1
            }
            if (!matcher.couldMatch(item.title)) continue
            val title = normalized(item.title)
            val rank = matcher.rank(title, null) ?: continue
            val best = bestVideos.getOrPut(item.kind) { ArrayList(MAX_PER_SECTION + 1) }
            keepBest(best, RankedTitle(rank, title,
                SearchResult(item.id, "iptv-${item.kind}", item.title, null, item.image)), comparator)
        }
        val kinds = listOf("movie", "series")
        val videos = kinds.flatMap { kind -> bestVideos[kind].orEmpty().map(RankedTitle::result) }
        state.catalog.vodCategories
            .filter { it.sourceId in enabled && it.name !in blocked && matcher.couldMatch(it.name) }
            .groupBy { it.kind to it.name }.forEach { (key, rows) ->
                counts[key] = maxOf(counts[key] ?: 0, rows.sumOf { it.count })
            }
        val categories = kinds.flatMap { kind ->
            counts.entries.asSequence().filter { it.key.first == kind }.take(MAX_PER_SECTION)
                .map { (key, count) ->
                    SearchResult(key.second, "iptv-$kind-category", "${key.second} · $count $kind titles", null, null)
                }.toList()
        }
        return matches + programs + groups + videos + categories
    }

    fun shouldSearch(query: String): Boolean =
        query.trim().length >= MIN_QUERY_LENGTH && query.any(Char::isLetterOrDigit)

    /** Reuse catalog titles that still match the edited query. IPTV visibility is checked afresh. */
    fun refineTitles(sections: List<SearchSection>, query: String): List<SearchSection> {
        val matcher = TitleQuery(query)
        return sections.filterNot { isIptv(it.type) }.mapNotNull { section ->
            val matches = section.items.filter { matcher.matches(it.title, it.year) }
            matches.takeIf { it.isNotEmpty() }?.let { section.copy(items = it) }
        }
    }

    /**
     * Folds every add-on's page into rows, in the spec's order.
     *
     * Add-on order breaks ties for the same `type/id`. Cached copies can supply stronger title
     * metadata and chart position (spec §12.7.3).
     */
    fun merge(
        pages: List<List<DiscoverItem>>,
        local: List<SearchResult> = emptyList(),
        query: String = "",
    ): List<SearchSection> {
        val matcher = TitleQuery(query)
        val byKey = LinkedHashMap<String, SearchResult>()
        for (page in pages) {
            for (item in page) {
                if (item.id.isBlank() || item.title.isBlank()) continue
                val result = item.searchResult() ?: continue
                byKey.putIfAbsent(result.key, result)
            }
        }
        local.forEach { candidate ->
            if (candidate.id.isBlank() || candidate.title.isBlank()) return@forEach
            val type = canonicalType(candidate.type) ?: return@forEach
            val result = candidate.copy(type = type)
            val previous = byKey[result.key]
            byKey[result.key] = if (previous == null) result else {
                val best = if (matcher.ranked(result).rank < matcher.ranked(previous).rank) result else previous
                best.copy(
                    year = best.year ?: previous.year ?: result.year,
                    posterURL = best.posterURL ?: previous.posterURL ?: result.posterURL,
                    chartPosition = previous.chartPosition ?: result.chartPosition,
                )
            }
        }
        val byType = byKey.values.groupBy(SearchResult::type)
        return (SECTION_ORDER + byType.keys.filterNot(SECTION_ORDER::contains)).mapNotNull { type ->
            val items = byType[type] ?: return@mapNotNull null
            val ranked = if (matcher.tokens.isEmpty() || isIptv(type)) items
                else items.map(matcher::ranked).sortedWith(titleOrder).map(RankedTitle::result)
            SearchSection(type, label(type), ranked.take(MAX_PER_SECTION))
        }
    }

    private fun DiscoverItem.searchResult(): SearchResult? =
        canonicalType(type)?.let { SearchResult(id, it, title, year, posterURL) }

    internal data class IndexedTitle(
        val item: CatalogItem,
        val type: String,
        val title: String,
        val titleWithoutArticles: String,
        val genres: String,
        val chartPosition: Int?,
    )

    /** Normalize each unique title once for this snapshot. */
    suspend fun indexCatalog(shelves: List<Shelf>): List<IndexedTitle> {
        val seen = HashSet<String>()
        val genres = HashMap<List<String>, String>()
        val indexed = ArrayList<IndexedTitle>()
        val chart = trending(shelves).firstOrNull()?.items.orEmpty()
        val chartPositions = chart.withIndex().associate { it.value.key to it.index }
        val chartAliases = chart.withIndex().associate { catalogDisplayKey(it.value) to it.index }
        var scanned = 0
        for (shelf in shelves) {
            for (item in shelf.items) {
                if (++scanned % 256 == 0) currentCoroutineContext().ensureActive()
                val type = canonicalType(item.mediaType) ?: continue
                if (item.canonicalId.isBlank() || item.title.isBlank() ||
                    !seen.add("$type/${item.canonicalId}")) continue
                val result = SearchResult(item.canonicalId, type, item.title, item.year, item.posterUrl)
                val title = normalized(item.title)
                indexed += IndexedTitle(item, type, title, withoutArticles(title),
                    genres.getOrPut(item.genres) { item.genres.joinToString(" ", transform = ::normalized) },
                    chartPositions[result.key] ?: chartAliases[catalogDisplayKey(result, title)])
            }
        }
        return indexed
    }

    suspend fun searchCatalog(index: List<IndexedTitle>, query: String): List<SearchResult> {
        val matcher = TitleQuery(query)
        if (!shouldSearch(query) || matcher.tokens.isEmpty()) return emptyList()
        val comparator = titleOrder.thenBy { it.normalizedTitle }

        suspend fun scan(allowTypo: Boolean): List<SearchResult> {
            val rankedByType = LinkedHashMap<String, MutableList<RankedTitle>>()
            for ((position, entry) in index.withIndex()) {
                if (position % 256 == 0) currentCoroutineContext().ensureActive()
                val item = entry.item
                val rank = matcher.rank(entry.title, item.year, entry.genres, allowTypo,
                    entry.titleWithoutArticles) ?: continue
                val result = SearchResult(item.canonicalId, entry.type, item.title, item.year, item.posterUrl, entry.chartPosition)
                val ranked = matcher.ranked(result, entry.title, entry.titleWithoutArticles, rank)
                val best = rankedByType.getOrPut(entry.type) { ArrayList(MAX_PER_SECTION + 1) }
                keepBest(best, ranked, comparator)
            }
            return rankedByType.values.flatMap { ranked -> ranked.map(RankedTitle::result) }
        }

        if (matcher.isMultiword) return scan(allowTypo = true)
        return scan(allowTypo = false).ifEmpty { scan(allowTypo = true) }
    }

    private fun keepBest(best: MutableList<RankedTitle>, candidate: RankedTitle, order: Comparator<RankedTitle>) {
        if (best.size == MAX_PER_SECTION && order.compare(candidate, best.last()) >= 0) return
        // Insert after existing ties to preserve source order.
        val index = best.indexOfFirst { order.compare(candidate, it) < 0 }
        best.add(if (index < 0) best.size else index, candidate)
        if (best.size > MAX_PER_SECTION) best.removeAt(MAX_PER_SECTION)
    }

    private data class RankedTitle(val rank: Int, val normalizedTitle: String, val result: SearchResult, val remaining: Int = 0)

    private val titleOrder = compareBy<RankedTitle>(
        { it.rank },
        { if (it.rank <= TYPO_MATCH) it.result.chartPosition ?: Int.MAX_VALUE else Int.MAX_VALUE },
        { it.remaining },
        { if (it.rank <= TYPO_MATCH) -(it.result.year ?: 0) else 0 },
    )

    /** The keyboard completes movie and series names, never channels or provider guesses. */
    fun titleSuggestions(sections: List<SearchSection>, query: String): List<SearchResult> {
        if (!shouldSearch(query)) return emptyList()
        val matcher = TitleQuery(query)
        return sections.asSequence().flatMap { it.items.asSequence() }
            .filter { it.type == "movie" || it.type == "series" || it.type == "anime" ||
                it.type == "iptv-movie" || it.type == "iptv-series" }
            .map(matcher::ranked)
            .filter { it.rank <= TYPO_MATCH }
            .sortedWith(titleOrder)
            .distinctBy { Triple(railType(it.result.type.removePrefix("iptv-")), it.normalizedTitle, it.result.year) }
            .map(RankedTitle::result)
            .take(6).toList()
    }

    private class TitleQuery(raw: String) {
        private val phrase = normalized(raw)
        val tokens = phrase.split(' ').filter(String::isNotEmpty)
        private val year = tokens.lastOrNull()?.toIntOrNull()?.takeIf {
            tokens.size > 1 && it in 1888..2035
        }
        private val literalTitle = if (year == null) phrase else tokens.dropLast(1).joinToString(" ")
        private val titlePhrase = withoutArticles(literalTitle)
        private val titleTokens = titlePhrase.split(' ')
        val isMultiword = titleTokens.size > 1
        private val yearToken = year?.toString()

        fun couldMatch(raw: String): Boolean {
            val text = if (raw.all { it.code < 128 }) raw else normalized(raw)
            return tokens.all { text.contains(it, ignoreCase = true) }
        }

        fun matches(title: String, year: Int?): Boolean = rank(normalized(title), year) != null

        fun ranked(
            result: SearchResult,
            title: String = normalized(result.title),
            matchTitle: String = withoutArticles(title),
            rank: Int = rank(title, result.year, matchTitle = matchTitle) ?: Int.MAX_VALUE,
        ): RankedTitle {
            val remaining = when (rank) {
                2 -> if (matchTitle.startsWith(titlePhrase)) matchTitle.length - titlePhrase.length
                    else title.length - literalTitle.length
                3 -> matchTitle.length - matchTitle.indexOf(" $titlePhrase") - 1 - titlePhrase.length
                else -> 0
            }
            return RankedTitle(rank, title, result, remaining)
        }

        fun matchesYear(title: String, releaseYear: Int?): Boolean =
            yearToken == null || year == releaseYear || title.split(' ').contains(yearToken)

        fun rank(
            title: String,
            releaseYear: Int?,
            genres: String = "",
            allowTypo: Boolean = true,
            matchTitle: String = withoutArticles(title),
        ): Int? {
            if (phrase.isEmpty()) return null
            if (title == phrase) return 0
            if (!matchesYear(title, releaseYear)) return null
            if (matchTitle == titlePhrase) return 0
            if (matchTitle.startsWith(titlePhrase) || title.startsWith(literalTitle)) return 2
            if (matchTitle.contains(" $titlePhrase")) return 3
            if (!allowTypo && titleTokens.any { it !in matchTitle && it !in genres }) return null
            val words = matchTitle.split(' ')
            if (titleTokens.all { token -> words.any { it.startsWith(token) } }) return 4
            if (genres.isNotEmpty() && titleTokens.all { token ->
                words.any { it.startsWith(token) } || genres.contains(token)
            }) return 5
            if (!allowTypo) return null
            // One typo per query. Four-letter minimum prevents loose matches on short prefixes.
            val unmatched = titleTokens.filterNot { token -> words.any { it.startsWith(token) } }
            val typo = unmatched.singleOrNull()?.takeIf { it.length >= 4 } ?: return null
            return if (words.any { word ->
                oneEditApart(word, typo) || (typo == titleTokens.last() && word.length > typo.length &&
                    oneEditApart(word.take(typo.length), typo))
            }) TYPO_MATCH else null
        }
    }

    private fun withoutArticles(title: String): String = title.split(' ')
        .filterNot { it == "a" || it == "an" || it == "the" }.joinToString(" ").ifEmpty { title }

    private fun oneEditApart(word: String, query: String): Boolean {
        if (kotlin.math.abs(word.length - query.length) > 1) return false
        val shorter = if (word.length <= query.length) word else query
        val longer = if (word.length <= query.length) query else word
        val first = shorter.indices.firstOrNull { shorter[it] != longer[it] } ?: shorter.length
        if (shorter.length != longer.length) return shorter.regionMatches(first, longer, first + 1, shorter.length - first)
        if (first == shorter.length) return false
        val next = first + 1
        if (shorter.regionMatches(next, longer, next, shorter.length - next)) return true
        return next < shorter.length && shorter[first] == longer[next] && shorter[next] == longer[first] &&
            shorter.regionMatches(next + 1, longer, next + 1, shorter.length - next - 1)
    }

    /** Group results by destination and rank within each rail. */
    fun resultRails(sections: List<SearchSection>, query: String): List<SearchSection> {
        val matcher = TitleQuery(query)
        val (iptv, titles) = sections.partition { isIptv(it.type) }
        val live = iptv.flatMap { it.items }
            .map(matcher::ranked).sortedBy { it.rank }.map(RankedTitle::result)
        val ranked = titles.flatMap { it.items }
            .map(matcher::ranked)
            .filter { matcher.matchesYear(it.normalizedTitle, it.result.year) }
            .sortedWith(titleOrder)
        val relevant = when {
            ranked.firstOrNull()?.rank?.let { it < TYPO_MATCH } == true ->
                ranked.takeWhile { it.rank < TYPO_MATCH || (matcher.isMultiword && it.rank == TYPO_MATCH) }
            ranked.firstOrNull()?.rank == TYPO_MATCH -> ranked.takeWhile { it.rank == TYPO_MATCH }
            live.isNotEmpty() -> emptyList()
            // Search-capable catalogs can return cast or other metadata matches.
            else -> ranked
        }
        val catalog = relevant.groupBy { catalogDisplayKey(it.result, it.normalizedTitle) }
            .values.map { matches ->
                val best = matches.first().result
                val withArt = matches.firstOrNull { !it.result.posterURL.isNullOrBlank() }?.result ?: best
                withArt.copy(chartPosition = best.chartPosition)
            }
        val (movies, series) = catalog.partition { railType(it.type) == "movie" }
        val catalogRails = listOfNotNull(
            movies.takeIf { it.isNotEmpty() }?.let { SearchSection("movie", "Movies", it) },
            series.takeIf { it.isNotEmpty() }?.let { SearchSection("series", "Series", it) },
        ).sortedWith(compareBy(titleOrder) { matcher.ranked(it.items.first()) })
        return if (live.isEmpty()) catalogRails else catalogRails + SearchSection("iptv", "IPTV", live)
    }

    /** A provider alias can change when another source responds. Keep its focus target stable. */
    fun catalogDisplayKey(result: SearchResult, title: String = normalized(result.title)): String =
        if (result.year == null) "id/${result.key}" else "title/${result.type}/${result.year}/$title"

    /** Use the public TMDB charts already on the TV. Alternate films and series. */
    fun trending(shelves: List<Shelf>): List<SearchSection> {
        val movies = shelves.firstOrNull { it.kind == ShelfKind.TMDB_MOVIES }?.items.orEmpty()
        val series = shelves.firstOrNull { it.kind == ShelfKind.TMDB_SERIES }?.items.orEmpty()
        val popular = (0 until 12).asSequence()
            .flatMap { sequenceOf(movies.getOrNull(it), series.getOrNull(it)).filterNotNull() }
            .filter { it.canonicalId.isNotBlank() && it.title.isNotBlank() }
            .mapNotNull { item ->
                canonicalType(item.mediaType)?.let {
                    SearchResult(item.canonicalId, it, item.title, item.year, item.posterUrl)
                }
            }
            .distinctBy { it.key }
            .take(12)
            .toList()
        return if (popular.isEmpty()) emptyList() else listOf(SearchSection("trending", "Trending", popular))
    }

    fun resultCount(sections: List<SearchSection>): Int = sections.sumOf { it.items.size }

    /** "18 results", "1 result", or null when there is nothing to count. */
    fun countLabel(sections: List<SearchSection>): String? = when (val total = resultCount(sections)) {
        0 -> null
        1 -> "1 result"
        else -> "$total results"
    }

    /**
     * The recent list after a viewer opened a result: newest first, no repeats, capped.
     *
     * Comparison is case-insensitive. Distinct submitted searches can share a prefix.
     */
    fun recordRecent(existing: List<String>, query: String, cap: Int = RECENT_CAP): List<String> {
        val trimmed = query.trim()
        if (!shouldSearch(trimmed)) return existing
        val lower = trimmed.lowercase()
        val kept = existing.filterNot { lower == it.trim().lowercase() }
        return (listOf(trimmed) + kept).take(cap)
    }

    fun cleanRecents(stored: List<String>): List<String> = stored.asSequence()
        .map(String::trim).filter(::shouldSearch).distinctBy(String::lowercase)
        .take(RECENT_CAP).toList()

    /** Search skips generic Other entries, whose type cannot identify a movie or series. */
    private fun canonicalType(raw: String): String? = when (raw.trim().lowercase()) {
        "", "movie", "movies", "film" -> "movie"
        "series", "show", "tv" -> "series"
        "anime" -> "anime"
        "other" -> null
        else -> raw.trim().lowercase()
    }

    internal fun label(type: String): String = when (type) {
        "movie" -> "Movies"
        "series" -> "Series"
        "anime" -> "Anime"
        "channel" -> "Channels"
        "iptv-program" -> "Programs"
        "iptv-channel-category" -> "Channel categories"
        "iptv-movie-category" -> "Movie categories"
        "iptv-series-category" -> "Series categories"
        "iptv-movie" -> "IPTV movies"
        "iptv-series" -> "IPTV series"
        "tv" -> "Live TV"
        else -> type.replaceFirstChar { it.uppercase() }
    }

    private val LATIN_DIACRITICS = Regex("(?<=\\p{IsLatin})\\p{M}+")
    private val SEPARATORS = Regex("[^\\p{L}\\p{M}\\p{N}]+")

    internal fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(LATIN_DIACRITICS, "")
        .lowercase(Locale.ROOT)
        .replace(SEPARATORS, " ")
        .trim()
}
