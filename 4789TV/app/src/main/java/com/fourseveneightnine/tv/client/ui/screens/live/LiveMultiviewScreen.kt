package com.fourseveneightnine.tv.client.ui.screens.live

import com.fourseveneightnine.tv.client.ui.components.PanelTransition

import android.media.MediaCodecList
import android.os.Build
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.client.appGraph
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.iptv.IptvChannel
import com.fourseveneightnine.tv.client.iptv.IptvGroupRanker
import com.fourseveneightnine.tv.client.iptv.IptvIndex
import com.fourseveneightnine.tv.client.iptv.IptvRepository
import com.fourseveneightnine.tv.client.iptv.IptvState
import com.fourseveneightnine.tv.client.iptv.iptvPlaybackHeaders
import com.fourseveneightnine.tv.player.forLivePlayback
import com.fourseveneightnine.tv.client.iptv.IptvRecordingStatus
import com.fourseveneightnine.tv.client.ui.components.SidePanel
import com.fourseveneightnine.tv.client.ui.components.SidePanelRow
import com.fourseveneightnine.tv.client.ui.components.TvButton
import com.fourseveneightnine.tv.client.ui.components.TvFocusableCard
import com.fourseveneightnine.tv.client.ui.nav.ClientNav
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvGeom
import com.fourseveneightnine.tv.client.ui.theme.TvShape
import com.fourseveneightnine.tv.client.ui.theme.TvType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.math.roundToInt

/** Limit tiles to the TV's advertised hardware AVC decoder instances. */
internal fun tvMultiviewDecoderSlots(): Int = runCatching {
    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.asSequence()
        .filter { !it.isEncoder && if (Build.VERSION.SDK_INT >= 29) it.isHardwareAccelerated
            else !it.name.contains("google", true) && !it.name.contains("android", true) }
        .mapNotNull { codec -> runCatching {
            codec.getCapabilitiesForType("video/avc").maxSupportedInstances
        }.getOrNull() }
        .maxOrNull()?.coerceIn(1, 4) ?: 1
}.getOrDefault(1)

internal fun IptvState.multiviewCapacity(sourceId: String?, decoders: Int): Int {
    val source = sources.firstOrNull { it.id == sourceId && it.enabled } ?: return 0
    val limit = source.maxConnections ?: return 0
    val recordings = accounts.recordings.count { it.status == IptvRecordingStatus.RECORDING &&
        channels.any { channel -> channel.id == it.channelId && channel.sourceId == source.id } }
    return minOf(4, decoders, (limit - recordings).coerceAtLeast(0))
}

