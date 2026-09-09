package com.fourseveneightnine.tv.catalog

import com.fourseveneightnine.tv.settings.StoredTVSettingsState
import com.fourseveneightnine.tv.settings.TVSettingsPersistence
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal data class TVAddonEndpoint(
    val name: String,
    val manifestURL: String,
)

internal data class TVAddonPlayableSource(
    val id: String,
    val url: String,
    val title: String,
    val detail: String,
    val addonName: String,
    val quality: String?,
    val sizeBytes: Long?,
)

internal data class TVAddonEpisode(
    val season: Int,
    val episode: Int,
    val title: String,
    val overview: String? = null,
    val thumbnailURL: String? = null,
)

internal data class TVAddonSourceSearchResult(
    val sources: List<TVAddonPlayableSource>,
    val attemptedAddons: Int,
    val failedAddons: Int,
    val torrentOnlyCount: Int,
    val episodes: List<TVAddonEpisode> = emptyList(),
)

internal sealed interface TVAddonSourceState {
    data object Idle : TVAddonSourceState
    data class Loading(val itemID: String) : TVAddonSourceState
    data class Ready(val itemID: String, val result: TVAddonSourceSearchResult) : TVAddonSourceState
    data class Error(val itemID: String, val message: String) : TVAddonSourceState
}

/** Pure stale-result boundary used by the Activity and rapid-key sequence tests. */
internal object TVAddonSourceLifecyclePolicy {
    fun accepts(
        resultGeneration: Long,
        currentGeneration: Long,
        resultItemID: String,
        openItemID: String?,
    ): Boolean = resultGeneration == currentGeneration && resultItemID == openItemID
}

/**
 * Reads only addon routes from the Keystore-encrypted TV settings document.
 *
 * Endpoint URLs can contain addon credentials, so callers receive names for display and must never
 * log either endpoint or resolved stream URLs. User-added rows, dedicated AIOStreams, MediaFusion,
 * and the two legacy manifest fields are merged in deterministic order and deduplicated.
 */
internal object TVAddonSettingsPolicy {
    private val json = Json { ignoreUnknownKeys = true }

    fun endpoints(state: StoredTVSettingsState): List<TVAddonEndpoint> {
        val raw = (state as? StoredTVSettingsState.Available)?.document?.rawJson ?: return emptyList()
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return emptyList()
        val candidates = buildList {
            root.string("aioStreamsURLText")?.let { add(TVAddonEndpoint("AIOStreams", it)) }
            (root["sources"] as? JsonArray).orEmpty().forEachIndexed { index, element ->
                val source = element as? JsonObject ?: return@forEachIndexed
                if (source.boolean("enabled") == false) return@forEachIndexed
                val url = source.string("url") ?: return@forEachIndexed
                val name = source.string("name")?.take(DISPLAY_NAME_LIMIT) ?: "Addon ${index + 1}"
                add(TVAddonEndpoint(name, url))
            }
            if (root.boolean("mediaFusionEnabled") != false) {
                root.string("mediaFusionURLText")?.let { add(TVAddonEndpoint("MediaFusion", it)) }
            }
            root.string("primaryManifestURLText")?.let { add(TVAddonEndpoint("Primary addon", it)) }
            root.string("secondaryManifestURLText")?.let { add(TVAddonEndpoint("Secondary addon", it)) }
        }

        val seen = mutableSetOf<String>()
        return candidates.mapNotNull { endpoint ->
            val trimmed = endpoint.manifestURL.trim()
            val normalized = TVAddonRoutePolicy.normalizedEndpoint(trimmed) ?: return@mapNotNull null
            if (!seen.add(normalized)) return@mapNotNull null
            endpoint.copy(manifestURL = trimmed)
        }.take(MAX_ADDONS)
    }

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.boolean(key: String): Boolean? =
        (get(key) as? JsonPrimitive)?.booleanOrNull

    private const val MAX_ADDONS = 32
    private const val DISPLAY_NAME_LIMIT = 80
}

internal object TVAddonRoutePolicy {
    private val safeIdentifier = Regex("[A-Za-z0-9:._-]{1,500}")

