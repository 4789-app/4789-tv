package com.fourseveneightnine.tv.client.data.library

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import java.util.UUID

/**
 * Library state only: progress, recents, collections, add-on health and job rows.
 *
 * Catalog rows deliberately live outside this database, in signed snapshots on disk
 * (`docs/INSTANT_CATALOG_ARCHITECTURE.md`). The thresholds that would justify indexing
 * 1,300 catalog rows in SQLite have not been reached.
 */
@Database(
    entities = [
        WatchProgressEntity::class,
        RecentEntity::class,
        CollectionEntity::class,
        CollectionItemEntity::class,
        AddonHealthEntity::class,
        JobEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
public abstract class LibraryDatabase : RoomDatabase() {
    public abstract fun watchProgressDao(): WatchProgressDao
    public abstract fun recentDao(): RecentDao
    public abstract fun collectionDao(): CollectionDao
    public abstract fun addonHealthDao(): AddonHealthDao
    public abstract fun jobDao(): JobDao

    public companion object {
        public const val NAME: String = "library.db"
        private const val PROFILE_NAME_PREFIX: String = "library-profile-"

        /** The one on-disk database. Callers keep a single instance for the process. */
        public fun create(context: Context): LibraryDatabase =
            create(context, NAME)

        /** Opens one validated library file. A missing migration fails instead of deleting data. */
        public fun create(context: Context, name: String): LibraryDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                LibraryDatabase::class.java,
                requireValidName(name),
            ).build()

        /** The isolated database name for a non-owner household profile. */
        public fun profileName(profileId: UUID): String = "$PROFILE_NAME_PREFIX$profileId.db"

        /** Rejects arbitrary paths and non-canonical UUID spellings before Room sees a filename. */
        public fun requireValidName(name: String): String {
            if (name == NAME) return name
            require(name.startsWith(PROFILE_NAME_PREFIX) && name.endsWith(".db")) {
                "invalid_library_database_name"
            }
            val rawId = name.removePrefix(PROFILE_NAME_PREFIX).removeSuffix(".db")
            val parsed = runCatching { UUID.fromString(rawId) }.getOrNull()
            require(parsed != null && parsed.toString() == rawId.lowercase()) {
                "invalid_library_profile_id"
            }
            return name
        }

        /** For tests. Dies with the process. */
        public fun createInMemory(context: Context): LibraryDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, LibraryDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
