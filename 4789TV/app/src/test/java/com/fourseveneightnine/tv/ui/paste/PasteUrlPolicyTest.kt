package com.fourseveneightnine.tv.ui.paste

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PasteUrlPolicyTest {

    @Test
    fun anMp4UrlPlaysDirect() {
        val d = PasteUrlPolicy.decide("https://example.com/movie.mp4")
        assertEquals(PasteVerdict.DirectMedia, d.verdict)
        assertEquals("https://example.com/movie.mp4", d.playableUrl)
    }

    @Test
    fun anHlsManifestPlaysDirect() {
        val d = PasteUrlPolicy.decide("https://cdn.example.com/path/stream.m3u8?token=abc")
        assertEquals(PasteVerdict.DirectMedia, d.verdict)
    }

    @Test
    fun theEinthusanSignedCdnLinkPlaysDirect() {
        // Extension is followed by a query string — the plain endsWith check would miss it, which
        // is why the CDN-host branch exists.
        val d = PasteUrlPolicy.decide(
            "http://cdn2.einthusan.io/d/etv/content/D2qiP.mp4?e=1785892959&md5=D2rW5G7qCvi5qgXJb4B33w&p=priority"
        )
        assertEquals(PasteVerdict.DirectMedia, d.verdict)
        assertEquals(
            "http://cdn2.einthusan.io/d/etv/content/D2qiP.mp4?e=1785892959&md5=D2rW5G7qCvi5qgXJb4B33w&p=priority",
            d.playableUrl,
        )
    }

    @Test
    fun theEinthusanHlsManifestPlaysDirect() {
        val d = PasteUrlPolicy.decide(
            "http://cdn2.einthusan.io/h/m6zpWtKbh-S7LJyyi4HTCg/1785892959/p/etv/content/D2qiP.mp4/index.m3u8"
        )
        assertEquals(PasteVerdict.DirectMedia, d.verdict)
    }

    @Test
    fun theEinthusanWatchPageIsRefusedWithAReason() {
        val d = PasteUrlPolicy.decide("https://einthusan.tv/premium/movie/watch/2qiP/?lang=tamil")
        assertEquals(PasteVerdict.EinthusanWatchPage, d.verdict)
        assertNull(d.playableUrl)
    }

    @Test
    fun theNonPremiumWatchPageIsAlsoRefused() {
        // Anonymous / non-premium form of the same route.
        val d = PasteUrlPolicy.decide("https://einthusan.tv/movie/watch/2qiP/?lang=tamil")
        assertEquals(PasteVerdict.EinthusanWatchPage, d.verdict)
    }

    @Test
    fun aBareHostname_isUnsupported() {
        // Someone typed "einthusan.tv" without a scheme.
        val d = PasteUrlPolicy.decide("einthusan.tv")
        assertEquals(PasteVerdict.Unsupported, d.verdict)
    }

    @Test
    fun anEmptyInputIsUnsupported() {
        assertEquals(PasteVerdict.Unsupported, PasteUrlPolicy.decide("").verdict)
        assertEquals(PasteVerdict.Unsupported, PasteUrlPolicy.decide("   ").verdict)
    }

    @Test
    fun whitespaceInsideAPastedUrlIsStripped() {
        // The Fire TV keyboard sometimes inserts spaces from voice input.
        val d = PasteUrlPolicy.decide("https://example.com/mov ie.mp4")
        assertEquals(PasteVerdict.DirectMedia, d.verdict)
        assertEquals("https://example.com/movie.mp4", d.playableUrl)
    }

    @Test
    fun anEinthusanHomePageIsUnsupported() {
        // Home / browse pages are not watch pages and are not direct media.
        val d = PasteUrlPolicy.decide("https://einthusan.tv/movie/browse/?lang=tamil")
        assertEquals(PasteVerdict.Unsupported, d.verdict)
    }

    @Test
    fun aSurroundingWhitespaceIsIgnored() {
        val d = PasteUrlPolicy.decide("   https://example.com/movie.mp4   ")
        assertEquals(PasteVerdict.DirectMedia, d.verdict)
    }
}
