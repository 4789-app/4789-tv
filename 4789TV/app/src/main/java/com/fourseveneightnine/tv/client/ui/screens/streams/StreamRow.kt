package com.fourseveneightnine.tv.client.ui.screens.streams

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.fourseveneightnine.tv.catalog.EinthusanSource
import com.fourseveneightnine.tv.client.data.streams.StreamRow
import com.fourseveneightnine.tv.client.data.streams.StreamRanker
import com.fourseveneightnine.tv.client.data.streams.HdrFormat
import com.fourseveneightnine.tv.client.data.streams.OriginalAudioLanguage
import com.fourseveneightnine.tv.R
import com.fourseveneightnine.tv.client.ui.components.SkeletonSweep
import com.fourseveneightnine.tv.client.ui.components.TvFocusableWithMenu
import com.fourseveneightnine.tv.client.ui.components.rememberSkeletonSweep
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvShape
import com.fourseveneightnine.tv.client.ui.theme.TvType
import java.util.Locale
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val SourceGlassFill = Brush.verticalGradient(
    listOf(Color(0x89313131), TvColor.Elevated.copy(alpha = 0.58f)),
)
private val SourceFocusGlassFill = Brush.verticalGradient(
    listOf(Color(0xAB393939), TvColor.Elevated.copy(alpha = 0.80f)),
)
private val SourceGlassRim = TvColor.TextPrimary.copy(alpha = 0.17f)

