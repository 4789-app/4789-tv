package com.fourseveneightnine.tv.client.iptv

import android.app.Application
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics

/** Local TV IPTV library; the paired phone can go away without losing login, guide or favorites. */
internal class IptvRepository(
    app: Application,
    http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val appContext = app.applicationContext
    private val standalone = app.getSharedPreferences("iptv-shell", Application.MODE_PRIVATE)
    private val vault = IptvVault(app)
    private val groupRanker = IptvGroupRanker(app, http)
    // Large Xtream series/VOD catalogs can take over a minute; the shared 20-second API deadline
    // silently discarded them while the encrypted on-TV catalog still showed older rows.
    private val fetcher = IptvFetcher(http.newBuilder()
        .callTimeout(180, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build())
    private val guide = IptvGuide(http)
    private val mutex = Mutex()
    private val loaded = CompletableDeferred<Unit>()
    @Volatile private var vaultAvailable = true
    private val mutableState = MutableStateFlow(IptvState())
    val state: StateFlow<IptvState> = mutableState.asStateFlow()
    private var refreshGeneration = 0L
    private val catalogLoaded = CompletableDeferred<Unit>()
    private var completedRefreshGeneration = 0L
    @Volatile private var startupCache: Pair<List<IptvSource>, IptvCatalog>? = null
    private val seriesCache = LinkedHashMap<Pair<IptvSource, String>, Pair<Long, IptvSeriesDetails>>()

    suspend fun awaitLoaded() { loaded.await() }

    init {
        scope.launch(Dispatchers.Default) {
            state.collect { current -> current.catalog.programsByChannel }
        }
        scope.launch(Dispatchers.IO) {
            val startedAt = SystemClock.elapsedRealtime()
            val accountsResult = vault.loadAccounts()
            vaultAvailable = accountsResult.isSuccess
            val accounts = accountsResult.getOrDefault(IptvAccounts())
            val storageErrors = if (vaultAvailable) emptyMap() else
                mapOf("storage" to "Saved IPTV accounts cannot be opened")
            mutableState.value = IptvState(accounts = accounts, loading = true,
                errors = storageErrors)
            val preview = vault.loadPreview()
            if (preview.channels.isNotEmpty()) {
                val previewAccounts = if (vaultAvailable) groupRanker.assign(accounts, preview.channels,
                    allowRemote = false) else accounts
                mutableState.value = IptvState(previewAccounts, preview, loading = false,
                    errors = storageErrors)
                loaded.complete(Unit)
                ReceiverDiagnostics.record("iptv.preview.loaded",
                    "channels=${preview.channels.size} ms=${SystemClock.elapsedRealtime() - startedAt}")
            }
            val catalog = vault.loadCatalog()
            startupCache = accounts.sources to catalog
            catalogLoaded.complete(Unit)
            val rankedAccounts = mutex.withLock {
                val current = mutableState.value
                if (refreshGeneration > 0L) {
                    if (!current.refreshing && completedRefreshGeneration == 0L)
                        mutableState.value = restoredStartup(current)
                    current.accounts
                } else {
                    val ranked = if (vaultAvailable) groupRanker.assign(current.accounts, catalog.channels,
                        allowRemote = false) else current.accounts
                    if (vaultAvailable && ranked != current.accounts) vault.saveAccounts(ranked)
                    val mergedCatalog = if (current.catalog.vod.isEmpty()) catalog else catalog.copy(
                        vod = (current.catalog.vod + catalog.vod).distinctBy(IptvVod::id))
                    mutableState.value = current.copy(accounts = ranked, catalog = mergedCatalog,
                        loading = false, catalogLoading = false)
                    ranked
                }
            }
            if (!loaded.isCompleted) loaded.complete(Unit)
            if (vaultAvailable && catalog.channels.isNotEmpty() &&
                (preview.channels.isEmpty() || preview.vodCategories.isEmpty() && catalog.vod.isNotEmpty())) {
                mutex.withLock {
                    if (refreshGeneration == 0L) vault.savePreview(catalog)
                }
            }
            ReceiverDiagnostics.record("iptv.cache.loaded",
                "channels=${catalog.channels.size} vod=${catalog.vod.size} ms=${SystemClock.elapsedRealtime() - startedAt}")
            Log.i("4789IptvPerf", "repository readyMs=${SystemClock.elapsedRealtime() - startedAt} " +
                "channels=${catalog.channels.size} vod=${catalog.vod.size}")
            if (vaultAvailable) {
                scope.launch { upgradeCachedDecisions() }
            }
            if (vaultAvailable) rankedAccounts.recordings.filter {
                it.status == IptvRecordingStatus.SCHEDULED && it.endsAtMillis > System.currentTimeMillis()
            }.forEach { IptvRecordingScheduler.schedule(appContext, it) }
            if (vaultAvailable && !mutableState.value.refreshing && rankedAccounts.sources.any(IptvSource::enabled) &&
                System.currentTimeMillis() - catalog.refreshedAtMillis > 6L * 60 * 60 * 1000) {
                scope.launch { refresh() }
            }
        }
    }

    private fun restoredStartup(latest: IptvState): IptvState {
        val (sources, cached) = startupCache ?: return latest
        if (latest.sources != sources) return latest
        val live = latest.catalog
        return latest.copy(loading = false, catalogLoading = false, catalog = cached.copy(
            channels = (live.channels + cached.channels).distinctBy(IptvChannel::id),
            vod = (live.vod + cached.vod).distinctBy(IptvVod::id),
            episodes = (live.episodes + cached.episodes).distinctBy(IptvVod::id),
            programs = (live.programs + cached.programs).distinctBy { it.channelKey to it.startMillis },
            vodCategories = (live.vodCategories + cached.vodCategories).distinctBy { Triple(it.sourceId, it.kind, it.name) },
        ))
    }

    suspend fun addLink(name: String, link: String): Boolean {
        val trimmed = link.trim()
        if (!IptvM3u.httpUrl(trimmed)) return false
        val uri = URI(trimmed)
        val query = uri.rawQuery.orEmpty().split('&').mapNotNull {
            val key = it.substringBefore('=', "").takeIf(String::isNotBlank) ?: return@mapNotNull null
            key to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }.toMap()
        val user = query["username"]
        val pass = query["password"]
        return if (!user.isNullOrBlank() && !pass.isNullOrBlank()) {
            addXtream(name, "${uri.scheme}://${uri.authority}", user, pass)
        } else {
            addSource(IptvSource(
                id = "m3u:${UUID.randomUUID()}", name = name.trim().ifBlank { uri.host ?: "Playlist" },
                kind = IptvSourceKind.M3U, url = trimmed,
            ))
        }
    }

    suspend fun addXtream(name: String, host: String, username: String, password: String): Boolean {
        if (username.isBlank() || password.isBlank()) return false
        val normalized = normalizeHost(host) ?: return false
        return addSource(IptvSource(
            id = "xtream:${UUID.randomUUID()}", name = name.trim().ifBlank { "Xtream" },
            kind = IptvSourceKind.XTREAM, host = normalized,
            username = username.trim(), password = password,
        ))
    }

    suspend fun addStalker(name: String, host: String, mac: String): Boolean {
        val normalized = normalizeHost(host) ?: return false
        if (!mac.trim().matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}"))) return false
        return addSource(IptvSource(
            id = "stalker:${UUID.randomUUID()}", name = name.trim().ifBlank { "Portal" },
            kind = IptvSourceKind.STALKER, host = normalized, mac = mac.trim().uppercase(),
        ))
    }

    private suspend fun addSource(source: IptvSource): Boolean {
        loaded.await()
        if (!vaultAvailable) return false
        val saved = mutex.withLock {
            val current = mutableState.value
            val accounts = current.accounts.copy(sources = current.sources + source)
            if (!withContext(Dispatchers.IO) { vault.saveAccounts(accounts) }) false else {
                standalone.edit().putBoolean("open-live-tv", true).apply()
                refreshGeneration++
                mutableState.value = current.copy(accounts = accounts)
                true
            }
        }
        if (saved) scope.launch { refresh() }
        return saved
    }

    suspend fun removeSource(id: String) {
        loaded.await()
        if (!vaultAvailable) return
        mutex.withLock {
            val current = mutableState.value
            val accounts = current.accounts.copy(
                sources = current.sources.filterNot { it.id == id },
                favoriteIds = current.accounts.favoriteIds.filterNot { it.startsWith("$id:") }.toSet(),
                recentIds = current.accounts.recentIds.filterNot { it.startsWith("$id:") },
            )
            val catalog = current.catalog.copy(
                channels = current.catalog.channels.filterNot { it.sourceId == id },
                programs = current.catalog.programs.filterNot { it.channelKey.startsWith("$id:") },
                vod = current.catalog.vod.filterNot { it.sourceId == id },
                episodes = current.catalog.episodes.filterNot { it.sourceId == id },
            )
            if (withContext(Dispatchers.IO) { vault.saveAccounts(accounts) && vault.saveCatalog(catalog) }) {
                if (accounts.sources.isEmpty()) standalone.edit().remove("open-live-tv").apply()
                refreshGeneration++
                mutableState.value = current.copy(accounts = accounts, catalog = catalog, errors = current.errors - id)
            }
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        loaded.await()
        if (!vaultAvailable) return
        val saved = mutex.withLock {
            val current = mutableState.value
            val accounts = current.accounts.copy(sources = current.sources.map {
                if (it.id == id) it.copy(enabled = enabled) else it
            })
            if (!withContext(Dispatchers.IO) { vault.saveAccounts(accounts) }) false else {
                refreshGeneration++
                mutableState.value = current.copy(accounts = accounts)
                true
            }
        }
        if (saved && enabled) scope.launch { refresh() }
    }

    suspend fun updateSource(id: String, name: String, url: String, host: String,
                             username: String, newPassword: String, mac: String,
                             epgUrl: String): Boolean {
        loaded.await()
        if (!vaultAvailable) return false
        val current = state.value.sources.firstOrNull { it.id == id } ?: return false
        val guide = epgUrl.trim().takeIf(String::isNotEmpty)
        if (guide != null && !IptvM3u.httpUrl(guide)) return false
        val updated = when (current.kind) {
            IptvSourceKind.M3U -> {
                val link = url.trim().takeIf(IptvM3u::httpUrl) ?: return false
                current.copy(name = name.trim().ifBlank { current.name }, url = link, epgUrl = guide)
            }
            IptvSourceKind.XTREAM -> {
                val normalized = normalizeHost(host) ?: return false
                val user = username.trim().takeIf(String::isNotEmpty) ?: return false
                val pass = newPassword.ifBlank { current.password.orEmpty() }.takeIf(String::isNotEmpty) ?: return false
                current.copy(name = name.trim().ifBlank { current.name }, host = normalized,
                    username = user, password = pass, epgUrl = guide)
            }
            IptvSourceKind.STALKER -> {
                val normalized = normalizeHost(host) ?: return false
                val value = mac.trim().takeIf { it.matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")) } ?: return false
                current.copy(name = name.trim().ifBlank { current.name }, host = normalized,
                    mac = value.uppercase(), epgUrl = guide)
            }
        }
        val saved = mutex.withLock {
            val stateNow = mutableState.value
            val accounts = stateNow.accounts.copy(sources = stateNow.sources.map { if (it.id == id) updated else it },
                vodDecisions = stateNow.accounts.vodDecisions.filterKeys { !it.startsWith("$id|") })
            if (withContext(Dispatchers.IO) { vault.saveAccounts(accounts) }) {
                refreshGeneration++
                mutableState.value = stateNow.copy(accounts = accounts)
                true
            } else false
        }
        if (saved) scope.launch { refresh() }
        return saved
    }

    suspend fun setFavorite(id: String, favorite: Boolean) = editAccounts { current ->
        current.copy(favoriteIds = if (favorite) current.favoriteIds + id else current.favoriteIds - id)
    }

    suspend fun toggleFavorite(id: String): Boolean? {
        var added = false
        val saved = editAccounts { current ->
            added = id !in current.favoriteIds
            current.copy(favoriteIds = if (added) current.favoriteIds + id else current.favoriteIds - id)
        }
        return if (saved) added else null
    }

    suspend fun createFavoriteList(name: String): Boolean {
        val label = name.trim().take(60)
        if (label.isBlank()) return false
        return editAccounts { current -> current.copy(favoriteLists =
            if (label in current.favoriteLists) current.favoriteLists else current.favoriteLists + (label to emptySet())) }
    }

    suspend fun removeFavoriteList(name: String) = editAccounts { current ->
        current.copy(favoriteLists = current.favoriteLists - name)
    }

    suspend fun toggleListFavorite(name: String, channelId: String): Boolean? {
        var added = false
        var found = false
        val saved = editAccounts { current ->
            val ids = current.favoriteLists[name]
            if (ids == null) current else {
                found = true
                added = channelId !in ids
                current.copy(favoriteLists = current.favoriteLists +
                    (name to if (added) ids + channelId else ids - channelId))
            }
        }
        return if (saved && found) added else null
    }

    suspend fun markPlayed(id: String) = editAccounts { current ->
        current.copy(recentIds = (listOf(id) + current.recentIds.filterNot { it == id }).take(30))
    }

    suspend fun setGroupHidden(group: String, hidden: Boolean) = editAccounts { current ->
        current.copy(hiddenGroups = if (hidden) current.hiddenGroups + group else current.hiddenGroups - group)
    }

    suspend fun moveGroup(group: String, direction: Int): Boolean = editAccounts { current ->
        val order = current.groupOrder.toMutableList()
        val index = order.indexOf(group)
        val destination = index + direction.coerceIn(-1, 1)
        if (index >= 0 && destination in order.indices) {
            val other = order[destination]
            order[destination] = group
            order[index] = other
        }
        current.copy(groupOrder = order, manualGroupOrder = true)
    }

    suspend fun resetGroupOrder(): Boolean {
        loaded.await()
        val current = state.value
        val automatic = groupRanker.assign(current.accounts.copy(manualGroupOrder = false,
            groupOrder = emptyList()), current.catalog.channels)
        return editAccounts { latest ->
            val manuallyChanged = latest.groupOrder != current.accounts.groupOrder ||
                latest.manualGroupOrder != current.accounts.manualGroupOrder
            mergeIptvGroupRanking(if (manuallyChanged) latest else latest.copy(manualGroupOrder = false), automatic)
        }
    }

    suspend fun setGroupLocked(group: String, locked: Boolean) = editAccounts { current ->
        current.copy(lockedGroups = if (locked) current.lockedGroups + group else current.lockedGroups - group)
    }

    suspend fun setDecisionApiKey(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.length < 16) return false
        return editAccounts { it.copy(decisionApiKey = trimmed) }
    }

    private suspend fun upgradeCachedDecisions() {
        val current = state.value
        val live = groupRanker.assign(current.accounts, current.catalog.channels)
        val decided = live.copy(vodDecisions = groupRanker.assignVod(live, current.catalog))
        if (decided != current.accounts) editAccounts { latest ->
            if (latest.sources != current.sources) return@editAccounts latest
            latest.copy(groupDecisions = latest.groupDecisions + decided.groupDecisions,
                groupOrder = if (latest.manualGroupOrder) latest.groupOrder else decided.groupOrder,
                decisionPolicyVersion = decided.decisionPolicyVersion,
                vodDecisions = latest.vodDecisions + decided.vodDecisions)
        }
    }

    suspend fun exportBackup(passphrase: CharArray): ByteArray {
        loaded.await()
        if (!vaultAvailable) error("IPTV storage unavailable")
        return withContext(Dispatchers.IO) { IptvBackup.export(state.value.accounts, passphrase) }
    }

    suspend fun importBackup(bytes: ByteArray, passphrase: CharArray): Boolean {
        loaded.await()
        if (!vaultAvailable) return false
        val imported = runCatching { withContext(Dispatchers.IO) { IptvBackup.import(bytes, passphrase) } }
            .getOrNull() ?: return false
        if (imported.sources.size > 50 || imported.sources.any { source ->
            when (source.kind) {
                IptvSourceKind.M3U -> !IptvM3u.httpUrl(source.url.orEmpty())
                IptvSourceKind.XTREAM -> normalizeHost(source.host.orEmpty()) == null ||
                    source.username.isNullOrBlank() || source.password.isNullOrBlank()
                IptvSourceKind.STALKER -> normalizeHost(source.host.orEmpty()) == null ||
                    !source.mac.orEmpty().matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}"))
            }
        }) return false
        val saved = mutex.withLock {
            val current = mutableState.value
            val ids = imported.sources.map(IptvSource::id).toSet()
            val catalog = current.catalog.copy(
                channels = current.catalog.channels.filter { it.sourceId in ids },
                vod = current.catalog.vod.filter { it.sourceId in ids },
                episodes = current.catalog.episodes.filter { it.sourceId in ids },
                programs = current.catalog.programs.filter { program ->
                    ids.any { program.channelKey.startsWith("$it:") }
                },
            )
            if (withContext(Dispatchers.IO) { vault.saveAccounts(imported) && vault.saveCatalog(catalog) }) {
                refreshGeneration++
                mutableState.value = current.copy(accounts = imported, catalog = catalog)
                true
            } else false
        }
        if (saved) scope.launch { refresh() }
        return saved
    }

    suspend fun setParentalPin(pin: String): Boolean {
        if (pin.length !in 4..8 || !pin.all(Char::isDigit)) return false
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val saltHex = salt.joinToString("") { "%02x".format(it) }
        return editAccounts { it.copy(parentalPinHash = "$saltHex:${hashPin(saltHex, pin)}") }
    }

    suspend fun scheduleRecording(channel: IptvChannel, program: IptvProgram): Boolean {
        loaded.await()
        if (!vaultAvailable || program.endMillis <= System.currentTimeMillis() ||
            state.value.accounts.recordings.any { it.channelId == channel.id && it.startsAtMillis == program.startMillis }) return false
        val recording = IptvRecording(UUID.randomUUID().toString(), channel.id, program.title,
            maxOf(System.currentTimeMillis(), program.startMillis), program.endMillis)
        val saved = mutex.withLock {
            val current = mutableState.value
            val accounts = current.accounts.copy(recordings = current.accounts.recordings + recording)
            if (withContext(Dispatchers.IO) { vault.saveAccounts(accounts) }) {
                mutableState.value = current.copy(accounts = accounts)
                true
            } else false
        }
        if (saved) IptvRecordingScheduler.schedule(appContext, recording)
        return saved
    }

    suspend fun recording(id: String): IptvRecording? {
        loaded.await()
        return state.value.accounts.recordings.firstOrNull { it.id == id }
    }

    fun recordingFile(item: IptvRecording): File? {
        val name = item.fileName?.takeIf { it.matches(Regex("[0-9a-f-]{36}\\.(ts|mp4)")) } ?: return null
        val directory = appContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: return null
        return File(directory, name).takeIf(File::isFile)
    }

    suspend fun updateRecording(id: String, status: IptvRecordingStatus, fileName: String? = null,
                                error: String? = null) = editAccounts { current ->
        current.copy(recordings = current.recordings.map { item ->
            if (item.id == id) item.copy(status = status, fileName = fileName ?: item.fileName, error = error) else item
        })
    }

    suspend fun cancelRecording(id: String): Boolean {
        val item = recording(id) ?: return false
        if (item.status !in setOf(IptvRecordingStatus.SCHEDULED, IptvRecordingStatus.RECORDING)) return false
        val saved = updateRecording(id, IptvRecordingStatus.FAILED, error = "Cancelled")
        if (saved) IptvRecordingScheduler.cancel(appContext, id)
        return saved
    }

    suspend fun deleteRecording(id: String): Boolean {
        val item = recording(id) ?: return false
        IptvRecordingScheduler.cancel(appContext, id)
        val saved = editAccounts { current ->
            current.copy(recordings = current.recordings.filterNot { it.id == id })
        }
        if (saved) recordingFile(item)?.delete()
        return saved
    }

    fun checkParentalPin(pin: String): Boolean {
        val saved = state.value.accounts.parentalPinHash?.split(':') ?: return true
        if (saved.size != 2) return false
        return MessageDigest.isEqual(saved[1].encodeToByteArray(), hashPin(saved[0], pin).encodeToByteArray())
    }

    private fun hashPin(salt: String, pin: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$salt:$pin".encodeToByteArray()).joinToString("") { "%02x".format(it) }

    private suspend fun editAccounts(block: (IptvAccounts) -> IptvAccounts): Boolean {
        loaded.await()
        if (!vaultAvailable) return false
        return mutex.withLock {
            val current = mutableState.value
            val accounts = block(current.accounts)
            if (withContext(Dispatchers.IO) { vault.saveAccounts(accounts) }) {
                mutableState.value = current.copy(accounts = accounts)
                true
            } else false
        }
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        loaded.await()
        if (!vaultAvailable) return@withContext
        // A failed refresh must not overwrite the full saved library with the fast startup preview.
        catalogLoaded.await()
        val (generation, sources, prior) = mutex.withLock {
            refreshGeneration++
            val current = mutableState.value
            mutableState.value = current.copy(refreshing = true)
            Triple(refreshGeneration, current.sources.filter(IptvSource::enabled),
                if (completedRefreshGeneration == 0L) restoredStartup(current).catalog else current.catalog)
        }
        try {
            val slots = Semaphore(3)
            val outcomes = coroutineScope {
                sources.map { source ->
                    async(Dispatchers.IO) {
                        slots.withPermit {
                            try {
                                val result = fetcher.fetch(source)
                                val guideItems = result.epgUrl?.let { runCatching { guide.load(it, result.channels) }.getOrNull() }.orEmpty()
                                SourceOutcome(source.id, result.channels, result.vod, result.episodes,
                                (result.programs + guideItems).distinctBy { it.channelKey to it.startMillis },
                                    null, result.maxConnections, result.failedVodKinds)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                val safeMessage = error.message?.takeIf { message ->
                                    message.startsWith("Provider HTTP") || message.startsWith("Portal HTTP") ||
                                        message.startsWith("IPTV login was rejected") ||
                                        message.startsWith("The portal did not accept") ||
                                        message.startsWith("Provider response is too large")
                                } ?: "Source did not answer (${error.javaClass.simpleName})"
                                SourceOutcome(source.id, emptyList(), emptyList(), emptyList(), emptyList(),
                                    safeMessage.take(120))
                            }
                        }
                    }
                }.awaitAll()
            }
            val successful = outcomes.filter { it.error == null }
            val refreshed = successful.map(SourceOutcome::id).toSet()
            val failedVodKinds = successful.associate { it.id to it.failedVodKinds }
            val rankedChannels = prior.channels.filterNot { it.sourceId in refreshed } + successful.flatMap(SourceOutcome::channels)
            // Jev can take 45 seconds per request. Do not hold Favorite/PIN/recents edits behind it.
            val ranking = groupRanker.assign(state.value.accounts, rankedChannels, allowRemote = false)
            mutex.withLock {
                if (generation != refreshGeneration) return@withContext
                val catalog = IptvCatalog(
                    channels = rankedChannels,
                    vod = prior.vod.filterNot { it.sourceId in refreshed &&
                        it.kind !in failedVodKinds[it.sourceId].orEmpty() } + successful.flatMap(SourceOutcome::vod),
                    episodes = prior.episodes.filterNot { it.sourceId in refreshed } +
                        successful.flatMap(SourceOutcome::episodes),
                    programs = prior.programs.filterNot { program -> refreshed.any { program.channelKey.startsWith("$it:") } } +
                        successful.flatMap(SourceOutcome::programs),
                    refreshedAtMillis = System.currentTimeMillis(),
                )
                val current = mutableState.value
                val errors = outcomes.mapNotNull { outcome -> outcome.error?.let { outcome.id to it } }.toMap() +
                    successful.filter { it.failedVodKinds.isNotEmpty() }.associate {
                        it.id to "Some movie or series listings are unavailable. Retry Refresh."
                    }
                val capabilities = successful.associate { it.id to it.maxConnections }
                val updatedSources = current.sources.map { source ->
                    if (source.id in capabilities) source.copy(maxConnections = capabilities[source.id]) else source
                }
                val rankedAccounts = mergeIptvGroupRanking(current.accounts.copy(sources = updatedSources), ranking)
                if (withContext(Dispatchers.IO) { vault.saveCatalog(catalog) &&
                    (rankedAccounts == current.accounts || vault.saveAccounts(rankedAccounts)) }) {
                    completedRefreshGeneration = generation
                    mutableState.value = current.copy(accounts = rankedAccounts, catalog = catalog,
                        refreshing = false, catalogLoading = false, errors = errors)
                    scope.launch { upgradeCachedDecisions() }
                } else {
                    mutableState.value = current.copy(refreshing = false, errors = errors + ("storage" to "Couldn't save IPTV cache"))
                }
            }
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    val latest = mutableState.value
                    if (generation == refreshGeneration && latest.refreshing)
                        mutableState.value = (if (completedRefreshGeneration == 0L) restoredStartup(latest) else latest)
                            .copy(refreshing = false, catalogLoading = false)
                }
            }
        }
    }

    suspend fun playUrl(channel: IptvChannel): String {
        val source = state.value.sources.firstOrNull { it.id == channel.sourceId } ?: error("Source removed")
        return if (source.kind == IptvSourceKind.STALKER) {
            withTimeout(22_000) { fetcher.resolveStalker(source, channel) }
        } else {
            fetcher.liveUrl(source, channel) ?: error("No playable link")
        }
    }

    suspend fun vodUrl(item: IptvVod): String? {
        val source = state.value.sources.firstOrNull { it.id == item.sourceId } ?: return null
        return if (source.kind == IptvSourceKind.STALKER) fetcher.resolveStalkerVod(source, item)
            else fetcher.streamUrl(source, item)
    }

    suspend fun renewVod(item: IptvVod): Pair<IptvVod, String>? = withContext(Dispatchers.IO) {
        val source = state.value.sources.firstOrNull { it.id == item.sourceId && it.enabled } ?: return@withContext null
        val rows = when (source.kind) {
            IptvSourceKind.M3U -> fetcher.fetch(source).let { it.vod + it.episodes }
            IptvSourceKind.XTREAM -> if (item.kind == "episode") {
                val series = state.value.catalog.vod.firstOrNull { it.sourceId == item.sourceId && it.kind == "series" &&
                    (it.id == item.seriesId || it.title == item.category) } ?: return@withContext null
                fetcher.seriesDetails(source, series).episodes
            } else fetcher.vodCategory(source, item.kind, item.category)
            IptvSourceKind.STALKER -> return@withContext item to fetcher.resolveStalkerVod(source, item)
        }
        val renewed = rows.firstOrNull { it.id == item.id || item.streamId != null && it.streamId == item.streamId }
            ?: rows.firstOrNull { it.title == item.title && it.category == item.category } ?: return@withContext null
        val url = fetcher.streamUrl(source, renewed) ?: return@withContext null
        renewed to url
    }

    suspend fun loadVodCategory(kind: String, name: String): Boolean = withContext(Dispatchers.IO) {
        loaded.await()
        val startedAt = SystemClock.elapsedRealtime()
        val current = state.value
        if (current.catalog.vod.any { it.kind == kind && it.category == name }) return@withContext true
        val sourceIds = current.catalog.vodCategories.filter { it.kind == kind && it.name == name }
            .map(IptvVodCategory::sourceId).toSet()
        val fetched = current.sources.filter { it.enabled && it.id in sourceIds }.flatMap { source ->
            try { fetcher.vodCategory(source, kind, name) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { emptyList() }
        }
        if (fetched.isEmpty()) {
            ReceiverDiagnostics.record("iptv.category.unavailable", "kind=$kind ms=${SystemClock.elapsedRealtime() - startedAt}")
            return@withContext false
        }
        mutex.withLock {
            val latest = mutableState.value
            val unchanged = current.sources.filter { original -> original.enabled && original in latest.sources }.map(IptvSource::id).toSet()
            val accepted = fetched.filter { it.sourceId in unchanged }
            if (accepted.isEmpty()) return@withContext false
            val merged = latest.catalog.copy(vod = (accepted + latest.catalog.vod).distinctBy(IptvVod::id))
            mutableState.value = latest.copy(catalog = merged)
            if (!vault.saveCatalog(merged)) {
                mutableState.value = mutableState.value.copy(errors = mutableState.value.errors +
                    ("storage" to "Couldn't save this category on the TV"))
            }
        }
        ReceiverDiagnostics.record("iptv.category.loaded",
            "kind=$kind count=${fetched.size} ms=${SystemClock.elapsedRealtime() - startedAt}")
        return@withContext true
    }

    suspend fun seriesDetails(item: IptvVod): IptvSeriesDetails {
        val source = state.value.sources.firstOrNull { it.id == item.sourceId }
            ?: return IptvSeriesDetails(item, emptyList())
        if (source.kind == IptvSourceKind.M3U) return IptvSeriesDetails(item,
            state.value.catalog.episodes.filter { it.category == item.id })
        val key = source to item.id
        val now = SystemClock.elapsedRealtime()
        synchronized(seriesCache) {
            seriesCache[key]?.takeIf { now - it.first < 600_000L }?.let { return it.second }
        }
        val details = fetcher.seriesDetails(source, item)
        synchronized(seriesCache) {
            seriesCache[key] = SystemClock.elapsedRealtime() to details
            while (seriesCache.size > 24) seriesCache.remove(seriesCache.keys.first())
        }
        return details
    }

    fun catchupUrl(channel: IptvChannel, program: IptvProgram): String? {
        val now = System.currentTimeMillis()
        if (program.startMillis > now || now - program.startMillis > channel.catchupDays * 86_400_000L) return null
        val source = state.value.sources.firstOrNull { it.id == channel.sourceId } ?: return null
        return fetcher.catchupUrl(source, channel, program)
    }

    fun now(channelId: String, at: Long = System.currentTimeMillis()): IptvProgram? =
        state.value.catalog.now(channelId, at)

    fun next(channelId: String, at: Long = System.currentTimeMillis()): IptvProgram? =
        state.value.catalog.next(channelId, at)

    private data class SourceOutcome(
        val id: String,
        val channels: List<IptvChannel>,
        val vod: List<IptvVod>,
        val episodes: List<IptvVod>,
        val programs: List<IptvProgram>,
        val error: String?,
        val maxConnections: Int? = null,
        val failedVodKinds: Set<String> = emptySet(),
    )

    private fun normalizeHost(raw: String): String? {
        val normalized = raw.trim().let { if ("://" in it) it else "http://$it" }
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
        return "${uri.scheme}://${uri.authority}"
    }
}
