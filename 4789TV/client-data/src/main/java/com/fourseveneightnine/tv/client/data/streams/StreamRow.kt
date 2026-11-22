package com.fourseveneightnine.tv.client.data.streams

import com.fourseveneightnine.contract.StreamEntry
import java.security.MessageDigest
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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
    /** True only after a read-only provider cache response, not a label or a direct URL. */
    val verified: Boolean = false,
    val uncached: Boolean = false,
    /** Explicit debrid transport even if a resolver has already supplied HTTPS. */
    val debrid: Boolean = false,
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
    val torboxTorrentID: Int? = null,
    val torboxFileID: Int? = null,
    val exactSource: Boolean = false,
    /** null for an exact saved file or a caller without title context. */
    val identityVerified: Boolean? = null,
    /** Resolver-path hash used only for cache inspection, never for deduplication. */
    val cacheHash: String? = null,
    val v2Infohash: String? = null,
    /** Actual filename hint, separate from a display title or provider label. */
    val filename: String? = null,
    /** Add-on request recipe survives URL-based row deduplication. */
    val refreshAddonKey: String? = null,
    val refreshID: String? = null,
    val bingeGroup: String? = null,
    val identityNames: List<String> = emptyList(),
    val expectation: StreamExpectation? = null,
    /** Local admission proof; never read from a phone/add-on readiness flag. */
    val linkCheckedAtMillis: Long? = null,
    /** Local cache identity behind a checked resolved route; never accepted from a source payload. */
    val validationSourceKey: String? = null,
    /** Exact path returned by the local cache check, separate from an add-on filename claim. */
    val verifiedCacheFile: String? = null,
    /** The viewer selected a provider file from the account library. */
    val torboxLibraryFile: Boolean = false,
) {
    val audioLanguageNames: List<String>
        get() = (audioLanguages + facts.reportedAudioLanguages).mapNotNull(OriginalAudioLanguage::normalize)
            .distinct().map(OriginalAudioLanguage::displayName)

    val theatricalRelease: Boolean = facts.theatricalRelease ||
        listOfNotNull(title, releaseName, filename).any(StreamFacts::isTheatricalRelease)
    /** A row with neither a URL nor a hash cannot be played and must never be listed. */
    val playable: Boolean get() = !url.isNullOrBlank() || !infoHash.isNullOrBlank()

    /** DMM entries require a local file/video check before any Sources surface displays them. */
    val requiresDmmValidation: Boolean get() = validationSourceKey != null || addonName.trim().startsWith("DMM Cast", ignoreCase = true) ||
        facts.provider?.startsWith("DMM Cast", ignoreCase = true) == true ||
        url?.startsWith("https://debridmediamanager.com/api/stremio", ignoreCase = true) == true ||
        url?.startsWith("http://debridmediamanager.com/api/stremio", ignoreCase = true) == true

    /** The URL is the actual player transport, even when the add-on also supplies an info hash. */
    val httpSource: Boolean = url?.startsWith("https://", ignoreCase = true) == true ||
        url?.startsWith("http://", ignoreCase = true) == true
    val needsResolve: Boolean get() = url.isNullOrBlank() && !infoHash.isNullOrBlank()

    private val cachedFeed = isCachedFeed(addonName)
    private val endpoint = url?.let { runCatching { java.net.URI(it) }.getOrNull() }
    private val host = endpoint?.host?.lowercase(Locale.ROOT)
    val deliveryService: String? = cachedHint.service ?: when {
        host.belongsTo("torbox.app") || host.belongsTo("torbox.cloud") ||
            url?.startsWith("https://debridmediamanager.com/api/stremio-tb/", true) == true -> "TorBox"
        host.belongsTo("real-debrid.com") || host.belongsTo("real-debrid.net") -> "Real-Debrid"
        host.belongsTo("alldebrid.com") -> "AllDebrid"
        host.belongsTo("premiumize.me") -> "Premiumize"
        else -> providerService(addonName) ?: providerService(facts.provider) ?:
            endpoint?.rawPath?.split('/')?.dropLast(1)?.firstNotNullOfOrNull(::providerService)
    }
    val debridBacked: Boolean = needsResolve || torboxTorrentID != null || torboxFileID != null ||
        !deliveryService.isNullOrBlank() || cachedHint.debrid || cachedFeed || requiresDmmValidation ||
        addonName.startsWith("MediaFusion", ignoreCase = true)
    val directHttp: Boolean get() = httpSource && !debridBacked
    val knownUncached: Boolean get() = cachedHint.uncached || facts.cachedHint.uncached
    val cachedDebrid: Boolean get() = debridBacked && !knownUncached && (cachedFeed || cachedHint.cached ||
        (requiresDmmValidation && linkCheckedAtMillis != null))

    /** Shared admission for Direct and cached Debrid. DMM also passes its exact-file/video gate. */
    val readyHint: Boolean get() = playable && !knownUncached && (directHttp || cachedDebrid)

    /** A direct link and a torrent are independent playback routes, even for the same release. */
    fun playbackKey(): String {
        val identity = if (!url.isNullOrBlank()) {
            val requestHeaders = headers.mapKeys { it.key.lowercase(Locale.ROOT) }.toSortedMap()
            "url\u0000$url\u0000" + requestHeaders.entries.joinToString("\u0000") { "${it.key}:${it.value}" }
        } else {
            "torrent\u0000${infoHash?.lowercase(Locale.ROOT)}\u0000${fileIdx ?: "unknown"}"
        }
        // Lazy-list keys can enter saved UI state. Do not put capability URLs or headers there.
        return MessageDigest.getInstance("SHA-256").digest(identity.encodeToByteArray()).toHexString()
    }

    val sizeGB: Double? get() = sizeBytes?.takeIf { it > 0 }?.let { it / BYTES_PER_GB }

    /** Cache/file-response evidence supplies the selected file's facts rather than the pack label. */
    fun withFileFacts(name: String?, bytes: Long? = null): StreamRow {
        val file = name?.takeIf(String::isNotBlank) ?: filename
        val actual = StreamFacts.parse(listOf(file), sizeBytes = bytes)
        val merged = facts.copy(
            quality = actual.quality ?: facts.quality, sizeBytes = bytes?.takeIf { it > 0 } ?: facts.sizeBytes,
            codecs = actual.codecs.ifEmpty { facts.codecs },
            hdr = if (actual.hdr == HdrFormat.NONE) facts.hdr else actual.hdr,
            dolbyVisionProfile = actual.dolbyVisionProfile ?: facts.dolbyVisionProfile,
            audio = actual.audio ?: facts.audio, audioChannels = actual.audioChannels ?: facts.audioChannels,
            audioCodecs = (actual.audioCodecs + facts.audioCodecs).distinct(),
            audioLanguages = (facts.audioLanguages + actual.reportedAudioLanguages).distinct(),
            reportedAudioLanguages = (facts.reportedAudioLanguages + actual.reportedAudioLanguages).distinct(),
            releaseGroup = actual.releaseGroup ?: facts.releaseGroup,
            resolutionText = actual.resolutionText ?: facts.resolutionText,
            container = actual.container ?: facts.container, bitDepth = actual.bitDepth ?: facts.bitDepth,
        )
        return copy(filename = file, releaseName = file ?: releaseName, facts = merged,
            quality = merged.quality ?: quality, sizeBytes = bytes?.takeIf { it > 0 } ?: sizeBytes,
            codecs = merged.codecs, hdr = merged.hdr, audioLanguages = merged.audioLanguages)
    }

    /** Only identical URLs/headers are merged; separate encodes and delivery routes retain choices. */
    fun mergingMetadata(other: StreamRow): StreamRow {
        val all = facts.merging(other.facts)
        val pickedQuality = quality ?: facts.quality ?: other.quality ?: all.quality
        val pickedSize = sizeBytes ?: facts.sizeBytes ?: other.sizeBytes ?: all.sizeBytes
        val pickedCodecs = (codecs + other.codecs + all.codecs).distinct()
        val pickedHdr = if (hdr != HdrFormat.NONE) hdr else if (other.hdr != HdrFormat.NONE) other.hdr else all.hdr
        val pickedLanguages = (audioLanguages + other.audioLanguages + all.audioLanguages).distinct()
        val merged = all.copy(quality = pickedQuality, sizeBytes = pickedSize, codecs = pickedCodecs,
            hdr = pickedHdr, audioLanguages = pickedLanguages)
        val hint = when {
            knownUncached -> cachedHint.copy(uncached = true)
            other.knownUncached -> other.cachedHint.copy(uncached = true)
            cachedHint.verified -> cachedHint
            other.cachedHint.verified -> other.cachedHint
            else -> cachedHint.copy(cached = cachedDebrid || other.cachedDebrid,
                service = deliveryService ?: other.deliveryService, debrid = debridBacked || other.debridBacked)
        }
        return copy(cachedHint = hint, facts = merged, quality = pickedQuality, sizeBytes = pickedSize,
            codecs = pickedCodecs, hdr = pickedHdr, audioLanguages = pickedLanguages,
            seeders = seeders ?: other.seeders ?: merged.seeders, filename = filename ?: other.filename,
            identityNames = (identityNames + other.identityNames).distinct(), cacheHash = cacheHash ?: other.cacheHash,
            refreshAddonKey = refreshAddonKey ?: other.refreshAddonKey, refreshID = refreshID ?: other.refreshID,
            bingeGroup = bingeGroup ?: other.bingeGroup.takeIf { (refreshAddonKey ?: addonName) == (other.refreshAddonKey ?: other.addonName) },
            linkCheckedAtMillis = linkCheckedAtMillis ?: other.linkCheckedAtMillis,
            validationSourceKey = validationSourceKey ?: other.validationSourceKey,
            verifiedCacheFile = verifiedCacheFile ?: other.verifiedCacheFile?.takeIf { cacheHash == null || cacheHash == other.cacheHash })
    }

    companion object {
        internal fun providerService(value: String?): String? = when (value?.lowercase(Locale.ROOT)?.trim()) {
            "torbox" -> "TorBox"
            "realdebrid", "real-debrid", "real debrid" -> "Real-Debrid"
            "alldebrid" -> "AllDebrid"
            "premiumize" -> "Premiumize"
            "debrid-link" -> "Debrid-Link"
            else -> null
        }

        private val CACHED_FEED = Regex("""^(?:aio[ ._-]*streams|meteor)(?:$|[^a-z0-9])""", RegexOption.IGNORE_CASE)
        internal fun isCachedFeed(name: String): Boolean = CACHED_FEED.containsMatchIn(name.trim())
        private fun String?.belongsTo(domain: String): Boolean = this == domain || this?.endsWith(".$domain") == true

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
                filename = entry.filename,
                identityNames = listOfNotNull(entry.title, entry.name, entry.description),
            )
        }
    }
}

