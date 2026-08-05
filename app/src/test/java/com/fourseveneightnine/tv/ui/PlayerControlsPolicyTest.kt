package com.fourseveneightnine.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PlayerControlsPolicyTest {
    @Test
    fun timesReadTheWayTelevisionPlayersWriteThem() {
        assertEquals("0:07", PlayerControlsPolicy.formatTime(7_000))
        assertEquals("12:34", PlayerControlsPolicy.formatTime(754_000))
        assertEquals("1:23:45", PlayerControlsPolicy.formatTime(5_025_000))
        assertEquals("0:00", PlayerControlsPolicy.formatTime(-1_000))
    }

    @Test
    fun aHeldDpadRampsInRealTimeNotPerKeyRepeat() {
        // Rates are "seconds of film per second of holding", so the bar moves at a readable speed.
        assertEquals(10, PlayerControlsPolicy.scrubRate(0))
        assertEquals(10, PlayerControlsPolicy.scrubRate(1_400))
        assertEquals(30, PlayerControlsPolicy.scrubRate(1_500))
        assertEquals(90, PlayerControlsPolicy.scrubRate(4_000))
    }

    @Test
    fun aTickMovesTheFilmByRateTimesRealTime() {
        // One 100ms tick, one second into the hold: 10x rate → one second of film.
        assertEquals(1_000L, PlayerControlsPolicy.scrubTickMillis(holdMillis = 900, sinceLastTickMillis = 100))
        // Same tick five seconds in: 90x → nine seconds of film.
        assertEquals(9_000L, PlayerControlsPolicy.scrubTickMillis(holdMillis = 5_000, sinceLastTickMillis = 100))
    }

    @Test
    fun aStalledMainThreadCannotCashInAHugeJump() {
        // Two seconds of stall would otherwise buy 180s of film on the very next tick.
        assertEquals(
            90 * PlayerControlsPolicy.MAX_TICK_CATCHUP_MILLIS,
            PlayerControlsPolicy.scrubTickMillis(holdMillis = 6_000, sinceLastTickMillis = 2_000),
        )
        assertEquals(0L, PlayerControlsPolicy.scrubTickMillis(holdMillis = 6_000, sinceLastTickMillis = -5))
    }

    @Test
    fun aScrubNeverLeavesTheMedia() {
        assertEquals(0L, PlayerControlsPolicy.scrubTarget(5_000, -30_000, 600_000))
        assertEquals(600_000L, PlayerControlsPolicy.scrubTarget(590_000, 60_000, 600_000))
        // Unknown duration (live, or before prepare) must not clamp forward to zero.
        assertEquals(70_000L, PlayerControlsPolicy.scrubTarget(10_000, 60_000, 0))
    }

    @Test
    fun theScrubHudAlwaysKeepsItsSign() {
        assertEquals("+2:30", PlayerControlsPolicy.formatSignedDelta(150_000))
        assertEquals("-0:45", PlayerControlsPolicy.formatSignedDelta(-45_000))
        assertEquals("+0:00", PlayerControlsPolicy.formatSignedDelta(0))
    }

    @Test
    fun theBarIsEmptyWhileTheDurationIsUnknown() {
        assertEquals(0, PlayerControlsPolicy.progressPermille(30_000, 0))
        assertEquals(500, PlayerControlsPolicy.progressPermille(30_000, 60_000))
        assertEquals(1000, PlayerControlsPolicy.progressPermille(90_000, 60_000))
    }

    @Test
    fun speedCyclesAndReadsCleanly() {
        assertEquals("1x", PlayerControlsPolicy.formatSpeed(1.0f))
        assertEquals("0.5x", PlayerControlsPolicy.formatSpeed(0.5f))
        assertEquals("1.25x", PlayerControlsPolicy.formatSpeed(1.25f))
        assertEquals(1.25f, PlayerControlsPolicy.nextSpeed(1.0f))
        // Wraps rather than sticking at the top of the list.
        assertEquals(0.5f, PlayerControlsPolicy.nextSpeed(2.0f))
    }

    @Test
    fun trackRowsNameWhatTheViewerIsChoosingBetween() {
        assertEquals(
            "ENG · Commentary · EAC3 · 6ch",
            PlayerControlsPolicy.trackLabel("eng", "Commentary", "audio/eac3", 6, "Track 1"),
        )
        // A nameless, languageless track still has to say something.
        assertEquals(
            "Track 3",
            PlayerControlsPolicy.trackLabel("", "", null, null, "Track 3"),
        )
    }

    @Test
    fun resizeCyclesFitCropStretch() {
        assertEquals(VideoResizeMode.Crop, VideoResizeMode.Fit.next())
        assertEquals(VideoResizeMode.Stretch, VideoResizeMode.Crop.next())
        assertEquals(VideoResizeMode.Fit, VideoResizeMode.Stretch.next())
    }

    @Test
    fun theClockCarriesOnBetweenPolls() {
        // A poll at 30:00, read again 400ms later: the bar shows 30:00.4, not 30:00 for half a
        // second and then a jump.
        assertEquals(
            1_800_400L,
            PlayerControlsPolicy.interpolatedPosition(
                sampleMillis = 1_800_000L,
                sampledAtUptime = 10_000L,
                nowUptime = 10_400L,
                rate = 1.0f,
                durationMillis = 7_200_000L,
            ),
        )
    }

    @Test
    fun aPausedFilmsClockDoesNotDrift() {
        // Rate zero is the whole reason this takes a rate and not a boolean: a paused film that
        // kept counting would send the viewer back to a position they never reached.
        assertEquals(
            1_800_000L,
            PlayerControlsPolicy.interpolatedPosition(
                sampleMillis = 1_800_000L,
                sampledAtUptime = 10_000L,
                nowUptime = 40_000L,
                rate = 0f,
                durationMillis = 7_200_000L,
            ),
        )
    }

    @Test
    fun interpolationRespectsSpeedAndNeverRunsPastTheEnd() {
        assertEquals(
            1_000_800L,
            PlayerControlsPolicy.interpolatedPosition(
                sampleMillis = 1_000_000L,
                sampledAtUptime = 500L,
                nowUptime = 900L,
                rate = 2.0f,
                durationMillis = 7_200_000L,
            ),
        )
        // A stalled main thread must not let the clock run off the end of the film.
        assertEquals(
            60_000L,
            PlayerControlsPolicy.interpolatedPosition(
                sampleMillis = 59_000L,
                sampledAtUptime = 0L.inc(),
                nowUptime = 600_000L,
                rate = 1.0f,
                durationMillis = 60_000L,
            ),
        )
        // No duration yet (live, or before the first frame) means nothing to clamp against.
        assertEquals(
            10_000L,
            PlayerControlsPolicy.interpolatedPosition(
                sampleMillis = 0L,
                sampledAtUptime = 1L,
                nowUptime = 10_001L,
                rate = 1.0f,
                durationMillis = 0L,
            ),
        )
    }

    @Test
    fun aPositionNeverSampledIsNotAdvanced() {
        assertEquals(
            0L,
            PlayerControlsPolicy.interpolatedPosition(
                sampleMillis = 0L,
                sampledAtUptime = 0L,
                nowUptime = 900_000L,
                rate = 1.0f,
                durationMillis = 7_200_000L,
            ),
        )
    }

    @Test
    fun chaptersBecomeFractionsOfTheRuntimeThePlayerActuallyReported() {
        assertEquals(
            listOf(0.25f, 0.5f),
            PlayerControlsPolicy.chapterFractions(listOf(900.0, 1_800.0), 3_600.0),
        )
    }

    @Test
    fun chapterMarksThatCouldOnlyBeDrawnInTheWrongPlaceAreDropped() {
        // Zero marks nothing, and anything at or past the end is a lie about where it is.
        assertEquals(
            emptyList<Float>(),
            PlayerControlsPolicy.chapterFractions(listOf(0.0, 3_600.0, 5_000.0), 3_600.0),
        )
        // No duration reported yet: draw nothing rather than draw it wrong.
        assertEquals(
            emptyList<Float>(),
            PlayerControlsPolicy.chapterFractions(listOf(900.0), 0.0),
        )
        assertEquals(emptyList<Float>(), PlayerControlsPolicy.chapterFractions(emptyList(), 3_600.0))
    }

    @Test
    fun everyPictureModeCanNameItself() {
        // The modes used to be a single glyph that cycled blind; each one now has to have a word.
        VideoResizeMode.entries.forEach { mode ->
            assertNotEquals(0, mode.labelRes())
        }
        assertNotEquals(VideoResizeMode.Fit.labelRes(), VideoResizeMode.Crop.labelRes())
        assertNotEquals(VideoResizeMode.Crop.labelRes(), VideoResizeMode.Stretch.labelRes())
    }
}
