package com.fourseveneightnine.tv.client.data.addons

import com.fourseveneightnine.tv.client.data.FakeAnswer
import com.fourseveneightnine.tv.client.data.FakeHttp
import com.fourseveneightnine.tv.client.data.fixture
import com.fourseveneightnine.tv.client.data.streams.HdrFormat
import com.fourseveneightnine.tv.client.data.streams.StreamRow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StremioClientTest {

    private val addon = Addon("AIOStreams", "https://aio.example.com/stremio/SECRETKEY/manifest.json")

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
