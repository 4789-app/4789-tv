package com.fourseveneightnine.phone

import android.content.Context
import com.fourseveneightnine.contract.CatalogSnapshotContract
import com.fourseveneightnine.contract.DiscoverEnvelope
import com.fourseveneightnine.contract.DiscoverItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

sealed interface CatalogLoadState {
    data object Loading : CatalogLoadState
    data class Ready(val items: List<DiscoverItem>, val isLive: Boolean) : CatalogLoadState
    data class Failed(val message: String) : CatalogLoadState
}

object CatalogSnapshotRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private val remoteLock = Mutex()
    private var remoteCache: CachedRemoteCatalog? = null

    suspend fun loadBundled(context: Context): CatalogLoadState = withContext(Dispatchers.IO) {
        runCatching {
            loadBundledItems(context)
        }.fold(
            onSuccess = { CatalogLoadState.Ready(it, isLive = false) },
            onFailure = { CatalogLoadState.Failed("Catalogs are unavailable offline.") },
        )
    }

    suspend fun loadRemote(context: Context): List<DiscoverItem>? = remoteLock.withLock {
        val now = System.nanoTime()
        remoteCache?.takeIf { CatalogRefreshPolicy.isFresh(now, it.storedAtNanos) }?.let {
            return@withLock it.items
        }
        val disk = CatalogDiskCache(context)
        withContext(Dispatchers.IO) {
            runCatching {
                val manifestData = fetch(
                    disk,
                    CatalogSnapshotContract.manifestURL,
                    CatalogSnapshotContract.maximumManifestBytes,
                )
                val payload = CatalogSnapshotContract.decodeAndVerifyManifest(manifestData)
                val artifact = payload.artifacts.firstOrNull { it.role == "popular-movies-popular" }
                    ?: return@runCatching null
                val artifactURL = CatalogSnapshotContract.artifactBaseURL +
                    CatalogSnapshotContract.artifactPath(artifact.key)
                val artifactData = fetch(
                    disk,
                    artifactURL,
                    CatalogSnapshotContract.maximumCatalogBytes,
                )
                val index = CatalogSnapshotContract.decodeAndVerifyArtifact(artifactData, artifact)
                CatalogSnapshotContract.rowsAsDiscoverItems(index).takeIf { it.isNotEmpty() }
            }.getOrNull()
        }?.also { remoteCache = CachedRemoteCatalog(it, now) }
    }

    /**
     * Disk first, then the network, then a stale entry if the network fails.
     *
     * The in-memory cache above only survives while the process does, so before this every cold
     * start paid for two round trips before any live row could appear. The bytes returned here are
     * still signature- and digest-checked by the caller, so a cached read is trusted no further
     * than a fresh download.
     */
    private fun fetch(disk: CatalogDiskCache, url: String, maximumBytes: Int): ByteArray {
        disk.fresh(url)?.let { return it }
        return runCatching { get(url, maximumBytes) }
            .onSuccess { disk.store(url, it) }
            .getOrElse { failure ->
                disk.stale(url) ?: throw failure
            }
    }

    fun mergeRemoteWithBundled(
        remote: List<DiscoverItem>,
        bundled: List<DiscoverItem>,
    ): List<DiscoverItem> = (remote + bundled).distinctBy(DiscoverItem::id)

    private fun loadBundledItems(context: Context): List<DiscoverItem> {
        val payload = context.assets.open("discover_catalog_movie.json")
                .bufferedReader()
                .use { it.readText() }
        return json.decodeFromString<DiscoverEnvelope>(payload).items
    }

    private fun get(url: String, maximumBytes: Int): ByteArray {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 3_000
        connection.readTimeout = 5_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/json")
        return try {
            require(connection.responseCode == 200) { "http_${connection.responseCode}" }
            val declared = connection.contentLengthLong
            require(declared == -1L || declared in 1..maximumBytes.toLong()) { "content_length" }
            connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= maximumBytes) { "response_size" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }
}

internal data class CachedRemoteCatalog(val items: List<DiscoverItem>, val storedAtNanos: Long)

internal object CatalogRefreshPolicy {
    private const val CACHE_LIFETIME_NANOS = 5L * 60 * 1_000_000_000

    fun isFresh(nowNanos: Long, storedAtNanos: Long): Boolean {
        val age = nowNanos - storedAtNanos
        return age in 0..<CACHE_LIFETIME_NANOS
    }
}
