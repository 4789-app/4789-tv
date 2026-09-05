package com.fourseveneightnine.tv.player

/**
 * How much of the stream ExoPlayer holds before it will show a picture, and how much it keeps
 * ahead of the playhead afterwards.
 *
 * These are two different questions and the receiver used to answer both with the same
 * conservatism. `bufferForPlaybackMs` was 2500, so every cast waited two and a half seconds of
 * *decoded runway* on top of connect + probe + codec init: measured start-to-first-frame on the
 * Fire TV was 4.5s at 1080p and 5.9s at 4K (`exo.open.surface` → `exo.firstFrameRendered`).
 *
 * Starting earlier does not make the stream more fragile, because the loader never stops: the
 * buffer keeps filling behind the picture up to [MAX_BUFFER_MS] / [TARGET_BUFFER_BYTES], and
 * `minBufferMs` — the level below which the loader is told to keep loading — is deliberately
 * unchanged at 15s. The only thing that shrinks is how long the viewer stares at a black screen.
 *
 * The rebuffer figure stays higher than the cold-start one on purpose: a mid-title stall means the
 * source has already proven it cannot keep up, so resuming on a thin buffer would just stall again.
 */
internal object ExoStartupBufferPolicy {
    /** Keep loader active while below 30s buffer. Absorbs Debrid HTTP network dips. */
    const val MIN_BUFFER_MS = 30_000

    /** Time ceiling only; the byte cap below is normally reached first for high-bitrate remuxes. */
    const val MAX_BUFFER_MS = 300_000

    /** Runway required before the first frame is allowed out. Sub-1s cold startup. */
    const val BUFFER_FOR_PLAYBACK_MS = 1_000

    /** Re-buffer cushion: resume with enough runway to avoid an immediate second stall. */
    const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 6_000

    /**
     * A 96MiB buffer is only about 11–12 seconds for a 72GB/140-minute remux, not minutes. Keep it
     * on constrained devices; onn-class boxes exposing a >=384MB large heap can safely spend an
     * additional 32MiB on burst absorption without applying that memory cost to Fire TV hardware.
     */
    const val CONSTRAINED_TARGET_BUFFER_BYTES = 96 * 1024 * 1024
    const val LARGE_HEAP_TARGET_BUFFER_BYTES = 128 * 1024 * 1024
    const val LARGE_HEAP_THRESHOLD_MB = 384

    fun targetBufferBytes(largeMemoryClassMb: Int): Int =
        if (largeMemoryClassMb >= LARGE_HEAP_THRESHOLD_MB) {
            LARGE_HEAP_TARGET_BUFFER_BYTES
        } else {
            CONSTRAINED_TARGET_BUFFER_BYTES
        }
}
