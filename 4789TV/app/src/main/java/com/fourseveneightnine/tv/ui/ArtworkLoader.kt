package com.fourseveneightnine.tv.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetches and decodes the images the receiver draws: the full-screen backdrop and every poster on
 * the Continue-Watching rail.
 *
 * IT USED TO HOLD EXACTLY ONE BITMAP. That was right when the only image on this box was the
 * buffering backdrop, and catastrophic the moment a rail of thirteen posters started calling it:
 * every card evicted the previous card, so nothing was ever reused, and each D-pad press fired a
 * fresh full-size fetch + decode for the backdrop — which then evicted the thumbnails again, so the
 * next render re-fetched all thirteen. On an AFTDCT31 that showed up as `Skipped 172 frames` and a
 * remote that felt dead. A rail is not one image, so the cache is no longer one slot.
 *
 * Still deliberately not Coil/Glide: what actually matters on a low-RAM Fire TV is (a) decoding
 * BOUNDS first and subsampling so a 3840px TMDB source never becomes a 60MB ARGB_8888 allocation,
 * (b) not asking the network for pixels that get thrown away (`tmdbSized`), and (c) a bounded cache.
 * That is three short functions, not a dependency.
 *
 * A failure is not an error anyone needs to see: the caller simply leaves its view as it was.
 */
