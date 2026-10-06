package com.fourseveneightnine.tv.client.ui.screens.discover

import androidx.compose.runtime.Immutable
import com.fourseveneightnine.tv.client.ClientGraph
import com.fourseveneightnine.tv.client.data.addons.Addon
import com.fourseveneightnine.tv.client.data.addons.AddonCatalog
import com.fourseveneightnine.tv.client.data.addons.CatalogExtra
import com.fourseveneightnine.tv.client.ui.screens.home.HomeCard
import com.fourseveneightnine.tv.client.ui.screens.home.toHomeCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** The three Type chips, spec §4.3. The value is the Stremio media type the catalog declares. */
internal enum class DiscoverType(val label: String, val wire: String) {
    Movies("Movies", "movie"),
    Series("Series", "series"),
    Anime("Anime", "anime"),
}

/** One row in the Catalog panel: an add-on's catalog, with the add-on name as its group header. */
@Immutable
internal data class DiscoverCatalogChoice(
    val key: String,
    val addonName: String,
    val catalogName: String,
    val manifestUrl: String,
    val type: String,
    val catalogId: String,
    val genres: List<String>,
)

@Immutable
internal data class DiscoverState(
    val type: DiscoverType = DiscoverType.Movies,
    val catalogs: List<DiscoverCatalogChoice> = emptyList(),
    val selectedCatalogKey: String? = null,
    val genre: String? = null,
    val items: List<HomeCard> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val failed: Boolean = false,
    val settingsLoaded: Boolean = false,
    /** Bumped every time page 1 of a new list lands. Appended pages keep the number (spec §4.8). */
    val page: Int = 0,
) {
    val selected: DiscoverCatalogChoice? get() = catalogs.firstOrNull { it.key == selectedCatalogKey }
    val genres: List<String> get() = selected?.genres.orEmpty()
    val catalogLabel: String get() = selected?.catalogName ?: "No catalog"
    val genreLabel: String get() = genre ?: "All genres"
}

/** One ink in the collapsed strip, spec §4.5. Named here so the strip can be tested without Compose. */
internal enum class StripInk { Word, Value, Separator }

/** One run of the collapsed strip: the text and which of the three inks draws it. */
@Immutable
internal data class StripPart(val text: String, val ink: StripInk)

/** One row of the Catalog panel: an add-on's name, or one of its catalogs (spec §4.4). */
internal sealed interface CatalogPanelRow {
    val key: String

    data class Group(val addonName: String) : CatalogPanelRow {
        override val key: String get() = "group:$addonName"
    }

    data class Choice(val choice: DiscoverCatalogChoice) : CatalogPanelRow {
        override val key: String get() = "choice:${choice.key}"
    }
}

/** Everything Discover decides that is not a network call. Kept free of Compose and of Android. */
internal object DiscoverPlan {

    /** The next page is asked for when the focused row passes this much of what is loaded. */
    const val PREFETCH_FRACTION: Float = 0.7f

    /** Spec §4.2: six columns, because a 236 px poster is the smallest one readable from 3 m. */
    const val COLUMNS: Int = 6

    // ------------------------------------------------------------------ geometry, spec §4.2
    //
    // Every number below is design-canvas dp. They live here rather than in the composables so the
    // skeleton and the real cell cannot drift apart again: the grid used to jump 20 px when the
    // first page landed because the two were written out by hand in different files.

    /** 1080 minus the 54 px safe top. The screen pads by the safe top, so this is what is left. */
    const val CONTENT_HEIGHT_DP: Int = 1026
    const val BAND_TOP_EXPANDED_DP: Int = 162
    const val BAND_TOP_COLLAPSED_DP: Int = 72

    const val POSTER_HEIGHT_DP: Int = 354
    const val POSTER_TEXT_GAP_DP: Int = 10
    const val CELL_TITLE_HEIGHT_DP: Int = 24
    const val CELL_TITLE_META_GAP_DP: Int = 2
    const val CELL_META_HEIGHT_DP: Int = 22
    const val ROW_GAP_DP: Int = 28

