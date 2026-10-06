package com.fourseveneightnine.tv.client.ui.screens.streams

import androidx.compose.runtime.Immutable
import com.fourseveneightnine.tv.client.ClientGraph
import com.fourseveneightnine.tv.client.data.meta.Episode
import com.fourseveneightnine.tv.client.data.streams.RankedRow
import com.fourseveneightnine.tv.client.data.streams.StreamRanker
import com.fourseveneightnine.tv.client.data.streams.StreamRow
import com.fourseveneightnine.tv.client.data.streams.StreamSearchState
import com.fourseveneightnine.tv.client.playback.HardwareCodecs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Which chips are lit. "All" is not stored: it is the state where nothing else is (§10.6). */
@Immutable
internal data class StreamFilters(
    val cached: Boolean = false,
    val fourK: Boolean = false,
    val fullHd: Boolean = false,
    val languages: Set<String> = emptySet(),
    /** The "Other" chip: everything whose audio language is not one of the three named. */
    val otherLanguages: Boolean = false,
) {
    val isAll: Boolean
        get() = !cached && !fourK && !fullHd && languages.isEmpty() && !otherLanguages
}

/** Everything the Streams screen draws (`TV_DESIGN_SPEC.md` §10). */
@Immutable
internal data class StreamsUiState(
    val search: StreamSearchState = StreamSearchState(),
    /** The order on screen. New rows are inserted below focus; a re-sort is held (§10.7). */
    val displayed: List<RankedRow> = emptyList(),
    val filters: StreamFilters = StreamFilters(),
    /** Up to three languages the results actually carry, in order of how many rows have them. */
    val languageChips: List<String> = emptyList(),
    val noAddons: Boolean = false,
) {
    val visible: List<RankedRow>
        get() = displayed.filter { StreamFilterRules.matches(it.row, filters) }

    val countChip: String get() = StreamCount.text(search)

    /** Every add-on answered and not one of them had a row (§10.9 Empty). */
    val isEmpty: Boolean get() = search.done && search.rows.isEmpty() && search.failed < search.attempted

    /** Every add-on failed (§10.9 Error). */
    val isError: Boolean
        get() = search.done && search.rows.isEmpty() && search.attempted > 0 && search.failed >= search.attempted

    /** Rows exist, but the chips hid all of them (§10.9 Empty after filtering). */
    val isFilteredEmpty: Boolean get() = displayed.isNotEmpty() && visible.isEmpty()
}

/**
 * Streams' state holder.
 *
 * It keeps the published order and the fresh ranking apart on purpose. `StreamSearch` guarantees
 * that `rows` only grows and never reorders; the ranker re-sorts a copy; this class decides when
 * that copy is allowed to become the list. A row cannot slide out from under the D-pad, which is
 * what §10.7 asks for and what a live search otherwise does every second.
 */
