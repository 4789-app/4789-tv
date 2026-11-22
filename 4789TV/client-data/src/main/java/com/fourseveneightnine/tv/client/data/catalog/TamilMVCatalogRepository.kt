package com.fourseveneightnine.tv.client.data.catalog

import android.graphics.BitmapFactory
import android.util.AtomicFile
import com.fourseveneightnine.tv.client.data.addons.send
import com.fourseveneightnine.tv.client.data.meta.MetaRepository
import com.fourseveneightnine.tv.client.data.streams.StreamFacts
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.text.Normalizer
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer

data class TamilMVCatalogState(
    val items: List<CatalogItem> = emptyList(),
    val updatedAtMillis: Long = 0,
    val loading: Boolean = false,
    val message: String? = null,
)

/** The receiver collects the current forum itself. No phone or private catalog token is used. */
class TamilMVCatalogRepository(
    filesDir: File,
    private val http: OkHttpClient,
    private val mirrors: List<String> = DEFAULT_MIRRORS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val tmdbKey: () -> String? = { null },
) {
    @Serializable private data class Saved(val updatedAtMillis: Long, val items: List<CatalogItem>)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val disk = AtomicFile(File(filesDir, "receiver-tamilmv/catalog.json"))
    private val gate = Mutex()
    private val refreshGate = Mutex()
    private val sourceGate = Mutex()
    private val artworkGate = kotlinx.coroutines.sync.Semaphore(4)
    private val artworkHttp by lazy {
        http.newBuilder().followRedirects(false).followSslRedirects(false).callTimeout(3, TimeUnit.SECONDS).build()
    }
    private val mutableState = MutableStateFlow(TamilMVCatalogState())
    val state = mutableState.asStateFlow()
    private var sourceGeneration = 0L
    private val sourceChecks = mutableMapOf<String, Long>()
    private val artworkChecks = mutableMapOf<String, Long>()

    suspend fun hydrate() = withContext(Dispatchers.IO) {
        if (mutableState.value.items.isNotEmpty()) return@withContext
        gate.withLock {
            if (mutableState.value.items.isNotEmpty()) return@withLock
            runCatching {
                require(disk.baseFile.length() in 1..MAX_CACHE_BYTES)
                disk.openRead().use { json.decodeFromString<Saved>(it.bufferedReader().readText()) }
            }.getOrNull()?.let { saved ->
                mutableState.value = TamilMVCatalogState(saved.items, saved.updatedAtMillis)
            }
        }
    }

    suspend fun refresh(force: Boolean = false, knownItems: List<CatalogItem> = emptyList()) = withContext(Dispatchers.IO) {
        if (mutableState.value.loading) return@withContext
        hydrate()
        if (!refreshGate.tryLock()) return@withContext
        try {
            val before = mutableState.value
            if (!force && before.updatedAtMillis > 0 && clock() - before.updatedAtMillis in 0 until REFRESH_MILLIS) return@withContext
            mutableState.update { it.copy(loading = true, message = null) }
            try {
                var rows: List<CatalogItem> = emptyList()
                var base: String? = null
                var lastFailure: Exception? = null
                for (mirror in mirrors) {
                    try {
                        val parsed = parseListing(fetch(mirror), mirror)
                        if (parsed.isEmpty()) continue
                        rows = parsed
                        base = parsed.first().sourcePageURL?.toHttpUrlOrNull()?.let { "https://${it.host}" }
                        break
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { lastFailure = failure }
                }
                if (rows.isEmpty()) throw IOException(lastFailure?.message
                    ?: "Tamil MV did not return a release list. Check the receiver network, then Retry.")
                // Current homepage order wins. Two current Tamil-language forum pages add releases
                // beyond its widgets; a failed extra page never discards the working homepage.
                val extra = base?.let { host -> coroutineScope {
                    (1..2).map { page -> async {
                        try { parseListing(fetch("$host/index.php?/forums/forum/11-web-hd-itunes-hd-bluray/&page=$page"), host) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { emptyList() }
                    } }.awaitAll().flatten()
                } }.orEmpty()
                val currentTopics = (rows + extra).distinctBy { it.canonicalId }.take(MAX_ITEMS)
                rows = gate.withLock {
                    val saved = knownItems + mutableState.value.items
                    val topics = saved.associateBy { it.canonicalId }
                    val wanted = currentTopics.mapTo(hashSetOf(), ::titleIdentity)
                    val metadata = saved.filter { titleIdentity(it) in wanted }.groupBy(::titleIdentity)
                        .mapValues { (_, editions) -> sharedMetadata(editions) }
                    val merged = currentTopics.map { fresh ->
                        val prior = topics[fresh.canonicalId]?.takeIf { titleIdentity(it) == titleIdentity(fresh) }
                        val row = prior?.let {
                            val keepCorrectedSeason = fresh.season == 1 && (it.season ?: 1) > 1
                            fresh.withMetadata(it).copy(
                                playbackSources = it.playbackSources,
                                season = if (keepCorrectedSeason) it.season else fresh.season,
                                episode = if (keepCorrectedSeason) it.episode else fresh.episode,
                            )
                        } ?: fresh
                        val match = metadata[titleIdentity(row)]
                        if (row.hasMetadataID || match == null) row else row.withMetadata(match)
                    }
                    sourceGeneration++
                    sourceChecks.clear()
                    if (force) artworkChecks.clear()
                    publish(merged, clock())
                    merged
                }
                // Warm the first screen once per title. Visible cards and Details can resolve
                // independently while a mirror or a background batch is still in flight.
                val matches = mutableListOf<CatalogItem>()
                withTimeoutOrNull(8_000L) {
                    for (batch in rows.filterNot { it.hasMetadataID }.distinctBy(::titleIdentity).take(32).chunked(4)) {
                        matches.addAll(coroutineScope { batch.map { async { artworkGate.withPermit { metadataMatch(it) } } }.awaitAll() })
                    }
                }
                if (matches.isNotEmpty()) storeMatches(matches)
                mutableState.update { it.copy(loading = false) }
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(loading = false) }
                throw cancelled
            } catch (failure: Exception) {
                mutableState.update { it.copy(loading = false, message = failure.message ?: "Tamil MV could not refresh. Retry.") }
            }
        } finally { refreshGate.unlock() }
    }

    /** Read the selected title's release topics. Refresh still never acquires torrents or video. */
    suspend fun sources(item: CatalogItem): CatalogItem = withContext(Dispatchers.IO) {
        // Serialize source checks without holding the lock used by refresh and poster updates.
        sourceGate.withLock {
            val (topics, pending, generation) = gate.withLock {
                val snapshot = mutableState.value
                val current = snapshot.items.firstOrNull {
                    it.canonicalId == item.canonicalId && it.mediaType == item.mediaType
                } ?: item
                val titleKey = titleIdentity(current)
                val topics = (sequenceOf(current) + snapshot.items.asSequence().filter { titleIdentity(it) == titleKey })
                    .distinctBy { it.canonicalId }.take(MAX_TITLE_TOPICS).toList()
                val now = clock()
                val pending = topics.filter { topic ->
                    topic.sourcePageURL != null &&
                        now - (sourceChecks[topic.canonicalId] ?: Long.MIN_VALUE / 2) !in 0 until 60_000L
                }
                Triple(topics, pending, sourceGeneration)
            }
            val updated = mutableMapOf<String, CatalogItem>()
            var failure: Exception? = null
            for (batch in pending.chunked(4)) {
                val results = coroutineScope {
                    batch.map { topic -> async {
                        try {
                            val page = requireNotNull(topic.sourcePageURL)
                            require(isSourceURL(page)) { "Invalid Tamil MV topic." }
                            val html = fetch(page)
                            val corrected = correctTopicSeason(topic, html)
                            val sources = parseMagnets(html, corrected)
                            require(sources.isNotEmpty()) { "No magnet link was found for this title." }
                            Result.success(corrected.copy(playbackSources = sources))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            Result.failure(error)
                        }
                    } }.awaitAll()
                }
                for (result in results) {
                    val topic = result.getOrNull()
                    if (topic != null) updated[topic.canonicalId] = topic
                    else if (failure == null) failure = result.exceptionOrNull() as? Exception
                }
            }
            if (updated.isNotEmpty()) gate.withLock {
                val current = mutableState.value
                if (sourceGeneration == generation) {
                    val checkedAt = clock()
                    val next = current.items.map { row ->
                        val topic = updated[row.canonicalId] ?: return@map row
                        sourceChecks[row.canonicalId] = checkedAt
                        row.copy(season = topic.season, episode = topic.episode, playbackSources = topic.playbackSources)
                    }
                    if (next != current.items) publish(next, current.updatedAtMillis)
                }
            }
            val sources = rankedSources(topics.flatMap { (updated[it.canonicalId] ?: it).playbackSources })
            if (sources.isEmpty()) throw failure ?: IOException("No magnet link was found for this title.")
            val selected = topics.first()
            (updated[selected.canonicalId] ?: selected).copy(playbackSources = sources)
        }
    }

    private fun publish(items: List<CatalogItem>, updatedAt: Long) {
        mutableState.update { it.copy(items = items, updatedAtMillis = updatedAt) }
        var output: java.io.FileOutputStream? = null
        try {
            disk.baseFile.parentFile?.mkdirs()
            output = disk.startWrite()
            output.write(json.encodeToString(Saved.serializer(), Saved(updatedAt, items)).toByteArray())
            disk.finishWrite(output)
        } catch (_: Exception) {
            if (output != null) disk.failWrite(output)
            mutableState.update { it.copy(message = "Catalog loaded, but could not be saved. Check receiver storage.") }
        }
    }

    private suspend fun fetch(url: String): String {
        val response = http.send(Request.Builder().url(url).header("User-Agent", USER_AGENT).build(), 3 * 1024 * 1024)
        if (!response.successful) throw IOException("Tamil MV request failed (HTTP ${response.code}). Retry.")
        return response.body
    }

    private val CatalogItem.hasMetadataID: Boolean
        get() = imdbId?.matches(IMDB_ID) == true || (tmdbId ?: 0) > 0

    private fun CatalogItem.withMetadata(match: CatalogItem) = copy(
        imdbId = match.imdbId ?: imdbId, tmdbId = match.tmdbId ?: tmdbId,
        posterUrl = match.posterUrl ?: posterUrl, backdropUrl = match.backdropUrl ?: backdropUrl,
        overview = match.overview ?: overview, genres = match.genres.ifEmpty { genres },
        metadataYear = match.metadataYear ?: metadataYear,
    )

    private fun sharedMetadata(editions: List<CatalogItem>): CatalogItem? {
        val identified = editions.filter { it.hasMetadataID }
        val imdb = identified.mapNotNull { it.imdbId }.toSet()
        val tmdb = identified.mapNotNull { it.tmdbId }.toSet()
        if (imdb.size > 1 || tmdb.size > 1) return null
        return identified.lastOrNull { (imdb.isEmpty() || it.imdbId in imdb) && (tmdb.isEmpty() || it.tmdbId in tmdb) }
    }

    /** Details can be opened before the visible-poster pass or from a paired snapshot. */
    suspend fun matchForDetails(item: CatalogItem): CatalogItem = withContext(Dispatchers.IO) {
        if (item.hasMetadataID) return@withContext item
        val cached = mutableState.value.items.firstOrNull {
            it.canonicalId == item.canonicalId && titleIdentity(it) == titleIdentity(item) && it.hasMetadataID
        }
        if (cached != null) return@withContext item.withMetadata(cached).copy(season = cached.season, episode = cached.episode)
        val match = withTimeoutOrNull(7_000L) { artworkGate.withPermit { metadataMatch(item) } } ?: return@withContext item
        storeMatches(listOf(match))
        match
    }

    suspend fun enrichVisiblePosters(ids: Set<String>) = withContext(Dispatchers.IO) {
        val pending = gate.withLock {
            val now = clock()
            mutableState.value.items.asSequence().filter {
                it.canonicalId in ids && (!it.hasMetadataID || it.posterUrl == null) &&
                    now - (artworkChecks[it.canonicalId] ?: Long.MIN_VALUE / 2) !in 0 until 600_000L
            }.distinctBy(::titleIdentity).toList()
        }
        for (batch in pending.chunked(4)) {
            val found = coroutineScope { batch.map { item -> async {
                artworkGate.withPermit { enrichArtwork(item) }
            } }.awaitAll() }
            storeMatches(found, checkedVisible = true)
        }
    }

    /** The visible grid runs first. Navigation or Play cancels the remaining title checks. */
    suspend fun backfillPosters(visibleIds: Set<String>) = withContext(Dispatchers.IO) {
        val missing = state.value.items.asSequence().distinctBy(::titleIdentity)
            .filter { it.canonicalId !in visibleIds && (!it.hasMetadataID || it.posterUrl == null) }
            .map { it.canonicalId }.toList()
        for (batch in missing.chunked(4)) {
            delay(500)
            enrichVisiblePosters(batch.toSet())
        }
    }

    private suspend fun enrichArtwork(item: CatalogItem): CatalogItem {
        var topicRead = false
        var html: String? = null
        suspend fun topic(): String? {
            if (!topicRead) {
                topicRead = true
                html = withTimeoutOrNull(3_500L) { readTopic(item) }
            }
            return html
        }
        val match = if (item.hasMetadataID) item else
            withTimeoutOrNull(7_000L) { metadataMatch(item, ::topic) } ?: item
        if (match.posterUrl != null) return match
        val page = item.sourcePageURL ?: return match
        val portrait = topic()?.let { withTimeoutOrNull(4_000L) { forumPoster(it, page) } }
        return match.copy(posterUrl = portrait)
    }

    private suspend fun readTopic(item: CatalogItem): String? {
        val page = item.sourcePageURL?.takeIf(::isSourceURL) ?: return null
        return try { fetch(page) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    private suspend fun storeMatches(matches: List<CatalogItem>, checkedVisible: Boolean = false) {
        val byTitle = matches.associateBy(::titleIdentity)
        gate.withLock {
            if (checkedVisible) matches.forEach { artworkChecks[it.canonicalId] = clock() }
            val current = mutableState.value
            val next = current.items.map { row ->
                val match = byTitle[titleIdentity(row)] ?: return@map row
                val sameTopic = row.canonicalId == match.canonicalId
                val enriched = when {
                    !row.hasMetadataID && match.hasMetadataID -> row.withMetadata(match)
                    sameTopic && row.posterUrl == null -> row.copy(posterUrl = match.posterUrl)
                    else -> row
                }
                if (sameTopic && match.hasMetadataID && (match.season ?: 0) > (row.season ?: 0))
                    enriched.copy(season = match.season, episode = match.episode)
                else enriched
            }
            if (next != current.items) publish(next, current.updatedAtMillis)
        }
    }

    private suspend fun metadataMatch(item: CatalogItem, topic: suspend () -> String? = { readTopic(item) }): CatalogItem {
        if (item.year == null || item.mediaType !in setOf("movie", "series")) return item
        val key = tmdbKey()?.takeIf(String::isNotBlank)
        if (key == null) return bestMatches(searchMatches(item, null)).singleOrNull()?.card ?: item

        var release = item
        var candidates = bestMatches(searchMatches(release, key))
        if (item.mediaType == "movie" && candidates.isEmpty()) {
            val unfiltered = searchMatches(item, key, withoutYearFilter = true)
            candidates = bestMatches(unfiltered.filter { it.premiereYear == item.year })
            if (candidates.isEmpty()) {
                val laterDigital = unfiltered.filter { it.premiereYear < item.year && it.exactName }
                if (laterDigital.size in 1..MAX_TIED_CANDIDATES) {
                    val proof = coroutineScope {
                        laterDigital.map { candidate ->
                            async { candidate to hasDigitalRelease(candidate.card.tmdbId, item.year, key) }
                        }.awaitAll()
                    }
                    if (proof.none { it.second == null }) {
                        candidates = proof.filter { it.second == true }.map { (candidate, _) ->
                            candidate.copy(card = candidate.card.copy(metadataYear = candidate.premiereYear))
                        }
                    }
                }
            }
        }
        if (item.mediaType == "series") {
            if (candidates.isEmpty() || candidates.size > 1) {
                val corrected = topic()?.let { correctTopicSeason(release, it) } ?: release
                if (corrected.season != release.season) {
                    release = corrected
                    candidates = bestMatches(searchMatches(release, key))
                }
            }
            if (candidates.any { it.needsSeasonProof || release.season != item.season }) {
                candidates = if (candidates.size <= MAX_TIED_CANDIDATES) {
                    verifiedCandidates(candidates) { seasonMatch(it, release, key) }
                } else emptyList()
            } else if ((release.season ?: 1) > 1 && candidates.size == 1) {
                seasonMatch(candidates.single(), release, key)?.let { candidates = listOf(it) }
            }
        }
        if (candidates.size == 1) {
            val chosen = candidates.single().card
            if (chosen.posterUrl != null) return chosen
            val cine = bestMatches(searchMatches(release, null)).singleOrNull()?.card ?: return chosen
            return if (matchesImdb(chosen, cine.imdbId, key)) chosen.copy(
                imdbId = cine.imdbId, posterUrl = cine.posterUrl,
                backdropUrl = chosen.backdropUrl ?: cine.backdropUrl, overview = chosen.overview ?: cine.overview,
            ) else chosen
        }
        val cine = bestMatches(searchMatches(release, null)).singleOrNull()?.card
        if (candidates.isEmpty()) return cine ?: release
        if (cine == null) return release
        val id = tmdbForImdb(cine.imdbId, release.mediaType, key) ?: return release
        return candidates.singleOrNull { it.card.tmdbId == id }?.card?.let { match ->
            match.copy(imdbId = cine.imdbId, posterUrl = match.posterUrl ?: cine.posterUrl)
        } ?: release
    }

    private data class MetadataCandidate(
        val card: CatalogItem,
        val premiereYear: Int,
        val score: Int,
        val exactName: Boolean,
        val needsSeasonProof: Boolean,
    )

    private fun bestMatches(candidates: List<MetadataCandidate>): List<MetadataCandidate> {
        val best = candidates.maxOfOrNull { it.score } ?: return emptyList()
        return candidates.filter { it.score == best }
    }

    private suspend fun verifiedCandidates(
        candidates: List<MetadataCandidate>,
        verify: suspend (MetadataCandidate) -> MetadataCandidate?,
    ): List<MetadataCandidate> = coroutineScope {
        candidates.map { async { verify(it) } }.awaitAll().filterNotNull()
    }

    private suspend fun tmdbForImdb(imdb: String?, type: String, key: String): Int? {
        if (imdb == null) return null
        val field = if (type == "movie") "movie_results" else "tv_results"
        return tmdbJson("find/$imdb", key, "external_source" to "imdb_id")?.array(field)
            ?.mapNotNull { (it as? JsonObject)?.text("id")?.toIntOrNull() }?.distinct()?.singleOrNull()
    }

    private suspend fun matchesImdb(item: CatalogItem, imdb: String?, key: String) =
        item.tmdbId != null && tmdbForImdb(imdb, item.mediaType, key) == item.tmdbId

    private suspend fun hasDigitalRelease(id: Int?, year: Int, key: String): Boolean? {
        if (id == null) return null
        val response = tmdbJson("movie/$id/release_dates", key) ?: return null
        val regions = response["results"] as? JsonArray ?: return null
        return regions.any { region ->
            (region as? JsonObject)?.array("release_dates").orEmpty().any { date ->
                date is JsonObject && date.text("type") == "4" && date.text("release_date")?.take(4) == year.toString()
            }
        }
    }

    private suspend fun seasonMatch(candidate: MetadataCandidate, release: CatalogItem, key: String): MetadataCandidate? {
        val season = release.season ?: return null
        val id = candidate.card.tmdbId ?: return null
        val details = tmdbJson("tv/$id/season/$season", key) ?: return null
        if (details.text("season_number")?.toIntOrNull() != season ||
            details.text("air_date")?.take(4)?.toIntOrNull() != release.year) return null
        return candidate.copy(card = candidate.card.copy(
            posterUrl = tmdbImage(details.text("poster_path"), MetaRepository.POSTER_SIZE) ?: candidate.card.posterUrl,
        ))
    }

    private suspend fun tmdbJson(path: String, key: String, vararg parameters: Pair<String, String>): JsonObject? =
        providerJson("${MetaRepository.TMDB_BASE_URL}/$path".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", key)
            .apply { parameters.forEach { (name, value) -> addQueryParameter(name, value) } }.build())

    private suspend fun providerJson(url: HttpUrl): JsonObject? = withTimeoutOrNull(2_500L) {
        try {
            val response = http.send(Request.Builder().url(url).build(), 1024 * 1024)
            if (response.successful) json.parseToJsonElement(response.body) as? JsonObject else null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    /** Preserve title punctuation for ties and use the original language to verify franchise aliases. */
    private suspend fun searchMatches(item: CatalogItem, key: String?, withoutYearFilter: Boolean = false): List<MetadataCandidate> {
        val movie = item.mediaType == "movie"
        val bundleTitle = if (movie) movieBundleTitle(item.title) else null
        val query = bundleTitle ?: item.title
        val root = if (key != null) {
            val path = if (movie) "search/movie" else "search/tv"
            if (movie && !withoutYearFilter) tmdbJson(path, key, "query" to query, "year" to item.year.toString())
            else tmdbJson(path, key, "query" to query)
        } else {
            providerJson("https://v3-cinemeta.strem.io".toHttpUrl().newBuilder()
                .addPathSegments("catalog/${item.mediaType}/top").addPathSegment("search=$query.json").build())
        } ?: return emptyList()
        val requested = normal(query)
        fun withoutArticle(title: String) = normal(title.replaceFirst(LEADING_ARTICLE, ""))
        val bundleStem = bundleTitle?.let(::withoutArticle)
        return root.array(if (key != null) "results" else "metas").mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            if (key == null && row.text("type") != item.mediaType) return@mapNotNull null
            val date = if (key == null) row.text("releaseInfo") ?: row.text("year")
                else row.text(if (movie) "release_date" else "first_air_date")
            val year = date?.take(4)?.toIntOrNull() ?: return@mapNotNull null
            val requestedYear = item.year ?: return@mapNotNull null
            val sameYear = requestedYear == year
            val allowEarlier = if (movie) withoutYearFilter else (item.season ?: 1) > 1
            if (!sameYear && (!allowEarlier || year > requestedYear)) return@mapNotNull null
            val names = if (key == null) listOfNotNull(row.text("name")) else
                listOfNotNull(row.text(if (movie) "title" else "name"), row.text(if (movie) "original_title" else "original_name"))
            val exact = names.any { normal(it) == requested }
            val subtitle = names.any { name ->
                val parts = name.split(SUBTITLE, limit = 2)
                parts.size == 2 && normal(parts.first()) == requested
            }
            val bundleNameMatches = bundleStem != null && names.any { name ->
                val parts = name.split(SUBTITLE, limit = 2)
                withoutArticle(name) == bundleStem || (parts.size == 2 && withoutArticle(parts.first()) == bundleStem)
            }
            val locale = if (!movie && key != null) SERIES_LANGUAGES[query.substringAfterLast(' ').lowercase()] else null
            val languageAlias = locale != null && locale == row.text("original_language") &&
                names.any { normal(it) == normal(query.substringBeforeLast(' ')) }
            if (!exact && !subtitle && !bundleNameMatches && !languageAlias) return@mapNotNull null
            val match = if (key != null) {
                val id = row.text("id")?.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
                item.copy(tmdbId = id,
                    posterUrl = tmdbImage(row.text("poster_path"), MetaRepository.POSTER_SIZE) ?: item.posterUrl,
                    backdropUrl = tmdbImage(row.text("backdrop_path"), MetaRepository.BACKDROP_SIZE) ?: item.backdropUrl,
                    overview = row.text("overview")?.takeIf(String::isNotBlank)?.take(4_000) ?: item.overview)
            } else {
                val id = (row.text("imdb_id") ?: row.text("id"))?.takeIf(IMDB_ID::matches) ?: return@mapNotNull null
                item.copy(imdbId = id, posterUrl = row.text("poster")?.takeIf(::isArtworkURL) ?: item.posterUrl,
                    backdropUrl = row.text("background")?.takeIf(::isArtworkURL) ?: item.backdropUrl,
                    overview = row.text("description")?.takeIf(String::isNotBlank)?.take(4_000) ?: item.overview)
            }
            val nameScore = when {
                query.startsWith('#') && names.any { it.trim().equals(query, ignoreCase = true) } -> 6
                exact -> 4
                subtitle -> 2
                else -> 0
            }
            MetadataCandidate(match, year, nameScore + (if (sameYear && (item.season ?: 1) <= 1) 1 else 0),
                exact, needsSeasonProof = languageAlias && !exact)
        }.distinctBy { it.card.imdbId to it.card.tmdbId }
    }

    private suspend fun forumPoster(html: String, topic: String): String? {
        for (candidate in topicPosterCandidates(html, topic).take(4)) {
            if (!candidate.requiresImageCheck || verifiedPortrait(candidate.url)) return candidate.url.toString()
        }
        return null
    }

    /** Read dimensions without downloading a whole image, following a redirect, or decoding pixels. */
    private suspend fun verifiedPortrait(url: HttpUrl): Boolean = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url).header("Range", "bytes=0-65535").build()
        val call = artworkHttp.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resume(false)
            }

            override fun onResponse(call: Call, response: Response) {
                val verified = response.use { reply ->
                    val body = reply.body
                    if (!reply.isSuccessful || body == null ||
                        reply.header("Content-Type")?.substringBefore(';')?.lowercase() !in RASTER_TYPES) {
                        return@use false
                    }
                    runCatching {
                        val buffer = Buffer()
                        val source = body.source()
                        while (buffer.size < POSTER_HEADER_LIMIT) {
                            if (source.read(buffer, minOf(8192, POSTER_HEADER_LIMIT - buffer.size)) == -1L) break
                        }
                        val bytes = buffer.readByteArray()
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        bounds.outMimeType in RASTER_TYPES && portraitSize(bounds.outWidth, bounds.outHeight)
                    }.getOrDefault(false)
                }
                if (continuation.isActive) continuation.resume(verified)
            }
        })
    }

    companion object {
        val DEFAULT_MIRRORS = listOf("https://www.1tamilmv.capital", "https://www.1tamilmv.meme", "https://www.1tamilmv.fi")
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130.0.0.0 Safari/537.36"
        private const val REFRESH_MILLIS = 5 * 60_000L
        private const val MAX_ITEMS = 2_000
        private const val MAX_TITLE_TOPICS = 16
        private const val MAX_TIED_CANDIDATES = 4
        private val SERIES_LANGUAGES = mapOf("tamil" to "ta", "telugu" to "te", "kannada" to "kn",
            "malayalam" to "ml", "hindi" to "hi", "bengali" to "bn", "marathi" to "mr")
        private const val MAX_CACHE_BYTES = 16L * 1024 * 1024
        private val YEAR = Regex("\\b(?:19|20)\\d{2}\\b")
        private val PAREN_YEAR = Regex("\\(((?:19|20)\\d{2})\\)")
        private val ANCHOR = Regex("""<a\b[^>]*?href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val TAG = Regex("<[^>]+>")
        private val BOUNDARY = Regex("<(?:br\\b[^>]*|/?(?:p|div|li|tr|h[1-6])\\b[^>]*)>", RegexOption.IGNORE_CASE)
        private val TOPIC = Regex("/forums/topic/(\\d+)")
        private val SEASON = Regex("(?i)\\bS0*(\\d{1,2})(?!\\d)")
        private val EPISODE = Regex("(?i)\\bS0*\\d{1,2}\\s*E(?:P)?0*(\\d{1,4})(?!\\d)")
        private val EPISODE_RANGE = Regex("""(?i)\b(?:ep(?:isodes?)?|e)[ ._-]*\(?([0-9]{1,4})\s*(?:-|–|to)\s*([0-9]{1,4})(?!\d)""")
        private val CAMERA_PRINT = Regex(
            """\b(?:cam(?:rip)?|(?:hd|hq)[ ._-]?cam|hd[ ._-]?(?:ts|tc)|telesync|telecine|pre[ ._-]?(?:dvd|hd)|pdvd|hq[ ._-]+(?:clean|audio|line[ ._-]+audio))\b""",
            RegexOption.IGNORE_CASE,
        )
        private val CLEAN_PRINT = Regex("""\b(?:web[ ._-]?(?:dl|rip)|blu[ ._-]?ray|bd[ ._-]?rip|br[ ._-]?rip|remux|hdtv)\b""", RegexOption.IGNORE_CASE)
        private val RELEASE_VERSION = Regex("(?i)\\bv([0-9]{1,3})\\b")
        private val FIRST_ARTICLE = Regex("""<article\b[^>]*>(.*?)</article>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val IMAGE = Regex("""<img\b[^>]+>""", RegexOption.IGNORE_CASE)
        private val HTML_ATTRIBUTE = Regex("""([\w-]+)\s*=\s*["']([^"']*)["']""")
        private val POSTER_HOSTS = setOf("m.media-amazon.com", "image.tmdb.org", "cdn.district.in", "d1lcdpwgve0lt5.cloudfront.net")
        private val CHECKED_POSTER_HOSTS = setOf(
            "pbs.twimg.com", "www.pixelbb.com", "i.postimg.cc",
            "cdn.moviefone.com", "static.toiimg.com", "images.justwatch.com",
        )
        private val RASTER_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif")
        private const val POSTER_HEADER_LIMIT = 65_536L
        private val TOPIC_HEADER = Regex("""<title\b[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

        fun topicPoster(html: String, topic: String): String? =
            topicPosterCandidates(html, topic).firstOrNull { !it.requiresImageCheck }?.url?.toString()

        private data class ForumPoster(val url: HttpUrl, val requiresImageCheck: Boolean)

        private fun topicPosterCandidates(html: String, topic: String): Sequence<ForumPoster> {
            val page = topic.toHttpUrlOrNull()?.takeIf { isSourceURL(it.toString()) } ?: return emptySequence()
            val article = FIRST_ARTICLE.find(html)?.groupValues?.get(1) ?: return emptySequence()
            return IMAGE.findAll(article).mapNotNull { match ->
                val attributes = HTML_ATTRIBUTE.findAll(match.value).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                val src = attributes["data-src"] ?: attributes["src"] ?: return@mapNotNull null
                val url = page.resolve(text(src)) ?: return@mapNotNull null
                if (!isArtworkURL(url.toString()) || url.port != 443) return@mapNotNull null
                if (url.host in CHECKED_POSTER_HOSTS) {
                    if (url.host == "pbs.twimg.com" && !url.encodedPath.startsWith("/media/")) return@mapNotNull null
                    return@mapNotNull ForumPoster(url, requiresImageCheck = true)
                }
                if (url.host != page.host && url.host !in POSTER_HOSTS) return@mapNotNull null
                val width = attributes["width"]?.toIntOrNull()
                val height = attributes["height"]?.toIntOrNull()
                val declaredPoster = width != null && height != null && portraitSize(width, height) &&
                    "ipsImage" in attributes["class"].orEmpty().split(' ')
                if (declaredPoster) ForumPoster(url, requiresImageCheck = false)
                else if (url.host in POSTER_HOSTS) ForumPoster(url, requiresImageCheck = true)
                else null
            }.distinctBy { it.url }
        }

        private fun portraitSize(width: Int, height: Int): Boolean =
            width in 300..6_000 && height in (width + width / 10)..8_000

        private fun correctTopicSeason(item: CatalogItem, html: String): CatalogItem {
            if (item.mediaType != "series") return item
            val heading = TOPIC_HEADER.find(html)?.groupValues?.get(1)?.let(::text) ?: return item
            val year = PAREN_YEAR.find(heading)?.groups?.get(1) ?: YEAR.find(heading)?.groups?.get(0) ?: return item
            val title = heading.substring(0, year.range.first).replace(SEASON, "")
            if (year.value.toIntOrNull() != item.year || normal(title) != normal(item.title)) return item
            val season = SEASON.findAll(heading.substringAfter(year.value)).mapNotNull {
                it.groupValues[1].toIntOrNull()?.takeIf { value -> value > 0 }
            }.distinct().singleOrNull() ?: return item
            if (season == item.season) return item
            val episode = EPISODE.find(heading)?.groupValues?.get(1)?.toIntOrNull()
            return item.copy(season = season, episode = episode)
        }

        fun parseListing(html: String, base: String): List<CatalogItem> {
            val root = base.toHttpUrlOrNull() ?: return emptyList()
            return ANCHOR.findAll(html).mapNotNull { match ->
                val href = text(match.groupValues[1])
                val url = root.resolve(href)?.toString() ?: return@mapNotNull null
                val topic = TOPIC.find(url)?.groupValues?.get(1) ?: return@mapNotNull null
                if (!isSourceURL(url) || url.length > 2_048) return@mapNotNull null
                var release = text(match.groupValues[2])
                if (!YEAR.containsMatchIn(release)) {
                    val prefix = html.substring(maxOf(0, match.range.first - 2_000), match.range.first)
                    val row = prefix.substring(BOUNDARY.findAll(prefix).lastOrNull()?.range?.last?.plus(1) ?: 0)
                    val visible = text(row)
                    release = if (YEAR.containsMatchIn(visible) && !Regex("(?i)</?a\\b").containsMatchIn(row)) "$visible $release"
                        else URLDecoder.decode(url.substringAfter("/forums/topic/").substringAfter('-').substringBefore('/'), "UTF-8").replace('-', ' ')
                }
                val year = PAREN_YEAR.find(release)?.groups?.get(1)
                    ?: YEAR.find(release)?.groups?.get(0) ?: return@mapNotNull null
                val season = SEASON.find(release)?.groupValues?.get(1)?.toIntOrNull()
                val episode = EPISODE.find(release)?.groupValues?.get(1)?.toIntOrNull()
                val title = release.substring(0, year.range.first).replace(SEASON, "").trim(' ', '(', '[', '-', '.', ':').take(500)
                if (title.isBlank() || Regex("(?i)\\b(?:soundtrack|audio songs|mp3|flac)\\b").containsMatchIn(release)) return@mapNotNull null
                CatalogItem("saved:tamilmv:topic:$topic", if (season != null || episode != null) "series" else "movie", title,
                    year = year.value.toInt(), season = season, episode = episode, sourcePageURL = url.substringBefore("#"))
            }.distinctBy { it.canonicalId }.take(MAX_ITEMS).toList()
        }

        fun parseMagnets(html: String, item: CatalogItem): List<CatalogPlaybackSource> =
            Regex("""magnet:\?[^"'<>\s]+""", RegexOption.IGNORE_CASE).findAll(html).mapNotNull { match ->
                val magnet = text(match.value)
                val fields = magnet.substringAfter('?').split('&').mapNotNull { field ->
                    val pair = field.split('=', limit = 2)
                    if (pair.size != 2) null else pair[0] to runCatching { URLDecoder.decode(pair[1], "UTF-8") }.getOrDefault("")
                }
                // Keep both identities of a hybrid torrent regardless of parameter order.
                val hash = fields.firstNotNullOfOrNull { (key, value) ->
                    if (!key.equals("xt", ignoreCase = true) || !value.startsWith("urn:btih:", ignoreCase = true)) null
                    else value.substring(9).lowercase().takeIf(INFO_HASH::matches)
                } ?: return@mapNotNull null
                val v2 = fields.firstNotNullOfOrNull { (key, value) ->
                    if (!key.equals("xt", ignoreCase = true)) null
                    else BTMH.matchEntire(value)?.groupValues?.get(1)?.lowercase()
                }
                val name = fields.firstOrNull { it.first == "dn" }?.second?.takeIf { it.isNotBlank() }?.take(500) ?: item.title
                val range = EPISODE_RANGE.find(name)?.let {
                    val first = it.groupValues[1].toInt()
                    val last = it.groupValues[2].toInt()
                    (first..last).takeIf { first > 0 && last - first in 0..199 }
                }
                CatalogPlaybackSource(hash, name,
                    v2Infohash = v2,
                    season = SEASON.find(name)?.groupValues?.get(1)?.toIntOrNull() ?: item.season,
                    episodes = range?.toList() ?: listOfNotNull(EPISODE.find(name)?.groupValues?.get(1)?.toIntOrNull() ?: item.episode))
            }.let { rankedSources(it.asIterable()).take(32) }

        private val BTMH = Regex("urn:btmh:1220([a-f0-9]{64})", RegexOption.IGNORE_CASE)
        private val IMDB_ID = Regex("tt[0-9]{5,16}")
        private val SUBTITLE = Regex(""":| [–—-] """)
        private fun tmdbImage(path: String?, size: String) =
            path?.takeIf { it.startsWith("/") && !it.startsWith("//") }?.let { "${MetaRepository.IMAGE_BASE}$size$it" }

        private val INFO_HASH = Regex("[a-f0-9]{40}|[a-z2-7]{32}")
        private val LATIN_ACCENTS = Regex("""(?<=\p{IsLatin})\p{M}+""")
        private val TITLE_MARKS = Regex("[^\\p{L}\\p{M}\\p{N}]+")

        private fun rankedSources(sources: Iterable<CatalogPlaybackSource>): List<CatalogPlaybackSource> =
            sources.distinctBy { Triple(it.infohash, it.fileID, it.season to it.episodes) }.map { source ->
                source to StreamFacts.parse(listOf(source.filename))
            }.sortedWith(compareByDescending<Pair<CatalogPlaybackSource, StreamFacts>> {
                printTier(it.first.filename)
            }.thenByDescending { it.second.qualityRank }
                .thenByDescending { RELEASE_VERSION.find(it.first.filename)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }
                .thenByDescending { it.second.sizeBytes ?: 0L })
                .map { it.first }

        /** An explicit web/disc release outranks an ambiguous rip, regardless of cache state. */
        fun printTier(filename: String): Int = when {
            CAMERA_PRINT.containsMatchIn(filename) -> 0
            CLEAN_PRINT.containsMatchIn(filename) -> 2
            else -> 1
        }

        private val MOVIE_BUNDLE_SUFFIX = Regex("""(?i)\b(?:duology|trilogy)\b[)\]\s]*$""")
        private val LEADING_ARTICLE = Regex("""(?i)^the\s+""")

        /** The label stays on the card. Only the artwork lookup uses the first film's title. */
        fun movieBundleTitle(title: String): String? = MOVIE_BUNDLE_SUFFIX.find(title)?.let { suffix ->
            title.substring(0, suffix.range.first).trimEnd(' ', ':', '-', '–', '—', '(', '[').takeIf(String::isNotBlank)
        }

        fun titleIdentity(item: CatalogItem) = "${item.mediaType}|${normal(item.title)}|${item.year}"
        private fun normal(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKD)
            .lowercase().replace(LATIN_ACCENTS, "").replace(TITLE_MARKS, "")
        private fun JsonObject.array(key: String) = (get(key) as? JsonArray).orEmpty()
        private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
        private fun isArtworkURL(value: String) = value.length <= 2_048 && value.toHttpUrlOrNull()?.let { it.isHttps && it.username.isEmpty() && it.password.isEmpty() } == true
        private fun isSourceURL(value: String) = value.toHttpUrlOrNull()?.let {
            it.isHttps && it.host.matches(Regex("(?:www\\.)?1tamilmv\\.[a-z]{2,20}")) && it.username.isEmpty() && it.password.isEmpty()
        } == true
        private fun text(value: String): String = TAG.replace(value, " ")
            .replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").replace("&#39;", "'")
            .replace("&nbsp;", " ").replace('\u00a0', ' ').replace("&lt;", "<").replace("&gt;", ">")
            .replace(Regex("\\s+"), " ").trim()
    }
}
