package com.fourseveneightnine.tv.ui

import com.fourseveneightnine.tv.catalog.TVTamilMVCatalogItem

/**
 * Resolves a mirrored Continue-Watching row back to the metadata identity the TV addons need.
 *
 * The phone intentionally does not persist resolver/CDN URLs: those links expire and may point at
 * a phone-local stream. Continue rows therefore arrive with a title and resume clock, while the TV
 * uses its verified catalog snapshot to recover the stable IMDb/TMDB identity before asking addons
 * for a fresh playable URL.
 */
internal object ContinuePlaybackPolicy {
    fun catalogItem(
        recent: RecentItem,
        candidates: List<TVTamilMVCatalogItem>,
    ): TVTamilMVCatalogItem? {
        val key = normalizedTitle(recent.title)
        if (key.isEmpty()) return null
        val matches = candidates.filter { normalizedTitle(it.title) == key }
        if (matches.isEmpty()) return null
        val episode = recent.subtitle.orEmpty().contains(EPISODE_MARKER)
        return matches.firstOrNull { it.mediaType == if (episode) "series" else "movie" }
            ?: matches.firstOrNull()
    }

    internal fun normalizedTitle(value: String): String = value
        .lowercase()
        .replace(Regex("[^a-z0-9]"), " ")
        .replace(Regex("\\b(19|20)\\d{2}\\b"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private val EPISODE_MARKER = Regex("\\bS\\d+\\b|\\bE\\d+\\b", RegexOption.IGNORE_CASE)
}
