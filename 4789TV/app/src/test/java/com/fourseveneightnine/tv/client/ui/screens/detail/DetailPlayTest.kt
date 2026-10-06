package com.fourseveneightnine.tv.client.ui.screens.detail

import com.fourseveneightnine.tv.client.data.library.ContinueItem
import com.fourseveneightnine.tv.client.data.library.RowOrigin
import com.fourseveneightnine.tv.client.data.meta.Episode
import com.fourseveneightnine.tv.client.data.meta.Meta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DetailPlayTest {

    @Test fun `episode ordering uses numbers and air dates with unknown dates last`() {
        val old = Episode(1, 9, "Old", id = "one", released = "2020-01-01")
        val newer = Episode(1, 100, "New", id = "two", released = "2021-01-01")
        val special = Episode(2, 1, "Unknown", id = "three")
        val rows = listOf(newer, special, old)
        assertEquals(listOf(special, newer, old), DetailFormat.orderedEpisodes(rows, "Latest episode"))
        assertEquals(listOf(old, newer, special), DetailFormat.orderedEpisodes(rows, "Oldest episode"))
        assertEquals(listOf(newer, old, special), DetailFormat.orderedEpisodes(rows, "Latest air date"))
    }

    @Test fun `thousand episode series starts at latest without a thousand remote presses`() {
        val rows = (1..1000).map { Episode(1, it, "Episode $it", id = "show:1:$it") }
        val ordered = DetailFormat.orderedEpisodes(rows, "Latest episode")
        assertEquals(1000, ordered.first().episode)
        assertEquals(1, ordered.last().episode)
        assertEquals(1000, ordered.size)
    }

    private val videos = listOf(
        Episode(season = 1, episode = 1, title = "System", id = "tt1:1:1"),
        Episode(season = 1, episode = 2, title = "Hands", id = "tt1:1:2"),
        Episode(season = 2, episode = 4, title = "Honeydew", id = "tt1:2:4"),
        Episode(season = 2, episode = 5, title = "Pot au Feu", id = "tt1:2:5"),
    )
    private val show = Meta(id = "tt1", type = "series", title = "The Bear", videos = videos)
    private val movie = Meta(id = "tt2", type = "movie", title = "Heat", runtimeMinutes = 170)

    @Test
    fun `a film with no progress just says Play`() {
        assertEquals("Play", DetailPlay.pick(movie, null).label)
    }

    @Test
    fun `a film in progress resumes and says how far in`() {
        val pick = DetailPlay.pick(movie, progress(positionMs = 4_324_000L, durationMs = 10_200_000L))

        assertEquals("Resume 1h 12m in", pick.label)
        assertEquals(4_324_000L, pick.resumeFromMs)
    }

    @Test
    fun `a film that is finished starts again from the top`() {
        val pick = DetailPlay.pick(movie, progress(positionMs = 10_100_000L, durationMs = 10_200_000L))

        assertEquals("Play", pick.label)
        assertNull(pick.resumeFromMs)
    }

    @Test
    fun `a series nobody has started opens on season one episode one`() {
        val pick = DetailPlay.pick(show, null)

        assertEquals(1, pick.season)
        assertEquals(1, pick.episode)
        assertEquals("Play S1 E1", pick.label)
    }

    @Test
    fun `a series in progress resumes that episode`() {
        val pick = DetailPlay.pick(
            show,
            progress(season = 2, episode = 4, positionMs = 600_000L, durationMs = 2_640_000L),
        )

        assertEquals(2, pick.season)
        assertEquals(4, pick.episode)
        assertEquals(600_000L, pick.resumeFromMs)
        assertEquals("Resume S2 E4", pick.label)
    }

    @Test
    fun `a finished episode moves Play on to the next one`() {
        val pick = DetailPlay.pick(
            show,
            progress(season = 2, episode = 4, positionMs = 2_600_000L, durationMs = 2_640_000L),
        )

        assertEquals(2, pick.season)
        assertEquals(5, pick.episode)
        assertEquals("Play S2 E5", pick.label)
        assertNull(pick.resumeFromMs)
    }

    @Test
    fun `a finished season boundary moves Play into the next season`() {
        val pick = DetailPlay.pick(
            show,
            progress(season = 1, episode = 2, positionMs = 2_600_000L, durationMs = 2_640_000L),
        )

        assertEquals(2, pick.season)
        assertEquals(4, pick.episode)
    }

    @Test
    fun `a finished last episode falls back to the first one`() {
        val pick = DetailPlay.pick(
            show,
            progress(season = 2, episode = 5, positionMs = 2_600_000L, durationMs = 2_640_000L),
        )

        assertEquals(1, pick.season)
        assertEquals(1, pick.episode)
    }

    @Test
    fun `a row under two percent has not started, so Play stays on that episode`() {
        val pick = DetailPlay.pick(
            show,
            progress(season = 2, episode = 4, positionMs = 10_000L, durationMs = 2_640_000L),
        )

        assertEquals(2, pick.season)
        assertEquals(4, pick.episode)
        assertEquals("Play S2 E4", pick.label)
    }

    @Test
    fun `the watched tick and the bar follow the one row the library holds`() {
        val finished = progress(season = 2, episode = 4, positionMs = 2_600_000L, durationMs = 2_640_000L)
        val midway = progress(season = 2, episode = 4, positionMs = 600_000L, durationMs = 2_640_000L)

        assert(DetailPlay.isWatched(finished, 2, 4))
        assert(!DetailPlay.isWatched(finished, 2, 5))
        assertNull(DetailPlay.episodeProgress(finished, 2, 4))
        assertEquals(0.227f, DetailPlay.episodeProgress(midway, 2, 4)!!, 0.01f)
        assertNull(DetailPlay.episodeProgress(midway, 2, 5))
    }

    private fun progress(
        season: Int? = null,
        episode: Int? = null,
        positionMs: Long,
        durationMs: Long,
    ) = ContinueItem(
        canonicalId = "tt1",
        mediaType = "series",
        title = "The Bear",
        posterUrl = null,
        backdropUrl = null,
        season = season,
        episode = episode,
        positionMs = positionMs,
        durationMs = durationMs,
        lastActivityMillis = 0L,
        origin = RowOrigin.LOCAL,
    )
}

