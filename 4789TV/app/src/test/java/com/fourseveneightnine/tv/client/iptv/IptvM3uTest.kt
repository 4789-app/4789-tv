package com.fourseveneightnine.tv.client.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IptvM3uTest {
    @Test fun `CRLF entries keep group guide id and request headers`() {
        val playlist = "#EXTM3U url-tvg=\"https://guide.example/epg.xml\"\r\n" +
            "#EXTINF:-1 tvg-id=\"news.id\" group-title=\"News, Local\" http-user-agent=\"Box\",News One\r\n" +
            "#EXTVLCOPT:http-referrer=https://guide.example/\r\n" +
            "https://video.example/live/one.ts\r\n"
        val parsed = IptvM3u.parse("source", playlist)
        assertEquals("https://guide.example/epg.xml", parsed.epgUrl)
        assertEquals(1, parsed.channels.size)
        assertEquals("News One", parsed.channels.single().name)
        assertEquals("News, Local", parsed.channels.single().group)
        assertEquals("news.id", parsed.channels.single().epgId)
        assertEquals("Box", parsed.channels.single().headers["User-Agent"])
        assertEquals("https://guide.example/", parsed.channels.single().headers["Referer"])
    }

    @Test fun `movie and episode URLs leave live channel list`() {
        val parsed = IptvM3u.parse("provider", """
            #EXTM3U
            #EXTINF:-1 group-title="Movies",Film One
            https://video.example/movie/film.mp4
            #EXTINF:-1 group-title="Series",Show S01E01
            https://video.example/series/show/episode.mkv
            #EXTINF:-1 group-title="Series",Show S01E1000
            https://video.example/series/show/episode1000.mkv
            #EXTINF:-1 group-title="Sports",Sport TV
            https://video.example/live/sport.ts
        """.trimIndent())
        assertEquals(1, parsed.channels.size)
        assertEquals(listOf("movie", "series"), parsed.vod.map(IptvVod::kind))
        assertEquals(2, parsed.episodes.size)
        assertTrue(parsed.episodes.all { it.category == parsed.vod.last().id })
        assertEquals("Show S01E1000", orderedIptvEpisodes(parsed.episodes, "Latest episode").first().title)
    }

    @Test fun `non-http URLs and unsafe paths are not accepted`() {
        assertFalse(IptvM3u.httpUrl("file:///tmp/private"))
        assertFalse(IptvM3u.httpUrl("javascript:alert(1)"))
        assertTrue(IptvM3u.httpUrl("https://example.org/list.m3u"))
    }

    @Test fun `rotating query token does not lose a favorite channel id`() {
        fun playlist(token: String) = "#EXTM3U\n#EXTINF:-1 tvg-id=\"news\" group-title=\"Telugu\",News\n" +
            "https://example.org/live/news.ts?token=$token"
        val first = IptvM3u.parse("source", playlist("old")).channels.single()
        val next = IptvM3u.parse("source", playlist("new")).channels.single()
        assertEquals(first.id, next.id)
        assertFalse(first.streamRef == next.streamRef)
    }

    @Test fun `guide program title becomes a channel suggestion`() {
        val channel = IptvChannel("s:1", "s", "City TV", "Local", streamRef = "https://example.org/live")
        val program = IptvProgram("s:1", "Championship Final", 1_000, 2_000)
        assertEquals(listOf(channel), IptvIndex.search(listOf(channel), listOf(program), "championship", now = 1_500))
    }
    @Test fun `long series retains latest episodes beyond two thousand`() {
        val playlist = buildString {
            append("#EXTM3U\n")
            for (episode in 1..2500) {
                append("#EXTINF:-1 group-title=\"Series\",Daily Show S01E$episode\n")
                append("https://video.example/series/daily/$episode.mkv\n")
            }
        }
        val parsed = IptvM3u.parse("source", playlist)
        assertEquals(2500, parsed.episodes.size)
        assertEquals("Daily Show S01E2500", orderedIptvEpisodes(parsed.episodes, "Latest episode").first().title)
    }

    @Test fun `portal imports paginated movies series and native guide and remints video links`() = kotlinx.coroutines.runBlocking {
        val requests = java.util.concurrent.CopyOnWriteArrayList<okhttp3.Request>()
        var handshakes = 0
        var links = 0
        val http = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            val type = request.url.queryParameter("type")
            val action = request.url.queryParameter("action")
            var code = 200
            val js = when (action) {
                "handshake" -> "{\"token\":\"token${++handshakes}\"}"
                "get_profile" -> "{}"
                "get_genres" -> "[{\"id\":\"1\",\"title\":\"Telugu\"}]"
                "get_all_channels" -> "{\"data\":[{\"id\":\"7\",\"name\":\"ETV\",\"tv_genre_id\":\"1\",\"cmd\":\"ffmpeg http://localhost/ch/7\"}]}"
                "get_categories" -> "[{\"id\":\"3\",\"title\":\"Telugu Originals\"}]"
                "get_epg_info" -> "{\"7\":[{\"name\":\"Evening News\",\"start_timestamp\":1700000000,\"stop_timestamp\":1700003600}]}"
                "get_ordered_list" -> when {
                    request.url.queryParameter("movie_id") != null -> "{\"total_items\":1,\"data\":[{\"id\":\"season1\",\"name\":\"Season 1\",\"series\":[1,2500],\"cmd\":\"ffmpeg /media/show\"}]}"
                    type == "series" -> "{\"total_items\":1,\"data\":[{\"id\":\"20\",\"name\":\"Show\",\"category_id\":\"3\",\"cmd\":\"ffmpeg /media/show\"}]}"
                    else -> {
                        val page = request.url.queryParameter("p") ?: "1"
                        "{\"total_items\":2,\"data\":[{\"id\":\"$page\",\"name\":\"Film $page\",\"category_id\":\"3\",\"description\":\"Full description\",\"cmd\":\"ffmpeg /media/$page\"}]}"
                    }
                }
                "create_link" -> {
                    links++
                    if (links == 1) { code = 401; "false" }
                    else "{\"cmd\":\"ffmpeg https://video.example/fresh$links.mp4\"}"
                }
                else -> error("Unexpected portal action $action")
            }
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(code).message("fixture").body("{\"js\":$js}".let {
                    with(okhttp3.ResponseBody.Companion) { it.toResponseBody() }
                }).build()
        }.build()
        val source = IptvSource("portal", "Portal", IptvSourceKind.STALKER,
            host = "https://portal.example/", mac = "00:11:22:33:44:55")
        val fetcher = IptvFetcher(http)
        val catalog = fetcher.fetch(source)
        assertEquals(2, catalog.vod.count { it.kind == "movie" })
        assertTrue(catalog.failedVodKinds.isEmpty())
        assertEquals("Telugu Originals", catalog.vod.first().category)
        assertEquals("Full description", catalog.vod.first().description)
        assertEquals("Evening News", catalog.programs.single().title)
        assertEquals(1700000000000L, catalog.programs.single().startMillis)
        val series = catalog.vod.single { it.kind == "series" }
        val episodes = fetcher.seriesDetails(source, series).episodes
        assertEquals(listOf(1,2500), episodes.map { it.episodeNumber })
        assertEquals("https://video.example/fresh2.mp4", fetcher.resolveStalkerVod(source, episodes.last()))
        assertEquals(2, handshakes)
        assertEquals("2500", requests.last().url.queryParameter("series"))
        assertEquals("https://video.example/fresh3.mp4", fetcher.resolveStalkerVod(source, episodes.last()))
    }

    @Test fun `Xtream episode refresh retains ownership and reads renewed direct source`() = kotlinx.coroutines.runBlocking {
        var token = "old"
        val http = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = """{"info":{"name":"Show"},"episodes":{"1":[{"id":"7","episode_num":4,"title":"Episode","container_extension":"mkv","direct_source":"https://video.example/episode?token=$token"}]}}"""
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("fixture").body(with(okhttp3.ResponseBody.Companion) { body.toResponseBody() }).build()
        }.build()
        val source = IptvSource("s", "TV", IptvSourceKind.XTREAM, host="https://provider.example/", username="fixture",password="fixture")
        val series = IptvVod("s:series:3", "s", "series", "Show", "Telugu", streamId="3")
        val fetcher = IptvFetcher(http)
        val old = fetcher.seriesDetails(source, series).episodes.single()
        token = "new"
        val renewed = fetcher.seriesDetails(source, series).episodes.single()
        assertEquals(old.id, renewed.id)
        assertEquals(series.id, renewed.seriesId)
        assertEquals(4, renewed.episodeNumber)
        assertEquals("https://video.example/episode?token=new", fetcher.streamUrl(source, renewed))
    }

}
