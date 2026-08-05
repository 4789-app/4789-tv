package com.fourseveneightnine.tv.player.upscale

import android.content.SharedPreferences

/**
 * How eagerly the receiver should run the SGSR pass before the picture reaches the screen.
 *
 * The upscaler is Snapdragon Game Super Resolution v1 (BSD-3-Clause, Qualcomm) — a single-pass
 * edge-directed fragment shader, the lightest real-time upscaler published for mobile GPUs. It is
 * plain GLES and runs on any vendor's GPU; "Snapdragon" is branding, not a requirement. Heavier
 * CNN upscalers (Anime4K, FSRCNNX) are out of reach on stick-class GPUs: Moonlight measured even
 * SGSR at ~14 ms/frame on a Mali-G31 television, which fits a 24 fps film (41 ms budget) but not
 * 60 fps — so the effect is opt-in, never a default.
 */
enum class UpscaleMode {
    /** The effects pipeline does not exist at all; zero-copy MediaCodec-to-surface. */
    OFF,

    /** Attach only when the decoded frame would genuinely be enlarged (scale >= 1.05). */
    AUTO,

    /**
     * Also run the pass at 1:1 on 1080p-class content — SGSR's edge-directed filtering then acts
     * as a sharpener feeding the panel scaler. Deliberately never reaches 4K-native frames or
     * HDR: the GL colour conversion alone changes those pictures (the Fire TV regression), and a
     * 1080p UI surface cannot show more than 1080p of the panel anyway.
     */
    FORCE_1080P,
}

internal object UpscalePolicy {

    /** Wire setting name, set by the phone through the Kodi settings sidechannel. */
    const val SETTING_NAME = "x4789.upscale"

    const val PREFERENCES_NAME = "receiver-video"
    const val PREFERENCE_KEY = "upscaleEnabled"

    /** Modes newer than the boolean era; read with [loadMode] so old prefs still work. */
    const val MODE_KEY = "upscaleMode"

    /**
     * SGSR v1 is a 2x-class spatial upscaler; past 2x it invents nothing more and only costs more
     * fragment work, so the surface's own bilinear covers any remainder (480p on a 4K panel).
     */
    const val MAX_SCALE = 2.0f

    /**
     * Below this the output is visually the input and the shader pass is pure heat. Covers
     * native-resolution content and the 1080p-video-on-1080p-panel case with rounding slack.
     */
    const val MIN_USEFUL_SCALE = 1.05f

    /** The tallest frame the FORCE_1080P mode will ever touch. */
    const val FORCE_MAX_INPUT_HEIGHT = 1080

    /**
     * Aspect-preserving fit of the decoded frame into the display, clamped to what the shader can
     * honestly deliver. Returns 1.0 for degenerate sizes so a broken stream never upscales.
     */
    fun scaleFor(inputWidth: Int, inputHeight: Int, displayWidth: Int, displayHeight: Int): Float {
        if (inputWidth <= 0 || inputHeight <= 0 || displayWidth <= 0 || displayHeight <= 0) return 1.0f
        val fit = minOf(
            displayWidth.toFloat() / inputWidth.toFloat(),
            displayHeight.toFloat() / inputHeight.toFloat(),
        )
        return fit.coerceIn(1.0f, MAX_SCALE)
    }

    fun isWorthUpscaling(scale: Float): Boolean = scale >= MIN_USEFUL_SCALE

    /**
     * Whether the effects pipeline should exist for a decoded frame at all. The pipeline reroutes
     * every frame through an OpenGL video graph whose colour conversion differs from the zero-copy
     * MediaCodec-to-surface path — attaching it to a title it cannot help only makes the picture's
     * colours change for nothing (the 4K-native regression on the Fire TV), so it must prove
     * useful AND SDR before it is ever attached. FORCE_1080P widens "useful" to the 1:1 sharpen on
     * 1080p-class frames but still never touches 4K-native or HDR.
     */
    fun shouldApply(
        inputWidth: Int,
        inputHeight: Int,
        displayWidth: Int,
        displayHeight: Int,
        hdr: Boolean,
        mode: UpscaleMode,
    ): Boolean {
        if (hdr || inputWidth <= 0 || inputHeight <= 0 || displayWidth <= 0 || displayHeight <= 0) {
            return false
        }
        return when (mode) {
            UpscaleMode.OFF -> false
            UpscaleMode.AUTO -> isWorthUpscaling(scaleFor(inputWidth, inputHeight, displayWidth, displayHeight))
            UpscaleMode.FORCE_1080P -> inputHeight <= FORCE_MAX_INPUT_HEIGHT &&
                inputWidth <= displayWidth && inputHeight <= displayHeight
        }
    }

    /** The phone's boolean sidechannel maps to AUTO/off; the TV pill carries the full cycle. */
    fun modeFromBoolean(enabled: Boolean): UpscaleMode = if (enabled) UpscaleMode.AUTO else UpscaleMode.OFF

    fun loadMode(prefs: SharedPreferences): UpscaleMode {
        val name = prefs.getString(MODE_KEY, null)
        if (name != null) {
            return runCatching { UpscaleMode.valueOf(name) }.getOrDefault(UpscaleMode.OFF)
        }
        // Pre-mode installs persisted a boolean; a missing MODE_KEY means one of those.
        return modeFromBoolean(prefs.getBoolean(PREFERENCE_KEY, false))
    }

    fun saveMode(prefs: SharedPreferences, mode: UpscaleMode) {
        prefs.edit().putString(MODE_KEY, mode.name).apply()
    }

    /**
     * The SGSR shader needs `textureGather`, a GLES 3.1 feature. media3 asks for a client-version-3
     * context, which on 3.1+ drivers exposes the highest 3.x the hardware speaks — so the
     * `GL_VERSION` string, not the context request, is the truth about what will compile.
     */
    fun supportsSgsr(glVersion: String?): Boolean {
        val match = GL_ES_VERSION.find(glVersion ?: return false) ?: return false
        val major = match.groupValues[1].toIntOrNull() ?: return false
        val minor = match.groupValues[2].toIntOrNull() ?: return false
        return major > 3 || (major == 3 && minor >= 1)
    }

    private val GL_ES_VERSION = Regex("""OpenGL ES (\d+)\.(\d+)""")
}
