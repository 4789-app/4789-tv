package com.fourseveneightnine.tv.client.ui.components

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick as semanticOnClick
import androidx.compose.ui.semantics.onLongClick as semanticOnLongClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.request.crossfade
import com.fourseveneightnine.tv.client.ui.theme.LocalReduceMotion
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvGeom
import com.fourseveneightnine.tv.client.ui.theme.TvMotion
import com.fourseveneightnine.tv.client.ui.theme.TvShape
import com.fourseveneightnine.tv.client.ui.theme.TvSpace
import com.fourseveneightnine.tv.client.ui.theme.TvType

/**
 * The component library, spec §16. Every size here is a design pixel; [TvTheme] makes one `dp`
 * one design pixel, so the spec's numbers appear literally.
 */

/** Full, untruncated speech labels assembled outside layout text. */
internal fun accessibilityLabel(vararg parts: String?): String = parts
    .asSequence()
    .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
    .distinct()
    .joinToString(", ")

/** A focusable surface that answers OK, draws the ring, and grows by the given scale. */
@Composable
internal fun TvFocusable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    focusWhenDisabled: Boolean = false,
    accessibleLabel: String? = null,
    accessibleRole: Role = Role.Button,
    selected: Boolean? = null,
    checked: Boolean? = null,
    stateDescription: String? = null,
    clickLabel: String? = null,
    customActions: List<CustomAccessibilityAction> = emptyList(),
    cornerRadius: Dp = 12.dp,
    focusScale: Float = TvGeom.FocusScale,
    focusRing: Boolean = true,
    content: @Composable (focused: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier = modifier
            .tvFocusScale(focused, focusScale)
            .tvFocusRing(focused && focusRing, cornerRadius)
            .semantics(mergeDescendants = true) {
                accessibleLabel?.let { contentDescription = it }
                role = accessibleRole
                selected?.let { this.selected = it }
                checked?.let { toggleableState = if (it) ToggleableState.On else ToggleableState.Off }
                stateDescription?.let { this.stateDescription = it }
                if (customActions.isNotEmpty()) this.customActions = customActions
                if (enabled) {
                    semanticOnClick(label = clickLabel) { onClick(); true }
                } else {
                    disabled()
                }
            }
            .focusable(enabled = enabled || focusWhenDisabled, interactionSource = interaction)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (enabled) onClick()
                        true
                    }
                    else -> false
                }
            },
        // Spec §16.7, and the reason a 240 px button drew its ring at 240 and its fill at the
        // label's width: a Box drops the minimum constraints on the way in, so an explicit width
        // on `modifier` reached the ring and never reached the content. Passing them through means
        // the fill is the width the caller asked for, which is the width the ring is drawn at.
        // With no width given the minimum is zero, so every card and chip still wraps its content.
        propagateMinConstraints = true,
    ) {
        content(focused)
    }
}

// ---------------------------------------------------------------------------- cards

