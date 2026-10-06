package com.fourseveneightnine.tv.client.data.catalog

import com.fourseveneightnine.contract.CatalogArtifact
import com.fourseveneightnine.contract.CatalogFacetIndex
import com.fourseveneightnine.contract.CatalogFacetRow
import com.fourseveneightnine.contract.CatalogManifestPayload
import com.fourseveneightnine.contract.CatalogSnapshotContract
import java.io.File
import java.net.URI
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * Local-first signed catalog snapshots, for both the public TMDB shelves and the owner's private
 * catalog (Tamil MV Popular and Recent, the Letterboxd lists, New From Friends).
 *
 * The contract, from `docs/TV_APP_REBUILD_PLAN.md` §5.4:
 *
 * 1. Paint the head file from disk before any network request.
 * 2. Revalidate the manifest with `If-None-Match`; fetch only the changed shards.
 * 3. Bounded shard concurrency, and a [PARTIAL_PAINT_DEADLINE_MILLIS] deadline after which
 *    whatever has decoded is painted. A partial paint reaches memory only.
 * 4. Promote a complete generation atomically. Old generations are evicted under
 *    [EVICTION_BUDGET_BYTES], always keeping the last complete one.
 * 5. A failed refresh — network, HTTP or signature — keeps the last known-good shelves on screen.
 *
 * Settings values arrive as functions, not as a settings object, so this class never depends on
 * how settings are stored.
 */
