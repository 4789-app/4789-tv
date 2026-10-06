package com.fourseveneightnine.tv.client.playback

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressRecorderTest {

    private val target = ProgressTarget("tt7366338", "series", season = 2, episode = 4)

    private class FakeSink : ProgressSink {
        val writes = mutableListOf<Pair<Long, Long>>()
        var watched = 0

        override suspend fun writeProgress(target: ProgressTarget, positionMs: Long, durationMs: Long) {
            writes += positionMs to durationMs
        }

        override suspend fun markWatched(target: ProgressTarget) {
            watched++
        }
    }

    private class FakeClock(var now: Long = 0L) : () -> Long {
        override fun invoke(): Long = now
    }

    @Test
    fun `a tick inside fifteen seconds is dropped`() = runTest {
        val sink = FakeSink()
        val clock = FakeClock(100_000L)
        val recorder = ProgressRecorder(sink, clock)
        recorder.begin(target)

        assertTrue(recorder.record(60_000L, 2_640_000L, ProgressRecorder.Reason.Tick))
        clock.now += 14_000L
        assertFalse(recorder.record(74_000L, 2_640_000L, ProgressRecorder.Reason.Tick))
        clock.now += 1_000L
        assertTrue(recorder.record(75_000L, 2_640_000L, ProgressRecorder.Reason.Tick))

        assertEquals(2, sink.writes.size)
        assertEquals(60_000L to 2_640_000L, sink.writes.first())
    }

    @Test
    fun `pause and stop are never dropped`() = runTest {
        val sink = FakeSink()
        val clock = FakeClock(0L)
        val recorder = ProgressRecorder(sink, clock)
        recorder.begin(target)

        assertTrue(recorder.record(10_000L, 2_640_000L, ProgressRecorder.Reason.Tick))
        assertTrue(recorder.record(11_000L, 2_640_000L, ProgressRecorder.Reason.Pause))
        assertTrue(recorder.record(12_000L, 2_640_000L, ProgressRecorder.Reason.Stop))

        assertEquals(3, sink.writes.size)
    }

    @Test
    fun `at ninety-five percent it marks watched instead of writing progress`() = runTest {
        val sink = FakeSink()
        val recorder = ProgressRecorder(sink, FakeClock(0L))
        recorder.begin(target)

        assertTrue(recorder.record(2_508_000L, 2_640_000L, ProgressRecorder.Reason.Tick))

        assertEquals(0, sink.writes.size)
        assertEquals(1, sink.watched)
    }

    @Test
    fun `watched is written once, however many stops follow`() = runTest {
        val sink = FakeSink()
        val recorder = ProgressRecorder(sink, FakeClock(0L))
        recorder.begin(target)

        recorder.record(2_640_000L, 2_640_000L, ProgressRecorder.Reason.Ended)
        recorder.record(2_640_000L, 2_640_000L, ProgressRecorder.Reason.Stop)

        assertEquals(1, sink.watched)
    }

    @Test
    fun `Ended marks watched even when the engine reports position zero`() = runTest {
        val sink = FakeSink()
        val recorder = ProgressRecorder(sink, FakeClock(0L))
        recorder.begin(target)

        assertTrue(recorder.record(0L, 0L, ProgressRecorder.Reason.Ended))

        assertEquals(1, sink.watched)
    }

    @Test
    fun `position zero writes nothing on a normal tick`() = runTest {
        val sink = FakeSink()
        val recorder = ProgressRecorder(sink, FakeClock(0L))
        recorder.begin(target)

        assertFalse(recorder.record(0L, 2_640_000L, ProgressRecorder.Reason.Tick))
        assertEquals(0, sink.writes.size)
    }

    @Test
    fun `a new title starts the cadence and the watched latch again`() = runTest {
        val sink = FakeSink()
        val clock = FakeClock(0L)
        val recorder = ProgressRecorder(sink, clock)

        recorder.begin(target)
        recorder.record(2_640_000L, 2_640_000L, ProgressRecorder.Reason.Ended)
        recorder.begin(target.copy(episode = 5))
        recorder.record(2_640_000L, 2_640_000L, ProgressRecorder.Reason.Ended)

        assertEquals(2, sink.watched)
    }

    @Test
    fun `nothing is written before a title has begun`() = runTest {
        val sink = FakeSink()
        val recorder = ProgressRecorder(sink, FakeClock(0L))

        assertFalse(recorder.record(10_000L, 2_640_000L, ProgressRecorder.Reason.Stop))
    }
}