    /** Poster, gap, title, gap, meta. */
    const val CELL_BLOCK_HEIGHT_DP: Int =
        POSTER_HEIGHT_DP + POSTER_TEXT_GAP_DP + CELL_TITLE_HEIGHT_DP + CELL_TITLE_META_GAP_DP + CELL_META_HEIGHT_DP

    /** What one grid row costs, cell block plus the row gap. */
    const val ROW_PITCH_DP: Int = CELL_BLOCK_HEIGHT_DP + ROW_GAP_DP

    /** The skeleton draws a poster block and then this, so its rows sit at the same pitch. */
    const val SKELETON_ROW_SPACER_DP: Int = ROW_PITCH_DP - POSTER_HEIGHT_DP

    /** Spec §4.2 and §4.5: the grid starts under the band, collapsed or not. */
    fun gridTopDp(collapsed: Boolean): Int =
        if (collapsed) BAND_TOP_COLLAPSED_DP else BAND_TOP_EXPANDED_DP

    /**
     * The grid's own height, so it ends at the safe bottom instead of running past it. A grid that
     * is taller than the screen composes cells nobody can see and tells `bringIntoView` that the
     * bottom row is already visible, which strands the ring off-screen.
     */
    fun gridHeightDp(collapsed: Boolean): Int = CONTENT_HEIGHT_DP - gridTopDp(collapsed)

    fun choices(catalogs: List<Pair<Addon, AddonCatalog>>): List<DiscoverCatalogChoice> {
        val seen = mutableSetOf<String>()
        return catalogs.mapNotNull { (addon, catalog) ->
            val key = catalog.uid(addon.manifestURL)
            if (!seen.add(key)) return@mapNotNull null
            DiscoverCatalogChoice(
                key = key,
                addonName = addon.displayName,
                catalogName = catalog.name.ifBlank { catalog.id },
                manifestUrl = addon.manifestURL,
                type = catalog.type,
                catalogId = catalog.id,
                genres = catalog.genres,
            )
        }
    }

    fun forType(choices: List<DiscoverCatalogChoice>, type: DiscoverType): List<DiscoverCatalogChoice> =
        choices.filter { it.type.equals(type.wire, ignoreCase = true) }

    /**
     * Spec §4.11.3: changing the Type chip keeps the Genre when the new catalog has it, and clears
     * it when it does not. No toast either way — the chip label says "All genres" and that is that.
     */
    fun retainGenre(current: String?, genres: List<String>): String? =
        current?.takeIf { wanted -> genres.any { it.equals(wanted, ignoreCase = true) } }

    /** Which catalog the new type opens on: the first one it has. */
    fun defaultCatalogKey(choices: List<DiscoverCatalogChoice>, type: DiscoverType): String? =
        forType(choices, type).firstOrNull()?.key

    /**
     * Spec §4.7: the next page is asked for once the focused row passes 70% of the loaded rows.
     * Appending never moves the focused cell, so this is safe to fire while the ring is moving.
     */
    fun shouldLoadMore(focusedIndex: Int, loadedCount: Int, columns: Int = COLUMNS): Boolean {
        if (loadedCount <= 0 || focusedIndex < 0) return false
        val loadedRows = (loadedCount + columns - 1) / columns
        val focusedRow = focusedIndex / columns
        // focusedRow + 1 is how many rows deep the ring is. A part-filled last row still counts,
        // because the viewer has reached the end of what is loaded either way.
        return (focusedRow + 1) >= loadedRows * PREFETCH_FRACTION
    }

    /**
     * Which cells may start a poster request, spec §4.11.10.
     *
     * Only what is on screen, plus one row of look-ahead so the next row is ready by the time the
     * ring reaches it. Holding DOWN used to start a request for every cell the moment it composed,
     * which is about 54 fetches a second on a box with one radio.
     */
    fun requestWindow(
        firstVisible: Int,
        lastVisible: Int,
        itemCount: Int,
        columns: Int = COLUMNS,
        lookAheadRows: Int = 1,
    ): IntRange {
        if (itemCount <= 0) return IntRange.EMPTY
        val first = firstVisible.coerceIn(0, itemCount - 1)
        val last = (lastVisible + columns * lookAheadRows).coerceIn(first, itemCount - 1)
        return first..last
    }

