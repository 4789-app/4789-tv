package com.fourseveneightnine.tv.player

/**
 * Tells a decoder/render pipeline that has stopped presenting pictures apart from a stream that has
 * stopped arriving.
 *
 * They look identical from the outside — the player sits in `STATE_BUFFERING` and the position does
 * not move — and the receiver used to report both the same way, so the phone applied the only
 * recovery it knows: stop and re-open. For a stalled decoder, a live-player retry can retain old
 * decoder ownership while creating new ownership, so it is not a safe recovery boundary.
 *
 * The two are trivially separable, and nothing before this asked the question: a stream that has
 * stopped arriving has an EMPTY buffer, while a decoder that cannot draw has a FULL one. On
 * *Black Widow* the buffer reached eighty seconds while the playhead never moved at all.
 */
internal object DecoderStarvationPolicy {
    /**
     * Buffered runway above which "we are waiting for the network" stops being a possible
     * explanation. Ten seconds is far more than any rebuffer needs to resume.
     */
    const val STARVED_BUFFER_FLOOR_MS = 10_000L

    /**
     * How long the playhead may stand still, with that runway available, before this is called.
     *
     * Long enough to sit through a slow seek settling on a 4K remux, short enough to beat the
     * phone's own eighteen-second stall watchdog to the diagnosis — so the viewer is told what
     * actually broke instead of watching a restart loop that cannot succeed.
     */
    const val STARVED_FOR_MS = 12_000L

    /** How far the position must move for playback to count as progressing at all. */
    private const val PROGRESS_EPSILON_MS = 250L

    /**
     * @param bufferedAheadMs runway held beyond the playhead.
     * @param positionMs the playhead now.
     * @param lastProgressPositionMs the playhead when it last moved.
     * @param millisSinceProgress how long ago that was.
     * @return true when the decoder is holding a full buffer it cannot turn into pictures.
     */
    fun isStarved(
        playWhenReady: Boolean,
        bufferedAheadMs: Long,
        positionMs: Long,
        lastProgressPositionMs: Long,
        millisSinceProgress: Long,
    ): Boolean {
        if (!playWhenReady) return false
        if (positionMs - lastProgressPositionMs >= PROGRESS_EPSILON_MS) return false
        if (bufferedAheadMs < STARVED_BUFFER_FLOOR_MS) return false
        return millisSinceProgress >= STARVED_FOR_MS
    }
}

/** The one bounded action available for a full-buffer, frozen-playhead decoder stall. */
internal enum class DecoderStallAction {
    NONE,
    RECOVER,
    SURFACE_FAILURE,
}

/** Correlates Media3's generation-less seek callbacks to the latest command we issued. */
internal object SeekCallbackAcceptancePolicy {
    fun acceptsDiscontinuity(activeTargetMs: Long?, newPositionMs: Long, toleranceMs: Long): Boolean =
        activeTargetMs != null && kotlin.math.abs(newPositionMs - activeTargetMs) <= toleranceMs

    fun acceptsRenderedFrame(
        activeTargetMs: Long?,
        reportedPositionMs: Long?,
        currentPositionMs: Long?,
        toleranceMs: Long,
    ): Boolean = when {
        activeTargetMs != null -> {
            val positions = listOfNotNull(reportedPositionMs, currentPositionMs)
            positions.isNotEmpty() && positions.all {
                kotlin.math.abs(it - activeTargetMs) <= toleranceMs
            }
        }
        // Once the latest seek has settled, an old analytics callback is safe only when it agrees
        // with the player that is currently on screen. A missing analytics position can degrade to
        // the current player position; a missing player position cannot prove callback ownership.
        reportedPositionMs == null -> currentPositionMs != null
        currentPositionMs == null -> false
        else -> kotlin.math.abs(reportedPositionMs - currentPositionMs) <= toleranceMs
    }
}

/** A Player.Listener callback may mutate receiver state only for its installed player instance. */
internal object PlayerCallbackOwnershipPolicy {
    fun accepts(
        installedPlayerGeneration: Long,
        activePlayerGeneration: Long,
        isCurrentPlayerInstance: Boolean,
    ): Boolean = isCurrentPlayerInstance && installedPlayerGeneration == activePlayerGeneration
}

/** Subtitle state retained only across an internal replacement of the same cast. */
internal sealed interface RecoverySubtitleSelection {
    data object NoTextTracks : RecoverySubtitleSelection
    data object ExplicitlyOff : RecoverySubtitleSelection
    data class Index(val value: Int) : RecoverySubtitleSelection
}