internal class ArtworkLoader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build(),
    /**
     * Where fetched (already TMDB-sized) bytes are kept between runs. Null disables the disk tier,
     * which is what the unit tests want.
     *
     * The memory cache dies with the process, and a TV process dies constantly: every trip back to
     * the launcher is a cold start, and the whole rail was being pulled over Wi-Fi again to draw
     * exactly the pixels it drew a minute earlier. That is most of "posters load slow" on a box
     * that is opened and closed all evening.
     */
    private val diskCacheDir: java.io.File? = null,
) {
    /**
     * Keyed by url AND target width: the same poster is legitimately wanted at rail-thumbnail size
     * and at panel size, and they are different bitmaps. Budgeted in BYTES, not entries — thirteen
     * thumbnails and one backdrop have wildly different costs and an entry count cannot express that.
     */
    private val cache = object : LruCache<String, Bitmap>(cacheBudgetBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * Requests already in flight, so the rail asking for thirteen posters at once — or the same
     * poster twice while the viewer moves and moves back — issues each HTTP call exactly once.
     */
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Bitmap?>>()

    /**
     * A LazyRow can compose ten cards at once. Limit decode/fetch work to four images so a cold
     * library opening does not allocate ten compressed byte buffers and bitmaps in one burst,
     * while still filling the visible rail quickly enough to avoid a screen of placeholders.
     */
    private val decodeSlots = Semaphore(permits = 4)

    /** Written bytes since the last disk trim, so trimming is amortised rather than per-write. */
    private val bytesSinceTrim = java.util.concurrent.atomic.AtomicLong(0)

    /** @param targetWidth the widest the bitmap ever needs to be — the view, not the source. */
    suspend fun load(url: String, targetWidth: Int, highQuality: Boolean = true): Bitmap? {
        val key = "$url@$targetWidth@$highQuality"
        synchronized(cache) {
            cache.get(key)
        }?.takeIf { !it.isRecycled }?.let { return it }

        // Join an identical request rather than starting a second one. Checked and inserted without
        // suspending in between, so two callers cannot both decide they are the first.
        val existing = inFlight[key]
        if (existing != null) return existing.await()
        val pending = CompletableDeferred<Bitmap?>()
        val raced = inFlight.putIfAbsent(key, pending)
        if (raced != null) return raced.await()

        val bitmap = try {
            loadWithRetry(key, tmdbSized(url, targetWidth), targetWidth, highQuality)
        } catch (error: Throwable) {
            ReceiverDiagnostics.record("artwork.failed", "${error::class.java.simpleName}: ${error.message}")
            null
        } finally {
            inFlight.remove(key, pending)
        }
        if (bitmap != null) {
            synchronized(cache) { cache.put(key, bitmap) }
        }
        pending.complete(bitmap)
        return bitmap
    }

    fun clear() {
        synchronized(cache) { cache.evictAll() }
    }

    /**
     * Ask TMDB for the size actually being drawn.
     *
     * The rows the phone sends carry whatever TMDB path the phone had — routinely `/original/` or
     * `/w780/`. Rendering one of those into a 130dp card means pulling a couple of megabytes over
     * Wi-Fi and decoding a 2000x3000 image to throw away 97% of it, thirteen times over. The
     * subsampling below already protects the heap; this protects the network and the CPU, which is
     * where the stall actually was.
     *
     * Untouched unless the URL is unmistakably a TMDB image path with a recognised size segment —
     * a URL this does not understand is passed through exactly as given.
     */
    internal fun tmdbSized(url: String, targetWidth: Int): String {
        if (!url.contains("image.tmdb.org/t/p/")) return url
        val wanted = when {
            targetWidth <= 0 -> return url
            targetWidth <= 185 -> "w185"
            targetWidth <= 342 -> "w342"
            targetWidth <= 500 -> "w500"
            targetWidth <= 780 -> "w780"
            else -> "original"
        }
        // Replace ONLY the size segment, keeping the rest of the path byte-for-byte.
        return SIZE_SEGMENT.replace(url) { "/t/p/$wanted/" }
    }

    /**
     * One retry, because the alternative is a permanently blank card.
     *
     * A miss here is not retried by anything above: the caller keeps whatever it already drew, and
     * nothing re-requests until that card is composed again. So a single dropped connection while
     * the box joins Wi-Fi used to mean that poster stayed empty for the rest of the session — the
     * "posters don't load at all" report. A second attempt costs one request on a path that has
     * already failed, and turns most of those permanent holes into a slightly late poster.
     *
     * Deliberately NOT retried: a well-formed refusal. A 404 or a too-large body means asking again
     * gets the same answer, so only transport failures and 5xx get the second attempt.
     */
    private suspend fun loadWithRetry(
        cacheKey: String,
        url: String,
        targetWidth: Int,
        highQuality: Boolean,
    ): Bitmap? {
        var attempt = 0
        while (true) {
            // The permit is taken per ATTEMPT, not for the whole retry. Holding one across the
            // backoff would idle a quarter of the decode capacity doing nothing, and on a cold rail
            // several failing posters would starve the ones that could have drawn immediately.
            val outcome = decodeSlots.withPermit {
                withContext(Dispatchers.IO) {
                    readDisk(cacheKey)?.let { cached ->
                        FetchOutcome.Body(cached, fromDisk = true)
                    } ?: attemptFetch(url)
                }
            }
            when (outcome) {
                is FetchOutcome.Body -> {
                    if (!outcome.fromDisk) writeDisk(cacheKey, outcome.bytes)
                    return withContext(Dispatchers.IO) { decodeSampled(outcome.bytes, targetWidth, highQuality) }
                }
                FetchOutcome.Permanent -> return null
                FetchOutcome.Transient -> {
                    if (attempt >= RETRY_ATTEMPTS) return null
                    attempt++
                    // Suspends rather than blocking: a sleeping thread here is an IO thread the
                    // other posters could have used.
                    kotlinx.coroutines.delay(RETRY_BACKOFF_MILLIS)
                }
            }
        }
    }

    private sealed interface FetchOutcome {
        data class Body(val bytes: ByteArray, val fromDisk: Boolean = false) : FetchOutcome
        /** Asking again would return the same thing. */
        data object Permanent : FetchOutcome
        /** Worth one more attempt. */
        data object Transient : FetchOutcome
    }

    private fun attemptFetch(url: String): FetchOutcome = try {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            when {
                response.code >= 500 -> FetchOutcome.Transient
                !response.isSuccessful -> FetchOutcome.Permanent
                else -> {
                    val bytes = response.body?.bytes()
                    when {
                        bytes == null -> FetchOutcome.Transient
                        bytes.size > MAX_BYTES -> {
                            ReceiverDiagnostics.record("artwork.tooLarge", "${bytes.size}B")
                            FetchOutcome.Permanent
                        }
                        else -> FetchOutcome.Body(bytes)
                    }
                }
            }
        }
    } catch (error: Throwable) {
        ReceiverDiagnostics.record("artwork.failed", "${error::class.java.simpleName}: ${error.message}")
        FetchOutcome.Transient
    }

    /**
     * Disk tier. Stores the COMPRESSED bytes as fetched, not the decoded bitmap: they are already
     * the right TMDB size (tens of KB), they survive a config change that wants a different
     * subsample, and they cost nothing to re-decode compared with pulling them over Wi-Fi again.
     */
    private fun readDisk(key: String): ByteArray? {
        val file = diskFile(key) ?: return null
        return try {
            if (!file.exists()) return null
            // Best-effort access stamp. On a device this returns false — Android's app-private
            // cache directory does not honour utime here (verified on an onn 4K Pro: mtimes were
            // unchanged after a restart that demonstrably served every poster from disk). So the
            // trim below degrades to oldest-WRITTEN rather than oldest-USED. That is acceptable
            // at this budget, which holds roughly 1,600 posters and so almost never evicts at all;
            // it is recorded here so nobody later reads the eviction order as access-based.
            file.setLastModified(System.currentTimeMillis())
            file.readBytes().takeIf { it.isNotEmpty() && it.size <= MAX_BYTES }
        } catch (error: Throwable) {
            null
        }
    }

    private fun writeDisk(key: String, bytes: ByteArray) {
        val file = diskFile(key) ?: return
        try {
            // Write beside then rename, so a process death mid-write cannot leave a truncated file
            // that every later run would happily decode into a broken poster.
            val staging = java.io.File(file.parentFile, "${file.name}.tmp")
            staging.writeBytes(bytes)
            if (!staging.renameTo(file)) staging.delete()
            // Trimming means stat-ing every file in the directory. At a 48MB budget of ~30KB
            // posters that is well over a thousand stats, and doing it after EVERY write turned a
            // cold rail into a directory scan per poster. Amortised: only once enough new bytes
            // have landed to plausibly matter.
            if (bytesSinceTrim.addAndGet(bytes.size.toLong()) >= TRIM_INTERVAL_BYTES) {
                bytesSinceTrim.set(0)
                trimDisk()
            }
        } catch (error: Throwable) {
            ReceiverDiagnostics.record("artwork.diskWriteFailed", error::class.java.simpleName)
        }
    }

    private fun diskFile(key: String): java.io.File? {
        val dir = diskCacheDir ?: return null
        if (!dir.exists() && !dir.mkdirs()) return null
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return java.io.File(dir, digest)
    }

    /** Oldest-first eviction once the directory exceeds its budget. Cheap: it only runs after a write. */
    private fun trimDisk() {
        val dir = diskCacheDir ?: return
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= DISK_BUDGET_BYTES) return
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= DISK_BUDGET_BYTES) return
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private fun decodeSampled(bytes: ByteArray, targetWidth: Int, highQuality: Boolean): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (targetWidth > 0 && bounds.outWidth / sample > targetWidth * 2) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            // 16-bit halves the allocation, and behind a dimmed scrim nobody can tell. On a
            // poster shown at full brightness on a large panel they absolutely can: 565 gives
            // five bits of red and blue, which bands visibly across the gradients posters are
            // full of, and flattens saturated colour. It was being applied to EVERY image,
            // which is the single biggest reason our art looked duller than other clients'.
            // Full depth for anything the viewer actually looks at; 565 stays for the wash.
            inPreferredConfig = if (highQuality) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 8L
        const val READ_TIMEOUT_SECONDS = 12L

        /** A backdrop over 8MB is not a backdrop; refuse rather than decode it. */
        const val MAX_BYTES = 8 * 1024 * 1024

        const val RETRY_ATTEMPTS = 1
        const val RETRY_BACKOFF_MILLIS = 400L

        /**
         * Roughly a few hundred rail thumbnails at w185. Small enough that no TV owner notices it
         * on a device with a handful of GB, large enough that a normal evening never re-downloads.
         */
        const val DISK_BUDGET_BYTES = 48L * 1024 * 1024

        /** Roughly one trim per 150-ish posters written, instead of one per poster. */
        const val TRIM_INTERVAL_BYTES = 4L * 1024 * 1024

        val SIZE_SEGMENT = Regex("/t/p/(original|w\\d+|h\\d+)/")

        /**
         * Keep a complete visible rail and one backdrop resident without holding every historical
         * poster. The previous 24 MB ceiling let cold-start decode bursts retain enough native
         * memory to trigger long concurrent GCs while the remote was moving.
         */
        fun cacheBudgetBytes(): Int {
            val heap = Runtime.getRuntime().maxMemory()
            // Raised with the move to full-depth decoding: the same rail now costs twice the
            // bytes, and a cache too small to hold one visible rail thrashes, which is worse for
            // both memory and jank than simply holding it.
            return (heap / 6).coerceIn(8L * 1024 * 1024, 24L * 1024 * 1024).toInt()
        }
    }
}
