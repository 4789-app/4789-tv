@file:Suppress("OPT_IN_USAGE")

package com.fourseveneightnine.tv.client.ui.screens.detail

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.data.images.PosterRequest
import com.fourseveneightnine.tv.client.data.images.TmdbSize
import com.fourseveneightnine.tv.client.data.meta.Episode
import com.fourseveneightnine.tv.client.data.meta.Meta
import com.fourseveneightnine.tv.client.data.meta.Ratings
import com.fourseveneightnine.tv.client.playback.PlayResult
import com.fourseveneightnine.tv.client.ui.LocalShellState
import com.fourseveneightnine.tv.client.ui.components.BadgeSpec
import com.fourseveneightnine.tv.client.ui.components.Badges
import com.fourseveneightnine.tv.client.ui.components.ButtonKind
import com.fourseveneightnine.tv.client.ui.components.PivotSpec
import com.fourseveneightnine.tv.client.ui.components.ShelfHeader
import com.fourseveneightnine.tv.client.ui.components.SidePanel
import com.fourseveneightnine.tv.client.ui.components.SidePanelRow
import com.fourseveneightnine.tv.client.ui.components.Skeleton
import com.fourseveneightnine.tv.client.ui.components.StateBlock
import com.fourseveneightnine.tv.client.ui.components.TvButton
import com.fourseveneightnine.tv.client.ui.components.TvFocusable
import com.fourseveneightnine.tv.client.ui.components.TvRatingsRow
import com.fourseveneightnine.tv.client.ui.nav.ClientNav
import com.fourseveneightnine.tv.client.ui.screens.collections.AddToCollectionSheet
import com.fourseveneightnine.tv.client.ui.screens.collections.CollectionCandidate
import com.fourseveneightnine.tv.client.ui.screens.streams.StreamsDrawer
import com.fourseveneightnine.tv.client.ui.screens.streams.StreamsViewModel
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvGeom
import com.fourseveneightnine.tv.client.ui.theme.TvMotion
import com.fourseveneightnine.tv.client.ui.theme.TvShape
import com.fourseveneightnine.tv.client.ui.theme.TvSpace
import com.fourseveneightnine.tv.client.ui.theme.TvType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which full-height panel is open over Detail. */
private enum class DetailPanel { More, Synopsis }

