package com.fourseveneightnine.tv.client.ui.screens.collections

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.fourseveneightnine.tv.client.data.library.CollectionDetail
import com.fourseveneightnine.tv.client.data.library.CollectionItem
import com.fourseveneightnine.tv.client.data.library.CollectionKind
import com.fourseveneightnine.tv.client.data.library.CollectionSummary
import com.fourseveneightnine.tv.client.data.library.SystemCollections
import com.fourseveneightnine.tv.client.data.catalog.Shelf
import com.fourseveneightnine.tv.client.data.catalog.ShelfKind
import com.fourseveneightnine.tv.client.ui.theme.TvColor

/**
 * Everything the Collections screens decide without drawing anything, spec §5 to §8.
 *
 * It lives apart from the composables for one reason: a folder order, a move-mode swap, a sort
 * choice and a checklist toggle are all rules, and a rule that can only be checked by pressing a
 * button on a television is a rule nobody checks. `CollectionsModelTest` runs every one of them.
 */

// ------------------------------------------------------------------ folder grid

/** Which kind of folder a card stands for. System folders carry a glyph instead of slivers. */
internal enum class FolderRole { ContinueWatching, Watchlist, MyCloud, User }

/** One card on the Collections grid, spec §5.3. */
@Immutable
internal data class FolderCardModel(
    val id: Long,
    val name: String,
    val count: Int,
    val accentHex: String,
    val kind: CollectionKind,
    val role: FolderRole,
    val pinned: Boolean,
    val posters: List<String>,
) {
    /**
     * Parsed once, at construction, not on every read. It used to be a `get()`, which ran
     * `removePrefix`, `toLongOrNull(16)` and a `Color` allocation inside composition at three call
     * sites. It is declared in the body rather than the constructor so it stays out of `equals`.
     */
    val accent: Color = accentColor(accentHex)

    /** System folders are neither moved nor deleted, and their name is fixed (spec §5.8.8). */
    val isSystem: Boolean get() = kind == CollectionKind.SYSTEM

    /** "24 titles", "8 files", or "Empty" when the folder holds nothing (spec §5.7). */
    val countLabel: String
        get() = when {
            count == 0 -> "Empty"
            role == FolderRole.MyCloud -> "$count ${plural(count, "file")}"
            else -> "$count ${plural(count, "title")}"
        }
}

internal object FolderGrid {
    /** Four columns at 220, 628, 1036 and 1444 (spec §5.2). */
    const val COLUMNS: Int = 4

    /**
     * The grid's order: system folders, then yours by sort index, then "New collection".
     *
     * [LibraryRepository.collections] already lifts the system folders to the front, so this only
     * decides which system folders are drawn at all. My Cloud is removed, not disabled, when no
     * debrid key is present (spec §5.8.4).
     */
    fun cards(summaries: List<CollectionSummary>, hasDebridKey: Boolean): List<FolderCardModel> =
        summaries.map(::card).filter { it.role != FolderRole.MyCloud || hasDebridKey }

    fun card(summary: CollectionSummary): FolderCardModel = FolderCardModel(
        id = summary.id,
        name = summary.name,
        count = summary.count,
        accentHex = summary.accent,
        kind = summary.kind,
        role = roleOf(summary.name, summary.kind),
        pinned = summary.pinnedHome,
        posters = summary.previewPosters,
    )

    /**
     * A system folder is known by its name, because [CollectionSummary] carries no source ref and
     * a system folder cannot be renamed.
     */
    fun roleOf(name: String, kind: CollectionKind): FolderRole = when {
        kind != CollectionKind.SYSTEM -> FolderRole.User
        name == SystemCollections.MY_CLOUD_NAME -> FolderRole.MyCloud
        name == SystemCollections.WATCHLIST_NAME -> FolderRole.Watchlist
        else -> FolderRole.ContinueWatching
    }

    /** A sliver is 96 px wide and the next one starts 40 px along, so they overlap (spec §5.3). */
    const val SLIVER_WIDTH: Int = 96
    const val SLIVER_PITCH: Int = 40
    const val SLIVER_INSET: Int = 24
    const val SLIVERS: Int = 3

