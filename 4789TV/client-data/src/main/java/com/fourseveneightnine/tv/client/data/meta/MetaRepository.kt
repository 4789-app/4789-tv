package com.fourseveneightnine.tv.client.data.meta

import com.fourseveneightnine.tv.client.data.addons.Addon
import com.fourseveneightnine.tv.client.data.addons.AddonEndpoint
import com.fourseveneightnine.tv.client.data.addons.AddonRegistry
import com.fourseveneightnine.tv.client.data.addons.StremioClient
import com.fourseveneightnine.tv.client.data.addons.defaultAddonHttpClient
import com.fourseveneightnine.tv.client.data.addons.getText
import com.fourseveneightnine.tv.client.data.streams.OriginalAudioLanguage
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Locale
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Everything the Detail screen needs about one title.
 *
 * Cinemeta answers first, because it answers for every IMDb id and it answers fast. TMDB then
 * fills the gaps Cinemeta leaves — the logo, a clean wide backdrop, cast photos, runtime,
 * certification, the trailer id — and only when the phone synced a TMDB key. Without a key the
 * screen still draws; §9.9 gives every missing field a fallback, so a gap is a layout instruction
 * rather than an error.
 *
 * The cache is a directory of regeneratable JSON files, with separate movie and series
 * freshness windows and periodic count/byte eviction. It uses the same file approach as `TVEnrichmentDiskCache` in
 * `:app`, without adding a table or migration.
 */
