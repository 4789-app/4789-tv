package com.fourseveneightnine.tv.client.playback

import com.fourseveneightnine.tv.client.data.catalog.CatalogItem
import com.fourseveneightnine.tv.client.data.catalog.CatalogPlaybackSource
import com.fourseveneightnine.tv.client.data.streams.CachedHint
import com.fourseveneightnine.tv.client.data.streams.PlaybackRules
import com.fourseveneightnine.tv.client.data.streams.StreamFacts
import com.fourseveneightnine.tv.client.data.streams.StreamRow
import com.fourseveneightnine.tv.client.data.streams.StreamSearchState
import com.fourseveneightnine.tv.client.data.streams.debrid.DebridOutcome
import com.fourseveneightnine.tv.client.data.streams.debrid.DebridFile
import com.fourseveneightnine.tv.client.data.streams.debrid.Resolved
import com.fourseveneightnine.tv.client.data.streams.debrid.TorboxLibraryTorrent
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class PlayPlannerTest {
    @Test
    fun `TamilMV bundles list actual feature files from one edition in order`() {
        val shortCopy = "a".repeat(40)
        val fullCopy = "b".repeat(40)
        for (count in listOf(2, 3)) {
            val title = if (count == 2) "Mechanic Duology" else "Film Trilogy"
            val item = CatalogItem("saved:tamilmv:fixture", "movie", title, playbackSources = listOf(
                CatalogPlaybackSource(shortCopy, "$title 2160p BluRay"),
                CatalogPlaybackSource(fullCopy, "$title 1080p BluRay"),
            ))
            val movies = List(count) { index ->
                DebridFile(index, "Pack/Film ${index + 1} (${2011 + index}).mkv", 100L)
            }
            val files = tamilMVMovieBundleFiles(item, mapOf(
                shortCopy to listOf(movies.first()),
                fullCopy to movies.reversed() + listOf(
                    DebridFile(12, "Extras/Behind the Scenes.mp4", 100L),
                    DebridFile(13, "Film Sample.mkv", 100L),
                    DebridFile(14, "Subtitles.srt", 100L),
                ),
            ))
            assertEquals(movies.map { it.name.substringAfterLast('/') }, files.map { it.filename })
            assertEquals((0 until count).toList(), files.map { it.stream.torboxFileID })
            assertEquals((0 until count).map { "saved-torbox:$fullCopy:$it" }, files.map { it.stream.id })
            assertTrue(files.all { it.stream.exactSource && it.stream.infoHash == fullCopy && it.season == null })
        }
    }

    @Test
    fun `TamilMV preserves the preferred hash when TorBox confirms cache without a file list`() = runTest {
        val item = CatalogItem("saved:tamilmv:movie", "movie", "Film", playbackSources = listOf(
            CatalogPlaybackSource("web4k", "Film TRUE WEB-DL 4K"),
            CatalogPlaybackSource("web1080", "Film TRUE WEB-DL 1080p"),
        ))
        val movie = item.probeTamilMVMovie(
            filesForHash = { assertEquals("web4k", it); emptyList() },
            filesForHashes = { error("The confirmed 4K copy takes precedence") },
            availabilityForHash = { assertEquals("web4k", it); true },
        )
        assertEquals("web4k", movie?.infoHash)
        assertNull(movie?.torboxFileID)
        assertTrue(movie?.exactSource == true)
    }

    @Test
    fun `TamilMV reuses a ready library file for the preferred exact hash without a cache request`() = runTest {
        val item = CatalogItem("saved:tamilmv:movie", "movie", "Film", playbackSources = listOf(
            CatalogPlaybackSource("web4k", "Film TRUE WEB-DL 4K"),
            CatalogPlaybackSource("web1080", "Film TRUE WEB-DL 1080p"),
        ))
        val file = DebridFile(0, "Film.mkv", 100)
        val movie = item.probeTamilMVMovie(
            filesForHash = { error("The exact ready library file should be reused") },
            filesForHashes = { error("The exact ready library file should be reused") },
            library = listOf(
                TorboxLibraryTorrent(1, "other", "Film", null, true, listOf(file)),
                TorboxLibraryTorrent(2, "web4k", "Film", null, false, listOf(file)),
                TorboxLibraryTorrent(3, "WEB4K", "Film", null, true, listOf(file)),
            ),
        )
        assertEquals("web4k", movie?.infoHash)
        assertEquals(3, movie?.torboxTorrentID)
        assertEquals(0, movie?.torboxFileID)
    }

    @Test
    fun `TamilMV checks the preferred hash alone and only batches clean alternatives on a miss`() = runTest {
        val item = CatalogItem("saved:tamilmv:movie", "movie", "Film", playbackSources = listOf(
            CatalogPlaybackSource("web4k", "Film TRUE WEB-DL 4K"),
            CatalogPlaybackSource("web1080", "Film TRUE WEB-DL 1080p"),
            CatalogPlaybackSource("rip", "Film HQ HDRip 1080p"),
        ))
        val file = DebridFile(0, "Film.mkv", 100)
        val best = item.probeTamilMVMovie(
            filesForHash = { assertEquals("web4k", it); listOf(file) },
            filesForHashes = { error("A preferred cache hit should not check alternatives") },
        )
        assertEquals("web4k", best?.infoHash)
        assertEquals(0, best?.torboxFileID)
        val fallback = item.probeTamilMVMovie(
            filesForHash = { assertEquals("web4k", it); emptyList() },
            filesForHashes = { assertEquals(listOf("web1080"), it); mapOf("web1080" to listOf(file)) },
        )
        assertEquals("web1080", fallback?.infoHash)
    }

    @Test
    fun `TamilMV does not substitute a cached HDRip when a WEB-DL is listed`() {
        val item = CatalogItem("saved:tamilmv:movie", "movie", "Film", playbackSources = listOf(
            CatalogPlaybackSource("web4k", "Film TRUE WEB-DL 4K"),
            CatalogPlaybackSource("web1080", "Film TRUE WEB-DL 1080p"),
            CatalogPlaybackSource("rip", "Film HQ HDRip 1080p"),
        ))
        val files = listOf(DebridFile(1, "Film.mkv", 100))
        assertNull(item.cachedTamilMVMovie(mapOf("rip" to files)))
        assertEquals("web1080", item.cachedTamilMVMovie(mapOf("rip" to files, "web1080" to files))?.infoHash)
        assertEquals("web4k", item.cachedTamilMVMovie(mapOf("web4k" to files, "web1080" to files))?.infoHash)
    }

    @Test
    fun `TamilMV picks the best cached print and preserves a bound movie file`() {
        val item = CatalogItem("saved:tamilmv:movie", "movie", "Film", playbackSources = listOf(
            CatalogPlaybackSource("best", "4K", fileID = 7),
            CatalogPlaybackSource("alternate", "1080p"),
        ))
        val alternateFiles = listOf(DebridFile(1, "sample.mkv", 5), DebridFile(2, "film.mkv", 50))
        val bestFiles = listOf(DebridFile(7, "film.mkv", 60), DebridFile(8, "other film.mkv", 100))
        val best = item.cachedTamilMVMovie(mapOf("best" to bestFiles, "alternate" to alternateFiles))
        assertEquals("best", best?.infoHash)
        assertEquals(7, best?.torboxFileID)
        val fallback = item.cachedTamilMVMovie(mapOf("best" to bestFiles.drop(1), "alternate" to alternateFiles))
        assertEquals("alternate", fallback?.infoHash)
        assertEquals(2, fallback?.torboxFileID)
        assertNull(item.cachedTamilMVMovie(mapOf("unrelated" to bestFiles)))
    }

    @Test
    fun `persisted TorBox identity restores exact file zero without addon search`() {
        val hash = "0123456789abcdef0123456789abcdef01234567"
        val row = requireNotNull(savedTorboxFile("saved-torbox:$hash:0:42", "Saved filename.mkv"))
        assertEquals(hash, row.infoHash)
        assertEquals(0, row.torboxFileID)
        assertEquals(42, row.torboxTorrentID)
        assertTrue(row.exactSource)
        assertNull(savedTorboxFile("saved-torbox:$hash:bad:42", "Wrong"))
        assertNull(savedTorboxFile("tt1375666", "Ordinary title"))
    }
    @Test
    fun `saved item chooses only its bound episode and keeps TorBox file ID`() {
        val source = com.fourseveneightnine.tv.client.data.catalog.CatalogPlaybackSource(
            "0123456789abcdef0123456789abcdef01234567", "Show.S02E03.mkv", 77, 9, 2, listOf(3))
        val item = com.fourseveneightnine.tv.client.data.catalog.CatalogItem("saved:library:one", "series", "Show",
            playbackSources = listOf(source))
        assertNull(item.savedStream(2, 4))
        assertNull(item.savedStream(1, 3))
        val row = requireNotNull(item.savedStream(2, 3))
        assertEquals(77, row.torboxTorrentID)
        assertEquals(9, row.torboxFileID)
        assertTrue(row.exactSource)
    }

    private val autoPlayRules = PlaybackRules(
        enabled = true,
        autoPlay = true,
        autoPlayMinSources = 2,
        readyOnly = false,
    )

    @Test
    fun `Play waits for the favorite when a different source is already ranked`() = runTest {
        val planner = PlayPlanner(autoPlayRules, setOf("hevc"), "en")
        val available = rows(3)
        val favorite = FavoriteSource.from(available.last())
        val attempted = mutableListOf<String>()

        val result = planner.chooseWithFavorite(flowOf(
            StreamSearchState(rows = available.take(2), pending = 1),
            StreamSearchState(rows = available, done = true),
        ), favorite) {
            attempted += it.id
            true
        }

        assertEquals(PlayPlanner.Choice.FavoriteOpened, result)
        assertEquals(listOf("row-3"), attempted)
    }

    @Test
    fun `Play falls back to a different row when the favorite fails to open`() = runTest {
        val planner = PlayPlanner(autoPlayRules, setOf("hevc"), "en")
        val available = rows(3)
        val favorite = FavoriteSource.from(available.first())
        val result = planner.chooseWithFavorite(
            flowOf(StreamSearchState(rows = available, done = true)), favorite,
        ) { false } as? PlayPlanner.Choice.Ranked

        assertEquals("row-2", result?.row?.row?.id)
    }

    @Test
    fun `watchlist rematches the same provider and file after URL and headers rotate`() {
        val request = PlayRequest("series", "tt1", "The Bear", 2, 4)
        val stream = rows(1).single().copy(filename = "The.Bear.S02E04-FLUX.mkv",
            refreshAddonKey = "https://addon.example/private-key/manifest.json",
            url = "https://cdn.example/?signed=secret", headers = mapOf("Auth" to "private header"))
        val source = requireNotNull(WatchlistSource.from(request, stream))
        val saved = Json.encodeToString(source)
        val restored = Json.decodeFromString<WatchlistSource>(saved)
        val renewed = stream.copy(id = "different index", url = "https://new-cdn.example/other",
            headers = mapOf("Auth" to "new header"))
        assertTrue(listOf("https://", "private-key", "secret", "private header").none { it in saved })
        assertTrue(restored.matches(renewed))
        assertFalse(restored.matches(renewed.copy(filename = "The.Bear.S02E04-OTHER.mkv")))
        assertFalse(restored.matches(renewed.copy(refreshAddonKey = "another provider")))
        assertEquals(source.cardId("series"), WatchlistSource.from(request, renewed)?.cardId("series"))
        assertFalse(source.cardId("series") == source.copy(episode = 5).cardId("series"))
    }

    @Test fun `watchlist legacy service identities survive parsing the same structured provider response`() = runTest {
        var payload = ""
        val http = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("fixture")
                .body(with(okhttp3.ResponseBody.Companion) { payload.toResponseBody() }).build()
        }.build()
        val addon = com.fourseveneightnine.tv.client.data.addons.Addon("New mix", "https://addon.example/manifest.json")
        val client = com.fourseveneightnine.tv.client.data.addons.StremioClient(http)
        val request = PlayRequest("movie", "tt1", "Film")
        // Golden release keys from 78f1d688: identical JSON already carried structured service,
        // but only textual service markers participated in that version's parsed identity.
        val cases = listOf(
            Triple("Mixed fixture", "TorBox", "04a106098000b2d5878892efdbd724080d71fb76341eae1cfdaf1a5204fa63bd"),
            Triple("[TB+] 1080p", "torbox", "64bc9499fb2ff8bc4e43f422113cc2259d1c3f674eb0e9df2794906cdd02e75e"),
        )
        try {
            for ((label, service, key) in cases) {
                payload = """{"streams":[{"name":"$label","url":"https://cdn.example/file?token=old",
                    "behaviorHints":{"filename":"Film.2024.1080p.ENG.mkv","videoSize":800000000,
                    "videoHash":"0123456789abcdef","debridService":"$service","cached":true}}]}"""
                val row = client.streams(addon, "movie", "tt1").single()
                assertEquals("TorBox", row.cachedHint.service)
                assertTrue(row.cachedDebrid && row.readyHint)
                assertTrue(com.fourseveneightnine.tv.client.data.streams.StreamExpectation("Film", 2024, null, null, true).matches(row))
                val legacy = Json.decodeFromString<WatchlistSource>(
                    """{"catalogId":"tt1","catalogTitle":"Film","season":null,"episode":null,"releaseKey":"$key"}""")
                val renewed = row.copy(id = "new index", url = "https://new-cdn.example/file?token=new",
                    headers = mapOf("Authorization" to "fixture new token"))
                assertTrue(legacy.matches(renewed))
                assertFalse(legacy.matches(renewed.copy(refreshAddonKey = "https://other-addon.example")))
                assertFalse(legacy.matches(renewed.copy(filename = "Film.2024.1080p.OTHER.mkv")))
                assertFalse(legacy.matches(renewed.copy(sizeBytes = 800_000_001L)))
                assertFalse(legacy.matches(renewed.copy(facts = renewed.facts.copy(videoHash = "fedcba9876543210"))))
                val knownOtherService = requireNotNull(WatchlistSource.from(request,
                    row.copy(cachedHint = row.cachedHint.copy(service = "Real-Debrid"))))
                assertFalse(knownOtherService.matches(renewed))
            }
        } finally {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    @Test
    fun `watchlist reopens the bound torrent file without a search or previous account torrent ID`() {
        val hash = "0123456789abcdef0123456789abcdef01234567"
        val row = rows(1).single().copy(infoHash = hash, fileIdx = 1, torboxFileID = 4, torboxTorrentID = 100)
        val source = requireNotNull(WatchlistSource.from(PlayRequest("movie", "tt2", "Film"), row))
        val restored = requireNotNull(source.boundRow("Film"))
        assertTrue(restored.exactSource)
        assertEquals(hash, restored.infoHash)
        assertEquals(4, restored.torboxFileID)
        assertNull(restored.torboxTorrentID)
        assertNull(restored.url)
        val rd = row.copy(url = null, torboxFileID = null, torboxTorrentID = null)
        assertEquals(1, WatchlistSource.from(PlayRequest("movie", "tt2", "Film"), rd)?.boundRow("Film")?.fileIdx)
    }

    @Test
    fun `saved favorite keeps file identity and no playback credentials`() {
        val hash = "0123456789abcdef0123456789abcdef01234567"
        val stream = rows(1).single().copy(url = "https://example/path?token=secret",
            headers = mapOf("Authorization" to "private value"), infoHash = hash,
            torboxTorrentID = 10, torboxFileID = 0)
        val favorite = FavoriteSource.from(stream)
        val stored = Json.encodeToString(favorite)
        val restored = Json.decodeFromString<FavoriteSource>(stored)

        assertTrue(!stored.contains("secret") && !stored.contains("private value"))
        assertEquals(0, restored.exactRow("Film")?.torboxFileID)
        assertTrue(restored.matches(stream.copy(torboxTorrentID = 11)))
        assertTrue(!restored.matches(stream.copy(torboxFileID = 1)))
        assertTrue(StreamIds.favoriteScopes(PlayRequest("series", "tt1", "Show"), null).isEmpty())
        assertTrue(StreamIds.favoriteScopes(PlayRequest("series", "tt1", "Show", 1, 2), null) !=
            StreamIds.favoriteScopes(PlayRequest("series", "tt1", "Show", 1, 3), null))
    }

    @Test
    fun `commits to the best row once enough sources are in hand`() = runTest {
        val planner = PlayPlanner(autoPlayRules, setOf("hevc"), "en")

        val choice = planner.choose(flowOf(StreamSearchState(rows = rows(3), done = true)))

        assertNotNull(choice)
        assertTrue(choice!!.eligible)
    }

    @Test
    fun `does not commit while the rules say show the list`() = runTest {
        val planner = PlayPlanner(PlaybackRules.DEFAULT, emptySet(), "en")

        val choice = planner.choose(flowOf(StreamSearchState(rows = rows(3), done = true)))

        assertNull(choice)
    }

    @Test
    fun `waits for more sources before the search finishes`() = runTest {
        val planner = PlayPlanner(autoPlayRules.copy(autoPlayMinSources = 5), setOf("hevc"), "en")

        val choice = planner.choose(flowOf(StreamSearchState(rows = rows(3), pending = 2)))

        assertNull(choice)
    }

    @Test
    fun `plays the best available source when search finishes below the threshold`() = runTest {
        val planner = PlayPlanner(autoPlayRules.copy(autoPlayMinSources = 5), setOf("hevc"), "en")

        val choice = planner.choose(flowOf(StreamSearchState(rows = rows(2), done = true)))

        assertNotNull(choice)
        assertEquals("row-1", choice!!.row.id)
    }

    @Test
    fun `commits on the first state that is decidable, without waiting for done`() = runTest {
        val planner = PlayPlanner(autoPlayRules, setOf("hevc"), "en")
        var statesCollected = 0

        val choice = planner.choose(
            flow {
                statesCollected++
                emit(StreamSearchState(rows = rows(1), pending = 2))
                statesCollected++
                emit(StreamSearchState(rows = rows(3).map {
                    it.copy(identityVerified = true, cachedHint = CachedHint(true, "TorBox", verified = true))
                }, pending = 1))
                statesCollected++
                emit(StreamSearchState(rows = rows(4), done = true))
            },
        )

        assertNotNull(choice)
        // The third state is never asked for: the search is cancelled as soon as one row wins.
        assertEquals(2, statesCollected)
    }

    @Test
    fun `direct and claimed cache rows do not cancel a pending checked source`() = runTest {
        val direct = rows(3)
        val checked = direct.first().copy(id = "checked", identityVerified = true,
            cachedHint = CachedHint(true, "TorBox", verified = true))
        var collected = 0
        val choice = PlayPlanner(autoPlayRules, setOf("hevc"), "en").choose(flow {
            collected++; emit(StreamSearchState(rows = direct, pending = 1))
            collected++; emit(StreamSearchState(rows = direct + checked, done = true))
        })
        assertEquals(2, collected)
        assertEquals("checked", choice?.row?.id)
    }

    @Test
    fun `failed media inspection tries the next ranked candidate instead of opening a broken link`() = runTest {
        val inspected = mutableListOf<String>()
        val planner = PlayPlanner(autoPlayRules, setOf("hevc"), "en") { candidate ->
            inspected += candidate.id
            candidate.takeIf { it.id == "row-2" }
        }
        val choice = planner.choose(flowOf(StreamSearchState(rows = rows(3), done = true)))
        assertEquals("row-2", choice?.row?.id)
        assertEquals(listOf("row-1", "row-2"), inspected)
    }

    @Test fun `player rejection after a successful probe opens the next eligible source`() = runTest {
        val opened = mutableListOf<String>()
        val choice = PlayPlanner(autoPlayRules, setOf("hevc"), "en").chooseWithFavorite(
            flowOf(StreamSearchState(rows = rows(3), done = true)),
            openRanked = { opened += it.id; it.id == "row-2" },
        ) as? PlayPlanner.Choice.Ranked
        assertEquals(listOf("row-1", "row-2"), opened)
        assertEquals("row-2", choice?.row?.row?.id)
    }

    @Test
    fun `an empty search commits to nothing`() = runTest {
        val planner = PlayPlanner(autoPlayRules, emptySet(), "en")

        assertNull(planner.choose(flowOf(StreamSearchState(done = true))))
    }

    @Test
    fun `every debrid outcome has the sentence API-A gives it`() {
        assertNull(PlayPlanner.failure(DebridOutcome.Success(Resolved(url = "https://example/one"))))
        assertEquals(
            "Add a debrid key in Settings.",
            PlayPlanner.failure(DebridOutcome.MissingApiKey)?.message,
        )
        assertEquals(
            "That copy is not on your debrid account yet.",
            PlayPlanner.failure(DebridOutcome.NotCached)?.message,
        )
        assertEquals(
            "That source did not answer. Try another.",
            PlayPlanner.failure(DebridOutcome.Stale)?.message,
        )
        assertEquals(
            "That source did not answer. Try another.",
            PlayPlanner.failure(DebridOutcome.Error)?.message,
        )
    }

    @Test
    fun `wrong audio cannot win before original audio arrives`() = runTest {
        val planner = PlayPlanner(autoPlayRules.copy(autoPlayMinSources = 1), emptySet(), "ko")
        val dub = rows(1).single()
        val original = dub.copy(id = "original", cachedHint = CachedHint(false),
            facts = dub.facts.copy(reportedAudioLanguages = listOf("Korean")))
        var collected = 0
        val choice = planner.choose(flow {
            collected++; emit(StreamSearchState(rows = listOf(dub), pending = 1))
            collected++; emit(StreamSearchState(rows = listOf(dub, original), done = true))
        })
        assertEquals(2, collected)
        assertEquals("original", choice?.row?.id)
    }

    @Test
    fun `never empty ranking does not restore dubbed sources`() = runTest {
        assertNull(PlayPlanner(autoPlayRules, emptySet(), "ko").choose(
            flowOf(StreamSearchState(rows = rows(3), done = true))))
        assertNull(PlayPlanner(autoPlayRules, emptySet(), null).choose(
            flowOf(StreamSearchState(rows = rows(3), done = true))))
    }

    @Test
    fun `track aliases agree with original language metadata`() {
        assertEquals("te", canonicalTrackLanguage("tel-IN"))
        assertEquals("de", canonicalTrackLanguage("ger"))
        assertEquals("cn", canonicalTrackLanguage("yue"))
        assertNull(canonicalTrackLanguage("und"))
    }

    private fun rows(count: Int): List<StreamRow> = (1..count).map { index ->
        StreamRow(
            id = "row-$index",
            addonName = "AIOStreams",
            title = "1080p",
            releaseName = "The.Bear.S02E04.1080p.WEB-DL-FLUX",
            url = "https://example/$index",
            quality = "1080p",
            sizeBytes = 4_000_000_000L,
            codecs = listOf("HEVC"),
            cachedHint = CachedHint(cached = true, service = "Real-Debrid"),
            facts = StreamFacts(quality = "1080p", sizeBytes = 4_000_000_000L, codecs = listOf("HEVC"), reportedAudioLanguages = listOf("English")),
        )
    }
}
