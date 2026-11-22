package com.fourseveneightnine.tv.client.playback

import com.fourseveneightnine.tv.client.data.streams.AutoPick
import com.fourseveneightnine.tv.client.data.streams.AutoPickDecision
import com.fourseveneightnine.tv.client.data.streams.PlaybackRules
import com.fourseveneightnine.tv.client.data.streams.RankedRow
import com.fourseveneightnine.tv.client.data.streams.StreamRanker
import com.fourseveneightnine.tv.client.data.streams.StreamRow
import com.fourseveneightnine.tv.client.data.streams.StreamSearchState
import com.fourseveneightnine.tv.client.data.streams.OriginalAudioLanguage
import com.fourseveneightnine.tv.client.data.streams.debrid.DebridOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull

/**
 * The two decisions a Play press makes, with no Android and no graph in sight: when to commit to a
 * row, and what to say when the resolve failed.
 *
 * They live here rather than inside [DefaultPlayFlow] because that class needs an `Application` to
 * exist, and these are the parts worth pinning with a test.
 */
internal class PlayPlanner(
    private val rules: PlaybackRules,
    private val codecs: Set<String>,
    private val originalLanguage: String?,
    private val prepare: suspend (com.fourseveneightnine.tv.client.data.streams.StreamRow) -> com.fourseveneightnine.tv.client.data.streams.StreamRow? = { it },
) {

    /**
     * Ranks each published state and returns the first row auto-pick will commit to, or null when
     * the search finished without one.
     *
     * `firstOrNull` cancels the search the moment a decision lands, so a box with eleven sources in
     * hand can start before the slowest add-on's 20 s deadline. Every
     * "show the list" answer before `done` is provisional: the row that would flip it may still
     * arrive, so only a commit ends this.
     */
    suspend fun choose(states: Flow<StreamSearchState>): RankedRow? =
        (chooseWithFavorite(states) as? Choice.Ranked)?.row

    sealed interface Choice {
        data class Ranked(val row: RankedRow) : Choice
        data object FavoriteOpened : Choice
    }

    /** Wait for the chosen source within the existing search deadline, then keep the same search. */
    suspend fun chooseWithFavorite(
        states: Flow<StreamSearchState>,
        favorite: FavoriteSource? = null,
        unavailable: FavoriteSource? = null,
        resumingEpisode: Boolean = false,
        openRanked: suspend (StreamRow) -> Boolean = { true },
        openFavorite: suspend (StreamRow) -> Boolean = { false },
    ): Choice? {
        var pending = favorite
        var failed = unavailable
        val rejected = mutableSetOf<String>()
        var inspected = 0
        var validationBudgetMillis = 20_000L
        return states.mapNotNull { state ->
            val wanted = pending
            if (wanted != null) {
                val row = state.rows.firstOrNull { it.readyHint && wanted.matches(it) }
                if (row != null) {
                    pending = null
                    if (openFavorite(row)) return@mapNotNull Choice.FavoriteOpened
                    failed = FavoriteSource.from(row)
                } else if (!state.done) {
                    return@mapNotNull null
                } else {
                    pending = null
                }
            }
            val rows = failed?.let { rejected -> state.rows.filterNot(rejected::matches) } ?: state.rows
            val ranked = StreamRanker.rank(OriginalAudioLanguage.sources(rows.filter { it.readyHint }, originalLanguage), rules, codecs)
                .filterNot { "${it.row.id}|${it.row.url}" in rejected }
            val decision = AutoPick.decide(ranked, rules)
            val first = (decision as? AutoPickDecision.PlayBest)?.choice?.takeIf {
                state.done || (it.row.identityVerified == true && (it.row.cachedHint.verified || it.row.linkCheckedAtMillis != null ||
                    (resumingEpisode && it.row.cachedDebrid)))
            }
                ?: if (state.done && decision is AutoPickDecision.ShowList &&
                    decision.reason in setOf(AutoPickDecision.Reason.TOO_FEW_SOURCES, AutoPickDecision.Reason.BEST_ROW_HAS_A_CAUTION)
                ) ranked.firstOrNull { it.eligible && it.cautions.isEmpty() } else null
            if (first == null) return@mapNotNull null
            val candidates = if (state.done) ranked.filter { it.eligible && it.cautions.isEmpty() } else listOf(first)
            for (candidate in candidates) {
                if (inspected >= 5 || validationBudgetMillis <= 0) break
                inspected++
                val started = System.nanoTime()
                val ready = kotlinx.coroutines.withTimeoutOrNull(minOf(8_000L, validationBudgetMillis)) {
                    prepare(candidate.row)
                }
                validationBudgetMillis -= (System.nanoTime() - started) / 1_000_000L
                if (ready != null && openRanked(ready)) return@mapNotNull Choice.Ranked(candidate.copy(row = ready))
                rejected += "${candidate.row.id}|${candidate.row.url}"
            }
            null
        }.firstOrNull()
    }

    companion object {
        // `client-data/API-A.md` §"Press Play". Each sentence names the viewer's next move, and
        // none of them says "error": a viewer cannot act on the word "error".
        const val MISSING_KEY = "Add a debrid key in Settings."
        const val NOT_CACHED = "That copy is not on your debrid account yet."
        const val NO_ANSWER = "That source did not answer. Try another."

        /** The sentence for a resolve that did not succeed, or null when it did. */
        fun failure(outcome: DebridOutcome): PlayResult.Failed? = when (outcome) {
            is DebridOutcome.Success -> null
            DebridOutcome.MissingApiKey -> PlayResult.Failed(MISSING_KEY)
            DebridOutcome.NotCached -> PlayResult.Failed(NOT_CACHED)
            DebridOutcome.TorboxPreparing -> PlayResult.Failed("TorBox is still preparing this file. Try again.")
            DebridOutcome.Stale, DebridOutcome.Error -> PlayResult.Failed(NO_ANSWER)
        }
    }
}
