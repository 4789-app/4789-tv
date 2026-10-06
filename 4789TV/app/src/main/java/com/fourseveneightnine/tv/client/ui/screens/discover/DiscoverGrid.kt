@file:Suppress("OPT_IN_USAGE")
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.fourseveneightnine.tv.client.ui.screens.discover

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.client.data.images.PosterRequest
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.ui.components.PivotSpec
import com.fourseveneightnine.tv.client.ui.components.PaintedArtwork
import com.fourseveneightnine.tv.client.ui.components.Skeleton
import com.fourseveneightnine.tv.client.ui.components.TvArtwork
import com.fourseveneightnine.tv.client.ui.components.TvFocusableCard
import com.fourseveneightnine.tv.client.ui.components.accessibilityLabel
import com.fourseveneightnine.tv.client.ui.screens.home.HomeCard
import com.fourseveneightnine.tv.client.ui.theme.LocalReduceMotion
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvGeom
import com.fourseveneightnine.tv.client.ui.theme.TvMotion
import com.fourseveneightnine.tv.client.ui.theme.TvType
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Spec §4.2: 236 px cards at a 256 px pitch. Every vertical number comes from `DiscoverPlan`. */
private val CellGap = 20.dp
private val GridRowGap = DiscoverPlan.ROW_GAP_DP.dp
private val NudgeDistance = 12.dp
private const val NudgeMillis = 120

/**
 * The 6-column poster grid, used by Discover and by Home's "See all".
 *
 * RIGHT wraps to the next row's first cell and LEFT does not wrap, which looks asymmetric and is
 * not: a paged grid is one list in reading order, so RIGHT walks it, while LEFT at column 1 always
 * means the rail. That rule wins everywhere in this app (spec §4.7).
 *
 * The wrap is `FocusDirection.Next`, not a focus requester on the next cell. A requester inside a
 * lazy item is unattached the moment that item scrolls out of the composed window, and a focus
 * request against an unattached requester destroys focus rather than moving it — which is a dead
 * remote, and the exact bug that shipped in 0.1.42.
 */
@Composable
internal fun PosterGrid(
    items: List<HomeCard>,
    gridState: LazyGridState,
    onFocusedIndex: (Int) -> Unit,
    onClick: (HomeCard) -> Unit,
    onLongClick: (HomeCard) -> Unit,
    modifier: Modifier = Modifier,
    trailingPlaceholders: Int = 0,
    onUpFromFirstRow: (() -> Boolean)? = null,
) {
    val context = LocalContext.current
    val imageLoader = remember(context) { context.applicationContext.clientGraph.imageLoader }
    val focusManager = LocalFocusManager.current
    val reduceMotion = LocalReduceMotion.current
    val scope = rememberCoroutineScope()
    val nudge = remember { Animatable(0f) }
    val nudgePx = with(LocalDensity.current) { NudgeDistance.toPx() }
    val focused = remember { intArrayOf(0) }
    val lastFocused = remember { intArrayOf(-1) }
    val requestedPosters = remember { LinkedHashSet<String>() }

    fun markRequested(url: String?) {
        if (url == null) return
        requestedPosters.remove(url)
        requestedPosters.add(url)
        while (requestedPosters.size > 128) requestedPosters.remove(requestedPosters.first())
    }

    fun prefetchNear(index: Int) {
        val row = index / DiscoverPlan.COLUMNS
        val upward = lastFocused[0] >= 0 && index < lastFocused[0]
        lastFocused[0] = index
        val nearbyRows = if (upward) listOf(row - 1, row - 2, row + 1)
            else listOf(row + 1, row + 2, row - 1)
        val queued = mutableListOf<String>()
        for (nearby in nearbyRows) {
            if (nearby < 0) continue
            for (i in nearby * DiscoverPlan.COLUMNS until
                minOf((nearby + 1) * DiscoverPlan.COLUMNS, items.size)) {
                val url = items[i].posterUrl ?: continue
                if (url in requestedPosters) continue
                markRequested(url)
                queued.add(url)
                if (queued.size == 12) break
            }
            if (queued.size == 12) break
        }
        if (queued.isNotEmpty()) scope.launch(Dispatchers.Default) {
            queued.forEach { url ->
                imageLoader.enqueue(PosterRequest.poster(url).toImageRequest(context.applicationContext))
            }
        }
    }
    val spec = remember { PivotSpec.column() }
    // Spec §4.11.10: a cell may fetch its poster once it is on screen, with one row of look-ahead.
    // Read through `derivedStateOf` so a scroll recomputes the window without recomposing the grid.
    val window = remember(gridState, items.size) {
        derivedStateOf {
            val visible = gridState.layoutInfo.visibleItemsInfo
            DiscoverPlan.requestWindow(
                firstVisible = visible.firstOrNull()?.index ?: 0,
                lastVisible = visible.lastOrNull()?.index ?: (DiscoverPlan.COLUMNS * 2 - 1),
                itemCount = items.size,
            )
        }
    }

    CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(DiscoverPlan.COLUMNS),
            state = gridState,
            horizontalArrangement = Arrangement.spacedBy(CellGap),
            verticalArrangement = Arrangement.spacedBy(GridRowGap),
            contentPadding = PaddingValues(bottom = 54.dp),
            modifier = modifier
                .fillMaxSize()
                .offset { IntOffset(nudge.value.toInt(), 0) }
                .focusRestorer()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val index = focused[0]
                    when (event.key) {
                        Key.DirectionLeft -> {
                            // Column 1 lets it bubble: the frame turns LEFT into the rail.
                            if (index % DiscoverPlan.COLUMNS == 0) return@onKeyEvent false
                            focusManager.moveFocus(FocusDirection.Previous)
                            true
                        }
                        Key.DirectionRight -> {
                            if (index >= items.size - 1) {
                                if (!reduceMotion) {
                                    scope.launch {
                                        nudge.animateTo(-nudgePx, tween(NudgeMillis, easing = TvMotion.Out))
                                        nudge.animateTo(0f, tween(NudgeMillis, easing = TvMotion.Out))
                                    }
                                }
                                return@onKeyEvent true
                            }
                            focusManager.moveFocus(FocusDirection.Next)
                            true
                        }
                        Key.DirectionUp -> {
                            if (index >= DiscoverPlan.COLUMNS) return@onKeyEvent false
                            onUpFromFirstRow?.invoke() ?: false
                        }
                        else -> false
                    }
                },
        ) {
            itemsIndexed(items = items, key = { _, item -> item.key }) { index, item ->
                GridCell(
                    card = item,
                    inWindow = { index in window.value },
                    wasRequested = { url -> url != null &&
                        (url in requestedPosters || PaintedArtwork.wasPainted(url)) },
                    onRequested = ::markRequested,
                    onClick = { onClick(item) },
                    onLongClick = { onLongClick(item) },
                    onFocused = {
                        focused[0] = index
                        prefetchNear(index)
                        onFocusedIndex(index)
                    },
                )
            }
            if (trailingPlaceholders > 0) {
                items(List(trailingPlaceholders) { "pending-$it" }, key = { it }) {
                    // Spec §4.9: placeholder cells at the tail while page 2 loads. No label on them.
                    Column(Modifier.width(TvGeom.PosterWidth)) {
                        Skeleton(Modifier.size(TvGeom.PosterWidth, TvGeom.PosterHeight), sweep = false)
                    }
                }
            }
        }
    }
}

