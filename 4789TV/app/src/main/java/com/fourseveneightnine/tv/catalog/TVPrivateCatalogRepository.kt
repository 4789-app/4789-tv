package com.fourseveneightnine.tv.catalog

import android.content.Context
import android.util.AtomicFile
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics
import com.fourseveneightnine.contract.CatalogSnapshotContract
import com.fourseveneightnine.tv.settings.StoredTVSettingsState
import com.fourseveneightnine.tv.settings.TVSettingsPersistence
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
internal data class TVTamilMVCatalogItem(
    val id: String,
    val mediaType: String,
    val title: String,
    val imdbID: String? = null,
    val tmdbID: Int? = null,
    val posterURL: String? = null,
    val backdropURL: String? = null,
    val overview: String? = null,
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    // Series detail state is intentionally part of the item identity. The catalog snapshot stays
    // backwards compatible (old transfers omit these fields), while addon routes can target an
    // exact season/episode instead of accidentally querying a show-level stream.
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
)

internal val TVTamilMVCatalogItem.sourceKey: String
    get() = if (mediaType == "series" && season != null && episode != null) {
        "$id:s$season:e$episode"
    } else {
        id
    }

@Serializable
internal data class TVLetterboxdShelf(
    val id: String,
    val title: String,
    val items: List<TVTamilMVCatalogItem>,
)

@Serializable
internal data class TVTamilMVCatalogSnapshot(
    val schemaVersion: Int = 1,
    val generation: String,
    val generatedAtMillis: Long,
    val cachedAtMillis: Long,
    val popular: List<TVTamilMVCatalogItem>,
    val recent: List<TVTamilMVCatalogItem>,
    val letterboxd: List<TVTamilMVCatalogItem> = emptyList(),
    val friends: List<TVTamilMVCatalogItem> = emptyList(),
    val letterboxdShelves: List<TVLetterboxdShelf> = emptyList(),
) {
    val itemCount: Int get() = popular.size + recent.size + letterboxd.size + friends.size
    val tamilMVItemCount: Int get() = (popular + recent).distinctBy(TVTamilMVCatalogItem::id).size

    /**
     * The Tamil MV shelves on their own. Keeps the same generation, so anything keyed on the
     * snapshot's identity treats the head and the full document as one catalog.
     */
    fun withoutLetterboxd(): TVTamilMVCatalogSnapshot = copy(
        letterboxd = emptyList(),
        friends = emptyList(),
        letterboxdShelves = emptyList(),
    )
}

internal object TVTamilMVCatalogImportPolicy {
    const val MAX_PLAINTEXT_BYTES = 2 * 1024 * 1024
    // Tamil MV exports include the full homepage/archive projection. The observed iPhone catalog
    // is 1,008+ titles, so a 1,000-row ceiling would silently lose the tail. Letterboxd/Friends
    // remain at the lower rendering cap because those shelves are independently paged by source.
    private const val MAX_TAMIL_ITEMS_PER_SHELF = 2_000
    private const val MAX_OTHER_ITEMS_PER_SHELF = 1_000
    private const val MAX_LETTERBOXD_SHELVES = 256
    private val strictJson = Json { ignoreUnknownKeys = false }

    fun decode(bytes: ByteArray): TVTamilMVCatalogSnapshot {
        require(bytes.size in 1..MAX_PLAINTEXT_BYTES) { "catalog_transfer_size" }
        return validate(strictJson.decodeFromString<TVTamilMVCatalogSnapshot>(bytes.decodeToString()))
    }

