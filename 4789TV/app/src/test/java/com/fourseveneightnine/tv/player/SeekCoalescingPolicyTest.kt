package com.fourseveneightnine.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekCoalescingPolicyTest {
    @Test
    fun nonLive4KUsesABoundedSynchronizationFrameWindow() {
        val parameters = SeekCoalescingPolicy.parameters(isLive = false, width = 3_840, height = 2_160)
        assertEquals(2_000_000, parameters.toleranceBeforeUs)
        assertEquals(2_000_000, parameters.toleranceAfterUs)
    }

    @Test
    fun smallAndLiveVideoKeepExactSeeking() {
        val small = SeekCoalescingPolicy.parameters(isLive = false, width = 1_920, height = 1_080)
        val live4K = SeekCoalescingPolicy.parameters(isLive = true, width = 3_840, height = 2_160)
        assertEquals(0, small.toleranceBeforeUs)
        assertEquals(0, small.toleranceAfterUs)
        assertEquals(0, live4K.toleranceBeforeUs)
        assertEquals(0, live4K.toleranceAfterUs)
    }

    @Test
    fun aNearbyScrubTargetWaitsUntilTheActiveSeekSettles() {
        assertFalse(SeekCoalescingPolicy.canIssue(activeTargetMs = 100_000))
        assertTrue(SeekCoalescingPolicy.canIssue(activeTargetMs = null))
    }

    @Test
    fun theFirstSeekOfABurstGoesStraightThrough() {
        assertEquals(
            SeekCoalescingPolicy.Decision.IssueNow,
            SeekCoalescingPolicy.decide(lastIssuedAtMillis = null, nowMillis = 10_000),
        )
    }

    @Test
    fun aSeekInsideTheWindowIsDeferredForTheRemainderOfIt() {
        assertEquals(
            SeekCoalescingPolicy.Decision.Defer(delayMillis = 150),
            SeekCoalescingPolicy.decide(lastIssuedAtMillis = 10_000, nowMillis = 10_100),
        )
    }

    @Test
    fun aSeekAfterTheWindowStartsAFreshBurst() {
        assertEquals(
            SeekCoalescingPolicy.Decision.IssueNow,
            SeekCoalescingPolicy.decide(lastIssuedAtMillis = 10_000, nowMillis = 10_250),
        )
    }

    /** A held D-pad repeating every 60ms must produce one seek per window, not one per repeat. */
    @Test
    fun aHeldDpadCollapsesToOneSeekPerWindow() {
        var lastIssued: Long? = null
        var issued = 0
        for (tick in 0 until 20) {
            val now = tick * 60L
            when (SeekCoalescingPolicy.decide(lastIssued, now)) {
                SeekCoalescingPolicy.Decision.IssueNow -> {
                    issued++
                    lastIssued = now
                }

                is SeekCoalescingPolicy.Decision.Defer -> Unit
            }
        }
        // 20 repeats over 1.14s: one immediate plus one per 250ms window, not 20 audio rebuilds.
        assertEquals(4, issued)
    }

    @Test
    fun aBackwardsClockDoesNotStrandTheSeekForever() {
        assertEquals(
            SeekCoalescingPolicy.Decision.IssueNow,
            SeekCoalescingPolicy.decide(lastIssuedAtMillis = 10_000, nowMillis = 9_000),
        )
    }
}

class ExoStartupBufferPolicyTest {
    @Test
    fun playbackStartsOnLessRunwayThanTheLoaderKeeps() {
        // The latency knob must stay well under the resilience knob, or the "start early, keep
        // filling behind the picture" contract collapses into "start early, stall immediately".
        assert(
            ExoStartupBufferPolicy.BUFFER_FOR_PLAYBACK_MS <
                ExoStartupBufferPolicy.MIN_BUFFER_MS,
        )
        assertEquals(1_000, ExoStartupBufferPolicy.BUFFER_FOR_PLAYBACK_MS)
        assertEquals(30_000, ExoStartupBufferPolicy.MIN_BUFFER_MS)
    }

    @Test
    fun resumingAfterAStallDemandsMoreRunwayThanAColdStart() {
        // A stalled source already proved it cannot keep up; resuming on 700ms would just stall.
        assert(
            ExoStartupBufferPolicy.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS >
                ExoStartupBufferPolicy.BUFFER_FOR_PLAYBACK_MS,
        )
        assertEquals(6_000, ExoStartupBufferPolicy.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
    }

    @Test
    fun theByteBudgetScalesOnlyForDevicesWithALargeHeap() {
        assertEquals(
            96 * 1024 * 1024,
            ExoStartupBufferPolicy.targetBufferBytes(256),
        )
        assertEquals(
            128 * 1024 * 1024,
            ExoStartupBufferPolicy.targetBufferBytes(384),
        )
    }
}
