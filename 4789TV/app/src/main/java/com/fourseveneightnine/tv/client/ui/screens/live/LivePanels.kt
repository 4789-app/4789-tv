package com.fourseveneightnine.tv.client.ui.screens.live

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.client.iptv.IptvChannel
import com.fourseveneightnine.tv.client.iptv.IptvIndex
import com.fourseveneightnine.tv.client.iptv.IptvProgram
import com.fourseveneightnine.tv.client.iptv.IptvRepository
import com.fourseveneightnine.tv.client.iptv.IptvRecording
import com.fourseveneightnine.tv.client.iptv.IptvRecordingStatus
import com.fourseveneightnine.tv.client.iptv.IptvSeriesDetails
import com.fourseveneightnine.tv.client.iptv.IptvSourceKind
import com.fourseveneightnine.tv.client.iptv.IptvState
import com.fourseveneightnine.tv.client.iptv.orderedIptvEpisodes
import com.fourseveneightnine.tv.client.iptv.IptvVod
import com.fourseveneightnine.tv.client.iptv.IptvGuideKind
import com.fourseveneightnine.tv.client.iptv.guideChannelHint
import com.fourseveneightnine.tv.client.ui.components.EmptyState
import com.fourseveneightnine.tv.client.ui.components.SidePanel
import com.fourseveneightnine.tv.client.ui.components.SidePanelRow
import com.fourseveneightnine.tv.client.ui.components.TvButton
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvShape
import com.fourseveneightnine.tv.client.ui.theme.TvType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun LiveSourceList(state: IptvState, onAdd: () -> Unit, onSelect: (String) -> Unit,
                            contentFocus: FocusRequester, onParental: () -> Unit,
                            onDecisionKey: () -> Unit, onBackup: () -> Unit, onManageGroups: () -> Unit) {
    Column {
        TvButton("Add playlist or login", onClick = onAdd, modifier = Modifier.focusRequester(contentFocus))
        Spacer(Modifier.height(12.dp))
        TvButton("Parental controls", onClick = onParental)
        Spacer(Modifier.height(12.dp))
        TvButton(if (state.accounts.decisionApiKey == null) "Set category decision key"
            else "Category decision key saved", onClick = onDecisionKey)
        Spacer(Modifier.height(12.dp))
        TvButton("Backup and restore", onClick = onBackup)
        Spacer(Modifier.height(12.dp))
        TvButton("Manage channel groups", onClick = onManageGroups)
        Spacer(Modifier.height(20.dp))
        if (state.sources.isEmpty()) {
            EmptyState("No IPTV sources on this TV", "Add an M3U link, Xtream login, or Stalker portal. It is saved on the TV.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(state.sources, key = { it.id }) { source ->
                    SidePanelRow(
                        label = "${source.name} · ${source.kind.name} · ${if (source.enabled) "On" else "Off"}" +
                            (source.maxConnections?.let { " · $it ${if (it == 1) "stream" else "streams"} allowed" } ?: "") +
                            (state.errors[source.id]?.let { " · $it" } ?: ""),
                        selected = false, width = 1100.dp, height = 82.dp,
                        onClick = { onSelect(source.id) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun LiveDecisionKeyPanel(repo: IptvRepository, onClose: () -> Unit,
                                  onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    var key by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    SidePanel("Category decisions", onClose, width = 760.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("The included Jev decisions order this TV's current categories. Add your own key for " +
                "new categories from future sources. The key is encrypted on this TV.",
                style = TvType.Body, color = TvColor.TextSecondary)
            LiveFormField("Jev API key", key, modifier = Modifier.focusRequester(first),
                password = true, onChange = { key = it })
            TvButton("Save key", onClick = {
                scope.launch {
                    if (repo.setDecisionApiKey(key)) {
                        onMessage("Decision key saved on this TV.")
                        key = ""
                        onClose()
                        repo.refresh()
                    } else onMessage("Enter a valid decision API key.")
                }
            })
        }
    }
}

@Composable
internal fun LiveAddSourcePanel(repo: IptvRepository, onClose: () -> Unit, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    var kind by remember { mutableStateOf(IptvSourceKind.M3U) }
    var name by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var mac by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    SidePanel("Add IPTV source", onClose, width = 760.dp, slideIn = true) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Your provider's link or login is encrypted on this TV and works without the iPhone.",
                style = TvType.Meta, color = TvColor.TextSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IptvSourceKind.entries.forEachIndexed { index, option ->
                    TvButton(option.name, onClick = { kind = option },
                        modifier = if (index == 0) Modifier.focusRequester(first) else Modifier)
                }
            }
            LiveFormField("Name (optional)", name, onChange = { name = it })
            when (kind) {
                IptvSourceKind.M3U -> LiveFormField("Playlist URL", link, onChange = { link = it })
                IptvSourceKind.XTREAM -> {
                    LiveFormField("Provider host", host, onChange = { host = it })
                    LiveFormField("Username", user, onChange = { user = it })
                    LiveFormField("Password", password, password = true, onChange = { password = it })
                }
                IptvSourceKind.STALKER -> {
                    LiveFormField("Portal host", host, onChange = { host = it })
                    LiveFormField("MAC address", mac, onChange = { mac = it })
                }
            }
            TvButton(if (saving) "Saving…" else "Save and load", enabled = !saving, onClick = {
                saving = true
                scope.launch {
                    val saved = when (kind) {
                        IptvSourceKind.M3U -> repo.addLink(name, link)
                        IptvSourceKind.XTREAM -> repo.addXtream(name, host, user, password)
                        IptvSourceKind.STALKER -> repo.addStalker(name, host, mac)
                    }
                    saving = false
                    if (saved) { onMessage("IPTV source saved on this TV."); onClose() }
                    else onMessage("Check the link or login fields and try again.")
                }
            })
        }
    }
}

@Composable
internal fun LiveFormField(label: String, value: String, modifier: Modifier = Modifier,
                           password: Boolean = false, fontSizeSp: Int = 25,
                           heightDp: Int = 58, onDown: (() -> Unit)? = null,
                           onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = TvType.Meta, color = TvColor.TextSecondary)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = TextStyle(color = TvColor.TextPrimary, fontSize = fontSizeSp.sp,
                fontWeight = FontWeight.Medium),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = modifier.onPreviewKeyEvent { event ->
                if (onDown != null && event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                    onDown(); true
                } else false
            }.fillMaxWidth().height(heightDp.dp).clip(TvShape.Control)
                .background(TvColor.Elevated2).border(1.dp, Color.White.copy(alpha = 0.22f), TvShape.Control)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(label, style = TvType.Meta, color = TvColor.TextMuted)
                inner()
            },
        )
    }
}

