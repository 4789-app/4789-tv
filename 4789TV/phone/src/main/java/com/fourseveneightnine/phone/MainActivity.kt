package com.fourseveneightnine.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.fourseveneightnine.contract.DiscoverItem
import com.fourseveneightnine.contract.PhoneDoor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val DETAIL_QUEUE_POLL_MILLIS = 1_500L

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { FourSevenEightNineTheme { FourSevenEightNinePhone() } }
    }
}

private data class PhoneUiRoute(
    val playbackURL: String?,
    val sourcesID: String?,
    val detailID: String?,
    val configuringSource: Boolean,
    val showingSettings: Boolean,
    val door: PhoneDoor,
)

@Composable
fun FourSevenEightNinePhone() {
    var door by rememberSaveable { mutableStateOf(PhoneDoor.Discover) }
    var selectedID by rememberSaveable { mutableStateOf<String?>(null) }
    var sourcesID by rememberSaveable { mutableStateOf<String?>(null) }
    var configuringSource by rememberSaveable { mutableStateOf(false) }
    var showingSettings by rememberSaveable { mutableStateOf(false) }
    var catalogSearchQuery by rememberSaveable { mutableStateOf("") }
    var catalogMediaFilter by rememberSaveable { mutableStateOf(CatalogMediaFilter.All) }
    // Local document capabilities and resolved remote URLs are play-now state. Neither survives
    // process death; only title-owned position/duration is durable.
    var playbackURL by remember { mutableStateOf<String?>(null) }
    var importingOfflineTitleID by remember { mutableStateOf<String?>(null) }
    var offlineGeneration by remember { mutableStateOf(0) }
    var offlineStatus by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloadQueue = remember(context) { OfflineDownloadQueue(context) }
    // A half-written file whose download is still queued is a head start, not rubbish.
    val offlineStore = remember(context) {
        OfflineMediaStore(context).also { it.cleanInterruptedImports(downloadQueue.unfinishedTitleIDs()) }
    }
    val localPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            playbackURL = uri.toString()
        }
    }
    val offlinePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val titleID = importingOfflineTitleID
        importingOfflineTitleID = null
        if (uri != null && titleID != null) {
            offlineStatus = "Saving an app-private offline copy…"
            scope.launch {
                offlineStatus = if (offlineStore.import(titleID, uri)) {
                    offlineGeneration += 1
                    "Offline copy saved."
                } else {
                    "The offline copy could not be saved. Check free space and try again."
                }
            }
        }
    }
    val reducedMotion = rememberReducedMotion()
    val catalog by produceState<CatalogLoadState>(initialValue = CatalogLoadState.Loading) {
        val bundled = CatalogSnapshotRepository.loadBundled(context)
        value = bundled
        CatalogSnapshotRepository.loadRemote(context)?.let { remote ->
            val bundledItems = (bundled as? CatalogLoadState.Ready)?.items.orEmpty()
            value = CatalogLoadState.Ready(
                CatalogSnapshotRepository.mergeRemoteWithBundled(remote, bundledItems),
                isLive = true,
            )
        }
    }
    LaunchedEffect(catalog, selectedID, sourcesID) {
        val items = (catalog as? CatalogLoadState.Ready)?.items ?: return@LaunchedEffect
        if (!PhoneRoutePolicy.hasTitle(items, selectedID)) {
            selectedID = null
            sourcesID = null
            playbackURL = null
        } else if (!PhoneRoutePolicy.hasTitle(items, sourcesID)) {
            sourcesID = null
            playbackURL = null
        }
    }

    BackHandler(
        enabled = playbackURL != null || sourcesID != null || configuringSource || showingSettings || selectedID != null,
    ) {
        when {
            playbackURL != null -> playbackURL = null
            sourcesID != null -> sourcesID = null
            configuringSource -> configuringSource = false
            showingSettings -> showingSettings = false
            else -> {
                offlineStatus = null
                selectedID = null
            }
        }
    }

    MaterialTheme {
        Scaffold(
            bottomBar = {
                if (
                    selectedID == null && playbackURL == null && sourcesID == null &&
                    !configuringSource && !showingSettings
                ) {
                    DoorSwitcher(
                        selected = door,
                        onSelect = {
                            selectedID = null
                            door = it
                        },
                    )
                }
            },
        ) { padding ->
            AnimatedContent(
                targetState = PhoneUiRoute(
                    playbackURL,
                    sourcesID,
                    selectedID,
                    configuringSource,
                    showingSettings,
                    door,
                ),
                transitionSpec = {
                    // Lengths come from Motion so that a phone asking for no animation gets none.
                    fadeIn(tween(Motion.screenIn(reducedMotion))) togetherWith
                        fadeOut(tween(Motion.screenOut(reducedMotion))) using
                        SizeTransform(clip = false)
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                label = "phone-route",
            ) { route ->
                val playbackURI = route.playbackURL
                val sourceTitleID = route.sourcesID
                val detailID = route.detailID
                val showConfiguration = route.configuringSource
                val showSettings = route.showingSettings
                val selectedDoor = route.door
                if (playbackURI != null) {
                    val playbackTitleID = detailID ?: return@AnimatedContent
                    PlaybackScreen(
                        uri = playbackURI,
                        titleID = playbackTitleID,
                        onClose = { playbackURL = null },
                    )
                } else if (sourceTitleID != null) {
                    val item = (catalog as? CatalogLoadState.Ready)
                        ?.items
                        ?.firstOrNull { it.id == sourceTitleID }
                    RemoteSourcesScreen(
                        item = item,
                        onBack = { sourcesID = null },
                        onPlay = { playbackURL = it },
                        onOfflineChanged = { offlineGeneration += 1 },
                    )
                } else if (showConfiguration) {
                    AddonConfigurationScreen(onBack = { configuringSource = false })
                } else if (showSettings) {
                    AppSettingsScreen(
                        onBack = { showingSettings = false },
                        onOpenSources = {
                            showingSettings = false
                            configuringSource = true
                        },
                    )
                } else if (detailID != null) {
                    val item = (catalog as? CatalogLoadState.Ready)
                        ?.items
                        ?.firstOrNull { it.id == detailID }
                    val resumePercent = LocalPlaybackProgressStore(context).load(detailID)
                        ?.let(LocalPlaybackProgressPolicy::progressPercent)
                    val offlineURI = remember(detailID, offlineGeneration) {
                        offlineStore.playbackUri(detailID)
                    }
                    var downloadJob by remember(detailID) {
                        mutableStateOf(downloadQueue.job(detailID))
                    }
                    // The download runs in its own worker. Reread its record so the drill-in shows
                    // real progress instead of whatever was true when the screen opened.
                    LaunchedEffect(detailID) {
                        while (true) {
                            val current = downloadQueue.job(detailID)
                            if (current?.state == OfflineDownloadState.Completed &&
                                downloadJob?.state != OfflineDownloadState.Completed
                            ) {
                                offlineGeneration += 1
                            }
                            downloadJob = current
                            delay(DETAIL_QUEUE_POLL_MILLIS)
                        }
                    }
                    DetailScreen(
                        item = item,
                        resumePercent = resumePercent,
                        offlineAvailable = offlineURI != null,
                        offlineStatus = offlineStatus,
                        downloadStatus = downloadJob?.let(OfflineDownloadQueuePolicy::statusLine),
                        onBack = {
                            offlineStatus = null
                            selectedID = null
                        },
                        onOpenLocal = { localPicker.launch(arrayOf("video/*")) },
                        onPlayOffline = { offlineURI?.let { playbackURL = it.toString() } },
                        onImportOffline = {
                            importingOfflineTitleID = detailID
                            offlineStatus = null
                            offlinePicker.launch(arrayOf("video/*"))
                        },
                        onRemoveOffline = {
                            offlineStatus = if (offlineStore.remove(detailID)) {
                                offlineGeneration += 1
                                "Offline copy removed."
                            } else {
                                "Offline copy could not be removed."
                            }
                        },
                        onOpenRemote = { sourcesID = detailID },
                        onConfigureSource = { configuringSource = true },
                    )
                } else {
                    DoorContent(
                        door = selectedDoor,
                        state = catalog,
                        searchQuery = catalogSearchQuery,
                        onSearchQueryChange = { catalogSearchQuery = it },
                        mediaFilter = catalogMediaFilter,
                        onMediaFilterChange = { catalogMediaFilter = it },
                        onSelect = { item: DiscoverItem -> selectedID = item.id },
                        onSettings = { showingSettings = true },
                    )
                }
            }
        }
    }
}
