package com.fourseveneightnine.tv.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * One tile on the home rail.
 *
 * [fromPhone] records WHERE the row came from, and it is the only reason this class is not a plain
 * bag of strings. Rows the box played itself are its own truth and must survive; rows mirrored from
 * the phone are a CACHE of somebody else's list and are replaced wholesale on every push (see
 * [RecentsStore.replacePhoneRecents]) — otherwise a title the user removed on the phone would live
 * on the TV forever, and the two lists would drift apart with no way back.
 */
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

/**
 * The home screen's "Continue watching" rail.
 */
class RecentsStore(context: Context) {
    private val prefs = context.getSharedPreferences("tv_recents_v1", Context.MODE_PRIVATE)

    fun getRecents(): List<RecentItem> {
        val raw = prefs.getString("items", null) ?: return emptyList()
        val list = mutableListOf<RecentItem>()
        runCatching {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                list.add(RecentItem.fromJson(array.getJSONObject(i)))
            }
        }
        return deduplicate(list.filterNot { it.url.contains(DEMO_URL_MARKER) })
    }

    fun push(item: RecentItem) {
        if (item.url.isBlank() || item.title.isBlank()) return
        val current = getRecents().toMutableList()
        current.removeAll { it.canonicalKey == item.canonicalKey || it.dedupeKey == item.dedupeKey }
        current.add(0, item)
        write(current)
    }

    fun replacePhoneRecents(items: List<RecentItem>) {
        val local = getRecents().filterNot { it.fromPhone }
        val phoneByCanonicalKey = newestPhoneRecentsByCanonicalKey(items)
        val enrichedLocal = local.map { localItem ->
            phoneByCanonicalKey[localItem.canonicalKey]
                ?.let { phoneItem -> enrichLocalRecent(localItem, phoneItem) }
                ?: localItem
        }
        val localKeys = local.map { it.canonicalKey }.toSet()
        val mirrored = items
            .filter { it.title.isNotBlank() }
            .filterNot { it.canonicalKey in localKeys }
            .map { it.copy(fromPhone = true) }
        write((enrichedLocal + mirrored).sortedByDescending { it.timestamp })
    }

    fun updatePosition(url: String, positionMillis: Long, durationMillis: Long) {
        val current = getRecents().toMutableList()
        val index = current.indexOfFirst { it.url == url }
        if (index < 0) return
        val updated = current.removeAt(index).copy(
            positionMillis = positionMillis,
            durationMillis = durationMillis,
            timestamp = System.currentTimeMillis(),
        )
        current.add(0, updated)
        write(current)
    }

    fun updateTrackSelection(url: String, audioTrackIndex: Int?, subtitleTrackIndex: Int?) {
        val current = getRecents().toMutableList()
        val index = current.indexOfFirst { it.url == url }
        if (index < 0) return
        val updated = current.removeAt(index).copy(
            audioTrackIndex = audioTrackIndex,
            subtitleTrackIndex = subtitleTrackIndex,
            timestamp = System.currentTimeMillis(),
        )
        current.add(0, updated)
        write(current)
    }

    private fun deduplicate(items: List<RecentItem>): List<RecentItem> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<RecentItem>()
        for (item in items) {
            if (seen.add(item.canonicalKey)) {
                result.add(item)
            }
        }
        return result
    }

    private fun write(items: List<RecentItem>) {
        val array = JSONArray()
        deduplicate(items).take(MAX_ITEMS).forEach { array.put(it.toJson()) }
        prefs.edit().putString("items", array.toString()).apply()
    }

    private companion object {
        const val MAX_ITEMS = 20
        /** Every fabricated row the old seed wrote pointed at this bucket. */
        const val DEMO_URL_MARKER = "gtv-videos-bucket/sample"
    }
}
