package com.fourseveneightnine.tv.client.ui.screens.home

import androidx.compose.runtime.Immutable
import com.fourseveneightnine.tv.client.ClientGraph
import com.fourseveneightnine.tv.client.data.addons.Addon
import com.fourseveneightnine.tv.client.data.addons.AddonCatalog
import com.fourseveneightnine.tv.client.data.addons.CatalogExtra
import com.fourseveneightnine.tv.client.data.catalog.CatalogItem
import com.fourseveneightnine.tv.client.data.catalog.Shelf
import com.fourseveneightnine.tv.client.data.catalog.ShelfKind
import com.fourseveneightnine.tv.client.data.catalog.SnapshotSource
import com.fourseveneightnine.tv.client.data.catalog.SnapshotState
import com.fourseveneightnine.tv.client.data.library.CollectionSummary
import com.fourseveneightnine.tv.client.data.library.ContinueItem
import com.fourseveneightnine.tv.client.data.refresh.Freshness
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock

// ------------------------------------------------------------------ UI models

/**
 * One browse card. Immutable and flat on purpose: the hero reads six fields off whatever card the
 * ring sits on, and a row of forty of these is compared on every recomposition.
 */
@Immutable
internal data class HomeCard(
    val id: String,
    val type: String,
    val title: String,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val logoUrl: String? = null,
    val year: Int? = null,
    val runtimeMinutes: Int? = null,
    val genre: String? = null,
    val rating: Double? = null,
    val overview: String? = null,
    val isNew: Boolean = false,
) {
    val key: String get() = "$type:$id"
}

/** One Continue Watching card. The line is built once, here, not in the row. */
@Immutable
internal data class HomeContinueCard(
    val card: HomeCard,
    val line: String,
    val progress: Float,
    val season: Int?,
    val episode: Int?,
    val resumeFromMs: Long,
    val stillUrl: String?,
)

/** One folder card on the Home collections row. */
@Immutable
internal data class HomeFolderCard(
    val id: Long,
    val name: String,
    val count: Int,
    val accent: String,
    val previewPosters: List<String>,
)

/** Which catalog a "See all" card opens as a full grid. */
@Immutable
internal data class HomeCatalogRef(
    val manifestUrl: String,
    val type: String,
    val catalogId: String,
    val name: String,
)

/** One row on Home. Every row carries its own stable key, so an insert never moves another row. */
@Immutable
internal sealed interface HomeRow {
    val key: String
    val title: String

    @Immutable
    data class AllCatalogs(val available: Boolean) : HomeRow {
        override val key: String get() = "row:all-catalogs"
        override val title: String get() = "All catalogs"
    }

    @Immutable
    data class Continue(val items: List<HomeContinueCard>) : HomeRow {
        override val key: String get() = KEY
        override val title: String get() = "Continue watching"

        companion object { const val KEY = "row:continue" }
    }

    @Immutable
    data class Collections(val items: List<HomeFolderCard>) : HomeRow {
        override val key: String get() = KEY
        override val title: String get() = "Your collections"

        companion object { const val KEY = "row:collections" }
    }

    /**
     * An add-on catalog or a snapshot shelf. [items] is empty while it is still loading.
     *
     * [removed] marks a row that answered empty or failed. It is not dropped from the list here:
     * dropping it under the ring pulls every row below it up one block, which is the band jumping
     * (spec §3.7). [HomePlan.visibleRows] decides when it may go.
     */
    @Immutable
    data class Posters(
        override val key: String,
        override val title: String,
        val items: List<HomeCard>,
        val staleLabel: String? = null,
        val loading: Boolean = false,
        val catalog: HomeCatalogRef? = null,
        val removed: Boolean = false,
    ) : HomeRow
}

/** Everything the screen draws. */
@Immutable
internal data class HomeState(
    val rows: List<HomeRow> = emptyList(),
    val settingsLoaded: Boolean = false,
    val loading: Boolean = true,
    val failed: Boolean = false,
) {
    /** A row that can take focus has items. A skeleton never does (plan §7.4 rule 4). */
    val focusableRows: List<HomeRow> get() = rows.filter { it.itemCount > 0 }

    val hasContent: Boolean get() = rows.any { it !is HomeRow.AllCatalogs && it.itemCount > 0 }
}

