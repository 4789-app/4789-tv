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
    val timestamp: Long = System.currentTimeMillis(),
    val fromPhone: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("url", url)
        put("title", title)
        put("subtitle", subtitle ?: "")
        put("posterUrl", posterUrl ?: "")
        put("positionMillis", positionMillis)
        put("durationMillis", durationMillis)
        put("timestamp", timestamp)
        put("fromPhone", fromPhone)
    }

    /** Identity for de-duplication: the stream URL when there is one, else the title. */
    val dedupeKey: String
        get() = url.ifBlank { title.lowercase() }

    companion object {
        fun fromJson(json: JSONObject): RecentItem = RecentItem(
            url = json.optString("url"),
            title = json.optString("title"),
            subtitle = json.optString("subtitle").takeIf { it.isNotBlank() },
            posterUrl = json.optString("posterUrl").takeIf { it.isNotBlank() },
            positionMillis = json.optLong("positionMillis", 0L),
            durationMillis = json.optLong("durationMillis", 0L),
            timestamp = json.optLong("timestamp", System.currentTimeMillis()),
            fromPhone = json.optBoolean("fromPhone", false),
        )
    }
}

/**
 * The home screen's "Continue watching" rail.
 *
 * NO DEMO SEED. This store used to ship two fabricated rows — real film titles, TMDB poster paths
 * that 404, and Big Buck Bunny behind both of them. On a fresh box that is not an empty state, it is
 * a LIE the user can tap: two films they never watched, which then play a rabbit. An empty rail that
 * fills the first time you cast is the honest version, and the phone push below is what shortens
 * the wait to zero.
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
        // A blob written by the pre-seed build can still hold the two fabricated rows. Filtered on
        // read rather than migrated: they are identifiable by their sample URLs, and the worst case
        // of being wrong is one real row lost — versus a fake row the user can tap forever.
        return list.filterNot { it.url.contains(DEMO_URL_MARKER) }
    }

    /**
     * Record something this box played. A local play outranks a mirrored row for the same title —
     * `push` writes `fromPhone = false`, so a later phone push can no longer replace it.
     */
    fun push(item: RecentItem) {
        if (item.url.isBlank() || item.title.isBlank()) return
        val current = getRecents().toMutableList()
        current.removeAll { it.dedupeKey == item.dedupeKey || it.title == item.title }
        current.add(0, item)
        write(current)
    }

    /**
     * Replace every phone-mirrored row with [items], keeping this box's own plays.
     *
     * WHOLESALE, NOT MERGE: the phone's list is authoritative about itself. If the user cleared a
     * title there, a merge would strand it on the TV with no way to remove it — the box has no
     * delete UI, and the rail is not a place you go to manage anything.
     *
     * A phone row is DROPPED when the box already has its own row for the same title: the box knows
     * a real resume position for that stream, and the phone's is at best a second opinion.
     */
    fun replacePhoneRecents(items: List<RecentItem>) {
        val local = getRecents().filterNot { it.fromPhone }
        val localKeys = local.map { it.dedupeKey }.toSet()
        val localTitles = local.map { it.title.lowercase() }.toSet()
        val mirrored = items
            .filter { it.title.isNotBlank() }
            .filterNot { it.dedupeKey in localKeys || it.title.lowercase() in localTitles }
            .map { it.copy(fromPhone = true) }
        write((local + mirrored).sortedByDescending { it.timestamp })
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

    private fun write(items: List<RecentItem>) {
        val array = JSONArray()
        items.take(MAX_ITEMS).forEach { array.put(it.toJson()) }
        prefs.edit().putString("items", array.toString()).apply()
    }

    private companion object {
        const val MAX_ITEMS = 20
        /** Every fabricated row the old seed wrote pointed at this bucket. */
        const val DEMO_URL_MARKER = "gtv-videos-bucket/sample"
    }
}
