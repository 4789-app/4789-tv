package com.fourseveneightnine.tv.ui

import com.fourseveneightnine.tv.client.playback.canonicalTrackLanguage

import com.fourseveneightnine.tv.client.ui.screens.player.playerMenuLabel
import com.fourseveneightnine.tv.client.ui.screens.player.nextSubtitleSize
import com.fourseveneightnine.tv.player.ReceiverSubtitleStyle
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PlayerControlsPolicyTest {
    @Test
    fun `a checked delivery route is still the same published source after resolution`() {
        val row = com.fourseveneightnine.tv.client.data.streams.StreamRow("stable-id", "DMM Cast for TorBox", "DMM", "Show.S01E01.mkv",
            url = "https://debridmediamanager.com/api/stremio-tb/profile/play/hash")
        val checked = row.copy(url = "https://cdn.example/video.mkv", linkCheckedAtMillis = 1, validationSourceKey = "local-proof-key")
        assertEquals(true, com.fourseveneightnine.tv.client.ui.screens.player.samePlayerSource(row, checked))
        assertEquals(false, com.fourseveneightnine.tv.client.ui.screens.player.samePlayerSource(row, checked.copy(id = "different")))
        assertEquals(false, com.fourseveneightnine.tv.client.ui.screens.player.samePlayerSource(row, checked.copy(validationSourceKey = null)))
    }

    @Test fun playingSourceIdentityIncludesHeadersAndExactFiles() {
        val row = com.fourseveneightnine.tv.client.data.streams.StreamRow("a", "Provider", "Copy", "Copy", url = "https://example.invalid/video", headers = mapOf("Authorization" to "fixture"))
        val same = com.fourseveneightnine.tv.client.ui.screens.player.samePlayerSource(row, row.copy(id = "new-search-id"))
        assertEquals(true, same)
        assertEquals(false, com.fourseveneightnine.tv.client.ui.screens.player.samePlayerSource(row, row.copy(headers = emptyMap())))
        assertEquals(false, com.fourseveneightnine.tv.client.ui.screens.player.samePlayerSource(row, row.copy(url = "https://example.invalid/another")))
    }

    @Test fun trackLanguagePreferencesSurviveProviderAliases() {
        assertEquals("en", canonicalTrackLanguage("ENG"))
        assertEquals("en", canonicalTrackLanguage("en-US"))
        assertEquals("te", canonicalTrackLanguage("tel"))
        assertEquals("ta", canonicalTrackLanguage("tam"))
        assertEquals(null, canonicalTrackLanguage("und"))
    }

    @Test
    fun subtitleSizesCycleThroughSofaReadablePresets() {
        assertEquals(26.0, nextSubtitleSize(20.0), 0.0)
        assertEquals(16.0, nextSubtitleSize(32.0), 0.0)
    }

    @Test
    fun localSubtitleAppearanceRoundTripsThroughTheExistingSidechannel() {
        val style = ReceiverSubtitleStyle(
            colorHex = "#FFE082",
            size = 26.0,
            lift = 80.0,
            shadow = true,
            bold = true,
        )

        assertEquals(style, ReceiverSubtitleStyle.from(style.toParams()))
    }

    @Test
    fun legacySubtitleOutlineAndBackgroundUseThePlainTextDefault() {
        val legacy = buildJsonObject {
            put("outline", true)
            put("background", true)
        }
        assertEquals(ReceiverSubtitleStyle(), ReceiverSubtitleStyle.from(legacy))
    }

    @Test
    fun playerMenuLabelsKeepFullValuesForLayoutAndSpeech() {
        assertEquals("Speed · 1.25x", playerMenuLabel("Speed", "1.25x"))
        assertEquals(
            "Audio · English commentary track with a very long release name",
            playerMenuLabel("Audio", "English commentary track with a very long release name"),
        )
    }

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
        assertEquals(60, PlayerControlsPolicy.scrubRate(1_500))
        assertEquals(300, PlayerControlsPolicy.scrubRate(3_000))
        assertEquals(600, PlayerControlsPolicy.scrubRate(6_000))
    }

    @Test
    fun aTickMovesTheFilmByRateTimesRealTime() {
        // One 100ms tick, one second into the hold: 10x rate → one second of film.
        assertEquals(1_000L, PlayerControlsPolicy.scrubTickMillis(holdMillis = 900, sinceLastTickMillis = 100))
        // Same tick five seconds in: 300x → thirty seconds of film.
        assertEquals(30_000L, PlayerControlsPolicy.scrubTickMillis(holdMillis = 5_000, sinceLastTickMillis = 100))
    }

    @Test
    fun aStalledMainThreadCannotCashInAHugeJump() {
        // Two seconds of stall capped at MAX_TICK_CATCHUP_MILLIS with 600x rate
        assertEquals(
            600 * PlayerControlsPolicy.MAX_TICK_CATCHUP_MILLIS,
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
            "ENG · Commentary · E-AC3 · 5.1ch",
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
    fun chapterPickerStartsOnTheCurrentOrPrecedingChapter() {
        val chapters = listOf(0.0, 600.0, 1_200.0)

        assertEquals(0, PlayerControlsPolicy.chapterIndexAt(chapters, 30.0))
        assertEquals(1, PlayerControlsPolicy.chapterIndexAt(chapters, 900.0))
        assertEquals(2, PlayerControlsPolicy.chapterIndexAt(chapters, 1_200.0))
    }

    @Test
    fun chapterRowsAlwaysUseHoursMinutesAndSeconds() {
        assertEquals("0:00:07", PlayerControlsPolicy.formatChapterTime(7.0))
        assertEquals("1:23:45", PlayerControlsPolicy.formatChapterTime(5_025.0))
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

    @Test
    fun pictureZoomOffersEveryTenPercentStepThroughDoubleSize() {
        val presets = VideoResizeMode.entries.filter { it.zoomPercent > 100 }
        assertEquals((110..200 step 10).toList(), presets.map { it.zoomPercent })
    }

    @Test
    fun fill235CropsNativeAndEmbeddedLetterboxByTheSameAmount() {
        val panelRatio = 16f / 9f
        val expected = 2.35f / panelRatio
        assertEquals(expected, VideoResizeMode.Fill235.zoomFactor(2.35f, panelRatio), 0.001f)
        assertEquals(expected, VideoResizeMode.Fill235.zoomFactor(panelRatio, panelRatio), 0.001f)
    }
    @Test fun sourceFormatsRecognizeDolbyVisionAndConfirmedJocAudio() {
        val d = com.fourseveneightnine.tv.player.PlaybackDiagnostics(active = true,
            width = 3840, height = 2160, videoCodec = "video/dolby-vision", hdrTransfer = "ST2084")
        val audio = com.fourseveneightnine.tv.protocol.ReceiverTrack(0, codec = "audio/eac3-joc", channels = 6, selected = true)
        assertEquals(listOf("4K", "Dolby Vision", "Dolby Atmos", "DD+ · 5.1"), PlayerControlsPolicy.sourceFormats(d, audio))
    }

    @Test fun ddPlusOrTrueHdWithoutAtmosSignallingStayUnbranded() {
        val d = com.fourseveneightnine.tv.player.PlaybackDiagnostics(active = true)
        for (codec in listOf("audio/eac3", "audio/true-hd")) {
            val audio = com.fourseveneightnine.tv.protocol.ReceiverTrack(0, codec = codec, channels = 8)
            org.junit.Assert.assertFalse(PlayerControlsPolicy.sourceFormats(d, audio).contains("Dolby Atmos"))
        }
    }

    @Test fun selectedAudioOverridesStaleAtmosDiagnostics() {
        val d = com.fourseveneightnine.tv.player.PlaybackDiagnostics(active = true, audioCodec = "audio/eac3-joc", audioChannels = 8)
        val audio = com.fourseveneightnine.tv.protocol.ReceiverTrack(1, name = "English", codec = "audio/mp4a-latm", channels = 2, selected = true)
        assertEquals(listOf("AAC · Stereo"), PlayerControlsPolicy.sourceFormats(d, audio))
        val misleading = audio.copy(name = "Atmos")
        assertEquals(listOf("AAC · Stereo"), PlayerControlsPolicy.sourceFormats(d, misleading))
    }

    @Test fun hdrTransferAndSelectedDolbyTrackLabelsSupplySourceFacts() {
        val d = com.fourseveneightnine.tv.player.PlaybackDiagnostics(active = true, width = 1920,
            height = 1080, videoCodec = "video/hevc", hdrTransfer = "smpte2084")
        val audio = com.fourseveneightnine.tv.protocol.ReceiverTrack(0, name = "English Dolby Atmos", codec = "audio/true-hd", channels = 8)
        assertEquals(listOf("1080p", "HDR10", "HEVC", "Dolby Atmos", "TrueHD · 7.1"), PlayerControlsPolicy.sourceFormats(d, audio))
        org.junit.Assert.assertFalse(PlayerControlsPolicy.sourceFormats(d.copy(hdrTransfer = ""), null).contains("HDR10"))
        org.junit.Assert.assertTrue(PlayerControlsPolicy.sourceFormats(d.copy(hdrTransfer = "HLG"), null).contains("HLG"))
    }

    @Test fun inactiveOrUnknownSourceDoesNotInventFormatBadges() {
        assertEquals(emptyList<String>(), PlayerControlsPolicy.sourceFormats(com.fourseveneightnine.tv.player.PlaybackDiagnostics(), null))
        assertEquals(emptyList<String>(), PlayerControlsPolicy.sourceFormats(com.fourseveneightnine.tv.player.PlaybackDiagnostics(active = true), null))
    }

    @Test fun headerHidesAvcAacAndChannelTextButKeepsResolution() {
        val d = com.fourseveneightnine.tv.player.PlaybackDiagnostics(active = true,
            width = 1920, height = 1080, videoCodec = "video/avc", audioCodec = "audio/mp4a-latm", audioChannels = 2)
        assertEquals(listOf("1080p"), PlayerControlsPolicy.headerFormats(d, null))
    }

    @Test fun headerUsesSelectedAudioInsteadOfStaleAtmosAndKeepsOtherCodecs() {
        val d = com.fourseveneightnine.tv.player.PlaybackDiagnostics(active = true,
            width = 1920, height = 1080, videoCodec = "video/av01", audioCodec = "audio/eac3-joc")
        val audio = com.fourseveneightnine.tv.protocol.ReceiverTrack(1, codec = "audio/opus", channels = 2)
        assertEquals(listOf("1080p", "AV1", "Opus"), PlayerControlsPolicy.headerFormats(d, audio))
        assertEquals(listOf("1080p", "AV1", "Dolby Atmos"), PlayerControlsPolicy.headerFormats(d, null))
    }

    @Test fun headerRequiresExplicitImaxAndDoesNotPromoteAdvertisedHdrVariants() {
        val d = com.fourseveneightnine.tv.player.PlaybackDiagnostics(active = true,
            width = 3840, height = 2160, videoCodec = "video/hevc", hdrTransfer = "smpte2084")
        assertEquals(listOf("4K", "IMAX", "HDR10", "HEVC"),
            PlayerControlsPolicy.headerFormats(d, null, "Film.IMAX.HDR10+.HEVC"))
        assertEquals(listOf("4K", "HDR10", "HEVC"),
            PlayerControlsPolicy.headerFormats(d, null, "Film.IMAXIMUM.HEVC"))
        assertEquals(listOf("4K", "IMAX", "HEVC"),
            PlayerControlsPolicy.headerFormats(d.copy(hdrTransfer = ""), null, "Film.IMAX.HDR10+"))
        assertEquals(emptyList<String>(),
            PlayerControlsPolicy.headerFormats(com.fourseveneightnine.tv.player.PlaybackDiagnostics(), null, "Film.IMAX.HDR10+"))
    }

    @Test fun contextSubtitleDropsRepeatedFormatsAndRetainsEpisodeAndReleaseNames() {
        val formats = listOf("4K", "Dolby Vision", "HEVC", "DD+ · 5.1")
        assertEquals(null, PlayerControlsPolicy.sourceContextSubtitle("4K", formats))
        assertEquals("S1 E1 · The Pilot", PlayerControlsPolicy.sourceContextSubtitle("S1 E1 · The Pilot · 4k", formats))
        assertEquals("Director's cut", PlayerControlsPolicy.sourceContextSubtitle("Director's cut · DD+ · 5.1", formats))
        assertEquals("4K restoration", PlayerControlsPolicy.sourceContextSubtitle("4K restoration", formats))
        assertEquals("HEVC · 1.2 GB", PlayerControlsPolicy.sourceContextSubtitle("4K • HEVC • 1.2 GB", listOf("4K")))
        assertEquals("4K", PlayerControlsPolicy.sourceContextSubtitle("4K", emptyList()))
    }
}