    /**
     * Spec §4.2 and §4.11.2: the count comes from the catalog's own total, and the line is dropped
     * rather than guessed. `CatalogPage` carries no total, so the only honest count is the one we
     * have in hand once the add-on says there is no further page.
     */
    fun countLine(loading: Boolean, itemCount: Int, hasMore: Boolean): String? = when {
        loading -> "Loading"
        hasMore || itemCount <= 0 -> null
        itemCount == 1 -> "1 title"
        else -> "${grouped(itemCount)} titles"
    }

    /**
     * Whether the empty state's Clear genre button owns the ring, spec §4.9.
     *
     * A filtered catalog can legitimately answer with no titles. The grid then disappears, so its
     * focused cell disappears with it. Unless the visible recovery action requests focus, the remote
     * is left with no target below the chips and the shell rescue net may open the rail instead.
     */
    fun shouldFocusEmptyAction(
        settingsLoaded: Boolean,
        loading: Boolean,
        failed: Boolean,
        itemCount: Int,
        hasGenre: Boolean,
    ): Boolean = settingsLoaded && !loading && !failed && itemCount == 0 && hasGenre

    /** 1284 reads as 1,284. Hand-grouped so the line never changes with the box's locale. */
    private fun grouped(value: Int): String {
        val digits = value.toString()
        return digits.reversed().chunked(3).joinToString(",").reversed()
    }

    /** Spec §4.5: the collapsed strip is a readout, never a control. */
    fun summaryStrip(type: DiscoverType, catalogName: String?, genre: String?): String =
        summaryParts(type, catalogName, genre, null).joinToString("") { it.text }

    /**
     * Spec §4.5: three inks in one line. "Discover" is the word that names the screen; everything
     * after it is a value the viewer chose, and the separators sit back.
     */
    fun summaryParts(
        type: DiscoverType,
        catalogName: String?,
        genre: String?,
        countLine: String?,
    ): List<StripPart> {
        val values = listOfNotNull(type.label, catalogName, genre, countLine)
        val parts = mutableListOf(StripPart("Discover", StripInk.Word))
        values.forEach { value ->
            parts += StripPart(" · ", StripInk.Separator)
            parts += StripPart(value, StripInk.Value)
        }
        return parts
    }

    /** Spec §4.4: catalogs grouped by add-on, one group header above each run. */
    fun panelRows(choices: List<DiscoverCatalogChoice>): List<CatalogPanelRow> {
        val rows = mutableListOf<CatalogPanelRow>()
        var lastAddon: String? = null
        choices.forEach { choice ->
            if (choice.addonName != lastAddon) {
                lastAddon = choice.addonName
                rows += CatalogPanelRow.Group(choice.addonName)
            }
            rows += CatalogPanelRow.Choice(choice)
        }
        return rows
    }

    /** Where the panel opens: on the current choice, or on the first row when there is none. */
    fun panelRowIndex(rows: List<CatalogPanelRow>, selectedKey: String?): Int {
        val index = rows.indexOfFirst { it is CatalogPanelRow.Choice && it.choice.key == selectedKey }
        return if (index >= 0) index else 0
    }
}

/**
 * Discover's data: one catalog at a time, paged by `skip`.
 *
 * Every chip change starts a new generation. A page that lands for an older generation is dropped
 * rather than appended, because the grid it belonged to is no longer on screen.
 */
