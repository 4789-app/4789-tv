@file:Suppress("OPT_IN_USAGE")

package com.fourseveneightnine.tv.client.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.flow.first
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.client.data.meta.CastMember
import com.fourseveneightnine.tv.client.ui.components.TvFocusable
import com.fourseveneightnine.tv.client.ui.components.accessibilityLabel
import com.fourseveneightnine.tv.client.ui.components.tvFocusRing
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvGeom
import com.fourseveneightnine.tv.client.ui.theme.TvSpace
import com.fourseveneightnine.tv.client.ui.theme.TvType

/**
 * Spec §9.4 Cast card: 180 wide, a 160 px circle, name over role.
 *
 * Select opens that person's credits.
 */
@Composable
internal fun CastRow(
    cast: List<CastMember>,
    onOpenPerson: (CastMember, String) -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    returnFocusKey: String? = null,
    onReturnFinished: (Boolean) -> Unit = {},
) {
    val rowState = rememberLazyListState()
    val returnFocus = remember { FocusRequester() }
    val visible = cast.take(24)
    LaunchedEffect(returnFocusKey, cast, loading) {
        if (returnFocusKey == null || loading) return@LaunchedEffect
        val index = visible.indices.firstOrNull { castKey(it, visible[it]) == returnFocusKey }
        if (index == null) {
            onReturnFinished(false)
            return@LaunchedEffect
        }
        if (rowState.layoutInfo.visibleItemsInfo.none { it.key == returnFocusKey }) rowState.scrollToItem(index)
        snapshotFlow { rowState.layoutInfo.visibleItemsInfo.any { it.key == returnFocusKey } }.first { it }
        repeat(8) {
            withFrameNanos { }
            if (runCatching { returnFocus.requestFocus() }.getOrDefault(false)) {
                onReturnFinished(true)
                return@LaunchedEffect
            }
        }
        onReturnFinished(false)
    }
    if (loading) {
        SkeletonRow(modifier, width = 160.dp, height = 160.dp)
        return
    }
    if (cast.isEmpty()) return
    LazyRow(
        state = rowState,
        modifier = modifier.fillMaxWidth().focusRestorer(),
        contentPadding = PaddingValues(start = TvGeom.RowEdgeInset, top = 12.dp, end = 48.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(TvSpace.M),
    ) {
        // Cinemeta repeats a name when one actor plays two parts, so the index is part of the key.
        itemsIndexed(visible, key = ::castKey) { index, member ->
            val key = castKey(index, member)
            val focus = if (key == returnFocusKey) Modifier.focusRequester(returnFocus) else Modifier
            CastCard(member, focus) { onOpenPerson(member, key) }
        }
    }
}

private fun castKey(index: Int, member: CastMember): String = "$index:${member.name}"

/** Select must start on a cast card whose current focus outline has been drawn. */
internal class CastFocusFrame {
    var focused by mutableStateOf(false)
        private set
    var canSelect = false
        private set

    fun onFocusChanged(isFocused: Boolean) {
        if (focused == isFocused) return
        canSelect = false
        focused = isFocused
    }

    fun onOutlineDrawn() {
        if (focused) canSelect = true
    }
}

@Composable
private fun CastCard(member: CastMember, modifier: Modifier, onClick: () -> Unit) {
    val focusFrame = remember { CastFocusFrame() }
    Column(
        modifier = Modifier.width(200.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TvFocusable(
            modifier = modifier
                .onFocusChanged { focusFrame.onFocusChanged(it.isFocused) }
                .onPreviewKeyEvent { event ->
                    event.type == KeyEventType.KeyDown && !focusFrame.canSelect &&
                        (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter)
                }
                .tvFocusRing(focusFrame.focused, 80.dp, onDrawn = focusFrame::onOutlineDrawn),
            onClick = onClick,
            accessibleLabel = accessibilityLabel(member.name, member.role, "View credits"),
            clickLabel = "View credits",
            cornerRadius = 80.dp,
            focusScale = 1f,
            focusRing = false,
        ) { _ ->
            Box(
                Modifier.size(160.dp).clip(CircleShape).background(TvColor.Elevated),
                contentAlignment = Alignment.Center,
            ) {
                DetailArtwork(
                    url = member.photo,
                    targetWidth = 342,
                    modifier = Modifier.size(160.dp).clip(CircleShape),
                    // §9.9: no photo gives the initials, not an empty circle.
                    fallback = initials(member.name),
                )
            }
        }
        Spacer(Modifier.height(TvGeom.FocusLabelGap))
        Text(
            text = member.name,
            style = TvType.CardTitle.copy(fontSize = 25.sp, lineHeight = 31.sp),
            color = TvColor.TextPrimary,
            maxLines = 1,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
        )
        member.role?.takeIf(String::isNotBlank)?.let {
            Text(
                text = it,
                style = TvType.Meta.copy(fontSize = 23.sp, lineHeight = 29.sp),
                color = TvColor.TextSecondary,
                maxLines = 1,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** "Jeremy Allen White" → "JA". One letter when the name is one word. */
internal fun initials(name: String): String = name
    .trim()
    .split(' ')
    .filter(String::isNotEmpty)
    .take(2)
    .map { it.first().uppercaseChar() }
    .joinToString("")
