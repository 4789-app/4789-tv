package com.fourseveneightnine.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.fourseveneightnine.tv.protocol.PhoneRecents
import com.fourseveneightnine.tv.ui.settings.TVSettingsPresentationSnapshot
import com.fourseveneightnine.tv.ui.settings.redactedCredentialLabel

class TVReceiverPresentationPolicyTest {
    @Test
    fun `more stepping and afr presentation are honest`() {
        assertEquals(0, TVReceiverPresentationPolicy.steppedIndex(3, 2, 1))
        assertEquals(2, TVReceiverPresentationPolicy.steppedIndex(3, 0, -1))
        assertNull(TVReceiverPresentationPolicy.steppedIndex(0, 0, 1))
        assertEquals(1, TVReceiverPresentationPolicy.steppedIndex(3, -4, 1))
        assertEquals("AFR OFF", TVReceiverPresentationPolicy.afrPresentation(false, 24f))
        assertEquals("AFR AUTO", TVReceiverPresentationPolicy.afrPresentation(true, 0f))
        assertEquals("AFR 24.00", TVReceiverPresentationPolicy.afrPresentation(true, 24f))
    }
    @Test
    fun `design canvas scales from physical framebuffer rather than Android density`() {
        assertEquals(1f, TvTokens.designScale(1920, 1080), 0.0001f)
        assertEquals(2f, TvTokens.designScale(3840, 2160), 0.0001f)
        assertEquals(2f / 3f, TvTokens.designScale(1280, 720), 0.0001f)
        assertEquals(96, TvTokens.Geometry.ScreenPaddingDp)
        assertEquals(54, TvTokens.Geometry.ScreenVerticalPaddingDp)
        assertEquals(64f, TvTokens.Geometry.ScreenPaddingDp * TvTokens.designScale(1280, 720), 0.0001f)
        assertEquals(36f, TvTokens.Geometry.ScreenVerticalPaddingDp * TvTokens.designScale(1280, 720), 0.0001f)
        val usableWidthFraction = (1920 - 2 * TvTokens.Geometry.ScreenPaddingDp) / 1920f
        val usableHeightFraction = (1080 - 2 * TvTokens.Geometry.ScreenVerticalPaddingDp) / 1080f
        assertEquals(0.90f, usableWidthFraction, 0.0001f)
        assertEquals(0.90f, usableHeightFraction, 0.0001f)
        assertTrue(usableWidthFraction >= 0.80f)
        assertTrue(usableHeightFraction >= 0.80f)
        assertTrue(TvTokens.Geometry.PrimaryActionHeightDp >= 60)
        assertTrue(TvTokens.Geometry.DetailPosterHeightDp >= 720)
        assertTrue(TvTokens.Geometry.DetailSourceGridHeightDp >= 270)
        assertTrue(TvTokens.Motion.MoreRailMillis <= 300L)
    }

    @Test
    fun `screen stack keeps playback mounted only for natural completion`() {
        assertTrue(TVReceiverPresentationPolicy.mountedSurfaces(TVReceiverPresentationPolicy.Surface.Home).isEmpty())
        assertEquals(
            setOf(TVReceiverPresentationPolicy.Surface.Play),
            TVReceiverPresentationPolicy.mountedSurfaces(TVReceiverPresentationPolicy.Surface.Play),
        )
        assertEquals(
            setOf(TVReceiverPresentationPolicy.Surface.Play, TVReceiverPresentationPolicy.Surface.Ended),
            TVReceiverPresentationPolicy.mountedSurfaces(TVReceiverPresentationPolicy.Surface.Ended),
        )
        assertEquals(
            TVReceiverPresentationPolicy.BackTarget.Detail,
            TVReceiverPresentationPolicy.backFromPlayback(TVReceiverPresentationPolicy.PlaybackOrigin.Detail),
        )
        assertEquals(
            TVReceiverPresentationPolicy.BackTarget.Library,
            TVReceiverPresentationPolicy.backFromPlayback(TVReceiverPresentationPolicy.PlaybackOrigin.Library),
        )
    }

