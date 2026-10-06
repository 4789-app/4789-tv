// Derived from NuvioTV (GPL-3.0)
//   app/src/main/java/com/nuvio/tv/core/debrid/DirectDebridResolver.kt
//
// Their front door is kept: a direct URL passes through untouched, an info hash is routed to a
// provider, a short-lived memory cache stops a repeat press paying twice, and an in-flight map
// means two callers asking together issue one resolve. Hilt, DataStore and their `Stream` domain
// type are gone; the providers here speak OkHttp.
package com.fourseveneightnine.tv.client.data.streams.debrid

import com.fourseveneightnine.tv.client.data.addons.defaultAddonHttpClient
import com.fourseveneightnine.tv.client.data.streams.DebridService
import com.fourseveneightnine.tv.client.data.streams.PlaybackRules
import com.fourseveneightnine.tv.client.data.streams.StreamRow
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import okhttp3.OkHttpClient

/** A URL the player can open, plus whatever headers it needs to keep. */
data class Resolved(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val filename: String? = null,
    val sizeBytes: Long? = null,
    /** "TorBox", "Real-Debrid", or null when the row already carried a URL. */
    val service: String? = null,
)

/** Every way a resolve can end. Each one has its own sentence on the Streams error card. */
sealed interface DebridOutcome {
    data class Success(val resolved: Resolved) : DebridOutcome

    /** No key for the provider this row needs. */
    data object MissingApiKey : DebridOutcome

    /** The provider does not hold this hash, and we will not start a download to get it. */
    data object NotCached : DebridOutcome

    /** The provider answered, but not with anything playable. Try another row. */
    data object Stale : DebridOutcome

    /** The network or the provider fell over. */
    data object Error : DebridOutcome
}

/**
 * The one place a row becomes a URL.
 *
 * **Nothing here is ever written down.** A resolved link is a capability: anyone holding it can
 * stream the owner's account until it expires. It lives in this object's memory for fifteen
 * minutes and dies with the process — never in Room, never in a file, never in a log line. That is
 * the same rule `:contract` states for `StreamEntry.durableURL()`.
 *
 * **Cached is a hint, never a probe.** Real-Debrid removed instant-availability in 2024, so the
 * order here is simply the owner's preference from [PlaybackRules.checkOrder]: ask the first
 * configured provider, and on a "not cached" ask the next. A row with a `[RD+]` badge and a row
 * without take exactly the same path, because the badge is a claim and not a fact.
 */
class DebridResolver(
    torboxKey: String?,
    rdKey: String?,
    private val okHttp: OkHttpClient = defaultAddonHttpClient(),
    private val rules: PlaybackRules = PlaybackRules.DEFAULT,
    private val clock: () -> Long = System::currentTimeMillis,
    torboxBaseURL: String = TorboxResolver.BASE_URL,
    realDebridBaseURL: String = RealDebridResolver.BASE_URL,
) {
    private val torbox = torboxKey?.takeIf(String::isNotBlank)
        ?.let { TorboxResolver(it, okHttp, torboxBaseURL) }
    private val realDebrid = rdKey?.takeIf(String::isNotBlank)
        ?.let { RealDebridResolver(it, okHttp, realDebridBaseURL) }

    private data class CachedResolve(val resolved: Resolved, val atMillis: Long)

    private val cache = ConcurrentHashMap<String, CachedResolve>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<DebridOutcome>>()

    /** Which providers are configured at all. Preference is filtered against this. */
    val configured: Set<DebridService> = buildSet {
        if (torbox != null) add(DebridService.TORBOX)
        if (realDebrid != null) add(DebridService.REAL_DEBRID)
    }

    suspend fun resolve(row: StreamRow, season: Int? = null, episode: Int? = null): DebridOutcome {
        // A row that already has a URL is already playable. Sending it to a debrid provider would
        // be a round trip that can only make it worse.
        row.url?.takeIf(String::isNotBlank)?.let {
            return DebridOutcome.Success(Resolved(url = it, headers = row.headers))
        }
        if (row.infoHash.isNullOrBlank()) return DebridOutcome.Stale
        if (configured.isEmpty()) return DebridOutcome.MissingApiKey

        val key = cacheKey(row, season, episode)
        cached(key)?.let { return DebridOutcome.Success(it) }

        inFlight[key]?.let { return it.await() }
        val pending = CompletableDeferred<DebridOutcome>()
        inFlight.putIfAbsent(key, pending)?.let { return it.await() }

        val outcome = try {
            attempt(row, season, episode)
        } catch (failure: Throwable) {
            pending.complete(DebridOutcome.Error)
            inFlight.remove(key, pending)
            throw failure
        }
        if (outcome is DebridOutcome.Success) {
            if (cache.size >= CACHE_MAX_ENTRIES) cache.clear()
            cache[key] = CachedResolve(outcome.resolved, clock())
        }
        pending.complete(outcome)
        inFlight.remove(key, pending)
        return outcome
    }

    /** Forget every resolved link. Called when the app leaves the foreground for good. */
    fun clear() {
        cache.clear()
    }

    private suspend fun attempt(row: StreamRow, season: Int?, episode: Int?): DebridOutcome {
        var reported: DebridOutcome? = null
        for (service in rules.checkOrder(configured)) {
            val outcome = when (service) {
                DebridService.TORBOX -> torbox?.resolve(row, season, episode)
                DebridService.REAL_DEBRID -> realDebrid?.resolve(row, season, episode)
            } ?: continue
            if (outcome is DebridOutcome.Success) return outcome
            // Keep the answer that tells the owner the most. "Not cached" names a real state of
            // the world; "the key is wrong" names something they can fix; "stale" and "error" say
            // almost nothing, so they never overwrite either.
            if (informativeness(outcome) > informativeness(reported)) reported = outcome
        }
        return reported ?: DebridOutcome.Stale
    }

    private fun informativeness(outcome: DebridOutcome?): Int = when (outcome) {
        DebridOutcome.NotCached -> 3
        DebridOutcome.MissingApiKey -> 2
        DebridOutcome.Stale -> 1
        else -> 0
    }

    private fun cached(key: String): Resolved? {
        val entry = cache[key] ?: return null
        val age = clock() - entry.atMillis
        if (age in 0..CACHE_TTL_MILLIS) return entry.resolved
        cache.remove(key, entry)
        return null
    }

    /**
     * The key holds no credential. A resolve is per hash, per file and per episode, and the key
     * says exactly that and nothing more.
     */
    private fun cacheKey(row: StreamRow, season: Int?, episode: Int?): String = listOf(
        row.infoHash.orEmpty().lowercase(),
        row.fileIdx?.toString().orEmpty(),
        season?.toString().orEmpty(),
        episode?.toString().orEmpty(),
    ).joinToString("|")

    companion object {
        /** Long enough that backing out and pressing Play again is free. Short enough to expire. */
        const val CACHE_TTL_MILLIS: Long = 15 * 60 * 1_000L
        const val CACHE_MAX_ENTRIES: Int = 64
    }
}
