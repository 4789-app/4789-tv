package com.fourseveneightnine.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumePolicyTest {
    private val now = 1_000_000_000L

    @Test fun `selected source recipe survives restart independently of an expired URL`() {
        val source = """{"catalogId":"tt1","catalogTitle":"Film","season":1,"episode":2,"torrentHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","fileIndex":7}"""
        val original = point(ageMillis = 3 * 60 * 60_000L).copy(origin = "local", profileId = "owner",
            catalogType = "series", catalogId = "tt1", season = 1, episode = 2, sourceRecipe = source)
        val restored = requireNotNull(ResumePoint.decode(original.encode()))
        assertEquals(source, restored.sourceRecipe)
        assertEquals(original.positionMillis, restored.positionMillis)
        assertFalse(ResumePolicy.linkIsRecent(restored, now))
    }

    private fun point(
        positionMillis: Long = 10 * 60_000L,
        durationMillis: Long = 120 * 60_000L,
        ageMillis: Long = 0,
        url: String = "https://cdn/stream.mkv",
    ) = ResumePoint(
        url = url,
        title = "Romeo",
        positionMillis = positionMillis,
        durationMillis = durationMillis,
        savedAtMillis = now - ageMillis,
    )

    @Test fun `watchlist restart persists only the source reference and position`() {
        val saved = point(url = "https://example.test/?secret").copy(origin = "local", catalogId = "tt1",
            headers = mapOf("Authorization" to "credential"), watchlistId = "watch-source:123")
        val encoded = saved.encode()
        assertFalse("secret" in encoded || "credential" in encoded)
        val restored = requireNotNull(ResumePoint.decode(encoded))
        assertEquals(saved.watchlistId, restored.watchlistId)
        assertEquals(saved.positionMillis, restored.positionMillis)
    }

    @Test
    fun automaticResumeKeepsEarlyPositionAndCastIdentityButLiveHasNoSeek() {
        val session = point(positionMillis = 9_000L).copy(origin = "phone", castId = "cast-1", paused = true)
        val decoded = requireNotNull(ResumePoint.decode(session.encode()))
        assertTrue(decoded.paused)
        assertEquals("cast-1", decoded.mediaRequest().castId)
        assertEquals(9_000L, decoded.mediaRequest().startPositionMs)
        assertNull(decoded.copy(isLive = true).mediaRequest().startPositionMs)
    }

    @Test
    fun automaticResumeRequiresTheOwningProfileAndRejectsOldManualOffers() {
        assertFalse(ResumePolicy.belongsTo(point(), "owner"))
        val local = point().copy(origin = "local", profileId = "owner")
        assertTrue(ResumePolicy.belongsTo(local, "owner"))
        assertFalse(ResumePolicy.belongsTo(local, "guest"))
        assertTrue(ResumePolicy.belongsTo(local.copy(origin = "phone"), "guest"))
        assertFalse(ResumePolicy.linkIsRecent(local.copy(savedAtMillis = now - ResumePolicy.TTL_MILLIS - 1), now))
    }

    @Test
    fun afreshCrumbInTheMiddleOfAFilmIsOffered() {
        assertTrue(ResumePolicy.shouldOffer(point(ageMillis = 5 * 60_000L), now))
    }

    @Test
    fun anExpiredCrumbIsForgotten() {
        // The TTL is about the URL, not the disk: a day-old debrid link opens into a 403.
        assertFalse(ResumePolicy.shouldOffer(point(ageMillis = 3 * 60 * 60_000L), now))
    }

    @Test
    fun theOpeningMinuteAndTheCreditsAreNotWorthResuming() {
        assertFalse(ResumePolicy.shouldOffer(point(positionMillis = 20_000L), now))
        assertFalse(
            ResumePolicy.shouldOffer(
                point(positionMillis = 120 * 60_000L - 30_000L),
                now,
            ),
        )
        assertFalse(ResumePolicy.shouldStore(positionMillis = 20_000L, durationMillis = 7_200_000L))
        assertTrue(ResumePolicy.shouldStore(positionMillis = 600_000L, durationMillis = 7_200_000L))
    }

    @Test
    fun nothingAndNonsenseAreBothIgnored() {
        assertFalse(ResumePolicy.shouldOffer(null, now))
        assertFalse(ResumePolicy.shouldOffer(point(url = ""), now))
        // A clock that moved backwards must not resurrect a crumb from the future.
        assertFalse(ResumePolicy.shouldOffer(point(ageMillis = -60_000L), now))
    }

    @Test
    fun aStoredCrumbSurvivesTheRoundTrip() {
        val original = ResumePoint(
            url = "https://cdn/s.mkv",
            title = "Romeo",
            subtitle = "2026",
            headers = mapOf("Authorization" to "Bearer x"),
            positionMillis = 90_000,
            durationMillis = 7_200_000,
            savedAtMillis = now,
            artworkURL = "https://img/b.jpg",
            logoURL = "https://img/logo.png",
        )
        val decoded = ResumePoint.decode(original.encode())
        assertEquals(original, decoded)
        assertNull(ResumePoint.decode("not json"))
        assertNull(ResumePoint.decode(null))
    }

    @Test
    fun oldResumeWithoutALogoStillDecodes() {
        assertNull(ResumePoint.decode("""{"url":"https://cdn/old.mkv","title":"Old movie"}""")?.logoURL)
        assertEquals("Old movie", ResumePoint.decode("""{"url":"https://cdn/old.mkv","title":"Old movie"}""")?.title)
    }

    @Test
    fun storedResumeNotifiesPhoneOnlyAfterOpenLandAndSeekAllSucceed() {
        assertTrue(ResumePolicy.shouldNotifyPhoneAfterStoredResume(true, true, true))
        assertFalse(ResumePolicy.shouldNotifyPhoneAfterStoredResume(false, true, true))
        assertFalse(ResumePolicy.shouldNotifyPhoneAfterStoredResume(true, false, true))
        assertFalse(ResumePolicy.shouldNotifyPhoneAfterStoredResume(true, true, false))
    }

    @Test
    fun savedOfferOutranksStaleStoppedPhaseOnlyWhenStartupIsReady() {
        assertTrue(ResumePolicy.shouldRenderStoredOffer(hasOffer = true, startupReady = true))
        assertFalse(ResumePolicy.shouldRenderStoredOffer(hasOffer = false, startupReady = true))
        assertFalse(ResumePolicy.shouldRenderStoredOffer(hasOffer = true, startupReady = false))
    }
}
