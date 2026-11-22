package com.fourseveneightnine.tv.client.ui.screens.detail

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fourseveneightnine.tv.catalog.TVLetterboxdClient
import com.fourseveneightnine.tv.catalog.TVLetterboxdIdentity
import com.fourseveneightnine.tv.client.ClientGraph
import com.fourseveneightnine.tv.client.data.library.ContinueItem
import com.fourseveneightnine.tv.client.data.library.SystemCollections
import com.fourseveneightnine.tv.client.data.meta.Episode
import com.fourseveneightnine.tv.client.data.meta.Meta
import com.fourseveneightnine.tv.client.data.meta.MetaRef
import com.fourseveneightnine.tv.client.data.meta.Ratings
import com.fourseveneightnine.tv.client.playback.PlayRequest
import com.fourseveneightnine.tv.client.ui.screens.home.HomeCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What Detail has in hand right now (`TV_DESIGN_SPEC.md` §9.8).
 *
 * `loading` and `failed` are separate from `meta` on purpose: §9.8 Partial says the hero paints
 * from the row's own data while the rails still carry skeletons, so "we have a title" and "we are
 * still fetching" are two different facts and a single nullable cannot hold both.
 */
@Immutable
internal data class DetailUiState(
    val meta: Meta? = null,
    val ratings: Ratings = Ratings.NONE,
    val loading: Boolean = true,
    /** Base metadata or an error is ready for display; enrichment may still be loading. */
    val metadataReady: Boolean = false,
    val failed: Boolean = false,
    val failureMessage: String? = null,
    val selectedSeason: Int? = null,
    /** The one row the library holds for this title, or null. */
    val progress: ContinueItem? = null,
    val inWatchlist: Boolean = false,
    val inFavorites: Boolean = false,
    /**
     * §9.4's badge column on the poster.
     *
     * Empty today, and honestly so: cached, 4K and HDR are facts about a *source*.
     * Detail warms sources for its drawer, but the poster does not claim source facts until a
     * selected source supplies them. Nothing invents a badge from title metadata.
     */
) {
    val isSeries: Boolean get() = meta?.type == "series"

    /** The episodes of the chosen season, in order. Empty on a movie. */
    val episodes: List<Episode>
        get() = selectedSeason?.let { meta?.episodes(it) }.orEmpty()
}

/** One badge on the Detail poster (§9.4). The order here is the order they stack. */
internal enum class DetailBadge { Cached, FourK, Hdr, DolbyVision }

/** The Home card supplies a first frame while the title's full metadata is fetched. */
internal object DetailPreview {
    private var last: HomeCard? = null

    fun stage(card: HomeCard) { last = card }

    fun stage(ref: MetaRef) {
        stage(HomeCard(ref.id, ref.type, ref.title, posterUrl = ref.poster))
    }

    fun forTitle(type: String, id: String): HomeCard? = last?.takeIf { it.type == type && it.id == id }
}

/**
 * Which badges the poster carries (§9.4, §9.10.5).
 *
 * "HDR or DV" is one slot, and Dolby Vision is the stronger claim, so it wins. Three is the cap,
 * because a fourth badge starts to cover the poster it is describing.
 */
internal object DetailBadges {
    const val MAX = 3

    fun of(cached: Boolean, fourK: Boolean, hdr: Boolean, dolbyVision: Boolean): List<DetailBadge> =
        buildList {
            if (cached) add(DetailBadge.Cached)
            if (fourK) add(DetailBadge.FourK)
            when {
                dolbyVision -> add(DetailBadge.DolbyVision)
                hdr -> add(DetailBadge.Hdr)
            }
        }.take(MAX)
}

/**
 * Detail's state holder.
 *
 * The navigation entry retains metadata while another title or screen is above it. Its own
 * coroutine scope is cancelled when that entry is removed. Player and source sessions stay out.
 */
