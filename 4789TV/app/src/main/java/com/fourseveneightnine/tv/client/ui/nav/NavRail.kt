package com.fourseveneightnine.tv.client.ui.nav

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick as semanticOnClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.R
import com.fourseveneightnine.tv.client.ui.theme.LocalReduceMotion
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvGeom
import com.fourseveneightnine.tv.client.ui.theme.TvMotion
import com.fourseveneightnine.tv.client.ui.theme.TvShape
import com.fourseveneightnine.tv.client.ui.theme.TvType

/** What the rail's bottom chip says, spec §1.4. The dot never pulses. */
internal sealed interface CastStatus {
    data object NoPhonePaired : CastStatus
    data class Idle(val receiverName: String) : CastStatus
    data object Connected : CastStatus
    data class Casting(val title: String?) : CastStatus
    data object Syncing : CastStatus
}

/**
 * The navigation rail, spec §1.
 *
 * It rests as a 184 px icon strip — a gradient, not a filled bar, so a bright Home backdrop stays
 * readable and is not cut in half by a hard edge — and opens to a 376 px panel. The shell shifts
 * the page enough to keep its content clear of the expanded rail.
 *
 * The rail remembers nothing of its own. On entry it focuses the item for the current screen; the
 * content side remembers its last focused card, and RIGHT or BACK restores it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun NavRail(
    current: TopLevel?,
    expanded: Boolean,
    profileName: String,
    profileAvatarKey: String,
    castStatus: CastStatus,
    onSelect: (TopLevel) -> Unit,
    onClose: () -> Unit,
    onProfileChipClick: () -> Unit,
    onCastChipClick: () -> Unit,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit = {},
) {
    val entryFocus = remember { FocusRequester() }
    // Read inside focusProperties at focus-change time, so every way out of the rail drops the
    // trap first and its own requestFocus hand-back can never be cancelled.
    val trap = remember { mutableStateOf(false) }
    SideEffect { trap.value = expanded }
    fun release(action: () -> Unit): () -> Unit = { trap.value = false; action() }
    val close = release(onClose)

    // The rail's own box never changes size. Only [RailBackground] reads the width animation, so
    // one small node recomposes and re-lays-out per frame instead of the logo, six items, the
    // divider and the cast chip. Measured on the onn 4K Pro before the split: 74% janky frames at
    // a 65 ms median across the 180 ms tween, with the GPU at 2 ms.
    // A collapsed rail never holds focus. Focus can land here from the rescue net, from a BACK
    // that popped a drill-in, or from a geometric focus search; in every case the viewer must see
    // the rail they are now steering, so it opens (spec §1.1).
    Box(
        modifier = modifier
            .width(TvGeom.RailExpanded)
            .fillMaxHeight()
            .onFocusChanged { if (it.hasFocus && !expanded) onOpen() }
            // An open rail keeps the remote. Without this, DOWN from Calendar found the page's
            // own button behind the scrim and the viewer steered a page they could not see.
            // RIGHT and BACK still close it through onClose, which hands focus back explicitly.
            .focusProperties { if (trap.value) exit = { FocusRequester.Cancel } }
            .focusGroup(),
    ) {
        RailBackground(expanded)

        // The 4789 mark at (116, 66), 48 x 48. It does not animate.
        Image(
            painter = painterResource(R.drawable.mark_4789),
            contentDescription = null,
            modifier = Modifier.offset(x = 108.dp, y = 28.dp).size(64.dp),
        )

        TopLevel.entries.forEachIndexed { index, item ->
            val y = ITEM_Y[index]
            RailItem(
                item = item,
                expanded = expanded,
                isCurrent = item == current,
                modifier = Modifier
                    .offset(x = 96.dp, y = y.dp)
                    .then(if (item == current) Modifier.focusRequester(entryFocus) else Modifier),
                onSelect = release { onSelect(item) },
                onClose = close,
            )
        }

        // Settings sits below a hairline divider because it is a different kind of place.
        Box(
            Modifier
                // Midway between Calendar (540..628) and Settings (676): at 548 it ran through
                // the Calendar row and its focus pill.
                .offset(x = 112.dp, y = 656.dp)
                .width(if (expanded) 232.dp else 56.dp)
                .height(1.dp)
                .background(TvColor.Border),
        )

        ProfileChip(
            name = profileName,
            avatarKey = profileAvatarKey,
            expanded = expanded,
            modifier = Modifier.offset(x = 96.dp, y = 780.dp),
            onClick = release(onProfileChipClick),
            onClose = close,
        )

        CastChip(
            status = castStatus,
            expanded = expanded,
            modifier = Modifier.offset(x = 96.dp, y = 884.dp),
            onClick = release(onCastChipClick),
            onClose = close,
        )
    }

    LaunchedEffect(expanded) {
        if (expanded) runCatching { entryFocus.requestFocus() }
    }
}

/** The active household profile, immediately above the receiver/cast chip. */
@Composable
private fun ProfileChip(
    name: String,
    avatarKey: String,
    expanded: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier = modifier
            .width(if (expanded) TvGeom.RailPanelWidth else TvGeom.RailItem)
            .height(RailRowHeight)
            .clip(TvShape.Control)
            .background(if (focused) Color.White else Color.Transparent)
            .semantics(mergeDescendants = true) {
                contentDescription = profileNameLabel(name)
                role = Role.Button
                selected = true
                stateDescription = "Current profile"
                semanticOnClick(label = "Switch profile") { onClick(); true }
            }
            .focusProperties { canFocus = expanded }
            .focusable(interactionSource = interaction)
            .onKeyEvent { event ->
                when {
                    event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight -> {
                        onClose(); true
                    }
                    event.type == KeyEventType.KeyUp &&
                        event.key in setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter) -> {
                        onClick(); true
                    }
                    else -> false
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(TvGeom.RailItem, RailRowHeight), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(profileColor(avatarKey)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    name.trim().take(1).uppercase(),
                    style = TvType.CardTitle,
                    color = TvColor.Canvas,
                    maxLines = 1,
                )
            }
        }
        if (expanded) {
            Column(Modifier.padding(end = 16.dp)) {
                Text(name, style = TvType.CardTitle, color = if (focused) TvColor.Canvas else TvColor.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Switch profile", style = TvType.Meta, color = if (focused) TvColor.Canvas else TvColor.TextSecondary, maxLines = 1)
            }
        }
    }
}

