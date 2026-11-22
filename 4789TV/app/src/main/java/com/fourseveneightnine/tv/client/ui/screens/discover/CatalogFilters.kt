package com.fourseveneightnine.tv.client.ui.screens.discover

import com.fourseveneightnine.tv.client.data.meta.Meta
import com.fourseveneightnine.tv.client.ui.screens.home.HomeCard
import java.util.BitSet
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.fourseveneightnine.tv.client.playback.WatchlistSource

internal const val CATALOG_FILTER_SHORTCUT = "Left from first column for filters"

@Serializable
internal enum class CatalogSort(val label: String) {
    Original("Catalog order"), Title("Title A–Z"), TitleDescending("Title Z–A"),
    Newest("Newest first"), Oldest("Oldest first"), Rating("Highest rating"),
    Shortest("Shortest first"), Longest("Longest first"), Votes("Most votes"), Popularity("Most popular"),
    ReleaseNewest("Latest release date"), ReleaseOldest("Earliest release date"),
}

@Serializable
internal enum class CatalogRuntime(val label: String) {
    Any("Any runtime"), Short("Under 90 minutes"), Medium("90–120 minutes"), Long("Over 120 minutes");
    fun matches(minutes: Int?): Boolean = when (this) {
        Any -> true
        Short -> minutes != null && minutes < 90
        Medium -> minutes != null && minutes in 90..120
        Long -> minutes != null && minutes > 120
    }
}

@Serializable
internal enum class CatalogRatingSource(val label: String) {
    Available("Available rating"), IMDb("IMDb"), TMDB("TMDB");
    fun score(card: HomeCard): Double? = when (this) {
        Available -> card.rating
        IMDb -> card.imdbRating
        TMDB -> card.tmdbRating
    }
}

@Serializable
internal enum class CatalogHistory(val label: String) {
    Any("Any playback history"), Unplayed("No recorded playback"), Continuing("Continue watching"), Finished("Finished movies");
}

/** Saveable local query. It never contains provider URLs or credentials. */
@Serializable
internal data class CatalogFilters(
    val year: Int? = null,
    val decade: Int? = null,
    val includedGenres: Set<String> = emptySet(),
    val excludedGenres: Set<String> = emptySet(),
    val matchAllGenres: Boolean = true,
    val language: String? = null,
    val country: String? = null,
    val minimumRating: Int? = null,
    val runtime: CatalogRuntime = CatalogRuntime.Any,
    val sort: CatalogSort = CatalogSort.Original,
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val maximumRating: Int? = null,
    val runtimeFrom: Int? = null,
    val runtimeTo: Int? = null,
    val mediaType: String? = null,
    val certification: String? = null,
    val actor: String? = null,
    val director: String? = null,
    val studio: String? = null,
    val keyword: String? = null,
    val spokenLanguage: String? = null,
    val releaseStatus: String? = null,
    val minimumVotes: Int? = null,
    val ratingSource: CatalogRatingSource = CatalogRatingSource.Available,
    val withTrailer: Boolean = false,
    val collectionId: Long? = null,
    val history: CatalogHistory = CatalogHistory.Any,
) {
    val active: Boolean get() = this != DEFAULT && copy(matchAllGenres = true) != DEFAULT
    val label: String get() = if (!active) "Filters & sort" else buildList {
        year?.let { add(it.toString()) }; decade?.let { add("${it}s") }
        if (includedGenres.isNotEmpty()) add("+${includedGenres.size} genres")
        if (excludedGenres.isNotEmpty()) add("−${excludedGenres.size} genres")
        if (copy(year = null, decade = null, includedGenres = emptySet(), excludedGenres = emptySet(),
                sort = CatalogSort.Original, matchAllGenres = true) != DEFAULT) add("More filters")
        if (sort != CatalogSort.Original) add(sort.label)
    }.joinToString(" · ")

    fun include(genre: String): CatalogFilters = copy(includedGenres = includedGenres.toggle(genre), excludedGenres = excludedGenres - genre)
    fun exclude(genre: String): CatalogFilters = copy(excludedGenres = excludedGenres.toggle(genre), includedGenres = includedGenres - genre)
    fun matches(card: HomeCard): Boolean = apply(listOf(card)).isNotEmpty()
    fun apply(cards: List<HomeCard>): List<HomeCard> = CatalogFacetIndex(cards).query(this)

    companion object { private val DEFAULT = CatalogFilters() }
}

