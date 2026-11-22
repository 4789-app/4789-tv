package com.fourseveneightnine.tv.client.playback

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.util.concurrent.atomic.AtomicInteger
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
        var watchedAttempts = 0
        var watchedFailure: Exception? = null

        override suspend fun writeProgress(target: ProgressTarget, positionMs: Long, durationMs: Long) {
            writes += positionMs to durationMs
        }

        override suspend fun markWatched(target: ProgressTarget) {
            watchedAttempts++
            watchedFailure?.let { failure ->
                watchedFailure = null
                throw failure
            }
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
    fun `a failed watched write can retry and then latches`() = runTest {
        val failure = IOException("synthetic watched failure")
        val sink = FakeSink().apply { watchedFailure = failure }
        val recorder = ProgressRecorder(sink, FakeClock())
        recorder.begin(target)

        val thrown = try {
            recorder.record(2_640_000L, 2_640_000L, ProgressRecorder.Reason.Ended)
            null
        } catch (caught: IOException) { caught }
        assertTrue("The original failure must reach the caller", thrown === failure)
        assertEquals(0, sink.watched)
        assertTrue(recorder.record(2_640_000L, 2_640_000L, ProgressRecorder.Reason.Stop))
        assertFalse(recorder.record(2_640_000L, 2_640_000L, ProgressRecorder.Reason.Ended))
        assertEquals(2, sink.watchedAttempts)
        assertEquals(1, sink.watched)
    }

    @Test
    fun `cancelling an in-flight watched write allows retry without duplicate calls`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var waitForRelease = true
        var attempts = 0
        var watched = 0
        val sink = object : ProgressSink {
            override suspend fun writeProgress(target: ProgressTarget, positionMs: Long, durationMs: Long) = Unit
            override suspend fun markWatched(target: ProgressTarget) {
                attempts++
                if (waitForRelease) {
                    entered.complete(Unit)
                    release.await()
                }
                watched++
            }
        }
        val recorder = ProgressRecorder(sink, FakeClock())
        recorder.begin(target)
        val pending = launch(Dispatchers.Default) { recorder.record(0L, 0L, ProgressRecorder.Reason.Ended) }
        entered.await()
        assertFalse(recorder.record(2_640_000L, 2_640_000L, ProgressRecorder.Reason.Stop))
        assertEquals(1, attempts)
        pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
        assertEquals(0, watched)
        waitForRelease = false
        assertTrue(recorder.record(0L, 0L, ProgressRecorder.Reason.Ended))
        assertFalse(recorder.record(2_640_000L, 2_640_000L, ProgressRecorder.Reason.Stop))
        assertEquals(2, attempts)
        assertEquals(1, watched)
    }

    @Test
    fun `parallel Default callers issue one watched write`() = runTest {
        val start = CompletableDeferred<Unit>()
        val attempts = AtomicInteger()
        val sink = object : ProgressSink {
            override suspend fun writeProgress(target: ProgressTarget, positionMs: Long, durationMs: Long) = Unit
            override suspend fun markWatched(target: ProgressTarget) { attempts.incrementAndGet() }
        }
        val recorder = ProgressRecorder(sink, FakeClock())
        recorder.begin(target)
        val results = List(32) {
            async(Dispatchers.Default) {
                start.await()
                recorder.record(0L, 0L, ProgressRecorder.Reason.Ended)
            }
        }
        start.complete(Unit)
        assertEquals(1, results.awaitAll().count { it })
        assertEquals(1, attempts.get())
    }

    @Test
    fun `a cleared engine timeline on stop retains duration and cannot resurrect a watched row`() = runTest {
        val sink = FakeSink()
        val recorder = ProgressRecorder(sink, FakeClock())
        recorder.begin(target)
        recorder.record(15_000, 150_000, ProgressRecorder.Reason.Tick)
        recorder.record(17_000, 0, ProgressRecorder.Reason.Stop)
        assertEquals(17_000L to 150_000L, sink.writes.last())
        recorder.record(0, 0, ProgressRecorder.Reason.Ended)
        assertFalse(recorder.record(17_000, 0, ProgressRecorder.Reason.Stop))
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
    fun `a two minute provider slate never marks a full episode watched`() = runTest {
        val sink = FakeSink()
        val recorder = ProgressRecorder(sink, FakeClock())
        recorder.begin(target, expectedRuntimeMinutes = 42)

        assertFalse(recorder.record(120_000L, 120_000L, ProgressRecorder.Reason.Ended))
        assertFalse(recorder.record(0L, 0L, ProgressRecorder.Reason.Stop))
        assertTrue(sink.writes.isEmpty())
        assertEquals(0, sink.watched)

        recorder.begin(target, expectedRuntimeMinutes = 2)
        assertTrue(recorder.record(120_000L, 120_000L, ProgressRecorder.Reason.Ended))
        assertEquals(1, sink.watched)
    }

    @Test
    fun `nothing is written before a title has begun`() = runTest {
        val sink = FakeSink()
        val recorder = ProgressRecorder(sink, FakeClock(0L))

        assertFalse(recorder.record(10_000L, 2_640_000L, ProgressRecorder.Reason.Stop))
    }
}
