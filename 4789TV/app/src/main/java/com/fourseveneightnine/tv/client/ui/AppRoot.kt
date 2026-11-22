package com.fourseveneightnine.tv.client.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.produceState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.zIndex
import androidx.navigation.NavBackStackEntry
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.savedstate.compose.LocalSavedStateRegistryOwner
import java.lang.ref.WeakReference
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import androidx.navigation.NavHostController
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fourseveneightnine.tv.client.ui.components.benchmarkDraw
import com.fourseveneightnine.tv.client.OfflineLetterboxd
import com.fourseveneightnine.tv.client.AppGraph
import com.fourseveneightnine.tv.client.appGraph
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.playback.PlaybackSession
import com.fourseveneightnine.tv.client.playback.PlayResult
import com.fourseveneightnine.tv.client.playback.WatchlistSource
import com.fourseveneightnine.tv.client.playback.PlayRequest
import com.fourseveneightnine.tv.client.playback.ReceiverHost
import com.fourseveneightnine.tv.client.playback.ReceiverState
import com.fourseveneightnine.tv.client.profiles.ProfileSessionState
import com.fourseveneightnine.tv.client.search.SystemSearchCommand
import com.fourseveneightnine.tv.client.ui.components.SidePanelRow
import com.fourseveneightnine.tv.client.ui.components.Toast
import com.fourseveneightnine.tv.client.ui.components.tvGlassPanel
import com.fourseveneightnine.tv.client.ui.nav.CastStatus
import com.fourseveneightnine.tv.client.ui.nav.NavRail
import com.fourseveneightnine.tv.client.ui.nav.Route
import com.fourseveneightnine.tv.client.ui.nav.TopLevel
import com.fourseveneightnine.tv.client.ui.nav.ClientNav
import com.fourseveneightnine.tv.client.ui.profiles.ProfileGate
import com.fourseveneightnine.tv.client.ui.screens.calendar.CalendarScreen
import com.fourseveneightnine.tv.client.ui.screens.collections.CollectionDetailScreen
import com.fourseveneightnine.tv.client.ui.screens.collections.CollectionEditorScreen
import com.fourseveneightnine.tv.client.ui.screens.collections.CollectionsScreen
import com.fourseveneightnine.tv.client.ui.screens.detail.DetailPreview
import com.fourseveneightnine.tv.client.ui.screens.detail.DetailScreen
import com.fourseveneightnine.tv.client.ui.screens.discover.DiscoverScreen
import com.fourseveneightnine.tv.client.ui.screens.home.HomeScreen
import com.fourseveneightnine.tv.client.ui.screens.live.LiveTvScreen
import com.fourseveneightnine.tv.client.ui.screens.live.LiveTvLaunch
import com.fourseveneightnine.tv.client.ui.screens.live.LiveMultiviewScreen
import com.fourseveneightnine.tv.client.ui.screens.search.SearchScreen
import com.fourseveneightnine.tv.client.ui.screens.streams.StreamsScreen
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.fourseveneightnine.tv.protocol.NowPlayingArt
import com.fourseveneightnine.tv.client.ui.screens.player.PlaybackLoadingSurface
import com.fourseveneightnine.tv.client.ui.screens.player.PlayerScreen
import com.fourseveneightnine.tv.client.ui.screens.settings.SettingsKeys
import com.fourseveneightnine.tv.client.ui.screens.settings.SettingsScreen
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvMotion
import com.fourseveneightnine.tv.client.ui.theme.LocalReduceMotion
import com.fourseveneightnine.tv.client.ui.theme.TvTheme
import com.fourseveneightnine.tv.client.ui.theme.TvType
import com.fourseveneightnine.tv.player.ResumePolicy
import com.fourseveneightnine.tv.client.iptv.iptvPlaybackHeaders
import kotlinx.coroutines.withTimeoutOrNull
import com.fourseveneightnine.tv.player.ReceiverPlaybackPhase
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics
import com.fourseveneightnine.tv.ui.VideoResizeMode
import com.fourseveneightnine.tv.ui.TvRemoteCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * The whole shell: one rail, one nav host, one rescue net.
 *
 * Nothing here paints the canvas. The Activity keeps a persistent video layer underneath this
 * composition, so a screen that wants an opaque background says so itself and the player route
 * deliberately does not — that is how the picture shows through.
 */
