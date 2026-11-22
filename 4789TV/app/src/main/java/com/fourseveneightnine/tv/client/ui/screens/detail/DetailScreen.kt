@file:Suppress("OPT_IN_USAGE")

package com.fourseveneightnine.tv.client.ui.screens.detail

import android.annotation.SuppressLint
import android.content.Intent
import android.os.SystemClock
import com.fourseveneightnine.tv.client.ui.components.benchmarkDraw
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics
import android.net.Uri
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import com.fourseveneightnine.tv.client.ui.components.TvGlassArtwork
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.R
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.data.images.PosterRequest
import com.fourseveneightnine.tv.client.data.images.TmdbSize
import com.fourseveneightnine.tv.client.data.images.tmdbLogo
import com.fourseveneightnine.tv.client.data.meta.Episode
import com.fourseveneightnine.tv.client.data.meta.CastMember
import com.fourseveneightnine.tv.client.data.meta.MetaRef
import com.fourseveneightnine.tv.client.ui.screens.home.CatalogGridOverlay
import com.fourseveneightnine.tv.client.ui.screens.home.HomeCard
import com.fourseveneightnine.tv.client.ui.screens.home.HomeCatalogRef
import com.fourseveneightnine.tv.client.ui.screens.home.HomeCatalogState
import com.fourseveneightnine.tv.client.data.meta.Meta
import com.fourseveneightnine.tv.client.data.meta.Ratings
import com.fourseveneightnine.tv.protocol.NowPlayingArt
import com.fourseveneightnine.tv.client.ui.screens.player.PlaybackLoadingSurface
import com.fourseveneightnine.tv.ui.PlayerControlsPolicy
import com.fourseveneightnine.tv.client.playback.PlayResult
import com.fourseveneightnine.tv.client.playback.PlayRequest
import com.fourseveneightnine.tv.client.playback.savedStream
import com.fourseveneightnine.tv.client.data.streams.StreamRow
import com.fourseveneightnine.tv.client.data.streams.debrid.DebridFile
import com.fourseveneightnine.tv.client.ui.screens.collections.TorboxFilePanel
import com.fourseveneightnine.tv.client.ui.LocalShellState
import com.fourseveneightnine.tv.client.ui.PosterHandoff
import com.fourseveneightnine.tv.client.ui.components.ButtonKind
import com.fourseveneightnine.tv.client.ui.components.PivotSpec
import com.fourseveneightnine.tv.client.ui.components.ShelfHeader
import com.fourseveneightnine.tv.client.ui.components.SidePanel
import com.fourseveneightnine.tv.client.ui.components.SidePanelRow
import com.fourseveneightnine.tv.client.ui.components.Skeleton
import com.fourseveneightnine.tv.client.ui.components.StateBlock
import com.fourseveneightnine.tv.client.ui.components.TvButton
import com.fourseveneightnine.tv.client.ui.components.tvFocusRing
import com.fourseveneightnine.tv.client.ui.components.TvFocusable
import com.fourseveneightnine.tv.client.ui.components.TvRatingsRow
import com.fourseveneightnine.tv.client.ui.components.ContentTransition
import com.fourseveneightnine.tv.client.ui.components.PanelTransition
import com.fourseveneightnine.tv.client.ui.nav.ClientNav
import com.fourseveneightnine.tv.client.ui.screens.collections.rememberLoveTitle
import com.fourseveneightnine.tv.client.ui.screens.collections.CollectionCandidate
import com.fourseveneightnine.tv.client.ui.screens.streams.StreamsDrawer
import com.fourseveneightnine.tv.client.ui.screens.streams.StreamFilters
import com.fourseveneightnine.tv.client.ui.screens.streams.StreamsViewModel
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvGeom
import com.fourseveneightnine.tv.client.ui.theme.TvMotion
import com.fourseveneightnine.tv.client.ui.theme.TvShape
import com.fourseveneightnine.tv.client.ui.theme.TvSpace
import com.fourseveneightnine.tv.client.ui.theme.TvType
import com.fourseveneightnine.tv.client.ui.theme.LocalReduceMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import com.fourseveneightnine.tv.client.data.library.SystemCollections
import org.json.JSONArray

