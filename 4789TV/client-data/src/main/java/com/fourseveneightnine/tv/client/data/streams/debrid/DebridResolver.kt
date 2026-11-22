// Derived from NuvioTV (GPL-3.0)
//   app/src/main/java/com/nuvio/tv/core/debrid/DirectDebridResolver.kt
//
// Their front door is kept: a direct URL passes through untouched, an info hash is routed to a
// provider, a short-lived memory cache stops a repeat press paying twice, and an in-flight map
// means two callers asking together issue one resolve. Hilt, DataStore and their `Stream` domain
// type are gone; the providers here speak OkHttp.
package com.fourseveneightnine.tv.client.data.streams.debrid

import kotlinx.coroutines.withTimeoutOrNull
import com.fourseveneightnine.tv.client.data.addons.mediaPrefix
import com.fourseveneightnine.tv.client.data.streams.StreamExpectation
import com.fourseveneightnine.tv.client.data.addons.defaultAddonHttpClient
import com.fourseveneightnine.tv.client.data.streams.DebridService
import com.fourseveneightnine.tv.client.data.streams.PlaybackRules
import com.fourseveneightnine.tv.client.data.streams.StreamRow
import com.fourseveneightnine.tv.client.data.streams.StreamFacts
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A URL the player can open, plus whatever headers it needs to keep. */
data class Resolved(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val filename: String? = null,
    val sizeBytes: Long? = null,
    /** "TorBox", "Real-Debrid", or null when the row already carried a URL. */
    val service: String? = null,
    val torrentIndex: Int? = null,
    val torboxFileId: Int? = null,
)

/** Every way a resolve can end. Each one has its own sentence on the Streams error card. */
sealed interface DebridOutcome {
    data class Success(val resolved: Resolved) : DebridOutcome

    /** No key for the provider this row needs. */
    data object MissingApiKey : DebridOutcome

    /** The provider does not hold this hash, and we will not start a download to get it. */
    data object NotCached : DebridOutcome

    /** TorBox has accepted a cached torrent and is still preparing its account entry or link. */
    data object TorboxPreparing : DebridOutcome

    /** The provider answered, but not with anything playable. Try another row. */
    data object Stale : DebridOutcome

    /** The network or the provider fell over. */
    data object Error : DebridOutcome
}

