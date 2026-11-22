package com.fourseveneightnine.tv.client.playback

import androidx.compose.runtime.Immutable
import com.fourseveneightnine.tv.client.data.addons.getText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import kotlin.math.abs
import com.fourseveneightnine.tv.client.data.catalog.CatalogItem
import com.fourseveneightnine.tv.client.data.streams.StreamFacts
import com.fourseveneightnine.tv.client.data.streams.StreamRow
import com.fourseveneightnine.tv.client.data.streams.OriginalAudioLanguage
import com.fourseveneightnine.tv.client.data.streams.debrid.DebridResolver
import com.fourseveneightnine.tv.client.data.streams.debrid.DebridFile
import com.fourseveneightnine.tv.client.data.streams.debrid.DebridFileSelection
import com.fourseveneightnine.tv.client.data.streams.debrid.DebridSelectionHint
import com.fourseveneightnine.tv.client.data.meta.episodeDisplayTitle
import com.fourseveneightnine.tv.client.data.streams.HdrFormat
import com.fourseveneightnine.tv.protocol.ReceiverTrack
import com.fourseveneightnine.tv.protocol.ReceiverTracks
import com.fourseveneightnine.tv.player.PlaybackDiagnostics
import com.fourseveneightnine.tv.client.data.meta.Episode
import com.fourseveneightnine.tv.client.data.meta.Meta

/**
 * The episode after the one now playing, and the line the plate prints (`TV_DESIGN_SPEC.md` §11.7).
 *
 * [OpenMediaRequest.nextUp][com.fourseveneightnine.tv.protocol.OpenMediaRequest.nextUp] cannot
 * carry this one, because that wire type needs a URL and a local next episode has no URL yet: its
 * sources are searched while the plate counts down, which is what §11.7 asks for. The phone's own
 * `nextUp` is unchanged and still drives the Next pill on a cast.
 */
@Immutable
internal data class NextUpPlan(
    /** The show, not the episode: this is the canonical id every other call already uses. */
    val canonicalId: String,
    val type: String,
    val showTitle: String,
    val season: Int,
    val episode: Int,
    val episodeTitle: String,
    val stillUrl: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val runtimeMinutes: Int? = null,
    /** Release identity for this handoff. Files and download links are checked afresh. */
    val release: CatalogItem? = null,
    val creditLookupId: String? = null,
) {
    /** "S2 E5 · Pot au Feu", 1 line, 28 characters on the plate. */
    val line: String get() = NextUp.subtitle(season, episode, episodeTitle)

    /** "44 min", or null when the show never stated a runtime. */
    val runtimeLine: String? get() = runtimeMinutes?.takeIf { it > 0 }?.let { "$it min" }

    /** What a Play press for this episode asks for. */
    fun request(): PlayRequest = PlayRequest(
        type = type,
        id = canonicalId,
        title = showTitle,
        season = season,
        episode = episode,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
    )
}

/**
 * Which episode comes next, by season and episode order.
 *
 * Order is taken from the numbers, never from the order an add-on happened to list its videos in:
 * Cinemeta returns specials interleaved and TMDB returns them last, and a "next" that follows the
 * array lands on a special in the middle of a season.
 *
 * Specials (season 0) are never "next". A viewer who wants one picks it from the episodes row.
 */
internal object NextUp {

