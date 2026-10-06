package com.fourseveneightnine.tv.client.data.addons

import com.fourseveneightnine.tv.client.data.FakeAnswer
import com.fourseveneightnine.tv.client.data.FakeHttp
import com.fourseveneightnine.tv.client.data.fixture
import com.fourseveneightnine.tv.client.data.settings.SettingsDocument
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonRegistryTest {

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
        assertFalse(registry.streamAddons().any { it.name == "Comet" })
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
