// Derived from NuvioTV (GPL-3.0) app/src/main/java/com/nuvio/tv/core/debrid/DebridMagnetBuilder.kt
//
// Their magnet shape is kept verbatim in behaviour: `xt=urn:btih:<hash>`, an optional `dn` from
// the stated filename, and one `tr` per tracker, with `dht:` pseudo-sources dropped. The `Stream`
// domain type is replaced by this module's `StreamRow`.
package com.fourseveneightnine.tv.client.data.streams.debrid

import com.fourseveneightnine.tv.client.data.streams.StreamRow
import java.net.URLEncoder

object DebridMagnetBuilder {

    /** A magnet for the row, or null when it carries no info hash. */
    fun fromRow(row: StreamRow): String? {
        val hash = row.infoHash?.trim()?.takeIf(String::isNotBlank) ?: return null
        return buildString {
            append("magnet:?xt=urn:btih:")
            append(hash)
            row.releaseName.trim().takeIf(String::isNotBlank)?.let { name ->
                append("&dn=")
                append(encode(name))
            }
            row.sources
                .mapNotNull(::trackerOrNull)
                .distinct()
                .take(MAX_TRACKERS)
                .forEach { tracker ->
                    append("&tr=")
                    append(encode(tracker))
                }
        }
    }

    /** `dht:` entries are not trackers; passing one on makes a provider reject the whole magnet. */
    private fun trackerOrNull(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isBlank() || trimmed.startsWith("dht:", ignoreCase = true)) return null
        return trimmed.removePrefix("tracker:").trim().takeIf(String::isNotBlank)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private const val MAX_TRACKERS = 24
}
