package com.fourseveneightnine.tv.client.data.streams

import java.util.Locale

/**
 * The facts one source actually states about itself.
 *
 * Ported from `phone/StreamFacts.kt` and widened for the TV: several codecs rather than one, an
 * audio-language list, and a cached hint, because the Streams screen (§10.3, §10.4) prints all
 * three and the ranker reads all three.
 *
 * **The honesty rule, kept from the phone.** A bitrate is a measured number or it does not exist.
 * It is read from an explicit `Mbps`/`kbps` marker only, never from size divided by a guessed
 * runtime. Anything the text did not state stays null, and the row simply does not print that
 * chip. Missing is not the same as zero, and a ranker that treats it as zero punishes the add-ons
 * that write the least text rather than the sources that are worst.
 */
data class StreamFacts(
    val quality: String? = null,
    val sizeBytes: Long? = null,
    val bitrateMbps: Double? = null,
    val codecs: List<String> = emptyList(),
    val hdr: HdrFormat = HdrFormat.NONE,
    /** 5 when the release says Dolby Vision profile 5, 8 for profile 8, null when it did not say. */
    val dolbyVisionProfile: Int? = null,
    val audio: String? = null,
    val audioChannels: String? = null,
    val audioLanguages: List<String> = emptyList(),
    val provider: String? = null,
    val seeders: Int? = null,
    val ageText: String? = null,
    val cachedHint: CachedHint = CachedHint(false),
    val releaseGroup: String? = null,
    val resolutionText: String? = null,
) {
    /** Higher is better. -1 means the source never stated a resolution. */
    val qualityRank: Int
        get() = qualityRank(quality)

    val sizeGB: Double? get() = sizeBytes?.takeIf { it > 0 }?.let { it / StreamRow.BYTES_PER_GB }

    val codec: String? get() = codecs.firstOrNull()

    val isHEVC: Boolean get() = codecs.any { it == "HEVC" }

    val isObjectAudio: Boolean
        get() = audio?.lowercase(Locale.ROOT)?.let {
            it.contains("atmos") || it.contains("dts-x") || it.contains("dtsx") || it.contains("dts:x")
        } == true

    /** "Atmos 7.1", or just the half that was stated, or null. */
    val audioLine: String?
        get() {
            // A name like "[5.1]" fills both fields with "5.1"; the badge read "5.1 5.1".
            val channels = audioChannels?.takeUnless { audio?.endsWith(it) == true }
            return listOfNotNull(audio, channels).takeIf { it.isNotEmpty() }?.joinToString(" ")
        }

    companion object {

        fun qualityRank(quality: String?): Int = when (quality) {
            "4K" -> 5
            "1440p" -> 4
            "1080p" -> 3
            "720p" -> 2
            "480p" -> 1
            "SD" -> 0
            else -> -1
        }

        /** The label for a rank, for the "Matches your 1080p rule" reason line. */
        fun qualityLabel(rank: Int): String = when {
            rank >= 5 -> "4K"
            rank == 4 -> "1440p"
            rank == 3 -> "1080p"
            rank == 2 -> "720p"
            rank == 1 -> "480p"
            else -> "any quality"
        }

        /**
         * Read every text field the add-on sent as one block. Add-ons scatter the same fact across
         * name, title, description and filename, and which one holds it changes between add-ons.
         */
        fun parse(
            texts: List<String?>,
            sizeBytes: Long? = null,
            statedQuality: String? = null,
            seeders: Int? = null,
        ): StreamFacts {
            val text = texts.filterNotNull().filter(String::isNotBlank).joinToString("\n")
            val lower = text.lowercase(Locale.ROOT)
            val dv = dolbyVision(text, lower)
            return StreamFacts(
                quality = normalizedQuality(statedQuality, text),
                sizeBytes = sizeBytes?.takeIf { it > 0 } ?: sizeBytesFromText(text),
                bitrateMbps = bitrateMbps(text),
                codecs = codecs(text),
                hdr = when {
                    dv != null || DOLBY_VISION_WORD.containsMatchIn(text) -> HdrFormat.DOLBY_VISION
                    HDR.containsMatchIn(text) -> HdrFormat.HDR10
                    else -> HdrFormat.NONE
                },
                dolbyVisionProfile = dv,
                audio = audioFormat(text, lower),
                audioChannels = audioChannels(lower),
                audioLanguages = audioLanguages(text, lower),
                provider = provider(text),
                seeders = seeders ?: seeders(text),
                ageText = age(text),
                cachedHint = cachedHint(text, lower),
                releaseGroup = releaseGroup(text),
                resolutionText = resolutionText(text),
            )
        }

        /**
         * The stated resolution, or null. "DS4K" and "downscaled" mean downscaled FROM 4K, so the
         * real resolution token in the same name has to win instead of reading as 4K.
         */
        fun normalizedQuality(raw: String?, fallbackText: String): String? {
            val hay = (raw?.takeIf(String::isNotBlank)?.plus("\n$fallbackText") ?: fallbackText)
                .lowercase(Locale.ROOT)
            val downscaled = hay.contains("ds4k") || hay.contains("ds 4k") ||
                hay.contains("ds-4k") || hay.contains("downscal")
            if (!downscaled && hay.contains("2160")) return "4K"
            if (hay.contains("1440")) return "1440p"
            if (hay.contains("1080") || hay.contains("fhd")) return "1080p"
            if (hay.contains("720")) return "720p"
            if (hay.contains("480") || hay.contains("576")) return "480p"
            // UHD and 4K are less precise than a stated pixel height. Some add-ons call a
            // 1080p WEBRip "UHD" because of its source; the 1080p file must not get a 4K badge.
            if (!downscaled && (FOUR_K.containsMatchIn(hay) || hay.contains("uhd"))) return "4K"
            if (STANDARD_DEFINITION.containsMatchIn(hay)) return "SD"
            return null
        }

        private val SIZE = Regex("""([0-9]+(?:[.,][0-9]+)?)\s?(GB|MB|GiB|MiB)\b""", RegexOption.IGNORE_CASE)
        private val SUB_ONE_MBPS = Regex("""<\s?1\s?Mbps""", RegexOption.IGNORE_CASE)
        private val MBPS = Regex("""[0-9]+([.,][0-9]+)?\s?Mbps""", RegexOption.IGNORE_CASE)
        private val KBPS = Regex("""[0-9]+([.,][0-9]+)?\s?kbps""", RegexOption.IGNORE_CASE)
        private val FOUR_K = Regex("""\b4k\b""", RegexOption.IGNORE_CASE)

        // Real release names write HDR as "HDR", "HDR10" or "HDR10+", so the optional suffix is part
        // of the match. The trailing boundary still rejects "HDRip", which is a rip format and not
        // high dynamic range.
        private val HDR = Regex("""\bHDR(10\+?)?\b|\bHDR10PLUS\b|\bHLG\b|\bPQ10\b""", RegexOption.IGNORE_CASE)
        private val DOLBY_VISION_WORD = Regex("""\bDV\b|\bDoVi\b|dolby\s?vision""", RegexOption.IGNORE_CASE)
        private val DV_PROFILE = Regex(
            """dvhe\.0?([0-9])|\bdv\s?-?\s?p(?:rofile)?\.?\s?0?([0-9])\b|\bprofile\s0?([0-9])\b""",
            RegexOption.IGNORE_CASE,
        )
        private val STANDARD_DEFINITION = Regex("""\bsd\b""", RegexOption.IGNORE_CASE)
        private val AUDIO_MARKER = Regex("""[🔊🔈🎧]\s*([^\n]+)""")
        private val PROVIDER_MARKER = Regex("""🧩\s*([^\n]+)""")
        private val GROUP_MARKER = Regex("""🏷️?\s*([^\n|·]+)""")
        private val SEEDERS_MARKER = Regex("""(?:🌱|👤|👥|seeders?[:\s])\s*([0-9][0-9.,]*)""", RegexOption.IGNORE_CASE)
        private val AGE_MARKER = Regex("""🌱[^\n]*?[·•|,/]\s*([0-9]+(?:\.[0-9]+)?\s*(?:mo|[ywdhm]))\b""")
        private val RESOLUTION = Regex("""\b([0-9]{3,4})\s?[x×]\s?([0-9]{3,4})\b""")
        private val TRAILING_GROUP = Regex("""-([A-Za-z0-9]{2,20})(?:\.[a-z0-9]{2,4})?$""")

        private val CODECS = listOf(
            Regex("""hevc|h\.?\s?265|x265""", RegexOption.IGNORE_CASE) to "HEVC",
            Regex("""\bav1\b""", RegexOption.IGNORE_CASE) to "AV1",
            Regex("""avc|h\.?\s?264|x264""", RegexOption.IGNORE_CASE) to "AVC",
            Regex("""\bvp9\b""", RegexOption.IGNORE_CASE) to "VP9",
            Regex("""mpeg-?2""", RegexOption.IGNORE_CASE) to "MPEG-2",
        )

        /**
         * Cached markers, by add-on. Nothing here is a probe: every one is a claim the add-on
         * printed into its own title, and the field it feeds is called a hint for that reason.
         */
        private val CACHED_SERVICES = listOf(
            Regex("""\[RD\+?]|\bRD\+""", RegexOption.IGNORE_CASE) to "Real-Debrid",
            Regex("""\[TB\+?]|\bTB\+""", RegexOption.IGNORE_CASE) to "TorBox",
            Regex("""\[AD\+?]""", RegexOption.IGNORE_CASE) to "AllDebrid",
            Regex("""\[PM\+?]""", RegexOption.IGNORE_CASE) to "Premiumize",
            Regex("""\[DL\+?]""", RegexOption.IGNORE_CASE) to "Debrid-Link",
        )
        private val CACHED_WORDS = Regex("""⚡|\bcached\b|\binstant\b""", RegexOption.IGNORE_CASE)
        private val UNCACHED_WORDS = Regex("""⏳|\bdownload\s?required\b|\bnot\s?cached\b|\buncached\b""", RegexOption.IGNORE_CASE)

        /**
         * Languages an add-on names in a title. Written as whole words so "Tamil" does not fire on
         * "TamilMV" spelled inside a group tag, and so "hi" never matches "this".
         */
        private val LANGUAGE_WORDS = linkedMapOf(
            "english" to "English", "hindi" to "Hindi", "tamil" to "Tamil", "telugu" to "Telugu",
            "malayalam" to "Malayalam", "kannada" to "Kannada", "bengali" to "Bengali",
            "marathi" to "Marathi", "punjabi" to "Punjabi", "gujarati" to "Gujarati",
            "japanese" to "Japanese", "korean" to "Korean", "mandarin" to "Chinese",
            "chinese" to "Chinese", "cantonese" to "Chinese", "spanish" to "Spanish",
            "latino" to "Spanish", "castellano" to "Spanish", "french" to "French",
            "german" to "German", "italian" to "Italian", "portuguese" to "Portuguese",
            "russian" to "Russian", "arabic" to "Arabic", "turkish" to "Turkish",
            "thai" to "Thai", "dutch" to "Dutch", "polish" to "Polish", "swedish" to "Swedish",
            "ukrainian" to "Ukrainian", "indonesian" to "Indonesian", "vietnamese" to "Vietnamese",
        )
        private val LANGUAGE_FLAGS = mapOf(
            "🇬🇧" to "English", "🇺🇸" to "English",
            "🇮🇳" to "Hindi", "🇯🇵" to "Japanese",
            "🇰🇷" to "Korean", "🇪🇸" to "Spanish",
            "🇫🇷" to "French", "🇩🇪" to "German",
            "🇮🇹" to "Italian", "🇵🇹" to "Portuguese",
            "🇧🇷" to "Portuguese", "🇷🇺" to "Russian",
            "🇨🇳" to "Chinese", "🇸🇦" to "Arabic",
            "🇹🇷" to "Turkish",
        )

        private fun sizeBytesFromText(text: String): Long? {
            val match = SIZE.find(text) ?: return null
            val value = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            val unit = match.groupValues[2].lowercase(Locale.ROOT)
            val bytes = when {
                unit.startsWith("g") -> value * StreamRow.BYTES_PER_GB
                else -> value * 1_048_576.0
            }
            return bytes.toLong().takeIf { it > 0 }
        }

        private fun bitrateMbps(text: String): Double? {
            if (SUB_ONE_MBPS.containsMatchIn(text)) return 0.5
            KBPS.find(text)?.value?.let { token -> number(token)?.let { return it / 1000 } }
            MBPS.find(text)?.value?.let { token -> number(token)?.let { return it } }
            return null
        }

        private fun codecs(text: String): List<String> =
            CODECS.filter { (pattern, _) -> pattern.containsMatchIn(text) }.map { it.second }

        private fun dolbyVision(text: String, lower: String): Int? {
            if (!DOLBY_VISION_WORD.containsMatchIn(text) && !lower.contains("dvhe")) return null
            val match = DV_PROFILE.find(text) ?: return null
            return match.groupValues.drop(1).firstOrNull(String::isNotEmpty)?.toIntOrNull()
        }

        private fun audioFormat(text: String, lower: String): String? {
            val marked = AUDIO_MARKER.find(text)?.groupValues?.getOrNull(1)
                ?.trim()?.trim(' ', '|', '·', '-')
            val hay = if (!marked.isNullOrBlank()) marked.lowercase(Locale.ROOT) else lower
            return when {
                hay.contains("atmos") -> "Atmos"
                hay.contains("truehd") || hay.contains("true-hd") -> "TrueHD"
                hay.contains("dts:x") || hay.contains("dts-x") || hay.contains("dtsx") -> "DTS-X"
                hay.contains("dts-hd") || hay.contains("dtshd") -> "DTS-HD"
                hay.contains("dts") -> "DTS"
                hay.contains("ddp") || hay.contains("dd+") || hay.contains("eac3") ||
                    hay.contains("e-ac3") -> "DD+"
                hay.contains("ac3") || hay.contains("dolby digital") -> "DD"
                hay.contains("flac") -> "FLAC"
                hay.contains("opus") -> "Opus"
                hay.contains("aac") -> "AAC"
                else -> marked?.takeIf(String::isNotBlank)?.take(24)
            }
        }

        private fun audioChannels(lower: String): String? = when {
            lower.contains("7.1") -> "7.1"
            lower.contains("5.1") -> "5.1"
            lower.contains("2.0") || lower.contains("stereo") -> "2.0"
            else -> null
        }

        private fun audioLanguages(text: String, lower: String): List<String> {
            val found = linkedSetOf<String>()
            LANGUAGE_FLAGS.forEach { (flag, name) -> if (text.contains(flag)) found += name }
            LANGUAGE_WORDS.forEach { (token, name) ->
                if (Regex("""\b$token\b""").containsMatchIn(lower)) found += name
            }
            if (found.isEmpty() && (lower.contains("dual audio") || lower.contains("dual-audio"))) {
                found += "Dual audio"
            }
            if (found.isEmpty() && Regex("""\bmulti(?:-|\s)?(?:audio|lang\w*)?\b""").containsMatchIn(lower)) {
                found += "Multi"
            }
            return found.toList().take(6)
        }

        private fun provider(text: String): String? = PROVIDER_MARKER.find(text)
            ?.groupValues?.getOrNull(1)
            ?.substringBefore("🏷")
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.take(40)

        private fun releaseGroup(text: String): String? {
            GROUP_MARKER.find(text)?.groupValues?.getOrNull(1)?.trim()
                ?.takeIf(String::isNotBlank)?.let { return it.take(40) }
            return text.lineSequence()
                .mapNotNull { TRAILING_GROUP.find(it.trim())?.groupValues?.getOrNull(1) }
                .firstOrNull()
                ?.take(40)
        }

        private fun resolutionText(text: String): String? = RESOLUTION.find(text)?.let {
            "${it.groupValues[1]}x${it.groupValues[2]}"
        }

        private fun seeders(text: String): Int? = SEEDERS_MARKER.find(text)
            ?.groupValues?.getOrNull(1)
            ?.filter(Char::isDigit)
            ?.takeIf(String::isNotEmpty)
            ?.toIntOrNull()

        private fun age(text: String): String? = AGE_MARKER.find(text)
            ?.groupValues?.getOrNull(1)
            ?.replace(" ", "")
            ?.takeIf(String::isNotBlank)

        private fun cachedHint(text: String, lower: String): CachedHint {
            val service = CACHED_SERVICES.firstOrNull { (pattern, _) -> pattern.containsMatchIn(text) }?.second
            // An explicit "not cached" marker beats a service tag: Torrentio prints `[RD download]`
            // for a hash its account does not hold, and the tag alone would read as ready.
            if (UNCACHED_WORDS.containsMatchIn(text) && !CACHED_WORDS.containsMatchIn(text)) {
                return CachedHint(cached = false, service = service)
            }
            val cached = service != null || CACHED_WORDS.containsMatchIn(lower)
            return CachedHint(cached = cached, service = service)
        }

        private fun number(token: String): Double? = token
            .replace(',', '.')
            .filter { it.isDigit() || it == '.' }
            .toDoubleOrNull()
    }
}
