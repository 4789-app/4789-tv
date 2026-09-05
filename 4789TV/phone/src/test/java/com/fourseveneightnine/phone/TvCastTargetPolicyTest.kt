package com.fourseveneightnine.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvCastTargetPolicyTest {
    @Test
    fun acceptsOnlyPrivateIpv4ReceiverTargets() {
        assertEquals(TvCastTarget("192.168.1.20"), TvCastTargetPolicy.parse(" 192.168.1.20 "))
        assertEquals(TvCastTarget("10.0.2.15"), TvCastTargetPolicy.parse("10.0.2.15"))
        assertNull(TvCastTargetPolicy.parse("8.8.8.8"))
        assertNull(TvCastTargetPolicy.parse("127.0.0.1"))
        assertNull(TvCastTargetPolicy.parse("192.168.1.999"))
        assertNull(TvCastTargetPolicy.parse("receiver.local"))
    }
}
