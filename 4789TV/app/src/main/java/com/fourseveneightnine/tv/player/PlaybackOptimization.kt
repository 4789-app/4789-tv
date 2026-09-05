package com.fourseveneightnine.tv.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import java.io.File
import java.util.Locale

data class PlaybackDiagnostics(
    val active: Boolean = false,
    val videoCodec: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val framesPerSecond: Double = 0.0,
    val durationSeconds: Double = 0.0,
    val hardwareDecoder: String = "",
    val hdrTransfer: String = "",
    val outputTransfer: String = "",
    val outputPrimaries: String = "",
    val dolbyVisionProfile: Int = 0,
    val colorMode: String = "",
    val audioCodec: String = "",
    val audioChannels: Int = 0,
    val droppedFrames: Int = 0,
    val decoderDroppedFrames: Int = 0,
    val cacheSeconds: Double = 0.0,
    val pausedForCache: Boolean = false,
    val avSyncSeconds: Double = 0.0,
    val bufferTargetSeconds: Int = AdaptiveBufferPolicy.INITIAL_SECONDS,
)

/**
 * Android's libmpv GPU/EGL path cannot request a native Dolby Vision swapchain. Profile 5 has no
 * HDR10-compatible base layer, so presenting it without libplacebo's reshape produces the familiar
 * pink/green picture. Keep gpu-next/libplacebo in the chain, use the copy hardware path so frame
 * metadata remains available to the renderer, then tone-map to a standard BT.709/BT.1886 surface
 * Android can display correctly. This is a color-safe fallback, not a false native-DV claim.
 */
internal object DolbyVisionColorPolicy {
    const val STANDARD_MODE = "Source colorspace"
    const val SAFE_MODE = "Dolby Vision → color-safe SDR"

    data class Settings(
        val hardwareDecoder: String,
        val colorspaceHint: String,
        val targetPrimaries: String,
        val targetTransfer: String,
        val toneMapping: String,
        val gamutMapping: String,
        val label: String,
    )

    fun settings(profile: Int?): Settings = if (profile != null && profile > 0) {
        Settings(
            hardwareDecoder = "mediacodec-copy",
            colorspaceHint = "no",
            targetPrimaries = "bt.709",
            targetTransfer = "bt.1886",
            toneMapping = "bt.2446a",
            gamutMapping = "perceptual",
            label = SAFE_MODE,
        )
    } else {
        Settings(
            hardwareDecoder = "mediacodec,mediacodec-copy",
            colorspaceHint = "auto",
            targetPrimaries = "auto",
            targetTransfer = "auto",
            toneMapping = "auto",
            gamutMapping = "auto",
            label = STANDARD_MODE,
        )
    }
}

internal object AdaptiveBufferPolicy {
    const val INITIAL_SECONDS = 15
    const val FOUR_K_SECONDS = 30
    const val RECOVERY_SECONDS = 60

    fun targetSeconds(
        currentSeconds: Int,
        width: Int,
        height: Int,
        videoBitsPerSecond: Double,
        pausedForCache: Boolean,
    ): Int = when {
        pausedForCache -> RECOVERY_SECONDS
        width >= 3_840 || height >= 2_160 || videoBitsPerSecond >= 25_000_000.0 ->
            maxOf(currentSeconds, FOUR_K_SECONDS)
        else -> maxOf(currentSeconds, INITIAL_SECONDS)
    }
}

/** One entry from `Display.getSupportedModes()`, without the Android type, so this is testable. */
internal data class DisplayModeInfo(
    val modeId: Int,
    val width: Int,
    val height: Int,
    val refreshRate: Float,
)

internal object SurfaceFrameRatePolicy {
    fun validRate(framesPerSecond: Double): Float? = framesPerSecond
        .takeIf { it.isFinite() && it in 1.0..240.0 }
        ?.toFloat()

    fun allowNonSeamlessSwitch(durationSeconds: Double): Boolean = durationSeconds >= 10 * 60

