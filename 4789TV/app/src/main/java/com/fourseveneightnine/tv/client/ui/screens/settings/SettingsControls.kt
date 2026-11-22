package com.fourseveneightnine.tv.client.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.client.ui.components.ButtonKind
import com.fourseveneightnine.tv.client.ui.components.SidePanel
import com.fourseveneightnine.tv.client.ui.components.SidePanelRow
import com.fourseveneightnine.tv.client.ui.components.TvButton
import com.fourseveneightnine.tv.client.ui.components.TvFocusable
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvGeom
import com.fourseveneightnine.tv.client.ui.components.tvGlassPanel
import com.fourseveneightnine.tv.client.ui.theme.TvShape
import com.fourseveneightnine.tv.client.ui.theme.TvSpace
import com.fourseveneightnine.tv.client.ui.theme.TvType

internal val SettingsListWidth = 366.dp
internal val SettingsPanePadding = 38.dp
internal val SettingsButtonHeight = 72.dp
internal val SettingsSurface = Color(0xFF141C29)
internal val SettingsFocusFill = Color(0xFF26354D)
internal val SettingsActionTint = Color(0xFF9DD5FF)
private val SettingsDialogWidth = 760.dp

/** A whole row is one remote target. Values and helper copy share its accessible label. */
@Composable
internal fun SettingRow(
    label: String,
    value: String = "",
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    note: String? = null,
    valueColor: Color = TvColor.TextSecondary,
    height: Dp = 78.dp,
    checked: Boolean? = null,
    prominent: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    icon: Int? = null,
    tint: Color = SettingsActionTint,
    tintIcon: Boolean = true,
) {
    TvFocusable(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        focusWhenDisabled = true,
        accessibleRole = if (checked == null) Role.Button else Role.Switch,
        checked = checked,
        cornerRadius = 16.dp,
        focusScale = 1f,
    ) { focused ->
        val fill = when {
            focused -> SettingsFocusFill
            prominent -> tint.copy(alpha = 0.15f)
            else -> Color.White.copy(alpha = 0.035f)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = height)
                .clip(TvShape.CardProminent)
                .background(fill)
                .padding(horizontal = 24.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let { SettingsGlyph(it, tint, tintIcon = tintIcon) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    label,
                    style = TvType.ControlLabel,
                    color = when {
                        !enabled -> TvColor.TextSecondary
                        destructive -> TvColor.Error
                        else -> TvColor.TextPrimary
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                note?.let { Text(it, style = TvType.Meta, color = TvColor.TextSecondary) }
            }
            if (checked != null) {
                Text(
                    if (checked) "On" else "Off",
                    style = TvType.Meta,
                    color = if (checked) tint else TvColor.TextSecondary,
                    modifier = Modifier.clearAndSetSemantics { },
                )
                Box(
                    Modifier.size(58.dp, 32.dp).clip(CircleShape)
                        .background(if (checked) tint else SettingsSurface)
                        .border(1.dp, if (checked) tint else TvColor.TextMuted, CircleShape)
                        .padding(5.dp)
                        .clearAndSetSemantics { },
                    contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
                ) {
                    Box(Modifier.size(20.dp).clip(CircleShape)
                        .background(if (checked) TvColor.OnAccent else TvColor.TextSecondary))
                }
            } else {
                if (value.isNotEmpty()) {
                    Text(
                        value,
                        style = TvType.Meta,
                        color = valueColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 360.dp),
                    )
                }
                Text("›", style = TvType.PlateTitle, color = TvColor.TextSecondary,
                    modifier = Modifier.clearAndSetSemantics { })
            }
        }
    }
}

@Composable
internal fun SettingsGlyph(icon: Int, tint: Color, size: Dp = 48.dp, tintIcon: Boolean = true) {
    Box(
        Modifier.size(size).clip(TvShape.Card).background(tint.copy(alpha = 0.13f)),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(icon), null, Modifier.size(size * 0.55f),
            colorFilter = if (tintIcon) ColorFilter.tint(tint) else null)
    }
}

@Composable
internal fun SettingsHeading(page: SettingsPage, title: String = page.title) {
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
        SettingsGlyph(page.icon, page.tint, 68.dp)
        Column {
            Text(title, style = TvType.ScreenTitle, color = TvColor.TextPrimary,
                modifier = Modifier.semantics { heading() })
            Text(page.blurb, style = TvType.Body, color = TvColor.TextSecondary)
        }
    }
    Spacer(Modifier.height(TvSpace.M))
}

