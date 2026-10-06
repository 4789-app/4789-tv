package com.fourseveneightnine.tv.client.data.meta

import com.fourseveneightnine.tv.client.data.FakeAnswer
import com.fourseveneightnine.tv.client.data.FakeHttp
import com.fourseveneightnine.tv.client.data.addons.AddonRegistry
import com.fourseveneightnine.tv.client.data.addons.InMemoryAddonHealthStore
import com.fourseveneightnine.tv.client.data.addons.StremioClient
import com.fourseveneightnine.tv.client.data.fixture
import com.fourseveneightnine.tv.client.data.settings.SettingsDocument
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test

class MetaRepositoryTest {

    private val directory = File(
        System.getProperty("java.io.tmpdir"),
        "client-data-meta-${System.nanoTime()}",
    ).apply { mkdirs() }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private val tmdbMovie = """
        {
          "id": 27205,
          "runtime": 148,
          "overview": "A thief who steals corporate secrets.",
          "images": {
            "logos": [
              { "file_path": "/logo-en.png", "iso_639_1": "en", "vote_average": 5.0 },
              { "file_path": "/logo-none.png", "iso_639_1": null, "vote_average": 4.0 }
            ],
            "backdrops": [
              { "file_path": "/clean.jpg", "iso_639_1": null, "vote_average": 6.0 },
              { "file_path": "/titled.jpg", "iso_639_1": "en", "vote_average": 9.0 }
            ]
          },
          "credits": {
            "cast": [
              { "name": "Leonardo DiCaprio", "character": "Cobb", "profile_path": "/leo.jpg" },
              { "name": "Elliot Page", "character": "Ariadne", "profile_path": "/elliot.jpg" }
            ]
          },
          "videos": { "results": [
            { "site": "YouTube", "type": "Teaser", "key": "TEASER1" },
            { "site": "YouTube", "type": "Trailer", "key": "TRAILER1" }
          ] },
          "release_dates": { "results": [
            { "iso_3166_1": "US", "release_dates": [{ "certification": "PG-13" }] }
          ] }
        }
    """.trimIndent()

    private fun repository(
        http: FakeHttp,
        tmdbKey: String? = null,
        cacheDir: File? = directory,
        clock: () -> Long = System::currentTimeMillis,
    ): MetaRepository {
        val client = StremioClient(http.client)
        val registry = AddonRegistry(
            SettingsDocument.parse("""{"sources":[]}"""),
            client,
            InMemoryAddonHealthStore(),
        )
        return MetaRepository(
            registry = registry,
            client = client,
            tmdbKey = tmdbKey,
            cacheDir = cacheDir,
            clock = clock,
            okHttp = http.client,
            tmdbBaseURL = "https://tmdb.test/3",
        )
    }

    @Test fun `metadata memory cache evicts old visits while retaining recent titles`() = runBlocking {
        val http = FakeHttp { FakeAnswer(body = fixture("cinemeta-movie.json")) }
        val repo = repository(http, cacheDir = null)
        for (id in 0..MetaRepository.MAX_MEMORY_ENTRIES) repo.meta("movie", "tt$id")
        val fetched = http.requests.size
        repo.meta("movie", "tt${MetaRepository.MAX_MEMORY_ENTRIES}")
        assertEquals(fetched, http.requests.size)
        assertNull(repo.peek("movie", "tt0"))
        repo.meta("movie", "tt0")
        assertEquals(fetched + 1, http.requests.size)
    }

    @Test fun `disk metadata runs off caller thread and base callback stays on caller`() = runBlocking {
        val threads = CopyOnWriteArrayList<Thread>()
        val clock = { threads.add(Thread.currentThread()); 1_000L }
        val http = FakeHttp { FakeAnswer(body = fixture("cinemeta-movie.json")) }
        val uiThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        val ui = Executors.newSingleThreadExecutor { task ->
            Thread(task, "tv-test-ui").also(uiThread::set)
        }.asCoroutineDispatcher()
        try {
            withContext(ui) { repository(http, clock = clock).meta("movie", "tt1375666") }
            assertTrue(threads.any { it !== uiThread.get() })
            threads.clear()
            val fetched = http.requests.size
            withContext(ui) {
                repository(http, clock = clock).meta("movie", "tt1375666") {
                    assertSame(uiThread.get(), Thread.currentThread())
                }
            }
            assertEquals(fetched, http.requests.size)
            assertTrue(threads.any { it !== uiThread.get() })
        } finally { ui.close() }
    }

