package com.fourseveneightnine.tv.client.playback

import com.fourseveneightnine.tv.catalog.EinthusanAccount
import com.fourseveneightnine.tv.catalog.EinthusanFailure
import com.fourseveneightnine.tv.catalog.EinthusanProvider
import com.fourseveneightnine.tv.catalog.EinthusanSource
import com.fourseveneightnine.tv.client.AppGraph
import com.fourseveneightnine.tv.client.ClientGraph
import com.fourseveneightnine.tv.client.data.catalog.CatalogItem
import com.fourseveneightnine.tv.client.data.catalog.TamilMVCatalogRepository
import com.fourseveneightnine.tv.client.data.library.LibraryRepository
import com.fourseveneightnine.tv.client.data.meta.displayTitleOrNull
import com.fourseveneightnine.tv.client.data.meta.Meta
import com.fourseveneightnine.tv.client.data.streams.StreamRow
import com.fourseveneightnine.tv.client.data.streams.OriginalAudioLanguage
import com.fourseveneightnine.tv.client.data.streams.StreamExpectation
import com.fourseveneightnine.tv.client.data.streams.StreamRanker
import com.fourseveneightnine.tv.client.data.streams.PlaybackRules
import com.fourseveneightnine.tv.player.ReceiverPlaybackPhase
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import com.fourseveneightnine.tv.client.data.streams.debrid.DebridOutcome
import com.fourseveneightnine.tv.client.data.streams.debrid.DebridFile
import com.fourseveneightnine.tv.client.data.streams.debrid.TorboxLibraryTorrent
import com.fourseveneightnine.tv.protocol.NowPlayingArtwork
import com.fourseveneightnine.tv.protocol.OpenMediaRequest
import com.fourseveneightnine.tv.protocol.ReceiverTracks
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics
import com.fourseveneightnine.tv.startup.sourceDiagnosticDetails
import com.fourseveneightnine.tv.player.ResumePoint
import com.fourseveneightnine.tv.player.ResumePolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Everything a Play press needs to know. The stream wave adds fields; keep defaults. */
internal data class PlayRequest(
    val type: String,
    val id: String,
    val title: String,
    val season: Int? = null,
    val episode: Int? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val resumeFromMs: Long? = null,
    /** True when the viewer chose Sources: always show the list, never auto-pick. */
    val forceList: Boolean = false,
    val watchlistId: String? = null,
)

/** Provider slates are often served as a valid, very short video for a full title. */
internal fun isUnexpectedShortSource(
    durationSeconds: Double,
    expectedRuntimeMinutes: Int?,
    regularEpisode: Boolean,
): Boolean {
    if (durationSeconds <= 0.0) return false
    return if (expectedRuntimeMinutes != null) {
        // Some providers deliver a two-minute error slate as a successful media response.
        // Require a known full-length title before treating a clip of that length as a failure.
        (expectedRuntimeMinutes >= 5 && durationSeconds <= 30.0) ||
            (expectedRuntimeMinutes >= 10 && durationSeconds <= 121.0)
    } else {
        regularEpisode && durationSeconds <= 10.0
    }
}

internal sealed interface PlayResult {
    /** The player opened; the shell navigates on `PlaybackSession.openRequests`. */
    data object Opened : PlayResult
    /** No auto-pick was possible; the caller should open the Streams screen. */
    data object ShowList : PlayResult
    data class Failed(val message: String, val acceptedGeneration: Long? = null) : PlayResult
}

/**
 * Search, rank, auto-pick, resolve, open.
 *
 * Home, Collections and Detail call [play]; they never touch `StreamSearch` or `DebridResolver`.
 * The Streams screen calls [playRow] when a viewer picked a row themselves. The player reads
 * [nextUp] for its Next pill and its Next Up plate, and calls [playNextUp] when the countdown
 * reaches zero or the viewer presses "Play now".
 */
internal interface PlayFlow {
    suspend fun play(request: PlayRequest): PlayResult
    suspend fun restore(point: ResumePoint): PlayResult = PlayResult.ShowList

    /** Play one row the viewer chose. No ranking, no auto-pick — just resolve and open. */
    suspend fun playRow(row: StreamRow, request: PlayRequest): PlayResult = PlayResult.ShowList

    /**
     * The episode after the one now playing, or null. It carries no URL: the next episode's
     * sources are searched while the plate counts down (`TV_DESIGN_SPEC.md` §11.7).
     */
    val nextUp: StateFlow<NextUpPlan?> get() = noNextUp

    fun warmNextUp() = Unit
    fun cancelNextWarmup() = Unit

    /** Start the episode [nextUp] names. */
    suspend fun playNextUp(): PlayResult = PlayResult.ShowList

    /** Resolve the current local source again after its link expires, keeping the playhead. */
    suspend fun retryCurrent(positionMillis: Long, automatic: Boolean = false): PlayResult = PlayResult.ShowList
    val canRecoverSource: Boolean get() = false

    /** A direct IPTV/recording open replaces any add-on source and progress context. */
    fun clearCurrent() = Unit

    suspend fun captureStopProgress() = Unit
    suspend fun savePlayingSource(): Boolean = false
    val playingSourceSaved: Flow<Boolean> get() = flowOf(false)

    /** The title this flow last opened locally, or null. The shell uses it for Sources-while-playing. */
    val current: StateFlow<PlayRequest?> get() = noCurrent

    /** In-memory identity of the last source accepted by the local player. Never persisted. */
    val activeSource: StateFlow<StreamRow?> get() = noActiveSource

    /** Explicit fallback target; a next-episode failure must not reopen the previous episode. */
    val sourcesRequest: StateFlow<PlayRequest?> get() = current

    /** The current real preparation step; no percentage is inferred. */
    val preparationLabel: StateFlow<String?> get() = noPreparation

    /** The incoming local title while its source is being prepared, before the player opens. */
    val preparing: StateFlow<PlayRequest?> get() = noCurrent
    fun cancelPreparation() = Unit

    val expectedRuntimeMinutes: Int? get() = null
}

/** One shared empty flow, so the default above allocates nothing per implementation. */
private val noNextUp: StateFlow<NextUpPlan?> = MutableStateFlow(null)
private val noCurrent: StateFlow<PlayRequest?> = MutableStateFlow(null)
private val noActiveSource: StateFlow<StreamRow?> = MutableStateFlow(null)
private val noPreparation: StateFlow<String?> = MutableStateFlow(null)

/** Stub for a preview and for a graph that has no settings yet: every play asks for the list. */
internal class StubPlayFlow : PlayFlow {
    override suspend fun play(request: PlayRequest): PlayResult = PlayResult.ShowList
}

/**
 * The real flow (`TV_APP_REBUILD_PLAN.md` §5.6, `client-data/API-A.md` §"Press Play").
 *
 * Three things are deliberate.
 *
 * **It commits as soon as it can, not when the search finishes.** `AutoPick` is asked on every
 * published state, so a box with eleven sources in hand starts on the eleventh rather than waiting
 * out the slowest add-on's 12 s deadline. It only stops early on a decision to PLAY; every "show
 * the list" answer before `done` is provisional, because the row that changes it may still land.
 *
 * **A resolved URL never leaves memory.** It goes into one `OpenMediaRequest` and nothing else —
 * no log line, no Room row, no diagnostics field. `DebridResolver` holds it for 15 minutes and the
 * session drops it with the process.
 *
 * **Failure sentences come from `API-A.md` verbatim.** Each one names what the viewer can do next.
 */
