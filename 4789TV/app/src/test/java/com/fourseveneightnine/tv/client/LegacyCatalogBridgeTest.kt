package com.fourseveneightnine.tv.client

import com.fourseveneightnine.tv.catalog.TVLetterboxdShelf
import com.fourseveneightnine.tv.catalog.TVTamilMVCatalogItem
import com.fourseveneightnine.tv.catalog.TVTamilMVCatalogSnapshot
import com.fourseveneightnine.tv.catalog.TVTamilMVCatalogImportPolicy
import com.fourseveneightnine.tv.client.data.catalog.ShelfKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyCatalogBridgeTest {
    @Test
    fun `newer signed list wins over an older full phone mirror with another ID format`() {
        val signed = com.fourseveneightnine.tv.client.data.catalog.Shelf(
            "letterboxd:liked:profile:curator/releases", "Releases", ShelfKind.LETTERBOXD,
            listOf(com.fourseveneightnine.tv.client.data.catalog.CatalogItem("tmdb:2", "movie", "New")), "new", 300)
        val phone = snapshot(letterboxdShelves = listOf(TVLetterboxdShelf("letterboxd:liked:curator/releases", "Releases", listOf(item("tmdb:1", "Old")), generatedAtMillis = 100)))
        val merged = mergeCollectionShelves(listOf(signed), phone.toPhoneLetterboxdCatalog())
        assertEquals(listOf(signed), merged)
    }

    @Test
    fun `full phone mirror keeps its source date instead of dating every list at transfer time`() {
        val phone = snapshot(letterboxdShelves = listOf(TVLetterboxdShelf("letterboxd:liked:curator/releases", "Releases", listOf(item("tmdb:1", "Saved")), generatedAtMillis = 100)))
        assertEquals(100L, phone.toPhoneLetterboxdCatalog().shelves.single().generatedAtMillis)
    }

    @Test
    fun `unimported phone list keeps a preview until a successful import`() {
        val id = "letterboxd:liked:curator/list"
        val preview = com.fourseveneightnine.tv.client.data.catalog.Shelf(id, "List", ShelfKind.LETTERBOXD,
            listOf(com.fourseveneightnine.tv.client.data.catalog.CatalogItem("tmdb:1", "movie", "Saved")),
            "iphone-300", 300)
        fun mirror(stamp: Long?) = snapshot(letterboxdShelves = listOf(
            TVLetterboxdShelf(id, "List", emptyList(), generatedAtMillis = stamp)
        )).copy(generation = "iphone-letterboxd-200")

        assertTrue(mergeCollectionShelves(emptyList(), mirror(0).toPhoneLetterboxdCatalog()).single().awaitingPhoneImport)
        assertEquals(preview, mergeCollectionShelves(listOf(preview), mirror(0).toPhoneLetterboxdCatalog()).single())
        val importedEmpty = mergeCollectionShelves(listOf(preview), mirror(100).toPhoneLetterboxdCatalog()).single()
        assertTrue(importedEmpty.items.isEmpty())
        assertFalse(importedEmpty.awaitingPhoneImport)
        assertFalse(mergeCollectionShelves(emptyList(), mirror(null).toPhoneLetterboxdCatalog()).single().awaitingPhoneImport)
    }

    @Test
    fun `authenticated empty full mirror clears removed liked lists`() {
        val old = com.fourseveneightnine.tv.client.data.catalog.Shelf("letterboxd:liked:profile:curator/releases", "Old", ShelfKind.LETTERBOXD,
            listOf(com.fourseveneightnine.tv.client.data.catalog.CatalogItem("tmdb:1", "movie", "Old")), "old", 100)
        val empty = snapshot()
        assertTrue(TVTamilMVCatalogImportPolicy.validate(empty, letterboxdOnly = true).letterboxdShelves.isEmpty())
        assertTrue(mergeCollectionShelves(listOf(old), empty.toPhoneLetterboxdCatalog()).isEmpty())
    }

    @Test
    fun `full mirror clears a removed preview even when it was transferred later`() {
        val preview = com.fourseveneightnine.tv.client.data.catalog.Shelf(
            "letterboxd:liked:curator/releases", "Removed", ShelfKind.LETTERBOXD,
            listOf(com.fourseveneightnine.tv.client.data.catalog.CatalogItem("tmdb:1", "movie", "Film")),
            "iphone-300", 300)
        assertTrue(mergeCollectionShelves(listOf(preview), snapshot().toPhoneLetterboxdCatalog()).isEmpty())
    }

    @Test
    fun `a newer full phone source copy is not treated as a bounded preview`() {
        val complete = com.fourseveneightnine.tv.client.data.catalog.Shelf(
            "letterboxd:liked:curator/releases", "Releases", ShelfKind.LETTERBOXD,
            listOf(com.fourseveneightnine.tv.client.data.catalog.CatalogItem("tmdb:2", "movie", "New")),
            "iphone-letterboxd-300", 300)
        val older = snapshot(letterboxdShelves = listOf(TVLetterboxdShelf(
            complete.id, complete.title, listOf(item("tmdb:1", "Old")), generatedAtMillis = 100)))
        assertEquals(listOf(complete), mergeCollectionShelves(listOf(complete), older.toPhoneLetterboxdCatalog()))
    }

    @Test
    fun `a saved special does not reject the complete paired catalog`() {
        val special = item("saved:library:special", "Special", mediaType = "series", season = 0, episode = 0)
        assertEquals(special, TVTamilMVCatalogImportPolicy.validate(snapshot(recent = listOf(special))).recent.single())
    }
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

    @Test
    fun `unrelated snapshot updates reuse normalized phone items and visibility together`() {
        val id = "letterboxd:liked:curator/list"
        val phone = snapshot(letterboxdShelves = listOf(
            TVLetterboxdShelf(id, "List", listOf(item("tmdb:1", "Saved")), profiles = listOf("owner")),
        )).toPhoneLetterboxdCatalog()
        val first = mergeCollectionCatalog(emptyList(), phone)
        val unrelated = com.fourseveneightnine.tv.client.data.catalog.Shelf(
            "library", "Library", ShelfKind.LIBRARY, emptyList(), "new", 300)
        val second = mergeCollectionCatalog(listOf(unrelated), phone)
        assertSame(first.shelves.single().items.single(), second.shelves.last().items.single())
        assertSame(phone.profilesByList, first.profilesByList)
        assertSame(phone.profilesByList, second.profilesByList)
        assertEquals(listOf("owner"), second.profilesByList[letterboxdListKey(id)])
        assertTrue(mergeCollectionCatalog(emptyList(), null).profilesByList.isEmpty())
    }

    @Test
    fun `same generation and date still replace changed mirror items and profiles`() {
        fun mirror(title: String, profile: String) = snapshot(letterboxdShelves = listOf(
            TVLetterboxdShelf("letterboxd:liked:curator/list", "List", listOf(item("tmdb:1", title)),
                profiles = listOf(profile)),
        )).toPhoneLetterboxdCatalog()
        val first = mergeCollectionCatalog(emptyList(), mirror("Old", "owner"))
        val updated = mergeCollectionCatalog(first.shelves, mirror("Changed", "guest"))
        assertEquals("Changed", updated.shelves.single().items.single().title)
        assertEquals(listOf("guest"), updated.profilesByList.values.single())
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
