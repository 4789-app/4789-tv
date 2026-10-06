package com.fourseveneightnine.tv.client

import android.app.Application
import coil3.ImageLoader
import com.fourseveneightnine.tv.client.data.addons.AddonHealthStore
import com.fourseveneightnine.tv.client.playback.PlayFlow
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
import com.fourseveneightnine.tv.client.data.addons.AddonRegistry
import com.fourseveneightnine.tv.client.data.addons.InMemoryAddonHealthStore
import com.fourseveneightnine.tv.client.data.addons.StremioClient
import com.fourseveneightnine.tv.client.data.catalog.SnapshotStore
import com.fourseveneightnine.tv.client.data.catalog.SnapshotSource
import com.fourseveneightnine.tv.client.data.images.ImageLoaderFactory
import com.fourseveneightnine.tv.client.iptv.IptvRepository
import com.fourseveneightnine.tv.client.data.library.LibraryDatabase
import com.fourseveneightnine.tv.client.data.library.LibraryRepository
import com.fourseveneightnine.tv.client.data.meta.MetaRepository
import com.fourseveneightnine.tv.client.data.meta.RatingsRepository
import com.fourseveneightnine.tv.client.data.refresh.RefreshScheduler
import com.fourseveneightnine.tv.client.data.refresh.asJobSink
import com.fourseveneightnine.tv.client.data.settings.SettingsDocument
import com.fourseveneightnine.tv.client.data.streams.PlaybackRules
import com.fourseveneightnine.tv.client.data.streams.StreamSearch
import com.fourseveneightnine.tv.client.data.streams.debrid.DebridResolver
import com.fourseveneightnine.tv.client.ui.screens.settings.PreferenceAddonRegistryStore
import com.fourseveneightnine.tv.settings.StoredTVSettingsState
import com.fourseveneightnine.tv.settings.TVSettingsPairingState
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics
import com.fourseveneightnine.tv.player.ReceiverPlaybackPhase
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Everything the client screens read that is not the receiver: the settings document, add-ons,
 * metadata, streams, the library database, catalog snapshots, images and the refresh scheduler.
 *
 * The settings-derived services are rebuilt as one unit whenever the encrypted document changes
 * (a new pairing, a trusted sync). Screens observe [services] and re-read on change. The library,
 * the snapshot store, the image loader and the scheduler live for the process.
 */
internal class ClientGraph(private val app: Application, private val graph: AppGraph) {

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

    val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** Receiver-wide rows only: refresh jobs and add-on health never follow a household profile. */
    val globalLibrary: LibraryRepository by lazy { LibraryRepository(LibraryDatabase.create(app)) }

    val profileStore: ProfileStore by lazy { ProfileStore(app) }
    val profileSession: ProfileSession by lazy { ProfileSession(profileStore) }
    private val profileLibraryManager: ProfileLibraryManager by lazy { ProfileLibraryManager(app, profileStore) }
    private val profileLibraries = LinkedHashMap<UUID, ProfileLibrary>()
    private val mutableProfiles = MutableStateFlow<ProfileCatalog?>(null)
    val profiles: StateFlow<ProfileCatalog?> = mutableProfiles.asStateFlow()

    @Volatile
    private var unlockedProfileId: UUID? = null

    /** Personal rows are unavailable until the full-screen profile gate has unlocked one profile. */
    val activeLibrary: LibraryRepository
        get() = repositoryFor(checkNotNull(unlockedProfileId) { "profile_not_unlocked" })

