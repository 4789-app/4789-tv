package com.fourseveneightnine.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvCastDiscoveryPolicyTest {
    @Test
    fun acceptsMarkedFirstPartyServiceAtPrivateAddress() {
        assertEquals(
            TvCastTarget("192.168.1.24"),
            TvCastDiscoveryPolicy.target(
                serviceName = "4789 TV abcdef12",
                serviceType = "_xbmc-jsonrpc-h._tcp.",
                port = 8_791,
                receiverMarker = "1",
                addresses = listOf("127.0.0.1", "192.168.1.24"),
            ),
        )
    }

    @Test
    fun rejectsUnmarkedWrongPortAndPublicServices() {
        assertNull(target(marker = null))
        assertNull(target(port = 8_080))
        assertNull(target(addresses = listOf("8.8.8.8")))
        assertNull(target(serviceName = "Kodi living room"))
    }

    private fun target(
        serviceName: String = "4789 TV abcdef12",
        port: Int = 8_791,
        marker: String? = "1",
        addresses: List<String> = listOf("10.0.0.9"),
    ): TvCastTarget? = TvCastDiscoveryPolicy.target(
        serviceName = serviceName,
        serviceType = "_xbmc-jsonrpc-h._tcp",
        port = port,
        receiverMarker = marker,
        addresses = addresses,
    )
}
