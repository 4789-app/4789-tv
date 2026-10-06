package com.fourseveneightnine.tv.client.ui.screens.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** §9.3 and §9.10.11: when the sticky strip is up, and why it does not flicker at the boundary. */
class DetailScrollTest {

    private val threshold = 520f
    private val deadBand = 40f

    private fun show(scroll: Float, shown: Boolean) =
        DetailScroll.showStrip(scroll, threshold, deadBand, shown)

    @Test
    fun `the strip stays away while the hero is on screen`() {
        assertFalse(show(0f, shown = false))
        assertFalse(show(519f, shown = false))
    }

    @Test
    fun `the strip arrives once the hero has scrolled out`() {
        assertTrue(show(521f, shown = false))
    }

    @Test
    fun `the dead band holds the strip through a small scroll back`() {
        // 500 px is under the threshold but inside the 40 px band, so a page parked on the
        // boundary does not flash the strip on and off as the scroll settles.
        assertTrue(show(500f, shown = true))
        assertFalse(show(500f, shown = false))
    }

    @Test
    fun `the strip goes once the scroll passes below the band`() {
        assertFalse(show(479f, shown = true))
    }

    @Test
    fun `the band is measured, not guessed`() {
        assertEquals(520, DetailScroll.ShowAt.value.toInt())
        assertEquals(40, DetailScroll.DeadBand.value.toInt())
    }
}

/** §9.4 and §9.10.5: the poster's badge column. */
class DetailBadgesTest {

    @Test
    fun `nothing known gives no column`() {
        assertEquals(
            emptyList<DetailBadge>(),
            DetailBadges.of(cached = false, fourK = false, hdr = false, dolbyVision = false),
        )
    }

    @Test
    fun `the order is fixed`() {
        assertEquals(
            listOf(DetailBadge.Cached, DetailBadge.FourK, DetailBadge.Hdr),
            DetailBadges.of(cached = true, fourK = true, hdr = true, dolbyVision = false),
        )
    }

    @Test
    fun `Dolby Vision takes the one HDR slot`() {
        assertEquals(
            listOf(DetailBadge.DolbyVision),
            DetailBadges.of(cached = false, fourK = false, hdr = true, dolbyVision = true),
        )
    }

    @Test
    fun `three is the cap`() {
        val badges = DetailBadges.of(cached = true, fourK = true, hdr = true, dolbyVision = true)

        assertEquals(DetailBadges.MAX, badges.size)
        assertEquals(listOf(DetailBadge.Cached, DetailBadge.FourK, DetailBadge.DolbyVision), badges)
    }
}
