package com.fourseveneightnine.tv.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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
    private val inFlight = mutableMapOf<String, CompletableDeferred<Bitmap?>>()

    /** @param targetWidth the widest the bitmap ever needs to be — the view, not the source. */
    suspend fun load(url: String, targetWidth: Int): Bitmap? {
        val key = "$url@$targetWidth"
        cache.get(key)?.takeIf { !it.isRecycled }?.let { return it }

        // Join an identical request rather than starting a second one. Checked and inserted without
        // suspending in between, so two callers cannot both decide they are the first.
        val existing = inFlight[key]
        if (existing != null) return existing.await()
        val pending = CompletableDeferred<Bitmap?>()
        inFlight[key] = pending

        val bitmap = try {
            withContext(Dispatchers.IO) { fetch(tmdbSized(url, targetWidth), targetWidth) }
        } catch (error: Throwable) {
            ReceiverDiagnostics.record("artwork.failed", "${error::class.java.simpleName}: ${error.message}")
            null
        } finally {
            inFlight.remove(key)
        }
        if (bitmap != null) cache.put(key, bitmap)
        pending.complete(bitmap)
        return bitmap
    }

    fun clear() {
        cache.evictAll()
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

    private fun fetch(url: String, targetWidth: Int): Bitmap? = try {
        val bytes = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.bytes()
        }
        if (bytes == null || bytes.size > MAX_BYTES) {
            if (bytes != null) ReceiverDiagnostics.record("artwork.tooLarge", "${bytes.size}B")
            null
        } else {
            decodeSampled(bytes, targetWidth)
        }
    } catch (error: Throwable) {
        ReceiverDiagnostics.record("artwork.failed", "${error::class.java.simpleName}: ${error.message}")
        null
    }

    private fun decodeSampled(bytes: ByteArray, targetWidth: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (targetWidth > 0 && bounds.outWidth / sample > targetWidth * 2) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            // 16-bit is invisible behind a scrim at this size and halves the allocation.
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 8L
        const val READ_TIMEOUT_SECONDS = 12L

        /** A backdrop over 8MB is not a backdrop; refuse rather than decode it. */
        const val MAX_BYTES = 8 * 1024 * 1024

        val SIZE_SEGMENT = Regex("/t/p/(original|w\\d+|h\\d+)/")

        /**
         * An eighth of the heap. A full rail is ~13 thumbnails (RGB_565 at ~260px wide ≈ 200KB) plus
         * one panel-width backdrop (~4MB) — comfortably inside this on the smallest Fire TV, while
         * still leaving the video decoder the headroom it actually needs.
         */
        fun cacheBudgetBytes(): Int {
            val heap = Runtime.getRuntime().maxMemory()
            return (heap / 8).coerceIn(4L * 1024 * 1024, 24L * 1024 * 1024).toInt()
        }
    }
}
