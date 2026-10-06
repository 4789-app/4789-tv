package com.fourseveneightnine.tv.client.playback

import com.fourseveneightnine.tv.client.data.streams.AutoPick
import com.fourseveneightnine.tv.client.data.streams.AutoPickDecision
import com.fourseveneightnine.tv.client.data.streams.PlaybackRules
import com.fourseveneightnine.tv.client.data.streams.RankedRow
import com.fourseveneightnine.tv.client.data.streams.StreamRanker
import com.fourseveneightnine.tv.client.data.streams.StreamSearchState
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
) {

    /**
     * Ranks each published state and returns the first row auto-pick will commit to, or null when
     * the search finished without one.
     *
     * `firstOrNull` cancels the search the moment a decision lands, so a box with eleven sources in
     * hand starts on the eleventh rather than waiting out the slowest add-on's 12 s deadline. Every
     * "show the list" answer before `done` is provisional: the row that would flip it may still
     * arrive, so only a commit ends this.
     */
    suspend fun choose(states: Flow<StreamSearchState>): RankedRow? = states
        .mapNotNull { state ->
            val ranked = StreamRanker.rank(state.rows, rules, codecs)
            val decision = AutoPick.decide(ranked, rules)
            (decision as? AutoPickDecision.PlayBest)?.choice
                ?: if (state.done && decision is AutoPickDecision.ShowList &&
                    decision.reason == AutoPickDecision.Reason.TOO_FEW_SOURCES
                ) ranked.firstOrNull { it.eligible && it.cautions.isEmpty() } else null
        }
        .firstOrNull()

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
            DebridOutcome.Stale, DebridOutcome.Error -> PlayResult.Failed(NO_ANSWER)
        }
    }
}
