package com.fourseveneightnine.tv.catalog

import android.content.Context
import android.util.AtomicFile
import com.fourseveneightnine.contract.CatalogSnapshotContract
import java.net.URI
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Signed public TMDB shelves. They never require the user's private catalog token. */
internal sealed interface TVTMDBCatalogState {
    data object Empty : TVTMDBCatalogState
    data class Loading(val cached: TVTMDBCatalogSnapshot? = null) : TVTMDBCatalogState
    data class Ready(
        val movies: List<TVTamilMVCatalogItem>,
        val series: List<TVTamilMVCatalogItem>,
        val generatedAtMillis: Long,
    ) : TVTMDBCatalogState
    data class Error(val message: String, val cached: TVTMDBCatalogSnapshot? = null) : TVTMDBCatalogState
}

@Serializable
internal data class TVTMDBCatalogSnapshot(
    val schemaVersion: Int = 1,
    val generatedAtMillis: Long,
    val cachedAtMillis: Long,
    val movies: List<TVTamilMVCatalogItem>,
    val series: List<TVTamilMVCatalogItem>,
) {
    val itemCount: Int get() = movies.size + series.size

    fun asReady(): TVTMDBCatalogState.Ready = TVTMDBCatalogState.Ready(
        movies = movies,
        series = series,
        generatedAtMillis = generatedAtMillis,
    )
}

internal fun TVTMDBCatalogState.visibleSnapshot(): TVTMDBCatalogSnapshot? = when (this) {
    is TVTMDBCatalogState.Loading -> cached
    is TVTMDBCatalogState.Error -> cached
    is TVTMDBCatalogState.Ready -> TVTMDBCatalogSnapshot(
        generatedAtMillis = generatedAtMillis,
        cachedAtMillis = 0L,
        movies = movies,
        series = series,
    )
    TVTMDBCatalogState.Empty -> null
}

internal interface TVTMDBCatalogCache {
    fun load(): TVTMDBCatalogSnapshot?
    fun store(snapshot: TVTMDBCatalogSnapshot)
    fun clear()
}

internal object TVTMDBCatalogCachePolicy {
    fun validate(snapshot: TVTMDBCatalogSnapshot): TVTMDBCatalogSnapshot {
        require(snapshot.schemaVersion == 1) { "tmdb_cache_schema" }
        require(snapshot.generatedAtMillis >= 0 && snapshot.cachedAtMillis >= 0) { "tmdb_cache_time" }
        require(snapshot.movies.size <= MAX_ITEMS && snapshot.series.size <= MAX_ITEMS) { "tmdb_cache_count" }
        // Reuse the same strict item/artwork validation as the private catalog cache. The bridge
        // keeps the two local stores on one safety boundary without persisting any private data.
        TVTamilMVCatalogImportPolicy.validate(
            TVTamilMVCatalogSnapshot(
                generation = "tmdb-cache",
                generatedAtMillis = snapshot.generatedAtMillis,
                cachedAtMillis = snapshot.cachedAtMillis,
                popular = snapshot.movies,
                recent = snapshot.series,
            ),
        )
        return snapshot
    }

    private const val MAX_ITEMS = 1_000
}

/** Durable receiver-local TMDB metadata. This is a cache, not a source of truth. */
internal class AtomicTVTMDBCatalogCache(context: Context) : TVTMDBCatalogCache {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val atomicFile = AtomicFile(
        java.io.File(context.applicationContext.filesDir, CACHE_FILE_NAME),
    )

    override fun load(): TVTMDBCatalogSnapshot? {
        val file = atomicFile.baseFile
        if (!file.isFile || file.length() !in 1..MAX_CACHE_BYTES.toLong()) return null
        return runCatching {
            TVTMDBCatalogCachePolicy.validate(
                json.decodeFromString<TVTMDBCatalogSnapshot>(atomicFile.readFully().decodeToString()),
            )
        }.getOrNull()
    }

    override fun store(snapshot: TVTMDBCatalogSnapshot) {
        val bytes = json.encodeToString(TVTMDBCatalogCachePolicy.validate(snapshot)).encodeToByteArray()
        require(bytes.size <= MAX_CACHE_BYTES)
        var output: java.io.FileOutputStream? = null
        try {
            output = atomicFile.startWrite()
            output.write(bytes)
            atomicFile.finishWrite(output)
        } catch (failure: Throwable) {
            output?.let(atomicFile::failWrite)
            throw failure
        }
    }

    override fun clear() {
        atomicFile.delete()
    }

    private companion object {
        const val CACHE_FILE_NAME = "public-tmdb-catalog-v1.json"
        const val MAX_CACHE_BYTES = 12 * 1024 * 1024
    }
}

