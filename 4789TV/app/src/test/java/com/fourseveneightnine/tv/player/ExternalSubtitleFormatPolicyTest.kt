package com.fourseveneightnine.tv.player

import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.charset.StandardCharsets

class ExternalSubtitleFormatPolicyTest {
    @Test
    fun genericEndpointWithWebVttBodyWinsOverSubRipFallback() {
        val body = """
            WEBVTT

            DID YOU SEE EUN CHAE-RYOUNG'S PHOTOS?
            00:10:38.471 --> 00:10:39.680
            ALL HER ITEMS ARE SOLD OUT
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        assertEquals(
            MimeTypes.TEXT_VTT,
            ExternalSubtitleFormatPolicy.detect(
                url = "https://subs.example/download?id=queenmaker",
                contentType = "application/octet-stream",
                data = body,
            ),
        )
    }

    @Test
    fun webVttBodyWinsEvenWhenUrlSaysSrt() {
        val body = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHello".toByteArray()

        assertEquals(
            MimeTypes.TEXT_VTT,
            ExternalSubtitleFormatPolicy.detect("https://subs.example/english.srt", null, body),
        )
    }

    @Test
    fun extensionlessSubRipIsDetectedAndPeriodTimestampsAreNormalized() {
        val body = "1\n00:00:01.000 --> 00:00:02.000\nHello\n".toByteArray()

        assertEquals(
            MimeTypes.APPLICATION_SUBRIP,
            ExternalSubtitleFormatPolicy.detect("https://subs.example/download", null, body),
        )
        assertTrue(
            String(
                ExternalSubtitleFormatPolicy.normalize(body, MimeTypes.APPLICATION_SUBRIP),
                StandardCharsets.UTF_8,
            ).contains("00:00:01,000 --> 00:00:02,000"),
        )
    }

    @Test
    fun contentTypeAndPathRemainFallbacksForKnownFormats() {
        assertEquals(
            MimeTypes.TEXT_VTT,
            ExternalSubtitleFormatPolicy.detect(
                "https://subs.example/subtitle?id=1",
                "text/vtt; charset=utf-8",
                "not a complete sample".toByteArray(),
            ),
        )
        assertEquals(
            MimeTypes.TEXT_SSA,
            ExternalSubtitleFormatPolicy.detect(
                "https://subs.example/subtitle.ass?token=1",
                null,
                "not a complete sample".toByteArray(),
            ),
        )
    }

    @Test
    fun unknownPayloadFailsClosedInsteadOfSilentlyProducingNoCues() {
        try {
            ExternalSubtitleFormatPolicy.detect(
                "https://subs.example/download?id=1",
                "application/octet-stream",
                "<html>temporary error</html>".toByteArray(),
            )
        } catch (_: IOException) {
            return
        }
        throw AssertionError("unknown subtitle payload should fail closed")
    }

    @Test
    fun vttWithoutHeaderGetsParserHeader() {
        val raw = "00:00:01.000 --> 00:00:02.000\nHello".toByteArray()
        val normalized = ExternalSubtitleFormatPolicy.normalize(raw, MimeTypes.TEXT_VTT)

        assertArrayEquals(
            "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHello".toByteArray(),
            normalized,
        )
    }

    @Test
    fun cueTimelineUsesHalfOpenWindowsWhenPlaybackCrossesCueBoundaries() {
        val track = ExternalSubtitleTrack(
            url = "https://subs.example/download?id=1",
            mimeType = MimeTypes.TEXT_VTT,
            cueWindows = listOf(
                ExternalSubtitleCueWindow(
                    startTimeMs = 1_000L,
                    endTimeMs = 2_000L,
                    cues = listOf(Cue.Builder().setText("Hello").build()),
                ),
            ),
        )

        assertTrue(track.cuesAt(999L).isEmpty())
        assertEquals(1, track.cuesAt(1_000L).size)
        assertTrue(track.cuesAt(2_000L).isEmpty())
    }

}