    /** Where sliver [index] starts inside the card. Laid out back to front, so later ones cover. */
    fun sliverOffset(index: Int): Int = index * SLIVER_PITCH

    /**
     * How wide three overlapping slivers are, inset included.
     *
     * Spaced 40 px *apart* they measured 392 px inside a 380 px card and the last one was sheared
     * off by the clip. Spaced 40 px *along* they measure 200.
     */
    fun sliverBlockWidth(count: Int): Int =
        if (count <= 0) 0 else SLIVER_INSET + sliverOffset(count - 1) + SLIVER_WIDTH

    /** The count line under the screen title. */
    fun countLine(cards: List<FolderCardModel>): String =
        "${cards.size} ${plural(cards.size, "collection")}"

    /** True when nothing but empty system folders exists, which is the empty state (spec §5.6). */
    fun isEmpty(cards: List<FolderCardModel>): Boolean =
        cards.none { it.role == FolderRole.User } && cards.all { it.count == 0 }
}

// ------------------------------------------------------------------ synced lists

/**
 * The phone and catalog server may contribute hundreds of shelves. Collections is the complete
 * directory for those shelves, so this policy deliberately filters only rows that have nothing
 * to show. Home applies its own small curation cap; this screen must never repeat that cap.
 */
internal object SyncedLists {
    /** Shelf names remain useful even when a bounded phone transfer carries zero preview items. */
    fun visible(shelves: List<Shelf>): List<Shelf> = shelves

    fun countLabel(shelf: Shelf): String =
        "${shelf.items.size} ${plural(shelf.items.size, "title")}"

    fun directoryLabel(localCount: Int, syncedCount: Int): String =
        "$localCount ${plural(localCount, "collection")} · $syncedCount synced ${plural(syncedCount, "list")}"

    fun sourceLabel(shelf: Shelf): String = when (shelf.kind) {
        ShelfKind.TAMILMV_POPULAR, ShelfKind.TAMILMV_RECENT -> "Tamil MV"
        ShelfKind.LETTERBOXD -> "Letterboxd"
        ShelfKind.LETTERBOXD_FRIENDS -> "Friends"
        ShelfKind.TMDB_MOVIES, ShelfKind.TMDB_SERIES -> "TMDB"
    }
}

// ------------------------------------------------------------------ move mode

/** The four arrows, as move mode reads them. */
internal enum class MoveDirection { Left, Right, Up, Down }

internal object MoveMode {
    /**
     * How far a folder or a title travels for one arrow press. LEFT and RIGHT move one place;
     * UP and DOWN move a whole row, which is the grid's column count.
     */
    fun delta(direction: MoveDirection, columns: Int): Int = when (direction) {
        MoveDirection.Left -> -1
        MoveDirection.Right -> 1
        MoveDirection.Up -> -columns
        MoveDirection.Down -> columns
    }

    /**
     * The order after one swap, or the same list when the move would fall off either end. This
     * mirrors `LibraryRepository.moveCollection`, so a test can check the two agree.
     */
    fun <T> swapped(items: List<T>, index: Int, delta: Int): List<T> {
        if (delta == 0 || index !in items.indices) return items
        val target = index + delta
        if (target !in items.indices) return items
        val moved = items.toMutableList()
        moved.add(target, moved.removeAt(index))
        return moved
    }

    /** BACK restores the original order by walking the net travel back (spec §6.5). */
    fun undoDelta(appliedDeltas: List<Int>): Int = -appliedDeltas.sum()
}

// ------------------------------------------------------------------ collection screen

/** The Sort panel's three rows, spec §6.2. */
internal enum class SortMode(val label: String) {
    Added("Added"),
    Title("Title"),
    Year("Year"),
}

/**
 * Where a collection's sort choice is kept, spec §6.7.3.
 *
 * Per collection, not per screen: a list you always read by year stays that way. The value lives in
 * `AppGraph.presentationPreferences` beside the other TV-local choices.
 */
internal object CollectionSort {
    fun key(collectionId: Long): String = "collection_sort_$collectionId"

    /** An unknown or absent value reads as the default rather than throwing. */
    fun read(stored: String?): SortMode =
        SortMode.entries.firstOrNull { it.name == stored } ?: SortMode.Added

    fun write(mode: SortMode): String = mode.name
}

