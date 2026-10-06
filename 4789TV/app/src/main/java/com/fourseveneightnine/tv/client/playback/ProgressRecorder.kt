package com.fourseveneightnine.tv.client.playback

import com.fourseveneightnine.tv.client.data.library.LibraryRepository
import com.fourseveneightnine.tv.player.ReceiverPlaybackPhase
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Which row in `watch_progress` a run of playback belongs to. */
internal data class ProgressTarget(
    val canonicalId: String,
    val mediaType: String,
    val season: Int? = null,
    val episode: Int? = null,
)


/**
 * Where a progress write goes. One method per row `client-data/API-B.md` defines, so the cadence
 * above can be tested on a plain JVM — `LibraryRepository` needs a Room database and a `Context`,
 * and neither belongs in a test about a clock.
 */
internal interface ProgressSink {
    suspend fun writeProgress(target: ProgressTarget, positionMs: Long, durationMs: Long)
    suspend fun markWatched(target: ProgressTarget)
}

/** The real one. */
internal class LibraryProgressSink(private val library: LibraryRepository) : ProgressSink {
    override suspend fun writeProgress(target: ProgressTarget, positionMs: Long, durationMs: Long) {
        library.writeProgress(
            canonicalId = target.canonicalId,
            mediaType = target.mediaType,
            season = target.season,
            episode = target.episode,
            positionMs = positionMs,
            durationMs = durationMs,
        )
    }

    override suspend fun markWatched(target: ProgressTarget) {
        library.markWatched(target.canonicalId, target.mediaType, target.season, target.episode)
    }
}

/**
 * Writes Continue Watching while the box itself is playing (`client-data/API-B.md`, §Continue
 * Watching): every 15 s, and on pause, stop and end. At 95% the row is marked watched instead, so
 * a finished film leaves the row rather than sitting there at "2 minutes left" forever.
 *
 * **Local only.** A phone cast already writes its own progress on the phone, and `X4789.SetRecents`
 * mirrors it here; writing a local row underneath would win the merge (part B rule 1) and the
 * phone's own position would stop being the truth.
 *
 * The 15 s tick and the phase watch are separate on purpose. Reading the playhead is a socket
 * round trip on the mpv path, so it happens on the slow tick and at the three moments that matter,
 * never on a poll fast enough to feel pause.
 */
internal class ProgressRecorder(
    private val sink: ProgressSink,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val intervalMillis: Long = WRITE_INTERVAL_MILLIS,
) {

    constructor(
        library: LibraryRepository,
        nowMillis: () -> Long = System::currentTimeMillis,
        intervalMillis: Long = WRITE_INTERVAL_MILLIS,
    ) : this(LibraryProgressSink(library), nowMillis, intervalMillis)

    enum class Reason { Tick, Pause, Stop, Ended }

    private var target: ProgressTarget? = null
    // Nullable, not a sentinel. `now - Long.MIN_VALUE` overflows to a negative number, so a
    // sentinel start made the interval test true and the FIRST write of every title was dropped.
    private var lastWriteAt: Long? = null
    private var watchedWritten = false

    /** A new title. The cadence and the watched latch both start again. */
    fun begin(target: ProgressTarget) {
        this.target = target
        lastWriteAt = null
        watchedWritten = false
    }

    /**
     * One write, if one is due. Returns true when something was written, which is what the cadence
     * test asserts against a fake clock.
     *
     * A [Reason.Tick] inside the interval is dropped. Pause, stop and end are never dropped: they
     * are the moments a viewer expects the row to be right.
     */
    suspend fun record(positionMs: Long, durationMs: Long, reason: Reason): Boolean {
        val current = target ?: return false
        if (positionMs <= 0L && reason != Reason.Ended) return false
        val now = nowMillis()
        val since = lastWriteAt?.let { now - it }
        if (reason == Reason.Tick && since != null && since < intervalMillis) return false

        val finished = reason == Reason.Ended ||
            (durationMs > 0L && positionMs.toDouble() / durationMs >= WATCHED_FRACTION)
        lastWriteAt = now

        if (finished) {
            if (watchedWritten) return false
            watchedWritten = true
            sink.markWatched(current)
            return true
        }
        sink.writeProgress(current, positionMs, durationMs)
        return true
    }

    /**
     * Follows one local playback until it ends. Cancel the job to drop it; a new title begins a
     * new run rather than reusing this one, so the cadence never carries across a switch.
     */
    suspend fun follow(session: PlaybackSession, target: ProgressTarget) {
        begin(target)
        try {
            coroutineScope {
                launch {
                    while (true) {
                        delay(intervalMillis)
                        if (session.phase.value is ReceiverPlaybackPhase.Playing) {
                            write(session, Reason.Tick)
                        }
                    }
                }
                var started = false
                session.phase.collect { phase ->
                    when (phase) {
                        is ReceiverPlaybackPhase.Playing -> started = true
                        is ReceiverPlaybackPhase.Paused -> write(session, Reason.Pause)
                        is ReceiverPlaybackPhase.Ended -> {
                            write(session, Reason.Ended)
                            throw StopFollowing
                        }
                        is ReceiverPlaybackPhase.Stopped -> {
                            write(session, Reason.Stop)
                            throw StopFollowing
                        }
                        // media3 delivers STATE_IDLE synchronously after stop(), so Idle once a
                        // title has played is the stop a viewer saw — the same rule the plate uses.
                        is ReceiverPlaybackPhase.Idle -> if (started) {
                            write(session, Reason.Stop)
                            throw StopFollowing
                        }
                        else -> Unit
                    }
                }
            }
        } catch (_: StopFollowing) {
            // The run finished on its own terms. Nothing above needs to hear about it.
        }
    }

    private suspend fun write(session: PlaybackSession, reason: Reason) {
        if (session.origin.value != PlaybackSession.Origin.Local) return
        val snapshot = runCatching { session.refreshSnapshot() }.getOrNull() ?: return
        val wrote = runCatching {
            record(
                positionMs = (snapshot.positionSeconds * 1_000).toLong(),
                durationMs = (snapshot.durationSeconds * 1_000).toLong(),
                reason = reason,
            )
        }.getOrDefault(false)
        if (wrote) ReceiverDiagnostics.record("progress.write", "reason=$reason")
    }

    private object StopFollowing : CancellationException("playback finished")

    companion object {
        /** `client-data/API-B.md`: "Call it every 15 s while playing". */
        const val WRITE_INTERVAL_MILLIS: Long = 15_000L

        /** Part B rule 3: a row over 95% is finished and leaves Continue Watching. */
        const val WATCHED_FRACTION: Double = 0.95
    }
}
