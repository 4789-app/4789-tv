package com.fourseveneightnine.tv.client.data.addons

import com.fourseveneightnine.contract.DiscoverItem
import com.fourseveneightnine.tv.client.data.meta.CastMember
import com.fourseveneightnine.tv.client.data.meta.Episode
import com.fourseveneightnine.tv.client.data.meta.Meta
import com.fourseveneightnine.tv.client.data.meta.MetaRef
import com.fourseveneightnine.tv.client.data.streams.CachedHint
import com.fourseveneightnine.tv.client.data.streams.StreamFacts
import com.fourseveneightnine.tv.client.data.streams.OriginalAudioLanguage
import com.fourseveneightnine.tv.client.data.streams.StreamRow
import com.fourseveneightnine.tv.client.data.streams.SubtitleTrack
import com.fourseveneightnine.tv.client.data.streams.debrid.hasDebridVideoExtension
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A catalog endpoint responded, but did not return a usable Stremio catalog. */
class CatalogResponseException : Exception("invalid_catalog_response")

/** One page of a Stremio catalog. */
@Serializable
data class CatalogPage(
    val items: List<DiscoverItem>,
    /** The add-on returned a full page, so there is probably another one. */
    val hasMore: Boolean,
)

/**
 * Every call the TV makes to a Stremio add-on.
 *
 * Ported from `TVAddonSourceRepository` and `phone/RemoteSourceRepository`. Three properties are
 * carried over deliberately:
 *
 *  - **Nothing is logged.** A manifest URL is a credential for AIOStreams and MediaFusion, and a
 *    resolved stream URL is a capability. Neither appears in a breadcrumb, and failures are
 *    recorded as a class name or a status code.
 *  - **Every response has a ceiling.** A 2 MB cap on an add-on answer and a 500-stream cap on what
 *    it may contain, because a hostile or broken add-on should cost one request, not the heap.
 *  - **Bad rows are isolated.** An invalid stream envelope is a failed add-on response.
 *
 * Timeouts for a whole fan-out live in `StreamSearch`, not here: one call knows nothing about the
 * deadline the set is racing.
 */