/** Spec §16.1. 236 x 354 at rest; the title lives on the hero, not under a Home card. */
@Composable
internal fun PosterCard(
    title: String,
    modifier: Modifier = Modifier,
    showTitle: Boolean = false,
    width: Dp = TvGeom.PosterWidth,
    height: Dp = TvGeom.PosterHeight,
    badges: List<BadgeSpec> = emptyList(),
    onClick: () -> Unit = {},
) {
    Column(modifier = modifier.width(width)) {
        TvFocusable(onClick = onClick, accessibleLabel = title, cornerRadius = 12.dp) { _ ->
            Box(
                modifier = Modifier
                    .size(width, height)
                    .clip(TvShape.Card)
                    .background(TvColor.PosterPlaceholder),
            ) {
                Text(
                    text = title,
                    style = TvType.CardTitle,
                    color = TvColor.TextSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = TvSpace.S),
                )
                Column(
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    badges.take(3).forEach { TvBadge(it) }
                }
            }
        }
        if (showTitle) {
            Spacer(Modifier.height(TvGeom.FocusLabelGap))
            Text(
                text = title,
                style = TvType.CardTitle,
                color = TvColor.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Spec §16.2. 300 x 169 image plus a text block: Continue rows and episodes. */
@Composable
internal fun WideCard(
    title: String,
    line: String,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    onClick: () -> Unit = {},
) {
    Column(modifier = modifier.width(TvGeom.WideCardWidth)) {
        TvFocusable(
            onClick = onClick,
            accessibleLabel = accessibilityLabel(title, line),
            cornerRadius = 12.dp,
        ) { _ ->
            Box(
                modifier = Modifier
                    .size(TvGeom.WideCardWidth, TvGeom.WideCardHeight)
                    .clip(TvShape.Card)
                    .background(TvColor.PosterPlaceholder),
            ) {
                if (progress != null) {
                    TvProgressBar(
                        progress = progress,
                        height = 6.dp,
                        modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
                    )
                }
            }
        }
        Spacer(Modifier.height(TvGeom.FocusLabelGap))
        Text(title, style = TvType.CardTitle, color = TvColor.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(line, style = TvType.Meta, color = TvColor.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Spec §16.3. One folder size everywhere: 380 x 214 plus 46 of text. */
@Composable
internal fun FolderCard(
    name: String,
    count: String,
    modifier: Modifier = Modifier,
    accent: Color = TvColor.TextMuted,
    isNew: Boolean = false,
    onClick: () -> Unit = {},
) {
    Column(modifier = modifier.width(TvGeom.FolderCardWidth)) {
        TvFocusable(
            onClick = onClick,
            accessibleLabel = accessibilityLabel(name, count),
            cornerRadius = 12.dp,
        ) { _ ->
            Box(
                modifier = Modifier
                    .size(TvGeom.FolderCardWidth, TvGeom.FolderCardHeight)
                    .clip(TvShape.Card)
                    .background(if (isNew) Color.Transparent else TvColor.Elevated)
                    .border(if (isNew) 2.dp else 0.dp, if (isNew) TvColor.Border else Color.Transparent, TvShape.Card),
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(6.dp)
                        .background(accent),
                )
            }
        }
        Spacer(Modifier.height(TvGeom.FocusLabelGap))
        Text(name, style = TvType.CardTitle, color = TvColor.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(count, style = TvType.Meta, color = TvColor.TextSecondary, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------- shelf header

/**
 * Spec §16.4. 40 tall, never focusable. The stale label sits 16 px after the title on the same
 * baseline and is a fact, not a fault — muted grey, never `warning`.
 */
@Composable
internal fun ShelfHeader(
    title: String,
    modifier: Modifier = Modifier,
    staleLabel: String? = null,
) {
    Row(modifier = modifier.height(48.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, style = TvType.ShelfHeader, color = TvColor.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (staleLabel != null) {
            Spacer(Modifier.width(TvSpace.S))
            StaleLabel(staleLabel)
        }
    }
}

/** Spec §15.6. "updated 2h ago", 20 Regular `textMuted`. It never says "stale". */
@Composable
internal fun StaleLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = TvType.Meta, color = TvColor.TextMuted, maxLines = 1, modifier = modifier)
}

// ---------------------------------------------------------------------------- chips and badges

internal enum class ChipKind { Choice, Data, Rating, Cached, Count }

/** Spec §16.5. */
@Composable
internal fun TvChip(
    label: String,
    modifier: Modifier = Modifier,
    kind: ChipKind = ChipKind.Choice,
    selected: Boolean = false,
    onClick: () -> Unit = {},
) {
    val height = when (kind) {
        ChipKind.Choice -> 56.dp
        ChipKind.Data, ChipKind.Cached -> 30.dp
        ChipKind.Rating -> 36.dp
        ChipKind.Count -> 40.dp
    }
    val shape = if (kind == ChipKind.Choice || kind == ChipKind.Count) TvShape.Chip else TvShape.Badge
    val fill = when {
        kind == ChipKind.Cached -> TvColor.Cached.copy(alpha = 0.16f)
        kind == ChipKind.Data -> TvColor.Elevated2
        selected -> TvColor.Elevated2
        else -> TvColor.Elevated
    }
    val labelColor = when {
        kind == ChipKind.Cached -> TvColor.Cached
        selected -> TvColor.TextPrimary
        else -> TvColor.TextSecondary
    }
    val style = when (kind) {
        ChipKind.Data, ChipKind.Count -> TvType.data(20)
        ChipKind.Cached -> TvType.Badge
        else -> TvType.CardTitle
    }
    if (kind == ChipKind.Choice) {
        TvFocusable(
            onClick = onClick,
            modifier = modifier,
            accessibleLabel = label,
            accessibleRole = Role.Button,
            selected = selected,
            cornerRadius = 8.dp,
            focusScale = 1f,
        ) { _ ->
            ChipBody(label, height, shape, fill, labelColor, style, selected)
        }
    } else {
        Box(modifier) { ChipBody(label, height, shape, fill, labelColor, style, selected) }
    }
}

@Composable
private fun ChipBody(
    label: String,
    height: Dp,
    shape: androidx.compose.ui.graphics.Shape,
    fill: Color,
    labelColor: Color,
    style: TextStyle,
    selected: Boolean,
) {
    Box(
        modifier = Modifier
            .height(height)
            .clip(shape)
            .background(fill)
            .border(if (selected) 1.dp else 0.dp, if (selected) Color.White.copy(alpha = 0.16f) else Color.Transparent, shape)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = style, color = labelColor, maxLines = 1)
    }
}

internal data class BadgeSpec(val label: String, val fill: Color, val labelColor: Color, val borderColor: Color = Color.Transparent)

internal object Badges {
    val Cached = BadgeSpec("CACHED", TvColor.Cached.copy(alpha = 0.16f), TvColor.Cached, TvColor.Cached.copy(alpha = 0.5f))
    val FourK = BadgeSpec("4K", TvColor.Canvas.copy(alpha = 0.78f), TvColor.TextPrimary)
    val Hdr = BadgeSpec("HDR", TvColor.Canvas.copy(alpha = 0.78f), TvColor.TextPrimary)
    // Spec §3.3: the badge a Home poster carries is canvas 78% with `textPrimary` ink, like 4K and
    // HDR. §16.6 still lists an accent-filled NEW; §3.3 is the one that names the Home poster and
    // wins here. An orange block on every recently-added card was the loudest thing on the screen.
    val New = BadgeSpec("NEW", TvColor.Canvas.copy(alpha = 0.78f), TvColor.TextPrimary)
}

/** Spec §16.6. */
@Composable
internal fun TvBadge(spec: BadgeSpec, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(24.dp)
            .clip(TvShape.Badge)
            .background(spec.fill)
            .border(1.dp, spec.borderColor, TvShape.Badge)
            .padding(horizontal = TvSpace.XS),
        contentAlignment = Alignment.Center,
    ) {
        Text(spec.label, style = TvType.Badge, color = spec.labelColor, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------- buttons

internal enum class ButtonKind { Primary, Secondary, Ghost, Destructive }

/** Spec §16.7: a filled button's label is 24 SemiBold. Built once, not per recomposition. */
private val FilledLabelStyle = TvType.ControlLabel.copy(fontWeight = FontWeight.SemiBold)

/** Spec §3.3: a 24 px glyph at the label's left with a 12 px gap. */
private val ButtonGlyphSize = 24.dp
private val ButtonGlyphGap = 12.dp

/**
 * Spec §16.7. A disabled button still takes focus, so the ring never disappears; OK does nothing.
 * Focus scale is 1.04, because 1.06 on a 320 px button reads as a wobble.
 *
 * [glyph] is the 24 px mark a primary action carries, spec §3.3 — the hero's play triangle. It is
 * a slot rather than a drawable so a caller can hand over whatever mark its own spec names.
 */
@Composable
internal fun TvButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Secondary,
    enabled: Boolean = true,
    height: Dp = TvGeom.ButtonHeight,
    glyph: (@Composable () -> Unit)? = null,
) {
    val fill = when {
        !enabled -> TvColor.Elevated
        kind == ButtonKind.Primary -> Color.White
        kind == ButtonKind.Destructive -> TvColor.Error
        kind == ButtonKind.Ghost -> Color.Transparent
        else -> TvColor.Elevated
    }
    val labelColor = when {
        !enabled -> TvColor.TextMuted
        kind == ButtonKind.Primary -> TvColor.Canvas
        kind == ButtonKind.Destructive -> TvColor.OnAccent
        kind == ButtonKind.Ghost -> TvColor.TextSecondary
        else -> TvColor.TextPrimary
    }
    val borderColor = when {
        !enabled -> Color.Transparent
        kind == ButtonKind.Secondary -> Color.White.copy(alpha = 0.10f)
        kind == ButtonKind.Ghost -> Color.White.copy(alpha = 0.14f)
        else -> Color.Transparent
    }
    // Spec §16.7: primary and destructive are 24 SemiBold, secondary and ghost 24 Medium.
    val labelStyle = when (kind) {
        ButtonKind.Primary, ButtonKind.Destructive -> FilledLabelStyle
        else -> TvType.ControlLabel
    }
    TvFocusable(
        onClick = { if (enabled) onClick() },
        modifier = modifier,
        enabled = enabled,
        focusWhenDisabled = true,
        accessibleLabel = label,
        cornerRadius = 32.dp,
        focusScale = 1.035f,
        focusRing = false,
    ) { focused ->
        Row(
            modifier = Modifier
                .height(height)
                .clip(CircleShape)
                .background(if (focused) Color.White else fill)
                .border(1.dp, if (focused) Color.White else borderColor, CircleShape)
                .padding(horizontal = 28.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (glyph != null) {
                Box(Modifier.size(ButtonGlyphSize), contentAlignment = Alignment.Center) { glyph() }
                Spacer(Modifier.width(ButtonGlyphGap))
            }
            Text(label, style = labelStyle,
                color = if (focused) TvColor.Canvas else labelColor, maxLines = 1)
        }
    }
}

// ---------------------------------------------------------------------------- panels, dialogs

/**
 * Spec §16.8. 640 wide standard, 720 for sources while playing, pinned to the right edge over a
 * canvas 40% scrim. LEFT or BACK closes it; RIGHT does nothing.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SidePanel(
    header: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = TvGeom.SidePanelWidth,
    slideIn: Boolean = true,
    panelColor: Color = TvColor.Elevated,
    closeOnLeft: Boolean = true,
    content: @Composable () -> Unit,
) {
    val panelWidthPx = with(LocalDensity.current) { width.toPx() }
    val reduceMotion = LocalReduceMotion.current
    val slide = remember { Animatable(if (slideIn && !reduceMotion) panelWidthPx else 0f) }
    LaunchedEffect(Unit) {
        if (slideIn) slide.animateTo(0f, tween(if (reduceMotion) 0 else TvMotion.PanelSlideMillis, easing = TvMotion.Out))
    }
    Box(modifier = modifier.fillMaxSize().background(TvColor.Canvas.copy(alpha = 0.52f))) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(width)
                .fillMaxHeight()
                .then(if (slideIn) Modifier.graphicsLayer { translationX = slide.value } else Modifier)
                .clip(RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp))
                .background(panelColor)
                .border(1.dp, Color.White.copy(alpha = 0.10f),
                    RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp))
                .focusProperties { if (!closeOnLeft) exit = { FocusRequester.Cancel } }
                .focusGroup()
                .semantics {
                    paneTitle = header
                    isTraversalGroup = true
                }
                .onKeyEvent { event ->
                    // LEFT on key down, ahead of the focus search; BACK belongs to the screen's
                    // own back handler, which knows what else is stacked over the video.
                    if (closeOnLeft && event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                        onClose()
                        true
                    } else {
                        false
                    }
                }
                .padding(start = 40.dp, end = 40.dp, top = 60.dp),
        ) {
            Text(header, style = TvType.PanelHeader.copy(fontSize = 32.sp, lineHeight = 40.sp),
                color = TvColor.TextPrimary)
            Spacer(Modifier.height(48.dp))
            content()
        }
    }
}

/** One row in a [SidePanel]: 560 x 72, radius 10, with a tick on the chosen row. */
@Composable
internal fun SidePanelRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRing: Boolean = false,
    width: Dp = 560.dp,
    height: Dp = 72.dp,
) {
    TvFocusable(
        onClick = onClick,
        modifier = modifier,
        accessibleLabel = label,
        selected = selected,
        cornerRadius = 10.dp,
        focusScale = 1f,
        focusRing = focusRing,
    ) { focused ->
        Row(
            modifier = Modifier
                .width(width)
                .height(height)
                .clip(TvShape.Control)
                .background(
                    when {
                        focused && !focusRing -> TvColor.TextPrimary
                        focused || selected -> TvColor.Elevated2
                        else -> Color.Transparent
                    },
                )
                .padding(horizontal = TvSpace.M),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = TvType.ControlLabel, color = if (focused && !focusRing) TvColor.Canvas else TvColor.TextPrimary, maxLines = 1, modifier = Modifier.weight(1f))
            if (selected) Text("✓", style = TvType.ControlLabel, color = if (focused && !focusRing) TvColor.Canvas else TvColor.TextPrimary)
        }
    }
}

/**
 * Spec §15.2. 720 x 320. The safe choice is on the left and focused; the destructive choice is on
 * the right. BACK is the safe choice, and the dialog never closes on its own.
 */
@Composable
internal fun TvDialog(
    title: String,
    body: String,
    safeLabel: String,
    destructiveLabel: String,
    onSafe: () -> Unit,
    onDestructive: () -> Unit,
    destructive: Boolean = true,
) {
    val safeFocus = remember { FocusRequester() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TvColor.Canvas.copy(alpha = 0.60f))
            .focusGroup()
            .semantics {
                paneTitle = title
                isTraversalGroup = true
            }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && event.key == Key.Back) {
                    onSafe()
                    true
                } else {
                    false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                // Height follows the content. A fixed 320 left a 110 px empty band under the
                // buttons of every one-line dialog.
                .width(720.dp)
                .clip(TvShape.Panel)
                .background(TvColor.Elevated)
                .border(1.dp, TvColor.Border, TvShape.Panel)
                .padding(start = 40.dp, end = 40.dp, top = 44.dp, bottom = 44.dp),
        ) {
            Text(title, style = TvType.PlateTitle, color = TvColor.TextPrimary, maxLines = 1)
            Spacer(Modifier.height(12.dp))
            Text(body, style = TvType.Body, color = TvColor.TextSecondary, maxLines = 2)
            Spacer(Modifier.height(TvSpace.M))
            Row(horizontalArrangement = Arrangement.spacedBy(TvSpace.M)) {
                TvButton(
                    safeLabel,
                    onSafe,
                    kind = ButtonKind.Secondary,
                    modifier = Modifier.width(240.dp).focusRequester(safeFocus),
                )
                TvButton(
                    destructiveLabel,
                    onDestructive,
                    kind = if (destructive) ButtonKind.Destructive else ButtonKind.Primary,
                    modifier = Modifier.width(240.dp),
                )
            }
        }
    }
    // The safe choice takes focus, always. A dialog nothing can focus is a dead remote, and BACK
    // being the only way out of it is exactly the trap this shell exists to remove.
    LaunchedEffect(Unit) { runCatching { safeFocus.requestFocus() } }
}

/**
 * Spec §15.1. 640 x 88 at (640, 900). Life 2600 ms, never focusable, never stacked — a new toast
 * replaces the current one at once.
 */
@Composable
internal fun Toast(message: String, glyphColor: Color = TvColor.TextSecondary, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            // Spec §15.1: 640 wide at x 640, so the toast is centred on the 1920 canvas. At 720 it
            // ran 40 px past centre on each side and sat left of where the spec draws it.
            .width(640.dp)
            .heightIn(min = 88.dp)
            .clip(TvShape.Card)
            .background(TvColor.Elevated.copy(alpha = 0.96f))
            .border(1.dp, TvColor.Border, TvShape.Card)
            .semantics(mergeDescendants = true) {
                contentDescription = message
                liveRegion = LiveRegionMode.Polite
            }
            // Glyph at x 672 is 32 in from the toast's left edge; label at x 716 is the glyph's
            // 28 px plus a 16 px gap after that.
            .padding(horizontal = 32.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp).clip(TvShape.Badge).background(glyphColor))
        Spacer(Modifier.width(TvSpace.S))
        // Two lines: several messages in this app are full sentences (spec §15.4).
        Text(message, style = TvType.ControlLabel, color = TvColor.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------------------- feedback

/** Spec §16.11. The bar never animates. It redraws. */
@Composable
internal fun TvProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
    buffered: Float = 0f,
) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(height / 2)
    Box(modifier = modifier.height(height).clip(shape).background(Color.White.copy(alpha = 0.18f))) {
        if (buffered > 0f) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(buffered.coerceIn(0f, 1f)).background(Color.White.copy(alpha = 0.30f)))
        }
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(TvColor.Accent))
    }
}

/**
 * Spec §15.4 and §18.11. Only the first two rows of any screen sweep; everything below is a still
 * fill, because a screen full of moving blocks costs frames on the AFTDCT31 and reads as noise.
 */
@Composable
internal fun Skeleton(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = TvShape.Card,
    sweep: Boolean = false,
) {
    val reduceMotion = LocalReduceMotion.current
    if (!sweep || reduceMotion) {
        Box(modifier.clip(shape).background(TvColor.Elevated))
        return
    }
    // One clock for the whole screen and at most [SkeletonSweep.MAX_SWEEPING_BLOCKS] blocks on it. A
    // cold Home asked for seventeen independent infinite transitions, each recomposing its block at
    // 60 fps and each allocating a Brush and a colour-stop array inside the draw path.
    var sweeping by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val granted = SkeletonSweep.acquire()
        sweeping = granted
        onDispose { if (granted) SkeletonSweep.release() }
    }
    if (sweeping) {
        // Every sweeping block drives the clock with the same frame time, so the writes are
        // identical and the state notifies once per frame however many blocks are on screen.
        // Having them all drive it means the sweep cannot freeze when one block leaves.
        LaunchedEffect(Unit) {
            while (true) {
                withInfiniteAnimationFrameNanos { SkeletonSweep.tick(it) }
            }
        }
        Box(
            modifier
                .clip(shape)
                .background(TvColor.Elevated)
                // `drawWithCache` builds the band's Brush once per size change, and the phase is
                // read inside the draw lambda, so a frame costs one translate and one rect.
                .drawWithCache {
                    val band = size.width * SkeletonSweep.BAND_FRACTION
                    val brush = Brush.horizontalGradient(
                        0f to Color.Transparent,
                        0.5f to TvColor.Elevated2.copy(alpha = 0.4f),
                        1f to Color.Transparent,
                        startX = 0f,
                        endX = band,
                    )
                    val bandSize = Size(band, size.height)
                    onDrawBehind {
                        val centre = SkeletonSweep.phase.floatValue * size.width
                        translate(left = centre - band / 2f) { drawRect(brush, size = bandSize) }
                    }
                },
        )
    } else {
        Box(modifier.clip(shape).background(TvColor.Elevated))
    }
}

/**
 * The one shimmer clock, spec §15.4: a diagonal sweep of `elevated2` at 40%, 1600 ms, linear,
 * from −30% to 130% of the block's width.
 *
 * It is a process-wide object rather than a `CompositionLocal` because every screen that draws a
 * skeleton would otherwise have to remember to provide the host, and the one that forgot would be
 * the one that regressed.
 */
internal object SkeletonSweep {
    /** The band is 30% of the block, so its centre travels from −0.3 to 1.3. */
    const val BAND_FRACTION: Float = 0.3f
    private const val PHASE_START = -0.3f
    private const val PHASE_END = 1.3f

    /**
     * Spec §15.4 says the sweep runs on the hero and the first row. A cold Home read that as
     * every block in the first two rows and drew seventeen, which is about 1.4 million pixels of
     * gradient per frame on a fill-bound GPU. Six is the hero's three blocks plus the first three
     * posters — enough that the screen reads as loading, cheap enough to hold 60 fps.
     */
    const val MAX_SWEEPING_BLOCKS: Int = 6

    /** Read only inside a draw lambda. Reading it in composition puts the clock back in the frame. */
    val phase = androidx.compose.runtime.mutableFloatStateOf(PHASE_START)

    private var granted = 0

    /** Where the band's centre sits, as a fraction of the block's width, at [elapsedMillis]. */
    fun phaseAt(elapsedMillis: Long, periodMillis: Int = TvMotion.ShimmerSweepMillis): Float {
        if (periodMillis <= 0) return PHASE_START
        val wrapped = Math.floorMod(elapsedMillis, periodMillis.toLong()).toFloat()
        return PHASE_START + (PHASE_END - PHASE_START) * (wrapped / periodMillis)
    }

    fun tick(frameNanos: Long) {
        phase.floatValue = phaseAt(frameNanos / 1_000_000L)
    }

    /** True when this block got one of the [MAX_SWEEPING_BLOCKS] slots. */
    fun acquire(): Boolean {
        if (granted >= MAX_SWEEPING_BLOCKS) return false
        granted++
        return true
    }

    fun release() {
        if (granted > 0) granted--
    }

    /** Test seam: the slot count is process state and a test must not leak it to the next one. */
    internal fun resetForTest() {
        granted = 0
        phase.floatValue = PHASE_START
    }
}

/**
 * Spec §15.5. One shape for both. The copy names what would fill the screen, or what failed, and
 * gives one way forward. It never says "Oops", "Sorry" or "Please", and never ends in "!".
 */
@Composable
internal fun StateBlock(
    headline: String,
    line: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    actionModifier: Modifier = Modifier,
    announceAsError: Boolean = false,
) {
    Column(
        modifier = modifier
            .width(600.dp)
            .then(
                if (announceAsError) {
                    Modifier.semantics(mergeDescendants = true) {
                        liveRegion = LiveRegionMode.Assertive
                        contentDescription = "$headline. $line"
                    }
                } else {
                    Modifier
                },
            ),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(headline, style = TvType.ScreenTitle, color = TvColor.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(TvSpace.S))
        Text(line, style = TvType.Body, color = TvColor.TextSecondary, maxLines = 2)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(TvSpace.M))
            Row(horizontalArrangement = Arrangement.spacedBy(TvSpace.S)) {
                TvButton(actionLabel, onAction, kind = ButtonKind.Primary, modifier = actionModifier)
                if (secondaryLabel != null && onSecondary != null) {
                    TvButton(secondaryLabel, onSecondary, kind = ButtonKind.Ghost)
                }
            }
        }
    }
}

