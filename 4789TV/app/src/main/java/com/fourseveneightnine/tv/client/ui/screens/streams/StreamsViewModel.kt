package com.fourseveneightnine.tv.client.ui.screens.streams

import androidx.compose.runtime.Immutable
import com.fourseveneightnine.tv.catalog.EinthusanAccount
import com.fourseveneightnine.tv.catalog.EinthusanProvider
import com.fourseveneightnine.tv.catalog.EinthusanSource
import com.fourseveneightnine.tv.client.ClientGraph
import com.fourseveneightnine.tv.client.data.meta.Episode
import com.fourseveneightnine.tv.client.data.meta.Meta
import com.fourseveneightnine.tv.client.data.streams.RankedRow
import com.fourseveneightnine.tv.client.data.streams.StreamRanker
import com.fourseveneightnine.tv.client.data.streams.StreamRow
import com.fourseveneightnine.tv.client.data.streams.StreamSearchState
import com.fourseveneightnine.tv.client.data.streams.StreamFacts
import com.fourseveneightnine.tv.client.playback.HardwareCodecs
import com.fourseveneightnine.tv.client.playback.StreamIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import com.fourseveneightnine.tv.client.playback.savedStream
import com.fourseveneightnine.tv.client.playback.episodeFiles
import com.fourseveneightnine.tv.client.playback.savedTorboxFile
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics

internal enum class SourceKind { ALL, DEBRID, HTTPS }

/** Which chips are lit. "All" is not stored: it is the state where nothing else is (§10.6). */
@Immutable
internal data class StreamFilters(
    val cached: Boolean = false,
    val kind: SourceKind = SourceKind.ALL,
    val fourK: Boolean = false,
    val fullHd: Boolean = false,
    val languages: Set<String> = emptySet(),
    /** The "Other" chip: everything whose audio language is not one of the three named. */
    val otherLanguages: Boolean = false,
    val unknownLanguage: Boolean = false,
) {
    companion object {
        val DrawerDefault = StreamFilters(kind = SourceKind.DEBRID)
    }

    val isAll: Boolean
        get() = kind == SourceKind.ALL && !cached && !fourK && !fullHd && languages.isEmpty() && !otherLanguages && !unknownLanguage
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
    val originalLanguage: String? = null,
    val releaseDate: String? = null,
    val preferredAudioLanguages: List<String> = emptyList(),
) {
    val visible: List<RankedRow>
        get() = displayed.filter { StreamFilterRules.matches(it.row, filters, languageChips) }

    val countChip: String get() {
        val count = StreamCount.text(search.copy(rows = visible.map { it.row }))
        return when {
            search.checkingLinks > 0 -> "$count · Checking ${search.checkingLinks} DMM ${if (search.checkingLinks == 1) "link" else "links"}"
            search.rejectedLinks > 0 -> "$count · ${search.rejectedLinks} DMM ${if (search.rejectedLinks == 1) "link" else "links"} failed checks"
            else -> count
        }
    }

    val failureMessage: String get() = if (search.timeouts == search.failed)
        "The add-ons took too long to answer." else "The TV could not load results from your add-ons."

    val emptyMessage: String get() = if (search.rejectedLinks > 0)
        "DMM links did not pass the file and video checks. Retry to check again."
        else if (search.failed > 0) "Some add-ons did not answer. Retry to check again."
        else "Check your add-ons in Settings or try another title."

    /** At least one add-on answered, but none returned a playable source (§10.9 Empty). */
    val isEmpty: Boolean get() = search.done && search.rows.isEmpty() && search.failed < search.attempted

    /** Every add-on failed (§10.9 Error). */
    val isError: Boolean
        get() = search.done && search.rows.isEmpty() && search.attempted > 0 && search.failed >= search.attempted

    /** Rows exist, but the chips hid all of them (§10.9 Empty after filtering). */
    val isFilteredEmpty: Boolean get() = displayed.isNotEmpty() && visible.isEmpty()
}

internal data class SourceQualityGroup(val label: String, val rows: List<RankedRow>)

private const val LINK_UNAVAILABLE_REASON = "Link unavailable"

