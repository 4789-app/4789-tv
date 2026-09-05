package com.fourseveneightnine.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerLifecycleTest {
    @Test
    fun repeatedTeardownReleasesExactlyOnceInOrder() {
        val events = mutableListOf<String>()
        var position = 12_000L
        val lifecycle = PlayerLifecycle(
            object : ReleasablePlayer {
                override val currentPositionMillis: Long get() = position
                override val durationMillis = 120_000L
                override fun pause() { events += "pause" }
                override fun stop() { events += "stop" }
                override fun clearMediaItems() { events += "clear" }
                override fun release() { events += "release" }
            },
            recordProgress = { savedPosition, duration -> events += "save:$savedPosition:$duration" },
        )

        lifecycle.pauseForBackground()
        position = 13_000
        lifecycle.releaseOnce()
        lifecycle.releaseOnce()
        lifecycle.pauseForBackground()

        assertEquals(
            listOf("save:12000:120000", "pause", "save:13000:120000", "stop", "clear", "release"),
            events,
        )
    }
}