    /**
     * How long after the picture appears a non-seamless mode change is still acceptable, in
     * milliseconds.
     *
     * A refresh-rate change re-links HDMI, and the television answers with two to three seconds of
     * black. During startup the screen is black anyway, so that costs the viewer nothing. Ten
     * seconds into a scene it is the single most disruptive thing this receiver can do — and it is
     * exactly what happened on the onn 4K Pro (2026-08-28 13:13:12), because the container stated
     * no frame rate and the measured one arrived 6.3s after the first frame.
     *
     * 4 seconds covers the measured path once its window is clean: 48 frames at 24fps is 2.0s, plus
     * the decoder's own settling. Anything later is not worth a black screen the viewer did not ask
     * for — the panel simply stays on the mode it was already on, judder and all.
     */
    const val NON_SEAMLESS_GRACE_MILLIS = 4_000L

    /**
     * Whether a rate learned [millisSincePictureUp] after the first frame may still force a
     * non-seamless mode change. Null means the picture is not up yet, when the switch is free.
     */
    fun withinNonSeamlessWindow(millisSincePictureUp: Long?): Boolean =
        millisSincePictureUp == null || millisSincePictureUp <= NON_SEAMLESS_GRACE_MILLIS

    /**
     * How far a panel's refresh rate may sit from a whole multiple of the content rate and still
     * count as a match, in Hz.
     *
     * 1.0 Hz is deliberately loose enough to accept a 24.000 panel mode for 23.976 content — that
     * pairing drifts one frame every ~41 seconds, which is still far better than the 3:2 pulldown
     * judder of leaving a 60 Hz mode in place — and deliberately tight enough to reject 50 Hz for
     * 23.976 content (best multiple 47.952, 2.05 Hz away), which would be worse than doing nothing.
     */
    private const val REFRESH_TOLERANCE_HZ = 1.0f

    /** Whole multiples worth testing. A 120 Hz panel plays 23.976 content cleanly at 5x. */
    private val MULTIPLES = 1..5

    /**
     * Pick the display mode to request for [framesPerSecond], or null to leave the panel alone.
     *
     * `Surface.setFrameRate` needs API 30. Every box below that — which includes both televisions
     * this receiver is verified on (Fire OS 7 at API 28, and an API 29 Hisense) — can only change
     * refresh rate through `WindowManager.LayoutParams.preferredDisplayModeId`. Without this path
     * auto frame rate matching is a no-op on that hardware, not a degraded version of itself.
     *
     * **Resolution is never changed.** Only modes matching the current width and height are
     * considered. The panel's resolution is the viewer's own display setting; a refresh-rate
     * request must not quietly re-scale their whole interface.
     *
     * Returns null when the current mode is already the best answer, so the caller can avoid a
     * pointless HDMI re-link.
     */
    fun selectModeId(
        modes: List<DisplayModeInfo>,
        currentModeId: Int,
        currentWidth: Int,
        currentHeight: Int,
        framesPerSecond: Double,
    ): Int? {
        val rate = validRate(framesPerSecond) ?: return null
        if (currentWidth <= 0 || currentHeight <= 0) return null

        val candidates = modes.filter { it.width == currentWidth && it.height == currentHeight }
        if (candidates.isEmpty()) return null

        val best = candidates
            .mapNotNull { mode -> scored(mode, rate)?.let { mode to it } }
            .minWithOrNull(
                compareBy<Pair<DisplayModeInfo, Score>> { it.second.error }
                    .thenBy { it.second.multiple }
                    // A stable tie-break keeps the choice identical across calls, so an unchanged
                    // title cannot ping-pong between two equally good modes.
                    .thenBy { it.first.modeId },
            )
            ?.first
            ?: return null

        return best.modeId.takeIf { it != currentModeId }
    }

    /**
     * The rates real content is actually shot and broadcast at.
     *
     * A measured frame rate wobbles; a display-mode request must not. Snapping a measurement onto
     * this list is what turns "23.94, 24.03, 23.98…" into one stable answer, and is the same thing
     * Kodi does before it touches a refresh rate.
     */
    private val STANDARD_RATES = listOf(
        24_000.0 / 1_001.0, // 23.976 — the rate almost every film release actually is
        24.0,
        25.0,
        30_000.0 / 1_001.0, // 29.97
        30.0,
        48.0,
        50.0,
        60_000.0 / 1_001.0, // 59.94
        60.0,
    )

    /** How far a measurement may sit from a standard rate and still be called that rate. */
    private const val SNAP_TOLERANCE_FPS = 1.0

