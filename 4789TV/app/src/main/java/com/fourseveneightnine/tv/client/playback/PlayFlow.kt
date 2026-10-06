package com.fourseveneightnine.tv.client.playback

import com.fourseveneightnine.tv.client.AppGraph
import com.fourseveneightnine.tv.client.ClientGraph
import com.fourseveneightnine.tv.client.data.library.LibraryRepository
import com.fourseveneightnine.tv.client.data.meta.Meta
import com.fourseveneightnine.tv.client.data.streams.StreamRow
import com.fourseveneightnine.tv.client.data.streams.debrid.DebridOutcome
import com.fourseveneightnine.tv.protocol.NowPlayingArtwork
import com.fourseveneightnine.tv.protocol.OpenMediaRequest
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
)

internal sealed interface PlayResult {
    /** The player opened; the shell navigates on `PlaybackSession.openRequests`. */
    data object Opened : PlayResult
    /** No auto-pick was possible; the caller should open the Streams screen. */
    data object ShowList : PlayResult
    data class Failed(val message: String) : PlayResult
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

    /** Play one row the viewer chose. No ranking, no auto-pick — just resolve and open. */
    suspend fun playRow(row: StreamRow, request: PlayRequest): PlayResult = PlayResult.ShowList

    /**
     * The episode after the one now playing, or null. It carries no URL: the next episode's
     * sources are searched while the plate counts down (`TV_DESIGN_SPEC.md` §11.7).
     */
    val nextUp: StateFlow<NextUpPlan?> get() = noNextUp

    /** Start the episode [nextUp] names. */
    suspend fun playNextUp(): PlayResult = PlayResult.ShowList

    /** Resolve the current local source again after its link expires, keeping the playhead. */
    suspend fun retryCurrent(positionMillis: Long): PlayResult = PlayResult.ShowList

    /** A direct IPTV/recording open replaces any add-on source and progress context. */
    fun clearCurrent() = Unit

    suspend fun captureStopProgress() = Unit

    /** The title this flow last opened locally, or null. The shell uses it for Sources-while-playing. */
    val current: StateFlow<PlayRequest?> get() = noCurrent
}

