package com.fourseveneightnine.tv.client.ui.screens.search

import com.fourseveneightnine.contract.DiscoverItem
import com.fourseveneightnine.tv.client.data.catalog.Shelf
import com.fourseveneightnine.tv.client.iptv.IptvState
import com.fourseveneightnine.tv.client.iptv.IptvIndex
import com.fourseveneightnine.tv.client.iptv.IptvChannel
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One card in a results row, spec §12.6: poster, title, year, type. */
internal data class SearchResult(
    val id: String,
    val type: String,
    val title: String,
    val year: Int?,
    val posterURL: String?,
) {
    /** Stable `key()` for the row, so a card that survives a new query keeps its focus. */
    val key: String get() = "$type/$id"
}

/** One labelled row of results. A section with nothing in it is never built. */
internal data class SearchSection(val type: String, val title: String, val items: List<SearchResult>)

/**
 * What the Search screen does with add-on answers, spec §12.
 *
 * Pure. No coroutines, no Compose, no network — the screen owns the fan-out and hands the pages
 * here, which is what makes the merge, the section order and the recent list testable on the JVM.
 */
internal object SearchSections {

    /** A query under two characters is not sent (spec §12.7.7). */
    const val MIN_QUERY_LENGTH = 2

    /** Typing is held for this long before a search runs (spec §12.3). */
    const val DEBOUNCE_MILLIS = 400L

    /** Recent searches are kept on the TV, capped at five (spec §12.7.6). */
    const val RECENT_CAP = 5

    /** More than this per row is scrolling no viewer does with a D-pad. */
    const val MAX_PER_SECTION = 24

    /** The extra key a catalog must declare for the TV to send it a search. */
    const val SEARCH_EXTRA = "search"

    private val SECTION_ORDER = listOf("channel", "iptv-channel-category", "iptv-program", "movie", "series",
        "iptv-movie", "iptv-movie-category", "iptv-series", "iptv-series-category", "anime")

    suspend fun progressiveResults(local: List<SearchResult>,
        remote: suspend () -> List<List<DiscoverItem>>?, publish: (List<SearchSection>) -> Unit): Boolean {
        if (local.isNotEmpty()) publish(merge(emptyList(), local))
        val pages = remote()
        if (pages != null || local.isNotEmpty()) {
            publish(merge(pages.orEmpty(), local))
            return true
        }
        return false
    }

    fun iptv(state: IptvState, query: String, now: Long = System.currentTimeMillis()): List<SearchResult> {
        if (!shouldSearch(query)) return emptyList()
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
        val vod = state.catalog.vod.filter { it.sourceId in enabled && it.category !in blocked }
        val videos = listOf("movie", "series").flatMap { kind ->
            vod.asSequence().filter { it.kind == kind && it.title.contains(query, ignoreCase = true) }
                .take(MAX_PER_SECTION).map { SearchResult(it.id, "iptv-$kind", it.title, null, it.image) }.toList()
        }
        val groups = channels.groupingBy(IptvChannel::group).eachCount().entries
            .filter { it.key.contains(query, ignoreCase = true) }.take(MAX_PER_SECTION)
            .map { SearchResult(it.key, "iptv-channel-category", "${it.key} · ${it.value} channels", null, null) }
        val counts = vod.groupingBy { it.kind to it.category }.eachCount().toMutableMap()
        state.catalog.vodCategories.filter { it.sourceId in enabled && it.name !in blocked }
            .groupBy { it.kind to it.name }.forEach { (key, rows) ->
                counts[key] = maxOf(counts[key] ?: 0, rows.sumOf { it.count })
            }
        val categories = listOf("movie", "series").flatMap { kind ->
            counts.entries.filter { it.key.first == kind && it.key.second.contains(query, ignoreCase = true) }
                .take(MAX_PER_SECTION).map { (key, count) ->
                    SearchResult(key.second, "iptv-$kind-category", "${key.second} · $count $kind titles", null, null)
                }
        }
        return matches + programs + groups + videos + categories
    }

    fun shouldSearch(query: String): Boolean = query.trim().length >= MIN_QUERY_LENGTH

    fun normalize(query: String): String = query.trim().lowercase()

