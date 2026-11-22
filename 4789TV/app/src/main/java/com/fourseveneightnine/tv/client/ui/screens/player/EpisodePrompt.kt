package com.fourseveneightnine.tv.client.ui.screens.player

import com.fourseveneightnine.tv.client.data.meta.episodeDisplayTitle
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.client.data.images.PosterRequest
import com.fourseveneightnine.tv.client.playback.NextUpPlan
import com.fourseveneightnine.tv.client.ui.components.TvArtwork
import com.fourseveneightnine.tv.client.ui.components.TvFocusable
import com.fourseveneightnine.tv.client.ui.components.tvGlassPanel
import com.fourseveneightnine.tv.client.ui.components.tvGlassPill
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvType

internal fun episodeCoordinates(season: Int?, episode: Int?): String =
    listOfNotNull(season?.let { "Season $it" }, episode?.let { "Episode $it" }).joinToString("  ·  ")

/** The same reading order and action placement from the first preview through a retry. */
@Composable
internal fun EpisodePrompt(
    plan: NextUpPlan,
    modifier: Modifier = Modifier,
    status: String = "Up next",
    seconds: Int? = null,
    message: String? = null,
    actions: @Composable () -> Unit,
) {
    val episode = episodeDisplayTitle(plan.episodeTitle).takeUnless {
        it.isEmpty() || it.equals(plan.showTitle, true) || it.equals("Episode ${plan.episode}", true)
    }
    val detail = listOfNotNull(plan.showTitle.takeIf { episode != null },
        episodeCoordinates(plan.season, plan.episode), plan.runtimeLine).joinToString("  ·  ")
    val landscape = plan.stillUrl?.takeIf(String::isNotBlank) ?: plan.backdropUrl?.takeIf(String::isNotBlank)
    PlayerGlassCard(episode ?: plan.showTitle, detail, status, modifier,
        image = landscape ?: plan.posterUrl, portrait = landscape == null,
        seconds = seconds, message = message, actions = actions)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlayerGlassCard(
    title: String,
    detail: String?,
    status: String,
    modifier: Modifier = Modifier,
    image: String? = null,
    portrait: Boolean = false,
    seconds: Int? = null,
    message: String? = null,
    actions: @Composable () -> Unit,
) {
    var bitmap by remember(image) { mutableStateOf<Bitmap?>(null) }
    Column(
        modifier.padding(end = 80.dp, bottom = 56.dp).width(720.dp)
            .focusProperties { onExit = {
                if (requestedFocusDirection == FocusDirection.Left || requestedFocusDirection == FocusDirection.Right ||
                    requestedFocusDirection == FocusDirection.Up || requestedFocusDirection == FocusDirection.Down) cancelFocusChange()
            } }.focusGroup()
            .tvGlassPanel(30.dp, bitmap).semantics { paneTitle = status }
            .padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            if (!image.isNullOrBlank()) {
                val request = remember(image, portrait) {
                    if (portrait) PosterRequest.poster(image, 180) else PosterRequest(image, 280)
                }
                TvArtwork(request, title, Modifier.size(if (portrait) 72.dp else 136.dp, 96.dp)
                    .clearAndSetSemantics {}, shape = RoundedCornerShape(13.dp),
                    showTitleWhenMissing = false, contentScale = ContentScale.Crop, onBitmap = { bitmap = it })
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(status, style = TvType.ControlLabel.copy(fontSize = 20.sp, lineHeight = 26.sp),
                    color = if (message == null) Color(0xFFFFC29C) else TvColor.Warning, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(title, style = TvType.PlateTitle.copy(fontSize = 38.sp, lineHeight = 43.sp),
                    color = TvColor.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!detail.isNullOrBlank()) Text(detail, style = TvType.Meta.copy(fontSize = 22.sp, lineHeight = 28.sp),
                    color = Color(0xFFDADEE2), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (seconds != null && seconds > 10) Spacer(Modifier.width(76.dp))
            if (seconds != null && seconds in 1..10) {
                val shape = RoundedCornerShape(16.dp)
                Column(Modifier.width(76.dp).clip(shape).background(Color.Black.copy(alpha = 0.23f))
                    .border(1.dp, Color.White.copy(alpha = 0.20f), shape)
                    .clearAndSetSemantics { contentDescription = "Next episode in $seconds seconds" }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${seconds}s", style = TvType.data(29), color = TvColor.TextPrimary)
                    Spacer(Modifier.height(5.dp))
                    Box(Modifier.fillMaxWidth().height(3.dp).clip(shape).background(Color.White.copy(alpha = 0.19f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth((seconds / 10f).coerceIn(0f, 1f)).background(TvColor.Accent))
                    }
                }
            }
        }
        if (message != null) Text(message, style = TvType.Body, color = TvColor.TextPrimary)
        Spacer(Modifier.fillMaxWidth().height(1.dp).background(Brush.horizontalGradient(listOf(
            Color.White.copy(alpha = 0.26f), Color.White.copy(alpha = 0.06f)))))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { actions() }
    }
}

@Composable
internal fun EpisodeAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    icon: Int? = null,
) {
    val shape = RoundedCornerShape(28.dp)
    TvFocusable(onClick, modifier, accessibleLabel = label, focusScale = 1f, focusRing = false, cornerRadius = 28.dp) { focused ->
        val ink = if (focused) Color(0xFF211A17) else TvColor.TextPrimary
        Row(Modifier.heightIn(min = 56.dp).clip(shape)
            .then(if (focused) Modifier.background(Brush.verticalGradient(listOf(Color(0xFFFFD9BA), Color(0xFFFF9F6A))))
                .border(2.dp, Color(0xFFFFE5CC), shape)
                else Modifier.tvGlassPill({ null }, focused = false, selected = primary))
            .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            icon?.let { Image(painterResource(it), contentDescription = null, modifier = Modifier.size(27.dp),
                colorFilter = ColorFilter.tint(ink)) }
            Text(label, style = TvType.ControlLabel.copy(fontSize = 23.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1, color = ink)
        }
    }
}
