package com.fourseveneightnine.tv.catalog

import com.fourseveneightnine.tv.client.OfflineLetterboxd
import android.os.SystemClock
import android.text.Html
import com.fourseveneightnine.tv.client.data.addons.HttpStatusException
import com.fourseveneightnine.tv.client.data.catalog.Shelf
import com.fourseveneightnine.tv.client.data.catalog.ShelfKind
import com.fourseveneightnine.tv.client.data.library.LibraryRepository
import com.fourseveneightnine.tv.client.data.meta.Meta
import java.time.Duration
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeToSequence
import com.fourseveneightnine.tv.client.data.addons.getText
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.buffer
import okio.source

internal data class TVLetterboxdList(val owner: String, val slug: String, val title: String) {
    val key: String get() = "$owner/$slug"
}

@Serializable
internal data class LetterboxdListInformation(
    val sourceAvailable: Boolean = false,
    val description: String? = null,
    val backdropUrl: String? = null,
    val backdropAsset: String? = null,
    val publishedAt: String? = null,
    val updatedAt: String? = null,
    val likedRank: Int? = null,
    val syncedAtMillis: Long? = null,
)

@Serializable
internal data class TVLetterboxdFilm(val slug: String, val name: String, val poster: String?)
internal data class TVLetterboxdPage<T>(
    val entries: List<T>, val hasNext: Boolean, val total: Int? = null,
    val information: LetterboxdListInformation? = null,
)
internal data class TVLetterboxdIdentity(val type: String, val id: String)
internal data class TVLetterboxdFilmDetails(
    val identity: TVLetterboxdIdentity?,
    val poster: String?,
    val imdbId: String? = null,
    val publicMetadata: Meta? = null,
) {
    fun resolveIdentity(savedType: String, savedId: String?): TVLetterboxdIdentity? =
        identity ?: imdbId?.takeIf { it == savedId && (savedType == "movie" || savedType == "series") }
            ?.let { TVLetterboxdIdentity(savedType, it) }
}

/** Public Letterboxd pages. Fixed origin, no cookies, and one request at a time. */
internal class TVLetterboxdClient(http: OkHttpClient) {
    private val http = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val gate = Mutex()
    private val details = android.util.LruCache<String, TVLetterboxdFilmDetails>(1024)
    private var lastRequest = -1_000L

    suspend fun likedLists(username: String, page: Int): TVLetterboxdPage<TVLetterboxdList> {
        require(validUsername(username) && page > 0)
        return parseLikedLists(fetch(pagePath("/${username.lowercase()}/likes/lists/", page)))
    }

    suspend fun films(list: TVLetterboxdList, page: Int): TVLetterboxdPage<TVLetterboxdFilm> {
        require(validUsername(list.owner) && SLUG.matches(list.slug) && page > 0)
        return parseFilms(fetch(pagePath("/${list.owner}/list/${list.slug}/", page)))
    }

    suspend fun importPage(list: TVLetterboxdList, page: Int): Pair<String?, TVLetterboxdPage<TVLetterboxdFilm>> {
        require(validUsername(list.owner) && LIST_SLUG.matches(list.slug) && page > 0)
        val html = fetch(pagePath("/${list.owner}/list/${list.slug}/", page))
        val name = LIST_TITLE.find(html)?.groupValues?.get(1)?.let(::plainText)?.takeIf { it.isNotBlank() }
        val results = parseFilms(html)
        return name to if (page == 1) results.copy(information = parseListInformation(html)) else results
    }

    suspend fun listInformation(list: TVLetterboxdList): LetterboxdListInformation? {
        require(validUsername(list.owner) && LIST_SLUG.matches(list.slug))
        return parseListInformation(fetch("/${list.owner}/list/${list.slug}/"))
    }

