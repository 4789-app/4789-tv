package com.fourseveneightnine.tv.settings

import com.fourseveneightnine.tv.catalog.TVLetterboxdShelf
import com.fourseveneightnine.tv.catalog.TVTamilMVCatalogCache
import com.fourseveneightnine.tv.catalog.TVTamilMVCatalogItem
import com.fourseveneightnine.tv.catalog.TVTamilMVCatalogSnapshot
import com.fourseveneightnine.tv.client.data.settings.AddonSource
import com.fourseveneightnine.tv.client.data.settings.SettingsDocument
import java.net.URI
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import com.fourseveneightnine.tv.client.data.addons.Addon
import com.fourseveneightnine.tv.client.data.addons.StremioClient
import com.fourseveneightnine.tv.transport.AddonLinkForm
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun disjointPhoneAliasBatchesExpireAtTheirOwnDeadlinesInEitherArrivalOrder() = runTest {
        val json = Json { encodeDefaults = true }
        val lifetime = 300_000L
        for (olderIsImdb in listOf(true, false)) {
            val persistence = FakePersistence()
            val key = ByteArray(32) { 42 }
            persistence.save(TVSettingsBackupPolicy.validate(rawSettings.encodeToByteArray()), 1L, key)
            val coordinator = TVSettingsPairingCoordinator("receiver", persistence,
                storageDispatcher = StandardTestDispatcher(testScheduler), uptimeMillis = { testScheduler.currentTime })
            fun batch(imdb: Boolean, name: String) = TVPhoneSources(type = "movie",
                imdbID = if (imdb) "tt43741215" else null, tmdbID = if (imdb) null else 607833,
                title = "Film", rows = listOf(TVPhoneSource(name, "Film.$name.mkv", url = "https://cdn.example/$name.mkv")))
            suspend fun send(value: TVPhoneSources, revision: Long) {
                val body = SettingsPairingCrypto.encryptForTest(json.encodeToString(value).encodeToByteArray(), key,
                    "4789-settings-sync-v2|receiver|$revision", revision, version = 2)
                assertEquals(200, coordinator.applySync("receiver", body).statusCode)
                runCurrent()
            }
            val emissions = mutableListOf<List<String>>()
            val collector = backgroundScope.launch {
                coordinator.sourceRows("movie", listOf("tt43741215", "tmdb:607833"))
                    .collect { rows -> emissions += rows.map { it.addonName } }
            }
            try {
                runCurrent()
                send(batch(olderIsImdb, "Older"), 2L)
                assertEquals(listOf("Older"), emissions.last())
                advanceTimeBy(60_000L)
                send(batch(!olderIsImdb, "Newer"), 3L)
                assertEquals(listOf("Older", "Newer"), emissions.last())
                advanceTimeBy(lifetime - 60_000L - 1L)
                runCurrent()
                assertEquals(listOf("Older", "Newer"), emissions.last())
                advanceTimeBy(1L)
                runCurrent()
                assertEquals(listOf("Newer"), emissions.last())
                advanceTimeBy(59_999L)
                runCurrent()
                assertEquals(listOf("Newer"), emissions.last())
                advanceTimeBy(1L)
                runCurrent()
                assertTrue(emissions.last().isEmpty())
            } finally {
                collector.cancelAndJoin()
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun phoneAliasReplacementRestartsOnlyThatAliasDeadlineAndCancelsTheOldExpiry() = runTest {
        val json = Json { encodeDefaults = true }
        val persistence = FakePersistence()
        val key = ByteArray(32) { 42 }
        persistence.save(TVSettingsBackupPolicy.validate(rawSettings.encodeToByteArray()), 1L, key)
        val coordinator = TVSettingsPairingCoordinator("receiver", persistence,
            storageDispatcher = StandardTestDispatcher(testScheduler), uptimeMillis = { testScheduler.currentTime })
        fun batch(imdb: Boolean, name: String) = TVPhoneSources(type = "movie",
            imdbID = if (imdb) "tt43741215" else null, tmdbID = if (imdb) null else 607833,
            title = "Film", rows = listOf(TVPhoneSource(name, "Film.$name.mkv", url = "https://cdn.example/$name.mkv")))
        suspend fun send(value: TVPhoneSources, revision: Long) {
            val body = SettingsPairingCrypto.encryptForTest(json.encodeToString(value).encodeToByteArray(), key,
                "4789-settings-sync-v2|receiver|$revision", revision, version = 2)
            assertEquals(200, coordinator.applySync("receiver", body).statusCode)
            runCurrent()
        }
        val emissions = mutableListOf<List<String>>()
        val collector = backgroundScope.launch {
            coordinator.sourceRows("movie", listOf("tt43741215", "tmdb:607833"))
                .collect { rows -> emissions += rows.map { it.addonName } }
        }
        try {
            runCurrent()
            send(batch(true, "Original"), 2L)
            advanceTimeBy(60_000L)
            send(batch(false, "OtherAlias"), 3L)
            advanceTimeBy(60_000L)
            send(batch(true, "Replacement"), 4L)
            assertEquals(listOf("OtherAlias", "Replacement"), emissions.last())
            advanceTimeBy(180_000L)
            runCurrent()
            assertEquals(listOf("OtherAlias", "Replacement"), emissions.last())
            advanceTimeBy(60_000L)
            runCurrent()
            assertEquals(listOf("Replacement"), emissions.last())
            advanceTimeBy(59_999L)
            runCurrent()
            assertEquals(listOf("Replacement"), emissions.last())
            advanceTimeBy(1L)
            runCurrent()
            assertTrue(emissions.last().isEmpty())
        } finally {
            collector.cancelAndJoin()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun phoneSourceExpiryKeepsTheEightBatchBoundRevocationDisconnectAndCollectorCancellation() = runTest {
        val json = Json { encodeDefaults = true }
        val lifetime = 300_000L
        val persistence = FakePersistence()
        val key = ByteArray(32) { 42 }
        persistence.save(TVSettingsBackupPolicy.validate(rawSettings.encodeToByteArray()), 1L, key)
        val coordinator = TVSettingsPairingCoordinator("receiver", persistence,
            storageDispatcher = StandardTestDispatcher(testScheduler), uptimeMillis = { testScheduler.currentTime })
        fun batch(index: Int, rows: Boolean = true) = TVPhoneSources(type = "movie", imdbID = "tt$index", title = "Film $index",
            rows = if (rows) listOf(TVPhoneSource("Batch$index", "Film.$index.mkv", url = "https://cdn.example/$index.mkv")) else emptyList())
        suspend fun send(text: String, revision: Long): Int = coordinator.applySync("receiver",
            SettingsPairingCrypto.encryptForTest(text.encodeToByteArray(), key,
                "4789-settings-sync-v2|receiver|$revision", revision, version = 2)).statusCode
        for (index in 1..9) assertEquals(200, send(json.encodeToString(batch(index)), index + 1L))
        val emissions = mutableListOf<List<String>>()
        val collector = backgroundScope.launch {
            coordinator.sourceRows("movie", (1..9).map { "tt$it" })
                .collect { values -> emissions += values.map { it.addonName } }
        }
        try {
            runCurrent()
            assertEquals((2..9).map { "Batch$it" }, emissions.last())
            assertEquals(200, send(json.encodeToString(batch(9, rows = false)), 11L))
            runCurrent()
            assertEquals((2..8).map { "Batch$it" }, emissions.last())
            assertTrue(coordinator.sourceRows("movie", listOf("tt9")).first().isEmpty())
            collector.cancelAndJoin()
            val countAtCancellation = emissions.size
            advanceTimeBy(lifetime + 1L)
            runCurrent()
            assertEquals(countAtCancellation, emissions.size)
            assertTrue(coordinator.sourceRows("movie", listOf("tt2")).first().isEmpty())
            assertEquals(200, send(json.encodeToString(batch(10)), 12L))
            val afterDisconnect = mutableListOf<List<String>>()
            val nextCollector = backgroundScope.launch {
                coordinator.sourceRows("movie", listOf("tt10"))
                    .collect { values -> afterDisconnect += values.map { it.addonName } }
            }
            try {
                runCurrent()
                assertEquals(listOf("Batch10"), afterDisconnect.last())
                assertEquals(200, send("""{"action":"disconnect"}""", 13L))
                runCurrent()
                assertTrue(afterDisconnect.last().isEmpty())
                advanceTimeBy(lifetime + 1L)
                runCurrent()
                assertTrue(afterDisconnect.last().isEmpty())
                assertEquals(409, send(json.encodeToString(batch(10)), 14L))
            } finally {
                nextCollector.cancelAndJoin()
            }
        } finally {
            collector.cancelAndJoin()
        }
    }
    private val rawSettings = """
        {"format":"4789-settings","version":1,"tmdbAPIKey":"never-echo-this","sources":[{"name":"A","url":"https://private.example"}]}
    """.trimIndent()

    @Test
    fun phoneSourcesUseTrustedPairingAndKeepSettingsAndTitleBoundaries() = runTest {
        val persistence = FakePersistence()
        val key = ByteArray(32) { 42 }
        val localSettings = rawSettings.dropLast(1) + """,
            "tvImportedSources":[{"name":"TV only","url":"https://tv-only.example/manifest.json","enabled":true}],
            "tvReplaceSources":true,"tvImportedSourcesEpoch":"existing-import"}
        """.trimIndent()
        persistence.save(TVSettingsBackupPolicy.validate(localSettings.encodeToByteArray()), 1, key)
        // An existing document may carry fields written by a newer compatible receiver.
        val retained = persistence.document?.rawJson.orEmpty().dropLast(1) + ",\"futureReceiverOption\":\"keep-exact\"}"
        persistence.document = persistence.document?.copy(rawJson = retained)
        var now = 1_000L
        val coordinator = TVSettingsPairingCoordinator("receiver", persistence, uptimeMillis = { now })
        val before = coordinator.state.value
        val batch = TVPhoneSources(type = "movie", imdbID = "tt43741215", tmdbID = 607833,
            title = "The Intern", year = 2026, rows = (1..34).map {
                TVPhoneSource("FileMood", "The.Intern.2026.1080p.$it.mkv", infoHash = "%040x".format(it),
                    cached = true, service = "TorBox")
            })
        val payload = Json { encodeDefaults = true }.encodeToString(batch).encodeToByteArray()
        val body = SettingsPairingCrypto.encryptForTest(payload, key,
            "4789-settings-sync-v2|receiver|2", 2, version = 2)
        assertEquals(200, coordinator.applySync("receiver", body).statusCode)
        assertEquals(retained, persistence.document?.rawJson)
        val kept = com.fourseveneightnine.tv.client.data.settings.SettingsDocument.parse(requireNotNull(persistence.document).rawJson)
        assertTrue(kept.tvReplaceSources)
        assertEquals("TV only", kept.tvImportedSources.single().name)
        assertEquals(2L, persistence.document?.revision)
        assertEquals(before, coordinator.state.value)
        assertEquals(34, coordinator.sourceRows("movie", listOf("tt43741215")).first().size)
        assertEquals(34, coordinator.sourceRows("movie", listOf("tmdb:607833")).first().size)
        val client = com.fourseveneightnine.tv.client.data.addons.StremioClient()
        val registry = com.fourseveneightnine.tv.client.data.addons.AddonRegistry(
            com.fourseveneightnine.tv.client.data.settings.SettingsDocument.parse("""{"sources":[]}"""), client)
        val shared = com.fourseveneightnine.tv.client.data.streams.StreamSearch(registry, client,
            phoneRows = coordinator::sourceRows).search("movie", listOf("tmdb:607833"))
        val state = shared.toList().last()
        assertTrue(state.done)
        assertEquals(34, state.rows.size)
        assertTrue(coordinator.sourceRows("movie", listOf("tt2361509", "tmdb:257211")).first().isEmpty())
        assertTrue(coordinator.sourceRows("series", listOf("tt43741215")).first().isEmpty())
        assertTrue(coordinator.sourceRows("movie", listOf("tt43741215")).first().none { it.cachedHint.verified })
        assertEquals(409, coordinator.applySync("receiver", body).statusCode)
        now += 300_000L
        assertTrue(coordinator.sourceRows("movie", listOf("tt43741215")).first().isEmpty())
    }

    @Test
    fun phoneSourcesRejectInvalidHeadersAndUnpairedRequestsAndClearOnRevocation() = runTest {
        val persistence = FakePersistence()
        val key = ByteArray(32) { 42 }
        val coordinator = TVSettingsPairingCoordinator("receiver", persistence, uptimeMillis = { 1L })
        fun body(batch: TVPhoneSources, revision: Long) = SettingsPairingCrypto.encryptForTest(
            Json { encodeDefaults = true }.encodeToString(batch).encodeToByteArray(), key,
            "4789-settings-sync-v2|receiver|$revision", revision, version = 2)
        val row = TVPhoneSource("Source", "Show.S02E03.mkv", url = "https://cdn.example/file.mkv",
            headers = mapOf("Referer" to "https://source.example"))
        val batch = TVPhoneSources(type = "series", imdbID = "tt42", title = "Show", season = 2,
            episode = 3, rows = listOf(row))
        assertEquals(409, coordinator.applySync("receiver", body(batch, 2)).statusCode)
        persistence.save(TVSettingsBackupPolicy.validate(rawSettings.encodeToByteArray()), 1, key)
        val bad = batch.copy(rows = listOf(row.copy(headers = mapOf("Referer" to "bad\r\nInjected: yes"))))
        assertEquals(400, coordinator.applySync("receiver", body(bad, 2)).statusCode)
        assertEquals(1L, persistence.document?.revision)
        assertEquals(200, coordinator.applySync("receiver", body(batch, 3)).statusCode)
        assertTrue(coordinator.sourceRows("series", listOf("tt42:2:4")).first().isEmpty())
        assertEquals("https://source.example", coordinator.sourceRows("series", listOf("tt42:2:3"))
            .first().single().headers["Referer"])
        coordinator.disconnectSync()
        assertTrue(coordinator.sourceRows("series", listOf("tt42:2:3")).first().isEmpty())
        assertEquals(409, coordinator.applySync("receiver", body(batch, 4)).statusCode)
    }

    @Test
    fun fullLetterboxdMirrorCommitsOnlyAfterFinalPartAndSurvivesPreviewAndDisconnect() = runTest {
        val settings = FakePersistence()
        val preview = FakeCatalogCache()
        val mirror = FakeCatalogCache()
        val key = ByteArray(32) { 42 }
        val receiver = "receiver-sync"
        settings.save(TVSettingsBackupPolicy.validate(rawSettings.encodeToByteArray()), 1, key)
        val coordinator = TVSettingsPairingCoordinator(
            receiver, settings, preview, letterboxdCache = mirror, uptimeMillis = { 1_000L },
        )
        val titles = (1..1_305).map { TVTamilMVCatalogItem("tmdb:$it", "movie", "Film $it") }
        val full = TVTamilMVCatalogSnapshot(
            generation = "iphone-letterboxd", generatedAtMillis = 10, cachedAtMillis = 10,
            popular = emptyList(), recent = emptyList(),
            letterboxdShelves = listOf(TVLetterboxdShelf("letterboxd:liked:other/list", "Full list", titles, listOf("viewer"))),
        )
        val body = Json.encodeToString(full).encodeToByteArray()
        val split = body.size / 2
        val transferID = "8CF00000-1000-4000-9000-000000000001"
        fun part(index: Int, bytes: ByteArray, id: String = transferID): String =
            """{"action":"letterboxd_part","transferID":"$id","index":$index,"count":2,"data":"${Base64.getEncoder().encodeToString(bytes)}"}"""
        suspend fun send(text: String, revision: Long): Int = coordinator.applySync(receiver,
            SettingsPairingCrypto.encryptForTest(text.encodeToByteArray(), key,
                "4789-settings-sync-v2|$receiver|$revision", revision, version = 2)).statusCode

        assertEquals(200, send(part(0, body.copyOfRange(0, split)), 2))
        assertNull(mirror.load())
        assertEquals(409, send(part(1, body.copyOfRange(split, body.size), "8CF00000-1000-4000-9000-000000000002"), 3))
        assertNull(mirror.load())
        assertEquals(200, send(part(1, body.copyOfRange(split, body.size)), 4))
        assertEquals(full, mirror.load())
        assertEquals(1L, coordinator.letterboxdApplied.value)
        assertEquals(409, send(rawSettings, 4))

        val oldPreview = full.copy(
            popular = titles.take(1),
            letterboxdShelves = full.letterboxdShelves.map { it.copy(items = it.items.take(1)) },
        )
        assertEquals(200, send("""{"action":"catalog","catalog":${Json.encodeToString(oldPreview)}}""", 5))
        assertEquals(1, preview.load()?.letterboxdShelves?.single()?.items?.size)
        assertEquals(1_305, mirror.load()?.letterboxdShelves?.single()?.items?.size)
        assertEquals(200, send("""{"action":"disconnect"}""", 6))
        assertEquals(full, mirror.load())
        assertEquals(409, send(part(0, body.copyOfRange(0, split)), 7))
        coordinator.clearReceiverSetup()
        assertNull(mirror.load())
    }

    @Test
    fun trustedCatalogSyncPersistsExactSourcesAndRejectsReplay() = runTest {
        val persistence = FakePersistence()
        val cache = FakeCatalogCache()
        val key = ByteArray(32) { 4 }
        persistence.save(TVSettingsBackupPolicy.validate(rawSettings.encodeToByteArray()), 1, key)
        val coordinator = TVSettingsPairingCoordinator("receiver-sync", persistence, cache, uptimeMillis = { 0L })
        val source = com.fourseveneightnine.tv.client.data.catalog.CatalogPlaybackSource(
            "0123456789abcdef0123456789abcdef01234567", "Exact.mkv", 42, 7)
        val snapshot = TVTamilMVCatalogSnapshot(generation = "iphone-new", generatedAtMillis = 2, cachedAtMillis = 3,
            popular = emptyList(), recent = emptyList(), library = listOf(
                TVTamilMVCatalogItem("saved:library:one", "movie", "Exact", playbackSources = listOf(source))))
        val payload = "{\"action\":\"catalog\",\"catalog\":" + Json.encodeToString(snapshot) + "}"
        val envelope = SettingsPairingCrypto.encryptForTest(payload.encodeToByteArray(), key,
            "4789-settings-sync-v2|receiver-sync|2", 2, version = 2)
        assertEquals(200, coordinator.applySync("receiver-sync", envelope).statusCode)
        assertEquals(7, cache.value?.library?.single()?.playbackSources?.single()?.fileID)
        assertEquals(rawSettings, persistence.document?.rawJson)
        assertEquals(1L, persistence.document?.revision)
        assertEquals(2L, cache.syncRevision("receiver-sync", key))
        assertEquals(409, coordinator.applySync("receiver-sync", envelope).statusCode)
        for (stalePayload in listOf(rawSettings, "{\"action\":\"disconnect\"}")) {
            val stale = SettingsPairingCrypto.encryptForTest(stalePayload.encodeToByteArray(), key,
                "4789-settings-sync-v2|receiver-sync|2", revision = 2, version = 2)
            assertEquals(409, coordinator.applySync("receiver-sync", stale).statusCode)
            assertNotNull(persistence.document?.syncKeyBase64)
        }
        val forged = SettingsPairingCrypto.encryptForTest(payload.encodeToByteArray(), ByteArray(32) { 9 },
            "4789-settings-sync-v2|receiver-sync|3", 3, version = 2)
        assertEquals(400, coordinator.applySync("receiver-sync", forged).statusCode)
        assertEquals(snapshot, cache.value)
    }

    @Test
    fun fileApprovalNotifiesTheRunningShellAfterItBecomesConfigured() = runTest {
        val coordinator = TVSettingsPairingCoordinator("receiver-1", FakePersistence()) { 1_000L }
        val observed = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.settingsApplied.first()
            coordinator.state.value
        }
        coordinator.stageFile(rawSettings.encodeToByteArray())
        coordinator.approve(keepSync = false)
        assertTrue(withTimeout(1_000) { observed.await() } is TVSettingsPairingState.Saved)
    }

    @Test
    fun fileApprovalPreservesTrustedSyncAndRejectsOlderUpdates() = runTest {
        val persistence = FakePersistence()
        val key = ByteArray(32) { 4 }
        persistence.save(TVSettingsBackupPolicy.validate(rawSettings.encodeToByteArray()), 7L, key)
        val coordinator = TVSettingsPairingCoordinator("receiver-sync", persistence) { 0L }
        val export = rawSettings.replace("never-echo-this", "new-fixture-key")

        coordinator.stageFile(export.encodeToByteArray())
        coordinator.approve(keepSync = false)

        assertEquals(export, persistence.document?.rawJson)
        assertEquals(7L, persistence.document?.revision)
        assertTrue((coordinator.state.value as TVSettingsPairingState.Saved).syncEnabled)
        for ((revision, expected) in listOf(7L to 409, 8L to 200)) {
            val envelope = SettingsPairingCrypto.encryptForTest(rawSettings.encodeToByteArray(), key,
                "4789-settings-sync-v2|receiver-sync|$revision", revision, version = 2)
            assertEquals(expected, coordinator.applySync("receiver-sync", envelope).statusCode)
        }
    }

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
        assertTrue(withContext(Dispatchers.IO) { persistence.saveEntered.await(5, TimeUnit.SECONDS) })
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
    fun clearReceiverSetupRemovesThePrivateCatalogWithTheEncryptedSettings() = runTest {
        val persistence = FakePersistence()
        val cache = FakeCatalogCache()
        val coordinator = TVSettingsPairingCoordinator(
            receiverID = "receiver-1",
            store = persistence,
            catalogCache = cache,
            uptimeMillis = { 1_000L },
        )
        stagePairing(coordinator)
        cache.value = TVTamilMVCatalogSnapshot(
            generation = "private-owner",
            generatedAtMillis = 1,
            cachedAtMillis = 2,
            popular = listOf(TVTamilMVCatalogItem("movie:1", "movie", "Private title")),
            recent = emptyList(),
        )
        coordinator.approve(keepSync = true)
        assertTrue(coordinator.state.value is TVSettingsPairingState.Saved)
        assertTrue(coordinator.hasSavedSettings.value)

        coordinator.clearReceiverSetup()

        assertNull(persistence.document)
        assertNull(cache.value)
        assertEquals(TVSettingsPairingState.Idle, coordinator.state.value)
        assertFalse(coordinator.hasSavedSettings.value)
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
            "4789-settings-sync-v2|$receiverID|$revision",
            revision,
            version = 2,
        )

        assertEquals(409, coordinator.applySync(receiverID, envelope(rawSettings, 7)).statusCode)
        assertEquals(200, coordinator.applySync(receiverID, envelope(rawSettings, 8)).statusCode)
        assertEquals(8L, persistence.document?.revision)
        assertEquals(200, coordinator.applySync(receiverID, envelope("{\"action\":\"disconnect\"}", 9)).statusCode)
        assertNull(persistence.document?.syncKeyBase64)
        assertEquals(409, coordinator.applySync(receiverID, envelope(rawSettings, 10)).statusCode)
    }

    @Test
    fun stremioImportCanConfigureAnUnpairedTVAndReimportWithoutDuplicates() = runTest {
        val persistence = FakePersistence()
        val coordinator = TVSettingsPairingCoordinator("receiver-local", persistence) { 1_000L }
        val first = AddonSource("First", "https://one.example/config", true)

        val inserted = coordinator.importStremioAddons(listOf(first), replace = false)
        assertEquals(TVAddonImportReceipt(1, 0, 0), inserted)
        assertTrue(coordinator.state.value is TVSettingsPairingState.Saved)
        val installed = SettingsDocument.parse(requireNotNull(persistence.document).rawJson).tvImportedSources.single()
        assertEquals("https://one.example/config/manifest.json", installed.url)
        assertTrue(requireNotNull(installed.addedAtMillis) > 0)

        val updated = coordinator.importStremioAddons(listOf(first.copy(name = "Renamed")), replace = false)
        assertEquals(TVAddonImportReceipt(0, 1, 0), updated)
        val renamed = SettingsDocument.parse(requireNotNull(persistence.document).rawJson).tvImportedSources.single()
        assertEquals("Renamed", renamed.name)
        assertEquals(installed.addedAtMillis, renamed.addedAtMillis)
    }

    @Test
    fun replaceHidesPhoneSourcesAndSurvivesLaterTrustedSync() = runTest {
        val persistence = FakePersistence()
        val syncKey = ByteArray(32) { (it + 1).toByte() }
        persistence.save(TVSettingsBackupPolicy.validate(rawSettings.encodeToByteArray()), 7, syncKey)
        val coordinator = TVSettingsPairingCoordinator("receiver-sync", persistence) { 1_000L }
        val replacement = AddonSource("Imported", "https://imported.example/manifest.json", true)

        val receipt = coordinator.importStremioAddons(listOf(replacement), replace = true)
        assertEquals(TVAddonImportReceipt(1, 0, 1), receipt)
        var document = SettingsDocument.parse(requireNotNull(persistence.document).rawJson)
        assertTrue(document.tvReplaceSources)
        assertEquals("Imported", document.tvImportedSources.single().name)
        assertEquals(7L, persistence.document?.revision)

        val envelope = SettingsPairingCrypto.encryptForTest(
            rawSettings.encodeToByteArray(), syncKey, "4789-settings-sync-v2|receiver-sync|8", 8, version = 2,
        )
        assertEquals(200, coordinator.applySync("receiver-sync", envelope).statusCode)
        document = SettingsDocument.parse(requireNotNull(persistence.document).rawJson)
        assertTrue(document.tvReplaceSources)
        assertEquals("Imported", document.tvImportedSources.single().name)
        assertEquals(8L, persistence.document?.revision)

        assertTrue(coordinator.includePhoneAddons())
        document = SettingsDocument.parse(requireNotNull(persistence.document).rawJson)
        assertFalse(document.tvReplaceSources)
        assertEquals("Imported", document.tvImportedSources.single().name)
        assertTrue(document.addonSources.isNotEmpty())
        assertEquals(8L, persistence.document?.revision)

        val nextEnvelope = SettingsPairingCrypto.encryptForTest(
            rawSettings.encodeToByteArray(), syncKey, "4789-settings-sync-v2|receiver-sync|9", 9, version = 2,
        )
        assertEquals(200, coordinator.applySync("receiver-sync", nextEnvelope).statusCode)
        val next = SettingsDocument.parse(requireNotNull(persistence.document).rawJson)
        assertFalse(next.tvReplaceSources)
        assertEquals("Imported", next.tvImportedSources.single().name)
    }

    @Test
    fun localImportSurvivesFirstPhonePairingAndClearRemovesIt() = runTest {
        val persistence = FakePersistence()
        val coordinator = TVSettingsPairingCoordinator("receiver-pair", persistence) { 1_000L }
        coordinator.importStremioAddons(
            listOf(AddonSource("Imported", "https://imported.example/manifest.json", true)),
            replace = false,
        )

        stagePairing(coordinator)
        coordinator.approve(keepSync = false)
        val paired = SettingsDocument.parse(requireNotNull(persistence.document).rawJson)
        assertEquals(listOf("Imported"), paired.tvImportedSources.map { it.name })
        assertEquals(listOf("A"), paired.addonSources.map { it.name })

        coordinator.clearReceiverSetup()
        assertNull(persistence.document)
        assertEquals(TVSettingsPairingState.Idle, coordinator.state.value)
    }

    @Test
    fun oneScanStagesMultipleAddsAndRemovalsAndRejectsReplayedChanges() = runTest {
        var time = 0L
        val persistence = FakePersistence()
        val coordinator = TVSettingsPairingCoordinator("receiver", persistence) { time }
        val form = coordinator.addonLinks
        val imported = Addon("Imported", "https://example.com/config/manifest.json?key=a%2Bb")
        val builtin = Addon("Cinemeta", StremioClient.CINEMETA_MANIFEST)
        val inventory = { AddonLinkForm.Inventory(listOf(builtin, imported), true) }
        assertNull(form.open("8.8.8.8", inventory))
        val invite = requireNotNull(form.open("192.168.1.4", inventory))
        val secret = SettingsPairingCrypto.decodeBase64URL(URI(invite.address).fragment)
        fun envelope(value: String) = SettingsPairingCrypto.encryptForTest(value.encodeToByteArray(),
            secret, "4789-addon-link-v1|${invite.token}")
        fun command(action: String, value: String = "") =
            envelope("""{"action":"$action","value":"$value","ticket":"${invite.ticket}"}""")
        assertEquals(410, form.submit("wrong", command("status")).statusCode)
        assertEquals(400, form.submit(invite.token, "plaintext").statusCode)
        assertEquals(200, form.submit(invite.token, command("status")).statusCode)
        assertEquals(setOf(builtin.key, imported.key), invite.ids.keys)
        assertEquals(400, form.submit(invite.token, command("remove", invite.ids.getValue(builtin.key))).statusCode)
        assertEquals(400, form.submit(invite.token, command("add", "invalid")).statusCode)
        for (host in listOf("one.example", "two.example")) {
            val request = command("add", "stremio://$host/config/manifest.json?key=a%2Bb")
            val receipt = form.submit(invite.token, request)
            assertEquals(200, receipt.statusCode)
            assertFalse(receipt.body.contains("example"))
            assertEquals(409, form.submit(invite.token, request).statusCode)
            val pending = invite.pending.value as AddonLinkForm.Change.Add
            assertEquals("https://$host/config/manifest.json?key=a%2Bb", pending.url)
            assertNull(persistence.document)
            form.finish(invite, pending, "Added")
            assertEquals(409, form.submit(invite.token, request).statusCode)
            assertNull(invite.pending.value)
            time += AddonLinkForm.LIFETIME
        }
        assertEquals(400, form.submit(invite.token, command("remove", "unknown")).statusCode)
        assertEquals(200, form.submit(invite.token, command("remove", invite.ids.getValue(imported.key))).statusCode)
        val remove = invite.pending.value as AddonLinkForm.Change.Remove
        assertEquals(imported.manifestURL, remove.url)
        form.finish(invite, remove, "Canceled")
        assertEquals(200, form.submit(invite.token, command("refresh", invite.ids.getValue(builtin.key))).statusCode)
        val refresh = invite.pending.value as AddonLinkForm.Change.Refresh
        assertEquals(builtin.manifestURL, refresh.url)
        form.finish(invite, refresh, "Refreshed")
        assertEquals(200, form.submit(invite.token, command("toggle", invite.ids.getValue(imported.key))).statusCode)
        val toggle = invite.pending.value as AddonLinkForm.Change.Toggle
        assertFalse(toggle.enabled)
        form.finish(invite, toggle, "Turned off")
        assertEquals(200, form.submit(invite.token, command("copy", invite.ids.getValue(imported.key))).statusCode)
        val copy = invite.pending.value as AddonLinkForm.Change.Copy
        assertNull(invite.copiedLink)
        form.finish(invite, copy, "Link approved", copy.url)
        assertEquals(imported.manifestURL, invite.copiedLink)
        val status = form.submit(invite.token, command("status"))
        assertEquals(200, status.statusCode)
        assertFalse(status.body.contains(imported.manifestURL))
        assertEquals(200, form.submit(invite.token, command("deleteImports")).statusCode)
        assertNull(invite.copiedLink)
        assertEquals(AddonLinkForm.Change.DeleteImports, invite.pending.value)
        val renewed = requireNotNull(form.open("192.168.1.4", inventory))
        form.close(invite)
        assertTrue(form.isOpen(renewed.token))
        assertFalse(form.isOpen(invite.token))
        time = renewed.expiresAt
        assertFalse(form.isOpen(renewed.token))
        val closed = requireNotNull(form.open("192.168.1.4", inventory))
        form.close(closed)
        assertFalse(form.isOpen(closed.token))
    }

    @Test
    fun deletingStremioImportsRestoresManualAndPhoneSourcesAfterReplaceAndSync() = runTest {
        val persistence = FakePersistence()
        val coordinator = TVSettingsPairingCoordinator("receiver", persistence) { 0L }
        val syncKey = ByteArray(32) { it.toByte() }
        persistence.save(TVSettingsBackupPolicy.validate(rawSettings.encodeToByteArray()), 8, syncKey)
        fun saved() = SettingsDocument.parse(requireNotNull(persistence.document).rawJson)
        val manual = AddonSource("Manual", "https://manual.example/manifest.json", true)
        val imported = AddonSource("Import only", "https://import.example/manifest.json", true)
        coordinator.addDirectAddon(manual)
        coordinator.importStremioAddons(listOf(manual, imported), replace = true)
        assertTrue(saved().visibleDirectAddons.isEmpty())
        coordinator.addDirectAddon(manual.copy(name = "Second", url = "https://second.example/manifest.json"))
        assertEquals(listOf("Second"), saved().visibleDirectAddons.map { it.source.name })
        assertEquals(2, coordinator.deleteStremioImports())
        assertFalse(saved().tvReplaceSources)
        assertTrue(saved().tvImportedSources.isEmpty())
        assertEquals(listOf("Manual", "Second"), saved().visibleDirectAddons.map { it.source.name })
        assertEquals(listOf("A"), saved().addonSources.map { it.name })
        assertEquals(8L, persistence.document?.revision)
        assertEquals(0, coordinator.deleteStremioImports())
        val sync = SettingsPairingCrypto.encryptForTest(rawSettings.encodeToByteArray(), syncKey,
            "4789-settings-sync-v2|receiver|9", 9L, version = 2)
        assertEquals(200, coordinator.applySync("receiver", sync).statusCode)
        assertTrue(saved().tvImportedSources.isEmpty())
        assertEquals(2, saved().visibleDirectAddons.size)
    }

    @Test
    fun deletingLegacyImportsWorksOfflineAndKeepsTheLastSavedSetupOnFailure() = runTest {
        val persistence = FakePersistence()
        val legacy = """{"format":"4789-settings","version":1,"tvImportedSources":[
            {"name":"Old","url":"https://old.example/manifest.json"}]}"""
        persistence.save(TVSettingsBackupPolicy.validate(legacy.encodeToByteArray()), 2, null)
        val failing = object : TVSettingsPersistence by persistence {
            override fun save(settings: ValidatedTVSettings, revision: Long, syncKey: ByteArray?) = false
        }
        val before = persistence.document
        assertTrue(runCatching { TVSettingsPairingCoordinator("tv", failing) { 0L }.deleteStremioImports() }.isFailure)
        assertEquals(before, persistence.document)
        val coordinator = TVSettingsPairingCoordinator("tv", persistence) { 0L }
        assertEquals(1, coordinator.deleteStremioImports())
        assertTrue(SettingsDocument.parse(requireNotNull(persistence.document).rawJson).tvImportedSources.isEmpty())
        assertTrue(coordinator.state.value is TVSettingsPairingState.Saved)
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
        private var syncReceiverID: String? = null
        private var syncKey: ByteArray? = null
        private var revision = 0L
        override fun load(): TVTamilMVCatalogSnapshot? = value
        override fun store(snapshot: TVTamilMVCatalogSnapshot) { value = snapshot }
        override fun syncRevision(receiverID: String, syncKey: ByteArray): Long =
            if (syncReceiverID == receiverID && this.syncKey?.contentEquals(syncKey) == true) revision else 0L
        override fun storeSynced(snapshot: TVTamilMVCatalogSnapshot, receiverID: String, revision: Long, syncKey: ByteArray) {
            check(revision > syncRevision(receiverID, syncKey))
            value = snapshot
            syncReceiverID = receiverID
            this.syncKey = syncKey.copyOf()
            this.revision = revision
        }
        override fun clear(discardSyncState: Boolean) {
            value = null
            if (discardSyncState) {
                syncReceiverID = null
                syncKey = null
                revision = 0L
            }
        }
    }
}
