@file:Suppress("OPT_IN_USAGE")
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.fourseveneightnine.tv.client.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.client.ui.components.EmptyState
import com.fourseveneightnine.tv.client.ui.screens.discover.DiscoverPlan
import com.fourseveneightnine.tv.client.ui.screens.discover.GridSkeleton
import com.fourseveneightnine.tv.client.ui.screens.discover.PosterGrid
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvGeom
import com.fourseveneightnine.tv.client.ui.theme.TvType

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
    gridFocus: FocusRequester,
    savePanelOpen: Boolean,
    onOpen: (HomeCard) -> Unit,
    onLongOpen: (HomeCard) -> Unit,
) {
    val catalog = state.catalog
    val items = state.items
    val gridState = rememberLazyGridState()
    LaunchedEffect(catalog, items.isEmpty()) {
        if (items.isNotEmpty() && returnCardKey == null) runCatching { gridFocus.requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(TvColor.Canvas)
            // The overlay is modal. Without the fence a focus search that the grid did not answer
            // walks out to the Home rows behind it, which are drawn over and unreachable by eye.
            // BACK is the way out, and Home restores the card the viewer came from.
            // A save panel above this grid must be able to take focus.
            .focusProperties {
                exit = { if (savePanelOpen) FocusRequester.Default else FocusRequester.Cancel }
            }
            .focusGroup()
            .padding(start = TvGeom.ContentLeft, top = 54.dp, end = 96.dp),
    ) {
        Text(catalog.name, style = TvType.ScreenTitle, color = TvColor.TextPrimary, maxLines = 1)
        // The same geometry as Discover: the grid ends at the safe bottom instead of running
        // 162 px past it (spec §4.2).
        Box(
            Modifier
                .fillMaxWidth()
                .height(DiscoverPlan.gridHeightDp(collapsed = false).dp)
                .offset(y = DiscoverPlan.gridTopDp(collapsed = false).dp),
        ) {
            when {
                state.loading && items.isEmpty() -> GridSkeleton()
                items.isEmpty() -> EmptyState(
                    headline = "No titles in this catalog",
                    line = "Try another genre, or pick a different catalog.",
                )
                else -> PosterGrid(
                    items = items,
                    gridState = gridState,
                    onFocusedIndex = { index ->
                        if (!state.exhausted && DiscoverPlan.shouldLoadMore(index, items.size)) onLoadMore()
                    },
                    returnFocusKey = returnCardKey,
                    onReturnFinished = onReturnFinished,
                    onClick = onOpen,
                    onLongClick = onLongOpen,
                    modifier = Modifier.focusRequester(gridFocus),
                )
            }
        }
    }
}