    fun identifiers(item: TVTamilMVCatalogItem): List<String> = buildList {
        item.imdbID?.takeIf { safeIdentifier.matches(it) }?.let(::add)
        val canonical = item.id.substringAfter("::")
        canonical.takeIf { safeIdentifier.matches(it) }?.let(::add)
        // Catalog facet IDs use tmdb:movie:<id> / tmdb:series:<id>, while most Stremio
        // stream routes expect the compact tmdb:<id> form. Keep the canonical ID first for
        // addons that understand it, then try the compact fallback.
        Regex("^tmdb:(?:movie|series):([0-9]+)$", RegexOption.IGNORE_CASE)
            .matchEntire(canonical)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { add("tmdb:$it") }
        item.tmdbID?.takeIf { it > 0 }?.let { add("tmdb:$it") }
    }.distinct().take(3)

    fun streamURL(endpoint: TVAddonEndpoint, item: TVTamilMVCatalogItem, identifier: String): String? {
        if (!safeIdentifier.matches(identifier)) return null
        val normalized = normalizedEndpoint(endpoint.manifestURL) ?: return null
        val parsed = runCatching { URI(endpoint.manifestURL.trim()) }.getOrNull() ?: return null
        val baseWithoutQuery = endpoint.manifestURL.trim().substringBefore('?').substringBefore('#')
            .trimEnd('/')
            .removeSuffix("/manifest.json")
        val type = if (item.mediaType == "series") "series" else "movie"
        val episodeSuffix = if (type == "series" && item.season != null && item.episode != null) {
            ":${item.season}:${item.episode}"
        } else {
            ""
        }
        val query = parsed.rawQuery?.let { "?$it" }.orEmpty()
        val result = "$baseWithoutQuery/stream/$type/$identifier$episodeSuffix.json$query"
        val resultURI = runCatching { URI(result) }.getOrNull() ?: return null
        return result.takeIf {
            it.length <= MAX_ENDPOINT_LENGTH &&
                resultURI.scheme?.lowercase(Locale.US) in setOf("http", "https") &&
                !resultURI.host.isNullOrBlank() && resultURI.userInfo == null && resultURI.fragment == null &&
                normalized.isNotEmpty()
        }
    }

    fun metaURL(endpoint: TVAddonEndpoint, item: TVTamilMVCatalogItem, identifier: String): String? {
        if (item.mediaType != "series" || !safeIdentifier.matches(identifier)) return null
        val normalized = normalizedEndpoint(endpoint.manifestURL) ?: return null
        val parsed = runCatching { URI(endpoint.manifestURL.trim()) }.getOrNull() ?: return null
        val baseWithoutQuery = endpoint.manifestURL.trim().substringBefore('?').substringBefore('#')
            .trimEnd('/')
            .removeSuffix("/manifest.json")
        val query = parsed.rawQuery?.let { "?$it" }.orEmpty()
        val result = "$baseWithoutQuery/meta/series/$identifier.json$query"
        val resultURI = runCatching { URI(result) }.getOrNull() ?: return null
        return result.takeIf {
            it.length <= MAX_ENDPOINT_LENGTH &&
                resultURI.scheme?.lowercase(Locale.US) in setOf("http", "https") &&
                !resultURI.host.isNullOrBlank() && resultURI.userInfo == null && resultURI.fragment == null &&
                normalized.isNotEmpty()
        }
    }

    fun playableURL(value: String?): String? {
        val candidate = value?.trim()?.takeIf { it.length in 1..MAX_STREAM_URL_LENGTH } ?: return null
        val parsed = runCatching { URI(candidate) }.getOrNull() ?: return null
        return candidate.takeIf {
            parsed.scheme?.lowercase(Locale.US) in setOf("http", "https") &&
                !parsed.host.isNullOrBlank() && parsed.userInfo == null && parsed.fragment == null
        }
    }

    fun normalizedEndpoint(value: String): String? {
        val candidate = value.trim().takeIf { it.length in 1..MAX_ENDPOINT_LENGTH } ?: return null
        val parsed = runCatching { URI(candidate) }.getOrNull() ?: return null
        val scheme = parsed.scheme?.lowercase(Locale.US)
        if (scheme !in setOf("http", "https") || parsed.host.isNullOrBlank() || parsed.userInfo != null || parsed.fragment != null) {
            return null
        }
        val port = if (parsed.port >= 0) ":${parsed.port}" else ""
        val path = parsed.rawPath.orEmpty().trimEnd('/').removeSuffix("/manifest.json")
        val query = parsed.rawQuery?.let { "?$it" }.orEmpty()
        return "$scheme://${parsed.host.lowercase(Locale.US)}$port$path$query"
    }