    @Test
    fun `navigation resets on rapid destination selection and empty shelves stay on nav`() {
        val lists = TVReceiverPresentationPolicy.selectDestination(TVLibraryDestination.LetterboxdLists)
        assertEquals(TVReceiverPresentationPolicy.HomeFocusZone.Navigation, lists.focusZone)
        assertEquals(0, lists.row)
        assertEquals(0, lists.column)
        assertEquals(lists, TVReceiverPresentationPolicy.selectDestination(TVLibraryDestination.LetterboxdLists))
        assertEquals(lists, TVReceiverPresentationPolicy.moveDown(lists, hasShelves = false))

        val tamil = TVReceiverPresentationPolicy.selectDestination(TVLibraryDestination.TamilMV)
        val hero = TVReceiverPresentationPolicy.moveDown(tamil, hasShelves = true)
        assertEquals(TVReceiverPresentationPolicy.HomeFocusZone.Hero, hero.focusZone)
        assertEquals(TVReceiverPresentationPolicy.HomeFocusZone.Shelf, TVReceiverPresentationPolicy.moveDown(hero, true).focusZone)
    }

    @Test
    fun `navigation chrome stays available while browsing a shelf`() {
        assertEquals(
            TVReceiverPresentationPolicy.NavigationChrome.Visible,
            TVReceiverPresentationPolicy.navigationChromeFor(TVReceiverPresentationPolicy.HomeFocusZone.Navigation),
        )
        val shelf = TVReceiverPresentationPolicy.navigationChromeFor(TVReceiverPresentationPolicy.HomeFocusZone.Shelf)
        assertEquals(TVReceiverPresentationPolicy.NavigationChrome.Visible, shelf)
        assertEquals(shelf, TVReceiverPresentationPolicy.navigationChromeFor(TVReceiverPresentationPolicy.HomeFocusZone.Shelf))
        assertEquals(
            TVReceiverPresentationPolicy.NavigationChrome.Visible,
            TVReceiverPresentationPolicy.navigationChromeFor(TVReceiverPresentationPolicy.HomeFocusZone.Hero),
        )
    }

    @Test
    fun `presentation reducer preserves playback origins and rejects stale Back`() {
        val home = TVReceiverPresentationPolicy.PresentationState(TVReceiverPresentationPolicy.Surface.Home)
        val detail = TVReceiverPresentationPolicy.PresentationState(
            TVReceiverPresentationPolicy.Surface.Play,
            TVReceiverPresentationPolicy.PlaybackOrigin.Detail,
        )
        val detailBack = TVReceiverPresentationPolicy.reduce(
            detail,
            TVReceiverPresentationPolicy.Event.Back,
        )
        assertEquals(TVReceiverPresentationPolicy.Surface.Detail, detailBack.surface)
        assertEquals(detailBack, TVReceiverPresentationPolicy.reduce(detailBack, TVReceiverPresentationPolicy.Event.Back))

        val libraryPlay = TVReceiverPresentationPolicy.PresentationState(TVReceiverPresentationPolicy.Surface.Play)
        assertEquals(TVReceiverPresentationPolicy.Surface.Home, TVReceiverPresentationPolicy.reduce(
            libraryPlay,
            TVReceiverPresentationPolicy.Event.Back,
        ).surface)
        assertEquals(home, TVReceiverPresentationPolicy.reduce(home, TVReceiverPresentationPolicy.Event.Back))
    }

    @Test
    fun `home idle transition is reversible and repeated events are deterministic`() {
        val home = TVReceiverPresentationPolicy.PresentationState(TVReceiverPresentationPolicy.Surface.Home)
        val idle = TVReceiverPresentationPolicy.reduce(home, TVReceiverPresentationPolicy.Event.HomeBack)
        assertEquals(TVReceiverPresentationPolicy.Surface.Idle, idle.surface)
        assertEquals(idle, TVReceiverPresentationPolicy.reduce(idle, TVReceiverPresentationPolicy.Event.HomeBack))
        assertEquals(TVReceiverPresentationPolicy.Surface.Home, TVReceiverPresentationPolicy.reduce(
            idle,
            TVReceiverPresentationPolicy.Event.EnterHome,
        ).surface)
    }

    @Test
    fun `empty destinations and missing episodes have no invisible focus target`() {
        assertFalse(TVReceiverPresentationPolicy.canFocusHero(hasShelves = false))
        assertFalse(TVReceiverPresentationPolicy.canFocusEpisodes(hasEpisodeMetadata = false))
        assertEquals(
            TVReceiverPresentationPolicy.DetailFocusZone.Sources,
            TVReceiverPresentationPolicy.initialDetailFocus(isSeries = true, hasEpisodeMetadata = false),
        )
    }

