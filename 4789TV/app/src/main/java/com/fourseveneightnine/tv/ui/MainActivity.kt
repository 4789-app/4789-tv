package com.fourseveneightnine.tv.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.net.Uri
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.Surface
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.fourseveneightnine.tv.R
import com.fourseveneightnine.tv.catalog.AtomicTVTamilMVCatalogCache
import com.fourseveneightnine.tv.catalog.NetworkTVPrivateCatalogLoader
import com.fourseveneightnine.tv.catalog.TVAddonPlayableSource
import com.fourseveneightnine.tv.catalog.TVArtworkEnrichmentRepository
import com.fourseveneightnine.tv.catalog.TVEnrichmentDiskCache
import com.fourseveneightnine.tv.catalog.TVRatingsRepository
import com.fourseveneightnine.tv.catalog.TVAddonSourceLifecyclePolicy
import com.fourseveneightnine.tv.catalog.sourceKey
import com.fourseveneightnine.tv.catalog.TVAddonSourceRepository
import com.fourseveneightnine.tv.catalog.TVAddonSourceState
import com.fourseveneightnine.tv.catalog.TVPrivateCatalogRepository
import com.fourseveneightnine.tv.catalog.TVPrivateCatalogCredentialPolicy
import com.fourseveneightnine.tv.catalog.TVTamilMVCatalogItem
import com.fourseveneightnine.tv.catalog.TVTamilMVCatalogState
import com.fourseveneightnine.tv.catalog.visibleSnapshot
import com.fourseveneightnine.tv.catalog.TVTMDBCatalogRepository
import com.fourseveneightnine.tv.catalog.TVTMDBCatalogSnapshot
import com.fourseveneightnine.tv.catalog.TVTMDBCatalogState
import com.fourseveneightnine.tv.discovery.NsdAdvertisementStatus
import com.fourseveneightnine.tv.discovery.NsdAdvertiser
import com.fourseveneightnine.tv.discovery.ReceiverDeviceIdentity
import com.fourseveneightnine.tv.discovery.ReceiverNetworkMonitor
import com.fourseveneightnine.tv.player.MpvReceiverController
import com.fourseveneightnine.tv.player.ExoReceiverController
import com.fourseveneightnine.tv.player.ExternalPlayerIntentPolicy
import com.fourseveneightnine.tv.player.MpvSubtitleFontPolicy
import com.fourseveneightnine.tv.player.ReceiverEngine
import com.fourseveneightnine.tv.player.ReceiverEnginePolicy
import com.fourseveneightnine.tv.player.SwappableReceiverController
import com.fourseveneightnine.tv.player.ReceiverSubtitleStyle
import com.fourseveneightnine.tv.player.VideoCodecProbe
import com.fourseveneightnine.tv.player.AudioCodecProbe
import com.fourseveneightnine.tv.player.AndroidDirectAudioProbe
import com.fourseveneightnine.tv.player.AndroidMediaCapabilities
import com.fourseveneightnine.tv.player.PlaybackDiagnostics
import com.fourseveneightnine.tv.player.ReceiverPlaybackPhase
import com.fourseveneightnine.tv.player.ReceiverPlaybackPhasePolicy
import com.fourseveneightnine.tv.player.ResumePolicy
import com.fourseveneightnine.tv.player.upscale.UpscaleMode
import com.fourseveneightnine.tv.player.SurfaceFrameRatePolicy
import com.fourseveneightnine.tv.player.DisplayModeInfo
import com.fourseveneightnine.tv.protocol.EngineOverridePort
import com.fourseveneightnine.tv.protocol.ExternalPlayerPort
import com.fourseveneightnine.tv.protocol.ExternalHandoffContext
import com.fourseveneightnine.tv.protocol.InstalledExternalPlayer
import com.fourseveneightnine.tv.protocol.OpenMediaRequest
import com.fourseveneightnine.tv.protocol.ReceiverSnapshot
import com.fourseveneightnine.tv.protocol.ReceiverController
import com.fourseveneightnine.tv.protocol.ReceiverEvent
import com.fourseveneightnine.tv.protocol.ReceiverIdentity
import com.fourseveneightnine.tv.protocol.ReceiverPreparationStage
import com.fourseveneightnine.tv.protocol.RpcDispatcher
import com.fourseveneightnine.tv.protocol.SeekCommand
import com.fourseveneightnine.tv.protocol.SubtitleSelection
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics
import com.fourseveneightnine.tv.settings.EncryptedTVSettingsStore
import com.fourseveneightnine.tv.settings.TVSettingsFileReader
import com.fourseveneightnine.tv.settings.TVSettingsPairingCoordinator
import com.fourseveneightnine.tv.settings.TVSettingsPairingState
import com.fourseveneightnine.tv.transport.KodiTransport
import com.fourseveneightnine.tv.transport.ReceiverPorts
import com.fourseveneightnine.tv.ui.settings.TVSettingsSurface
import com.fourseveneightnine.tv.ui.settings.TVSettingsDestination
import com.fourseveneightnine.tv.ui.settings.TVSettingsPresentationSnapshot
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Activity-owned Android TV receiver. A boot/update receiver makes a best-effort request to open
 * this Activity, but the network endpoint is advertised only after a visible video Surface is ready.
 *
 * The media3-ui surfaces this Activity draws with (AspectRatioFrameLayout, SubtitleView,
 * CaptionStyleCompat) are all `@UnstableApi`; ExoReceiverController opts in the same way.
 */
