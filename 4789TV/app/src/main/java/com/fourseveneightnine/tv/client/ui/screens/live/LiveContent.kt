package com.fourseveneightnine.tv.client.ui.screens.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.client.data.images.PosterRequest
import com.fourseveneightnine.tv.client.iptv.IptvChannel
import com.fourseveneightnine.tv.client.iptv.IptvGroupDecision
import com.fourseveneightnine.tv.client.iptv.IptvProgram
import com.fourseveneightnine.tv.client.iptv.IptvRepository
import com.fourseveneightnine.tv.client.iptv.IptvRecording
import com.fourseveneightnine.tv.client.iptv.IptvRecordingStatus
import com.fourseveneightnine.tv.client.iptv.IptvSource
import com.fourseveneightnine.tv.client.iptv.IptvSourceKind
import com.fourseveneightnine.tv.client.iptv.IptvState
import com.fourseveneightnine.tv.client.iptv.IptvVod
import com.fourseveneightnine.tv.client.iptv.IptvVodCategory
import com.fourseveneightnine.tv.client.ui.components.EmptyState
import com.fourseveneightnine.tv.client.ui.components.ErrorState
import com.fourseveneightnine.tv.client.ui.components.TvArtwork
import com.fourseveneightnine.tv.client.ui.components.TvButton
import com.fourseveneightnine.tv.client.ui.components.TvFocusableCard
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvShape
import com.fourseveneightnine.tv.client.ui.theme.TvType
import com.fourseveneightnine.tv.ui.TvRemoteKeyPolicy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.coroutines.launch

/** The focused channel owns the Favorite key; held repeats never toggle it twice. */
internal fun Modifier.liveFavoriteKey(onFavorite: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    if (!TvRemoteKeyPolicy.isFavoriteKey(event.nativeKeyEvent.keyCode)) false else {
        if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) onFavorite()
        true
    }
}

@Composable
internal fun LiveTabLabel(label: String, active: Boolean, focused: Boolean) {
    Box(Modifier.height(62.dp).clip(CircleShape)
        .background(if (focused) TvColor.TextPrimary else Color.Transparent)
        .padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
        Text(label, style = TvType.ControlLabel.copy(fontSize = 23.sp),
            color = if (focused) TvColor.Canvas else if (active) TvColor.TextPrimary else TvColor.TextSecondary)
        if (active && !focused) Box(Modifier.align(Alignment.BottomCenter).width(46.dp).height(3.dp)
            .background(Color.White, CircleShape))
    }
}

