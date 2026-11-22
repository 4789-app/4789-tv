package com.fourseveneightnine.tv.client

import android.app.Application
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.fourseveneightnine.tv.client.data.images.PosterRequest
import com.fourseveneightnine.tv.client.data.images.tmdbLogo
import com.fourseveneightnine.tv.catalog.TVLetterboxdListRefresh
import com.fourseveneightnine.tv.catalog.TVLetterboxdClient
import com.fourseveneightnine.tv.catalog.TVLetterboxdIdentity
import com.fourseveneightnine.tv.client.data.addons.AddonHealthStore
import com.fourseveneightnine.tv.client.data.purgeLegacyRecents
import com.fourseveneightnine.tv.client.playback.PlayFlow
import com.fourseveneightnine.tv.client.playback.PlayRequest
import com.fourseveneightnine.tv.client.playback.FavoriteSource
import com.fourseveneightnine.tv.client.playback.SavedSourceFavorite
import com.fourseveneightnine.tv.client.playback.StreamIds
import com.fourseveneightnine.tv.client.playback.DefaultPlayFlow
import com.fourseveneightnine.tv.client.playback.PlaybackSession
import com.fourseveneightnine.tv.client.profiles.HouseholdProfile
import com.fourseveneightnine.tv.client.profiles.OwnerProfile
import com.fourseveneightnine.tv.client.profiles.PinVerification
import com.fourseveneightnine.tv.client.profiles.ProfileCatalog
import com.fourseveneightnine.tv.client.profiles.ProfileLibraryManager
import com.fourseveneightnine.tv.client.profiles.ProfileSession
import com.fourseveneightnine.tv.client.profiles.ProfileSessionState
import com.fourseveneightnine.tv.client.profiles.ProfileStore
import com.fourseveneightnine.tv.client.profiles.ProfileSwitchPolicy
import com.fourseveneightnine.tv.client.data.addons.AddonEndpoint
import com.fourseveneightnine.tv.client.data.settings.AddonSource
import com.fourseveneightnine.tv.client.data.addons.AddonRegistry
import com.fourseveneightnine.tv.client.data.addons.InMemoryAddonHealthStore
import com.fourseveneightnine.tv.client.data.addons.StremioClient
import com.fourseveneightnine.tv.client.data.catalog.SnapshotStore
import com.fourseveneightnine.tv.client.data.catalog.SnapshotSource
import com.fourseveneightnine.tv.client.data.catalog.SnapshotState
import com.fourseveneightnine.tv.client.data.catalog.TamilMVCatalogRepository
import com.fourseveneightnine.tv.client.data.library.JobState
import com.fourseveneightnine.tv.client.data.images.FanartPosters
import com.fourseveneightnine.tv.client.data.images.ImageLoaderFactory
import com.fourseveneightnine.tv.client.iptv.IptvRepository
import com.fourseveneightnine.tv.client.data.library.LibraryDatabase
import com.fourseveneightnine.tv.client.data.library.LibraryRepository
import com.fourseveneightnine.tv.client.data.meta.MetaRepository
import com.fourseveneightnine.tv.client.data.meta.Meta
import com.fourseveneightnine.tv.client.data.meta.RatingsRepository
import com.fourseveneightnine.tv.client.data.refresh.RefreshScheduler
import com.fourseveneightnine.tv.client.data.refresh.asJobSink
import com.fourseveneightnine.tv.client.data.settings.SettingsDocument
import com.fourseveneightnine.tv.client.data.streams.PlaybackRules
import com.fourseveneightnine.tv.client.data.streams.StreamSearch
import com.fourseveneightnine.tv.client.data.streams.StreamRow
import com.fourseveneightnine.tv.client.data.streams.debrid.DebridResolver
import com.fourseveneightnine.tv.client.home.TvHomeChannelPublisher
import com.fourseveneightnine.tv.client.ui.screens.home.HomeCard
import com.fourseveneightnine.tv.client.ui.screens.settings.PreferenceAddonRegistryStore
import com.fourseveneightnine.tv.settings.StoredTVSettingsState
import com.fourseveneightnine.tv.settings.TVSettingsPairingState
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics
import com.fourseveneightnine.tv.startup.sourceDiagnosticDetails
import com.fourseveneightnine.tv.player.ReceiverPlaybackPhase
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Everything the client screens read that is not the receiver: the settings document, add-ons,
 * metadata, streams, the library database, catalog snapshots, images and the refresh scheduler.
 *
 * The settings-derived services are rebuilt as one unit whenever the encrypted document changes
 * (a new pairing, a trusted sync). Screens observe [services] and re-read on change. The library,
 * the snapshot store, the image loader and the scheduler live for the process.
 */
