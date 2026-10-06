package com.fourseveneightnine.tv.client.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstRunStepsTest {

    private fun derive(
        settingsSaved: Boolean = true,
        addonsTotal: Int = 6,
        addonsResolved: Int = 6,
        addonsFailed: Int = 0,
        catalogs: SourcePhase = SourcePhase.Ready,
        posterItems: Int = 120,
    ) = FirstRunSteps.derive(
        settingsSaved, addonsTotal, addonsResolved, addonsFailed, catalogs, posterItems,
    )

    @Test
    fun `the four rows are always drawn, in order`() {
        val progress = derive(settingsSaved = false, addonsResolved = 0)
        assertEquals(
            listOf("Settings saved", "Add-ons 0 of 6", "Catalogs", "Posters"),
            progress.steps.map { it.label },
        )
    }

    @Test
    fun `nothing below settings starts before the settings are saved`() {
        val progress = derive(settingsSaved = false, addonsResolved = 0)
        assertEquals(StepState.Running, progress.steps[0].state)
        assertEquals(StepState.Pending, progress.steps[1].state)
        assertEquals(StepState.Pending, progress.steps[2].state)
        assertEquals(StepState.Pending, progress.steps[3].state)
        assertFalse(progress.settled)
    }

    @Test
    fun `the add-on row counts up in place`() {
        assertEquals("Add-ons 4 of 6", derive(addonsResolved = 4).steps[1].label)
        assertEquals(StepState.Running, derive(addonsResolved = 4).steps[1].state)
    }

    @Test
    fun `a receiver with no add-ons settles that row at once`() {
        val progress = derive(addonsTotal = 0, addonsResolved = 0)
        assertEquals("Add-ons", progress.steps[1].label)
        assertEquals(StepState.Done, progress.steps[1].state)
    }

    @Test
    fun `a failed add-on turns the row red but still settles the screen`() {
        val progress = derive(addonsResolved = 4, addonsFailed = 2)
        assertEquals(StepState.Failed, progress.steps[1].state)
        assertTrue(progress.settled)
        assertEquals("Couldn't reach 2 add-ons. You can fix this later in Settings.", progress.note)
    }

    @Test
    fun `one failed add-on reads in the singular`() {
        assertEquals(
            "Couldn't reach 1 add-on. You can fix this later in Settings.",
            derive(addonsResolved = 5, addonsFailed = 1).note,
        )
    }

    @Test
    fun `posters wait for the catalogs and then run`() {
        assertEquals(StepState.Pending, derive(catalogs = SourcePhase.Loading).steps[3].state)
        assertEquals(StepState.Running, derive(posterItems = 0).steps[3].state)
        assertEquals(StepState.Done, derive().steps[3].state)
    }

    @Test
    fun `a failed catalog fails the posters too`() {
        val progress = derive(catalogs = SourcePhase.Failed, posterItems = 0)
        assertEquals(StepState.Failed, progress.steps[2].state)
        assertEquals(StepState.Failed, progress.steps[3].state)
        assertTrue(progress.settled)
    }

    @Test
    fun `everything green settles with no note`() {
        val progress = derive()
        assertTrue(progress.steps.all { it.state == StepState.Done })
        assertTrue(progress.settled)
        assertNull(progress.note)
    }
}
