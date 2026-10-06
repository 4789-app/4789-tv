package com.fourseveneightnine.tv.client.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fourseveneightnine.tv.client.AppGraph
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.playback.PlaybackSession
import com.fourseveneightnine.tv.client.playback.ReceiverHost
import com.fourseveneightnine.tv.client.playback.ReceiverState
import com.fourseveneightnine.tv.client.profiles.ProfileSessionState
import com.fourseveneightnine.tv.client.search.SystemSearchCommand
import com.fourseveneightnine.tv.client.ui.components.Toast
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
import com.fourseveneightnine.tv.client.ui.screens.player.PlayerScreen
import com.fourseveneightnine.tv.client.ui.screens.settings.FirstRunScreen
import com.fourseveneightnine.tv.client.ui.screens.settings.SettingsKeys
import com.fourseveneightnine.tv.client.ui.screens.settings.SettingsScreen
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvMotion
import com.fourseveneightnine.tv.client.ui.theme.LocalReduceMotion
import com.fourseveneightnine.tv.client.ui.theme.TvTheme
import com.fourseveneightnine.tv.player.ReceiverPlaybackPhase
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics
import com.fourseveneightnine.tv.ui.LocalNetworkAddress
import com.fourseveneightnine.tv.ui.VideoResizeMode
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
    onImportSettingsFile: () -> Unit,
    systemSearchCommands: Flow<SystemSearchCommand>,
    onExit: () -> Unit,
) {
    // Look settings live in the presentation preferences (Settings → Look). Re-read on every
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
            val standaloneIptv = remember(context) {
                context.getSharedPreferences("iptv-shell", android.content.Context.MODE_PRIVATE)
                    .getBoolean("open-live-tv", false)
            }
            val profileState by client.profileSession.state.collectAsState()
            val profileCatalog by client.profiles.collectAsState()
            val playbackOrigin by graph.playback.origin.collectAsState()
            val playbackPhase by graph.playback.phase.collectAsState()
            LaunchedEffect(playbackOrigin) {
                if (playbackOrigin == PlaybackSession.Origin.Phone) LiveTvLaunch.clearPlayback()
            }
            var profileGateEpoch by remember { mutableIntStateOf(0) }

            // Unlock is runtime-only. Backgrounding discards both the active repository pointer
            // and any PIN UI state; returning to a multi-profile household always crosses the gate.
            DisposableEffect(lifecycleOwner, configured) {
                val observer = LifecycleEventObserver { _, event ->
                    if (configured && event == Lifecycle.Event.ON_STOP) {
                        profileGateEpoch++
                        client.lockProfile()
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            if (!settingsLoaded) {
                Box(Modifier.fillMaxSize().background(TvColor.Canvas))
                return@CompositionLocalProvider
            }

            val phonePlaybackActive = playbackOrigin == PlaybackSession.Origin.Phone && when (playbackPhase) {
                is ReceiverPlaybackPhase.Idle,
                is ReceiverPlaybackPhase.Ended,
                is ReceiverPlaybackPhase.Stopped,
                is ReceiverPlaybackPhase.Error,
                -> false
                else -> true
            }
            if (configured && profileState !is ProfileSessionState.Unlocked && phonePlaybackActive) {
                // Casting is receiver-global. A locked household profile must never hide or block
                // a phone-started title, and this surface touches no personal library.
                PlayerScreen(
                    session = graph.playback,
                    artworkLoader = graph.artworkLoader,
                    receiverName = host.receiverName,
                    receiverAddress = null,
                    onResizeMode = onResizeMode,
                    onControlBarVisible = onControlBarVisible,
                    onLocalControl = host::notifyLocalControl,
                    onLeave = client::lockProfile,
                    onOpenSources = null,
                )
                return@CompositionLocalProvider
            }

            when {
                !configured -> key("pair-sync") {
                    Shell(
                        graph = graph,
                        host = host,
                        shell = shell,
                        navController = rememberNavController(),
                        configured = false,
                        standaloneIptv = standaloneIptv,
                        onResizeMode = onResizeMode,
                        onControlBarVisible = onControlBarVisible,
                        onImportSettingsFile = onImportSettingsFile,
                        systemSearchCommands = systemSearchCommands,
                        onExit = onExit,
                    )
                }

                profileState is ProfileSessionState.Unlocked -> {
                    val profileId = (profileState as ProfileSessionState.Unlocked).profileId
                    // A new controller per unlocked profile is the navigation reset: no Detail,
                    // Streams or Collection back-stack entry can cross the profile boundary.
                    key(profileId) {
                        Shell(
                            graph = graph,
                            host = host,
                            shell = shell,
                            navController = rememberNavController(),
                            configured = true,
                            standaloneIptv = false,
                            onResizeMode = onResizeMode,
                            onControlBarVisible = onControlBarVisible,
                            onImportSettingsFile = onImportSettingsFile,
                            systemSearchCommands = systemSearchCommands,
                            onExit = onExit,
                        )
                    }
                }

                profileCatalog != null -> key(profileGateEpoch) {
                    ProfileGate(
                        catalog = requireNotNull(profileCatalog),
                        onSelect = client::selectProfile,
                        onUnlock = client::unlockProfile,
                    )
                }

                else -> Box(Modifier.fillMaxSize().background(TvColor.Canvas))
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
    standaloneIptv: Boolean,
    onResizeMode: (VideoResizeMode) -> Unit,
    onControlBarVisible: (Boolean) -> Unit,
    onImportSettingsFile: () -> Unit,
    systemSearchCommands: Flow<SystemSearchCommand>,
    onExit: () -> Unit,
) {
    val reduceMotion = LocalReduceMotion.current
    val session = graph.playback
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentPath = backStackEntry?.destination?.route
    val topLevel = TopLevel.forPath(currentPath)
    // Settings is a rail screen (spec §1 and §14.2): its two-pane layout leaves the rail strip
    // free, so the rail is drawn there like everywhere else. First run is the exception — the
    // `PairSync` route is a drill-in with nowhere to go — and it is not a TopLevel at all.
    val showRail = topLevel != null

    var railOpen by remember { mutableStateOf(false) }
    var closingRail by remember { mutableStateOf(false) }
    val railScope = rememberCoroutineScope()
    var anythingFocused by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var searchSeed by remember { mutableStateOf(0L to "") }

    val receiverState by host.state.collectAsState()
    val origin by session.origin.collectAsState()
    val phase by session.phase.collectAsState()
    val profileCatalog by appContext.clientGraph.profiles.collectAsState()
    val activeProfileId = appContext.clientGraph.activeProfileId()
    val activeProfile = profileCatalog?.profiles?.firstOrNull { it.id == activeProfileId }
    var receiverAddress by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(receiverState) {
        receiverAddress = withContext(Dispatchers.Default) { LocalNetworkAddress.currentIPv4() }
    }

    shell.openRail = { if (!closingRail) railOpen = true }

    // The rail closes when the screen changes, always, even if focus was inside it (spec §1.8.7).
    LaunchedEffect(currentPath) { railOpen = false }

    // A phone `Player.Open` navigates here from wherever the viewer was, Settings included.
    LaunchedEffect(Unit) {
        session.openRequests.collect {
            if (navController.currentDestination?.route != Route.Player.path) {
                navController.navigate(Route.Player.path) { launchSingleTop = true }
            }
        }
    }

    LaunchedEffect(systemSearchCommands) {
        systemSearchCommands.collect { command ->
            // First run owns the screen until a settings document exists. Public suggestions may
            // still be visible in the launcher, but opening one cannot bypass Pair & Sync.
            if (!configured && !standaloneIptv) return@collect
            when (command) {
                is SystemSearchCommand.Query -> {
                    searchSeed = searchSeed.first + 1L to command.value
                    navController.navigateTop(Route.Search.path)
                }
                is SystemSearchCommand.Detail ->
                    if (configured) navController.navigate(Route.Detail(command.type, command.id).path)
            }
        }
    }

    LaunchedEffect(toast) {
        if (toast == null) return@LaunchedEffect
        delay(TOAST_LIFE_MILLIS)
        toast = null
    }

    // All top-level pages start content at x220. Shift by 188 so the x376 rail leaves 32 px air.
    val railShiftPx = with(LocalDensity.current) { 188.dp.toPx() }
    val contentShift = animateFloatAsState(
        targetValue = if (showRail && railOpen) railShiftPx else 0f,
        animationSpec = tween(if (reduceMotion) 0 else TvMotion.RailMillis, easing = TvMotion.Std),
        label = "railContentShift",
    )

    /**
     * The net, asserted after every navigation: something focusable must be on screen.
     *
     * Every hide in this app is correct on its own and depends on some later event putting focus
     * back. When that event never arrives the television shows neither video nor interface and the
     * D-pad dies, and the only cure a viewer finds is force-quitting. Rather than chase each path
     * and miss the next one, this fires only when the screen would otherwise have no focus, and
     * lands the viewer on the rail, which is always navigable.
     *
     * The player is exempt. A film with its chrome down is SUPPOSED to have no focus target: every
     * key it cares about arrives through `dispatchKeyEvent`, and BACK through its own handler.
     */
    LaunchedEffect(currentPath, anythingFocused, shell.playerVisible) {
        if (anythingFocused || shell.playerVisible) return@LaunchedEffect
        delay(FOCUS_RESCUE_MILLIS)
        if (anythingFocused || shell.playerVisible) return@LaunchedEffect
        // First ask the screen for its own focus target; the rail is the last resort.
        shell.restoreContentFocus?.invoke()
        delay(FOCUS_RESCUE_RETRY_MILLIS)
        if (anythingFocused || shell.playerVisible) return@LaunchedEffect
        ReceiverDiagnostics.record("overlay.rescued", "route=$currentPath phase=${session.phase.value}")
        railOpen = true
    }

    var showAllCatalogs by remember { mutableStateOf(false) }
    val nav = remember(navController) {
        object : ClientNav {
            override fun openRail() { if (!closingRail) railOpen = true }
            override fun back() { navController.popBackStack() }
            override fun openHome() = navController.navigateTop(
                if (configured) Route.Home.path else Route.LiveTv.path)
            override fun openLiveTv() = navController.navigateTop(Route.LiveTv.path)
            override fun openLiveItem(kind: String, id: String) {
                LiveTvLaunch.stage(kind, id)
                navController.navigateTop(Route.LiveTv.path)
            }
            override fun openMultiview(channelId: String) =
                navController.navigate(Route.LiveMultiview(channelId).path)
            override fun openDiscover() {
                if (configured) navController.navigateTop(Route.Discover.path)
                else toast = "Pair your iPhone to browse add-on catalogs."
            }
            override fun openAllCatalogs() {
                if (configured) { showAllCatalogs = true; navController.navigateTop(Route.Discover.path) }
                else toast = "Pair your iPhone to browse add-on catalogs."
            }
            override fun openDetail(type: String, id: String) {
                if (configured) navController.navigate(Route.Detail(type, id).path)
                else toast = "Pair your iPhone to open this title."
            }
            override fun openStreams(type: String, id: String, season: Int?, episode: Int?) {
                if (configured) navController.navigate(Route.Streams(type, id, season, episode).path)
                else toast = "Pair your iPhone to browse these sources."
            }
            override fun openCollection(id: String) {
                if (configured) navController.navigate(Route.CollectionDetail(id).path)
                else toast = "Pair your iPhone for collections."
            }
            override fun openCollectionEditor(id: String?) {
                if (configured) navController.navigate(Route.CollectionEditor(id).path)
                else toast = "Pair your iPhone for collections."
            }
            override fun openSettings(page: String) = navController.navigateTop(Route.Settings(page).path)
            override fun openPlayer() = navController.navigate(Route.Player.path) { launchSingleTop = true }
            override fun toast(message: String) { toast = message }
        }
    }

    fun closeRail() {
        // Focus properties update on the next composition. Keep the rail from reopening until
        // that update has landed and the page has had a chance to take focus back.
        closingRail = true
        railOpen = false
        railScope.launch {
            delay(120)
            shell.restoreContentFocus?.invoke()
            delay(120)
            closingRail = false
        }
    }

    fun handleTopLevelBack() {
        when {
            railOpen -> closeRail()
            else -> railOpen = true
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onFocusChanged { anythingFocused = it.hasFocus }
            .focusGroup(),
    ) {
        NavHost(
            navController = navController,
            // First run: an unconfigured receiver shows Pair & Sync and nothing else (spec §2.1).
            startDestination = when {
                configured -> Route.Home.path
                standaloneIptv -> Route.LiveTv.path
                else -> Route.PairSync.path
            },
            modifier = Modifier.fillMaxSize().graphicsLayer { translationX = contentShift.value },
            // The default whole-screen crossfade blends two full artwork-heavy routes while
            // Detail is loading. On this box that felt like a frozen remote. Keep one brief
            // incoming fade and release the outgoing route immediately.
            enterTransition = {
                if (reduceMotion) EnterTransition.None else fadeIn(tween(120))
            },
            exitTransition = { ExitTransition.None },
            popEnterTransition = {
                if (reduceMotion) EnterTransition.None else fadeIn(tween(120))
            },
            popExitTransition = { ExitTransition.None },
        ) {
            // Destination handlers register after NavHost's default pop handler, but before each
            // page's own overlay handler. This makes BACK close an overlay, then open/close rail.
            composable(Route.Home.path) { BackHandler { handleTopLevelBack() }; HomeScreen(nav) }
            composable(Route.LiveTv.path) { BackHandler { handleTopLevelBack() }; LiveTvScreen(nav) }
            composable(Route.LiveMultiview.PATTERN,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                LiveMultiviewScreen(entry.arguments?.getString("id").orEmpty(), nav)
            }
            composable(Route.Discover.path) {
                BackHandler { handleTopLevelBack() }
                DiscoverScreen(nav, openAllCatalogs = showAllCatalogs, onAllCatalogsOpened = { showAllCatalogs = false })
            }
            composable(Route.Collections.path) { BackHandler { handleTopLevelBack() }; CollectionsScreen(nav) }
            composable(Route.Search.path) {
                BackHandler { handleTopLevelBack() }
                SearchScreen(
                    nav = nav,
                    initialQuery = searchSeed.second,
                    initialQueryKey = searchSeed.first,
                )
            }
            composable(Route.Calendar.path) { BackHandler { handleTopLevelBack() }; CalendarScreen(nav) }
            composable(
                Route.Detail.PATTERN,
                arguments = listOf(navArgument("type") { type = NavType.StringType }, navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                DetailScreen(
                    type = entry.arguments?.getString("type").orEmpty(),
                    id = entry.arguments?.getString("id").orEmpty(),
                    nav = nav,
                )
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
            ) { entry -> CollectionDetailScreen(id = entry.arguments?.getString("id").orEmpty(), nav = nav) }
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
                BackHandler { handleTopLevelBack() }
                SettingsScreen(
                    page = entry.arguments?.getString("page") ?: Route.Settings.DEFAULT_PAGE,
                    nav = nav,
                    autoFrameRate = { host.autoFrameRate },
                    onAutoFrameRate = { host.autoFrameRate = it },
                    onImportFile = onImportSettingsFile,
                )
            }
            composable(Route.PairSync.path) {
                FirstRunScreen(nav = nav, onImportFile = onImportSettingsFile)
            }
            composable(Route.Player.path) {
                PlayerScreen(
                    session = session,
                    artworkLoader = graph.artworkLoader,
                    receiverName = host.receiverName,
                    receiverAddress = receiverAddress,
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
                    onOpenSources = {
                        // Only a title this box opened itself has a source list to show. A
                        // phone-driven cast has its sources on the phone.
                        val playing = appContext.clientGraph.playFlow.current.value
                        if (playing != null) nav.openStreams(playing.type, playing.id, playing.season, playing.episode)
                    },
                )
            }
        }

        if (showRail) {
            NavRail(
                current = topLevel,
                expanded = railOpen,
                onOpen = { if (!closingRail) railOpen = true },
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
                    railOpen = false
                    if (!configured && item !in setOf(TopLevel.Search, TopLevel.LiveTv, TopLevel.Settings)) {
                        toast = "Pair your iPhone to open ${item.label}."
                        shell.restoreContentFocus?.invoke()
                    } else if (item != topLevel) navController.navigateTop(item.route.path)
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

        toast?.let {
            Toast(it, modifier = Modifier.align(Alignment.TopStart).offset(x = 640.dp, y = 900.dp))
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

private const val TOAST_LIFE_MILLIS = 2_600L

/** Long enough that a normal screen has placed its own focus, short enough not to be felt. */
private const val FOCUS_RESCUE_MILLIS = 900L
private const val FOCUS_RESCUE_RETRY_MILLIS = 300L