    @Test
    fun `detail return falls back only when its originating card no longer exists`() {
        assertEquals(
            TVReceiverPresentationPolicy.DetailReturnZone.Card,
            TVReceiverPresentationPolicy.detailReturnZone(cardTargetExists = true, heroTargetExists = true),
        )
        assertTrue(TVReceiverPresentationPolicy.shouldPlaceInitialDetailFocus(alreadyPlaced = false, targetExists = true))
        assertFalse(TVReceiverPresentationPolicy.shouldPlaceInitialDetailFocus(alreadyPlaced = true, targetExists = true))
        assertEquals(
            TVReceiverPresentationPolicy.DetailReturnZone.Hero,
            TVReceiverPresentationPolicy.detailReturnZone(cardTargetExists = false, heroTargetExists = true),
        )
        assertEquals(
            TVReceiverPresentationPolicy.DetailReturnZone.Navigation,
            TVReceiverPresentationPolicy.detailReturnZone(cardTargetExists = false, heroTargetExists = false),
        )
    }

    @Test
    fun `detail waits for a terminal source state before canonical focus`() {
        assertEquals(3, TVReceiverPresentationPolicy.InitialDetailFocusFrameBudget)
        assertEquals(
            TVReceiverPresentationPolicy.InitialDetailFocusTarget.Defer,
            TVReceiverPresentationPolicy.initialDetailFocusTarget(true, isTerminalSourceState = false, hasVisibleEpisodes = false),
        )
        assertEquals(
            TVReceiverPresentationPolicy.InitialDetailFocusTarget.Defer,
            TVReceiverPresentationPolicy.initialDetailFocusTarget(false, isTerminalSourceState = false, hasVisibleEpisodes = false),
        )
        assertEquals(
            TVReceiverPresentationPolicy.InitialDetailFocusTarget.Episodes,
            TVReceiverPresentationPolicy.initialDetailFocusTarget(true, isTerminalSourceState = true, hasVisibleEpisodes = true),
        )
        assertEquals(
            TVReceiverPresentationPolicy.InitialDetailFocusTarget.Primary,
            TVReceiverPresentationPolicy.initialDetailFocusTarget(true, isTerminalSourceState = true, hasVisibleEpisodes = false),
        )
        assertEquals(
            TVReceiverPresentationPolicy.InitialDetailFocusTarget.Primary,
            TVReceiverPresentationPolicy.initialDetailFocusTarget(false, isTerminalSourceState = true, hasVisibleEpisodes = false),
        )
        // Loading -> Ready without episodes and Loading -> Error both use the real primary row.
        assertEquals(
            TVReceiverPresentationPolicy.InitialDetailFocusTarget.Primary,
            TVReceiverPresentationPolicy.initialDetailFocusTarget(true, isTerminalSourceState = true, hasVisibleEpisodes = false),
        )
        assertTrue(TVReceiverPresentationPolicy.shouldPlaceInitialDetailFocus(alreadyPlaced = false, targetExists = true))
        // A repeated Ready snapshot cannot steal focus after the canonical placement succeeded.
        assertFalse(TVReceiverPresentationPolicy.shouldPlaceInitialDetailFocus(alreadyPlaced = true, targetExists = true))
    }

