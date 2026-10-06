package com.fourseveneightnine.tv.client.ui.components

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The painted-url cache, audit F13 and F14.
 *
 * The defect it answers: a `LazyRow` item that scrolls out of the composed window loses its
 * `remember`, so a poster walked ten cards right and ten back came home believing it had never
 * painted, drew its title over the bitmap, and let Coil fade the bitmap in again.
 */
class PaintedArtworkTest {

    @Before
    fun setUp() = PaintedArtwork.resetForTest()

    @After
    fun tearDown() = PaintedArtwork.resetForTest()

    @Test
    fun `an unseen url has not painted`() {
        assertFalse(PaintedArtwork.wasPainted("https://image.tmdb.org/t/p/w342/a.jpg"))
    }

    @Test
    fun `a url stays painted after the item leaves the composed window`() {
        val url = "https://image.tmdb.org/t/p/w342/a.jpg"
        PaintedArtwork.markPainted(url)
        assertTrue(PaintedArtwork.wasPainted(url))
    }

    @Test
    fun `a null url is never painted and never stored`() {
        PaintedArtwork.markPainted(null)
        assertFalse(PaintedArtwork.wasPainted(null))
        assertEquals(0, PaintedArtwork.size())
    }

    @Test
    fun `two sizes of the same poster are two urls`() {
        PaintedArtwork.markPainted("https://image.tmdb.org/t/p/w342/a.jpg")
        assertFalse(PaintedArtwork.wasPainted("https://image.tmdb.org/t/p/w780/a.jpg"))
    }

    @Test
    fun `the cache is bounded`() {
        repeat(PaintedArtwork.MAX_URLS * 2) { PaintedArtwork.markPainted("url-$it") }
        assertEquals(PaintedArtwork.MAX_URLS, PaintedArtwork.size())
    }

    @Test
    fun `eviction drops the least recently used url, not the oldest`() {
        repeat(PaintedArtwork.MAX_URLS) { PaintedArtwork.markPainted("url-$it") }
        // Touch the first url, then overflow by one. The one that goes is url-1, not url-0.
        assertTrue(PaintedArtwork.wasPainted("url-0"))
        PaintedArtwork.markPainted("overflow")
        assertTrue(PaintedArtwork.wasPainted("url-0"))
        assertFalse(PaintedArtwork.wasPainted("url-1"))
        assertEquals(PaintedArtwork.MAX_URLS, PaintedArtwork.size())
    }

    @Test
    fun `marking the same url twice does not grow the cache`() {
        repeat(10) { PaintedArtwork.markPainted("https://image.tmdb.org/t/p/w342/a.jpg") }
        assertEquals(1, PaintedArtwork.size())
    }
}
