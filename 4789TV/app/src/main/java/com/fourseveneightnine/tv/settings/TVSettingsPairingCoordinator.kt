package com.fourseveneightnine.tv.settings

import com.fourseveneightnine.tv.catalog.TVTamilMVCatalogCache
import com.fourseveneightnine.tv.catalog.TVTamilMVCatalogImportPolicy
import com.fourseveneightnine.tv.catalog.TVTamilMVCatalogSnapshot
import com.fourseveneightnine.tv.transport.SettingsEndpointResponse
import com.fourseveneightnine.tv.transport.SettingsPairingEndpoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

internal enum class TVSettingsImportSource { SecurePairing, File }

internal sealed interface TVSettingsPairingState {
    data object Idle : TVSettingsPairingState
    data class Invitation(
        val pairingID: String,
        val qrText: String,
        val expiresAtUptimeMillis: Long,
    ) : TVSettingsPairingState

    data class Staged(
        val receipt: TVSettingsReceipt,
        val source: TVSettingsImportSource,
    ) : TVSettingsPairingState

    data class Saving(val receipt: TVSettingsReceipt) : TVSettingsPairingState

    data class Saved(
        val receipt: TVSettingsReceipt,
        val syncEnabled: Boolean,
        val message: String,
    ) : TVSettingsPairingState

    data class Error(val message: String) : TVSettingsPairingState
}

@Serializable
private data class PairStatusPayload(
    val status: String,
    val keepSync: Boolean = false,
    val receiverID: String? = null,
)

@Serializable
private data class PairStagePayload(
    val status: String,
    val receipt: TVSettingsReceipt,
)

@Serializable
private data class CatalogStagePayload(
    val status: String,
    val itemCount: Int,
)

@Serializable
private data class SyncPayload(val status: String)

/**
 * Owns every plaintext lifetime: invitation → staged receipt → approved encrypted store.
 * Staged data is memory-only and is wiped on reject, expiry, replacement, or Activity teardown.
 */