    /**
     * Turn a measured frame rate into a standard one, or null when it matches nothing.
     *
     * Needed because `Format.frameRate` is only populated by some extractors — Matroska sets it
     * from `DefaultDuration`, but a progressive MP4 arrives with `NO_VALUE`, which was measured on
     * a real box: size, duration and codec all published while the rate stayed 0.0 and auto frame
     * rate matching therefore did nothing. Counting rendered frames covers those containers.
     *
     * Nearest wins, with no thumb on the scale. 23.976 and 24.000 sit 0.024 apart — finer than
     * counting frames can resolve — so a measurement either side of 24.000 simply takes whichever
     * is closer. Getting that pair wrong costs one frame of drift every ~41 seconds in either
     * direction, which is far below what the judder this feature removes costs.
     */
    fun snapToStandardRate(measured: Double): Double? {
        if (!measured.isFinite() || measured <= 0.0) return null
        return STANDARD_RATES
            .map { it to kotlin.math.abs(it - measured) }
            .filter { it.second <= SNAP_TOLERANCE_FPS }
            .minByOrNull { it.second }
            ?.first
    }

    private data class Score(val error: Float, val multiple: Int)

    private fun scored(mode: DisplayModeInfo, rate: Float): Score? = MULTIPLES
        .map { multiple -> Score(kotlin.math.abs(mode.refreshRate - rate * multiple), multiple) }
        .minByOrNull { it.error }
        ?.takeIf { it.error <= REFRESH_TOLERANCE_HZ }
}

data class AndroidMediaCapabilities(
    val hdrTypes: List<String>,
    val directAudioCodecs: List<String>,
) {
    fun summary(): String {
        val hdr = hdrTypes.takeIf { it.isNotEmpty() }?.joinToString("/") ?: "SDR display"
        val audio = directAudioCodecs.takeIf { it.isNotEmpty() }?.joinToString("/") ?: "PCM audio"
        return "$hdr · $audio"
    }

    companion object {
        fun probe(context: Context, display: Display?): AndroidMediaCapabilities =
            AndroidMediaCapabilities(
                hdrTypes = AndroidHdrCapabilityProbe.supportedTypes(display),
                directAudioCodecs = AndroidDirectAudioProbe.supportedMpvCodecs(context),
            )
    }
}

internal object AndroidHdrCapabilityProbe {
    @Suppress("DEPRECATION")
    fun supportedTypes(display: Display?): List<String> {
        if (display == null) return emptyList()
        return runCatching {
            display.hdrCapabilities.supportedHdrTypes.asSequence().mapNotNull { type: Int ->
                when (type) {
                    Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
                    Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                    Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                    Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
                    else -> null
                }
            }.toList()
        }.getOrDefault(emptyList())
    }
}

internal object AndroidDirectAudioProbe {
    /**
     * Whether Android can report what the active route accepts at all. Below API 29 it cannot, and
     * [supportedMpvCodecs] returns an empty list that means "unknown", NOT "nothing supported".
     */
    val routeProbeAvailable: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    private data class Candidate(
        val mpvCodec: String,
        val encoding: Int,
        val channelMask: Int,
    )

    @Suppress("DEPRECATION")
    fun supportedMpvCodecs(context: Context): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val manager = context.getSystemService(AudioManager::class.java) ?: return emptyList()
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        return candidates.filter { candidate ->
            val format = AudioFormat.Builder()
                .setEncoding(candidate.encoding)
                .setChannelMask(candidate.channelMask)
                .setSampleRate(48_000)
                .build()
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    AudioManager.getDirectPlaybackSupport(format, attributes) !=
                        AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED
                } else {
                    AudioTrack.isDirectPlaybackSupported(format, attributes)
                }
            }.getOrDefault(false)
        }.map(Candidate::mpvCodec)
    }

    private val candidates = listOf(
        Candidate("ac3", AudioFormat.ENCODING_AC3, AudioFormat.CHANNEL_OUT_5POINT1),
        Candidate("eac3", AudioFormat.ENCODING_E_AC3, AudioFormat.CHANNEL_OUT_5POINT1),
        Candidate("dts", AudioFormat.ENCODING_DTS, AudioFormat.CHANNEL_OUT_5POINT1),
        Candidate("dts-hd", AudioFormat.ENCODING_DTS_HD, AudioFormat.CHANNEL_OUT_7POINT1_SURROUND),
        Candidate("truehd", AudioFormat.ENCODING_DOLBY_TRUEHD, AudioFormat.CHANNEL_OUT_7POINT1_SURROUND),
    )
}