/**
 * Spec §4.2: poster, 10 px, title 24, 2 px, meta 22.
 *
 * Both text lines carry an explicit height and a line height to match, so a long title in a tall
 * font cannot change the row pitch. The skeleton is built from the same numbers, which is what
 * stops the grid jumping the moment the first page lands.
 */
@Composable
private fun GridCell(
    card: HomeCard,
    inWindow: () -> Boolean,
    wasRequested: (String?) -> Boolean,
    onRequested: (String?) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocused: () -> Unit,
) {
    // Spec §4.11.10: 140 ms of rest inside the visible window before the poster is asked for. Once
    // a cell has asked, it never un-asks: dropping the request back would blank a poster the viewer
    // is looking at. `collectLatest` cancels the wait for a cell the ring flew past.
    var armed by remember(card.key, card.posterUrl) {
        mutableStateOf(wasRequested(card.posterUrl))
    }
    if (!armed) {
        LaunchedEffect(card.key) {
            snapshotFlow { inWindow() }.collectLatest { visible ->
                if (!visible) return@collectLatest
                delay(TvMotion.HeroSettleMillis)
                armed = true
                onRequested(card.posterUrl)
            }
        }
    }
    val request = remember(card.posterUrl, armed) {
        PosterRequest.poster(if (armed) card.posterUrl else null)
    }
    Column(Modifier.width(TvGeom.PosterWidth)) {
        TvFocusableCard(
            onClick = onClick,
            onLongClick = onLongClick,
            onFocused = onFocused,
            accessibleLabel = accessibilityLabel(card.title, card.year?.toString(), card.type),
            clickLabel = "Open details",
            longClickLabel = "Add to collection",
            cornerRadius = 12.dp,
        ) { _ ->
            TvArtwork(
                request = request,
                title = card.title,
                modifier = Modifier.size(TvGeom.PosterWidth, TvGeom.PosterHeight),
            )
        }
        Spacer(Modifier.height(DiscoverPlan.POSTER_TEXT_GAP_DP.dp))
        Text(
            text = card.title,
            style = TvType.CardTitle.copy(lineHeight = DiscoverPlan.CELL_TITLE_HEIGHT_DP.sp),
            color = TvColor.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.height(DiscoverPlan.CELL_TITLE_HEIGHT_DP.dp),
        )
        Spacer(Modifier.height(DiscoverPlan.CELL_TITLE_META_GAP_DP.dp))
        Text(
            text = cellMeta(card),
            style = TvType.Meta.copy(lineHeight = DiscoverPlan.CELL_META_HEIGHT_DP.sp),
            color = TvColor.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.height(DiscoverPlan.CELL_META_HEIGHT_DP.dp),
        )
    }
}

/** Spec §4.10: "2024", or the year alone when a series has no season count. */
internal fun cellMeta(card: HomeCard): String = card.year?.toString().orEmpty()

/** Spec §4.9: 12 cells, the first six sweeping. */
@Composable
internal fun GridSkeleton(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        repeat(2) { row ->
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(CellGap)) {
                repeat(DiscoverPlan.COLUMNS) {
                    Skeleton(
                        Modifier.size(TvGeom.PosterWidth, TvGeom.PosterHeight),
                        sweep = row == 0,
                    )
                }
            }
            // The real cell's text block plus the row gap, so the skeleton's rows sit at the same
            // pitch and nothing moves when the page lands.
            Spacer(Modifier.height(DiscoverPlan.SKELETON_ROW_SPACER_DP.dp))
        }
    }
}

/** Used by the grid's owners to know when focus left it, so the chips band can come back. */
internal fun Modifier.onGridFocusChanged(onChanged: (Boolean) -> Unit): Modifier =
    this.then(Modifier.onFocusChanged { onChanged(it.hasFocus) })
