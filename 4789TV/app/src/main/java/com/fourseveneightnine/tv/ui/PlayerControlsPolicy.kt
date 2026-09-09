package com.fourseveneightnine.tv.ui

import com.fourseveneightnine.tv.R
import java.util.Locale

/** How the decoded picture is fitted to the panel. Mirrors Just Player's resize cycle. */
internal enum class VideoResizeMode {
    /** Letterbox: whole frame visible, black bars where the ratios differ. */
    Fit,

    /** Zoom until the panel is filled; the overflowing edges are cropped. No distortion. */
    Crop,

    /** Stretch to the panel. Distorts — offered because some viewers want it anyway. */
    Stretch,
    ;

    fun next(): VideoResizeMode = entries[(ordinal + 1) % entries.size]

    /** What this mode is called on the television. Named, never a glyph. */
    fun labelRes(): Int = when (this) {
        Fit -> R.string.controls_fit
        Crop -> R.string.controls_crop
        Stretch -> R.string.controls_stretch
    }
}

/**
 * Pure decisions behind the on-TV player controls. Kept out of the view so remote behaviour is
 * testable without a television: a D-pad scrub is the one interaction nobody can eyeball reliably.
 */
internal object PlayerControlsPolicy {
    /** Controls fade after this much inactivity, matching the receiver's existing toast timing. */
    const val AUTO_HIDE_MILLIS = 4_500L

    /** How often the bar re-reads the playhead while it is on screen. */
    const val POSITION_POLL_MILLIS = 500L

    /** One tap = one comfortable jump. Holding is a different gesture entirely (see below). */
    const val BASE_SCRUB_MILLIS = 10_000L

    /**
     * How often a HELD D-pad is allowed to move the playhead. Android repeats a held key every
     * ~50ms, and the first version applied a whole step per repeat — 10s of film per repeat is
     * ~200s of film per second of holding, which is why the bar appeared to teleport before the eye
     * could follow it.
     */
    const val SCRUB_TICK_MILLIS = 100L

    val SPEEDS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

    /**
     * Seconds of film per second of holding. A rate, not a step — so the picture moves at a speed a
     * viewer can read, and the ramp is felt as acceleration rather than as sudden jumps.
     *
     * 10x for the first second (a gentle nudge, matching the single-press feel), 30x while looking
     * for a scene, 90x only once the hold is clearly a long journey across the film.
     */
    const val STEP_JUMP_MILLIS = 600_000L // 10 minutes step jump on D-Pad UP/DOWN

    fun scrubRate(holdMillis: Long): Int = when {
        holdMillis >= 6_000 -> 600 // 10 minutes of film per second of holding
        holdMillis >= 3_000 -> 300 // 5 minutes of film per second of holding
        holdMillis >= 1_500 -> 60  // 1 minute of film per second of holding
        else -> 10                 // 10 seconds of film per second of holding (fine control)
    }

    /**
     * How far this tick moves the playhead.
     *
     * @param holdMillis how long the key has been down — the ramp reads from this, NOT from the
     *   repeat counter, so a fast-repeating remote and a slow one scrub at the same speed.
     * @param sinceLastTickMillis real time since the last applied tick, clamped so a dropped frame
     *   or a stalled main thread cannot cash in a huge jump on the next one.
     */
    fun scrubTickMillis(holdMillis: Long, sinceLastTickMillis: Long): Long {
        val elapsed = sinceLastTickMillis.coerceIn(0L, MAX_TICK_CATCHUP_MILLIS)
        return scrubRate(holdMillis) * elapsed
    }

    /** A stall must not become a leap: catch-up is capped at a quarter second of holding. */
    const val MAX_TICK_CATCHUP_MILLIS = 250L

