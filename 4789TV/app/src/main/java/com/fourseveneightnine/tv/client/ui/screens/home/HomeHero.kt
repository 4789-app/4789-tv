package com.fourseveneightnine.tv.client.ui.screens.home

import coil3.request.crossfade

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics
import com.fourseveneightnine.tv.client.ui.LocalShellState
import com.fourseveneightnine.tv.client.ui.components.TvGlassArtwork
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.client.data.images.PosterRequest
import com.fourseveneightnine.tv.client.data.images.TmdbSize
import com.fourseveneightnine.tv.client.data.images.tmdbLogo
import com.fourseveneightnine.tv.client.data.meta.Ratings
import com.fourseveneightnine.tv.client.ui.components.TvRatingsRow
import com.fourseveneightnine.tv.client.ui.components.Skeleton
import com.fourseveneightnine.tv.client.ui.components.ButtonKind
import com.fourseveneightnine.tv.client.ui.components.TvButton
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvGeom
import com.fourseveneightnine.tv.client.ui.theme.TvType
import com.fourseveneightnine.tv.ui.TvTokens

/** A stable decision area: title, useful facts, short synopsis and watching actions. */
internal val HeroBandHeight = 372.dp
private val HeroTitleBlockHeight = 146.dp

/** Artwork owns the complete width; text and controls keep their own safe margins. */
@Composable
internal fun HomeBackdrop(card: () -> HomeCard?, modifier: Modifier = Modifier) {
    val settled = card()
    val backdrop = settled?.backdropUrl
    val poster = settled?.posterUrl
    val fallbackWidthPx = with(LocalDensity.current) { PosterFallbackWidth.roundToPx().coerceAtLeast(1) }
    val unavailable = remember(backdrop) { mutableStateOf(false) }
    val ready = remember(backdrop) { mutableStateOf(false) }
    val shell = LocalShellState.current
    val glass = remember(backdrop) { mutableStateOf<TvGlassArtwork?>(null) }
    SideEffect { shell.homeGlassArtwork = glass.value }
    DisposableEffect(shell) { onDispose { shell.homeGlassArtwork = null } }
    val context = androidx.compose.ui.platform.LocalContext.current
    Box(modifier.fillMaxSize().background(TvColor.Background)) {
        if (poster != null && (!ready.value || unavailable.value)) {
            // A separate small portrait while loading, or for portrait-only/error artwork.
            coil3.compose.AsyncImage(
                model = remember(context, poster, fallbackWidthPx) {
                    PosterRequest.poster(poster, fallbackWidthPx).toImageRequest(context)
                },
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.align(Alignment.TopEnd)
                    .padding(end = 96.dp, top = TvGeom.SafeTop)
                    .size(PosterFallbackWidth, PosterFallbackHeight)
                    .clip(com.fourseveneightnine.tv.client.ui.theme.TvShape.Card),
            )
        }
        if (backdrop != null && !unavailable.value) {
            com.fourseveneightnine.tv.client.ui.components.LandscapeBackdrop(
                url = backdrop,
                modifier = Modifier.align(Alignment.TopStart),
                onUnavailable = {
                unavailable.value = true
                ReceiverDiagnostics.benchmark("artwork.landscape") { "screen=home result=unavailable" }
            },
                onLandscapeReady = {
                ready.value = true
                ReceiverDiagnostics.benchmark("artwork.landscape") { "screen=home result=ready" }
            },
                onGlassArtwork = { glass.value = it },
            )
        }
    }
}

/** A 2:3 poster that ends 24 px above the first shelf title. */
private val PosterFallbackHeight = 336.dp
private val PosterFallbackWidth = 224.dp

/** A readout for the focused poster. The shelf owns remote focus and actions. */
@Composable
internal fun HomeHero(
    card: () -> HomeCard?,
    ratings: (HomeCard) -> Ratings = { Ratings.NONE },
    modifier: Modifier = Modifier,
) {
    val settled = card()
    Box(modifier.fillMaxWidth().height(HeroBandHeight)) {
        if (settled != null) HeroText(settled, ratings(settled))
    }
}

