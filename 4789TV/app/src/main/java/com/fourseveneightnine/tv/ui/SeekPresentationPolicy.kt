package com.fourseveneightnine.tv.ui

import kotlin.math.abs

/**
 * Keeps the receiver-owned timeline stable while a player seek catches up asynchronously.
 *
 * A seek is rendered optimistically so the D-pad answers on the frame it is pressed. Player
 * snapshots can then arrive out of order: the old playhead, a transient zero, and finally the
 * landed playhead. Accepting those literally makes the elapsed clock jump backwards or flash
 * 0:00. This policy holds the optimistic target until a snapshot is close enough to prove the
 * seek landed, while still imposing a bounded settle window so a failed seek cannot freeze the
 * timeline forever.
 */
internal object SeekPresentationPolicy {
    const val SETTLE_WINDOW_MILLIS = 2_500L
    const val LANDED_TOLERANCE_MILLIS = 4_000L

    data class Position(
        val millis: Long,
        val seekLanded: Boolean,
    )

    data class Chrome(
        val topRightPreviewVisible: Boolean,
        val bottomTimelineVisible: Boolean,
        val fullControlsVisible: Boolean,
    )

    /** Seeking reports intent without changing whether the viewer opened the full controls. */
    fun chromeDuringSeek(fullControlsWereVisible: Boolean): Chrome = Chrome(
        topRightPreviewVisible = true,
        bottomTimelineVisible = fullControlsWereVisible,
        fullControlsVisible = fullControlsWereVisible,
    )

    /** A delayed scrub belongs only to the media identity that created it. */
    fun mediaChanged(previousIdentity: String?, incomingIdentity: String?): Boolean =
        previousIdentity != incomingIdentity

    fun resolvePosition(
        currentMillis: Long,
        incomingMillis: Long,
        optimisticTargetMillis: Long?,
        elapsedSinceSeekMillis: Long,
    ): Position {
        val current = currentMillis.coerceAtLeast(0L)
        val incoming = incomingMillis.coerceAtLeast(0L)
        val target = optimisticTargetMillis?.coerceAtLeast(0L)

        // A transient zero on the same title is never useful after progress has been established.
        // Seeking deliberately to the beginning remains legal because that target is itself zero.
        if (incoming == 0L && current > 0L && target != 0L) {
            return Position(current, seekLanded = false)
        }

        if (target == null) return Position(incoming, seekLanded = true)

        val age = elapsedSinceSeekMillis.coerceAtLeast(0L)
        val landed = abs(incoming - target) <= LANDED_TOLERANCE_MILLIS
        if (landed) return Position(incoming, seekLanded = true)

        return if (age < SETTLE_WINDOW_MILLIS) {
            Position(current, seekLanded = false)
        } else {
            Position(incoming, seekLanded = true)
        }
    }

    /** A prepared title keeps its known runtime through transient pre-prepare/seek snapshots. */
    fun resolveDuration(currentMillis: Long, incomingMillis: Long): Long =
        if (incomingMillis > 0L) incomingMillis else currentMillis.coerceAtLeast(0L)
}
