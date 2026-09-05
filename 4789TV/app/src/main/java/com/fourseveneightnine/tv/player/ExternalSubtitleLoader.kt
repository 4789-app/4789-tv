package com.fourseveneightnine.tv.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * A parsed sidecar that can be rendered by the Activity's existing SubtitleView without changing
 * the video MediaSource. The receiver keeps this in memory only; a subtitle URL is a short-lived
 * bearer URL in many of the source providers and must not be persisted or logged.
 */
@OptIn(UnstableApi::class)
internal data class ExternalSubtitleTrack(
    val url: String,
    val mimeType: String,
    val cueWindows: List<ExternalSubtitleCueWindow>,
) {
    fun cuesAt(positionMs: Long): List<Cue> = cueWindows
        .asSequence()
        .filter { positionMs >= it.startTimeMs && positionMs < it.endTimeMs }
        .flatMap { it.cues.asSequence() }
        .toList()
}

@OptIn(UnstableApi::class)
internal data class ExternalSubtitleCueWindow(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val cues: List<Cue>,
)

/** Loads and parses one complete sidecar off the main thread. */
@OptIn(UnstableApi::class)
internal class ExternalSubtitleLoader(
    private val client: OkHttpClient,
) {
    suspend fun load(url: String, headers: Map<String, String>): ExternalSubtitleTrack =
        withContext(Dispatchers.IO) {
            val requestBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", BROWSER_USER_AGENT)
            headers.forEach { (name, value) ->
                if (name.isNotBlank() && value.isNotBlank()) {
                    requestBuilder.header(name, value)
                }
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Subtitle request failed with HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("Subtitle response had no body")
                if (body.contentLength() > MAX_SUBTITLE_BYTES) {
                    throw IOException("Subtitle response is too large")
                }
                val data = readBounded(body.byteStream())
                val mimeType = ExternalSubtitleFormatPolicy.detect(
                    url = url,
                    contentType = response.header("Content-Type"),
                    data = data,
                )
                val parserData = ExternalSubtitleFormatPolicy.normalize(data, mimeType)
                val format = Format.Builder()
                    .setSampleMimeType(mimeType)
                    .setLanguage(EXTERNAL_SUBTITLE_LANGUAGE)
                    .setLabel(EXTERNAL_SUBTITLE_LABEL)
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
                val factory = DefaultSubtitleParserFactory()
                if (!factory.supportsFormat(format)) {
                    throw IOException("Unsupported subtitle format: $mimeType")
                }

                val parsed = mutableListOf<CuesWithTiming>()
                factory.create(format).parse(
                    parserData,
                    SubtitleParser.OutputOptions.allCues(),
                ) { parsed += it }

                val cueWindows = parsed.mapNotNull { window ->
                    val startUs = window.startTimeUs.takeUnless { it == C.TIME_UNSET } ?: return@mapNotNull null
                    val endUs = window.endTimeUs.takeUnless { it == C.TIME_UNSET }
                        ?: window.durationUs.takeUnless { it == C.TIME_UNSET }?.let { startUs + it }
                        ?: return@mapNotNull null
                    if (endUs <= startUs || window.cues.isEmpty()) return@mapNotNull null
                    ExternalSubtitleCueWindow(
                        startTimeMs = startUs / 1_000L,
                        endTimeMs = endUs / 1_000L,
                        cues = window.cues.toList(),
                    )
                }
                if (cueWindows.isEmpty()) {
                    throw IOException("Subtitle file contained no timed cues")
                }
                ExternalSubtitleTrack(url = url, mimeType = mimeType, cueWindows = cueWindows)
            }
        }

    private fun readBounded(input: java.io.InputStream): ByteArray {
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count == -1) break
                if (total > MAX_SUBTITLE_BYTES - count) {
                    throw IOException("Subtitle response is too large")
                }
                output.write(buffer, 0, count)
                total += count
            }
            return output.toByteArray()
        }
    }

    private companion object {
        const val MAX_SUBTITLE_BYTES = 10 * 1024 * 1024
        const val EXTERNAL_SUBTITLE_LANGUAGE = "en"
        const val EXTERNAL_SUBTITLE_LABEL = "Cast subtitle"
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36"
    }
}