/**
 * Which video codecs this box can decode in hardware, for the phone's source ranking.
 *
 * AV1 hardware decode is standard on Fire TV from 2022 onwards, but this must never be inferred
 * from the API level: Fire OS 7 reports API 28 and Amazon backported the AV1 media constants, so an
 * SDK-gated check rejects hardware that in fact decodes AV1 perfectly well. Only the device's own
 * decoder list is authoritative.
 */
internal object VideoCodecProbe {

    /** MIME types worth reporting, in the order the phone should read them. */
    val mimeByName: Map<String, String> = linkedMapOf(
        "h264" to "video/avc",
        "hevc" to "video/hevc",
        "av1" to "video/av01",
        "vp9" to "video/x-vnd.on2.vp9",
        "dolbyvision" to "video/dolby-vision",
    )

    fun hardwareDecoded(): List<String> {
        val codecs = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.asList()
        }.getOrElse { return emptyList() }
        val decoders = codecs.filter { !it.isEncoder && !isSoftwareOnly(it) }
        return mimeByName.filterValues { mime ->
            decoders.any { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
        }.keys.toList()
    }

    /**
     * `isHardwareAccelerated` only exists from API 29. Below that — which includes the Fire OS 7
     * boxes this receiver targets — fall back to the long-standing OMX/c2 naming convention, where
     * Google's own software decoders are the ones prefixed `OMX.google.` or `c2.android.`.
     */
    private fun isSoftwareOnly(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            !info.isHardwareAccelerated
        } else {
            SoftwareDecoderNamePolicy.isSoftwareName(info.name)
        }
}

/**
 * Which audio codecs any MediaCodec decoder exists for on this box — hardware or software, decode
 * is decode. Same law as [VideoCodecProbe]: read the device's own decoder list, never infer from
 * the API level. An empty result means the probe failed → "unknown", and the phone must not gate.
 */
internal object AudioCodecProbe {

    /** Canonical wire names → MIME types, in the order the phone should read them. */
    val mimeByName: Map<String, String> = linkedMapOf(
        "aac" to "audio/mp4a-latm",
        "ac3" to "audio/ac3",
        "eac3" to "audio/eac3",
        "dts" to "audio/vnd.dts",
        "dtshd" to "audio/vnd.dts.hd",
        "truehd" to "audio/true-hd",
        "flac" to "audio/flac",
        "opus" to "audio/opus",
        "vorbis" to "audio/vorbis",
        "mp3" to "audio/mpeg",
    )

    /**
     * Audio codecs the bundled ffmpeg extension decodes in software, regardless of what the chip
     * has. These ride the same ExoPlayer instance as the hardware video decoder, so reporting them
     * is honest: the phone can send a DTS/TrueHD release and the receiver really will play it.
     */
    private val ffmpegExtensionCodecs = listOf(
        "aac", "ac3", "eac3", "dts", "dtshd", "truehd", "flac", "opus", "vorbis", "mp3",
    )

    fun decoded(): List<String> {
        val codecs = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.asList()
        }.getOrElse { return ffmpegExtensionCodecs }
        val decoders = codecs.filter { !it.isEncoder }
        val hardware = mimeByName.filterValues { mime ->
            decoders.any { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
        }.keys
        return (hardware + ffmpegExtensionCodecs).distinct()
    }
}

internal object SoftwareDecoderNamePolicy {
    private val softwarePrefixes = listOf("omx.google.", "c2.android.", "omx.sec.sw.", "omx.ffmpeg.")

    fun isSoftwareName(name: String): Boolean {
        val lower = name.lowercase(Locale.US)
        return softwarePrefixes.any(lower::startsWith) || lower.contains(".sw.")
    }
}

internal object DirectAudioPolicy {
    private val knownCodecs = setOf("ac3", "eac3", "dts", "dts-hd", "truehd")

    /** Kodi per-codec passthrough keys, shared by both engine paths so they cannot drift apart. */
    val codecSettings = mapOf(
        "audiooutput.ac3passthrough" to "ac3",
        "audiooutput.eac3passthrough" to "eac3",
        "audiooutput.dtspassthrough" to "dts",
        "audiooutput.dtshdpassthrough" to "dts-hd",
        "audiooutput.truehdpassthrough" to "truehd",
    )

