@file:Suppress("OPT_IN_USAGE")

package com.fourseveneightnine.tv.client.ui.screens.detail

import com.fourseveneightnine.tv.client.data.meta.episodeDisplayTitle
import androidx.compose.foundation.background
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.border
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.client.data.library.ContinueItem
import com.fourseveneightnine.tv.client.data.meta.Episode
import com.fourseveneightnine.tv.client.ui.components.Skeleton
import com.fourseveneightnine.tv.client.ui.components.TvChip
import com.fourseveneightnine.tv.client.ui.components.TvFocusable
import com.fourseveneightnine.tv.client.ui.components.TvFocusableWithMenu
import com.fourseveneightnine.tv.client.ui.components.TvProgressBar
import com.fourseveneightnine.tv.client.ui.components.SidePanelRow
import com.fourseveneightnine.tv.client.ui.components.accessibilityLabel
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvGeom
import com.fourseveneightnine.tv.client.ui.theme.TvShape
import com.fourseveneightnine.tv.client.ui.theme.TvSpace
import com.fourseveneightnine.tv.client.ui.theme.TvType
import kotlin.math.roundToInt

private val EpisodeArtworkWidth = 406.dp
private val EpisodeArtworkHeight = EpisodeArtworkWidth * 9f / 16f

/** Underlined season tabs keep selection distinct from the remote's focus outline. */
@Composable
internal fun SeasonsChips(
    seasons: List<Int>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    upFocus: FocusRequester,
    downFocus: FocusRequester?,
    modifier: Modifier = Modifier,
    selectedFocus: FocusRequester,
) {
    if (seasons.size <= 1) return
    val focusSeason = selected?.takeIf { it in seasons } ?: seasons.first()
    LazyRow(
        modifier = modifier.fillMaxWidth().focusRestorer(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(start = TvGeom.RowEdgeInset, end = TvGeom.RowEdgeInset),
    ) {
        items(seasons, key = { it }) { season ->
            TvFocusable(
                accessibleLabel = DetailFormat.seasonLabel(season),
                accessibleRole = Role.Tab,
                selected = season == selected,
                onClick = { onSelect(season) },
                focusScale = 1f,
                modifier = (if (season == focusSeason) Modifier.focusRequester(selectedFocus) else Modifier).focusProperties {
                    up = upFocus
                    downFocus?.let { down = it }
                },
            ) { focused ->
                Box(Modifier.width(170.dp).height(60.dp).clip(TvShape.Card)
                    .background(if (focused) TvColor.Elevated2 else Color.Transparent)) {
                    Text(DetailFormat.seasonLabel(season), style = TvType.ControlLabel,
                        color = if (season == selected || focused) TvColor.TextPrimary else TvColor.TextSecondary,
                        modifier = Modifier.align(Alignment.Center))
                    if (season == selected) Box(Modifier.align(Alignment.BottomCenter)
                        .fillMaxWidth().height(3.dp).background(TvColor.Accent))
                }
            }
        }
    }
}

/**
 * Spec §9.4 Episode card: a 406 px, 16:9 still, a wrapping title, separate runtime,
 * a progress bar along the image edge and a watched tick top-right.
 *
 * The screen owns its single return requester. Only the saved, laid-out episode attaches it;
 * disposal clears readiness before another request. No off-screen item is asked for focus.
 */
@Composable
internal fun EpisodesRow(
    episodes: List<Episode>,
    progress: ContinueItem?,
    showRuntimeMinutes: Int?,
    todayIso: String,
    onSources: (Episode) -> Unit,
    onMenu: (Episode) -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    playTarget: PlayPick? = null,
    focusedEpisodeKey: String? = null,
    onEpisodeFocus: (Episode) -> Unit = {},
    returnFocusRequester: FocusRequester? = null,
    onReturnTargetPlaced: (String?) -> Unit = {},
    orderFocus: FocusRequester,
    upFocus: FocusRequester,
    episodesFocus: FocusRequester,
    seasons: List<Int>,
    selectedSeason: Int?,
    onSeason: (Int) -> Unit,
    seasonsFocus: FocusRequester,
) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("tv_episode_order", 0) }
    var order by remember { mutableStateOf(prefs.getString("order", "Oldest episode") ?: "Oldest episode") }
    var showOrder by remember { mutableStateOf(false) }
    var orderChipHeight by remember { mutableStateOf(0) }
    val tvDensity = LocalDensity.current
    val orderGap = with(tvDensity) { 12.dp.roundToPx() }
    val selectedOrderFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    fun closeOrder() {
        showOrder = false
        scope.launch { withFrameNanos { }; runCatching { orderFocus.requestFocus() } }
    }
    val ordered = remember(episodes, order) { DetailFormat.orderedEpisodes(episodes, order) }
    val contextEpisode = ordered.firstOrNull { "${it.season}:${it.episode}" == focusedEpisodeKey }
        ?: ordered.firstOrNull { it.season == playTarget?.season && it.episode == playTarget?.episode }
        ?: ordered.firstOrNull()
    val initialIndex = ordered.indexOf(contextEpisode).coerceAtLeast(0)
    val rowState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    LaunchedEffect(selectedSeason, order, loading) {
        if (!loading && ordered.isNotEmpty()) rowState.scrollToItem(initialIndex)
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(end = 96.dp).height(60.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            if (seasons.size > 1) {
                SeasonsChips(seasons, selectedSeason, onSeason, upFocus, episodesFocus.takeIf { !loading && ordered.isNotEmpty() },
                    Modifier.weight(1f), selectedFocus = seasonsFocus)
            } else {
                Text("${selectedSeason?.let(DetailFormat::seasonLabel) ?: "Episodes"} · Episodes",
                    style = TvType.ShelfHeader, color = TvColor.TextPrimary, modifier = Modifier.weight(1f))
            }
            Box {
                TvChip("Order: $order ▾", onClick = { showOrder = true },
                    modifier = Modifier.focusRequester(orderFocus).onSizeChanged { orderChipHeight = it.height }
                        .focusProperties {
                            up = upFocus
                            down = if (!loading && ordered.isNotEmpty()) episodesFocus else FocusRequester.Cancel
                            if (seasons.size > 1) left = seasonsFocus
                        })
                if (showOrder) {
                    Popup(
                        alignment = Alignment.TopEnd,
                        offset = IntOffset(0, orderChipHeight + orderGap),
                        onDismissRequest = ::closeOrder,
                        properties = PopupProperties(focusable = true),
                    ) {
                        CompositionLocalProvider(LocalDensity provides tvDensity) {
                            Column(
                                Modifier.width(420.dp)
                                    .clip(TvShape.Panel)
                                    .background(TvColor.Elevated)
                                    .border(1.dp, TvColor.Border, TvShape.Panel)
                                    .semantics { paneTitle = "Episode order" }
                                    .onPreviewKeyEvent { event ->
                                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                                            closeOrder()
                                            true
                                        } else false
                                    }
                                    .padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text("Episode order", style = TvType.PanelHeader.copy(fontSize = 28.sp, lineHeight = 34.sp),
                                    color = TvColor.TextPrimary)
                                Spacer(Modifier.height(2.dp))
                                listOf("Oldest episode", "Latest episode", "Latest air date").forEach { value ->
                                    SidePanelRow(value, order == value, onClick = {
                                        order = value
                                        prefs.edit().putString("order", value).apply()
                                        closeOrder()
                                    }, modifier = if (order == value) Modifier.focusRequester(selectedOrderFocus) else Modifier,
                                        width = 380.dp, height = 60.dp)
                                }
                            }
                            LaunchedEffect(Unit) {
                                withFrameNanos { }
                                runCatching { selectedOrderFocus.requestFocus() }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        when {
            loading -> SkeletonRow(Modifier.height(295.dp), width = EpisodeArtworkWidth, height = EpisodeArtworkHeight)
            ordered.isEmpty() -> EmptySeasonBlock(Modifier.height(295.dp))
            else -> LazyRow(
            modifier = Modifier.fillMaxWidth().padding(end = 96.dp).focusRequester(episodesFocus).focusRestorer(),
            state = rowState,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            contentPadding = PaddingValues(horizontal = TvGeom.RowEdgeInset, vertical = 12.dp),
        ) {
            items(ordered, key = { "${it.season}:${it.episode}" }) { episode ->
                val key = "${episode.season}:${episode.episode}"
                val returnTarget = key == focusedEpisodeKey && returnFocusRequester != null
                if (returnTarget) DisposableEffect(key) {
                    onDispose { onReturnTargetPlaced(null) }
                }
                EpisodeCard(
                    episode = episode,
                    watched = DetailPlay.isWatched(progress, episode.season, episode.episode),
                    progress = DetailPlay.episodeProgress(progress, episode.season, episode.episode),
                    showRuntimeMinutes = showRuntimeMinutes,
                    unaired = DetailFormat.isUnaired(episode.released, todayIso),
                    onSources = { onSources(episode) },
                    onMenu = { onMenu(episode) },
                    playTarget = playTarget?.takeIf { it.season == episode.season && it.episode == episode.episode },
                    onFocused = { onEpisodeFocus(episode) },
                    focusModifier = (if (returnTarget && returnFocusRequester != null) Modifier.focusRequester(returnFocusRequester)
                        .onGloballyPositioned { onReturnTargetPlaced(key) } else Modifier)
                        .focusProperties { up = if (seasons.size > 1) seasonsFocus else orderFocus },
                )
            }
        }
        }
    }

}

@Composable
private fun EpisodeCard(
    episode: Episode,
    watched: Boolean,
    progress: Float?,
    showRuntimeMinutes: Int?,
    unaired: Boolean,
    onSources: () -> Unit,
    onMenu: () -> Unit,
    playTarget: PlayPick?,
    onFocused: () -> Unit,
    focusModifier: Modifier,
) {
    val bringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val airingLine = if (unaired) {
        "Airs ${DetailFormat.airDate(episode.released) ?: "soon"}"
    } else {
        DetailFormat.runtime(showRuntimeMinutes)?.let { "Typical $it" }
            ?: DetailFormat.airDate(episode.released) ?: "Runtime not supplied"
    }
    Column(Modifier.width(EpisodeArtworkWidth).bringIntoViewRequester(bringIntoView).padding(bottom = 54.dp)) {
        TvFocusableWithMenu(
            onClick = if (unaired) onMenu else onSources,
            onLongClick = onMenu,
            accessibleLabel = accessibilityLabel(
                "Season ${episode.season}, episode ${episode.episode}",
                episodeDisplayTitle(episode.title),
                airingLine,
                DetailFormat.airDate(episode.released),
                if (watched) "Watched" else progress?.let { "${(it * 100).roundToInt()} percent watched" },
            ),
            clickLabel = if (unaired) "Episode information" else "Choose episode source",
            longClickLabel = "Episode options",
            cornerRadius = 16.dp,
            focusScale = 1f,
            focusRing = false,
            modifier = focusModifier.onFocusChanged {
                if (it.isFocused) {
                    onFocused()
                    scope.launch { withFrameNanos { }; bringIntoView.bringIntoView() }
                }
            },
        ) { focused ->
            Box(
                Modifier
                    .size(EpisodeArtworkWidth, EpisodeArtworkHeight)
                    .clip(TvShape.CardProminent)
                    .background(TvColor.PosterPlaceholder),
            ) {
                DetailArtwork(
                    url = episode.thumbnail.takeUnless { unaired },
                    targetWidth = 780,
                    modifier = Modifier.fillMaxWidth().height(EpisodeArtworkHeight),
                    // §9.9: a missing still draws the episode number, not an empty box.
                    fallback = episode.episode.toString(),
                )
                if (progress != null) {
                    TvProgressBar(
                        progress = progress,
                        height = 6.dp,
                        modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
                    )
                }
                if (watched) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(TvColor.Canvas.copy(alpha = 0.70f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("✓", style = TvType.Badge, color = TvColor.Cached)
                    }
                }
                if (playTarget != null && !unaired) {
                    Text(
                        if (playTarget.resumeFromMs != null) "Resume" else "Up next",
                        style = TvType.Badge,
                        color = TvColor.Canvas,
                        modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
                            .clip(TvShape.Badge).background(TvColor.Selection).padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                if (focused) Box(
                    Modifier.fillMaxWidth().height(EpisodeArtworkHeight)
                        .border(3.dp, Color.White.copy(alpha = 0.95f), TvShape.CardProminent),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = episode.episode.toString(),
                style = TvType.data(25),
                color = TvColor.TextSecondary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = episodeDisplayTitle(episode.title),
                style = TvType.ShelfHeader.copy(fontSize = 28.sp, lineHeight = 30.sp),
                color = TvColor.TextPrimary,
                modifier = Modifier.heightIn(min = 68.dp),
            )
        }
        Text(
            text = airingLine,
            style = TvType.Meta.copy(fontSize = 24.sp, lineHeight = 30.sp),
            color = TvColor.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The selected episode uses only metadata already loaded for the current season. */
@Composable
internal fun EpisodeSummary(episode: Episode, progress: ContinueItem?, runtimeMinutes: Int?, todayIso: String) {
    val unaired = DetailFormat.isUnaired(episode.released, todayIso)
    val status = when {
        unaired -> "Airs ${DetailFormat.airDate(episode.released) ?: "soon"}"
        DetailPlay.isWatched(progress, episode.season, episode.episode) -> "Watched"
        DetailPlay.episodeProgress(progress, episode.season, episode.episode) != null ->
            "Resume at ${DetailFormat.duration(progress?.positionMs ?: 0L)}"
        else -> "Not started"
    }
    Text("S${episode.season} E${episode.episode} · ${episodeDisplayTitle(episode.title)}",
        style = TvType.ShelfHeader.copy(fontSize = 32.sp, lineHeight = 38.sp),
        color = TvColor.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(8.dp))
    Text(if (unaired) status else listOfNotNull(DetailFormat.airDate(episode.released),
        DetailFormat.runtime(runtimeMinutes)?.let { "Typical $it" }, status).joinToString(" · "),
        style = TvType.Meta, color = TvColor.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(8.dp))
    Text(if (unaired) "Episode details are hidden until release."
        else episode.overview?.takeIf(String::isNotBlank) ?: "No episode synopsis supplied.",
        style = TvType.Body.copy(fontSize = 28.sp, lineHeight = 34.sp),
        color = TvColor.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/** Spec §9.8 Empty: one 960 × 169 block, not an empty rail. */
@Composable
private fun EmptySeasonBlock(modifier: Modifier = Modifier) {
    Box(
        modifier
            .width(960.dp)
            .height(TvGeom.WideCardHeight)
            .clip(TvShape.Card)
            .background(TvColor.Elevated),
        contentAlignment = Alignment.Center,
    ) {
        Text("No episodes listed for this season.", style = TvType.Body, color = TvColor.TextSecondary)
    }
}

/** Five skeleton cards, and only the first two sweep (§15.4, §18.11). */
@Composable
internal fun SkeletonRow(
    modifier: Modifier = Modifier,
    width: androidx.compose.ui.unit.Dp = TvGeom.PosterWidth,
    height: androidx.compose.ui.unit.Dp = TvGeom.PosterHeight,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(TvSpace.S)) {
        repeat(5) { index ->
            Skeleton(Modifier.size(width, height), sweep = index < 2)
        }
    }
}
