package com.fourseveneightnine.tv.startup

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverBootPolicyTest {
    @Test
    fun launchesOnlyForBootAndOwnPackageReplacement() {
        assertTrue(ReceiverBootPolicy.shouldLaunch(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(ReceiverBootPolicy.shouldLaunch(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertFalse(ReceiverBootPolicy.shouldLaunch(Intent.ACTION_SCREEN_ON))
        assertFalse(ReceiverBootPolicy.shouldLaunch(null))
    }
}