/** Strict positive name evidence for automatic selection. Unknown names remain manual choices. */
data class StreamExpectation(
    val title: String,
    val year: Int?,
    val season: Int?,
    val episode: Int?,
    val isMovie: Boolean,
    val aliases: List<String> = emptyList(),
    val episodeYear: Int? = null,
    val canonicalID: String? = null,
    val verifiedReleaseYear: Int? = null,
) {
    val cacheIdentity: String get() = (canonicalID ?: normalized(title)) + ":$year:$season:$episode:$isMovie:$episodeYear:$verifiedReleaseYear:" +
        (listOf(title) + aliases).map(::normalized).filter(String::isNotBlank).distinct().sorted().joinToString("|")

    fun matches(row: StreamRow): Boolean {
        if (row.theatricalRelease) return false
        val actual = row.filename?.takeIf { it.isNotBlank() && !genericFilename(it) }
        if (actual != null) return (listOf(title) + aliases).any { matchesName(actual, it) }
        val candidates = (row.identityNames + listOf(row.releaseName, row.title)).flatMap { it.lines() }.filter { it.isNotBlank() }
        val names = (listOf(title) + aliases).filter { it.isNotBlank() }
        if (candidates.any { name -> names.any { normalized(name).startsWith(normalized(it) + " ") } && contradictsFilename(name) }) return false
        return candidates.any { name -> names.any { matchesName(name, it) } }
    }

    private fun matchesName(value: String, canonical: String): Boolean {
        val expected = normalized(canonical)
        val release = normalized(value)
        if (expected.isBlank() || !release.startsWith(expected)) return false
        val tail = release.removePrefix(expected)
        if (tail.isNotEmpty() && (!tail.startsWith(" ") || !BOUNDARY.containsMatchIn(tail.trimStart()))) return false
        if (JUNK.containsMatchIn(release)) return false
        val markers = EPISODE.findAll(release).toList()
        if (isMovie) {
            if (markers.isNotEmpty() || SEASON.containsMatchIn(release)) return false
            val actualYear = YEAR.find(tail)?.value?.toIntOrNull() ?: return false
            return actualYear == year || actualYear == verifiedReleaseYear
        }
        if (season == null || episode == null || RANGE.containsMatchIn(value)) return false
        val years = YEAR.findAll(tail).mapNotNull { it.value.toIntOrNull() }.toList()
        if (years.isNotEmpty() && years.none { it == year || it == episodeYear }) return false
        // Match the resolver's first-season fallback for absolute anime episode labels.
        if (markers.isEmpty() && season == 1 && !SEASON.containsMatchIn(release)) {
            return EPISODE_ONLY.findAll(tail).toList().singleOrNull()?.groupValues?.get(1)?.toIntOrNull() == episode
        }
        if (markers.size != 1) return false
        val marker = markers.single()
        val actualSeason = marker.groupValues[1].ifEmpty { marker.groupValues[3] }.toIntOrNull()
        val actualEpisode = marker.groupValues[2].ifEmpty { marker.groupValues[4] }.toIntOrNull()
        return actualSeason == season && actualEpisode == episode
    }

    /** Explicit date/episode contradictions remain unsafe even for a manually selected alias. */
    fun contradictsFilename(filename: String): Boolean {
        val release = normalized(filename)
        val markers = EPISODE.findAll(release).toList()
        if (isMovie) {
            if (markers.isNotEmpty() || SEASON.containsMatchIn(release)) return true
            val canonical = (listOf(title) + aliases).map(::normalized).filter { it.isNotBlank() }
                .firstOrNull { release == it || release.startsWith("$it ") }
            val tail = canonical?.let { release.removePrefix(it) } ?: release
            val actualYear = YEAR.find(tail)?.value?.toIntOrNull()
            return year != null && actualYear != null && actualYear != year && actualYear != verifiedReleaseYear
        }
        if (markers.isEmpty() || season == null || episode == null) return false
        if (markers.size != 1 || RANGE.containsMatchIn(filename)) return true
        val marker = markers.single()
        return marker.groupValues[1].ifEmpty { marker.groupValues[3] }.toIntOrNull() != season ||
            marker.groupValues[2].ifEmpty { marker.groupValues[4] }.toIntOrNull() != episode
    }

    private fun normalized(value: String): String = java.text.Normalizer
        .normalize(value.replace(RELEASE_PREFIX, ""), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(java.util.Locale.ROOT)
        .replace(LATIN_MANJI, " ")
        .replace("'", "").replace("’", "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    companion object {
        // Known release wrappers are not part of the work's title.
        private val RELEASE_PREFIX = Regex("^(?:(?:www\\.)?1tamilmv\\.[a-z]{2,20}\\s*-\\s*|pfanime[-._ ]+)", RegexOption.IGNORE_CASE)
        private val LATIN_MANJI = Regex("(?<=\\p{IsLatin})\\s*卍\\s*(?=\\p{IsLatin})")
        fun genericFilename(value: String): Boolean {
            val stem = value.substringBefore("|^|").replace('\\', '/').substringAfterLast('/').substringBeforeLast('.').lowercase(Locale.ROOT)
            return stem in setOf("video", "stream", "file", "download", "index", "master", "playlist", "media", "content", "output", "play", "live") ||
                (stem.length >= 16 && stem.all { it in "0123456789abcdef-" })
        }
        val BOUNDARY = Regex("^(?:19\\d{2}|20\\d{2}|s\\d+(?:ep?\\d+)?|e(?:p(?:isode)?)?\\s*\\d+|\\d+x\\d+|2160p?|1080p?|720p?|480p?|4k|web|bluray|blu|remux|hevc|x264|x265|h264|h265)(?:\\b|$)")
        val YEAR = Regex("\\b(?:19|20)\\d{2}\\b")
        val EPISODE = Regex("\\bs(\\d{1,3})\\s*ep?\\s*(\\d{1,4})(?![\\de])|\\b(\\d{1,3})x(\\d{1,4})(?!\\d)")
        private val EPISODE_ONLY = Regex("\\be(?:p(?:isode)?)?\\s*(\\d{1,4})(?!\\d)")
        val RANGE = Regex(
            "e\\d+\\s*(?:[-–+]|e)\\s*(?:s\\d+\\s*e|e)?\\d+\\b|\\d+x\\d+\\s*(?:[-–+]|x)\\s*(?:\\d+x)?\\d+\\b|\\be(?:p(?:isode)?)?[ ._-]*\\d+\\s*[-–+]\\s*\\d+\\b",
            RegexOption.IGNORE_CASE,
        )
        val SEASON = Regex("\\b(?:s\\d+|season\\s*\\d+)")
        val JUNK = Regex("\\b(?:sample|trailer|teaser)\\b")
    }
}

/** A subtitle track an add-on offered. */
data class SubtitleTrack(
    val id: String,
    val url: String,
    val language: String,
    val addonName: String,
    val release: String? = null,
    val isForced: Boolean = false,
    val trusted: Boolean = false,
    val aiTranslated: Boolean = false,
) {
    companion object {
        private val english = Regex("(?<![\\p{L}\\p{N}])(?:en|eng|english)(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
        private val forced = Regex("(?<![\\p{L}\\p{N}])forced(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
        private val token = Regex("[\\p{L}\\p{N}]+")

        fun isEnglishLabel(label: String): Boolean = english.containsMatchIn(label)

        fun isEnglishLanguage(language: String): Boolean =
            OriginalAudioLanguage.normalize(language) == "en" || isEnglishLabel(language)

        fun isForcedLabel(label: String): Boolean = forced.containsMatchIn(label)

        fun rankedForPlayback(tracks: List<SubtitleTrack>, videoFilename: String?): List<SubtitleTrack> {
            val video = tokens(videoFilename.orEmpty())
            return tracks.mapNotNull { track ->
                val filename = track.url.toHttpUrlOrNull()?.pathSegments?.lastOrNull().orEmpty()
                if (!isEnglishLanguage(track.language) || track.isForced ||
                    listOf(track.id, track.language, track.release.orEmpty(), filename).any(::isForcedLabel)) {
                    return@mapNotNull null
                }
                val score = listOfNotNull(track.release, track.id, filename).maxOf { tokens(it).intersect(video).size }
                track to score
            }.sortedWith(compareByDescending<Pair<SubtitleTrack, Int>> { it.second }
                .thenByDescending { it.first.trusted }.thenBy { it.first.aiTranslated }).map { it.first }
        }

        private fun tokens(name: String): Set<String> = token.findAll(name.lowercase(Locale.ROOT))
            .map { it.value }.filter { it.length > 2 }.toSet()
    }
}
