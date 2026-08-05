package com.fourseveneightnine.tv.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The failure this guards against is the one that looks like a working system: the box stays in the
 * phone's list, and every connection to it fails, because the DNS-SD record still names an address
 * nothing answers on.
 */
class NetworkRepublishPolicyTest {

    @Test
    fun aChangedAddressRepublishes() {
        assertEquals(
            NetworkRepublishAction.Republish,
            NetworkRepublishPolicy.decide(
                previousAddresses = setOf("192.168.1.40"),
                currentAddresses = setOf("192.168.1.77"),
            ),
        )
    }

    @Test
    fun theSameAddressDoesNotRestartAHealthyAdvertisement() {
        assertEquals(
            NetworkRepublishAction.Ignore,
            NetworkRepublishPolicy.decide(
                previousAddresses = setOf("192.168.1.40"),
                currentAddresses = setOf("192.168.1.40"),
            ),
        )
    }

    @Test
    fun theFirstObservationIsNotAChange() {
        // The advertisement was registered with this address already; republishing would tear down
        // a record that was correct from the start.
        assertEquals(
            NetworkRepublishAction.Ignore,
            NetworkRepublishPolicy.decide(
                previousAddresses = null,
                currentAddresses = setOf("192.168.1.40"),
            ),
        )
    }

    @Test
    fun losingEveryAddressIsNotSomethingToRepublishInto() {
        assertEquals(
            NetworkRepublishAction.Ignore,
            NetworkRepublishPolicy.decide(
                previousAddresses = setOf("192.168.1.40"),
                currentAddresses = emptySet(),
            ),
        )
    }

    @Test
    fun linkLocalAndLoopbackAddressesAreIgnored() {
        // A box can acquire or drop these without its real address changing at all.
        assertEquals(
            setOf("192.168.1.40"),
            NetworkRepublishPolicy.routableAddresses(
                listOf("192.168.1.40", "127.0.0.1", "169.254.3.9", "fe80::1", "::1"),
            ),
        )
    }

    @Test
    fun aScopedIpv6AddressIsComparedWithoutItsInterfaceSuffix() {
        assertEquals(
            setOf("2001:db8::5"),
            NetworkRepublishPolicy.routableAddresses(listOf("2001:db8::5%wlan0")),
        )
    }

    @Test
    fun gainingAnIpv6AddressAlongsideTheSameIpv4CountsAsAChange() {
        assertEquals(
            NetworkRepublishAction.Republish,
            NetworkRepublishPolicy.decide(
                previousAddresses = setOf("192.168.1.40"),
                currentAddresses = setOf("192.168.1.40", "2001:db8::5"),
            ),
        )
    }

    @Test
    fun theCoalesceWindowOutlastsADhcpBurstWithoutStallingReconnection() {
        assertTrue(NetworkRepublishPolicy.COALESCE_DELAY_MS >= 1_000)
        assertTrue(NetworkRepublishPolicy.COALESCE_DELAY_MS <= 5_000)
    }
}
