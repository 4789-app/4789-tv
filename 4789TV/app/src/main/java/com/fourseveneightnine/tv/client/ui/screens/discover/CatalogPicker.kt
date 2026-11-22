package com.fourseveneightnine.tv.client.ui.screens.discover

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.client.ui.components.SidePanel
import com.fourseveneightnine.tv.client.ui.components.SidePanelRow
import com.fourseveneightnine.tv.client.ui.components.TvChip
import com.fourseveneightnine.tv.client.ui.components.TvSearchField
import com.fourseveneightnine.tv.client.ui.screens.search.VoiceSearchButton
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvType
import kotlinx.coroutines.launch

/** Which chip UP from the grid comes back to (spec §4.5: "the last used chip"). */
internal enum class ChipSlot { Type, Catalog, Genre, Letterboxd }

/** Spec §4.3. Groups are 36 px apart; inside a group the gap is 12 px. */
@Composable
internal fun DiscoverChips(
    state: DiscoverState,
    filterLabel: String = "Filters & sort",
    filtersActive: Boolean = false,
    onType: (DiscoverType) -> Unit,
    onOpenCatalogPanel: () -> Unit,
    onOpenGenrePanel: () -> Unit,
    onOpenLetterboxd: () -> Unit,
    chipFocuses: List<FocusRequester>,
    onFocused: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    fun navigation(index: Int) = Modifier.focusRequester(chipFocuses[index])
        .onFocusChanged { if (it.isFocused) onFocused(index) }
        .focusProperties {
            left = chipFocuses.getOrNull(index - 1) ?: FocusRequester.Default
            right = chipFocuses.getOrNull(index + 1) ?: FocusRequester.Cancel
        }
    Row(modifier.height(56.dp).focusGroup(), verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DiscoverType.entries.forEach { type ->
                TvChip(
                    label = type.label,
                    selected = state.type == type,
                    onClick = { onType(type) },
                    modifier = navigation(type.ordinal),
                )
            }
        }
        Spacer(Modifier.width(36.dp))
        TvChip(
            // A chip with nothing chosen shows its rest fill. Pinning it to `selected` made the
            // Catalog chip read as picked before the viewer had picked anything.
            label = "Catalog: ${state.catalogLabel} ▾",
            selected = state.selected != null,
            onClick = onOpenCatalogPanel,
            modifier = Modifier
                .widthIn(min = 240.dp, max = 520.dp)
                .then(navigation(DiscoverType.entries.size)),
        )
        Spacer(Modifier.width(12.dp))
        TvChip(
            label = "$filterLabel ▾",
            selected = filtersActive || state.genre != null,
            onClick = onOpenGenrePanel,
            modifier = Modifier
                .widthIn(min = 220.dp, max = 400.dp)
                .then(navigation(DiscoverType.entries.size + 1)),
        )
        Spacer(Modifier.width(12.dp))
        TvChip(
            label = "Letterboxd",
            selected = state.selected?.source == CatalogSource.Letterboxd,
            onClick = onOpenLetterboxd,
            modifier = navigation(DiscoverType.entries.size + 2),
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
internal fun CollapsedChipsBand(parts: List<StripPart>,
    focusedCard: () -> com.fourseveneightnine.tv.client.ui.screens.home.HomeCard? = { null },
    modifier: Modifier = Modifier) {
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
    Column(modifier.fillMaxWidth().height(72.dp)) {
        Text(text = line, style = TvType.Meta, color = TvColor.TextSecondary,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        focusedCard()?.let { card ->
            Text(listOfNotNull(card.title, card.year?.toString(), card.genre,
                card.runtimeMinutes?.let { "$it min" }).joinToString(" · "),
                style = TvType.CardTitle, color = TvColor.TextPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

internal data class CatalogHomeAction(val label: String, val apply: () -> Unit)

/**
 * Spec §4.4. Catalogs grouped by add-on, the current choice focused on entry so that OK with no
 * movement is a safe no-op.
 *
 * The owner's box declares 342 catalogs, so rows stay lazy. Scroll the intended choice into view
 * before requesting its focus. The catalog rows stay visible while the viewer types.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun CatalogPanel(
    choices: List<DiscoverCatalogChoice>,
    selectedKey: String?,
    query: String,
    onQuery: (String) -> Unit,
    source: CatalogSource?,
    onSource: (CatalogSource?) -> Unit,
    listState: LazyListState = rememberLazyListState(),
    focusKey: String? = null,
    onFocused: (String) -> Unit = {},
    showTypes: Boolean = false,
    onPick: (String) -> Unit,
    homeActionFor: ((DiscoverCatalogChoice) -> CatalogHomeAction?)? = null,
    active: Boolean = true,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val searchFocus = remember { FocusRequester() }
    val actionFocus = remember { FocusRequester() }
    val entryFocus = remember { FocusRequester() }
    var enterList by remember { mutableStateOf(true) }
    var homeMenu by remember { mutableStateOf<Pair<DiscoverCatalogChoice, CatalogHomeAction>?>(null) }
    val matches = remember(choices, query, source) { DiscoverPlan.filterCatalogs(choices, query, source) }
    val rows = remember(matches) { DiscoverPlan.panelRows(matches) }
    val entryKey = (focusKey ?: selectedKey)?.takeIf { key -> matches.any { it.key == key } }
        ?: matches.firstOrNull()?.key

    fun closeHomeMenu() {
        homeMenu = null
        enterList = true
    }

    BackHandler(enabled = active && homeMenu != null, onBack = ::closeHomeMenu)
    LaunchedEffect(active) {
        if (active && homeMenu == null) enterList = true
    }
    SidePanel(header = "Catalog", closeOnLeft = false, onClose = onClose) {
        val menu = homeMenu
        if (menu != null) {
            val (choice, action) = menu
            Text(choice.catalogName, style = TvType.ControlLabel, color = TvColor.TextPrimary, maxLines = 2)
            Spacer(Modifier.height(18.dp))
            SidePanelRow(label = action.label, selected = false, modifier = Modifier.focusRequester(actionFocus),
                onClick = { action.apply(); closeHomeMenu() })
            LaunchedEffect(menu) {
                withFrameNanos { }
                actionFocus.requestFocus()
            }
            return@SidePanel
        }
        Row(
            Modifier.onFocusChanged { if (it.hasFocus) enterList = false }.focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvSearchField("Search catalogs", query, onQuery = {
                enterList = false
                onQuery(it)
                scope.launch { listState.scrollToItem(0) }
            }, modifier = Modifier.weight(1f), focus = searchFocus, onDone = {
                if (entryKey != null) enterList = true
            })
            VoiceSearchButton(prompt = "Say a catalog or add-on name",
                modifier = Modifier.focusProperties { left = searchFocus },
                onQuery = { onQuery(it); enterList = true })
        }
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.focusGroup().onPreviewKeyEvent { event ->
                if (event.key != Key.DirectionDown || entryKey == null) false
                else {
                    if (event.type == KeyEventType.KeyDown) enterList = true
                    true
                }
            },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for (option in listOf(null, CatalogSource.Letterboxd, CatalogSource.Stremio)) {
                TvChip(
                    label = option?.label ?: "All",
                    selected = option == source,
                        onClick = { enterList = false; onSource(option) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        val count = when {
            matches.isEmpty() && query.isBlank() && source == CatalogSource.Letterboxd ->
                "No synced Letterboxd lists. Refresh catalogs in Settings or sync from your iPhone."
            matches.isEmpty() && query.isBlank() && source == CatalogSource.Stremio -> "No browsable Stremio catalogs."
            matches.isEmpty() -> "No matching catalogs. Try another name."
            matches.size == 1 -> "1 catalog"
            else -> "${matches.size} catalogs"
        }
        Text(count, style = TvType.Meta, color = TvColor.TextSecondary)
        if (homeActionFor != null && matches.isNotEmpty()) {
            Text("Hold Select on a catalog for Den TV Home options",
                style = TvType.Meta, color = TvColor.TextMuted, maxLines = 1)
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier.heightIn(max = 620.dp).focusRestorer(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(rows, key = { it.key }) { row ->
                when (row) {
                    is CatalogPanelRow.Section -> Box(
                        Modifier.width(560.dp).height(40.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(row.source.label, style = TvType.ControlLabel, color = TvColor.TextPrimary, maxLines = 1)
                    }
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
                        modifier = Modifier
                            .onFocusChanged { if (it.isFocused) onFocused(row.choice.key) }
                            .focusProperties { right = searchFocus }
                            .then(if (row.choice.key == entryKey) Modifier.focusRequester(entryFocus) else Modifier),
                        onClick = { onPick(row.choice.key) },
                        onLongClick = homeActionFor?.let { actionFor ->
                            { actionFor(row.choice)?.let { homeMenu = row.choice to it } }
                        },
                    )
                }
            }
        }
    }
    LaunchedEffect(active, enterList, homeMenu, rows, entryKey) {
        if (!active || !enterList || homeMenu != null) return@LaunchedEffect
        val target = if (entryKey == null) searchFocus else {
            val index = DiscoverPlan.panelRowIndex(rows, entryKey)
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) listState.scrollToItem(index)
            entryFocus
        }
        repeat(12) {
            withFrameNanos { }
            if (runCatching { target.requestFocus() }.getOrDefault(false)) {
                enterList = false
                return@LaunchedEffect
            }
        }
        runCatching { searchFocus.requestFocus() }
        enterList = false
    }
}

/** Spec §4.4. "All genres" is the first row, and it is what clears the chip. */
@Composable
internal fun GenrePanel(
    genres: List<String>,
    selected: String?,
    allowAll: Boolean,
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
            if (allowAll) SidePanelRow(
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