    private const val MAX_ENDPOINT_LENGTH = 16 * 1_024
    private const val MAX_STREAM_URL_LENGTH = 32 * 1_024
}

internal class TVAddonSourceRepository(
    private val settings: TVSettingsPersistence,
    private val http: TVAddonHTTPClient = OkHttpTVAddonHTTPClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private data class CachedEpisodes(val episodes: List<TVAddonEpisode>, val storedAtMillis: Long)

    private val episodeCache = java.util.concurrent.ConcurrentHashMap<String, CachedEpisodes>()
    private val episodeInFlight =
        java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<List<TVAddonEpisode>>>()

    suspend fun find(item: TVTamilMVCatalogItem): TVAddonSourceSearchResult = withContext(ioDispatcher) {
        val endpoints = TVAddonSettingsPolicy.endpoints(settings.load())
        val identifiers = TVAddonRoutePolicy.identifiers(item)
        ReceiverDiagnostics.record("addon.sources.request", "item=${item.id.take(120)} ids=${identifiers.joinToString(",").take(300)}")
        if (endpoints.isEmpty() || identifiers.isEmpty()) {
            return@withContext TVAddonSourceSearchResult(emptyList(), endpoints.size, 0, 0)
        }

        // A series without a selected episode is metadata-only. Querying /stream/series/<show>
        // produces ambiguous or empty responses on most addons; episode selection owns the exact
        // route and source lifecycle. Metadata remains available so the TV can render seasons.
        val episodes = if (item.mediaType == "series") cachedEpisodes(endpoints, item, identifiers) else emptyList()
        if (item.mediaType == "series" && (item.season == null || item.episode == null)) {
            return@withContext TVAddonSourceSearchResult(
                sources = emptyList(),
                attemptedAddons = endpoints.size,
                failedAddons = 0,
                torrentOnlyCount = 0,
                episodes = episodes,
            )
        }

        // One dead endpoint used to cost the viewer the whole per-call timeout, because every
        // endpoint was joined with awaitAll: the fastest thirty results sat finished and invisible
        // while the slowest one ran out its clock. The spinner meanwhile promised the opposite
        // ("one slow provider cannot hide the others"), which simply was not true.
        //
        // There is now a deadline for the SET, not just for each call. Whatever has answered by
        // then is what the viewer gets; stragglers are cancelled and counted as failures. A
        // provider that is down now costs the deadline once, not a full timeout.
        val outcomes = supervisorScope {
            val pending = endpoints.mapIndexed { endpointIndex, endpoint ->
                async { loadEndpoint(endpointIndex, endpoint, item, identifiers) }
            }
            withTimeoutOrNull(AGGREGATE_DEADLINE_MILLIS) { pending.awaitAll() }
                ?: pending.map { deferred ->
                    if (deferred.isCompleted && !deferred.isCancelled) {
                        runCatching { deferred.getCompleted() }
                            .getOrDefault(EndpointOutcome(emptyList(), 0, failed = true))
                    } else {
                        deferred.cancel()
                        ReceiverDiagnostics.record("addon.sources.deadline", "cancelled")
                        EndpointOutcome(emptyList(), 0, failed = true)
                    }
                }
        }
        outcomes.forEachIndexed { index, outcome ->
            // Keep the breadcrumb endpoint-name only; manifest URLs can contain credentials.
            ReceiverDiagnostics.record(
                "addon.sources.result",
                "name=${endpoints.getOrNull(index)?.name ?: "unknown"} playable=${outcome.sources.size} torrentOnly=${outcome.torrentOnlyCount} failed=${outcome.failed}",
            )
        }
        val sources = outcomes
            .flatMap(EndpointOutcome::sources)
            .distinctBy(TVAddonPlayableSource::url)
            .sortedWith(compareByDescending<TVAddonPlayableSource> { qualityRank(it.quality, it.title, it.detail) }
                .thenByDescending { it.sizeBytes ?: 0L })
            .take(MAX_PLAYABLE_SOURCES)
        TVAddonSourceSearchResult(
            sources = sources,
            attemptedAddons = endpoints.size,
            failedAddons = outcomes.count(EndpointOutcome::failed),
            torrentOnlyCount = outcomes.sumOf(EndpointOutcome::torrentOnlyCount),
            episodes = episodes,
        )
    }

    /**
     * Episode lists, cached.
     *
     * An episode list is the same for everyone and changes when a season airs, not between two
     * presses of the remote — yet backing out of a series and re-entering it re-asked every
     * configured addon (up to 32) from scratch, because nothing here cached and the HTTP layer was
     * told `no-store`. That re-fan-out was the wait the viewer felt on the second visit.
     *
     * Two guards, both already proven in ArtworkLoader: a TTL entry so a repeat visit is free, and
     * an in-flight map so the detail screen and a background prefetch asking together issue one
     * fan-out rather than two.
     */
    private suspend fun cachedEpisodes(
        endpoints: List<TVAddonEndpoint>,
        item: TVTamilMVCatalogItem,
        identifiers: List<String>,
    ): List<TVAddonEpisode> {
        // Endpoints are part of the key: removing an addon must not keep serving its episodes.
        val key = buildString {
            append(item.id)
            append('|')
            append(identifiers.joinToString(","))
            append('|')
            endpoints.joinTo(this, ",") { it.name }
        }
        val now = System.currentTimeMillis()
        episodeCache[key]?.let { entry ->
            if (now - entry.storedAtMillis < EPISODE_CACHE_TTL_MILLIS) {
                ReceiverDiagnostics.record("addon.episodes.cache", "hit")
                return entry.episodes
            }
            episodeCache.remove(key, entry)
        }

        episodeInFlight[key]?.let { return it.await() }
        val pending = CompletableDeferred<List<TVAddonEpisode>>()
        episodeInFlight.putIfAbsent(key, pending)?.let { return it.await() }

        val episodes = try {
            loadEpisodes(endpoints, item, identifiers)
        } catch (error: Throwable) {
            pending.complete(emptyList())
            episodeInFlight.remove(key, pending)
            throw error
        }
        // Only a real answer is worth remembering. Caching an empty list would pin a transient
        // outage in place for the whole TTL.
        if (episodes.isNotEmpty()) {
            if (episodeCache.size >= EPISODE_CACHE_MAX_ENTRIES) episodeCache.clear()
            episodeCache[key] = CachedEpisodes(episodes, now)
        }
        pending.complete(episodes)
        episodeInFlight.remove(key, pending)
        return episodes
    }

    private suspend fun loadEpisodes(
        endpoints: List<TVAddonEndpoint>,
        item: TVTamilMVCatalogItem,
        identifiers: List<String>,
    ): List<TVAddonEpisode> = supervisorScope {
        endpoints.map { endpoint ->
            async {
                for (identifier in identifiers) {
                    val url = TVAddonRoutePolicy.metaURL(endpoint, item, identifier) ?: continue
                    val bytes = runCatching { http.get(url, MAX_RESPONSE_BYTES) }.getOrNull() ?: continue
                    val parsed = runCatching { TVAddonEpisodePolicy.parse(bytes.decodeToString()) }.getOrDefault(emptyList())
                    if (parsed.isNotEmpty()) {
                        ReceiverDiagnostics.record("addon.episodes.result", "name=${endpoint.name} count=${parsed.size}")
                        return@async parsed
                    }
                }
                emptyList()
            }
        }.awaitAll()
            .flatten()
            .distinctBy { "${it.season}:${it.episode}" }
            .sortedWith(compareBy(TVAddonEpisode::season, TVAddonEpisode::episode))
            .take(MAX_EPISODES)
    }

    private suspend fun loadEndpoint(
        endpointIndex: Int,
        endpoint: TVAddonEndpoint,
        item: TVTamilMVCatalogItem,
        identifiers: List<String>,
    ): EndpointOutcome {
        var hadSuccessfulResponse = false
        var torrentOnly = 0
        val playable = mutableListOf<TVAddonPlayableSource>()
        for (identifier in identifiers) {
            val url = TVAddonRoutePolicy.streamURL(endpoint, item, identifier) ?: continue
            val response = try {
                decode(http.get(url, MAX_RESPONSE_BYTES))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                continue
            }
            hadSuccessfulResponse = true
            ReceiverDiagnostics.record(
                "addon.sources.response",
                "name=${endpoint.name} streams=${response.streams.size} urls=${response.streams.count { !it.url.isNullOrBlank() }} hashes=${response.streams.count { !it.infoHash.isNullOrBlank() }}",
            )
            torrentOnly += response.streams.count { it.url.isNullOrBlank() && !it.infoHash.isNullOrBlank() }
            response.streams.forEachIndexed { streamIndex, stream ->
                // Several Stremio addons return a successful JSON response containing a
                // placeholder MP4 when no real result exists. It is not a playable source and
                // must not become a misleading PLAY row in the TV picker.
                if (stream.behaviorHints?.notWebReady == true) {
                    ReceiverDiagnostics.record("addon.sources.rejected", "name=${endpoint.name} reason=not_web_ready")
                    return@forEachIndexed
                }
                val direct = TVAddonRoutePolicy.playableURL(stream.url) ?: run {
                    if (!stream.url.isNullOrBlank()) {
                        ReceiverDiagnostics.record("addon.sources.rejected", "name=${endpoint.name} reason=invalid_url length=${stream.url.length}")
                    }
                    return@forEachIndexed
                }
                if (direct.contains("/assets/stream-errors/", ignoreCase = true)) {
                    ReceiverDiagnostics.record("addon.sources.rejected", "name=${endpoint.name} reason=placeholder")
                    return@forEachIndexed
                }
                val title = listOf(stream.behaviorHints?.filename, stream.title, stream.name)
                    .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
                    .maxByOrNull(::decisionFactWeight)
                    ?.take(TITLE_LIMIT)
                    ?: "Direct stream"
                val combined = listOfNotNull(stream.name, stream.title, stream.description, stream.behaviorHints?.filename)
                    .joinToString(" ")
                playable += TVAddonPlayableSource(
                    id = "$endpointIndex:$streamIndex:${direct.hashCode()}",
                    url = direct,
                    title = title,
                    detail = stream.description?.trim()?.take(DETAIL_LIMIT).orEmpty(),
                    addonName = endpoint.name,
                    quality = quality(combined),
                    sizeBytes = stream.behaviorHints?.videoSize?.takeIf { it > 0 },
                )
            }
            if (playable.isNotEmpty()) break
        }
        return EndpointOutcome(playable, torrentOnly, failed = !hadSuccessfulResponse)
    }

    private fun decode(bytes: ByteArray): AddonStreamResponse {
        require(bytes.size in 1..MAX_RESPONSE_BYTES) { "addon_response_size" }
        val response = tolerantJson.decodeFromString<AddonStreamResponse>(bytes.decodeToString())
        require(response.streams.size <= MAX_RESPONSE_STREAMS) { "addon_response_count" }
        return response
    }

    private fun quality(text: String): String? = when {
        Regex("(?i)(2160p|4k)").containsMatchIn(text) -> "4K"
        Regex("(?i)1080p").containsMatchIn(text) -> "1080p"
        Regex("(?i)720p").containsMatchIn(text) -> "720p"
        Regex("(?i)(480p|576p)").containsMatchIn(text) -> "SD"
        else -> null
    }

    private fun qualityRank(vararg text: String?): Int = when (quality(text.filterNotNull().joinToString(" "))) {
        "4K" -> 4
        "1080p" -> 3
        "720p" -> 2
        "SD" -> 1
        else -> 0
    }

    private fun decisionFactWeight(text: String): Int = text.length + qualityRank(text) * 1_000

    private data class EndpointOutcome(
        val sources: List<TVAddonPlayableSource>,
        val torrentOnlyCount: Int,
        val failed: Boolean,
    )

    private companion object {
        val tolerantJson = Json { ignoreUnknownKeys = true; isLenient = true }
        /**
         * How long the viewer waits for the WHOLE set of addons, not one call.
         *
         * Deliberately well under the 15s per-call timeout: by this point the endpoints that were
         * going to answer have answered, and everything still outstanding is a provider having a
         * bad day. Showing eleven sources now beats showing fourteen in fifteen seconds.
         */
        const val AGGREGATE_DEADLINE_MILLIS = 6_000L

        /**
         * Long enough that browsing in and out of a series is free, short enough that a season
         * airing today shows up without restarting the box.
         */
        const val EPISODE_CACHE_TTL_MILLIS = 30 * 60 * 1_000L
        const val EPISODE_CACHE_MAX_ENTRIES = 64

        const val MAX_RESPONSE_BYTES = 2 * 1_024 * 1_024
        const val MAX_RESPONSE_STREAMS = 500
        const val MAX_PLAYABLE_SOURCES = 200
        const val MAX_EPISODES = 1_000
        const val TITLE_LIMIT = 180
        const val DETAIL_LIMIT = 320
    }
}

