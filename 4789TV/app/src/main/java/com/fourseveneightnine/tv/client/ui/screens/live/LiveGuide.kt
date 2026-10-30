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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.fourseveneightnine.tv.client.iptv.guideChannels
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
        state.accounts.hiddenGroups, state.accounts.lockedGroups, unlockedGroups) {
        state.guideChannels(unlockedGroups)
    }
    if (channels.isEmpty()) {
        EmptyState("The guide is empty", "Add an IPTV source to load channels and programs.")
        return
    }
    var selectedID by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = channels.firstOrNull { it.id == selectedID } ?: channels.first()
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
    var selectedProgramStart by rememberSaveable(selected.id) { mutableStateOf<Long?>(null) }
    val selectedProgram = programs.firstOrNull { it.startMillis == selectedProgramStart }
        ?: programs.firstOrNull { it.startMillis <= guideNow && it.endMillis > guideNow }
        ?: programs.firstOrNull()
    LaunchedEffect(selected.id) {
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
                    TvFocusableCard(onClick = { onChannel(channel) }, onFocused = { selectedID = channel.id },
                        modifier = (if (channel.id == selected.id) Modifier.focusRequester(firstFocus) else Modifier)
                            .liveFavoriteKey { onFavorite(channel) },
                        focusScale = 1.015f, focusRing = true) { focused ->
                        Row(Modifier.fillMaxWidth().height(84.dp).clip(TvShape.Card)
                            .background(if (focused) TvColor.Elevated2 else TvColor.Elevated)
                            .padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(channel.name, style = TvType.CardTitle.copy(fontSize = 24.sp),
                                color = TvColor.TextPrimary,
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
                    color = TvColor.TextSecondary)
            }
            Spacer(Modifier.height(14.dp))
            selectedProgram?.let { program ->
                Column(Modifier.fillMaxWidth().height(174.dp).clip(TvShape.Card).background(TvColor.Elevated).padding(20.dp)) {
                    Text(program.title, style = TvType.CardTitle, color = TvColor.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(program.description?.takeIf(String::isNotBlank) ?: "No program description was supplied.",
                        style = TvType.Meta, color = TvColor.TextSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(12.dp))
            }
            if (programs.isEmpty()) {
                Text("No program guide was supplied for this channel. Press OK on the channel to watch live.",
                    style = TvType.Body.copy(fontSize = 27.sp, lineHeight = 36.sp),
                    color = TvColor.TextSecondary)
            } else LazyColumn(state = scheduleState, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(programs, key = { "${it.channelKey}:${it.startMillis}" }) { program ->
                    val status = when {
                        program.startMillis > guideNow -> if ((state.sources.firstOrNull { it.id == selected.sourceId }?.maxConnections ?: 0) >= 2) "RECORD" else "UPCOMING"
                        program.endMillis > guideNow -> "WATCH LIVE"
                        else -> "PAST PROGRAM"
                    }
                    GuideProgramCard(program, selected, status, onClick = { onProgram(selected, program) },
                        showDescription = false, onFocused = { selectedProgramStart = program.startMillis })
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
    showDescription: Boolean = true,
    onFocused: () -> Unit = {},
) {
    val kind = remember(program.categories, program.title, channel.name, channel.group) {
        program.guideKind(channel)
    }
    val accent = TvColor.TextSecondary
    TvFocusableCard(
        onClick = onClick,
        onFocused = onFocused,
        modifier = modifier,
        focusScale = 1f,
        focusRing = true,
        accessibleLabel = "${kind.label}, $status, ${clock(program.startMillis)} to ${clock(program.endMillis)}, " +
            program.title + program.description?.let { ", $it" }.orEmpty(),
    ) { focused ->
        val foreground = TvColor.TextPrimary
        Column(
            Modifier.fillMaxWidth().clip(TvShape.Card)
                .background(if (focused) TvColor.Elevated2 else TvColor.Elevated)
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
                    .background(accent.copy(alpha = 0.12f))
                    .padding(horizontal = 10.dp, vertical = 5.dp)) {
                    Text(kind.label, style = TvType.Badge.copy(fontSize = 20.sp),
                        color = accent)
                }
                Text(program.title, style = TvType.CardTitle.copy(fontSize = 29.sp,
                    lineHeight = 36.sp, fontWeight = FontWeight.SemiBold), color = foreground,
                    modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (showDescription) program.description?.takeIf(String::isNotBlank)?.let { description ->
                Text(description, style = TvType.Body.copy(fontSize = 24.sp, lineHeight = 32.sp),
                    color = TvColor.TextSecondary, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun clock(value: Long): String = SimpleDateFormat("EEE h:mm a", Locale.getDefault()).format(Date(value))
