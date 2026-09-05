package com.fourseveneightnine.phone

import com.fourseveneightnine.contract.DiscoverItem

/** One horizontal row on Discover: a heading and the titles under it. */
data class DiscoverShelf(val title: String, val items: List<DiscoverItem>)

/**
 * Turns a flat catalog into the editorial shelves Discover shows.
 *
 * Discover used to be one long list of rows, which is the shape a catalog arrives in rather than a
 * shape anyone wants to browse. The iOS app groups the same data into shelves, so this does too.
 *
 * Pure Kotlin with no Compose or Android imports, so plain unit tests cover the ordering rules
 * without an emulator.
 *
 * Every comparison ends in a tiebreak on `id`. Without one, two titles with the same rating could
 * swap places between reads and the shelf would appear to shuffle itself while the viewer looked
 * at it.
 */
object DiscoverShelfPolicy {

    /** Longer than a thumb wants to swipe, and long enough not to feel thin. */
    const val MAX_PER_SHELF = 20

    /** Below this a shelf reads as an accident rather than a group. */
    const val MIN_PER_SHELF = 4

    /** Past this the page becomes a list of headings. */
    const val MAX_GENRE_SHELVES = 8

    const val JUST_LANDED = "Just landed"
    const val HIGHLY_RATED = "Highly rated"

    fun shelves(items: List<DiscoverItem>): List<DiscoverShelf> {
        if (items.isEmpty()) return emptyList()
        val built = mutableListOf<DiscoverShelf>()

        justLanded(items)?.let(built::add)
        highlyRated(items)?.let(built::add)
        built += genreShelves(items)

        return built
    }

    /**
     * The newest year the catalog actually contains, not the calendar year.
     *
     * Asking the clock would empty this shelf every January until the catalog caught up, and would
     * make the result depend on when it ran, which no test could pin down.
     */
    private fun justLanded(items: List<DiscoverItem>): DiscoverShelf? {
        val newest = items.mapNotNull(DiscoverItem::year).maxOrNull() ?: return null
        val recent = items.filter { it.year == newest }
        return shelfOrNull(JUST_LANDED, recent)
    }

    private fun highlyRated(items: List<DiscoverItem>): DiscoverShelf? =
        shelfOrNull(HIGHLY_RATED, items.filter { it.rating != null })

    private fun genreShelves(items: List<DiscoverItem>): List<DiscoverShelf> {
        val counts = mutableMapOf<String, Int>()
        for (item in items) {
            for (genre in item.genres) {
                val name = genre.trim()
                if (name.isNotEmpty()) counts[name] = (counts[name] ?: 0) + 1
            }
        }
        return counts.entries
            // Commonest first, then by name, so the page keeps the same order every time.
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .asSequence()
            .mapNotNull { (genre, _) ->
                shelfOrNull(genre, items.filter { item -> item.genres.any { it.trim() == genre } })
            }
            .take(MAX_GENRE_SHELVES)
            .toList()
    }

    /** A shelf, or null when too few titles qualify to make one worth showing. */
    private fun shelfOrNull(title: String, candidates: List<DiscoverItem>): DiscoverShelf? {
        if (candidates.size < MIN_PER_SHELF) return null
        return DiscoverShelf(title, candidates.sortedWith(bestFirst).take(MAX_PER_SHELF))
    }

    /**
     * Best first: highest rating, then newest, then id.
     *
     * An unrated title sorts below a rated one rather than being dropped. The catalog does not
     * always carry a rating, and a missing number is not evidence of a bad film.
     */
    private val bestFirst: Comparator<DiscoverItem> =
        compareByDescending<DiscoverItem> { it.rating ?: Double.NEGATIVE_INFINITY }
            .thenByDescending { it.year ?: Int.MIN_VALUE }
            .thenBy { it.id }
}
