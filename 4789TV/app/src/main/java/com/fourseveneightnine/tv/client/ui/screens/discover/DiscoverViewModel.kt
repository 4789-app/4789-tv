package com.fourseveneightnine.tv.client.ui.screens.discover

import com.fourseveneightnine.tv.client.letterboxdListKey
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.fourseveneightnine.tv.ui.TvTokens
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fourseveneightnine.tv.catalog.TVLetterboxdClient
import com.fourseveneightnine.tv.catalog.TVLetterboxdList
import com.fourseveneightnine.tv.client.ClientGraph
import com.fourseveneightnine.tv.client.data.addons.Addon
import com.fourseveneightnine.tv.client.data.addons.AddonCatalog
import com.fourseveneightnine.tv.client.data.addons.CatalogExtra
import com.fourseveneightnine.tv.client.data.addons.CatalogResponseException
import com.fourseveneightnine.tv.client.data.addons.HttpStatusException
import com.fourseveneightnine.tv.client.data.catalog.Shelf
import com.fourseveneightnine.tv.client.data.catalog.ShelfKind
import com.fourseveneightnine.tv.client.data.library.CollectionDetail
import com.fourseveneightnine.tv.client.ui.screens.collections.SyncedLists
import com.fourseveneightnine.tv.client.ui.screens.search.SearchSections
import com.fourseveneightnine.tv.client.ui.screens.home.HomeCard
import com.fourseveneightnine.tv.client.ui.screens.home.toHomeCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** The three Type chips, spec §4.3. The value is the Stremio media type the catalog declares. */
internal enum class DiscoverType(val label: String, val wire: String) {
    Movies("Movies", "movie"),
    Series("Series", "series"),
    Anime("Anime", "anime"),
}

internal enum class CatalogSource(val label: String) {
    Saved("Your saved titles"),
    Letterboxd("Letterboxd"),
    Stremio("Stremio"),
}

/** One catalog or local list in the Catalog panel. */
@Immutable
internal data class DiscoverCatalogChoice(
    val key: String,
    val addonName: String,
    val catalogName: String,
    val manifestUrl: String,
    val type: String,
    val catalogId: String,
    val genres: List<String>,
    val collectionId: Long? = null,
    val shelfId: String? = null,
    val requiredGenre: String? = null,
    val likedList: TVLetterboxdList? = null,
    val phoneProfiles: List<String>? = null,
    val isWatchlist: Boolean = false,
) {
    val source: CatalogSource get() = when {
        shelfId != null || likedList != null -> CatalogSource.Letterboxd
        collectionId != null -> CatalogSource.Saved
        else -> CatalogSource.Stremio
    }
    val isLocal: Boolean get() = likedList == null && source != CatalogSource.Stremio
}

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
    val failureMessage: String? = null,
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

    data class Section(val source: CatalogSource) : CatalogPanelRow {
        override val key: String get() = "section:${source.name}"
    }

    data class Group(val addonName: String, val sourceKey: String = addonName) : CatalogPanelRow {
        override val key: String get() = "group:$sourceKey"
    }

    data class Choice(val choice: DiscoverCatalogChoice) : CatalogPanelRow {
        override val key: String get() = "choice:${choice.key}"
    }
}

/** Everything Discover decides that is not a network call. Kept free of Compose and of Android. */
internal object DiscoverPlan {

    /** The next page is asked for when the focused row passes this much of what is loaded. */
    const val PREFETCH_FRACTION: Float = 0.7f

    const val COLUMNS: Int = TvTokens.Geometry.CatalogPosterColumns

    // ------------------------------------------------------------------ geometry, spec §4.2
    //
    // Every number below is design-canvas dp. They live here rather than in the composables so the
    // skeleton and the real cell cannot drift apart again: the grid used to jump 20 px when the
    // first page landed because the two were written out by hand in different files.

    /** 1080 minus the 54 px safe top. The screen pads by the safe top, so this is what is left. */
    const val CONTENT_HEIGHT_DP: Int = 1026
    const val BAND_TOP_EXPANDED_DP: Int = 162
    const val BAND_TOP_COLLAPSED_DP: Int = 72