/**
 * One row in the sources list (`TV_DESIGN_SPEC.md` §10.3).
 *
 * One shared Cinema row for both source views. The theme-colored halo identifies remote focus.
 * Format artwork describes add-on reports; complete filenames and technical facts live on hold OK.
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
    originalLanguage: String? = null,
    posterUrl: String? = null,
    releaseDate: String? = null,
    translucent: Boolean = false,
) {
    val native = EinthusanSource.parse(row.url)
    if (native != null) {
        EinthusanTicket(row.title, native, posterUrl, releaseDate, width, modifier, onPlay, onMenu)
        return
    }
    val quality = row.quality?.takeIf(String::isNotBlank) ?: "Quality unknown"
    val languages = row.audioLanguageNames
    val original = languages.firstOrNull { OriginalAudioLanguage.normalize(it) == OriginalAudioLanguage.normalize(originalLanguage) }
        .takeIf { OriginalAudioLanguage.normalize(originalLanguage) != null }
    val matchingLanguage = original ?: languages.firstOrNull { language -> preferredLanguages.any { it.equals(language, true) } }
        ?: languages.firstOrNull()
    val others = languages.filterNot { it == matchingLanguage }
    val additional = preferredLanguages.firstNotNullOfOrNull { preferred ->
        others.firstOrNull { OriginalAudioLanguage.normalize(it) == OriginalAudioLanguage.normalize(preferred) }
    } ?: others.firstOrNull()
    val language = matchingLanguage?.let { it + (if (it == original) " (Original)" else "") +
        (additional?.let { extra -> " · $extra" + if (others.size > 1) " +${others.size - 1}" else "" } ?: "")
    }
    val video = when (row.facts.hdr) {
        HdrFormat.DOLBY_VISION -> "Dolby Vision"
        HdrFormat.HDR10 -> sourceHdrLabel(row.releaseName)
        else -> null
    }
    val audio = sourceAudioLabel(row.facts.audio)
    val imax = sourceImaxLabel(row.releaseName)
    val cached = row.cachedDebrid
    val confirmedTB = StreamRanker.deliveryPriority(row) == 0
    val availability = StreamRanker.deliveryLabel(row)
    val service = row.cachedHint.service ?: row.facts.cachedHint.service
    val provider = listOfNotNull(row.addonName, service).distinct().joinToString(" · ")
    val size = row.sizeGB?.let { String.format(Locale.US, "%.1f GB", it) }
    TvFocusableWithMenu(
        onClick = onPlay, onLongClick = onMenu, modifier = modifier,
        accessibleLabel = listOfNotNull(quality, video, audio, imax, row.facts.audioChannels, language, size,
            provider, availability, reason).joinToString(", "),
        clickLabel = "Play this source", longClickLabel = "Source details",
        cornerRadius = 16.dp, focusScale = 1f, focusRing = false,
    ) { focused ->
        val shape = TvShape.CardProminent
        val surface = when {
            !translucent -> Modifier.background(if (focused) SourceFocusGlassFill else SourceGlassFill, shape)
            focused -> Modifier.background(Brush.horizontalGradient(listOf(
                Color.White.copy(alpha = 0.095f), Color.White.copy(alpha = 0.035f),
            )), shape)
            else -> Modifier.background(TvColor.Canvas.copy(alpha = 0.24f), shape)
        }
        val glow = if (!focused) Modifier
            else if (translucent) Modifier.dropShadow(shape, Shadow(radius = 16.dp, color = Color.Black.copy(alpha = 0.18f)))
            else Modifier
                .dropShadow(shape, Shadow(radius = 24.dp, spread = 2.dp, color = TvColor.Accent.copy(alpha = 0.24f)))
                .dropShadow(shape, Shadow(radius = 6.dp, spread = 1.dp, color = TvColor.Accent.copy(alpha = 0.28f)))
        val edge = when {
            focused && translucent -> Color.White.copy(alpha = 0.68f)
            focused -> lerp(TvColor.Accent, TvColor.TextPrimary, 0.32f)
            confirmedTB -> TvColor.Cached.copy(alpha = 0.55f)
            else -> SourceGlassRim
        }
        Column(
            Modifier.width(width).heightIn(min = ROW_HEIGHT)
                .then(glow)
                .then(surface)
                .border(if (focused && translucent) 2.dp else 1.dp, edge, shape)
                .padding(horizontal = 22.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(quality, style = TvType.SourceTitle.copy(fontSize = 28.sp, fontWeight = FontWeight.SemiBold), color = TvColor.TextPrimary)
                video?.let { SourceFormatMark(it) }
                audio?.let { SourceFormatMark(it) }
                imax?.let { SourceFormatMark(it) }
                row.facts.audioChannels?.let { Text(it, style = TvType.SourceMeta, color = TvColor.TextSecondary) }
                Spacer(Modifier.weight(1f))
                if (cached) {
                    Image(painterResource(R.drawable.ic_source_bolt), contentDescription = null,
                        colorFilter = ColorFilter.tint(TvColor.Cached), modifier = Modifier.size(30.dp))
                    Text(availability, style = TvType.Badge,
                        color = TvColor.Cached)
                }
                else if (!row.url.isNullOrBlank()) Text(availability, style = TvType.Badge, color = TvColor.TextSecondary)
            }
            if (language != null || size != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (language != null) Text(language, style = TvType.ControlLabel, color = TvColor.TextPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    else Spacer(Modifier.weight(1f))
                    size?.let { Text(it, style = TvType.data(24), color = TvColor.TextPrimary) }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(provider, style = TvType.SourceMeta, color = TvColor.TextSecondary, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (caution && reason != null) Text(reason, style = TvType.SourceMeta, color = TvColor.Warning,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun EinthusanTicket(
    title: String,
    source: EinthusanSource,
    posterUrl: String?,
    releaseDate: String?,
    width: Dp,
    modifier: Modifier,
    onPlay: () -> Unit,
    onDetails: () -> Unit,
) {
    val ink = Color(0xFF492424)
    val stock = Color(0xFFE9AD9C)
    val tier = if (source.ultraHd) "Ultra HD · 1080p" else "HD"
    val captions = if (source.subtitles) "CC SUBS" else "NO SUBS"
    val captionInk = if (source.subtitles) Color(0xFF185333) else ink
    val date = releaseDate?.let {
        runCatching { LocalDate.parse(it).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)) }.getOrNull()
    }
    TvFocusableWithMenu(
        onClick = onPlay, onLongClick = onDetails, modifier = modifier,
        accessibleLabel = "Einthusan, $title, ${source.language.label}, $tier, $captions",
        clickLabel = "Play this source", longClickLabel = "Source details", focusScale = 1f, cornerRadius = 16.dp,
    ) { focused ->
        Row(
            Modifier.width(width).height(ROW_HEIGHT).clip(TvShape.CardProminent)
                .background(if (focused) lerp(stock, Color.White, 0.18f) else stock)
                .border(if (focused) 3.dp else 1.dp, ink, TvShape.CardProminent)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (posterUrl != null) {
                AsyncImage(
                    model = posterUrl, contentDescription = null, contentScale = ContentScale.Crop,
                    colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }),
                    modifier = Modifier.width(72.dp).height(ROW_HEIGHT - 24.dp).clip(TvShape.Control),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("EINTHUSAN", color = ink, style = TvType.Meta.copy(fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, letterSpacing = 3.sp))
                Text(title, color = ink, style = TvType.SourceTitle.copy(
                    fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(listOfNotNull(source.language.label, tier, date).joinToString(" · "), color = ink,
                        style = TvType.SourceMeta, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Text(captions, color = captionInk, style = TvType.Meta.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.border(1.dp, captionInk, TvShape.Control).padding(horizontal = 6.dp))
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("BALCONY", color = ink, style = TvType.Badge.copy(fontFamily = FontFamily.Monospace))
                Canvas(Modifier.width(112.dp).height(18.dp)) {
                    val step = size.width / 28
                    for (index in 0 until 28) {
                        val seed = source.id[index % source.id.length].code + index * 11
                        drawRect(ink, topLeft = androidx.compose.ui.geometry.Offset(index * step, 0f),
                            size = androidx.compose.ui.geometry.Size(step * (if (seed % 5 < 2) 0.42f else 0.68f), size.height))
                    }
                }
                Text("Nº ${source.id}", color = ink, style = TvType.Meta.copy(fontFamily = FontFamily.Monospace))
            }
        }
    }
}

@Composable
internal fun SourceFormatMark(label: String, height: Dp = 26.dp, textStyle: TextStyle = TvType.ControlLabel) {
    val logo = when (label) {
        "Dolby Vision" -> R.drawable.brand_dolby_vision
        "Dolby Atmos" -> R.drawable.brand_dolby_atmos
        "DD+", "Dolby Digital+", "Dolby Digital Plus" -> R.drawable.brand_dolby_digital_plus
        "Dolby Digital" -> R.drawable.brand_dolby_digital
        "HDR" -> R.drawable.brand_hdr
        "HDR10" -> R.drawable.brand_hdr10
        "HDR10+" -> R.drawable.brand_hdr10plus
        "IMAX" -> R.drawable.brand_imax
        else -> null
    }
    if (logo != null) {
        val painter = painterResource(logo)
        Image(painter, contentDescription = null,
            colorFilter = ColorFilter.tint(TvColor.TextPrimary), contentScale = ContentScale.Fit,
            modifier = Modifier.height(height).aspectRatio(painter.intrinsicSize.width / painter.intrinsicSize.height))
    } else Text(label, style = textStyle, color = TvColor.TextSecondary, maxLines = 1)
}

private val IMAX = Regex("(?:^|[ ._-])IMAX(?=$|[ ._-])", RegexOption.IGNORE_CASE)
internal fun sourceImaxLabel(text: String): String? = "IMAX".takeIf { IMAX.containsMatchIn(text) }

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

internal fun sourceAudioLabel(audio: String?): String? = when (audio) {
    "Atmos" -> "Dolby Atmos"
    "DD+" -> "Dolby Digital+"
    "DD" -> "Dolby Digital"
    "TrueHD" -> "Dolby TrueHD"
    else -> audio
}

/** Match the loaded row's surface and let one soft band pass through the placeholder. */
@Composable
internal fun StreamSkeletonRow(sweep: Boolean, modifier: Modifier = Modifier) {
    val sweeping = rememberSkeletonSweep(sweep)
    val shape = TvShape.CardProminent
    Box(modifier.fillMaxWidth().height(ROW_HEIGHT)
        .clip(shape)
        .background(SourceGlassFill)
        .border(1.dp, SourceGlassRim, shape)
        .drawWithCache {
            val band = size.width * SkeletonSweep.BAND_FRACTION
            val bandSize = Size(band, size.height)
            val light = Brush.linearGradient(
                colors = listOf(Color.Transparent, Color(0x31EBEBEB), Color.Transparent),
                start = Offset.Zero, end = Offset(band, size.height / 2f),
            )
            val radius = CornerRadius(7.dp.toPx())
            val inset = 22.dp.toPx()
            val rowTop = 21.dp.toPx()
            onDrawBehind {
                drawRoundRect(Color(0x56EBEBEB), Offset(inset, rowTop),
                    Size(size.width * 0.25f, 16.dp.toPx()), radius)
                drawRoundRect(Color(0x3CEBEBEB), Offset(inset, rowTop + 38.dp.toPx()),
                    Size(size.width * 0.43f, 12.dp.toPx()), radius)
                drawRoundRect(Color(0x2FEBEBEB), Offset(inset, rowTop + 70.dp.toPx()),
                    Size(size.width * 0.29f, 11.dp.toPx()), radius)
                if (sweeping) {
                    val x = SkeletonSweep.phase.floatValue * size.width - band / 2f
                    translate(left = x) { drawRect(light, size = bandSize) }
                }
            }
        })
}

internal val ROW_WIDTH = 1556.dp
internal val ROW_HEIGHT = 128.dp
internal val ROW_REASON_HEIGHT = 128.dp

/** §10.10: with neither a release name nor a title field, the row still says what it is. */
internal const val UNNAMED = "Unnamed release"
