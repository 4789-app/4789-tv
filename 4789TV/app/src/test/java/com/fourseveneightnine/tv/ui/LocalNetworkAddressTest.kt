package com.fourseveneightnine.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalNetworkAddressTest {
    @Test
    fun prefersPrivateIpv4ForManualConnection() {
        assertEquals(
            "192.168.68.59",
            LocalNetworkAddress.preferredIPv4(listOf("203.0.113.8", "192.168.68.59")),
        )
    }

    @Test
    fun fallsBackToFirstUsableAddressAndHandlesEmptyInput() {
        assertEquals("203.0.113.8", LocalNetworkAddress.preferredIPv4(listOf("203.0.113.8")))
        assertNull(LocalNetworkAddress.preferredIPv4(emptyList()))
    }
}
