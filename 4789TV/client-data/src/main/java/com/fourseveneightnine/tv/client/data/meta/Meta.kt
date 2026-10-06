package com.fourseveneightnine.tv.client.data.meta

import kotlinx.serialization.Serializable

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
)

/** One person on the Detail cast rail. */
@Serializable
data class CastMember(
    val name: String,
    val role: String? = null,
    val photo: String? = null,
)

/** A pointer at another title, for the More Like This rail. */
@Serializable
data class MetaRef(
    val id: String,
    val type: String,
    val title: String,
    val poster: String? = null,
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
) {
    fun episodes(season: Int): List<Episode> =
        videos.filter { it.season == season }.sortedBy(Episode::episode)
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
