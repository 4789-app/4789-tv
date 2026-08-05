package com.fourseveneightnine.tv.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MpvIpcCommandPolicyTest {
    @Test
    fun `payload frames and escapes a load command`() {
        val payload = MpvIpcCommandPolicy.payload(
            arrayOf("loadfile", "http://phone/stream?title=\"one\"", "replace"),
            requestID = 7,
        )

        assertArrayEquals(
            "{\"command\":[\"loadfile\",\"http://phone/stream?title=\\\"one\\\"\",\"replace\"],\"request_id\":7}\n"
                .toByteArray(),
            payload,
        )
    }

    @Test
    fun `payload rejects empty commands and tokens`() {
        assertNull(MpvIpcCommandPolicy.payload(emptyArray(), requestID = 1))
        assertNull(MpvIpcCommandPolicy.payload(arrayOf("loadfile", ""), requestID = 1))
    }

    @Test
    fun `response must be successful and correlated`() {
        val response = "{\"data\":null,\"request_id\":9,\"error\":\"success\"}"

        org.junit.Assert.assertTrue(MpvIpcCommandPolicy.isSuccessfulResponse(response, requestID = 9))
        org.junit.Assert.assertFalse(MpvIpcCommandPolicy.isSuccessfulResponse(response, requestID = 8))
        org.junit.Assert.assertFalse(
            MpvIpcCommandPolicy.isSuccessfulResponse(
                "{\"request_id\":9,\"error\":\"failure\"}",
                requestID = 9,
            ),
        )
    }

    @Test
    fun `native event path validation rejects stale title`() {
        org.junit.Assert.assertTrue(
            MpvPathPolicy.matches("http://phone/first.mp4", "http://phone/first.mp4"),
        )
        org.junit.Assert.assertFalse(
            MpvPathPolicy.matches("http://phone/first.mp4", "http://phone/second.mp4"),
        )
        org.junit.Assert.assertFalse(
            MpvPathPolicy.matches("http://phone/first.mp4", null),
        )
    }

    @Test
    fun `every replacement gets a fresh callback gate`() {
        org.junit.Assert.assertFalse(
            MpvOpenPlayerPolicy.requiresFreshPlayer(
                playerPresent = false,
                freshMarkerGeneration = -1,
                generation = 1,
            ),
        )
        org.junit.Assert.assertTrue(
            MpvOpenPlayerPolicy.requiresFreshPlayer(
                playerPresent = true,
                freshMarkerGeneration = -1,
                generation = 2,
            ),
        )
        org.junit.Assert.assertTrue(
            MpvOpenPlayerPolicy.requiresFreshPlayer(
                playerPresent = false,
                freshMarkerGeneration = 2,
                generation = 3,
            ),
        )
    }

    @Test
    fun `retirement recovery waits for a pending teardown to become stuck`() {
        org.junit.Assert.assertFalse(
            MpvRetirementPolicy.shouldRecover(pendingCount = 0, oldestAgeMillis = 60_000),
        )
        org.junit.Assert.assertFalse(
            MpvRetirementPolicy.shouldRecover(
                pendingCount = 1,
                oldestAgeMillis = MpvRetirementPolicy.STUCK_RETIREMENT_MILLIS - 1,
            ),
        )
        org.junit.Assert.assertTrue(
            MpvRetirementPolicy.shouldRecover(
                pendingCount = 1,
                oldestAgeMillis = MpvRetirementPolicy.STUCK_RETIREMENT_MILLIS,
            ),
        )
    }
}
