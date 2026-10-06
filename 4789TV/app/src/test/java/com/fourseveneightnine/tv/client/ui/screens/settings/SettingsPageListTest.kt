package com.fourseveneightnine.tv.client.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsPageListTest {

    @Test
    fun `the list includes profiles beside pairing in concise TV order`() {
        assertEquals(
            listOf(
                "Pair & Sync", "Profiles", "Add-ons", "Accounts",
                "Playback", "Look", "Jobs", "About",
            ),
            SettingsPage.entries.map { it.title },
        )
    }

    @Test
    fun `a route slug picks its page and anything else falls back to Pair and Sync`() {
        assertEquals(SettingsPage.Jobs, SettingsPage.fromSlug("jobs"))
        assertEquals(SettingsPage.Look, SettingsPage.fromSlug("LOOK"))
        assertEquals(SettingsPage.Pair, SettingsPage.fromSlug(null))
        assertEquals(SettingsPage.Pair, SettingsPage.fromSlug("nonsense"))
    }

    @Test
    fun `no dots when everything is healthy`() {
        val dots = SettingsPageList.dots(
            paired = true, addonsFailing = 0, addonsSlow = 0, jobsFailed = 0, keysMissing = false,
        )
        assertEquals(emptyMap<SettingsPage, PageDot>(), dots)
    }

    @Test
    fun `a failing add-on is an error dot and beats a slow one`() {
        val dots = SettingsPageList.dots(
            paired = true, addonsFailing = 1, addonsSlow = 2, jobsFailed = 0, keysMissing = false,
        )
        assertEquals(PageDot.Error, dots[SettingsPage.Addons])
    }

    @Test
    fun `a slow add-on alone is a warning dot`() {
        val dots = SettingsPageList.dots(
            paired = true, addonsFailing = 0, addonsSlow = 1, jobsFailed = 0, keysMissing = false,
        )
        assertEquals(PageDot.Warning, dots[SettingsPage.Addons])
    }

    @Test
    fun `an unpaired TV marks Pair and Sync, and a failed job marks Jobs`() {
        val dots = SettingsPageList.dots(
            paired = false, addonsFailing = 0, addonsSlow = 0, jobsFailed = 2, keysMissing = true,
        )
        assertEquals(PageDot.Warning, dots[SettingsPage.Pair])
        assertEquals(PageDot.Error, dots[SettingsPage.Jobs])
        assertEquals(PageDot.Warning, dots[SettingsPage.Accounts])
        assertNull(dots[SettingsPage.Look])
    }
}