/** Which full-height panel is open over Detail. */
private enum class DetailPanel { Synopsis }

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
internal fun DetailScreen(
    type: String, id: String, nav: ClientNav, posterHandoff: PosterHandoff? = null,
    initialSeason: Int? = null, initialEpisode: Int? = null,
    restoredTitle: String? = null, restoredPosterLookupId: String? = null,
) {
    val context = LocalContext.current
    val enteredAt = remember(type, id) { SystemClock.uptimeMillis() }
    val client = remember(context) { context.clientGraph }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var browsedSeason by rememberSaveable(type, id) { mutableStateOf(initialSeason) }
    var browsedEpisode by rememberSaveable(type, id) {
        mutableStateOf(initialSeason?.let { season -> initialEpisode?.let { "$season:$it" } })
    }
    var restoreEpisodeFocus by rememberSaveable(type, id) { mutableStateOf(false) }
    val viewModel: DetailViewModel = viewModel(key = "detail:$type:$id") {
        DetailViewModel(type, id, client, browsedSeason, restoredTitle, restoredPosterLookupId)
    }
    // Capture only on entry; setting the model's target before navigating must not restore focus
    // while this outgoing Detail is still composed for the route transition.
    var relatedReturnKey by remember { mutableStateOf(viewModel.relatedReturnKey) }
    var castReturnKey by remember { mutableStateOf(viewModel.castReturnKey) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by viewModel.state.collectAsState()
    var placedInitialFocus by rememberSaveable(type, id, state.failed) { mutableStateOf(false) }
    val shell = LocalShellState.current

    val playFocus = remember { FocusRequester() }
    val sourcesFocus = remember { FocusRequester() }
    val trailerFocus = remember { FocusRequester() }
    val synopsisFocus = remember { FocusRequester() }
    val actionsFocus = remember { FocusRequester() }
    val directorFocus = remember { FocusRequester() }
    val personGridFocus = remember { FocusRequester() }
    val crewReturnFocus = remember { FocusRequester() }
    var crewReturnKey by remember { mutableStateOf<String?>(null) }
    var person by rememberSaveable(type, id, stateSaver = listSaver<PersonSelection?, Any>(
        save = { it?.let { target ->
            listOf(target.name, target.id ?: 0, target.castKey.orEmpty(), target.crewKey.orEmpty())
        } ?: emptyList() },
        restore = { saved ->
            saved.takeIf { it.size == 4 }?.let {
                PersonSelection(it[0] as String, (it[1] as Int).takeIf { id -> id > 0 },
                    (it[2] as String).takeIf(String::isNotEmpty), (it[3] as String).takeIf(String::isNotEmpty))
            }
        },
    )) { mutableStateOf<PersonSelection?>(null) }
    var personReturnKey by rememberSaveable(type, id) { mutableStateOf<String?>(null) }
    var personTitles by remember(person) { mutableStateOf<List<MetaRef>?>(null) }
    var personLoading by remember(person) { mutableStateOf(true) }
    val seasonsFocus = remember { FocusRequester() }
    val orderFocus = remember { FocusRequester() }
    val episodesFocus = remember { FocusRequester() }
    val episodeReturnFocus = remember { FocusRequester() }
    var placedEpisodeKey by remember { mutableStateOf<String?>(null) }
    var pendingEpisodeFocus by remember { mutableStateOf(restoreEpisodeFocus) }
    var panel by remember { mutableStateOf<DetailPanel?>(null) }
    var episodeMenu by remember { mutableStateOf<Episode?>(null) }
    val loveTitle = rememberLoveTitle(nav)
    var finding by remember { mutableStateOf<FindingState?>(null) }
    var sourcesTarget by rememberSaveable(type, id, stateSaver = listSaver<Pair<Int?, Int?>?, Int>(
        save = { target -> target?.let { listOf(it.first ?: -1, it.second ?: -1) } ?: emptyList() },
        restore = { saved -> saved.takeIf { it.size == 2 }?.let {
            it[0].takeIf { value -> value >= 0 } to it[1].takeIf { value -> value >= 0 }
        } },
    )) { mutableStateOf<Pair<Int?, Int?>?>(null) }
    var openTrailer by remember(type, id) { mutableStateOf<String?>(null) }
    var sourceFiles by remember { mutableStateOf<Triple<StreamRow, PlayRequest, List<DebridFile>>?>(null) }
    var fileOpening by remember { mutableStateOf(false) }
    var fileJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var watchlistSaving by remember { mutableStateOf(false) }
    var favoriteSaving by remember { mutableStateOf(false) }

    val meta = state.meta
    var glassArtwork by remember(type, id) { mutableStateOf<TvGlassArtwork?>(null) }
    val trailerId = meta?.trailerYouTubeID?.takeIf(TrailerYouTubeId::matches)
    var showEpisodeContext by rememberSaveable(type, id) { mutableStateOf(restoreEpisodeFocus) }
    val todayIso = remember { todayIso() }
    val pick = remember(meta, state.progress, todayIso, initialSeason, initialEpisode) {
        meta?.let { DetailPlay.pick(it, state.progress, todayIso, initialSeason, initialEpisode) }
    }
    val pickedUpcoming = remember(meta, pick, todayIso) {
        meta?.videos?.firstOrNull { it.season == pick?.season && it.episode == pick?.episode }
            ?.let { DetailFormat.isUnaired(it.released, todayIso) } == true
    }
    val allUpcoming = remember(meta, todayIso) {
        meta != null && meta.type == "series" && meta.videos.isNotEmpty() &&
            meta.videos.all { DetailFormat.isUnaired(it.released, todayIso) }
    }
    val mediaType = meta?.type ?: type
    val mediaId = meta?.id ?: id
    val actionsReady = meta != null && state.metadataReady && !state.failed
    val sourcesEnabled = actionsReady && !(state.loading && state.isSeries)
    val playEnabled = sourcesEnabled && !allUpcoming && !pickedUpcoming
    val loadingFocus = remember { FocusRequester() }
    val entryFocus = when {
        state.failed || playEnabled -> playFocus
        sourcesEnabled -> sourcesFocus
        else -> loadingFocus
    }
    if (com.fourseveneightnine.tv.BuildConfig.BENCHMARK_LOGGING) {
        LaunchedEffect(actionsReady, sourcesEnabled, playEnabled) {
            ReceiverDiagnostics.benchmark("detail.actions") {
                "ready=$actionsReady sources=$sourcesEnabled play=$playEnabled"
            }
        }
        LaunchedEffect(sourcesTarget) {
            ReceiverDiagnostics.benchmark("panel.sources") { "open=${sourcesTarget != null}" }
        }
    }
    LaunchedEffect(type, id, state.metadataReady) {
        if (state.metadataReady) {
            ReceiverDiagnostics.benchmark("detail.metadata") { "type=$mediaType elapsedMs=${SystemClock.uptimeMillis() - enteredAt}" }
            ReceiverDiagnostics.record("detail.metadata.ready", "type=$mediaType elapsedMs=${SystemClock.uptimeMillis() - enteredAt}")
        }
    }
    LaunchedEffect(person) {
        val selected = person ?: return@LaunchedEffect
        val repository = client.services.value?.meta
        personTitles = withContext(Dispatchers.Default) { repository?.personCredits(selected.name, selected.id) }
        personLoading = false
    }
    fun closePerson() {
        val selected = person ?: return
        person = null
        personReturnKey = null
        if (selected.castKey != null) {
            castReturnKey = selected.castKey
            viewModel.castReturnKey = selected.castKey
        } else {
            crewReturnKey = selected.crewKey
            scope.launch {
                withFrameNanos { }
                if (!runCatching { crewReturnFocus.requestFocus() }.getOrDefault(false)) {
                    runCatching { directorFocus.requestFocus() }
                }
                crewReturnKey = null
            }
        }
    }
    val warmSeason = pick?.season
    val warmEpisode = pick?.episode
    val warmKey = if (sourcesEnabled && (mediaType != "series" || (warmSeason != null && warmEpisode != null))) {
        "$mediaType:$mediaId:$warmSeason:$warmEpisode"
    } else null
    val reduceMotion = LocalReduceMotion.current
    val entryTravel = with(LocalDensity.current) { 16.dp.toPx() }
    val connected = posterHandoff?.takeUnless { reduceMotion }
    var arriving by rememberSaveable(type, id) { mutableStateOf(true) }
    val entryOffset = animateFloatAsState(
        targetValue = if (arriving && !reduceMotion && connected == null) entryTravel else 0f,
        animationSpec = tween(if (reduceMotion) 0 else TvMotion.ContentMillis, easing = TvMotion.Out),
        label = "detailForeground",
    )
    LaunchedEffect(type, id) {
        viewModel.loadIfNeeded()
        withFrameNanos { }
        arriving = false
        ReceiverDiagnostics.benchmark("detail.frame_callback") { "type=$type elapsedMs=${SystemClock.uptimeMillis() - enteredAt}" }
        ReceiverDiagnostics.record("detail.visible", "type=$type elapsedMs=${SystemClock.uptimeMillis() - enteredAt}")
    }
    var warmedSources by remember(warmKey) { mutableStateOf<StreamsViewModel?>(null) }
    LaunchedEffect(warmKey) {
        if (warmKey == null) return@LaunchedEffect
        snapshotFlow { !arriving && entryOffset.value == 0f && connected?.visible != true }.first { it }
        // If Sources was opened immediately, the drawer already owns that search.
        if (sourcesTarget == null) {
            warmedSources = StreamsViewModel(mediaType, mediaId, warmSeason, warmEpisode, client, scope,
                initialFilters = StreamFilters.DrawerDefault)
        }
    }
    val hasSupplemental = meta != null && (meta.cast.isNotEmpty() || meta.similar.isNotEmpty())
    var supplementalReady by rememberSaveable(type, id) {
        mutableStateOf(relatedReturnKey != null || castReturnKey != null)
    }
    LaunchedEffect(type, id, hasSupplemental, posterHandoff) {
        if (!hasSupplemental || supplementalReady) return@LaunchedEffect
        // Keep image and text layout for the secondary rails out of the poster handoff.
        if (posterHandoff != null) snapshotFlow { !posterHandoff.visible }.first { it }
        else repeat(2) { withFrameNanos { } }
        supplementalReady = true
    }
    val warmSources = warmedSources
    DisposableEffect(warmSources) { onDispose { warmSources?.stop() } }

    // §9.3: once the hero has scrolled out, a strip names the title. The flag is written from a
    // `snapshotFlow`, never read in composition, so scrolling does not recompose the page — only
    // the strip and the backdrop's own layer change, and only when the flag flips.
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val strip = remember { mutableStateOf(false) }
    var episodeViewportHeight by remember { mutableStateOf(0) }
    LaunchedEffect(showEpisodeContext, episodeViewportHeight, density) {
        if (!showEpisodeContext || episodeViewportHeight == 0) return@LaunchedEffect
        val episodeTop = with(density) {
            (TvGeom.SafeTop + DetailHeroHeight + DetailRailGap - EpisodeHeadingInset).roundToPx()
        }
        withFrameNanos { }
        scroll.scrollTo(episodeTop)
    }
    LaunchedEffect(scroll, density) {
        val threshold = with(density) { DetailScroll.ShowAt.toPx() }
        val deadBand = with(density) { DetailScroll.DeadBand.toPx() }
        snapshotFlow { scroll.value }.collect { value ->
            strip.value = DetailScroll.showStrip(value.toFloat(), threshold, deadBand, strip.value)
        }
    }

    // The rail borrows focus and must be able to give it back.
    DisposableEffect(entryFocus) {
        val restore = {
            if (restoreEpisodeFocus && placedEpisodeKey == browsedEpisode) pendingEpisodeFocus = true
            else runCatching { entryFocus.requestFocus() }
            Unit
        }
        shell.restoreContentFocus = restore
        onDispose { if (shell.restoreContentFocus === restore) shell.restoreContentFocus = null }
    }

    // A search suggestion can leave the IME and the outgoing focus group in transition.
    // Wait for this entry to own input, then place focus once on its hero action.
    LaunchedEffect(lifecycle, entryFocus, state.failed) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (person != null) return@repeatOnLifecycle
            castReturnKey = viewModel.castReturnKey
            if (placedInitialFocus || restoreEpisodeFocus || relatedReturnKey != null ||
                castReturnKey != null || sourcesTarget != null) return@repeatOnLifecycle
            repeat(12) {
                withFrameNanos { }
                if (runCatching { entryFocus.requestFocus() }.getOrDefault(false)) {
                    placedInitialFocus = entryFocus !== loadingFocus
                    return@repeatOnLifecycle
                }
            }
        }
    }
    fun finishCastReturn(restored: Boolean) {
        viewModel.castReturnKey = null
        castReturnKey = null
        if (!restored) runCatching { playFocus.requestFocus() }
    }
    LaunchedEffect(castReturnKey, meta?.cast, state.loading, state.failed) {
        if (castReturnKey != null && !state.loading && (state.failed || meta?.cast.isNullOrEmpty())) {
            finishCastReturn(false)
        }
    }
    LaunchedEffect(placedEpisodeKey, pendingEpisodeFocus, sourcesTarget, actionsReady) {
        if (actionsReady && sourcesTarget == null && pendingEpisodeFocus && placedEpisodeKey != null && placedEpisodeKey == browsedEpisode) {
            runCatching { episodeReturnFocus.requestFocus() }
            pendingEpisodeFocus = false
        }
    }
    LaunchedEffect(state.loading, state.failed, state.episodes, pendingEpisodeFocus, state.metadataReady) {
        if (state.metadataReady && pendingEpisodeFocus && !state.loading && (state.failed || state.episodes.none {
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
        ReceiverDiagnostics.benchmark("detail.action") {
            "kind=${if (forceList) "sources" else "play"} ready=$actionsReady"
        }
        val title = meta?.takeIf { actionsReady } ?: return
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

    fun closeSynopsis() {
        panel = null
        scope.launch { androidx.compose.runtime.withFrameNanos { }; runCatching { synopsisFocus.requestFocus() } }
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
    BackHandler(enabled = panel != null || episodeMenu != null || finding != null) {
        when {
            episodeMenu != null -> closeEpisodeMenu()
            panel == DetailPanel.Synopsis -> closeSynopsis()
            panel != null -> panel = null
            finding != null -> cancelFinding()
        }
    }

    Box(Modifier.fillMaxSize().benchmarkDraw(if (state.failed) "detail_error" else "detail").background(TvColor.Background)) {
        // The drawer changes every scroll frame; the covered Detail page is static.
        // Keep that page in one hardware layer while Sources is open so the image,
        // gradients, text and cast portraits are not rasterized again behind each row move.
        Box(Modifier.fillMaxSize()
            .then(if (person != null) Modifier.clearAndSetSemantics { } else Modifier)
            .focusProperties { onEnter = { if (person != null) cancelFocusChange() } }
            .focusGroup().then(
            if (sourcesTarget != null) Modifier.graphicsLayer {
                compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
            } else Modifier,
        )) {
            Backdrop(meta, onGlassArtwork = { glassArtwork = it }, artworkKey = id,
                onLandscapeReady = { ReceiverDiagnostics.benchmark("artwork.landscape") { "screen=detail result=ready" } },
            ) { strip.value }
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

            // The title strip owns layout space. Scrolling content can never draw beneath it.
            Column(Modifier.fillMaxSize()) {
                if (meta != null && !state.failed) StickyStrip(meta) { strip.value }
                CompositionLocalProvider(LocalBringIntoViewSpec provides PivotSpec.columnKeepVisible()) {
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .onSizeChanged { if (state.isSeries) episodeViewportHeight = it.height }
                            .verticalScroll(scroll)
                            .then(if (entryFocus === loadingFocus) Modifier
                                .focusRequester(loadingFocus).focusable()
                                .semantics { stateDescription = "Loading title" } else Modifier)
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown || event.key != Key.DirectionDown ||
                                    supplementalReady || !hasSupplemental || state.isSeries
                                ) return@onPreviewKeyEvent false
                                supplementalReady = true
                                scope.launch {
                                    repeat(2) { withFrameNanos { } }
                                    focusManager.moveFocus(FocusDirection.Down)
                                }
                                true
                            }
                            .then(if (sourcesTarget != null || person != null) Modifier.focusProperties { canFocus = false } else Modifier)
                            .padding(start = 96.dp, top = TvGeom.SafeTop, bottom = 54.dp),
                    ) {
                        when {
                            state.failed -> MetaError(meta, state.loading, state.failureMessage, viewModel::load,
                                Modifier.focusRequester(playFocus))
                            else -> {
                                Hero(
                                    meta = meta,
                                    ratings = state.ratings,
                                    loading = state.loading,
                                    directorFocus = directorFocus,
                                    onCredit = { name, personID, key -> person = PersonSelection(name, personID, crewKey = key) },
                                    crewReturnKey = crewReturnKey,
                                    crewReturnFocus = crewReturnFocus,
                                    inWatchlist = state.inWatchlist,
                                    watchlistSaving = watchlistSaving,
                                    inFavorites = state.inFavorites,
                                    favoriteSaving = favoriteSaving,
                                    playLabel = when {
                                        !actionsReady -> "Loading title"
                                        state.loading && meta.type == "series" -> "Loading episodes"
                                        pickedUpcoming -> "Episode upcoming"
                                        allUpcoming -> "Episodes upcoming"
                                        else -> pick?.label ?: "Play"
                                    },
                                    playEnabled = playEnabled,
                                    showTrailer = trailerId != null,
                                    sourcesEnabled = sourcesEnabled,
                                    savedActionsEnabled = actionsReady,
                                    playModifier = Modifier.focusRequester(playFocus),
                                    sourcesModifier = Modifier.focusRequester(sourcesFocus),
                                    synopsisFocus = synopsisFocus,
                                    actionsFocus = actionsFocus,
                                    actionsModifier = Modifier.focusRequester(actionsFocus)
                                        .onFocusChanged { focus ->
                                            if (focus.hasFocus) {
                                                showEpisodeContext = false
                                                if (scroll.value > 0) scope.launch { scroll.scrollTo(0) }
                                            }
                                        }
                                        .onPreviewKeyEvent { event ->
                                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp && scroll.value > 0) {
                                                scope.launch { scroll.scrollTo(0) }; true
                                            } else false
                                        }.focusRestorer().focusGroup(),
                                    actionsDownFocus = when {
                                        state.isSeries && state.episodes.isNotEmpty() && !state.loading -> episodesFocus
                                        else -> null
                                    },
                                    onPlay = { restoreEpisodeFocus = false; startPlay() },
                                    onSources = { restoreEpisodeFocus = false; startPlay(forceList = true) },
                                    onWatchlist = {
                                        val current = meta
                                        if (actionsReady && !watchlistSaving) {
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
                                    trailerModifier = Modifier.focusRequester(trailerFocus),
                                    onTrailer = { openTrailer = trailerId },
                                    onLove = {
                                        val current = meta
                                        if (actionsReady && !favoriteSaving) {
                                            val library = client.activeLibrary
                                            scope.launch {
                                                favoriteSaving = true
                                                try {
                                                    if (state.inFavorites) {
                                                        library.savedCollections().first()
                                                            .firstOrNull { it.sourceRef == SystemCollections.FAVORITES_REF }
                                                            ?.let { library.removeItem(it.id, current.id) }
                                                        nav.toast("Removed from Favorites.")
                                                    } else {
                                                        library.saveToFavorites(current.id, current.type, current.title, current.poster)
                                                        nav.toast("Loved. Saved to Favorites.")
                                                    }
                                                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                                catch (_: Exception) { nav.toast("Couldn’t update Favorites. Try again.") }
                                                finally { favoriteSaving = false }
                                            }
                                        }
                                    },
                                    onSynopsis = { panel = DetailPanel.Synopsis },
                                    modifier = Modifier.graphicsLayer {
                                        val progress = connected?.progress?.value
                                        translationY = if (progress != null) entryTravel * (1f - progress) else entryOffset.value
                                    },
                                )
                                Spacer(Modifier.height(DetailRailGap))
                                Rails(
                                    modifier = if (state.isSeries) {
                                        // Keep the browser anchor reachable for a short season.
                                        // Cast follows the cards. Spare room stays below the last row.
                                        Modifier.heightIn(min = with(density) { episodeViewportHeight.toDp() })
                                    } else Modifier,
                                    state = state,
                                    focusedEpisode = state.episodes.firstOrNull { "${it.season}:${it.episode}" == browsedEpisode }
                                        .takeIf { showEpisodeContext },
                                    showSupplemental = supplementalReady || relatedReturnKey != null,
                                    todayIso = todayIso,
                                    onSeason = { restoreEpisodeFocus = false; browsedSeason = it; browsedEpisode = null; viewModel.selectSeason(it) },
                                    playTarget = pick,
                                    focusedEpisodeKey = browsedEpisode,
                                    onEpisodeFocus = {
                                        browsedEpisode = "${it.season}:${it.episode}"
                                        restoreEpisodeFocus = true
                                        showEpisodeContext = true
                                    },
                                    episodeReturnFocus = episodeReturnFocus,
                                    actionsFocus = actionsFocus,
                                    seasonsFocus = seasonsFocus,
                                    orderFocus = orderFocus,
                                    episodesFocus = episodesFocus,
                                    onEpisodePlaced = { placedEpisodeKey = it },
                                    onEpisodeSources = { startPlay(forceList = true, season = it.season, episode = it.episode) },
                                    onEpisodeMenu = { restoreEpisodeFocus = true; browsedEpisode = "${it.season}:${it.episode}"; episodeMenu = it },
                                    onPerson = { member, key ->
                                        restoreEpisodeFocus = false
                                        person = PersonSelection(member.name, member.personID, castKey = key)
                                    },
                                    castReturnKey = castReturnKey,
                                    onCastReturnFinished = ::finishCastReturn,
                                    onOpen = {
                                        restoreEpisodeFocus = false
                                        viewModel.relatedReturnKey = "${it.type}:${it.id}"
                                        DetailPreview.stage(it)
                                        nav.openDetail(it.type, it.id)
                                    },
                                    relatedReturnKey = relatedReturnKey,
                                    onRelatedReturnFinished = { restored ->
                                        viewModel.relatedReturnKey = null
                                        relatedReturnKey = null
                                        if (!restored) runCatching { playFocus.requestFocus() }
                                    },
                                    onLoveTitle = { ref ->
                                        restoreEpisodeFocus = false
                                        loveTitle(CollectionCandidate(ref.id, ref.type, ref.title, ref.poster))
                                    },
                                )
                            }
                        }
                    }
                }
            }

        }

        PanelTransition(finding) { current ->
            PlaybackLoadingSurface(
                request = current.request,
                art = NowPlayingArt(
                    landscapeURL = current.request.backdropUrl ?: meta?.backdrop,
                    posterURL = current.request.posterUrl ?: meta?.poster, logoURL = meta?.logo,
                ),
                title = current.request.title,
                onCancel = ::cancelFinding,
            )
        }

        PanelTransition(panel) { visiblePanel ->
            when (visiblePanel) {
                DetailPanel.Synopsis -> {
                    val bodyFocus = remember { FocusRequester() }
                    var bodyFocused by remember { mutableStateOf(false) }
                    val bodyScroll = rememberScrollState()
                    val linePixels = with(LocalDensity.current) { 80.dp.toPx() }
                    SidePanel(header = "Synopsis", onClose = ::closeSynopsis) {
                        Column(Modifier.fillMaxHeight()) {
                        TvButton("Back to details", ::closeSynopsis, kind = ButtonKind.Secondary)
                        Spacer(Modifier.height(TvSpace.M))
                        Text(meta?.description.orEmpty(), style = TvType.Body, color = TvColor.TextPrimary,
                            modifier = Modifier.width(560.dp).weight(1f).focusRequester(bodyFocus)
                                .tvFocusRing(bodyFocused, 8.dp).onFocusChanged { bodyFocused = it.isFocused }
                                .verticalScroll(bodyScroll).focusable().onKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                                        Key.DirectionUp -> if (bodyScroll.value > 0) { scope.launch { bodyScroll.scrollBy(-linePixels) }; true } else false
                                        Key.DirectionDown -> if (bodyScroll.value < bodyScroll.maxValue) { scope.launch { bodyScroll.scrollBy(linePixels) }; true } else false
                                        else -> false
                                    }
                                })
                        }
                    }
                    LaunchedEffect(Unit) { androidx.compose.runtime.withFrameNanos { }; runCatching { bodyFocus.requestFocus() } }
                }

            }
        }

        PanelTransition(episodeMenu) { episode ->
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

        person?.let { selected ->
            BackHandler { closePerson() }
            val onlyCurrentTitle = !personLoading && personTitles.isNullOrEmpty()
            val credits = if (onlyCurrentTitle) {
                listOfNotNull(meta?.let { MetaRef(it.id, it.type, it.title, it.poster) })
            } else personTitles.orEmpty()
            CatalogGridOverlay(
                state = HomeCatalogState(
                    HomeCatalogRef("tmdb-person", mediaType, selected.id?.toString() ?: selected.name, selected.name),
                    items = credits.map { ref -> HomeCard(ref.id, ref.type, ref.title, posterUrl = ref.poster,
                        metadataLookupAllowed = false) },
                    loading = personLoading,
                    exhausted = true,
                ),
                returnCardKey = personReturnKey,
                onReturnFinished = { personReturnKey = null },
                onLoadMore = {},
                onClose = ::closePerson,
                gridFocus = personGridFocus,
                savePanelOpen = false,
                statusMessage = when {
                    personLoading -> "Loading credits…"
                    onlyCurrentTitle -> "More credits are unavailable."
                    else -> null
                },
                returnLabel = "Back to details",
                onOpen = { card ->
                    val tmdbID = meta?.tmdbID?.let { "tmdb:$it" }
                    if (card.type == mediaType && (card.id == mediaId || card.id == tmdbID || card.id == meta?.imdbID)) {
                        closePerson()
                    } else {
                        personReturnKey = card.key
                        DetailPreview.stage(card)
                        nav.openDetail(card.type, card.id)
                    }
                },
                onLongOpen = { card -> loveTitle(CollectionCandidate(card.id, card.type, card.title, card.posterUrl)) },
            )
        }

        PanelTransition(sourcesTarget.takeIf { actionsReady }) { (season, episode) ->
            StreamsDrawer(
                type = mediaType,
                id = mediaId,
                season = season,
                episode = episode,
                title = meta?.title ?: mediaId,
                subtitle = if (season != null && episode != null) {
                    val episodeTitle = meta?.videos?.firstOrNull { it.season == season && it.episode == episode }?.title
                    listOfNotNull("S$season E$episode", episodeTitle).joinToString(" · ")
                } else null,
                episodes = meta?.videos.orEmpty(),
                resumeFromMs = pick?.takeIf { it.season == season && it.episode == episode }?.resumeFromMs,
                posterUrl = meta?.poster,
                backdropUrl = meta?.backdrop,
                prefetched = warmSources?.takeIf { season == warmSeason && episode == warmEpisode },
                glassArtwork = glassArtwork,
                onDismiss = ::closeSources,
                onSettings = { sourcesTarget = null; nav.openSettings("addons") },
                onToast = nav::toast,
            )
        }
    }
    openTrailer?.let { videoId ->
        TrailerDialog(videoId, onClose = {
            openTrailer = null
            scope.launch {
                withFrameNanos { }
                runCatching { trailerFocus.requestFocus() }
            }
        }, onUnavailable = nav::toast)
    }
}

/** The auto-pick card's own coroutine, so OK on "Show all sources" can really cancel it. */
private data class PersonSelection(
    val name: String,
    val id: Int?,
    val castKey: String? = null,
    val crewKey: String? = null,
)

private data class FindingState(val job: Job, val request: PlayRequest, val phaseOverride: String? = null)

// ---------------------------------------------------------------------------- hero

/** Full-width, top-preserving landscape artwork behind the detail content. */
@Composable
internal fun Backdrop(
    meta: Meta?,
    onLandscapeReady: () -> Unit = {},
    onGlassArtwork: (TvGlassArtwork?) -> Unit = {},
    onSettled: () -> Unit = {},
    artworkKey: String? = meta?.id,
    dimmed: () -> Boolean,
) {
    val reduceMotion = LocalReduceMotion.current
    val failed = remember(artworkKey) { mutableStateOf(emptySet<String>()) }
    var retry by remember(artworkKey) { mutableStateOf(0) }
    var displayedUrl by remember(artworkKey) { mutableStateOf<String?>(null) }
    val candidates = remember(meta) { meta?.backdropCandidates.orEmpty() }
    val url = displayedUrl ?: candidates.firstOrNull { it !in failed.value }
    LaunchedEffect(url, candidates, retry) {
        if (url == null && candidates.isNotEmpty() && retry == 0) {
            delay(800)
            retry = 1
            failed.value = emptySet()
        } else if (url == null) onSettled()
    }
    val dim = animateFloatAsState(
        targetValue = if (dimmed()) STRIP_BACKDROP_DIM else 1f,
        animationSpec = tween(if (reduceMotion) 0 else TvMotion.PanelSlideMillis, easing = TvMotion.Std),
        label = "backdropDim",
    )
    Box(Modifier.fillMaxSize().clipToBounds()) {
        if (url != null) {
            com.fourseveneightnine.tv.client.ui.components.LandscapeBackdrop(
                url = url,
                retryToken = retry,
                bottomFadeHeight = 1.dp,
                sideFadeFraction = 0.001f,
                artworkOffsetX = 0.dp,
                modifier = Modifier.align(Alignment.TopStart).graphicsLayer { alpha = dim.value },
                onUnavailable = { failed.value = failed.value + url },
                onLandscapeReady = {
                    displayedUrl = url
                    onLandscapeReady()
                    onSettled()
                },
                onGlassArtwork = onGlassArtwork,
            )
        }
        // Retain the original lower image; only the text side receives a light contrast fade.
        Box(Modifier.fillMaxWidth().height(160.dp).background(Brush.verticalGradient(
            listOf(TvColor.Canvas.copy(alpha = 0.35f), Color.Transparent),
        )))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
            0f to TvColor.Canvas.copy(alpha = 0.35f), 0.65f to Color.Transparent,
        )))
    }
}