@Composable
internal fun AppRoot(
    graph: AppGraph,
    host: ReceiverHost,
    shell: ShellState,
    onResizeMode: (VideoResizeMode) -> Unit,
    onControlBarVisible: (Boolean) -> Unit,
    onOpenPictureSettings: () -> Unit = {},
    onBrowseX: () -> Unit = {},
    systemSearchCommands: Flow<SystemSearchCommand>,
    onExit: () -> Unit,
) {
    // Appearance settings live in the presentation preferences. Re-read on every
    // change so a toggle applies without a relaunch.
    val prefs = graph.presentationPreferences
    var reduceMotion by remember { mutableStateOf(prefs.getBoolean(SettingsKeys.REDUCE_MOTION, false)) }
    var blackCanvas by remember { mutableStateOf(prefs.getString(SettingsKeys.CANVAS, SettingsKeys.CANVAS_SLATE) == SettingsKeys.CANVAS_BLACK) }
    androidx.compose.runtime.DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == SettingsKeys.REDUCE_MOTION) reduceMotion = p.getBoolean(key, false)
            if (key == SettingsKeys.CANVAS) blackCanvas = p.getString(key, SettingsKeys.CANVAS_SLATE) == SettingsKeys.CANVAS_BLACK
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    TvTheme(reduceMotion = reduceMotion, blackCanvas = blackCanvas) {
        CompositionLocalProvider(LocalShellState provides shell) {
            val context = androidx.compose.ui.platform.LocalContext.current
            val client = remember(context) { context.applicationContext.clientGraph }
            val lifecycleOwner = LocalLifecycleOwner.current
            val settingsLoaded by host.settingsLoaded.collectAsState()
            val configured by host.settingsConfigured.collectAsState()
            val profileState by client.profileSession.state.collectAsState()
            val profileCatalog by client.profiles.collectAsState()
            val playbackOrigin by graph.playback.origin.collectAsState()
            val playbackPhase by graph.playback.phase.collectAsState()
            LaunchedEffect(playbackOrigin) {
                if (playbackOrigin == PlaybackSession.Origin.Phone) LiveTvLaunch.clearPlayback()
            }
            LaunchedEffect(lifecycleOwner) {
                lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    val session = graph.playback
                    if (client.profileSession.state.value is ProfileSessionState.Unlocked) return@repeatOnLifecycle
                    val point = session.pendingResume(null)?.takeIf { it.origin == "phone" }
                        ?: return@repeatOnLifecycle
                    if (withTimeoutOrNull(8_000L) { session.surfaceReadyState.first { it } } != true ||
                        client.profileSession.state.value is ProfileSessionState.Unlocked ||
                        session.origin.value != null) return@repeatOnLifecycle
                    val owner = session.generation
                    val message = when {
                        !ResumePolicy.linkIsRecent(point, System.currentTimeMillis()) ->
                            "The cast link expired. Cast this title again from your phone."
                        session.restoreDirect(point).isFailure -> "Couldn’t reopen the cast. Cast this title again from your phone."
                        else -> null
                    }
                    if (message != null && session.generation in owner..(owner + 1))
                        session.showResumeError(point, message)
                }
            }
            var profileGateEpoch by remember { mutableIntStateOf(0) }

            // Unlock is runtime-only. Backgrounding discards both the active repository pointer
            // and any PIN UI state; returning to a multi-profile household always crosses the gate.
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_STOP) {
                        profileGateEpoch++
                        client.lockProfile()
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            if (!settingsLoaded) {
                Box(Modifier.fillMaxSize().background(TvColor.Background))
                return@CompositionLocalProvider
            }

            val phonePlaybackActive = playbackOrigin == PlaybackSession.Origin.Phone && when (playbackPhase) {
                is ReceiverPlaybackPhase.Idle,
                is ReceiverPlaybackPhase.Ended,
                is ReceiverPlaybackPhase.Stopped,
                -> false
                is ReceiverPlaybackPhase.Error -> graph.playback.resumeTitle != null
                else -> true
            }
            if (profileState !is ProfileSessionState.Unlocked && phonePlaybackActive) {
                // Casting is receiver-global. A locked household profile must never hide or block
                // a phone-started title, and this surface touches no personal library.
                PlayerScreen(
                    session = graph.playback,
                    onResizeMode = onResizeMode,
                    onControlBarVisible = onControlBarVisible,
                    onLocalControl = host::notifyLocalControl,
                    onLeave = client::lockProfile,
                )
                return@CompositionLocalProvider
            }

            when {
                profileState is ProfileSessionState.Unlocked -> {
                    val profileId = (profileState as ProfileSessionState.Unlocked).profileId
                    // Keep the first setup dialog alive long enough to show its save receipt.
                    var shellConfigured by remember { mutableStateOf(configured) }
                    LaunchedEffect(configured, shell.overlayVisible) {
                        if (!shell.overlayVisible) shellConfigured = configured
                    }
                    // A new controller per unlocked profile is the navigation reset: no Detail,
                    // Streams or Collection back-stack entry can cross the profile boundary.
                    key(profileId, shellConfigured) {
                        val navController = rememberNavController()
                        val navigationOwner = remember {
                            object : ViewModelStoreOwner {
                                override val viewModelStore = ViewModelStore()
                            }
                        }
                        DisposableEffect(navController, navigationOwner) {
                            onDispose {
                                // Detach the old Activity observer before retiring only this shell.
                                // Attaching an already-destroyed owner would not replay ON_DESTROY.
                                val retirementOwner = object : LifecycleOwner {
                                    override val lifecycle = LifecycleRegistry(this)
                                }
                                val alreadyDestroyed = navController.currentBackStackEntry?.lifecycle?.currentState ==
                                    Lifecycle.State.DESTROYED
                                retirementOwner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
                                // Activity teardown may already have destroyed the entries. Do not
                                // replay ON_CREATE to them, because destroyed entries cannot revive.
                                if (alreadyDestroyed) retirementOwner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
                                navController.setLifecycleOwner(retirementOwner)
                                if (!alreadyDestroyed) retirementOwner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
                                navigationOwner.viewModelStore.clear()
                            }
                        }
                        CompositionLocalProvider(LocalViewModelStoreOwner provides navigationOwner) {
                            Shell(
                                graph = graph,
                                host = host,
                                shell = shell,
                                navController = navController,
                                configured = shellConfigured,
                                onResizeMode = onResizeMode,
                                onControlBarVisible = onControlBarVisible,
                                onBrowseX = onBrowseX,
                                onOpenPictureSettings = onOpenPictureSettings,
                                systemSearchCommands = systemSearchCommands,
                                onExit = onExit,
                            )
                        }
                    }
                }

                profileCatalog != null -> key(profileGateEpoch) {
                    ProfileGate(
                        catalog = requireNotNull(profileCatalog),
                        onSelect = client::selectProfile,
                        onVerifyPin = client::verifyProfilePinForGate,
                        onUnlock = client::finishProfileUnlock,
                        unlockEpoch = { client.profileSession.epoch },
                        pinChecks = client.profilePinChecks,
                    )
                }

                else -> Box(Modifier.fillMaxSize().background(TvColor.Background))
            }
        }
    }
}