@Composable
internal fun EmptyState(
    headline: String,
    line: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    actionModifier: Modifier = Modifier,
) = StateBlock(headline, line, modifier, actionLabel, onAction, actionModifier = actionModifier)

@Composable
internal fun ErrorState(
    headline: String,
    line: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    actionModifier: Modifier = Modifier,
) = StateBlock(
    headline = headline,
    line = line,
    modifier = modifier,
    actionLabel = "Try again",
    onAction = onRetry,
    actionModifier = actionModifier,
    announceAsError = true,
)

// ---------------------------------------------------------------------------- artwork

/**
 * One piece of artwork, sized to the card that draws it (`client-data` `images/`).
 *
 * The placeholder is drawn underneath rather than swapped in, so a poster that never answers keeps
 * the block and its title and the row's geometry never changes. A broken-image glyph is never
 * drawn (spec §3.9.12).
 */
@Composable
internal fun TvArtwork(
    request: com.fourseveneightnine.tv.client.data.images.PosterRequest,
    title: String,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = TvShape.Card,
    showTitleWhenMissing: Boolean = true,
    contentScale: androidx.compose.ui.layout.ContentScale = androidx.compose.ui.layout.ContentScale.Fit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // The title is the placeholder, not a caption: it goes the moment the poster paints. Drawing
    // it under the image is not enough — a crossfading bitmap is translucent while it arrives, and
    // the words read straight through it.
    //
    // The flag starts from [PaintedArtwork], not from false. A LazyRow item that scrolls out of
    // the composed window loses its `remember`, so a poster walked ten cards right and ten back
    // came home with `painted = false` and flashed its title over the bitmap again. The cache
    // remembers the url, so re-entry is silent.
    val seen = remember(request.url) { PaintedArtwork.wasPainted(request.url) }
    val painted = remember(request.url) { mutableStateOf(seen) }
    // A url this session has already drawn must not fade in again. Coil skips its crossfade for a
    // memory-cache hit but runs the full 190 ms for a disk hit, and on this box's small heap a row
    // walked twice is a disk hit. First paint keeps the fade; every re-entry is instant.
    val model = remember(request, seen) {
        val base = request.toImageRequest(context)
        if (seen) base.newBuilder().crossfade(false).build() else base
    }
    // The placeholder fill goes when the poster paints: an opaque bitmap over an opaque fill is
    // a full card of overdraw, times every card on screen.
    Box(modifier.clip(shape).then(if (painted.value) Modifier else Modifier.background(TvColor.PosterPlaceholder))) {
        if (showTitleWhenMissing && !painted.value) {
            Text(
                text = title,
                style = TvType.CardTitle,
                color = TvColor.TextSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = TvSpace.S),
            )
        }
        if (request.url != null) {
            coil3.compose.AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = contentScale,
                onSuccess = {
                    painted.value = true
                    PaintedArtwork.markPainted(request.url)
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * The urls this app session has already drawn once.
 *
 * It is an app-level LRU rather than per-item state, because the flag has to outlive the composed
 * window of a `LazyRow`: that is the whole defect. It holds urls, not bitmaps, so 512 of them is a
 * few tens of kilobytes — about two full screens of posters in each direction, which is further
 * than a viewer walks before the memory cache has answered anyway.
 *
 * A false negative costs one crossfade. A false positive is impossible: nothing writes a url here
 * until Coil has reported that url painted.
 */
internal object PaintedArtwork {
    const val MAX_URLS: Int = 512

    // accessOrder = true makes it an LRU: a get moves the entry to the young end.
    private val seen = object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean =
            size > MAX_URLS
    }

    fun wasPainted(url: String?): Boolean = url != null && seen[url] == true

    fun markPainted(url: String?) {
        if (url != null) seen[url] = true
    }

    /** How many urls are held. For the test that proves the LRU evicts. */
    fun size(): Int = seen.size

    internal fun resetForTest() = seen.clear()
}

/**
 * [TvFocusable] plus the two things a browse card needs: long-OK, and a way to tell the screen
 * which card the ring is on so the hero can follow it.
 *
 * Long-OK is measured between key down and key up rather than taken from the platform's long-press
 * flag, because a television remote's repeat rate decides when that flag fires and the two boxes in
 * this fleet disagree about it.
 */
@Composable
internal fun TvFocusableCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accessibleLabel: String? = null,
    accessibleRole: Role = Role.Button,
    selected: Boolean? = null,
    checked: Boolean? = null,
    stateDescription: String? = null,
    clickLabel: String? = null,
    longClickLabel: String = "More options",
    customActions: List<CustomAccessibilityAction> = emptyList(),
    onLongClick: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null,
    cornerRadius: Dp = 12.dp,
    focusScale: Float = TvGeom.FocusScale,
    focusRing: Boolean = true,
    content: @Composable (focused: Boolean) -> Unit,
) {
    // A local state written by `onFocusChanged`, not `collectIsFocusedAsState`. The interaction
    // source version costs one coroutine and one flow collection per card, and a browse row holds
    // forty of them; the ring needs one boolean.
    var focused by remember { androidx.compose.runtime.mutableStateOf(false) }
    val pressedAt = remember { longArrayOf(0L) }
    Box(
        modifier = modifier
            .tvFocusScale(focused, focusScale)
            .tvFocusRing(focused && focusRing, cornerRadius)
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
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused?.invoke()
            }
            .focusable(enabled = enabled)
            .onKeyEvent { event ->
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> when (event.type) {
                        KeyEventType.KeyDown -> {
                            if (!enabled) return@onKeyEvent true
                            if (pressedAt[0] == 0L) pressedAt[0] = System.currentTimeMillis()
                            true
                        }
                        KeyEventType.KeyUp -> {
                            val held = System.currentTimeMillis() - pressedAt[0]
                            pressedAt[0] = 0L
                            if (onLongClick != null && held >= LONG_OK_MILLIS) onLongClick() else onClick()
                            true
                        }
                        else -> false
                    }
                    else -> false
                }
            },
    ) {
        content(focused)
    }
}

