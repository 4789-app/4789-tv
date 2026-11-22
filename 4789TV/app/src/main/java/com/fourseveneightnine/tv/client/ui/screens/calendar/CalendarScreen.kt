@file:Suppress("OPT_IN_USAGE")

package com.fourseveneightnine.tv.client.ui.screens.calendar

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fourseveneightnine.tv.client.ClientGraph
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.data.images.PosterRequest
import com.fourseveneightnine.tv.client.data.meta.Episode
import com.fourseveneightnine.tv.client.ui.LocalShellState
import com.fourseveneightnine.tv.client.ui.components.ButtonKind
import com.fourseveneightnine.tv.client.ui.components.SidePanel
import com.fourseveneightnine.tv.client.ui.components.Skeleton
import com.fourseveneightnine.tv.client.ui.components.StateBlock
import com.fourseveneightnine.tv.client.ui.components.TvArtwork
import com.fourseveneightnine.tv.client.ui.components.TvButton
import com.fourseveneightnine.tv.client.ui.components.TvFocusable
import com.fourseveneightnine.tv.client.ui.components.ContentTransition
import com.fourseveneightnine.tv.client.ui.components.PanelTransition
import com.fourseveneightnine.tv.client.ui.nav.ClientNav
import com.fourseveneightnine.tv.client.ui.screens.search.RailEdge
import com.fourseveneightnine.tv.client.ui.screens.streams.StreamsDrawer
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvGeom
import com.fourseveneightnine.tv.client.ui.theme.TvShape
import com.fourseveneightnine.tv.client.ui.theme.TvSpace
import com.fourseveneightnine.tv.client.ui.theme.TvType
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val THUMB_WIDTH = 44
private const val THUMB_HEIGHT = 66

/**
 * The pixel widths the calendar asks Coil for, spec §13.2 and §13.3.
 *
 * `PosterRequest.poster` pins every request to 342 px. The grid draws 42 cells of three 44 px
 * thumbs, so up to 126 bitmaps were decoding at nearly eight times the width they are drawn at —
 * about 44 MB against the 180 MB the plan allows the whole app. 92 px is the smallest TMDB rung
 * above the drawn size, and the day panel's 120 px still needs 154, not 780.
 */
private const val THUMB_REQUEST_WIDTH = 92
private const val PANEL_STILL_REQUEST_WIDTH = 154

/** One title the calendar asks about. Bounded, because every one of these is a metadata call. */
private const val MAX_TITLES = 40
private const val META_DEADLINE_MILLIS = 60_000L
private const val CALENDAR_CACHE_LIFE_MILLIS = 30L * 60 * 1000

private sealed interface CalendarPhase {
    data object Loading : CalendarPhase
    data class Loaded(
        val episodes: List<CalendarEpisode>,
        val incomplete: Boolean = false,
        val discoveryAvailable: Boolean = true,
    ) : CalendarPhase
    data object Failed : CalendarPhase
}

/** Keep loaded days with their navigation entry while Sources or the player is above Calendar. */
private class CalendarMemory : ViewModel() {
    val phase = mutableStateOf<CalendarPhase>(CalendarPhase.Loading)
    val sourceEpisode = mutableStateOf<CalendarEpisode?>(null)
    var completedInputs: List<Any?>? = null
    var loadedAtMillis = 0L
}

/**
 * Calendar, spec §13.
 *
 * Popular English, Indian and South Korean shows, plus series in the viewer's library.
 * Published dates come from fresh TMDB season schedules, with installed add-ons as a fallback.
 */
