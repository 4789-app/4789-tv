package com.fourseveneightnine.tv.client.playback

import com.fourseveneightnine.tv.client.appGraph
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.data.addons.SubtitleExtra
import com.fourseveneightnine.tv.client.data.addons.SubtitleSearchResult
import com.fourseveneightnine.tv.client.data.streams.SubtitleTrack

import com.fourseveneightnine.tv.client.iptv.IptvVod
import android.content.Context
import android.view.SurfaceHolder
import com.fourseveneightnine.tv.player.ExternalPlayerIntentPolicy
import com.fourseveneightnine.tv.player.PlaybackDiagnostics
import com.fourseveneightnine.tv.player.ReceiverPlaybackPhase
import com.fourseveneightnine.tv.player.ReceiverSubtitleStyle
import com.fourseveneightnine.tv.player.ResumePoint
import com.fourseveneightnine.tv.player.ResumePolicy
import com.fourseveneightnine.tv.startup.ResumePointStore
import kotlinx.coroutines.channels.Channel
import com.fourseveneightnine.tv.player.SwappableReceiverController
import com.fourseveneightnine.tv.player.upscale.UpscaleMode
import com.fourseveneightnine.tv.protocol.InstalledExternalPlayer
import com.fourseveneightnine.tv.protocol.NowPlayingArt
import com.fourseveneightnine.tv.protocol.NowPlayingArtwork
import com.fourseveneightnine.tv.protocol.OpenMediaRequest
import com.fourseveneightnine.tv.protocol.ReceiverController
import com.fourseveneightnine.tv.protocol.ReceiverEvent
import com.fourseveneightnine.tv.protocol.ReceiverSnapshot
import com.fourseveneightnine.tv.protocol.ReceiverTrack
import com.fourseveneightnine.tv.protocol.ReceiverTracks
import com.fourseveneightnine.tv.protocol.SeekCommand
import com.fourseveneightnine.tv.protocol.SubtitleSelection
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import com.fourseveneightnine.tv.client.ui.screens.settings.SettingsKeys
import com.fourseveneightnine.tv.client.data.streams.OriginalAudioLanguage

/**
 * The single owner of the [ReceiverController].
 *
 * Both routes into playback go through here: the local Play action and the phone's `Player.Open`.
 * That is the whole point — the old Activity let the RPC dispatcher reach the controller directly
 * while the on-TV controls reached it by another path, so nothing could answer "who started this
 * and is the player on screen?" without a flag that could get stuck.
 *
 * The dispatcher holds this object as its [ReceiverController], so a phone open lands on
 * [open] with [Origin.Phone] and raises [openRequests], which is what navigates the shell to the
 * player from wherever it was — including from inside Settings (plan §8).
 */
