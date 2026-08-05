package com.fourseveneightnine.tv.player

enum class EngineFailoverDecision { RetryWithMpv, Surface }

/**
 * Pure decision: when a playback failure on one engine should silently retry on the other.
 *
 * Only Exo→mpv exists — there is nothing ExoPlayer decodes that libmpv does not, and mpv on this
 * hardware already runs the reliable software path (hwdec=no baked into its config). One attempt
 * per open: a title that fails on BOTH engines surfaces the second failure to the phone instead
 * of ping-ponging. Codec failures are the whole point (no DTS/TrueHD/DV decoder on Fire OS);
 * fatal unknowns get one shot too because tonight's "Unexpected runtime error" class lives there.
 * A non-fatal codec-audio failure (video playing silently) also retries — silent video is not
 * playback. Source/network failures never retry: a dead stream is dead on every engine.
 */
object EngineFailoverPolicy {
    fun decide(
        category: String,
        fatal: Boolean,
        engine: ReceiverEngine,
        alreadyAttempted: Boolean,
        mediaKnown: Boolean,
        mpvHardwareDecode: Boolean = MpvReceiverStartupPolicy.HARDWARE_DECODE_ENABLED,
    ): EngineFailoverDecision = when {
        engine != ReceiverEngine.Exo -> EngineFailoverDecision.Surface
        alreadyAttempted -> EngineFailoverDecision.Surface
        !mediaKnown -> EngineFailoverDecision.Surface
        // On a box where libmpv has no hardware decoder (Fire OS: the vendor MediaCodec wrapper
        // deadlocks, so mpv is pinned to software), failing over trades a named error for a
        // 4-frames-per-second slideshow on anything above SD — measurably worse than the failure
        // it replaces. Surface the real reason instead and let the viewer hand off to an external
        // player, which keeps hardware decode.
        !mpvHardwareDecode -> EngineFailoverDecision.Surface
        category == PlaybackErrorTaxonomy.CODEC_AUDIO -> EngineFailoverDecision.RetryWithMpv
        !fatal -> EngineFailoverDecision.Surface
        category == PlaybackErrorTaxonomy.CODEC_VIDEO -> EngineFailoverDecision.RetryWithMpv
        category == PlaybackErrorTaxonomy.UNKNOWN -> EngineFailoverDecision.RetryWithMpv
        else -> EngineFailoverDecision.Surface
    }
}
