package com.fourseveneightnine.tv.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.SeekParameters

/**
 * Collapses a burst of seeks into one player seek.
 *
 * A scrub — on the phone's scrubber or by holding the TV remote's D-pad — arrives as many small
 * seek commands. ExoPlayer honours each one literally: it flushes and re-initialises the audio
 * track every time. Live on the box, one 10:44 scrub produced six `exo.audio.trackInit` records
 * inside a single second, and each rebuild is a chance to click or gap. That is the "janky
 * scrubbing" report.
 *
 * The rule: the FIRST seek of a burst goes through immediately (a single ±10s press must feel
 * instant), and anything that lands inside [WINDOW_MILLIS] of the last issued seek is folded into
 * one deferred seek to the newest target. Intermediate targets are never sent — nobody wants to
 * hear the frames in between.
 */
@OptIn(UnstableApi::class)
internal object SeekCoalescingPolicy {
    /**
     * Media3 defaults to exact seeking, which decodes forward from a keyframe until it reaches the
     * requested timestamp. That extra decode can be visible on high-bitrate 4K files. A bounded
     * sync tolerance may return a nearby keyframe faster without letting a badly indexed file jump
     * to an arbitrarily distant scene. Initial resume, live streams, and smaller files remain exact.
     */
    const val SYNC_TOLERANCE_MILLIS = 2_000L
    private val fastParameters = SeekParameters(
        SYNC_TOLERANCE_MILLIS * 1_000,
        SYNC_TOLERANCE_MILLIS * 1_000,
    )

    fun parameters(isLive: Boolean, width: Int, height: Int): SeekParameters =
        if (!isLive && maxOf(width, height) >= UHD_LONG_EDGE_PIXELS) {
            fastParameters
        } else {
            SeekParameters.EXACT
        }

    /** A second seek waits for the first frame (or timeout) so callbacks never overlap. */
    fun canIssue(activeTargetMs: Long?): Boolean = activeTargetMs == null

    /**
     * Short enough that a deliberate second press still feels immediate, long enough to swallow a
     * held D-pad (~50-100ms repeats) and a phone scrubber's drag stream.
     */
    const val WINDOW_MILLIS = 250L

    private const val UHD_LONG_EDGE_PIXELS = 3_840

    sealed interface Decision {
        /** Send this target to the player now. */
        data object IssueNow : Decision

        /** Hold the target; send it in [delayMillis] unless a newer one replaces it first. */
        data class Defer(val delayMillis: Long) : Decision
    }

    /**
     * @param lastIssuedAtMillis when the last seek actually reached the player, or null if none has
     *   this session.
     */
    fun decide(
        lastIssuedAtMillis: Long?,
        nowMillis: Long,
        windowMillis: Long = WINDOW_MILLIS,
    ): Decision {
        if (lastIssuedAtMillis == null) return Decision.IssueNow
        val elapsed = nowMillis - lastIssuedAtMillis
        // A clock that appears to have gone backwards (or an equal timestamp) must not defer
        // forever; treat anything outside the window as a fresh burst.
        if (elapsed >= windowMillis || elapsed < 0) return Decision.IssueNow
        return Decision.Defer(windowMillis - elapsed)
    }
}
