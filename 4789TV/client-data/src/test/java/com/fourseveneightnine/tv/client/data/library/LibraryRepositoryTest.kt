package com.fourseveneightnine.tv.client.data.library

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryRepositoryTest {
    private lateinit var database: LibraryDatabase
    private lateinit var repository: LibraryRepository
    private var clock: Long = 1_000_000L

    @Before
    fun setUp() {
        database = LibraryDatabase.createInMemory(RuntimeEnvironment.getApplication())
        repository = LibraryRepository(database) { clock }
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ------------------------------------------------------------- continue watching

    @Test
    fun `local progress beats the phone mirror for the same title`() = runTest {
        repository.replacePhoneRecents(
            listOf(
                PhoneRecentRow(
                    canonicalId = "tt1",
                    mediaType = "movie",
                    title = "Dune Part Two",
                    posterUrl = "https://image.tmdb.org/t/p/w342/dune.jpg",
                    positionMs = 10_000,
                    durationMs = 100_000,
                    lastPlayedAt = 500L,
                ),
            ),
        )
        repository.recordLocalPlay("tt1", "movie", "Dune Part Two", playedAtMillis = 900L)
        repository.writeProgress("tt1", "movie", positionMs = 60_000, durationMs = 100_000)

        val items = repository.continueWatching().first()

        assertEquals(1, items.size)
        assertEquals(RowOrigin.LOCAL, items.single().origin)
        assertEquals(60_000L, items.single().positionMs)
        // Display metadata still comes from whichever recent row exists.
        assertEquals("Dune Part Two", items.single().title)
    }

    @Test
    fun `rows under two percent and over ninety five percent are dropped`() = runTest {
        repository.recordLocalPlay("barely", "movie", "Barely started")
        repository.recordLocalPlay("nearly", "movie", "Nearly finished")
        repository.recordLocalPlay("middle", "movie", "Half way")
        repository.writeProgress("barely", "movie", positionMs = 1_000, durationMs = 100_000)
        repository.writeProgress("nearly", "movie", positionMs = 96_000, durationMs = 100_000)
        repository.writeProgress("middle", "movie", positionMs = 50_000, durationMs = 100_000)

        val ids = repository.continueWatching().first().map(ContinueItem::canonicalId)

        assertEquals(listOf("middle"), ids)
    }

    @Test
    fun `exactly two percent stays and exactly ninety five percent stays`() = runTest {
        repository.recordLocalPlay("low", "movie", "Two percent")
        repository.recordLocalPlay("high", "movie", "Ninety five percent")
        repository.writeProgress("low", "movie", positionMs = 2_000, durationMs = 100_000)
        repository.writeProgress("high", "movie", positionMs = 95_000, durationMs = 100_000)

        val ids = repository.continueWatching().first().map(ContinueItem::canonicalId).toSet()

        assertEquals(setOf("low", "high"), ids)
    }

    @Test
    fun `an unknown duration is kept because there is no progress to judge`() = runTest {
        repository.recordLocalPlay("live", "series", "Live channel")
        repository.writeProgress("live", "series", positionMs = 0, durationMs = 0)

        assertEquals(listOf("live"), repository.continueWatching().first().map(ContinueItem::canonicalId))
    }

    @Test
    fun `sorted by last activity, newest first`() = runTest {
        repository.recordLocalPlay("old", "movie", "Old", playedAtMillis = 100L)
        repository.recordLocalPlay("new", "movie", "New", playedAtMillis = 100L)
        clock = 200L
        repository.writeProgress("old", "movie", positionMs = 50_000, durationMs = 100_000)
        clock = 300L
        repository.writeProgress("new", "movie", positionMs = 50_000, durationMs = 100_000)

        assertEquals(
            listOf("new", "old"),
            repository.continueWatching().first().map(ContinueItem::canonicalId),
        )
    }

    @Test
    fun `markWatched removes the title from continue watching`() = runTest {
        repository.recordLocalPlay("tt2", "movie", "Finished")
        repository.writeProgress("tt2", "movie", positionMs = 50_000, durationMs = 100_000)
        assertEquals(1, repository.continueWatching().first().size)

        repository.markWatched("tt2", "movie")

        assertTrue(repository.continueWatching().first().isEmpty())
    }

    // ------------------------------------------------------------- phone mirror

    @Test
    fun `phone recents are replaced wholesale, never merged`() = runTest {
        repository.replacePhoneRecents(
            listOf(
                phoneRow("a", "Removed later"),
                phoneRow("b", "Kept"),
            ),
        )
        assertEquals(
            setOf("a", "b"),
            repository.continueWatching().first().map(ContinueItem::canonicalId).toSet(),
        )

        repository.replacePhoneRecents(listOf(phoneRow("b", "Kept")))

        assertEquals(
            setOf("b"),
            repository.continueWatching().first().map(ContinueItem::canonicalId).toSet(),
        )
    }

    @Test
    fun `a phone push never overwrites the box's own progress`() = runTest {
        repository.recordLocalPlay("shared", "movie", "Shared title")
        repository.writeProgress("shared", "movie", positionMs = 70_000, durationMs = 100_000)

        repository.replacePhoneRecents(
            listOf(phoneRow("shared", "Shared title", positionMs = 5_000, durationMs = 100_000)),
        )

        val row = repository.continueWatching().first().single()
        assertEquals(RowOrigin.LOCAL, row.origin)
        assertEquals(70_000L, row.positionMs)
    }

    @Test
    fun `a local recent survives a phone push`() = runTest {
        repository.recordLocalPlay("localonly", "movie", "Played here", playedAtMillis = 10L)
        repository.writeProgress("localonly", "movie", positionMs = 50_000, durationMs = 100_000)

        repository.replacePhoneRecents(listOf(phoneRow("other", "From the phone")))

        assertEquals(
            setOf("localonly", "other"),
            repository.continueWatching().first().map(ContinueItem::canonicalId).toSet(),
        )
    }

    // ------------------------------------------------------------- collections

    @Test
    fun `system folders are created once, however often they are asked for`() = runTest {
        val first = repository.ensureSystemCollections()
        val second = repository.ensureSystemCollections()

        assertEquals(first, second)
        val all = repository.collections().first()
        assertEquals(3, all.size)
        assertEquals(
            listOf(
                SystemCollections.CONTINUE_WATCHING_NAME,
                SystemCollections.MY_CLOUD_NAME,
                SystemCollections.WATCHLIST_NAME,
            ),
            all.map(CollectionSummary::name),
        )
        assertTrue(all.all { it.kind == CollectionKind.SYSTEM })
    }

    @Test
    fun `system folders sort ahead of the viewer's folders`() = runTest {
        repository.create("Sunday night", "#F2A0A0")
        repository.ensureSystemCollections()

        val names = repository.collections().first().map(CollectionSummary::name)

        assertEquals(
            listOf(
                SystemCollections.CONTINUE_WATCHING_NAME,
                SystemCollections.MY_CLOUD_NAME,
                SystemCollections.WATCHLIST_NAME,
                "Sunday night",
            ),
            names,
        )
    }

    @Test
    fun `moveCollection swaps with the neighbour and stops at the ends`() = runTest {
        val a = repository.create("A", "#111111")
        val b = repository.create("B", "#222222")
        val c = repository.create("C", "#333333")

        assertTrue(repository.moveCollection(c, -1))
        assertEquals(listOf("A", "C", "B"), repository.collections().first().map(CollectionSummary::name))

        assertTrue(repository.moveCollection(a, 1))
        assertEquals(listOf("C", "A", "B"), repository.collections().first().map(CollectionSummary::name))

        assertFalse(repository.moveCollection(c, -1))
        assertFalse(repository.moveCollection(b, 1))
        assertEquals(listOf("C", "A", "B"), repository.collections().first().map(CollectionSummary::name))
    }

    @Test
    fun `a system folder never moves`() = runTest {
        val (continueWatching, _) = repository.ensureSystemCollections().let { it[0] to it[1] }
        assertFalse(repository.moveCollection(continueWatching, 1))
    }

    @Test
    fun `moveItem reorders inside one collection`() = runTest {
        val id = repository.create("Sunday night", "#F2A0A0")
        repository.addItem(id, "one", "movie", "One")
        repository.addItem(id, "two", "movie", "Two")
        repository.addItem(id, "three", "movie", "Three")

        assertTrue(repository.moveItem(id, "three", -2))

        assertEquals(
            listOf("three", "one", "two"),
            repository.collection(id).first()?.items?.map(CollectionItem::canonicalId),
        )
    }

    @Test
    fun `a sourced collection cannot be reordered by hand`() = runTest {
        val id = repository.create("Watchlist", "#2FD4C8", CollectionKind.LETTERBOXD, "letterboxd:saran:watchlist")
        repository.replaceSourcedItems(
            id,
            listOf(sourcedItem("one", "One"), sourcedItem("two", "Two")),
        )

        assertFalse(repository.moveItem(id, "two", -1))
        assertEquals(
            listOf("one", "two"),
            repository.collection(id).first()?.items?.map(CollectionItem::canonicalId),
        )
    }

    @Test
    fun `replaceSourcedItems drops what the source dropped`() = runTest {
        val id = repository.create("MDBList", "#2FD4C8", CollectionKind.MDBLIST, "mdblist:1234")
        repository.replaceSourcedItems(
            id,
            listOf(sourcedItem("one", "One"), sourcedItem("two", "Two"), sourcedItem("three", "Three")),
        )

        repository.replaceSourcedItems(id, listOf(sourcedItem("three", "Three"), sourcedItem("four", "Four")))

        val detail = repository.collection(id).first()
        assertEquals(listOf("three", "four"), detail?.items?.map(CollectionItem::canonicalId))
        assertEquals(listOf(0, 1), detail?.items?.map(CollectionItem::sortIndex))
    }

    @Test
    fun `removing an item renumbers the rest`() = runTest {
        val id = repository.create("Sunday night", "#F2A0A0")
        repository.addItem(id, "one", "movie", "One")
        repository.addItem(id, "two", "movie", "Two")
        repository.addItem(id, "three", "movie", "Three")

        repository.removeItem(id, "two")

        assertEquals(listOf(0, 1), repository.collection(id).first()?.items?.map(CollectionItem::sortIndex))
    }

    @Test
    fun `summary counts items and shows at most three posters`() = runTest {
        val id = repository.create("Sunday night", "#F2A0A0")
        repeat(5) { index ->
            repository.addItem(id, "item$index", "movie", "Title $index", "https://cdn/$index.jpg")
        }

        val summary = repository.collections().first().single()

        assertEquals(5, summary.count)
        assertEquals(3, summary.previewPosters.size)
        assertEquals("https://cdn/0.jpg", summary.previewPosters.first())
    }

    @Test
    fun `collectionsContaining answers the add to collection checklist`() = runTest {
        val a = repository.create("A", "#111111")
        val b = repository.create("B", "#222222")
        repository.addItem(a, "tt9", "movie", "Nine")

        assertEquals(setOf(a), repository.collectionsContaining("tt9").first())

        repository.addItem(b, "tt9", "movie", "Nine")
        assertEquals(setOf(a, b), repository.collectionsContaining("tt9").first())
    }

    @Test
    fun `rename, accent and pin all save at once`() = runTest {
        val id = repository.create("Old name", "#111111")

        repository.rename(id, "New name")
        repository.setAccent(id, "#2FD4C8")
        repository.setPinned(id, true)

        val detail = repository.collection(id).first()
        assertEquals("New name", detail?.name)
        assertEquals("#2FD4C8", detail?.accent)
        assertEquals(true, detail?.pinnedHome)
    }

    @Test
    fun `delete removes the collection and its items, and never a system folder`() = runTest {
        val id = repository.create("Temporary", "#111111")
        repository.addItem(id, "tt1", "movie", "One")
        val systemIds = repository.ensureSystemCollections()

        repository.delete(id)
        assertNull(repository.collection(id).first())
        assertTrue(repository.collectionsContaining("tt1").first().isEmpty())

        repository.delete(systemIds.first())
        assertNotNull(repository.collection(systemIds.first()).first())
    }

    // ------------------------------------------------------------- jobs

    @Test
    fun `a running job keeps its start time when it finishes`() = runTest {
        clock = 10L
        repository.updateJob("Catalog snapshots", JobState.RUNNING)
        clock = 40L
        repository.updateJob("Catalog snapshots", JobState.OK, "1,284 items")

        val row = repository.jobs().first().single()
        assertEquals(JobState.OK, row.state)
        assertEquals(10L, row.startedAtMillis)
        assertEquals(40L, row.finishedAtMillis)
        assertEquals("1,284 items", row.message)
    }

    @Test
    fun `a running job has no finish time`() = runTest {
        repository.updateJob("Add-on catalogs", JobState.RUNNING)

        val row = repository.jobs().first().single()
        assertEquals(JobState.RUNNING, row.state)
        assertNull(row.finishedAtMillis)
    }

    private fun phoneRow(
        id: String,
        title: String,
        positionMs: Long = 20_000,
        durationMs: Long = 100_000,
    ) = PhoneRecentRow(
        canonicalId = id,
        mediaType = "movie",
        title = title,
        positionMs = positionMs,
        durationMs = durationMs,
        lastPlayedAt = 500L,
    )

    private fun sourcedItem(id: String, title: String) = CollectionItem(
        canonicalId = id,
        mediaType = "movie",
        title = title,
        posterUrl = null,
        sortIndex = 0,
        addedAtMillis = 0L,
    )
}