    fun validate(snapshot: TVTamilMVCatalogSnapshot): TVTamilMVCatalogSnapshot {
        require(snapshot.schemaVersion == 1) { "catalog_transfer_schema" }
        require(snapshot.generation.isNotBlank() && snapshot.generation.length <= 500) { "catalog_transfer_generation" }
        require(snapshot.generatedAtMillis >= 0 && snapshot.cachedAtMillis >= 0) { "catalog_transfer_time" }
        require(
            snapshot.popular.size <= MAX_TAMIL_ITEMS_PER_SHELF &&
                snapshot.recent.size <= MAX_TAMIL_ITEMS_PER_SHELF &&
                snapshot.letterboxd.size <= MAX_OTHER_ITEMS_PER_SHELF &&
                snapshot.friends.size <= MAX_OTHER_ITEMS_PER_SHELF,
        ) {
            "catalog_transfer_count"
        }
        require(snapshot.itemCount in 1..(MAX_TAMIL_ITEMS_PER_SHELF * 2 + MAX_OTHER_ITEMS_PER_SHELF * 2)) { "catalog_transfer_empty" }
        require((snapshot.popular + snapshot.recent + snapshot.letterboxd + snapshot.friends).all(::validItem)) { "catalog_transfer_item" }
        require(snapshot.letterboxdShelves.size <= MAX_LETTERBOXD_SHELVES) { "catalog_transfer_shelves" }
        require(snapshot.letterboxdShelves.all { shelf ->
            shelf.id.isNotBlank() && shelf.id.length <= 500 &&
                shelf.title.isNotBlank() && shelf.title.length <= 500 &&
                shelf.items.size <= MAX_OTHER_ITEMS_PER_SHELF && shelf.items.all(::validItem)
        }) { "catalog_transfer_shelf" }
        return snapshot
    }

    private fun validItem(item: TVTamilMVCatalogItem): Boolean =
        item.id.isNotBlank() && item.id.length <= 500 &&
            item.imdbID?.let { it.matches(Regex("tt[0-9]{5,16}")) } != false &&
            item.tmdbID?.let { it > 0 } != false &&
            item.title.isNotBlank() && item.title.length <= 500 &&
            item.mediaType in setOf("movie", "series") &&
            item.season?.let { it in 1..100 } != false &&
            item.episode?.let { it in 1..1_000 } != false &&
            item.episodeTitle?.length?.let { it <= 500 } != false &&
            (item.overview?.length ?: 0) <= 4_000 &&
            item.year?.let { it in 1870..2200 } != false &&
            item.genres.size <= 8 && item.genres.all { it.isNotBlank() && it.length <= 100 } &&
            validArtworkURL(item.posterURL) && validArtworkURL(item.backdropURL)

    private fun validArtworkURL(value: String?): Boolean {
        if (value == null) return true
        if (value.length > 2_048) return false
        return runCatching { URI(value) }.getOrNull()
            ?.let { it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null } == true
    }
}

internal sealed interface TVTamilMVCatalogState {
    data class Loading(val cached: TVTamilMVCatalogSnapshot? = null) : TVTamilMVCatalogState
    data class Ready(
        val snapshot: TVTamilMVCatalogSnapshot,
        val isStale: Boolean = false,
        val notice: String? = null,
    ) : TVTamilMVCatalogState
    data object MissingCredential : TVTamilMVCatalogState
    data object Empty : TVTamilMVCatalogState
    data class Error(
        val message: String,
        val cached: TVTamilMVCatalogSnapshot? = null,
    ) : TVTamilMVCatalogState
}

internal fun TVTamilMVCatalogState.visibleSnapshot(): TVTamilMVCatalogSnapshot? = when (this) {
    is TVTamilMVCatalogState.Loading -> cached
    is TVTamilMVCatalogState.Ready -> snapshot
    is TVTamilMVCatalogState.Error -> cached
    TVTamilMVCatalogState.Empty, TVTamilMVCatalogState.MissingCredential -> null
}

internal interface TVTamilMVCatalogCache {
    fun load(): TVTamilMVCatalogSnapshot?

    /**
     * The Tamil MV shelves only, with the Letterboxd payload stripped out.
     *
     * The full document is dominated by Letterboxd: 120 shelves of up to a thousand titles each
     * against 1,311 Tamil MV rows, which is why the file on a real receiver is over 9MB. Decoding
     * all of it before anything can be drawn is what left the first screen empty for seconds after
     * a cold start. This is the same document with those lists emptied, so it decodes in a fraction
     * of the time and gives the opening screen something real to show. Returns null when no head
     * file has been written yet, in which case the caller falls back to [load].
     */
    fun loadHead(): TVTamilMVCatalogSnapshot? = null

    fun store(snapshot: TVTamilMVCatalogSnapshot)
    fun clear()
}

internal fun interface TVPrivateCatalogLoader {
    /**
     * @param onPartial invoked at most once with a usable-but-incomplete snapshot when the
     * artifact fetch outruns [PARTIAL_PAINT_DEADLINE_MILLIS]. A partial snapshot is for painting
     * only and must never be written to the on-disk cache.
     */
    suspend fun load(
        token: String,
        onPartial: (TVTamilMVCatalogSnapshot) -> Unit,
    ): TVTamilMVCatalogSnapshot
}

