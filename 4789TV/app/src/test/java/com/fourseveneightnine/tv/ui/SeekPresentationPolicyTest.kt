package com.fourseveneightnine.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekPresentationPolicyTest {
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