    val imageLoader: ImageLoader by lazy {
        ImageLoaderFactory.create(app, okHttp, File(app.cacheDir, "coil"))
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

    private val legacyCatalogBridge: LegacyCatalogBridge by lazy {
        LegacyCatalogBridge(graph.catalogCache, snapshots)
    }

    val scheduler: RefreshScheduler by lazy {
        RefreshScheduler(
            scope = scope,
            snapshotStore = snapshots,
            addonCatalogRefresher = { services.value?.registry?.refresh() },
            jobs = globalLibrary.asJobSink(),
        )
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
        val selected = mutableProfiles.value?.selectedProfileId
        if (!ProfileSwitchPolicy.allowed(selected, profileId, localPlaybackActive())) {
            return PinVerification.PlaybackActive
        }
        unlockedProfileId = null
        return profileSession.select(profileId).also { result ->
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
    fun verifyProfilePin(profileId: UUID, pin: CharArray): PinVerification =
        profileStore.verifyPin(profileId, pin)

    @Synchronized
    fun lockProfile() {
        unlockedProfileId = null
        profileSession.lock()
        val catalog = profileStore.load()
        mutableProfiles.value = catalog
        if (catalog.profiles.size == 1 && !catalog.profiles.single().hasPin) {
            profileSession.load()
            activate(catalog.profiles.single().id)
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

    fun activeProfileId(): UUID? = (profileSession.state.value as? ProfileSessionState.Unlocked)?.profileId

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
        val health = InMemoryAddonHealthStore()
        val client = StremioClient(okHttp, cacheDir = File(app.cacheDir, "addons"))
        val registry = AddonRegistry(
            document = document,
            client = client,
            health = health,
            store = PreferenceAddonRegistryStore(graph.presentationPreferences, graph.settingsStore),
        )
        val built = DataServices(
            document = document,
            health = health,
            client = client,
            registry = registry,
            meta = MetaRepository(registry, client, document.tmdbAPIKey, File(app.cacheDir, "meta"), okHttp),
            ratings = RatingsRepository(document.mdbListAPIKey, okHttp),
            search = StreamSearch(registry, client, health),
            debrid = DebridResolver(
                torboxKey = document.torboxAPIKey,
                rdKey = document.realDebridAPIKey,
                okHttp = okHttp,
                rules = document.playbackRules,
            ),
        )
        mutableServices.value = built
        ReceiverDiagnostics.record("client.settings", "addons=${document.addonSources.size}")
        scope.launch { runCatching { registry.refresh() } }
    }

    /**
     * Starts the process-level plumbing: the first settings load, snapshot hydration, and the
     * settings-change subscription. Safe to call more than once; only the first call does work.
     */
    fun start() {
        if (started) return
        started = true
        initializeProfiles()
        reloadSettings()
        scope.launch {
            try {
                runCatching { snapshots.hydrate() }
            } finally {
                snapshotsHydrated.complete(Unit)
            }
            runCatching { legacyCatalogBridge.importIfNewer() }
        }
        scope.launch {
            var savedObserved = false
            graph.pairing.state
                .collect { state ->
                    when (state) {
                        is TVSettingsPairingState.Saved -> {
                        savedObserved = true
                        reloadSettingsNow()
                        snapshotsHydrated.await()
                        runCatching { legacyCatalogBridge.importIfNewer() }
                        }
                        TVSettingsPairingState.Idle -> {
                            val configured = withContext(Dispatchers.IO) {
                                graph.settingsStore.load() is StoredTVSettingsState.Available
                            }
                            // Initial Idle precedes the async persisted-state restore. Only purge
                            // when storage also says there is no owner setup.
                            if (!configured) clearPrivateSetupState(
                                clearLegacyCache = !savedObserved,
                            )
                        }
                        else -> Unit
                    }
                }
        }
        scope.launch {
            graph.pairing.settingsApplied.collect {
                reloadSettingsNow()
                snapshotsHydrated.await()
                runCatching { legacyCatalogBridge.importIfNewer() }
                scheduler.onTrustedSync()
            }
        }
        scheduler.schedulePeriodicRefresh(app)
    }

    @Volatile
    private var started = false

    private suspend fun clearPrivateSetupState(clearLegacyCache: Boolean) {
        mutableServices.value = null
        // On a cold unconfigured start, remove any orphaned cache before the user can approve a
        // new pairing. After an observed Saved→Idle transition the coordinator already removed
        // the old cache synchronously; deleting again after waiting on refresh could erase the
        // newly paired phone's catalog.
        if (clearLegacyCache) {
            runCatching { withContext(Dispatchers.IO) { graph.catalogCache.clear() } }
        }
        snapshotsHydrated.await()
        runCatching { snapshots.clear(SnapshotSource.PRIVATE_CATALOG) }
        ReceiverDiagnostics.record("client.settings", "cleared")
    }

    @Synchronized
    private fun initializeProfiles() {
        val catalog = profileSession.load()
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
