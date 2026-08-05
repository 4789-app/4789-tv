package com.fourseveneightnine.tv.player

import com.fourseveneightnine.tv.protocol.ReceiverEvent
import com.fourseveneightnine.tv.protocol.ReceiverSnapshot
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * The small, Android-free portion of receiver state. It deliberately retains the actual
 * playback rate while paused so a later resume reports the right Kodi-compatible integer speed.
 */
internal data class MpvPlaybackState(
    val active: Boolean = false,
    val paused: Boolean = false,
    val playbackRate: Double = 1.0,
    val positionSeconds: Double = 0.0,
    val durationSeconds: Double = 0.0,
    val volume: Int = 100,
    val muted: Boolean = false,
) {
    fun snapshot(): ReceiverSnapshot =
        ReceiverSnapshot(
            active = active,
            positionSeconds = positionSeconds,
            durationSeconds = durationSeconds,
            speed = if (active && !paused) playbackRate.toReceiverSpeed() else 0,
            volume = volume,
            muted = muted,
        )
}

internal data class MpvStateChange(
    val state: MpvPlaybackState,
    val event: ReceiverEvent? = null,
)

/**
 * Converts libmpv property notifications into safe receiver state. Native callbacks can arrive
 * independently of controller requests, so this contains no Android or libmpv calls.
 */
internal object MpvSnapshotReducer {
    /** A replacement load has been accepted but has not emitted START_FILE yet. Clear every value
     * owned by the previous file so it cannot satisfy a new sender's startup acknowledgement. */
    fun opening(state: MpvPlaybackState): MpvStateChange =
        MpvStateChange(
            state.copy(
                active = false,
                paused = false,
                positionSeconds = 0.0,
                durationSeconds = 0.0,
            ),
        )

    fun active(state: MpvPlaybackState, active: Boolean): MpvStateChange {
        if (state.active == active) return state.unchanged()

        val next = state.copy(active = active)
        val event = when {
            !active -> ReceiverEvent.Stop(next.snapshot())
            next.paused -> ReceiverEvent.Pause(next.snapshot())
            else -> ReceiverEvent.Play(next.snapshot())
        }
        return MpvStateChange(next, event)
    }

    fun paused(state: MpvPlaybackState, paused: Boolean): MpvStateChange {
        if (state.paused == paused) return state.unchanged()

        val next = state.copy(paused = paused)
        val event = when {
            !next.active -> null
            paused -> ReceiverEvent.Pause(next.snapshot())
            else -> ReceiverEvent.Play(next.snapshot())
        }
        return MpvStateChange(next, event)
    }

    fun playbackRate(state: MpvPlaybackState, rate: Double): MpvStateChange {
        if (!rate.isFinite() || rate <= 0.0) return state.unchanged()

        val next = state.copy(playbackRate = rate)
        val event = if (
            next.active &&
            !next.paused &&
            state.snapshot().speed != next.snapshot().speed
        ) {
            ReceiverEvent.SpeedChanged(next.snapshot())
        } else {
            null
        }
        return MpvStateChange(next, event)
    }

    fun position(state: MpvPlaybackState, seconds: Double): MpvStateChange {
        if (!seconds.isFinite()) return state.unchanged()

        return MpvStateChange(state.copy(positionSeconds = seconds.coerceAtLeast(0.0)))
    }

    fun duration(state: MpvPlaybackState, seconds: Double): MpvStateChange {
        if (!seconds.isFinite()) return state.unchanged()

        return MpvStateChange(state.copy(durationSeconds = seconds.coerceAtLeast(0.0)))
    }

    fun volume(state: MpvPlaybackState, value: Double): MpvStateChange {
        if (!value.isFinite()) return state.unchanged()

        val volume = value.coerceIn(0.0, 100.0).roundToInt()
        if (state.volume == volume) return state.unchanged()

        val next = state.copy(volume = volume)
        return MpvStateChange(next, ReceiverEvent.VolumeChanged(next.snapshot()))
    }

    fun muted(state: MpvPlaybackState, muted: Boolean): MpvStateChange {
        if (state.muted == muted) return state.unchanged()

        val next = state.copy(muted = muted)
        return MpvStateChange(next, ReceiverEvent.VolumeChanged(next.snapshot()))
    }

    fun seek(state: MpvPlaybackState, offsetSeconds: Double): ReceiverEvent.Seek? =
        if (state.active && offsetSeconds.isFinite()) {
            ReceiverEvent.Seek(state.snapshot(), offsetSeconds)
        } else {
            null
        }

    private fun MpvPlaybackState.unchanged(): MpvStateChange = MpvStateChange(this)
}

/** A receiver is playable only after demux succeeded, not merely after START_FILE began loading. */
internal object MpvLoadStatePolicy {
    fun isPlayable(idleActive: Boolean, fileLoaded: Boolean): Boolean = !idleActive && fileLoaded
}

/**
 * Lock-free snapshot holder shared by serialized controller requests and libmpv callback threads.
 */
internal class MpvSnapshotStore(initialState: MpvPlaybackState = MpvPlaybackState()) {
    private val state = AtomicReference(initialState)

    fun snapshot(): ReceiverSnapshot = state.get().snapshot()

    fun update(reducer: (MpvPlaybackState) -> MpvStateChange): ReceiverEvent? {
        while (true) {
            val previous = state.get()
            val change = reducer(previous)
            if (change.state == previous) return null
            if (state.compareAndSet(previous, change.state)) return change.event
        }
    }

    fun seekEvent(offsetSeconds: Double): ReceiverEvent.Seek? =
        MpvSnapshotReducer.seek(state.get(), offsetSeconds)
}

private fun Double.toReceiverSpeed(): Int =
    coerceIn(1.0, 32.0).roundToInt()
