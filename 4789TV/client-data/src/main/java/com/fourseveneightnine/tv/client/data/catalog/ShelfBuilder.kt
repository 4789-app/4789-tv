package com.fourseveneightnine.tv.client.data.catalog

import com.fourseveneightnine.contract.CatalogArtifact
import com.fourseveneightnine.contract.CatalogFacetIndex
import com.fourseveneightnine.contract.CatalogManifestPayload

/**
 * Turns verified artifacts into Home shelves.
 *
 * The merge is additive and each artifact is merged exactly once, so a partial batch can be built
 * and painted, and the remainder appended to the same accumulators afterwards. Merging early costs
 * nothing and cannot double-count.
 */
internal class ShelfBuilder(
    private val source: SnapshotSource,
    private val manifest: CatalogManifestPayload,
    private val letterboxdUsernames: List<String>,
    private val now: () -> Long,
) {
    private class Bucket(val id: String, var title: String, val kind: ShelfKind, val limit: Int) {
        val items = mutableListOf<CatalogItem>()
        val seen = mutableSetOf<String>()

        fun append(index: CatalogFacetIndex) {
            if (items.size >= limit) return
            for (row in index.rows) {
                val item = SnapshotStore.displayItem(row) ?: continue
                if (seen.add(item.canonicalId)) items += item
                if (items.size >= limit) return
            }
        }
    }

    private val buckets = linkedMapOf<String, Bucket>()
    private var generatedAtMillis: Long =
        SnapshotStore.epochMillisOrNull(manifest.createdAt) ?: now()

    val itemCount: Int get() = buckets.values.sumOf { it.items.size }

    fun merge(batch: List<Pair<CatalogArtifact, CatalogFacetIndex>>) {
        for ((artifact, index) in batch) {
            SnapshotStore.epochMillisOrNull(index.generatedAt)?.let {
                generatedAtMillis = maxOf(generatedAtMillis, it)
            }
            val bucket = bucketFor(artifact, index) ?: continue
            bucket.append(index)
        }
    }

    fun build(complete: Boolean): StoredSnapshot = StoredSnapshot(
        source = source.wire,
        generation = manifest.generation,
        generatedAtMillis = generatedAtMillis,
        cachedAtMillis = now(),
        shelves = buckets.values
            .filter { it.items.isNotEmpty() }
            .map { bucket ->
                Shelf(
                    id = bucket.id,
                    title = bucket.title,
                    kind = bucket.kind,
                    items = bucket.items.toList(),
                    generation = manifest.generation,
                    generatedAtMillis = generatedAtMillis,
                    complete = complete,
                )
            }
            .sortedBy { it.kind.ordinal },
    )

    private fun bucketFor(artifact: CatalogArtifact, index: CatalogFacetIndex): Bucket? =
        when (source) {
            SnapshotSource.PUBLIC_TMDB -> publicBucket(artifact)
            SnapshotSource.PRIVATE_CATALOG -> privateBucket(artifact, index)
        }

    private fun publicBucket(artifact: CatalogArtifact): Bucket? = when (artifact.role) {
        "popular-movies-popular", "popular-movies-recent" -> buckets.getOrPut(TMDB_MOVIES_ID) {
            Bucket(TMDB_MOVIES_ID, "TMDB Popular Movies", ShelfKind.TMDB_MOVIES, SnapshotStore.MAX_OTHER_ITEMS)
        }
        "popular-tv-popular", "popular-tv-recent" -> buckets.getOrPut(TMDB_SERIES_ID) {
            Bucket(TMDB_SERIES_ID, "TMDB Popular Series", ShelfKind.TMDB_SERIES, SnapshotStore.MAX_OTHER_ITEMS)
        }
        else -> null
    }

    private fun privateBucket(artifact: CatalogArtifact, index: CatalogFacetIndex): Bucket? {
        val catalogId = index.catalogID
        return when {
            catalogId.startsWith(SnapshotStore.TAMILMV_PREFIX) && catalogId.endsWith(":popular") ->
                buckets.getOrPut(TAMILMV_POPULAR_ID) {
                    Bucket(
                        TAMILMV_POPULAR_ID,
                        "Tamil MV Popular",
                        ShelfKind.TAMILMV_POPULAR,
                        SnapshotStore.MAX_TAMIL_ITEMS,
                    )
                }
            catalogId.startsWith(SnapshotStore.TAMILMV_PREFIX) && catalogId.endsWith(":recent") ->
                buckets.getOrPut(TAMILMV_RECENT_ID) {
                    Bucket(
                        TAMILMV_RECENT_ID,
                        "Tamil MV Recent",
                        ShelfKind.TAMILMV_RECENT,
                        SnapshotStore.MAX_TAMIL_ITEMS,
                    )
                }
            catalogId.contains(SnapshotStore.FRIENDS_MARKER) -> buckets.getOrPut(FRIENDS_ID) {
                Bucket(FRIENDS_ID, "New From Friends", ShelfKind.LETTERBOXD_FRIENDS, SnapshotStore.MAX_OTHER_ITEMS)
            }
            catalogId.startsWith(SnapshotStore.LETTERBOXD_PREFIX) -> {
                val letterboxdCount = buckets.values.count { it.kind == ShelfKind.LETTERBOXD }
                if (catalogId !in buckets && letterboxdCount >= SnapshotStore.MAX_LETTERBOXD_SHELVES) {
                    null
                } else {
                    buckets.getOrPut(catalogId) {
                        Bucket(catalogId, shelfTitle(artifact, catalogId), ShelfKind.LETTERBOXD, SnapshotStore.MAX_OTHER_ITEMS)
                    }
                }
            }
            else -> null
        }
    }

    /**
     * The server's display name wins. Failing that the list's own id is made readable, and a
     * segment that matches one of the owner's Letterboxd usernames is labelled as that account's.
     */
    private fun shelfTitle(artifact: CatalogArtifact, catalogId: String): String {
        artifact.displayName?.trim()?.takeIf(String::isNotEmpty)?.let { return it }
        val tail = catalogId.substringAfterLast(':')
        letterboxdUsernames.firstOrNull { it.equals(tail, ignoreCase = true) }
            ?.let { return "Letterboxd · $it" }
        return tail
            .replace('-', ' ')
            .replace('_', ' ')
            .trim()
            .ifEmpty { "Letterboxd list" }
            .replaceFirstChar { it.titlecase() }
    }

    companion object {
        const val TAMILMV_POPULAR_ID = "tamilmv:popular"
        const val TAMILMV_RECENT_ID = "tamilmv:recent"
        const val FRIENDS_ID = "letterboxd:friends"
        const val TMDB_MOVIES_ID = "tmdb:movies"
        const val TMDB_SERIES_ID = "tmdb:series"
    }
}
