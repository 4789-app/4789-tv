package com.fourseveneightnine.tv.client.data.images

import org.junit.Assert.assertEquals
import org.junit.Test

/** The one rule that keeps a 236 px card from pulling a 2000 px poster. */
class TmdbSizeTest {
    @Test
    fun `rewrites only the size segment`() {
        val table = listOf(
            Triple("https://image.tmdb.org/t/p/original/abc.jpg", 342, "https://image.tmdb.org/t/p/w342/abc.jpg"),
            Triple("https://image.tmdb.org/t/p/w780/abc.jpg", 342, "https://image.tmdb.org/t/p/w342/abc.jpg"),
            Triple("https://image.tmdb.org/t/p/w92/abc.jpg", 180, "https://image.tmdb.org/t/p/w185/abc.jpg"),
            Triple("https://image.tmdb.org/t/p/w185/abc.jpg", 500, "https://image.tmdb.org/t/p/w500/abc.jpg"),
            Triple("https://image.tmdb.org/t/p/w185/abc.jpg", 780, "https://image.tmdb.org/t/p/w780/abc.jpg"),
            Triple("https://image.tmdb.org/t/p/w185/abc.jpg", 1280, "https://image.tmdb.org/t/p/w1280/abc.jpg"),
            Triple("https://image.tmdb.org/t/p/w185/abc.jpg", 4000, "https://image.tmdb.org/t/p/original/abc.jpg"),
        )
        for ((url, width, expected) in table) {
            assertEquals("$url @ $width", expected, TmdbSize.sized(url, width))
        }
    }

    @Test
    fun `large catalog cards request artwork for HD and 4K`() {
        val poster = "https://image.tmdb.org/t/p/w342/abc.jpg"
        assertEquals("https://image.tmdb.org/t/p/w500/abc.jpg", TmdbSize.poster(poster, 376))
        assertEquals("https://image.tmdb.org/t/p/w780/abc.jpg", TmdbSize.poster(poster, 752))
        assertEquals("https://image.tmdb.org/t/p/original/abc.jpg", TmdbSize.poster(poster, 781))
        val other = "https://cdn.example.com/poster.jpg"
        assertEquals(other, TmdbSize.poster(other, 752))
    }

    @Test
    fun `large catalog cards can upgrade MetaHub thumbnails`() {
        val poster = "https://images.metahub.space/poster/small/tt0032138/img?v=2"
        assertEquals(poster, sizedPosterUrl(poster, 342))
        assertEquals(poster.replace("/small/", "/medium/"), sizedPosterUrl(poster, 376))
        assertEquals(poster.replace("/small/", "/large/"), sizedPosterUrl(poster, 752))
        val other = "https://images.metahub.space/poster/custom/tt0032138/img"
        assertEquals(other, sizedPosterUrl(other, 752))
    }

    @Test
    fun `passes through a url it does not recognise`() {
        val untouched = listOf(
            "https://cdn.example.com/poster.jpg",
            "https://image.tmdb.org/other/path.jpg",
        )
        for (url in untouched) {
            assertEquals(url, TmdbSize.sized(url, 342))
        }
    }

    @Test
    fun `a width of zero or less changes nothing`() {
        val url = "https://image.tmdb.org/t/p/original/abc.jpg"
        assertEquals(url, TmdbSize.sized(url, 0))
        assertEquals(url, TmdbSize.sized(url, -1))
    }

    @Test
    fun `keeps the rest of the path byte for byte`() {
        assertEquals(
            "https://image.tmdb.org/t/p/w342/a%2Fb_c-d.1.jpg?v=2",
            TmdbSize.sized("https://image.tmdb.org/t/p/original/a%2Fb_c-d.1.jpg?v=2", 300),
        )
    }

    @Test
    fun `the three card widths are the ones the design spec draws`() {
        assertEquals(342, TmdbSize.POSTER_WIDTH)
        assertEquals(780, TmdbSize.WIDE_WIDTH)
        assertEquals(1280, TmdbSize.BACKDROP_WIDTH)
    }
}
