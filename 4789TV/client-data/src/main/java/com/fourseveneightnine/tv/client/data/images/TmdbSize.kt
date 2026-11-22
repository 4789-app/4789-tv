package com.fourseveneightnine.tv.client.data.images

import coil3.Extras
import coil3.getExtra
import coil3.intercept.Interceptor
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.size.pxOrElse

/**
 * Ask TMDB for the size actually being drawn.
 *
 * The rows the phone and the catalog server send carry whatever TMDB path they had — routinely
 * `/original/` or `/w780/`. Drawing one of those into a 236 px card pulls a couple of megabytes
 * over Wi-Fi and decodes a 2000×3000 image to throw away 97% of it, thirteen times per rail. The
 * card's own size already protects the heap; this protects the network and the CPU, which is where
 * the stall actually was.
 *
 * A URL this does not recognise as a TMDB image path is passed through byte for byte.
 */
public object TmdbSize {
    /** Small overlapping preview inside a 96 px-wide collection folder. */
    public const val POSTER_PREVIEW_WIDTH: Int = 185

    /** 2:3 poster card, 236 px drawn. */
    public const val POSTER_WIDTH: Int = 342

    /** 16:9 wide card, 300 px drawn. */
    public const val WIDE_WIDTH: Int = 780

    /** Full-bleed hero backdrop. */
    public const val BACKDROP_WIDTH: Int = 1280

    private const val TMDB_IMAGE_PATH = "image.tmdb.org/t/p/"
    private val sizeSegment = Regex("/t/p/[^/]+/")
    internal val logoKey = Extras.Key(default = false)
    internal val posterKey = Extras.Key(default = false)

    /** Logos have a different size ladder from posters and backdrops. */
    public fun logo(url: String, targetWidth: Int): String {
        if (!isTmdbImage(url) || targetWidth <= 0) return url
        val wanted = when {
            targetWidth <= 185 -> "w185"
            targetWidth <= 300 -> "w300"
            targetWidth <= 500 -> "w500"
            else -> "original"
        }
        return sizeSegment.replaceFirst(url, "/t/p/$wanted/")
    }

    /** Portrait artwork uses the poster ladder, which has no w1280 rung. */
    public fun poster(url: String, targetWidth: Int): String =
        if (targetWidth > WIDE_WIDTH && isTmdbImage(url)) sizeSegment.replaceFirst(url, "/t/p/original/")
        else sized(url, targetWidth)

    /**
     * Rewrites only the size segment.
     *
     * `w1280` exists for backdrops and stills, not for posters, so [BACKDROP_WIDTH] belongs to
     * backdrop URLs only. Posters ask for [POSTER_WIDTH] or [WIDE_WIDTH].
     */
    public fun sized(url: String, targetWidth: Int): String {
        if (!isTmdbImage(url)) return url
        val wanted = when {
            targetWidth <= 0 -> return url
            targetWidth <= 185 -> "w185"
            targetWidth <= 342 -> "w342"
            targetWidth <= 500 -> "w500"
            targetWidth <= 780 -> "w780"
            targetWidth <= 1280 -> "w1280"
            else -> "original"
        }
        return sizeSegment.replaceFirst(url, "/t/p/$wanted/")
    }

    private fun isTmdbImage(url: String): Boolean =
        url.startsWith("https://$TMDB_IMAGE_PATH") || url.startsWith("http://$TMDB_IMAGE_PATH")
}

private const val METAHUB_POSTER_PATH = "https://images.metahub.space/poster/"
private val metahubPosterSize =
    Regex("^https://images[.]metahub[.]space/poster/(?:small|medium|large)/(?=tt[0-9]+/img(?:[?#]|$))")

/** Upgrade known MetaHub thumbnails for large cards while preserving the IMDb ID and URL suffix. */
internal fun sizedPosterUrl(url: String, targetWidth: Int): String {
    if (targetWidth > TmdbSize.POSTER_WIDTH && url.startsWith(METAHUB_POSTER_PATH)) {
        val size = if (targetWidth <= 500) "medium" else "large"
        return metahubPosterSize.replaceFirst(url, "$METAHUB_POSTER_PATH$size/")
    }
    return TmdbSize.poster(url, targetWidth)
}

/** Keep the logo's size role when Coil resolves the physical display constraints. */
public fun ImageRequest.Builder.tmdbLogo(): ImageRequest.Builder = apply {
    extras[TmdbSize.logoKey] = true
}

/**
 * Applies [TmdbSize.sized] to every request, using the width Coil resolved for the target. A
 * request that already names its size still passes through here, so a card that changes size gets
 * the right bytes without the call site remembering to ask.
 */
public class TmdbSizeInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val data = chain.request.data
        if (data !is String) return chain.proceed()
        val width = chain.size.width.pxOrElse { 0 }
        val rewritten = when {
            chain.request.getExtra(TmdbSize.logoKey) -> TmdbSize.logo(data, width)
            chain.request.getExtra(TmdbSize.posterKey) -> sizedPosterUrl(data, width)
            else -> TmdbSize.sized(data, width)
        }
        if (rewritten == data) return chain.proceed()
        return chain.withRequest(chain.request.newBuilder().data(rewritten).build()).proceed()
    }
}
