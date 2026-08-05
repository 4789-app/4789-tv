package com.fourseveneightnine.tv.player

import org.junit.Assert.assertEquals
import org.junit.Test

class EngineFailoverPolicyTest {
    private fun decide(
        category: String,
        fatal: Boolean = true,
        engine: ReceiverEngine = ReceiverEngine.Exo,
        alreadyAttempted: Boolean = false,
        mediaKnown: Boolean = true,
        mpvHardwareDecode: Boolean = true,
    ) = EngineFailoverPolicy.decide(
        category,
        fatal,
        engine,
        alreadyAttempted,
        mediaKnown,
        mpvHardwareDecode,
    )

    @Test
    fun softwareOnlyMpvNeverTakesOver() {
        // Fire OS pins libmpv to software decode; handing it an HD/4K stream trades a named error
        // for a 4fps slideshow. Surface the error and let the viewer hand off instead.
        listOf(
            PlaybackErrorTaxonomy.CODEC_AUDIO,
            PlaybackErrorTaxonomy.CODEC_VIDEO,
            PlaybackErrorTaxonomy.UNKNOWN,
        ).forEach { category ->
            assertEquals(
                EngineFailoverDecision.Surface,
                decide(category, mpvHardwareDecode = false),
            )
        }
    }

    @Test
    fun fatalCodecAndUnknownErrorsRetryOnMpv() {
        listOf(
            PlaybackErrorTaxonomy.CODEC_AUDIO,
            PlaybackErrorTaxonomy.CODEC_VIDEO,
            PlaybackErrorTaxonomy.UNKNOWN,
        ).forEach { category ->
            assertEquals(EngineFailoverDecision.RetryWithMpv, decide(category))
        }
    }

    @Test
    fun silentAudioFallbackAlsoRetries() {
        // Video playing without sound is not playback — mpv gives it a voice.
        assertEquals(
            EngineFailoverDecision.RetryWithMpv,
            decide(PlaybackErrorTaxonomy.CODEC_AUDIO, fatal = false),
        )
    }

    @Test
    fun nonFatalNonAudioErrorsSurface() {
        assertEquals(
            EngineFailoverDecision.Surface,
            decide(PlaybackErrorTaxonomy.CODEC_VIDEO, fatal = false),
        )
        assertEquals(
            EngineFailoverDecision.Surface,
            decide(PlaybackErrorTaxonomy.UNKNOWN, fatal = false),
        )
    }

    @Test
    fun sourceAndNetworkFailuresNeverRetry() {
        assertEquals(EngineFailoverDecision.Surface, decide(PlaybackErrorTaxonomy.SOURCE))
        assertEquals(EngineFailoverDecision.Surface, decide(PlaybackErrorTaxonomy.NETWORK))
    }

    @Test
    fun onlyOneAttemptPerOpen() {
        assertEquals(
            EngineFailoverDecision.Surface,
            decide(PlaybackErrorTaxonomy.CODEC_AUDIO, alreadyAttempted = true),
        )
    }

    @Test
    fun mpvFailuresNeverPingPongBack() {
        assertEquals(
            EngineFailoverDecision.Surface,
            decide(PlaybackErrorTaxonomy.CODEC_AUDIO, engine = ReceiverEngine.Mpv),
        )
    }

    @Test
    fun noMediaMeansNothingToRetry() {
        assertEquals(
            EngineFailoverDecision.Surface,
            decide(PlaybackErrorTaxonomy.CODEC_AUDIO, mediaKnown = false),
        )
    }
}
