@file:Suppress("OPT_IN_USAGE")

package com.fourseveneightnine.tv.client.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fourseveneightnine.tv.client.data.meta.MetaRef
import com.fourseveneightnine.tv.client.ui.components.TvFocusableWithMenu
import com.fourseveneightnine.tv.client.ui.components.accessibilityLabel
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvGeom
import com.fourseveneightnine.tv.client.ui.theme.TvShape

/**
 * Spec §9.4 More Like This: a standard 236 × 354 poster row.
 *
 * OK opens that title's Detail; long-OK adds it to a collection, which is the global rule for a
 * poster in §17.
 */
@Composable
internal fun MoreLikeThis(
    similar: List<MetaRef>,
    onOpen: (MetaRef) -> Unit,
    onAddToCollection: (MetaRef) -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
) {
    if (loading) {
        SkeletonRow(modifier)
        return
    }
    if (similar.isEmpty()) return
    LazyRow(
        modifier = modifier.fillMaxWidth().focusRestorer(),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        contentPadding = PaddingValues(start = TvGeom.RowEdgeInset, end = 48.dp),
    ) {
        items(similar.take(30), key = { "${it.type}:${it.id}" }) { ref ->
            Column(Modifier.width(268.dp)) {
                TvFocusableWithMenu(
                    onClick = { onOpen(ref) },
                    onLongClick = { onAddToCollection(ref) },
                    accessibleLabel = accessibilityLabel(ref.title, ref.type),
                    clickLabel = "Open details",
                    longClickLabel = "Add to collection",
                    cornerRadius = 16.dp,
                    focusScale = 1.05f,
                    focusRing = false,
                ) { focused ->
                    Box(
                        Modifier
                            .size(268.dp, 402.dp)
                            .clip(TvShape.CardProminent)
                            .background(TvColor.PosterPlaceholder),
                    ) {
                        DetailArtwork(
                            url = ref.poster,
                            targetWidth = 500,
                            modifier = Modifier.size(268.dp, 402.dp),
                            fallback = ref.title,
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        )
                        if (focused) Box(
                            Modifier.size(268.dp, 402.dp)
                                .border(3.dp, Color.White.copy(alpha = 0.95f), TvShape.CardProminent),
                        )
                    }
                }
            }
        }
    }
}
