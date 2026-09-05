package com.fourseveneightnine.tv.ui

import kotlin.math.roundToInt

/** Pure screen, focus and derived-fact rules for the receiver presentation layer. */
internal object TVReceiverPresentationPolicy {
    /** A short, cancellable budget for focus targets created by lazy async-result content. */
    const val InitialDetailFocusFrameBudget = 3

    fun steppedIndex(size: Int, current: Int, direction: Int): Int? {
        if (size <= 0) return null
        return Math.floorMod(current.coerceIn(0, size - 1) + direction, size)
    }

    fun afrPresentation(enabled: Boolean, appliedRate: Float): String = when {
        !enabled -> "AFR OFF"
        appliedRate > 0f -> "AFR ${"%.2f".format(java.util.Locale.US, appliedRate)}"
        else -> "AFR AUTO"
    }
    /** Home has no overlay surface; ended deliberately keeps Play mounted beneath it. */
    enum class Surface { Home, Detail, Play, Ended, Idle }
    enum class PlaybackOrigin { Detail, Library }
    enum class BackTarget { Detail, Library }
    enum class DetailFocusZone { Episodes, Sources }
    enum class HomeFocusZone { Navigation, Hero, Shelf }
    enum class NavigationChrome { Visible, HiddenForShelf }
    enum class DetailReturnZone { Card, Hero, Navigation }
    enum class InitialDetailFocusTarget { Defer, Episodes, Primary }
    enum class NonterminalDetailFocusOwner { None, IdleAction, LoadingClose }
    enum class Event { Back, HomeBack, EnterHome }

    data class PresentationState(
        val surface: Surface,
        val playbackOrigin: PlaybackOrigin = PlaybackOrigin.Library,
    )

    data class HomeNavigationState(
        val destination: TVLibraryDestination,
        val focusZone: HomeFocusZone,
        val row: Int,
        val column: Int,
    )

    fun mountedSurfaces(screen: Surface): Set<Surface> = when (screen) {
        Surface.Home -> emptySet()
        Surface.Detail -> setOf(Surface.Detail)
        Surface.Play -> setOf(Surface.Play)
        Surface.Ended -> setOf(Surface.Play, Surface.Ended)
        Surface.Idle -> setOf(Surface.Idle)
    }

    /** Presentation ownership, rather than a stale controller activity flag, gates Settings. */
    fun canOpenSettings(surface: Surface): Boolean = surface == Surface.Home || surface == Surface.Idle

    fun backFromPlayback(origin: PlaybackOrigin): BackTarget = when (
        reduce(PresentationState(Surface.Play, origin), Event.Back).surface
    ) {
        Surface.Detail -> BackTarget.Detail
        else -> BackTarget.Library
    }

    /** Rejects stale Back events by leaving the current presentation intact. */
    fun reduce(state: PresentationState, event: Event): PresentationState = when (event) {
        Event.Back -> when (state.surface) {
            Surface.Play -> PresentationState(
                surface = if (state.playbackOrigin == PlaybackOrigin.Detail) Surface.Detail else Surface.Home,
                playbackOrigin = state.playbackOrigin,
            )
            Surface.Ended -> PresentationState(Surface.Idle)
            else -> state
        }
        Event.HomeBack -> if (state.surface == Surface.Home) PresentationState(Surface.Idle) else state
        Event.EnterHome -> if (state.surface == Surface.Idle) PresentationState(Surface.Home) else state
    }

    /** Every destination change begins at its horizontal nav tab with a zeroed shelf cursor. */
    fun selectDestination(destination: TVLibraryDestination): HomeNavigationState =
        HomeNavigationState(destination, HomeFocusZone.Navigation, row = 0, column = 0)

    fun moveDown(state: HomeNavigationState, hasShelves: Boolean): HomeNavigationState = when (state.focusZone) {
        HomeFocusZone.Navigation -> if (hasShelves) state.copy(focusZone = HomeFocusZone.Hero) else state
        HomeFocusZone.Hero -> state.copy(focusZone = HomeFocusZone.Shelf)
        else -> state
    }

    fun initialDetailFocus(isSeries: Boolean, hasEpisodeMetadata: Boolean): DetailFocusZone =
        if (isSeries && hasEpisodeMetadata) DetailFocusZone.Episodes else DetailFocusZone.Sources

    fun canFocusHero(hasShelves: Boolean): Boolean = hasShelves
    fun canFocusEpisodes(hasEpisodeMetadata: Boolean): Boolean = hasEpisodeMetadata
    /** Keep the global navigation available while browsing any shelf. */
    fun navigationChromeFor(zone: HomeFocusZone): NavigationChrome = when (zone) {
        HomeFocusZone.Shelf -> NavigationChrome.Visible
        HomeFocusZone.Navigation, HomeFocusZone.Hero -> NavigationChrome.Visible
    }

    fun detailReturnZone(cardTargetExists: Boolean, heroTargetExists: Boolean): DetailReturnZone = when {
        cardTargetExists -> DetailReturnZone.Card
        heroTargetExists -> DetailReturnZone.Hero
        else -> DetailReturnZone.Navigation
    }