/** One shared empty flow, so the default above allocates nothing per implementation. */
private val noNextUp: StateFlow<NextUpPlan?> = MutableStateFlow(null)
private val noCurrent: StateFlow<PlayRequest?> = MutableStateFlow(null)

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

    private var progressJob: Job? = null
    private var progressRecorder: ProgressRecorder? = null
    private var metadataJob: Job? = null
    private var metadataGeneration = 0L

    override fun clearCurrent() {
        progressJob?.cancel()
        progressRecorder = null
        metadataJob?.cancel()
        metadataGeneration++
        _current.value = null
        _nextUp.value = null
    }

    override suspend fun captureStopProgress() {
        val recorder = progressRecorder ?: return
        val request = _current.value ?: return
        if (graph.playback.origin.value != PlaybackSession.Origin.Local) return
        val snapshot = withTimeoutOrNull(250) { graph.playback.refreshSnapshot() }
            ?: graph.playback.snapshot.value
        if (progressRecorder !== recorder || _current.value != request ||
            graph.playback.origin.value != PlaybackSession.Origin.Local) return
        val position = graph.playback.resumePositionMillis()
        progressJob?.cancel()
        // Capture before the engine clears its timeline; Room persistence must not delay Stop.
        client.scope.launch {
            recorder.record(position, (snapshot.durationSeconds * 1_000).toLong(), ProgressRecorder.Reason.Stop)
        }
    }

    override suspend fun play(request: PlayRequest): PlayResult {
        val services = awaitServices() ?: return PlayResult.Failed(DefaultPlayFlow.NOT_PAIRED)
        // "Sources" means the viewer wants to look. The Streams screen runs its own search, so
        // searching twice here would only cost the add-ons a second round of calls.
        if (request.forceList) return PlayResult.ShowList

        // Play always tries the ranked best source. Sources is the explicit path to the list.
        val playRules = services.rules.let { rules ->
            if (rules.enabled) rules.copy(autoPlay = true)
            else com.fourseveneightnine.tv.client.data.streams.PlaybackRules.DEFAULT.copy(
                enabled = true, readyOnly = false, autoPlay = true,
            )
        }
        val planner = PlayPlanner(playRules, codecs())
        val streamId = StreamIds.forStreams(services, request.type, request.id)
        val choice = planner.choose(
            services.search.search(request.type, streamId, request.season, request.episode),
        ) ?: return PlayResult.ShowList
        ReceiverDiagnostics.record("playflow.autopick", "addon=${choice.row.addonName}")
        return open(choice.row, request)
    }

    override suspend fun playRow(row: StreamRow, request: PlayRequest): PlayResult {
        awaitServices() ?: return PlayResult.Failed(DefaultPlayFlow.NOT_PAIRED)
        return open(row, request)
    }

    override suspend fun playNextUp(): PlayResult {
        val plan = _nextUp.value ?: return PlayResult.ShowList
        return play(plan.request())
    }

    override suspend fun retryCurrent(positionMillis: Long): PlayResult {
        val request = _current.value ?: return PlayResult.ShowList
        val services = awaitServices() ?: return PlayResult.Failed(NOT_PAIRED)
        // An expired direct URL needs a fresh add-on search. An expired debrid URL needs the
        // resolver's in-memory answer evicted before that search can yield a new link.
        services.debrid.clear()
        return play(request.copy(resumeFromMs = positionMillis.coerceAtLeast(0L), forceList = false))
    }

    /** Resolve one row and hand it to the player. The URL goes nowhere else. */
    private suspend fun open(row: StreamRow, request: PlayRequest): PlayResult {
        val services = awaitServices() ?: return PlayResult.Failed(DefaultPlayFlow.NOT_PAIRED)
        val outcome = services.debrid.resolve(row, request.season, request.episode)
        PlayPlanner.failure(outcome)?.let { return it }
        val resolved = (outcome as DebridOutcome.Success).resolved

        progressJob?.cancel()
        progressRecorder = null
        metadataJob?.cancel()
        val generation = ++metadataGeneration
        // A playable URL must not wait for TMDB/cast enrichment, even on a cold direct Play.
        val meta = services.meta.peek(request.type, request.id)
        val plan = NextUp.compute(meta, request.season, request.episode)
        _nextUp.value = plan

        val poster = request.posterUrl ?: meta?.poster
        val backdrop = request.backdropUrl ?: meta?.backdrop
        // The Opening and Buffering screens read this; `OpenMediaRequest` carries no artwork.
        NowPlayingArtwork.stage(landscapeURL = backdrop, posterURL = poster, logoURL = meta?.logo)

        // Capture ownership before the engine opens. Profile switching is blocked for this local
        // run, and this stable reference also guarantees that lifecycle locking cannot redirect a
        // late pause/stop write into a different household member's library.
        val playbackLibrary = client.activeLibrary

        _current.value = request
        val opened = graph.playback.open(
            OpenMediaRequest(
                url = resolved.url,
                title = request.title,
                subtitle = episodeSubtitle(meta, request.season, request.episode),
                headers = resolved.headers,
                startPositionMs = request.resumeFromMs?.takeIf { it > 0L },
            ),
            PlaybackSession.Origin.Local,
        )
        if (opened.isFailure) return PlayResult.Failed(PlayPlanner.NO_ANSWER)
        metadataJob = client.scope.launch {
            val enriched = try { services.meta.meta(request.type, request.id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            withContext(Dispatchers.Main) {
                if (generation != metadataGeneration || _current.value != request ||
                    graph.playback.origin.value != PlaybackSession.Origin.Local ||
                    graph.playback.lastOpenMedia()?.url != resolved.url) return@withContext
                _nextUp.value = NextUp.compute(enriched, request.season, request.episode)
                if (enriched != null) NowPlayingArtwork.stage(
                    landscapeURL = request.backdropUrl ?: enriched.backdrop,
                    posterURL = request.posterUrl ?: enriched.poster, logoURL = enriched.logo)
            }
        }

        runCatching {
            playbackLibrary.recordLocalPlay(
                canonicalId = request.id,
                mediaType = request.type,
                title = request.title,
                posterUrl = poster,
                backdropUrl = backdrop,
                season = request.season,
                episode = request.episode,
            )
        }
        startProgress(request, playbackLibrary)
        return PlayResult.Opened
    }

    /** One recorder per title. A switch cancels the last run rather than letting two rows race. */
    private fun startProgress(request: PlayRequest, playbackLibrary: LibraryRepository) {
        progressJob?.cancel()
        val recorder = ProgressRecorder(playbackLibrary)
        recorder.begin(ProgressTarget(request.id, request.type, request.season, request.episode))
        progressRecorder = recorder
        progressJob = client.scope.launch {
            recorder.follow(
                session = graph.playback,
                target = ProgressTarget(request.id, request.type, request.season, request.episode),
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
        const val SERVICES_TIMEOUT_MILLIS: Long = 3_000L

        /** `client-data/API-A.md` §"Press Play". It names the one thing the viewer can do. */
        const val NOT_PAIRED = "Pair your iPhone first."
    }
}
