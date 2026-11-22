package com.fourseveneightnine.tv.client.playback

import com.fourseveneightnine.tv.client.data.addons.SubtitleSearchResult
import com.fourseveneightnine.tv.client.data.meta.Episode
import com.fourseveneightnine.tv.client.data.meta.Meta
import com.fourseveneightnine.tv.client.data.catalog.CatalogItem
import com.fourseveneightnine.tv.client.data.catalog.CatalogPlaybackSource
import com.fourseveneightnine.tv.client.data.streams.StreamRow
import com.fourseveneightnine.tv.client.data.streams.StreamFacts
import com.fourseveneightnine.tv.client.data.streams.SubtitleTrack
import com.fourseveneightnine.tv.client.data.streams.debrid.DebridFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.fourseveneightnine.tv.protocol.ReceiverTrack
import com.fourseveneightnine.tv.protocol.ReceiverTracks
import com.fourseveneightnine.tv.player.PlaybackDiagnostics
import com.fourseveneightnine.tv.client.data.streams.HdrFormat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class NextUpTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `automatic subtitle ranks nearby replies without waiting for a slow provider`() = runTest {
        val generic = SubtitleTrack("generic", "https://fixture.invalid/generic.vtt", "en", "Fast")
        val forced = generic.copy(id = "forced", isForced = true)
        val exact = generic.copy(id = "matching", url = "https://fixture.invalid/matching.vtt",
            release = "Show.S01E02.1080p.WEB")
        val candidates = flow {
            emit(SubtitleSearchResult(listOf(forced), completed = 1, total = 4))
            delay(500)
            emit(SubtitleSearchResult(listOf(forced, generic), completed = 2, total = 4))
            delay(150)
            emit(SubtitleSearchResult(listOf(forced, generic, exact), completed = 3, total = 4))
            delay(10_000)
            emit(SubtitleSearchResult(listOf(forced, generic, exact), completed = 4, total = 4))
        }.automaticSubtitleCandidates("Show.S01E02.1080p.WEB.mkv").first()

        assertEquals(listOf(exact, generic), candidates)
        assertEquals(900L, testScheduler.currentTime)
    }

    @Test fun `automatic subtitle can still try a later match when the fast download fails`() = runTest {
        val fast = SubtitleTrack("fast", "https://fixture.invalid/fast.vtt", "en", "Fast")
        val later = fast.copy(id = "later", url = "https://fixture.invalid/later.vtt")
        val received = mutableListOf<List<SubtitleTrack>>()
        val match = flow {
            emit(SubtitleSearchResult(listOf(fast), completed = 1, total = 2))
            delay(5_000)
            emit(SubtitleSearchResult(listOf(fast, later), completed = 2, total = 2))
        }.automaticSubtitleCandidates(null).first { candidates ->
            received += candidates
            later in candidates
        }

        assertEquals(listOf(listOf(fast), listOf(fast, later)), received)
        assertEquals(listOf(fast, later), match)
    }

    @Test fun `automatic subtitles skip selected forced and unsupported English tracks`() {
        val forced = ReceiverTrack(0, "eng", "English (Forced)", selected = true)
        val flagged = ReceiverTrack(1, "en", "Track 2", isForced = true)
        val unsupported = ReceiverTrack(2, "en", "English", isSupported = false)
        val sdh = ReceiverTrack(3, "", "English SDH")
        val spanish = ReceiverTrack(4, "spa", "Spanish full")
        val tracks = listOf(forced, flagged, unsupported, spanish, sdh)
        assertEquals(sdh, automaticSubtitleTrack(tracks, "en", null))
        assertEquals(spanish, automaticSubtitleTrack(tracks, "es", null))
        assertNull(automaticSubtitleTrack(listOf(forced, flagged, unsupported, spanish), "en", null))
    }

    @Test fun `continuity follows selected 4K HDR audio layout and subtitle variant across changed indices`() {
        val audio = ReceiverTrack(1, "ta", "Tamil surround", "audio/eac3", 6, selected = true)
        val spanish = ReceiverTrack(2, "es", "Spanish full", selected = true)
        val original = StreamRow("old", "My addon", "Film", "Film", quality = "4K")
        val tracks = ReceiverTracks(listOf(audio), listOf(spanish))
        val format = PlaybackDiagnostics(width = 3840, height = 2160, hdrTransfer = "PQ", videoCodec = "video/hevc")
        val pref = EpisodePreference.playing(original, tracks, format)
        val good = original.copy(id = "match", codecs = listOf("HEVC"), hdr = HdrFormat.HDR10, audioLanguages = listOf("Tamil"),
            facts = StreamFacts(audioCodecs = listOf("E-AC-3"), audioChannels = "5.1", subtitleLanguages = listOf("Spanish")))
        val stereo = good.copy(id = "stereo", facts = good.facts.copy(audioCodecs = listOf("AAC"), audioChannels = "2.0"))
        val noSpanish = good.copy(id = "no-es", facts = good.facts.copy(subtitleLanguages = listOf("English")))
        val hd = good.copy(id = "1080", quality = "1080p")
        assertEquals("match", pref.preferred(listOf(stereo, noSpanish, hd, good)).first().id)
        assertEquals(false, pref.earlyMatch(hd))
        assertEquals(false, pref.earlyMatch(noSpanish))
        assertEquals(7, pref.audioTrack(listOf(audio.copy(index = 1, name = "Stereo", channels = 2, codec = "AAC"),
            audio.copy(index = 7)), "ta")?.index)
        assertEquals(9, pref.subtitleTrack(listOf(spanish.copy(index = 2, name = "Spanish forced"), spanish.copy(index = 9)))?.index)
        assertNull(pref.subtitleTrack(listOf(spanish.copy(language = "en"))))
        val off = EpisodePreference.playing(original, tracks.copy(subtitles = listOf(spanish.copy(selected = false))), format)
        assertEquals(true, off.subtitlesOff)
        assertNull(off.subtitleTrack(listOf(spanish)))
    }

    @Test fun `community timing uses only public episode coordinates and measured runtime`() = runTest {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("api.theintrodb.org", request.url.host)
            assertEquals("123", request.url.queryParameter("tmdb_id"))
            assertEquals("1", request.url.queryParameter("season"))
            assertEquals("7", request.url.queryParameter("episode"))
            assertEquals("3176000", request.url.queryParameter("duration_ms"))
            assertNull(request.header("Authorization"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"type":"tv","season":1,"episode":7,"group_duration_ms":3175000,"credits":[{"start_ms":2921000,"end_ms":null}]}""".toResponseBody()).build()
        }.build()
        val start = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            NextUp.creditsForEpisode(http, "tmdb:123", 1, 7, 3176000)
        }
        assertEquals(2921.0, requireNotNull(start), 0.001)
        assertNull(NextUp.creditsForEpisode(http, "saved:private-id", 1, 7, 3176000))
    }

    @Test fun `credits preview uses the matching episode cut and leaves time for the rest of the credits`() {
        val response = """{"type":"tv","season":1,"episode":6,"group_duration_ms":3557000,
            "credits":[{"start_ms":2800000,"end_ms":2890000},{"start_ms":3283177,"end_ms":null}]}"""
        val start = NextUp.terminalCredits(response, 1, 6, 3557000)
        assertEquals(3283.177, requireNotNull(start), 0.001)
        assertNull(NextUp.remainingSeconds(3283.0, 3557.0, start))
        assertEquals(274, NextUp.remainingSeconds(3283.2, 3557.0, start))
        assertNull(NextUp.terminalCredits(response, 1, 7, 3557000))
        assertNull(NextUp.terminalCredits(response, 1, 6, 3300000))
        assertNull(NextUp.terminalCredits(response.replace("null", "3300000"), 1, 6, 3557000))
    }


    private val videos = listOf(
        episode(0, 1, "Behind the scenes"),
        episode(1, 1, "System"),
        episode(1, 2, "Hands"),
        episode(1, 3, "Brigade"),
        episode(2, 1, "Beef"),
        episode(2, 2, "Pasta"),
    )

    private val show = Meta(id = "tt7366338", type = "series", title = "The Bear", videos = videos)

    @Test
    fun `mid-season gives the next episode`() {
        assertEquals(episode(1, 2, "Hands"), NextUp.nextEpisode(videos, season = 1, episode = 1))
    }

    @Test
    fun `the last episode of a season crosses into the next one`() {
        assertEquals(episode(2, 1, "Beef"), NextUp.nextEpisode(videos, season = 1, episode = 3))
    }

    @Test
    fun `the last episode of the last season has no next`() {
        assertNull(NextUp.nextEpisode(videos, season = 2, episode = 2))
    }

    @Test
    fun `a special is never next, and never the episode after one`() {
        // Season 0 is not in the ordered run at all, so it cannot be reached or landed on.
        assertNull(NextUp.nextEpisode(videos, season = 0, episode = 1))
        assertEquals(listOf(1, 1, 1, 2, 2), NextUp.ordered(videos).map(Episode::season))
    }

    @Test
    fun `an episode the list does not hold gets no guess`() {
        assertNull(NextUp.nextEpisode(videos, season = 9, episode = 9))
    }

    @Test
    fun `order comes from the numbers, not from the array`() {
        val shuffled = videos.reversed()
        assertEquals(episode(2, 1, "Beef"), NextUp.nextEpisode(shuffled, season = 1, episode = 3))
    }

    @Test
    fun `compute carries the show's own artwork and title`() {
        val plan = NextUp.compute(show, season = 1, episode = 3)

        assertEquals("tt7366338", plan?.canonicalId)
        assertEquals("The Bear", plan?.showTitle)
        assertEquals(2, plan?.season)
        assertEquals(1, plan?.episode)
        assertEquals("S2 E1 · Beef", plan?.line)
    }

    @Test
    fun `a movie has no next episode`() {
        val movie = Meta(id = "tt0111161", type = "movie", title = "The Shawshank Redemption")
        assertNull(NextUp.compute(movie, season = null, episode = null))
    }

    @Test
    fun `the player offers an aired next episode but never a future episode`() {
        val next = episode(1, 2, "Next")
        val series = show.copy(videos = listOf(episode(1, 1, "Current"), next.copy(released = "2000-01-01")))
        assertEquals("S1 E2 · Next", NextUp.compute(series, 1, 1)?.line)
        assertNull(NextUp.compute(series.copy(videos = listOf(
            episode(1, 1, "Current"), next.copy(released = "2999-01-01T00:00:00Z"))), 1, 1))
        assertNull(NextUp.compute(series, 1, 2))
    }

    @Test
    fun `the subtitle drops the half that is missing`() {
        assertEquals("S2 E4 · Honeydew", NextUp.subtitle(2, 4, "Honeydew"))
        assertEquals("S2 E4", NextUp.subtitle(2, 4, null))
        assertEquals("S2 E4", NextUp.subtitle(2, 4, "  "))
        assertEquals("Honeydew", NextUp.subtitle(null, null, "Honeydew"))
        assertEquals("", NextUp.subtitle(null, null, null))
    }

    @Test fun `Part Timers E04 to E05 uses the catalog file despite unordered provider IDs`() {
        val pack = CatalogItem("saved:tamilmv:part-timers", "series", "Part-Timers", season = 1,
            playbackSources = listOf(CatalogPlaybackSource("pack", "Part-Timers season 1", season = 1)))
        val request = PlayRequest("series", pack.canonicalId, pack.title, 1, 4)
        fun file(id: Int, episode: Int) = DebridFile(id, "Part-Timers S01E0$episode.mkv", 100)
        val files = mapOf("pack" to listOf(file(91, 6), file(17, 5), file(33, 4)))
        val plan = requireNotNull(NextUp.fromRelease(request, null, tamilMVEpisodeFiles(pack, files)))
        val manual = tamilMVEpisodeFiles(pack, files).single { it.episode == 5 }.stream
        val auto = tamilMVEpisodeFiles(pack, files, allCopies = true)
            .single { it.season == plan.season && it.episode == plan.episode }.stream
        assertEquals(17, auto.torboxFileID)
        assertEquals(manual.infoHash to manual.torboxFileID, auto.infoHash to auto.torboxFileID)
        val partial = mapOf("pack" to listOf(file(91, 6), file(33, 4)))
        assertEquals(5, NextUp.fromRelease(request, null, tamilMVEpisodeFiles(pack, partial))?.episode)
        assertNull(NextUp.nextEpisode(listOf(episode(1, 4, ""), episode(3, 2, "")), 1, 4))
        assertNull(NextUp.fromRelease(request.copy(episode = 6), null, tamilMVEpisodeFiles(pack, files)))
    }

    @Test fun `same addon binge group then language resolution and audio select continuity`() {
        val old = StreamRow("e4", "A", "Show", "Show S01E04", refreshAddonKey = "A1",
            bingeGroup = "1080-ta", quality = "1080p", audioLanguages = listOf("Tamil"),
            facts = StreamFacts(audioCodecs = listOf("E-AC-3")))
        val matched = old.copy(id = "match")
        val foreign = old.copy(id = "foreign", refreshAddonKey = "A2", quality = "2160p")
        val bestAudio = old.copy(id = "eac3", bingeGroup = null)
        val downgrade = bestAudio.copy(id = "aac", facts = StreamFacts(audioCodecs = listOf("AAC")))
        val wrongLanguage = bestAudio.copy(id = "english", audioLanguages = listOf("English"))
        val preference = EpisodePreference.from(old, "ta", "audio/eac3")
        assertEquals(true, preference.acceptsLanguage(matched.copy(audioLanguages = emptyList()), "ta"))
        assertEquals(false, preference.acceptsLanguage(matched.copy(audioLanguages = listOf("English")), "ta"))
        val rows = listOf(wrongLanguage, downgrade, foreign, bestAudio, matched)
        assertEquals(listOf("match", "eac3", "aac", "foreign", "english"),
            preference.preferred(rows).map { it.id })
    }

    @Test fun `preview and last ten seconds use measured episode time`() {
        assertNull(NextUp.remainingSeconds(1469.9, 1500.0))
        assertEquals(30, NextUp.remainingSeconds(1470.0, 1500.0))
        assertEquals(10, NextUp.remainingSeconds(1490.1, 1500.0))
        assertEquals(1, NextUp.remainingSeconds(1499.5, 1500.0))
        assertNull(NextUp.remainingSeconds(20.0, 0.0))
    }

    private fun episode(season: Int, number: Int, title: String) = Episode(
        season = season,
        episode = number,
        title = title,
        id = "tt7366338:$season:$number",
    )
}
