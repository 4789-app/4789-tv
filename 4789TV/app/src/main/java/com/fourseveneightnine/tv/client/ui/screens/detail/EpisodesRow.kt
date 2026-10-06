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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * **No `FocusRequester` inside these items.** An off-screen lazy item's requester is unattached
 * and `requestFocus()` on it kills the remote — the exact fault in
 * `tv-dpad-focus-destroyed-by-loading-shelf.md`. The row restores its own focus instead.
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
    var order by remember { mutableStateOf(prefs.getString("order", "Latest episode") ?: "Latest episode") }
    val ordered = remember(episodes, order) { DetailFormat.orderedEpisodes(episodes, order) }
    val rowState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("Latest episode", "Latest air date", "Oldest episode").forEach { value ->
                TvChip(value, kind = ChipKind.Choice, selected = order == value, onClick = {
                    if (order != value) scope.launch { rowState.scrollToItem(0) }
                    order = value
                    prefs.edit().putString("order", value).apply()
                })
            }
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth().focusRestorer(),
            state = rowState,
            horizontalArrangement = Arrangement.spacedBy(TvSpace.S),
            contentPadding = PaddingValues(start = TvGeom.RowEdgeInset, top = 12.dp, end = 48.dp, bottom = 28.dp),
        ) {
            items(ordered, key = { "${it.season}:${it.episode}" }) { episode ->
                EpisodeCard(
                    episode = episode,
                    watched = DetailPlay.isWatched(progress, episode.season, episode.episode),
                    progress = DetailPlay.episodeProgress(progress, episode.season, episode.episode),
                    showRuntimeMinutes = showRuntimeMinutes,
                    unaired = DetailFormat.isUnaired(episode.released, todayIso),
                    onPlay = { onPlay(episode) },
                    onMenu = { onMenu(episode) },
                )
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
    onPlay: () -> Unit,
    onMenu: () -> Unit,
) {
    val airingLine = if (unaired) {
        "Airs ${DetailFormat.airDate(episode.released) ?: "soon"}"
    } else {
        DetailFormat.episodeLine(showRuntimeMinutes, episode.released)
    }
    Column(Modifier.width(EpisodeArtworkWidth)) {
        TvFocusableWithMenu(
            onClick = onPlay,
            onLongClick = onMenu,
            accessibleLabel = accessibilityLabel(
                "Season ${episode.season}, episode ${episode.episode}",
                episode.title,
                airingLine,
                if (watched) "Watched" else progress?.let { "${(it * 100).roundToInt()} percent watched" },
            ),
            clickLabel = "Play episode",
            longClickLabel = "Episode options",
            cornerRadius = 16.dp,
            focusScale = 1.04f,
            focusRing = false,
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
            color = TvColor.TextMuted,
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
