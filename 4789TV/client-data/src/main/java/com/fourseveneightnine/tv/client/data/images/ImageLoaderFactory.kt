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
) {
    public fun toImageRequest(context: Context): ImageRequest = ImageRequest.Builder(context)
        .data(url?.let { TmdbSize.sized(it, width) })
        .size(Size(width, Dimension.Undefined))
        .apply {
            if (width <= TmdbSize.POSTER_WIDTH && ImageLoaderFactory.isLowRamDevice(context)) {
                bitmapConfig(Bitmap.Config.RGB_565)
            }
        }
        .crossfade(ImageLoaderFactory.CROSSFADE_MILLIS)
        .build()

    public companion object {
        public fun preview(url: String?): PosterRequest = PosterRequest(url, TmdbSize.POSTER_PREVIEW_WIDTH)
        public fun poster(url: String?): PosterRequest = PosterRequest(url, TmdbSize.POSTER_WIDTH)
        public fun wide(url: String?): PosterRequest = PosterRequest(url, TmdbSize.WIDE_WIDTH)
        public fun backdrop(url: String?): PosterRequest = PosterRequest(url, TmdbSize.BACKDROP_WIDTH)
    }
}
