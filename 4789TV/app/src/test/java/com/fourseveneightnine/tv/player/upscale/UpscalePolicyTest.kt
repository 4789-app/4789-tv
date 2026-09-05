package com.fourseveneightnine.tv.player.upscale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpscalePolicyTest {

    @Test
    fun a720pFrameOnA4kPanelIsCappedAtTheShaderHonest2x() {
        assertEquals(2.0f, UpscalePolicy.scaleFor(1280, 720, 3840, 2160), 0.0001f)
    }

    @Test
    fun a1080pFrameOnA4kPanelDoublesExactly() {
        assertEquals(2.0f, UpscalePolicy.scaleFor(1920, 1080, 3840, 2160), 0.0001f)
    }

    @Test
    fun a720pFrameOnA1080pPanelScalesToFit() {
        assertEquals(1.5f, UpscalePolicy.scaleFor(1280, 720, 1920, 1080), 0.0001f)
    }

    @Test
    fun nativeResolutionContentIsNotWorthAPass() {
        val scale = UpscalePolicy.scaleFor(1920, 1080, 1920, 1080)
        assertEquals(1.0f, scale, 0.0001f)
        assertFalse(UpscalePolicy.isWorthUpscaling(scale))
    }

    @Test
    fun contentLargerThanThePanelNeverDownscalesThroughTheShader() {
        assertEquals(1.0f, UpscalePolicy.scaleFor(3840, 2160, 1920, 1080), 0.0001f)
    }

    @Test
    fun theFitUsesTheTightAxisSoAnamorphicContentDoesNotOverflow() {
        // 2.39:1 scope frame inside a 16:9 panel: width binds, not height.
        assertEquals(2.0f, UpscalePolicy.scaleFor(1920, 804, 3840, 2160), 0.0001f)
    }

    @Test
    fun degenerateSizesNeverUpscale() {
        assertEquals(1.0f, UpscalePolicy.scaleFor(0, 0, 3840, 2160), 0.0001f)
        assertEquals(1.0f, UpscalePolicy.scaleFor(1280, 720, 0, 0), 0.0001f)
    }

    @Test
    fun aWorthwhileScaleClearsTheThreshold() {
        assertTrue(UpscalePolicy.isWorthUpscaling(1.5f))
        assertFalse(UpscalePolicy.isWorthUpscaling(1.02f))
    }

    @Test
    fun hdrContentNeverEntersThePipeline() {
        // The GL colour conversion alone changes the picture on the box — HDR must not even attach,
        // in any mode.
        assertFalse(UpscalePolicy.shouldApply(1280, 720, 1920, 1080, hdr = true, mode = UpscaleMode.AUTO))
        assertFalse(UpscalePolicy.shouldApply(720, 480, 3840, 2160, hdr = true, mode = UpscaleMode.FORCE_1080P))
    }

    @Test
    fun native4kOnA1080pUiDisplayNeverAttaches() {
        // The Fire TV renders its UI at 1920x1080 (displayMetrics), so a 4K frame maps 1:1 onto
        // the surface: the shader cannot enlarge it and the pipeline must not exist for it — not
        // even in the force mode, which is capped at 1080p-class frames.
        assertFalse(UpscalePolicy.shouldApply(3840, 2160, 1920, 1080, hdr = false, mode = UpscaleMode.AUTO))
        assertFalse(UpscalePolicy.shouldApply(3840, 2160, 1920, 1080, hdr = false, mode = UpscaleMode.FORCE_1080P))
    }

    @Test
    fun native4kOnA4kPanelNeverForced() {
        // On a box whose displayMetrics are physical 4K, force mode still must not touch 4K-native:
        // the 1:1 sharpening intent stops at 1080p-class frames.
        assertFalse(UpscalePolicy.shouldApply(3840, 2160, 3840, 2160, hdr = false, mode = UpscaleMode.FORCE_1080P))
    }

    @Test
    fun autoModeSkipsNativeResolutionContent() {
        assertFalse(UpscalePolicy.shouldApply(1920, 1080, 1920, 1080, hdr = false, mode = UpscaleMode.AUTO))
        assertFalse(UpscalePolicy.shouldApply(1280, 720, 1280, 720, hdr = false, mode = UpscaleMode.AUTO))
    }

    @Test
    fun forceModeSharpensOneToOne1080p() {
        // The manual mode's whole point: run the edge-directed pass at 1:1 on 1080p-class frames
        // as a sharpener feeding the panel scaler.
        assertTrue(UpscalePolicy.shouldApply(1920, 1080, 1920, 1080, hdr = false, mode = UpscaleMode.FORCE_1080P))
        assertTrue(UpscalePolicy.shouldApply(1920, 804, 1920, 1080, hdr = false, mode = UpscaleMode.FORCE_1080P))
        assertTrue(UpscalePolicy.shouldApply(1440, 1080, 1920, 1080, hdr = false, mode = UpscaleMode.FORCE_1080P))
    }

    @Test
    fun forceModeStillEnlargesBelow1080p() {
        assertTrue(UpscalePolicy.shouldApply(1280, 720, 1920, 1080, hdr = false, mode = UpscaleMode.FORCE_1080P))
        assertTrue(UpscalePolicy.shouldApply(854, 480, 1920, 1080, hdr = false, mode = UpscaleMode.FORCE_1080P))
    }

    @Test
    fun offModeNeverApplies() {
        assertFalse(UpscalePolicy.shouldApply(1280, 720, 1920, 1080, hdr = false, mode = UpscaleMode.OFF))
        assertFalse(UpscalePolicy.shouldApply(1920, 1080, 3840, 2160, hdr = false, mode = UpscaleMode.OFF))
    }

    @Test
    fun sdrContentBelowTheDisplayAttachesInAuto() {
        assertTrue(UpscalePolicy.shouldApply(1280, 720, 1920, 1080, hdr = false, mode = UpscaleMode.AUTO))
        assertTrue(UpscalePolicy.shouldApply(1920, 1080, 3840, 2160, hdr = false, mode = UpscaleMode.AUTO))
    }

    @Test
    fun unknownOrDegenerateSizesNeverAttach() {
        assertFalse(UpscalePolicy.shouldApply(0, 0, 1920, 1080, hdr = false, mode = UpscaleMode.AUTO))
        assertFalse(UpscalePolicy.shouldApply(1280, 720, 0, 0, hdr = false, mode = UpscaleMode.FORCE_1080P))
        assertFalse(UpscalePolicy.shouldApply(0, 0, 1920, 1080, hdr = false, mode = UpscaleMode.FORCE_1080P))
    }

    @Test
    fun modeFromBooleanMapsPhoneWireToAutoOff() {
        assertEquals(UpscaleMode.AUTO, UpscalePolicy.modeFromBoolean(true))
        assertEquals(UpscaleMode.OFF, UpscalePolicy.modeFromBoolean(false))
    }

    @Test
    fun sgsrNeedsGles31ForTextureGather() {
        assertTrue(UpscalePolicy.supportsSgsr("OpenGL ES 3.1"))
        assertTrue(UpscalePolicy.supportsSgsr("OpenGL ES 3.2 v1.r26p0-01rel0.a8c72e394b04f0a4d9e5edd5a34c58b3"))
        assertFalse(UpscalePolicy.supportsSgsr("OpenGL ES 3.0 build 1.13@5776728"))
        assertFalse(UpscalePolicy.supportsSgsr("OpenGL ES 2.0"))
        assertFalse(UpscalePolicy.supportsSgsr(null))
        assertFalse(UpscalePolicy.supportsSgsr("Metal 3"))
    }
}
