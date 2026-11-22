package com.fourseveneightnine.tv.client.data.streams

import com.fourseveneightnine.tv.client.data.FakeAnswer
import com.fourseveneightnine.tv.client.data.FakeHttp
import com.fourseveneightnine.tv.client.data.fixture
import com.fourseveneightnine.tv.client.data.streams.debrid.DebridFile
import com.fourseveneightnine.tv.client.data.streams.debrid.DebridFileSelection
import com.fourseveneightnine.tv.client.data.streams.debrid.DebridMagnetBuilder
import com.fourseveneightnine.tv.client.data.streams.debrid.DebridOutcome
import com.fourseveneightnine.tv.client.data.streams.debrid.DebridResolver
import com.fourseveneightnine.tv.client.data.streams.debrid.DebridSelectionHint
import com.fourseveneightnine.tv.client.data.streams.debrid.TorboxResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebridResolverTest {

    @Test fun `DMM anime filenames retain identity through release prefixes and first season episode labels`() = runTest { withContext(Dispatchers.IO) {
        val expected = StreamExpectation("Tokyo Revengers", 2021, 1, 1, false)
        val cases = listOf(
            Triple("pfanime-tokyo.revengers.s01e01.dubbed.1080p[eztv.re].mkv", expected, true),
            Triple("Tokyo 卍 Revengers - Episode 01 - Reborn 1080p BD-Rip x265 PCM 2.0 Kira [SEV].mkv", expected, true),
            Triple("Tokyo 卍 Revengers - Episode 01 - Reborn 1080p.mkv", expected.copy(season = 2), false),
            Triple("Tokyo 卍 Revengers - Episode 02 - 1080p.mkv", expected, false),
            Triple("Tokyo 卍 Revengers - Episode 01-02 - 1080p.mkv", expected, false),
            Triple("Tokyo 卍 Revengers - Season 2 Episode 01 - 1080p.mkv", expected, false),
            Triple("pfanime-tokyo.revengers.s01e02.1080p.mkv", expected, false),
            Triple("pfanime-another.show.s01e01.1080p.mkv", expected, false),
        )
        for ((filename, expectation, accepted) in cases) {
            val encodedFilename = java.net.URLEncoder.encode(filename, "UTF-8").replace("+", "%20")
            val http = FakeHttp { FakeAnswer(code = 206, body = "\u0000\u0000\u0000\u0018ftypisom", headers = mapOf(
                "Content-Type" to "application/force-download", "Content-Range" to "bytes 0-63/2959357656",
                "Content-Disposition" to "attachment; filename*=UTF-8''$encodedFilename")) }
            val row = directRow().copy(addonName = "DMM Cast for Real-Debrid", title = "1080p", releaseName = filename)
            assertEquals(filename, accepted, DebridResolver(null, null, http.client).inspect(row, expectation))
        }
        assertTrue(StreamExpectation("卍", 1964, null, null, true).matches(
            directRow().copy(filename = "卍.1964.1080p.mkv")))
    } }

    @Test fun `new verified title aliases immediately retire an earlier identity rejection`() = runTest { withContext(Dispatchers.IO) {
        val filename = "Doctor.X.Mafia.in.White.2026.S01E01.1080p.mkv"
        val expected = StreamExpectation("Doctor X: Age of the White Mafia", 2026, 1, 1, false, canonicalID = "tt39328047")
        val http = FakeHttp { FakeAnswer(code = 206, body = "\u0000\u0000\u0000\u0018ftypisom", headers = mapOf(
            "Content-Type" to "video/mp4", "Content-Range" to "bytes 0-63/2620228116",
            "Content-Disposition" to "attachment; filename=\"$filename\"")) }
        val row = directRow().copy(addonName = "DMM Cast for Real-Debrid", releaseName = filename, filename = null)
        val resolver = DebridResolver(null, null, http.client)
        assertEquals(false, resolver.inspect(row, expected))
        assertEquals(true, resolver.inspect(row, expected.copy(aliases = listOf("Doctor X: Mafia in White"))))
    } }

    @Test fun `Real Debrid response paths verify MediaFusion and restored movies without a filename header`() = runTest { withContext(Dispatchers.IO) {
        val file = "Home.Alone.2.Lost.in.New.York.1992.2160p.DV.HDR.HEVC.DTS-HD.MA.5.1-CiUHD.mkv"
        val expected = StreamExpectation("Home Alone 2: Lost in New York", 1992, null, null, true)
        for (addon in listOf("MediaFusion | ElfHosted", "Last played")) {
            for ((host, name, accepted) in listOf(
                Triple("dal1-4.download.real-debrid.com", file, true),
                Triple("dal1-4.download.real-debrid.com", file.replace("1992", "1993"), false),
                Triple("dal1-4.download.real-debrid.com", file.replace("Home.Alone.2.Lost.in.New.York", "Another.Movie"), false),
                Triple("dal1-4.download.real-debrid.com", "video.mkv", false),
                Triple("real-debrid.com.example", file, false),
            )) {
                val http = FakeHttp { FakeAnswer(code = 206, body = "\u0000\u0000\u0000\u0018ftypisom", headers = mapOf(
                    "Content-Type" to "application/force-download", "Content-Disposition" to "attachment",
                    "Content-Range" to "bytes 0-63/18342525636")) }
                val row = directRow().copy(addonName = addon, title = expected.title, releaseName = expected.title,
                    filename = null, identityNames = emptyList(), url = "https://$host/d/id/$name", expectation = expected)
                val resolver = DebridResolver(null, null, http.client)
                assertEquals("$addon $host $name", accepted, resolver.inspect(row, expected))
                if (accepted) {
                    val resolved = (resolver.resolve(row, null, null) as DebridOutcome.Success).resolved
                    assertEquals(file, resolved.filename)
                    assertEquals(18_342_525_636L, resolved.sizeBytes)
                    assertEquals(true, resolver.inspect(row, expected))
                    assertEquals(1, http.requests.size)
                }
            }
        }
    } }

    @Test fun `served movie identity tolerates addon filename spelling and extension differences`() = runTest { withContext(Dispatchers.IO) {
        val stem = "Home.Alone.2.Lost.In.New.York.1992.2160p.Ai-Upscaled.10Bit.H265.DTS-HD.MA.5.1-DirtyHippie.rife4.14v2-60fps"
        val expected = StreamExpectation("Home Alone 2: Lost in New York", 1992, null, null, true)
        for ((hint, actual, accepted) in listOf(
            Triple(stem, "$stem.mkv", true),
            Triple("$stem.mkv", "$stem.MKV", true),
            Triple(stem, "${stem.replace("1992", "1993")}.mkv", false),
            Triple(stem, "${stem.replace("Home.Alone.2.Lost.In.New.York", "Another.Movie")}.mkv", false),
            Triple(stem.replace('.', ' '), "$stem.mkv", true),
            Triple("$stem.mp4", "$stem.mkv", true),
        )) {
            val http = FakeHttp { FakeAnswer(code = 206, body = "\u0000\u0000\u0000\u0018ftypisom", headers = mapOf(
                "Content-Type" to "application/force-download", "Content-Range" to "bytes 0-63/21463398830",
                "Content-Disposition" to "attachment; filename=\"$actual\"")) }
            val row = directRow().copy(addonName = "MediaFusion | ElfHosted", title = expected.title,
                releaseName = hint, filename = hint)
            assertEquals("$hint -> $actual", accepted, DebridResolver(null, null, http.client).inspect(row, expected))
        }
    } }

    @Test fun `actual named movie files verify when addons advertise torrent names instead of filenames`() = runTest { withContext(Dispatchers.IO) {
        for ((title, year, hint, actual) in listOf(
            listOf("Malcolm X", "1992", "Malcolm_X_(1992)_MULTi_VFF_4K_Light_2160p_10bit_DV_HDR10Plus_BluRay_DTS-HD_MA_5 1 x265-M5 mkv",
                "Malcolm_X_(1992)_MULTi_VFF_4K_Light_2160p_10bit_DV_HDR10Plus_BluRay_DTS-HD_MA_5.1.x265-M5.mkv"),
            listOf("12 Years a Slave", "2013", "12 Years a Slave 2013 Upscaled BluRay 2160p HDR10 HEVC DTS-HD MA 5.1 x265-E", "12 Years a Slave 2013.mkv"),
            listOf("12 Years a Slave", "2013", "12 Years a Slave (2013) + Extras (1080p BluRay x265 ITA ENG MULTISUB) - GEGE",
                "12 Years a Slave (2013) (1080p BluRay x265 ITA ENG MULTISUB) - GEGE.mkv"),
        )) {
            for (addon in listOf("MediaFusion | ElfHosted TRB", "AIOStreams", "Meteor", "DMM Cast for Real-Debrid", "TorBox")) {
                val http = FakeHttp { FakeAnswer(code = 206, body = "\u0000\u0000\u0000\u0018ftypisom", headers = mapOf(
                    "Content-Type" to "application/octet-stream", "Content-Range" to "bytes 0-63/4000000000",
                    "Content-Disposition" to "attachment; filename=\"$actual\"")) }
                val row = directRow().copy(addonName = addon, title = title, releaseName = hint, filename = hint)
                assertEquals("$addon $title", true, DebridResolver(null, null, http.client).inspect(row,
                    StreamExpectation(title, year.toInt(), null, null, true)))
            }
        }
    } }

    @Test fun `TorBox JSON retains season paths while resolved filenames remain basenames`() = runTest {
        for (pathField in listOf("name", "absolute_path")) {
            val basename = if (pathField == "absolute_path") "\"name\":\"04.mkv\"," else ""
            val http = FakeHttp { request -> when (request.url.pathSegments.last()) {
                "mylist" -> FakeAnswer(body = """{"success":true,"data":{"hash":"$hash","name":"Show Complete","files":[
                    {"id":51,"short_name":"04.mkv",$basename"$pathField":"Show/Season 1/04.mkv","size":1000},
                    {"id":72,"short_name":"04.mkv",$basename"$pathField":"Show/Season 2/04.mkv","size":2000}]}}""")
                "requestdl" -> {
                    assertEquals("72", request.url.queryParameter("file_id"))
                    assertEquals("14", request.url.queryParameter("torrent_id"))
                    FakeAnswer(body = """{"success":true,"data":"https://cdn.example/04.mkv"}""")
                }
                else -> error("An existing cached torrent must not be acquired again")
            } }
            val row = hashRow().copy(releaseName = "Show Complete", fileIdx = null,
                exactSource = true, torboxTorrentID = 14)
            val outcome = TorboxResolver("key", http.client).resolve(row, 2, 4)
            assertTrue(pathField, outcome is DebridOutcome.Success)
            val resolved = (outcome as DebridOutcome.Success).resolved
            assertEquals("04.mkv", resolved.filename)
            assertEquals(2000L, resolved.sizeBytes)
            assertEquals(2, http.requests.size)
        }
    }

    @Test fun `TorBox JSON season folder rejects a conflicting explicit index`() = runTest {
        val http = FakeHttp { request -> when (request.url.pathSegments.last()) {
            "mylist" -> FakeAnswer(body = """{"success":true,"data":{"hash":"$hash","name":"Show Complete","files":[
                {"id":7,"short_name":"04.mkv","name":"Show/Season 2/04.mkv","size":2000}]}}""")
            "requestdl" -> FakeAnswer(body = """{"success":true,"data":"https://cdn.example/04.mkv"}""")
            else -> error("Unexpected acquisition")
        } }
        val row = hashRow().copy(releaseName = "Show Complete", fileIdx = 0,
            exactSource = true, torboxTorrentID = 14)
        assertEquals(DebridOutcome.Stale, TorboxResolver("key", http.client).resolve(row, 1, 4))
        assertEquals(1, http.requests.size)
    }

    @Test fun `first TamilMV movie and episode play recover the same cached file`() = runTest {
        withContext(Dispatchers.IO) {
            for (episode in listOf(null, 4)) {
                val name = if (episode == null) "Film.2026.1080p.WEB-DL.mkv" else "Film.S02E04.1080p.WEB-DL.mkv"
                var accountReads = 0
                var linkReads = 0
                val http = FakeHttp { request -> when (request.url.pathSegments.last()) {
                    "createtorrent" -> FakeAnswer(body = """{"success":true,"data":{"torrent_id":14}}""")
                    "mylist" -> if (accountReads++ == 0) FakeAnswer(code = 404) else FakeAnswer(
                        body = """{"success":true,"data":{"hash":"$hash","name":"$name","files":[{"id":7,"name":"$name","size":2000}]}}""")
                    "requestdl" -> {
                        assertEquals("14", request.url.queryParameter("torrent_id"))
                        assertEquals("7", request.url.queryParameter("file_id"))
                        if (linkReads++ == 0) FakeAnswer(body = """{"success":true,"data":null}""") else
                            FakeAnswer(body = """{"success":true,"data":"https://cdn.example/film.mkv"}""")
                    }
                    else -> error("Unexpected TorBox request")
                } }
                val season = episode?.let { 2 }
                val year = if (episode == null) 2026 else null
                val row = hashRow().copy(exactSource = true, fileIdx = null, torboxFileID = 7,
                    expectation = StreamExpectation("Film", year, season, episode, episode == null, canonicalID = "saved:tamilmv:fixture"))
                val steps = java.util.concurrent.CopyOnWriteArrayList<String>()
                val resolver = DebridResolver("key", null, http.client, diagnostics = { _, _, step -> steps += step })
                val firstPress = resolver.resolve(row, season, episode)
                assertTrue("episode=$episode outcome=$firstPress steps=$steps", firstPress is DebridOutcome.Success)
                assertEquals(7, (firstPress as DebridOutcome.Success).resolved.torboxFileId)
                assertEquals(1, http.requests.count { it.url.pathSegments.last() == "createtorrent" })
                assertEquals(2, accountReads)
                assertEquals(2, linkReads)
                assertTrue(steps.any { "stage=mylist attempt=2 status=200" in it })
                assertTrue(steps.none { "cdn.example" in it })
            }
        }
    }

    @Test fun `TamilMV readiness retries are bounded and do not repeat acquisition`() = runTest {
        withContext(Dispatchers.IO) {
            val http = FakeHttp { request -> when (request.url.pathSegments.last()) {
                "createtorrent" -> FakeAnswer(body = """{"success":true,"data":{"torrent_id":14}}""")
                "mylist" -> FakeAnswer(code = 404)
                else -> error("A missing account entry has no link")
            } }
            val row = hashRow().copy(exactSource = true, torboxFileID = 7,
                expectation = StreamExpectation("Film", null, null, null, true, canonicalID = "saved:tamilmv:fixture"))
            assertEquals(DebridOutcome.TorboxPreparing, DebridResolver("key", null, http.client).resolve(row))
            assertEquals(listOf("createtorrent", "mylist", "mylist", "mylist"),
                http.requests.map { it.url.pathSegments.last() })
        }
    }

    @Test fun `TamilMV site stamp preserves exact movie file selection and rejects contradictions`() = runTest {
        val name = "www.1TamilMV.io - Sandakari (2026) Tamil TRUE WEB-DL - 1080p - AVC - (DD5.1 - 384Kbps and AAC) - 5GB - ESub.mkv"
        val expected = StreamExpectation("Sandakari", 2026, null, null, true)
        val row = hashRow().copy(exactSource = true, torboxTorrentID = 14, torboxFileID = 0, expectation = expected)
        suspend fun check(filename: String, source: StreamRow = row, succeeds: Boolean) {
            val http = FakeHttp { request -> when (request.url.pathSegments.last()) {
                "mylist" -> FakeAnswer(body = """{"success":true,"data":{"hash":"$hash","name":"$filename","files":[{"id":0,"short_name":"$filename","name":"release/$filename","size":5342264180,"mimetype":"video/x-matroska"}]}}""")
                "requestdl" -> {
                    assertEquals("0", request.url.queryParameter("file_id"))
                    assertEquals("14", request.url.queryParameter("torrent_id"))
                    FakeAnswer(body = """{"success":true,"data":"https://cdn.example/movie.mkv"}""")
                }
                else -> error("Unexpected torrent acquisition")
            } }
            val result = TorboxResolver("key", http.client).resolve(source, null, null)
            assertEquals(filename, succeeds, result is DebridOutcome.Success)
            assertEquals(filename, succeeds, http.urls.any { it.contains("requestdl") })
        }
        check(name, succeeds = true)
        check(name.replace("www.1TamilMV.io", "1tamilmv.capital"), succeeds = true)
        check(name.replace("Sandakari", "Another Movie"), succeeds = false)
        check(name.replace("2026", "2025"), succeeds = false)
        check(name.replace("TRUE WEB-DL", "HDCAM"), succeeds = false)
        check(name.replace("1080p", "1080p trailer"), succeeds = false)
        check(name.replace("1080p", "S01E01 1080p"), succeeds = false)
        check(name, row.copy(infoHash = "a".repeat(40)), succeeds = false)
        check(name, row.copy(torboxFileID = 1), succeeds = false)
        check("extra text - $name", succeeds = false)
    }

    @Test fun `a failed checked delivery cannot return under a different addon row id`() {
        val expected = StreamExpectation("The Bear", 2022, 2, 4, false)
        val row = directRow().copy(expectation = expected)
        val resolver = DebridResolver(null, null)
        resolver.notePlaybackFailure(row, 2, 4)
        assertTrue(resolver.isUnavailable(row.copy(id = "other-alias"), 2, 4))
        assertFalse(resolver.isUnavailable(row.copy(id = "other-alias", url = "https://another.example/file.mkv"), 2, 4))
        assertFalse(resolver.isUnavailable(row.copy(expectation = expected.copy(episode = 5)), 2, 5))
    }

    @Test fun `DMM RD uses the redirected file name and still rejects a wrong episode or generic path`() = runTest { withContext(Dispatchers.IO) {
        val http = FakeHttp { FakeAnswer(code = 206, body = "\u0000\u0000\u0000\u0018ftypisom", headers = mapOf(
            "Content-Type" to "application/force-download", "Content-Range" to "bytes 0-63/4000000000")) }
        val expected = StreamExpectation("The Bear", 2022, 2, 4, false)
        fun source(name: String) = directRow().copy(addonName = "DMM Cast for Real-Debrid", filename = null,
            releaseName = "The.Bear.S02E04.1080p.-", url = "https://dal1.download.real-debrid.com/d/id/$name")
        val resolver = DebridResolver(null, null, http.client)
        val good = requireNotNull(resolver.verifiedSource(source("The.Bear.S02E04.1080p.mkv"), expected))
        assertEquals("The.Bear.S02E04.1080p.mkv", good.filename)
        assertEquals("Cached Debrid", StreamRanker.deliveryLabel(good))
        assertNull(resolver.verifiedSource(source("The.Bear.S02E03.1080p.mkv"), expected))
        assertNull(resolver.verifiedSource(source("video.mkv").copy(cachedHint = CachedHint(true, "TorBox", verified = true)), expected))
    } }

    @Test fun `checked source metadata and later failures stay linked to the original DMM route`() = runTest { withContext(Dispatchers.IO) {
        val file = "The.Bear.S02E04.1080p.WEB-DL.HEVC.mkv"
        val http = FakeHttp { FakeAnswer(code = 206, body = "\u0000\u0000\u0000\u0018ftypisom", headers = mapOf(
            "Content-Type" to "video/mp4", "Content-Range" to "bytes 0-63/4000000000", "Content-Disposition" to "attachment; filename=\"$file\"")) }
        val expected = StreamExpectation("The Bear", 2022, 2, 4, false)
        val source = directRow().copy(addonName = "DMM Cast for Real-Debrid", releaseName = "The.Bear.S02E04.1080p.-",
            filename = null, sizeBytes = 9_000_000_000L, expectation = expected)
        val resolver = DebridResolver(null, null, http.client)
        val checked = requireNotNull(resolver.verifiedSource(source, expected))
        assertEquals(file, checked.filename)
        assertEquals(4_000_000_000L, checked.sizeBytes)
        assertEquals(listOf("HEVC"), checked.facts.codecs)
        assertNotNull(checked.linkCheckedAtMillis)
        resolver.notePlaybackFailure(checked, 2, 4)
        assertTrue(resolver.isUnavailable(source, 2, 4))
        val count = http.requests.size
        assertNull(resolver.verifiedSource(source, expected))
        assertEquals(count, http.requests.size)
        resolver.forget(checked, 2, 4)
        assertNotNull(resolver.verifiedSource(source, expected))
    } }

    @Test fun `a verified DMM episode falls back to the same cached TorBox file after a proxy denial`() = runTest { withContext(Dispatchers.IO) {
        val file = "The.Bear.S02E04.1080p.WEB-DL.H.265-FLUX.mkv"
        val http = FakeHttp { request -> when {
            request.url.host == "debridmediamanager.com" -> FakeAnswer(code = 403)
            request.url.encodedPath.contains("createtorrent") -> FakeAnswer(body = fixture("torbox-createtorrent.json"))
            request.url.encodedPath.contains("mylist") -> FakeAnswer(body = fixture("torbox-mylist.json"))
            request.url.encodedPath.contains("requestdl") -> FakeAnswer(body = fixture("torbox-requestdl.json"))
            request.url.host == "store-042.torbox.app" -> FakeAnswer(code = 206, body = "\u0000\u0000\u0000\u0018ftypisom", headers = mapOf(
                "Content-Type" to "video/mp4", "Content-Range" to "bytes 0-63/4520902164", "Content-Disposition" to "attachment; filename=\"$file\""))
            else -> FakeAnswer(code = 500)
        } }
        val expected = StreamExpectation("The Bear", 2022, 2, 4, false)
        val row = directRow().copy(addonName = "DMM Cast for TorBox", filename = file, releaseName = file,
            url = "https://debridmediamanager.com/api/stremio-tb/profile/play/$hash?file=$file",
            expectation = expected, cacheHash = hash, cachedHint = CachedHint(true, "TorBox", verified = true))
        val resolver = DebridResolver("key", null, http.client)
        assertEquals(true, resolver.inspect(row, expected))
        val resolved = resolver.resolve(row, 2, 4) as DebridOutcome.Success
        assertTrue(resolved.resolved.url.contains("/91823/1/"))
        assertTrue(resolved.resolved.url.contains(file))
        assertEquals(1, http.requests.count { it.url.encodedPath.contains("createtorrent") })
    } }

    @Test fun `a selected slow source can pass after the automatic check budget expires`() = runTest { withContext(Dispatchers.IO) {
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        val http = FakeHttp { FakeAnswer(code = 206, delayMillis = 150, body = "\u0000\u0000\u0000\u0018ftypisom", headers = mapOf(
            "Content-Type" to "video/mp4", "Content-Range" to "bytes 0-63/4000000000")) }
        val resolver = DebridResolver(null, null, http.client, diagnostics = { _, _, event -> events += event })
        val row = directRow().copy(addonName = "DMM Cast for TorBox", title = "TorBox FHD")
        val expected = StreamExpectation("The Bear", 2022, 2, 4, false)
        assertNull(resolver.inspect(row, expected, timeoutMillis = 25L))
        assertTrue(events.last().contains("result=unknown reason=timeout_25ms"))
        assertEquals(true, resolver.inspect(row, expected, timeoutMillis = 1_000L))
        assertTrue(events.last().contains("result=true reason=media_prefix"))
        assertEquals(6_000L, DebridResolver.MEDIA_INSPECTION_TIMEOUT_MILLIS)
        assertEquals(30_000L, DebridResolver.MANUAL_MEDIA_INSPECTION_TIMEOUT_MILLIS)
    } }

    @Test fun `source diagnostics distinguish denied media from inconclusive bytes`() = runTest { withContext(Dispatchers.IO) {
        val expected = StreamExpectation("The Bear", 2022, 2, 4, false)
        for (denied in listOf(true, false)) {
            val events = java.util.concurrent.CopyOnWriteArrayList<String>()
            val row = directRow().copy(addonName = "DMM Cast for TorBox", title = "TorBox FHD")
            val http = FakeHttp { FakeAnswer(code = if (denied) 403 else 206, body = if (denied) "denied" else "unknown-bytes", headers = mapOf("Content-Type" to "application/octet-stream")) }
            val resolver = DebridResolver(null, null, http.client, diagnostics = { seen, context, event ->
                assertEquals(row.addonName, seen.addonName)
                assertEquals(expected, context)
                events += event
            })
            assertEquals(if (denied) false else null, resolver.inspect(row, expected))
            assertTrue(events.any { it.contains("stage=http") && it.contains("status=${if (denied) 403 else 206}") })
            assertTrue(events.last().contains(if (denied) "result=false reason=http_403" else "result=unknown reason=inconclusive_media"))
            assertTrue(events.none { it.contains("timeout_6000ms") })
            assertTrue(events.none { it.contains(row.url.orEmpty()) || it.contains("denied") || it.contains("unknown-bytes") })
        }
    } }

    @Test fun `movie bundles select the named film instead of a larger different film`() {
        val expected = StreamExpectation("Alpha", 2026, null, null, true)
        val files = listOf(DebridFile(3, "Alpha.2026.1080p.mkv", 2_000), DebridFile(9, "Other.Movie.2026.2160p.mkv", 8_000))
        assertEquals(3, DebridFileSelection.select(files, DebridSelectionHint(expectation = expected))?.id)
        assertNull(DebridFileSelection.select(listOf(DebridFile(1, "video.mkv", 2_000), DebridFile(2, "file.mkv", 8_000)),
            DebridSelectionHint(expectation = expected)))
    }
    @Test fun `folder seasons and composite episode files cannot select a wrong episode`() {
        val files = listOf(DebridFile(3, "Show/Season 2/04.mkv", 2_000))
        assertEquals(3, DebridFileSelection.select(files, DebridSelectionHint(season = 2, episode = 4))?.id)
        assertNull(DebridFileSelection.select(files, DebridSelectionHint(season = 1, episode = 4, fileIdx = 0)))
        assertNull(DebridFileSelection.select(listOf(DebridFile(1, "Show.S01E01-E04.mkv", 2_000)),
            DebridSelectionHint(season = 1, episode = 1)))
    }
    @Test fun `provider file ids and missing positions never substitute for torrent indices`() {
        val files = listOf(DebridFile(1, "video.mkv", 2_000, torrentIndex = 2))
        assertNull(DebridFileSelection.select(files, DebridSelectionHint(fileIdx = 1)))
        assertEquals(1, DebridFileSelection.select(files, DebridSelectionHint(fileIdx = 2))?.id)
    }
    @Test fun `HTTP identity proof cannot be weakened by a cache provider label or prior relaxed probe`() = runTest { withContext(Dispatchers.IO) {
        for (hint in listOf(CachedHint(false, null), CachedHint(true, "TorBox"), CachedHint(true, "Real-Debrid"))) {
            val http = FakeHttp { FakeAnswer(code = 206, body = "\u0000\u0000\u0000\u0018ftypisom", headers = mapOf(
                "Content-Type" to "video/mp4", "Content-Range" to "bytes 0-63/4000000000")) }
            val expected = StreamExpectation("The Bear", 2022, 2, 4, false)
            val row = directRow().copy(releaseName = "video.mkv", filename = "video.mkv", title = "Provider 1080p",
                identityNames = emptyList(), cachedHint = hint)
            val resolver = DebridResolver(null, null, http.client)
            assertEquals(true, resolver.inspect(row, expected, strictName = false))
            assertEquals(false, resolver.inspect(row, expected, strictName = true))
            assertEquals(1, http.requests.size)
        }
    } }

    @Test fun `actual named HTTP file can verify an otherwise generic source`() = runTest { withContext(Dispatchers.IO) {
        val http = FakeHttp { FakeAnswer(code = 206, body = "\u0000\u0000\u0000\u0018ftypisom", headers = mapOf(
            "Content-Type" to "video/mp4", "Content-Range" to "bytes 0-63/4000000000",
            "Content-Disposition" to "attachment; filename=\"The.Bear.S02E04.1080p.mkv\"")) }
        val row = directRow().copy(releaseName = "video.mkv", filename = "video.mkv", title = "Provider 1080p", identityNames = emptyList())
        assertEquals(true, DebridResolver(null, null, http.client).inspect(row, StreamExpectation("The Bear", 2022, 2, 4, false)))
    } }

    @Test fun `a clean cached child cannot launder a camera container even for an exact saved file`() = runTest {
        for (row in listOf(hashRow(), hashRow().copy(exactSource = true, torboxTorrentID = 91823, torboxFileID = 1))) {
            val http = FakeHttp { request -> when {
                request.url.encodedPath.contains("createtorrent") -> FakeAnswer(body = fixture("torbox-createtorrent.json"))
                request.url.encodedPath.contains("mylist") -> FakeAnswer(body = fixture("torbox-mylist.json").replace(
                    "\"name\": \"The.Bear.S02.1080p.WEB-DL.H.265-FLUX\"", "\"name\": \"The.Bear.S02.HDCAM\""))
                else -> FakeAnswer(body = fixture("torbox-requestdl.json"))
            } }
            assertEquals(DebridOutcome.Stale, TorboxResolver("key", http.client).resolve(row, 2, 4))
            assertTrue(http.urls.none { it.contains("requestdl") })
        }
    }

    @Test fun `an exact movie selects its named feature before a larger unrelated file`() = runTest {
        val http = FakeHttp { request -> if (request.url.encodedPath.contains("mylist")) FakeAnswer(body = """{
            "success":true,"data":{"id":91823,"hash":"$hash","name":"Alpha.2026","files":[
            {"id":3,"name":"Alpha.2026.1080p.mkv","size":2000},
            {"id":9,"name":"Other.2026.2160p.mkv","size":8000}]}}""") else FakeAnswer(body = fixture("torbox-requestdl.json")) }
        val row = hashRow().copy(releaseName = "Alpha", fileIdx = null, exactSource = true, torboxTorrentID = 91823,
            expectation = StreamExpectation("Alpha", 2026, null, null, true))
        assertTrue(TorboxResolver("key", http.client).resolve(row, null, null, selectLargestVideo = true) is DebridOutcome.Success)
        assertTrue(http.urls.last().contains("file_id=3"))
    }

    @Test fun `padded seasons and EP labels retain exact episode identity`() {
        val expected = StreamExpectation("Bigg Boss Telugu", 2017, 10, 53, false, episodeYear = 2026)
        val row = StreamRow("padded", "provider", "Provider", "BIGG.BOSS.Telugu.2026.S010.EP53.1080p.mkv")
        assertTrue(expected.matches(row))
        assertFalse(expected.copy(episode = 54).matches(row))
        val files = listOf(DebridFile(13, "BIGG.BOSS.Telugu.S010.EP53.DAY52.mkv", 4_000))
        assertEquals(13, DebridFileSelection.select(files, DebridSelectionHint(season = 10, episode = 53, expectation = expected))?.id)
    }

    @Test fun `playback failures expire and never poison another route or title`() = runTest {
        var now = 0L
        val resolver = DebridResolver(null, null, FakeHttp { FakeAnswer(code = 404) }.client, clock = { now })
        val row = directRow().copy(expectation = StreamExpectation("The Bear", 2022, 2, 4, false, canonicalID = "tt14452776"))
        resolver.notePlaybackFailure(row, 2, 4)
        assertTrue(resolver.isUnavailable(row, 2, 4))
        assertFalse(resolver.isUnavailable(row.copy(url = "https://other.example/video.mkv"), 2, 4))
        assertFalse(resolver.isUnavailable(row.copy(expectation = row.expectation?.copy(canonicalID = "other")), 2, 4))
        now = 31_000
        assertFalse(resolver.isUnavailable(row, 2, 4))
    }

    @Test fun `Real Debrid links follow the selected file order`() = runTest { withContext(Dispatchers.IO) {
        val info = fixture("realdebrid-torrent-info.json")
            .replace("\"selected\": 0", "\"selected\": 1")
            .replace("[\"https://real-debrid.com/d/QWERTYUIOP12345\"]", "[\"https://real-debrid.com/d/FIRST\",\"https://real-debrid.com/d/SECOND\",\"https://real-debrid.com/d/THIRD\"]")
        var selectedLink: String? = null
        val http = FakeHttp { request -> when {
            request.url.encodedPath.endsWith("addMagnet") -> FakeAnswer(body = fixture("realdebrid-addmagnet.json"))
            request.url.encodedPath.contains("torrents/info") -> FakeAnswer(body = info)
            request.url.encodedPath.contains("selectFiles") -> FakeAnswer(code = 202)
            request.url.encodedPath.endsWith("unrestrict/link") -> {
                selectedLink = (request.body as okhttp3.FormBody).value(0)
                FakeAnswer(body = fixture("realdebrid-unrestrict-link.json"))
            }
            else -> FakeAnswer(code = 204)
        } }
        val result = com.fourseveneightnine.tv.client.data.streams.debrid.RealDebridResolver("key", http.client)
            .resolve(hashRow(), 2, 4)
        assertTrue(result is DebridOutcome.Success)
        assertEquals("https://real-debrid.com/d/SECOND", selectedLink)
    } }

    @Test
    fun `unprobed HLS variants stay unknown when the bounded prefix failed`() = runTest { withContext(Dispatchers.IO) {
        val http = FakeHttp { request -> if (request.url.encodedPath == "/master.m3u8")
            FakeAnswer(body = "#EXTM3U\n" + (1..4).joinToString("\n") { "#EXT-X-STREAM-INF:BANDWIDTH=800000\n$it.m3u8" })
            else FakeAnswer(code = 404) }
        val row = directRow().copy(url = "https://media.example/master.m3u8")
        val resolver = DebridResolver(null, null, http.client)
        assertNull(resolver.inspect(row, StreamExpectation("The Bear", 2022, 2, 4, false)))
        assertEquals(4, http.requests.size)
        assertFalse(resolver.isUnavailable(row.copy(expectation = StreamExpectation("The Bear", 2022, 2, 4, false)), 2, 4))
    } }

    @Test
    fun `video MIME alone cannot verify an error body`() = runTest { withContext(Dispatchers.IO) {
        val http = FakeHttp { FakeAnswer(code = 206, body = "Gateway temporarily unavailable", headers = mapOf(
            "Content-Type" to "video/mp4", "Content-Range" to "bytes 0-63/4000000000")) }
        val resolver = DebridResolver(null, null, http.client)
        assertNull(resolver.inspect(directRow(), StreamExpectation("The Bear", 2022, 2, 4, false)))
        assertFalse(resolver.isUnavailable(directRow(), 2, 4))
    } }

    @Test
    fun `a master playlist can use a working second variant`() = runTest { withContext(Dispatchers.IO) {
        val http = FakeHttp { request -> when (request.url.encodedPath) {
            "/master.m3u8" -> FakeAnswer(body = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=4000000\ndead.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nworking.m3u8\n")
            "/dead.m3u8" -> FakeAnswer(code = 404)
            "/working.m3u8" -> FakeAnswer(body = "#EXTM3U\n#EXTINF:6,\nsegment.ts\n")
            else -> FakeAnswer(body = "\u0000\u0000\u0000\u0018ftypisom", headers = mapOf("Content-Type" to "video/mp2t"))
        } }
        val expected = StreamExpectation("The Bear", 2022, 2, 4, false)
        val row = directRow().copy(url = "https://media.example/master.m3u8", expectation = expected)
        val resolver = DebridResolver(null, null, http.client)
        assertEquals(true, resolver.inspect(row, expected))
        assertEquals("https://media.example/working.m3u8", (resolver.resolve(row, 2, 4) as DebridOutcome.Success).resolved.url)
        assertEquals(4, http.requests.size)
    } }

    @Test
    fun `HLS must have an available AES key before encrypted bytes are proof`() = runTest { withContext(Dispatchers.IO) {
        for (code in listOf(200, 403, 404)) {
            val http = FakeHttp { request -> when (request.url.encodedPath) {
                "/index.m3u8" -> FakeAnswer(body = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF:6,\nsegment.ts\n")
                "/key" -> FakeAnswer(code = code, body = "1234567890abcdef")
                else -> FakeAnswer(body = "0123456789abcdef0123456789abcdef", headers = mapOf("Content-Type" to "video/mp2t"))
            } }
            val row = directRow().copy(url = "https://media.example/index.m3u8", headers = mapOf("Referer" to "https://provider.example/"))
            assertEquals(code.toString(), code == 200, DebridResolver(null, null, http.client).inspect(row,
                StreamExpectation("The Bear", 2022, 2, 4, false)))
            assertTrue(http.requests.all { it.header("Referer") == "https://provider.example/" })
        }
    } }

    @Test
    fun `direct and exact saved theater prints are refused before network work`() = runTest { withContext(Dispatchers.IO) {
        val http = FakeHttp { error("Theater print must not reach the network") }
        val row = directRow().copy(filename = "The.Bear.S02E04.HD-TS.mkv", exactSource = true)
        val resolver = DebridResolver(null, null, http.client)
        assertEquals(DebridOutcome.Stale, resolver.resolve(row, 2, 4))
        assertEquals(false, resolver.inspect(row, null))
        assertTrue(http.requests.isEmpty())
    } }

    @Test
    fun `media inspection follows HLS children and preserves required headers`() = runTest { withContext(Dispatchers.IO) {
        val http = FakeHttp { request ->
            val body = when (request.url.encodedPath) {
                "/master.m3u8" -> "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nmedia.m3u8\n"
                "/media.m3u8" -> "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:6,\nfirst.ts\n#EXT-X-ENDLIST\n"
                else -> "\u0000\u0000\u0000\u0018ftypisom"
            }
            FakeAnswer(body = body, headers = mapOf("Content-Type" to
                if (request.url.encodedPath.endsWith("m3u8")) "text/x-mpegurl" else "video/mp2t"))
        }
        val row = directRow().copy(url = "https://media.example/master.m3u8", headers = mapOf("Referer" to "https://provider.example/"))
        val resolver = DebridResolver(null, null, http.client)
        val expected = StreamExpectation("The Bear", 2022, 2, 4, false)
        assertEquals(true, resolver.inspect(row, expected))
        assertEquals(4, http.requests.size)
        assertTrue(http.requests.all { it.header("Referer") == "https://provider.example/" })
        val before = http.requests.size
        val resolved = (resolver.resolve(row, 2, 4) as DebridOutcome.Success).resolved
        assertEquals(before, http.requests.size)
        assertFalse(resolved.headers.keys.any { it.equals("Range", ignoreCase = true) })
    } }

    @Test
    fun `valid playlist does not conceal a blocked first segment`() = runTest { withContext(Dispatchers.IO) {
        val http = FakeHttp { request ->
            if (request.url.encodedPath.endsWith("m3u8")) FakeAnswer(
                body = "#EXTM3U\n#EXTINF:6,\nmissing.ts\n", headers = mapOf("Content-Type" to "application/vnd.apple.mpegurl"))
            else FakeAnswer(code = 404)
        }
        val row = directRow().copy(url = "https://media.example/index.m3u8")
        val resolver = DebridResolver(null, null, http.client)
        assertEquals(false, resolver.inspect(row, StreamExpectation("The Bear", 2022, 2, 4, false)))
        assertTrue(resolver.isUnavailable(row.copy(expectation = StreamExpectation("The Bear", 2022, 2, 4, false)), 2, 4))
    } }

    @Test
    fun `actual wrong episode and mislabeled JSON cannot become playable evidence`() = runTest { withContext(Dispatchers.IO) {
        var json = false
        val http = FakeHttp { FakeAnswer(
            body = if (json) "{\"error\":\"removed\"}" else "\u0000\u0000\u0000\u0018ftypisom",
            headers = mapOf("Content-Type" to "application/octet-stream", "Content-Range" to "bytes 0-63/4000000000") +
                if (json) emptyMap() else mapOf("Content-Disposition" to "attachment; filename=\"The.Bear.S02E05.1080p.mkv\"")) }
        val row = directRow()
        val expected = StreamExpectation("The Bear", 2022, 2, 4, false)
        assertEquals(false, DebridResolver(null, null, http.client).inspect(row, expected))
        json = true
        assertEquals(false, DebridResolver(null, null, http.client).inspect(row, expected))
    } }


    @Test
    fun `download only range ignoring endpoint is not a verified movie stream`() = runTest { withContext(Dispatchers.IO) {
        val http = FakeHttp { FakeAnswer(body = "\u0000\u0000\u0000\u0018ftypisom",
            headers = mapOf("Content-Type" to "video/mp4", "Content-Length" to "4000000000")) }
        val row = directRow()
        assertEquals(false, DebridResolver(null, null, http.client).inspect(row,
            StreamExpectation("The Bear", 2022, 2, 4, false)))
    } }

    @Test
    fun `transient seek check stays inconclusive instead of hiding a source`() = runTest { withContext(Dispatchers.IO) {
        val http = FakeHttp { request ->
            if (request.header("Range")?.startsWith("bytes=1048576-") == true) FakeAnswer(code = 503)
            else FakeAnswer(body = "\u0000\u0000\u0000\u0018ftypisom",
                headers = mapOf("Content-Type" to "video/mp4", "Content-Length" to "4000000000"))
        }
        val resolver = DebridResolver(null, null, http.client)
        assertNull(resolver.inspect(directRow(), StreamExpectation("The Bear", 2022, 2, 4, false)))
        assertFalse(resolver.isUnavailable(directRow(), 2, 4))
    } }

    @Test
    fun `range refusal retries a bounded normal GET and proof expires quickly`() = runTest { withContext(Dispatchers.IO) {
        var now = 0L
        var denied = false
        val http = FakeHttp { request ->
            val range = request.header("Range")
            if (denied || range?.startsWith("bytes=0-") == true) FakeAnswer(code = 403)
            else FakeAnswer(code = if (range == null) 200 else 206,
                body = "\u0000\u0000\u0000\u0018ftypisom", headers = mapOf(
                    "Content-Type" to "video/mp4", "Content-Length" to "4000000000") +
                    if (range == null) emptyMap() else mapOf("Content-Range" to "bytes 1048576-1048639/4000000000"))
        }
        val row = directRow()
        val expected = StreamExpectation("The Bear", 2022, 2, 4, false)
        val resolver = DebridResolver(null, null, http.client, clock = { now })
        assertEquals(true, resolver.inspect(row, expected))
        assertEquals(3, http.requests.size)
        denied = true
        assertEquals(true, resolver.inspect(row, expected))
        assertEquals(3, http.requests.size)
        now = 31_000L
        assertEquals(false, resolver.inspect(row, expected))
        assertTrue(resolver.isUnavailable(row.copy(expectation = StreamExpectation("The Bear", 2022, 2, 4, false)), 2, 4))
        assertTrue(http.urls.none { it.contains("createtorrent") || it.contains("addMagnet") })
    } }


    @Test
    fun `TorBox accepts an alias key when the payload confirms the requested hash`() = runTest {
        val requested = "a".repeat(40)
        val alias = "b".repeat(40)
        val unrelated = "c".repeat(40)
        val http = FakeHttp { request ->
            assertEquals("hash=$requested&format=object&list_files=true", request.url.encodedQuery)
            FakeAnswer(body = """{"success":true,"data":{
                "$alias":{"hash":"${requested.uppercase()}","files":[{"id":7,"name":"film.mkv","size":100}]},
                "$unrelated":{"hash":"$unrelated","files":[{"id":8,"name":"other.mkv","size":200}]}}}""")
        }
        val files = TorboxResolver("key", http.client).cachedFiles(requested)
        assertEquals(listOf(7), files.map { it.id })
    }

    @Test
    fun `hybrid release uses its own v2 hash for cache and exact file playback`() = runTest {
        val v1 = "001a2265e7270375996518c9de1799e0c265e914"
        val v2 = "fb4c3c7da47dc880af3adb6ac9b89ac1cd7bd12db8700694c956baa2acd5219b"
        val alias = v2.take(40)
        val wrong = "c".repeat(40)
        val files = """[{"id":7,"name":"film.mkv","size":100}]"""
        val http = FakeHttp { request ->
            when (request.url.pathSegments.last()) {
                "checkcached" -> FakeAnswer(body = """{"success":true,"data":{
                    "$wrong":{"hash":"$wrong","files":[{"id":9,"name":"other.mkv"}]},
                    "$alias":{"hash":"$alias","files":$files}}}""")
                "createtorrent" -> {
                    val body = okio.Buffer().also { requireNotNull(request.body).writeTo(it) }.readUtf8()
                    assertTrue(body.contains("xt=urn:btih:$v1&xt=urn:btmh:1220$v2"))
                    assertTrue(body.contains("add_only_if_cached"))
                    FakeAnswer(body = """{"success":true,"data":{"torrent_id":14}}""")
                }
                "mylist" -> FakeAnswer(body = """{"success":true,"data":{"hash":"$alias","files":$files}}""")
                else -> {
                    assertEquals("7", request.url.queryParameter("file_id"))
                    FakeAnswer(body = """{"success":true,"data":"https://cdn.example/film.mkv"}""")
                }
            }
        }
        val torbox = TorboxResolver("key", http.client)
        assertTrue(torbox.cachedFiles(v1).isEmpty())
        assertEquals(listOf(7), torbox.cachedFiles(listOf(v1), mapOf(v1 to v2)).getValue(v1).map { it.id })
        assertTrue(torbox.isCached(v1, v2))
        val row = hashRow().copy(infoHash = v1, v2Infohash = v2, exactSource = true, torboxFileID = 7)
        assertTrue(torbox.resolve(row, null, null) is DebridOutcome.Success)
    }

    @Test
    fun `TorBox can confirm an exact cached hash without returning files`() = runTest {
        val hash = "a".repeat(40)
        val http = FakeHttp { request ->
            assertEquals("GET", request.method)
            assertEquals(hash, request.url.queryParameter("hash"))
            assertNull(request.url.queryParameter("list_files"))
            FakeAnswer(body = """{"success":true,"data":{"$hash":{"hash":"$hash"}}}""")
        }
        assertTrue(TorboxResolver("key", http.client).isCached(hash))
    }

    @Test
    fun `an exact movie cannot guess the largest file in an episodic pack`() = runTest {
        val http = torboxHttp()
        val row = hashRow().copy(fileIdx = null, exactSource = true)
        val outcome = DebridResolver("key", null, http.client).resolve(row, selectLargestVideo = true)
        assertEquals(DebridOutcome.Stale, outcome)
        assertTrue(http.urls.first().contains("createtorrent"))
        assertFalse(http.urls.any { it.contains("requestdl") })
    }

    @Test
    fun `shared library refresh retains account count on failure and does not require a phone`() = runTest {
        var fail = false
        val http = FakeHttp { request ->
            assertEquals("Bearer local-key", request.header("Authorization"))
            FakeAnswer(code = if (fail) 401 else 200,
                body = """{"success":true,"data":[{"id":8,"name":"Saved movie","hash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","download_present":true,"files":[]}]}""")
        }
        val resolver = DebridResolver("local-key", null, http.client)
        resolver.refreshTorboxLibrary()
        assertTrue(resolver.libraryState.value.loaded)
        assertEquals(1, resolver.libraryState.value.rows.size)
        resolver.refreshTorboxLibrary()
        assertEquals(1, http.urls.size)
        fail = true
        resolver.refreshTorboxLibrary(force = true)
        assertEquals(1, resolver.libraryState.value.rows.size)
        assertTrue(resolver.libraryState.value.message.orEmpty().contains("401"))
        assertFalse(resolver.libraryState.value.loading)
        assertTrue(http.urls.all { it.contains("/torrents/mylist") })
    }

    @Test
    fun `saved ambiguous pack requires a file choice instead of choosing the largest video`() = runTest {
        val http = torboxHttp()
        val row = hashRow().copy(torboxTorrentID = 91823, fileIdx = null, exactSource = true)
        assertTrue(DebridResolver("key", null, http.client).resolve(row) is DebridOutcome.Stale)
        assertFalse(http.urls.any { it.contains("requestdl") })
    }
    @Test
    fun `account library paginates all torrents newest first including not ready items`() = runTest {
        val http = FakeHttp { request ->
            val data = if (request.url.queryParameter("offset") == "0") """[
              {"id":1,"hash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","name":"Older","created_at":"2026-01-01T00:00:00Z","cached":true,"files":[]},
              {"id":2,"hash":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","name":"Pending","created_at":"2026-02-01T00:00:00Z","download_finished":false,"files":[]}]
              """ else """[{"id":3,"hash":"cccccccccccccccccccccccccccccccccccccccc","name":"Newest","created_at":"2026-03-01T00:00:00Z","download_present":true,"files":[]}]"""
            FakeAnswer(body = "{\"success\":true,\"data\":$data}")
        }
        val rows = TorboxResolver("key", http.client).library(pageSize = 2)
        assertEquals(listOf(3, 2, 1), rows.map { it.id })
        assertFalse(rows[1].ready)
        assertEquals(2, http.urls.size)
        assertTrue(http.urls.all { it.contains("mylist") })
    }

    @Test
    fun `library selection resolves the chosen movie or episode directly by its provider file ID`() = runTest {
        val hash = "a".repeat(40)
        val files = listOf(0 to "Feature.2026.mkv", 17 to "Show.S02E04.mkv")
        val http = FakeHttp { request ->
            assertEquals("/v1/api/torrents/requestdl", request.url.encodedPath)
            assertEquals("73", request.url.queryParameter("torrent_id"))
            val fileId = requireNotNull(request.url.queryParameter("file_id")).toInt()
            assertTrue(files.any { it.first == fileId })
            FakeAnswer(body = """{"success":true,"data":"https://cdn.example/$fileId.mkv"}""")
        }
        val resolver = DebridResolver("local-key", null, http.client)
        for ((id, name) in files) {
            val row = StreamRow("saved-torbox:$hash:$id:73", "TorBox Library", "Library pack", name,
                infoHash = hash, torboxTorrentID = 73, torboxFileID = id, exactSource = true,
                filename = "Library/$name", sizeBytes = 8_000L, torboxLibraryFile = true)
            val played = resolver.resolve(row) as DebridOutcome.Success
            assertEquals(id, played.resolved.torboxFileId)
            assertEquals(name, played.resolved.filename)
            assertEquals("https://cdn.example/$id.mkv", played.resolved.url)
        }
        assertEquals(2, http.urls.size)
    }

    @Test
    fun `an uncached pack offers no files and never acquires a torrent`() = runTest {
        val http = FakeHttp { FakeAnswer(body = """{"success":true,"data":{}}""") }
        assertTrue(TorboxResolver("key", http.client).cachedFiles("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").isEmpty())
        assertEquals(1, http.urls.size)
        assertTrue(http.urls.single().contains("checkcached"))
        assertFalse(http.urls.single().contains("createtorrent"))
    }

    @Test
    fun `cached pack file picker keeps actual file IDs and paths and omits nonvideo files`() = runTest {
        val hash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val body = """{"success":true,"data":{"$hash":{"files":[
            {"id":7,"name":"Season 2/Episode 03.mkv","size":100},
            {"id":8,"name":"Season 3/Episode 03.mkv","size":200},
            {"id":9,"name":"readme.txt","size":1}]}}}"""
        val http = FakeHttp { FakeAnswer(body = body) }
        val files = TorboxResolver("key", http.client).cachedFiles(hash)
        assertEquals(listOf(7, 8), files.map { it.id })
        assertEquals("Season 3/Episode 03.mkv", files[1].name)
        assertEquals(1, http.urls.size)
    }

    @Test
    fun `TorBox batches the requested hashes and returns only verified matching files`() = runTest {
        val best = "a".repeat(40)
        val next = "b".repeat(40)
        val unrelated = "c".repeat(40)
        val http = FakeHttp { request ->
            assertEquals("GET", request.method)
            assertEquals(listOf("$best,$next"), request.url.queryParameterValues("hash"))
            assertEquals("true", request.url.queryParameter("list_files"))
            FakeAnswer(body = """{"success":true,"data":{
                "${next.uppercase()}":{"files":[{"id":7,"name":"film.mkv","size":100}]},
                "$unrelated":{"files":[{"id":8,"name":"other.mkv","size":100}]}}}""")
        }
        val files = TorboxResolver("key", http.client).cachedFiles(listOf(best, next, next))
        assertEquals(setOf(next), files.keys)
        assertEquals(7, files.getValue(next).single().id)
        assertEquals(1, http.urls.size)
    }

    @Test
    fun `saved TorBox file uses exact IDs without creating a torrent or falling back`() = runTest {
        val http = torboxHttp()
        val row = hashRow().copy(torboxTorrentID = 91823, torboxFileID = 0, exactSource = true)
        val result = DebridResolver("key", null, http.client).resolve(row, 2, 3)
        assertTrue(result is DebridOutcome.Success)
        assertEquals(2, http.urls.size)
        assertFalse(http.urls.any { it.contains("createtorrent") })
        assertTrue(http.urls.last().contains("file_id=0"))
        val wrongEpisode = DebridResolver("key", null, http.client).resolve(row, 2, 4)
        assertEquals(DebridOutcome.Stale, wrongEpisode)
        val missing = DebridResolver("key", null, http.client).resolve(row.copy(torboxFileID = 999), 2, 4)
        assertTrue(missing is DebridOutcome.Stale)
    }

    @Test
    fun `saved episode pack refuses an absent episode instead of playing the largest file`() = runTest {
        val http = torboxHttp()
        val row = hashRow().copy(torboxTorrentID = 91823, fileIdx = null, exactSource = true)
        val result = DebridResolver("key", null, http.client).resolve(row, 2, 40)
        assertTrue(result is DebridOutcome.Stale)
        assertFalse(http.urls.any { it.contains("requestdl") || it.contains("createtorrent") })
    }

    private val hash = "c9e1585b1c4b0a1bb9a2e5a3d74c1f0ee2b34d55"

    private fun hashRow() = StreamRow(
        id = "row-1",
        addonName = "Torrentio",
        title = "Torrentio 1080p",
        releaseName = "The.Bear.S02E04.1080p.WEB-DL.H.265-FLUX.mkv",
        infoHash = hash,
        fileIdx = 1,
        sources = listOf("tracker:udp://tracker.opentrackr.org:1337/announce", "dht:$hash"),
    )

    private fun directRow() = StreamRow(
        id = "row-0",
        addonName = "AIOStreams",
        title = "AIOStreams 1080p",
        releaseName = "The.Bear.S02E04.1080p.mkv",
        url = "https://rd.example.com/d/ABC/The.Bear.S02E04.1080p.mkv",
        headers = mapOf("User-Agent" to "4789TV"),
    )

    private fun torboxHttp(createBody: String = fixture("torbox-createtorrent.json"), createCode: Int = 200) =
        FakeHttp { request ->
            val url = request.url.toString()
            when {
                url.contains("createtorrent") -> FakeAnswer(code = createCode, body = createBody)
                url.contains("mylist") -> FakeAnswer(body = fixture("torbox-mylist.json"))
                url.contains("requestdl") -> FakeAnswer(body = fixture("torbox-requestdl.json"))
                else -> FakeAnswer(code = 404, body = "{}")
            }
        }

    private fun realDebridHttp(status: String = "downloaded") = FakeHttp { request ->
        val url = request.url.toString()
        when {
            url.contains("addMagnet") -> FakeAnswer(body = fixture("realdebrid-addmagnet.json"))
            url.contains("torrents/info") ->
                FakeAnswer(body = fixture("realdebrid-torrent-info.json").replace("\"downloaded\"", "\"$status\""))
            url.contains("selectFiles") -> FakeAnswer(code = 202, body = "")
            url.contains("unrestrict/link") -> FakeAnswer(body = fixture("realdebrid-unrestrict-link.json"))
            url.contains("torrents/delete") -> FakeAnswer(code = 204, body = "")
            else -> FakeAnswer(code = 404, body = "{}")
        }
    }

    // MARK: - Pass-through

    @Test
    fun `a direct URL never reaches a provider`() = runTest {
        val http = torboxHttp()
        val outcome = DebridResolver("tb-key", "rd-key", http.client).resolve(directRow())
        assertTrue(outcome is DebridOutcome.Success)
        assertEquals(directRow().url, (outcome as DebridOutcome.Success).resolved.url)
        assertEquals(mapOf("User-Agent" to "4789TV"), outcome.resolved.headers)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `no key at all is MissingApiKey, not an attempt`() = runTest {
        val http = torboxHttp()
        assertEquals(
            DebridOutcome.MissingApiKey,
            DebridResolver(null, null, http.client).resolve(hashRow()),
        )
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `one or every provider transport failure remains Error`() = runTest {
        listOf("tb-key" to null, null to "rd-key", "tb-key" to "rd-key").forEach { (tb, rd) ->
            val http = FakeHttp { throw IOException("fixture transport failure") }
            assertEquals(DebridOutcome.Error, DebridResolver(tb, rd, http.client).resolve(hashRow()))
            assertTrue(http.requests.isNotEmpty())
        }
    }

    @Test
    fun `transport Error never replaces more informative provider failures`() = runTest {
        val cases = listOf(503 to DebridOutcome.Stale, 401 to DebridOutcome.MissingApiKey,
            409 to DebridOutcome.NotCached)
        cases.forEach { (code, expected) ->
            listOf(listOf(DebridService.REAL_DEBRID, DebridService.TORBOX),
                listOf(DebridService.TORBOX, DebridService.REAL_DEBRID)).forEach { order ->
                val http = FakeHttp { request ->
                    if (request.url.host.contains("torbox")) FakeAnswer(code = code)
                    else throw IOException("fixture transport failure")
                }
                val rules = PlaybackRules(enabled = true, debridPriority = order)
                assertEquals(expected, DebridResolver("tb-key", "rd-key", http.client, rules).resolve(hashRow()))
            }
        }
    }

    @Test
    fun `transport Error still falls through to provider Success`() = runTest {
        val http = FakeHttp { request ->
            when {
                request.url.host.contains("torbox") -> throw IOException("fixture transport failure")
                request.url.encodedPath.endsWith("addMagnet") -> FakeAnswer(body = fixture("realdebrid-addmagnet.json"))
                request.url.encodedPath.contains("torrents/info") -> FakeAnswer(body = fixture("realdebrid-torrent-info.json"))
                request.url.encodedPath.contains("selectFiles") -> FakeAnswer(code = 202, body = "")
                request.url.encodedPath.endsWith("unrestrict/link") -> FakeAnswer(body = fixture("realdebrid-unrestrict-link.json"))
                else -> FakeAnswer(code = 404)
            }
        }
        val rules = PlaybackRules(enabled = true,
            debridPriority = listOf(DebridService.TORBOX, DebridService.REAL_DEBRID))
        val outcome = DebridResolver("tb-key", "rd-key", http.client, rules).resolve(hashRow(), 2, 4)
        assertTrue(outcome is DebridOutcome.Success)
        assertEquals("Real-Debrid", (outcome as DebridOutcome.Success).resolved.service)
    }

    // MARK: - TorBox

    @Test
    fun `TorBox resolves a cached hash to a playable URL`() = runTest {
        val http = torboxHttp()
        val outcome = DebridResolver("tb-key", null, http.client).resolve(hashRow(), season = 2, episode = 4)
        assertTrue(outcome is DebridOutcome.Success)
        val resolved = (outcome as DebridOutcome.Success).resolved
        assertTrue(resolved.url.startsWith("https://store-042.torbox.app/dl/91823/1/"))
        assertEquals("TorBox", resolved.service)
        assertEquals(4520902164L, resolved.sizeBytes)
    }

    @Test
    fun `TorBox is asked never to start a download`() = runTest {
        val http = torboxHttp()
        DebridResolver("tb-key", null, http.client).resolve(hashRow(), 2, 4)
        val create = http.requests.first { it.url.toString().contains("createtorrent") }
        val body = okio.Buffer().also { create.body?.writeTo(it) }.readUtf8()
        assertTrue(body.contains("add_only_if_cached"))
        assertTrue(body.contains("magnet:?xt=urn:btih:$hash"))
    }

    @Test
    fun `a TorBox 409 means not cached`() = runTest {
        val http = torboxHttp(createCode = 409, createBody = fixture("torbox-createtorrent-notcached.json"))
        assertEquals(
            DebridOutcome.NotCached,
            DebridResolver("tb-key", null, http.client).resolve(hashRow(), 2, 4),
        )
    }

    @Test
    fun `a TorBox 401 means the key is wrong`() = runTest {
        val http = torboxHttp(createCode = 401, createBody = """{"success":false}""")
        assertEquals(
            DebridOutcome.MissingApiKey,
            DebridResolver("tb-key", null, http.client).resolve(hashRow(), 2, 4),
        )
    }

    // MARK: - Real-Debrid

    @Test
    fun `Real-Debrid runs the whole flow and unrestricts the link`() = runTest {
        val http = realDebridHttp()
        val outcome = DebridResolver(null, "rd-key", http.client).resolve(hashRow(), 2, 4)
        assertTrue(outcome is DebridOutcome.Success)
        val resolved = (outcome as DebridOutcome.Success).resolved
        assertTrue(resolved.url.startsWith("https://sgp1.download.real-debrid.com/"))
        assertEquals("Real-Debrid", resolved.service)
        assertEquals("The.Bear.S02E04.1080p.WEB-DL.H.265-FLUX.mkv", resolved.filename)
        // addMagnet, info, selectFiles, info, unrestrict — and no delete, because it worked.
        assertEquals(5, http.requests.size)
        assertFalse(http.urls.any { it.contains("torrents/delete") })
    }

    @Test
    fun `Real-Debrid picks the episode file, not the biggest one`() = runTest {
        val http = realDebridHttp()
        DebridResolver(null, "rd-key", http.client).resolve(hashRow(), 2, 4)
        val select = http.requests.first { it.url.toString().contains("selectFiles") }
        val body = okio.Buffer().also { select.body?.writeTo(it) }.readUtf8()
        assertEquals("files=2", body)
    }

    @Test
    fun `a torrent that is not downloaded is not cached, and is deleted again`() = runTest { withContext(Dispatchers.IO) {
        val http = realDebridHttp(status = "magnet_conversion")
        val outcome = DebridResolver(null, "rd-key", http.client).resolve(hashRow(), 2, 4)
        assertEquals(DebridOutcome.NotCached, outcome)
        assertTrue(http.urls.any { it.contains("torrents/delete/ABCD1234EFGH") })
    } }

    // MARK: - Order and caching

    @Test
    fun `the owner's stated provider order is honoured`() = runTest {
        val http = FakeHttp { request ->
            val url = request.url.toString()
            when {
                url.contains("createtorrent") -> FakeAnswer(code = 409, body = "{}")
                url.contains("addMagnet") -> FakeAnswer(body = fixture("realdebrid-addmagnet.json"))
                url.contains("torrents/info") -> FakeAnswer(body = fixture("realdebrid-torrent-info.json"))
                url.contains("selectFiles") -> FakeAnswer(code = 202, body = "")
                url.contains("unrestrict/link") -> FakeAnswer(body = fixture("realdebrid-unrestrict-link.json"))
                else -> FakeAnswer(code = 404, body = "{}")
            }
        }
        val rules = PlaybackRules(
            enabled = true,
            debridPriority = listOf(DebridService.TORBOX, DebridService.REAL_DEBRID),
        )
        val outcome = DebridResolver("tb-key", "rd-key", http.client, rules).resolve(hashRow(), 2, 4)
        // TorBox said no, so Real-Debrid was asked next rather than the whole thing failing.
        assertTrue(outcome is DebridOutcome.Success)
        assertEquals("Real-Debrid", (outcome as DebridOutcome.Success).resolved.service)
        assertTrue(http.urls.first().contains("torbox"))
    }

    @Test
    fun `a second press inside the TTL costs no requests`() = runTest {
        val http = torboxHttp()
        val resolver = DebridResolver("tb-key", null, http.client)
        resolver.resolve(hashRow(), 2, 4)
        val after = http.requests.size
        resolver.resolve(hashRow(), 2, 4)
        assertEquals(after, http.requests.size)
    }

    @Test
    fun `clear forgets every resolved link`() = runTest {
        val http = torboxHttp()
        val resolver = DebridResolver("tb-key", null, http.client)
        resolver.resolve(hashRow(), 2, 4)
        val after = http.requests.size
        resolver.clear()
        resolver.resolve(hashRow(), 2, 4)
        assertTrue(http.requests.size > after)
    }

    // MARK: - Magnet and file selection

    @Test
    fun `the magnet keeps trackers and drops the dht pseudo-source`() {
        val magnet = requireNotNull(DebridMagnetBuilder.fromRow(hashRow()))
        assertTrue(magnet.startsWith("magnet:?xt=urn:btih:$hash"))
        assertTrue(magnet.contains("tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce"))
        assertFalse(magnet.contains("dht"))
    }

    @Test
    fun `a row with no hash has no magnet`() {
        assertNull(DebridMagnetBuilder.fromRow(directRow()))
    }

    @Test
    fun `file selection prefers the episode pattern over the biggest file`() {
        val files = listOf(
            DebridFile(0, "The.Bear.S02E03.1080p.mkv", 9_000_000_000),
            DebridFile(1, "The.Bear.S02E04.1080p.mkv", 4_000_000_000),
            DebridFile(2, "readme.txt", 42),
        )
        val picked = DebridFileSelection.select(files, DebridSelectionHint(season = 2, episode = 4))
        assertEquals(1, picked?.id)
    }

    @Test
    fun `file selection honors exact indices and refuses ambiguous largest file guesses`() {
        val files = listOf(
            DebridFile(0, "part1.mkv", 1_000),
            DebridFile(1, "part2.mkv", 9_000),
            DebridFile(2, "notes.txt", 10),
        )
        assertEquals(0, DebridFileSelection.select(files, DebridSelectionHint(fileIdx = 0))?.id)
        assertNull(DebridFileSelection.select(files, DebridSelectionHint()))
    }

    @Test
    fun `a torrent with no playable video picks nothing`() {
        val files = listOf(DebridFile(0, "readme.txt", 10), DebridFile(1, "cover.jpg", 20))
        assertNull(DebridFileSelection.select(files, DebridSelectionHint()))
    }

    @Test
    fun `a provider file index and name cannot substitute a different labelled episode`() {
        val files = listOf(DebridFile(0, "Show.S02E03.mkv", 100), DebridFile(1, "Show.S02E05.mkv", 120))
        val hint = DebridSelectionHint(filename = "Show.S02E05.mkv", fileIdx = 1, season = 2, episode = 4)
        assertNull(DebridFileSelection.select(files, hint))
        assertEquals(1, DebridFileSelection.select(files, hint.copy(episode = 5))?.id)
    }

    @Test
    fun `episode patterns cover the three forms release names use`() {
        assertEquals(listOf("s02e04", "2x04", "2x4"), DebridFileSelection.episodePatterns(2, 4))
        assertTrue(DebridFileSelection.episodePatterns(null, 4).isEmpty())
    }
}