@Composable
internal fun CalendarScreen(nav: ClientNav) {
    val context = LocalContext.current
    val client = remember(context) { context.clientGraph }
    val services by client.services.collectAsState()
    val shell = LocalShellState.current

    val today = remember { LocalDate.now() }
    var monthValue by rememberSaveable { mutableStateOf(YearMonth.from(today).toString()) }
    val month = YearMonth.parse(monthValue)
    val memory: CalendarMemory = viewModel { CalendarMemory() }
    var phase by memory.phase
    var sourceEpisode by memory.sourceEpisode
    var retryToken by rememberSaveable { mutableIntStateOf(0) }
    var selectedDateValue by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedDate = selectedDateValue?.let(LocalDate::parse)
    var selectedEpisodeKey by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(services, month, retryToken) {
        val ready = services
        // F59: ten collections is ten Room subscriptions and ten list maps. None of that belongs
        // on the dispatcher that answers the remote.
        val titles = if (ready == null) emptyList() else withContext(Dispatchers.IO) { followedSeries(client) }
        val inputs = listOf(ready, month, retryToken, titles)
        val loadedAgeMillis = System.currentTimeMillis() - memory.loadedAtMillis
        if (memory.completedInputs == inputs && phase is CalendarPhase.Loaded &&
            loadedAgeMillis in 0L until CALENDAR_CACHE_LIFE_MILLIS
        ) return@LaunchedEffect
        if (ready == null) {
            phase = CalendarPhase.Loaded(emptyList())
            memory.completedInputs = inputs
            memory.loadedAtMillis = System.currentTimeMillis()
            return@LaunchedEffect
        }
        phase = CalendarPhase.Loading
        val loaded = withTimeoutOrNull(META_DEADLINE_MILLIS) { loadEpisodes(ready, titles, month) }
        phase = loaded ?: CalendarPhase.Failed
        if (loaded != null) {
            memory.completedInputs = inputs
            memory.loadedAtMillis = System.currentTimeMillis()
        }
    }

    val loaded = phase as? CalendarPhase.Loaded
    val episodes = loaded?.episodes.orEmpty()
    val cells = remember(month, episodes) { CalendarMonth.grid(month, episodes) }
    val empty = loaded != null && episodes.isEmpty()
    val failed = phase is CalendarPhase.Failed || empty && loaded?.incomplete == true
    val showsGrid = !failed && !empty

    // The 42 cells are all composed, always, so a requester on one of them is never unattached —
    // the trap that killed the remote on the Tamil MV screen.
    val anchor = remember { FocusRequester() }
    val statusFocus = remember { FocusRequester() }
    var anchorIndex by rememberSaveable { mutableIntStateOf(CalendarMonth.initialFocusIndex(cells, today)) }
    var focusedIndex by rememberSaveable { mutableIntStateOf(anchorIndex) }

    DisposableEffect(showsGrid) {
        val restore = {
            runCatching { if (showsGrid) anchor.requestFocus() else statusFocus.requestFocus() }
            Unit
        }
        shell.restoreContentFocus = restore
        onDispose { if (shell.restoreContentFocus === restore) shell.restoreContentFocus = null }
    }
    // Focus moves on a real navigation only: entering the screen, changing month, or replacing the
    // loading grid with one terminal state action.
    LaunchedEffect(month, anchorIndex, showsGrid) {
        if (selectedDate == null) {
            runCatching { if (showsGrid) anchor.requestFocus() else statusFocus.requestFocus() }
        }
    }

    fun changeMonth(delta: Long) {
        val next = month.plusMonths(delta)
        val nextCells = CalendarMonth.grid(next, episodes)
        anchorIndex = CalendarMonth.keepColumn(nextCells, focusedIndex)
        monthValue = next.toString()
    }

    BackHandler(enabled = selectedDate != null) {
        selectedDateValue = null
        runCatching { anchor.requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(TvColor.Background)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Menu) {
                    nav.openRail()
                    true
                } else {
                    false
                }
            },
    ) {
        // Spec §13.2 measures the column from the safe top: the title band is y 54..120, the
        // weekday header y 132..170 and the grid y 180..1012. Every height here is fixed, so the
        // grid never moves when the status line changes what it says.
        Column(Modifier.fillMaxSize().padding(start = TvGeom.ContentLeft, top = TvGeom.SafeTop, end = 96.dp)) {
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth().height(CalendarMonth.Layout.TITLE_BAND_HEIGHT.dp),
            ) {
                Text("Calendar", style = TvType.ScreenTitle, color = TvColor.TextPrimary, maxLines = 1)
                Spacer(Modifier.width(TvSpace.M))
                Text(
                    CalendarMonth.monthLabel(month),
                    style = TvType.PlateTitle,
                    color = TvColor.TextSecondary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f).padding(top = 6.dp),
                )
                TvButton("Refresh", { retryToken++ }, kind = ButtonKind.Secondary, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.width(TvSpace.S))
                TvButton("Prev", { changeMonth(-1) }, kind = ButtonKind.Secondary, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.width(TvSpace.S))
                TvButton("Next", { changeMonth(1) }, kind = ButtonKind.Secondary, modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(CalendarMonth.Layout.TITLE_TO_HEADER_GAP.dp))
            if (showsGrid) {
                Row(Modifier.fillMaxWidth()) {
                    RailEdge(onOpenRail = nav::openRail, modifier = Modifier.height((CalendarMonth.Layout.ROW_PITCH * CalendarMonth.ROWS).dp))
                    Column {
                        WeekdayHeader()
                        Spacer(Modifier.height(10.dp))
                        ContentTransition(targetKey = month) {
                            Grid(
                                cells = cells,
                                today = today,
                                loading = phase is CalendarPhase.Loading,
                                anchor = anchor,
                                anchorIndex = anchorIndex,
                                onFocused = { focusedIndex = it },
                                onOpen = { index ->
                                    anchorIndex = index
                                    selectedDateValue = cells[index].date.toString()
                                    selectedEpisodeKey = null
                                },
                            )
                        }
                    }
                }
            } else {
                // The rail edge keeps LEFT working here too: without it the status action was the
                // only focus target and LEFT went nowhere.
                Row(Modifier.fillMaxSize()) {
                    RailEdge(onOpenRail = nav::openRail)
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                        StatusBlock(
                            failed = failed,
                            focus = statusFocus,
                            onRetry = { retryToken++ },
                            discoveryAvailable = loaded?.discoveryAvailable != false,
                        )
                    }
                }
            }
        }

        if (phase is CalendarPhase.Loading || loaded != null && !empty) {
            Text(
                when {
                    phase is CalendarPhase.Loading -> "Building your calendar"
                    loaded?.incomplete == true -> "Some schedules are unavailable · Refresh to try again"
                    loaded?.discoveryAvailable == false -> "Followed shows · Add a TMDB key in Settings for popular shows"
                    else -> "Popular + followed · English, Indian and South Korean shows"
                },
                style = TvType.Meta,
                color = TvColor.TextSecondary,
                maxLines = 1,
                modifier = Modifier.padding(
                    start = TvGeom.ContentLeft,
                    top = CalendarMonth.Layout.STATUS_LINE_TOP.dp,
                ),
            )
        }

        PanelTransition(cells.firstOrNull { it.date == selectedDate }?.takeIf { sourceEpisode == null }) { cell ->
            DayPanel(
                cell = cell,
                selectedEpisodeKey = selectedEpisodeKey,
                onClose = {
                    selectedDateValue = null
                    runCatching { anchor.requestFocus() }
                },
                onOpen = { episode ->
                    selectedEpisodeKey = episode.key
                    sourceEpisode = episode
                },
            )
        }
        PanelTransition(sourceEpisode) { target ->
            StreamsDrawer(
                type = target.mediaType,
                id = target.canonicalId,
                season = target.season,
                episode = target.episode,
                title = target.showTitle,
                episodes = episodes.filter { it.canonicalId == target.canonicalId }.map { item ->
                    Episode(item.season, item.episode, item.episodeTitle ?: "Episode ${item.episode}",
                        overview = item.overview, thumbnail = item.stillURL,
                        released = item.date.toString(), id = item.key)
                },
                posterUrl = target.posterURL,
                backdropUrl = target.stillURL,
                onDismiss = { sourceEpisode = null },
                onSettings = { sourceEpisode = null; nav.openSettings("addons") },
                onToast = nav::toast,
            )
        }
    }
}

