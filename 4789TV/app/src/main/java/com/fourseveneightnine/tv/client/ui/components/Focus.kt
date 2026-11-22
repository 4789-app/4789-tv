@file:Suppress("OPT_IN_USAGE")

package com.fourseveneightnine.tv.client.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fourseveneightnine.tv.client.ui.theme.LocalReduceMotion
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvGeom
import com.fourseveneightnine.tv.client.ui.theme.TvMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Move to a concrete card, including when that row has yet to be composed. */
internal class GridRowFocus(
    private val state: LazyGridState,
    private val scope: CoroutineScope,
    private val columns: Int,
    private val smoothScroll: Boolean = false,
) {
    private val attached = mutableMapOf<String, FocusRequester>()
    private var movement: Job? = null

    @Composable
    fun card(key: String): Modifier {
        val focus = remember(key) { FocusRequester() }
        DisposableEffect(key, focus) {
            attached[key] = focus
            onDispose { attached.remove(key, focus) }
        }
        return Modifier.focusRequester(focus)
    }

    fun cancel() { movement?.cancel() }

    fun move(from: Int, down: Boolean, count: Int, repeating: Boolean = false, keyAt: (Int) -> String): Boolean {
        if (from !in 0 until count) return false
        if (movement?.isActive == true) return true
        val row = from / columns
        if (!down && row == 0) return false
        if (down && row == (count - 1) / columns) return true
        val target = if (down) (from + columns).coerceAtMost(count - 1) else from - columns
        val key = keyAt(target)
        val layout = state.layoutInfo
        val next = layout.visibleItemsInfo.firstOrNull { it.key == key }
        if (next != null && next.offset.y >= layout.viewportStartOffset &&
            next.offset.y + next.size.height <= layout.viewportEndOffset && request(key)
        ) return true

        val current = layout.visibleItemsInfo.firstOrNull { it.index == from }
        val height = current?.size?.height ?: 0
        // Reveal one row at the edge. The previous row remains on screen while focus transfers.
        val offset = if (down) -(layout.viewportEndOffset - height - layout.mainAxisItemSpacing).coerceAtLeast(0) else 0
        movement = scope.launch {
            if (smoothScroll && current != null) {
                val rowDistance = (height + layout.mainAxisItemSpacing) * (if (down) 1 else -1)
                val targetTop = next?.offset?.y ?: (current.offset.y + rowDistance)
                val scroll = targetTop - (layout.viewportStartOffset - offset)
                val duration = if (repeating) TvMotion.ScrollMillis / 2 else TvMotion.ScrollMillis
                state.animateScrollBy(scroll.toFloat(), tween(duration, easing = TvMotion.Std))
            } else {
                state.scrollToItem(target, offset)
            }
            if (request(key)) return@launch
            repeat(4) {
                withFrameNanos { }
                if (request(key)) return@launch
            }
        }
        return true
    }

    fun request(key: String): Boolean = runCatching { attached[key]?.requestFocus() == true }.getOrDefault(false)
}

/**
 * A neutral focus outline painted over the finished content, inside its bounds. Keeping the
 * guarded stroke inside the target prevents a lazy viewport clipping the first/last edge.
 *
 * It is drawn by the focused element and by nothing else, so a rail of forty cards still paints
 * one ring. The ring never waits on artwork — it is not gated on any load.
 */
@Composable
internal fun Modifier.tvFocusRing(
    focused: Boolean,
    cornerRadius: Dp,
    onDrawn: (() -> Unit)? = null,
    pressed: (() -> Boolean)? = null,
): Modifier {
    if (!focused) return this
    return this.drawWithContent {
        drawContent()
        val ring = TvGeom.FocusRingWidth.toPx()
        val guard = 2.dp.toPx()
        val radius = cornerRadius.toPx()
        val inset = (ring + guard * 2f) / 2f
        val bounds = Size((size.width - inset * 2f).coerceAtLeast(0f), (size.height - inset * 2f).coerceAtLeast(0f))
        val corners = CornerRadius((radius - inset).coerceAtLeast(0f))
        drawRoundRect(
            color = TvColor.Canvas,
            topLeft = Offset(inset, inset),
            size = bounds,
            cornerRadius = corners,
            style = Stroke(width = ring + guard * 2f),
        )
        drawRoundRect(
            color = if (pressed?.invoke() == true) TvColor.Accent else TvColor.Focus,
            topLeft = Offset(inset, inset),
            size = bounds,
            cornerRadius = corners,
            style = Stroke(width = ring),
        )
        onDrawn?.invoke()
    }
}

