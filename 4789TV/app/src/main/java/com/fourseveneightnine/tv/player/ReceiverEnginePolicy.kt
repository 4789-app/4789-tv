package com.fourseveneightnine.tv.player

/**
 * Which playback engine this box should run.
 *
 * The choice was previously a hardcoded brand check with no way out: a Fire TV could never run
 * libmpv and a Shield could never run ExoPlayer, so a device-specific playback fault had no
 * comparison to test against and no workaround short of a rebuild. The brand check remains the
 * default — it encodes real, tested behaviour — but it is now a default rather than a law.
 */
enum class ReceiverEngine { Mpv, Exo }

internal object ReceiverEnginePolicy {

    /** This build packages NextLib's FFmpeg, not libmpv's incompatible FFmpeg SONAMEs. */
    const val MPV_SELECTABLE = false

    const val SETTING_NAME = "x4789.engine"

    const val OVERRIDE_AUTO = "auto"
    const val OVERRIDE_MPV = "mpv"
    const val OVERRIDE_EXO = "exo"

    /**
     * ExoPlayer everywhere, on every brand.
     *
     * libmpv used to be the default for non-Fire boxes because it decoded formats ExoPlayer could
     * not. That advantage is gone: the ffmpeg extension now gives the Exo path the same software
     * audio decoders (DTS, DTS-HD, TrueHD) while the picture stays on the hardware decoder — the
     * split Kodi uses. libmpv is also, in this build, unsafe to select at all: it ships FFmpeg n8.1
     * and the extension ships 6.0 under identical SONAMEs, so only one set can be packaged and mpv
     * would link against the wrong one. Defaulting anything to Mpv would hand a non-Amazon box a
     * broken engine, which is exactly what this receiver must never do on hardware it has not run
     * on before.
     */
    fun automaticEngine(manufacturer: String, model: String): ReceiverEngine = ReceiverEngine.Exo

    /** An unrecognised or absent override falls back to the brand default rather than failing. */
    fun engine(manufacturer: String, model: String, override: String?): ReceiverEngine =
        when (override?.trim()?.lowercase()) {
            OVERRIDE_EXO -> ReceiverEngine.Exo
            else -> automaticEngine(manufacturer, model)
        }

    /** Normalises a requested override, or returns null when the value is not one we accept. */
    fun normalizedOverride(value: String): String? =
        when (val trimmed = value.trim().lowercase()) {
            OVERRIDE_AUTO, OVERRIDE_EXO -> trimmed
            else -> null
        }

    /** `auto` is stored as "no override" so a later default change is picked up automatically. */
    fun storedValue(normalizedOverride: String): String? =
        normalizedOverride.takeIf { it == OVERRIDE_EXO }
}