private fun profileColor(key: String): Color = when (key.lowercase()) {
    "cyan" -> TvColor.Focus
    "amber" -> TvColor.Warning
    "rose" -> Color(0xFFE98AA8)
    "green" -> TvColor.Cached
    "owner" -> Color(0xFFB6A6FF)
    else -> Color(0xFF9D8CFF)
}

/**
 * The only part of the rail that animates: the panel grows from the 184 px strip to 376 px over
 * 180 ms. Collapsed it is a gradient rather than a filled bar, so over a bright Home backdrop the
 * icons stay readable and the backdrop is not cut in half by a hard edge (spec §1.8.1).
 *
 * Expanded it is the flat `elevated` token with a 1 px right border (spec §1.2). The border is the
 * whole reason the panel reads as a panel: without it the fill runs into poster column 1 and the
 * 80 px sliver beside it looks like a poster cut in half rather than one standing behind a menu.
 * A gradient from `elevated` to `elevated` was the same fill through a shader, which is a wasted
 * allocation and no edge at all.
 */
@Composable
private fun RailBackground(expanded: Boolean) {
    val reduceMotion = LocalReduceMotion.current
    val width by animateDpAsState(
        targetValue = if (expanded) TvGeom.RailExpanded else TvGeom.RailStrip,
        animationSpec = tween(if (reduceMotion) 0 else TvMotion.RailMillis, easing = TvMotion.Std),
        label = "railWidth",
    )
    val collapsedBrush = remember {
        Brush.horizontalGradient(
            0f to TvColor.Canvas,
            0.65f to TvColor.Canvas,
            1f to Color.Transparent,
        )
    }
    Box(Modifier.width(width).fillMaxHeight()) {
        if (expanded) {
            Box(Modifier.fillMaxSize().background(TvColor.Elevated))
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(TvColor.Border),
            )
        } else {
            Box(Modifier.fillMaxSize().background(collapsedBrush))
        }
    }
}

/** Item y positions. A 104 px pitch leaves room for the 88 px row and its exterior focus ring. */
private val ITEM_Y = intArrayOf(100, 194, 288, 382, 476, 570, 672)
private val RailRowHeight = 80.dp
private val RailIconSize = 40.dp

@Composable
private fun RailItem(
    item: TopLevel,
    expanded: Boolean,
    isCurrent: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val fill = when {
        focused -> Color.White
        isCurrent && expanded -> TvColor.Elevated
        else -> Color.Transparent
    }
    val ink = if (focused) TvColor.Canvas else if (isCurrent) TvColor.TextPrimary else TvColor.TextSecondary
    Row(
        modifier = modifier
            .width(if (expanded) TvGeom.RailPanelWidth else TvGeom.RailItem)
            .height(RailRowHeight)
            .clip(TvShape.Card)
            .background(fill)
            .semantics(mergeDescendants = true) {
                contentDescription = item.label
                role = Role.Button
                selected = isCurrent
                stateDescription = if (isCurrent) "Current screen" else "Not current"
                semanticOnClick(label = "Open ${item.label}") { onSelect(); true }
            }
            .focusProperties { canFocus = expanded }
            .focusable(interactionSource = interaction)
            .onKeyEvent { event ->
                // Directions are consumed on key DOWN so Compose's focus search never runs and
                // RIGHT closes the rail instead of walking out of it sideways.
                when {
                    event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight -> {
                        onClose(); true
                    }
                    event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft -> true
                    event.type == KeyEventType.KeyUp &&
                        event.key in setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter) -> {
                        onSelect(); true
                    }
                    else -> false
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(TvGeom.RailItem, RailRowHeight), contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(railIcon(item)),
                contentDescription = null,
                colorFilter = ColorFilter.tint(ink),
                modifier = Modifier.size(RailIconSize),
            )
        }
        if (expanded) {
            Text(
                text = item.label,
                style = TvType.ControlLabel,
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 20.dp),
            )
        }
    }
}

