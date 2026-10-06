package com.fourseveneightnine.tv.client

import com.fourseveneightnine.tv.catalog.TVLetterboxdShelf
import com.fourseveneightnine.tv.catalog.TVTamilMVCatalogItem
import com.fourseveneightnine.tv.catalog.TVTamilMVCatalogSnapshot
import com.fourseveneightnine.tv.client.data.catalog.ShelfKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyCatalogBridgeTest {
    @Test
    fun `current phone transfer maps every private shelf and all card metadata`() {
        val movie = item("movie-id", "Movie")
        val episode = item("series-id", "Episode", mediaType = "series", season = 2, episode = 3)
        val snapshot = snapshot(
            popular = listOf(movie),
            recent = listOf(episode),
            letterboxd = listOf(item("aggregate", "Compatibility aggregate")),
            friends = listOf(item("friend", "Friend pick")),
            letterboxdShelves = listOf(
                TVLetterboxdShelf("letterboxd:owner:favorites", "Favorites", listOf(movie)),
                TVLetterboxdShelf("letterboxd:owner:watchlist", "Watchlist", listOf(episode)),
            ),
        )

        val shelves = snapshot.toShelves()

        assertEquals(
            listOf(
                ShelfKind.TAMILMV_POPULAR,
                ShelfKind.TAMILMV_RECENT,
                ShelfKind.LETTERBOXD,
                ShelfKind.LETTERBOXD,
                ShelfKind.LETTERBOXD_FRIENDS,
            ),
            shelves.map { it.kind },
        )
        assertEquals(
            listOf(
                "tamilmv:popular",
                "tamilmv:recent",
                "letterboxd:owner:favorites",
                "letterboxd:owner:watchlist",
                "letterboxd:friends",
            ),
            shelves.map { it.id },
        )
        assertFalse(shelves.any { it.id == "letterboxd:legacy" })
        assertEquals("series-id:s2:e3", shelves[1].items.single().canonicalId)
        with(shelves.first().items.single()) {
            assertEquals("tt1234567", imdbId)
            assertEquals(123, tmdbId)
            assertEquals("https://images.example/poster.jpg", posterUrl)
            assertEquals("https://images.example/backdrop.jpg", backdropUrl)
            assertEquals("Full overview", overview)
            assertEquals(listOf("Drama", "Thriller"), genres)
            assertEquals(2026, year)
        }
        assertTrue(shelves.all { it.generation == "phone-g2" && it.generatedAtMillis == 200L && it.complete })
    }

    @Test
    fun `older phone transfer retains its flat Letterboxd compatibility shelf`() {
        val shelves = snapshot(
            letterboxd = listOf(item("listed", "Listed")),
        ).toShelves()

        assertEquals(listOf("letterboxd:legacy"), shelves.map { it.id })
        assertEquals("Listed", shelves.single().items.single().title)
    }

    @Test
    fun `phone transfer preserves a list name when the bounded envelope carries no items`() {
        val shelves = snapshot(
            popular = listOf(item("popular", "Popular")),
            letterboxdShelves = listOf(TVLetterboxdShelf("letterboxd:owner:empty", "Saved name", emptyList())),
        ).toShelves()

        assertEquals("Saved name", shelves.last().title)
        assertTrue(shelves.last().items.isEmpty())
    }

    private fun snapshot(
        popular: List<TVTamilMVCatalogItem> = emptyList(),
        recent: List<TVTamilMVCatalogItem> = emptyList(),
        letterboxd: List<TVTamilMVCatalogItem> = emptyList(),
        friends: List<TVTamilMVCatalogItem> = emptyList(),
        letterboxdShelves: List<TVLetterboxdShelf> = emptyList(),
    ) = TVTamilMVCatalogSnapshot(
        generation = "phone-g2",
        generatedAtMillis = 200L,
        cachedAtMillis = 201L,
        popular = popular,
        recent = recent,
        letterboxd = letterboxd,
        friends = friends,
        letterboxdShelves = letterboxdShelves,
    )

    private fun item(
        id: String,
        title: String,
        mediaType: String = "movie",
        season: Int? = null,
        episode: Int? = null,
    ) = TVTamilMVCatalogItem(
        id = id,
        mediaType = mediaType,
        title = title,
        imdbID = "tt1234567",
        tmdbID = 123,
        posterURL = "https://images.example/poster.jpg",
        backdropURL = "https://images.example/backdrop.jpg",
        overview = "Full overview",
        year = 2026,
        genres = listOf("Drama", "Thriller"),
        season = season,
        episode = episode,
    )
}
