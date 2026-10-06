@file:Suppress("OPT_IN_USAGE")

package com.fourseveneightnine.tv.client.ui.screens.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick as semanticOnClick
import androidx.compose.ui.semantics.onLongClick as semanticOnLongClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import android.view.KeyEvent as NativeKeyEvent
import coil3.compose.AsyncImage
import com.fourseveneightnine.tv.client.data.images.PosterRequest
import com.fourseveneightnine.tv.client.ui.components.KeyStroke
import com.fourseveneightnine.tv.client.ui.components.Skeleton
import com.fourseveneightnine.tv.client.ui.components.TvKeyboard
import com.fourseveneightnine.tv.client.ui.components.tvFocusRing
import com.fourseveneightnine.tv.client.ui.components.tvFocusScale
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvGeom
import com.fourseveneightnine.tv.client.ui.theme.TvShape
import com.fourseveneightnine.tv.client.ui.theme.TvType

/**
 * The pieces every Collections screen shares: a card that knows a held OK from a tapped one, the
 * modal keyboard, the drill-in frame, and a poster that never blocks the focus ring.
 */

/** How long OK must be held to mean "edit" rather than "open" (spec §17). */
private const val LONG_PRESS_MILLIS = 500L

/**
 * A focusable surface that answers both a press and a hold of OK.
 *
 * The hold is measured from the native event's own `downTime`, not from a clock read in the
 * composition, so a dropped frame cannot turn a hold into a press or the other way round.
 *
 * [onFocusedChange] fires from `onFocusChanged`, in the same frame the ring moves. A caller that
 * needs the focused index — the LEFT handler on the folder grid does — must not learn it from a
 * `LaunchedEffect`, which dispatches a frame later and can be read stale by the next key press.
 */
@Composable
internal fun LongPressFocusable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    accessibleLabel: String? = null,
    accessibleRole: Role = Role.Button,
    selected: Boolean? = null,
    checked: Boolean? = null,
    stateDescription: String? = null,
    clickLabel: String? = null,
    longClickLabel: String = "More options",
    customActions: List<CustomAccessibilityAction> = emptyList(),
    cornerRadius: Dp = 12.dp,
    focusScale: Float = TvGeom.FocusScale,
    onPlay: (() -> Unit)? = null,
    onMenu: (() -> Unit)? = null,
    onFocusedChange: ((Boolean) -> Unit)? = null,
    content: @Composable (focused: Boolean) -> Unit,
) {
    // A plain boolean, not `collectIsFocusedAsState`: an interaction source costs one coroutine and
    // one flow collection per card, and a grid of folders pays it per card for one bit of state.
    var focused by remember { mutableStateOf(false) }
    // True once the hold has already fired, so the key up that ends it is not a second press.
    val held = remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .tvFocusScale(focused, focusScale)
            .tvFocusRing(focused, cornerRadius)
            .semantics(mergeDescendants = true) {
                accessibleLabel?.let { contentDescription = it }
                role = accessibleRole
                selected?.let { this.selected = it }
                checked?.let { toggleableState = if (it) ToggleableState.On else ToggleableState.Off }
                stateDescription?.let { this.stateDescription = it }
                if (customActions.isNotEmpty()) this.customActions = customActions
                if (enabled) {
                    semanticOnClick(label = clickLabel) { onClick(); true }
                    if (onLongClick != null) {
                        semanticOnLongClick(label = longClickLabel) { onLongClick(); true }
                    }
                } else {
                    disabled()
                }
            }
            .onFocusChanged { state ->
                if (focused != state.isFocused) {
                    focused = state.isFocused
                    onFocusedChange?.invoke(state.isFocused)
                }
            }
            .focusable(enabled = enabled)
            .onKeyEvent { event ->
                // MENU is claimed on the DOWN event. The screen frame opens the rail on MENU from
                // an ancestor handler, and an ancestor only sees what the focused node did not
                // take first — so a card that wants MENU must take it here, not on the key up.
                if (event.type == KeyEventType.KeyDown && event.key == Key.Menu && onMenu != null) {
                    if (enabled) onMenu()
                    return@onKeyEvent true
                }
                val ok = event.key == Key.DirectionCenter ||
                    event.key == Key.Enter ||
                    event.key == Key.NumPadEnter
                if (ok) {
                    val native = event.nativeKeyEvent
                    val longEnough = native.repeatCount > 0 ||
                        native.flags and NativeKeyEvent.FLAG_LONG_PRESS != 0 ||
                        native.eventTime - native.downTime >= LONG_PRESS_MILLIS
                    when (event.type) {
                        // A held OK fires while it is still down, which is what a remote feels
                        // like. Waiting for the key up would leave the viewer holding a button
                        // with nothing happening.
                        KeyEventType.KeyDown -> {
                            if (enabled && onLongClick != null && longEnough && !held.value) {
                                held.value = true
                                onLongClick()
                            }
                        }
                        KeyEventType.KeyUp -> {
                            val alreadyFired = held.value
                            held.value = false
                            if (enabled && !alreadyFired) {
                                if (onLongClick != null && longEnough) onLongClick() else onClick()
                            }
                        }
                        else -> Unit
                    }
                    return@onKeyEvent true
                }
                if (event.type != KeyEventType.KeyUp) return@onKeyEvent false
                when (event.key) {
                    Key.MediaPlay, Key.MediaPlayPause -> {
                        if (enabled && onPlay != null) { onPlay(); true } else false
                    }
                    else -> false
                }
            },
    ) {
        content(focused)
    }
}

