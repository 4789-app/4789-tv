package com.fourseveneightnine.tv.catalog

import com.fourseveneightnine.tv.settings.StoredTVSettingsState
import com.fourseveneightnine.tv.settings.TVSettingsPersistence
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** One critic or audience score, already formatted for display. */
internal data class TVRating(
    /** Stable lower-case key: imdb, tmdb, trakt, letterboxd, tomatoes, audience, metacritic, mal. */
    val source: String,
    /** Short display label, e.g. "IMDb". */
    val label: String,
    /** Pre-formatted for the row, e.g. "8.4" or "92%". */
    val display: String,
)

internal sealed interface TVRatingsState {
    data object Idle : TVRatingsState
    data class Ready(val itemID: String, val ratings: List<TVRating>) : TVRatingsState
    /** No key configured, no IMDb ID, or the service had nothing. The row simply does not draw. */
    data class Unavailable(val itemID: String) : TVRatingsState
}

/**
 * Critic and audience scores for the detail pane.
 *
 * The phone has always synced an MDBList key to this box and the receiver never spent it, so a
 * title opened on the TV showed a year and a genre list where every other client in this space
 * shows scores. MDBList is the right shape for a TV: one broker returns every provider's number
 * for one IMDb ID in a single request, so the pane costs one round trip rather than one per
 * provider.
 *
 * Three properties matter more than the feature itself, because this must never delay or break
 * the pane it decorates:
 *  - it is decoration, so every failure resolves to [TVRatingsState.Unavailable] and nothing is
 *    ever shown to the viewer about it;
 *  - repeat visits are free (TTL cache), and two callers asking together issue one request;
 *  - the API key never reaches a log line, including on the failure paths.
 */
internal class TVRatingsRepository(
    private val settings: TVSettingsPersistence,
    private val http: TVRatingsHTTPClient = OkHttpTVRatingsHTTPClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Survives the process: a cold start should not re-ask for scores it already had. */
    private val disk: TVEnrichmentDiskCache? = null,
) {
    private data class CachedRatings(val ratings: List<TVRating>, val storedAtMillis: Long)

    private val cache = ConcurrentHashMap<String, CachedRatings>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<List<TVRating>>>()

    suspend fun ratings(item: TVTamilMVCatalogItem): List<TVRating> = withContext(ioDispatcher) {
        val imdbID = item.imdbID?.trim()?.takeIf(IMDB_ID::matches) ?: return@withContext emptyList()
        val key = TVRatingsCredentialPolicy.apiKey(settings.load()) ?: return@withContext emptyList()

        val now = clock()
        cache[imdbID]?.let { entry ->
            if (now - entry.storedAtMillis < CACHE_TTL_MILLIS) return@withContext entry.ratings
            cache.remove(imdbID, entry)
        }

        disk?.get(imdbID)?.let { stored ->
            val restored = decodeRatings(stored)
            if (restored.isNotEmpty()) {
                cache[imdbID] = CachedRatings(restored, now)
                return@withContext restored
            }
        }

        inFlight[imdbID]?.let { return@withContext it.await() }
        val pending = CompletableDeferred<List<TVRating>>()
        inFlight.putIfAbsent(imdbID, pending)?.let { return@withContext it.await() }

        val ratings = try {
            val body = http.get(url(key, imdbID, item.mediaType), MAX_RESPONSE_BYTES)
            val text = body.decodeToString()
            parse(text).also { parsed ->
                // The response never contains the credential (that is in the query string), so a
                // short prefix is safe to record and is the only way to see a shape change.
                if (parsed.isEmpty()) {
                    ReceiverDiagnostics.record("ratings.unparsed", text.take(220).replace('\n', ' '))
                }
            }
        } catch (error: Throwable) {
            // Deliberately no URL and no key in the breadcrumb — the URL carries the credential.
            ReceiverDiagnostics.record("ratings.failed", error::class.java.simpleName)
            emptyList()
        }
        if (ratings.isNotEmpty()) {
            if (cache.size >= CACHE_MAX_ENTRIES) cache.clear()
            cache[imdbID] = CachedRatings(ratings, now)
            disk?.put(imdbID, encodeRatings(ratings))
        }
        ReceiverDiagnostics.record("ratings.result", "count=${ratings.size}")
        pending.complete(ratings)
        inFlight.remove(imdbID, pending)
        ratings
    }

    /**
     * Path-based, not the old `?i=` query form. That legacy shape now answers 200 with MDBList's
     * API index page, which parses as valid JSON containing no ratings — so the mistake is silent
     * unless you look at the body. Verified on device.
     */
    private fun url(apiKey: String, imdbID: String, mediaType: String): String {
        val kind = if (mediaType == "series") "show" else "movie"
        return "${BASE}imdb/$kind/$imdbID?apikey=$apiKey"
    }

    internal companion object {
        const val BASE = "https://api.mdblist.com/"
        val IMDB_ID = Regex("^tt[0-9]{6,12}$")
        const val CACHE_TTL_MILLIS = 30 * 60 * 1_000L
        const val CACHE_MAX_ENTRIES = 128
        const val MAX_RESPONSE_BYTES = 256 * 1_024

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Provider key -> display label, in the order the row draws them. Anything MDBList returns
         * that is not in this map is dropped rather than rendered under a raw key.
         */
        private val LABELS = linkedMapOf(
            "imdb" to "IMDb",
            "tmdb" to "TMDB",
            "trakt" to "Trakt",
            "letterboxd" to "Letterboxd",
            "tomatoes" to "Tomatometer",
            "tomatoesaudience" to "Audience",
            "audience" to "Audience",
            "metacritic" to "Metacritic",
            "metacriticuser" to "Metacritic users",
            "myanimelist" to "MyAnimeList",
        )

        /** Providers whose numbers are percentages, not a score out of ten. */
        private val PERCENT_SOURCES = setOf(
            "tomatoes", "tomatoesaudience", "audience", "metacritic", "metacriticuser",
        )

        /**
         * Tolerant on purpose. MDBList returns a `ratings` array of `{source, value, score}` and
         * has moved which of `value`/`score` is populated before; a shape we do not recognise must
         * degrade to "no ratings", never to an exception on the detail screen.
         */
        fun parse(text: String): List<TVRating> {
            val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                ?: return emptyList()
            val array = root["ratings"] as? JsonArray ?: return emptyList()
            return array.mapNotNull { element ->
                val entry = element as? JsonObject ?: return@mapNotNull null
                val source = (entry["source"] as? JsonPrimitive)?.contentOrNull
                    ?.lowercase(Locale.US)?.trim()
                    ?: return@mapNotNull null
                val label = LABELS[source] ?: return@mapNotNull null
                val number = (entry["value"] as? JsonPrimitive)?.doubleOrNull
                    ?: (entry["score"] as? JsonPrimitive)?.doubleOrNull
                    ?: return@mapNotNull null
                // A provider with no data reports zero rather than omitting itself.
                if (number <= 0.0) return@mapNotNull null
                TVRating(source = source, label = label, display = format(source, number))
            }
                .distinctBy { it.label }
                .take(MAX_RATINGS)
        }

        private fun format(source: String, number: Double): String = when {
            source in PERCENT_SOURCES -> "${number.toInt().coerceIn(0, 100)}%"
            // Trakt reports a percentage on a 0-100 scale under a name that looks like a score.
            source == "trakt" && number > 10.0 -> "${number.toInt().coerceIn(0, 100)}%"
            else -> String.format(Locale.US, "%.1f", number.coerceIn(0.0, 10.0))
        }

        const val MAX_RATINGS = 6

        /** One row per rating, tab-separated fields. Neither a label nor a score contains a tab. */
        fun encodeRatings(ratings: List<TVRating>): String =
            ratings.joinToString("\n") { "${it.source}\t${it.label}\t${it.display}" }

        fun decodeRatings(stored: String): List<TVRating> = stored.split('\n').mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 3 || parts.any { it.isEmpty() }) null
            else TVRating(source = parts[0], label = parts[1], display = parts[2])
        }
    }
}