@Composable
internal fun LiveSourceActions(id: String, state: IptvState, repo: IptvRepository,
                               onClose: () -> Unit, onEdit: () -> Unit) {
    val source = state.sources.firstOrNull { it.id == id } ?: return
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    var confirmRemove by remember { mutableStateOf(false) }
    SidePanel(source.name, onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${source.kind.name} · ${state.catalog.channels.count { it.sourceId == id }} channels",
                style = TvType.Meta, color = TvColor.TextSecondary)
            TvButton("Edit source and guide", onClick = onEdit, modifier = Modifier.focusRequester(first))
            TvButton(if (source.enabled) "Disable source" else "Enable source", onClick = {
                scope.launch { repo.setEnabled(id, !source.enabled); onClose() }
            })
            TvButton("Refresh all sources", onClick = { scope.launch { repo.refresh(); onClose() } })
            TvButton(if (confirmRemove) "Confirm remove" else "Remove source", onClick = {
                if (!confirmRemove) confirmRemove = true
                else scope.launch { repo.removeSource(id); onClose() }
            })
        }
    }
}

@Composable
internal fun LiveRecordingPanel(item: IptvRecording, repo: IptvRepository,
                                onClose: () -> Unit, onPlay: () -> Unit,
                                onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    SidePanel(item.title, onClose, width = 700.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("${item.status.name.lowercase().replaceFirstChar(Char::uppercase)} · ${clock(item.startsAtMillis)}",
                style = TvType.Meta, color = TvColor.TextSecondary)
            item.error?.let { Text(it, style = TvType.Meta, color = TvColor.TextSecondary) }
            if (item.status == IptvRecordingStatus.DONE) {
                TvButton("Play recording", onClick = onPlay, modifier = Modifier.focusRequester(first))
            }
            if (item.status in setOf(IptvRecordingStatus.SCHEDULED, IptvRecordingStatus.RECORDING)) {
                TvButton("Cancel recording", onClick = {
                    scope.launch {
                        onMessage(if (repo.cancelRecording(item.id)) "Recording cancelled." else "Couldn't cancel recording.")
                        onClose()
                    }
                }, modifier = Modifier.focusRequester(first))
            }
            TvButton(if (confirmDelete) "Confirm delete" else "Delete recording", onClick = {
                if (!confirmDelete) confirmDelete = true
                else scope.launch {
                    onMessage(if (repo.deleteRecording(item.id)) "Recording deleted." else "Couldn't delete recording.")
                    onClose()
                }
            }, modifier = if (item.status == IptvRecordingStatus.FAILED) Modifier.focusRequester(first) else Modifier)
        }
    }
}