/**
 * One title: what it is, how to play it, and what is near it (`TV_DESIGN_SPEC.md` §9).
 *
 * **Why this page is a `verticalScroll` column and not a `LazyColumn`.** Detail has at most four
 * rails, and the Play button must hold the screen's one `FocusRequester` (plan §7.4 rule 2). A
 * requester inside a lazy item detaches the moment that item scrolls out of the viewport, and
 * calling `requestFocus()` on an unattached requester is the fault that killed the remote in
 * `tv-dpad-focus-destroyed-by-loading-shelf.md`. The rails themselves are still `LazyRow`s, which
 * is where the item counts actually are.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun DetailScreen(type: String, id: String, nav: ClientNav) {
    val context = LocalContext.current
    val client = remember(context) { context.clientGraph }
    val scope = rememberCoroutineScope()
    val viewModel = remember(type, id) { DetailViewModel(type, id, client, scope) }
    val state by viewModel.state.collectAsState()
    val shell = LocalShellState.current

    val playFocus = remember { FocusRequester() }
    val sourcesFocus = remember { FocusRequester() }
    var panel by remember { mutableStateOf<DetailPanel?>(null) }
    var episodeMenu by remember { mutableStateOf<Episode?>(null) }
    var addTo by remember { mutableStateOf<CollectionCandidate?>(null) }
    var finding by remember { mutableStateOf<FindingState?>(null) }
    var sourcesTarget by remember { mutableStateOf<Pair<Int?, Int?>?>(null) }
    var trailerInstalled by remember { mutableStateOf(false) }

    val meta = state.meta
    val pick = remember(meta, state.progress) { meta?.let { DetailPlay.pick(it, state.progress) } }
    val warmSeason = pick?.season
    val warmEpisode = pick?.episode
    val warmKey = if (type != "series" || (warmSeason != null && warmEpisode != null)) {
        "$type:$id:$warmSeason:$warmEpisode"
    } else null
    val warmSources = remember(warmKey) {
        warmKey?.let { StreamsViewModel(type, id, warmSeason, warmEpisode, client, scope) }
    }
    DisposableEffect(warmSources) { onDispose { warmSources?.stop() } }
    val todayIso = remember { today() }

    // §9.3: once the hero has scrolled out, a strip names the title. The flag is written from a
    // `snapshotFlow`, never read in composition, so scrolling does not recompose the page — only
    // the strip and the backdrop's own layer change, and only when the flag flips.
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val strip = remember { mutableStateOf(false) }
    LaunchedEffect(scroll, density) {
        val threshold = with(density) { DetailScroll.ShowAt.toPx() }
        val deadBand = with(density) { DetailScroll.DeadBand.toPx() }
        snapshotFlow { scroll.value }.collect { value ->
            strip.value = DetailScroll.showStrip(value.toFloat(), threshold, deadBand, strip.value)
        }
    }

    // The rail borrows focus and must be able to give it back.
    DisposableEffect(Unit) {
        val restore = { runCatching { playFocus.requestFocus() }; Unit }
        shell.restoreContentFocus = restore
        onDispose { if (shell.restoreContentFocus === restore) shell.restoreContentFocus = null }
    }

    // A PackageManager query is a Binder call and is slow on Fire OS. Asked once, off the main
    // thread. A trailer with nothing to play it is a button that lies, so it is missing (§9.10.13).
    LaunchedEffect(meta?.trailerYouTubeID) {
        val videoId = meta?.trailerYouTubeID ?: return@LaunchedEffect
        trailerInstalled = withContext(Dispatchers.Default) { canPlayTrailer(context, videoId) }
    }

    // Focus is placed once, on entry — never again because data arrived (plan §7.4 rule 3).
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }

    fun startPlay(forceList: Boolean = false, fromStart: Boolean = false, season: Int? = null, episode: Int? = null) {
        val title = meta ?: return
        val base = (pick ?: PlayPick()).let { chosen ->
            if (season == null) chosen else PlayPick(season, episode, null, chosen.label)
        }
        val request = base.request(title, forceList = forceList, fromStart = fromStart)
        if (forceList) {
            sourcesTarget = request.season to request.episode
            return
        }
        val job = scope.launch {
            when (val result = client.playFlow.play(request)) {
                // The shell navigates to the player on `PlaybackSession.openRequests`.
                PlayResult.Opened -> finding = null
                PlayResult.ShowList -> {
                    finding = null
                    sourcesTarget = request.season to request.episode
                }
                is PlayResult.Failed -> {
                    finding = null
                    nav.toast(result.message)
                }
            }
        }
        finding = FindingState(job = job, season = request.season, episode = request.episode)
    }

    fun closeSources() {
        sourcesTarget = null
        scope.launch {
            delay(16)
            runCatching { sourcesFocus.requestFocus() }
        }
    }

    // Register the page fallback before its drawer, so the drawer owns BACK while visible.
    BackHandler(enabled = panel != null || episodeMenu != null || finding != null || addTo != null) {
        when {
            addTo != null -> addTo = null
            episodeMenu != null -> episodeMenu = null
            panel != null -> panel = null
            finding != null -> {
                finding?.job?.cancel()
                finding = null
            }
        }
    }

    Box(Modifier.fillMaxSize().background(TvColor.Canvas)) {
        Backdrop(meta) { strip.value }

        CompositionLocalProvider(LocalBringIntoViewSpec provides PivotSpec.columnKeepVisible()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .then(if (sourcesTarget != null) Modifier.focusProperties { canFocus = false } else Modifier)
                    .padding(start = 96.dp, top = 72.dp, bottom = 54.dp),
            ) {
                when {
                    state.failed -> MetaError(viewModel::load, Modifier.focusRequester(playFocus))
                    else -> {
                        Hero(
                            meta = meta,
                            ratings = state.ratings,
                            badges = state.posterBadges,
                            loading = state.loading,
                            playLabel = when {
                                meta == null -> "Loading title"
                                state.loading && meta.type == "series" -> "Loading episodes"
                                else -> pick?.label ?: "Play"
                            },
                            playEnabled = meta != null && !(state.loading && meta.type == "series"),
                            showTrailer = trailerInstalled,
                            playModifier = Modifier.focusRequester(playFocus),
                            sourcesModifier = Modifier.focusRequester(sourcesFocus),
                            onPlay = { startPlay() },
                            onSources = { startPlay(forceList = true) },
                            onWatchlist = {
                                val current = meta
                                if (current != null) {
                                    scope.launch {
                                        client.activeLibrary.saveToWatchlist(
                                            canonicalId = current.id,
                                            mediaType = current.type,
                                            title = current.title,
                                            posterUrl = current.poster,
                                        )
                                        nav.toast("Saved to Watchlist.")
                                    }
                                }
                            },
                            onTrailer = { meta?.trailerYouTubeID?.let { playTrailer(context, it) } },
                            onMore = { panel = DetailPanel.More },
                            onSynopsis = { panel = DetailPanel.Synopsis },
                        )
                        Spacer(Modifier.height(16.dp))
                        Rails(
                            state = state,
                            todayIso = todayIso,
                            onSeason = viewModel::selectSeason,
                            onEpisodePlay = { startPlay(season = it.season, episode = it.episode) },
                            onEpisodeMenu = { episodeMenu = it },
                            // §9.10.14 wants OK here to search for the name. The shell has no
                            // search route that takes a query yet, so the card names the device
                            // that can do it, the way the Streams refusal does.
                            onPerson = { nav.toast("Searching by cast needs your iPhone.") },
                            onOpen = {
                                DetailPreview.stage(it)
                                nav.openDetail(it.type, it.id)
                            },
                            onAddToCollection = { ref ->
                                addTo = CollectionCandidate(ref.id, ref.type, ref.title, ref.poster)
                            },
                        )
                    }
                }
            }
        }

        if (meta != null && !state.failed) StickyStrip(meta) { strip.value }

        finding?.let { current ->
            FindingCard(
                line = AUTO_PICK_LINE,
                onShowAll = {
                    current.job.cancel()
                    finding = null
                    sourcesTarget = current.season to current.episode
                },
            )
        }

        when (panel) {
            DetailPanel.More -> MorePanel(
                onAddToCollection = {
                    panel = null
                    meta?.let { addTo = CollectionCandidate(it.id, it.type, it.title, it.poster) }
                },
                onMarkWatched = {
                    panel = null
                    viewModel.markWatched(pick?.season, pick?.episode)
                    nav.toast("Marked watched.")
                },
                onPlayFromStart = {
                    panel = null
                    startPlay(fromStart = true)
                },
                onClose = { panel = null },
            )

            DetailPanel.Synopsis -> SidePanel(header = "Synopsis", onClose = { panel = null }) {
                Text(
                    text = meta?.description.orEmpty(),
                    style = TvType.Body,
                    color = TvColor.TextPrimary,
                    modifier = Modifier
                        .width(560.dp)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                )
            }

            null -> Unit
        }

        episodeMenu?.let { episode ->
            EpisodeMenu(
                episode = episode,
                onPlay = {
                    episodeMenu = null
                    startPlay(season = episode.season, episode = episode.episode)
                },
                onSources = {
                    episodeMenu = null
                    sourcesTarget = episode.season to episode.episode
                },
                onMarkWatched = {
                    episodeMenu = null
                    viewModel.markWatched(episode.season, episode.episode)
                },
                onClose = { episodeMenu = null },
            )
        }

        addTo?.let { candidate ->
            AddToCollectionSheet(candidate = candidate, onDismiss = { addTo = null })
        }

        sourcesTarget?.let { (season, episode) ->
            StreamsDrawer(
                type = type,
                id = id,
                season = season,
                episode = episode,
                title = meta?.title ?: id,
                subtitle = if (season != null && episode != null) {
                    val episodeTitle = meta?.videos?.firstOrNull { it.season == season && it.episode == episode }?.title
                    listOfNotNull("S$season E$episode", episodeTitle).joinToString(" · ")
                } else null,
                episodes = meta?.videos.orEmpty(),
                resumeFromMs = pick?.takeIf { it.season == season && it.episode == episode }?.resumeFromMs,
                posterUrl = meta?.poster,
                backdropUrl = meta?.backdrop,
                prefetched = warmSources?.takeIf { season == warmSeason && episode == warmEpisode },
                onDismiss = ::closeSources,
                onSettings = { sourcesTarget = null; nav.openSettings("addons") },
                onToast = nav::toast,
            )
        }
    }

}

/** The auto-pick card's own coroutine, so OK on "Show all sources" can really cancel it. */
private data class FindingState(val job: Job, val season: Int?, val episode: Int?)