    fun allowedCodecs(
        passthroughRequested: Boolean,
        routeSupported: Collection<String>,
        profileRequested: Map<String, Boolean>,
    ): List<String> {
        if (!passthroughRequested) return emptyList()
        return routeSupported
            .filter { it in knownCodecs && profileRequested[it] == true }
            .distinct()
    }
}

/**
 * The route the ExoPlayer path should present to its audio sink.
 *
 * libmpv takes a direct `audio-spdif` property, so [DirectAudioPolicy] is all the mpv path needs.
 * ExoPlayer has no equivalent switch: `DefaultAudioSink` decides passthrough versus decode purely
 * from the [androidx.media3.exoplayer.audio.AudioCapabilities] it was built with. Constraining
 * those capabilities is therefore the only honest way to honour the phone's speaker profile on the
 * Fire OS path, which previously accepted every audio setting and applied none of them.
 */
internal sealed interface ExoAudioRoute {
    /** Let the platform's real capabilities stand — passthrough happens whenever the route allows. */
    data object Automatic : ExoAudioRoute

    /**
     * Offer the sink PCM plus exactly [codecs]; every other encoded format is decoded instead.
     * An empty list is the "decode everything to PCM" profile.
     */
    data class Constrained(val codecs: List<String>) : ExoAudioRoute
}

internal object ExoAudioRoutePolicy {
    fun resolve(
        automaticRouting: Boolean,
        passthroughRequested: Boolean,
        routeSupported: Collection<String>,
        profileRequested: Map<String, Boolean>,
        routeProbeAvailable: Boolean,
    ): ExoAudioRoute {
        if (automaticRouting) return ExoAudioRoute.Automatic
        // Below API 29 Android cannot report what the route accepts, so AndroidDirectAudioProbe
        // returns an empty list. The mpv path tolerates that — an empty list there just means "ask
        // for no passthrough". Constraining the ExoPlayer sink on the same empty list means
        // something entirely different and far worse: "this device supports nothing but PCM", which
        // tore down a working E-AC3 5.1 output on a Fire OS 7 box (0.1.22/0.1.23, AFTDCT31). Never
        // narrow capabilities from a probe that could not run — defer to the platform instead.
        if (!routeProbeAvailable) return ExoAudioRoute.Automatic
        return ExoAudioRoute.Constrained(
            DirectAudioPolicy.allowedCodecs(
                passthroughRequested = passthroughRequested,
                routeSupported = routeSupported,
                profileRequested = profileRequested,
            ),
        )
    }

    /**
     * Kodi setting keys this path genuinely acts on. Everything else in a Kodi audio profile
     * describes Kodi's own engine and is a no-op here, which the caller reports as such.
     */
    val actionableSettings: Set<String> = setOf(
        "x4789.audio.automatic",
        "audiooutput.passthrough",
    ) + DirectAudioPolicy.codecSettings.keys

    /** AudioFormat encodings for the codecs above, resolved at the Android edge. */
    fun encodings(codecs: Collection<String>): List<Int> =
        codecs.mapNotNull(encodingByCodec::get)

    private val encodingByCodec = mapOf(
        "ac3" to AudioFormat.ENCODING_AC3,
        "eac3" to AudioFormat.ENCODING_E_AC3,
        "dts" to AudioFormat.ENCODING_DTS,
        "dts-hd" to AudioFormat.ENCODING_DTS_HD,
        "truehd" to AudioFormat.ENCODING_DOLBY_TRUEHD,
    )
}

internal object MpvSubtitleFontPolicy {
    const val DEFAULT_FAMILY = "Roboto Medium"

    fun resolvedFamily(requestedFamily: String?): String {
        val normalized = requestedFamily.orEmpty().trim().lowercase(Locale.ROOT)
        return when {
            normalized.isEmpty() -> DEFAULT_FAMILY
            "atkinson" in normalized -> "Atkinson Hyperlegible"
            "noto" in normalized -> "Noto Sans Medium"
            "roboto" in normalized -> DEFAULT_FAMILY
            "menlo" in normalized || "mono" in normalized -> "JetBrains Mono"
            "georgia" in normalized || "serif" in normalized -> "Fraunces 72pt Soft"
            else -> "Inter SemiBold"
        }
    }