    /**
     * The playhead between player polls.
     *
     * A snapshot is a hop to the player thread — a socket round trip on the mpv path — so it is
     * read on a slow cadence, and painting that reading raw makes the clock advance in visible
     * steps. The bar carries the last reading forward itself at the known rate and lets the next
     * poll correct it.
     *
     * @param rate seconds of film per second of wall clock. Zero while paused, so a paused film's
     *   clock never drifts forward, which is the whole reason this takes a rate rather than a flag.
     * @param durationMillis the end of the media, or 0 when it is not known yet (live, or before
     *   the first frame) — in which case there is nothing to clamp against.
     */
    fun interpolatedPosition(
        sampleMillis: Long,
        sampledAtUptime: Long,
        nowUptime: Long,
        rate: Float,
        durationMillis: Long,
    ): Long {
        if (rate <= 0f || sampledAtUptime <= 0L) return sampleMillis.coerceAtLeast(0L)
        val elapsed = (nowUptime - sampledAtUptime).coerceAtLeast(0L)
        val advanced = sampleMillis + (elapsed * rate).toLong()
        val upper = if (durationMillis > 0) durationMillis else Long.MAX_VALUE
        return advanced.coerceIn(0L, upper)
    }

    /**
     * Chapter boundaries as 0-1 fractions of the runtime, for the seek bar's tick marks.
     *
     * The phone sends seconds, because it does not know the runtime the player will report — and
     * often neither does the phone's metadata source. Anything at or beyond the end, anything at
     * the very start, and everything sent before a duration is known is dropped rather than drawn
     * in the wrong place: a tick at 0:00 marks nothing, and a tick past the end is a lie.
     */
    fun chapterFractions(chapterSeconds: List<Double>, durationSeconds: Double): List<Float> {
        if (chapterSeconds.isEmpty() || durationSeconds <= 0.0) return emptyList()
        return chapterSeconds
            .map { (it / durationSeconds).toFloat() }
            .filter { it > 0f && it < 1f }
            .distinct()
    }

    /** Where a scrub lands, never outside the media. Unknown duration = no forward bound. */
    fun scrubTarget(currentMillis: Long, stepMillis: Long, durationMillis: Long): Long {
        val target = currentMillis + stepMillis
        val upper = if (durationMillis > 0) durationMillis else Long.MAX_VALUE
        return target.coerceIn(0L, upper)
    }

    /**
     * The provider segment is DROPPED when the caller does not know the provider. It used to
     * default to "Real-Debrid", so every stream the box played — an add-on link, a local file, a
     * public URL — was branded with a debrid service it had never touched. A badge that names the
     * wrong source is worse than a badge that names none.
     */
    fun formatQualityBadge(d: com.fourseveneightnine.tv.player.PlaybackDiagnostics, providerName: String? = null): String {
        if (!d.active || d.width == 0 || d.height == 0) return ""
        val resLabel = when {
            d.width >= 3840 || d.height >= 2160 -> "4K"
            d.width >= 2560 || d.height >= 1440 -> "1440p"
            d.width >= 1920 || d.height >= 1080 -> "1080p"
            d.width >= 1280 || d.height >= 720 -> "720p"
            else -> "${d.height}p"
        }

        val hdrLabel = when {
            d.dolbyVisionProfile > 0 -> "DV"
            d.hdrTransfer.contains("ST2084", ignoreCase = true) || d.hdrTransfer.contains("PQ", ignoreCase = true) -> "HDR10"
            d.hdrTransfer.contains("HLG", ignoreCase = true) -> "HLG"
            else -> ""
        }

        val codecLabel = when {
            d.videoCodec.contains("hevc", ignoreCase = true) || d.videoCodec.contains("h265", ignoreCase = true) -> "HEVC"
            d.videoCodec.contains("avc", ignoreCase = true) || d.videoCodec.contains("h264", ignoreCase = true) -> "H.264"
            d.videoCodec.contains("av01", ignoreCase = true) || d.videoCodec.contains("av1", ignoreCase = true) -> "AV1"
            d.videoCodec.contains("vp9", ignoreCase = true) -> "VP9"
            d.videoCodec.isNotBlank() -> d.videoCodec.take(6).uppercase()
            else -> "AVC"
        }

        val fullRes = if (hdrLabel.isNotBlank()) "$resLabel $hdrLabel" else resLabel

        return listOfNotNull(
            providerName?.takeIf { it.isNotBlank() },
            fullRes,
            codecLabel,
        ).joinToString("  ·  ")
    }