    fun compute(meta: Meta?, season: Int?, episode: Int?): NextUpPlan? {
        if (meta == null || meta.type != "series") return null
        if (season == null || episode == null) return null
        val next = nextEpisode(meta.videos, season, episode) ?: return null
        val release = next.released?.let { value ->
            runCatching { java.time.Instant.parse(value) }.getOrNull()
                ?: runCatching { java.time.LocalDate.parse(value.take(10)).atStartOfDay(java.time.ZoneOffset.UTC).toInstant() }.getOrNull()
        }
        if (release != null && release.isAfter(java.time.Instant.now())) return null
        return NextUpPlan(
            canonicalId = meta.id,
            type = meta.type,
            showTitle = meta.title,
            season = next.season,
            episode = next.episode,
            episodeTitle = next.title,
            stillUrl = next.thumbnail,
            posterUrl = meta.poster,
            backdropUrl = meta.backdrop,
            creditLookupId = meta.imdbID?.takeIf(IMDB::matches) ?: meta.id.substringAfter("::").takeIf(IMDB::matches)
                ?: meta.tmdbID?.takeIf { it > 0 }?.let { "tmdb:$it" } ?: meta.id.substringAfter("::").takeIf(TMDB::matches),
            runtimeMinutes = null, // Series runtime is typical, not an exact duration for this episode.
        )
    }

    fun fromRelease(request: PlayRequest, meta: Meta?, inventory: List<TamilMVReleaseFile>): NextUpPlan? {
        val season = request.season ?: return null
        val episode = request.episode ?: return null
        val listed = inventory.mapNotNull { file ->
            val s = file.season ?: return@mapNotNull null
            val e = file.episode ?: return@mapNotNull null
            Episode(s, e, "", id = "${request.id}:$s:$e")
        }
        val show = meta?.takeIf { it.type == "series" } ?: Meta(request.id, "series", request.title, poster = request.posterUrl, backdrop = request.backdropUrl)
        val current = Episode(season, episode, "", id = "")
        val inventoryEpisodes = (show.videos + listed + current).distinctBy { it.season to it.episode }
        val following = ordered(inventoryEpisodes).firstOrNull { it.season > season || (it.season == season && it.episode > episode) } ?: return null
        // A partial cache containing E04 and E06 must offer E05, never jump over it.
        val missing = if (following.season == season && following.episode > episode + 1)
            listOf(Episode(season, episode + 1, "", id = "")) else emptyList()
        return compute(show.copy(id = request.id, videos = inventoryEpisodes + missing), season, episode)
    }

    private val IMDB = Regex("tt[0-9]{7,10}")
    private val TMDB = Regex("tmdb:(?:series:)?[1-9][0-9]*")

    /** TheIntroDB v4 returns release-specific segments in milliseconds. Ending at EOF avoids
     * suggesting a handoff over a post-credit scene. A different cut keeps the duration fallback. */
    internal fun terminalCredits(body: String, season: Int, episode: Int, durationMs: Long): Double? {
        val data = Json.parseToJsonElement(body) as? JsonObject ?: return null
        fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.longOrNull
        if ((data["type"] as? JsonPrimitive)?.contentOrNull != "tv" ||
            data.number("season") != season.toLong() || data.number("episode") != episode.toLong()) return null
        val runtime = data.number("group_duration_ms") ?: return null
        if (durationMs <= 0 || runtime <= 0 || abs(runtime - durationMs) > 5_000L) return null
        val starts = (data["credits"] as? JsonArray).orEmpty().mapNotNull { segment ->
            val entry = segment as? JsonObject ?: return@mapNotNull null
            val end = entry["end_ms"]
            if (end != JsonNull && entry.number("end_ms") != runtime) return@mapNotNull null
            entry.number("start_ms")?.takeIf { it in 1 until minOf(durationMs, runtime) }
        }
        return starts.maxOrNull()?.div(1000.0)
    }