    suspend fun resolveListLink(link: String): TVLetterboxdList? {
        listLink(link)?.let { return it }
        val short = shortListLink(link) ?: return null
        return withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(short).build()).execute().use { response ->
                if (response.code !in 300..399) null
                else response.header("Location")?.let { listLink(it) }
            }
        }
    }

    suspend fun identity(slug: String): TVLetterboxdIdentity? = filmDetails(slug).identity

    fun cachedPortrait(slug: String): String? = details.get(slug)?.poster

    suspend fun filmDetails(slug: String): TVLetterboxdFilmDetails = withContext(Dispatchers.IO) {
        require(SLUG.matches(slug))
        details.get(slug) ?: gate.withLock {
            details.get(slug) ?: fetchLocked("/film/$slug/").let { html ->
                val identity = parseIdentity(html)
                val imdbId = imdbIdentity(html)
                TVLetterboxdFilmDetails(identity, parsePortrait(html), imdbId, parsePublicMetadata(html, slug)).also {
                    if (it.identity != null || it.poster != null || it.imdbId != null) details.put(slug, it)
                }
            }
        }
    }

    private suspend fun fetch(path: String): String = gate.withLock { fetchLocked(path) }

    private suspend fun fetchLocked(path: String): String {
        delay((lastRequest + 1_000L - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        lastRequest = SystemClock.elapsedRealtime()
        val html = http.getText(HOST + path, 2 * 1024 * 1024, headers = mapOf("Accept" to "text/html")).body
        check(!isVerificationPage(html)) { "letterboxd_verification" }
        return html
    }

    private fun pagePath(root: String, page: Int): String = if (page == 1) root else "${root}page/$page/"

    companion object {
        const val FILM_ID_PREFIX = "letterboxd-film:"
        private const val HOST = "https://letterboxd.com"
        private val LIST_SLUG = Regex("[a-zA-Z0-9_-]{1,200}|filmlist:[0-9]{1,24}")
        private val LIST_TITLE = Regex("""<h1\b[^>]*class=["'][^"']*\btitle-1\b[^"']*["'][^>]*>(.*?)</h1>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

        fun importLink(value: String): String? = listLink(value)?.let {
            "$HOST/${it.owner}/list/${it.slug}/"
        } ?: shortListLink(value)

        private fun shortListLink(value: String): String? {
            if (value.length > 512) return null
            val url = value.trim().toHttpUrlOrNull() ?: return null
            if (!url.isHttps || url.host != "boxd.it" || url.port != 443 ||
                url.username.isNotEmpty() || url.password.isNotEmpty()) return null
            val code = url.pathSegments.singleOrNull()?.takeIf { SHORT_CODE.matches(it) } ?: return null
            return "https://boxd.it/$code"
        }

        private val SHORT_CODE = Regex("[a-zA-Z0-9]{1,32}")

        fun listLink(value: String): TVLetterboxdList? {
            if (value.length > 512) return null
            val url = value.trim().toHttpUrlOrNull() ?: return null
            if (!url.isHttps || url.host !in setOf("letterboxd.com", "www.letterboxd.com") || url.port != 443 ||
                url.username.isNotEmpty() || url.password.isNotEmpty()) return null
            val path = url.pathSegments.let { if (it.lastOrNull() == "") it.dropLast(1) else it }
            if (path.size != 3 || path[1] != "list" || !validUsername(path[0]) || !LIST_SLUG.matches(path[2])) return null
            return TVLetterboxdList(path[0].lowercase(), path[2].lowercase(), path[2])
        }

        private val USERNAME = Regex("[a-zA-Z0-9_]{1,32}")
        private val SLUG = Regex("[a-zA-Z0-9_-]{1,200}")
        private val LIST_COUNT = Regex("""<meta\s+[^>]*content=["']A list of ([0-9,]+) films? compiled on Letterboxd""", RegexOption.IGNORE_CASE)
        fun savedList(shelf: Shelf): TVLetterboxdList? {
            if (shelf.kind != ShelfKind.LETTERBOXD || !shelf.id.startsWith("letterboxd:") ||
                shelf.id.startsWith(OfflineLetterboxd.PREFIX)) return null
            val parts = shelf.id.substringAfterLast(':').split('/')
            if (parts.size != 2 || !validUsername(parts[0]) || !SLUG.matches(parts[1])) return null
            return TVLetterboxdList(parts[0], parts[1], shelf.title)
        }

        private val LIST_CARD = Regex(
            """<h2\s+class=["']name[^"']*["'][^>]*>\s*<a\s+href=["']/([a-zA-Z0-9_]{1,32})/list/([a-zA-Z0-9_-]{1,200})/["'][^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val LIKED_TITLE = Regex("""<title>[^<]*liked lists[^<]*Letterboxd</title>""", RegexOption.IGNORE_CASE)
        private val NEXT = Regex("""<a\b[^>]*\b(?:class|rel)=["']next["']""", RegexOption.IGNORE_CASE)
        private val GRID = Regex(
            """<ul\s+class=["']poster-list[^"']*["'][^>]*>(.*?)</ul>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val POSTER_TAG = Regex("""<[a-z]+\b[^>]*\bdata-(?:item|film)-slug=["'][^>]+>""")
        private val BODY = Regex("""<body\b[^>]*>""", RegexOption.IGNORE_CASE)
        private val PROVIDER_LINK = Regex(
            """<a\s+[^>]*\bdata-track-action\s*=\s*(["'])(?:TMDB|IMDb)\1[^>]*>""",
            RegexOption.IGNORE_CASE,
        )
        private val TMDB_LINK = Regex("""https://(?:www\.)?themoviedb\.org/(movie|tv)/([1-9][0-9]*)/?(?:[?#].*)?""")
        private val IMDB_LINK = Regex("""https?://(?:www\.)?imdb\.com/title/(tt[0-9]{5,16})(?:/[^?#]*)?(?:[?#].*)?""")
        private val LINKED_DATA = Regex(
            """<script\s+type=["']application/ld\+json["'][^>]*>(.*?)</script>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val ATTRIBUTE = Regex("""([\w-]+)\s*=\s*(["'])(.*?)\2""")
        private val META_TAG = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)
        private val LIST_DATE = Regex("""<span\b([^>]*)>[^<]*<time\b([^>]*)>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val ISO_DATE = Regex("[0-9T:.Z+-]{10,40}")

        fun parseListInformation(html: String): LetterboxdListInformation? {
            val properties = META_TAG.findAll(html).mapNotNull { tag ->
                val fields = attributes(tag.value)
                fields["property"]?.let { name -> name to fields["content"].orEmpty() }
            }.toMap()
            if (properties["og:type"] != "letterboxd:list") return null
            val width = properties["og:image:width"]?.toIntOrNull() ?: 0
            val height = properties["og:image:height"]?.toIntOrNull() ?: 0
            val dates = LIST_DATE.findAll(html).mapNotNull { match ->
                val kind = attributes(match.groupValues[1])["class"]?.split(Regex("\\s+"))
                    ?.firstOrNull { it == "published" || it == "updated" } ?: return@mapNotNull null
                val time = attributes(match.groupValues[2])["datetime"]?.takeIf(ISO_DATE::matches)
                time?.let { kind to it }
            }.toMap()
            val description = properties["og:description"]?.let(::plainText)?.trim()?.takeIf(String::isNotEmpty)
            return LetterboxdListInformation(
                sourceAvailable = true,
                description = description?.let { text ->
                    if (text.length <= 900) text else text.take(897).substringBeforeLast(' ', text.take(897)) + "…"
                },
                backdropUrl = if (width > height && height > 0) directPortrait(properties["og:image"]?.replace("&amp;", "&")) else null,
                publishedAt = dates["published"], updatedAt = dates["updated"],
            )
        }

        fun parseLikedLists(html: String): TVLetterboxdPage<TVLetterboxdList> {
            val entries = LIST_CARD.findAll(html).map { match ->
                val (owner, slug, title) = match.destructured
                TVLetterboxdList(owner, slug, plainText(title).ifEmpty { slug })
            }.distinctBy(TVLetterboxdList::key).toList()
            check(entries.isNotEmpty() || LIKED_TITLE.containsMatchIn(html)) { "letterboxd_unreadable_index" }
            return TVLetterboxdPage(entries, NEXT.containsMatchIn(html))
        }

        fun parseFilms(html: String): TVLetterboxdPage<TVLetterboxdFilm> {
            val total = LIST_COUNT.find(html)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
            if (total == 0 && !GRID.containsMatchIn(html)) return TVLetterboxdPage(emptyList(), false, 0)
            val grid = GRID.find(html)?.groupValues?.get(1) ?: error("letterboxd_unreadable_list")
            val entries = POSTER_TAG.findAll(grid).mapNotNull { match ->
                val attrs = attributes(match.value)
                val slug = (attrs["data-item-slug"] ?: attrs["data-film-slug"])
                    ?.takeIf(SLUG::matches) ?: return@mapNotNull null
                val title = attrs["data-item-name"] ?: attrs["data-film-name"] ?: attrs["alt"]
                val poster = attrs["data-poster-url"]
                val image = directPortrait(poster) ?: poster?.takeIf { it == "/film/$slug/image-150/" }?.let { HOST + it }
                TVLetterboxdFilm(slug, title?.let(::plainText)?.takeIf(String::isNotEmpty) ?: slug, image)
            }.distinctBy(TVLetterboxdFilm::slug).toList()
            check(entries.isNotEmpty() || total == 0) { "letterboxd_unreadable_list" }
            return TVLetterboxdPage(entries, NEXT.containsMatchIn(html), total)
        }

        fun parseIdentity(html: String): TVLetterboxdIdentity? {
            val attrs = attributes(BODY.find(html)?.value.orEmpty())
            val tmdbId = attrs["data-tmdb-id"]?.toIntOrNull()?.takeIf { it > 0 }
            val type = tmdbMediaType(attrs["data-tmdb-type"])
            if (tmdbId != null && type != null) return TVLetterboxdIdentity(type, "tmdb:$tmdbId")

            return providerLinks(html, "TMDB").mapNotNull { href ->
                val match = TMDB_LINK.matchEntire(href) ?: return@mapNotNull null
                val id = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                val mediaType = tmdbMediaType(match.groupValues[1]) ?: return@mapNotNull null
                TVLetterboxdIdentity(mediaType, "tmdb:$id")
            }.distinct().take(2).toList().singleOrNull()
        }

        fun imdbIdentity(html: String): String? = providerLinks(html, "IMDb").mapNotNull { href ->
            IMDB_LINK.matchEntire(href)?.groupValues?.get(1)
        }.distinct().take(2).toList().singleOrNull()

        private fun providerLinks(html: String, provider: String): Sequence<String> =
            PROVIDER_LINK.findAll(html).mapNotNull { match ->
                val attrs = attributes(match.value)
                attrs["href"].takeIf { attrs["data-track-action"].equals(provider, ignoreCase = true) }
            }

        private fun tmdbMediaType(value: String?): String? = when (value) {
            "tv" -> "series"
            "movie" -> "movie"
            else -> null
        }

        fun titleLookupMessage(error: Throwable?): String = when ((error as? HttpStatusException)?.code) {
            404, 410 -> "This title is unavailable on Letterboxd. Try Search."
            else -> if (error == null) "No match for this Letterboxd title. Try Search."
                else "Couldn’t load this title from Letterboxd. Try again."
        }

        // The social sharing image can be a landscape backdrop. The film's linked data gives the
        // portrait directly from the artwork CDN, even when the image redirect is unavailable.
        fun parsePortrait(html: String): String? = linkedData(html).firstNotNullOfOrNull {
            directPortrait((it["image"] as? JsonPrimitive)?.contentOrNull)
        }

        fun parsePublicMetadata(html: String, slug: String): Meta? {
            val data = linkedData(html).firstOrNull {
                (it["@type"] as? JsonPrimitive)?.contentOrNull in setOf("Movie", "TVSeries", "TVSeason")
            } ?: return null
            fun string(key: String) = (data[key] as? JsonPrimitive)?.contentOrNull?.let(::plainText)?.takeIf(String::isNotEmpty)
            fun strings(key: String) = (data[key] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(::plainText) }.distinct()
            fun names(key: String) = (data[key] as? JsonArray).orEmpty().mapNotNull { child ->
                ((child as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull?.let(::plainText)
            }.distinct()
            val title = string("name") ?: return null
            val date = string("datePublished") ?: string("dateCreated")
            val languages = strings("inLanguage")
            val runtime = string("duration")?.let { value ->
                runCatching { Duration.parse(value).toMinutes().toInt().takeIf { it > 0 } }.getOrNull()
            }
            return Meta("$FILM_ID_PREFIX$slug", if (string("@type") == "Movie") "movie" else "series", title,
                year = date?.take(4)?.toIntOrNull(), description = string("description"),
                poster = directPortrait(string("image")), runtimeMinutes = runtime,
                genres = strings("genre"), originalLanguage = languages.firstOrNull(), spokenLanguages = languages,
                countries = names("countryOfOrigin"), castNames = names("actor").take(200),
                directors = names("director").take(50), studios = names("productionCompany").take(50),
                releaseDate = date?.takeIf { it.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) })
        }

        private fun linkedData(html: String): Sequence<JsonObject> = LINKED_DATA.findAll(html).mapNotNull { match ->
            val json = match.groupValues[1].trim().removePrefix("/* <![CDATA[ */").removeSuffix("/* ]]> */").trim()
            runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonObject
        }

        fun isPosterLookup(url: String): Boolean = url.toHttpUrlOrNull()?.let {
            it.host == "letterboxd.com" && it.encodedPath.startsWith("/film/") && it.encodedPath.endsWith("/image-150/")
        } == true

        fun directPortrait(url: String?): String? =
            url?.toHttpUrlOrNull()?.takeIf { it.isHttps && it.host == "a.ltrbxd.com" && it.port == 443 &&
                it.username.isEmpty() && it.password.isEmpty() }?.toString()

        private fun attributes(tag: String): Map<String, String> =
            ATTRIBUTE.findAll(tag).associate { it.groupValues[1].lowercase() to it.groupValues[3] }

        private fun plainText(value: String): String =
            if ('<' !in value && '&' !in value) value.trim()
            else Html.fromHtml(value, Html.FROM_HTML_MODE_COMPACT).toString().trim()

        // Public 200 responses also load Cloudflare's JavaScript diagnostics. The list, profile
        // and film parsers still validate their own content before publishing anything.
        fun isVerificationPage(html: String): Boolean =
            html.contains("/cdn-cgi/challenge-platform/") && !html.contains("s.ltrbxd.com")

        fun validUsername(value: String): Boolean = USERNAME.matches(value)
    }
}

/** A TV refresh reads only the public lists already in Collections. Each complete list is its own checkpoint. */
internal class TVLetterboxdListRefresh(
    private val directory: File,
    private val scope: CoroutineScope,
    private val page: suspend (TVLetterboxdList, Int) -> TVLetterboxdPage<TVLetterboxdFilm>,
    private val now: () -> Long = System::currentTimeMillis,
    private val runCatalogWork: suspend (suspend () -> Unit) -> Unit = { work -> work() },
) {
    @Serializable
    data class Stamp(val key: String, val checkedAt: Long, val count: Int, val preview: List<TVLetterboxdFilm> = emptyList())

    @Serializable
    private data class Batch(val started: Long, val keys: Set<String>)

    data class State(
        val stamps: Map<String, Stamp> = emptyMap(),
        val running: Boolean = false,
        val pending: Boolean = false,
        val currentKey: String? = null,
        val failedKeys: Set<String> = emptySet(),
        val message: String? = null,
    )
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private val gate = Mutex()
    private var active: Job? = null
    private var clearing = false
    @Volatile private var hydrated = false
    private var resumedKeys: Set<String> = emptySet()
    private val batchFile get() = File(directory, "pending")
    private fun file(list: TVLetterboxdList) = File(File(directory, list.owner), "${list.slug}.list")

    @OptIn(ExperimentalSerializationApi::class)
    private fun readStamp(file: File): Stamp? = runCatching {
        file.source().buffer().use { source ->
            val stamp = Json.decodeFromString<Stamp>(source.readUtf8LineStrict())
            val coverCount = minOf(stamp.count, LibraryRepository.PREVIEW_POSTERS)
            if (stamp.preview.size >= coverCount) return@use stamp
            // Old copies wrote four previews. Read only the additional prefix from the saved films.
            val preview = runCatching {
                Json.decodeToSequence<TVLetterboxdFilm>(source.inputStream(), DecodeSequenceMode.ARRAY_WRAPPED)
                    .take(coverCount).toList()
            }.getOrDefault(stamp.preview)
            if (preview.size > stamp.preview.size) stamp.copy(preview = preview) else stamp
        }
    }.getOrNull()

    private fun hydrateLocked() {
        if (hydrated) return
        val stamps = directory.walkTopDown().filter { it.isFile && it.extension == "list" }
            .mapNotNull(::readStamp).associateBy(Stamp::key)
        mutable.value = State(stamps, pending = batchFile.isFile)
        hydrated = true
    }

    suspend fun hydrate() {
        if (!hydrated) withContext(Dispatchers.IO) { gate.withLock { hydrateLocked() } }
    }

    suspend fun films(list: TVLetterboxdList): List<TVLetterboxdFilm>? = withContext(Dispatchers.IO) {
        runCatching {
            file(list).bufferedReader().use { input ->
                val stamp = Json.decodeFromString<Stamp>(input.readLine())
                Json.decodeFromString<List<TVLetterboxdFilm>>(input.readLine()).also {
                    check(stamp.key == list.key && stamp.count == it.size)
                }
            }
        }.getOrNull()
    }

    suspend fun firstPage(list: TVLetterboxdList): TVLetterboxdPage<TVLetterboxdFilm> =
        withContext(Dispatchers.IO) { page(list, 1) }

    /** Reopening Collections resumes an interrupted batch. Retry skips lists saved during that batch. */
    @Synchronized
    fun refresh(
        shelves: List<Shelf>,
        bulk: Boolean,
        resume: Boolean = false,
        firstPage: TVLetterboxdPage<TVLetterboxdFilm>? = null,
    ): Job? {
        if (clearing) return null
        if (active?.isActive == true) return active
        val lists = shelves.sortedBy { it.items.isNotEmpty() }.mapNotNull(TVLetterboxdClient::savedList)
            .distinctBy(TVLetterboxdList::key)
        if (lists.isEmpty()) return null
        active = scope.launch(Dispatchers.IO) {
            val keys = lists.mapTo(mutableSetOf(), TVLetterboxdList::key)
            var attempted = false
            runCatalogWork {
                gate.withLock {
                    hydrateLocked()
                    if (!attempted && resume && (!batchFile.isFile || resumedKeys == keys)) return@withLock
                    resumedKeys = keys
                    val resumeBatch = resume || attempted
                    attempted = true
                    try {
                        runBatch(lists, bulk, resumeBatch, firstPage.takeIf { lists.size == 1 && !bulk })
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        mutable.value = mutable.value.copy(message = "Couldn’t save the refresh. Try again.")
                    } finally {
                        mutable.value = mutable.value.copy(running = false, currentKey = null)
                    }
                }
            }
        }
        return active
    }

    private suspend fun runBatch(
        available: List<TVLetterboxdList>,
        bulk: Boolean,
        resume: Boolean,
        firstPage: TVLetterboxdPage<TVLetterboxdFilm>?,
    ) {
        val previous = if (bulk && batchFile.isFile) Json.decodeFromString<Batch>(batchFile.readText()) else null
        val keys = if (resume && previous != null) previous.keys else available.map { it.key }.toSet()
        val batch = Batch(previous?.started ?: now(), keys)
        if (bulk) {
            atomicWrite(batchFile) { it.write(Json.encodeToString(batch)) }
            mutable.value = mutable.value.copy(pending = true, failedKeys = emptySet())
        }
        val lists = available.filter { it.key in keys }
        val todo = lists.filter { !bulk || (mutable.value.stamps[it.key]?.checkedAt ?: 0) < batch.started }
        var saved = if (bulk) keys.count { (mutable.value.stamps[it]?.checkedAt ?: 0) >= batch.started } else 0
        val total = keys.size
        var failed = 0
        var accessDenied = false
        for (list in todo) {
            currentCoroutineContext().ensureActive()
            mutable.value = mutable.value.copy(running = true, currentKey = list.key, message = "Refreshing $saved of $total · ${list.title}")
            try {
                val films = allFilms(list, firstPage)
                currentCoroutineContext().ensureActive()
                val stamp = Stamp(list.key, now(), films.size, films.take(LibraryRepository.PREVIEW_POSTERS))
                atomicWrite(file(list)) { out ->
                    out.write(Json.encodeToString(stamp))
                    out.newLine()
                    out.write(Json.encodeToString(films))
                }
                mutable.value = mutable.value.copy(stamps = mutable.value.stamps + (list.key to stamp),
                    failedKeys = mutable.value.failedKeys - list.key)
                saved++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failed++
                mutable.value = mutable.value.copy(failedKeys = mutable.value.failedKeys + list.key)
                val status = (error as? HttpStatusException)?.code
                if (status == 403 || error.message == "letterboxd_verification") accessDenied = true
                if (status == 429) {
                    mutable.value = mutable.value.copy(message = "$saved of $total refreshed. Letterboxd asked the TV to slow down. Retry unfinished later.")
                    return
                }
            }
        }
        if (bulk && saved == total) Files.deleteIfExists(batchFile.toPath())
        mutable.value = mutable.value.copy(pending = batchFile.isFile, message = when {
            saved == total -> "Refreshed $saved of $total lists."
            accessDenied -> "$saved of $total refreshed · $failed need retry. For restricted lists, sync all profiles on your paired iPhone."
            failed > 0 -> "$saved of $total refreshed · $failed need retry."
            else -> "$saved of $total refreshed. Waiting for the saved list directory."
        })
    }

    private suspend fun allFilms(
        list: TVLetterboxdList,
        firstPage: TVLetterboxdPage<TVLetterboxdFilm>?,
    ): List<TVLetterboxdFilm> {
        val films = linkedMapOf<String, TVLetterboxdFilm>()
        var expected: Int? = null
        for (number in 1..1_000) {
            val result = try {
                if (number == 1 && firstPage != null) firstPage else page(list, number)
            } catch (error: Exception) {
                if (error !is IOException && ((error as? HttpStatusException)?.code ?: 0) !in 500..599) throw error
                delay(1_000)
                page(list, number)
            }
            val before = films.size
            result.entries.forEach { films.putIfAbsent(it.slug, it) }
            if (number == 1) expected = result.total
            if (!result.hasNext) {
                check((expected == null || films.size == expected) && (films.size > before || (number == 1 && expected == 0))) {
                    "letterboxd_incomplete_list"
                }
                return films.values.toList()
            }
            check(films.size > before) { "letterboxd_repeated_page" }
        }
        error("letterboxd_page_limit")
    }

    private fun atomicWrite(target: File, write: (java.io.BufferedWriter) -> Unit) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        try {
            temp.bufferedWriter().use(write)
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
    }

    suspend fun clear() {
        val old = synchronized(this) { clearing = true; active }
        try {
            old?.cancelAndJoin()
            withContext(Dispatchers.IO) {
                gate.withLock {
                    check(directory.deleteRecursively()) { "letterboxd_cache_clear_failed" }
                    mutable.value = State()
                    resumedKeys = emptySet()
                    hydrated = true
                }
            }
        } finally {
            synchronized(this) { clearing = false }
        }
    }
}