    @Test fun `active metadata request takes over cancelled owner instead of caching incomplete cast`() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        val http = FakeHttp { request ->
            when {
                request.url.toString().contains("/find/") -> FakeAnswer(body = "{\"movie_results\":[{\"id\":27205}]}")
                request.url.host == "tmdb.test" -> {
                    if (first.compareAndSet(true, false)) { started.countDown(); release.await(3, TimeUnit.SECONDS) }
                    FakeAnswer(body = tmdbMovie)
                }
                else -> FakeAnswer(body = fixture("cinemeta-movie.json"))
            }
        }
        val repo = repository(http, tmdbKey = "tmdbkey")
        val owner = async(Dispatchers.Default) { repo.meta("movie", "tt1375666") }
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS))
            val base = CompletableDeferred<Meta>()
            val detail = async(Dispatchers.Default) { repo.meta("movie", "tt1375666") { base.complete(it) } }
            withTimeout(3_000) { base.await() }
            owner.cancel()
            release.countDown()
            owner.join()
            val completed = withTimeout(5_000) { detail.await() }
            assertEquals("PG-13", completed?.certification)
            assertEquals("https://image.tmdb.org/t/p/w185/leo.jpg", completed?.cast?.first()?.photo)
        } finally { release.countDown(); owner.cancel() }
    }

    @Test
    fun `Cinemeta answers on its own when there is no TMDB key`() = runTest {
        val http = FakeHttp { FakeAnswer(body = fixture("cinemeta-movie.json")) }
        val meta = requireNotNull(repository(http).meta("movie", "tt1375666"))
        assertEquals("Inception", meta.title)
        assertEquals(148, meta.runtimeMinutes)
        assertNull(meta.certification)
        assertTrue(http.urls.none { it.contains("tmdb.test") })
    }

    @Test
    fun `TMDB fills only the gaps Cinemeta left`() = runTest {
        val http = FakeHttp { request ->
            when {
                request.url.host == "tmdb.test" && request.url.toString().contains("/find/") ->
                    FakeAnswer(body = """{"movie_results":[{"id":27205}]}""")
                request.url.host == "tmdb.test" -> FakeAnswer(body = tmdbMovie)
                else -> FakeAnswer(body = fixture("cinemeta-movie.json"))
            }
        }
        val meta = requireNotNull(repository(http, tmdbKey = "tmdbkey").meta("movie", "tt1375666"))
        // Cinemeta already had these, so they are not overwritten.
        assertEquals("https://images.metahub.space/logo/medium/tt1375666/img", meta.logo)
        assertEquals("YoHD9XEInc0", meta.trailerYouTubeID)
        // These it did not have.
        assertEquals("PG-13", meta.certification)
        assertEquals(27205, meta.tmdbID)
        assertEquals("https://image.tmdb.org/t/p/w185/leo.jpg", meta.cast.first().photo)
        assertEquals("Cobb", meta.cast.first().role)
    }

    @Test
    fun `base metadata is available before TMDB enrichment finishes`() = runTest {
        val http = FakeHttp { request ->
            when {
                request.url.host == "tmdb.test" && request.url.toString().contains("/find/") ->
                    FakeAnswer(body = """{"movie_results":[{"id":27205}]}""")
                request.url.host == "tmdb.test" -> FakeAnswer(body = tmdbMovie)
                else -> FakeAnswer(body = fixture("cinemeta-movie.json"))
            }
        }
        var baseShown = false
        val meta = requireNotNull(repository(http, tmdbKey = "tmdbkey").meta("movie", "tt1375666") { base ->
            assertEquals("Inception", base.title)
            assertTrue(http.urls.none { it.contains("tmdb.test") })
            baseShown = true
        })
        assertTrue(baseShown)
        assertEquals("PG-13", meta.certification)
    }

    @Test
    fun `Detail joining a prefetch receives base metadata before enrichment finishes`() = runBlocking {
        val enrichmentStarted = CountDownLatch(1)
        val allowEnrichment = CountDownLatch(1)
        val http = FakeHttp { request ->
            when {
                request.url.host == "tmdb.test" -> {
                    enrichmentStarted.countDown()
                    allowEnrichment.await(3, TimeUnit.SECONDS)
                    if (request.url.toString().contains("/find/")) {
                        FakeAnswer(body = """{"movie_results":[{"id":27205}]}""")
                    } else FakeAnswer(body = tmdbMovie)
                }
                else -> FakeAnswer(body = fixture("cinemeta-movie.json"))
            }
        }
        val repository = repository(http, tmdbKey = "tmdbkey")
        try {
            val prefetch = async(Dispatchers.Default) { repository.meta("movie", "tt1375666") }
            assertTrue(enrichmentStarted.await(3, TimeUnit.SECONDS))
            assertEquals("Inception", repository.peek("movie", "tt1375666")?.title)
            assertFalse(prefetch.isCompleted)
            val baseShown = CompletableDeferred<Meta>()
            val detail = async(Dispatchers.Default) {
                repository.meta("movie", "tt1375666") { baseShown.complete(it) }
            }
            assertEquals("Inception", withTimeout(1_000) { baseShown.await() }.title)
            assertFalse(prefetch.isCompleted)
            allowEnrichment.countDown()
            assertEquals("PG-13", detail.await()?.certification)
            assertEquals("PG-13", prefetch.await()?.certification)
        } finally {
            allowEnrichment.countDown()
        }
    }

    @Test
    fun `a backdrop with no burnt-in titling wins over a better-rated English one`() = runTest {
        val cinemetaWithoutArt = fixture("cinemeta-movie.json")
            .replace("\"background\": \"https://images.metahub.space/background/medium/tt1375666/img\",", "")
            .replace("\"logo\": \"https://images.metahub.space/logo/medium/tt1375666/img\",", "")
        val http = FakeHttp { request ->
            when {
                request.url.host == "tmdb.test" && request.url.toString().contains("/find/") ->
                    FakeAnswer(body = """{"movie_results":[{"id":27205}]}""")
                request.url.host == "tmdb.test" -> FakeAnswer(body = tmdbMovie)
                else -> FakeAnswer(body = cinemetaWithoutArt)
            }
        }
        val meta = requireNotNull(repository(http, tmdbKey = "tmdbkey").meta("movie", "tt1375666"))
        assertEquals("https://image.tmdb.org/t/p/w1280/clean.jpg", meta.backdrop)
        // A logo, by contrast, WANTS its lettering, so the English one is fine.
        assertEquals("https://image.tmdb.org/t/p/w500/logo-en.png", meta.logo)
    }

    @Test
    fun `a TMDB failure leaves the Cinemeta answer intact`() = runTest {
        val http = FakeHttp { request ->
            if (request.url.host == "tmdb.test") FakeAnswer(code = 500, body = "down")
            else FakeAnswer(body = fixture("cinemeta-movie.json"))
        }
        val meta = requireNotNull(repository(http, tmdbKey = "tmdbkey").meta("movie", "tt1375666"))
        assertEquals("Inception", meta.title)
    }

    @Test
    fun `a repeat visit inside the day costs nothing`() = runTest {
        val http = FakeHttp { FakeAnswer(body = fixture("cinemeta-series.json")) }
        val repository = repository(http)
        repository.meta("series", "tt7366338")
        val after = http.requests.size
        repository.meta("series", "tt7366338")
        assertEquals(after, http.requests.size)
    }

    @Test
    fun `the disk cache survives a new repository`() = runTest {
        val http = FakeHttp { FakeAnswer(body = fixture("cinemeta-series.json")) }
        repository(http).meta("series", "tt7366338")
        val after = http.requests.size
        val meta = requireNotNull(repository(http).meta("series", "tt7366338"))
        assertEquals("Chernobyl", meta.title)
        assertEquals(5, meta.episodes(1).size)
        assertEquals(after, http.requests.size)
    }

    @Test
    fun `an id with a slash cannot become a path`() = runTest {
        val http = FakeHttp { FakeAnswer(body = fixture("cinemeta-movie.json")) }
        repository(http).meta("movie", "tt1375666")
        val names = directory.resolve("meta").listFiles().orEmpty().map(File::getName)
        assertTrue(names.isNotEmpty())
        assertTrue(names.all { it.matches(Regex("[a-f0-9]{64}\\.json")) })
    }

    @Test
    fun `no add-on knowing the title gives null, not an exception`() = runTest {
        val http = FakeHttp { FakeAnswer(code = 404, body = "{}") }
        assertNull(repository(http).meta("movie", "tt0000001"))
    }

    @Test
    fun `clear empties both the memory and the disk cache`() = runTest {
        val http = FakeHttp { FakeAnswer(body = fixture("cinemeta-movie.json")) }
        val repository = repository(http)
        repository.meta("movie", "tt1375666")
        repository.clear()
        assertFalse(directory.resolve("meta").listFiles().orEmpty().any { it.name.endsWith(".json") })
        repository.meta("movie", "tt1375666")
        assertNotNull(repository.meta("movie", "tt1375666"))
        assertTrue(http.requests.size >= 2)
    }
    @Test fun `clear prevents an in flight metadata owner repopulating memory or disk`() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val http = FakeHttp { request ->
            when {
                request.url.toString().contains("/find/") -> FakeAnswer(body = "{\"movie_results\":[{\"id\":27205}]}")
                request.url.host == "tmdb.test" -> {
                    started.countDown()
                    release.await(3, TimeUnit.SECONDS)
                    FakeAnswer(body = tmdbMovie)
                }
                else -> FakeAnswer(body = fixture("cinemeta-movie.json"))
            }
        }
        val repo = repository(http, tmdbKey = "fixture-key")
        val owner = async(Dispatchers.Default) { repo.meta("movie", "tt1375666") }
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS))
            repo.clear()
            release.countDown()
            assertNotNull(withTimeout(3_000) { owner.await() })
            assertNull(repo.peek("movie", "tt1375666"))
            assertTrue(directory.walkTopDown().none { it.isFile })
        } finally { release.countDown(); owner.cancel() }
    }

}
