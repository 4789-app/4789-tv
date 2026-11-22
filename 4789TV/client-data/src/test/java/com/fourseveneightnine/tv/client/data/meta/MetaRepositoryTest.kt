package com.fourseveneightnine.tv.client.data.meta

import com.fourseveneightnine.tv.client.data.FakeAnswer
import com.fourseveneightnine.tv.client.data.FakeHttp
import com.fourseveneightnine.tv.client.data.addons.AddonRegistry
import com.fourseveneightnine.tv.client.data.addons.InMemoryAddonHealthStore
import com.fourseveneightnine.tv.client.data.addons.StremioClient
import com.fourseveneightnine.tv.client.data.fixture
import com.fourseveneightnine.tv.client.data.settings.SettingsDocument
import com.fourseveneightnine.tv.client.data.streams.StreamExpectation
import com.fourseveneightnine.tv.client.data.streams.StreamRow
import com.fourseveneightnine.tv.client.data.streams.debrid.DebridResolver
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

    @Test fun `calendar discovers each region and retains dated future episodes across a season boundary`() = runBlocking {
        val month = java.time.YearMonth.of(2026, 11)
        val http = FakeHttp { request -> when (request.url.encodedPath) {
            "/3/discover/tv" -> {
                assertEquals("2026-11-01", request.url.queryParameter("air_date.gte"))
                assertEquals("2026-11-30", request.url.queryParameter("air_date.lte"))
                assertEquals("popularity.desc", request.url.queryParameter("sort_by"))
                assertEquals("false", request.url.queryParameter("include_adult"))
                FakeAnswer(body = """{"results":[{"id":42},{"id":42}]}""")
            }
            "/3/trending/tv/week" -> FakeAnswer(body = """{"results":[
                {"id":42,"original_language":"en"},{"id":99,"original_language":"ja","origin_country":["JP"]}]}""")
            "/3/tv/42" -> FakeAnswer(body = """{"id":42,"name":"Calendar series","first_air_date":"2024-01-01",
                "poster_path":"/poster.jpg","backdrop_path":"/backdrop.jpg","external_ids":{"imdb_id":"tt42"},
                "seasons":[{"season_number":1,"air_date":"2024-01-01"},{"season_number":2,"air_date":"2026-10-15"},
                    {"season_number":3,"air_date":"2026-11-20"},{"season_number":4,"air_date":"2026-12-01"}],
                "watch/providers":{"results":{"US":{"flatrate":[{"provider_id":8,"provider_name":"Netflix","logo_path":"/n.png"} ]}}},
                "next_episode_to_air":{"season_number":3,"episode_number":1,"name":"Premiere","air_date":"2026-11-20"}}""")
            "/3/tv/42/season/2" -> FakeAnswer(body = """{"episodes":[
                {"season_number":2,"episode_number":1,"name":"Aired","air_date":"2026-11-03"},
                {"season_number":2,"episode_number":2,"name":"Future","air_date":"2026-11-30","still_path":"/future.jpg","overview":"Episode background"},
                {"season_number":2,"episode_number":3,"air_date":null},
                {"season_number":2,"episode_number":4,"air_date":"2026-12-01"},
                {"season_number":2,"episode_number":2501,"name":"Long-running serial","air_date":"2026-11-21"},
                {"season_number":2,"episode_number":0,"air_date":"2026-11-21"},
                {"season_number":0,"episode_number":1,"air_date":"2026-11-21"}]}""")
            "/3/tv/42/season/3" -> FakeAnswer(body = """{"episodes":[
                {"season_number":3,"episode_number":1,"name":"Premiere","air_date":"2026-11-20"}]}""")
            else -> error("Unexpected calendar endpoint ${request.url.encodedPath}")
        } }
        val schedule = repository(http, tmdbKey = "key").calendar(month, emptyList())
        assertFalse(schedule.incomplete)
        assertTrue(schedule.discoveryAvailable)
        val meta = schedule.series.single()
        assertEquals("tt42", meta.id)
        assertEquals("2024-01-01", meta.releaseDate)
        assertEquals(listOf("Netflix"), meta.streamingProviders.map { it.name })
        assertEquals(setOf(2 to 1, 2 to 2, 2 to 2501, 3 to 1), meta.videos.map { it.season to it.episode }.toSet())
        val future = meta.videos.single { it.season == 2 && it.episode == 2 }
        assertEquals("tt42:2:2", future.id)
        assertEquals("Episode background", future.overview)
        assertEquals("https://image.tmdb.org/t/p/w500/future.jpg", future.thumbnail)
        val filters = http.requests.filter { it.url.encodedPath == "/3/discover/tv" }
        assertEquals(3, filters.size)
        assertEquals(setOf("IN", "KR"), filters.mapNotNull { it.url.queryParameter("with_origin_country") }.toSet())
        assertEquals(1, filters.count { it.url.queryParameter("with_original_language") == "en" })
        assertEquals(1, http.requests.count { it.url.encodedPath == "/3/tv/42" })
        assertEquals(setOf("/3/tv/42/season/2", "/3/tv/42/season/3"),
            http.requests.map { it.url.encodedPath }.filter { "/season/" in it }.toSet())
    }

    @Test fun `calendar refresh reads changed schedules instead of the detail cache`() = runBlocking {
        var episodeDate = "2026-11-20"
        val http = FakeHttp { request -> when (request.url.encodedPath) {
            "/3/discover/tv" -> FakeAnswer(body = """{"results":[{"id":42}]}""")
            "/3/trending/tv/week" -> FakeAnswer(body = """{"results":[]}""")
            "/3/tv/42" -> FakeAnswer(body = """{"id":42,"name":"Calendar series","external_ids":{"imdb_id":"tt42"},
                "seasons":[{"season_number":1,"air_date":"2026-11-01"}]}""")
            "/3/tv/42/season/1" -> FakeAnswer(body = """{"episodes":[
                {"season_number":1,"episode_number":1,"name":"Moved","air_date":"$episodeDate"}]}""")
            else -> error("Unexpected calendar endpoint")
        } }
        val repo = repository(http, tmdbKey = "key")
        val month = java.time.YearMonth.of(2026, 11)
        assertEquals("2026-11-20", repo.calendar(month, emptyList()).series.single().videos.single().released)
        episodeDate = "2026-11-27"
        assertEquals("2026-11-27", repo.calendar(month, emptyList()).series.single().videos.single().released)
        assertEquals(2, http.requests.count { it.url.encodedPath == "/3/tv/42/season/1" })
    }

    @Test fun `calendar keeps other regions when one discovery provider fails`() = runBlocking {
        val http = FakeHttp { request -> when (request.url.encodedPath) {
            "/3/discover/tv" -> if (request.url.queryParameter("with_origin_country") == "IN") FakeAnswer(code = 503)
                else FakeAnswer(body = """{"results":[{"id":42}]}""")
            "/3/trending/tv/week" -> FakeAnswer(body = """{"results":[]}""")
            "/3/tv/42" -> FakeAnswer(body = """{"id":42,"name":"Available series",
                "next_episode_to_air":{"season_number":1,"episode_number":1,"air_date":"2026-11-20"}}""")
            "/3/tv/42/season/1" -> FakeAnswer(code = 503)
            else -> error("Unexpected calendar endpoint")
        } }
        val schedule = repository(http, tmdbKey = "key").calendar(java.time.YearMonth.of(2026, 11), emptyList())
        assertTrue(schedule.incomplete)
        assertEquals("2026-11-20", schedule.series.single().videos.single().released)
    }

    @Test fun `calendar still reads followed addon series without a TMDB key`() = runBlocking {
        val http = FakeHttp { FakeAnswer(body = fixture("cinemeta-series.json")) }
        val schedule = repository(http).calendar(java.time.YearMonth.of(2026, 11), listOf("tt7366338"))
        assertFalse(schedule.discoveryAvailable)
        assertFalse(schedule.incomplete)
        assertEquals("Chernobyl", schedule.series.single().title)
        assertTrue(http.requests.none { it.url.host == "tmdb.test" })
    }

    @Test fun `person credits combine cast and crew without merging movie and series IDs`() = runTest {
        val http = FakeHttp { request -> when (request.url.encodedPath) {
            "/3/search/person" -> {
                assertEquals("Selvamani Selvaraj", request.url.queryParameter("query"))
                FakeAnswer(body = """{"results":[{"id":5,"name":"Selvamani Selvaraj"}]}""")
            }
            "/3/person/5/combined_credits" -> FakeAnswer(body = """{"cast":[
                {"id":2,"media_type":"movie","title":"Feature","release_date":"2025-11-14","poster_path":"/a.jpg"},
                {"id":2,"media_type":"tv","name":"Series with same number","first_air_date":"2026-01-08"},
                {"id":9,"media_type":"movie","title":"Adult credit","adult":true}],"crew":[
                {"id":2,"media_type":"movie","title":"Feature","job":"Director"},
                {"id":3,"media_type":"tv","name":"Produced series","job":"Producer"},
                {"id":4,"media_type":"person","name":"Unsupported record"}]}""")
            else -> error("Unexpected credit endpoint")
        } }
        val titles = requireNotNull(repository(http, tmdbKey = "key").personCredits("Selvamani Selvaraj"))
        assertEquals(listOf("Series with same number", "Feature", "Produced series"), titles.map { it.title })
        assertEquals(listOf("series" to "tmdb:2", "movie" to "tmdb:2", "series" to "tmdb:3"),
            titles.map { it.type to it.id })
        assertEquals("https://image.tmdb.org/t/p/w342/a.jpg", titles[1].poster)
    }

    @Test fun `known person ID bypasses ambiguous names`() = runTest {
        val http = FakeHttp { FakeAnswer(body = """{"cast":[],"crew":[]}""") }
        assertEquals(emptyList<MetaRef>(), repository(http, tmdbKey = "key").personCredits("Same Name", personID = 7))
        assertEquals("/3/person/7/combined_credits", http.requests.single().url.encodedPath)
    }

    @Test fun `ambiguous person names do not combine unrelated credits`() = runTest {
        val http = FakeHttp { FakeAnswer(body = """{"results":[{"id":1,"name":"Same Name"},{"id":2,"name":"Same Name"}]}""") }
        assertNull(repository(http, tmdbKey = "key").personCredits("Same Name"))
        assertEquals(1, http.requests.size)
    }

    @Test fun `Fanart ranks posters by language and likes and sends the key only as a header`() = runTest {
        val fixtureKey = "test-only-fanart"
        val http = FakeHttp { request ->
            assertEquals("webservice.fanart.tv", request.url.host)
            assertEquals(fixtureKey, request.header("client-key"))
            assertNull(request.url.queryParameter("client_key"))
            assertNull(request.url.queryParameter("api_key"))
            when (request.url.encodedPath) {
                "/v3.2/tv/42" -> {
                    assertEquals("tmdb", request.url.queryParameter("source"))
                    FakeAnswer(body = """{"tvposter":[
                        {"url":"https://assets.fanart.tv/fanart/tv/1/high.jpg","lang":"fr","likes":"99"},
                        {"url":"https://assets.fanart.tv/fanart/tv/1/english.jpg","lang":"en","likes":"9"},
                        {"url":"https://assets.fanart.tv/fanart/tv/1/english-more.jpg","lang":"en","likes":"20"},
                        {"url":"https://assets.fanart.tv/fanart/tv/1/neutral.jpg","lang":"00","likes":"45"},
                        {"url":"https://other.invalid/fanart/wrong.jpg","lang":"en","likes":"100"}]}""")
                }
                "/v3.2/movies/tt21427" -> {
                    assertEquals("imdb", request.url.queryParameter("source"))
                    FakeAnswer(body = """{"movieposter":[{"url":"https://assets.fanart.tv/fanart/movies/1/movie.jpg"}]}""")
                }
                else -> error("Unexpected Fanart title lookup")
            }
        }
        val fanart = com.fourseveneightnine.tv.client.data.images.FanartPosters(http.client, fixtureKey)
        val series = fanart.posters("series", "local:title", 42)
        assertEquals(listOf("english-more.jpg", "english.jpg", "neutral.jpg", "high.jpg"),
            series.map { it.substringAfterLast('/') })
        assertEquals(series, fanart.posters("series", "tmdb:42", null))
        assertEquals(emptyList<String>(), fanart.posters("series", "unknown-title", null))
        assertEquals("movie.jpg", fanart.posters("movie", "tt21427", null).single().substringAfterLast('/'))
        assertEquals(2, http.requests.size)
    }

    @Test fun `streaming logos use the selected country and exclude rental only offers`() = runTest {
        val http = FakeHttp { FakeAnswer(body = """{"id":42,"title":"Kaantha",
            "credits":{"crew":[{"id":5,"name":"Selvamani Selvaraj","job":"Director"}]},
            "created_by":[{"id":7,"name":"A creator"}],
            "watch/providers":{"results":{"US":{"flatrate":[
                {"provider_id":8,"provider_name":"Netflix","logo_path":"/netflix.png","display_priority":1}],
                "ads":[{"provider_id":8,"provider_name":"Netflix","logo_path":"/netflix.png"}],
                "rent":[{"provider_id":9,"provider_name":"Rental only","logo_path":"/rental.png"}]},
                "IN":{"flatrate":[{"provider_id":10,"provider_name":"Other region","logo_path":"/other.png"}]}}}}""") }
        val meta = requireNotNull(repository(http, tmdbKey = "key").meta("movie", "tmdb:42"))
        assertEquals(listOf("Netflix"), meta.streamingProviders.map { it.name })
        assertEquals("https://image.tmdb.org/t/p/w92/netflix.png", meta.streamingProviders.single().logo)
        assertEquals(mapOf("Selvamani Selvaraj" to 5), meta.directorIDs)
        assertEquals(listOf("Selvamani Selvaraj"), meta.directors)
        assertEquals(listOf("A creator"), meta.creators)
        assertEquals(meta.streamingProviders, repository(http, tmdbKey = "key").meta("movie", "tmdb:42")?.streamingProviders)
    }

    @Test fun `canonical and localized names verify real DMM files without replacing the display title`() = runTest {
        val cases = listOf(
            listOf("series", "tt39328047", "Doctor X: Age of the White Mafia", "Doctor X: Mafia in White",
                "Doctor X: Mafia in White", "Doctor.X.Mafia.in.White.2026.S01E01.1080p.NF.RGzsRutracker.mkv"),
            listOf("movie", "tt7526136", "Heart of the Beast", "Heart of the Beast",
                "Heart of the Beast - Nel profondo selvaggio", "Heart.Of.The.Beast.Nel.Profondo.Selvaggio.2026.iTA-ENG.WEBDL.2160p.HEVC.HDR.x265-CYBER.mkv"),
        )
        for (case in cases) {
            val (type, id, display, official, translated) = case
            val filename = case[5]
            val kind = if (type == "series") "tv" else "movie"
            val nameField = if (type == "series") "name" else "title"
            val http = FakeHttp { request -> when {
                request.url.host == "tmdb.test" && request.url.encodedPath.contains("/find/") ->
                    FakeAnswer(body = """{"${kind}_results":[{"id":42}]}""")
                request.url.host == "tmdb.test" -> {
                    assertTrue(request.url.queryParameter("append_to_response").orEmpty().split(',').contains("translations"))
                    FakeAnswer(body = """{"id":42,"$nameField":"$official","original_$nameField":"$official",
                        "external_ids":{"imdb_id":"$id"},
                        "translations":{"translations":[{"data":{"$nameField":"$translated"}}]}}""")
                }
                else -> FakeAnswer(body = """{"meta":{"id":"$id","type":"$type","name":"$display","releaseInfo":"2026"}}""")
            } }
            val meta = requireNotNull(repository(http, tmdbKey = "key").meta(type, id))
            assertEquals(display, meta.title)
            assertTrue(meta.titleAliases.containsAll(listOf(official, translated)))
            val expected = StreamExpectation(meta.title, meta.year, if (type == "series") 1 else null,
                if (type == "series") 1 else null, type == "movie", aliases = meta.titleAliases)
            val row = StreamRow("dmm", "DMM Cast for Real-Debrid", "DMM", filename,
                url = "https://cdn.example/file.mkv", filename = filename)
            val media = FakeHttp { FakeAnswer(code = 206, body = "\u0000\u0000\u0000\u0018ftypisom", headers = mapOf(
                "Content-Type" to "application/force-download", "Content-Range" to "bytes 0-63/20393740301",
                "Content-Disposition" to "attachment; filename=\"$filename\"")) }
            assertEquals(true, withContext(Dispatchers.IO) { DebridResolver(null, null, media.client).inspect(row, expected) })
            assertFalse(expected.matches(row.copy(filename = filename.replace("2026", "2025").replace("S01E01", "S01E02"))))
            if (type == "movie") assertFalse(expected.matches(row.copy(filename = "Heart.Of.The.Beast.Returns.2026.2160p.mkv")))
            val before = http.requests.size
            val restarted = requireNotNull(repository(http, tmdbKey = "key").meta(type, id))
            assertEquals(meta.titleAliases, restarted.titleAliases)
            assertEquals(before, http.requests.size)
        }
    }

    @Test fun `a different IMDb work cannot supply a movie title alias`() = runTest {
        val http = FakeHttp { request -> when {
            request.url.host == "tmdb.test" && request.url.encodedPath.contains("/find/") ->
                FakeAnswer(body = """{"movie_results":[{"id":42}]}""")
            request.url.host == "tmdb.test" -> FakeAnswer(body = """{"id":42,"title":"Another Movie",
                "external_ids":{"imdb_id":"tt9999999"},
                "translations":{"translations":[{"data":{"title":"Another localized movie"}}]}}""")
            else -> FakeAnswer(body = """{"meta":{"id":"tt7526136","type":"movie","name":"Heart of the Beast","releaseInfo":"2026"}}""")
        } }
        val meta = requireNotNull(repository(http, tmdbKey = "key").meta("movie", "tt7526136"))
        assertTrue(meta.titleAliases.isEmpty())
    }

    @Test fun `verified movie release year replaces stale catalog year before source admission`() = runTest {
        val id = "tt32880540"
        val http = FakeHttp { request -> when {
            request.url.host == "tmdb.test" && request.url.encodedPath.contains("/find/") ->
                FakeAnswer(body = """{"movie_results":[{"id":1314786}]}""")
            request.url.host == "tmdb.test" -> FakeAnswer(body = """{"id":1314786,"title":"Agent Zeta",
                "original_title":"Zeta","release_date":"2026-11-13","external_ids":{"imdb_id":"$id"},
                "translations":{"translations":[{"data":{"title":"Agente Zeta"}}]}}""")
            else -> FakeAnswer(body = """{"meta":{"id":"$id","type":"movie","name":"Zeta","releaseInfo":"2025"}}""")
        } }
        val meta = requireNotNull(repository(http, tmdbKey = "key").meta("movie", id))
        assertEquals(2026, meta.year)
        val expected = StreamExpectation(meta.title, meta.year, null, null, true, aliases = meta.titleAliases)
        val file = "Agente.Zeta.2026.2160p.WEB-DL.HEVC.mkv"
        val row = StreamRow("zeta", "DMM Cast for TorBox", "4K", file, filename = file,
            url = "https://cdn.example/feature.mkv")
        assertTrue(expected.matches(row))
        assertFalse(expected.matches(row.copy(filename = "Zeta.2024.2160p.mkv")))
        assertFalse(expected.matches(row.copy(filename = "Zeta.Returns.2026.2160p.mkv")))
        val media = FakeHttp { FakeAnswer(code = 206, body = "\u0000\u0000\u0000\u0018ftypisom", headers = mapOf(
            "Content-Type" to "application/force-download", "Content-Range" to "bytes 0-63/24857123225",
            "Content-Disposition" to "attachment; filename=\"$file\"")) }
        assertEquals(true, withContext(Dispatchers.IO) { DebridResolver(null, null, media.client).inspect(row, expected) })
        val before = http.requests.size
        assertEquals(2026, repository(http, tmdbKey = "key").meta("movie", id)?.year)
        assertEquals(before, http.requests.size)
    }

    @Test fun `missing or different IMDb evidence cannot replace a known movie year`() = runTest {
        for (external in listOf("", """"external_ids":{"imdb_id":"tt9999999"},""")) {
            val http = FakeHttp { request -> when {
                request.url.host == "tmdb.test" && request.url.encodedPath.contains("/find/") ->
                    FakeAnswer(body = """{"movie_results":[{"id":1314786}]}""")
                request.url.host == "tmdb.test" -> FakeAnswer(body = """{${external}"id":1314786,
                    "title":"Agent Zeta","release_date":"2026-11-13"}""")
                else -> FakeAnswer(body = """{"meta":{"id":"tt32880540","type":"movie","name":"Zeta","releaseInfo":"2025"}}""")
            } }
            assertEquals(2025, repository(http, tmdbKey = "key", cacheDir = null).meta("movie", "tt32880540")?.year)
        }
    }

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
          "original_language": "en",
          "production_countries": [{"iso_3166_1":"US","name":"United States of America"}],
          "vote_average": 8.4,
          "vote_count": 100,
          "popularity": 42.5,
          "status": "Released",
          "release_date": "2010-07-16",
          "production_companies": [{"name":"Fixture studio"}],
          "spoken_languages": [{"iso_639_1":"en"},{"iso_639_1":"ja"}],
          "keywords": {"keywords":[{"name":"heist"}]},
          "genres": [{"id": 18, "name": "Drama"}],
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
            "crew": [{"job":"Director","name":"Christopher Nolan"}],
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
        sources: String = """{"sources":[]}""",
    ): MetaRepository {
        val client = StremioClient(http.client)
        val registry = AddonRegistry(
            SettingsDocument.parse(sources),
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
            watchRegion = "US",
        )
    }

    @Test fun `TMDB poster preview uses a small request and survives a restart`() = runTest {
        val http = FakeHttp { request ->
            check(request.url.encodedPath == "/3/movie/27205")
            check(request.url.queryParameter("append_to_response") == null)
            FakeAnswer(body = """{"title":"Inception","poster_path":"/poster.jpg","overview":"A dream heist."}""")
        }
        val first = repository(http, tmdbKey = "tmdbkey")
        val poster = requireNotNull(first.tmdbPreview("movie", 27205))
        assertEquals("https://image.tmdb.org/t/p/w500/poster.jpg", poster.poster)
        assertEquals("A dream heist.", first.peekTmdbPreview("movie", 27205)?.description)
        val second = repository(http, tmdbKey = "tmdbkey")
        assertEquals(poster, second.tmdbPreview("movie", 27205))
        assertEquals(1, http.urls.size)
    }

    @Test fun `verified TMDB identity publishes a Detail preview before a slow Cinemeta answer`() = runBlocking {
        val releaseCinemeta = CountDownLatch(1)
        val http = FakeHttp { request ->
            if (request.url.host == "tmdb.test") {
                check(request.url.encodedPath == "/3/movie/27205")
                FakeAnswer(body = """{"title":"Inception","poster_path":"/poster.jpg","overview":"A dream heist.","external_ids":{"imdb_id":"tt1375666"}}""")
            } else {
                check(releaseCinemeta.await(5, TimeUnit.SECONDS))
                FakeAnswer(body = """{"meta":{"id":"tt1375666","type":"movie","name":"Inception"}}""")
            }
        }
        val meta = repository(http, tmdbKey = "tmdbkey", cacheDir = null)
        val preview = CompletableDeferred<Meta>()
        val result = async { meta.meta("movie", "tt1375666", knownTmdbID = 27205) { preview.complete(it) } }
        try {
            assertEquals("A dream heist.", withTimeout(2_000) { preview.await() }.description)
            assertFalse(result.isCompleted)
        } finally {
            releaseCinemeta.countDown()
        }
        assertEquals("tt1375666", result.await()?.id)
    }

    @Test fun `series original language is backfilled from TMDB and survives disk cache`() = runTest {
        val http = FakeHttp { request ->
            when {
                request.url.toString().contains("/find/") -> FakeAnswer(body = "{\"tv_results\":[{\"id\":10}]}")
                request.url.host == "tmdb.test" -> FakeAnswer(body = "{\"id\":10,\"original_language\":\"ko\"}")
                else -> FakeAnswer(body = fixture("cinemeta-series.json"))
            }
        }
        val meta = requireNotNull(repository(http, tmdbKey = "key").meta("series", "tt0944947"))
        assertEquals("ko", meta.originalLanguage)
        val fetched = http.requests.size
        assertEquals("ko", repository(http, tmdbKey = "key").meta("series", "tt0944947")?.originalLanguage)
        assertEquals(fetched, http.requests.size)
    }

    @Test fun `a TMDB catalog title gains the IMDb alias for IMDb-only stream add-ons`() = runTest {
        val http = FakeHttp { request ->
            if (request.url.host == "tmdb.test") {
                FakeAnswer(body = """{"id":42,"title":"Fixture","external_ids":{"imdb_id":"tt4242"}}""")
            } else {
                FakeAnswer(body = """{"meta":{"id":"tmdb:42","name":"Fixture","type":"movie"}}""")
            }
        }
        val meta = requireNotNull(repository(http, tmdbKey = "key").meta("movie", "tmdb:42"))
        assertEquals("tt4242", meta.imdbID)
        assertTrue(http.requests.single { it.url.host == "tmdb.test" }.url
            .queryParameter("append_to_response").orEmpty().contains("external_ids"))
    }

    @Test fun `a canonical TMDB movie loads directly without a Cinemeta request`() = runTest {
        val http = FakeHttp { request ->
            assertEquals("tmdb.test", request.url.host)
            FakeAnswer(body = """{"id":42,"title":"Fixture","release_date":"2022-01-03","overview":"A test film."}""")
        }
        val meta = requireNotNull(repository(http, tmdbKey = "key").meta("movie", "tmdb:movie:42"))
        assertEquals("Fixture", meta.title)
        assertEquals(2022, meta.year)
        assertEquals(42, meta.tmdbID)
        assertEquals("/3/movie/42", http.requests.single().url.encodedPath)
    }

    @Test fun `a TMDB movie keeps Cinemeta and TMDB artwork alternatives across restart`() = runTest {
        val http = FakeHttp { request -> when (request.url.host) {
            "tmdb.test" -> FakeAnswer(body = """{"id":1410937,"title":"Balan - The Boy",
                "external_ids":{"imdb_id":"tt35254624"},"poster_path":"/poster.jpg","backdrop_path":"/wide.jpg"}""")
            else -> {
                assertEquals("/meta/movie/tt35254624.json", request.url.encodedPath)
                FakeAnswer(body = """{"meta":{"id":"tt35254624","type":"movie","name":"Balan: The Boy",
                    "poster":"https://images.metahub.space/poster/small/tt35254624/img",
                    "background":"https://images.metahub.space/background/medium/tt35254624/img"}}""")
            }
        } }
        val meta = requireNotNull(repository(http, tmdbKey = "key").meta("movie", "tmdb:1410937"))
        assertEquals("tmdb:1410937", meta.id)
        assertEquals(listOf("https://images.metahub.space/poster/small/tt35254624/img",
            "https://image.tmdb.org/t/p/w500/poster.jpg"), meta.posterCandidates)
        assertEquals(listOf("https://images.metahub.space/background/medium/tt35254624/img",
            "https://image.tmdb.org/t/p/w1280/wide.jpg"), meta.backdropCandidates)
        val before = http.requests.size
        assertEquals(meta, repository(http, tmdbKey = "key").meta("movie", "tmdb:1410937"))
        assertEquals(before, http.requests.size)
    }

    @Test fun `missing TMDB art uses Cinemeta without guessing a title match`() = runTest {
        val http = FakeHttp { request ->
            if (request.url.host == "tmdb.test") FakeAnswer(body = """{"title":"Inception",
                "external_ids":{"imdb_id":"tt1375666"}}""")
            else FakeAnswer(body = fixture("cinemeta-movie.json"))
        }
        val meta = requireNotNull(repository(http, tmdbKey = "key").meta("movie", "tmdb:27205"))
        assertEquals("https://images.metahub.space/poster/medium/tt1375666/img", meta.poster)
        assertEquals("https://images.metahub.space/background/medium/tt1375666/img", meta.backdrop)
    }

    @Test fun `Cinemeta failure leaves TMDB artwork usable`() = runTest {
        val http = FakeHttp { request ->
            if (request.url.host == "tmdb.test") FakeAnswer(body = """{"title":"Fixture",
                "external_ids":{"imdb_id":"tt42"},"poster_path":"/poster.jpg","backdrop_path":"/wide.jpg"}""")
            else FakeAnswer(code = 503)
        }
        val meta = requireNotNull(repository(http, tmdbKey = "key").meta("movie", "tmdb:42"))
        assertEquals("https://image.tmdb.org/t/p/w500/poster.jpg", meta.poster)
        assertEquals("https://image.tmdb.org/t/p/w1280/wide.jpg", meta.backdrop)
    }

    @Test fun `provider IMDb identity recovers Cinemeta movie artwork without a TMDB key`() = runTest {
        val http = FakeHttp { request -> when {
            request.url.encodedPath == "/meta/movie/custom:42.json" && request.url.host == "catalog.test" ->
                FakeAnswer(body = """{"meta":{"id":"custom:42","type":"movie","name":"Inception","imdb_id":"tt1375666"}}""")
            request.url.encodedPath == "/meta/movie/tt1375666.json" -> FakeAnswer(body = fixture("cinemeta-movie.json"))
            else -> FakeAnswer(code = 404)
        } }
        val meta = requireNotNull(repository(http,
            sources = """{"sources":[{"name":"Catalog","url":"https://catalog.test/manifest.json"}]}""")
            .meta("movie", "custom:42"))
        assertEquals("custom:42", meta.id)
        assertEquals("https://images.metahub.space/poster/medium/tt1375666/img", meta.poster)
        assertEquals("https://images.metahub.space/background/medium/tt1375666/img", meta.backdrop)
        assertTrue(http.requests.none { it.url.host == "tmdb.test" })
    }

    @Test fun `nonempty Cinemeta URLs retain bounded independent TMDB alternatives`() = runTest {
        val http = FakeHttp { request -> when {
            request.url.encodedPath.contains("/find/") -> FakeAnswer(body = """{"movie_results":[{"id":42}]}""")
            request.url.host == "tmdb.test" -> FakeAnswer(body = """{"title":"Fixture",
                "external_ids":{"imdb_id":"tt42"},"poster_path":"/last-poster.jpg","backdrop_path":"/last-wide.jpg",
                "images":{"posters":[{"file_path":"/poster.jpg"},{"file_path":"/poster.jpg"},{"file_path":"bad"}],
                    "backdrops":[{"file_path":"/portrait.jpg","width":500,"height":750},
                        {"file_path":"//invalid.jpg"},{"file_path":"/wide.jpg","width":1920,"height":1080},
                        {"file_path":"/second.jpg","width":1280,"height":720}]}}""")
            else -> FakeAnswer(body = """{"meta":{"id":"tt42","type":"movie","name":"Fixture",
                "poster":"https://broken.test/poster.jpg","background":"https://broken.test/wide.jpg"}}""")
        } }
        val meta = requireNotNull(repository(http, tmdbKey = "key").meta("movie", "tt42"))
        assertEquals(listOf("https://broken.test/poster.jpg", "https://image.tmdb.org/t/p/w500/poster.jpg",
            "https://image.tmdb.org/t/p/w500/last-poster.jpg"), meta.posterCandidates)
        assertEquals(listOf("https://broken.test/wide.jpg", "https://image.tmdb.org/t/p/w1280/wide.jpg",
            "https://image.tmdb.org/t/p/w1280/second.jpg", "https://image.tmdb.org/t/p/w1280/last-wide.jpg"), meta.backdropCandidates)
    }

    @Test fun `different IMDb work cannot supply fallback artwork`() = runTest {
        val http = FakeHttp { request -> when {
            request.url.encodedPath.contains("/find/") -> FakeAnswer(body = """{"movie_results":[{"id":42}]}""")
            request.url.host == "tmdb.test" -> FakeAnswer(body = """{"title":"Wrong movie",
                "external_ids":{"imdb_id":"tt999"},"poster_path":"/wrong.jpg","backdrop_path":"/wrong-wide.jpg"}""")
            else -> FakeAnswer(body = """{"meta":{"id":"tt42","type":"movie","name":"Fixture"}}""")
        } }
        val meta = requireNotNull(repository(http, tmdbKey = "key").meta("movie", "tt42"))
        assertTrue(meta.posterCandidates.isEmpty())
        assertTrue(meta.backdropCandidates.isEmpty())
    }

    @Test fun `a sparse add-on does not discard the TMDB title or primary artwork`() = runTest {
        val id = "tmdb:movie:1263337"
        val http = FakeHttp { request ->
            if (request.url.host == "tmdb.test") FakeAnswer(body = """
                {"id":1263337,"title":"Heart of the Beast","poster_path":"/beast.jpg",
                 "backdrop_path":"/wide.jpg","release_date":"2026-09-25","genres":[{"name":"Adventure"}]}
            """.trimIndent())
            else FakeAnswer(body = """{"meta":{"id":"$id","type":"movie"}}""")
        }
        val meta = requireNotNull(repository(http, tmdbKey = "key",
            sources = """{"sources":[{"name":"Sparse","url":"https://sparse.test/manifest.json"}]}""")
            .meta("movie", id))
        assertEquals(id, meta.id)
        assertEquals("Heart of the Beast", meta.title)
        assertEquals("https://image.tmdb.org/t/p/w500/beast.jpg", meta.poster)
        assertEquals("https://image.tmdb.org/t/p/w1280/wide.jpg", meta.backdrop)
        assertEquals(2026, meta.year)
        assertEquals(listOf("Adventure"), meta.genres)
    }

    @Test fun `a TMDB series previews before Cinemeta supplies its episodes`() = runBlocking {
        val releaseCinemeta = CountDownLatch(1)
        val first = CompletableDeferred<Meta>()
        val http = FakeHttp { request ->
            if (request.url.host == "tmdb.test") {
                FakeAnswer(body = """{"id":10,"name":"Fixture","external_ids":{"imdb_id":"tt7366338"}}""")
            } else {
                releaseCinemeta.await(3, TimeUnit.SECONDS)
                FakeAnswer(body = fixture("cinemeta-series.json"))
            }
        }
        val job = async {
            repository(http, tmdbKey = "key").meta("series", "tmdb:series:10") { first.complete(it) }
        }
        try {
            val preview = withTimeout(2_000) { first.await() }
            assertEquals("Fixture", preview.title)
            assertTrue(preview.videos.isEmpty())
            assertFalse(job.isCompleted)
            releaseCinemeta.countDown()
            assertTrue(requireNotNull(job.await()).videos.isNotEmpty())
        } finally {
            releaseCinemeta.countDown()
        }
    }

    @Test fun `a partial TMDB catalog keeps its video ID and adds missing Cinemeta episodes`() = runTest {
        val http = FakeHttp { request ->
            when (request.url.host) {
                "tmdb.test" -> FakeAnswer(body = """{"id":10,"name":"Fixture","external_ids":{"imdb_id":"tt7366338"}}""")
                "catalog.test" -> FakeAnswer(body = """
                    {"meta":{"id":"tmdb:10","name":"Fixture","type":"series","videos":[
                      {"id":"catalog:episode:one","season":1,"episode":1,"title":"Episode 1",
                       "streams":[{"url":"https://cdn.example/exact.mkv"}]}
                    ]}}
                """.trimIndent())
                else -> FakeAnswer(body = fixture("cinemeta-series.json"))
            }
        }
        val meta = requireNotNull(repository(http, tmdbKey = "key",
            sources = """{"sources":[{"name":"Catalog","url":"https://catalog.test/manifest.json"}]}""")
            .meta("series", "tmdb:10"))

        val first = meta.episodes(1).first()
        assertEquals("catalog:episode:one", first.id)
        assertEquals("1:23:45", first.title)
        assertTrue(first.inlineStreams.orEmpty().isNotEmpty())
        assertTrue(meta.hasInlineStreams)
        assertTrue(meta.episodes(1).any { it.episode == 2 })
        assertEquals(listOf(0, 1), meta.seasons)
    }

    @Test fun `TMDB fills an incomplete current season with aired episodes after the preview`() = runTest {
        val http = FakeHttp { request ->
            when (request.url.encodedPath) {
                "/3/find/tt42" -> FakeAnswer(body = """{"tv_results":[{"id":42}]}""")
                "/3/tv/42" -> FakeAnswer(body = """
                    {"name":"Fixture","last_episode_to_air":{"season_number":2,"episode_number":3,
                     "name":"Latest","air_date":"2025-09-09"}}
                """.trimIndent())
                "/3/tv/42/season/2" -> FakeAnswer(body = """
                    {"episodes":[
                      {"season_number":2,"episode_number":1,"name":"Existing","air_date":"2025-09-01",
                       "overview":"A synopsis","still_path":"/first.jpg"},
                      {"season_number":2,"episode_number":2,"name":"Missing","air_date":"2025-09-04"},
                      {"season_number":2,"episode_number":4,"name":"Upcoming","air_date":"2099-09-30"}
                    ]}
                """.trimIndent())
                else -> FakeAnswer(body = """
                    {"meta":{"id":"tt42","name":"Fixture","type":"series","videos":[
                      {"id":"tt42:2:1","season":2,"episode":1,"title":"Keep this title"}
                    ]}}
                """.trimIndent())
            }
        }
        val now = java.time.Instant.parse("2025-09-12T00:00:00Z").toEpochMilli()
        var firstPreview = true
        val meta = requireNotNull(repository(http, tmdbKey = "key", clock = { now }).meta("series", "tt42") {
            assertEquals(listOf(1), it.episodes(2).map(Episode::episode))
            if (firstPreview) assertTrue(http.requests.none { request -> request.url.host == "tmdb.test" })
            firstPreview = false
            assertTrue(http.requests.none { request -> request.url.encodedPath.endsWith("/season/2") })
        })
        assertEquals(listOf(1, 2, 3), meta.episodes(2).map(Episode::episode))
        assertEquals(listOf("Keep this title", "Missing", "Latest"), meta.episodes(2).map(Episode::title))
        assertEquals("A synopsis", meta.episodes(2).first().overview)
        assertEquals("https://image.tmdb.org/t/p/w500/first.jpg", meta.episodes(2).first().thumbnail)
        assertEquals("tt42:2:3", meta.episodes(2).last().id)
        assertEquals("2025-09-09", meta.episodes(2).last().released)
        assertEquals(listOf("/3/find/tt42", "/3/tv/42", "/3/tv/42/season/2"),
            http.requests.filter { it.url.host == "tmdb.test" }.map { it.url.encodedPath })
    }

    @Test fun `series cache refreshes after 30 minutes even after reading from disk`() = runTest {
        var now = 1_000L
        var latest = 1
        val http = FakeHttp {
            FakeAnswer(body = """
                {"meta":{"id":"tt42","name":"Fixture","type":"series","videos":[
                  {"id":"tt42:1:$latest","season":1,"episode":$latest}
                ]}}
            """.trimIndent())
        }
        assertEquals(1, repository(http, clock = { now }).meta("series", "tt42")?.videos?.single()?.episode)
        now += MetaRepository.SERIES_CACHE_TTL_MILLIS - 1
        latest = 2
        val reopened = repository(http, clock = { now })
        assertEquals(1, reopened.meta("series", "tt42")?.videos?.single()?.episode)
        assertEquals(1, http.requests.size)
        now++
        assertEquals(2, reopened.meta("series", "tt42")?.videos?.single()?.episode)
        assertEquals(2, http.requests.size)
    }

    @Test fun `expired metadata shows saved artwork before refresh without publishing stale episode actions`() = runBlocking {
        var now = 1_000L
        val release = CountDownLatch(1)
        val http = FakeHttp {
            if (now > 1_000L) release.await(3, TimeUnit.SECONDS)
            FakeAnswer(body = """{"meta":{"id":"tt42","type":"series","name":"Saved show",
                "poster":"https://image.example/poster.jpg","background":"https://image.example/backdrop.jpg",
                "cast":["An actor"],"videos":[{"id":"file:7","season":1,"episode":1,
                "streams":[{"url":"https://secret.example/PLAY_NOW"}]}]}}""")
        }
        repository(http, clock = { now }).meta("series", "tt42")
        now += MetaRepository.SERIES_CACHE_TTL_MILLIS
        val reopened = repository(http, clock = { now })
        val preview = CompletableDeferred<Meta>()
        val job = async { reopened.meta("series", "tt42") { preview.complete(it) } }
        try {
            val shown = withTimeout(2_000) { preview.await() }
            assertEquals("https://image.example/backdrop.jpg", shown.backdrop)
            assertEquals("An actor", shown.cast.single().name)
            assertTrue(shown.videos.isEmpty())
            assertTrue(shown.seasons.isEmpty())
            assertNull(shown.defaultVideoID)
            assertNull(shown.inlineStreams)
            assertNull(reopened.peek("series", "tt42"))
            assertFalse(job.isCompleted)
            release.countDown()
            assertEquals(1, job.await()?.videos?.size)
        } finally { release.countDown(); job.cancel() }
    }

    @Test fun `failed metadata refresh keeps saved preview without making it fresh`() = runTest {
        var now = 1_000L
        var fail = false
        val http = FakeHttp { if (fail) FakeAnswer(code = 503) else FakeAnswer(body = fixture("cinemeta-series.json")) }
        val repo = repository(http, clock = { now })
        repo.meta("series", "tt7366338")
        now += MetaRepository.SERIES_CACHE_TTL_MILLIS
        fail = true
        val previews = mutableListOf<Meta>()
        assertNull(repo.meta("series", "tt7366338", onBase = { previews.add(it) }))
        assertEquals("Chernobyl", previews.single().title)
        assertNull(repo.peek("series", "tt7366338"))
        now += MetaRepository.MAX_STALE_MILLIS
        previews.clear()
        repo.meta("series", "tt7366338", onBase = { previews.add(it) })
        assertTrue(previews.isEmpty())
    }

    @Test fun `Detail sharing a prefetch receives enriched artwork before slow episode completion`() = runBlocking {
        val detailsStarted = CountDownLatch(1)
        val allowDetails = CountDownLatch(1)
        val allowEpisodes = CountDownLatch(1)
        val http = FakeHttp { request -> when (request.url.encodedPath) {
            "/3/find/tt42" -> FakeAnswer(body = """{"tv_results":[{"id":42}]}""")
            "/3/tv/42" -> {
                detailsStarted.countDown()
                allowDetails.await(3, TimeUnit.SECONDS)
                FakeAnswer(body = """{"name":"Show","backdrop_path":"/wide.jpg",
                    "credits":{"cast":[{"name":"Actor","profile_path":"/actor.jpg"}]},
                    "last_episode_to_air":{"season_number":1,"episode_number":1,"name":"First","air_date":"2020-01-01"}}""")
            }
            "/3/tv/42/season/1" -> { allowEpisodes.await(3, TimeUnit.SECONDS); FakeAnswer(body = """{"episodes":[]}""") }
            else -> FakeAnswer(body = """{"meta":{"id":"tt42","type":"series","name":"Show"}}""")
        } }
        val repo = repository(http, tmdbKey = "key")
        val owner = async(Dispatchers.Default) { repo.meta("series", "tt42") }
        try {
            assertTrue(detailsStarted.await(3, TimeUnit.SECONDS))
            val base = CompletableDeferred<Meta>()
            val enriched = CompletableDeferred<Meta>()
            val detail = async {
                repo.meta("series", "tt42") {
                    base.complete(it)
                    if (it.cast.any { member -> member.photo != null }) enriched.complete(it)
                }
            }
            withTimeout(2_000) { base.await() }
            allowDetails.countDown()
            assertEquals("https://image.tmdb.org/t/p/w1280/wide.jpg", withTimeout(2_000) { enriched.await() }.backdrop)
            assertFalse(detail.isCompleted)
            allowEpisodes.countDown()
            assertNotNull(detail.await())
            owner.await()
        } finally { allowDetails.countDown(); allowEpisodes.countDown(); owner.cancel() }
        Unit
    }

    @Test fun `TMDB previews an IMDb title after Cinemeta misses it without waiting for other add-ons`() = runBlocking {
        val releaseProvider = CountDownLatch(1)
        val first = CompletableDeferred<Meta>()
        val http = FakeHttp { request ->
            when (request.url.host) {
                "tmdb.test" -> if (request.url.encodedPath.contains("/find/")) {
                    FakeAnswer(body = """{"movie_results":[{"id":42}]}""")
                } else FakeAnswer(body = """{"id":42,"title":"TMDB preview"}""")
                "slow.test" -> {
                    releaseProvider.await(3, TimeUnit.SECONDS)
                    FakeAnswer(body = """{"meta":{"id":"tt4242","type":"movie","name":"Provider title"}}""")
                }
                else -> FakeAnswer(code = 404, body = "{}")
            }
        }
        val job = async {
            repository(http, tmdbKey = "key",
                sources = """{"sources":[{"name":"Slow","url":"https://slow.test/manifest.json"}]}""")
                .meta("movie", "tt4242") { first.complete(it) }
        }
        try {
            val preview = withTimeout(2_000) { first.await() }
            assertEquals("TMDB preview", preview.title)
            assertEquals("tt4242", preview.imdbID)
            assertFalse(job.isCompleted)
            releaseProvider.countDown()
            assertEquals("Provider title", job.await()?.title)
        } finally {
            releaseProvider.countDown()
        }
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
        assertEquals("en", meta.originalLanguage)
        assertTrue(meta.genres.containsAll(listOf("Action", "Adventure", "Sci-Fi", "Drama")))
        assertEquals(listOf("United States of America"), meta.countries)
        assertEquals(8.4, requireNotNull(meta.tmdbRating), 0.001)
        assertEquals(100, meta.tmdbVotes)
        assertEquals(listOf("Fixture studio"), meta.studios)
        assertEquals(listOf("Christopher Nolan"), meta.directors)
        assertEquals(listOf("heist"), meta.keywords)
        assertEquals(listOf("en", "ja"), meta.spokenLanguages)
        assertEquals("Released", meta.releaseStatus)
        assertEquals("2010-07-16", meta.releaseDate)
        assertEquals(42.5, requireNotNull(meta.popularity), 0.001)
        assertTrue(meta.castNames.contains("Elliot Page"))
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
        var enrichedShown = false
        val meta = requireNotNull(repository(http, tmdbKey = "tmdbkey").meta("movie", "tt1375666") { base ->
            assertEquals("Inception", base.title)
            if (!baseShown) assertTrue(http.urls.none { it.contains("tmdb.test") })
            else {
                assertEquals("PG-13", base.certification)
                enrichedShown = true
            }
            baseShown = true
        })
        assertTrue(baseShown)
        assertTrue(enrichedShown)
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
