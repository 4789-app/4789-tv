package com.fourseveneightnine.tv.client.data.streams

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamRankerTest {

    @Test fun `one new addon can return both direct and debrid routes`() {
        val stream = row("new", "Film.2026.1080p").copy(addonName = "New mixed addon")
        assertEquals("Direct", StreamRanker.deliveryLabel(stream))
        assertTrue(stream.copy(infoHash = "a".repeat(40)).directHttp)
        val magnet = stream.copy(url = null, infoHash = "a".repeat(40))
        assertTrue(magnet.debridBacked)
        assertFalse(magnet.readyHint)
        val resolved = stream.copy(url = "https://cdn.torbox.app/file")
        assertFalse(resolved.directHttp)
        assertFalse(resolved.readyHint)
        assertTrue(stream.copy(url = "https://torbox.app.example/video").directHttp)
        assertEquals("Cached Debrid", StreamRanker.deliveryLabel(resolved.copy(cachedHint = CachedHint(true))))
        assertFalse(stream.copy(addonName = "MediaFusion").readyHint)
        for (feed in listOf("AIOStreams", "Meteor")) {
            assertTrue(stream.copy(addonName = feed).cachedDebrid)
            assertFalse(stream.copy(addonName = feed, cachedHint = CachedHint(false, uncached = true)).readyHint)
        }
    }

    @Test fun `resolution wins within confirmed TorBox before bitrate or size score`() {
        val hd = row("hd", "Film.2026.1080p.WEB-DL.HEVC 📁 4 GB · 18 Mbps", cached = true, service = "TorBox")
            .copy(cachedHint = CachedHint(true, "TorBox", verified = true))
        val uhd = row("uhd", "Film.2026.2160p.REMUX.HEVC 📁 70 GB · 140 Mbps", cached = true, service = "TorBox")
            .copy(cachedHint = CachedHint(true, "TorBox", verified = true))
        for (sort in listOf(StreamSort.BEST, StreamSort.READY)) {
            val ranked = StreamRanker.rank(listOf(hd, uhd), on.copy(sort = sort))
            assertEquals("uhd", ranked.first().row.id)
            assertTrue(ranked.first { it.row.id == "hd" }.score > ranked.first { it.row.id == "uhd" }.score)
        }
    }

    @Test fun `manual cache hierarchy precedes autoplay size and quality preferences`() {
        val tb = row("tb", "Film.2026.1080p.WEB-DL 📁 4 GB", cached = true, service = "TorBox")
            .copy(identityVerified = true, cachedHint = CachedHint(true, "TorBox", verified = true))
        val https = row("https", "Film.2026.2160p.WEB-DL 📁 1 GB").copy(identityVerified = true)
        val preferences = on.copy(minQualityRank = 5, maxSizeGB = 2.0)
        val manual = StreamRanker.rankForSourceList(listOf(https, tb), preferences, emptySet(), null)
        assertEquals("tb", manual.first().row.id)
        assertTrue(manual.first().eligible)
        assertTrue(manual.first().cautions.any { it.text == "Outside autoplay preferences" })
        assertFalse(StreamRanker.rank(listOf(tb), preferences).single().eligible)
        val wrong = tb.copy(identityVerified = false)
        assertEquals("https", StreamRanker.rankForSourceList(listOf(wrong, https), preferences, emptySet(), null).first().row.id)
    }

    @Test fun `audio uncertainty does not move confirmed TorBox below HTTPS in the manual list`() {
        val tb = row("tb", "Film.2026.2160p", cached = true, service = "TorBox")
            .copy(cachedHint = CachedHint(true, "TorBox", verified = true))
        val https = row("https", "Film.2026.2160p.English")
        val ranked = StreamRanker.rankForSourceList(listOf(https, tb), on, emptySet(), "en")
        assertEquals("tb", ranked.first().row.id)
        assertTrue(ranked.first().eligible)
        assertTrue(ranked.first().cautions.isEmpty())
        val french = row("fr", "Film.2026.2160p.French")
        val mismatch = StreamRanker.rankForSourceList(listOf(french), on, emptySet(), "en").single()
        assertTrue(mismatch.cautions.any { it.text == "Different audio language" })
    }

    @Test fun `aliases and rich metadata survive generic filenames without overriding contradictions`() {
        val expected = StreamExpectation("Alpha", 2026, null, null, true, aliases = listOf("Alfa"))
        val generic = row("generic", "video.mkv").copy(filename = "video.mkv", identityNames = listOf("Alfa.2026.1080p"))
        assertTrue(expected.matches(generic))
        assertFalse(expected.matches(generic.copy(filename = "Other.Movie.2026.1080p.mkv")))
        assertFalse(expected.matches(generic.copy(identityNames = listOf("Alfa.2025.1080p"))))
        val episode = StreamExpectation("Show", 2022, 2, 4, false)
        assertFalse(episode.matches(generic.copy(identityNames = listOf("Show.S02E04.1080p", "Show.S02E05.1080p"))))
    }
    @Test
    fun `delivery proof and provider priority precede quality while decoder safety wins`() {
        val tb = row("tb", "Film.2026.1080p.WEB-DL", cached = true, service = "TorBox")
        val rd = row("rd", "Film.2026.2160p.REMUX", cached = true, service = "Real-Debrid")
        val checked = rd.copy(id = "rd-checked", cachedHint = CachedHint(true, "Real-Debrid", verified = true))
        val direct = row("direct", "Film.2026.2160p.WEB-DL")
        val checkedTB = tb.copy(id = "tb-checked", cachedHint = CachedHint(true, "TorBox", verified = true))
        for (sort in listOf(StreamSort.BEST, StreamSort.READY)) {
            assertEquals(listOf("tb-checked", "rd-checked", "tb", "rd", "direct"),
                StreamRanker.rank(listOf(direct, rd, tb, checked, checkedTB), on.copy(sort = sort)).map { it.row.id })
        }
        val incompatible = checkedTB.copy(id = "dv", hdr = HdrFormat.DOLBY_VISION)
        assertEquals("rd-checked", StreamRanker.rank(listOf(incompatible, checked), on).first().row.id)
    }

    @Test
    fun `theater only pool stays empty even when rules are disabled or would restore input`() {
        val cam = row("cam", "Film.2026.1080p.Pre-DVD-Rip", cached = true, service = "TorBox")
        assertTrue(StreamRanker.rank(listOf(cam)).isEmpty())
        assertTrue(PlaybackRules.DEFAULT.eligible(listOf(cam)).isEmpty())
        assertTrue(on.copy(minQualityRank = 5).eligible(listOf(cam)).isEmpty())
        assertTrue(StreamRanker.rankForSourceList(listOf(cam), on, emptySet(), "en").isEmpty())
        val englishCam = cam.copy(facts = StreamFacts.parse(listOf("Film.2026.English.HD-TS")))
        val clean = row("clean", "Film.2026.English.1080p.WEB-DL")
        assertEquals(listOf(clean), OriginalAudioLanguage.sources(listOf(englishCam, clean), "en"))
    }

    @Test
    fun `The Intern sources remain visible when Korean audio or title language is unreported`() {
        val rows = (1..34).map { index ->
            val release = "The.Intern.2026.1080p.FHD.H264.AAC.mp4"
            StreamRow("source-$index", "TorBox", "The Intern", release,
                url = "https://cdn.example/film-$index.mp4", facts = StreamFacts.parse(listOf(release)))
        }
        // This was the unconditional pre-render filter, even with every source chip cleared.
        assertTrue(OriginalAudioLanguage.sources(rows, "ko").isEmpty())
        assertTrue(OriginalAudioLanguage.sources(rows, null).isEmpty())
        for (language in listOf("ko", null)) {
            val displayed = StreamRanker.rankForSourceList(rows, PlaybackRules.DEFAULT, emptySet(), language)
            assertEquals(34, displayed.size)
            assertTrue(displayed.all { it.eligible })
            assertTrue(displayed.all { row -> row.cautions.none { it.text.contains("language", ignoreCase = true) } })
        }
        // Automatic playback still requires reported original audio.
        assertTrue(OriginalAudioLanguage.sources(rows, "ko").isEmpty())
        val korean = rows.first().copy(id = "known-korean",
            facts = StreamFacts.parse(listOf("The.Intern.2026.1080p.Korean.AAC.mp4")))
        val displayed = StreamRanker.rankForSourceList(rows + korean, PlaybackRules.DEFAULT, emptySet(), "ko")
        assertEquals(35, displayed.size)
        assertEquals(35, displayed.count { it.eligible })
    }

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

    @Test
    fun `checked cache and verified name precede a high quality direct URL`() {
        val direct = row("direct", "Movie.2024.2160p.REMUX", sizeBytes = 40_000_000_000)
            .copy(identityVerified = true)
        val checked = row("checked", "Movie.2024.1080p", cached = true, service = "TorBox")
            .copy(identityVerified = true, cachedHint = CachedHint(true, "TorBox", verified = true))
        val wrong = checked.copy(id = "wrong", identityVerified = false)
        val ordered = StreamRanker.rank(listOf(direct, wrong, checked))
        assertEquals(listOf("checked", "direct", "wrong"), ordered.map { it.row.id })
        assertFalse(ordered.last().eligible)
        assertTrue(ordered.last().cautions.any { it.text.contains("Name not verified") })
    }

    @Test
    fun `name confirmation requires exact title and corroborating year or episode`() {
        val movie = StreamExpectation("Inception", 2010, null, null, true)
        assertTrue(movie.matches(row("right", "Inception.2010.1080p.WEB-DL")))
        assertFalse(movie.matches(row("year", "Inception.2024.1080p.WEB-DL")))
        assertFalse(movie.matches(row("sequel", "Inception.Returns.2010.1080p")))
        assertFalse(movie.matches(row("unknown", "Inception.1080p")))
        assertFalse(movie.copy(year = null).matches(row("noMetadataYear", "Inception.2010.1080p")))
        assertFalse(movie.matches(row("twoYears", "Inception.2009.2010.1080p")))
        assertTrue(StreamExpectation("1917", 2019, null, null, true).matches(row("numericTitle", "1917.2019.1080p")))
        assertFalse(StreamExpectation("1917", 1917, null, null, true).matches(row("wrongNumericYear", "1917.2019.1080p")))
        val episode = StreamExpectation("Breaking Bad", 2008, 1, 2, false)
        assertTrue(episode.matches(row("ep", "Breaking.Bad.S01E02.1080p")))
        assertFalse(episode.matches(row("other", "Breaking.Bad.S01E01.1080p")))
        assertFalse(episode.matches(row("pack", "Breaking.Bad.S01E01-E12.1080p")))
        assertFalse(episode.copy(episode = 1).matches(row("firstInPack", "Breaking.Bad.S01E01-E12.1080p")))
        assertFalse(episode.copy(episode = 1).matches(row("xRange", "Breaking.Bad.1x01-03.1080p")))
        assertFalse(episode.copy(episode = 1).matches(row("plusRange", "Breaking.Bad.S01E01+E02.1080p")))
        assertFalse(movie.matches(row("seasonPack", "Inception.2010.S01.Complete.1080p")))
        assertFalse(episode.matches(row("unknown", "Breaking.Bad.1080p")))
    }

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
    fun `rule - ready only keeps direct and cached sources`() {
        val rows = listOf(
            row("cached", "Movie.1080p.WEB", cached = true),
            row("direct", "Movie.1080p.WEB"),
            row("hash", "Movie.1080p.WEB", url = null, infoHash = "a".repeat(40)),
        )
        val kept = PlaybackRules(enabled = true, readyOnly = true).eligible(rows).map(StreamRow::id)
        assertEquals(listOf("cached", "direct"), kept)
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
    fun `rule - rules off does not apply preferences to admitted releases`() {
        val rows = listOf(row("a", "Movie.2024.720p.WEB-DL"), row("b", "Movie.2024.1080p"))
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
            row("dv", "Movie.2160p.WEB.DV.dvhe.05.HEVC 📁 18 GB", cached = true)
                .copy(cachedHint = CachedHint(true, "TorBox", verified = true)),
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
        val rows = listOf(row("repack", "Movie.2024.REPACK.x264", cached = true))
        val ranked = StreamRanker.rank(rows, on.copy(excludeKeywords = listOf("repack")))
        assertTrue(ranked.single().reasons.any { it.caution && it.text == "Name holds a word you avoid: REPACK" })
    }

    // MARK: - Guarantees

    @Test
    fun `a non-empty admitted pool survives preferences that exclude every row`() {
        val rows = listOf(row("a", "Movie.480p.WEB-DL.x264"), row("b", "Movie.480p.BluRay.x264"))
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
            row("repack4k", "Movie.2160p.REPACK.HEVC 📁 20 GB", cached = true),
            row("clean720", "Movie.720p.WEB.x264 📁 2 GB", cached = true),
        )
        val ranked = StreamRanker.rank(rows, on.copy(excludeKeywords = listOf("repack")))
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
    fun `newest uses reported age rather than swarm size`() {
        val rows = listOf(
            row("old", "Movie.1080p.WEB 🌱 1000 · 1w"),
            row("new", "Movie.1080p.WEB 🌱 5 · 1h"),
            row("newest", "Movie.1080p.WEB 🌱 1 · 0.5h"),
            row("middle", "Movie.1080p.WEB 🌱 2000 · 2d"),
        )
        assertEquals(listOf("newest", "new", "middle", "old"),
            StreamRanker.rank(rows, on.copy(sort = StreamSort.NEWEST)).map { it.row.id })
    }

    @Test
    fun `newest keeps eligibility first and unknown ages last with stable ties`() {
        val base = row("base", "Movie.1080p.WEB")
        fun aged(id: String, age: String?, release: String = "Same release") = base.copy(
            id = id, releaseName = release, facts = base.facts.copy(ageText = age))
        val rows = listOf(
            aged("unknown-z", null, "A unknown"),
            aged("bad", "NaNh", "B invalid"),
            aged("equal-z", "60m"),
            aged("equal-a", "1h"),
            aged("known", "1y", "Z known"),
            row("ineligible-newest", "Movie.720p.WEB 🌱 5000 · 1m"),
        )
        val rules = on.copy(sort = StreamSort.NEWEST, minQualityRank = 3)
        val expected = listOf("equal-a", "equal-z", "known", "unknown-z", "bad", "ineligible-newest")
        assertEquals(expected, StreamRanker.rank(rows, rules).map { it.row.id })
        assertEquals(expected, StreamRanker.rank(rows.reversed(), rules).map { it.row.id })
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
