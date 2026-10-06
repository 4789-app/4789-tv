package com.fourseveneightnine.tv.client.data.addons

/** One catalog an add-on declares in its manifest. */
data class AddonCatalog(
    val type: String,
    val id: String,
    val name: String,
    /** Extra keys the manifest declares, e.g. `skip`, `genre`, `search`. Advisory only. */
    val extraSupported: List<String> = emptyList(),
    val extraRequired: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
) {
    /** Stable key for a catalog across add-ons: `<manifest host>|<type>|<id>`. */
    fun uid(manifestURL: String): String = "${AddonEndpoint.normalize(manifestURL) ?: manifestURL}|$type|$id"
}

/** What an add-on says it is and what it says it can answer. */
data class AddonManifest(
    val id: String,
    val name: String,
    val version: String? = null,
    val description: String? = null,
    val logo: String? = null,
    val types: List<String> = emptyList(),
    /** `catalog`, `meta`, `stream`, `subtitles`. */
    val resources: List<String> = emptyList(),
    val catalogs: List<AddonCatalog> = emptyList(),
    val idPrefixes: List<String> = emptyList(),
)

/** How an add-on has been behaving. Three consecutive failures makes it unhealthy. */
data class AddonHealth(
    val failStreak: Int = 0,
    val lastOkAtMillis: Long? = null,
    val lastFailAtMillis: Long? = null,
    /** A short reason for Settings. Never a URL: an add-on URL can carry a credential. */
    val lastError: String? = null,
) {
    val healthy: Boolean get() = failStreak < UNHEALTHY_AFTER

    companion object {
        const val UNHEALTHY_AFTER: Int = 3
    }
}

/**
 * One add-on in the registry.
 *
 * [manifest] is null until the first successful fetch, so the UI can paint the list before the
 * network answers. An unhealthy add-on is never dropped — it goes grey in Settings and is skipped
 * for this fan-out, which is recoverable; dropping it would need the owner to add it again.
 */
data class Addon(
    val name: String,
    val manifestURL: String,
    val enabled: Boolean = true,
    val manifest: AddonManifest? = null,
    val health: AddonHealth = AddonHealth(),
) {
    val key: String get() = AddonEndpoint.normalize(manifestURL) ?: manifestURL

    /** The display name the manifest gave, falling back to the name the phone stored. */
    val displayName: String get() = manifest?.name?.takeIf(String::isNotBlank) ?: name

    fun supports(resource: String): Boolean {
        val declared = manifest?.resources ?: return true // unknown yet: try it once
        return declared.isEmpty() || declared.any { it.equals(resource, ignoreCase = true) }
    }

    /** Usable right now: the owner left it on, and it has not failed three times running. */
    val usable: Boolean get() = enabled && health.healthy
}

/** What Stremio's `extra` segment can carry on a catalog call. */
data class CatalogExtra(
    val skip: Int = 0,
    val genre: String? = null,
    val search: String? = null,
)

/**
 * Where add-on health lives.
 *
 * In-memory here so this module has no Room dependency in its own tests. Part B's Room layer can
 * implement the same interface later and the registry will not notice.
 */
interface AddonHealthStore {
    fun health(manifestURL: String): AddonHealth
    fun markOk(manifestURL: String)
    fun markFail(manifestURL: String, error: String)
    fun snapshot(): Map<String, AddonHealth>
}

class InMemoryAddonHealthStore(
    private val clock: () -> Long = System::currentTimeMillis,
) : AddonHealthStore {
    private val entries = java.util.concurrent.ConcurrentHashMap<String, AddonHealth>()

    override fun health(manifestURL: String): AddonHealth =
        entries[key(manifestURL)] ?: AddonHealth()

    override fun markOk(manifestURL: String) {
        entries[key(manifestURL)] = AddonHealth(failStreak = 0, lastOkAtMillis = clock())
    }

    override fun markFail(manifestURL: String, error: String) {
        val id = key(manifestURL)
        val current = entries[id] ?: AddonHealth()
        entries[id] = current.copy(
            failStreak = current.failStreak + 1,
            lastFailAtMillis = clock(),
            // Class names and status codes only. The caller is responsible for never passing a URL.
            lastError = error.take(120),
        )
    }

    override fun snapshot(): Map<String, AddonHealth> = entries.toMap()

    private fun key(manifestURL: String): String = AddonEndpoint.normalize(manifestURL) ?: manifestURL
}