/**
 * The pivot every focused element grows from: horizontally centred, pinned to the top edge.
 * A value class over a packed Long, so it is free to read in the layer block.
 */
private val TopCentreOrigin = TransformOrigin(pivotFractionX = 0.5f, pivotFractionY = 0f)

/**
 * Focus growth for a card, spec §16.1: scale 1.06 over 160 ms.
 *
 * One animation per focused element. Never a per-card image scale plus a per-card label fade —
 * that measured 20% janky frames against 1.1% on the same box.
 *
 * The pivot is the TOP edge, not the centre. A 354 px poster grown 6% about its centre pushes
 * 10.6 px up, and an external ring could then land on the shelf header's
 * baseline — the 16 px gap in `HomeRows.ShelfHeaderGap` is one pixel short of the 17 it needs.
 * Pinning the top edge means cards only grow down and sideways. The current ring is inset and
 * the default scale is one; callers that deliberately grow a control retain this top anchor.
 */
@Composable
internal fun Modifier.tvFocusScale(
    focused: Boolean,
    target: Float = TvGeom.FocusScale,
): Modifier {
    if (target == 1f) return this
    val reduceMotion = LocalReduceMotion.current
    var elevated by remember { mutableStateOf(false) }
    LaunchedEffect(focused) { if (focused) elevated = true }
    val value = animateFloatAsState(
        targetValue = if (focused) target else 1f,
        animationSpec = tween(
            durationMillis = if (reduceMotion) 0 else TvMotion.FocusScaleMillis,
            easing = TvMotion.Std,
        ),
        label = "focusScale",
        finishedListener = { if (!focused && it == 1f) elevated = false },
    )
    // Only the incoming and outgoing cards carry a layer during the transition.
    if (!focused && !elevated) return this
    return this.graphicsLayer {
        val s = value.value
        scaleX = s
        scaleY = s
        transformOrigin = TopCentreOrigin
    }
}

/**
 * Where a scrolling container parks the focused child, spec §0.3.
 *
 * Rows pivot at 30%: the focused card's left edge pins to x 732, poster column 3. Cards 1 and 2
 * never move because a row cannot scroll before its start, which falls out of the clamp Compose
 * already applies. Grids use the nearest edge to keep the focused card visible.
 */
internal object PivotSpec {
    /** Poster column 3 on the design canvas. */
    const val RowPivotDesignPx = 732f

    /** Content starts here, so a row's own coordinate space begins 220 px in. */
    const val ContentLeftDesignPx = 220f

    /** The pivot expressed inside a row's own scroll container. */
    const val RowPivotWithinRowDesignPx = RowPivotDesignPx - ContentLeftDesignPx

    /**
     * How far to scroll so [offset] lands on [pivot]. Positive scrolls forward. Compose clamps
     * the result against the container's own bounds, which is what keeps cards 1 and 2 still.
     */
    fun scrollDistance(offset: Float, pivot: Float): Float = offset - pivot

    /** Rows: pin the focused card's leading edge to the pivot column. */
    fun row(pivotPx: Float): BringIntoViewSpec = object : BringIntoViewSpec {
        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
            scrollDistance(offset, pivotPx)
    }

    /** Use the default nearest-edge behavior even on Android TV, which supplies its own pivot. */
    fun nearestEdge(): BringIntoViewSpec = object : BringIntoViewSpec {}

    /**
     * Columns whose first screen is a composed page (Detail): a fully visible item never scrolls,
     * so entry focus on the Play button leaves the hero where it was drawn. An item off the
     * bottom pins to the band top. An item off the top scrolls just into view.
     */
    fun columnKeepVisible(): BringIntoViewSpec = object : BringIntoViewSpec {
        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
            offset >= 0f && offset + size <= containerSize -> 0f
            offset < 0f -> offset
            else -> scrollDistance(offset, 0f)
        }
    }
}
