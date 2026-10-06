package com.fourseveneightnine.tv.client.data.addons

import com.fourseveneightnine.contract.DiscoverItem
import com.fourseveneightnine.tv.client.data.meta.CastMember
import com.fourseveneightnine.tv.client.data.meta.Episode
import com.fourseveneightnine.tv.client.data.meta.Meta
import com.fourseveneightnine.tv.client.data.meta.MetaRef
import com.fourseveneightnine.tv.client.data.streams.CachedHint
import com.fourseveneightnine.tv.client.data.streams.StreamFacts
import com.fourseveneightnine.tv.client.data.streams.StreamRow
import com.fourseveneightnine.tv.client.data.streams.SubtitleTrack
import java.io.File
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient

/** One page of a Stremio catalog. */
data class CatalogPage(
    val items: List<DiscoverItem>,
    /** The add-on returned a full page, so there is probably another one. */
    val hasMore: Boolean,
)

/**
 * Every call the TV makes to a Stremio add-on.
 *
 * Ported from `TVAddonSourceRepository` and `phone/RemoteSourceRepository`. Three properties are
 * carried over deliberately:
 *
 *  - **Nothing is logged.** A manifest URL is a credential for AIOStreams and MediaFusion, and a
 *    resolved stream URL is a capability. Neither appears in a breadcrumb, and failures are
 *    recorded as a class name or a status code.
 *  - **Every response has a ceiling.** A 2 MB cap on an add-on answer and a 500-stream cap on what
 *    it may contain, because a hostile or broken add-on should cost one request, not the heap.
 *  - **Every parse is tolerant.** A shape we do not recognise degrades to empty, never to an
 *    exception on a screen that has already painted.
 *
 * Timeouts for a whole fan-out live in `StreamSearch`, not here: one call knows nothing about the
 * deadline the set is racing.
 */
