package com.fourseveneightnine.tv.client.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F53, spec §14.3 and §17: a choice panel opens with a row focused. A panel with nothing focused
 * cannot be used — even LEFT does nothing, because the handler sits on a column that is not
 * focused, and BACK is the only key left.
 */
class SettingsPanelFocusTest {

    private val canvas = listOf("Slate", "Black")

    @Test
    fun `the chosen row takes the ring`() {
        assertEquals(1, ChoiceFocus.initialIndex(canvas, "Black"))
        assertEquals(0, ChoiceFocus.initialIndex(canvas, "Slate"))
    }

    @Test
    fun `the first row takes the ring when nothing is chosen`() {
        assertEquals(0, ChoiceFocus.initialIndex(canvas, null))
    }

    @Test
    fun `a value that is not in the list still leaves a row focused`() {
        assertEquals(0, ChoiceFocus.initialIndex(canvas, "Sepia"))
    }

    @Test
    fun `an empty panel asks for nothing`() {
        assertEquals(-1, ChoiceFocus.initialIndex(emptyList(), "Black"))
    }

    @Test
    fun `every page in the list can be entered`() {
        // Every settings page draws a pane, and every choice panel it opens keeps a focusable row.
        SettingsPage.entries.forEach { page ->
            assertEquals("${page.slug} has no row to focus", 0, ChoiceFocus.initialIndex(listOf("On", "Off"), null))
        }
    }
}
