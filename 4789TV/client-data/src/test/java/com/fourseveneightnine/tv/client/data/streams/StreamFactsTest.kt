package com.fourseveneightnine.tv.client.data.streams

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser table.
 *
 * Every row here is a shape a real add-on actually sends. The table is the regression test: when a
 * title stops parsing, this is where it is written down, not in a device log.
 */
class StreamFactsTest {

    @Test
    fun `a verified digital year does not discard the original movie year or allow other years`() {
        val expectation = StreamExpectation("Saayavanam", 2025, null, null, true, verifiedReleaseYear = 2026)
        fun row(year: Int): StreamRow {
            val name = "Saayavanam.$year.1080p.WEB-DL.mkv"
            return StreamRow("file", "TorBox", name, name, filename = name)
        }
        assertTrue(expectation.matches(row(2025)))
        assertTrue(expectation.matches(row(2026)))
        assertFalse(expectation.matches(row(2024)))
        assertFalse(expectation.contradictsFilename(row(2026).filename.orEmpty()))
    }

    @Test
    fun `published dates support age ranking without inventing a file date from a release year`() {
        val published = java.time.Instant.parse("2025-10-27T01:33:51Z").toEpochMilli()
        assertEquals(published, StreamFacts.sourceDate("2025-10-27T01:33:51Z"))
        assertEquals(published, StreamFacts.sourceDate((published / 1000).toString()))
        assertEquals(published, StreamFacts.sourceDate(published.toString()))
        assertNull(StreamFacts.sourceDate("2025"))
        assertNull(StreamFacts.sourceDate("2025-99-99"))
        assertEquals(60.0, requireNotNull(StreamFacts(sourceDates = mapOf("Published" to published))
            .ageMinutesAt(published + 3_600_000L)), 0.001)
        assertTrue(facts("Movie.2025.1080p.mkv").sourceDates.isEmpty())
    }

    @Test
    fun `language-only audio lanes do not hide filename codecs`() {
        val f = facts("Show.S01E02.HEVC.TrueHD.Atmos.7.1.mkv\n🎧 Audio: English, Hindi\nSubtitles: French")
        assertEquals("Atmos", f.audio)
        assertEquals(listOf("TrueHD"), f.audioCodecs)
        assertTrue(f.reportedAudioLanguages.containsAll(listOf("English", "Hindi")))
        assertFalse(f.reportedAudioLanguages.contains("French"))
        assertEquals(listOf("French"), f.subtitleLanguages)
    }
    @Test
    fun `theater label families are rejected without blocking clean names or TS containers`() {
        val labels = listOf("CAM", "CAMRip", "HDCAM", "HDCAMRip", "HQCAM", "NEWCAM", "TS", "HDTS",
            "HQTS", "TSRip", "HDTSRip", "TELESYNC", "TELESYNCH", "TC", "HDTC", "TELECINE",
            "PreDVD", "PreDVDRip", "PDVD", "PDVDRip", "SCR", "SCREENER", "DVDSCR", "BDSCR",
            "HDSCR", "WEBSCR", "WP", "WORKPRINT", "THEATERPRINT", "THEATREPRINT")
        for (label in labels) for (separator in listOf("", "-", ".", "_", " ")) {
            val variant = label.replace("HD", "HD$separator").replace("DVD", "${separator}DVD$separator")
            for (text in listOf(variant, variant.lowercase())) {
                assertTrue(text, StreamFacts.isTheatricalRelease("Movie.2026.1080p.$text.x264.mkv"))
            }
        }
        for (name in listOf("Cam.2018.1080p.WEB-DL", "Ghosts.S01E01.1080p.HDTV", "Movie.2026.WEB-DL.ts",
            "Movie.2026.1080p.DTS-HD", "Movie.2026.2160p.BluRay.REMUX", "Movie.2026.1080p.HDRip",
            "Movie.2026.1080p.HQ.Clean.Audio", "Movie.2026.1080p.HC.WEBRip")) {
            assertFalse(name, StreamFacts.isTheatricalRelease(name))
        }
    }

