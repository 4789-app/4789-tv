package com.fourseveneightnine.tv.settings

import com.fourseveneightnine.tv.catalog.TVTamilMVCatalogCache
import com.fourseveneightnine.tv.catalog.TVTamilMVCatalogItem
import com.fourseveneightnine.tv.catalog.TVTamilMVCatalogSnapshot
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TVSettingsPairingCoordinatorTest {
    private val rawSettings = """
        {"format":"4789-settings","version":1,"tmdbAPIKey":"never-echo-this","sources":[{"name":"A","url":"https://private.example"}]}
    """.trimIndent()

    @Test
    fun invitationStagesOnceApprovesAndDisconnectKeepsSettings() = runTest {
        var now = 1_000L
        val persistence = FakePersistence()
        val coordinator = TVSettingsPairingCoordinator("receiver-1", persistence) { now }

        coordinator.beginPairing("192.168.1.24")
        val invitation = coordinator.state.value as TVSettingsPairingState.Invitation
        val uri = URI(invitation.qrText)
        val secret = SettingsPairingCrypto.decodeBase64URL(uri.fragment)
        val body = SettingsPairingCrypto.encryptForTest(
            rawSettings.encodeToByteArray(),
            SettingsPairingCrypto.derivePairKey(secret, invitation.pairingID),
            "4789-settings-pair-v1|${invitation.pairingID}",
        )

        val staged = coordinator.stagePair(invitation.pairingID, body)
        assertEquals(202, staged.statusCode)
        assertFalse(staged.body.contains("never-echo-this"))
        assertFalse(staged.body.contains("private.example"))
        assertTrue(coordinator.state.value is TVSettingsPairingState.Staged)
        assertEquals(409, coordinator.stagePair(invitation.pairingID, body).statusCode)

        coordinator.approve(keepSync = true)
        assertNotNull(persistence.document?.syncKeyBase64)
        assertTrue(coordinator.state.value is TVSettingsPairingState.Saved)
        val status = coordinator.pairStatus(invitation.pairingID)
        assertEquals("approved", Json.parseToJsonElement(status.body).jsonObject["status"]?.jsonPrimitive?.content)

        coordinator.disconnectSync()
        assertNotNull(persistence.document?.rawJson)
        assertNull(persistence.document?.syncKeyBase64)
        assertFalse((coordinator.state.value as TVSettingsPairingState.Saved).syncEnabled)
    }

    @Test
    fun slowApprovalPublishesSavingAndRapidPressPersistsOnceOffCallerThread() = runTest {
        val persistence = BlockingPersistence()
        val coordinator = TVSettingsPairingCoordinator("receiver-1", persistence) { 1_000L }
        val invitation = stagePairing(coordinator)
        val callerThread = Thread.currentThread().name

        val firstApproval = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.approve(keepSync = true)
        }
        assertTrue(persistence.saveEntered.await(5, TimeUnit.SECONDS))
        assertTrue(coordinator.state.value is TVSettingsPairingState.Saving)

        val repeatedApproval = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.approve(keepSync = true)
        }
        firstApproval.cancel()
        persistence.releaseSave.countDown()
        firstApproval.join()
        repeatedApproval.join()

        assertEquals(1, persistence.saveCalls.get())
        assertNotEquals(callerThread, persistence.saveThreadName)
        assertTrue(coordinator.state.value is TVSettingsPairingState.Saved)
        assertEquals(
            "approved",
            Json.parseToJsonElement(coordinator.pairStatus(invitation.pairingID).body)
                .jsonObject["status"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun failedAtomicSaveEndsBothTVAndPhoneFlows() = runTest {
        val coordinator = TVSettingsPairingCoordinator("receiver-1", FailingPersistence()) { 1_000L }
        val invitation = stagePairing(coordinator)

        coordinator.approve(keepSync = true)

        assertTrue(coordinator.state.value is TVSettingsPairingState.Error)
        assertEquals(
            "rejected",
            Json.parseToJsonElement(coordinator.pairStatus(invitation.pairingID).body)
                .jsonObject["status"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun expiryRejectsLateTransferAndWipesInvitation() = runTest {
        var now = 5_000L
        val coordinator = TVSettingsPairingCoordinator("receiver-1", FakePersistence()) { now }
        coordinator.beginPairing("10.0.0.5")
        val invitation = coordinator.state.value as TVSettingsPairingState.Invitation
        now = invitation.expiresAtUptimeMillis

        coordinator.expireInvitation()

        assertTrue(coordinator.state.value is TVSettingsPairingState.Error)
        assertEquals(404, coordinator.pairStatus(invitation.pairingID).statusCode)
        assertEquals(404, coordinator.stagePair(invitation.pairingID, "{}").statusCode)
    }

    @Test
    fun closingAStagedReceiptReportsRejectionToThePhone() = runTest {
        val coordinator = TVSettingsPairingCoordinator("receiver-1", FakePersistence()) { 1_000L }
        coordinator.beginPairing("192.168.1.24")
        val invitation = coordinator.state.value as TVSettingsPairingState.Invitation
        val uri = URI(invitation.qrText)
        val secret = SettingsPairingCrypto.decodeBase64URL(uri.fragment)
        val body = SettingsPairingCrypto.encryptForTest(
            rawSettings.encodeToByteArray(),
            SettingsPairingCrypto.derivePairKey(secret, invitation.pairingID),
            "4789-settings-pair-v1|${invitation.pairingID}",
        )
        assertEquals(202, coordinator.stagePair(invitation.pairingID, body).statusCode)

        coordinator.close()

        val status = coordinator.pairStatus(invitation.pairingID)
        assertEquals(200, status.statusCode)
        assertEquals(
            "rejected",
            Json.parseToJsonElement(status.body).jsonObject["status"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun encryptedCatalogStagesInMemoryAndCommitsOnlyAfterTVApproval() = runTest {
        val cache = FakeCatalogCache()
        val coordinator = TVSettingsPairingCoordinator(
            receiverID = "receiver-1",
            store = FakePersistence(),
            catalogCache = cache,
            uptimeMillis = { 1_000L },
        )
        val invitation = stagePairing(coordinator)
        val secret = SettingsPairingCrypto.decodeBase64URL(URI(invitation.qrText).fragment)
        val snapshot = TVTamilMVCatalogSnapshot(
            generation = "iphone-1",
            generatedAtMillis = 1,
            cachedAtMillis = 2,
            popular = listOf(
                TVTamilMVCatalogItem(
                    id = "movie:1",
                    mediaType = "movie",
                    title = "One",
                    posterURL = "https://image.tmdb.org/one.jpg",
                ),
            ),
            recent = emptyList(),
        )
        val catalogBody = SettingsPairingCrypto.encryptForTest(
            Json.encodeToString(snapshot).encodeToByteArray(),
            SettingsPairingCrypto.deriveCatalogPairKey(secret, invitation.pairingID),
            "4789-catalog-pair-v1|${invitation.pairingID}",
        )

        val response = coordinator.stagePairCatalog(invitation.pairingID, catalogBody)
        assertEquals(202, response.statusCode)
        assertTrue(response.body.contains("catalog_staged"))
        assertNull(cache.value)

        coordinator.approve(keepSync = false)
        assertEquals(snapshot, cache.value)
    }

    @Test
    fun settingsEnvelopeCannotBeReplayedAsCatalogAndRejectionNeverWritesCatalog() = runTest {
        val cache = FakeCatalogCache()
        val coordinator = TVSettingsPairingCoordinator(
            receiverID = "receiver-1",
            store = FakePersistence(),
            catalogCache = cache,
            uptimeMillis = { 1_000L },
        )
        val invitation = stagePairing(coordinator)
        val secret = SettingsPairingCrypto.decodeBase64URL(URI(invitation.qrText).fragment)
        val replay = SettingsPairingCrypto.encryptForTest(
            rawSettings.encodeToByteArray(),
            SettingsPairingCrypto.derivePairKey(secret, invitation.pairingID),
            "4789-settings-pair-v1|${invitation.pairingID}",
        )

        assertEquals(400, coordinator.stagePairCatalog(invitation.pairingID, replay).statusCode)
        coordinator.reject()
        assertNull(cache.value)
    }

    @Test
    fun syncRejectsOutOfOrderRevisionAndCanRevokeItself() = runTest {
        val persistence = FakePersistence()
        val receiverID = "receiver-sync"
        val syncKey = ByteArray(32) { (it + 1).toByte() }
        val validated = TVSettingsBackupPolicy.validate(rawSettings.encodeToByteArray())
        persistence.save(validated, revision = 7, syncKey = syncKey)
        val coordinator = TVSettingsPairingCoordinator(receiverID, persistence) { 0L }

        fun envelope(plaintext: String, revision: Long) = SettingsPairingCrypto.encryptForTest(
            plaintext.encodeToByteArray(),
            syncKey,
            "4789-settings-sync-v1|$receiverID",
            revision,
        )

        assertEquals(409, coordinator.applySync(receiverID, envelope(rawSettings, 7)).statusCode)
        assertEquals(200, coordinator.applySync(receiverID, envelope(rawSettings, 8)).statusCode)
        assertEquals(8L, persistence.document?.revision)
        assertEquals(200, coordinator.applySync(receiverID, envelope("{\"action\":\"disconnect\"}", 9)).statusCode)
        assertNull(persistence.document?.syncKeyBase64)
        assertEquals(409, coordinator.applySync(receiverID, envelope(rawSettings, 10)).statusCode)
    }

    private suspend fun stagePairing(
        coordinator: TVSettingsPairingCoordinator,
    ): TVSettingsPairingState.Invitation {
        coordinator.beginPairing("192.168.1.24")
        val invitation = coordinator.state.value as TVSettingsPairingState.Invitation
        val secret = SettingsPairingCrypto.decodeBase64URL(URI(invitation.qrText).fragment)
        val body = SettingsPairingCrypto.encryptForTest(
            rawSettings.encodeToByteArray(),
            SettingsPairingCrypto.derivePairKey(secret, invitation.pairingID),
            "4789-settings-pair-v1|${invitation.pairingID}",
        )
        assertEquals(202, coordinator.stagePair(invitation.pairingID, body).statusCode)
        return invitation
    }

    private class BlockingPersistence : TVSettingsPersistence {
        val saveEntered = CountDownLatch(1)
        val releaseSave = CountDownLatch(1)
        val saveCalls = AtomicInteger()
        @Volatile var saveThreadName: String? = null
        private val delegate = FakePersistence()

        override fun load(): StoredTVSettingsState = delegate.load()

        override fun save(settings: ValidatedTVSettings, revision: Long, syncKey: ByteArray?): Boolean {
            saveCalls.incrementAndGet()
            saveThreadName = Thread.currentThread().name
            saveEntered.countDown()
            check(releaseSave.await(5, TimeUnit.SECONDS)) { "test did not release persistence" }
            return delegate.save(settings, revision, syncKey)
        }

        override fun clearSync(): Boolean = delegate.clearSync()
        override fun clearAll(): Boolean = delegate.clearAll()
    }

    private class FailingPersistence : TVSettingsPersistence {
        override fun load(): StoredTVSettingsState = StoredTVSettingsState.Missing
        override fun save(settings: ValidatedTVSettings, revision: Long, syncKey: ByteArray?) = false
        override fun clearSync() = false
        override fun clearAll() = false
    }

    private class FakePersistence : TVSettingsPersistence {
        var document: StoredTVSettings? = null

        override fun load(): StoredTVSettingsState = document
            ?.let(StoredTVSettingsState::Available)
            ?: StoredTVSettingsState.Missing

        override fun save(settings: ValidatedTVSettings, revision: Long, syncKey: ByteArray?): Boolean {
            document = StoredTVSettings(
                rawJson = settings.rawJson,
                revision = revision,
                savedAtMillis = 1L,
                syncKeyBase64 = syncKey?.let(SettingsPairingCrypto::encodeBase64URL),
            )
            return true
        }

        override fun clearSync(): Boolean {
            document = document?.copy(syncKeyBase64 = null)
            return true
        }

        override fun clearAll(): Boolean {
            document = null
            return true
        }
    }

    private class FakeCatalogCache : TVTamilMVCatalogCache {
        var value: TVTamilMVCatalogSnapshot? = null
        override fun load(): TVTamilMVCatalogSnapshot? = value
        override fun store(snapshot: TVTamilMVCatalogSnapshot) { value = snapshot }
        override fun clear() { value = null }
    }
}