@Composable
internal fun LiveChannelPanel(
    channel: IptvChannel, state: IptvState, repo: IptvRepository,
    onClose: () -> Unit, onPlay: (IptvChannel) -> Unit,
    onProgram: (IptvProgram) -> Unit, onMessage: (String) -> Unit,
    onMultiview: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    var listMembership by remember { mutableStateOf(false) }
    LaunchedEffect(listMembership) { withFrameNanos { }; runCatching { first.requestFocus() } }
    BackHandler(enabled = listMembership) { listMembership = false }
    if (listMembership) {
        SidePanel("Lists for ${channel.name}", { listMembership = false }, width = 780.dp, closeOnLeft = false) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(state.accounts.favoriteLists.keys.toList(), key = { it }) { name ->
                    SidePanelRow(name, selected = channel.id in state.accounts.favoriteLists[name].orEmpty(),
                        modifier = if (name == state.accounts.favoriteLists.keys.first()) Modifier.focusRequester(first) else Modifier, onClick = {
                        scope.launch { repo.toggleListFavorite(name, channel.id) }
                    })
                }
            }
        }
        return
    }
    val programs = remember(state.catalog.programs, channel.id) {
        state.catalog.programsByChannel[channel.id].orEmpty()
    }
    val source = state.sources.firstOrNull { it.id == channel.sourceId }
    val canRecord = (source?.maxConnections ?: 0) >= 2
    val decoderSlots = remember { tvMultiviewDecoderSlots() }
    SidePanel(channel.name, onClose, width = 780.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(channel.group, style = TvType.Meta.copy(fontSize = 24.sp),
                color = TvColor.TextSecondary)
            channel.guideChannelHint()?.let { hint ->
                Text(hint.label, style = TvType.Badge.copy(fontSize = 22.sp),
                    color = if (hint == IptvGuideKind.MovieChannel) TvColor.Warning else TvColor.Cached)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton("Play live", onClick = { onPlay(channel) }, modifier = Modifier.focusRequester(first))
                TvButton(if (channel.id in state.accounts.favoriteIds) "Remove favorite" else "★ Favorite",
                    onClick = { scope.launch {
                        onMessage(when (repo.toggleFavorite(channel.id)) {
                            true -> "Added to Favorites"
                            false -> "Removed from Favorites"
                            null -> "Couldn't save Favorite"
                        })
                    } })
            }
            if (state.accounts.favoriteLists.isNotEmpty())
                TvButton("Add to favorite lists", onClick = { listMembership = true })
            if (minOf(source?.maxConnections ?: 0, decoderSlots) >= 2)
                TvButton("Open in Multiview", onClick = onMultiview)
            val currentProgram = programs.firstOrNull { it.startMillis <= System.currentTimeMillis() &&
                it.endMillis > System.currentTimeMillis() }
            if (canRecord && currentProgram != null) TvButton("Record this program", onClick = {
                scope.launch {
                    onMessage(if (repo.scheduleRecording(channel, currentProgram)) "Recording started on this TV."
                        else "Couldn't start recording.")
                }
            })
            Text("What's on", style = TvType.ShelfHeader.copy(fontSize = 30.sp),
                color = TvColor.TextPrimary)
            if (programs.isEmpty()) Text("No schedule data was supplied for this channel.",
                style = TvType.Meta.copy(fontSize = 24.sp, lineHeight = 32.sp),
                color = TvColor.TextSecondary)
            else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(programs, key = { "${it.channelKey}:${it.startMillis}" }) { program ->
                    val action = when {
                        program.startMillis > System.currentTimeMillis() && canRecord -> "Record"
                        program.startMillis > System.currentTimeMillis() -> "Upcoming"
                        program.endMillis <= System.currentTimeMillis() && repo.catchupUrl(channel, program) != null -> "Replay"
                        program.endMillis > System.currentTimeMillis() -> "Live"
                        else -> "Unavailable"
                    }
                    GuideProgramCard(program, channel, action.uppercase(Locale.getDefault()),
                        onClick = { onProgram(program) })
                }
            }
        }
    }
}