/**
 * Receiver-local Tamil MV refresh pipeline.
 *
 * Credentials are read from the encrypted settings store for one request and are never cached or
 * logged. The cache contains normalized display metadata only. A failed refresh keeps the last
 * verified snapshot visible, and [refresh] coalesces rapid remote presses into one request.
 */
internal class TVPrivateCatalogRepository(
    private val settings: TVSettingsPersistence,
    private val loader: TVPrivateCatalogLoader,
    private val cache: TVTamilMVCatalogCache,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val refreshGate = Mutex()
    private val mutableState = MutableStateFlow<TVTamilMVCatalogState>(TVTamilMVCatalogState.Loading())
    val state: StateFlow<TVTamilMVCatalogState> = mutableState.asStateFlow()

    private var lastAttemptMillis: Long? = null

    suspend fun refresh(force: Boolean) {
        if (!refreshGate.tryLock()) return
        val priorState = mutableState.value
        try {
            withContext(ioDispatcher) {
                val now = nowMillis()
                if (!force && lastAttemptMillis?.let { ageMillis(now, it) < AUTO_REFRESH_INTERVAL_MILLIS } == true) {
                    return@withContext
                }
                lastAttemptMillis = now
                // Draw the Tamil MV shelves before the full document is decoded. Everything below
                // this point — the 9MB decode, the token round trip, the artifact fetch — used to
                // run against a blank screen.
                val headStartedAt = System.currentTimeMillis()
                val head = cache.loadHead()
                if (head != null && mutableState.value.visibleSnapshot() == null) {
                    mutableState.value = TVTamilMVCatalogState.Loading(head)
                }
                val headMillis = System.currentTimeMillis() - headStartedAt
                val fullStartedAt = System.currentTimeMillis()
                val cached = cache.load()
                ReceiverDiagnostics.record(
                    "catalog.cache",
                    "headMillis=$headMillis headItems=${head?.tamilMVItemCount ?: 0} " +
                        "fullMillis=${System.currentTimeMillis() - fullStartedAt} fullItems=${cached?.itemCount ?: 0}",
                )
                val tokenInfo = TVPrivateCatalogCredentialPolicy.inspect(settings.load())
                ReceiverDiagnostics.record(
                    "catalog.token",
                    "present=${tokenInfo.present} length=${tokenInfo.length}",
                )
                val token = tokenInfo.value
                if (token == null) {
                    mutableState.value = cached?.let {
                        TVTamilMVCatalogState.Ready(
                            snapshot = it,
                            isStale = true,
                            notice = "Catalog copied securely from the iPhone · server refresh is unavailable",
                        )
                    } ?: TVTamilMVCatalogState.MissingCredential
                    return@withContext
                }
                mutableState.value = TVTamilMVCatalogState.Loading(cached)
                // Painted as soon as enough shelves exist to be worth looking at. Deliberately
                // NOT written to the cache: a partial snapshot on disk would look complete on the
                // next cold start, and the missing shelves would never come back.
                val loaded = runCatching {
                    loader.load(token) { partial ->
                        if (partial.itemCount > 0) {
                            mutableState.value = TVTamilMVCatalogState.Ready(
                                snapshot = partial,
                                notice = "Still loading the rest of your shelves…",
                            )
                        }
                    }
                }.getOrElse { failure ->
                    if (failure is CancellationException) throw failure
                    mutableState.value = TVTamilMVCatalogState.Error(
                        message = failure.userMessage(),
                        cached = cached,
                    )
                    return@withContext
                }
                if (loaded.itemCount == 0) {
                    mutableState.value = cached?.let {
                        TVTamilMVCatalogState.Ready(
                            snapshot = it,
                            isStale = true,
                            notice = "The server snapshot is empty; showing the last saved catalog.",
                        )
                    } ?: TVTamilMVCatalogState.Empty
                    return@withContext
                }
                // A cache write is an optimization. The verified live response remains usable even
                // if the cache directory is temporarily read-only or full.
                runCatching { cache.store(loaded) }
                mutableState.value = TVTamilMVCatalogState.Ready(loaded)
            }
        } catch (cancelled: CancellationException) {
            mutableState.value = priorState
            throw cancelled
        } finally {
            refreshGate.unlock()
        }
    }

    suspend fun clear() = withContext(ioDispatcher) {
        cache.clear()
        lastAttemptMillis = null
        mutableState.value = TVTamilMVCatalogState.MissingCredential
    }

    private fun Throwable.userMessage(): String = when (this) {
        is TVPrivateCatalogHTTPException -> when (statusCode) {
            401, 403 -> "Catalog access was rejected. Re-pair the latest iPhone settings, then retry."
            404 -> "No Tamil MV snapshot is on the server yet. Refresh Tamil MV once on the iPhone, then retry here."
            else -> "Tamil MV refresh failed (server $statusCode). The last saved catalog remains available."
        }
        else -> "Tamil MV could not refresh. Check the TV network and retry. The last saved catalog remains available."
    }

    private companion object {
        const val AUTO_REFRESH_INTERVAL_MILLIS = 5L * 60 * 1_000
        fun ageMillis(now: Long, then: Long): Long = (now - then).coerceAtLeast(0L)
    }
}

