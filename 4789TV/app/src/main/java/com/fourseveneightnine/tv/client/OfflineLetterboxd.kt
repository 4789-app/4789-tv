package com.fourseveneightnine.tv.client

import android.app.Application
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.AtomicFile
import com.fourseveneightnine.tv.client.data.catalog.CatalogItem
import com.fourseveneightnine.tv.client.data.catalog.Shelf
import com.fourseveneightnine.tv.client.data.catalog.ShelfKind
import com.fourseveneightnine.tv.client.ui.screens.home.HomeCard
import com.fourseveneightnine.tv.catalog.LetterboxdListInformation
import com.fourseveneightnine.tv.client.data.meta.Meta
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString.Companion.encodeUtf8

/** The install seed has no account data and opens read-only. Only the selected list enters memory. */
internal class OfflineLetterboxd(
    private val app: Application,
    private val scope: CoroutineScope,
    private val http: OkHttpClient,
) {
    data class Directory(
        val shelves: List<Shelf>, val counts: Map<String, Int>,
        val information: Map<String, LetterboxdListInformation> = emptyMap(),
    ) {
        companion object { val Empty = Directory(emptyList(), emptyMap()) }
    }

    data class PosterDownload(val done: Int = 0, val total: Int = 0, val message: String? = null, val running: Boolean = false)

    val imports = OfflineLetterboxdImportStore(app)
    private val mutableRevision = MutableStateFlow(0)
    val revision = mutableRevision.asStateFlow()

    fun importChanged() {
        mutableRevision.value += 1
        if (!mutableDownload.value.running) mutableDownload.value = PosterDownload()
    }

    private val imageHttp by lazy { http.newBuilder().followRedirects(false).followSslRedirects(false).build() }
    private val openGate = Mutex()
    private var database: SQLiteDatabase? = null
    private var hasAsset = true
    private val posterFiles = File(app.filesDir, "letterboxd-offline-posters")
    private val packagedPosters by lazy { app.assets.list("$ASSET/posters").orEmpty().toHashSet() }
    private val bundledInformation by lazy {
        try {
            app.assets.open("$ASSET/list-information.json").bufferedReader().use {
                Json.decodeFromString<Map<String, LetterboxdListInformation>>(it.readText())
            }
        } catch (_: FileNotFoundException) {
            emptyMap()
        }
    }
    private val mutableDownload = MutableStateFlow(PosterDownload())
    val download = mutableDownload.asStateFlow()
    private var downloadJob: Job? = null

    suspend fun available(): Boolean = withContext(Dispatchers.IO) {
        try {
            app.assets.open("$ASSET/catalog.sqlite").use { true }
        } catch (_: FileNotFoundException) {
            imports.hasLists()
        }
    }

    suspend fun directory(localArtwork: Boolean = true): Directory = withContext(Dispatchers.IO) {
        val local = imports.audits()
        val information = bundledInformation.toMutableMap()
        imports.information().forEach { (key, newer) ->
            information[key] = mergeInformation(bundledInformation[key], newer)
        }
        val counts = mutableMapOf<String, Int>()
        val updated = local.map { row ->
            val id = PREFIX + row.key
            counts[id] = row.saved
            val covers = imports.films(row.key, 10).orEmpty().map { film ->
                CatalogItem(film.id, film.media, film.title, year = film.year,
                    posterUrl = importedPoster(film.poster, localArtwork))
            }
            Shelf(id, row.title, ShelfKind.LETTERBOXD, covers, "imported-letterboxd", 0L)
        }
        val db = open() ?: return@withContext Directory(updated, counts, information)
        val shelves = db.rawQuery("SELECT id,name,count FROM list ORDER BY count DESC,name COLLATE NOCASE,id", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val key = cursor.getString(0)
                    val id = PREFIX + key
                    if (id in counts) continue
                    val count = cursor.getInt(2)
                    counts[id] = count
                    val covers = db.rawQuery(
                        """SELECT title.media,title.canonical,title.name,title.year,title.poster
                           FROM member JOIN title ON title.id=member.title_id
                           WHERE member.list_id=? AND member.position<10 ORDER BY member.position""",
                        arrayOf(key),
                    ).use { cover ->
                        buildList {
                            while (cover.moveToNext()) {
                                add(CatalogItem(cover.getString(1), cover.getString(0), cover.getString(2),
                                    year = cover.optionalInt(3), posterUrl = poster(cover.getString(4), localArtwork)))
                            }
                        }
                    }
                    add(Shelf(id, cursor.getString(1), ShelfKind.LETTERBOXD, covers, GENERATION, 0L))
                }
            }
        }
        Directory(updated + shelves, counts, information)
    }

    suspend fun cards(shelfId: String): List<HomeCard> = withContext(Dispatchers.IO) {
        if (!shelfId.startsWith(PREFIX)) return@withContext emptyList()
        imports.films(shelfId.removePrefix(PREFIX), Int.MAX_VALUE)?.let { films ->
            return@withContext films.mapIndexed { position, film ->
                val facts = film.facts
                facts.card(film.id, film.media, film.title, film.year, importedPoster(film.poster), "$shelfId:$position")
            }
        }
        val db = open() ?: return@withContext emptyList()
        db.rawQuery(
            """SELECT title.media,title.canonical,title.name,title.year,title.poster,title.facts,member.position
               FROM member JOIN title ON title.id=member.title_id WHERE member.list_id=? ORDER BY member.position""",
            arrayOf(shelfId.removePrefix(PREFIX)),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    if (cursor.position % 256 == 0) coroutineContext.ensureActive()
                    val id = cursor.getString(1)
                    val facts = Json.decodeFromString<Brief>(cursor.getString(5))
                    add(facts.card(id, cursor.getString(0), cursor.getString(2), cursor.optionalInt(3),
                        poster(cursor.getString(4)), "$shelfId:${cursor.getInt(6)}"))
                }
            }
        }
    }

    suspend fun listInformation(key: String): LetterboxdListInformation? = withContext(Dispatchers.IO) {
        val bundled = bundledInformation[key]
        imports.information()[key]?.let { mergeInformation(bundled, it) } ?: bundled
    }

    private fun mergeInformation(bundled: LetterboxdListInformation?, newer: LetterboxdListInformation): LetterboxdListInformation {
        val content = if (newer.sourceAvailable) newer else bundled ?: newer
        return content.copy(likedRank = if (bundled != null) bundled.likedRank else newer.likedRank,
            syncedAtMillis = newer.syncedAtMillis ?: bundled?.syncedAtMillis)
    }

    suspend fun saveListInformation(key: String, information: LetterboxdListInformation) = withContext(Dispatchers.IO) {
        cacheBackdrop(information)
        imports.saveInformation(key, information)
    }

    suspend fun cacheBackdrop(information: LetterboxdListInformation) = withContext(Dispatchers.IO) {
        val url = publicArtwork(information.backdropUrl) ?: return@withContext
        val file = backdropFile(url)
        if (!file.isFile) fetchImage(url, file, maxWidth = 2048)
    }

    suspend fun backdrop(information: LetterboxdListInformation): String? = withContext(Dispatchers.IO) {
        val asset = information.backdropAsset?.takeIf { BACKDROP_ASSET.matches(it) }
        val url = publicArtwork(information.backdropUrl)
        val local = url?.let(::backdropFile)?.takeIf(File::isFile)
        when {
            local != null -> Uri.fromFile(local).toString()
            asset != null -> "file:///android_asset/$ASSET/backdrops/$asset"
            else -> url
        }
    }

    private fun backdropFile(url: String) = File(posterFiles, "backdrop-${url.encodeUtf8().sha256().hex()}.img")

    suspend fun audits(): List<OfflineListAudit> = withContext(Dispatchers.IO) {
        val saved = imports.audits()
        val overridden = saved.mapTo(HashSet()) { it.key }
        val db = open() ?: return@withContext saved
        val bundled = db.rawQuery("""SELECT list.id,list.name,list.count,
            SUM(CASE WHEN title.canonical NOT LIKE 'letterboxd-film:%' THEN 1 ELSE 0 END),
            SUM(CASE WHEN title.canonical NOT LIKE 'letterboxd-film:%' AND title.facts='{}' THEN 1 ELSE 0 END)
            FROM list LEFT JOIN member ON member.list_id=list.id LEFT JOIN title ON title.id=member.title_id
            GROUP BY list.id ORDER BY list.name COLLATE NOCASE""", null).use { rows ->
            buildList {
                while (rows.moveToNext()) {
                    val key = rows.getString(0)
                    if (key in overridden) continue
                    val count = rows.getInt(2)
                    val verified = rows.getInt(3)
                    add(OfflineListAudit(key, rows.getString(1), count, verified, rows.getInt(4),
                        count - verified, 0, imported = false))
                }
            }
        }
        (saved + bundled).sortedBy { it.title.lowercase() }
    }

    suspend fun issues(row: OfflineListAudit): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        if (row.imported) return@withContext imports.issues(row.key)
        val db = open() ?: return@withContext emptyList()
        db.rawQuery("""SELECT title.name FROM member JOIN title ON title.id=member.title_id
            WHERE member.list_id=? AND title.canonical LIKE 'letterboxd-film:%'
            ORDER BY member.position LIMIT 40""", arrayOf(row.key)).use { rows ->
            buildList { while (rows.moveToNext()) add("Unverified" to rows.getString(0)) }
        }
    }

    suspend fun cachedFilms(slugs: List<String>): Map<String, ImportedFilm> = withContext(Dispatchers.IO) {
        val cached = imports.resolved(slugs).toMutableMap()
        val remaining = slugs.filterNot(cached::containsKey)
        if (remaining.isEmpty()) return@withContext cached
        val db = open() ?: return@withContext cached
        val markers = remaining.joinToString(",") { "?" }
        db.rawQuery("""SELECT member.slug,title.canonical,title.media,title.name,title.year,title.poster,title.facts
            FROM member JOIN title ON title.id=member.title_id WHERE member.slug IN ($markers)
            AND title.canonical NOT LIKE 'letterboxd-film:%' AND title.facts<>'{}'""", remaining.toTypedArray()).use { rows ->
            while (rows.moveToNext()) {
                val slug = rows.getString(0)
                val facts = Json.decodeFromString<Brief>(rows.getString(6))
                cached.putIfAbsent(slug, ImportedFilm(slug, rows.getString(1), rows.getString(2), rows.getString(3),
                    rows.optionalInt(4), poster(rows.getString(5)), facts,
                    if (facts.genres.isNotEmpty()) ImportQuality.Matched else ImportQuality.Limited))
            }
        }
        cached
    }

    suspend fun importedDetail(type: String, id: String): Meta? = withContext(Dispatchers.IO) {
        imports.detail(type, id)?.let { detail -> detail.copy(poster = importedPoster(detail.poster)) }
    }

    suspend fun cacheImportedPoster(url: String): Boolean = withContext(Dispatchers.IO) {
        val remote = publicArtwork(url) ?: return@withContext url.startsWith(ASSET_POSTER_PREFIX)
        val file = importedPosterFile(remote)
        file.isFile || fetchImage(remote, file)
    }

    private fun importedPoster(url: String?, local: Boolean = true): String? {
        if (url == null) return null
        if (url.startsWith(ASSET_POSTER_PREFIX)) return url.takeIf { local }
        val remote = publicArtwork(url) ?: return null
        val saved = importedPosterFile(remote)
        return if (local && saved.isFile) Uri.fromFile(saved).toString() else remote
    }

    private fun importedPosterFile(url: String): File {
        val key = url.encodeUtf8().sha256().hex()
        return File(posterFiles, "import-$key.img")
    }

    fun toggleDownload() {
        if (downloadJob?.isCompleted == false) {
            if (downloadJob?.isActive == true) downloadJob?.cancel()
            return
        }
        val generation = mutableRevision.value
        mutableDownload.value = PosterDownload(running = true)
        downloadJob = scope.launch(Dispatchers.IO) {
            try {
                val db = open()
                val paths = db?.rawQuery("SELECT DISTINCT poster FROM title WHERE poster IS NOT NULL ORDER BY poster", null)?.use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) cursor.getString(0).takeIf(POSTER_PATH::matches)?.let(::add)
                    }
                }.orEmpty()
                val urls = imports.artworkUrls().mapNotNull(::publicArtwork)
                val total = paths.size + urls.size
                val pending = paths.filterNot { localPoster(it) != null } +
                    urls.filterNot { importedPosterFile(it).isFile }
                var done = total - pending.size
                for (batch in pending.chunked(12)) {
                    val saved = coroutineScope {
                        batch.chunked(3).map { worker ->
                            async {
                                worker.count { path ->
                                    coroutineContext.ensureActive()
                                    if (path.startsWith("/")) fetchPoster(path) else cacheImportedPoster(path)
                                }
                            }
                        }.awaitAll().sum()
                    }
                    done += saved
                    mutableDownload.value = PosterDownload(done, total, running = true)
                    if (saved == 0) break
                }
                mutableDownload.value = if (mutableRevision.value != generation) {
                    PosterDownload(message = "The saved catalog changed. Select Retry to include its new posters.")
                } else {
                    PosterDownload(done, total,
                        if (done == total) "Offline posters downloaded" else "Some posters could not download. Select Retry to continue.")
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                mutableDownload.value = mutableDownload.value.copy(running = false, message = "Poster download paused")
                throw cancelled
            } catch (_: Exception) {
                mutableDownload.value = mutableDownload.value.copy(running = false, message = "Poster download couldn’t start. Try again.")
            }
        }
    }

    private fun fetchPoster(path: String): Boolean =
        localPoster(path) != null || fetchImage("https://image.tmdb.org/t/p/w185$path", File(posterFiles, posterBase(path) + ".img"))

    private fun fetchImage(url: String, target: File, maxWidth: Int = 1024): Boolean {
        return try {
            val request = Request.Builder().url(url).apply {
                if (url.toHttpUrlOrNull()?.host == "a.ltrbxd.com") header("Referer", "https://letterboxd.com/")
            }.build()
            imageHttp.newCall(request).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null || body.contentType()?.type != "image") return false
                val source = body.source()
                if (source.request(POSTER_LIMIT + 1L)) return false
                val bytes = source.readByteArray()
                val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, size)
                if (size.outWidth !in 1..maxWidth || size.outHeight !in 1..2048) return false
                synchronized(posterFiles) {
                    if (target.isFile) return true
                    posterFiles.mkdirs()
                    val file = AtomicFile(target)
                    val output = file.startWrite()
                    try {
                        output.write(bytes)
                        file.finishWrite(output)
                        true
                    } catch (_: IOException) {
                        file.failWrite(output)
                        false
                    }
                }
            }
        } catch (_: IOException) {
            false
        }
    }

    private fun poster(path: String?, local: Boolean = true): String? = path?.takeIf(POSTER_PATH::matches)?.let {
        (if (local) localPoster(it) else null) ?: "https://image.tmdb.org/t/p/w342$it"
    }

    private fun localPoster(path: String): String? {
        val base = posterBase(path)
        if ("$base.webp" in packagedPosters) return "file:///android_asset/$ASSET/posters/$base.webp"
        val file = File(posterFiles, "$base.img")
        return file.takeIf(File::isFile)?.let { Uri.fromFile(it).toString() }
    }

    private fun posterBase(path: String): String = path.substringAfter('/').substringBefore('.')

    private suspend fun open(): SQLiteDatabase? = openGate.withLock {
        database?.let { return it }
        if (!hasAsset) return null
        val install = app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime
        val file = AtomicFile(File(app.noBackupFilesDir, "letterboxd-offline-$install.sqlite"))
        if (!file.baseFile.isFile) {
            val asset = try {
                app.assets.open("$ASSET/catalog.sqlite")
            } catch (_: FileNotFoundException) {
                hasAsset = false
                return null
            }
            asset.use { input ->
                val output = file.startWrite()
                try {
                    input.copyTo(output)
                    file.finishWrite(output)
                } catch (failure: IOException) {
                    file.failWrite(output)
                    throw failure
                }
            }
        }
        SQLiteDatabase.openDatabase(file.baseFile.path, null, SQLiteDatabase.OPEN_READONLY).also { opened ->
            database = opened
            app.noBackupFilesDir.listFiles()?.filter {
                it != file.baseFile && it.name.matches(Regex("letterboxd-offline-[0-9]+\\.sqlite"))
            }?.forEach { it.delete() }
        }
    }

    @Serializable
    data class Brief(
        val imdb: String? = null, val overview: String? = null, val releaseDate: String? = null,
        val runtime: Int? = null, val tmdbRating: Double? = null, val imdbRating: Double? = null,
        val votes: Int? = null, val popularity: Double? = null, val language: String? = null,
        val countries: List<String> = emptyList(), val spokenLanguages: List<String> = emptyList(),
        val genres: List<String> = emptyList(), val cast: List<String> = emptyList(),
        val directors: List<String> = emptyList(), val studios: List<String> = emptyList(), val keywords: List<String> = emptyList(),
        val status: String? = null, val certification: String? = null, val hasTrailer: Boolean = false,
        val backdrop: String? = null, val logo: String? = null,
    ) {
        fun card(id: String, media: String, name: String, year: Int?, poster: String?, key: String) = HomeCard(
            id = id, type = media, title = name, posterUrl = poster, year = year,
            runtimeMinutes = runtime, genre = genres.firstOrNull(), genres = genres,
            rating = imdbRating ?: tmdbRating, overview = overview, backdropUrl = backdrop, logoUrl = logo,
            imdbID = imdb, tmdbID = id.removePrefix("tmdb:").toIntOrNull(),
            originalLanguage = language, countries = countries, castNames = cast, directors = directors,
            studios = studios, keywords = keywords, spokenLanguages = spokenLanguages, releaseStatus = status,
            releaseDate = releaseDate, tmdbVotes = votes, popularity = popularity, certification = certification,
            imdbRating = imdbRating, tmdbRating = tmdbRating, hasTrailer = hasTrailer,
            metadataLookupAllowed = false, collectionFocusKey = key,
        )

        companion object {
            fun fromMeta(meta: Meta): Brief = Brief(
                imdb = meta.imdbID, overview = meta.description, releaseDate = meta.releaseDate, runtime = meta.runtimeMinutes,
                tmdbRating = meta.tmdbRating, imdbRating = meta.imdbRating, votes = meta.tmdbVotes,
                popularity = meta.popularity, language = meta.originalLanguage, countries = meta.countries,
                spokenLanguages = meta.spokenLanguages, genres = meta.genres,
                cast = (meta.castNames + meta.cast.map { it.name }).distinct(), directors = meta.directors,
                studios = meta.studios, keywords = meta.keywords, status = meta.releaseStatus,
                certification = meta.certification, hasTrailer = meta.trailerYouTubeID != null,
                backdrop = meta.backdrop, logo = meta.logo,
            )
        }
    }

    companion object {
        const val PREFIX = "letterboxd:offline:"
        private const val GENERATION = "bundled-cineorgasm"
        private const val ASSET = "letterboxd-offline"
        private const val POSTER_LIMIT = 512 * 1024
        private const val ASSET_POSTER_PREFIX = "file:///android_asset/letterboxd-offline/posters/"
        private val ARTWORK_HOSTS = setOf("image.tmdb.org", "a.ltrbxd.com", "images.metahub.space", "images.cinemeta.strem.io")

        fun publicArtwork(value: String?): String? {
            val url = value?.toHttpUrlOrNull() ?: return null
            if (!url.isHttps || url.port != 443 || url.host !in ARTWORK_HOSTS ||
                url.username.isNotEmpty() || url.password.isNotEmpty()) return null
            return url.newBuilder().query(null).fragment(null).apply {
                if (url.host == "a.ltrbxd.com") url.queryParameter("v")?.takeIf { ARTWORK_VERSION.matches(it) }
                    ?.let { addQueryParameter("v", it) }
            }.build().toString()
        }

        private val ARTWORK_VERSION = Regex("[a-fA-F0-9]{1,32}")

        private val BACKDROP_ASSET = Regex("[a-f0-9]{24}\\.webp")
        private val POSTER_PATH = Regex("/[A-Za-z0-9]+\\.(?:jpg|png)")
    }
}

private fun Cursor.optionalInt(column: Int): Int? = if (isNull(column)) null else getInt(column)