@Composable
private fun HeroText(card: HomeCard, ratings: Ratings) {
    Box(Modifier.size(840.dp, HeroBandHeight)) {
        val logoReady = remember(card.logoUrl) { mutableStateOf(false) }
        val logoWidthPx = with(LocalDensity.current) { 760.dp.roundToPx() }
        // A compact readable foreground preserves the right-hand artwork.
        Box(
            Modifier.size(760.dp, HeroTitleBlockHeight),
            contentAlignment = Alignment.BottomStart,
        ) {
            if (card.logoUrl != null) {
                val context = androidx.compose.ui.platform.LocalContext.current
                coil3.compose.AsyncImage(
                    model = remember(context, card.logoUrl, logoWidthPx) {
                        coil3.request.ImageRequest.Builder(context)
                            .data(TmdbSize.logo(card.logoUrl, logoWidthPx)).tmdbLogo().crossfade(false).build()
                    },
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.BottomStart,
                    onSuccess = { logoReady.value = true },
                    onError = { logoReady.value = false },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (!logoReady.value) {
                Text(
                    text = card.title,
                    style = TvType.HeroTitle.copy(fontSize = 58.sp, lineHeight = 68.sp),
                    color = TvColor.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(760.dp),
                )
            }
        }
        val meta = HomePlan.metaLine(card)
        if (meta.isNotEmpty()) {
            Text(
                text = meta,
                style = TvType.Body.copy(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium),
                color = TvColor.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.offset(y = 152.dp).width(820.dp),
            )
        }
        TvRatingsRow(
            ratings = ratings,
            fallbackImdb = card.rating,
            modifier = Modifier.offset(y = 195.dp),
            prominent = true,
        )
        card.overview?.takeIf { it.isNotBlank() }?.let { synopsis ->
            Text(
                text = synopsis,
                style = TvType.Body.copy(fontSize = 29.sp, lineHeight = 38.sp),
                color = TvColor.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.offset(y = 265.dp).width(820.dp),
            )
        }
    }
}

/**
 * Spec §3.7: the skeleton mirrors the loaded layout, so nothing jumps when data lands.
 *
 * The button is a real button, not a grey block. Something focusable has to be on screen after
 * every render — a screen with nothing focused trips the shell's rescue net and opens the rail by
 * itself, which is how a cold start on a slow box used to look.
 */
@Composable
internal fun HomeHeroSkeleton(
    modifier: Modifier = Modifier,
    actionModifier: Modifier = Modifier,
    onOpenMenu: () -> Unit,
) {
    Box(modifier.fillMaxWidth().height(HeroBandHeight)) {
        Skeleton(Modifier.size(760.dp, HeroTitleBlockHeight), sweep = true)
        Skeleton(Modifier.offset(y = 152.dp).size(620.dp, 26.dp), sweep = true)
        TvButton(
            label = "Open menu",
            onClick = onOpenMenu,
            modifier = actionModifier.offset(y = 302.dp).width(240.dp),
            kind = ButtonKind.Secondary,
        )
    }
}

/** A column of two shelf skeletons: only the first sweeps (spec §15.4). */
@Composable
internal fun HomeRowsSkeleton(modifier: Modifier = Modifier) {
    Column(modifier) {
        HomeShelfSkeleton(sweep = true)
        androidx.compose.foundation.layout.Spacer(Modifier.height(36.dp))
        HomeShelfSkeleton(sweep = false)
    }
}

@Composable
private fun HomeShelfSkeleton(sweep: Boolean) {
    Column {
        Skeleton(Modifier.size(240.dp, 32.dp), sweep = sweep)
        androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.padding(start = TvGeom.RowEdgeInset),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(TvGeom.CatalogPosterGap),
        ) {
            repeat(TvTokens.Geometry.CatalogPosterColumns) {
                Skeleton(Modifier.size(TvGeom.CatalogPosterWidth, TvGeom.CatalogPosterHeight), sweep = sweep)
            }
        }
    }
}