/**
 * Immutable inverted index. String normalization and facet menus are built once, off Main.
 * Queries intersect bitsets and reuse sorted row orders. Network calls never run in a query.
 */
internal class CatalogFacetIndex(
    val cards: List<HomeCard>, checkCancelled: () -> Unit = {},
) {
    private val postings: Map<String, Map<String, CatalogPosting>>
    private val orders = ConcurrentHashMap<Int, IntArray>()
    val options: Map<String, List<String>>

    init {
        val builders = HashMap<String, MutableMap<String, MutableList<Int>>>()
        fun add(field: String, value: String?, row: Int) {
            if (value.isNullOrBlank()) return
            builders.getOrPut(field) { HashMap() }.getOrPut(value) { ArrayList() }.add(row)
        }
        cards.forEachIndexed { row, card ->
            if (row % 256 == 0) checkCancelled()
            val genres = catalogGenres(card.genres.ifEmpty { listOfNotNull(card.genre) })
            genres.forEach { add("genre", it, row) }
            if (genres.isNotEmpty()) add("genreKnown", "yes", row)
            add("year", card.year?.toString(), row); add("decade", card.year?.let { (it / 10 * 10).toString() }, row)
            add("type", card.catalogMediaType, row); add("language", card.originalLanguage, row)
            card.countries.forEach { add("country", catalogCountry(it), row) }
            card.spokenLanguages.forEach { add("spoken", it, row) }
            card.castNames.forEach { add("actor", it, row) }; card.directors.forEach { add("director", it, row) }
            card.studios.forEach { add("studio", it, row) }; card.keywords.forEach { add("keyword", it, row) }
            add("certification", card.certification, row); add("status", card.releaseStatus, row)
            if (card.hasTrailer) add("trailer", "yes", row)
            card.collectionIds.forEach { add("collection", it.toString(), row) }
            add("history", if (card.hasPlaybackHistory) "recorded" else "unplayed", row)
            if (card.continuing) add("history", "continuing", row)
            if (card.finishedMovie) add("history", "finished", row)
        }
        postings = builders.mapValues { (_, values) -> values.mapValues { (_, rows) -> CatalogPosting(rows, cards.size) } }
        options = postings.mapValues { (_, values) -> values.keys.sortedWith(String.CASE_INSENSITIVE_ORDER) }
    }

    fun query(filters: CatalogFilters, checkCancelled: () -> Unit = {}): List<HomeCard> {
        if (!filters.active) return cards
        val found = BitSet(cards.size).apply { set(0, cards.size) }
        fun require(field: String, value: String?) {
            if (value == null) return
            val bits = postings[field]?.get(value)
            if (bits == null) found.clear() else bits.intersect(found)
        }
        require("year", filters.year?.toString()); require("decade", filters.decade?.toString())
        val included = catalogGenres(filters.includedGenres.toList())
        if (included.isNotEmpty()) {
            if (filters.matchAllGenres) included.forEach { require("genre", it) }
            else {
                val union = BitSet()
                included.forEach { postings["genre"]?.get(it)?.addTo(union) }
                found.and(union)
            }
        }
        if (filters.excludedGenres.isNotEmpty()) {
            require("genreKnown", "yes")
            catalogGenres(filters.excludedGenres.toList()).forEach { postings["genre"]?.get(it)?.removeFrom(found) }
        }
        require("type", filters.mediaType); require("language", filters.language); require("country", filters.country)
        require("spoken", filters.spokenLanguage); require("actor", filters.actor); require("director", filters.director)
        require("studio", filters.studio); require("keyword", filters.keyword); require("certification", filters.certification)
        require("status", filters.releaseStatus); require("collection", filters.collectionId?.toString())
        if (filters.withTrailer) require("trailer", "yes")
        when (filters.history) {
            CatalogHistory.Any -> Unit
            CatalogHistory.Unplayed -> require("history", "unplayed")
            CatalogHistory.Continuing -> require("history", "continuing")
            CatalogHistory.Finished -> require("history", "finished")
        }
        val numeric = filters.yearFrom != null || filters.yearTo != null || filters.minimumRating != null ||
            filters.maximumRating != null || filters.minimumVotes != null || filters.runtimeFrom != null ||
            filters.runtimeTo != null || filters.runtime != CatalogRuntime.Any
        var row = if (numeric) found.nextSetBit(0) else -1
        while (row >= 0) {
            if (row % 256 == 0) checkCancelled()
            val card = cards[row]
            val rating = filters.ratingSource.score(card)
            val keep = (filters.yearFrom == null || (card.year != null && card.year >= filters.yearFrom)) &&
                (filters.yearTo == null || (card.year != null && card.year <= filters.yearTo)) &&
                (filters.minimumRating == null || (rating != null && rating >= filters.minimumRating)) &&
                (filters.maximumRating == null || (rating != null && rating <= filters.maximumRating)) &&
                (filters.minimumVotes == null || (card.tmdbVotes != null && card.tmdbVotes >= filters.minimumVotes)) &&
                (filters.runtimeFrom == null || (card.runtimeMinutes != null && card.runtimeMinutes >= filters.runtimeFrom)) &&
                (filters.runtimeTo == null || (card.runtimeMinutes != null && card.runtimeMinutes <= filters.runtimeTo)) &&
                filters.runtime.matches(card.runtimeMinutes)
            if (!keep) found.clear(row)
            row = found.nextSetBit(row + 1)
        }
        if (filters.sort == CatalogSort.Original) {
            val result = ArrayList<HomeCard>(found.cardinality())
            row = found.nextSetBit(0)
            while (row >= 0) { result.add(cards[row]); row = found.nextSetBit(row + 1) }
            return result
        }
        val key = filters.sort.ordinal * CatalogRatingSource.entries.size + filters.ratingSource.ordinal
        val order = orders.getOrPut(key) {
            val comparator: Comparator<HomeCard> = when (filters.sort) {
                CatalogSort.Title -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
                CatalogSort.TitleDescending -> compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.title }
                CatalogSort.Newest -> compareBy<HomeCard> { it.year == null }.thenByDescending { it.year }
                CatalogSort.Oldest -> compareBy<HomeCard> { it.year == null }.thenBy { it.year }
                CatalogSort.Rating -> compareBy<HomeCard> { filters.ratingSource.score(it) == null }.thenByDescending { filters.ratingSource.score(it) }
                CatalogSort.Shortest -> compareBy<HomeCard> { it.runtimeMinutes == null }.thenBy { it.runtimeMinutes }
                CatalogSort.Longest -> compareBy<HomeCard> { it.runtimeMinutes == null }.thenByDescending { it.runtimeMinutes }
                CatalogSort.Votes -> compareBy<HomeCard> { it.tmdbVotes == null }.thenByDescending { it.tmdbVotes }
                CatalogSort.Popularity -> compareBy<HomeCard> { it.popularity == null }.thenByDescending { it.popularity }
                CatalogSort.ReleaseNewest -> compareBy<HomeCard> { it.releaseDate == null }.thenByDescending { it.releaseDate }
                CatalogSort.ReleaseOldest -> compareBy<HomeCard> { it.releaseDate == null }.thenBy { it.releaseDate }
                CatalogSort.Original -> compareBy { 0 }
            }
            cards.indices.sortedWith { a, b -> comparator.compare(cards[a], cards[b]) }.toIntArray()
        }
        checkCancelled()
        return order.asSequence().filter(found::get).map(cards::get).toList()
    }
}