    /**
     * A Typeface for renderers that draw cues themselves (ExoPlayer's SubtitleView) instead of
     * resolving a font by family name the way libmpv does.
     *
     * The phone offers 14 faces; six ship in the APK. The rest are Apple-licensed and cannot, so
     * each maps to the Android family closest in class (grotesque / geometric / condensed / wide)
     * rather than collapsing to one fallback — which made seven of the phone's choices look
     * identical on television.
     */
    fun resolvedTypeface(context: Context, requestedFamily: String?): android.graphics.Typeface {
        val normalized = requestedFamily.orEmpty().trim().lowercase(Locale.ROOT)
        BUNDLED_ASSET_FOR_REQUEST.entries.firstOrNull { (key, _) -> key in normalized }
            ?.let { (_, asset) -> return bundled(context, asset) }
        SYSTEM_FAMILY_FOR_REQUEST.entries.firstOrNull { (key, _) -> key in normalized }
            ?.let { (_, family) ->
                return android.graphics.Typeface.create(family, android.graphics.Typeface.BOLD)
            }
        return bundled(context, "Inter-SemiBold.ttf")
    }

    private fun bundled(context: Context, asset: String): android.graphics.Typeface {
        typefaceCache[asset]?.let { return it }
        val loaded = runCatching {
            if (asset == JOST_VARIABLE) {
                // Jost ships as a variable font whose default instance is Regular — too light for
                // cues. Pin the weight axis instead of relying on synthetic bold.
                android.graphics.Typeface.Builder(context.assets, asset)
                    .setFontVariationSettings("'wght' 600")
                    .build()
            } else {
                android.graphics.Typeface.createFromAsset(context.assets, asset)
            }
        }.getOrNull() ?: android.graphics.Typeface.DEFAULT_BOLD
        typefaceCache[asset] = loaded
        return loaded
    }

    private const val JOST_VARIABLE = "Jost-Variable.ttf"

    private val typefaceCache = java.util.concurrent.ConcurrentHashMap<String, android.graphics.Typeface>()

    /** Checked first — an exact face we actually ship. Keys match the phone's `SubtitleFont` labels. */
    private val BUNDLED_ASSET_FOR_REQUEST = linkedMapOf(
        "atkinson" to "AtkinsonHyperlegible-Bold.ttf",
        "noto" to "NotoSans-Medium.ttf",
        "roboto" to "Roboto-Medium.ttf",
        "inter" to "Inter-SemiBold.ttf",
        "menlo" to "JetBrainsMono-Bold.ttf",
        "mono" to "JetBrainsMono-Bold.ttf",
        "georgia" to "Fraunces72ptSoft-SemiBold.ttf",
        "serif" to "Fraunces72ptSoft-SemiBold.ttf",
        // Futura is Apple-licensed and cannot ship here. Jost* is an OFL geometric revival drawn
        // from the same 1920s model — the circular bowls and single-storey 'a' actually read as
        // Futura on screen, unlike the system fallback this used to land on.
        "futura" to JOST_VARIABLE,
        "avenir" to JOST_VARIABLE,
    )

    /** Nearest-class approximations for faces that cannot ship with the APK. */
    private val SYSTEM_FAMILY_FOR_REQUEST = linkedMapOf(
        "trebuchet" to "sans-serif-condensed",
        "verdana" to "sans-serif-black",
        "gill" to "sans-serif-light",
        "helvetica" to "sans-serif",
        "arial" to "sans-serif",
        "rounded" to "sans-serif-medium",
        "system" to "sans-serif-medium",
    )
}

internal object MpvSubtitleFontStore {
    private const val ASSET_DIRECTORY = "subtitle-fonts"

    fun prepare(context: Context): File? = runCatching {
        val directory = File(context.filesDir, ASSET_DIRECTORY).apply { mkdirs() }
        FONT_FILES.forEach { filename ->
            val destination = File(directory, filename)
            if (!destination.isFile || destination.length() == 0L) {
                context.assets.open(filename).use { input ->
                    destination.outputStream().use(input::copyTo)
                }
            }
        }
        directory
    }.getOrNull()

    private val FONT_FILES = listOf(
        "AtkinsonHyperlegible-Bold.ttf",
        "Fraunces72ptSoft-SemiBold.ttf",
        "Inter-SemiBold.ttf",
        "JetBrainsMono-Bold.ttf",
        "NotoSans-Medium.ttf",
        "Roboto-Medium.ttf",
    )
}