/** Up to four TV-owned live streams. Only the chosen tile is audible. */
@Composable
internal fun LiveMultiviewScreen(initialChannelId: String, nav: ClientNav) {
    val context = LocalContext.current
    val repo = remember(context) { context.clientGraph.iptv }
    val state by repo.state.collectAsState()
    val initialChannel = state.channels.firstOrNull { it.id == initialChannelId }
    val decoders = remember { tvMultiviewDecoderSlots() }
    val maxSlots = remember(state, initialChannel?.sourceId) { state.multiviewCapacity(initialChannel?.sourceId, decoders) }
    val allowed = remember(state, initialChannel?.sourceId) {
        state.channels.filter { it.sourceId == initialChannel?.sourceId && it.group !in state.accounts.hiddenGroups &&
            it.group !in state.accounts.lockedGroups }.mapTo(mutableSetOf()) { it.id }
    }
    val slots = remember { mutableStateListOf<IptvChannel>() }
    var activeAudioId by remember { mutableStateOf(initialChannelId) }
    var adding by remember { mutableStateOf(false) }
    val entryFocus = remember { FocusRequester() }
    var focusAfterEdit by remember { mutableStateOf(0) }
    var prepared by remember { mutableStateOf(false) }
    var prepareError by remember { mutableStateOf(false) }
    var prepareRetry by remember { mutableStateOf(0) }
    LaunchedEffect(maxSlots >= 2, prepareRetry) {
        if (maxSlots < 2) return@LaunchedEffect
        prepareError = false
        // Voice search can leave the Activity-owned player running behind browse screens.
        // Stop that stream before tiles consume provider slots or produce a second audio track.
        val stopped = context.appGraph.playback.stop()
        prepared = stopped.isSuccess
        prepareError = stopped.isFailure
    }
    LaunchedEffect(prepared, prepareError, maxSlots, focusAfterEdit, adding) {
        if (!adding) {
            withFrameNanos { }
            runCatching { entryFocus.requestFocus() }
        }
    }
    LaunchedEffect(initialChannelId, allowed, maxSlots) {
        slots.removeAll { it.id !in allowed }
        while (slots.size > maxSlots) slots.removeAt(slots.lastIndex)
        if (slots.isEmpty() && maxSlots >= 2 && initialChannelId in allowed) initialChannel?.let(slots::add)
        if (slots.none { it.id == activeAudioId }) activeAudioId = slots.firstOrNull()?.id.orEmpty()
    }
    BackHandler(enabled = adding) { adding = false }
    if (maxSlots < 2 || initialChannelId !in allowed) {
        Column(Modifier.fillMaxSize().background(TvColor.Background).padding(TvGeom.SafeLeft),
            verticalArrangement = Arrangement.Center) {
            Text("Multiview is unavailable for this source", style = TvType.ScreenTitle,
                color = TvColor.TextPrimary)
            Spacer(Modifier.height(24.dp))
            Text("It needs at least two provider streams and two TV video decoders.",
                style = TvType.Body, color = TvColor.TextSecondary)
            Spacer(Modifier.height(24.dp))
            TvButton("Back to Live TV", onClick = nav::back, modifier = Modifier.focusRequester(entryFocus))
        }
        return
    }
    if (!prepared) {
        Column(Modifier.fillMaxSize().background(TvColor.Background).padding(TvGeom.SafeLeft),
            verticalArrangement = Arrangement.Center) {
            Text(if (prepareError) "Couldn't stop the previous stream" else "Opening Multiview…",
                style = TvType.ScreenTitle, color = TvColor.TextPrimary)
            Spacer(Modifier.height(24.dp))
            if (prepareError) TvButton("Try again", onClick = { prepareRetry++ })
            TvButton("Back to Live TV", onClick = nav::back, modifier = Modifier.focusRequester(entryFocus))
        }
        return
    }
    Column(Modifier.fillMaxSize().background(TvColor.Background)
        .padding(horizontal = TvGeom.SafeLeft, vertical = TvGeom.SafeTop)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Multiview", style = TvType.ScreenTitle, color = TvColor.TextPrimary)
            Spacer(Modifier.width(24.dp))
            Text("${slots.size} / $maxSlots · OK for sound · hold OK to remove", style = TvType.Meta,
                color = TvColor.TextSecondary)
            Spacer(Modifier.weight(1f))
            if (slots.size == 3 && activeAudioId != slots.first().id) {
                TvButton("Enlarge sound tile", onClick = {
                    val index = slots.indexOfFirst { it.id == activeAudioId }
                    if (index > 0) slots.add(0, slots.removeAt(index))
                    focusAfterEdit++
                })
                Spacer(Modifier.width(12.dp))
            }
            if (slots.size < maxSlots) {
                TvButton("Add channel", onClick = { adding = true })
                Spacer(Modifier.width(12.dp))
            }
            TvButton("Back to Live TV", onClick = nav::back)
        }
        Spacer(Modifier.height(20.dp))
        Layout(modifier = Modifier.fillMaxSize(), content = {
            slots.forEachIndexed { index, channel ->
                key(channel.id) {
                    LiveVideoTile(channel, repo, audible = channel.id == activeAudioId,
                        modifier = if (index == 0) Modifier.focusRequester(entryFocus) else Modifier,
                        onChooseAudio = { activeAudioId = channel.id },
                        onRemove = {
                            if (slots.size == 1) nav.back() else {
                                slots.remove(channel)
                                if (activeAudioId == channel.id) activeAudioId = slots.first().id
                                focusAfterEdit++
                            }
                        })
                }
            }
        }) { children, constraints ->
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val gap = 12.dp.roundToPx()
            val caption = tileCaption.roundToPx()
            // All video rectangles keep the broadcast ratio. Captions sit below the picture.
            val three = children.size == 3
            val cols = if (children.size > 1) 2 else 1
            val rows = if (children.size >= 3) 2 else 1
            val smallVideoHeight = if (three)
                minOf(((width - gap) * 9.0 / 16 - caption - gap) / 3, (height - gap) / 2.0 - caption)
            else minOf((width - gap * (cols - 1)) / cols * 9.0 / 16, (height - gap * (rows - 1)) / rows - caption.toDouble())
            val smallH = smallVideoHeight.toInt().coerceAtLeast(1)
            val smallW = (smallH * 16.0 / 9).roundToInt()
            val largeH = if (three) 2 * smallH + caption + gap else smallH
            val largeW = (largeH * 16.0 / 9).roundToInt()
            val left = (width - largeW - if (cols == 2) gap + smallW else 0) / 2
            val top = (height - rows * (smallH + caption) - (rows - 1) * gap) / 2
            val tiles = children.mapIndexed { index, child ->
                val large = three && index == 0
                child.measure(Constraints.fixed(if (large) largeW else smallW, (if (large) largeH else smallH) + caption))
            }
            layout(width, height) {
                tiles.forEachIndexed { i, tile ->
                    val column = if (three && i > 0) 1 else i % cols
                    val row = if (three) (i - 1).coerceAtLeast(0) else i / cols
                    tile.place(left + column * (largeW + gap), top + row * (smallH + caption + gap))
                }
            }
        }
    }
    PanelTransition(adding) { LiveMultiviewPicker(state, initialChannel?.sourceId,
        onClose = { adding = false }, excluded = slots.mapTo(mutableSetOf()) { it.id }) { channel ->
        if (channel.id in allowed && slots.none { it.id == channel.id } && slots.size < maxSlots) {
            slots.add(channel)
            if (activeAudioId.isEmpty()) activeAudioId = channel.id
        }
        adding = false
    } }
}