    @Test
    fun `provider identity alone is not cache proof and explicit cold variants win`() {
        assertFalse(facts("[TB] Movie 1080p").cachedHint.cached)
        assertEquals("TorBox", facts("[TB] Movie 1080p").cachedHint.service)
        assertFalse(facts("[RD] Movie 1080p").cachedHint.cached)
        for (cold in listOf("uncached", "un_cached", "not-cached", "not cached", "download_required")) {
            assertFalse(cold, facts("[TB+] cached $cold Movie 1080p").cachedHint.cached)
        }
        assertTrue(facts("[TB+] Movie 1080p").cachedHint.cached)
        assertEquals(CachedHint(true, "TorBox"), facts("[TorBox+] Movie 1080p").cachedHint)
        assertEquals(CachedHint(true, "TorBox"), facts("TorBox cached Movie 1080p").cachedHint)
        assertEquals(CachedHint(false, "Real-Debrid"), facts("Real-Debrid Movie 1080p").cachedHint)
    }


    private fun facts(title: String) = StreamFacts.parse(listOf(title))

    // MARK: - Resolution, one row per shape

    @Test
    fun `resolution table`() {
        val cases = listOf(
            "The.Bear.S02E04.2160p.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265-FLUX" to "4K",
            "Dune.Part.Two.2024.UHD.BluRay.REMUX.HDR.HEVC.TrueHD.7.1-FraMeSToR" to "4K",
            "Oppenheimer.2023.4K.WEB-DL.H265" to "4K",
            "Some.Show.S01E01.1440p.WEBRip.x265" to "1440p",
            "Interstellar.2014.1080p.BluRay.x264-SPARKS" to "1080p",
            "Movie.2019.FHD.WEB-DL.AAC" to "1080p",
            "Old.Show.S03E09.720p.HDTV.x264-LOL" to "720p",
            "Tiny.Movie.2001.480p.DVDRip.XviD" to "480p",
            "Classic.1962.576p.PAL.DVD" to "480p",
            "Something.2020.SD.WEBRip" to "SD",
            "Movie.Title.2021.WEB-DL.NoResolutionStated" to null,
            // DS4K is downscaled FROM 4K. The real resolution in the same name has to win.
            "Movie.2022.DS4K.1080p.WEBRip.HEVC" to "1080p",
            "Movie.2022.Downscaled.2160p.to.1080p.WEB" to "1080p",
            "Movie.2026.UHD.WEBRip.1080p.AV1" to "1080p",
        )
        cases.forEach { (title, expected) ->
            assertEquals(title, expected, facts(title).quality)
        }
    }

    // MARK: - HDR and Dolby Vision

    @Test
    fun `hdr table`() {
        assertEquals(HdrFormat.DOLBY_VISION, facts("Movie.2160p.DV.HDR10.HEVC").hdr)
        assertEquals(HdrFormat.DOLBY_VISION, facts("Movie 2160p DoVi Profile 8 HEVC").hdr)
        assertEquals(HdrFormat.DOLBY_VISION, facts("Movie.2160p.dvhe.05.HEVC").hdr)
        assertEquals(HdrFormat.HDR10, facts("Movie.2160p.HDR10+.HEVC").hdr)
        assertEquals(HdrFormat.HDR10, facts("Movie.2160p.HDR10Plus.HEVC").hdr)
        assertEquals(HdrFormat.HDR10, facts("Movie.2160p.HLG.HEVC").hdr)
        assertEquals(HdrFormat.NONE, facts("Movie.1080p.HDRip.x264").hdr)
        assertEquals(HdrFormat.NONE, facts("Movie.1080p.WEB-DL.x264").hdr)
    }

    @Test
    fun `dolby vision profile is read only when the release states one`() {
        assertEquals(5, facts("Movie.2160p.dvhe.05.HEVC").dolbyVisionProfile)
        assertEquals(8, facts("Movie 2160p DV P8 HEVC").dolbyVisionProfile)
        assertEquals(8, facts("Movie 2160p DoVi Profile 8 HEVC").dolbyVisionProfile)
        assertNull(facts("Movie.2160p.DV.HEVC").dolbyVisionProfile)
    }