/** One cell in a collection's poster grid. */
@Immutable
internal data class CollectionCell(
    val canonicalId: String,
    val mediaType: String,
    val title: String,
    val posterUrl: String?,
    val year: Int?,
)

internal object CollectionGrid {
    /** Six columns at 220, 476, 732, 988, 1244 and 1500 (spec §6.2). */
    const val COLUMNS: Int = 6

    const val CARD_WIDTH: Int = 236
    const val COLUMN_GAP: Int = 20

    /**
     * The band is pinned to this, not to the screen, so the columns land on the spec's x values.
     *
     * A `fillMaxSize` band is 1604 px wide, which divides into 250.67 px slots and walks each
     * poster up to 27 px off its column. Six cards plus five gaps is 1516.
     */
    const val BAND_WIDTH: Int = COLUMNS * CARD_WIDTH + (COLUMNS - 1) * COLUMN_GAP

    fun cells(items: List<CollectionItem>, sort: SortMode): List<CollectionCell> =
        sorted(items, sort).map { item ->
            CollectionCell(
                canonicalId = item.canonicalId,
                mediaType = item.mediaType,
                title = titleWithoutYear(item.title),
                posterUrl = item.posterUrl,
                year = yearIn(item.title),
            )
        }

    /**
     * Added order is the stored order, which move mode writes. Title is A to Z, case ignored.
     *
     * Year reads the year out of the stored title, because the library row does not carry one — a
     * title with no year keeps its added order at the end rather than pretending to be from 1970.
     */
    fun sorted(items: List<CollectionItem>, sort: SortMode): List<CollectionItem> = when (sort) {
        SortMode.Added -> items.sortedBy(CollectionItem::sortIndex)
        SortMode.Title -> items.sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER) { titleWithoutYear(it.title) },
        )
        SortMode.Year -> items.sortedWith(
            compareBy<CollectionItem> { yearIn(it.title) == null }
                .thenByDescending { yearIn(it.title) ?: 0 }
                .thenBy(CollectionItem::sortIndex),
        )
    }

    /** "24 titles · updated 2h ago". The stale half is dropped when there is nothing to say. */
    fun metaLine(count: Int, staleLabel: String?): String {
        val titles = "$count ${plural(count, "title")}"
        return if (staleLabel.isNullOrBlank()) titles else "$titles · $staleLabel"
    }

    /** "From Letterboxd · saran". A manual folder has no source line (spec §6.6). */
    fun sourceLine(kind: CollectionKind, sourceRef: String?, sourceName: String?): String? {
        if (!kind.isSourced) return null
        val label = when (kind) {
            CollectionKind.CATALOG -> "add-on"
            CollectionKind.LETTERBOXD -> "Letterboxd"
            CollectionKind.MDBLIST -> "MDBList"
            else -> return null
        }
        val name = sourceName?.takeIf(String::isNotBlank) ?: sourceRef?.substringAfterLast('|')
        return if (name.isNullOrBlank()) "From $label" else "From $label · $name"
    }
}

// ------------------------------------------------------------------ add to collection

/** One row of the Add-to-collection checklist, spec §8.2. */
@Immutable
internal data class ChecklistRow(
    val id: Long,
    val name: String,
    val accentHex: String,
    val count: Int,
    val checked: Boolean,
    /** A sourced folder takes no manual add. Its row is dimmed and OK does nothing (spec §8.6.4). */
    val sourced: Boolean,
) {
    /** Parsed once, for the same reason [FolderCardModel.accent] is. */
    val accent: Color = accentColor(accentHex)
    val countLabel: String get() = if (sourced) "sourced" else count.toString()
}

/** What one OK press on a checklist row must write. */
internal enum class ChecklistAction { Add, Remove, Ignored }

internal object Checklist {
    /** Only your own folders take a manual add; the system ones are not offered (spec §8.2). */
    fun rows(summaries: List<CollectionSummary>, containing: Set<Long>): List<ChecklistRow> =
        summaries
            .filter { it.kind != CollectionKind.SYSTEM }
            .map { summary ->
                ChecklistRow(
                    id = summary.id,
                    name = summary.name,
                    accentHex = summary.accent,
                    count = summary.count,
                    checked = summary.id in containing,
                    sourced = summary.kind.isSourced,
                )
            }