internal class StreamsViewModel(
    private val type: String,
    private val id: String,
    private val season: Int?,
    private val episode: Int?,
    private val client: ClientGraph,
    private val scope: CoroutineScope,
    private val codecs: () -> Set<String> = HardwareCodecs::names,
) {
    private val _state = MutableStateFlow(StreamsUiState())
    val state: StateFlow<StreamsUiState> = _state.asStateFlow()

    /** The row the D-pad is on. New rows go below it; a re-sort above it waits. */
    private var focusedId: String? = null
    private var ranked: List<RankedRow> = emptyList()
    private var job: Job? = null
    private var startedAtNanos = 0L

    init {
        start()
    }

    fun start() {
        job?.cancel()
        startedAtNanos = System.nanoTime()
        _state.value = StreamsUiState()
        ranked = emptyList()
        job = scope.launch {
            val services = client.services.value ?: client.services.filterNotNull().first()
            val rules = services.rules
            val boxCodecs = codecs()
            val streamId = com.fourseveneightnine.tv.client.playback.StreamIds.forStreams(services, type, id)
            services.search.search(type, streamId, season, episode).collect { search ->
                ranked = StreamRanker.rank(search.rows, rules, boxCodecs)
                val current = _state.value
                _state.value = current.copy(
                    search = search,
                    displayed = StreamListOrder.merge(current.displayed, ranked, focusedId),
                    languageChips = StreamFilterRules.languageChips(search.rows),
                    noAddons = search.done && search.attempted == 0,
                )
            }
        }
    }

    /** Detail may have searched before the drawer opens; do not offer old direct links. */
    fun refreshIfStale(maxAgeMillis: Long = 120_000L) {
        if ((System.nanoTime() - startedAtNanos) / 1_000_000 > maxAgeMillis) start()
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun onFocus(rowId: String?) {
        focusedId = rowId
        // Focus leaving the list is the moment a held re-sort is allowed to happen (§10.7).
        if (rowId == null) applyPendingSort()
    }

    /** UP from row 1, or focus leaving the list: the held order lands in one step, no animation. */
    fun applyPendingSort() {
        if (ranked.isEmpty()) return
        _state.value = _state.value.copy(displayed = ranked)
    }

    fun toggle(chip: StreamChip) {
        val current = _state.value.filters
        _state.value = _state.value.copy(
            filters = when (chip) {
                StreamChip.All -> StreamFilters()
                StreamChip.Cached -> current.copy(cached = !current.cached)
                StreamChip.FourK -> current.copy(fourK = !current.fourK)
                StreamChip.FullHd -> current.copy(fullHd = !current.fullHd)
                StreamChip.Other -> current.copy(otherLanguages = !current.otherLanguages)
                is StreamChip.Language -> current.copy(
                    languages = if (chip.name in current.languages) {
                        current.languages - chip.name
                    } else {
                        current.languages + chip.name
                    },
                )
            },
        )
    }

    fun clearFilters() {
        _state.value = _state.value.copy(filters = StreamFilters())
    }
}

/** One filter chip. The language ones are built from what the results actually carry. */
internal sealed interface StreamChip {
    data object All : StreamChip
    data object Cached : StreamChip
    data object FourK : StreamChip
    data object FullHd : StreamChip
    data object Other : StreamChip
    data class Language(val name: String) : StreamChip
}

/** One ordered episode sequence for the source picker's Previous/Next controls. */
internal object SourceEpisodeNavigation {
    fun ordered(videos: List<Episode>): List<Episode> {
        val regular = videos.filter { it.season > 0 }
        return (regular.ifEmpty { videos })
            .distinctBy { it.season to it.episode }
            .sortedWith(compareBy(Episode::season, Episode::episode))
    }

    fun adjacent(videos: List<Episode>, season: Int?, episode: Int?, direction: Int): Episode? {
        val episodes = ordered(videos)
        val current = episodes.indexOfFirst { it.season == season && it.episode == episode }
        return when {
            current >= 0 -> episodes.getOrNull(current + direction)
            direction > 0 -> episodes.firstOrNull()
            else -> null
        }
    }

    fun firstInSeason(videos: List<Episode>, season: Int): Episode? =
        ordered(videos).firstOrNull { it.season == season }
}

/**
 * Where a newly arrived row goes.
 *
 * §10.7: "New rows insert below the focused row, never above. When the ranker says a new row
 * belongs above the focused one, the move is held." So while a row holds focus, everything new
 * lands directly under it in ranked order and nothing already on screen moves. With no focus in
 * the list there is nothing to protect, and the fresh ranking is taken whole.
 */
internal object StreamListOrder {

    fun merge(
        current: List<RankedRow>,
        ranked: List<RankedRow>,
        focusedId: String?,
    ): List<RankedRow> {
        if (focusedId == null || current.isEmpty()) return ranked
        val byId = ranked.associateBy { it.row.id }
        // Keep the published order, but take each row's newest score and reasons.
        val kept = current.mapNotNull { byId[it.row.id] }
        val shown = kept.mapTo(mutableSetOf()) { it.row.id }
        val fresh = ranked.filter { it.row.id !in shown }
        if (fresh.isEmpty()) return kept
        val focusIndex = kept.indexOfFirst { it.row.id == focusedId }
        if (focusIndex < 0) return kept + fresh
        return kept.take(focusIndex + 1) + fresh + kept.drop(focusIndex + 1)
    }
}

/** The count chip's four sentences (§10.7). It never animates; only its text changes. */
internal object StreamCount {

    fun text(state: StreamSearchState): String {
        if (state.attempted == 0) return if (state.done) "No add-ons" else "Searching"
        if (state.rows.isEmpty() && !state.done) {
            return "Searching · ${state.attempted - state.pending} of ${state.attempted} add-ons"
        }
        val sources = "${state.rows.size} ${plural(state.rows.size, "source")}"
        return when {
            state.pending > 0 -> "$sources · ${state.pending} ${plural(state.pending, "add-on")} pending"
            state.failed > 0 -> "$sources · ${state.failed} ${plural(state.failed, "add-on")} failed"
            else -> sources
        }
    }

    /** True when the tail of [text] should draw in `warning` (§10.7). */
    fun namesAFailure(state: StreamSearchState): Boolean =
        state.rows.isNotEmpty() && state.pending == 0 && state.failed > 0

    private fun plural(count: Int, word: String): String = if (count == 1) word else "${word}s"
}

/** Which rows a set of chips leaves on screen, and which language chips to offer. */
internal object StreamFilterRules {

    /** §10.2: up to three languages from the results, then "Other". */
    const val LANGUAGE_CHIP_CAP = 3

    fun matches(row: StreamRow, filters: StreamFilters): Boolean {
        if (filters.isAll) return true
        if (filters.cached && !row.cachedHint.cached) return false
        if (filters.fourK && row.quality != "4K") return false
        if (filters.fullHd && row.quality != "1080p") return false
        if (filters.languages.isNotEmpty() || filters.otherLanguages) {
            val named = filters.languages.any { language ->
                row.audioLanguages.any { it.equals(language, ignoreCase = true) }
            }
            val other = filters.otherLanguages && row.audioLanguages.none { spoken ->
                filters.languages.any { spoken.equals(it, ignoreCase = true) }
            }
            if (!named && !other) return false
        }
        return true
    }

    /**
     * The three languages most rows carry, most common first.
     *
     * Ties break on the name so the chip row does not reshuffle as rows land — a chip that swaps
     * places under the D-pad is the same fault as a row that does.
     */
    fun languageChips(rows: List<StreamRow>): List<String> = rows
        .asSequence()
        .flatMap { it.audioLanguages.asSequence() }
        .map { it.trim() }
        .filter(String::isNotEmpty)
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(LANGUAGE_CHIP_CAP)
        .map { it.key }

    /** "Other" is only worth a chip when some row's language is not one of the named three. */
    fun hasOtherLanguages(rows: List<StreamRow>, chips: List<String>): Boolean = rows.any { row ->
        row.audioLanguages.isNotEmpty() &&
            row.audioLanguages.none { spoken -> chips.any { spoken.equals(it, ignoreCase = true) } }
    }
}