    // MARK: - Codec

    @Test
    fun `codec table`() {
        assertTrue(facts("Movie.2160p.HEVC").isHEVC)
        assertTrue(facts("Movie.2160p.x265").isHEVC)
        assertTrue(facts("Movie.2160p.H.265").isHEVC)
        assertEquals(listOf("AVC"), facts("Movie.1080p.x264-GRP").codecs)
        assertEquals(listOf("AV1"), facts("Movie.1080p.AV1.WEB").codecs)
        assertEquals(listOf("VP9"), facts("Movie.1080p.VP9.WEBM").codecs)
        assertTrue(facts("Movie.1080p.NoCodecStated.WEB").codecs.isEmpty())
    }

    // MARK: - Size and bitrate

    @Test
    fun `size table`() {
        assertEquals(4.21, requireNotNull(facts("Movie 1080p 📁 4.21 GB").sizeGB), 0.01)
        assertEquals(0.928, requireNotNull(facts("Movie 720p 950 MB").sizeGB), 0.01)
        assertEquals(18.42, requireNotNull(facts("Movie 2160p 18,42 GB").sizeGB), 0.01)
        assertNull(facts("Movie 1080p no size at all").sizeGB)
    }

    @Test
    fun `a stated byte count beats the text`() {
        val parsed = StreamFacts.parse(listOf("Movie 1080p 📁 4.21 GB"), sizeBytes = 1_073_741_824L)
        assertEquals(1.0, requireNotNull(parsed.sizeGB), 0.001)
    }

    @Test
    fun `a bitrate is a stated number or it does not exist`() {
        assertEquals(33.0, requireNotNull(facts("Movie 1080p 📁 13.08 GB · 33 Mbps").bitrateMbps), 0.01)
        assertEquals(0.8, requireNotNull(facts("Movie 480p 800 kbps").bitrateMbps), 0.01)
        assertEquals(0.5, requireNotNull(facts("Movie 480p <1 Mbps").bitrateMbps), 0.01)
        // Size alone is never divided by a guessed runtime to invent one.
        assertNull(facts("Movie 1080p 📁 13.08 GB").bitrateMbps)
    }

    // MARK: - Audio

    @Test
    fun `audio table`() {
        assertEquals("Atmos", facts("Movie.2160p.TrueHD.7.1.Atmos").audio)
        assertEquals("TrueHD", facts("Movie.2160p.TrueHD.7.1").audio)
        assertEquals("DTS-HD", facts("Movie.1080p.DTS-HD.MA.5.1").audio)
        assertEquals("DD+", facts("Movie.1080p.DDP5.1").audio)
        assertEquals("DD", facts("Movie.1080p.AC3.2.0").audio)
        assertEquals("AAC", facts("Movie.720p.AAC.2.0").audio)
        assertEquals("7.1", facts("Movie.2160p.TrueHD.7.1.Atmos").audioChannels)
        assertEquals("5.1", facts("Movie.1080p.DDP5.1").audioChannels)
        assertEquals("2.0", facts("Movie.720p.AAC.2.0").audioChannels)
        assertEquals("Atmos 7.1", facts("Movie.2160p.TrueHD.7.1.Atmos").audioLine)
    }

    @Test
    fun `object audio is recognised for the atmos rule`() {
        assertTrue(facts("Movie.2160p.TrueHD.7.1.Atmos").isObjectAudio)
        assertEquals("DTS-X", facts("Movie.2160p.DTS-X.7.1").audio)
        assertTrue(facts("Movie.2160p.DTS-X.7.1").isObjectAudio)
        assertFalse(facts("Movie.1080p.DDP5.1").isObjectAudio)
    }

