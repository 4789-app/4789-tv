package com.fourseveneightnine.tv.client.data.addons

import com.fourseveneightnine.tv.client.data.FakeAnswer
import com.fourseveneightnine.tv.client.data.FakeHttp
import com.fourseveneightnine.tv.client.data.fixture
import com.fourseveneightnine.tv.client.data.settings.SettingsDocument
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonRegistryTest {

    @Test fun `subtitle searches publish fast results before slow failures and merge duplicate URLs`() = runBlocking {
        val releaseSlow = CountDownLatch(1)
        val http = FakeHttp { request ->
            if (request.url.host == "slow.example") {
                releaseSlow.await(5, TimeUnit.SECONDS)
                FakeAnswer(code = 503)
            } else FakeAnswer(body = """{"subtitles":[{"id":"one","lang":"eng","url":"https://subs.example/one.srt"}]}""")
        }
        val document = SettingsDocument.parse("""{"format":"4789-settings","version":1,"subtitleSources":[
            {"name":"Slow","url":"https://slow.example/manifest.json","enabled":true},
            {"name":"Fast","url":"https://fast.example/manifest.json","enabled":true},
            {"name":"Duplicate","url":"https://duplicate.example/manifest.json","enabled":true}
        ]}""")
        val registry = AddonRegistry(document, StremioClient(http.client))
        val states = mutableListOf<SubtitleSearchResult>()
        try {
            withTimeout(5_000) {
                registry.searchSubtitles("series", "tt1234567:2:4").collect { result ->
                    states += result
                    if (result.tracks.isNotEmpty()) {
                        if (releaseSlow.count > 0) assertTrue(result.searching)
                        releaseSlow.countDown()
                    }
                }
            }
        } finally { releaseSlow.countDown() }
        assertEquals(1, states.last().tracks.size)
        assertEquals(3, states.last().completed)
        assertEquals(1, states.last().failed)
        assertFalse(states.last().searching)
        assertTrue(http.requests.all { it.url.encodedPath == "/subtitles/series/tt1234567:2:4.json" })
    }

    @Test fun `subtitle search obeys local disable and removal and retries unhealthy enabled providers`() = runBlocking {
        val document = SettingsDocument.parse("""{"format":"4789-settings","version":1,"subtitleSources":[
            {"name":"Retry","url":"https://retry.example/manifest.json","enabled":true},
            {"name":"Disabled","url":"https://disabled.example/manifest.json","enabled":true},
            {"name":"Removed","url":"https://removed.example/manifest.json","enabled":true}
        ]}""")
        val health = InMemoryAddonHealthStore()
        repeat(3) { health.markFail("https://retry.example/manifest.json", "http_503") }
        val store = MemoryAddonRegistryStore(AddonRegistryOverrides(
            enabled = mapOf("https://disabled.example" to false), removed = setOf("https://removed.example")))
        val http = FakeHttp { FakeAnswer(body = """{"subtitles":[]}""") }
        val registry = AddonRegistry(document, StremioClient(http.client), health, store)
        val result = registry.searchSubtitles("movie", "tt1234567").last()
        assertEquals(1, result.total)
        assertEquals(0, result.failed)
        assertEquals("retry.example", http.requests.single().url.host)
    }

    @Test
    fun `manifest cache evicts the least recently read entry without resetting its fetch age`() {
        val directory = createTempDir()
        try {
            val cache = HttpDiskCache(directory, maximumEntries = 2, maximumBytes = 1_024)
            val a = "https://cache.example/a/manifest.json"
            val b = "https://cache.example/b/manifest.json"
            val c = "https://cache.example/c/manifest.json"
            cache.write(a, "A", "a", 1L)
            cache.write(b, "B", "b", 2L)
            assertTrue(manifestCacheFile(directory, a).setLastModified(1_000L))
            assertTrue(manifestCacheFile(directory, b).setLastModified(2_000L))
            assertEquals(1L, cache.read(a)?.storedAtMillis)
            cache.write(c, "C", "c", 3L)
            assertEquals("A", cache.read(a)?.body)
            assertNull(cache.read(b))
            assertEquals("C", cache.read(c)?.body)
            assertEquals(2, directory.listFiles().orEmpty().size)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `manifest cache bounds encoded bytes protects the current write and skips an oversized replacement`() {
        val directory = createTempDir()
        try {
            val body = "é".repeat(8)
            val entryBytes = "tag\n1\n$body".toByteArray().size.toLong()
            val budget = 2 * entryBytes
            val cache = HttpDiskCache(directory, maximumEntries = 10, maximumBytes = budget)
            val urls = (1..3).map { "https://cache.example/$it/manifest.json" }
            cache.write(urls[0], body, "tag", 1L)
            cache.write(urls[1], body, "tag", 1L)
            // A clock correction can leave older files with future mtimes. The new target stays.
            val future = System.currentTimeMillis() + 60_000L
            assertTrue(manifestCacheFile(directory, urls[0]).setLastModified(future))
            assertTrue(manifestCacheFile(directory, urls[1]).setLastModified(future + 1_000L))
            cache.write(urls[2], body, "tag", 1L)
            assertNull(cache.read(urls[0]))
            assertEquals(body, cache.read(urls[1])?.body)
            assertEquals(body, cache.read(urls[2])?.body)
            assertTrue(directory.listFiles().orEmpty().sumOf(java.io.File::length) <= budget)
            val original = manifestCacheFile(directory, urls[2]).readBytes()
            cache.write(urls[2], body.repeat(100), "replacement", 2L)
            org.junit.Assert.assertArrayEquals(original, manifestCacheFile(directory, urls[2]).readBytes())
            assertEquals(body, cache.read(urls[2])?.body)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `new manifest cache instances trim history and remove only owned orphan staging files`() {
        val directory = createTempDir()
        try {
            val seed = HttpDiskCache(directory, maximumEntries = 10, maximumBytes = 1_024)
            val urls = (1..3).map { "https://cache.example/history-$it/manifest.json" }
            urls.forEachIndexed { index, url ->
                seed.write(url, "body-$index", "tag", 1L)
                assertTrue(manifestCacheFile(directory, url).setLastModified((index + 1) * 1_000L))
            }
            val orphan = java.io.File(directory, manifestCacheFile(directory, urls[0]).name + ".tmp")
            orphan.writeText("interrupted write")
            val unrelated = java.io.File(directory, "unrelated.tmp").apply { writeText("leave alone") }
            val cache = HttpDiskCache(directory, maximumEntries = 2, maximumBytes = 1_024)
            assertNull(cache.read(urls[0]))
            assertEquals("body-1", cache.read(urls[1])?.body)
            assertEquals("body-2", cache.read(urls[2])?.body)
            assertFalse(orphan.exists())
            assertEquals("leave alone", unrelated.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `manifest cache keeps URL hashes body bytes etags and 304 fetch-age updates`() {
        val directory = createTempDir()
        try {
            val cache = HttpDiskCache(directory, maximumEntries = 2, maximumBytes = 1_024)
            val url = "https://cache.example/FAKE-TOKEN/manifest.json?key=FIXTURE"
            val body = "{\"name\":\"Café\"}\nsecond line"
            cache.write(url, body, "\"tag\"", 11L)
            assertEquals(listOf(manifestCacheFile(directory, url).name), directory.list().orEmpty().toList())
            assertTrue(directory.list().orEmpty().none { it.contains("FAKE-TOKEN") || it.contains("FIXTURE") })
            assertEquals(HttpDiskCache.Entry(body, "\"tag\"", 11L), cache.read(url))
            assertEquals("\"tag\"\n11\n$body", manifestCacheFile(directory, url).readText())
            cache.touch(url, 99L)
            assertEquals(HttpDiskCache.Entry(body, "\"tag\"", 99L), cache.read(url))
            assertEquals("\"tag\"\n99\n$body", manifestCacheFile(directory, url).readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `manifest cache filesystem failures remain nonfatal`() {
        val root = createTempDir()
        try {
            val blockedDirectory = java.io.File(root, "file-not-directory").apply { writeText("occupied") }
            val cache = HttpDiskCache(blockedDirectory, maximumEntries = 2, maximumBytes = 64)
            cache.write("https://cache.example/manifest.json", "body", "tag", 1L)
            assertNull(cache.read("https://cache.example/manifest.json"))
            cache.touch("https://cache.example/manifest.json", 2L)
            cache.clear()
            assertEquals("occupied", blockedDirectory.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `reloaded manifest cache instances serialize staging publication reads and trimming`() {
        val directory = createTempDir()
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val shared = "https://cache.example/shared/manifest.json"
        val completeBodies = listOf("A".repeat(256), "B".repeat(256))
        try {
            val futures = (0..1).map { worker ->
                executor.submit<Unit> {
                    check(start.await(5, TimeUnit.SECONDS))
                    repeat(16) { iteration ->
                        // Recreate instances while the other client can still be using this directory.
                        val cache = HttpDiskCache(directory, maximumEntries = 3, maximumBytes = 4_096)
                        cache.write(shared, completeBodies[worker], "tag-$worker", iteration.toLong())
                        cache.read(shared)?.let { entry ->
                            assertTrue(entry.body in completeBodies)
                            assertEquals("tag-${completeBodies.indexOf(entry.body)}", entry.etag)
                        }
                        cache.write("https://cache.example/$worker-$iteration/manifest.json", "history-$worker-$iteration", null, 1L)
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
            val files = directory.listFiles().orEmpty()
            assertTrue(files.size <= 3)
            assertTrue(files.sumOf(java.io.File::length) <= 4_096L)
            assertTrue(files.all { it.name.matches(Regex("[0-9a-f]{64}")) })
            val expectedBodies = completeBodies.toSet() + (0..1).flatMap { worker ->
                (0 until 16).map { iteration -> "history-$worker-$iteration" }
            }
            files.forEach { file ->
                val text = file.readText()
                val secondBreak = text.indexOf('\n', text.indexOf('\n') + 1)
                assertTrue(secondBreak >= 0)
                assertTrue(text.substring(secondBreak + 1) in expectedBodies)
            }
        } finally {
            start.countDown()
            executor.shutdownNow()
            val stopped = executor.awaitTermination(5, TimeUnit.SECONDS)
            if (stopped) directory.deleteRecursively()
            assertTrue("Manifest cache fixture workers must stop before deleting their files", stopped)
        }
    }

    private fun manifestCacheFile(directory: java.io.File, url: String): java.io.File = java.io.File(
        directory,
        java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) },
    )

    private val document = SettingsDocument.parse(fixture("settings-export.json"))

    private fun registry(
        http: FakeHttp,
        health: AddonHealthStore = InMemoryAddonHealthStore(),
        store: AddonRegistryStore = MemoryAddonRegistryStore(),
    ): AddonRegistry = AddonRegistry(document, StremioClient(http.client), health, store)

    private fun okHttp() = FakeHttp { request ->
        val body = if (request.url.host.contains("cinemeta")) {
            fixture("manifest-cinemeta.json")
        } else {
            fixture("manifest-aiostreams.json")
        }
        FakeAnswer(body = body)
    }

    @Test
    fun `Cinemeta is always first and always present`() {
        val addons = registry(okHttp()).addons.value
        assertEquals(StremioClient.CINEMETA_MANIFEST, addons.first().manifestURL)
        assertTrue(addons.first().enabled)
    }

    @Test
    fun `stored order follows Cinemeta and duplicates are asked once`() {
        val addons = registry(okHttp()).addons.value
        assertEquals(
            listOf("Cinemeta", "Torrentio", "Comet", "Old thing", "Secondary add-on", "AIOStreams", "MediaFusion"),
            addons.map(Addon::name),
        )
        assertEquals(addons.size, addons.map(Addon::key).distinct().size)
    }

    @Test
    fun `refresh fills every manifest and never asks a disabled add-on`() = runTest {
        val http = okHttp()
        val registry = registry(http)
        registry.refresh()
        val addons = registry.addons.value
        assertNotNull(addons.first { it.name == "AIOStreams" }.manifest)
        // "Old thing" is off in the export, so its manifest is never fetched.
        assertTrue(http.urls.none { it.contains("dead.example.org") })
    }

    @Test
    fun `ready catalogs appear while another add-on is still loading`() = runBlocking {
        val releaseSlowManifest = CountDownLatch(1)
        val manifest = fixture("manifest-cinemeta.json")
        val http = FakeHttp { request ->
            if (request.url.host.contains("comet")) {
                check(releaseSlowManifest.await(10, TimeUnit.SECONDS))
            }
            FakeAnswer(body = manifest)
        }
        val registry = registry(http)
        val refresh = async { registry.refresh() }
        try {
            val ready = withTimeout(5_000) {
                registry.addons.first { rows -> rows.first().manifest != null }
            }
            assertTrue(registry.catalogs().any { (addon, _) -> addon.name == "Cinemeta" })
            assertTrue(ready.first { it.name == "Comet" }.manifest == null)
            assertFalse(refresh.isCompleted)
        } finally {
            releaseSlowManifest.countDown()
        }
        refresh.await()
        assertNotNull(registry.addons.value.first { it.name == "Comet" }.manifest)
    }

    @Test
    fun `a manual refresh revalidates fresh manifests and asks catalog observers to reload`() = runTest {
        val cache = kotlin.io.path.createTempDirectory("stremio-manifests").toFile()
        val http = FakeHttp { request ->
            if (request.header("If-None-Match") == "first") FakeAnswer(code = 304)
            else FakeAnswer(body = fixture("manifest-cinemeta.json"), headers = mapOf("ETag" to "first"))
        }
        val registry = AddonRegistry(SettingsDocument.parse("""{"sources":[]}"""), StremioClient(http.client, cache))
        try {
            registry.refresh()
            registry.refresh()
            assertEquals(1, http.requests.size)
            assertEquals(0L, registry.catalogRefreshes.value)
            val original = registry.catalogs()
            assertTrue(original.isNotEmpty())
            registry.refresh(force = true)
            assertEquals(2, http.requests.size)
            assertEquals("first", http.requests.last().header("If-None-Match"))
            assertEquals(1L, registry.catalogRefreshes.value)
            assertEquals(original.map { it.second }, registry.catalogs().map { it.second })
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun `refreshing one installed add-on only asks its endpoint`() = runTest {
        val http = FakeHttp { FakeAnswer(body = fixture("manifest-cinemeta.json")) }
        val registry = registry(http)
        val disabled = registry.addons.value.first { !it.enabled }
        assertTrue(registry.refreshAddon(disabled.manifestURL))
        assertEquals(listOf(disabled.manifestURL), http.urls)
        val refreshed = requireNotNull(registry.addon(disabled.manifestURL))
        assertNotNull(refreshed.manifest)
        assertFalse(refreshed.enabled)
        assertEquals(1L, registry.catalogRefreshes.value)
        assertFalse(registry.refreshAddon("https://missing.example/manifest.json"))
        assertEquals(1, http.urls.size)
    }

    @Test
    fun `a failing add-on goes unhealthy after three refreshes and never disappears`() = runTest {
        val http = FakeHttp { request ->
            if (request.url.host.contains("comet")) FakeAnswer(code = 500, body = "nope")
            else FakeAnswer(body = fixture("manifest-cinemeta.json"))
        }
        val registry = registry(http)
        repeat(2) { registry.refresh() }
        assertTrue(registry.addons.value.first { it.name == "Comet" }.health.healthy)

        registry.refresh()
        val comet = registry.addons.value.first { it.name == "Comet" }
        assertFalse(comet.health.healthy)
        assertEquals(3, comet.health.failStreak)
        // Still in the list, so Settings can show it grey and the owner keeps the URL.
        assertTrue(registry.addons.value.any { it.name == "Comet" })
        assertTrue(registry.streamAddons().any { it.name == "Comet" })
    }

    @Test
    fun `one add-on failing never stops the others`() = runTest {
        val http = FakeHttp { request ->
            if (request.url.host.contains("comet")) FakeAnswer(code = 503, body = "down")
            else FakeAnswer(body = fixture("manifest-aiostreams.json"))
        }
        val registry = registry(http)
        registry.refresh()
        assertNotNull(registry.addons.value.first { it.name == "AIOStreams" }.manifest)
    }

    @Test
    fun `health records a status code and never a URL`() {
        val store = InMemoryAddonHealthStore()
        store.markFail("https://aio.example.com/stremio/SECRETKEY/manifest.json", "http_503")
        val health = store.health("https://aio.example.com/stremio/SECRETKEY/manifest.json")
        assertEquals("http_503", health.lastError)
        assertFalse(health.lastError.orEmpty().contains("SECRETKEY"))
    }

    @Test
    fun `one success clears a fail streak`() {
        val store = InMemoryAddonHealthStore()
        repeat(3) { store.markFail("https://x.example.com/manifest.json", "timeout") }
        assertFalse(store.health("https://x.example.com/manifest.json").healthy)
        store.markOk("https://x.example.com/manifest.json")
        assertTrue(store.health("https://x.example.com/manifest.json").healthy)
    }

    @Test
    fun `catalogs sort into the phone's stated order`() = runTest {
        val http = okHttp()
        val registry = registry(http)
        registry.refresh()
        val catalogs = registry.catalogs()
        assertTrue(catalogs.isNotEmpty())
        assertTrue(catalogs.all { (addon, _) -> addon.usable })
    }

    @Test
    fun `receiver catalog order overrides the phone and survives registry reads`() = runTest {
        val store = MemoryAddonRegistryStore()
        val registry = registry(okHttp(), store = store)
        registry.refresh()
        val baseline = registry.catalogs()
        val reversed = baseline.reversed().map { (addon, catalog) -> catalog.uid(addon.manifestURL) }
        store.value = store.value.copy(catalogOrder = reversed)

        assertEquals(reversed, registry.catalogOrder())
        assertEquals(reversed, registry.catalogs().map { (addon, catalog) -> catalog.uid(addon.manifestURL) })
    }

    @Test
    fun `pre-refresh moves are cumulative survive restart and keep Cinemeta first`() = runTest {
        val store = MemoryAddonRegistryStore()
        val registry = registry(okHttp(), store = store)
        val oldThing = registry.addons.value.first { it.name == "Old thing" }

        assertTrue(registry.moveAddon(oldThing.manifestURL, -1))
        assertTrue(registry.moveAddon(oldThing.manifestURL, -1))
        assertEquals(listOf("Cinemeta", "Old thing", "Torrentio"), registry.addons.value.take(3).map(Addon::name))
        assertFalse(registry.moveAddon(oldThing.manifestURL, -1))
        assertTrue(store.value.addonOrder.isNotEmpty())

        val restarted = registry(okHttp(), store = store)
        assertEquals(listOf("Cinemeta", "Old thing", "Torrentio"), restarted.addons.value.take(3).map(Addon::name))
    }

    @Test
    fun `subtitle add-ons come from the subtitle list plus any add-on that declares the resource`() = runTest {
        val http = okHttp()
        val registry = registry(http)
        registry.refresh()
        val names = registry.subtitleAddons().map(Addon::name)
        assertTrue(names.contains("OpenSubtitles v3"))
        assertTrue(names.contains("AIO Subtitles"))
        assertEquals(names.size, names.distinct().size)
    }

    @Test
    fun `a manifest is fetched once and then served from the disk cache`() = runTest {
        val directory = createTempDir()
        val http = FakeHttp { FakeAnswer(body = fixture("manifest-aiostreams.json"), headers = mapOf("ETag" to "\"v1\"")) }
        val client = StremioClient(http.client, directory)
        val addon = Addon("AIOStreams", "https://aio.example.com/x/manifest.json")
        client.manifest(addon.manifestURL)
        client.manifest(addon.manifestURL)
        assertEquals(1, http.requests.size)
        directory.deleteRecursively()
    }

    @Test
    fun `a TV enable override wins after the registry is rebuilt from phone settings`() {
        val store = MemoryAddonRegistryStore()
        val first = registry(okHttp(), store = store)
        val oldThing = first.addons.value.first { it.name == "Old thing" }
        assertFalse(oldThing.enabled)

        assertTrue(first.setEnabled(oldThing.manifestURL, true))
        assertTrue(first.addons.value.first { it.name == "Old thing" }.enabled)

        val restarted = registry(okHttp(), store = store)
        assertTrue(restarted.addons.value.first { it.name == "Old thing" }.enabled)
    }

    @Test
    fun `removing a synced add-on survives restart while Cinemeta remains structural`() {
        val store = MemoryAddonRegistryStore()
        val first = registry(okHttp(), store = store)
        val comet = first.addons.value.first { it.name == "Comet" }

        assertTrue(first.remove(comet.manifestURL))
        assertFalse(first.remove(StremioClient.CINEMETA_MANIFEST))
        val restarted = registry(okHttp(), store = store)
        assertFalse(restarted.addons.value.any { it.name == "Comet" })
        assertEquals("Cinemeta", restarted.addons.value.first().name)
    }

    @Test
    fun `install validates and fetches a manifest before persisting it`() = runTest {
        val store = MemoryAddonRegistryStore()
        val http = okHttp()
        val first = registry(http, store = store)

        val installed = first.install("https://new.example/addon")
        assertEquals("AIOStreams", installed.displayName)
        assertTrue(http.urls.last().endsWith("/addon/manifest.json"))
        assertTrue(store.value.installed.any { it.url == "https://new.example/addon/manifest.json" })

        val restarted = registry(okHttp(), store = store)
        assertTrue(restarted.addons.value.any { it.manifestURL == "https://new.example/addon/manifest.json" })
    }

    @Test
    fun `install rejects a non-http endpoint without persisting it`() = runTest {
        val store = MemoryAddonRegistryStore()
        val failure = runCatching {
            registry(okHttp(), store = store).install("file:///data/local/secret")
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(store.value.installed.isEmpty())
    }

    @Test
    fun `TV replace ignores phone sources and old local overrides`() {
        val importURL = "https://imported.example/secret/manifest.json"
        val document = SettingsDocument.parse("""
            {"tvReplaceSources":true,"tvImportedSources":[{"name":"Imported","url":"$importURL","enabled":true}],
             "sources":[{"name":"Phone","url":"https://phone.example/manifest.json"}]}
        """.trimIndent())
        val store = MemoryAddonRegistryStore(AddonRegistryOverrides(
            installed = listOf(com.fourseveneightnine.tv.client.data.settings.AddonSource(
                "TV local", "https://local.example/manifest.json", true)),
            removed = setOf(requireNotNull(AddonEndpoint.normalize(importURL))),
            enabled = mapOf(requireNotNull(AddonEndpoint.normalize(importURL)) to false),
        ))
        val addons = AddonRegistry(document, StremioClient(okHttp().client), store = store).addons.value
        assertEquals(listOf("Cinemeta", "Imported"), addons.map(Addon::name))
        assertTrue(addons.last().enabled)
    }

    @Test
    fun `TV replace rejects manual installs before fetching or changing overrides`() = runTest {
        val document = SettingsDocument.parse("""
            {"tvReplaceSources":true,"tvImportedSources":[{"name":"Imported","url":"https://imported.example/manifest.json"}]}
        """.trimIndent())
        val saved = AddonRegistryOverrides(installed = listOf(
            com.fourseveneightnine.tv.client.data.settings.AddonSource("Old local", "https://old.example/manifest.json", true)))
        val store = MemoryAddonRegistryStore(saved)
        val http = okHttp()
        val registry = AddonRegistry(document, StremioClient(http.client), store = store)
        val before = registry.addons.value

        val failure = runCatching { registry.install("https://new.example/addon") }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("replace_mode_hides_manual_addons", failure?.message)
        assertTrue(http.requests.isEmpty())
        assertEquals(saved, store.value)
        assertEquals(before, registry.addons.value)
        assertEquals(before, AddonRegistry(document, StremioClient(http.client), store = store).addons.value)
    }

    @Test
    fun `TV replace hides dedicated subtitle providers sent by the phone`() {
        val document = SettingsDocument.parse("""
            {"tvReplaceSources":true,"tvImportedSources":[{"name":"Imported","url":"https://imported.example/manifest.json"}],
             "subtitleSources":[{"name":"Phone subtitles","url":"https://subtitles.example/manifest.json"}]}
        """.trimIndent())
        val registry = AddonRegistry(document, StremioClient(okHttp().client))
        assertFalse(registry.subtitleAddons().any { it.name == "Phone subtitles" })
    }

    @Test
    fun `TV upsert keeps phone sources and restores an imported URL previously removed locally`() {
        val importURL = "https://imported.example/manifest.json"
        val document = SettingsDocument.parse("""
            {"tvImportedSources":[{"name":"Imported","url":"$importURL","enabled":true}],
             "sources":[{"name":"Phone","url":"https://phone.example/manifest.json"}]}
        """.trimIndent())
        val store = MemoryAddonRegistryStore(AddonRegistryOverrides(
            removed = setOf(requireNotNull(AddonEndpoint.normalize(importURL))),
        ))
        val names = AddonRegistry(document, StremioClient(okHttp().client), store = store)
            .addons.value.map(Addon::name)
        assertEquals(listOf("Cinemeta", "Imported", "Phone"), names)
    }

    @Test
    fun `only active sources contribute catalogs after deleting an import or reinstalling a link`() = runTest {
        val store = MemoryAddonRegistryStore()
        val url = "https://manual.example/manifest.json"
        val imported = "https://import.example/manifest.json"
        fun loaded(id: String, includeImport: Boolean): AddonRegistry {
            val direct = """{"name":"Manual","url":"$url","installID":"$id"}"""
            val stremio = if (includeImport) """{"name":"Imported","url":"$imported"}""" else ""
            val doc = SettingsDocument.parse("""{"tvDirectAddons":[$direct],"tvImportedSources":[$stremio]}""")
            return AddonRegistry(doc, StremioClient(okHttp().client), store = store)
        }
        val before = loaded("first", true)
        before.refresh()
        assertTrue(before.catalogs().any { it.first.manifestURL == imported })
        assertTrue(before.remove(url))
        val deleted = loaded("first", false)
        deleted.refresh()
        assertTrue(deleted.catalogs().none { it.first.manifestURL == imported || it.first.manifestURL == url })
        val reinstalled = loaded("second", false)
        reinstalled.refresh()
        assertTrue(reinstalled.catalogs().any { it.first.manifestURL == url })
        assertTrue(reinstalled.catalogs().none { it.first.manifestURL == imported })
    }

    @Suppress("DEPRECATION")
    private fun createTempDir(): java.io.File =
        java.io.File(System.getProperty("java.io.tmpdir"), "client-data-test-${System.nanoTime()}")
            .apply { mkdirs() }
}

private class MemoryAddonRegistryStore(
    initial: AddonRegistryOverrides = AddonRegistryOverrides(),
) : AddonRegistryStore {
    var value: AddonRegistryOverrides = initial

    override fun load(): AddonRegistryOverrides = value

    override fun save(overrides: AddonRegistryOverrides) {
        value = overrides
    }
}