internal class DetailViewModel(
    private val type: String,
    private val id: String,
    private val client: ClientGraph,
    initialSeason: Int? = null,
    restoredTitle: String? = null,
    restoredPosterLookupId: String? = null,
) : ViewModel() {
    private val scope get() = viewModelScope
    private val isLetterboxd = id.startsWith(TVLetterboxdClient.FILM_ID_PREFIX)
    private val previewCard = DetailPreview.forTitle(type, id) ?: restoredTitle?.let {
        HomeCard(id, type, it, posterLookupId = restoredPosterLookupId)
    }
    private val _state = MutableStateFlow(DetailUiState(
        meta = previewCard?.let { card ->
            Meta(
                id = card.id, type = card.type, title = card.title, year = card.year,
                runtimeMinutes = card.runtimeMinutes, certification = card.certification,
                genres = card.genres.ifEmpty { listOfNotNull(card.genre) }, description = card.overview,
                poster = card.posterUrl, backdrop = card.backdropUrl, logo = card.logoUrl,
                imdbID = card.imdbID, tmdbID = card.tmdbID, imdbRating = card.imdbRating,
                tmdbRating = card.tmdbRating, releaseDate = card.releaseDate, directors = card.directors,
                castNames = card.castNames, countries = card.countries, originalLanguage = card.originalLanguage,
            )
        },
        selectedSeason = initialSeason,
    ))
    val state: StateFlow<DetailUiState> = _state.asStateFlow()
    private var seasonChosen = initialSeason != null
    private var preferredSeason = initialSeason
    private var loadJob: Job? = null
    private var letterboxdIdentity: TVLetterboxdIdentity? = null
    var relatedReturnKey: String? = null
    var castReturnKey: String? = null

    init {
        if (!isLetterboxd) observeLibrary(type, id)
    }

    private fun observeLibrary(type: String, id: String) {
        scope.launch {
            client.activeLibrary.playbackHistory().collect { rows ->
                val progress = rows.firstOrNull { it.canonicalId == id }
                val current = _state.value
                _state.value = current.copy(
                    progress = progress,
                    selectedSeason = if (seasonChosen) current.selectedSeason else current.meta?.let {
                        DetailPlay.pick(it, progress, todayIso()).season
                    } ?: current.selectedSeason,
                )
            }
        }
        scope.launch {
            client.activeLibrary.savedCollections().collect { lists ->
                _state.value = _state.value.copy(inWatchlist = lists.any { list ->
                    list.sourceRef == SystemCollections.WATCHLIST_REF &&
                        list.items.any { it.canonicalId == id && it.mediaType == type }
                }, inFavorites = lists.any { list ->
                    list.sourceRef == SystemCollections.FAVORITES_REF &&
                        list.items.any { it.canonicalId == id && it.mediaType == type }
                })
            }
        }
    }

    fun loadIfNeeded() {
        if (loadJob == null) load()
    }

    fun load() {
        // Retain the known details or error panel until a new metadata result replaces it.
        _state.value = _state.value.copy(loading = true)
        loadJob?.cancel()
        loadJob = scope.launch {
            val offlinePreview = previewCard?.collectionFocusKey?.startsWith(
                com.fourseveneightnine.tv.client.OfflineLetterboxd.PREFIX) == true
            val offlineDetail = if (offlinePreview) client.offlineLetterboxd.importedDetail(type, id) else null
            val local = offlineDetail ?: _state.value.meta?.takeIf { offlinePreview && !isLetterboxd }
            local?.let { showMeta(it, awaitingEpisodes = type == "series" && it.videos.isEmpty()) }
            if (offlinePreview && client.services.value == null) {
                _state.value = _state.value.copy(loading = false, metadataReady = true)
                return@launch
            }
            if (isLetterboxd && letterboxdIdentity == null) {
                val card = previewCard ?: HomeCard(id, type, id.removePrefix(TVLetterboxdClient.FILM_ID_PREFIX))
                val lookup = runCatching { client.resolveLetterboxdIdentity(card) }
                    .onFailure { if (it is CancellationException) throw it }
                val identity = lookup.getOrNull()
                if (identity == null) {
                    _state.value = _state.value.copy(loading = false, metadataReady = offlineDetail != null,
                        failed = offlineDetail == null,
                        failureMessage = TVLetterboxdClient.titleLookupMessage(lookup.exceptionOrNull()))
                    return@launch
                }
                letterboxdIdentity = identity
                observeLibrary(identity.type, identity.id)
            }
            val type = letterboxdIdentity?.type ?: this@DetailViewModel.type
            val id = letterboxdIdentity?.id ?: this@DetailViewModel.id
            val services = client.services.value ?: client.services.filterNotNull().first()
            val saved = if (id.startsWith("saved:")) {
                withContext(Dispatchers.Default) { client.savedCatalogItem(type, id) }
            } else null
            val fallback = saved?.let { item ->
                val savedSeason = item.season
                val savedEpisode = item.episode
                Meta(id = id, type = type, title = item.title, year = item.year, poster = item.posterUrl,
                    backdrop = item.backdropUrl, description = item.overview, genres = item.genres,
                    imdbID = item.imdbId, tmdbID = item.tmdbId,
                    videos = if (savedSeason != null && savedEpisode != null) listOf(
                        Episode(savedSeason, savedEpisode, item.title, id = id)) else emptyList())
            }
            fallback?.let { showMeta(it, awaitingEpisodes = type == "series" && it.videos.isEmpty()) }
            val tamilRelease = id.startsWith("saved:tamilmv:")
            val matched = if (tamilRelease && saved != null) client.tamilCatalog.matchForDetails(saved) else saved
            val lookupID = matched?.imdbId ?: matched?.tmdbId?.let { "tmdb:$it" }
            val releasePreview = if (matched != null && matched != saved) fallback?.copy(
                poster = matched.posterUrl, backdrop = matched.backdropUrl, description = matched.overview,
                genres = matched.genres, imdbID = matched.imdbId, tmdbID = matched.tmdbId,
            ) else fallback
            if (releasePreview != null && releasePreview != fallback) showMeta(releasePreview, awaitingEpisodes = true)
            if (tamilRelease && lookupID == null) {
                _state.value = _state.value.copy(loading = false, metadataReady = true, failed = releasePreview == null)
                return@launch
            }
            val knownTmdbID = matched?.tmdbId ?: _state.value.meta?.tmdbID
            fun forRelease(base: Meta): Meta {
                val bound = releasePreview?.videos?.singleOrNull() ?: return base.copy(id = id)
                val episode = if (tamilRelease) base.videos.firstOrNull {
                    it.season == bound.season && it.episode == bound.episode
                } ?: bound else bound
                return base.copy(id = id, videos = listOf(episode), seasons = listOf(episode.season))
            }
            fun showPreview(base: Meta) {
                val visible = forRelease(base)
                // A saved title already has a usable page. Let a series fill its episode picker
                // early, then apply the remaining live metadata together when the lookup ends.
                if (local != null && !(visible.videos.isNotEmpty() && _state.value.meta?.videos.isNullOrEmpty())) return
                showMeta(visible, awaitingEpisodes = type == "series" && visible.videos.isEmpty(), retainCast = true)
            }
            knownTmdbID?.let { services.meta.peekTmdbPreview(type, it) }?.let(::showPreview)
            val meta = try {
                services.meta.meta(type, lookupID ?: id, knownTmdbID, onBase = ::showPreview)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { null }
            val complete = meta?.let(::forRelease) ?: releasePreview ?: offlineDetail
                ?: _state.value.meta?.copy(id = id, type = type)
            if (complete == null) {
                _state.value = _state.value.copy(loading = false, metadataReady = true, failed = true)
                return@launch
            }
            showMeta(complete)
            // The chips fill in where they sit, with no movement (§9.8 Partial).
            val imdb = complete.imdbID ?: return@launch
            val scores = try { services.ratings.ratings(imdb, complete.type == "series") }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { Ratings.NONE }
            _state.value = _state.value.copy(ratings = scores)
        }
    }

    private fun showMeta(meta: Meta, awaitingEpisodes: Boolean = false, retainCast: Boolean = false) {
        val known = _state.value.meta
        val displayed = meta.copy(
            poster = meta.poster ?: known?.poster,
            backdrop = meta.backdrop ?: known?.backdrop,
            logo = meta.logo ?: known?.logo,
            description = meta.description ?: known?.description,
            year = meta.year ?: known?.year,
            runtimeMinutes = meta.runtimeMinutes ?: known?.runtimeMinutes,
            genres = meta.genres.ifEmpty { known?.genres.orEmpty() },
            imdbRating = meta.imdbRating ?: known?.imdbRating,
            releaseDate = meta.releaseDate ?: known?.releaseDate,
            cast = if (retainCast && meta.cast.isEmpty()) known?.cast.orEmpty() else meta.cast,
            inlineStreams = null,
            videos = meta.videos.map { episode ->
                if (episode.inlineStreams == null) episode else episode.copy(inlineStreams = null)
            },
        )
        _state.value = _state.value.copy(
                meta = displayed,
                loading = awaitingEpisodes,
                metadataReady = true,
                failed = false,
                failureMessage = null,
                // A season in progress opens on itself, not on season 1 (§9.10.10).
                selectedSeason = preferredSeason?.takeIf { it in meta.seasons }
                    ?: _state.value.selectedSeason?.takeIf { meta.seasons.isEmpty() }
                    ?: DetailPlay.pick(meta, _state.value.progress, todayIso()).season?.takeIf { it in meta.seasons }
                    ?: meta.seasons.filter { it > 0 }.minOrNull()
                    ?: meta.seasons.firstOrNull(),
            )
    }

    fun selectSeason(season: Int) {
        seasonChosen = true
        preferredSeason = season
        if (_state.value.selectedSeason == season) return
        _state.value = _state.value.copy(selectedSeason = season)
    }

    fun markWatched(season: Int? = null, episode: Int? = null) {
        val current = _state.value
        val title = current.meta?.takeIf { current.metadataReady && !current.failed } ?: return
        scope.launch {
            client.activeLibrary.markWatched(
                canonicalId = title.id,
                mediaType = title.type,
                season = season ?: current.progress?.season,
                episode = episode ?: current.progress?.episode,
            )
        }
    }
}

/** What pressing Play will actually start, and the words the button says it will (§9.4, §9.6). */
@Immutable
internal data class PlayPick(
    val season: Int? = null,
    val episode: Int? = null,
    val resumeFromMs: Long? = null,
    val label: String = "Play",
) {
    fun request(meta: Meta, forceList: Boolean = false, fromStart: Boolean = false): PlayRequest =
        PlayRequest(
            type = meta.type,
            id = meta.id,
            title = meta.title,
            season = season,
            episode = episode,
            posterUrl = meta.poster,
            backdropUrl = meta.backdrop,
            resumeFromMs = resumeFromMs.takeUnless { fromStart },
            forceList = forceList,
        )
}

/**
 * Which episode Play starts, and what the button says.
 *
 * §9.6: "Play on a series resolves in this order: the episode in progress, else the next unwatched
 * episode, else season 1 episode 1." §9.10.1 and §9.10.2: the button says what it will do, and a
 * resume shows a time rather than a percentage, because "62%" means nothing from a sofa.
 *
 * **One row, not a history.** `watch_progress` holds one row per title (`API-B.md`), so "the next
 * unwatched episode" can only mean "the one after the row we have". A box that played episodes out
 * of order gets the episode after the last one it played, which is the same answer a viewer would
 * give looking at the same evidence.
 */
internal object DetailPlay {

    /** Below this a title has not started; at or above [WATCHED] it is finished (API-B rule 3). */
    const val STARTED = 0.02f
    const val WATCHED = 0.95f

    fun pick(
        meta: Meta, progress: ContinueItem?, todayIso: String? = null,
        requestedSeason: Int? = null, requestedEpisode: Int? = null,
    ): PlayPick {
        if (meta.type != "series") return moviePick(progress)
        val requested = if (requestedSeason != null && requestedEpisode != null) {
            meta.videos.firstOrNull { it.season == requestedSeason && it.episode == requestedEpisode }
        } else null
        if (requested != null) {
            val resume = progress?.takeIf {
                it.season == requested.season && it.episode == requested.episode
            }?.let(::resumeMillis)
            return PlayPick(requested.season, requested.episode, resume,
                if (resume != null) "Resume S${requested.season} E${requested.episode}"
                else "Play S${requested.season} E${requested.episode}")
        }
        return seriesPick(meta, progress, todayIso)
    }

    private fun moviePick(progress: ContinueItem?): PlayPick {
        val resume = resumeMillis(progress) ?: return PlayPick(label = "Play")
        return PlayPick(resumeFromMs = resume, label = "Resume ${DetailFormat.duration(resume)} in")
    }

    private fun seriesPick(meta: Meta, progress: ContinueItem?, todayIso: String?): PlayPick {
        val ordered = meta.videos
            .filter { it.season > 0 }
            .filterNot { todayIso != null && DetailFormat.isUnaired(it.released, todayIso) }
            .sortedWith(compareBy(Episode::season, Episode::episode))
        val first = ordered.firstOrNull() ?: meta.videos.firstOrNull {
            todayIso == null || !DetailFormat.isUnaired(it.released, todayIso)
        }

        val season = progress?.season
        val episode = progress?.episode
        if (progress == null || season == null || episode == null) {
            return firstPick(first)
        }

        val fraction = progress.progressFraction
        if (progress.resumePositionMs != null && ordered.any { it.season == season && it.episode == episode }) {
            return PlayPick(
                season = season,
                episode = episode,
                resumeFromMs = progress.positionMs.takeIf { it > 0L },
                label = "Resume S$season E$episode",
            )
        }

        // Finished, or never really started. Either way the next one is what Play means.
        val index = ordered.indexOfFirst { it.season == season && it.episode == episode }
        val next = if (fraction >= WATCHED) ordered.getOrNull(index + 1) else ordered.getOrNull(index)
        val target = next ?: first ?: return PlayPick(label = "Play")
        return PlayPick(
            season = target.season,
            episode = target.episode,
            label = "Play S${target.season} E${target.episode}",
        )
    }

    private fun firstPick(first: Episode?): PlayPick = when (first) {
        null -> PlayPick(label = "Play")
        else -> PlayPick(
            season = first.season,
            episode = first.episode,
            label = "Play S${first.season} E${first.episode}",
        )
    }

    private fun resumeMillis(progress: ContinueItem?): Long? = progress?.resumePositionMs

    /** Has this exact episode been watched, as far as the one progress row can say? */
    fun isWatched(progress: ContinueItem?, season: Int, episode: Int): Boolean =
        progress != null &&
            progress.season == season &&
            progress.episode == episode &&
            progress.progressFraction >= WATCHED

    /** How far through this exact episode, or null when the bar should not draw (§16.11). */
    fun episodeProgress(progress: ContinueItem?, season: Int, episode: Int): Float? = progress
        ?.takeIf { it.season == season && it.episode == episode }
        ?.progressFraction
        ?.takeIf { it in STARTED..<WATCHED }
}

/** Every string on Detail that is built from a number. Pure, so the table above is testable. */
internal object DetailFormat {
    /** Group known Prime variants without merging unrelated Amazon channels or other brands. */
    fun providerGroups(providers: List<com.fourseveneightnine.tv.client.data.meta.StreamingProvider>) =
        providers.distinctBy { it.id }.groupBy { provider ->
            when (provider.name.trim().lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " ")) {
                "amazon prime video", "prime video", "amazon video",
                "amazon prime video with ads", "prime video with ads",
                "amazon prime video free with ads" -> "Prime Video"
                else -> provider.name.trim()
            }
        }

    /** The dock separates genres and keeps typical episode runtime with the episodes. */
    fun heroMetaLine(meta: Meta): String = listOfNotNull(
        meta.year?.toString(), seasonsLabel(meta),
        runtime(meta.runtimeMinutes).takeUnless { meta.type == "series" },
        meta.certification?.takeIf(String::isNotBlank),
    ).joinToString(" · ")

    fun orderedEpisodes(episodes: List<Episode>, order: String): List<Episode> = when (order) {
        "Oldest episode" -> episodes.sortedWith(compareBy(Episode::season, Episode::episode))
        "Latest air date" -> episodes.sortedWith(compareByDescending<Episode> { date(it.released)?.toString().orEmpty() }
            .thenByDescending(Episode::season).thenByDescending(Episode::episode))
        else -> episodes.sortedWith(compareByDescending(Episode::season).thenByDescending(Episode::episode))
    }


    /** "2h 09m", "44m". A resume says a time, never a percentage (§9.10.2). */
    fun duration(millis: Long): String {
        val totalMinutes = (millis / 60_000L).coerceAtLeast(0L)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}h ${minutes.toString().padStart(2, '0')}m" else "${minutes}m"
    }

    fun runtime(minutes: Int?): String? = minutes
        ?.takeIf { it > 0 }
        ?.let { duration(it * 60_000L) }

    /** "2024 · 2h 09m · Thriller, Crime · 15". A missing part drops its segment (§9.9). */
    fun metaLine(meta: Meta): String = listOfNotNull(
        meta.year?.toString(),
        seasonsLabel(meta),
        runtime(meta.runtimeMinutes)?.let { if (meta.type == "series") "Typical episode $it" else it },
        meta.genres.take(3).takeIf { it.isNotEmpty() }?.joinToString(", "),
        meta.certification?.takeIf(String::isNotBlank),
    ).joinToString(" · ")

    /** Season count and typical runtime describe different facts. */
    private fun seasonsLabel(meta: Meta): String? {
        if (meta.type != "series") return null
        val count = meta.seasons.count { it > 0 }
        return when {
            count <= 0 -> null
            count == 1 -> "1 season"
            else -> "$count seasons"
        }
    }

    /** "44 min · 12th March 2024", or whichever half exists (§9.4, §9.9). */
    fun episodeLine(runtimeMinutes: Int?, released: String?): String = listOfNotNull(
        runtimeMinutes?.takeIf { it > 0 }?.let { "$it min" },
        airDate(released),
    ).joinToString(" · ")

    /** A supplied full date. Invalid dates remain unknown. */
    fun airDate(released: String?): String? {
        val date = date(released) ?: return null
        val name = MONTHS[date.monthValue - 1]
        val day = date.dayOfMonth
        val suffix = if (day in 11..13) "th" else when (day % 10) {
            1 -> "st"
            2 -> "nd"
            3 -> "rd"
            else -> "th"
        }
        return "$day$suffix $name ${date.year}"
    }

    /** True when this episode has not aired yet (§9.4). An unknown date is treated as aired. */
    fun isUnaired(released: String?, nowIso: String): Boolean {
        val release = date(released) ?: return false
        val today = date(nowIso) ?: return false
        return release.isAfter(today)
    }

    private fun date(text: String?): java.time.LocalDate? = text?.trim()?.takeIf { it.length >= 10 }
        ?.let { runCatching { java.time.LocalDate.parse(it.take(10)) }.getOrNull() }

    fun seasonLabel(season: Int): String = if (season == 0) "Specials" else "Season $season"

    private val MONTHS = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )
}

/** Device-local date, used only to label known future episodes. */
internal fun todayIso(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
    .format(java.util.Date())