internal object TVPrivateCatalogCredentialPolicy {
    private val json = Json { ignoreUnknownKeys = true }

    fun token(state: StoredTVSettingsState): String? {
        return inspect(state).value
    }

    data class TokenInfo(val value: String?, val length: Int) {
        val present: Boolean get() = value != null
    }

    fun inspect(state: StoredTVSettingsState): TokenInfo {
        val raw = (state as? StoredTVSettingsState.Available)?.document?.rawJson
        val root = raw?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        val value = (root?.get("catalogServerToken") as? JsonPrimitive)
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_TOKEN_CHARACTERS && '\r' !in it && '\n' !in it }
        return TokenInfo(value, value?.length ?: 0)
    }

    fun letterboxdUsernames(state: StoredTVSettingsState): List<String> {
        val raw = (state as? StoredTVSettingsState.Available)?.document?.rawJson ?: return emptyList()
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return emptyList()
        val values = buildList {
            (root["letterboxdUsernames"] as? kotlinx.serialization.json.JsonArray)
                ?.forEach { (it as? JsonPrimitive)?.contentOrNull?.let(::add) }
            (root["letterboxdUsername"] as? JsonPrimitive)?.contentOrNull?.let(::add)
        }
        return values.asSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && it.length <= MAX_USERNAME_CHARACTERS }
            .distinctBy(String::lowercase)
            .take(MAX_USERNAMES)
            .toList()
    }

    private const val MAX_TOKEN_CHARACTERS = 8 * 1024
    private const val MAX_USERNAME_CHARACTERS = 128
    private const val MAX_USERNAMES = 24
}