internal class DefaultPlayFlow(
    private val client: ClientGraph,
    private val graph: AppGraph,
    private val codecs: () -> Set<String> = HardwareCodecs::names,
    private val servicesTimeoutMillis: Long = SERVICES_TIMEOUT_MILLIS,
) : PlayFlow {

    private val _nextUp = MutableStateFlow<NextUpPlan?>(null)
    override val nextUp: StateFlow<NextUpPlan?> = _nextUp.asStateFlow()

    private val _current = MutableStateFlow<PlayRequest?>(null)
    override val current: StateFlow<PlayRequest?> = _current.asStateFlow()
    private val _activeSource = MutableStateFlow<StreamRow?>(null)
    override val activeSource: StateFlow<StreamRow?> = _activeSource.asStateFlow()

    private val _sourcesRequest = MutableStateFlow<PlayRequest?>(null)
    override val sourcesRequest: StateFlow<PlayRequest?> = _sourcesRequest.asStateFlow()

    private val _preparationLabel = MutableStateFlow<String?>(null)
    override val preparationLabel: StateFlow<String?> = _preparationLabel.asStateFlow()

    private val _preparing = MutableStateFlow<PlayRequest?>(null)
    override val preparing: StateFlow<PlayRequest?> = _preparing.asStateFlow()
    private var preparationOwner: Any? = null
    private var preparationJob: Job? = null

    @Synchronized
    private fun beginPreparation(request: PlayRequest, job: Job?): Any = Any().also {
        val obsolete = preparationJob
        preparationOwner = it
        preparationJob = job
        _preparing.value = request
        ReceiverDiagnostics.benchmark("play.prepare.start") {
            "owner=${System.identityHashCode(it)} series=${request.type == "series"} resume=${request.resumeFromMs != null} forceList=${request.forceList}"
        }
        if (obsolete !== job) obsolete?.cancel()
    }

    @Synchronized
    private fun endPreparation(owner: Any, clearLabel: Boolean = false) {
        ReceiverDiagnostics.benchmark("play.prepare.end") {
            "owner=${System.identityHashCode(owner)} current=${preparationOwner === owner}"
        }
        if (preparationOwner !== owner) return
        preparationOwner = null
        preparationJob = null
        _preparing.value = null
        if (clearLabel) _preparationLabel.value = null
    }

    @Synchronized
    override fun cancelPreparation() {
        if (preparationOwner != null) ReceiverDiagnostics.benchmark("play.prepare.cancel") {
            "owner=${System.identityHashCode(preparationOwner)}"
        }
        val job = preparationJob
        preparationOwner = null
        preparationJob = null
        _preparing.value = null
        job?.cancel()
    }

    private var progressJob: Job? = null
    private var progressRecorder: ProgressRecorder? = null
    private var progressGeneration: Long? = null
    private var metadataJob: Job? = null
    private var metadataGeneration = 0L
    private var currentSource: StreamRow? = null
    private data class PlayingBookmark(val library: LibraryRepository, val cardId: String, val source: WatchlistSource)
    private val playingBookmark = MutableStateFlow<PlayingBookmark?>(null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val playingSourceSaved: Flow<Boolean> = playingBookmark.flatMapLatest { bookmark ->
        bookmark?.library?.watchlistContains(bookmark.cardId)?.onStart { emit(false) } ?: flowOf(false)
    }

    private var currentFromFavorite = false
    private var recoveredSource = false
    override val canRecoverSource: Boolean get() = currentSource != null && !recoveredSource
    override var expectedRuntimeMinutes: Int? = null
        private set

    private data class EpisodeCandidates(val rows: List<StreamRow>, val preference: EpisodePreference, val complete: Boolean = true)
    private var warmedNext: Deferred<EpisodeCandidates>? = null
    private var warmedOwner = -1L
    private var warmedTrackVersion = -1L
    private var warmedTracks: ReceiverTracks? = null
    private var warmedPlan: NextUpPlan? = null

    override fun cancelNextWarmup() {
        warmedNext?.cancel()
        warmedNext = null
        warmedPlan = null
        warmedTracks = null
    }

    override fun clearCurrent() {
        cancelNextWarmup()
        cancelPreparation()
        progressJob?.cancel()
        progressRecorder = null
        metadataJob?.cancel()
        metadataGeneration++
        _current.value = null
        _sourcesRequest.value = null
        _preparationLabel.value = null
        currentSource = null
        playingBookmark.value = null
        _activeSource.value = null
        currentFromFavorite = false
        expectedRuntimeMinutes = null
        _nextUp.value = null
    }

    override suspend fun savePlayingSource(): Boolean {
        val session = graph.playback
        val request = current.value ?: return false
        val row = activeSource.value ?: return false
        if (session.origin.value != PlaybackSession.Origin.Local || row != currentSource ||
            client.activeProfileId() == null) return false
        val bookmark = playingBookmark.value ?: return false
        if (client.activeLibrary !== bookmark.library) return false
        val episode = NextUp.subtitle(request.season, request.episode, null)
        val label = listOf(request.title, episode, (row.filename ?: row.releaseName).takeUnless {
            it.contains("://") || it == request.title
        }.orEmpty()).filter(String::isNotBlank).joinToString(" · ")
        bookmark.library.saveToWatchlist(bookmark.cardId, request.type, label,
            request.posterUrl ?: session.nowPlayingArt.value?.posterURL, Json.encodeToString(bookmark.source))
        return true
    }

    override suspend fun captureStopProgress(): Unit = withContext(Dispatchers.Main.immediate) {
        val recorder = progressRecorder ?: return@withContext
        val request = _current.value ?: return@withContext
        val generation = graph.playback.generation
        fun ownsRun() = progressRecorder === recorder && _current.value == request &&
            progressGeneration == generation && graph.playback.generation == generation &&
            graph.playback.origin.value == PlaybackSession.Origin.Local
        if (!ownsRun()) return@withContext
        val snapshot = withTimeoutOrNull(250) { graph.playback.refreshSnapshot() }
            ?: graph.playback.snapshot.value
        if (!ownsRun()) return@withContext
        val position = graph.playback.resumePositionMillis()
        try {
            progressJob?.cancelAndJoin()
            if (!ownsRun()) return@withContext
            recorder.record(position, (snapshot.durationSeconds * 1_000).toLong(), ProgressRecorder.Reason.Stop)
            if (ownsRun()) graph.playback.checkpointResume(snapshot)
        } finally {
            // A cancelled/failed save may leave the same film playing. Keep its recorder alive.
            if (ownsRun() && progressJob?.isActive != true) {
                progressJob = client.scope.launch(Dispatchers.Main) {
                    // Successful Stop/replacement advances ownership before this queued job runs.
                    if (ownsRun()) recorder.follow(graph.playback,
                        ProgressTarget(request.id, request.type, request.season, request.episode), generation)
                }
            }
        }
    }

    override suspend fun play(request: PlayRequest): PlayResult {
        val generation = graph.playback.generation
        return coroutineScope {
            val owner = beginPreparation(request, coroutineContext[Job])
            try {
                // Register/cancel preparation on the caller before dispatching expensive source work.
                withContext(Dispatchers.Default) {
                    if (request.id.startsWith(WatchlistSource.PREFIX)) playSavedSource(request, generation)
                    else {
                        val point = if (!request.forceList && (request.resumeFromMs ?: 0) > 0)
                            graph.playback.titleResume(client.activeProfileId()?.toString(), request) else null
                        if (request.episode != null && (request.resumeFromMs ?: 0) > 0) ReceiverDiagnostics.record("source.resume.request",
                            "season=${request.season} episode=${request.episode} resumeMs=${request.resumeFromMs} checkpoint=${point != null}")
                        if (point != null) resumeCatalogPoint(point, request, generation, paused = false)
                        else if ((request.resumeFromMs ?: 0L) > 0L && !request.id.startsWith("saved-torbox:")) PlayResult.ShowList
                        else play(request, generation)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { PlayResult.Failed(PlayPlanner.NO_ANSWER) }
            finally { endPreparation(owner) }
        }.also {
            ReceiverDiagnostics.benchmark("play.result") { "result=${it::class.java.simpleName}" }
            if (it is PlayResult.Failed) reportSourceFailure(it)
        }
    }

    private suspend fun reportSourceFailure(failure: PlayResult.Failed) = withContext(Dispatchers.Main.immediate) {
        graph.playback.reportSourceFailure(failure.acceptedGeneration)
    }

    /** Only the selected file can satisfy a Watchlist card. A missing source stays saved. */
    private suspend fun playSavedSource(
        card: PlayRequest, generation: Long, retrying: Boolean = false,
    ): PlayResult = withContext(Dispatchers.Default) {
        val library = client.activeLibrary
        val stored = library.savedSource(card.id) ?: return@withContext PlayResult.Failed("This saved source was removed.")
        val source = Json.decodeFromString<WatchlistSource>(stored)
        val request = card.copy(id = source.catalogId, title = source.catalogTitle,
            season = source.season, episode = source.episode)
        val position = card.resumeFromMs ?: library.resumePosition(request.id, request.type, request.season, request.episode)
        val resumed = request.copy(resumeFromMs = position, watchlistId = card.id)
        renewSavedSource(source, resumed, generation, retrying)
    }

    private suspend fun renewSavedSource(
        source: WatchlistSource, request: PlayRequest, generation: Long, retrying: Boolean = false,
        resumePaused: Boolean = false,
    ): PlayResult {
        if (source.catalogId != request.id || source.season != request.season || source.episode != request.episode)
            return PlayResult.Failed("The saved source does not match this title or episode. Choose another source.")
        val owner = synchronized(this@DefaultPlayFlow) { preparationOwner }
        val library = client.activeLibrary
        coroutineContext.ensureActive()
        ensurePlaybackOwner(generation)
        synchronized(this@DefaultPlayFlow) {
            if (owner == null || preparationOwner !== owner) throw CancellationException("Preparation changed")
            _preparing.value = request
            _sourcesRequest.value = request
            _preparationLabel.value = "Finding saved source"
        }
        val services = awaitServices() ?: return PlayResult.Failed(NOT_PAIRED)
        val row = savedTorboxFile(request.id, request.title) ?: source.boundRow(request.title) ?: run {
            val lookup = request.id.substringAfter("::")
            if (services.meta.peek(request.type, lookup)?.hasInlineStreams == true) services.meta.invalidate(request.type, lookup)
            val meta = services.meta.meta(request.type, lookup)
            val expected = com.fourseveneightnine.tv.client.data.streams.StreamExpectation(
                meta?.title ?: request.title, meta?.year, request.season, request.episode, request.type == "movie",
                aliases = meta?.titleAliases.orEmpty() + request.title, canonicalID = request.id)
            val ids = StreamIds.candidates(request.type, request.id, meta, request.season, request.episode)
            val results = services.search.search(request.type, ids,
                inlineRows = meta?.streamsFor(request.season, request.episode).orEmpty(), expectation = expected)
            // Sources saves checked file facts. Compare the same admitted facts on replay.
            val state = services.search.validatedForDisplay(results, expected)
                .first { it.done || it.rows.any(source::matches) }
            state.rows.singleOrNull(source::matches)
        } ?: return PlayResult.Failed("This saved source is unavailable. Choose another source from the title.")
        ensurePlaybackOwner(generation)
        if (client.activeProfileId() == null || client.activeLibrary !== library) throw CancellationException("Profile changed")
        services.debrid.forget(row, request.season, request.episode)
        return open(row, request, generation, manualSelection = true, retrying = retrying,
            resumePaused = resumePaused, resumeRecipe = Json.encodeToString(source),
            confirmEpisodeStart = (request.resumeFromMs ?: 0L) > 0L)
    }

    override suspend fun restore(point: ResumePoint): PlayResult {
        val owner = graph.playback.generation
        val type = point.catalogType ?: return PlayResult.ShowList
        val id = point.catalogId ?: return PlayResult.ShowList
        val request = PlayRequest(type, id, point.title ?: "Movie or episode", point.season, point.episode,
            point.posterURL, point.artworkURL, point.positionMillis.takeIf { it > 0L })
        return coroutineScope {
            val preparation = beginPreparation(request, coroutineContext[Job])
            try {
                _sourcesRequest.value = request
                if (point.watchlistId != null) {
                    val restored = playSavedSource(request.copy(id = point.watchlistId), owner)
                    if (restored == PlayResult.Opened && point.paused && graph.playback.generation == owner + 1)
                        graph.playback.setSpeed(0)
                    restored
                } else if (!point.paused && request.season != null && request.episode != null &&
                    client.activeLibrary.pendingEpisodePreference(id, request.season, request.episode) != null) {
                    play(request, owner)
                } else resumeCatalogPoint(point, request, owner, point.paused)
            } finally { endPreparation(preparation) }
        }
    }

    /** A recent signed URL can expire before its TTL. Keep the episode and playhead on renewal. */
    private suspend fun resumeCatalogPoint(point: ResumePoint, request: PlayRequest, generation: Long, paused: Boolean): PlayResult {
        savedTorboxFile(request.id, request.title)?.let { file ->
            return open(file, request, generation, manualSelection = true, resumePaused = paused)
        }
        EinthusanSource.parse(point.url)?.let { source ->
            return open(source.row(request.title), request, generation, manualSelection = true, resumePaused = paused)
        }
        var owner = generation
        val recipe = point.sourceRecipe?.let { runCatching { Json.decodeFromString<WatchlistSource>(it) }.getOrNull() }
        if (ResumePolicy.linkIsRecent(point, System.currentTimeMillis())) {
            val row = StreamRow("resume", "Last played", request.title, point.catalogFile?.filename ?: request.title,
                url = point.url, headers = point.headers, filename = point.catalogFile?.filename).withFileFacts(point.catalogFile?.filename, point.catalogFile?.sizeBytes)
            val result = try {
                open(row, request, owner, manualSelection = true, resumePaused = paused,
                    resumeLinkSavedAtMillis = point.savedAtMillis, resumeRecipe = point.sourceRecipe,
                    confirmEpisodeStart = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { PlayResult.Failed(PlayPlanner.NO_ANSWER) }
            if (result == PlayResult.Opened) return result
            if (graph.playback.generation == owner + 1 &&
                _current.value == request && currentSource?.id == row.id) owner++
            ensurePlaybackOwner(owner)
        }
        ReceiverDiagnostics.record("source.resume.renew", "season=${request.season} episode=${request.episode} resumeMs=${request.resumeFromMs} exact=${recipe != null}")
        if (recipe == null) return PlayResult.Failed("The saved playback link is unavailable. Choose a source to resume at your saved position.")
        return renewSavedSource(recipe, request, owner, retrying = true, resumePaused = paused)
    }

    private suspend fun play(
        request: PlayRequest, expectedGeneration: Long?, retrying: Boolean = false,
        resumePaused: Boolean = false,
    ): PlayResult {
        _preparationLabel.value = "Preparing playback"
        val services = awaitServices() ?: return PlayResult.Failed(DefaultPlayFlow.NOT_PAIRED)
        coroutineContext.ensureActive()
        ensurePlaybackOwner(expectedGeneration)
        // "Sources" means the viewer wants to look. The Streams screen runs its own search, so
        // searching twice here would only cost the add-ons a second round of calls.
        if (request.forceList) return PlayResult.ShowList
        if (!retrying && !resumePaused && request.season != null && request.episode != null && expectedGeneration != null) {
            val profile = client.activeProfileId()
            val stored = client.activeLibrary.pendingEpisodePreference(request.id, request.season, request.episode)
            val preference = stored?.let { runCatching { Json.decodeFromString<EpisodePreference>(it) }.getOrNull() }
            if (profile != null && preference != null) {
                var owned: Long = expectedGeneration
                val deadline = android.os.SystemClock.elapsedRealtime() + HANDOFF_MS
                val result = withTimeoutOrNull(HANDOFF_MS) {
                    openContinuation(request, expectedGeneration, profile, continuationCandidates(request, preference), deadline = deadline) { owned = it }
                }
                return continuationResult(result, request, expectedGeneration, owned, profile)
            }
        }
        if (request.id.startsWith("saved-torbox:")) {
            val exact = savedTorboxFile(request.id, request.title)
                ?: return PlayResult.Failed("This saved TorBox file is unavailable.")
            return open(exact, request, expectedGeneration, manualSelection = true,
                retrying = retrying, resumePaused = resumePaused)
        }

        var pendingFavorite = if (retrying) null else client.favoriteSource(request)
        var unavailableFavorite = currentSource?.takeIf { retrying }?.let(FavoriteSource::from)
        var owner = expectedGeneration

        suspend fun tryOpening(row: StreamRow, favorite: Boolean): Boolean {
            if (favorite) _preparationLabel.value = "Opening favorite source"
            coroutineContext.ensureActive()
            ensurePlaybackOwner(owner)
            val before = graph.playback.generation
            val result = try {
                open(row, request, owner, manualSelection = favorite, retrying = retrying, fromFavorite = favorite, resumePaused = resumePaused)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { PlayResult.Failed(PlayPlanner.NO_ANSWER) }
            if (result == PlayResult.Opened) {
                if (!favorite) ReceiverDiagnostics.record("playflow.autopick", "addon=${row.addonName}")
                return true
            }
            if (owner != null && graph.playback.generation == before + 1 &&
                graph.playback.origin.value == PlaybackSession.Origin.Local &&
                _current.value == request && currentSource?.id == row.id) owner = before + 1
            ensurePlaybackOwner(owner)
            if (favorite) unavailableFavorite = FavoriteSource.from(row)
            return false
        }

        suspend fun tryBoundFavorite(): Boolean {
            val row = pendingFavorite?.exactRow(request.title) ?: return false
            pendingFavorite = null
            return tryOpening(row, favorite = true)
        }

        if (tryBoundFavorite()) return PlayResult.Opened
        val savedItem = try { client.resolveCatalogItem(request.type, request.id) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { return PlayResult.Failed(failure.message ?: "Tamil MV sources could not load. Retry.") }
        savedItem?.let { saved ->
            if (request.type == "movie" && request.id.startsWith("saved:tamilmv:")) {
                if (TamilMVCatalogRepository.movieBundleTitle(saved.title) != null)
                    return PlayResult.Failed("Open this bundle in TamilMV Collection and choose a movie file.")
                val movie = try {
                    saved.probeTamilMVMovie(services.debrid, checkAvailability = true)
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    return PlayResult.Failed(failure.message ?: "Could not check the TorBox cache. Try again.")
                }
                android.util.Log.d("4789TamilMV", "Movie cache selected=${movie?.infoHash?.take(8)} saved=${movie?.torboxTorrentID != null}")
                if (movie == null) return PlayResult.Failed(
                    if (saved.playbackSources.any { TamilMVCatalogRepository.printTier(it.filename) == 2 })
                        "A clean release is listed. TorBox has no playable file for it yet."
                    else "No playable file from this release is ready in TorBox.",
                )
                return open(movie, request, owner, retrying = retrying, resumePaused = resumePaused)
            }
            if (request.type == "series" && (request.season == null || request.episode == null) &&
                saved.playbackSources.singleOrNull()?.fileID == null)
                return PlayResult.Failed("Open this show and choose an episode or an actual file.")
            if (request.type == "series" && request.season != null && request.episode != null) {
                val exact = saved.episodeFiles(services.debrid).firstOrNull {
                    it.season == request.season && it.episode == request.episode
                } ?: return PlayResult.Failed("TorBox has no confirmed file for S${request.season} E${request.episode}.")
                return open(exact.stream, request, owner, manualSelection = true, retrying = retrying, resumePaused = resumePaused)
            }
            val row = saved.savedStream(request.season, request.episode)
                ?: return PlayResult.Failed(if (request.id.startsWith("saved:tamilmv:"))
                    "This release has no saved TorBox source. Refresh TamilMV Collection."
                else "The saved file is unavailable. Refresh Library on your iPhone, then sync this TV.")
            return open(row, request, owner, retrying = retrying, resumePaused = resumePaused)
        }
        if (request.id.startsWith("saved:tamilmv:topic:")) return PlayResult.Failed("This release is no longer in the current catalog. Refresh TamilMV Collection.")
        if (request.id.startsWith("saved:")) return PlayResult.Failed("This saved source is no longer available. Sync Library from your iPhone.")

        _preparationLabel.value = "Finding sources"
        val metadata = services.meta.meta(request.type, request.id.substringAfter("::"))
        if (pendingFavorite == null && unavailableFavorite == null && !retrying) {
            pendingFavorite = client.favoriteSource(request, metadata)
            if (tryBoundFavorite()) return PlayResult.Opened
        }
        val inline = metadata?.streamsFor(request.season, request.episode).orEmpty()
        // Play always tries the ranked best source. Sources is the explicit path to the list.
        val playRules = services.rules.let { rules ->
            if (rules.enabled) rules.copy(autoPlay = true)
            else com.fourseveneightnine.tv.client.data.streams.PlaybackRules.DEFAULT.copy(
                enabled = true, readyOnly = false, autoPlay = true,
            )
        }
        val expectation = com.fourseveneightnine.tv.client.data.streams.StreamExpectation(
            metadata?.title.orEmpty(), metadata?.year, request.season, request.episode, request.type == "movie",
            aliases = metadata?.titleAliases.orEmpty() + request.title, episodeYear = metadata?.videos?.firstOrNull { it.season == request.season && it.episode == request.episode }?.released?.take(4)?.toIntOrNull(), canonicalID = request.id)
        val planner = PlayPlanner(playRules, codecs(), metadata?.originalLanguage) { candidate ->
            prepareSource(candidate, request.type, expectation, services, automatic = true)
        }
        val ids = StreamIds.candidates(request.type, request.id, metadata, request.season, request.episode)
        val results = services.search.search(request.type, ids, inlineRows = inline, expectation = expectation)
        val choice = planner.chooseWithFavorite(
            services.search.validatedForDisplay(results, expectation),
            favorite = pendingFavorite,
            unavailable = unavailableFavorite,
            resumingEpisode = request.season != null && request.episode != null && (request.resumeFromMs ?: 0) > 0,
            openRanked = { tryOpening(it, favorite = false) },
            openFavorite = { tryOpening(it, favorite = true) },
        )
        return if (choice != null) PlayResult.Opened else PlayResult.ShowList
    }

    override suspend fun playRow(row: StreamRow, request: PlayRequest): PlayResult {
        val generation = graph.playback.generation
        return coroutineScope {
            val preparation = beginPreparation(request, coroutineContext[Job])
            try {
                _preparationLabel.value = "Preparing source"
                awaitServices() ?: return@coroutineScope PlayResult.Failed(DefaultPlayFlow.NOT_PAIRED)
                if (row.exactSource && row.torboxFileID == null && request.type == "series" &&
                    (request.season == null || request.episode == null)) return@coroutineScope PlayResult.Failed("Choose an actual file from this pack.")
                coroutineContext.ensureActive()
                ensurePlaybackOwner(generation)
                val current = _current.value
                val switchingCurrent = current?.let { it.type == request.type && it.id == request.id &&
                    it.season == request.season && it.episode == request.episode } == true &&
                    graph.playback.origin.value == PlaybackSession.Origin.Local && graph.playback.liveChannel.value == null
                val replacingShortSource = switchingCurrent && isUnexpectedShortSource(
                    graph.playback.snapshot.value.durationSeconds, expectedRuntimeMinutes,
                    request.type == "series" && (request.season ?: 0) > 0 && request.episode != null,
                )
                val resumed = when {
                    replacingShortSource -> request.copy(resumeFromMs = current?.resumeFromMs)
                    switchingCurrent -> request.copy(resumeFromMs = graph.playback.resumePositionMillis())
                    request.resumeFromMs == null -> request.copy(resumeFromMs = client.activeLibrary.resumePosition(
                        request.id, request.type, request.season, request.episode))
                    else -> request
                }
                open(row, resumed.copy(watchlistId = null), generation, manualSelection = true)

            } finally { endPreparation(preparation) }
        }.also {
            ReceiverDiagnostics.benchmark("play.result") { "result=${it::class.java.simpleName}" }
            if (it is PlayResult.Failed) reportSourceFailure(it)
        }
    }

    /** One bounded lookup per playing episode. Preparation must never replace the playing media. */
    override fun warmNextUp() {
        val session = graph.playback
        val plan = nextUp.value ?: return
        val previous = activeSource.value ?: return
        val owner = session.generation
        if (session.origin.value != PlaybackSession.Origin.Local) return
        if (warmedOwner == owner && warmedPlan == plan && warmedNext != null && warmedTrackVersion == session.trackChoiceVersion &&
            warmedTracks == session.episodeTracks) return
        cancelNextWarmup()
        warmedOwner = owner
        warmedPlan = plan
        warmedTrackVersion = session.trackChoiceVersion
        warmedTracks = session.episodeTracks
        val preference = EpisodePreference.playing(previous, session.episodeTracks, session.diagnosticsState.value)
        warmedNext = client.scope.async {
            withTimeoutOrNull(HANDOFF_MS) { continuationCandidates(plan.request(), preference, plan.release) }
                ?: EpisodeCandidates(emptyList(), preference, complete = false)
        }
    }

    /** A catalog refresh may drop or replace a visible release while its video still plays. */
    private suspend fun continuationRelease(request: PlayRequest, retained: CatalogItem?): CatalogItem? {
        val current = try { client.resolveCatalogItem(request.type, request.id) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { if (retained == null) throw failure else null }
        if (retained == null) return current
        if (current == null || current.playbackSources == retained.playbackSources) return retained
        return current.copy(playbackSources = (retained.playbackSources + current.playbackSources).distinct())
    }

    private suspend fun continuationCandidates(request: PlayRequest, preference: EpisodePreference,
        retainedRelease: CatalogItem? = null, excluded: Set<String> = emptySet()): EpisodeCandidates = withContext(Dispatchers.IO) {
        val preferredUntil = android.os.SystemClock.elapsedRealtime() + if (excluded.isEmpty()) PREFERRED_MATCH_MS else 0L
        val empty = EpisodeCandidates(emptyList(), preference)
        val services = awaitServices() ?: return@withContext empty
        if (request.id.startsWith("saved:")) {
            val release = continuationRelease(request, retainedRelease) ?: return@withContext empty
            val files = release.episodeFiles(services.debrid, allCopies = true)
                .filter { it.season == request.season && it.episode == request.episode && it.cached }.map { it.stream }
            val preferred = preference.preferred(files.filterNot { it.playbackKey() in excluded }).take(5)
            preferred.firstOrNull()?.let { row ->
                withTimeoutOrNull(8_000L) { services.debrid.resolve(row, request.season, request.episode) }
            }
            return@withContext EpisodeCandidates(preferred, preference)
        }
        val meta = services.meta.meta(request.type, request.id.substringAfter("::"))
        val expected = StreamExpectation(meta?.title ?: request.title, meta?.year, request.season, request.episode, false,
            aliases = meta?.titleAliases.orEmpty() + request.title, episodeYear = meta?.videos?.firstOrNull {
                it.season == request.season && it.episode == request.episode
            }?.released?.take(4)?.toIntOrNull(), canonicalID = request.id)
        val ids = StreamIds.candidates(request.type, request.id, meta, request.season, request.episode)
        val results = services.search.search(request.type, ids, inlineRows = meta?.streamsFor(request.season, request.episode).orEmpty(),
            expectation = expected)
        val rules = services.rules.takeIf { it.enabled } ?: PlaybackRules.DEFAULT.copy(enabled = true, readyOnly = false)
        val language = preference.languages.firstOrNull() ?: meta?.originalLanguage
        val hardware = codecs()
        val inspected = mutableSetOf<String>()
        val fallbackWindow = flow {
            val left = preferredUntil - android.os.SystemClock.elapsedRealtime()
            if (left > 0) { emit(false); delay(left) }
            emit(true)
        }
        combine(services.search.validatedForDisplay(results, expected), fallbackWindow) { state, fallback -> state to fallback }
            .conflate().mapNotNull { (state, fallback) ->
                val eligible = StreamRanker.rank(state.rows.filter { it.readyHint }, rules, hardware)
                    .filter { it.eligible && it.decodeCompatible && it.cautions.isEmpty() && it.row.identityVerified == true }
                    .map { it.row }.filterNot { excluded.isNotEmpty() && it.playbackKey() in excluded }
                val (matchingAudio, otherAudio) = eligible.partition { preference.acceptsLanguage(it, language) }
                val ranked = preference.preferred(matchingAudio) + if (fallback || state.done) {
                    preference.preferred(otherAudio.filter { OriginalAudioLanguage.matches(it, meta?.originalLanguage) })
                } else emptyList()
                if (state.done) return@mapNotNull EpisodeCandidates(ranked.take(5), preference)
                val candidates = ranked.filter { it.id !in inspected && (fallback ||
                    (it.cachedDebrid && preference.earlyMatch(it))) }.take(5)
                for (row in candidates) {
                    val budget = if (fallback) MEDIA_PROBE_MS else minOf(MEDIA_PROBE_MS, preferredUntil - android.os.SystemClock.elapsedRealtime())
                    if (budget <= 0) break
                    inspected += row.id
                    val verified = try {
                        withTimeoutOrNull(budget) { services.debrid.inspect(row, expected, strictName = true) }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                    if (verified != true) {
                        if (verified == null && !fallback) inspected.remove(row.id)
                        continue
                    }
                    ReceiverDiagnostics.record("source.nextup.ready", "season=${request.season} episode=${request.episode} quality=${row.quality} fallback=$fallback")
                    return@mapNotNull EpisodeCandidates((listOf(row) + ranked.filterNot { it.id == row.id }).take(5), preference, complete = false)
                }
                null
        }.firstOrNull() ?: empty
    }

    override suspend fun playNextUp(): PlayResult = withContext(Dispatchers.Main.immediate) {
        val session = graph.playback
        val initialOwner = session.generation
        val profile = client.activeProfileId() ?: return@withContext PlayResult.ShowList
        val plan = _nextUp.value ?: return@withContext PlayResult.ShowList
        val request = plan.request()
        ReceiverDiagnostics.record("nextup.request", "season=${plan.season} episode=${plan.episode}")
        _sourcesRequest.value = request
        var owned = initialOwner
        val deadline = android.os.SystemClock.elapsedRealtime() + HANDOFF_MS
        val result = withTimeoutOrNull(HANDOFF_MS) {
            session.refreshTracks()
            ensurePlaybackOwner(initialOwner)
            _preparationLabel.value = "Finding S${plan.season} E${plan.episode}"
            val preparedEarlier = warmedOwner == initialOwner && warmedPlan == plan && warmedNext?.isCompleted == true
            warmNextUp()
            val pending = warmedNext ?: return@withTimeoutOrNull PlayResult.ShowList
            warmedNext = null
            try {
                openContinuation(request, initialOwner, profile, pending.await(), plan.release, renewOnFailure = preparedEarlier, deadline = deadline) { owned = it }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { PlayResult.ShowList }
            finally { pending.cancel() }
        }
        continuationResult(result, request, initialOwner, owned, profile)
    }

    private suspend fun continuationResult(result: PlayResult?, request: PlayRequest, initialOwner: Long, owned: Long,
        profile: java.util.UUID): PlayResult {
        val session = graph.playback
        if (result == PlayResult.Opened) return result
        coroutineContext.ensureActive()
        if (client.activeProfileId() != profile || session.origin.value == PlaybackSession.Origin.Phone)
            throw CancellationException("Playback changed")
        if (session.generation != initialOwner && session.generation != owned) throw CancellationException("Playback replaced")
        if (session.generation != initialOwner && _current.value == request && _activeSource.value != null && session.phase.value in setOf(ReceiverPlaybackPhase.Playing, ReceiverPlaybackPhase.Paused))
            return PlayResult.Opened
        if (session.generation != initialOwner && session.origin.value == PlaybackSession.Origin.Local && _activeSource.value == null) {
            session.stop()
        }
        _sourcesRequest.value = request
        ReceiverDiagnostics.record("nextup.sources", "season=${request.season} episode=${request.episode} timeout=${result == null}")
        return PlayResult.ShowList
    }

    private suspend fun openContinuation(request: PlayRequest, initialOwner: Long, profile: java.util.UUID,
        batch: EpisodeCandidates, retainedRelease: CatalogItem? = null, renewOnFailure: Boolean = false,
        deadline: Long, onOwnedGeneration: (Long) -> Unit): PlayResult = withContext(Dispatchers.Main.immediate) {
        val rows = batch.rows
        val session = graph.playback
        var owner = initialOwner
        ReceiverDiagnostics.record("nextup.candidates", "season=${request.season} episode=${request.episode} count=${rows.size}")
        // A provider may rotate a signed link between preview and EOF. Renew a bound file once.
        val attempts = if (rows.firstOrNull()?.exactSource == true) listOf(rows.first()) + rows else rows
        for ((index, row) in attempts.withIndex()) {
            if (client.activeProfileId() != profile) throw CancellationException("Profile changed")
            if (index == 1 && row === attempts.first()) client.services.value?.debrid?.forget(row, request.season, request.episode)
            coroutineContext.ensureActive()
            ensurePlaybackOwner(owner)
            var engineOwner: Long? = null
            val verifyBy = minOf(deadline, android.os.SystemClock.elapsedRealtime() + SOURCE_OPEN_MS)
            val result = try {
                withTimeoutOrNull(SOURCE_OPEN_MS) {
                    open(row, request, owner, manualSelection = true, confirmEpisodeStart = true,
                        retainedRelease = retainedRelease, episodePreference = batch.preference, verifyBy = verifyBy, onOpeningGeneration = { generation ->
                            engineOwner = generation
                            onOwnedGeneration(generation)
                        })
                } ?: if (session.generation == owner + 1 && _current.value == request && _activeSource.value?.id == row.id &&
                    session.phase.value in setOf(ReceiverPlaybackPhase.Playing, ReceiverPlaybackPhase.Paused)) PlayResult.Opened else PlayResult.Failed(PlayPlanner.NO_ANSWER)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { PlayResult.Failed(PlayPlanner.NO_ANSWER) }
            if (result == PlayResult.Opened) {
                ReceiverDiagnostics.record("nextup.playing", "season=${request.season} episode=${request.episode} attempt=${index + 1}")
                return@withContext result
            }
            val reportedOwner = (result as? PlayResult.Failed)?.acceptedGeneration
            val failedOwner = reportedOwner ?: engineOwner
            if (failedOwner == owner + 1 && session.generation == failedOwner &&
                _current.value == request && currentSource?.id == row.id) {
                if (reportedOwner == null && session.origin.value == PlaybackSession.Origin.Local && _activeSource.value == null)
                    session.discardUnverifiedLocalOpen(failedOwner)
                owner = failedOwner
                onOwnedGeneration(owner)
            }
            ensurePlaybackOwner(owner)
        }
        if (!batch.complete || renewOnFailure) {
            ensurePlaybackOwner(owner)
            if (client.activeProfileId() != profile) throw CancellationException("Profile changed")
            val tried = attempts.mapTo(hashSetOf()) { it.playbackKey() }
            val fallback = continuationCandidates(request, batch.preference, retainedRelease, excluded = tried)
            if (fallback.rows.isNotEmpty()) return@withContext openContinuation(request, owner, profile, fallback, retainedRelease, deadline = deadline, onOwnedGeneration = onOwnedGeneration)
        }
        ReceiverDiagnostics.record("nextup.unavailable", "season=${request.season} episode=${request.episode} tried=${attempts.size}")
        if (rows.isNotEmpty() && session.generation != initialOwner) PlayResult.ShowList
        else PlayResult.Failed("Couldn’t play S${request.season} E${request.episode}. Choose a source for this episode.")
    }

    override suspend fun retryCurrent(positionMillis: Long, automatic: Boolean): PlayResult =
        withContext(Dispatchers.Main.immediate) {
            val expectedGeneration = graph.playback.generation
            val request = _current.value ?: return@withContext PlayResult.ShowList
            if (automatic && !canRecoverSource) return@withContext PlayResult.ShowList
            // A reopen can fail after it advances the engine generation. Keep this budget across
            // retries of the same playback so a failing server cannot cause a reconnect loop.
            recoveredSource = true
            coroutineScope {
                val preparation = beginPreparation(request, coroutineContext[Job])
                try {
                    val services = awaitServices() ?: return@coroutineScope PlayResult.Failed(NOT_PAIRED)
                    coroutineContext.ensureActive()
                    ensurePlaybackOwner(expectedGeneration)
                    val resumed = request.copy(resumeFromMs = positionMillis.coerceAtLeast(0L), forceList = false)
                    request.watchlistId?.let { return@coroutineScope playSavedSource(resumed.copy(id = it), expectedGeneration, retrying = true) }
                    val source = currentSource
                    val fromFavorite = currentFromFavorite
                    if (source != null && EinthusanSource.parse(source.url) != null) {
                        return@coroutineScope open(source, resumed, expectedGeneration, manualSelection = true, retrying = true)
                    }
                    if (automatic && source != null) services.debrid.notePlaybackFailure(source, request.season, request.episode)
                    if (source != null && (source.exactSource || (!automatic && source.needsResolve))) {
                        services.debrid.forget(source, request.season, request.episode)
                        val renewed = open(source, resumed, expectedGeneration, manualSelection = true, retrying = true,
                            fromFavorite = fromFavorite)
                        if (renewed == PlayResult.Opened || (source.exactSource && !fromFavorite) ||
                            graph.playback.generation != expectedGeneration) return@coroutineScope renewed
                    }
                    val lookupId = request.id.substringAfter("::")
                    if (services.meta.peek(request.type, lookupId)?.hasInlineStreams == true) {
                        services.meta.invalidate(request.type, lookupId)
                    }
                    play(resumed, expectedGeneration, retrying = true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    PlayResult.Failed(PlayPlanner.NO_ANSWER)
                } finally {
                    endPreparation(preparation, clearLabel = graph.playback.generation == expectedGeneration)
                }
            }
        }

    /** Resolve one row and hand it to the player. The URL goes nowhere else. */
    private suspend fun prepareSource(
        row: StreamRow, type: String,
        expected: com.fourseveneightnine.tv.client.data.streams.StreamExpectation,
        services: com.fourseveneightnine.tv.client.ClientGraph.DataServices,
        automatic: Boolean,
    ): StreamRow? {
        if (automatic && !row.readyHint) return null
        val bound = row.copy(expectation = expected)
        val httpSource = bound.httpSource && !bound.exactSource
        val strict = automatic || httpSource || row.identityVerified == true
        val timeout = if (automatic) com.fourseveneightnine.tv.client.data.streams.debrid.DebridResolver.MEDIA_INSPECTION_TIMEOUT_MILLIS
            else com.fourseveneightnine.tv.client.data.streams.debrid.DebridResolver.MANUAL_MEDIA_INSPECTION_TIMEOUT_MILLIS
        val checked = services.debrid.inspect(bound, expected, strictName = strict, timeoutMillis = timeout)
        if (checked == true) return bound.copy(linkCheckedAtMillis = System.currentTimeMillis())
        if (!automatic && !httpSource && checked == null) return bound
        if (checked == false && !row.exactSource) {
            _preparationLabel.value = "Refreshing source"
            val fresh = try {
                services.search.refresh(row, type, expected,
                    timeoutMillis = if (automatic) 4_000L else 20_000L)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            if (fresh != null && !fresh.knownUncached && (fresh.readyHint || fresh.requiresDmmValidation)) {
                services.debrid.forget(fresh, expected.season, expected.episode)
                val freshHttpSource = fresh.httpSource && !fresh.exactSource
                val freshStrict = automatic || freshHttpSource || fresh.identityVerified == true
                val result = services.debrid.inspect(fresh, expected, strictName = freshStrict, timeoutMillis = timeout)
                if (result == true) return fresh.copy(expectation = expected, linkCheckedAtMillis = System.currentTimeMillis())
                if (!automatic && !freshHttpSource && result == null) return fresh.copy(expectation = expected)
            }
        }
        return null
    }

    private suspend fun open(
        selected: StreamRow,
        request: PlayRequest,
        expectedGeneration: Long? = null,
        manualSelection: Boolean = false,
        resumePaused: Boolean = false,
        resumeLinkSavedAtMillis: Long? = null,
        resumeRecipe: String? = null,
        retrying: Boolean = false,
        fromFavorite: Boolean = false,
        confirmEpisodeStart: Boolean = false,
        retainedRelease: CatalogItem? = null,
        episodePreference: EpisodePreference? = null,
        verifyBy: Long? = null,
        onOpeningGeneration: ((Long) -> Unit)? = null,
    ): PlayResult {
        var row = selected
        val einthusan = EinthusanSource.parse(row.url)
        if (einthusan != null && (!EinthusanProvider.available || !manualSelection ||
                request.type != "movie" || request.season != null || request.episode != null)) {
            return PlayResult.ShowList
        }
        val attempt = System.nanoTime()
        val diagnosticExpectation = com.fourseveneightnine.tv.client.data.streams.StreamExpectation(
            request.title, null, request.season, request.episode, request.type == "movie")
        fun trace(stage: String, detail: String) {
            ReceiverDiagnostics.benchmark("play.source.$stage") {
                "attempt=$attempt elapsedMs=${(System.nanoTime() - attempt) / 1_000_000} $detail"
            }
            ReceiverDiagnostics.record("source.$stage",
                sourceDiagnosticDetails(row, diagnosticExpectation, "attempt=$attempt $detail"))
        }
        trace("selected", "manual=$manualSelection retry=$retrying")
        val services = awaitServices() ?: run {
            trace("failed", "stage=settings reason=not_paired")
            return PlayResult.Failed(DefaultPlayFlow.NOT_PAIRED)
        }
        val libraryFile = row.exactSource && row.torboxFileID != null && row.id == request.id &&
            request.id.startsWith("saved-torbox:")
        val saved = if (libraryFile) null else retainedRelease ?: client.savedCatalogItem(request.type, request.id)
        val metadataID = saved?.imdbId ?: saved?.tmdbId?.let { "tmdb:$it" } ?: request.id
        // A selected TamilMV release is already bound to a TorBox hash. The add-on
        // language admission rule needs reported audio labels, which these files may lack.
        val directTamilMV = row.exactSource && request.id.startsWith("saved:tamilmv:")
        val meta = when {
            libraryFile -> null
            directTamilMV && metadataID == request.id -> null
            manualSelection -> services.meta.peek(request.type, metadataID.substringAfter("::"))
                ?: if (row.httpSource && !row.exactSource) services.meta.meta(request.type, metadataID.substringAfter("::")) else null
            else -> services.meta.meta(request.type, metadataID.substringAfter("::"))
        }
        coroutineContext.ensureActive()
        ensurePlaybackOwner(expectedGeneration)
        if (!manualSelection && !directTamilMV && !libraryFile && !OriginalAudioLanguage.matches(row, meta?.originalLanguage)) {
            trace("failed", "stage=admission reason=original_audio_unverified")
            return PlayResult.Failed(meta?.originalLanguage?.let {
                "This source does not report ${OriginalAudioLanguage.displayName(it)} original audio. Choose another source."
            } ?: "The title's original language could not be identified. Try again.")
        }
        row = row.copy(torboxLibraryFile = libraryFile,
            expectation = if (libraryFile) null else com.fourseveneightnine.tv.client.data.streams.StreamExpectation(
            meta?.title ?: request.title, meta?.year ?: saved?.metadataYear ?: saved?.year,
            request.season, request.episode, request.type == "movie",
            aliases = meta?.titleAliases.orEmpty() + request.title,
            episodeYear = meta?.videos?.firstOrNull { it.season == request.season && it.episode == request.episode }?.released?.take(4)?.toIntOrNull(),
            canonicalID = request.id, verifiedReleaseYear = saved?.year.takeIf { directTamilMV && saved?.metadataYear != null }))
        _preparationLabel.value = if (row.needsResolve) "Resolving source" else "Opening source"
        val resolved = if (einthusan != null) {
            _preparationLabel.value = "Opening Einthusan"
            val playback = try {
                client.einthusan.resolve(einthusan, EinthusanAccount.from(services.document))
            } catch (failure: EinthusanFailure) {
                return PlayResult.Failed(failure.message ?: "Einthusan could not open this movie.")
            }
            if (client.services.value !== services) return PlayResult.Failed("Einthusan settings changed. Choose the source again.")
            playback
        } else {
            var outcome = services.debrid.resolve(row, request.season, request.episode,
                selectLargestVideo = directTamilMV && request.type == "movie" && row.torboxFileID == null)
            coroutineContext.ensureActive()
            ensurePlaybackOwner(expectedGeneration)
            trace("resolve", "result=${outcome::class.java.simpleName}")
            PlayPlanner.failure(outcome)?.let {
                trace("failed", "stage=resolve reason=${outcome::class.java.simpleName}")
                return it
            }
            if (!directTamilMV && !libraryFile) {
                val expected = com.fourseveneightnine.tv.client.data.streams.StreamExpectation(
                    meta?.title ?: request.title, meta?.year ?: saved?.year, request.season, request.episode, request.type == "movie",
                    aliases = meta?.titleAliases.orEmpty() + request.title, episodeYear = meta?.videos?.firstOrNull { it.season == request.season && it.episode == request.episode }?.released?.take(4)?.toIntOrNull(), canonicalID = request.id)
                row = prepareSource(row, request.type, expected, services, automatic = false)
                    ?: run {
                        trace("failed", "stage=inspect reason=video_not_verified")
                        return PlayResult.Failed("This link did not serve the selected video. Search again or choose another source.")
                    }
                coroutineContext.ensureActive()
                ensurePlaybackOwner(expectedGeneration)
                outcome = services.debrid.resolve(row, request.season, request.episode)
                coroutineContext.ensureActive()
                ensurePlaybackOwner(expectedGeneration)
                PlayPlanner.failure(outcome)?.let {
                    trace("failed", "stage=resolve_after_inspect reason=${outcome::class.java.simpleName}")
                    return it
                }
            }
            (outcome as DebridOutcome.Success).resolved
        }
        val bookmarkBase = if (selected.requiresDmmValidation) row.withFileFacts(
            resolved.filename?.substringAfterLast('/')?.takeUnless {
                com.fourseveneightnine.tv.client.data.streams.StreamExpectation.genericFilename(it)
            } ?: row.filename, resolved.sizeBytes,
        ) else row
        val bookmarkRow = if (resolved.torrentIndex != null || resolved.torboxFileId != null) {
            bookmarkBase.copy(infoHash = bookmarkBase.infoHash ?: bookmarkBase.cacheHash,
                fileIdx = resolved.torrentIndex ?: bookmarkBase.fileIdx,
                torboxFileID = resolved.torboxFileId ?: bookmarkBase.torboxFileID)
        } else if (selected.requiresDmmValidation) bookmarkBase else selected

        return withContext(Dispatchers.Main.immediate) {
            coroutineContext.ensureActive()
            ensurePlaybackOwner(expectedGeneration)
            try { captureStopProgress() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { ReceiverDiagnostics.record("progress.write.failed", "reason=replace") }
            ensurePlaybackOwner(expectedGeneration)
            progressJob?.cancel()
            progressRecorder = null
            playingBookmark.value = null
            cancelNextWarmup()
            _activeSource.value = null
            metadataJob?.cancel()
            val generation = ++metadataGeneration
            val plan = if (request.type == "series" && request.id.startsWith("saved:")) null else NextUp.compute(meta?.copy(id = request.id), request.season, request.episode)
            _nextUp.value = null

            val title = displayTitleOrNull(request.title, request.id) ?: meta?.let { displayTitleOrNull(it.title, it.id) }
                ?: request.title
            val poster = request.posterUrl?.takeIf(String::isNotBlank) ?: meta?.poster
            val backdrop = request.backdropUrl?.takeIf(String::isNotBlank) ?: meta?.backdrop
            // The Opening and Buffering screens read this; `OpenMediaRequest` carries no artwork.
            NowPlayingArtwork.stage(landscapeURL = backdrop, posterURL = poster, logoURL = meta?.logo, owner = title)

            // Capture ownership before the engine opens. Profile switching is blocked for this local
            // run, and this stable reference also guarantees that lifecycle locking cannot redirect a
            // late pause/stop write into a different household member's library.
            val playbackLibrary = client.activeLibrary

            expectedRuntimeMinutes = meta?.runtimeMinutes
            _current.value = request
            _sourcesRequest.value = request
            currentSource = row
            currentFromFavorite = fromFavorite
            if (!retrying) recoveredSource = false
            _preparationLabel.value = "Opening video"
            val openingGeneration = graph.playback.generation + 1
            trace("player", "stage=open generation=$openingGeneration")
            onOpeningGeneration?.invoke(openingGeneration)
            val opened = graph.playback.open(
                OpenMediaRequest(
                    url = resolved.url,
                    title = title,
                    subtitle = episodeSubtitle(meta, request.season, request.episode),
                    headers = resolved.headers,
                    startPositionMs = request.resumeFromMs?.takeIf { it > 0L },
                    resumeSourceReference = einthusan?.reference,
                ),
                PlaybackSession.Origin.Local,
                originalAudioLanguage = meta?.originalLanguage,
                catalogRequest = request, profileId = client.activeProfileId()?.toString(),
                resumePaused = resumePaused, resumeLinkSavedAtMillis = resumeLinkSavedAtMillis,
                episodePreference = episodePreference,
                subtitleVideoHash = row.facts.videoHash,
                sourceRecipe = if (einthusan == null) resumeRecipe
                    ?: WatchlistSource.from(request, bookmarkRow)?.let { Json.encodeToString(it) } else null,
                catalogFile = ResumePoint.CatalogFile(
                    filename = (row.filename ?: row.releaseName).substringBefore("|^|").substringBefore('?').substringAfterLast('/').takeIf {
                        it.length in 1..1024 && row.expectation?.matches(row.copy(filename = it)) == true
                    },
                    sizeBytes = resolved.sizeBytes?.takeIf { it > 0 } ?: row.sizeBytes?.takeIf { it > 0 },
                ),
            )
            ensurePlaybackOwner(openingGeneration)
            trace("player", "stage=result generation=$openingGeneration accepted=${opened.isSuccess}")
            if (opened.isFailure) {
                if (einthusan == null) services.debrid.notePlaybackFailure(row, request.season, request.episode)
                return@withContext PlayResult.Failed(PlayPlanner.NO_ANSWER, openingGeneration)
            }

            // Engine acceptance outlives the caller. Verify and finish this run together;
            // explicit generation checks still retire work after a newer playback request.
            val accepted = withContext(NonCancellable) finalize@ {
                ensurePlaybackOwner(openingGeneration)
                if (confirmEpisodeStart && !awaitEpisodeStart(openingGeneration, meta?.runtimeMinutes, verifyBy)) {
                    services.debrid.notePlaybackFailure(row, request.season, request.episode)
                    val discarded = graph.playback.discardUnverifiedLocalOpen(openingGeneration)
                    ensurePlaybackOwner(openingGeneration)
                    discarded.exceptionOrNull()?.let { failure ->
                        if (failure is CancellationException) throw failure
                        ReceiverDiagnostics.record("playback.discard.failed", failure.javaClass.simpleName)
                    }
                    // Keep this rejected attempt's accepted generation in the failure below.
                    return@finalize false
                }
                _nextUp.value = plan
                playingBookmark.value = (if (einthusan == null) resumeRecipe?.let { Json.decodeFromString<WatchlistSource>(it) }
                    ?: WatchlistSource.from(request, bookmarkRow) else null)?.let { source ->
                    PlayingBookmark(playbackLibrary, request.watchlistId ?: source.cardId(request.type), source)
                }
                if (!confirmEpisodeStart) _activeSource.value = row
                if (confirmEpisodeStart) {
                    // Install the new recorder before Room can suspend or Stop can retire this run.
                    startProgress(request, playbackLibrary, openingGeneration, meta?.runtimeMinutes)
                    val sample = graph.playback.snapshot.value
                    try {
                        progressRecorder?.record((sample.positionSeconds * 1000).toLong(),
                            (sample.durationSeconds * 1000).toLong(), ProgressRecorder.Reason.Tick)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { ReceiverDiagnostics.record("progress.write.failed", "reason=nextup") }
                    ensurePlaybackOwner(openingGeneration)
                    _activeSource.value = row
                }
                try {
                    playbackLibrary.recordLocalPlay(
                        canonicalId = request.id,
                        mediaType = request.type,
                        title = title,
                        posterUrl = poster,
                        backdropUrl = backdrop,
                        season = request.season,
                        episode = request.episode,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A history write may fail without invalidating already-open video.
                }
                ensurePlaybackOwner(openingGeneration)
                metadataJob = client.scope.launch {
                    val savedForNext = client.savedCatalogItem(request.type, request.id) ?: saved
                    val metadataID = savedForNext?.imdbId ?: savedForNext?.tmdbId?.let { "tmdb:$it" } ?: request.id
                    val enriched = try { if (metadataID.startsWith("saved:")) null else services.meta.meta(request.type, metadataID.substringAfter("::"))?.copy(id = request.id) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                    val successor = if (request.type == "series" && request.id.startsWith("saved:")) {
                        val release = try { continuationRelease(request, retainedRelease ?: savedForNext) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { savedForNext }
                        release?.let {
                            val inventory = try { it.episodeFiles(services.debrid, includeDeclared = true) }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { tamilMVEpisodeFiles(it, emptyMap(), includeDeclared = true) }
                            NextUp.fromRelease(request, enriched, inventory)?.copy(release = it)
                        }
                    } else NextUp.compute(enriched ?: meta, request.season, request.episode)
                    withContext(Dispatchers.Main) {
                        if (generation != metadataGeneration || _current.value != request ||
                            graph.playback.origin.value != PlaybackSession.Origin.Local ||
                            graph.playback.lastOpenMedia()?.url != resolved.url) return@withContext
                        _nextUp.value = successor
                        if (successor != null && request.season != null && request.episode != null) {
                            playbackLibrary.updateWatchedSuccessor(request.id, request.season, request.episode, successor.season, successor.episode)
                        }
                        if (enriched != null) NowPlayingArtwork.update(
                            generation = openingGeneration,
                            landscapeURL = backdrop ?: enriched.backdrop,
                            posterURL = poster ?: enriched.poster, logoURL = enriched.logo)
                    }
                    if (enriched != null) try {
                        playbackLibrary.fillRecentMetadata(request.id, enriched)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Display metadata is optional for a running player.
                    }
                }
                if (!confirmEpisodeStart) startProgress(request, playbackLibrary, openingGeneration, meta?.runtimeMinutes)
                true
            }
            coroutineContext.ensureActive()
            if (!accepted) return@withContext PlayResult.Failed(PlayPlanner.NO_ANSWER, openingGeneration)
            _preparationLabel.value = null
            PlayResult.Opened
        }
    }

    private suspend fun awaitEpisodeStart(owner: Long, runtimeMinutes: Int?, verifyBy: Long?): Boolean {
        val session = graph.playback
        var firstPosition: Double? = null
        val budget = verifyBy?.let { (it - android.os.SystemClock.elapsedRealtime()).coerceIn(0L, 15_000L) } ?: RESUME_START_MS
        return withTimeoutOrNull(budget) {
            var started = false
            while (!started) {
                ensurePlaybackOwner(owner)
                when (session.phase.value) {
                    is ReceiverPlaybackPhase.Error, ReceiverPlaybackPhase.Ended, ReceiverPlaybackPhase.Stopped -> return@withTimeoutOrNull false
                    ReceiverPlaybackPhase.Playing, ReceiverPlaybackPhase.Paused -> {
                        val sample = session.refreshSnapshot()
                        ensurePlaybackOwner(owner)
                        if (isUnexpectedShortSource(sample.durationSeconds, runtimeMinutes, true)) return@withTimeoutOrNull false
                        val prior = firstPosition
                        val video = session.diagnosticsState.value
                        val pictureReady = session.videoAspectRatio.value > 0f || (video.width > 0 && video.height > 0)
                        val paused = session.phase.value == ReceiverPlaybackPhase.Paused && sample.speed == 0
                        started = sample.active && pictureReady && (paused || (prior != null && sample.positionSeconds > prior))
                        if (prior == null) firstPosition = sample.positionSeconds
                    }
                    else -> Unit
                }
                if (!started) delay(200)
            }
            true
        } == true
    }

    private fun ensurePlaybackOwner(expectedGeneration: Long?) {
        if (expectedGeneration != null && graph.playback.generation != expectedGeneration) {
            throw CancellationException("Playback was replaced")
        }
    }

    /** One recorder per title. A switch cancels the last run rather than letting two rows race. */
    private fun startProgress(
        request: PlayRequest, playbackLibrary: LibraryRepository, generation: Long, runtimeMinutes: Int?,
    ) {
        progressJob?.cancel()
        val recorder = ProgressRecorder(LibraryProgressSink(playbackLibrary, preference = {
            _activeSource.value?.takeIf { request.type == "series" && progressGeneration == generation }?.let {
                Json.encodeToString(EpisodePreference.playing(it, graph.playback.episodeTracks, graph.playback.diagnosticsState.value))
            }
        }) { _nextUp.value.takeIf { progressGeneration == generation } })
        recorder.begin(ProgressTarget(request.id, request.type, request.season, request.episode), runtimeMinutes)
        progressRecorder = recorder
        progressGeneration = generation
        progressJob = client.scope.launch(Dispatchers.Main.immediate) {
            recorder.follow(
                session = graph.playback,
                target = ProgressTarget(request.id, request.type, request.season, request.episode),
                generation = generation,
            )
        }
    }

    /** "S2 E4 · Honeydew" for an episode, the title's own subtitle for a film, else null. */
    private fun episodeSubtitle(meta: Meta?, season: Int?, episode: Int?): String? {
        if (season == null || episode == null) return null
        val title = meta?.videos
            ?.firstOrNull { it.season == season && it.episode == episode }
            ?.title
        return NextUp.subtitle(season, episode, title).takeIf(String::isNotEmpty)
    }

    private suspend fun awaitServices(): ClientGraph.DataServices? =
        client.services.value ?: withTimeoutOrNull(servicesTimeoutMillis) {
            client.services.filterNotNull().first()
        }

    companion object {
        private const val PREFERRED_MATCH_MS = 3_000L
        private const val MEDIA_PROBE_MS = 4_000L
        private const val SOURCE_OPEN_MS = 10_000L
        private const val RESUME_START_MS = 30_000L
        private const val HANDOFF_MS = 30_000L
        const val SERVICES_TIMEOUT_MILLIS: Long = 3_000L

        /** `client-data/API-A.md` §"Press Play". It names the one thing the viewer can do. */
        const val NOT_PAIRED = "Pair your iPhone first."
    }
}

/** Only sources attached to this saved item are eligible. No title search or provider substitution. */
internal fun CatalogItem.savedStream(season: Int?, episode: Int?): StreamRow? {
    val source = playbackSources.firstOrNull { source ->
        (source.season == null || season == null || source.season == season) &&
            (source.episodes.isEmpty() || episode == null || episode in source.episodes)
    } ?: return null
    return StreamRow(id = "saved:$canonicalId:${source.infohash}:${source.fileID}", addonName = "Saved TorBox source",
        title = title, releaseName = source.filename, infoHash = source.infohash,
        torboxTorrentID = source.torrentID, torboxFileID = source.fileID, exactSource = true,
        v2Infohash = source.v2Infohash)
}

internal fun CatalogItem.torboxV2Hashes(): Map<String, String> =
    playbackSources.mapNotNull { source ->
        source.v2Infohash?.let { source.infohash.lowercase() to it }
    }.toMap()

/** The catalog supplies the order. TorBox must confirm the exact hash and a playable file. */
internal fun CatalogItem.cachedTamilMVMovie(
    filesByHash: Map<String, List<DebridFile>>,
    libraryByHash: Map<String, List<TorboxLibraryTorrent>> = emptyMap(),
): StreamRow? {
    for (source in preferredTamilMVMovieSources()) {
        fun video(files: List<DebridFile>) = files.asSequence()
            .filter { it.id != null && (source.fileID == null || it.id == source.fileID) && it.isPlayableVideo() }
            .maxByOrNull { it.sizeBytes ?: 0L }
        val hash = source.infohash.lowercase()
        val saved = libraryByHash[hash].orEmpty().firstNotNullOfOrNull { torrent ->
            video(torrent.files)?.let { file -> torrent.id to file }
        }
        val file = saved?.second ?: video(filesByHash[hash].orEmpty()) ?: continue
        return copy(playbackSources = listOf(source)).savedStream(null, null)
            ?.copy(torboxTorrentID = saved?.first ?: source.torrentID, torboxFileID = file.id)
    }
    return null
}

internal suspend fun CatalogItem.probeTamilMVMovie(
    debrid: com.fourseveneightnine.tv.client.data.streams.debrid.DebridResolver,
    checkAvailability: Boolean = false,
): StreamRow? {
    val aliases = torboxV2Hashes()
    return probeTamilMVMovie(
        filesForHash = { hash -> debrid.torboxFiles(listOf(hash), aliases)[hash].orEmpty() },
        filesForHashes = { hashes -> debrid.torboxFiles(hashes, aliases) },
        library = debrid.libraryState.value.rows,
        availabilityForHash = if (checkAvailability) { hash -> debrid.isTorboxCached(hash, aliases[hash]) } else null,
    )
}

/** Match the iPhone's single-hash file check before looking at other copies in the same tier. */
internal suspend fun CatalogItem.probeTamilMVMovie(
    filesForHash: suspend (String) -> List<DebridFile>,
    filesForHashes: suspend (List<String>) -> Map<String, List<DebridFile>>,
    library: List<TorboxLibraryTorrent> = emptyList(),
    availabilityForHash: (suspend (String) -> Boolean)? = null,
): StreamRow? {
    val sources = preferredTamilMVMovieSources()
    val hashes = sources.map { it.infohash.lowercase() }.distinct()
    val preferred = hashes.firstOrNull() ?: return null
    val aliases = torboxV2Hashes()
    val requested = buildMap {
        for (hash in hashes) {
            aliases[hash]?.takeIf { it.length == 64 }?.let { v2 ->
                put(v2, hash)
                put(v2.take(40), hash)
            }
        }
        for (hash in hashes) put(hash, hash)
    }
    val saved = library.asSequence().filter { it.ready }.mapNotNull { torrent ->
        requested[torrent.infoHash.lowercase()]?.let { it to torrent }
    }.groupBy({ it.first }, { it.second })
    cachedTamilMVMovie(emptyMap(), saved.filterKeys { it == preferred })?.let { return it }
    cachedTamilMVMovie(mapOf(preferred to filesForHash(preferred)))?.let { return it }
    // Only Play supplies this read. A positive status can resolve the exact torrent's
    // files through the cache-only open even when checkcached omitted its file list.
    if (availabilityForHash?.invoke(preferred) == true) {
        return copy(playbackSources = listOf(sources.first())).savedStream(null, null)
    }
    val alternatives = hashes.drop(1)
    if (alternatives.isEmpty()) return null
    val savedAlternatives = saved.filterKeys { it != preferred }
    val firstSaved = cachedTamilMVMovie(emptyMap(), savedAlternatives)
    val toCheck = alternatives.takeWhile { it != firstSaved?.infoHash?.lowercase() }
    if (toCheck.isEmpty()) return firstSaved
    return cachedTamilMVMovie(filesForHashes(toCheck), savedAlternatives)
}

private fun CatalogItem.preferredTamilMVMovieSources() =
    playbackSources.maxOfOrNull { TamilMVCatalogRepository.printTier(it.filename) }?.let { tier ->
        playbackSources.filter { TamilMVCatalogRepository.printTier(it.filename) == tier }
    }.orEmpty()

/** Stable local progress identity can restore the same file after a process restart. */
internal fun savedTorboxFile(id: String, title: String): StreamRow? {
    val match = Regex("^saved-torbox:([a-fA-F0-9]{64}|[a-fA-F0-9]{40}|[a-zA-Z2-7]{32}):([0-9]+)(?::([0-9]+))?$").matchEntire(id) ?: return null
    val fileID = match.groupValues[2].toIntOrNull() ?: return null
    val torrentID = match.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull()
    if (match.groupValues[3].isNotEmpty() && torrentID == null) return null
    return StreamRow(id, "TorBox Library", "Saved TorBox file", title, infoHash = match.groupValues[1],
        torboxFileID = fileID, torboxTorrentID = torrentID, exactSource = true)
}
