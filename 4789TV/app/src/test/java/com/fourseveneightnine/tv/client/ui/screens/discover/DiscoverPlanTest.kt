package com.fourseveneightnine.tv.client.ui.screens.discover

import com.fourseveneightnine.tv.client.data.addons.Addon
import com.fourseveneightnine.tv.client.data.addons.AddonCatalog
import com.fourseveneightnine.tv.client.data.addons.AddonManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverPlanTest {

    private fun addon(name: String, url: String, vararg catalogs: AddonCatalog) = Addon(
        name = name,
        manifestURL = url,
        manifest = AddonManifest(id = name, name = name, catalogs = catalogs.toList()),
    )

    private val netflix = addon(
        "Netflix",
        "https://one.example/manifest.json",
        AddonCatalog("movie", "top", "Top movies", genres = listOf("Thriller", "Comedy")),
        AddonCatalog("series", "top", "Top series"),
    )
    private val anime = addon(
        "Kitsu",
        "https://two.example/manifest.json",
        AddonCatalog("anime", "trending", "Trending"),
    )

    private val choices = DiscoverPlan.choices(
        listOf(
            netflix to netflix.manifest!!.catalogs[0],
            netflix to netflix.manifest!!.catalogs[1],
            anime to anime.manifest!!.catalogs[0],
            // a duplicate the manifest declared twice
            netflix to netflix.manifest!!.catalogs[0],
        ),
    )

    @Test
    fun `choices keep registry order and drop duplicates`() {
        assertEquals(3, choices.size)
        assertEquals(listOf("Top movies", "Top series", "Trending"), choices.map { it.catalogName })
        assertEquals(listOf("Netflix", "Netflix", "Kitsu"), choices.map { it.addonName })
    }

    @Test
    fun `a type chip shows only the catalogs of that type`() {
        assertEquals(listOf("Top movies"), DiscoverPlan.forType(choices, DiscoverType.Movies).map { it.catalogName })
        assertEquals(listOf("Top series"), DiscoverPlan.forType(choices, DiscoverType.Series).map { it.catalogName })
        assertEquals(listOf("Trending"), DiscoverPlan.forType(choices, DiscoverType.Anime).map { it.catalogName })
    }

    @Test
    fun `the type chip opens on the first catalog it has, or none`() {
        assertEquals(choices[0].key, DiscoverPlan.defaultCatalogKey(choices, DiscoverType.Movies))
        assertNull(DiscoverPlan.defaultCatalogKey(emptyList(), DiscoverType.Movies))
    }

    @Test
    fun `the genre is kept when the new catalog has it and cleared when it does not`() {
        assertEquals("Thriller", DiscoverPlan.retainGenre("Thriller", listOf("Thriller", "Comedy")))
        assertEquals("Thriller", DiscoverPlan.retainGenre("Thriller", listOf("thriller")))
        assertNull(DiscoverPlan.retainGenre("Thriller", listOf("Drama")))
        assertNull(DiscoverPlan.retainGenre("Thriller", emptyList()))
        assertNull(DiscoverPlan.retainGenre(null, listOf("Thriller")))
    }

    // ------------------------------------------------------------------ paging

    @Test
    fun `the next page is asked for once the focused row passes seventy percent`() {
        // 60 items is 10 rows of 6. The seventh row is 70% of the way in.
        assertFalse(DiscoverPlan.shouldLoadMore(focusedIndex = 0, loadedCount = 60)) // row 0
        assertFalse(DiscoverPlan.shouldLoadMore(focusedIndex = 35, loadedCount = 60)) // row 5
        assertTrue(DiscoverPlan.shouldLoadMore(focusedIndex = 36, loadedCount = 60)) // row 6
        assertTrue(DiscoverPlan.shouldLoadMore(focusedIndex = 59, loadedCount = 60)) // row 9
    }

    @Test
    fun `an empty grid never asks for a page`() {
        assertFalse(DiscoverPlan.shouldLoadMore(focusedIndex = 0, loadedCount = 0))
        assertFalse(DiscoverPlan.shouldLoadMore(focusedIndex = -1, loadedCount = 60))
    }

    @Test
    fun `a partly filled last row still counts as a row`() {
        // 7 items is 2 rows. Row 1 is already past 70% of 2 rows.
        assertTrue(DiscoverPlan.shouldLoadMore(focusedIndex = 6, loadedCount = 7))
        assertFalse(DiscoverPlan.shouldLoadMore(focusedIndex = 0, loadedCount = 7))
    }

    @Test
    fun `paging waits while the first page is still on the wire`() {
        // 12 items is 2 rows, so row 1 is already past the line; the guard is the caller's.
        assertTrue(DiscoverPlan.shouldLoadMore(focusedIndex = 6, loadedCount = 12))
        assertFalse(DiscoverPlan.shouldLoadMore(focusedIndex = 5, loadedCount = 12))
    }

    // ------------------------------------------------------------------ the request window

    @Test
    fun `only visible cells and one row of look-ahead may fetch`() {
        // 60 items, rows 0 to 2 on screen: cells 0 to 17 plus the next row, so 0 to 23.
        assertEquals(0..23, DiscoverPlan.requestWindow(firstVisible = 0, lastVisible = 17, itemCount = 60))
        // Scrolled down: nothing above the window is asked for again.
        assertEquals(18..41, DiscoverPlan.requestWindow(firstVisible = 18, lastVisible = 35, itemCount = 60))
    }

    @Test
    fun `the window never runs past the last cell or below the first`() {
        assertEquals(54..59, DiscoverPlan.requestWindow(firstVisible = 54, lastVisible = 59, itemCount = 60))
        assertEquals(0..5, DiscoverPlan.requestWindow(firstVisible = -4, lastVisible = 3, itemCount = 6))
        assertTrue(DiscoverPlan.requestWindow(firstVisible = 0, lastVisible = 5, itemCount = 0).isEmpty())
    }

    @Test
    fun `a cell past the look-ahead does not fetch`() {
        val window = DiscoverPlan.requestWindow(firstVisible = 0, lastVisible = 11, itemCount = 120)
        assertTrue(0 in window)
        assertTrue(17 in window)
        assertFalse(18 in window)
    }

    // ------------------------------------------------------------------ geometry

    @Test
    fun `the skeleton and the real cell sit at the same row pitch`() {
        // The grid used to jump 20 px when the first page landed, because the two were written by
        // hand in two files. Both now come from these numbers.
        assertEquals(412, DiscoverPlan.CELL_BLOCK_HEIGHT_DP)
        assertEquals(440, DiscoverPlan.ROW_PITCH_DP)
        assertEquals(
            DiscoverPlan.ROW_PITCH_DP,
            DiscoverPlan.POSTER_HEIGHT_DP + DiscoverPlan.SKELETON_ROW_SPACER_DP,
        )
    }

    @Test
    fun `the grid ends at the safe bottom, collapsed or not`() {
        listOf(true, false).forEach { collapsed ->
            assertEquals(
                DiscoverPlan.CONTENT_HEIGHT_DP,
                DiscoverPlan.gridTopDp(collapsed) + DiscoverPlan.gridHeightDp(collapsed),
            )
        }
        assertEquals(162, DiscoverPlan.gridTopDp(collapsed = false))
        assertEquals(72, DiscoverPlan.gridTopDp(collapsed = true))
        // Collapsing the band gives the grid the 90 px the band gave up, and nothing more.
        assertEquals(90, DiscoverPlan.gridHeightDp(true) - DiscoverPlan.gridHeightDp(false))
    }

    // ------------------------------------------------------------------ the count line

    @Test
    fun `the count line says what is known and nothing else`() {
        assertEquals("Loading", DiscoverPlan.countLine(loading = true, itemCount = 0, hasMore = false))
        assertEquals("1,284 titles", DiscoverPlan.countLine(loading = false, itemCount = 1284, hasMore = false))
        assertEquals("1 title", DiscoverPlan.countLine(loading = false, itemCount = 1, hasMore = false))
        assertEquals("847 titles", DiscoverPlan.countLine(loading = false, itemCount = 847, hasMore = false))
        // A catalog with another page has no total to show, so the line is dropped, not guessed.
        assertNull(DiscoverPlan.countLine(loading = false, itemCount = 100, hasMore = true))
        assertNull(DiscoverPlan.countLine(loading = false, itemCount = 0, hasMore = false))
    }

    @Test
    fun `an empty filtered catalog gives its recovery action focus`() {
        assertTrue(
            DiscoverPlan.shouldFocusEmptyAction(
                settingsLoaded = true,
                loading = false,
                failed = false,
                itemCount = 0,
                hasGenre = true,
            ),
        )
    }

    @Test
    fun `empty action never steals focus before it is the useful recovery`() {
        assertFalse(DiscoverPlan.shouldFocusEmptyAction(false, false, false, 0, true))
        assertFalse(DiscoverPlan.shouldFocusEmptyAction(true, true, false, 0, true))
        assertFalse(DiscoverPlan.shouldFocusEmptyAction(true, false, true, 0, true))
        assertFalse(DiscoverPlan.shouldFocusEmptyAction(true, false, false, 1, true))
        assertFalse(DiscoverPlan.shouldFocusEmptyAction(true, false, false, 0, false))
    }

    // ------------------------------------------------------------------ the Catalog panel

    @Test
    fun `the panel lists one group header per add-on`() {
        val rows = DiscoverPlan.panelRows(choices)
        assertEquals(
            listOf("group:Netflix", "choice:${choices[0].key}", "choice:${choices[1].key}", "group:Kitsu", "choice:${choices[2].key}"),
            rows.map { it.key },
        )
    }

    @Test
    fun `the panel opens on the current choice, or on the first row`() {
        val rows = DiscoverPlan.panelRows(choices)
        assertEquals(2, DiscoverPlan.panelRowIndex(rows, choices[1].key))
        assertEquals(0, DiscoverPlan.panelRowIndex(rows, "not-a-catalog"))
        assertEquals(0, DiscoverPlan.panelRowIndex(rows, null))
    }

    // ------------------------------------------------------------------ the collapsed strip

    @Test
    fun `the collapsed strip names every chosen value and drops the empty ones`() {
        assertEquals(
            "Discover · Movies · Netflix · Thrillers",
            DiscoverPlan.summaryStrip(DiscoverType.Movies, "Netflix", "Thrillers"),
        )
        assertEquals(
            "Discover · Series · Top series",
            DiscoverPlan.summaryStrip(DiscoverType.Series, "Top series", null),
        )
        assertEquals("Discover · Anime", DiscoverPlan.summaryStrip(DiscoverType.Anime, null, null))
    }

    @Test
    fun `the strip draws values, words and separators in three inks`() {
        val parts = DiscoverPlan.summaryParts(DiscoverType.Movies, "Netflix", "Thrillers", "1,284 titles")
        assertEquals(
            listOf("Discover", " · ", "Movies", " · ", "Netflix", " · ", "Thrillers", " · ", "1,284 titles"),
            parts.map { it.text },
        )
        assertEquals(StripInk.Word, parts.first().ink)
        assertEquals(listOf(StripInk.Separator, StripInk.Value), parts.drop(1).take(2).map { it.ink })
        assertEquals(StripInk.Value, parts.last().ink)
    }

    @Test
    fun `the strip drops what was never chosen`() {
        val parts = DiscoverPlan.summaryParts(DiscoverType.Anime, null, null, null)
        assertEquals(listOf("Discover", " · ", "Anime"), parts.map { it.text })
    }

    @Test
    fun `chip labels truncate where the spec says`() {
        assertEquals("Netflix", truncate("Netflix", 28))
        assertTrue(truncate("A genre name far too long for one chip", 20).length <= 20)
        assertTrue(truncate("A genre name far too long for one chip", 20).endsWith("…"))
    }
}