internal class NetworkTVPrivateCatalogLoader(
    private val http: TVPrivateCatalogHTTPClient = URLConnectionPrivateCatalogHTTPClient(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : TVPrivateCatalogLoader {
    override suspend fun load(
        token: String,
        onPartial: (TVTamilMVCatalogSnapshot) -> Unit,
    ): TVTamilMVCatalogSnapshot {
        val manifestBytes = http.get(
            url = CatalogSnapshotContract.privateManifestURL,
            token = token,
            maximumBytes = CatalogSnapshotContract.maximumManifestBytes,
        )
        val manifest = CatalogSnapshotContract.decodeAndVerifyPrivateManifest(manifestBytes, OWNER_ID)
        ReceiverDiagnostics.record("catalog.manifest", "artifacts=${manifest.artifacts.size}")

        // Do not retain unbounded decoded indexes. A Letterboxd account can have hundreds of
        // lists, so retain only bounded rows per list and a bounded number of list descriptors
        // while streaming artifacts. The flat aggregate remains a compatibility fallback; it is
        // intentionally capped, but it must not prevent later Letterboxd lists from being read.
        val popular = mutableListOf<TVTamilMVCatalogItem>()
        val recent = mutableListOf<TVTamilMVCatalogItem>()
        val letterboxd = mutableListOf<TVTamilMVCatalogItem>()
        val friends = mutableListOf<TVTamilMVCatalogItem>()
        data class MutableLetterboxdShelf(
            val id: String,
            val title: String,
            val items: MutableList<TVTamilMVCatalogItem> = mutableListOf(),
            val ids: MutableSet<String> = mutableSetOf(),
        )
        val letterboxdShelves = linkedMapOf<String, MutableLetterboxdShelf>()
        val popularIDs = mutableSetOf<String>()
        val recentIDs = mutableSetOf<String>()
        val letterboxdIDs = mutableSetOf<String>()
        val friendsIDs = mutableSetOf<String>()
        var generatedAt = runCatching { Instant.parse(manifest.createdAt).toEpochMilli() }.getOrNull() ?: nowMillis()

        val relevantArtifacts = manifest.artifacts.filter { artifact ->
            val advertisedCatalogID = artifact.catalogID
            artifact.role.startsWith(PRIVATE_ROLE_PREFIX) ||
                advertisedCatalogID?.startsWith(TAMILMV_CATALOG_PREFIX) == true ||
                advertisedCatalogID?.startsWith(LETTERBOXD_CATALOG_PREFIX) == true
        }
        // Every shelf used to wait for the slowest artifact, because one awaitAll gated the whole
        // catalog. With hundreds of Letterboxd lists that join IS the wait. The fan-out now races
        // a paint deadline: whatever has decoded by then is merged and handed to the UI, and the
        // rest keeps loading into the same accumulators for the complete snapshot that follows.
        // Deliberately NOT its own coroutineScope. That scope waits for every child before it
        // returns, so the deadline below was being measured after the work had already finished
        // and the partial paint could never fire. Verified on the box: a 122-artifact load took
        // 8.5s and never once took the partial branch. Creation and awaiting must share one scope.
        val decoded = coroutineScope {
            val semaphore = Semaphore(ARTIFACT_CONCURRENCY)
            val pendingArtifacts = relevantArtifacts.map { artifact ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        require(artifact.contentType.substringBefore(';') == "application/json") { "artifact_content_type" }
                        val path = CatalogSnapshotContract.privateArtifactPath(artifact.key, OWNER_ID)
                        val bytes = http.get(
                            url = CatalogSnapshotContract.privateArtifactBaseURL + path,
                            token = token,
                            maximumBytes = minOf(artifact.byteCount, CatalogSnapshotContract.maximumCatalogBytes),
                        )
                        artifact to CatalogSnapshotContract.decodeAndVerifyArtifact(bytes, artifact).also {
                            require(
                                it.catalogID.startsWith(TAMILMV_CATALOG_PREFIX) ||
                                    it.catalogID.startsWith(LETTERBOXD_CATALOG_PREFIX),
                            ) { "catalog_id" }
                            require(artifact.catalogID == null || artifact.catalogID == it.catalogID) { "catalog_identity" }
                        }
                    }
                }
            }

        fun snapshot(): TVTamilMVCatalogSnapshot = TVTamilMVCatalogSnapshot(
            schemaVersion = 1,
            generation = manifest.generation,
            generatedAtMillis = generatedAt,
            cachedAtMillis = nowMillis(),
            popular = popular,
            recent = recent,
            letterboxd = letterboxd,
            friends = friends,
            letterboxdShelves = letterboxdShelves.values.map { shelf ->
                TVLetterboxdShelf(shelf.id, shelf.title, shelf.items.toList())
            },
        )

        // The merge is additive, so a second batch appends to the same accumulators rather than
        // rebuilding: merging early costs nothing and cannot double-count, because each artifact
        // is merged exactly once.
        val merged = java.util.concurrent.atomic.AtomicInteger(0)
        fun merge(batch: List<Pair<com.fourseveneightnine.contract.CatalogArtifact, com.fourseveneightnine.contract.CatalogFacetIndex>>) {
        for ((artifact, index) in batch) {
            val advertisedCatalogID = artifact.catalogID
            val advertisedLetterboxd = advertisedCatalogID?.startsWith(LETTERBOXD_CATALOG_PREFIX) == true
            val advertisedFriends = advertisedCatalogID?.contains(":friends-activity:") == true
            if (advertisedLetterboxd && !advertisedFriends &&
                advertisedCatalogID !in letterboxdShelves &&
                letterboxdShelves.size >= MAX_LETTERBOXD_SHELVES
            ) continue
            require(artifact.contentType.substringBefore(';') == "application/json") { "artifact_content_type" }
            runCatching { Instant.parse(index.generatedAt).toEpochMilli() }.getOrNull()?.let {
                generatedAt = maxOf(generatedAt, it)
            }
            when {
                index.catalogID.endsWith(":popular") -> appendItems(
                    index, popular, popularIDs, MAX_TAMIL_ITEMS_PER_SHELF)
                index.catalogID.endsWith(":recent") -> appendItems(
                    index, recent, recentIDs, MAX_TAMIL_ITEMS_PER_SHELF)
                index.catalogID.startsWith(LETTERBOXD_CATALOG_PREFIX) && !index.catalogID.contains(":friends-activity:") -> {
                    appendItems(index, letterboxd, letterboxdIDs, MAX_OTHER_ITEMS_PER_SHELF)
                    val shelfID = index.catalogID
                    val shelf = letterboxdShelves.getOrPut(shelfID) {
                        MutableLetterboxdShelf(
                            id = shelfID,
                            title = artifact.displayName?.trim()?.takeIf { it.isNotEmpty() }
                                ?: shelfTitle(shelfID),
                        )
                    }
                    appendItems(index, shelf.items, shelf.ids, MAX_OTHER_ITEMS_PER_SHELF)
                }
                index.catalogID.contains(":friends-activity:") -> appendItems(
                    index, friends, friendsIDs, MAX_OTHER_ITEMS_PER_SHELF)
            }
        }
            merged.addAndGet(batch.size)
        }

        val complete = withTimeoutOrNull(PARTIAL_PAINT_DEADLINE_MILLIS) { pendingArtifacts.awaitAll() }
        if (complete != null) {
            merge(complete)
        } else {
            // Merge and paint what is already decoded, then wait for the remainder. Nothing is
            // cancelled: a slow shelf still arrives, it just stops holding the fast ones hostage.
            val ready = pendingArtifacts.filter { it.isCompleted && !it.isCancelled }
            merge(ready.mapNotNull { runCatching { it.getCompleted() }.getOrNull() })
            if (merged.get() > 0) {
                ReceiverDiagnostics.record("catalog.partial", "artifacts=${merged.get()}/${pendingArtifacts.size}")
                runCatching { onPartial(snapshot()) }
            }
            val remaining = pendingArtifacts.filterNot { it in ready }
            merge(remaining.awaitAll())
            }

            ReceiverDiagnostics.record(
                "catalog.snapshot",
                "popular=${popular.size} recent=${recent.size} letterboxd=${letterboxd.size} " +
                    "letterboxdShelves=${letterboxdShelves.size} friends=${friends.size}",
            )
            snapshot()
        }
        return decoded
    }

    private fun shelfTitle(catalogID: String): String = catalogID
        .substringAfterLast(':')
        .replace('-', ' ')
        .replace('_', ' ')
        .trim()
        .ifEmpty { "Letterboxd list" }
        .replaceFirstChar { it.titlecase() }

    private fun appendItems(
        index: com.fourseveneightnine.contract.CatalogFacetIndex,
        destination: MutableList<TVTamilMVCatalogItem>,
        seenIDs: MutableSet<String>,
        maximumItems: Int,
    ) {
        if (destination.size >= maximumItems) return
        for (row in index.rows) {
            val item = displayItem(row) ?: continue
            if (seenIDs.add(item.id)) destination += item
            if (destination.size >= maximumItems) return
        }
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

    private fun safeArtworkURL(value: String?): String? {
        val candidate = value?.trim()?.takeIf { it.length <= MAX_URL_CHARACTERS } ?: return null
        return runCatching { URI(candidate) }.getOrNull()
            ?.takeIf { it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null }
            ?.toString()
    }

    private companion object {
        const val OWNER_ID = "owner"
        const val PRIVATE_ROLE_PREFIX = "private-"
        const val TAMILMV_CATALOG_PREFIX = "tamilmv:"
        const val LETTERBOXD_CATALOG_PREFIX = "letterboxd:"
        const val MAX_TAMIL_ITEMS_PER_SHELF = 2_000
        const val MAX_OTHER_ITEMS_PER_SHELF = 1_000
        const val MAX_LETTERBOXD_SHELVES = 256
        const val ARTIFACT_CONCURRENCY = 6

        /**
         * How long the catalog waits for EVERY shelf before painting the ones it already has.
         * Short on purpose: past this point the viewer is staring at an empty screen for shelves
         * that are already decoded and sitting in memory.
         */
        const val PARTIAL_PAINT_DEADLINE_MILLIS = 1_200L
        const val MAX_URL_CHARACTERS = 2_048
        const val MAX_OVERVIEW_CHARACTERS = 4_000
    }
}

internal fun interface TVPrivateCatalogHTTPClient {
    fun get(url: String, token: String, maximumBytes: Int): ByteArray
}

internal class TVPrivateCatalogHTTPException(val statusCode: Int) : Exception("http_status")

internal class URLConnectionPrivateCatalogHTTPClient : TVPrivateCatalogHTTPClient {
    override fun get(url: String, token: String, maximumBytes: Int): ByteArray {
        require(maximumBytes > 0) { "maximum_bytes" }
        val parsed = URL(url)
        require(parsed.protocol == "https" && parsed.host == "api.4789library.com" && parsed.userInfo == null) {
            "catalog_host"
        }
        val connection = parsed.openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = READ_TIMEOUT_MILLIS
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("X-4789-Owner", "owner")
        return try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                connection.errorStream?.close()
                throw TVPrivateCatalogHTTPException(status)
            }
            BoundedCatalogResponse.read(
                input = connection.inputStream,
                declaredBytes = connection.contentLengthLong,
                maximumBytes = maximumBytes,
            )
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 4_000
        const val READ_TIMEOUT_MILLIS = 12_000
    }
}

