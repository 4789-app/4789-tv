package com.fourseveneightnine.tv.client.data.library

import androidx.room.withTransaction
import com.fourseveneightnine.tv.client.data.meta.Meta
import com.fourseveneightnine.tv.client.data.meta.displayTitleOrNull
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
     * just watched here is always the older answer. A local checkpoint at 15 seconds also qualifies
     * before the percentage threshold. Finished titles leave the shelf.
     *
     * A row whose duration is unknown is kept. There is no progress to judge it by, and dropping it
     * would silently lose every phone row that arrived without a duration.
     */
    public fun continueWatching(): Flow<List<ContinueItem>> =
        combine(progressDao.observeAll(), recentDao.observeAll(), ::mergeContinueWatching).distinctUntilChanged()

    /** Complete title progress, including finished rows needed by Detail and next-episode picking. */
    public fun playbackHistory(): Flow<List<ContinueItem>> =
        combine(progressDao.observeAll(), recentDao.observeAll()) { progress, recents ->
            mergePlaybackHistory(progress, recents)
        }.distinctUntilChanged()

    internal fun mergeContinueWatching(
        progress: List<WatchProgressEntity>,
        recents: List<RecentEntity>,
    ): List<ContinueItem> {
        val byId = progress.associateBy { it.canonicalId }
        return mergePlaybackHistory(progress, recents).mapNotNull { item ->
            val row = byId[item.canonicalId]
            if (row?.nextSeason != null && row.nextEpisode != null && item.origin == RowOrigin.LOCAL) {
                if (row.season == row.nextSeason && row.episode == row.nextEpisode) item
                else item.copy(season = row.nextSeason, episode = row.nextEpisode, positionMs = 0, durationMs = 0, pendingNextEpisode = true)
            } else item.takeIf(::isWorthContinuing)
        }
    }

    private fun mergePlaybackHistory(
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
            .sortedByDescending(ContinueItem::lastActivityMillis)
    }

    private fun isWorthContinuing(item: ContinueItem): Boolean {
        if (item.durationMs <= 0L) return true
        return item.hasStarted && item.progressFraction <= FINISHED_FRACTION
    }

    public suspend fun resumePosition(id: String, type: String, season: Int?, episode: Int?): Long? =
        progressDao.find(id)?.takeIf { it.mediaType == type && it.season == season && it.episode == episode }
            ?.let { row -> row.positionMs.takeIf { it > 0 && (row.durationMs <= 0 ||
                it.toDouble() / row.durationMs < FINISHED_FRACTION.toDouble()) } }

    public suspend fun pendingEpisodePreference(id: String, season: Int, episode: Int): String? =
        progressDao.find(id)?.takeIf { it.source == RowOrigin.LOCAL.wire && it.nextSeason == season &&
            it.nextEpisode == episode }?.episodePreference

    /** A finished episode's old lifecycle checkpoint must not reopen over its pending successor. */
    public suspend fun hasPendingSuccessor(id: String, season: Int, episode: Int): Boolean {
        val row = progressDao.find(id) ?: return false
        return row.source == RowOrigin.LOCAL.wire && row.mediaType == "series" &&
            row.season == season && row.episode == episode && row.positionMs == row.durationMs &&
            row.nextSeason != null && row.nextEpisode != null
    }

    public suspend fun updateWatchedSuccessor(id: String, season: Int, episode: Int, nextSeason: Int, nextEpisode: Int) =
        progressDao.updateWatchedSuccessor(id, season, episode, nextSeason, nextEpisode)

    /** Called every 15 s while playing, and on pause, stop and background. */
    public suspend fun writeProgress(
        canonicalId: String,
        mediaType: String,
        season: Int? = null,
        episode: Int? = null,
        positionMs: Long,
        durationMs: Long,
    ): Unit = database.withTransaction {
        val previous = progressDao.find(canonicalId)
        val continuing = season != null && episode != null &&
            previous?.nextSeason == season && previous.nextEpisode == episode
        progressDao.upsert(
            WatchProgressEntity(
                canonicalId = canonicalId,
                mediaType = mediaType,
                season = season,
                episode = episode,
                positionMs = positionMs.coerceAtLeast(0L),
                durationMs = durationMs.coerceAtLeast(0L),
                nextSeason = season.takeIf { continuing }, nextEpisode = episode.takeIf { continuing },
                episodePreference = previous?.episodePreference.takeIf { continuing },
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
        nextSeason: Int? = null,
        nextEpisode: Int? = null,
        episodePreference: String? = null,
    ): Unit = database.withTransaction {
        val existing = progressDao.find(canonicalId)
        val duration = existing?.durationMs?.takeIf { it > 0L } ?: 1L
        progressDao.upsert(
            WatchProgressEntity(
                canonicalId = canonicalId,
                mediaType = mediaType,
                season = season ?: existing?.season,
                episode = episode ?: existing?.episode,
                nextSeason = nextSeason, nextEpisode = nextEpisode, episodePreference = episodePreference,
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
                        title = displayTitleOrNull(local.title, local.canonicalId)
                            ?: displayTitleOrNull(row.title, row.canonicalId) ?: local.title,
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
        database.withTransaction {
            val previous = recentDao.find(canonicalId)?.takeIf { it.mediaType == mediaType }
            recentDao.upsert(
                RecentEntity(
                    canonicalId = canonicalId,
                    title = displayTitleOrNull(title, canonicalId)
                        ?: displayTitleOrNull(previous?.title, canonicalId) ?: title,
                    posterUrl = posterUrl?.takeIf(String::isNotBlank) ?: previous?.posterUrl,
                    backdropUrl = backdropUrl?.takeIf(String::isNotBlank) ?: previous?.backdropUrl,
                    mediaType = mediaType,
                    season = season,
                    episode = episode,
                    lastPlayedAt = playedAtMillis,
                    origin = RowOrigin.LOCAL.wire,
                ),
            )
        }
    }

    /** Fill display fields without touching playback progress, episode selection or recency. */
    public suspend fun fillRecentMetadata(canonicalId: String, metadata: Meta) {
        database.withTransaction {
            val existing = recentDao.find(canonicalId) ?: run {
                val progress = progressDao.find(canonicalId)
                    ?.takeIf { it.mediaType == metadata.type && it.source == RowOrigin.LOCAL.wire }
                    ?: return@withTransaction
                RecentEntity(canonicalId = canonicalId, title = canonicalId, mediaType = progress.mediaType,
                    season = progress.season, episode = progress.episode, lastPlayedAt = progress.updatedAt,
                    origin = RowOrigin.LOCAL.wire)
            }
            if (existing.mediaType != metadata.type) return@withTransaction
            val updated = existing.copy(
                title = displayTitleOrNull(existing.title, canonicalId)
                    ?: displayTitleOrNull(metadata.title, metadata.id) ?: existing.title,
                posterUrl = existing.posterUrl?.takeIf(String::isNotBlank) ?: metadata.poster?.takeIf(String::isNotBlank),
                backdropUrl = existing.backdropUrl?.takeIf(String::isNotBlank) ?: metadata.backdrop?.takeIf(String::isNotBlank),
            )
            if (updated != existing) recentDao.upsert(updated)
        }
    }

    /** Persist a decoded replacement only while the rejected URL is still this row's poster. */
    public suspend fun replaceRecentPoster(canonicalId: String, mediaType: String, rejectedUrl: String?, posterUrl: String) {
        if (posterUrl.isBlank()) return
        database.withTransaction {
            val existing = recentDao.find(canonicalId) ?: return@withTransaction
            if (existing.mediaType == mediaType && existing.posterUrl == rejectedUrl && existing.posterUrl != posterUrl)
                recentDao.upsert(existing.copy(posterUrl = posterUrl))
        }
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
                    previewPosters = rows.asSequence().mapNotNull(CollectionItemEntity::posterUrl)
                        .filter(String::isNotBlank).distinct().take(PREVIEW_POSTERS).toList(),
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

    /** TV-local saved lists for Home poster rows and Discover catalogs. */
    public fun savedCollections(): Flow<List<CollectionDetail>> =
        combine(collectionDao.observeCollections(), collectionDao.observeAllItems()) { collections, items ->
            val refs = listOf(SystemCollections.FAVORITES_REF, SystemCollections.WATCHLIST_REF)
            val byCollection = items.groupBy(CollectionItemEntity::collectionId)
            collections.filter { it.sourceRef in refs && CollectionKind.fromWire(it.kind) == CollectionKind.SYSTEM }
                .sortedBy { refs.indexOf(it.sourceRef) }
                .map { row ->
                    CollectionDetail(row.id, row.name, row.accent, CollectionKind.fromWire(row.kind),
                        row.sourceRef, row.pinnedHome, row.sortIndex, row.updatedAt,
                        byCollection[row.id].orEmpty().sortedBy(CollectionItemEntity::sortIndex).map(::asCollectionItem))
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

    /** Changes a mutable folder's source and clears its prior items in one transaction. */
    public suspend fun setSource(id: Long, kind: CollectionKind, sourceRef: String?): Boolean {
        if (kind == CollectionKind.SYSTEM) return false
        val reference = if (kind.isSourced) sourceRef?.trim()?.takeIf(String::isNotEmpty) else null
        if (kind.isSourced && reference == null) return false
        return database.withTransaction {
            val current = collectionDao.find(id) ?: return@withTransaction false
            if (CollectionKind.fromWire(current.kind) == CollectionKind.SYSTEM) return@withTransaction false
            if (current.kind == kind.wire && current.sourceRef == reference) return@withTransaction true
            collectionDao.deleteItems(id)
            collectionDao.updateCollection(current.copy(kind = kind.wire, sourceRef = reference, updatedAt = now()))
            true
        }
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
        savedSource: String? = null,
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
                    savedSource = savedSource ?: existing?.savedSource ?: collectionDao.savedSource(canonicalId),
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
        savedSource: String? = null,
    ): Unit = database.withTransaction {
        val id = collectionDao.findSystem(SystemCollections.WATCHLIST_REF)?.id
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
        addItem(id, canonicalId, mediaType, title, posterUrl, savedSource)
    }

    /** Long-OK loves a title directly, without changing Watchlist membership or its saved order. */
    public suspend fun saveToFavorites(
        canonicalId: String,
        mediaType: String,
        title: String,
        posterUrl: String?,
    ): Unit = database.withTransaction {
        ensureSystemCollections()
        val favorites = requireNotNull(collectionDao.findSystem(SystemCollections.FAVORITES_REF))
        addItem(favorites.id, canonicalId, mediaType, title, posterUrl)
    }

    public fun watchlistContains(cardId: String): Flow<Boolean> =
        collectionDao.observeWatchlistMembership(SystemCollections.WATCHLIST_REF, cardId).distinctUntilChanged()

    public suspend fun savedSource(id: String): String? =
        collectionDao.savedSource(id)

    public suspend fun removeItem(collectionId: Long, canonicalId: String) {
        database.withTransaction {
            collectionDao.deleteItem(collectionId, canonicalId)
            renumberItems(collectionId)
            touch(collectionId)
        }
    }

    /** Read this collection's recipes once, keeping each saved file distinct. */
    public suspend fun watchlistSources(collectionId: Long): Map<String, String> =
        collectionDao.items(collectionId).mapNotNull { row ->
            row.savedSource?.let { row.canonicalId to it }
        }.toMap()

    public suspend fun removeWatchlistItems(collectionId: Long, ids: Set<String>) {
        database.withTransaction {
            val folder = collectionDao.find(collectionId)
            if (folder?.sourceRef != SystemCollections.WATCHLIST_REF) return@withTransaction
            val current = collectionDao.items(collectionId).filter { it.canonicalId in ids }
            if (current.isEmpty()) return@withTransaction
            current.forEach { collectionDao.deleteItem(collectionId, it.canonicalId) }
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
            SystemCollections.FAVORITES_REF to SystemCollections.FAVORITES_NAME,
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
        public const val PREVIEW_POSTERS: Int = 10
    }
}
