package com.fourseveneightnine.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumePolicyTest {
    private val now = 1_000_000_000L

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
        )
        val decoded = ResumePoint.decode(original.encode())
        assertEquals(original, decoded)
        assertNull(ResumePoint.decode("not json"))
        assertNull(ResumePoint.decode(null))
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