/** Reads the MDBList key out of the Keystore-encrypted settings document. */
internal object TVRatingsCredentialPolicy {
    private val json = Json { ignoreUnknownKeys = true }

    fun apiKey(state: StoredTVSettingsState): String? {
        val raw = (state as? StoredTVSettingsState.Available)?.document?.rawJson ?: return null
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        return (root["mdbListAPIKey"] as? JsonPrimitive)?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_KEY_LENGTH && SAFE_KEY.matches(it) }
    }

    /** Present without ever exposing the value, for diagnostics and settings display. */
    fun isConfigured(state: StoredTVSettingsState): Boolean = apiKey(state) != null

    private val SAFE_KEY = Regex("[A-Za-z0-9._-]{8,128}")
    private const val MAX_KEY_LENGTH = 128
}

internal interface TVRatingsHTTPClient {
    suspend fun get(url: String, maxBytes: Int): ByteArray
}

private class OkHttpTVRatingsHTTPClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_SECONDS, TimeUnit.SECONDS)
        .build(),
) : TVRatingsHTTPClient {
    override suspend fun get(url: String, maxBytes: Int): ByteArray =
        withContext(Dispatchers.IO) {
            client.newCall(Request.Builder().url(url).header("Accept", "application/json").build())
                .execute()
                .use { response ->
                    require(response.isSuccessful) { "ratings_http_${response.code}" }
                    val bytes = response.body?.bytes() ?: ByteArray(0)
                    require(bytes.size <= maxBytes) { "ratings_response_too_large" }
                    bytes
                }
        }

    private companion object {
        // Short. This is decoration on a pane that has already drawn; it must never be the reason
        // anything feels slow.
        const val CONNECT_SECONDS = 4L
        const val READ_SECONDS = 6L
        const val CALL_SECONDS = 8L
    }
}
