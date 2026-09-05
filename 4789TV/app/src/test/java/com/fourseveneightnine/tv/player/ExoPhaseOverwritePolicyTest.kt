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
