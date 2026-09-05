package com.fourseveneightnine.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalPlaybackProgressPolicyTest {
    @Test
    fun onlyMeaningfulUnfinishedProgressResumes() {
        assertNull(LocalPlaybackProgressPolicy.resumable(-1, 120_000))
        assertNull(LocalPlaybackProgressPolicy.resumable(4_999, 120_000))
        assertNull(LocalPlaybackProgressPolicy.resumable(20_000, 0))
        assertNull(LocalPlaybackProgressPolicy.resumable(110_400, 120_000))

        assertEquals(
            LocalPlaybackProgress(positionMillis = 60_000, durationMillis = 120_000),
            LocalPlaybackProgressPolicy.resumable(60_000, 120_000),
        )
    }

    @Test
    fun progressIsBoundedAndRenderedDeterministically() {
        val bounded = LocalPlaybackProgressPolicy.resumable(300_000, 600_000)
        assertEquals(50, LocalPlaybackProgressPolicy.progressPercent(requireNotNull(bounded)))
    }
}
