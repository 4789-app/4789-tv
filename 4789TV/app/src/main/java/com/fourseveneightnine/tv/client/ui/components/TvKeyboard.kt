package com.fourseveneightnine.tv.client.ui.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick as semanticOnClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvGeom
import com.fourseveneightnine.tv.client.ui.theme.TvType

/** What a key press means. */
internal sealed interface KeyStroke {
    data class Character(val value: String) : KeyStroke
    data object Space : KeyStroke
    data object Backspace : KeyStroke
    /** "Clear" on the Search screen, "Done" when the keyboard is modal (spec §15.3). */
    data object Commit : KeyStroke
}

/**
 * The on-screen keyboard, spec §12.2.
 *
 * Six columns by seven rows. Row 7 is Space, Backspace and Clear/Done, two columns each. The last
 * key clears on Search and commits when the keyboard is modal.
 *
 * Keys do not scale on focus (spec §18.17): they are pressed fast and often, and a scaling key
 * wobbles under a held press. They take the ring plus an `elevated2` fill.
 */
@Composable
internal fun TvKeyboard(
    onKey: (KeyStroke) -> Unit,
    modifier: Modifier = Modifier,
    modal: Boolean = false,
    keyWidth: Dp = 88.dp,
    keyHeight: Dp = 72.dp,
    gap: Dp = 12.dp,
    firstKeyModifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        LETTER_ROWS.forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEachIndexed { columnIndex, label ->
                    KeyCap(
                        label = label,
                        width = keyWidth,
                        height = keyHeight,
                        modifier = if (rowIndex == 0 && columnIndex == 0) firstKeyModifier else Modifier,
                        onClick = { onKey(KeyStroke.Character(label)) },
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            KeyCap("Space", keyWidth * 2 + gap, keyHeight) { onKey(KeyStroke.Space) }
            // Spec §12.2 names this key Backspace, and the 188 px key fits the longer word. It is
            // the one key that repeats when held (spec §12.7.4).
            KeyCap(
                label = "Backspace",
                width = keyWidth * 2 + gap,
                height = keyHeight,
                repeats = true,
                onClick = { onKey(KeyStroke.Backspace) },
            )
            // Two columns, not one: in a single 88 px key "Clear" touched both edges.
            KeyCap(if (modal) "Done" else "Clear", keyWidth * 2 + gap, keyHeight) { onKey(KeyStroke.Commit) }
        }
    }
}

/**
 * When a held key fires again, spec §12.7.4 and §0.9.6.
 *
 * Android sends the first press with `repeatCount == 0` and then a stream of repeats while the key
 * stays down. Acting on every one of them would delete a whole query in a frame, so the repeats are
 * gated to one every [GATE_MILLIS]. Pure, so the gate is testable without a remote.
 */
internal object KeyRepeat {

    /** Spec §0.9.6. */
    const val GATE_MILLIS: Long = 60L

    fun shouldFire(repeatCount: Int, nowMillis: Long, lastFiredMillis: Long): Boolean =
        repeatCount <= 0 || nowMillis - lastFiredMillis >= GATE_MILLIS
}

@Composable
private fun KeyCap(
    label: String,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    repeats: Boolean = false,
    onClick: () -> Unit,
) {
    // Focus changes invalidate this key's draw node only. Recomposition of a 42-key keyboard on
    // every D-pad move was the Search screen's hot path on the onn 4K Pro.
    val focused = remember { mutableStateOf(false) }
    // A plain long array, not state: nothing here is drawn from it, so writing it must not
    // recompose the key under a held press.
    val lastFired = remember { longArrayOf(0L) }
    val actionLabel = when (label) {
        "Backspace" -> "Delete character"
        "Clear" -> "Clear search"
        "Done" -> "Finish typing"
        else -> "Type $label"
    }
    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .drawBehind {
                val radius = 8.dp.toPx()
                drawRoundRect(if (focused.value) TvColor.Elevated2 else TvColor.Elevated,
                    cornerRadius = CornerRadius(radius))
                if (focused.value) {
                    val ring = TvGeom.FocusRingWidth.toPx()
                    val gap = 2.dp.toPx()
                    val outline = 1.dp.toPx()
                    val bandInset = -(gap + ring / 2f)
                    drawRoundRect(TvColor.Focus, topLeft = Offset(bandInset, bandInset),
                        size = Size(size.width - bandInset * 2, size.height - bandInset * 2),
                        cornerRadius = CornerRadius(radius + gap + ring / 2f),
                        style = Stroke(width = ring))
                    val outlineInset = -(gap + ring + outline / 2f)
                    drawRoundRect(TvColor.Canvas, topLeft = Offset(outlineInset, outlineInset),
                        size = Size(size.width - outlineInset * 2, size.height - outlineInset * 2),
                        cornerRadius = CornerRadius(radius + gap + ring + outline / 2f),
                        style = Stroke(width = outline))
                }
            }
            .semantics(mergeDescendants = true) {
                contentDescription = label
                role = Role.Button
                semanticOnClick(label = actionLabel) { onClick(); true }
            }
            .onFocusChanged { focused.value = it.isFocused }
            .focusable()
            .onKeyEvent { event ->
                val isOk = when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> true
                    else -> false
                }
                if (!isOk) return@onKeyEvent false
                if (!repeats) {
                    return@onKeyEvent if (event.type == KeyEventType.KeyUp) { onClick(); true } else false
                }
                // A repeating key acts on key down and swallows the matching key up, so one press
                // is still exactly one stroke.
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent event.type == KeyEventType.KeyUp
                val now = android.os.SystemClock.uptimeMillis()
                if (!KeyRepeat.shouldFire(event.nativeKeyEvent.repeatCount, now, lastFired[0])) {
                    return@onKeyEvent true
                }
                lastFired[0] = now
                onClick()
                true
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = TvType.Key, color = TvColor.TextPrimary, maxLines = 1)
    }
}

private val LETTER_ROWS = listOf(
    listOf("A", "B", "C", "D", "E", "F"),
    listOf("G", "H", "I", "J", "K", "L"),
    listOf("M", "N", "O", "P", "Q", "R"),
    listOf("S", "T", "U", "V", "W", "X"),
    listOf("Y", "Z", "0", "1", "2", "3"),
    listOf("4", "5", "6", "7", "8", "9"),
)