@Composable
internal fun LiveEpisodesPanel(series: IptvVod, repo: IptvRepository, onClose: () -> Unit, onPlay: (IptvVod) -> Unit) {
    var details by remember(series.id) { mutableStateOf(IptvSeriesDetails(series, emptyList())) }
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("tv_episode_order", 0) }
    var order by remember { mutableStateOf(prefs.getString("order", "Latest episode") ?: "Latest episode") }
    val episodes = remember(details.episodes, order) { orderedIptvEpisodes(details.episodes, order) }
    var loading by remember(series.id) { mutableStateOf(true) }
    var error by remember(series.id) { mutableStateOf<String?>(null) }
    var retry by remember(series.id) { mutableStateOf(0) }
    val first = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var selectedEpisodeId by rememberSaveable(series.id) { mutableStateOf<String?>(null) }
    val selectedEpisode = episodes.firstOrNull { it.id == selectedEpisodeId } ?: episodes.firstOrNull()
    LaunchedEffect(series.id, retry) {
        loading = true
        error = null
        val result = runCatching { repo.seriesDetails(series) }
        result.getOrNull()?.let { details = it }
        error = result.exceptionOrNull()?.message?.take(120)
        loading = false
    }
    LaunchedEffect(loading, error) {
        if (!loading && (error != null || episodes.isNotEmpty())) {
            val shown = details.series
            val headerCount = (if (listOfNotNull(shown.year, shown.genre, shown.rating).isNotEmpty()) 1 else 0) +
                (if (shown.description != null) 1 else 0) + (if (shown.cast != null) 1 else 0)
            val target = if (error != null) headerCount + 1
                else headerCount + episodes.indexOf(selectedEpisode).coerceAtLeast(0)
            listState.scrollToItem(target)
            withFrameNanos { }
            runCatching { first.requestFocus() }
        }
    }
    SidePanel(series.title, onClose, width = 780.dp) {
        val orders = listOf("Latest episode", "Latest air date", "Oldest episode")
        TvButton("Order: $order", onClick = {
            order = orders[(orders.indexOf(order) + 1) % orders.size]
            selectedEpisodeId = null
            scope.launch { listState.scrollToItem(0) }
            prefs.edit().putString("order", order).apply()
        }, height = 60.dp)
        Spacer(Modifier.height(16.dp))
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val displayed = details.series
            val facts = listOfNotNull(displayed.year, displayed.genre, displayed.rating?.let { "Rating $it" })
            if (facts.isNotEmpty()) item {
                Text(facts.joinToString(" · "), style = TvType.Meta, color = TvColor.TextSecondary)
            }
            displayed.description?.let { plot -> item {
                Text(plot, style = TvType.Body, color = TvColor.TextPrimary)
            } }
            displayed.cast?.let { cast -> item {
                Text("Cast · $cast", style = TvType.Meta, color = TvColor.TextSecondary)
            } }
            if (loading) item {
                Text("Loading episodes…", style = TvType.Body, color = TvColor.TextSecondary)
            } else if (error != null) {
                item { Text("Couldn't load episodes: $error", style = TvType.Body, color = TvColor.TextSecondary) }
                item { SidePanelRow("Try again", selected = false, onClick = { retry++ },
                    modifier = Modifier.focusRequester(first)) }
            } else if (episodes.isEmpty()) item {
                Text("No episodes returned by this source.", style = TvType.Body,
                    color = TvColor.TextSecondary)
            } else items(episodes, key = IptvVod::id) { episode ->
                SidePanelRow(episode.title, selected = false, onClick = { selectedEpisodeId = episode.id; onPlay(episode) },
                    modifier = if (episode == selectedEpisode) Modifier.focusRequester(first) else Modifier)
            }
        }
    }
}