internal class DiscoverViewModel(
    private val scope: CoroutineScope,
    private val client: ClientGraph,
) {
    private val mutableState = MutableStateFlow(DiscoverState())
    val state: StateFlow<DiscoverState> = mutableState.asStateFlow()

    private var generation = 0
    private var skip = 0

    fun start() {
        scope.launch(Dispatchers.Default) {
            // A TV import or trusted phone sync constructs a new registry. Leave the old
            // subscription and re-plan against the new one without requiring navigation away.
            client.services.filterNotNull().collectLatest { services ->
                val choices = DiscoverPlan.choices(services.registry.catalogs())
                val current = mutableState.value
                val keep = current.selectedCatalogKey?.takeIf { key -> choices.any { it.key == key } }
                mutableState.value = current.copy(
                    catalogs = choices,
                    selectedCatalogKey = keep ?: DiscoverPlan.defaultCatalogKey(choices, current.type),
                    settingsLoaded = true,
                )
                reload()
                // The registry fills manifests in after the network answers, so choices grow.
                services.registry.addons.collect {
                    val refreshed = DiscoverPlan.choices(services.registry.catalogs())
                    if (refreshed.map { it.key } == mutableState.value.catalogs.map { it.key }) return@collect
                    val previous = mutableState.value
                    val selected = previous.selectedCatalogKey?.takeIf { key -> refreshed.any { it.key == key } }
                    mutableState.value = previous.copy(
                        catalogs = refreshed,
                        selectedCatalogKey = selected ?: DiscoverPlan.defaultCatalogKey(refreshed, previous.type),
                    )
                    if (selected == null) reload()
                }
            }
        }
    }

    fun selectType(type: DiscoverType) {
        val current = mutableState.value
        if (current.type == type) return
        val key = DiscoverPlan.defaultCatalogKey(current.catalogs, type)
        val genres = current.catalogs.firstOrNull { it.key == key }?.genres.orEmpty()
        mutableState.value = current.copy(
            type = type,
            selectedCatalogKey = key,
            genre = DiscoverPlan.retainGenre(current.genre, genres),
        )
        reload()
    }

    fun selectCatalog(key: String) {
        val current = mutableState.value
        val choice = current.catalogs.firstOrNull { it.key == key } ?: return
        val type = DiscoverType.entries.firstOrNull { it.wire == choice.type } ?: current.type
        if (current.selectedCatalogKey == key && current.type == type) return
        val genres = choice.genres
        mutableState.value = current.copy(
            type = type,
            selectedCatalogKey = key,
            genre = DiscoverPlan.retainGenre(current.genre, genres),
        )
        reload()
    }

    fun selectGenre(genre: String?) {
        val current = mutableState.value
        if (current.genre == genre) return
        mutableState.value = current.copy(genre = genre)
        reload()
    }

    fun retry() = reload()

    /** Called as the ring crosses the prefetch line. Safe to call on every move; it coalesces. */
    fun maybeLoadMore(focusedIndex: Int) {
        val current = mutableState.value
        if (current.loading || current.loadingMore || !current.hasMore) return
        if (!DiscoverPlan.shouldLoadMore(focusedIndex, current.items.size)) return
        loadPage(append = true)
    }

    /**
     * A chip change keeps the old cells on screen until the new page lands (spec §4.8). Emptying
     * the list first blanked the whole grid to a skeleton for as long as the add-on took to answer,
     * which on this fleet is often two seconds.
     */
    private fun reload() {
        skip = 0
        generation++
        mutableState.value = mutableState.value.copy(loading = true, failed = false, hasMore = false)
        loadPage(append = false)
    }

    private fun loadPage(append: Boolean) {
        val services = client.services.value ?: return
        val choice = mutableState.value.selected ?: run {
            mutableState.value = mutableState.value.copy(items = emptyList(), loading = false, loadingMore = false)
            return
        }
        val addon = services.registry.addon(choice.manifestUrl) ?: return
        val token = generation
        val wanted = skip
        if (append) mutableState.value = mutableState.value.copy(loadingMore = true)
        // Dispatchers.IO: OkHttp answers off the main thread, but the continuation that parses a
        // 2 MB catalog body resumes on the dispatcher that launched it. On Main that is an ANR.
        scope.launch(Dispatchers.IO) {
            val page = runCatching {
                services.client.catalog(
                    addon = addon,
                    type = choice.type,
                    id = choice.catalogId,
                    extra = CatalogExtra(skip = wanted, genre = mutableState.value.genre),
                )
            }.getOrNull()
            if (token != generation) return@launch
            val current = mutableState.value
            if (page == null) {
                // A page-2 failure keeps page 1 on screen; a page-1 failure is the error state.
                mutableState.value = current.copy(
                    items = if (append) current.items else emptyList(),
                    page = if (append) current.page else current.page + 1,
                    loading = false,
                    loadingMore = false,
                    failed = !append,
                    hasMore = false,
                )
                return@launch
            }
            val incoming = page.items.map { it.toHomeCard() }
            val merged = if (append) mergeAppend(current.items, incoming) else incoming
            skip = merged.size
            mutableState.value = current.copy(
                items = merged,
                page = if (append) current.page else current.page + 1,
                loading = false,
                loadingMore = false,
                failed = false,
                hasMore = page.hasMore && incoming.isNotEmpty(),
            )
        }
    }

    /** Appending never reorders or removes: what is drawn stays exactly where it is. */
    private fun mergeAppend(existing: List<HomeCard>, incoming: List<HomeCard>): List<HomeCard> {
        val seen = existing.mapTo(mutableSetOf()) { it.key }
        return existing + incoming.filter { seen.add(it.key) }
    }

    // -------------------------------------------------------------- the Rows variant

    private val mutableRails = MutableStateFlow<List<DiscoverRail>>(emptyList())

    /** Spec §4.6, behind Settings → Look → Discover layout → Rows. */
    val rails: StateFlow<List<DiscoverRail>> = mutableRails.asStateFlow()

    private var railGeneration = 0

    /**
     * One rail per genre of the chosen catalog. A catalog that declares no genres gets one rail per
     * catalog of the chosen type instead, which is the spec's "Catalog set to All" case.
     */
    fun loadRails() {
        val services = client.services.value ?: return
        val current = mutableState.value
        val choice = current.selected
        val token = ++railGeneration
        val plan: List<Triple<String, String, CatalogExtra>> = when {
            choice != null && choice.genres.isNotEmpty() ->
                choice.genres.take(MAX_RAILS).map { genre ->
                    Triple("${choice.key}|$genre", genre, CatalogExtra(genre = genre))
                }
            else -> DiscoverPlan.forType(current.catalogs, current.type).take(MAX_RAILS).map {
                Triple(it.key, it.catalogName, CatalogExtra())
            }
        }
        mutableRails.value = plan.map { DiscoverRail(it.first, it.second, emptyList()) }
        plan.forEach { (key, title, extra) ->
            val source = if (choice != null && choice.genres.isNotEmpty()) {
                choice
            } else {
                current.catalogs.firstOrNull { it.key == key } ?: return@forEach
            }
            val addon = services.registry.addon(source.manifestUrl) ?: return@forEach
            scope.launch(Dispatchers.IO) {
                val page = railGate.withPermit {
                    runCatching {
                        services.client.catalog(addon, source.type, source.catalogId, extra)
                    }.getOrNull()
                }
                if (token != railGeneration) return@launch
                val items = page?.items?.map { it.toHomeCard() }.orEmpty()
                // A rail with nothing in it is removed, not drawn empty (plan §7.4 rule 4).
                mutableRails.value = mutableRails.value.mapNotNull { rail ->
                    when {
                        rail.key != key -> rail
                        items.isEmpty() -> null
                        else -> rail.copy(items = items, title = title)
                    }
                }
            }
        }
    }

    /** Four sockets at a time. The box has one radio and a small heap. */
    private val railGate = Semaphore(4)

    private companion object {
        /** Twelve rails is already more than a viewer will walk; each one is a network call. */
        const val MAX_RAILS = 12
    }
}

/** One rail in the Rows variant. */
@Immutable
internal data class DiscoverRail(val key: String, val title: String, val items: List<HomeCard>)
