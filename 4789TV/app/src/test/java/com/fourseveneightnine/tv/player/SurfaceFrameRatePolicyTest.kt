package com.fourseveneightnine.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Auto frame rate matching below API 30.
 *
 * `Surface.setFrameRate` needs API 30, so on the two televisions this receiver is verified against
 * — Fire OS 7 at API 28 and a Hisense at API 29 — it can never run. `selectModeId` is the pure
 * decision behind the `preferredDisplayModeId` path that covers those boxes, so it is pinned here.
 */
class SurfaceFrameRatePolicyTest {

    private val panel1080 = listOf(
        DisplayModeInfo(modeId = 1, width = 1920, height = 1080, refreshRate = 60.000f),
        DisplayModeInfo(modeId = 2, width = 1920, height = 1080, refreshRate = 59.940f),
        DisplayModeInfo(modeId = 3, width = 1920, height = 1080, refreshRate = 50.000f),
        DisplayModeInfo(modeId = 4, width = 1920, height = 1080, refreshRate = 23.976f),
        DisplayModeInfo(modeId = 5, width = 1920, height = 1080, refreshRate = 24.000f),
    )

    private fun select(fps: Double, modes: List<DisplayModeInfo> = panel1080, currentModeId: Int = 1) =
        SurfaceFrameRatePolicy.selectModeId(
            modes = modes,
            currentModeId = currentModeId,
            currentWidth = 1920,
            currentHeight = 1080,
            framesPerSecond = fps,
        )

    // MARK: - The rate that matters most

    @Test
    fun filmRatePicksTheExact23976ModeNotThe24Mode() {
        // The whole point of the feature: 23.976 content on a 60 Hz panel is 3:2 pulldown judder.
        assertEquals(4, select(23.976))
    }

    @Test
    fun trueTwentyFourPicksTheTwentyFourModeNotThe23976Mode() {
        assertEquals(5, select(24.000))
    }

    @Test
    fun palRatePicksFiftyByDoubling() {
        assertEquals(3, select(25.0))
    }

    @Test
    fun ntscRatePicksFiftyNineNineFourByDoubling() {
        assertEquals(2, select(29.97))
    }

    @Test
    fun sixtyHertzContentStaysOnTheSixtyHertzModeItIsAlreadyOn() {
        assertNull(select(60.0))
    }

    // MARK: - Refusing to act

    @Test
    fun noChangeWhenThePanelIsAlreadyOnTheBestMode() {
        assertNull(select(23.976, currentModeId = 4))
    }

    @Test
    fun noModeIsPickedWhenNothingIsCloseEnough() {
        // 25 fps on a panel that only offers 60 Hz: the best whole multiple is 50, which is 10 Hz
        // away. Switching would be worse than leaving it alone.
        val sixtyOnly = listOf(DisplayModeInfo(modeId = 1, width = 1920, height = 1080, refreshRate = 60f))
        assertNull(select(25.0, modes = sixtyOnly))
    }

    @Test
    fun resolutionIsNeverChanged() {
        // A 23.976 mode exists, but only at 4K. The panel is running 1080p, which is the viewer's
        // own display setting — a refresh request must not re-scale their whole interface.
        val fourKOnlyFilmMode = listOf(
            DisplayModeInfo(modeId = 1, width = 1920, height = 1080, refreshRate = 60f),
            DisplayModeInfo(modeId = 9, width = 3840, height = 2160, refreshRate = 23.976f),
        )
        assertNull(select(23.976, modes = fourKOnlyFilmMode))
    }

    @Test
    fun anEmptyModeListIsSafe() {
        assertNull(select(23.976, modes = emptyList()))
    }

    @Test
    fun anUnknownFrameRateNeverSwitches() {
        // This is the exact value the whole feature used to receive: PlaybackDiagnostics defaulted
        // framesPerSecond to 0.0 and nothing ever wrote it, so applyContentFrameRate returned early
        // on every title. A zero must still be refused here.
        assertNull(select(0.0))
    }

    @Test
    fun anAbsurdFrameRateNeverSwitches() {
        assertNull(select(1_000.0))
        assertNull(select(Double.NaN))
    }

    @Test
    fun aPanelWithNoResolutionReportedIsSafe() {
        assertNull(
            SurfaceFrameRatePolicy.selectModeId(
                modes = panel1080,
                currentModeId = 1,
                currentWidth = 0,
                currentHeight = 0,
                framesPerSecond = 23.976,
            ),
        )
    }

    // MARK: - Higher multiples

    @Test
    fun a120HertzPanelPlaysFilmRateAtFiveTimes() {
        val panel120 = listOf(
            DisplayModeInfo(modeId = 1, width = 1920, height = 1080, refreshRate = 60f),
            DisplayModeInfo(modeId = 7, width = 1920, height = 1080, refreshRate = 119.88f),
        )
        assertEquals(7, select(23.976, modes = panel120))
    }

    @Test
    fun aTwentyFourHertzModeIsAcceptedForTwentyThreeNineSevenSixWhenNothingBetterExists() {
        // 24.000 for 23.976 drifts one frame every ~41 seconds. That is still far better than
        // leaving a 60 Hz mode in place, so the tolerance is deliberately loose enough to take it.
        val noExactFilmMode = listOf(
            DisplayModeInfo(modeId = 1, width = 1920, height = 1080, refreshRate = 60f),
            DisplayModeInfo(modeId = 5, width = 1920, height = 1080, refreshRate = 24.000f),
        )
        assertEquals(5, select(23.976, modes = noExactFilmMode))
    }