private val tileCaption = 34.dp

@Composable
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
private fun LiveVideoTile(channel: IptvChannel, repo: IptvRepository, audible: Boolean,
                          modifier: Modifier, onChooseAudio: () -> Unit, onRemove: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val player = remember(channel.id) {
        val source = repo.state.value.sources.firstOrNull { it.id == channel.sourceId }
        val endpoint = (source?.host ?: channel.streamRef).toHttpUrlOrNull()
        val http = endpoint?.let { context.clientGraph.okHttp.forLivePlayback(it) } ?: context.clientGraph.okHttp
        val headers = iptvPlaybackHeaders(channel.headers)
        val agent = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
            ?: "VLC/3.0.21 LibVLC/3.0.21"
        val httpFactory = OkHttpDataSource.Factory(http).setUserAgent(agent)
            .setDefaultRequestProperties(headers)
        val buffers = DefaultLoadControl.Builder().setBufferDurationsMs(3_000, 12_000, 500, 1_500)
            .setTargetBufferBytes(8 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false).build()
        ExoPlayer.Builder(context).setLoadControl(buffers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory)).build().apply {
                volume = if (audible) 1f else 0f
                trackSelectionParameters = trackSelectionParameters.buildUpon().setMaxVideoSize(1280, 720).build()
            }
    }
    var failure by remember(channel.id) { mutableStateOf<String?>(null) }
    var loading by remember(channel.id) { mutableStateOf(true) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) { loading =
                lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                    playbackState != Player.STATE_IDLE && playbackState != Player.STATE_READY && playbackState != Player.STATE_ENDED }
            override fun onPlayerError(error: PlaybackException) {
                failure = "This stream could not play. Hold OK to remove it."
                loading = false
                player.stop()
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    DisposableEffect(player, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (player.mediaItemCount > 0 && failure == null) {
                    loading = true
                    player.prepare()
                    player.playWhenReady = true
                }
                Lifecycle.Event.ON_STOP -> {
                    player.playWhenReady = false
                    player.stop()
                    loading = false
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(player, lifecycleOwner) {
        val url = runCatching { repo.playUrl(channel) }.getOrNull()
        if (url == null) { failure = "Stream unavailable. Hold OK to remove it."; loading = false }
        else {
            player.setMediaItem(MediaItem.fromUri(url))
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                player.prepare()
                player.playWhenReady = true
            } else loading = false
        }
    }
    LaunchedEffect(player, audible) { player.volume = if (audible) 1f else 0f }
    TvFocusableCard(onClick = onChooseAudio, onLongClick = onRemove, focusRing = true,
        focusScale = 1f, modifier = modifier, longClickLabel = "Remove channel",
        accessibleLabel = "${channel.name}, ${if (audible) "sound on" else "muted"}, OK selects sound, hold OK removes tile") { focused ->
        Column(Modifier.fillMaxSize().clip(TvShape.CardProminent).background(Color.Black)) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                AndroidView(factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        isFocusable = false
                        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                        this.player = player
                    }
                }, update = { it.player = player }, modifier = Modifier.fillMaxSize())
                val message = failure ?: if (loading) "Opening ${channel.name}…" else null
                if (message != null) Text(message, style = TvType.Meta, color = TvColor.TextPrimary,
                    modifier = Modifier.background(TvColor.Canvas.copy(alpha = 0.85f)).padding(8.dp))
            }
            Row(Modifier.height(tileCaption).fillMaxWidth().background(if (focused) TvColor.Elevated2 else TvColor.Canvas)
                .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(channel.name, style = TvType.CardTitle, color = TvColor.TextPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (audible) Text("● Sound", style = TvType.Badge, color = TvColor.Selection)
            }
        }
    }
}

