package com.fourseveneightnine.tv.client.data.streams

/** Show the list, or start playing. `TV_DESIGN_SPEC.md` §10.5. */
sealed interface AutoPickDecision {
    /** Open the top row without showing the list. The card over Detail names what it picked. */
    data class PlayBest(val choice: RankedRow, val why: String) : AutoPickDecision

    /** Show the Streams screen with the top row focused. */
    data class ShowList(val reason: Reason) : AutoPickDecision

    enum class Reason {
        /** Auto-play is off, or the rules are off. */
        RULES_OFF,

        /** Fewer eligible rows in hand than [PlaybackRules.autoPlayMinSources]. */
        TOO_FEW_SOURCES,

        /** Nothing is eligible, or nothing was found at all. */
        NOTHING_ELIGIBLE,

        /** The best row carries a caution, so the viewer gets to look before it starts. */
        BEST_ROW_HAS_A_CAUTION,
    }
}

/**
 * Decides between playing the best row and showing the list.
 *
 * The bar is deliberately high. Auto-play is the one place this app commits on the viewer's behalf
 * with nothing on screen to argue with, so it only commits when the rules asked for it, enough
 * sources are in hand that the first add-on to answer cannot win by default, and the row it picked
 * carries no caution. A caution means the ranker knows something is off — a size over the cap, a
 * DV profile this box cannot decode, a word the owner avoids — and that is exactly the moment to
 * let a person look.
 */
object AutoPick {

    fun decide(
        ranked: List<RankedRow>,
        rules: PlaybackRules = PlaybackRules.DEFAULT,
    ): AutoPickDecision {
        val eligible = ranked.filter(RankedRow::eligible)
        if (eligible.isEmpty()) return AutoPickDecision.ShowList(AutoPickDecision.Reason.NOTHING_ELIGIBLE)
        if (!rules.enabled || !rules.autoPlay) {
            return AutoPickDecision.ShowList(AutoPickDecision.Reason.RULES_OFF)
        }
        if (!rules.mayAutoPlay(eligible.size)) {
            return AutoPickDecision.ShowList(AutoPickDecision.Reason.TOO_FEW_SOURCES)
        }
        val best = eligible.first()
        if (best.cautions.isNotEmpty()) {
            return AutoPickDecision.ShowList(AutoPickDecision.Reason.BEST_ROW_HAS_A_CAUTION)
        }
        return AutoPickDecision.PlayBest(choice = best, why = describe(best))
    }

    /** The line 2 the "Finding a source" card prints: "AIOStreams · 1080p · cached" (§10.5). */
    fun describe(choice: RankedRow): String = listOfNotNull(
        choice.row.addonName,
        choice.row.quality,
        "cached".takeIf { choice.row.cachedHint.cached },
    ).joinToString(" · ")
}
