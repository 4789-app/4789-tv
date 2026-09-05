package com.fourseveneightnine.tv.player

import com.fourseveneightnine.tv.protocol.ReceiverSnapshot
import com.fourseveneightnine.tv.protocol.SeekCommand
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvReceiverRequestPolicyTest {
    @Test
    fun fixedLengthValuesUseUtf8ByteCounts() {
        assertEquals("%0%", MpvReceiverRequestPolicy.fixedLengthValue(""))
        assertEquals("%6%héllo", MpvReceiverRequestPolicy.fixedLengthValue("héllo"))
    }

    @Test
    fun loadPlansKeepPunctuationUnicodeAndNestedHeadersInsideFixedLengthValues() {
        val result = MpvReceiverRequestPolicy.loadPlan(
            title = "Mäx, 100% \"ready\"",
            headers = linkedMapOf(
                "Authorization" to "Bearer a,b%",
            ),
        )

        assertTrue(result.isSuccess)
        assertEquals(
            "force-media-title=%18%Mäx, 100% \"ready\",http-header-fields=%27%Authorization: Bearer a\\,b%," +
                "start=%4%none",
            result.getOrNull()?.options,
        )
        assertFalse(result.getOrNull().toString().contains("Mäx"))
        assertFalse(result.getOrNull().toString().contains("Bearer"))
    }

    @Test
    fun nestedHeaderListRetainsItsOwnCommaAndBackslashEscaping() {
        val plan = MpvReceiverRequestPolicy.loadPlan(
            title = null,
            headers = linkedMapOf(
                "X-Zeta" to "one\\two,three",
                "Authorization" to "Bearer token",
            ),
        ).getOrThrow()
        val expectedHeaderFields = "Authorization: Bearer token,X-Zeta: one\\\\two\\,three"

        assertEquals(
            "force-media-title=%0%,http-header-fields=" +
                "%${expectedHeaderFields.toByteArray(Charsets.UTF_8).size}%$expectedHeaderFields,start=%4%none",
            plan.options,
        )
    }

    @Test
    fun aResumePositionReachesMpvAsAStartOptionAndAsAPreLoadProperty() {
        val plan = MpvReceiverRequestPolicy.loadPlan(
            title = "Alpha",
            headers = emptyMap(),
            startSeconds = 1_544.457,
        ).getOrThrow()

        assertTrue(plan.options.endsWith("start=%8%1544.457"))
        // On the IPC path `start` is set BEFORE the load, because mpv reads it when it opens the
        // file. Ordering is the whole point, so it is pinned here.
        val commands = plan.loadFileIpcCommands("https://example.invalid/a.mkv").map { it.toString() }
        assertEquals(3, commands.size)
        assertTrue(commands[1].contains("\"start\"") && commands[1].contains("1544.457"))
        assertTrue(commands[2].contains("loadfile"))
    }

    @Test
    fun withoutAResumePositionEveryOpenStillStatesStartExplicitly() {
        // `start` is GLOBAL on the IPC path. A title that left it unset would inherit the previous
        // title's resume point, so "from the beginning" is written out rather than omitted.
        val plan = MpvReceiverRequestPolicy.loadPlan(title = "Next", headers = emptyMap()).getOrThrow()
        val commands = plan.loadFileIpcCommands("https://example.invalid/b.mkv").map { it.toString() }

        assertEquals(3, commands.size)
        assertTrue(commands[1].contains("\"start\"") && commands[1].contains("none"))
    }

    @Test
    fun aZeroOrNonsenseResumePositionIsTreatedAsTheBeginning() {
        for (value in listOf(0.0, -12.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals("none", MpvReceiverRequestPolicy.startValue(value))
        }
        assertEquals("none", MpvReceiverRequestPolicy.startValue(null))
    }

    @Test
    fun unsafeHeaderInputFailsWithoutEchoingTheHeaderValue() {
        val result = MpvReceiverRequestPolicy.loadPlan(
            title = "Safe title",
            headers = mapOf("X-Test" to "secret\r\nInjected: value"),
        )

        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull()?.message.orEmpty().contains("secret"))
    }

    @Test
    fun authenticatedThenHeaderlessLoadsEachHaveTheirOwnRedactedOptionsMap() {
        val authenticated = MpvReceiverRequestPolicy.loadPlan(
            title = "Episode",
            headers = mapOf("Authorization" to "Bearer token"),
        ).getOrThrow()
        val headerless = MpvReceiverRequestPolicy.loadPlan(
            title = "Next",
            headers = emptyMap(),
        ).getOrThrow()

        assertEquals(
            "force-media-title=%7%Episode,http-header-fields=%27%Authorization: Bearer token,start=%4%none",
            authenticated.options,
        )
        assertEquals("force-media-title=%4%Next,http-header-fields=%0%,start=%4%none", headerless.options)
        assertFalse(authenticated.toString().contains("Bearer token"))
        assertFalse(authenticated.toString().contains("Episode"))
        assertFalse(headerless.options.contains("Authorization"))
        assertArrayEquals(
            arrayOf(
                "loadfile",
                "https://example.invalid/authenticated.m3u8",
                "replace",
                "-1",
                authenticated.options,
            ),
            authenticated.loadFileCommand("https://example.invalid/authenticated.m3u8"),
        )
        assertArrayEquals(
            arrayOf(
                "loadfile",
                "https://example.invalid/headerless.m3u8",
                "replace",
                "-1",
                headerless.options,
            ),
            headerless.loadFileCommand("https://example.invalid/headerless.m3u8"),
        )
    }

    @Test
    fun ipcLoadPlanSetsHeadersBeforeLoadAndClearsThemForTheNextTitle() {
        val authenticated = MpvReceiverRequestPolicy.loadPlan(
            title = "Episode",
            headers = mapOf("Authorization" to "Bearer token"),
        ).getOrThrow()
        val authenticatedCommands = authenticated.loadFileIpcCommands("https://example.invalid/a.m3u8")
        assertEquals(
            "[\"set_property\",\"http-header-fields\",\"Authorization: Bearer token\"]",
            authenticatedCommands.first().toString(),
        )
        assertEquals(
            "[\"loadfile\",\"https://example.invalid/a.m3u8\",\"replace\",\"-1\"]",
            authenticatedCommands.last().toString(),
        )

        val headerless = MpvReceiverRequestPolicy.loadPlan(null, emptyMap()).getOrThrow()
        assertEquals(
            "[\"set_property\",\"http-header-fields\",\"\"]",
            headerless.loadFileIpcCommands("https://example.invalid/b.m3u8").first().toString(),
        )
    }

    @Test
    fun startupOptionsKeepMpvAliveForTheNextCast() {
        val idle = MpvReceiverStartupPolicy.options.single { (name, _) -> name == "idle" }

        assertEquals("yes", idle.second)
    }

    @Test
    fun headlessReceiverDoesNotLoadDesktopLuaControlClients() {
        val options = MpvReceiverStartupPolicy.options.toMap()

        assertEquals("no", options["load-scripts"])
        assertEquals("no", options["osc"])
        assertEquals("no", options["ytdl"])
        assertEquals("no", options["load-stats-overlay"])
        assertEquals("no", options["load-console"])
        assertEquals("no", options["load-auto-profiles"])
        assertEquals("no", options["load-select"])
        assertEquals("no", options["load-positioning"])
        assertEquals("no", options["load-commands"])
        assertEquals("no", options["load-context-menu"])
    }

    @Test
    fun startupOptionsKeepTheFireTvFallbackDecodePathBounded() {
        val options = MpvReceiverStartupPolicy.options.toMap()

        assertEquals("gpu-next", options["vo"])
        assertEquals("android", options["gpu-context"])
        assertEquals("auto", options["target-colorspace-hint"])
        assertEquals("no", options["hwdec"])
        assertFalse(MpvReceiverStartupPolicy.HARDWARE_DECODE_ENABLED)
        assertEquals("audio", options["video-sync"])
        assertEquals("vo", options["framedrop"])
        assertEquals("134217728", options["demuxer-max-bytes"])
        assertEquals("8388608", options["demuxer-max-back-bytes"])
        assertEquals("15", options["cache-secs"])
        assertEquals("5", options["demuxer-hysteresis-secs"])
        assertEquals("yes", options["cache-pause"])
        assertEquals("2", options["cache-pause-wait"])
        assertEquals("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36", options["user-agent"])
    }

    @Test
    fun memoryPressureTrimsWithoutDestroyingTheActivePlayer() {
        assertEquals(MpvMemoryPressureAction.None, MpvMemoryPressurePolicy.action(5, active = true))
        assertEquals(MpvMemoryPressureAction.None, MpvMemoryPressurePolicy.action(5, active = false))
        // RUNNING_LOW must not starve an active 4K pipeline into rebuffer loops.
        assertEquals(MpvMemoryPressureAction.None, MpvMemoryPressurePolicy.action(10, active = true))
        assertEquals(MpvMemoryPressureAction.TrimCache, MpvMemoryPressurePolicy.action(10, active = false))
        assertEquals(MpvMemoryPressureAction.TrimCache, MpvMemoryPressurePolicy.action(15, active = true))
        assertEquals(MpvMemoryPressureAction.TrimCache, MpvMemoryPressurePolicy.action(15, active = false))
        assertEquals(MpvMemoryPressureAction.TrimCache, MpvMemoryPressurePolicy.action(80, active = true))
        assertEquals("33554432", MpvMemoryPressurePolicy.LOW_CACHE_BYTES)
        assertEquals("2097152", MpvMemoryPressurePolicy.LOW_BACK_BYTES)
        assertEquals("67108864", MpvMemoryPressurePolicy.ACTIVE_CACHE_BYTES)
        assertEquals("4194304", MpvMemoryPressurePolicy.ACTIVE_BACK_BYTES)
    }

    @Test
    fun directAudioIntersectsTheActiveRouteWithTheChosenSpeakerProfile() {
        val route = listOf("ac3", "eac3", "dts", "dts-hd", "truehd")
        val lossyProfile = mapOf(
            "ac3" to true,
            "eac3" to true,
            "dts" to true,
            "dts-hd" to false,
            "truehd" to false,
        )

        assertEquals(
            listOf("ac3", "eac3", "dts"),
            DirectAudioPolicy.allowedCodecs(true, route, lossyProfile),
        )
        assertTrue(DirectAudioPolicy.allowedCodecs(false, route, lossyProfile).isEmpty())
        assertTrue(DirectAudioPolicy.allowedCodecs(true, listOf("truehd"), lossyProfile).isEmpty())
    }

    @Test
    fun androidSurfaceSizeUsesOnlyValidDimensions() {
        assertEquals("3840x2160", MpvSurfaceSizePolicy.value(3_840, 2_160))
        assertNull(MpvSurfaceSizePolicy.value(0, 2_160))
        assertNull(MpvSurfaceSizePolicy.value(3_840, -1))
    }

    @Test
    fun adaptiveBufferRaises4kAndRecoversAfterAnUnderrun() {
        assertEquals(
            AdaptiveBufferPolicy.FOUR_K_SECONDS,
            AdaptiveBufferPolicy.targetSeconds(15, 3_840, 2_160, 20_000_000.0, false),
        )
        assertEquals(
            AdaptiveBufferPolicy.RECOVERY_SECONDS,
            AdaptiveBufferPolicy.targetSeconds(30, 3_840, 2_160, 40_000_000.0, true),
        )
        assertEquals(
            AdaptiveBufferPolicy.INITIAL_SECONDS,
            AdaptiveBufferPolicy.targetSeconds(15, 1_920, 1_080, 8_000_000.0, false),
        )
    }

    @Test
    fun frameRatePolicyKeepsExactRatesAndUsesLongFormSwitchingOnlyForMovies() {
        assertEquals(23.976f, SurfaceFrameRatePolicy.validRate(23.976) ?: 0f, 0.0001f)
        assertNull(SurfaceFrameRatePolicy.validRate(Double.NaN))
        assertNull(SurfaceFrameRatePolicy.validRate(0.0))
        assertTrue(SurfaceFrameRatePolicy.allowNonSeamlessSwitch(7_200.0))
        assertFalse(SurfaceFrameRatePolicy.allowNonSeamlessSwitch(120.0))
    }

    @Test
    fun dolbyVisionUsesColorSafeSdrWhileOrdinaryVideoRestoresTheFastPath() {
        val dolbyVision = DolbyVisionColorPolicy.settings(profile = 5)
        assertEquals("mediacodec-copy", dolbyVision.hardwareDecoder)
        assertEquals("no", dolbyVision.colorspaceHint)
        assertEquals("bt.709", dolbyVision.targetPrimaries)
        assertEquals("bt.1886", dolbyVision.targetTransfer)
        assertEquals("bt.2446a", dolbyVision.toneMapping)
        assertEquals(DolbyVisionColorPolicy.SAFE_MODE, dolbyVision.label)

        val ordinary = DolbyVisionColorPolicy.settings(profile = null)
        assertEquals("mediacodec,mediacodec-copy", ordinary.hardwareDecoder)
        assertEquals("auto", ordinary.colorspaceHint)
        assertEquals("auto", ordinary.targetPrimaries)
        assertEquals("auto", ordinary.targetTransfer)
        assertEquals(DolbyVisionColorPolicy.STANDARD_MODE, ordinary.label)
    }

    @Test
    fun everyIphoneSubtitleFamilyGetsAnEmbeddedAndroidFamily() {
        val expected = mapOf(
            null to "Roboto Medium",
            "Atkinson Hyperlegible" to "Atkinson Hyperlegible",
            "Inter SemiBold" to "Inter SemiBold",
            "Noto Sans Medium" to "Noto Sans Medium",
            "Roboto Medium" to "Roboto Medium",
            "Helvetica Neue" to "Inter SemiBold",
            "Avenir Next" to "Inter SemiBold",
            "Arial" to "Inter SemiBold",
            "Verdana" to "Inter SemiBold",
            "Trebuchet MS" to "Inter SemiBold",
            "Gill Sans" to "Inter SemiBold",
            "Futura" to "Inter SemiBold",
            "Georgia" to "Fraunces 72pt Soft",
            "Menlo" to "JetBrains Mono",
        )

        expected.forEach { (requested, resolved) ->
            assertEquals(resolved, MpvSubtitleFontPolicy.resolvedFamily(requested))
        }
    }

    @Test
    fun loadRequiresTheCapturedReadySurfaceGeneration() {
        assertTrue(
            MpvSurfaceGenerationPolicy.isReadyForLoad(
                openGeneration = 7,
                currentGeneration = 7,
                surfaceReady = true,
            ),
        )
        assertFalse(
            MpvSurfaceGenerationPolicy.isReadyForLoad(
                openGeneration = 7,
                currentGeneration = 8,
                surfaceReady = true,
            ),
        )
        assertFalse(
            MpvSurfaceGenerationPolicy.isReadyForLoad(
                openGeneration = 7,
                currentGeneration = 7,
                surfaceReady = false,
            ),
        )
    }

    @Test
    fun seekPlansUseMpvModesAndRejectNonFiniteValues() {
        val snapshot = ReceiverSnapshot(positionSeconds = 25.0, durationSeconds = 100.0)

        val percentage = MpvReceiverRequestPolicy.seek(SeekCommand.Percentage(50.0), snapshot)
        val relative = MpvReceiverRequestPolicy.seek(SeekCommand.RelativeSeconds(-10.0), snapshot)
        val absolute = MpvReceiverRequestPolicy.seek(SeekCommand.AbsoluteSeconds(80.0), snapshot)

        assertArrayEquals(arrayOf("seek", "50.0", "absolute-percent"), percentage?.command)
        assertEquals(25.0, percentage?.expectedOffsetSeconds ?: Double.NaN, 0.0)
        assertArrayEquals(arrayOf("seek", "-10.0", "relative+exact"), relative?.command)
        assertEquals(-10.0, relative?.expectedOffsetSeconds ?: Double.NaN, 0.0)
        assertArrayEquals(arrayOf("seek", "80.0", "absolute"), absolute?.command)
        assertEquals(55.0, absolute?.expectedOffsetSeconds ?: Double.NaN, 0.0)
        assertNull(MpvReceiverRequestPolicy.seek(SeekCommand.Percentage(Double.NaN), snapshot))
    }

    @Test
    fun actionAllowlistMapsOnlyKnownRemoteCommands() {
        assertArrayEquals(arrayOf("keypress", "RIGHT"), MpvReceiverRequestPolicy.action("RIGHT"))
        assertArrayEquals(arrayOf("cycle", "osd-level"), MpvReceiverRequestPolicy.action("osd"))
        assertNull(MpvReceiverRequestPolicy.action("run arbitrary command"))
    }
}
