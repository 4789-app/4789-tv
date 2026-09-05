package com.fourseveneightnine.tv.player

import com.fourseveneightnine.tv.protocol.ReceiverAudioProfile
import com.fourseveneightnine.tv.protocol.ReceiverPreparationStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    @Test
    fun staleTerminalPreparationCannotReplaceANewerOrPlayingTitle() {
        assertEquals(
            ReceiverPlaybackPhase.Opening(
                "New Film",
                ReceiverPreparationStage.RESOLVING,
                "cast-new",
            ),
            ReceiverPlaybackPhasePolicy.preparation(
                prior = ReceiverPlaybackPhase.Idle,
                title = "New Film",
                stage = ReceiverPreparationStage.RESOLVING,
                castId = "cast-new",
                activeCastId = null,
            ),
        )
        assertNull(
            ReceiverPlaybackPhasePolicy.preparation(
                prior = ReceiverPlaybackPhase.Opening("New Film", castId = "cast-new"),
                title = "Old Film",
                stage = ReceiverPreparationStage.FAILED,
                castId = "cast-old",
                activeCastId = "cast-new",
            ),
        )
        assertNull(
            ReceiverPlaybackPhasePolicy.preparation(
                prior = ReceiverPlaybackPhase.Playing,
                title = "Old Film",
                stage = ReceiverPreparationStage.FAILED,
                castId = "cast-old",
                activeCastId = "cast-old",
            ),
        )
        val receiverError = ReceiverPlaybackPhase.Error("Decoder rejected Dolby Vision")
        assertNull(
            ReceiverPlaybackPhasePolicy.preparation(
                prior = receiverError,
                title = "Film",
                stage = ReceiverPreparationStage.FAILED,
                castId = "cast-new",
                activeCastId = "cast-new",
            ),
        )
    }

    @Test
    fun preparationStagesCannotRegressAnActiveOrMoreAdvancedCast() {
        assertNull(
            ReceiverPlaybackPhasePolicy.preparation(
                prior = ReceiverPlaybackPhase.Opening(
                    "Film",
                    ReceiverPreparationStage.PREPARING,
                    "cast-1",
                ),
                title = "Film",
                stage = ReceiverPreparationStage.RESOLVING,
                castId = "cast-1",
                activeCastId = "cast-1",
            ),
        )
        assertNull(
            ReceiverPlaybackPhasePolicy.preparation(
                prior = ReceiverPlaybackPhase.Playing,
                title = "Film",
                stage = ReceiverPreparationStage.PREPARING,
                castId = "cast-1",
                activeCastId = "cast-1",
            ),
        )
        assertEquals(
            ReceiverPlaybackPhase.Opening(
                "Next Film",
                ReceiverPreparationStage.RESOLVING,
                "cast-2",
            ),
            ReceiverPlaybackPhasePolicy.preparation(
                prior = ReceiverPlaybackPhase.Playing,
                title = "Next Film",
                stage = ReceiverPreparationStage.RESOLVING,
                castId = "cast-2",
                activeCastId = "cast-1",
            ),
        )
    }

    @Test
    fun rapidOpenRejectsAnOldFirstFrameUntilTheNewDecoderIsReady() {
        val policy = ExoPlaybackGenerationPolicy()
        val first = policy.beginOpen()
        assertEquals(1L, first)
        assertTrue(policy.videoDecoderInitialized(first))
        assertEquals(ExoRenderedFrameDisposition.FIRST, policy.renderedFrame(first))

        val second = policy.beginOpen()
        assertEquals(2L, second)
        assertFalse(policy.videoDecoderInitialized(first))
        assertEquals(ExoRenderedFrameDisposition.STALE, policy.renderedFrame(first))
        assertEquals(ExoRenderedFrameDisposition.STALE, policy.renderedFrame(second))
        assertFalse(policy.hasRenderedFirstFrame)

        assertTrue(policy.videoDecoderInitialized(second))
        assertEquals(ExoRenderedFrameDisposition.FIRST, policy.renderedFrame(second))
        assertTrue(policy.hasRenderedFirstFrame)
        assertEquals(ExoRenderedFrameDisposition.CURRENT_REPEAT, policy.renderedFrame(second))
    }

    @Test
    fun currentRepeatFrameCompletesAResumeSeekButStaleFramesCannot() {
        val policy = ExoPlaybackGenerationPolicy()
        val seekAudio = ExoSeekAudioLifecycle()
        val first = policy.beginOpen()
        assertTrue(policy.videoDecoderInitialized(first))
        assertEquals(ExoRenderedFrameDisposition.FIRST, policy.renderedFrame(first))

        // The player can render at zero before STATE_READY applies the saved resume. The frame
        // after that seek is not a second playback start, but it is the boundary that may unmute.
        seekAudio.begin()
        val resumeFrame = policy.renderedFrame(first)
        assertEquals(ExoRenderedFrameDisposition.CURRENT_REPEAT, resumeFrame)
        assertTrue(seekAudio.renderedFrame(resumeFrame, belongsToLatestSeek = true))
        assertFalse(seekAudio.isMuted)
        assertFalse(seekAudio.renderedFrame(resumeFrame, belongsToLatestSeek = true))

        val replacement = policy.beginOpen()
        seekAudio.begin()
        val staleFrame = policy.renderedFrame(first)
        assertEquals(ExoRenderedFrameDisposition.STALE, staleFrame)
        assertFalse(seekAudio.renderedFrame(staleFrame, belongsToLatestSeek = true))
        assertTrue(seekAudio.isMuted)
        assertEquals(ExoRenderedFrameDisposition.STALE, policy.renderedFrame(replacement))
        assertTrue(policy.videoDecoderInitialized(replacement))
        val replacementFrame = policy.renderedFrame(replacement)
        assertEquals(ExoRenderedFrameDisposition.FIRST, replacementFrame)
        assertTrue(seekAudio.renderedFrame(replacementFrame, belongsToLatestSeek = true))
        assertFalse(seekAudio.isMuted)

        seekAudio.begin()
        assertTrue(seekAudio.cancel())
        assertFalse(seekAudio.isMuted)
        assertFalse(seekAudio.cancel())
    }

    @Test
    fun anOlderCurrentTitleFrameCannotUnmuteTheLatestRapidSeek() {
        val seekAudio = ExoSeekAudioLifecycle()
        seekAudio.begin()

        assertFalse(
            seekAudio.renderedFrame(
                ExoRenderedFrameDisposition.CURRENT_REPEAT,
                belongsToLatestSeek = false,
            ),
        )
        assertTrue(seekAudio.isMuted)
        assertTrue(
            seekAudio.renderedFrame(
                ExoRenderedFrameDisposition.CURRENT_REPEAT,
                belongsToLatestSeek = true,
            ),
        )
        assertFalse(seekAudio.isMuted)
    }

    @Test
    fun audioProfilesAndDeviceQueueingHaveTotalDeterministicMappings() {
        val automatic = ReceiverAudioProfilePolicy.plan(ReceiverAudioProfile.AUTOMATIC)
        assertTrue(automatic.automatic)
        assertFalse(automatic.passthrough)

        val lossless = ReceiverAudioProfilePolicy.plan(ReceiverAudioProfile.SURROUND_LOSSLESS)
        assertTrue(lossless.passthrough)
        assertTrue(lossless.codecRequests.values.all { it })
        assertTrue(lossless.codecRequests.getValue("truehd"))

        val decoded = ReceiverAudioProfilePolicy.plan(ReceiverAudioProfile.SURROUND_DECODE)
        assertFalse(decoded.automatic)
        assertFalse(decoded.passthrough)
        assertTrue(decoded.codecRequests.isEmpty())

        assertTrue(ExoMediaCodecQueueingPolicy.forceSynchronous("Amazon", "anything"))
        assertTrue(ExoMediaCodecQueueingPolicy.forceSynchronous("Other", "AFTDCT31"))
        assertFalse(ExoMediaCodecQueueingPolicy.forceSynchronous("Google", "ADT-3"))
    }

    // MARK: - What the "something must be on screen" net is allowed to overrule

    @Test
    fun aPhaseThatMeansAPictureStopsTheHomeScreenBeingDrawnOverIt() {
        // Live on the onn 4K Pro (2026-08-28 14:43:51.524): the net fired 81ms after the first
        // frame, because `playbackActive` had not caught up yet, and drew the home screen over a
        // film that had just started.
        assertTrue(ReceiverPlaybackPhasePolicy.showsPicture(ReceiverPlaybackPhase.Playing))
        assertTrue(ReceiverPlaybackPhasePolicy.showsPicture(ReceiverPlaybackPhase.Paused))
        assertTrue(ReceiverPlaybackPhasePolicy.showsPicture(ReceiverPlaybackPhase.Buffering))
    }

    @Test
    fun aPhaseWithNoPictureStillLetsTheNetFire() {
        // The black screen that swallows the D-pad is the whole reason the net exists.
        assertFalse(ReceiverPlaybackPhasePolicy.showsPicture(ReceiverPlaybackPhase.Idle))
        assertFalse(ReceiverPlaybackPhasePolicy.showsPicture(ReceiverPlaybackPhase.Stopped))
        assertFalse(ReceiverPlaybackPhasePolicy.showsPicture(ReceiverPlaybackPhase.Ended))
        assertFalse(ReceiverPlaybackPhasePolicy.showsPicture(ReceiverPlaybackPhase.Error("boom")))
        assertFalse(ReceiverPlaybackPhasePolicy.showsPicture(ReceiverPlaybackPhase.Opening("A Movie")))
    }
}
