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
