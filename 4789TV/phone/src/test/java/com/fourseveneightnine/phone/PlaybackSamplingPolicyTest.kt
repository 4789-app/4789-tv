package com.fourseveneightnine.phone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSamplingPolicyTest {
    @Test
    fun samplesOnlyAnActiveUnreleasedPlayer() {
        assertTrue(PlaybackSamplingPolicy.shouldSample(isPlaying = true, isReleased = false))
        assertFalse(PlaybackSamplingPolicy.shouldSample(isPlaying = false, isReleased = false))
        assertFalse(PlaybackSamplingPolicy.shouldSample(isPlaying = true, isReleased = true))
        assertTrue(PlaybackSamplingPolicy.INTERVAL_MILLIS >= 15_000)
    }
}