class StremioClient(
    private val okHttp: OkHttpClient = defaultAddonHttpClient(),
    cacheDir: File? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val manifestCache = cacheDir?.let { HttpDiskCache(File(it, "manifests")) }

    /**
     * The add-on's manifest, cached for 24 h on disk and revalidated with `If-None-Match`.
     *
     * A 304 keeps the cached body and only resets its age, which is what makes a cold start cheap
     * on a box with a dozen add-ons.
     */
    suspend fun manifest(url: String): AddonManifest {
        val route = AddonEndpoint.manifestURL(url) ?: throw IllegalArgumentException("bad_manifest_url")
        val cached = manifestCache?.read(route)
        val fresh = cached != null && clock() - cached.storedAtMillis < MANIFEST_TTL_MILLIS
        if (fresh) {
            parseManifest(cached.body)?.let { return it }
        }
        val response = okHttp.getText(route, MAX_RESPONSE_BYTES, etag = cached?.etag)
        if (response.notModified && cached != null) {
            manifestCache.touch(route, clock())
            parseManifest(cached.body)?.let { return it }
        }
        val manifest = parseManifest(response.body) ?: throw IllegalArgumentException("bad_manifest")
        manifestCache?.write(route, response.body, response.etag, clock())
        return manifest
    }

    /** One catalog page. `extra` becomes Stremio's `key=value&key=value` path segment. */
    suspend fun catalog(
        addon: Addon,
        type: String,
        id: String,
        extra: CatalogExtra = CatalogExtra(),
    ): CatalogPage {
        val route = AddonEndpoint.catalogURL(
            base = addon.manifestURL,
            type = type,
            id = id,
            extra = AddonEndpoint.extraSegment(extra),
        ) ?: throw IllegalArgumentException("bad_catalog_url")
        val body = okHttp.getText(route, MAX_RESPONSE_BYTES).body
        val metas = (root(body)?.get("metas") as? JsonArray).orEmpty()
        val items = metas.mapNotNull { discoverItem(it, catalogID = id) }.take(MAX_CATALOG_ITEMS)
        return CatalogPage(items = items, hasMore = metas.size >= CATALOG_FULL_PAGE)
    }

    /** Full metadata for one title, or null when this add-on has nothing for it. */
    suspend fun meta(addon: Addon, type: String, id: String): Meta? {
        val route = AddonEndpoint.metaURL(addon.manifestURL, type, id) ?: return null
        val body = okHttp.getText(route, MAX_RESPONSE_BYTES).body
        return parseMeta(body, fallbackType = type)
    }

    /**
     * Playable rows from one add-on. `id` already carries `:season:episode` for a series episode.
     *
     * Rows with neither a URL nor an info hash are dropped, as is the placeholder MP4 several
     * add-ons return instead of an empty list. A placeholder that reaches the list is worse than
     * no row: it is a Play button that opens a ten-second apology video.
     */
    suspend fun streams(addon: Addon, type: String, id: String): List<StreamRow> {
        val route = AddonEndpoint.streamURL(addon.manifestURL, type, id) ?: return emptyList()
        val body = okHttp.getText(route, MAX_RESPONSE_BYTES).body
        val streams = (root(body)?.get("streams") as? JsonArray).orEmpty().take(MAX_RESPONSE_STREAMS)
        return streams.mapIndexedNotNull { index, element ->
            streamRow(element, addon, index, id)
        }
    }

    /** Subtitle tracks from one add-on. */
    suspend fun subtitles(addon: Addon, type: String, id: String): List<SubtitleTrack> {
        val route = AddonEndpoint.subtitlesURL(addon.manifestURL, type, id) ?: return emptyList()
        val body = okHttp.getText(route, MAX_RESPONSE_BYTES).body
        val tracks = (root(body)?.get("subtitles") as? JsonArray).orEmpty().take(MAX_SUBTITLES)
        return tracks.mapIndexedNotNull { index, element ->
            val entry = element as? JsonObject ?: return@mapIndexedNotNull null
            val url = AddonEndpoint.playableURL(entry.string("url")) ?: return@mapIndexedNotNull null
            SubtitleTrack(
                id = entry.string("id") ?: "${addon.key}:$index",
                url = url,
                language = entry.string("lang") ?: entry.string("language") ?: "und",
                addonName = addon.displayName,
            )
        }
    }

    // MARK: - Parsing

    internal fun parseManifest(body: String): AddonManifest? {
        val root = root(body) ?: return null
        val id = root.string("id") ?: return null
        return AddonManifest(
            id = id,
            name = root.string("name") ?: id,
            version = root.string("version"),
            description = root.string("description")?.take(600),
            logo = root.string("logo"),
            types = root.stringList("types"),
            resources = (root["resources"] as? JsonArray).orEmpty().mapNotNull { element ->
                (element as? JsonPrimitive)?.contentOrNull
                    ?: (element as? JsonObject)?.string("name")
            },
            catalogs = (root["catalogs"] as? JsonArray).orEmpty().mapNotNull { element ->
                val entry = element as? JsonObject ?: return@mapNotNull null
                val type = entry.string("type") ?: return@mapNotNull null
                val catalogID = entry.string("id") ?: return@mapNotNull null
                val extra = (entry["extra"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                AddonCatalog(
                    type = type,
                    id = catalogID,
                    name = entry.string("name") ?: catalogID,
                    extraSupported = extra.mapNotNull { it.string("name") } +
                        entry.stringList("extraSupported"),
                    extraRequired = extra.filter { it.boolean("isRequired") == true }
                        .mapNotNull { it.string("name") } + entry.stringList("extraRequired"),
                    genres = extra.firstOrNull { it.string("name") == "genre" }?.stringList("options")
                        ?: entry.stringList("genres"),
                )
            }.take(MAX_CATALOGS),
            idPrefixes = root.stringList("idPrefixes"),
        )
    }

    internal fun parseMeta(body: String, fallbackType: String): Meta? {
        val root = root(body) ?: return null
        val meta = root["meta"] as? JsonObject ?: root.takeIf { it["id"] != null } ?: return null
        val id = meta.string("id") ?: return null
        val videos = (meta["videos"] as? JsonArray).orEmpty()
            .mapNotNull(::episode)
            .distinctBy { "${it.season}:${it.episode}" }
            .sortedWith(compareBy(Episode::season, Episode::episode))
            .take(MAX_EPISODES)
        return Meta(
            id = id,
            type = meta.string("type") ?: fallbackType,
            title = meta.string("name") ?: id,
            year = meta.string("year")?.take(4)?.toIntOrNull()
                ?: meta.string("releaseInfo")?.take(4)?.toIntOrNull(),
            runtimeMinutes = runtimeMinutes(meta.string("runtime")),
            certification = meta.string("certification"),
            description = meta.string("description"),
            poster = meta.string("poster"),
            backdrop = meta.string("background"),
            logo = meta.string("logo"),
            genres = (meta.stringList("genres") + meta.stringList("genre")).distinct().take(8),
            imdbID = meta.string("imdb_id") ?: id.takeIf { it.startsWith("tt") },
            tmdbID = meta.string("moviedb_id")?.toIntOrNull(),
            imdbRating = meta.string("imdbRating")?.toDoubleOrNull(),
            trailerYouTubeID = trailerID(meta),
            cast = castMembers(meta),
            videos = videos,
            similar = (meta["links"] as? JsonArray).orEmpty().mapNotNull(::similarRef).take(20),
        )
    }

    private fun episode(element: JsonElement): Episode? {
        val entry = element as? JsonObject ?: return null
        val id = entry.string("id").orEmpty()
        val fromID = SEASON_EPISODE.find(id) ?: COLON_EPISODE.find(id)
        val season = entry.int("season") ?: fromID?.groupValues?.getOrNull(1)?.toIntOrNull()
        val episode = entry.int("episode") ?: entry.int("number")
            ?: fromID?.groupValues?.getOrNull(2)?.toIntOrNull()
        if (season == null || episode == null || season !in 0..100 || episode !in 0..2_000) return null
        return Episode(
            season = season,
            episode = episode,
            title = entry.string("title") ?: entry.string("name") ?: "Episode $episode",
            overview = entry.string("overview")?.take(1_000),
            thumbnail = entry.string("thumbnail")?.let(AddonEndpoint::playableURL),
            released = entry.string("released") ?: entry.string("firstAired"),
            id = id.takeIf(String::isNotBlank) ?: "$season:$episode",
        )
    }

    private fun castMembers(meta: JsonObject): List<CastMember> {
        val fromLinks = (meta["links"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { it.string("category").equals("Cast", ignoreCase = true) }
            .mapNotNull { it.string("name")?.let { name -> CastMember(name = name) } }
        if (fromLinks.isNotEmpty()) return fromLinks.take(MAX_CAST)
        return meta.stringList("cast").map { CastMember(name = it) }.take(MAX_CAST)
    }

    private fun similarRef(element: JsonElement): MetaRef? {
        val entry = element as? JsonObject ?: return null
        if (!entry.string("category").equals("similar", ignoreCase = true)) return null
        val url = entry.string("url").orEmpty()
        // Stremio writes a deep link: stremio:///detail/<type>/<id>
        val parts = url.substringAfter("/detail/", "").split('/').filter(String::isNotBlank)
        val type = parts.getOrNull(0) ?: return null
        val id = parts.getOrNull(1) ?: return null
        return MetaRef(id = id, type = type, title = entry.string("name") ?: id)
    }

    private fun trailerID(meta: JsonObject): String? {
        (meta["trailers"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { trailer ->
            trailer.string("source")?.takeIf(String::isNotBlank)?.let { return it }
            trailer.string("ytId")?.takeIf(String::isNotBlank)?.let { return it }
        }
        (meta["trailerStreams"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { trailer ->
            trailer.string("ytId")?.takeIf(String::isNotBlank)?.let { return it }
        }
        return null
    }

    private fun discoverItem(element: JsonElement, catalogID: String): DiscoverItem? {
        val entry = element as? JsonObject ?: return null
        val id = entry.string("id") ?: return null
        val title = entry.string("name") ?: return null
        return DiscoverItem(
            id = id,
            type = entry.string("type") ?: "movie",
            title = title,
            posterURL = entry.string("poster"),
            backdropURL = entry.string("background"),
            description = entry.string("description")?.take(1_200),
            runtime = entry.string("runtime"),
            year = entry.string("year")?.take(4)?.toIntOrNull()
                ?: entry.string("releaseInfo")?.take(4)?.toIntOrNull(),
            rating = entry.string("imdbRating")?.toDoubleOrNull(),
            genres = (entry.stringList("genres") + entry.stringList("genre")).distinct().take(8),
            catalogID = catalogID,
        )
    }

    private fun streamRow(element: JsonElement, addon: Addon, index: Int, requestID: String): StreamRow? {
        val entry = element as? JsonObject ?: return null
        val hints = entry["behaviorHints"] as? JsonObject
        if (hints?.boolean("notWebReady") == true && entry.string("url") != null) return null
        val url = AddonEndpoint.playableURL(entry.string("url"))
        if (url != null && url.contains("/assets/stream-errors/", ignoreCase = true)) return null
        val infoHash = entry.string("infoHash")
            ?.lowercase(Locale.US)
            ?.takeIf { INFO_HASH.matches(it) }
        if (url == null && infoHash == null) return null

        val texts = listOf(
            entry.string("name"),
            entry.string("title"),
            entry.string("description"),
            hints?.string("filename"),
        )
        val facts = StreamFacts.parse(
            texts = texts,
            sizeBytes = hints?.long("videoSize") ?: entry.long("sizeBytes"),
            statedQuality = entry.string("quality"),
        )
        val releaseName = listOfNotNull(
            hints?.string("filename"),
            entry.string("title")?.lineSequence()?.firstOrNull(),
            entry.string("name"),
        ).firstOrNull(String::isNotBlank).orEmpty().take(RELEASE_NAME_LIMIT)

        return StreamRow(
            id = "${addon.key}|$requestID|$index",
            addonName = addon.displayName,
            title = entry.string("name")?.lineSequence()?.firstOrNull()?.trim()?.take(TITLE_LIMIT)
                ?: addon.displayName,
            releaseName = releaseName.ifBlank { "Unnamed release" },
            url = url,
            infoHash = infoHash,
            fileIdx = entry.int("fileIdx"),
            quality = facts.quality,
            sizeBytes = facts.sizeBytes,
            codecs = facts.codecs,
            hdr = facts.hdr,
            audioLanguages = facts.audioLanguages,
            cachedHint = if (url != null && facts.cachedHint.cached.not()) {
                // A direct URL from an add-on IS ready: there is nothing left to acquire.
                CachedHint(cached = true, service = facts.cachedHint.service)
            } else {
                facts.cachedHint
            },
            seeders = facts.seeders,
            headers = proxyHeaders(hints),
            sources = entry.stringList("sources").take(MAX_TRACKERS),
            facts = facts,
        )
    }

    private fun proxyHeaders(hints: JsonObject?): Map<String, String> {
        val request = (hints?.get("proxyHeaders") as? JsonObject)?.get("request") as? JsonObject
            ?: return emptyMap()
        return request.entries.mapNotNull { (name, value) ->
            val text = (value as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            if (name.isBlank() || text.isBlank()) null else name to text.take(1_024)
        }.take(8).toMap()
    }

    private fun runtimeMinutes(raw: String?): Int? {
        val text = raw?.lowercase(Locale.ROOT) ?: return null
        val hours = Regex("""([0-9]+)\s*h""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val minutes = Regex("""([0-9]+)\s*min""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (hours != null || minutes != null) return (hours ?: 0) * 60 + (minutes ?: 0)
        return text.filter(Char::isDigit).toIntOrNull()?.takeIf { it in 1..1_000 }
    }

    private fun root(body: String): JsonObject? =
        runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.boolean(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.int(key: String): Int? =
        (get(key) as? JsonPrimitive)?.let { it.contentOrNull?.toDoubleOrNull()?.toInt() }

    private fun JsonObject.long(key: String): Long? =
        (get(key) as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

    private fun JsonObject.stringList(key: String): List<String> =
        (get(key) as? JsonArray).orEmpty().mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
        }

    companion object {
        /** Always on, always first. The TV has no usable Detail screen without it. */
        const val CINEMETA_MANIFEST: String = "https://v3-cinemeta.strem.io/manifest.json"

        const val MANIFEST_TTL_MILLIS: Long = 24 * 60 * 60 * 1_000L
        const val MAX_RESPONSE_BYTES: Int = 2 * 1_024 * 1_024
        const val MAX_RESPONSE_STREAMS: Int = 500
        const val MAX_CATALOG_ITEMS: Int = 200
        const val MAX_EPISODES: Int = 2_000
        const val MAX_CATALOGS: Int = 200
        const val MAX_SUBTITLES: Int = 100
        const val MAX_CAST: Int = 30
        const val MAX_TRACKERS: Int = 24
        const val CATALOG_FULL_PAGE: Int = 20
        const val TITLE_LIMIT: Int = 180
        const val RELEASE_NAME_LIMIT: Int = 320

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private val SEASON_EPISODE = Regex("""(?i)s(\d{1,3})e(\d{1,4})""")
        private val COLON_EPISODE = Regex(""":(\d{1,3}):(\d{1,4})$""")
        private val INFO_HASH = Regex("""[a-f0-9]{40}|[a-z2-7]{32}""")
    }
}
