package com.fourseveneightnine.tv.client.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.R
import com.fourseveneightnine.tv.client.data.meta.Ratings
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvShape
import com.fourseveneightnine.tv.client.ui.theme.TvType
import java.util.Locale

/** The same source logos and score order on Home and Detail. Missing scores take no space. */
@Composable
internal fun TvRatingsRow(ratings: Ratings, fallbackImdb: Double? = null, modifier: Modifier = Modifier) {
    val imdb = (ratings.imdb ?: fallbackImdb)?.takeIf { it > 0.0 }
    if (imdb == null && ratings.trakt == null && ratings.tmdb == null && ratings.letterboxd == null) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        imdb?.let { TvRatingBadge(R.drawable.ic_rating_imdb, "IMDb", 52.dp, 26.dp, oneDecimal(it)) }
        ratings.trakt?.let { TvRatingBadge(R.drawable.ic_rating_trakt, "Trakt", 30.dp, 30.dp, "$it%") }
        ratings.tmdb?.let { TvRatingBadge(R.drawable.ic_rating_tmdb, "TMDB", 142.dp, 20.dp, "$it%") }
        ratings.letterboxd?.let { TvRatingBadge(R.drawable.ic_rating_letterboxd, "Letterboxd", 67.dp, 25.dp, "${oneDecimal(it)}/5") }
    }
}

@Composable
private fun TvRatingBadge(icon: Int, provider: String, iconWidth: Dp, iconHeight: Dp, score: String) {
    Row(
        Modifier.height(48.dp).clip(TvShape.Chip)
            .background(Color(0xE6191B20))
            .border(1.dp, Color.White.copy(alpha = 0.20f), TvShape.Chip)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = provider,
            contentScale = ContentScale.Fit,
            modifier = Modifier.width(iconWidth).height(iconHeight),
        )
        Text(
            score,
            style = TvType.ControlLabel.copy(fontSize = 25.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold),
            color = TvColor.TextPrimary,
            maxLines = 1,
        )
    }
}

private fun oneDecimal(value: Double): String = String.format(Locale.US, "%.1f", value)
