package com.fourseveneightnine.tv.client.ui.screens.player

import com.fourseveneightnine.tv.client.data.meta.episodeDisplayTitle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.fourseveneightnine.tv.R
import com.fourseveneightnine.tv.client.ui.screens.detail.DetailFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.data.images.PosterRequest
import com.fourseveneightnine.tv.client.data.meta.Episode
import com.fourseveneightnine.tv.client.data.meta.Meta
import com.fourseveneightnine.tv.client.data.streams.StreamRow
import com.fourseveneightnine.tv.client.data.streams.RankedRow
import com.fourseveneightnine.tv.client.playback.PlayRequest
import com.fourseveneightnine.tv.client.playback.PlayResult
import com.fourseveneightnine.tv.client.playback.PlaybackSession
import com.fourseveneightnine.tv.client.playback.TamilMVReleaseFile
import com.fourseveneightnine.tv.client.playback.episodeFiles
import com.fourseveneightnine.tv.client.playback.tamilMVEpisodeFiles
import com.fourseveneightnine.tv.client.ui.components.tvGlassPanel
import com.fourseveneightnine.tv.client.ui.components.tvGlassPill
import com.fourseveneightnine.tv.client.ui.screens.streams.StreamSkeletonRow
import com.fourseveneightnine.tv.client.data.library.LibraryRepository
import com.fourseveneightnine.tv.client.ui.components.TvArtwork
import com.fourseveneightnine.tv.client.ui.components.TvFocusable
import com.fourseveneightnine.tv.client.ui.components.TvFocusableWithMenu
import com.fourseveneightnine.tv.client.ui.components.PanelTransition
import com.fourseveneightnine.tv.client.ui.screens.streams.SourceQualityGroups
import com.fourseveneightnine.tv.client.ui.screens.streams.SourceOptions
import com.fourseveneightnine.tv.client.ui.screens.streams.SourceFormatMark
import com.fourseveneightnine.tv.client.ui.screens.streams.sourceAudioLabel
import com.fourseveneightnine.tv.client.ui.screens.streams.sourceHdrLabel
import com.fourseveneightnine.tv.client.ui.screens.streams.sourceImaxLabel
import com.fourseveneightnine.tv.client.ui.screens.streams.StreamsViewModel
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvType
import com.fourseveneightnine.tv.player.ReceiverPlaybackPhase
import com.fourseveneightnine.tv.ui.PlayerControlsPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

/** Capability URLs stay in memory; matching includes the exact file and required headers. */
internal fun samePlayerSource(a: StreamRow, b: StreamRow): Boolean =
    ((a.id == b.id && a.id.isNotBlank() && (a.validationSourceKey != null || b.validationSourceKey != null)) ||
        a.playbackKey() == b.playbackKey()) && a.torboxFileID == b.torboxFileID

/** A saved release supplies its own episode inventory; metadata only enriches matching entries. */
internal fun playerEpisodeList(id: String, metadata: Meta?, files: List<TamilMVReleaseFile>): List<Episode> {
    val supplied = files.mapNotNull { file ->
        val season = file.season ?: return@mapNotNull null
        val episode = file.episode ?: return@mapNotNull null
        val known = metadata?.videos?.firstOrNull { it.season == season && it.episode == episode }
        Episode(season, episode, known?.title ?: if (file.cached) file.filename else "Episode $episode",
            overview = known?.overview, thumbnail = known?.thumbnail, released = known?.released,
            id = known?.id ?: "$id:$season:$episode",
            inlineStreams = if (file.cached) listOf(file.stream) else null)
    }.distinctBy { it.season to it.episode }.sortedWith(compareBy({ it.season }, { it.episode }))
    return supplied.ifEmpty { metadata?.videos.orEmpty() }
}

internal data class DrawerPreparation(val request: PlayRequest, val cancel: () -> Unit)

