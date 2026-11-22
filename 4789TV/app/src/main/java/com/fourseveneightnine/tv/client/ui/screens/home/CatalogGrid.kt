@file:Suppress("OPT_IN_USAGE")
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.fourseveneightnine.tv.client.ui.screens.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import com.fourseveneightnine.tv.R
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.home.LauncherRails
import com.fourseveneightnine.tv.client.ui.components.TvSearchField
import com.fourseveneightnine.tv.client.ui.components.CatalogAction
import com.fourseveneightnine.tv.client.ui.components.CatalogActionRail
import com.fourseveneightnine.tv.client.ui.components.CatalogRailFocus
import com.fourseveneightnine.tv.client.ui.components.CatalogRailGutter
import com.fourseveneightnine.tv.client.ui.components.TvButton
import com.fourseveneightnine.tv.client.ui.screens.collections.filterCollectionSearch
import com.fourseveneightnine.tv.client.ui.screens.discover.rememberCatalogFilters
import com.fourseveneightnine.tv.client.ui.screens.discover.CatalogFilters
import com.fourseveneightnine.tv.client.ui.screens.discover.CatalogFilterPanel
import com.fourseveneightnine.tv.client.ui.screens.discover.rememberCatalogBrowse
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.client.ui.components.StateBlock
import com.fourseveneightnine.tv.client.ui.components.EmptyState
import com.fourseveneightnine.tv.client.ui.screens.discover.DiscoverPlan
import com.fourseveneightnine.tv.client.ui.screens.discover.GridSkeleton
import com.fourseveneightnine.tv.client.ui.screens.discover.PosterGrid
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvGeom
import com.fourseveneightnine.tv.client.ui.theme.TvType
import kotlinx.coroutines.launch

/**
 * "See all" on a Home catalog row, shown in place over Home.
 *
 * Spec §3.5 sends "See all" to Discover pre-filtered to that catalog, and `ClientNav` has no route
 * that carries a catalog. Rather than widen a navigation contract three other agents are building
 * against, the whole catalog opens here as the same 6-column grid, over the screen the viewer was
 * already on. BACK closes it and the ring is where they left it.
 */