internal class ClientGraph(private val app: Application, private val graph: AppGraph) {
    fun savedCatalogItem(type: String, id: String): com.fourseveneightnine.tv.client.data.catalog.CatalogItem? =
        if (!id.startsWith("saved:")) null else snapshots.shelves().value.asSequence()
            .filter { it.kind == com.fourseveneightnine.tv.client.data.catalog.ShelfKind.LIBRARY ||
                it.kind == com.fourseveneightnine.tv.client.data.catalog.ShelfKind.TAMILMV_POPULAR ||
                it.kind == com.fourseveneightnine.tv.client.data.catalog.ShelfKind.TAMILMV_RECENT }
            .flatMap { it.items.asSequence() }
            .firstOrNull { it.canonicalId == id && it.mediaType == type }

    suspend fun subtitleVideoId(request: PlayRequest, data: DataServices): String? {
        if (request.type == "series" && (request.season == null || request.episode == null)) return null
        val saved = savedCatalogItem(request.type, request.id)
        val lookupId = saved?.imdbId ?: saved?.tmdbId?.let { "tmdb:$it" } ?: request.id
        val directId = AddonEndpoint.identifiers(lookupId, null, null).firstOrNull()
        val id = directId?.takeIf { it.startsWith("tt") } ?: run {
            val meta = withTimeoutOrNull(4_000L) { data.meta.meta(request.type, lookupId) }
            AddonEndpoint.identifiers(lookupId, meta?.imdbID, meta?.tmdbID).firstOrNull()
        } ?: return null
        return AddonEndpoint.episodeIdentifier(id, request.season, request.episode)
    }

    /** Services that depend on the phone's settings document. Null until the document is loaded. */
    class DataServices(
        val document: SettingsDocument,
        val health: AddonHealthStore,
        val client: StremioClient,
        val registry: AddonRegistry,
        val meta: MetaRepository,
        val ratings: RatingsRepository,
        val search: StreamSearch,
        val debrid: DebridResolver,
    ) {
        val rules: PlaybackRules get() = document.playbackRules
    }

    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val einthusan by lazy { com.fourseveneightnine.tv.catalog.EinthusanProvider(okHttp) }

    private val fanart = MutableStateFlow<FanartPosters?>(null)
    private val fanartSettingsLock = Mutex()

    val fanartConfigured: StateFlow<Boolean> = fanart.map { it != null }
        .stateIn(scope, SharingStarted.Eagerly, false)

    init {
        scope.launch(Dispatchers.IO) {
            fanartSettingsLock.withLock {
                if (fanart.value == null) fanart.value = graph.settingsStore.loadFanartKey()?.let { FanartPosters(okHttp, it) }
            }
            if (!runCatching { purgeLegacyRecents(app) }.getOrDefault(false)) {
                ReceiverDiagnostics.record("recents.legacy.purge_failed")
            }
        }
    }