/**
 * Terminal calendar states replace the grid. Drawing a large message over 42 empty cells made the
 * interface look broken and left two competing focus targets under the same pixels.
 */
@Composable
private fun StatusBlock(
    failed: Boolean,
    focus: FocusRequester,
    onRetry: () -> Unit,
    discoveryAvailable: Boolean,
) {
    StateBlock(
        headline = if (failed) "Couldn't build the calendar" else "Nothing scheduled",
        line = if (failed) {
            "Check the TV's network, then try again."
        } else if (!discoveryAvailable) {
            "Add a TMDB key in Settings for popular shows, or follow a series."
        } else {
            "No published episode dates for this month. Try another month or refresh."
        },
        actionLabel = if (failed) "Retry" else "Refresh",
        onAction = onRetry,
        actionModifier = Modifier.focusRequester(focus),
        announceAsError = failed,
    )
}

@Composable
private fun WeekdayHeader() {
    Row(Modifier.height(38.dp)) {
        CalendarMonth.WEEKDAYS.forEach { day ->
            Box(Modifier.width(CalendarMonth.Layout.COLUMN_PITCH.dp)) {
                Text(day, style = TvType.Meta, color = TvColor.TextMuted, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Grid(
    cells: List<CalendarCell>,
    today: LocalDate,
    loading: Boolean,
    anchor: FocusRequester,
    anchorIndex: Int,
    onFocused: (Int) -> Unit,
    onOpen: (Int) -> Unit,
) {
    Column {
        (0 until CalendarMonth.ROWS).forEach { row ->
            Row(Modifier.height(CalendarMonth.Layout.ROW_PITCH.dp)) {
                (0 until CalendarMonth.COLUMNS).forEach { column ->
                    val index = row * CalendarMonth.COLUMNS + column
                    val cell = cells[index]
                    Box(Modifier.width(CalendarMonth.Layout.COLUMN_PITCH.dp)) {
                        key(cell.date) {
                            DayCell(
                                cell = cell,
                                isToday = cell.inMonth && cell.date == today,
                                loading = loading && row == 0,
                                modifier = if (index == anchorIndex) Modifier.focusRequester(anchor) else Modifier,
                                onFocused = { onFocused(index) },
                                onOpen = { onOpen(index) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Spec §13.2. Cells scale to 1.04: a 216 px cell in a tight grid at 1.06 touches its neighbour. */
@Composable
private fun DayCell(
    cell: CalendarCell,
    isToday: Boolean,
    loading: Boolean,
    modifier: Modifier,
    onFocused: () -> Unit,
    onOpen: () -> Unit,
) {
    if (!cell.focusable) {
        Box(
            Modifier.size(CalendarMonth.Layout.CELL_WIDTH.dp, CalendarMonth.Layout.CELL_HEIGHT.dp).padding(12.dp),
        ) {
            Text("${cell.dayOfMonth}", style = TvType.ControlLabel, color = TvColor.TextMuted, maxLines = 1)
        }
        return
    }
    TvFocusable(
        onClick = onOpen,
        accessibleLabel = "${CalendarMonth.dayLabel(cell.date)}, ${cell.episodes.size} episodes${if (isToday) ", today" else ""}",
        clickLabel = "Show episodes",
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocused() },
        cornerRadius = 12.dp,
        focusScale = 1.04f,
    ) { _ ->
        Box(
            Modifier
                .size(CalendarMonth.Layout.CELL_WIDTH.dp, CalendarMonth.Layout.CELL_HEIGHT.dp)
                .clip(TvShape.Card)
                .background(TvColor.Elevated)
                .then(if (isToday) Modifier.border(1.dp, TvColor.Selection, TvShape.Card) else Modifier)
                .padding(12.dp),
        ) {
            Text(
                "${cell.dayOfMonth}${if (isToday) " · Today" else ""}",
                style = TvType.ControlLabel,
                color = if (isToday) TvColor.Selection else TvColor.TextPrimary,
                maxLines = 1,
            )
            Row(
                modifier = Modifier.align(Alignment.BottomStart),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (loading && cell.episodes.isEmpty()) {
                    repeat(2) {
                        Skeleton(
                            modifier = Modifier.size(THUMB_WIDTH.dp, THUMB_HEIGHT.dp),
                            shape = TvShape.Badge,
                            sweep = true,
                        )
                    }
                    return@Row
                }
                cell.episodes.take(CalendarMonth.MAX_THUMBS).forEach { episode ->
                    TvArtwork(
                        request = PosterRequest(episode.posterURL, THUMB_REQUEST_WIDTH),
                        title = episode.showTitle,
                        modifier = Modifier.size(THUMB_WIDTH.dp, THUMB_HEIGHT.dp),
                        shape = TvShape.Badge,
                        showTitleWhenMissing = false,
                    )
                }
                CalendarMonth.overflowLabel(cell.episodes.size)?.let { label ->
                    Spacer(Modifier.width(2.dp))
                    Text(label, style = TvType.Meta, color = TvColor.TextSecondary, maxLines = 1)
                }
            }
        }
    }
}

/** Episode cards open a fresh source search for that exact season/episode. BACK closes the panel. */
@Composable
private fun DayPanel(
    cell: CalendarCell,
    selectedEpisodeKey: String?,
    onClose: () -> Unit,
    onOpen: (CalendarEpisode) -> Unit,
) {
    val firstRow = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val entryKey = cell.episodes.firstOrNull { it.key == selectedEpisodeKey }?.key ?: cell.episodes.firstOrNull()?.key
    LaunchedEffect(cell.date, entryKey) {
        val index = cell.episodes.indexOfFirst { it.key == selectedEpisodeKey }.coerceAtLeast(0)
        if (cell.episodes.isNotEmpty()) listState.scrollToItem(index)
        androidx.compose.runtime.withFrameNanos { }
        runCatching { firstRow.requestFocus() }
    }
    SidePanel(header = CalendarMonth.dayLabel(cell.date), onClose = onClose) {
        if (cell.episodes.isEmpty()) {
            Text("No episodes scheduled for this day.", style = TvType.Body, color = TvColor.TextSecondary)
            Spacer(Modifier.height(TvSpace.M))
            TvButton("Back to calendar", onClose, kind = ButtonKind.Secondary,
                modifier = Modifier.focusRequester(firstRow))
        }
        LazyColumn(state = listState, modifier = Modifier.height(if (cell.episodes.isEmpty()) 0.dp else 720.dp).padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(TvSpace.XS)) {
            items(cell.episodes, key = { it.key }) { episode ->
                run {
                    TvFocusable(
                        onClick = { onOpen(episode) },
                        clickLabel = "Show sources",
                        modifier = if (episode.key == entryKey) Modifier.focusRequester(firstRow) else Modifier,
                        cornerRadius = 10.dp,
                        focusScale = 1f,
                    ) { focused ->
                        Column(
                            modifier = Modifier
                                .width(560.dp)
                                .clip(TvShape.Control)
                                .background(if (focused) TvColor.Elevated2 else TvColor.Elevated)
                                .padding(16.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TvArtwork(
                                    request = PosterRequest(episode.stillURL ?: episode.posterURL, PANEL_STILL_REQUEST_WIDTH),
                                    title = episode.showTitle,
                                    modifier = Modifier.size(120.dp, 68.dp),
                                    shape = TvShape.Chip,
                                    showTitleWhenMissing = false,
                                )
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        episode.showTitle,
                                        style = TvType.CardTitle,
                                        color = TvColor.TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        episode.episodeLine,
                                        style = TvType.Meta,
                                        color = TvColor.TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            episode.firstAired?.let { first ->
                                Spacer(Modifier.height(8.dp))
                                Text("Series started $first", style = TvType.Meta, color = TvColor.TextSecondary, maxLines = 1)
                            }
                            if (episode.providers.isNotEmpty()) {
                                Text(episode.providers.joinToString(" · "), style = TvType.Meta,
                                    color = TvColor.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            episode.overview?.takeIf(String::isNotBlank)?.let { overview ->
                                Spacer(Modifier.height(8.dp))
                                Text(overview, style = TvType.Meta, color = TvColor.TextSecondary,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(if (episode.date > LocalDate.now()) "Upcoming · Show sources" else "Show sources",
                                style = TvType.ControlLabel, color = TvColor.Selection, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- data

/** One series the viewer follows: what Continue Watching and the collections hold. */
private data class FollowedTitle(
    val canonicalId: String,
    val mediaType: String,
    val title: String,
    val posterURL: String?,
)

private suspend fun followedSeries(client: ClientGraph): List<FollowedTitle> {
    val library = client.activeLibrary
    val found = LinkedHashMap<String, FollowedTitle>()
    runCatching {
        library.continueWatching().first().forEach { item ->
            if (!item.mediaType.equals("series", ignoreCase = true)) return@forEach
            found.getOrPut(item.canonicalId) {
                FollowedTitle(item.canonicalId, item.mediaType, item.title, item.posterUrl)
            }
        }
    }
    runCatching {
        library.collections().first().forEach { summary ->
            val detail = library.collection(summary.id).first() ?: return@forEach
            detail.items.forEach { item ->
                if (!item.mediaType.equals("series", ignoreCase = true)) return@forEach
                found.getOrPut(item.canonicalId) {
                    FollowedTitle(item.canonicalId, item.mediaType, item.title, item.posterUrl)
                }
            }
        }
    }
    return found.values.take(MAX_TITLES)
}

/** Fresh metadata only. Source URLs are resolved by the existing Sources screen on selection. */
private suspend fun loadEpisodes(
    services: ClientGraph.DataServices,
    titles: List<FollowedTitle>,
    month: YearMonth,
): CalendarPhase.Loaded = withContext(Dispatchers.IO) {
    val schedule = services.meta.calendar(month, titles.map { it.canonicalId })
    val episodes = schedule.series.flatMap { meta ->
        val followed = titles.firstOrNull { it.canonicalId == meta.id || it.canonicalId == meta.imdbID }
        meta.videos.mapNotNull { video ->
            val date = CalendarMonth.parseReleased(video.released) ?: return@mapNotNull null
            if (YearMonth.from(date) != month) return@mapNotNull null
            CalendarEpisode(
                canonicalId = meta.imdbID ?: meta.id,
                mediaType = "series",
                showTitle = meta.title,
                posterURL = meta.poster ?: followed?.posterURL,
                season = video.season,
                episode = video.episode,
                episodeTitle = video.title.takeIf(String::isNotBlank),
                date = date,
                stillURL = video.thumbnail ?: meta.backdrop,
                overview = video.overview?.takeIf(String::isNotBlank) ?: meta.description,
                firstAired = CalendarMonth.parseReleased(meta.releaseDate),
                providers = meta.streamingProviders.map { it.name },
            )
        }
    }.distinctBy { it.key }
    ReceiverDiagnostics.record("calendar.load", "month=$month shows=${schedule.series.size} episodes=${episodes.size} incomplete=${schedule.incomplete}")
    CalendarPhase.Loaded(episodes, schedule.incomplete, schedule.discoveryAvailable)
}