/** Choose the short list across the whole ranked pool before separating delivery routes. */
internal object SourceQualityGroups {
    fun from(rows: List<RankedRow>, expanded: Boolean = false, playingQuality: String? = null,
        retainedId: String? = null): List<SourceQualityGroup> {
        val (regional, regular) = rows.partition { EinthusanSource.parse(it.row.url) != null }
        val admitted = regular.filter { it.row.readyHint && it.row.identityVerified != false }
            .partition { it.row.cachedDebrid }
        val (cached, direct) = if (expanded) admitted else {
            // Reserve the playing row's slot before choosing alternatives across both sections.
            val ordered = admitted.first + admitted.second
            val retainedFailure = retainedId?.let { id -> ordered.firstOrNull { source ->
                source.row.id == id && !source.eligible && source.reasons.any {
                    it.caution && it.text == LINK_UNAVAILABLE_REASON
                }
            } }
            val picks = listOf("4K", "1080p").flatMap { quality ->
                val band = ordered.filter { it.row.quality == quality }
                val ready = band.filter { it.eligible && it.decodeCompatible }
                    .take(if (playingQuality == quality) 1 else 2)
                // Only the failed retained copy is extra; keep its published order in this band.
                if (retainedFailure?.row?.quality == quality) band.filter { it in ready || it === retainedFailure }
                else ready
            }
            val selected = if (retainedFailure != null && picks.none { it.row.id == retainedFailure.row.id })
                picks + retainedFailure else picks
            selected.partition { it.row.cachedDebrid }
        }
        return buildList {
            if (cached.isNotEmpty()) add(SourceQualityGroup("Cached Debrid", cached))
            val directRows = if (regional.isEmpty()) direct else {
                val included = (regional + direct).mapTo(hashSetOf()) { it.row.id }
                rows.filter { it.row.id in included }
            }
            if (directRows.isNotEmpty()) add(SourceQualityGroup("Direct", directRows))
        }
    }

    /** A returning row wins; fresh entry uses the best currently playable rank across groups. */
    fun entryId(rows: List<RankedRow>, retainedId: String?): String? =
        retainedId?.takeIf { id -> rows.any { it.row.id == id } }
            ?: rows.firstOrNull { it.eligible && it.decodeCompatible }?.row?.id
            ?: rows.firstOrNull()?.row?.id

