package com.fourseveneightnine.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ExoPlayer path used to accept every audio setting and apply none of them. These pin the
 * resolution the sink's capabilities are now built from.
 */
class ExoAudioRoutePolicyTest {

    private val route = listOf("ac3", "eac3", "dts")
    private val lossyProfile = mapOf(
        "ac3" to true,
        "eac3" to true,
        "dts" to true,
        "dts-hd" to false,
        "truehd" to false,
    )

    @Test
    fun automaticRoutingDefersToThePlatform() {
        assertEquals(
            ExoAudioRoute.Automatic,
            ExoAudioRoutePolicy.resolve(
                automaticRouting = true,
                passthroughRequested = true,
                routeSupported = route,
                profileRequested = lossyProfile,
                routeProbeAvailable = true,
            ),
        )
    }

    @Test
    fun manualPassthroughKeepsOnlyCodecsTheProfileAndRouteBothAllow() {
        assertEquals(
            ExoAudioRoute.Constrained(listOf("ac3", "eac3", "dts")),
            ExoAudioRoutePolicy.resolve(
                automaticRouting = false,
                passthroughRequested = true,
                routeSupported = route,
                profileRequested = lossyProfile,
                routeProbeAvailable = true,
            ),
        )
    }

    @Test
    fun aCodecTheRouteRefusesIsNotOfferedEvenWhenTheProfileAsks() {
        val losslessProfile = lossyProfile + mapOf("truehd" to true, "dts-hd" to true)
        assertEquals(
            ExoAudioRoute.Constrained(listOf("ac3", "eac3", "dts")),
            ExoAudioRoutePolicy.resolve(
                automaticRouting = false,
                passthroughRequested = true,
                routeSupported = route,
                profileRequested = losslessProfile,
                routeProbeAvailable = true,
            ),
        )
    }

    @Test
    fun passthroughOffDecodesEverythingToPcm() {
        val resolved = ExoAudioRoutePolicy.resolve(
            automaticRouting = false,
            passthroughRequested = false,
            routeSupported = route,
            profileRequested = lossyProfile,
            routeProbeAvailable = true,
        )
        assertEquals(ExoAudioRoute.Constrained(emptyList()), resolved)
    }

    @Test
    fun aDeviceThatCannotReportItsRouteIsNeverNarrowedToPcm() {
        // API 28 (Fire OS 7) cannot probe the route, so AndroidDirectAudioProbe returns an empty
        // list meaning "unknown". Reading that as "supports nothing" built a PCM-only sink and tore
        // down a working E-AC3 5.1 output on AFTDCT31 (0.1.22/0.1.23). Defer to the platform.
        assertEquals(
            ExoAudioRoute.Automatic,
            ExoAudioRoutePolicy.resolve(
                automaticRouting = false,
                passthroughRequested = true,
                routeSupported = emptyList(),
                profileRequested = lossyProfile,
                routeProbeAvailable = false,
            ),
        )
        // Even a passthrough-off profile must not narrow capabilities we could not measure.
        assertEquals(
            ExoAudioRoute.Automatic,
            ExoAudioRoutePolicy.resolve(
                automaticRouting = false,
                passthroughRequested = false,
                routeSupported = emptyList(),
                profileRequested = lossyProfile,
                routeProbeAvailable = false,
            ),
        )
    }

    @Test
    fun onlyRealSwitchesAreReportedAsActionable() {
        assertTrue("x4789.audio.automatic" in ExoAudioRoutePolicy.actionableSettings)
        assertTrue("audiooutput.passthrough" in ExoAudioRoutePolicy.actionableSettings)
        assertTrue("audiooutput.truehdpassthrough" in ExoAudioRoutePolicy.actionableSettings)
        // A Kodi key describing Kodi's own engine is not something this path can honour.
        assertTrue("audiooutput.stereoupmix" !in ExoAudioRoutePolicy.actionableSettings)
    }

    @Test
    fun bothEnginesShareOneCodecKeyMap() {
        assertEquals("truehd", DirectAudioPolicy.codecSettings["audiooutput.truehdpassthrough"])
        assertEquals(5, DirectAudioPolicy.codecSettings.size)
    }

    @Test
    fun everyKnownCodecMapsToAnEncoding() {
        assertEquals(
            DirectAudioPolicy.codecSettings.values.size,
            ExoAudioRoutePolicy.encodings(DirectAudioPolicy.codecSettings.values).size,
        )
    }
}
