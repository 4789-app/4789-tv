package com.fourseveneightnine.phone

import com.fourseveneightnine.contract.DiscoverItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverShelfPolicyTest {

    @Test
    fun anEmptyCatalogMakesNoShelves() {
        assertTrue(DiscoverShelfPolicy.shelves(emptyList()).isEmpty())
    }

    @Test
    fun aShelfNeedsEnoughTitlesToBeWorthShowing() {
        val thin = (1..DiscoverShelfPolicy.MIN_PER_SHELF - 1).map { item(id = "t$it", genres = listOf("Drama")) }

        val shelves = DiscoverShelfPolicy.shelves(thin)

        assertNull(shelves.firstOrNull { it.title == "Drama" })
    }

    @Test
    fun justLandedUsesTheNewestYearInTheCatalogNotTheCalendar() {
        // Every title is in the past. A shelf keyed on today's date would be empty forever.
        val items = (1..4).map { item(id = "old$it", year = 1998) } +
            (1..4).map { item(id = "new$it", year = 2011) }

        val landed = DiscoverShelfPolicy.shelves(items).first { it.title == DiscoverShelfPolicy.JUST_LANDED }

        assertEquals(4, landed.items.size)
        assertTrue(landed.items.all { it.year == 2011 })
    }

    @Test
    fun titlesWithoutARatingStillAppearButSortBelowRatedOnes() {
        val items = listOf(
            item(id = "unrated", rating = null, genres = listOf("Drama")),
            item(id = "low", rating = 4.0, genres = listOf("Drama")),
            item(id = "high", rating = 9.0, genres = listOf("Drama")),
            item(id = "mid", rating = 6.0, genres = listOf("Drama")),
        )

        val drama = DiscoverShelfPolicy.shelves(items).first { it.title == "Drama" }

        assertEquals(listOf("high", "mid", "low", "unrated"), drama.items.map(DiscoverItem::id))
    }

    @Test
    fun theSameCatalogAlwaysProducesTheSameOrder() {
        // Two titles share a rating and a year. Without a final tiebreak the shelf could swap them
        // between reads and appear to shuffle while the viewer is looking at it.
        val items = listOf(
            item(id = "bbb", rating = 7.0, year = 2020, genres = listOf("Drama")),
            item(id = "aaa", rating = 7.0, year = 2020, genres = listOf("Drama")),
            item(id = "ccc", rating = 7.0, year = 2020, genres = listOf("Drama")),
            item(id = "ddd", rating = 7.0, year = 2020, genres = listOf("Drama")),
        )

        val first = DiscoverShelfPolicy.shelves(items).first { it.title == "Drama" }
        val second = DiscoverShelfPolicy.shelves(items.shuffled()).first { it.title == "Drama" }

        assertEquals(first.items.map(DiscoverItem::id), second.items.map(DiscoverItem::id))
        assertEquals(listOf("aaa", "bbb", "ccc", "ddd"), first.items.map(DiscoverItem::id))
    }

    @Test
    fun theCommonestGenresComeFirstAndThePageStaysShort() {
        val items = buildList {
            // Ten distinct genres, each with enough titles to qualify. Only the cap should survive.
            for (g in 1..10) {
                val name = "Genre%02d".format(g)
                // Give lower-numbered genres more titles, so frequency order is checkable.
                repeat(DiscoverShelfPolicy.MIN_PER_SHELF + (11 - g)) {
                    add(item(id = "g$g-$it", genres = listOf(name)))
                }
            }
        }

        val genreShelves = DiscoverShelfPolicy.shelves(items)
            .filterNot { it.title == DiscoverShelfPolicy.JUST_LANDED }
            .filterNot { it.title == DiscoverShelfPolicy.HIGHLY_RATED }

        assertEquals(DiscoverShelfPolicy.MAX_GENRE_SHELVES, genreShelves.size)
        assertEquals("Genre01", genreShelves.first().title)
    }

    @Test
    fun aShelfIsCappedSoTheRowStaysSwipeable() {
        val items = (1..60).map { item(id = "t%03d".format(it), genres = listOf("Drama")) }

        val drama = DiscoverShelfPolicy.shelves(items).first { it.title == "Drama" }

        assertEquals(DiscoverShelfPolicy.MAX_PER_SHELF, drama.items.size)
    }

    @Test
    fun highlyRatedSkipsTitlesThatCarryNoRating() {
        val items = (1..6).map { item(id = "rated$it", rating = it.toDouble()) } +
            (1..6).map { item(id = "unrated$it", rating = null) }

        val rated = DiscoverShelfPolicy.shelves(items).first { it.title == DiscoverShelfPolicy.HIGHLY_RATED }

        assertEquals(6, rated.items.size)
        assertTrue(rated.items.all { it.rating != null })
        assertNotNull(rated.items.first().rating)
    }

    private fun item(
        id: String,
        year: Int? = 2020,
        rating: Double? = 7.0,
        genres: List<String> = emptyList(),
    ) = DiscoverItem(
        id = id,
        type = "movie",
        title = "Title $id",
        year = year,
        rating = rating,
        genres = genres,
    )
}
