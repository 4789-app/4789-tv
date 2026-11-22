package com.fourseveneightnine.tv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A late transport callback must not overwrite the reason playback ended.
 *
 * Live on the onn 4K Pro (2026-08-28 13:13:04): a phone-issued Stop set `Stopped`, the trailing
 * `isPlaying=false` landed on a coroutine afterwards and overwrote it with `Paused`, and because
 * `Paused` means "a film is up, keep the picture" the screen went black with nothing focusable on
 * it. `overlay.rescued phase=Paused` in the receiver's own diagnostics is that bug's fingerprint.
 */
class ExoPhaseOverwritePolicyTest {

    @Test
    fun aLivePlayerAcceptsPlayingAndPaused() {
        assertTrue(ExoPhaseOverwritePolicy.allowsTransportPhase(ReceiverPlaybackPhase.Playing))
        assertTrue(ExoPhaseOverwritePolicy.allowsTransportPhase(ReceiverPlaybackPhase.Paused))
        assertTrue(ExoPhaseOverwritePolicy.allowsTransportPhase(ReceiverPlaybackPhase.Buffering))
        assertTrue(
            ExoPhaseOverwritePolicy.allowsTransportPhase(
                ReceiverPlaybackPhase.Opening("A Movie"),
            ),
        )
    }

    @Test
    fun aBufferingEngineCannotBeReportedAsPausedByALateIsPlayingCallback() {
        for (phase in listOf(ReceiverPlaybackPhase.Playing, ReceiverPlaybackPhase.Paused,
                ReceiverPlaybackPhase.Buffering, ReceiverPlaybackPhase.Opening("A Movie"))) {
            assertFalse(ExoPhaseOverwritePolicy.allowsTransportPhase(phase, isBuffering = true))
        }
    }

    @Test
    fun aStoppedPlayerIsNeverRewrittenAsPaused() {
        assertFalse(ExoPhaseOverwritePolicy.allowsTransportPhase(ReceiverPlaybackPhase.Stopped))
    }

    @Test
    fun endedErroredAndIdleAreAlsoFinal() {
        assertFalse(ExoPhaseOverwritePolicy.allowsTransportPhase(ReceiverPlaybackPhase.Ended))
        assertFalse(ExoPhaseOverwritePolicy.allowsTransportPhase(ReceiverPlaybackPhase.Idle))
        assertFalse(
            ExoPhaseOverwritePolicy.allowsTransportPhase(
                ReceiverPlaybackPhase.Error("Audio hardware clock failure on TV."),
            ),
        )
    }
}


class LiveStallWatchTest {
    @org.junit.Test
    fun `a frozen picture interrupts live playback even while the audio clock advances`() {
        val watch = LiveStallWatch().apply { reset(1_000L) }
        org.junit.Assert.assertFalse(watch.stalled(8_999L, true, true, 1_000L, true))
        org.junit.Assert.assertTrue(watch.stalled(9_000L, true, true, 1_000L, true))
        org.junit.Assert.assertFalse(watch.stalled(9_500L, true, true, 9_499L, true))
    }

    @org.junit.Test
    fun `startup has a longer deadline and an intentional pause resets it`() {
        val watch = LiveStallWatch().apply { reset(1_000L) }
        org.junit.Assert.assertFalse(watch.stalled(18_999L, true, false, null, false))
        org.junit.Assert.assertTrue(watch.stalled(19_000L, true, false, null, false))
        org.junit.Assert.assertFalse(watch.stalled(30_000L, false, false, null, true))
        org.junit.Assert.assertFalse(watch.stalled(31_000L, true, false, null, true))
    }
}


class LiveTimingRecoveryTest {
    @Test
    fun isolatedAudioWarningsOrDroppedFramesDoNotReconnect() {
        val watch = LiveTimingRecovery()
        repeat(12) { assertFalse(watch.observe(1_000L + it * 40L, 1, audioDiscontinuity = true)) }
        assertFalse(watch.observe(6_000L, 1, droppedVideoFrames = 100))
        repeat(7) { assertFalse(watch.observe(6_050L + it * 40L, 1, audioDiscontinuity = true)) }
        assertTrue(watch.observe(6_500L, 1, audioDiscontinuity = true))
    }

    @Test
    fun observationsDoNotCrossStreamsAndTheReconnectCooldownSurvivesReplacement() {
        val watch = LiveTimingRecovery()
        repeat(8) { assertFalse(watch.observe(1_000L + it * 50L, 1, audioDiscontinuity = true)) }
        assertFalse(watch.observe(1_500L, 2, droppedVideoFrames = 50))
        repeat(7) { assertFalse(watch.observe(1_600L + it * 50L, 2, audioDiscontinuity = true)) }
        assertTrue(watch.observe(2_000L, 2, audioDiscontinuity = true))
        repeat(8) { assertFalse(watch.observe(2_100L + it * 50L, 3, audioDiscontinuity = true, droppedVideoFrames = 50)) }
        repeat(7) { assertFalse(watch.observe(62_000L + it * 50L, 3, audioDiscontinuity = true)) }
        assertTrue(watch.observe(62_500L, 3, audioDiscontinuity = true, droppedVideoFrames = 50))
    }
}