internal object RecoverySubtitleSelectionPolicy {
    fun capture(textTrackCount: Int, selectedIndex: Int?): RecoverySubtitleSelection = when {
        textTrackCount <= 0 -> RecoverySubtitleSelection.NoTextTracks
        selectedIndex == null -> RecoverySubtitleSelection.ExplicitlyOff
        else -> RecoverySubtitleSelection.Index(selectedIndex)
    }

    fun isReady(selection: RecoverySubtitleSelection, textTrackCount: Int): Boolean = when (selection) {
        RecoverySubtitleSelection.NoTextTracks -> true
        RecoverySubtitleSelection.ExplicitlyOff -> textTrackCount > 0
        is RecoverySubtitleSelection.Index -> textTrackCount > selection.value
    }

    fun captureForRecovery(
        textRendererDisabled: Boolean,
        pendingRecoverySelection: RecoverySubtitleSelection?,
        pendingRestoreIndex: Int?,
        textTrackCount: Int,
        selectedIndex: Int?,
    ): RecoverySubtitleSelection = when {
        pendingRecoverySelection != null -> pendingRecoverySelection
        pendingRestoreIndex != null -> RecoverySubtitleSelection.Index(pendingRestoreIndex)
        textRendererDisabled -> RecoverySubtitleSelection.ExplicitlyOff
        else -> capture(textTrackCount, selectedIndex)
    }
}

/** Every user-visible stream replacement gets a fresh decoder/surface ownership boundary. */
internal object PublicStreamReplacementPolicy {
    fun requiresFreshPlayer(hasCurrentPlayer: Boolean): Boolean = hasCurrentPlayer
}

/** FFmpeg remains an audio fallback only; software 4K video must never be selected. */
internal object FfmpegExtensionPolicy {
    const val ENABLE_AUDIO_RENDERER = true
    const val ENABLE_VIDEO_RENDERER = false
}

/**
 * Pure state for decoder-stall recovery. The controller owns Media3 lifecycle work; this policy
 * owns the observations that make recovery safe and the once-per-public-open budget.
 */
internal class DecoderStallRecoveryPolicy {
    private var firstFrameRendered = false
    private var seekPending = false
    private var seekSettling = false
    private var seekSettlingSinceMs = 0L
    private var recoveryUsed = false
    private var terminalFailureReported = false
    private var lastProgressPositionMs = 0L
    private var lastProgressWallMs = 0L

    /** A genuine public open starts a new user-visible playback generation and budget. */
    fun beginPublicOpen(positionMs: Long, nowMs: Long) {
        recoveryUsed = false
        terminalFailureReported = false
        beginGeneration(positionMs, nowMs)
    }

    /** A recovery creates a new player generation but deliberately retains its consumed budget. */
    fun beginRecoveryOpen(positionMs: Long, nowMs: Long) = beginGeneration(positionMs, nowMs)

    /** Decoder initialization can fail before a first frame, but consumes the same bounded budget. */
    fun videoDecoderInitializationFailed(): DecoderStallAction = nextRecoveryAction()

    /** Reaching end-of-stream without ever presenting a picture is also a decoder-output failure. */
    fun videoEndedWithoutFrame(): DecoderStallAction = nextRecoveryAction()

    private fun beginGeneration(positionMs: Long, nowMs: Long) {
        firstFrameRendered = false
        seekPending = false
        seekSettling = false
        seekSettlingSinceMs = 0L
        rebase(positionMs, nowMs)
    }

    /** A coalesced seek is still user intent; it cannot race recovery before it is issued. */
    fun seekSubmitted() {
        seekPending = true
    }

    /** Suppress evaluation from an issued seek until it settles or its bounded grace expires. */
    fun seekIssued(positionMs: Long, nowMs: Long) {
        seekPending = false
        seekSettling = true
        seekSettlingSinceMs = nowMs
        rebase(positionMs, nowMs)
    }

    /** Position discontinuity is the authoritative seek baseline, including backward seeks. */
    fun seekDiscontinuity(positionMs: Long, nowMs: Long) {
        seekPending = false
        seekSettling = true
        seekSettlingSinceMs = nowMs
        rebase(positionMs, nowMs)
    }

    /** Explicit play intent is a progress boundary even if no watchdog sample occurred between. */
    fun playWhenReadyChanged(positionMs: Long, nowMs: Long) {
        rebase(positionMs, nowMs)
        if (seekSettling) seekSettlingSinceMs = nowMs
    }

    /** A rendered frame is the boundary at which a new seek position becomes observable. */
    fun frameRendered(positionMs: Long, nowMs: Long) {
        firstFrameRendered = true
        seekPending = false
        seekSettling = false
        seekSettlingSinceMs = 0L
        rebase(positionMs, nowMs)
    }

