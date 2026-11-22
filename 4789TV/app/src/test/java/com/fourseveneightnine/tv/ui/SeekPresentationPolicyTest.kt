package com.fourseveneightnine.tv.ui

import com.fourseveneightnine.tv.protocol.ReceiverSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SeekPresentationPolicyTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun firstRightAfterTenMinutesWithoutUiPollsSeeksFromTheEngine() = runTest {
        val cached = ReceiverSnapshot(positionSeconds = 0.0)
        advanceTimeBy(600_000L)
        val target = SeekPresentationPolicy.targetForStep(
            10_000L, null, 1L, { 1L },
            { ReceiverSnapshot(positionSeconds = testScheduler.currentTime / 1_000.0, durationSeconds = 7_200.0) },
        )
        assertEquals(0.0, cached.positionSeconds, 0.0)
        assertEquals(610_000L, target)
    }

    @Test
    fun firstLeftAfterHiddenPlaybackUsesTheCurrentEnginePosition() = runTest {
        assertEquals(590_000L, SeekPresentationPolicy.targetForStep(
            -10_000L, null, 1L, { 1L },
            { ReceiverSnapshot(positionSeconds = 600.0, durationSeconds = 7_200.0) },
        ))
    }

    @Test
    fun rapidStepsAccumulateFromThePendingTargetBeforeTheEngineCatchesUp() = runTest {
        var pending: Long? = null
        repeat(3) {
            pending = SeekPresentationPolicy.targetForStep(
                10_000L, pending, 1L, { 1L },
                { ReceiverSnapshot(positionSeconds = 600.0, durationSeconds = 7_200.0) },
            )
        }
        assertEquals(630_000L, pending)
        assertEquals(620_000L, SeekPresentationPolicy.targetForStep(
            -10_000L, pending, 1L, { 1L },
            { ReceiverSnapshot(positionSeconds = 600.0, durationSeconds = 7_200.0) },
        ))
    }

    @Test
    fun pausedAndBufferingPositionsDoNotAdvanceWithWallTime() = runTest {
        for (active in listOf(true, false)) {
            assertEquals(610_000L, SeekPresentationPolicy.targetForStep(
                10_000L, null, 1L, { 1L },
                { ReceiverSnapshot(active = active, positionSeconds = 600.0, speed = 0) },
            ))
        }
    }

    @Test
    fun newGestureDropsThePreviousGesturesTargetAndHonorsCurrentDuration() = runTest {
        assertEquals(700_000L, SeekPresentationPolicy.targetForStep(
            10_000L, null, 1L, { 1L },
            { ReceiverSnapshot(positionSeconds = 695.0, durationSeconds = 700.0) },
        ))
    }

    @Test
    fun aFailedEngineReadCannotFallBackToACachedZeroOrPendingTarget() = runTest {
        val failure = IllegalStateException("Engine unavailable")
        try {
            SeekPresentationPolicy.targetForStep(10_000L, 600_000L, 1L, { 1L }, { throw failure })
            fail("A failed read must reject the seek")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
    }

    @Test
    fun replacementDuringTheEngineReadCancelsTheOldTitlesSeek() = runTest {
        var generation = 1L
        try {
            SeekPresentationPolicy.targetForStep(10_000L, null, 1L, { generation }, {
                generation = 2L
                ReceiverSnapshot(positionSeconds = 600.0)
            })
            fail("The old title must not seek the replacement")
        } catch (_: CancellationException) {
            assertEquals(2L, generation)
        }
    }

    @Test
    fun replacementBeforeDispatchDoesNotEvenReadTheNewTitle() = runTest {
        var read = false
        try {
            SeekPresentationPolicy.targetForStep(10_000L, null, 1L, { 2L }, {
                read = true
                ReceiverSnapshot()
            })
            fail("An obsolete gesture must be cancelled")
        } catch (_: CancellationException) {
            assertFalse(read)
        }
    }

    @Test
    fun hiddenControlsSeekShowsOnlyTheTopRightPreview() {
        val chrome = SeekPresentationPolicy.chromeDuringSeek(fullControlsWereVisible = false)

        assertTrue(chrome.topRightPreviewVisible)
        assertFalse(chrome.bottomTimelineVisible)
        assertFalse(chrome.fullControlsVisible)
    }

    @Test
    fun seekingDoesNotDismissControlsTheViewerAlreadyOpened() {
        val chrome = SeekPresentationPolicy.chromeDuringSeek(fullControlsWereVisible = true)

        assertTrue(chrome.topRightPreviewVisible)
        assertTrue(chrome.bottomTimelineVisible)
        assertTrue(chrome.fullControlsVisible)
    }

    @Test
    fun titleReplacementCancelsTheOldTitlesDelayedScrub() {
        assertTrue(SeekPresentationPolicy.mediaChanged("old-url", "new-url"))
        assertFalse(SeekPresentationPolicy.mediaChanged("same-url", "same-url"))
    }

    @Test
    fun stalePreSeekSnapshotCannotPullAnOptimisticForwardSeekBackwards() {
        val resolved = SeekPresentationPolicy.resolvePosition(70_000L, 60_000L, 70_000L, 300L)
        assertEquals(70_000L, resolved.millis)
        assertFalse(resolved.seekLanded)
    }

    @Test
    fun transientZeroCannotFlashAfterProgressWasEstablished() {
        val resolved = SeekPresentationPolicy.resolvePosition(70_000L, 0L, 70_000L, 900L)
        assertEquals(70_000L, resolved.millis)
        assertFalse(resolved.seekLanded)
    }

    @Test
    fun snapshotNearTheTargetConfirmsTheSeek() {
        val resolved = SeekPresentationPolicy.resolvePosition(70_000L, 72_100L, 70_000L, 700L)
        assertEquals(72_100L, resolved.millis)
        assertTrue(resolved.seekLanded)
    }

    @Test
    fun newestRapidTapTargetWinsOverAnOlderCallback() {
        val resolved = SeekPresentationPolicy.resolvePosition(80_000L, 70_000L, 80_000L, 120L)
        assertEquals(80_000L, resolved.millis)
        assertFalse(resolved.seekLanded)
    }

    @Test
    fun deliberateSeekToBeginningCanLandAtZero() {
        val resolved = SeekPresentationPolicy.resolvePosition(10_000L, 0L, 0L, 400L)
        assertEquals(0L, resolved.millis)
        assertTrue(resolved.seekLanded)
    }

    @Test
    fun aFailedNonzeroSeekEventuallyStopsHoldingTheOptimisticTarget() {
        val resolved = SeekPresentationPolicy.resolvePosition(
            70_000L,
            61_000L,
            70_000L,
            SeekPresentationPolicy.SETTLE_WINDOW_MILLIS,
        )
        assertEquals(61_000L, resolved.millis)
        assertTrue(resolved.seekLanded)
    }

    @Test
    fun knownDurationSurvivesATransientUnknownSnapshot() {
        assertEquals(7_200_000L, SeekPresentationPolicy.resolveDuration(7_200_000L, 0L))
        assertEquals(7_201_000L, SeekPresentationPolicy.resolveDuration(7_200_000L, 7_201_000L))
    }
}
