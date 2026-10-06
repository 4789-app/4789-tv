package com.fourseveneightnine.tv.client.iptv

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.fourseveneightnine.tv.player.FastPlaybackDns
import java.util.concurrent.TimeUnit

internal data class IptvFetchResult(
    val channels: List<IptvChannel>,
    val vod: List<IptvVod>,
    val episodes: List<IptvVod>,
    val epgUrl: String?,
    val maxConnections: Int? = null,
    val failedVodKinds: Set<String> = emptySet(),
    val programs: List<IptvProgram> = emptyList(),
)

/** Provider formats match the phone client; one source failure never discards another source. */
internal class IptvFetcher(private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }
    private val stalkerSessions = ConcurrentHashMap<String, StalkerSession>()

    suspend fun fetch(source: IptvSource): IptvFetchResult = withContext(Dispatchers.IO) {
        when (source.kind) {
            IptvSourceKind.M3U -> {
                val url = requireNotNull(source.url).takeIf(IptvM3u::httpUrl) ?: error("Invalid playlist URL")
                val request = Request.Builder().url(url).build()
                val parsed = call(request).use { response ->
                    if (!response.isSuccessful) error("Provider HTTP ${response.code}")
                    val reader = response.body?.charStream() ?: error("Empty playlist")
                    reader.use { IptvM3u.parse(source.id, it.buffered().lineSequence()) }
                }
                IptvFetchResult(parsed.channels, parsed.vod, parsed.episodes, source.epgUrl ?: parsed.epgUrl)
            }
            IptvSourceKind.XTREAM -> fetchXtream(source)
            IptvSourceKind.STALKER -> fetchStalker(source)
        }
    }

    private suspend fun fetchXtream(source: IptvSource): IptvFetchResult {
        val base = base(source.host) ?: error("Invalid Xtream host")
        val user = source.username?.takeIf(String::isNotBlank) ?: error("Missing username")
        val pass = source.password?.takeIf(String::isNotBlank) ?: error("Missing password")
        fun api(action: String? = null, categoryId: String? = null,
                limit: Int = 96 * 1024 * 1024): JsonElement = json.parseToJsonElement(get(
            base.newBuilder().addPathSegment("player_api.php")
                .addQueryParameter("username", user).addQueryParameter("password", pass)
                .apply {
                    if (action != null) addQueryParameter("action", action)
                    if (categoryId != null) addQueryParameter("category_id", categoryId)
                }.build().toString(), limit,
        ))
        val userInfo = api().asObject()?.get("user_info")?.asObject()
        val auth = userInfo?.string("auth")
        if (auth != "1") error("IPTV login was rejected")
        val maxConnections = userInfo?.string("max_connections")?.toIntOrNull()?.coerceAtLeast(0)
        val categoryNames = api("get_live_categories").asArray().mapNotNull { element ->
            val row = element.asObject() ?: return@mapNotNull null
            (row.string("category_id") ?: return@mapNotNull null) to (row.string("category_name") ?: "Other")
        }.toMap()
        val channels = api("get_live_streams").asArray().mapNotNull { element ->
            val row = element.asObject() ?: return@mapNotNull null
            val streamId = row.string("stream_id") ?: return@mapNotNull null
            val name = row.string("name")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val group = categoryNames[row.string("category_id")] ?: "Other"
            IptvChannel(
                id = "${source.id}:live:$streamId", sourceId = source.id, name = name, group = group,
                logo = row.string("stream_icon")?.takeIf(IptvM3u::httpUrl),
                epgId = row.string("epg_channel_id")?.takeIf(String::isNotBlank), streamRef = streamId,
                catchupDays = row.string("tv_archive_duration")?.toIntOrNull()?.coerceIn(0, 30)
                    ?: if (row.string("tv_archive") == "1") 1 else 0,
            )
        }
        // Some panels omit VOD entirely. Live TV still works when either VOD request fails.
        var moviePartial = false
        val movieResult = runCatching {
            val categories = categoryMap(api("get_vod_categories"), "category_name")
            // Large all-movies responses can stream for minutes and exceed the call deadline.
            // Xtream's category filter returns bounded responses; keep the three in-flight API
            // calls below the provider's capacity while still loading the whole movie library.
            val rows = if (categories.size >= 32) {
                val slots = Semaphore(3)
                val batches = coroutineScope {
                    categories.keys.map { id -> async(Dispatchers.IO) {
                        slots.withPermit { runCatching {
                            api("get_vod_streams", id, 8 * 1024 * 1024) as? JsonArray
                                ?: error("Invalid category response")
                        } }
                    } }.awaitAll()
                }
                moviePartial = batches.any { it.isFailure }
                val received = batches.mapNotNull { it.getOrNull() }
                if (received.isEmpty()) error("Movie categories did not answer")
                received.flatMap { it }
            } else api("get_vod_streams").asArray()
            rows.mapNotNull { element ->
                val row = element.asObject() ?: return@mapNotNull null
                val id = row.string("stream_id") ?: return@mapNotNull null
                IptvVod(
                    id = "${source.id}:movie:$id", sourceId = source.id, kind = "movie",
                    title = row.string("name") ?: return@mapNotNull null,
                    category = categories[row.string("category_id")] ?: "Movies",
                    image = row.string("stream_icon")?.takeIf(IptvM3u::httpUrl),
                    streamId = id, extension = row.string("container_extension") ?: "mp4",
                    directUrl = row.string("direct_source")?.takeIf(IptvM3u::httpUrl),
                    description = row.string("plot")?.takeIf(String::isNotBlank),
                    year = row.string("releaseDate")?.take(4),
                    genre = row.string("genre")?.takeIf(String::isNotBlank),
                    cast = row.string("cast")?.takeIf(String::isNotBlank),
                    rating = row.string("rating_5based") ?: row.string("rating"),
                )
            }.distinctBy(IptvVod::id)
        }
        val seriesResult = runCatching {
            val categories = categoryMap(api("get_series_categories"), "category_name")
            api("get_series").asArray().mapNotNull { element ->
                val row = element.asObject() ?: return@mapNotNull null
                val id = row.string("series_id") ?: return@mapNotNull null
                IptvVod(
                    id = "${source.id}:series:$id", sourceId = source.id, kind = "series",
                    title = row.string("name") ?: return@mapNotNull null,
                    category = categories[row.string("category_id")] ?: "Series",
                    image = row.string("cover")?.takeIf(IptvM3u::httpUrl), streamId = id,
                    description = row.string("plot")?.takeIf(String::isNotBlank),
                    year = row.string("releaseDate")?.take(4),
                    genre = row.string("genre")?.takeIf(String::isNotBlank),
                    cast = row.string("cast")?.takeIf(String::isNotBlank),
                    rating = row.string("rating_5based") ?: row.string("rating"),
                )
            }
        }
        val failedVodKinds = buildSet {
            if (movieResult.isFailure || moviePartial) add("movie")
            if (seriesResult.isFailure) add("series")
        }
        if (failedVodKinds.isNotEmpty()) {
            com.fourseveneightnine.tv.startup.ReceiverDiagnostics.record("iptv.vod.partial",
                "kinds=${failedVodKinds.joinToString(",")} errors=${listOfNotNull(
                    movieResult.exceptionOrNull()?.javaClass?.simpleName,
                    seriesResult.exceptionOrNull()?.javaClass?.simpleName,
                ).joinToString(",")}")
        }
        val movies = movieResult.getOrDefault(emptyList())
        val series = seriesResult.getOrDefault(emptyList())
        val epg = base.newBuilder().addPathSegment("xmltv.php")
            .addQueryParameter("username", user).addQueryParameter("password", pass).build().toString()
        return IptvFetchResult(channels, movies + series, emptyList(), source.epgUrl ?: epg,
            maxConnections, failedVodKinds)
    }

    suspend fun vodCategory(source: IptvSource, kind: String, name: String): List<IptvVod> =
        withContext(Dispatchers.IO) {
            if (source.kind != IptvSourceKind.XTREAM || kind !in setOf("movie", "series"))
                return@withContext emptyList()
            val base = base(source.host) ?: return@withContext emptyList()
            val user = source.username ?: return@withContext emptyList()
            val pass = source.password ?: return@withContext emptyList()
            fun api(action: String, categoryId: String? = null): JsonElement = json.parseToJsonElement(get(
                base.newBuilder().addPathSegment("player_api.php")
                    .addQueryParameter("username", user).addQueryParameter("password", pass)
                    .addQueryParameter("action", action)
                    .apply { if (categoryId != null) addQueryParameter("category_id", categoryId) }
                    .build().toString(), 8 * 1024 * 1024))
            val movie = kind == "movie"
            val categories = categoryMap(api(if (movie) "get_vod_categories" else "get_series_categories"),
                "category_name")
            val ids = categories.filterValues { it.equals(name, ignoreCase = true) }.keys
            ids.flatMap { id -> api(if (movie) "get_vod_streams" else "get_series", id).asArray() }
                .mapNotNull { element ->
                    val row = element.asObject() ?: return@mapNotNull null
                    val streamId = row.string(if (movie) "stream_id" else "series_id")
                        ?: return@mapNotNull null
                    IptvVod(
                        id = "${source.id}:$kind:$streamId", sourceId = source.id, kind = kind,
                        title = row.string("name") ?: return@mapNotNull null,
                        category = categories[row.string("category_id")] ?: name,
                        image = row.string(if (movie) "stream_icon" else "cover")
                            ?.takeIf(IptvM3u::httpUrl),
                        streamId = streamId,
                        extension = if (movie) row.string("container_extension") ?: "mp4" else null,
                        directUrl = if (movie) row.string("direct_source")?.takeIf(IptvM3u::httpUrl) else null,
                        description = row.string("plot")?.takeIf(String::isNotBlank),
                        year = row.string("releaseDate")?.take(4),
                        genre = row.string("genre")?.takeIf(String::isNotBlank),
                        cast = row.string("cast")?.takeIf(String::isNotBlank),
                        rating = row.string("rating_5based") ?: row.string("rating"),
                    )
                }.distinctBy(IptvVod::id)
        }

    suspend fun seriesDetails(source: IptvSource, series: IptvVod): IptvSeriesDetails = withContext(Dispatchers.IO) {
        if (source.kind == IptvSourceKind.STALKER) return@withContext stalkerSeriesDetails(source, series)
        if (source.kind != IptvSourceKind.XTREAM || series.streamId == null)
            return@withContext IptvSeriesDetails(series, emptyList())
        val base = base(source.host) ?: return@withContext IptvSeriesDetails(series, emptyList())
        fun detailsUrl(path: String) = base.newBuilder().addPathSegment(path)
            .addQueryParameter("username", source.username)
            .addQueryParameter("password", source.password)
            .addQueryParameter("action", "get_series_info")
            .addQueryParameter("series_id", series.streamId).build().toString()
        // Some Xtream panels redirect player_api.php to info_api.php without preserving the
        // configured port. Retry on the original host/port; never forward credentials elsewhere.
        val root = runCatching { json.parseToJsonElement(get(detailsUrl("player_api.php"))).asObject() }
            .getOrNull() ?: json.parseToJsonElement(get(detailsUrl("info_api.php"))).asObject()
            ?: error("The provider did not return series details")
        val info = root["info"].asObject()
        val enriched = series.copy(
            description = info?.string("plot")?.takeIf(String::isNotBlank) ?: series.description,
            year = info?.string("releaseDate")?.take(4) ?: series.year,
            genre = info?.string("genre")?.takeIf(String::isNotBlank) ?: series.genre,
            cast = info?.string("cast")?.takeIf(String::isNotBlank) ?: series.cast,
            rating = info?.string("rating_5based") ?: info?.string("rating") ?: series.rating,
            image = info?.string("cover")?.takeIf(IptvM3u::httpUrl) ?: series.image,
        )
        val seasons = root["episodes"].asObject().orEmpty()
        val episodes = seasons.toList().sortedBy { it.first.toIntOrNull() ?: Int.MAX_VALUE }.flatMap { (season, list) ->
            list.asArray().mapNotNull { element ->
                val row = element.asObject() ?: return@mapNotNull null
                val id = row.string("id") ?: return@mapNotNull null
                val episode = row.string("episode_num") ?: id
                IptvVod(
                    id = "${source.id}:episode:$id", sourceId = source.id, kind = "episode",
                    title = "S$season E$episode · ${row.string("title") ?: series.title}",
                    category = series.title, image = row.string("movie_image")?.takeIf(IptvM3u::httpUrl) ?: series.image,
                    streamId = id, extension = row.string("container_extension") ?: "mp4",
                    description = row["info"].asObject()?.string("plot")?.takeIf(String::isNotBlank),
                    seriesId = series.id,
                    directUrl = row.string("direct_source")?.takeIf(IptvM3u::httpUrl),
                    seasonNumber = season.toIntOrNull(), episodeNumber = episode.toIntOrNull(),
                    released = row["info"].asObject()?.string("releasedate")
                        ?: row["info"].asObject()?.string("releaseDate"),
                )
            }.sortedBy { it.title.substringAfter(" E", "").substringBefore(' ').toIntOrNull() ?: Int.MAX_VALUE }
        }
        IptvSeriesDetails(enriched, episodes)
    }

    fun streamUrl(source: IptvSource, item: IptvVod): String? {
        item.directUrl?.let { return it }
        if (source.kind != IptvSourceKind.XTREAM || item.streamId == null) return null
        val base = base(source.host) ?: return null
        val kind = if (item.kind == "movie") "movie" else "series"
        val user = source.username ?: return null
        val pass = source.password ?: return null
        val ext = item.extension?.takeIf { it.matches(Regex("[a-zA-Z0-9]{2,5}")) } ?: "mp4"
        return base.newBuilder().addPathSegment(kind).addPathSegment(user).addPathSegment(pass)
            .addPathSegment("${item.streamId}.$ext").build().toString()
    }

    fun liveUrl(source: IptvSource, channel: IptvChannel): String? {
        return when (source.kind) {
            IptvSourceKind.M3U -> channel.streamRef
            IptvSourceKind.XTREAM -> {
                val base = base(source.host) ?: return null
                base.newBuilder().addPathSegment("live").addPathSegment(source.username ?: return null)
                    .addPathSegment(source.password ?: return null).addPathSegment("${channel.streamRef}.ts")
                    .build().toString()
            }
            IptvSourceKind.STALKER -> null // minted for each play by resolveStalker()
        }
    }

    fun catchupUrl(source: IptvSource, channel: IptvChannel, program: IptvProgram): String? {
        if (channel.catchupDays <= 0 || program.endMillis <= program.startMillis) return null
        val duration = ((program.endMillis - program.startMillis + 59_999) / 60_000).coerceAtLeast(1)
        return when (source.kind) {
            IptvSourceKind.XTREAM -> {
                val host = base(source.host) ?: return null
                val start = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).format(Date(program.startMillis))
                host.newBuilder().addPathSegment("timeshift")
                    .addPathSegment(source.username ?: return null)
                    .addPathSegment(source.password ?: return null)
                    .addPathSegment(duration.toString())
                    .addEncodedPathSegment(start)
                    .addPathSegment("${channel.streamRef}.ts").build().toString()
            }
            IptvSourceKind.M3U -> channel.catchupSource?.let { template ->
                val value = template.replace("{utc}", (program.startMillis / 1000).toString())
                    .replace("{start}", (program.startMillis / 1000).toString())
                    .replace("{duration}", (duration * 60).toString())
                value.takeIf(IptvM3u::httpUrl)
            }
            IptvSourceKind.STALKER -> null
        }
    }

    private fun fetchStalker(source: IptvSource): IptvFetchResult {
        val session = stalkerSession(source)
        val genres = stalkerRequest(session, "itv", "get_genres").asArray().mapNotNull { element ->
            val row = element.asObject() ?: return@mapNotNull null
            (row.string("id") ?: return@mapNotNull null) to (row.string("title") ?: "Other")
        }.toMap()
        val rows = stalkerRequest(session, "itv", "get_all_channels").asObject()?.get("data").asArray()
        val channels = rows.mapNotNull { element ->
            val row = element.asObject() ?: return@mapNotNull null
            val id = row.string("id") ?: return@mapNotNull null
            val cmd = row.string("cmd")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            IptvChannel(
                id = "${source.id}:live:$id", sourceId = source.id,
                name = row.string("name") ?: "Channel $id",
                group = genres[row.string("tv_genre_id")] ?: "Other",
                logo = row.string("logo")?.takeIf(IptvM3u::httpUrl), streamRef = cmd,
            )
        }
        val failed = mutableSetOf<String>()
        val vod = listOf("vod" to "movie", "series" to "series").flatMap { (type, kind) ->
            try {
                val categories = portalRows(stalkerRequest(session, type, "get_categories")).associate { row ->
                    row.string("id").orEmpty() to (row.string("title") ?: row.string("name") ?: "Other")
                }
                stalkerPages(session, type).mapNotNull { row -> stalkerVod(source, row, type, kind, categories) }
            } catch (_: Exception) { failed += kind; emptyList() }
        }
        val programs = runCatching { stalkerEpg(session, channels) }.getOrDefault(emptyList())
        return IptvFetchResult(channels, vod, emptyList(), source.epgUrl, failedVodKinds = failed, programs = programs)
    }

    suspend fun resolveStalker(source: IptvSource, channel: IptvChannel): String = withContext(Dispatchers.IO) {
        var session = stalkerSession(source)
        fun mint(): String? {
            val cmd = stalkerRequest(session, "itv", "create_link", mapOf("cmd" to channel.streamRef,
                "forced_storage" to "undefined", "disable_ad" to "0")).asObject()?.string("cmd") ?: return null
            return cmd.split(Regex("\\s+")).firstOrNull(IptvM3u::httpUrl)
        }
        runCatching { mint() }.getOrNull()?.let { return@withContext it }
        stalkerSessions.remove(source.id)
        session = stalkerSession(source)
        mint() ?: error("The portal did not return a stream")
    }

    private fun portalRows(value: JsonElement?): List<JsonObject> =
        (value.asObject()?.get("data") ?: value).asArray().mapNotNull { it.asObject() }

    private fun stalkerPages(session: StalkerSession, type: String, extra: Map<String, String> = emptyMap()): List<JsonObject> {
        val rows = ArrayList<JsonObject>()
        val seen = HashSet<String>()
        for (page in 1..1_000) {
            val result = stalkerRequest(session, type, "get_ordered_list", extra + mapOf("p" to "$page", "category" to "*"))
            val batch = portalRows(result)
            val fresh = batch.filter { seen.add(it.string("id") ?: it.toString()) }
            if (fresh.isEmpty()) break
            rows.addAll(fresh)
            val total = result.asObject()?.string("total_items")?.toIntOrNull()
            if (rows.size >= 150_000 || total != null && rows.size >= total) break
            if (total == null && batch.size < (result.asObject()?.string("max_page_items")?.toIntOrNull() ?: batch.size)) break
        }
        return rows.take(150_000)
    }

    private fun stalkerVod(source: IptvSource, row: JsonObject, type: String, kind: String,
        categories: Map<String, String>): IptvVod? {
        val id = row.string("id") ?: return null
        return IptvVod("${source.id}:$kind:$id", source.id, kind,
            row.string("name") ?: row.string("title") ?: "Video $id",
            categories[row.string("category_id")] ?: "Other",
            image = row.string("screenshot_uri")?.takeIf(IptvM3u::httpUrl), streamId = id,
            portalCommand = row.string("cmd"), portalType = type,
            description = row.string("description"), year = row.string("year"),
            cast = row.string("actors"), genre = row.string("genres_str"), rating = row.string("rating_imdb"))
    }

    private fun stalkerSeriesDetails(source: IptvSource, series: IptvVod): IptvSeriesDetails {
        val session = stalkerSession(source)
        val type = series.portalType ?: "series"
        val seasons = stalkerPages(session, type, mapOf("movie_id" to (series.streamId ?: return IptvSeriesDetails(series, emptyList()))))
        val episodes = seasons.flatMapIndexed { index, season ->
            val seasonNumber = season.string("season_number")?.toIntOrNull()
                ?: Regex("\\d+").find(season.string("name").orEmpty())?.value?.toIntOrNull() ?: index + 1
            val numbered = season["series"].asArray()
            if (numbered.isNotEmpty()) numbered.mapNotNull { element ->
                val number = (element as? JsonPrimitive)?.content?.toIntOrNull() ?: return@mapNotNull null
                series.copy(id = "${series.id}:$seasonNumber:$number", kind = "episode",
                    title = "S$seasonNumber E$number · ${series.title}", category = series.title,
                    seriesId = series.id, seasonNumber = seasonNumber, episodeNumber = number,
                    portalCommand = season.string("cmd") ?: series.portalCommand)
            } else {
                stalkerPages(session, type, mapOf("movie_id" to series.streamId, "season_id" to (season.string("id") ?: "$seasonNumber")))
                    .mapIndexedNotNull { episodeIndex, row ->
                        val item = stalkerVod(source, row, type, "episode", emptyMap()) ?: return@mapIndexedNotNull null
                        val number = row.string("series_number")?.toIntOrNull() ?: row.string("episode_num")?.toIntOrNull() ?: episodeIndex + 1
                        item.copy(seriesId = series.id, category = series.title, seasonNumber = seasonNumber, episodeNumber = number,
                            title = "S$seasonNumber E$number · ${item.title}")
                    }
            }
        }
        return IptvSeriesDetails(series, episodes)
    }

    suspend fun resolveStalkerVod(source: IptvSource, item: IptvVod): String = withContext(Dispatchers.IO) {
        fun mint(session: StalkerSession): String? {
            val cmd = item.portalCommand ?: return null
            val result = stalkerRequest(session, item.portalType ?: "vod", "create_link", buildMap {
                put("cmd", cmd); item.episodeNumber?.let { put("series", "$it") }
                put("forced_storage", "undefined"); put("disable_ad", "0")
            }).asObject()?.string("cmd") ?: return null
            val candidate = result.substringAfter(' ', result).trim()
            return candidate.takeIf(IptvM3u::httpUrl)
                ?: session.portal.resolve(candidate)?.toString()?.takeIf(IptvM3u::httpUrl)
        }
        val first = try { mint(stalkerSession(source)) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        first ?: run {
            stalkerSessions.remove(source.id)
            mint(stalkerSession(source)) ?: error("The portal did not return a video link")
        }
    }

    private fun stalkerEpg(session: StalkerSession, channels: List<IptvChannel>): List<IptvProgram> {
        val byId = channels.associateBy { it.id.substringAfterLast(':') }
        val response = stalkerRequest(session, "itv", "get_epg_info", mapOf("period" to "48")).asObject().orEmpty()
        val root = response["data"].asObject() ?: response
        fun programTime(row: JsonObject, timestamp: String, primary: String, alternate: String): Long? {
            row.string(timestamp)?.toLongOrNull()?.takeIf { it > 0 }?.let { return it * 1_000 }
            val raw = row.string(primary) ?: row.string(alternate) ?: return null
            runCatching { java.time.OffsetDateTime.parse(raw.replace(' ', 'T')).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
            return runCatching { java.time.LocalDateTime.parse(raw.replace(' ', 'T'))
                .atZone(java.time.ZoneId.of("Europe/London")).toInstant().toEpochMilli() }.getOrNull()
        }
        return root.entries.flatMap { (id, value) ->
            val channel = byId[id] ?: return@flatMap emptyList()
            (value.asObject()?.let { it["data"] ?: it["epg"] ?: it["items"] } ?: value).asArray().mapNotNull { element ->
                val row = element.asObject() ?: return@mapNotNull null
                val start = programTime(row, "start_timestamp", "start", "time") ?: return@mapNotNull null
                val end = programTime(row, "stop_timestamp", "stop", "time_to") ?: return@mapNotNull null
                val title = row.string("name") ?: return@mapNotNull null
                if (end <= start) null else IptvProgram(channel.id, title, start, end, row.string("descr"))
            }
        }.take(100_000)
    }

    private data class StalkerSession(val portal: HttpUrl, val token: String, val mac: String)

    private fun stalkerSession(source: IptvSource): StalkerSession {
        stalkerSessions[source.id]?.let { return it }
        val base = base(source.host) ?: error("Invalid portal host")
        val mac = source.mac?.takeIf { it.matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")) }
            ?: error("Invalid portal MAC")
        for (path in listOf("portal.php", "stalker_portal/server/load.php", "server/load.php", "c/portal.php")) {
            val portal = base.newBuilder().addEncodedPathSegments(path).build()
            val handshake = runCatching {
                stalkerRequest(StalkerSession(portal, "", mac), "stb", "handshake", mapOf("token" to ""))
                    .asObject()
            }.getOrNull()
            val token = handshake?.string("token")
            if (!token.isNullOrBlank()) {
                val session = StalkerSession(portal, token, mac)
                runCatching { stalkerProfile(session, handshake.string("random").orEmpty()) }
                stalkerSessions[source.id] = session
                return session
            }
        }
        error("The portal did not accept this MAC")
    }

    private fun stalkerProfile(session: StalkerSession, random: String) {
        val mac = session.mac.uppercase(Locale.US)
        val deviceId = sha256(mac)
        val serial = sha256("sn:$mac").take(13).uppercase(Locale.US)
        val metrics = "{\"mac\":\"${session.mac}\",\"sn\":\"$serial\",\"type\":\"STB\",\"model\":\"MAG250\",\"uid\":\"\",\"random\":\"$random\"}"
        stalkerRequest(session, "stb", "get_profile", mapOf(
            "auth_second_step" to if (random.isEmpty()) "0" else "1",
            "hd" to "1", "num_banks" to "2", "sn" to serial,
            "stb_type" to "MAG250", "client_type" to "STB", "image_version" to "218",
            "video_out" to "hdmi", "device_id" to deviceId, "device_id2" to deviceId,
            "signature" to sha256(mac + random), "hw_version" to "1.7-BD-00",
            "not_valid_token" to "0", "metrics" to metrics,
        ))
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }

    private fun stalkerRequest(
        session: StalkerSession, type: String, action: String, extra: Map<String, String> = emptyMap(),
    ): JsonElement {
        val url = session.portal.newBuilder().addQueryParameter("type", type)
            .addQueryParameter("action", action).apply { extra.forEach { (key, value) -> addQueryParameter(key, value) } }
            .addQueryParameter("JsHttpRequest", "1-xml").build()
        val agent = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"
        val request = Request.Builder().url(url).header("User-Agent", agent)
            .header("X-User-Agent", "Model: MAG250; Link: Ethernet")
            .header("Cookie", "mac=${URLEncoder.encode(session.mac, "UTF-8")}; stb_lang=en; timezone=Europe/London")
            .header("Referer", session.portal.newBuilder().encodedPath("/c/").build().toString())
            .apply { if (session.token.isNotEmpty()) header("Authorization", "Bearer ${session.token}") }.build()
        call(request).use { response ->
            if (response.code == 429) error("Portal is rate limited")
            if (!response.isSuccessful) error("Portal HTTP ${response.code}")
            val body = boundedBody(response, 4 * 1024 * 1024)
            val root = json.parseToJsonElement(body).asObject() ?: error("Invalid portal response")
            return root["js"]?.takeUnless { it.toString() == "false" || it.toString() == "null" }
                ?: error("Portal authorization failed")
        }
    }

    private fun categoryMap(element: JsonElement, label: String): Map<String, String> = element.asArray().mapNotNull { item ->
        val row = item.asObject() ?: return@mapNotNull null
        (row.string("category_id") ?: return@mapNotNull null) to (row.string(label) ?: "Other")
    }.toMap()

    private fun base(raw: String?): HttpUrl? {
        val cleaned = raw?.trim()?.takeIf(String::isNotBlank) ?: return null
        val candidate = if ("://" in cleaned) cleaned else "http://$cleaned"
        val url = candidate.toHttpUrlOrNull() ?: return null
        if (url.scheme !in setOf("http", "https")) return null
        return url.newBuilder().encodedPath("/").query(null).fragment(null).build()
    }

    private fun get(url: String, limit: Int = 96 * 1024 * 1024): String {
        val request = Request.Builder().url(url).build()
        call(request).use { response ->
            if (!response.isSuccessful) error("Provider HTTP ${response.code}")
            return boundedBody(response, limit)
        }
    }

    private fun call(request: Request): Response = http.newBuilder()
        .dns(FastPlaybackDns(request.url.host, request.url.port))
        .connectTimeout(3, TimeUnit.SECONDS)
        .build().newCall(request).execute()

    private fun boundedBody(response: Response, limit: Int): String {
        val input = response.body?.byteStream() ?: error("Empty provider response")
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > limit) error("Provider response is too large")
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }
}

private fun JsonElement?.asObject(): JsonObject? = this as? JsonObject
private fun JsonElement?.asArray(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())
private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content?.takeUnless { it == "null" }
