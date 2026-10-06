package com.fourseveneightnine.tv.client.data.catalog

import com.fourseveneightnine.contract.CatalogSnapshotContract
import java.io.File
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SnapshotStoreTest {
    private lateinit var filesDir: File
    private lateinit var http: FakeCatalogHttp
    private lateinit var verifier: FakeSnapshotVerifier

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Before
    fun setUp() {
        filesDir = File(RuntimeEnvironment.getApplication().filesDir, "snapshot-test-${System.nanoTime()}")
        filesDir.mkdirs()
        http = FakeCatalogHttp()
        verifier = FakeSnapshotVerifier()
    }

    private fun TestScope.store(evictionBudgetBytes: Long = SnapshotStore.EVICTION_BUDGET_BYTES) =
        SnapshotStore(
            filesDir = filesDir,
            http = http,
            verifier = verifier,
            tokenProvider = { "a-token" },
            letterboxdUsernames = { listOf("saran") },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            now = { currentTime },
            evictionBudgetBytes = evictionBudgetBytes,
        )

    private fun disk() = SnapshotDiskCache(filesDir, json)

    private fun generation(name: String) = PrivateGeneration(http, name)
        .shelf("private/v1/owner/popular.json", "tamilmv:main:popular", listOf("Popular one", "Popular two"))
        .shelf("private/v1/owner/recent.json", "tamilmv:main:recent", listOf("Recent one"))
        .shelf("private/v1/owner/watchlist.json", "letterboxd:saran:watchlist", listOf("Listed one"))

    // ------------------------------------------------------------- first paint

    @Test
    fun `the head file holds the Tamil MV shelves alone`() = runTest {
        generation("g1").publish()
        store().refresh()

        val head = disk().readHead(SnapshotSource.PRIVATE_CATALOG)
        val full = disk().readFull(SnapshotSource.PRIVATE_CATALOG)

        assertEquals(
            listOf(ShelfKind.TAMILMV_POPULAR, ShelfKind.TAMILMV_RECENT),
            head?.shelves?.map(Shelf::kind),
        )
        assertEquals(
            listOf(ShelfKind.TAMILMV_POPULAR, ShelfKind.TAMILMV_RECENT, ShelfKind.LETTERBOXD),
            full?.shelves?.map(Shelf::kind),
        )
    }

    @Test
    fun `hydrate paints the head before the full document`() = runTest {
        generation("g1").publish()
        store().refresh()

        // A second store over the same directory, with a server that answers nothing.
        val offline = store()
        val emissions = mutableListOf<List<Shelf>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            offline.shelves().toList(emissions)
        }
        offline.hydrate()

        val painted = emissions.filter { it.isNotEmpty() }
        assertTrue("expected a head-only paint, got ${painted.map { p -> p.map(Shelf::kind) }}",
            painted.first().map(Shelf::kind) == listOf(ShelfKind.TAMILMV_POPULAR, ShelfKind.TAMILMV_RECENT))
        assertEquals(3, painted.last().size)
    }

    @Test
    fun `a letterboxd shelf is titled from the owner's username when the server sends no name`() = runTest {
        generation("g1").publish()
        val store = store()
        store.refresh()

        val shelf = store.shelves().value.single { it.kind == ShelfKind.LETTERBOXD }
        assertEquals("Watchlist", shelf.title)
    }

    // ------------------------------------------------------------- partial paint

    @Test
    fun `a slow shelf is painted late and the partial never reaches disk`() = runTest {
        PrivateGeneration(http, "g1")
            .shelf("private/v1/owner/popular.json", "tamilmv:main:popular", listOf("Fast one"))
            .shelf(
                key = "private/v1/owner/watchlist.json",
                catalogId = "letterboxd:saran:watchlist",
                titles = listOf("Slow one"),
                delayMillis = SnapshotStore.PARTIAL_PAINT_DEADLINE_MILLIS * 3,
            )
            .publish()

        val store = store()
        val emissions = mutableListOf<List<Shelf>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.shelves().toList(emissions)
        }
        store.refresh()

        val partial = emissions.firstOrNull { list -> list.isNotEmpty() && list.any { !it.complete } }
        assertNotNull("expected a partial paint before the deadline", partial)
        assertEquals(listOf(ShelfKind.TAMILMV_POPULAR), partial?.map(Shelf::kind))

        val finished = store.shelves().value
        assertEquals(2, finished.size)
        assertTrue(finished.all(Shelf::complete))

        // Only the complete generation was written.
        val stored = disk().readFull(SnapshotSource.PRIVATE_CATALOG)
        assertEquals(2, stored?.shelves?.size)
        assertTrue(stored?.shelves.orEmpty().all(Shelf::complete))
    }

    // ------------------------------------------------------------- promotion

    @Test
    fun `a failure part way through keeps the previous generation on disk and on screen`() = runTest {
        generation("g1").publish()
        val store = store()
        store.refresh()
        val beforeIds = store.shelves().value.map(Shelf::id)

        PrivateGeneration(http, "g2")
            .shelf("private/v1/owner/popular.json", "tamilmv:main:popular", listOf("New one"))
            .shelf(
                key = "private/v1/owner/broken.json",
                catalogId = "letterboxd:saran:broken",
                titles = listOf("Never arrives"),
                fail = CatalogHttpException(500),
            )
            .publish()
        store.refresh(force = true)

        assertEquals("g1", disk().readFull(SnapshotSource.PRIVATE_CATALOG)?.generation)
        assertEquals(beforeIds, store.shelves().value.map(Shelf::id))
        assertEquals("g1", store.shelves().value.first().generation)
        assertEquals(
            SnapshotState.FAILED,
            store.status().value.of(SnapshotSource.PRIVATE_CATALOG)?.state,
        )
    }

    @Test
    fun `a promoted generation replaces the shelves in place`() = runTest {
        generation("g1").publish()
        val store = store()
        store.refresh()
        assertEquals("g1", store.shelves().value.first().generation)

        PrivateGeneration(http, "g2")
            .shelf("private/v1/owner/popular.json", "tamilmv:main:popular", listOf("Brand new"))
            .publish()
        store.refresh(force = true)

        assertEquals("g2", store.shelves().value.first().generation)
        assertEquals("Brand new", store.shelves().value.first().items.first().title)
        assertEquals("g2", disk().readFull(SnapshotSource.PRIVATE_CATALOG)?.generation)
    }

    @Test
    fun `an older phone import never replaces a newer server snapshot`() = runTest {
        generation("server-new").publish()
        val store = store()
        store.refresh()

        val imported = store.importPrivateSnapshot(
            generation = "phone-old",
            generatedAtMillis = Instant.parse("2026-09-19T10:00:00Z").toEpochMilli(),
            shelves = phoneShelves("phone-old", "2026-09-19T10:00:00Z", "Old phone item"),
        )

        assertFalse(imported)
        assertEquals("server-new", store.status().value.of(SnapshotSource.PRIVATE_CATALOG)?.generation)
        assertEquals("Popular one", store.shelves().value.first().items.first().title)
        assertEquals("server-new", disk().readFull(SnapshotSource.PRIVATE_CATALOG)?.generation)
    }

    @Test
    fun `a newer phone import survives an older server and yields to a newer one`() = runTest {
        generation("server-old").publish()
        val store = store()
        store.refresh()

        val phoneTime = "2026-09-22T10:00:00Z"
        assertTrue(
            store.importPrivateSnapshot(
                generation = "phone-new",
                generatedAtMillis = Instant.parse(phoneTime).toEpochMilli(),
                shelves = phoneShelves("phone-new", phoneTime, "Fresh phone item"),
            ),
        )
        assertEquals("phone-new", disk().readFull(SnapshotSource.PRIVATE_CATALOG)?.generation)

        // A manual/forced refresh still must not roll the direct phone transfer backwards.
        store.refresh(force = true)
        assertEquals("phone-new", store.status().value.of(SnapshotSource.PRIVATE_CATALOG)?.generation)
        assertEquals("Fresh phone item", store.shelves().value.first().items.first().title)

        PrivateGeneration(http, "server-new", "2026-09-23T10:00:00Z")
            .shelf("private/v1/owner/popular.json", "tamilmv:main:popular", listOf("Newest server item"))
            .publish()
        store.refresh(force = true)

        assertEquals("server-new", store.status().value.of(SnapshotSource.PRIVATE_CATALOG)?.generation)
        assertEquals("Newest server item", store.shelves().value.first().items.first().title)
    }

    @Test
    fun `equal-time bounded phone import does not replace a complete server snapshot`() = runTest {
        generation("server-new").publish()
        val store = store()
        store.refresh()
        val sameTime = Instant.parse("2026-09-20T10:00:00Z").toEpochMilli()

        val imported = store.importPrivateSnapshot(
            generation = "phone-bounded",
            generatedAtMillis = sameTime,
            shelves = phoneShelves("phone-bounded", "2026-09-20T10:00:00Z", "Bounded phone item"),
        )

        assertFalse(imported)
        assertEquals("server-new", store.status().value.of(SnapshotSource.PRIVATE_CATALOG)?.generation)
        assertEquals("Popular one", store.shelves().value.first().items.first().title)
    }

    @Test
    fun `clear waits out an in-flight private refresh and leaves no private catalog behind`() = runTest {
        PrivateGeneration(http, "slow-private")
            .shelf(
                "private/v1/owner/popular.json",
                "tamilmv:main:popular",
                listOf("Previous owner"),
                delayMillis = 2_000L,
            )
            .publish()
        val store = store()
        val refresh = backgroundScope.launch { store.refresh(force = true) }
        runCurrent()

        val clear = backgroundScope.launch { store.clear(SnapshotSource.PRIVATE_CATALOG) }
        advanceUntilIdle()
        refresh.join()
        clear.join()

        assertNull(disk().readFull(SnapshotSource.PRIVATE_CATALOG))
        assertNull(store.status().value.of(SnapshotSource.PRIVATE_CATALOG)?.generation)
        assertFalse(store.shelves().value.any { it.kind in setOf(
            ShelfKind.TAMILMV_POPULAR,
            ShelfKind.TAMILMV_RECENT,
            ShelfKind.LETTERBOXD,
            ShelfKind.LETTERBOXD_FRIENDS,
        ) })
    }

    // ------------------------------------------------------------- revalidation

    @Test
    fun `an unchanged manifest costs one request and no shards`() = runTest {
        generation("g1").publish(etag = "v1")
        val store = store()
        store.refresh()
        val itemsBefore = store.shelves().value.sumOf { it.items.size }

        http.requests.clear()
        store.refresh()

        assertEquals(
            listOf(CatalogSnapshotContract.privateManifestURL),
            http.privateRequests(),
        )
        assertEquals(itemsBefore, store.shelves().value.sumOf { it.items.size })
        assertEquals(
            SnapshotState.READY,
            store.status().value.of(SnapshotSource.PRIVATE_CATALOG)?.state,
        )
    }

    @Test
    fun `the same generation is not refetched`() = runTest {
        generation("g1").publish()
        val store = store()
        store.refresh()

        http.requests.clear()
        store.refresh()

        // The manifest is read again; the shards are not.
        assertEquals(listOf(CatalogSnapshotContract.privateManifestURL), http.privateRequests())
    }

    // ------------------------------------------------------------- failure handling

    @Test
    fun `a signature that does not verify keeps the last known good shelves`() = runTest {
        generation("g1").publish()
        val store = store()
        store.refresh()
        val before = store.shelves().value

        verifier.failManifest = true
        store.refresh(force = true)

        assertEquals(before.map(Shelf::id), store.shelves().value.map(Shelf::id))
        val status = store.status().value.of(SnapshotSource.PRIVATE_CATALOG)
        assertEquals(SnapshotState.FAILED, status?.state)
        assertTrue(status?.message.orEmpty().contains("signature"))
        assertEquals("g1", disk().readFull(SnapshotSource.PRIVATE_CATALOG)?.generation)
    }

    @Test
    fun `a missing token fails the private source and says what to do`() = runTest {
        generation("g1").publish()
        val store = SnapshotStore(
            filesDir = filesDir,
            http = http,
            verifier = verifier,
            tokenProvider = { null },
            letterboxdUsernames = { emptyList() },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            now = { currentTime },
        )
        store.refresh()

        val status = store.status().value.of(SnapshotSource.PRIVATE_CATALOG)
        assertEquals(SnapshotState.FAILED, status?.state)
        assertTrue(status?.message.orEmpty().contains("token"))
        assertTrue(store.shelves().value.isEmpty())
        assertNull(disk().currentDirectoryName(SnapshotSource.PRIVATE_CATALOG))
    }

    // ------------------------------------------------------------- eviction

    @Test
    fun `eviction keeps the last complete generation and drops the rest`() = runTest {
        val store = store(evictionBudgetBytes = 1L)
        generation("g1").publish()
        store.refresh()
        generation("g2").publish()
        store.refresh(force = true)
        generation("g3").publish()
        store.refresh(force = true)

        val generationDirs = File(File(filesDir, SnapshotDiskCache.ROOT_DIRECTORY), SnapshotSource.PRIVATE_CATALOG.wire)
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name.startsWith(SnapshotDiskCache.GENERATION_PREFIX) }

        assertEquals(1, generationDirs.size)
        assertEquals("g3", disk().readFull(SnapshotSource.PRIVATE_CATALOG)?.generation)
    }

    @Test
    fun `nothing is evicted while the cache fits its budget`() = runTest {
        val store = store()
        generation("g1").publish()
        store.refresh()
        generation("g2").publish()
        store.refresh(force = true)

        val generationDirs = File(File(filesDir, SnapshotDiskCache.ROOT_DIRECTORY), SnapshotSource.PRIVATE_CATALOG.wire)
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name.startsWith(SnapshotDiskCache.GENERATION_PREFIX) }

        assertEquals(2, generationDirs.size)
    }

    // ------------------------------------------------------------- ordering

    @Test
    fun `shelves come out in Home row order`() = runTest {
        generation("g1").publish()
        val store = store()
        store.refresh()

        assertEquals(
            listOf(ShelfKind.TAMILMV_POPULAR, ShelfKind.TAMILMV_RECENT, ShelfKind.LETTERBOXD),
            store.shelves().value.map(Shelf::kind),
        )
        assertFalse(store.shelves().value.any { it.items.isEmpty() })
    }

    @Test
    fun `system search cache exposes only public tmdb shelves and follows promotion`() = runTest {
        fun snapshot(generation: String, publicTitle: String) = StoredSnapshot(
            source = SnapshotSource.PUBLIC_TMDB.wire,
            generation = generation,
            generatedAtMillis = 1L,
            cachedAtMillis = 1L,
            shelves = listOf(
                Shelf(
                    id = "tmdb:movies",
                    title = "Movies",
                    kind = ShelfKind.TMDB_MOVIES,
                    items = listOf(CatalogItem("tmdb:movie:1", "movie", publicTitle)),
                    generation = generation,
                    generatedAtMillis = 1L,
                ),
                Shelf(
                    id = "letterboxd:private",
                    title = "Private",
                    kind = ShelfKind.LETTERBOXD,
                    items = listOf(CatalogItem("private-id", "movie", "Must not leak")),
                    generation = generation,
                    generatedAtMillis = 1L,
                ),
            ),
        )
        disk().promote(SnapshotSource.PUBLIC_TMDB, snapshot("public-1", "Public one"), SnapshotStore::isHeadShelf)
        disk().promote(
            SnapshotSource.PRIVATE_CATALOG,
            snapshot("private-1", "Private source").copy(source = SnapshotSource.PRIVATE_CATALOG.wire),
            SnapshotStore::isHeadShelf,
        )
        val store = store()

        assertEquals(listOf("Public one"), store.cachedPublicTmdbItems().map(CatalogItem::title))

        disk().promote(SnapshotSource.PUBLIC_TMDB, snapshot("public-2", "Public two"), SnapshotStore::isHeadShelf)
        assertEquals(listOf("Public two"), store.cachedPublicTmdbItems().map(CatalogItem::title))
    }

    private fun phoneShelves(generation: String, generatedAt: String, title: String): List<Shelf> {
        val generatedAtMillis = Instant.parse(generatedAt).toEpochMilli()
        return listOf(
            Shelf(
                id = "tamilmv:popular",
                title = "Tamil MV Popular",
                kind = ShelfKind.TAMILMV_POPULAR,
                items = listOf(CatalogItem("phone::tt1000000", "movie", title)),
                generation = generation,
                generatedAtMillis = generatedAtMillis,
            ),
        )
    }
}