/** Spec §3.5: long-OK on a browse card opens Add to collection. */
internal const val LONG_OK_MILLIS = 600L

// ---------------------------------------------------------------------------- long-OK

/**
 * A focusable that answers OK and long-OK, for the rows whose spec gives them both: an episode
 * card (§9.6 episode menu), a poster (§17 "add to collection") and a stream row (§10.6 row menu).
 *
 * Long-OK is measured from the key-down, and the up event that follows a long press is swallowed,
 * so one hold never fires both actions. Android's own repeat counter is not used: a TV remote's
 * repeat rate varies by brand, and the threshold has to be a wall-clock time or the same hold
 * means different things on two boxes.
 */
@Composable
internal fun TvFocusableWithMenu(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
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
    content: @Composable (focused: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val downAt = remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    Box(
        modifier = modifier
            .tvFocusScale(focused, focusScale)
            .tvFocusRing(focused && focusRing, cornerRadius)
            .semantics(mergeDescendants = true) {
                accessibleLabel?.let { contentDescription = it }
                role = accessibleRole
                selected?.let { this.selected = it }
                checked?.let { toggleableState = if (it) ToggleableState.On else ToggleableState.Off }
                stateDescription?.let { this.stateDescription = it }
                if (customActions.isNotEmpty()) this.customActions = customActions
                if (enabled) {
                    semanticOnClick(label = clickLabel) { onClick(); true }
                    semanticOnLongClick(label = longClickLabel) { onLongClick(); true }
                } else {
                    disabled()
                }
            }
            .focusable(enabled = enabled, interactionSource = interaction)
            .onKeyEvent { event ->
                val isOk = event.key == Key.DirectionCenter ||
                    event.key == Key.Enter ||
                    event.key == Key.NumPadEnter
                if (!isOk || !enabled) return@onKeyEvent false
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        if (downAt.longValue == 0L) {
                            downAt.longValue = android.os.SystemClock.elapsedRealtime()
                        }
                        true
                    }
                    KeyEventType.KeyUp -> {
                        val held = android.os.SystemClock.elapsedRealtime() - downAt.longValue
                        downAt.longValue = 0L
                        if (held >= LONG_PRESS_MILLIS) onLongClick() else onClick()
                        true
                    }
                    else -> false
                }
            },
    ) {
        content(focused)
    }
}

/** Long enough not to fire on a firm press, short enough to find by accident once. */
internal const val LONG_PRESS_MILLIS = 500L
