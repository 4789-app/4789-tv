package com.fourseveneightnine.tv.client.ui.screens.home

import com.fourseveneightnine.tv.client.data.addons.Addon
import com.fourseveneightnine.tv.client.data.addons.AddonCatalog
import com.fourseveneightnine.tv.client.data.addons.AddonManifest
import com.fourseveneightnine.tv.client.data.library.ContinueItem
import com.fourseveneightnine.tv.client.data.library.RowOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePlanTest {

    private fun addon(name: String, url: String, vararg catalogs: AddonCatalog) = Addon(
        name = name,
        manifestURL = url,
        manifest = AddonManifest(id = name, name = name, catalogs = catalogs.toList()),
    )

    private fun catalog(id: String, name: String = id, type: String = "movie") =
        AddonCatalog(type = type, id = id, name = name)

    private fun card(id: String) = HomeCard(id = id, type = "movie", title = id)

    // ------------------------------------------------------------------ row order

    @Test
    fun `row order is continue then all catalogs then catalogs then non-empty collections then shelves`() {
        val rows = HomePlan.rows(
            continueItems = listOf(continueCard("tt1")),
            folders = listOf(HomeFolderCard(1L, "Sunday night", 3, "#E9A23B", emptyList())),
            catalogRows = listOf(HomeRow.Posters("catalog:a", "Netflix", listOf(card("a")))),
            shelfRows = listOf(HomeRow.Posters("shelf:tamil", "Tamil MV Popular", listOf(card("b")))),
        )

        assertEquals(
            listOf(HomeRow.Continue.KEY, "row:all-catalogs", "catalog:a", HomeRow.Collections.KEY, "shelf:tamil"),
            rows.map { it.key },
        )
    }

    @Test
    fun `continue and collections keep their slots while they are empty`() {
        val rows = HomePlan.rows(
            continueItems = emptyList(),
            folders = emptyList(),
            catalogRows = listOf(HomeRow.Posters("catalog:a", "Netflix", listOf(card("a")))),
            shelfRows = emptyList(),
        )

        // The keys are reserved from the first publish, so a late Room emission grows a row in
        // place instead of inserting one above everything (F04).
        assertEquals(
            listOf(HomeRow.Continue.KEY, "row:all-catalogs", "catalog:a", HomeRow.Collections.KEY),
            rows.map { it.key },
        )
        // Both are still empty, so neither draws and neither can take the ring (spec §3.9.9).
        assertEquals(0, rows[0].itemCount)
        assertEquals(0, rows[3].itemCount)
        assertEquals(listOf("row:all-catalogs", "catalog:a"), rows.filter { it.itemCount > 0 }.map { it.key })
    }

    @Test
    fun `an empty pinned collection does not become the first Home card`() {
        val rows = HomePlan.rows(
            continueItems = emptyList(),
            folders = listOf(HomeFolderCard(1L, "Empty", 0, "#E9A23B", emptyList())),
            catalogRows = emptyList(),
            shelfRows = emptyList(),
        )

        assertEquals(0, (rows.single { it is HomeRow.Collections } as HomeRow.Collections).items.size)
        assertTrue(rows.none { it.itemCount > 0 })
        assertNull(HomePlan.featuredCard(rows))
    }

    @Test
    fun `the first playable card seeds the Home hero before collection focus`() {
        val rows = HomePlan.rows(
            continueItems = emptyList(),
            folders = listOf(HomeFolderCard(1L, "Sunday night", 3, "#E9A23B", emptyList())),
            catalogRows = listOf(HomeRow.Posters("catalog:a", "A", listOf(card("feature")))),
            shelfRows = listOf(HomeRow.Posters("shelf:b", "B", listOf(card("later")))),
        )

        assertEquals("feature", HomePlan.featuredCard(rows)?.id)
    }

    @Test
    fun `a snapshot shelf does not duplicate a healthy live catalog title`() {
        val rows = HomePlan.rows(
            continueItems = emptyList(),
            folders = emptyList(),
            catalogRows = listOf(HomeRow.Posters("catalog:popular", "Popular · Cinemeta", listOf(card("live")))),
            shelfRows = listOf(HomeRow.Posters("shelf:popular", "Popular · Cinemeta", listOf(card("snapshot")))),
        )

        assertEquals(1, rows.count { it.title == "Popular · Cinemeta" })
        assertEquals("catalog:popular", rows.single { it.title == "Popular · Cinemeta" }.key)
    }

    @Test
    fun `same named movie and series catalogs are told apart by type`() {
        val cinemeta = addon(
            "Cinemeta",
            "https://cinemeta.example/manifest.json",
            catalog("popular", "Popular", "movie"),
            catalog("popular", "Popular", "series"),
        )

        val titles = HomePlan.catalogSkeletons(
            listOf(
                cinemeta to catalog("popular", "Popular", "movie"),
                cinemeta to catalog("popular", "Popular", "series"),
            ),
        ).map { it.title }

        assertEquals(listOf("Popular movies", "Popular series"), titles)
    }

    @Test
    fun `two add-ons declaring the same catalog name get told apart`() {
        val one = addon("Torrentio", "https://one.example/manifest.json", catalog("new", "New"))
        val two = addon("MediaFusion", "https://two.example/manifest.json", catalog("new", "New"))

        val titles = HomePlan.catalogSkeletons(listOf(one to catalog("new", "New"), two to catalog("new", "New")))
            .map { it.title }

        assertEquals(listOf("New · Torrentio", "New · MediaFusion"), titles)
    }

    @Test
    fun `a name only one add-on uses is left alone`() {
        val one = addon("Torrentio", "https://one.example/manifest.json", catalog("new", "New"))
        val two = addon("MediaFusion", "https://two.example/manifest.json", catalog("top", "Top"))

        val titles = HomePlan.catalogSkeletons(listOf(one to catalog("new", "New"), two to catalog("top", "Top")))
            .map { it.title }

        assertEquals(listOf("New", "Top"), titles)
    }

    @Test
    fun `catalog skeletons keep the registry order and drop duplicates`() {
        val netflix = addon("Netflix", "https://one.example/manifest.json", catalog("top"), catalog("new"))
        val cinemeta = addon("Cinemeta", "https://two.example/manifest.json", catalog("top"))
        val pairs = listOf(
            netflix to catalog("top"),
            netflix to catalog("new"),
            cinemeta to catalog("top"),
            // the same catalog declared twice by one add-on
            netflix to catalog("top"),
        )

        val skeletons = HomePlan.catalogSkeletons(pairs)

        assertEquals(3, skeletons.size)
        assertEquals(listOf("top", "new", "top"), skeletons.map { it.catalog?.catalogId })
        assertTrue(skeletons.all { it.loading && it.items.isEmpty() })
    }

    @Test
    fun `a stated catalog order sorts matches and appends unmatched catalogs`() {
        val netflix = addon("Netflix", "https://one.example/manifest.json", catalog("top"), catalog("new"))
        val pairs = listOf(netflix to catalog("top"), netflix to catalog("new"))

        val chosen = HomePlan.catalogSkeletons(pairs, catalogOrder = listOf("new"))

        assertEquals(listOf("new", "top"), chosen.map { it.catalog?.catalogId })
    }

    @Test
    fun `a stated catalog order still obeys the Home row cap`() {
        val many = addon("Everything", "https://one.example/manifest.json", *Array(20) { catalog("c$it") })
        val pairs = (0 until 20).map { many to catalog("c$it") }

        assertEquals(3, HomePlan.catalogSkeletons(pairs, catalogOrder = (0 until 20).map { "c$it" }, cap = 3).size)
    }

    @Test
    fun `with no stated order the rows are capped`() {
        val many = addon(
            "Everything",
            "https://one.example/manifest.json",
            *Array(40) { catalog("c$it") },
        )
        val pairs = (0 until 40).map { many to catalog("c$it") }

        assertEquals(HomePlan.CATALOG_ROW_CAP, HomePlan.catalogSkeletons(pairs).size)
        assertEquals(3, HomePlan.catalogSkeletons(pairs, cap = 3).size)
    }

    // ------------------------------------------------------------------ progressive insertion

    @Test
    fun `a result fills its own row and moves nothing else`() {
        val rows = listOf(
            HomeRow.Posters("catalog:a", "A", emptyList(), loading = true),
            HomeRow.Posters("catalog:b", "B", emptyList(), loading = true),
            HomeRow.Posters("catalog:c", "C", emptyList(), loading = true),
        )

        val filled = HomePlan.applyResult(rows, "catalog:b", listOf(card("x"), card("y")))

        assertEquals(listOf("catalog:a", "catalog:b", "catalog:c"), filled.map { it.key })
        assertEquals(2, filled[1].items.size)
        assertTrue(filled[0].loading)
        assertTrue(filled[2].loading)
    }

    @Test
    fun `late results keep the order the plan gave them`() {
        var rows = HomePlan.catalogSkeletons(
            listOf(
                addon("A", "https://a.example/manifest.json", catalog("one")) to catalog("one"),
                addon("B", "https://b.example/manifest.json", catalog("two")) to catalog("two"),
                addon("C", "https://c.example/manifest.json", catalog("three")) to catalog("three"),
            ),
        )
        val keys = rows.map { it.key }

        // They answer back to front, which is what a slow first add-on looks like.
        rows = HomePlan.applyResult(rows, keys[2], listOf(card("c")))
        rows = HomePlan.applyResult(rows, keys[1], listOf(card("b")))
        rows = HomePlan.applyResult(rows, keys[0], listOf(card("a")))

        assertEquals(keys, rows.map { it.key })
        assertEquals(listOf("a", "b", "c"), rows.map { it.items.single().id })
    }

    // ------------------------------------------------------------------ the row-removal policy

    @Test
    fun `a row that answers empty is marked, not dropped from the list`() {
        val rows = listOf(
            HomeRow.Posters("catalog:a", "A", emptyList(), loading = true),
            HomeRow.Posters("catalog:b", "B", emptyList(), loading = true),
        )

        val after = HomePlan.applyResult(rows, "catalog:a", emptyList())

        assertEquals(listOf("catalog:a", "catalog:b"), after.map { it.key })
        assertTrue(after[0].removed)
        assertEquals(false, after[0].loading)
        assertEquals(false, after[1].removed)
    }

    @Test
    fun `a failure marks a row that had nothing and keeps one that did`() {
        val rows = listOf(
            HomeRow.Posters("catalog:a", "A", emptyList(), loading = true),
            HomeRow.Posters("catalog:b", "B", listOf(card("b")), loading = true),
        )

        assertTrue(HomePlan.applyFailure(rows, "catalog:a")[0].removed)
        val keptItems = HomePlan.applyFailure(rows, "catalog:b")[1]
        assertEquals(false, keptItems.removed)
        assertEquals(1, keptItems.items.size)
        assertEquals(false, keptItems.loading)
    }

    @Test
    fun `an empty row stays as a placeholder while the ring is on it or below it`() {
        val rows = listOf(
            HomeRow.Posters("catalog:a", "A", listOf(card("a"))),
            HomeRow.Posters("catalog:b", "B", emptyList(), removed = true),
            HomeRow.Posters("catalog:c", "C", listOf(card("c"))),
        )

        // The ring is below the empty row. Dropping it now would pull row C up one block under
        // the ring, which is spec §3.7's "the removal waits until focus moves above it".
        assertEquals(
            listOf("catalog:a", "catalog:b", "catalog:c"),
            HomePlan.visibleRows(rows, "catalog:c").map { it.key },
        )
    }

    @Test
    fun `an empty row goes once the ring is above it`() {
        val rows = listOf(
            HomeRow.Posters("catalog:a", "A", listOf(card("a"))),
            HomeRow.Posters("catalog:b", "B", emptyList(), removed = true),
            HomeRow.Posters("catalog:c", "C", listOf(card("c"))),
        )

        assertEquals(
            listOf("catalog:a", "catalog:c"),
            HomePlan.visibleRows(rows, "catalog:a").map { it.key },
        )
    }

    @Test
    fun `with nothing focused every empty row goes at once`() {
        val rows = listOf(
            HomeRow.Posters("catalog:a", "A", emptyList(), removed = true),
            HomeRow.Posters("catalog:b", "B", listOf(card("b"))),
        )

        assertEquals(listOf("catalog:b"), HomePlan.visibleRows(rows, null).map { it.key })
        // A key that is no longer in the list reads the same way: there is no ring to protect.
        assertEquals(listOf("catalog:b"), HomePlan.visibleRows(rows, "catalog:gone").map { it.key })
    }

    @Test
    fun `a row still loading is never dropped`() {
        val rows = listOf(
            HomeRow.Posters("catalog:a", "A", listOf(card("a"))),
            HomeRow.Posters("catalog:b", "B", emptyList(), loading = true),
        )

        assertEquals(listOf("catalog:a", "catalog:b"), HomePlan.visibleRows(rows, "catalog:a").map { it.key })
    }

    // ------------------------------------------------------------------ the rescue-focus policy

    @Test
    fun `the rescue net returns to the row that had the ring, not row 1`() {
        assertEquals(7, HomePlan.restoreRow(lastFocusedRow = 7, rowCount = 12))
    }

    @Test
    fun `a band nothing has touched restores to row 1`() {
        assertEquals(0, HomePlan.restoreRow(lastFocusedRow = -1, rowCount = 12))
        assertEquals(0, HomePlan.restoreRow(lastFocusedRow = 0, rowCount = 12))
    }

    @Test
    fun `a row index left over from a longer list is clamped, never thrown`() {
        assertEquals(11, HomePlan.restoreRow(lastFocusedRow = 40, rowCount = 12))
        assertEquals(0, HomePlan.restoreRow(lastFocusedRow = 5, rowCount = 0))
    }

    // ------------------------------------------------------------------ the error state

    @Test
    fun `every catalog answering with nothing is the error state`() {
        val catalogs = listOf(
            HomeRow.Posters("catalog:a", "A", emptyList(), removed = true),
            HomeRow.Posters("catalog:b", "B", emptyList(), removed = true),
        )
        val rows = HomePlan.rows(emptyList(), emptyList(), catalogs, emptyList())

        assertTrue(HomePlan.failed(settled = true, catalogRows = catalogs, rows = rows))
        // Before the first-paint deadline the screen is still loading, not failed.
        assertEquals(false, HomePlan.failed(settled = false, catalogRows = catalogs, rows = rows))
    }

    @Test
    fun `one row with items is not the error state, and neither is having no add-ons`() {
        val catalogs = listOf(
            HomeRow.Posters("catalog:a", "A", emptyList(), removed = true),
            HomeRow.Posters("catalog:b", "B", listOf(card("b"))),
        )

        assertEquals(
            false,
            HomePlan.failed(true, catalogs, HomePlan.rows(emptyList(), emptyList(), catalogs, emptyList())),
        )
        // No add-ons at all is the empty state; it must not read as a failure.
        assertEquals(false, HomePlan.failed(true, emptyList(), HomePlan.rows(emptyList(), emptyList(), emptyList(), emptyList())))
    }

    @Test
    fun `a catalog still in flight is not the error state`() {
        val catalogs = listOf(HomeRow.Posters("catalog:a", "A", emptyList(), loading = true))

        assertEquals(
            false,
            HomePlan.failed(true, catalogs, HomePlan.rows(emptyList(), emptyList(), catalogs, emptyList())),
        )
    }

    // ------------------------------------------------------------------ continue watching text

    private fun continueCard(
        id: String,
        season: Int? = 2,
        episode: Int? = 4,
        positionMs: Long = 38L * 60_000,
        durationMs: Long = 60L * 60_000,
    ) = ContinueItem(
        canonicalId = id,
        mediaType = "series",
        title = "The Bear",
        posterUrl = null,
        backdropUrl = null,
        season = season,
        episode = episode,
        positionMs = positionMs,
        durationMs = durationMs,
        lastActivityMillis = 1L,
        origin = RowOrigin.LOCAL,
    ).toHomeContinueCard()

    @Test
    fun `an episode in progress reads S2 E4 and the minutes left`() {
        val item = ContinueItem(
            canonicalId = "tt1",
            mediaType = "series",
            title = "The Bear",
            posterUrl = null,
            backdropUrl = null,
            season = 2,
            episode = 4,
            positionMs = 8L * 60_000,
            durationMs = 30L * 60_000,
            lastActivityMillis = 1L,
            origin = RowOrigin.LOCAL,
        )

        assertEquals("S2 E4 · 22 min left", HomePlan.continueLine(item))
    }

    @Test
    fun `an unknown duration says only what it knows`() {
        val item = ContinueItem(
            canonicalId = "tt1",
            mediaType = "series",
            title = "The Bear",
            posterUrl = null,
            backdropUrl = null,
            season = 1,
            episode = 2,
            positionMs = 60_000,
            durationMs = 0L,
            lastActivityMillis = 1L,
            origin = RowOrigin.LOCAL,
        )

        assertEquals("S1 E2", HomePlan.continueLine(item))
        assertNull(HomePlan.remainingText(0L, 0L))
    }

    @Test
    fun `over an hour left reads in hours and minutes`() {
        assertEquals("1h 22m left", HomePlan.remainingText(82L * 60_000, 180L * 60_000))
        assertEquals("2h left", HomePlan.remainingText(120L * 60_000, 180L * 60_000))
    }

    // ------------------------------------------------------------------ hero

    @Test
    fun `the hero button says what it will do`() {
        assertEquals("Play", HomePlan.heroAction(card("a"), null))
        assertEquals("Resume S2 E4", HomePlan.heroAction(card("a"), continueCard("tt1")))
        assertEquals(
            "Resume 1h 12m in",
            HomePlan.heroAction(card("a"), continueCard("tt1", season = null, episode = null, positionMs = 72L * 60_000)),
        )
    }

    @Test
    fun `a missing field drops itself and its separator`() {
        val full = HomeCard(
            id = "a",
            type = "movie",
            title = "Dune",
            year = 2024,
            runtimeMinutes = 129,
            genre = "Thriller",
            rating = 7.8,
        )
        assertEquals("2024 · 2h 09m · Thriller", HomePlan.metaLine(full))
        assertEquals("2024 · Thriller", HomePlan.metaLine(full.copy(runtimeMinutes = null, rating = null)))
        assertEquals("", HomePlan.metaLine(HomeCard(id = "a", type = "movie", title = "Dune")))
    }
}