internal val HomeRow.itemCount: Int
    get() = when (this) {
        is HomeRow.Continue -> items.size
        is HomeRow.Collections -> items.size
        is HomeRow.Posters -> items.size
        is HomeRow.AllCatalogs -> if (available) 1 else 0
    }

// ------------------------------------------------------------------ pure logic

/**
 * The row-order and label rules, kept free of Compose and of Android so they can be tested on a
 * plain JVM. Everything the view model decides that is not "call this repository" lives here.
 */
internal object HomePlan {

    /**
     * Row order for a television: Continue, add-on catalogs, non-empty pinned collections, then
     * snapshot shelves. A collection with no titles is useful in the editor, not as the first giant
     * card on Home.
     *
     * Continue and collections keep their slots from the first publish even while they are empty.
     * Both come from Room flows that emit after the catalogs have painted, and inserting either at
     * index 0 later moved every row below it down 291 or 326 px (F04). An empty one holds its key,
     * takes no height and cannot be focused, so it is still "removed, not drawn empty"
     * (spec §3.9.9).
     */
    fun rows(
        continueItems: List<HomeContinueCard>,
        folders: List<HomeFolderCard>,
        catalogRows: List<HomeRow.Posters>,
        shelfRows: List<HomeRow.Posters>,
    ): List<HomeRow> = buildList {
        add(HomeRow.Continue(continueItems))
        add(HomeRow.AllCatalogs(catalogRows.isNotEmpty()))
        addAll(catalogRows)
        add(HomeRow.Collections(folders.filter { it.count > 0 }))
        // A signed snapshot can mirror a live add-on catalog. Drawing both produced consecutive
        // shelves with the exact same heading and made Home look duplicated. Prefer the live row
        // while it is healthy; if it later fails/empties, the snapshot is allowed back in.
        val liveTitles = catalogRows
            .asSequence()
            .filterNot(HomeRow.Posters::removed)
            .map { it.title.trim().lowercase() }
            .toSet()
        addAll(shelfRows.filter { shelf ->
            val shelfTitle = shelf.title.trim().lowercase()
            liveTitles.none { liveTitle ->
                liveTitle == shelfTitle || liveTitle.startsWith("$shelfTitle · ")
            }
        })
    }

    /** The first playable card seeds the stable hero before Compose places initial focus. */
    fun featuredCard(rows: List<HomeRow>): HomeCard? = rows.firstNotNullOfOrNull { row ->
        when (row) {
            is HomeRow.Continue -> row.items.firstOrNull()?.card
            is HomeRow.Posters -> row.items.firstOrNull()
            is HomeRow.Collections -> null
            is HomeRow.AllCatalogs -> null
        }
    }

    /**
     * The rows the band draws.
     *
     * Spec §3.7: "A row that answers empty is removed. If focus is inside or below that row, the
     * removal waits until focus moves above it." [focusedKey] is the row the ring sits on. A
     * removed row at or above it keeps its place and its height; only rows below the ring go, and
     * `Modifier.animateItem` slides the rest up rather than cutting (spec §3.6).
     *
     * With nothing focused there is no ring to protect, so every removed row goes at once.
     */
    fun visibleRows(rows: List<HomeRow>, focusedKey: String?): List<HomeRow> {
        if (rows.none { it is HomeRow.Posters && it.removed }) return rows
        val focused = rows.indexOfFirst { it.key == focusedKey }
        return rows.filterIndexed { index, row ->
            row !is HomeRow.Posters || !row.removed || index <= focused
        }
    }