internal class TVSettingsPairingCoordinator(
    private val receiverID: String,
    private val store: TVSettingsPersistence,
    private val catalogCache: TVTamilMVCatalogCache? = null,
    private val storageDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val uptimeMillis: () -> Long = android.os.SystemClock::uptimeMillis,
) : SettingsPairingEndpoint {
    private data class ActivePair(
        val id: String,
        val secret: ByteArray,
        val expiresAt: Long,
        var consumed: Boolean = false,
    )

    private data class StagedSettings(
        val validated: ValidatedTVSettings,
        val source: TVSettingsImportSource,
        val pair: ActivePair?,
        val catalog: TVTamilMVCatalogSnapshot? = null,
    )

    private data class LastPairStatus(
        val id: String,
        val status: String,
        val keepSync: Boolean,
        val expiresAt: Long,
    )

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = false }
    // Keystore decrypt/read-back is deliberately not part of construction. Fire OS can block a
    // cold Activity for ~1s while the provider is brought up; the TV can draw its first frame with
    // the honest Idle state and restore the persisted receipt through [restorePersistedState].
    private val mutableState = MutableStateFlow<TVSettingsPairingState>(TVSettingsPairingState.Idle)
    val state: StateFlow<TVSettingsPairingState> = mutableState.asStateFlow()

    private var activePair: ActivePair? = null
    private var staged: StagedSettings? = null
    private var lastPairStatus: LastPairStatus? = null
    private var persistedStateLoaded = false

    /** Restores the encrypted receipt off the UI critical path, once per coordinator lifetime. */
    suspend fun restorePersistedState() = mutex.withLock {
        if (persistedStateLoaded) return@withLock
        val restored = read(::initialState)
        // A phone may complete pairing before this background read returns. Never replace a live
        // invitation/staged/saved state with the older persisted snapshot in that case.
        if (activePair == null && staged == null && mutableState.value == TVSettingsPairingState.Idle) {
            mutableState.value = restored
        }
        persistedStateLoaded = true
    }

    suspend fun beginPairing(host: String?) = mutex.withLock {
        if (host == null || !isPrivateIPv4(host)) {
            mutableState.value = TVSettingsPairingState.Error(
                "Connect this TV to the same private Wi-Fi network as your phone, then try again.",
            )
            return@withLock
        }
        wipeStagedLocked()
        val id = SettingsPairingCrypto.randomID()
        val secret = SettingsPairingCrypto.randomBytes(32)
        val expiresAt = uptimeMillis() + INVITATION_LIFETIME_MILLIS
        activePair = ActivePair(id, secret, expiresAt)
        lastPairStatus = LastPairStatus(id, "waiting", keepSync = false, expiresAt = expiresAt)
        mutableState.value = TVSettingsPairingState.Invitation(
            pairingID = id,
            qrText = "http://$host:8791/x4789/pair/$id#${SettingsPairingCrypto.encodeBase64URL(secret)}",
            expiresAtUptimeMillis = expiresAt,
        )
    }

    suspend fun stageFile(bytes: ByteArray) = mutex.withLock {
        runCatching { TVSettingsBackupPolicy.validate(bytes) }.fold(
            onSuccess = { validated ->
                wipeStagedLocked()
                staged = StagedSettings(validated, TVSettingsImportSource.File, pair = null)
                mutableState.value = TVSettingsPairingState.Staged(
                    validated.receipt,
                    TVSettingsImportSource.File,
                )
            },
            onFailure = { mutableState.value = TVSettingsPairingState.Error("That file is not a valid 4789 settings export.") },
        )
    }

    suspend fun expireInvitation() = mutex.withLock {
        val pair = activePair ?: return@withLock
        if (uptimeMillis() < pair.expiresAt) return@withLock
        wipeStagedLocked()
        mutableState.value = TVSettingsPairingState.Error("Pairing code expired. Start a new one.")
    }

    suspend fun approve(keepSync: Boolean) = mutex.withLock {
        val pending = staged ?: return@withLock
        val syncKey = if (keepSync) {
            pending.pair?.let { SettingsPairingCrypto.deriveSyncKey(it.secret, receiverID) }
        } else {
            null
        }
        // Keystore generation, AES-GCM, SharedPreferences.commit(), and read-back verification
        // are synchronous Android APIs. Publish a non-actionable state before leaving Main, then
        // keep the complete atomic save off the Compose/UI thread.
        mutableState.value = TVSettingsPairingState.Saving(pending.validated.receipt)
        val saved = persist {
            store.save(pending.validated, revision = 1L, syncKey = syncKey)
        }
        if (!saved) {
            pending.pair?.let {
                lastPairStatus = LastPairStatus(
                    id = it.id,
                    status = "rejected",
                    keepSync = false,
                    expiresAt = uptimeMillis() + STATUS_LIFETIME_MILLIS,
                )
            }
            wipeStagedLocked(keepLastStatus = pending.pair != null)
            mutableState.value = TVSettingsPairingState.Error("The receiver could not verify the encrypted save.")
            return@withLock
        }
        pending.catalog?.let { catalog ->
            // Settings are the required transaction; a cache write cannot roll back an already
            // verified Keystore save. A later QR transfer or server refresh can retry the catalog.
            persistCatalog(catalog)
        }
        pending.pair?.let {
            lastPairStatus = LastPairStatus(
                id = it.id,
                status = "approved",
                keepSync = syncKey != null,
                expiresAt = uptimeMillis() + STATUS_LIFETIME_MILLIS,
            )
        }
        mutableState.value = TVSettingsPairingState.Saved(
            receipt = pending.validated.receipt,
            syncEnabled = syncKey != null,
            message = if (syncKey != null) "Settings saved · foreground sync is trusted" else "Settings saved on this receiver",
        )
        wipeStagedLocked(keepLastStatus = true)
    }

    suspend fun reject() = mutex.withLock {
        staged?.pair?.let {
            lastPairStatus = LastPairStatus(
                id = it.id,
                status = "rejected",
                keepSync = false,
                expiresAt = uptimeMillis() + STATUS_LIFETIME_MILLIS,
            )
        }
        wipeStagedLocked(keepLastStatus = true)
        mutableState.value = read(::initialState)
    }

    suspend fun disconnectSync() = mutex.withLock {
        val cleared = persist(store::clearSync)
        if (!cleared) {
            mutableState.value = TVSettingsPairingState.Error("The sync credential could not be removed.")
            return@withLock
        }
        mutableState.value = read {
            savedState("Sync disconnected · imported settings remain on this receiver")
        }
    }

    suspend fun clearReceiverSetup() = mutex.withLock {
        val cleared = persist(store::clearAll)
        if (!cleared) {
            mutableState.value = TVSettingsPairingState.Error("The encrypted setup could not be cleared.")
            return@withLock
        }
        wipeStagedLocked()
        mutableState.value = TVSettingsPairingState.Idle
    }

    suspend fun close() = mutex.withLock {
        val stagedPair = staged?.pair
        if (stagedPair != null) {
            lastPairStatus = LastPairStatus(
                id = stagedPair.id,
                status = "rejected",
                keepSync = false,
                expiresAt = uptimeMillis() + STATUS_LIFETIME_MILLIS,
            )
        }
        wipeStagedLocked(keepLastStatus = stagedPair != null)
        mutableState.value = read(::initialState)
    }

    override suspend fun stagePair(pairingID: String, body: String): SettingsEndpointResponse = mutex.withLock {
        val pair = activePair
        if (pair == null || pair.id != pairingID) return@withLock response(404, SyncPayload("unknown_invitation"))
        if (uptimeMillis() >= pair.expiresAt) {
            wipeStagedLocked()
            mutableState.value = TVSettingsPairingState.Error("Pairing code expired. Start a new one.")
            return@withLock response(410, SyncPayload("expired"))
        }
        if (pair.consumed) return@withLock response(409, SyncPayload("already_used"))

        val validated = runCatching {
            val plaintext = SettingsPairingCrypto.decryptPair(body, pair.secret, pair.id)
            TVSettingsBackupPolicy.validate(plaintext)
        }.getOrElse {
            return@withLock response(400, SyncPayload("invalid_envelope"))
        }
        pair.consumed = true
        staged = StagedSettings(validated, TVSettingsImportSource.SecurePairing, pair)
        lastPairStatus = LastPairStatus(pair.id, "staged", keepSync = false, expiresAt = pair.expiresAt)
        mutableState.value = TVSettingsPairingState.Staged(
            receipt = validated.receipt,
            source = TVSettingsImportSource.SecurePairing,
        )
        response(202, PairStagePayload("staged", validated.receipt))
    }

    override suspend fun stagePairCatalog(pairingID: String, body: String): SettingsEndpointResponse = mutex.withLock {
        val pending = staged ?: return@withLock response(409, SyncPayload("settings_not_staged"))
        val pair = pending.pair
        if (pair == null || pair.id != pairingID) {
            return@withLock response(404, SyncPayload("unknown_invitation"))
        }
        if (uptimeMillis() >= pair.expiresAt) {
            wipeStagedLocked()
            mutableState.value = TVSettingsPairingState.Error("Pairing code expired. Start a new one.")
            return@withLock response(410, SyncPayload("expired"))
        }
        val catalog = runCatching {
            TVTamilMVCatalogImportPolicy.decode(
                SettingsPairingCrypto.decryptCatalogPair(body, pair.secret, pair.id),
            )
        }.getOrElse {
            return@withLock response(400, SyncPayload("invalid_catalog_envelope"))
        }
        staged = pending.copy(catalog = catalog)
        response(202, CatalogStagePayload("catalog_staged", catalog.itemCount))
    }

    override suspend fun pairStatus(pairingID: String): SettingsEndpointResponse = mutex.withLock {
        val status = lastPairStatus
        if (status == null || status.id != pairingID || uptimeMillis() >= status.expiresAt) {
            return@withLock response(404, PairStatusPayload("unknown"))
        }
        response(
            200,
            PairStatusPayload(
                status = status.status,
                keepSync = status.keepSync,
                receiverID = receiverID.takeIf { status.status == "approved" },
            ),
        )
    }

    override suspend fun applySync(receiverID: String, body: String): SettingsEndpointResponse = mutex.withLock {
        if (receiverID != this.receiverID) return@withLock response(404, SyncPayload("unknown_receiver"))
        val stored = (read(store::load) as? StoredTVSettingsState.Available)?.document
            ?: return@withLock response(409, SyncPayload("not_paired"))
        val syncKey = stored.syncKeyBase64?.let(SettingsPairingCrypto::decodeBase64URL)
            ?: return@withLock response(409, SyncPayload("sync_disabled"))
        val decrypted = runCatching { SettingsPairingCrypto.decryptSync(body, syncKey, receiverID) }
            .getOrElse { return@withLock response(400, SyncPayload("invalid_envelope")) }
        val (plaintext, revision) = decrypted
        if (revision <= stored.revision) return@withLock response(409, SyncPayload("stale_revision"))

        val root = runCatching { json.parseToJsonElement(plaintext.decodeToString()).jsonObject }.getOrNull()
            ?: return@withLock response(400, SyncPayload("invalid_payload"))
        if ((root["action"] as? JsonPrimitive)?.content == "disconnect") {
            val cleared = persist(store::clearSync)
            if (!cleared) return@withLock response(500, SyncPayload("save_failed"))
            mutableState.value = read {
                savedState("Sync disconnected · imported settings remain on this receiver")
            }
            return@withLock response(200, SyncPayload("disconnected"))
        }

        val validated = runCatching { TVSettingsBackupPolicy.validate(plaintext) }
            .getOrElse { return@withLock response(400, SyncPayload("invalid_settings")) }
        val saved = persist { store.save(validated, revision, syncKey) }
        if (!saved) return@withLock response(500, SyncPayload("save_failed"))
        mutableState.value = TVSettingsPairingState.Saved(
            validated.receipt,
            syncEnabled = true,
            message = "Settings synced from the trusted phone",
        )
        response(200, SyncPayload("applied"))
    }

    private fun initialState(): TVSettingsPairingState = when (val loaded = store.load()) {
        StoredTVSettingsState.Missing -> TVSettingsPairingState.Idle
        is StoredTVSettingsState.Unavailable -> TVSettingsPairingState.Error(
            "Encrypted settings are unavailable (${loaded.reason}).",
        )
        is StoredTVSettingsState.Available -> savedState("Settings are configured on this receiver")
    }

    private fun savedState(message: String): TVSettingsPairingState {
        val loaded = (store.load() as? StoredTVSettingsState.Available)?.document
            ?: return TVSettingsPairingState.Idle
        val receipt = runCatching {
            TVSettingsBackupPolicy.validate(loaded.rawJson.encodeToByteArray()).receipt
        }.getOrElse { TVSettingsReceipt(0, emptyList()) }
        return TVSettingsPairingState.Saved(
            receipt = receipt,
            syncEnabled = loaded.syncKeyBase64 != null,
            message = message,
        )
    }

    private fun wipePairLocked() {
        activePair?.secret?.fill(0)
        activePair = null
    }

    private fun wipeStagedLocked(keepLastStatus: Boolean = false) {
        wipePairLocked()
        staged = null
        if (!keepLastStatus) lastPairStatus = null
    }

    /** Once persistence starts it completes atomically even if the Activity is closing. */
    private suspend fun persist(action: () -> Boolean): Boolean = withContext(NonCancellable) {
        withContext(storageDispatcher) {
            runCatching(action).getOrDefault(false)
        }
    }

    private suspend fun persistCatalog(snapshot: TVTamilMVCatalogSnapshot) = withContext(NonCancellable) {
        withContext(storageDispatcher) {
            runCatching { catalogCache?.store(snapshot) }
        }
    }

    private suspend fun <T> read(action: () -> T): T = withContext(storageDispatcher) { action() }

    private inline fun <reified T> response(code: Int, body: T): SettingsEndpointResponse =
        SettingsEndpointResponse(code, json.encodeToString(body))

    private fun isPrivateIPv4(address: String): Boolean {
        val octets = address.split('.').mapNotNull(String::toIntOrNull)
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        return octets[0] == 10 ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)
    }

    private companion object {
        const val INVITATION_LIFETIME_MILLIS = 5 * 60_000L
        const val STATUS_LIFETIME_MILLIS = 2 * 60_000L
    }
}