    val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .eventListenerFactory(ReceiverDiagnostics.networkEvents("client"))
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(24, TimeUnit.SECONDS)
            .build()
    }

    suspend fun setFanartKey(key: String?): Boolean = withContext(Dispatchers.IO) {
        val value = key?.trim()
        if (value != null && (value.isEmpty() || value.length > 128 || value.any { it !in '!'..'~' })) {
            return@withContext false
        }
        fanartSettingsLock.withLock {
            if (!graph.settingsStore.saveFanartKey(value)) return@withLock false
            fanart.value = value?.let { FanartPosters(okHttp, it) }
            true
        }
    }

    val letterboxd: TVLetterboxdClient by lazy { TVLetterboxdClient(okHttp) }

    val publicLists by lazy {
        TVLetterboxdListRefresh(File(app.filesDir, "letterboxd-public-lists"), scope, letterboxd::films,
            runCatalogWork = { work -> scheduler.runBulkWork("letterboxd-lists", work) })
    }

    private val mutablePhoneLetterboxd = MutableStateFlow<PhoneLetterboxdCatalog?>(null)

    /** Receiver-wide rows only: refresh jobs and add-on health never follow a household profile. */
    val globalLibrary: LibraryRepository by lazy { LibraryRepository(LibraryDatabase.create(app)) }

    val profileStore: ProfileStore by lazy { ProfileStore(app) }
    val profileSession: ProfileSession by lazy { ProfileSession(profileStore) }
    private val profileLibraryManager: ProfileLibraryManager by lazy { ProfileLibraryManager(app, profileStore) }
    private val profileLibraries = LinkedHashMap<UUID, ProfileLibrary>()
    private val mutableProfiles = MutableStateFlow<ProfileCatalog?>(null)
    val profiles: StateFlow<ProfileCatalog?> = mutableProfiles.asStateFlow()
    private val mutableProfilePinChecks = MutableStateFlow(0)
    val profilePinChecks: StateFlow<Int> = mutableProfilePinChecks.asStateFlow()

    @Volatile
    private var unlockedProfileId: UUID? = null

    /** Personal rows are unavailable until the full-screen profile gate has unlocked one profile. */
    val activeLibrary: LibraryRepository
        get() = repositoryFor(checkNotNull(unlockedProfileId) { "profile_not_unlocked" })

    val imageLoader: ImageLoader by lazy {
        ImageLoaderFactory.create(app, okHttp, File(app.cacheDir, "coil"))
    }

    private var detailWarmJob: Job? = null
    private var detailWarmKey: String? = null

    /** One focused title at a time. Detail can join this work after the grid leaves composition. */
    @Synchronized
    fun warmDetail(type: String, id: String, tmdbID: Int?, backdropWidthPixels: Int) {
        if (id.startsWith(TVLetterboxdClient.FILM_ID_PREFIX)) return
        val key = "$type:$id"
        if (detailWarmKey == key && detailWarmJob?.isActive == true) return
        detailWarmJob?.cancel()
        detailWarmKey = key
        detailWarmJob = scope.launch {
            val data = services.value ?: return@launch
            val saved = savedCatalogItem(type, id)
            val lookup = saved?.imdbId ?: saved?.tmdbId?.let { "tmdb:$it" } ?: id.substringAfter("::")
            if (id.startsWith("saved:") && saved?.imdbId == null && saved?.tmdbId == null) return@launch
            val queued = mutableSetOf<String>()
            fun warm(meta: Meta) {
                fun enqueue(request: ImageRequest) {
                    val url = request.data as? String ?: return
                    if (queued.size >= 12 || !queued.add(url)) return
                    launch { imageLoader.execute(request) }
                }
                meta.backdrop?.let {
                    enqueue(PosterRequest.backdrop(it, backdropWidthPixels).toImageRequest(app))
                }
                meta.logo?.let {
                    val width = (backdropWidthPixels * 528 / 1920).coerceAtLeast(1)
                    enqueue(ImageRequest.Builder(app).data(it).tmdbLogo()
                        .size(width, (width * 134 / 528).coerceAtLeast(1)).crossfade(false).build())
                }
                meta.cast.take(6).mapNotNull { it.photo }.forEach { photo ->
                    val side = (backdropWidthPixels * 160 / 1920).coerceAtLeast(1)
                    enqueue(ImageRequest.Builder(app).data(photo).size(side, side).crossfade(false).build())
                }
            }
            try {
                data.meta.meta(type, lookup, saved?.tmdbId ?: tmdbID, onBase = ::warm)?.let(::warm)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Foreground Detail owns any visible error. */ }
        }
    }

    private val posterLookupGate = Semaphore(4)

    suspend fun resolveLetterboxdIdentity(card: HomeCard): TVLetterboxdIdentity? =
        card.letterboxdIdentity ?: letterboxd.filmDetails(card.id.removePrefix(TVLetterboxdClient.FILM_ID_PREFIX))
            .resolveIdentity(card.type, card.posterLookupId)

    suspend fun resolveLetterboxdCard(card: HomeCard): HomeCard? =
        resolveLetterboxdIdentity(card)?.let { card.copy(id = it.id, type = it.type) }

    /** Use the verified identity for a small artwork request before trying full metadata. */
    suspend fun recoverPoster(type: String, id: String, failedUrl: String?, tmdbID: Int?,
        failedUrls: Set<String> = setOfNotNull(failedUrl)): String? {
        val meta = services.value?.meta
        if (id.startsWith(TVLetterboxdClient.FILM_ID_PREFIX)) {
            val film = try {
                letterboxd.filmDetails(id.removePrefix(TVLetterboxdClient.FILM_ID_PREFIX))
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { null } ?: return null
            film.poster?.takeUnless { it in failedUrls }?.let { return it }
            val identity = film.identity ?: return null
            return recoverPoster(identity.type, identity.id, failedUrl, identity.id.substringAfter(':').toIntOrNull(), failedUrls)
        }
        val saved = savedCatalogItem(type, id)
        val lookup = saved?.imdbId ?: saved?.tmdbId?.let { "tmdb:$it" } ?: id.substringAfter("::")
        if (lookup != id) return recoverPoster(type, lookup, failedUrl, saved?.tmdbId ?: tmdbID, failedUrls)
        fun available(values: List<String>): String? = values.firstOrNull { it.isNotBlank() && it !in failedUrls }
        val knownID = tmdbID ?: id.takeIf { it.startsWith("tmdb:") }?.substringAfterLast(':')?.toIntOrNull()
        suspend fun tmdbPoster(): String? {
            if (knownID == null || meta == null) return null
            val preview = meta.peekTmdbPreview(type, knownID)
                ?: posterLookupGate.withPermit { meta.tmdbPreview(type, knownID) }
            return preview?.posterCandidates?.let(::available)
        }
        val failedLetterboxdPortrait = TVLetterboxdClient.directPortrait(failedUrl) != null
        if (failedLetterboxdPortrait) tmdbPoster()?.let { return it }
        meta?.peek(type, id)?.posterCandidates?.let(::available)?.let { return it }
        fanart.value?.let { provider ->
            val posters = withContext(Dispatchers.Default) {
                posterLookupGate.withPermit { provider.posters(type, id, knownID) }
            }
            available(posters)?.let { return it }
        }
        if (!failedLetterboxdPortrait) tmdbPoster()?.let { return it }
        if (id.startsWith("tt")) {
            val url = "https://images.metahub.space/poster/medium/$id/img"
            if (url !in failedUrls) return url
        }
        if (meta == null) return null
        return posterLookupGate.withPermit {
            meta.meta(type, id)?.posterCandidates?.let(::available)
        }
    }

    /** IPTV accounts and cached channels belong to the TV and survive a disconnected phone. */
    val iptv: IptvRepository by lazy { IptvRepository(app, okHttp, scope) }

    private val mutableServices = MutableStateFlow<DataServices?>(null)

    /** Rebuilt whenever the settings document changes. Null before the first load. */
    val services: StateFlow<DataServices?> = mutableServices.asStateFlow()

    val snapshots: SnapshotStore by lazy {
        SnapshotStore(
            filesDir = app.filesDir,
            okHttp = okHttp,
            tokenProvider = { services.value?.document?.catalogServerToken },
            letterboxdUsernames = { services.value?.document?.letterboxdUsernames.orEmpty() },
        )
    }

    val offlineLetterboxd by lazy { OfflineLetterboxd(app, scope, okHttp) }
    private val publicLetterboxdMetadata by lazy {
        val client = StremioClient(okHttp)
        val registry = AddonRegistry(SettingsDocument.parse("{}"), client)
        MetaRepository(registry, client, cacheDir = File(app.cacheDir, "letterboxd-metadata"), okHttp = okHttp)
    }
    val letterboxdImporter by lazy {
        OfflineLetterboxdImporter(scope, offlineLetterboxd, letterboxd::importPage, letterboxd::filmDetails) { type, id, tmdb ->
            (services.value?.meta ?: publicLetterboxdMetadata).meta(type, id, tmdb)
        }
    }


    val collectionCatalog by lazy {
        combine(snapshots.shelves(), mutablePhoneLetterboxd, ::mergeCollectionCatalog)
            .stateIn(scope, SharingStarted.Eagerly,
                mergeCollectionCatalog(snapshots.shelves().value, mutablePhoneLetterboxd.value))
    }

    val collectionShelves by lazy {
        collectionCatalog.map { it.shelves }
            .stateIn(scope, SharingStarted.Eagerly, collectionCatalog.value.shelves)
    }

    private val legacyCatalogBridge: LegacyCatalogBridge by lazy {
        LegacyCatalogBridge(graph.catalogCache, snapshots)
    }

    val tamilCatalog: TamilMVCatalogRepository by lazy {
        TamilMVCatalogRepository(app.filesDir, okHttp, tmdbKey = { services.value?.document?.tmdbAPIKey })
    }

    // The receiver owns collection refreshes so a screen can stop waiting without discarding them.
    suspend fun refreshReceiverCollections(force: Boolean = false) = scope.async {
        scheduler.runBulkWork("receiver") { refreshReceiverCollectionsNow(force) }
    }.await()

    private suspend fun refreshReceiverCollectionsNow(force: Boolean) {
        scheduler.refreshJob("TamilMV Collection") {
            tamilCatalog.refresh(force, snapshots.shelves().value.flatMap { it.items })
            val message = tamilCatalog.state.value.message
            (if (message == null) JobState.OK else JobState.FAILED) to message
        }
        services.value?.takeIf { !it.document.torboxAPIKey.isNullOrBlank() }?.let { ready ->
            scheduler.refreshJob("My Library") {
                ready.debrid.refreshTorboxLibrary(force)
                val message = ready.debrid.libraryState.value.message
                (if (message == null) JobState.OK else JobState.FAILED) to message
            }
        }
    }

    suspend fun resolveCatalogItem(type: String, id: String): com.fourseveneightnine.tv.client.data.catalog.CatalogItem? {
        // A refresh can publish receiver rows before the shared snapshot collector catches up.
        val receiverItem = if (id.startsWith("saved:tamilmv:topic:")) {
            tamilCatalog.state.value.items.firstOrNull { it.canonicalId == id && it.mediaType == type }
        } else null
        val saved = receiverItem ?: savedCatalogItem(type, id) ?: return null
        return if (saved.sourcePageURL == null) saved else tamilCatalog.sources(saved)
    }

    val scheduler: RefreshScheduler by lazy {
        RefreshScheduler(
            scope = scope,
            snapshotStore = snapshots,
            addonCatalogRefresher = { force -> services.value?.registry?.refresh(force = force) },
            jobs = globalLibrary.asJobSink(),
            receiverCatalogRefresher = ::refreshReceiverCollectionsNow,
            playbackBusy = graph.playback.phase.map { phase ->
                when (phase) {
                    is ReceiverPlaybackPhase.Opening, ReceiverPlaybackPhase.Buffering,
                    ReceiverPlaybackPhase.Playing, ReceiverPlaybackPhase.Paused -> true
                    else -> false
                }
            },
            diagnostic = { job, stage -> ReceiverDiagnostics.record("catalog.bulk.$stage", "job=$job") },
        )
    }

    fun publishTvHomeChannel(): Job = scope.launch(Dispatchers.IO) {
        if (started) snapshotsHydrated.await()
        scheduler.runBulkWork("tv-home-channel") {
            TvHomeChannelPublisher.reconcile(app, snapshotsHydrated.isCompleted)
        }
    }

    /** Replaced by the streams agent with `DefaultPlayFlow(this, graph)`. */
    val playFlow: PlayFlow by lazy { DefaultPlayFlow(this, graph) }

    /**
     * Selects a profile without allowing an in-flight local title to change library ownership.
     * Selecting the already active profile is allowed after lifecycle locking so the viewer can
     * return to that title; a phone cast is intentionally outside this household-profile rule.
     */
    @Synchronized
    fun selectProfile(profileId: UUID): PinVerification {
        if (profilePinChecks.value > 0) return PinVerification.Required
        val catalog = mutableProfiles.value ?: return PinVerification.Required
        val selected = catalog.selectedProfileId
        if (!ProfileSwitchPolicy.allowed(selected, profileId, localPlaybackActive())) {
            return PinVerification.PlaybackActive
        }
        unlockedProfileId = null
        return profileSession.select(profileId, catalog).also { result ->
            refreshProfiles()
            if (result == PinVerification.NotRequired) activate(profileId)
        }
    }

    @Synchronized
    fun unlockProfile(profileId: UUID, pin: CharArray): PinVerification =
        profileSession.unlock(profileId, pin).also { result ->
            refreshProfiles()
            if (result == PinVerification.Verified || result == PinVerification.NotRequired) {
                activate(profileId)
            }
        }

    /** Verifies a protected profile for management without switching the active library/session. */
    fun verifyProfilePin(profileId: UUID, pin: CharArray): PinVerification {
        mutableProfilePinChecks.update { it + 1 }
        try {
            return profileStore.verifyPin(profileId, pin)
        } finally {
            mutableProfilePinChecks.update { it - 1 }
        }
    }

    suspend fun verifyProfilePinForGate(profileId: UUID, pin: CharArray): PinVerification {
        val startedAt = System.nanoTime()
        mutableProfilePinChecks.update { it + 1 }
        try {
            return withContext(Dispatchers.Default) { profileStore.verifyPin(profileId, pin) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ReceiverDiagnostics.failure("profile.pin.failed", error)
            throw error
        } finally {
            ReceiverDiagnostics.record("profile.pin.finished", "elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000}")
            mutableProfilePinChecks.update { it - 1 }
        }
    }

    @Synchronized
    fun finishProfileUnlock(profileId: UUID, result: PinVerification, expectedEpoch: Long): PinVerification {
        val catalog = mutableProfiles.value ?: return PinVerification.Required
        if (catalog.selectedProfileId != profileId || catalog.profiles.none { it.id == profileId }) {
            return PinVerification.Required
        }
        return profileSession.finishUnlock(profileId, result, expectedEpoch).also { applied ->
            if (applied == PinVerification.Verified || applied == PinVerification.NotRequired) activate(profileId)
        }
    }

    @Synchronized
    fun lockProfile() {
        unlockedProfileId = null
        val catalog = mutableProfiles.value ?: return
        if (catalog.profiles.size == 1 && !catalog.profiles.single().hasPin) {
            // A single public profile stays selected on Home. Emitting Selecting here would
            // revoke the active launcher picture in the middle of this same transition.
            profileSession.load(catalog)
            activate(catalog.profiles.single().id)
        } else {
            profileSession.lock(catalog.selectedProfileId)
        }
    }

    @Synchronized
    fun createProfile(name: String): HouseholdProfile {
        val profile = profileStore.create(name, DEFAULT_PROFILE_AVATARS[profileStore.load().profiles.size % DEFAULT_PROFILE_AVATARS.size])
        refreshProfiles()
        return profile
    }

    @Synchronized
    fun renameProfile(profileId: UUID, name: String): HouseholdProfile {
        val current = profileStore.load().profiles.first { it.id == profileId }
        return profileStore.update(profileId, name, current.avatarKey).also { refreshProfiles() }
    }

    @Synchronized
    fun setProfilePin(profileId: UUID, pin: CharArray?) {
        profileStore.setPin(profileId, pin)
        refreshProfiles()
    }

    @Synchronized
    fun deleteProfile(profileId: UUID): Boolean {
        if (profileId == activeProfileId() || localPlaybackActive()) return false
        profileLibraries.remove(profileId)?.database?.close()
        return profileLibraryManager.deleteProfile(profileId).also { refreshProfiles() }
    }

    /** A launcher grant exposes a shelf from the selected, unprotected profile only. */
    @Synchronized
    fun launcherLibrary(profileId: UUID): LibraryRepository? {
        val catalog = profileStore.load()
        if (catalog.selectedProfileId != profileId) return null
        return catalog.profiles.firstOrNull { it.id == profileId && !it.hasPin }
            ?.let { repositoryFor(profileId) }
    }

    fun activeProfileId(): UUID? = (profileSession.state.value as? ProfileSessionState.Unlocked)?.profileId

    private val sourceFavoritePreferences by lazy {
        app.getSharedPreferences("source-favorites-v1", android.content.Context.MODE_PRIVATE)
    }
    private val sourceFavoriteGate = Mutex()

    fun favoriteSource(request: PlayRequest, meta: Meta? = null): FavoriteSource? {
        val profile = activeProfileId() ?: return null
        val metadata = meta ?: services.value?.meta?.peek(request.type, request.id.substringAfter("::"))
        return StreamIds.favoriteScopes(request, metadata).firstNotNullOfOrNull { scope ->
            sourceFavorite(profile, scope)?.source
        }
    }

    /** True for a saved favorite, false for a removal, null when the write failed. */
    suspend fun toggleFavoriteSource(request: PlayRequest, row: StreamRow): Boolean? {
        if (com.fourseveneightnine.tv.catalog.EinthusanSource.parse(row.url) != null) return null
        val profile = activeProfileId() ?: return null
        val metadata = services.value?.meta?.peek(request.type, request.id.substringAfter("::"))
        val scopes = StreamIds.favoriteScopes(request, metadata)
        if (!row.playable || scopes.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            sourceFavoriteGate.withLock {
                if (activeProfileId() != profile) return@withLock null
                val previous = scopes.mapNotNull { sourceFavorite(profile, it) }
                val save = previous.firstOrNull()?.source?.matches(row) != true
                val allScopes = (scopes + previous.flatMap(SavedSourceFavorite::scopes)).distinct()
                val editor = sourceFavoritePreferences.edit()
                if (save) {
                    val record = Json.encodeToString(SavedSourceFavorite(allScopes, FavoriteSource.from(row)))
                    allScopes.forEach { editor.putString("$profile.$it", record) }
                } else {
                    allScopes.forEach { editor.remove("$profile.$it") }
                }
                save.takeIf { editor.commit() }
            }
        }
    }

    private fun sourceFavorite(profile: UUID, scope: String): SavedSourceFavorite? =
        sourceFavoritePreferences.getString("$profile.$scope", null)?.let { record ->
            runCatching { Json.decodeFromString<SavedSourceFavorite>(record) }.getOrNull()
        }

    fun localPlaybackActive(): Boolean {
        if (graph.playback.origin.value != PlaybackSession.Origin.Local) return false
        return when (graph.playback.phase.value) {
            is ReceiverPlaybackPhase.Idle,
            is ReceiverPlaybackPhase.Ended,
            is ReceiverPlaybackPhase.Stopped,
            is ReceiverPlaybackPhase.Error,
            -> false

            else -> true
        }
    }

    private val reloadGate = Mutex()
    private val snapshotsHydrated = CompletableDeferred<Unit>()

    suspend fun addDirectAddon(typed: String): String = withContext(Dispatchers.IO) {
        val url = requireNotNull(AddonEndpoint.installURL(typed))
        val key = AddonEndpoint.normalize(url)
        if (key == AddonEndpoint.normalize(StremioClient.CINEMETA_MANIFEST)) return@withContext "Cinemeta is built in."
        val current = services.value
        val existing = current?.registry?.addons?.value.orEmpty()
        require(existing.any { it.key == key } || existing.size < 257) { "addon_limit" }
        val manifest = (current?.client ?: StremioClient()).manifest(url)
        val name = manifest.name.take(80).ifBlank { "Add-on" }
        graph.pairing.addDirectAddon(AddonSource(name, url, true))
        "Added $name."
    }

    /** Reads the encrypted document off the main thread and rebuilds the settings services. */
    fun reloadSettings() {
        scope.launch { reloadSettingsNow() }
    }

    suspend fun reloadSettingsNow() = reloadGate.withLock {
        val raw = withContext(Dispatchers.IO) {
            (graph.settingsStore.load() as? StoredTVSettingsState.Available)?.document?.rawJson
        }
        if (raw == null) {
            mutableServices.value = null
            ReceiverDiagnostics.record("client.settings", "none")
            return@withLock
        }
        val document = runCatching { SettingsDocument.parse(raw) }
            .onFailure { ReceiverDiagnostics.record("client.settings.parse", it::class.java.simpleName) }
            .getOrNull() ?: return@withLock
        if (mutableServices.value?.document?.sameConfiguration(document) == true) return@withLock
        val health = InMemoryAddonHealthStore()
        val benchmark = if (com.fourseveneightnine.tv.BuildConfig.BENCHMARK_LOGGING) ReceiverDiagnostics::recordBenchmark else null
        val client = StremioClient(okHttp, cacheDir = File(app.cacheDir, "addons"), benchmark = benchmark)
        val registry = AddonRegistry(
            document = document,
            client = client,
            health = health,
            store = PreferenceAddonRegistryStore(graph.presentationPreferences, graph.settingsStore),
        )
        val debrid = DebridResolver(
            torboxKey = document.torboxAPIKey,
            rdKey = document.realDebridAPIKey,
            okHttp = okHttp,
            rules = document.playbackRules,
            diagnostics = { row, expected, detail ->
                ReceiverDiagnostics.record("source.inspect", sourceDiagnosticDetails(row, expected, detail))
            },
        )
        val built = DataServices(
            document = document,
            health = health,
            client = client,
            registry = registry,
            meta = MetaRepository(registry, client, document.tmdbAPIKey, File(app.cacheDir, "meta"), okHttp),
            ratings = RatingsRepository(document.mdbListAPIKey, okHttp),
            search = StreamSearch(registry, client, torbox = document.torboxAPIKey?.takeIf(String::isNotBlank)?.let {
                com.fourseveneightnine.tv.client.data.streams.debrid.TorboxResolver(it, okHttp)
            }, phoneRows = graph.pairing::sourceRows, diagnostics = { addon, expected, detail ->
                ReceiverDiagnostics.record("source.search", sourceDiagnosticDetails(null, expected, detail, addon))
            }, validateSource = debrid::verifiedSource,
                benchmark = benchmark),
            debrid = debrid,
        )
        mutableServices.value = built
        ReceiverDiagnostics.record("client.settings", "addons=${document.addonSources.size}")
        scope.launch {
            runCatching { registry.refresh() }.onSuccess {
                registry.addons.value.filter { it.enabled }.forEach { addon ->
                    val host = runCatching { java.net.URI(addon.manifestURL).host }.getOrNull().orEmpty()
                    val result = addon.health.lastError ?: if (addon.manifest == null) "missing_manifest" else "ok"
                    ReceiverDiagnostics.record("source.manifest", sourceDiagnosticDetails(null, null,
                        "host=$host result=$result stream=${addon.supports("stream")}", addon.name))
                }
            }
        }
    }

    /**
     * Starts the process-level plumbing: the first settings load, snapshot hydration, and the
     * settings-change subscription. Safe to call more than once; only the first call does work.
     */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch {
            val catalog = withContext(Dispatchers.IO) { profileStore.load() }
            withContext(Dispatchers.Main.immediate) { initializeProfiles(catalog) }
        }
        reloadSettings()
        scope.launch {
            scheduler.runBulkWork("initial-tamilmv") { tamilCatalog.hydrate() }
            tamilCatalog.state
                .map { it.items to it.updatedAtMillis }
                .distinctUntilChanged()
                .collect { (items, updatedAt) -> snapshots.setReceiverTamilMV(items, updatedAt) }
        }
        scope.launch {
            try {
                scheduler.runBulkWork("initial-snapshots") { snapshots.hydrate() }
            } finally {
                snapshotsHydrated.complete(Unit)
            }
            runCatching { importLegacyCatalogIfConfigured() }
        }
        scope.launch {
            snapshots.status().map { it.of(SnapshotSource.PUBLIC_TMDB) }
                .distinctUntilChanged { old, new ->
                    old?.generation == new?.generation && old?.complete == new?.complete
                }
                .collect { status ->
                    if (status?.state == SnapshotState.READY && status.complete && status.itemCount > 0) {
                        publishTvHomeChannel().join()
                    }
                }
        }
        scope.launch {
            graph.pairing.state
                .collect { state ->
                    when (state) {
                        is TVSettingsPairingState.Saved -> {
                            reloadSettingsNow()
                            runCatching { importLegacyCatalogIfConfigured() }
                        }
                        is TVSettingsPairingState.Error -> {
                            if (state.settingsRemoved) runCatching { purgeUnconfiguredPrivateSetup() }
                        }
                        TVSettingsPairingState.Idle -> {
                            runCatching { purgeUnconfiguredPrivateSetup() }
                        }
                        else -> Unit
                    }
                }
        }
        scope.launch {
            graph.pairing.settingsApplied.collect {
                reloadSettingsNow()
                runCatching { importLegacyCatalogIfConfigured() }
                scheduler.onTrustedSync()
            }
        }
        scope.launch {
            graph.pairing.catalogApplied.collect {
                runCatching { importLegacyCatalogIfConfigured() }
            }
        }
        scope.launch {
            graph.pairing.letterboxdApplied.collect {
                scheduler.runBulkWork("phone-letterboxd") {
                    reloadGate.withLock {
                        mutablePhoneLetterboxd.value = withContext(Dispatchers.IO) {
                            if (graph.settingsStore.load() is StoredTVSettingsState.Available) graph.letterboxdCache.load()?.toPhoneLetterboxdCatalog()
                            else null
                        }
                    }
                }
            }
        }
        scheduler.schedulePeriodicRefresh(app)
    }

    @Volatile
    private var started = false

    /** Completes before a removed setup can be replaced by another approved phone. */
    suspend fun purgeUnconfiguredPrivateSetup() {
        start()
        val missing = withContext(Dispatchers.IO) {
            graph.settingsStore.load() == StoredTVSettingsState.Missing
        }
        if (!missing) return
        // Hydration can wait for playback. Do not hold the settings lock while it waits.
        snapshotsHydrated.await()
        reloadGate.withLock {
            // Recheck after acquiring the same gate as reload/import. A delayed old state must
            // never clear a newly approved setup or let a captured old token publish afterwards.
            val missing = withContext(Dispatchers.IO) {
                graph.settingsStore.load() == StoredTVSettingsState.Missing
            }
            if (!missing) return@withLock
            mutableServices.value = null
            mutablePhoneLetterboxd.value = null
            publicLists.clear()
            snapshots.clear(SnapshotSource.PRIVATE_CATALOG)
            ReceiverDiagnostics.record("client.settings", "cleared")
        }
    }

    private suspend fun importLegacyCatalogIfConfigured() {
        snapshotsHydrated.await()
        scheduler.runBulkWork("import-catalog") {
            reloadGate.withLock {
                val configured = withContext(Dispatchers.IO) {
                    graph.settingsStore.load() is StoredTVSettingsState.Available
                }
                if (configured) legacyCatalogBridge.importIfNewer()
            }
        }
    }

    @Synchronized
    private fun initializeProfiles(catalog: ProfileCatalog) {
        profileSession.load(catalog)
        mutableProfiles.value = catalog
        (profileSession.state.value as? ProfileSessionState.Unlocked)?.let { activate(it.profileId) }
    }

    @Synchronized
    private fun refreshProfiles() {
        mutableProfiles.value = profileStore.load()
    }

    @Synchronized
    private fun activate(profileId: UUID) {
        unlockedProfileId = profileId
        scope.launch { runCatching { repositoryFor(profileId).ensureSystemCollections() } }
    }

    @Synchronized
    private fun repositoryFor(profileId: UUID): LibraryRepository =
        if (profileId == OwnerProfile.id) {
            globalLibrary
        } else {
            profileLibraries.getOrPut(profileId) {
                val database = profileLibraryManager.open(profileId)
                ProfileLibrary(database, LibraryRepository(database))
            }.repository
        }

    private data class ProfileLibrary(
        val database: LibraryDatabase,
        val repository: LibraryRepository,
    )

    private companion object {
        val DEFAULT_PROFILE_AVATARS = listOf("violet", "cyan", "amber", "rose", "green")
    }
}