@Composable
internal fun LiveContent(
    tab: LiveTab, state: IptvState, repo: IptvRepository, group: String?,
    vodGroup: String?, contentFocus: FocusRequester,
    unlockedGroups: Set<String>,
    onGroup: () -> Unit, onGroupSelect: (String?) -> Unit, onTab: (LiveTab) -> Unit,
    onVodGroupSelect: (String?) -> Unit, onAdd: () -> Unit,
    onChannel: (IptvChannel) -> Unit, onChannelMenu: (IptvChannel) -> Unit,
    onFavorite: (IptvChannel) -> Unit,
    onGuideProgram: (IptvChannel, IptvProgram) -> Unit,
    onVod: (IptvVod) -> Unit, onRecording: (IptvRecording) -> Unit, onSource: (String) -> Unit,
    onParental: () -> Unit, onDecisionKey: () -> Unit, onBackup: () -> Unit,
    onManageGroups: () -> Unit,
) {
    when (tab) {
        LiveTab.Favorites -> {
            if (state.favorites.isEmpty()) {
                LiveEmptyHero(hasSources = state.sources.isNotEmpty(), onAdd = onAdd,
                    onBrowse = { onTab(LiveTab.Channels) }, contentFocus = contentFocus)
            } else LiveFavoritesShowcase(state, onChannel, onChannelMenu, onFavorite, contentFocus)
        }
        LiveTab.Channels -> {
            if (group == null) {
                LiveGroupOverview(state, onGroupSelect, contentFocus)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (group == ALL_CHANNELS) "All channels" else group,
                        style = TvType.ShelfHeader, color = TvColor.TextPrimary)
                    Spacer(Modifier.width(20.dp))
                    TvButton("Groups", onClick = { onGroupSelect(null) })
                }
                Spacer(Modifier.height(12.dp))
                LiveChannelList(state.channels.filter { (group == ALL_CHANNELS || it.group == group) &&
                    it.group !in state.accounts.hiddenGroups &&
                    (it.group !in state.accounts.lockedGroups || it.group in unlockedGroups) },
                    state, onChannel, onChannelMenu, onFavorite, contentFocus,
                    showGroup = group == ALL_CHANNELS)
            }
        }
        LiveTab.Guide -> LiveGuide(state, contentFocus, unlockedGroups, onChannel, onFavorite, onGuideProgram)
        LiveTab.Movies, LiveTab.Series -> {
            val scope = rememberCoroutineScope()
            val kind = if (tab == LiveTab.Movies) "movie" else "series"
            val enabled = state.sources.filter(IptvSource::enabled).map(IptvSource::id).toSet()
            val vod = remember(state.catalog.vod, enabled, kind) {
                state.catalog.vod.filter { it.kind == kind && it.sourceId in enabled }
            }
            val categories = remember(state.catalog.vodCategories, enabled, kind) {
                state.catalog.vodCategories.filter { it.kind == kind && it.sourceId in enabled }
            }
            when {
                vod.isEmpty() && categories.isEmpty() && (state.catalogLoading || state.refreshing) ->
                    EmptyState("Opening saved ${tab.label.lowercase()}", "Your TV library is loading.")
                vod.isEmpty() && categories.isEmpty() && state.errors.keys.any { it in enabled } ->
                    ErrorState("${tab.label} are temporarily unavailable",
                        "The provider did not finish this listing. Try the refresh again.",
                        onRetry = { scope.launch { repo.refresh() } },
                        actionModifier = Modifier.focusRequester(contentFocus))
                else -> LiveVodBrowse(vod, categories, state.sources, state.accounts.vodDecisions, tab, vodGroup,
                    state.catalogLoading, onVodGroupSelect, onVod, contentFocus)
            }
        }
        LiveTab.Recordings -> LiveRecordingsList(state.accounts.recordings, contentFocus, onRecording)
        LiveTab.Sources -> LiveSourceList(state, onAdd, onSource, contentFocus,
            onParental, onDecisionKey, onBackup, onManageGroups)
    }
}

@Composable
private fun LiveRecordingsList(items: List<IptvRecording>, focus: FocusRequester,
                               onClick: (IptvRecording) -> Unit) {
    if (items.isEmpty()) {
        EmptyState("No recordings yet", "Choose a current or upcoming program in Guide to save it on this TV.")
        return
    }
    val ordered = remember(items) { items.sortedByDescending(IptvRecording::startsAtMillis) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(ordered, key = IptvRecording::id) { item ->
            TvFocusableCard(onClick = { onClick(item) }, focusScale = 1.02f, focusRing = false,
                modifier = if (item == ordered.first()) Modifier.focusRequester(focus) else Modifier) { focused ->
                Row(Modifier.fillMaxWidth().height(110.dp).clip(TvShape.Card)
                    .background(if (focused) Color.White else TvColor.Elevated).padding(horizontal = 26.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.title, style = TvType.CardTitle.copy(fontSize = 29.sp),
                            color = if (focused) TvColor.Canvas else TvColor.TextPrimary, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        val date = SimpleDateFormat("EEE d MMM · h:mm a", Locale.getDefault())
                            .format(Date(item.startsAtMillis))
                        Text(date, style = TvType.Meta,
                            color = if (focused) TvColor.Canvas.copy(alpha = 0.7f) else TvColor.TextSecondary)
                    }
                    Text(item.status.name.lowercase().replaceFirstChar(Char::uppercase), style = TvType.Meta,
                        color = if (focused) TvColor.Canvas else if (item.status == IptvRecordingStatus.DONE)
                            TvColor.Cached else TvColor.TextSecondary)
                }
            }
        }
    }
}

internal const val ALL_CHANNELS = "__all_channels__"

@Composable
private fun LiveGroupOverview(state: IptvState, onPick: (String?) -> Unit, contentFocus: FocusRequester) {
    val counts = remember(state.catalog.channels, state.sources, state.accounts.groupOrder,
        state.accounts.hiddenGroups) {
        val totals = state.channels.groupingBy(IptvChannel::group).eachCount()
        state.orderedGroups.filterNot { it in state.accounts.hiddenGroups }
            .map { it to (totals[it] ?: 0) }
    }
    Column {
        Text("Channel groups", style = TvType.ShelfHeader.copy(fontSize = 32.sp), color = TvColor.TextPrimary)
        Spacer(Modifier.height(20.dp))
        LazyVerticalGrid(columns = GridCells.Fixed(4), verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            item(key = ALL_CHANNELS) { LiveGroupTile("All channels", state.channels.size,
                modifier = Modifier.focusRequester(contentFocus), onClick = { onPick(ALL_CHANNELS) }) }
            gridItems(counts, key = { it.first }) { entry ->
                LiveGroupTile(entry.first, entry.second, onClick = { onPick(entry.first) })
            }
        }
    }
}

