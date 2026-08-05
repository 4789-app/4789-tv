package com.fourseveneightnine.tv.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The handoff must RESUME the viewer, not restart them: position, sideloaded subtitle, and the
 * stream's headers all have to survive the jump into the external player.
 */
class ExternalPlayerHandoffTest {
    @Test
    fun positionSubtitleAndHeadersTravelWithTheHandoff() {
        val extras = ExternalPlayerIntentPolicy.handoffExtras(
            ExternalPlayerIntentPolicy.HandoffContext(
                positionMillis = 812_200,
                subtitleURL = "http://127.0.0.1:8791/subs/en.srt",
                subtitleName = "English",
                headers = linkedMapOf("Authorization" to "Bearer x"),
            ),
        )

        assertEquals(812_200, extras.positionMillis)
        assertEquals("http://127.0.0.1:8791/subs/en.srt", extras.subtitleURL)
        assertEquals("English", extras.subtitleName)
        assertArrayEquals(arrayOf("Authorization", "Bearer x"), extras.headerPairs)
    }

    @Test
    fun anEmptyContextAddsNothing() {
        val extras = ExternalPlayerIntentPolicy.handoffExtras(
            ExternalPlayerIntentPolicy.HandoffContext(),
        )

        assertNull(extras.positionMillis)
        assertNull(extras.subtitleURL)
        assertNull(extras.headerPairs)
    }

    @Test
    fun startOfFilePositionIsOmitted() {
        val extras = ExternalPlayerIntentPolicy.handoffExtras(
            ExternalPlayerIntentPolicy.HandoffContext(positionMillis = 0),
        )

        assertNull(extras.positionMillis)
    }

    @Test
    fun eachPlayerGetsTheDialectItActuallyReads() {
        // Sending MX extras to VLC is not harmless neglect — VLC reads different names, so the
        // viewer silently restarts at 0:00 with no subtitle.
        assertEquals(
            ExternalPlayerIntentPolicy.HandoffDialect.MxStyle,
            ExternalPlayerIntentPolicy.dialect(ExternalPlayerIntentPolicy.PACKAGE_JUST_PLAYER),
        )
        assertEquals(
            ExternalPlayerIntentPolicy.HandoffDialect.MxStyle,
            ExternalPlayerIntentPolicy.dialect(ExternalPlayerIntentPolicy.PACKAGE_NEXT_PLAYER),
        )
        assertEquals(
            ExternalPlayerIntentPolicy.HandoffDialect.Vlc,
            ExternalPlayerIntentPolicy.dialect(ExternalPlayerIntentPolicy.PACKAGE_VLC),
        )
        assertEquals(
            ExternalPlayerIntentPolicy.HandoffDialect.UrlOnly,
            ExternalPlayerIntentPolicy.dialect(ExternalPlayerIntentPolicy.PACKAGE_KODI),
        )
        // The system chooser gets the MX set: it is the most widely understood vocabulary.
        assertEquals(
            ExternalPlayerIntentPolicy.HandoffDialect.MxStyle,
            ExternalPlayerIntentPolicy.dialect(null),
        )
    }

    @Test
    fun blankSubtitleFieldsFallBackSafely() {
        val extras = ExternalPlayerIntentPolicy.handoffExtras(
            ExternalPlayerIntentPolicy.HandoffContext(
                subtitleURL = "http://127.0.0.1:8791/subs/x.srt",
                subtitleName = "  ",
            ),
        )

        assertEquals("Subtitle", extras.subtitleName)
    }
}