@Composable
internal fun LiveGroupPicker(groups: List<String>, selected: String?, onPick: (String?) -> Unit, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    SidePanel("Groups", onClose) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { SidePanelRow("All groups", selected == null, onClick = { onPick(null) },
                modifier = Modifier.focusRequester(first)) }
            items(groups) { group -> SidePanelRow(group, selected == group, onClick = { onPick(group) }) }
        }
    }
}

private data class LiveSearchMatches(
    val channels: List<IptvChannel>,
    val programs: List<Pair<IptvProgram, IptvChannel>>,
    val movies: List<IptvVod>,
    val series: List<IptvVod>,
    val categories: List<Triple<String, String, Int>>,
)

@Composable
internal fun LiveSearchPanel(
    state: IptvState, onClose: () -> Unit,
    onChannel: (IptvChannel) -> Unit, onFavorite: (IptvChannel) -> Unit,
    onVod: (IptvVod) -> Unit,
    unlockedGroups: Set<String>,
    onChannelGroup: (String) -> Unit, onVodGroup: (String, String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var channels by remember { mutableStateOf(emptyList<IptvChannel>()) }
    var programs by remember { mutableStateOf(emptyList<Pair<IptvProgram, IptvChannel>>()) }
    var movies by remember { mutableStateOf(emptyList<IptvVod>()) }
    var series by remember { mutableStateOf(emptyList<IptvVod>()) }
    var categories by remember { mutableStateOf(emptyList<Triple<String, String, Int>>()) }
    val first = remember { FocusRequester() }
    val firstResult = remember { FocusRequester() }
    val liveCategories = categories.filter { it.first == "channel" }
    val vodCategories = categories.filter { it.first != "channel" }
    val hasResults = channels.isNotEmpty() || liveCategories.isNotEmpty() || programs.isNotEmpty() ||
        movies.isNotEmpty() || series.isNotEmpty() || vodCategories.isNotEmpty()
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    LaunchedEffect(query, state.catalog, state.accounts.lockedGroups, unlockedGroups) {
        delay(250)
        val matches = withContext(Dispatchers.Default) {
            val allowed = state.channels.filter {
                it.group !in state.accounts.lockedGroups || it.group in unlockedGroups
            }
            val term = query.trim()
            val channelHits = if (term.isBlank()) {
                val recent = state.accounts.recentIds.mapNotNull { id -> allowed.firstOrNull { it.id == id } }
                (recent + allowed.filter { it.id in state.accounts.favoriteIds }).distinctBy(IptvChannel::id).take(20)
            } else IptvIndex.search(allowed, state.catalog.programs, term,
                limit = 20, groupOrder = state.orderedGroups)
            val byId = allowed.associateBy(IptvChannel::id)
            val programHits = if (term.length < 2) emptyList() else state.catalog.programs.asSequence()
                .filter { it.title.contains(term, ignoreCase = true) }
                .mapNotNull { program -> byId[program.channelKey]?.let { program to it } }
                .take(12).toList()
            val movieHits = if (term.length < 2) emptyList() else state.catalog.vod.asSequence()
                .filter { it.kind == "movie" && it.title.contains(term, ignoreCase = true) }
                .take(16).toList()
            val seriesHits = if (term.length < 2) emptyList() else state.catalog.vod.asSequence()
                .filter { it.kind == "series" && it.title.contains(term, ignoreCase = true) }
                .take(16).toList()
            val groupHits = if (term.length < 2) emptyList() else {
                val live = allowed.groupingBy(IptvChannel::group).eachCount().filterKeys {
                    it.contains(term, ignoreCase = true)
                }.map { Triple("channel", it.key, it.value) }
                val vod = state.catalog.vod.groupingBy { it.kind to it.category }.eachCount().mapNotNull { (key, count) ->
                    if (key.first in setOf("movie", "series") && key.second.contains(term, ignoreCase = true))
                        Triple(key.first, key.second, count) else null
                }
                (live + vod).sortedWith(compareBy({ it.first != "channel" }, { it.second })).take(20)
            }
            LiveSearchMatches(channelHits, programHits, movieHits, seriesHits, groupHits)
        }
        channels = matches.channels
        programs = matches.programs
        movies = matches.movies
        series = matches.series
        categories = matches.categories
    }
    SidePanel("Search Live TV", onClose, width = 900.dp, closeOnLeft = false) {
        LiveFormField("Channels, programs, movies, series", query,
            modifier = Modifier.focusRequester(first), fontSizeSp = 30, heightDp = 72,
            onDown = { if (hasResults) runCatching { firstResult.requestFocus() } },
            onChange = { query = it })
        Spacer(Modifier.height(24.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (query.isBlank()) item {
                Text(if (channels.isEmpty()) "Type to search channels, programs, movies, and series."
                    else "Recent and favorite channels", style = TvType.Meta, color = TvColor.TextSecondary)
            }
            if (channels.isNotEmpty() && query.isNotBlank()) item {
                Text("Live channels", style = TvType.ShelfHeader, color = TvColor.TextPrimary)
            }
            items(channels, key = IptvChannel::id) { channel ->
                SidePanelRow("${channel.name} · ${channel.group}", selected = false,
                    width = 790.dp, height = 82.dp,
                    modifier = (if (channel == channels.first()) Modifier.focusRequester(firstResult) else Modifier)
                        .liveFavoriteKey { onFavorite(channel) },
                    onClick = { onClose(); onChannel(channel) })
            }
            if (liveCategories.isNotEmpty()) item {
                Text("Live categories", style = TvType.ShelfHeader, color = TvColor.TextPrimary)
            }
            items(liveCategories, key = { "${it.first}:${it.second}" }) { (_, name, count) ->
                SidePanelRow("$name · $count channels", selected = false,
                    width = 790.dp, height = 82.dp,
                    modifier = if (channels.isEmpty() && name == liveCategories.first().second)
                        Modifier.focusRequester(firstResult) else Modifier,
                    onClick = { onClose(); onChannelGroup(name) })
            }
            if (programs.isNotEmpty()) item {
                Text("Programs", style = TvType.ShelfHeader, color = TvColor.TextPrimary)
            }
            items(programs, key = { "${it.first.channelKey}:${it.first.startMillis}" }) { (program, channel) ->
                SidePanelRow("${program.title} · ${channel.name}", selected = false,
                    width = 790.dp, height = 82.dp,
                    modifier = if (channels.isEmpty() && liveCategories.isEmpty() &&
                        program == programs.first().first) Modifier.focusRequester(firstResult) else Modifier,
                    onClick = { onClose(); onChannel(channel) })
            }
            if (movies.isNotEmpty()) item {
                Text("Movies", style = TvType.ShelfHeader, color = TvColor.TextPrimary)
            }
            items(movies, key = IptvVod::id) { item ->
                SidePanelRow("${item.title} · ${item.category}", selected = false,
                    width = 790.dp, height = 82.dp,
                    modifier = if (channels.isEmpty() && liveCategories.isEmpty() && programs.isEmpty() &&
                        item == movies.first()) Modifier.focusRequester(firstResult) else Modifier,
                    onClick = { onClose(); onVod(item) })
            }
            if (series.isNotEmpty()) item {
                Text("Series", style = TvType.ShelfHeader, color = TvColor.TextPrimary)
            }
            items(series, key = IptvVod::id) { item ->
                SidePanelRow("${item.title} · ${item.category}", selected = false,
                    width = 790.dp, height = 82.dp,
                    modifier = if (channels.isEmpty() && liveCategories.isEmpty() && programs.isEmpty() &&
                        movies.isEmpty() && item == series.first()) Modifier.focusRequester(firstResult) else Modifier,
                    onClick = { onClose(); onVod(item) })
            }
            if (vodCategories.isNotEmpty()) item {
                Text("Movie and series categories", style = TvType.ShelfHeader, color = TvColor.TextPrimary)
            }
            items(vodCategories, key = { "${it.first}:${it.second}" }) { (kind, name, count) ->
                SidePanelRow("$name · $count ${if (kind == "movie") "movies" else "series"}",
                    selected = false,
                    width = 790.dp, height = 82.dp,
                    modifier = if (channels.isEmpty() && liveCategories.isEmpty() && programs.isEmpty() &&
                        movies.isEmpty() && series.isEmpty() && name == vodCategories.first().second)
                        Modifier.focusRequester(firstResult) else Modifier,
                    onClick = { onClose(); onVodGroup(kind, name) })
            }
            if (query.length >= 2 && channels.isEmpty() && programs.isEmpty() &&
                movies.isEmpty() && series.isEmpty() && categories.isEmpty()) item {
                Text("No matching IPTV results.", style = TvType.Meta, color = TvColor.TextSecondary)
            }
        }
    }
}

private fun clock(timeMillis: Long): String = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timeMillis))

@Composable
internal fun LiveFavoriteListsPanel(state: IptvState, repo: IptvRepository, selected: String?,
    onSelect: (String?) -> Unit, onClose: () -> Unit, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    var name by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    SidePanel("Favorite lists", onClose, width = 780.dp, closeOnLeft = false) {
        LiveFormField("New list name", name, modifier = Modifier.focusRequester(first), onChange = { name = it })
        Spacer(Modifier.height(12.dp))
        TvButton("Create list", onClick = { scope.launch {
            if (repo.createFavoriteList(name)) { onSelect(name.trim().take(60)); onClose() }
            else onMessage("Enter a list name.")
        } })
        Spacer(Modifier.height(16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { SidePanelRow("All favorites", selected == null, onClick = { onSelect(null); onClose() }) }
            items(state.accounts.favoriteLists.keys.toList(), key = { it }) { list ->
                SidePanelRow("$list · ${state.accounts.favoriteLists[list]?.size ?: 0} channels", selected == list,
                    onClick = { onSelect(list); onClose() })
            }
            selected?.let { list -> item {
                TvButton("Delete $list", onClick = { scope.launch {
                    repo.removeFavoriteList(list); onSelect(null)
                } })
            } }
        }
    }
}
