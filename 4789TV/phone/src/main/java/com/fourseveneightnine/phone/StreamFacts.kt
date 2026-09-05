package com.fourseveneightnine.phone

import com.fourseveneightnine.contract.StreamEntry
import java.util.Locale

/**
 * The facts a single remote source actually states about itself.
 *
 * The wire type [StreamEntry] is frozen and carries almost nothing: url, name, title,
 * description, quality, filename, size_bytes. Real Stremio and AIOStreams addons pack the
 * useful facts into the freeform text instead, for example:
 *
 * ```
 * 📅 S01 · E01 🎟️ The Continental
 * 🎥 WEB-DL 🌗 HDR 🎞️ HEVC
 * 📁 13.08 GB · 33 Mbps
 * 🧩 Comet 🏷️ XEBEC
 * ```
 *
 * So this parser reads name, title, description and filename together and pulls out what is
 * written there. It mirrors `StreamMetric.swift` on iOS.
 *
 * **The honesty rule.** A bitrate is a measured number or it does not exist. It is read from an
 * explicit `Mbps` or `kbps` marker only. Size is never divided by a guessed runtime to invent one.
 * Any fact the text did not state stays null, and the row simply does not print that chip.
 */
internal data class StreamFacts(
    /** "4K", "1440p", "1080p", "720p", "480p" or "SD". Null when no resolution was stated. */
    val quality: String? = null,
    val sizeGB: Double? = null,
    val bitrateMbps: Double? = null,
    val codec: String? = null,
    val hdr: Boolean = false,
    val dolbyVision: Boolean = false,
    val audio: String? = null,
    val provider: String? = null,
    val seeders: Int? = null,
    val ageText: String? = null,
) {
    /** Higher is better. -1 means the source never stated a resolution. */
    val qualityRank: Int
        get() = when (quality) {
            "4K" -> 5
            "1440p" -> 4
            "1080p" -> 3
            "720p" -> 2
            "480p" -> 1
            "SD" -> 0
            else -> -1
        }

    companion object {
        private val SIZE = Regex("""[0-9]+([.,][0-9]+)?\s?(GB|MB)""", RegexOption.IGNORE_CASE)
        private val SUB_ONE_MBPS = Regex("""<\s?1\s?Mbps""", RegexOption.IGNORE_CASE)
        private val MBPS = Regex("""[0-9]+([.,][0-9]+)?\s?Mbps""", RegexOption.IGNORE_CASE)
        private val KBPS = Regex("""[0-9]+([.,][0-9]+)?\s?kbps""", RegexOption.IGNORE_CASE)
        // Real release names write HDR as "HDR", "HDR10" or "HDR10+", so the optional suffix has to
        // be part of the match. The trailing boundary still rejects "HDRip", which is a rip format
        // and not high dynamic range. "HDR10+" matches through backtracking: the group falls back to
        // "10" and the boundary lands between "0" and "+".
        private val HDR = Regex("""\bHDR(10\+?)?\b""", RegexOption.IGNORE_CASE)
        private val DOLBY_VISION = Regex("""\bDV\b""", RegexOption.IGNORE_CASE)
        private val STANDARD_DEFINITION = Regex("""\bsd\b""", RegexOption.IGNORE_CASE)
        private val AUDIO_MARKER = Regex("""🔊\s*([^\n]+)""")
        private val PROVIDER_MARKER = Regex("""🧩\s*([^\n]+)""")
        private val SEEDERS_MARKER = Regex("""🌱\s*([0-9][0-9.,]*)""")
        private val AGE_MARKER = Regex(
            """🌱[^\n]*?[·•|,/]\s*([0-9]+(?:\.[0-9]+)?\s*(?:mo|[ywdhm]))\b""",
        )
        private val CODECS = listOf(
            Regex("""hevc|h\.?265|x265""", RegexOption.IGNORE_CASE) to "HEVC",
            Regex("""avc|h\.?264|x264""", RegexOption.IGNORE_CASE) to "AVC",
            Regex("""av1""", RegexOption.IGNORE_CASE) to "AV1",
            Regex("""vp9""", RegexOption.IGNORE_CASE) to "VP9",
            Regex("""mpeg-?2""", RegexOption.IGNORE_CASE) to "MPEG-2",
        )
        private const val BYTES_PER_GB = 1_073_741_824.0

        fun from(stream: StreamEntry): StreamFacts {
            val text = listOfNotNull(stream.name, stream.title, stream.description, stream.filename)
                .filter(String::isNotBlank)
                .joinToString("\n")
            return StreamFacts(
                quality = normalizedQuality(stream.quality, text),
                sizeGB = sizeGB(text, stream.sizeBytes),
                bitrateMbps = bitrateMbps(text),
                codec = codec(text),
                hdr = HDR.containsMatchIn(text),
                dolbyVision = DOLBY_VISION.containsMatchIn(text) ||
                    text.contains("dolby vision", ignoreCase = true),
                audio = audio(text),
                provider = provider(text),
                seeders = seeders(text),
                ageText = age(text),
            )
        }

        /**
         * The stated resolution, or null. "DS4K" and "downscaled" mean downscaled FROM 4K, so the
         * real resolution token in the same name must win instead of reading as 4K.
         */
        fun normalizedQuality(raw: String?, fallbackText: String): String? {
            val hay = (raw?.takeIf(String::isNotBlank) ?: fallbackText).lowercase(Locale.ROOT)
            val downscaled = hay.contains("ds4k") || hay.contains("ds 4k") ||
                hay.contains("ds-4k") || hay.contains("downscal")
            if (!downscaled && (hay.contains("2160") || hay.contains("4k") || hay.contains("uhd"))) {
                return "4K"
            }
            if (hay.contains("1440")) return "1440p"
            if (hay.contains("1080") || hay.contains("fhd")) return "1080p"
            if (hay.contains("720")) return "720p"
            if (hay.contains("480")) return "480p"
            if (STANDARD_DEFINITION.containsMatchIn(hay)) return "SD"
            return null
        }

        /** The exact byte count when the addon sent one, else the size written in the text. */
        private fun sizeGB(text: String, sizeBytes: Long?): Double? {
            if (sizeBytes != null && sizeBytes > 0) return sizeBytes / BYTES_PER_GB
            val token = SIZE.find(text)?.value ?: return null
            val value = number(token) ?: return null
            return if (token.contains("MB", ignoreCase = true)) value / 1024 else value
        }

        /** An explicit marker only. A source that never stated a bitrate does not get one. */
        private fun bitrateMbps(text: String): Double? {
            if (SUB_ONE_MBPS.containsMatchIn(text)) return 0.5
            KBPS.find(text)?.value?.let { token -> number(token)?.let { return it / 1000 } }
            MBPS.find(text)?.value?.let { token -> number(token)?.let { return it } }
            return null
        }

        private fun codec(text: String): String? =
            CODECS.firstOrNull { (pattern, _) -> pattern.containsMatchIn(text) }?.second

        /** The 🔊 line when there is one, else the audio format written into the release name. */
        private fun audio(text: String): String? {
            val marked = AUDIO_MARKER.find(text)?.groupValues?.getOrNull(1)
                ?.trim()
                ?.trim(' ', '|', '·', '-')
            if (!marked.isNullOrBlank()) return marked
            return audioFromName(text)
        }

        private fun audioFromName(text: String): String? {
            val n = text.lowercase(Locale.ROOT)
            val format = when {
                n.contains("atmos") -> "Atmos"
                n.contains("truehd") || n.contains("true-hd") -> "TrueHD"
                n.contains("dts-hd") || n.contains("dtshd") || n.contains("dts:x") -> "DTS-HD"
                n.contains("dts") -> "DTS"
                n.contains("ddp") || n.contains("dd+") || n.contains("eac3") -> "DD+"
                n.contains("ac3") || n.contains("dolby digital") -> "DD"
                n.contains("flac") -> "FLAC"
                n.contains("aac") -> "AAC"
                else -> null
            }
            val channels = when {
                n.contains("7.1") -> "7.1"
                n.contains("5.1") -> "5.1"
                n.contains("2.0") -> "2.0"
                else -> null
            }
            return when {
                format != null && channels != null -> "$format $channels"
                format != null -> format
                else -> channels
            }
        }

        /** The name after the 🧩 marker, cut before the 🏷️ release-group marker. */
        private fun provider(text: String): String? = PROVIDER_MARKER.find(text)
            ?.groupValues?.getOrNull(1)
            ?.substringBefore("🏷")
            ?.trim()
            ?.takeIf(String::isNotBlank)

        private fun seeders(text: String): Int? = SEEDERS_MARKER.find(text)
            ?.groupValues?.getOrNull(1)
            ?.filter(Char::isDigit)
            ?.takeIf(String::isNotEmpty)
            ?.toIntOrNull()

        private fun age(text: String): String? = AGE_MARKER.find(text)
            ?.groupValues?.getOrNull(1)
            ?.replace(" ", "")
            ?.takeIf(String::isNotBlank)

        private fun number(token: String): Double? = token
            .replace(',', '.')
            .filter { it.isDigit() || it == '.' }
            .toDoubleOrNull()
    }
}
