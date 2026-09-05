package com.fourseveneightnine.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three failures a *Black Widow* cast produced on the onn 4K Pro (2026-08-28 15:06–15:08), each
 * pinned to the evidence that named it.
 */
class VideoDecoderPressurePolicyTest {

    @Test
    fun ffmpegFallbackIsAudioOnly() {
        assertTrue(FfmpegExtensionPolicy.ENABLE_AUDIO_RENDERER)
        assertFalse(FfmpegExtensionPolicy.ENABLE_VIDEO_RENDERER)
    }

    // MARK: - A starved decoder is not a starved network

    @Test
    fun afullBufferWithAFrozenPlayheadIsAStarvedDecoder() {
        // Generation 5 of the Black Widow cast: the playhead never left 9,593ms while the buffer
        // reached 80,914ms. That cannot be the network.
        assertTrue(
            DecoderStarvationPolicy.isStarved(
                playWhenReady = true,
                bufferedAheadMs = 80_914,
                positionMs = 9_593,
                lastProgressPositionMs = 9_593,
                millisSinceProgress = 20_000,
            ),
        )
    }

    @Test
    fun anEmptyBufferIsAStreamProblemAndIsLeftAlone() {
        // The ordinary rebuffer. The stream has stopped arriving; the decoder is fine, and telling
        // the viewer their TV is out of memory would be a lie.
        assertFalse(
            DecoderStarvationPolicy.isStarved(
                playWhenReady = true,
                bufferedAheadMs = 0,
                positionMs = 9_593,
                lastProgressPositionMs = 9_593,
                millisSinceProgress = 30_000,
            ),
        )
    }

    @Test
    fun aPlayheadThatIsMovingIsNeverStarvedHoweverSlowly() {
        assertFalse(
            DecoderStarvationPolicy.isStarved(
                playWhenReady = true,
                bufferedAheadMs = 80_000,
                positionMs = 10_000,
                lastProgressPositionMs = 9_593,
                millisSinceProgress = 30_000,
            ),
        )
    }

    @Test
    fun aPausedPlayerIsNeverStarved() {
        // A viewer who pressed pause has a full buffer and a frozen playhead on purpose.
        assertFalse(
            DecoderStarvationPolicy.isStarved(
                playWhenReady = false,
                bufferedAheadMs = 80_000,
                positionMs = 9_593,
                lastProgressPositionMs = 9_593,
                millisSinceProgress = 600_000,
            ),
        )
    }

    @Test
    fun aBriefStallIsGivenTimeToRecoverOnItsOwn() {
        assertFalse(
            DecoderStarvationPolicy.isStarved(
                playWhenReady = true,
                bufferedAheadMs = 80_000,
                positionMs = 9_593,
                lastProgressPositionMs = 9_593,
                millisSinceProgress = DecoderStarvationPolicy.STARVED_FOR_MS - 1,
            ),
        )
    }

    @Test
    fun theVerdictBeatsThePhonesOwnStallWatchdogToTheDiagnosis() {
        // The phone gives up at 18s and restarts, which for this failure competes for the memory
        // that ran out. The receiver has to have said something before then.
        assertTrue(DecoderStarvationPolicy.STARVED_FOR_MS < 18_000)
    }

    // MARK: - Bounded decoder-stall recovery state

    @Test
    fun aBackwardSeekRebasesProgressBeforeStallEvaluation() {
        val policy = DecoderStallRecoveryPolicy()
        policy.beginPublicOpen(positionMs = 10_000, nowMs = 0)
        policy.frameRendered(positionMs = 10_000, nowMs = 0)
        policy.seekSubmitted()
        policy.seekIssued(positionMs = 10_000, nowMs = 50)
        policy.seekDiscontinuity(positionMs = 2_000, nowMs = 100)
        policy.frameRendered(positionMs = 2_000, nowMs = 200)

        assertEquals(
            DecoderStallAction.NONE,
            policy.evaluate(true, 20_000, 2_000, 200 + DecoderStarvationPolicy.STARVED_FOR_MS - 1),
        )
        assertEquals(
            DecoderStallAction.RECOVER,
            policy.evaluate(true, 20_000, 2_000, 200 + DecoderStarvationPolicy.STARVED_FOR_MS),
        )
    }

    @Test
    fun aForwardSeekRebasesProgressBeforeStallEvaluation() {
        val policy = DecoderStallRecoveryPolicy()
        policy.beginPublicOpen(positionMs = 2_000, nowMs = 0)
        policy.frameRendered(positionMs = 2_000, nowMs = 0)
        policy.seekSubmitted()
        policy.seekIssued(positionMs = 2_000, nowMs = 50)
        policy.seekDiscontinuity(positionMs = 30_000, nowMs = 100)
        policy.frameRendered(positionMs = 30_000, nowMs = 200)

        assertEquals(
            DecoderStallAction.NONE,
            policy.evaluate(true, 20_000, 30_000, 200 + DecoderStarvationPolicy.STARVED_FOR_MS - 1),
        )
    }

