package com.fourseveneightnine.tv.player

import com.fourseveneightnine.tv.protocol.NextUpItem
import com.fourseveneightnine.tv.protocol.OpenMediaRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Where the last cast had got to when this receiver lost the screen.
 *
 * Leaving the app tears everything down — surface, discovery, player — so reopening used to land
 * on a blank "Ready for your iPhone" with no trace of the film that was playing thirty seconds
 * earlier. This is the crumb that makes the reopen offer to pick it up again.
 */
@Serializable
data class ResumePoint(
    val url: String,
    val title: String? = null,
    val subtitle: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val positionMillis: Long = 0,
    val durationMillis: Long = 0,
    val savedAtMillis: Long = 0,
    val artworkURL: String? = null,
    val posterURL: String? = null,
    val logoURL: String? = null,
    val origin: String? = null,
    val profileId: String? = null,
    val catalogType: String? = null,
    val catalogId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val iptvVodId: String? = null,
    val castId: String? = null,
    val chapterSeconds: List<Double> = emptyList(),
    val isLive: Boolean = false,
    val paused: Boolean = false,
    val nextEpisode: SavedNextEpisode? = null,
    val watchlistId: String? = null,
    val catalogFile: CatalogFile? = null,
    /** Renewable identity of the selected file, independent of its expiring playback link. */
    val sourceRecipe: String? = null,
) {
    @Serializable
    data class CatalogFile(val filename: String?, val sizeBytes: Long?)

    @Serializable
    data class SavedNextEpisode(
        val url: String,
        val title: String?,
        val subtitle: String?,
        val posterUrl: String?,
        val headers: Map<String, String>,
    ) {
        internal constructor(item: NextUpItem) : this(item.url, item.title, item.subtitle, item.posterUrl, item.headers)
        internal fun mediaItem() = NextUpItem(url, title, subtitle, posterUrl, headers)
    }

    internal fun mediaRequest() = OpenMediaRequest(
        url = url, title = title, subtitle = subtitle, headers = headers, castId = castId,
        isLive = isLive, chapterSeconds = chapterSeconds, nextUp = nextEpisode?.mediaItem(),
        startPositionMs = positionMillis.takeIf { !isLive && it > 0L },
    )

    fun encode(): String {
        val stored = if (watchlistId == null) this else copy(url = "", headers = emptyMap(), nextEpisode = null, catalogFile = null)
        return JSON.encodeToString(serializer(), stored)
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun decode(raw: String?): ResumePoint? = raw
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() }
            ?.takeIf { it.url.isNotBlank() || (it.origin == "local" && it.catalogId != null && it.watchlistId != null) }
    }
}

/**
 * Whether a stored crumb is still worth offering.
 *
 * The TTL is not about disk: it is about the URL. A cast link is usually a short-lived debrid or
 * CDN bearer URL, so a day-old resume would reliably open into a 403 that the viewer reads as "the
 * app is broken". Two hours keeps "I closed it by accident" working and quietly forgets the rest.
 * Positions inside the first minute are not worth an offer — restarting is the same thing.
 */
internal object ResumePolicy {
    const val TTL_MILLIS = 2 * 60 * 60 * 1000L
    const val MIN_POSITION_MILLIS = 60_000L

    /** Do not offer to resume the last 90 seconds — that is the credits, and the title is over. */
    const val END_MARGIN_MILLIS = 90_000L

    fun shouldOffer(point: ResumePoint?, nowMillis: Long, ttlMillis: Long = TTL_MILLIS): Boolean {
        if (point == null || point.url.isBlank()) return false
        val age = nowMillis - point.savedAtMillis
        if (age < 0 || age > ttlMillis) return false
        if (point.positionMillis < MIN_POSITION_MILLIS) return false
        if (point.durationMillis > 0 &&
            point.positionMillis > point.durationMillis - END_MARGIN_MILLIS
        ) {
            return false
        }
        return true
    }

    /** Only sessions captured by the lifecycle handoff are eligible for automatic restore. */
    fun belongsTo(point: ResumePoint, profileId: String?): Boolean = when (point.origin) {
        "phone" -> true
        "local" -> point.profileId == null || point.profileId == profileId
        else -> false
    }

    fun linkIsRecent(point: ResumePoint, nowMillis: Long): Boolean =
        point.url.startsWith("file:") || nowMillis - point.savedAtMillis in 0..TTL_MILLIS

    /** Worth writing at all? Same position rules, without the age test. */
    fun shouldStore(positionMillis: Long, durationMillis: Long): Boolean {
        if (positionMillis < MIN_POSITION_MILLIS) return false
        if (durationMillis > 0 && positionMillis > durationMillis - END_MARGIN_MILLIS) return false
        return true
    }

    /** The home-screen Resume button owns a TV-local open/seek sequence. Notify the phone only
     * after every step succeeded; an expired link or dropped seek must not present a false remote. */
    fun shouldNotifyPhoneAfterStoredResume(
        opened: Boolean,
        landed: Boolean,
        seekSucceeded: Boolean,
    ): Boolean = opened && landed && seekSucceeded

    /** A saved offer outranks the previous engine's Stopped phase once startup is ready. */
    fun shouldRenderStoredOffer(hasOffer: Boolean, startupReady: Boolean): Boolean =
        hasOffer && startupReady
}
