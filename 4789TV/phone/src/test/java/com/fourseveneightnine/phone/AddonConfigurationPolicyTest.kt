package com.fourseveneightnine.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddonConfigurationPolicyTest {
    @Test
    fun manifestValidationIsHttpsAndCredentialFree() {
        assertEquals(
            "https://addon.example/config/manifest.json",
            AddonConfigurationPolicy.normalizedManifestURL(" https://addon.example/config/manifest.json "),
        )
        assertNull(AddonConfigurationPolicy.normalizedManifestURL("http://addon.example/manifest.json"))
        assertNull(AddonConfigurationPolicy.normalizedManifestURL("https://user:secret@addon.example/manifest.json"))
        assertNull(AddonConfigurationPolicy.normalizedManifestURL("https://addon.example/manifest.json?token=secret"))
        assertNull(AddonConfigurationPolicy.normalizedManifestURL("https://addon.example/config.json"))
    }

    @Test
    fun streamRouteIsBuiltFromValidatedManifestAndTitleIdentity() {
        assertEquals(
            "https://addon.example/config/stream/movie/tt26657236.json",
            AddonConfigurationPolicy.streamURL(
                "https://addon.example/config/manifest.json",
                "movie",
                "tt26657236",
            ),
        )
        assertNull(
            AddonConfigurationPolicy.streamURL(
                "https://addon.example/config/manifest.json",
                "movie",
                "../../secret",
            ),
        )
    }
}