@Composable
internal fun SettingsModal(
    title: String,
    onClose: () -> Unit,
    width: Dp = SettingsDialogWidth,
    content: @Composable () -> Unit,
) {
    val tvDensity = LocalDensity.current
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        // A Dialog gets its own window and resets density to Android's platform density.
        CompositionLocalProvider(LocalDensity provides tvDensity) {
            Box(
                Modifier.fillMaxSize().background(TvColor.CanvasBlack.copy(alpha = 0.80f))
                    .padding(horizontal = TvGeom.SafeLeft, vertical = TvGeom.SafeTop),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier.width(width).clip(TvShape.Panel).background(SettingsSurface)
                        .border(1.dp, TvColor.Border, TvShape.Panel)
                        .verticalScroll(rememberScrollState())
                        .padding(36.dp)
                        .semantics { paneTitle = title; isTraversalGroup = true },
                ) {
                    Text(title, style = TvType.PlateTitle, color = TvColor.TextPrimary,
                        modifier = Modifier.semantics { heading() })
                    Spacer(Modifier.height(16.dp))
                    content()
                }
            }
        }
    }
}

@Composable
internal fun SettingsDialogButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Secondary,
    enabled: Boolean = true,
) {
    TvButton(label, onClick, modifier.sizeIn(minWidth = 192.dp), kind = kind,
        enabled = enabled, height = SettingsButtonHeight)
}

@Composable
internal fun SettingsConfirmation(request: ConfirmRequest, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val safe = remember { FocusRequester() }
    SettingsModal(request.title, onCancel) {
        Text(request.body, style = TvType.Body, color = TvColor.TextSecondary)
        Spacer(Modifier.height(TvSpace.L))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TvSpace.S)) {
            SettingsDialogButton("Cancel", onCancel, Modifier.weight(1f).focusRequester(safe))
            SettingsDialogButton(request.destructiveLabel, onConfirm, Modifier.weight(1f),
                kind = if (request.destructive) ButtonKind.Destructive else ButtonKind.Primary)
        }
        LaunchedEffect(Unit) { safe.requestFocus() }
    }
}

/** A row that states a fact and takes no focus: spec §14.6 and §14.10. */
@Composable
internal fun ReadoutRow(
    label: String,
    value: String,
    valueColor: Color = TvColor.TextPrimary,
    height: Dp = 72.dp,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .padding(horizontal = TvSpace.M, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = TvType.Body,
            color = TvColor.TextSecondary,
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = TvType.data(24),
            color = valueColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 465.dp),
        )
    }
}

/** A line of explanation under a row, spec §14.7. Never a warning colour. */
@Composable
internal fun PaneNote(text: String) {
    Text(
        text,
        style = TvType.Meta,
        color = TvColor.TextSecondary,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    )
}

@Composable
internal fun PaneTitle(text: String) {
    Text(text, style = TvType.ShelfHeader, color = TvColor.TextPrimary,
        modifier = Modifier.semantics { heading() })
}

/**
 * Which row of a choice panel takes the ring when the panel opens.
 *
 * Pure, so the rule is testable without a receiver: the row that is already chosen, or the first
 * row when nothing is chosen yet. A panel that opens with nothing focused cannot be used at all —
 * `SidePanel`'s LEFT handler sits on a column that is not focused, so even LEFT does nothing and
 * BACK is the only key left.
 */
internal object ChoiceFocus {
    /** An index into [options], or -1 when there is nothing to focus. */
    fun initialIndex(options: List<String>, selected: String?): Int {
        if (options.isEmpty()) return -1
        val chosen = options.indexOfFirst { it == selected }
        return if (chosen >= 0) chosen else 0
    }
}

/** Short choices wrap their content. Long lists scroll in the same dialog. */
@Composable
internal fun ChoicePanel(
    header: String,
    options: List<String>,
    selected: String?,
    description: String?,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val focusIndex = ChoiceFocus.initialIndex(options, selected)
    SettingsModal(header, onClose) {
        description?.let {
            Text(it, style = TvType.Body, color = TvColor.TextSecondary)
            Spacer(Modifier.height(TvSpace.S))
        }
        Column(
            Modifier.fillMaxWidth().heightIn(max = 650.dp)
                .verticalScroll(rememberScrollState()).padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(TvSpace.XS),
        ) {
            options.forEachIndexed { index, option ->
                SidePanelRow(
                    label = option,
                    selected = option == selected,
                    onClick = { onPick(option) },
                    modifier = if (index == focusIndex) Modifier.focusRequester(focus) else Modifier,
                    width = 676.dp,
                    height = SettingsButtonHeight,
                )
            }
        }
        LaunchedEffect(header) {
            if (focusIndex >= 0) focus.requestFocus()
        }
    }
}
