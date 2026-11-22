package com.fourseveneightnine.tv.client.ui.screens.streams

import com.fourseveneightnine.tv.client.data.meta.Episode
import com.fourseveneightnine.tv.client.data.streams.CachedHint
import com.fourseveneightnine.tv.client.data.streams.RankReason
import com.fourseveneightnine.tv.client.data.streams.RankedRow
import com.fourseveneightnine.tv.client.data.streams.StreamRow
import com.fourseveneightnine.tv.client.data.streams.StreamSearchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamFilterRulesTest {

    private val cached4k = row("a", quality = "4K", cached = true, languages = listOf("English"))
    private val plain1080 = row("b", quality = "1080p", cached = false, languages = listOf("Tamil"))
    private val cached1080 = row("c", quality = "1080p", cached = true, languages = listOf("English", "Hindi"))
    private val noLanguage = row("d", quality = "720p", cached = false, languages = emptyList())

    @Test fun `route filters distinguish debrid delivery from a plain HTTPS URL`() {
        val debrid = cached4k.copy(cachedHint = CachedHint(true, "TorBox", verified = true))
        assertTrue(StreamFilterRules.matches(debrid, StreamFilters(kind = SourceKind.DEBRID)))
        assertFalse(StreamFilterRules.matches(debrid, StreamFilters(kind = SourceKind.HTTPS)))
        assertTrue(StreamFilterRules.matches(plain1080, StreamFilters(kind = SourceKind.HTTPS)))
        assertFalse(StreamFilterRules.matches(plain1080, StreamFilters(kind = SourceKind.DEBRID)))
        assertFalse(StreamFilters(kind = SourceKind.HTTPS).isAll)
        val refreshed = debrid.copy(cachedHint = CachedHint(false, null))
        assertTrue(refreshed.directHttp)
        assertTrue(StreamFilterRules.matches(refreshed, StreamFilters(kind = SourceKind.HTTPS)))
        assertFalse(StreamFilterRules.matches(refreshed, StreamFilters(kind = SourceKind.DEBRID)))
    }

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
    addonName = "Test add-on",
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


class SourceIdentifierTest {
    @Test fun `episode-specific video ID is kept and aliases carry the same season and episode`() {
        val meta = com.fourseveneightnine.tv.client.data.meta.Meta(
            id = "tmdb:42", type = "series", title = "Show", imdbID = "tt42", tmdbID = 42,
            videos = listOf(com.fourseveneightnine.tv.client.data.meta.Episode(2, 3, "Episode", id = "library:file:19")),
        )
        assertEquals(listOf("library:file:19", "tt42:2:3", "tmdb:series:42:2:3", "tmdb:42:2:3"),
            com.fourseveneightnine.tv.client.playback.StreamIds.candidates("series", "catalog::tmdb:series:42", meta, 2, 3))
    }
}

class SourceAvailabilityTest {
    @Test fun `unreported audio remains visible in All`() {
        val source = row("unknown")
        val state = StreamsUiState(
            search = StreamSearchState(rows = listOf(source), attempted = 1, done = true),
            displayed = listOf(RankedRow(source, 0.0, emptyList())),
        )
        assertEquals(listOf(source), state.visible.map(RankedRow::row))
        assertEquals("1 source", state.countChip)
        assertFalse(state.isEmpty)
    }

    @Test fun `a partial failure is not an all add-on failure`() {
        val state = StreamsUiState(search = StreamSearchState(attempted = 2, failed = 1, done = true))
        assertTrue(state.isEmpty)
        assertFalse(state.isError)
        assertTrue(state.emptyMessage.contains("Some add-ons"))
    }
}

class SourceQualityGroupsTest {
    @Test fun `only the retained failed copy stays compact with its published position and reason`() {
        val failed = ranked("failed", "4K").let { source -> source.copy(eligible = false,
            reasons = listOf(RankReason("Link unavailable", caution = true)),
            row = source.row.copy(cachedHint = CachedHint(true, "TorBox"))) }
        val otherFailure = ranked("other-failure", "4K").copy(eligible = false,
            reasons = listOf(RankReason("Link unavailable", caution = true)))
        val rows = listOf(failed, ranked("first4k", "4K"), ranked("second4k", "4K"),
            ranked("extra4k", "4K"), otherFailure, ranked("firsthd", "1080p"),
            ranked("secondhd", "1080p"), ranked("extrahd", "1080p"))
        val fresh = SourceQualityGroups.from(rows).flatMap { it.rows }
        assertEquals(listOf("first4k", "second4k", "firsthd", "secondhd"), fresh.map { it.row.id })
        val kept = SourceQualityGroups.from(rows, retainedId = "failed").flatMap { it.rows }
        assertEquals(listOf("failed", "first4k", "second4k", "firsthd", "secondhd"), kept.map { it.row.id })
        assertEquals("Link unavailable", kept.first().cautions.first().text)
        assertEquals("failed", SourceQualityGroups.entryId(kept, "failed"))
        assertFalse(kept.any { it.row.id == "other-failure" })
        assertEquals(fresh, SourceQualityGroups.from(rows, retainedId = "first4k").flatMap { it.rows })
        assertEquals(fresh, SourceQualityGroups.from(rows, retainedId = "removed").flatMap { it.rows })
        assertEquals(SourceQualityGroups.from(rows, expanded = true),
            SourceQualityGroups.from(rows, expanded = true, retainedId = "failed"))
    }

    @Test fun `retention does not admit other exclusions rejected identities or unready delivery`() {
        val policy = ranked("policy", "4K").copy(eligible = false,
            reasons = listOf(RankReason("Outside playback rules", caution = true)))
        val codec = ranked("codec", "4K").copy(decodeCompatible = false)
        val lower = ranked("lower", "720p")
        val rejected = ranked("rejected", "4K").let { source -> source.copy(eligible = false,
            reasons = listOf(RankReason("Link unavailable", caution = true)),
            row = source.row.copy(addonName = "DMM Cast fixture", identityVerified = false,
                cachedHint = CachedHint(true, "TorBox", verified = true), linkCheckedAtMillis = 1L)) }
        val unready = ranked("unready", "4K").let { source -> source.copy(eligible = false,
            reasons = listOf(RankReason("Link unavailable", caution = true)),
            row = source.row.copy(cachedHint = CachedHint(false, "TorBox"))) }
        val rows = listOf(policy, codec, lower, rejected, unready, ranked("ready", "1080p"))
        for (retained in listOf("policy", "codec", "lower", "rejected", "unready", "missing")) {
            assertEquals(listOf("ready"), SourceQualityGroups.from(rows, retainedId = retained)
                .flatMap { it.rows }.map { it.row.id })
        }
    }

    @Test fun `playing reservation and cached first survive exact failed retention together`() {
        fun cached(id: String, quality: String) = ranked(id, quality).let {
            it.copy(row = it.row.copy(cachedHint = CachedHint(true, "TorBox")))
        }
        val failed = cached("failed", "4K").copy(eligible = false,
            reasons = listOf(RankReason("Link unavailable", caution = true)))
        val rows = listOf(ranked("direct4k", "4K"), failed, cached("cached4k", "4K"),
            cached("extra4k", "4K"), ranked("directhd", "1080p"), cached("cachedhd", "1080p"))
        val groups = SourceQualityGroups.from(rows, playingQuality = "4K", retainedId = "failed")
        assertEquals(listOf("Cached Debrid", "Direct"), groups.map { it.label })
        assertEquals(listOf("failed", "cached4k", "cachedhd"), groups.first().rows.map { it.row.id })
        assertEquals(listOf("directhd"), groups.last().rows.map { it.row.id })
        val chosen = groups.flatMap { it.rows }
        assertEquals(1, chosen.count { it.eligible && it.row.quality == "4K" })
        assertEquals(2, chosen.count { it.eligible && it.row.quality == "1080p" })
        assertEquals("failed", SourceQualityGroups.entryId(chosen, "failed"))
        val released = SourceQualityGroups.from(rows, playingQuality = "4K", retainedId = "cached4k")
        assertFalse(released.flatMap { it.rows }.any { it.row.id == "failed" })
        assertEquals(SourceQualityGroups.from(rows, expanded = true, playingQuality = "4K"),
            SourceQualityGroups.from(rows, expanded = true, playingQuality = "4K", retainedId = "failed"))
    }

    @Test fun `a provider URL without cache evidence stays out of both sections`() {
        val tb = ranked("tb", "4K").let { it.copy(row = it.row.copy(cachedHint = CachedHint(false, "TorBox"))) }
        val groups = SourceQualityGroups.from(listOf(tb, ranked("direct", "4K")), expanded = true)
        assertEquals(listOf("Direct"), groups.map { it.label })
        assertEquals(listOf("direct"), groups.single().rows.map { it.row.id })
    }

    @Test fun `fresh focus follows displayed group order even when the first source has an audio caution`() {
        val hd = ranked("hd", "1080p")
        val uhd = ranked("uhd", "4K").copy(reasons = listOf(RankReason("Audio language not reported", caution = true)))
        val displayed = SourceQualityGroups.from(listOf(hd, uhd)).flatMap { it.rows }
        assertEquals("uhd", SourceQualityGroups.entryId(displayed, null))
        assertEquals("hd", SourceQualityGroups.entryId(displayed, "hd"))
    }

    @Test fun `both sections share two slots per resolution and expand in ranked order`() {
        fun cached(id: String, quality: String) = ranked(id, quality).let {
            it.copy(row = it.row.copy(cachedHint = CachedHint(true, "TorBox")))
        }
        val rows = listOf(ranked("b", "4K"), cached("a", "4K"), cached("c", "4K"),
            ranked("d", "1080p"), cached("e", "1080p"), ranked("f", "1080p"), ranked("g", "720p"))
        val picks = SourceQualityGroups.from(rows).associate { it.label to it.rows.map { source -> source.row.id } }
        assertEquals(listOf("Cached Debrid", "Direct"), picks.keys.toList())
        assertEquals(mapOf("Cached Debrid" to listOf("a", "c", "e"), "Direct" to listOf("d")), picks)
        val playing4K = SourceQualityGroups.from(rows, playingQuality = "4K")
        assertEquals(listOf("a", "e", "d"), playing4K.flatMap { it.rows }.map { it.row.id })
        val playingHD = SourceQualityGroups.from(rows, playingQuality = "1080p")
        assertEquals(listOf("a", "c", "e"), playingHD.flatMap { it.rows }.map { it.row.id })
        val all = SourceQualityGroups.from(rows, expanded = true)
        assertEquals(listOf("a", "c", "e"), all.first().rows.map { it.row.id })
        assertEquals(listOf("b", "d", "f", "g"), all.last().rows.map { it.row.id })
    }

    @Test fun `initial unchecked row does not freeze ordering before a checked source arrives`() {
        val direct = ranked("direct", "4K")
        val checked = ranked("checked", "1080p").let {
            it.copy(row = it.row.copy(identityVerified = true,
                cachedHint = com.fourseveneightnine.tv.client.data.streams.CachedHint(true, "TorBox", verified = true)))
        }
        assertFalse(StreamListOrder.canRequestEntryFocus(listOf(direct), false, null))
        assertEquals("checked", StreamListOrder.merge(listOf(direct), listOf(checked, direct), null).first().row.id)
        assertTrue(StreamListOrder.canRequestEntryFocus(listOf(checked, direct), false, null))
        assertTrue(StreamListOrder.canRequestEntryFocus(listOf(direct), false, "direct"))
        assertEquals("direct", StreamListOrder.merge(listOf(direct), listOf(checked, direct), "direct").first().row.id)
    }

    private fun ranked(id: String, quality: String?) = RankedRow(row(id, quality), 0.0, emptyList())

    @Test fun `checked cache does not put an unsupported format above a playable quality group`() {
        val unsupported = ranked("dv", "4K").let {
            it.copy(decodeCompatible = false, row = it.row.copy(identityVerified = true,
                cachedHint = com.fourseveneightnine.tv.client.data.streams.CachedHint(true, "TorBox", verified = true)))
        }
        val groups = SourceQualityGroups.from(listOf(unsupported, ranked("playable", "1080p")))
        assertEquals("playable", groups.first().rows.first().row.id)
    }

    @Test
    fun `entry prefers the current playable rank and preserves a returning source`() {
        val unavailable = ranked("unavailable", "4K").copy(eligible = false)
        val best = ranked("best", "1080p")
        val returning = ranked("returning", "4K")
        val visible = listOf(unavailable, best, returning)
        assertEquals("best", SourceQualityGroups.entryId(visible, null))
        assertEquals("returning", SourceQualityGroups.entryId(visible, "returning"))
        assertEquals("best", SourceQualityGroups.entryId(visible, "missing"))
        assertNull(SourceQualityGroups.entryId(emptyList(), null))
    }

    @Test fun `quality groups descend while preserving the balanced ranking inside each group`() {
        val groups = SourceQualityGroups.from(listOf(ranked("hd", "1080p"), ranked("better4k", "4K"),
            ranked("next4k", "4K"), ranked("unknown", null), ranked("small", "720p")))
        assertEquals("Direct", groups.single().label)
        assertEquals(listOf("better4k", "next4k", "hd"), groups.single().rows.map { it.row.id })
    }

    @Test fun `source restoration counts group headings instead of scrolling to a different source`() {
        val groups = SourceQualityGroups.from(listOf(ranked("first", "4K"), ranked("second", "4K"), ranked("hd", "1080p")))
        assertEquals(1, SourceQualityGroups.listIndex(groups, "first"))
        assertEquals(2, SourceQualityGroups.listIndex(groups, "second"))
        assertEquals(3, SourceQualityGroups.listIndex(groups, "hd"))
        assertEquals(0, SourceQualityGroups.listIndex(groups, "gone"))
    }

    @Test fun `filtered count matches the rows that can actually be seen`() {
        val cached = row("cached", cached = true)
        val direct = row("direct")
        val state = StreamsUiState(search = StreamSearchState(rows = listOf(cached, direct), attempted = 1, done = true),
            displayed = listOf(RankedRow(cached, 0.0, emptyList()), RankedRow(direct, 0.0, emptyList())),
            filters = StreamFilters(cached = true))
        assertEquals("1 source", state.countChip)
        assertFalse(StreamFilterRules.matches(direct, state.filters))
    }
}
