package com.fourseveneightnine.tv.client.data.streams

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPickTest {

    private fun row(id: String, title: String, cached: Boolean = true, sizeBytes: Long? = null): StreamRow {
        val facts = StreamFacts.parse(listOf(title), sizeBytes = sizeBytes)
        return StreamRow(
            id = id,
            addonName = "AIOStreams",
            title = title,
            releaseName = title,
            url = "https://host.example.com/$id.mkv",
            quality = facts.quality,
            sizeBytes = facts.sizeBytes,
            codecs = facts.codecs,
            hdr = facts.hdr,
            cachedHint = CachedHint(cached),
            facts = facts,
        )
    }

    private val autoPlay = PlaybackRules(enabled = true, readyOnly = false, autoPlay = true, autoPlayMinSources = 3)

    private val threeGoodRows = listOf(
        row("a", "Movie.2024.1080p.WEB-DL.x265 📁 4 GB · 12 Mbps"),
        row("b", "Movie.2024.1080p.WEB-DL.x264 📁 6 GB · 15 Mbps"),
        row("c", "Movie.2024.720p.WEB-DL.x264 📁 2 GB · 6 Mbps"),
    )

    @Test
    fun `auto-play commits when the rules asked and enough sources are in hand`() {
        val ranked = StreamRanker.rank(threeGoodRows, autoPlay)
        val decision = AutoPick.decide(ranked, autoPlay)
        assertTrue(decision is AutoPickDecision.PlayBest)
        assertEquals(ranked.first().row.id, (decision as AutoPickDecision.PlayBest).choice.row.id)
    }

    @Test
    fun `the card line names the add-on, the quality and the cached state`() {
        val ranked = StreamRanker.rank(threeGoodRows, autoPlay)
        val decision = AutoPick.decide(ranked, autoPlay) as AutoPickDecision.PlayBest
        assertEquals("AIOStreams · 1080p · cached", decision.why)
    }

    @Test
    fun `rules off always shows the list`() {
        val ranked = StreamRanker.rank(threeGoodRows)
        assertEquals(
            AutoPickDecision.ShowList(AutoPickDecision.Reason.RULES_OFF),
            AutoPick.decide(ranked, PlaybackRules.DEFAULT),
        )
    }

    @Test
    fun `auto-play off inside enabled rules still shows the list`() {
        val rules = autoPlay.copy(autoPlay = false)
        val ranked = StreamRanker.rank(threeGoodRows, rules)
        assertEquals(
            AutoPickDecision.ShowList(AutoPickDecision.Reason.RULES_OFF),
            AutoPick.decide(ranked, rules),
        )
    }

    @Test
    fun `too few sources holds the decision, so the first add-on cannot win by default`() {
        val ranked = StreamRanker.rank(threeGoodRows.take(2), autoPlay)
        assertEquals(
            AutoPickDecision.ShowList(AutoPickDecision.Reason.TOO_FEW_SOURCES),
            AutoPick.decide(ranked, autoPlay),
        )
    }

    @Test
    fun `a minimum of one plays the first eligible thing that lands`() {
        val rules = autoPlay.copy(autoPlayMinSources = 1)
        val ranked = StreamRanker.rank(threeGoodRows.take(1), rules)
        assertTrue(AutoPick.decide(ranked, rules) is AutoPickDecision.PlayBest)
    }

    @Test
    fun `nothing found shows the list`() {
        assertEquals(
            AutoPickDecision.ShowList(AutoPickDecision.Reason.NOTHING_ELIGIBLE),
            AutoPick.decide(emptyList(), autoPlay),
        )
    }

    @Test
    fun `a caution on the best row hands the decision back to a person`() {
        // Every copy of this title is Dolby Vision, and this box has no DV decoder. Nothing is
        // excluded — a bad picture beats no picture — but the top row carries a caution, so the
        // decision goes back to a person instead of starting on its own.
        val rows = listOf(
            row("a", "Movie.2024.2160p.WEB-DL.DV.dvhe.05.HEVC 📁 18 GB · 38 Mbps", sizeBytes = 18_000_000_000),
            row("b", "Movie.2024.2160p.WEB-DL.DV.HEVC 📁 20 GB · 40 Mbps", sizeBytes = 20_000_000_000),
            row("c", "Movie.2024.1080p.WEB-DL.DV.HEVC 📁 6 GB · 14 Mbps", sizeBytes = 6_000_000_000),
        )
        val ranked = StreamRanker.rank(rows, autoPlay, hardwareVideoCodecs = setOf("hevc"))
        assertTrue(ranked.all(RankedRow::eligible))
        assertTrue(ranked.first().cautions.isNotEmpty())
        assertEquals(
            AutoPickDecision.ShowList(AutoPickDecision.Reason.BEST_ROW_HAS_A_CAUTION),
            AutoPick.decide(ranked, autoPlay),
        )
    }

    @Test
    fun `when nothing is eligible the never-empty guard does not become an auto-play`() {
        val rules = autoPlay.copy(minQualityRank = 5)
        val ranked = StreamRanker.rank(threeGoodRows, rules)
        assertEquals(
            AutoPickDecision.ShowList(AutoPickDecision.Reason.NOTHING_ELIGIBLE),
            AutoPick.decide(ranked, rules),
        )
    }
}
