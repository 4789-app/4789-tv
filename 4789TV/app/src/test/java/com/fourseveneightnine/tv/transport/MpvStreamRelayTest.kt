package com.fourseveneightnine.tv.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvStreamRelayTest {
    @Test
    fun hostnameUrlsNeedRelay() {
        assertTrue(MpvStreamRelay.needsRelay("https://cdn.example.com/movie.mkv"))
        assertTrue(MpvStreamRelay.needsRelay("http://sample-videos.com/video.mp4"))
    }

    @Test
    fun ipLiteralUrlsGoDirect() {
        assertFalse(MpvStreamRelay.needsRelay("http://192.168.0.134:8899/test.mp4"))
        assertFalse(MpvStreamRelay.needsRelay("http://127.0.0.1:8791/stream.mp4"))
        assertFalse(MpvStreamRelay.needsRelay("http://[fe80::1]:8080/x.mp4"))
    }

    @Test
    fun nonHttpSchemesGoDirect() {
        assertFalse(MpvStreamRelay.needsRelay("file:///sdcard/movie.mkv"))
        assertFalse(MpvStreamRelay.needsRelay("rtsp://host/stream"))
        assertFalse(MpvStreamRelay.needsRelay("not a url"))
    }

    @Test
    fun registerRoundTripsAndRelayUrlIsLoopback() {
        val token = MpvStreamRelay.register("https://cdn.example.com/a.mkv", mapOf("Cookie" to "x"))
        val upstream = MpvStreamRelay.lookup(token)
        assertNotNull(upstream)
        assertEquals("https://cdn.example.com/a.mkv", upstream?.url)
        assertEquals("x", upstream?.headers?.get("Cookie"))
        assertTrue(MpvStreamRelay.relayURL(token, 8791).startsWith("http://127.0.0.1:8791/mpv-relay/"))
    }

    @Test
    fun hostLookalikesAreNotIpLiterals() {
        assertFalse(MpvStreamRelay.isIpLiteral("300.1.2.3.example.com"))
        assertFalse(MpvStreamRelay.isIpLiteral("1.2.3.999"))
        assertTrue(MpvStreamRelay.isIpLiteral("10.0.0.1"))
    }
}