    // MARK: - The non-seamless gate

    @Test
    fun shortContentDoesNotEarnAModeSwitch() {
        // A mode change re-links HDMI. Nobody wants a black flash before a two-minute trailer.
        assertFalse(SurfaceFrameRatePolicy.allowNonSeamlessSwitch(120.0))
    }

    @Test
    fun aFeatureLengthTitleEarnsAModeSwitch() {
        assertTrue(SurfaceFrameRatePolicy.allowNonSeamlessSwitch(95 * 60.0))
    }

    @Test
    fun aLiveStreamWithNoDurationDoesNotEarnAModeSwitch() {
        assertFalse(SurfaceFrameRatePolicy.allowNonSeamlessSwitch(0.0))
    }

    @Test
    fun aRateLearnedBeforeThePictureIsUpMaySwitchTheMode() {
        // The screen is black through startup anyway, so the HDMI re-link costs the viewer nothing.
        assertTrue(SurfaceFrameRatePolicy.withinNonSeamlessWindow(null))
        assertTrue(SurfaceFrameRatePolicy.withinNonSeamlessWindow(0L))
        assertTrue(
            SurfaceFrameRatePolicy.withinNonSeamlessWindow(
                SurfaceFrameRatePolicy.NON_SEAMLESS_GRACE_MILLIS,
            ),
        )
    }

    @Test
    fun aRateLearnedLateLeavesThePanelAlone() {
        // On the onn 4K Pro (2026-08-28 13:13:12) the measured rate arrived 6.3s after the first
        // frame and blacked out a scene the viewer was already watching. Judder beats that.
        assertFalse(SurfaceFrameRatePolicy.withinNonSeamlessWindow(6_300L))
    }

    // MARK: - Snapping a measured rate
    //
    // Measured on both boxes: a progressive MP4 reaches the player with Format.frameRate unset, so
    // counting rendered frames is the ONLY rate available for those containers. A counted rate
    // wobbles, and a wobbling rate must never reach a display-mode request.

    @Test
    fun aWobblingFilmRateSnapsToTheSameAnswerEveryTime() {
        val film = 24_000.0 / 1_001.0
        // The crossover between the two film rates sits at 23.988, so anything below that lands on
        // 23.976 — which is where real film measurements sit.
        listOf(23.7, 23.9, 23.94, 23.976, 23.98).forEach { measured ->
            assertEquals(
                "measured $measured should snap to 23.976",
                film,
                SurfaceFrameRatePolicy.snapToStandardRate(measured)!!,
                0.001,
            )
        }
    }

    @Test
    fun nearestWinsAcrossTheFilmRatePair() {
        // 23.976 and 24.000 are 0.024 apart, which is finer than counting frames can resolve. The
        // rule is simply nearest — no thumb on the scale — so a measurement above 24.000 reads as
        // 24.000. Either answer costs one frame of drift every ~41 seconds, so this is safe.
        assertEquals(24.0, SurfaceFrameRatePolicy.snapToStandardRate(24.03)!!, 0.001)
        assertEquals(24.0, SurfaceFrameRatePolicy.snapToStandardRate(24.2)!!, 0.001)
    }

    @Test
    fun palAndNtscRatesSnapToThemselves() {
        assertEquals(25.0, SurfaceFrameRatePolicy.snapToStandardRate(24.9)!!, 0.001)
        assertEquals(30_000.0 / 1_001.0, SurfaceFrameRatePolicy.snapToStandardRate(29.9)!!, 0.001)
        assertEquals(50.0, SurfaceFrameRatePolicy.snapToStandardRate(50.2)!!, 0.001)
        // 59.8 is nearer 59.94 than 60.000 — the NTSC rate, which is the right answer for it.
        assertEquals(60_000.0 / 1_001.0, SurfaceFrameRatePolicy.snapToStandardRate(59.8)!!, 0.001)
        assertEquals(60.0, SurfaceFrameRatePolicy.snapToStandardRate(60.1)!!, 0.001)
    }

    @Test
    fun aRateBetweenStandardsIsRefusedRatherThanGuessed() {
        // 35 fps is not a rate anything is shot at. Reporting the nearest standard would be a lie,
        // and acting on a lie means a mode switch the content does not want.
        assertNull(SurfaceFrameRatePolicy.snapToStandardRate(35.0))
        assertNull(SurfaceFrameRatePolicy.snapToStandardRate(12.0))
    }

    @Test
    fun nonsenseMeasurementsAreRefused() {
        assertNull(SurfaceFrameRatePolicy.snapToStandardRate(0.0))
        assertNull(SurfaceFrameRatePolicy.snapToStandardRate(-5.0))
        assertNull(SurfaceFrameRatePolicy.snapToStandardRate(Double.NaN))
    }

    @Test
    fun aSnappedFilmRateFeedsStraightIntoModeSelection() {
        // The two halves join up: counting frames yields 23.976, which then picks the film mode.
        val snapped = SurfaceFrameRatePolicy.snapToStandardRate(23.94)!!
        assertEquals(4, select(snapped))
    }
}