    /** 0-1000 for the progress bar; 0 when the duration is not known yet (live, or pre-prepare). */
    fun progressPermille(positionMillis: Long, durationMillis: Long): Int {
        if (durationMillis <= 0) return 0
        return ((positionMillis.coerceIn(0, durationMillis) * 1000) / durationMillis).toInt()
    }

    /** `12:34` under an hour, `1:23:45` over it — the form every player on a TV uses. */
    fun formatTime(millis: Long): String {
        val total = (millis.coerceAtLeast(0L) + 500) / 1000
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    /** `1x` reads better than `1.0x`; the quarter steps keep their decimals. */
    fun formatSpeed(speed: Float): String {
        val number = if (speed == speed.toInt().toFloat()) {
            speed.toInt().toString()
        } else {
            String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.')
        }
        return "${number}x"
    }

    /** `+2:30` / `-0:45`, for the scrub HUD. The sign is the whole point, so it is never dropped. */
    fun formatSignedDelta(millis: Long): String {
        val sign = if (millis < 0) "-" else "+"
        return sign + formatTime(kotlin.math.abs(millis))
    }

    /**
     * What a track row says on the television. Language first because that is what a viewer is
     * choosing by; the codec and channel count settle the "which 5.1 is which" question that a
     * bare "English" list cannot.
     */
    fun trackLabel(
        language: String,
        name: String,
        codec: String?,
        channels: Int?,
        fallback: String,
    ): String {
        val parts = mutableListOf<String>()
        val readableLanguage = language.trim().takeIf(String::isNotEmpty)?.uppercase(Locale.US)
        if (readableLanguage != null) parts += readableLanguage

        val isReleaseFilename = name.contains(".2160p", ignoreCase = true) ||
            name.contains(".1080p", ignoreCase = true) ||
            name.contains(".BluRay", ignoreCase = true) ||
            name.contains(".REMUX", ignoreCase = true) ||
            name.contains(".WEB", ignoreCase = true) ||
            name.contains(".x264", ignoreCase = true) ||
            name.contains(".x265", ignoreCase = true) ||
            name.contains(".HEVC", ignoreCase = true)

        val cleanDescriptor = name.trim().takeIf {
            it.isNotEmpty() && !it.equals(language, true) && !isReleaseFilename
        }
        if (cleanDescriptor != null) parts += cleanDescriptor

        val cleanCodec = when {
            codec.orEmpty().contains("dts-hd", ignoreCase = true) || codec.orEmpty().contains("dtshd", ignoreCase = true) -> "DTS-HD"
            codec.orEmpty().contains("dts", ignoreCase = true) -> "DTS"
            codec.orEmpty().contains("truehd", ignoreCase = true) -> "TRUEHD"
            codec.orEmpty().contains("eac3", ignoreCase = true) || codec.orEmpty().contains("ec-3", ignoreCase = true) -> "E-AC3"
            codec.orEmpty().contains("ac3", ignoreCase = true) || codec.orEmpty().contains("ac-3", ignoreCase = true) -> "AC3"
            codec.orEmpty().contains("flac", ignoreCase = true) -> "FLAC"
            codec.orEmpty().contains("aac", ignoreCase = true) -> "AAC"
            codec != null -> codec.substringAfter('/').uppercase(Locale.US).takeIf(String::isNotEmpty)
            else -> null
        }
        if (cleanCodec != null) parts += cleanCodec

        channels?.takeIf { it > 0 }?.let { ch ->
            val chLabel = when (ch) {
                8 -> "7.1ch"
                6 -> "5.1ch"
                2 -> "2.0ch"
                1 -> "Mono"
                else -> "${ch}ch"
            }
            parts += chLabel
        }

        return parts.joinToString(" · ").ifEmpty { fallback }
    }

    /** Cycles the list; an unrecognised speed (a phone-sent integer rate) resets to 1x. */
    fun nextSpeed(current: Float): Float {
        val index = SPEEDS.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
        return SPEEDS[(if (index < 0) SPEEDS.indexOf(1.0f) else index + 1) % SPEEDS.size]
    }
}