    @Test
    fun rapidSeeksUseTheLatestDiscontinuityAsTheirBaseline() {
        val policy = DecoderStallRecoveryPolicy()
        policy.beginPublicOpen(positionMs = 10_000, nowMs = 0)
        policy.frameRendered(positionMs = 10_000, nowMs = 0)
        policy.seekSubmitted()
        policy.seekIssued(positionMs = 10_000, nowMs = 50)
        policy.seekDiscontinuity(positionMs = 40_000, nowMs = 100)
        // A later callback can arrive for the latest backward command after the earlier forward one.
        policy.seekDiscontinuity(positionMs = 5_000, nowMs = 200)
        policy.frameRendered(positionMs = 5_000, nowMs = 300)

        assertEquals(
            DecoderStallAction.NONE,
            policy.evaluate(true, 20_000, 5_000, 300 + DecoderStarvationPolicy.STARVED_FOR_MS - 1),
        )
        assertEquals(
            DecoderStallAction.RECOVER,
            policy.evaluate(true, 20_000, 5_000, 300 + DecoderStarvationPolicy.STARVED_FOR_MS),
        )
    }

    @Test
    fun pausedAndActiveSeekStatesCannotRecoverButAFullBufferBeforeFirstFrameCan() {
        val policy = DecoderStallRecoveryPolicy()
        policy.beginPublicOpen(positionMs = 0, nowMs = 0)
        assertEquals(
            DecoderStallAction.RECOVER,
            policy.evaluate(true, 20_000, 0, DecoderStarvationPolicy.STARVED_FOR_MS),
        )

        policy.beginPublicOpen(positionMs = 0, nowMs = 30_000)
        policy.frameRendered(positionMs = 0, nowMs = 30_000)
        assertEquals(DecoderStallAction.NONE, policy.evaluate(false, 20_000, 0, 60_000))

        policy.seekSubmitted()
        policy.seekIssued(positionMs = 0, nowMs = 60_001)
        assertEquals(
            DecoderStallAction.NONE,
            policy.evaluate(true, 20_000, 0, 60_001 + DecoderStarvationPolicy.STARVED_FOR_MS - 1),
        )
    }

    @Test
    fun seekWithoutCallbacksButWithProgressRearmsWithoutRecovery() {
        val policy = DecoderStallRecoveryPolicy()
        policy.beginPublicOpen(positionMs = 0, nowMs = 0)
        policy.frameRendered(positionMs = 0, nowMs = 0)
        policy.seekSubmitted()
        policy.seekIssued(positionMs = 0, nowMs = 100)

        assertEquals(DecoderStallAction.NONE, policy.evaluate(true, 20_000, 500, 200))
        assertEquals(DecoderStallAction.NONE, policy.evaluate(true, 20_000, 1_000, 30_000))
    }

    @Test
    fun seekWithoutCallbacksAndFrozenFullBufferRecoversAfterBoundedGrace() {
        val policy = DecoderStallRecoveryPolicy()
        policy.beginPublicOpen(positionMs = 0, nowMs = 0)
        policy.frameRendered(positionMs = 0, nowMs = 0)
        policy.seekSubmitted()
        policy.seekIssued(positionMs = 0, nowMs = 100)

        assertEquals(
            DecoderStallAction.NONE,
            policy.evaluate(true, 20_000, 0, 100 + DecoderStarvationPolicy.STARVED_FOR_MS - 1),
        )
        assertEquals(
            DecoderStallAction.RECOVER,
            policy.evaluate(true, 20_000, 0, 100 + DecoderStarvationPolicy.STARVED_FOR_MS),
        )
    }

    @Test
    fun backwardSeekThenOrdinaryProgressDoesNotUseTheOldBaselineDeadline() {
        val policy = DecoderStallRecoveryPolicy()
        policy.beginPublicOpen(positionMs = 10_000, nowMs = 0)
        policy.frameRendered(positionMs = 10_000, nowMs = 0)
        policy.seekSubmitted()
        policy.seekIssued(positionMs = 10_000, nowMs = 50)
        policy.seekDiscontinuity(positionMs = 2_000, nowMs = 100)

        // No rendered-frame callback is needed when the ordinary playhead itself has advanced.
        assertEquals(DecoderStallAction.NONE, policy.evaluate(true, 20_000, 2_300, 200))
        assertEquals(
            DecoderStallAction.NONE,
            policy.evaluate(true, 20_000, 2_300, DecoderStarvationPolicy.STARVED_FOR_MS),
        )
    }

