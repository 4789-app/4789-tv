package com.fourseveneightnine.tv.startup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverDiagnosticsTest {

    @Test
    fun playbackFailureKeepsTheCauseAndRemovesTheSourceCapability() {
        val row = playbackFailureDetails(
            title = "Heart of the Beast", source = "TorBox 4K", positionMs = 74_322L, attempt = 17L,
            code = "ERROR_CODE_IO_BAD_HTTP_STATUS", httpStatus = 403, causeClass = "InvalidResponseCodeException",
            reason = "Stream stopped at https://provider.example/private-path?token=secret-token\nCookie: secret-cookie",
            afterFirstFrame = true,
        )
        for (field in listOf("Heart of the Beast", "TorBox 4K", "attempt=17", "positionMs=74322",
                "code=ERROR_CODE_IO_BAD_HTTP_STATUS", "http=403", "afterFirstFrame=true", "Stream stopped")) {
            assertTrue(field, field in row)
        }
        for (secret in listOf("provider.example", "private-path", "secret-token", "secret-cookie", "\n")) {
            assertFalse(secret, secret in row)
        }
    }

    @Test
    fun sourceContextKeepsTitleEpisodeAndTorboxLabelWithoutCapabilities() {
        val row = com.fourseveneightnine.tv.client.data.streams.StreamRow(
            id = "private-id", addonName = "DMM Cast for TorBox", title = "TorBox FHD",
            releaseName = "private-release", quality = "1080p",
            url = "https://example.test/private-path?token=private-url-token",
            infoHash = "a".repeat(40), headers = mapOf("Authorization" to "Bearer private-header"),
        )
        val expected = com.fourseveneightnine.tv.client.data.streams.StreamExpectation(
            "IT: Welcome to Derry", 2025, 1, 2, false)
        val safe = sourceDiagnosticDetails(row, expected, "result=unknown reason=timeout_6000ms")
        for (value in listOf("IT: Welcome to Derry", "season=1", "episode=2", "DMM Cast for TorBox", "TorBox FHD", "1080p", "timeout_6000ms")) {
            assertTrue(safe.contains(value))
        }
        for (value in listOf("private-id", "private-path", "private-url-token", "private-header", "private-release", "a".repeat(40))) {
            assertFalse(safe.contains(value))
        }
        val hostile = row.copy(addonName = "https://example.test/secret-addon", title = "token=secret-label\nspoofed")
        val redacted = sourceDiagnosticDetails(hostile, expected, "result=false")
        assertFalse(redacted.contains("secret-addon"))
        assertFalse(redacted.contains("secret-label"))
        assertFalse(redacted.contains('\n'))
        assertEquals("<url>", diagnosticSafeText("stremio://example.test/private-addon-token/manifest.json"))
    }
    @Test
    fun removesCredentialsFromUrlsIncludingProviderPathsAndEncodedRoutes() {
        val safe = diagnosticSafeText(
            "open https://alice:pw@example.test/live/user/pass/123.m3u8?token=abc " +
                "route=https%3A%2F%2Fexample.test%2Fsecret-path failed",
        )
        assertEquals("open <url> route=<url> failed", safe)
    }

    @Test
    fun removesCompleteAuthorizationAndCookieValuesBeforeFlatteningLines() {
        val safe = diagnosticSafeText(
            "Authorization: Bearer private-token\nCookie: session=secret; next=hidden\n" +
                "java.io.IOException: connection reset\n at source.fetch(Source.kt:12)",
        )
        for (secret in listOf("private-token", "session=secret", "hidden")) assertFalse(safe.contains(secret))
        assertTrue(safe.contains("java.io.IOException: connection reset"))
        assertTrue(safe.contains("Source.kt:12"))
    }

    @Test
    fun removesSecretAssignmentsAndJsonValues() {
        val safe = diagnosticSafeText(
            "token=abc password='two words' api_key=xyz {\"access_token\":\"json-secret\"} Bearer raw-secret",
        )
        for (secret in listOf("abc", "two words", "xyz", "json-secret", "raw-secret")) assertFalse(safe.contains(secret))
        assertTrue(safe.contains("<redacted>"))
    }

    @Test
    fun capsLargeRecordsAndDoesNotExposeTheStartOfATruncatedUrl() {
        val safe = diagnosticSafeText("https://example.test/" + "private".repeat(10_000), 100)
        assertEquals("<url>", safe)
        assertEquals(100, diagnosticSafeText("x".repeat(10_000), 100).length)
    }

    @Test
    fun keepsCompleteUtf8LinesWithinTheByteLimit() {
        val newest = "new: 日本語 🌐\n".toByteArray(Charsets.UTF_8)
        val all = "old: αβγδε\n".toByteArray(Charsets.UTF_8) + newest
        val tail = diagnosticUtf8Tail(all, newest.size + 2)
        assertArrayEquals(newest, tail)
        assertFalse(tail.toString(Charsets.UTF_8).contains('\uFFFD'))
        assertTrue(tail.size <= newest.size + 2)
    }

    @Test
    fun neverKeepsPartialOrOversizedSingleLines() {
        assertEquals(0, diagnosticUtf8Tail("x".repeat(100).toByteArray(), 10).size)
        assertEquals(0, diagnosticUtf8Tail("line\n".toByteArray(), 0).size)
        val small = "line\n".toByteArray()
        assertArrayEquals(small, diagnosticUtf8Tail(small, small.size))
    }
}
