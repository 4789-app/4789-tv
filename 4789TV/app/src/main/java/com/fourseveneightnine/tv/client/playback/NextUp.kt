package com.fourseveneightnine.tv.client.playback

import androidx.compose.runtime.Immutable
import com.fourseveneightnine.tv.client.data.meta.Episode
import com.fourseveneightnine.tv.client.data.meta.Meta

/**
 * The episode after the one now playing, and the line the plate prints (`TV_DESIGN_SPEC.md` §11.7).
 *
 * [OpenMediaRequest.nextUp][com.fourseveneightnine.tv.protocol.OpenMediaRequest.nextUp] cannot
 * carry this one, because that wire type needs a URL and a local next episode has no URL yet: its
 * sources are searched while the plate counts down, which is what §11.7 asks for. The phone's own
 * `nextUp` is unchanged and still drives the Next pill on a cast.
 */
@Immutable
internal data class NextUpPlan(
    /** The show, not the episode: this is the canonical id every other call already uses. */
    val canonicalId: String,
    val type: String,
    val showTitle: String,
    val season: Int,
    val episode: Int,
    val episodeTitle: String,
    val stillUrl: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val runtimeMinutes: Int? = null,
) {
    /** "S2 E5 · Pot au Feu", 1 line, 28 characters on the plate. */
    val line: String get() = NextUp.subtitle(season, episode, episodeTitle)

    /** "44 min", or null when the show never stated a runtime. */
    val runtimeLine: String? get() = runtimeMinutes?.takeIf { it > 0 }?.let { "$it min" }

    /** What a Play press for this episode asks for. */
    fun request(): PlayRequest = PlayRequest(
        type = type,
        id = canonicalId,
        title = showTitle,
        season = season,
        episode = episode,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
    )
}

/**
 * Which episode comes next, by season and episode order.
 *
 * Order is taken from the numbers, never from the order an add-on happened to list its videos in:
 * Cinemeta returns specials interleaved and TMDB returns them last, and a "next" that follows the
 * array lands on a special in the middle of a season.
 *
 * Specials (season 0) are never "next". A viewer who wants one picks it from the episodes row.
 */
internal object NextUp {

    fun compute(meta: Meta?, season: Int?, episode: Int?): NextUpPlan? {
        if (meta == null || meta.type != "series") return null
        if (season == null || episode == null) return null
        val next = nextEpisode(meta.videos, season, episode) ?: return null
        return NextUpPlan(
            canonicalId = meta.id,
            type = meta.type,
            showTitle = meta.title,
            season = next.season,
            episode = next.episode,
            episodeTitle = next.title,
            stillUrl = next.thumbnail,
            posterUrl = meta.poster,
            backdropUrl = meta.backdrop,
            runtimeMinutes = meta.runtimeMinutes,
        )
    }

    /**
     * The episode straight after ([season], [episode]) in ordered, specials-free order.
     *
     * Returns null on the last episode of the last season, and on an episode the list does not
     * hold — a title whose numbering the add-on and the caller disagree about gets no auto-next
     * rather than a guess at which episode was meant.
     */
    fun nextEpisode(videos: List<Episode>, season: Int, episode: Int): Episode? {
        val ordered = ordered(videos)
        val index = ordered.indexOfFirst { it.season == season && it.episode == episode }
        if (index < 0) return null
        return ordered.getOrNull(index + 1)
    }

    /** Every real episode, ascending by season then episode. Specials are dropped. */
    fun ordered(videos: List<Episode>): List<Episode> = videos
        .filter { it.season > 0 }
        .sortedWith(compareBy(Episode::season, Episode::episode))

    /** "S2 E4 · Honeydew", or "S2 E4" when the episode has no title (§11.16). */
    fun subtitle(season: Int?, episode: Int?, title: String?): String {
        val number = when {
            season == null || episode == null -> null
            else -> "S$season E$episode"
        }
        val name = title?.trim()?.takeIf(String::isNotEmpty)
        return listOfNotNull(number, name).joinToString(" · ")
    }
}
