package com.fourseveneightnine.tv.client.iptv

import java.net.URI
import java.security.MessageDigest

internal data class ParsedM3u(
    val channels: List<IptvChannel>,
    val vod: List<IptvVod>,
    val episodes: List<IptvVod>,
    val epgUrl: String?,
)

/** Pure M3U-plus parser. CRLF, quoted commas, EXTGRP and VLC request headers are common. */
internal object IptvM3u {
    private data class SeriesKey(val id: String, val show: String, val group: String)
    private val attribute = Regex("([\\w-]+)=\"([^\"]*)\"")
    private val episodeMarker = Regex("(?i)(?:[ ._\\-]+)?(?:S\\d{1,2}E(?:P)?\\d{1,4}|\\d{1,2}x\\d{1,4}|EP\\s?\\d{1,4})\\b")
    private val finishedVideo = setOf("mp4", "mkv", "avi", "m4v", "mov", "flv", "wmv", "mpg", "mpeg", "webm")

    fun parse(sourceId: String, text: String): ParsedM3u = parse(sourceId, text.lineSequence())

    fun parse(sourceId: String, lines: Sequence<String>): ParsedM3u {
        val channels = ArrayList<IptvChannel>()
        val movies = ArrayList<IptvVod>()
        val episodesByShow = LinkedHashMap<SeriesKey, MutableList<IptvVod>>()
        var episodeCount = 0
        var epgUrl: String? = null
        var title = ""
        var logo: String? = null
        var group = "Other"
        var epgId: String? = null
        var catchupDays = 0
        var catchupSource: String? = null
        var headers = emptyMap<String, String>()
        var pending = false
        for (raw in lines) {
            if (channels.size >= 50_000 && movies.size >= 150_000 && episodeCount >= 150_000) break
            val line = raw.trim()
            when {
                line.startsWith("#EXTM3U", true) -> {
                    val attrs = attrs(line)
                    epgUrl = (attrs["url-tvg"] ?: attrs["x-tvg-url"])?.split(',')?.firstOrNull()?.trim()
                }
                line.startsWith("#EXTINF", true) -> {
                    val attrs = attrs(line)
                    title = afterUnquotedComma(line).ifBlank { attrs["tvg-name"].orEmpty() }
                    logo = attrs["tvg-logo"]?.takeIf(::httpUrl)
                    group = attrs["group-title"]?.ifBlank { null } ?: "Other"
                    epgId = attrs["tvg-id"]?.ifBlank { null }
                    catchupDays = attrs["catchup-days"]?.toIntOrNull()?.coerceIn(0, 30) ?: 0
                    catchupSource = attrs["catchup-source"]
                    headers = buildMap {
                        attrs["http-user-agent"]?.let { put("User-Agent", it) }
                        (attrs["http-referrer"] ?: attrs["http-referer"] ?: attrs["referrer"])
                            ?.let { put("Referer", it) }
                    }
                    pending = true
                }
                pending && line.startsWith("#EXTGRP:", true) -> group = line.substringAfter(':').trim().ifBlank { group }
                pending && line.startsWith("#EXTVLCOPT:", true) -> {
                    val key = line.substringAfter(':').substringBefore('=').lowercase()
                    val value = line.substringAfter('=', "").trim().trim('"')
                    headers = headers + when (key) {
                        "http-user-agent" -> mapOf("User-Agent" to value)
                        "http-referrer", "http-referer" -> mapOf("Referer" to value)
                        else -> emptyMap()
                    }
                }
                pending && !line.startsWith('#') -> {
                    if (httpUrl(line)) {
                        val display = title.ifBlank { runCatching { URI(line).path.substringAfterLast('/') }.getOrDefault("Channel") }
                        val stableAddress = runCatching {
                            URI(line).let { uri -> "${uri.host}${uri.path}" }
                        }.getOrDefault(line.substringBefore('?'))
                        val id = "$sourceId:${stableId(epgId.orEmpty(), group, display, stableAddress)}"
                        val kind = classify(display, group, line)
                        if (kind == "live") {
                            if (channels.size < 50_000) channels += IptvChannel(id, sourceId, display, group, logo, epgId,
                                line, headers, catchupDays, catchupSource)
                        } else if (kind == "movie") {
                            if (movies.size < 150_000) movies += IptvVod(id, sourceId, kind, display, group,
                                logo, directUrl = line, headers = headers)
                        } else {
                            val show = showTitle(display)
                            val seriesId = "$sourceId:series:${stableId(group.lowercase(), show.lowercase())}"
                            val list = episodesByShow.getOrPut(SeriesKey(seriesId, show, group)) { mutableListOf() }
                            if (episodeCount < 150_000) {
                                list += IptvVod(id, sourceId, "episode", display, seriesId,
                                    logo, directUrl = line, headers = headers)
                                episodeCount++
                            }
                        }
                    }
                    pending = false
                    title = ""
                    headers = emptyMap()
                }
            }
        }
        val series = episodesByShow.mapNotNull { (identity, items) ->
            if (items.isEmpty()) return@mapNotNull null
            IptvVod(identity.id, sourceId, "series", identity.show, identity.group, items.first().image)
        }
        return ParsedM3u(channels, movies + series, episodesByShow.values.flatten(), epgUrl?.takeIf(::httpUrl))
    }

    private fun classify(name: String, group: String, rawUrl: String): String {
        val path = runCatching { URI(rawUrl).path.lowercase().split('/') }.getOrDefault(emptyList())
        val ext = path.lastOrNull()?.substringAfterLast('.', "") ?: ""
        val category = group.uppercase()
        return when {
            "series" in path -> "series"
            path.any { it in setOf("movie", "movies", "vod") } -> "movie"
            "live" in path -> "live"
            category.contains("SERIES") || category.contains("SHOWS") || category.contains("TV SHOW") -> "series"
            category.contains("VOD") || category.contains("MOVIE") || category.contains("CINEMA") || category.contains("FILM") -> "movie"
            category.contains("LIVE") || category.contains("CHANNEL") -> "live"
            episodeMarker.containsMatchIn(name) -> "series"
            ext in finishedVideo -> "movie"
            Regex("\\((19|20)\\d{2}\\)$").containsMatchIn(name) -> "movie"
            else -> "live"
        }
    }

    private fun showTitle(name: String): String = episodeMarker.find(name)?.let { marker ->
        name.substring(0, marker.range.first).trim(' ', '.', '-', '_').ifBlank { name }
    } ?: name.substringBefore(" - ").ifBlank { name }

    private fun attrs(line: String): Map<String, String> = attribute.findAll(line)
        .associate { it.groupValues[1].lowercase() to it.groupValues[2].trim() }

    private fun afterUnquotedComma(line: String): String {
        var quote = false
        var index = -1
        line.forEachIndexed { i, char ->
            if (char == '"') quote = !quote
            if (char == ',' && !quote) index = i
        }
        return if (index < 0) "" else line.substring(index + 1).trim()
    }

    private fun stableId(vararg parts: String): String = MessageDigest.getInstance("SHA-256")
        .digest(parts.joinToString("|").encodeToByteArray()).take(12)
        .joinToString("") { "%02x".format(it) }

    internal fun httpUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()
    }.getOrDefault(false)
}
