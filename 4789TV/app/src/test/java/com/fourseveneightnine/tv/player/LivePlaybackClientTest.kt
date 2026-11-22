package com.fourseveneightnine.tv.player

import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LivePlaybackClientTest {
    @Test fun aPlayingResponseOutlivesTheCatalogDeadlineButIdleReadsStillTimeOut() {
        val catalog = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(24, TimeUnit.SECONDS)
            .build()

        val playback = catalog.forLivePlayback("https://example.test/live".toHttpUrl())

        assertEquals(0, playback.callTimeoutMillis)
        assertEquals(3_000, playback.connectTimeoutMillis)
        assertEquals(catalog.readTimeoutMillis, playback.readTimeoutMillis)
        assertSame(catalog.connectionPool, playback.connectionPool)
        assertEquals(24_000, catalog.callTimeoutMillis)
    }
}
