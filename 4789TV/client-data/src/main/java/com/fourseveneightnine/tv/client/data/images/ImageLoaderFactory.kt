package com.fourseveneightnine.tv.client.data.images

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import androidx.core.content.getSystemService
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.bitmapConfig
import coil3.request.crossfade
import coil3.size.Dimension
import coil3.size.Size
import java.io.File
import com.fourseveneightnine.tv.client.data.addons.getText
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath

/**
 * The one Coil loader for the TV app.
 *
 * Three rules do most of the work: a real disk cache so a trip back to the launcher does not repull
 * the whole rail, a shared memory cache for decoded posters, and
 * [TmdbSizeInterceptor] so nothing pulls a 2000 px poster for a 236 px card.
 */
public object ImageLoaderFactory {
    /** 256 MB of poster bytes on disk. */
    public const val DISK_CACHE_BYTES: Long = 256L * 1024 * 1024

    /**
     * 30% of the app's heap. Two Home rows hold ~100 posters at 342 x 513 ARGB (about 700 KB
     * each); at 15% the cache held 80 and a row swept twice re-decoded from disk and faded in
     * again. The AFTDCT31 keeps its smaller share through [LOW_RAM_MEMORY_CACHE_PERCENT].
     */
    public const val MEMORY_CACHE_PERCENT: Double = 0.30
    public const val LOW_RAM_MEMORY_CACHE_PERCENT: Double = 0.15

    /** Spec §3.6: 190 ms, Std easing. */
    public const val CROSSFADE_MILLIS: Int = 190

    public fun create(context: Context, okHttp: OkHttpClient, cacheDir: File): ImageLoader =
        ImageLoader.Builder(context)
            // Bound concurrent bitmap allocations during fast scrolling and artwork recovery.
            .decoderCoroutineContext(Dispatchers.IO.limitedParallelism(2))
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, if (isLowRamDevice(context)) LOW_RAM_MEMORY_CACHE_PERCENT else MEMORY_CACHE_PERCENT)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.toOkioPath())
                    .maxSizeBytes(DISK_CACHE_BYTES)
                    .build()
            }
            .components {
                add(TmdbSizeInterceptor())
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { okHttp },
                    ),
                )
            }
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .crossfade(CROSSFADE_MILLIS)
            .build()

    /** True on a box whose RAM makes a 32-bit bitmap per card a bad trade. */
    public fun isLowRamDevice(context: Context): Boolean =
        context.getSystemService<ActivityManager>()?.isLowRamDevice == true
}

/**
 * One artwork request, sized to the card that will draw it.
 *
 * On a low-RAM box a poster drops to `RGB_565`, which halves the bitmap. Backdrops keep
 * `ARGB_8888`: they are the one image on screen and 565 banding is visible across a gradient at
 * 3 m, where a poster's is not.
 */
public data class PosterRequest(
    public val url: String?,
    public val width: Int,
    private val portrait: Boolean = false,
) {
    public fun toImageRequest(context: Context): ImageRequest = ImageRequest.Builder(context)
        .data(url?.let { if (portrait) sizedPosterUrl(it, width) else TmdbSize.sized(it, width) })
        .size(Size(width, Dimension.Undefined))
        .apply {
            if (portrait) extras[TmdbSize.posterKey] = true
            if (width <= TmdbSize.POSTER_WIDTH && ImageLoaderFactory.isLowRamDevice(context)) {
                bitmapConfig(Bitmap.Config.RGB_565)
            }
        }
        .crossfade(ImageLoaderFactory.CROSSFADE_MILLIS)
        .build()

    public companion object {
        public fun preview(url: String?): PosterRequest = PosterRequest(url, TmdbSize.POSTER_PREVIEW_WIDTH)
        public fun poster(url: String?, widthPixels: Int = TmdbSize.POSTER_WIDTH): PosterRequest =
            PosterRequest(url, widthPixels, portrait = true)
        public fun wide(url: String?): PosterRequest = PosterRequest(url, TmdbSize.WIDE_WIDTH)
        public fun backdrop(url: String?, widthPixels: Int = TmdbSize.BACKDROP_WIDTH): PosterRequest =
            PosterRequest(url, widthPixels)
    }
}

/** Fanart.tv is queried only for artwork recovery and only by a known title ID. */
public class FanartPosters(okHttp: OkHttpClient, private val personalKey: String) {
    private val http = okHttp.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(4, TimeUnit.SECONDS).build()
    private val cache = LinkedHashMap<String, Pair<Long, List<String>>>(32, 0.75f, true)

    public suspend fun posters(type: String, id: String, tmdbID: Int?): List<String> {
        val resource = when (type) {
            "movie" -> "movies"
            "series" -> "tv"
            else -> return emptyList()
        }
        val knownID = tmdbID?.takeIf { it > 0 }
            ?: id.takeIf { it.startsWith("tmdb:") }?.substringAfter(':')?.toIntOrNull()?.takeIf { it > 0 }
        val imdb = id.takeIf { IMDbID.matches(it) }
        val identifier = knownID?.toString() ?: imdb ?: return emptyList()
        val source = if (knownID == null) "imdb" else "tmdb"
        val cacheKey = "$resource/$source/$identifier"
        val now = System.currentTimeMillis()
        synchronized(cache) {
            cache[cacheKey]?.takeIf { now - it.first < 15 * 60_000L }?.let { return it.second }
        }
        val url = "https://webservice.fanart.tv/v3.2".toHttpUrl().newBuilder()
            .addPathSegment(resource).addPathSegment(identifier).addQueryParameter("source", source).build()
        val candidates = try {
            val response = http.getText(url.toString(), 512 * 1024, headers = mapOf("client-key" to personalKey))
            val root = Json.parseToJsonElement(response.body) as? JsonObject
            parse((root?.get(if (type == "movie") "movieposter" else "tvposter") as? JsonArray).orEmpty())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
        synchronized(cache) {
            cache[cacheKey] = now to candidates
            if (cache.size > 128) cache.remove(cache.keys.first())
        }
        return candidates
    }

    private fun parse(rows: List<kotlinx.serialization.json.JsonElement>): List<String> {
        fun JsonObject.field(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
        return rows.mapNotNull { it as? JsonObject }
            .sortedWith(compareBy<JsonObject> {
                when (it.field("lang")?.lowercase()) { "en" -> 0; null, "", "00" -> 1; else -> 2 }
            }.thenByDescending { it.field("likes")?.toIntOrNull() ?: 0 })
            .mapNotNull { row ->
                row.field("url")?.toHttpUrlOrNull()?.takeIf {
                    it.isHttps && it.host == "assets.fanart.tv" && it.encodedPath.startsWith("/fanart/")
                }?.toString()
            }.distinct().take(5)
    }

    private companion object {
        val IMDbID = Regex("tt[0-9]{1,16}")
    }
}