    @Test
    fun `audio languages come from words and from flags`() {
        assertEquals(listOf("English"), facts("Movie 1080p 🎧 English DDP5.1").audioLanguages)
        // The order is the parser's fixed table order, not the order the title happened to use,
        // so the same two languages always render as the same two chips.
        assertEquals(
            listOf("English", "Tamil"),
            facts("Movie 720p Tamil + English x264 AAC").audioLanguages,
        )
        assertTrue(facts("Movie 1080p 🇬🇧 WEB-DL").audioLanguages.contains("English"))
        assertEquals(listOf("Dual audio"), facts("Movie 1080p Dual Audio WEB-DL").audioLanguages)
        assertEquals(listOf("Multi"), facts("Movie 1080p MULTi WEB-DL").audioLanguages)
        assertTrue(facts("Movie 1080p WEB-DL").audioLanguages.isEmpty())
    }

    // MARK: - Cached hints

    @Test
    fun `cached hint table`() {
        assertEquals(CachedHint(true, "Real-Debrid"), facts("⚡ [RD+] Movie 1080p").cachedHint)
        assertEquals(CachedHint(true, "TorBox"), facts("[TB+] Movie 1080p").cachedHint)
        assertEquals(CachedHint(true, "AllDebrid"), facts("[AD+] Movie 1080p").cachedHint)
        assertTrue(facts("⚡ Movie 1080p Instant").cachedHint.cached)
        assertFalse(facts("⏳ Movie 1080p Download required").cachedHint.cached)
        assertFalse(facts("Movie 1080p 👤 41").cachedHint.cached)
    }

    // MARK: - Swarm and extras

    @Test
    fun `seeders and provider and group`() {
        assertEquals(41, facts("Movie 1080p 👤 41 💾 2.1 GB").seeders)
        assertEquals(220, facts("Movie 1080p 🌱 220").seeders)
        assertEquals(12, facts("Movie 1080p Seeders: 12").seeders)
        assertNull(facts("Movie 1080p no swarm stated").seeders)
        assertEquals("Comet", facts("Movie 1080p 🧩 Comet 🏷️ FLUX").provider)
        assertEquals("FLUX", facts("Movie 1080p 🧩 Comet 🏷️ FLUX").releaseGroup)
        assertEquals("FraMeSToR", facts("Movie.2160p.REMUX-FraMeSToR").releaseGroup)
    }

    @Test
    fun `reported ages normalize relative units without inventing missing ages`() {
        val cases = listOf("1m" to 1.0, "1.5h" to 90.0, "2d" to 2_880.0,
            "1w" to 10_080.0, "1mo" to 43_200.0, "1y" to 525_600.0, "1\t h" to 60.0)
        cases.forEach { (age, expected) ->
            assertEquals(age, expected, requireNotNull(facts("🌱 5 · $age").ageMinutes), 0.001)
        }
        assertEquals(0.0, requireNotNull(StreamFacts(ageText = " 0h ").ageMinutes), 0.001)
        listOf(null, "", "unknown", "1H", "-1h", "1e3h", "1h ago", "NaNh", "9".repeat(400) + "y").forEach {
            assertNull(it, StreamFacts(ageText = it).ageMinutes)
        }
        assertNull(facts("Movie 1080p, no age stated").ageMinutes)
        assertNull(facts("Movie 1080p, bare 1h is not an age marker").ageMinutes)
    }

    @Test
    fun `an explicit resolution reads into the facts pane`() {
        assertEquals("1920x1080", facts("Movie 1920x1080 WEB-DL").resolutionText)
        assertNull(facts("Movie 1080p WEB-DL").resolutionText)
    }

    @Test
    fun `quality ranks order the way the ranker expects`() {
        assertTrue(StreamFacts.qualityRank("4K") > StreamFacts.qualityRank("1080p"))
        assertTrue(StreamFacts.qualityRank("1080p") > StreamFacts.qualityRank("720p"))
        assertEquals(-1, StreamFacts.qualityRank(null))
        assertEquals("1080p", StreamFacts.qualityLabel(3))
    }
}


