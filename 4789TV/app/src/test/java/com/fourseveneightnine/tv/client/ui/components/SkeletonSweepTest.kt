package com.fourseveneightnine.tv.client.ui.components

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The shimmer clock and its slot count, audit F07.
 *
 * Both are plain state on the main thread, so they test without Compose. What they buy is that a
 * cold Home draws at most [SkeletonSweep.MAX_SWEEPING_BLOCKS] sweeping blocks instead of seventeen,
 * and that every block reads one phase rather than owning an infinite transition of its own.
 */
class SkeletonSweepTest {

    @Before
    fun setUp() = SkeletonSweep.resetForTest()

    @After
    fun tearDown() = SkeletonSweep.resetForTest()

    @Test
    fun `phase starts at minus 30 percent and ends at 130`() {
        assertEquals(-0.3f, SkeletonSweep.phaseAt(0L, periodMillis = 1600), 0.0001f)
        assertEquals(0.5f, SkeletonSweep.phaseAt(800L, periodMillis = 1600), 0.0001f)
        // The last sample before the wrap is just short of 1.3, never past it.
        assertTrue(SkeletonSweep.phaseAt(1599L, periodMillis = 1600) < 1.3f)
        assertTrue(SkeletonSweep.phaseAt(1599L, periodMillis = 1600) > 1.29f)
    }

    @Test
    fun `phase repeats every period`() {
        assertEquals(
            SkeletonSweep.phaseAt(400L, periodMillis = 1600),
            SkeletonSweep.phaseAt(400L + 1600L * 7, periodMillis = 1600),
            0.0001f,
        )
    }

    @Test
    fun `phase never runs backwards inside one period`() {
        var previous = SkeletonSweep.phaseAt(0L, periodMillis = 1600)
        for (millis in 1L..1599L) {
            val next = SkeletonSweep.phaseAt(millis, periodMillis = 1600)
            assertTrue("phase fell at ${millis}ms", next >= previous)
            previous = next
        }
    }

    @Test
    fun `a period of zero cannot divide by zero`() {
        assertEquals(-0.3f, SkeletonSweep.phaseAt(123L, periodMillis = 0), 0.0001f)
    }

    @Test
    fun `at most six blocks sweep at once`() {
        val granted = (1..20).count { SkeletonSweep.acquire() }
        assertEquals(SkeletonSweep.MAX_SWEEPING_BLOCKS, granted)
    }

    @Test
    fun `a released slot is handed to the next block`() {
        repeat(SkeletonSweep.MAX_SWEEPING_BLOCKS) { assertTrue(SkeletonSweep.acquire()) }
        assertFalse(SkeletonSweep.acquire())
        SkeletonSweep.release()
        assertTrue(SkeletonSweep.acquire())
        assertFalse(SkeletonSweep.acquire())
    }

    @Test
    fun `releasing more than was taken cannot go negative`() {
        repeat(5) { SkeletonSweep.release() }
        val granted = (1..20).count { SkeletonSweep.acquire() }
        assertEquals(SkeletonSweep.MAX_SWEEPING_BLOCKS, granted)
    }

    @Test
    fun `tick converts frame nanos to a phase`() {
        SkeletonSweep.tick(800L * 1_000_000L)
        assertEquals(0.5f, SkeletonSweep.phase.floatValue, 0.0001f)
    }
}