    @Test
    fun `refreshing the same detail resets and replaces canonical focus`() {
        val idleOwner = TVReceiverPresentationPolicy.nonterminalDetailFocusOwner(isIdle = true, isLoading = false)
        val loadingOwner = TVReceiverPresentationPolicy.nonterminalDetailFocusOwner(isIdle = false, isLoading = true)
        assertEquals(TVReceiverPresentationPolicy.NonterminalDetailFocusOwner.IdleAction, idleOwner)
        assertEquals(TVReceiverPresentationPolicy.NonterminalDetailFocusOwner.LoadingClose, loadingOwner)
        assertFalse(idleOwner == loadingOwner)

        var placed = TVReceiverPresentationPolicy.updatedInitialDetailFocusPlacement(
            previouslyPlaced = false,
            isTerminalSourceState = true,
            placementSucceeded = true,
        )
        assertTrue(placed)

        placed = TVReceiverPresentationPolicy.updatedInitialDetailFocusPlacement(
            previouslyPlaced = placed,
            isTerminalSourceState = false,
            placementSucceeded = false,
        )
        assertFalse(placed)
        assertFalse(TVReceiverPresentationPolicy.shouldPlaceInitialDetailFocus(placed, targetExists = false))

        // A terminal result cannot consume the cycle until its lazy target is actually ready.
        placed = TVReceiverPresentationPolicy.updatedInitialDetailFocusPlacement(
            previouslyPlaced = placed,
            isTerminalSourceState = true,
            placementSucceeded = false,
        )
        assertFalse(placed)
        assertTrue(TVReceiverPresentationPolicy.shouldPlaceInitialDetailFocus(placed, targetExists = true))

        placed = TVReceiverPresentationPolicy.updatedInitialDetailFocusPlacement(
            previouslyPlaced = placed,
            isTerminalSourceState = true,
            placementSucceeded = true,
        )
        assertTrue(placed)
    }

    @Test
    fun `rail offset settles the focused card at visual index three without negative scroll`() {
        assertEquals(0, TVReceiverPresentationPolicy.railScrollOffset(0, 184))
        assertEquals(0, TVReceiverPresentationPolicy.railScrollOffset(3, 184))
        assertEquals(184, TVReceiverPresentationPolicy.railScrollOffset(4, 184))
        assertEquals(0, TVReceiverPresentationPolicy.railScrollOffset(4, -1))
    }

    @Test
    fun `jobs grid keeps first actionable row attached to nav and matching second row`() {
        assertNull(TVReceiverPresentationPolicy.jobGridUp(1))
        assertNull(TVReceiverPresentationPolicy.jobGridUp(2))
        assertEquals(4, TVReceiverPresentationPolicy.jobGridDown(1))
        assertEquals(5, TVReceiverPresentationPolicy.jobGridDown(2))
        assertEquals(1, TVReceiverPresentationPolicy.jobGridUp(4))
        assertEquals(2, TVReceiverPresentationPolicy.jobGridUp(5))
    }

    @Test
    fun `settings snapshot never receives credential values and settings focus clamps at row bounds`() {
        val snapshot = TVSettingsPresentationSnapshot.from(
            paired = true,
            syncEnabled = false,
            engineLabel = "MPV",
            autoFrameRateEnabled = true,
            diagnosticsEnabled = false,
        )
        assertEquals("IMPORTED", snapshot.pairedStatus)
        assertEquals("REDACTED", redactedCredentialLabel())
        assertNull(TVReceiverPresentationPolicy.settingsAdjacentIndex(0, -1, 8))
        assertEquals(1, TVReceiverPresentationPolicy.settingsAdjacentIndex(0, 1, 8))
        assertNull(TVReceiverPresentationPolicy.settingsAdjacentIndex(7, 1, 8))
    }

    @Test
    fun `runtime and progress facts are derived only when both values exist`() {
        assertEquals(8, TVReceiverPresentationPolicy.minutesLeft(30, 74))
        assertEquals("74% · 8m LEFT", TVReceiverPresentationPolicy.cardBadge(30, 74, isSeries = null))
        assertEquals("SERIES", TVReceiverPresentationPolicy.cardBadge(null, null, isSeries = true))
        assertNull(TVReceiverPresentationPolicy.cardBadge(null, null, isSeries = null))
        assertNull(TVReceiverPresentationPolicy.minutesLeft(null, 74))
        assertEquals(9_000L, TVReceiverPresentationPolicy.durationSeconds(150))
        assertEquals(6_660L, TVReceiverPresentationPolicy.resumeSeconds(150, 74))
    }

    @Test
    fun `chapter derivation clamps runtime facts`() {
        assertEquals(2, TVReceiverPresentationPolicy.chapterCount(30))
        assertEquals(6, TVReceiverPresentationPolicy.chapterCount(150))
        assertEquals(123L, TVReceiverPresentationPolicy.sourceSizeBytes(123L))
        assertNull(TVReceiverPresentationPolicy.sourceSizeBytes(null))
    }

