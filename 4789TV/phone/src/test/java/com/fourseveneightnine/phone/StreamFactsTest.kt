package com.fourseveneightnine.phone

import com.fourseveneightnine.contract.StreamEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamFactsTest {
    private val aioStreamsDescription = listOf(
        "📅 S01 · E01 🎟️ The Continental",
        "🎥 WEB-DL 🌗 HDR 🎞️ HEVC",
        "📁 13.08 GB · 33 Mbps",
        "🔊 Atmos | DD+ | 5.1",
        "🧩 Comet 🏷️ XEBEC",
        "🌱 2,779 · 22h",
    ).joinToString("\n")

    @Test
    fun everyStatedFactIsReadOutOfTheFreeformDescription() {
        val facts = StreamFacts.from(
            StreamEntry(url = "https://a/1", name = "2160p", description = aioStreamsDescription),
        )

        assertEquals("4K", facts.quality)
        assertEquals(13.08, requireNotNull(facts.sizeGB), 0.001)
        assertEquals(33.0, requireNotNull(facts.bitrateMbps), 0.001)
        assertEquals("HEVC", facts.codec)
        assertTrue(facts.hdr)
        assertFalse(facts.dolbyVision)
        assertEquals("Atmos | DD+ | 5.1", facts.audio)
        assertEquals("Comet", facts.provider)
        assertEquals(2779, facts.seeders)
        assertEquals("22h", facts.ageText)
    }

    @Test
    fun aBitrateIsMeasuredOrItDoesNotExist() {
        val facts = StreamFacts.from(
            StreamEntry(
                url = "https://a/2",
                name = "1080p WEB-DL",
                description = "📁 8.4 GB",
            ),
        )

        assertEquals(8.4, requireNotNull(facts.sizeGB), 0.001)
        assertNull("size must never be divided by a guessed runtime", facts.bitrateMbps)
    }

    @Test
    fun anExactByteCountBeatsTheWrittenSize() {
        val facts = StreamFacts.from(
            StreamEntry(url = "https://a/3", description = "📁 2 GB", sizeBytes = 1_073_741_824L),
        )

        assertEquals(1.0, requireNotNull(facts.sizeGB), 0.001)
    }

    @Test
    fun megabyteSizesAndKilobitRatesAreConverted() {
        val facts = StreamFacts.from(
            StreamEntry(url = "https://a/4", description = "📁 512 MB · 800 kbps"),
        )

        assertEquals(0.5, requireNotNull(facts.sizeGB), 0.001)
        assertEquals(0.8, requireNotNull(facts.bitrateMbps), 0.001)
    }

    @Test
    fun aDownscaledFourKReleaseIsNotReadAsFourK() {
        val facts = StreamFacts.from(
            StreamEntry(url = "https://a/5", filename = "Movie.DS4K.1080p.WEB-DL.mkv"),
        )

        assertEquals("1080p", facts.quality)
    }

    @Test
    fun anUnstatedResolutionStaysUnknown() {
        val facts = StreamFacts.from(StreamEntry(url = "https://a/6", name = "Mirror one"))

        assertNull(facts.quality)
        assertEquals(-1, facts.qualityRank)
        assertNull(facts.sizeGB)
        assertNull(facts.codec)
        assertNull(facts.seeders)
    }

    @Test
    fun dolbyVisionDoesNotFireInsideTheWordDvdRip() {
        val plain = StreamFacts.from(StreamEntry(url = "https://a/7", filename = "Movie.DVDRip.avi"))
        val real = StreamFacts.from(StreamEntry(url = "https://a/8", filename = "Movie.2160p.DV.HDR.mkv"))

        assertFalse(plain.dolbyVision)
        assertTrue(real.dolbyVision)
    }

    @Test
    fun hdrIsReadFromItsNumberedFormsButNotFromAHdRip() {
        fun hdrOf(name: String) =
            StreamFacts.from(StreamEntry(url = "https://a/hdr", filename = name)).hdr

        // The three forms that real release names actually use.
        assertTrue(hdrOf("Movie.2160p.HDR.mkv"))
        assertTrue(hdrOf("Movie.2160p.HDR10.mkv"))
        assertTrue(hdrOf("Movie.2160p.HDR10+.mkv"))

        // "HDRip" is a rip format. It is not high dynamic range.
        assertFalse(hdrOf("Movie.1080p.HDRip.x264.mkv"))
        assertFalse(hdrOf("Movie.1080p.SDR.mkv"))
    }

    @Test
    fun audioFallsBackToTheReleaseNameWhenThereIsNoSpeakerLine() {
        val facts = StreamFacts.from(
            StreamEntry(url = "https://a/9", filename = "Movie.1080p.TrueHD.7.1.mkv"),
        )

        assertEquals("TrueHD 7.1", facts.audio)
    }
}
