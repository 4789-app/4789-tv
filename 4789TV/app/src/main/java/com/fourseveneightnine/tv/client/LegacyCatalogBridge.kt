package com.fourseveneightnine.tv.client

import com.fourseveneightnine.tv.catalog.TVTamilMVCatalogCache
import com.fourseveneightnine.tv.catalog.TVTamilMVCatalogItem
import com.fourseveneightnine.tv.catalog.TVTamilMVCatalogSnapshot
import com.fourseveneightnine.tv.catalog.sourceKey
import com.fourseveneightnine.tv.client.data.catalog.CatalogItem
import com.fourseveneightnine.tv.client.data.catalog.Shelf
import com.fourseveneightnine.tv.client.data.catalog.ShelfKind
import com.fourseveneightnine.tv.client.data.catalog.SnapshotStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Migrates the catalog copied in the original QR protocol into the active client snapshot store. */
internal class LegacyCatalogBridge(
    private val cache: TVTamilMVCatalogCache,
    private val snapshots: SnapshotStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun importIfNewer(): Boolean {
        val legacy = withContext(ioDispatcher) { cache.load() } ?: return false
        return snapshots.importPrivateSnapshot(
            generation = legacy.generation,
            generatedAtMillis = legacy.generatedAtMillis,
            shelves = legacy.toShelves(),
        )
    }
}

/**
 * Converts the sanitized legacy transfer one-for-one; no network credentials or playable URLs
 * exist in either model. The flat Letterboxd aggregate is retained only for older phones that did
 * not send individual shelves, avoiding a duplicate catch-all row on current transfers.
 */
internal fun TVTamilMVCatalogSnapshot.toShelves(): List<Shelf> = buildList {
    fun addShelf(
        id: String,
        title: String,
        kind: ShelfKind,
        source: List<TVTamilMVCatalogItem>,
        preserveEmpty: Boolean = false,
    ) {
        if (source.isEmpty() && !preserveEmpty) return
        add(
            Shelf(
                id = id,
                title = title,
                kind = kind,
                items = source.map(TVTamilMVCatalogItem::toCatalogItem),
                generation = generation,
                generatedAtMillis = generatedAtMillis,
                complete = true,
            ),
        )
    }

    addShelf(TAMILMV_POPULAR_ID, "Tamil MV Popular", ShelfKind.TAMILMV_POPULAR, popular)
    addShelf(TAMILMV_RECENT_ID, "Tamil MV Recent", ShelfKind.TAMILMV_RECENT, recent)
    if (letterboxdShelves.isEmpty()) {
        addShelf(LETTERBOXD_LEGACY_ID, "Letterboxd", ShelfKind.LETTERBOXD, letterboxd)
    } else {
        letterboxdShelves.forEach { shelf ->
            addShelf(shelf.id, shelf.title, ShelfKind.LETTERBOXD, shelf.items, preserveEmpty = true)
        }
    }
    addShelf(FRIENDS_ID, "New From Friends", ShelfKind.LETTERBOXD_FRIENDS, friends)
}

private fun TVTamilMVCatalogItem.toCatalogItem(): CatalogItem = CatalogItem(
    canonicalId = sourceKey,
    mediaType = mediaType,
    title = title,
    year = year,
    posterUrl = posterURL,
    backdropUrl = backdropURL,
    imdbId = imdbID,
    tmdbId = tmdbID,
    overview = overview,
    genres = genres,
)

private const val TAMILMV_POPULAR_ID = "tamilmv:popular"
private const val TAMILMV_RECENT_ID = "tamilmv:recent"
private const val LETTERBOXD_LEGACY_ID = "letterboxd:legacy"
private const val FRIENDS_ID = "letterboxd:friends"