    const val POSTER_HEIGHT_DP: Int = TvTokens.Geometry.CatalogPosterHeightDp
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
            if (!catalog.canBrowse) return@mapNotNull null
            val key = catalog.uid(addon.manifestURL)
            if (!seen.add(key)) return@mapNotNull null
            DiscoverCatalogChoice(
                key = key,
                addonName = addon.displayName,
                catalogName = catalog.name.ifBlank { catalog.id },
                manifestUrl = addon.manifestURL,
                type = catalog.type,
                catalogId = catalog.id,
                genres = catalog.genres.filter(String::isNotBlank).distinct(),
                requiredGenre = catalog.requiredBrowseGenre,
            )
        }
    }

    fun forType(choices: List<DiscoverCatalogChoice>, type: DiscoverType): List<DiscoverCatalogChoice> =
        choices.filter { it.type.equals(type.wire, ignoreCase = true) }

    fun catalogPanelChoices(
        choices: List<DiscoverCatalogChoice>,
        type: DiscoverType,
        source: CatalogSource?,
        allTypes: Boolean,
    ): List<DiscoverCatalogChoice> =
        if (allTypes || source == CatalogSource.Letterboxd) choices else forType(choices, type)

    /**
     * Spec §4.11.3: changing the Type chip keeps the Genre when the new catalog has it, and clears
     * it when it does not. No toast either way — the chip label says "All genres" and that is that.
     */
    fun retainGenre(current: String?, genres: List<String>): String? =
        current?.let { wanted -> genres.firstOrNull { it.equals(wanted, ignoreCase = true) } }

    fun genreFor(choice: DiscoverCatalogChoice?, current: String?): String? =
        retainGenre(current, choice?.genres.orEmpty()) ?: choice?.requiredGenre

    fun catalogError(error: Throwable?): String = when ((error as? HttpStatusException)?.code) {
        401, 403 -> "The add-on refused access. Check its setup in Settings."
        404 -> "The add-on couldn't find this catalog."
        301, 302, 303, 307, 308 -> "The add-on moved this catalog. Check its address in Settings."
        429 -> "The add-on received too many requests. Try again shortly."
        in 500..599 -> "The add-on reported a server error. Try again later."
        else -> when (error) {
            is CatalogResponseException -> "The add-on returned catalog data 4789 couldn't read."
            is java.net.SocketTimeoutException -> "The add-on took too long to respond."
            is java.net.UnknownHostException -> "The TV couldn't reach the add-on server."
            else -> "The add-on couldn't load this catalog."
        }
    }

    /** Keep the existing Stremio default when the TV also has local lists. */
    fun defaultCatalogKey(choices: List<DiscoverCatalogChoice>, type: DiscoverType): String? {
        val matching = forType(choices, type)
        return (matching.firstOrNull { it.source == CatalogSource.Stremio }
            ?: matching.firstOrNull { it.source == CatalogSource.Letterboxd }
            ?: matching.firstOrNull())?.key
    }

    fun savedCatalogs(collections: List<CollectionDetail>): List<DiscoverCatalogChoice> =
        collections.flatMap { collection -> DiscoverType.entries.map { type ->
            DiscoverCatalogChoice("saved:${collection.id}:${type.wire}", "Your saved titles", collection.name,
                "", type.wire, "", emptyList(), collectionId = collection.id,
                isWatchlist = collection.sourceRef == com.fourseveneightnine.tv.client.data.library.SystemCollections.WATCHLIST_REF)
        } }

    fun letterboxdShelves(shelves: List<Shelf>): List<Shelf> =
        shelves.filter { it.kind == ShelfKind.LETTERBOXD || it.kind == ShelfKind.LETTERBOXD_FRIENDS }

    fun phoneLikedChoices(choices: List<DiscoverCatalogChoice>, username: String): List<DiscoverCatalogChoice> =
        choices.filter { choice ->
            choice.phoneProfiles?.let { profiles ->
                username.isBlank() || profiles.isEmpty() || profiles.any { it.equals(username, ignoreCase = true) }
            } == true
        }

    fun letterboxdChoices(shelves: List<Shelf>): List<DiscoverCatalogChoice> =
        letterboxdShelves(shelves).flatMap { shelf ->
            val types = DiscoverType.entries.filter { type -> shelf.items.any { it.mediaType == type.wire } }
                .ifEmpty { if (shelf.kind == ShelfKind.LETTERBOXD && shelf.items.isEmpty()) listOf(DiscoverType.Movies) else emptyList() }
            types.map { type ->
                DiscoverCatalogChoice("letterboxd:${shelf.id}:${type.wire}", "Letterboxd", shelf.title,
                    "", type.wire, "", emptyList(), shelfId = shelf.id)
            }
        }

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
        savedList: Boolean = false,
    ): Boolean = settingsLoaded && !loading && !failed && itemCount == 0 && (hasGenre || savedList)

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

    fun filterCatalogs(
        choices: List<DiscoverCatalogChoice>,
        query: String,
        source: CatalogSource? = null,
    ): List<DiscoverCatalogChoice> {
        val terms = SearchSections.normalized(query).split(' ').filter(String::isNotEmpty)
        return choices.filter { choice ->
            (source == null || source == choice.source) && (terms.isEmpty() ||
                terms.all(SearchSections.normalized("${choice.catalogName} ${choice.addonName} ${choice.source.label}")::contains))
        }
    }

    fun panelRows(choices: List<DiscoverCatalogChoice>): List<CatalogPanelRow> = buildList {
        val bySource = choices.groupBy { it.source }
        CatalogSource.entries.forEach { source ->
            val entries = bySource[source] ?: return@forEach
            add(CatalogPanelRow.Section(source))
            var lastAddon: String? = null
            for (choice in entries) {
                if (source == CatalogSource.Stremio && choice.manifestUrl != lastAddon) {
                    lastAddon = choice.manifestUrl
                    add(CatalogPanelRow.Group(choice.addonName, choice.key))
                }
                add(CatalogPanelRow.Choice(choice))
            }
        }
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
    private val client: ClientGraph,
    private val preferences: android.content.SharedPreferences,
) : ViewModel() {
    private val scope = viewModelScope
    private var railsStarted = false
    var detailReturnCardKey: String? = null
    var detailReturnRailKey: String? = null
    // A directory is a parent of its catalog, not a temporary filter dialog. Memory only:
    // keys can contain credentialed manifest URLs.
    var directoryActive by androidx.compose.runtime.mutableStateOf(false)
    var directoryFromHome by androidx.compose.runtime.mutableStateOf(false)
    var directoryOpen by androidx.compose.runtime.mutableStateOf(false)
    var directoryQuery by androidx.compose.runtime.mutableStateOf("")
    var directorySource by androidx.compose.runtime.mutableStateOf<CatalogSource?>(null)
    var directoryFocusKey by androidx.compose.runtime.mutableStateOf<String?>(null)
    var likedCatalogActive by androidx.compose.runtime.mutableStateOf(false)
    var likedIndexLoading by androidx.compose.runtime.mutableStateOf(false)
        private set
    var likedIndexMessage by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set
    private var likedIndexJob: Job? = null
    private var likedIndexGeneration = 0
    private var likedIndexUser: String? = null
    private var likedIndexTime = 0L
    private var likedChoices: List<DiscoverCatalogChoice> = emptyList()
    var lastContentCardKey: String? = null
    var lastContentRailKey: String? = null
    private val mutableState = MutableStateFlow(DiscoverState())
    val state: StateFlow<DiscoverState> = mutableState.asStateFlow()

    private var generation = 0
    private var pageJob: Job? = null
    private val railJobs = mutableListOf<Job>()
    private var skip = 0
    @Volatile private var savedCollections: List<CollectionDetail> = emptyList()
    @Volatile private var remoteCatalogs: List<DiscoverCatalogChoice> = emptyList()
    @Volatile private var letterboxdShelves: Map<String, Shelf> = emptyMap()
    private var letterboxdChoices: List<DiscoverCatalogChoice> = emptyList()

    private fun categoryKey(catalogKey: String): String =
        "catalog_category_v1_${catalogPreferenceSuffix(client.activeProfileId(), catalogKey)}"

    private fun restoreGenre(choice: DiscoverCatalogChoice?, fallback: String?): String? =
        DiscoverPlan.genreFor(choice, choice?.let { preferences.getString(categoryKey(it.key), null) } ?: fallback)

    private fun refreshChoices(settingsLoaded: Boolean? = null) {
        mutableState.update { current ->
            val choices = DiscoverPlan.savedCatalogs(savedCollections) + letterboxdChoices + remoteCatalogs + likedChoices
            val keep = current.selectedCatalogKey?.takeIf { key -> choices.any { it.key == key } }
            val ready = settingsLoaded ?: current.settingsLoaded
            val key = keep ?: if (ready || letterboxdChoices.isNotEmpty()) {
                DiscoverPlan.defaultCatalogKey(choices, current.type)
            } else null
            val choice = choices.firstOrNull { it.key == key }
            current.copy(catalogs = choices, settingsLoaded = ready, selectedCatalogKey = key,
                genre = if (keep == null) restoreGenre(choice, current.genre) else DiscoverPlan.genreFor(choice, current.genre))
        }
    }

    private fun localItems(choice: DiscoverCatalogChoice, limit: Int = Int.MAX_VALUE): List<HomeCard> {
        val shelf = choice.shelfId?.let(letterboxdShelves::get)
        if (shelf != null) {
            return shelf.items.asSequence().filter { choice.phoneProfiles != null || it.mediaType == choice.type }
                .take(limit).map { it.toHomeCard(recoverPoster = true) }.toList()
        }
        return savedCollections.firstOrNull { it.id == choice.collectionId }?.items.orEmpty().asSequence()
            .filter { it.mediaType == choice.type }.take(limit)
            .map { HomeCard(it.canonicalId, it.mediaType,
                com.fourseveneightnine.tv.client.ui.screens.collections.titleWithoutYear(it.title), posterUrl = it.posterUrl,
                year = com.fourseveneightnine.tv.client.ui.screens.collections.yearIn(it.title)) }.toList()
    }

    private fun updateLikedChoices(choices: List<DiscoverCatalogChoice>) {
        val oldKey = mutableState.value.selectedCatalogKey
        likedChoices = choices
        refreshChoices()
        if (oldKey?.startsWith("liked:") == true && oldKey != mutableState.value.selectedCatalogKey) reload()
    }

    fun refreshLikedLists(username: String, force: Boolean = false) {
        if (!force && DiscoverPlan.phoneLikedChoices(mutableState.value.catalogs, username).isNotEmpty()) {
            if (username != likedIndexUser) {
                likedIndexGeneration += 1
                likedIndexJob?.cancel()
                updateLikedChoices(emptyList())
                likedIndexUser = username
                likedIndexLoading = false
                likedIndexMessage = null
            }
            return
        }
        if (username == likedIndexUser && !force &&
            (likedIndexLoading || android.os.SystemClock.elapsedRealtime() - likedIndexTime < 15 * 60_000L)
        ) return
        val indexGeneration = ++likedIndexGeneration
        likedIndexJob?.cancel()
        if (username != likedIndexUser) updateLikedChoices(emptyList())
        likedIndexUser = username
        likedIndexTime = android.os.SystemClock.elapsedRealtime()
        likedIndexMessage = null
        likedIndexLoading = TVLetterboxdClient.validUsername(username)
        if (!likedIndexLoading) return
        likedIndexJob = scope.launch {
            val entries = linkedMapOf<String, DiscoverCatalogChoice>()
            try {
                for (pageNumber in 1..50) {
                    val page = withContext(Dispatchers.IO) { client.letterboxd.likedLists(username, pageNumber) }
                    for (list in page.entries) {
                        entries.putIfAbsent(list.key, DiscoverCatalogChoice(
                            "liked:${list.key}", "Letterboxd", list.title, "", "movie",
                            list.key, emptyList(), likedList = list,
                        ))
                    }
                    updateLikedChoices(
                        if (page.hasNext) (entries.values + likedChoices).distinctBy(DiscoverCatalogChoice::key)
                        else entries.values.toList(),
                    )
                    if (!page.hasNext) break
                    check(page.entries.isNotEmpty() && pageNumber < 50) { "letterboxd_partial_index" }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val status = (error as? HttpStatusException)?.code
                Log.i("4789Letterboxd", "Liked lists refresh failed: ${status ?: error.javaClass.simpleName}")
                likedIndexMessage = when {
                    status == 403 || status == 429 || error.message == "letterboxd_verification" ->
                        "Letterboxd blocked the live refresh."
                    status == 404 -> "This Letterboxd profile is unavailable."
                    else -> "Couldn’t refresh every liked list."
                }
            } finally {
                if (likedIndexGeneration == indexGeneration) likedIndexLoading = false
            }
        }
    }

    private fun savedLikedItems(list: TVLetterboxdList): List<HomeCard> =
        letterboxdShelves["letterboxd:liked:${list.key}"]?.items.orEmpty()
            .map { it.toHomeCard(recoverPoster = true) }.distinctBy(HomeCard::key)

    private fun loadLikedPage(choice: DiscoverCatalogChoice, list: TVLetterboxdList, append: Boolean) {
        val token = generation
        val pageNumber = skip + 1
        if (append) mutableState.update { it.copy(loadingMore = true) }
        pageJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { client.letterboxd.films(list, pageNumber) }
                    .onFailure { if (it is CancellationException) throw it }
            }
            val page = result.getOrNull()
            if (generation != token || mutableState.value.selectedCatalogKey != choice.key) return@launch
            val saved = savedLikedItems(list)
            val incoming = withContext(Dispatchers.Default) {
                SyncedLists.publicCards(page?.entries.orEmpty(), saved, client.letterboxd::cachedPortrait)
            }
            if (generation != token || mutableState.value.selectedCatalogKey != choice.key) return@launch
            val current = mutableState.value
            val cards = when {
                append -> mergeAppend(current.items, incoming)
                page != null -> incoming
                else -> saved
            }
            if (page != null) skip = pageNumber
            mutableState.value = current.copy(
                items = cards, loading = false, loadingMore = false, hasMore = page?.hasNext == true,
                page = if (append) current.page else current.page + 1,
                failed = page == null && cards.isEmpty(),
                failureMessage = if (page == null) DiscoverPlan.catalogError(result.exceptionOrNull()) else null,
            )
        }
    }

    fun start() {
        scope.launch {
            client.activeLibrary.ensureSystemCollections()
            client.activeLibrary.savedCollections().flowOn(Dispatchers.Default).collect { collections ->
                savedCollections = collections
                refreshChoices()
                if (mutableState.value.selected?.collectionId != null) reload()
                else if (railsStarted) loadRails(retainLoaded = true)
            }
        }
        scope.launch {
            client.collectionCatalog.map { catalog ->
                val merged = DiscoverPlan.letterboxdShelves(catalog.shelves)
                val profiles = catalog.profilesByList
                val choices = DiscoverPlan.letterboxdChoices(merged).map { choice ->
                    val sourceProfiles = choice.shelfId?.let { profiles[letterboxdListKey(it)] }
                    if (sourceProfiles == null) choice else choice.copy(key = "phone-liked:${choice.shelfId}:${choice.type}", phoneProfiles = sourceProfiles)
                }
                merged.associateBy(Shelf::id) to choices
            }.flowOn(Dispatchers.Default).collect { (shelves, choices) ->
                val previous = mutableState.value.selected
                val oldShelf = previous?.shelfId?.let(letterboxdShelves::get)
                letterboxdShelves = shelves
                letterboxdChoices = choices
                refreshChoices()
                val selected = mutableState.value.selected
                if (selected?.key != previous?.key || selected?.shelfId?.let { oldShelf != shelves[it] } == true) {
                    reload()
                } else if (railsStarted && selected?.isLocal != true) loadRails(retainLoaded = true)
            }
        }
        scope.launch {
            // A TV import or trusted phone sync constructs a new registry. Leave the old
            // subscription and re-plan against the new one without requiring navigation away.
            client.services.filterNotNull().collectLatest { services ->
                val registry = services.registry
                var loadedRevision = registry.catalogRefreshes.value
                remoteCatalogs = withContext(Dispatchers.Default) {
                    DiscoverPlan.choices(registry.catalogs())
                }
                refreshChoices(settingsLoaded = true)
                reload()
                // A manual refresh can change catalog contents without changing its manifest.
                combine(registry.addons, registry.catalogRefreshes) { _, revision -> revision }.collect { revision ->
                    val refreshed = withContext(Dispatchers.Default) {
                        DiscoverPlan.choices(registry.catalogs())
                    }
                    val refresh = revision != loadedRevision
                    loadedRevision = revision
                    if (!refresh && refreshed == remoteCatalogs) return@collect
                    val previous = mutableState.value.selected
                    remoteCatalogs = refreshed
                    refreshChoices(settingsLoaded = true)
                    val selected = mutableState.value.selected
                    when {
                        selected != previous -> reload()
                        refresh && selected?.isLocal != true -> reload(retainItems = true)
                        railsStarted -> loadRails()
                    }
                }
            }
        }
    }

    fun selectType(type: DiscoverType) {
        val current = mutableState.value
        if (current.type == type) return
        val selected = current.selected
        val sameList = if (selected?.isLocal == true) current.catalogs.firstOrNull {
            it.type == type.wire && it.source == selected.source &&
                it.collectionId == selected.collectionId && it.shelfId == selected.shelfId
        } else null
        val key = sameList?.key ?: DiscoverPlan.defaultCatalogKey(current.catalogs, type)
        val choice = current.catalogs.firstOrNull { it.key == key }
        mutableState.value = current.copy(
            type = type,
            selectedCatalogKey = key,
            genre = restoreGenre(choice, current.genre),
        )
        reload()
    }

    fun selectCatalog(key: String) {
        val current = mutableState.value
        val choice = current.catalogs.firstOrNull { it.key == key } ?: return
        val type = DiscoverType.entries.firstOrNull { it.wire == choice.type } ?: current.type
        if (current.selectedCatalogKey == key && current.type == type) return
        mutableState.value = current.copy(
            type = type,
            selectedCatalogKey = key,
            genre = restoreGenre(choice, current.genre),
        )
        reload()
    }

    fun selectGenre(genre: String?) {
        val current = mutableState.value
        val next = DiscoverPlan.genreFor(current.selected, genre)
        current.selectedCatalogKey?.let { preferences.edit().putString(categoryKey(it), next.orEmpty()).apply() }
        if (current.genre == next) return
        mutableState.value = current.copy(genre = next)
        reload()
    }

    fun retry() = reload()

    fun refreshCatalog() = reload(retainItems = true)

    /** Called as the ring crosses the prefetch line. Safe to call on every move; it coalesces. */
    fun loadNextPage() {
        val current = mutableState.value
        if (!current.loading && !current.loadingMore && current.hasMore) loadPage(append = true)
    }

    fun maybeLoadMore(focusedIndex: Int, visibleCount: Int = mutableState.value.items.size) {
        val current = mutableState.value
        if (current.loading || current.loadingMore || !current.hasMore) return
        if (!DiscoverPlan.shouldLoadMore(focusedIndex, visibleCount)) return
        loadPage(append = true)
    }

    /** A changed catalog identity must never describe actionable cards from the previous list. */
    private fun reload(retainItems: Boolean = false) {
        pageJob?.cancel()
        skip = 0
        generation++
        val current = mutableState.value
        mutableState.value = current.copy(items = if (retainItems) current.items else emptyList(),
            loading = true, loadingMore = false, failed = false, failureMessage = null, hasMore = false)
        loadPage(append = false, retainItems = retainItems)
        if (railsStarted) loadRails()
    }

    private fun loadPage(append: Boolean, retainItems: Boolean = false) {
        val choice = mutableState.value.selected ?: run {
            mutableState.value = mutableState.value.copy(items = emptyList(), loading = false, loadingMore = false)
            return
        }
        choice.likedList?.let { list ->
            loadLikedPage(choice, list, append)
            return
        }
        if (choice.isLocal) {
            val token = generation
            pageJob = scope.launch(Dispatchers.Default) {
                val items = localItems(choice)
                withContext(Dispatchers.Main.immediate) {
                    mutableState.update { current ->
                        if (token != generation || current.selectedCatalogKey != choice.key) current
                        else current.copy(items = items.distinctBy(HomeCard::key), page = current.page + 1, loading = false,
                            loadingMore = false, failed = false, hasMore = false)
                    }
                }
            }
            return
        }
        val services = client.services.value
        val addon = services?.registry?.addon(choice.manifestUrl)
        if (services == null || addon == null) {
            mutableState.value = mutableState.value.copy(loading = false, loadingMore = false, failed = true,
                failureMessage = "This add-on is unavailable. Check its setup in Settings.")
            return
        }
        val token = generation
        val wanted = skip
        val wantedGenre = DiscoverPlan.genreFor(choice, mutableState.value.genre)
        if (append) mutableState.value = mutableState.value.copy(loadingMore = true)
        // Dispatchers.IO: OkHttp answers off the main thread, but the continuation that parses a
        // 2 MB catalog body resumes on the dispatcher that launched it. On Main that is an ANR.
        pageJob = scope.launch(Dispatchers.IO) {
            val result = runCatching {
                services.client.catalog(
                    addon = addon,
                    type = choice.type,
                    id = choice.catalogId,
                    extra = CatalogExtra(skip = wanted, genre = wantedGenre),
                    onCached = if (append) null else { cached ->
                        val cards = cached.items.map { it.toHomeCard() }.distinctBy(HomeCard::key)
                        withContext(Dispatchers.Main.immediate) {
                            if (token == generation) mutableState.update { current ->
                                current.copy(items = cards, loading = false, loadingMore = true,
                                    failed = false, failureMessage = null, hasMore = cached.hasMore)
                            }
                        }
                    },
                )
            }.onFailure { if (it is CancellationException) throw it }
            val page = result.getOrNull()
            val incoming = page?.items?.map { it.toHomeCard() }?.distinctBy(HomeCard::key).orEmpty()
            withContext(Dispatchers.Main.immediate) {
                if (token != generation) return@withContext
                val current = mutableState.value
                if (page == null) {
                    // Keep the previous list when refreshing the same catalog fails.
                    val keepItems = append || (retainItems && current.items.isNotEmpty())
                    mutableState.value = current.copy(
                        items = if (keepItems) current.items else emptyList(),
                        page = if (keepItems) current.page else current.page + 1,
                        loading = false,
                        loadingMore = false,
                        failed = !keepItems,
                        failureMessage = if (append) null else DiscoverPlan.catalogError(result.exceptionOrNull()),
                        hasMore = false,
                    )
                    return@withContext
                }
                val merged = if (append) mergeAppend(current.items, incoming) else incoming
                skip = wanted + page.items.size
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
    /** Start once per navigation entry. Returning from Detail must not clear populated rails. */
    fun enableRails() {
        if (railsStarted) return
        railsStarted = true
        loadRails()
    }

    private fun loadRails(retainLoaded: Boolean = false) {
        railJobs.forEach { it.cancel() }
        railJobs.clear()
        val current = mutableState.value
        val choice = current.selected
        val token = ++railGeneration
        if (choice?.likedList != null) {
            mutableRails.value = emptyList()
            return
        }
        if (choice?.isLocal == true) {
            mutableRails.value = emptyList()
            railJobs += scope.launch(Dispatchers.Default) {
                val items = localItems(choice)
                withContext(Dispatchers.Main.immediate) {
                    if (token == railGeneration && items.isNotEmpty()) {
                        mutableRails.value = listOf(DiscoverRail(choice.key, choice.catalogName, items))
                    }
                }
            }
            return
        }
        val services = client.services.value ?: return
        val plan: List<Triple<String, String, CatalogExtra>> = when {
            choice != null && choice.genres.isNotEmpty() ->
                choice.genres.take(MAX_RAILS).map { genre ->
                    Triple("${choice.key}|$genre", genre, CatalogExtra(genre = genre))
                }
            else -> DiscoverPlan.forType(current.catalogs, current.type).take(MAX_RAILS).map {
                Triple(it.key, it.catalogName, CatalogExtra(genre = it.requiredGenre))
            }
        }
        val retained = if (retainLoaded) mutableRails.value.associateBy { it.key } else emptyMap()
        mutableRails.value = plan.map { retained[it.first]?.copy(title = it.second)
            ?: DiscoverRail(it.first, it.second, emptyList()) }
        plan.forEach { (key, title, extra) ->
            val source = if (choice != null && choice.genres.isNotEmpty()) {
                choice
            } else {
                current.catalogs.firstOrNull { it.key == key } ?: return@forEach
            }
            if (source.isLocal) {
                val items = localItems(source, limit = 24)
                mutableRails.update { rails -> rails.mapNotNull { rail ->
                    if (rail.key != key) rail else if (items.isEmpty()) null else rail.copy(items = items)
                } }
                return@forEach
            }
            // Saving a local membership must not remove or refetch populated remote posters.
            if (retained[key]?.items?.isNotEmpty() == true) return@forEach
            val addon = services.registry.addon(source.manifestUrl) ?: return@forEach
            railJobs += scope.launch(Dispatchers.IO) {
                val page = railGate.withPermit {
                    runCatching {
                        services.client.catalog(addon, source.type, source.catalogId, extra)
                    }.onFailure { if (it is CancellationException) throw it }.getOrNull()
                }
                val items = page?.items?.map { it.toHomeCard() }?.distinctBy(HomeCard::key).orEmpty()
                withContext(Dispatchers.Main.immediate) {
                if (token != railGeneration) return@withContext
                // A rail with nothing in it is removed, not drawn empty (plan §7.4 rule 4).
                mutableRails.update { rails -> rails.mapNotNull { rail ->
                    when {
                        rail.key != key -> rail
                        items.isEmpty() -> null
                        else -> rail.copy(items = items, title = title)
                    }
                } }
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