/** Tolerant parser for the Stremio meta.videos shape used by series addons. */
internal object TVAddonEpisodePolicy {
    private val tolerantJson = Json { ignoreUnknownKeys = true; isLenient = true }
    private val seasonEpisodePattern = Regex("(?i)s(\\d{1,3})e(\\d{1,4})")
    private val colonPattern = Regex(":(\\d{1,3}):(\\d{1,4})$")

    fun parse(raw: String): List<TVAddonEpisode> {
        val root = runCatching { tolerantJson.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return emptyList()
        val meta = root["meta"] as? JsonObject ?: root
        val videos = meta["videos"] as? JsonArray ?: return emptyList()
        return videos.mapNotNull { element -> parseEpisode(element) }
            .distinctBy { "${it.season}:${it.episode}" }
            .sortedWith(compareBy(TVAddonEpisode::season, TVAddonEpisode::episode))
            .take(1_000)
    }

    private fun parseEpisode(element: JsonElement): TVAddonEpisode? {
        val objectValue = element as? JsonObject ?: return null
        val id = objectValue.string("id").orEmpty()
        val idMatch = seasonEpisodePattern.find(id) ?: colonPattern.find(id)
        val season = objectValue.int("season") ?: idMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
        val episode = objectValue.int("episode") ?: idMatch?.groupValues?.getOrNull(2)?.toIntOrNull()
        if (season == null || episode == null || season !in 1..100 || episode !in 1..1_000) return null
        val title = objectValue.string("title")?.takeIf { it.isNotBlank() } ?: "Episode $episode"
        return TVAddonEpisode(
            season = season,
            episode = episode,
            title = title.take(240),
            overview = objectValue.string("overview")?.take(1_000),
            thumbnailURL = objectValue.string("thumbnail")?.takeIf { TVAddonRoutePolicy.playableURL(it) != null },
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
}

@Serializable
private data class AddonStreamResponse(val streams: List<AddonStream> = emptyList())

@Serializable
private data class AddonStream(
    val url: String? = null,
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val behaviorHints: AddonBehaviorHints? = null,
)

@Serializable
private data class AddonBehaviorHints(
    val filename: String? = null,
    val videoSize: Long? = null,
    val notWebReady: Boolean? = null,
)

internal fun interface TVAddonHTTPClient {
    suspend fun get(url: String, maximumBytes: Int): ByteArray
}

internal class TVAddonHTTPException(val statusCode: Int) : Exception("addon_http_status")

/** Async, cancellable HTTP with redirects disabled so credential-bearing addon routes cannot leak. */
internal class OkHttpTVAddonHTTPClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) : TVAddonHTTPClient {
    override suspend fun get(url: String, maximumBytes: Int): ByteArray {
        require(maximumBytes > 0) { "maximum_bytes" }
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (it.code !in 200..299) throw TVAddonHTTPException(it.code)
                            val body = it.body ?: throw IllegalArgumentException("addon_response_empty")
                            val declared = body.contentLength()
                            if (declared > maximumBytes) throw IllegalArgumentException("addon_response_size")
                            val output = ByteArrayOutputStream(minOf(maximumBytes, 64 * 1_024))
                            body.byteStream().use { input ->
                                val buffer = ByteArray(16 * 1_024)
                                var total = 0
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    total += read
                                    if (total > maximumBytes) throw IllegalArgumentException("addon_response_size")
                                    output.write(buffer, 0, read)
                                }
                            }
                            if (continuation.isActive) continuation.resumeWith(Result.success(output.toByteArray()))
                        }
                    } catch (failure: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    }
                }
            })
        }
    }
}