internal enum class PipCategory(val label: String) {
    Favourites("Favourites"), Telugu("Telugu TV"), Sports("Sports")
}

@Composable
internal fun PipChannelPicker(state: IptvState, category: PipCategory?, onCategory: (PipCategory) -> Unit,
                              onBack: () -> Unit, onPick: (IptvChannel) -> Unit) {
    var previous by remember { mutableStateOf(PipCategory.Favourites) }
    if (category == null) {
        val focus = remember { FocusRequester() }
        SidePanel("Add live channel", onBack, width = 780.dp) {
            PipCategory.entries.forEach { item ->
                SidePanelRow(item.label, false, onClick = { previous = item; onCategory(item) },
                    modifier = if (item == previous) Modifier.focusRequester(focus) else Modifier)
                Spacer(Modifier.height(8.dp))
            }
        }
        LaunchedEffect(Unit) { withFrameNanos { }; focus.requestFocus() }
    } else androidx.compose.runtime.key(category) {
        LiveMultiviewPicker(state, null, onBack, category.label, category, onPick = onPick)
    }
}

/** The named Telugu TV group wins within each source. Other providers can use ranked groups. */
internal fun pipCategoryChannels(state: IptvState, category: PipCategory, eligible: List<IptvChannel>): List<IptvChannel> {
    if (category == PipCategory.Favourites) return eligible.filter { it.id in state.accounts.favoriteIds }
    val groups = eligible.asSequence().map { it.group }.distinct().associateWith { name ->
        state.accounts.groupDecisions[name]?.bucket ?: IptvGroupRanker.fallback(name).bucket
    }
    if (category == PipCategory.Sports) {
        val sportsGroups = groups.filterValues { it == "SPORTS" || it == "CRICKET" }.keys
        return eligible.filter { it.group in sportsGroups }
    }
    val exact = groups.keys.filter { it.trim().replace(teluguGroupSeparators, " ").equals("Telugu TV", ignoreCase = true) }.toSet()
    val namedSources = eligible.asSequence().filter { it.group in exact }.map { it.sourceId }.toSet()
    return eligible.filter { if (it.sourceId in namedSources) it.group in exact else groups[it.group] == "TELUGU" }
}

