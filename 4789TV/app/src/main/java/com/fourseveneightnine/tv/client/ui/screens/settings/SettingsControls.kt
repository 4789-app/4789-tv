package com.fourseveneightnine.tv.client.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.client.ui.components.SidePanel
import com.fourseveneightnine.tv.client.ui.components.SidePanelRow
import com.fourseveneightnine.tv.client.ui.components.TvFocusable
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvShape
import com.fourseveneightnine.tv.client.ui.theme.TvSpace
import com.fourseveneightnine.tv.client.ui.theme.TvType

/** The compact two-pane shell consumes the 5% safe box exactly at 1080p. */
internal val SettingsListWidth: Dp = 440.dp
internal val SettingsPaneWidth: Dp = 1130.dp
internal val SettingsPanePadding: Dp = 32.dp
internal val PaneWidth: Dp = SettingsPaneWidth - SettingsPanePadding * 2

/**
 * One settings row, spec §14.7: label on the left, value on the right, and OK does the one thing
 * the row is for. The whole row takes the ring; the value is never a separate focus target.
 */
@Composable
internal fun SettingRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    note: String? = null,
    valueColor: Color = TvColor.TextSecondary,
    height: Dp = 72.dp,
) {
    Column {
        TvFocusable(onClick = onClick, modifier = modifier, cornerRadius = 10.dp, focusScale = 1f) { focused ->
            Row(
                modifier = Modifier
                    .width(PaneWidth)
                    .heightIn(min = height)
                    .clip(TvShape.Control)
                    .background(if (focused) TvColor.Elevated2 else TvColor.Elevated)
                    .padding(horizontal = TvSpace.M, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    style = TvType.ControlLabel,
                    color = TvColor.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    value,
                    style = TvType.Meta,
                    color = valueColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 360.dp),
                )
            }
        }
        if (note != null) {
            Text(
                note,
                style = TvType.Meta,
                color = TvColor.TextMuted,
                // 12 px on top clears the focused row's exterior ring, which the note used to touch.
                modifier = Modifier.width(PaneWidth).padding(start = TvSpace.M, end = TvSpace.M, top = 12.dp, bottom = 4.dp),
            )
        }
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
            .width(PaneWidth)
            .heightIn(min = height)
            .padding(horizontal = TvSpace.M, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = TvType.Body,
            color = TvColor.TextMuted,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = TvType.data(24),
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 400.dp),
        )
    }
}

/** A line of explanation under a row, spec §14.7. Never a warning colour. */
@Composable
internal fun PaneNote(text: String) {
    Text(
        text,
        style = TvType.Meta,
        color = TvColor.TextMuted,
        modifier = Modifier.width(PaneWidth).padding(horizontal = TvSpace.M, vertical = 4.dp),
    )
}

@Composable
internal fun PaneTitle(text: String) {
    Text(text, style = TvType.PanelHeader, color = TvColor.TextPrimary, maxLines = 2)
}

/** The 12–14 px dot that says how a source is behaving (spec §14.5). */
@Composable
internal fun StatusDot(color: Color, size: Dp = 14.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(TvShape.Badge).background(color))
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

/** Spec §17: a side panel is a list of choices. LEFT or BACK closes it; RIGHT does nothing. */
@Composable
internal fun ChoicePanel(
    header: String,
    options: List<String>,
    selected: String?,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val focusIndex = ChoiceFocus.initialIndex(options, selected)
    SidePanel(header = header, onClose = onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(TvSpace.XS)) {
            options.forEachIndexed { index, option ->
                SidePanelRow(
                    label = option,
                    selected = option == selected,
                    onClick = { onPick(option) },
                    modifier = if (index == focusIndex) Modifier.focusRequester(focus) else Modifier,
                )
            }
        }
    }
    // The rows are a plain Column, never a lazy list, so the requester is always attached by the
    // time this runs. Keyed on the header: a second panel replaces the first in place.
    LaunchedEffect(header) { runCatching { focus.requestFocus() } }
}
