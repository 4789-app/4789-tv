package com.fourseveneightnine.tv.client.data

import android.content.Context
import org.json.JSONObject

/** Legacy recent-row shape retained for metadata policies; active progress lives in Room. */
data class RecentItem(
    val url: String,
    val title: String,
    val subtitle: String? = null,
    val posterUrl: String? = null,
    val positionMillis: Long = 0L,
    val durationMillis: Long = 0L,
    val audioTrackIndex: Int? = null,
    val subtitleTrackIndex: Int? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val fromPhone: Boolean = false,
    /** Optional landscape art and real synopsis for the Continue hero; portrait stays in [posterUrl]. */
    val backdropUrl: String? = null,
    val overview: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("url", url)
        put("title", title)
        put("subtitle", subtitle ?: "")
        put("posterUrl", posterUrl ?: "")
        put("backdropUrl", backdropUrl ?: "")
        put("overview", overview ?: "")
        put("positionMillis", positionMillis)
        put("durationMillis", durationMillis)
        audioTrackIndex?.let { put("audioTrackIndex", it) }
        subtitleTrackIndex?.let { put("subtitleTrackIndex", it) }
        put("timestamp", timestamp)
        put("fromPhone", fromPhone)
    }

    val dedupeKey: String
        get() = url.ifBlank { title.lowercase() }

    /** Canonical key for robust deduplication regardless of title formatting or release year. */
    val canonicalKey: String
        get() {
            val cleanTitle = title.lowercase()
                .replace(Regex("[^a-z0-9]"), " ")
                .replace(Regex("\\b(19|20)\\d{2}\\b"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
            return cleanTitle.ifBlank { dedupeKey }
        }

    companion object {
        fun fromJson(json: JSONObject): RecentItem = RecentItem(
            url = json.optString("url"),
            title = json.optString("title"),
            subtitle = json.optString("subtitle").takeIf { it.isNotBlank() },
            posterUrl = json.optString("posterUrl").takeIf { it.isNotBlank() },
            positionMillis = json.optLong("positionMillis", 0L),
            durationMillis = json.optLong("durationMillis", 0L),
            audioTrackIndex = if (json.has("audioTrackIndex")) json.optInt("audioTrackIndex") else null,
            subtitleTrackIndex = if (json.has("subtitleTrackIndex")) json.optInt("subtitleTrackIndex") else null,
            timestamp = json.optLong("timestamp", System.currentTimeMillis()),
            fromPhone = json.optBoolean("fromPhone", false),
            backdropUrl = json.optString("backdropUrl").takeIf { it.isNotBlank() },
            overview = json.optString("overview").takeIf { it.isNotBlank() },
        )
    }
}

/** Phone metadata enriches a matching local playback row without replacing its receiver truth. */
internal fun enrichLocalRecent(local: RecentItem, phone: RecentItem): RecentItem = local.copy(
    subtitle = local.subtitle ?: phone.subtitle,
    posterUrl = local.posterUrl ?: phone.posterUrl,
    backdropUrl = local.backdropUrl ?: phone.backdropUrl,
    overview = local.overview ?: phone.overview,
)

/**
 * The phone protocol orders recents newest first. Keep the first row for every canonical title so
 * an older duplicate cannot replace richer artwork or overview metadata on a local playback row.
 */
internal fun newestPhoneRecentsByCanonicalKey(items: List<RecentItem>): Map<String, RecentItem> =
    LinkedHashMap<String, RecentItem>().also { newestByKey ->
        items.asSequence()
            .filter { it.title.isNotBlank() }
            .forEach { item -> newestByKey.putIfAbsent(item.canonicalKey, item) }
    }

/** Remove the unused plaintext playback-URL cache from earlier receiver versions. */
internal fun purgeLegacyRecents(context: Context): Boolean =
    context.applicationContext.deleteSharedPreferences("tv_recents_v1")
