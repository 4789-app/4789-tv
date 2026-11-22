package com.fourseveneightnine.tv.client.data.catalog

import com.fourseveneightnine.contract.CatalogSnapshotContract
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertArrayEquals
import java.time.Instant
import java.security.MessageDigest
import com.fourseveneightnine.contract.CatalogArtifact
import com.fourseveneightnine.contract.CatalogManifestPayload
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
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

    @Test
    fun `catalog HTTP keeps raw bytes headers limits and exact status semantics`() = runBlocking(Dispatchers.IO) {
        var code = 200
        var bytes = byteArrayOf(0, 0x80.toByte(), 0xff.toByte())
        var declaredLength = bytes.size.toLong()
        var observed: Request? = null
        var responses = 0
        var closedBodies = 0
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .addInterceptor { chain ->
                observed = chain.request()
                responses++
                val buffer = Buffer().write(bytes)
                val body = object : ResponseBody() {
                    override fun contentType(): MediaType? = null
                    override fun contentLength(): Long = declaredLength
                    override fun source(): BufferedSource = buffer
                    override fun close() { closedBodies++; super.close() }
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(code).message("fixture").header("ETag", "\"v1\"")
                    .header("Location", "https://unused.example/redirect")
                    .body(body).build()
            }.build()
        try {
            val transport = OkHttpCatalogHttp(client, ownerId = "fixture-owner")
            val url = "https://api.4789library.com/fixture.json"
            val body = transport.get(url, "fixture-token", "\"previous\"", 3) as CatalogHttpResult.Body
            assertArrayEquals(bytes, body.bytes)
            assertEquals(1, closedBodies)
            assertEquals("\"v1\"", body.etag)
            assertEquals("application/json", observed?.header("Accept"))
            assertEquals("Bearer fixture-token", observed?.header("Authorization"))
            assertEquals("fixture-owner", observed?.header("X-4789-Owner"))
            assertEquals("\"previous\"", observed?.header("If-None-Match"))

            code = 304
            bytes = byteArrayOf()
            declaredLength = 0L
            assertEquals(CatalogHttpResult.NotModified, transport.get(url, null, null, 3))
            assertNull(observed?.header("Authorization"))
            assertNull(observed?.header("X-4789-Owner"))
            assertNull(observed?.header("If-None-Match"))

            for (status in listOf(204, 302, 401, 500)) {
                code = status
                val failure = runCatching { transport.get(url, null, null, 3) }.exceptionOrNull()
                assertTrue("status=$status", failure is CatalogHttpException)
                assertEquals(status, (failure as CatalogHttpException).statusCode)
            }
            code = 200
            bytes = ByteArray(4)
            declaredLength = 4L
            assertEquals("content_length", runCatching { transport.get(url, null, null, 3) }.exceptionOrNull()?.message)
            declaredLength = -1L
            assertEquals("response_size", runCatching { transport.get(url, null, null, 3) }.exceptionOrNull()?.message)
            bytes = byteArrayOf()
            declaredLength = 0L
            assertEquals("content_length", runCatching { transport.get(url, null, null, 3) }.exceptionOrNull()?.message)
            declaredLength = -1L
            assertEquals("empty_response", runCatching { transport.get(url, null, null, 3) }.exceptionOrNull()?.message)
            assertEquals(10, responses)
            assertEquals(responses, closedBodies)
        } finally {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }

    @Test
    fun `cancelling a stalled catalog body cancels its call and lets private clear finish`() = runBlocking(Dispatchers.IO) {
        val readingBody = CompletableDeferred<Unit>()
        val disconnected = CompletableDeferred<Unit>()
        val observedCall = AtomicReference<Call?>()
        val peer = AtomicReference<Socket?>()
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val worker = thread(name = "catalog-cancel-fixture", isDaemon = true) {
            try {
                server.accept().use { socket ->
                    peer.set(socket)
                    socket.soTimeout = 15_000
                    val input = socket.getInputStream()
                    val headers = input.bufferedReader(Charsets.US_ASCII)
                    while (true) {
                        val line = headers.readLine() ?: error("Request ended before its headers")
                        if (line.isEmpty()) break
                    }
                    socket.getOutputStream().apply {
                        write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                            "Content-Length: 100\r\nConnection: close\r\n\r\n{").toByteArray(Charsets.US_ASCII))
                        flush()
                    }
                    // Leave the declared body incomplete. EOF/reset must come from client cancellation.
                    try { while (input.read() >= 0) { } } catch (_: IOException) { }
                    disconnected.complete(Unit)
                }
            } catch (failure: Exception) {
                readingBody.completeExceptionally(failure)
                disconnected.completeExceptionally(failure)
            }
        }
        val client = OkHttpClient.Builder()
            .proxy(java.net.Proxy.NO_PROXY)
            .protocols(listOf(Protocol.HTTP_1_1))
            .followRedirects(false).followSslRedirects(false)
            .readTimeout(30, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
            .eventListener(object : EventListener() {
                override fun callStart(call: Call) { observedCall.set(call) }
                override fun responseBodyStart(call: Call) { readingBody.complete(Unit) }
            })
            .addInterceptor { chain ->
                // Admission still receives the real allowed HTTPS catalog host. Only this test's
                // injected client routes its request to the disposable loopback fixture.
                val local = chain.request().url.newBuilder().scheme("http").host("127.0.0.1")
                    .port(server.localPort).build()
                chain.proceed(chain.request().newBuilder().url(local).build())
            }.build()
        val transport = OkHttpCatalogHttp(client)
        val http = object : CatalogHttp {
            override suspend fun get(url: String, token: String?, ifNoneMatch: String?, maximumBytes: Int): CatalogHttpResult {
                if (url != CatalogSnapshotContract.privateManifestURL) throw CatalogHttpException(404)
                return transport.get(url, token, ifNoneMatch, maximumBytes)
            }
        }
        var refresh: Job? = null
        var clear: Deferred<Unit>? = null
        try {
            val generation = "saved-before-cancellation"
            val saved = StoredSnapshot(source = SnapshotSource.PRIVATE_CATALOG.wire,
                generation = generation, generatedAtMillis = 1L, cachedAtMillis = 1L,
                shelves = listOf(Shelf("letterboxd:saved", "Saved", ShelfKind.LETTERBOXD,
                    listOf(CatalogItem("tt1000001", "movie", "Saved title")), generation, 1L)))
            disk().promote(SnapshotSource.PRIVATE_CATALOG, saved, SnapshotStore::isHeadShelf)
            val store = SnapshotStore(filesDir, http, FakeSnapshotVerifier(), tokenProvider = { "fixture-token" },
                ioDispatcher = Dispatchers.IO)
            store.hydrate()
            assertTrue(store.shelves().value.isNotEmpty())
            refresh = launch { store.refresh(force = true) }
            withTimeout(5_000L) { readingBody.await() }
            assertFalse(requireNotNull(refresh).isCompleted)
            clear = async(start = CoroutineStart.UNDISPATCHED) { store.clear(SnapshotSource.PRIVATE_CATALOG) }
            assertFalse(requireNotNull(clear).isCompleted)

            withTimeout(5_000L) {
                requireNotNull(refresh).cancelAndJoin()
                requireNotNull(clear).await()
                disconnected.await()
            }
            assertTrue(requireNotNull(observedCall.get()).isCanceled())
            assertTrue(store.shelves().value.isEmpty())
            assertEquals(SnapshotState.IDLE, store.status().value.of(SnapshotSource.PRIVATE_CATALOG)?.state)
            assertNull(disk().readFull(SnapshotSource.PRIVATE_CATALOG))
        } finally {
            refresh?.cancel()
            clear?.cancel()
            client.dispatcher.cancelAll()
            runCatching { peer.get()?.close() }
            runCatching { server.close() }
            worker.join(5_000L)
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
            withTimeout(5_000L) { refresh?.join(); clear?.join() }
        }
    }
    @Test
    fun `server metadata never removes paired Library or replaces its saved sources`() {
        val source = CatalogPlaybackSource("0123456789abcdef0123456789abcdef01234567", "Movie.mkv", 1, 7)
        val saved = Shelf("tamilmv:popular", "Tamil", ShelfKind.TAMILMV_POPULAR,
            listOf(CatalogItem("saved:tamilmv:movie", "movie", "Movie", playbackSources = listOf(source))), "phone", 1)
        val library = saved.copy(id = "library:phone", kind = ShelfKind.LIBRARY)
        val server = saved.copy(items = emptyList(), generation = "server", generatedAtMillis = 2)
        assertEquals(listOf(saved, library), SnapshotStore.retainSavedShelves(listOf(server), listOf(saved, library)))
    }
    @Test
    fun `receiver listing reads visible quality-link titles and padded episode identity`() {
        val html = """<p>The Dark Heaven (2026) Tamil - <a href="https://www.1tamilmv.style/index.php?/forums/topic/22-old-slug/">[4K &amp; 1080p]</a></p>
          <p><a href="https://www.1tamilmv.style/index.php?/forums/topic/23-bigg-boss/">Bigg Boss (2026) S010EP53 Tamil</a></p>
          <a href="https://other.example/index.php?/forums/topic/24-film/">Wrong host (2026)</a>
          <a href="https://www.1tamilmv.style/index.php?/forums/topic/25-audio/">Audio Songs (2026) MP3</a>
          <a href="https://www.1tamilmv.style/index.php?/forums/topic/26-numeric/">2018 (2023) Tamil</a>"""
        val rows = TamilMVCatalogRepository.parseListing(html, "https://www.1tamilmv.style")
        assertEquals(listOf("The Dark Heaven", "Bigg Boss", "2018"), rows.map { it.title })
        assertEquals(10, rows[1].season)
        assertEquals(53, rows[1].episode)
        assertEquals("series", rows[1].mediaType)
        assertEquals(2023, rows[2].year)
    }

    @Test
    fun `receiver ranks clean prints before camera copies and prefers quality then version`() {
        val names = listOf("Film.2160p.HDCAM", "Film.1080p.WEB-DL", "Film.2160p.WEB-DL.v1", "Film.2160p.WEB-DL.v2")
        val html = names.mapIndexed { index, name ->
            """<a href="magnet:?xt=urn:btih:${index.toString().repeat(40)}&amp;dn=$name">Magnet</a>"""
        }.joinToString("")
        val item = CatalogItem("saved:tamilmv:topic:1", "movie", "Film")
        val sources = TamilMVCatalogRepository.parseMagnets(html, item)
        assertEquals(listOf(names[3], names[2], names[1], names[0]), sources.map { it.filename })
    }

    @Test
    fun `hybrid season pack declares all episodes from its display name`() {
        val hash = "a".repeat(40)
        val v2 = "1220" + "b".repeat(64)
        val html = """<a href="magnet:?xt=urn:btih:$hash&amp;xt=urn:btmh:$v2&amp;dn=Campus%20Drivers%20%282026%29%20S01%20EP%20%2801-06%29">Pack</a>"""
        val item = CatalogItem("saved:tamilmv:topic:1", "series", "Campus Drivers", season = 1)
        val source = TamilMVCatalogRepository.parseMagnets(html, item).single()
        assertEquals(hash, source.infohash)
        assertEquals(v2.drop(4), source.v2Infohash)
        assertEquals((1..6).toList(), source.episodes)
    }

    @Test
    fun `topic art accepts a known portrait and ignores screenshots and later comments`() {
        val url = "https://cdn.district.in/movies-assets/poster.jpg"
        val html = """
            <article>
              <img class="ipsImage" width="600" height="900" src="https://untrusted.example/art.jpg">
              <img class="ipsImage" width="1280" height="720" src="https://m.media-amazon.com/frame.jpg">
              <img class="ipsImage" width="600" height="800" src="$url">
            </article>
            <article><img class="ipsImage" width="600" height="800" src="https://m.media-amazon.com/comment.jpg"></article>
        """.trimIndent()
        assertEquals(url, TamilMVCatalogRepository.topicPoster(html, "https://www.1tamilmv.style/index.php?/forums/topic/1-film/"))
    }

    @Test
    fun `hybrid magnets keep a valid btih even when btmh is listed last`() {
        val first = "a".repeat(40)
        val second = "b".repeat(40)
        val v2 = "1220" + "c".repeat(64)
        val html = """
            <a href="magnet:?xt=urn:btih:$first&amp;xt=urn:btmh:$v2&amp;dn=Film.1080p">A</a>
            <a href="magnet:?xt=urn:btmh:$v2&amp;xt=urn%3Abtih%3A$second&amp;dn=Film.2160p">B</a>
            <a href="magnet:?xt=urn:btmh:$v2&amp;dn=Film.720p">C</a>
        """.trimIndent()
        val item = CatalogItem("saved:tamilmv:topic:1", "movie", "Film")
        assertEquals(listOf(second, first), TamilMVCatalogRepository.parseMagnets(html, item).map { it.infohash })
    }

    @Test
    fun `selecting a title ranks both release topics without fetching another year`() = runTest {
        val host = "https://www.1tamilmv.style"
        val fake = com.fourseveneightnine.tv.client.data.FakeHttp { request ->
            val url = request.url.toString()
            com.fourseveneightnine.tv.client.data.FakeAnswer(body = when {
                request.url.host.contains("cinemeta") -> """{"metas":[]}"""
                url.contains("/forums/topic/91-") -> """magnet:?xt=urn:btih:${"a".repeat(40)}&amp;dn=Film.1080p"""
                url.contains("/forums/topic/92-") -> """magnet:?xt=urn:btih:${"b".repeat(40)}&amp;dn=Film.2160p"""
                else -> (91..93).joinToString("") { topic ->
                    """<p><a href="$host/index.php?/forums/topic/$topic-film/">Film (${if (topic == 93) 2020 else 2026}) Tamil</a></p>"""
                }
            })
        }
        val catalog = TamilMVCatalogRepository(filesDir, fake.client, listOf(host))
        catalog.refresh()
        val selected = catalog.sources(catalog.state.value.items.first())
        assertEquals(listOf("b".repeat(40), "a".repeat(40)), selected.playbackSources.map { it.infohash })
        assertTrue(fake.urls.none { it.contains("/forums/topic/93-") })
        assertEquals(3, catalog.state.value.items.size)
    }

    @Test
    fun `receiver refresh and selected magnets work without phone token and survive restart and outage`() = runTest {
        var offline = false
        val hash = "a".repeat(40)
        val fake = com.fourseveneightnine.tv.client.data.FakeHttp { request ->
            com.fourseveneightnine.tv.client.data.FakeAnswer(code = if (offline) 503 else 200, body = when {
                request.url.host.contains("cinemeta") -> """{"metas":[]}"""
                request.url.toString().contains("/forums/topic/") -> """<a href="magnet:?xt=urn:btih:$hash&amp;dn=Example.2026.1080p.mkv">Magnet</a>"""
                else -> """<p><a href="https://www.1tamilmv.style/index.php?/forums/topic/91-example/">Example (2026) Tamil</a></p>"""
            })
        }
        val catalog = TamilMVCatalogRepository(filesDir, fake.client, listOf("https://www.1tamilmv.style"))
        catalog.refresh()
        assertEquals("Example", catalog.state.value.items.single().title)
        val selected = catalog.sources(catalog.state.value.items.single())
        assertEquals(hash, selected.playbackSources.single().infohash)
        assertTrue(fake.requests.all { it.method == "GET" && it.header("Authorization") == null })
        val restarted = TamilMVCatalogRepository(filesDir, fake.client, listOf("https://www.1tamilmv.style"))
        restarted.hydrate()
        assertEquals(selected, restarted.state.value.items.single())
        offline = true
        restarted.refresh(force = true)
        assertEquals(selected, restarted.state.value.items.single())
        assertNotNull(restarted.state.value.message)
        assertTrue(restarted.state.value.message.orEmpty().contains("503"))
        assertFalse(restarted.state.value.loading)
    }

    @Test
    fun `TamilMV retains a working source when a refreshed topic temporarily has no links`() = runTest {
        val host = "https://www.1tamilmv.style"
        val hash = "a".repeat(40)
        var now = 1L
        var topicReads = 0
        val fake = com.fourseveneightnine.tv.client.data.FakeHttp { request ->
            com.fourseveneightnine.tv.client.data.FakeAnswer(body = when {
                request.url.host.contains("cinemeta") -> """{"metas":[]}"""
                request.url.toString().contains("/forums/topic/") -> when (topicReads++) {
                    1 -> "<html>Temporarily empty</html>"
                    else -> "magnet:?xt=urn:btih:$hash&dn=Film.2026.1080p.mkv"
                }
                else -> """<a href="$host/index.php?/forums/topic/91-film/">Film (2026) Tamil</a>"""
            })
        }
        val repo = TamilMVCatalogRepository(filesDir, fake.client, listOf(host), clock = { now })
        repo.refresh()
        val initial = repo.sources(repo.state.value.items.single())
        now += 61_000L
        assertEquals(initial.playbackSources, repo.sources(initial).playbackSources)
        assertEquals(initial.playbackSources, repo.state.value.items.single().playbackSources)
        assertEquals(initial.playbackSources, repo.sources(initial).playbackSources)
        assertEquals(3, topicReads)
    }

    @Test
    fun `TamilMV refresh publishes while an older topic read is waiting`() = runTest {
        val host = "https://www.1tamilmv.style"
        val topicStarted = CompletableDeferred<Unit>()
        val releaseTopic = java.util.concurrent.CountDownLatch(1)
        val currentTopic = java.util.concurrent.atomic.AtomicInteger(91)
        val hash = "a".repeat(40)
        val fake = com.fourseveneightnine.tv.client.data.FakeHttp { request ->
            com.fourseveneightnine.tv.client.data.FakeAnswer(body = when {
                request.url.host.contains("cinemeta") -> """{"metas":[]}"""
                request.url.toString().contains("/forums/topic/") -> {
                    topicStarted.complete(Unit)
                    check(releaseTopic.await(5, TimeUnit.SECONDS))
                    "magnet:?xt=urn:btih:$hash&dn=Film.2026.1080p.mkv"
                }
                else -> """<a href="$host/index.php?/forums/topic/${currentTopic.get()}-film/">Film (2026) Tamil</a>"""
            })
        }
        val repo = TamilMVCatalogRepository(filesDir, fake.client, listOf(host))
        repo.refresh()
        val pressed = repo.state.value.items.single()
        val selected = async(Dispatchers.IO) { repo.sources(pressed) }
        try {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                withTimeout(3_000L) {
                    topicStarted.await()
                    currentTopic.set(92)
                    repo.refresh(force = true)
                }
            }
            assertEquals("saved:tamilmv:topic:92", repo.state.value.items.single().canonicalId)
        } finally {
            releaseTopic.countDown()
        }
        assertEquals(hash, selected.await().playbackSources.single().infohash)
        assertTrue(repo.state.value.items.single().playbackSources.isEmpty())
    }

    @Test
    fun `TamilMV matches duplicate releases once and reopens saved metadata without a request`() = runTest {
        val host = "https://www.1tamilmv.style"
        val search = """{"metas":[
            {"type":"movie","id":"tt1111111","name":"Film","releaseInfo":"1986"},
            {"type":"movie","id":"tt2222222","name":"Film","releaseInfo":"2022","poster":"https://art.example/film.jpg"}
        ]}"""
        val fake = com.fourseveneightnine.tv.client.data.FakeHttp { request ->
            com.fourseveneightnine.tv.client.data.FakeAnswer(body = if (request.url.host.contains("cinemeta")) search
            else (1..2).joinToString("") { """<a href="$host/index.php?/forums/topic/$it-film/">Film (2022) Tamil</a>""" })
        }
        val repo = TamilMVCatalogRepository(filesDir, fake.client, listOf(host))
        repo.refresh()
        val items = repo.state.value.items
        assertEquals(2, items.map { it.canonicalId }.distinct().size)
        assertTrue(items.all { it.imdbId == "tt2222222" && it.posterUrl == "https://art.example/film.jpg" })
        assertEquals(1, fake.urlsContaining("/catalog/movie/").size)
        val restarted = TamilMVCatalogRepository(filesDir, fake.client, listOf(host))
        restarted.hydrate()
        val calls = fake.requests.size
        assertEquals(items.first(), restarted.matchForDetails(items.first().copy(imdbId = null)))
        restarted.refresh()
        assertEquals(calls, fake.requests.size)
    }

    @Test
    fun `TamilMV movie bundles match stripped names and the listed first year`() = runTest {
        val host = "https://www.1tamilmv.style"
        val queries = java.util.concurrent.CopyOnWriteArrayList<String>()
        val fake = com.fourseveneightnine.tv.client.data.FakeHttp { request ->
            val name = request.url.queryParameter("query").orEmpty()
            if (name.isNotEmpty()) queries += "$name:${request.url.queryParameter("year")}"
            com.fourseveneightnine.tv.client.data.FakeAnswer(body = when (name) {
                "Mechanic" -> """{"results":[
                    {"id":1,"title":"Mechanic","release_date":"2016-08-25","poster_path":"/wrong.jpg"},
                    {"id":27582,"title":"The Mechanic","release_date":"2011-01-13","poster_path":"/mechanic.jpg"}
                ]}"""
                "The Lord of the Rings" -> """{"results":[
                    {"id":123,"title":"The Lord of the Rings","release_date":"1978-11-15","poster_path":"/animated.jpg"},
                    {"id":120,"title":"The Lord of the Rings: The Fellowship of the Ring","release_date":"2001-12-18","poster_path":"/fellowship.jpg"}
                ]}"""
                else -> """<a href="$host/index.php?/forums/topic/91-mechanic/">Mechanic Duology (2011-2016) BluRay</a>
                    <a href="$host/index.php?/forums/topic/92-lotr/">The Lord of the Rings (Trilogy) (2001-2003) BluRay</a>"""
            })
        }
        val repo = TamilMVCatalogRepository(filesDir, fake.client, listOf(host), tmdbKey = { "fixture" })
        repo.refresh()
        val items = repo.state.value.items
        assertEquals(listOf("Mechanic Duology", "The Lord of the Rings (Trilogy)"), items.map { it.title })
        assertEquals(listOf(2011, 2001), items.map { it.year })
        assertEquals(listOf("movie", "movie"), items.map { it.mediaType })
        assertEquals(listOf(27582, 120), items.map { it.tmdbId })
        assertEquals(listOf("mechanic.jpg", "fellowship.jpg"), items.map { it.posterUrl?.substringAfterLast('/') })
        assertEquals(setOf("Mechanic:2011", "The Lord of the Rings:2001"), queries.toSet())
    }

    @Test
    fun `TamilMV resolves TMDB ties only with cross-provider agreement and keeps title markers`() = runTest {
        val checked = com.fourseveneightnine.tv.client.data.FakeHttp { request ->
            val name = request.url.queryParameter("query")
            val body = when {
                name == "Haiku" -> """{"results":[
                    {"id":1593350,"title":"Haiku","release_date":"2026-10-23","poster_path":"/tamil.jpg"},
                    {"id":1803391,"title":"Haiku","release_date":"2026-07-16","poster_path":"/other.jpg"}]}"""
                name == "#Cult" -> """{"results":[
                    {"id":1622354,"title":"Cult","release_date":"2026-01-25","poster_path":"/kannada.jpg"},
                    {"id":1637705,"title":"#Cult","release_date":"2026-10-30","poster_path":"/telugu.jpg"}]}"""
                name == "Obsession" -> """{"results":[
                    {"id":31,"title":"Obsession","release_date":"2026-01-25","poster_path":"/short.jpg"},
                    {"id":32,"title":"Obsession","release_date":"2026-10-30","poster_path":"/feature.jpg"}]}"""
                request.url.pathSegments.last() == "tt39239818" -> """{"movie_results":[{"id":1593350}]}"""
                request.url.pathSegments.last() == "tt39060004" -> """{"movie_results":[{"id":33}]}"""
                request.url.host.contains("cinemeta") && request.url.pathSegments.last().contains("Haiku") ->
                    """{"metas":[{"type":"movie","id":"tt39239818","name":"Haiku","releaseInfo":"2026"}]}"""
                request.url.host.contains("cinemeta") && request.url.pathSegments.last().contains("Obsession") ->
                    """{"metas":[{"type":"movie","id":"tt39060004","name":"Obsession","releaseInfo":"2026"}]}"""
                else -> """{"results":[]}"""
            }
            com.fourseveneightnine.tv.client.data.FakeAnswer(body = body)
        }
        val repo = TamilMVCatalogRepository(filesDir, checked.client, tmdbKey = { "fixture" })
        suspend fun match(name: String) = repo.matchForDetails(CatalogItem("saved:tamilmv:$name", "movie", name, year = 2026))
        assertEquals(1593350, match("Haiku").tmdbId)
        assertEquals(1637705, match("#Cult").tmdbId)
        assertNull(match("Obsession").posterUrl)
        assertTrue(checked.urls.none { it.contains("cinemeta") && it.contains("Cult") })
    }

    @Test
    fun `TamilMV finds editions without a search year and verifies older digital release dates`() = runTest {
        val checked = com.fourseveneightnine.tv.client.data.FakeHttp { request ->
            val name = request.url.queryParameter("query")
            val yearFilter = request.url.queryParameter("year")
            val body = when {
                name == "Avengers Endgame : Encore" && yearFilter != null ->
                    """{"results":[{"id":299534,"title":"Avengers: Endgame","release_date":"2019-04-24","poster_path":"/2019.jpg"}]}"""
                name == "Avengers Endgame : Encore" ->
                    """{"results":[{"id":1830834,"title":"Avengers Endgame: Encore","release_date":"2026-09-25","poster_path":"/encore.jpg"}]}"""
                name == "Saayavanam" && yearFilter == null ->
                    """{"results":[{"id":1310458,"title":"Saayavanam","release_date":"2025-12-19","poster_path":"/saaya.jpg"}]}"""
                request.url.encodedPath.contains("1310458/release_dates") ->
                    """{"results":[{"iso_3166_1":"IN","release_dates":[{"type":4,"release_date":"2026-11-12T00:00:00Z"}]}]}"""
                name == "Other Film" && yearFilter == null ->
                    """{"results":[{"id":1234,"title":"Other Film","release_date":"2025-12-19","poster_path":"/wrong.jpg"}]}"""
                request.url.encodedPath.contains("1234/release_dates") ->
                    """{"results":[{"iso_3166_1":"IN","release_dates":[{"type":3,"release_date":"2026-11-12T00:00:00Z"}]}]}"""
                else -> """{"results":[],"metas":[]}"""
            }
            com.fourseveneightnine.tv.client.data.FakeAnswer(body = body)
        }
        val repo = TamilMVCatalogRepository(filesDir, checked.client, tmdbKey = { "fixture" })
        suspend fun match(name: String) = repo.matchForDetails(CatalogItem("saved:tamilmv:$name", "movie", name, year = 2026))
        assertEquals(1830834, match("Avengers Endgame : Encore").tmdbId)
        val saaya = match("Saayavanam")
        assertEquals(1310458, saaya.tmdbId)
        assertEquals(2025, saaya.metadataYear)
        assertEquals(2026, saaya.year)
        assertNull(match("Other Film").tmdbId)
    }

    @Test
    fun `TamilMV verifies corrected topic seasons and language aliases against the provider season`() = runTest {
        val host = "https://www.1tamilmv.style"
        val fake = com.fourseveneightnine.tv.client.data.FakeHttp { request ->
            val path = request.url.encodedPath
            val name = request.url.queryParameter("query")
            val body = when {
                request.url.host == "www.1tamilmv.style" -> "<title>Heart Beat (2026) S03 EP (01-68) BluRay</title>"
                name == "Heart Beat" -> """{"results":[
                    {"id":248064,"name":"Heart Beat","first_air_date":"2024-03-08","poster_path":"/series.jpg"},
                    {"id":248337,"name":"Heart Beat","first_air_date":"2024-03-08"}]}"""
                name == "Bigg Boss Tamil" -> """{"results":[
                    {"id":72907,"name":"Bigg Boss","original_language":"hi","first_air_date":"2007-03-08","poster_path":"/other.jpg"},
                    {"id":72908,"name":"Bigg Boss","original_language":"ta","first_air_date":"2017-06-25","poster_path":"/tamil.jpg"}]}"""
                path.endsWith("248064/season/3") -> """{"season_number":3,"air_date":"2026-07-30","poster_path":"/heart3.jpg"}"""
                path.endsWith("72908/season/10") -> """{"season_number":10,"air_date":"2026-09-06","poster_path":"/boss10.jpg"}"""
                else -> """{"results":[],"metas":[]}"""
            }
            com.fourseveneightnine.tv.client.data.FakeAnswer(body = body)
        }
        val repo = TamilMVCatalogRepository(filesDir, fake.client, tmdbKey = { "fixture" })
        val heart = repo.matchForDetails(CatalogItem("saved:tamilmv:topic:1", "series", "Heart Beat", 2026,
            season = 1, sourcePageURL = "$host/index.php?/forums/topic/1-heart/"))
        assertEquals(248064, heart.tmdbId)
        assertEquals(3, heart.season)
        assertTrue(heart.posterUrl.orEmpty().endsWith("/heart3.jpg"))
        val boss = repo.matchForDetails(CatalogItem("saved:tamilmv:topic:2", "series", "Bigg Boss Tamil", 2026, season = 10))
        assertEquals(72908, boss.tmdbId)
        assertTrue(boss.posterUrl.orEmpty().endsWith("/boss10.jpg"))
        assertTrue(fake.urls.none { it.contains("72907/season") })
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `TamilMV checks real poster dimensions on approved forum image hosts`() = runTest {
        val host = "https://www.1tamilmv.style"
        val decoder = java.util.Base64.getDecoder()
        val landscape = decoder.decode("iVBORw0KGgoAAAANSUhEUgAAAlgAAAFeAQAAAABxrFzxAAAAMUlEQVR4nO3BgQAAAADDoPlTn+AGVQEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAzwBn6AABctW1ZAAAAABJRU5ErkJggg==")
        val portrait = decoder.decode("iVBORw0KGgoAAAANSUhEUgAAAWgAAAIcAQAAAADzxQkyAAAAL0lEQVR4nO3BMQEAAADCoPVPbQlPoAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAD4GYQgAAQ2l8A8AAAAASUVORK5CYII=")
        val requests = java.util.concurrent.CopyOnWriteArrayList<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            requests += req
            val response = Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            val jsonType = "application/json".toMediaType()
            val pngType = "image/png".toMediaType()
            when (req.url.host) {
                "v3-cinemeta.strem.io" -> response.body("""{"metas":[]}""".toResponseBody(jsonType))
                "www.1tamilmv.style" -> {
                    val body = if (req.url.toString().contains("/forums/topic/")) """
                        <article>
                        <img src="https://unapproved.example/portrait.png" width="400" height="600" class="ipsImage">
                        <img src="https://www.pixelbb.com/landscape.png">
                        <img src="https://i.postimg.cc/path/redirect.png">
                        <img src="https://pbs.twimg.com/media/promo.png">
                        </article>
                    """ else """<a href="$host/index.php?/forums/topic/91-film/">Film (2026) Tamil</a>"""
                    response.body(body.toResponseBody(jsonType))
                }
                "www.pixelbb.com" -> response.header("Content-Type", "image/png").body(landscape.toResponseBody(pngType))
                "i.postimg.cc" -> response.code(302).message("Redirect").header("Location", "https://unapproved.example/fake.png")
                    .body("".toResponseBody(jsonType))
                "pbs.twimg.com" -> response.header("Content-Type", "image/png").body(portrait.toResponseBody(pngType))
                else -> error("Unexpected artwork host")
            }
            response.build()
        }.build()
        val repo = TamilMVCatalogRepository(filesDir, client, listOf(host))
        repo.refresh()
        repo.enrichVisiblePosters(setOf(repo.state.value.items.single().canonicalId))
        assertEquals("https://pbs.twimg.com/media/promo.png", repo.state.value.items.single().posterUrl)
        assertTrue(requests.none { it.url.host == "unapproved.example" })
        assertEquals("bytes=0-65535", requests.first { it.url.host == "pbs.twimg.com" }.header("Range"))
    }

    @Test
    fun `Details matches a poster-only release by original TMDB title and preserves its source`() = runTest {
        val fake = com.fourseveneightnine.tv.client.data.FakeHttp {
            com.fourseveneightnine.tv.client.data.FakeAnswer(body = """{"results":[
                {"id":10,"title":"Another film","original_title":"விக்ரம்","release_date":"1986-05-29"},
                {"id":11,"title":"Vikram","original_title":"விக்ரம்","release_date":"2022-06-03",
                 "poster_path":"/poster.jpg","overview":"A detective investigates."}]}""")
        }
        val item = CatalogItem("saved:tamilmv:topic:1", "movie", "விக்ரம்", year = 2022,
            posterUrl = "https://forum.example/cover.jpg", playbackSources = listOf(CatalogPlaybackSource("a".repeat(40), "Vikram.mkv")))
        val repo = TamilMVCatalogRepository(filesDir, fake.client, tmdbKey = { "fixture" })
        val matched = repo.matchForDetails(item)
        assertEquals(11, matched.tmdbId)
        assertEquals("https://image.tmdb.org/t/p/w500/poster.jpg", matched.posterUrl)
        assertEquals("A detective investigates.", matched.overview)
        assertEquals(item.canonicalId, matched.canonicalId)
        assertEquals(item.playbackSources, matched.playbackSources)
        assertEquals("/3/search/movie", fake.requests.single().url.encodedPath)
        assertEquals("2022", fake.requests.single().url.queryParameter("year"))
    }

    @Test
    fun `ambiguous matches retain the source and later seasons can use an earlier premiere`() = runTest {
        var ambiguous = false
        val fake = com.fourseveneightnine.tv.client.data.FakeHttp {
            val other = if (ambiguous) """,{"id":"tt2222222","type":"series","name":"Suzhal - Other","releaseInfo":"2022"}""" else ""
            com.fourseveneightnine.tv.client.data.FakeAnswer(body =
                """{"metas":[{"id":"tt1111111","type":"series","name":"Suzhal - The Vortex","releaseInfo":"2022"}$other]}""")
        }
        val repo = TamilMVCatalogRepository(filesDir, fake.client)
        val release = CatalogItem("saved:tamilmv:topic:3", "series", "Suzhal", year = 2025, season = 2)
        assertEquals("tt1111111", repo.matchForDetails(release).imdbId)
        val firstSeason = release.copy(season = 1)
        assertEquals(firstSeason, repo.matchForDetails(firstSeason))
        ambiguous = true
        assertEquals(release, repo.matchForDetails(release))
    }

    @Test
    fun `server refresh and phone imports cannot replace receiver-owned current Tamil MV`() = runTest {
        generation("g1").publish()
        val store = store()
        val owned = CatalogItem("saved:tamilmv:topic:99", "movie", "Current receiver release", year = 2026)
        store.setReceiverTamilMV(listOf(owned), 999)
        store.refresh()
        assertEquals(listOf(owned), store.shelves().value.filter { it.kind == ShelfKind.TAMILMV_RECENT }.flatMap { it.items })
        assertTrue(store.shelves().value.none { it.kind == ShelfKind.TAMILMV_POPULAR })
        assertTrue(store.shelves().value.any { it.kind == ShelfKind.LETTERBOXD })
    }

    @Test
    fun `signed manifest parts retain every list and invalid parts keep the complete cache`() = runTest {
        val source = PrivateGeneration(http, "parts", "2026-11-10T00:00:00Z")
        repeat(270) { source.shelf("private/v1/owner/artifacts/list-$it.json", "letterboxd:liked:profile:curator/list-$it", listOf("Film $it")) }
        source.publish()
        val root = json.decodeFromString<CatalogManifestPayload>(http.bodies.getValue(CatalogSnapshotContract.privateManifestURL).decodeToString())
        val parts = root.artifacts.chunked(32).mapIndexed { index, artifacts ->
            val bytes = json.encodeToString(CatalogManifestPayload.serializer(), root.copy(artifacts = artifacts)).encodeToByteArray()
            val key = "private/v1/owner/artifacts/manifest-parts/$index.json"
            http.bodies[CatalogSnapshotContract.privateArtifactBaseURL + CatalogSnapshotContract.privateArtifactPath(key, "owner")] = bytes
            CatalogArtifact(key, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, bytes.size,
                "application/json", "private-manifest-part")
        }
        http.bodies[CatalogSnapshotContract.privateManifestURL] = json.encodeToString(CatalogManifestPayload.serializer(), root.copy(artifacts = parts)).encodeToByteArray()
        val receiver = store()
        receiver.refresh()
        assertEquals(270, receiver.shelves().value.count { it.kind == ShelfKind.LETTERBOXD })
        val bad = root.copy(generation = "bad-parts", artifacts = parts)
        http.bodies[CatalogSnapshotContract.privateManifestURL] = json.encodeToString(CatalogManifestPayload.serializer(), bad).encodeToByteArray()
        receiver.refresh(force = true)
        assertEquals(SnapshotState.FAILED, receiver.status().value.of(SnapshotSource.PRIVATE_CATALOG)?.state)
        assertEquals(270, receiver.shelves().value.count { it.kind == ShelfKind.LETTERBOXD })
    }

    @Test
    fun `a verified empty private roster clears the old server lists`() = runTest {
        PrivateGeneration(http, "before", "2026-09-20T10:00:00Z")
            .shelf("private/v1/owner/list.json", "letterboxd:liked:profile:curator/list", listOf("Old")).publish()
        val receiver = store()
        receiver.refresh()
        val empty = CatalogManifestPayload(1, "cleared", "2026-11-10T00:00:00Z", "private", "owner", emptyList())
        http.bodies[CatalogSnapshotContract.privateManifestURL] = json.encodeToString(CatalogManifestPayload.serializer(), empty).encodeToByteArray()
        receiver.refresh(force = true)
        assertTrue(receiver.shelves().value.none { it.kind == ShelfKind.LETTERBOXD })
        assertEquals(SnapshotState.READY, receiver.status().value.of(SnapshotSource.PRIVATE_CATALOG)?.state)
    }

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

    private fun TestScope.store(
        evictionBudgetBytes: Long = SnapshotStore.EVICTION_BUDGET_BYTES,
        token: String? = "a-token",
    ) = SnapshotStore(
            filesDir = filesDir,
            http = http,
            verifier = verifier,
            tokenProvider = { token },
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
        val emissions = mutableListOf<List<Shelf>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.shelves().toList(emissions)
        }

        PrivateGeneration(http, "g2", createdAt = "2026-09-21T10:00:00Z")
            .shelf("private/v1/owner/popular.json", "tamilmv:main:popular", listOf("New one"))
            .shelf(
                key = "private/v1/owner/broken.json",
                catalogId = "letterboxd:saran:broken",
                titles = listOf("Never arrives"),
                delayMillis = SnapshotStore.PARTIAL_PAINT_DEADLINE_MILLIS * 2,
                fail = CatalogHttpException(500),
            )
            .publish()
        store.refresh(force = true)

        assertTrue(emissions.any { shelves -> shelves.any { it.generation == "g2" && !it.complete } })
        assertEquals("g1", disk().readFull(SnapshotSource.PRIVATE_CATALOG)?.generation)
        assertEquals(beforeIds, store.shelves().value.map(Shelf::id))
        assertEquals("g1", store.shelves().value.first().generation)
        assertEquals(
            SnapshotState.FAILED,
            store.status().value.of(SnapshotSource.PRIVATE_CATALOG)?.state,
        )
        assertTrue(store.status().value.of(SnapshotSource.PRIVATE_CATALOG)?.complete == true)
    }

    @Test
    fun `cancelling after partial paint restores the complete visible generation`() = runTest {
        PublicGeneration.standard(http)
        generation("g1").publish()
        val store = store()
        store.refresh()
        val before = store.shelves().value
        val owned = CatalogItem("saved:tamilmv:receiver", "movie", "Receiver-owned release")
        store.setReceiverTamilMV(listOf(owned), 10)

        PrivateGeneration(http, "g2", createdAt = "2026-09-21T10:00:00Z")
            .shelf("private/v1/owner/popular.json", "tamilmv:main:popular", listOf("Fast replacement"))
            .shelf("private/v1/owner/slow.json", "letterboxd:saran:slow", listOf("Slow replacement"),
                delayMillis = SnapshotStore.PARTIAL_PAINT_DEADLINE_MILLIS * 3)
            .publish()
        val refresh = backgroundScope.launch { store.refresh(force = true) }
        runCurrent()
        advanceTimeBy(SnapshotStore.PARTIAL_PAINT_DEADLINE_MILLIS + 1)
        runCurrent()
        assertTrue(store.status().value.of(SnapshotSource.PRIVATE_CATALOG)?.complete == false)
        refresh.cancel()
        refresh.join()

        val status = store.status().value.of(SnapshotSource.PRIVATE_CATALOG)
        assertEquals("g1", status?.generation)
        assertEquals(SnapshotState.READY, status?.state)
        assertTrue(status?.complete == true)
        assertEquals(before.filter { it.kind == ShelfKind.LETTERBOXD },
            store.shelves().value.filter { it.kind == ShelfKind.LETTERBOXD })
        assertEquals(before.filter { it.kind == ShelfKind.TMDB_MOVIES || it.kind == ShelfKind.TMDB_SERIES },
            store.shelves().value.filter { it.kind == ShelfKind.TMDB_MOVIES || it.kind == ShelfKind.TMDB_SERIES })
        assertEquals(listOf(owned), store.shelves().value.filter { it.kind == ShelfKind.TAMILMV_RECENT }.flatMap { it.items })
    }

    @Test
    fun `cancelling during the complete commit finishes publishing the durable generation`() = runTest {
        generation("g1").publish()
        val store = store()
        store.refresh()
        PrivateGeneration(http, "g2", createdAt = "2026-09-21T10:00:00Z")
            .shelf("private/v1/owner/popular.json", "tamilmv:main:popular", listOf("Fast replacement"))
            .shelf("private/v1/owner/slow.json", "letterboxd:saran:slow", listOf("Slow replacement"),
                delayMillis = SnapshotStore.PARTIAL_PAINT_DEADLINE_MILLIS * 3)
            .publish()
        val refresh = backgroundScope.launch { store.refresh(force = true) }
        runCurrent()
        advanceTimeBy(SnapshotStore.PARTIAL_PAINT_DEADLINE_MILLIS + 1)
        runCurrent()
        assertTrue(store.status().value.of(SnapshotSource.PRIVATE_CATALOG)?.complete == false)

        val gate = SnapshotStore::class.java.getDeclaredField("publishGate").let { field ->
            field.isAccessible = true
            field.get(store) as Mutex
        }
        gate.lock()
        try {
            advanceTimeBy(SnapshotStore.PARTIAL_PAINT_DEADLINE_MILLIS * 3)
            runCurrent()
            assertEquals("g2", disk().readFull(SnapshotSource.PRIVATE_CATALOG)?.generation)
            assertTrue(store.status().value.of(SnapshotSource.PRIVATE_CATALOG)?.complete == false)
            refresh.cancel()
            runCurrent()
        } finally {
            gate.unlock()
        }
        refresh.join()

        val status = store.status().value.of(SnapshotSource.PRIVATE_CATALOG)
        assertEquals("g2", status?.generation)
        assertEquals(SnapshotState.READY, status?.state)
        assertTrue(status?.complete == true)
        assertEquals(disk().readFull(SnapshotSource.PRIVATE_CATALOG)?.shelves, store.shelves().value)
        http.requests.clear()
        store.refresh()
        assertEquals(listOf(CatalogSnapshotContract.privateManifestURL), http.privateRequests())
    }

    @Test
    fun `a failed partial refresh restores complete memory even when it was not persisted`() = runTest {
        File(filesDir, SnapshotDiskCache.ROOT_DIRECTORY).writeText("blocked")
        generation("memory-only").publish()
        val store = store()
        store.refresh()
        val before = store.shelves().value
        assertTrue(before.isNotEmpty() && before.all(Shelf::complete))
        assertNull(disk().readFull(SnapshotSource.PRIVATE_CATALOG))

        PrivateGeneration(http, "failed-newer", createdAt = "2026-09-21T10:00:00Z")
            .shelf("private/v1/owner/popular.json", "tamilmv:main:popular", listOf("Fast replacement"))
            .shelf("private/v1/owner/broken.json", "letterboxd:saran:broken", listOf("Never arrives"),
                delayMillis = SnapshotStore.PARTIAL_PAINT_DEADLINE_MILLIS * 2,
                fail = CatalogHttpException(500))
            .publish()
        store.refresh(force = true)

        assertEquals(before, store.shelves().value)
        assertEquals("memory-only", store.status().value.of(SnapshotSource.PRIVATE_CATALOG)?.generation)
        assertEquals(SnapshotState.FAILED, store.status().value.of(SnapshotSource.PRIVATE_CATALOG)?.state)
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
    fun `a fresh Letterboxd phone transfer updates an unchanged Tamil MV generation without a token`() = runTest {
        val receiver = store(token = null)
        val time = 1_790_000_000_000L
        val generation = "iphone-$time"
        val tamil = Shelf("tamilmv:popular", "Tamil MV", ShelfKind.TAMILMV_POPULAR,
            listOf(CatalogItem("saved:tamilmv:old", "movie", "Saved film")), generation, time)
        val initial = Shelf("letterboxd:owned:latest", "Latest Digital Releases", ShelfKind.LETTERBOXD,
            listOf(CatalogItem("tt10000", "movie", "Previous film")), generation, time)
        val refreshed = initial.copy(items = listOf(CatalogItem("tt3500528", "movie", "The Teacher's Diary")))
        val oldTransfer = listOf(tamil, initial)
        assertTrue(receiver.importPrivateSnapshot(generation, time, oldTransfer))
        assertFalse(receiver.importPrivateSnapshot(generation, time, oldTransfer))

        val newerTransfer = listOf(tamil.copy(items = emptyList()), refreshed)
        assertTrue(receiver.importPrivateSnapshot(generation, time, newerTransfer))
        assertEquals(listOf(tamil, refreshed), receiver.shelves().value)
        assertEquals(listOf(tamil, refreshed), disk().readFull(SnapshotSource.PRIVATE_CATALOG)?.shelves)
        assertFalse(receiver.importPrivateSnapshot(generation, time, newerTransfer))

        assertFalse(receiver.importPrivateSnapshot(generation, time, listOf(tamil, refreshed.copy(items = emptyList()))))
        assertEquals(refreshed, receiver.shelves().value.single { it.kind == ShelfKind.LETTERBOXD })
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

    @Test
    fun `normal refresh retries persistence of an unchanged complete memory generation`() = runTest {
        val obstruction = File(filesDir, SnapshotDiskCache.ROOT_DIRECTORY).apply { writeText("blocked") }
        generation("g1").publish(etag = "v1")
        val store = store()
        store.refresh()
        assertEquals("g1", store.status().value.of(SnapshotSource.PRIVATE_CATALOG)?.generation)
        assertTrue(store.shelves().value.all(Shelf::complete))
        assertNull(disk().readFull(SnapshotSource.PRIVATE_CATALOG))

        assertTrue(obstruction.delete())
        http.requests.clear()
        store.refresh()
        assertTrue(http.privateRequests().size > 1)
        assertEquals("g1", disk().readFull(SnapshotSource.PRIVATE_CATALOG)?.generation)

        http.requests.clear()
        store.refresh()
        assertEquals(listOf(CatalogSnapshotContract.privateManifestURL), http.privateRequests())
        val restarted = store(token = null)
        restarted.hydrate()
        assertEquals(store.shelves().value, restarted.shelves().value)
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
    fun `a missing token skips the optional private server without making a request`() = runTest {
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
        assertEquals(SnapshotState.NOT_CONFIGURED, status?.state)
        assertTrue(status?.message.orEmpty().contains("token"))
        assertTrue(store.shelves().value.isEmpty())
        assertTrue(http.privateRequests().isEmpty())
        assertNull(disk().currentDirectoryName(SnapshotSource.PRIVATE_CATALOG))
    }

    @Test
    fun `missing and blank tokens keep copied lists and a later token enables server refresh`() = runTest {
        PrivateGeneration(http, "server-new", "2026-11-16T10:00:00Z")
            .shelf("private/v1/owner/list.json", "letterboxd:saran:watchlist", listOf("Server film"))
            .publish()
        var token: String? = null
        val receiver = SnapshotStore(
            filesDir = filesDir, http = http, verifier = verifier,
            tokenProvider = { token },
            ioDispatcher = StandardTestDispatcher(testScheduler), now = { currentTime },
        )
        val copied = phoneShelves("iphone-old", "2026-11-15T10:00:00Z", "Copied film")
        receiver.importPrivateSnapshot("iphone-old", copied.first().generatedAtMillis, copied)
        val before = receiver.shelves().value
        val beforeStatus = receiver.status().value.of(SnapshotSource.PRIVATE_CATALOG)
        for (missing in listOf(null, "  ")) {
            token = missing
            receiver.refresh(force = true)
            val status = receiver.status().value.of(SnapshotSource.PRIVATE_CATALOG)
            assertEquals(SnapshotState.NOT_CONFIGURED, status?.state)
            assertEquals(before, receiver.shelves().value)
            assertEquals(beforeStatus?.itemCount, status?.itemCount)
            assertEquals("iphone-old", status?.generation)
            assertEquals(true, status?.complete)
            assertEquals("iphone-old", disk().readFull(SnapshotSource.PRIVATE_CATALOG)?.generation)
            assertTrue(http.privateRequests().isEmpty())
        }

        token = "a-token"
        receiver.refresh()
        val status = receiver.status().value.of(SnapshotSource.PRIVATE_CATALOG)
        assertEquals(SnapshotState.READY, status?.state)
        assertEquals("server-new", status?.generation)
        assertNull(status?.message)
        assertTrue(http.privateRequests().isNotEmpty())
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