// ---------------------------------------------------------------------------- hero

/**
 * The backdrop, spec §9.2, drawn the way Home draws its own (`HomeHero.kt` `HomeBackdrop`).
 *
 * The old version wrote a full-bleed 1920 × 1080 image, a 1560 × 1080 wash and a 1920 × 380 wash
 * every frame: about 4.5 million pixels on a 2.07 million pixel panel before one rail. The GPU on
 * the onn 4K Pro is fill bound with a 10 ms floor, and most of that fill lay under an opaque wash.
 *
 * So the image is sharp (w1280) and drawn only across the top-right 1220 × 900 px, which is the
 * region the left column and the rails leave clear, and both fades live inside that box. There is
 * no canvas fill of its own — the screen's own `Box` already painted it, and a second full-screen
 * fill is 2 MPix of overdraw per frame. About 2.05 million pixels now, down from 4.48 million.
 */
@Composable
private fun Backdrop(meta: Meta?, dimmed: () -> Boolean) {
    val context = LocalContext.current
    val url = meta?.backdrop ?: meta?.poster
    // §9.3: the backdrop drops to 35% behind the sticky strip. Read inside `graphicsLayer`, so the
    // tween costs a layer alpha and not a recomposition.
    val dim = animateFloatAsState(
        targetValue = if (dimmed()) STRIP_BACKDROP_DIM else 1f,
        animationSpec = tween(TvMotion.PanelSlideMillis, easing = TvMotion.Std),
        label = "backdropDim",
    )
    Box(Modifier.fillMaxSize()) {
        // The picture runs under the title. The old box started 700 px in, so the left column was
        // bare canvas, and the fade on top of that was solid black for most of its width.
        Box(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .height(BackdropHeight)
                .graphicsLayer { alpha = dim.value },
        ) {
            if (url != null) {
                AsyncImage(
                    model = remember(url) { PosterRequest.backdrop(url).toImageRequest(context) },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to TvColor.Canvas.copy(alpha = 0.92f),
                            0.38f to TvColor.Canvas.copy(alpha = 0.68f),
                            0.62f to TvColor.Canvas.copy(alpha = 0.20f),
                            0.82f to Color.Transparent,
                        ),
                    ),
            )
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(BackdropBottomFade)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, TvColor.Canvas))),
            )
        }
    }
}

