package com.fourseveneightnine.tv.client.playback

import com.fourseveneightnine.tv.client.data.addons.AddonEndpoint
import com.fourseveneightnine.tv.client.data.meta.Meta
import com.fourseveneightnine.tv.client.data.streams.StreamExpectation
import com.fourseveneightnine.tv.client.data.streams.StreamRow
import java.security.MessageDigest
import kotlinx.serialization.Serializable

/** Build title aliases and keep explicit episode video IDs intact. */
internal object StreamIds {
    fun candidates(type: String, id: String, meta: Meta?, season: Int?, episode: Int?): List<String> {
        val titles = titleAliases(id, meta)
        if (type != "series") return (listOfNotNull(meta?.defaultVideoID) + titles).distinct()
        if (season == null || episode == null) return titles
        val video = meta?.videos?.firstOrNull { it.season == season && it.episode == episode }?.id
            ?.takeUnless { it == "$season:$episode" }
        return (listOfNotNull(video) + titles.map {
            AddonEndpoint.episodeIdentifier(it, season, episode)
        }).distinct()
    }

    fun favoriteScopes(request: PlayRequest, meta: Meta?): List<String> {
        if (request.type != "movie" && request.type != "series") return emptyList()
        if (request.type == "series" && (request.season == null || request.episode == null)) return emptyList()
        val aliases = if (request.id.startsWith("saved:") || request.id.startsWith("saved-torbox:")) {
            listOf(request.id)
        } else {
            titleAliases(request.id, meta).ifEmpty { listOf(request.id) }
        }
        return aliases.map { alias ->
            val episode = if (request.type == "series") "${request.season}:${request.episode}" else ""
            MessageDigest.getInstance("SHA-256")
                .digest("${request.type}\u0000$alias\u0000$episode".encodeToByteArray()).toHexString()
        }
    }

    private fun titleAliases(id: String, meta: Meta?): List<String> {
        val canonical = id.substringAfter("::")
        val imdb = meta?.imdbID?.takeIf(IMDB::matches) ?: canonical.takeIf(IMDB::matches)
        val tmdb = meta?.tmdbID ?: TMDB.matchEntire(canonical)?.groupValues?.get(1)?.toIntOrNull()
        return AddonEndpoint.identifiers(canonical, imdb, tmdb)
    }

    private val IMDB = Regex("tt[0-9]+")
    private val TMDB = Regex("tmdb:(?:(?:movie|series):)?([0-9]+)")
}


/** Enough to find the chosen file again, without retaining a link or its request headers. */
@Serializable
internal data class FavoriteSource(
    val fingerprint: String,
    val fileIdentity: String?,
    val torboxReference: String?,
) {
    fun exactRow(title: String): StreamRow? = torboxReference?.let { savedTorboxFile(it, title) }

    fun matches(row: StreamRow): Boolean = when {
        torboxReference != null -> fileIdentity == row.favoriteFileIdentity()
        fileIdentity != null && fileIdentity == row.favoriteFileIdentity() -> true
        else -> fingerprint == row.playbackKey()
    }

    companion object {
        fun from(row: StreamRow): FavoriteSource {
            val hash = row.infoHash?.takeIf(validFavoriteHash::matches)?.lowercase()
            val file = row.torboxFileID?.takeIf { it >= 0 }
            val reference = if (hash != null && file != null) {
                "saved-torbox:$hash:$file" + (row.torboxTorrentID?.takeIf { it > 0 }?.let { ":$it" } ?: "")
            } else null
            return FavoriteSource(row.playbackKey(), row.favoriteFileIdentity(), reference)
        }
    }
}

/** The Watchlist stores one renewable source per card. A profile's Room row owns the recipe. */
@Serializable
internal data class WatchlistSource(
    val catalogId: String,
    val catalogTitle: String,
    val season: Int?,
    val episode: Int?,
    val torboxReference: String? = null,
    val torrentHash: String? = null,
    val fileIndex: Int? = null,
    val v2Hash: String? = null,
    val releaseKey: String? = null,
) {
    fun cardId(type: String): String = PREFIX + digest(listOf(type, catalogId, season, episode,
        torboxReference, torrentHash, fileIndex, releaseKey).joinToString("\u0000"))

    fun boundRow(title: String): StreamRow? {
        torboxReference?.let { return savedTorboxFile(it, title)?.copy(v2Infohash = v2Hash) }
        return torrentHash?.let { StreamRow(it, "Saved source", title, title,
            infoHash = it, fileIdx = fileIndex, v2Infohash = v2Hash) }
    }

    fun matches(row: StreamRow): Boolean = releaseKey != null &&
        (releaseKey == releaseIdentity(row) || (!row.cachedHint.service.isNullOrBlank() &&
            releaseKey == releaseIdentity(row, service = null)))

    companion object {
        const val PREFIX = "watch-source:"

        fun from(request: PlayRequest, row: StreamRow): WatchlistSource? {
            if (!row.playable || request.id.isBlank() || request.id.contains("://") || request.title.contains("://") ||
                request.id.startsWith(PREFIX)) return null
            val infoHash = row.infoHash?.takeIf(validFavoriteHash::matches)?.lowercase()
            val file = row.torboxFileID?.takeIf { it >= 0 }
            val reference = if (infoHash != null && file != null) "saved-torbox:$infoHash:$file" else null
            val hash = infoHash?.takeIf { reference == null && row.needsResolve && row.fileIdx != null }
            val release = if (reference == null && hash == null) releaseIdentity(row) else null
            if (reference == null && hash == null && release == null) return null
            return WatchlistSource(request.id, request.title, request.season, request.episode, reference,
                hash, row.fileIdx.takeIf { hash != null },
                row.v2Infohash?.takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) }, release)
        }

        private fun releaseIdentity(row: StreamRow, service: String? = row.cachedHint.service): String? {
            val name = (row.filename?.takeUnless { StreamExpectation.genericFilename(it) } ?: row.releaseName).substringBefore("|^|").trim().lowercase()
            if (name.isBlank() || name == "unnamed release" || "://" in name ||
                (row.filename.isNullOrBlank() && name == row.title.trim().lowercase())) return null
            return digest(listOf(row.refreshAddonKey ?: row.addonName, service.orEmpty(),
                name, row.sizeBytes ?: "", row.facts.videoHash.orEmpty()).joinToString("\u0000"))
        }

        private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray()).toHexString()
    }
}

@Serializable
internal data class SavedSourceFavorite(val scopes: List<String>, val source: FavoriteSource)

private val validFavoriteHash = Regex("[a-fA-F0-9]{40}|[a-zA-Z2-7]{32}")

private fun StreamRow.favoriteFileIdentity(): String? {
    val hash = infoHash?.takeIf(validFavoriteHash::matches)?.lowercase() ?: return null
    torboxFileID?.takeIf { it >= 0 }?.let { return "torbox:$hash:$it" }
    return fileIdx?.takeIf { it >= 0 }?.let { "torrent:$hash:$it" }
}
