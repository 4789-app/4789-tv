package com.fourseveneightnine.tv.client.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec §12.7.4 and §0.9.6: Backspace repeats when held, and never faster than the 60 ms gate. */
class TvKeyboardTest {

    @Test
    fun `accessibility labels keep full untruncated media text`() {
        assertEquals(
            "A Very Long Film Title That Must Not Be Clipped, Season 2 episode 11, 73 percent watched",
            accessibilityLabel(
                "A Very Long Film Title That Must Not Be Clipped",
                "Season 2 episode 11",
                "73 percent watched",
            ),
        )
    }

    @Test
    fun `accessibility labels omit blank and duplicate state`() {
        assertEquals("Title, 2026", accessibilityLabel(" Title ", null, "", "2026", "Title"))
    }

    @Test
    fun `the first press always fires`() {
        assertTrue(KeyRepeat.shouldFire(repeatCount = 0, nowMillis = 1_000L, lastFiredMillis = 999L))
    }

    @Test
    fun `a repeat inside the gate is dropped`() {
        assertFalse(KeyRepeat.shouldFire(repeatCount = 1, nowMillis = 1_030L, lastFiredMillis = 1_000L))
    }

    @Test
    fun `a repeat on the gate fires`() {
        assertTrue(KeyRepeat.shouldFire(repeatCount = 1, nowMillis = 1_060L, lastFiredMillis = 1_000L))
    }

    @Test
    fun `a held key keeps deleting`() {
        // Android sends the first press, then repeats about every 50 ms.
        val fired = firedTimes(repeatPeriod = 50L, holdMillis = 1_000L)
        assertTrue("fired ${fired.size} times: $fired", fired.size >= 6)
    }

    @Test
    fun `two strokes are never closer than the gate`() {
        val fired = firedTimes(repeatPeriod = 10L, holdMillis = 1_000L)
        val gaps = fired.zipWithNext { a, b -> b - a }
        assertTrue("gaps $gaps", gaps.all { it >= KeyRepeat.GATE_MILLIS })
    }

    @Test
    fun `a single tap is one stroke`() {
        assertEquals(listOf(0L), firedTimes(repeatPeriod = 50L, holdMillis = 0L))
    }

    /** Every moment a held key would fire, given a platform repeat period. */
    private fun firedTimes(repeatPeriod: Long, holdMillis: Long): List<Long> {
        val fired = mutableListOf<Long>()
        var last = -KeyRepeat.GATE_MILLIS
        var now = 0L
        var repeat = 0
        while (now <= holdMillis) {
            if (KeyRepeat.shouldFire(repeat, now, last)) {
                fired += now
                last = now
            }
            // The platform waits ~400 ms before the first repeat.
            now += if (repeat == 0) 400L else repeatPeriod
            repeat++
        }
        return fired
    }
}