    /**
     * Folds every add-on's page into rows, in the spec's order.
     *
     * The first answer for a `type/id` wins, so the add-on that answered first keeps its poster
     * and a later duplicate does not reorder the row. Sections with no results are removed rather
     * than drawn empty (spec §12.7.3).
     */
    fun merge(
        pages: List<List<DiscoverItem>>,
        local: List<SearchResult> = emptyList(),
    ): List<SearchSection> {
        val seen = LinkedHashSet<String>()
        val byType = LinkedHashMap<String, MutableList<SearchResult>>()
        pages.forEach { page ->
            page.forEach { item ->
                if (item.id.isBlank() || item.title.isBlank()) return@forEach
                val type = canonicalType(item.type)
                val result = SearchResult(item.id, type, item.title, item.year, item.posterURL)
                if (!seen.add(result.key)) return@forEach
                byType.getOrPut(type) { mutableListOf() }.add(result)
            }
        }
        local.forEach { candidate ->
            if (candidate.id.isBlank() || candidate.title.isBlank()) return@forEach
            val result = candidate.copy(type = canonicalType(candidate.type))
            if (!seen.add(result.key)) return@forEach
            byType.getOrPut(result.type) { mutableListOf() }.add(result)
        }
        val known = SECTION_ORDER.mapNotNull { type ->
            byType.remove(type)?.take(MAX_PER_SECTION)?.let { SearchSection(type, label(type), it) }
        }
        val rest = byType.map { (type, items) ->
            SearchSection(type, label(type), items.take(MAX_PER_SECTION))
        }
        return known + rest
    }

    /**
     * Searches every cached snapshot shelf, including the lists that Home intentionally does not
     * draw. This runs after the keyboard debounce on Dispatchers.IO. It scans the complete local
     * directory but returns only the strongest TV-sized result set, so 120 synced lists remain
     * searchable without composing or retaining thousands of result cards.
     */
    suspend fun local(shelves: List<Shelf>, query: String): List<SearchResult> {
        val needle = normalized(query)
        if (!shouldSearch(needle)) return emptyList()
        val tokens = needle.split(' ').filter(String::isNotBlank)
        val seen = HashSet<String>()
        val rankedByType = LinkedHashMap<String, MutableList<Pair<Int, SearchResult>>>()
        val comparator = compareBy<Pair<Int, SearchResult>>({ it.first }, { normalized(it.second.title) })
        var scanned = 0
        shelves.forEach { shelf ->
            shelf.items.forEach { item ->
                if (++scanned % 256 == 0) currentCoroutineContext().ensureActive()
                val type = canonicalType(item.mediaType)
                val key = "$type/${item.canonicalId}"
                if (item.canonicalId.isBlank() || item.title.isBlank() || !seen.add(key)) return@forEach
                val title = normalized(item.title)
                val haystack = buildString {
                    append(title)
                    append(' ')
                    append(item.year ?: "")
                    append(' ')
                    append(item.genres.joinToString(" ") { normalized(it) })
                }
                if (!tokens.all(haystack::contains)) return@forEach
                val score = when {
                    title == needle -> 0
                    title.startsWith(needle) -> 1
                    title.split(' ').any { it.startsWith(needle) } -> 2
                    title.contains(needle) -> 3
                    else -> 4
                }
                val ranked = score to SearchResult(
                    id = item.canonicalId,
                    type = type,
                    title = item.title,
                    year = item.year,
                    posterURL = item.posterUrl,
                )
                val best = rankedByType.getOrPut(type) { ArrayList(MAX_PER_SECTION + 1) }
                best += ranked
                if (best.size > MAX_PER_SECTION) {
                    var worst = 0
                    for (index in 1 until best.size) {
                        if (comparator.compare(best[index], best[worst]) > 0) worst = index
                    }
                    best.removeAt(worst)
                }
            }
        }
        return rankedByType.values.flatMap { ranked ->
            ranked.sortedWith(comparator).map(Pair<Int, SearchResult>::second)
        }
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
     * Comparison is case-insensitive, so "Dune" does not sit beside "dune". A stored entry that is
     * a prefix of the new one is dropped, because it is what the viewer typed on the way to this
     * one: the box showed "DUNE", "DUN" and "DU" as three separate searches.
     */
    fun recordRecent(existing: List<String>, query: String, cap: Int = RECENT_CAP): List<String> {
        val trimmed = query.trim()
        if (trimmed.length < MIN_QUERY_LENGTH) return existing
        val lower = trimmed.lowercase()
        val kept = existing.filterNot { lower.startsWith(it.trim().lowercase()) }
        return (listOf(trimmed) + kept).take(cap)
    }

    /**
     * Drops a stored search that is only the start of another stored search. Builds before F83
     * saved every keystroke, so a box still carried "DUNE", "DUN" and "DU" as three recents.
     */
    fun cleanRecents(stored: List<String>): List<String> = stored.filter { term ->
        val lower = term.trim().lowercase()
        stored.none { other ->
            val o = other.trim().lowercase()
            o.length > lower.length && o.startsWith(lower)
        }
    }

    /** Stremio types the TV knows. `tv` and `show` are the same shelf as `series`. */
    private fun canonicalType(raw: String): String = when (raw.trim().lowercase()) {
        "", "movie", "movies", "film" -> "movie"
        "series", "show", "tv" -> "series"
        "anime" -> "anime"
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

    private val DIACRITICS = Regex("\\p{Mn}+")
    private val WHITESPACE = Regex("\\s+")

    private fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(DIACRITICS, "")
        .lowercase(Locale.ROOT)
        .trim()
        .replace(WHITESPACE, " ")
}
