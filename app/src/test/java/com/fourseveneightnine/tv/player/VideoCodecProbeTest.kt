package com.fourseveneightnine.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decoder list itself needs a device, but the two decisions that made previous AV1 support
 * checks wrong on Fire TV are pure and pinned here: which MIME types we ask about, and how a
 * software decoder is recognised below API 29.
 */
class VideoCodecProbeTest {

    @Test
    fun av1IsAskedAboutByItsRealMimeType() {
        assertEquals("video/av01", VideoCodecProbe.mimeByName["av1"])
    }

    @Test
    fun theProbeCoversTheCodecsAReceiverActuallyMeets() {
        assertEquals(
            listOf("h264", "hevc", "av1", "vp9", "dolbyvision"),
            VideoCodecProbe.mimeByName.keys.toList(),
        )
    }

    @Test
    fun googleAndAndroidReferenceDecodersAreTreatedAsSoftware() {
        assertTrue(SoftwareDecoderNamePolicy.isSoftwareName("OMX.google.h264.decoder"))
        assertTrue(SoftwareDecoderNamePolicy.isSoftwareName("c2.android.av1.decoder"))
        assertTrue(SoftwareDecoderNamePolicy.isSoftwareName("OMX.SEC.sw.dec"))
    }

    @Test
    fun vendorDecodersAreNotMistakenForSoftware() {
        // These are exactly the AV1 decoders a Fire TV or Amlogic box reports, on a build that
        // predates isHardwareAccelerated. Classing them as software would hide real AV1 support.
        assertFalse(SoftwareDecoderNamePolicy.isSoftwareName("c2.mtk.av1.decoder"))
        assertFalse(SoftwareDecoderNamePolicy.isSoftwareName("OMX.amlogic.av1.decoder.awesome"))
        assertFalse(SoftwareDecoderNamePolicy.isSoftwareName("OMX.qcom.video.decoder.hevc"))
    }

    @Test
    fun theProbeNamesAreCaseInsensitiveOnTheDeviceSide() {
        assertTrue(SoftwareDecoderNamePolicy.isSoftwareName("C2.Android.AV1.Decoder"))
    }
}