    @Test
    fun `settings is gated by presentation surface not a stale playback activity flag`() {
        assertTrue(TVReceiverPresentationPolicy.canOpenSettings(TVReceiverPresentationPolicy.Surface.Home))
        assertTrue(TVReceiverPresentationPolicy.canOpenSettings(TVReceiverPresentationPolicy.Surface.Idle))
        assertFalse(TVReceiverPresentationPolicy.canOpenSettings(TVReceiverPresentationPolicy.Surface.Detail))
        assertFalse(TVReceiverPresentationPolicy.canOpenSettings(TVReceiverPresentationPolicy.Surface.Play))
        assertFalse(TVReceiverPresentationPolicy.canOpenSettings(TVReceiverPresentationPolicy.Surface.Ended))
    }

    @Test
    fun `optional recent hero fields stay backward compatible and enrich a matching local row`() {
        val legacy = PhoneRecents.sanitize(
            url = "",
            title = "Title",
            subtitle = null,
            posterUrl = "https://example.com/poster.jpg",
            positionMillis = 1L,
            durationMillis = 2L,
            timestamp = 1L,
            now = 2L,
        )!!
        assertNull(legacy.landscapeUrl)
        assertNull(legacy.overview)

        val rich = PhoneRecents.sanitize(
            url = "",
            title = "Title",
            subtitle = "Director's cut",
            posterUrl = "https://example.com/poster.jpg",
            positionMillis = 1L,
            durationMillis = 2L,
            timestamp = 1L,
            now = 2L,
            landscapeUrl = "https://example.com/backdrop.jpg",
            overview = "A real overview.",
        )!!
        assertEquals("https://example.com/poster.jpg", rich.posterUrl)
        assertEquals("https://example.com/backdrop.jpg", rich.landscapeUrl)

        val local = RecentItem(
            url = "https://receiver.example/stream",
            title = "Title",
            positionMillis = 90_000L,
            durationMillis = 120_000L,
            audioTrackIndex = 2,
        )
        val enriched = enrichLocalRecent(
            local,
            RecentItem(
                url = "",
                title = "Title",
                subtitle = rich.subtitle,
                posterUrl = rich.posterUrl,
                backdropUrl = rich.landscapeUrl,
                overview = rich.overview,
            ),
        )
        assertEquals(local.url, enriched.url)
        assertEquals(local.positionMillis, enriched.positionMillis)
        assertEquals(local.audioTrackIndex, enriched.audioTrackIndex)
        assertEquals("https://example.com/backdrop.jpg", enriched.backdropUrl)
        assertEquals("A real overview.", enriched.overview)
        assertEquals(
            "A real overview.",
            TVReceiverPresentationPolicy.recentOverviewForLocalPush(
                detailOverview = null,
                continueOverview = enriched.overview,
            ),
        )

        val newest = RecentItem(
            url = "",
            title = "Title",
            backdropUrl = "https://example.com/newest.jpg",
            overview = "Newest overview.",
            timestamp = 2L,
        )
        val older = newest.copy(
            backdropUrl = "https://example.com/older.jpg",
            overview = "Older overview.",
            timestamp = 1L,
        )
        assertEquals(
            newest.backdropUrl,
            newestPhoneRecentsByCanonicalKey(listOf(newest, older))[newest.canonicalKey]?.backdropUrl,
        )
    }

    @Test
    fun `recent metadata refresh preserves a settled nonfirst hero identity`() {
        assertEquals(
            1,
            TVLibrarySurfacePolicy.refreshedFocusIndex(
                previousDedupeKey = "second-url",
                previousCanonicalKey = "second title",
                dedupeKeys = listOf("first-url", "second-url", "third-url"),
                canonicalKeys = listOf("first title", "second title", "third title"),
            ),
        )
        assertEquals(
            1,
            TVLibrarySurfacePolicy.refreshedFocusIndex(
                previousDedupeKey = "expired-url",
                previousCanonicalKey = "second title",
                dedupeKeys = listOf("first-url", "new-second-url"),
                canonicalKeys = listOf("first title", "second title"),
            ),
        )
        assertEquals(
            0,
            TVLibrarySurfacePolicy.refreshedFocusIndex(
                previousDedupeKey = "removed-url",
                previousCanonicalKey = "removed title",
                dedupeKeys = listOf("first-url"),
                canonicalKeys = listOf("first title"),
            ),
        )
        assertEquals(
            -1,
            TVLibrarySurfacePolicy.refreshedFocusIndex(null, null, emptyList(), emptyList()),
        )
    }
}
