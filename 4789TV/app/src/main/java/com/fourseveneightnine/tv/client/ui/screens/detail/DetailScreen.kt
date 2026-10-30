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
import androidx.compose.runtime.saveable.rememberSaveable
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
import coil3.request.ImageRequest
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.data.images.PosterRequest
import com.fourseveneightnine.tv.client.data.images.TmdbSize
import com.fourseveneightnine.tv.client.data.images.tmdbLogo
import com.fourseveneightnine.tv.client.data.meta.Episode
import com.fourseveneightnine.tv.client.data.meta.Meta
import com.fourseveneightnine.tv.client.data.meta.Ratings
import com.fourseveneightnine.tv.client.playback.PlayResult
import com.fourseveneightnine.tv.client.playback.PlayRequest
import com.fourseveneightnine.tv.client.playback.savedStream
import com.fourseveneightnine.tv.client.data.streams.StreamRow
import com.fourseveneightnine.tv.client.data.streams.debrid.DebridFile
import com.fourseveneightnine.tv.client.ui.screens.collections.TorboxFilePanel
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import com.fourseveneightnine.tv.client.data.library.SystemCollections

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
    var browsedSeason by rememberSaveable(type, id) { mutableStateOf<Int?>(null) }
    var browsedEpisode by rememberSaveable(type, id) { mutableStateOf<String?>(null) }
    var restoreEpisodeFocus by rememberSaveable(type, id) { mutableStateOf(false) }
    val viewModel = remember(type, id) { DetailViewModel(type, id, client, scope, browsedSeason) }
    val state by viewModel.state.collectAsState()
    val shell = LocalShellState.current

    val playFocus = remember { FocusRequester() }
    val sourcesFocus = remember { FocusRequester() }
    val episodeReturnFocus = remember { FocusRequester() }
    var placedEpisodeKey by remember { mutableStateOf<String?>(null) }
    var pendingEpisodeFocus by remember { mutableStateOf(restoreEpisodeFocus) }
    var panel by remember { mutableStateOf<DetailPanel?>(null) }
    var episodeMenu by remember { mutableStateOf<Episode?>(null) }
    var addTo by remember { mutableStateOf<CollectionCandidate?>(null) }
    var finding by remember { mutableStateOf<FindingState?>(null) }
    var sourcesTarget by remember { mutableStateOf<Pair<Int?, Int?>?>(null) }
    var trailerInstalled by remember { mutableStateOf(false) }
    var sourceFiles by remember { mutableStateOf<Triple<StreamRow, PlayRequest, List<DebridFile>>?>(null) }
    var fileOpening by remember { mutableStateOf(false) }
    var fileJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var watchlistSaving by remember { mutableStateOf(false) }

    val meta = state.meta
    val todayIso = remember { todayIso() }
    val pick = remember(meta, state.progress, todayIso) { meta?.let { DetailPlay.pick(it, state.progress, todayIso) } }
    val allUpcoming = remember(meta, todayIso) {
        meta != null && meta.type == "series" && meta.videos.isNotEmpty() &&
            meta.videos.all { DetailFormat.isUnaired(it.released, todayIso) }
    }
    var landscapeReady by remember(meta?.backdrop) { mutableStateOf(false) }
    val warmSeason = pick?.season
    val warmEpisode = pick?.episode
    val warmKey = if (type != "series" || (warmSeason != null && warmEpisode != null)) {
        "$type:$id:$warmSeason:$warmEpisode"
    } else null
    val warmSources = remember(warmKey) {
        warmKey?.let { StreamsViewModel(type, id, warmSeason, warmEpisode, client, scope) }
    }
    DisposableEffect(warmSources) { onDispose { warmSources?.stop() } }

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
        val restore = {
            if (restoreEpisodeFocus && placedEpisodeKey == browsedEpisode) pendingEpisodeFocus = true
            else runCatching { playFocus.requestFocus() }
            Unit
        }
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
    LaunchedEffect(Unit) { if (!restoreEpisodeFocus) runCatching { playFocus.requestFocus() } }
    LaunchedEffect(placedEpisodeKey, pendingEpisodeFocus) {
        if (pendingEpisodeFocus && placedEpisodeKey != null && placedEpisodeKey == browsedEpisode) {
            runCatching { episodeReturnFocus.requestFocus() }
            pendingEpisodeFocus = false
        }
    }
    LaunchedEffect(state.loading, state.failed, state.episodes, pendingEpisodeFocus) {
        if (pendingEpisodeFocus && !state.loading && (state.failed || state.episodes.none {
                "${it.season}:${it.episode}" == browsedEpisode
            })) {
            pendingEpisodeFocus = false
            restoreEpisodeFocus = false
            runCatching { playFocus.requestFocus() }
        }
    }

    suspend fun offerActualFiles(request: PlayRequest): Boolean {
        val saved = client.savedCatalogItem(request.type, request.id) ?: return false
        val row = saved.savedStream(request.season, request.episode) ?: return false
        if (row.torboxFileID != null) return false
        try {
            finding = finding?.copy(phaseOverride = "Loading source files")
            val files = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                client.services.value?.debrid?.torboxFiles(row.infoHash.orEmpty()).orEmpty()
            }
            finding = null
            if (files.isEmpty()) nav.toast("Not ready in TorBox. Add this source from your iPhone, then retry.")
            else sourceFiles = Triple(row, request, files)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { finding = null; nav.toast(failure.message ?: "TorBox files could not load. Retry.") }
        return true
    }

    fun startPlay(forceList: Boolean = false, fromStart: Boolean = false, season: Int? = null, episode: Int? = null) {
        val title = meta ?: return
        finding?.job?.cancel()
        finding = null
        if (season != null && episode != null) {
            restoreEpisodeFocus = true
            browsedSeason = season
            browsedEpisode = "$season:$episode"
        }
        val base = (pick ?: PlayPick()).let { chosen ->
            if (season == null) chosen else PlayPick(season, episode,
                chosen.resumeFromMs.takeIf { chosen.season == season && chosen.episode == episode }, chosen.label)
        }
        val request = base.request(title, forceList = forceList, fromStart = fromStart)
        if (forceList) {
            sourcesTarget = request.season to request.episode
            return
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            if (request.type == "series" && (request.season == null || request.episode == null) && offerActualFiles(request))
                return@launch
            finding = finding?.copy(phaseOverride = null)
            when (val result = client.playFlow.play(request)) {
                // The shell navigates to the player on `PlaybackSession.openRequests`.
                PlayResult.Opened -> finding = null
                PlayResult.ShowList -> {
                    finding = null
                    sourcesTarget = request.season to request.episode
                }
                is PlayResult.Failed -> {
                    finding = null
                    if (!offerActualFiles(request)) {
                        nav.toast(result.message)
                        sourcesTarget = request.season to request.episode
                    }
                }
            }
        }
        finding = FindingState(job = job, request = request, phaseOverride = "Preparing playback")
        job.start()
    }

    fun closeSources() {
        sourcesTarget = null
        if (restoreEpisodeFocus && placedEpisodeKey == browsedEpisode) {
            pendingEpisodeFocus = true
            return
        }
        scope.launch {
            delay(16)
            runCatching { sourcesFocus.requestFocus() }
        }
    }

    fun closeEpisodeMenu() {
        episodeMenu = null
        if (placedEpisodeKey != null && placedEpisodeKey == browsedEpisode) pendingEpisodeFocus = true
    }

    fun cancelFinding() {
        finding?.job?.cancel()
        finding = null
        if (restoreEpisodeFocus) pendingEpisodeFocus = true
        else scope.launch { delay(16); runCatching { playFocus.requestFocus() } }
    }

    // Register the page fallback before its drawer, so the drawer owns BACK while visible.
    BackHandler(enabled = panel != null || episodeMenu != null || finding != null || addTo != null) {
        when {
            addTo != null -> addTo = null
            episodeMenu != null -> closeEpisodeMenu()
            panel != null -> panel = null
            finding != null -> cancelFinding()
        }
    }

    Box(Modifier.fillMaxSize().background(TvColor.Canvas)) {
        Backdrop(meta, onLandscapeReady = { landscapeReady = true }) { strip.value }
        sourceFiles?.let { (row, request, files) ->
            TorboxFilePanel(request.title, files, fileOpening, onSelect = { file ->
                fileJob?.cancel()
                fileJob = scope.launch {
                    fileOpening = true
                    try {
                        val exact = row.copy(torboxFileID = file.id, releaseName = file.name)
                        val result = client.playFlow.playRow(exact, request.copy(
                            id = "saved-torbox:${row.infoHash}:${file.id}" + row.torboxTorrentID?.let { ":$it" }.orEmpty(), title = file.name,
                            season = null, episode = null, resumeFromMs = null))
                        if (result is PlayResult.Failed) nav.toast(result.message)
                    } finally { fileOpening = false }
                }
            }, onClose = {
                fileJob?.cancel(); fileOpening = false; sourceFiles = null
                scope.launch { kotlinx.coroutines.delay(16); runCatching { playFocus.requestFocus() } }
            })
        }

        CompositionLocalProvider(LocalBringIntoViewSpec provides PivotSpec.columnKeepVisible()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .then(if (sourcesTarget != null) Modifier.focusProperties { canFocus = false } else Modifier)
                    .padding(start = 96.dp, top = 72.dp, bottom = 54.dp),
            ) {
                when {
                    state.failed -> MetaError(meta, state.loading, viewModel::load, Modifier.focusRequester(playFocus))
                    else -> {
                        Hero(
                            meta = meta,
                            ratings = state.ratings,
                            badges = state.posterBadges,
                            loading = state.loading,
                            landscapeReady = landscapeReady,
                            inWatchlist = state.inWatchlist,
                            watchlistSaving = watchlistSaving,
                            playLabel = when {
                                meta == null -> "Loading title"
                                state.loading && meta.type == "series" -> "Loading episodes"
                                allUpcoming -> "Episodes upcoming"
                                else -> pick?.label ?: "Play"
                            },
                            playEnabled = meta != null && !(state.loading && meta.type == "series") && !allUpcoming,
                            showTrailer = trailerInstalled,
                            playModifier = Modifier.focusRequester(playFocus),
                            sourcesModifier = Modifier.focusRequester(sourcesFocus),
                            onPlay = { restoreEpisodeFocus = false; startPlay() },
                            onSources = { restoreEpisodeFocus = false; startPlay(forceList = true) },
                            onWatchlist = {
                                val current = meta
                                if (current != null && !watchlistSaving) {
                                    scope.launch {
                                        watchlistSaving = true
                                        try {
                                            if (state.inWatchlist) {
                                                client.activeLibrary.savedCollections().first()
                                                    .firstOrNull { it.sourceRef == SystemCollections.WATCHLIST_REF }
                                                    ?.let { client.activeLibrary.removeItem(it.id, current.id) }
                                                nav.toast("Removed from Watchlist.")
                                            } else {
                                                client.activeLibrary.saveToWatchlist(current.id, current.type, current.title, current.poster)
                                                nav.toast("Saved to Watchlist.")
                                            }
                                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                        catch (_: Exception) { nav.toast("Watchlist could not be updated. Try again.") }
                                        finally { watchlistSaving = false }
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
                            onSeason = { restoreEpisodeFocus = false; browsedSeason = it; browsedEpisode = null; viewModel.selectSeason(it) },
                            playTarget = pick,
                            focusedEpisodeKey = browsedEpisode,
                            onEpisodeFocus = { browsedEpisode = "${it.season}:${it.episode}"; restoreEpisodeFocus = true },
                            episodeReturnFocus = episodeReturnFocus,
                            onEpisodePlaced = { placedEpisodeKey = it },
                            onEpisodePlay = { startPlay(season = it.season, episode = it.episode) },
                            onEpisodeMenu = { restoreEpisodeFocus = true; browsedEpisode = "${it.season}:${it.episode}"; episodeMenu = it },
                            onPerson = { restoreEpisodeFocus = false; nav.openSearch(it) },
                            onOpen = {
                                restoreEpisodeFocus = false
                                DetailPreview.stage(it)
                                nav.openDetail(it.type, it.id)
                            },
                            onAddToCollection = { ref ->
                                restoreEpisodeFocus = false
                                addTo = CollectionCandidate(ref.id, ref.type, ref.title, ref.poster)
                            },
                        )
                    }
                }
            }
        }

        if (meta != null && !state.failed) StickyStrip(meta) { strip.value }

        finding?.let { current ->
            val preparation by client.playFlow.preparationLabel.collectAsState()
            FindingCard(
                request = current.request,
                phase = current.phaseOverride ?: preparation,
                onCancel = ::cancelFinding,
                onShowAll = {
                    current.job.cancel()
                    finding = null
                    sourcesTarget = current.request.season to current.request.episode
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
                unaired = DetailFormat.isUnaired(episode.released, todayIso),
                onPlay = {
                    episodeMenu = null
                    startPlay(season = episode.season, episode = episode.episode)
                },
                onSources = {
                    episodeMenu = null
                    sourcesTarget = episode.season to episode.episode
                },
                onMarkWatched = {
                    closeEpisodeMenu()
                    viewModel.markWatched(episode.season, episode.episode)
                },
                onClose = ::closeEpisodeMenu,
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
private data class FindingState(val job: Job, val request: PlayRequest, val phaseOverride: String? = null)

// ---------------------------------------------------------------------------- hero

/** The same full-width, top-preserving image path as Home. Foreground Cover is the
 * separate portrait fallback when no usable landscape is available.
 */
@Composable
private fun Backdrop(meta: Meta?, onLandscapeReady: () -> Unit, dimmed: () -> Boolean) {
    val url = meta?.backdrop
    val unavailable = remember(url) { mutableStateOf(false) }
    val dim = animateFloatAsState(
        targetValue = if (dimmed()) STRIP_BACKDROP_DIM else 1f,
        animationSpec = tween(TvMotion.PanelSlideMillis, easing = TvMotion.Std),
        label = "backdropDim",
    )
    Box(Modifier.fillMaxSize()) {
        if (url != null && !unavailable.value) {
            com.fourseveneightnine.tv.client.ui.components.LandscapeBackdrop(
                url = url,
                bottomFadeHeight = 220.dp,
                modifier = Modifier.align(Alignment.TopStart).graphicsLayer { alpha = dim.value },
                onUnavailable = { unavailable.value = true },
                onLandscapeReady = onLandscapeReady,
            )
        }
    }
}

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
    landscapeReady: Boolean,
    inWatchlist: Boolean,
    watchlistSaving: Boolean,
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
        Column(Modifier.width(1080.dp)) {
            Spacer(Modifier.height(8.dp))
            if (meta == null) {
                Skeleton(Modifier.size(520.dp, 160.dp), sweep = loading)
                Spacer(Modifier.height(20.dp))
                Skeleton(Modifier.size(520.dp, 76.dp), sweep = loading)
            } else {
                Box(Modifier.width(740.dp).height(160.dp), contentAlignment = Alignment.BottomStart) {
                    if (meta.logo != null) {
                        DetailArtwork(
                            url = meta.logo,
                            targetWidth = 780,
                            modifier = Modifier.height(160.dp).width(620.dp),
                            fallback = meta.title,
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.BottomStart,
                            logo = true,
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
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    text = DetailFormat.metaLine(meta),
                    style = TvType.Body.copy(fontSize = 30.sp, lineHeight = 38.sp),
                    color = TvColor.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.height(76.dp),
                )
            }

            // Reserve this single row so late ratings cannot move the watching actions.
            Spacer(Modifier.height(14.dp))
            Box(Modifier.height(48.dp)) {
                TvRatingsRow(ratings, fallbackImdb = meta?.imdbRating)
            }

            Spacer(Modifier.height(20.dp))
            Synopsis(meta?.description, loading, onSynopsis)

            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(TvSpace.S)) {
                CinematicAction(playLabel, onPlay, primary = true, enabled = playEnabled, modifier = playModifier.width(300.dp))
                CinematicAction("Sources", onSources, modifier = sourcesModifier)
                CinematicAction(
                    if (watchlistSaving) "Saving…" else if (inWatchlist) "✓ Watchlist" else "+ Watchlist",
                    onWatchlist,
                    enabled = !watchlistSaving,
                    selected = inWatchlist,
                    modifier = Modifier.width(218.dp),
                )
                if (showTrailer) CinematicAction("Trailer", onTrailer)
                CinematicAction("More", onMore)
            }
        }

        if (!landscapeReady) {
            Poster(meta, badges, Modifier.align(Alignment.TopEnd).padding(end = 96.dp))
        }
    }
}

@Composable
private fun CinematicAction(
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    enabled: Boolean = true,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
) {
    TvFocusable(
        onClick = { if (enabled) onClick() },
        modifier = modifier,
        enabled = enabled,
        focusWhenDisabled = true,
        accessibleLabel = label,
        checked = selected.takeIf { label.contains("Watchlist") },
        stateDescription = if (!enabled) "Unavailable" else if (selected) "Saved" else null,
        cornerRadius = 34.dp,
        focusScale = 1f,
        focusRing = true,
    ) { focused ->
        Box(
            Modifier
                .height(72.dp)
                .clip(CircleShape)
                .background(
                    when {
                        !enabled -> TvColor.Elevated2
                        primary -> TvColor.Accent
                        selected -> TvColor.Selection.copy(alpha = 0.18f)
                        focused -> TvColor.Elevated2
                        else -> TvColor.Elevated.copy(alpha = 0.88f)
                    },
                )
                .border(1.dp, if (selected) TvColor.Selection else Color.White.copy(alpha = 0.18f), CircleShape)
                .padding(horizontal = 22.dp),
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
                        primary -> TvColor.Canvas
                        selected -> TvColor.Selection
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
            .size(300.dp, 450.dp)
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

/** A fixed reading region keeps Play and episode choices in the first viewport. */
@Composable
private fun Synopsis(text: String?, loading: Boolean, onExpand: () -> Unit) {
    Box(Modifier.width(980.dp).height(156.dp)) {
    if (text.isNullOrBlank()) {
        if (loading) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(3) { Skeleton(Modifier.size(700.dp, 24.dp), sweep = it < 2) }
            }
        }
    } else {
        TvFocusable(
            onClick = onExpand,
            accessibleLabel = "Synopsis. $text",
            clickLabel = "Read full synopsis",
            cornerRadius = 8.dp,
            focusScale = 1f,
        ) { _ ->
            Column {
                Text(
                    text = text,
                    style = TvType.Body.copy(fontSize = 29.sp, lineHeight = 39.sp),
                    color = TvColor.TextPrimary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("Full synopsis ›", style = TvType.Meta, color = TvColor.TextSecondary)
            }
        }
    }
    }
}

// ---------------------------------------------------------------------------- rails

@Composable
private fun Rails(
    state: DetailUiState,
    todayIso: String,
    onSeason: (Int) -> Unit,
    playTarget: PlayPick?,
    focusedEpisodeKey: String?,
    onEpisodeFocus: (Episode) -> Unit,
    episodeReturnFocus: FocusRequester,
    onEpisodePlaced: (String?) -> Unit,
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
        androidx.compose.runtime.key(state.selectedSeason) { EpisodesRow(
            episodes = state.episodes,
            progress = state.progress,
            showRuntimeMinutes = meta?.runtimeMinutes,
            todayIso = todayIso,
            onPlay = onEpisodePlay,
            onMenu = onEpisodeMenu,
            loading = state.loading,
            playTarget = playTarget,
            focusedEpisodeKey = focusedEpisodeKey,
            onEpisodeFocus = onEpisodeFocus,
            returnFocusRequester = episodeReturnFocus,
            onReturnTargetPlaced = onEpisodePlaced,
        ) }
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
    unaired: Boolean,
    onPlay: () -> Unit,
    onSources: () -> Unit,
    onMarkWatched: () -> Unit,
    onClose: () -> Unit,
) {
    val first = remember { FocusRequester() }
    SidePanel(header = NextUpHeader(episode), onClose = onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(TvSpace.XS)) {
            if (unaired) {
                Text("Airs ${DetailFormat.airDate(episode.released) ?: "soon"}. Playback availability is not confirmed.",
                    style = TvType.Body, color = TvColor.TextSecondary, modifier = Modifier.width(560.dp))
                SidePanelRow("Check sources", false, onSources, Modifier.focusRequester(first))
            } else {
                SidePanelRow("Play", false, onPlay, Modifier.focusRequester(first))
                SidePanelRow("Sources", false, onSources)
                SidePanelRow("Mark watched", false, onMarkWatched)
            }
        }
    }
    LaunchedEffect(episode) { runCatching { first.requestFocus() } }
}

private fun NextUpHeader(episode: Episode): String =
    com.fourseveneightnine.tv.client.playback.NextUp.subtitle(episode.season, episode.episode, episode.title)

/**
 * §10.5: when the rules say "play best without asking", the Streams screen does not show and this
 * card sits over Detail instead. Back and Cancel stop the same work; Sources opens the list.
 */
@Composable
private fun FindingCard(request: PlayRequest, phase: String?, onCancel: () -> Unit, onShowAll: () -> Unit) {
    val focus = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(TvColor.Canvas.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .width(900.dp)
                .clip(TvShape.Panel)
                .background(TvColor.Elevated)
                .border(1.dp, TvColor.Border, TvShape.Panel)
                .padding(horizontal = 40.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                DetailArtwork(
                    url = request.posterUrl ?: request.backdropUrl,
                    targetWidth = 185,
                    modifier = Modifier.size(120.dp, 180.dp).clip(TvShape.Card),
                    fallback = request.title,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        phase?.takeIf(String::isNotBlank) ?: "Preparing playback",
                        style = TvType.ShelfHeader,
                        color = TvColor.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(request.title, style = TvType.CardTitle, color = TvColor.TextSecondary,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (request.season != null && request.episode != null) {
                        Text("Season ${request.season} · Episode ${request.episode}",
                            style = TvType.Meta, color = TvColor.TextSecondary)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                TvButton("Cancel", onCancel, kind = ButtonKind.Ghost, height = TvGeom.ButtonHeightDense,
                    modifier = Modifier.width(260.dp).focusRequester(focus))
                TvButton("Show sources", onShowAll, kind = ButtonKind.Ghost, height = TvGeom.ButtonHeightDense,
                    modifier = Modifier.width(300.dp))
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/** §9.8 Error: the poster and title still draw, and Retry takes focus. */
@Composable
private fun MetaError(meta: Meta?, loading: Boolean, onRetry: () -> Unit, actionModifier: Modifier) {
    Box(Modifier.fillMaxWidth().height(600.dp)) {
    Column(Modifier.width(980.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        meta?.let {
            Text(it.title, style = TvType.HeroTitle, color = TvColor.TextPrimary,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(DetailFormat.metaLine(it), style = TvType.Body, color = TvColor.TextSecondary)
        }
        StateBlock(
            headline = if (loading) "Loading title details" else "Title details could not load",
            line = "Check your connection or add-ons, then try again.",
            actionLabel = "Retry details",
            onAction = onRetry,
            actionModifier = actionModifier,
            announceAsError = true,
        )
    }
    if (meta?.backdrop == null && meta?.poster != null) {
        Poster(meta, emptyList(), Modifier.align(Alignment.TopEnd).padding(end = 96.dp))
    }
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
    contentScale: ContentScale = ContentScale.Fit,
    alignment: Alignment = Alignment.Center,
    logo: Boolean = false,
) {
    val sized = url?.takeIf(String::isNotBlank)?.let {
        if (logo) TmdbSize.logo(it, targetWidth) else TmdbSize.sized(it, targetWidth)
    }
    val painted = remember(sized) { mutableStateOf(false) }
    val context = LocalContext.current
    val request = remember(context, sized, logo) {
        sized?.let {
            ImageRequest.Builder(context).data(it).apply { if (logo) tmdbLogo() }.build()
        }
    }
    Box(modifier, contentAlignment = alignment) {
        if (fallback != null && !painted.value) {
            Text(
                text = fallback,
                style = if (logo) TvType.HeroTitle.copy(fontSize = 64.sp, lineHeight = 72.sp) else TvType.PlateTitle,
                color = if (logo) TvColor.TextPrimary else TvColor.TextSecondary,
                maxLines = if (logo) 2 else 3,
                textAlign = if (alignment == Alignment.BottomStart) TextAlign.Start else TextAlign.Center,
                overflow = TextOverflow.Ellipsis,
                modifier = if (logo) Modifier else Modifier.padding(horizontal = TvSpace.S),
            )
        }
        if (sized != null) {
            AsyncImage(
                model = request,
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

private fun trailerIntent(videoId: String): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

private fun canPlayTrailer(context: Context, videoId: String): Boolean = runCatching {
    trailerIntent(videoId).resolveActivity(context.packageManager) != null
}.getOrDefault(false)

private fun playTrailer(context: Context, videoId: String) {
    runCatching { context.startActivity(trailerIntent(videoId)) }
}
