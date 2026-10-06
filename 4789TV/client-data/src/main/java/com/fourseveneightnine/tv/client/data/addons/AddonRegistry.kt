package com.fourseveneightnine.tv.client.data.addons

import com.fourseveneightnine.tv.client.data.settings.AddonSource
import com.fourseveneightnine.tv.client.data.settings.SettingsDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.supervisorScope

/** TV-local add-on choices layered over the latest phone settings document. */
data class AddonRegistryOverrides(
    val installed: List<AddonSource> = emptyList(),
    val enabled: Map<String, Boolean> = emptyMap(),
    val removed: Set<String> = emptySet(),
    val catalogOrder: List<String> = emptyList(),
    val addonOrder: List<String> = emptyList(),
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
 * **An unhealthy add-on is never dropped automatically.** Three failures in a row marks it,
 * Settings shows it grey, and the fan-out skips it. The viewer may still remove it explicitly.
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

    /**
     * Fetch every manifest, in parallel, and publish the result.
     *
     * One add-on failing never fails the set: `supervisorScope` keeps the siblings running, and the
     * failure lands in [AddonHealthStore] as a class name or a status code. A cached manifest makes
     * this nearly free, so it is safe to call on every foreground.
     */
    suspend fun refresh() {
        val current = state.value
        val gate = Semaphore(8)
        val fetched = supervisorScope {
            current.map { addon ->
                async {
                    if (!addon.enabled) return@async addon
                    gate.withPermit {
                        try {
                            val manifest = client.manifest(addon.manifestURL)
                            health.markOk(addon.manifestURL)
                            addon.copy(manifest = manifest, health = health.health(addon.manifestURL))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Throwable) {
                            health.markFail(addon.manifestURL, failure.healthLabel())
                            addon.copy(health = health.health(addon.manifestURL))
                        }
                    }
                }
            }.awaitAll()
        }
        val fetchedByKey = fetched.associateBy(Addon::key)
        // A refresh can finish after the viewer toggles, removes, or installs a source. Merge only
        // fetched manifest/health data into the latest list so that stale network work cannot undo
        // an explicit Settings action.
        state.value = state.value.map { latest ->
            fetchedByKey[latest.key]?.copy(enabled = latest.enabled) ?: latest
        }
    }

    /** Add-ons that can answer `/stream`, healthy and enabled, in registry order. */
    fun streamAddons(): List<Addon> = state.value.filter { it.usable && it.supports("stream") }

    /** Add-ons that can answer `/catalog`. */
    fun catalogAddons(): List<Addon> = state.value.filter { it.usable && it.supports("catalog") }

    /** Add-ons that can answer `/meta`, Cinemeta first. */
    fun metaAddons(): List<Addon> = state.value.filter { it.usable && it.supports("meta") }

    /**
     * Subtitle add-ons: the dedicated subtitle list from settings, then any stream add-on that also
     * declares the `subtitles` resource.
     */
    fun subtitleAddons(): List<Addon> {
        val dedicated = (if (document.tvReplaceSources) emptyList() else document.subtitleSources)
            .filter(com.fourseveneightnine.tv.client.data.settings.SubtitleSource::enabled)
            .map { source ->
                Addon(
                    name = source.name,
                    manifestURL = source.url,
                    enabled = true,
                    health = health.health(source.url),
                )
            }
        val alsoSubtitles = state.value.filter { it.usable && it.manifest?.resources?.any { resource ->
            resource.equals("subtitles", ignoreCase = true)
        } == true }
        val seen = mutableSetOf<String>()
        return (dedicated + alsoSubtitles).filter { seen.add(it.key) }.filter { it.health.healthy }
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
        val saved = store.load()
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
        val saved = store.load()
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
        val saved = store.load()
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
        if (state.value.none { it.key == key } && state.value.size >= MAX_ADDONS + 1) {
            throw IllegalStateException("addon_limit")
        }
        val manifest = client.manifest(manifestURL)
        val saved = store.load()
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
        val importedKeys = document.tvImportedSources.mapNotNull { normalizedKey(it.url) }.toSet()
        // Local installs are explicit receiver choices, so they retain a slot even if a later
        // phone sync fills all 32 source positions. Phone order is preserved among phone sources.
        val sources = if (document.tvReplaceSources) {
            document.tvImportedSources
        } else {
            document.tvImportedSources + overrides.installed + document.addonSources
        }
        val stored = sources.mapNotNull { source ->
            val key = normalizedKey(source.url) ?: return@mapNotNull null
            if (key !in importedKeys && key in overrides.removed) return@mapNotNull null
            Addon(
                name = source.name,
                manifestURL = source.url,
                enabled = if (key in importedKeys) source.enabled else overrides.enabled[key] ?: source.enabled,
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
        return AddonRegistryOverrides(installed, enabled, removed, catalogOrder, addonOrder)
    }

    private companion object {
        const val MAX_ADDONS = 256
        val CINEMETA_KEY = checkNotNull(AddonEndpoint.normalize(StremioClient.CINEMETA_MANIFEST))
        fun normalizedKey(url: String): String? = AddonEndpoint.normalize(url)
    }
}
