package com.fourseveneightnine.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AudioClockWatchdogPolicyTest {

    private lateinit var policy: AudioClockWatchdogPolicy

    @Before
    fun setUp() {
        policy = AudioClockWatchdogPolicy()
    }

    @Test
    fun `normal 1x playback does not trigger watchdog`() {
        val action = policy.evaluateSample(
            deltaPosMs = 500,
            deltaWallMs = 500,
            playWhenReady = true,
            playbackSpeed = 1.0f,
        )
        assertEquals(AudioClockRaceAction.NONE, action)
        assertEquals(0, policy.consecutiveHits)
        assertEquals(0, policy.recoveryAttempts)
    }

    @Test
    fun `clock race triggers REOPEN on first 3 hits`() {
        // Sample 1: 6.9x speed
        var action = policy.evaluateSample(deltaPosMs = 3450, deltaWallMs = 500, playWhenReady = true, playbackSpeed = 1.0f)
        assertEquals(AudioClockRaceAction.NONE, action)
        assertEquals(1, policy.consecutiveHits)

        // Sample 2: 6.9x speed
        action = policy.evaluateSample(deltaPosMs = 3450, deltaWallMs = 500, playWhenReady = true, playbackSpeed = 1.0f)
        assertEquals(AudioClockRaceAction.NONE, action)
        assertEquals(2, policy.consecutiveHits)

        // Sample 3: 6.9x speed -> REOPEN
        action = policy.evaluateSample(deltaPosMs = 3450, deltaWallMs = 500, playWhenReady = true, playbackSpeed = 1.0f)
        assertEquals(AudioClockRaceAction.REOPEN, action)
        assertEquals(1, policy.recoveryAttempts)
        assertFalse(policy.forcedPcmDemote)
    }

    @Test
    fun `recurrent clock race triggers DEMOTE_TO_PCM on second escalation`() {
        // First 3 hits -> REOPEN
        repeat(3) {
            policy.evaluateSample(deltaPosMs = 3500, deltaWallMs = 500, playWhenReady = true, playbackSpeed = 1.0f)
        }
        assertEquals(1, policy.recoveryAttempts)

        // Next 3 hits -> DEMOTE_TO_PCM
        var action = AudioClockRaceAction.NONE
        repeat(3) {
            action = policy.evaluateSample(deltaPosMs = 3500, deltaWallMs = 500, playWhenReady = true, playbackSpeed = 1.0f)
        }
        assertEquals(AudioClockRaceAction.DEMOTE_TO_PCM, action)
        assertEquals(2, policy.recoveryAttempts)
        assertTrue(policy.forcedPcmDemote)
    }

    @Test
    fun `persisting clock race beyond attempt 2 triggers SURFACE_FAILURE`() {
        // 1st escalation: REOPEN
        repeat(3) { policy.evaluateSample(3500, 500, true, 1.0f) }
        // 2nd escalation: DEMOTE_TO_PCM
        repeat(3) { policy.evaluateSample(3500, 500, true, 1.0f) }

        // 3rd escalation: SURFACE_FAILURE
        var action = AudioClockRaceAction.NONE
        repeat(3) {
            action = policy.evaluateSample(3500, 500, true, 1.0f)
        }
        assertEquals(AudioClockRaceAction.SURFACE_FAILURE, action)
        assertEquals(3, policy.recoveryAttempts)
    }

    @Test
    fun `paused playback or fast-forward speed resets consecutive hits`() {
        policy.evaluateSample(3500, 500, true, 1.0f)
        assertEquals(1, policy.consecutiveHits)

        // Paused
        val action = policy.evaluateSample(3500, 500, false, 1.0f)
        assertEquals(AudioClockRaceAction.NONE, action)
        assertEquals(0, policy.consecutiveHits)

        // Fast forward 2x speed
        policy.evaluateSample(3500, 500, true, 1.0f)
        assertEquals(1, policy.consecutiveHits)
        policy.evaluateSample(3500, 500, true, 2.0f)
        assertEquals(0, policy.consecutiveHits)
    }

    @Test
    fun `seek reversal and delayed scheduler sample cannot false trigger`() {
        policy.evaluateSample(3500, 500, true, 1.0f)
        assertEquals(1, policy.consecutiveHits)

        // A backwards seek is not a racing playback clock.
        assertEquals(AudioClockRaceAction.NONE, policy.evaluateSample(-120_000, 500, true, 1.0f))
        assertEquals(0, policy.consecutiveHits)

        // A long app/OS scheduling gap is outside the detector's observation contract.
        policy.evaluateSample(3500, 500, true, 1.0f)
        assertEquals(AudioClockRaceAction.NONE, policy.evaluateSample(30_000, 3_000, true, 1.0f))
        assertEquals(0, policy.consecutiveHits)
    }

    @Test
    fun `one normal sample breaks an out of order race sequence`() {
        repeat(2) { policy.evaluateSample(3500, 500, true, 1.0f) }
        assertEquals(2, policy.consecutiveHits)
        assertEquals(AudioClockRaceAction.NONE, policy.evaluateSample(500, 500, true, 1.0f))
        assertEquals(0, policy.consecutiveHits)
        assertEquals(AudioClockRaceAction.NONE, policy.evaluateSample(3500, 500, true, 1.0f))
        assertEquals(0, policy.recoveryAttempts)
    }

    @Test
    fun `resetSession clears all recovery state`() {
        repeat(3) { policy.evaluateSample(3500, 500, true, 1.0f) }
        assertEquals(1, policy.recoveryAttempts)

        policy.resetSession()
        assertEquals(0, policy.recoveryAttempts)
        assertEquals(0, policy.consecutiveHits)
        assertFalse(policy.forcedPcmDemote)
    }
}
