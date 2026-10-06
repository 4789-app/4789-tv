package com.fourseveneightnine.tv.client.ui.screens.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.client.iptv.IptvChannel
import com.fourseveneightnine.tv.client.iptv.IptvProgram
import com.fourseveneightnine.tv.client.iptv.IptvGuideKind
import com.fourseveneightnine.tv.client.iptv.IptvState
import com.fourseveneightnine.tv.client.iptv.guideKind
import com.fourseveneightnine.tv.client.iptv.guideChannelHint
import com.fourseveneightnine.tv.client.ui.components.EmptyState
import com.fourseveneightnine.tv.client.ui.components.TvFocusableCard
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvShape
import com.fourseveneightnine.tv.client.ui.theme.TvType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/** Two-pane guide: channel focus on the left, its complete cached schedule on the right. */
@Composable
internal fun LiveGuide(
    state: IptvState, firstFocus: FocusRequester, unlockedGroups: Set<String>,
    onChannel: (IptvChannel) -> Unit, onFavorite: (IptvChannel) -> Unit,
    onProgram: (IptvChannel, IptvProgram) -> Unit,
) {
    val channels = remember(state.catalog.channels, state.accounts.groupOrder, state.sources,
        state.accounts.lockedGroups, unlockedGroups) {
        val order = state.orderedGroups.withIndex().associate { it.value to it.index }
        state.channels.filter { it.group !in state.accounts.lockedGroups || it.group in unlockedGroups }
            .sortedBy { order[it.group] ?: Int.MAX_VALUE }
    }
    if (channels.isEmpty()) {
        EmptyState("The guide is empty", "Add an IPTV source to load channels and programs.")
        return
    }
    var selected by remember(state.catalog.channels) { mutableStateOf(channels.first()) }
    val programs = remember(state.catalog, selected.id) {
        state.catalog.programsByChannel[selected.id].orEmpty()
    }
    var guideNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L)
            guideNow = System.currentTimeMillis()
        }
    }
    val scheduleState = rememberLazyListState()
    LaunchedEffect(selected.id, programs.size) {
        val now = System.currentTimeMillis()
        val current = programs.indexOfFirst { it.endMillis > now }
        if (current > 0) scheduleState.scrollToItem(current)
    }

    Row(Modifier.fillMaxWidth().fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(Modifier.width(475.dp)) {
            Text("Channels", style = TvType.ShelfHeader, color = TvColor.TextPrimary)
            Spacer(Modifier.height(14.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(channels, key = IptvChannel::id) { channel ->
                    TvFocusableCard(onClick = { onChannel(channel) }, onFocused = { selected = channel },
                        modifier = (if (channel == channels.first()) Modifier.focusRequester(firstFocus) else Modifier)
                            .liveFavoriteKey { onFavorite(channel) },
                        focusScale = 1.015f, focusRing = false) { focused ->
                        Row(Modifier.fillMaxWidth().height(84.dp).clip(TvShape.Card)
                            .background(if (focused) Color.White else TvColor.Elevated)
                            .padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(channel.name, style = TvType.CardTitle.copy(fontSize = 24.sp),
                                color = if (focused) TvColor.Canvas else TvColor.TextPrimary,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(selected.name, style = TvType.PlateTitle, color = TvColor.TextPrimary,
                maxLines = 2)
            selected.guideChannelHint()?.let { hint ->
                Text(hint.label, style = TvType.Badge.copy(fontSize = 22.sp),
                    color = if (hint == IptvGuideKind.MovieChannel) TvColor.Warning else TvColor.Cached)
            }
            Spacer(Modifier.height(14.dp))
            if (programs.isEmpty()) {
                Text("No program guide was supplied for this channel. Press OK on the channel to watch live.",
                    style = TvType.Body.copy(fontSize = 27.sp, lineHeight = 36.sp),
                    color = TvColor.TextSecondary)
            } else LazyColumn(state = scheduleState, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(programs, key = { "${it.channelKey}:${it.startMillis}" }) { program ->
                    val status = when {
                        program.startMillis > guideNow -> "UPCOMING"
                        program.endMillis > guideNow -> "ON NOW"
                        else -> "REPLAY"
                    }
                    GuideProgramCard(program, selected, status, onClick = { onProgram(selected, program) })
                }
            }
        }
    }
}

/** Complete guide copy stays readable at TV distance; the card grows to fit a long synopsis. */
@Composable
internal fun GuideProgramCard(
    program: IptvProgram,
    channel: IptvChannel,
    status: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val kind = remember(program.categories, program.title, channel.name, channel.group) {
        program.guideKind(channel)
    }
    val accent = when (kind) {
        IptvGuideKind.Movie, IptvGuideKind.MovieChannel -> TvColor.Warning
        IptvGuideKind.Sports, IptvGuideKind.SportsChannel -> TvColor.Cached
        IptvGuideKind.News -> TvColor.Accent
        IptvGuideKind.Series -> TvColor.CollectionAccents[2]
        IptvGuideKind.Program -> TvColor.TextSecondary
    }
    TvFocusableCard(
        onClick = onClick,
        modifier = modifier,
        focusScale = 1f,
        focusRing = false,
        accessibleLabel = "${kind.label}, $status, ${clock(program.startMillis)} to ${clock(program.endMillis)}, " +
            program.title + program.description?.let { ", $it" }.orEmpty(),
    ) { focused ->
        val foreground = if (focused) TvColor.Canvas else TvColor.TextPrimary
        Column(
            Modifier.fillMaxWidth().clip(TvShape.Card)
                .background(if (focused) Color.White else TvColor.Elevated2)
                .padding(horizontal = 22.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("${clock(program.startMillis)} – ${clock(program.endMillis)}",
                    style = TvType.Meta.copy(fontSize = 23.sp, fontWeight = FontWeight.Medium),
                    color = foreground, modifier = Modifier.weight(1f))
                Text(status, style = TvType.Badge.copy(fontSize = 20.sp), color = foreground)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clip(TvShape.Badge)
                    .background(if (focused) TvColor.Canvas.copy(alpha = 0.10f) else accent.copy(alpha = 0.15f))
                    .padding(horizontal = 10.dp, vertical = 5.dp)) {
                    Text(kind.label, style = TvType.Badge.copy(fontSize = 20.sp),
                        color = if (focused) TvColor.Canvas else accent)
                }
                Text(program.title, style = TvType.CardTitle.copy(fontSize = 29.sp,
                    lineHeight = 36.sp, fontWeight = FontWeight.SemiBold), color = foreground,
                    modifier = Modifier.weight(1f))
            }
            program.description?.takeIf(String::isNotBlank)?.let { description ->
                Text(description, style = TvType.Body.copy(fontSize = 24.sp, lineHeight = 32.sp),
                    color = if (focused) TvColor.Canvas.copy(alpha = 0.82f) else TvColor.TextSecondary)
            }
        }
    }
}

private fun clock(value: Long): String = SimpleDateFormat("EEE h:mm a", Locale.getDefault()).format(Date(value))
