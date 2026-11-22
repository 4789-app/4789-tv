package com.fourseveneightnine.tv.client

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.fourseveneightnine.tv.catalog.LetterboxdListInformation
import com.fourseveneightnine.tv.catalog.TVLetterboxdClient
import com.fourseveneightnine.tv.catalog.TVLetterboxdFilm
import com.fourseveneightnine.tv.catalog.TVLetterboxdFilmDetails
import com.fourseveneightnine.tv.catalog.TVLetterboxdList
import com.fourseveneightnine.tv.catalog.TVLetterboxdPage
import com.fourseveneightnine.tv.client.data.addons.HttpStatusException
import com.fourseveneightnine.tv.client.data.meta.Meta
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal enum class ImportQuality { Matched, Limited, Unverified, Conflict }

internal data class OfflineListAudit(
    val key: String, val title: String, val saved: Int, val verified: Int,
    val limited: Int, val unverified: Int, val conflicts: Int, val imported: Boolean,
)

internal data class ImportedFilm(
    val slug: String, val id: String, val media: String, val title: String, val year: Int?,
    val poster: String?, val facts: OfflineLetterboxd.Brief, val quality: ImportQuality,
    val detail: Meta? = null, val reused: Boolean = false,
)

@Serializable
internal data class LetterboxdImportReport(
    val owner: String = "", val slug: String = "", val title: String = "",
    val stage: String = "", val running: Boolean = false, val fetched: Int = 0, val total: Int? = null,
    val checked: Int = 0, val verified: Int = 0, val limited: Int = 0, val unverified: Int = 0,
    val conflicts: Int = 0, val saved: Int = 0, val log: List<String> = emptyList(),
)

