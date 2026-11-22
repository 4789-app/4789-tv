package com.fourseveneightnine.tv.client.data.streams

import com.fourseveneightnine.tv.client.data.FakeAnswer
import com.fourseveneightnine.tv.client.data.FakeHttp
import com.fourseveneightnine.tv.client.data.addons.AddonRegistry
import com.fourseveneightnine.tv.client.data.addons.InMemoryAddonHealthStore
import com.fourseveneightnine.tv.client.data.addons.StremioClient
import com.fourseveneightnine.tv.client.data.fixture
import com.fourseveneightnine.tv.client.data.settings.SettingsDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamSearchTest {

    @Test fun `an expired checked DMM route refreshes the same full filename from wrapped display text`() = runTest { withContext(Dispatchers.IO) {
        val filename = "Heart.Of.The.Beast.Nel.Profondo.Selvaggio.2026.iTA-ENG.WEBDL.2160p.HEVC.HDR.x265-CYBER.mkv"
        val manifest = "https://debridmediamanager.com/api/stremio/profile/manifest.json"
        val display = kotlinx.serialization.json.JsonPrimitive("Heart.Of.The.Beast.Nel.Profondo.Selvaggio.202-\n6.iTA-ENG.WEBDL.2160p.HEVC.HDR.x265-CYBER.mkv\n🎬 DMM Cast RD")
        val http = FakeHttp { request -> when {
            request.url.host.contains("cinemeta") -> FakeAnswer(body = fixture("manifest-cinemeta.json"))
            request.url.encodedPath.endsWith("manifest.json") -> FakeAnswer(body = fixture("manifest-aiostreams.json"))
            else -> FakeAnswer(body = """{"streams":[
                {"url":"https://debridmediamanager.com/api/stremio/profile/play/WRONG","title":"Other.Movie.2026.mkv\n🎬 DMM Cast RD"},
                {"url":"https://debridmediamanager.com/api/stremio/profile/play/FRESH","title":$display}]}""")
        } }
        val client = StremioClient(http.client)
        val registry = AddonRegistry(SettingsDocument.parse("""{"sources":[{"name":"DMM Cast for Real-Debrid","url":"$manifest","enabled":true}]}"""), client)
        registry.refresh()
        val addon = registry.streamAddons().single { it.manifestURL == manifest }
        val expected = StreamExpectation("Heart of the Beast", 2026, null, null, true,
            aliases = listOf("Heart of the Beast - Nel profondo selvaggio"))
        val old = StreamRow("checked", addon.displayName, "4K", filename, filename = filename,
            url = "https://cdn.example/expired-token", refreshAddonKey = addon.key, refreshID = "tt7526136")
        val fresh = requireNotNull(StreamSearch(registry, client).refresh(old, "movie", expected))
        assertTrue(fresh.url.orEmpty().endsWith("/FRESH"))
        assertEquals(filename, fresh.filename)
        assertEquals(old.id, fresh.id)
        assertTrue(fresh.identityVerified == true)
    } }

    @Test
    fun `checked duplicate cache files merge but different encodes remain separate choices`() = runTest {
        val client = StremioClient(FakeHttp { FakeAnswer(body = "{\"streams\":[]}") }.client)
        val registry = AddonRegistry(SettingsDocument.empty, client)
        val first = StreamRow("one", "DMM Cast for TorBox", "1080p", "The.Bear.S02E04.1080p.mkv",
            url = "https://cdn.example/one.mkv", filename = "The.Bear.S02E04.1080p.mkv", cacheHash = "a".repeat(40),
            cachedHint = CachedHint(true, "TorBox", verified = true), verifiedCacheFile = "Season2/The.Bear.S02E04.1080p.mkv")
        val duplicate = first.copy(id = "two", url = "https://cdn.example/another-route.mkv", facts = StreamFacts(subtitleLanguages = listOf("French")))
        val different = first.copy(id = "three", url = "https://cdn.example/different.mkv", cacheHash = "b".repeat(40))
        val search = StreamSearch(registry, client, validateSource = { row, _ -> row.copy(identityVerified = true, linkCheckedAtMillis = 1) })
        val state = search.validatedForDisplay(flowOf(StreamSearchState(rows = listOf(first, duplicate, different), done = true)),
            StreamExpectation("The Bear", 2022, 2, 4, false)).toList().last()
        assertEquals(2, state.rows.size)
        assertEquals(listOf("French"), state.rows.first().facts.subtitleLanguages)
        assertEquals(setOf("a".repeat(40), "b".repeat(40)), state.rows.map { it.cacheHash }.toSet())
    }

    @Test
    fun `a DMM link rejected after selection cannot reappear from an earlier passed result`() = runTest {
        val client = StremioClient(FakeHttp { FakeAnswer(body = "{\"streams\":[]}") }.client)
        val registry = AddonRegistry(SettingsDocument.empty, client)
        val row = StreamRow("dmm", "DMM Cast for TorBox", "DMM", "The.Bear.S02E04.mkv",
            url = "https://cdn.example/file.mkv", filename = "The.Bear.S02E04.mkv")
        val rejected = MutableStateFlow<Set<String>>(emptySet())
        val search = StreamSearch(registry, client, validateSource = { value, _ ->
            kotlinx.coroutines.delay(10);value.copy(identityVerified = true)
        })
        val states = search.validatedForDisplay(flowOf(StreamSearchState(rows = listOf(row), done = true)),
            StreamExpectation("The Bear", 2022, 2, 4, false), followUpdates = true, rejectedIDs = rejected)
            .onEach { if (it.rows.isNotEmpty()) rejected.value = setOf("dmm") }.take(3).toList()
        assertTrue(states.first().rows.isEmpty())
        assertEquals(1, states[1].rows.size)
        assertTrue(states.last().rows.isEmpty())
        assertEquals(1, states.last().rejectedLinks)
    }

    @Test
    fun `DMM display gate hides pending failed and wrong episode links while other sources remain usable`() = runTest {
        val client = StremioClient(FakeHttp { FakeAnswer(body = "{\"streams\":[]}") }.client)
        val registry = AddonRegistry(SettingsDocument.empty, client)
        val expected = StreamExpectation("The Bear", 2022, 2, 4, false)
        fun row(id: String, addon: String, episode: Int = 4) = StreamRow(id, addon, addon,
            "The.Bear.S02E0$episode.1080p.mkv", url = "https://cdn.example/$id.mkv", filename = "The.Bear.S02E0$episode.1080p.mkv")
        val good = row("good", "DMM Cast for TorBox")
        val bad = row("bad", "DMM Cast for Real-Debrid")
        val wrong = row("wrong", "DMM Cast for TorBox", 3)
        val other = row("other", "Other addon")
        val search = StreamSearch(registry, client, validateSource = { source, _ ->
            kotlinx.coroutines.delay(100)
            if (source.id == "bad") null else source.copy(identityVerified = true)
        })
        val states = search.validatedForDisplay(flowOf(StreamSearchState(rows = listOf(good, bad, wrong, other), attempted = 3, done = true)), expected).toList()
        assertTrue(states.first().rows.contains(other))
        assertFalse(states.first().rows.any { it.requiresDmmValidation })
        assertTrue(states.first().checkingLinks > 0)
        assertFalse(states.first().done)
        assertEquals(setOf("good", "other"), states.last().rows.map { it.id }.toSet())
        assertEquals(2, states.last().rejectedLinks)
        assertTrue(states.last().done)
        val noValidator = StreamSearch(registry, client).validatedForDisplay(
            flowOf(StreamSearchState(rows = listOf(good, other), done = true)), expected).toList().last()
        assertEquals(listOf(other), noValidator.rows)
    }

    @Test
    fun `phone and inline DMM cache flags cannot bypass local display validation`() = runTest {
        val client = StremioClient(FakeHttp { FakeAnswer(body = "{\"streams\":[]}") }.client)
        val registry = AddonRegistry(SettingsDocument.empty, client)
        val untrusted = StreamRow("phone", "DMM Cast for TorBox", "Cached", "The.Bear.S02E04.mkv",
            url = "https://cdn.example/file.mkv", cachedHint = CachedHint(true, "TorBox", verified = true), identityVerified = true)
        val search = StreamSearch(registry, client, phoneRows = { _, _ -> flowOf(listOf(untrusted)) }, validateSource = { _, _ -> null })
        val expected = StreamExpectation("The Bear", 2022, 2, 4, false)
        val result = search.validatedForDisplay(search.search("series", "tt1", 2, 4), expected).toList().last()
        assertTrue(result.rows.isEmpty())
        val inline = search.validatedForDisplay(flowOf(StreamSearchState(rows = listOf(untrusted), done = true)), expected).toList().last()
        assertTrue(inline.rows.isEmpty())
        assertEquals(1, inline.rejectedLinks)
    }

    @Test
    fun `identical transport duplicates keep complementary metadata and verified cache evidence`() = runTest {
        val client = StremioClient(FakeHttp { FakeAnswer(body = "{\"streams\":[]}") }.client)
        val registry = AddonRegistry(SettingsDocument.empty, client)
        val first = StreamRow("first", "Source", "4K", "Movie.2025.mkv", url = "https://cdn.example/file.mkv",
            sizeBytes = 4_000_000_000L, cachedHint = CachedHint(true, "TorBox", verified = true),
            facts = StreamFacts(codecs = listOf("HEVC")))
        val richer = first.copy(id = "second", sizeBytes = null, cachedHint = CachedHint(false),
            facts = StreamFacts(audioLanguages = listOf("English"), reportedAudioLanguages = listOf("English"),
                subtitleLanguages = listOf("French"), durationSeconds = 3226, sourceDates = mapOf("Published" to 1_761_000_000_000L)))
        val result = StreamSearch(registry, client).search("movie", listOf("tt1"), inlineRows = listOf(first, richer)).toList().last().rows.single()
        assertTrue(result.cachedHint.verified)
        assertEquals(4_000_000_000L, result.sizeBytes)
        assertEquals(listOf("HEVC"), result.facts.codecs)
        assertEquals(listOf("English"), result.facts.reportedAudioLanguages)
        assertEquals(listOf("French"), result.facts.subtitleLanguages)
        assertEquals(3226L, result.facts.durationSeconds)
        assertTrue(result.facts.sourceDates.containsKey("Published"))
    }

    @Test
    fun `legacy DMM season packs bind only a unique cached episode before becoming verified`() = runTest { withContext(Dispatchers.IO) {
        val hash = "0123456789abcdef0123456789abcdef01234567"
        val file = "The.Bear.S02E04.1080p.mkv"
        for ((ambiguous, fullLabel) in listOf(false to false, false to true, true to false, true to true)) {
            val display = if (fullLabel) file else "The.Bear.S02E04.1080p.-"
            val extra = if (ambiguous) ",{\"id\":5,\"name\":\"The.Bear.S02E04.720p.mkv\",\"size\":2000000000}" else ""
            val http = FakeHttp { request -> when {
                request.url.host.contains("cinemeta") -> FakeAnswer(body = fixture("manifest-cinemeta.json"))
                request.url.encodedPath.endsWith("manifest.json") -> FakeAnswer(body = fixture("manifest-aiostreams.json"))
                request.url.host == "debridmediamanager.com" -> FakeAnswer(body = """{"streams":[{"name":"1080p","title":"$display",
                    "url":"https://debridmediamanager.com/api/stremio-tb/profile/play/$hash"}]}""")
                request.url.encodedPath.endsWith("checkcached") -> FakeAnswer(body = """{"success":true,"data":{"$hash":{"hash":"$hash","files":[{"id":3,"name":"The.Bear.S02E03.1080p.mkv","size":5000000000},{"id":4,"name":"$file","size":4000000000}$extra]}}}""")
                else -> FakeAnswer(code = 500)
            } }
            val client = StremioClient(http.client)
            val config = SettingsDocument.parse("""{"sources":[{"name":"DMM Cast for TorBox","url":"https://debridmediamanager.com/api/stremio-tb/profile/manifest.json","enabled":true}]}""")
            val registry = AddonRegistry(config, client);registry.refresh()
            val search = StreamSearch(registry, client, torbox = com.fourseveneightnine.tv.client.data.streams.debrid.TorboxResolver("key", http.client, "https://api.example"))
            val row = search.search("series", "tt1", season = 2, episode = 4,
                expectation = StreamExpectation("The Bear", 2022, 2, 4, false)).toList().last().rows.single()
            val verified = !ambiguous || fullLabel
            assertEquals(verified, row.cachedHint.verified)
            if (!verified) assertFalse(row.url.orEmpty().contains("?file="))
            else {
                assertEquals(file, row.filename)
                assertTrue(row.url.orEmpty().endsWith("?file=$file"))
                assertEquals("Cached Debrid", StreamRanker.deliveryLabel(row))
            }
            assertTrue(http.urls.none { it.contains("createtorrent") || it.contains("requestdl") })
        }
    } }

    @Test fun `an unfamiliar hash source needs a cached copy of the selected episode`() = runTest { withContext(Dispatchers.IO) {
        val good = "a".repeat(40)
        val wrong = "b".repeat(40)
        val missing = "c".repeat(40)
        val http = FakeHttp { FakeAnswer(body = """{"success":true,"data":{
            "$good":{"hash":"$good","files":[{"id":7,"name":"The.Bear.S02E04.1080p.mkv","size":800000000}]},
            "$wrong":{"hash":"$wrong","files":[{"id":8,"name":"The.Bear.S02E03.1080p.mkv","size":800000000}]}
        }}""") }
        val client = StremioClient(http.client)
        val torbox = com.fourseveneightnine.tv.client.data.streams.debrid.TorboxResolver("key", http.client, "https://cache.example")
        val search = StreamSearch(AddonRegistry(SettingsDocument.empty, client), client, torbox = torbox)
        val rows = listOf(good, wrong, missing).map { hash ->
            StreamRow(hash, "New add-on", "Season 2", "The Bear season pack", infoHash = hash)
        }
        val expected = StreamExpectation("The Bear", 2022, 2, 4, false)
        val verified = search.verifyRows(rows, expected)
        val result = search.validatedForDisplay(flowOf(StreamSearchState(rows = verified, done = true)), expected).toList()
        val source = result.last().rows.single()
        assertEquals(good, source.infoHash)
        assertEquals(7, source.torboxFileID)
        assertEquals("1080p", source.quality)
        assertTrue(source.cachedDebrid)
        assertEquals(listOf("/v1/api/torrents/checkcached"), http.requests.map { it.url.encodedPath })
    } }

    @Test
    fun `modern DMM TorBox links receive exact filename cache proof without acquiring a torrent`() = runTest { withContext(Dispatchers.IO) {
        val hash = "0123456789abcdef0123456789abcdef01234567"
        val file = "The.Bear.S02E04.1080p.mkv"
        val http = FakeHttp { request -> when {
            request.url.host.contains("cinemeta") -> FakeAnswer(body = fixture("manifest-cinemeta.json"))
            request.url.encodedPath.endsWith("manifest.json") -> FakeAnswer(body = fixture("manifest-aiostreams.json"))
            request.url.host == "debridmediamanager.com" -> FakeAnswer(body = """{"streams":[{"name":"1080p","title":"$file",
                "url":"https://debridmediamanager.com/api/stremio-tb/profile/play/123:4?h=$hash&file=$file"}]}""")
            request.url.encodedPath.endsWith("checkcached") -> FakeAnswer(body = """{"success":true,"data":{"$hash":{"hash":"$hash","files":[{"id":4,"name":"$file","size":4000000000}]}}}""")
            else -> FakeAnswer(code = 500)
        } }
        val client = StremioClient(http.client)
        val config = SettingsDocument.parse("""{"sources":[{"name":"DMM Cast for TorBox","url":"https://debridmediamanager.com/api/stremio-tb/profile/manifest.json","enabled":true}]}""")
        val registry = AddonRegistry(config, client)
        registry.refresh()
        val search = StreamSearch(registry, client, torbox = com.fourseveneightnine.tv.client.data.streams.debrid.TorboxResolver("key", http.client, "https://api.example"))
        val row = search.search("series", "tt1", season = 2, episode = 4,
            expectation = StreamExpectation("The Bear", 2022, 2, 4, false)).toList().last().rows.single()
        assertEquals(hash, row.cacheHash)
        assertTrue(row.cachedHint.verified)
        assertEquals("TorBox", row.cachedHint.service)
        assertTrue(http.urls.none { it.contains("createtorrent") || it.contains("requestdl") })
    } }

    @Test
    fun `each addon attempt logs its own title episode outcome and search id`() = runTest { withContext(Dispatchers.IO) {
        val events = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
        val http = FakeHttp { request -> when {
            request.url.host.contains("cinemeta") -> FakeAnswer(body = fixture("manifest-cinemeta.json"))
            request.url.encodedPath.endsWith("manifest.json") -> FakeAnswer(body = fixture("manifest-aiostreams.json"))
            request.url.host.startsWith("broken") -> FakeAnswer(code = 429)
            else -> FakeAnswer(body = "{\"streams\":[]}")
        } }
        val client = StremioClient(http.client)
        val registry = AddonRegistry(document, client)
        registry.refresh()
        val expected = StreamExpectation("IT: Welcome to Derry", 2025, 1, 2, false)
        val search = StreamSearch(registry, client, diagnostics = { addon, context, detail ->
            assertEquals(expected, context)
            events += addon to detail
        })
        val last = search.search("series", "tt1", season = 1, episode = 2, expectation = expected).toList().last()
        assertEquals(1, last.failed)
        assertEquals(3, events.size)
        assertTrue(events.any { it.first == "Broken" && it.second.contains("result=http_429") })
        assertEquals(2, events.count { it.second.contains("result=ok rows=0") })
        assertEquals(1, events.map { it.second.substringBefore(' ') }.distinct().size)
        assertTrue(events.none { it.second.contains("https://") || it.second.contains("/stream/") })
    } }

    @Test
    fun `phone arrivals fill an empty completed TV search without repeating native requests`() = runTest {
        val http = FakeHttp { FakeAnswer(body = """{"streams":[]}""") }
        val client = StremioClient(http.client)
        val registry = AddonRegistry(SettingsDocument.parse("""{"sources":[]}"""), client)
        val phone = MutableStateFlow<List<StreamRow>>(emptyList())
        val first = CompletableDeferred<Unit>()
        val search = StreamSearch(registry, client, phoneRows = { _, _ -> phone })
        val results = async {
            search.search("movie", listOf("tt43741215", "tmdb:607833"),
                expectation = StreamExpectation("The Intern", 2026, null, null, true), followPhoneResults = true)
                .onEach { first.complete(Unit) }.take(2).toList()
        }
        first.await()
        phone.value = (1..34).map { StreamRow("phone-$it", "FileMood", "The Intern",
            "The.Intern.2026.1080p.$it.mkv", infoHash = "%040x".format(it)) }
        val states = results.await()
        assertTrue(states.first().done)
        assertTrue(states.first().rows.isEmpty())
        assertEquals(34, states.last().rows.size)
        assertTrue(states.last().rows.all { it.identityVerified == true })
        assertTrue(http.requests.isEmpty())
        // Automatic Play still finishes if no phone answer exists.
        phone.value = emptyList()
        assertTrue(search.search("movie", listOf("tt43741215")).toList().last().done)
    }

    @Test
    fun `shared direct duplicates retain native provenance and required header variants`() = runTest {
        val http = FakeHttp { FakeAnswer(body = """{"streams":[]}""") }
        val client = StremioClient(http.client)
        val registry = AddonRegistry(SettingsDocument.parse("""{"sources":[]}"""), client)
        val native = StreamRow("native", "Source", "The Intern", "The.Intern.2026.mkv",
            url = "https://cdn.example/file.mkv", refreshAddonKey = "source", refreshID = "tt43741215")
        val phone = native.copy(id = "phone", addonName = "Phone")
        val protected = phone.copy(headers = mapOf("Referer" to "https://source.example"))
        val exact = phone.copy(url = null, infoHash = "a".repeat(40), torboxTorrentID = 42, torboxFileID = 1)
        val otherFile = exact.copy(torboxFileID = 2)
        val states = StreamSearch(registry, client, phoneRows = { _, _ -> flowOf(listOf(phone, protected, exact, otherFile)) })
            .search("movie", listOf("tt43741215"), inlineRows = listOf(native)).toList()
        val rows = states.last().rows
        assertEquals(4, rows.size)
        assertEquals("source", rows.first().refreshAddonKey)
        assertEquals("tt43741215", rows.first().refreshID)
        assertTrue(rows.any { it.headers["Referer"] == "https://source.example" })
        assertEquals(setOf(1, 2), rows.mapNotNull { it.torboxFileID }.toSet())
    }

    @Test
    fun `same file refresh retains request provenance after URL identity deduplication`() = runTest { withContext(Dispatchers.IO) {
        var refreshed = false
        val http = FakeHttp { request ->
            when {
                request.url.host.contains("cinemeta") -> FakeAnswer(body = fixture("manifest-cinemeta.json"))
                request.url.encodedPath.endsWith("manifest.json") -> FakeAnswer(body = fixture("manifest-aiostreams.json"))
                else -> FakeAnswer(body = """{"streams":[{"url":"https://cdn.example/${if (refreshed) "new" else "old"}.mkv","behaviorHints":{"filename":"Movie.2024.1080p.mkv"}}]}""")
            }
        }
        val document = SettingsDocument.parse("""{"sources":[{"name":"Source","url":"https://source.example/manifest.json","enabled":true}]}""")
        val client = StremioClient(http.client)
        val registry = AddonRegistry(document, client, InMemoryAddonHealthStore())
        registry.refresh()
        val search = StreamSearch(registry, client)
        val expected = StreamExpectation("Movie", 2024, null, null, true)
        val original = search.search("movie", "tt1375666", expectation = expected).toList().last().rows.single()
        assertEquals("tt1375666", original.refreshID)
        refreshed = true
        val fresh = search.refresh(original, "movie", expected)
        assertEquals("https://cdn.example/new.mkv", fresh?.url)
        assertEquals(original.id, fresh?.id)
        assertTrue(fresh?.identityVerified == true)
    } }

    @Test
    fun `a duplicate URL upgrades name evidence without moving the focused identity`() = runTest { withContext(Dispatchers.IO) {
        val document = SettingsDocument.parse("""{"sources":[
            {"name":"HdHub","url":"https://fast.example/manifest.json","enabled":true},
            {"name":"Bridge","url":"https://slow.example/manifest.json","enabled":true}]}""")
        val http = FakeHttp { request ->
            when {
                request.url.host.contains("cinemeta") -> FakeAnswer(body = fixture("manifest-cinemeta.json"))
                request.url.encodedPath.endsWith("manifest.json") -> FakeAnswer(body = fixture("manifest-aiostreams.json"))
                request.url.host.startsWith("fast") -> FakeAnswer(body =
                    """{"streams":[{"url":"https://cdn.example/same.mkv","name":"1080p"}]}""")
                else -> FakeAnswer(body =
                    """{"streams":[{"url":"https://cdn.example/same.mkv","behaviorHints":{"filename":"Movie.2024.1080p.mkv"}}]}""",
                    delayMillis = 100)
            }
        }
        val client = StremioClient(http.client)
        val registry = AddonRegistry(document, client, InMemoryAddonHealthStore())
        registry.refresh()
        val states = StreamSearch(registry, client).search("movie", "tt1375666",
            expectation = StreamExpectation("Movie", 2024, null, null, true)).toList()
        val first = states.first { it.rows.isNotEmpty() }.rows.single()
        val last = states.last().rows.single()
        assertFalse(first.identityVerified == true)
        assertTrue(last.identityVerified == true)
        assertEquals(first.id, last.id)
    } }

    @Test
    fun `TorBox priority requires both a cache response and the matching cached filename`() = runTest { withContext(Dispatchers.IO) {
        val hash = "a".repeat(40)
        var cachedName = "Movie.2024.1080p.mkv"
        var cacheDelayMillis = 0L
        var cacheMissing = false
        val http = FakeHttp { request ->
            when {
                request.url.host.contains("cinemeta") -> FakeAnswer(body = fixture("manifest-cinemeta.json"))
                request.url.encodedPath.endsWith("manifest.json") -> FakeAnswer(body = fixture("manifest-aiostreams.json"))
                request.url.encodedPath.endsWith("checkcached") -> {
                    assertEquals("Bearer key", request.header("Authorization"))
                    assertEquals("true", request.url.queryParameter("list_files"))
                    FakeAnswer(body = if (cacheMissing) """{"success":true,"data":{}}""" else """{"success":true,"data":{"$hash":{"files":[{"id":1,"name":"folder/$cachedName","size":1000}]}}}""",
                        delayMillis = cacheDelayMillis)
                }
                else -> FakeAnswer(body = """{"streams":[{"url":"https://cdn.example/$hash/video.mkv","name":"[TB+] TorBox","behaviorHints":{"filename":"Movie.2024.1080p.mkv"}}]}""")
            }
        }
        val document = SettingsDocument.parse("""{"sources":[{"name":"TorBox","url":"https://tb.example/manifest.json","enabled":true}]}""")
        val client = StremioClient(http.client)
        val registry = AddonRegistry(document, client, InMemoryAddonHealthStore())
        registry.refresh()
        val search = StreamSearch(registry, client,
            torbox = com.fourseveneightnine.tv.client.data.streams.debrid.TorboxResolver("key", http.client, "https://api.example"))
        val expectation = StreamExpectation("Movie", 2024, null, null, true)
        val checked = search.search("movie", "tt1375666", expectation = expectation).toList().last().rows.single()
        assertTrue(checked.identityVerified == true)
        assertTrue(checked.cachedHint.verified)
        cachedName = "Other.2024.1080p.mkv"
        val wrong = search.search("movie", "tt1375666", expectation = expectation).toList().last().rows.single()
        assertFalse(wrong.cachedHint.verified)
        assertFalse(wrong.identityVerified == true)
        cachedName = "Movie.2024.720p.mkv"
        val otherRelease = search.search("movie", "tt1375666", expectation = expectation).toList().last().rows.single()
        assertFalse(otherRelease.cachedHint.verified)
        cacheMissing = true
        val missing = search.search("movie", "tt1375666", expectation = expectation).toList().last().rows.single()
        assertFalse(missing.cachedHint.cached)
        cacheMissing = false
        cacheDelayMillis = 3_000L
        val slow = search.search("movie", "tt1375666", expectation = expectation).toList().last()
        assertEquals(1, slow.rows.size)
        assertEquals(0, slow.failed)
        assertFalse(slow.rows.single().cachedHint.verified)
        assertFalse(http.urls.any { it.contains("createtorrent") || it.contains("requestdl") })
    } }

    @Test
    fun `a cold thirteen second addon still arrives while fast results publish immediately`() = runTest { withContext(Dispatchers.IO) {
        val http = FakeHttp { request -> when {
            request.url.host.contains("cinemeta") -> FakeAnswer(body = fixture("manifest-cinemeta.json"))
            request.url.encodedPath.endsWith("manifest.json") -> FakeAnswer(body = fixture("manifest-aiostreams.json"))
            request.url.host.startsWith("fast") -> FakeAnswer(body = streamBody("Fast", "a".repeat(40)))
            request.url.host.startsWith("slow") -> FakeAnswer(body = streamBody("Slow", "b".repeat(40)), delayMillis = 13_000)
            else -> FakeAnswer(code = 500)
        } }
        val client = StremioClient(http.client)
        val registry = AddonRegistry(document, client, InMemoryAddonHealthStore())
        registry.refresh()
        val states = StreamSearch(registry, client).search("movie", "tt1375666").toList()
        assertEquals(1, states.first { it.rows.isNotEmpty() }.rows.size)
        assertTrue(states.first { it.rows.isNotEmpty() }.pending > 0)
        assertTrue(states.last().rows.any { it.title == "Slow" })
    } }

    /** Three stream add-ons: fast, slow, and one that falls over. */
    private val document = SettingsDocument.parse(
        """
        {
          "sources": [
            { "name": "Fast", "url": "https://fast.example.com/manifest.json", "enabled": true },
            { "name": "Slow", "url": "https://slow.example.com/manifest.json", "enabled": true },
            { "name": "Broken", "url": "https://broken.example.com/manifest.json", "enabled": true }
          ]
        }
        """.trimIndent(),
    )

    private fun streamBody(name: String, hash: String) = """
        {"streams":[{"name":"$name","title":"Movie.2024.1080p.WEB-DL.x265-$name",
        "infoHash":"$hash"}]}
    """.trimIndent()

    private suspend fun search(
        slowDelayMillis: Long,
        setDeadlineMillis: Long = 12_000L,
        perAddonTimeoutMillis: Long = 8_000L,
    ): List<StreamSearchState> = withContext(Dispatchers.IO) {
        val http = FakeHttp { request ->
            when {
                // Cinemeta is always in the registry, and its real manifest declares no `stream`
                // resource, so it must not count as a fourth stream add-on here either.
                request.url.host.contains("cinemeta") -> FakeAnswer(body = fixture("manifest-cinemeta.json"))
                request.url.toString().contains("/manifest.json") ->
                    FakeAnswer(body = fixture("manifest-aiostreams.json"))
                request.url.host.startsWith("fast") ->
                    FakeAnswer(body = streamBody("Fast", "a".repeat(40)))
                request.url.host.startsWith("slow") ->
                    FakeAnswer(body = streamBody("Slow", "b".repeat(40)), delayMillis = slowDelayMillis)
                else -> FakeAnswer(code = 500, body = "down")
            }
        }
        val client = StremioClient(http.client)
        val registry = AddonRegistry(document, client, InMemoryAddonHealthStore())
        registry.refresh()
        StreamSearch(
            registry = registry,
            client = client,
            perAddonTimeoutMillis = perAddonTimeoutMillis,
            setDeadlineMillis = setDeadlineMillis,
        ).search("movie", "tt1375666").toList()
    }

    @Test
    fun `the fast add-on publishes before the slow one finishes`() = runTest {
        val states = search(slowDelayMillis = 400)
        // The first state is the "searching" one, before anything answered.
        assertEquals(0, states.first().rows.size)
        assertEquals(3, states.first().attempted)
        assertEquals(3, states.first().pending)

        val firstWithRows = states.first { it.rows.isNotEmpty() }
        assertEquals(1, firstWithRows.rows.size)
        assertEquals("Fast", firstWithRows.rows.single().title)
        assertTrue(firstWithRows.pending > 0)
    }

    @Test
    fun `a row that has been published never moves`() = runTest {
        val states = search(slowDelayMillis = 300).filter { it.rows.isNotEmpty() }
        val firstIDs = states.first().rows.map(StreamRow::id)
        states.forEach { state ->
            assertEquals(
                "a published row moved",
                firstIDs,
                state.rows.take(firstIDs.size).map(StreamRow::id),
            )
        }
    }

    @Test
    fun `the last state is done and counts the add-on that failed`() = runTest {
        val last = search(slowDelayMillis = 200).last()
        assertTrue(last.done)
        assertEquals(0, last.pending)
        assertEquals(3, last.attempted)
        assertEquals(1, last.failed)
        assertEquals(2, last.rows.size)
    }

    @Test
    fun `the set deadline ends the wait and keeps what answered`() = runTest {
        val last = search(slowDelayMillis = 3_000, setDeadlineMillis = 500).last()
        assertTrue(last.done)
        assertEquals(1, last.rows.size)
        assertEquals("Fast", last.rows.single().title)
        // The straggler and the broken one both count as failures in the chip.
        assertEquals(2, last.failed)
    }

    @Test
    fun `a per-add-on timeout is counted as a failure, not as an empty answer`() = runTest {
        val last = search(slowDelayMillis = 1_500, perAddonTimeoutMillis = 300).last()
        assertTrue(last.done)
        assertEquals(1, last.rows.size)
        assertEquals(2, last.failed)
    }

    @Test
    fun `no stream add-ons gives one done state`() = runTest {
        val http = FakeHttp { FakeAnswer(body = """{"id":"x","name":"X","resources":["meta"]}""") }
        val client = StremioClient(http.client)
        val registry = AddonRegistry(SettingsDocument.empty, client, InMemoryAddonHealthStore())
        registry.refresh()
        val states = StreamSearch(registry, client).search("movie", "tt1375666").toList()
        assertEquals(1, states.size)
        assertTrue(states.single().done)
        assertTrue(states.single().rows.isEmpty())
    }

    @Test
    fun `two add-ons offering the same hash is one row`() = runTest {
        val states = withContext(Dispatchers.IO) {
            val http = FakeHttp { request ->
                when {
                    request.url.host.contains("cinemeta") ->
                        FakeAnswer(body = fixture("manifest-cinemeta.json"))
                    request.url.toString().contains("/manifest.json") ->
                        FakeAnswer(body = fixture("manifest-aiostreams.json"))
                    else -> FakeAnswer(body = streamBody("Same", "f".repeat(40)))
                }
            }
            val client = StremioClient(http.client)
            val registry = AddonRegistry(document, client, InMemoryAddonHealthStore())
            registry.refresh()
            StreamSearch(registry, client).search("movie", "tt1375666").toList()
        }
        assertEquals(1, states.last().rows.size)
    }

    @Test
    fun `compatible aliases find different providers without calling incompatible routes`() = runTest {
        val (last, urls) = withContext(Dispatchers.IO) {
            val http = FakeHttp { request ->
                val path = request.url.encodedPath
                when {
                    path == "/manifest.json" -> FakeAnswer(body = when (request.url.host) {
                        "fast.example.com" -> """{"id":"a","types":["movie"],"resources":[{"name":"stream","types":["series"],"idPrefixes":["tt"]}]}"""
                        "slow.example.com" -> """{"id":"b","resources":[{"name":"stream","types":["series"],"idPrefixes":["tmdb:"]}]}"""
                        else -> """{"id":"c","resources":["meta"]}"""
                    })
                    path.endsWith("tmdb:42:2:3.json") -> FakeAnswer(body = streamBody("TMDB", "a".repeat(40)))
                    path.endsWith("tt42:2:3.json") -> FakeAnswer(body = streamBody("IMDb", "b".repeat(40)))
                    else -> FakeAnswer(code = 404)
                }
            }
            val client = StremioClient(http.client)
            val registry = AddonRegistry(document, client).also { it.refresh() }
            val result = StreamSearch(registry, client)
                .search("series", listOf("tt42:2:3", "tmdb:42:2:3")).toList().last()
            result to http.urlsContaining("/stream/").toSet()
        }
        assertEquals(setOf("IMDb", "TMDB"), last.rows.map(StreamRow::title).toSet())
        assertEquals(2, last.attempted)
        assertEquals(setOf(
            "https://fast.example.com/stream/series/tt42:2:3.json",
            "https://slow.example.com/stream/series/tmdb:42:2:3.json",
        ), urls)
    }

    @Test
    fun `unhealthy enabled add-on recovers and malformed response counts as failure`() = runTest {
        val last = withContext(Dispatchers.IO) {
            val health = InMemoryAddonHealthStore()
            val http = FakeHttp { request ->
                when {
                    request.url.encodedPath == "/manifest.json" -> FakeAnswer(code = 503)
                    request.url.host.startsWith("fast") && request.url.encodedPath.endsWith("tt42.json") ->
                        FakeAnswer(code = 404)
                    request.url.host.startsWith("fast") ->
                        FakeAnswer(body = streamBody("Recovered", "a".repeat(40)))
                    request.url.host.startsWith("slow") ->
                        FakeAnswer(body = "<html>bad gateway</html>")
                    else -> FakeAnswer(body = """{"streams":[]}""")
                }
            }
            val client = StremioClient(http.client)
            val registry = AddonRegistry(document, client, health)
            repeat(3) { registry.refresh() }
            assertTrue(!registry.addons.value.first { it.name == "Fast" }.health.healthy)
            val result = StreamSearch(registry, client)
                .search("movie", listOf("tt42", "tmdb:42")).toList().last()
            assertTrue(registry.addons.value.first { it.name == "Fast" }.health.healthy)
            result
        }
        assertEquals("Recovered", last.rows.single().title)
        assertEquals(1, last.failed)
    }

    @Test
    fun `same torrent has stable identity but alternate direct links and required headers survive`() = runTest {
        val states = withContext(Dispatchers.IO) {
            val hash = "a".repeat(40)
            val http = FakeHttp { request ->
                when {
                    request.url.host.startsWith("fast") -> FakeAnswer(
                        body = """{"streams":[{"name":"Preferred","infoHash":"$hash"}]}""", delayMillis = 250,
                    )
                    request.url.host.startsWith("slow") -> FakeAnswer(body = """
                        {"streams":[{"name":"Duplicate","infoHash":"$hash"},
                        {"name":"Direct","infoHash":"$hash","url":"https://cdn.example/video.mkv"},
                        {"name":"Protected","url":"https://cdn.example/video.mkv",
                        "behaviorHints":{"notWebReady":true,"proxyHeaders":{"request":{"Referer":"https://test.example"}}}}]}
                    """.trimIndent())
                    else -> FakeAnswer(body = """{"streams":[]}""")
                }
            }
            val client = StremioClient(http.client)
            val inline = StreamRow("inline", "Catalog", "Embedded", "Embedded", url = "https://cdn.example/embedded.mkv")
            StreamSearch(AddonRegistry(document, client), client)
                .search("movie", listOf("tt42"), inlineRows = listOf(inline)).toList()
        }
        assertEquals("Embedded", states.first().rows.single().title)
        val first = states.flatMap { it.rows }.first { it.title == "Duplicate" }
        val last = states.last()
        assertEquals(4, last.rows.size)
        assertEquals(first.id, last.rows.first { it.title == "Preferred" }.id)
        assertEquals(2, last.rows.count { it.url == "https://cdn.example/video.mkv" })
        assertEquals("https://test.example", last.rows.first { it.title == "Protected" }.headers["Referer"])
        assertTrue(last.rows.none { it.id.contains("cdn.example") })
    }
}
