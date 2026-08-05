package com.fourseveneightnine.tv.player

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
) {
    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun decode(raw: String?): ResumePoint? = raw
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() }
            ?.takeIf { it.url.isNotBlank() }
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

    /** Worth writing at all? Same position rules, without the age test. */
    fun shouldStore(positionMillis: Long, durationMillis: Long): Boolean {
        if (positionMillis < MIN_POSITION_MILLIS) return false
        if (durationMillis > 0 && positionMillis > durationMillis - END_MARGIN_MILLIS) return false
        return true
    }
}
