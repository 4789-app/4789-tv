package com.fourseveneightnine.tv.client.data.meta

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import com.fourseveneightnine.tv.client.data.streams.StreamRow

/** A playback request can carry an ID as a temporary title. */
public fun displayTitleOrNull(title: String?, canonicalId: String): String? =
    title?.trim()?.takeIf { it.isNotEmpty() && it != canonicalId && it != canonicalId.substringAfter("::") }

/** Preserve editorial casing. Some catalogs supply every title in capitals. */
public fun episodeDisplayTitle(raw: String): String {
    val title = raw.trim()
    if (!title.any(Char::isUpperCase) || title.any(Char::isLowerCase)) return title
    val firstWord = title.indexOfFirst(Char::isLetterOrDigit)
    val lastWord = title.indexOfLast(Char::isLetterOrDigit)
    return titleWord.replace(title) { match ->
        val word = match.value
        when {
            word in acronyms || word.any(Char::isDigit) -> word
            word in minorWords && match.range.first > firstWord && match.range.last < lastWord -> word.lowercase()
            else -> word.lowercase().replaceFirstChar { it.titlecase() }
        }
    }
}

private val titleWord = Regex("""[\p{L}\p{N}]+(?:['’][\p{L}\p{N}]+)*""")
private val acronyms = setOf("AP", "AI", "DNA", "TV", "UFO", "FBI", "CIA", "NASA", "NYC", "USA", "UK",
    "II", "III", "IV", "VI", "VII", "VIII", "IX", "XI", "XII")
private val minorWords = setOf("A", "AN", "THE", "AND", "AS", "AT", "BUT", "BY", "FOR", "IN", "OF", "ON", "OR", "TO")

/** One episode of a series. `id` is the Stremio video id the stream call needs. */
@Serializable
data class Episode(
    val season: Int,
    val episode: Int,
    val title: String,
    val overview: String? = null,
    val thumbnail: String? = null,
    /** ISO-8601 as the add-on wrote it. The UI formats it; this layer does not guess a locale. */
    val released: String? = null,
    val id: String,
    @Transient val inlineStreams: List<StreamRow>? = null,
)

/** One person on the Detail cast rail. */
@Serializable
data class CastMember(
    val name: String,
    val role: String? = null,
    val photo: String? = null,
    val personID: Int? = null,
)

/** A pointer at another title, for the More Like This rail. */
@Serializable
data class MetaRef(
    val id: String,
    val type: String,
    val title: String,
    val poster: String? = null,
)

/** Confirmed streaming availability for the selected country, supplied by TMDB/JustWatch. */
@Serializable
data class StreamingProvider(val id: Int, val name: String, val logo: String)

/** Published episode dates for one calendar month. Contains metadata only, never source URLs. */
data class CalendarSchedule(
    val series: List<Meta>,
    val incomplete: Boolean = false,
    val discoveryAvailable: Boolean = true,
)

/**
 * Everything Detail (`TV_DESIGN_SPEC.md` §9) draws for one title.
 *
 * Every field is nullable or empty-able on purpose, because §9.9 gives a fallback for each missing
 * one. A gap is a layout instruction here, not an error.
 */
@Serializable
data class Meta(
    val id: String,
    val type: String,
    val title: String,
    val year: Int? = null,
    val runtimeMinutes: Int? = null,
    val certification: String? = null,
    val description: String? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val genres: List<String> = emptyList(),
    val imdbID: String? = null,
    val tmdbID: Int? = null,
    val imdbRating: Double? = null,
    val trailerYouTubeID: String? = null,
    val cast: List<CastMember> = emptyList(),
    val videos: List<Episode> = emptyList(),
    val similar: List<MetaRef> = emptyList(),
    /** Seasons present in [videos], ascending. Season 0 is Specials. */
    val seasons: List<Int> = videos.map(Episode::season).distinct().sorted(),
    val defaultVideoID: String? = null,
    /** Disk caches remember that a fresh response is required, never the capability URLs. */
    val hasInlineStreams: Boolean = false,
    @Transient val inlineStreams: List<StreamRow>? = null,
    /** The title's original audio language, not the metadata UI language or production country. */
    val originalLanguage: String? = null,
    val countries: List<String> = emptyList(),
    val tmdbRating: Double? = null,
    val castNames: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    val directorIDs: Map<String, Int> = emptyMap(),
    val creators: List<String> = emptyList(),
    val streamingProviders: List<StreamingProvider> = emptyList(),
    val studios: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
    val spokenLanguages: List<String> = emptyList(),
    val releaseStatus: String? = null,
    val releaseDate: String? = null,
    val tmdbVotes: Int? = null,
    val popularity: Double? = null,
    /** Canonical and localized names from the same TMDB work; never add-on release labels. */
    val titleAliases: List<String> = emptyList(),
    /** Alternate provider artwork, retained so an image HTTP/decode failure can recover. */
    val posterFallbacks: List<String> = emptyList(),
    val backdropFallbacks: List<String> = emptyList(),
    val creatorIDs: Map<String, Int> = emptyMap(),
) {
    val posterCandidates: List<String>
        get() = (listOfNotNull(poster) + posterFallbacks).filter(String::isNotBlank).distinct()

    val backdropCandidates: List<String>
        get() = (listOfNotNull(backdrop) + backdropFallbacks).filter(String::isNotBlank).distinct()

    fun episodes(season: Int): List<Episode> =
        videos.filter { it.season == season }.sortedBy(Episode::episode)

    fun streamsFor(season: Int?, episode: Int?): List<StreamRow>? =
        if (type == "series") videos.firstOrNull { it.season == season && it.episode == episode }?.inlineStreams
        else inlineStreams
}

/**
 * The four numbers the Detail ratings row can print (§9.4). A provider with nothing to say is
 * null, and its chip is dropped rather than shown at zero.
 */
@Serializable
data class Ratings(
    /** 0–10. */
    val imdb: Double? = null,
    /** 0–100, because Trakt is a percentage that looks like a score. */
    val trakt: Int? = null,
    /** 0–100. */
    val tmdb: Int? = null,
    /** 0–5 stars, as Letterboxd reports it. */
    val letterboxd: Double? = null,
) {
    val isEmpty: Boolean get() = imdb == null && trakt == null && tmdb == null && letterboxd == null

    companion object {
        val NONE: Ratings = Ratings()
    }
}