internal class TVTMDBCatalogRepository(
    private val http: TVPrivateCatalogHTTPClient = URLConnectionPrivateCatalogHTTPClient(),
    private val cache: TVTMDBCatalogCache? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val gate = Mutex()
    private val mutableState = MutableStateFlow<TVTMDBCatalogState>(TVTMDBCatalogState.Empty)
    val state: StateFlow<TVTMDBCatalogState> = mutableState.asStateFlow()

    suspend fun refresh(force: Boolean = false) {
        if (!gate.tryLock()) return
        val priorState = mutableState.value
        try {
            withContext(ioDispatcher) {
                if (!force && mutableState.value is TVTMDBCatalogState.Ready) return@withContext
                val cached = cache?.load()
                mutableState.value = TVTMDBCatalogState.Loading(cached)
                val loaded = try {
                    load()
                } catch (cancelled: CancellationException) {
                    mutableState.value = priorState
                    throw cancelled
                } catch (_: Throwable) {
                    mutableState.value = TVTMDBCatalogState.Error(
                        "TMDB catalogs could not refresh. Check the TV network and retry.",
                        cached = cached,
                    )
                    return@withContext
                }
                mutableState.value = if (loaded.movies.isEmpty() && loaded.series.isEmpty()) {
                    TVTMDBCatalogState.Error(
                        "The public TMDB snapshot is empty. Try again from Jobs later.",
                        cached = cached,
                    )
                } else {
                    runCatching {
                        cache?.store(
                            TVTMDBCatalogSnapshot(
                                generatedAtMillis = loaded.generatedAtMillis,
                                cachedAtMillis = nowMillis(),
                                movies = loaded.movies,
                                series = loaded.series,
                            ),
                        )
                    }
                    loaded
                }
            }
        } catch (cancelled: CancellationException) {
            mutableState.value = priorState
            throw cancelled
        } finally {
            gate.unlock()
        }
    }

    private fun load(): TVTMDBCatalogState.Ready {
        val manifestBytes = http.get(
            url = CatalogSnapshotContract.manifestURL,
            token = "",
            maximumBytes = CatalogSnapshotContract.maximumManifestBytes,
        )
        val manifest = CatalogSnapshotContract.decodeAndVerifyManifest(manifestBytes)
        val indexes = manifest.artifacts.asSequence()
            .filter { artifact ->
                artifact.role in setOf(
                    "popular-movies-popular", "popular-movies-recent",
                    "popular-tv-popular", "popular-tv-recent",
                )
            }
            .map { artifact ->
                val bytes = http.get(
                    url = CatalogSnapshotContract.artifactBaseURL + CatalogSnapshotContract.artifactPath(artifact.key),
                    token = "",
                    maximumBytes = minOf(artifact.byteCount, CatalogSnapshotContract.maximumCatalogBytes),
                )
                CatalogSnapshotContract.decodeAndVerifyArtifact(bytes, artifact)
            }
            .toList()
        val movies = indexes.filter { it.catalogID.startsWith("4789:popular-movies:") }
            .flatMap { it.rows.mapNotNull(::displayItem) }
            .distinctBy(TVTamilMVCatalogItem::id)
            .take(MAX_ITEMS)
        val series = indexes.filter { it.catalogID.startsWith("4789:popular-tv:") }
            .flatMap { it.rows.mapNotNull(::displayItem) }
            .distinctBy(TVTamilMVCatalogItem::id)
            .take(MAX_ITEMS)
        val generatedAt = indexes.mapNotNull { runCatching { Instant.parse(it.generatedAt).toEpochMilli() }.getOrNull() }
            .maxOrNull() ?: runCatching { Instant.parse(manifest.createdAt).toEpochMilli() }.getOrDefault(0L)
        return TVTMDBCatalogState.Ready(movies, series, generatedAt)
    }

    private fun displayItem(row: com.fourseveneightnine.contract.CatalogFacetRow): TVTamilMVCatalogItem? {
        val id = row.canonicalID.trim().takeIf { it.isNotEmpty() && it.length <= 500 } ?: return null
        val title = row.title.trim().takeIf { it.isNotEmpty() && it.length <= 500 } ?: return null
        val mediaType = row.mediaType.takeIf { it == "movie" || it == "series" } ?: return null
        return TVTamilMVCatalogItem(
            id = id,
            imdbID = id.substringAfterLast("::").takeIf { it.matches(Regex("tt[0-9]{5,16}")) },
            tmdbID = id.substringAfterLast("::").removePrefix("tmdb:").toIntOrNull()?.takeIf { it > 0 },
            mediaType = mediaType,
            title = title,
            posterURL = safeArtworkURL(row.posterURL),
            backdropURL = safeArtworkURL(row.backdropURL),
            overview = row.overview?.trim()?.takeIf(String::isNotEmpty)?.take(MAX_OVERVIEW_CHARACTERS),
            year = row.year?.takeIf { it in 1870..2200 },
            genres = row.genres.map(String::trim).filter(String::isNotEmpty).distinct().take(8),
        )
    }

    private fun safeArtworkURL(value: String?): String? = value?.trim()?.takeIf { it.length <= MAX_URL_CHARACTERS }
        ?.let { candidate -> runCatching { URI(candidate) }.getOrNull() }
        ?.takeIf { it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null }
        ?.toString()

    private companion object {
        const val MAX_ITEMS = 1_000
        const val MAX_URL_CHARACTERS = 2_048
        const val MAX_OVERVIEW_CHARACTERS = 4_000
    }
}