    @Test
    fun deferredSeekCannotTriggerAFalseRecoveryBeforeItIsIssued() {
        val policy = DecoderStallRecoveryPolicy()
        policy.beginPublicOpen(positionMs = 0, nowMs = 0)
        policy.frameRendered(positionMs = 0, nowMs = 0)
        policy.seekSubmitted()

        assertEquals(DecoderStallAction.NONE, policy.evaluate(true, 20_000, 0, 30_000))
    }

    @Test
    fun delayedOlderSeekCallbacksCannotRebaseTheLatestBackwardSeek() {
        val tolerance = 2_000L
        val latestBackwardTarget = 5_000L
        assertTrue(SeekCallbackAcceptancePolicy.acceptsDiscontinuity(latestBackwardTarget, 5_000, tolerance))
        assertFalse(SeekCallbackAcceptancePolicy.acceptsDiscontinuity(latestBackwardTarget, 40_000, tolerance))
        assertTrue(
            SeekCallbackAcceptancePolicy.acceptsRenderedFrame(
                activeTargetMs = latestBackwardTarget,
                reportedPositionMs = 5_000,
                currentPositionMs = 5_000,
                toleranceMs = tolerance,
            ),
        )
        // Once the latest frame settled and cleared its target, a delayed older forward frame
        // still cannot change the baseline because it disagrees with the on-screen player.
        assertFalse(
            SeekCallbackAcceptancePolicy.acceptsRenderedFrame(
                activeTargetMs = null,
                reportedPositionMs = 40_000,
                currentPositionMs = 5_000,
                toleranceMs = tolerance,
            ),
        )
        assertTrue(
            SeekCallbackAcceptancePolicy.acceptsRenderedFrame(
                activeTargetMs = null,
                reportedPositionMs = null,
                currentPositionMs = 5_000,
                toleranceMs = tolerance,
            ),
        )
        assertFalse(
            SeekCallbackAcceptancePolicy.acceptsRenderedFrame(
                activeTargetMs = null,
                reportedPositionMs = null,
                currentPositionMs = null,
                toleranceMs = tolerance,
            ),
        )
    }

    @Test
    fun rapidTenSecondSeekRejectsTheOlderSyncFrameAndRequiresCurrentPlayerAgreement() {
        val tolerance = SeekCoalescingPolicy.SYNC_TOLERANCE_MILLIS
        val latestTarget = 20_000L

        assertFalse(
            SeekCallbackAcceptancePolicy.acceptsRenderedFrame(
                activeTargetMs = latestTarget,
                reportedPositionMs = 12_000,
                currentPositionMs = 20_000,
                toleranceMs = tolerance,
            ),
        )
        assertFalse(
            SeekCallbackAcceptancePolicy.acceptsDiscontinuity(
                activeTargetMs = latestTarget,
                newPositionMs = 12_000,
                toleranceMs = tolerance,
            ),
        )
        assertTrue(
            SeekCallbackAcceptancePolicy.acceptsRenderedFrame(
                activeTargetMs = latestTarget,
                reportedPositionMs = 18_000,
                currentPositionMs = 20_000,
                toleranceMs = tolerance,
            ),
        )
    }

    @Test
    fun nearbyAndMissingPositionCallbacksCannotOverlapBecauseOnlyOneSeekMayBeActive() {
        // Position proximity cannot distinguish 102s from a newer 103s scrub target. The
        // controller therefore keeps 103s pending until the active 100s frame settles or times out.
        assertFalse(SeekCoalescingPolicy.canIssue(activeTargetMs = 100_000))
        assertTrue(SeekCoalescingPolicy.canIssue(activeTargetMs = null))

        // With one active seek, a missing analytics position may safely fall back to the current
        // player position; a current position outside the active target's window remains rejected.
        assertTrue(
            SeekCallbackAcceptancePolicy.acceptsRenderedFrame(
                activeTargetMs = 100_000,
                reportedPositionMs = null,
                currentPositionMs = 102_000,
                toleranceMs = SeekCoalescingPolicy.SYNC_TOLERANCE_MILLIS,
            ),
        )
        assertFalse(
            SeekCallbackAcceptancePolicy.acceptsRenderedFrame(
                activeTargetMs = 100_000,
                reportedPositionMs = null,
                currentPositionMs = 103_000,
                toleranceMs = SeekCoalescingPolicy.SYNC_TOLERANCE_MILLIS,
            ),
        )
    }

