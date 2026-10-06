package com.fourseveneightnine.tv.client.data.library

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The one way into [LibraryDatabase].
 *
 * Everything the UI needs is a `Flow`, so a write anywhere repaints every screen that shows it.
 * Nothing here talks to the network.
 */
public class LibraryRepository(
    private val database: LibraryDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val progressDao = database.watchProgressDao()
    private val recentDao = database.recentDao()
    private val collectionDao = database.collectionDao()
    private val addonHealthDao = database.addonHealthDao()
    private val jobDao = database.jobDao()

    // ---------------------------------------------------------------- continue watching

    /**
     * Home row 1.
     *
     * Merges local progress with the phone's mirrored recents. The box's own playback is its own
     * truth, so for one canonical id the local row wins outright: the phone's copy of a title you
     * just watched here is always the older answer. Rows under [MINIMUM_FRACTION] have not really
     * started and rows over [FINISHED_FRACTION] are finished; neither belongs on a "continue" shelf.
     *
     * A row whose duration is unknown is kept. There is no progress to judge it by, and dropping it
     * would silently lose every phone row that arrived without a duration.
     */
    public fun continueWatching(): Flow<List<ContinueItem>> =
        combine(progressDao.observeAll(), recentDao.observeAll()) { progress, recents ->
            mergeContinueWatching(progress, recents)
        }.distinctUntilChanged()

    internal fun mergeContinueWatching(
        progress: List<WatchProgressEntity>,
        recents: List<RecentEntity>,
    ): List<ContinueItem> {
        val recentById = recents.associateBy(RecentEntity::canonicalId)
        val progressById = progress.associateBy(WatchProgressEntity::canonicalId)
        val merged = LinkedHashMap<String, ContinueItem>()

        // Phone mirror first, so a local row can overwrite it below.
        for (recent in recents) {
            if (RowOrigin.fromWire(recent.origin) != RowOrigin.PHONE) continue
            val mirrored = progressById[recent.canonicalId]
                ?.takeIf { RowOrigin.fromWire(it.source) == RowOrigin.PHONE }
            merged[recent.canonicalId] = ContinueItem(
                canonicalId = recent.canonicalId,
                mediaType = recent.mediaType,
                title = recent.title,
                posterUrl = recent.posterUrl,
                backdropUrl = recent.backdropUrl,
                season = mirrored?.season ?: recent.season,
                episode = mirrored?.episode ?: recent.episode,
                positionMs = mirrored?.positionMs ?: 0L,
                durationMs = mirrored?.durationMs ?: 0L,
                lastActivityMillis = maxOf(recent.lastPlayedAt, mirrored?.updatedAt ?: 0L),
                origin = RowOrigin.PHONE,
            )
        }

        for (row in progress) {
            if (RowOrigin.fromWire(row.source) != RowOrigin.LOCAL) continue
            val meta = recentById[row.canonicalId]
            merged[row.canonicalId] = ContinueItem(
                canonicalId = row.canonicalId,
                mediaType = row.mediaType,
                title = meta?.title ?: row.canonicalId,
                posterUrl = meta?.posterUrl,
                backdropUrl = meta?.backdropUrl,
                season = row.season ?: meta?.season,
                episode = row.episode ?: meta?.episode,
                positionMs = row.positionMs,
                durationMs = row.durationMs,
                lastActivityMillis = maxOf(row.updatedAt, meta?.lastPlayedAt ?: 0L),
                origin = RowOrigin.LOCAL,
            )
        }

        return merged.values
            .filter(::isWorthContinuing)
            .sortedByDescending(ContinueItem::lastActivityMillis)
    }

    private fun isWorthContinuing(item: ContinueItem): Boolean {
        if (item.durationMs <= 0L) return true
        val fraction = item.progressFraction
        return fraction >= MINIMUM_FRACTION && fraction <= FINISHED_FRACTION
    }

    /** Called every 15 s while playing, and on pause, stop and background. */
    public suspend fun writeProgress(
        canonicalId: String,
        mediaType: String,
        season: Int? = null,
        episode: Int? = null,
        positionMs: Long,
        durationMs: Long,
    ) {
        progressDao.upsert(
            WatchProgressEntity(
                canonicalId = canonicalId,
                mediaType = mediaType,
                season = season,
                episode = episode,
                positionMs = positionMs.coerceAtLeast(0L),
                durationMs = durationMs.coerceAtLeast(0L),
                updatedAt = now(),
                source = RowOrigin.LOCAL.wire,
            ),
        )
    }

    /**
     * Marks a title finished, which is the same thing as writing 100% progress: the Continue row
     * drops it on the next emission and the Detail screen's tick turns on.
     */
    public suspend fun markWatched(
        canonicalId: String,
        mediaType: String,
        season: Int? = null,
        episode: Int? = null,
    ) {
        val existing = progressDao.find(canonicalId)
        val duration = existing?.durationMs?.takeIf { it > 0L } ?: 1L
        progressDao.upsert(
            WatchProgressEntity(
                canonicalId = canonicalId,
                mediaType = mediaType,
                season = season ?: existing?.season,
                episode = episode ?: existing?.episode,
                positionMs = duration,
                durationMs = duration,
                updatedAt = now(),
                source = RowOrigin.LOCAL.wire,
            ),
        )
    }

    /**
     * Replaces the phone's mirror wholesale, both the recents and their progress.
     *
     * Today's rule, kept: a title the viewer removed on the phone must disappear here too, and a
     * merge would leave it on the TV forever.
     */
    public suspend fun replacePhoneRecents(rows: List<PhoneRecentRow>) {
        database.withTransaction {
            recentDao.deleteByOrigin(RowOrigin.PHONE.wire)
            progressDao.deleteBySource(RowOrigin.PHONE.wire)
            val deduplicated = rows.distinctBy(PhoneRecentRow::canonicalId)
            // Everything left in `recent` is the box's own. A title the box played is its own truth,
            // so the phone's copy only fills the gaps in it — poster, backdrop, episode numbers.
            val (enriching, mirroring) = deduplicated.partition { recentDao.find(it.canonicalId) != null }
            for (row in enriching) {
                val local = recentDao.find(row.canonicalId) ?: continue
                recentDao.upsert(
                    local.copy(
                        posterUrl = local.posterUrl ?: row.posterUrl,
                        backdropUrl = local.backdropUrl ?: row.backdropUrl,
                        season = local.season ?: row.season,
                        episode = local.episode ?: row.episode,
                    ),
                )
            }
            recentDao.upsertAll(
                mirroring.map { row ->
                    RecentEntity(
                        canonicalId = row.canonicalId,
                        title = row.title,
                        posterUrl = row.posterUrl,
                        backdropUrl = row.backdropUrl,
                        mediaType = row.mediaType,
                        season = row.season,
                        episode = row.episode,
                        lastPlayedAt = row.lastPlayedAt,
                        origin = RowOrigin.PHONE.wire,
                    )
                },
            )
            progressDao.upsertAll(
                mirroring
                    // A local row for the same title is the newer truth. Never overwrite it.
                    .filter { progressDao.find(it.canonicalId) == null }
                    .filter { it.positionMs > 0L || it.durationMs > 0L }
                    .map { row ->
                        WatchProgressEntity(
                            canonicalId = row.canonicalId,
                            mediaType = row.mediaType,
                            season = row.season,
                            episode = row.episode,
                            positionMs = row.positionMs,
                            durationMs = row.durationMs,
                            updatedAt = row.lastPlayedAt,
                            source = RowOrigin.PHONE.wire,
                        )
                    },
            )
        }
    }

    /** The box played this itself. Writes the display metadata the Continue card needs. */
    public suspend fun recordLocalPlay(
        canonicalId: String,
        mediaType: String,
        title: String,
        posterUrl: String? = null,
        backdropUrl: String? = null,
        season: Int? = null,
        episode: Int? = null,
        playedAtMillis: Long = now(),
    ) {
        recentDao.upsert(
            RecentEntity(
                canonicalId = canonicalId,
                title = title,
                posterUrl = posterUrl,
                backdropUrl = backdropUrl,
                mediaType = mediaType,
                season = season,
                episode = episode,
                lastPlayedAt = playedAtMillis,
                origin = RowOrigin.LOCAL.wire,
            ),
        )
    }

    // ---------------------------------------------------------------- collections

    /** Every folder card, system folders first, then the viewer's by `sort_index`. */
    public fun collections(): Flow<List<CollectionSummary>> =
        combine(
            collectionDao.observeCollections(),
            collectionDao.observeAllItems(),
        ) { collections, items ->
            val byCollection = items.groupBy(CollectionItemEntity::collectionId)
            // The DAO already returns them by sort_index. System folders are simply lifted to the
            // front, which is the order the Collections grid draws (spec §5.3).
            val (system, owned) = collections.partition {
                CollectionKind.fromWire(it.kind) == CollectionKind.SYSTEM
            }
            (system + owned).map { row ->
                val rows = byCollection[row.id].orEmpty().sortedBy(CollectionItemEntity::sortIndex)
                CollectionSummary(
                    id = row.id,
                    name = row.name,
                    accent = row.accent,
                    kind = CollectionKind.fromWire(row.kind),
                    count = rows.size,
                    pinnedHome = row.pinnedHome,
                    previewPosters = rows.mapNotNull(CollectionItemEntity::posterUrl).take(PREVIEW_POSTERS),
                )
            }
        }.distinctUntilChanged()

    /** One collection with its items. Emits null after a delete. */
    public fun collection(id: Long): Flow<CollectionDetail?> =
        combine(
            collectionDao.observeCollection(id),
            collectionDao.observeItems(id),
        ) { row, items ->
            row?.let {
                CollectionDetail(
                    id = it.id,
                    name = it.name,
                    accent = it.accent,
                    kind = CollectionKind.fromWire(it.kind),
                    sourceRef = it.sourceRef,
                    pinnedHome = it.pinnedHome,
                    sortIndex = it.sortIndex,
                    updatedAtMillis = it.updatedAt,
                    items = items.map(::asCollectionItem),
                )
            }
        }.distinctUntilChanged()

    /** Which folders already hold this title. Drives the "Add to collection" checklist. */
    public fun collectionsContaining(canonicalId: String): Flow<Set<Long>> =
        collectionDao.observeCollectionIdsContaining(canonicalId)
            .map(List<Long>::toSet)
            .distinctUntilChanged()

    public suspend fun create(
        name: String,
        accent: String,
        kind: CollectionKind = CollectionKind.MANUAL,
        sourceRef: String? = null,
    ): Long = database.withTransaction {
        collectionDao.insertCollection(
            CollectionEntity(
                name = name,
                accent = accent,
                kind = kind.wire,
                sourceRef = sourceRef,
                sortIndex = collectionDao.maximumCollectionSortIndex() + 1,
                pinnedHome = false,
                updatedAt = now(),
            ),
        )
    }

    public suspend fun rename(id: Long, name: String) {
        editCollection(id) { it.copy(name = name) }
    }

    public suspend fun setAccent(id: Long, accent: String) {
        editCollection(id) { it.copy(accent = accent) }
    }

    public suspend fun setPinned(id: Long, pinned: Boolean) {
        editCollection(id) { it.copy(pinnedHome = pinned) }
    }

    public suspend fun delete(id: Long) {
        database.withTransaction {
            val row = collectionDao.find(id) ?: return@withTransaction
            // A system folder is not the viewer's to remove. Its long-OK menu never offers it.
            if (CollectionKind.fromWire(row.kind) == CollectionKind.SYSTEM) return@withTransaction
            collectionDao.deleteItems(id)
            collectionDao.deleteCollection(id)
            renumberCollections()
        }
    }

    /**
     * Move mode on the Collections grid. [delta] is normally -1 or +1; the folder swaps with the
     * neighbour that many places away. System folders never move, and nothing moves past the ends.
     */
    public suspend fun moveCollection(id: Long, delta: Int): Boolean = database.withTransaction {
        if (delta == 0) return@withTransaction false
        val ordered = collectionDao.allCollections()
            .filter { CollectionKind.fromWire(it.kind) != CollectionKind.SYSTEM }
        val from = ordered.indexOfFirst { it.id == id }
        if (from < 0) return@withTransaction false
        val to = from + delta
        if (to !in ordered.indices) return@withTransaction false
        val moved = ordered.toMutableList()
        moved.add(to, moved.removeAt(from))
        collectionDao.updateCollections(
            moved.mapIndexed { index, row -> row.copy(sortIndex = index, updatedAt = now()) },
        )
        true
    }

    public suspend fun addItem(
        collectionId: Long,
        canonicalId: String,
        mediaType: String,
        title: String,
        posterUrl: String? = null,
    ) {
        database.withTransaction {
            val existing = collectionDao.items(collectionId).firstOrNull { it.canonicalId == canonicalId }
            collectionDao.insertItem(
                CollectionItemEntity(
                    collectionId = collectionId,
                    canonicalId = canonicalId,
                    mediaType = mediaType,
                    title = title,
                    posterUrl = posterUrl,
                    // Re-adding a title keeps its place rather than sending it to the end.
                    sortIndex = existing?.sortIndex ?: (collectionDao.maximumItemSortIndex(collectionId) + 1),
                    addedAt = existing?.addedAt ?: now(),
                ),
            )
            touch(collectionId)
        }
    }

    /** Puts a title in the built-in Watchlist. Creates that folder the first time. */
    public suspend fun saveToWatchlist(
        canonicalId: String,
        mediaType: String,
        title: String,
        posterUrl: String?,
    ) {
        val id = database.withTransaction {
            collectionDao.findSystem(SystemCollections.WATCHLIST_REF)?.id
                ?: collectionDao.insertCollection(
                    CollectionEntity(
                        name = SystemCollections.WATCHLIST_NAME,
                        accent = SystemCollections.SYSTEM_ACCENT,
                        kind = CollectionKind.SYSTEM.wire,
                        sourceRef = SystemCollections.WATCHLIST_REF,
                        sortIndex = collectionDao.maximumCollectionSortIndex() + 1,
                        pinnedHome = true,
                        updatedAt = now(),
                    ),
                )
        }
        addItem(id, canonicalId, mediaType, title, posterUrl)
    }

    public suspend fun removeItem(collectionId: Long, canonicalId: String) {
        database.withTransaction {
            collectionDao.deleteItem(collectionId, canonicalId)
            renumberItems(collectionId)
            touch(collectionId)
        }
    }

    /** Move mode inside a collection. Sourced collections cannot be reordered by hand. */
    public suspend fun moveItem(collectionId: Long, canonicalId: String, delta: Int): Boolean =
        database.withTransaction {
            if (delta == 0) return@withTransaction false
            val owner = collectionDao.find(collectionId) ?: return@withTransaction false
            if (CollectionKind.fromWire(owner.kind).isSourced) return@withTransaction false
            val ordered = collectionDao.items(collectionId)
            val from = ordered.indexOfFirst { it.canonicalId == canonicalId }
            if (from < 0) return@withTransaction false
            val to = from + delta
            if (to !in ordered.indices) return@withTransaction false
            val moved = ordered.toMutableList()
            moved.add(to, moved.removeAt(from))
            collectionDao.updateItems(moved.mapIndexed { index, row -> row.copy(sortIndex = index) })
            touch(collectionId)
            true
        }

    /**
     * A sourced collection's refresh result. The list on the server is the whole truth, so the old
     * items go and the new ones land in source order.
     */
    public suspend fun replaceSourcedItems(collectionId: Long, items: List<CollectionItem>) {
        database.withTransaction {
            val owner = collectionDao.find(collectionId) ?: return@withTransaction
            collectionDao.deleteItems(collectionId)
            collectionDao.insertItems(
                items.distinctBy(CollectionItem::canonicalId).mapIndexed { index, item ->
                    CollectionItemEntity(
                        collectionId = owner.id,
                        canonicalId = item.canonicalId,
                        mediaType = item.mediaType,
                        title = item.title,
                        posterUrl = item.posterUrl,
                        sortIndex = index,
                        addedAt = item.addedAtMillis.takeIf { it > 0L } ?: now(),
                    )
                },
            )
            touch(collectionId)
        }
    }

    /**
     * Creates Continue Watching and My Cloud if they are missing. Safe to call on every open of the
     * Collections screen; a second call writes nothing.
     */
    public suspend fun ensureSystemCollections(): List<Long> = database.withTransaction {
        listOf(
            SystemCollections.CONTINUE_WATCHING_REF to SystemCollections.CONTINUE_WATCHING_NAME,
            SystemCollections.MY_CLOUD_REF to SystemCollections.MY_CLOUD_NAME,
            SystemCollections.WATCHLIST_REF to SystemCollections.WATCHLIST_NAME,
        ).map { (ref, label) ->
            collectionDao.findSystem(ref)?.id ?: collectionDao.insertCollection(
                CollectionEntity(
                    name = label,
                    accent = SystemCollections.SYSTEM_ACCENT,
                    kind = CollectionKind.SYSTEM.wire,
                    sourceRef = ref,
                    sortIndex = collectionDao.maximumCollectionSortIndex() + 1,
                    pinnedHome = false,
                    updatedAt = now(),
                ),
            )
        }
    }

    // ---------------------------------------------------------------- add-on health

    public fun addonHealth(): Flow<List<AddonHealthEntity>> = addonHealthDao.observeAll()

    /** An add-on that fails three refreshes in a row is drawn grey in Settings. It is never dropped. */
    public suspend fun recordAddonResult(manifestUrl: String, ok: Boolean, error: String? = null) {
        val existing = addonHealthDao.find(manifestUrl)
        addonHealthDao.upsert(
            if (ok) {
                AddonHealthEntity(manifestUrl, lastOkAt = now(), failCount = 0, lastError = null)
            } else {
                AddonHealthEntity(
                    manifestUrl = manifestUrl,
                    lastOkAt = existing?.lastOkAt,
                    failCount = (existing?.failCount ?: 0) + 1,
                    lastError = error,
                )
            },
        )
    }

    // ---------------------------------------------------------------- jobs

    /** Settings → Jobs, one row per source. */
    public fun jobs(): Flow<List<JobStatus>> = jobDao.observeAll().map { rows ->
        rows.map {
            JobStatus(
                name = it.name,
                state = JobState.fromWire(it.state),
                startedAtMillis = it.startedAt,
                finishedAtMillis = it.finishedAt,
                message = it.message,
            )
        }
    }.distinctUntilChanged()

    /**
     * Records where one source's refresh stands. `running` stamps `started_at` and clears
     * `finished_at`; every other state stamps `finished_at` and keeps the start it already had.
     */
    public suspend fun updateJob(name: String, state: JobState, message: String? = null) {
        val existing = jobDao.find(name)
        val timestamp = now()
        jobDao.upsert(
            JobEntity(
                name = name,
                state = state.wire,
                startedAt = if (state == JobState.RUNNING) timestamp else existing?.startedAt,
                finishedAt = if (state == JobState.RUNNING) null else timestamp,
                message = message,
            ),
        )
    }

    // ---------------------------------------------------------------- internals

    private suspend fun editCollection(id: Long, edit: (CollectionEntity) -> CollectionEntity) {
        database.withTransaction {
            val row = collectionDao.find(id) ?: return@withTransaction
            collectionDao.updateCollection(edit(row).copy(updatedAt = now()))
        }
    }

    private suspend fun touch(collectionId: Long) {
        collectionDao.find(collectionId)?.let { collectionDao.updateCollection(it.copy(updatedAt = now())) }
    }

    private suspend fun renumberItems(collectionId: Long) {
        collectionDao.updateItems(
            collectionDao.items(collectionId).mapIndexed { index, row -> row.copy(sortIndex = index) },
        )
    }

    private suspend fun renumberCollections() {
        collectionDao.updateCollections(
            collectionDao.allCollections()
                .filter { CollectionKind.fromWire(it.kind) != CollectionKind.SYSTEM }
                .mapIndexed { index, row -> row.copy(sortIndex = index) },
        )
    }

    private fun asCollectionItem(row: CollectionItemEntity): CollectionItem = CollectionItem(
        canonicalId = row.canonicalId,
        mediaType = row.mediaType,
        title = row.title,
        posterUrl = row.posterUrl,
        sortIndex = row.sortIndex,
        addedAtMillis = row.addedAt,
    )

    public companion object {
        /** Under 2% has not started. */
        public const val MINIMUM_FRACTION: Float = 0.02f

        /** Over 95% is finished. */
        public const val FINISHED_FRACTION: Float = 0.95f

        /** Poster slivers on a folder card. */
        public const val PREVIEW_POSTERS: Int = 3
    }
}
