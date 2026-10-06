package com.fourseveneightnine.tv.client.ui.screens.streams

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.client.data.streams.StreamRow
import com.fourseveneightnine.tv.client.data.streams.HdrFormat
import com.fourseveneightnine.tv.R
import com.fourseveneightnine.tv.client.ui.components.TvFocusableWithMenu
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvShape
import com.fourseveneightnine.tv.client.ui.theme.TvType
import java.util.Locale

/**
 * One row in the sources list (`TV_DESIGN_SPEC.md` §10.3).
 *
 * One reusable source card for Detail's drawer and the full Sources screen. Format badges use
 * Google's Material Symbols vectors; source claims remain text beside them so an icon never
 * invents a format the add-on did not state.
 */
@Composable
internal fun StreamListRow(
    row: StreamRow,
    onPlay: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = ROW_WIDTH,
    reason: String? = null,
) {
    val premium = row.facts.qualityRank >= 5 && row.facts.hdr != HdrFormat.NONE
    val quality = row.quality?.takeIf(String::isNotBlank)
    val releaseType = sourceReleaseType(row.releaseName)
    val colors = if (premium) listOf(Color(0xFF2B252E), Color(0xFF252329))
    else listOf(Color(0xFF282A2D), Color(0xFF212429))
    val channels = row.facts.audioChannels
    val audio = sourceAudioLabel(row.facts.audio)?.takeUnless { it == channels }
    val accessible = listOfNotNull(
        quality, row.facts.hdr.takeIf { it != HdrFormat.NONE }?.let {
            if (it == HdrFormat.DOLBY_VISION) "Dolby Vision" else "HDR"
        }, releaseType, audio, channels, row.addonName,
        "${row.sizeGB?.let { String.format(Locale.US, "%.1f GB", it) } ?: "size unknown"}",
        if (row.cachedHint.cached) "cached" else null,
    ).joinToString(", ")
    TvFocusableWithMenu(
        onClick = onPlay,
        onLongClick = onMenu,
        modifier = modifier,
        accessibleLabel = accessible,
        clickLabel = "Play this source",
        cornerRadius = 16.dp,
        focusScale = 1f,
        focusRing = false,
    ) { focused ->
        val ink = if (focused) TvColor.Canvas else TvColor.TextPrimary
        Column(
            Modifier
                .width(width)
                .heightIn(min = if (reason == null) ROW_HEIGHT else ROW_REASON_HEIGHT)
                .clip(TvShape.CardProminent)
                .background(Brush.horizontalGradient(if (focused) listOf(Color.White, Color.White) else colors))
                .border(
                    2.dp,
                    when {
                        focused -> Color.White
                        premium -> Color(0xFFD5BAF1)
                        else -> Color(0xFFFFB08B).copy(alpha = 0.70f)
                    },
                    TvShape.CardProminent,
                )
                .padding(horizontal = 26.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                SourcePill(
                    icon = if (quality == "4K") R.drawable.ic_source_4k else if (quality == "1080p") R.drawable.ic_source_hd else R.drawable.ic_source_video,
                    label = quality ?: "Source",
                    accent = TvColor.Accent,
                    focused = focused,
                    prominent = true,
                )
                when (row.facts.hdr) {
                    HdrFormat.DOLBY_VISION -> SourcePill(R.drawable.ic_source_hdr, "DOLBY VISION", Color(0xFFD7B7F2), focused)
                    HdrFormat.HDR10 -> SourcePill(R.drawable.ic_source_hdr, sourceHdrLabel(row.releaseName), Color(0xFFFFD47B), focused)
                    else -> Unit
                }
                if (row.facts.hdr == HdrFormat.DOLBY_VISION && row.releaseName.contains("HDR10", true)) {
                    SourcePill(R.drawable.ic_source_hdr, sourceHdrLabel(row.releaseName), Color(0xFFFFD47B), focused)
                }
                releaseType?.let { SourcePill(R.drawable.ic_source_video, it, Color(0xFFFFBBA1), focused) }
                Spacer(Modifier.weight(1f))
                if (row.cachedHint.cached) CachedLightning()
            }
            Text(
                row.addonName,
                style = TvType.ControlLabel.copy(fontSize = 26.sp, lineHeight = 33.sp, fontWeight = FontWeight.SemiBold),
                color = ink,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                audio?.let { SourcePill(R.drawable.ic_source_surround, it, Color(0xFFFFC896), focused) }
                channels?.let { SourcePill(null, it, Color(0xFFBDF49E), focused) }
                row.audioLanguages.firstOrNull()?.let { SourcePill(null, it, Color(0xFFEAD9C4), focused) }
                Spacer(Modifier.weight(1f))
                Text(
                    listOfNotNull(
                        row.sizeGB?.let { String.format(Locale.US, "%.1f GB", it) },
                        row.facts.codec?.uppercase(Locale.US),
                    ).joinToString(" · "),
                    style = TvType.Meta.copy(fontSize = 21.5.sp, lineHeight = 28.sp),
                    color = if (focused) ink else Color(0xFFE2E5EA),
                    maxLines = 1,
                    textAlign = TextAlign.End,
                )
            }
            Text(
                text = row.releaseName.ifBlank { UNNAMED },
                style = TvType.Meta.copy(fontSize = 21.5.sp, lineHeight = 28.sp),
                color = if (focused) ink.copy(alpha = 0.78f) else Color(0xFFD8DDE4),
                modifier = Modifier.fillMaxWidth(),
            )
            reason?.takeIf(String::isNotBlank)?.let {
                Text(
                    it,
                    style = TvType.Meta.copy(fontSize = 21.5.sp, lineHeight = 28.sp),
                    color = if (focused) ink.copy(alpha = 0.78f) else Color(0xFFFFC896),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SourcePill(icon: Int?, label: String, accent: Color, focused: Boolean, prominent: Boolean = false) {
    Box(
        Modifier.clip(TvShape.Badge)
            .background(if (focused) accent.copy(alpha = 0.32f) else accent.copy(alpha = 0.20f))
            .border(1.dp, accent.copy(alpha = if (focused) 0.72f else 0.56f), TvShape.Badge)
            .padding(horizontal = if (prominent) 11.dp else 9.dp, vertical = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            icon?.let {
                Image(
                    painter = painterResource(it), contentDescription = null,
                    colorFilter = ColorFilter.tint(if (focused) TvColor.Canvas else accent),
                    modifier = Modifier.width(if (prominent) 48.dp else 36.dp).height(if (prominent) 48.dp else 36.dp),
                )
            }
            Text(
                label,
                style = TvType.Badge.copy(fontSize = if (prominent) 23.sp else 21.sp, lineHeight = if (prominent) 28.sp else 26.sp, fontWeight = FontWeight.Bold),
                color = if (focused) TvColor.Canvas else Color.White,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CachedLightning() {
    Box(
        Modifier.width(58.dp).height(58.dp).clip(TvShape.Badge)
            .background(TvColor.Cached)
            .border(1.dp, TvColor.Cached, TvShape.Badge),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_source_bolt), contentDescription = "Cached",
            colorFilter = ColorFilter.tint(TvColor.Canvas),
            modifier = Modifier.width(46.dp).height(46.dp),
        )
    }
}

private val WEB_DL = Regex("\\bWEB[- .]?DL\\b", RegexOption.IGNORE_CASE)
private val WEB_RIP = Regex("\\bWEB[- .]?RIP\\b", RegexOption.IGNORE_CASE)
private val BLU_RAY = Regex("\\bBLU[- .]?RAY\\b|\\bBDRIP\\b", RegexOption.IGNORE_CASE)
private val HDTV = Regex("\\bHDTV\\b", RegexOption.IGNORE_CASE)

internal fun sourceReleaseType(text: String): String? = when {
    WEB_DL.containsMatchIn(text) -> "WEB-DL"
    WEB_RIP.containsMatchIn(text) -> "WEBRip"
    BLU_RAY.containsMatchIn(text) -> "Blu-ray"
    HDTV.containsMatchIn(text) -> "HDTV"
    else -> null
}

internal fun sourceHdrLabel(text: String): String = when {
    text.contains("HDR10+", ignoreCase = true) || text.contains("HDR10Plus", ignoreCase = true) -> "HDR10+"
    text.contains("HDR10", ignoreCase = true) -> "HDR10"
    else -> "HDR"
}

private fun sourceAudioLabel(audio: String?): String? = when (audio) {
    "Atmos" -> "Dolby Atmos"
    "DD+" -> "Dolby Digital+"
    "DD" -> "Dolby Digital"
    "TrueHD" -> "Dolby TrueHD"
    else -> audio
}

@Composable
internal fun CodecMark(label: String) {
    val (ink, fill) = codecInk(label)
    Box(
        Modifier
            .height(26.dp)
            .clip(TvShape.Badge)
            .background(fill)
            .padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = TvType.Badge.copy(fontWeight = FontWeight.SemiBold),
            color = ink,
            maxLines = 1,
        )
    }
}

private fun codecInk(label: String): Pair<Color, Color> {
    val key = label.lowercase(Locale.US)
    return when {
        "dolby vision" in key || key == "dv" -> Color(0xFF1A1A1A) to Color(0xFFB8A4FF)
        "hdr10" in key || key == "hdr" -> Color(0xFF1A1A1A) to Color(0xFFE6D18D)
        "hevc" in key || "h.265" in key || "h265" in key -> Color.White to Color(0xFF37404A)
        "av1" in key -> Color.White to Color(0xFF0E8A6A)
        "atmos" in key -> Color(0xFF1A1A1A) to Color(0xFF7EC8FF)
        "truehd" in key -> Color(0xFF1A1A1A) to Color(0xFF9AD7FF)
        "dts" in key -> Color.White to Color(0xFF8B3A3A)
        "aac" in key || "eac3" in key || "ac3" in key -> Color.White to Color(0xFF3A4A5C)
        else -> TvColor.TextPrimary to TvColor.Elevated2
    }
}
@Composable
private fun DataChip(label: String) {
    Box(
        Modifier
            .height(26.dp)
            .clip(TvShape.Badge)
            .background(TvColor.Elevated2)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = TvType.data(20), color = TvColor.TextSecondary, maxLines = 1)
    }
}

@Composable
private fun CachedBadge() {
    Box(
        Modifier
            .height(26.dp)
            .clip(TvShape.Badge)
            .background(TvColor.Cached.copy(alpha = 0.16f))
            .border(1.dp, TvColor.Cached.copy(alpha = 0.5f), TvShape.Badge)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("CACHED", style = TvType.Badge, color = TvColor.Cached, maxLines = 1)
    }
}

/** A skeleton row, for the tail while add-ons are still pending (§10.9 Partial). */
@Composable
internal fun StreamSkeletonRow(sweep: Boolean, modifier: Modifier = Modifier) {
    com.fourseveneightnine.tv.client.ui.components.Skeleton(
        modifier = modifier.width(ROW_WIDTH).heightIn(min = ROW_HEIGHT),
        sweep = sweep,
    )
}

internal val ROW_WIDTH = 1012.dp
internal val ROW_HEIGHT = 184.dp
internal val ROW_REASON_HEIGHT = 216.dp

/** §10.10: with neither a release name nor a title field, the row still says what it is. */
internal const val UNNAMED = "Unnamed release"
