@file:Suppress("OPT_IN_USAGE")

package com.fourseveneightnine.tv.client.ui.screens.collections

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import com.fourseveneightnine.tv.client.ui.components.SelectGesture
import com.fourseveneightnine.tv.client.ui.components.SelectAction
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
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
import coil3.compose.AsyncImage
import com.fourseveneightnine.tv.client.data.images.PosterRequest
import com.fourseveneightnine.tv.client.ui.components.KeyStroke
import com.fourseveneightnine.tv.client.ui.components.ButtonKind
import com.fourseveneightnine.tv.client.ui.components.Skeleton
import com.fourseveneightnine.tv.client.ui.components.TvButton
import com.fourseveneightnine.tv.client.ui.components.TvKeyboard
import com.fourseveneightnine.tv.client.ui.components.TvArtwork
import com.fourseveneightnine.tv.client.ui.screens.home.HomeCard
import com.fourseveneightnine.tv.client.ui.components.tvFocusRing
import com.fourseveneightnine.tv.client.ui.components.tvFocusScale
import com.fourseveneightnine.tv.client.ui.theme.LocalReduceMotion
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvGeom
import com.fourseveneightnine.tv.client.ui.theme.TvMotion
import com.fourseveneightnine.tv.client.ui.theme.TvShape
import com.fourseveneightnine.tv.client.ui.theme.TvType
import kotlinx.coroutines.delay

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
    focusRing: Boolean = true,
    onPlay: (() -> Unit)? = null,
    onMenu: (() -> Unit)? = null,
    onFocusedChange: ((Boolean) -> Unit)? = null,
    onSelectPressed: (() -> Unit)? = null,
    content: @Composable (focused: Boolean) -> Unit,
) {
    // A plain boolean, not `collectIsFocusedAsState`: an interaction source costs one coroutine and
    // one flow collection per card, and a grid of folders pays it per card for one bit of state.
    var focused by remember { mutableStateOf(false) }
    // True once the hold has already fired, so the key up that ends it is not a second press.
    val select = remember(enabled) { SelectGesture(LONG_PRESS_MILLIS) }
    var pressed by remember(enabled) { mutableStateOf(false) }
    Box(
        modifier = modifier
            .tvFocusScale(focused, focusScale)
            .tvFocusRing(focused && focusRing, cornerRadius, pressed = { pressed })
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
                if (!state.isFocused) {
                    select.cancel()
                    pressed = false
                }
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
                    when (event.type) {
                        // A held OK fires while it is still down, which is what a remote feels
                        // like. Waiting for the key up would leave the viewer holding a button
                        // with nothing happening.
                        KeyEventType.KeyDown -> {
                            if (enabled && native.repeatCount == 0) {
                                pressed = true
                                onSelectPressed?.invoke()
                            }
                            if (enabled && select.down(native.eventTime, native.repeatCount, onLongClick != null) ==
                                SelectAction.LongClick) onLongClick?.invoke()
                        }
                        KeyEventType.KeyUp -> {
                            pressed = false
                            if (native.isCanceled) select.cancel()
                            if (enabled) when (select.up(native.eventTime, onLongClick != null)) {
                                SelectAction.Click -> onClick()
                                SelectAction.LongClick -> onLongClick?.invoke()
                                SelectAction.None -> Unit
                            }
                        }
                        else -> Unit
                    }
                    return@onKeyEvent true
                }
                if (pressed && onSelectPressed != null && when (event.key) {
                        Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight -> true
                        else -> false
                    }) return@onKeyEvent true
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
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TvColor.Background)
            .padding(start = TvGeom.ContentLeft, top = TvGeom.SafeTop, end = 96.dp),
        content = content,
    )
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
    hint: String = "Type a name",
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
            Row(
                Modifier.width(900.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.weight(1f).height(88.dp)
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
                    Text(
                        text.ifEmpty { hint },
                        style = TvType.Body,
                        color = if (text.isEmpty()) TvColor.TextMuted else TvColor.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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

internal inline fun <T> List<T>.filterCollectionSearch(query: String, label: (T) -> String): List<T> =
    if (query.isEmpty()) this else filter { label(it).contains(query, ignoreCase = true) }

/** A collection's accent, drawn as the 12 px dot the checklist uses (spec §8.2). */
@Composable
internal fun AccentDot(color: Color, size: Dp = 12.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(androidx.compose.foundation.shape.CircleShape).background(color))
}

private val StackHeight = 252.dp
private val StackPosterWidth = 150.dp
private val StackPosterHeight = 225.dp

/** The front cover takes the focus ring. Rear covers fan from one shared animation value. */
@Composable
internal fun CollectionCover(
    accent: Color,
    posters: List<HomeCard>,
    focused: Boolean = false,
    modifier: Modifier = Modifier,
    emptyMark: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.fillMaxWidth().height(StackHeight)) {
        if (posters.isEmpty()) {
            Box(Modifier.align(Alignment.Center).size(128.dp)
                .tvFocusRing(focused, 64.dp)) {
                emptyMark()
            }
        } else {
            val previews = remember(posters) { posters.take(FolderGrid.POSTER_COUNT) }
            val reduceMotion = LocalReduceMotion.current
            var settled by remember { mutableStateOf(false) }
            LaunchedEffect(focused, reduceMotion) {
                settled = false
                if (focused && !reduceMotion) {
                    delay(TvMotion.CollectionSettleMillis)
                    settled = true
                }
            }
            // Losing focus changes the target in this composition, before the effect retires.
            val unfolded = focused && settled && !reduceMotion
            val lift = animateFloatAsState(
                targetValue = if (focused && !reduceMotion) 1f else 0f,
                animationSpec = tween(
                    durationMillis = if (reduceMotion) 0 else TvMotion.CollectionLiftMillis,
                    easing = TvMotion.CollectionStackOut,
                ),
                label = "collectionFrontLift",
            )
            val fan = animateFloatAsState(
                targetValue = if (unfolded) 1f else 0f,
                animationSpec = tween(
                    durationMillis = when {
                        reduceMotion -> 0
                        unfolded -> TvMotion.CollectionUnfoldMillis
                        else -> TvMotion.CollectionFoldMillis
                    },
                    easing = TvMotion.CollectionStackOut,
                ),
                label = "collectionFan",
            )
            for (index in previews.indices.reversed()) {
                val card = previews[index]
                val front = index == 0
                val depth = (index + 1) / 2
                val side = if (index % 2 == 1) -1f else 1f
                // Short stacks have enough room to show more of each side cover.
                val compact = previews.size <= 3
                val restSpread = if (compact) 0.26f else 0.16f + (depth - 1) * 0.085f
                val openSpread = if (compact) 0.59f else 0.39f + (depth - 1) * 0.149f
                val request = remember(card.posterUrl) { PosterRequest.preview(card.posterUrl) }
                TvArtwork(
                    request = request,
                    title = card.title,
                    modifier = Modifier.align(Alignment.Center)
                        .size(StackPosterWidth, StackPosterHeight)
                        .graphicsLayer {
                            val progress = if (front) lift.value else fan.value
                            translationX = if (front) 0f else side * (restSpread + (openSpread - restSpread) * progress) * size.width
                            translationY = (if (front) -0.028f * progress else 0.018f + 0.007f * depth) * size.height
                            rotationZ = if (front) 0f else side * (1.6f + 0.58f * depth + 2.6f * progress)
                            val scale = if (front) 1f + 0.025f * progress else 0.96f - 0.018f * depth
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(0.5f, 0.88f)
                            shape = TvShape.Tile
                            if (front) shadowElevation = 6.dp.toPx()
                        }
                        .then(if (front) Modifier.border(1.dp, accent.copy(alpha = 0.32f), TvShape.Tile) else Modifier)
                        .tvFocusRing(front && focused, 9.dp),
                    shape = TvShape.Tile,
                    showTitleWhenMissing = false,
                    showPlaceholder = front,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    posterLookupId = card.posterLookupId,
                    posterLookupType = card.type,
                    posterLookupTmdbID = card.tmdbID,
                    posterFallbackUrl = card.posterFallbackUrl,
                )
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

/** One row of poster proportions while the collection directory loads. */
@Composable
internal fun FolderSkeletonRow(sweep: Boolean, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        repeat(FolderGrid.COLUMNS) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Skeleton(
                    modifier = Modifier.size(StackPosterWidth, StackPosterHeight),
                    sweep = sweep,
                )
                Column(Modifier.padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Skeleton(Modifier.size(254.dp, 30.dp), shape = TvShape.Badge)
                    Spacer(Modifier.height(10.dp))
                    Skeleton(Modifier.size(145.dp, 28.dp), shape = TvShape.Badge)
                }
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
            .background(TvColor.Selection.copy(alpha = 0.14f))
            .border(1.dp, TvColor.Selection, TvShape.Control),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = TvType.ControlLabel, color = TvColor.TextPrimary, maxLines = 1)
    }
}

/** Everything but the picked card dims to 55% in move mode; dimming reads at 3 m (spec §6.7.6). */
internal fun Modifier.moveModeDim(dimmed: Boolean): Modifier = if (dimmed) alpha(0.55f) else this