/**
 * The frame a drill-in screen sits in: the same 220 px gutter as every other screen, an opaque
 * canvas over the Activity's picture layer, and no rail. BACK is the only way out (spec §1.1).
 */
@Composable
internal fun DrillInFrame(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TvColor.Canvas)
            .padding(start = TvGeom.ContentLeft, top = TvGeom.SafeTop, end = 96.dp),
    ) {
        content()
    }
}

/**
 * The modal TV keyboard, spec §15.3. A reason line, a field, the 6 by 7 grid, and Done.
 *
 * The first key holds the only [FocusRequester] in this overlay, and it is always composed — the
 * grid is not lazy — so the remote can never land on an unattached node.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun KeyboardOverlay(
    reason: String,
    initial: String,
    onCancel: () -> Unit,
    onDone: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val firstKey = remember { FocusRequester() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TvColor.Canvas.copy(alpha = 0.70f))
            // The overlay is modal, so focus may not leave it. Measured on the box: LEFT from key
            // column 1 walked out to the navigation rail, DOWN walked the rail to Settings, and OK
            // opened Pair and Sync with the half-typed name thrown away.
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup()
            .semantics {
                paneTitle = reason
                isTraversalGroup = true
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(210.dp))
            Text(reason, style = TvType.PanelHeader, color = TvColor.TextPrimary, maxLines = 1)
            Spacer(Modifier.height(24.dp))
            Box(
                modifier = Modifier
                    .width(900.dp)
                    .height(88.dp)
                    .clip(TvShape.Control)
                    .background(TvColor.Elevated)
                    .border(1.dp, TvColor.Border, TvShape.Control)
                    .semantics {
                        contentDescription = "Entered text"
                        stateDescription = text.ifEmpty { "Empty" }
                    }
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                // No blinking caret: a blink at 3 m is a distraction and costs a frame twice a
                // second for nothing (spec §12.4).
                Text(
                    text.ifEmpty { "Type a name" },
                    style = TvType.Body,
                    color = if (text.isEmpty()) TvColor.TextMuted else TvColor.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(820.dp),
                )
            }
            Spacer(Modifier.height(32.dp))
            TvKeyboard(
                onKey = { stroke ->
                    when (stroke) {
                        is KeyStroke.Character -> text += stroke.value
                        KeyStroke.Space -> text += " "
                        KeyStroke.Backspace -> text = text.dropLast(1)
                        KeyStroke.Commit -> if (text.isNotBlank()) onDone(text.trim())
                    }
                },
                modal = true,
                keyWidth = 132.dp,
                keyHeight = 80.dp,
                gap = 14.dp,
                firstKeyModifier = Modifier.focusRequester(firstKey),
            )
        }
    }
    LaunchedEffect(Unit) { runCatching { firstKey.requestFocus() } }
    androidx.activity.compose.BackHandler(enabled = true) { onCancel() }
}

/** A collection's accent, drawn as the 12 px dot the checklist uses (spec §8.2). */
@Composable
internal fun AccentDot(color: Color, size: Dp = 12.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(androidx.compose.foundation.shape.CircleShape).background(color))
}

