package com.fourseveneightnine.phone

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PhoneSettingsStoreTest {
    @Test
    fun anUnsetOrUnreadableRuleWaitsForWiFi() {
        assertEquals(DownloadNetworkRule.UnmeteredOnly, PhoneSettingsPolicy.DEFAULT_DOWNLOAD_NETWORK)
        assertEquals(DownloadNetworkRule.UnmeteredOnly, PhoneSettingsPolicy.decodeDownloadNetwork(null))
        assertEquals(DownloadNetworkRule.UnmeteredOnly, PhoneSettingsPolicy.decodeDownloadNetwork(""))
        assertEquals(
            DownloadNetworkRule.UnmeteredOnly,
            PhoneSettingsPolicy.decodeDownloadNetwork("anyconnection"),
        )
        assertEquals(
            DownloadNetworkRule.UnmeteredOnly,
            PhoneSettingsPolicy.decodeDownloadNetwork("a rule from a later version"),
        )
    }

    @Test
    fun everyRuleSurvivesAWrite() {
        DownloadNetworkRule.entries.forEach { rule ->
            assertEquals(rule, PhoneSettingsPolicy.decodeDownloadNetwork(PhoneSettingsPolicy.encode(rule)))
        }
    }

    @Test
    fun theRuleDecidesTheConstraintTheDownloadIsQueuedWith() {
        assertEquals(
            NetworkType.UNMETERED,
            PhoneSettingsPolicy.networkType(DownloadNetworkRule.UnmeteredOnly),
        )
        assertEquals(
            NetworkType.CONNECTED,
            PhoneSettingsPolicy.networkType(DownloadNetworkRule.AnyConnection),
        )
    }

    @Test
    fun theButtonNamesTheOtherRuleAndTheLineNamesThisOne() {
        DownloadNetworkRule.entries.forEach { rule ->
            val other = PhoneSettingsPolicy.toggled(rule)
            assertNotEquals(rule, other)
            assertEquals(rule, PhoneSettingsPolicy.toggled(other))
            assertNotEquals(
                PhoneSettingsPolicy.downloadNetworkLine(rule),
                PhoneSettingsPolicy.downloadNetworkLine(other),
            )
            assertNotEquals(
                PhoneSettingsPolicy.downloadNetworkAction(rule),
                PhoneSettingsPolicy.downloadNetworkAction(other),
            )
        }
        assertEquals(
            "Downloads wait for Wi-Fi.",
            PhoneSettingsPolicy.downloadNetworkLine(DownloadNetworkRule.UnmeteredOnly),
        )
        assertEquals(
            "Allow downloads on mobile data",
            PhoneSettingsPolicy.downloadNetworkAction(DownloadNetworkRule.UnmeteredOnly),
        )
    }

    @Test
    fun theQueueLineCountsJobsAndNamesNoTitle() {
        assertEquals("No downloads are waiting.", PhoneSettingsPolicy.queueSummary(0))
        assertEquals("1 download in the queue.", PhoneSettingsPolicy.queueSummary(1))
        assertEquals("4 downloads in the queue.", PhoneSettingsPolicy.queueSummary(4))
    }
}
