package com.fourseveneightnine.tv.client.data.library

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Library state, and nothing else.
 *
 * Rule from the rebuild plan (§5.3): no stream URL, no magnet and no token ever reaches this
 * database. Catalog rows stay in the signed snapshots under [com.fourseveneightnine.tv.client.data.catalog].
 */

/** Where a row came from. Phone rows are a mirror of somebody else's list and are replaced wholesale. */
public enum class RowOrigin(public val wire: String) {
    LOCAL("local"),
    PHONE("phone"),
    ;

    public companion object {
        public fun fromWire(value: String?): RowOrigin =
            entries.firstOrNull { it.wire == value } ?: LOCAL
    }
}

@Entity(tableName = "watch_progress")
public data class WatchProgressEntity(
    @PrimaryKey @ColumnInfo(name = "canonical_id") public val canonicalId: String,
    @ColumnInfo(name = "media_type") public val mediaType: String,
    @ColumnInfo(name = "season") public val season: Int? = null,
    @ColumnInfo(name = "episode") public val episode: Int? = null,
    @ColumnInfo(name = "position_ms") public val positionMs: Long,
    @ColumnInfo(name = "duration_ms") public val durationMs: Long,
    @ColumnInfo(name = "updated_at") public val updatedAt: Long,
    /** `local` or `phone`, matching [RowOrigin.wire]. */
    @ColumnInfo(name = "source") public val source: String,
)

@Entity(tableName = "recent")
public data class RecentEntity(
    @PrimaryKey @ColumnInfo(name = "canonical_id") public val canonicalId: String,
    @ColumnInfo(name = "title") public val title: String,
    @ColumnInfo(name = "poster_url") public val posterUrl: String? = null,
    @ColumnInfo(name = "backdrop_url") public val backdropUrl: String? = null,
    @ColumnInfo(name = "media_type") public val mediaType: String,
    @ColumnInfo(name = "season") public val season: Int? = null,
    @ColumnInfo(name = "episode") public val episode: Int? = null,
    @ColumnInfo(name = "last_played_at") public val lastPlayedAt: Long,
    /** `local` or `phone`, matching [RowOrigin.wire]. */
    @ColumnInfo(name = "origin") public val origin: String,
)

@Entity(
    tableName = "collection",
    indices = [Index(value = ["source_ref"])],
)
public data class CollectionEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") public val id: Long = 0L,
    @ColumnInfo(name = "name") public val name: String,
    @ColumnInfo(name = "accent") public val accent: String,
    /** [CollectionKind.wire]. */
    @ColumnInfo(name = "kind") public val kind: String,
    @ColumnInfo(name = "source_ref") public val sourceRef: String? = null,
    @ColumnInfo(name = "sort_index") public val sortIndex: Int,
    @ColumnInfo(name = "pinned_home") public val pinnedHome: Boolean = false,
    @ColumnInfo(name = "updated_at") public val updatedAt: Long,
)

@Entity(
    tableName = "collection_item",
    primaryKeys = ["collection_id", "canonical_id"],
    indices = [Index(value = ["canonical_id"])],
)
public data class CollectionItemEntity(
    @ColumnInfo(name = "collection_id") public val collectionId: Long,
    @ColumnInfo(name = "canonical_id") public val canonicalId: String,
    @ColumnInfo(name = "media_type") public val mediaType: String,
    @ColumnInfo(name = "title") public val title: String,
    @ColumnInfo(name = "poster_url") public val posterUrl: String? = null,
    @ColumnInfo(name = "sort_index") public val sortIndex: Int,
    @ColumnInfo(name = "added_at") public val addedAt: Long,
)

@Entity(tableName = "addon_health")
public data class AddonHealthEntity(
    @PrimaryKey @ColumnInfo(name = "manifest_url") public val manifestUrl: String,
    @ColumnInfo(name = "last_ok_at") public val lastOkAt: Long? = null,
    @ColumnInfo(name = "fail_count") public val failCount: Int = 0,
    @ColumnInfo(name = "last_error") public val lastError: String? = null,
)

@Entity(tableName = "job")
public data class JobEntity(
    @PrimaryKey @ColumnInfo(name = "name") public val name: String,
    /** [JobState.wire]. */
    @ColumnInfo(name = "state") public val state: String,
    @ColumnInfo(name = "started_at") public val startedAt: Long? = null,
    @ColumnInfo(name = "finished_at") public val finishedAt: Long? = null,
    @ColumnInfo(name = "message") public val message: String? = null,
)