    @Test
    fun stalePlayerCallbacksCannotMutateTheReplacementGeneration() {
        assertTrue(
            PlayerCallbackOwnershipPolicy.accepts(
                installedPlayerGeneration = 4,
                activePlayerGeneration = 4,
                isCurrentPlayerInstance = true,
            ),
        )
        assertFalse(
            PlayerCallbackOwnershipPolicy.accepts(
                installedPlayerGeneration = 4,
                activePlayerGeneration = 5,
                isCurrentPlayerInstance = false,
            ),
        )
        assertFalse(
            PlayerCallbackOwnershipPolicy.accepts(
                installedPlayerGeneration = 4,
                activePlayerGeneration = 0,
                isCurrentPlayerInstance = true,
            ),
        )
    }

    @Test
    fun pauseAndResumeBetweenSamplesRebasesTheStarvationClock() {
        val policy = DecoderStallRecoveryPolicy()
        policy.beginPublicOpen(positionMs = 0, nowMs = 0)
        policy.frameRendered(positionMs = 0, nowMs = 0)
        policy.playWhenReadyChanged(positionMs = 0, nowMs = 11_900)
        policy.playWhenReadyChanged(positionMs = 0, nowMs = 11_950)

        assertEquals(DecoderStallAction.NONE, policy.evaluate(true, 20_000, 0, 12_001))
        assertEquals(
            DecoderStallAction.RECOVER,
            policy.evaluate(true, 20_000, 0, 11_950 + DecoderStarvationPolicy.STARVED_FOR_MS),
        )
    }

    @Test
    fun firstVideoDecoderInitializationFailureRecoversSecondSurfacesAndPublicOpenResets() {
        val policy = DecoderStallRecoveryPolicy()
        policy.beginPublicOpen(positionMs = 0, nowMs = 0)
        assertEquals(DecoderStallAction.RECOVER, policy.videoDecoderInitializationFailed())

        policy.beginRecoveryOpen(positionMs = 0, nowMs = 1)
        assertEquals(DecoderStallAction.SURFACE_FAILURE, policy.videoDecoderInitializationFailed())

        policy.beginPublicOpen(positionMs = 0, nowMs = 2)
        assertEquals(DecoderStallAction.RECOVER, policy.videoDecoderInitializationFailed())
    }

    @Test
    fun advancingWithoutAFirstFrameRecoversAndEndedWithoutAFrameSharesTheBudget() {
        val policy = DecoderStallRecoveryPolicy()
        policy.beginPublicOpen(positionMs = 0, nowMs = 0)
        assertEquals(DecoderStallAction.NONE, policy.evaluate(true, 5_000, 1_999, 1_999))
        assertEquals(DecoderStallAction.RECOVER, policy.evaluate(true, 5_000, 2_000, 2_000))

        policy.beginRecoveryOpen(positionMs = 0, nowMs = 2_001)
        assertEquals(DecoderStallAction.SURFACE_FAILURE, policy.videoEndedWithoutFrame())
        assertEquals(DecoderStallAction.NONE, policy.videoEndedWithoutFrame())
    }

    @Test
    fun publicStreamReplacementAlwaysReleasesAnExistingPlayer() {
        assertTrue(PublicStreamReplacementPolicy.requiresFreshPlayer(hasCurrentPlayer = true))
        assertFalse(PublicStreamReplacementPolicy.requiresFreshPlayer(hasCurrentPlayer = false))
    }

    @Test
    fun recoverySubtitleSelectionKeepsOffDistinctFromNoTracksAndSelectedIndex() {
        assertEquals(
            RecoverySubtitleSelection.NoTextTracks,
            RecoverySubtitleSelectionPolicy.capture(textTrackCount = 0, selectedIndex = null),
        )
        assertEquals(
            RecoverySubtitleSelection.ExplicitlyOff,
            RecoverySubtitleSelectionPolicy.capture(textTrackCount = 3, selectedIndex = null),
        )
        assertEquals(
            RecoverySubtitleSelection.Index(2),
            RecoverySubtitleSelectionPolicy.capture(textTrackCount = 3, selectedIndex = 2),
        )
        assertFalse(
            RecoverySubtitleSelectionPolicy.isReady(RecoverySubtitleSelection.ExplicitlyOff, textTrackCount = 0),
        )
        assertTrue(
            RecoverySubtitleSelectionPolicy.isReady(RecoverySubtitleSelection.ExplicitlyOff, textTrackCount = 1),
        )
        assertEquals(
            RecoverySubtitleSelection.ExplicitlyOff,
            RecoverySubtitleSelectionPolicy.captureForRecovery(
                textRendererDisabled = true,
                pendingRecoverySelection = null,
                pendingRestoreIndex = null,
                textTrackCount = 0,
                selectedIndex = null,
            ),
        )
        assertEquals(
            RecoverySubtitleSelection.Index(2),
            RecoverySubtitleSelectionPolicy.captureForRecovery(
                // A pending explicit selection is newer intent than the selector's old disabled
                // bit and must survive an init failure before tracks arrive.
                textRendererDisabled = true,
                pendingRecoverySelection = null,
                pendingRestoreIndex = 2,
                textTrackCount = 0,
                selectedIndex = null,
            ),
        )
    }