/** Staging rows are invisible until the complete list and its report commit together. */
internal class OfflineLetterboxdImportStore(app: Application) {
    private val helper = object : SQLiteOpenHelper(app, app.noBackupFilesDir.resolve("letterboxd-imports.sqlite").path,
        null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE lists(id TEXT PRIMARY KEY, name TEXT NOT NULL, generation TEXT NOT NULL,
                total INTEGER NOT NULL, verified INTEGER NOT NULL, limited INTEGER NOT NULL,
                unverified INTEGER NOT NULL, conflicts INTEGER NOT NULL, saved_at INTEGER NOT NULL)""")
            db.execSQL("""CREATE TABLE films(generation TEXT NOT NULL, position INTEGER NOT NULL, slug TEXT NOT NULL,
                canonical TEXT NOT NULL, media TEXT NOT NULL, name TEXT NOT NULL, year INTEGER, poster TEXT,
                facts TEXT NOT NULL, detail TEXT, quality TEXT NOT NULL, PRIMARY KEY (generation, position)) WITHOUT ROWID""")
            db.execSQL("CREATE INDEX imported_identity ON films(media, canonical)")
            db.execSQL("CREATE INDEX imported_slug ON films(slug)")
            db.execSQL("CREATE TABLE report(id INTEGER PRIMARY KEY, data TEXT NOT NULL)")
            onUpgrade(db, 1, 2)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) db.execSQL("CREATE TABLE list_information(id TEXT PRIMARY KEY, data TEXT NOT NULL)")
        }
    }.apply { setWriteAheadLoggingEnabled(true) }
    private val db get() = helper.writableDatabase
    private val json = Json { ignoreUnknownKeys = true }

    fun hasLists(): Boolean = db.rawQuery("SELECT 1 FROM lists LIMIT 1", null).use { it.moveToFirst() }

    fun audits(): List<OfflineListAudit> = db.rawQuery(
        "SELECT id,name,total,verified,limited,unverified,conflicts FROM lists ORDER BY name COLLATE NOCASE", null
    ).use { rows ->
        buildList {
            while (rows.moveToNext()) add(OfflineListAudit(rows.getString(0), rows.getString(1), rows.getInt(2),
                rows.getInt(3), rows.getInt(4), rows.getInt(5), rows.getInt(6), imported = true))
        }
    }

    fun information(): Map<String, LetterboxdListInformation> = buildMap {
        db.rawQuery("SELECT id,data FROM list_information", null).use { rows ->
            while (rows.moveToNext()) put(rows.getString(0), json.decodeFromString(rows.getString(1)))
        }
        db.rawQuery("SELECT id,saved_at FROM lists", null).use { rows ->
            while (rows.moveToNext()) {
                val key = rows.getString(0)
                put(key, (get(key) ?: LetterboxdListInformation()).copy(syncedAtMillis = rows.getLong(1)))
            }
        }
    }

    fun saveInformation(key: String, information: LetterboxdListInformation) {
        db.execSQL("INSERT OR REPLACE INTO list_information VALUES(?,?)", arrayOf(key,
            json.encodeToString(LetterboxdListInformation.serializer(), information)))
    }

    fun films(key: String, limit: Int): List<ImportedFilm>? {
        if (!db.rawQuery("SELECT 1 FROM lists WHERE id=?", arrayOf(key)).use { it.moveToFirst() }) return null
        return readFilms("""SELECT films.slug,films.canonical,films.media,films.name,films.year,
            films.poster,films.facts,films.quality,NULL FROM films JOIN lists USING(generation)
            WHERE lists.id=? ORDER BY films.position LIMIT ?""", arrayOf(key, limit.toString()))
    }

    fun resolved(slugs: List<String>): Map<String, ImportedFilm> = buildMap {
        for (chunk in slugs.chunked(250)) {
            val markers = chunk.joinToString(",") { "?" }
            for (film in readFilms("""SELECT films.slug,films.canonical,films.media,films.name,films.year,
                films.poster,films.facts,films.quality,films.detail FROM films JOIN lists USING(generation)
                WHERE films.slug IN ($markers) AND films.quality='Matched' ORDER BY lists.saved_at DESC""", chunk.toTypedArray())) {
                putIfAbsent(film.slug, film)
            }
        }
    }

    private fun readFilms(sql: String, args: Array<String>): List<ImportedFilm> = db.rawQuery(sql, args).use { rows ->
        buildList {
            while (rows.moveToNext()) add(ImportedFilm(
                rows.getString(0), rows.getString(1), rows.getString(2), rows.getString(3),
                if (rows.isNull(4)) null else rows.getInt(4), rows.getString(5),
                json.decodeFromString<OfflineLetterboxd.Brief>(rows.getString(6)), ImportQuality.valueOf(rows.getString(7)),
                if (rows.isNull(8)) null else json.decodeFromString<Meta>(rows.getString(8)),
            ))
        }
    }

    fun detail(type: String, id: String): Meta? = db.rawQuery(
        """SELECT films.detail FROM films JOIN lists USING(generation)
            WHERE films.media=? AND films.canonical=? AND films.detail IS NOT NULL
            ORDER BY lists.saved_at DESC LIMIT 1""", arrayOf(type, id),
    ).use { if (it.moveToFirst()) json.decodeFromString<Meta>(it.getString(0)) else null }

    fun issues(key: String): List<Pair<String, String>> = db.rawQuery(
        """SELECT films.quality,films.name FROM films JOIN lists USING(generation)
            WHERE lists.id=? AND films.quality IN ('Conflict','Unverified')
            ORDER BY films.position LIMIT 40""", arrayOf(key),
    ).use { rows -> buildList { while (rows.moveToNext()) add(rows.getString(0) to rows.getString(1)) } }

    fun artworkUrls(): List<String> = db.rawQuery(
        "SELECT DISTINCT films.poster FROM films JOIN lists USING(generation) WHERE films.poster IS NOT NULL", null
    ).use { rows -> buildList { while (rows.moveToNext()) add(rows.getString(0)) } }

    fun discardStaging() {
        db.execSQL("DELETE FROM films WHERE generation NOT IN (SELECT generation FROM lists)")
    }

    fun writeBatch(generation: String, position: Int, films: List<ImportedFilm>) = transaction {
        films.forEachIndexed { index, film ->
            db.execSQL("INSERT INTO films VALUES(?,?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(
                generation, position + index, film.slug, film.id, film.media, film.title, film.year, film.poster,
                json.encodeToString(OfflineLetterboxd.Brief.serializer(), film.facts),
                film.detail?.let { json.encodeToString(Meta.serializer(), it) }, film.quality.name,
            ))
        }
    }

    fun promote(key: String, generation: String, report: LetterboxdImportReport,
                information: LetterboxdListInformation?) = transaction {
        val verified = db.rawQuery("SELECT COUNT(*) FROM films WHERE generation=?", arrayOf(generation)).use {
            it.moveToFirst()
            it.getInt(0)
        }
        check(verified == report.saved)
        db.execSQL("INSERT OR REPLACE INTO lists VALUES(?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(key, report.title, generation,
            report.saved, report.verified, report.limited, report.unverified, report.conflicts, System.currentTimeMillis()))
        if (information != null) saveInformation(key, information)
        discardStaging()
        saveReport(report)
    }

    fun report(): LetterboxdImportReport = db.rawQuery("SELECT data FROM report WHERE id=1", null).use {
        if (it.moveToFirst()) json.decodeFromString<LetterboxdImportReport>(it.getString(0)) else LetterboxdImportReport()
    }

    fun saveReport(report: LetterboxdImportReport) {
        db.execSQL("INSERT OR REPLACE INTO report VALUES(1,?)",
            arrayOf(json.encodeToString(LetterboxdImportReport.serializer(), report)))
    }

    private inline fun transaction(block: () -> Unit) {
        val database = db
        database.beginTransactionNonExclusive()
        try {
            block()
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }
}

internal class OfflineLetterboxdImporter(
    private val scope: CoroutineScope,
    private val offline: OfflineLetterboxd,
    private val page: suspend (TVLetterboxdList, Int) -> Pair<String?, TVLetterboxdPage<TVLetterboxdFilm>>,
    private val details: suspend (String) -> TVLetterboxdFilmDetails,
    private val metadata: suspend (String, String, Int?) -> Meta?,
) {
    private val store = offline.imports
    private val mutable = MutableStateFlow(LetterboxdImportReport())
    val status = mutable.asStateFlow()
    private var active: Job? = null

    init {
        active = scope.launch(Dispatchers.IO) {
            val restored = try {
                store.discardStaging()
                store.report()
            } catch (_: Exception) {
                mutable.value = LetterboxdImportReport(stage = "Import history unavailable")
                return@launch
            }
            if (restored.running) report(restored.copy(running = false, stage = "Import interrupted"),
                "Saved lists were kept. Import the link again to retry.")
            else mutable.value = restored
        }
    }

    @Synchronized
    fun start(list: TVLetterboxdList): Boolean {
        if (active?.isCompleted == false) return false
        mutable.value = LetterboxdImportReport(owner = list.owner, slug = list.slug, title = list.slug,
            stage = "Reading list", running = true, log = listOf("Starting ${list.owner} / ${list.slug}"))
        active = scope.launch(Dispatchers.IO) {
            val generation = UUID.randomUUID().toString()
            try {
                report(mutable.value)
                val (films, information) = readList(list)
                var cached = 0
                report(mutable.value.copy(stage = "Matching titles"), "Matching ${titleCount(films.size)} and saving available metadata.")
                for ((batch, group) in films.chunked(8).withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val saved = offline.cachedFilms(group.map { it.slug })
                    val resolved = coroutineScope {
                        group.chunked(2).map { part -> async { part.map { resolve(it, saved[it.slug]) } } }
                            .awaitAll().flatten()
                    }
                    val beginning = batch * 8
                    val starter = resolved.take((10 - beginning).coerceAtLeast(0)).mapNotNull { it.poster }.distinct()
                    for (url in starter) {
                        currentCoroutineContext().ensureActive()
                        offline.cacheImportedPoster(url)
                    }
                    store.writeBatch(generation, beginning, resolved)
                    cached += resolved.count { it.reused }
                    val current = mutable.value
                    val verified = resolved.count { it.quality == ImportQuality.Matched || it.quality == ImportQuality.Limited }
                    val issues = resolved.filter { it.quality == ImportQuality.Unverified || it.quality == ImportQuality.Conflict }
                    val next = current.copy(checked = current.checked + resolved.size, verified = current.verified + verified,
                        limited = current.limited + resolved.count { it.quality == ImportQuality.Limited },
                        unverified = current.unverified + issues.count { it.quality == ImportQuality.Unverified },
                        conflicts = current.conflicts + issues.count { it.quality == ImportQuality.Conflict })
                    val message = "Checked ${next.checked} of ${films.size} · ${next.verified} verified · $cached reused"
                    val issueLog = issues.take(4).map { "${if (it.quality == ImportQuality.Conflict) "Conflict" else "Unverified"} · ${it.title}" }
                    report(next.copy(log = (next.log + issueLog).takeLast(59)), message)
                }
                currentCoroutineContext().ensureActive()
                val current = mutable.value
                val finished = current.copy(stage = "Saved on this TV", running = false, saved = films.size,
                    log = (current.log + eventLine("Saved ${titleCount(films.size)} for offline browsing.")).takeLast(60))
                information?.let { offline.cacheBackdrop(it) }
                currentCoroutineContext().ensureActive()
                store.promote(list.key, generation, finished, information)
                offline.importChanged()
                mutable.value = finished
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    endFailed("Import canceled", "The previous saved list was kept.")
                }
                throw cancelled
            } catch (failure: Exception) {
                val message = when ((failure as? HttpStatusException)?.code) {
                    403 -> "Letterboxd did not allow this TV to read the list."
                    404, 410 -> "Letterboxd could not find this public list."
                    429 -> "Letterboxd asked this TV to slow down. Retry later."
                    else -> when (failure.message) {
                        "letterboxd_verification" -> "Letterboxd requested browser verification."
                        "letterboxd_incomplete_list", "letterboxd_repeated_page" -> "The list changed or arrived incomplete. Retry the import."
                        else -> "Could not finish the import. Check the connection and retry."
                    }
                }
                endFailed("Import needs attention", message)
            }
        }.also { job ->
            job.invokeOnCompletion { error ->
                if (error is CancellationException && mutable.value.running) {
                    mutable.value = mutable.value.copy(stage = "Import canceled", running = false)
                }
            }
        }
        return true
    }

    @Synchronized
    fun cancel() {
        if (mutable.value.running) active?.cancel()
    }

    private suspend fun readList(list: TVLetterboxdList): Pair<List<TVLetterboxdFilm>, LetterboxdListInformation?> {
        val found = linkedMapOf<String, TVLetterboxdFilm>()
        var expected: Int? = null
        var information: LetterboxdListInformation? = null
        for (number in 1..1_000) {
            val (name, result) = page(list, number)
            val before = found.size
            result.entries.forEach { found.putIfAbsent(it.slug, it) }
            if (number == 1) {
                expected = result.total
                information = result.information
            }
            report(mutable.value.copy(title = name ?: mutable.value.title, fetched = found.size, total = expected),
                "Read page $number · ${titleCount(found.size)} found")
            if (!result.hasNext) {
                check((expected == null || expected == found.size) && (found.size > before || (number == 1 && expected == 0))) {
                    "letterboxd_incomplete_list"
                }
                return found.values.toList() to information
            }
            check(found.size > before) { "letterboxd_repeated_page" }
        }
        error("letterboxd_page_limit")
    }

    private suspend fun resolve(film: TVLetterboxdFilm, cached: ImportedFilm?): ImportedFilm {
        if (cached != null) return cached.copy(reused = true, detail = cached.detail ?: with(cached) {
            Meta(id = id, type = media, title = title, year = year, description = facts.overview, poster = poster,
                backdrop = facts.backdrop, logo = facts.logo, runtimeMinutes = facts.runtime, certification = facts.certification,
                imdbID = facts.imdb, tmdbID = id.removePrefix("tmdb:").toIntOrNull(), genres = facts.genres,
                originalLanguage = facts.language, countries = facts.countries, castNames = facts.cast, directors = facts.directors,
                studios = facts.studios, keywords = facts.keywords, spokenLanguages = facts.spokenLanguages,
                releaseStatus = facts.status, releaseDate = facts.releaseDate, tmdbVotes = facts.votes,
                imdbRating = facts.imdbRating, tmdbRating = facts.tmdbRating, popularity = facts.popularity)
        })
        val display = YEAR.find(film.name)
        val name = (display?.let { film.name.substring(0, it.range.first) } ?: film.name).ifBlank { film.slug }
        val year = display?.groupValues?.get(1)?.toIntOrNull()
        val source = try {
            details(film.slug)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: HttpStatusException) {
            if (failure.code == 429) throw failure
            null
        } catch (_: Exception) {
            null
        }
        val identity = source?.identity
        val imdb = source?.imdbId ?: identity?.id?.takeIf { it.startsWith("tt") }
        val tmdb = identity?.id?.takeIf { it.startsWith("tmdb:") }?.removePrefix("tmdb:")?.toIntOrNull()
        val meta = try {
            when {
                identity != null -> metadata(identity.type, imdb ?: identity.id, tmdb)
                imdb != null -> metadata("movie", imdb, null) ?: metadata("series", imdb, null)
                else -> null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val mismatch = meta != null && ((identity != null && meta.type != identity.type) ||
            (tmdb != null && meta.tmdbID != null && tmdb != meta.tmdbID) ||
            (imdb != null && meta.imdbID != null && imdb != meta.imdbID))
        val verified = !mismatch && (identity != null || (imdb != null && meta != null &&
            (meta.imdbID == imdb || meta.id == imdb)))
        val id = if (verified) identity?.id ?: checkNotNull(imdb) else TVLetterboxdClient.FILM_ID_PREFIX + film.slug
        val public = source?.publicMetadata
        val type = if (verified) identity?.type ?: checkNotNull(meta).type else public?.type ?: "movie"
        val trusted = meta?.takeIf { verified && !mismatch }
        val usable = if (trusted != null && public != null) trusted.copy(
            description = trusted.description ?: public.description,
            runtimeMinutes = trusted.runtimeMinutes ?: public.runtimeMinutes,
            genres = trusted.genres.ifEmpty { public.genres },
            originalLanguage = trusted.originalLanguage ?: public.originalLanguage,
            spokenLanguages = trusted.spokenLanguages.ifEmpty { public.spokenLanguages },
            countries = trusted.countries.ifEmpty { public.countries },
            castNames = if (trusted.cast.isEmpty()) trusted.castNames.ifEmpty { public.castNames } else trusted.castNames,
            directors = trusted.directors.ifEmpty { public.directors }, studios = trusted.studios.ifEmpty { public.studios },
            releaseDate = trusted.releaseDate ?: public.releaseDate,
        ) else trusted ?: public
        val poster = OfflineLetterboxd.publicArtwork(usable?.poster) ?: OfflineLetterboxd.publicArtwork(source?.poster)
            ?: OfflineLetterboxd.publicArtwork(film.poster)
        val clean = usable?.copy(id = id, type = type, hasInlineStreams = false, inlineStreams = null,
            imdbID = (usable.imdbID?.takeIf(IMDB_ID::matches) ?: imdb).takeIf { verified },
            poster = poster, backdrop = OfflineLetterboxd.publicArtwork(usable.backdrop),
            logo = OfflineLetterboxd.publicArtwork(usable.logo),
            posterFallbacks = usable.posterFallbacks.mapNotNull { OfflineLetterboxd.publicArtwork(it) },
            backdropFallbacks = usable.backdropFallbacks.mapNotNull { OfflineLetterboxd.publicArtwork(it) },
            cast = usable.cast.map { it.copy(photo = OfflineLetterboxd.publicArtwork(it.photo)) },
            videos = usable.videos.filter { VIDEO_ID.matches(it.id) }
                .map { it.copy(thumbnail = OfflineLetterboxd.publicArtwork(it.thumbnail), inlineStreams = null) },
            defaultVideoID = usable.defaultVideoID?.takeIf(VIDEO_ID::matches),
            similar = usable.similar.filter { VIDEO_ID.matches(it.id) }
                .map { it.copy(poster = OfflineLetterboxd.publicArtwork(it.poster)) },
            streamingProviders = usable.streamingProviders.filter { OfflineLetterboxd.publicArtwork(it.logo) != null },
        )
        return ImportedFilm(film.slug, id, type, name, year ?: clean?.year, poster,
            clean?.let(OfflineLetterboxd.Brief::fromMeta) ?: OfflineLetterboxd.Brief(imdb = imdb.takeIf { verified }),
            when {
                mismatch -> ImportQuality.Conflict
                !verified -> ImportQuality.Unverified
                trusted == null || clean?.genres.isNullOrEmpty() -> ImportQuality.Limited
                else -> ImportQuality.Matched
            }, detail = clean)
    }

    private fun report(report: LetterboxdImportReport, event: String? = null) {
        val next = if (event == null) report else report.copy(log = (report.log + eventLine(event)).takeLast(60))
        store.saveReport(next)
        mutable.value = next
    }

    private fun endFailed(stage: String, message: String) {
        runCatching { store.discardStaging() }
        val next = mutable.value.copy(stage = stage, running = false,
            log = (mutable.value.log + eventLine(message)).takeLast(60))
        runCatching { store.saveReport(next) }
        mutable.value = next
    }

    private fun titleCount(count: Int) = "$count ${if (count == 1) "title" else "titles"}"

    private fun eventLine(message: String) = "${LocalTime.now().format(LOG_TIME)} · $message"

    companion object {
        private val LOG_TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
        private val IMDB_ID = Regex("tt[0-9]{5,16}")
        private val VIDEO_ID = Regex("[A-Za-z0-9_:.-]{1,180}")
        private val YEAR = Regex(" \\(([0-9]{4})\\)$")
    }
}
