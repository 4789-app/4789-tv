package com.fourseveneightnine.tv.client.ui.screens.collections

import com.fourseveneightnine.tv.client.data.library.CollectionDetail
import com.fourseveneightnine.tv.client.data.library.CollectionItem
import com.fourseveneightnine.tv.client.data.library.CollectionKind
import com.fourseveneightnine.tv.client.data.library.CollectionSummary
import com.fourseveneightnine.tv.client.data.library.SystemCollections
import com.fourseveneightnine.tv.client.data.catalog.CatalogItem
import com.fourseveneightnine.tv.client.data.catalog.Shelf
import com.fourseveneightnine.tv.client.data.catalog.ShelfKind
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The folder card is 380 px wide (spec §5.3), which the sliver block must fit inside. */
private const val FOLDER_CARD_WIDTH = 380

/** Every rule the Collections screens follow, checked without a television. */
class CollectionsModelTest {

    // ---------------------------------------------------------------- folder order

    @Test
    fun `system folders come first and keep the order the repository gave`() {
        val cards = FolderGrid.cards(
            listOf(
                system(1, SystemCollections.CONTINUE_WATCHING_NAME),
                system(2, SystemCollections.MY_CLOUD_NAME),
                system(3, SystemCollections.WATCHLIST_NAME),
                user(4, "Sunday night"),
                user(5, "Rewatch pile"),
            ),
            hasDebridKey = true,
        )

        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), cards.map(FolderCardModel::id))
        assertEquals(
            listOf(
                FolderRole.ContinueWatching, FolderRole.MyCloud, FolderRole.Watchlist,
                FolderRole.User, FolderRole.User,
            ),
            cards.map(FolderCardModel::role),
        )
    }

    @Test
    fun `My Cloud is removed, not disabled, when no debrid key is present`() {
        val summaries = listOf(
            system(1, SystemCollections.CONTINUE_WATCHING_NAME),
            system(2, SystemCollections.MY_CLOUD_NAME),
            user(3, "Sunday night"),
        )

        assertEquals(listOf(1L, 2L, 3L), FolderGrid.cards(summaries, true).map(FolderCardModel::id))
        assertEquals(listOf(1L, 3L), FolderGrid.cards(summaries, false).map(FolderCardModel::id))
    }

    @Test
    fun `an empty folder says Empty and My Cloud counts files`() {
        assertEquals("Empty", FolderGrid.card(user(1, "Sunday night", count = 0)).countLabel)
        assertEquals("1 title", FolderGrid.card(user(1, "Sunday night", count = 1)).countLabel)
        assertEquals("24 titles", FolderGrid.card(user(1, "Sunday night", count = 24)).countLabel)
        assertEquals(
            "8 files",
            FolderGrid.card(system(2, SystemCollections.MY_CLOUD_NAME, count = 8)).countLabel,
        )
    }

    @Test
    fun `the empty state is only for bare system folders`() {
        val bare = FolderGrid.cards(listOf(system(1, SystemCollections.CONTINUE_WATCHING_NAME)), false)
        assertTrue(FolderGrid.isEmpty(bare))

        val withOwn = FolderGrid.cards(
            listOf(system(1, SystemCollections.CONTINUE_WATCHING_NAME), user(2, "Sunday night")),
            false,
        )
        assertFalse(FolderGrid.isEmpty(withOwn))
    }

    @Test
    fun `the count line agrees with the cards drawn`() {
        val cards = FolderGrid.cards(
            listOf(system(1, SystemCollections.MY_CLOUD_NAME), user(2, "Sunday night")),
            hasDebridKey = false,
        )
        assertEquals("1 collection", FolderGrid.countLine(cards))
    }

    // ---------------------------------------------------------------- card geometry

    @Test
    fun `three poster slivers fit inside the folder card`() {
        assertEquals(0, FolderGrid.sliverOffset(0))
        assertEquals(40, FolderGrid.sliverOffset(1))
        assertEquals(80, FolderGrid.sliverOffset(2))
        // 24 inset + 80 + 96 = 200, inside the 380 px card. Spaced 40 apart it was 392 and the
        // card's clip sheared 12 px off the third sliver.
        assertEquals(200, FolderGrid.sliverBlockWidth(FolderGrid.SLIVERS))
        assertTrue(FolderGrid.sliverBlockWidth(FolderGrid.SLIVERS) <= FOLDER_CARD_WIDTH)
        assertEquals(0, FolderGrid.sliverBlockWidth(0))
    }

    @Test
    fun `the collection grid band is six cards and five gaps, not the whole screen`() {
        assertEquals(1516, CollectionGrid.BAND_WIDTH)
        // Every column lands on the spec's x values, measured from the 220 px gutter.
        val columns = (0 until CollectionGrid.COLUMNS).map {
            220 + it * (CollectionGrid.CARD_WIDTH + CollectionGrid.COLUMN_GAP)
        }
        assertEquals(listOf(220, 476, 732, 988, 1244, 1500), columns)
    }

    @Test
    fun `Collections exposes every synced shelf name without a display cap`() {
        val shelves = (0 until 140).map { index ->
            Shelf(
                id = "list-$index",
                title = "List $index",
                kind = ShelfKind.LETTERBOXD,
                items = listOf(CatalogItem("tt$index", "movie", "Title $index")),
                generation = "g1",
                generatedAtMillis = 1L,
            )
        } + Shelf(
            id = "empty",
            title = "Empty",
            kind = ShelfKind.LETTERBOXD,
            items = emptyList(),
            generation = "g1",
            generatedAtMillis = 1L,
        )

        val visible = SyncedLists.visible(shelves)

        assertEquals(141, visible.size)
        assertEquals("list-0", visible.first().id)
        assertEquals("empty", visible.last().id)
        assertEquals("1 title", SyncedLists.countLabel(visible.first()))
        assertEquals("4 collections · 141 synced lists", SyncedLists.directoryLabel(4, visible.size))
        assertEquals("Letterboxd", SyncedLists.sourceLabel(visible.first()))
    }

    // ---------------------------------------------------------------- accents

    @Test
    fun `a card's accent is parsed once and matches the palette`() {
        val card = FolderGrid.card(user(1, "Sunday night").copy(accent = Accents.HEXES[2]))
        assertEquals(Accents.PALETTE[2], card.accent)
        assertEquals(Accents.PALETTE[2], ChecklistRow(1, "x", Accents.HEXES[2], 0, false, false).accent)
    }

    @Test
    fun `an unreadable accent falls back rather than throwing`() {
        assertEquals(TvColor.TextMuted, accentColor("not a colour"))
        assertEquals(TvColor.TextMuted, accentColor("#GGGGGG"))
        assertEquals(Accents.PALETTE.first(), accentColor(Accents.DEFAULT))
    }

    // ---------------------------------------------------------------- stored sort

    @Test
    fun `the sort choice is stored under the collection's own key`() {
        assertEquals("collection_sort_7", CollectionSort.key(7L))
        assertEquals("collection_sort_12", CollectionSort.key(12L))
    }

    @Test
    fun `a stored sort choice reads back, and an unknown one reads as Added`() {
        SortMode.entries.forEach { mode ->
            assertEquals(mode, CollectionSort.read(CollectionSort.write(mode)))
        }
        assertEquals(SortMode.Added, CollectionSort.read(null))
        assertEquals(SortMode.Added, CollectionSort.read(""))
        assertEquals(SortMode.Added, CollectionSort.read("Sideways"))
    }

    // ---------------------------------------------------------------- move mode

    @Test
    fun `an arrow travels one place sideways and a whole row up or down`() {
        assertEquals(-1, MoveMode.delta(MoveDirection.Left, 4))
        assertEquals(1, MoveMode.delta(MoveDirection.Right, 4))
        assertEquals(-4, MoveMode.delta(MoveDirection.Up, 4))
        assertEquals(4, MoveMode.delta(MoveDirection.Down, 4))
        assertEquals(6, MoveMode.delta(MoveDirection.Down, 6))
    }

    @Test
    fun `a swap moves the picked item and shuffles the rest along`() {
        val order = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "a", "c", "d"), MoveMode.swapped(order, 0, 1))
        assertEquals(listOf("a", "c", "b", "d"), MoveMode.swapped(order, 2, -1))
        assertEquals(listOf("b", "c", "d", "a"), MoveMode.swapped(order, 0, 3))
    }

    @Test
    fun `a swap past either end changes nothing`() {
        val order = listOf("a", "b", "c")
        assertEquals(order, MoveMode.swapped(order, 0, -1))
        assertEquals(order, MoveMode.swapped(order, 2, 1))
        assertEquals(order, MoveMode.swapped(order, 1, 0))
        assertEquals(order, MoveMode.swapped(order, 9, 1))
    }

    @Test
    fun `BACK walks the whole journey back in one step`() {
        assertEquals(0, MoveMode.undoDelta(emptyList()))
        assertEquals(-1, MoveMode.undoDelta(listOf(1)))
        assertEquals(-5, MoveMode.undoDelta(listOf(1, 4)))
        assertEquals(0, MoveMode.undoDelta(listOf(4, -4)))
    }

    @Test
    fun `undoing a run of swaps lands on the order it started from`() {
        val start = listOf("a", "b", "c", "d", "e", "f")
        val applied = listOf(1, 1, -1)
        var order = start
        var index = 0
        applied.forEach { delta ->
            order = MoveMode.swapped(order, index, delta)
            index += delta
        }
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), MoveMode.swapped(order, index, MoveMode.undoDelta(applied)))
    }

    // ---------------------------------------------------------------- sort

    @Test
    fun `Added sort is the stored order, which is what move mode writes`() {
        val items = listOf(
            item("c", "Zodiac (2007)", sortIndex = 2),
            item("a", "Arrival (2016)", sortIndex = 0),
            item("b", "Dune Part Two (2024)", sortIndex = 1),
        )
        assertEquals(listOf("a", "b", "c"), CollectionGrid.sorted(items, SortMode.Added).ids())
    }

    @Test
    fun `Title sort ignores case and ignores the year in the stored title`() {
        val items = listOf(
            item("z", "zodiac (2007)", sortIndex = 0),
            item("a", "Arrival (2016)", sortIndex = 1),
            item("d", "Dune Part Two (2024)", sortIndex = 2),
        )
        assertEquals(listOf("a", "d", "z"), CollectionGrid.sorted(items, SortMode.Title).ids())
    }

    @Test
    fun `Year sort is newest first and parks unknown years at the end in added order`() {
        val items = listOf(
            item("old", "Zodiac (2007)", sortIndex = 0),
            item("none", "A Title With No Year", sortIndex = 1),
            item("new", "Dune Part Two (2024)", sortIndex = 2),
            item("none2", "Another Unknown", sortIndex = 3),
        )
        assertEquals(
            listOf("new", "old", "none", "none2"),
            CollectionGrid.sorted(items, SortMode.Year).ids(),
        )
    }

    @Test
    fun `a cell shows the title without its year and the year on its own`() {
        val cells = CollectionGrid.cells(listOf(item("a", "Dune Part Two (2024)")), SortMode.Added)
        assertEquals("Dune Part Two", cells.single().title)
        assertEquals(2024, cells.single().year)
    }

    @Test
    fun `a title that only looks like it has a year keeps it`() {
        assertNull(yearIn("Blade Runner 2049"))
        assertNull(yearIn("Se7en (1899)"))
        assertEquals(1999, yearIn("The Matrix (1999)"))
        assertEquals("The Matrix", titleWithoutYear("The Matrix (1999)"))
    }

    @Test
    fun `the meta line drops the stale half when there is nothing to say`() {
        assertEquals("24 titles", CollectionGrid.metaLine(24, null))
        assertEquals("1 title", CollectionGrid.metaLine(1, ""))
        assertEquals("24 titles · updated 2h ago", CollectionGrid.metaLine(24, "updated 2h ago"))
    }

    @Test
    fun `only a sourced folder gets a source line`() {
        assertNull(CollectionGrid.sourceLine(CollectionKind.MANUAL, null, null))
        assertNull(CollectionGrid.sourceLine(CollectionKind.SYSTEM, "system:my-cloud", null))
        assertEquals(
            "From Letterboxd · saran",
            CollectionGrid.sourceLine(CollectionKind.LETTERBOXD, "lb:saran", "saran"),
        )
        assertEquals(
            "From add-on · Cinemeta Trending",
            CollectionGrid.sourceLine(CollectionKind.CATALOG, "url|movie|top", "Cinemeta Trending"),
        )
    }

    // ---------------------------------------------------------------- checklist

    @Test
    fun `the checklist offers your folders only, with a tick where the title already is`() {
        val rows = Checklist.rows(
            listOf(
                system(1, SystemCollections.CONTINUE_WATCHING_NAME),
                user(2, "Sunday night", count = 12),
                user(3, "Rewatch pile", count = 31),
            ),
            containing = setOf(2L),
        )

        assertEquals(listOf(2L, 3L), rows.map(ChecklistRow::id))
        assertTrue(rows.first().checked)
        assertFalse(rows.last().checked)
    }

    @Test
    fun `OK adds an unchecked folder, removes a checked one, and does nothing to a sourced one`() {
        val rows = Checklist.rows(
            listOf(
                user(2, "Sunday night", count = 12),
                user(3, "Rewatch pile", count = 31),
                sourced(4, "Best of 2024", count = 50),
            ),
            containing = setOf(2L),
        )

        assertEquals(ChecklistAction.Remove, Checklist.action(rows[0]))
        assertEquals(ChecklistAction.Add, Checklist.action(rows[1]))
        assertEquals(ChecklistAction.Ignored, Checklist.action(rows[2]))
        assertEquals("sourced", rows[2].countLabel)
        assertEquals("31", rows[1].countLabel)
    }

    @Test
    fun `focus lands on the first row that can still take this title`() {
        val rows = Checklist.rows(
            listOf(sourced(1, "Best of 2024"), user(2, "Sunday night"), user(3, "Rewatch pile")),
            containing = setOf(2L),
        )
        assertEquals(2, Checklist.initialIndex(rows))

        val allChecked = Checklist.rows(
            listOf(user(2, "Sunday night"), user(3, "Rewatch pile")),
            containing = setOf(2L, 3L),
        )
        assertEquals(0, Checklist.initialIndex(allChecked))
    }

    // ---------------------------------------------------------------- sourced refresh

    @Test
    fun `a refresh replaces a sourced folder's titles and renumbers them`() {
        val detail = detail(
            CollectionKind.LETTERBOXD,
            items = listOf(item("old1", "Old One", sortIndex = 0), item("old2", "Old Two", sortIndex = 1)),
        )
        val incoming = listOf(item("new1", "New One", sortIndex = 7), item("new2", "New Two", sortIndex = 9))

        val written = SourcedRefresh.itemsFor(detail, incoming)

        assertEquals(listOf("new1", "new2"), written?.ids())
        assertEquals(listOf(0, 1), written?.map(CollectionItem::sortIndex))
    }

    @Test
    fun `a refresh never touches a manual folder or a system folder`() {
        val manual = detail(CollectionKind.MANUAL, items = listOf(item("mine", "Mine")))
        val builtIn = detail(CollectionKind.SYSTEM, items = listOf(item("mine", "Mine")))
        val incoming = listOf(item("theirs", "Theirs"))

        assertNull(SourcedRefresh.itemsFor(manual, incoming))
        assertNull(SourcedRefresh.itemsFor(builtIn, incoming))
        // The manual folder is untouched: the caller has nothing to write.
        assertEquals(listOf("mine"), manual.items.ids())
    }

    @Test
    fun `a source that repeats a title writes it once`() {
        val detail = detail(CollectionKind.CATALOG, items = emptyList())
        val incoming = listOf(item("a", "A"), item("a", "A again"), item("b", "B"))

        assertEquals(listOf("a", "b"), SourcedRefresh.itemsFor(detail, incoming)?.ids())
    }

    @Test
    fun `an unchanged source skips the write`() {
        val stored = listOf(item("a", "A", sortIndex = 0), item("b", "B", sortIndex = 1))
        assertTrue(SourcedRefresh.unchanged(stored, stored))
        assertFalse(SourcedRefresh.unchanged(stored, stored.reversed()))
        assertFalse(SourcedRefresh.unchanged(stored, stored + item("c", "C")))
    }

    // ---------------------------------------------------------------- source refs and accents

    @Test
    fun `a catalog ref survives the round trip, pipes in the catalog id included`() {
        val ref = SourceRef.catalog("https://cinemeta.strem.io/manifest.json", "movie", "top|10")
        val parsed = SourceRef.parseCatalog(ref)

        assertEquals("https://cinemeta.strem.io/manifest.json", parsed?.manifestUrl)
        assertEquals("movie", parsed?.type)
        assertEquals("top|10", parsed?.id)
        assertNull(SourceRef.parseCatalog(null))
        assertNull(SourceRef.parseCatalog("nonsense"))
    }

    @Test
    fun `the eight accents round trip through hex`() {
        assertEquals(8, Accents.PALETTE.size)
        Accents.HEXES.forEachIndexed { index, hex ->
            assertEquals(Accents.PALETTE[index], accentColor(hex))
            assertEquals(index, Accents.indexOf(hex))
        }
        assertEquals(-1, Accents.indexOf("#123456"))
    }

    @Test
    fun `an unreadable accent falls back instead of crashing`() {
        assertEquals(com.fourseveneightnine.tv.client.ui.theme.TvColor.TextMuted, accentColor("nope"))
        assertEquals(com.fourseveneightnine.tv.client.ui.theme.TvColor.TextMuted, accentColor("#GGGGGG"))
    }

    @Test
    fun `a long name is clipped on one line, never wrapped`() {
        assertEquals("Sunday night", clip("Sunday night", 22))
        assertEquals("A very long collection…", clip("A very long collection name here", 24))
    }

    @Test
    fun `a stored title carries the year the library row cannot`() {
        assertEquals("Dune Part Two (2024)", storedTitle("Dune Part Two", 2024))
        assertEquals("Dune Part Two (2024)", storedTitle("Dune Part Two (2024)", 2024))
        assertEquals("Dune Part Two", storedTitle("Dune Part Two", null))
    }

    // ---------------------------------------------------------------- fixtures

    private fun List<CollectionItem>.ids(): List<String> = map(CollectionItem::canonicalId)

    private fun system(id: Long, name: String, count: Int = 0) = CollectionSummary(
        id = id,
        name = name,
        accent = SystemCollections.SYSTEM_ACCENT,
        kind = CollectionKind.SYSTEM,
        count = count,
        pinnedHome = false,
        previewPosters = emptyList(),
    )

    private fun user(id: Long, name: String, count: Int = 3) = CollectionSummary(
        id = id,
        name = name,
        accent = Accents.DEFAULT,
        kind = CollectionKind.MANUAL,
        count = count,
        pinnedHome = false,
        previewPosters = emptyList(),
    )

    private fun sourced(id: Long, name: String, count: Int = 3) =
        user(id, name, count).copy(kind = CollectionKind.LETTERBOXD)

    private fun item(id: String, title: String, sortIndex: Int = 0) = CollectionItem(
        canonicalId = id,
        mediaType = "movie",
        title = title,
        posterUrl = null,
        sortIndex = sortIndex,
        addedAtMillis = 0L,
    )

    private fun detail(kind: CollectionKind, items: List<CollectionItem>) = CollectionDetail(
        id = 1L,
        name = "Sunday night",
        accent = Accents.DEFAULT,
        kind = kind,
        sourceRef = "ref",
        pinnedHome = false,
        sortIndex = 0,
        updatedAtMillis = 0L,
        items = items,
    )
}
