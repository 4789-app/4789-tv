package com.fourseveneightnine.tv.client.ui.screens.detail

import androidx.compose.runtime.Immutable
import com.fourseveneightnine.tv.client.ClientGraph
import com.fourseveneightnine.tv.client.data.library.ContinueItem
import com.fourseveneightnine.tv.client.data.library.SystemCollections
import com.fourseveneightnine.tv.client.data.meta.Episode
import com.fourseveneightnine.tv.client.data.meta.Meta
import com.fourseveneightnine.tv.client.data.meta.MetaRef
import com.fourseveneightnine.tv.client.data.meta.Ratings
import com.fourseveneightnine.tv.client.playback.PlayRequest
import com.fourseveneightnine.tv.client.ui.screens.home.HomeCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException

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
    val failed: Boolean = false,
    val selectedSeason: Int? = null,
    /** The one row the library holds for this title, or null. */
    val progress: ContinueItem? = null,
    val inWatchlist: Boolean = false,
    /**
     * §9.4's badge column on the poster.
     *
     * Empty today, and honestly so: cached, 4K and HDR are facts about a *source*.
     * Detail warms sources for its drawer, but the poster does not claim source facts until a
     * selected source supplies them. Nothing invents a badge from title metadata.
     */
    val posterBadges: List<DetailBadge> = emptyList(),
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
    private var last: Meta? = null

    fun stage(card: HomeCard) {
        last = Meta(
            id = card.id,
            type = card.type,
            title = card.title,
            year = card.year,
            runtimeMinutes = card.runtimeMinutes,
            genres = listOfNotNull(card.genre),
            description = card.overview,
            poster = card.posterUrl,
            backdrop = card.backdropUrl,
            logo = card.logoUrl,
        )
    }

    fun stage(ref: MetaRef) {
        last = Meta(id = ref.id, type = ref.type, title = ref.title, poster = ref.poster)
    }

    fun forTitle(type: String, id: String): Meta? = last?.takeIf { it.type == type && it.id == id }
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
 * Plain class, constructed by `remember`, not an `androidx.lifecycle.ViewModel`: the shell hands
 * screens a `ClientNav` and nothing else, and a factory that reaches the graph through a static
 * `Context` buys nothing a `remember(type, id)` does not already give. Everything it needs comes
 * in through the constructor, so the pick rules below are unit-testable without any of it.
 */
internal class DetailViewModel(
    private val type: String,
    private val id: String,
    private val client: ClientGraph,
    private val scope: CoroutineScope,
    initialSeason: Int? = null,
) {
    private val _state = MutableStateFlow(DetailUiState(meta = DetailPreview.forTitle(type, id), selectedSeason = initialSeason))
    val state: StateFlow<DetailUiState> = _state.asStateFlow()
    private var seasonChosen = initialSeason != null
    private var preferredSeason = initialSeason
    private var loadJob: Job? = null

    init {
        load()
        scope.launch {
            client.activeLibrary.continueWatching().collect { rows ->
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
                })
            }
        }
    }

    fun load() {
        _state.value = _state.value.copy(loading = true, failed = false)
        loadJob?.cancel()
        loadJob = scope.launch {
            val services = client.services.value ?: client.services.filterNotNull().first()
            val saved = client.savedCatalogItem(type, id)
            val lookupID = saved?.imdbId ?: saved?.tmdbId?.let { "tmdb:$it" } ?: id
            val fallback = saved?.let { item ->
                val savedSeason = item.season
                val savedEpisode = item.episode
                Meta(id = id, type = type, title = item.title, year = item.year, poster = item.posterUrl,
                    backdrop = item.backdropUrl, description = item.overview,
                    videos = if (savedSeason != null && savedEpisode != null) listOf(
                        Episode(savedSeason, savedEpisode, item.title, id = id)) else emptyList())
            }
            fallback?.let(::showMeta)
            val meta = try {
                services.meta.meta(type, lookupID, onBase = { base ->
                    showMeta(base.copy(id = id, videos = fallback?.videos?.takeIf { it.isNotEmpty() } ?: base.videos))
                })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { null }
            val complete = meta?.let { base ->
                base.copy(id = id, videos = fallback?.videos?.takeIf { it.isNotEmpty() } ?: base.videos)
            } ?: fallback
            if (complete == null) {
                _state.value = _state.value.copy(loading = false, failed = true)
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

    private fun showMeta(meta: Meta) {
        val known = _state.value.meta
        val displayed = meta.copy(
            poster = meta.poster ?: known?.poster,
            backdrop = meta.backdrop ?: known?.backdrop,
            logo = meta.logo ?: known?.logo,
            description = meta.description ?: known?.description,
        )
        _state.value = _state.value.copy(
                meta = displayed,
                loading = false,
                failed = false,
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
        scope.launch {
            client.activeLibrary.markWatched(
                canonicalId = id,
                mediaType = type,
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

    fun pick(meta: Meta, progress: ContinueItem?, todayIso: String? = null): PlayPick {
        if (meta.type != "series") return moviePick(progress)
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
        if (fraction in STARTED..<WATCHED && ordered.any { it.season == season && it.episode == episode }) {
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

    private fun resumeMillis(progress: ContinueItem?): Long? = progress
        ?.takeIf { it.progressFraction in STARTED..<WATCHED }
        ?.positionMs
        ?.takeIf { it > 0L }

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

    /** "44 min · 12 March", or whichever half exists (§9.4, §9.9). */
    fun episodeLine(runtimeMinutes: Int?, released: String?): String = listOfNotNull(
        runtimeMinutes?.takeIf { it > 0 }?.let { "$it min" },
        airDate(released),
    ).joinToString(" · ")

    /** A supplied full date. Invalid dates remain unknown. */
    fun airDate(released: String?): String? {
        val date = date(released) ?: return null
        val name = MONTHS[date.monthValue - 1]
        return "${date.dayOfMonth} $name ${date.year}"
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