/** Format detection deliberately prefers bytes over URL and provider-declared metadata. */
internal object ExternalSubtitleFormatPolicy {
    private val timingLine = Regex(
        """^\s*(?:\d{1,2}:)?\d{2}:\d{2}[.,]\d{3}\s*-->\s*(?:\d{1,2}:)?\d{2}:\d{2}[.,]\d{3}""",
    )
    private val subRipIndex = Regex("^\\s*\\d+\\s*$")
    private val subRipTimestamp = Regex(
        """^\s*(?:\d{1,2}:)?\d{2}:\d{2}[.,]\d{3}\s*-->""",
    )
    private val subRipTimestampPair = Regex(
        """(\d{1,2}:\d{2}:\d{2})\.([0-9]{3})(\s*-->\s*)(\d{1,2}:\d{2}:\d{2})\.([0-9]{3})""",
    )

    fun detect(url: String, contentType: String?, data: ByteArray): String {
        val text = String(data, StandardCharsets.UTF_8).removePrefix("\uFEFF")
        val trimmed = text.trimStart()
        val lines = text.lineSequence().map(String::trim).toList()
        val declaredMime = contentTypeMime(contentType)
        val extensionMime = pathMime(url)

        return when {
            trimmed.startsWith("WEBVTT", ignoreCase = true) -> MimeTypes.TEXT_VTT
            looksLikeTtml(trimmed) -> MimeTypes.APPLICATION_TTML
            looksLikeAss(trimmed) -> MimeTypes.TEXT_SSA
            looksLikeWebVtt(text, lines) -> MimeTypes.TEXT_VTT
            looksLikeSubRip(lines) -> MimeTypes.APPLICATION_SUBRIP
            declaredMime != null -> declaredMime
            extensionMime != null -> extensionMime
            else -> throw IOException("Could not identify subtitle format")
        }
    }

    /** Makes provider variants consumable by the parser selected above. */
    fun normalize(data: ByteArray, mimeType: String): ByteArray {
        val text = String(data, StandardCharsets.UTF_8).removePrefix("\uFEFF")
        return when (mimeType) {
            MimeTypes.TEXT_VTT -> {
                if (text.trimStart().startsWith("WEBVTT", ignoreCase = true)) {
                    text.toByteArray(StandardCharsets.UTF_8)
                } else {
                    "WEBVTT\n\n$text".toByteArray(StandardCharsets.UTF_8)
                }
            }
            MimeTypes.APPLICATION_SUBRIP -> subRipTimestampPair.replace(text) {
                "${it.groupValues[1]},${it.groupValues[2]}${it.groupValues[3]}" +
                    "${it.groupValues[4]},${it.groupValues[5]}"
            }.toByteArray(StandardCharsets.UTF_8)
            else -> data
        }
    }

    private fun looksLikeWebVtt(text: String, lines: List<String>): Boolean {
        val hasTiming = lines.any { timingLine.containsMatchIn(it) }
        val hasPeriodTimestamp = lines.any { line ->
            timingLine.containsMatchIn(line) && line.substringBefore("-->").contains('.')
        }
        return hasTiming && hasPeriodTimestamp && !looksLikeSubRip(lines)
    }

    private fun looksLikeSubRip(lines: List<String>): Boolean = lines
        .windowed(size = 2, step = 1, partialWindows = false)
        .any { (first, second) -> subRipIndex.matches(first) && subRipTimestamp.containsMatchIn(second) }

    private fun looksLikeTtml(text: String): Boolean =
        Regex("<tt(?:\\s|>)", RegexOption.IGNORE_CASE).containsMatchIn(text)

    private fun looksLikeAss(text: String): Boolean =
        Regex(
            "^\\s*\\[(?:script info|events)\\]",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
        ).containsMatchIn(text) || Regex(
            "^\\s*Dialogue:",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
        ).containsMatchIn(text)

    private fun contentTypeMime(contentType: String?): String? = when (
        contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
    ) {
        "text/vtt", "text/webvtt" -> MimeTypes.TEXT_VTT
        "application/x-subrip", "application/srt", "text/srt", "text/x-srt" -> MimeTypes.APPLICATION_SUBRIP
        "text/ssa", "text/x-ssa", "text/ass", "text/x-ass", "application/x-ass" -> MimeTypes.TEXT_SSA
        "application/ttml+xml", "text/ttml" -> MimeTypes.APPLICATION_TTML
        else -> null
    }

    private fun pathMime(url: String): String? {
        val path = url.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
        return when {
            path.endsWith(".vtt") || path.endsWith(".webvtt") -> MimeTypes.TEXT_VTT
            path.endsWith(".ass") || path.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            path.endsWith(".ttml") || path.endsWith(".xml") -> MimeTypes.APPLICATION_TTML
            path.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
            else -> null
        }
    }
}
