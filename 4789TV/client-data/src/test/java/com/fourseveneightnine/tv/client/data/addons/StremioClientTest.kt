package com.fourseveneightnine.tv.client.data.addons

import com.fourseveneightnine.tv.client.data.FakeAnswer
import com.fourseveneightnine.tv.client.data.FakeHttp
import com.fourseveneightnine.tv.client.data.fixture
import com.fourseveneightnine.tv.client.data.streams.HdrFormat
import com.fourseveneightnine.tv.client.data.streams.StreamRow
import com.fourseveneightnine.tv.client.data.streams.SubtitleTrack
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StremioClientTest {

    @Test fun `benchmark events pair add-on outcomes without request URLs or title IDs`() = runTest {
        val http = FakeHttp { FakeAnswer(body = """{"streams":[]}""") }
        val events = mutableListOf<Pair<String, String>>()
        val client = StremioClient(http.client, benchmark = { stage, detail -> events += stage to detail })

        assertTrue(client.streams(addon, "movie", "tt1234567").isEmpty())
        assertEquals(listOf("addon.start", "addon.end"), events.map { it.first })
        assertTrue(events[0].second.contains("op=stream name=AIOStreams"))
        assertTrue(events[1].second.contains("op=stream result=empty rows=0"))
        assertEquals(events[0].second.substringBefore(' '), events[1].second.substringBefore(' '))
        assertTrue(events.none { (_, fields) -> "SECRETKEY" in fields || "tt1234567" in fields })
    }

    @Test fun `HTTP body byte ceiling preserves full UTF-8 text at the limit`() = runTest {
        val body = "a".repeat(8_191) + "தமிழ்🎬"
        val http = FakeHttp { FakeAnswer(body = body) }
        val url = "https://addon.example/data"
        val limit = body.encodeToByteArray().size
        assertEquals(body, http.client.getText(url, limit).body)
        val request = okhttp3.Request.Builder().url(url).build()
        assertEquals(body, http.client.send(request, limit).body)
        assertEquals("response_too_large",
            runCatching { http.client.send(request, limit - 1) }.exceptionOrNull()?.message)
    }

    @Test fun `automatic subtitle fallback ranks the matching release and excludes forced cuts`() = runTest {
        val http = FakeHttp { FakeAnswer(body = """{"subtitles":[
            {"id":"unmatched","lang":"en","title":"Show.S02E04.BluRay-OTHER","url":"https://subs.example/1.srt"},
            {"id":"flagged","lang":"en","forced":true,"title":"Show.S02E04.1080p.NF.WEB-DL-GRP","url":"https://subs.example/2.srt"},
            {"id":"labeled","lang":"en","title":"Show.S02E04.1080p.NF.WEB-DL-GRP.forced","url":"https://subs.example/3.srt"},
            {"id":"filename","lang":"en","url":"https://subs.example/English%20Forced.srt"},
            {"id":"sdh","lang":"English SDH","title":"Show.S02E04.1080p.NF.WEB-DL-GRP","url":"https://subs.example/4.srt"}
        ]}""") }
        val hits = StremioClient(http.client).subtitles(addon, "series", "tt1234567:2:4")
        assertEquals(5, hits.size)
        assertEquals(listOf("sdh", "unmatched"),
            SubtitleTrack.rankedForPlayback(hits, "Show.S02E04.1080p.NF.WEB-DL-GRP.mkv").map { it.id })
    }

    @Test fun `subtitle search keeps English aliases and regional tags after other languages fill the first page`() = runTest {
        val entries = (List(101) { "tam" } + listOf("eng", "en", "en-US", "English", "und", "spa"))
            .mapIndexed { index, language ->
                """{"id":"$index","lang":"$language","url":"https://subs.example/$index.srt"}"""
            }.joinToString(",")
        val http = FakeHttp { FakeAnswer(body = """{"subtitles":[$entries]}""") }
        val tracks = StremioClient(http.client).subtitles(addon, "movie", "tt1234567")
        assertEquals(listOf("eng", "en", "en-US", "English"), tracks.map { it.language })
    }

    @Test fun `subtitle requests preserve credentials and encode only known file facts`() = runTest {
        val http = FakeHttp { FakeAnswer(body = """{"subtitles":[
            {"id":"one","lang":"eng","url":"https://subs.example/one.srt"},
            {"id":"bad","lang":"tam","url":"file:///private/subtitle.srt"}
        ]}""") }
        val source = addon.copy(manifestURL = "https://addon.example/SECRET/manifest.json?token=KEY")
        val tracks = StremioClient(http.client).subtitles(source, "series", "tt1234567:2:4",
            SubtitleExtra("/media/Show S02E04 & #1.mkv", "0123456789ABCDEF", 123456L))
        val url = http.requests.single().url
        assertEquals("/SECRET/subtitles/series/tt1234567:2:4/videoHash=0123456789abcdef&videoSize=123456&filename=Show%20S02E04%20%26%20%231.mkv.json", url.encodedPath)
        assertEquals("KEY", url.queryParameter("token"))
        assertEquals(1, tracks.size)
        assertEquals("eng", tracks.single().language)
        assertEquals(source.displayName, tracks.single().addonName)
        assertEquals("https://addon.example/subtitles/movie/tt1234567.json",
            AddonEndpoint.subtitlesURL("https://addon.example/manifest.json", "movie", "tt1234567",
                SubtitleExtra("https://video.example/SECRET", "a".repeat(40), -1)))
    }

    @Test fun `a malformed subtitle response fails instead of looking like an empty search`() = runTest {
        for (body in listOf("bad JSON", "{}", """{"subtitles":{}}""")) {
            val http = FakeHttp { FakeAnswer(body = body) }
            assertTrue(runCatching { StremioClient(http.client).subtitles(addon, "movie", "tt1234567") }.isFailure)
        }
        val http = FakeHttp { FakeAnswer(body = """{"subtitles":[]}""") }
        assertTrue(StremioClient(http.client).subtitles(addon, "movie", "tt1234567").isEmpty())
    }

    @Test fun `catalog pages reuse parsed metadata across screens and process restart`() = runTest {
        val directory = Files.createTempDirectory("catalog-cache").toFile()
        try {
            val http = FakeHttp { FakeAnswer(body = """{"metas":[{"id":"tt42","type":"movie","name":"Saved title",
                "poster":"https://images.example/poster.jpg","streams":[{"url":"https://secret.example/PLAY_NOW"}]}]}""") }
            val first = StremioClient(http.client, directory)
            val page = first.catalog(addon, "movie", "top")
            assertEquals(page, first.catalog(addon, "movie", "top"))
            assertEquals(page, StremioClient(http.client, directory).catalog(addon, "movie", "top"))
            assertEquals(1, http.requests.size)
            val files = directory.resolve("catalogs-v1").listFiles().orEmpty()
            assertEquals(1, files.size)
            assertTrue(files.single().name.matches(Regex("[a-f0-9]{64}")))
            assertFalse(files.single().readText().contains("PLAY_NOW"))
            assertFalse(files.single().readText().contains("SECRETKEY"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun `expired catalog paints saved data before a slow refresh and survives refresh failure`() = runTest {
        var now = 1_000L
        var fail = false
        var previewed = false
        val http = FakeHttp {
            if (fail) FakeAnswer(code = 503) else FakeAnswer(body = fixture("catalog-page.json"))
        }
        val client = StremioClient(http.client, clock = { now })
        val saved = client.catalog(addon, "movie", "top")
        now += StremioClient.CATALOG_TTL_MILLIS
        fail = true
        val fallback = client.catalog(addon, "movie", "top", onCached = {
            assertEquals(saved, it)
            assertEquals(1, http.requests.size) // No refresh request has started yet.
            previewed = true
        })
        assertTrue(previewed)
        assertEquals(saved, fallback)
        assertEquals(2, http.requests.size)
        now += StremioClient.CATALOG_STALE_MILLIS
        assertTrue(runCatching { client.catalog(addon, "movie", "top") }.isFailure)
    }

    @Test fun `catalog cache separates accounts filters pages and searches and manual refresh invalidates it`() = runTest {
        val http = FakeHttp { FakeAnswer(body = fixture("catalog-page.json")) }
        val client = StremioClient(http.client)
        client.catalog(addon, "movie", "top")
        client.catalog(addon, "movie", "top", CatalogExtra(skip = 20))
        client.catalog(addon, "movie", "top", CatalogExtra(genre = "Drama"))
        client.catalog(addon.copy(manifestURL = "https://other.example/manifest.json"), "movie", "top")
        repeat(2) { client.catalog(addon, "movie", "top", CatalogExtra(search = "query")) }
        assertEquals(6, http.requests.size)
        client.clearCatalogCache()
        client.catalog(addon, "movie", "top")
        assertEquals(7, http.requests.size)
    }

    @Test fun `simultaneous catalog callers share one fetch`() = runTest {
        val http = FakeHttp { FakeAnswer(body = fixture("catalog-page.json"), delayMillis = 100) }
        val client = StremioClient(http.client)
        val pages = (1..8).map { async(Dispatchers.Default) { client.catalog(addon, "movie", "top") } }.awaitAll()
        assertTrue(pages.all { it == pages.first() })
        assertEquals(1, http.requests.size)
    }

    @Test fun `catalog refresh cannot be overwritten by an older request`() = kotlinx.coroutines.runBlocking {
        val directory = Files.createTempDirectory("catalog-generation").toFile()
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val first = java.util.concurrent.atomic.AtomicBoolean(true)
        val http = FakeHttp {
            val old = first.compareAndSet(true, false)
            if (old) { started.countDown(); release.await(3, java.util.concurrent.TimeUnit.SECONDS) }
            FakeAnswer(body = """{"metas":[{"id":"tt42","name":"${if (old) "Old" else "Fresh"}"}]}""")
        }
        val client = StremioClient(http.client, directory)
        val owner = async(Dispatchers.Default) { client.catalog(addon, "movie", "top") }
        try {
            assertTrue(started.await(3, java.util.concurrent.TimeUnit.SECONDS))
            client.clearCatalogCache()
            assertEquals("Fresh", client.catalog(addon, "movie", "top").items.single().title)
            release.countDown()
            owner.await()
            assertEquals("Fresh", client.catalog(addon, "movie", "top").items.single().title)
            assertEquals("Fresh", StremioClient(http.client, directory).catalog(addon, "movie", "top").items.single().title)
            assertEquals(2, http.requests.size)
        } finally { release.countDown(); owner.cancel(); directory.deleteRecursively() }
    }

    @Test fun `a cancelled catalog owner does not cancel another screen waiting for its page`() = kotlinx.coroutines.runBlocking {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val first = java.util.concurrent.atomic.AtomicBoolean(true)
        val http = FakeHttp {
            if (first.compareAndSet(true, false)) {
                started.countDown()
                release.await(3, java.util.concurrent.TimeUnit.SECONDS)
            }
            FakeAnswer(body = fixture("catalog-page.json"))
        }
        val client = StremioClient(http.client)
        val owner = async(Dispatchers.Default) { client.catalog(addon, "movie", "top") }
        try {
            assertTrue(started.await(3, java.util.concurrent.TimeUnit.SECONDS))
            val detail = async(Dispatchers.Default) { client.catalog(addon, "movie", "top") }
            owner.cancel()
            release.countDown()
            owner.join()
            assertTrue(kotlinx.coroutines.withTimeout(3_000) { detail.await() }.items.isNotEmpty())
        } finally { release.countDown(); owner.cancel() }
    }

    @Test fun `DMM soft line wrapping preserves the complete filename for signed link renewal`() = runTest {
        val filename = "Heart.Of.The.Beast.Nel.Profondo.Selvaggio.2026.iTA-ENG.WEBDL.2160p.HEVC.HDR.x265-CYBER.mkv"
        val display = kotlinx.serialization.json.JsonPrimitive("Heart.Of.The.Beast.Nel.Profondo.Selvaggio.202-\n6.iTA-ENG.WEBDL.2160p.HEVC.HDR.x265-CYBER.mkv\nDD+ 5.1\n🎬 DMM Cast RD")
        val body = """{"streams":[
            {"url":"https://debridmediamanager.com/api/stremio/profile/play/NEW","title":$display},
            {"url":"https://elsewhere.example/file.mkv","title":$display}]}"""
        val rows = StremioClient(FakeHttp { FakeAnswer(body = body) }.client).streams(addon, "movie", "tt7526136")
        assertEquals(filename, rows.first().filename)
        assertEquals(filename, rows.first().releaseName)
        assertNull(rows.last().filename)
    }

    @Test
    fun `abbreviated aggregator filename remains useful display metadata without asserting an exact file`() = runTest {
        val body = """{"streams":[{"url":"https://cdn.example/file.mkv","name":"4K",
            "behaviorHints":{"filename":"The.Bear.S02E04.2160p.-","videoSize":4000000000}}]}"""
        val row = StremioClient(FakeHttp { FakeAnswer(body = body) }.client).streams(addon, "series", "tt1:2:4").single()
        assertNull(row.filename)
        assertEquals("The.Bear.S02E04.2160p.-", row.releaseName)
        assertEquals("4K", row.quality)
    }
    @Test
    fun `structured addon file metadata is retained and subtitles never become audio evidence`() = runTest {
        val body = """{"streams":[{"url":"https://cdn.example/file.mkv","name":"4K",
            "description":"🎧 Audio: English, Hindi\nSubtitles: French",
            "behaviorHints":{"filename":"Show.S01E02.2160p.10bit.HEVC.TrueHD.Atmos.7.1-GRP.mkv",
                "videoSize":4000000000,"audioCodec":["E-AC-3","TrueHD"],"audioLanguages":["eng","hin"],
                "duration":3226,"publishDate":"2025-10-27T01:33:51Z","folderName":"Show.Season.1",
                "folderSize":30000000000,"seeders":7,"videoHash":"0123456789abcdef","fps":23.976},
            "subtitles":[{"lang":"fra","url":"https://subs.example/private.vtt"}]}]}"""
        val row = StremioClient(FakeHttp { FakeAnswer(body = body) }.client).streams(addon, "series", "tt1:1:2").single()
        val f = row.facts
        assertEquals(4_000_000_000L, row.sizeBytes)
        assertEquals(7, row.seeders)
        assertEquals(3226L, f.durationSeconds)
        assertEquals(30_000_000_000L, f.folderSizeBytes)
        assertEquals("Show.Season.1", f.folderName)
        assertEquals("MKV", f.container)
        assertEquals(10, f.bitDepth)
        assertEquals(23.976, requireNotNull(f.frameRate), 0.001)
        assertEquals("0123456789abcdef", f.videoHash)
        assertTrue(f.audioCodecs.containsAll(listOf("TrueHD", "E-AC-3")))
        assertEquals("Atmos", f.audio)
        assertTrue(f.reportedAudioLanguages.containsAll(listOf("English", "Hindi")))
        assertFalse(f.reportedAudioLanguages.contains("French"))
        assertEquals(listOf("French"), f.subtitleLanguages)
        assertEquals(java.time.Instant.parse("2025-10-27T01:33:51Z").toEpochMilli(), f.sourceDates["Published"])
        val details = com.fourseveneightnine.tv.client.data.streams.StreamRanker.facts(row)
        for (label in listOf("Audio codecs", "Subtitle languages", "Published", "Duration", "Pack size", "Bit depth", "Frame rate")) {
            assertTrue(label, details.any { it.first == label })
        }
        assertFalse(details.any { it.second.contains("private.vtt") })
    }


    @Test
    fun `DMM TorBox modern play queries preserve the fallback hash only on its known route`() = runTest {
        val hash = "0123456789abcdef0123456789abcdef01234567"
        val urls = listOf(
            "https://debridmediamanager.com/api/stremio-tb/profile/play/123:4?h=$hash&file=The.Bear.S02E04.mkv",
            "https://debridmediamanager.com/api/stremio-tb/profile/play/$hash",
            "https://elsewhere.example/api/stremio-tb/profile/play/123:4?h=$hash",
            "https://debridmediamanager.com/api/stremio/profile/play/123:4?h=$hash",
            "https://debridmediamanager.com/api/stremio-tb/profile/play/123:4?h=not-a-hash",
        )
        val body = "{\"streams\":[" + urls.joinToString(",") { "{\"url\":\"$it\",\"title\":\"The.Bear.S02E04.1080p.mkv\"}" } + "]}"
        val rows = StremioClient(FakeHttp { FakeAnswer(body = body) }.client).streams(addon, "series", "tt1:2:4")
        assertEquals(listOf(hash, hash, null, null, null), rows.map { it.cacheHash })
        assertTrue(rows.all { it.infoHash == null })
        assertEquals("The.Bear.S02E04.mkv", rows.first().filename)
        assertEquals("The.Bear.S02E04.mkv", rows.first().releaseName)
        assertTrue(rows.drop(1).all { it.filename == null })
    }
    @Test
    fun `HDHub description release is retained without stripping a real bracketed title`() = runTest {
        val body = """{"streams":[
            {"url":"https://cdn.example/one.mkv","name":"4KHDHub 1080p",
             "description":"[PixelDrain] [💾 3.29 GB] Inception.2010.1080p.WEB-DL.mkv\nEnglish"},
            {"url":"https://cdn.example/two.mkv","name":"HdHub 1080p",
             "description":"[REC] 2007 1080p BluRay.mkv"}]}"""
        val rows = StremioClient(FakeHttp { FakeAnswer(body = body) }.client).streams(addon, "movie", "tt1234567")
        assertTrue(rows.first().releaseName.startsWith("Inception.2010"))
        assertTrue(com.fourseveneightnine.tv.client.data.streams.StreamExpectation("Inception", 2010, null, null, true).matches(rows.first()))
        assertTrue(rows.last().releaseName.startsWith("[REC]"))
    }

    @Test
    fun `explicit audio language hints are retained instead of losing original audio evidence`() = runTest {
        val body = """{"streams":[{"url":"https://cdn.example/movie.mkv","name":"English label",
            "behaviorHints":{"filename":"Movie.2024.1080p.mkv","audioLanguages":["kor"]}}]}"""
        val row = StremioClient(FakeHttp { FakeAnswer(body = body) }.client).streams(addon, "movie", "tt1234567").single()
        assertEquals(listOf("Korean"), row.facts.reportedAudioLanguages)
        assertTrue(com.fourseveneightnine.tv.client.data.streams.OriginalAudioLanguage.matches(row, "ko"))
        assertFalse(com.fourseveneightnine.tv.client.data.streams.OriginalAudioLanguage.matches(row, "en"))
    }


    private val addon = Addon("AIOStreams", "https://aio.example.com/stremio/SECRETKEY/manifest.json")

    @Test fun `catalog metadata keeps all genres and parses country year runtime and rating`() = runTest {
        val body = """{"meta":{"id":"tt12345","type":"movie","name":"Fixture",
            "releaseInfo":"1999-2005","genres":["Action","Thriller","Horror"],
            "country":"USA, India","runtime":"120 min","imdbRating":"8.5","original_language":"ta"}}"""
        val meta = requireNotNull(StremioClient(FakeHttp { FakeAnswer(body = body) }.client).meta(addon, "movie", "tt12345"))
        assertEquals(1999, meta.year)
        assertEquals(listOf("Action", "Thriller", "Horror"), meta.genres)
        assertEquals(listOf("USA", "India"), meta.countries)
        assertEquals("ta", meta.originalLanguage)
        assertEquals(120, meta.runtimeMinutes)
        assertEquals(8.5, requireNotNull(meta.imdbRating), 0.001)
    }

    @Test
    fun `native streams survive notWebReady and retain required headers`() = runTest {
        val body = """{"streams":[
            {"url":"https://cdn.example/video.mkv","title":"Movie.2024.1080p",
             "behaviorHints":{"notWebReady":true,"proxyHeaders":{"request":{"Referer":"https://origin.example/","User-Agent":"NativePlayer"}}}},
            {"url":"https://cdn.example/plain.mkv","title":"Movie.2024.720p",
             "behaviorHints":{"notWebReady":true}},
            {"url":"https://cdn.example/assets/stream-errors/no-stream-found.mp4",
             "behaviorHints":{"notWebReady":true}}
        ]}"""
        val rows = StremioClient(FakeHttp { FakeAnswer(body = body) }.client)
            .streams(addon, "movie", "tt1234567")
        assertEquals(2, rows.size)
        assertEquals("https://origin.example/", rows.first().headers["Referer"])
        assertEquals("NativePlayer", rows.first().headers["User-Agent"])
        assertTrue(rows.last().headers.isEmpty())
    }

    @Test
    fun `library movie preserves its file video and embedded stream instead of IMDb`() = runTest {
        val body = """{"meta":{"id":"library:42","type":"movie","name":"Saved movie","imdb_id":"tt1375666",
            "videos":[{"id":"library:42:9","title":"Exact file","streams":[{"url":"https://cdn.example/exact.mkv"}]}]}}"""
        val meta = requireNotNull(StremioClient(FakeHttp { FakeAnswer(body = body) }.client)
            .meta(addon, "movie", "library:42"))
        assertEquals("library:42:9", meta.defaultVideoID)
        assertEquals("https://cdn.example/exact.mkv", meta.streamsFor(null, null)?.single()?.url)
        assertTrue(meta.hasInlineStreams)
        val disk = kotlinx.serialization.json.Json.encodeToString(com.fourseveneightnine.tv.client.data.meta.Meta.serializer(), meta)
        assertFalse(disk.contains("exact.mkv"))
        assertTrue(disk.contains("hasInlineStreams"))
    }

    @Test
    fun `embedded episode streams stay bound to the selected episode`() = runTest {
        val body = """{"meta":{"id":"library:series","type":"series","name":"Saved show","videos":[
            {"id":"file:8","season":2,"episode":3,"streams":[{"infoHash":"0123456789abcdef0123456789abcdef01234567","fileIdx":8}]},
            {"id":"file:9","season":2,"episode":4,"streams":[]}]}}"""
        val meta = requireNotNull(StremioClient(FakeHttp { FakeAnswer(body = body) }.client)
            .meta(addon, "series", "library:series"))
        assertEquals(8, meta.streamsFor(2, 3)?.single()?.fileIdx)
        assertEquals(emptyList<StreamRow>(), meta.streamsFor(2, 4))
        assertNull(meta.streamsFor(2, 5))
    }

    @Test
    fun `only original language metadata identifies a titles original audio`() = runTest {
        val client = StremioClient(FakeHttp { request ->
            val fields = if (request.url.toString().contains("/known"))
                "\"original_language\":\"Telugu\"" else "\"language\":\"English\",\"country\":\"India\""
            FakeAnswer(body = "{\"meta\":{\"id\":\"test\",\"type\":\"movie\",\"name\":\"Film\",$fields}}")
        }.client)
        assertEquals("te", client.meta(addon, "movie", "known")?.originalLanguage)
        assertNull(client.meta(addon, "movie", "unknown")?.originalLanguage)
    }

    @Test fun `unfamiliar mixed addon classifies each transport and preserves explicit cache misses`() = runTest {
        val hash = "0123456789abcdef0123456789abcdef01234567"
        val body = """{"streams":[
            {"url":"https://cdn.example/film.m3u8","behaviorHints":{"proxyHeaders":{"request":{"Referer":"https://cinema.example"}}}},
            {"url":"magnet:?xt=urn:btih:$hash"},
            {"url":"https://plugin.example/file","behaviorHints":{"debridService":"TorBox","cached":true}},
            {"url":"https://plugin.example/two","behaviorHints":{"debridService":"TorBox","cached":false}}
        ]}"""
        val client = StremioClient(FakeHttp { FakeAnswer(body = body) }.client)
        val rows = client.streams(addon.copy(name = "New mix"), "movie", "tt1")
        assertEquals(4, rows.size)
        assertTrue(rows[0].directHttp)
        assertEquals(hash, rows[1].infoHash)
        assertFalse(rows[1].readyHint)
        assertTrue(rows[2].cachedDebrid)
        assertFalse(rows[3].readyHint)
        assertFalse(client.streams(addon.copy(name = "Meteor"), "movie", "tt1")[3].readyHint)
    }

    @Test fun `structured service aliases preserve matching text hints but override contradictory hints`() = runTest {
        val cases = listOf(
            Triple("[TB+] 1080p", " torbox ", "TorBox"),
            Triple("[RD+] 1080p", "realdebrid", "Real-Debrid"),
            Triple("[RD+] 1080p", "REAL DEBRID", "Real-Debrid"),
            Triple("[RD+] 1080p", "TorBox", "TorBox"),
            Triple("[RD+] 1080p", "CustomCloud", "CustomCloud"),
        )
        for ((label, structured, expected) in cases) {
            val body = """{"streams":[{"name":"$label","url":"https://cdn.example/file.mkv",
                "behaviorHints":{"debridService":"$structured","cached":true}}]}"""
            val row = StremioClient(FakeHttp { FakeAnswer(body = body) }.client)
                .streams(addon.copy(name = "New mix"), "movie", "tt1").single()
            assertEquals(expected, row.cachedHint.service)
            assertEquals(expected, row.deliveryService)
            assertTrue(row.cachedDebrid && row.readyHint)
            assertFalse(row.directHttp)
        }
    }

    @Test
    fun `a direct link stays ready without inventing a cache report`() = runTest {
        val http = FakeHttp { FakeAnswer(body = """{"streams":[{"name":"Film 1080p ENG","url":"https://cdn.example/movie.mkv"}]}""") }
        val row = StremioClient(http.client).streams(addon.copy(name = "Penguin Play"), "movie", "tt1").single()
        assertFalse(row.cachedHint.cached)
        assertTrue(row.readyHint)
        assertTrue(com.fourseveneightnine.tv.client.data.streams.PlaybackRules(readyOnly = true).allows(row))
    }

    @Test
    fun `manifest parses resources and catalog extras`() = runTest {
        val http = FakeHttp { FakeAnswer(body = fixture("manifest-aiostreams.json")) }
        val manifest = StremioClient(http.client).manifest(addon.manifestURL)
        assertEquals("AIOStreams", manifest.name)
        assertEquals(listOf("stream", "meta", "catalog", "subtitles"), manifest.resources)
        assertEquals(2, manifest.catalogs.size)
        val trending = manifest.catalogs.first()
        assertTrue(trending.extraSupported.containsAll(listOf("skip", "genre", "search")))
        assertEquals(listOf("Action", "Drama", "Comedy"), trending.genres)
    }

    @Test
    fun `a manifest URL keeps its credential path and gains manifest json only once`() {
        assertEquals(
            "https://aio.example.com/stremio/SECRETKEY/manifest.json",
            AddonEndpoint.manifestURL("https://aio.example.com/stremio/SECRETKEY/manifest.json"),
        )
        assertEquals(
            "https://aio.example.com/stremio/SECRETKEY/manifest.json",
            AddonEndpoint.manifestURL("https://aio.example.com/stremio/SECRETKEY/"),
        )
    }

    @Test
    fun `an add-on query survives every route, because for AIOStreams it is the configuration`() {
        val base = "https://torrentio.strem.fun/manifest.json?providers=yts"
        assertEquals(
            "https://torrentio.strem.fun/stream/movie/tt1375666.json?providers=yts",
            AddonEndpoint.streamURL(base, "movie", "tt1375666"),
        )
    }

    @Test
    fun `catalog paging writes Stremio's extra segment`() = runTest {
        val http = FakeHttp { FakeAnswer(body = fixture("catalog-page.json")) }
        val client = StremioClient(http.client)
        client.catalog(addon, "movie", "aio.trending", CatalogExtra(skip = 40, genre = "Sci-Fi"))
        val url = http.urls.single()
        assertTrue(url, url.contains("/catalog/movie/aio.trending/"))
        assertTrue(url, url.contains("genre=Sci-Fi"))
        assertTrue(url, url.contains("skip=40"))
        assertTrue(url, url.endsWith(".json"))
    }

    @Test
    fun `a catalog with no extras has no extra segment`() = runTest {
        val http = FakeHttp { FakeAnswer(body = fixture("catalog-page.json")) }
        StremioClient(http.client).catalog(addon, "movie", "aio.trending")
        assertTrue(http.urls.single().endsWith("/catalog/movie/aio.trending.json"))
    }

    @Test
    fun `a search term is URL-encoded into the extra segment`() = runTest {
        val http = FakeHttp { FakeAnswer(body = fixture("catalog-page.json")) }
        StremioClient(http.client).catalog(addon, "movie", "aio.trending", CatalogExtra(search = "the bear"))
        assertTrue(http.urls.single().contains("search=the%20bear"))
    }

    @Test
    fun `only public Cinemeta browse requests use the separate catalog host`() = runTest {
        val http = FakeHttp { FakeAnswer(body = """{"metas":[]}""") }
        val client = StremioClient(http.client)
        val cinemeta = Addon("Cinemeta", StremioClient.CINEMETA_MANIFEST)
        client.catalog(cinemeta, "movie", "top", CatalogExtra(skip = 50))
        client.catalog(cinemeta, "series", "year", CatalogExtra(genre = "Action"))
        client.catalog(cinemeta, "movie", "imdbRating")
        client.catalog(cinemeta, "movie", "top", CatalogExtra(search = "Dune"))
        client.catalog(cinemeta, "series", "last-videos")
        client.catalog(cinemeta.copy(manifestURL = cinemeta.manifestURL + "?key=private"), "movie", "top")
        assertEquals(listOf(
            "https://cinemeta-catalogs.strem.io/top/catalog/movie/top/skip=50.json",
            "https://cinemeta-catalogs.strem.io/year/catalog/series/year/genre=Action.json",
            "https://cinemeta-catalogs.strem.io/imdbRating/catalog/movie/imdbRating.json",
            "https://v3-cinemeta.strem.io/catalog/movie/top/search=Dune.json",
            "https://v3-cinemeta.strem.io/catalog/series/last-videos.json",
            "https://v3-cinemeta.strem.io/catalog/movie/top.json?key=private",
        ), http.urls)
    }

    @Test
    fun `a valid empty catalog is distinct from malformed catalog data`() = runTest {
        val http = FakeHttp { request ->
            val body = when (request.url.encodedPath.substringAfterLast('/')) {
                "empty.json" -> """{"metas":[]}"""
                "invalid.json" -> """{"metas":[{"name":"No ID"}]}"""
                "series.json" -> """{"metas":[{"id":"tt42","name":"Series"}]}"""
                else -> """{"error":"access denied"}"""
            }
            FakeAnswer(body = body)
        }
        val client = StremioClient(http.client)
        assertTrue(client.catalog(addon, "series", "empty").items.isEmpty())
        assertEquals("series", client.catalog(addon, "series", "series").items.single().type)
        for (id in listOf("invalid", "error")) {
            assertTrue(runCatching { client.catalog(addon, "series", id) }
                .exceptionOrNull() is CatalogResponseException)
        }
    }

    @Test
    fun `catalog rows map onto the frozen DiscoverItem and drop the ones with no id`() = runTest {
        val http = FakeHttp { FakeAnswer(body = fixture("catalog-page.json")) }
        val page = StremioClient(http.client).catalog(addon, "movie", "aio.trending")
        assertEquals(4, page.items.size)
        val first = page.items.first()
        assertEquals("tt1375666", first.id)
        assertEquals("Inception", first.title)
        assertEquals(2010, first.year)
        assertEquals(8.8, requireNotNull(first.rating), 0.001)
        assertEquals("aio.trending", first.catalogID)
    }

    @Test
    fun `a movie meta comes back whole`() = runTest {
        val http = FakeHttp { FakeAnswer(body = fixture("cinemeta-movie.json")) }
        val meta = requireNotNull(StremioClient(http.client).meta(addon, "movie", "tt1375666"))
        assertEquals("Inception", meta.title)
        assertEquals(2010, meta.year)
        assertEquals(148, meta.runtimeMinutes)
        assertEquals("YoHD9XEInc0", meta.trailerYouTubeID)
        assertEquals(listOf("Leonardo DiCaprio"), meta.cast.map { it.name })
        assertEquals(listOf("tt0816692", "tt0482571"), meta.similar.map { it.id })
        assertTrue(meta.videos.isEmpty())
    }

    @Test
    fun `a series meta carries its episodes and its specials`() = runTest {
        val http = FakeHttp { FakeAnswer(body = fixture("cinemeta-series.json")) }
        val meta = requireNotNull(StremioClient(http.client).meta(addon, "series", "tt7366338"))
        assertEquals(6, meta.videos.size)
        assertEquals(listOf(0, 1), meta.seasons)
        assertEquals(5, meta.episodes(1).size)
        val first = meta.episodes(1).first()
        assertEquals("1:23:45", first.title)
        assertEquals("tt7366338:1:1", first.id)
        assertEquals("2019-05-06T00:00:00.000Z", first.released)
        assertEquals("TV-MA", meta.certification)
    }

    @Test
    fun `stream rows keep both url and hash kinds and drop what cannot play`() = runTest {
        val http = FakeHttp { FakeAnswer(body = fixture("streams-mixed.json")) }
        val rows = StremioClient(http.client).streams(addon, "series", "tt14452776:2:4")
        // Eight rows in the fixture; the id-less one and the placeholder MP4 are not rows.
        assertEquals(6, rows.size)
        assertTrue(rows.all(StreamRow::playable))
        assertEquals(2, rows.count { it.url != null })
        assertEquals(4, rows.count { it.infoHash != null })
        assertFalse(rows.any { it.url?.contains("stream-errors") == true })
    }

    @Test
    fun `a 4k DV row parses its quality, hdr, codec, size and cached marker`() = runTest {
        val http = FakeHttp { FakeAnswer(body = fixture("streams-mixed.json")) }
        val row = StremioClient(http.client).streams(addon, "series", "tt14452776:2:4").first()
        assertEquals("4K", row.quality)
        assertEquals(HdrFormat.DOLBY_VISION, row.hdr)
        assertTrue(row.codecs.contains("HEVC"))
        assertEquals(19779969843L, row.sizeBytes)
        assertTrue(row.cachedHint.cached)
        assertEquals("Real-Debrid", row.cachedHint.service)
        assertTrue(row.audioLanguages.contains("English"))
    }

    @Test
    fun `an uncached hash row keeps its trackers and drops the dht pseudo-source`() = runTest {
        val http = FakeHttp { FakeAnswer(body = fixture("streams-mixed.json")) }
        val rows = StremioClient(http.client).streams(addon, "series", "tt14452776:2:4")
        val remux = rows.first { it.infoHash == "c9e1585b1c4b0a1bb9a2e5a3d74c1f0ee2b34d55" }
        assertEquals(3, remux.fileIdx)
        assertEquals(41, remux.seeders)
        assertEquals(2, remux.sources.size)
    }

    @Test
    fun `a download-required marker beats a service tag`() = runTest {
        val http = FakeHttp { FakeAnswer(body = fixture("streams-mixed.json")) }
        val rows = StremioClient(http.client).streams(addon, "series", "tt14452776:2:4")
        val mediaFusion = rows.first { it.infoHash == "ffeeddccbbaa99887766554433221100ffeeddcc" }
        assertFalse(mediaFusion.cachedHint.cached)
    }

    @Test
    fun `an identifier that is not safe never becomes a URL`() {
        assertNull(AddonEndpoint.streamURL(addon.manifestURL, "movie", "../../etc/passwd"))
        assertNull(AddonEndpoint.streamURL("javascript:alert(1)", "movie", "tt1375666"))
        assertNull(AddonEndpoint.playableURL("file:///etc/passwd"))
        assertNotNull(AddonEndpoint.playableURL("https://host.example.com/a.mkv"))
    }

    @Test
    fun `episode identifiers take the season and episode Stremio expects`() {
        assertEquals("tt7366338:1:4", AddonEndpoint.episodeIdentifier("tt7366338", 1, 4))
        assertEquals("tt1375666", AddonEndpoint.episodeIdentifier("tt1375666", null, null))
    }

    @Test
    fun `a catalog tmdb id offers the compact form too`() {
        assertEquals(
            listOf("tt1375666", "tmdb:movie:27205", "tmdb:27205"),
            AddonEndpoint.identifiers("tamilmv::tmdb:movie:27205", "tt1375666", 27205),
        )
    }
}