class StremioClient(
    private val okHttp: OkHttpClient = defaultAddonHttpClient(),
    cacheDir: File? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val benchmark: ((String, String) -> Unit)? = null,
) {
    private val manifestCache = cacheDir?.let { HttpDiskCache(File(it, "manifests")) }
    private val catalogCache = cacheDir?.let { HttpDiskCache(File(it, "catalogs-v1"), 128, 32L * 1_024 * 1_024) }
    private val catalogMemory = LinkedHashMap<String, Pair<CatalogPage, Long>>(32, 0.75f, true)
    private val catalogRequests = ConcurrentHashMap<String, PendingCatalog>()
    private var catalogGeneration = 0L

    private class PendingCatalog {
        val result = CompletableDeferred<CatalogPage?>()
        @Volatile var cancelled = false
        var failure: Exception? = null
    }

    private inline fun <T> observedAddon(
        operation: String, label: String, resultCount: (T) -> Int, action: (Long) -> T,
    ): T {
        val log = benchmark ?: return action(0)
        val request = benchmarkRequests.incrementAndGet()
        val started = System.nanoTime()
        log("addon.start", "request=$request op=$operation name=$label")
        var outcome = "failed"
        var results = 0
        try {
            return action(request).also {
                results = resultCount(it)
                outcome = if (results == 0) "empty" else "ok"
            }
        } catch (cancelled: CancellationException) {
            outcome = "cancelled"
            throw cancelled
        } catch (http: HttpStatusException) {
            outcome = "http_${http.code}"
            throw http
        } finally {
            log("addon.end", "request=$request op=$operation result=$outcome rows=$results elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
        }
    }

    /** A manual refresh invalidates saved pages, including writes from older requests. */
    suspend fun clearCatalogCache() = withContext(Dispatchers.IO) {
        synchronized(catalogMemory) {
            catalogGeneration++
            catalogMemory.clear()
            catalogCache?.clear()
        }
    }

    /**
     * The add-on's manifest, cached for 24 h on disk and revalidated with `If-None-Match`.
     *
     * A 304 keeps the cached body and only resets its age, which is what makes a cold start cheap
     * on a box with a dozen add-ons.
     */
    suspend fun manifest(url: String, force: Boolean = false): AddonManifest =
        observedAddon("manifest", "configured", { 1 }) { request ->
            val route = AddonEndpoint.manifestURL(url) ?: throw IllegalArgumentException("bad_manifest_url")
            val cached = manifestCache?.read(route)
            val fresh = cached != null && clock() - cached.storedAtMillis < MANIFEST_TTL_MILLIS
            if (!force && fresh) {
                parseManifest(cached.body)?.let {
                    benchmark?.invoke("addon.cache", "request=$request layer=manifest_disk")
                    return@observedAddon it
                }
            }
            val response = okHttp.getText(route, MAX_RESPONSE_BYTES, etag = cached?.etag)
            if (response.notModified && cached != null) {
                manifestCache.touch(route, clock())
                parseManifest(cached.body)?.let {
                    benchmark?.invoke("addon.cache", "request=$request layer=manifest_disk")
                    return@observedAddon it
                }
            }
            val manifest = parseManifest(response.body) ?: throw IllegalArgumentException("bad_manifest")
            manifestCache?.write(route, response.body, response.etag, clock())
            manifest
        }

    /** One catalog page. `extra` becomes Stremio's `key=value&key=value` path segment. */
    suspend fun catalog(
        addon: Addon,
        type: String,
        id: String,
        extra: CatalogExtra = CatalogExtra(),
        onCached: (suspend (CatalogPage) -> Unit)? = null,
    ): CatalogPage = observedAddon("catalog", addon.displayName, { it.items.size }) { request ->
        catalogPage(addon, type, id, extra, onCached, request)
    }

    private suspend fun catalogPage(
        addon: Addon, type: String, id: String, extra: CatalogExtra,
        onCached: (suspend (CatalogPage) -> Unit)?, request: Long,
    ): CatalogPage = withContext(Dispatchers.IO) {
        // These built-in public feeds moved to Cinemeta's catalog host. Searches still use v3.
        // Never rewrite an imported URL, which can carry a credential.
        val base = if (addon.manifestURL == CINEMETA_MANIFEST && extra.search.isNullOrBlank() &&
            id in CINEMETA_MOVED_CATALOGS
        ) "https://cinemeta-catalogs.strem.io/$id" else addon.manifestURL
        val route = AddonEndpoint.catalogURL(
            base = base,
            type = type,
            id = id,
            extra = AddonEndpoint.extraSegment(extra),
        ) ?: throw IllegalArgumentException("bad_catalog_url")
        // Search is transient. Persist only parsed display metadata, never a raw provider body
        // that could also contain stream URLs. The full route separates accounts and filters.
        if (!extra.search.isNullOrBlank()) return@withContext parseCatalog(
            okHttp.getText(route, MAX_RESPONSE_BYTES).body, id, type)
        val generation = synchronized(catalogMemory) { catalogGeneration }
        val memory = synchronized(catalogMemory) { catalogMemory[route] }
        val disk = if (memory == null) catalogCache?.read(route) else null
        val cached = memory ?: disk?.let { entry ->
            runCatching { json.decodeFromString(CatalogPage.serializer(), entry.body) to entry.storedAtMillis }.getOrNull()
        }
        val usable = cached?.takeIf { clock() - it.second in 0 until CATALOG_STALE_MILLIS }
        if (usable != null) {
            rememberCatalog(route, usable.first, usable.second, generation)
            val cacheLayer = if (memory != null) "memory" else "disk"
            if (clock() - usable.second < CATALOG_TTL_MILLIS) {
                benchmark?.invoke("addon.cache", "request=$request layer=$cacheLayer state=fresh")
                return@withContext usable.first
            }
            benchmark?.invoke("addon.cache", "request=$request layer=$cacheLayer state=stale")
            onCached?.invoke(usable.first)
        }
        val requestKey = "$generation:$route"
        val pending = PendingCatalog()
        catalogRequests.putIfAbsent(requestKey, pending)?.let { active ->
            benchmark?.invoke("addon.cache", "request=$request layer=inflight")
            val page = active.result.await()
            if (active.cancelled) return@withContext catalog(addon, type, id, extra, onCached)
            return@withContext page ?: throw (active.failure ?: CatalogResponseException())
        }
        var answer: CatalogPage? = null
        try {
            // A caller can finish between our first cache read and acquiring request ownership.
            synchronized(catalogMemory) { catalogMemory[route] }
                ?.takeIf { clock() - it.second in 0 until CATALOG_TTL_MILLIS }
                ?.let { answer = it.first; return@withContext it.first }
            answer = parseCatalog(okHttp.getText(route, MAX_RESPONSE_BYTES).body, id, type)
            synchronized(catalogMemory) {
                if (generation == catalogGeneration) {
                    catalogCache?.write(route, json.encodeToString(CatalogPage.serializer(), answer), null, clock())
                    rememberCatalog(route, answer, clock(), generation)
                }
            }
            answer
        } catch (cancelled: CancellationException) {
            pending.cancelled = true
            throw cancelled
        } catch (failure: Exception) {
            pending.failure = failure
            answer = usable?.first
            answer ?: throw failure
        } finally {
            catalogRequests.remove(requestKey, pending)
            pending.result.complete(answer.takeUnless { pending.cancelled })
        }
    }

    private fun rememberCatalog(route: String, page: CatalogPage, storedAt: Long, generation: Long) = synchronized(catalogMemory) {
        if (generation != catalogGeneration) return@synchronized
        catalogMemory[route] = page to storedAt
        while (catalogMemory.size > 32) catalogMemory.remove(catalogMemory.keys.first())
    }

    private fun parseCatalog(body: String, id: String, type: String): CatalogPage {
        val metas = root(body)?.get("metas") as? JsonArray ?: throw CatalogResponseException()
        val items = metas.mapNotNull { discoverItem(it, catalogID = id, catalogType = type) }.take(MAX_CATALOG_ITEMS)
        if (items.isEmpty() && metas.isNotEmpty()) throw CatalogResponseException()
        return CatalogPage(items = items, hasMore = metas.size >= CATALOG_FULL_PAGE)
    }

    /** Full metadata for one title, or null when this add-on has nothing for it. */
    suspend fun meta(addon: Addon, type: String, id: String): Meta? =
        observedAddon("metadata", addon.displayName, { if (it == null) 0 else 1 }) {
            val route = AddonEndpoint.metaURL(addon.manifestURL, type, id) ?: return@observedAddon null
            val body = okHttp.getText(route, MAX_RESPONSE_BYTES).body
            parseMeta(body, fallbackType = type, addon = addon)
        }

    /**
     * Playable rows from one add-on. `id` already carries `:season:episode` for a series episode.
     *
     * Rows with neither a URL nor an info hash are dropped, as is the placeholder MP4 several
     * add-ons return instead of an empty list. A placeholder that reaches the list is worse than
     * no row: it is a Play button that opens a ten-second apology video.
     */
    suspend fun streams(addon: Addon, type: String, id: String): List<StreamRow> =
        observedAddon("stream", addon.displayName, { it.size }) {
            val route = AddonEndpoint.streamURL(addon.manifestURL, type, id) ?: return@observedAddon emptyList()
            val body = okHttp.getText(route, MAX_RESPONSE_BYTES).body
            val streams = (root(body)?.get("streams") as? JsonArray)
                ?: throw IllegalArgumentException("bad_stream_response")
            streams.take(MAX_RESPONSE_STREAMS).mapIndexedNotNull { index, element ->
                streamRow(element, addon, index, id)
            }
        }

    /** English subtitle tracks from one add-on; other languages do not consume the result limit. */
    suspend fun subtitles(addon: Addon, type: String, id: String, extra: SubtitleExtra = SubtitleExtra()): List<SubtitleTrack> =
        observedAddon("subtitles", addon.displayName, { it.size }) {
            val route = AddonEndpoint.subtitlesURL(addon.manifestURL, type, id, extra) ?: return@observedAddon emptyList()
            val body = okHttp.getText(route, MAX_RESPONSE_BYTES).body
            val tracks = ((root(body)?.get("subtitles") as? JsonArray)
                ?: throw IllegalArgumentException("bad_subtitle_response"))
            tracks.asSequence().mapIndexedNotNull { index, element ->
                val entry = element as? JsonObject ?: return@mapIndexedNotNull null
                val url = AddonEndpoint.playableURL(entry.string("url")) ?: return@mapIndexedNotNull null
                SubtitleTrack(
                    id = entry.string("id") ?: "${addon.key}:$index",
                    url = url,
                    language = entry.string("lang") ?: entry.string("language") ?: "und",
                    addonName = addon.displayName,
                    release = entry.string("title") ?: entry.string("filename"),
                    isForced = (entry["forced"] as? JsonPrimitive)?.booleanOrNull == true,
                    trusted = (entry["from_trusted"] as? JsonPrimitive)?.booleanOrNull == true,
                    aiTranslated = (entry["ai_translated"] as? JsonPrimitive)?.booleanOrNull == true,
                )
            }.filter { SubtitleTrack.isEnglishLanguage(it.language) }.take(MAX_SUBTITLES).toList()
        }

    // MARK: - Parsing

    internal fun parseManifest(body: String): AddonManifest? {
        val root = root(body) ?: return null
        val id = root.string("id") ?: return null
        return AddonManifest(
            id = id,
            name = root.string("name") ?: id,
            version = root.string("version"),
            description = root.string("description")?.take(600),
            logo = root.string("logo"),
            types = root.stringList("types"),
            resources = (root["resources"] as? JsonArray).orEmpty().mapNotNull { element ->
                (element as? JsonPrimitive)?.contentOrNull
                    ?: (element as? JsonObject)?.string("name")
            },
            catalogs = (root["catalogs"] as? JsonArray).orEmpty().mapNotNull { element ->
                val entry = element as? JsonObject ?: return@mapNotNull null
                val type = entry.string("type") ?: return@mapNotNull null
                val catalogID = entry.string("id") ?: return@mapNotNull null
                val extra = (entry["extra"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                AddonCatalog(
                    type = type,
                    id = catalogID,
                    name = entry.string("name") ?: catalogID,
                    extraSupported = extra.mapNotNull { it.string("name") } +
                        entry.stringList("extraSupported"),
                    extraRequired = extra.filter { it.boolean("isRequired") == true }
                        .mapNotNull { it.string("name") } + entry.stringList("extraRequired"),
                    genres = extra.firstOrNull { it.string("name") == "genre" }?.stringList("options")
                        ?: entry.stringList("genres"),
                )
            }.take(MAX_CATALOGS),
            idPrefixes = root.stringList("idPrefixes"),
            streamResources = (root["resources"] as? JsonArray).orEmpty().mapNotNull { resource ->
                val objectResource = resource as? JsonObject
                val name = objectResource?.string("name") ?: (resource as? JsonPrimitive)?.contentOrNull
                if (!name.equals("stream", ignoreCase = true)) return@mapNotNull null
                StreamResource(
                    types = if (objectResource?.containsKey("types") == true) objectResource.stringList("types")
                        else root.stringList("types"),
                    idPrefixes = if (objectResource?.containsKey("idPrefixes") == true) objectResource.stringList("idPrefixes")
                        else root.stringList("idPrefixes"),
                )
            },
        )
    }

    internal fun parseMeta(body: String, fallbackType: String, addon: Addon? = null): Meta? {
        val root = root(body) ?: return null
        val meta = root["meta"] as? JsonObject ?: root.takeIf { it["id"] != null } ?: return null
        val id = meta.string("id") ?: return null
        val rawVideos = (meta["videos"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val type = meta.string("type") ?: fallbackType
        val defaultID = (meta["behaviorHints"] as? JsonObject)?.string("defaultVideoId")
            ?: rawVideos.singleOrNull()?.string("id").takeIf { type != "series" }
        val movieVideo = rawVideos.firstOrNull { it.string("id") == defaultID }
        fun inline(entry: JsonObject?, videoID: String): List<StreamRow>? {
            val array = entry?.get("streams") as? JsonArray ?: return null
            val owner = addon ?: return null
            return array.take(MAX_RESPONSE_STREAMS).mapIndexedNotNull { index, element ->
                streamRow(element, owner, index, videoID)
            }
        }
        val movieStreams = inline(movieVideo, defaultID ?: id) ?: inline(meta, id)
        val videos = rawVideos
            .mapNotNull { entry -> episode(entry)?.copy(inlineStreams = inline(entry, entry.string("id") ?: id)) }
            .distinctBy { "${it.season}:${it.episode}" }
            .sortedWith(compareBy(Episode::season, Episode::episode))
            .take(MAX_EPISODES)
        return Meta(
            id = id,
            type = type,
            title = meta.string("name") ?: id,
            originalLanguage = OriginalAudioLanguage.normalize(meta.string("original_language")),
            year = meta.string("year")?.take(4)?.toIntOrNull()
                ?: meta.string("releaseInfo")?.take(4)?.toIntOrNull(),
            runtimeMinutes = runtimeMinutes(meta.string("runtime")),
            certification = meta.string("certification"),
            castNames = meta.stringList("cast").take(200),
            directors = (meta.stringList("director") + listOfNotNull(meta.string("director")))
                .flatMap { it.split(',') }.map(String::trim).filter(String::isNotBlank).distinct().take(50),
            spokenLanguages = (meta.stringList("language") + listOfNotNull(meta.string("language")))
                .flatMap { it.split(',') }.mapNotNull(OriginalAudioLanguage::normalize).distinct(),
            releaseDate = meta.string("released")?.take(10),
            countries = (meta.stringList("country") + listOfNotNull(meta.string("country"))).flatMap { it.split(',') }.map(String::trim).filter(String::isNotBlank),
            description = meta.string("description"),
            poster = meta.string("poster"),
            backdrop = meta.string("background"),
            logo = meta.string("logo"),
            genres = (meta.stringList("genres") + meta.stringList("genre")).distinct().take(8),
            imdbID = meta.string("imdb_id") ?: id.takeIf { it.startsWith("tt") },
            tmdbID = (meta.string("moviedb_id") ?: meta.string("tmdb_id"))?.toIntOrNull()
                ?: TMDB_ID.matchEntire(id)?.groupValues?.get(1)?.toIntOrNull(),
            imdbRating = meta.string("imdbRating")?.toDoubleOrNull(),
            trailerYouTubeID = trailerID(meta),
            cast = castMembers(meta),
            videos = videos,
            defaultVideoID = defaultID,
            hasInlineStreams = movieStreams != null || videos.any { it.inlineStreams != null },
            inlineStreams = movieStreams,
            similar = (meta["links"] as? JsonArray).orEmpty().mapNotNull(::similarRef).take(20),
        )
    }

    private fun episode(element: JsonElement): Episode? {
        val entry = element as? JsonObject ?: return null
        val id = entry.string("id").orEmpty()
        val fromID = SEASON_EPISODE.find(id) ?: COLON_EPISODE.find(id)
        val season = entry.int("season") ?: fromID?.groupValues?.getOrNull(1)?.toIntOrNull()
        val episode = entry.int("episode") ?: entry.int("number")
            ?: fromID?.groupValues?.getOrNull(2)?.toIntOrNull()
        if (season == null || episode == null || season !in 0..100 || episode !in 0..2_000) return null
        return Episode(
            season = season,
            episode = episode,
            title = entry.string("title") ?: entry.string("name") ?: "Episode $episode",
            overview = entry.string("overview")?.take(1_000),
            thumbnail = entry.string("thumbnail")?.let(AddonEndpoint::playableURL),
            released = entry.string("released") ?: entry.string("firstAired"),
            id = id.takeIf(String::isNotBlank) ?: "$season:$episode",
        )
    }

    private fun castMembers(meta: JsonObject): List<CastMember> {
        val fromLinks = (meta["links"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { it.string("category").equals("Cast", ignoreCase = true) }
            .mapNotNull { it.string("name")?.let { name -> CastMember(name = name) } }
        if (fromLinks.isNotEmpty()) return fromLinks.take(MAX_CAST)
        return meta.stringList("cast").map { CastMember(name = it) }.take(MAX_CAST)
    }

    private fun similarRef(element: JsonElement): MetaRef? {
        val entry = element as? JsonObject ?: return null
        if (!entry.string("category").equals("similar", ignoreCase = true)) return null
        val url = entry.string("url").orEmpty()
        // Stremio writes a deep link: stremio:///detail/<type>/<id>
        val parts = url.substringAfter("/detail/", "").split('/').filter(String::isNotBlank)
        val type = parts.getOrNull(0) ?: return null
        val id = parts.getOrNull(1) ?: return null
        return MetaRef(id = id, type = type, title = entry.string("name") ?: id)
    }

    private fun trailerID(meta: JsonObject): String? {
        (meta["trailers"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { trailer ->
            trailer.string("source")?.takeIf(String::isNotBlank)?.let { return it }
            trailer.string("ytId")?.takeIf(String::isNotBlank)?.let { return it }
        }
        (meta["trailerStreams"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { trailer ->
            trailer.string("ytId")?.takeIf(String::isNotBlank)?.let { return it }
        }
        return null
    }

    private fun discoverItem(element: JsonElement, catalogID: String, catalogType: String): DiscoverItem? {
        val entry = element as? JsonObject ?: return null
        val id = entry.string("id") ?: return null
        val title = entry.string("name") ?: return null
        return DiscoverItem(
            id = id,
            type = entry.string("type") ?: catalogType,
            title = title,
            posterURL = entry.string("poster"),
            backdropURL = entry.string("background"),
            description = entry.string("description")?.take(1_200),
            runtime = entry.string("runtime"),
            year = entry.string("year")?.take(4)?.toIntOrNull()
                ?: entry.string("releaseInfo")?.take(4)?.toIntOrNull(),
            rating = entry.string("imdbRating")?.toDoubleOrNull(),
            genres = (entry.stringList("genres") + entry.stringList("genre")).distinct().take(8),
            catalogID = catalogID,
        )
    }

    private fun streamRow(element: JsonElement, addon: Addon, index: Int, requestID: String): StreamRow? {
        val entry = element as? JsonObject ?: return null
        val hints = entry["behaviorHints"] as? JsonObject
        // notWebReady describes browser playback, not native-player availability. Native
        // streams may require proxyHeaders; preserve those below and reject known error clips.
        val url = AddonEndpoint.playableURL(entry.string("url"))
            ?.takeUnless { it.substringBefore('?').contains("/assets/stream-errors/", ignoreCase = true) }
        val magnet = entry.string("url")?.takeIf { it.startsWith("magnet:?", ignoreCase = true) }
        val magnetHash = magnet?.let { MAGNET_HASH.find(it)?.groupValues?.get(1) }
        val infoHash = (entry.string("infoHash") ?: magnetHash)
            ?.lowercase(Locale.US)
            ?.takeIf { INFO_HASH.matches(it) }
        if (url == null && infoHash == null) return null
        val parsedURL = url?.toHttpUrlOrNull()
        val dmmTorboxPlay = parsedURL?.takeIf { it.isHttps && it.host == "debridmediamanager.com" &&
            it.pathSegments.take(2) == listOf("api", "stremio-tb") &&
            it.pathSegments.getOrNull(3) == "play" }
        // DMM's displayed release can be abbreviated; `file` names the file its play route resolves.
        val dmmDisplayFilename = parsedURL?.takeIf { it.isHttps && it.host == "debridmediamanager.com" &&
            it.pathSegments.firstOrNull() == "api" && it.pathSegments.getOrNull(1) in setOf("stremio", "stremio-tb") }
            ?.let { entry.string("title") }?.takeIf { it.contains("🎬 DMM Cast ") }
            // DMM inserts "-\n" halfway through long filenames for display only.
            ?.replace("-\r\n", "")?.replace("-\n", "")?.lineSequence()?.firstOrNull()?.trim()
            ?.takeIf { it.lowercase(Locale.ROOT).hasDebridVideoExtension() }
        val filename = dmmTorboxPlay?.queryParameter("file")?.takeIf(String::isNotBlank)
            ?.take(RELEASE_NAME_LIMIT) ?: hints?.string("filename")?.take(RELEASE_NAME_LIMIT)
            ?: dmmDisplayFilename?.take(RELEASE_NAME_LIMIT)

        val texts = listOf(
            entry.string("name"),
            entry.string("title"),
            entry.string("description"),
            filename,
        )
        val metadata = listOfNotNull(hints, entry)
        fun strings(key: String): List<String> = metadata.flatMap { obj ->
            when (val value = obj[key]) {
                is JsonArray -> value.take(32).mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.take(320) }
                is JsonPrimitive -> listOfNotNull(value.contentOrNull?.take(320))
                else -> emptyList()
            }
        }.filter(String::isNotBlank)
        fun number(key: String): Long? = metadata.firstNotNullOfOrNull { it.long(key) }?.takeIf { it > 0 }
        fun decimal(key: String): Double? = metadata.firstNotNullOfOrNull { (it[key] as? JsonPrimitive)?.doubleOrNull }
            ?.takeIf { it.isFinite() && it > 0 }
        fun languages(values: List<String>): List<String> = values.flatMap { it.split(Regex("[,;|/]+")) }
            .mapNotNull { com.fourseveneightnine.tv.client.data.streams.OriginalAudioLanguage.normalize(it) }
            .map { com.fourseveneightnine.tv.client.data.streams.OriginalAudioLanguage.displayName(it) }.distinct().take(32)
        val technical = listOf("videoCodec", "videoCodecs", "codec", "audioCodec", "audioCodecs", "audioChannels",
            "resolution", "hdr", "videoRange", "releaseType", "releaseGroup").flatMap(::strings)
        val parsedFacts = StreamFacts.parse(
            texts = texts + technical,
            sizeBytes = number("videoSize") ?: entry.long("sizeBytes"),
            statedQuality = entry.string("quality"), seeders = metadata.firstNotNullOfOrNull { it.int("seeders") }?.takeIf { it >= 0 },
        )
        val declaredLanguages = languages(strings("audioLanguages"))
        val subtitles = languages(strings("subtitleLanguages") + (entry["subtitles"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.let { sub -> sub.string("lang") ?: sub.string("language") } })
        val dates = linkedMapOf<String, Long>()
        for ((label, keys) in listOf("Published" to listOf("publishDate", "publishedAt"),
            "Uploaded" to listOf("uploadedAt", "uploadDate"), "Added" to listOf("addedAt"),
            "File created" to listOf("fileCreatedAt"))) {
            keys.flatMap(::strings).firstNotNullOfOrNull { StreamFacts.sourceDate(it) }?.let { dates[label] = it }
        }
        val service = (strings("debridService") + strings("debridProvider")).firstOrNull {
            it.trim().lowercase(Locale.ROOT) !in setOf("none", "direct", "p2p", "false")
        }
            ?: strings("provider").firstOrNull { it.lowercase(Locale.ROOT) in DEBRID_SERVICES }
        val cacheFlags = metadata.mapNotNull { (it["cached"] as? JsonPrimitive)?.booleanOrNull }
        val hint = parsedFacts.cachedHint
        val cold = hint.uncached || false in cacheFlags
        val facts = parsedFacts.copy(
            cachedHint = hint.copy(cached = !cold && (hint.cached || true in cacheFlags || StreamRow.isCachedFeed(addon.name)),
                uncached = cold, service = StreamRow.providerService(service) ?: service?.trim() ?: hint.service,
                debrid = service != null || StreamRow.isCachedFeed(addon.name) || strings("deliveryKind").any { it.equals("debrid", true) }),
            quality = (number("videoHeight") ?: number("height"))?.let { StreamFacts.normalizedQuality(null, "${it}p") } ?: parsedFacts.quality,
            audioLanguages = (declaredLanguages + parsedFacts.audioLanguages).distinct(),
            reportedAudioLanguages = (declaredLanguages + parsedFacts.reportedAudioLanguages).distinct(),
            subtitleLanguages = (subtitles + parsedFacts.subtitleLanguages).distinct(),
            durationSeconds = (number("duration") ?: number("durationSeconds"))?.takeIf { it <= 604_800L }, sourceDates = dates,
            bitrateMbps = decimal("bitrateMbps") ?: decimal("videoBitrateMbps") ?: parsedFacts.bitrateMbps,
            releaseGroup = strings("releaseGroup").firstOrNull() ?: parsedFacts.releaseGroup,
            audioChannels = strings("audioChannels").firstOrNull()?.let { value ->
                if (value.matches(Regex("[0-9]{1,2}"))) "$value channels" else value.takeIf { it.matches(Regex("[0-9]{1,2}\\.[0-9]{1,2}")) }
            } ?: parsedFacts.audioChannels,
            resolutionText = ((number("videoWidth") ?: number("width"))?.takeIf { it <= 16384 }
                ?.let { width -> (number("videoHeight") ?: number("height"))?.takeIf { it <= 16384 }?.let { height -> "${width}x$height" } })
                ?: parsedFacts.resolutionText,
            folderName = strings("folderName").firstOrNull()?.takeUnless { it.contains("://") },
            folderSizeBytes = number("folderSize"),
            bitDepth = number("bitDepth")?.toInt()?.takeIf { it in 8..16 } ?: parsedFacts.bitDepth,
            provider = strings("provider").firstOrNull() ?: parsedFacts.provider,
            server = strings("server").firstOrNull() ?: parsedFacts.server,
            frameRate = (decimal("frameRate") ?: decimal("fps"))?.takeIf { it <= 240 },
            videoHash = strings("videoHash").firstOrNull()?.lowercase(Locale.US)?.takeIf { it.matches(Regex("[a-f0-9]{16}")) },
        )
        val releaseName = filename?.takeIf(String::isNotBlank) ?: listOfNotNull(
            entry.string("title")?.lineSequence()?.firstOrNull(),
            entry.string("name"),
            descriptionRelease(entry.string("description")),
        ).filter(String::isNotBlank).maxByOrNull { value ->
            (if (Regex("(?i)\\b(?:19|20)\\d{2}\\b|\\bs\\d+e\\d+\\b|\\b\\d+x\\d+\\b").containsMatchIn(value)) 2_000 else 0) +
                (if (Regex("(?i)2160|1080|720|WEB-DL|BluRay").containsMatchIn(value)) 500 else 0) +
                value.length.coerceAtMost(1_000)
        }.orEmpty()

        return StreamRow(
            id = "${addon.key}|$requestID|$index",
            addonName = addon.displayName,
            title = entry.string("name")?.lineSequence()?.firstOrNull()?.trim()?.take(TITLE_LIMIT)
                ?: addon.displayName,
            releaseName = releaseName.ifBlank { "Unnamed release" }.take(RELEASE_NAME_LIMIT),
            url = url,
            infoHash = infoHash,
            fileIdx = entry.int("fileIdx"),
            quality = facts.quality,
            sizeBytes = facts.sizeBytes,
            codecs = facts.codecs,
            hdr = facts.hdr,
            audioLanguages = facts.audioLanguages,
            cachedHint = facts.cachedHint,
            seeders = facts.seeders,
            headers = proxyHeaders(hints),
            sources = entry.stringList("sources").take(MAX_TRACKERS),
            facts = facts,
            cacheHash = infoHash ?: parsedURL?.pathSegments?.firstOrNull { INFO_HASH.matches(it) }?.lowercase(Locale.US)
                ?: dmmTorboxPlay?.queryParameter("h")?.lowercase(Locale.US)?.takeIf { INFO_HASH.matches(it) },
            filename = filename?.takeUnless { it.endsWith(".-") || it.endsWith("...") || it.endsWith("…") },
            identityNames = texts.filterNotNull().filter(String::isNotBlank),
                bingeGroup = hints?.string("bingeGroup")?.takeIf { it.isNotBlank() && it.length <= 256 },
                refreshAddonKey = addon.key,
                refreshID = requestID,
        )
    }

    private fun descriptionRelease(description: String?): String? {
        var text = description?.lineSequence()?.firstOrNull(String::isNotBlank)?.trim() ?: return null
        if (text.contains("://")) return null
        val prefix = Regex(
            "^\\[(?:PixelDrain|FSL(?:v\\d+)?|4KHDHub|HdHub|[^\\]]*\\d+(?:[.,]\\d+)?\\s*(?:GiB|GB|MiB|MB)[^\\]]*)]\\s*",
            RegexOption.IGNORE_CASE,
        )
        while (true) {
            val wrapper = prefix.find(text)?.value ?: break
            text = text.removePrefix(wrapper).trimStart()
        }
        for (symbol in listOf("🎟️", "🍿", "🎬", "📺")) text = text.removePrefix(symbol).trimStart()
        return text.takeIf(String::isNotBlank)
    }

    private fun proxyHeaders(hints: JsonObject?): Map<String, String> {
        val request = (hints?.get("proxyHeaders") as? JsonObject)?.get("request") as? JsonObject
            ?: return emptyMap()
        return request.entries.mapNotNull { (name, value) ->
            val text = (value as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            if (!HTTP_HEADER_NAME.matches(name) || text.isBlank() || text.any { it != '\t' && it !in ' '..'~' }) null
            else name to text.take(1_024)
        }.take(8).toMap()
    }

    private fun runtimeMinutes(raw: String?): Int? {
        val text = raw?.lowercase(Locale.ROOT) ?: return null
        val hours = Regex("""([0-9]+)\s*h""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val minutes = Regex("""([0-9]+)\s*min""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (hours != null || minutes != null) return (hours ?: 0) * 60 + (minutes ?: 0)
        return text.filter(Char::isDigit).toIntOrNull()?.takeIf { it in 1..1_000 }
    }

    private fun root(body: String): JsonObject? =
        runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.boolean(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.int(key: String): Int? =
        (get(key) as? JsonPrimitive)?.let { it.contentOrNull?.toDoubleOrNull()?.toInt() }

    private fun JsonObject.long(key: String): Long? =
        (get(key) as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

    private fun JsonObject.stringList(key: String): List<String> =
        (get(key) as? JsonArray).orEmpty().mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
        }

    companion object {
        private val benchmarkRequests = java.util.concurrent.atomic.AtomicLong()
        /** Always on, always first. The TV has no usable Detail screen without it. */
        const val CINEMETA_MANIFEST: String = "https://v3-cinemeta.strem.io/manifest.json"
        private val CINEMETA_MOVED_CATALOGS = setOf("top", "year", "imdbRating")

        const val MANIFEST_TTL_MILLIS: Long = 24 * 60 * 60 * 1_000L
        const val CATALOG_TTL_MILLIS: Long = 5 * 60 * 1_000L
        const val CATALOG_STALE_MILLIS: Long = 7 * 24 * 60 * 60 * 1_000L
        const val MAX_RESPONSE_BYTES: Int = 2 * 1_024 * 1_024
        const val MAX_RESPONSE_STREAMS: Int = 500
        const val MAX_CATALOG_ITEMS: Int = 200
        const val MAX_EPISODES: Int = 2_000
        const val MAX_CATALOGS: Int = 200
        const val MAX_SUBTITLES: Int = 100
        const val MAX_CAST: Int = 30
        const val MAX_TRACKERS: Int = 24
        const val CATALOG_FULL_PAGE: Int = 20
        const val TITLE_LIMIT: Int = 180
        const val RELEASE_NAME_LIMIT: Int = 320

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private val SEASON_EPISODE = Regex("""(?i)s(\d{1,3})e(\d{1,4})""")
        private val COLON_EPISODE = Regex(""":(\d{1,3}):(\d{1,4})$""")
        private val HTTP_HEADER_NAME = Regex("[!#\$%&'*+.^_`|~0-9A-Za-z-]{1,128}")
        private val TMDB_ID = Regex("tmdb:(?:(?:movie|series):)?([0-9]+)")
        private val DEBRID_SERVICES = setOf("real-debrid", "realdebrid", "real debrid", "torbox", "alldebrid", "premiumize", "debrid-link")
        private val MAGNET_HASH = Regex("""[?&]xt=urn:btih:([a-z0-9]+)(?:&|$)""", RegexOption.IGNORE_CASE)
        private val INFO_HASH = Regex("""[a-f0-9]{40}|[a-z2-7]{32}""")
    }
}
