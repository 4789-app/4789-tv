package com.fourseveneightnine.tv.client.ui.screens.streams

import com.fourseveneightnine.tv.client.data.meta.Episode
import com.fourseveneightnine.tv.client.data.streams.CachedHint
import com.fourseveneightnine.tv.client.data.streams.RankReason
import com.fourseveneightnine.tv.client.data.streams.RankedRow
import com.fourseveneightnine.tv.client.data.streams.StreamRow
import com.fourseveneightnine.tv.client.data.streams.StreamSearchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamFilterRulesTest {

    private val cached4k = row("a", quality = "4K", cached = true, languages = listOf("English"))
    private val plain1080 = row("b", quality = "1080p", cached = false, languages = listOf("Tamil"))
    private val cached1080 = row("c", quality = "1080p", cached = true, languages = listOf("English", "Hindi"))
    private val noLanguage = row("d", quality = "720p", cached = false, languages = emptyList())

    @Test
    fun `All keeps every row`() {
        val filters = StreamFilters()
        assertTrue(filters.isAll)
        listOf(cached4k, plain1080, cached1080, noLanguage).forEach {
            assertTrue(StreamFilterRules.matches(it, filters))
        }
    }

    @Test
    fun `Cached keeps only the rows an add-on claimed`() {
        val filters = StreamFilters(cached = true)
        assertTrue(StreamFilterRules.matches(cached4k, filters))
        assertFalse(StreamFilterRules.matches(plain1080, filters))
    }

    @Test
    fun `quality chips are exact, not a floor`() {
        assertTrue(StreamFilterRules.matches(cached4k, StreamFilters(fourK = true)))
        assertFalse(StreamFilterRules.matches(cached1080, StreamFilters(fourK = true)))
        assertTrue(StreamFilterRules.matches(cached1080, StreamFilters(fullHd = true)))
    }

    @Test
    fun `a language chip matches whatever case the add-on wrote`() {
        val filters = StreamFilters(languages = setOf("english"))
        assertTrue(StreamFilterRules.matches(cached4k, filters))
        assertFalse(StreamFilterRules.matches(plain1080, filters))
    }

    @Test
    fun `Other keeps the rows no named chip covers`() {
        val filters = StreamFilters(languages = setOf("English"), otherLanguages = true)
        assertTrue(StreamFilterRules.matches(cached4k, filters))
        assertTrue(StreamFilterRules.matches(plain1080, filters))
    }

    @Test
    fun `at most three language chips, most common first`() {
        val rows = listOf(
            row("1", languages = listOf("English")),
            row("2", languages = listOf("English")),
            row("3", languages = listOf("English")),
            row("4", languages = listOf("Tamil")),
            row("5", languages = listOf("Tamil")),
            row("6", languages = listOf("Hindi")),
            row("7", languages = listOf("Telugu")),
        )

        val chips = StreamFilterRules.languageChips(rows)

        assertEquals(StreamFilterRules.LANGUAGE_CHIP_CAP, chips.size)
        assertEquals(listOf("English", "Tamil", "Hindi"), chips)
        assertTrue(StreamFilterRules.hasOtherLanguages(rows, chips))
    }

    @Test
    fun `a tie breaks on the name, so the chip row never reshuffles`() {
        val rows = listOf(row("1", languages = listOf("Zulu")), row("2", languages = listOf("Arabic")))
        assertEquals(listOf("Arabic", "Zulu"), StreamFilterRules.languageChips(rows))
    }

    @Test
    fun `Other is not offered when the three chips already cover everything`() {
        val rows = listOf(row("1", languages = listOf("English")), row("2", languages = listOf("Tamil")))
        val chips = StreamFilterRules.languageChips(rows)
        assertFalse(StreamFilterRules.hasOtherLanguages(rows, chips))
    }
}

class StreamListOrderTest {

    @Test
    fun `with no focus the fresh ranking is taken whole`() {
        val current = ranked("a", "b")
        val fresh = ranked("c", "a", "b")

        assertEquals(listOf("c", "a", "b"), StreamListOrder.merge(current, fresh, null).ids())
    }

    @Test
    fun `a new row lands below the focused one and nothing on screen moves`() {
        val current = ranked("a", "b", "c")
        // The ranker would put the new row first; focus is on b, so the move is held.
        val fresh = ranked("new", "a", "b", "c")

        val merged = StreamListOrder.merge(current, fresh, focusedId = "b")

        assertEquals(listOf("a", "b", "new", "c"), merged.ids())
    }

    @Test
    fun `several new rows keep the ranker's order among themselves`() {
        val current = ranked("a")
        val fresh = ranked("x", "a", "y")

        val merged = StreamListOrder.merge(current, fresh, focusedId = "a")

        assertEquals(listOf("a", "x", "y"), merged.ids())
    }