internal object BoundedCatalogResponse {
    fun read(input: java.io.InputStream, declaredBytes: Long, maximumBytes: Int): ByteArray {
        require(declaredBytes == -1L || declaredBytes in 1..maximumBytes.toLong()) { "content_length" }
        return input.use { stream ->
            val output = ByteArrayOutputStream(minOf(maximumBytes, DEFAULT_BUFFER_BYTES))
            val buffer = ByteArray(DEFAULT_BUFFER_BYTES)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size() + count <= maximumBytes) { "response_size" }
                output.write(buffer, 0, count)
            }
            output.toByteArray().also { require(it.isNotEmpty()) { "empty_response" } }
        }
    }

    private const val DEFAULT_BUFFER_BYTES = 16 * 1024
}

internal class AtomicTVTamilMVCatalogCache(context: Context) : TVTamilMVCatalogCache {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    // This snapshot is the receiver's durable, phone-independent catalog. Android may evict
    // cacheDir at any time, so keep the sanitized metadata in app-private filesDir instead.
    // Credentials and resolved playback URLs are never part of this document.
    private val atomicFile = AtomicFile(java.io.File(context.applicationContext.filesDir, CACHE_FILE_NAME))
    private val headFile = AtomicFile(java.io.File(context.applicationContext.filesDir, HEAD_FILE_NAME))