    /**
     * Which row the rail's RIGHT and the shell's rescue net put the ring back on.
     *
     * Never row 1 when a lower row had it. Asking the column itself scrolls the band to its head,
     * and that snap is the second half of the movement the owner sees (F03). [lastFocusedRow] is
     * -1 until a card has been focused, and row 1 is the right answer then. A row index left over
     * from a longer list is clamped rather than thrown away, so the band still lands near where
     * the viewer was.
     */
    fun restoreRow(lastFocusedRow: Int, rowCount: Int): Int {
        if (rowCount <= 0) return 0
        return lastFocusedRow.coerceIn(0, rowCount - 1)
    }

    /**
     * Spec §3.7 Error: every source answered and none of them produced a row.
     *
     * "No add-ons at all" is the empty state, not this one, so a box that planned no catalog rows
     * never reads as failed.
     */
    fun failed(settled: Boolean, catalogRows: List<HomeRow.Posters>, rows: List<HomeRow>): Boolean =
        settled &&
            catalogRows.isNotEmpty() &&
            catalogRows.none { it.loading } &&
            rows.none { it !is HomeRow.AllCatalogs && it.itemCount > 0 }

    /** The stable key a catalog row keeps for the whole session. */
    fun catalogKey(manifestUrl: String, catalog: AddonCatalog): String = "catalog:" + catalog.uid(manifestUrl)

    /** How many catalog rows Home draws when the phone stated no order of its own. */
    const val CATALOG_ROW_CAP: Int = 12

    /**
     * The add-on catalog rows, in `catalogOrder`, as skeletons until each one answers.
     *
     * `AddonRegistry.catalogs()` returns every catalog every add-on declares and sorts it; it does
     * not choose. On the owner's box that is 342 catalogs, which is 342 network calls and 342 rows
     * on a screen a viewer walks with a D-pad. So `catalogOrder` is read as a choice as well as an
     * order: when the phone stated one, Home draws exactly those catalogs. With no stated order the
     * registry's first [CATALOG_ROW_CAP] stand in, which is a home screen rather than a directory.
     */
    fun catalogSkeletons(
        catalogs: List<Pair<Addon, AddonCatalog>>,
        catalogOrder: List<String> = emptyList(),
        cap: Int = CATALOG_ROW_CAP,
    ): List<HomeRow.Posters> {
        val seen = mutableSetOf<String>()
        val unique = catalogs.filter { (addon, catalog) -> seen.add(catalogKey(addon.manifestURL, catalog)) }
        // A phone order is a preference, never a whitelist. Older phone exports use a different
        // UID scheme, so filtering by it hid every unmatched catalog from Home.
        val rank = catalogOrder.withIndex().associate { (index, uid) -> uid to index }
        val chosen = unique.sortedBy { (addon, catalog) ->
            rank[catalog.uid(addon.manifestURL)] ?: rank[catalog.id] ?: Int.MAX_VALUE
        }.take(cap)
        // Two add-ons both declare a catalog called "New", and Home drew two headers reading "New"
        // one above the other (F29). A name only one add-on uses is left alone.
        val names = chosen.map { (addon, catalog) -> catalog.name.ifBlank { addon.displayName } }
        val shared = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        // Try the type first: Cinemeta's two "Popular" rows read "Popular movies" and "Popular
        // series", not "Popular · Cinemeta · Movie". The add-on name only comes in when two
        // add-ons still collide after that.
        val typed = chosen.mapIndexed { index, (_, catalog) ->
            val base = names[index]
            if (base in shared) "$base ${typeWord(catalog.type)}" else base
        }
        val sharedTyped = typed.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        return chosen.mapIndexed { index, pair ->
            val (addon, catalog) = pair
            val base = names[index]
            val name = if (typed[index] in sharedTyped && base != addon.displayName) {
                "$base · ${addon.displayName}"
            } else {
                typed[index]
            }
            HomeRow.Posters(
                key = catalogKey(addon.manifestURL, catalog),
                title = name,
                items = emptyList(),
                loading = true,
                catalog = HomeCatalogRef(addon.manifestURL, catalog.type, catalog.id, name),
            )
        }
    }

    private fun typeWord(type: String): String = when (type.lowercase()) {
        "movie" -> "movies"
        "tv", "channel" -> "channels"
        else -> type.lowercase()
    }

