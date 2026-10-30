package com.fourseveneightnine.tv.client.ui.screens.home

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
import com.fourseveneightnine.tv.client.data.meta.Ratings
import com.fourseveneightnine.tv.client.ui.components.TvRatingsRow
import com.fourseveneightnine.tv.client.ui.components.Skeleton
import com.fourseveneightnine.tv.client.ui.components.ButtonKind
import com.fourseveneightnine.tv.client.ui.components.TvButton
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvGeom
import com.fourseveneightnine.tv.client.ui.theme.TvType

/** The hero is only the title and the description. Play lives on the title page. */
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
 * The button is the only focusable thing here. A hero button you cannot press is decoration, and
 * this screen has none (spec §3.9.15).
 */
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
private fun HeroText(card: HomeCard?, ratings: Ratings) {
    if (card == null) return
    Box(Modifier.size(1100.dp, HeroBandHeight)) {
        // The logo slot: 520 x 160, its BOTTOM edge at y 300. With no logo the title takes the
        // same slot as two lines of 56 Bold (spec §3.8).
        Box(
            Modifier.offset(y = 32.dp).size(880.dp, 160.dp),
            contentAlignment = Alignment.BottomStart,
        ) {
            if (card.logoUrl != null) {
                val context = androidx.compose.ui.platform.LocalContext.current
                coil3.compose.AsyncImage(
                    model = PosterRequest(card.logoUrl, 500).toImageRequest(context),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = card.title,
                    style = TvType.HeroTitle,
                    color = TvColor.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(880.dp),
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
                modifier = Modifier.offset(y = 222.dp).width(880.dp),
            )
        }
        TvRatingsRow(
            ratings = ratings,
            fallbackImdb = card.rating,
            modifier = Modifier.offset(y = 264.dp),
        )
        val synopsis = card.overview?.takeIf { it.isNotBlank() }
        if (synopsis != null) {
            val scroll = rememberScrollState()
            val focused = remember { mutableStateOf(false) }
            LaunchedEffect(card.key, synopsis) { scroll.scrollTo(0) }
            val scope = rememberCoroutineScope()
            val linePixels = with(LocalDensity.current) { 37.dp.toPx() }
            Text(
                text = synopsis,
                style = TvType.Body.copy(fontSize = 27.sp, lineHeight = 37.sp, fontWeight = FontWeight.Medium),
                color = TvColor.TextPrimary,
                modifier = Modifier.offset(y = 316.dp).width(1060.dp).heightIn(max = 148.dp)
                    .border(if (focused.value) 2.dp else 0.dp, if (focused.value) Color.White else Color.Transparent, RoundedCornerShape(8.dp))
                    .onFocusChanged { focused.value = it.isFocused }
                    .verticalScroll(scroll).focusable().onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) false else when {
                            event.key == Key.DirectionDown && scroll.value < scroll.maxValue -> {
                                scope.launch { scroll.scrollBy(linePixels) }; true
                            }
                            event.key == Key.DirectionUp && scroll.value > 0 -> {
                                scope.launch { scroll.scrollBy(-linePixels) }; true
                            }
                            else -> false
                        }
                    },
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
        Skeleton(Modifier.offset(y = 32.dp).size(880.dp, 160.dp), sweep = true)
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