    suspend fun creditsForEpisode(http: OkHttpClient, id: String, season: Int, episode: Int, durationMs: Long): Double? {
        if (season < 1 || episode < 1 || durationMs <= 0) return null
        val identifier = when {
            IMDB.matches(id) -> "imdb_id" to id
            TMDB.matches(id) -> "tmdb_id" to id.substringAfterLast(':')
            else -> return null
        }
        val url = "https://api.theintrodb.org/v4/media".toHttpUrl().newBuilder()
            .addQueryParameter(identifier.first, identifier.second).addQueryParameter("season", season.toString())
            .addQueryParameter("episode", episode.toString()).addQueryParameter("duration_ms", durationMs.toString())
            .addQueryParameter("details", "true").build()
        return try {
            withTimeoutOrNull(4_000L) {
                terminalCredits(http.getText(url.toString(), 32_768, headers = mapOf("User-Agent" to "4789TV/1.0")).body,
                    season, episode, durationMs)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    fun previewStart(duration: Double, credits: Double?): Double =
        credits?.takeIf { it.isFinite() && it > 0 && it < duration } ?: (duration * 0.98)

    fun remainingSeconds(position: Double, duration: Double, credits: Double? = null): Int? {
        if (!duration.isFinite() || !position.isFinite() || duration <= 0 || position < previewStart(duration, credits)) return null
        return kotlin.math.ceil((duration - position).coerceAtLeast(0.0)).toInt()
    }

    /**
     * The episode straight after ([season], [episode]) in ordered, specials-free order.
     *
     * Returns null on the last episode of the last season, and on an episode the list does not
     * hold — a title whose numbering the add-on and the caller disagree about gets no auto-next
     * rather than a guess at which episode was meant.
     */
    fun nextEpisode(videos: List<Episode>, season: Int, episode: Int): Episode? {
        val ordered = ordered(videos)
        val index = ordered.indexOfFirst { it.season == season && it.episode == episode }
        if (index < 0) return null
        return ordered.getOrNull(index + 1)?.takeIf {
            (it.season == season && it.episode == episode + 1) ||
                (it.season == season + 1 && it.episode == 1)
        }
    }

    /** Every real episode, ascending by season then episode. Specials are dropped. */
    fun ordered(videos: List<Episode>): List<Episode> = videos
        .filter { it.season > 0 }
        .sortedWith(compareBy(Episode::season, Episode::episode))

    /** "S2 E4 · Honeydew", or "S2 E4" when the episode has no title (§11.16). */
    fun subtitle(season: Int?, episode: Int?, title: String?): String {
        val number = when {
            season == null || episode == null -> null
            else -> "S$season E$episode"
        }
        val name = title?.let(::episodeDisplayTitle)?.takeIf(String::isNotEmpty)
        return listOfNotNull(number, name).joinToString(" · ")
    }
}

/** Only hashed source identifiers and format preferences enter the profile database. */
@kotlinx.serialization.Serializable
internal data class EpisodePreference(
    val addon: String,
    val group: String?,
    val pack: String?,
    val languages: List<String>,
    val resolution: Int,
    val audio: List<String>,
    val selectedAudioLanguage: String? = null,
    val channels: Int? = null,
    val video: List<String> = emptyList(),
    val hdr: HdrFormat? = null,
    val subtitleLanguage: String? = null,
    val subtitlesOff: Boolean? = null,
    val audioLabel: String? = null,
    val subtitleLabel: String? = null,
) {
    companion object {
        private fun digest(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray()).toHexString()

        private fun addon(row: StreamRow) = row.refreshAddonKey ?: row.addonName
        private fun codec(value: String): String = when (val key = value.substringAfterLast('/').lowercase().replace("+", "plus").filter(Char::isLetterOrDigit)) {
            "h264", "x264", "avc1" -> "avc"
            "h265", "x265", "hvc1", "hev1" -> "hevc"
            "av01" -> "av1"
            "dolbydigital", "dd" -> "ac3"
            "dolbydigitalplus", "ddp", "ddplus", "eac3joc" -> "eac3"
            else -> key
        }

        fun from(row: StreamRow, language: String?, audioCodec: String?, width: Int = 0) = EpisodePreference(
            digest(addon(row)), row.bingeGroup?.let(::digest), row.infoHash?.lowercase()?.let(::digest),
            listOfNotNull(OriginalAudioLanguage.normalize(language)).ifEmpty {
                (row.facts.reportedAudioLanguages + row.audioLanguages).mapNotNull(OriginalAudioLanguage::normalize).distinct()
            }, when {
                width >= 3840 -> 5
                width >= 2560 -> 4
                width >= 1920 -> 3
                width >= 1280 -> 2
                else -> StreamFacts.qualityRank(row.quality ?: row.facts.quality)
            },
            listOfNotNull(audioCodec).ifEmpty { row.facts.audioCodecs }.map(::codec).filter { it.length in 1..24 },
            selectedAudioLanguage = canonicalTrackLanguage(language),
            channels = when (val layout = row.facts.audioChannels) {
                "2.0" -> 2
                "5.1" -> 6
                "7.1" -> 8
                else -> layout?.removeSuffix(" channels")?.toIntOrNull()?.takeIf { it > 0 }
            },
            video = row.codecs.map(::codec),
            hdr = row.hdr.takeUnless { it == HdrFormat.NONE },
        )

        private fun label(track: ReceiverTrack?) = track?.name?.trim()?.lowercase()?.takeIf(String::isNotBlank)?.let(::digest)

        fun playing(row: StreamRow, tracks: ReceiverTracks, format: PlaybackDiagnostics): EpisodePreference {
            val audio = tracks.currentAudio
            val sub = tracks.currentSubtitle
            val metadata = from(row, audio?.language, audio?.codec ?: format.audioCodec.takeIf(String::isNotBlank), format.width)
            return metadata.copy(
                resolution = when {
                    format.height >= 2160 -> maxOf(5, metadata.resolution)
                    format.height >= 1440 -> maxOf(4, metadata.resolution)
                    format.height >= 1080 -> maxOf(3, metadata.resolution)
                    else -> metadata.resolution
                },
                channels = audio?.channels?.takeIf { it > 0 } ?: format.audioChannels.takeIf { it > 0 } ?: metadata.channels,
                video = format.videoCodec.takeIf(String::isNotBlank)?.let { listOf(codec(it)) } ?: metadata.video,
                hdr = when {
                    format.dolbyVisionProfile > 0 -> HdrFormat.DOLBY_VISION
                    format.hdrTransfer.contains("2084", true) || format.hdrTransfer.equals("pq", true) -> HdrFormat.HDR10
                    else -> metadata.hdr
                },
                subtitleLanguage = canonicalTrackLanguage(sub?.language),
                subtitlesOff = (sub == null).takeIf { tracks.subtitles.isNotEmpty() },
                audioLabel = label(audio), subtitleLabel = label(sub),
            )
        }
    }

    fun audioTrack(available: List<ReceiverTrack>, language: String?): ReceiverTrack? =
        available.filter { language != null && canonicalTrackLanguage(it.language) == language }.maxWithOrNull(
            compareBy<ReceiverTrack> { audioLabel != null && label(it) == audioLabel }
                .thenBy { it.codec?.let(::codec) in audio }
                .thenBy { channels != null && it.channels == channels }.thenBy { it.selected })

    fun subtitleTrack(available: List<ReceiverTrack>): ReceiverTrack? {
        if (subtitlesOff == true) return null
        return available.filter { (subtitleLanguage != null && canonicalTrackLanguage(it.language) == subtitleLanguage) ||
            (subtitleLanguage == null && subtitleLabel != null && label(it) == subtitleLabel) }
            .maxWithOrNull(compareBy<ReceiverTrack> { subtitleLabel != null && label(it) == subtitleLabel }.thenBy { it.selected })
    }

    private fun hasPreferredSubtitles(row: StreamRow): Boolean =
        subtitleLanguage != null && row.facts.subtitleLanguages.any { canonicalTrackLanguage(it) == subtitleLanguage }

    /** Start early on a compatible encoding; an explicitly different format can wait for normal fallback. */
    fun earlyMatch(row: StreamRow): Boolean {
        val candidate = from(row, null, null)
        if (resolution >= 0 && resolution != candidate.resolution) return false
        val family = (pack != null && candidate.pack == pack) ||
            (group != null && group == candidate.group && addon == candidate.addon)
        if (hdr != null && candidate.hdr != null && hdr != candidate.hdr) return false
        if (channels != null && candidate.channels != null && channels != candidate.channels) return false
        if (audio.isNotEmpty() && candidate.audio.isNotEmpty() && candidate.audio.none(audio::contains)) return false
        if (video.isNotEmpty() && candidate.video.isNotEmpty() && candidate.video.none(video::contains)) return false
        if (subtitleLanguage != null && !hasPreferredSubtitles(row) && (!family || row.facts.subtitleLanguages.isNotEmpty())) return false
        return true
    }

    fun acceptsLanguage(row: StreamRow, language: String?): Boolean {
        if (OriginalAudioLanguage.matches(row, language)) return true
        val declared = (row.facts.reportedAudioLanguages + row.audioLanguages).mapNotNull(OriginalAudioLanguage::normalize)
        if (language != null && declared.isNotEmpty()) return OriginalAudioLanguage.normalize(language) in declared
        val sameGroup = group != null && digest(addon(row)) == addon && row.bingeGroup?.let(::digest) == group
        return sameGroup || (pack != null && row.infoHash?.lowercase()?.let(::digest) == pack)
    }

    fun preferred(rows: List<StreamRow>): List<StreamRow> {
        // Compute each identity once, then preserve the existing eligible order for ties.
        val candidates = rows.map { from(it, null, null) to it }
        return candidates.sortedWith(compareByDescending<Pair<EpisodePreference, StreamRow>> { pack != null && it.first.pack == pack }
            .thenByDescending { group != null && it.first.addon == addon && it.first.group == group }
            .thenByDescending { it.first.languages.any(languages::contains) }
            .thenByDescending { resolution >= 0 && it.first.resolution == resolution }
            .thenByDescending { hasPreferredSubtitles(it.second) }
            .thenByDescending { hdr != null && it.first.hdr == hdr }
            .thenByDescending { it.first.audio.any(audio::contains) }
            .thenByDescending { channels != null && it.first.channels == channels }
            .thenByDescending { it.first.video.any(video::contains) }
            .thenByDescending { it.first.addon == addon })
            .map { it.second }
    }
}

internal data class TamilMVReleaseFile(
    val stream: StreamRow,
    val filename: String,
    val season: Int?,
    val episode: Int?,
    val cached: Boolean = true,
) {
    val label: String get() = if (season != null && episode != null) "S${season} E${episode} · $filename" else filename
}

internal suspend fun CatalogItem.episodeFiles(debrid: DebridResolver, includeDeclared: Boolean = false,
    allCopies: Boolean = false): List<TamilMVReleaseFile> {
    val files = debrid.torboxFiles(playbackSources.map { it.infohash }.distinct(), torboxV2Hashes())
    return tamilMVEpisodeFiles(this, files, includeDeclared, allCopies)
}

internal suspend fun CatalogItem.movieBundleFiles(debrid: DebridResolver): List<TamilMVReleaseFile> {
    val files = debrid.torboxFiles(playbackSources.map { it.infohash }.distinct(), torboxV2Hashes())
    return tamilMVMovieBundleFiles(this, files)
}

/** Pick one edition and bind every visible part to its own exact TorBox file. */
internal fun tamilMVMovieBundleFiles(
    item: CatalogItem,
    filesByHash: Map<String, List<DebridFile>>,
): List<TamilMVReleaseFile> {
    val best = item.playbackSources.asSequence()
        .filterNot { StreamFacts.isTheatricalRelease(it.filename) }
        .map { source ->
            val files = filesByHash[source.infohash.lowercase()].orEmpty().filter { file ->
                (file.id ?: -1) >= 0 && (source.fileID == null || source.fileID == file.id) &&
                    !bundleExtras.containsMatchIn(file.name) &&
                    DebridFileSelection.select(listOf(file), DebridSelectionHint()) != null
            }.distinctBy(DebridFile::id)
            source to files
        }
        .maxByOrNull { it.second.size } ?: return emptyList()
    val (source, files) = best
    val release = item.copy(playbackSources = listOf(source)).savedStream(null, null) ?: return emptyList()
    return files.sortedWith(compareBy<DebridFile>(
        { bundleFileYear.find(it.name.substringAfterLast('/').substringAfterLast('\\'))?.value?.toIntOrNull() ?: Int.MAX_VALUE },
        { it.torrentIndex ?: Int.MAX_VALUE }, { it.name },
    )).map { file ->
        val filename = file.name.substringAfterLast('/').substringAfterLast('\\')
        val id = "saved-torbox:${source.infohash}:${file.id}"
        val stream = release.withFileFacts(filename, file.sizeBytes).copy(
            id = id, torboxTorrentID = null, torboxFileID = file.id,
        )
        TamilMVReleaseFile(stream, filename, season = null, episode = null)
    }
}

private val bundleExtras = Regex("""(?i)(?:^|[/\\ ._-])(?:sample|trailer|teaser|featurettes?|extras)(?=[/\\ ._-]|$)""")
private val bundleFileYear = Regex("""\b(?:19|20)\d{2}\b""")

/** Build the episode picker from files TorBox confirms for this release. */
internal fun tamilMVEpisodeFiles(
    item: CatalogItem,
    filesByHash: Map<String, List<DebridFile>>,
    includeDeclared: Boolean = false,
    allCopies: Boolean = false,
): List<TamilMVReleaseFile> {
    val episodes = mutableListOf<TamilMVReleaseFile>()
    val readyScopes = mutableSetOf<Pair<Int?, List<Int>>>()
    // Keep the best cached print for each scope, including separately synced episode releases.
    for (source in item.playbackSources) {
        val scope = source.season to source.episodes
        val hasPreferredFiles = scope in readyScopes
        val row = item.copy(playbackSources = listOf(source)).savedStream(null, null) ?: continue
        val files = filesByHash[source.infohash.lowercase()].orEmpty()
            .filter { it.id != null && (source.fileID == null || source.fileID == it.id) }
        for (file in files) {
            val name = file.name.substringAfterLast('/')
            val match = tamilMVEpisodeCode.find(name)
            val season = match?.groupValues?.get(1)?.toIntOrNull() ?: source.season ?: item.season
            val episode = match?.groupValues?.get(2)?.toIntOrNull()
                ?: tamilMVEpisodeOnly.find(name)?.groupValues?.get(1)?.toIntOrNull()
                ?: (source.episodes.singleOrNull() ?: item.episode).takeIf { files.size == 1 }
            if (source.season != null && season != source.season) continue
            if (source.episodes.isNotEmpty() && episode !in source.episodes) continue
            if (hasPreferredFiles && (season == null || episode == null)) continue
            episodes += TamilMVReleaseFile(row.withFileFacts(name, file.sizeBytes).copy(torboxFileID = file.id), name, season, episode)
            readyScopes += scope
        }
    }
    if (includeDeclared) {
        for (source in item.playbackSources) {
            val season = source.season ?: item.season ?: continue
            val row = item.copy(playbackSources = listOf(source)).savedStream(null, null) ?: continue
            for (episode in source.episodes) {
                episodes += TamilMVReleaseFile(row, "Check TorBox", season, episode, cached = false)
            }
        }
    }
    return episodes.distinctBy {
        if (!allCopies && it.season != null && it.episode != null) "${it.season}:${it.episode}"
        else "${it.stream.infoHash}:${it.stream.torboxFileID}"
    }.sortedWith(compareBy({ it.season ?: Int.MAX_VALUE }, { it.episode ?: Int.MAX_VALUE }, { it.filename }))
}

private val tamilMVEpisodeCode = Regex(
    "(?i)(?:^|[^a-z0-9])s?(\\d{1,3})(?:[ ._-]*ep?|x)[ ._-]*(\\d{1,4})(?![0-9])",
)
private val tamilMVEpisodeOnly = Regex("(?i)(?:^|[^a-z0-9])(?:e|ep|episode)[ ._-]*(\\d{1,4})(?![0-9])")
