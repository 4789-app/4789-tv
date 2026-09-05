package com.fourseveneightnine.tv.player

import com.fourseveneightnine.tv.protocol.ReceiverEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvReceiverStateTest {
    @Test
    fun nativeDiagnosticsRedactSignedUrlsAndNewlines() {
        val safe = MpvNativeLogPolicy.safe("TLS failed https://example.test/file?token=secret\nnext")

        assertEquals("TLS failed <url> next", safe)
    }

    @Test
    fun loadingUrlIsNotReportedAsConnectedUntilFileLoaded() {
        assertEquals(false, MpvLoadStatePolicy.isPlayable(idleActive = false, fileLoaded = false))
        assertEquals(true, MpvLoadStatePolicy.isPlayable(idleActive = false, fileLoaded = true))
        assertEquals(false, MpvLoadStatePolicy.isPlayable(idleActive = true, fileLoaded = true))
    }

    @Test
    fun replacementOpenCannotExposeThePreviousFilesClockAsActive() {
        val store = MpvSnapshotStore(
            MpvPlaybackState(
                active = true,
                paused = true,
                positionSeconds = 1.916,
                durationSeconds = 2.0,
            ),
        )

        assertNull(store.update(MpvSnapshotReducer::opening))

        val snapshot = store.snapshot()
        assertEquals(false, snapshot.active)
        assertEquals(0.0, snapshot.positionSeconds, 0.0)
        assertEquals(0.0, snapshot.durationSeconds, 0.0)
    }

    @Test
    fun pauseAndResumeRetainTheUnderlyingPlaybackRate() {
        val store = MpvSnapshotStore(
            MpvPlaybackState(
                active = true,
                playbackRate = 2.0,
            ),
        )

        val pause = store.update { MpvSnapshotReducer.paused(it, paused = true) }

        assertTrue(pause is ReceiverEvent.Pause)
        assertEquals(0, store.snapshot().speed)

        val play = store.update { MpvSnapshotReducer.paused(it, paused = false) }

        assertTrue(play is ReceiverEvent.Play)
        assertEquals(2, store.snapshot().speed)
    }

    @Test
    fun invalidNativeNumbersDoNotReplaceTheLastKnownSnapshot() {
        val store = MpvSnapshotStore(
            MpvPlaybackState(
                active = true,
                positionSeconds = 45.0,
                durationSeconds = 120.0,
                playbackRate = 1.0,
            ),
        )

        assertNull(store.update { MpvSnapshotReducer.position(it, Double.NaN) })
        assertNull(store.update { MpvSnapshotReducer.duration(it, Double.POSITIVE_INFINITY) })
        assertNull(store.update { MpvSnapshotReducer.playbackRate(it, Double.NaN) })

        assertEquals(45.0, store.snapshot().positionSeconds, 0.0)
        assertEquals(120.0, store.snapshot().durationSeconds, 0.0)
        assertEquals(1, store.snapshot().speed)
    }

    @Test
    fun inactiveTransitionEmitsOneStopWithAStoppedSnapshot() {
        val store = MpvSnapshotStore(
            MpvPlaybackState(
                active = true,
                playbackRate = 4.0,
            ),
        )

        val event = store.update { MpvSnapshotReducer.active(it, active = false) }

        assertTrue(event is ReceiverEvent.Stop)
        assertEquals(0, store.snapshot().speed)
        assertNull(store.update { MpvSnapshotReducer.active(it, active = false) })
    }

    @Test
    fun volumeIsKeptInKodiCompatibleBounds() {
        val store = MpvSnapshotStore(MpvPlaybackState(active = true, volume = 50))

        val event = store.update { MpvSnapshotReducer.volume(it, 150.0) }

        assertTrue(event is ReceiverEvent.VolumeChanged)
        assertEquals(100, store.snapshot().volume)
        assertNull(store.update { MpvSnapshotReducer.volume(it, Double.NaN) })
    }

    @Test
    fun seekDoesNotEmitForInactiveOrInvalidState() {
        val inactive = MpvSnapshotStore()
        val active = MpvSnapshotStore(MpvPlaybackState(active = true))

        assertNull(inactive.seekEvent(10.0))
        assertNull(active.seekEvent(Double.NaN))
        val event = active.seekEvent(10.0)
        assertTrue(event is ReceiverEvent.Seek)
        if (event is ReceiverEvent.Seek) {
            assertEquals(10.0, event.offsetSeconds, 0.0)
        }
    }
}
