package com.fourseveneightnine.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager

/**
 * Rate-limits a held D-pad direction.
 *
 * A television remote repeats roughly every 40-60ms once held. Compose answers every one of those
 * with a focus search, and each search on this surface costs real UI-thread work: measured on an
 * onn 4K Pro, sweeping a rail ran 86% janky frames at a 69ms median while the GPU sat idle at
 * 11ms. Past a certain rate the extra events buy the viewer nothing — the focus cannot be seen
 * moving that fast — but they do buy dropped frames, which is exactly what makes a remote feel
 * sticky.
 *
 * The first press of a direction ALWAYS passes through untouched, so a single deliberate step is
 * never delayed. Only repeats are gated.
 *
 * This is a plain composable extension rather than `Modifier.composed`. A composed modifier is
 * materialised again at every use site and can never be compared, so Compose cannot reuse or skip
 * the chain it sits in — on this screen that is the chain every key press walks.
 * [onPreviewKeyEvent] is a real modifier element, and the remembered handler below keeps it equal
 * across recompositions, so the node is updated in place instead of being rebuilt.
 */
@Composable
internal fun Modifier.throttleDpadRepeats(
    horizontalIntervalMillis: Long = HORIZONTAL_INTERVAL_MILLIS,
    verticalIntervalMillis: Long = VERTICAL_INTERVAL_MILLIS,
): Modifier {
    val focusManager = LocalFocusManager.current
    val state = remember { ThrottleState() }
    val handler: (KeyEvent) -> Boolean =
        remember(focusManager, horizontalIntervalMillis, verticalIntervalMillis) {
            gate@{ event: KeyEvent ->
                if (event.type != KeyEventType.KeyDown) return@gate false
                val direction = when (event.key) {
                    Key.DirectionLeft -> FocusDirection.Left
                    Key.DirectionRight -> FocusDirection.Right
                    Key.DirectionUp -> FocusDirection.Up
                    Key.DirectionDown -> FocusDirection.Down
                    else -> return@gate false
                }
                // repeatCount 0 is the initial press. Never touch it: a deliberate single step must
                // feel immediate, and swallowing it would make the remote feel broken, not smooth.
                val isRepeat =
                    (event.nativeKeyEvent as? android.view.KeyEvent)?.repeatCount?.let { it > 0 }
                        ?: false
                if (!isRepeat) {
                    state.lastAcceptedAtMillis = System.currentTimeMillis()
                    state.lastDirection = direction
                    return@gate false
                }

                val interval = when (direction) {
                    FocusDirection.Left, FocusDirection.Right -> horizontalIntervalMillis
                    else -> verticalIntervalMillis
                }
                val now = System.currentTimeMillis()
                // A change of direction restarts the clock; a new intention, not a continuation.
                if (direction != state.lastDirection) {
                    state.lastDirection = direction
                    state.lastAcceptedAtMillis = now
                    return@gate false
                }
                if (now - state.lastAcceptedAtMillis < interval) {
                    // Consume it. Returning true stops Compose running a focus search for an event
                    // whose result the viewer would never see.
                    return@gate true
                }
                state.lastAcceptedAtMillis = now
                // Move focus ourselves and consume, so the accepted repeat costs exactly one search.
                focusManager.moveFocus(direction)
                true
            }
        }
    return this.onPreviewKeyEvent(handler)
}

private class ThrottleState {
    var lastAcceptedAtMillis: Long = 0L
    var lastDirection: FocusDirection? = null
}

/**
 * Horizontal is allowed to run faster than vertical: scanning along a rail is a common, cheap
 * motion, while crossing rows swaps the hero and its artwork and is worth pacing harder.
 *
 * These are a deliberate departure from the 60 ms gate in spec §0.9.6, kept because 60 ms on the
 * onn 4K Pro still ran the focus search faster than the frame it feeds. The spec is what needs the
 * edit, not these numbers; see `app/FIX_G_REPORT.md`.
 */
private const val HORIZONTAL_INTERVAL_MILLIS = 80L
private const val VERTICAL_INTERVAL_MILLIS = 112L