    @Test
    fun firstStallRecoversSecondStallSurfacesAndNeverLoops() {
        val policy = DecoderStallRecoveryPolicy()
        policy.beginPublicOpen(positionMs = 0, nowMs = 0)
        policy.frameRendered(positionMs = 0, nowMs = 0)
        assertEquals(
            DecoderStallAction.RECOVER,
            policy.evaluate(true, 20_000, 0, DecoderStarvationPolicy.STARVED_FOR_MS),
        )

        policy.beginRecoveryOpen(positionMs = 0, nowMs = 20_000)
        policy.frameRendered(positionMs = 0, nowMs = 20_000)
        assertEquals(
            DecoderStallAction.SURFACE_FAILURE,
            policy.evaluate(true, 20_000, 0, 20_000 + DecoderStarvationPolicy.STARVED_FOR_MS),
        )
        assertEquals(
            DecoderStallAction.NONE,
            policy.evaluate(true, 20_000, 0, 40_000),
        )

        // A later public open is a new user-visible player generation, not another internal retry.
        policy.beginPublicOpen(positionMs = 0, nowMs = 50_000)
        policy.frameRendered(positionMs = 0, nowMs = 50_000)
        assertEquals(
            DecoderStallAction.RECOVER,
            policy.evaluate(true, 20_000, 0, 50_000 + DecoderStarvationPolicy.STARVED_FOR_MS),
        )
    }

    @Test
    fun newPublicOpenResetsTheRecoveryBudget() {
        val policy = DecoderStallRecoveryPolicy()
        policy.beginPublicOpen(positionMs = 0, nowMs = 0)
        policy.frameRendered(positionMs = 0, nowMs = 0)
        assertEquals(
            DecoderStallAction.RECOVER,
            policy.evaluate(true, 20_000, 0, DecoderStarvationPolicy.STARVED_FOR_MS),
        )

        policy.beginPublicOpen(positionMs = 0, nowMs = 20_000)
        policy.frameRendered(positionMs = 0, nowMs = 20_000)
        assertEquals(
            DecoderStallAction.RECOVER,
            policy.evaluate(true, 20_000, 0, 20_000 + DecoderStarvationPolicy.STARVED_FOR_MS),
        )
    }

    // MARK: - Keeping the audio track across a re-open of the same cast

    @Test
    fun theSameCastKeepsTheTrackItWasPlaying() {
        // Black Widow wandered E-AC3 7.1 -> AC3 stereo -> TrueHD across generations of ONE cast.
        assertEquals(
            2,
            AudioTrackContinuityPolicy.restoreIndex(
                previousIndex = 2,
                previousCastId = "F2381968",
                incomingCastId = "F2381968",
                incomingAudioTrackCount = 4,
            ),
        )
    }

    @Test
    fun aDifferentCastStartsFromItsOwnAnswer() {
        // Another title's track numbering means nothing here.
        assertNull(
            AudioTrackContinuityPolicy.restoreIndex(
                previousIndex = 2,
                previousCastId = "F2381968",
                incomingCastId = "A31314D8",
                incomingAudioTrackCount = 4,
            ),
        )
    }

    @Test
    fun aTrackThatIsNotThereIsNeverRestored() {
        assertNull(
            AudioTrackContinuityPolicy.restoreIndex(
                previousIndex = 5,
                previousCastId = "F2381968",
                incomingCastId = "F2381968",
                incomingAudioTrackCount = 2,
            ),
        )
    }

    @Test
    fun nothingToRestoreIsNotAnError() {
        assertNull(
            AudioTrackContinuityPolicy.restoreIndex(
                previousIndex = null,
                previousCastId = "F2381968",
                incomingCastId = "F2381968",
                incomingAudioTrackCount = 4,
            ),
        )
        assertNull(
            AudioTrackContinuityPolicy.restoreIndex(
                previousIndex = 1,
                previousCastId = null,
                incomingCastId = "F2381968",
                incomingAudioTrackCount = 4,
            ),
        )
    }
}