@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {
    private val activityJob = SupervisorJob()
    private val activityScope = CoroutineScope(activityJob + Dispatchers.Main.immediate)
    private val sessionMutex = Mutex()
    private val playbackStatusNotifications = Channel<Pair<String, String>>(
        capacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private lateinit var controller: ReceiverController
    private lateinit var transport: KodiTransport
    private lateinit var receiverDispatcher: RpcDispatcher
    private lateinit var advertiser: NsdAdvertiser
    private lateinit var networkMonitor: ReceiverNetworkMonitor
    private lateinit var enginePreferences: android.content.SharedPreferences
    private lateinit var activeEngine: ReceiverEngine

    private lateinit var surfaceView: SurfaceView
    private lateinit var videoFrame: AspectRatioFrameLayout
    private lateinit var subtitleView: SubtitleView
    private lateinit var overlay: FrameLayout
    /// The overlay's vertical scroller. Owns the ONLY vertical scroll on the home screen —
    /// without it the content below the fold (the recents rail) is focusable but unreachable.
    private lateinit var overlayScroller: ScrollView
    /// Pure black behind the letterboxed video, raised whenever the overlay is down so a scope
    /// film sits in black rather than in the home screen's slate gradient.
    private lateinit var letterboxBackingView: View
    /// Content signature of the rail as currently drawn — the guard that stops an unchanged
    /// shelf being torn down and rebuilt under a focused card. See `renderRecentsShelf`.
    private var renderedRecentsSignature: String = ""
    /// Which overlay state the last render drew. Focus may only be MOVED when this changes;
    /// a repeat render of the same state must leave the viewer's focus alone.
    private var renderedOverlayState: String? = null
    private lateinit var receiverHomeContent: LinearLayout
    private lateinit var receiverBrandStage: FrameLayout
    private lateinit var receiverBrandView: android.widget.ImageView
    private lateinit var idleFactsView: TextView
    private lateinit var idlePingRing: View
    private var idlePingAnimator: android.animation.ValueAnimator? = null
    private lateinit var headingView: TextView
    private lateinit var directionView: TextView
    private lateinit var progressView: ProgressBar

    // PlayerInstaller lives in a per-product source directory, so it cannot reach the private
    // fields above. These three accessors are the whole surface it needs. See
    // app/src/installer/java/.../PlayerInstaller.kt and its stub in app/src/installer-stub/.
    internal val installDirectionView: TextView get() = directionView
    internal val installProgressView: ProgressBar get() = progressView
    internal val installScope: CoroutineScope get() = activityScope
    private lateinit var retryButton: AppCompatButton
    private lateinit var externalButtonsView: LinearLayout
    private lateinit var externalButtonsScrollerView: android.widget.HorizontalScrollView
    private lateinit var diagnosticsView: TextView
    private lateinit var backdropView: android.widget.ImageView
    /** Full-bleed playback backdrop; uses the title's landscape art or a blurred poster extension. */
    private lateinit var playbackBackdropView: android.widget.ImageView
    /** Crisp, non-zoomed portrait fallback centered over [playbackBackdropView]. */
    private lateinit var playbackPosterView: android.widget.ImageView
    private var playbackArtworkJob: Job? = null
    private var playbackArtworkKey: String? = null
    private lateinit var playbackStatusView: LinearLayout
    private lateinit var playbackStatusTitle: TextView
    private lateinit var playbackStatusDetail: TextView
    private lateinit var playbackStatusProgress: ProgressBar
    private lateinit var playerControlsView: PlayerControlsView
    private lateinit var optionRail: OptionRailView
    private lateinit var endedPlate: EndedPlateView
    private lateinit var bufferingChip: LinearLayout
    private lateinit var audioManager: AudioManager
    private lateinit var mediaSession: MediaSession
    private lateinit var recentsStore: RecentsStore
    private lateinit var recentsTitleView: TextView
    private lateinit var recentsLayoutView: LinearLayout
    private lateinit var recentsScrollerView: android.widget.HorizontalScrollView
    private lateinit var settingsButton: AppCompatButton
    private lateinit var librarySurfaceView: ComposeView
    private lateinit var settingsSurfaceView: ComposeView
    private lateinit var settingsStore: EncryptedTVSettingsStore
    private lateinit var settingsCoordinator: TVSettingsPairingCoordinator
    private lateinit var tamilMVCatalogCache: AtomicTVTamilMVCatalogCache
    private lateinit var tamilMVCatalogRepository: TVPrivateCatalogRepository
    private lateinit var tmdbCatalogRepository: TVTMDBCatalogRepository
    private lateinit var addonSourceRepository: TVAddonSourceRepository
    private lateinit var ratingsRepository: TVRatingsRepository
    private lateinit var artworkEnrichmentRepository: TVArtworkEnrichmentRepository
    private lateinit var ratingsDiskCache: TVEnrichmentDiskCache
    private lateinit var artworkDiskCache: TVEnrichmentDiskCache

    private var activityStarted = false
    private var surfaceAttached = false
    private var surfaceReady = false
    private var transportRunning = false
    private var advertisingRequested = false
    private var playbackActive = false
    private var frameRateEligible = false
    private var showDiagnostics = false
    private var currentDiagnostics = PlaybackDiagnostics()
    private var mediaCapabilities = AndroidMediaCapabilities(emptyList(), emptyList())
    private var appliedFrameRate = 0f
    private var autoFrameRateEnabled = true
    private val presentationPreferences by lazy { getSharedPreferences("receiver_presentation", MODE_PRIVATE) }
    private var settingsPresentationSnapshot by mutableStateOf<TVSettingsPresentationSnapshot?>(null)
    private var settingsPresentationGeneration = 0L
    private var settingsPresentationJob: Job? = null
    private var settingsSurfaceVisible = false
    private var settingsEntryDestination by mutableStateOf(TVSettingsDestination.Pair)
    private var librarySurfaceActive by mutableStateOf(false)
    private var libraryDestination by mutableStateOf(TVLibraryDestination.Continue)
    private var libraryDetailItem by mutableStateOf<TVTamilMVCatalogItem?>(null)
    private var libraryDetailLabel by mutableStateOf<String?>(null)
    /** A deliberate home Back is the receiver-ready canvas, not an Activity finish. */
    private var receiverIdleRequested by mutableStateOf(false)
    private var playbackStopPending = false
    private var playbackPresentationOrigin = TVReceiverPresentationPolicy.PlaybackOrigin.Library
    private var libraryItems by mutableStateOf(emptyList<RecentItem>())
    private var libraryNavigationGeneration by mutableStateOf(0)
    private var librarySettingsConfigured by mutableStateOf(false)
    // These values are display-only, but their old inline lookups were not cheap: walking the
    // network interfaces and decrypting the Keystore-backed settings document during a Compose
    // recomposition made a single focus change pay that cost again. Keep a UI snapshot and refresh
    // it only when the Activity starts or settings actually change.
    private var libraryReceiverAddress by mutableStateOf("")
    private var libraryLetterboxdUsernames by mutableStateOf(emptyList<String>())
    private var settingsStateLoaded by mutableStateOf(false)
    private var addonSourceState by mutableStateOf<TVAddonSourceState>(TVAddonSourceState.Idle)
    private var tamilJobStatus by mutableStateOf(
        TVJobStatus(TVJobPhase.Waiting, "Select Tamil MV or run it from Jobs"),
    )
    private var letterboxdJobStatus by mutableStateOf(
        TVJobStatus(TVJobPhase.Waiting, "Waiting for a Letterboxd snapshot"),
    )
    private var tmdbJobStatus by mutableStateOf(
        TVJobStatus(TVJobPhase.Waiting, "Select TMDB catalogs or run it from Jobs"),
    )
    private var addonSourceJob: Job? = null
    private var addonPlaybackJob: Job? = null
    private var addonSourceGeneration = 0L
    private var openTamilMVItemID: String? = null
    private var continuePlaybackJob: Job? = null
    private var continuePlaybackItem: RecentItem? = null
    private var continuePlaybackFallbackAttempted = false
    private val continueAttemptedSourceURLs = mutableSetOf<String>()
    private var settingsSurfaceActive by mutableStateOf(false)
    private var settingsFilePickerActive = false

    private val settingsFilePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        settingsFilePickerActive = false
        if (::settingsCoordinator.isInitialized) {
            showSettingsSurface()
            if (uri != null) importSettingsFile(uri)
        }
    }

    /**
     * The display mode the panel was on before we asked for a film's rate, so it can be handed
     * back. Only used below API 30, where [applyContentFrameRateLegacy] owns the switch.
     */
    private var originalDisplayModeId = 0
    private var startupState: StartupState = StartupState.Starting
    private var playbackPhase: ReceiverPlaybackPhase = ReceiverPlaybackPhase.Idle
    private var surfaceReadyTimeout: Job? = null
    private var capabilityProbeJob: Job? = null
    private var networkMonitorStartJob: Job? = null

    /** Collects phone-pushed settings syncs while the Activity is started. */
    private var settingsSyncJob: Job? = null

    // The on-TV player UI (seek bar + track/resize/speed row).
    private var playerControlsVisible = false
    private var playerControlsHideJob: Job? = null
    private var playerControlsPollJob: Job? = null
    private var seekPreviewHideJob: Job? = null

    /**
     * The recents row for the title now playing, written ONCE per open.
     *
     * It used to be re-pushed on every controls refresh — twice a second, on the main thread, each
     * one a full read-parse-serialise-commit of the whole SharedPreferences blob while the seek bar
     * was animating. The row's only volatile field is the position, and [saveResumePoint] already
     * visits that on a 15s tick off the main thread, so the position now rides along with it.
     */
    private var recentsRowUrl: String? = null

    /**
     * Whether this title has ever reached the picture.
     *
     * It is the difference between "still opening" and "was watching this and it stalled" — and the
     * two deserve different screens. Only the second gets the small corner spinner.
     */
    private var playbackHasStarted = false

    /**
     * When the picture for the current title first reached the screen, on the elapsed-realtime
     * clock, or 0 while nothing is up.
     *
     * Auto frame rate matching reads it. A refresh-rate change re-links HDMI, and the panel answers
     * with seconds of black; during startup that is invisible, mid-scene it is the worst thing this
     * receiver can do. See [SurfaceFrameRatePolicy.withinNonSeamlessWindow].
     */
    private var pictureUpAtMillis = 0L

    /** Escalates a long mid-film stall from the corner spinner to the full waiting plate. */
    private var bufferingEscalationJob: Job? = null
    private var bufferingPlateVisible = false

    /**
     * Installed handoff targets, resolved once per opened title.
     *
     * `installedPlayers()` is a PackageManager `queryIntentActivities` sweep, and the controls used
     * to run it synchronously on the main thread every time the bar was raised — which is every
     * play/pause and every ±10s. On a box with a full app list that is the reason the bar arrived a
     * beat after the key. The cache is dropped when a package is installed or removed, so a player
     * added while the receiver keeps running still appears.
     */
    private var cachedExternalPlayers: List<InstalledExternalPlayer>? = null
    private var cachedExternalTargets: List<ExternalPlayerIntentPolicy.PlayerTargetInfo>? = null
    private var externalTargetsJob: Job? = null
    private var renderedExternalOptionsVisible = false
    private var renderedExternalOptionsKey: String? = null

    private val packageChangeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            cachedExternalPlayers = null
            cachedExternalTargets = null
            renderedExternalOptionsVisible = false
            renderedExternalOptionsKey = null
        }
    }
    private var packageChangeReceiverRegistered = false

    // Buffering-screen artwork pushed by the phone (X4789.NowPlaying), and every poster on the
    // library rails. Given a cache directory so a cold start redraws from disk instead of pulling
    // the whole rail over Wi-Fi again — a TV process is killed and restarted constantly.
    private val artworkLoader by lazy { ArtworkLoader(diskCacheDir = java.io.File(cacheDir, "artwork")) }
    private var artworkURLOnScreen: String? = null
    /// The backdrop URL most recently ASKED for — the yardstick a completed load is judged
    /// stale against. Distinct from `artworkURLOnScreen`, which is what is already drawn.
    private var backdropRequestedURL: String? = null
    /// Debounce for the focus→backdrop load; a new focus supersedes the pending one.
    private var backdropSettleJob: kotlinx.coroutines.Job? = null

    // "Reopen the app and pick the film back up." Written while playing, offered on the next start.
    private lateinit var resumeStore: com.fourseveneightnine.tv.startup.ResumePointStore
    private var resumeOffer: com.fourseveneightnine.tv.player.ResumePoint? = null
    private var resumeSaveJob: Job? = null
    private var resumeInFlight = false

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            refreshMediaCapabilities()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            refreshMediaCapabilities()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        autoFrameRateEnabled = presentationPreferences.getBoolean("auto_frame_rate_enabled", true)
        ReceiverDiagnostics.record("activity.onCreate.begin")
        val onCreateStartedAt = android.os.SystemClock.elapsedRealtime()

        // The brand default is still the tested behaviour; the override exists so a device-specific
        // playback fault can be compared against the other engine without a rebuild.
        enginePreferences = getSharedPreferences(ENGINE_PREFERENCES_NAME, MODE_PRIVATE)
        // Raw stored value, for diagnosing "override didn't stick" reports: distinguishes a wiped
        // preference file (reinstall) from a value that was stored but not honored.
        ReceiverDiagnostics.record(
            "engine.pref.raw",
            "value=${enginePreferences.getString(ENGINE_OVERRIDE_KEY, null) ?: "<none>"}",
        )
        activeEngine = ReceiverEnginePolicy.engine(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            override = enginePreferences.getString(ENGINE_OVERRIDE_KEY, null),
        )
        // The proxy owns the engine at runtime: transport/dispatcher/observers hold the proxy, so
        // a codec-failure Exo→mpv failover swaps engines without dropping the connection.
        controller = SwappableReceiverController(applicationContext, activeEngine)
        ReceiverDiagnostics.record(
            "activity.controller.created",
            "engine=$activeEngine override=${enginePreferences.getString(ENGINE_OVERRIDE_KEY, "auto")}",
        )
        resumeStore = com.fourseveneightnine.tv.startup.ResumePointStore(this)
        audioManager = getSystemService(AudioManager::class.java)
        mediaSession = createMediaSession()
        ReceiverDiagnostics.record(
            "activity.stage",
            "preAdvertiserMs=${android.os.SystemClock.elapsedRealtime() - onCreateStartedAt}",
        )
        advertiser = NsdAdvertiser(applicationContext)
        ReceiverDiagnostics.record(
            "activity.stage",
            "advertiserMs=${android.os.SystemClock.elapsedRealtime() - onCreateStartedAt}",
        )
        val receiverDeviceIdentity = ReceiverDeviceIdentity.current(applicationContext)
        settingsStore = EncryptedTVSettingsStore(applicationContext)
        tamilMVCatalogCache = AtomicTVTamilMVCatalogCache(applicationContext)
        settingsCoordinator = TVSettingsPairingCoordinator(
            receiverID = advertiser.receiverUuid,
            store = settingsStore,
            catalogCache = tamilMVCatalogCache,
        )
        tamilMVCatalogRepository = TVPrivateCatalogRepository(
            settings = settingsStore,
            loader = NetworkTVPrivateCatalogLoader(),
            cache = tamilMVCatalogCache,
        )
        tmdbCatalogRepository = TVTMDBCatalogRepository(
            cache = com.fourseveneightnine.tv.catalog.AtomicTVTMDBCatalogCache(applicationContext),
        )
        addonSourceRepository = TVAddonSourceRepository(settingsStore)
        // Both caches persist to filesDir. A television process is killed constantly, so an
        // in-memory-only cache meant every return to the app re-asked for scores and artwork it
        // already had — the same lesson the poster cache learned earlier.
        ratingsDiskCache = TVEnrichmentDiskCache(
            file = java.io.File(filesDir, "enrich-ratings-v1.json"),
            ttlMillis = TVRatingsRepository.CACHE_TTL_MILLIS,
            maxEntries = 512,
        )
        artworkDiskCache = TVEnrichmentDiskCache(
            file = java.io.File(filesDir, "enrich-artwork-v1.json"),
            ttlMillis = TVArtworkEnrichmentRepository.CACHE_TTL_MILLIS,
            maxEntries = 1024,
        )
        ratingsRepository = TVRatingsRepository(settingsStore, disk = ratingsDiskCache)
        artworkEnrichmentRepository = TVArtworkEnrichmentRepository(settingsStore, disk = artworkDiskCache)
        ReceiverDiagnostics.record(
            "activity.stage",
            "repositoriesMs=${android.os.SystemClock.elapsedRealtime() - onCreateStartedAt}",
        )
        // The listeners bind 0.0.0.0 and survive an address change; the DNS-SD record does not. A
        // router reboot or DHCP lease change otherwise leaves the box listed but unreachable.
        networkMonitor = ReceiverNetworkMonitor(applicationContext) {
            if (advertisingRequested) advertiser.republish()
        }
        ReceiverDiagnostics.record("activity.advertiser.created")
        // Codec enumeration and direct-audio probing call vendor media services. On Fire OS those
        // calls are synchronous and can take hundreds of milliseconds (or longer on a cold boot),
        // so they must not sit between onCreate and the first focused window. The dispatcher starts
        // with an honest "unknown" capability set and receives the complete snapshot asynchronously
        // from onStart.
        receiverDispatcher = RpcDispatcher(
                controller,
                ReceiverIdentity(
                    name = receiverDeviceIdentity.displayName,
                    uuid = advertiser.receiverUuid,
                    manufacturer = receiverDeviceIdentity.manufacturer,
                    model = receiverDeviceIdentity.model,
                    deviceName = receiverDeviceIdentity.deviceName,
                    deviceKind = receiverDeviceIdentity.kind,
                ),
                engineOverrides = object : EngineOverridePort {
                    override fun setOverride(normalizedOverride: String) {
                        // commit(), NOT apply(). This setting only takes effect on the next start,
                        // so the very next thing that happens is a restart — and apply()'s async
                        // write does not survive `am force-stop`, which SIGKILLs the process before
                        // it flushes. Verified on AFTDCT31: the preference file was never created.
                        val stored = enginePreferences.edit()
                            .putString(
                                ENGINE_OVERRIDE_KEY,
                                ReceiverEnginePolicy.storedValue(normalizedOverride),
                            )
                            .commit()
                        ReceiverDiagnostics.record(
                            "engine.override",
                            "$normalizedOverride persisted=$stored",
                        )
                    }

                    // Reports what is RUNNING right now — after a failover that is the proxy's
                    // current engine, not the one chosen at Activity creation.
                    override fun currentEngineName(): String =
                        ((controller as? SwappableReceiverController)?.engine ?: activeEngine)
                            .name.lowercase(java.util.Locale.US)

                    // Explicit user action from the phone: swap the live engine and reopen the
                    // current title. Not persisted — the next launch still follows the policy.
                    override suspend fun switchNow(engineName: String): Boolean {
                        val proxy = controller as? SwappableReceiverController ?: return false
                        val target = when (engineName.lowercase(java.util.Locale.US)) {
                            "exo" -> ReceiverEngine.Exo
                            else -> return false
                        }
                        return proxy.requestEngine(target)
                    }
                },
                externalPlayers = object : ExternalPlayerPort {
                    override fun installedPlayers(): List<InstalledExternalPlayer> =
                        ExternalPlayerIntentPolicy.installedPlayers(applicationContext)

                    override suspend fun launch(
                        url: String,
                        title: String?,
                        targetPackage: String?,
                        handoff: ExternalHandoffContext,
                    ): Boolean =
                        withContext(Dispatchers.Main) {
                            launchExternalPlayer(
                                url,
                                title,
                                targetPackage,
                                ExternalPlayerIntentPolicy.HandoffContext(
                                    positionMillis = handoff.positionMillis,
                                    subtitleURL = handoff.subtitleURL,
                                    subtitleName = handoff.subtitleName,
                                    headers = handoff.headers,
                                ),
                            )
                        }
                },
            )
        transport = KodiTransport(
            dispatcher = receiverDispatcher,
            events = controller.events,
            settingsPairingEndpoint = settingsCoordinator,
        )
        ReceiverDiagnostics.record("activity.transport.created")

        recentsStore = RecentsStore(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(createContentView())
        ReceiverDiagnostics.record("activity.contentView.created")
        surfaceView.holder.addCallback(this)
        observeReceiverState()
        hideSystemBars()
        ReceiverDiagnostics.record("activity.onCreate.complete")
    }

    override fun onStart() {
        super.onStart()
        ReceiverDiagnostics.record("activity.onStart.begin")
        activityStarted = true
        activityScope.launch {
            settingsCoordinator.restorePersistedState()
            settingsStateLoaded = true
            refreshLibrarySnapshot()
            renderOverlay()
        }
        // A sync from the phone changes which addons and catalogs this box should be using. Until
        // now only the QR approval path rebuilt anything, so a later sync landed on disk and was
        // ignored until the Activity restarted.
        settingsSyncJob?.cancel()
        settingsSyncJob = activityScope.launch {
            settingsCoordinator.settingsApplied.collect {
                librarySettingsConfigured =
                    settingsCoordinator.state.value is TVSettingsPairingState.Saved
                refreshSettingsPresentationSnapshot()
                if (librarySettingsConfigured) {
                    refreshLibraryDisplayMetadata()
                    refreshTamilMVCatalog(force = true)
                }
            }
        }
        refreshLibraryDisplayMetadata()
        // Network callback registration performs a synchronous framework/Binder round-trip on
        // some Fire OS builds. It is not needed to draw or focus the first screen, so start it off
        // the UI dispatcher after the Activity is visible.
        networkMonitorStartJob?.cancel()
        networkMonitorStartJob = activityScope.launch(Dispatchers.Default) { networkMonitor.start() }
        refreshReceiverCapabilities()
        mediaSession.isActive = true
        refreshMediaCapabilities()
        ReceiverDiagnostics.record("activity.mediaCapabilities.refreshed")
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        // A player installed while the receiver was in the background must still show up on the
        // handoff control, so the cache is dropped and re-armed across every visibility cycle.
        cachedExternalPlayers = null
        registerPackageChangeReceiver()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        startupState = StartupState.Starting
        loadResumeOffer()
        renderOverlay()
        attachCurrentSurfaceIfAvailable()
        reconcileReceiver()
        ReceiverDiagnostics.record("activity.onStart.complete")
    }

    override fun onStop() {
        activityStarted = false
        // Persist what this session learned. The batched flush only fires every few entries, and
        // a normal session looks at fewer titles than that — so without this the cache was written
        // almost never and every launch re-fetched artwork and scores it already had.
        if (::ratingsDiskCache.isInitialized) runCatching { ratingsDiskCache.flush() }
        if (::artworkDiskCache.isInitialized) runCatching { artworkDiskCache.flush() }
        idlePingAnimator?.cancel()
        idlePingAnimator = null
        capabilityProbeJob?.cancel()
        capabilityProbeJob = null
        // Visibility teardown already stops the controller once, under sessionMutex. Cancel the
        // source-open coroutine here without racing a second stop against that ordered teardown.
        cancelTamilMVSourceFlow(stopPendingOpen = false)
        cancelContinuePlaybackFlow()
        hideSettingsSurface(resetPairing = !settingsFilePickerActive)
        mediaSession.isActive = false
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        unregisterPackageChangeReceiver()
        clearContentFrameRate()
        // Withdraw discovery synchronously before transport shutdown begins. NsdAdvertiser's own
        // registration lock makes this safe if a framework callback is still in flight.
        advertisingRequested = false
        advertiser.stop()
        // Revoke readiness before the mutex-bound teardown so a saved-IP Player.Open racing
        // Home cannot retain a usable video target while transport shutdown is still pending.
        controller.detachSurface()
        surfaceReadyTimeout?.cancel()
        surfaceReadyTimeout = null
        hidePlayerControls()
        // Last chance: teardown is exactly the moment worth remembering.
        saveResumePoint()
        stopResumeSaving()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        stopReceiverForVisibilityLoss()
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        controller.handleMemoryPressure(level)
    }

    override fun onDestroy() {
        ReceiverDiagnostics.record("activity.onDestroy")
        idlePingAnimator?.cancel()
        idlePingAnimator = null
        seekPreviewHideJob?.cancel()
        seekPreviewHideJob = null
        if (::bufferingChip.isInitialized) bufferingChip.animate().cancel()
        bufferingPlateVisible = false
        networkMonitorStartJob?.cancel()
        networkMonitorStartJob = null
        settingsSyncJob?.cancel()
        settingsSyncJob = null
        capabilityProbeJob?.cancel()
        capabilityProbeJob = null
        surfaceReadyTimeout?.cancel()
        surfaceReadyTimeout = null
        networkMonitor.close()
        advertiser.close()
        controller.detachSurface()
        controller.close()
        mediaSession.release()
        playbackStatusNotifications.close()
        if (::settingsCoordinator.isInitialized) {
            activityScope.launch(start = CoroutineStart.UNDISPATCHED) {
                withContext(NonCancellable) { settingsCoordinator.close() }
            }
        }
        if (::librarySurfaceView.isInitialized) librarySurfaceView.disposeComposition()
        if (::settingsSurfaceView.isInitialized) settingsSurfaceView.disposeComposition()
        cancelContinuePlaybackFlow()
        activityScope.cancel()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        ReceiverDiagnostics.record("surface.created", "valid=${holder.surface.isValid}")
        if (!activityStarted) return
        attachSurface(holder)
        reconcileReceiver()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        ReceiverDiagnostics.record("surface.changed", "valid=${holder.surface.isValid} size=${width}x$height")
        controller.updateSurfaceSize(width, height)
        if (activityStarted && !surfaceAttached) {
            attachSurface(holder)
            reconcileReceiver()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        ReceiverDiagnostics.record("surface.destroyed")
        clearContentFrameRate()
        surfaceAttached = false
        surfaceReady = false
        surfaceReadyTimeout?.cancel()
        surfaceReadyTimeout = null
        // Do not leave a discoverable receiver behind after Android invalidates its video target.
        advertisingRequested = false
        advertiser.stop()
        // Revoke readiness before the mutex-bound teardown so a saved-IP Player.Open racing
        // Home cannot retain a usable video target while transport shutdown is still pending.
        controller.detachSurface()

        if (activityStarted) {
            startupState = StartupState.Starting
            renderOverlay()
        }
        stopReceiverForVisibilityLoss()
    }

    /**
     * Does the PLAYER own the D-pad right now?
     *
     * `playbackActive` alone is not safe to answer this. It is fed from ONE place — the controller's
     * event stream — so any ending that fails to deliver a final event (a cast the phone dropped, a
     * stream that died, a flow cancelled during teardown) leaves it stuck at `true` forever. On the
     * home screen that is catastrophic and completely silent: UP/DOWN raise player controls for a
     * film that is not playing, LEFT/RIGHT issue blind seeks, and the recents rail cannot be reached.
     * The remote looks broken, and the only cure the viewer finds is force-quitting the app — which
     * works purely because a fresh process starts the flag at `false`.
     *
     * So the question is answered from WHAT IS ON SCREEN instead. The overlay being down IS "the
     * picture is the only thing showing", and a state derived from what is drawn cannot get stuck:
     * if the home screen is visible, the D-pad belongs to the home screen, whatever any flag says.
     */
    private val playbackOwnsDpad: Boolean
        get() = playbackActive &&
            (!::overlay.isInitialized || overlay.visibility != View.VISIBLE)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The Compose-for-TV settings canvas owns every remote key while visible, including BACK.
        if (settingsSurfaceVisible) return super.dispatchKeyEvent(event)
        // The receiver-ready plate has one deterministic exit. Pairing remains owned by the
        // settings flow: an unpaired receiver cannot manufacture a library destination here.
        if (receiverIdleRequested &&
            librarySettingsConfigured &&
            (playbackPhase is ReceiverPlaybackPhase.Idle || playbackPhase is ReceiverPlaybackPhase.Stopped) &&
            event.keyCode in setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)
        ) {
            if (event.action == KeyEvent.ACTION_UP) {
                receiverIdleRequested = TVReceiverPresentationPolicy.reduce(
                    TVReceiverPresentationPolicy.PresentationState(TVReceiverPresentationPolicy.Surface.Idle),
                    TVReceiverPresentationPolicy.Event.EnterHome,
                ).surface != TVReceiverPresentationPolicy.Surface.Home
                renderOverlay()
            }
            return true
        }
        // The My Stuff canvas is the home screen while ready. Let Compose own directional focus;
        // the legacy receiver overlay is deliberately blocked underneath it.
        if (librarySurfaceActive) {
            // Detail has its own Compose BackHandler so it can restore the originating card.
            if (event.keyCode != KeyEvent.KEYCODE_BACK || libraryDetailItem != null) {
                return super.dispatchKeyEvent(event)
            }
            if (event.action == KeyEvent.ACTION_UP) {
                receiverIdleRequested = TVReceiverPresentationPolicy.reduce(
                    TVReceiverPresentationPolicy.PresentationState(TVReceiverPresentationPolicy.Surface.Home),
                    TVReceiverPresentationPolicy.Event.HomeBack,
                ).surface == TVReceiverPresentationPolicy.Surface.Idle
                hideLibrarySurface()
                renderOverlay()
            }
            return true
        }
        // A raised choice list owns the D-pad, BACK included. Without this the Activity's own BACK
        // handling fires first and tears the bar down underneath the list the viewer is reading.
        if (::optionRail.isInitialized && optionRail.isShowing) {
            return super.dispatchKeyEvent(event)
        }
        // The end-of-film plate has its own way out; BACK must not exit the app from it.
        if (::endedPlate.isInitialized && endedPlate.isShowing) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) {
                    endedPlate.hide()
                    playbackPhase = ReceiverPlaybackPhase.Stopped
                    renderOverlay()
                }
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        // Back is a presentation transition while a stream is opening, buffering, or showing.
        // It is intentionally handled ahead of the platform fallback so it cannot finish the
        // Activity or turn a user-initiated stop into the natural-ended plate.
        if (event.keyCode == KeyEvent.KEYCODE_BACK &&
            (playbackPhase is ReceiverPlaybackPhase.Opening ||
                playbackPhase is ReceiverPlaybackPhase.Buffering ||
                playbackPhase is ReceiverPlaybackPhase.Playing ||
                playbackPhase is ReceiverPlaybackPhase.Paused)
        ) {
            if (event.action == KeyEvent.ACTION_UP) requestStop()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (TvRemoteKeyPolicy.command(event.keyCode, playbackOwnsDpad, playerControlsVisible)) {
                TvRemoteCommand.ToggleDiagnostics -> {
                showDiagnostics = !showDiagnostics
                renderDiagnostics()
                return true
                }

                TvRemoteCommand.TogglePlayPause -> {
                    requestTogglePlayPause()
                    return true
                }

                TvRemoteCommand.Play -> {
                    requestPlay()
                    return true
                }

                TvRemoteCommand.Pause -> {
                    requestPause()
                    return true
                }

                TvRemoteCommand.SeekBackward -> {
                    nudgeScrub(forward = false)
                    return true
                }

                TvRemoteCommand.SeekForward -> {
                    nudgeScrub(forward = true)
                    return true
                }

                TvRemoteCommand.Stop -> {
                    requestStop()
                    return true
                }

                TvRemoteCommand.ShowControls -> {
                    showPlayerControls()
                    return true
                }

                TvRemoteCommand.HideControls -> {
                    hidePlayerControls()
                    return true
                }

                TvRemoteCommand.PasteUrl -> {
                    showPasteUrlDialog()
                    return true
                }

                TvRemoteCommand.PassThrough -> Unit
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun requestTogglePlayPause() {
        activityScope.launch {
            controller.playPause().onSuccess {
                showPlayerControls()
                val snapshot = controller.snapshot()
                transport.notifyLocalControl(
                    if (snapshot.speed == 0) "pause" else "resume",
                    snapshot,
                )
            }
        }
    }

    private fun requestPlay() {
        activityScope.launch {
            val snapshot = controller.snapshot()
            if (snapshot.active && snapshot.speed == 0) {
                controller.playPause().onSuccess {
                    showPlayerControls()
                    transport.notifyLocalControl("resume", controller.snapshot())
                }
            }
        }
    }

    private fun requestPause() {
        activityScope.launch {
            val snapshot = controller.snapshot()
            if (snapshot.active && snapshot.speed != 0) {
                controller.playPause().onSuccess { showPlayerControls() }
            }
        }
    }

    /**
     * LEFT/RIGHT, whether or not the bar was already up.
     *
     * There used to be two models on one pair of keys: a blind ±10s seek with the bar down, and an
     * accelerating scrub with it up. Both use the same pending position and coalescing, but a seek
     * never changes whether the viewer explicitly opened the controls: hidden stays hidden and
     * answers only with the top-right readout.
     */
    private fun nudgeScrub(forward: Boolean) {
        if (!::playerControlsView.isInitialized) return
        val step = if (forward) {
            PlayerControlsPolicy.BASE_SCRUB_MILLIS
        } else {
            -PlayerControlsPolicy.BASE_SCRUB_MILLIS
        }
        showPlayerSeekPreview()
        playerControlsView.nudgeScrub(step)
        restartSeekPreviewHideTimer()
    }

    /** Shows top-right seek feedback without changing control visibility or focus ownership. */
    private fun showPlayerSeekPreview(refreshSnapshot: Boolean = true) {
        if (!playbackActive || !::playerControlsView.isInitialized) return
        seekPreviewHideJob?.cancel()
        playerControlsView.animate().cancel()
        playerControlsView.showSeekFeedback(playerControlsVisible)
        playerControlsView.alpha = 1f
        playerControlsView.translationY = 0f
        if (refreshSnapshot) refreshPlayerControls()
    }

    private fun restartSeekPreviewHideTimer() {
        seekPreviewHideJob?.cancel()
        seekPreviewHideJob = activityScope.launch {
            delay(TvTokens.Motion.SeekPreviewDismissMillis)
            if (!playerControlsVisible && ::playerControlsView.isInitialized) {
                playerControlsView.hideSeekPreview()
            }
        }
    }

    /** The relative-seek path for media buttons and the wire protocol; not the D-pad. */
    private fun requestSeek(offsetSeconds: Double) {
        activityScope.launch {
            controller.seek(SeekCommand.RelativeSeconds(offsetSeconds))
        }
    }

    private fun requestSeekTo(positionMillis: Long) {
        activityScope.launch {
            controller.seek(SeekCommand.AbsoluteSeconds(positionMillis.coerceAtLeast(0L) / 1_000.0))
                .onFailure {
                    // The optimistic position never landed; drop it rather than leave the bar
                    // showing a place the film is not.
                    if (::playerControlsView.isInitialized) playerControlsView.rejectOptimisticSeek()
                }
        }
    }

    private fun requestStop() {
        if (playbackStopPending) return
        playbackStopPending = true
        hidePlayerControls()
        val returnTarget = TVReceiverPresentationPolicy.backFromPlayback(playbackPresentationOrigin)
        receiverIdleRequested = false
        activityScope.launch {
            try {
                controller.stop()
                if (returnTarget == TVReceiverPresentationPolicy.BackTarget.Library) {
                    libraryDetailItem = null
                    libraryDetailLabel = null
                }
            } finally {
                playbackStopPending = false
            }
        }
    }

    /**
     * SEARCH-key entry point: paste a URL and play it. The paste dialog delegates classification
     * to [com.fourseveneightnine.tv.ui.paste.PasteUrlPolicy]; direct media (MP4/HLS/DASH or the
     * signed Einthusan CDN link) plays immediately, an Einthusan watch page is refused with a
     * clear explanation (the receiver has no session — only the phone does).
     */
    private fun showPasteUrlDialog() {
        com.fourseveneightnine.tv.ui.paste.PasteUrlDialog.show(
            activity = this,
            onPlay = { url ->
                ReceiverDiagnostics.record("paste.play", "url=${redactedUrlForLog(url)}")
                // Cast-initiated opens carry a title so the "Opening…" overlay has something to
                // show; a pasted URL has no title, so fall back to the host.
                val fallbackTitle = pasteFallbackTitle(url)
                activityScope.launch {
                    controller.open(
                        OpenMediaRequest(
                            url = url,
                            title = fallbackTitle,
                            subtitle = null,
                            isLive = false,
                            headers = emptyMap(),
                        ),
                    )
                }
            },
            onNeedsResolver = {
                ReceiverDiagnostics.record("paste.reject", "einthusan-watch-page (needs phone)")
                android.widget.Toast.makeText(
                    this,
                    "Einthusan watch links can't be pasted here — open from the 4789 phone app.",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            },
        )
    }

    private fun pasteFallbackTitle(url: String): String {
        val host = Regex("""^https?://([^/?#]+)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrElse(1) { "" }.orEmpty()
        return if (host.isNotEmpty()) host else "Pasted URL"
    }

    /** A signed Einthusan URL carries a bearer credential in `md5=`. Never write the query. */
    private fun redactedUrlForLog(url: String): String = url.substringBefore('?')

    private fun observeReceiverState() {
        // Wire delivery has its own ordered queue. A dead phone can take the bounded 400 ms eviction
        // window without holding the TV's local spinner or phase collector behind network I/O.
        activityScope.launch {
            for ((status, message) in playbackStatusNotifications) {
                transport.notifyPlaybackStatus(status, message, controller.snapshot())
            }
        }

        activityScope.launch {
            controller.surfaceReady.collect { ready ->
                surfaceReady = ready
                if (ready) {
                    surfaceReadyTimeout?.cancel()
                    surfaceReadyTimeout = null
                    if (activityStarted && startupState !is StartupState.Error) {
                        startupState = StartupState.Starting
                    }
                } else {
                    if (transportRunning || advertisingRequested) {
                        advertisingRequested = false
                        advertiser.stop()
                    }
                    if (activityStarted && surfaceAttached) {
                        scheduleSurfaceReadyTimeout()
                    }
                }
                renderOverlay()
                reconcileReceiver()
            }
        }

        activityScope.launch {
            controller.playbackPhase.collect { phase ->
                val wasPaused = playbackPhase is ReceiverPlaybackPhase.Paused
                playbackPhase = phase
                // The bar is held up for as long as the film is paused; the countdown that takes it
                // back down only starts again once the film does.
                if (wasPaused && phase is ReceiverPlaybackPhase.Playing && playerControlsVisible) {
                    restartPlayerControlsHideTimer()
                }
                // The television is the primary playback surface. Render its state before any
                // receiver→phone work so a stale peer can never delay the buffering indicator.
                renderOverlay()
                val wireStatus = when (phase) {
                    is ReceiverPlaybackPhase.Opening -> when (phase.stage) {
                        ReceiverPreparationStage.RESOLVING -> "resolving" to "Resolving source"
                        ReceiverPreparationStage.PREPARING -> "preparing" to "Preparing on TV"
                        else -> "opening" to "Opening on TV"
                    }
                    is ReceiverPlaybackPhase.Buffering -> "buffering" to "TV is buffering"
                    is ReceiverPlaybackPhase.Playing -> "playing" to "Playing on TV"
                    else -> null
                }
                if (wireStatus != null) {
                    playbackStatusNotifications.trySend(wireStatus)
                }
            }
        }

        activityScope.launch {
            controller.events.collect { event ->
                playbackActive = event.isPlaybackActive()
                if (playbackActive && settingsSurfaceVisible) hideSettingsSurface(resetPairing = true)
                frameRateEligible = event.playbackSpeed() == 1
                updateMediaSession(event)
                // Keep the bar's playhead warm even while it is down. Every event already carries a
                // snapshot, so this costs nothing — and it is what lets the first LEFT/RIGHT after
                // the bar is raised step from the real position instead of from a stale zero.
                bindPlaybackSnapshot(event.snapshot())
                if (autoFrameRateEnabled && frameRateEligible) {
                    applyContentFrameRate(currentDiagnostics)
                } else {
                    clearContentFrameRate()
                }
                refreshAfrPresentation()
                if (event is com.fourseveneightnine.tv.protocol.ReceiverEvent.Seek) {
                    if (::playerControlsView.isInitialized) {
                        val targetMillis = (event.snapshot.positionSeconds * 1000).toLong()
                        playerControlsView.noteExternalSeek(
                            targetMillis = targetMillis,
                            offsetMillis = (event.offsetSeconds * 1_000).toLong(),
                        )
                        if (playerControlsVisible) {
                            restartPlayerControlsHideTimer()
                        } else {
                            showPlayerSeekPreview(refreshSnapshot = false)
                            restartSeekPreviewHideTimer()
                        }
                    }
                }
                // Nothing is playing any more: a seek bar over a stopped stream is a lie.
                if (!playbackActive) {
                    if (::playerControlsView.isInitialized) playerControlsView.resetTimeline()
                    hidePlayerControls()
                }
                if (playbackActive) {
                    // Something is playing again: the old crumb is history, not an offer.
                    resumeOffer = null
                    startResumeSaving()
                    pushRecentRow()
                } else {
                    stopResumeSaving()
                    // The next open gets its own row; without this a re-open of the same title
                    // would never be re-pushed after the shelf had been rewritten by the phone.
                    recentsRowUrl = null
                }
                renderOverlay()
            }
        }

        // The proxy re-exposes the Exo side channels across engine swaps; the direct downcast is
        // the fallback for a hypothetical non-proxy build.
        val exoChannels = controller as? SwappableReceiverController
        if (exoChannels != null) {
            activityScope.launch {
                exoChannels.cues.collect { cues -> subtitleView.setCues(cues) }
            }
            activityScope.launch {
                exoChannels.videoAspectRatio.collect { ratio ->
                    currentVideoRatio = ratio
                    applyVideoResizeMode(currentResizeMode)
                }
            }
            activityScope.launch {
                exoChannels.subtitleStyle.collect { style -> applySubtitleStyle(style) }
            }
        } else {
            (controller as? ExoReceiverController)?.let { exo ->
                activityScope.launch {
                    exo.cues.collect { cues -> subtitleView.setCues(cues) }
                }
                activityScope.launch {
                    exo.videoAspectRatio.collect { ratio ->
                        currentVideoRatio = ratio
                        applyVideoResizeMode(currentResizeMode)
                    }
                }
                activityScope.launch {
                    exo.subtitleStyle.collect { style -> applySubtitleStyle(style) }
                }
            }
        }

        activityScope.launch {
            com.fourseveneightnine.tv.protocol.NowPlayingArtwork.art.collect { art ->
                applyBackdrop(art)
            }
        }

        // The phone's Continue-Watching list, mirrored onto the home rail (`X4789.SetRecents`).
        // Without it a freshly-installed box shows a blank shelf even though the paired phone knows
        // exactly what the user is halfway through. Re-rendered on every push, which is cheap: the
        // rail is at most twenty 130dp cards and the posters come out of `artworkLoader`'s cache.
        activityScope.launch {
            com.fourseveneightnine.tv.protocol.PhoneRecents.entries.collect { entries ->
                if (entries == null || !::recentsStore.isInitialized) return@collect
                recentsStore.replacePhoneRecents(
                    entries.map { entry ->
                        RecentItem(
                            url = entry.url,
                            title = entry.title,
                            subtitle = entry.subtitle,
                            posterUrl = entry.posterUrl,
                            backdropUrl = entry.landscapeUrl,
                            overview = entry.overview,
                            positionMillis = entry.positionMillis,
                            durationMillis = entry.durationMillis,
                            timestamp = entry.timestamp,
                            fromPhone = true,
                        )
                    },
                )
                refreshLibrarySnapshot()
                renderRecentsShelf()
            }
        }

        activityScope.launch {
            controller.diagnostics.collect { diagnostics ->
                currentDiagnostics = diagnostics
                if (autoFrameRateEnabled && diagnostics.active && frameRateEligible) {
                    applyContentFrameRate(diagnostics)
                } else if (!diagnostics.active) {
                    clearContentFrameRate()
                }
                refreshAfrPresentation()
                if (::playerControlsView.isInitialized) {
                    playerControlsView.setStreamQualityBadge(
                        PlayerControlsPolicy.formatQualityBadge(diagnostics),
                    )
                }
                renderDiagnostics()
            }
        }

        activityScope.launch {
            advertiser.status.collect { status ->
                if (!activityStarted) return@collect
                var shouldReconcile = false
                when (status) {
                    is NsdAdvertisementStatus.Advertising -> {
                        if (surfaceReady && transportRunning && advertisingRequested) {
                            startupState = StartupState.Ready
                        }
                    }

                    is NsdAdvertisementStatus.Error -> {
                        if (advertisingRequested) {
                            startupState = StartupState.Error(NETWORK_START_ERROR)
                        }
                    }

                    NsdAdvertisementStatus.Starting -> {
                        if (advertisingRequested && startupState !is StartupState.Error) {
                            startupState = StartupState.Starting
                        }
                    }

                    NsdAdvertisementStatus.Stopping -> {
                        if (advertisingRequested && startupState !is StartupState.Error) {
                            startupState = StartupState.Starting
                        }
                    }

                    NsdAdvertisementStatus.Stopped -> {
                        // Ignore a stale collection emission if a synchronous start has already
                        // advanced the advertiser. Otherwise a Ready UI must not outlive the
                        // registration it represents.
                        if (
                            advertisingRequested &&
                            advertiser.status.value is NsdAdvertisementStatus.Stopped
                        ) {
                            advertisingRequested = false
                            startupState = StartupState.Starting
                            shouldReconcile = true
                        } else if (startupState is StartupState.Ready) {
                            startupState = StartupState.Starting
                        }
                    }
                }
                renderOverlay()
                if (shouldReconcile) reconcileReceiver()
            }
        }
    }

    private fun reconcileReceiver() {
        activityScope.launch {
            sessionMutex.withLock {
                reconcileReceiverLocked()
            }
        }
    }

    private suspend fun reconcileReceiverLocked() {
        while (true) {
            // This executes on the Activity's Main dispatcher. Re-checking on each iteration
            // closes the onStop → onStart race where teardown detached a still-valid Surface
            // after onStart had already observed it.
            attachSurfaceForReconciliationIfNeeded()
            when (ReceiverLifecyclePlanner.next(
                    ReceiverLifecycleState(
                        activityStarted = activityStarted,
                        surfaceReady = surfaceReady,
                        transportRunning = transportRunning,
                        advertisingRequested = advertisingRequested,
                    ),
                )) {
                ReceiverLifecycleAction.StartTransport -> {
                    startupState = StartupState.Starting
                    renderOverlay()
                    if (!startTransportLocked()) return
                }

                ReceiverLifecycleAction.StartAdvertising -> {
                    advertisingRequested = true
                    if (!advertiser.start()) {
                        advertisingRequested = false
                        startupState = StartupState.Error(NETWORK_START_ERROR)
                        renderOverlay()
                    }
                    return
                }

                ReceiverLifecycleAction.StopAll -> {
                    stopReceiverForVisibilityLossLocked()
                    // A rapid onStop → onStart can leave a valid holder after teardown. Loop so
                    // the valid-holder reconciliation above can reattach it without waiting for
                    // another framework Surface callback.
                    continue
                }

                ReceiverLifecycleAction.Idle -> return
            }
        }
    }

    private suspend fun startTransportLocked(): Boolean {
        // Treat an in-flight bind as running for teardown purposes. If Activity destruction
        // cancels this coroutine after Ktor bound its ports but before it resumes on Main,
        // stopTransportLocked still must call the idempotent transport stop.
        transportRunning = true
        return try {
            withContext(Dispatchers.IO) {
                transport.start()
            }
            true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            transportRunning = false
            startupState = StartupState.Error(NETWORK_START_ERROR)
            renderOverlay()
            false
        }
    }

    /** Runs teardown even if onDestroy cancels ordinary Activity jobs during Ktor shutdown. */
    private fun stopReceiverForVisibilityLoss() {
        activityScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                sessionMutex.withLock {
                    stopReceiverForVisibilityLossLocked()
                }
            }
        }
    }

    /** Required stop order: revoke Surface readiness, discovery, transport, then player. */
    private suspend fun stopReceiverForVisibilityLossLocked() {
        // This is deliberately first and synchronous: it invalidates any queued Player.Open's
        // captured Surface generation before we wait for transport teardown.
        controller.detachSurface()
        surfaceAttached = false
        surfaceReady = false
        advertisingRequested = false
        advertiser.stop()
        stopTransportLocked()
        stopPlaybackBestEffortLocked()
        playbackActive = false
    }

    private suspend fun stopTransportLocked() {
        if (!transportRunning) return
        try {
            withContext(Dispatchers.IO) {
                transport.stop()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // Ktor stop is best effort here; no server is retained by this Activity after stop.
        } finally {
            transportRunning = false
        }
    }

    private suspend fun stopPlaybackBestEffortLocked() {
        try {
            // A loadfile can be accepted before libmpv emits START_FILE, so a snapshot/event
            // cannot decide whether a queued stop is required. stop() is no-op safe if no
            // player exists and follows any accepted loadfile on the native dispatcher.
            controller.stop()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // The controller can already be closing when Android tears down a Surface.
        }
        playbackActive = false
    }

    private fun attachCurrentSurfaceIfAvailable() {
        attachSurface(surfaceView.holder)
    }

    private fun attachSurfaceForReconciliationIfNeeded() {
        val holder = surfaceView.holder
        val state = ReceiverLifecycleState(
            activityStarted = activityStarted,
            surfaceReady = surfaceReady,
            transportRunning = transportRunning,
            advertisingRequested = advertisingRequested,
            surfaceAttached = surfaceAttached,
            surfaceAvailable = holder.surface.isValid,
        )
        if (ReceiverLifecyclePlanner.shouldAttachSurface(state)) {
            attachSurface(holder)
        }
    }

    private fun attachSurface(holder: SurfaceHolder) {
        if (surfaceAttached || !holder.surface.isValid) {
            ReceiverDiagnostics.record(
                "surface.attach.skipped",
                "alreadyAttached=$surfaceAttached valid=${holder.surface.isValid}",
            )
            return
        }
        surfaceAttached = true
        surfaceReady = false
        val frame = holder.surfaceFrame
        ReceiverDiagnostics.record("surface.attach.requested", "size=${frame.width()}x${frame.height()}")
        (controller as? SwappableReceiverController)
            ?.attachSurfaceHolder(holder, frame.width(), frame.height())
            ?: ((controller as? ExoReceiverController)?.attachSurfaceHolder(holder)
                ?: controller.attachSurface(holder.surface, frame.width(), frame.height()))
        resumePendingSoftwareOpenIfNeeded()
        scheduleSurfaceReadyTimeout()
    }

    /** Resumes a cast that was restarted after a hardware-decode stall, using software decode. */
    private fun resumePendingSoftwareOpenIfNeeded() {
        val pending = controller.consumePendingSoftwareOpen() ?: return
        activityScope.launch {
            controller.surfaceReady.first { ready -> ready }
            controller.openWithSoftware(pending)
        }
    }

    private fun scheduleSurfaceReadyTimeout() {
        surfaceReadyTimeout?.cancel()
        surfaceReadyTimeout = activityScope.launch {
            delay(SURFACE_READY_TIMEOUT_MILLIS)
            if (activityStarted && surfaceAttached && !surfaceReady) {
                startupState = StartupState.Error(SURFACE_START_ERROR)
                renderOverlay()
            }
        }
    }

    private fun retryStartup() {
        if (!activityStarted) return
        surfaceReadyTimeout?.cancel()
        surfaceReadyTimeout = null
        startupState = StartupState.Starting
        renderOverlay()

        activityScope.launch {
            sessionMutex.withLock {
                controller.detachSurface()
                surfaceAttached = false
                surfaceReady = false
                advertisingRequested = false
                advertiser.stop()
                stopTransportLocked()
                stopPlaybackBestEffortLocked()
                attachCurrentSurfaceIfAvailable()
                reconcileReceiverLocked()
            }
        }
    }


    // ---- Resume-after-reopen -------------------------------------------------------------------

    /**
     * Keeps a crumb of the live cast on disk. Written on a slow tick rather than on every position
     * change: this is a "close the app by accident" safety net, not a progress database, and the
     * phone remains the owner of Continue Watching (DECISIONS) — nothing here is ever read by it.
     */
    private fun startResumeSaving() {
        if (resumeSaveJob?.isActive == true) return
        resumeSaveJob = activityScope.launch {
            while (true) {
                delay(RESUME_SAVE_INTERVAL_MILLIS)
                saveResumePoint()
            }
        }
    }

    private fun stopResumeSaving() {
        resumeSaveJob?.cancel()
        resumeSaveJob = null
    }

    /**
     * Writes this title's recents row, once, when it starts playing.
     *
     * Keyed on the URL so a replacement open gets its own row and a pause/resume does not rewrite
     * the blob. The position on this row is the position at open; [saveResumePoint] moves it on
     * afterwards, off the main thread.
     */
    private fun pushRecentRow() {
        if (!::recentsStore.isInitialized) return
        val media = controller.lastOpenMedia() ?: return
        if (media.url == recentsRowUrl) return
        recentsRowUrl = media.url
        val artwork = com.fourseveneightnine.tv.protocol.NowPlayingArtwork.art.value
        val poster = artwork?.posterURL
        val title = media.title?.takeIf(String::isNotBlank) ?: getString(R.string.recents_untitled)
        // A detail row is authoritative only for the title currently opened from it. Never attach
        // an unrelated previous-detail overview to a generic receiver open.
        val currentDetail = libraryDetailItem?.takeIf { it.title == title }
        val currentContinue = continuePlaybackItem?.takeIf { it.title == title }
        activityScope.launch(Dispatchers.IO) {
            recentsStore.push(
                RecentItem(
                    url = media.url,
                    title = title,
                    subtitle = media.subtitle,
                    posterUrl = poster,
                    backdropUrl = artwork?.landscapeURL,
                    overview = TVReceiverPresentationPolicy.recentOverviewForLocalPush(
                        detailOverview = currentDetail?.overview,
                        continueOverview = currentContinue?.overview,
                    ),
                ),
            )
        }
    }

    /**
     * Handoff targets on this box. Cached — see [cachedExternalPlayers] for why the uncached call
     * was costing the controls their first frame.
     */
    private fun externalPlayers(): List<InstalledExternalPlayer> =
        cachedExternalPlayers ?: ExternalPlayerIntentPolicy.installedPlayers(this)
            .also { cachedExternalPlayers = it }

    private fun registerPackageChangeReceiver() {
        if (packageChangeReceiverRegistered) return
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        registerReceiver(packageChangeReceiver, filter)
        packageChangeReceiverRegistered = true
    }

    private fun unregisterPackageChangeReceiver() {
        if (!packageChangeReceiverRegistered) return
        runCatching { unregisterReceiver(packageChangeReceiver) }
        packageChangeReceiverRegistered = false
    }

    private fun saveResumePoint() {
        if (!::resumeStore.isInitialized) return
        val media = controller.lastOpenMedia() ?: return
        activityScope.launch {
            val snapshot = runCatching { controller.snapshot() }.getOrNull() ?: return@launch
            val positionMillis = (snapshot.positionSeconds * 1_000).toLong()
            val durationMillis = (snapshot.durationSeconds * 1_000).toLong()
            if (!com.fourseveneightnine.tv.player.ResumePolicy.shouldStore(positionMillis, durationMillis)) {
                return@launch
            }
            val art = com.fourseveneightnine.tv.protocol.NowPlayingArtwork.art.value
            // The recents row rides this same slow tick rather than the 500ms controls poll.
            if (::recentsStore.isInitialized && media.url == recentsRowUrl) {
                withContext(Dispatchers.IO) {
                    recentsStore.updatePosition(media.url, positionMillis, durationMillis)
                }
            }
            withContext(Dispatchers.IO) {
                resumeStore.save(
                    com.fourseveneightnine.tv.player.ResumePoint(
                        url = media.url,
                        title = media.title,
                        subtitle = media.subtitle,
                        headers = media.headers,
                        positionMillis = positionMillis,
                        durationMillis = durationMillis,
                        savedAtMillis = System.currentTimeMillis(),
                        artworkURL = art?.landscapeURL,
                        posterURL = art?.posterURL,
                    ),
                )
            }
        }
    }

    /** Reads the crumb at start and decides whether it is still worth offering. */
    private fun loadResumeOffer() {
        if (!::resumeStore.isInitialized) return
        val stored = resumeStore.load()
        resumeOffer = stored?.takeIf {
            com.fourseveneightnine.tv.player.ResumePolicy.shouldOffer(it, System.currentTimeMillis())
        }
        val offer = resumeOffer
        if (offer != null) {
            ReceiverDiagnostics.record(
                "resume.offer",
                "pos=${offer.positionMillis / 1000}s ageMin=${(System.currentTimeMillis() - offer.savedAtMillis) / 60_000}",
            )
            // The film's own art behind its own resume prompt, for free — it was stored with it.
            com.fourseveneightnine.tv.protocol.NowPlayingArtwork.stage(offer.artworkURL, offer.posterURL)
        }
    }

    /**
     * Reopens the stored stream and jumps back to where it was.
     *
     * The URL is usually a short-lived debrid/CDN bearer link, so this is allowed to fail — and when
     * it does the viewer is told the link expired, not shown a codec error, because re-casting from
     * the phone is the actual fix.
     */
    private fun resumeStoredPoint() {
        val offer = resumeOffer ?: return
        if (resumeInFlight) return
        resumeInFlight = true
        resumeOffer = null
        startupState = StartupState.Starting
        renderOverlay()
        activityScope.launch {
            val opened = controller.open(
                OpenMediaRequest(
                    url = offer.url,
                    title = offer.title,
                    subtitle = offer.subtitle,
                    headers = offer.headers,
                ),
            ).isSuccess
            if (!opened) {
                resumeInFlight = false
                startupState = StartupState.Error(RESUME_EXPIRED_ERROR)
                renderOverlay()
                return@launch
            }
            // Seek only once the fresh open is actually playing; seeking a preparing player is a
            // no-op that silently drops the viewer back at 0:00.
            val landed = kotlinx.coroutines.withTimeoutOrNull(RESUME_READY_TIMEOUT_MILLIS) {
                controller.playbackPhase.first {
                    it is ReceiverPlaybackPhase.Playing || it is ReceiverPlaybackPhase.Paused
                }
            }
            resumeInFlight = false
            if (landed == null) {
                startupState = StartupState.Error(RESUME_EXPIRED_ERROR)
                renderOverlay()
                return@launch
            }
            val seekSucceeded = controller.seek(
                SeekCommand.AbsoluteSeconds(offer.positionMillis / 1_000.0),
            ).isSuccess
            if (ResumePolicy.shouldNotifyPhoneAfterStoredResume(
                    opened = opened,
                    landed = true,
                    seekSucceeded = seekSucceeded,
                )
            ) {
                transport.notifyLocalControl("resume", controller.snapshot())
            }
        }
    }

    private fun createContentView(): View {
        // The home canvas is composed entirely from the shared receiver slate tokens.
        val root = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    getColor(R.color.tv_black),
                    getColor(R.color.tv_elevated),
                    getColor(R.color.tv_slate),
                ),
            )
        }

        // PITCH BLACK BEHIND THE PICTURE. `videoFrame` letterboxes the SurfaceView to the decoded
        // aspect, and whatever sits behind shows in the gap. On a dark scene that slate
        // is plainly visible above and below the picture, and it is the one thing a television must
        // never do: a scope film has to sit in black, not in the app's furniture.
        //
        // A separate view rather than repainting `root`: the gradient is the HOME screen's identity
        // and has to survive untouched. This is added FIRST so it is behind the video, and is only
        // raised while the overlay is down — i.e. exactly when the picture is the only thing on screen.
        letterboxBackingView = View(this).apply {
            // `tv_black` is intentionally a blue-black for the library theme. It is not black
            // enough for natural film letterboxing: Fire OS exposes this view in the fit gaps,
            // so using the theme token creates visible grey bands around a true-black matte.
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(
            letterboxBackingView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        surfaceView = SurfaceView(this).apply {
            isFocusable = false
            keepScreenOn = false
            // The video surface is an opaque layer. A translucent SurfaceView can expose the
            // receiver's gradient through the narrow fit gaps as grey strips on Fire OS.
            holder.setFormat(android.graphics.PixelFormat.OPAQUE)
        }
        // A bare MATCH_PARENT SurfaceView stretches every stream to the panel — SD and anamorphic
        // titles came out distorted. AspectRatioFrameLayout letterboxes to the decoded ratio;
        // ratio 0 (mpv path, or before the first frame) leaves it unconstrained as before.
        videoFrame = AspectRatioFrameLayout(this).apply {
            setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)
            setAspectRatio(0f)
        }
        videoFrame.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            videoFrame,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )

        // Continue Watching opens are resolver work before they are player work. Keep that wait
        // honest: show the title's own high-resolution landscape art edge-to-edge, with only a
        // small bottom status readout. The old centred card/brand/buttons made a stream error look
        // like the home screen was still underneath playback and swallowed the viewer's focus.
        playbackBackdropView = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(
            playbackBackdropView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        playbackPosterView = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(
            playbackPosterView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        playbackStatusView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isFocusable = false
            isClickable = false
            setPadding(dp(28), dp(18), dp(28), dp(18))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, tokenAlpha(R.color.tv_black, 190)),
            )
        }
        playbackStatusTitle = textView(22f, R.color.tv_text_primary).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        playbackStatusDetail = textView(15f, R.color.tv_text_secondary)
        playbackStatusProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            max = 1000
            progress = 0
        }
        playbackStatusView.addView(
            playbackStatusTitle,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        playbackStatusView.addView(
            playbackStatusDetail,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            },
        )
        playbackStatusView.addView(
            playbackStatusProgress,
            LinearLayout.LayoutParams(dp(440), dp(6)).apply { topMargin = dp(12) },
        )
        root.addView(
            playbackStatusView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ).apply { bottomMargin = dp(48) },
        )

        // ExoPlayer decodes cues but renders nothing on its own — without this view the receiver
        // reported subtitle tracks to the phone while the television showed none.
        subtitleView = SubtitleView(this).apply {
            setUserDefaultStyle()
            setUserDefaultTextSize()
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(
            subtitleView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        // Added BEFORE the startup/error overlay so a playback error still covers the controls —
        // an error offering external-player buttons must never sit behind a seek bar.
        playerControlsView = PlayerControlsView(this, playerControlsListener).apply {
            visibility = View.GONE
        }
        root.addView(
            playerControlsView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        // Keep the last decoded frame visible during a cache dip, but explain the freeze with a
        // centered state indicator. The native indeterminate spinner keeps moving while loading;
        // the pill is presentation only and never takes focus from playback.
        bufferingChip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(24), dp(16), dp(28), dp(16))
            isFocusable = false
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            contentDescription = getString(R.string.buffering_accessibility)
            background = GradientDrawable().apply {
                cornerRadius = dp(30).toFloat()
                setColor(tokenAlpha(R.color.tv_black, 220))
                setStroke(dp(1), tokenAlpha(R.color.tv_text_primary, 34))
            }
            addView(
                ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleSmall).apply {
                    isIndeterminate = true
                    indeterminateTintList = android.content.res.ColorStateList.valueOf(
                        getColor(R.color.tv_accent),
                    )
                },
                LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(16) },
            )
            addView(
                textView(18f, R.color.tv_text_primary).apply {
                    text = getString(R.string.buffering)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        root.addView(
            bufferingChip,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )

        // Above the bar, below the error overlay: a choice list must cover the controls it is
        // changing, and a playback error must still cover everything.
        optionRail = OptionRailView(this).apply {
            onInteraction = { restartPlayerControlsHideTimer() }
        }
        root.addView(
            optionRail,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        endedPlate = EndedPlateView(this)
        root.addView(
            endedPlate,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        overlay = FrameLayout(this).apply {
            background = null
            isFocusable = true
            isFocusableInTouchMode = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        root.addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        // The title's own backdrop behind the startup/opening copy. First child of the overlay, so
        // every message the overlay draws sits on top of it.
        backdropView = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        overlay.addView(
            backdropView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        diagnosticsView = textView(TEXT_DIAGNOSTICS_SP, R.color.tv_text_primary).apply {
            gravity = Gravity.START
            setPadding(dp(18), dp(14), dp(18), dp(14))
            setBackgroundColor(tokenAlpha(R.color.tv_black, 210))
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(
            diagnosticsView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ).apply {
                leftMargin = dp(24)
                topMargin = dp(24)
            },
        )

        receiverHomeContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // v2: elevated card — warm slate, no stroke, luminance lift
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(28).toFloat()
                setColor(tokenAlpha(R.color.tv_elevated, 200))
            }
        }
        // THE OVERLAY MUST SCROLL. `content` is WRAP_CONTENT and was centred directly in a
        // MATCH_PARENT FrameLayout, so the moment brand + heading + buttons + the recents rail grew
        // past 1080p it was clipped at BOTH ends with no way to reach what fell off. That is the
        // whole "pressing down does nothing" report: D-pad focus DID move to a poster — focus search
        // does not care about clipping — but there was no scrollable ancestor, so the screen never
        // followed and the viewer saw an unchanged, half-cut shelf.
        //
        // A ScrollView fixes it structurally rather than by tuning sizes: `requestChildRectangleOnScreen`
        // pulls whatever gains focus into view, so the rail is reachable at any content height and
        // stays reachable when a row is added later.
        //
        // `isFillViewport` keeps the short case identical to before — with content shorter than the
        // panel the child still fills it and `Gravity.CENTER` still centres it, so the idle screen is
        // pixel-unchanged.
        overlayScroller = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            // TV OVERSCAN: Fire TV panels routinely crop ~5% of each edge, so a rail flush to the
            // bottom of the framebuffer is behind the bezel even after it scrolls into view.
            // `clipToPadding = false` keeps the card's own background edge-to-edge while the
            // scrollable content stays inside the safe area.
            clipToPadding = false
            setPadding(0, dp(OVERSCAN_INSET_DP), 0, dp(OVERSCAN_INSET_DP))
        }
        overlayScroller.addView(
            receiverHomeContent,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
        overlay.addView(
            overlayScroller,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )

        // The app's own mark, not a typed-out name: this is the first thing the television shows
        // and it should look like the phone app it pairs with.
        receiverBrandStage = FrameLayout(this).apply {
            visibility = View.VISIBLE
        }
        idlePingRing = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(dp(3), tokenAlpha(R.color.tv_accent, 115))
            }
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        receiverBrandStage.addView(
            idlePingRing,
            FrameLayout.LayoutParams(dp(IDLE_PING_RING_DP), dp(IDLE_PING_RING_DP), Gravity.CENTER),
        )
        receiverBrandView = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.brand_4789_tv)
            adjustViewBounds = true
            contentDescription = getString(R.string.app_name)
        }
        receiverBrandStage.addView(
            receiverBrandView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(BRAND_HEIGHT_DP), Gravity.CENTER),
        )
        receiverHomeContent.addView(
            receiverBrandStage,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(BRAND_HEIGHT_DP),
            ).apply { gravity = Gravity.CENTER_HORIZONTAL },
        )

        headingView = textView(TEXT_HEADING_SP, R.color.tv_text_primary).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            typeface = TvTokens.Type.display(this@MainActivity)
        }
        receiverHomeContent.addView(headingView, linearParams(topMargin = dp(HEADING_TOP_MARGIN_DP)))

        directionView = textView(TEXT_DIRECTION_SP, R.color.tv_text_secondary).apply {
            maxLines = 3
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        receiverHomeContent.addView(directionView, linearParams(topMargin = dp(DIRECTION_TOP_MARGIN_DP)))

        idleFactsView = textView(14f, R.color.tv_text_tertiary).apply {
            gravity = Gravity.CENTER
            setTypeface(TvTokens.Type.data(this@MainActivity))
            maxLines = 2
            setLineSpacing(dp(6).toFloat(), 1f)
            visibility = View.GONE
        }
        receiverHomeContent.addView(idleFactsView, linearParams(topMargin = dp(22)))

        progressView = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            max = 100
            contentDescription = getString(R.string.starting_receiver)
        }
        val progressParams = LinearLayout.LayoutParams(
            dp(320),
            dp(8),
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(PROGRESS_TOP_MARGIN_DP)
        }
        receiverHomeContent.addView(progressView, progressParams)

        retryButton = AppCompatButton(this).apply {
            text = getString(R.string.retry)
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_PX, TvTokens.designTextPx(this@MainActivity, TEXT_BUTTON_SP))
            minimumHeight = dp(BUTTON_MIN_HEIGHT_DP)
            minimumWidth = dp(200)
            contentDescription = getString(R.string.retry_receiver)
            setPadding(dp(32), dp(12), dp(32), dp(12))
            val updateBtnBg = { hasFocus: Boolean ->
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(24).toFloat()
                    setColor(getColor(if (hasFocus) R.color.tv_control_fill_focused else R.color.tv_control_fill))
                    setStroke(dp(2), getColor(if (hasFocus) R.color.tv_accent else R.color.tv_control_stroke))
                }
                setTextColor(getColor(if (hasFocus) R.color.tv_control_text_focused else R.color.tv_control_text))
            }
            updateBtnBg(false)
            setOnFocusChangeListener { _, hasFocus ->
                updateBtnBg(hasFocus)
                animate()
                    .scaleX(if (hasFocus) 1.05f else 1.0f)
                    .scaleY(if (hasFocus) 1.05f else 1.0f)
                    .setDuration(160)
                    .start()
            }
            setOnClickListener {
                when {
                    // A pending resume offer owns the button while the receiver is otherwise idle.
                    resumeOffer != null && startupState is StartupState.Ready -> resumeStoredPoint()
                    playbackPhase is ReceiverPlaybackPhase.Error -> {
                        directionView.text = getString(R.string.retry_fetching_fresh_link)
                        progressView.visibility = View.VISIBLE
                        activityScope.launch { controller.retryLastOpen() }
                    }

                    else -> retryStartup()
                }
            }
        }
        val retryBtnParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(RETRY_TOP_MARGIN_DP)
        }
        receiverHomeContent.addView(retryButton, retryBtnParams)

        settingsButton = AppCompatButton(this).apply {
            text = getString(R.string.settings)
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_PX, TvTokens.designTextPx(this@MainActivity, TEXT_BUTTON_SP))
            minimumHeight = dp(BUTTON_MIN_HEIGHT_DP)
            minimumWidth = dp(180)
            contentDescription = getString(R.string.settings_accessibility)
            setPadding(dp(28), dp(12), dp(28), dp(12))
            val updateBackground = { hasFocus: Boolean ->
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(24).toFloat()
                    setColor(getColor(if (hasFocus) R.color.tv_control_fill_focused else R.color.tv_control_fill))
                    setStroke(dp(2), getColor(if (hasFocus) R.color.tv_accent else R.color.tv_control_stroke))
                }
                setTextColor(getColor(if (hasFocus) R.color.tv_control_text_focused else R.color.tv_control_text))
            }
            updateBackground(false)
            setOnFocusChangeListener { _, hasFocus -> updateBackground(hasFocus) }
            setOnClickListener { showSettingsSurface() }
        }
        receiverHomeContent.addView(
            settingsButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(14)
            },
        )

        externalButtonsView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        externalButtonsScrollerView = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            visibility = View.GONE
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setPadding(dp(16), dp(4), dp(16), dp(4))
            addView(
                externalButtonsView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
        }
        receiverHomeContent.addView(
            externalButtonsScrollerView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(16)
                gravity = Gravity.CENTER_HORIZONTAL
            },
        )

        recentsTitleView = textView(TEXT_DIRECTION_SP, R.color.tv_accent).apply {
            text = "Recently on this TV"
            setTypeface(TvTokens.Type.display(this@MainActivity))
            visibility = View.GONE
        }
        receiverHomeContent.addView(
            recentsTitleView,
            linearParams(topMargin = dp(20)).apply { gravity = Gravity.START },
        )

        recentsLayoutView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        recentsScrollerView = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(8), dp(4), dp(8), dp(4))
            visibility = View.GONE
            addView(
                recentsLayoutView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        receiverHomeContent.addView(
            recentsScrollerView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(6)
                gravity = Gravity.CENTER_HORIZONTAL
            },
        )

        librarySurfaceView = ComposeView(this).apply {
            visibility = View.GONE
            isFocusable = true
            isFocusableInTouchMode = true
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                if (librarySurfaceActive) {
                    val tamilMVState by tamilMVCatalogRepository.state.collectAsState()
                    val tmdbState by tmdbCatalogRepository.state.collectAsState()
                    TVLibrarySurface(
                        items = libraryItems,
                        receiverAddress = libraryReceiverAddress,
                        settingsConfigured = librarySettingsConfigured,
                        artworkLoader = artworkLoader,
                        tamilMVState = tamilMVState,
                        tmdbState = tmdbState,
                        tamilJobStatus = tamilJobStatus,
                        letterboxdJobStatus = letterboxdJobStatus,
                        tmdbJobStatus = tmdbJobStatus,
                        letterboxdUsernames = libraryLetterboxdUsernames,
                        selectedDestination = libraryDestination,
                        ratingsFor = { candidate -> ratingsRepository.ratings(candidate) },
                        enrichedArtworkFor = { candidate -> artworkEnrichmentRepository.artwork(candidate) },
                        prefetchRatingsFor = { candidate -> ratingsRepository.ratings(candidate) },
                        destinationResetKey = libraryNavigationGeneration,
                        onDestinationChanged = { destination ->
                            // Focus previews the right-hand canvas only. Network refreshes are
                            // coalesced by each repository, so opening a destination from focus
                            // never leaves a cached shelf blank while waiting for an explicit Select.
                            libraryDestination = destination
                            when (destination) {
                                TVLibraryDestination.TamilMV,
                                TVLibraryDestination.LetterboxdLists,
                                TVLibraryDestination.NewFromFriends,
                                -> refreshTamilMVCatalog(force = false)
                                TVLibraryDestination.TMDBCatalogs -> refreshTMDBCatalog(force = false)
                                else -> Unit
                            }
                        },
                        onDestinationSelected = { destination ->
                            libraryNavigationGeneration += 1
                            libraryDestination = destination
                            if (destination == TVLibraryDestination.TamilMV ||
                                destination == TVLibraryDestination.LetterboxdLists ||
                                destination == TVLibraryDestination.NewFromFriends
                            ) {
                                // All private shelves share one signed snapshot. Hydrate it when
                                // any private collection is opened; otherwise Letterboxd/Friends
                                // appear empty simply because the user did not visit Tamil MV
                                // first.
                                refreshTamilMVCatalog(force = false)
                            }
                            if (destination == TVLibraryDestination.TMDBCatalogs) refreshTMDBCatalog(force = false)
                        },
                        onOpenSettings = { showSettingsSurface(TVSettingsDestination.Pair) },
                        onOpenItem = ::openRecentItem,
                        onRefreshTamilMV = { refreshTamilMVCatalog(force = true) },
                        onRefreshLetterboxd = { refreshCollectionJob("Letterboxd") },
                        onRefreshTMDB = { refreshTMDBCatalog(force = true) },
                        onResync = { showSettingsSurface(TVSettingsDestination.Pair) },
                        addonSourceState = addonSourceState,
                        onRequestTamilMVSources = ::requestTamilMVSources,
                        onEpisodeSelected = { episodeItem ->
                            requestTamilMVSources(episodeItem)
                        },
                        onCancelTamilMVSources = { cancelTamilMVSourceFlow(stopPendingOpen = true) },
                        onPlayTamilMVSource = ::playTamilMVSource,
                        restoredDetailItem = libraryDetailItem,
                        restoredDetailLabel = libraryDetailLabel,
                        onDetailChanged = { item, label ->
                            libraryDetailItem = item
                            libraryDetailLabel = label
                        },
                    )
                }
            }
        }
        root.addView(
            librarySurfaceView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        settingsSurfaceView = ComposeView(this).apply {
            visibility = View.GONE
            isFocusable = true
            isFocusableInTouchMode = true
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                if (settingsSurfaceActive) {
                    val pairingState by settingsCoordinator.state.collectAsState()
                    TVSettingsSurface(
                        state = pairingState,
                        receiverName = getString(R.string.app_name),
                        pairingRequired = settingsStateLoaded && !librarySettingsConfigured,
                        presentation = settingsPresentationSnapshot,
                        initialDestination = settingsEntryDestination,
                        onClose = { hideSettingsSurface(resetPairing = true) },
                        onBeginPairing = {
                            activityScope.launch {
                                settingsCoordinator.beginPairing(LocalNetworkAddress.currentIPv4())
                            }
                        },
                        onImportFile = {
                            settingsFilePickerActive = true
                            settingsFilePicker.launch(
                                // Fire OS often reports files copied into /Download with a
                                // vendor MIME type (or no type at all). Filtering to JSON made a
                                // valid settings export disappear from DocumentsUI, so the
                                // receiver validates the bytes after the user picks them instead.
                                arrayOf("*/*"),
                            )
                        },
                        onInvitationExpired = {
                            activityScope.launch { settingsCoordinator.expireInvitation() }
                        },
                        onApprove = { keepSync ->
                            activityScope.launch {
                                settingsCoordinator.approve(keepSync)
                                librarySettingsConfigured =
                                    settingsCoordinator.state.value is TVSettingsPairingState.Saved
                                refreshSettingsPresentationSnapshot()
                                if (librarySettingsConfigured) {
                                    refreshLibraryDisplayMetadata()
                                    refreshTamilMVCatalog(force = true)
                                    // A successful first pairing is the handoff into the TV app:
                                    // return the user to My Stuff instead of leaving them on the
                                    // receipt page after the phone has finished sending setup.
                                    hideSettingsSurface(resetPairing = false)
                                }
                            }
                        },
                        onReject = { activityScope.launch { settingsCoordinator.reject() } },
                        onDisconnectSync = {
                            activityScope.launch { settingsCoordinator.disconnectSync() }
                        },
                        onClearSetup = {
                            activityScope.launch {
                                val wasConfigured = librarySettingsConfigured
                                settingsCoordinator.clearReceiverSetup()
                                val cleared = settingsCoordinator.state.value is TVSettingsPairingState.Idle
                                librarySettingsConfigured = if (cleared) false else wasConfigured
                                refreshSettingsPresentationSnapshot()
                                if (cleared) {
                                    libraryLetterboxdUsernames = emptyList()
                                    tamilMVCatalogRepository.clear()
                                    settingsEntryDestination = TVSettingsDestination.Pair
                                }
                            }
                        },
                        onRefreshTMDB = { refreshTMDBCatalog(force = true) },
                    )
                }
            }
        }
        root.addView(
            settingsSurfaceView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        renderOverlay()
        renderDiagnostics()
        return root
    }

    /**
     * Draw the Continue-Watching rail — but ONLY when its contents actually changed.
     *
     * WHY THE GUARD IS THE WHOLE POINT: `renderOverlay()` calls this, and `renderOverlay()` runs
     * from eighteen places — every playback-phase emission, every startup-state change, every
     * reconcile. Each run used to `removeAllViews()` and rebuild the cards from scratch. If the
     * viewer had a poster focused when any of that fired, the focused View was DESTROYED under
     * them: focus fell to nothing and the next D-pad press did nothing at all. That is the
     * "sometimes it works, sometimes it doesn't" — it was never random, it tracked whatever the
     * receiver happened to emit while the viewer was browsing.
     *
     * The signature is content-derived, so a genuine change (a phone push, a new local play, a
     * position update) still redraws, and a no-op state tick now costs nothing and touches nothing.
     */
    /**
     * Place D-pad focus for an overlay state — WITHOUT stealing it from the viewer.
     *
     * The old code called `retryButton.requestFocus()` / `overlay.requestFocus()` unconditionally at
     * the end of every `renderOverlay()`. Since that runs on every playback-phase and startup-state
     * emission, a viewer browsing the recents rail would silently have focus yanked back to the
     * button by a snapshot they never asked for — and their next LEFT/RIGHT press moved nothing.
     *
     * Two rules, and only two:
     *   • the overlay STATE CHANGED (a real transition, e.g. an error appearing) → move focus, since
     *     the thing they were looking at is no longer what the screen is about;
     *   • nothing usable holds focus → place it, or the next D-pad press lands nowhere.
     * Any other render leaves focus exactly where the viewer put it.
     */
    private fun placeOverlayFocus(state: String, preferred: View?) {
        if (!::overlay.isInitialized || overlay.visibility != View.VISIBLE) {
            renderedOverlayState = state
            return
        }
        val stateChanged = state != renderedOverlayState
        renderedOverlayState = state
        val focused = currentFocus
        val focusIsUsable = focused != null && focused.isShown && focused.isFocusable
        if (!stateChanged && focusIsUsable) return

        val target = preferred?.takeIf { it.visibility == View.VISIBLE && it.isFocusable }
        if (target != null) {
            target.requestFocus()
            return
        }
        // No specific control wants focus (the idle "ready for iPhone" screen). Prefer a real,
        // actionable card over the overlay itself so DOWN/LEFT/RIGHT have somewhere to go from the
        // very first press; fall back to the overlay only when nothing actionable exists.
        //
        // `overlay` is a MATCH_PARENT FrameLayout, so it is the LAST resort and never a good one:
        // Android picks directional focus by comparing bounds, and nothing lies in any direction
        // from a full-screen view. Focus parked there is focus a remote cannot move — the viewer
        // gets a screen that only BACK can leave. On the idle screen the recents rail is empty, so
        // before giving up, try the external-player buttons, which are the only controls present.
        val firstCard = recentsLayoutView.takeIf {
            ::recentsLayoutView.isInitialized && it.childCount > 0 && it.isShown
        }?.getChildAt(0)
        (firstCard ?: visibleSettingsButton() ?: firstExternalButton() ?: overlay).requestFocus()
    }

    private fun visibleSettingsButton(): View? {
        if (!::settingsButton.isInitialized) return null
        return settingsButton.takeIf { it.visibility == View.VISIBLE && it.isFocusable }
    }

    /** The first external-player button that can actually take focus, or null when none can. */
    private fun firstExternalButton(): View? {
        if (!::externalButtonsView.isInitialized) return null
        if (!externalButtonsView.isShown) return null
        return (0 until externalButtonsView.childCount)
            .map(externalButtonsView::getChildAt)
            .firstOrNull { it.visibility == View.VISIBLE && it.isFocusable }
    }

    /**
     * Hand focus back to the overlay after the thing that HELD it was dismissed (the player bar, the
     * option rail). Unconditional on purpose — unlike `placeOverlayFocus`, here the previously
     * focused View really is going away, so leaving focus alone would leave the D-pad pointing at a
     * view that is fading out.
     *
     * Prefers a real control over the bare overlay, in the order the viewer would reach for them:
     * the action button if there is one, else the first poster, else the overlay as a last resort.
     */
    private fun restoreOverlayFocusAfterDismiss() {
        if (!::overlay.isInitialized || overlay.visibility != View.VISIBLE) return
        val button = retryButton.takeIf { ::retryButton.isInitialized && it.visibility == View.VISIBLE }
        val firstCard = recentsLayoutView.takeIf {
            ::recentsLayoutView.isInitialized && it.childCount > 0 && it.isShown
        }?.getChildAt(0)
        // Same last-resort rule as placeOverlayFocus: the full-screen overlay is a focus dead end
        // for a D-pad, so try the external-player buttons before settling for it.
        (button ?: firstCard ?: visibleSettingsButton() ?: firstExternalButton() ?: overlay).requestFocus()
    }

    private fun showSettingsSurface(
        destination: TVSettingsDestination = TVSettingsDestination.Pair,
    ) {
        val presentationSurface = when (playbackPhase) {
            is ReceiverPlaybackPhase.Ended -> TVReceiverPresentationPolicy.Surface.Ended
            is ReceiverPlaybackPhase.Opening,
            ReceiverPlaybackPhase.Buffering,
            ReceiverPlaybackPhase.Playing,
            ReceiverPlaybackPhase.Paused,
            -> TVReceiverPresentationPolicy.Surface.Play
            else -> when {
                librarySurfaceActive && libraryDetailItem != null -> TVReceiverPresentationPolicy.Surface.Detail
                librarySurfaceActive -> TVReceiverPresentationPolicy.Surface.Home
                else -> TVReceiverPresentationPolicy.Surface.Idle
            }
        }
        if (!::settingsSurfaceView.isInitialized ||
            !TVReceiverPresentationPolicy.canOpenSettings(presentationSurface)
        ) return
        refreshSettingsPresentationSnapshot()
        hidePlayerControls()
        settingsEntryDestination = destination
        settingsSurfaceVisible = true
        hideLibrarySurface()
        blockReceiverHomeFocus()
        settingsSurfaceView.visibility = View.VISIBLE
        settingsSurfaceView.requestFocus()
        settingsSurfaceActive = true
        settingsSurfaceView.bringToFront()
    }

    private fun hideSettingsSurface(resetPairing: Boolean) {
        val wasVisible = settingsSurfaceVisible
        settingsSurfaceVisible = false
        settingsSurfaceActive = false
        if (::settingsSurfaceView.isInitialized) {
            settingsSurfaceView.clearFocus()
            settingsSurfaceView.visibility = View.GONE
        }
        releaseReceiverHomeFocus()
        if (resetPairing && ::settingsCoordinator.isInitialized) {
            activityScope.launch(start = CoroutineStart.UNDISPATCHED) {
                withContext(NonCancellable) { settingsCoordinator.close() }
            }
        }
        if (wasVisible && activityStarted) {
            renderOverlay()
            if (!librarySurfaceActive) restoreOverlayFocusAfterDismiss()
        }
    }

    private fun refreshLibrarySnapshot() {
        if (!::recentsStore.isInitialized || !::settingsCoordinator.isInitialized) return
        val recents = recentsStore.getRecents()
        if (libraryItems != recents) libraryItems = recents
        val configured = settingsCoordinator.state.value is TVSettingsPairingState.Saved
        if (librarySettingsConfigured != configured) librarySettingsConfigured = configured
    }

    /**
     * Produces display-only settings facts at a lifecycle edge. Credentials deliberately never
     * cross this boundary: the configured canvas renders their fixed REDACTED receipt state.
     */
    private fun refreshSettingsPresentationSnapshot() {
        if (!::settingsCoordinator.isInitialized) return
        val pairingState = settingsCoordinator.state.value
        val generation = ++settingsPresentationGeneration
        settingsPresentationJob?.cancel()
        if (pairingState !is TVSettingsPairingState.Saved) {
            settingsPresentationSnapshot = null
            settingsPresentationJob = null
            return
        }
        val engine = if (::controller.isInitialized) {
            (controller as? SwappableReceiverController)?.engine ?: activeEngine
        } else {
            activeEngine
        }
        // This is synchronous only because it contains no disk/keystore work. The generation is
        // still advanced before every lifecycle transition so a future asynchronous producer
        // cannot publish into a cleared or re-opened settings canvas.
        if (generation == settingsPresentationGeneration) {
            settingsPresentationSnapshot = TVSettingsPresentationSnapshot.from(
                paired = true,
                syncEnabled = pairingState.syncEnabled,
                engineLabel = engine.name.uppercase(),
                autoFrameRateEnabled = autoFrameRateEnabled,
                diagnosticsEnabled = showDiagnostics,
            )
        }
    }

    /**
     * Populate the small, display-only values consumed by the Compose library once per lifecycle.
     * Both helpers can cross framework/network boundaries on Fire OS, so neither belongs in the
     * composition lambda. The usernames are refreshed after a new settings save as well; a phone
     * sync therefore updates the Letterboxd rail without putting Keystore work on every D-pad frame.
     */
    private fun refreshLibraryDisplayMetadata() {
        activityScope.launch(Dispatchers.IO) {
            val usernames = TVPrivateCatalogCredentialPolicy.letterboxdUsernames(settingsStore.load())
            val address = withContext(Dispatchers.Default) { LocalNetworkAddress.currentIPv4().orEmpty() }
            withContext(Dispatchers.Main.immediate) {
                if (!activityStarted) return@withContext
                libraryLetterboxdUsernames = usernames
                libraryReceiverAddress = address
            }
        }
    }

    private fun updateLibrarySurface() {
        if (!::librarySurfaceView.isInitialized || !settingsStateLoaded) return
        val homePhase = playbackPhase is ReceiverPlaybackPhase.Idle ||
            playbackPhase is ReceiverPlaybackPhase.Stopped
        val settingsConfigured = ::settingsCoordinator.isInitialized &&
            settingsCoordinator.state.value is TVSettingsPairingState.Saved
        val shouldShow = TVLibrarySurfacePolicy.shouldShow(
            startupReady = startupState is StartupState.Ready,
            homePhase = homePhase,
            settingsVisible = settingsSurfaceVisible,
            settingsConfigured = settingsConfigured,
        )
        applyPresentationSurface(
            when {
                playbackPhase is ReceiverPlaybackPhase.Ended -> TVReceiverPresentationPolicy.Surface.Ended
                ReceiverPlaybackPhasePolicy.showsPicture(playbackPhase) -> TVReceiverPresentationPolicy.Surface.Play
                !receiverIdleRequested && shouldShow && libraryDetailItem != null -> TVReceiverPresentationPolicy.Surface.Detail
                !receiverIdleRequested && shouldShow -> TVReceiverPresentationPolicy.Surface.Home
                else -> TVReceiverPresentationPolicy.Surface.Idle
            },
        )
        if (TVLibrarySurfacePolicy.shouldShowPairing(
                startupReady = startupState is StartupState.Ready,
                homePhase = homePhase,
                settingsVisible = settingsSurfaceVisible,
                settingsConfigured = settingsConfigured,
            )
        ) {
            showSettingsSurface(TVSettingsDestination.Pair)
        }
    }

    /**
     * The one layer coordinator for receiver presentation. Individual render branches provide
     * their data and copy, but none decides whether Home/Detail/Play/Ended/Idle is mounted.
     */
    private fun applyPresentationSurface(surface: TVReceiverPresentationPolicy.Surface) {
        val mounted = TVReceiverPresentationPolicy.mountedSurfaces(surface)
        if (surface == TVReceiverPresentationPolicy.Surface.Home ||
            surface == TVReceiverPresentationPolicy.Surface.Detail
        ) {
            showLibrarySurface()
        } else {
            hideLibrarySurface()
        }
        if (TVReceiverPresentationPolicy.Surface.Ended !in mounted &&
            ::endedPlate.isInitialized && endedPlate.isShowing
        ) {
            endedPlate.hide()
        }
    }

    private fun showLibrarySurface() {
        // renderOverlay() is also driven by receiver/catalog state ticks. Once the Compose canvas
        // is visible those ticks must not re-read and re-parse the recents blob or enqueue another
        // catalog check; both operations compete with D-pad frames. Explicit refresh actions and
        // the lifecycle path below still update the data when it can actually have changed.
        if (librarySurfaceActive && librarySurfaceView.visibility == View.VISIBLE) return
        refreshLibrarySnapshot()
        if (libraryDestination == TVLibraryDestination.TamilMV ||
            libraryDestination == TVLibraryDestination.LetterboxdLists ||
            libraryDestination == TVLibraryDestination.NewFromFriends
        ) {
            refreshTamilMVCatalog(force = false)
        }
        if (libraryDestination == TVLibraryDestination.TMDBCatalogs) refreshTMDBCatalog(force = false)
        blockReceiverHomeFocus()
        librarySurfaceView.visibility = View.VISIBLE
        librarySurfaceView.requestFocus()
        librarySurfaceActive = true
        librarySurfaceView.bringToFront()
        if (settingsSurfaceVisible) settingsSurfaceView.bringToFront()
    }

    private fun refreshTamilMVCatalog(force: Boolean) {
        if (!::tamilMVCatalogRepository.isInitialized) return
        tamilJobStatus = TVJobStatus(TVJobPhase.Running, "Checking the catalog server…")
        activityScope.launch {
            tamilMVCatalogRepository.refresh(force)
            tamilJobStatus = tamilJobStatus(tamilMVCatalogRepository.state.value)
        }
    }

    private fun refreshTMDBCatalog(force: Boolean) {
        if (!::tmdbCatalogRepository.isInitialized) return
        tmdbJobStatus = TVJobStatus(TVJobPhase.Running, "Downloading signed TMDB shelves…")
        activityScope.launch {
            tmdbCatalogRepository.refresh(force)
            tmdbJobStatus = tmdbJobStatus(tmdbCatalogRepository.state.value)
        }
    }

    private fun refreshCollectionJob(name: String) {
        // Private Letterboxd/Friends shelves share the same signed manifest as Tamil MV. Running
        // the real loader here means Jobs has an observable lifecycle and never reports a fake
        // success when the token or server snapshot is unavailable.
        if (name.equals("Letterboxd", ignoreCase = true)) {
            letterboxdJobStatus = TVJobStatus(TVJobPhase.Running, "Checking the catalog server…")
            ReceiverDiagnostics.record("catalog.job.requested", name)
            activityScope.launch {
                tamilMVCatalogRepository.refresh(force = true)
                letterboxdJobStatus = tamilJobStatus(tamilMVCatalogRepository.state.value, source = "Letterboxd")
                tamilJobStatus = tamilJobStatus(tamilMVCatalogRepository.state.value)
            }
        } else {
            ReceiverDiagnostics.record("catalog.job.requested", name)
        }
    }

    private fun tamilJobStatus(
        state: TVTamilMVCatalogState,
        source: String = "Tamil MV",
    ): TVJobStatus = when (state) {
        TVTamilMVCatalogState.Empty -> TVJobStatus(TVJobPhase.Waiting, "The server snapshot is empty")
        TVTamilMVCatalogState.MissingCredential -> TVJobStatus(
            TVJobPhase.Blocked,
            "Catalog Server Token missing · Update from iPhone",
        )
        is TVTamilMVCatalogState.Loading -> TVJobStatus(TVJobPhase.Running, "Downloading signed $source shelves…")
        is TVTamilMVCatalogState.Ready -> TVJobStatus(
            TVJobPhase.Ready,
            state.notice ?: "${state.snapshot.tamilMVItemCount} Tamil MV entries · " +
                "${state.snapshot.letterboxdShelves.size} Letterboxd lists · " +
                "${state.snapshot.letterboxd.size} posters",
        )
        is TVTamilMVCatalogState.Error -> TVJobStatus(TVJobPhase.Failed, state.message)
    }

    private fun tmdbJobStatus(state: TVTMDBCatalogState): TVJobStatus = when (state) {
        TVTMDBCatalogState.Empty -> TVJobStatus(TVJobPhase.Waiting, "Run refresh from Jobs")
        is TVTMDBCatalogState.Loading -> TVJobStatus(TVJobPhase.Running, "Downloading signed TMDB shelves…")
        is TVTMDBCatalogState.Ready -> TVJobStatus(
            TVJobPhase.Ready,
            "${state.movies.size} movies · ${state.series.size} series available",
        )
        is TVTMDBCatalogState.Error -> TVJobStatus(TVJobPhase.Failed, state.message)
    }

    private fun requestTamilMVSources(item: TVTamilMVCatalogItem) {
        if (!::addonSourceRepository.isInitialized) return
        addonSourceJob?.cancel()
        addonPlaybackJob?.cancel()
        addonPlaybackJob = null
        val generation = ++addonSourceGeneration
        val itemKey = item.sourceKey
        openTamilMVItemID = itemKey
        addonSourceState = TVAddonSourceState.Loading(itemKey)
        addonSourceJob = activityScope.launch {
            try {
                val result = addonSourceRepository.find(item)
                if (TVAddonSourceLifecyclePolicy.accepts(
                        resultGeneration = generation,
                        currentGeneration = addonSourceGeneration,
                        resultItemID = itemKey,
                        openItemID = openTamilMVItemID,
                    )
                ) {
                    addonSourceState = TVAddonSourceState.Ready(itemKey, result)
                }
            } catch (_: CancellationException) {
                // A new poster, Back, lifecycle loss, or source selection owns the next state.
            } catch (_: Throwable) {
                if (TVAddonSourceLifecyclePolicy.accepts(generation, addonSourceGeneration, itemKey, openTamilMVItemID)) {
                    addonSourceState = TVAddonSourceState.Error(
                        itemKey,
                        "Sources could not be loaded. Check the TV network and retry.",
                    )
                }
            } finally {
                if (generation == addonSourceGeneration) addonSourceJob = null
            }
        }
    }

    private fun playTamilMVSource(item: TVTamilMVCatalogItem, source: TVAddonPlayableSource) {
        if (addonPlaybackJob?.isActive == true) return
        val ready = addonSourceState as? TVAddonSourceState.Ready ?: return
        val itemKey = item.sourceKey
        if (ready.itemID != itemKey || ready.result.sources.none { it.id == source.id && it.url == source.url }) return

        addonSourceJob?.cancel()
        addonSourceJob = null
        ++addonSourceGeneration
        receiverIdleRequested = false
        playbackPresentationOrigin = TVReceiverPresentationPolicy.PlaybackOrigin.Detail
        openTamilMVItemID = itemKey
        com.fourseveneightnine.tv.protocol.NowPlayingArtwork.stage(item.backdropURL, item.posterURL)
        addonPlaybackJob = activityScope.launch {
            val opened = controller.open(
                OpenMediaRequest(
                    url = source.url,
                    title = item.title,
                    subtitle = listOfNotNull(
                        item.season?.let { season -> item.episode?.let { episode -> "S$season · E$episode" } },
                        source.quality,
                        source.addonName,
                    ).joinToString(" · "),
                ),
            )
            addonPlaybackJob = null
            if (opened.isSuccess) {
                addonSourceState = TVAddonSourceState.Idle
                openTamilMVItemID = null
            } else {
                com.fourseveneightnine.tv.protocol.NowPlayingArtwork.clear()
                addonSourceState = TVAddonSourceState.Error(
                    itemKey,
                    "That direct source could not start. Choose another source or refresh the list.",
                )
            }
        }
    }

    private fun cancelTamilMVSourceFlow(stopPendingOpen: Boolean) {
        addonSourceJob?.cancel()
        addonSourceJob = null
        val opening = addonPlaybackJob
        addonPlaybackJob = null
        if (stopPendingOpen && opening?.isActive == true) {
            opening.cancel()
            activityScope.launch { controller.stop() }
        }
        ++addonSourceGeneration
        openTamilMVItemID = null
        addonSourceState = TVAddonSourceState.Idle
    }

    private fun cancelContinuePlaybackFlow() {
        continuePlaybackJob?.cancel()
        continuePlaybackJob = null
        clearPlaybackArtwork()
        continuePlaybackItem = null
        continuePlaybackFallbackAttempted = false
        continueAttemptedSourceURLs.clear()
        if (::playbackPosterView.isInitialized) playbackPosterView.visibility = View.GONE
        if (::playbackStatusView.isInitialized) playbackStatusView.visibility = View.GONE
    }

    private fun hideLibrarySurface() {
        if (!::librarySurfaceView.isInitialized) return
        librarySurfaceActive = false
        librarySurfaceView.clearFocus()
        librarySurfaceView.visibility = View.GONE
        if (!settingsSurfaceVisible) releaseReceiverHomeFocus()
    }

    private fun blockReceiverHomeFocus() {
        if (!::overlay.isInitialized) return
        overlay.clearFocus()
        overlay.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        overlay.isFocusable = false
    }

    private fun releaseReceiverHomeFocus() {
        if (!::overlay.isInitialized) return
        overlay.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        overlay.isFocusable = true
    }

    private fun importSettingsFile(uri: Uri) {
        activityScope.launch {
            val bytes = runCatching {
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use(TVSettingsFileReader::read)
                        ?: error("settings_file_unavailable")
                }
            }.getOrNull()
            settingsCoordinator.stageFile(bytes ?: ByteArray(0))
        }
    }

    /** The receiver-ready screen is a canvas, not the startup/error card with different copy. */
    private fun applyReadyIdleTreatment(active: Boolean) {
        if (!::receiverHomeContent.isInitialized) return
        receiverHomeContent.background = if (active) {
            null
        } else {
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(28).toFloat()
                setColor(tokenAlpha(R.color.tv_elevated, 200))
            }
        }
        val stageParams = receiverBrandStage.layoutParams as LinearLayout.LayoutParams
        val brandParams = receiverBrandView.layoutParams as FrameLayout.LayoutParams
        val desiredBrandHeight = dp(if (active) IDLE_BRAND_HEIGHT_DP else BRAND_HEIGHT_DP)
        val desiredStage = dp(if (active) IDLE_BRAND_STAGE_DP else BRAND_HEIGHT_DP)
        if (stageParams.width != desiredStage || stageParams.height != desiredStage) {
            stageParams.width = desiredStage
            stageParams.height = desiredStage
            receiverBrandStage.layoutParams = stageParams
        }
        if (brandParams.height != desiredBrandHeight) {
            brandParams.height = desiredBrandHeight
            receiverBrandView.layoutParams = brandParams
        }
        idleFactsView.visibility = if (active) View.VISIBLE else View.GONE
        idlePingRing.visibility = if (active) View.VISIBLE else View.GONE
        if (active) {
            val address = LocalNetworkAddress.currentIPv4()
            val artworkAvailable = com.fourseveneightnine.tv.protocol.NowPlayingArtwork.art.value?.best != null
            idleFactsView.text = listOf(
                "IP ${address ?: "—"}",
                "HTTP ${ReceiverPorts.HTTP}",
                "WS ${ReceiverPorts.WEB_SOCKET}",
                if (advertisingRequested) "ADVERTISING" else "NOT ADVERTISING",
                if (artworkAvailable) "ARTWORK AVAILABLE" else "ARTWORK —",
            ).joinToString("  ·  ")
            if (transitionsDisabled()) {
                idlePingAnimator?.cancel()
                idlePingAnimator = null
                idlePingRing.alpha = 0.45f
            } else if (idlePingAnimator?.isRunning != true) {
                idlePingAnimator?.cancel()
                idlePingAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 1_600L
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    repeatMode = android.animation.ValueAnimator.RESTART
                    addUpdateListener { animation ->
                        val fraction = animation.animatedFraction
                        idlePingRing.alpha = 0.45f * (1f - fraction)
                        idlePingRing.scaleX = 1f + fraction * 0.18f
                        idlePingRing.scaleY = 1f + fraction * 0.18f
                    }
                    start()
                }
            }
        } else {
            idlePingAnimator?.cancel()
            idlePingAnimator = null
            idlePingRing.alpha = 1f
            idlePingRing.scaleX = 1f
            idlePingRing.scaleY = 1f
        }
    }

    private fun renderRecentsShelf() {
        if (!::recentsLayoutView.isInitialized || !::recentsStore.isInitialized) return
        recentsTitleView.visibility = View.GONE
        recentsScrollerView.visibility = View.GONE
        return
    }

    private fun createRecentCardView(item: RecentItem): View {
        val frame = FrameLayout(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
        }

        val imageView = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        }
        frame.addView(
            imageView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        val posterUrl = item.posterUrl
        if (!posterUrl.isNullOrBlank()) {
            activityScope.launch {
                val bitmap = artworkLoader.load(posterUrl, dp(IDLE_RECENT_WIDTH_DP))
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                }
            }
        }

        val overlayView = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, tokenAlpha(R.color.tv_black, 220)),
            )
        }
        frame.addView(
            overlayView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(54),
                Gravity.BOTTOM,
            ),
        )

        val titleTv = textView(12f, R.color.tv_text_primary).apply {
            text = item.title
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        frame.addView(
            titleTv,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ).apply { bottomMargin = dp(8) },
        )

        if (item.durationMillis > 0L) {
            val progressView = View(this).apply {
                setBackgroundColor(getColor(R.color.tv_accent))
            }
            val fraction = (item.positionMillis.toFloat() / item.durationMillis.toFloat()).coerceIn(0f, 1f)
            val progressWidth = (dp(IDLE_RECENT_WIDTH_DP) * fraction).roundToInt().coerceAtLeast(dp(4))
            frame.addView(
                progressView,
                FrameLayout.LayoutParams(
                    progressWidth,
                    dp(4),
                    Gravity.BOTTOM or Gravity.START,
                ),
            )
        }

        val updateCardBg = { hasFocus: Boolean ->
            frame.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(tokenAlpha(R.color.tv_slate, 140))
                setStroke(
                    dp(if (hasFocus) 3 else 1),
                    getColor(if (hasFocus) R.color.tv_accent else R.color.tv_control_stroke),
                )
            }
        }
        updateCardBg(false)

        frame.setOnFocusChangeListener { _, hasFocus ->
            updateCardBg(hasFocus)
            frame.animate()
                .scaleX(if (hasFocus) 1.06f else 1.0f)
                .scaleY(if (hasFocus) 1.06f else 1.0f)
                .setDuration(160)
                .start()
            if (hasFocus && !item.posterUrl.isNullOrBlank()) {
                // DEBOUNCED, because this is a PANEL-WIDTH fetch and decode. Wired straight to focus
                // it fired once per D-pad press: holding the pad across the rail queued a
                // multi-megabyte load per card, all of which the viewer had already scrolled past.
                // Only the card focus SETTLES on is worth a backdrop.
                requestBackdropForFocusedCard(item.posterUrl)
            }
        }

        frame.setOnClickListener { openRecentItem(item) }

        return frame
    }

    private fun openRecentItem(item: RecentItem) {
        receiverIdleRequested = false
        playbackPresentationOrigin = TVReceiverPresentationPolicy.PlaybackOrigin.Library
        libraryDetailItem = null
        libraryDetailLabel = null
        continuePlaybackJob?.cancel()
        continuePlaybackItem = item
        continuePlaybackFallbackAttempted = false
        continueAttemptedSourceURLs.clear()
        hideLibrarySurface()
        com.fourseveneightnine.tv.protocol.NowPlayingArtwork.stage(item.backdropUrl, item.posterUrl)
        showPlaybackStatus(
            title = if (item.url.isBlank()) "Finding a playable source" else "Loading ${item.title}",
            detail = continueClock(item),
            posterURL = item.posterUrl,
            progressPermille = continueProgress(item),
        )
        continuePlaybackJob = activityScope.launch {
            val request = if (item.url.isBlank()) {
                val source = findContinueSource(item)
                if (source == null) {
                    continuePlaybackFallbackAttempted = true
                    showPlaybackStatus(
                        title = "No playable source",
                        detail = "Refresh addons on the paired iPhone, then try again.",
                        posterURL = item.posterUrl,
                    )
                    return@launch
                }
                continueAttemptedSourceURLs += source.url
                OpenMediaRequest(
                    url = source.url,
                    title = item.title,
                    subtitle = listOfNotNull(source.quality, source.addonName).joinToString(" · "),
                )
            } else {
                continueAttemptedSourceURLs += item.url
                OpenMediaRequest(url = item.url, title = item.title, subtitle = item.subtitle)
            }
            openContinueRequest(item, request)
        }
    }

    private suspend fun openContinueRequest(item: RecentItem, request: OpenMediaRequest) {
        val opened = controller.open(request)
        if (!opened.isSuccess) {
            if (!continuePlaybackFallbackAttempted) {
                continuePlaybackFallbackAttempted = true
                recoverContinuePlayback(item)
            } else {
                showPlaybackStatus("Stream unavailable", "Press Back and choose another title.", item.posterUrl)
            }
            return
        }
        val phase = kotlinx.coroutines.withTimeoutOrNull(CONTINUE_OPEN_WAIT_MILLIS) {
            controller.playbackPhase.first {
                it is ReceiverPlaybackPhase.Playing ||
                    it is ReceiverPlaybackPhase.Paused ||
                    it is ReceiverPlaybackPhase.Error
            }
        }
        if (phase is ReceiverPlaybackPhase.Playing || phase is ReceiverPlaybackPhase.Paused) {
            val duration = item.durationMillis
            val isNearEnd = duration > 0L && item.positionMillis.toDouble() / duration.toDouble() > 0.95
            if (item.positionMillis > 0L && !isNearEnd) {
                controller.seek(SeekCommand.AbsoluteSeconds(item.positionMillis / 1000.0))
            }
            item.audioTrackIndex?.let { controller.selectAudio(it) }
            item.subtitleTrackIndex?.let {
                controller.selectSubtitle(SubtitleSelection.Index(it))
            }
        }
    }

    private suspend fun recoverContinuePlayback(item: RecentItem) {
        val source = findContinueSource(item)
        if (source == null) {
            showPlaybackStatus(
                title = "Stream unavailable",
                detail = "No fresh addon source was returned. Press Back and refresh Jobs.",
                posterURL = item.posterUrl,
            )
            return
        }
        continueAttemptedSourceURLs += source.url
        showPlaybackStatus(
            title = "Loading another source",
            detail = "${item.title} · ${source.quality ?: source.addonName}",
            posterURL = item.posterUrl,
            progressPermille = continueProgress(item),
        )
        openContinueRequest(
            item,
            OpenMediaRequest(
                url = source.url,
                title = item.title,
                subtitle = listOfNotNull(source.quality, source.addonName).joinToString(" · "),
            ),
        )
    }

    private suspend fun findContinueSource(item: RecentItem): TVAddonPlayableSource? {
        if (!::addonSourceRepository.isInitialized) return null
        // Continue can be the first route opened after launch. The catalogue repository is
        // normally hydrated when the user enters Tamil MV, but a Continue tap must not depend on
        // that unrelated navigation history: load the cached snapshot (or the signed server
        // snapshot when no cache exists) before matching the phone's title to an addon identity.
        if (tamilMVCatalogRepository.state.value.visibleSnapshot() == null) {
            tamilMVCatalogRepository.refresh(force = false)
        }
        val catalogItem = ContinuePlaybackPolicy.catalogItem(item, continueCatalogCandidates())
        if (catalogItem == null) {
            ReceiverDiagnostics.record("continue.resolve.miss", "title=${item.title.take(120)}")
            return null
        }
        val result = addonSourceRepository.find(catalogItem)
        val source = result.sources.firstOrNull { it.url !in continueAttemptedSourceURLs }
        ReceiverDiagnostics.record(
            "continue.resolve",
            "title=${item.title.take(120)} catalog=${catalogItem.id.take(120)} sources=${result.sources.size} selected=${source != null}",
        )
        return source
    }

    private fun continueCatalogCandidates(): List<TVTamilMVCatalogItem> {
        val privateSnapshot = when (val state = tamilMVCatalogRepository.state.value) {
            is TVTamilMVCatalogState.Loading -> state.cached
            is TVTamilMVCatalogState.Ready -> state.snapshot
            is TVTamilMVCatalogState.Error -> state.cached
            TVTamilMVCatalogState.Empty, TVTamilMVCatalogState.MissingCredential -> null
        }
        val privateItems = privateSnapshot?.let {
            it.popular + it.recent + it.letterboxd + it.friends + it.letterboxdShelves.flatMap { shelf -> shelf.items }
        }.orEmpty()
        val tmdbSnapshot = when (val state = tmdbCatalogRepository.state.value) {
            is TVTMDBCatalogState.Loading -> state.cached
            is TVTMDBCatalogState.Ready -> TVTMDBCatalogSnapshot(
                generatedAtMillis = state.generatedAtMillis,
                cachedAtMillis = 0L,
                movies = state.movies,
                series = state.series,
            )
            is TVTMDBCatalogState.Error -> state.cached
            TVTMDBCatalogState.Empty -> null
        }
        return (privateItems + tmdbSnapshot?.movies.orEmpty() + tmdbSnapshot?.series.orEmpty())
            .distinctBy(TVTamilMVCatalogItem::id)
    }

    private fun continueClock(item: RecentItem): String = if (item.durationMillis > 0L) {
        "${PlayerControlsPolicy.formatTime(item.positionMillis)} / ${PlayerControlsPolicy.formatTime(item.durationMillis)}"
    } else {
        "Preparing playback on TV"
    }

    private fun continueProgress(item: RecentItem): Int? = item.durationMillis
        .takeIf { it > 0L }
        ?.let { PlayerControlsPolicy.progressPermille(item.positionMillis, it) }

    private var currentVideoRatio: Float = 0f
    private var currentResizeMode: VideoResizeMode = VideoResizeMode.Fit

    private fun applyVideoResizeMode(mode: VideoResizeMode) {
        if (!::videoFrame.isInitialized || !::surfaceView.isInitialized) return
        videoFrame.clipChildren = false
        val currentRatio = if (currentVideoRatio > 0f) currentVideoRatio else (16f / 9f)

        when (mode) {
            VideoResizeMode.Fit -> {
                videoFrame.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)
                videoFrame.setAspectRatio(currentRatio)
                surfaceView.scaleX = 1.0f
                surfaceView.scaleY = 1.0f
            }
            VideoResizeMode.Crop -> {
                // Hardware surface zoom transform: expands video height to crop top & bottom letterbox bars
                val zoom = 1.34f
                videoFrame.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)
                videoFrame.setAspectRatio(currentRatio)
                surfaceView.scaleX = zoom
                surfaceView.scaleY = zoom
            }
            VideoResizeMode.Stretch -> {
                videoFrame.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL)
                videoFrame.setAspectRatio(0f)
                surfaceView.scaleX = 1.0f
                surfaceView.scaleY = 1.0f
            }
        }
        videoFrame.requestLayout()
        videoFrame.invalidate()
    }

    private val playerControlsListener = object : PlayerControlsView.Listener {
        override fun onPlayPauseRequested() = requestTogglePlayPause()

        override fun onSeekRequested(positionMillis: Long) = requestSeekTo(positionMillis)

        override fun onAudioTracksRequested() = showTrackPicker(audio = true)

        override fun onSubtitleTracksRequested() = showTrackPicker(audio = false)

        override fun onResizeModeChanged(mode: VideoResizeMode) {
            currentResizeMode = mode
            applyVideoResizeMode(mode)
            playerControlsView.setResizeMode(mode)
            ReceiverDiagnostics.record("controls.resize", mode.name)
        }

        override fun onSpeedChanged(speed: Float) {
            playerControlsView.setSpeed(speed)
            activityScope.launch { controller.setSpeedMultiplier(speed) }
        }

        override fun onUpscaleModeChanged(mode: UpscaleMode) {
            // The control already rendered the new state; the controller persists it and (on OFF)
            // tears the effects pipeline down immediately.
            playerControlsView.setUpscaleMode(mode)
            controller.setUpscaleMode(mode)
            ReceiverDiagnostics.record("controls.upscale", mode.name)
        }

        override fun onMoreRequested() = showMoreRail()

        override fun onHandoffRequested() = showHandoffPicker()

        override fun onInteraction() = restartPlayerControlsHideTimer()
    }

    /**
     * The secondary options: speed, picture size, upscaling and hand-off.
     *
     * These were four more glyphs on the bar. They are set-once-and-forget choices, so they cost
     * the bar four D-pad targets to serve a rare press; here each one is a named row with its
     * current value ticked, which is also the first time picture size and upscaling have been
     * readable at all rather than blind three-state cycles.
     */
    private fun showMoreRail() {
        fun <T> stepped(values: List<T>, current: T, direction: Int): T =
            values[requireNotNull(TVReceiverPresentationPolicy.steppedIndex(values.size, values.indexOf(current), direction))]
        val players = externalPlayers()
        var selectedPlayerIndex = 0
        val rows = listOf(
            OptionRailView.Row("Speed", valueProvider = { PlayerControlsPolicy.formatSpeed(playerControlsView.speed()) }, onStep = { direction ->
                playerControlsListener.onSpeedChanged(stepped(PlayerControlsPolicy.SPEEDS.toList(), playerControlsView.speed(), direction))
            }),
            OptionRailView.Row("Picture size", valueProvider = { getString(playerControlsView.resizeMode().labelRes()) }, onStep = { direction ->
                playerControlsListener.onResizeModeChanged(stepped(VideoResizeMode.entries.toList(), playerControlsView.resizeMode(), direction))
            }),
            OptionRailView.Row("Upscaling", valueProvider = { getString(upscaleLabelRes(playerControlsView.upscaleMode())) }, onStep = { direction ->
                playerControlsListener.onUpscaleModeChanged(stepped(UpscaleMode.entries.toList(), playerControlsView.upscaleMode(), direction))
            }),
            OptionRailView.Row("Auto frame rate", valueProvider = { if (autoFrameRateEnabled) "ON" else "OFF" }, onStep = {
                autoFrameRateEnabled = !autoFrameRateEnabled
                presentationPreferences.edit().putBoolean("auto_frame_rate_enabled", autoFrameRateEnabled).apply()
                if (!autoFrameRateEnabled) {
                    clearContentFrameRate()
                } else if (currentDiagnostics.active && frameRateEligible) {
                    applyContentFrameRate(currentDiagnostics)
                }
                refreshAfrPresentation()
            }),
            OptionRailView.Row(
                "Hand off",
                onPick = if (players.isEmpty()) {
                    null
                } else {
                    {
                        players.getOrNull(selectedPlayerIndex)?.let { player ->
                            controller.lastOpenMedia()?.let { handOff(it, player) }
                        }
                    }
                },
                valueProvider = { players.getOrNull(selectedPlayerIndex)?.label ?: "Unavailable" },
                onStep = { direction -> TVReceiverPresentationPolicy.steppedIndex(players.size, selectedPlayerIndex, direction)?.let { selectedPlayerIndex = it } },
            ),
        )
        showOptionRail(getString(R.string.controls_more_title), listOf(OptionRailView.Section(rows = rows)))
    }

    private fun upscaleLabelRes(mode: UpscaleMode): Int = when (mode) {
        UpscaleMode.OFF -> R.string.controls_enhance_off
        UpscaleMode.AUTO -> R.string.controls_enhance_auto
        UpscaleMode.FORCE_1080P -> R.string.controls_enhance_1080p
    }

    /**
     * Raises the rail and holds the bar up behind it. The auto-hide timer is stopped for as long
     * as the rail is open — a viewer reading a list of twelve subtitle tracks has not gone away —
     * and focus is handed back to the bar when it closes.
     */
    private fun showOptionRail(title: String, sections: List<OptionRailView.Section>) {
        playerControlsHideJob?.cancel()
        playerControlsHideJob = null
        optionRail.show(title, sections) {
            if (playerControlsVisible) {
                playerControlsView.takeFocus()
                restartPlayerControlsHideTimer()
            } else {
                restoreOverlayFocusAfterDismiss()
            }
        }
    }

    /**
     * Ask which installed player should take over, then hand the stream across.
     *
     * The same seam the playback-error overlay uses — position and headers travel, so the other
     * app resumes instead of restarting. The difference is that this one is reachable while
     * playback is working, which is when a viewer actually decides they want a different player.
     * With exactly one target installed there is nothing to choose, so it launches straight away.
     */
    private fun showHandoffPicker() {
        val media = controller.lastOpenMedia() ?: return
        val players = externalPlayers()
        if (players.isEmpty()) {
            showPlayerControlsMessage(getString(R.string.controls_handoff_none))
            return
        }
        if (players.size == 1) {
            handOff(media, players.first())
            return
        }
        showOptionRail(
            getString(R.string.controls_handoff_title),
            listOf(
                OptionRailView.Section(
                    rows = players.map { player ->
                        OptionRailView.Row(label = player.label, onPick = { handOff(media, player) })
                    },
                ),
            ),
        )
    }

    private fun handOff(media: OpenMediaRequest, player: InstalledExternalPlayer) {
        activityScope.launch {
            // Read the playhead BEFORE anything stops: after a stop there is no position left to
            // carry, and a handoff that restarts a two-hour film at zero is worse than no handoff.
            val position = runCatching { controller.snapshot().positionSeconds }.getOrDefault(0.0)
            val handoff = ExternalPlayerIntentPolicy.HandoffContext(
                positionMillis = (position * 1_000).toLong().coerceAtLeast(0),
                headers = media.headers,
            )
            if (launchExternalPlayer(media.url, media.title, player.packageName, handoff)) {
                // Tell the phone where the video went, then stop — the same order the RPC path
                // uses, so a phone that understands the announcement keeps its cast session.
                runCatching {
                    controller.announceExternalHandoff(player.label, player.packageName)
                }
                controller.stop()
            } else {
                showPlayerControlsMessage(getString(R.string.external_player_failed, player.label))
            }
        }
    }

    /** Raises the on-TV player UI and keeps it fed until it times out. */
    private fun showPlayerControls() {
        if (!playbackActive || !::playerControlsView.isInitialized) return
        seekPreviewHideJob?.cancel()
        seekPreviewHideJob = null
        playerControlsView.showFullPanel()
        if (!playerControlsVisible) {
            playerControlsVisible = true
            fadeControlsIn()
            playerControlsView.takeFocus()
        }
        // The phone's sidechannel may have changed the receiver-level choice while the bar was
        // down; the control must mirror what the receiver will actually do.
        playerControlsView.setUpscaleMode(controller.upscaleMode())
        playerControlsView.setResizeMode(currentResizeMode)
        refreshAfrPresentation()
        refreshPlayerControls()
        refreshTrackSummary()
        restartPlayerControlsHideTimer()
        startPlayerControlsPolling()
    }

    private fun hidePlayerControls() {
        playerControlsHideJob?.cancel()
        playerControlsHideJob = null
        playerControlsPollJob?.cancel()
        playerControlsPollJob = null
        seekPreviewHideJob?.cancel()
        seekPreviewHideJob = null
        playerControlsVisible = false
        if (::optionRail.isInitialized) optionRail.hide()
        if (::playerControlsView.isInitialized) {
            playerControlsView.cancelScrub()
            fadeControlsOut()
        }
        // Focus must go somewhere real, or the next D-pad press lands nowhere.
        restoreOverlayFocusAfterDismiss()
    }

    /**
     * The bar arrives and leaves rather than blinking. A `GONE` on a large panel reads as a glitch,
     * and the whole point of auto-hiding chrome is that its coming and going is not an event.
     *
     * A viewer who has turned animations off in Accessibility gets the old instant behaviour: the
     * system transition scale is authoritative, never our own taste.
     */
    private fun fadeControlsIn() {
        val view = playerControlsView
        view.animate().cancel()
        if (transitionsDisabled()) {
            view.alpha = 1f
            view.translationY = 0f
            view.visibility = View.VISIBLE
            return
        }
        if (view.visibility != View.VISIBLE) {
            view.alpha = 0f
            view.translationY = dp(CONTROLS_RISE_DP).toFloat()
            view.visibility = View.VISIBLE
        }
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(CONTROLS_FADE_IN_MILLIS)
            .setInterpolator(android.view.animation.PathInterpolator(0.23f, 1f, 0.32f, 1f))
            .start()
    }

    private fun fadeControlsOut() {
        val view = playerControlsView
        view.animate().cancel()
        if (transitionsDisabled() || view.visibility != View.VISIBLE) {
            view.alpha = 1f
            view.translationY = 0f
            view.visibility = View.GONE
            return
        }
        view.animate()
            .alpha(0f)
            .translationY(dp(CONTROLS_EXIT_DP).toFloat())
            .setDuration(CONTROLS_FADE_OUT_MILLIS)
            .setInterpolator(android.view.animation.PathInterpolator(0.23f, 1f, 0.32f, 1f))
            .withEndAction {
                // A show that raced the fade must win: only hide if the bar is still meant to be down.
                if (!playerControlsVisible) {
                    view.visibility = View.GONE
                    view.alpha = 1f
                    view.translationY = 0f
                }
            }
            .start()
    }

    private fun transitionsDisabled(): Boolean =
        android.provider.Settings.Global.getFloat(
            contentResolver,
            android.provider.Settings.Global.TRANSITION_ANIMATION_SCALE,
            1f,
        ) == 0f

    /**
     * Restarts the auto-hide countdown — except while the film is paused.
     *
     * A paused film is not an idle screen. The viewer stopped it on purpose and will come back to
     * it, and a television that has forgotten the title, the position and the controls by then is
     * telling them nothing at all. The bar comes down again on its own when playback resumes.
     */
    private fun restartPlayerControlsHideTimer() {
        playerControlsHideJob?.cancel()
        playerControlsHideJob = null
        if (playbackPhase is ReceiverPlaybackPhase.Paused) return
        playerControlsHideJob = activityScope.launch {
            delay(TvTokens.Motion.ControlsAutoHideMillis)
            hidePlayerControls()
        }
    }

    private fun startPlayerControlsPolling() {
        if (playerControlsPollJob?.isActive == true) return
        playerControlsPollJob = activityScope.launch {
            while (playerControlsVisible) {
                refreshPlayerControls()
                delay(PlayerControlsPolicy.POSITION_POLL_MILLIS)
            }
        }
    }

    private fun refreshPlayerControls() {
        activityScope.launch {
            val snapshot = runCatching { controller.snapshot() }.getOrNull() ?: return@launch
            bindPlaybackSnapshot(snapshot)
        }
    }

    /** One place where a snapshot becomes what the bar shows, whether it came from a poll or an event. */
    private fun bindPlaybackSnapshot(snapshot: ReceiverSnapshot) {
        if (!::playerControlsView.isInitialized) return
        val media = controller.lastOpenMedia()
        playerControlsView.bind(
            mediaIdentity = media?.url,
            title = media?.title,
            subtitle = media?.subtitle,
            positionMillis = (snapshot.positionSeconds * 1_000).toLong(),
            durationMillis = (snapshot.durationSeconds * 1_000).toLong(),
            bufferedMillis = (snapshot.bufferedSeconds * 1_000).toLong(),
            isPlaying = snapshot.speed != 0,
        )
        // Prefer named marks when supplied; otherwise render honest, evenly-spaced numbered
        // chapters derived from the actual player runtime.
        val reportedChapters = PlayerControlsPolicy.chapterFractions(
            media?.chapterSeconds.orEmpty(),
            snapshot.durationSeconds,
        )
        playerControlsView.setChapters(
            reportedChapters.ifEmpty {
                TVReceiverPresentationPolicy.chapterFractionsForDuration(snapshot.durationSeconds)
            },
        )
    }

    /**
     * Dresses the waiting screen with the title's own artwork. "Opening Romeo…" over the film's
     * backdrop is the difference between a cast that looks like it is working and one that looks
     * like a hung terminal — and the wait is real: several seconds of connect, probe and codec init.
     */
    /**
     * Show a focused card's poster as the backdrop, once focus has stopped moving.
     *
     * The wait is the feature. A backdrop is decoded at panel width, so tying it directly to a focus
     * change meant every press down the rail started a full-size fetch — thirteen of them for one
     * held D-pad, twelve of which were already stale before they finished. `BACKDROP_SETTLE_MILLIS`
     * is below the threshold where a viewer reads it as lag, and above a comfortable repeat rate.
     */
    private fun requestBackdropForFocusedCard(posterUrl: String) {
        backdropSettleJob?.cancel()
        backdropSettleJob = activityScope.launch {
            delay(BACKDROP_SETTLE_MILLIS)
            applyBackdrop(com.fourseveneightnine.tv.protocol.NowPlayingArt(posterURL = posterUrl))
        }
    }

    private fun applyBackdrop(art: com.fourseveneightnine.tv.protocol.NowPlayingArt?) {
        if (!::backdropView.isInitialized) return
        val url = art?.best
        if (url == null) {
            artworkURLOnScreen = null
            backdropRequestedURL = null
            backdropView.setImageDrawable(null)
            backdropView.alpha = 0f
            // NOT `artworkLoader.clear()`: the loader is now shared with the recents rail, and
            // dropping the backdrop must not throw away thirteen poster thumbnails that are still
            // on screen — that is what made every rail render re-fetch everything.
            return
        }
        if (url == artworkURLOnScreen) return
        backdropRequestedURL = url
        activityScope.launch {
            val bitmap = artworkLoader.load(url, resources.displayMetrics.widthPixels)
            // Staleness is measured against THE LAST BACKDROP REQUESTED, not against the staged cast
            // artwork. The old guard compared to `NowPlayingArtwork.art.value`, which only ever holds
            // the CAST title's art — so a poster from a focused rail card could never match it and
            // the backdrop silently refused to change. Comparing to our own request makes the guard
            // mean what it says: drop a load that a newer one has superseded.
            if (bitmap == null || backdropRequestedURL != url) return@launch
            artworkURLOnScreen = url
            ReceiverDiagnostics.record("artwork.ready", "${bitmap.width}x${bitmap.height}")
            backdropView.setImageBitmap(bitmap)
            // A poster is portrait: cropping it to a 16:9 panel would show a band of someone's
            // chin, so it is fitted and the empty sides stay black.
            backdropView.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            renderBackdropVisibility()
        }
    }

    /** Keep every video-fit gap pure black, including while the compact controls are visible. */
    private fun renderLetterboxBacking() {
        if (!::letterboxBackingView.isInitialized || !::overlay.isInitialized) return
        letterboxBackingView.visibility =
            if (ReceiverPlaybackPhasePolicy.showsPicture(playbackPhase) || overlay.visibility != View.VISIBLE) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }

    /** Artwork belongs to the waiting/resume states; it fills the background behind the overlay card. */
    private fun renderBackdropVisibility() {
        if (!::backdropView.isInitialized) return
        val wanted = artworkURLOnScreen != null &&
            overlay.visibility == View.VISIBLE &&
            startupState !is StartupState.Error
        val target = if (wanted) 0.88f else 0f
        if (backdropView.alpha == target) return
        backdropView.animate().alpha(target).setDuration(BACKDROP_FADE_MILLIS).start()
    }

    /**
     * Puts the CURRENT audio and subtitle choice on the pills themselves. Without this the row
     * reads "Audio / Subtitles" whatever is playing, and the only way to learn which track is on
     * is to open a dialog and look for the tick.
     */
    private fun refreshTrackSummary() {
        activityScope.launch {
            val tracks = controller.tracks().getOrNull() ?: return@launch
            val audio = tracks.currentAudio?.let {
                PlayerControlsPolicy.trackLabel(
                    language = it.language,
                    name = it.name,
                    codec = it.codec,
                    channels = it.channels,
                    fallback = "",
                )
            }
            val subtitle = tracks.currentSubtitle?.let {
                PlayerControlsPolicy.trackLabel(
                    language = it.language,
                    name = it.name,
                    codec = null,
                    channels = null,
                    fallback = "",
                )
            } ?: getString(R.string.controls_subtitles_off)
            playerControlsView.bindTrackSummary(audio, subtitle)
        }
    }

    /** Track choice on the television itself, D-pad navigable, with the current track pre-ticked. */
    private fun showTrackPicker(audio: Boolean) {
        activityScope.launch {
            val tracks = controller.tracks().getOrNull()
            val entries = if (audio) tracks?.audio.orEmpty() else tracks?.subtitles.orEmpty()
            // Subtitles the phone found in the viewer's addons but did not load. They are offered
            // here, below the file's own tracks, and only fetched if one is picked.
            val found = if (audio) {
                emptyList()
            } else {
                com.fourseveneightnine.tv.protocol.SubtitleOptions.options.value
            }
            if (entries.isEmpty() && found.isEmpty()) {
                showPlayerControlsMessage(getString(R.string.controls_no_tracks))
                return@launch
            }

            val nothingSelected = entries.none { it.selected }
            val embedded = entries.mapIndexed { position, track ->
                OptionRailView.Row(
                    label = PlayerControlsPolicy.trackLabel(
                        language = track.language,
                        name = track.name,
                        codec = track.codec,
                        channels = track.channels,
                        fallback = getString(R.string.controls_track_unnamed, position + 1),
                    ),
                    selected = track.selected,
                    onPick = {
                        applyTrackChoice {
                            if (audio) {
                                controller.selectAudio(track.index)
                                recentsRowUrl?.let { url ->
                                    if (::recentsStore.isInitialized) recentsStore.updateTrackSelection(url, track.index, null)
                                }
                            } else {
                                controller.selectSubtitle(
                                    SubtitleSelection.Index(track.index, enable = true),
                                )
                                recentsRowUrl?.let { url ->
                                    if (::recentsStore.isInitialized) recentsStore.updateTrackSelection(url, null, track.index)
                                }
                            }
                        }
                    },
                )
            }

            val sections = mutableListOf<OptionRailView.Section>()
            // Subtitles get an explicit Off row at the top; audio cannot be switched off.
            if (!audio) {
                sections += OptionRailView.Section(
                    rows = listOf(
                        OptionRailView.Row(
                            label = getString(R.string.controls_subtitles_off),
                            selected = nothingSelected,
                            onPick = {
                                applyTrackChoice { controller.selectSubtitle(SubtitleSelection.Off) }
                            },
                        ),
                    ),
                )
            }
            if (embedded.isNotEmpty()) sections += OptionRailView.Section(rows = embedded)
            // Anything the phone found online sits under its own heading, so it is never mistaken
            // for a track the file already carries.
            if (found.isNotEmpty()) {
                sections += OptionRailView.Section(
                    heading = getString(R.string.controls_subtitles_found),
                    rows = found.map { option ->
                        OptionRailView.Row(
                            label = option.label,
                            onPick = {
                                applyTrackChoice {
                                    ReceiverDiagnostics.record("subtitle.pickOnline", option.label)
                                    if (!controller.addSubtitle(option.url).isSuccess) {
                                        showPlayerControlsMessage(
                                            getString(R.string.controls_subtitles_failed),
                                        )
                                    }
                                }
                            },
                        )
                    },
                )
            }

            showOptionRail(
                getString(
                    if (audio) R.string.controls_audio_title else R.string.controls_subtitles_title,
                ),
                sections,
            )
        }
    }

    /**
     * Applies a track choice and then re-reads it. The selector lands on the player thread, so what
     * the viewer picked and what the player ended up on are not the same thing until it settles.
     */
    private fun applyTrackChoice(choose: suspend () -> Unit) {
        activityScope.launch {
            choose()
            delay(TRACK_SUMMARY_SETTLE_MILLIS)
            refreshTrackSummary()
        }
    }

    /** A one-line note on the controls when there is nothing to choose from. */
    private fun showPlayerControlsMessage(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun createMediaSession(): MediaSession =
        MediaSession(this, MEDIA_SESSION_TAG).apply {
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS,
            )
            setCallback(
                object : MediaSession.Callback() {
                    override fun onPlay() = requestPlay()
                    override fun onPause() = requestPause()
                    override fun onStop() = requestStop()
                    override fun onRewind() = requestSeek(-REMOTE_SEEK_SECONDS)
                    override fun onFastForward() = requestSeek(REMOTE_SEEK_SECONDS)
                    override fun onSeekTo(pos: Long) = requestSeekTo(pos)
                },
            )
            setPlaybackState(
                PlaybackState.Builder()
                    .setActions(MEDIA_SESSION_ACTIONS)
                    .setState(PlaybackState.STATE_NONE, 0L, 0f)
                    .build(),
            )
        }

    private fun updateMediaSession(event: ReceiverEvent) {
        val snapshot = event.snapshot()
        val state = when {
            !snapshot.active -> PlaybackState.STATE_STOPPED
            snapshot.speed == 0 -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_PLAYING
        }
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(MEDIA_SESSION_ACTIONS)
                .setState(
                    state,
                    (snapshot.positionSeconds.coerceAtLeast(0.0) * 1_000.0).toLong(),
                    if (state == PlaybackState.STATE_PLAYING) snapshot.speed.toFloat() else 0f,
                )
                .build(),
        )
    }

    private fun renderDiagnostics() {
        if (!::diagnosticsView.isInitialized) return
        diagnosticsView.visibility = if (showDiagnostics && currentDiagnostics.active) View.VISIBLE else View.GONE
        if (diagnosticsView.visibility != View.VISIBLE) return

        val value = currentDiagnostics
        val hdr = when (value.hdrTransfer.lowercase()) {
            "pq" -> "HDR (PQ)"
            "hlg" -> "HDR (HLG)"
            else -> value.hdrTransfer.ifBlank { "SDR/unknown" }
        }
        val decoder = value.hardwareDecoder.ifBlank { "software/unknown" }
        val output = listOf(value.outputPrimaries, value.outputTransfer)
            .filter(String::isNotBlank)
            .joinToString("/")
            .ifBlank { "unknown" }
        val buffering = if (value.pausedForCache) " · BUFFERING" else ""
        diagnosticsView.text = buildString {
            append("4789 PLAYBACK STATS\n")
            append("${value.width}×${value.height} · ${"%.3f".format(value.framesPerSecond)} fps · ${value.videoCodec.ifBlank { "codec?" }}\n")
            append("Decoder: $decoder · Source: $hdr\n")
            if (value.dolbyVisionProfile > 0) {
                append("Dolby Vision: profile ${value.dolbyVisionProfile} · ${value.colorMode}\n")
            }
            append("Output signal: $output\n")
            append("Audio: ${value.audioCodec.ifBlank { "unknown" }} ${value.audioChannels}ch\n")
            append("Cache: ${"%.1f".format(value.cacheSeconds)}s / ${value.bufferTargetSeconds}s$buffering\n")
            append("Dropped: ${value.droppedFrames} render / ${value.decoderDroppedFrames} decode · A/V ${"%+.3f".format(value.avSyncSeconds)}s\n")
            append("TV: ${mediaCapabilities.summary()}\n")
            append("Press INFO or MENU to hide")
        }
    }

    private fun refreshMediaCapabilities() {
        val activeDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay
        }
        mediaCapabilities = AndroidMediaCapabilities.probe(this, activeDisplay)
        controller.refreshAudioRoute()
        renderDiagnostics()
    }

    /**
     * Populate the phone-facing decoder profile without holding up window creation. MediaCodecList
     * and direct-playback support are vendor IPC calls; the onn 4K box has repeatedly shown them on
     * the Activity's critical path during cold start. An empty list is the protocol's explicit
     * "unknown" value, so the receiver remains useful immediately and later advertises the precise
     * capabilities once the probe completes.
     */
    private fun refreshReceiverCapabilities() {
        capabilityProbeJob?.cancel()
        capabilityProbeJob = activityScope.launch(Dispatchers.Default) {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            val hardwareVideoCodecs = VideoCodecProbe.hardwareDecoded()
            val audioDecodeCodecs = AudioCodecProbe.decoded()
            val audioPassthroughCodecs = AndroidDirectAudioProbe.supportedMpvCodecs(applicationContext)
                .map { if (it == "dts-hd") "dtshd" else it }
            receiverDispatcher.updateReceiverCapabilities(
                hardwareVideoCodecs = hardwareVideoCodecs,
                audioDecodeCodecs = audioDecodeCodecs,
                audioPassthroughCodecs = audioPassthroughCodecs,
            )
            ReceiverDiagnostics.record(
                "receiver.capabilities.ready",
                "video=${hardwareVideoCodecs.size} audio=${audioDecodeCodecs.size} " +
                    "passthrough=${audioPassthroughCodecs.size} " +
                    "elapsedMs=${android.os.SystemClock.elapsedRealtime() - startedAt}",
            )
        }
    }

    /** Null until the picture is up; afterwards, how long it has been up. */
    private fun millisSincePictureUp(): Long? = pictureUpAtMillis
        .takeIf { it > 0L }
        ?.let { android.os.SystemClock.elapsedRealtime() - it }

    /** The More summary reports a receiver-applied rate, never merely the source diagnostics. */
    private fun refreshAfrPresentation() {
        if (::playerControlsView.isInitialized) {
            playerControlsView.setAfrPresentation(
                TVReceiverPresentationPolicy.afrPresentation(autoFrameRateEnabled, appliedFrameRate),
            )
        }
    }

    private fun applyContentFrameRate(diagnostics: PlaybackDiagnostics) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            applyContentFrameRateLegacy(diagnostics)
            return
        }
        if (!surfaceView.holder.surface.isValid) return
        val rate = SurfaceFrameRatePolicy.validRate(diagnostics.framesPerSecond) ?: return
        if (kotlin.math.abs(appliedFrameRate - rate) < FRAME_RATE_EPSILON) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val strategy = if (
                    SurfaceFrameRatePolicy.allowNonSeamlessSwitch(diagnostics.durationSeconds) &&
                    SurfaceFrameRatePolicy.withinNonSeamlessWindow(millisSincePictureUp())
                ) {
                    val displayManager = getSystemService(DisplayManager::class.java)
                    if (displayManager?.matchContentFrameRateUserPreference ==
                        DisplayManager.MATCH_CONTENT_FRAMERATE_ALWAYS
                    ) {
                        Surface.CHANGE_FRAME_RATE_ALWAYS
                    } else {
                        Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
                    }
                } else {
                    Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
                }
                surfaceView.holder.surface.setFrameRate(
                    rate,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    strategy,
                )
            } else {
                surfaceView.holder.surface.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            }
            appliedFrameRate = rate
        }
    }

    /**
     * Auto frame rate matching for televisions below API 30.
     *
     * `Surface.setFrameRate` needs API 30. Fire OS 7 reports API 28 and the Hisense box reports 29,
     * so on the hardware this receiver is actually verified against that call can never run — auto
     * frame rate was not merely degraded there, it was absent. `preferredDisplayModeId` has existed
     * since API 23 and is the only refresh-rate control these boxes have.
     *
     * A mode change re-links HDMI, so it is gated on [SurfaceFrameRatePolicy.allowNonSeamlessSwitch]
     * — a ten-minute floor. Nobody wants a black flash before a two-minute trailer.
     */
    @Suppress("DEPRECATION")
    private fun applyContentFrameRateLegacy(diagnostics: PlaybackDiagnostics) {
        val rate = SurfaceFrameRatePolicy.validRate(diagnostics.framesPerSecond) ?: return
        if (!SurfaceFrameRatePolicy.allowNonSeamlessSwitch(diagnostics.durationSeconds)) return
        // The legacy path has no seamless option at all — a mode change is always an HDMI re-link.
        // Once the picture is up, leaving the panel alone beats blacking it out mid-scene.
        if (!SurfaceFrameRatePolicy.withinNonSeamlessWindow(millisSincePictureUp())) {
            ReceiverDiagnostics.record("afr.legacy.tooLate", "fps=$rate")
            return
        }
        if (kotlin.math.abs(appliedFrameRate - rate) < FRAME_RATE_EPSILON) return

        val activeDisplay = windowManager.defaultDisplay ?: return
        val current = activeDisplay.mode ?: return
        val target = SurfaceFrameRatePolicy.selectModeId(
            modes = activeDisplay.supportedModes.map {
                DisplayModeInfo(
                    modeId = it.modeId,
                    width = it.physicalWidth,
                    height = it.physicalHeight,
                    refreshRate = it.refreshRate,
                )
            },
            currentModeId = current.modeId,
            currentWidth = current.physicalWidth,
            currentHeight = current.physicalHeight,
            framesPerSecond = diagnostics.framesPerSecond,
        )
        if (target == null) {
            // Not a failure: either the panel already sits on the best mode, or it has nothing
            // closer than the one it is on. Record it so a field report can tell the two apart.
            ReceiverDiagnostics.record(
                "afr.legacy.skip",
                "fps=$rate current=${current.refreshRate} modes=" +
                    activeDisplay.supportedModes.joinToString("/") { "${it.refreshRate}" },
            )
            appliedFrameRate = rate
            return
        }
        runCatching {
            if (originalDisplayModeId == 0) originalDisplayModeId = current.modeId
            window.attributes = window.attributes.apply { preferredDisplayModeId = target }
            appliedFrameRate = rate
            ReceiverDiagnostics.record(
                "afr.legacy.applied",
                "fps=$rate from=${current.refreshRate} modeId=$target",
            )
        }
    }

    private fun clearContentFrameRate() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            clearContentFrameRateLegacy()
            return
        }
        if (appliedFrameRate == 0f) return
        runCatching {
            if (surfaceView.holder.surface.isValid) {
                surfaceView.holder.surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            }
        }
        appliedFrameRate = 0f
    }

    /**
     * Hand the panel back to the mode it was on before playback.
     *
     * Leaving a 24 Hz mode applied after a film ends makes the television's own interface stutter,
     * and the viewer has no idea our app caused it.
     */
    private fun clearContentFrameRateLegacy() {
        if (appliedFrameRate == 0f) return
        runCatching {
            window.attributes = window.attributes.apply { preferredDisplayModeId = originalDisplayModeId }
            ReceiverDiagnostics.record("afr.legacy.cleared", "modeId=$originalDisplayModeId")
        }
        appliedFrameRate = 0f
        originalDisplayModeId = 0
    }

    /** Applies the phone's subtitle appearance to the receiver's own cue renderer. */
    private fun applySubtitleStyle(style: ReceiverSubtitleStyle) {
        if (!::subtitleView.isInitialized) return
        // Embedded ASS styling would override the user's choices, so draw cues with ours.
        subtitleView.setApplyEmbeddedStyles(false)
        subtitleView.setStyle(
            CaptionStyleCompat(
                style.foregroundColor(),
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                getColor(R.color.tv_black),
                MpvSubtitleFontPolicy.resolvedTypeface(this, style.family),
            ),
        )
        subtitleView.setFractionalTextSize(style.fractionalTextSize)
        subtitleView.setBottomPaddingFraction(style.bottomPaddingFraction)
    }

    /** Launches the stream in an external player; targeted first, then the system chooser. */
    private fun launchExternalPlayer(
        url: String,
        title: String?,
        targetPackage: String?,
        handoff: ExternalPlayerIntentPolicy.HandoffContext =
            ExternalPlayerIntentPolicy.HandoffContext(),
    ): Boolean {
        ReceiverDiagnostics.record(
            "external.handoff",
            "player=${targetPackage ?: "chooser"} posMs=${handoff.positionMillis} " +
                "subs=${handoff.subtitleURL != null}",
        )
        val targeted = targetPackage
            ?.let { ExternalPlayerIntentPolicy.launch(this, url, title, it, handoff) }
            ?: false
        return targeted || ExternalPlayerIntentPolicy.launch(this, url, title, null, handoff)
    }

    enum class PlayerAction { OPEN, INSTALL }
    private var selectedPlayerAction = PlayerAction.OPEN
    private var selectedPlayerTarget: ExternalPlayerIntentPolicy.PlayerTargetInfo? = null

    /** Two dropdown selectors (Action + Player) defaulting to "Open Next Player" */
    private fun renderExternalPlayerOptions(visible: Boolean) {
        if (!::externalButtonsView.isInitialized) return
        if (!visible) {
            externalButtonsView.removeAllViews()
            externalButtonsView.visibility = View.GONE
            externalButtonsScrollerView.visibility = View.GONE
            externalButtonsScrollerView.isFocusable = false
            externalButtonsScrollerView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            renderedExternalOptionsVisible = false
            renderedExternalOptionsKey = null
            return
        }

        // PackageManager queries are Binder calls and can be surprisingly slow on Fire OS. Do the
        // discovery once off the UI thread; the buttons are only a fallback during playback and do
        // not need to delay the library's first frame.
        val allTargets = cachedExternalTargets ?: run {
            externalButtonsView.visibility = View.GONE
            externalButtonsScrollerView.visibility = View.GONE
            if (externalTargetsJob?.isActive != true) {
                externalTargetsJob = activityScope.launch(Dispatchers.Default) {
                    val discovered = ExternalPlayerIntentPolicy.allPlayers(applicationContext)
                    withContext(Dispatchers.Main.immediate) {
                        cachedExternalTargets = discovered
                        externalTargetsJob = null
                        if (activityStarted) renderOverlay()
                    }
                }
            }
            return
        }
        val media: OpenMediaRequest? = controller.lastOpenMedia()
        val installedTargets = allTargets.filter { it.isInstalled }
        val uninstalledTargets = allTargets.filter { !it.isInstalled }

        if (selectedPlayerTarget == null) {
            selectedPlayerTarget = installedTargets.firstOrNull { it.label == "Next Player" }
                ?: installedTargets.firstOrNull()
                ?: allTargets.firstOrNull()
        }

        val renderKey = buildString {
            append(selectedPlayerAction)
            append('|')
            append(selectedPlayerTarget?.packageName.orEmpty())
            append('|')
            allTargets.joinTo(this, separator = ",") { "${it.packageName}:${it.isInstalled}" }
        }
        if (renderedExternalOptionsVisible && renderedExternalOptionsKey == renderKey) return
        externalButtonsView.removeAllViews()

        fun createCapsuleBtn(initialText: String, isPrimary: Boolean = false): AppCompatButton {
            return AppCompatButton(this).apply {
                text = initialText
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_PX, TvTokens.designTextPx(this@MainActivity, 13f))
                minimumHeight = dp(38)
                setPadding(dp(18), dp(8), dp(18), dp(8))
                val updateBtnBg = { hasFocus: Boolean ->
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(16).toFloat()
                        setColor(
                            getColor(
                                if (hasFocus) {
                                    R.color.tv_control_fill_focused
                                } else if (isPrimary) {
                                    R.color.tv_control_fill
                                } else {
                                    R.color.tv_control_fill
                                },
                            ),
                        )
                        setStroke(
                            dp(if (isPrimary) 2 else 1),
                            getColor(if (hasFocus) R.color.tv_accent else R.color.tv_control_stroke),
                        )
                    }
                    setTextColor(getColor(if (hasFocus) R.color.tv_control_text_focused else R.color.tv_control_text))
                }
                updateBtnBg(false)
                setOnFocusChangeListener { _, hasFocus ->
                    updateBtnBg(hasFocus)
                    animate()
                        .scaleX(if (hasFocus) 1.05f else 1.0f)
                        .scaleY(if (hasFocus) 1.05f else 1.0f)
                        .setDuration(160)
                        .start()
                }
            }
        }

        // 1. Action Dropdown Button (Open / Install)
        // The Play product has no installer, so Open is the only action and the chooser is not
        // offered at all. PlayerInstaller.IS_SUPPORTED is false in that build.
        if (!PlayerInstaller.IS_SUPPORTED) selectedPlayerAction = PlayerAction.OPEN
        val actionBtn = createCapsuleBtn("Action: ${if (selectedPlayerAction == PlayerAction.OPEN) "Open" else "Install"} ▾")
        actionBtn.setOnClickListener {
            val actions = PlayerInstaller.ACTION_LABELS
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Select Player Action")
                .setItems(actions) { dialog, which ->
                    dialog.dismiss()
                    selectedPlayerAction = if (which == 0) PlayerAction.OPEN else PlayerAction.INSTALL
                    val validTargets = if (selectedPlayerAction == PlayerAction.OPEN) installedTargets else uninstalledTargets
                    selectedPlayerTarget = validTargets.firstOrNull { it.label == "Next Player" }
                        ?: validTargets.firstOrNull()
                        ?: allTargets.firstOrNull()
                    renderExternalPlayerOptions(true)
                }
                .show()
        }

        // 2. Player Selector Dropdown Button (Dynamic based on Action)
        val currentTargetName = selectedPlayerTarget?.label ?: "Next Player"
        val playerBtn = createCapsuleBtn("Player: $currentTargetName ▾")
        playerBtn.setOnClickListener {
            val availableTargets = if (selectedPlayerAction == PlayerAction.OPEN) installedTargets else uninstalledTargets
            if (availableTargets.isEmpty()) {
                val msg = if (selectedPlayerAction == PlayerAction.OPEN) "No external players installed yet." else "All supported players are already installed!"
                directionView.text = msg
                return@setOnClickListener
            }
            val names = availableTargets.map { it.label }.toTypedArray()
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Select ${if (selectedPlayerAction == PlayerAction.OPEN) "External Player to Open" else "Player to Install"}")
                .setItems(names) { dialog, which ->
                    dialog.dismiss()
                    selectedPlayerTarget = availableTargets[which]
                    renderExternalPlayerOptions(true)
                }
                .show()
        }

        // 3. Execution Button ([ Open in Next Player ] / [ Install Next Player ])
        val actionLabel = if (selectedPlayerAction == PlayerAction.OPEN) "Open in $currentTargetName" else "Install $currentTargetName"
        val executeBtn = createCapsuleBtn(actionLabel, isPrimary = true)
        executeBtn.setOnClickListener {
            val target = selectedPlayerTarget ?: return@setOnClickListener
            if (selectedPlayerAction == PlayerAction.OPEN || target.isInstalled) {
                if (media != null) {
                    activityScope.launch {
                        val position = runCatching { controller.snapshot().positionSeconds }.getOrDefault(0.0)
                        val handoff = ExternalPlayerIntentPolicy.HandoffContext(
                            positionMillis = (position * 1_000).toLong().coerceAtLeast(0),
                            headers = media.headers,
                        )
                        if (!launchExternalPlayer(media.url, media.title, target.packageName, handoff)) {
                            directionView.text = getString(R.string.external_player_failed, target.label)
                        }
                    }
                } else {
                    val launchIntent = packageManager.getLaunchIntentForPackage(target.packageName)
                    if (launchIntent != null) startActivity(launchIntent)
                }
            } else {
                PlayerInstaller.downloadAndInstall(this, target)
            }
        }

        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(6)
            rightMargin = dp(6)
        }
        // With one possible action there is nothing to choose, so the Play product drops the
        // button rather than showing a chooser with a single entry.
        if (PlayerInstaller.IS_SUPPORTED) externalButtonsView.addView(actionBtn, lp)
        externalButtonsView.addView(playerBtn, lp)
        externalButtonsView.addView(executeBtn, lp)
        externalButtonsView.visibility = View.VISIBLE
        externalButtonsScrollerView.visibility = View.VISIBLE
        externalButtonsScrollerView.isFocusable = false
        externalButtonsScrollerView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        renderedExternalOptionsVisible = true
        renderedExternalOptionsKey = renderKey
    }

    /**
     * Minimal poster-led state for a TV open. It intentionally bypasses the old home/error card:
     * while a stream is resolving, the viewer should see the title artwork and one honest status
     * line, not a second set of navigation buttons over the poster.
     */
    private fun showPlaybackStatus(
        title: String,
        detail: String,
        posterURL: String?,
        progressPermille: Int? = null,
    ) {
        if (!::playbackPosterView.isInitialized ||
            !::playbackBackdropView.isInitialized ||
            !::playbackStatusView.isInitialized
        ) return
        overlay.visibility = View.GONE
        retryButton.visibility = View.GONE
        externalButtonsView.visibility = View.GONE
        hideBufferingIndicator()
        playbackStatusTitle.text = title
        playbackStatusDetail.text = detail
        playbackStatusView.visibility = View.VISIBLE
        val stagedArtwork = com.fourseveneightnine.tv.protocol.NowPlayingArtwork.art.value
        val landscape = com.fourseveneightnine.tv.protocol.NowPlayingArt.sanitize(stagedArtwork?.landscapeURL)
        val poster = com.fourseveneightnine.tv.protocol.NowPlayingArt.sanitize(posterURL)
            ?: com.fourseveneightnine.tv.protocol.NowPlayingArt.sanitize(stagedArtwork?.posterURL)
        val candidates = listOfNotNull(landscape, poster).distinct()
        val artworkKey = candidates.joinToString("|")
        if (artworkKey != playbackArtworkKey) {
            playbackArtworkKey = artworkKey
            playbackArtworkJob?.cancel()
            playbackBackdropView.setImageDrawable(null)
            playbackPosterView.setImageDrawable(null)
            playbackPosterView.visibility = View.GONE
            playbackBackdropView.visibility = if (candidates.isEmpty()) View.GONE else View.VISIBLE
            playbackBackdropView.alpha = if (landscape != null) 1f else 0.82f
            if (candidates.isNotEmpty()) {
                playbackArtworkJob = activityScope.launch {
                    var loaded: android.graphics.Bitmap? = null
                    var loadedURL: String? = null
                    for (candidate in candidates) {
                        val bitmap = artworkLoader.load(candidate, resources.displayMetrics.widthPixels)
                        if (bitmap != null) {
                            loaded = bitmap
                            loadedURL = candidate
                            break
                        }
                    }
                    val bitmap = loaded ?: return@launch
                    if (playbackArtworkKey != artworkKey) return@launch
                    val isLandscape = loadedURL == landscape &&
                        bitmap.width.toFloat() / bitmap.height.toFloat() > 1.2f
                    if (isLandscape) {
                        playbackBackdropView.setImageBitmap(bitmap)
                        playbackBackdropView.alpha = 1f
                    } else {
                        // Keep the supplied portrait art crisp and uncropped in the centre. The
                        // blurred extension fills the widescreen canvas without cutting off faces
                        // or poster typography when a provider has no landscape backdrop.
                        playbackBackdropView.setImageBitmap(bitmap.blurredExtension())
                        playbackBackdropView.alpha = 0.82f
                        playbackPosterView.setImageBitmap(bitmap)
                        playbackPosterView.visibility = View.VISIBLE
                    }
                }
            }
        }
        if (progressPermille == null) {
            playbackStatusProgress.isIndeterminate = true
        } else {
            playbackStatusProgress.isIndeterminate = false
            playbackStatusProgress.progress = progressPermille.coerceIn(0, 1000)
        }
        renderLetterboxBacking()
    }

    private fun hidePlaybackStatus() {
        if (!::playbackPosterView.isInitialized || !::playbackStatusView.isInitialized) return
        clearPlaybackArtwork()
        playbackStatusView.visibility = View.GONE
    }

    private fun clearPlaybackArtwork() {
        playbackArtworkJob?.cancel()
        playbackArtworkJob = null
        playbackArtworkKey = null
        if (::playbackBackdropView.isInitialized) {
            playbackBackdropView.setImageDrawable(null)
            playbackBackdropView.visibility = View.GONE
        }
        if (::playbackPosterView.isInitialized) {
            playbackPosterView.setImageDrawable(null)
            playbackPosterView.visibility = View.GONE
        }
    }

    private fun renderOverlay() {
        if (!::overlay.isInitialized) return
        renderSettingsEntry()
        applyReadyIdleTreatment(
            startupState is StartupState.Ready &&
                (playbackPhase is ReceiverPlaybackPhase.Idle ||
                    playbackPhase is ReceiverPlaybackPhase.Stopped) &&
                resumeOffer == null &&
                !librarySurfaceActive &&
                !settingsSurfaceVisible,
        )
        renderRecentsShelf()
        updateLibrarySurface()
        // My Stuff is a full-screen Compose canvas. Once it owns the home phase, the legacy
        // receiver overlay must stop rendering altogether rather than being rebuilt underneath
        // it on every controller/catalog tick. Those hidden text, backdrop, and animation updates
        // still consume main-thread and RenderThread time and make D-pad focus feel intermittent.
        if (librarySurfaceActive && !settingsSurfaceVisible) {
            overlay.visibility = View.GONE
            hidePlayerControls()
            renderLetterboxBacking()
            return
        }
        // The legacy overlay is hidden under My Stuff. Do not keep rebuilding its external-player
        // buttons while the Compose canvas owns the screen; that view churn was visible as D-pad
        // stalls whenever playback state or a catalog job emitted a tick.
        renderExternalPlayerOptions(
            visible = !librarySurfaceActive && !settingsSurfaceVisible &&
                (startupState is StartupState.Ready || playbackPhase is ReceiverPlaybackPhase.Error),
        )
        if (startupState is StartupState.Error) {
            renderStartupOverlay(startupState)
            return
        }

        // Every branch below owns the buffering indicator; only one of them ever wants it.
        if (playbackPhase !is ReceiverPlaybackPhase.Buffering) clearBufferingIndicator()
        if (TVReceiverPresentationPolicy.Surface.Ended !in mountedPresentationSurfaces() && ::endedPlate.isInitialized) {
            endedPlate.hide()
        }

        when (val phase = playbackPhase) {
            is ReceiverPlaybackPhase.Opening -> {
                // The bar belongs to the title that is leaving; it must not sit over the next
                // one's "Opening…" showing the old runtime.
                hidePlayerControls()
                playbackHasStarted = false
                pictureUpAtMillis = 0L
                val resolving = phase.stage == ReceiverPreparationStage.RESOLVING
                val item = continuePlaybackItem
                val duration = item?.durationMillis ?: 0L
                val position = item?.positionMillis ?: 0L
                val progress = if (duration > 0L) PlayerControlsPolicy.progressPermille(position, duration) else null
                val clock = if (duration > 0L) {
                    "${PlayerControlsPolicy.formatTime(position)} / ${PlayerControlsPolicy.formatTime(duration)}"
                } else {
                    "Preparing playback on TV"
                }
                showPlaybackStatus(
                    title = if (resolving) "Finding a playable source" else "Loading ${phase.title.orEmpty()}".trim(),
                    detail = if (resolving) "$clock · checking your addons" else clock,
                    posterURL = item?.posterUrl ?: com.fourseveneightnine.tv.protocol.NowPlayingArtwork.art.value?.best,
                    progressPermille = progress,
                )
            }

            ReceiverPlaybackPhase.Buffering -> renderBuffering()

            is ReceiverPlaybackPhase.Error -> {
                hidePlayerControls()
                if (continuePlaybackItem != null && !continuePlaybackFallbackAttempted) {
                    continuePlaybackFallbackAttempted = true
                    continuePlaybackJob?.cancel()
                    val item = continuePlaybackItem
                    if (item != null) {
                        showPlaybackStatus(
                            title = "Trying another source",
                            detail = "The first stream did not start · ${item.title}",
                            posterURL = item.posterUrl ?: com.fourseveneightnine.tv.protocol.NowPlayingArtwork.art.value?.best,
                        )
                        continuePlaybackJob = activityScope.launch { recoverContinuePlayback(item) }
                    }
                } else {
                    showPlaybackStatus(
                        title = "Stream unavailable",
                        detail = phase.message.ifBlank { "Press Back and choose another title." },
                        posterURL = continuePlaybackItem?.posterUrl
                            ?: com.fourseveneightnine.tv.protocol.NowPlayingArtwork.art.value?.best,
                    )
                }
            }

            ReceiverPlaybackPhase.Ended -> {
                hidePlayerControls()
                cancelContinuePlaybackFlow()
                hidePlaybackStatus()
                overlay.visibility = View.GONE
                renderLetterboxBacking()
                showEndedPlate()
            }

            ReceiverPlaybackPhase.Stopped -> {
                cancelContinuePlaybackFlow()
                hidePlaybackStatus()
                if (ResumePolicy.shouldRenderStoredOffer(
                        hasOffer = resumeOffer != null,
                        startupReady = startupState is StartupState.Ready,
                    )
                ) {
                    // onStop saves a resume crumb and leaves the prior engine in Stopped. The
                    // startup offer must outrank that stale terminal phase or its button is hidden.
                    renderStartupOverlay(startupState)
                } else if (startupState is StartupState.Ready) {
                    // A stopped stream with no resume offer is the same ready receiver state as a
                    // fresh launch. Keep the handoff idle canvas instead of reviving the legacy
                    // "Playback stopped" plate.
                    renderStartupOverlay(startupState)
                } else {
                    showOverlay(dimmed = false)
                    headingView.text = getString(R.string.stopped_ready)
                    directionView.text = getString(R.string.stopped_direction)
                    progressView.visibility = View.GONE
                    retryButton.visibility = View.GONE
                    placeOverlayFocus("playback.stopped", null)
                }
            }

            ReceiverPlaybackPhase.Playing,
            ReceiverPlaybackPhase.Paused,
            -> {
                if (!playbackHasStarted) {
                    pictureUpAtMillis = android.os.SystemClock.elapsedRealtime()
                }
                playbackHasStarted = true
                continuePlaybackJob = null
                continuePlaybackItem = null
                continuePlaybackFallbackAttempted = false
                continueAttemptedSourceURLs.clear()
                hidePlaybackStatus()
                overlay.visibility = View.GONE
                renderLetterboxBacking()
                renderBackdropVisibility()
            }

            ReceiverPlaybackPhase.Idle -> {
                cancelContinuePlaybackFlow()
                hidePlaybackStatus()
                renderStartupOverlay(startupState)
            }
        }
        enforceSomethingOnScreen()
    }

    private fun renderSettingsEntry() {
        if (!::settingsButton.isInitialized) return
        val homePhase = playbackPhase is ReceiverPlaybackPhase.Idle ||
            playbackPhase is ReceiverPlaybackPhase.Stopped
        settingsButton.visibility = if (
            homePhase && startupState !is StartupState.Starting && !settingsSurfaceVisible
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    /** One presentation decision controls the mutually-compatible receiver layers. */
    private fun mountedPresentationSurfaces(): Set<TVReceiverPresentationPolicy.Surface> {
        val surface = when {
            playbackPhase is ReceiverPlaybackPhase.Ended -> TVReceiverPresentationPolicy.Surface.Ended
            ReceiverPlaybackPhasePolicy.showsPicture(playbackPhase) -> TVReceiverPresentationPolicy.Surface.Play
            librarySurfaceActive && libraryDetailItem != null -> TVReceiverPresentationPolicy.Surface.Detail
            librarySurfaceActive -> TVReceiverPresentationPolicy.Surface.Home
            else -> TVReceiverPresentationPolicy.Surface.Idle
        }
        return TVReceiverPresentationPolicy.mountedSurfaces(surface)
    }

    /**
     * THE ONE INVARIANT THIS SCREEN MUST NEVER BREAK: if there is no picture, there is a home screen.
     *
     * Every branch above hides the overlay for a good reason — a film is playing, a held frame is
     * buffering, the ended plate has taken over. Each of those is correct on its own, and each of
     * them depends on some LATER event arriving to put the overlay back. When one of those events
     * never comes (a cast the phone dropped, a decoder that died into IDLE, an ended plate whose
     * countdown finished), the receiver is left showing neither video nor interface: a black screen
     * that swallows the D-pad, because the only focusable views are in the hidden overlay. The only
     * cure a viewer finds is force-quitting the app.
     *
     * Rather than chase each path — and miss the next one — this asserts the invariant after every
     * render. It is a NET, not a state: it fires only when the screen would otherwise be empty, so
     * a correct hide (playing, buffering, plate up) passes straight through untouched.
     */
    private fun enforceSomethingOnScreen() {
        if (!::overlay.isInitialized) return
        if (overlay.visibility == View.VISIBLE) return
        if (::playbackStatusView.isInitialized && playbackStatusView.visibility == View.VISIBLE) return
        // Two signals, either of which means the screen is not empty. `playbackActive` is fed from
        // the controller's event stream and lags the phase by a coroutine hop; the phase is set in
        // this same render pass. Asking only the slow one fired the net over a film that had just
        // started. Asking only the phase would trust a signal this net exists to distrust. Asking
        // both keeps the net for the case it was built for: nothing on screen, and nothing claiming
        // otherwise.
        if (playbackActive || playerControlsVisible) return
        if (ReceiverPlaybackPhasePolicy.showsPicture(playbackPhase)) return
        if (::endedPlate.isInitialized && endedPlate.isShowing) return
        if (::optionRail.isInitialized && optionRail.isShowing) return
        ReceiverDiagnostics.record("overlay.rescued", "phase=$playbackPhase startup=$startupState")
        // Straight to the startup overlay, which owns `showOverlay()` — this cannot recurse, because
        // by the time that returns the overlay is VISIBLE and the guard above short-circuits.
        renderStartupOverlay(startupState)
    }

    /**
     * A cache dip mid-film is not the same event as a stream that will not start, and it should not
     * get the same screen.
     *
     * Blacking out the picture for two seconds of re-buffering is the loudest thing this receiver
     * can do, and it used to do it for every dip. A film that has already been playing keeps its
     * held frame and gets a centered animated buffering pill; only a stall long enough to be a
     * real problem earns the full plate, by which time covering the picture is telling the viewer
     * something they need to know.
     */
    private fun renderBuffering() {
        if (!playbackHasStarted) {
            bufferingEscalationJob?.cancel()
            bufferingEscalationJob = null
            bufferingPlateVisible = false
            hideBufferingIndicator()
            val item = continuePlaybackItem
            val duration = item?.durationMillis ?: 0L
            val position = item?.positionMillis ?: 0L
            val progress = if (duration > 0L) PlayerControlsPolicy.progressPermille(position, duration) else null
            showPlaybackStatus(
                title = "Buffering",
                detail = if (duration > 0L) {
                    "${PlayerControlsPolicy.formatTime(position)} / ${PlayerControlsPolicy.formatTime(duration)}"
                } else {
                    "Waiting for the first frame"
                },
                posterURL = item?.posterUrl ?: com.fourseveneightnine.tv.protocol.NowPlayingArtwork.art.value?.best,
                progressPermille = progress,
            )
            return
        }
        overlay.visibility = View.GONE
        renderLetterboxBacking()
        if (bufferingPlateVisible) {
            hideBufferingIndicator()
            showBufferingPlate()
            return
        }
        showBufferingIndicator()
        if (bufferingEscalationJob?.isActive == true) return
        bufferingEscalationJob = activityScope.launch {
            delay(BUFFERING_ESCALATION_MILLIS)
            if (playbackPhase is ReceiverPlaybackPhase.Buffering) {
                bufferingPlateVisible = true
                hideBufferingIndicator()
                showBufferingPlate()
            }
        }
    }

    private fun showBufferingPlate() {
        bufferingPlateVisible = true
        showOverlay(dimmed = true)
        headingView.text = getString(R.string.buffering)
        directionView.text = getString(R.string.buffering_direction)
        progressView.visibility = View.VISIBLE
        retryButton.visibility = View.GONE
        overlay.contentDescription = getString(R.string.buffering_accessibility)
    }

    private fun clearBufferingIndicator() {
        bufferingEscalationJob?.cancel()
        bufferingEscalationJob = null
        bufferingPlateVisible = false
        hideBufferingIndicator()
    }

    private fun showBufferingIndicator() {
        if (!::bufferingChip.isInitialized || bufferingChip.visibility == View.VISIBLE) return
        bufferingChip.animate().cancel()
        bufferingChip.visibility = View.VISIBLE
        if (transitionsDisabled()) {
            bufferingChip.alpha = 1f
            bufferingChip.scaleX = 1f
            bufferingChip.scaleY = 1f
            return
        }
        bufferingChip.alpha = 0f
        bufferingChip.scaleX = 0.96f
        bufferingChip.scaleY = 0.96f
        bufferingChip.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(TvTokens.Motion.BufferingEnterMillis)
            .setInterpolator(android.view.animation.PathInterpolator(0.23f, 1f, 0.32f, 1f))
            .start()
    }

    private fun hideBufferingIndicator() {
        if (!::bufferingChip.isInitialized || bufferingChip.visibility == View.GONE) return
        bufferingChip.animate().cancel()
        bufferingChip.alpha = 1f
        bufferingChip.scaleX = 1f
        bufferingChip.scaleY = 1f
        bufferingChip.visibility = View.GONE
    }

    /**
     * The end-of-film screen. The next episode appears only when the phone named one on the open
     * request — the receiver has no catalogue and never guesses what follows.
     */
    private fun showEndedPlate() {
        if (!::endedPlate.isInitialized) return
        val media = controller.lastOpenMedia()
        val next = media?.nextUp
        val posterUrl = next?.posterUrl
            ?: com.fourseveneightnine.tv.protocol.NowPlayingArtwork.art.value?.posterURL
        endedPlate.show(
            nextTitle = next?.title ?: media?.title,
            nextMeta = next?.subtitle,
            poster = null,
            onReplay = { media?.let { openMedia(it, preservePlaybackOrigin = true) } },
            onNext = next?.let { item ->
                {
                    openMedia(
                        OpenMediaRequest(
                            url = item.url,
                            title = item.title,
                            subtitle = item.subtitle,
                            headers = item.headers,
                        ),
                        preservePlaybackOrigin = true,
                    )
                }
            },
            onDone = {
                receiverIdleRequested = true
                libraryDetailItem = null
                libraryDetailLabel = null
                playbackPhase = ReceiverPlaybackPhase.Stopped
                renderOverlay()
            },
        )
        // The artwork arrives after the plate, or not at all; either way the plate is already up.
        if (posterUrl != null) {
            activityScope.launch {
                val bitmap = artworkLoader.load(posterUrl, resources.displayMetrics.widthPixels / 4)
                if (endedPlate.isShowing) endedPlate.setPoster(bitmap)
            }
        }
    }

    private fun openMedia(request: OpenMediaRequest, preservePlaybackOrigin: Boolean = false) {
        receiverIdleRequested = false
        if (!preservePlaybackOrigin) playbackPresentationOrigin = TVReceiverPresentationPolicy.PlaybackOrigin.Library
        activityScope.launch { controller.open(request) }
    }

    private fun renderStartupOverlay(state: StartupState) {
        showOverlay(dimmed = false)
        retryButton.text = getString(R.string.retry)
        retryButton.contentDescription = getString(R.string.retry_receiver)
        when (state) {
            StartupState.Ready -> {
                val offer = resumeOffer
                if (offer != null) {
                    // The film that was interrupted comes first; the pairing copy stays underneath
                    // because the phone is still the way to start anything else.
                    headingView.text = getString(
                        R.string.resume_heading,
                        offer.title ?: getString(R.string.resume_untitled),
                    )
                    directionView.text = getString(
                        R.string.resume_direction,
                        PlayerControlsPolicy.formatTime(offer.positionMillis),
                    )
                    retryButton.text = getString(R.string.resume_action)
                    retryButton.contentDescription = getString(R.string.resume_action)
                    retryButton.visibility = View.VISIBLE
                    placeOverlayFocus("ready.resumeOffer", retryButton)
                } else {
                    headingView.text = getString(R.string.ready_for_iphone)
                    directionView.text = LocalNetworkAddress.currentIPv4()?.let { address ->
                        getString(R.string.iphone_direction_with_ip, address, ReceiverPorts.HTTP)
                    } ?: getString(R.string.iphone_direction)
                    retryButton.visibility = View.GONE
                    placeOverlayFocus("ready.idle", null)
                }
                progressView.visibility = View.GONE
                overlay.contentDescription = getString(R.string.receiver_ready_accessibility)
            }

            StartupState.Starting -> {
                headingView.text = getString(R.string.starting_receiver)
                directionView.text = getString(R.string.starting_receiver_direction)
                progressView.visibility = View.VISIBLE
                retryButton.visibility = View.GONE
                overlay.contentDescription = getString(R.string.receiver_starting_accessibility)
                placeOverlayFocus("startup.starting", null)
            }

            is StartupState.Error -> {
                headingView.text = getString(R.string.receiver_start_error)
                directionView.text = state.message
                progressView.visibility = View.GONE
                retryButton.visibility = View.VISIBLE
                overlay.contentDescription = getString(R.string.receiver_error_accessibility, state.message)
                placeOverlayFocus("startup.error", retryButton)
            }
        }
    }

    private fun showOverlay(dimmed: Boolean) {
        overlay.visibility = View.VISIBLE
        renderLetterboxBacking()
        overlay.setBackgroundColor(
            if (dimmed) tokenAlpha(R.color.tv_slate, 190) else getColor(R.color.tv_overlay),
        )
        // Called after the phase has been rendered, so the backdrop reads the state it belongs to.
        renderBackdropVisibility()
    }

    private fun textView(sizeSp: Float, colorRes: Int): TextView =
        TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(getColor(colorRes))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, TvTokens.designTextPx(this@MainActivity, sizeSp))
            includeFontPadding = false
        }

    private fun tokenAlpha(colorRes: Int, alpha: Int): Int =
        (getColor(colorRes) and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private fun linearParams(topMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            this.topMargin = topMargin
        }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun ReceiverEvent.isPlaybackActive(): Boolean =
        when (this) {
            is ReceiverEvent.Play -> snapshot.active
            is ReceiverEvent.Pause -> snapshot.active
            is ReceiverEvent.Seek -> snapshot.active
            is ReceiverEvent.SpeedChanged -> snapshot.active
            is ReceiverEvent.Stop -> false
            // The video moved to another app on this TV: this receiver is no longer playing.
            is ReceiverEvent.ExternalHandoff -> false
            // A non-fatal error (silent-audio fallback) leaves playback running.
            is ReceiverEvent.Error -> !fatal && snapshot.active
            is ReceiverEvent.VolumeChanged -> snapshot.active
            is ReceiverEvent.LinkRefreshRequested -> snapshot.active
        }

    private fun ReceiverEvent.playbackSpeed(): Int =
        when (this) {
            is ReceiverEvent.Play -> snapshot.speed
            is ReceiverEvent.Pause -> snapshot.speed
            is ReceiverEvent.Seek -> snapshot.speed
            is ReceiverEvent.SpeedChanged -> snapshot.speed
            is ReceiverEvent.Stop -> 0
            is ReceiverEvent.ExternalHandoff -> 0
            is ReceiverEvent.Error -> if (fatal) 0 else snapshot.speed
            is ReceiverEvent.VolumeChanged -> snapshot.speed
            is ReceiverEvent.LinkRefreshRequested -> snapshot.speed
        }

    private fun ReceiverEvent.snapshot() =
        when (this) {
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

    private fun dp(value: Int): Int = TvTokens.designUnit(this, value)

    private sealed interface StartupState {
        data object Starting : StartupState

        data object Ready : StartupState

        data class Error(
            val message: String,
        ) : StartupState
    }

    private companion object {
        const val ENGINE_PREFERENCES_NAME = "receiver-engine"
        const val ENGINE_OVERRIDE_KEY = "override"

        const val REMOTE_SEEK_SECONDS = 10.0

        /** Track overrides land on the player thread; give it a beat before re-reading them. */
        const val TRACK_SUMMARY_SETTLE_MILLIS = 350L

        /** Slow on purpose — a crash-safety crumb, not a progress feed. */
        const val RESUME_SAVE_INTERVAL_MILLIS = 15_000L
        const val RESUME_READY_TIMEOUT_MILLIS = 30_000L

        /** The bar arrives and leaves; a hard cut on a large panel reads as a glitch. */
        const val CONTROLS_FADE_IN_MILLIS = 220L
        const val CONTROLS_FADE_OUT_MILLIS = 140L
        const val CONTROLS_RISE_DP = 16
        const val CONTROLS_EXIT_DP = 8

        /**
         * How long a mid-film stall may keep the picture before the full waiting plate takes over.
         * Below this it is a cache dip and the frame stays; above it the viewer needs telling.
         */
        const val BUFFERING_ESCALATION_MILLIS = 6_000L
        const val CONTINUE_OPEN_WAIT_MILLIS = 30_000L

        /** Dim enough that white copy stays legible over any frame the artwork happens to be. */
        const val BACKDROP_ALPHA = 0.38f
        const val BACKDROP_FADE_MILLIS = 320L
        /** How long focus must rest on a card before its poster is worth a panel-width load. */
        const val BACKDROP_SETTLE_MILLIS = 260L
        const val SURFACE_READY_TIMEOUT_MILLIS = 6_000L

        const val HORIZONTAL_PADDING_DP = 48
        const val VERTICAL_PADDING_DP = 24
        const val HEADING_TOP_MARGIN_DP = 14
        const val DIRECTION_TOP_MARGIN_DP = 8
        const val PROGRESS_TOP_MARGIN_DP = 16
        const val RETRY_TOP_MARGIN_DP = 16
        const val BUTTON_MIN_HEIGHT_DP = 44
        const val BUTTON_MIN_WIDTH_DP = 180
        const val EXTERNAL_BUTTONS_TOP_MARGIN_DP = 12
        const val EXTERNAL_BUTTON_TOP_MARGIN_DP = 8

        /** Fire TV panels crop roughly 5% of each edge; keep scrollable content inside that. */
        const val OVERSCAN_INSET_DP = 16
        const val BRAND_HEIGHT_DP = 96
        const val IDLE_BRAND_HEIGHT_DP = 170
        const val IDLE_BRAND_STAGE_DP = 210
        const val IDLE_PING_RING_DP = 200
        const val IDLE_RECENT_WIDTH_DP = 186
        const val IDLE_RECENT_HEIGHT_DP = 105
        const val TEXT_HEADING_SP = 96f
        const val TEXT_DIRECTION_SP = 21f
        const val TEXT_BUTTON_SP = 16f
        const val TEXT_DIAGNOSTICS_SP = 14f
        const val FRAME_RATE_EPSILON = 0.001f
        const val MEDIA_SESSION_TAG = "4789 TV"
        val MEDIA_SESSION_ACTIONS =
            PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_STOP or
                PlaybackState.ACTION_REWIND or
                PlaybackState.ACTION_FAST_FORWARD or
                PlaybackState.ACTION_SEEK_TO

        const val NETWORK_START_ERROR =
            "The TV receiver could not start on this network. Check the connection, then try again."
        const val RESUME_EXPIRED_ERROR =
            "That stream's link has expired. Cast it again from your iPhone to pick up where you left off."
        const val SURFACE_START_ERROR =
            "Video output could not start. Try again, or reopen 4789 TV."
    }
}