    override fun load(): TVTamilMVCatalogSnapshot? = read(atomicFile)

    override fun loadHead(): TVTamilMVCatalogSnapshot? = read(headFile)

    override fun store(snapshot: TVTamilMVCatalogSnapshot) {
        TVTamilMVCatalogImportPolicy.validate(snapshot)
        write(atomicFile, snapshot)
        // Best effort. The head is an optimization for the next cold start; failing to write it
        // must never cost the caller the real catalog it just fetched.
        runCatching { write(headFile, snapshot.withoutLetterboxd()) }
    }

    override fun clear() {
        atomicFile.delete()
        headFile.delete()
    }

    private fun read(file: AtomicFile): TVTamilMVCatalogSnapshot? {
        val base = file.baseFile
        if (!base.isFile || base.length() !in 1..MAX_CACHE_BYTES.toLong()) return null
        return runCatching {
            TVTamilMVCatalogImportPolicy.validate(
                json.decodeFromString<TVTamilMVCatalogSnapshot>(file.readFully().decodeToString()),
            )
        }.getOrNull()
    }

    private fun write(file: AtomicFile, snapshot: TVTamilMVCatalogSnapshot) {
        val bytes = json.encodeToString(snapshot).encodeToByteArray()
        require(bytes.size <= MAX_CACHE_BYTES)
        var output: java.io.FileOutputStream? = null
        try {
            output = file.startWrite()
            output.write(bytes)
            file.finishWrite(output)
        } catch (failure: Throwable) {
            output?.let(file::failWrite)
            throw failure
        }
    }

    private companion object {
        const val CACHE_FILE_NAME = "private-tamilmv-catalog-v1.json"
        const val HEAD_FILE_NAME = "private-tamilmv-head-v1.json"
        // Per-list Letterboxd shelves are retained so the TV can expose every synced list rather
        // than flattening the account into one 1,000-row fallback. Keep enough room for a large
        // account's sanitized metadata while still bounding private storage use.
        const val MAX_CACHE_BYTES = 32 * 1024 * 1024
    }
}
