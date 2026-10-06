package com.fourseveneightnine.tv.client.ui.screens.collections

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fourseveneightnine.tv.client.ClientGraph
import com.fourseveneightnine.tv.client.data.catalog.Shelf
import com.fourseveneightnine.tv.client.data.catalog.ShelfKind
import com.fourseveneightnine.tv.client.data.library.CollectionDetail
import com.fourseveneightnine.tv.client.data.library.CollectionItem
import com.fourseveneightnine.tv.client.data.library.CollectionKind

/**
 * Where a sourced collection's titles come from, and how they get into the folder.
 *
 * Two sources exist on this box today. A Letterboxd list arrives inside the signed catalog
 * snapshot, so it is read from `snapshots.shelves()` and costs no network. An add-on catalog is
 * fetched when the collection is opened, because an add-on has no snapshot to read.
 *
 * MDBList is deliberately absent. Nothing in `client-data` fetches an MDBList list, so offering
 * the row would be a button that does nothing.
 */
internal object CollectionSources {

    /** What one refresh found, or null when this folder is not sourced or the source is unknown. */
    suspend fun fetch(graph: ClientGraph, detail: CollectionDetail): List<CollectionItem>? =
        when (detail.kind) {
            CollectionKind.LETTERBOXD -> letterboxdItems(graph, detail.sourceRef)
            CollectionKind.CATALOG -> catalogItems(graph, detail.sourceRef)
            else -> null
        }

    /**
     * Refreshes one folder. Manual and system folders are left exactly as they are: the write only
     * ever happens through [SourcedRefresh.itemsFor], which refuses anything that is not sourced.
     */
    suspend fun refresh(graph: ClientGraph, detail: CollectionDetail): Boolean {
        val found = fetch(graph, detail) ?: return false
        val items = SourcedRefresh.itemsFor(detail, found) ?: return false
        if (SourcedRefresh.unchanged(detail.items, items)) return true
        graph.activeLibrary.replaceSourcedItems(detail.id, items)
        return true
    }

    /** Every Letterboxd shelf the snapshot holds, for the editor's picker. */
    fun letterboxdShelves(graph: ClientGraph): List<Shelf> =
        graph.snapshots.shelves().value.filter {
            it.kind == ShelfKind.LETTERBOXD || it.kind == ShelfKind.LETTERBOXD_FRIENDS
        }

    /** The human name for a folder's source, for the header's source line. */
    fun sourceName(graph: ClientGraph, detail: CollectionDetail): String? = when (detail.kind) {
        CollectionKind.LETTERBOXD ->
            graph.snapshots.shelves().value.firstOrNull { it.id == detail.sourceRef }?.title
        CollectionKind.CATALOG -> {
            val ref = SourceRef.parseCatalog(detail.sourceRef) ?: return null
            graph.services.value?.registry?.catalogs()
                ?.firstOrNull { (addon, catalog) ->
                    addon.key == ref.manifestUrl && catalog.type == ref.type && catalog.id == ref.id
                }
                ?.let { (addon, catalog) -> "${addon.displayName} ${catalog.name}" }
        }
        else -> null
    }

    private fun letterboxdItems(graph: ClientGraph, ref: String?): List<CollectionItem>? {
        val shelf = graph.snapshots.shelves().value.firstOrNull { it.id == ref } ?: return null
        return shelf.items.mapIndexed { index, item ->
            CollectionItem(
                canonicalId = item.canonicalId,
                mediaType = item.mediaType,
                title = storedTitle(item.title, item.year),
                posterUrl = item.posterUrl,
                sortIndex = index,
                addedAtMillis = 0L,
            )
        }
    }

    private suspend fun catalogItems(graph: ClientGraph, ref: String?): List<CollectionItem>? {
        val parsed = SourceRef.parseCatalog(ref) ?: return null
        val services = graph.services.value ?: return null
        val addon = services.registry.addon(parsed.manifestUrl) ?: return null
        val page = runCatching { services.client.catalog(addon, parsed.type, parsed.id) }
            .getOrNull() ?: return null
        return page.items.mapIndexed { index, item ->
            CollectionItem(
                canonicalId = item.id,
                mediaType = item.type,
                title = storedTitle(item.title, item.year),
                posterUrl = item.posterURL,
                sortIndex = index,
                addedAtMillis = 0L,
            )
        }
    }
}

/**
 * The library row has no year column, so a year that is known is written into the stored title as
 * a trailing `(2024)`. The Collection screen reads it back for the Year sort and the cell line.
 */
internal fun storedTitle(title: String, year: Int?): String {
    val clean = titleWithoutYear(title)
    return if (year == null) clean else "$clean ($year)"
}

/**
 * The editor's "Reorder items" row leaves for the Collection screen in move mode (spec §7.4).
 *
 * The route carries no argument for it and `Routes.kt` belongs to the shell, so the request is
 * handed over here and consumed once by the screen that opens next.
 */
internal object PendingMoveMode {
    private var pending by mutableStateOf<Long?>(null)

    fun request(collectionId: Long) { pending = collectionId }

    /** True once, for the collection that asked. */
    fun consume(collectionId: Long): Boolean {
        if (pending != collectionId) return false
        pending = null
        return true
    }

    fun clear() { pending = null }
}
