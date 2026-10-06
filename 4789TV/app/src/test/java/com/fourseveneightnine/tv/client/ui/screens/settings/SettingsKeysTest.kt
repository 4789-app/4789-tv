package com.fourseveneightnine.tv.client.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsKeysTest {

    @Test
    fun `screen fit steps by a half point and clamps to three and seven`() {
        assertEquals(5.5f, SettingsKeys.nudgeScreenFit(5.0f, 1))
        assertEquals(4.5f, SettingsKeys.nudgeScreenFit(5.0f, -1))
        assertEquals(7.0f, SettingsKeys.nudgeScreenFit(7.0f, 1))
        assertEquals(3.0f, SettingsKeys.nudgeScreenFit(3.0f, -1))
        assertEquals(3.0f, SettingsKeys.nudgeScreenFit(5.0f, -20))
    }

    @Test
    fun `the screen fit label drops a trailing zero`() {
        assertEquals("5%", SettingsKeys.screenFitLabel(5.0f))
        assertEquals("5.5%", SettingsKeys.screenFitLabel(5.5f))
    }

    @Test
    fun `a catalog order round-trips`() {
        val order = listOf("a|movie|top", "b|series|new")
        assertEquals(order, SettingsKeys.parseStringList(SettingsKeys.encodeStringList(order)))
    }

    @Test
    fun `a broken or absent order reads as no override`() {
        assertEquals(emptyList<String>(), SettingsKeys.parseStringList(null))
        assertEquals(emptyList<String>(), SettingsKeys.parseStringList(""))
        assertEquals(emptyList<String>(), SettingsKeys.parseStringList("{not json"))
    }

    @Test
    fun `extra add-ons round-trip and a blank url is dropped`() {
        val addons = listOf(
            SettingsKeys.ExtraAddon("Cinemeta", "https://v3-cinemeta.strem.io/manifest.json"),
            SettingsKeys.ExtraAddon("Broken", ""),
        )
        val read = SettingsKeys.parseExtraAddons(SettingsKeys.encodeExtraAddons(addons))
        assertEquals(1, read.size)
        assertEquals("Cinemeta", read.single().name)
    }

    @Test
    fun `extra add-ons keep only validated manifest routes and deduplicate them`() {
        val addons = listOf(
            SettingsKeys.ExtraAddon("One", "https://addons.example/config"),
            SettingsKeys.ExtraAddon("Duplicate", "https://addons.example/config/manifest.json"),
            SettingsKeys.ExtraAddon("Unsafe", "file:///data/local/secret"),
        )
        val read = SettingsKeys.parseExtraAddons(SettingsKeys.encodeExtraAddons(addons))
        assertEquals(listOf("One"), read.map { it.name })
        assertEquals("https://addons.example/config/manifest.json", read.single().url)
    }

    @Test
    fun `moving a catalog returns the new order, or null at an end`() {
        val order = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), SettingsKeys.moved(order, "b", -1))
        assertEquals(listOf("a", "c", "b"), SettingsKeys.moved(order, "b", +1))
        assertNull(SettingsKeys.moved(order, "a", -1))
        assertNull(SettingsKeys.moved(order, "c", +1))
        assertNull(SettingsKeys.moved(order, "zzz", +1))
    }

    @Test
    fun `an add-on's catalogs move together`() {
        val blocks = listOf(listOf("a1", "a2"), listOf("b1"), listOf("c1", "c2"))
        assertEquals(
            listOf("b1", "a1", "a2", "c1", "c2"),
            SettingsKeys.movedBlocks(blocks, 0, +1),
        )
        assertEquals(
            listOf("a1", "a2", "c1", "c2", "b1"),
            SettingsKeys.movedBlocks(blocks, 2, -1),
        )
        assertNull(SettingsKeys.movedBlocks(blocks, 0, -1))
        assertNull(SettingsKeys.movedBlocks(blocks, 2, +1))
        assertNull(SettingsKeys.movedBlocks(blocks, 9, +1))
    }
}