/**
 * Spec §1.4. Collapsed it is a glyph and a dot; expanded it is two lines. The dot turns cyan the
 * moment the phone connects even with the rail shut — the one signal a viewer needs without
 * opening anything.
 */
@Composable
private fun CastChip(
    status: CastStatus,
    expanded: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val dot = when (status) {
        CastStatus.NoPhonePaired -> TvColor.TextMuted
        is CastStatus.Idle -> TvColor.TextMuted
        CastStatus.Connected, is CastStatus.Casting -> TvColor.Focus
        CastStatus.Syncing -> TvColor.Focus.copy(alpha = 0.5f)
    }
    val lineOne = when (status) {
        CastStatus.NoPhonePaired -> "No phone paired"
        is CastStatus.Idle -> status.receiverName
        CastStatus.Connected -> "iPhone connected"
        is CastStatus.Casting -> "iPhone is playing"
        CastStatus.Syncing -> "Syncing"
    }
    val lineTwo = when (status) {
        CastStatus.NoPhonePaired -> "Open Pair and Sync"
        is CastStatus.Idle -> "Ready"
        CastStatus.Connected -> "Ready to play"
        is CastStatus.Casting -> status.title ?: "Playing"
        CastStatus.Syncing -> "Syncing"
    }
    Row(
        modifier = modifier
            .width(if (expanded) TvGeom.RailPanelWidth else TvGeom.RailItem)
            .height(RailRowHeight)
            .clip(TvShape.Control)
            // Collapsed and unfocused it has no tile: a filled tile read as the focused item.
            .background(if (focused) Color.White else if (expanded) TvColor.Elevated else Color.Transparent)
            .semantics(mergeDescendants = true) {
                contentDescription = "Cast and receiver"
                role = Role.Button
                stateDescription = "$lineOne. $lineTwo"
                semanticOnClick(label = "Open cast status") { onClick(); true }
            }
            .focusProperties { canFocus = expanded }
            .focusable(interactionSource = interaction)
            .onKeyEvent { event ->
                when {
                    event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight -> {
                        onClose(); true
                    }
                    event.type == KeyEventType.KeyUp &&
                        event.key in setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter) -> {
                        onClick(); true
                    }
                    else -> false
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (expanded) {
            // Same icon column as the rail items above, so the glyphs line up down the rail.
            Box(Modifier.size(TvGeom.RailItem, RailRowHeight), contentAlignment = Alignment.Center) {
                Image(
                    painter = painterResource(R.drawable.ic_rail_cast),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(if (focused) TvColor.Canvas else TvColor.TextSecondary),
                    modifier = Modifier.size(RailIconSize),
                )
                Box(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).size(8.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape).background(dot),
                )
            }
            Column(Modifier.padding(end = 16.dp)) {
                Text(lineOne, style = TvType.CardTitle, color = if (focused) TvColor.Canvas else TvColor.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(lineTwo, style = TvType.Meta, color = if (focused) TvColor.Canvas else TvColor.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Column(Modifier.width(TvGeom.RailItem), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painter = painterResource(R.drawable.ic_rail_cast),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(TvColor.TextSecondary),
                    modifier = Modifier.size(RailIconSize),
                )
                Spacer(Modifier.height(6.dp))
                Box(Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(dot))
            }
        }
    }
}

private fun profileNameLabel(name: String): String = "Profile, $name"

/** Filled 24 px glyphs. The old canvas strokes read as broken SVGs from the couch. */
private fun railIcon(item: TopLevel): Int = when (item) {
    TopLevel.Search -> R.drawable.ic_rail_search
    TopLevel.Home -> R.drawable.ic_rail_home
    TopLevel.LiveTv -> R.drawable.ic_rail_live_tv
    TopLevel.Discover -> R.drawable.ic_rail_discover
    TopLevel.Collections -> R.drawable.ic_rail_collections
    TopLevel.Calendar -> R.drawable.ic_rail_calendar
    TopLevel.Settings -> R.drawable.ic_rail_settings
}