    /**
     * Fills one row's items in place.
     *
     * Order never changes: the row keeps the slot its key was given when the plan was made, which
     * is what "rows insert without moving focus" means in practice. A row that answered empty is
     * marked [HomeRow.Posters.removed] rather than dropped, because dropping it here moved every
     * row below it (F02). [visibleRows] drops it once the ring is above it.
     */
    fun applyResult(rows: List<HomeRow.Posters>, key: String, items: List<HomeCard>): List<HomeRow.Posters> =
        rows.map { row ->
            when {
                row.key != key -> row
                items.isEmpty() -> row.copy(items = emptyList(), loading = false, removed = true)
                else -> row.copy(items = items, loading = false, removed = false)
            }
        }

    /** Marks a row that failed. A failure leaves nothing behind, so it reads like an empty answer. */
    fun applyFailure(rows: List<HomeRow.Posters>, key: String): List<HomeRow.Posters> =
        rows.map { row ->
            when {
                row.key != key -> row
                row.items.isEmpty() -> row.copy(loading = false, removed = true)
                else -> row.copy(loading = false)
            }
        }

    /** Which signed snapshot a shelf came from. Drives the "hide the label while loading" rule. */
    /** Letterboxd rows on Home; the rest live in Collections. */
    const val MAX_LETTERBOXD_ROWS = 6

    fun sourceOf(kind: ShelfKind): SnapshotSource = when (kind) {
        ShelfKind.TMDB_MOVIES, ShelfKind.TMDB_SERIES -> SnapshotSource.PUBLIC_TMDB
        else -> SnapshotSource.PRIVATE_CATALOG
    }

    /**
     * "S2 E4 · 22 min left", spec §3.3.
     *
     * A row with an unknown duration keeps its place and says only what it knows; inventing
     * "0 min left" from a missing number is worse than saying nothing.
     */
    fun continueLine(item: ContinueItem): String {
        val parts = buildList {
            if (item.season != null && item.episode != null) add("S${item.season} E${item.episode}")
            else if (item.episode != null) add("E${item.episode}")
            remainingText(item.remainingMs, item.durationMs)?.let { add(it) }
        }
        return parts.joinToString(" · ")
    }

    /** The remaining-time half of the line, or null when the duration is unknown. */
    fun remainingText(remainingMs: Long, durationMs: Long): String? {
        if (durationMs <= 0L) return null
        val minutes = (remainingMs + 59_999L) / 60_000L
        return when {
            minutes <= 0L -> "finished"
            minutes < 60L -> "$minutes min left"
            else -> {
                val hours = minutes / 60
                val rest = minutes % 60
                if (rest == 0L) "${hours}h left" else "${hours}h ${rest}m left"
            }
        }
    }

    /** The hero button's label, spec §3.3. The button always says what it will do. */
    fun heroAction(card: HomeCard?, continueCard: HomeContinueCard?): String = when {
        card == null -> "Play"
        continueCard != null && continueCard.season != null && continueCard.episode != null ->
            "Resume S${continueCard.season} E${continueCard.episode}"
        continueCard != null -> "Resume " + watchedText(continueCard.resumeFromMs) + " in"
        card.type.equals("series", ignoreCase = true) -> "Play"
        else -> "Play"
    }

    /** "1h 12m", for the resume label. */
    fun watchedText(positionMs: Long): String {
        val minutes = positionMs / 60_000L
        val hours = minutes / 60
        val rest = minutes % 60
        return if (hours > 0) "${hours}h ${rest}m" else "${rest}m"
    }

