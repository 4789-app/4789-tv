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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.data.addons.CatalogExtra
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
    catalog: HomeCatalogRef,
    onOpen: (HomeCard) -> Unit,
    onLongOpen: (HomeCard) -> Unit,
) {
    val context = LocalContext.current
    val client = remember(context) { context.clientGraph }
    var items by remember(catalog.catalogId) { mutableStateOf<List<HomeCard>>(emptyList()) }
    var loading by remember(catalog.catalogId) { mutableStateOf(true) }
    var exhausted by remember(catalog.catalogId) { mutableStateOf(false) }
    var wanted by remember(catalog.catalogId) { mutableIntStateOf(0) }
    val gridState = rememberLazyGridState()
    val gridFocus = remember { FocusRequester() }

    // F01: the overlay is opaque and full-screen, and it never took the ring. The D-pad drove the
    // Home rows underneath, which the viewer cannot see. The request waits for the first page,
    // because a grid with no cells has nothing to focus.
    LaunchedEffect(catalog.catalogId, items.isEmpty()) {
        if (items.isEmpty()) return@LaunchedEffect
        runCatching { gridFocus.requestFocus() }
    }

    LaunchedEffect(catalog.catalogId, wanted) {
        val services = client.services.value ?: return@LaunchedEffect
        val addon = services.registry.addon(catalog.manifestUrl) ?: return@LaunchedEffect
        if (exhausted) return@LaunchedEffect
        // Off the main thread: the parse of a full catalog page is the expensive half of this call.
        val page = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                services.client.catalog(addon, catalog.type, catalog.catalogId, CatalogExtra(skip = wanted))
            }.getOrNull()
        }
        loading = false
        val incoming = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            page?.items?.map { it.toHomeCard() }.orEmpty()
        }
        if (incoming.isEmpty() || page?.hasMore != true) exhausted = true
        val seen = items.mapTo(mutableSetOf()) { it.key }
        items = items + incoming.filter { seen.add(it.key) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(TvColor.Canvas)
            // The overlay is modal. Without the fence a focus search that the grid did not answer
            // walks out to the Home rows behind it, which are drawn over and unreachable by eye.
            // BACK is the way out, and Home restores the card the viewer came from.
            .focusProperties { exit = { FocusRequester.Cancel } }
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
                loading && items.isEmpty() -> GridSkeleton()
                items.isEmpty() -> EmptyState(
                    headline = "No titles in this catalog",
                    line = "Try another genre, or pick a different catalog.",
                )
                else -> PosterGrid(
                    items = items,
                    gridState = gridState,
                    onFocusedIndex = { index ->
                        if (!exhausted && DiscoverPlan.shouldLoadMore(index, items.size)) {
                            wanted = items.size
                        }
                    },
                    onClick = onOpen,
                    onLongClick = onLongOpen,
                    modifier = Modifier.focusRequester(gridFocus),
                )
            }
        }
    }
}