class DetailFormatTest {

    @Test
    fun `durations read as a time, never as a percentage`() {
        assertEquals("1h 12m", DetailFormat.duration(4_324_000L))
        assertEquals("2h 09m", DetailFormat.duration(7_740_000L))
        assertEquals("44m", DetailFormat.duration(2_640_000L))
        assertEquals("0m", DetailFormat.duration(-5L))
    }

    @Test
    fun `the meta line drops the segments the title never stated`() {
        val full = Meta(
            id = "tt1",
            type = "movie",
            title = "Heat",
            year = 1995,
            runtimeMinutes = 170,
            certification = "15",
            genres = listOf("Thriller", "Crime"),
        )
        assertEquals("1995 · 2h 50m · Thriller, Crime · 15", DetailFormat.metaLine(full))

        val bare = Meta(id = "tt2", type = "movie", title = "Unknown")
        assertEquals("", DetailFormat.metaLine(bare))
    }

    @Test
    fun `a series with no runtime counts its seasons instead`() {
        val show = Meta(
            id = "tt1",
            type = "series",
            title = "The Bear",
            year = 2022,
            videos = listOf(
                Episode(1, 1, "System", id = "a"),
                Episode(2, 1, "Beef", id = "b"),
            ),
        )
        assertEquals("2022 · 2 seasons", DetailFormat.metaLine(show))
    }

    @Test
    fun `an air date reads as a day and a month, or not at all`() {
        assertEquals("12 March", DetailFormat.airDate("2024-03-12T00:00:00Z"))
        assertEquals("1 January", DetailFormat.airDate("2024-01-01"))
        assertNull(DetailFormat.airDate("soon"))
        assertNull(DetailFormat.airDate(null))
    }

    @Test
    fun `an unaired episode is one dated after today, and an unknown date is not one`() {
        assert(DetailFormat.isUnaired("2026-12-01", "2026-09-21"))
        assert(!DetailFormat.isUnaired("2026-09-21", "2026-09-21"))
        assert(!DetailFormat.isUnaired(null, "2026-09-21"))
        assert(!DetailFormat.isUnaired("", "2026-09-21"))
    }

    @Test
    fun `season zero is called Specials`() {
        assertEquals("Specials", DetailFormat.seasonLabel(0))
        assertEquals("Season 3", DetailFormat.seasonLabel(3))
    }
}
