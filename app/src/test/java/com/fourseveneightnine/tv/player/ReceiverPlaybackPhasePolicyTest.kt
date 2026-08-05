package com.fourseveneightnine.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ReceiverPlaybackPhasePolicyTest {
    @Test
    fun loadingFailureBecomesErrorAndPreservesAnExistingError() {
        val fromOpening = ReceiverPlaybackPhasePolicy.ended(
            ReceiverPlaybackPhase.Opening("Film"),
            openErrorMessage = "Could not open",
        )
        assertEquals(ReceiverPlaybackPhase.Error("Could not open"), fromOpening)

        val timeout = ReceiverPlaybackPhase.Error("Timed out")
        assertSame(timeout, ReceiverPlaybackPhasePolicy.ended(timeout, "Could not open"))
    }

    @Test
    fun ordinaryEndBecomesStopped() {
        assertEquals(
            ReceiverPlaybackPhase.Stopped,
            ReceiverPlaybackPhasePolicy.ended(ReceiverPlaybackPhase.Playing, "Could not open"),
        )
    }

    @Test
    fun cachePauseTakesPriorityOverUserPause() {
        assertEquals(
            ReceiverPlaybackPhase.Buffering,
            ReceiverPlaybackPhasePolicy.active(pausedForCache = true, speed = 0),
        )
        assertEquals(
            ReceiverPlaybackPhase.Paused,
            ReceiverPlaybackPhasePolicy.active(pausedForCache = false, speed = 0),
        )
        assertEquals(
            ReceiverPlaybackPhase.Playing,
            ReceiverPlaybackPhasePolicy.loaded(speed = 1),
        )
    }
}