/** The hero picture. It stops above the episode rail; it is not a second full-screen plate. */
private val BackdropHeight = 780.dp
private val BackdropBottomFade = 220.dp

/** §9.3: the backdrop dims to 35% once the strip is up. */
private const val STRIP_BACKDROP_DIM = 0.35f

/**
 * §9.3: the sticky strip that names the title once the hero has scrolled out.
 *
 * It sits outside the scrolling column and fades rather than slides, and its alpha is read inside
 * `graphicsLayer`, so a page that is being scrolled never recomposes because of it.
 */
@Composable
private fun StickyStrip(meta: Meta, shown: () -> Boolean) {
    val alpha = animateFloatAsState(
        targetValue = if (shown()) 1f else 0f,
        animationSpec = tween(TvMotion.CrossfadeMillis, easing = TvMotion.Std),
        label = "stripAlpha",
    )
    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha.value },
    ) {
        Spacer(Modifier.height(90.dp))
        Text(
            text = meta.title,
            style = TvType.PlateTitle.copy(fontWeight = FontWeight.SemiBold),
            color = TvColor.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = TvGeom.ContentLeft).width(1200.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = DetailFormat.metaLine(meta),
            style = TvType.Meta,
            color = TvColor.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = TvGeom.ContentLeft).width(1200.dp),
        )
        Spacer(Modifier.height(48.dp))
        Box(
            Modifier
                .padding(start = TvGeom.ContentLeft)
                .width(1604.dp)
                .height(1.dp)
                .background(TvColor.Border),
        )
    }
}

