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
    caution: Boolean = false,
    preferredLanguages: Set<String> = emptySet(),
) {
    val quality = row.quality?.takeIf(String::isNotBlank) ?: "Quality unknown"
    val languages = row.audioLanguages.filter(String::isNotBlank).distinct()
    val matchingLanguage = languages.firstOrNull { language -> preferredLanguages.any { it.equals(language, true) } }
        ?: languages.firstOrNull()
    val language = matchingLanguage?.let { it + if (languages.size > 1) " +${languages.size - 1}" else "" } ?: "Language unknown"
    val formats = listOfNotNull(
        when (row.facts.hdr) {
            HdrFormat.DOLBY_VISION -> "Dolby Vision"
            HdrFormat.HDR10 -> sourceHdrLabel(row.releaseName)
            else -> null
        }, sourceReleaseType(row.releaseName), row.facts.codec?.uppercase(Locale.US),
    ).joinToString(" · ")
    val cache = if (row.cachedHint.cached) row.cachedHint.service?.let { "Cached · $it" } ?: "Cached" else "Cache unknown"
    val secondary = listOfNotNull(language, sourceAudioLabel(row.facts.audio), row.facts.audioChannels,
        row.sizeGB?.let { String.format(Locale.US, "%.1f GB", it) } ?: "Size unknown").joinToString(" · ")
    TvFocusableWithMenu(
        onClick = onPlay, onLongClick = onMenu, modifier = modifier,
        accessibleLabel = listOf(quality, cache, secondary, row.addonName, row.releaseName, reason.orEmpty()).joinToString(", "),
        clickLabel = "Play this source", cornerRadius = 16.dp, focusScale = 1f, focusRing = false,
    ) { focused ->
        Column(
            Modifier.width(width).heightIn(min = if (reason == null) ROW_HEIGHT else ROW_REASON_HEIGHT)
                .clip(TvShape.CardProminent)
                .background(if (focused) TvColor.Elevated2 else TvColor.Elevated)
                .border(if (focused) 3.dp else 1.dp, if (focused) TvColor.Focus else TvColor.Border, TvShape.CardProminent)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(quality, style = TvType.CardTitle.copy(fontSize = 28.sp, fontWeight = FontWeight.SemiBold), color = TvColor.TextPrimary)
                Text(formats, style = TvType.Meta, color = TvColor.TextSecondary, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(cache, style = TvType.Badge, color = if (row.cachedHint.cached) TvColor.Cached else TvColor.TextMuted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 330.dp))
            }
            Text(secondary, style = TvType.Meta.copy(fontSize = 22.sp, lineHeight = 29.sp),
                color = TvColor.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(row.addonName + " · " + row.releaseName.ifBlank { UNNAMED },
                style = TvType.Meta.copy(fontSize = 21.sp, lineHeight = 28.sp), color = TvColor.TextMuted,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
            reason?.takeIf(String::isNotBlank)?.let {
                Text(if (caution) "! $it" else it, style = TvType.Meta.copy(fontSize = 22.sp, lineHeight = 29.sp),
                    color = if (caution) TvColor.Warning else TvColor.TextSecondary,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
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