private val teluguGroupSeparators = Regex("[^A-Za-z0-9]+")

@Composable
internal fun LiveMultiviewPicker(state: IptvState, sourceId: String?,
                                onClose: () -> Unit, title: String = "Add to Multiview",
                                category: PipCategory? = null, excluded: Set<String> = emptySet(), onPick: (IptvChannel) -> Unit) {
    var query by remember { mutableStateOf("") }
    var matches by remember(query) { mutableStateOf<List<IptvChannel>?>(null) }
    val rows = matches.orEmpty()
    val first = remember { FocusRequester() }
    val firstMatch = remember { FocusRequester() }
    var placedFocus by remember { mutableStateOf(false) }
    LaunchedEffect(matches, state.loading, state.catalogLoading) {
        if (!placedFocus && matches != null && query.isBlank() &&
            (rows.isNotEmpty() || (!state.loading && !state.catalogLoading))) {
            withFrameNanos { }
            placedFocus = (if (rows.isEmpty()) first else firstMatch).requestFocus()
        }
    }
    LaunchedEffect(query, state.catalog, state.accounts, category, excluded) {
        delay(240)
        matches = withContext(Dispatchers.Default) {
            val allowed = state.channels.filter { it.id !in excluded && (sourceId == null || it.sourceId == sourceId) &&
                it.group !in state.accounts.lockedGroups && it.group !in state.accounts.hiddenGroups }
            val channels = if (category == null) allowed else pipCategoryChannels(state, category, allowed)
            if (query.isBlank() && category != null) channels
            else if (query.isBlank()) allowed.filter { it.id in state.accounts.favoriteIds }.ifEmpty { allowed.take(30) }
            else IptvIndex.search(channels, state.catalog.programs, query, limit = 50,
                groupOrder = state.orderedGroups)
        }
    }
    SidePanel(title, onClose, width = 780.dp) {
        val emptyCatalog = matches != null && rows.isEmpty() && !state.loading && !state.catalogLoading && query.isBlank()
        // Opening from the player should focus a channel. Giving the editor transient focus
        // while results load can open the keyboard and consume the first Back press.
        if (emptyCatalog) {
            TvButton(if (category != null) "Choose category" else "Back to Multiview", onClick = onClose, modifier = Modifier.focusRequester(first))
        } else LiveFormField("Find a channel", query, modifier = Modifier.focusRequester(first).focusProperties {
            canFocus = placedFocus || emptyCatalog
        }, onChange = { query = it })
        Spacer(Modifier.height(20.dp))
        if (rows.isEmpty()) {
            val message = when {
                matches == null -> if (query.isBlank()) "Loading channels…" else "Searching…"
                query.isNotBlank() -> "No channels match this search."
                state.loading || state.catalogLoading -> "Loading channels…"
                category == PipCategory.Favourites -> "No favourite channels yet."
                category != null -> "No available channels in this category."
                else -> "No more available channels."
            }
            Text(message, style = TvType.Body, color = TvColor.TextSecondary)
        }
        androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows, key = IptvChannel::id) { channel ->
                SidePanelRow(channel.name, selected = false, modifier = if (rows.firstOrNull()?.id == channel.id)
                    Modifier.focusRequester(firstMatch) else Modifier, onClick = {
                    if (channel.group !in state.accounts.lockedGroups && channel.group !in state.accounts.hiddenGroups &&
                        state.channels.any { it.id == channel.id }) onPick(channel)
                })
            }
        }
    }
}
