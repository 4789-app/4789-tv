package com.fourseveneightnine.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The engine choice used to be an un-overridable brand check: a Fire TV could never run libmpv and
 * a Shield could never run ExoPlayer. The default is unchanged — it encodes tested behaviour — but
 * a device-specific playback fault can now be compared against the other engine without a rebuild.
 */
class ReceiverEnginePolicyTest {

    @Test
    fun everyBrandDefaultsToExoPlayer() {
        // Fire OS: vendor decoders deadlock in libmpv's hardware path.
        assertEquals(ReceiverEngine.Exo, ReceiverEnginePolicy.automaticEngine("Amazon", "AFTKA"))
        // Insignia and Toshiba Fire TVs report a non-Amazon manufacturer but an AFT model.
        assertEquals(ReceiverEngine.Exo, ReceiverEnginePolicy.automaticEngine("Insignia", "AFTDCT31"))
        // Everything else too, now that the ffmpeg extension gives the Exo path mpv's audio
        // decoders. A box this receiver has never run on must never be handed the engine whose
        // native libraries are not the ones packaged.
        assertEquals(ReceiverEngine.Exo, ReceiverEnginePolicy.automaticEngine("NVIDIA", "SHIELD Android TV"))
        assertEquals(ReceiverEngine.Exo, ReceiverEnginePolicy.automaticEngine("Sony", "BRAVIA 4K"))
        assertEquals(ReceiverEngine.Exo, ReceiverEnginePolicy.automaticEngine("Google", "Streamer 4K"))
    }

    @Test
    fun anOverrideBeatsTheBrandDefaultInBothDirections() {
        assertEquals(ReceiverEngine.Mpv, ReceiverEnginePolicy.engine("Amazon", "AFTKA", "mpv"))
        assertEquals(ReceiverEngine.Exo, ReceiverEnginePolicy.engine("NVIDIA", "SHIELD", "exo"))
    }

    @Test
    fun autoAndAbsentAndNonsenseAllFallBackToTheBrandDefault() {
        assertEquals(ReceiverEngine.Exo, ReceiverEnginePolicy.engine("Amazon", "AFTKA", "auto"))
        assertEquals(ReceiverEngine.Exo, ReceiverEnginePolicy.engine("Amazon", "AFTKA", null))
        // A typo must never leave the box with no engine at all.
        assertEquals(ReceiverEngine.Exo, ReceiverEnginePolicy.engine("Amazon", "AFTKA", "vlc"))
    }

    @Test
    fun onlyTheThreeAcceptedValuesNormalise() {
        assertEquals("mpv", ReceiverEnginePolicy.normalizedOverride(" MPV "))
        assertEquals("exo", ReceiverEnginePolicy.normalizedOverride("Exo"))
        assertEquals("auto", ReceiverEnginePolicy.normalizedOverride("AUTO"))
        assertNull(ReceiverEnginePolicy.normalizedOverride("kodi"))
        assertNull(ReceiverEnginePolicy.normalizedOverride(""))
    }

    @Test
    fun autoIsStoredAsNoOverrideSoALaterDefaultChangeIsPickedUp() {
        assertNull(ReceiverEnginePolicy.storedValue("auto"))
        assertEquals("mpv", ReceiverEnginePolicy.storedValue("mpv"))
    }
}