/**
 * When the strip is up (§9.3, §9.10.11).
 *
 * The threshold has a dead band, so a page parked on the boundary does not flicker the strip on
 * and off as the scroll settles. Pure, and in pixels, because the design canvas is scaled to the
 * real panel and a raw pixel constant would mean a different place on every box.
 */
internal object DetailScroll {
    /** The hero is 60% out by here. */
    val ShowAt = 520.dp
    val DeadBand = 40.dp

    fun showStrip(scrollPx: Float, thresholdPx: Float, deadBandPx: Float, shown: Boolean): Boolean =
        if (shown) scrollPx > thresholdPx - deadBandPx else scrollPx > thresholdPx
}

@Composable
private fun Hero(
    meta: Meta?,
    ratings: Ratings,
    badges: List<DetailBadge>,
    loading: Boolean,
    playLabel: String,
    playEnabled: Boolean,
    showTrailer: Boolean,
    playModifier: Modifier,
    sourcesModifier: Modifier,
    onPlay: () -> Unit,
    onSources: () -> Unit,
    onWatchlist: () -> Unit,
    onTrailer: () -> Unit,
    onMore: () -> Unit,
    onSynopsis: () -> Unit,
) {
    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.width(1160.dp)) {
            Spacer(Modifier.height(8.dp))
            if (meta == null) {
                Skeleton(Modifier.size(520.dp, 160.dp), sweep = loading)
                Spacer(Modifier.height(20.dp))
                Skeleton(Modifier.size(520.dp, 24.dp), sweep = loading)
            } else {
                // §9.9: no logo gives the title at 56 Bold over two lines.
                if (meta.logo != null) {
                    DetailArtwork(
                        url = meta.logo,
                        targetWidth = 500,
                        modifier = Modifier.height(160.dp).width(520.dp),
                        fallback = meta.title,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.BottomStart,
                    )
                } else {
                    Text(
                        text = meta.title,
                        style = TvType.HeroTitle.copy(fontSize = 64.sp, lineHeight = 72.sp),
                        color = TvColor.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    text = DetailFormat.metaLine(meta),
                    style = TvType.Body.copy(fontSize = 30.sp, lineHeight = 38.sp),
                    color = TvColor.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // §9.9: all ratings missing drops the row and the synopsis moves up by 56 px.
            if (!ratings.isEmpty || meta?.imdbRating != null) {
                Spacer(Modifier.height(14.dp))
                TvRatingsRow(ratings, fallbackImdb = meta?.imdbRating)
            }

            Spacer(Modifier.height(20.dp))
            Synopsis(meta?.description, loading, onSynopsis)

            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(TvSpace.S)) {
                CinematicAction(playLabel, onPlay, primary = true, enabled = playEnabled, modifier = playModifier)
                CinematicAction("Sources", onSources, modifier = sourcesModifier)
                CinematicAction("Watchlist", onWatchlist)
                if (showTrailer) CinematicAction("Trailer", onTrailer)
                CinematicAction("More", onMore)
            }
        }

        Poster(
            meta,
            badges,
            Modifier.align(Alignment.TopEnd).padding(end = 96.dp),
        )
    }
}