public class SnapshotStore(
    filesDir: File,
    private val http: CatalogHttp,
    private val verifier: SnapshotVerifier = ContractSnapshotVerifier,
    private val tokenProvider: suspend () -> String? = { null },
    private val letterboxdUsernames: suspend () -> List<String> = { emptyList() },
    private val ownerId: String = OkHttpCatalogHttp.OWNER_ID,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    private val evictionBudgetBytes: Long = EVICTION_BUDGET_BYTES,
) {
    /** Production wiring: OkHttp plus the frozen contract's Ed25519 verification. */
    public constructor(
        filesDir: File,
        okHttp: OkHttpClient,
        verifier: SnapshotVerifier = ContractSnapshotVerifier,
        tokenProvider: suspend () -> String? = { null },
        letterboxdUsernames: suspend () -> List<String> = { emptyList() },
    ) : this(
        filesDir = filesDir,
        http = OkHttpCatalogHttp(okHttp),
        verifier = verifier,
        tokenProvider = tokenProvider,
        letterboxdUsernames = letterboxdUsernames,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val disk = SnapshotDiskCache(filesDir, json)

    private val refreshGate = Mutex()
    private val publishGate = Mutex()
    private val shelvesBySource = linkedMapOf<SnapshotSource, List<Shelf>>()
    private val promotedGeneration = mutableMapOf<SnapshotSource, String>()

    private val publicCacheGate = Any()
    private var publicCacheDirectory: String? = null
    private var publicCacheItems: List<CatalogItem> = emptyList()

    private val mutableShelves = MutableStateFlow<List<Shelf>>(emptyList())
    private val mutableStatus = MutableStateFlow(SnapshotStatus.idle())

    @Volatile
    private var hydrated = false

    /** Every shelf, in Home row order (spec §3.4 rows 4 to 6). */
    public fun shelves(): StateFlow<List<Shelf>> = mutableShelves.asStateFlow()

    /** One row per source for Settings → Jobs. */
    public fun status(): StateFlow<SnapshotStatus> = mutableStatus.asStateFlow()

    /**
     * Metadata-only rows from the last complete, atomically promoted public TMDB snapshot.
     *
     * Android TV's exported search provider uses this synchronous read path. It deliberately does
     * not hydrate or refresh the store: a system suggestion query must never trigger network work,
     * and it must never cross into the private catalog. The decoded document is retained until the
     * atomic `current` pointer changes so typing a title does not parse the same JSON per letter.
     */
    public fun cachedPublicTmdbItems(): List<CatalogItem> {
        val directory = disk.currentDirectoryName(SnapshotSource.PUBLIC_TMDB)
        synchronized(publicCacheGate) {
            if (directory == publicCacheDirectory) return publicCacheItems
            publicCacheItems = disk.readFull(SnapshotSource.PUBLIC_TMDB)
                ?.takeIf { it.source == SnapshotSource.PUBLIC_TMDB.wire }
                ?.shelves
                .orEmpty()
                .filter { it.kind == ShelfKind.TMDB_MOVIES || it.kind == ShelfKind.TMDB_SERIES }
                .flatMap(Shelf::items)
            publicCacheDirectory = directory
            return publicCacheItems
        }
    }

    /**
     * Paints from disk. Head file first, then the full document.
     *
     * Call this before the first [refresh]; [refresh] also calls it once if nothing is on screen.
     */
    public suspend fun hydrate(): Unit = withContext(ioDispatcher) {
        hydrated = true
        for (source in SnapshotSource.entries) {
            val head = runCatching { disk.readHead(source) }.getOrNull()
            if (head != null && head.shelves.isNotEmpty()) publish(source, head, SnapshotState.READY)
            val full = runCatching { disk.readFull(source) }.getOrNull()
            if (full != null) publish(source, full, SnapshotState.READY)
        }
    }

    /**
     * Revalidates both manifests and promotes anything that changed.
     *
     * Overlapping calls are dropped, not queued: a second refresh would ask the same server for the
     * same generation. [force] skips the `If-None-Match` shortcut and refetches the shards.
     */
    public suspend fun refresh(force: Boolean = false) {
        if (!refreshGate.tryLock()) return
        try {
            withContext(ioDispatcher) {
                if (!hydrated) hydrate()
                coroutineScope {
                    launch { runCatching { refreshSource(SnapshotSource.PUBLIC_TMDB, force) } }
                    launch { runCatching { refreshSource(SnapshotSource.PRIVATE_CATALOG, force) } }
                }
                runCatching { disk.evict(evictionBudgetBytes) }
            }
        } finally {
            refreshGate.unlock()
        }
    }

    /** Forgets one source's cached generations. Used when the owner unpairs the phone. */
    public suspend fun clear(source: SnapshotSource): Unit = withContext(ioDispatcher) {
        // Wait for a refresh that already captured the previous owner's token. Clearing outside
        // this gate allowed that request to finish afterwards and republish private shelves that
        // the user had just deleted.
        refreshGate.withLock {
            disk.clear(source)
            promotedGeneration.remove(source)
            publishGate.lockAndSet { shelvesBySource.remove(source) }
            setStatus(
                source = source,
                state = SnapshotState.IDLE,
                message = null,
                generation = null,
                generatedAtMillis = null,
                itemCount = 0,
                complete = false,
            )
        }
    }

    /**
     * Imports the display-only catalog copied directly from a trusted phone pairing.
     *
     * The phone transfer remains a fallback beside the signed server path, not a second source of
     * truth. Whichever complete snapshot has the newest source timestamp wins. This matters when
     * a phone carries a fresh Tamil MV refresh but the promoted private server generation is stale,
     * and equally prevents an old QR payload from replacing a newer server snapshot.
     *
     * @return true only when [shelves] replaced the currently promoted private generation.
     */
    public suspend fun importPrivateSnapshot(
        generation: String,
        generatedAtMillis: Long,
        shelves: List<Shelf>,
    ): Boolean = withContext(ioDispatcher) {
        require(generation.isNotBlank() && generation.length <= 500) { "catalog_import_generation" }
        require(generatedAtMillis >= 0) { "catalog_import_time" }
        require(shelves.isNotEmpty() && shelves.any { it.items.isNotEmpty() }) { "catalog_import_empty" }
        require(shelves.all { shelf ->
            shelf.kind in PRIVATE_SHELF_KINDS &&
                shelf.generation == generation &&
                shelf.generatedAtMillis == generatedAtMillis &&
                shelf.complete
        }) { "catalog_import_shelf" }

        refreshGate.withLock {
            val current = newestStoredSnapshot(SnapshotSource.PRIVATE_CATALOG)
            // A timestamp tie belongs to the signed server/disk generation already present. The
            // direct phone envelope is bounded to 2 MiB and may preserve list names with zero or
            // truncated items, so accepting it on a tie would discard richer complete data.
            if (current != null &&
                (current.generatedAtMillis >= generatedAtMillis || current.generation == generation)
            ) {
                return@withLock false
            }
            val stored = StoredSnapshot(
                source = SnapshotSource.PRIVATE_CATALOG.wire,
                generation = generation,
                generatedAtMillis = generatedAtMillis,
                cachedAtMillis = now(),
                shelves = shelves,
            )
            runCatching {
                disk.promote(SnapshotSource.PRIVATE_CATALOG, stored) { isHeadShelf(it) }
            }
            publish(SnapshotSource.PRIVATE_CATALOG, stored, SnapshotState.READY)
            true
        }
    }

    // ---------------------------------------------------------------- refresh

    private suspend fun refreshSource(source: SnapshotSource, force: Boolean) {
        setStatus(source, SnapshotState.LOADING)
        val token = if (source == SnapshotSource.PRIVATE_CATALOG) {
            tokenProvider()?.takeIf { it.isNotBlank() } ?: run {
                fail(source, "No catalog server token yet. Re-pair the iPhone, then retry.")
                return
            }
        } else {
            null
        }

        val manifestUrl = when (source) {
            SnapshotSource.PUBLIC_TMDB -> CatalogSnapshotContract.manifestURL
            SnapshotSource.PRIVATE_CATALOG -> CatalogSnapshotContract.privateManifestURL
        }
        val cachedEtag = disk.etag(source).takeIf { !force }

        val response = try {
            http.get(manifestUrl, token, cachedEtag, CatalogSnapshotContract.maximumManifestBytes)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            fail(source, failure.userMessage())
            return
        }

        if (response is CatalogHttpResult.NotModified) {
            // Nothing changed. The cached generation is still the right answer.
            setStatus(source, SnapshotState.READY, message = null)
            return
        }

        val body = response as CatalogHttpResult.Body
        val manifest = try {
            verifier.manifest(body.bytes, source, ownerId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // A bad signature is not a reason to drop shelves the viewer is already reading.
            fail(source, "The catalog signature did not verify. Keeping the last saved shelves.")
            return
        }

        if (!force && manifest.generation == promotedGeneration[source]) {
            disk.storeEtag(source, body.etag)
            setStatus(source, SnapshotState.READY, message = null)
            return
        }

        val stored = try {
            fetchGeneration(source, manifest, token)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            fail(source, failure.userMessage())
            return
        }

        if (stored.shelves.all { it.items.isEmpty() }) {
            fail(source, "The server snapshot is empty. Showing the last saved shelves.")
            return
        }

        // A direct phone transfer can be newer than the server's currently promoted generation.
        // Do not let a forced refresh roll that catalog backwards while the server catches up.
        val current = newestStoredSnapshot(source)
        if (current != null && current.generatedAtMillis > stored.generatedAtMillis) {
            disk.storeEtag(source, body.etag)
            setStatus(source, SnapshotState.READY, message = null)
            return
        }

        // Cache writes are an optimisation. A verified response stays usable when the filesystem
        // refuses it.
        runCatching { disk.promote(source, stored) { isHeadShelf(it) } }
        disk.storeEtag(source, body.etag)
        promotedGeneration[source] = stored.generation
        publish(source, stored, SnapshotState.READY)
    }

    /**
     * Fans out over the manifest's artifacts.
     *
     * TRAP, paid for once on the box: the `async` calls and the `awaitAll` must share ONE scope. A
     * nested `coroutineScope` around the creation waits for every child before it returns, so the
     * deadline below was measured after the work had already finished and the partial paint could
     * never fire. A 122-artifact load took 8.5 s and never took the partial branch.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun fetchGeneration(
        source: SnapshotSource,
        manifest: CatalogManifestPayload,
        token: String?,
    ): StoredSnapshot = coroutineScope {
        val usernames = letterboxdUsernames()
        val artifacts = manifest.artifacts.filter { isRelevant(source, it) }
        val gate = Semaphore(SHARD_CONCURRENCY)
        val pending: List<Deferred<Pair<CatalogArtifact, CatalogFacetIndex>>> = artifacts.map { artifact ->
            async(ioDispatcher) {
                gate.withPermit { artifact to fetchArtifact(source, artifact, token) }
            }
        }
        val builder = ShelfBuilder(source, manifest, usernames, now)

        val everything = withTimeoutOrNull(PARTIAL_PAINT_DEADLINE_MILLIS) { pending.awaitAll() }
        if (everything != null) {
            builder.merge(everything)
        } else {
            // Paint what has already decoded, then wait for the rest. Nothing is cancelled: a slow
            // shelf still arrives, it just stops holding the fast ones hostage.
            val ready = pending.filter { it.isCompleted && !it.isCancelled }
            builder.merge(ready.mapNotNull { runCatching { it.getCompleted() }.getOrNull() })
            if (builder.itemCount > 0) {
                publishPartial(source, builder.build(complete = false))
            }
            builder.merge(pending.filterNot { it in ready }.awaitAll())
        }
        builder.build(complete = true)
    }

    private suspend fun fetchArtifact(
        source: SnapshotSource,
        artifact: CatalogArtifact,
        token: String?,
    ): CatalogFacetIndex {
        require(artifact.contentType.substringBefore(';') == "application/json") { "artifact_content_type" }
        val url = when (source) {
            SnapshotSource.PUBLIC_TMDB ->
                CatalogSnapshotContract.artifactBaseURL + CatalogSnapshotContract.artifactPath(artifact.key)
            SnapshotSource.PRIVATE_CATALOG ->
                CatalogSnapshotContract.privateArtifactBaseURL +
                    CatalogSnapshotContract.privateArtifactPath(artifact.key, ownerId)
        }
        val result = http.get(
            url = url,
            token = token,
            ifNoneMatch = null,
            maximumBytes = minOf(artifact.byteCount, CatalogSnapshotContract.maximumCatalogBytes),
        )
        val body = result as? CatalogHttpResult.Body ?: error("artifact_not_modified")
        return verifier.artifact(body.bytes, artifact)
    }

    private fun isRelevant(source: SnapshotSource, artifact: CatalogArtifact): Boolean = when (source) {
        SnapshotSource.PUBLIC_TMDB -> artifact.role in PUBLIC_ROLES
        SnapshotSource.PRIVATE_CATALOG -> artifact.role.startsWith(PRIVATE_ROLE_PREFIX) ||
            artifact.catalogID?.startsWith(TAMILMV_PREFIX) == true ||
            artifact.catalogID?.startsWith(LETTERBOXD_PREFIX) == true
    }

    // ---------------------------------------------------------------- publication

    private suspend fun publish(source: SnapshotSource, stored: StoredSnapshot, state: SnapshotState) {
        promotedGeneration[source] = stored.generation
        publishGate.lockAndSet { shelvesBySource[source] = stored.shelves }
        setStatus(
            source = source,
            state = state,
            message = null,
            generation = stored.generation,
            generatedAtMillis = stored.generatedAtMillis,
            itemCount = stored.itemCount,
            complete = stored.shelves.all(Shelf::complete),
        )
    }

    private fun newestStoredSnapshot(source: SnapshotSource): StoredSnapshot? {
        val diskSnapshot = runCatching { disk.readFull(source) }.getOrNull()
        val currentStatus = mutableStatus.value.of(source)
        val memorySnapshot = currentStatus?.generation?.let { generation ->
            StoredSnapshot(
                source = source.wire,
                generation = generation,
                generatedAtMillis = currentStatus.generatedAtMillis ?: return@let null,
                cachedAtMillis = currentStatus.lastRunMillis ?: 0,
                shelves = shelvesBySource[source].orEmpty(),
            )
        }
        return listOfNotNull(diskSnapshot, memorySnapshot).maxByOrNull(StoredSnapshot::generatedAtMillis)
    }

    /** Memory only. A partial generation on disk would look complete on the next cold start. */
    private suspend fun publishPartial(source: SnapshotSource, stored: StoredSnapshot) {
        // A slow older server generation must never temporarily replace a newer phone import.
        // The final freshness check happens after every shard arrives; partial paint needs the
        // same guard because it publishes while that fetch is still in flight.
        val current = newestStoredSnapshot(source)
        if (current != null && current.generatedAtMillis >= stored.generatedAtMillis) return
        publishGate.lockAndSet { shelvesBySource[source] = stored.shelves }
        setStatus(
            source = source,
            state = SnapshotState.LOADING,
            message = "Still loading the rest of your shelves…",
            generation = stored.generation,
            generatedAtMillis = stored.generatedAtMillis,
            itemCount = stored.itemCount,
            complete = false,
        )
    }

    /** One writer at a time, and every write republishes the whole ordered shelf list. */
    private suspend fun Mutex.lockAndSet(block: () -> Unit) {
        lock()
        try {
            block()
            mutableShelves.value = shelvesBySource.values.flatten().sortedBy { it.kind.ordinal }
        } finally {
            unlock()
        }
    }

    private fun fail(source: SnapshotSource, message: String) {
        val existing = mutableStatus.value.of(source)
        setStatus(
            source = source,
            state = SnapshotState.FAILED,
            message = message,
            generation = existing?.generation,
            generatedAtMillis = existing?.generatedAtMillis,
            itemCount = existing?.itemCount ?: 0,
            complete = existing?.complete ?: false,
        )
    }

    private fun setStatus(
        source: SnapshotSource,
        state: SnapshotState,
        message: String? = mutableStatus.value.of(source)?.message,
        generation: String? = mutableStatus.value.of(source)?.generation,
        generatedAtMillis: Long? = mutableStatus.value.of(source)?.generatedAtMillis,
        itemCount: Int = mutableStatus.value.of(source)?.itemCount ?: 0,
        complete: Boolean = mutableStatus.value.of(source)?.complete ?: false,
    ) {
        val row = SourceStatus(
            source = source,
            state = state,
            lastRunMillis = now(),
            generation = generation,
            generatedAtMillis = generatedAtMillis,
            itemCount = itemCount,
            complete = complete,
            message = message,
        )
        mutableStatus.value = SnapshotStatus(
            mutableStatus.value.sources.map { if (it.source == source) row else it },
        )
    }

    private fun Throwable.userMessage(): String = when (this) {
        is CatalogHttpException -> when (statusCode) {
            401, 403 -> "Catalog access was rejected. Re-pair the latest iPhone settings, then retry."
            404 -> "No snapshot is on the server yet. Refresh once on the iPhone, then retry here."
            else -> "The catalog refresh failed (server $statusCode). The last saved shelves remain."
        }
        else -> "The catalog could not refresh. Check the TV network and retry."
    }

    public companion object {
        /**
         * How long the fan-out waits for EVERY shard before painting the ones it already has. Short
         * on purpose: past this point the viewer stares at an empty screen for shelves that are
         * already decoded and sitting in memory.
         */
        public const val PARTIAL_PAINT_DEADLINE_MILLIS: Long = 1_200L

        /** Shards in flight at once. Bounded concurrency took a box refresh from ~43 s to ~8 s. */
        public const val SHARD_CONCURRENCY: Int = 4

        /** Whole on-disk snapshot budget, across both sources and every kept generation. */
        public const val EVICTION_BUDGET_BYTES: Long = 64L * 1024 * 1024

        internal const val TAMILMV_PREFIX = "tamilmv:"
        internal const val LETTERBOXD_PREFIX = "letterboxd:"
        internal const val FRIENDS_MARKER = ":friends-activity:"
        internal const val PRIVATE_ROLE_PREFIX = "private-"
        internal val PUBLIC_ROLES = setOf(
            "popular-movies-popular",
            "popular-movies-recent",
            "popular-tv-popular",
            "popular-tv-recent",
        )

        internal const val MAX_TAMIL_ITEMS = 2_000
        internal const val MAX_OTHER_ITEMS = 1_000
        internal const val MAX_LETTERBOXD_SHELVES = 256
        internal const val MAX_URL_CHARACTERS = 2_048
        internal const val MAX_OVERVIEW_CHARACTERS = 4_000

        private val PRIVATE_SHELF_KINDS = setOf(
            ShelfKind.TAMILMV_POPULAR,
            ShelfKind.TAMILMV_RECENT,
            ShelfKind.LETTERBOXD,
            ShelfKind.LETTERBOXD_FRIENDS,
        )

        internal fun isHeadShelf(shelf: Shelf): Boolean =
            shelf.kind == ShelfKind.TAMILMV_POPULAR ||
                shelf.kind == ShelfKind.TAMILMV_RECENT ||
                shelf.kind == ShelfKind.TMDB_MOVIES ||
                shelf.kind == ShelfKind.TMDB_SERIES

        /** Sanitised display row. Anything that fails a check is dropped, never repaired. */
        internal fun displayItem(row: CatalogFacetRow): CatalogItem? {
            val id = row.canonicalID.trim().takeIf { it.isNotEmpty() && it.length <= 500 } ?: return null
            val title = row.title.trim().takeIf { it.isNotEmpty() && it.length <= 500 } ?: return null
            val mediaType = row.mediaType.takeIf { it == "movie" || it == "series" } ?: return null
            val tail = id.substringAfterLast("::")
            return CatalogItem(
                canonicalId = id,
                mediaType = mediaType,
                title = title,
                year = row.year?.takeIf { it in 1870..2200 },
                posterUrl = safeArtworkUrl(row.posterURL),
                backdropUrl = safeArtworkUrl(row.backdropURL),
                imdbId = tail.takeIf { it.matches(Regex("tt[0-9]{5,16}")) },
                tmdbId = tail.removePrefix("tmdb:").toIntOrNull()?.takeIf { it > 0 },
                overview = row.overview?.trim()?.takeIf(String::isNotEmpty)?.take(MAX_OVERVIEW_CHARACTERS),
                genres = row.genres.map(String::trim).filter(String::isNotEmpty).distinct().take(8),
            )
        }

        internal fun safeArtworkUrl(value: String?): String? = value?.trim()
            ?.takeIf { it.length <= MAX_URL_CHARACTERS }
            ?.let { candidate -> runCatching { URI(candidate) }.getOrNull() }
            ?.takeIf { it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null }
            ?.toString()

        internal fun epochMillisOrNull(value: String?): Long? =
            value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    }
}
