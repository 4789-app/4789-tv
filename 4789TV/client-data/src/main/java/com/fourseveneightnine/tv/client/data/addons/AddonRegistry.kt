package com.fourseveneightnine.tv.client.data.addons

import com.fourseveneightnine.tv.client.data.settings.AddonSource
import com.fourseveneightnine.tv.client.data.settings.SettingsDocument
import com.fourseveneightnine.tv.client.data.streams.SubtitleTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

data class SubtitleSearchResult(
    val tracks: List<SubtitleTrack> = emptyList(),
    val completed: Int = 0,
    val total: Int = 0,
    val failed: Int = 0,
) {
    val searching: Boolean get() = completed < total
}

/** TV-local add-on choices layered over the latest phone settings document. */
data class AddonRegistryOverrides(
    val installed: List<AddonSource> = emptyList(),
    val enabled: Map<String, Boolean> = emptyMap(),
    val removed: Set<String> = emptySet(),
    val catalogOrder: List<String> = emptyList(),
    val addonOrder: List<String> = emptyList(),
    val importedEpoch: String? = null,
    val directInstallIDs: Map<String, String> = emptyMap(),
)

/** Persistence seam kept Android-free so registry precedence is covered by plain JVM tests. */
interface AddonRegistryStore {
    fun load(): AddonRegistryOverrides
    fun save(overrides: AddonRegistryOverrides)
}

private object EmptyAddonRegistryStore : AddonRegistryStore {
    override fun load(): AddonRegistryOverrides = AddonRegistryOverrides()
    override fun save(overrides: AddonRegistryOverrides) = Unit
}

/**
 * The ordered list of add-ons the TV talks to.
 *
 * Order, and why: **Cinemeta is always first and always on**, because it is the only add-on that
 * answers meta for every IMDb id, and a Detail screen with no meta is a blank page. After it come
 * the owner's own `sources[]` in the order the phone stored them, then the two legacy manifest
 * fields, then AIOStreams and MediaFusion. Duplicates are dropped by normalised manifest URL, so
 * an add-on listed twice is asked once.
 *
 * Stream searches retry enabled add-ons even when their last manifest refresh failed.
 */