@Composable
internal fun CatalogGridOverlay(
    state: HomeCatalogState,
    returnCardKey: String?,
    onReturnFinished: (Boolean) -> Unit,
    onLoadMore: () -> Unit,
    onClose: () -> Unit,
    gridFocus: FocusRequester,
    savePanelOpen: Boolean,
    statusMessage: String? = null,
    returnLabel: String = "Back to shelves",
    onOpen: (HomeCard) -> Unit,
    onLongOpen: (HomeCard) -> Unit,
    onToast: ((String) -> Unit)? = null,
    active: Boolean = true,
) {
    val catalog = state.catalog
    val context = LocalContext.current
    val graph = context.clientGraph
    val scope = rememberCoroutineScope()
    val catalogKey = "${catalog.manifestUrl}|${catalog.type}|${catalog.catalogId}"
    var filters by rememberCatalogFilters(graph, catalogKey)
    var filterOpen by remember(catalog) { mutableStateOf(false) }
    var filtersUsed by remember(catalog) { mutableStateOf(false) }
    val filterFocus = remember { FocusRequester() }
    val actions = remember(catalog) { CatalogRailFocus() }
    var filtersFromRail by remember(catalog) { mutableStateOf(false) }
    var titleQuery by rememberSaveable(catalog) { mutableStateOf("") }
    var searching by remember(catalog) { mutableStateOf(false) }
    var actionReturnKey by remember(catalog) { mutableStateOf<String?>(null) }
    val browse = rememberCatalogBrowse(state.items, graph, filters, filterOpen || filters.active, scopeKey = catalogKey)
    val items = remember(browse.items, titleQuery) { browse.items.filterCollectionSearch(titleQuery, HomeCard::title) }
    val gridState = rememberLazyGridState()
    BackHandler(enabled = active && !filterOpen && !savePanelOpen, onBack = onClose)
    LaunchedEffect(catalog, active, items.isEmpty(), state.failed, filterOpen) {
        if (active && returnCardKey == null && !filterOpen && !filtersUsed && !searching) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { gridFocus.requestFocus() }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(TvColor.Background)
            // The overlay is modal. Without the fence a focus search that the grid did not answer
            // walks out to the Home rows behind it, which are drawn over and unreachable by eye.
            // BACK is the way out, and Home restores the card the viewer came from.
            // A save panel above this grid must be able to take focus.
            .focusProperties {
                if (savePanelOpen) canFocus = false
                exit = { direction ->
                    if (direction == androidx.compose.ui.focus.FocusDirection.Enter ||
                        direction == androidx.compose.ui.focus.FocusDirection.Exit) FocusRequester.Default else FocusRequester.Cancel
                }
            }
            .focusGroup()
            .padding(start = TvGeom.ContentLeft, top = 54.dp, end = 96.dp),
    ) {
        Text(catalog.name, style = TvType.ScreenTitle, color = TvColor.TextPrimary, maxLines = 1)
        Row(Modifier.offset(y = 62.dp), horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TvButton(filters.label, { filtersUsed = true; filterOpen = true }, modifier = Modifier
                .focusRequester(filterFocus).focusProperties { down = gridFocus })
            if (searching) TvSearchField("Search titles", titleQuery, { titleQuery = it },
                Modifier.width(540.dp).focusProperties { down = gridFocus }, openKeyboard = true,
                enabled = active && !filterOpen && !savePanelOpen, onClose = {
                    searching = false
                    scope.launch { withFrameNanos { }; actions.restoreAction() }
                })
        }
        val count = if (titleQuery.isEmpty()) statusMessage ?: browse.status(!state.exhausted) else "${items.size} matching titles"
        Text(count + if (items.isNotEmpty()) " · Right for catalog actions" else "",
            style = TvType.Meta, color = TvColor.TextMuted,
            modifier = Modifier.offset(y = 128.dp))
        // The same geometry as Discover: the grid ends at the safe bottom instead of running
        // 162 px past it (spec §4.2).
        Box(
            Modifier
                .fillMaxWidth()
                .padding(end = CatalogRailGutter)
                .height(DiscoverPlan.gridHeightDp(collapsed = false).dp)
                .offset(y = DiscoverPlan.gridTopDp(collapsed = false).dp),
        ) {
            when {
                state.loading && items.isEmpty() -> androidx.compose.foundation.layout.Column {
                    com.fourseveneightnine.tv.client.ui.components.TvButton(returnLabel, onClose,
                        kind = com.fourseveneightnine.tv.client.ui.components.ButtonKind.Secondary,
                        modifier = Modifier.focusRequester(gridFocus))
                    androidx.compose.foundation.layout.Spacer(Modifier.height(24.dp))
                    GridSkeleton()
                }
                state.failed -> StateBlock(
                    headline = "Couldn't load this catalog",
                    line = "The add-on did not answer. Retry or return to your shelves.",
                    actionLabel = "Retry", onAction = onLoadMore,
                    secondaryLabel = returnLabel, onSecondary = onClose,
                    actionModifier = Modifier.focusRequester(gridFocus),
                    announceAsError = true,
                )
                titleQuery.isNotEmpty() && items.isEmpty() -> StateBlock(
                    headline = "No matching titles",
                    line = "Search matches titles loaded in this catalog.",
                    actionLabel = if (state.exhausted) "Clear search" else "Load more titles",
                    onAction = if (state.exhausted) ({ titleQuery = "" }) else onLoadMore,
                    secondaryLabel = if (state.exhausted) null else "Clear search",
                    onSecondary = if (state.exhausted) null else ({ titleQuery = "" }),
                    actionModifier = Modifier.focusRequester(gridFocus),
                )
                filters.active && items.isEmpty() -> StateBlock(
                    headline = if ((browse.pending > 0 || browse.updating)) "Checking title metadata…" else "No matching titles",
                    line = browse.status(!state.exhausted),
                    actionLabel = if (!state.exhausted) "Load more titles" else "Reset filters",
                    onAction = { if (!state.exhausted) onLoadMore() else filters = CatalogFilters(); runCatching { filterFocus.requestFocus() } },
                    secondaryLabel = "Edit filters", onSecondary = { filtersUsed = true; filterOpen = true },
                    actionModifier = Modifier.focusRequester(gridFocus),
                )
                items.isEmpty() -> EmptyState(
                    headline = "No titles in this catalog",
                    line = "The add-on returned no titles.",
                    actionLabel = returnLabel, onAction = onClose,
                    actionModifier = Modifier.focusRequester(gridFocus),
                )
                else -> PosterGrid(
                    items = items,
                    gridState = gridState,
                    onFocusedIndex = { index ->
                        if (!state.exhausted && DiscoverPlan.shouldLoadMore(index, items.size)) onLoadMore()
                    },
                    returnFocusKey = actionReturnKey ?: returnCardKey,
                    onReturnFinished = { restored ->
                        if (actionReturnKey == null) onReturnFinished(restored) else {
                            actionReturnKey = null
                            if (!restored) runCatching { gridFocus.requestFocus() }
                        }
                    },
                    onClick = onOpen,
                    onLongClick = onLongOpen,
                    onFiltersFocus = { filterFocus.requestFocus() },
                    actions = actions.takeIf { active && !filterOpen && !searching && !savePanelOpen },
                    modifier = Modifier.focusRequester(gridFocus),
                )
            }
        }
        CatalogActionRail(
            actions,
            listOfNotNull(
                CatalogAction(R.drawable.ic_rail_search, if (titleQuery.isEmpty()) "Search titles" else "Search: $titleQuery") {
                    searching = true
                },
                CatalogAction(R.drawable.ic_catalog_filter, "Filters & sort") {
                    filtersFromRail = true; filtersUsed = true; filterOpen = true
                },
                onToast?.let { toast ->
                    CatalogAction(R.drawable.ic_catalog_pin, "Add to Den TV Home") {
                        LauncherRails.catalog(context, catalogKey, catalog.requiredGenre, toast)
                    }
                },
            ),
            onLeave = { key ->
                if (key != null && items.any { it.key == key }) actionReturnKey = key
                else runCatching { gridFocus.requestFocus() }
            },
            modifier = Modifier.align(Alignment.CenterEnd),
            enabled = active && !filterOpen && !searching && !savePanelOpen,
        )
        if (filterOpen) CatalogFilterPanel(filters, browse, { filters = it },
            onClose = {
                filterOpen = false
                if (filtersFromRail) {
                    filtersFromRail = false
                    scope.launch { withFrameNanos { }; actions.restoreAction() }
                } else runCatching { filterFocus.requestFocus() }
            },
            hasMore = !state.exhausted, loadingMore = state.loading, onLoadMore = onLoadMore, active = active)
    }
}
