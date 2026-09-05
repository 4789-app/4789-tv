package com.fourseveneightnine.phone

import java.net.URI

internal object AddonConfigurationPolicy {
    private const val MANIFEST_SUFFIX = "/manifest.json"
    private val titleIDPattern = Regex("^[A-Za-z0-9:_-]{1,160}$")

    fun normalizedManifestURL(value: String): String? {
        val text = value.trim()
        if (text.isEmpty() || text.length > 2_048) return null
        val parsed = runCatching { URI(text) }.getOrNull() ?: return null
        if (!parsed.scheme.equals("https", ignoreCase = true)) return null
        if (parsed.host.isNullOrBlank() || parsed.rawUserInfo != null) return null
        if (parsed.rawQuery != null || parsed.rawFragment != null) return null
        if (!parsed.rawPath.endsWith(MANIFEST_SUFFIX)) return null
        return parsed.normalize().toASCIIString()
    }

    /** The one wire word for a media type, or null when the caller named something else. */
    fun normalizedMediaType(value: String): String? = when (value.lowercase()) {
        "movie" -> "movie"
        "series", "tv" -> "series"
        else -> null
    }

    fun isTitleID(value: String): Boolean = titleIDPattern.matches(value)

    fun streamURL(manifestURL: String, mediaType: String, titleID: String): String? {
        val manifest = normalizedManifestURL(manifestURL) ?: return null
        val type = normalizedMediaType(mediaType) ?: return null
        if (!isTitleID(titleID)) return null
        return manifest.removeSuffix(MANIFEST_SUFFIX) + "/stream/$type/$titleID.json"
    }

    fun displayHost(manifestURL: String): String? = normalizedManifestURL(manifestURL)
        ?.let { URI(it).host }
}