/** Sparse actor/keyword postings avoid an N-bit allocation for each unique name. */
private class CatalogPosting(rows: List<Int>, total: Int) {
    private val dense = if (rows.size >= 64 && rows.size.toLong() * 32 >= total) BitSet(total).apply { rows.forEach(::set) } else null
    private val sparse = if (dense == null) rows.toIntArray() else IntArray(0)
    fun intersect(target: BitSet) {
        if (dense != null) target.and(dense)
        else { val allowed = BitSet(); sparse.forEach(allowed::set); target.and(allowed) }
    }
    fun addTo(target: BitSet) { if (dense != null) target.or(dense) else sparse.forEach(target::set) }
    fun removeFrom(target: BitSet) { if (dense != null) target.andNot(dense) else sparse.forEach(target::clear) }
}

private fun Set<String>.toggle(value: String): Set<String> = if (value in this) this - value else this + value

internal fun catalogGenres(values: List<String>): List<String> = values.flatMap { value ->
    when (value.trim().lowercase(Locale.ROOT)) {
        "sci-fi", "science fiction" -> listOf("Science Fiction")
        "sci-fi & fantasy" -> listOf("Science Fiction", "Fantasy")
        "action & adventure" -> listOf("Action", "Adventure")
        "war & politics" -> listOf("War", "Politics")
        else -> listOf(value.trim().lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) })
    }
}.filter(String::isNotBlank).distinct()

