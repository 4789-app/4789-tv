package com.fourseveneightnine.tv.client.ui.screens.live

import android.media.MediaCodecList
import android.os.Build
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.client.appGraph
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.iptv.IptvChannel
import com.fourseveneightnine.tv.client.iptv.IptvIndex
import com.fourseveneightnine.tv.client.iptv.IptvRepository
import com.fourseveneightnine.tv.client.iptv.IptvState
import com.fourseveneightnine.tv.client.iptv.iptvPlaybackHeaders
import com.fourseveneightnine.tv.player.FastPlaybackDns
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
import java.util.concurrent.TimeUnit

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

/** Up to four TV-owned live streams. Only the chosen tile is audible. */
@Composable
internal fun LiveMultiviewScreen(initialChannelId: String, nav: ClientNav) {
    val context = LocalContext.current
    val repo = remember(context) { context.clientGraph.iptv }
    val state by repo.state.collectAsState()
    val initialChannel = state.channels.firstOrNull { it.id == initialChannelId }
    val source = state.sources.firstOrNull { it.id == initialChannel?.sourceId }
    val maxSlots = minOf(4, source?.maxConnections ?: 0, remember { tvMultiviewDecoderSlots() })
    val slots = remember { mutableStateListOf<IptvChannel>() }
    var activeAudioId by remember { mutableStateOf(initialChannelId) }
    var adding by remember { mutableStateOf(false) }
    val entryFocus = remember { FocusRequester() }
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
    LaunchedEffect(prepared, prepareError, maxSlots) {
        withFrameNanos { }
        runCatching { entryFocus.requestFocus() }
    }
    LaunchedEffect(initialChannelId, state.catalog.channels) {
        if (slots.isEmpty()) state.channels.firstOrNull { it.id == initialChannelId }?.let(slots::add)
    }
    BackHandler(enabled = adding) { adding = false }
    if (maxSlots < 2) {
        Column(Modifier.fillMaxSize().background(TvColor.Canvas).padding(TvGeom.SafeLeft),
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
        Column(Modifier.fillMaxSize().background(TvColor.Canvas).padding(TvGeom.SafeLeft),
            verticalArrangement = Arrangement.Center) {
            Text(if (prepareError) "Couldn't stop the previous stream" else "Opening Multiview…",
                style = TvType.ScreenTitle, color = TvColor.TextPrimary)
            Spacer(Modifier.height(24.dp))
            if (prepareError) TvButton("Try again", onClick = { prepareRetry++ })
            TvButton("Back to Live TV", onClick = nav::back, modifier = Modifier.focusRequester(entryFocus))
        }
        return
    }
    Column(Modifier.fillMaxSize().background(TvColor.Canvas)
        .padding(horizontal = TvGeom.SafeLeft, vertical = TvGeom.SafeTop)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Multiview", style = TvType.ScreenTitle, color = TvColor.TextPrimary)
            Spacer(Modifier.width(24.dp))
            Text("Select a picture for its sound · hold OK to remove it", style = TvType.Meta,
                color = TvColor.TextSecondary)
            Spacer(Modifier.weight(1f))
            TvButton("Add channel", onClick = { adding = true }, enabled = slots.size < maxSlots)
            Spacer(Modifier.width(12.dp))
            TvButton("Back to Live TV", onClick = nav::back)
        }
        Spacer(Modifier.height(30.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            repeat(if (maxSlots <= 2) 1 else 2) { row ->
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    repeat(2) { column ->
                        val index = row * 2 + column
                        val channel = slots.getOrNull(index)
                        if (channel != null) {
                            LiveVideoTile(channel, repo, audible = channel.id == activeAudioId,
                                modifier = Modifier.weight(1f).fillMaxSize()
                                    .then(if (index == 0) Modifier.focusRequester(entryFocus) else Modifier),
                                onChooseAudio = { activeAudioId = channel.id },
                                onRemove = {
                                    slots.remove(channel)
                                    if (activeAudioId == channel.id) activeAudioId = slots.firstOrNull()?.id.orEmpty()
                                })
                        } else if (slots.size >= maxSlots) {
                            Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Stream limit reached", style = TvType.Meta, color = TvColor.TextSecondary)
                            }
                        } else {
                            TvFocusableCard(onClick = { adding = true }, focusRing = false,
                                focusScale = 1.015f, modifier = Modifier.weight(1f).fillMaxSize()) { focused ->
                                Box(Modifier.fillMaxSize().clip(TvShape.CardProminent)
                                    .background(if (focused) Color.White else TvColor.Elevated),
                                    contentAlignment = Alignment.Center) {
                                    Text("Add channel", style = TvType.PlateTitle,
                                        color = if (focused) TvColor.Canvas else TvColor.TextSecondary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (adding) LiveMultiviewPicker(state, initialChannel?.sourceId.orEmpty(),
        onClose = { adding = false }) { channel ->
        if (slots.none { it.id == channel.id } && slots.size < maxSlots) {
            slots.add(channel)
            if (activeAudioId.isEmpty()) activeAudioId = channel.id
        }
        adding = false
    }
}

@Composable
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun LiveVideoTile(channel: IptvChannel, repo: IptvRepository, audible: Boolean,
                          modifier: Modifier, onChooseAudio: () -> Unit, onRemove: () -> Unit,
                          previewOnly: Boolean = false) {
    val context = LocalContext.current
    val player = remember(channel.id) {
        val source = repo.state.value.sources.firstOrNull { it.id == channel.sourceId }
        val endpoint = (source?.host ?: channel.streamRef).toHttpUrlOrNull()
        val http = endpoint?.let { context.clientGraph.okHttp.newBuilder()
            .dns(FastPlaybackDns(it.host, it.port)).connectTimeout(3, TimeUnit.SECONDS).build()
        } ?: context.clientGraph.okHttp
        val headers = iptvPlaybackHeaders(channel.headers)
        val agent = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
            ?: "VLC/3.0.21 LibVLC/3.0.21"
        val httpFactory = OkHttpDataSource.Factory(http).setUserAgent(agent)
            .setDefaultRequestProperties(headers)
        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory)).build().apply {
            volume = if (audible) 1f else 0f
            if (previewOnly) trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
        }
    }
    var failure by remember(channel.id) { mutableStateOf<String?>(null) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                failure = "This stream could not play."
                if (previewOnly) player.stop()
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    LaunchedEffect(channel.id) {
        val url = runCatching { repo.playUrl(channel) }.getOrNull()
        if (url == null) failure = "Stream unavailable"
        else {
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
            player.playWhenReady = true
        }
    }
    LaunchedEffect(audible) { player.volume = if (audible) 1f else 0f }
    val picture: @Composable (Boolean) -> Unit = { focused ->
        Box(Modifier.fillMaxSize().clip(TvShape.CardProminent).background(Color.Black)
            .border(if (focused || audible) 3.dp else 1.dp,
                if (focused) Color.White else if (audible) TvColor.TextSecondary else TvColor.Border,
                TvShape.CardProminent)) {
            AndroidView(factory = { viewContext ->
                PlayerView(viewContext).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    isFocusable = false
                    this.player = player
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT)
                }
            }, update = { it.player = player }, modifier = Modifier.fillMaxSize())
            Row(Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(TvColor.Canvas.copy(alpha = 0.82f)).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(channel.name, style = TvType.CardTitle, color = TvColor.TextPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (audible) Text("AUDIO", style = TvType.Badge, color = TvColor.TextPrimary)
            }
            failure?.let { Text(it, style = TvType.Meta, color = TvColor.TextPrimary,
                modifier = Modifier.align(Alignment.Center)) }
        }
    }
    if (previewOnly) Box(modifier) { picture(false) }
    else TvFocusableCard(onClick = onChooseAudio, onLongClick = onRemove, focusRing = false,
        focusScale = 1.015f, modifier = modifier, content = picture)
}

@Composable
private fun LiveMultiviewPicker(state: IptvState, sourceId: String,
                                onClose: () -> Unit, onPick: (IptvChannel) -> Unit) {
    var query by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf(emptyList<IptvChannel>()) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    LaunchedEffect(query, state.catalog, state.accounts.lockedGroups) {
        delay(240)
        matches = withContext(Dispatchers.Default) {
            val allowed = state.channels.filter { it.sourceId == sourceId &&
                it.group !in state.accounts.lockedGroups }
            if (query.isBlank()) state.favorites.filter { it.sourceId == sourceId }.ifEmpty { allowed.take(30) }
            else IptvIndex.search(allowed, state.catalog.programs, query, limit = 50,
                groupOrder = state.orderedGroups)
        }
    }
    SidePanel("Add to Multiview", onClose, width = 780.dp) {
        LiveFormField("Find a channel", query, modifier = Modifier.focusRequester(first), onChange = { query = it })
        Spacer(Modifier.height(20.dp))
        androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(matches, key = IptvChannel::id) { channel ->
                SidePanelRow(channel.name, selected = false, onClick = { onPick(channel) })
            }
        }
    }
}
