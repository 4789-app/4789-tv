package com.fourseveneightnine.tv.client.data.streams

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamRankerTest {

    private fun row(
        id: String,
        title: String,
        cached: Boolean = false,
        service: String? = null,
        sizeBytes: Long? = null,
        seeders: Int? = null,
        url: String? = "https://host.example.com/$id.mkv",
        infoHash: String? = null,
    ): StreamRow {
        val facts = StreamFacts.parse(listOf(title), sizeBytes = sizeBytes, seeders = seeders)
        return StreamRow(
            id = id,
            addonName = "Test add-on",
            title = title,
            releaseName = title,
            url = url,
            infoHash = infoHash,
            quality = facts.quality,
            sizeBytes = facts.sizeBytes,
            codecs = facts.codecs,
            hdr = facts.hdr,
            audioLanguages = facts.audioLanguages,
            cachedHint = CachedHint(cached, service),
            seeders = facts.seeders,
            facts = facts,
        )
    }

    private val on = PlaybackRules(enabled = true, readyOnly = false)

    // MARK: - One rule, one test

    @Test
    fun `rule - a quality floor keeps 1080p and better`() {
        val rows = listOf(
            row("a", "Movie.720p.WEB-DL.x264"),
            row("b", "Movie.1080p.WEB-DL.x265"),
            row("c", "Movie.2160p.WEB-DL.x265"),
        )
        val kept = on.copy(minQualityRank = 3).eligible(rows).map(StreamRow::id)
        assertEquals(listOf("b", "c"), kept)
    }

    @Test
    fun `rule - a size cap keeps a row that never stated a size`() {
        val rows = listOf(
            row("big", "Movie.2160p.REMUX", sizeBytes = 54_000_000_000),
            row("small", "Movie.1080p.WEB", sizeBytes = 4_000_000_000),
            row("unknown", "Movie.1080p.WEB.NoSize"),
        )
        val kept = on.copy(maxSizeGB = 8.0).eligible(rows).map(StreamRow::id)
        assertEquals(listOf("small", "unknown"), kept)
    }

    @Test
    fun `rule - ready only keeps the cached rows`() {
        val rows = listOf(
            row("cached", "Movie.1080p.WEB", cached = true),
            row("hash", "Movie.1080p.WEB", url = null, infoHash = "a".repeat(40)),
        )
        val kept = PlaybackRules(enabled = true, readyOnly = true).eligible(rows).map(StreamRow::id)
        assertEquals(listOf("cached"), kept)
    }

    @Test
    fun `rule - hdr only keeps HDR and DV`() {
        val rows = listOf(
            row("sdr", "Movie.2160p.WEB.HEVC"),
            row("hdr", "Movie.2160p.WEB.HDR10.HEVC"),
            row("dv", "Movie.2160p.WEB.DV.HEVC"),
        )
        assertEquals(listOf("hdr", "dv"), on.copy(hdrOnly = true).eligible(rows).map(StreamRow::id))
    }

    @Test
    fun `rule - hevc only`() {
        val rows = listOf(row("avc", "Movie.1080p.x264"), row("hevc", "Movie.1080p.x265"))
        assertEquals(listOf("hevc"), on.copy(hevcOnly = true).eligible(rows).map(StreamRow::id))
    }

    @Test
    fun `rule - atmos only`() {
        val rows = listOf(
            row("ddp", "Movie.1080p.DDP5.1"),
            row("atmos", "Movie.2160p.TrueHD.7.1.Atmos"),
        )
        assertEquals(listOf("atmos"), on.copy(atmosOnly = true).eligible(rows).map(StreamRow::id))
    }

    @Test
    fun `rule - a seeder floor never touches a cached row, and never punishes silence`() {
        val rows = listOf(
            row("cachedLowSwarm", "Movie.1080p 👤 2", cached = true),
            row("uncachedLowSwarm", "Movie.1080p 👤 2", url = null, infoHash = "b".repeat(40)),
            row("uncachedNoSwarm", "Movie.1080p", url = null, infoHash = "c".repeat(40)),
        )
        val kept = on.copy(minSeeders = 10).eligible(rows).map(StreamRow::id)
        assertEquals(listOf("cachedLowSwarm", "uncachedNoSwarm"), kept)
    }

    @Test
    fun `rule - a short exclusion keyword matches a whole token, never a substring`() {
        val rows = listOf(
            row("cam", "Movie.2024.HDCAM.x264-JUNK"),
            row("ts", "Movie.2024.TS.x264"),
            // "ts" must not delete "Ghosts", and "tc" must not delete "Dutch".
            row("ghosts", "Ghosts.of.War.2020.1080p.WEB-DL"),
            row("dutch", "Movie.2024.1080p.Dutch.WEB-DL"),
        )
        val kept = on.copy(excludeKeywords = PlaybackRules.JUNK_KEYWORDS).eligible(rows).map(StreamRow::id)
        assertEquals(listOf("ghosts", "dutch"), kept)
    }

    @Test
    fun `rule - a long exclusion keyword still matches a substring, which is what a group name needs`() {
        val rows = listOf(row("a", "Movie.2024.1080p.WEB-DL-SOMEGROUP"), row("b", "Movie.2024.1080p.WEB-DL-OTHER"))
        val kept = on.copy(excludeKeywords = listOf("somegroup")).eligible(rows).map(StreamRow::id)
        assertEquals(listOf("b"), kept)
    }

    @Test
    fun `rule - rules off filters nothing`() {
        val rows = listOf(row("a", "Movie.2024.HDCAM"), row("b", "Movie.2024.1080p"))
        val off = PlaybackRules(enabled = false, minQualityRank = 5, excludeKeywords = PlaybackRules.JUNK_KEYWORDS)
        assertEquals(rows, off.eligible(rows))
    }

    // MARK: - Ordering

    @Test
    fun `cached comes first`() {
        val rows = listOf(
            row("uncached", "Movie.2160p.WEB.HEVC 📁 8 GB · 20 Mbps", url = null, infoHash = "d".repeat(40)),
            row("cached", "Movie.1080p.WEB.HEVC 📁 4 GB · 12 Mbps", cached = true, service = "TorBox"),
        )
        val ranked = StreamRanker.rank(rows)
        assertEquals("cached", ranked.first().row.id)
        assertTrue(ranked.first().reasons.any { it.text == "Cached on TorBox" })
    }

    @Test
    fun `dolby vision is demoted on a box with no DV decoder and the pane says why`() {
        val rows = listOf(
            row("dv", "Movie.2160p.WEB.DV.dvhe.05.HEVC 📁 18 GB", cached = true),
            row("hdr", "Movie.2160p.WEB.HDR10.HEVC 📁 16 GB", cached = true),
        )
        val withoutDV = StreamRanker.rank(rows, hardwareVideoCodecs = setOf("hevc", "av1"))
        assertEquals("hdr", withoutDV.first().row.id)
        val demoted = withoutDV.first { it.row.id == "dv" }
        assertTrue(
            demoted.reasons.any {
                it.caution && it.text == "Dolby Vision profile 5, and this box has no DV decoder"
            },
        )

        val withDV = StreamRanker.rank(rows, hardwareVideoCodecs = setOf("hevc", "dolbyvision"))
        assertEquals("dv", withDV.first().row.id)
        assertTrue(withDV.first().cautions.isEmpty())
    }

    @Test
    fun `a preferred audio language lifts a row and names itself`() {
        val rows = listOf(
            row("other", "Movie.1080p.WEB.HEVC.Spanish", cached = true),
            row("english", "Movie.1080p.WEB.HEVC.English", cached = true),
        )
        val ranked = StreamRanker.rank(rows, on.copy(preferredAudioLanguages = listOf("English")))
        assertEquals("english", ranked.first().row.id)
        assertTrue(ranked.first().reasons.any { it.text == "English audio" })
    }

    @Test
    fun `a row over the cap is marked, with the cap in the sentence`() {
        val rows = listOf(row("big", "Movie.2160p.REMUX", cached = true, sizeBytes = 54_000_000_000))
        val ranked = StreamRanker.rank(rows, on.copy(maxSizeGB = 8.0))
        assertTrue(ranked.single().reasons.any { it.caution && it.text == "Bigger than your 8 GB cap" })
    }

    @Test
    fun `a row under the cap says so with its own size`() {
        val rows = listOf(row("ok", "Movie.1080p.WEB", cached = true, sizeBytes = 4_520_902_164))
        val ranked = StreamRanker.rank(rows, on.copy(maxSizeGB = 8.0))
        assertTrue(ranked.single().reasons.any { it.text == "4.21 GB, under your 8 GB cap" })
    }

    @Test
    fun `an avoided word is named in the caution`() {
        val rows = listOf(row("cam", "Movie.2024.CAM.x264", cached = true))
        val ranked = StreamRanker.rank(rows, on.copy(excludeKeywords = listOf("cam")))
        assertTrue(ranked.single().reasons.any { it.caution && it.text == "Name holds a word you avoid: CAM" })
    }

    // MARK: - Guarantees

    @Test
    fun `a non-empty input never gives an empty output`() {
        val rows = listOf(row("a", "Movie.480p.CAM.x264"), row("b", "Movie.480p.TS.x264"))
        val strict = PlaybackRules(
            enabled = true,
            minQualityRank = 5,
            readyOnly = true,
            excludeKeywords = PlaybackRules.JUNK_KEYWORDS,
        )
        val ranked = StreamRanker.rank(rows, strict)
        assertEquals(2, ranked.size)
        // Nothing passed, so nothing claims to have passed — but the list still plays.
        assertTrue(ranked.none(RankedRow::eligible))
    }

    @Test
    fun `an empty input gives an empty output`() {
        assertTrue(StreamRanker.rank(emptyList()).isEmpty())
    }

    @Test
    fun `eligible rows always sort above ineligible ones`() {
        val rows = listOf(
            row("cam4k", "Movie.2160p.HDCAM.HEVC 📁 20 GB", cached = true),
            row("clean720", "Movie.720p.WEB.x264 📁 2 GB", cached = true),
        )
        val ranked = StreamRanker.rank(rows, on.copy(excludeKeywords = listOf("hdcam")))
        assertEquals("clean720", ranked.first().row.id)
        assertTrue(ranked.first().eligible)
        assertFalse(ranked.last().eligible)
    }

    @Test
    fun `two identical rows break their tie on id, so the order never wobbles`() {
        val rows = listOf(row("zz", "Movie.1080p.WEB", cached = true), row("aa", "Movie.1080p.WEB", cached = true))
        assertEquals(listOf("aa", "zz"), StreamRanker.rank(rows).map { it.row.id })
        assertEquals(
            StreamRanker.rank(rows).map { it.row.id },
            StreamRanker.rank(rows.reversed()).map { it.row.id },
        )
    }

    @Test
    fun `the opening sort follows the rules`() {
        val rows = listOf(
            row("big", "Movie.2160p.WEB", cached = true, sizeBytes = 20_000_000_000),
            row("small", "Movie.720p.WEB", cached = true, sizeBytes = 2_000_000_000),
        )
        assertEquals("big", StreamRanker.rank(rows, on).first().row.id)
        assertEquals("small", StreamRanker.rank(rows, on.copy(sort = StreamSort.SIZE)).first().row.id)
    }

    @Test
    fun `the facts pane drops a pair it has no value for`() {
        val bare = row("bare", "Movie.1080p.WEB", url = null, infoHash = "e".repeat(40))
        val labels = StreamRanker.facts(bare).map { it.first }
        assertFalse(labels.contains("Size"))
        assertFalse(labels.contains("Host"))
        assertTrue(labels.contains("Add-on"))
    }

    @Test
    fun `the facts pane names the host for a direct URL`() {
        val direct = row("direct", "Movie.1080p.WEB.HEVC 📁 4.21 GB", cached = true, sizeBytes = 4_520_902_164)
        val facts = StreamRanker.facts(direct).toMap()
        assertEquals("host.example.com", facts["Host"])
        assertEquals("4.21 GB", facts["Size"])
        assertNotEquals(null, facts["Video"])
    }
}