@Composable
private fun CinematicAction(
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    TvFocusable(
        onClick = { if (enabled) onClick() },
        modifier = modifier,
        enabled = enabled,
        focusWhenDisabled = true,
        accessibleLabel = label,
        cornerRadius = 34.dp,
        focusScale = 1.035f,
        focusRing = false,
    ) { focused ->
        Box(
            Modifier
                .height(72.dp)
                .clip(CircleShape)
                .background(
                    when {
                        !enabled -> TvColor.Elevated2
                        focused || primary -> Color.White
                        else -> TvColor.Elevated.copy(alpha = 0.88f)
                    },
                )
                .border(1.dp, if (focused) Color.White else Color.White.copy(alpha = 0.18f), CircleShape)
                .padding(horizontal = 30.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (primary) Canvas(Modifier.size(22.dp)) {
                    val triangle = Path().apply {
                        moveTo(size.width * 0.18f, size.height * 0.06f)
                        lineTo(size.width * 0.91f, size.height * 0.5f)
                        lineTo(size.width * 0.18f, size.height * 0.94f)
                        close()
                    }
                    drawPath(triangle, if (enabled) TvColor.Canvas else TvColor.TextMuted)
                }
                Text(
                    label,
                    style = TvType.ControlLabel.copy(fontWeight = FontWeight.SemiBold, fontSize = 28.sp),
                    color = when {
                        !enabled -> TvColor.TextMuted
                        focused || primary -> TvColor.Canvas
                        else -> TvColor.TextPrimary
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun Poster(meta: Meta?, badges: List<DetailBadge>, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(360.dp, 540.dp)
            .clip(TvShape.CardProminent)
            .background(TvColor.Elevated)
            .border(1.dp, TvColor.Border, TvShape.CardProminent),
        contentAlignment = Alignment.Center,
    ) {
        DetailArtwork(
            url = meta?.poster,
            targetWidth = 500,
            modifier = Modifier.fillMaxSize(),
            // §9.9: no poster gives the title at 32 SemiBold, centred.
            fallback = meta?.title,
            contentScale = ContentScale.Fit,
        )
        // §9.4: the badge column, top-right, 12 px in, 28 tall, 6 px apart. It never animates.
        if (badges.isNotEmpty()) {
            Column(
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                badges.forEach { PosterBadge(it.spec()) }
            }
        }
    }
}

/** The ink for each badge. `Badges` already owns cached, 4K and HDR; DV borrows the HDR plate. */
private fun DetailBadge.spec(): BadgeSpec = when (this) {
    DetailBadge.Cached -> Badges.Cached
    DetailBadge.FourK -> Badges.FourK
    DetailBadge.Hdr -> Badges.Hdr
    DetailBadge.DolbyVision -> BadgeSpec("DV", Badges.Hdr.fill, Badges.Hdr.labelColor)
}

@Composable
private fun PosterBadge(spec: BadgeSpec) {
    Box(
        Modifier
            .height(28.dp)
            .clip(TvShape.Badge)
            .background(spec.fill)
            .border(1.dp, spec.borderColor, TvShape.Badge)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(spec.label, style = TvType.Badge, color = spec.labelColor, maxLines = 1)
    }
}

/** Full synopsis wraps with the page; long descriptions also offer the reading sheet. */
@Composable
private fun Synopsis(text: String?, loading: Boolean, onExpand: () -> Unit) {
    if (text.isNullOrBlank()) {
        if (loading) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(4) { Skeleton(Modifier.size(700.dp, 24.dp), sweep = it < 2) }
            }
        }
        return
    }
    val truncated = text.length > SYNOPSIS_CHARACTERS
    if (!truncated) {
        Text(
            text, style = TvType.Body.copy(fontSize = 29.sp, lineHeight = 39.sp, fontWeight = FontWeight.Medium),
            color = TvColor.TextPrimary,
            modifier = Modifier.width(1080.dp),
        )
        return
    }
    TvFocusable(onClick = onExpand, cornerRadius = 8.dp, focusScale = 1f, focusRing = false) { focused ->
        Text(
            text = text,
            style = TvType.Body.copy(fontSize = 29.sp, lineHeight = 39.sp, fontWeight = FontWeight.Medium),
            color = TvColor.TextPrimary,
            modifier = Modifier.width(1080.dp)
                .then(if (focused) Modifier.border(2.dp, Color.White, TvShape.Chip) else Modifier),
        )
    }
}

/** Four lines of 24 px at 720 wide is about this many characters (§9.10.12). */
private const val SYNOPSIS_CHARACTERS = 165

// ---------------------------------------------------------------------------- rails

@Composable
private fun Rails(
    state: DetailUiState,
    todayIso: String,
    onSeason: (Int) -> Unit,
    onEpisodePlay: (Episode) -> Unit,
    onEpisodeMenu: (Episode) -> Unit,
    onPerson: (String) -> Unit,
    onOpen: (com.fourseveneightnine.tv.client.data.meta.MetaRef) -> Unit,
    onAddToCollection: (com.fourseveneightnine.tv.client.data.meta.MetaRef) -> Unit,
) {
    val meta = state.meta
    // §9.5 rail order. Series: seasons, episodes, cast, More Like This. Movie: cast, then similar.
    if (state.isSeries) {
        SeasonsChips(
            seasons = meta?.seasons.orEmpty(),
            selected = state.selectedSeason,
            onSelect = onSeason,
        )
        Spacer(Modifier.height(TvSpace.L))
        DetailSectionTitle("Episodes")
        Spacer(Modifier.height(TvSpace.S))
        EpisodesRow(
            episodes = state.episodes,
            progress = state.progress,
            showRuntimeMinutes = meta?.runtimeMinutes,
            todayIso = todayIso,
            onPlay = onEpisodePlay,
            onMenu = onEpisodeMenu,
            loading = state.loading,
        )
        Spacer(Modifier.height(TvSpace.L))
    }

    // §9.8 asks these two rails for a skeleton while the page loads, and this page does not give
    // them one. Cast and More Like This both arrive inside the one `Meta`, so a skeleton here is a
    // promise the page cannot keep: when the meta lands with no cast, the block it was holding
    // disappears and More Like This jumps up 252 px under the ring. A rail is drawn once it has
    // something in it, and after that it never moves. The hero and the episodes row still carry
    // the loading state, so the page is never bare.
    if (meta?.cast?.isNotEmpty() == true) {
        DetailSectionTitle("Cast")
        Spacer(Modifier.height(TvSpace.S))
        CastRow(meta.cast, onPerson)
        Spacer(Modifier.height(TvSpace.L))
    }

    if (meta?.similar?.isNotEmpty() == true) {
        DetailSectionTitle("More like this")
        Spacer(Modifier.height(TvSpace.S))
        MoreLikeThis(meta.similar, onOpen, onAddToCollection)
    }
}

@Composable
private fun DetailSectionTitle(label: String) {
    Text(
        label,
        style = TvType.ShelfHeader.copy(fontSize = 32.sp, lineHeight = 40.sp),
        color = TvColor.TextPrimary,
    )
}

// ---------------------------------------------------------------------------- panels and cards

/** §9.4: the "More" panel. "Open on your iPhone" waits for a wire call that does not exist yet. */
@Composable
private fun MorePanel(
    onAddToCollection: () -> Unit,
    onMarkWatched: () -> Unit,
    onPlayFromStart: () -> Unit,
    onClose: () -> Unit,
) {
    val first = remember { FocusRequester() }
    SidePanel(header = "More", onClose = onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(TvSpace.XS)) {
            SidePanelRow("Add to collection", false, onAddToCollection, Modifier.focusRequester(first))
            SidePanelRow("Mark watched", false, onMarkWatched)
            SidePanelRow("Play from the start", false, onPlayFromStart)
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

/** §9.6 episode menu, on long-OK. */
@Composable
private fun EpisodeMenu(
    episode: Episode,
    onPlay: () -> Unit,
    onSources: () -> Unit,
    onMarkWatched: () -> Unit,
    onClose: () -> Unit,
) {
    val first = remember { FocusRequester() }
    SidePanel(header = NextUpHeader(episode), onClose = onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(TvSpace.XS)) {
            SidePanelRow("Play", false, onPlay, Modifier.focusRequester(first))
            SidePanelRow("Sources", false, onSources)
            SidePanelRow("Mark watched", false, onMarkWatched)
        }
    }
    LaunchedEffect(episode) { runCatching { first.requestFocus() } }
}

private fun NextUpHeader(episode: Episode): String =
    com.fourseveneightnine.tv.client.playback.NextUp.subtitle(episode.season, episode.episode, episode.title)

/**
 * §10.5: when the rules say "play best without asking", the Streams screen does not show and this
 * card sits over Detail instead. OK cancels the auto-pick and opens the list.
 */
@Composable
private fun FindingCard(line: String, onShowAll: () -> Unit) {
    val focus = remember { FocusRequester() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .size(720.dp, 200.dp)
                .clip(TvShape.Panel)
                .background(TvColor.Elevated)
                .border(1.dp, TvColor.Border, TvShape.Panel)
                .padding(horizontal = 40.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Finding a source", style = TvType.ShelfHeader, color = TvColor.TextPrimary)
            Spacer(Modifier.height(12.dp))
            Text(
                text = line,
                style = TvType.CardTitle,
                color = TvColor.TextSecondary,
                maxLines = 1,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(TvSpace.S))
            TvButton(
                "Show all sources",
                onShowAll,
                kind = ButtonKind.Ghost,
                height = TvGeom.ButtonHeightDense,
                modifier = Modifier.width(280.dp).focusRequester(focus),
            )
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/**
 * The card's line 2 is "AIOStreams · 1080p · cached", updated in place (§10.5). Auto-pick does not
 * publish a running best today, so the card states what it is doing rather than inventing a name.
 */
private const val AUTO_PICK_LINE = "Checking your add-ons"

/** §9.8 Error: the poster and title still draw, and Retry takes focus. */
@Composable
private fun MetaError(onRetry: () -> Unit, actionModifier: Modifier) {
    Box(Modifier.fillMaxWidth().height(600.dp), contentAlignment = Alignment.CenterStart) {
        StateBlock(
            headline = "Couldn't load this title",
            line = "The add-on didn't answer.",
            actionLabel = "Retry",
            onAction = onRetry,
            actionModifier = actionModifier,
            announceAsError = true,
        )
    }
}

// ---------------------------------------------------------------------------- artwork

/**
 * One artwork call, sized to what will be drawn.
 *
 * Coil's `TmdbSizeInterceptor` rewrites the TMDB size segment from the resolved target, so a 236 px
 * card never pulls a 2000 px poster; [targetWidth] is the hint for a URL Coil cannot measure yet.
 * [fallback] draws under the image, so §9.9's "the title, centred" is what a missing poster shows —
 * and it goes the moment the image paints. Drawing it underneath is not enough, because a
 * crossfading bitmap is translucent while it arrives and the words read straight through it. This
 * is `TvArtwork`'s rule (`Components.kt`), which Detail never got.
 */
@Composable
internal fun DetailArtwork(
    url: String?,
    targetWidth: Int,
    modifier: Modifier = Modifier,
    fallback: String?,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
) {
    val sized = url?.takeIf(String::isNotBlank)?.let { TmdbSize.sized(it, targetWidth) }
    val painted = remember(sized) { mutableStateOf(false) }
    Box(modifier, contentAlignment = alignment) {
        if (fallback != null && !painted.value) {
            Text(
                text = fallback,
                style = TvType.PlateTitle,
                color = TvColor.TextSecondary,
                maxLines = 3,
                textAlign = if (alignment == Alignment.BottomStart) TextAlign.Start else TextAlign.Center,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = TvSpace.S),
            )
        }
        if (sized != null) {
            AsyncImage(
                model = sized,
                contentDescription = null,
                contentScale = contentScale,
                alignment = alignment,
                onSuccess = { painted.value = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// ---------------------------------------------------------------------------- trailer

/** "yyyy-MM-dd" on the box's own clock, for the unaired test. */
private fun today(): String {
    val calendar = java.util.Calendar.getInstance()
    return String.format(
        java.util.Locale.US,
        "%04d-%02d-%02d",
        calendar.get(java.util.Calendar.YEAR),
        calendar.get(java.util.Calendar.MONTH) + 1,
        calendar.get(java.util.Calendar.DAY_OF_MONTH),
    )
}

private fun trailerIntent(videoId: String): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

private fun canPlayTrailer(context: Context, videoId: String): Boolean = runCatching {
    trailerIntent(videoId).resolveActivity(context.packageManager) != null
}.getOrDefault(false)

private fun playTrailer(context: Context, videoId: String) {
    runCatching { context.startActivity(trailerIntent(videoId)) }
}