/** Optional hero artwork gets a short grace period; metadata stays usable after it expires. */

private val DetailHeroHeight = 972.dp
private val DetailRailGap = 64.dp
private val EpisodeHeadingInset = 24.dp

/** §9.3: the backdrop dims to 35% once the strip is up. */
private const val STRIP_BACKDROP_DIM = 0.35f

/**
 * §9.3: the sticky strip that names the title once the hero has scrolled out.
 *
 * It occupies its own row above the scrolling viewport, including while fading out.
 */
@Composable
private fun StickyStrip(meta: Meta, shown: () -> Boolean) {
    val reduceMotion = LocalReduceMotion.current
    val fade = tween<Float>(TvMotion.CrossfadeMillis, easing = TvMotion.Std)
    AnimatedVisibility(
        visible = shown(),
        enter = if (reduceMotion) EnterTransition.None else fadeIn(fade),
        exit = if (reduceMotion) ExitTransition.None else fadeOut(fade),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(32.dp))
            Text(
                text = meta.title,
                style = TvType.PlateTitle.copy(fontWeight = FontWeight.SemiBold),
                color = TvColor.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 96.dp).width(1200.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = DetailFormat.metaLine(meta),
                style = TvType.Meta,
                color = TvColor.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 96.dp).width(1200.dp),
            )
            Spacer(Modifier.height(24.dp))
            Box(
                Modifier
                    .padding(start = 96.dp)
                    .width(1604.dp)
                    .height(1.dp)
                    .background(TvColor.Border),
            )
        }
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
internal fun Hero(
    meta: Meta?,
    ratings: Ratings,
    loading: Boolean,
    directorFocus: FocusRequester,
    onCredit: (String, Int?, String) -> Unit,
    crewReturnKey: String? = null,
    crewReturnFocus: FocusRequester? = null,
    inWatchlist: Boolean,
    watchlistSaving: Boolean,
    inFavorites: Boolean,
    favoriteSaving: Boolean,
    playLabel: String,
    playEnabled: Boolean,
    showTrailer: Boolean,
    playModifier: Modifier,
    sourcesModifier: Modifier,
    synopsisFocus: FocusRequester,
    actionsFocus: FocusRequester,
    actionsModifier: Modifier,
    actionsDownFocus: FocusRequester?,
    trailerModifier: Modifier = Modifier,
    onPlay: () -> Unit,
    onSources: () -> Unit,
    onWatchlist: () -> Unit,
    onTrailer: () -> Unit,
    onLove: () -> Unit,
    onSynopsis: () -> Unit,
    modifier: Modifier = Modifier,
    onLogoSettled: () -> Unit = {},
    sourcesEnabled: Boolean = true,
    savedActionsEnabled: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val providerFocus = remember { FocusRequester() }
    val sourcesModifierFocus = remember { FocusRequester() }
    var synopsisOverflows by remember(meta?.description) { mutableStateOf(false) }
    var providersOpen by remember(meta?.id) { mutableStateOf(false) }
    val credits = meta?.creators.orEmpty().map { "Created by" to it } +
        meta?.directors.orEmpty().map { "Directed by" to it }
    val providers = remember(meta?.streamingProviders) { DetailFormat.providerGroups(meta?.streamingProviders.orEmpty()) }
    fun closeProviders() {
        providersOpen = false
        scope.launch { withFrameNanos { }; runCatching { providerFocus.requestFocus() } }
    }
    Box(modifier.fillMaxWidth().padding(end = 96.dp).height(DetailHeroHeight)) {
        Column(Modifier.fillMaxSize()) {
            if (credits.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    credits.forEachIndexed { index, (role, name) ->
                        val key = "$role:$name"
                        val personID = if (role == "Directed by") meta?.directorIDs?.get(name) else meta?.creatorIDs?.get(name)
                        val returnModifier = if (key == crewReturnKey && crewReturnFocus != null) {
                            Modifier.focusRequester(crewReturnFocus)
                        } else Modifier
                        TvFocusable(onClick = { onCredit(name, personID, key) },
                            modifier = (if (index == 0) Modifier.focusRequester(directorFocus) else Modifier)
                                .then(returnModifier).focusProperties { down = actionsFocus },
                            accessibleLabel = "$role $name", cornerRadius = 12.dp, focusScale = 1f) { focused ->
                            Text(if (index == 0 || credits[index - 1].first != role) "$role $name ›" else "$name ›",
                                style = TvType.Meta.copy(fontSize = 26.sp, lineHeight = 34.sp),
                                color = if (focused) TvColor.TextPrimary else TvColor.TextSecondary,
                                modifier = Modifier.widthIn(max = 760.dp).padding(horizontal = 8.dp, vertical = 8.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(64.dp)) {
                Column(Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth().height(164.dp), contentAlignment = Alignment.BottomStart) {
                        when {
                            meta == null -> Skeleton(Modifier.size(720.dp, 164.dp), sweep = loading)
                            else -> DetailArtwork(meta.logo, targetWidth = 1280,
                                modifier = Modifier.fillMaxSize(), fallback = meta.title,
                                contentScale = ContentScale.Fit, alignment = Alignment.BottomStart,
                                logo = true, onSettled = onLogoSettled)
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    meta?.let { title ->
                        Text(DetailFormat.heroMetaLine(title), style = TvType.Body.copy(fontSize = 28.sp, lineHeight = 36.sp),
                            color = TvColor.TextPrimary)
                        if (title.genres.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(title.genres.joinToString(" · "), style = TvType.Meta.copy(fontSize = 26.sp, lineHeight = 34.sp),
                                color = TvColor.TextSecondary)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        TvRatingsRow(ratings, fallbackImdb = meta?.imdbRating, compact = true)
                        providers.entries.firstOrNull()?.let { (brand, variants) ->
                            val provider = variants.firstOrNull { it.name == brand || it.name == "Amazon Prime Video" } ?: variants.first()
                            TvFocusable(onClick = { providersOpen = true }, focusScale = 1f, cornerRadius = 16.dp,
                                modifier = Modifier.focusRequester(providerFocus).focusProperties { down = sourcesModifierFocus },
                                accessibleLabel = "Streaming service: $brand") { focused ->
                                Box(Modifier.size(64.dp).clip(RoundedCornerShape(16.dp))
                                    .background(if (focused) TvColor.Elevated2 else TvColor.Canvas.copy(alpha = 0.30f))
                                    .padding(6.dp), contentAlignment = Alignment.Center) {
                                    DetailArtwork(provider.logo, targetWidth = 92, fallback = null,
                                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Fit)
                                }
                            }
                        }
                    }
                }

            }
            if (!meta?.description.isNullOrBlank()) {
                Spacer(Modifier.height(20.dp))
                Column(Modifier.widthIn(max = 1184.dp)) {
                    Text(meta?.description.orEmpty(), style = TvType.Body.copy(fontSize = 28.sp, lineHeight = 36.sp),
                        color = TvColor.TextPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        onTextLayout = { synopsisOverflows = it.hasVisualOverflow })
                    if (synopsisOverflows) {
                        Spacer(Modifier.height(8.dp))
                        TvFocusable(onClick = onSynopsis, accessibleLabel = "Read full synopsis", focusScale = 1f,
                            modifier = Modifier.focusRequester(synopsisFocus).focusProperties {
                                down = actionsFocus
                                if (credits.isNotEmpty()) up = directorFocus
                            }) { focused ->
                            Text("Read more", style = TvType.ControlLabel,
                                color = if (focused) TvColor.TextPrimary else TvColor.TextSecondary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            val actionNavigation = Modifier.focusProperties {
                up = when {
                    synopsisOverflows -> synopsisFocus
                    credits.isNotEmpty() -> directorFocus
                    else -> FocusRequester.Cancel
                }
                actionsDownFocus?.let { down = it }
            }
            Row(modifier = actionsModifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.Bottom) {
                CinematicAction(playLabel, R.drawable.ic_detail_play, R.drawable.ic_detail_play_filled,
                    onPlay, primary = true, enabled = playEnabled,
                    modifier = playModifier.then(actionNavigation))
                CinematicAction("Sources", R.drawable.ic_detail_layers, R.drawable.ic_detail_layers_filled, onSources,
                    enabled = sourcesEnabled,
                    modifier = sourcesModifier.focusRequester(sourcesModifierFocus)
                        .focusProperties { if (providers.isNotEmpty()) up = providerFocus }.then(actionNavigation))
                CinematicAction(if (watchlistSaving) "Saving…" else "Watchlist",
                    if (inWatchlist) R.drawable.ic_detail_bookmark_saved else R.drawable.ic_detail_bookmark,
                    if (inWatchlist) R.drawable.ic_player_bookmark_added else R.drawable.ic_player_bookmark,
                    onWatchlist, enabled = savedActionsEnabled && !watchlistSaving, checked = inWatchlist, modifier = actionNavigation)
                CinematicAction(if (favoriteSaving) "Saving…" else "Love",
                    R.drawable.ic_detail_love, R.drawable.ic_player_favorite, onLove,
                    enabled = savedActionsEnabled && !favoriteSaving, checked = inFavorites,
                    iconColor = when {
                        !savedActionsEnabled -> TvColor.TextMuted
                        inFavorites -> TvColor.Success
                        else -> TvColor.Accent
                    }, modifier = actionNavigation)
                if (showTrailer) CinematicAction("Trailer", R.drawable.ic_source_video, R.drawable.ic_detail_trailer_filled, onTrailer,
                    modifier = trailerModifier.then(actionNavigation))
                Spacer(Modifier.weight(1f))
                if (meta != null && (meta.videos.isNotEmpty() || meta.cast.isNotEmpty() || meta.similar.isNotEmpty())) {
                    Text(if (meta.type == "series") "Episodes ↓" else "Cast & more ↓", style = TvType.ControlLabel,
                        color = TvColor.TextSecondary, modifier = Modifier.align(Alignment.CenterVertically))
                }
            }
        }
    }
    if (providersOpen) {
        val first = remember { FocusRequester() }
        val bodyFocus = remember { FocusRequester() }
        val bodyScroll = rememberScrollState()
        var bodyFocused by remember { mutableStateOf(false) }
        val linePixels = with(LocalDensity.current) { 80.dp.toPx() }
        Dialog(onDismissRequest = ::closeProviders, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            SidePanel(header = "Available services", onClose = ::closeProviders) {
                Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    TvButton("Back to details", ::closeProviders, modifier = Modifier.focusRequester(first))
                    Column(Modifier.weight(1f).focusRequester(bodyFocus).tvFocusRing(bodyFocused, 8.dp)
                        .onFocusChanged { bodyFocused = it.isFocused }.verticalScroll(bodyScroll).focusable()
                        .onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                                Key.DirectionDown -> if (bodyScroll.value < bodyScroll.maxValue) {
                                    scope.launch { bodyScroll.scrollBy(linePixels) }; true
                                } else false
                                Key.DirectionUp -> if (bodyScroll.value > 0) {
                                    scope.launch { bodyScroll.scrollBy(-linePixels) }; true
                                } else false
                                else -> false
                            }
                        }, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        providers.forEach { (brand, variants) ->
                            Text(brand, style = TvType.ShelfHeader, color = TvColor.TextPrimary)
                            variants.forEach { variant ->
                                Text(variant.name, style = TvType.Body, color = TvColor.TextSecondary)
                            }
                        }
                    }
                }
            }
            LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
        }
    }
}

/** Focus fills the icon inside its fixed-size target. The label remains available to accessibility. */
@Composable
private fun CinematicAction(
    label: String,
    icon: Int,
    focusedIcon: Int,
    onClick: () -> Unit,
    primary: Boolean = false,
    enabled: Boolean = true,
    checked: Boolean? = null,
    iconColor: Color? = null,
    modifier: Modifier = Modifier,
) {
    Box(Modifier.width(112.dp), contentAlignment = Alignment.Center) {
        TvFocusable(
            onClick = { if (enabled) onClick() },
            modifier = modifier.size(104.dp),
            enabled = enabled,
            focusWhenDisabled = true,
            accessibleLabel = label,
            checked = checked,
            stateDescription = when {
                !enabled -> "Unavailable"
                checked == true -> "Saved"
                else -> null
            },
            cornerRadius = 0.dp,
            focusScale = 1f,
            focusRing = false,
        ) { hasFocus ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Image(painterResource(if (hasFocus) focusedIcon else icon), contentDescription = null,
                    modifier = Modifier.size(68.dp), contentScale = ContentScale.Fit,
                    colorFilter = ColorFilter.tint(iconColor ?: when {
                        !enabled -> TvColor.TextMuted
                        primary || checked == true -> TvColor.Accent
                        else -> TvColor.TextPrimary
                    }))
            }
        }
    }
}

// ---------------------------------------------------------------------------- rails

@Composable
private fun Rails(
    modifier: Modifier,
    state: DetailUiState,
    focusedEpisode: Episode?,
    showSupplemental: Boolean,
    todayIso: String,
    onSeason: (Int) -> Unit,
    playTarget: PlayPick?,
    focusedEpisodeKey: String?,
    onEpisodeFocus: (Episode) -> Unit,
    episodeReturnFocus: FocusRequester,
    actionsFocus: FocusRequester,
    seasonsFocus: FocusRequester,
    orderFocus: FocusRequester,
    episodesFocus: FocusRequester,
    onEpisodePlaced: (String?) -> Unit,
    onEpisodeSources: (Episode) -> Unit,
    onEpisodeMenu: (Episode) -> Unit,
    onPerson: (CastMember, String) -> Unit,
    castReturnKey: String?,
    onCastReturnFinished: (Boolean) -> Unit,
    onOpen: (com.fourseveneightnine.tv.client.data.meta.MetaRef) -> Unit,
    relatedReturnKey: String?,
    onRelatedReturnFinished: (Boolean) -> Unit,
    onLoveTitle: (com.fourseveneightnine.tv.client.data.meta.MetaRef) -> Unit,
) {
    Column(modifier) {
        val meta = state.meta
        // §9.5 rail order. Series: seasons, episodes, cast, More Like This. Movie: cast, then similar.
        if (state.isSeries) {
            if (focusedEpisode != null) {
                Column(Modifier.padding(end = 96.dp)) {
                    EpisodeSummary(focusedEpisode, state.progress, meta?.runtimeMinutes, todayIso)
                    Spacer(Modifier.height(28.dp))
                }
            }
            EpisodesRow(
                    episodes = state.episodes,
                    progress = state.progress,
                    showRuntimeMinutes = meta?.runtimeMinutes,
                    todayIso = todayIso,
                    onSources = onEpisodeSources,
                    onMenu = onEpisodeMenu,
                    loading = state.loading,
                    playTarget = playTarget,
                    focusedEpisodeKey = focusedEpisodeKey,
                    onEpisodeFocus = onEpisodeFocus,
                    returnFocusRequester = episodeReturnFocus,
                    onReturnTargetPlaced = onEpisodePlaced,
                    orderFocus = orderFocus,
                    upFocus = actionsFocus,
                    episodesFocus = episodesFocus,
                    seasons = meta?.seasons.orEmpty(),
                    selectedSeason = state.selectedSeason,
                    onSeason = onSeason,
                    seasonsFocus = seasonsFocus,
            )
            Spacer(Modifier.height(80.dp))

        }

        if (!showSupplemental) return@Column

        // §9.8 asks these two rails for a skeleton while the page loads, and this page does not give
        // them one. Cast and More Like This both arrive inside the one `Meta`, so a skeleton here is a
        // promise the page cannot keep: when the meta lands with no cast, the block it was holding
        // disappears and More Like This jumps up 252 px under the ring. A rail is drawn once it has
        // something in it, and after that it never moves. The hero and the episodes row still carry
        // the loading state, so the page is never bare.
        if (meta?.cast?.isNotEmpty() == true) {
            DetailSectionTitle("Cast")
            Spacer(Modifier.height(TvSpace.S))
            CastRow(meta.cast, onPerson, returnFocusKey = castReturnKey, onReturnFinished = onCastReturnFinished)
            Spacer(Modifier.height(TvSpace.L))
        }

        if (meta?.similar?.isNotEmpty() == true) {
            DetailSectionTitle("More like this")
            Spacer(Modifier.height(TvSpace.S))
            MoreLikeThis(
                similar = meta.similar,
                onOpen = onOpen,
                onLoveTitle = onLoveTitle,
                returnFocusKey = relatedReturnKey,
                onReturnFinished = onRelatedReturnFinished,
            )
        }
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

/** §9.8 Error: the title still draws, and Retry takes focus. */
@Composable
private fun MetaError(meta: Meta?, loading: Boolean, message: String?, onRetry: () -> Unit, actionModifier: Modifier) {
    Column(Modifier.width(980.dp).height(600.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        meta?.let {
            Text(it.title, style = TvType.HeroTitle, color = TvColor.TextPrimary,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(DetailFormat.metaLine(it), style = TvType.Body, color = TvColor.TextSecondary)
        }
        StateBlock(
            headline = if (loading) "Loading title details" else "Title details could not load",
            line = if (loading) "Trying again…" else message ?: "Check your connection or add-ons, then try again.",
            actionLabel = "Retry details",
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
    contentScale: ContentScale = ContentScale.Fit,
    alignment: Alignment = Alignment.Center,
    logo: Boolean = false,
    fallbackUrls: List<String> = emptyList(),
    onSettled: () -> Unit = {},
) {
    val failed = remember(url) { mutableStateOf(emptySet<String>()) }
    var retry by remember(url) { mutableStateOf(0) }
    val candidates = remember(url, fallbackUrls) {
        (listOfNotNull(url) + fallbackUrls).filter(String::isNotBlank).distinct()
    }
    val candidate = candidates.firstOrNull { it !in failed.value }
    LaunchedEffect(candidate, candidates, retry) {
        if (candidate == null && candidates.isNotEmpty() && retry == 0) {
            delay(800)
            retry = 1
            failed.value = emptySet()
        } else if (candidate == null) {
            onSettled()
        }
    }
    val sized = candidate?.let {
        if (logo) TmdbSize.logo(it, targetWidth) else TmdbSize.sized(it, targetWidth)
    }
    val painted = remember(sized) { mutableStateOf(false) }
    var logoBitmap by remember(sized) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var lightLogoInk by remember { mutableStateOf(false) }
    var logoPainter by remember { mutableStateOf<Painter?>(null) }
    LaunchedEffect(logoBitmap) {
        val bitmap = logoBitmap ?: return@LaunchedEffect
        val (bounds, lightInk) = withContext(Dispatchers.Default) {
            val sample = IntArray(32 * 32) { index ->
                val x = ((index % 32 + 0.5f) * bitmap.width / 32).toInt().coerceAtMost(bitmap.width - 1)
                val y = ((index / 32 + 0.5f) * bitmap.height / 32).toInt().coerceAtMost(bitmap.height - 1)
                bitmap.getPixel(x, y)
            }
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            DetailLogoContrast.inkBounds(pixels, bitmap.width, bitmap.height) to DetailLogoContrast.needsLightInk(sample)
        }
        if (bounds == null) {
            candidate?.let { failed.value = failed.value + it }
        } else {
            lightLogoInk = lightInk
            logoPainter = BitmapPainter(bitmap.asImageBitmap(),
                srcOffset = IntOffset(bounds[0], bounds[1]), srcSize = IntSize(bounds[2], bounds[3]))
            onSettled()
        }
    }
    val context = LocalContext.current
    val request = remember(context, sized, logo, retry) {
        sized?.let {
            ImageRequest.Builder(context).data(it).apply {
                // The page owns the reveal; nested fades would expose placeholders again.
                crossfade(false)
                if (logo) { tmdbLogo(); allowHardware(false) }
                if (retry > 0) diskCachePolicy(coil3.request.CachePolicy.WRITE_ONLY)
            }.build()
        }
    }
    Box(modifier, contentAlignment = alignment) {
        if (fallback != null && !painted.value && logoPainter == null) {
            Text(
                text = fallback,
                style = if (logo) TvType.HeroTitle else TvType.PlateTitle,
                color = if (logo) TvColor.TextPrimary else TvColor.TextSecondary,
                maxLines = if (logo) 2 else 3,
                textAlign = if (alignment == Alignment.BottomStart) TextAlign.Start else TextAlign.Center,
                overflow = TextOverflow.Ellipsis,
                modifier = if (logo) Modifier else Modifier.padding(horizontal = TvSpace.S),
            )
        }
        if (logoPainter != null) {
            Image(painter = requireNotNull(logoPainter), contentDescription = null, contentScale = contentScale,
                alignment = alignment, modifier = Modifier.widthIn(max = 800.dp).fillMaxSize(),
                colorFilter = if (lightLogoInk) ColorFilter.tint(TvColor.TextPrimary) else null)
        } else if (sized != null) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = contentScale,
                alignment = alignment,
                onSuccess = { result ->
                    if (logo) {
                        logoBitmap = (result.result.image as? coil3.BitmapImage)?.bitmap
                        if (logoBitmap == null) candidate?.let { failed.value = failed.value + it }
                    } else {
                        painted.value = true
                        onSettled()
                    }
                },
                onError = { candidate?.let { failed.value = failed.value + it } },
                modifier = Modifier.fillMaxSize().then(if (logo) Modifier.graphicsLayer { alpha = 0f } else Modifier),
            )
        }
    }
}

/** Only dark, neutral ink on transparency is lifted. Colored and opaque artwork stays intact. */
internal object DetailLogoContrast {
    /** Exclude only fully transparent margins, retaining every visible edge and shadow. */
    fun inkBounds(pixels: IntArray, width: Int, height: Int): IntArray? {
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        pixels.forEachIndexed { index, pixel ->
            if (pixel ushr 24 > 0) {
                val x = index % width
                val y = index / width
                left = minOf(left, x); top = minOf(top, y)
                right = maxOf(right, x); bottom = maxOf(bottom, y)
            }
        }
        return if (right < left) null else intArrayOf(left, top, right - left + 1, bottom - top + 1)
    }

    fun needsLightInk(pixels: IntArray): Boolean {
        var visible = 0
        var darkNeutral = 0
        var transparent = 0
        for (pixel in pixels) {
            val alpha = pixel ushr 24
            if (alpha < 64) { transparent++; continue }
            visible++
            val red = pixel ushr 16 and 255
            val green = pixel ushr 8 and 255
            val blue = pixel and 255
            val maximum = maxOf(red, green, blue)
            val minimum = minOf(red, green, blue)
            if (maximum <= 90 && maximum - minimum <= 24) darkNeutral++
        }
        return visible > 0 && transparent > pixels.size / 20 && darkNeutral * 10 >= visible * 9
    }
}

// ---------------------------------------------------------------------------- trailer

private val TrailerYouTubeId = Regex("[A-Za-z0-9_-]{11}")

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TrailerDialog(videoId: String, onClose: () -> Unit, onUnavailable: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val reduceMotion = LocalReduceMotion.current
    val focus = remember { FocusRequester() }
    val fallbackFocus = remember { FocusRequester() }
    val visibility = remember { MutableTransitionState(false).apply { targetState = true } }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var failed by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(false) }
    var lastInput by remember { mutableStateOf(0) }

    fun runControl(command: String) {
        if (!visibility.targetState) return
        controlsVisible = true
        lastInput++
        if (ready) webView?.evaluateJavascript(command, null)
    }

    LaunchedEffect(lastInput) {
        delay(TvMotion.ControlsAutoHideMillis.toLong())
        controlsVisible = false
    }
    LaunchedEffect(visibility.isIdle, visibility.targetState) {
        if (visibility.isIdle && !visibility.targetState) onClose()
    }
    LaunchedEffect(webView, visibility.targetState, failed) {
        val player = webView ?: return@LaunchedEffect
        if (!visibility.targetState || failed) return@LaunchedEffect
        val startedAt = android.os.SystemClock.elapsedRealtime()
        while (true) {
            player.evaluateJavascript("typeof trailerStatus === 'function' ? trailerStatus() : null") { result ->
                if (failed) return@evaluateJavascript
                val status = runCatching { JSONArray(result) }.getOrNull()
                if (status != null) {
                    failed = status.optBoolean(0)
                    ready = status.optBoolean(1)
                    playing = status.optInt(2) == 1
                    if (controlsVisible) {
                        positionMs = (status.optDouble(3, 0.0) * 1000).toLong()
                        durationMs = (status.optDouble(4, 0.0) * 1000).toLong()
                    }
                }
                if (!ready && android.os.SystemClock.elapsedRealtime() - startedAt >= 20_000) failed = true
            }
            delay(if (controlsVisible) 250L else 750L)
        }
    }
    DisposableEffect(webView, lifecycle) {
        val player = webView
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) player?.evaluateJavascript(PauseTrailer, null)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Dialog(onDismissRequest = {
        webView?.evaluateJavascript(PauseTrailer, null)
        visibility.targetState = false
    }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val panelWidth = minOf(maxWidth * 0.84f, (maxHeight * 0.84f - 50.dp) * (16f / 9f))
            val enter = if (reduceMotion) EnterTransition.None else
                fadeIn(tween(TvMotion.PosterOpenMillis, easing = TvMotion.Out)) +
                    scaleIn(tween(TvMotion.PosterOpenMillis, easing = TvMotion.Out), initialScale = 0.96f)
            val exit = if (reduceMotion) ExitTransition.None else
                fadeOut(tween(TvMotion.PosterCloseMillis, easing = TvMotion.Std)) +
                    scaleOut(tween(TvMotion.PosterCloseMillis, easing = TvMotion.Std), targetScale = 0.96f)
            AnimatedVisibility(visibleState = visibility, enter = enter, exit = exit) {
                Column(Modifier.width(panelWidth).background(Color.Black, RoundedCornerShape(16.dp)).padding(8.dp)) {
                    LaunchedEffect(failed) {
                        withFrameNanos { }
                        (if (failed) fallbackFocus else focus).requestFocus()
                    }
                    if (failed) {
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text("Trailer unavailable here", style = TvType.Body, color = TvColor.TextPrimary)
                                TvButton("Watch on YouTube", onClick = {
                                    val intent = Intent(Intent.ACTION_VIEW,
                                        Uri.parse("https://www.youtube.com/watch?v=$videoId"))
                                    if (runCatching { context.startActivity(intent) }.isFailure) {
                                        onUnavailable("No app can open YouTube")
                                    }
                                }, modifier = Modifier.focusRequester(fallbackFocus))
                            }
                        }
                        Spacer(Modifier.height(34.dp))
                    } else {
                        TvFocusable(onClick = { runControl("toggleTrailer()") },
                            accessibleLabel = "Trailer playback",
                            clickLabel = "Play or pause",
                            focusScale = 1f,
                            focusRing = false,
                            modifier = Modifier.focusRequester(focus).onPreviewKeyEvent { event ->
                                val seek = when (event.key) {
                                    Key.DirectionLeft -> -10
                                    Key.DirectionRight -> 10
                                    else -> return@onPreviewKeyEvent false
                                }
                                if (event.type == KeyEventType.KeyUp) runControl("seekTrailer($seek)")
                                true
                            }) {
                            AndroidView(
                                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                                factory = { ctx ->
                                    runCatching {
                                        WebView(ctx).apply {
                                            isFocusable = false
                                            setBackgroundColor(android.graphics.Color.BLACK)
                                            settings.javaScriptEnabled = true
                                            settings.domStorageEnabled = true
                                            settings.mediaPlaybackRequiresUserGesture = false
                                            settings.allowFileAccess = false
                                            settings.allowContentAccess = false
                                            webViewClient = object : WebViewClient() {
                                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                                                    request.isForMainFrame

                                                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                                    if (request.isForMainFrame ||
                                                        request.url.host == "www.youtube.com" && request.url.path == "/embed/$videoId"
                                                    ) failed = true
                                                }

                                                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                                    webView = null
                                                    failed = true
                                                    return true
                                                }
                                            }
                                            val origin = "https://${ctx.packageName}"
                                            loadDataWithBaseURL(origin, trailerHtml(videoId, origin), "text/html", "UTF-8", null)
                                            webView = this
                                        }
                                    }.getOrElse { failed = true; View(ctx) }
                                },
                                onRelease = { view ->
                                    val player = view as? WebView
                                    if (webView === player) webView = null
                                    player?.destroy()
                                },
                            )
                        }
                        Row(Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (!ready) {
                                Text("Loading trailer…", style = TvType.Meta, color = TvColor.TextSecondary)
                            } else if (controlsVisible) {
                                Image(painterResource(if (playing) R.drawable.ic_player_pause else R.drawable.ic_player_play),
                                    contentDescription = null, modifier = Modifier.size(20.dp), colorFilter = ColorFilter.tint(Color.White))
                                Text(PlayerControlsPolicy.formatTime(positionMs), style = TvType.Meta, color = Color.White)
                                Box(Modifier.weight(1f).height(4.dp).background(Color.White.copy(alpha = 0.28f))) {
                                    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
                                    Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(TvColor.Accent))
                                }
                                Text(PlayerControlsPolicy.formatTime(durationMs), style = TvType.Meta, color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val PauseTrailer = "if (typeof pauseTrailer === 'function') pauseTrailer()"

private fun trailerHtml(videoId: String, origin: String) = """
    <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
    <style>html,body{margin:0;overflow:hidden;background:#000}
    iframe{display:block;width:100%;border:0}</style></head><body>
    <iframe id="player" title="YouTube trailer" allow="autoplay; encrypted-media"
        src="https://www.youtube.com/embed/$videoId?enablejsapi=1&origin=$origin&playsinline=1&controls=0&disablekb=1&fs=0&rel=0"></iframe>
    <script>
      let player, ready = false, failed = false, active = true, started = false;
      const frame = document.getElementById('player');
      function fitTrailer() {
        // Percentage heights can resolve to zero in Android WebView's document layout.
        if (window.innerHeight <= 0) return;
        frame.style.height = window.innerHeight + 'px';
        if (ready && active && !started) {
          started = true;
          player.playVideo();
        }
      }
      window.addEventListener('resize', fitTrailer);
      fitTrailer();
      function onYouTubeIframeAPIReady() {
        player = new YT.Player('player', {events: {
          onReady: function() { ready = true; fitTrailer(); },
          onError: function() { failed = true; }
        }});
      }
      function pauseTrailer() { active = false; if (ready) player.pauseVideo(); }
      function toggleTrailer() {
        if (!ready) return;
        if (player.getPlayerState() === 1) player.pauseVideo(); else player.playVideo();
      }
      function seekTrailer(delta) {
        if (ready) player.seekTo(Math.min(player.getDuration(), Math.max(0, player.getCurrentTime() + delta)), true);
      }
      function trailerStatus() {
        return [failed, ready, ready ? player.getPlayerState() : -1,
          ready ? player.getCurrentTime() : 0, ready ? player.getDuration() : 0];
      }
    </script>
    <script async src="https://www.youtube.com/iframe_api" onerror="failed=true"></script></body></html>
""".trimIndent()
