package com.fourseveneightnine.tv.client.data.meta

import com.fourseveneightnine.tv.client.data.addons.defaultAddonHttpClient
import com.fourseveneightnine.tv.client.data.addons.getText
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.OkHttpClient

/**
 * IMDb, Trakt, TMDB and Letterboxd scores for the Detail ratings row (§9.4).
 *
 * One MDBList call returns every provider for one IMDb id, so a Detail pane costs one round trip
 * rather than one per provider. This is decoration on a screen that has already painted, so every
 * failure resolves to [Ratings.NONE] and the row simply does not draw.
 *
 * **The path form, never `?i=`.** MDBList's legacy query form now answers 200 with the API index
 * page — valid JSON containing no ratings — so the mistake is invisible unless you read the body.
 * It cost a build cycle once. The path form is `api.mdblist.com/imdb/{movie|show}/{tt}?apikey=`.
 *
 * The API key never reaches a log line, including on the failure paths: the URL carries the
 * credential, so a breadcrumb records a status code or a class name and nothing else.
 */
class RatingsRepository(
    private val mdbListKey: String? = null,
    private val okHttp: OkHttpClient = defaultAddonHttpClient(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val baseURL: String = BASE_URL,
) {
    private val cache = ConcurrentHashMap<String, Pair<Ratings, Long>>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Ratings>>()

    /** Scores for one IMDb id, or [Ratings.NONE] when there is no key, no id, or no answer. */
    suspend fun ratings(imdbId: String, isShow: Boolean): Ratings {
        val id = imdbId.trim().takeIf(IMDB_ID::matches) ?: return Ratings.NONE
        val key = mdbListKey?.trim()?.takeIf { it.isNotEmpty() && SAFE_KEY.matches(it) }
            ?: return Ratings.NONE

        cache[id]?.let { (ratings, storedAt) ->
            if (clock() - storedAt < CACHE_TTL_MILLIS) return ratings
            cache.remove(id)
        }

        inFlight[id]?.let { return it.await() }
        val pending = CompletableDeferred<Ratings>()
        inFlight.putIfAbsent(id, pending)?.let { return it.await() }

        val ratings = try {
            val kind = if (isShow) "show" else "movie"
            parse(okHttp.getText("$baseURL/imdb/$kind/$id?apikey=$key", MAX_RESPONSE_BYTES).body)
        } catch (cancelled: CancellationException) {
            pending.complete(Ratings.NONE)
            inFlight.remove(id, pending)
            throw cancelled
        } catch (_: Throwable) {
            Ratings.NONE
        }
        if (!ratings.isEmpty) {
            if (cache.size >= CACHE_MAX_ENTRIES) cache.clear()
            cache[id] = ratings to clock()
        }
        pending.complete(ratings)
        inFlight.remove(id, pending)
        return ratings
    }

    fun clear() {
        cache.clear()
    }

    companion object {
        const val BASE_URL: String = "https://api.mdblist.com"
        const val CACHE_TTL_MILLIS: Long = 30 * 60 * 1_000L
        const val CACHE_MAX_ENTRIES: Int = 128
        const val MAX_RESPONSE_BYTES: Int = 256 * 1_024

        val IMDB_ID: Regex = Regex("^tt[0-9]{6,12}$")
        private val SAFE_KEY = Regex("[A-Za-z0-9._-]{8,128}")
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Tolerant on purpose. MDBList returns `ratings: [{source, value, score}]` and has moved
         * which of `value`/`score` is populated before. A shape we do not recognise must degrade to
         * "no ratings", never to an exception on a screen that has already drawn.
         */
        fun parse(body: String): Ratings {
            val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
                ?: return Ratings.NONE
            val entries = (root["ratings"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            var imdb: Double? = null
            var trakt: Int? = null
            var tmdb: Int? = null
            var letterboxd: Double? = null
            entries.forEach { entry ->
                val source = (entry["source"] as? JsonPrimitive)?.contentOrNull
                    ?.lowercase(Locale.US)?.trim() ?: return@forEach
                val number = (entry["value"] as? JsonPrimitive)?.doubleOrNull
                    ?: (entry["score"] as? JsonPrimitive)?.doubleOrNull
                    ?: return@forEach
                // A provider with no data reports zero rather than omitting itself.
                if (number <= 0.0) return@forEach
                when (source) {
                    "imdb" -> imdb = number.coerceIn(0.0, 10.0)
                    // Trakt reports a percentage on a 0-100 scale under a name that looks like a
                    // score out of ten, so a value at or under 10 is read as one and scaled.
                    "trakt" -> trakt = (if (number <= 10.0) number * 10 else number).toInt().coerceIn(0, 100)
                    "tmdb" -> tmdb = (if (number <= 10.0) number * 10 else number).toInt().coerceIn(0, 100)
                    "letterboxd" -> letterboxd = number.coerceIn(0.0, 5.0)
                }
            }
            return Ratings(imdb = imdb, trakt = trakt, tmdb = tmdb, letterboxd = letterboxd)
        }
    }
}