internal fun catalogCountry(value: String): String = when (val clean = value.trim()) {
    "USA", "US", "United States of America" -> "United States"
    "UK", "GB" -> "United Kingdom"
    else -> if (clean.length == 2) Locale("", clean.uppercase(Locale.ROOT)).getDisplayCountry(Locale.ENGLISH) else clean
}

/** A saved source keeps its own playback ID while metadata uses the bound catalog title. */
internal fun catalogMetadataId(id: String, savedSource: String?): String {
    if (!id.startsWith(WatchlistSource.PREFIX) || savedSource == null) return id
    return runCatching { Json.decodeFromString<WatchlistSource>(savedSource).catalogId }
        .getOrNull()?.takeIf { it.isNotBlank() && !it.contains("://") && !it.startsWith(WatchlistSource.PREFIX) } ?: id
}

/** Preserve provider identities while retaining source facts that are already known. */
internal fun HomeCard.withCatalogMetadata(meta: Meta): HomeCard = copy(
    year = year ?: meta.year, genres = catalogGenres(genres + listOfNotNull(genre) + meta.genres),
    genre = genre ?: meta.genres.firstOrNull(), rating = rating ?: meta.imdbRating ?: meta.tmdbRating,
    runtimeMinutes = runtimeMinutes ?: meta.runtimeMinutes, originalLanguage = meta.originalLanguage ?: originalLanguage,
    countries = (countries + meta.countries).map(::catalogCountry).distinct(), posterUrl = posterUrl ?: meta.poster,
    tmdbID = tmdbID ?: meta.tmdbID, imdbID = imdbID ?: meta.imdbID,
    castNames = (castNames + meta.castNames + meta.cast.map { it.name }).distinct(),
    directors = (directors + meta.directors).distinct(), studios = (studios + meta.studios).distinct(),
    keywords = (keywords + meta.keywords).distinct(), spokenLanguages = (spokenLanguages + meta.spokenLanguages).distinct(),
    certification = certification ?: meta.certification, releaseStatus = releaseStatus ?: meta.releaseStatus,
    releaseDate = releaseDate ?: meta.releaseDate, tmdbVotes = tmdbVotes ?: meta.tmdbVotes,
    popularity = popularity ?: meta.popularity, imdbRating = imdbRating ?: meta.imdbRating,
    tmdbRating = tmdbRating ?: meta.tmdbRating, hasTrailer = hasTrailer || meta.trailerYouTubeID != null,
)