internal class PlaybackSession(
    private val controller: SwappableReceiverController,
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val renewLiveRequest: suspend (LiveChannel, OpenMediaRequest) -> OpenMediaRequest,
    private val recordRecentChannel: suspend (String) -> Unit,
    private val resumeStore: ResumePointStore,
    private val beforePhoneOpen: () -> Unit,
    private val beforeLocalStop: suspend () -> Unit,
) : ReceiverController by controller {

    /** Who asked for this title. The cast chip and the D-pad routing both read it. */
    enum class Origin { Phone, Local }

    enum class SourceFeedback { Started, Failed }

    /** TV-local context for channel navigation. Never leaves this process or enters the wire DTO. */
    data class LiveChannel(
        val id: String, val sourceId: String, val group: String,
        val browseGroup: String? = group,
        val channelIds: List<String> = emptyList(),
        val browseLabel: String = group,
    )

    /** The scope the shell fires player commands on, so a key press never blocks on the engine. */
    val uiScope: CoroutineScope get() = scope

    private val _origin = MutableStateFlow<Origin?>(null)
    val origin: StateFlow<Origin?> = _origin.asStateFlow()

    private val _liveChannel = MutableStateFlow<LiveChannel?>(null)
    val liveChannel: StateFlow<LiveChannel?> = _liveChannel.asStateFlow()
    private val _iptvVod = MutableStateFlow<IptvVod?>(null)
    val iptvVod: StateFlow<IptvVod?> = _iptvVod.asStateFlow()

    private val _phase = MutableStateFlow<ReceiverPlaybackPhase>(ReceiverPlaybackPhase.Idle)
    val phase: StateFlow<ReceiverPlaybackPhase> = _phase.asStateFlow()

    private val _sourceFeedback = MutableSharedFlow<SourceFeedback>(extraBufferCapacity = 4)
    val sourceFeedback: SharedFlow<SourceFeedback> = _sourceFeedback.asSharedFlow()
    private var lastStartedCue = 0L
    private var lastFailedCue = 0L

    /** Called on Main when the final failure reaches the viewer. */
    fun reportSourceFailure(generation: Long? = null) {
        if (generation != null) {
            if (generation != openGeneration || generation == lastFailedCue) return
            lastFailedCue = generation
        }
        _sourceFeedback.tryEmit(SourceFeedback.Failed)
    }

    private val _snapshot = MutableStateFlow(ReceiverSnapshot())
    val snapshot: StateFlow<ReceiverSnapshot> = _snapshot.asStateFlow()
    private var lastPositionMillis = 0L
    private val trackPrefs = appContext.getSharedPreferences("tv_player_preferences", Context.MODE_PRIVATE)
    private val presentationPrefs = appContext.getSharedPreferences("receiver_presentation", Context.MODE_PRIVATE)
    @Volatile
    private var openGeneration = 0L
    // Reserve intent order before a progress checkpoint can suspend on Room.
    private var playbackIntent = 0L
    /** Stop and replacement invalidate continuation work; writers are serialized on Main. */
    val generation: Long get() = openGeneration
    private val resumeWrites = Channel<ResumePoint?>(Channel.CONFLATED)
    private var resumeLoaded = false
    private var resumePoint: ResumePoint? = null
    val resumeTitle: String? get() = resumePoint?.title
    val resumeLiveChannelId: String? get() = presentationPrefs.getString("resume_live_channel", null)
    private var liveReconnectJob: Job? = null
    private var liveReconnectAttempts = 0
    private var livePlayingSince = 0L
    private var latestControllerPhase: ReceiverPlaybackPhase = ReceiverPlaybackPhase.Idle
    // Keep Stop callbacks from retiring retained ownership before Stop succeeds.
    private var failedStopGeneration: Long? = null
    val stopRequested: Boolean get() = failedStopGeneration == openGeneration
    private var restoredGeneration = -1L
    private var trackRestoreJob: Job? = null
    private var subtitleLookup: Pair<PlayRequest, SubtitleExtra>? = null
    internal var trackChoiceVersion = 0L
        private set
    private var inheritedEpisodeTracks: EpisodePreference? = null
    internal var episodeTracks = ReceiverTracks()
        private set
    private var titleOriginalAudioLanguage: String? = null
    private var recoveredPhoneSource: Pair<String?, Long>? = null

    /** A renewed URL or recreated player screen must not restart a failing cast forever. */
    fun claimPhoneSourceRecovery(): Boolean {
        val request = controller.lastOpenMedia() ?: return false
        if (_origin.value != Origin.Phone || request.isLive) return false
        val castId = request.castId?.takeIf { it.isNotBlank() }
        val owner = castId to if (castId == null) openGeneration else 0L
        if (recoveredPhoneSource == owner) return false
        recoveredPhoneSource = owner
        return true
    }

    private val _tracks = MutableStateFlow(ReceiverTracks())
    val tracks: StateFlow<ReceiverTracks> = _tracks.asStateFlow()

    private val _diagnostics = MutableStateFlow(PlaybackDiagnostics())
    val diagnosticsState: StateFlow<PlaybackDiagnostics> = _diagnostics.asStateFlow()

    private val _surfaceReady = MutableStateFlow(false)
    val surfaceReadyState: StateFlow<Boolean> = _surfaceReady.asStateFlow()

    /** Artwork the phone staged for this title (`X4789.NowPlaying`), or a local card's poster. */
    val nowPlayingArt: StateFlow<NowPlayingArt?> = NowPlayingArtwork.art

    /** Exo side channels the player screen draws with. mpv renders its own subtitles in-video. */
    val cues: StateFlow<List<androidx.media3.common.text.Cue>> = controller.cues
    val videoAspectRatio: StateFlow<Float> = controller.videoAspectRatio
    val subtitleStyle: StateFlow<ReceiverSubtitleStyle> = controller.subtitleStyle

    /** One event per accepted open. The shell navigates to the player on it. */
    private val _openRequests = MutableSharedFlow<Origin>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val openRequests: SharedFlow<Origin> = _openRequests.asSharedFlow()

    init {
        scope.launch(Dispatchers.IO) {
            for (point in resumeWrites) {
                if (point == null) resumeStore.clear()
                else if (!resumeStore.save(point)) ReceiverDiagnostics.record("resume.save.failed")
            }
        }
        trackPrefs.getString("subtitleStyle", null)?.let { saved ->
            runCatching { Json.parseToJsonElement(saved) as? JsonObject }.getOrNull()?.let { style ->
                scope.launch { controller.stageSubtitleStyle(style) }
            }
        }
        scope.launch { controller.playbackPhase.collect { phase ->
            latestControllerPhase = phase
            if (failedStopGeneration == openGeneration) return@collect
            val channel = _liveChannel.value
            if (channel != null) {
                if (phase != ReceiverPlaybackPhase.Playing && livePlayingSince > 0L) {
                    if (SystemClock.elapsedRealtime() - livePlayingSince >= 30_000L) liveReconnectAttempts = 0
                    livePlayingSince = 0L
                }
                if (phase == ReceiverPlaybackPhase.Playing && livePlayingSince == 0L) {
                    livePlayingSince = SystemClock.elapsedRealtime()
                }
                if (phase == ReceiverPlaybackPhase.Ended || (phase as? ReceiverPlaybackPhase.Error)?.retryLive == true) {
                    reconnectLive(channel, phase as? ReceiverPlaybackPhase.Error)
                    return@collect
                }
            }
            _phase.value = phase
            if (phase == ReceiverPlaybackPhase.Playing && _origin.value != null && lastStartedCue != openGeneration) {
                lastStartedCue = openGeneration
                _sourceFeedback.tryEmit(SourceFeedback.Started)
            }
            when (phase) {
                ReceiverPlaybackPhase.Ended -> if (_origin.value != null && resumePoint != null) setResumePoint(null)
                ReceiverPlaybackPhase.Playing, ReceiverPlaybackPhase.Paused ->
                    resumePoint?.let { saved ->
                        val paused = phase == ReceiverPlaybackPhase.Paused
                        if (saved.paused != paused) setResumePoint(saved.copy(paused = paused))
                    }
                else -> Unit
            }
            if (phase is ReceiverPlaybackPhase.Playing && _origin.value == Origin.Local && restoredGeneration != openGeneration) {
                restoredGeneration = openGeneration
                val generation = openGeneration
                val choices = trackChoiceVersion
                trackRestoreJob = scope.launch { restoreLocalTrackChoices(generation, choices) }
            }
        } }
        scope.launch { controller.surfaceReady.collect { _surfaceReady.value = it } }
        scope.launch { controller.diagnostics.collect { _diagnostics.value = it } }
        scope.launch {
            controller.events.collect { event ->
                _snapshot.value = event.snapshot()
                rememberPosition(_snapshot.value)
                if (!event.snapshot().active) _tracks.value = ReceiverTracks()
            }
        }
    }

    /**
     * The wire path. `RpcDispatcher` calls this, so an open the phone asked for is recorded as
     * such without the dispatcher knowing anything about origins.
     */
    override suspend fun open(request: OpenMediaRequest): Result<Unit> = open(request, Origin.Phone)

    suspend fun open(
        request: OpenMediaRequest,
        origin: Origin,
        originalAudioLanguage: String? = null,
        catalogRequest: PlayRequest? = null,
        profileId: String? = null,
        resumePaused: Boolean = false,
        resumeLinkSavedAtMillis: Long? = null,
        catalogFile: ResumePoint.CatalogFile? = null,
        episodePreference: EpisodePreference? = null,
        sourceRecipe: String? = null,
        subtitleVideoHash: String? = null,
    ): Result<Unit> =
        open(request, origin, null, originalAudioLanguage = originalAudioLanguage,
            catalogRequest = catalogRequest, profileId = profileId, resumePaused = resumePaused,
            resumeLinkSavedAtMillis = resumeLinkSavedAtMillis, catalogFile = catalogFile, episodePreference = episodePreference,
            sourceRecipe = sourceRecipe, subtitleVideoHash = subtitleVideoHash)

    suspend fun openLiveChannel(request: OpenMediaRequest, channel: LiveChannel): Result<Unit> =
        open(request, Origin.Local, channel)

    /** A new displayed category can include the same video. Adopt its order without reopening. */
    fun updateLiveBrowseContext(channel: LiveChannel): Boolean {
        val active = _liveChannel.value ?: return false
        if (_origin.value != Origin.Local || active.id != channel.id) return false
        return _liveChannel.compareAndSet(active, active.copy(
            browseGroup = channel.browseGroup, channelIds = channel.channelIds,
            browseLabel = channel.browseLabel))
    }

    suspend fun openIptvVod(request: OpenMediaRequest, item: IptvVod, resumePaused: Boolean = false): Result<Unit> =
        open(request, Origin.Local, null, item, resumePaused = resumePaused)

    /** `open` prepares asynchronously; a channel is recent only after media actually starts. */
    suspend fun awaitLiveOutcome(timeoutMillis: Long = 45_000L): ReceiverPlaybackPhase? =
        withTimeoutOrNull(timeoutMillis) {
            phase.first { phase ->
                phase is ReceiverPlaybackPhase.Playing || phase is ReceiverPlaybackPhase.Error ||
                    phase is ReceiverPlaybackPhase.Stopped || phase is ReceiverPlaybackPhase.Ended
            }
        }

    private suspend fun open(request: OpenMediaRequest, origin: Origin, channel: LiveChannel?, vod: IptvVod? = null,
        originalAudioLanguage: String? = null, catalogRequest: PlayRequest? = null,
        profileId: String? = null, resumePaused: Boolean = false,
        resumeLinkSavedAtMillis: Long? = null, resumeIptvVodId: String? = null, catalogFile: ResumePoint.CatalogFile? = null,
        episodePreference: EpisodePreference? = null,
        sourceRecipe: String? = null,
        subtitleVideoHash: String? = null,
    ): Result<Unit> = withContext(Dispatchers.Main.immediate) {
        val intent = ++playbackIntent
        if (origin == Origin.Phone) {
            beforePhoneOpen()
            if (_origin.value == Origin.Local) {
                val previousGeneration = openGeneration
                try { beforeLocalStop() }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { ReceiverDiagnostics.record("progress.write.failed", "reason=phone-open") }
                coroutineContext.ensureActive()
                if (intent != playbackIntent || previousGeneration != openGeneration)
                    throw kotlinx.coroutines.CancellationException("Playback changed")
            }
        }
        if (origin == Origin.Local && !request.isLive)
            appContext.appGraph.pictureInPicture.moveLiveToCompanion(vod?.sourceId)
        val generation = ++openGeneration
        trackRestoreJob?.cancel()
        failedStopGeneration = null
        liveReconnectJob?.cancel()
        liveReconnectJob = null
        liveReconnectAttempts = 0
        livePlayingSince = 0L
        NowPlayingArtwork.beginPlayback(generation, useStaged = channel == null, owner = request.castId ?: request.title)
        _phase.value = ReceiverPlaybackPhase.Opening(request.title)
        inheritedEpisodeTracks = episodePreference.takeIf { origin == Origin.Local }
        subtitleLookup = catalogRequest?.takeIf { origin == Origin.Local && !request.isLive }?.let {
            it to SubtitleExtra(catalogFile?.filename, subtitleVideoHash, catalogFile?.sizeBytes)
        }
        titleOriginalAudioLanguage = OriginalAudioLanguage.normalize(originalAudioLanguage).takeIf { origin == Origin.Local }
        lastPositionMillis = request.startPositionMs ?: 0L
        _snapshot.value = ReceiverSnapshot(positionSeconds = lastPositionMillis / 1_000.0)
        _tracks.value = ReceiverTracks()
        episodeTracks = ReceiverTracks()
        _iptvVod.value = vod.takeIf { origin == Origin.Local }
        _origin.value = origin
        _liveChannel.value = channel.takeIf { origin == Origin.Local && request.isLive }
        ReceiverDiagnostics.benchmark("playback.open") { "generation=$generation origin=$origin live=${request.isLive}" }
        ReceiverDiagnostics.record("playback.open", "origin=$origin live=${request.isLive}")
        // The Activity owns the video surface. Keep the caller's screen and coroutine alive until
        // the engine accepts the handoff. A rejected open returns its error to the caller.
        val result = controller.open(request)
        if (generation == openGeneration && result.isSuccess) {
            val activeChannel = _liveChannel.value
            presentationPrefs.edit().putString("resume_live_channel", activeChannel?.id).apply()
            if (activeChannel != null) {
                setResumePoint(null)
                scope.launch {
                    val outcome = phase.first { openGeneration != generation || it == ReceiverPlaybackPhase.Playing ||
                        it is ReceiverPlaybackPhase.Error || it == ReceiverPlaybackPhase.Stopped || it == ReceiverPlaybackPhase.Ended }
                    if (openGeneration == generation && outcome == ReceiverPlaybackPhase.Playing)
                        recordRecentChannel(activeChannel.id)
                }
            } else {
                val artwork = NowPlayingArtwork.forPlayback(generation)
                setResumePoint(ResumePoint(
                    url = request.resumeSourceReference ?: request.url, title = request.title, subtitle = request.subtitle,
                    headers = if (request.resumeSourceReference == null) request.headers else emptyMap(),
                    positionMillis = request.startPositionMs ?: 0L,
                    savedAtMillis = resumeLinkSavedAtMillis ?: System.currentTimeMillis(),
                    artworkURL = artwork?.landscapeURL,
                    posterURL = artwork?.posterURL, logoURL = artwork?.logoURL, origin = if (origin == Origin.Phone) "phone" else "local",
                    profileId = profileId, catalogType = catalogRequest?.type, catalogId = catalogRequest?.id,
                    season = catalogRequest?.season, episode = catalogRequest?.episode, watchlistId = catalogRequest?.watchlistId, iptvVodId = vod?.id ?: resumeIptvVodId,
                    castId = request.castId, chapterSeconds = request.chapterSeconds, isLive = request.isLive,
                    paused = resumePaused, catalogFile = catalogFile, sourceRecipe = sourceRecipe,
                    nextEpisode = request.nextUp?.let { ResumePoint.SavedNextEpisode(it) },
                ))
                if (resumePaused) controller.setSpeed(0)
            }
            _openRequests.tryEmit(origin)
        }
        if (result.isFailure) {
            // Keep attempted IPTV ownership for Retry, Guide and Return-to-previous.
            // It clears on Stop or the next open, not on an engine prepare failure.
            ReceiverDiagnostics.record("playback.open.failed", "origin=$origin")
            if (origin == Origin.Phone && generation == openGeneration) reportSourceFailure(generation)
        }
        result
    }

    private fun setResumePoint(point: ResumePoint?) {
        resumeLoaded = true
        resumePoint = point
        resumeWrites.trySend(point)
    }

    suspend fun pendingResume(profileId: String?): ResumePoint? = withContext(Dispatchers.Main.immediate) {
        if (!resumeLoaded) {
            val stored = withContext(Dispatchers.IO) { resumeStore.load() }
            if (!resumeLoaded) {
                resumeLoaded = true
                resumePoint = stored
            }
        }
        resumePoint?.takeIf { ResumePolicy.belongsTo(it, profileId) }
    }

    suspend fun titleResume(profileId: String?, request: PlayRequest): ResumePoint? {
        val stored = withContext(Dispatchers.IO) {
            resumeStore.loadTitle(profileId, request.type, request.id, request.season, request.episode)
        }
        return stored ?: pendingResume(profileId)?.takeIf {
            it.origin == "local" && it.catalogType == request.type && it.catalogId == request.id &&
                it.season == request.season && it.episode == request.episode
        }
    }

    suspend fun checkpointResume(sample: ReceiverSnapshot? = null) = withContext(Dispatchers.Main.immediate) {
        val saved = resumePoint ?: return@withContext
        val owner = openGeneration
        val media = controller.lastOpenMedia() ?: return@withContext
        val sourceReference = media.resumeSourceReference ?: media.url
        if (_origin.value == null || sourceReference != saved.url || _phase.value == ReceiverPlaybackPhase.Ended) return@withContext
        val snapshot = sample ?: controller.snapshot()
        val current = resumePoint ?: return@withContext
        if (owner != openGeneration || current.url != saved.url || _phase.value == ReceiverPlaybackPhase.Ended) {
            return@withContext
        }
        val measured = (snapshot.positionSeconds * 1_000).toLong().coerceAtLeast(0L)
        val position = when {
            current.isLive -> 0L
            snapshot.active || measured > 0L -> measured
            else -> current.positionMillis
        }
        val updated = current.copy(positionMillis = position,
            logoURL = NowPlayingArtwork.forPlayback(owner)?.logoURL ?: current.logoURL,
            durationMillis = (snapshot.durationSeconds * 1_000).toLong())
        setResumePoint(updated)
        if (restoredGeneration == owner) withContext(Dispatchers.IO) {
            if (!resumeStore.saveTitle(updated)) ReceiverDiagnostics.record("resume.title.save.failed")
        }
    }

    suspend fun restoreDirect(point: ResumePoint, vod: IptvVod? = null): Result<Unit> {
        NowPlayingArtwork.stage(landscapeURL = point.artworkURL, posterURL = point.posterURL, logoURL = point.logoURL, owner = point.castId ?: point.title)
        val origin = if (point.origin == "phone") Origin.Phone else Origin.Local
        return open(point.mediaRequest(), origin, null, vod, profileId = point.profileId,
            resumePaused = point.paused, resumeLinkSavedAtMillis = point.savedAtMillis,
            resumeIptvVodId = point.iptvVodId)
    }

    /** Keep the interrupted title on the player surface when its old link cannot be reopened. */
    fun showResumeError(point: ResumePoint, message: String) {
        openGeneration++
        _origin.value = if (point.origin == "phone") Origin.Phone else Origin.Local
        _phase.value = ReceiverPlaybackPhase.Error(message, code = "resume_failed")
    }

    private fun reconnectLive(channel: LiveChannel, interruption: ReceiverPlaybackPhase.Error?) {
        if (liveReconnectJob?.isActive == true) return
        val request = controller.lastOpenMedia() ?: return
        val owner = openGeneration
        _phase.value = ReceiverPlaybackPhase.Opening("Reconnecting to ${request.title.orEmpty()}")
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                while (owner == openGeneration && liveReconnectAttempts < 3) {
                    val attempt = ++liveReconnectAttempts
                    val canRetryQuickly = interruption?.code in setOf("end_of_input", "no_progress", "audio_video_timing")
                    delay(if (attempt == 1 && canRetryQuickly) 250L else 1_000L shl (attempt - 1))
                    if (owner != openGeneration || latestControllerPhase == ReceiverPlaybackPhase.Playing) return@launch
                    ReceiverDiagnostics.record("iptv.stream.reconnect", "attempt=$attempt")
                    val outcome = withTimeoutOrNull(22_000L) {
                        try {
                            val renewed = renewLiveRequest(channel, request)
                            if (owner != openGeneration) return@withTimeoutOrNull null
                            controller.open(renewed.copy(startPositionMs = null)).getOrThrow()
                            controller.playbackPhase.first {
                                it == ReceiverPlaybackPhase.Playing || it == ReceiverPlaybackPhase.Ended ||
                                    it is ReceiverPlaybackPhase.Error
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                    }
                    if (outcome == ReceiverPlaybackPhase.Playing) return@launch
                    if (outcome is ReceiverPlaybackPhase.Error && !outcome.retryLive) return@launch
                }
                if (owner == openGeneration && latestControllerPhase != ReceiverPlaybackPhase.Playing) {
                    ReceiverDiagnostics.record("iptv.stream.reconnect.exhausted")
                    _phase.value = ReceiverPlaybackPhase.Error(
                        "Couldn’t reconnect to this live channel.",
                        code = interruption?.code?.let { "live_reconnect_exhausted:$it" } ?: "live_reconnect_exhausted",
                        httpStatus = interruption?.httpStatus, afterFirstFrame = interruption?.afterFirstFrame,
                        engineAttempt = interruption?.engineAttempt,
                    )
                }
            } finally {
                if (liveReconnectJob === coroutineContext[Job]) liveReconnectJob = null
            }
        }
        liveReconnectJob = job
        job.start()
    }

    /** Retire a loading stream during held selection without losing IPTV/category ownership. */
    suspend fun suspendLiveSelection(expected: LiveChannel): Result<Unit> {
        if (_liveChannel.value != expected || _origin.value != Origin.Local) return Result.success(Unit)
        openGeneration++
        liveReconnectJob?.cancel()
        liveReconnectJob = null
        return controller.stop()
    }

    override suspend fun stop(): Result<Unit> = stop(preserveLiveResume = false)

    suspend fun stopForBrowse(): Result<Unit> = stop(preserveLiveResume = false, keepMatch = true)

    override suspend fun executeAction(action: String): Result<Unit> =
        if (action.equals("stop", ignoreCase = true)) stop()
        else controller.executeAction(action)

    /** Discard this rejected engine without invalidating a newer source still being prepared. */
    suspend fun discardUnverifiedLocalOpen(expectedGeneration: Long): Result<Unit> =
        withContext(Dispatchers.Main.immediate) {
            if (openGeneration != expectedGeneration || _origin.value != Origin.Local)
                return@withContext Result.success(Unit)
            val intent = playbackIntent
            val previousFailedStop = failedStopGeneration
            failedStopGeneration = expectedGeneration
            val result = try { controller.stop() } catch (failure: Throwable) {
                if (openGeneration == expectedGeneration && failedStopGeneration == expectedGeneration) {
                    failedStopGeneration = previousFailedStop
                    if (previousFailedStop != expectedGeneration) _phase.value = latestControllerPhase
                }
                throw failure
            }
            // Stop may suspend while a Phone or another Local open takes the engine.
            if (openGeneration == expectedGeneration && failedStopGeneration == expectedGeneration) {
                if (result.isFailure) {
                    _phase.value = ReceiverPlaybackPhase.Error("Couldn’t stop playback. Try again or choose another source.")
                } else {
                    failedStopGeneration = null
                    if (playbackIntent == intent && _origin.value == Origin.Local) {
                        setResumePoint(null)
                        NowPlayingArtwork.clear()
                        _iptvVod.value = null
                        _origin.value = null
                        _liveChannel.value = null
                    }
                    _phase.value = ReceiverPlaybackPhase.Stopped
                }
            }
            result
        }

    suspend fun stopForVisibilityLoss(stillOwnsReceiver: () -> Boolean = { true }): Result<Unit> =
        withContext(Dispatchers.Main.immediate) {
            if (!stillOwnsReceiver()) return@withContext Result.success(Unit)
            val intent = ++playbackIntent
            runCatching { checkpointResume() }
            // Saving the playhead can suspend while Android starts a replacement Activity.
            if (intent != playbackIntent || !stillOwnsReceiver()) return@withContext Result.success(Unit)
            stop(preserveLiveResume = true, stillOwnsReceiver = stillOwnsReceiver, reservedIntent = intent)
        }

    private suspend fun stop(
        preserveLiveResume: Boolean,
        stillOwnsReceiver: () -> Boolean = { true },
        reservedIntent: Long? = null,
        keepMatch: Boolean = false,
    ): Result<Unit> = withContext(Dispatchers.Main.immediate) {
        if (!stillOwnsReceiver()) return@withContext Result.success(Unit)
        val intent = reservedIntent ?: ++playbackIntent
        if (intent != playbackIntent) return@withContext Result.success(Unit)
        val owner = openGeneration
        if (_origin.value == Origin.Local) {
            runCatching { beforeLocalStop() }
            coroutineContext.ensureActive()
        }
        // A snapshot may suspend while a phone or replacement Activity takes ownership.
        if (intent != playbackIntent || owner != openGeneration || !stillOwnsReceiver())
            return@withContext Result.success(Unit)
        val stoppingGeneration = ++openGeneration
        trackRestoreJob?.cancel()
        liveReconnectJob?.cancel()
        liveReconnectJob = null
        val previousFailedStop = failedStopGeneration
        failedStopGeneration = stoppingGeneration
        val result = try { controller.stop() } catch (failure: Throwable) {
            if (stoppingGeneration == openGeneration && failedStopGeneration == stoppingGeneration) {
                failedStopGeneration = previousFailedStop
                if (previousFailedStop != stoppingGeneration) _phase.value = latestControllerPhase
            }
            throw failure
        }
        if (stoppingGeneration == openGeneration && failedStopGeneration == stoppingGeneration) {
            if (result.isFailure) {
                _phase.value = ReceiverPlaybackPhase.Error("Couldn’t stop playback. Try again or choose another source.")
            } else {
                failedStopGeneration = null
                if (intent == playbackIntent && stillOwnsReceiver()) {
                    if (!preserveLiveResume) {
                        val pip = appContext.appGraph.pictureInPicture
                        if (pip.movieInCorner) pip.clear()
                        else if (pip.channel != null) {
                            if (keepMatch && _origin.value == Origin.Local && _liveChannel.value == null)
                                pip.returnToBrowse()
                            else pip.clear()
                        }
                        presentationPrefs.edit().remove("resume_live_channel").apply()
                        setResumePoint(null)
                    }
                    NowPlayingArtwork.clear()
                    _iptvVod.value = null
                    _origin.value = null
                    _liveChannel.value = null
                }
                _phase.value = ReceiverPlaybackPhase.Stopped
            }
        }
        result
    }

    fun attachSurfaceHolder(holder: SurfaceHolder, width: Int, height: Int) =
        controller.attachSurfaceHolder(holder, width, height)

    suspend fun refreshSnapshot(): ReceiverSnapshot {
        val generation = openGeneration
        val value = try { controller.snapshot() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { return _snapshot.value }
        if (openGeneration != generation) return _snapshot.value
        _snapshot.value = value
        rememberPosition(value)
        return value
    }

    fun resumePositionMillis(): Long = maxOf(
        lastPositionMillis,
        (_snapshot.value.positionSeconds * 1_000).toLong().coerceAtLeast(0L),
    )

    private fun rememberPosition(value: ReceiverSnapshot) {
        val position = (value.positionSeconds * 1_000).toLong()
        if (value.active || position > 0L) lastPositionMillis = position.coerceAtLeast(0L)
    }

    suspend fun refreshTracks(): ReceiverTracks {
        val owner = openGeneration
        val value = controller.tracks().getOrNull() ?: return _tracks.value
        if (owner != openGeneration) return _tracks.value
        _tracks.value = value
        if (_origin.value == Origin.Local && _phase.value != ReceiverPlaybackPhase.Ended) {
            // Some engines clear the track inventory on EOF. Retain the last playable selection for this generation.
            episodeTracks = ReceiverTracks(if (value.currentAudio != null) value.audio else episodeTracks.audio,
                value.subtitles.ifEmpty { episodeTracks.subtitles })
        }
        return value
    }

    suspend fun togglePlayPause(): Result<Int> = controller.playPause()

    suspend fun seekTo(positionMillis: Long): Result<Unit> =
        controller.seek(SeekCommand.AbsoluteSeconds(positionMillis.coerceAtLeast(0L) / 1_000.0))

    suspend fun seekRelative(offsetSeconds: Double): Result<Unit> =
        controller.seek(SeekCommand.RelativeSeconds(offsetSeconds))

    suspend fun selectAudioTrack(index: Int): Result<Unit> {
        trackChoiceVersion++
        trackRestoreJob?.cancel()
        val result = controller.selectAudio(index)
        if (result.isSuccess && _origin.value == Origin.Local) {
            val track = refreshTracks().audio.firstOrNull { it.index == index }
            canonicalTrackLanguage(track?.language)?.let { language ->
                trackPrefs.edit().putString("audioLanguage", language)
                    .putString("audioSetting", presentationPrefs.getString(SettingsKeys.AUDIO_LANGUAGE, "Original")).apply()
            }
        }
        return result
    }

    suspend fun selectSubtitleTrack(selection: SubtitleSelection): Result<Unit> {
        trackChoiceVersion++
        trackRestoreJob?.cancel()
        val result = controller.selectSubtitle(selection)
        if (result.isSuccess && _origin.value == Origin.Local) {
            val editor = trackPrefs.edit().putBoolean("subtitleOff", selection is SubtitleSelection.Off)
            val refreshed = refreshTracks()
            if (selection is SubtitleSelection.Index) {
                val track = refreshed.subtitles.firstOrNull { it.index == selection.value }
                val language = canonicalTrackLanguage(track?.language)
                    ?: "en".takeIf { track != null && SubtitleTrack.isEnglishLabel(track.name) }
                language?.let { editor.putString("subtitleLanguage", it) }
            }
            editor.apply()
        }
        return result
    }

    suspend fun addSubtitleTrack(url: String, language: String): Result<Unit> {
        trackChoiceVersion++
        trackRestoreJob?.cancel()
        val owner = generation
        val result = controller.addSubtitle(url)
        if (generation != owner) return Result.failure(IllegalStateException("Playback changed"))
        if (result.isSuccess) {
            if (_origin.value == Origin.Local) {
                val editor = trackPrefs.edit().putBoolean("subtitleOff", false)
                canonicalTrackLanguage(language)?.let { editor.putString("subtitleLanguage", it) }
                editor.apply()
            }
            refreshTracks()
        }
        return result
    }

    override suspend fun stageSubtitleStyle(params: JsonObject) {
        controller.stageSubtitleStyle(params)
        trackPrefs.edit().putString("subtitleStyle", params.toString()).apply()
    }

    private suspend fun restoreLocalTrackChoices(generation: Long, choiceVersion: Long) {
        val inherited = inheritedEpisodeTracks
        val audioLanguage = inherited?.selectedAudioLanguage ?: titleOriginalAudioLanguage ?: trackPrefs.getString("audioLanguage", null).takeIf {
            trackPrefs.getString("audioSetting", null) == presentationPrefs.getString(SettingsKeys.AUDIO_LANGUAGE, "Original")
        }
        val subtitleLanguage = trackPrefs.getString("subtitleLanguage", "en") ?: "en"
        val subtitleOff = trackPrefs.getBoolean("subtitleOff", false)
        var audioDone = audioLanguage == null
        var subtitleDone = false
        var needsAddon = false
        for (attempt in 1..30) {
            if (!ownsAutomaticTracks(generation, choiceVersion)) return
            val available = refreshTracks()
            if (!ownsAutomaticTracks(generation, choiceVersion)) return
            if (!audioDone && available.audio.isNotEmpty()) {
                val chosen = inherited?.audioTrack(available.audio, audioLanguage)
                    ?: available.audio.firstOrNull { canonicalTrackLanguage(it.language) == audioLanguage }
                chosen?.let { controller.selectAudio(it.index) }
                audioDone = true
            }
            if (!ownsAutomaticTracks(generation, choiceVersion)) return
            if (!subtitleDone && (subtitleOff || available.audio.isNotEmpty() || available.subtitles.isNotEmpty())) {
                val preferred = if (subtitleOff) null else automaticSubtitleTrack(available.subtitles, subtitleLanguage, inherited)
                val selected = preferred?.let { controller.selectSubtitle(SubtitleSelection.Index(it.index)).isSuccess } == true
                if (!ownsAutomaticTracks(generation, choiceVersion)) return
                if (!selected) controller.selectSubtitle(SubtitleSelection.Off)
                needsAddon = !subtitleOff && !selected
                subtitleDone = true
            }
            if (audioDone && subtitleDone) break
            delay(200)
        }
        if (!ownsAutomaticTracks(generation, choiceVersion)) return
        refreshTracks()
        if (!subtitleOff && (needsAddon || !subtitleDone) && ownsAutomaticTracks(generation, choiceVersion)) {
            attachAutomaticSubtitle(generation, choiceVersion)
        }
    }

    private fun ownsAutomaticTracks(generation: Long, choiceVersion: Long): Boolean =
        generation == openGeneration && choiceVersion == trackChoiceVersion && _origin.value == Origin.Local

    private suspend fun attachAutomaticSubtitle(generation: Long, choiceVersion: Long) {
        val (request, extra) = subtitleLookup ?: return
        val client = appContext.clientGraph
        val data = client.services.value ?: return
        val startedAt = SystemClock.elapsedRealtime()
        val id = client.subtitleVideoId(request, data) ?: return
        val attempted = mutableSetOf<String>()
        data.registry.searchSubtitles(request.type, id, extra).automaticSubtitleCandidates(extra.filename).firstOrNull { candidates ->
            for (track in candidates) {
                if (!ownsAutomaticTracks(generation, choiceVersion) || _phase.value == ReceiverPlaybackPhase.Ended) return@firstOrNull true
                if (!attempted.add(track.url)) continue
                val pickedAt = SystemClock.elapsedRealtime()
                val loaded = withTimeoutOrNull(6_000L) { controller.addSubtitle(track.url).isSuccess } == true
                if (loaded) {
                    if (ownsAutomaticTracks(generation, choiceVersion)) {
                        ReceiverDiagnostics.record("subtitle.auto.loaded",
                            "lookupMs=${pickedAt - startedAt} loadMs=${SystemClock.elapsedRealtime() - pickedAt} attempts=${attempted.size}")
                        refreshTracks()
                    }
                    return@firstOrNull true
                }
                if (attempted.size == 3) return@firstOrNull true
            }
            false
        }
    }

    fun setUpscale(mode: UpscaleMode) = controller.setUpscaleMode(mode)

    /** Handoff targets on this box, ranked by how much state each dialect keeps (CONTEXT §7). */
    fun installedPlayers(): List<InstalledExternalPlayer> =
        ExternalPlayerIntentPolicy.installedPlayers(appContext)
            .sortedBy { ExternalPlayerIntentPolicy.dialect(it.packageName).ordinal }

    /**
     * Give the stream to another app on this television.
     *
     * This is not a stop and not a cast: the video is still on that television, so the phone is
     * told where it went BEFORE playback here ends, and a phone that understands the announcement
     * keeps its cast session instead of leaving the viewer with no controls.
     */
    suspend fun externalHandoff(player: InstalledExternalPlayer): Boolean = withContext(Dispatchers.Main.immediate) {
        val media = controller.lastOpenMedia() ?: return@withContext false
        val generation = openGeneration
        val position = runCatching { controller.snapshot().positionSeconds }.getOrDefault(0.0)
        if (generation != openGeneration) return@withContext false
        val handoff = (controller.externalSubtitleHandoff() ?: ExternalPlayerIntentPolicy.HandoffContext()).copy(
            positionMillis = (position * 1_000).toLong().coerceAtLeast(0L),
            headers = media.headers,
        )
        val launched = ExternalPlayerIntentPolicy.launch(
            context = appContext,
            url = media.url,
            title = media.title,
            targetPackage = player.packageName,
            handoff = handoff,
        )
        if (!launched) return@withContext false
        runCatching { controller.announceExternalHandoff(player.label, player.packageName) }
        if (generation != openGeneration) return@withContext true
        controller.stop()
        if (generation == openGeneration) {
            _iptvVod.value = null
            _origin.value = null
            _liveChannel.value = null
        }
        true
    }

    private fun ReceiverEvent.snapshot(): ReceiverSnapshot = when (this) {
        is ReceiverEvent.Play -> snapshot
        is ReceiverEvent.Pause -> snapshot
        is ReceiverEvent.Seek -> snapshot
        is ReceiverEvent.SpeedChanged -> snapshot
        is ReceiverEvent.Stop -> snapshot
        is ReceiverEvent.ExternalHandoff -> snapshot
        is ReceiverEvent.Error -> snapshot
        is ReceiverEvent.VolumeChanged -> snapshot
        is ReceiverEvent.LinkRefreshRequested -> snapshot
    }
}

/** Normalize ISO-639 aliases across provider files; an unknown language is not a preference. */
internal fun canonicalTrackLanguage(value: String?): String? = OriginalAudioLanguage.normalize(value)

/** Allow nearby providers to supply a better match before the first download. */
internal fun Flow<SubtitleSearchResult>.automaticSubtitleCandidates(filename: String?): Flow<List<SubtitleTrack>> = flow {
    coroutineScope {
        val updates = this@automaticSubtitleCandidates.conflate().produceIn(this)
        try {
            var first = true
            for (update in updates) {
                var ranked = SubtitleTrack.rankedForPlayback(update.tracks, filename)
                if (ranked.isEmpty()) continue
                if (first && update.searching) {
                    withTimeoutOrNull(400L) {
                        for (newer in updates) {
                            ranked = SubtitleTrack.rankedForPlayback(newer.tracks, filename)
                            if (!newer.searching) break
                        }
                    }
                }
                first = false
                emit(ranked)
            }
        } finally {
            updates.cancel()
        }
    }
}

internal fun automaticSubtitleTrack(
    tracks: List<ReceiverTrack>, preferredLanguage: String, inherited: EpisodePreference?,
): ReceiverTrack? {
    val full = tracks.filter { it.isSupported && !it.isForced &&
        !SubtitleTrack.isForcedLabel(it.name) && !SubtitleTrack.isForcedLabel(it.language) }
    fun isEnglish(track: ReceiverTrack) = SubtitleTrack.isEnglishLanguage(track.language) ||
        SubtitleTrack.isEnglishLabel(track.name)
    return inherited?.subtitleTrack(full)
        ?: full.firstOrNull { preferredLanguage != "en" && canonicalTrackLanguage(it.language) == preferredLanguage }
        ?: full.firstOrNull(::isEnglish)
}
