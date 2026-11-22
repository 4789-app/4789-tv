package com.fourseveneightnine.tv.client.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsPageListTest {

    @Test
    fun `connections precede viewing preferences in the settings list`() {
        assertEquals(
            listOf(
                "Setup", "Accounts & keys", "Add-ons", "Live TV",
                "Playback", "Appearance", "Discover & catalogs", "About",
            ),
            SettingsPage.entries.map { it.title },
        )
    }

    @Test
    fun `a route slug picks its page and anything else falls back to Setup`() {
        assertEquals(SettingsPage.Jobs, SettingsPage.fromSlug("jobs"))
        assertEquals(SettingsPage.Look, SettingsPage.fromSlug("LOOK"))
        assertEquals(SettingsPage.LiveTv, SettingsPage.fromSlug("live-tv"))
        assertEquals(SettingsPage.Pair, SettingsPage.fromSlug("profiles"))
        assertEquals(SettingsPage.Pair, SettingsPage.fromSlug(null))
        assertEquals(SettingsPage.Pair, SettingsPage.fromSlug("nonsense"))
    }

    @Test
    fun `no dots when everything is healthy`() {
        val dots = SettingsPageList.dots(
            addonsFailing = 0, addonsSlow = 0, jobsFailed = 0,
        )
        assertEquals(emptyMap<SettingsPage, PageDot>(), dots)
    }

    @Test
    fun `a failing add-on is an error dot and beats a slow one`() {
        val dots = SettingsPageList.dots(
            addonsFailing = 1, addonsSlow = 2, jobsFailed = 0,
        )
        assertEquals(PageDot.Error, dots[SettingsPage.Addons])
    }

    @Test
    fun `a slow add-on alone is a warning dot`() {
        val dots = SettingsPageList.dots(
            addonsFailing = 0, addonsSlow = 1, jobsFailed = 0,
        )
        assertEquals(PageDot.Warning, dots[SettingsPage.Addons])
    }

    @Test
    fun `a failed refresh shows an error while optional setup stays neutral`() {
        val dots = SettingsPageList.dots(
            addonsFailing = 0, addonsSlow = 0, jobsFailed = 2,
        )
        assertNull(dots[SettingsPage.Pair])
        assertEquals(PageDot.Error, dots[SettingsPage.Jobs])
        assertNull(dots[SettingsPage.Accounts])
        assertNull(dots[SettingsPage.Look])
    }
}
