package com.fourseveneightnine.tv.player

/**
 * Binds decoder/first-frame callbacks to the current open without depending on Player.Listener's
 * generation-less callback surface. A first frame is eligible only after this generation has
 * initialized a video decoder, which rejects the stale surface callback observed during rapid
 * Dolby Vision title replacement.
 */
internal class ExoPlaybackGenerationPolicy {
    private var generation = 0L
    private var decoderReadyGeneration: Long? = null
    private var firstFrameGeneration: Long? = null

    fun beginOpen(): Long {
        generation += 1
        decoderReadyGeneration = null
        firstFrameGeneration = null
        return generation
    }

    fun videoDecoderInitialized(callbackGeneration: Long): Boolean {
        if (callbackGeneration != generation) return false
        decoderReadyGeneration = callbackGeneration
        return true
    }

    fun renderedFrame(callbackGeneration: Long): ExoRenderedFrameDisposition {
        if (callbackGeneration != generation) return ExoRenderedFrameDisposition.STALE
        if (decoderReadyGeneration != generation) return ExoRenderedFrameDisposition.STALE
        if (firstFrameGeneration == generation) return ExoRenderedFrameDisposition.CURRENT_REPEAT
        firstFrameGeneration = generation
        return ExoRenderedFrameDisposition.FIRST
    }

    val hasRenderedFirstFrame: Boolean
        get() = firstFrameGeneration == generation

    val currentGeneration: Long
        get() = generation
}

/**
 * A repeated first-frame callback is expected after a seek/flush. It must remain distinct from a
 * stale title callback: the current repeat completes seek synchronization (including unmuting),
 * while a stale callback must not touch the replacement player at all.
 */
internal enum class ExoRenderedFrameDisposition {
    STALE,
    FIRST,
    CURRENT_REPEAT,
}

/** Owns the temporary mute used to keep a seek/flush from emitting old-position audio. */
internal class ExoSeekAudioLifecycle {
    var isMuted: Boolean = false
        private set

    fun begin() {
        isMuted = true
    }

    /** Returns true exactly once when a frame from the current title makes audio safe again. */
    fun renderedFrame(
        disposition: ExoRenderedFrameDisposition,
        belongsToLatestSeek: Boolean,
    ): Boolean {
        if (!isMuted || disposition == ExoRenderedFrameDisposition.STALE || !belongsToLatestSeek) {
            return false
        }
        isMuted = false
        return true
    }

    /** Returns true when cancellation/error teardown has a gain change to undo. */
    fun cancel(): Boolean {
        if (!isMuted) return false
        isMuted = false
        return true
    }
}

/** Fire OS vendor codecs retain the proven synchronous workaround; Android/Google TV hardware uses
 * Media3's lower-latency asynchronous adapter. */
internal object ExoMediaCodecQueueingPolicy {
    fun forceSynchronous(manufacturer: String, model: String): Boolean =
        manufacturer.equals("Amazon", ignoreCase = true) || model.startsWith("AFT", ignoreCase = true)
}

/**
 * Whether a LATE transport callback may still decide what is on the screen.
 *
 * Media3 delivers `onIsPlayingChanged` and `onPlayWhenReadyChanged` alongside the state change, and
 * the receiver answers them on a coroutine — so a `Playing`/`Paused` written there can land AFTER
 * the synchronous state handler has already recorded WHY playback ended. Live on the onn 4K Pro
 * (2026-08-28 13:13:04) a phone-issued Stop set `Stopped`, the trailing `isPlaying=false` overwrote
 * it with `Paused`, and because `Paused` means "a film is up, keep the picture" the screen went
 * black with nothing focusable on it. The `overlay.rescued` net had to put the home screen back.
 *
 * Ended, stopped, errored and idle are FINAL answers. Nothing arriving afterwards knows better.
 */
internal object ExoPhaseOverwritePolicy {
    fun allowsTransportPhase(current: ReceiverPlaybackPhase): Boolean = when (current) {
        is ReceiverPlaybackPhase.Playing,
        is ReceiverPlaybackPhase.Paused,
        is ReceiverPlaybackPhase.Buffering,
        is ReceiverPlaybackPhase.Opening,
        -> true

        is ReceiverPlaybackPhase.Idle,
        is ReceiverPlaybackPhase.Ended,
        is ReceiverPlaybackPhase.Stopped,
        is ReceiverPlaybackPhase.Error,
        -> false
    }
}
