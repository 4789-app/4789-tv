@file:Suppress("OPT_IN_USAGE")
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.fourseveneightnine.tv.client.ui.screens.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.client.data.images.PosterRequest
import com.fourseveneightnine.tv.client.ui.components.BadgeSpec
import com.fourseveneightnine.tv.client.ui.components.Badges
import com.fourseveneightnine.tv.client.ui.components.PivotSpec
import com.fourseveneightnine.tv.client.ui.components.ShelfHeader
import com.fourseveneightnine.tv.client.ui.components.TvArtwork
import com.fourseveneightnine.tv.client.ui.components.TvBadge
import com.fourseveneightnine.tv.client.ui.components.TvFocusableCard
import com.fourseveneightnine.tv.client.ui.components.TvProgressBar
import com.fourseveneightnine.tv.client.ui.components.accessibilityLabel
import com.fourseveneightnine.tv.client.ui.theme.LocalReduceMotion
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvGeom
import com.fourseveneightnine.tv.client.ui.theme.TvMotion
import com.fourseveneightnine.tv.client.ui.theme.TvShape
import com.fourseveneightnine.tv.client.ui.theme.TvType
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Compact shelf rhythm: a 32 px header line and 12 px before the cards. */
internal val ShelfHeaderBlock = 32.dp
internal val ShelfHeaderGap = 12.dp

/** Artwork leads the shelf, at a size readable across the room. */
internal val HomePosterWidth = 268.dp
internal val HomePosterHeight = 402.dp
internal val HomeContinueWidth = 344.dp
internal val HomeContinueHeight = 194.dp

/** Spec §0.4: the rest zone between two rows. */
internal val RowGap = 28.dp

/**
 * How a card tells its row which index has the ring.
 *
 * [TvCardRow] provides one around every card, and the card's own `onFocused` calls it. The row's
 * LEFT handler reads the value to decide whether to step inside the row or let the key bubble up
 * to the rail. The wrapper `onFocusChanged` it used to read alone can still hold the previous
 * card's index when focus arrives through `focusRestorer()` rather than a key press, and the row
 * then swallowed LEFT at card 1 so the rail never opened (audit §6). The card's callback fires
 * first, so it is the one that writes.
 */
internal val LocalCardFocusReport = androidx.compose.runtime.staticCompositionLocalOf<() -> Unit> { {} }

/**
 * Where a scrolling column of shelves parks the focused row, spec §0.3.
 *
 * `PivotSpec.column()` pins the focused NODE's top to the band top, and the focused node is a card,
 * not a row. That scrolls the row's own header off the top of the screen: the ring sits on card one
 * of a shelf whose name is no longer on screen. The focused card starts one header block plus one
 * gap below its row, so the pivot is that far down instead of zero.
 */
@Composable
internal fun rememberShelfColumnPivot(): androidx.compose.foundation.gestures.BringIntoViewSpec {
    val offsetPx = with(LocalDensity.current) { (ShelfHeaderBlock + ShelfHeaderGap).toPx() }
    return remember(offsetPx) {
        object : androidx.compose.foundation.gestures.BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                PivotSpec.scrollDistance(offset, offsetPx)
        }
    }
}

private val CardGap = 24.dp

/**
 * Room for card 1's ring, F21.
 *
 * A `LazyRow` clips its own scroll axis, so the ring's 7 px and the 1.06 scale's 7.1 px of
 * sideways growth were cut off at the row's left edge and the first poster read as pinned to the
 * menu. Starting the content 16 px in gives both somewhere to go. The row pivot moves the same
 * distance, or every row parks its focused card 16 px left of spec §0.3.
 */
private val RowStartInset = TvGeom.RowEdgeInset

/** Spec §3.9.4: 12 px out and back says "end of row" without a sound or a colour change. */
private val NudgeDistance = 12.dp
private const val NudgeMillis = 120