@Composable
private fun LiveGroupTile(label: String, count: Int, modifier: Modifier = Modifier,
                          noun: String = "channels", tag: String? = null, onClick: () -> Unit) {
    TvFocusableCard(onClick = onClick, modifier = modifier, focusScale = 1f, focusRing = false) { focused ->
        Column(Modifier.fillMaxWidth().height(160.dp).clip(TvShape.CardProminent)
            .background(if (focused) Color.White else TvColor.Elevated)
            .padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text(label, style = TvType.CardTitle.copy(fontSize = 30.sp, lineHeight = 36.sp,
                fontWeight = FontWeight.SemiBold), color = if (focused) TvColor.Canvas else TvColor.TextPrimary,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            Text("$count $noun${tag?.let { " · $it" }.orEmpty()}", style = TvType.Meta,
                color = if (focused) TvColor.Canvas.copy(alpha = 0.65f) else TvColor.TextSecondary)
        }
    }
}

internal const val ALL_VOD = "__all_vod__"

@Composable
private fun LiveVodBrowse(vod: List<IptvVod>, preview: List<IptvVodCategory>,
                          sources: List<IptvSource>, savedDecisions: Map<String, IptvGroupDecision>, tab: LiveTab, group: String?,
                          catalogLoading: Boolean, onGroup: (String?) -> Unit, onPlay: (IptvVod) -> Unit,
                          contentFocus: FocusRequester) {
    if (vod.isEmpty() && preview.isEmpty()) {
        EmptyState("No ${tab.label.lowercase()} in these sources", "Add a provider with movies or series.")
        return
    }
    val noun = if (tab == LiveTab.Movies) "movies" else "series"
    val kind = if (tab == LiveTab.Movies) "movie" else "series"
    val context = LocalContext.current
    val decisions = remember(context) {
        runCatching { context.assets.open("iptv-vod-decisions.json").bufferedReader().use {
            Json.decodeFromString<Map<String, IptvGroupDecision>>(it.readText())
        } }.getOrDefault(emptyMap())
    }
    val bySource = remember(sources) { sources.associateBy(IptvSource::id) }
    val categorySources = remember(vod, preview) {
        val groups = HashMap<String, MutableSet<String>>()
        vod.forEach { groups.getOrPut(it.category) { HashSet() }.add(it.sourceId) }
        preview.forEach { groups.getOrPut(it.name) { HashSet() }.add(it.sourceId) }
        groups
    }
    // These decisions were calibrated against one provider's category/title samples. A matching
    // category name from another provider has no proven original-language meaning.
    fun decision(name: String): IptvGroupDecision? {
        val stored = categorySources[name].orEmpty().mapNotNull { source ->
            savedDecisions[com.fourseveneightnine.tv.client.iptv.IptvGroupRanker.vodDecisionKey(source, kind, name)]
        }
        if (stored.size == categorySources[name].orEmpty().size && stored.isNotEmpty())
            return stored.maxByOrNull { it.priority }
        val known = categorySources[name].orEmpty().all { id ->
            val source = bySource[id]
            source?.kind == IptvSourceKind.XTREAM && source.host?.let { host -> runCatching {
                URI(host).host.equals("fastshare1.com", ignoreCase = true)
            }.getOrDefault(false) } == true
        }
        return if (known) decisions["$kind|${name.trim().lowercase()}"] else null
    }
    if (group == null) {
        val counts = remember(vod, preview, decisions, savedDecisions, sources) {
            val cached = preview.groupBy(IptvVodCategory::name)
                .mapValues { (_, entries) -> entries.sumOf(IptvVodCategory::count) }
            val actual = vod.groupingBy(IptvVod::category).eachCount()
            (cached.keys + actual.keys).map { name ->
                name to maxOf(cached[name] ?: 0, actual[name] ?: 0)
            }
                .sortedWith(compareBy<Pair<String, Int>> {
                    decision(it.first)?.priority ?: vodCategoryPriority(it.first)
                }
                    .thenBy { it.first.lowercase() })
        }
        Column {
            Text("${tab.label} categories", style = TvType.ShelfHeader.copy(fontSize = 32.sp),
                color = TvColor.TextPrimary)
            Spacer(Modifier.height(20.dp))
            LazyVerticalGrid(columns = GridCells.Fixed(4), verticalArrangement = Arrangement.spacedBy(20.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                item(key = ALL_VOD) { LiveGroupTile("All $noun", maxOf(vod.size, counts.sumOf { it.second }),
                    modifier = Modifier.focusRequester(contentFocus), noun = noun,
                    onClick = { onGroup(ALL_VOD) }) }
                gridItems(counts, key = { it.first }) { (name, count) ->
                    LiveGroupTile(name, count, noun = noun,
                        tag = decision(name)?.let(::vodOriginTag) ?: "Origin unverified",
                        onClick = { onGroup(name) })
                }
            }
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (group == ALL_VOD) "All $noun" else group,
                style = TvType.ShelfHeader, color = TvColor.TextPrimary)
            Spacer(Modifier.width(20.dp))
            TvButton("Categories", onClick = { onGroup(null) })
        }
        Spacer(Modifier.height(12.dp))
        val selected = remember(vod, group) { if (group == ALL_VOD) vod else vod.filter { it.category == group } }
        if (selected.isEmpty() && (catalogLoading || preview.any { group == ALL_VOD || it.name == group })) {
            EmptyState("Opening saved $noun", "Loading this category from your provider and TV cache.",
                actionLabel = if (group == ALL_VOD) null else "Retry category",
                onAction = if (group == ALL_VOD) null else ({ onGroup(group) }),
                actionModifier = Modifier.focusRequester(contentFocus))
        } else {
            if (catalogLoading && group == ALL_VOD && preview.sumOf(IptvVodCategory::count) > selected.size) {
                Text("${selected.size} titles ready · opening the full library", style = TvType.Meta,
                    color = TvColor.TextSecondary)
                Spacer(Modifier.height(10.dp))
            }
            LiveVodList(selected, onPlay, contentFocus)
        }
    }
}

private fun vodCategoryPriority(name: String): Int {
    val text = name.lowercase()
    val base = when {
        "telugu" in text -> 1
        "cricket" in text -> 2
        "sport" in text -> 3
        "hindi" in text -> 5
        "tamil" in text -> 7
        "english" in text -> 12
        "malayalam" in text || "kannada" in text -> 13
        else -> 19
    }
    return if ("dub" in text) 20 + base else base
}

private fun vodOriginTag(decision: IptvGroupDecision): String = when {
    decision.bucket.startsWith("CRICKET_") || decision.bucket.startsWith("SPORTS_") -> "Sports replays"
    decision.bucket.endsWith("_ORIGINAL") -> "Likely originals"
    decision.bucket.endsWith("_DUBBED") -> "Dubbed"
    else -> "Mixed origins"
}

@Composable
private fun LiveEmptyHero(hasSources: Boolean, onAdd: () -> Unit, onBrowse: () -> Unit,
                          contentFocus: FocusRequester) {
    Box(Modifier.fillMaxWidth().height(625.dp).clip(TvShape.Panel)
        .background(Brush.horizontalGradient(0f to TvColor.Canvas, 1f to Color(0xFF2D2724)))) {
        Column(Modifier.align(Alignment.CenterStart).padding(start = 44.dp)) {
            Text("FAVORITES", style = TvType.Badge.copy(fontSize = 21.sp), color = TvColor.TextSecondary)
            Spacer(Modifier.height(24.dp))
            Text(if (hasSources) "Your channels, one press away." else "Live TV starts here.",
                style = TvType.HeroTitle.copy(fontSize = 64.sp, lineHeight = 72.sp),
                color = TvColor.TextPrimary, maxLines = 2)
            Spacer(Modifier.height(24.dp))
            Text(if (hasSources) "Choose a channel and press Favorite, or hold OK for more actions."
                else "Add a playlist or provider login directly on this TV. It will be here even when your iPhone is away.",
                style = TvType.Body.copy(fontSize = 29.sp, lineHeight = 39.sp),
                color = TvColor.TextSecondary, modifier = Modifier.width(920.dp), maxLines = 3)
            Spacer(Modifier.height(34.dp))
            TvButton(if (hasSources) "Browse channels" else "Add your first source",
                onClick = if (hasSources) onBrowse else onAdd,
                kind = com.fourseveneightnine.tv.client.ui.components.ButtonKind.Primary,
                height = 70.dp, modifier = Modifier.focusRequester(contentFocus))
        }
    }
}

@Composable
internal fun LiveLoadingHero() {
    Box(Modifier.fillMaxWidth().height(625.dp).clip(TvShape.Panel)
        .background(Brush.horizontalGradient(0f to TvColor.Canvas, 1f to Color(0xFF2D2724)))) {
        Column(Modifier.align(Alignment.CenterStart).padding(start = 44.dp)) {
            Text("LIVE TV", style = TvType.Badge.copy(fontSize = 21.sp), color = TvColor.TextSecondary)
            Spacer(Modifier.height(24.dp))
            Text("Opening your saved TV library", style = TvType.HeroTitle.copy(fontSize = 62.sp),
                color = TvColor.TextPrimary)
            Spacer(Modifier.height(22.dp))
            Text("Your channels and favorites are saved on this TV.", style = TvType.Body.copy(fontSize = 29.sp),
                color = TvColor.TextSecondary)
        }
    }
}

@Composable
private fun LiveFavoritesShowcase(
    state: IptvState, onPlay: (IptvChannel) -> Unit, onMenu: (IptvChannel) -> Unit,
    onFavorite: (IptvChannel) -> Unit,
    contentFocus: FocusRequester,
) {
    val featured = state.accounts.recentIds.firstNotNullOfOrNull { id -> state.favorites.firstOrNull { it.id == id } }
        ?: state.favorites.first()
    val now = remember(state.catalog.programs, featured.id) {
        val time = System.currentTimeMillis()
        state.catalog.programs.firstOrNull { it.channelKey == featured.id && it.startMillis <= time && it.endMillis > time }
    }
    Column {
        Box(Modifier.fillMaxWidth().height(400.dp).clip(TvShape.Panel)
            .background(Brush.horizontalGradient(0f to TvColor.Canvas, 1f to Color(0xFF28282B)))) {
            Column(Modifier.align(Alignment.CenterStart).padding(start = 36.dp)) {
                Text("ON YOUR FAVORITES", style = TvType.Badge.copy(fontSize = 20.sp), color = TvColor.TextSecondary)
                Spacer(Modifier.height(18.dp))
                Text(featured.name, style = TvType.HeroTitle.copy(fontSize = 60.sp, lineHeight = 68.sp),
                    color = TvColor.TextPrimary, maxLines = 2, modifier = Modifier.width(870.dp))
                Spacer(Modifier.height(18.dp))
                Text(now?.title ?: featured.group, style = TvType.Body.copy(fontSize = 28.sp),
                    color = TvColor.TextSecondary, maxLines = 2, modifier = Modifier.width(850.dp))
                Spacer(Modifier.height(26.dp))
                TvButton("Watch live", onClick = { onPlay(featured) },
                    kind = com.fourseveneightnine.tv.client.ui.components.ButtonKind.Primary, height = 68.dp,
                    modifier = Modifier.focusRequester(contentFocus).liveFavoriteKey { onFavorite(featured) })
            }
            TvArtwork(PosterRequest(featured.logo, 360), featured.name,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 130.dp).size(360.dp, 220.dp),
                showTitleWhenMissing = false, contentScale = ContentScale.Fit)
        }
        Spacer(Modifier.height(24.dp))
        Text("Favorite channels", style = TvType.ShelfHeader, color = TvColor.TextPrimary)
        Spacer(Modifier.height(18.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            items(state.favorites, key = IptvChannel::id) { channel ->
                TvFocusableCard(onClick = { onPlay(channel) }, onLongClick = { onMenu(channel) },
                    modifier = Modifier.liveFavoriteKey { onFavorite(channel) },
                    focusScale = 1.035f, focusRing = false) { focused ->
                    Column(Modifier.width(300.dp).height(230.dp).clip(TvShape.CardProminent)
                        .background(TvColor.Elevated)
                        .border(if (focused) 3.dp else 1.dp,
                            if (focused) Color.White else TvColor.Border, TvShape.CardProminent)
                        .padding(20.dp)) {
                        TvArtwork(PosterRequest(channel.logo, 240), channel.name,
                            modifier = Modifier.fillMaxWidth().height(145.dp), contentScale = ContentScale.Fit)
                        Spacer(Modifier.height(13.dp))
                        Text(channel.name, style = TvType.CardTitle.copy(fontSize = 26.sp),
                            color = TvColor.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
internal fun LiveChannelList(
    channels: List<IptvChannel>, state: IptvState,
    onPlay: (IptvChannel) -> Unit, onMenu: (IptvChannel) -> Unit,
    onFavorite: (IptvChannel) -> Unit,
    contentFocus: FocusRequester,
    showGroup: Boolean = true,
) {
    if (channels.isEmpty()) {
        EmptyState("No channels in this group", "Choose another group or refresh your source.")
        return
    }
    val nowById = remember(state.catalog.programs) {
        val now = System.currentTimeMillis()
        state.catalog.programs.filter { it.startMillis <= now && it.endMillis > now }.associateBy(IptvProgram::channelKey)
    }
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("iptv-shell", 0) }
    var grid by remember { mutableStateOf(prefs.getBoolean("channelGrid", false)) }
    val card: @Composable (IptvChannel) -> Unit = { channel ->
            val program = nowById[channel.id]
            TvFocusableCard(onClick = { onPlay(channel) }, onLongClick = { onMenu(channel) },
                modifier = (if (channel == channels.first()) Modifier.focusRequester(contentFocus) else Modifier)
                    .liveFavoriteKey { onFavorite(channel) },
                focusScale = 1.02f, focusRing = false,
                accessibleLabel = listOfNotNull(channel.name, program?.title).joinToString(", ")) { focused ->
                Row(Modifier.fillMaxWidth().height(120.dp).clip(TvShape.Card)
                    .background(if (focused) Color.White else TvColor.Elevated)
                    .padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(90.dp, 78.dp).clip(TvShape.Card).background(TvColor.Elevated),
                        contentAlignment = Alignment.Center) {
                        TvArtwork(PosterRequest(channel.logo, 96), channel.name, Modifier.fillMaxSize(),
                            showTitleWhenMissing = false, contentScale = ContentScale.Fit)
                    }
                    Spacer(Modifier.width(20.dp))
                    Column(Modifier.weight(1f)) {
                        Text(channel.name, style = TvType.CardTitle.copy(fontSize = 27.sp, lineHeight = 34.sp,
                            fontWeight = FontWeight.SemiBold), color = if (focused) TvColor.Canvas else TvColor.TextPrimary,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(program?.title ?: channel.group, style = TvType.Meta,
                            color = if (focused) TvColor.Canvas.copy(alpha = 0.74f) else TvColor.TextSecondary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (channel.id in state.accounts.favoriteIds) {
                        Text("★", style = TvType.CardTitle, color = TvColor.Warning)
                        Spacer(Modifier.width(22.dp))
                    }
                    if (showGroup && !grid) {
                        Spacer(Modifier.width(24.dp))
                        Text(channel.group, style = TvType.Meta,
                            color = if (focused) TvColor.Canvas.copy(alpha = 0.74f) else TvColor.TextSecondary,
                            maxLines = 1, modifier = Modifier.width(230.dp))
                    }
                }
            }
    }
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(if (grid) "View: grid" else "View: list", onClick = {
                grid = !grid
                prefs.edit().putBoolean("channelGrid", grid).apply()
            })
        }
        Spacer(Modifier.height(16.dp))
        if (grid) LazyVerticalGrid(columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            gridItems(channels, key = IptvChannel::id) { card(it) }
        } else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(channels, key = IptvChannel::id) { card(it) }
        }
    }
}

@Composable
internal fun LiveVodList(vod: List<IptvVod>, onPlay: (IptvVod) -> Unit, contentFocus: FocusRequester) {
    if (vod.isEmpty()) {
        EmptyState("No videos in these sources", "Xtream accounts and supported M3U playlists can include movies and series.")
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(vod, key = IptvVod::id) { item ->
            TvFocusableCard(onClick = { onPlay(item) }, focusScale = 1.02f, focusRing = false,
                modifier = if (item == vod.first()) Modifier.focusRequester(contentFocus) else Modifier) { focused ->
                Row(Modifier.fillMaxWidth().height(110.dp).clip(TvShape.Card)
                    .background(if (focused) Color.White else TvColor.Elevated)
                    .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TvArtwork(PosterRequest(item.image, 128), item.title, Modifier.size(70.dp, 98.dp))
                    Spacer(Modifier.width(22.dp))
                    Column {
                        Text(item.title, style = TvType.CardTitle,
                            color = if (focused) TvColor.Canvas else TvColor.TextPrimary, maxLines = 1)
                        Text(item.category, style = TvType.Meta,
                            color = if (focused) TvColor.Canvas.copy(alpha = 0.74f) else TvColor.TextSecondary)
                    }
                }
            }
        }
    }
}
