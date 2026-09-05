package com.fourseveneightnine.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fourseveneightnine.contract.DiscoverItem
import com.fourseveneightnine.contract.StreamEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val QUEUE_POLL_MILLIS = 1_500L

private sealed interface RemoteSourcesState {
    data object Loading : RemoteSourcesState
    data object NotConfigured : RemoteSourcesState
    data class Ready(val streams: List<StreamEntry>) : RemoteSourcesState
    data class Failed(val message: String) : RemoteSourcesState
}

@Composable
fun RemoteSourcesScreen(
    item: DiscoverItem?,
    onBack: () -> Unit,
    onPlay: (String) -> Unit,
    onOfflineChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val castStore = remember(context) { TvCastTargetStore(context) }
    var castTarget by remember(castStore) { mutableStateOf(castStore.load()) }
    val castDiscovery = remember(context) { TvCastDiscovery(context) }
    val castRepository = remember { TvCastRepository() }
    val downloadQueue = remember(context) { OfflineDownloadQueue(context) }
    var castStatus by remember { mutableStateOf<String?>(null) }
    var offlineStatus by remember { mutableStateOf<String?>(null) }
    var queuedJob by remember { mutableStateOf<OfflineDownloadJob?>(null) }
    // The viewer's ranking choices survive a rotation and a process death within this session.
    var sortOrder by rememberSaveable(stateSaver = enumStateSaver(StreamSortOrder.Recommended)) {
        mutableStateOf(StreamSortOrder.Recommended)
    }
    var ceiling by rememberSaveable(stateSaver = enumStateSaver(StreamQualityCeiling.Any)) {
        mutableStateOf(StreamQualityCeiling.Any)
    }
    var seederFloor by rememberSaveable(stateSaver = enumStateSaver(StreamSeederFloor.Any)) {
        mutableStateOf(StreamSeederFloor.Any)
    }
    var requireKnownSize by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(castDiscovery) {
        if (castTarget == null) {
            castDiscovery.start(
                onTarget = { target ->
                    if (castStore.save(target.host)) castTarget = target
                },
                onStatus = { castStatus = it },
            )
        }
        onDispose { castDiscovery.stop() }
    }
    // The worker owns the download. This screen only reads the record it keeps, so the percentage
    // stays true whether or not anyone is looking at it.
    LaunchedEffect(item?.id) {
        val titleID = item?.id ?: return@LaunchedEffect
        while (true) {
            queuedJob = downloadQueue.job(titleID)
            delay(QUEUE_POLL_MILLIS)
        }
    }
    val state by produceState<RemoteSourcesState>(RemoteSourcesState.Loading, item?.id) {
        val title = item ?: run {
            value = RemoteSourcesState.Failed("This title is unavailable.")
            return@produceState
        }
        when (val config = SecureAddonConfigStore(context).load()) {
            SecureAddonConfigState.Missing -> value = RemoteSourcesState.NotConfigured
            is SecureAddonConfigState.Unavailable -> {
                value = RemoteSourcesState.Failed("Secure source configuration is unavailable.")
            }
            is SecureAddonConfigState.Available -> {
                value = runCatching {
                    RemoteSourceRepository().load(config.manifestURL, title.type, title.id)
                }.fold(
                    onSuccess = { RemoteSourcesState.Ready(it) },
                    onFailure = { RemoteSourcesState.Failed("Sources could not be loaded. Try again.") },
                )
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Button(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") } }
        item {
            Text(item?.title ?: "Sources", style = MaterialTheme.typography.headlineMedium)
            Text("Play-now links are never saved.", style = MaterialTheme.typography.bodyMedium)
        }
        when (val current = state) {
            RemoteSourcesState.Loading -> item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                }
            }
            RemoteSourcesState.NotConfigured -> item {
                Text("Add your HTTPS Stremio manifest in Source configuration first.")
            }
            is RemoteSourcesState.Failed -> item { Text(current.message) }
            is RemoteSourcesState.Ready -> {
                val ranked = StreamRankingPolicy.rank(
                    streams = current.streams,
                    order = sortOrder,
                    filter = StreamFilter(
                        ceiling = ceiling,
                        requireKnownSize = requireKnownSize,
                        minimumSeeders = seederFloor.minimum,
                    ),
                )
                item {
                    SourceRankingControls(
                        sortOrder = sortOrder,
                        ceiling = ceiling,
                        seederFloor = seederFloor,
                        requireKnownSize = requireKnownSize,
                        onSortOrder = { sortOrder = it },
                        onCeiling = { ceiling = it },
                        onSeederFloor = { seederFloor = it },
                        onRequireKnownSize = { requireKnownSize = it },
                    )
                }
                when {
                    current.streams.isEmpty() ->
                        item { Text("No playable HTTPS sources were returned.") }
                    ranked.isEmpty() ->
                        item { Text("No sources match these filters.") }
                }
                items(ranked, key = { it.stream.url.orEmpty() }) { rankedStream ->
                    val active = queuedJob
                    val isThisRow = active != null &&
                        active.recipe.selectorHash ==
                        DurableStreamRecipePolicy.selectorHash(rankedStream.stream)
                    SourceRankingRow(
                        ranked = rankedStream,
                        canCast = item != null && castTarget != null,
                        queueStatus = active
                            ?.takeIf { isThisRow }
                            ?.let(OfflineDownloadQueuePolicy::statusLine),
                        onPlay = onPlay,
                        onKeepOffline = { entry ->
                            val title = item
                            val recipe = title?.let {
                                DurableStreamRecipePolicy.recipe(it.id, it.type, entry)
                            }
                            offlineStatus = when {
                                recipe == null ->
                                    "This source did not say enough about itself to download later."
                                downloadQueue.enqueue(recipe) -> {
                                    queuedJob = downloadQueue.job(recipe.titleID)
                                    onOfflineChanged()
                                    "Queued. It downloads in the background and continues where it " +
                                        "stopped. No source link is saved."
                                }
                                else -> "This download could not be queued."
                            }
                        },
                        onCast = { url ->
                            val title = item
                            val target = castTarget
                            if (title != null && target != null) {
                                castStatus = "Connecting to 4789 TV…"
                                scope.launch {
                                    castStatus = if (runCatching {
                                            castRepository.cast(target, title, url)
                                        }.getOrDefault(false)
                                ) {
                                    "Playing on 4789 TV. Open TV controls here to pause, seek, or stop."
                                    } else {
                                        "Could not reach 4789 TV. Check its address and that it is open."
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
        offlineStatus?.let { message -> item { Text(message) } }
        castStatus?.let { message -> item { Text(message) } }
        castTarget?.let { target ->
            item {
                TvRemoteControls(
                    onCommand = { operation ->
                        castStatus = "Sending TV command…"
                        scope.launch {
                            val success = runCatching { operation(castRepository, target) }
                                .getOrDefault(false)
                            castStatus = if (success) "TV command sent." else "TV command failed."
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun TvRemoteControls(
    onCommand: (suspend (TvCastRepository, TvCastTarget) -> Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("4789 TV controls", style = MaterialTheme.typography.titleMedium)
        Button(
            onClick = { onCommand { repository, target -> repository.playPause(target) } },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text("Play / Pause") }
        Button(
            onClick = { onCommand { repository, target -> repository.seek(target, forward = false) } },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text("Back 10 seconds") }
        Button(
            onClick = { onCommand { repository, target -> repository.seek(target, forward = true) } },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text("Forward 10 seconds") }
        Button(
            onClick = { onCommand { repository, target -> repository.stop(target) } },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text("Stop TV playback") }
    }
}

/**
 * Sort order, quality limit, swarm floor and the known-size toggle. All four only reorder or hide
 * rows; none of them changes a fact, and none of them hides a source that simply stayed silent.
 */
@Composable
private fun SourceRankingControls(
    sortOrder: StreamSortOrder,
    ceiling: StreamQualityCeiling,
    seederFloor: StreamSeederFloor,
    requireKnownSize: Boolean,
    onSortOrder: (StreamSortOrder) -> Unit,
    onCeiling: (StreamQualityCeiling) -> Unit,
    onSeederFloor: (StreamSeederFloor) -> Unit,
    onRequireKnownSize: (Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(StreamSortOrder.entries, key = { it.name }) { order ->
                FilterChip(
                    selected = sortOrder == order,
                    onClick = { onSortOrder(order) },
                    label = { Text(order.label) },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("source-sort-${order.name.lowercase()}"),
                )
            }
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(StreamQualityCeiling.entries, key = { it.name }) { limit ->
                FilterChip(
                    selected = ceiling == limit,
                    onClick = { onCeiling(limit) },
                    label = { Text(limit.label) },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("source-ceiling-${limit.name.lowercase()}"),
                )
            }
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(StreamSeederFloor.entries, key = { it.name }) { floor ->
                FilterChip(
                    selected = seederFloor == floor,
                    onClick = { onSeederFloor(floor) },
                    label = { Text(floor.label) },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("source-seeders-${floor.name.lowercase()}"),
                )
            }
        }
        FilterChip(
            selected = requireKnownSize,
            onClick = { onRequireKnownSize(!requireKnownSize) },
            label = { Text("Known size only") },
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("source-known-size"),
        )
    }
}

/**
 * Saves an enum choice by name, so an added or reordered case can never restore as a different one.
 * An unknown saved name falls back instead of failing.
 */
private inline fun <reified T : Enum<T>> enumStateSaver(fallback: T): Saver<T, String> = Saver(
    save = { it.name },
    restore = { saved -> enumValues<T>().firstOrNull { entry -> entry.name == saved } ?: fallback },
)

@Composable
fun AddonConfigurationScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { SecureAddonConfigStore(context) }
    val castStore = remember(context) { TvCastTargetStore(context) }
    var input by remember { mutableStateOf("") }
    var tvInput by remember { mutableStateOf("") }
    var status by remember {
        mutableStateOf(
            when (val saved = store.load()) {
                is SecureAddonConfigState.Available -> {
                    "Configured: ${AddonConfigurationPolicy.displayHost(saved.manifestURL)}"
                }
                SecureAddonConfigState.Missing -> "No remote source configured."
                is SecureAddonConfigState.Unavailable -> "Secure configuration is unavailable."
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Button(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") } }
        item { Text("Source configuration", style = MaterialTheme.typography.headlineMedium) }
        item {
            Text(
                "Paste an HTTPS Stremio manifest URL. It is encrypted with Android Keystore, " +
                    "excluded from backup, and never shown after saving.",
            )
        }
        item {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Manifest URL") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Button(
                onClick = {
                    status = if (store.save(input)) {
                        input = ""
                        "Source saved securely."
                    } else {
                        "Not saved. Use an HTTPS URL ending in /manifest.json without query credentials."
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text("Save source") }
        }
        item {
            Button(
                onClick = {
                    status = if (store.clear()) "Source removed." else "Source could not be removed."
                    input = ""
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text("Remove source") }
        }
        item { Text(status) }
        item { Text("4789 TV", style = MaterialTheme.typography.headlineSmall) }
        item {
            Text(
                "Enter the private IPv4 address shown by the 4789 TV receiver. The address stays " +
                    "on this device; resolved media links are still memory-only.",
            )
        }
        item {
            OutlinedTextField(
                value = tvInput,
                onValueChange = { tvInput = it },
                label = { Text("TV address") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Button(
                onClick = {
                    status = if (castStore.save(tvInput)) {
                        tvInput = ""
                        "4789 TV saved."
                    } else {
                        "TV not saved. Use a private IPv4 address such as 192.168.1.20."
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text("Save TV") }
        }
        item {
            Button(
                onClick = {
                    status = if (castStore.clear()) "4789 TV removed." else "TV could not be removed."
                    tvInput = ""
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text("Remove TV") }
        }
        castStore.load()?.let { target -> item { Text("TV configured: ${target.host}") } }
    }
}
