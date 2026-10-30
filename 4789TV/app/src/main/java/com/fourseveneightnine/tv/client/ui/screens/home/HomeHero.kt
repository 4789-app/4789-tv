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

/** A stable decision area: title, useful facts, short synopsis and watching actions. */
internal val HeroBandHeight = 500.dp

/** Artwork owns the complete width; text and controls keep their own safe margins. */
@Composable
internal fun HomeBackdrop(card: () -> HomeCard?, modifier: Modifier = Modifier) {
    val settled = card()
    val backdrop = settled?.backdropUrl
    val poster = settled?.posterUrl
    val unavailable = remember(backdrop) { mutableStateOf(false) }
    val ready = remember(backdrop) { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    Box(modifier.fillMaxSize()) {
        if (poster != null && (!ready.value || unavailable.value)) {
            // A separate small portrait while loading, or for portrait-only/error artwork.
            coil3.compose.AsyncImage(
                model = remember(context, poster) { PosterRequest.poster(poster).toImageRequest(context) },
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
                onUnavailable = { unavailable.value = true },
                onLandscapeReady = { ready.value = true },
            )
        }
    }
}

/** A 2:3 poster that ends 24 px above the first shelf title. */
private val PosterFallbackHeight = 376.dp
private val PosterFallbackWidth = 251.dp

/**
 * The hero strip, spec §3.2 and §3.3. Every coordinate below is the spec's, minus the 54 px the
 * safe line already took.
 *
 * The synopsis stays a readout. Play/Resume and Details are ordinary remote targets.
 */
@Composable
internal fun HomeHero(
    card: () -> HomeCard?,
    ratings: (HomeCard) -> Ratings = { Ratings.NONE },
    resume: () -> Boolean = { false },
    onPlay: () -> Unit = {},
    onDetails: () -> Unit = {},
    actionModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
) {
    val settled = card()
    Box(modifier.fillMaxWidth().height(HeroBandHeight)) {
        if (settled != null) HeroText(settled, ratings(settled), resume(), onPlay, onDetails, actionModifier)
    }
}

@Composable
private fun HeroText(card: HomeCard?, ratings: Ratings, resume: Boolean, onPlay: () -> Unit,
    onDetails: () -> Unit, actionModifier: Modifier) {
    if (card == null) return
    Box(Modifier.size(800.dp, HeroBandHeight)) {
        val logoFailed = remember(card.logoUrl) { mutableStateOf(false) }
        val logoWidthPx = with(LocalDensity.current) { 720.dp.roundToPx() }
        // A compact readable foreground preserves the right-hand artwork.
        Box(
            Modifier.offset(y = 24.dp).size(720.dp, 148.dp),
            contentAlignment = Alignment.BottomStart,
        ) {
            if (card.logoUrl != null && !logoFailed.value) {
                val context = androidx.compose.ui.platform.LocalContext.current
                coil3.compose.AsyncImage(
                    model = remember(context, card.logoUrl, logoWidthPx) {
                        coil3.request.ImageRequest.Builder(context)
                            .data(TmdbSize.logo(card.logoUrl, logoWidthPx)).tmdbLogo().crossfade(false).build()
                    },
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    onError = { logoFailed.value = true },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = card.title,
                    style = TvType.HeroTitle,
                    color = TvColor.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(720.dp),
                )
            }
        }
        val meta = HomePlan.metaLine(card)
        if (meta.isNotEmpty()) {
            Text(
                text = meta,
                style = TvType.Body,
                color = TvColor.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.offset(y = 200.dp).width(760.dp),
            )
        }
        TvRatingsRow(
            ratings = ratings,
            fallbackImdb = card.rating,
            modifier = Modifier.offset(y = 244.dp),
        )
        card.overview?.takeIf { it.isNotBlank() }?.let { synopsis ->
            Text(
                text = synopsis,
                style = TvType.Body.copy(fontSize = 26.sp, lineHeight = 35.sp),
                color = TvColor.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.offset(y = 292.dp).width(760.dp),
            )
        }
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.offset(y = 396.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
        ) {
            TvButton(if (resume) "Resume" else "Play", onPlay, kind = ButtonKind.Primary,
                modifier = actionModifier.width(220.dp))
            TvButton("Details and sources", onDetails, kind = ButtonKind.Secondary)
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
        Skeleton(Modifier.offset(y = 24.dp).size(720.dp, 148.dp), sweep = true)
        Skeleton(Modifier.offset(y = 222.dp).size(620.dp, 24.dp), sweep = true)
        TvButton(
            label = "Open menu",
            onClick = onOpenMenu,
            modifier = actionModifier.offset(y = 320.dp).width(240.dp),
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
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(20.dp),
        ) {
            repeat(6) {
                Skeleton(Modifier.size(HomePosterWidth, HomePosterHeight), sweep = sweep)
            }
        }
    }
}
