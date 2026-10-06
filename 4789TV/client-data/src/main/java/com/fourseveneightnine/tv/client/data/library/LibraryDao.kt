package com.fourseveneightnine.tv.client.data.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
public interface WatchProgressDao {
    @Query("SELECT * FROM watch_progress")
    public fun observeAll(): Flow<List<WatchProgressEntity>>

    @Query("SELECT * FROM watch_progress WHERE canonical_id = :canonicalId")
    public suspend fun find(canonicalId: String): WatchProgressEntity?

    @Query("SELECT * FROM watch_progress WHERE canonical_id = :canonicalId")
    public fun observe(canonicalId: String): Flow<WatchProgressEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsert(row: WatchProgressEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsertAll(rows: List<WatchProgressEntity>)

    @Query("DELETE FROM watch_progress WHERE source = :source")
    public suspend fun deleteBySource(source: String)

    @Query("DELETE FROM watch_progress WHERE canonical_id = :canonicalId")
    public suspend fun delete(canonicalId: String)
}

@Dao
public interface RecentDao {
    @Query("SELECT * FROM recent ORDER BY last_played_at DESC")
    public fun observeAll(): Flow<List<RecentEntity>>

    @Query("SELECT * FROM recent WHERE canonical_id = :canonicalId")
    public suspend fun find(canonicalId: String): RecentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsert(row: RecentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsertAll(rows: List<RecentEntity>)

    @Query("DELETE FROM recent WHERE origin = :origin")
    public suspend fun deleteByOrigin(origin: String)

    @Query("DELETE FROM recent WHERE canonical_id = :canonicalId")
    public suspend fun delete(canonicalId: String)
}

@Dao
public interface CollectionDao {
    @Query("SELECT * FROM collection ORDER BY sort_index ASC, id ASC")
    public fun observeCollections(): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM collection_item ORDER BY collection_id ASC, sort_index ASC")
    public fun observeAllItems(): Flow<List<CollectionItemEntity>>

    @Query("SELECT * FROM collection WHERE id = :id")
    public fun observeCollection(id: Long): Flow<CollectionEntity?>

    @Query("SELECT * FROM collection_item WHERE collection_id = :id ORDER BY sort_index ASC")
    public fun observeItems(id: Long): Flow<List<CollectionItemEntity>>

    @Query("SELECT collection_id FROM collection_item WHERE canonical_id = :canonicalId")
    public fun observeCollectionIdsContaining(canonicalId: String): Flow<List<Long>>

    @Query("SELECT * FROM collection WHERE id = :id")
    public suspend fun find(id: Long): CollectionEntity?

    @Query("SELECT * FROM collection WHERE kind = 'system' AND source_ref = :sourceRef LIMIT 1")
    public suspend fun findSystem(sourceRef: String): CollectionEntity?

    @Query("SELECT * FROM collection ORDER BY sort_index ASC, id ASC")
    public suspend fun allCollections(): List<CollectionEntity>

    @Query("SELECT COALESCE(MAX(sort_index), -1) FROM collection")
    public suspend fun maximumCollectionSortIndex(): Int

    @Query("SELECT COALESCE(MAX(sort_index), -1) FROM collection_item WHERE collection_id = :id")
    public suspend fun maximumItemSortIndex(id: Long): Int

    @Query("SELECT * FROM collection_item WHERE collection_id = :id ORDER BY sort_index ASC")
    public suspend fun items(id: Long): List<CollectionItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun insertCollection(row: CollectionEntity): Long

    @Update
    public suspend fun updateCollection(row: CollectionEntity)

    @Update
    public suspend fun updateCollections(rows: List<CollectionEntity>)

    @Query("DELETE FROM collection WHERE id = :id")
    public suspend fun deleteCollection(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun insertItem(row: CollectionItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun insertItems(rows: List<CollectionItemEntity>)

    @Update
    public suspend fun updateItems(rows: List<CollectionItemEntity>)

    @Query("DELETE FROM collection_item WHERE collection_id = :id AND canonical_id = :canonicalId")
    public suspend fun deleteItem(id: Long, canonicalId: String)

    @Query("DELETE FROM collection_item WHERE collection_id = :id")
    public suspend fun deleteItems(id: Long)
}

@Dao
public interface AddonHealthDao {
    @Query("SELECT * FROM addon_health")
    public fun observeAll(): Flow<List<AddonHealthEntity>>

    @Query("SELECT * FROM addon_health WHERE manifest_url = :manifestUrl")
    public suspend fun find(manifestUrl: String): AddonHealthEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsert(row: AddonHealthEntity)
}

@Dao
public interface JobDao {
    @Query("SELECT * FROM job ORDER BY name ASC")
    public fun observeAll(): Flow<List<JobEntity>>

    @Query("SELECT * FROM job WHERE name = :name")
    public suspend fun find(name: String): JobEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsert(row: JobEntity)
}
