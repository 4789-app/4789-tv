package com.fourseveneightnine.tv.catalog

import com.fourseveneightnine.tv.settings.StoredTVSettingsState
import com.fourseveneightnine.tv.settings.TVSettingsPersistence
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics
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
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** Landscape art for the hero, plus the title's own logo when one exists. */
internal data class TVEnrichedArtwork(
    val backdropURL: String?,
    val logoURL: String?,
)

/**
 * Fills in the artwork the catalog snapshot does not carry.
 *
 * The rows the phone sends have a poster and a TMDB id and, for a great many titles, nothing else:
 * `backdropURL` is null. The hero then had no landscape image to draw and fell back to the portrait
 * poster, which is why a title like KJQ looked wrong on a 16:9 stage while every other client in
 * this space showed a proper wide still. Those clients are not getting better data from the
 * catalog — they ask TMDB for the images, and we never did, despite already syncing a TMDB key
 * from the phone.
 *
 * Artwork is close to immutable, so the cache is generous. Every failure resolves to no artwork
 * and the caller keeps whatever it was already drawing.
 */
internal class TVArtworkEnrichmentRepository(
    private val settings: TVSettingsPersistence,
    private val http: TVArtworkHTTPClient = OkHttpTVArtworkHTTPClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Survives the process, so a cold start redraws instead of re-asking TMDB. */
    private val disk: TVEnrichmentDiskCache? = null,
) {
    private data class Cached(val artwork: TVEnrichedArtwork, val storedAtMillis: Long)

    private val cache = ConcurrentHashMap<String, Cached>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<TVEnrichedArtwork>>()

    suspend fun artwork(item: TVTamilMVCatalogItem): TVEnrichedArtwork =
        withContext(ioDispatcher) {
            val tmdbID = item.tmdbID?.takeIf { it > 0 } ?: return@withContext EMPTY
            val key = TVTMDBCredentialPolicy.apiKey(settings.load()) ?: return@withContext EMPTY
            val kind = if (item.mediaType == "series") "tv" else "movie"
            val cacheKey = "$kind:$tmdbID"

            val now = clock()
            cache[cacheKey]?.let { entry ->
                if (now - entry.storedAtMillis < CACHE_TTL_MILLIS) return@withContext entry.artwork
                cache.remove(cacheKey, entry)
            }
            disk?.get(cacheKey)?.let { stored ->
                val restored = decode(stored)
                if (restored != EMPTY) {
                    cache[cacheKey] = Cached(restored, now)
                    return@withContext restored
                }
            }

            inFlight[cacheKey]?.let { return@withContext it.await() }
            val pending = CompletableDeferred<TVEnrichedArtwork>()
            inFlight.putIfAbsent(cacheKey, pending)?.let { return@withContext it.await() }

            val artwork = try {
                parse(http.get(url(key, kind, tmdbID), MAX_RESPONSE_BYTES).decodeToString())
            } catch (error: Throwable) {
                // No URL in the breadcrumb: it carries the key.
                ReceiverDiagnostics.record("artwork.enrich.failed", error::class.java.simpleName)
                EMPTY
            }
            if (artwork.backdropURL != null || artwork.logoURL != null) {
                if (cache.size >= CACHE_MAX_ENTRIES) cache.clear()
                cache[cacheKey] = Cached(artwork, now)
                disk?.put(cacheKey, encode(artwork))
            }
            ReceiverDiagnostics.record(
                "artwork.enrich",
                "$cacheKey backdrop=${artwork.backdropURL != null} logo=${artwork.logoURL != null}",
            )
            pending.complete(artwork)
            inFlight.remove(cacheKey, pending)
            artwork
        }

    private fun url(apiKey: String, kind: String, tmdbID: Int): String =
        "$BASE$kind/$tmdbID/images?api_key=$apiKey&include_image_language=$IMAGE_LANGUAGES"

    internal companion object {
        const val BASE = "https://api.themoviedb.org/3/"

        /**
         * Language-free art first. A backdrop with burnt-in English titling is worse than a clean
         * still, and `null` is TMDB's marker for no text at all. The logo is the opposite case:
         * there we WANT the lettering, so English is acceptable.
         */
        const val IMAGE_LANGUAGES = "en,null"

        /** Artwork barely changes; a day avoids re-asking for every title on every cold start. */
        const val CACHE_TTL_MILLIS = 24 * 60 * 60 * 1_000L
        const val CACHE_MAX_ENTRIES = 256
        const val MAX_RESPONSE_BYTES = 512 * 1_024

        /** Wide enough for a 1080p hero without paying for /original/. */
        const val BACKDROP_SIZE = "w1280"
        const val LOGO_SIZE = "w500"
        const val IMAGE_BASE = "https://image.tmdb.org/t/p/"

        val EMPTY = TVEnrichedArtwork(null, null)

        /** Two urls on one line. A tab cannot appear in a url, so it is a safe separator. */
        fun encode(artwork: TVEnrichedArtwork): String =
            "${artwork.backdropURL.orEmpty()}\t${artwork.logoURL.orEmpty()}"

        fun decode(stored: String): TVEnrichedArtwork {
            val parts = stored.split('\t')
            return TVEnrichedArtwork(
                backdropURL = parts.getOrNull(0)?.takeIf { it.isNotEmpty() },
                logoURL = parts.getOrNull(1)?.takeIf { it.isNotEmpty() },
            )
        }

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun parse(text: String): TVEnrichedArtwork {
            val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                ?: return EMPTY
            return TVEnrichedArtwork(
                backdropURL = best(root["backdrops"] as? JsonArray, preferNoLanguage = true)
                    ?.let { IMAGE_BASE + BACKDROP_SIZE + it },
                logoURL = best(root["logos"] as? JsonArray, preferNoLanguage = false)
                    ?.let { IMAGE_BASE + LOGO_SIZE + it },
            )
        }

        /**
         * TMDB returns its images already ordered by vote, but a text-free still can sit below a
         * localised one, so language is the first sort key and the site's own ranking is the
         * tiebreak. Anything without a usable path is dropped rather than turned into a broken URL.
         */
        private fun best(array: JsonArray?, preferNoLanguage: Boolean): String? {
            if (array == null) return null
            data class Candidate(val path: String, val language: String?, val votes: Double)
            val candidates = array.mapNotNull { element ->
                val entry = element as? JsonObject ?: return@mapNotNull null
                val path = (entry["file_path"] as? JsonPrimitive)?.contentOrNull
                    ?.takeIf { it.startsWith("/") && it.length in 2..200 }
                    ?: return@mapNotNull null
                val language = (entry["iso_639_1"] as? JsonPrimitive)?.contentOrNull
                val votes = (entry["vote_average"] as? JsonPrimitive)?.doubleOrNull
                    ?: (entry["vote_count"] as? JsonPrimitive)?.intOrNull?.toDouble()
                    ?: 0.0
                Candidate(path, language, votes)
            }
            if (candidates.isEmpty()) return null
            return candidates.sortedWith(
                compareByDescending<Candidate> {
                    if (preferNoLanguage) it.language == null else it.language == "en"
                }.thenByDescending { it.votes },
            ).first().path
        }
    }
}

/** Reads the TMDB key out of the Keystore-encrypted settings document. */
internal object TVTMDBCredentialPolicy {
    private val json = Json { ignoreUnknownKeys = true }

    fun apiKey(state: StoredTVSettingsState): String? {
        val raw = (state as? StoredTVSettingsState.Available)?.document?.rawJson ?: return null
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        return (root["tmdbAPIKey"] as? JsonPrimitive)?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() && SAFE_KEY.matches(it) }
    }

    private val SAFE_KEY = Regex("[A-Za-z0-9._-]{8,128}")
}

internal interface TVArtworkHTTPClient {
    suspend fun get(url: String, maxBytes: Int): ByteArray
}

private class OkHttpTVArtworkHTTPClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build(),
) : TVArtworkHTTPClient {
    override suspend fun get(url: String, maxBytes: Int): ByteArray = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).header("Accept", "application/json").build())
            .execute()
            .use { response ->
                require(response.isSuccessful) { "tmdb_images_http_${response.code}" }
                val bytes = response.body?.bytes() ?: ByteArray(0)
                require(bytes.size <= maxBytes) { "tmdb_images_too_large" }
                bytes
            }
    }
}
