package com.fourseveneightnine.tv.client.playback

import com.fourseveneightnine.tv.client.data.meta.Episode
import com.fourseveneightnine.tv.client.data.meta.Meta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextUpTest {

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
    fun `the subtitle drops the half that is missing`() {
        assertEquals("S2 E4 · Honeydew", NextUp.subtitle(2, 4, "Honeydew"))
        assertEquals("S2 E4", NextUp.subtitle(2, 4, null))
        assertEquals("S2 E4", NextUp.subtitle(2, 4, "  "))
        assertEquals("Honeydew", NextUp.subtitle(null, null, "Honeydew"))
        assertEquals("", NextUp.subtitle(null, null, null))
    }

    private fun episode(season: Int, number: Int, title: String) = Episode(
        season = season,
        episode = number,
        title = title,
        id = "tt7366338:$season:$number",
    )
}