class MetaRepository(
    private val registry: AddonRegistry,
    private val client: StremioClient,
    private val tmdbKey: String? = null,
    cacheDir: File? = null,
    private val okHttp: OkHttpClient = defaultAddonHttpClient(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val tmdbBaseURL: String = TMDB_BASE_URL,
    private val watchRegion: String = Locale.getDefault().country.ifBlank { "US" },
) {
    private val directory = cacheDir?.let { File(it, "meta") }
    private val memory = LinkedHashMap<String, Pair<Meta, Long>>(MAX_MEMORY_ENTRIES, 0.75f, true)
    private val inFlight = ConcurrentHashMap<String, PendingMeta>()
    private val diskLock = Any()
    private var writesSinceDiskMaintenance = DISK_MAINTENANCE_WRITE_INTERVAL
    @Volatile private var cacheGeneration = 0L

    private class PendingMeta {
        val previews = MutableStateFlow<Meta?>(null)
        val result = CompletableDeferred<Meta?>()
        @Volatile var cancelled = false
        val preview: Meta? get() = previews.value
    }

    /**
     * One title's metadata, or null when no add-on knows it.
     *
     * A fresh cache hit needs no network. The Detail screen and a prefetch share an active fetch.
     */
    suspend fun meta(
        type: String,
        id: String,
        knownTmdbID: Int? = null,
        onBase: ((Meta) -> Unit)? = null,
    ): Meta? = cachedMeta(type, "$type:$id", onBase) { publish ->
        fetch(type, id, knownTmdbID, publish)
    }

    /**
     * Fresh popular/on-air discovery and published episode dates, including future episodes.
     * Calendar reads are separate from Detail's aired-only enrichment and its metadata cache.
     */
    suspend fun calendar(month: YearMonth, followedIDs: List<String>): CalendarSchedule = coroutineScope {
        val key = tmdbKey?.takeIf(String::isNotBlank)
        val gate = Semaphore(4)
        val trending = if (key == null) null else async {
            withTimeoutOrNull(8_000) {
                readTmdbObject("$tmdbBaseURL/trending/tv/week?api_key=$key&language=en-US")?.takeIf { it["results"] is JsonArray }
            }
        }
        val discovery = if (key == null) emptyList() else listOf(
            "with_original_language" to "en", "with_origin_country" to "IN", "with_origin_country" to "KR",
        ).map { (filter, value) ->
            async {
                val url = "$tmdbBaseURL/discover/tv".toHttpUrl().newBuilder()
                    .addQueryParameter("api_key", key).addQueryParameter("language", "en-US")
                    .addQueryParameter("include_adult", "false")
                    .addQueryParameter("include_null_first_air_dates", "false")
                    .addQueryParameter("sort_by", "popularity.desc")
                    .addQueryParameter("air_date.gte", month.atDay(1).toString())
                    .addQueryParameter("air_date.lte", month.atEndOfMonth().toString())
                    .addQueryParameter(filter, value).build()
                withTimeoutOrNull(8_000) { readTmdbObject(url.toString())?.takeIf { it["results"] is JsonArray } }
            }
        }.awaitAll()
        // Each region keeps its own places, so a large English catalog cannot crowd out India/Korea.
        val popularIDs = discovery.filterNotNull().flatMap { root ->
            (root["results"] as? JsonArray).orEmpty().mapNotNull { row ->
                (row as? JsonObject)?.int("id")?.takeIf { it > 0 }?.let { "tmdb:$it" }
            }.distinct().take(12)
        }
        val trendingRoot = trending?.await()
        val trendingIDs = (trendingRoot?.get("results") as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }.filter { row ->
                row.string("original_language") == "en" ||
                    (row["origin_country"] as? JsonArray).orEmpty().any { (it as? JsonPrimitive)?.contentOrNull in setOf("IN", "KR") }
            }.mapNotNull { it.int("id")?.takeIf { id -> id > 0 }?.let { id -> "tmdb:$id" } }.distinct().take(8)
        val ids = (followedIDs.take(40) + popularIDs + trendingIDs).distinct()
        val incomplete = AtomicBoolean(discovery.any { it == null } || key != null && trendingRoot == null)
        val results = Collections.synchronizedList(mutableListOf<Meta?>())
        // Keep successful schedules if a slow provider exhausts the overall budget.
        val completed = withTimeoutOrNull(45_000) {
            ids.map { id ->
                async {
                    val result = gate.withPermit {
                        withTimeoutOrNull(10_000) {
                            val scheduled = if (key == null) null else calendarMeta(id, month, key) { incomplete.set(true) }
                            // Installed metadata add-ons still cover followed titles without a TMDB identity/key.
                            scheduled ?: if (id in followedIDs) meta("series", id) else null
                        }
                    }
                    results.add(result)
                }
            }.awaitAll()
            true
        } == true
        CalendarSchedule(
            series = results.filterNotNull().distinctBy { it.imdbID ?: it.id },
            incomplete = !completed || incomplete.get() || results.any { it == null },
            discoveryAvailable = key != null,
        )
    }

    private suspend fun calendarMeta(id: String, month: YearMonth, key: String, onIncomplete: () -> Unit): Meta? {
        val tmdbID = directTmdbID("series", id)
            ?: id.takeIf(IMDB_ID::matches)?.let { findTmdbID(it, "tv", key) } ?: return null
        val url = "$tmdbBaseURL/tv/$tmdbID".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", key).addQueryParameter("language", "en-US")
            .addQueryParameter("append_to_response", "external_ids,watch/providers").build()
        val root = readTmdbObject(url.toString()) ?: return null
        val imdb = (root["external_ids"] as? JsonObject)?.string("imdb_id")?.takeIf(IMDB_ID::matches)
        if (IMDB_ID.matches(id) && imdb != null && imdb != id) return null
        val canonicalID = imdb ?: id
        val base = tmdbMeta("series", canonicalID, tmdbID, root) ?: return null
        val last = root["last_episode_to_air"] as? JsonObject
        val next = root["next_episode_to_air"] as? JsonObject
        val end = month.atEndOfMonth()
        fun date(raw: String?): LocalDate? = raw?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        // Fetch the seasons spanning this month, not every season in a long-running series.
        val seasons = (root["seasons"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .mapNotNull { row ->
                val number = row.int("season_number")?.takeIf { it in 1..100 } ?: return@mapNotNull null
                val started = date(row.string("air_date")) ?: return@mapNotNull null
                number.takeIf { started <= end }
            }.sortedDescending().take(2) + listOfNotNull(last, next).mapNotNull { row ->
                row.int("season_number")?.takeIf { it in 1..100 && date(row.string("air_date"))?.let { day -> day <= end } == true }
            }
        val episodes = seasons.distinct().flatMap { season ->
            val response = readTmdbObject("$tmdbBaseURL/tv/$tmdbID/season/$season?api_key=$key&language=en-US")
            if (response?.get("episodes") !is JsonArray) onIncomplete()
            (response?.get("episodes") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        } + listOfNotNull(last, next)
        val videos = episodes.mapNotNull { row ->
            val released = row.string("air_date") ?: return@mapNotNull null
            val day = date(released)?.takeIf { YearMonth.from(it) == month } ?: return@mapNotNull null
            val season = row.int("season_number")?.takeIf { it in 1..100 } ?: return@mapNotNull null
            val number = row.int("episode_number")?.takeIf { it > 0 } ?: return@mapNotNull null
            Episode(season, number, row.string("name") ?: "Episode $number",
                overview = row.string("overview")?.take(1_000),
                thumbnail = row.string("still_path")?.takeIf { it.startsWith('/') && !it.startsWith("//") }
                    ?.let { "${IMAGE_BASE}w500$it" },
                released = day.toString(), id = AddonEndpoint.episodeIdentifier(canonicalID, season, number))
        }.distinctBy { it.season to it.episode }
        return base.copy(videos = videos, seasons = videos.map(Episode::season).distinct().sorted())
    }

    /** Movies and series credited to the identified person. */
    suspend fun personCredits(name: String, personID: Int? = null): List<MetaRef>? {
        val key = tmdbKey?.takeIf(String::isNotBlank) ?: return null
        val person = personID?.takeIf { it > 0 } ?: run {
            val url = "$tmdbBaseURL/search/person".toHttpUrl().newBuilder()
                .addQueryParameter("api_key", key).addQueryParameter("query", name.trim().take(200)).build()
            val people = (readTmdbObject(url.toString())?.get("results") as? JsonArray) ?: return null
            people.mapNotNull { it as? JsonObject }
                .filter { it.string("name")?.equals(name.trim(), ignoreCase = true) == true }
                .mapNotNull { it.int("id")?.takeIf { id -> id > 0 } }.distinct().singleOrNull() ?: return null
        }
        val root = readTmdbObject("$tmdbBaseURL/person/$person/combined_credits?api_key=$key") ?: return null
        val cast = root["cast"] as? JsonArray
        val crew = root["crew"] as? JsonArray
        if (cast == null && crew == null) return null
        return (cast.orEmpty() + crew.orEmpty()).mapNotNull { it as? JsonObject }
            .filter { it["adult"]?.toString() != "true" }
            .sortedByDescending { (it.string("release_date") ?: it.string("first_air_date")).orEmpty() }
            .mapNotNull { credit ->
                val id = credit.int("id")?.takeIf { it > 0 } ?: return@mapNotNull null
                val type = when (credit.string("media_type")) {
                    "movie" -> "movie"
                    "tv" -> "series"
                    else -> return@mapNotNull null
                }
                val title = credit.string(if (type == "movie") "title" else "name")?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                val poster = credit.string("poster_path")?.takeIf { it.startsWith('/') && !it.startsWith("//") }
                MetaRef("tmdb:$id", type, title, poster?.let { "${IMAGE_BASE}w342$it" })
            }.distinctBy { it.type to it.id }
    }

    /** Artwork and a first Detail frame from one small TMDB response, with the normal cache bounds. */
    suspend fun tmdbPreview(type: String, tmdbID: Int): Meta? {
        val key = tmdbKey?.takeIf(String::isNotBlank) ?: return null
        if (type !in setOf("movie", "series") || tmdbID <= 0) return null
        peekTmdbPreview(type, tmdbID)?.let { return it }
        val id = "tmdb:$tmdbID"
        return cachedMeta(type, "$type:preview:$id") {
            val root = readTmdbObject("$tmdbBaseURL/${tmdbKind(type)}/$tmdbID?api_key=$key")
            root?.let { tmdbMeta(type, id, tmdbID, it) }
        }
    }

    fun peekTmdbPreview(type: String, tmdbID: Int): Meta? =
        peek(type, "tmdb:$tmdbID") ?: peek(type, "preview:tmdb:$tmdbID")

    private suspend fun cachedMeta(
        type: String,
        key: String,
        onBase: ((Meta) -> Unit)? = null,
        fetch: suspend ((Meta) -> Unit) -> Meta?,
    ): Meta? {
        val generation = cacheGeneration
        val cached = synchronized(memory) {
            memory[key]?.takeIf { clock() - it.second in 0 until MAX_STALE_MILLIS }
                ?: run { memory.remove(key); null }
        }
        cached?.takeIf { isFresh(type, it.second) }?.let { (meta, _) -> onBase?.invoke(meta); return meta }
        val disk = if (cached == null) withContext(Dispatchers.IO) { readDisk(key) } else null
        if (disk != null && isFresh(type, disk.second) && !disk.first.hasInlineStreams) {
            val (meta, storedAt) = disk
            rememberMeta(key, meta, generation, storedAt)
            onBase?.invoke(meta)
            return meta
        }
        // Saved artwork remains useful while the provider refreshes. Stale episode lists and
        // playback capabilities must never become a fresh playback decision or a cache hit.
        (cached ?: disk)?.first?.let { saved ->
            onBase?.invoke(saved.copy(videos = emptyList(), seasons = emptyList(),
                defaultVideoID = null, inlineStreams = null))
        }
        suspend fun join(pending: PendingMeta): Meta? {
            val result = coroutineScope {
                val previews = if (onBase == null) null else launch(start = CoroutineStart.UNDISPATCHED) {
                    pending.previews.filterNotNull().collect { onBase(it) }
                }
                try { pending.result.await() } finally { previews?.cancel() }
            }
            return if (pending.cancelled) cachedMeta(type, key, onBase, fetch) else result
        }
        inFlight[key]?.let { return join(it) }
        val pending = PendingMeta()
        inFlight.putIfAbsent(key, pending)?.let { return join(it) }
        var answer: Meta? = null
        try {
            answer = fetch { base ->
                pending.previews.value = base
                onBase?.invoke(base)
            }
            answer?.let { complete ->
                withContext(Dispatchers.IO) { writeDisk(key, complete, generation) }
                rememberMeta(key, complete, generation)
            }
            return answer
        } catch (cancelled: CancellationException) {
            pending.cancelled = true
            throw cancelled
        } catch (_: Exception) {
            return null
        } finally {
            inFlight.remove(key, pending)
            pending.result.complete(answer.takeUnless { pending.cancelled })
        }
    }
    /** Only memory: playback must not wait for disk, cast photos or a metadata server. */
    fun peek(type: String, id: String): Meta? {
        val key = "$type:$id"
        return synchronized(memory) { memory[key]?.takeIf { isFresh(type, it.second) }?.first }
            ?: inFlight[key]?.preview
    }

    suspend fun invalidate(type: String, id: String) {
        val key = "$type:$id"
        synchronized(memory) { memory.remove(key) }
        withContext(Dispatchers.IO) { synchronized(diskLock) { directory?.let { File(it, fileName(key)).delete() } } }
    }

    private fun isFresh(type: String, storedAt: Long): Boolean =
        clock() - storedAt < (if (type == "series") SERIES_CACHE_TTL_MILLIS else CACHE_TTL_MILLIS)

    private fun rememberMeta(key: String, meta: Meta, generation: Long, storedAt: Long = clock()) = synchronized(memory) {
        if (generation != cacheGeneration) return@synchronized
        memory[key] = meta to storedAt
        while (memory.size > MAX_MEMORY_ENTRIES) memory.remove(memory.keys.first())
    }

    /** Drop everything, for Settings → Jobs → "Clear caches". */
    fun clear() {
        synchronized(diskLock) {
            synchronized(memory) { cacheGeneration++; memory.clear() }
            inFlight.clear()
            writesSinceDiskMaintenance = DISK_MAINTENANCE_WRITE_INTERVAL
            runCatching { directory?.listFiles()?.forEach(File::delete) }
        }
    }

    private suspend fun fetch(type: String, id: String, knownTmdbID: Int?, onBase: ((Meta) -> Unit)?): Meta? {
        val key = tmdbKey?.takeIf(String::isNotBlank)
        val addons = metaAddons()
        val directID = directTmdbID(type, id)
        if (key != null && directID != null) return fetchWithTmdb(type, id, directID, key, addons, onBase)
        if (key != null && knownTmdbID != null && knownTmdbID > 0 && type in setOf("movie", "series")) {
            return fetchWithTmdb(type, id, knownTmdbID, key, addons, onBase, includeCinemeta = true)
        }

        val preferCinemeta = key != null && type in setOf("movie", "series") && IMDB_ID.matches(id)
        val base = withContext(Dispatchers.Default) {
            fetchAddonMeta(if (preferCinemeta) addons.filter(::isCinemeta) else addons, type, id)
        }
        if (base != null) {
            onBase?.invoke(base)
            return try {
                withContext(Dispatchers.Default) {
                    val imdb = base.imdbID?.takeIf {
                        type in setOf("movie", "series") && !IMDB_ID.matches(id) && IMDB_ID.matches(it)
                    }
                    val cinemeta = imdb?.let { fetchCinemeta(addons, type, it) }
                    val combined = if (cinemeta == null) base else mergeCinemeta(base, cinemeta)
                    if (key == null) combined else fillFromTmdb(combined, key, onBase)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { base }
        }

        return if (preferCinemeta && key != null) fetchWithTmdb(type, id, null, key, addons, onBase)
        else null
    }

    /** TMDB can render while the original provider is still resolving metadata. */
    private suspend fun fetchWithTmdb(
        type: String,
        id: String,
        directID: Int?,
        key: String,
        addons: List<Addon>,
        onBase: ((Meta) -> Unit)?,
        includeCinemeta: Boolean = false,
    ): Meta? = coroutineScope {
        val kind = tmdbKind(type)
        val tmdb = async(Dispatchers.Default) {
            val tmdbID = directID ?: findTmdbID(id, kind, key) ?: return@async null
            val root = readTmdbDetails(kind, tmdbID, key) ?: return@async null
            val imdb = (root["external_ids"] as? JsonObject)?.string("imdb_id")?.takeIf(IMDB_ID::matches)
            if (includeCinemeta && IMDB_ID.matches(id) && imdb != null && imdb != id) return@async null
            tmdbID to root
        }
        val provider = async(Dispatchers.Default) {
            fetchAddonMeta(if (includeCinemeta) addons else addons.filterNot(::isCinemeta), type, id)
        }
        var preview = select<Meta?> {
            tmdb.onAwait { details -> details?.let { (tmdbID, root) -> tmdbMeta(type, id, tmdbID, root) } }
            provider.onAwait { it }
        }
        preview?.let { onBase?.invoke(it) }

        val details = tmdb.await()
        val native = details?.let { (tmdbID, root) -> tmdbMeta(type, id, tmdbID, root) }
        if (preview == null && native != null) {
            preview = native
            onBase?.invoke(native)
        }
        val imdb = native?.imdbID
        val supplemental = if (directID != null && !includeCinemeta && imdb != null) async(Dispatchers.Default) {
            fetchCinemeta(addons, type, imdb)
        } else null
        val original = provider.await()
        val cinemeta = supplemental?.await()?.takeIf { original?.imdbID == null || original.imdbID == it.imdbID }
        val base = when {
            original == null -> cinemeta?.copy(id = id) ?: native
            cinemeta != null -> mergeCinemeta(original, cinemeta)
            else -> original
        } ?: return@coroutineScope null
        if (preview == null) onBase?.invoke(base)
        if (details == null) base else withContext(Dispatchers.Default) {
            val (tmdbID, root) = details
            completeFromTmdb(base, kind, tmdbID, root, key, onBase)
        }
    }

    private suspend fun fetchAddonMeta(addons: List<Addon>, type: String, id: String): Meta? =
        addons.firstNotNullOfOrNull { addon ->
            try { client.meta(addon, type, id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }

    private suspend fun fetchCinemeta(addons: List<Addon>, type: String, imdb: String): Meta? =
        fetchAddonMeta(addons.filter(::isCinemeta), type, imdb)?.takeIf { it.type == type && it.imdbID == imdb }

    private fun mergeCinemeta(base: Meta, cinemeta: Meta): Meta {
        val posters = (listOfNotNull(base.poster, cinemeta.poster) + base.posterFallbacks + cinemeta.posterFallbacks)
            .filter(String::isNotBlank).distinct().take(5)
        val backdrops = (listOfNotNull(base.backdrop, cinemeta.backdrop) + base.backdropFallbacks + cinemeta.backdropFallbacks)
            .filter(String::isNotBlank).distinct().take(5)
        val combined = base.copy(
            poster = posters.firstOrNull(), posterFallbacks = posters.drop(1),
            backdrop = backdrops.firstOrNull(), backdropFallbacks = backdrops.drop(1),
            logo = base.logo ?: cinemeta.logo,
        )
        return if (base.type == "series") mergeEpisodes(combined, cinemeta.videos) else combined
    }

    private fun isCinemeta(addon: Addon): Boolean =
        addon.manifestURL.equals(StremioClient.CINEMETA_MANIFEST, ignoreCase = true)

    /** Cinemeta first, then any other add-on that declares the `meta` resource. */
    private fun metaAddons(): List<Addon> =
        registry.metaAddons().sortedBy { !isCinemeta(it) }

    private fun tmdbKind(type: String): String = if (type == "series") "tv" else "movie"

    private fun directTmdbID(type: String, id: String): Int? {
        if (type != "movie" && type != "series") return null
        val match = TMDB_ID.matchEntire(id) ?: return null
        val idType = match.groupValues[1]
        if (idType.isNotEmpty() && idType != type && idType != tmdbKind(type)) return null
        return match.groupValues[2].toIntOrNull()?.takeIf { it > 0 }
    }

    private suspend fun fillFromTmdb(base: Meta, key: String, onBase: ((Meta) -> Unit)?): Meta {
        val kind = tmdbKind(base.type)
        val tmdbID = base.tmdbID ?: base.imdbID?.let { findTmdbID(it, kind, key) } ?: return base
        val root = readTmdbDetails(kind, tmdbID, key) ?: return base
        return completeFromTmdb(base, kind, tmdbID, root, key, onBase)
    }

    private suspend fun completeFromTmdb(
        base: Meta, kind: String, tmdbID: Int, root: JsonObject, key: String, onBase: ((Meta) -> Unit)?,
    ): Meta {
        val tmdbImdb = (root["external_ids"] as? JsonObject)?.string("imdb_id")?.takeIf(IMDB_ID::matches)
        val baseImdb = base.imdbID?.takeIf(IMDB_ID::matches)
        if (base.type == "series" && baseImdb != null && tmdbImdb != null && baseImdb != tmdbImdb) return base
        val enriched = mergeTmdb(base, kind, tmdbID, root)
        onBase?.invoke(enriched)
        if (base.type != "series") return enriched

        // Keep this to one season request. The series response already identifies the latest aired episode.
        val last = root["last_episode_to_air"] as? JsonObject ?: return enriched
        val season = last.int("season_number")?.takeIf { it in 1..100 } ?: return enriched
        val number = last.int("episode_number")?.takeIf { it in 1..2_000 } ?: return enriched
        val id = enriched.imdbID?.takeIf(IMDB_ID::matches) ?: "tmdb:$tmdbID"
        val today = Instant.ofEpochMilli(clock()).atOffset(ZoneOffset.UTC).toLocalDate()
        val latest = tmdbEpisode(last, id, season, number, today) ?: return enriched
        val known = enriched.episodes(season).associateBy(Episode::episode)
        val hasGaps = (1..number).any { episode ->
            val current = known[episode]
            current == null || current.overview.isNullOrBlank() ||
                current.thumbnail.isNullOrBlank() || current.released.isNullOrBlank() ||
                current.title == "Episode $episode"
        }
        val additional = if (hasGaps) {
            val response = readTmdbObject("$tmdbBaseURL/tv/$tmdbID/season/$season?api_key=$key")
            (response?.get("episodes") as? JsonArray).orEmpty().mapNotNull {
                (it as? JsonObject)?.let { episode -> tmdbEpisode(episode, id, season, number, today) }
            }
        } else emptyList()
        return mergeEpisodes(enriched, additional + latest)
    }

    private fun tmdbEpisode(row: JsonObject, id: String, season: Int, latest: Int, today: LocalDate): Episode? {
        if (row.int("season_number") != season) return null
        val number = row.int("episode_number")?.takeIf { it in 1..latest } ?: return null
        val released = row.string("air_date") ?: return null
        val date = runCatching { LocalDate.parse(released) }.getOrNull() ?: return null
        if (date > today) return null
        return Episode(
            season = season,
            episode = number,
            title = row.string("name") ?: "Episode $number",
            overview = row.string("overview")?.take(1_000),
            thumbnail = row.string("still_path")?.let { "${IMAGE_BASE}w500$it" },
            released = released,
            id = AddonEndpoint.episodeIdentifier(id, season, number),
        )
    }

    private fun mergeEpisodes(base: Meta, additional: List<Episode>): Meta {
        if (additional.isEmpty()) return base
        val byNumber = base.videos.associateByTo(linkedMapOf()) { it.season to it.episode }
        for (episode in additional) {
            val key = episode.season to episode.episode
            val current = byNumber[key]
            byNumber[key] = current?.copy(
                title = episode.title.takeIf { current.title == "Episode ${current.episode}" } ?: current.title,
                overview = current.overview?.takeIf(String::isNotBlank) ?: episode.overview,
                thumbnail = current.thumbnail?.takeIf(String::isNotBlank) ?: episode.thumbnail,
                released = current.released?.takeIf(String::isNotBlank) ?: episode.released,
            ) ?: episode
        }
        val episodes = byNumber.values.toList()
        return base.copy(
            videos = episodes,
            seasons = episodes.map(Episode::season).distinct().sorted(),
            hasInlineStreams = base.hasInlineStreams || episodes.any { it.inlineStreams != null },
        )
    }

    private suspend fun readTmdbDetails(kind: String, tmdbID: Int, key: String): JsonObject? {
        val append = "images,credits,videos,external_ids,keywords,translations,watch/providers,${if (kind == "tv") "content_ratings" else "release_dates"}"
        val url = "$tmdbBaseURL/$kind/$tmdbID?api_key=$key&append_to_response=$append" +
            "&include_image_language=en,null"
        return readTmdbObject(url)
    }

    private suspend fun readTmdbObject(url: String): JsonObject? =
        try { json.parseToJsonElement(okHttp.getText(url, MAX_RESPONSE_BYTES).body) as? JsonObject }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }

    private fun tmdbMeta(type: String, id: String, tmdbID: Int, root: JsonObject): Meta? {
        val title = root.string(if (type == "series") "name" else "title")?.takeIf(String::isNotBlank)
            ?: return null
        val base = Meta(id, type, title, imdbID = id.takeIf(IMDB_ID::matches))
        return mergeTmdb(base, tmdbKind(type), tmdbID, root)
    }

    private fun mergeTmdb(base: Meta, kind: String, tmdbID: Int, root: JsonObject): Meta {
        val directors = ((root["credits"] as? JsonObject)?.get("crew") as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }.filter { it.string("job") == "Director" }
        val creators = (root["created_by"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val region = watchRegion.uppercase(Locale.ROOT)
        val availability = ((root["watch/providers"] as? JsonObject)?.get("results") as? JsonObject)
            ?.get(region) as? JsonObject
        val providers = listOf("flatrate", "free", "ads").flatMap { (availability?.get(it) as? JsonArray).orEmpty() }
            .mapNotNull { it as? JsonObject }.sortedBy { it.int("display_priority") ?: Int.MAX_VALUE }
            .mapNotNull { provider ->
                val id = provider.int("provider_id")?.takeIf { it > 0 } ?: return@mapNotNull null
                val name = provider.string("provider_name") ?: return@mapNotNull null
                val logo = provider.string("logo_path")?.takeIf { it.startsWith('/') && !it.startsWith("//") }
                    ?: return@mapNotNull null
                StreamingProvider(id, name, "${IMAGE_BASE}w92$logo")
            }.distinctBy { it.id }
        val images = root["images"] as? JsonObject
        val title = root.string(if (kind == "tv") "name" else "title")
        val year = root.string(if (kind == "tv") "first_air_date" else "release_date")?.take(4)?.toIntOrNull()
        val tmdbImdb = (root["external_ids"] as? JsonObject)?.string("imdb_id")
        val sameWork = base.imdbID == null || tmdbImdb == null || base.imdbID == tmdbImdb
        val posters = (base.posterCandidates + if (sameWork) {
            images?.imageCandidates("posters", POSTER_SIZE).orEmpty() +
                listOfNotNull(root.string("poster_path")?.takeIf { it.startsWith('/') && !it.startsWith("//") }
                    ?.let { "$IMAGE_BASE$POSTER_SIZE$it" })
        } else emptyList()).distinct().take(5)
        val backdrops = (base.backdropCandidates + if (sameWork) {
            images?.imageCandidates("backdrops", BACKDROP_SIZE).orEmpty() +
                listOfNotNull(root.string("backdrop_path")?.takeIf { it.startsWith('/') && !it.startsWith("//") }
                    ?.let { "$IMAGE_BASE$BACKDROP_SIZE$it" })
        } else emptyList()).distinct().take(5)
        val translations = (root["translations"] as? JsonObject)?.get("translations") as? JsonArray
        val aliases = if (sameWork) listOfNotNull(title, root.string(if (kind == "tv") "original_name" else "original_title")) +
            translations.orEmpty().mapNotNull { translation ->
                ((translation as? JsonObject)?.get("data") as? JsonObject)?.string(if (kind == "tv") "name" else "title")
            } else emptyList()

        return base.copy(
            title = displayTitleOrNull(base.title, base.id) ?: title ?: base.title,
            titleAliases = (base.titleAliases + aliases).filter { it.length in 1..320 }.distinct().take(100),
            // A same-IMDb movie detail can correct a stale catalog release year before source matching.
            year = year?.takeIf { kind == "movie" && base.imdbID?.matches(IMDB_ID) == true && tmdbImdb == base.imdbID }
                ?: base.year ?: year,
            genres = (base.genres + (root["genres"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.string("name") }).distinct(),
            castNames = (base.castNames + (root["credits"] as? JsonObject)?.let { credits ->
                (credits["cast"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.string("name") }
            }.orEmpty()).distinct().take(200),
            directors = (base.directors + directors.mapNotNull { it.string("name") }).distinct().take(50),
            directorIDs = directors.mapNotNull { row ->
                val name = row.string("name") ?: return@mapNotNull null
                row.int("id")?.takeIf { it > 0 }?.let { name to it }
            }.toMap(),
            creators = creators.mapNotNull { it.string("name") }.distinct().take(50),
            creatorIDs = creators.mapNotNull { row ->
                val name = row.string("name") ?: return@mapNotNull null
                row.int("id")?.takeIf { it > 0 }?.let { name to it }
            }.toMap(),
            streamingProviders = providers,
            studios = (base.studios + (root["production_companies"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.string("name") }).distinct().take(50),
            keywords = (base.keywords + (root["keywords"] as? JsonObject)?.let { keywords ->
                ((keywords["keywords"] ?: keywords["results"]) as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonObject)?.string("name") }
            }.orEmpty()).distinct().take(200),
            spokenLanguages = (base.spokenLanguages + (root["spoken_languages"] as? JsonArray).orEmpty()
                .mapNotNull { OriginalAudioLanguage.normalize((it as? JsonObject)?.string("iso_639_1")) }).distinct(),
            releaseStatus = root.string("status") ?: base.releaseStatus,
            releaseDate = root.string(if (kind == "tv") "first_air_date" else "release_date") ?: base.releaseDate,
            tmdbVotes = root.int("vote_count") ?: base.tmdbVotes,
            popularity = root.double("popularity")?.takeIf { it.isFinite() && it >= 0 } ?: base.popularity,
            tmdbRating = root.double("vote_average")?.takeIf { it in 0.0..10.0 && (root.int("vote_count") ?: 0) > 0 } ?: base.tmdbRating,
            countries = (base.countries + (root["production_countries"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.string("name") }
                + (root["origin_country"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }).distinct(),
            tmdbID = tmdbID,
            imdbID = base.imdbID ?: (root["external_ids"] as? JsonObject)?.string("imdb_id")
                ?.takeIf { it.matches(Regex("tt[0-9]+")) },
            originalLanguage = OriginalAudioLanguage.normalize(root.string("original_language")) ?: base.originalLanguage,
            logo = base.logo ?: images?.bestImage("logos", LOGO_SIZE, preferTextless = false),
            backdrop = backdrops.firstOrNull(), backdropFallbacks = backdrops.drop(1),
            poster = posters.firstOrNull(), posterFallbacks = posters.drop(1),
            runtimeMinutes = base.runtimeMinutes ?: root.int("runtime")
                ?: (root["episode_run_time"] as? JsonArray)
                    ?.firstNotNullOfOrNull { (it as? JsonPrimitive)?.intOrNull },
            certification = base.certification ?: certification(root, kind),
            description = base.description ?: root.string("overview"),
            trailerYouTubeID = base.trailerYouTubeID ?: trailer(root),
            cast = if (base.cast.isEmpty()) credits(root) else enrichCast(base.cast, root),
        )
    }

    private suspend fun findTmdbID(imdbID: String, kind: String, key: String): Int? {
        val url = "$tmdbBaseURL/find/$imdbID?api_key=$key&external_source=imdb_id"
        val root = readTmdbObject(url) ?: return null
        val listKey = if (kind == "tv") "tv_results" else "movie_results"
        return (root[listKey] as? JsonArray)?.firstNotNullOfOrNull {
            (it as? JsonObject)?.int("id")
        }
    }

    private fun enrichCast(members: List<CastMember>, root: JsonObject): List<CastMember> {
        val byName = credits(root).groupBy { it.name.trim().lowercase(Locale.ROOT) }
        return members.map { member ->
            val matches = byName[member.name.trim().lowercase(Locale.ROOT)] ?: return@map member
            val match = matches.first()
            val personID = matches.mapNotNull { it.personID }.distinct().singleOrNull()
            member.copy(role = member.role ?: match.role, photo = member.photo ?: match.photo,
                personID = member.personID ?: personID)
        }
    }

    private fun credits(root: JsonObject): List<CastMember> {
        val cast = ((root["credits"] as? JsonObject)?.get("cast") as? JsonArray).orEmpty()
        return cast.mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val name = entry.string("name") ?: return@mapNotNull null
            CastMember(
                name = name,
                role = entry.string("character"),
                photo = entry.string("profile_path")?.let { "$IMAGE_BASE$PROFILE_SIZE$it" },
                personID = entry.int("id")?.takeIf { it > 0 },
            )
        }.take(MAX_CAST)
    }

    private fun trailer(root: JsonObject): String? {
        val videos = ((root["videos"] as? JsonObject)?.get("results") as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { it.string("site").equals("YouTube", ignoreCase = true) }
        return (
            videos.firstOrNull { it.string("type").equals("Trailer", ignoreCase = true) }
                ?: videos.firstOrNull()
            )?.string("key")
    }

    private fun certification(root: JsonObject, kind: String): String? {
        if (kind == "tv") {
            val results = ((root["content_ratings"] as? JsonObject)?.get("results") as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }
            return results.firstOrNull { it.string("iso_3166_1") == "US" }?.string("rating")
                ?: results.firstNotNullOfOrNull { it.string("rating") }
        }
        val results = ((root["release_dates"] as? JsonObject)?.get("results") as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
        val us = results.firstOrNull { it.string("iso_3166_1") == "US" } ?: results.firstOrNull()
        return (us?.get("release_dates") as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.string("certification") }
            .firstOrNull(String::isNotBlank)
    }

    /**
     * [preferTextless] is the whole point of this function. A backdrop with burnt-in English
     * titling is worse than a clean still, so art asks for the language-free file first. A logo is
     * the opposite case: the lettering IS the logo, so there English wins.
     */
    private fun JsonObject.bestImage(
        key: String,
        size: String,
        preferTextless: Boolean = true,
    ): String? = imageCandidates(key, size, preferTextless).firstOrNull()

    private fun JsonObject.imageCandidates(
        key: String,
        size: String,
        preferTextless: Boolean = true,
    ): List<String> {
        val list = (get(key) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .filter { image ->
                val width = image.int("width")
                val height = image.int("height")
                // Reject a known portrait as a landscape. Unknown dimensions remain usable.
                val path = image.string("file_path")
                path != null && path.startsWith('/') && !path.startsWith("//") && path.length in 2..200 &&
                    (key != "backdrops" || width == null || height == null || width > height)
            }
        val sufficientWidth = when (key) {
            "backdrops" -> 1920
            "logos" -> 620
            else -> 500
        }
        val ordered = list.sortedWith(
            compareBy<JsonObject> {
                when (it.string("iso_639_1")) {
                    null -> if (preferTextless) 0 else 1
                    "en" -> if (preferTextless) 1 else 0
                    else -> 2
                }
            }.thenBy { image ->
                when (val width = image.int("width")) {
                    null -> 1
                    in sufficientWidth..Int.MAX_VALUE -> 0
                    else -> 2
                }
            }.thenByDescending { it.double("vote_average") ?: 0.0 }
                .thenByDescending { it.int("width") ?: 0 },
        )
        return ordered.mapNotNull { it.string("file_path") }.distinct().take(4).map { "$IMAGE_BASE$size$it" }
    }

    private fun readDisk(key: String): Pair<Meta, Long>? = synchronized(diskLock) {
        val file = File(directory ?: return@synchronized null, fileName(key))
        if (!file.isFile) return@synchronized null
        runCatching {
            val text = file.readText()
            val split = text.indexOf('\n')
            if (split < 0) return@runCatching null
            val storedAt = text.substring(0, split).toLongOrNull() ?: return@runCatching null
            if (clock() - storedAt !in 0 until MAX_STALE_MILLIS) return@runCatching null
            json.decodeFromString(Meta.serializer(), text.substring(split + 1)) to storedAt
        }.getOrNull()
    }

    private fun writeDisk(key: String, meta: Meta, generation: Long) = synchronized(diskLock) {
        if (generation != cacheGeneration) return@synchronized
        val dir = directory ?: return@synchronized
        runCatching {
            dir.mkdirs()
            val target = File(dir, fileName(key))
            // Readers and maintenance see only complete JSON files. An active writer owns a
            // unique .tmp file, which is never an eviction candidate.
            val staging = File.createTempFile(target.name, ".tmp", dir)
            try {
                staging.writeText("${clock()}\n" + json.encodeToString(Meta.serializer(), meta))
                if (!staging.renameTo(target)) return@runCatching
            } finally {
                staging.delete()
            }
            writesSinceDiskMaintenance++
            if (writesSinceDiskMaintenance >= DISK_MAINTENANCE_WRITE_INTERVAL) {
                writesSinceDiskMaintenance = 0
                trimDisk(dir, target)
            }
        }
    }

    /** Called only from the serialized IO write path, never from focus or cache reads. */
    private fun trimDisk(dir: File, activeTarget: File) {
        val files = dir.listFiles { file -> file.isFile && CACHE_FILE_NAME.matches(file.name) }
            ?.toList().orEmpty()
        var count = files.size
        var bytes = files.sumOf(File::length)
        if (count <= MAX_DISK_ENTRIES && bytes <= MAX_DISK_BYTES) return

        val protectedNames = inFlight.keys.mapTo(mutableSetOf(), ::fileName)
        val candidates = files.filterNot { it == activeTarget || it.name in protectedNames }
            .sortedBy(File::lastModified)
        for (file in candidates) {
            if (count <= MAX_DISK_ENTRIES && bytes <= MAX_DISK_BYTES) break
            val size = file.length()
            if (file.delete()) {
                count--
                bytes -= size
            }
        }
    }

    /** Refresh older entries that discarded alternate provider artwork. */
    private fun fileName(key: String): String {
        val isTmdb = directTmdbID(key.substringBefore(':'), key.substringAfter(':')) != null
        val version = if (isTmdb) "tmdb-detail-identity-year-v6" else "detail-identity-year-v6"
        return MessageDigest.getInstance("SHA-256").digest("$version:${watchRegion.uppercase(Locale.ROOT)}:$key".toByteArray())
            .joinToString("") { "%02x".format(it) } + ".json"
    }

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)

    private fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull

    private fun JsonObject.double(key: String): Double? = (get(key) as? JsonPrimitive)?.doubleOrNull

    companion object {
        private val IMDB_ID = Regex("tt[0-9]+")
        private val TMDB_ID = Regex("tmdb:(?:(movie|series|tv):)?([0-9]+)")
        const val TMDB_BASE_URL: String = "https://api.themoviedb.org/3"
        const val IMAGE_BASE: String = "https://image.tmdb.org/t/p/"

        /** Wide enough for a 1080p hero without paying for `/original/`. */
        const val BACKDROP_SIZE: String = "w1280"
        const val LOGO_SIZE: String = "w500"
        const val POSTER_SIZE: String = "w500"
        const val PROFILE_SIZE: String = "w185"

        const val SERIES_CACHE_TTL_MILLIS: Long = 30 * 60 * 1_000L
        const val CACHE_TTL_MILLIS: Long = 24 * 60 * 60 * 1_000L
        const val MAX_STALE_MILLIS: Long = 7 * 24 * 60 * 60 * 1_000L
        const val MAX_RESPONSE_BYTES: Int = 1 * 1_024 * 1_024
        const val MAX_CAST: Int = 20
        const val MAX_MEMORY_ENTRIES: Int = 64
        /** Keep recent metadata without allowing a long browsing history to grow disk forever. */
        const val MAX_DISK_ENTRIES: Int = 512
        const val MAX_DISK_BYTES: Long = 64L * 1_024 * 1_024
        const val DISK_MAINTENANCE_WRITE_INTERVAL: Int = 16

        private val CACHE_FILE_NAME = Regex("[0-9a-f]{64}\\.json")
        private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    }
}