class AddonRegistry(
    private val document: SettingsDocument,
    private val client: StremioClient,
    private val health: AddonHealthStore = InMemoryAddonHealthStore(),
    private val store: AddonRegistryStore = EmptyAddonRegistryStore,
) {
    private val state = MutableStateFlow(initialAddons())

    /** Paints immediately with names and URLs; manifests fill in after [refresh]. */
    val addons: StateFlow<List<Addon>> = state.asStateFlow()

    private val catalogRevision = MutableStateFlow(0L)
    val catalogRefreshes: StateFlow<Long> = catalogRevision.asStateFlow()

    /**
     * Fetch manifests concurrently and publish each result as it arrives.
     *
     * One slow or failing add-on does not delay catalogs from providers that have answered.
     */
    suspend fun refresh(force: Boolean = false) {
        if (force) client.clearCatalogCache()
        val gate = Semaphore(8)
        supervisorScope {
            state.value.filter(Addon::enabled).map { addon ->
                async {
                    gate.withPermit {
                        refreshManifest(addon, force)
                    }
                }
            }.awaitAll()
        }
        if (force) catalogRevision.update { it + 1 }
    }

    /** Recheck one installed manifest, even when its sources are turned off. */
    suspend fun refreshAddon(manifestURL: String): Boolean {
        val installed = addon(manifestURL) ?: return false
        if (!refreshManifest(installed, force = true)) return false
        catalogRevision.update { it + 1 }
        return true
    }

    private suspend fun refreshManifest(addon: Addon, force: Boolean): Boolean {
        val manifest = try {
            client.manifest(addon.manifestURL, force = force).also { health.markOk(addon.manifestURL) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            health.markFail(addon.manifestURL, failure.healthLabel())
            null
        }
        // Keep edits made while this request was in flight.
        state.update { list ->
            list.map { latest ->
                if (latest.key == addon.key) latest.copy(
                    manifest = manifest ?: latest.manifest,
                    health = health.health(latest.manifestURL),
                ) else latest
            }
        }
        return manifest != null
    }

    /** A transient manifest or stream failure cannot remove an enabled stream add-on. */
    fun streamAddons(): List<Addon> = state.value.filter {
        it.key != CINEMETA_KEY && it.enabled && it.supports("stream")
    }

    internal fun recordStreamResult(addon: Addon, failure: String?) {
        if (failure == null) health.markOk(addon.manifestURL)
        else health.markFail(addon.manifestURL, failure)
        state.update { list ->
            list.map { row ->
                if (row.key == addon.key) row.copy(health = health.health(row.manifestURL)) else row
            }
        }
    }

    /** Add-ons that can answer `/catalog`. */
    fun catalogAddons(): List<Addon> = state.value.filter { it.usable && it.supports("catalog") }

    /** Add-ons that can answer `/meta`, Cinemeta first. */
    fun metaAddons(): List<Addon> = state.value.filter { it.usable && it.supports("meta") }

    /**
     * Subtitle add-ons: the dedicated subtitle list from settings, then any stream add-on that also
     * declares the `subtitles` resource.
     */
    fun subtitleAddons(): List<Addon> {
        val overrides = store.load().sanitized()
        val dedicated = (if (document.tvReplaceSources) emptyList() else document.subtitleSources)
            .filter { source ->
                val key = normalizedKey(source.url)
                key !in overrides.removed && (overrides.enabled[key] ?: source.enabled)
            }
            .map { source ->
                Addon(
                    name = source.name,
                    manifestURL = source.url,
                    enabled = true,
                    health = health.health(source.url),
                )
            }
        // Retry enabled providers after transient failures, including a missing manifest.
        val alsoSubtitles = state.value.filter {
            it.key != CINEMETA_KEY && it.enabled && it.supports("subtitles")
        }
        val seen = mutableSetOf<String>()
        return (dedicated + alsoSubtitles).filter { seen.add(it.key) }
    }

    /** Results arrive independently. Closing the picker cancels the requests and their deadline. */
    fun searchSubtitles(type: String, id: String, extra: SubtitleExtra = SubtitleExtra()): Flow<SubtitleSearchResult> = channelFlow {
        val providers = subtitleAddons().filter { addon ->
            addon.manifest?.types.orEmpty().let { it.isEmpty() || type in it }
        }.take(32)
        var result = SubtitleSearchResult(total = providers.size)
        send(result)
        val lock = Mutex()
        val gate = Semaphore(6)
        withTimeoutOrNull(20_000L) {
            supervisorScope {
                providers.forEach { addon -> launch {
                    var failed = false
                    val found = try {
                        gate.withPermit {
                            withTimeoutOrNull(12_000L) { client.subtitles(addon, type, id, extra) }
                                ?: run { failed = true; emptyList() }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        failed = true
                        emptyList()
                    }
                    lock.withLock {
                        result = result.copy(
                            tracks = (result.tracks + found).distinctBy(SubtitleTrack::url).take(300),
                            completed = result.completed + 1,
                            failed = result.failed + if (failed) 1 else 0,
                        )
                        send(result)
                    }
                } }
            }
        }
        if (result.searching) {
            send(result.copy(completed = result.total, failed = result.failed + result.total - result.completed))
        }
    }

    /** Every catalog every add-on declares, sorted into the phone's `catalogOrder` where stated. */
    fun catalogs(): List<Pair<Addon, AddonCatalog>> {
        val pairs = catalogAddons().flatMap { addon ->
            addon.manifest?.catalogs.orEmpty().map { addon to it }
        }
        val order = catalogOrder()
        if (order.isEmpty()) return pairs
        val rank = order.withIndex().associate { (index, uid) -> uid to index }
        return pairs.sortedBy { (addon, catalog) ->
            rank[catalog.uid(addon.manifestURL)] ?: rank[catalog.id] ?: Int.MAX_VALUE
        }
    }

    /** A receiver-local explicit order wins; otherwise the paired phone's order remains canonical. */
    fun catalogOrder(): List<String> {
        val overrides = store.load().sanitized()
        if (overrides.addonOrder.isNotEmpty()) {
            return state.value.flatMap { addon ->
                addon.manifest?.catalogs.orEmpty().map { catalog -> catalog.uid(addon.manifestURL) }
            }
        }
        return overrides.catalogOrder.ifEmpty { document.catalogOrder }
    }

    /** Moves an add-on and its catalogs as one receiver-local block. */
    fun moveAddon(manifestURL: String, delta: Int): Boolean {
        val key = AddonEndpoint.normalize(manifestURL) ?: return false
        if (key == CINEMETA_KEY) return false
        val current = state.value
        val from = current.indexOfFirst { it.key == key }
        val to = from + delta
        if (from < 0 || to !in current.indices || to == 0) return false
        val reordered = current.toMutableList().apply { add(to, removeAt(from)) }
        state.value = reordered
        val order = reordered.flatMap { addon ->
            addon.manifest?.catalogs.orEmpty().map { catalog -> catalog.uid(addon.manifestURL) }
        }
        val saved = store.load().sanitized()
        store.save(saved.copy(catalogOrder = order, addonOrder = reordered.map(Addon::key)))
        return true
    }

    /** Find an add-on by its normalised manifest URL. */
    fun addon(manifestURL: String): Addon? {
        val key = AddonEndpoint.normalize(manifestURL) ?: manifestURL
        return state.value.firstOrNull { it.key == key }
    }

    /** A TV choice is explicit and therefore wins over the phone's value after a later sync. */
    fun setEnabled(manifestURL: String, enabled: Boolean): Boolean {
        val key = AddonEndpoint.normalize(manifestURL) ?: return false
        if (key == CINEMETA_KEY || state.value.none { it.key == key }) return false
        val saved = store.load().sanitized()
        store.save(saved.copy(enabled = saved.enabled + (key to enabled), removed = saved.removed - key))
        state.value = state.value.map { addon ->
            if (addon.key == key) addon.copy(enabled = enabled) else addon
        }
        return true
    }

    /** Cinemeta is structural; every other source can be hidden locally without editing the phone. */
    fun remove(manifestURL: String): Boolean {
        val key = AddonEndpoint.normalize(manifestURL) ?: return false
        if (key == CINEMETA_KEY || state.value.none { it.key == key }) return false
        val saved = store.load().sanitized()
        store.save(
            saved.copy(
                installed = saved.installed.filterNot { normalizedKey(it.url) == key },
                enabled = saved.enabled - key,
                removed = saved.removed + key,
            ),
        )
        state.value = state.value.filterNot { it.key == key }
        return true
    }

    /**
     * Validates the endpoint and fetches a real Stremio manifest before the URL is persisted.
     * The URL is never included in an exception or display message because it may carry a key.
     */
    suspend fun install(typedURL: String): Addon {
        val manifestURL = AddonEndpoint.manifestURL(typedURL)
            ?: throw IllegalArgumentException("bad_manifest_url")
        val key = checkNotNull(AddonEndpoint.normalize(manifestURL))
        if (key == CINEMETA_KEY) return checkNotNull(state.value.firstOrNull { it.key == key })
        check(!document.tvReplaceSources) { "replace_mode_hides_manual_addons" }
        if (state.value.none { it.key == key } && state.value.size >= MAX_ADDONS + 1) {
            throw IllegalStateException("addon_limit")
        }
        val manifest = client.manifest(manifestURL)
        val saved = store.load().sanitized()
        val local = AddonSource(manifest.name.take(80), manifestURL, enabled = true)
        store.save(
            saved.copy(
                installed = (saved.installed.filterNot { normalizedKey(it.url) == key } + local).takeLast(MAX_ADDONS),
                enabled = saved.enabled + (key to true),
                removed = saved.removed - key,
            ),
        )
        val installed = Addon(
            name = local.name,
            manifestURL = manifestURL,
            enabled = true,
            manifest = manifest,
            health = health.health(manifestURL),
        )
        val current = state.value
        state.value = if (current.any { it.key == key }) {
            current.map { if (it.key == key) installed else it }
        } else {
            current + installed
        }
        return installed
    }

    private fun initialAddons(): List<Addon> {
        val cinemeta = Addon(
            name = "Cinemeta",
            manifestURL = StremioClient.CINEMETA_MANIFEST,
            enabled = true,
            health = health.health(StremioClient.CINEMETA_MANIFEST),
        )
        val overrides = store.load().sanitized()
        // Local installs are explicit receiver choices, so they retain a slot even if a later
        // phone sync fills all 32 source positions. Phone order is preserved among phone sources.
        val sources = document.visibleDirectAddons.map { it.source } + if (document.tvReplaceSources) {
            document.tvImportedSources
        } else {
            document.tvImportedSources + overrides.installed + document.addonSources
        }
        val stored = sources.mapNotNull { source ->
            val key = normalizedKey(source.url) ?: return@mapNotNull null
            if (key in overrides.removed) return@mapNotNull null
            Addon(
                name = source.name,
                manifestURL = source.url,
                enabled = overrides.enabled[key] ?: source.enabled,
                health = health.health(source.url),
            )
        }
        val seen = mutableSetOf<String>()
        val base = (listOf(cinemeta) + stored).filter { seen.add(it.key) }.take(MAX_ADDONS + 1)
        if (overrides.addonOrder.isEmpty()) return base
        val rank = overrides.addonOrder.withIndex().associate { (index, key) -> key to index }
        return listOf(cinemeta) + base.drop(1).sortedBy { rank[it.key] ?: Int.MAX_VALUE }
    }

    private fun AddonRegistryOverrides.sanitized(): AddonRegistryOverrides {
        val installed = installed.mapNotNull { source ->
            val route = AddonEndpoint.manifestURL(source.url) ?: return@mapNotNull null
            source.copy(name = source.name.trim().take(80).ifBlank { "Add-on" }, url = route)
        }.distinctBy { normalizedKey(it.url) }.take(MAX_ADDONS)
        val enabled = enabled.mapNotNull { (raw, value) -> normalizedKey(raw)?.let { it to value } }.toMap()
        val removed = removed.mapNotNull(::normalizedKey).toSet()
        val addonOrder = addonOrder.mapNotNull(::normalizedKey).distinct().take(MAX_ADDONS + 1)
        val epoch = document.tvImportedSourcesEpoch ?: "legacy:" + java.security.MessageDigest.getInstance("SHA-256")
            .digest(document.tvImportedSources.joinToString("\n") { "${it.name}\u0000${it.url}\u0000${it.enabled}" }.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
        val importedKeys = document.tvImportedSources.mapNotNull { normalizedKey(it.url) }.toSet()
        val retainedEnabled = if (importedEpoch == epoch) enabled else enabled - importedKeys
        val retainedRemoved = if (importedEpoch == epoch) removed else removed - importedKeys
        val directIDs = document.directAddons.associate { checkNotNull(normalizedKey(it.source.url)) to it.installID }
        val reinstalled = directIDs.filter { (key, id) -> directInstallIDs[key] != id }.keys
        return AddonRegistryOverrides(installed, retainedEnabled - reinstalled, retainedRemoved - reinstalled,
            catalogOrder, addonOrder, epoch, directIDs)
    }

    private companion object {
        const val MAX_ADDONS = 256
        val CINEMETA_KEY = checkNotNull(AddonEndpoint.normalize(StremioClient.CINEMETA_MANIFEST))
        fun normalizedKey(url: String): String? = AddonEndpoint.normalize(url)
    }
}
