package com.fourseveneightnine.tv.client.data.catalog

import kotlinx.serialization.Serializable

/** Which signed snapshot a shelf came from. */
public enum class SnapshotSource(public val wire: String) {
    /** Public TMDB shelves. No token. */
    PUBLIC_TMDB("tmdb"),

    /** Tamil MV, Letterboxd lists and New From Friends. Needs the catalog server token. */
    PRIVATE_CATALOG("private"),
}

/** What a shelf is, so the UI can pick a card shape and a row order without parsing its id. */
public enum class ShelfKind {
    TAMILMV_POPULAR,
    TAMILMV_RECENT,
    LETTERBOXD,
    LETTERBOXD_FRIENDS,
    TMDB_MOVIES,
    TMDB_SERIES,
}

/**
 * One card's worth of catalog metadata.
 *
 * Display identity only. No stream URL, no magnet and no token is ever stored here
 * (`docs/INSTANT_CATALOG_ARCHITECTURE.md`, "Data and security boundaries").
 */
@Serializable
public data class CatalogItem(
    public val canonicalId: String,
    public val mediaType: String,
    public val title: String,
    public val year: Int? = null,
    public val posterUrl: String? = null,
    public val backdropUrl: String? = null,
    public val imdbId: String? = null,
    public val tmdbId: Int? = null,
    public val overview: String? = null,
    public val genres: List<String> = emptyList(),
)

/**
 * One Home row.
 *
 * TRAP: a shelf holds up to 2,000 items, so `Shelf` equality is a deep compare. Key any UI state
 * on [generation], never on the whole object. Keying the Tamil MV hero on the snapshot cost a
 * reset to card zero on every catalog write (`tv-dpad-focus-destroyed-by-loading-shelf`).
 */
@Serializable
public data class Shelf(
    public val id: String,
    public val title: String,
    public val kind: ShelfKind,
    public val items: List<CatalogItem>,
    /** The signed generation this row came from. */
    public val generation: String,
    public val generatedAtMillis: Long,
    /**
     * False only for a partial paint: some shards had not decoded when the 1.2 s deadline fired.
     * A partial shelf is never written to disk.
     */
    public val complete: Boolean = true,
)

/** Where one source's refresh stands. Drives Settings → Jobs. */
public enum class SnapshotState {
    /** Nothing loaded yet. */
    IDLE,

    /** A refresh is running. */
    LOADING,

    /** Shelves are on screen. They may still be from cache. */
    READY,

    /** The refresh failed. The last known-good generation is still being served. */
    FAILED,
}

/** One row of Settings → Jobs. */
public data class SourceStatus(
    public val source: SnapshotSource,
    public val state: SnapshotState,
    public val lastRunMillis: Long?,
    public val generation: String?,
    public val generatedAtMillis: Long?,
    public val itemCount: Int,
    public val complete: Boolean,
    public val message: String? = null,
)

/** Both sources at once. */
public data class SnapshotStatus(
    public val sources: List<SourceStatus>,
) {
    public fun of(source: SnapshotSource): SourceStatus? = sources.firstOrNull { it.source == source }

    public companion object {
        public fun idle(): SnapshotStatus = SnapshotStatus(
            SnapshotSource.entries.map {
                SourceStatus(
                    source = it,
                    state = SnapshotState.IDLE,
                    lastRunMillis = null,
                    generation = null,
                    generatedAtMillis = null,
                    itemCount = 0,
                    complete = false,
                )
            },
        )
    }
}

/** The document written to disk, one per generation per source. */
@Serializable
internal data class StoredSnapshot(
    val schemaVersion: Int = 1,
    val source: String,
    val generation: String,
    val generatedAtMillis: Long,
    val cachedAtMillis: Long,
    val shelves: List<Shelf>,
) {
    val itemCount: Int get() = shelves.sumOf { it.items.size }
}