/**
 * One horizontal shelf: header, gap, cards.
 *
 * LEFT and RIGHT are answered here rather than left to Compose's focus search, for one reason: the
 * screen frame above consumes LEFT to open the rail, and it consumes it on key DOWN, ahead of the
 * search. A row that does not answer LEFT itself would therefore open the rail on every step back
 * along the row. So the row moves focus when there is somewhere to move, and lets the key bubble
 * up to the rail only at card 1 — which is exactly the rule in spec §17.
 */
@Composable
internal fun <T> TvCardRow(
    title: String,
    items: List<T>,
    key: (T) -> String,
    modifier: Modifier = Modifier,
    staleLabel: String? = null,
    trailingCard: (@Composable () -> Unit)? = null,
    rowState: LazyListState? = null,
    firstCardFocus: FocusRequester? = null,
    onVertical: ((Int) -> Boolean)? = null,
    restoreFocus: Boolean = true,
    card: @Composable (index: Int, item: T) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val reduceMotion = LocalReduceMotion.current
    val defaultListState = rememberLazyListState()
    val listState = rowState ?: defaultListState
    val scope = rememberCoroutineScope()
    val nudge = remember { Animatable(0f) }
    // A plain holder, not a State. It is read only inside the key handler, and making it a
    // State costs one recomposition of the entire row on every single focus move.
    val focusedIndex = remember { intArrayOf(0) }
    val lastIndex = items.size - 1 + if (trailingCard != null) 1 else 0
    val density = LocalDensity.current
    // F21: card 1 starts [RowStartInset] in, so the ring and focus growth have somewhere to go
    // inside a `LazyRow` that clips its own scroll axis. The pivot moves the same distance.
    val pivotPx = with(density) { (PivotSpec.RowPivotWithinRowDesignPx.dp + TvGeom.RowEdgeInset).toPx() }
    val nudgePx = with(density) { NudgeDistance.toPx() }
    val spec = remember(pivotPx) { PivotSpec.row(pivotPx) }

    Column(modifier) {
        ShelfHeader(title = title, staleLabel = staleLabel)
        Spacer(Modifier.height(ShelfHeaderGap))
        CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
            LazyRow(
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(CardGap),
                contentPadding = PaddingValues(
                    start = RowStartInset,
                    end = 96.dp,
                    bottom = 12.dp,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(nudge.value.toInt(), 0) }
                    .then(if (restoreFocus) Modifier.focusRestorer() else Modifier)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionUp -> onVertical?.invoke(-1) ?: false
                            Key.DirectionDown -> onVertical?.invoke(1) ?: false
                            else -> false
                        }
                    }
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (event.key) {
                            Key.DirectionLeft -> {
                                // Card 1 lets it bubble: the frame above turns LEFT into the rail.
                                if (focusedIndex[0] <= 0) return@onKeyEvent false
                                focusManager.moveFocus(FocusDirection.Left)
                                true
                            }
                            Key.DirectionRight -> {
                                if (focusedIndex[0] >= lastIndex) {
                                    if (!reduceMotion) {
                                        scope.launch {
                                            nudge.animateTo(-nudgePx, tween(NudgeMillis, easing = TvMotion.Out))
                                            nudge.animateTo(0f, tween(NudgeMillis, easing = TvMotion.Out))
                                        }
                                    }
                                    return@onKeyEvent true
                                }
                                focusManager.moveFocus(FocusDirection.Right)
                                true
                            }
                            else -> false
                        }
                    },
            ) {
                itemsIndexed(items = items, key = { _, item -> key(item) }) { index, item ->
                    Box(Modifier
                        .then(if (index == 0 && firstCardFocus != null)
                            Modifier.focusRequester(firstCardFocus) else Modifier)
                        .onFocusChanged { if (it.hasFocus) focusedIndex[0] = index }) {
                        CompositionLocalProvider(
                            LocalCardFocusReport provides remember(index) { { focusedIndex[0] = index } },
                        ) {
                            card(index, item)
                        }
                    }
                }
                if (trailingCard != null) {
                    item(key = "see-all") {
                        Box(Modifier.onFocusChanged { if (it.hasFocus) focusedIndex[0] = lastIndex }) {
                            CompositionLocalProvider(
                                LocalCardFocusReport provides remember(lastIndex) { { focusedIndex[0] = lastIndex } },
                            ) {
                                trailingCard()
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Spec §3.3. 236 x 354, no title under it: the hero already names the focused title, and a label
 * under every card doubles the text on screen for no gain.
 */
@Composable
internal fun HomePosterCard(
    card: HomeCard,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val badges = remember(card.isNew) { if (card.isNew) listOf(Badges.New) else emptyList<BadgeSpec>() }
    // Held, not built in composition: one allocation per card per recomposition otherwise (F52).
    val poster = remember(card.posterUrl) { PosterRequest.poster(card.posterUrl) }
    TvFocusableCard(
        onClick = onClick,
        onLongClick = onLongClick,
        accessibleLabel = accessibilityLabel(
            card.title,
            card.year?.toString(),
            card.type.takeIf(String::isNotBlank),
            if (card.isNew) "New" else null,
        ),
        clickLabel = "Open details",
        longClickLabel = "Add to collection",
        onFocused = onFocused,
        modifier = modifier,
        cornerRadius = 16.dp,
        focusScale = 1f,
        focusRing = false,
    ) { focused ->
        Box(
            Modifier.size(HomePosterWidth, HomePosterHeight)
                .then(if (focused) Modifier.border(3.dp, Color.White, TvShape.CardProminent) else Modifier),
        ) {
            TvArtwork(
                request = poster,
                title = card.title,
                shape = TvShape.CardProminent,
                modifier = Modifier.fillMaxSize(),
            )
            if (badges.isNotEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    badges.forEach { TvBadge(it) }
                }
            }
        }
    }
}

/** Spec §3.3. The progress bar is 6 px: at 3 m a 3 px bar disappears. */
@Composable
internal fun HomeContinueCardView(
    item: HomeContinueCard,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Held, not built in composition (F52).
    val still = remember(item.stillUrl) { PosterRequest.wide(item.stillUrl) }
    Column(modifier.width(HomeContinueWidth)) {
        TvFocusableCard(
            onClick = onClick,
            onLongClick = onLongClick,
            accessibleLabel = accessibilityLabel(
                item.card.title,
                item.line,
                "${(item.progress.coerceIn(0f, 1f) * 100).roundToInt()} percent watched",
            ),
            clickLabel = "Resume",
            longClickLabel = "Add to collection",
            onFocused = onFocused,
            cornerRadius = 16.dp,
            focusScale = 1f,
            focusRing = false,
        ) { focused ->
            Box(
                Modifier.size(HomeContinueWidth, HomeContinueHeight)
                    .then(if (focused) Modifier.border(3.dp, Color.White, TvShape.CardProminent) else Modifier),
            ) {
                TvArtwork(
                    request = still,
                    title = item.card.title,
                    modifier = Modifier.fillMaxSize(),
                    shape = TvShape.CardProminent,
                    showTitleWhenMissing = false,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
                TvProgressBar(
                    progress = item.progress,
                    height = 6.dp,
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
                )
            }
        }
        // Keep the label outside the focus ring without opening a second visual band.
        Spacer(Modifier.height(TvGeom.FocusLabelGap))
        Text(
            text = item.card.title,
            style = TvType.CardTitle,
            color = TvColor.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.height(28.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = item.line,
            style = TvType.Meta,
            color = TvColor.TextMuted,
            maxLines = 1,
            modifier = Modifier.height(22.dp),
        )
    }
}

/**
 * Spec §3.3. A 6 px accent bar runs the card's full height at its left edge, and up to three
 * poster slivers sit inside, drawn back to front so the leftmost is on top.
 */
@Composable
internal fun HomeFolderCardView(
    folder: HomeFolderCard,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = remember(folder.accent) { parseAccent(folder.accent) }
    Column(modifier.width(TvGeom.FolderCardWidth)) {
        TvFocusableCard(
            onClick = onClick,
            onFocused = onFocused,
            accessibleLabel = accessibilityLabel(
                folder.name,
                if (folder.count == 1) "1 title" else "${folder.count} titles",
            ),
            clickLabel = "Open collection",
            cornerRadius = 12.dp,
        ) { _ ->
            Box(
                Modifier
                    .size(TvGeom.FolderCardWidth, TvGeom.FolderCardHeight)
                    .clip(TvShape.Card)
                    .background(TvColor.Elevated),
            ) {
                // Back to front: the last sliver is drawn first, so slot one ends up on top.
                folder.previewPosters.take(3).reversed().forEachIndexed { reverseIndex, url ->
                    val slot = minOf(folder.previewPosters.size, 3) - 1 - reverseIndex
                    TvArtwork(
                        request = PosterRequest.poster(url),
                        title = "",
                        showTitleWhenMissing = false,
                        modifier = Modifier
                            .offset(x = 24.dp + (slot * 40).dp, y = 40.dp)
                            .size(96.dp, 144.dp),
                    )
                }
                Box(Modifier.width(6.dp).fillMaxHeight().background(accent))
            }
        }
        // Keep the label outside the focus ring without opening a second visual band.
        Spacer(Modifier.height(TvGeom.FocusLabelGap))
        Text(
            text = folder.name,
            style = TvType.ControlLabel,
            color = TvColor.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.height(28.dp),
        )
        Text(
            text = if (folder.count == 1) "1 title" else "${folder.count} titles",
            style = TvType.Meta,
            color = TvColor.TextMuted,
            maxLines = 1,
            modifier = Modifier.height(20.dp),
        )
    }
}

/** Spec §3.3. The last card of a catalog row, the same size as a poster. */
@Composable
internal fun SeeAllCard(
    shelfTitle: String,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvFocusableCard(
        onClick = onClick,
        onFocused = onFocused,
        modifier = modifier,
        accessibleLabel = "See all $shelfTitle",
        cornerRadius = 16.dp,
        focusScale = 1f,
        focusRing = false,
    ) { focused ->
        Box(
            Modifier
                .size(HomePosterWidth, HomePosterHeight)
                .clip(TvShape.CardProminent)
                .background(TvColor.Elevated)
                .then(if (focused) Modifier.border(3.dp, Color.White, TvShape.CardProminent) else Modifier),
        ) {
            // Spec §3.3: a 44 px chevron centred at y 140, the label at y 200. A drawn chevron,
            // not a "›" glyph: the glyph's own metrics put it 32 px off its slot (F31).
            Chevron(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = 118.dp)
                    .size(44.dp),
            )
            Text(
                text = "See all",
                style = TvType.CardTitle,
                color = TvColor.TextPrimary,
                modifier = Modifier.align(Alignment.TopCenter).offset(y = 200.dp),
            )
        }
    }
}

/** The "See all" chevron: two strokes pointing right, drawn inside the box it is given. */
@Composable
private fun Chevron(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val stroke = 4.dp.toPx()
        val x = size.width * 0.38f
        val top = size.height * 0.24f
        val mid = size.height * 0.5f
        val bottom = size.height * 0.76f
        val tip = androidx.compose.ui.geometry.Offset(size.width * 0.68f, mid)
        drawLine(
            color = TvColor.TextSecondary,
            start = androidx.compose.ui.geometry.Offset(x, top),
            end = tip,
            strokeWidth = stroke,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        drawLine(
            color = TvColor.TextSecondary,
            start = tip,
            end = androidx.compose.ui.geometry.Offset(x, bottom),
            strokeWidth = stroke,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}

/** A stored accent is a hex string. A bad one falls back to muted rather than throwing. */
internal fun parseAccent(value: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(value)) }.getOrDefault(TvColor.TextMuted)

/** Spec §0.4: the pitch a row of this kind occupies, header and gap included. */
internal fun rowBlockHeight(row: HomeRow): Dp = when (row) {
    is HomeRow.Continue -> 308.dp
    is HomeRow.Collections -> 322.dp
    is HomeRow.Posters -> 446.dp
    is HomeRow.AllCatalogs -> 152.dp
}
