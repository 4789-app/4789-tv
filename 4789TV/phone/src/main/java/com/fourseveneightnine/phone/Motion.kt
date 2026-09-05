package com.fourseveneightnine.phone

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Whether this phone has asked for less movement.
 *
 * Android has no single "Reduce Motion" switch. What it has is
 * `Settings.Global.ANIMATOR_DURATION_SCALE`, which Developer options and the accessibility
 * "Remove animations" setting both drive. A value of 0 means the viewer has turned animation off,
 * and an app that keeps animating anyway is ignoring a direct instruction.
 *
 * The iOS app honours Reduce Motion. This is the same idea using the control Android actually
 * offers.
 */
@Composable
internal fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val scale = runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
        scale == 0f
    }
}

/**
 * Durations for the app's transitions, in milliseconds.
 *
 * Every animation reads its length from here so that turning animation off turns ALL of it off,
 * rather than leaving whichever transition someone forgot to check still moving.
 */
internal object Motion {
    /** A screen swapping for another screen. */
    fun screenIn(reduced: Boolean) = if (reduced) 0 else 180

    /** The outgoing screen. Shorter than [screenIn] so the two do not muddy each other. */
    fun screenOut(reduced: Boolean) = if (reduced) 0 else 120

    /** Small in-place changes: a chip selecting, a row settling. */
    fun element(reduced: Boolean) = if (reduced) 0 else 140
}