    fun initialDetailFocusTarget(
        isSeries: Boolean,
        isTerminalSourceState: Boolean,
        hasVisibleEpisodes: Boolean,
    ): InitialDetailFocusTarget = when {
        !isTerminalSourceState -> InitialDetailFocusTarget.Defer
        isSeries && hasVisibleEpisodes -> InitialDetailFocusTarget.Episodes
        else -> InitialDetailFocusTarget.Primary
    }

    fun shouldPlaceInitialDetailFocus(alreadyPlaced: Boolean, targetExists: Boolean): Boolean =
        !alreadyPlaced && targetExists

    fun nonterminalDetailFocusOwner(isIdle: Boolean, isLoading: Boolean): NonterminalDetailFocusOwner = when {
        isLoading -> NonterminalDetailFocusOwner.LoadingClose
        isIdle -> NonterminalDetailFocusOwner.IdleAction
        else -> NonterminalDetailFocusOwner.None
    }

    /** A new source search owns a new canonical focus handoff for the same detail item. */
    fun updatedInitialDetailFocusPlacement(
        previouslyPlaced: Boolean,
        isTerminalSourceState: Boolean,
        placementSucceeded: Boolean,
    ): Boolean = if (isTerminalSourceState) {
        previouslyPlaced || placementSucceeded
    } else {
        false
    }

    /** Focused item is the fourth visual rail slot when enough earlier items exist. */
    fun railScrollOffset(focusedIndex: Int, cardExtentPx: Int): Int =
        ((focusedIndex - 3).coerceAtLeast(0) * cardExtentPx.coerceAtLeast(0))

    /**
     * Resolves a focus preview after a recents refresh. Prefer exact playback identity, then the
     * canonical title when a sender replaced its URL, and only then fall back to the first row.
     */
    fun refreshedFocusIndex(
        previousDedupeKey: String?,
        previousCanonicalKey: String?,
        dedupeKeys: List<String>,
        canonicalKeys: List<String>,
    ): Int {
        previousDedupeKey?.let { key ->
            dedupeKeys.indexOf(key).takeIf { it >= 0 }?.let { return it }
        }
        previousCanonicalKey?.let { key ->
            canonicalKeys.indexOf(key).takeIf { it >= 0 }?.let { return it }
        }
        return if (dedupeKeys.isEmpty()) -1 else 0
    }

    /** Detail metadata wins only for the current detail; Continue metadata preserves its own row. */
    fun recentOverviewForLocalPush(detailOverview: String?, continueOverview: String?): String? =
        detailOverview?.trim()?.takeIf(String::isNotEmpty)
            ?: continueOverview?.trim()?.takeIf(String::isNotEmpty)

    /** Null is the top navigation; the receiver status tile deliberately has no focus node. */
    fun jobGridUp(index: Int): Int? = when (index) {
        4 -> 1
        5 -> 2
        else -> null
    }

    fun jobGridDown(index: Int): Int? = when (index) {
        1 -> 4
        2 -> 5
        else -> null
    }

    fun settingsAdjacentIndex(current: Int, direction: Int, size: Int): Int? =
        (current + direction).takeIf { it in 0 until size }
    fun minutesLeft(runtimeMinutes: Int?, progressPercent: Int?): Int? {
        val duration = durationSeconds(runtimeMinutes) ?: return null
        val resume = resumeSeconds(runtimeMinutes, progressPercent) ?: return null
        return ((duration - resume).coerceAtLeast(0L) / 60.0).roundToInt()
    }

    fun cardBadge(runtimeMinutes: Int?, progressPercent: Int?, isSeries: Boolean?): String? {
        val left = minutesLeft(runtimeMinutes, progressPercent)
        return if (progressPercent != null && progressPercent > 0 && left != null) {
            "${progressPercent.coerceIn(0, 100)}% · ${left}m LEFT"
        } else when (isSeries) {
            true -> "SERIES"
            false -> "MOVIE"
            null -> null
        }
    }

    fun durationSeconds(runtimeMinutes: Int?): Long? = runtimeMinutes
        ?.takeIf { it >= 0 }
        ?.times(60L)

    fun resumeSeconds(runtimeMinutes: Int?, progressPercent: Int?): Long? =
        durationSeconds(runtimeMinutes)?.let { duration ->
            progressPercent?.let { (duration * it.coerceIn(0, 100) / 100f).roundToInt().toLong() }
        }

    fun chapterCount(runtimeMinutes: Int?): Int? = runtimeMinutes
        ?.takeIf { it > 0 }
        ?.let { (it / 25f).roundToInt().coerceIn(2, 8) }

    fun chapterFractionsForDuration(durationSeconds: Double): List<Float> {
        if (durationSeconds <= 0.0) return emptyList()
        val count = chapterCount((durationSeconds / 60.0).roundToInt()) ?: return emptyList()
        return (1 until count).map { index -> index.toFloat() / count.toFloat() }
    }

    /** Source rows display only server-authoritative sizes; unknown remains unknown. */
    fun sourceSizeBytes(authoritativeBytes: Long?): Long? = authoritativeBytes?.takeIf { it >= 0L }

}
