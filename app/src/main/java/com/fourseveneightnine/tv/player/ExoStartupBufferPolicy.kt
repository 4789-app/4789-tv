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
    /** Keep loading while below this. Unchanged — this is the resilience knob, not the latency one. */
    const val MIN_BUFFER_MS = 15_000

    const val MAX_BUFFER_MS = 60_000

    /** Runway required before the first frame is allowed out. Was 2500, then 700. Now 400 for sub-500ms startup. */
    const val BUFFER_FOR_PLAYBACK_MS = 400

    /** Runway required to resume after a stall. Was 5000. */
    const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 2_000

    /**
     * 60s of a 25Mbps remux is ~190MB, which this low-RAM Fire TV cannot hold. With the byte cap
     * governing, fat streams keep ~20s of buffer and lean 1080p streams get the full 60s.
     */
    const val TARGET_BUFFER_BYTES = 64 * 1024 * 1024
}