    fun evaluate(
        playWhenReady: Boolean,
        bufferedAheadMs: Long,
        positionMs: Long,
        nowMs: Long,
    ): DecoderStallAction {
        if (terminalFailureReported) {
            return DecoderStallAction.NONE
        }
        if (!playWhenReady) {
            rebase(positionMs, nowMs)
            if (seekSettling) seekSettlingSinceMs = nowMs
            return DecoderStallAction.NONE
        }
        if (seekPending) return DecoderStallAction.NONE
        if (seekSettling) {
            if (positionMs - lastProgressPositionMs >= PROGRESS_EPSILON_MS) {
                seekSettling = false
                seekSettlingSinceMs = 0L
                rebase(positionMs, nowMs)
                return DecoderStallAction.NONE
            }
            if (nowMs - seekSettlingSinceMs < DecoderStarvationPolicy.STARVED_FOR_MS) {
                return DecoderStallAction.NONE
            }
            // Some vendor paths do not emit either callback for a no-op or failed seek. The
            // current baseline was set at issue/discontinuity, so after the normal grace period
            // a full buffer plus frozen playhead is eligible for the ordinary bounded recovery.
            seekSettling = false
            seekSettlingSinceMs = 0L
        }
        if (!firstFrameRendered) {
            // A player clock that advances while no picture has ever reached the Surface is not a
            // slow network. Keep the initial/open position as the baseline so this cannot be
            // hidden by ordinary progress rebasing; a valid decoder normally presents a frame
            // before playback is allowed to advance this far.
            if (positionMs - lastProgressPositionMs >= FIRST_FRAME_PROGRESS_LIMIT_MS) {
                return nextRecoveryAction()
            }
            if (DecoderStarvationPolicy.isStarved(
                    playWhenReady = playWhenReady,
                    bufferedAheadMs = bufferedAheadMs,
                    positionMs = positionMs,
                    lastProgressPositionMs = lastProgressPositionMs,
                    millisSinceProgress = nowMs - lastProgressWallMs,
                )
            ) {
                return nextRecoveryAction()
            }
            return DecoderStallAction.NONE
        }
        if (positionMs - lastProgressPositionMs >= PROGRESS_EPSILON_MS) {
            rebase(positionMs, nowMs)
            return DecoderStallAction.NONE
        }
        if (!DecoderStarvationPolicy.isStarved(
                playWhenReady = playWhenReady,
                bufferedAheadMs = bufferedAheadMs,
                positionMs = positionMs,
                lastProgressPositionMs = lastProgressPositionMs,
                millisSinceProgress = nowMs - lastProgressWallMs,
            )
        ) {
            return DecoderStallAction.NONE
        }
        return nextRecoveryAction()
    }

    private fun nextRecoveryAction(): DecoderStallAction {
        if (terminalFailureReported) return DecoderStallAction.NONE
        if (!recoveryUsed) {
            recoveryUsed = true
            return DecoderStallAction.RECOVER
        }
        terminalFailureReported = true
        return DecoderStallAction.SURFACE_FAILURE
    }

    private fun rebase(positionMs: Long, nowMs: Long) {
        lastProgressPositionMs = positionMs
        lastProgressWallMs = nowMs
    }

    private companion object {
        const val PROGRESS_EPSILON_MS = 250L
        const val FIRST_FRAME_PROGRESS_LIMIT_MS = 2_000L
    }
}

/**
 * Keeps the audio track a cast was playing across an automatic re-open of that same cast.
 *
 * On *Black Widow* the selected track changed on its own between generations of ONE cast:
 * E-AC3 7.1 (`encoding=6`, mask 6396, a 1.15MB sink), then AC3 STEREO (`encoding=5`, mask 12, a
 * 12,000-byte sink), then TrueHD (`encoding=14`, mask 6396, a 4.59MB sink). Nobody asked for any of
 * that. The two generations that got the stereo track are exactly the two whose playhead never
 * moved.
 *
 * Whatever decides the tie on a re-prepare, it is not the viewer, and a title that started in 7.1
 * should not quietly land in stereo because it was re-opened. This mirrors the restore already done
 * for subtitles.
 */
internal object AudioTrackContinuityPolicy {
    /**
     * Should [previousIndex], played by the outgoing generation, be re-selected on the incoming one?
     *
     * Only within the SAME cast: a different title's track numbering means nothing here. And only
     * when the incoming title actually has that many audio tracks, so a restore can never select a
     * track that does not exist.
     */
    fun restoreIndex(
        previousIndex: Int?,
        previousCastId: String?,
        incomingCastId: String?,
        incomingAudioTrackCount: Int,
    ): Int? {
        if (previousIndex == null || previousIndex < 0) return null
        if (previousCastId == null || incomingCastId == null) return null
        if (previousCastId != incomingCastId) return null
        if (previousIndex >= incomingAudioTrackCount) return null
        return previousIndex
    }
}