    @Test
    fun `a row already on screen keeps its place and takes its newest reasons`() {
        val current = listOf(rankedRow("a", reason = "old"), rankedRow("b", reason = "old"))
        val fresh = listOf(rankedRow("b", reason = "new"), rankedRow("a", reason = "new"))

        val merged = StreamListOrder.merge(current, fresh, focusedId = "a")

        assertEquals(listOf("a", "b"), merged.ids())
        assertEquals("new", merged.first().reasons.first().text)
    }

    @Test
    fun `an empty list takes the ranking even while a row claims focus`() {
        assertEquals(listOf("a"), StreamListOrder.merge(emptyList(), ranked("a"), "gone").ids())
    }

    private fun List<RankedRow>.ids(): List<String> = map { it.row.id }

    private fun ranked(vararg ids: String): List<RankedRow> = ids.map { rankedRow(it) }

    private fun rankedRow(id: String, reason: String = "Cached on Real-Debrid") = RankedRow(
        row = row(id),
        score = 0.0,
        reasons = listOf(RankReason(reason)),
    )
}

class StreamCountTest {

    @Test
    fun `the four sentences section ten point seven gives`() {
        assertEquals(
            "Searching · 0 of 4 add-ons",
            StreamCount.text(StreamSearchState(attempted = 4, pending = 4)),
        )
        assertEquals(
            "7 sources · 2 add-ons pending",
            StreamCount.text(StreamSearchState(rows = rows(7), attempted = 4, pending = 2)),
        )
        assertEquals(
            "12 sources · 1 add-on failed",
            StreamCount.text(StreamSearchState(rows = rows(12), attempted = 4, failed = 1, done = true)),
        )
        assertEquals(
            "12 sources",
            StreamCount.text(StreamSearchState(rows = rows(12), attempted = 4, done = true)),
        )
    }

    @Test
    fun `one source is not "1 sources"`() {
        assertEquals("1 source", StreamCount.text(StreamSearchState(rows = rows(1), attempted = 1, done = true)))
    }

    @Test
    fun `only a finished search with a failure draws the chip in warning`() {
        assertTrue(
            StreamCount.namesAFailure(StreamSearchState(rows = rows(3), attempted = 4, failed = 1, done = true)),
        )
        assertFalse(
            StreamCount.namesAFailure(StreamSearchState(rows = rows(3), attempted = 4, pending = 1, failed = 1)),
        )
    }

    @Test
    fun `a box with no stream add-ons says so`() {
        assertEquals("No add-ons", StreamCount.text(StreamSearchState(done = true)))
    }

    private fun rows(count: Int) = (1..count).map { row("row-$it") }
}

internal fun row(
    id: String,
    quality: String? = "1080p",
    cached: Boolean = false,
    languages: List<String> = emptyList(),
) = StreamRow(
    id = id,
    addonName = "AIOStreams",
    title = quality.orEmpty(),
    releaseName = "The.Bear.S02E04.$quality.WEB-DL-FLUX",
    url = "https://example/$id",
    quality = quality,
    audioLanguages = languages,
    cachedHint = CachedHint(cached = cached),
)

class SourceEpisodeNavigationTest {
    private val episodes = listOf(
        Episode(1, 1, "Pilot", id = "1:1"),
        Episode(1, 2, "Second", id = "1:2"),
        Episode(2, 1, "New season", id = "2:1"),
    )

    @Test
    fun `Next and Previous cross a season boundary`() {
        assertEquals(2 to 1, SourceEpisodeNavigation.adjacent(episodes, 1, 2, 1)?.let { it.season to it.episode })
        assertEquals(1 to 2, SourceEpisodeNavigation.adjacent(episodes, 2, 1, -1)?.let { it.season to it.episode })
        assertEquals(null, SourceEpisodeNavigation.adjacent(episodes, 2, 1, 1))
    }

    @Test
    fun `choosing a season picks its first real episode`() {
        assertEquals(2 to 1, SourceEpisodeNavigation.firstInSeason(episodes, 2)?.let { it.season to it.episode })
    }

    @Test
    fun `WEB DL is distinct from WEBRip`() {
        assertEquals("WEB-DL", sourceReleaseType("Film.1080p.WEB-DL.x265"))
        assertEquals("WEBRip", sourceReleaseType("Film.1080p.WEBRip.x265"))
        assertEquals(null, sourceReleaseType("Film.1080p.BluRayRip"))
        assertEquals("HDR10+", sourceHdrLabel("Film.2160p.HDR10+.DV"))
        assertEquals("HDR10+", sourceHdrLabel("Film.2160p.HDR10Plus.DV"))
    }
}