@Composable
private fun Shell(
    graph: AppGraph,
    host: ReceiverHost,
    shell: ShellState,
    navController: NavHostController,
    configured: Boolean,
    onResizeMode: (VideoResizeMode) -> Unit,
    onControlBarVisible: (Boolean) -> Unit,
    onOpenPictureSettings: () -> Unit = {},
    onBrowseX: () -> Unit = {},
    systemSearchCommands: Flow<SystemSearchCommand>,
    onExit: () -> Unit,
) {
    val reduceMotion = LocalReduceMotion.current
    val session = graph.playback
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentPath = backStackEntry?.destination?.route
    if (com.fourseveneightnine.tv.BuildConfig.BENCHMARK_LOGGING) {
        DisposableEffect(navController) {
            val listener = androidx.navigation.NavController.OnDestinationChangedListener { _, destination, _ ->
                val route = destination.route?.substringBefore('/') ?: "unknown"
                ReceiverDiagnostics.benchmark("nav.destination") { "route=$route" }
            }
            navController.addOnDestinationChangedListener(listener)
            onDispose { navController.removeOnDestinationChangedListener(listener) }
        }
        LaunchedEffect(backStackEntry) {
            val entry = backStackEntry ?: return@LaunchedEffect
            val route = entry.destination.route?.substringBefore('/') ?: "unknown"
            val id = System.identityHashCode(entry)
            ReceiverDiagnostics.benchmark("nav.route") { "route=$route entry=$id reduceMotion=$reduceMotion" }
            withFrameNanos { }
            ReceiverDiagnostics.benchmark("nav.frame_callback") { "route=$route entry=$id" }
            entry.lifecycle.currentStateFlow.first { it == Lifecycle.State.RESUMED }
            ReceiverDiagnostics.benchmark("nav.resumed") { "route=$route entry=$id" }
        }
    }
    val offlineRevision by appContext.clientGraph.offlineLetterboxd.revision.collectAsState()
    val offlineCollections by produceState<Boolean?>(null, appContext, offlineRevision) {
        value = appContext.clientGraph.offlineLetterboxd.available()
    }
    val localPlayFlow = appContext.clientGraph.playFlow
    val preparing by localPlayFlow.preparing.collectAsState()
    var playerCoverEntryId by remember { mutableStateOf<String?>(null) }
    val topLevel = TopLevel.forPath(currentPath)
    val activeBrowse = backStackEntry?.takeIf { topLevel == TopLevel.Home || topLevel == TopLevel.Discover }
    var lastBrowse by remember { mutableStateOf<NavBackStackEntry?>(null) }
    val browseEntry = activeBrowse ?: lastBrowse?.takeIf { currentPath == Route.Detail.PATTERN }
    val browseState = rememberSaveableStateHolder()
    val collectionEntries = remember(backStackEntry) {
        val directory = runCatching { navController.getBackStackEntry(Route.Collections.path) }.getOrNull()
        val local = runCatching { navController.getBackStackEntry(Route.CollectionDetail.PATTERN) }.getOrNull()
        val keepLocal = local != null && (currentPath == Route.CollectionDetail.PATTERN ||
            runCatching { navController.getBackStackEntry(Route.Detail.PATTERN) }.isSuccess)
        listOfNotNull(directory, local.takeIf { keepLocal })
    }
    var collectionReturn by remember { mutableStateOf<CollectionReturn?>(null) }
    var focusedCollection by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(backStackEntry, collectionReturn) {
        val returning = collectionReturn ?: return@LaunchedEffect
        when (backStackEntry?.id) {
            returning.detail -> {
                // Place the retained grid behind the opaque Detail page and transfer focus
                // before starting the pop. A deleted poster must never trap the Back key.
                ReceiverDiagnostics.benchmark("nav.collection_return.start") { "target=collection" }
                var waitedFrames = 0
                for (frame in 1..20) {
                    withFrameNanos { }
                    waitedFrames = frame
                    if (focusedCollection == returning.collection) break
                }
                ReceiverDiagnostics.benchmark("nav.collection_return.focus") {
                    "frames=$waitedFrames restored=${focusedCollection == returning.collection}"
                }
                if (navController.currentBackStackEntry?.id == returning.detail &&
                    navController.popBackStack()
                ) return@LaunchedEffect
            }
            returning.collection -> backStackEntry?.lifecycle?.currentStateFlow?.first {
                it == Lifecycle.State.RESUMED
            }
        }
        collectionReturn = null
    }
    SideEffect {
        if (activeBrowse != null || currentPath != Route.Detail.PATTERN) lastBrowse = activeBrowse
    }

    var railOpen by remember { mutableStateOf(false) }
    var exitArmedAt by remember { mutableLongStateOf(0L) }
    var closingRail by remember { mutableStateOf(false) }
    val railScope = rememberCoroutineScope()
    var anythingFocused by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    fun openSeededSearch(query: String, fromDetail: Boolean) {
        if (fromDetail) navController.navigate(Route.Search.path)
        else navController.navigateTop(Route.Search.path)
        navController.currentBackStackEntry?.savedStateHandle?.let { state ->
            state["searchQuery"] = query
            state["searchFromDetail"] = fromDetail
            state["searchQueryVersion"] = (state.get<Long>("searchQueryVersion") ?: 0L) + 1L
        }
    }

    val receiverState by host.state.collectAsState()
    val origin by session.origin.collectAsState()
    val phase by session.phase.collectAsState()
    val profileCatalog by appContext.clientGraph.profiles.collectAsState()
    val activeProfileId = appContext.clientGraph.activeProfileId()
    val activeProfile = profileCatalog?.profiles?.firstOrNull { it.id == activeProfileId }
    fun disarmExit() { exitArmedAt = 0L }

    fun closeRail() {
        disarmExit()
        // Focus properties update on the next composition. Keep the rail from reopening until
        // that update has landed and the page has had a chance to take focus back.
        closingRail = true
        railOpen = false
        railScope.launch {
            androidx.compose.runtime.withFrameNanos { }
            shell.restoreContentFocus?.invoke()
            androidx.compose.runtime.withFrameNanos { }
            closingRail = false
        }
    }

    fun openRail() {
        if (closingRail || shell.overlayVisible || TopLevel.forPath(navController.currentDestination?.route) == null) return
        disarmExit()
        if (railOpen) closeRail() else railOpen = true
    }

    shell.openRail = ::openRail

    // A Back gesture can carry the open rail to Home. Other route changes release it.
    LaunchedEffect(currentPath, offlineCollections) {
        val prefs = graph.presentationPreferences
        if (currentPath == Route.Home.path && !configured && offlineCollections != null && session.origin.value == null &&
            !prefs.getBoolean(TV_SETUP_OFFERED, false)
        ) {
            val freshInstall = withContext(Dispatchers.IO) {
                runCatching {
                    val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
                    info.firstInstallTime == info.lastUpdateTime
                }.getOrDefault(false)
            }
            if (freshInstall && navController.currentDestination?.route == Route.Home.path &&
                session.origin.value == null
            ) {
                if (offlineCollections == true) {
                    navController.navigateTop(Route.Collections.path)
                } else {
                    navController.navigateTop(Route.Settings().path)
                    navController.currentBackStackEntry?.savedStateHandle?.set(TV_SETUP_OFFERED, true)
                }
                prefs.edit().putBoolean(TV_SETUP_OFFERED, true).apply()
            }
        }
        if (currentPath != Route.Home.path) {
            disarmExit()
            railOpen = false
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) disarmExit()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // A phone `Player.Open` navigates here from wherever the viewer was, Settings included.
    LaunchedEffect(Unit) {
        // An already-restored phone cast may predate this shell when a profile is unlocked.
        val activeCast = session.origin.value == PlaybackSession.Origin.Phone && when (session.phase.value) {
            ReceiverPlaybackPhase.Idle, ReceiverPlaybackPhase.Ended, ReceiverPlaybackPhase.Stopped -> false
            is ReceiverPlaybackPhase.Error -> session.resumeTitle != null
            else -> true
        }
        if (activeCast && navController.currentBackStackEntryFlow.first().destination.route != Route.Player.path) {
            navController.navigate(Route.Player.path) { launchSingleTop = true }
        }
        // Accept on Main before the opening coroutine releases its preparation artwork.
        withContext(Dispatchers.Main.immediate) {
            session.openRequests.collect { origin ->
                val from = navController.currentDestination?.route
                if (from != Route.Player.path) {
                    navController.navigate(Route.Player.path) { launchSingleTop = true }
                    if (origin == PlaybackSession.Origin.Local && localPlayFlow.preparing.value != null &&
                        from != Route.Detail.PATTERN && from != Route.Streams.PATTERN
                    ) {
                        playerCoverEntryId = navController.currentBackStackEntry?.id
                    }
                }
            }
        }
    }

    LaunchedEffect(lifecycleOwner, navController) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val route = navController.currentBackStackEntryFlow.first().destination.route
            if (route !in setOf(Route.Home.path, Route.LiveTv.path, Route.Player.path)) return@repeatOnLifecycle
            val liveId = session.resumeLiveChannelId
            if (liveId != null) {
                if (session.origin.value == null) {
                    LiveTvLaunch.stage("resume-channel", liveId)
                    navController.navigateTop(Route.LiveTv.path)
                }
                return@repeatOnLifecycle
            }
            val point = session.pendingResume(activeProfileId?.toString()) ?: return@repeatOnLifecycle
            if (withTimeoutOrNull(8_000L) { session.surfaceReadyState.first { it } } != true ||
                session.origin.value != null ||
                navController.currentDestination?.route !in setOf(Route.Home.path, Route.LiveTv.path, Route.Player.path)) {
                return@repeatOnLifecycle
            }
            val client = appContext.clientGraph
            val owner = session.generation
            if (point.origin == "local" && point.catalogType == "series" && point.catalogId != null &&
                point.season != null && point.episode != null &&
                client.activeLibrary.hasPendingSuccessor(point.catalogId, point.season, point.episode)) return@repeatOnLifecycle
            if (session.generation != owner || session.origin.value != null) return@repeatOnLifecycle
            val result = try {
                when {
                    point.catalogId != null -> when (val restored = client.playFlow.restore(point)) {
                        PlayResult.Opened -> null
                        is PlayResult.Failed -> restored.message
                        PlayResult.ShowList -> "Couldn’t reopen this source. Open Sources to choose another."
                    }
                    point.iptvVodId != null -> {
                        val vod = withTimeoutOrNull(8_000L) {
                            client.iptv.state.first { state ->
                                state.catalog.vod.any { it.id == point.iptvVodId } ||
                                    state.catalog.episodes.any { it.id == point.iptvVodId } || !state.catalogLoading
                            }
                        }?.catalog?.let { catalog ->
                            (catalog.vod.asSequence() + catalog.episodes.asSequence()).firstOrNull { it.id == point.iptvVodId }
                        }
                        if (session.generation != owner) return@repeatOnLifecycle
                        val resolved = if (ResumePolicy.linkIsRecent(point, System.currentTimeMillis())) point
                            else vod?.let {
                                try {
                                    client.iptv.renewVod(it)?.let { (item, url) ->
                                        point.copy(url = url, headers = iptvPlaybackHeaders(item.headers),
                                            savedAtMillis = System.currentTimeMillis())
                                    }
                                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                catch (_: Exception) { null }
                            }
                        if (session.generation != owner) return@repeatOnLifecycle
                        if (resolved != null && session.restoreDirect(resolved, vod).isSuccess) null
                        else "Couldn’t reopen this IPTV video."
                    }
                    ResumePolicy.linkIsRecent(point, System.currentTimeMillis()) ->
                        if (session.restoreDirect(point).isSuccess) null else "Couldn’t reopen the last video."
                    point.origin == "phone" -> "The cast link expired. Cast this title again from your phone."
                    else -> "The last video link expired. Open the title again."
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                coroutineContext.ensureActive()
                return@repeatOnLifecycle
            } catch (_: Exception) {
                "Couldn’t reopen the last video."
            }
            if (result != null && session.generation in owner..(owner + 1)) {
                if (point.origin == "local" && point.watchlistId == null && point.catalogType == "series" &&
                    point.catalogId != null && point.season != null && point.episode != null) {
                    toast = "Choose another source for S${point.season} E${point.episode}."
                    navController.navigate(Route.Streams("series", point.catalogId, point.season, point.episode).path) {
                        launchSingleTop = true
                    }
                    return@repeatOnLifecycle
                }
                session.showResumeError(point, result)
                if (navController.currentDestination?.route != Route.Player.path)
                    navController.navigate(Route.Player.path) { launchSingleTop = true }
            }
        }
    }

    fun playWatchlistSource(type: String, id: String) {
        railScope.launch {
            when (val result = localPlayFlow.play(PlayRequest(type, id, "Saved source"))) {
                is PlayResult.Failed -> toast = result.message
                PlayResult.ShowList -> toast = "Couldn’t open this saved source."
                PlayResult.Opened -> Unit
            }
        }
    }

    LaunchedEffect(systemSearchCommands) {
        systemSearchCommands.collect { command ->
            when (command) {
                SystemSearchCommand.LiveGuide -> {
                    val opened = shell.playerCommands?.invoke(TvRemoteCommand.OpenGuide) == true
                    if (!opened) {
                        LiveTvLaunch.stage("guide", "")
                        navController.navigateTop(Route.LiveTv.path)
                    }
                }
                is SystemSearchCommand.Query -> openSeededSearch(command.value, fromDetail = false)
                is SystemSearchCommand.Detail ->
                    if (configured) {
                        if (command.id.startsWith(WatchlistSource.PREFIX)) playWatchlistSource(command.type, command.id)
                        else navController.navigate(Route.Detail(command.type, command.id).path)
                    }
                is SystemSearchCommand.Resume -> {
                    if (!configured) return@collect
                    val generation = session.generation
                    val client = appContext.clientGraph
                    if (client.activeProfileId() != command.profile) return@collect
                    try {
                        val item = client.activeLibrary.continueWatching().first().firstOrNull {
                            it.canonicalId == command.id && it.mediaType == command.type
                        }
                        coroutineContext.ensureActive()
                        if (session.generation != generation) return@collect
                        if (item == null) {
                            navController.navigate(Route.Detail(command.type, command.id).path)
                            return@collect
                        }
                        when (val result = client.playFlow.play(PlayRequest(
                            type = item.mediaType, id = item.canonicalId, title = item.title,
                            season = item.season, episode = item.episode, resumeFromMs = item.positionMs,
                            posterUrl = item.posterUrl, backdropUrl = item.backdropUrl,
                        ))) {
                            PlayResult.Opened -> Unit
                            PlayResult.ShowList -> navController.navigate(
                                Route.Streams(item.mediaType, item.canonicalId, item.season, item.episode).path,
                            )
                            is PlayResult.Failed -> {
                                toast = result.message
                                if (item.mediaType == "series" && item.season != null && item.episode != null)
                                    navController.navigate(Route.Streams(item.mediaType, item.canonicalId, item.season, item.episode).path)
                            }
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        // Retire this request without canceling the long-lived command collector.
                        coroutineContext.ensureActive()
                    }
                }
            }
        }
    }

    LaunchedEffect(toast) {
        if (toast == null) return@LaunchedEffect
        delay(TOAST_LIFE_MILLIS)
        toast = null
    }

    LaunchedEffect(exitArmedAt) {
        if (exitArmedAt == 0L) return@LaunchedEffect
        delay(DOUBLE_BACK_MILLIS)
        disarmExit()
    }

    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    // Restore the screen's focus in place. Only a navigation key can open the menu.
    LaunchedEffect(currentPath, anythingFocused, shell.playerVisible) {
        if (anythingFocused || shell.playerVisible) return@LaunchedEffect
        delay(FOCUS_RESCUE_MILLIS)
        if (anythingFocused || shell.playerVisible) return@LaunchedEffect
        shell.restoreContentFocus?.invoke()
        delay(FOCUS_RESCUE_RETRY_MILLIS)
        if (anythingFocused || shell.playerVisible || shell.overlayVisible || railOpen) return@LaunchedEffect
        focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Enter)
    }

    var showAllCatalogs by remember { mutableStateOf(false) }
    var homeEntry by remember { mutableIntStateOf(0) }
    val nav = remember(navController) {
        object : ClientNav {
            override fun openRail() { shell.openRail?.invoke() }
            override fun back() { navController.popBackStack() }
            override fun openHome() {
                // Explicit Home (including a stopped cast) starts a new browse session.
                shell.posterHandoff = null
                homeEntry++
                navController.navigateTop(Route.Home.path)
            }
            override fun openLiveTv() = navController.navigateTop(Route.LiveTv.path)
            override fun openLiveItem(kind: String, id: String) {
                LiveTvLaunch.stage(kind, id)
                navController.navigateTop(Route.LiveTv.path)
            }
            override fun openMultiview(channelId: String) =
                navController.navigate(Route.LiveMultiview(channelId).path)
            override fun openSearch(query: String) = openSeededSearch(query, fromDetail = true)
            override fun openDiscover() {
                if (configured) navController.navigateTop(Route.Discover.path)
                else toast = "Add a source in Settings → Add-ons to browse catalogs."
            }
            override fun openAllCatalogs() {
                if (configured) { showAllCatalogs = true; navController.navigateTop(Route.Discover.path) }
                else toast = "Add a source in Settings → Add-ons to browse catalogs."
            }
            override fun openDetail(type: String, id: String, season: Int?, episode: Int?) {
                ReceiverDiagnostics.benchmark("nav.intent") { "to=detail series=${type == "series"}" }
                if (configured && id.startsWith(WatchlistSource.PREFIX)) {
                    shell.selectedPoster = null
                    playWatchlistSource(type, id)
                    return
                }
                val selected = shell.selectedPoster?.takeIf { it.key == "$type:$id" }
                shell.selectedPoster = null
                val staged = DetailPreview.forTitle(type, id)
                val bundledTitle = staged?.collectionFocusKey?.startsWith(OfflineLetterboxd.PREFIX) == true
                if (configured || bundledTitle) {
                    val shelf = navController.currentBackStackEntry?.id
                    navController.navigate(Route.Detail(type, id).path)
                    navController.currentBackStackEntry?.savedStateHandle?.let { state ->
                        state["detailSeason"] = season ?: -1
                        state["detailEpisode"] = episode ?: -1
                        state["detailPreviewTitle"] = staged?.title
                        state["detailPreviewPosterLookupId"] = staged?.posterLookupId
                    }
                    val detail = navController.currentBackStackEntry?.id
                    if (selected != null && shelf != null && detail != null && shelf != detail) {
                        val returning = shell.posterHandoff?.takeIf { it.shelfEntry == shelf && it.origin.key == selected.key }
                        shell.posterHandoff = if (returning == null) PosterHandoff(selected, shelf, detail)
                            else PosterHandoff(selected, shelf, detail, returning.progress)
                    }
                } else toast = "Add a source in Settings → Add-ons to open this title."
            }
            override fun openStreams(type: String, id: String, season: Int?, episode: Int?) {
                ReceiverDiagnostics.benchmark("nav.intent") { "to=sources series=${type == "series"}" }
                if (configured) navController.navigate(Route.Streams(type, id, season, episode).path)
                else toast = "Add a source in Settings → Add-ons."
            }
            override fun openCollection(id: String) {
                if (configured) navController.navigate(Route.CollectionDetail(id).path)
                else toast = "Add a source in Settings → Add-ons to use collections."
            }
            override fun openWatchlist(id: String, mediaType: String) {
                if (!configured) {
                    openCollection(id)
                    return
                }
                navController.navigate(Route.CollectionDetail(id).path)
                navController.currentBackStackEntry?.savedStateHandle?.set("watchlistMediaType", mediaType)
            }
            override fun openCollectionEditor(id: String?) {
                if (configured) navController.navigate(Route.CollectionEditor(id).path)
                else toast = "Add a source in Settings → Add-ons to use collections."
            }
            override fun openSettings(page: String) = navController.navigateTop(Route.Settings(page).path)
            override fun openPlayer() = navController.navigate(Route.Player.path) { launchSingleTop = true }
            override fun toast(message: String) {
                ReceiverDiagnostics.benchmark("ui.notice") { "type=toast" }
                toast = message
            }
        }
    }

    SideEffect {
        shell.remoteShortcutsAllowed = currentPath != Route.Settings.PATTERN && !railOpen && preparing == null
    }
    DisposableEffect(nav) {
        shell.remoteShortcut = { action ->
            when (action) {
                com.fourseveneightnine.tv.ui.RemoteButtonAction.Navigation -> nav.openRail()
                com.fourseveneightnine.tv.ui.RemoteButtonAction.Search -> nav.openSearch()
                com.fourseveneightnine.tv.ui.RemoteButtonAction.Settings -> nav.openSettings("playback")
                else -> Unit
            }
        }
        onDispose { shell.remoteShortcut = null; shell.remoteShortcutsAllowed = false }
    }

    val pip = appContext.appGraph.pictureInPicture
    val navigationFocus = remember { FocusRequester() }
    val pictureMenuFocus = remember { FocusRequester() }
    val moveHintFocus = remember { FocusRequester() }
    var savedNavigationFocus by remember { mutableStateOf(false) }
    var railBeforePicture by remember { mutableStateOf(false) }
    var pictureMenuOpen by remember { mutableStateOf(false) }
    var moveFromCorner by remember { mutableStateOf<Int?>(null) }
    val pictureVisible = !shell.playerVisible && pip.browseOnly && !pip.pictureHidden && !railOpen && preparing == null

    fun finishMove(save: Boolean) {
        val original = moveFromCorner ?: return
        pip.place(if (save) pip.corner else original)
        moveFromCorner = null
        pictureMenuOpen = true
    }

    fun returnToPlayer() = pip.expandBrowse { nav.openPlayer() }
    LaunchedEffect(pip.openPinnedPlayer) {
        if (pip.consumeOpenPinnedPlayer()) returnToPlayer()
    }
    LaunchedEffect(pictureVisible) {
        if (!pictureVisible) {
            pictureMenuOpen = false
            moveFromCorner?.let { pip.place(it, save = false) }
            moveFromCorner = null
        }
    }
    fun leavePictureControls() {
        pictureMenuOpen = false
        moveFromCorner?.let { pip.place(it, save = false) }
        moveFromCorner = null
        railOpen = railBeforePicture
        railBeforePicture = false
        val restoreSaved = savedNavigationFocus
        savedNavigationFocus = false
        railScope.launch {
            withFrameNanos { }
            if (pictureMenuOpen || shell.playerVisible) return@launch
            val restored = restoreSaved &&
                runCatching { navigationFocus.restoreFocusedChild() }.getOrDefault(false)
            if (!restored && !railOpen) {
                val restore = shell.restoreContentFocus
                if (restore != null) restore()
                else focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Previous)
            }
        }
    }

    LaunchedEffect(pip.pictureHidden) {
        if (pip.pictureHidden && (savedNavigationFocus || railBeforePicture || pictureMenuOpen ||
                moveFromCorner != null || !anythingFocused)) leavePictureControls()
    }
    LaunchedEffect(pip.browseOnly) {
        if (!pip.browseOnly) {
            savedNavigationFocus = false
            railBeforePicture = false
        }
    }
    SideEffect {
        shell.returnToPinnedVideo = ::returnToPlayer
        shell.togglePinnedControls = {
            // The pin is committed before the browse view replaces the player.
            if (!pip.browseOnly || preparing != null) false
            else {
                if (!pip.pictureHidden) {
                    if (pictureMenuOpen || moveFromCorner != null) leavePictureControls()
                    else {
                        railBeforePicture = railOpen
                        savedNavigationFocus = runCatching { navigationFocus.saveFocusedChild() }.getOrDefault(false)
                        railOpen = false
                        pictureMenuOpen = true
                    }
                }
                true
            }
        }
    }
    DisposableEffect(shell) {
        onDispose { shell.returnToPinnedVideo = null; shell.togglePinnedControls = null }
    }
    fun handleTopLevelBack() {
        if (railOpen) { closeRail(); return }
        val now = SystemClock.uptimeMillis()
        val atHome = navController.currentDestination?.route == Route.Home.path
        if (atHome && exitArmedAt != 0L && now - exitArmedAt <= DOUBLE_BACK_MILLIS) {
            disarmExit()
            onExit()
            return
        }

        if (atHome) exitArmedAt = now else {
            disarmExit()
            nav.openHome()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key != Key.Back) disarmExit()
                if (moveFromCorner == null) false
                else when (event.key) {
                    Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown -> {
                        if (event.type == KeyEventType.KeyDown) {
                            pip.place(pictureCornerAfterKey(pip.corner, event.nativeKeyEvent.keyCode), save = false)
                        }
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (event.type == KeyEventType.KeyUp) finishMove(save = true)
                        true
                    }
                    else -> false
                }
            }
            .focusRequester(navigationFocus)
            .onFocusChanged { anythingFocused = it.hasFocus }
            .focusGroup(),
    ) {
        // Each page draws its rail in the same navigation layer. A newly published back-stack
        // entry must never place the rail over the outgoing detail page.
        val navigationRail: @Composable (TopLevel) -> Unit = { displayedTopLevel ->
            NavRail(
                current = displayedTopLevel,
                expanded = railOpen,
                glassArtwork = { shell.homeGlassArtwork },
                profileName = activeProfile?.name ?: "Owner",
                profileAvatarKey = activeProfile?.avatarKey ?: "owner",
                castStatus = castStatus(
                    configured = configured,
                    receiverName = host.receiverName,
                    receiverReady = receiverState is ReceiverState.Ready,
                    castingTitle = if (origin == PlaybackSession.Origin.Phone) {
                        session.lastOpenMedia()?.title
                    } else {
                        null
                    },
                    playing = phase is ReceiverPlaybackPhase.Playing,
                ),
                onSelect = { item ->
                    val bundledLists = offlineCollections == true && item == TopLevel.Collections
                    if (!configured && !bundledLists && item !in setOf(TopLevel.Home, TopLevel.Search, TopLevel.LiveTv, TopLevel.Settings)) {
                        toast = "Add a source in Settings → Add-ons to open ${item.label}."
                        closeRail()
                    } else if (item == TopLevel.Home) {
                        nav.openHome()
                        closeRail()
                    } else if (item == displayedTopLevel) {
                        closeRail()
                    } else {
                        railOpen = false
                        navController.navigateTop(item.route.path)
                        if (item == TopLevel.Search) {
                            navController.currentBackStackEntry?.savedStateHandle?.set("searchEditOnEntry", true)
                        }
                    }
                },
                onClose = ::closeRail,
                onProfileChipClick = {
                    railOpen = false
                    if (appContext.clientGraph.localPlaybackActive()) {
                        toast = "Finish or stop the current title before switching profiles."
                        shell.restoreContentFocus?.invoke()
                    } else {
                        appContext.clientGraph.lockProfile()
                    }
                },
                onCastChipClick = {
                    railOpen = false
                    if (phase is ReceiverPlaybackPhase.Playing) {
                        navController.navigate(Route.Player.path) { launchSingleTop = true }
                    } else {
                        navController.navigateTop(Route.Settings().path)
                    }
                },
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }

        val pictureCutout = if (pip.browseOnly && !pip.pictureHidden && !shell.playerVisible) {
            Modifier.cornerPictureCutout(pip.corner, pip.size.widthFraction)
        } else Modifier
        NavHost(
            navController = navController,
            startDestination = Route.Home.path,
            modifier = Modifier.fillMaxSize().then(pictureCutout)
                .benchmarkDraw("nav_host_${currentPath?.substringBefore('/') ?: "pending"}"),
            // Main tabs use the rail reveal. Detail animates only its foreground, so the
            // outgoing catalog and incoming backdrop do not compete during entry.
            enterTransition = {
                val destination = targetState.destination.route
                if (reduceMotion || destination == Route.Player.path || destination == Route.Detail.PATTERN ||
                    TopLevel.forPath(destination) != null
                )
                    EnterTransition.None
                else slideInHorizontally(tween(TvMotion.ContentMillis, easing = TvMotion.Out)) { it / 64 }
            },
            exitTransition = {
                val destination = targetState.destination.route
                if (reduceMotion || destination == Route.Player.path || destination == Route.Detail.PATTERN ||
                    TopLevel.forPath(destination) != null
                )
                    ExitTransition.None
                else ExitTransition.KeepUntilTransitionsFinished
            },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = {
                val destination = initialState.destination.route
                when {
                    reduceMotion -> ExitTransition.None
                    destination == Route.Detail.PATTERN && isCollectionRoute(targetState.destination.route) ->
                        fadeOut(tween(TvMotion.ContentMillis, easing = TvMotion.Out))
                    destination == Route.Player.path || destination == Route.Detail.PATTERN ||
                        TopLevel.forPath(destination) != null -> ExitTransition.None
                    else -> slideOutHorizontally(tween(TvMotion.RouteExitMillis, easing = TvMotion.Out)) { it / 64 }
                }
            },
        ) {
            // Destination handlers register after NavHost's default pop handler, but before each
            // page's own overlay handler. BACK closes the deepest layer, then returns Home.
            composable(Route.Home.path) { }
            composable(Route.LiveTv.path) {
                BackHandler { handleTopLevelBack() }
                Box(Modifier.fillMaxSize()) { LiveTvScreen(nav); navigationRail(TopLevel.LiveTv) }
            }
            composable(Route.LiveMultiview.PATTERN,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                LiveMultiviewScreen(entry.arguments?.getString("id").orEmpty(), nav)
            }
            composable(Route.Discover.path) { }
            composable(Route.Collections.path) { }
            composable(Route.Search.path) { entry ->
                val query by entry.savedStateHandle.getStateFlow("searchQuery", "").collectAsState()
                val version by entry.savedStateHandle.getStateFlow("searchQueryVersion", 0L).collectAsState()
                val fromDetail by entry.savedStateHandle.getStateFlow("searchFromDetail", false).collectAsState()
                val editOnEntry by entry.savedStateHandle.getStateFlow("searchEditOnEntry", false).collectAsState()
                BackHandler(enabled = backStackEntry?.id == entry.id) {
                    if (railOpen || !fromDetail || !navController.popBackStack()) handleTopLevelBack()
                }
                Box(Modifier.fillMaxSize()) {
                    SearchScreen(nav = nav, initialQuery = query, initialQueryKey = version, editOnEntry = editOnEntry)
                    navigationRail(TopLevel.Search)
                }
            }
            composable(Route.Calendar.path) {
                BackHandler { handleTopLevelBack() }
                Box(Modifier.fillMaxSize()) { CalendarScreen(nav); navigationRail(TopLevel.Calendar) }
            }
            composable(
                Route.Detail.PATTERN,
                arguments = listOf(navArgument("type") { type = NavType.StringType }, navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                val parent = navController.previousBackStackEntry
                BackHandler(enabled = backStackEntry?.id == entry.id && isCollectionRoute(parent?.destination?.route)) {
                    if (parent != null && collectionReturn?.detail != entry.id) {
                        focusedCollection = null
                        collectionReturn = CollectionReturn(entry.id, parent.id)
                    }
                }
                val season by entry.savedStateHandle.getStateFlow("detailSeason", -1).collectAsState()
                val episode by entry.savedStateHandle.getStateFlow("detailEpisode", -1).collectAsState()
                Box(Modifier.fillMaxSize()
                    .focusProperties { onEnter = { if (collectionReturn?.detail == entry.id) cancelFocusChange() } }
                    .focusGroup(),
                ) {
                    DetailScreen(
                        type = entry.arguments?.getString("type").orEmpty(),
                        id = entry.arguments?.getString("id").orEmpty(),
                        nav = nav,
                        posterHandoff = shell.posterHandoff?.takeIf { it.detailEntry == entry.id },
                        initialSeason = season.takeIf { it >= 0 && episode > 0 },
                        initialEpisode = episode.takeIf { it > 0 && season >= 0 },
                        restoredTitle = entry.savedStateHandle.get<String>("detailPreviewTitle"),
                        restoredPosterLookupId = entry.savedStateHandle.get<String>("detailPreviewPosterLookupId"),
                    )
                }
            }
            composable(
                Route.Streams.PATTERN,
                arguments = listOf(
                    navArgument("type") { type = NavType.StringType },
                    navArgument("id") { type = NavType.StringType },
                    navArgument("season") { type = NavType.IntType; defaultValue = -1 },
                    navArgument("episode") { type = NavType.IntType; defaultValue = -1 },
                ),
            ) { entry ->
                val season = entry.arguments?.getInt("season")?.takeIf { it >= 0 }
                val episode = entry.arguments?.getInt("episode")?.takeIf { it >= 0 }
                StreamsScreen(
                    type = entry.arguments?.getString("type").orEmpty(),
                    id = entry.arguments?.getString("id").orEmpty(),
                    season = season,
                    episode = episode,
                    nav = nav,
                )
            }
            composable(
                Route.CollectionDetail.PATTERN,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { }
            composable(
                Route.CollectionEditor.PATTERN,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                CollectionEditorScreen(
                    id = entry.arguments?.getString("id")?.takeIf { it != Route.CollectionEditor.NEW },
                    nav = nav,
                )
            }
            composable(
                Route.Settings.PATTERN,
                arguments = listOf(
                    navArgument("page") {
                        type = NavType.StringType
                        defaultValue = Route.Settings.DEFAULT_PAGE
                    },
                ),
            ) { entry ->
                val welcome by entry.savedStateHandle.getStateFlow(TV_SETUP_OFFERED, false).collectAsState()
                BackHandler { if (welcome && !railOpen) nav.openHome() else handleTopLevelBack() }
                Box(Modifier.fillMaxSize()) {
                    SettingsScreen(
                        page = entry.arguments?.getString("page") ?: Route.Settings.DEFAULT_PAGE,
                        nav = nav,
                        autoFrameRate = { host.autoFrameRate },
                        onAutoFrameRate = { host.autoFrameRate = it },
                        welcome = welcome,
                    )
                    navigationRail(TopLevel.Settings)
                }
            }
            // An older task may restore the former setup destination after an APK upgrade.
            composable(Route.PairSync.path) {
                LaunchedEffect(Unit) {
                    navController.navigate(Route.Home.path) {
                        popUpTo(Route.PairSync.path) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }
            composable(Route.Player.path) {
                PlayerScreen(
                    session = session,
                    onResizeMode = onResizeMode,
                    onControlBarVisible = onControlBarVisible,
                    onLocalControl = host::notifyLocalControl,
                    onLeave = {
                        val fromIptv = LiveTvLaunch.consumePlayback()
                        // The player was pushed over the screen that opened it. Return to that
                        // exact screen, including a category or source list, instead of jumping
                        // to a top-level Home/Live TV destination. The fallback covers a player
                        // restored without a previous entry after process recreation.
                        if (!navController.popBackStack()) {
                            navController.navigateTop(if (fromIptv) Route.LiveTv.path else Route.Home.path)
                        }
                    },
                    onBrowseWithLive = nav::openHome,
                    onMultiview = { id ->
                        navController.navigate(Route.LiveMultiview(id).path) {
                            popUpTo(Route.Player.path) { inclusive = true }
                        }
                    },
                    onStopped = {
                        LiveTvLaunch.clearPlayback()
                        val id = session.resumeLiveChannelId
                        if (id == null) nav.openHome() else {
                            LiveTvLaunch.stage("resume-channel", id)
                            nav.openLiveTv()
                        }
                    },
                )
            }
        }

        collectionEntries.forEach { entry -> key(entry.id) {
            val active = backStackEntry?.id == entry.id
            val returning = collectionReturn?.let {
                it.collection == entry.id && (active || backStackEntry?.id == it.detail)
            } == true
            val placed = active || returning
            BrowseEntryState(entry, browseState) {
                // Compose after NavHost for Back priority. Draw below it so Detail can fade
                // off the ready catalog without exposing the directory or the video layer.
                Box(
                    Modifier.fillMaxSize().zIndex(-1f).then(pictureCutout)
                        .layout { measurable, constraints ->
                            val catalog = measurable.measure(constraints)
                            layout(catalog.width, catalog.height) { if (placed) catalog.placeRelative(0, 0) }
                        }
                        .benchmarkDraw(
                            if (returning && !active) "collection_return_underlay"
                            else if (entry.destination.route == Route.Collections.path) "collections" else "collection",
                            active = placed,
                        )
                        .then(if (entry.destination.route == Route.CollectionDetail.PATTERN) {
                            Modifier.onFocusChanged {
                                if (it.hasFocus) focusedCollection = entry.id
                                else if (focusedCollection == entry.id) focusedCollection = null
                            }
                        } else Modifier)
                        .focusProperties { onEnter = { if (!placed) cancelFocusChange() } }
                        .focusGroup()
                        .then(if (active) Modifier else Modifier.clearAndSetSemantics {}),
                ) {
                    if (entry.destination.route == Route.Collections.path) {
                        BackHandler(enabled = active) { if (railOpen) closeRail() else handleTopLevelBack() }
                        CollectionsScreen(nav, active = active, restoringPoster = returning,
                            onPosterRestored = { focusedCollection = entry.id })
                        if (active && !shell.overlayVisible) navigationRail(TopLevel.Collections)
                    } else {
                        val watchlistType by entry.savedStateHandle
                            .getStateFlow<String?>("watchlistMediaType", null).collectAsState()
                        CollectionDetailScreen(
                            id = entry.arguments?.getString("id").orEmpty(),
                            nav = nav,
                            initialWatchlistType = watchlistType,
                            active = active,
                            placed = placed,
                        )
                    }
                }
            }
        } }

        if (browseEntry != null) key(browseEntry.id) {
            val visible = activeBrowse != null
            BrowseEntryState(browseEntry, browseState) {
                BackHandler(enabled = visible) { handleTopLevelBack() }
                // Keep the measured browse nodes. Unplaced content does not draw or join focus search.
                Box(
                    Modifier.fillMaxSize().then(pictureCutout)
                        .layout { measurable, constraints ->
                            val shelf = measurable.measure(constraints)
                            layout(shelf.width, shelf.height) { if (visible) shelf.placeRelative(0, 0) }
                        }
                        .focusProperties { onEnter = { if (!visible) cancelFocusChange() } }
                        .focusGroup(),
                ) {
                    if (browseEntry.destination.route == Route.Home.path) {
                        HomeScreen(nav, explicitEntry = homeEntry, visible = visible)
                    } else {
                        DiscoverScreen(nav, openAllCatalogs = showAllCatalogs,
                            onAllCatalogsOpened = { showAllCatalogs = false }, visible = visible)
                    }
                    navigationRail(if (browseEntry.destination.route == Route.Home.path) TopLevel.Home else TopLevel.Discover)
                }
            }
        }

        if (pictureVisible && (pictureMenuOpen || moveFromCorner != null)) {
            BackHandler {
                if (moveFromCorner != null) finishMove(save = false)
                else leavePictureControls()
            }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val margin = maxWidth / 32f
                val nextToPicture = margin + maxWidth * pip.size.widthFraction + 16.dp
                val left = pip.corner in 1..2
                val bottom = pip.corner >= 2
                val alignment = when {
                    left && bottom -> Alignment.BottomStart
                    left -> Alignment.TopStart
                    bottom -> Alignment.BottomEnd
                    else -> Alignment.TopEnd
                }
                Column(
                    Modifier.align(alignment)
                        .padding(
                            start = if (left) nextToPicture else 0.dp,
                            end = if (left) 0.dp else nextToPicture,
                            top = margin,
                            bottom = margin,
                        )
                        .width(340.dp)
                        .tvGlassPanel(20.dp, opaqueBase = true)
                        .padding(12.dp)
                        .focusProperties { this.onExit = { cancelFocusChange() } }
                        .focusGroup(),
                ) {
                    if (moveFromCorner != null) {
                        Text(
                            "Arrows move · OK saves",
                            style = TvType.ControlLabel,
                            color = TvColor.TextPrimary,
                            modifier = Modifier.focusRequester(moveHintFocus).focusable().padding(12.dp),
                        )
                    } else {
                        Text("Picture in picture", style = TvType.ControlLabel, color = TvColor.TextPrimary,
                            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp))
                        PictureSize.entries.forEach { size ->
                            SidePanelRow(size.label, size == pip.size, { pip.resize(size) },
                                if (size == pip.size) Modifier.focusRequester(pictureMenuFocus) else Modifier,
                                width = 316.dp, height = 48.dp)
                        }
                        SidePanelRow("Move picture", false, {
                            moveFromCorner = pip.corner
                            pictureMenuOpen = false
                        }, width = 316.dp, height = 48.dp)
                        SidePanelRow("Go to video", false, ::returnToPlayer, width = 316.dp, height = 48.dp)
                        if (remember(pip) { pip.supportsCrossApp() }) {
                            if (!pip.canFloatOverApps()) SidePanelRow("Allow PiP over other apps", false, {
                                leavePictureControls()
                                onOpenPictureSettings()
                            }, width = 316.dp, height = 48.dp)
                            else SidePanelRow("Browse X", false, {
                                leavePictureControls()
                                onBrowseX()
                            }, width = 316.dp, height = 48.dp)
                        }
                        SidePanelRow("Close picture", false, {
                            leavePictureControls()
                            pip.closeBrowse()
                        }, width = 316.dp, height = 48.dp)
                    }
                }
            }
            LaunchedEffect(pictureMenuOpen) {
                withFrameNanos { }
                (if (pictureMenuOpen) pictureMenuFocus else moveHintFocus).requestFocus()
            }
        }
        val displayedEntry = navController.currentBackStackEntry
        PosterReturnState(shell, displayedEntry?.id, reduceMotion, TopLevel.forPath(displayedEntry?.destination?.route) != null)



        (if (exitArmedAt != 0L) EXIT_HINT else toast)?.let {
            Toast(it, modifier = Modifier.align(Alignment.TopStart).offset(x = 640.dp, y = 900.dp))
        }
        // NavHost can still be drawing the collection after publishing the player entry.
        val enteringPlayer = origin == PlaybackSession.Origin.Local && playerCoverEntryId != null &&
            playerCoverEntryId == navController.currentBackStackEntry?.id
        val loadingRequest = when {
            shell.playerVisible -> null
            enteringPlayer -> localPlayFlow.current.collectAsState().value
            currentPath == Route.Detail.PATTERN || currentPath == Route.Streams.PATTERN ||
                currentPath == Route.Player.path -> null
            else -> preparing
        }
        loadingRequest?.let { request ->
            val services by appContext.clientGraph.services.collectAsState()
            val step by localPlayFlow.preparationLabel.collectAsState()
            val metadata = remember(request.type, request.id, services, step) {
                val saved = appContext.clientGraph.savedCatalogItem(request.type, request.id)
                val id = saved?.imdbId ?: saved?.tmdbId?.let { "tmdb:$it" } ?: request.id
                services?.meta?.peek(request.type, id.substringAfter("::"))
            }
            PlaybackLoadingSurface(
                request = request,
                art = NowPlayingArt(request.backdropUrl ?: metadata?.backdrop,
                    request.posterUrl ?: metadata?.poster, metadata?.logo),
                title = request.title,
                onCancel = if (enteringPlayer) null else fun() {
                    localPlayFlow.cancelPreparation()
                    railOpen = false
                    railScope.launch {
                        withFrameNanos { }
                        shell.restoreContentFocus?.invoke()
                    }
                },
            )
        }

    }

}

private data class CollectionReturn(val detail: String, val collection: String)

private fun isCollectionRoute(route: String?): Boolean =
    route == Route.Collections.path || route == Route.CollectionDetail.PATTERN

/** The parked shelf keeps the route's owners and its own saveable state. */
@Composable
private fun BrowseEntryState(
    entry: NavBackStackEntry,
    holder: SaveableStateHolder,
    content: @Composable () -> Unit,
) {
    val lifetime = viewModel(viewModelStoreOwner = entry) { BrowseEntryLifetime(entry.id) }
    SideEffect { lifetime.holder = WeakReference(holder) }
    CompositionLocalProvider(
        LocalViewModelStoreOwner provides entry,
        LocalLifecycleOwner provides entry,
        LocalSavedStateRegistryOwner provides entry,
    ) {
        holder.SaveableStateProvider(entry.id, content)
    }
}

private class BrowseEntryLifetime(private val key: String) : ViewModel() {
    var holder: WeakReference<SaveableStateHolder>? = null

    override fun onCleared() {
        holder?.get()?.removeState(key)
    }
}

/** Preserve the selected focus target without flying preview artwork over a finished page. */
@Composable
private fun PosterReturnState(shell: ShellState, entry: String?, reduceMotion: Boolean, onTopLevel: Boolean) {
    val flight = shell.posterHandoff ?: return
    LaunchedEffect(flight, entry, reduceMotion) {
        flight.visible = false
        flight.progress.snapTo(1f)
        if (entry == flight.shelfEntry) {
            // The retained shelf restores the selected poster on its next placed frame.
            repeat(2) { withFrameNanos { } }
            if (shell.posterHandoff === flight) shell.posterHandoff = null
        } else if (reduceMotion || (onTopLevel && entry != flight.detailEntry)) {
            shell.posterHandoff = null
        }
    }
}

/**
 * Top-level moves keep exactly one entry behind Home, so the system BACK stack cannot grow
 * sideways as a viewer walks the rail.
 *
 * NOT `popUpTo(start) { saveState = true }` with `restoreState = true`. That pair saves the popped
 * stack under the destination it popped to and restores it on the way back in — so popping the
 * player and then navigating to Home restored the player on top of Home, and the screen never
 * changed. Verified on the box: `nav.go to=home from=player` with the Stopped plate still up.
 */
private fun NavHostController.navigateTop(path: String) {
    if (currentDestination?.route == path) return
    ReceiverDiagnostics.record("nav.go", "to=$path from=${currentDestination?.route}")
    navigate(path) {
        launchSingleTop = true
        popUpTo(Route.Home.path) { inclusive = false }
    }
}

private fun castStatus(
    configured: Boolean,
    receiverName: String,
    receiverReady: Boolean,
    castingTitle: String?,
    playing: Boolean,
): CastStatus = when {
    !configured -> CastStatus.NoPhonePaired
    playing && castingTitle != null -> CastStatus.Casting(castingTitle)
    castingTitle != null -> CastStatus.Connected
    receiverReady -> CastStatus.Idle(receiverName)
    else -> CastStatus.Idle(receiverName)
}

private const val TV_SETUP_OFFERED = "tv-setup-offered"
private const val TOAST_LIFE_MILLIS = 2_600L
private const val DOUBLE_BACK_MILLIS = 2_500L
private const val EXIT_HINT = "Press Back again to exit 4789."

/** Long enough that a normal screen has placed its own focus, short enough not to be felt. */
private const val FOCUS_RESCUE_MILLIS = 900L
private const val FOCUS_RESCUE_RETRY_MILLIS = 300L