/**
 * The up-to-three poster slivers on a folder card: 96 x 144, each starting 40 px along the one
 * before it, drawn back to front so the later one lands on top.
 *
 * 40 px is a pitch, not a gap. As a gap it measured 24 + 96 x 3 + 40 x 2 = 392 px inside a 380 px
 * card and the card's clip sheared 12 px off the third sliver. As a pitch the block is 200 px.
 *
 * They never rotate. Rotation costs a layer per card and reads as clutter at 3 m (spec §5.8.2).
 */
@Composable
internal fun PosterSlivers(posters: List<String>, modifier: Modifier = Modifier) {
    val drawn = posters.take(FolderGrid.SLIVERS)
    val width = FolderGrid.sliverOffset(drawn.lastIndex.coerceAtLeast(0)) + FolderGrid.SLIVER_WIDTH
    Box(modifier = modifier.size(width.dp, 144.dp)) {
        drawn.forEachIndexed { index, url ->
            Box(
                modifier = Modifier
                    .offset(x = FolderGrid.sliverOffset(index).dp)
                    .size(FolderGrid.SLIVER_WIDTH.dp, 144.dp)
                    .clip(TvShape.Tile)
                    .background(TvColor.PosterPlaceholder),
            ) {
                Poster(url, Modifier.fillMaxSize())
            }
        }
    }
}

/** One folder preview. Coil is asked for w185, never a full-size poster (API-B, images). */
@Composable
internal fun Poster(url: String?, modifier: Modifier = Modifier, contentDescription: String? = null) {
    if (url.isNullOrBlank()) return
    val context = androidx.compose.ui.platform.LocalContext.current
    AsyncImage(
        model = PosterRequest.preview(url).toImageRequest(context),
        contentDescription = contentDescription,
        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
        modifier = modifier,
    )
}

/** A block of eight skeleton cells for the folder grid, spec §5.6. */
@Composable
internal fun FolderSkeletonRow(sweep: Boolean, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        repeat(FolderGrid.COLUMNS) {
            Column(Modifier.width(TvGeom.FolderCardWidth)) {
                Skeleton(
                    modifier = Modifier.size(TvGeom.FolderCardWidth, TvGeom.FolderCardHeight),
                    sweep = sweep,
                )
                Spacer(Modifier.height(10.dp))
                Skeleton(Modifier.size(240.dp, 24.dp), shape = TvShape.Badge)
                Spacer(Modifier.height(2.dp))
                Skeleton(Modifier.size(120.dp, 20.dp), shape = TvShape.Badge)
            }
        }
    }
}

/** The move-mode banner, spec §6.5. */
@Composable
internal fun MoveBanner(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(TvShape.Control)
            .background(TvColor.Focus.copy(alpha = 0.14f))
            .border(1.dp, TvColor.Focus, TvShape.Control),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = TvType.ControlLabel, color = TvColor.TextPrimary, maxLines = 1)
    }
}

/** Everything but the picked card dims to 55% in move mode; dimming reads at 3 m (spec §6.7.6). */
internal fun Modifier.moveModeDim(dimmed: Boolean): Modifier = if (dimmed) alpha(0.55f) else this
