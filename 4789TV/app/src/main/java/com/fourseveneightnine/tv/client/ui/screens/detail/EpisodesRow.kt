@file:Suppress("OPT_IN_USAGE")

package com.fourseveneightnine.tv.client.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.client.data.library.ContinueItem
import com.fourseveneightnine.tv.client.data.meta.Episode
import com.fourseveneightnine.tv.client.ui.components.ChipKind
import com.fourseveneightnine.tv.client.ui.components.Skeleton
import com.fourseveneightnine.tv.client.ui.components.TvChip
import com.fourseveneightnine.tv.client.ui.components.TvFocusableWithMenu
import com.fourseveneightnine.tv.client.ui.components.TvProgressBar
import com.fourseveneightnine.tv.client.ui.components.SidePanel
import com.fourseveneightnine.tv.client.ui.components.SidePanelRow
import com.fourseveneightnine.tv.client.ui.components.accessibilityLabel
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvGeom
import com.fourseveneightnine.tv.client.ui.theme.TvShape
import com.fourseveneightnine.tv.client.ui.theme.TvSpace
import com.fourseveneightnine.tv.client.ui.theme.TvType
import kotlin.math.roundToInt

private val EpisodeArtworkWidth = 340.dp
private val EpisodeArtworkHeight = 192.dp

/** Spec §9.4 Seasons chips: 56 tall, "Specials" when season 0 exists, more than eight scroll. */
@Composable
internal fun SeasonsChips(
    seasons: List<Int>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (seasons.size <= 1) return
    LazyRow(
        modifier = modifier.fillMaxWidth().focusRestorer(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(start = TvGeom.RowEdgeInset, end = TvGeom.RowEdgeInset),
    ) {
        items(seasons, key = { it }) { season ->
            TvChip(
                label = DetailFormat.seasonLabel(season),
                kind = ChipKind.Choice,
                selected = season == selected,
                onClick = { onSelect(season) },
            )
        }
    }
}

/**
 * Spec §9.4 Episode card: a 300 × 169 still, the number and title on line 1, runtime and air date
 * on line 2, a progress bar at 163 and a watched tick top-right.
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
    onPlay: (Episode) -> Unit,
    onMenu: (Episode) -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    playTarget: PlayPick? = null,
    focusedEpisodeKey: String? = null,
    onEpisodeFocus: (Episode) -> Unit = {},
    returnFocusRequester: FocusRequester? = null,
    onReturnTargetPlaced: (String?) -> Unit = {},
) {
    if (loading) {
        SkeletonRow(modifier, width = EpisodeArtworkWidth, height = EpisodeArtworkHeight)
        return
    }
    if (episodes.isEmpty()) {
        EmptySeasonBlock(modifier)
        return
    }
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("tv_episode_order", 0) }
    var order by remember { mutableStateOf(prefs.getString("order", "Oldest episode") ?: "Oldest episode") }
    var showOrder by remember { mutableStateOf(false) }
    val selectedOrderFocus = remember { FocusRequester() }
    val ordered = remember(episodes, order) { DetailFormat.orderedEpisodes(episodes, order) }
    val contextEpisode = ordered.firstOrNull { "${it.season}:${it.episode}" == focusedEpisodeKey }
        ?: ordered.firstOrNull { it.season == playTarget?.season && it.episode == playTarget?.episode }
        ?: ordered.first()
    val initialIndex = ordered.indexOf(contextEpisode).coerceAtLeast(0)
    val rowState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    Column(modifier) {
        TvChip("Order: $order ▾", onClick = { showOrder = true })
        LazyRow(
            modifier = Modifier.fillMaxWidth().focusRestorer(),
            state = rowState,
            horizontalArrangement = Arrangement.spacedBy(TvSpace.S),
            contentPadding = PaddingValues(start = TvGeom.RowEdgeInset, top = 12.dp, end = 48.dp, bottom = 28.dp),
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
                    onPlay = { onPlay(episode) },
                    onMenu = { onMenu(episode) },
                    playTarget = playTarget?.takeIf { it.season == episode.season && it.episode == episode.episode },
                    onFocused = { onEpisodeFocus(episode) },
                    focusModifier = if (returnTarget && returnFocusRequester != null) Modifier.focusRequester(returnFocusRequester)
                        .onGloballyPositioned { onReturnTargetPlaced(key) } else Modifier,
                )
            }
        }
        // Already-loaded data only. A focused episode never triggers a network request.
        val unaired = DetailFormat.isUnaired(contextEpisode.released, todayIso)
        val status = when {
            unaired -> "Airs ${DetailFormat.airDate(contextEpisode.released) ?: "soon"}"
            DetailPlay.isWatched(progress, contextEpisode.season, contextEpisode.episode) -> "Watched"
            DetailPlay.episodeProgress(progress, contextEpisode.season, contextEpisode.episode) != null ->
                "Resume at ${DetailFormat.duration(progress?.positionMs ?: 0L)}"
            else -> "Not started"
        }
        Text(
            "S${contextEpisode.season} E${contextEpisode.episode} · ${contextEpisode.title} · $status",
            style = TvType.CardTitle,
            color = TvColor.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(1400.dp),
        )
        Text(
            if (unaired) "Episode details are hidden until release."
            else contextEpisode.overview?.takeIf(String::isNotBlank) ?: "No episode synopsis supplied.",
            style = TvType.Meta.copy(fontSize = 25.sp, lineHeight = 32.sp),
            color = TvColor.TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(1400.dp).height(64.dp),
        )
    }
    if (showOrder) {
        Dialog(onDismissRequest = { showOrder = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        SidePanel(header = "Episode order", onClose = { showOrder = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("Oldest episode", "Latest episode", "Latest air date").forEach { value ->
                    SidePanelRow(value, order == value, onClick = {
                        order = value
                        prefs.edit().putString("order", value).apply()
                        showOrder = false
                    }, modifier = if (order == value) Modifier.focusRequester(selectedOrderFocus) else Modifier)
                }
            }
        }
        LaunchedEffect(Unit) { runCatching { selectedOrderFocus.requestFocus() } }
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
    onPlay: () -> Unit,
    onMenu: () -> Unit,
    playTarget: PlayPick?,
    onFocused: () -> Unit,
    focusModifier: Modifier,
) {
    val airingLine = if (unaired) {
        "Airs ${DetailFormat.airDate(episode.released) ?: "soon"}"
    } else {
        listOfNotNull(
            DetailFormat.airDate(episode.released),
            DetailFormat.runtime(showRuntimeMinutes)?.let { "Typical $it" },
        ).joinToString(" · ").ifBlank { "Release date and runtime not supplied" }
    }
    Column(Modifier.width(EpisodeArtworkWidth)) {
        TvFocusableWithMenu(
            onClick = if (unaired) onMenu else onPlay,
            onLongClick = onMenu,
            accessibleLabel = accessibilityLabel(
                "Season ${episode.season}, episode ${episode.episode}",
                episode.title,
                airingLine,
                if (watched) "Watched" else progress?.let { "${(it * 100).roundToInt()} percent watched" },
            ),
            clickLabel = if (unaired) "Episode information" else "Play episode",
            longClickLabel = "Episode options",
            cornerRadius = 16.dp,
            focusScale = 1f,
            focusRing = false,
            modifier = focusModifier.onFocusChanged { if (it.isFocused) onFocused() },
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
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = episode.episode.toString(),
                style = TvType.data(25),
                color = TvColor.TextSecondary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = episode.title,
                style = TvType.ShelfHeader.copy(fontSize = 29.sp, lineHeight = 36.sp),
                color = TvColor.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
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