    fun action(row: ChecklistRow): ChecklistAction = when {
        row.sourced -> ChecklistAction.Ignored
        row.checked -> ChecklistAction.Remove
        else -> ChecklistAction.Add
    }

    /** Initial focus: the first unchecked row, else the first row (spec §8.3). */
    fun initialIndex(rows: List<ChecklistRow>): Int =
        rows.indexOfFirst { !it.checked && !it.sourced }.takeIf { it >= 0 } ?: 0
}

// ------------------------------------------------------------------ sourced refresh

internal object SourcedRefresh {
    /**
     * What a refresh may write into a folder.
     *
     * Null means "write nothing": a manual folder holds what the viewer put in it, and a system
     * folder is built from elsewhere. Only a sourced folder's items are replaced, so a refresh can
     * never reach across into a manual list.
     */
    fun itemsFor(detail: CollectionDetail, incoming: List<CollectionItem>): List<CollectionItem>? {
        if (!detail.kind.isSourced) return null
        return incoming.distinctBy(CollectionItem::canonicalId)
            .mapIndexed { index, item -> item.copy(sortIndex = index) }
    }

    /** True when the stored items already match the source, so the write can be skipped. */
    fun unchanged(existing: List<CollectionItem>, incoming: List<CollectionItem>): Boolean =
        existing.map(CollectionItem::canonicalId) == incoming.map(CollectionItem::canonicalId)
}

/** How a sourced folder names the list it follows. */
internal object SourceRef {
    /** An add-on catalog: `<manifest url>|<type>|<catalog id>`, the key `AddonCatalog.uid` makes. */
    fun catalog(manifestUrl: String, type: String, id: String): String = "$manifestUrl|$type|$id"

    data class CatalogRef(val manifestUrl: String, val type: String, val id: String)

    fun parseCatalog(ref: String?): CatalogRef? {
        val parts = ref?.split('|') ?: return null
        if (parts.size < 3) return null
        return CatalogRef(parts[0], parts[1], parts.drop(2).joinToString("|"))
    }
}

// ------------------------------------------------------------------ accents

internal object Accents {
    /** The eight swatches, spec §7.3. They appear on folder cards and nowhere else. */
    val PALETTE: List<Color> = TvColor.CollectionAccents
    val HEXES: List<String> = PALETTE.map(::hexOf)

    val DEFAULT: String get() = HEXES.first()

    /** Which swatch is the current one, or -1 when the stored accent is not in the palette. */
    fun indexOf(hex: String): Int = HEXES.indexOfFirst { it.equals(hex, ignoreCase = true) }
}

/** `#RRGGBB` to a colour. An unreadable value falls back to `textMuted`, never to a crash. */
internal fun accentColor(hex: String): Color {
    val cleaned = hex.removePrefix("#")
    if (cleaned.length != 6) return TvColor.TextMuted
    val value = cleaned.toLongOrNull(radix = 16) ?: return TvColor.TextMuted
    return Color(0xFF000000L or value)
}

internal fun hexOf(color: Color): String {
    val red = (color.red * 255f).toInt().coerceIn(0, 255)
    val green = (color.green * 255f).toInt().coerceIn(0, 255)
    val blue = (color.blue * 255f).toInt().coerceIn(0, 255)
    return "#%02X%02X%02X".format(red, green, blue)
}

// ------------------------------------------------------------------ small helpers

/** A trailing `(2024)` is a year the catalog wrote into the title; anything else is not. */
internal fun yearIn(title: String): Int? {
    val match = TRAILING_YEAR.find(title.trim()) ?: return null
    return match.groupValues[1].toIntOrNull()
}

internal fun titleWithoutYear(title: String): String =
    TRAILING_YEAR.replace(title.trim(), "").trim()

private val TRAILING_YEAR = Regex("""\s*\(((?:19|20)\d{2})\)$""")

internal fun plural(count: Int, noun: String): String = if (count == 1) noun else "${noun}s"

/** One line, never wrapped to two (spec §5.7). */
internal fun clip(text: String, characters: Int): String =
    if (text.length <= characters) text else text.take(characters - 1).trimEnd() + "…"