/** One player-owned drawer. It never changes the video viewport or places a scrim over it. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun PlayerBrowsePanel(
    request: PlayRequest,
    episodes: Boolean,
    session: PlaybackSession,
    onPreparing: (DrawerPreparation?) -> Unit,
    onClose: () -> Unit,
    onSources: (PlayRequest) -> Unit,
) {
    val client = LocalContext.current.clientGraph
    val scope = rememberCoroutineScope()
    val currentRequest by client.playFlow.current.collectAsState()
    val origin by session.origin.collectAsState()
    val active by client.playFlow.activeSource.collectAsState()
    val phase by session.phase.collectAsState()
    var meta by remember(request.type, request.id) { mutableStateOf<Meta?>(null) }
    var releaseEpisodes by remember(request.type, request.id) { mutableStateOf(emptyList<TamilMVReleaseFile>()) }
    var episodeLoadMessage by remember(request.type, request.id) { mutableStateOf<String?>(null) }
    var metadataFailed by remember { mutableStateOf(false) }
    var episodeLoading by remember { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var openJob by remember { mutableStateOf<Job?>(null) }
    val library = client.activeLibrary
    val historyFlow = remember(library) { library.playbackHistory() }
    val history by historyFlow.collectAsState(initial = emptyList())
    var chosenEpisode by remember { mutableStateOf<Episode?>(null) }
    var season by rememberSaveable(request.id) { mutableIntStateOf(request.season ?: 1) }
    val first = remember { FocusRequester() }
    var menuRow by remember { mutableStateOf<RankedRow?>(null) }
    var menuReturnKey by remember { mutableStateOf<String?>(null) }
    val menuReturnFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val seasonFocus = remember { FocusRequester() }

    LaunchedEffect(request.type, request.id, episodes, reload) {
        episodeLoading = true
        metadataFailed = false
        episodeLoadMessage = null
        try {
            val services = client.services.filterNotNull().first()
            val saved = client.savedCatalogItem(request.type, request.id)
            val metadataID = saved?.imdbId ?: saved?.tmdbId?.let { "tmdb:$it" } ?: request.id
            if (episodes && request.id.startsWith("saved:tamilmv:")) {
                val release = try { client.resolveCatalogItem(request.type, request.id) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { saved?.takeIf { it.playbackSources.isNotEmpty() } }
                    ?: error("This release is unavailable. Refresh TamilMV Collection.")
                releaseEpisodes = tamilMVEpisodeFiles(release, emptyMap(), includeDeclared = true)
                try {
                    releaseEpisodes = release.episodeFiles(services.debrid, includeDeclared = true)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { episodeLoadMessage = "TorBox could not be checked. Select an episode to check it again." }
            }
            meta = if (metadataID.startsWith("saved:")) null else services.meta.meta(request.type, metadataID.substringAfter("::"))
            metadataFailed = meta == null && releaseEpisodes.isEmpty()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { metadataFailed = true }
        finally { episodeLoading = false }
    }
    val model = if (episodes) null else remember(request.type, request.id, request.season, request.episode) {
        StreamsViewModel(request.type, request.id, request.season, request.episode, client, scope)
    }
    DisposableEffect(model) { onDispose { model?.stop() } }
    val sourceState = model?.state?.collectAsState()?.value
    val isPlaying = phase is ReceiverPlaybackPhase.Playing || phase is ReceiverPlaybackPhase.Paused ||
        phase is ReceiverPlaybackPhase.Buffering
    val playingRequest = currentRequest?.takeIf {
        origin == PlaybackSession.Origin.Local && isPlaying && active != null && it.type == request.type && it.id == request.id
    }
    val playing = active?.takeIf { source -> playingRequest != null &&
        (!source.requiresDmmValidation || source.linkCheckedAtMillis != null) &&
        playingRequest.season == request.season && playingRequest.episode == request.episode }
    val found = sourceState?.displayed.orEmpty()
    val (matching, alternatives) = remember(found, playing) {
        found.partition { playing != null && samePlayerSource(it.row, playing) }
    }
    val pinned = remember(playing, matching) {
        playing?.let { activeRow ->
            val match = matching.firstOrNull()
            if (match == null) RankedRow(activeRow, 0.0, emptyList())
            else match.copy(row = activeRow.copy(quality = activeRow.quality ?: match.row.quality))
        }
    }
    var expanded by rememberSaveable(request.id, request.season, request.episode) { mutableStateOf(false) }
    var revealId by remember(request) { mutableStateOf<String?>(null) }
    val picks = remember(alternatives, pinned?.row?.quality, revealId) {
        SourceQualityGroups.from(alternatives, playingQuality = pinned?.row?.quality, retainedId = revealId)
    }
    val all = remember(alternatives) { SourceQualityGroups.from(alternatives, expanded = true) }
    val hasMore = all.sumOf { it.rows.size } > picks.sumOf { it.rows.size }
    val groups = if (expanded) all else picks
    val reveal = revealId?.takeIf { id -> groups.any { group -> group.rows.any { it.row.id == id } } }
    val entryId = reveal ?: playing?.id ?: groups.firstOrNull()?.rows?.firstOrNull()?.row?.id
    val videos = remember(request.id, meta, releaseEpisodes) { playerEpisodeList(request.id, meta, releaseEpisodes) }
    val seasons = remember(videos) { videos.map { it.season }.distinct().sorted() }
    val visibleEpisodes = remember(videos, season) { videos.filter { it.season == season }.sortedBy { it.episode } }
    var lastEpisode by remember(request.id, season) { mutableStateOf<Int?>(null) }
    val currentIndex = visibleEpisodes.indexOfFirst { it.episode == (lastEpisode ?: request.episode) &&
        (lastEpisode != null || it.season == request.season) }.coerceAtLeast(0)
    val seasonList = rememberLazyListState()
    LaunchedEffect(seasons, season) {
        val index = seasons.indexOf(season)
        if (seasons.size > 1 && index >= 0 && seasonList.layoutInfo.visibleItemsInfo.none { it.index == index }) seasonList.scrollToItem(index)
    }
    var placed by remember(request.id, request.season, request.episode, episodes, season, chosenEpisode) { mutableStateOf(false) }

    LaunchedEffect(seasons) {
        if (seasons.isNotEmpty() && season !in seasons) season = seasons.first()
    }
    val entryReady = if (episodes) visibleEpisodes.isNotEmpty() || !episodeLoading || chosenEpisode != null
        else playing != null || sourceState?.search?.done == true
    LaunchedEffect(episodes, season, entryReady, chosenEpisode, expanded, entryId) {
        if (placed || !entryReady) return@LaunchedEffect
        if (episodes && chosenEpisode == null && visibleEpisodes.isNotEmpty()) {
            listState.scrollToItem(currentIndex)
        } else if (!episodes && entryId != null) {
            val index = if (reveal == null) 0 else SourceQualityGroups.listIndex(groups, reveal) +
                if (playing == null) 0 else 1
            listState.scrollToItem(index)
        }
        withFrameNanos { }
        if (runCatching { first.requestFocus() }.getOrDefault(false)) placed = true
    }

    fun playSource(row: StreamRow) {
        if (busy) return
        busy = true
        onPreparing(DrawerPreparation(request) { openJob?.cancel() })
        message = null
        openJob = scope.launch {
            try {
                session.refreshSnapshot()
                when (val result = client.playFlow.playRow(row, request)) {
                    PlayResult.Opened -> onClose()
                    PlayResult.ShowList -> message = "Choose another source."
                    is PlayResult.Failed -> { revealId = row.id; model?.refreshAvailability(row); message = result.message }
                }
            } finally { busy = false; onPreparing(null) }
        }
    }
    @Composable
    fun SourceRow(rankedRow: RankedRow, current: Boolean) {
        val row = rankedRow.row
        val formats = listOfNotNull(row.quality,
            when (row.hdr.name) { "HDR10" -> sourceHdrLabel(row.releaseName); "DOLBY_VISION" -> "Dolby Vision"; else -> null },
            sourceAudioLabel(row.facts.audio), row.facts.audioChannels, sourceImaxLabel(row.releaseName)).distinct()
        val quality = formats.joinToString(" · ").ifBlank { "Quality not reported" }
        PlayerDrawerRow(quality, listOf(row.audioLanguageNames.joinToString(" / "), row.addonName).filter(String::isNotBlank).joinToString(" · "),
            current, Modifier.then(if (row.id == entryId && current == (playing != null && reveal == null))
                Modifier.focusRequester(first) else Modifier)
                .then(if (menuReturnKey != null && row.playbackKey() == menuReturnKey) Modifier.focusRequester(menuReturnFocus) else Modifier)
                .onFocusChanged { if (it.isFocused) {
                    placed = true
                    if (revealId != null && revealId != row.id) revealId = null
                    model?.onFocus(if (current) null else row.id)
                } },
            formatLabels = formats,
            detail = listOfNotNull(PlayerControlsPolicy.sourceContextSubtitle(row.title, formats), row.sizeBytes?.takeIf { it > 0 }?.let { android.text.format.Formatter.formatShortFileSize(LocalContext.current, it) }).joinToString(" · ").takeIf(String::isNotBlank), onLongClick = {
            if (!busy) {
                menuReturnKey = row.playbackKey()
                menuRow = rankedRow
            }
        }) {
            if (current) onClose() else playSource(row)
        }
    }

    fun closeSourceMenu() {
        menuRow = null
        scope.launch { withFrameNanos { }; runCatching { menuReturnFocus.requestFocus() } }
    }

    fun playEpisode(episode: Episode, restart: Boolean) {
        if (busy) return
        busy = true
        message = null
        openJob = scope.launch {
            try {
                val progress = history.firstOrNull { it.canonicalId == request.id && it.season == episode.season && it.episode == episode.episode }
                val target = request.copy(season = episode.season, episode = episode.episode,
                    resumeFromMs = if (restart) 0L else progress?.positionMs, forceList = false)
                onPreparing(DrawerPreparation(target) { openJob?.cancel() })
                val result = if (request.id.startsWith("saved:tamilmv:")) {
                    val exact = episode.inlineStreams?.singleOrNull() ?: run {
                        val release = client.savedCatalogItem(request.type, request.id)?.takeIf { it.playbackSources.isNotEmpty() }
                            ?: client.resolveCatalogItem(request.type, request.id)
                            ?: error("This release is unavailable. Refresh TamilMV Collection.")
                        val services = client.services.filterNotNull().first()
                        release.episodeFiles(services.debrid).firstOrNull {
                            it.season == episode.season && it.episode == episode.episode
                        }?.stream
                    }
                    if (exact == null) {
                        message = "TorBox has not confirmed this episode is ready. Retry or choose another episode."
                        return@launch
                    }
                    client.playFlow.playRow(exact, target)
                } else client.playFlow.play(target)
                when (result) {
                    PlayResult.Opened -> onClose()
                    PlayResult.ShowList -> onSources(target)
                    is PlayResult.Failed -> message = result.message
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "This episode could not be checked. Retry or choose another episode." }
            finally { busy = false; onPreparing(null) }
        }
    }

    Column(
        Modifier.fillMaxHeight().padding(end = 48.dp, top = 40.dp, bottom = 40.dp).width(640.dp)
            .then(if (menuRow != null || busy) Modifier.graphicsLayer { alpha = 0f }.clearAndSetSemantics {} else Modifier)
            .tvGlassPanel(30.dp)
            .focusProperties { exit = { direction -> if (direction == FocusDirection.Enter || direction == FocusDirection.Exit) FocusRequester.Default else FocusRequester.Cancel } }
            .focusGroup().semantics { paneTitle = if (episodes) "All episodes" else "Sources" }
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft && !episodes) { onClose(); true } else false
            }.padding(horizontal = 28.dp, vertical = 26.dp),
    ) {
        if (episodes) {
            Text("All episodes", style = TvType.ControlLabel.copy(fontSize = 21.sp), color = TvColor.TextSecondary)
            Text(request.title, style = TvType.PanelHeader.copy(fontSize = 38.sp, lineHeight = 44.sp),
                color = TvColor.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        } else {
            Text("Sources", style = TvType.PanelHeader.copy(fontSize = 40.sp), color = TvColor.TextPrimary)
            Spacer(Modifier.height(12.dp))
            Text(request.title, style = TvType.ControlLabel, color = TvColor.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!episodes && request.episode != null) Text(listOfNotNull(request.season?.let { "Season $it" },
            "Episode ${request.episode}").joinToString("  ·  "), style = TvType.SourceMeta, color = TvColor.TextSecondary)
        Spacer(Modifier.height(24.dp))
        if (episodes) {
            if (videos.isNotEmpty()) episodeLoadMessage?.let {
                Text(it, style = TvType.SourceMeta, color = TvColor.TextSecondary)
                Spacer(Modifier.height(12.dp))
            }
            if (chosenEpisode != null) {
                val selected = requireNotNull(chosenEpisode)
                Text(episodeDisplayTitle(selected.title).takeIf { it.isNotBlank() && !it.equals("Episode ${selected.episode}", true) }
                    ?.let { "Episode ${selected.episode} · $it" } ?: "Episode ${selected.episode}",
                    style = TvType.PlateTitle, color = TvColor.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(24.dp))
                val position = history.firstOrNull { it.canonicalId == request.id && it.season == selected.season && it.episode == selected.episode }?.positionMs
                PlayerDrawerRow("Resume episode", position?.let { PlayerControlsPolicy.formatTime(it) }.orEmpty(), false, Modifier.focusRequester(first)) { playEpisode(selected, false) }
                Spacer(Modifier.height(12.dp))
                PlayerDrawerRow("Start from beginning", "", false) { playEpisode(selected, true) }
                Spacer(Modifier.weight(1f))
            } else if (videos.isEmpty()) {
                Text(if (episodeLoading) "Loading episodes…" else if (metadataFailed) "Episodes could not be loaded." else "No episodes were supplied.", style = TvType.Body, color = TvColor.TextSecondary)
                Spacer(Modifier.height(20.dp))
                if (!episodeLoading) PlayerDrawerRow("Retry", "", false, Modifier.focusRequester(first)) { reload++ }
                Spacer(Modifier.weight(1f))
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (seasons.size == 1) {
                        Text(DetailFormat.seasonLabel(seasons.single()), modifier = Modifier.weight(1f),
                            style = TvType.ControlLabel, color = TvColor.TextPrimary)
                    } else LazyRow(Modifier.weight(1f), state = seasonList, contentPadding = PaddingValues(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(seasons, key = { _, value -> value }) { _, value ->
                            val label = DetailFormat.seasonLabel(value)
                            TvFocusable(onClick = { season = value; chosenEpisode = null },
                                modifier = if (season == value) Modifier.focusRequester(seasonFocus) else Modifier,
                                accessibleLabel = label, selected = season == value, cornerRadius = 26.dp, focusScale = 1f) { focused ->
                                Text(label, style = TvType.ControlLabel,
                                    color = if (value == season) TvColor.Success else TvColor.TextPrimary,
                                    modifier = Modifier.clip(RoundedCornerShape(26.dp)).tvGlassPill({ null }, focused, false)
                                        .padding(horizontal = 18.dp, vertical = 11.dp))
                            }
                        }
                    }
                    val count = visibleEpisodes.size
                    Text(if (count == 1) "1 episode" else "$count episodes", style = TvType.SourceMeta, color = TvColor.TextSecondary)
                }
                Spacer(Modifier.height(14.dp))
                var focusedEpisode by remember(season, request.id) { mutableStateOf<Episode?>(null) }
                LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                    itemsIndexed(visibleEpisodes, key = { _, e -> "${e.season}:${e.episode}" }) { index, value ->
                        val current = playingRequest?.season == value.season && playingRequest?.episode == value.episode
                        val progress = history.firstOrNull { it.canonicalId == request.id && it.season == value.season && it.episode == value.episode }
                        val status = when {
                            current -> "Playing"
                            progress != null && progress.progressFraction >= LibraryRepository.FINISHED_FRACTION -> "Watched"
                            progress != null && progress.positionMs > 0L -> "Resume at ${PlayerControlsPolicy.formatTime(progress.positionMs)}"
                            request.id.startsWith("saved:tamilmv:") && value.inlineStreams.isNullOrEmpty() -> "Check TorBox"
                            else -> null
                        }
                        DrawerEpisodeRow(value, status, current, Modifier
                            .then(if (index == currentIndex) Modifier.focusRequester(first) else Modifier)
                            .onFocusChanged { if (it.hasFocus) focusedEpisode = value }
                            .focusProperties {
                                if (index == 0) up = if (seasons.size > 1) seasonFocus else FocusRequester.Cancel
                            }) {
                            if (current) onClose() else if (progress != null && progress.positionMs > 0L && progress.progressFraction < LibraryRepository.FINISHED_FRACTION) {
                                lastEpisode = value.episode
                                chosenEpisode = value
                            } else playEpisode(value, true)
                        }
                    }
                }
                if (remember(visibleEpisodes) { visibleEpisodes.any { !it.overview.isNullOrBlank() } }) {
                    Spacer(Modifier.height(12.dp))
                    Text(focusedEpisode?.overview.orEmpty(), style = TvType.SourceMeta, color = TvColor.TextSecondary,
                        minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        } else {
            if (all.isNotEmpty()) Text(sourceState?.countChip.orEmpty(), style = TvType.SourceMeta, color = TvColor.TextSecondary)
            Spacer(Modifier.height(16.dp))
            if (all.isEmpty() && playing == null) {
                Text(if (sourceState?.search?.done == true) sourceState.emptyMessage else "Finding alternatives while your video keeps playing…",
                    style = TvType.Body, color = TvColor.TextSecondary)
                Spacer(Modifier.height(20.dp))
                if (sourceState?.search?.done == true) {
                    PlayerDrawerRow("Retry", "", false, Modifier.focusRequester(first)) { model?.start() }
                } else {
                    repeat(2) { index ->
                        StreamSkeletonRow(sweep = index == 0)
                        Spacer(Modifier.height(14.dp))
                    }
                }
                Spacer(Modifier.weight(1f))
            } else LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(4.dp)) {
                if (pinned != null) item(key = "playing") { SourceRow(pinned, current = true) }
                groups.forEach { group ->
                    item(key = "delivery:${group.label}") { Text(group.label, style = TvType.ShelfHeader, color = TvColor.TextPrimary) }
                    itemsIndexed(group.rows, key = { _, rankedRow -> "source:${rankedRow.row.id}" }) { _, rankedRow ->
                        SourceRow(rankedRow, current = false)
                    }
                }
            }
            if (hasMore) {
                com.fourseveneightnine.tv.client.ui.components.SidePanelRow(
                    if (expanded) "Show less" else "Show more", false, {
                        val shown = picks.flatMap { it.rows }.mapTo(hashSetOf()) { it.row.id }
                        revealId = if (expanded) null else all.flatMap { it.rows }.firstOrNull { it.row.id !in shown }?.row?.id
                        expanded = !expanded
                        placed = false
                    }, modifier = if (entryId == null) Modifier.focusRequester(first) else Modifier,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        if (message != null) Text(message.orEmpty(),
            style = TvType.SourceMeta, color = TvColor.Accent, maxLines = 2)
        Spacer(Modifier.height(12.dp))
        if (!episodes && entryId != null) Text("Hold OK for source details", style = TvType.SourceMeta, color = TvColor.TextSecondary)
    }
    PanelTransition(menuRow) { ranked ->
        SourceOptions(ranked, client, request, onPlay = { menuRow = null; playSource(ranked.row) },
            onClose = ::closeSourceMenu, onToast = { message = it }, playerOverlay = true)
    }
    BackHandler(enabled = busy || chosenEpisode != null || menuRow != null) {
        if (busy) { openJob?.cancel(); busy = false; message = "Selection cancelled." }
        else if (menuRow != null) closeSourceMenu()
        else chosenEpisode = null
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DrawerEpisodeRow(
    episode: Episode,
    status: String?,
    playing: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val number = "Episode ${episode.episode}"
    val title = remember(episode.title, number) { episodeDisplayTitle(episode.title).ifBlank { number } }
    val date = remember(episode.released) { DetailFormat.airDate(episode.released) }
    val shape = RoundedCornerShape(16.dp)
    TvFocusable(onClick, modifier.fillMaxWidth(), accessibleLabel = listOfNotNull(number,
        title.takeUnless { it.equals(number, true) }, date, status.takeUnless { playing }).joinToString(", "),
        stateDescription = if (playing) "Currently playing" else null, focusScale = 1f, focusRing = false) { focused ->
        val ink = if (focused) Color(0xFF221F1D) else TvColor.TextPrimary
        val secondaryInk = if (focused) Color(0xFF514A45) else TvColor.TextSecondary
        Row(Modifier.fillMaxWidth().clip(shape)
            .then(if (focused) Modifier.background(Brush.horizontalGradient(listOf(Color(0xFFFFF2E3), Color(0xFFE0DCD8)))) else Modifier)
            .padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(120.dp, 72.dp).clip(RoundedCornerShape(10.dp)).background(TvColor.Elevated2),
                contentAlignment = if (episode.thumbnail.isNullOrBlank()) Alignment.Center else Alignment.BottomStart) {
                if (!episode.thumbnail.isNullOrBlank()) TvArtwork(PosterRequest(episode.thumbnail, 180), "",
                    Modifier.fillMaxSize().clearAndSetSemantics {}, showTitleWhenMissing = false, contentScale = ContentScale.Crop)
                val badge = if (episode.thumbnail.isNullOrBlank()) Modifier else Modifier.padding(5.dp)
                    .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 1.dp)
                Text(episode.episode.toString().padStart(2, '0'), modifier = badge.clearAndSetSemantics {},
                    style = TvType.data(if (episode.thumbnail.isNullOrBlank()) 23 else 16), color = Color.White)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = TvType.SourceTitle.copy(fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium),
                    color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (date != null || status != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (date != null) Text(date, style = TvType.SourceMeta, color = secondaryInk)
                    if (status != null) Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        val color = if (playing && !focused) TvColor.Success else if (playing) Color(0xFF26552F) else secondaryInk
                        if (playing) Image(painterResource(R.drawable.ic_player_play), contentDescription = null,
                            modifier = Modifier.size(19.dp), colorFilter = ColorFilter.tint(color))
                        Text(status, style = TvType.SourceMeta, color = color)
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun PlayerDrawerRow(
    title: String,
    subtitle: String,
    playing: Boolean,
    modifier: Modifier = Modifier,
    detail: String? = null,
    formatLabels: List<String> = emptyList(),
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    val rowContent: @Composable (Boolean) -> Unit = { focused ->
        Row(Modifier.fillMaxWidth().clip(shape)
            .background(Brush.linearGradient(listOf(
                if (focused) Color(0xFF444442) else Color.White.copy(alpha = 0.075f),
                if (focused) Color(0xFF33302F) else Color.White.copy(alpha = 0.025f))))
            .border(if (focused) 3.dp else 1.dp, if (focused) TvColor.Accent else Color.White.copy(alpha = 0.15f), shape)
            .padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (playing) Canvas(Modifier.width(4.dp).height(72.dp)) { drawLine(TvColor.Success, Offset(center.x, 0f), Offset(center.x, size.height), 4.dp.toPx()) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (formatLabels.isEmpty()) Text(title, style = TvType.SourceTitle.copy(fontSize = 28.sp, fontWeight = FontWeight.SemiBold), color = TvColor.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                else FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    formatLabels.forEach { SourceFormatMark(it, height = 24.dp) }
                }
                if (subtitle.isNotBlank() && !(playing && subtitle == "Playing")) Text(subtitle, style = TvType.SourceMeta.copy(fontSize = 21.sp), color = TvColor.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (detail != null) Text(detail, style = TvType.SourceMeta.copy(fontSize = 20.sp), color = TvColor.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (playing) Text("Playing", style = TvType.SourceMeta, color = TvColor.Success)

            }
        }
    }
    if (onLongClick == null) TvFocusable(onClick, modifier.fillMaxWidth(),
        accessibleLabel = listOfNotNull(title, subtitle, detail).joinToString(", "),
        stateDescription = if (playing) "Currently playing" else null, focusScale = 1f, focusRing = false, content = rowContent)
    else TvFocusableWithMenu(onClick, onLongClick, modifier.fillMaxWidth(),
        accessibleLabel = listOfNotNull(title, subtitle, detail).joinToString(", "),
        stateDescription = if (playing) "Currently playing" else null, focusScale = 1f, focusRing = false,
        longClickLabel = "Source details and Favorite for Play", content = rowContent)

}