    /** Includes non-focusable group headers in the lazy-list index. */
    fun listIndex(groups: List<SourceQualityGroup>, sourceID: String?): Int {
        var offset = 0
        for (group in groups) {
            val index = group.rows.indexOfFirst { it.row.id == sourceID }
            if (index >= 0) return offset + 1 + index
            offset += 1 + group.rows.size
        }
        return 0
    }
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
    initialFilters: StreamFilters = StreamFilters(),
) {
    private val _state = MutableStateFlow(StreamsUiState(filters = initialFilters))
    val state: StateFlow<StreamsUiState> = _state.asStateFlow()

    /** The row the D-pad is on. New rows go below it; a re-sort above it waits. */
    private var focusedId: String? = null
    private var ranked: List<RankedRow> = emptyList()
    private var job: Job? = null
    private var startedAtNanos = 0L
    private val rejectedDmmIds = MutableStateFlow<Set<String>>(emptySet())
    private val pickerVisible = MutableStateFlow(false)

    private data class RegionalResult(val pending: Boolean, val row: StreamRow? = null)

    init {
        start()
    }

    fun setPickerVisible(visible: Boolean) { pickerVisible.value = visible }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun start() {
        val retrying = job != null
        job?.cancel()
        rejectedDmmIds.value = emptySet()
        startedAtNanos = System.nanoTime()
        val searchTimingId = startedAtNanos
        ReceiverDiagnostics.benchmark("sources.ui.start") { "search=$searchTimingId series=${type == "series"} retry=$retrying" }
        _state.value = StreamsUiState(filters = _state.value.filters, languageChips = _state.value.languageChips)
        ranked = emptyList()
        focusedId = null
        job = scope.launch {
            val boxCodecs = withContext(Dispatchers.Default) { codecs() }
            var seenServices = false
            client.services.collectLatest { services ->
                if (services == null) {
                    if (seenServices) {
                        ranked = emptyList()
                        _state.value = StreamsUiState(noAddons = true)
                    }
                    return@collectLatest
                }
                if (seenServices) {
                    ranked = emptyList()
                    _state.value = StreamsUiState(filters = _state.value.filters)
                }
                seenServices = true
                val rules = services.rules
                val saved = try { client.resolveCatalogItem(type, id) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) {
                    _state.value = _state.value.copy(search = StreamSearchState(attempted = 1, failed = 1, done = true))
                    return@collectLatest
                }
                val localFile = id.startsWith("saved-torbox:")
                val metadataID = saved?.imdbId ?: saved?.tmdbId?.let { "tmdb:$it" } ?: id.substringAfter("::")
                if (retrying && services.meta.peek(type, metadataID)?.hasInlineStreams == true) {
                    services.meta.invalidate(type, metadataID)
                }
                var preview: Meta? = null
                val meta = if (localFile) null else withTimeoutOrNull(4_000L) {
                    services.meta.meta(type, metadataID) { preview = it }
                } ?: preview ?: services.meta.peek(type, metadataID)
                val exact = if (localFile) listOfNotNull(savedTorboxFile(id, "Exact saved file"))
                    else if (saved != null && type == "series" && season != null && episode != null &&
                        id.startsWith("saved:")) saved.episodeFiles(services.debrid, allCopies = true)
                        .filter { it.season == season && it.episode == episode }.map { it.stream }
                    else if (saved?.sourcePageURL != null) saved.playbackSources.mapNotNull { source ->
                        saved.copy(playbackSources = listOf(source)).savedStream(season, episode)
                    } else if (saved != null) listOfNotNull(saved.savedStream(season, episode)) else null
                val expected = com.fourseveneightnine.tv.client.data.streams.StreamExpectation(
                    meta?.title ?: saved?.title.orEmpty(), meta?.year ?: saved?.year, season, episode, type == "movie",
                    aliases = meta?.titleAliases.orEmpty() + listOfNotNull(saved?.title), episodeYear = meta?.videos?.firstOrNull { it.season == season && it.episode == episode }?.released?.take(4)?.toIntOrNull(), canonicalID = id)
                val results = if (exact != null) flowOf(StreamSearchState(rows = withContext(Dispatchers.IO) { services.search.verifyRows(exact, expected) }, attempted = 1, done = true))
                    else services.search.search(
                        type, StreamIds.candidates(type, id, meta, season, episode),
                        inlineRows = meta?.streamsFor(season, episode).orEmpty(),
                        expectation = expected,
                        followPhoneResults = true,
                    )
                val account = EinthusanAccount.from(services.document)
                val regional = if (EinthusanProvider.available && account.configured &&
                    type == "movie" && season == null && episode == null && !localFile) {
                    pickerVisible.flatMapLatest { visible ->
                        if (!visible) flowOf(null) else flow<RegionalResult?> {
                            emit(RegionalResult(pending = true))
                            val title = expected.title
                            val imdbId = meta?.imdbID ?: saved?.imdbId ?: metadataID.takeIf { it.matches(Regex("tt[0-9]+")) }
                            val row = client.einthusan.find(title, meta?.year ?: saved?.year, imdbId,
                                meta?.originalLanguage, account)?.row(title)
                            emit(RegionalResult(pending = false, row = row))
                        }
                    }
                } else flowOf(null)
                var recordedCompletion = false
                var recordedFirstRow = false
                // Detail warms this search while its actions already own D-pad focus. Keep
                // add-on routing, identity checks and result merging off the input thread.
                services.search.validatedForDisplay(results, expected, followUpdates = true, rejectedIDs = rejectedDmmIds)
                    .combine(regional) { incoming, native ->
                        if (native == null) incoming else incoming.copy(
                            rows = incoming.rows + listOfNotNull(native.row),
                            attempted = incoming.attempted + 1,
                            pending = incoming.pending + if (native.pending) 1 else 0,
                            done = incoming.done && !native.pending,
                        )
                    }
                    .flowOn(Dispatchers.Default).collect { incoming ->
                    // Exact saved files bypass StreamSearch, so apply the same hard boundary here.
                    val failedDmm = incoming.rows.filter { it.requiresDmmValidation && services.debrid.isUnavailable(it, season, episode) }
                        .map { it.id }.toSet()
                    if (failedDmm.isNotEmpty()) rejectedDmmIds.update { it + failedDmm }
                    val search = incoming.copy(rows = incoming.rows.filterNot {
                        EinthusanSource.parse(it.url) == null &&
                            (it.theatricalRelease || (it.requiresDmmValidation && isRejected(it)))
                    })
                    if (search.done) {
                        ReceiverDiagnostics.record(
                            if (recordedCompletion) "streams.search.updated" else "streams.search.done",
                            com.fourseveneightnine.tv.startup.sourceDiagnosticDetails(null, expected,
                                "search=$startedAtNanos attempted=${search.attempted} failed=${search.failed} timeouts=${search.timeouts} rows=${search.rows.size} rejectedLinks=${search.rejectedLinks}"),
                        )
                        if (!recordedCompletion) ReceiverDiagnostics.benchmark("sources.ui.done") {
                            "search=$searchTimingId attempted=${search.attempted} failed=${search.failed} timeouts=${search.timeouts} rows=${search.rows.size}"
                        }
                        recordedCompletion = true
                    }
                    val ordered = withContext(Dispatchers.Default) {
                        val (native, regular) = search.rows.partition { EinthusanSource.parse(it.url) != null }
                        val rankedRegular = StreamRanker.rankForSourceList(regular, rules, boxCodecs, meta?.originalLanguage).map { ranked ->
                            if (services.debrid.isUnavailable(ranked.row, season, episode)) {
                                ranked.copy(eligible = false, reasons = listOf(
                                    com.fourseveneightnine.tv.client.data.streams.RankReason(LINK_UNAVAILABLE_REASON, caution = true)
                                ) + ranked.reasons)
                            } else ranked
                        }.sortedByDescending { it.eligible }
                        native.map { RankedRow(it, 0.0, emptyList()) } + rankedRegular
                    }
                    if (com.fourseveneightnine.tv.BuildConfig.BENCHMARK_LOGGING && !recordedFirstRow && ordered.isNotEmpty()) {
                        recordedFirstRow = true
                        ReceiverDiagnostics.benchmark("sources.ui.first_row_ready") {
                            "search=$searchTimingId rows=${ordered.size} elapsedMs=${(System.nanoTime() - searchTimingId) / 1_000_000}"
                        }
                    }
                    ranked = ordered
                    val current = _state.value
                    _state.value = current.copy(
                        search = search,
                        originalLanguage = meta?.originalLanguage,
                        releaseDate = meta?.releaseDate,
                        preferredAudioLanguages = rules.preferredAudioLanguages,
                        displayed = StreamListOrder.merge(current.displayed, ordered, focusedId),
                        languageChips = StreamFilterRules.languageChips(search.rows, current.languageChips, current.filters.languages),
                        noAddons = search.done && search.attempted == 0,
                    )
                }
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

    /** Refresh proven failures immediately; keep the remote's focused row in place. */
    fun isRejected(row: StreamRow): Boolean = row.id in rejectedDmmIds.value

    fun refreshAvailability(failedSource: StreamRow? = null) {
        val debrid = client.services.value?.debrid ?: return
        failedSource?.takeIf { it.requiresDmmValidation }?.let { debrid.notePlaybackFailure(it, season, episode) }
        val denied = ranked.filter { it.row.requiresDmmValidation && debrid.isUnavailable(it.row, season, episode) }
            .map { it.row.id }.toSet() + listOfNotNull(failedSource?.takeIf { it.requiresDmmValidation }?.id)
        if (denied.isNotEmpty()) rejectedDmmIds.update { it + denied }
        ranked = ranked.filterNot { it.row.requiresDmmValidation && isRejected(it.row) }.map { source ->
            if (!debrid.isUnavailable(source.row, season, episode)) source
            else source.copy(eligible = false, reasons = listOf(
                com.fourseveneightnine.tv.client.data.streams.RankReason(LINK_UNAVAILABLE_REASON, caution = true)
            ) + source.reasons)
        }.sortedByDescending { it.eligible }
        val current = _state.value
        val removed = current.displayed.count { it.row.requiresDmmValidation && isRejected(it.row) }
        _state.value = current.copy(
            search = current.search.copy(rows = current.search.rows.filterNot { it.requiresDmmValidation && isRejected(it) },
                rejectedLinks = current.search.rejectedLinks + removed),
            displayed = StreamListOrder.merge(current.displayed, ranked, focusedId))
    }

    fun onFocus(rowId: String?) {
        focusedId = rowId
        // Focus leaving the list is the moment a held re-sort is allowed to happen (§10.7).
        if (rowId == null) applyPendingSort()
    }

    /** UP from row 1, or focus leaving the list: the held order lands in one step, no animation. */
    fun applyPendingSort() {
        if (ranked.isEmpty()) return
        ReceiverDiagnostics.benchmark("sources.sort.applied") { "rows=${ranked.size}" }
        _state.value = _state.value.copy(displayed = ranked)
    }

    fun toggle(chip: StreamChip) {
        val current = _state.value.filters
        ReceiverDiagnostics.benchmark("sources.filter.intent") { "type=${chip::class.java.simpleName}" }
        _state.value = _state.value.copy(
            filters = when (chip) {
                StreamChip.All -> StreamFilters()
                StreamChip.Debrid -> current.copy(kind = SourceKind.DEBRID)
                StreamChip.Https -> current.copy(kind = SourceKind.HTTPS, cached = false)
                StreamChip.Cached -> current.copy(cached = !current.cached)
                StreamChip.FourK -> current.copy(fourK = !current.fourK)
                StreamChip.FullHd -> current.copy(fullHd = !current.fullHd)
                StreamChip.Other -> current.copy(otherLanguages = !current.otherLanguages)
                StreamChip.Unknown -> current.copy(unknownLanguage = !current.unknownLanguage)
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
        ReceiverDiagnostics.benchmark("sources.filter.intent") { "type=clear" }
        setFilters(StreamFilters())
    }

    fun setFilters(filters: StreamFilters) {
        if (filters != StreamFilters()) {
            ReceiverDiagnostics.benchmark("sources.filter.intent") { "type=dropdown" }
        }
        _state.update { it.copy(filters = filters) }
    }
}

/** One filter chip. The language ones are built from what the results actually carry. */
internal sealed interface StreamChip {
    data object All : StreamChip
    data object Debrid : StreamChip
    data object Https : StreamChip
    data object Cached : StreamChip
    data object FourK : StreamChip
    data object FullHd : StreamChip
    data object Other : StreamChip
    data object Unknown : StreamChip
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

    fun canRequestEntryFocus(rows: List<RankedRow>, searchDone: Boolean, restoredSourceId: String?): Boolean =
        restoredSourceId != null || searchDone || rows.any {
            it.eligible && it.decodeCompatible && it.row.identityVerified == true &&
                (it.row.cachedHint.verified || EinthusanSource.parse(it.row.url) != null)
        }

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

    fun matches(row: StreamRow, filters: StreamFilters, namedLanguages: List<String> = emptyList()): Boolean {
        if (filters.isAll) return true
        if (filters.kind == SourceKind.DEBRID && !row.debridBacked) return false
        if (filters.kind == SourceKind.HTTPS && !row.directHttp && EinthusanSource.parse(row.url) == null) return false
        if (filters.cached && !row.cachedHint.cached) return false
        // Alternatives within quality/language; requirements across categories.
        if ((filters.fourK || filters.fullHd) &&
            !(filters.fourK && row.quality == "4K") && !(filters.fullHd && row.quality == "1080p")) return false
        if (filters.languages.isNotEmpty() || filters.otherLanguages || filters.unknownLanguage) {
            val spoken = row.audioLanguages.filter { it.isNotBlank() && !it.equals("unknown", true) && !it.equals("und", true) }
            val named = filters.languages.any { language -> spoken.any { it.equals(language, true) } }
            val other = filters.otherLanguages && spoken.any { language ->
                namedLanguages.none { it.equals(language, true) }
            }
            val unknown = filters.unknownLanguage && spoken.isEmpty()
            if (!named && !other && !unknown) return false
        }
        return true
    }

    /**
     * The three languages most rows carry, most common first.
     *
     * Ties break on the name so the chip row does not reshuffle as rows land — a chip that swaps
     * places under the D-pad is the same fault as a row that does.
     */
    fun languageChips(rows: List<StreamRow>, previous: List<String> = emptyList(), selected: Set<String> = emptySet()): List<String> {
        val ranked = rows
        .asSequence()
        .flatMap { it.audioLanguages.asSequence() }
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.equals("unknown", true) && !it.equals("und", true) }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(LANGUAGE_CHIP_CAP)
        .map { it.key }
        // Keep named chip identity stable while incremental results arrive. Active choices never vanish.
        val pinned = (previous + selected.sorted()).distinct().filter { it in selected }
        val remaining = (previous + ranked).distinct().filterNot { it in selected }
        return pinned + remaining.take((LANGUAGE_CHIP_CAP - pinned.size).coerceAtLeast(0))
    }

    /** "Other" is only worth a chip when some row's language is not one of the named three. */
    fun hasUnknownLanguages(rows: List<StreamRow>): Boolean = rows.any { row ->
        row.audioLanguages.none { it.isNotBlank() && !it.equals("unknown", true) && !it.equals("und", true) }
    }

    fun hasOtherLanguages(rows: List<StreamRow>, chips: List<String>): Boolean = rows.any { row ->
        row.audioLanguages.isNotEmpty() &&
            row.audioLanguages.any { spoken -> spoken.isNotBlank() && !spoken.equals("unknown", true) && !spoken.equals("und", true) && chips.none { spoken.equals(it, ignoreCase = true) } }
    }
}
