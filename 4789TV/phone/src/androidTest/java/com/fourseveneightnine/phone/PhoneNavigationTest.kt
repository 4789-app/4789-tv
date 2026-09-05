package com.fourseveneightnine.phone

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsDisplayed
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PhoneNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun exactlyTwoDoorsSwitchAndDetailReturnsToWall() {
        returnToDiscover()
        compose.onNodeWithContentDescription("Open Discover").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open Wall").performClick()

        waitForTag("door-wall")
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithTag("poster-tile", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithTag("poster-tile", useUnmergedTree = true)[0].performClick()

        waitForTag("detail-title")
        compose.onNodeWithText("Back").performClick()
        waitForTag("door-wall")
    }

    @Test
    fun sourceConfigurationIsReachableAndRejectsInsecureManifest() {
        returnToDiscover()
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithContentDescription("Open ", substring = true)
                .fetchSemanticsNodes().size > 2
        }
        compose.onAllNodesWithContentDescription("Open ", substring = true)[2].performClick()
        waitForTag("detail-title")
        compose.onNodeWithTag("detail-list").performScrollToIndex(8)
        compose.onNodeWithText("Source configuration").performScrollTo().performClick()
        compose.onNodeWithText("Manifest URL").performTextInput("http://addon.example/manifest.json")
        compose.onNodeWithText("Save source").performClick()
        compose.onNodeWithText(
            "Not saved. Use an HTTPS URL ending in /manifest.json without query credentials.",
        ).assertIsDisplayed()
    }

    @Test
    fun settingsIsADrillInAndReturnsToTheSameDoor() {
        returnToDiscover()
        compose.onNodeWithText("Settings").performClick()
        waitForTag("settings-screen")
        compose.onNodeWithContentDescription("Open Discover").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open Wall").assertDoesNotExist()
        compose.onNodeWithText("Back").performClick()
        waitForTag("door-discover")
    }

    @Test
    fun searchFiltersBothDoorsAndShowsAnEmptyState() {
        returnToDiscover()
        compose.onNodeWithTag("catalog-search").performTextInput("__no_catalog_match__")
        compose.onNodeWithTag("catalog-empty").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open Wall").performClick()
        waitForTag("door-wall")
        compose.onNodeWithTag("catalog-empty").assertIsDisplayed()
    }

    @Test
    fun mediaFilterSurvivesDoorSwitching() {
        returnToDiscover()
        compose.onNodeWithTag("catalog-filter-series").performClick()
        compose.onNodeWithContentDescription("Open Wall").performClick()
        waitForTag("door-wall")
        compose.onNodeWithTag("catalog-filter-series").assertIsDisplayed()
    }

    @Test
    fun settingsUsesConfirmationBeforeClearingPlaybackPositions() {
        returnToDiscover()
        compose.onNodeWithText("Settings").performClick()
        waitForTag("settings-screen")
        compose.onNodeWithText("Clear saved playback positions").performScrollTo().performClick()
        compose.onNodeWithTag("confirm-progress-removal").assertIsDisplayed().performClick()
        compose.onNodeWithTag("settings-status").assertIsDisplayed()
    }

    @Test
    fun theDownloadNetworkRuleIsADrillInControlThatKeepsItsChoice() {
        returnToDiscover()
        compose.onNodeWithText("Settings").performClick()
        waitForTag("settings-screen")
        compose.onNodeWithTag("download-queue-summary").performScrollTo().assertIsDisplayed()
        val store = PhoneSettingsStore(compose.activity)
        val before = store.downloadNetworkRule()

        compose.onNodeWithTag("download-network-toggle").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(PhoneSettingsPolicy.toggled(before), store.downloadNetworkRule())
        compose.onNodeWithText(
            PhoneSettingsPolicy.downloadNetworkLine(PhoneSettingsPolicy.toggled(before)),
        ).assertIsDisplayed()
        // Put it back, so the next test in this run starts where this one found it.
        compose.onNodeWithTag("download-network-toggle").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(before, store.downloadNetworkRule())
        compose.onNodeWithText("Back").performScrollTo().performClick()
        waitForTag("door-discover")
    }

    private fun waitForTag(tag: String) {
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }

    private fun returnToDiscover() {
        repeat(5) {
            if (compose.onAllNodesWithTag("door-discover").fetchSemanticsNodes().isNotEmpty()) return
            if (compose.onAllNodesWithTag("door-wall").fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithContentDescription("Open Discover").performClick()
                waitForTag("door-discover")
                return
            }
            compose.activity.onBackPressedDispatcher.onBackPressed()
            compose.waitForIdle()
        }
        waitForTag("door-discover")
    }
}
