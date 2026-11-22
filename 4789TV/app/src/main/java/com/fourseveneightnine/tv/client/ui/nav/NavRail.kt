package com.fourseveneightnine.tv.client.ui.nav

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.updateTransition
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import com.fourseveneightnine.tv.client.ui.components.TvGlassArtwork
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import com.fourseveneightnine.tv.client.ui.components.tvFocusRing
import com.fourseveneightnine.tv.client.ui.components.tvGlassPanel
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
 * A small edge notch marks the closed menu. The open panel overlays the stationary page.
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
    glassArtwork: () -> TvGlassArtwork? = { null },
) {
    val entryFocus = remember { FocusRequester() }
    val reduceMotion = LocalReduceMotion.current
    val transition = updateTransition(expanded, label = "rail")
    val reveal = transition.animateFloat(
        transitionSpec = { tween(if (reduceMotion) 0 else TvMotion.RailMillis, easing = TvMotion.Std) },
        label = "railReveal",
    ) { open -> if (open) 1f else 0f }
    val glassHome = current == TopLevel.Home
    // Read inside focusProperties so exiting can release the trap before restoring content.
    val trap = remember { mutableStateOf(false) }
    SideEffect { trap.value = expanded }
    fun release(action: () -> Unit): () -> Unit = { trap.value = false; action() }
    val close = release(onClose)

    Box(modifier.width(TvGeom.RailExpanded).fillMaxHeight()) {
        Box(
            Modifier.align(Alignment.CenterStart)
                .size(width = 12.dp, height = 64.dp)
                .graphicsLayer { alpha = 1f - reveal.value }
                .background(TvColor.TextPrimary.copy(alpha = 0.28f),
                    RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)),
        )
        if (expanded || transition.currentState) Box(
            modifier = Modifier.matchParentSize()
                // One edge reveals the background and labels while the rest of the page stays put.
                .drawWithCache {
                    onDrawWithContent {
                        clipRect(right = size.width * reveal.value) { this@onDrawWithContent.drawContent() }
                    }
                }
                .then(if (expanded) Modifier else Modifier.clearAndSetSemantics {})
                // An open rail keeps the remote. Without this, DOWN from Calendar found the page's
                // own button behind the scrim and the viewer steered a page they could not see.
                // RIGHT and BACK still close it through onClose, which hands focus back explicitly.
                .focusProperties { if (trap.value) exit = { FocusRequester.Cancel } }
                .focusGroup(),
        ) {
            Box(Modifier.matchParentSize().padding(horizontal = 16.dp, vertical = 18.dp)
                .tvGlassPanel(30.dp, backdrop = if (glassHome) glassArtwork() else null, opaqueBase = true))
            val dividerInset = 16.dp
            val logoSize = 64.dp
            // The mark shares the icon centerline.
            Image(
                painter = painterResource(R.drawable.brand_mark),
                contentDescription = null,
                modifier = Modifier.offset(x = TvGeom.RailIconCenter - logoSize / 2, y = 28.dp).size(logoSize),
            )

            TopLevel.entries.forEachIndexed { index, item ->
                val y = ITEM_Y[index]
                RailItem(
                    item = item,
                    expanded = expanded,
                    isCurrent = item == current,
                    modifier = Modifier
                        .offset(x = TvGeom.RailInset, y = y.dp)
                        .then(if (item == current) Modifier.focusRequester(entryFocus) else Modifier),
                    onSelect = release { onSelect(item) },
                    onClose = close,
                )
            }

            // Settings sits below a hairline divider because it is a different kind of place.
            Box(
                Modifier
                    .offset(x = TvGeom.RailInset + dividerInset, y = 656.dp)
                    .width(TvGeom.RailPanelWidth - dividerInset * 2)
                    .height(1.dp)
                    .background(TvColor.Border),
            )

            ProfileChip(
                name = profileName,
                avatarKey = profileAvatarKey,
                expanded = expanded,
                modifier = Modifier.offset(x = TvGeom.RailInset, y = 780.dp),
                onClick = release(onProfileChipClick),
                onClose = close,
            )

            CastChip(
                status = castStatus,
                expanded = expanded,
                modifier = Modifier.offset(x = TvGeom.RailInset, y = 884.dp),
                onClick = release(onCastChipClick),
                onClose = close,
            )
        }
    }

    LaunchedEffect(expanded, current) {
        if (!expanded) return@LaunchedEffect
        repeat(3) {
            if (runCatching { entryFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            withFrameNanos { }
        }
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
            .width(TvGeom.RailPanelWidth)
            .height(RailRowHeight)
            .background(if (focused) TvColor.Elevated2 else Color.Transparent, TvShape.Control)
            .tvFocusRing(focused, 12.dp)
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
        Column(Modifier.clearAndSetSemantics {}.padding(end = 16.dp)) {
            Text(name, style = TvType.CardTitle, color = TvColor.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Switch profile", style = TvType.Meta, color = TvColor.TextSecondary, maxLines = 1)
        }
    }
}

private fun profileColor(key: String): Color = when (key.lowercase()) {
    "cyan" -> TvColor.CyanIdentity
    "amber" -> TvColor.Warning
    "rose" -> Color(0xFFE98AA8)
    "green" -> TvColor.Cached
    "owner" -> Color(0xFFB6A6FF)
    else -> Color(0xFF9D8CFF)
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
        focused -> TvColor.Elevated2
        isCurrent && expanded -> TvColor.Selection.copy(alpha = 0.12f)
        else -> Color.Transparent
    }
    val ink = if (isCurrent) TvColor.Selection else if (focused) TvColor.TextPrimary else TvColor.TextSecondary
    Row(
        modifier = modifier
            .width(TvGeom.RailPanelWidth)
            .height(RailRowHeight)
            .background(fill, TvShape.Card)
            .tvFocusRing(focused, 12.dp)
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
            if (isCurrent) Box(Modifier.align(Alignment.CenterStart).width(4.dp).height(32.dp)
                .clip(TvShape.Chip).background(TvColor.Selection))
            Image(
                painter = painterResource(railIcon(item)),
                contentDescription = null,
                colorFilter = ColorFilter.tint(ink),
                modifier = Modifier.size(RailIconSize),
            )
        }
        Text(
            text = item.label,
            style = TvType.ControlLabel,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clearAndSetSemantics {}.padding(end = 20.dp),
        )
    }
}

/**
 * Receiver and cast status at the bottom of the open menu.
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
        CastStatus.Connected, is CastStatus.Casting -> TvColor.Success
        CastStatus.Syncing -> TvColor.InProgress
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
            .width(TvGeom.RailPanelWidth)
            .height(RailRowHeight)
            .background(if (focused) TvColor.Elevated2 else Color.Transparent, TvShape.Control)
            .tvFocusRing(focused, 10.dp)
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
        Box(Modifier.size(TvGeom.RailItem, RailRowHeight), contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(R.drawable.ic_rail_cast),
                contentDescription = null,
                colorFilter = ColorFilter.tint(TvColor.TextSecondary),
                modifier = Modifier.size(RailIconSize),
            )
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).size(8.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape).background(dot),
            )
        }
        Column(Modifier.clearAndSetSemantics {}.padding(end = 16.dp)) {
            Text(lineOne, style = TvType.CardTitle, color = TvColor.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(lineTwo, style = TvType.Meta, color = TvColor.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
