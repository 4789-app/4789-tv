package com.fourseveneightnine.tv.settings

import com.fourseveneightnine.tv.catalog.TVTamilMVCatalogCache
import com.fourseveneightnine.tv.catalog.TVTamilMVCatalogImportPolicy
import com.fourseveneightnine.tv.catalog.TVTamilMVCatalogSnapshot
import com.fourseveneightnine.tv.client.data.addons.AddonEndpoint
import com.fourseveneightnine.tv.client.data.settings.AddonSource
import com.fourseveneightnine.tv.client.data.settings.SettingsDocument
import com.fourseveneightnine.tv.client.data.streams.CachedHint
import com.fourseveneightnine.tv.client.data.streams.StreamFacts
import com.fourseveneightnine.tv.client.data.streams.StreamRow
import com.fourseveneightnine.tv.transport.SettingsEndpointResponse
import com.fourseveneightnine.tv.transport.SettingsPairingEndpoint
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

internal enum class TVSettingsImportSource { SecurePairing, File }

internal data class TVAddonImportReceipt(val added: Int, val updated: Int, val removed: Int)

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

    data class Error(val message: String, val settingsRemoved: Boolean = false) : TVSettingsPairingState
}

@Serializable
private data class PairStatusPayload(
    val status: String,
    val syncVersion: Int,
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

/** Optional trusted-sync action. Older phones/receivers continue using settings and catalogs. */
@Serializable
internal data class TVPhoneSources(
    val action: String = "phone_sources",
    val version: Int = 1,
    val type: String,
    val imdbID: String? = null,
    val tmdbID: Int? = null,
    val title: String,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val rows: List<TVPhoneSource>,
) {
    fun identifiers(): List<String> = listOfNotNull(imdbID, tmdbID?.let { "tmdb:$it" }).map {
        if (type == "series") "$it:$season:$episode" else it
    }

    fun validate(): TVPhoneSources {
        require(version == 1 && type in setOf("movie", "series") && title.length in 1..500)
        require(imdbID == null || Regex("tt[0-9]{1,15}").matches(imdbID))
        require(tmdbID == null || tmdbID > 0)
        require(identifiers().isNotEmpty() && (year == null || year in 1870..2200))
        require(if (type == "movie") season == null && episode == null else
            season != null && season in 0..1_000 && episode != null && episode in 1..100_000)
        require(rows.size <= 200)
        rows.forEach(TVPhoneSource::validate)
        return this
    }
}

@Serializable
internal data class TVPhoneSource(
    val name: String,
    val releaseName: String,
    val url: String? = null,
    val infoHash: String? = null,
    val quality: String? = null,
    val sizeBytes: Long? = null,
    val seeders: Int? = null,
    val cached: Boolean = false,
    val service: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val torrentID: Int? = null,
    val fileID: Int? = null,
) {
    fun validate() {
        require(name.length in 1..160 && releaseName.length in 1..4_000)
        require(url == null || (url.length <= 32_768 && AddonEndpoint.playableURL(url) != null))
        require(infoHash == null || Regex("[a-fA-F0-9]{40}|[a-zA-Z2-7]{32}").matches(infoHash))
        require(url != null || infoHash != null)
        require(quality == null || quality.length <= 80)
        require(sizeBytes == null || sizeBytes >= 0)
        require(seeders == null || seeders >= 0)
        require(service == null || service in setOf("TorBox", "Real-Debrid"))
        require(torrentID == null || torrentID > 0)
        require(fileID == null || fileID >= 0)
        require(headers.size <= 32 && headers.all { (key, value) ->
            key.length in 1..80 && key.all { it.isLetterOrDigit() || it in "!#$%&'*+-.^_`|~" } &&
                value.length <= 4_096 && value.none { it == '\r' || it == '\n' || it == '\u0000' }
        })
    }

    fun row(): StreamRow {
        val facts = StreamFacts.parse(listOf(releaseName), sizeBytes = sizeBytes, statedQuality = quality)
        val source = StreamRow("", name, name, releaseName, url = url, infoHash = infoHash,
            quality = facts.quality, sizeBytes = sizeBytes ?: facts.sizeBytes, codecs = facts.codecs,
            hdr = facts.hdr, audioLanguages = facts.audioLanguages, seeders = seeders,
            cachedHint = CachedHint(cached, service, debrid = cached), headers = headers, facts = facts,
            torboxTorrentID = torrentID, torboxFileID = fileID, cacheHash = infoHash,
            filename = releaseName)
        // Cache evidence is checked again by the receiver; no phone badge grants automatic proof.
        return source.copy(id = source.playbackKey())
    }
}

/**
 * Owns every plaintext lifetime: invitation → staged receipt → approved encrypted store.
 * Staged data is memory-only and is wiped on reject, expiry, replacement, or Activity teardown.
 */
internal class TVSettingsPairingCoordinator(
    private val receiverID: String,
    private val store: TVSettingsPersistence,
    private val catalogCache: TVTamilMVCatalogCache? = null,
    private val storageDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val purgePrivateSetup: suspend () -> Unit = {},
    private val letterboxdCache: TVTamilMVCatalogCache? = null,
    private val uptimeMillis: () -> Long = android.os.SystemClock::uptimeMillis,
) : SettingsPairingEndpoint {
    override val addonLinks = com.fourseveneightnine.tv.transport.AddonLinkForm(uptimeMillis)
    override val letterboxdLinks = com.fourseveneightnine.tv.transport.LetterboxdLinkForm(uptimeMillis)

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

    @Serializable
    private data class LetterboxdPart(
        val action: String,
        val transferID: String,
        val index: Int,
        val count: Int,
        val data: String,
    )

    private class LetterboxdTransfer(val id: String, val owner: String, val count: Int) {
        val body = ByteArrayOutputStream()
        var nextIndex = 0
        var revision = 0L
        var touchedAt = 0L
    }

    private var letterboxdTransfer: LetterboxdTransfer? = null
    private data class PhoneSources(val batch: TVPhoneSources, val receivedAt: Long)
    private val phoneSources = MutableStateFlow<List<PhoneSources>>(emptyList())

    /** Memory-only capabilities, scoped to the exact movie or episode and expired after 5 min. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun sourceRows(type: String, ids: List<String>): Flow<List<StreamRow>> = phoneSources.transformLatest { snapshots ->
        var matching = snapshots.filter { snapshot ->
            snapshot.batch.type == type && snapshot.batch.identifiers().any(ids::contains)
        }
        while (true) {
            val now = uptimeMillis()
            matching = matching.filter { now - it.receivedAt in 0 until PHONE_SOURCE_LIFETIME_MILLIS }
            emit(matching.flatMap { it.batch.rows }.map(TVPhoneSource::row))
            if (matching.isEmpty()) break
            val nextExpiry = matching.minOf { it.receivedAt } + PHONE_SOURCE_LIFETIME_MILLIS
            delay((nextExpiry - uptimeMillis()).coerceAtLeast(0))
        }
    }.distinctUntilChanged()
    private val mutableLetterboxdApplied = MutableStateFlow(0L)
    val letterboxdApplied: StateFlow<Long> = mutableLetterboxdApplied.asStateFlow()

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = false }
    // Keystore decrypt/read-back is deliberately not part of construction. Fire OS can block a
    // cold Activity for ~1s while the provider is brought up; the TV can draw its first frame with
    // the honest Idle state and restore the persisted receipt through [restorePersistedState].
    private val mutableState = MutableStateFlow<TVSettingsPairingState>(TVSettingsPairingState.Idle)
    val state: StateFlow<TVSettingsPairingState> = mutableState.asStateFlow()

    // Invitations, staged imports, and pairing errors can coexist with a saved setup.
    private val mutableHasSavedSettings = MutableStateFlow(false)
    val hasSavedSettings: StateFlow<Boolean> = mutableHasSavedSettings.asStateFlow()

    /**
     * Fires after approved pairing/imports and new settings over the trusted sync channel.
     *
     * The library rebuild used to be hardcoded into the QR-approval callback, so it only ever ran
     * for a first pairing. A later sync — the phone adding an addon, changing a catalog token,
     * editing usernames — saved correctly and then sat there: the receiver kept serving the old
     * addons until the Activity happened to restart. `state` cannot carry this, because a sync that
     * changes settings without changing the receipt is an identical value and a StateFlow drops it.
     */
    private val mutableSettingsApplied = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val settingsApplied: SharedFlow<Unit> = mutableSettingsApplied.asSharedFlow()
    private val mutableCatalogApplied = MutableSharedFlow<Unit>(extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val catalogApplied: SharedFlow<Unit> = mutableCatalogApplied.asSharedFlow()

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
        // Keystore reads and writes can block. Publish the saving state before either.
        mutableState.value = TVSettingsPairingState.Saving(pending.validated.receipt)
        val current = read { (store.load() as? StoredTVSettingsState.Available)?.document }
        val syncKey = when {
            pending.pair == null -> current?.syncKeyBase64?.let(SettingsPairingCrypto::decodeBase64URL)
            keepSync -> SettingsPairingCrypto.deriveSyncKey(pending.pair.secret, receiverID)
            else -> null
        }
        val revision = if (pending.pair == null) current?.revision ?: 1L else 1L
        val validated = runCatching { retainTVImport(pending.validated, current) }.getOrElse {
            pending.pair?.let { pair ->
                lastPairStatus = LastPairStatus(
                    id = pair.id,
                    status = "rejected",
                    keepSync = false,
                    expiresAt = uptimeMillis() + STATUS_LIFETIME_MILLIS,
                )
            }
            wipeStagedLocked(keepLastStatus = pending.pair != null)
            mutableState.value = TVSettingsPairingState.Error("The combined settings exceed this TV's transfer limit.")
            return@withLock
        }
        val saved = persistSetup(validated, revision = revision, syncKey = syncKey)
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
            receipt = validated.receipt,
            syncEnabled = syncKey != null,
            message = if (syncKey != null) "Settings saved · keep both apps updated for sync" else "Settings saved on this receiver",
        )
        publishSettingsApplied()
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
        phoneSources.value = emptyList()
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
        phoneSources.value = emptyList()
        // The normalized catalog is private account data even though it carries no credentials.
        // A failed delete is not a completed reset. Revocation remains authoritative, while the
        // client still purges its active private snapshot and a future approval must retry removal.
        val privateStatePurged = withContext(NonCancellable) {
            runCatching { purgePrivateSetup() }.isSuccess
        }
        val catalogCleared = persist {
            catalogCache?.clear(discardSyncState = true)
            letterboxdCache?.clear(discardSyncState = true)
            true
        }
        letterboxdTransfer = null
        wipeStagedLocked()
        mutableState.value = if (privateStatePurged && catalogCleared) {
            TVSettingsPairingState.Idle
        } else {
            TVSettingsPairingState.Error(
                "Settings were removed, but the catalog could not be cleared. Check TV storage, then try again.",
                settingsRemoved = true,
            )
        }
        mutableHasSavedSettings.value = false
    }

    /**
     * Adds or replaces add-ons only on this receiver. Configured URLs can contain keys, so the
     * complete list is saved inside the existing Keystore-encrypted settings document. A future
     * phone pairing or trusted settings sync retains these TV-only fields.
     */
    suspend fun importStremioAddons(incoming: List<AddonSource>, replace: Boolean): TVAddonImportReceipt =
        mutex.withLock {
            require(staged == null) { "pairing_pending" }
            require(incoming.size <= MAX_LOCAL_ADDONS) { "addon_limit" }
            val normalized = incoming.map { source ->
                val url = AddonEndpoint.manifestURL(source.url) ?: error("bad_manifest_url")
                require(url.startsWith("https://")) { "bad_manifest_url" }
                source.copy(name = source.name.trim().take(80).ifBlank { "Add-on" }, url = url)
            }.distinctBy { AddonEndpoint.normalize(it.url) }

            val current = when (val loaded = read(store::load)) {
                is StoredTVSettingsState.Available -> loaded.document
                StoredTVSettingsState.Missing -> null
                is StoredTVSettingsState.Unavailable -> error("settings_unavailable")
            }
            val oldRoot = current?.rawJson?.let { json.parseToJsonElement(it).jsonObject }
                ?: JsonObject(mapOf("format" to JsonPrimitive("4789-settings"), "version" to JsonPrimitive(1)))
            val oldDocument = SettingsDocument.parse(oldRoot.toString())
            val before = if (oldDocument.tvReplaceSources) oldDocument.tvImportedSources else
                oldDocument.tvImportedSources + oldDocument.addonSources
            val beforeKeys = ((before + oldDocument.visibleDirectAddons.map { it.source }).mapNotNull { AddonEndpoint.normalize(it.url) } +
                if (oldDocument.tvReplaceSources) emptyList() else
                    oldDocument.subtitleSources.mapNotNull { AddonEndpoint.normalize(it.url) }).toSet()
            val nextImported = if (replace) normalized else {
                (oldDocument.tvImportedSources + normalized)
                    .associateBy { AddonEndpoint.normalize(it.url) }
                    .values.toList()
            }
            require(nextImported.size <= MAX_LOCAL_ADDONS) { "addon_limit" }
            val remaining = if (replace) emptyList() else oldDocument.visibleDirectAddons.map { it.source } +
                if (oldDocument.tvReplaceSources) emptyList() else oldDocument.addonSources
            val effectiveCount = (nextImported + remaining)
                .mapNotNull { AddonEndpoint.normalize(it.url) }.distinct().size
            require(effectiveCount <= MAX_LOCAL_ADDONS) { "addon_limit" }
            val nextKeys = normalized.mapNotNull { AddonEndpoint.normalize(it.url) }.toSet()
            val directRows = (oldRoot["tvDirectAddons"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            val priorImports = oldDocument.tvImportedSources.associateBy { AddonEndpoint.normalize(it.url) }
            val importedAt = System.currentTimeMillis()
            val root = JsonObject(oldRoot + mapOf(
                "tvDirectAddons" to JsonArray(directRows.map { row ->
                    if (replace) JsonObject(row + ("hidden" to JsonPrimitive(true))) else row
                }),
                "tvImportedSources" to JsonArray(nextImported.map { source ->
                    val prior = priorImports[AddonEndpoint.normalize(source.url)]
                    val addedAt = if (prior == null) importedAt else prior.addedAtMillis
                    JsonObject(buildMap {
                        put("name", JsonPrimitive(source.name))
                        put("url", JsonPrimitive(source.url))
                        put("enabled", JsonPrimitive(source.enabled))
                        addedAt?.let { put("addedAtMillis", JsonPrimitive(it)) }
                    })
                }),
                "tvReplaceSources" to JsonPrimitive(replace || oldDocument.tvReplaceSources),
                "tvImportedSourcesEpoch" to JsonPrimitive(SettingsPairingCrypto.randomID()),
            ))
            val validated = TVSettingsBackupPolicy.validate(root.toString().encodeToByteArray())
            val syncKey = current?.syncKeyBase64?.let(SettingsPairingCrypto::decodeBase64URL)
            check(persistSetup(validated, current?.revision ?: 0L, syncKey)) { "save_failed" }
            wipePairLocked()
            mutableState.value = TVSettingsPairingState.Saved(
                receipt = validated.receipt,
                syncEnabled = syncKey != null,
                message = "Stremio add-ons imported on this receiver",
            )
            publishSettingsApplied()
            TVAddonImportReceipt(
                added = nextKeys.count { it !in beforeKeys },
                updated = nextKeys.count { it in beforeKeys },
                removed = if (replace) beforeKeys.count { it !in nextKeys } else 0,
            )
        }

    /** Update the same encrypted fields that Pair & Sync imports from iOS. */
    suspend fun saveEinthusan(account: com.fourseveneightnine.tv.catalog.EinthusanAccount) = mutex.withLock {
        check(com.fourseveneightnine.tv.catalog.EinthusanProvider.available && staged == null) { "settings_unavailable" }
        require(account.email.length <= 512 && account.password.length <= 512)
        val stored = when (val loaded = read(store::load)) {
            is StoredTVSettingsState.Available -> loaded.document
            StoredTVSettingsState.Missing -> null
            is StoredTVSettingsState.Unavailable -> error("settings_unavailable")
        }
        val root = stored?.let { json.parseToJsonElement(it.rawJson).jsonObject }
            ?: JsonObject(mapOf("format" to JsonPrimitive("4789-settings"), "version" to JsonPrimitive(1)))
        val updated = JsonObject(root + mapOf(
            "einthusanEmail" to JsonPrimitive(account.email.trim()),
            "einthusanPassword" to JsonPrimitive(account.password),
            "einthusanEnabled" to JsonPrimitive(account.enabled),
            "einthusanLanguage" to JsonPrimitive(account.language.key),
        ))
        val validated = TVSettingsBackupPolicy.validate(updated.toString().encodeToByteArray())
        val syncKey = stored?.syncKeyBase64?.let(SettingsPairingCrypto::decodeBase64URL)
        val saved = if (stored == null) persistSetup(validated, 0, null)
            else persist { store.save(validated, stored.revision, syncKey) }
        check(saved) { "save_failed" }
        wipePairLocked()
        mutableState.value = TVSettingsPairingState.Saved(validated.receipt, syncKey != null, "Einthusan settings saved")
        mutableSettingsApplied.tryEmit(Unit)
    }

    /** The caller has fetched the manifest. Serialize the edit with imports and trusted phone writes. */
    suspend fun addDirectAddon(source: AddonSource) = mutex.withLock {
        check(staged == null) { "pairing_pending" }
        val key = requireNotNull(AddonEndpoint.normalize(source.url))
        val stored = when (val loaded = read(store::load)) {
            is StoredTVSettingsState.Available -> loaded.document
            StoredTVSettingsState.Missing -> null
            is StoredTVSettingsState.Unavailable -> error("settings_unavailable")
        }
        val root = stored?.let { json.parseToJsonElement(it.rawJson).jsonObject }
            ?: JsonObject(mapOf("format" to JsonPrimitive("4789-settings"), "version" to JsonPrimitive(1)))
        val document = SettingsDocument.parse(root.toString())
        val visible = document.tvImportedSources + document.visibleDirectAddons.map { it.source } +
            if (document.tvReplaceSources) emptyList() else document.addonSources
        require((visible.mapNotNull { AddonEndpoint.normalize(it.url) } + key).distinct().size <= MAX_LOCAL_ADDONS)
        val manual = (root["tvDirectAddons"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            .filterNot { AddonEndpoint.normalize((it["url"] as? JsonPrimitive)?.content.orEmpty()) == key }
        require(manual.size < MAX_LOCAL_ADDONS)
        val row = JsonObject(mapOf(
            "name" to JsonPrimitive(source.name.take(80)),
            "url" to JsonPrimitive(source.url),
            "installID" to JsonPrimitive(SettingsPairingCrypto.randomID()),
            "addedAtMillis" to JsonPrimitive(System.currentTimeMillis()),
        ))
        val validated = TVSettingsBackupPolicy.validate(JsonObject(root + ("tvDirectAddons" to
            JsonArray(manual + listOf(row)))).toString().encodeToByteArray())
        val syncKey = stored?.syncKeyBase64?.let(SettingsPairingCrypto::decodeBase64URL)
        val saved = if (stored == null) persistSetup(validated, 0, null)
            else persist { store.save(validated, stored.revision, syncKey) }
        check(saved) { "save_failed" }
        wipePairLocked()
        mutableState.value = TVSettingsPairingState.Saved(validated.receipt, syncKey != null, "Add-on saved on this TV")
        publishSettingsApplied()
    }

    /** Remove only the Stremio layer, including legacy imports. Restore the pre-import source selection. */
    suspend fun deleteStremioImports(): Int = mutex.withLock {
        check(staged == null) { "pairing_pending" }
        val stored = when (val loaded = read(store::load)) {
            is StoredTVSettingsState.Available -> loaded.document
            StoredTVSettingsState.Missing -> return@withLock 0
            is StoredTVSettingsState.Unavailable -> error("settings_unavailable")
        }
        val root = json.parseToJsonElement(stored.rawJson).jsonObject
        val count = SettingsDocument.parse(stored.rawJson).tvImportedSources.size
        if (count == 0 && "tvReplaceSources" !in root) return@withLock 0
        val validated = TVSettingsBackupPolicy.validate(
            JsonObject(root - setOf("tvImportedSources", "tvImportedSourcesEpoch", "tvReplaceSources"))
                .toString().encodeToByteArray())
        val syncKey = stored.syncKeyBase64?.let(SettingsPairingCrypto::decodeBase64URL)
        check(persist { store.save(validated, stored.revision, syncKey) }) { "save_failed" }
        mutableState.value = TVSettingsPairingState.Saved(validated.receipt, syncKey != null, "Stremio imports deleted")
        publishSettingsApplied()
        count
    }

    /** Restore merged mode without removing the add-ons imported directly on the TV. */
    suspend fun includePhoneAddons(): Boolean = mutex.withLock {
        if (staged != null) return@withLock false
        val stored = (read(store::load) as? StoredTVSettingsState.Available)?.document ?: return@withLock false
        val root = json.parseToJsonElement(stored.rawJson).jsonObject
        if ((root["tvReplaceSources"] as? JsonPrimitive)?.content != "true") return@withLock true
        val validated = TVSettingsBackupPolicy.validate(
            JsonObject(root - "tvReplaceSources").toString().encodeToByteArray(),
        )
        val syncKey = stored.syncKeyBase64?.let(SettingsPairingCrypto::decodeBase64URL)
        if (!persist { store.save(validated, stored.revision, syncKey) }) return@withLock false
        mutableState.value = TVSettingsPairingState.Saved(
            validated.receipt,
            syncEnabled = syncKey != null,
            message = "iPhone add-ons are included on this receiver",
        )
        publishSettingsApplied()
        true
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
            return@withLock response(404, PairStatusPayload("unknown", SettingsPairingCrypto.SYNC_VERSION))
        }
        response(
            200,
            PairStatusPayload(
                status = status.status,
                syncVersion = SettingsPairingCrypto.SYNC_VERSION,
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
            .getOrElse {
                if (it is SettingsSyncUpdateRequiredException) {
                    return@withLock response(409, SyncPayload("sync_update_required"))
                }
                return@withLock response(400, SyncPayload("invalid_envelope"))
            }
        val (plaintext, revision) = decrypted
        val catalogRevision = runCatching {
            read {
                maxOf(catalogCache?.syncRevision(receiverID, syncKey) ?: 0L,
                    letterboxdCache?.syncRevision(receiverID, syncKey) ?: 0L)
            }
        }.getOrElse { return@withLock response(500, SyncPayload("catalog_state_unavailable")) }
        val owner = SettingsPairingCrypto.encodeBase64URL(MessageDigest.getInstance("SHA-256").digest(syncKey))
        val now = uptimeMillis()
        val pendingLetterboxd = letterboxdTransfer?.takeIf { it.owner == owner && now - it.touchedAt < 120_000L }
        if (pendingLetterboxd == null) letterboxdTransfer = null
        if (revision <= maxOf(stored.revision, catalogRevision, pendingLetterboxd?.revision ?: 0L)) {
            return@withLock response(409, SyncPayload("stale_revision"))
        }

        val root = runCatching { json.parseToJsonElement(plaintext.decodeToString()).jsonObject }.getOrNull()
            ?: return@withLock response(400, SyncPayload("invalid_payload"))
        val action = (root["action"] as? JsonPrimitive)?.content
        if (action == "phone_sources") {
            val batch = runCatching { json.decodeFromJsonElement<TVPhoneSources>(root).validate() }
                .getOrElse { return@withLock response(400, SyncPayload("invalid_phone_sources")) }
            // Persist only the replay floor. Do not emit settingsApplied or touch player state.
            val validated = runCatching { TVSettingsBackupPolicy.validate(stored.rawJson.encodeToByteArray()) }
                .getOrElse { return@withLock response(500, SyncPayload("settings_unavailable")) }
            if (!persist { store.save(validated.copy(rawJson = stored.rawJson), revision, syncKey) }) {
                return@withLock response(500, SyncPayload("save_failed"))
            }
            val aliases = batch.identifiers()
            phoneSources.value = (phoneSources.value.filter { previous ->
                now - previous.receivedAt in 0 until PHONE_SOURCE_LIFETIME_MILLIS &&
                    !(previous.batch.type == batch.type && previous.batch.identifiers().any(aliases::contains))
            } + PhoneSources(batch, now)).takeLast(8)
            return@withLock response(200, SyncPayload("phone_sources_applied"))
        }
        if (action == "letterboxd_part") {
            return@withLock applyLetterboxdPart(root, revision, syncKey, owner, now)
        }
        if (action == "disconnect") {
            val cleared = persist(store::clearSync)
            if (!cleared) return@withLock response(500, SyncPayload("save_failed"))
            phoneSources.value = emptyList()
            letterboxdTransfer = null
            mutableState.value = read {
                savedState("Sync disconnected · imported settings remain on this receiver")
            }
            return@withLock response(200, SyncPayload("disconnected"))
        }

        if (action == "catalog") {
            val catalog = runCatching {
                TVTamilMVCatalogImportPolicy.decode(requireNotNull(root["catalog"]).toString().encodeToByteArray())
            }.getOrElse { return@withLock response(400, SyncPayload("invalid_catalog")) }
            val cache = catalogCache ?: return@withLock response(409, SyncPayload("catalog_unavailable"))
            // One commit owns both the catalog and this action's replay floor. The settings
            // vault keeps its own action floor; every sync checks the maximum of both stores.
            val copied = persist {
                cache.storeSynced(catalog, receiverID, revision, syncKey)
                true
            }
            if (!copied) return@withLock response(500, SyncPayload("catalog_save_failed"))
            mutableCatalogApplied.tryEmit(Unit)
            return@withLock response(200, SyncPayload("catalog_applied"))
        }

        val validated = runCatching { retainTVImport(TVSettingsBackupPolicy.validate(plaintext), stored) }
            .getOrElse { return@withLock response(400, SyncPayload("invalid_settings")) }
        val saved = persist { store.save(validated, revision, syncKey) }
        if (!saved) return@withLock response(500, SyncPayload("save_failed"))
        mutableState.value = TVSettingsPairingState.Saved(
            validated.receipt,
            syncEnabled = true,
            message = "Settings synced from the trusted phone",
        )
        publishSettingsApplied()
        response(200, SyncPayload("applied"))
    }

    private suspend fun applyLetterboxdPart(
        root: JsonObject,
        revision: Long,
        syncKey: ByteArray,
        owner: String,
        now: Long,
    ): SettingsEndpointResponse {
        val cache = letterboxdCache ?: return response(409, SyncPayload("catalog_unavailable"))
        val part = runCatching {
            json.decodeFromJsonElement<LetterboxdPart>(root).also {
                require(it.transferID.length == 36 && UUID.fromString(it.transferID).toString().equals(it.transferID, true))
                require(it.count in 1..TVTamilMVCatalogImportPolicy.MAX_LETTERBOXD_PARTS && it.index in 0 until it.count)
                require(it.data.length <= ((TVTamilMVCatalogImportPolicy.LETTERBOXD_PART_BYTES + 2) / 3) * 4)
            }
        }.getOrElse { return response(400, SyncPayload("invalid_letterboxd_part")) }
        val bytes = runCatching { Base64.getDecoder().decode(part.data) }.getOrNull()
        if (bytes == null || bytes.size !in 1..TVTamilMVCatalogImportPolicy.LETTERBOXD_PART_BYTES) {
            return response(400, SyncPayload("invalid_letterboxd_part"))
        }
        val transfer = if (part.index == 0) {
            LetterboxdTransfer(part.transferID, owner, part.count).also { letterboxdTransfer = it }
        } else {
            letterboxdTransfer?.takeIf { it.id == part.transferID && it.count == part.count && it.nextIndex == part.index }
                ?: return response(409, SyncPayload("letterboxd_restart"))
        }
        transfer.body.write(bytes)
        transfer.nextIndex++
        transfer.revision = revision
        transfer.touchedAt = now
        if (transfer.nextIndex < transfer.count) return response(200, SyncPayload("letterboxd_part_received"))

        letterboxdTransfer = null
        val snapshot = runCatching {
            read { TVTamilMVCatalogImportPolicy.decodeLetterboxdMirror(transfer.body.toByteArray()) }
        }.getOrElse { return response(400, SyncPayload("invalid_letterboxd_mirror")) }
        val saved = persist {
            cache.storeSynced(snapshot, receiverID, revision, syncKey)
            true
        }
        if (!saved) return response(500, SyncPayload("catalog_save_failed"))
        mutableLetterboxdApplied.value++
        return response(200, SyncPayload("letterboxd_applied"))
    }

    private fun publishSettingsApplied() {
        mutableHasSavedSettings.value = true
        mutableSettingsApplied.tryEmit(Unit)
    }

    private fun initialState(): TVSettingsPairingState {
        val loaded = store.load()
        mutableHasSavedSettings.value = loaded is StoredTVSettingsState.Available
        return when (loaded) {
            StoredTVSettingsState.Missing -> TVSettingsPairingState.Idle
            is StoredTVSettingsState.Unavailable -> TVSettingsPairingState.Error(
                "Encrypted settings are unavailable (${loaded.reason}).",
            )
            is StoredTVSettingsState.Available ->
                savedState("Settings are configured on this receiver", loaded.document)
        }
    }

    private fun retainTVImport(incoming: ValidatedTVSettings, current: StoredTVSettings?): ValidatedTVSettings {
        if (current == null) return incoming
        val old = json.parseToJsonElement(current.rawJson).jsonObject
        val retained = old.filterKeys { it == "tvImportedSources" || it == "tvReplaceSources" || it == "tvImportedSourcesEpoch" || it == "tvDirectAddons" }
        if (retained.isEmpty()) return incoming
        val proposed = json.parseToJsonElement(incoming.rawJson).jsonObject
        return TVSettingsBackupPolicy.validate(JsonObject(proposed + retained).toString().encodeToByteArray())
    }

    /**
     * @param document the already-loaded settings, when the caller has them.
     *
     * Every [store.load] is a SharedPreferences read, an Android Keystore key lookup and an AES-GCM
     * decrypt of the whole settings export. On a cheap TV box the Keystore lookup alone is the slow
     * part, and [initialState] was paying for it twice for one state emission: once to decide the
     * document existed, then again in here to read the same bytes back. Callers that already hold
     * the document now hand it over.
     */
    private fun savedState(
        message: String,
        document: StoredTVSettings? = null,
    ): TVSettingsPairingState {
        val loaded = document
            ?: (store.load() as? StoredTVSettingsState.Available)?.document
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

    /** Both first-pair approval and direct add-on import can create an otherwise missing setup. */
    private suspend fun persistSetup(settings: ValidatedTVSettings, revision: Long, syncKey: ByteArray?): Boolean {
        val firstSetup = read { store.load() == StoredTVSettingsState.Missing }
        if (firstSetup) {
            val privateStatePurged = withContext(NonCancellable) {
                runCatching { purgePrivateSetup() }.isSuccess
            }
            if (!privateStatePurged) return false
        }
        val saved = persist {
            // A failed earlier reset must not let either setup path inherit the previous owner.
            if (store.load() == StoredTVSettingsState.Missing) {
                catalogCache?.clear(discardSyncState = true)
                letterboxdCache?.clear(discardSyncState = true)
            }
            store.save(settings, revision, syncKey)
        }
        if (saved) phoneSources.value = emptyList()
        return saved
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
        const val PHONE_SOURCE_LIFETIME_MILLIS = 5 * 60_000L
        const val MAX_LOCAL_ADDONS = 256
        const val INVITATION_LIFETIME_MILLIS = 5 * 60_000L
        const val STATUS_LIFETIME_MILLIS = 2 * 60_000L
    }
}
