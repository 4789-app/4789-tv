package com.fourseveneightnine.tv.client.data.addons

import java.net.URI
import java.util.Locale

/**
 * Builds and validates Stremio add-on routes.
 *
 * Ported from `TVAddonRoutePolicy` in `:app`. Two rules carry over unchanged, and both are
 * security rules rather than tidiness:
 *
 *  - a manifest URL can carry a credential in its path or query, so it is never logged and never
 *    redirected;
 *  - an identifier that is not `[A-Za-z0-9:._-]` never reaches a URL, so a catalog row cannot
 *    steer a request somewhere else.
 */
object AddonEndpoint {

    private val SAFE_IDENTIFIER = Regex("[A-Za-z0-9:._-]{1,500}")
    private const val MAX_URL_LENGTH = 16 * 1_024
    private const val MAX_STREAM_URL_LENGTH = 32 * 1_024

    /**
     * A comparison key for "is this the same add-on?". Lower-cases the host, drops a trailing
     * slash and `/manifest.json`, and keeps the query, because for AIOStreams and MediaFusion the
     * query IS the configuration.
     */
    fun normalize(value: String): String? {
        val candidate = value.trim().takeIf { it.length in 1..MAX_URL_LENGTH } ?: return null
        val parsed = runCatching { URI(candidate) }.getOrNull() ?: return null
        if (!parsed.isSafeHttp()) return null
        val scheme = parsed.scheme.lowercase(Locale.US)
        val port = if (parsed.port >= 0) ":${parsed.port}" else ""
        val path = parsed.rawPath.orEmpty().trimEnd('/').removeSuffix("/manifest.json")
        val query = parsed.rawQuery?.let { "?$it" }.orEmpty()
        return "$scheme://${parsed.host.lowercase(Locale.US)}$port$path$query"
    }

    /** `https://host/path/manifest.json?query` for an add-on base that may be either form. */
    fun manifestURL(base: String): String? = route(base, "manifest.json")

    /**
     * `/catalog/{type}/{id}[/{extra}].json`. Stremio's extra segment is `key=value&key=value`,
     * URL-encoded, which is why it is built here rather than as query parameters: an add-on that
     * carries its own query must keep it.
     */
    fun catalogURL(base: String, type: String, id: String, extra: String?): String? {
        if (!SAFE_IDENTIFIER.matches(type) || !SAFE_IDENTIFIER.matches(id)) return null
        val tail = if (extra.isNullOrBlank()) "catalog/$type/$id.json" else "catalog/$type/$id/$extra.json"
        return route(base, tail)
    }

    /** `/meta/{type}/{id}.json`. */
    fun metaURL(base: String, type: String, id: String): String? {
        if (!SAFE_IDENTIFIER.matches(type) || !SAFE_IDENTIFIER.matches(id)) return null
        return route(base, "meta/$type/$id.json")
    }

    /** `/stream/{type}/{id}.json`, where `id` already carries `:season:episode` for a series. */
    fun streamURL(base: String, type: String, id: String): String? {
        if (!SAFE_IDENTIFIER.matches(type) || !SAFE_IDENTIFIER.matches(id)) return null
        return route(base, "stream/$type/$id.json")
    }

    /** `/subtitles/{type}/{id}.json`. */
    fun subtitlesURL(base: String, type: String, id: String): String? {
        if (!SAFE_IDENTIFIER.matches(type) || !SAFE_IDENTIFIER.matches(id)) return null
        return route(base, "subtitles/$type/$id.json")
    }

    /**
     * A URL an add-on handed back that we are willing to hand to the player. `http`/`https` only,
     * a real host, no embedded credentials and no fragment.
     */
    fun playableURL(value: String?): String? {
        val candidate = value?.trim()?.takeIf { it.length in 1..MAX_STREAM_URL_LENGTH } ?: return null
        val parsed = runCatching { URI(candidate) }.getOrNull() ?: return null
        return candidate.takeIf { parsed.isSafeHttp() }
    }

    /**
     * Stremio identifiers the TV may try for one title, best first: the IMDb id, the canonical id
     * the row carries, and the compact `tmdb:<id>` form several add-ons want instead of the
     * catalog's `tmdb:movie:<id>`.
     */
    fun identifiers(canonicalID: String, imdbID: String?, tmdbID: Int?): List<String> = buildList {
        imdbID?.trim()?.takeIf(SAFE_IDENTIFIER::matches)?.let(::add)
        val canonical = canonicalID.substringAfter("::").trim()
        canonical.takeIf(SAFE_IDENTIFIER::matches)?.let(::add)
        Regex("^tmdb:(?:movie|series):([0-9]+)$", RegexOption.IGNORE_CASE)
            .matchEntire(canonical)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { add("tmdb:$it") }
        tmdbID?.takeIf { it > 0 }?.let { add("tmdb:$it") }
    }.distinct().take(3)

    /** `tt1234567:2:4` for a series episode, `tt1234567` for a movie. */
    fun episodeIdentifier(id: String, season: Int?, episode: Int?): String =
        if (season != null && episode != null) "$id:$season:$episode" else id

    /**
     * Stremio's `extra` segment. Keys the add-on never declared are still sent — the manifest's
     * `extraSupported` list is advisory and several add-ons under-report it.
     */
    fun extraSegment(extra: CatalogExtra): String? {
        val parts = buildList {
            extra.genre?.takeIf(String::isNotBlank)?.let { add("genre=${encode(it)}") }
            extra.search?.takeIf(String::isNotBlank)?.let { add("search=${encode(it)}") }
            extra.skip.takeIf { it > 0 }?.let { add("skip=$it") }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("&")
    }

    private fun route(base: String, tail: String): String? {
        val trimmed = base.trim().takeIf { it.length in 1..MAX_URL_LENGTH } ?: return null
        val parsed = runCatching { URI(trimmed) }.getOrNull() ?: return null
        if (!parsed.isSafeHttp()) return null
        val withoutQuery = trimmed.substringBefore('?').substringBefore('#')
            .trimEnd('/')
            .removeSuffix("/manifest.json")
        val query = parsed.rawQuery?.let { "?$it" }.orEmpty()
        val result = "$withoutQuery/$tail$query"
        if (result.length > MAX_URL_LENGTH) return null
        val resultURI = runCatching { URI(result) }.getOrNull() ?: return null
        return result.takeIf { resultURI.isSafeHttp() }
    }

    private fun URI.isSafeHttp(): Boolean =
        scheme?.lowercase(Locale.US) in setOf("http", "https") &&
            !host.isNullOrBlank() &&
            userInfo == null &&
            fragment == null

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
