package com.fourseveneightnine.tv.client.profiles

import com.fourseveneightnine.tv.client.data.library.LibraryDatabase
import java.nio.file.Files
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileFoundationTest {
    @Test
    fun missingStoreSynthesizesPinlessOwnerWithoutTouchingLegacyLibrary() {
        val directory = Files.createTempDirectory("4789-profile-upgrade")
        val legacy = directory.resolve(LibraryDatabase.NAME)
        val original = ByteArray(257) { (it % 251).toByte() }
        Files.write(legacy, original)
        val persistence = MemoryPersistence()
        val store = store(persistence)

        val catalog = store.load()

        assertEquals(OwnerProfile.id, catalog.selectedProfileId)
        assertEquals(1, catalog.profiles.size)
        assertEquals(OwnerProfile.name, catalog.profiles.single().name)
        assertFalse(catalog.profiles.single().hasPin)
        val session = ProfileSession(store)
        session.load()
        assertEquals(ProfileSessionState.Unlocked(OwnerProfile.id), session.state.value)
        assertEquals(LibraryDatabase.NAME, manager(store).databaseName(OwnerProfile.id))
        assertArrayEquals(original, Files.readAllBytes(legacy))
    }

    @Test
    fun profileNamesAreUuidValidatedAndDeleteTouchesOnlyThatProfilesDatabase() {
        val firstId = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val secondId = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val ids = ArrayDeque(listOf(firstId, secondId))
        val store = store(MemoryPersistence(), uuidFactory = ids::removeFirst)
        val first = store.create("First", "blue")
        val second = store.create("Second", "green")
        val deleted = mutableListOf<String>()
        val manager = manager(store, deleted)

        assertEquals("library-profile-$firstId.db", manager.databaseName(first.id))
        assertEquals("library-profile-$secondId.db", manager.databaseName(second.id))
        assertThrows(IllegalArgumentException::class.java) {
            LibraryDatabase.requireValidName("library-profile-../../library.db")
        }

        assertTrue(manager.deleteProfile(first.id))
        assertEquals(listOf("library-profile-$firstId.db"), deleted)
        assertEquals(listOf(OwnerProfile.id, second.id), store.load().profiles.map(HouseholdProfile::id))
        assertFalse(manager.deleteProfile(OwnerProfile.id))
        assertEquals(listOf("library-profile-$firstId.db"), deleted)
    }

    @Test
    fun fifthPinFailurePersistsThirtySecondThrottleAndUnlockStateNeverPersists() {
        var now = 10_000L
        val profileId = UUID.fromString("33333333-3333-4333-8333-333333333333")
        val persistence = MemoryPersistence()
        val cipher = JvmEnvelopeCipher()
        val firstStore = store(persistence, cipher, { now }) { profileId }
        val profile = firstStore.create("Kids", "rocket", "2468".toCharArray())
        val firstSession = ProfileSession(firstStore)
        firstSession.load()
        assertEquals(PinVerification.Required, firstSession.select(profile.id))

        repeat(4) { attempt ->
            assertEquals(
                PinVerification.Rejected(4 - attempt),
                firstSession.unlock(profile.id, "0000".toCharArray()),
            )
        }
        assertEquals(
            PinVerification.Locked(now + 30_000L),
            firstSession.unlock(profile.id, "0000".toCharArray()),
        )
        assertFalse(requireNotNull(persistence.value).contains("2468"))

        val restoredStore = store(persistence, cipher, { now }) { error("unexpected UUID") }
        val restoredSession = ProfileSession(restoredStore)
        assertEquals(profile.id, restoredSession.load().selectedProfileId)
        assertEquals(ProfileSessionState.Selecting(profile.id), restoredSession.state.value)
        assertEquals(
            PinVerification.Locked(now + 30_000L),
            restoredSession.unlock(profile.id, "2468".toCharArray()),
        )

        now += 30_000L
        assertEquals(
            PinVerification.Verified,
            restoredSession.unlock(profile.id, "2468".toCharArray()),
        )
        assertEquals(ProfileSessionState.Unlocked(profile.id), restoredSession.state.value)

        val afterProcessRestart = ProfileSession(store(persistence, cipher, { now }) { error("unexpected UUID") })
        afterProcessRestart.load()
        assertEquals(ProfileSessionState.Selecting(profile.id), afterProcessRestart.state.value)
        assertEquals(
            PinVerification.Rejected(4),
            afterProcessRestart.unlock(profile.id, "0000".toCharArray()),
        )
    }

    @Test
    fun corruptPrimaryEnvelopeRecoversTheLatestVerifiedBackup() {
        val profileId = UUID.fromString("44444444-4444-4444-8444-444444444444")
        val persistence = MemoryPersistence()
        val cipher = JvmEnvelopeCipher()
        store(persistence, cipher, uuidFactory = { profileId }).create("Guest", "guest")
        persistence.value = "not-an-aes-gcm-envelope"
        val deleted = mutableListOf<String>()
        val recoveredStore = store(persistence, cipher, uuidFactory = { error("unexpected UUID") })
        val recoveredManager = manager(recoveredStore, deleted)

        val recovered = recoveredStore.load()

        assertEquals(listOf(OwnerProfile.id, profileId), recovered.profiles.map(HouseholdProfile::id))
        assertEquals(OwnerProfile.id, recovered.selectedProfileId)
        assertTrue(deleted.isEmpty())
        assertEquals(LibraryDatabase.NAME, recoveredManager.databaseName(OwnerProfile.id))
        assertEquals(
            listOf(OwnerProfile.id, profileId),
            store(persistence, cipher, uuidFactory = { error("unexpected UUID") })
                .load().profiles.map(HouseholdProfile::id),
        )
    }

    @Test
    fun failedDatabaseDeletionKeepsMetadataAndCanBeRetried() {
        val profileId = UUID.fromString("55555555-5555-4555-8555-555555555555")
        val store = store(MemoryPersistence(), uuidFactory = { profileId })
        store.create("Guest", "guest")
        var attempts = 0
        val manager = ProfileLibraryManager(
            store = store,
            openDatabase = { error("database open was not expected") },
            databaseExists = { true },
            deleteDatabase = { attempts++; attempts > 1 },
        )

        assertFalse(manager.deleteProfile(profileId))
        assertTrue(store.load().profiles.any { it.id == profileId })
        assertTrue(manager.deleteProfile(profileId))
        assertFalse(store.load().profiles.any { it.id == profileId })
    }

    @Test
    fun localPlaybackKeepsRepositoryOwnershipButPhonePlaybackDoesNotBlockProfileChoice() {
        val owner = OwnerProfile.id
        val guest = UUID.fromString("66666666-6666-4666-8666-666666666666")

        assertTrue(ProfileSwitchPolicy.allowed(owner, owner, localPlaybackActive = true))
        assertFalse(ProfileSwitchPolicy.allowed(owner, guest, localPlaybackActive = true))
        assertTrue(ProfileSwitchPolicy.allowed(owner, guest, localPlaybackActive = false))
    }

    private fun store(
        persistence: MemoryPersistence,
        cipher: ProfileEnvelopeCipher = JvmEnvelopeCipher(),
        clock: () -> Long = { 1_000L },
        uuidFactory: () -> UUID = { UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa") },
    ): ProfileStore = ProfileStore(
        persistence = persistence,
        cipher = cipher,
        clock = clock,
        uuidFactory = uuidFactory,
        randomBytes = { size -> ByteArray(size) { index -> (index + 1).toByte() } },
    )

    private fun manager(
        store: ProfileStore,
        deleted: MutableList<String> = mutableListOf(),
    ): ProfileLibraryManager = ProfileLibraryManager(
        store = store,
        openDatabase = { error("database open was not expected") },
        databaseExists = { true },
        deleteDatabase = { name -> deleted += name; true },
    )

    private class MemoryPersistence(var value: String? = null, var backup: String? = null) : ProfileEnvelopePersistence {
        override fun read(): String? = value
        override fun write(encoded: String?): Boolean {
            value = encoded
            return true
        }

        override fun readBackup(): String? = backup

        override fun writeBackup(encoded: String?): Boolean {
            backup = encoded
            return true
        }
    }

    private class JvmEnvelopeCipher : ProfileEnvelopeCipher {
        private val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
        private val random = SecureRandom()

        override fun encrypt(plaintext: ByteArray): ByteArray {
            val iv = ByteArray(12).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            return iv + cipher.doFinal(plaintext)
        }

        override fun decrypt(envelope: ByteArray): ByteArray {
            require(envelope.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, envelope.copyOfRange(0, 12)))
            return cipher.doFinal(envelope.copyOfRange(12, envelope.size))
        }
    }
}