class OriginalAudioLanguageTest {
    private fun row(text: String): StreamRow = StreamFacts.parse(listOf(text)).let { facts ->
        StreamRow("test", "Any add-on", "Source", text, url = "https://example.test/video",
            audioLanguages = facts.audioLanguages, facts = facts)
    }

    @Test fun `original languages normalize full names codes and aliases`() {
        mapOf("Telugu" to "te", "TEL" to "te", "te-IN" to "te", "eng" to "en",
            "fre" to "fr", "ger" to "de", "Cantonese" to "cn", "yue" to "cn",
            "Indonesian" to "id", "Hebrew" to "he", "iw" to "he").forEach { (raw, expected) ->
            assertEquals(raw, expected, OriginalAudioLanguage.normalize(raw))
        }
        listOf(null, "", "und", "unknown", "root", "none", "null", "n/a", "mul", "mis", "zxx", "xx", "Multi", "Dual audio").forEach {
            assertNull(OriginalAudioLanguage.normalize(it))
        }
    }

    @Test fun `explicit audio names and filename aliases admit original audio`() {
        listOf("Film.2026.1080p.WEB-DL.TEL.AAC", "Film 1080p [Tel + Tam + Hin]",
            "Audio: Telugu + English", "🎧 te-IN", "[TEL+ENG]", "Telugu + English").forEach {
            assertTrue(it, OriginalAudioLanguage.matches(row(it), "Telugu"))
        }
        assertTrue(OriginalAudioLanguage.matches(row("🔊 Korean | Subtitles: English"), "ko"))
        assertTrue(OriginalAudioLanguage.matches(row("Film.1080p.yue"), "cn"))
        assertFalse(OriginalAudioLanguage.matches(row("Film.1080p.Mandarin"), "cn"))
    }

    @Test fun `subtitles title words countries and generic packs do not prove original audio`() {
        listOf("The English Patient.1996.1080p.German", "Film 1080p 🇬🇧 WEB-DL",
            "Film 1080p MULTi", "Film 1080p Dual Audio", "Film 1080p WEB-DL",
            "Film 1080p English Subs", "Film 1080p [ENG] Subtitles", "Subtitles: English",
            "Audio: Tamil | Subtitles: English", "Film 1080p ENG.SDH", "Audio: no English").forEach {
            assertFalse(it, OriginalAudioLanguage.matches(row(it), "en"))
        }
        assertFalse(OriginalAudioLanguage.matches(row("1TamilMV Film 2026 1080p TEL"), "ta"))
        assertFalse(OriginalAudioLanguage.matches(row("Mr.Robot.2015.1080p.ENG"), "mr"))
        assertFalse(OriginalAudioLanguage.matches(row("Film 1080p 🇮🇳"), "hi"))
    }

    @Test fun `DV and SD video tags are not Divehi or Sindhi audio`() {
        val video = row("Film.2026.2160p.DV.HDR10.HEVC.TEL.ENG.DDP5.1.Atmos")
        assertFalse(OriginalAudioLanguage.matches(video, "dv"))
        assertFalse(video.facts.reportedAudioLanguages.contains("Divehi"))
        assertFalse(video.facts.reportedAudioLanguages.any(String::isBlank))
        assertFalse(OriginalAudioLanguage.matches(row("Film.2026.SD.ENG.AVC"), "sd"))
        assertTrue(OriginalAudioLanguage.matches(row("Audio: dv + eng"), "dv"))
        assertTrue(OriginalAudioLanguage.matches(row("Film.2026.1080p.Divehi"), "dv"))
    }

    @Test fun `an empty original audio pool stays empty even when other sources exist`() {
        val sources = listOf(row("Film.1080p.ENG"), row("Film.2160p.MULTi"))
        assertTrue(OriginalAudioLanguage.sources(sources, "ko").isEmpty())
        assertTrue(OriginalAudioLanguage.sources(sources, null).isEmpty())
        assertEquals(1, OriginalAudioLanguage.sources(sources, "en").size)
    }
}
