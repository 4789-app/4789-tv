package com.fourseveneightnine.tv.client.data.library

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LibraryDatabaseNameTest {
    @Test
    fun acceptsOnlyLegacyOrCanonicalProfileDatabaseNames() {
        val id = UUID.fromString("12345678-1234-4234-8234-123456789abc")
        val profileName = "library-profile-$id.db"

        assertEquals(LibraryDatabase.NAME, LibraryDatabase.requireValidName(LibraryDatabase.NAME))
        assertEquals(profileName, LibraryDatabase.profileName(id))
        assertEquals(profileName, LibraryDatabase.requireValidName(profileName))
        assertThrows(IllegalArgumentException::class.java) {
            LibraryDatabase.requireValidName("library-profile-../../library.db")
        }
        assertThrows(IllegalArgumentException::class.java) {
            LibraryDatabase.requireValidName("library-profile-1-1-1-1-1.db")
        }
    }
}
