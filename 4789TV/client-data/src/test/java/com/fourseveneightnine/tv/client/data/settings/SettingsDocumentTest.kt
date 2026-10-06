package com.fourseveneightnine.tv.client.data.settings

import com.fourseveneightnine.tv.client.data.fixture
import com.fourseveneightnine.tv.client.data.streams.DebridService
import com.fourseveneightnine.tv.client.data.streams.StreamSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDocumentTest {

    private val document = SettingsDocument.parse(fixture("settings-export.json"))

    @Test
    fun `add-on sources keep stored order and drop the duplicate manifest`() {
        val names = document.addonSources.map(AddonSource::name)
        // Torrentio appears as a row AND as primaryManifestURLText. It is asked once, under the
        // row's name, and the legacy field is dropped rather than the other way round.
        assertEquals(
            listOf("Torrentio", "Comet", "Old thing", "Secondary add-on", "AIOStreams", "MediaFusion"),
            names,
        )
    }

    @Test
    fun `a disabled row survives parsing and is marked, not dropped`() {
        val disabled = document.addonSources.first { it.name == "Old thing" }
        assertFalse(disabled.enabled)
    }

    @Test
    fun `mediaFusionEnabled false disables MediaFusion without removing it`() {
        val off = SettingsDocument.parse(
            """{"mediaFusionURLText":"https://mf.example.com/manifest.json","mediaFusionEnabled":false}""",
        )
        assertEquals(1, off.addonSources.size)
        assertFalse(off.addonSources.single().enabled)
    }

    @Test
    fun `subtitle sources merge the list and the dedicated AIO field`() {
        assertEquals(
            listOf("OpenSubtitles v3", "AIO Subtitles"),
            document.subtitleSources.map(SubtitleSource::name),
        )
    }

    @Test
    fun `catalog order and letterboxd usernames are read and deduplicated`() {
        assertEquals(listOf("tmdb.top", "torrentio.movies", "letterboxd.watchlist"), document.catalogOrder)
        assertEquals(listOf("saranpenna", "someoneelse"), document.letterboxdUsernames)
    }

    @Test
    fun `every credential field is read`() {
        assertEquals("tb-1234567890abcdef", document.torboxAPIKey)
        assertEquals("RD1234567890ABCDEFGHIJ", document.realDebridAPIKey)
        assertEquals("0123456789abcdef0123456789abcdef", document.tmdbAPIKey)
        assertEquals("mdb-abcdefgh12345678", document.mdbListAPIKey)
        assertEquals("cat-9876543210zyxwvu", document.catalogServerToken)
    }

    @Test
    fun `uncached limits are read`() {
        assertEquals(3, document.uncachedDailyMax)
        assertEquals(1, document.uncachedSlotOverride)
    }

    @Test
    fun `playback rules come across whole`() {
        val rules = document.playbackRules
        assertTrue(rules.enabled)
        assertEquals(3, rules.minQualityRank)
        assertEquals(8.0, requireNotNull(rules.maxSizeGB), 0.001)
        assertEquals(5, rules.minSeeders)
        assertEquals(listOf("cam", "ts", "telesync"), rules.normalizedKeywords)
        assertEquals(listOf("English"), rules.preferredAudioLanguages)
        assertEquals(StreamSort.BEST, rules.sort)
        assertEquals(listOf(DebridService.REAL_DEBRID, DebridService.TORBOX), rules.debridPriority)
        assertTrue(rules.autoPlay)
        assertEquals(3, rules.autoPlayMinSources)
    }

    @Test
    fun `an export without playback rules gets the stock defaults`() {
        val bare = SettingsDocument.parse("""{"format":"4789-settings","version":1}""")
        assertFalse(bare.playbackRules.enabled)
        assertFalse(bare.redacted().hasPlaybackRules)
    }

    @Test
    fun `a blank or oversized credential is not a credential`() {
        val broken = SettingsDocument.parse(
            """{"torboxAPIKey":"  ","realDebridAPIKey":"a b c","mdbListAPIKey":"ok-12345678"}""",
        )
        assertNull(broken.torboxAPIKey)
        assertNull(broken.realDebridAPIKey)
        assertEquals("ok-12345678", broken.mdbListAPIKey)
    }

    @Test
    fun `garbage parses to an empty document instead of throwing`() {
        val junk = SettingsDocument.parse("not json at all {{{")
        assertTrue(junk.addonSources.isEmpty())
        assertEquals(0, junk.redacted().totalFields)
    }

    @Test
    fun `redacted reports counts only`() {
        val receipt = document.redacted()
        assertEquals(6, receipt.addonSources)
        assertEquals(2, receipt.subtitleSources)
        assertEquals(5, receipt.credentials)
        assertEquals(3, receipt.catalogOrder)
        assertEquals(2, receipt.letterboxdUsernames)
        assertTrue(receipt.hasPlaybackRules)
    }

    @Test
    fun `toString never prints a value`() {
        val printed = document.toString()
        listOf(
            "tb-1234567890abcdef",
            "RD1234567890ABCDEFGHIJ",
            "0123456789abcdef0123456789abcdef",
            "mdb-abcdefgh12345678",
            "cat-9876543210zyxwvu",
            "AAAABBBBCCCC",
            "saranpenna",
        ).forEach { secret ->
            assertFalse("toString leaked $secret", printed.contains(secret))
        }
    }
}
