package com.fourseveneightnine.tv.ui

import com.fourseveneightnine.tv.catalog.TVTamilMVCatalogItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContinuePlaybackPolicyTest {
    @Test
    fun `matches mirrored movie title to stable catalog identity`() {
        val recent = RecentItem(url = "", title = "Newton's 3rd Law", timestamp = 1L)
        val item = TVTamilMVCatalogItem(
            id = "tmdb:1740614",
            mediaType = "movie",
            title = "Newton's 3rd Law",
            tmdbID = 1_740_614,
        )

        assertEquals(item, ContinuePlaybackPolicy.catalogItem(recent, listOf(item)))
    }

    @Test
    fun `episode metadata prefers a series match when titles collide`() {
        val recent = RecentItem(url = "", title = "The Office", subtitle = "S2 · E3", timestamp = 1L)
        val movie = TVTamilMVCatalogItem(id = "movie", mediaType = "movie", title = "The Office")
        val series = TVTamilMVCatalogItem(id = "series", mediaType = "series", title = "The Office")

        assertEquals(series, ContinuePlaybackPolicy.catalogItem(recent, listOf(movie, series)))
    }

    @Test
    fun `unknown title does not invent an addon identity`() {
        val recent = RecentItem(url = "", title = "Not in a catalog", timestamp = 1L)

        assertNull(ContinuePlaybackPolicy.catalogItem(recent, emptyList()))
    }
}
