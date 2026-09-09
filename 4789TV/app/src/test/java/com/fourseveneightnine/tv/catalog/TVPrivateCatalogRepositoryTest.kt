package com.fourseveneightnine.tv.catalog

import com.fourseveneightnine.tv.settings.StoredTVSettings
import com.fourseveneightnine.tv.settings.StoredTVSettingsState
import com.fourseveneightnine.tv.settings.TVSettingsPersistence
import com.fourseveneightnine.tv.settings.ValidatedTVSettings
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TVPrivateCatalogRepositoryTest {
    @Test
    fun missingServerTokenUsesSecurePhoneCatalogCache() = runTest {
        val imported = snapshot("phone-copy")
        val cache = FakeCache(imported)
        val repository = TVPrivateCatalogRepository(
            settings = FakeSettings(StoredTVSettingsState.Missing),
            loader = TVPrivateCatalogLoader { _, _ -> error("server must not be called") },
            cache = cache,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        repository.refresh(force = true)

        val state = repository.state.value as TVTamilMVCatalogState.Ready
        assertEquals(imported, state.snapshot)
        assertTrue(state.isStale)
        assertTrue(state.notice.orEmpty().contains("copied securely"))
    }
    @Test
    fun missingCredentialDoesNotContactPrivateCatalog() = runTest {
        var loads = 0
        val repository = repository(
            settingsState = StoredTVSettingsState.Missing,
            loader = TVPrivateCatalogLoader { _, _ ->
                loads += 1
                snapshot("unexpected")
            },
        )

        repository.refresh(force = true)

        assertTrue(repository.state.value is TVTamilMVCatalogState.MissingCredential)
        assertEquals(0, loads)
    }

    @Test
    fun successfulRefreshPublishesAndCachesBothShelves() = runTest {
        val cache = FakeCache()
        val expected = snapshot("live")
        val repository = repository(loader = TVPrivateCatalogLoader { _, _ -> expected }, cache = cache)

        repository.refresh(force = true)

        val ready = repository.state.value as TVTamilMVCatalogState.Ready
        assertEquals(expected, ready.snapshot)
        assertEquals(expected, cache.value)
        assertEquals(2, ready.snapshot.itemCount)
    }

    @Test
    fun tamilMvCountDoesNotIncludeLetterboxdRows() {
        val snapshot = TVTamilMVCatalogSnapshot(
            generation = "mixed",
            generatedAtMillis = 100,
            cachedAtMillis = 200,
            popular = listOf(TVTamilMVCatalogItem("popular", "movie", "Popular")),
            recent = listOf(TVTamilMVCatalogItem("recent", "movie", "Recent")),
            letterboxd = listOf(TVTamilMVCatalogItem("letterboxd", "movie", "Fist of Fury")),
        )

        assertEquals(2, snapshot.tamilMVItemCount)
        assertEquals(3, snapshot.itemCount)
    }

    @Test
    fun tamilMvImportKeepsTheFullObservedHomepageBeyondOneThousandRows() {
        val snapshot = TVTamilMVCatalogSnapshot(
            generation = "homepage-1008",
            generatedAtMillis = 100,
            cachedAtMillis = 200,
            popular = (0 until 1_008).map { index ->
                TVTamilMVCatalogItem("tamil-$index", "movie", "Tamil title $index")
            },
            recent = emptyList(),
        )

        assertEquals(1_008, TVTamilMVCatalogImportPolicy.validate(snapshot).popular.size)
        assertEquals(1_008, snapshot.tamilMVItemCount)
    }

    @Test
    fun tmdbCachedSnapshotRemainsVisibleDuringRefreshAndFailure() {
        val cached = TVTMDBCatalogSnapshot(
            generatedAtMillis = 100,
            cachedAtMillis = 200,
            movies = listOf(TVTamilMVCatalogItem("movie", "movie", "Movie")),
            series = listOf(TVTamilMVCatalogItem("series", "series", "Series")),
        )

        val loading = TVTMDBCatalogState.Loading(cached)
        assertEquals(cached, loading.visibleSnapshot())
        assertEquals(cached.movies, loading.visibleSnapshot()?.movies)

        val failed = TVTMDBCatalogState.Error("offline", cached)
        assertEquals(cached, failed.visibleSnapshot())
        val ready = cached.asReady()
        assertEquals(cached.movies, ready.movies)
        assertEquals(cached.series, ready.series)
    }

    @Test
    fun tmdbCachePolicyRejectsOversizedOrUntrustedRows() {
        val oversized = TVTMDBCatalogSnapshot(
            generatedAtMillis = 100,
            cachedAtMillis = 200,
            movies = (0..1_000).map { index -> TVTamilMVCatalogItem("movie-$index", "movie", "Movie $index") },
            series = emptyList(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            TVTMDBCatalogCachePolicy.validate(oversized)
        }

        val untrustedArtwork = TVTMDBCatalogSnapshot(
            generatedAtMillis = 100,
            cachedAtMillis = 200,
            movies = listOf(TVTamilMVCatalogItem("movie", "movie", "Movie", posterURL = "http://not-https")),
            series = emptyList(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            TVTMDBCatalogCachePolicy.validate(untrustedArtwork)
        }
    }

    @Test
    fun letterboxdCatalogsRemainSeparateShelvesWhileTheFlatFallbackStaysCompatible() {
        val first = TVTamilMVCatalogItem("first", "movie", "First")
        val second = TVTamilMVCatalogItem("second", "movie", "Second")
        val snapshot = TVTamilMVCatalogSnapshot(
            generation = "letterboxd-lists",
            generatedAtMillis = 100,
            cachedAtMillis = 200,
            popular = emptyList(),
            recent = emptyList(),
            letterboxd = listOf(first, second),
            letterboxdShelves = listOf(
                TVLetterboxdShelf("letterboxd:custom:one", "One", listOf(first)),
                TVLetterboxdShelf("letterboxd:custom:two", "Two", listOf(second)),
            ),
        )

        val validated = TVTamilMVCatalogImportPolicy.validate(snapshot)
        assertEquals(2, validated.letterboxdShelves.size)
        assertEquals(listOf("One", "Two"), validated.letterboxdShelves.map(TVLetterboxdShelf::title))
        assertEquals(2, validated.itemCount)
    }

    @Test
    fun iphoneWirePayloadDecodesLetterboxdShelves() {
        val payload = """
            {
              "cachedAtMillis": 200,
              "friends": [],
              "generatedAtMillis": 100,
              "generation": "iphone-wire",
              "letterboxd": [{
                "genres": [],
                "id": "tmdb:550",
                "imdbID": "tt0137523",
                "mediaType": "movie",
                "posterURL": "https://image.tmdb.org/t/p/w500/poster.jpg",
                "title": "Fight Club",
                "tmdbID": 550,
                "year": 1999
              }],
              "letterboxdShelves": [{
                "id": "letterboxd:owned:filmfan/my-list",
                "items": [{
                  "genres": [],
                  "id": "tmdb:550",
                  "imdbID": "tt0137523",
                  "mediaType": "movie",
                  "posterURL": "https://image.tmdb.org/t/p/w500/poster.jpg",
                  "title": "Fight Club",
                  "tmdbID": 550,
                  "year": 1999
                }],
                "title": "My list"
              }],
              "popular": [],
              "recent": [],
              "schemaVersion": 1
            }
        """.trimIndent().encodeToByteArray()

        val decoded = TVTamilMVCatalogImportPolicy.decode(payload)

        assertEquals(listOf("My list"), decoded.letterboxdShelves.map(TVLetterboxdShelf::title))
        assertEquals("Fight Club", decoded.letterboxdShelves.single().items.single().title)
        assertEquals("tmdb:550", decoded.letterboxd.single().id)
    }

    @Test
    fun networkFailureKeepsLastGoodSnapshotVisible() = runTest {
        val cached = snapshot("cached")
        val cache = FakeCache(cached)
        val repository = repository(
            loader = TVPrivateCatalogLoader { _, _ -> throw TVPrivateCatalogHTTPException(503) },
            cache = cache,
        )

        repository.refresh(force = true)

        val failed = repository.state.value as TVTamilMVCatalogState.Error
        assertEquals(cached, failed.cached)
        assertTrue(failed.message.contains("server 503"))
        assertEquals(cached, cache.value)
    }

    @Test
    fun rapidRefreshPressesCoalesceIntoOneInFlightLoad() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val release = CompletableDeferred<Unit>()
        var loads = 0
        val repository = repository(
            loader = TVPrivateCatalogLoader { _, _ ->
                loads += 1
                release.await()
                snapshot("live")
            },
            dispatcher = dispatcher,
        )

        val first = launch { repository.refresh(force = true) }
        runCurrent()
        val second = launch { repository.refresh(force = true) }
        runCurrent()
        assertEquals(1, loads)

        release.complete(Unit)
        advanceUntilIdle()
        first.join()
        second.join()
        assertTrue(repository.state.value is TVTamilMVCatalogState.Ready)
    }

    @Test
    fun cancelledRefreshRestoresPriorState() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val waiting = CompletableDeferred<TVTamilMVCatalogSnapshot>()
        val repository = repository(
            loader = TVPrivateCatalogLoader { _, _ -> waiting.await() },
            dispatcher = dispatcher,
        )
        val task = launch { repository.refresh(force = true) }
        runCurrent()
        task.cancel()
        advanceUntilIdle()

        assertTrue(repository.state.value is TVTamilMVCatalogState.Loading)
    }

    @Test
    fun clearRemovesPrivateMetadataAndCredentialPolicyRejectsHeaderInjection() = runTest {
        val cache = FakeCache(snapshot("cached"))
        val repository = repository(loader = TVPrivateCatalogLoader { _, _ -> snapshot("live") }, cache = cache)
        repository.clear()

        assertNull(cache.value)
        assertTrue(repository.state.value is TVTamilMVCatalogState.MissingCredential)
        assertNull(
            TVPrivateCatalogCredentialPolicy.token(
                settingsState("bad\r\ntoken"),
            ),
        )
    }

    @Test
    fun credentialInspectionExposesPresenceWithoutExposingValue() {
        val info = TVPrivateCatalogCredentialPolicy.inspect(settingsState("catalog-token"))
        assertTrue(info.present)
        assertEquals("catalog-token".length, info.length)
        assertEquals(emptyList<String>(), TVPrivateCatalogCredentialPolicy.letterboxdUsernames(settingsState("catalog-token")))
    }

    @Test
    fun boundedReaderRejectsDeclaredAndStreamingOverflow() {
        assertArrayEquals(
            byteArrayOf(1, 2, 3),
            BoundedCatalogResponse.read(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3, 3),
        )
        assertThrows(IllegalArgumentException::class.java) {
            BoundedCatalogResponse.read(ByteArrayInputStream(byteArrayOf(1)), 4, 3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BoundedCatalogResponse.read(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), -1, 3)
        }
    }

    /**
     * The whole point of the partial-paint path: a half-loaded catalog is fine to LOOK at and
     * must never be written to disk. Cached, it would look complete on the next cold start and
     * the missing shelves would never come back.
     */
    @Test
    fun partialSnapshotPaintsButIsNeverCached() = runTest {
        val cache = FakeCache()
        val partial = snapshot("partial")
        val full = snapshot("full")
        val repository = repository(
            loader = TVPrivateCatalogLoader { _, onPartial ->
                onPartial(partial)
                full
            },
            cache = cache,
        )

        repository.refresh(force = true)

        assertEquals("only the complete snapshot may be cached", full.generation, cache.value?.generation)
        val state = repository.state.value
        assertTrue(state is TVTamilMVCatalogState.Ready)
        assertEquals(full.generation, (state as TVTamilMVCatalogState.Ready).snapshot.generation)
    }

    private fun repository(
        settingsState: StoredTVSettingsState = settingsState("catalog-token"),
        loader: TVPrivateCatalogLoader,
        cache: FakeCache = FakeCache(),
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ): TVPrivateCatalogRepository = TVPrivateCatalogRepository(
        settings = FakeSettings(settingsState),
        loader = loader,
        cache = cache,
        ioDispatcher = dispatcher,
        nowMillis = { 1_000L },
    )

    private fun settingsState(token: String): StoredTVSettingsState = StoredTVSettingsState.Available(
        StoredTVSettings(
            rawJson = """{"format":"4789-settings","version":1,"catalogServerToken":"$token"}""",
            revision = 1,
            savedAtMillis = 1,
        ),
    )

    private fun snapshot(generation: String): TVTamilMVCatalogSnapshot = TVTamilMVCatalogSnapshot(
        generation = generation,
        generatedAtMillis = 100,
        cachedAtMillis = 200,
        popular = listOf(TVTamilMVCatalogItem("popular-$generation", "movie", "Popular")),
        recent = listOf(TVTamilMVCatalogItem("recent-$generation", "movie", "Recent")),
    )

    private class FakeCache(initial: TVTamilMVCatalogSnapshot? = null) : TVTamilMVCatalogCache {
        var value: TVTamilMVCatalogSnapshot? = initial
        override fun load(): TVTamilMVCatalogSnapshot? = value
        override fun store(snapshot: TVTamilMVCatalogSnapshot) { value = snapshot }
        override fun clear() { value = null }
    }

    private class FakeSettings(private val state: StoredTVSettingsState) : TVSettingsPersistence {
        override fun load(): StoredTVSettingsState = state
        override fun save(settings: ValidatedTVSettings, revision: Long, syncKey: ByteArray?): Boolean = true
        override fun clearSync(): Boolean = true
        override fun clearAll(): Boolean = true
    }
}
