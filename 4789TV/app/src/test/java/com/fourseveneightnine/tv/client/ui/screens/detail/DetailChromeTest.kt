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


class CastFocusFrameTest {
    @Test
    fun `Select waits for the new cast card outline`() {
        val first = CastFocusFrame()
        val next = CastFocusFrame()
        first.onFocusChanged(true)
        assertFalse(first.canSelect)
        first.onOutlineDrawn()
        assertTrue(first.canSelect)

        first.onFocusChanged(false)
        next.onFocusChanged(true)
        first.onOutlineDrawn()
        assertFalse(first.canSelect)
        assertFalse(next.canSelect)
        next.onOutlineDrawn()
        assertTrue(next.canSelect)
    }

    @Test
    fun `returning to the same cast card needs a fresh outline`() {
        val card = CastFocusFrame()
        card.onFocusChanged(true)
        card.onOutlineDrawn()
        card.onFocusChanged(false)
        card.onFocusChanged(true)
        assertFalse(card.canSelect)
        card.onOutlineDrawn()
        assertTrue(card.canSelect)
    }
}

class DetailLogoContrastTest {
    @Test fun `transparent margins do not shrink the visible title`() {
        val pixels = IntArray(8 * 6)
        pixels[2 * 8 + 1] = 0xFFFFFFFF.toInt()
        pixels[3 * 8 + 6] = 0x01FFFFFF
        org.junit.Assert.assertArrayEquals(intArrayOf(1, 2, 6, 2), DetailLogoContrast.inkBounds(pixels, 8, 6))
    }

    @Test fun `opaque edges remain and an empty logo has no crop`() {
        org.junit.Assert.assertArrayEquals(intArrayOf(0, 0, 3, 2),
            DetailLogoContrast.inkBounds(IntArray(6) { 0xFF101010.toInt() }, 3, 2))
        assertEquals(null, DetailLogoContrast.inkBounds(IntArray(6), 3, 2))
    }

    @Test fun `dark transparent lettering gains light ink`() {
        assertTrue(DetailLogoContrast.needsLightInk(intArrayOf(0xFF121212.toInt(), 0x00121212)))
    }

    @Test fun `transparent padding does not hide colored or white ink`() {
        assertFalse(DetailLogoContrast.needsLightInk(intArrayOf(0xFFFF0000.toInt(), 0)))
        assertFalse(DetailLogoContrast.needsLightInk(intArrayOf(0xFFFFFFFF.toInt(), 0)))
        assertFalse(DetailLogoContrast.needsLightInk(intArrayOf(0xFF201080.toInt(), 0)))
    }

    @Test fun `opaque images and empty artwork are never recolored`() {
        assertFalse(DetailLogoContrast.needsLightInk(intArrayOf(0xFF101010.toInt(), 0xFF121212.toInt())))
        assertFalse(DetailLogoContrast.needsLightInk(intArrayOf(0, 0)))
        assertFalse(DetailLogoContrast.needsLightInk(intArrayOf()))
    }
}

class DetailProviderGroupsTest {
    private fun provider(id: Int, name: String) =
        com.fourseveneightnine.tv.client.data.meta.StreamingProvider(id, name, "https://example.test/$id.png")

    @Test fun `Prime variants share a badge and retain every supplied option`() {
        val groups = DetailFormat.providerGroups(listOf(
            provider(9, "Amazon Prime Video"), provider(2100, "Amazon Prime Video with Ads"),
            provider(10, "Amazon Video"), provider(9, "Amazon Prime Video"),
        ))
        assertEquals(listOf("Prime Video"), groups.keys.toList())
        assertEquals(listOf(9, 2100, 10), groups.getValue("Prime Video").map { it.id })
    }

    @Test fun `other brands and Amazon channels remain distinct`() {
        val groups = DetailFormat.providerGroups(listOf(
            provider(9, "Amazon Prime Video"), provider(8, "Netflix"),
            provider(119, "MGM+ Amazon Channel"), provider(99, "Apple TV"),
        ))
        assertEquals(listOf("Prime Video", "Netflix", "MGM+ Amazon Channel", "Apple TV"), groups.keys.toList())
        assertTrue(DetailFormat.providerGroups(emptyList()).isEmpty())
    }

    @Test fun `series metadata leaves genres and typical runtime to their own places`() {
        val meta = com.fourseveneightnine.tv.client.data.meta.Meta("series", "series", "Title",
            year = 2022, seasons = listOf(0, 1, 2, 3), runtimeMinutes = 68, genres = listOf("Fantasy", "Adventure"))
        assertEquals("2022 · 3 seasons", DetailFormat.heroMetaLine(meta))
        assertEquals("2022 · 1h 08m", DetailFormat.heroMetaLine(meta.copy(type = "movie")))
    }
}