data class TorboxLibraryState(
    val rows: List<TorboxLibraryTorrent> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val updatedAtMillis: Long = 0,
    val message: String? = null,
)

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
    private val diagnostics: (StreamRow, StreamExpectation?, String) -> Unit = { _, _, _ -> },
) {
    private val torbox = torboxKey?.takeIf(String::isNotBlank)
        ?.let { TorboxResolver(it, okHttp, torboxBaseURL) }
    private val realDebrid = rdKey?.takeIf(String::isNotBlank)
        ?.let { RealDebridResolver(it, okHttp, realDebridBaseURL) }

    private data class CachedResolve(val resolved: Resolved, val atMillis: Long, val mediaChecked: Boolean = false)

    private val cache = ConcurrentHashMap<String, CachedResolve>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<DebridOutcome>>()
    private val unavailable = ConcurrentHashMap<String, Long>()
    private val mediaHttp = okHttp.newBuilder().followRedirects(true).followSslRedirects(false)
        .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS).readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
    private val libraryGate = Mutex()
    private val mutableLibrary = MutableStateFlow(TorboxLibraryState())
    val libraryState = mutableLibrary.asStateFlow()

    /** The folder card and opened account share this result; a failed refresh retains its rows. */
    suspend fun refreshTorboxLibrary(force: Boolean = false) = withContext(Dispatchers.IO) { libraryGate.withLock {
        val before = mutableLibrary.value
        if (!force && before.loaded && clock() - before.updatedAtMillis in 0 until 60_000L) return@withLock
        mutableLibrary.value = before.copy(loading = true, message = null)
        try {
            mutableLibrary.value = TorboxLibraryState(torboxLibrary(), loaded = true, updatedAtMillis = clock())
        } catch (cancelled: CancellationException) {
            mutableLibrary.value = before.copy(loading = false)
            throw cancelled
        } catch (failure: Exception) {
            mutableLibrary.value = before.copy(message = failure.message ?: "My Library could not refresh. Retry.")
        }
    } }

    /** Which providers are configured at all. Preference is filtered against this. */
    val configured: Set<DebridService> = buildSet {
        if (torbox != null) add(DebridService.TORBOX)
        if (realDebrid != null) add(DebridService.REAL_DEBRID)
    }

    suspend fun torboxLibrary(): List<TorboxLibraryTorrent> =
        torbox?.library() ?: error("Add your TorBox key in TV Settings to load My Library.")

    suspend fun torboxFiles(infoHash: String): List<DebridFile> =
        torbox?.cachedFiles(infoHash) ?: error("Add your TorBox key in TV Settings, or sync it from your iPhone.")

    suspend fun torboxFiles(
        infoHashes: List<String>,
        v2ByHash: Map<String, String> = emptyMap(),
    ): Map<String, List<DebridFile>> =
        torbox?.cachedFiles(infoHashes, v2ByHash) ?: error("Add your TorBox key in TV Settings, or sync it from your iPhone.")

    suspend fun isTorboxCached(infoHash: String, v2Infohash: String? = null): Boolean =
        torbox?.isCached(infoHash, v2Infohash) ?: error("Add your TorBox key in TV Settings, or sync it from your iPhone.")

    suspend fun resolve(
        row: StreamRow, season: Int? = null, episode: Int? = null, selectLargestVideo: Boolean = false,
    ): DebridOutcome {
        if (row.theatricalRelease) return DebridOutcome.Stale
        val key = cacheKey(row, season, episode, selectLargestVideo)
        cached(key)?.let { return DebridOutcome.Success(it) }
        // A row that already has a URL is already playable. Sending it to a debrid provider would
        // be a round trip that can only make it worse.
        row.url?.takeIf(String::isNotBlank)?.let {
            return DebridOutcome.Success(Resolved(url = it, headers = row.headers))
        }
        if (row.infoHash.isNullOrBlank()) return DebridOutcome.Stale
        if (configured.isEmpty()) return DebridOutcome.MissingApiKey


        inFlight[key]?.let { return it.await() }
        val pending = CompletableDeferred<DebridOutcome>()
        inFlight.putIfAbsent(key, pending)?.let { return it.await() }

        val outcome = try {
            attempt(row, season, episode, selectLargestVideo)
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
        unavailable.clear()
    }

    /** null means inconclusive. A positive probe is short-lived and never persisted. */
    suspend fun inspect(source: StreamRow, expected: StreamExpectation?, strictName: Boolean = true,
                        allowNativeFallback: Boolean = true,
                        timeoutMillis: Long = MEDIA_INSPECTION_TIMEOUT_MILLIS): Boolean? {
        val budget = timeoutMillis.coerceIn(1L, MANUAL_MEDIA_INSPECTION_TIMEOUT_MILLIS)
        val row = source.copy(expectation = expected ?: source.expectation)
        val started = System.nanoTime()
        fun trace(detail: String) = diagnostics(row, row.expectation, "check=$started $detail")
        fun finish(result: Boolean?, reason: String): Boolean? {
            trace("stage=done result=${result?.toString() ?: "unknown"} reason=$reason elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
            return result
        }
        trace("stage=start strictName=$strictName timeoutMs=$budget")
        if (row.theatricalRelease) return finish(false, "theatrical_release")
        val key = cacheKey(row, expected?.season, expected?.episode)
        if (isUnavailable(row, expected?.season, expected?.episode)) return finish(false, "recent_failure")
        val previous = cache[key]
        if (previous?.mediaChecked == true && clock() - previous.atMillis in 0..30_000L) {
            val name = previous.resolved.filename
            return finish((name == null || nameMatches(row, expected, name, strictName)) &&
                (!strictName || verifiedHttpIdentity(row, expected, name)), "cached_check")
        }
        if (previous?.mediaChecked == true && !row.url.isNullOrBlank()) cache.remove(key, previous)
        val outcome = resolve(row, expected?.season, expected?.episode)
        if (outcome !is DebridOutcome.Success) return finish(if (outcome == DebridOutcome.NotCached) false else null, "resolve_${outcome::class.java.simpleName}")
        val resolved = outcome.resolved
        if (resolved.filename != null && !nameMatches(row, expected, resolved.filename, strictName)) {
            notePlaybackFailure(row, expected?.season, expected?.episode)
            return finish(false, "resolved_filename_mismatch")
        }
        var reason = "inconclusive_media"
        var inspectionFinished = false
        fun verdict(result: Boolean?, why: String): Boolean? { reason = why; return result }
        var finalURL = resolved.url
        var actualName = resolved.filename
        var actualSize = resolved.sizeBytes
        var acceptedHeaders = resolved.headers
        var result = try { withTimeoutOrNull(budget) {
            suspend fun inspectURL(url: String, part: Boolean, visited: Set<String>, encrypted: Boolean = false): Boolean? {
                if (visited.size >= 4 || url in visited || !url.startsWith("http", ignoreCase = true)) return verdict(null, "playlist_route_limit")
                var response = mediaHttp.mediaPrefix(url, resolved.headers)
                trace("stage=http host=${url.toHttpUrlOrNull()?.host ?: "unknown"} status=${response.code} bytes=${response.bytes.size} range=true")
                if (response.code in setOf(403, 416) && resolved.headers.keys.none { it.equals("Range", ignoreCase = true) }) {
                    response = mediaHttp.mediaPrefix(url, resolved.headers, range = false)
                    trace("stage=http host=${url.toHttpUrlOrNull()?.host ?: "unknown"} status=${response.code} bytes=${response.bytes.size} range=false")
                }
                if (response.code in setOf(401, 403, 404, 410, 451)) return verdict(false, "http_${response.code}")
                if (response.code !in 200..299) return verdict(null, "http_${response.code}")
                if (!part) {
                    finalURL = response.finalURL
                    val redirected = response.finalURL.toHttpUrlOrNull()
                    val pathName = redirected?.takeIf { it.host == "real-debrid.com" || it.host.endsWith(".real-debrid.com") }
                        ?.pathSegments?.lastOrNull()?.takeIf {
                            VIDEO_EXTENSION.containsMatchIn(it) && !genericFilename(it)
                        }
                    val actual = response.filename?.takeUnless(::genericFilename) ?: pathName ?: response.filename
                    actualName = actual ?: actualName
                    acceptedHeaders = response.requestHeaders
                    if (actual != null && !genericFilename(actual)) {
                        if (!nameMatches(row, expected, actual, strictName)) return verdict(false, "response_filename_mismatch")
                    }
                }
                val text = response.bytes.toString(Charsets.UTF_8).trim { it.isWhitespace() || it == '\uFEFF' }
                val lower = text.take(256).lowercase()
                if (response.type.contains("html") || response.type.contains("json") ||
                    lower.startsWith("<html") || lower.startsWith("<!doctype html") ||
                    lower.startsWith("{") || lower.startsWith("[")) return verdict(false, "non_media_response")
                if (text.startsWith("#EXTM3U")) {
                    val lines = text.lineSequence().map(String::trim).toList()
                    val children = lines.filter { it.isNotEmpty() && !it.startsWith("#") }
                    if (children.isEmpty()) return verdict(null, "playlist_empty")
                    val seen = visited + url
                    // A dead variant does not make every variant in a master playlist dead.
                    if (lines.any { it.startsWith("#EXT-X-STREAM-INF:") }) {
                        var unknown = false
                        for (child in children.take(3)) {
                            when (inspectURL(java.net.URI(response.finalURL).resolve(child).toString(), true, seen)) {
                                true -> return true
                                null -> unknown = true
                                false -> Unit
                            }
                        }
                        return if (unknown || children.size > 3) null else false
                    }
                    val key = lines.takeWhile { it != children.first() }.lastOrNull { it.startsWith("#EXT-X-KEY:") }
                        ?.takeUnless { it.contains("METHOD=NONE") }
                    if (key != null) {
                        if (!key.contains("METHOD=AES-128")) return null
                        val uri = Regex("URI=\"([^\"]+)\"").find(key)?.groupValues?.get(1) ?: return null
                        val keyResponse = mediaHttp.mediaPrefix(java.net.URI(response.finalURL).resolve(uri).toString(), resolved.headers, range = false)
                        if (keyResponse.code in setOf(401, 403, 404, 410, 451)) return verdict(false, "key_http_${keyResponse.code}")
                        if (keyResponse.code !in 200..299) return verdict(null, "key_http_${keyResponse.code}")
                        if (keyResponse.bytes.size != 16) return verdict(false, "invalid_playlist_key")
                    }
                    val map = lines.firstOrNull { it.startsWith("#EXT-X-MAP:") }
                        ?.let { Regex("URI=\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
                    if (map != null) {
                        val initResult = inspectURL(java.net.URI(response.finalURL).resolve(map).toString(), true, seen, key != null)
                        if (initResult != true) return initResult
                    }
                    val media = inspectURL(java.net.URI(response.finalURL).resolve(children.first()).toString(), true, seen, key != null)
                    if (media == true) {
                        finalURL = response.finalURL
                        acceptedHeaders = response.requestHeaders
                    }
                    return media
                }
                if (response.type.contains("mpegurl") || response.type.contains("dash+xml")) return null
                if (response.type.startsWith("text/") || response.type.startsWith("image/")) return verdict(false, "non_media_type")
                if (response.bytes.isEmpty()) return null
                val total = response.totalBytes
                if (!part && total != null && total > 0 && total < 20L * 1024 * 1024) {
                    val advertised = row.sizeBytes ?: resolved.sizeBytes
                    if ((advertised == null && !row.exactSource) || (advertised != null && total < advertised * 0.05)) return verdict(false, "unexpected_short_file")
                }
                if (!part && !row.exactSource && response.code == 200 &&
                    (expected?.isMovie == true || expected?.episode != null) &&
                    (total == null || total > 1_048_576L)) {
                    val offset = 1_048_576L
                    val seek = mediaHttp.mediaPrefix(response.finalURL,
                        resolved.headers.filterKeys { !it.equals("Range", ignoreCase = true) } +
                            ("Range" to "bytes=$offset-${offset + 63}"), range = false)
                    if (seek.code in setOf(401, 403, 404, 410, 451)) return verdict(false, "seek_http_${seek.code}")
                    if (seek.code !in 200..299) return verdict(null, "seek_http_${seek.code}")
                    if (seek.code != 206 || seek.rangeStart != offset || seek.bytes.isEmpty()) return verdict(false, "seek_range_unavailable")
                }
                // MIME is only a server claim. Unknown bytes stay unknown, never live proof.
                val media = (encrypted && response.bytes.size >= 16 && response.bytes.size % 16 == 0) || hasMediaPrefix(response.bytes)
                if (media && !part) actualSize = response.totalBytes?.takeIf { it > 0 } ?: actualSize
                return if (media) true else null
            }
            inspectURL(resolved.url, false, emptySet()).also {
                inspectionFinished = true
                reason = if (it == true) "media_prefix" else reason
            }
        } } catch (cancelled: CancellationException) {
            trace("stage=done result=cancelled elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
            throw cancelled
        }
        catch (error: Exception) {
            inspectionFinished = true
            verdict(null, "network_${error::class.java.simpleName}")
        }
        if (!inspectionFinished) reason = "timeout_${budget}ms"
        // The cache check already proved this exact file. A failed proxy can use the same cached
        // file through the owner's TorBox account, without acquiring an uncached torrent.
        if (result != true && allowNativeFallback && row.cachedHint.verified && row.cacheHash != null && torbox != null) {
            val native = torbox.resolve(row.copy(url = null, infoHash = row.cacheHash), expected?.season, expected?.episode)
            if (native is DebridOutcome.Success) {
                cache[key] = CachedResolve(native.resolved, clock())
                trace("stage=fallback service=TorBox")
                return finish(inspect(row, expected, strictName, allowNativeFallback = false, timeoutMillis = budget), "native_torbox_fallback")
            }
        }
        if (result == true && strictName && !verifiedHttpIdentity(row, expected, actualName)) result = verdict(false, "identity_unverified")
        if (result == true) {
            if (cache.size >= CACHE_MAX_ENTRIES) cache.clear()
            cache[key] = CachedResolve(resolved.copy(url = finalURL, filename = actualName, headers = acceptedHeaders, sizeBytes = actualSize), clock(), mediaChecked = true)
        } else if (result == false) {
            notePlaybackFailure(row, expected?.season, expected?.episode)
        }
        return finish(result, reason)
    }

    /** Return the locally checked route, never a phone-provided cache or readiness assertion. */
    suspend fun verifiedSource(source: StreamRow, expected: StreamExpectation?): StreamRow? {
        if (expected == null || expected.title.isBlank()) return null
        val row = source.copy(expectation = expected, exactSource = false)
        if (inspect(row, expected, strictName = true, timeoutMillis = MANUAL_MEDIA_INSPECTION_TIMEOUT_MILLIS) != true) return null
        val result = resolve(row, expected.season, expected.episode) as? DebridOutcome.Success ?: return null
        val file = result.resolved
        val actual = file.filename?.substringAfterLast('/')?.takeUnless(::genericFilename)
        if (actual == null && !(row.cachedHint.verified && row.verifiedCacheFile != null)) {
            diagnostics(row, expected, "stage=display_gate result=rejected reason=file_identity_unconfirmed")
            return null
        }
        val checked = row.withFileFacts(actual ?: row.filename, file.sizeBytes)
            .copy(url = file.url, headers = file.headers, identityVerified = true, linkCheckedAtMillis = clock(),
                validationSourceKey = cacheKey(row, expected.season, expected.episode))
        return checked.takeUnless { isUnavailable(it, expected.season, expected.episode) }
    }

    fun isUnavailable(row: StreamRow, season: Int?, episode: Int?): Boolean {
        for (key in listOfNotNull(cacheKey(row, season, episode), row.validationSourceKey, failedDeliveryKey(row, season, episode)).distinct()) {
            val at = unavailable[key] ?: continue
            if (clock() - at in 0..30_000L) return true
            unavailable.remove(key, at)
        }
        return false
    }

    fun notePlaybackFailure(row: StreamRow, season: Int?, episode: Int?) {
        val key = cacheKey(row, season, episode)
        cache.remove(key)
        if (unavailable.size >= CACHE_MAX_ENTRIES) unavailable.clear()
        unavailable[key] = clock()
        unavailable[failedDeliveryKey(row, season, episode)] = clock()
        row.validationSourceKey?.let { original -> cache.remove(original); unavailable[original] = clock() }
    }

    fun forget(row: StreamRow, season: Int?, episode: Int?) {
        val key = cacheKey(row, season, episode)
        cache.remove(key)
        unavailable.remove(key)
        unavailable.remove(failedDeliveryKey(row, season, episode))
        row.validationSourceKey?.let { original -> cache.remove(original); unavailable.remove(original) }
    }

    private fun failedDeliveryKey(row: StreamRow, season: Int?, episode: Int?): String =
        "delivery:${row.playbackKey()}:${row.expectation?.cacheIdentity.orEmpty()}:$season:$episode"

    private fun verifiedHttpIdentity(row: StreamRow, expected: StreamExpectation?, filename: String?): Boolean =
        !row.httpSource || row.exactSource || expected == null || expected.matches(
            if (filename.isNullOrBlank()) row else row.copy(filename = filename))

    private fun nameMatches(row: StreamRow, expected: StreamExpectation?, actual: String, strict: Boolean): Boolean {
        if (StreamFacts.isTheatricalRelease(actual) || expected?.contradictsFilename(actual) == true) return false
        if (row.exactSource) return true
        if (genericFilename(actual)) return true
        val actualIdentity = row.copy(releaseName = actual, filename = actual, identityNames = emptyList())
        val stated = row.filename?.substringBefore("|^|")
        // Add-ons can supply a torrent display name rather than the served file's exact spelling.
        if (stated != null && !genericFilename(stated) && !basename(stated).equals(basename(actual), ignoreCase = true) &&
            expected?.matches(actualIdentity) != true) return false
        return expected == null || if (strict) expected.matches(actualIdentity)
            else !expected.contradictsFilename(actual)
    }

    private fun basename(name: String) = name.replace('\\', '/').substringAfterLast('/')
    private fun hasMediaPrefix(bytes: ByteArray): Boolean {
        fun starts(vararg values: Int): Boolean = bytes.size >= values.size &&
            values.indices.all { (bytes[it].toInt() and 255) == values[it] }
        val marker = if (bytes.size >= 8) bytes.copyOfRange(4, 8).toString(Charsets.US_ASCII) else ""
        return marker in setOf("ftyp", "styp", "moof", "moov", "mdat", "sidx") ||
            starts(0x1a, 0x45, 0xdf, 0xa3) || listOf(188, 192, 204).any { stride ->
                listOf(0, 4).any { offset -> bytes.size > offset + stride * 2 &&
                    (0..2).all { bytes[offset + stride * it] == 0x47.toByte() } }
            } || starts(0, 0, 1, 0xba) ||
            starts(0x49, 0x44, 0x33) || starts(0x4f, 0x67, 0x67, 0x53) || starts(0x66, 0x4c, 0x61, 0x43) ||
            starts(0x52, 0x49, 0x46, 0x46) || starts(0x46, 0x4c, 0x56) ||
            (bytes.size >= 2 && (bytes[0].toInt() and 255) == 255 && (bytes[1].toInt() and 224) == 224)
    }
    private fun genericFilename(name: String): Boolean {
        val stem = basename(name).substringBeforeLast('.').lowercase()
        return stem in setOf("video", "stream", "file", "download", "index", "master", "playlist", "media", "content", "output", "play", "live") ||
            (stem.length >= 16 && stem.all { it in "0123456789abcdef-" })
    }

    private suspend fun attempt(row: StreamRow, season: Int?, episode: Int?, selectLargestVideo: Boolean): DebridOutcome {
        if (row.exactSource || (row.cachedHint.verified && row.deliveryService.equals("TorBox", true)))
            return torbox?.resolve(row, season, episode, selectLargestVideo) { detail ->
                diagnostics(row, row.expectation, "provider=torbox $detail")
            } ?: DebridOutcome.MissingApiKey
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
            if (reported == null || informativeness(outcome) > informativeness(reported)) reported = outcome
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
    private fun cacheKey(row: StreamRow, season: Int?, episode: Int?, selectLargestVideo: Boolean = false): String {
        if (!row.url.isNullOrBlank()) {
            val capability = row.id + "\n" + row.url + "\n" + row.releaseName + "\n$season:$episode\n" + row.expectation?.cacheIdentity.orEmpty() + "\n" +
                row.headers.toSortedMap().entries.joinToString("\n") { "${it.key}=${it.value}" }
            return "url:" + java.security.MessageDigest.getInstance("SHA-256").digest(capability.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
        return listOf(
        row.infoHash.orEmpty().lowercase(),
        row.expectation?.cacheIdentity.orEmpty(),
        row.v2Infohash.orEmpty().lowercase(),
        row.fileIdx?.toString().orEmpty(),
        row.torboxTorrentID?.toString().orEmpty(),
        row.torboxFileID?.toString().orEmpty(),
        row.exactSource.toString(),
        row.torboxLibraryFile.toString(),
        season?.toString().orEmpty(),
        episode?.toString().orEmpty(),
        selectLargestVideo.toString(),
    ).joinToString("|")
    }

    companion object {
        private val VIDEO_EXTENSION = Regex("(?i)\\.(mkv|mp4|m4v|mov|avi|webm|m2ts|ts)$")
        /** Long enough that backing out and pressing Play again is free. Short enough to expire. */
        const val MEDIA_INSPECTION_TIMEOUT_MILLIS: Long = 6_000L
        const val MANUAL_MEDIA_INSPECTION_TIMEOUT_MILLIS: Long = 30_000L
        const val CACHE_TTL_MILLIS: Long = 15 * 60 * 1_000L
        const val CACHE_MAX_ENTRIES: Int = 64
    }
}
