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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebridResolverTest {
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
    fun `saved TorBox file uses exact IDs without creating a torrent or falling back`() = runTest {
        val http = torboxHttp()
        val row = hashRow().copy(torboxTorrentID = 91823, torboxFileID = 0, exactSource = true)
        val result = DebridResolver("key", null, http.client).resolve(row, 2, 4)
        assertTrue(result is DebridOutcome.Success)
        assertEquals(2, http.urls.size)
        assertFalse(http.urls.any { it.contains("createtorrent") })
        assertTrue(http.urls.last().contains("file_id=0"))
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
    fun `a torrent that is not downloaded is not cached, and is deleted again`() = runTest {
        val http = realDebridHttp(status = "magnet_conversion")
        val outcome = DebridResolver(null, "rd-key", http.client).resolve(hashRow(), 2, 4)
        assertEquals(DebridOutcome.NotCached, outcome)
        assertTrue(http.urls.any { it.contains("torrents/delete/ABCD1234EFGH") })
    }

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
    fun `file selection falls back to the file index, then to the biggest playable file`() {
        val files = listOf(
            DebridFile(0, "part1.mkv", 1_000),
            DebridFile(1, "part2.mkv", 9_000),
            DebridFile(2, "notes.txt", 10),
        )
        assertEquals(0, DebridFileSelection.select(files, DebridSelectionHint(fileIdx = 0))?.id)
        assertEquals(1, DebridFileSelection.select(files, DebridSelectionHint())?.id)
    }

    @Test
    fun `a torrent with no playable video picks nothing`() {
        val files = listOf(DebridFile(0, "readme.txt", 10), DebridFile(1, "cover.jpg", 20))
        assertNull(DebridFileSelection.select(files, DebridSelectionHint()))
    }

    @Test
    fun `episode patterns cover the three forms release names use`() {
        assertEquals(listOf("s02e04", "2x04", "2x4"), DebridFileSelection.episodePatterns(2, 4))
        assertTrue(DebridFileSelection.episodePatterns(null, 4).isEmpty())
    }
}
