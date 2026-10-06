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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.client.data.meta.CastMember
import com.fourseveneightnine.tv.client.ui.components.TvFocusable
import com.fourseveneightnine.tv.client.ui.components.accessibilityLabel
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvGeom
import com.fourseveneightnine.tv.client.ui.theme.TvSpace
import com.fourseveneightnine.tv.client.ui.theme.TvType

/**
 * Spec §9.4 Cast card: 180 wide, a 160 px circle, name over role.
 *
 * OK opens a search for that name (§9.10.14). It is the cheapest useful thing OK can do there, and
 * it beats a dead card; a person page is not in this wave.
 */
@Composable
internal fun CastRow(
    cast: List<CastMember>,
    onOpenPerson: (String) -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
) {
    if (loading) {
        SkeletonRow(modifier, width = 160.dp, height = 160.dp)
        return
    }
    if (cast.isEmpty()) return
    LazyRow(
        modifier = modifier.fillMaxWidth().focusRestorer(),
        contentPadding = PaddingValues(start = TvGeom.RowEdgeInset, end = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(TvSpace.M),
    ) {
        // Cinemeta repeats a name when one actor plays two parts, so the index is part of the key.
        itemsIndexed(cast.take(24), key = { index, member -> "$index:${member.name}" }) { _, member ->
            CastCard(member) { onOpenPerson(member.name) }
        }
    }
}

@Composable
private fun CastCard(member: CastMember, onClick: () -> Unit) {
    Column(
        modifier = Modifier.width(200.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TvFocusable(
            onClick = onClick,
            accessibleLabel = accessibilityLabel(member.name, member.role, "Cast member"),
            clickLabel = "Search for cast member",
            cornerRadius = 80.dp,
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
