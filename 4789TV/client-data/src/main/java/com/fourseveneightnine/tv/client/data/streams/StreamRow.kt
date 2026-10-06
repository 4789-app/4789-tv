package com.fourseveneightnine.tv.client.data.streams

import com.fourseveneightnine.contract.StreamEntry

/** High dynamic range, as far as a release name states it. */
enum class HdrFormat { NONE, HDR10, DOLBY_VISION }

/**
 * Whether a row plays right now.
 *
 * A HINT, and the name says so. Real-Debrid removed its instant-availability endpoint in 2024, so
 * there is no probe left that can answer this honestly. What we have is what the add-on claims —
 * AIOStreams' "⚡", Torrentio's `[RD+]`, MediaFusion's cached flag — and a claim is all this is.
 */
data class CachedHint(
    val cached: Boolean,
    /** "Real-Debrid", "TorBox", … when the marker named one. */
    val service: String? = null,
)

/** One row in the Streams list (`TV_DESIGN_SPEC.md` §10.3). */
data class StreamRow(
    /** Stable for the life of a search, so live arrival can keep focus by id (§10.7). */
    val id: String,
    val addonName: String,
    /** The short label line 1 draws. */
    val title: String,
    /** The full release name line 2 draws, middle-ellipsised by the UI. */
    val releaseName: String,
    val url: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val quality: String? = null,
    val sizeBytes: Long? = null,
    val codecs: List<String> = emptyList(),
    val hdr: HdrFormat = HdrFormat.NONE,
    val audioLanguages: List<String> = emptyList(),
    val cachedHint: CachedHint = CachedHint(false),
    val seeders: Int? = null,
    /** Proxy headers the add-on asked for. Passed to the player untouched, never logged. */
    val headers: Map<String, String> = emptyMap(),
    /** Trackers for the magnet, when the add-on listed any. */
    val sources: List<String> = emptyList(),
    /** Everything the parser read out of the text, for the right-hand facts pane (§10.4). */
    val facts: StreamFacts = StreamFacts(),
) {
    /** A row with neither a URL nor a hash cannot be played and must never be listed. */
    val playable: Boolean get() = !url.isNullOrBlank() || !infoHash.isNullOrBlank()

    /** Needs a debrid round trip before the player can open it. */
    val needsResolve: Boolean get() = url.isNullOrBlank() && !infoHash.isNullOrBlank()

    val sizeGB: Double? get() = sizeBytes?.takeIf { it > 0 }?.let { it / BYTES_PER_GB }

    companion object {
        const val BYTES_PER_GB: Double = 1_073_741_824.0

        /**
         * Build a row from the frozen `:contract` wire type, so a source list that arrives from the
         * phone or the private catalog server ranks by exactly the same rules as one the TV fetched
         * itself. `StreamEntry` carries no hash, so a row built this way is always direct.
         */
        fun from(entry: StreamEntry, addonName: String, id: String): StreamRow {
            val facts = StreamFacts.parse(
                listOfNotNull(entry.name, entry.title, entry.description, entry.filename),
                sizeBytes = entry.sizeBytes,
                statedQuality = entry.quality,
            )
            return StreamRow(
                id = id,
                addonName = addonName,
                title = entry.name?.takeIf(String::isNotBlank) ?: addonName,
                releaseName = listOfNotNull(entry.filename, entry.title, entry.name)
                    .firstOrNull(String::isNotBlank)
                    .orEmpty(),
                url = entry.url,
                quality = facts.quality,
                sizeBytes = entry.sizeBytes ?: facts.sizeBytes,
                codecs = facts.codecs,
                hdr = facts.hdr,
                audioLanguages = facts.audioLanguages,
                cachedHint = facts.cachedHint,
                seeders = facts.seeders,
                facts = facts,
            )
        }
    }
}

/** A subtitle track an add-on offered. */
data class SubtitleTrack(
    val id: String,
    val url: String,
    val language: String,
    val addonName: String,
)
