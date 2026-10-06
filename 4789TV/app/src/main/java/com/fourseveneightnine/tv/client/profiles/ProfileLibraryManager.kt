package com.fourseveneightnine.tv.client.profiles

import android.content.Context
import com.fourseveneightnine.tv.client.data.library.LibraryDatabase
import java.util.UUID

/** Opens and deletes only the Room database belonging to one household profile. */
internal class ProfileLibraryManager internal constructor(
    private val store: ProfileStore,
    private val openDatabase: (String) -> LibraryDatabase,
    private val databaseExists: (String) -> Boolean,
    private val deleteDatabase: (String) -> Boolean,
) {
    constructor(context: Context, store: ProfileStore) : this(
        store = store,
        openDatabase = { name -> LibraryDatabase.create(context, name) },
        databaseExists = { name -> context.getDatabasePath(name).exists() },
        deleteDatabase = context::deleteDatabase,
    )

    fun open(profileId: UUID): LibraryDatabase {
        require(store.load().profiles.any { it.id == profileId }) { "profile_not_found" }
        return openDatabase(databaseName(profileId))
    }

    fun databaseName(profileId: UUID): String = if (profileId == OwnerProfile.id) {
        LibraryDatabase.NAME
    } else {
        LibraryDatabase.profileName(profileId)
    }

    /** A persisted tombstone makes a failed database deletion safe to retry. */
    fun deleteProfile(profileId: UUID): Boolean {
        if (!store.beginDelete(profileId)) return false
        val name = databaseName(profileId)
        if (databaseExists(name) && !deleteDatabase(name)) return false
        return store.finishDelete(profileId)
    }
}
