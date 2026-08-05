package com.fourseveneightnine.tv.player

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
internal object SeekCoalescingPolicy {
    /**
     * Short enough that a deliberate second press still feels immediate, long enough to swallow a
     * held D-pad (~50-100ms repeats) and a phone scrubber's drag stream.
     */
    const val WINDOW_MILLIS = 250L

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