    /**
     * The hero meta line: `2024 · 2h 09m · Thriller · IMDb 7.8`.
     *
     * A missing field drops itself and its separator (spec §3.8). With nothing left the line hides,
     * which is this returning an empty string.
     */
    fun metaLine(card: HomeCard?): String {
        if (card == null) return ""
        return buildList {
            card.year?.let { add(it.toString()) }
            card.runtimeMinutes?.takeIf { it > 0 }?.let { add(runtimeText(it)) }
            card.genre?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.joinToString(" · ")
    }

    fun runtimeText(minutes: Int): String {
        val hours = minutes / 60
        val rest = minutes % 60
        return if (hours > 0) "${hours}h ${rest.toString().padStart(2, '0')}m" else "${rest}m"
    }

    private fun trimZero(value: Double): String {
        val rounded = Math.round(value * 10.0) / 10.0
        return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
    }

    /** Added in the last 48 hours: the one badge a Home poster may carry (spec §3.3). */
    const val NEW_WINDOW_MILLIS: Long = 48L * 60 * 60 * 1000
}

// ------------------------------------------------------------------ mapping

internal fun CatalogItem.toHomeCard(): HomeCard = HomeCard(
    // Add-ons answer IMDb ids; a snapshot row that knows one is opened by it.
    id = imdbId?.takeIf { it.startsWith("tt") } ?: canonicalId,
    type = mediaType,
    title = title,
    posterUrl = posterUrl,
    backdropUrl = backdropUrl,
    year = year,
    genre = genres.firstOrNull(),
    overview = overview,
)

internal fun com.fourseveneightnine.contract.DiscoverItem.toHomeCard(): HomeCard = HomeCard(
    id = id,
    type = type,
    title = title,
    posterUrl = posterURL,
    backdropUrl = backdropURL,
    year = year,
    rating = rating,
    genre = genres.firstOrNull(),
    overview = description,
)

internal fun ContinueItem.toHomeContinueCard(): HomeContinueCard = HomeContinueCard(
    card = HomeCard(
        id = canonicalId,
        type = mediaType,
        title = title,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        overview = null,
    ),
    line = HomePlan.continueLine(this),
    progress = progressFraction,
    season = season,
    episode = episode,
    resumeFromMs = positionMs,
    stillUrl = backdropUrl ?: posterUrl,
)

internal fun CollectionSummary.toHomeFolderCard(): HomeFolderCard =
    HomeFolderCard(id = id, name = name, count = count, accent = accent, previewPosters = previewPosters)

// ------------------------------------------------------------------ view model

/**
 * Home's data. A plain class held by `remember`, not an Android `ViewModel`: it owns no state that
 * has to outlive the screen, and the scope it runs in is the composition's.
 *
 * Progressive paint is the whole design. Cached shelves and the library flows paint first; every
 * add-on catalog is a skeleton row in `catalogOrder` that fills itself in when it answers, and the
 * row's key never moves, so a row arriving cannot move the ring.
 */
internal class HomeViewModel(
    private val scope: CoroutineScope,
    private val client: ClientGraph,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutableState = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = mutableState.asStateFlow()

    private var catalogRows: List<HomeRow.Posters> = emptyList()
    private var continueCards: List<HomeContinueCard> = emptyList()
    private var folderCards: List<HomeFolderCard> = emptyList()
    private var shelfRows: List<HomeRow.Posters> = emptyList()
    private var settingsLoaded = false

    /**
     * The row the ring sits on. A removed row at or above it keeps its place (spec §3.7).
     *
     * Written under [publishGate] on a background dispatcher and read by [onRowFocused] on the
     * main thread, which is only a "has this changed" guard.
     */
    @Volatile
    private var focusedRowKey: String? = null
    private val publishGate = Mutex()

    /**
     * True once the first paint deadline has passed. Without it a television with no add-ons at all
     * would sweep its skeleton for ever: nothing is loading, and nothing is ever going to arrive.
     */
    private var settled = false

    fun start() {
        armSettleDeadline()
        scope.launch(Dispatchers.Default) {
            client.activeLibrary.continueWatching().collect { items ->
                val cards = items.map { it.toHomeContinueCard() }
                publish { continueCards = cards }
            }
        }
        scope.launch(Dispatchers.Default) {
            client.activeLibrary.collections().collect { list ->
                val cards = list.filter { it.pinnedHome }.map { it.toHomeFolderCard() }
                publish { folderCards = cards }
            }
        }
        // Dispatchers.Default, not the composition's Main scope. This mapping turns up to 124
        // shelves of up to 2,000 titles each into card models; on the main thread that is the
        // frame budget for several seconds and the box answers it with an ANR.
        scope.launch(Dispatchers.Default) {
            combine(client.snapshots.shelves(), client.snapshots.status()) { shelves, status ->
                // The private snapshot carries one Letterboxd shelf per list (124 on the owner's
                // box). Home shows the first few; every list stays reachable from Collections.
                var letterboxdShown = 0
                shelves.mapNotNull { shelf ->
                    if (shelf.kind == ShelfKind.LETTERBOXD) {
                        if (letterboxdShown >= HomePlan.MAX_LETTERBOXD_ROWS) return@mapNotNull null
                        letterboxdShown++
                    }
                    shelfRow(shelf, status.of(HomePlan.sourceOf(shelf.kind))?.state)
                }
            }.collect { rows ->
                ReceiverDiagnostics.record("home.shelves", "rows=${rows.size}")
                publish { shelfRows = rows }
            }
        }
        // The documented Home sequence (client-data API-B): hydrate paints from disk, then the
        // server is asked. `ClientGraph.start()` does the hydrate; without this the snapshot
        // shelves never arrive on a box that has not saved a generation yet.
        scope.launch(Dispatchers.Default) { runCatching { client.scheduler.onForeground().join() } }
        scope.launch(Dispatchers.Default) {
            // Inside the gate. Written outside it, a publish landing between this read and its
            // write lost that publish's rows (F47).
            // A trusted settings sync replaces DataServices and its registry. Switch to the new
            // registry so an already-open Home sees imports and removals without leaving the page.
            client.services.filterNotNull().collectLatest { services ->
                services.registry.addons
                    .map { services.registry.catalogs() }
                    .distinctUntilChanged { old, new -> old.map { it.planKey() } == new.map { it.planKey() } }
                    .collect { catalogs ->
                        publish { settingsLoaded = true }
                        loadCatalogs(catalogs)
                    }
                }
        }
    }

    /** Lets the skeleton stand for the deadline, then makes the screen say what it knows. */
    private fun armSettleDeadline() {
        scope.launch(Dispatchers.Default) {
            kotlinx.coroutines.delay(FIRST_PAINT_DEADLINE_MILLIS)
            settled = true
            publish()
        }
    }

    private fun Pair<Addon, AddonCatalog>.planKey(): String = HomePlan.catalogKey(first.manifestURL, second)

    private fun shelfRow(shelf: Shelf, state: SnapshotState?): HomeRow.Posters? {
        if (shelf.items.isEmpty()) return null
        // The label is hidden while that source is refreshing, and comes back only after 30
        // minutes (spec §15.6). It is a fact, not a fault.
        val label = if (state == SnapshotState.LOADING) null else Freshness.label(shelf.generatedAtMillis, now())
        return HomeRow.Posters(
            key = "shelf:" + shelf.id,
            title = shelf.title,
            items = shelf.items.map { it.toHomeCard() },
            staleLabel = label,
        )
    }

    private val inFlight = mutableSetOf<String>()

    private suspend fun loadCatalogs(catalogs: List<Pair<Addon, AddonCatalog>>) {
        val services = client.services.value ?: return
        val plan = HomePlan.catalogSkeletons(catalogs, services.registry.catalogOrder())
        ReceiverDiagnostics.record("home.catalogs", "planned=${plan.size} declared=${catalogs.size}")
        if (plan.isEmpty()) {
            publish { catalogRows = emptyList() }
            return
        }
        // Keep what has already answered; only genuinely new keys arrive as skeletons.
        val answered = catalogRows.associateBy { it.key }
        publish { catalogRows = plan.map { row -> answered[row.key] ?: row } }

        val planned = plan.mapTo(mutableSetOf()) { it.key }
        catalogs.forEach { (addon, catalog) ->
            val key = HomePlan.catalogKey(addon.manifestURL, catalog)
            if (key !in planned) return@forEach
            if (answered[key]?.loading == false) return@forEach
            if (!inFlight.add(key)) return@forEach
            // Dispatchers.IO, not the composition's Main scope. OkHttp answers off the main thread,
            // but the continuation — which is where a 2 MB catalog body is parsed into 100 DTOs —
            // resumes on whatever dispatcher launched it. Twelve of those on Main is five ANRs in a
            // row on the onn 4K Pro, which is exactly what the box reported.
            scope.launch(Dispatchers.IO) {
                val page = fetchGate.withPermit {
                    runCatching {
                        services.client.catalog(addon, catalog.type, catalog.id, CatalogExtra())
                    }.getOrNull()
                }
                inFlight.remove(key)
                // Catalog ids and counts only. A manifest URL is a credential for AIOStreams and
                // MediaFusion, so it never reaches a breadcrumb.
                ReceiverDiagnostics.record(
                    "home.catalog.result",
                    "id=${catalog.id} type=${catalog.type} items=${page?.items?.size ?: -1}",
                )
                val cards = page?.items?.map { it.toHomeCard() }
                publish {
                    catalogRows = if (cards == null) {
                        HomePlan.applyFailure(catalogRows, key)
                    } else {
                        HomePlan.applyResult(catalogRows, key, cards)
                    }
                }
            }
        }
    }

    /** Four at a time. A television with eleven add-ons must not open eleven sockets at once. */
    private val fetchGate = Semaphore(MAX_PARALLEL_FETCHES)

    /**
     * The one place the row fields are written.
     *
     * Four coroutines on three dispatchers feed this screen. Taking the mutation inside the lock
     * means a read-modify-write of `catalogRows` from two catalog results landing together cannot
     * lose one of them.
     */
    private suspend fun publish(mutate: () -> Unit = {}) = publishGate.withLock {
        mutate()
        publishNow()
    }

    private fun publishNow() {
        val planned = HomePlan.rows(continueCards, folderCards, catalogRows, shelfRows)
        val rows = HomePlan.visibleRows(planned, focusedRowKey)
        val shape = "total=${rows.size} catalogs=${catalogRows.size} shelves=${shelfRows.size} " +
            "continue=${continueCards.size} folders=${folderCards.size}"
        if (shape != lastLoggedShape) {
            lastLoggedShape = shape
            ReceiverDiagnostics.record("home.rows", shape)
        }
        mutableState.value = HomeState(
            rows = rows,
            settingsLoaded = settingsLoaded,
            loading = !settled && planned.none { it.itemCount > 0 },
            failed = HomePlan.failed(settled, catalogRows, planned),
        )
    }

    /**
     * The row the ring is on, reported by the screen.
     *
     * It decides when a removed row may leave the list, and nothing else, so a move that changes
     * nothing publishes nothing. Horizontal moves inside one row never reach here.
     */
    fun onRowFocused(key: String) {
        if (key == focusedRowKey) return
        scope.launch(Dispatchers.Default) { publish { focusedRowKey = key } }
    }

    private var lastLoggedShape: String? = null

    private companion object {
        /** How long the skeleton is allowed to stand before the screen says what it knows. */
        const val FIRST_PAINT_DEADLINE_MILLIS = 2_500L

        /** Spec §9: the box has one radio and a small heap. Four sockets is the ceiling. */
        const val MAX_PARALLEL_FETCHES = 4
    }

    /**
     * The Retry button on the error state, spec §3.7.
     *
     * Every planned row goes back to loading and the first-paint deadline is re-armed, so the
     * screen answers with the skeleton it showed on a cold start. Emptying the row list instead
     * dropped the screen straight to "Add a catalog add-on", which is the wrong sentence for a
     * retry that has not answered yet.
     */
    fun retry() {
        scope.launch(Dispatchers.Default) {
            settled = false
            publish { catalogRows = catalogRows.map { it.copy(items = emptyList(), loading = true, removed = false) } }
            armSettleDeadline()
            runCatching { client.snapshots.refresh(force = true) }
            val services = client.services.value ?: return@launch
            inFlight.clear()
            loadCatalogs(services.registry.catalogs())
        }
    }
}
