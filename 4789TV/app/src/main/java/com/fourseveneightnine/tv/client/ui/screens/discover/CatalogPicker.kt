package com.fourseveneightnine.tv.client.ui.screens.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.client.ui.components.SidePanel
import com.fourseveneightnine.tv.client.ui.components.SidePanelRow
import com.fourseveneightnine.tv.client.ui.components.TvChip
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvShape
import com.fourseveneightnine.tv.client.ui.theme.TvType

/** Which chip UP from the grid comes back to (spec §4.5: "the last used chip"). */
internal enum class ChipSlot { Type, Catalog, Genre }

/** Spec §4.3. Groups are 36 px apart; inside a group the gap is 12 px. */
@Composable
internal fun DiscoverChips(
    state: DiscoverState,
    onType: (DiscoverType) -> Unit,
    onOpenCatalogPanel: () -> Unit,
    onOpenGenrePanel: () -> Unit,
    chipFocus: FocusRequester,
    focusSlot: ChipSlot,
    modifier: Modifier = Modifier,
) {
    Row(modifier.height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DiscoverType.entries.forEach { type ->
                val holdsFocus = focusSlot == ChipSlot.Type && state.type == type
                TvChip(
                    label = type.label,
                    selected = state.type == type,
                    onClick = { onType(type) },
                    modifier = if (holdsFocus) Modifier.focusRequester(chipFocus) else Modifier,
                )
            }
        }
        Spacer(Modifier.width(36.dp))
        TvChip(
            // A chip with nothing chosen shows its rest fill. Pinning it to `selected` made the
            // Catalog chip read as picked before the viewer had picked anything.
            label = truncate(state.catalogLabel, 28),
            selected = state.selected != null,
            onClick = onOpenCatalogPanel,
            modifier = Modifier
                .widthIn(min = 240.dp, max = 520.dp)
                .then(if (focusSlot == ChipSlot.Catalog) Modifier.focusRequester(chipFocus) else Modifier),
        )
        Spacer(Modifier.width(12.dp))
        TvChip(
            label = truncate(state.genreLabel, 20),
            selected = state.genre != null,
            onClick = onOpenGenrePanel,
            modifier = Modifier
                .widthIn(min = 220.dp, max = 400.dp)
                .then(if (focusSlot == ChipSlot.Genre) Modifier.focusRequester(chipFocus) else Modifier),
        )
    }
}

/**
 * Spec §4.5. A readout, not a control: it cannot take focus, and UP is the way back to the chips.
 *
 * Three inks in one line: the chosen values stand out, the words that name them sit back, and the
 * separators sit back further still.
 */
@Composable
internal fun CollapsedChipsBand(parts: List<StripPart>, modifier: Modifier = Modifier) {
    val line = remember(parts) {
        buildAnnotatedString {
            parts.forEach { part ->
                val ink = when (part.ink) {
                    StripInk.Value -> TvColor.TextPrimary
                    StripInk.Word -> TvColor.TextSecondary
                    StripInk.Separator -> TvColor.TextMuted
                }
                withStyle(SpanStyle(color = ink)) { append(part.text) }
            }
        }
    }
    Box(modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.CenterStart) {
        Text(
            text = line,
            style = TvType.CardTitle,
            color = TvColor.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Spec §4.4. Catalogs grouped by add-on, the current choice focused on entry so that OK with no
 * movement is a safe no-op.
 *
 * A lazy list, with no focus requester inside an item: the owner's box declares 342 catalogs, and a
 * plain Column composed a focusable row for every one of them before the panel could draw. The
 * requester sits on the list itself, which is always composed, and the list is scrolled to the
 * current choice first so that is where `focusRestorer` puts the ring.
 */
@Composable
internal fun CatalogPanel(
    choices: List<DiscoverCatalogChoice>,
    selectedKey: String?,
    showTypes: Boolean = false,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
) {
    val listFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val rows = remember(choices) { DiscoverPlan.panelRows(choices) }
    SidePanel(header = "Catalog", onClose = onClose) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .heightIn(max = 820.dp)
                .focusRequester(listFocus)
                .focusRestorer()
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(rows, key = { it.key }) { row ->
                when (row) {
                    is CatalogPanelRow.Group -> Box(
                        Modifier.width(560.dp).height(40.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(row.addonName, style = TvType.Meta, color = TvColor.TextMuted, maxLines = 1)
                    }
                    is CatalogPanelRow.Choice -> SidePanelRow(
                        label = if (showTypes) {
                            val type = when (row.choice.type) {
                                "movie" -> "Movies"
                                "series" -> "Series"
                                "anime" -> "Anime"
                                else -> row.choice.type
                            }
                            "${row.choice.catalogName} · $type"
                        } else row.choice.catalogName,
                        selected = row.choice.key == selectedKey,
                        onClick = { onPick(row.choice.key) },
                    )
                }
            }
        }
    }
    LaunchedEffect(selectedKey, rows.size) {
        runCatching { listState.scrollToItem(DiscoverPlan.panelRowIndex(rows, selectedKey)) }
        runCatching { listFocus.requestFocus() }
    }
}

/** Spec §4.4. "All genres" is the first row, and it is what clears the chip. */
@Composable
internal fun GenrePanel(
    genres: List<String>,
    selected: String?,
    onPick: (String?) -> Unit,
    onClose: () -> Unit,
) {
    val entryFocus = remember { FocusRequester() }
    val scroll = rememberScrollState()
    SidePanel(header = "Genre", onClose = onClose) {
        Column(
            modifier = Modifier.heightIn(max = 820.dp).verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SidePanelRow(
                label = "All genres",
                selected = selected == null,
                onClick = { onPick(null) },
                modifier = if (selected == null) Modifier.focusRequester(entryFocus) else Modifier,
            )
            genres.forEach { genre ->
                SidePanelRow(
                    label = genre,
                    selected = genre == selected,
                    onClick = { onPick(genre) },
                    modifier = if (genre == selected) Modifier.focusRequester(entryFocus) else Modifier,
                )
            }
        }
    }
    LaunchedEffect(selected, genres.size) { runCatching { entryFocus.requestFocus() } }
}

/** Spec §4.10: the catalog chip truncates at 28 characters, the genre chip at 20. */
internal fun truncate(value: String, limit: Int): String =
    if (value.length <= limit) value else value.take(limit - 1).trimEnd() + "…"
