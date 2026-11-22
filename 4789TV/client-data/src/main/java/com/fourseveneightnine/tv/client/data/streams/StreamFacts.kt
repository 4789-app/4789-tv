package com.fourseveneightnine.tv.client.data.streams

import java.util.Locale
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

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
    /** Explicit audio-language evidence. Country flags and generic Multi/Dual are not evidence. */
    val reportedAudioLanguages: List<String> = emptyList(),
    val theatricalRelease: Boolean = false,
    val audioCodecs: List<String> = emptyList(),
    val subtitleLanguages: List<String> = emptyList(),
    val durationSeconds: Long? = null,
    /** Source-reported dates; these are never inferred from the title's release year. */
    val sourceDates: Map<String, Long> = emptyMap(),
    val folderName: String? = null,
    val folderSizeBytes: Long? = null,
    val container: String? = null,
    val bitDepth: Int? = null,
    val server: String? = null,
    val frameRate: Double? = null,
    /** The add-on's OpenSubtitles file fingerprint, never a torrent/cache hash. */
    val videoHash: String? = null,
) {
    /** Higher is better. -1 means the source never stated a resolution. */
    val qualityRank: Int
        get() = qualityRank(quality)

    val sizeGB: Double? get() = sizeBytes?.takeIf { it > 0 }?.let { it / StreamRow.BYTES_PER_GB }

    private val reportedAgeMinutes: Double? = ageText?.trim()?.let(AGE_VALUE::matchEntire)?.let { match ->
        val amount = match.groupValues[1].toDoubleOrNull() ?: return@let null
        // Relative-age ranking treats a reported month as 30 days and a year as 365 days.
        val minutes = when (match.groupValues[2]) {
            "m" -> 1.0
            "h" -> 60.0
            "d" -> 1_440.0
            "w" -> 10_080.0
            "mo" -> 43_200.0
            "y" -> 525_600.0
            else -> return@let null
        }
        (amount * minutes).takeIf { it.isFinite() && it >= 0 }
    }

    val ageMinutes: Double? = ageMinutesAt(System.currentTimeMillis())

    fun ageMinutesAt(nowMillis: Long): Double? = reportedAgeMinutes ?:
        (sourceDates["Published"] ?: sourceDates["Uploaded"] ?: sourceDates["Added"])
            ?.let { (nowMillis - it).coerceAtLeast(0L) / 60_000.0 }

    /** Fill missing technical facts when an identical delivery URL has several descriptions. */
    fun merging(other: StreamFacts): StreamFacts = copy(
        quality = quality ?: other.quality, sizeBytes = sizeBytes ?: other.sizeBytes,
        bitrateMbps = bitrateMbps ?: other.bitrateMbps, codecs = (codecs + other.codecs).distinct(),
        hdr = if (hdr == HdrFormat.NONE) other.hdr else hdr,
        dolbyVisionProfile = dolbyVisionProfile ?: other.dolbyVisionProfile,
        audio = audio ?: other.audio, audioChannels = audioChannels ?: other.audioChannels,
        audioLanguages = (audioLanguages + other.audioLanguages).distinct(),
        reportedAudioLanguages = (reportedAudioLanguages + other.reportedAudioLanguages).distinct(),
        audioCodecs = (audioCodecs + other.audioCodecs).distinct(),
        subtitleLanguages = (subtitleLanguages + other.subtitleLanguages).distinct(),
        provider = provider ?: other.provider, seeders = seeders ?: other.seeders,
        ageText = ageText ?: other.ageText, releaseGroup = releaseGroup ?: other.releaseGroup,
        resolutionText = resolutionText ?: other.resolutionText,
        durationSeconds = durationSeconds ?: other.durationSeconds,
        sourceDates = other.sourceDates + sourceDates, folderName = folderName ?: other.folderName,
        folderSizeBytes = folderSizeBytes ?: other.folderSizeBytes, container = container ?: other.container,
        bitDepth = bitDepth ?: other.bitDepth, server = server ?: other.server,
        frameRate = frameRate ?: other.frameRate, videoHash = videoHash ?: other.videoHash,
        theatricalRelease = theatricalRelease || other.theatricalRelease,
    )

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

        /** A source label, not a quality preference. Never restored by a never-empty fallback. */
        fun isTheatricalRelease(value: String): Boolean = value.lineSequence().any { raw ->
            val text = raw.replace(TS_EXTENSION, "")
            val tail = TECH_ANCHOR.find(text)?.range?.first?.let { text.substring(it) } ?: text
            THEATRICAL_COMPOUND.containsMatchIn(text) || THEATRICAL_SHORT.containsMatchIn(tail)
        }

        private val TS_EXTENSION = Regex("\\.ts\\s*$", RegexOption.IGNORE_CASE)
        private val TECH_ANCHOR = Regex("(?<![a-z0-9])(?:(?:19|20)[0-9]{2}|s[0-9]+e[0-9]+|[0-9]{3,4}[pi]|4k)", RegexOption.IGNORE_CASE)
        private val THEATRICAL_COMPOUND = Regex(
            "(?<![a-z0-9])(?:(?:NEW|HD|HQ)[ ._-]*CAM(?:[ ._-]*RIP)?|CAM[ ._-]*RIP|" +
                "(?:HD|HQ)[ ._-]*(?:TS|TC)(?:[ ._-]*RIP)?|(?:TS|TC)[ ._-]*RIP|" +
                "TELE[ ._-]*(?:SYNCH?|CINE)(?:[ ._-]*RIP)?|(?:PRE[ ._-]*DVD|P[ ._-]*DVD)(?:[ ._-]*RIP)?|" +
                "(?:DVD|BD|HD|WEB)[ ._-]*(?:SCR|SCREENER)|SCREENER|WORK[ ._-]*PRINT|THEAT(?:ER|RE)[ ._-]*(?:PRINT|RIP))(?![a-z0-9])",
            RegexOption.IGNORE_CASE,
        )
        private val THEATRICAL_SHORT = Regex("(?<![a-z0-9])(?:CAM|TS|TC|SCR|WP)(?![a-z0-9])(?![ ._()\\-]*(?:19|20)[0-9]{2}\\b)", RegexOption.IGNORE_CASE)

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
            val reportedLanguages = OriginalAudioLanguage.reported(text)
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
                audioLanguages = reportedLanguages.ifEmpty { audioLanguages(text, lower) },
                reportedAudioLanguages = reportedLanguages,
                provider = provider(text),
                seeders = seeders ?: seeders(text),
                ageText = age(text),
                cachedHint = cachedHint(text, lower),
                releaseGroup = releaseGroup(text),
                resolutionText = resolutionText(text),
                theatricalRelease = texts.filterNotNull().any(::isTheatricalRelease),
                audioCodecs = audioCodecs(text),
                subtitleLanguages = subtitleLanguages(text),
                container = CONTAINER.findAll(text).lastOrNull()?.groupValues?.get(1)?.uppercase(Locale.ROOT),
                bitDepth = BIT_DEPTH.find(text)?.groupValues?.get(1)?.toIntOrNull(),
                server = SOURCE_SERVER.find(text)?.groupValues?.get(1)?.trim()?.take(100),
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
        private val PROVIDER_MARKER = Regex("""(?:🧩|🛰️?\s*Source:|Provider:)\s*([^\n]+)""", RegexOption.IGNORE_CASE)
        private val GROUP_MARKER = Regex("""🏷️?\s*([^\n|·]+)""")
        private val SEEDERS_MARKER = Regex("""(?:🌱|👤|👥|seeders?[:\s])\s*([0-9][0-9.,]*)""", RegexOption.IGNORE_CASE)
        private val AGE_MARKER = Regex("""🌱[^\n]*?[·•|,/]\s*([0-9]+(?:\.[0-9]+)?\s*(?:mo|[ywdhm]))\b""")
        private val AGE_VALUE = Regex("""([0-9]+(?:\.[0-9]+)?)\s*(mo|[ywdhm])""")
        private val RESOLUTION = Regex("""\b([0-9]{3,4})\s?[x×]\s?([0-9]{3,4})\b""")
        private val TRAILING_GROUP = Regex("""-([A-Za-z0-9]{2,20})(?:\.[a-z0-9]{2,4})?$""")

        private val CODECS = listOf(
            Regex("""hevc|h\.?\s?265|x265""", RegexOption.IGNORE_CASE) to "HEVC",
            Regex("""\bav1\b""", RegexOption.IGNORE_CASE) to "AV1",
            Regex("""avc|h\.?\s?264|x264""", RegexOption.IGNORE_CASE) to "AVC",
            Regex("""\bvp9\b""", RegexOption.IGNORE_CASE) to "VP9",
            Regex("""mpeg-?2""", RegexOption.IGNORE_CASE) to "MPEG-2",
            Regex("""\bxvid\b|\bdivx\b|\bmpeg[ ._-]?4\b""", RegexOption.IGNORE_CASE) to "MPEG-4",
            Regex("""\bvp8\b""", RegexOption.IGNORE_CASE) to "VP8",
        )

        /**
         * Cached markers, by add-on. Nothing here is a probe: every one is a claim the add-on
         * printed into its own title, and the field it feeds is called a hint for that reason.
         */
        private val CACHED_SERVICES = listOf(
            Regex("""\[RD\+?]|\bRD\+|\bReal[ ._-]?Debrid\b""", RegexOption.IGNORE_CASE) to "Real-Debrid",
            Regex("""\[TB\+?]|\bTB\+|\bTorBox\b""", RegexOption.IGNORE_CASE) to "TorBox",
            Regex("""\[AD\+?]""", RegexOption.IGNORE_CASE) to "AllDebrid",
            Regex("""\[PM\+?]""", RegexOption.IGNORE_CASE) to "Premiumize",
            Regex("""\[DL\+?]""", RegexOption.IGNORE_CASE) to "Debrid-Link",
        )
        private val CACHED_WORDS = Regex("""⚡|\bcached\b|\binstant\b""", RegexOption.IGNORE_CASE)
        private val UNCACHED_WORDS = Regex("""⏳|\[(?:rd|tb|trb|ad|pm|dl)(?:-| download)]|\bcache[ ._-]*miss\b|\bcached\s*[:=]\s*(?:false|no|0)\b|(?:^|\n)\s*(?:queued|downloading)\b|\bdownload[ ._-]*required\b|\bnot[ ._-]*cached\b|\bun[ ._-]*cached\b""", RegexOption.IGNORE_CASE)
        private val CACHED_SERVICE_CLAIM = Regex("""\[(?:RD|TB|AD|PM|DL|TorBox|Real[ ._-]?Debrid)\+]|\b(?:RD|TB)\+""", RegexOption.IGNORE_CASE)

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
            val hay = if (!marked.isNullOrBlank() && (audioCodecs(marked).isNotEmpty() ||
                Regex("\\batmos\\b", RegexOption.IGNORE_CASE).containsMatchIn(marked))) marked.lowercase(Locale.ROOT) else lower
            val formats = audioCodecs(hay)
            return when {
                hay.contains("atmos") -> "Atmos"
                "TrueHD" in formats -> "TrueHD"
                hay.contains("dts:x") || hay.contains("dts-x") || hay.contains("dtsx") -> "DTS-X"
                hay.contains("dts-hd") || hay.contains("dtshd") -> "DTS-HD"
                hay.contains("dts") -> "DTS"
                "E-AC-3" in formats -> "DD+"
                hay.contains("ac3") || hay.contains("dolby digital") || Regex("\\bdd(?=[ ._-]?[0-9]|\\b)(?![p+])").containsMatchIn(hay) -> "DD"
                hay.contains("flac") -> "FLAC"
                hay.contains("opus") -> "Opus"
                hay.contains("aac") -> "AAC"
                else -> null
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
            ?.substringBefore("🏷")?.substringBefore("•")
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.take(80) ?: "DMM Cast".takeIf { text.contains("⚡ DMM Cast", ignoreCase = true) }

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
            if (UNCACHED_WORDS.containsMatchIn(text)) {
                return CachedHint(cached = false, service = service, uncached = true)
            }
            val cached = CACHED_SERVICE_CLAIM.containsMatchIn(text) || CACHED_WORDS.containsMatchIn(lower)
            return CachedHint(cached = cached, service = service)
        }

        private val AUDIO_CODECS = listOf(
            Regex("""(?i)\btrue[ ._-]?hd\b""") to "TrueHD",
            Regex("""(?i)\b(?:e[ ._-]?ac[ ._-]?3|ddp)(?=[ ._-]?[0-9]|\b)|dd\+""") to "E-AC-3",
            Regex("""(?i)\b(?:ac[ ._-]?3|dd)(?=[ ._-]?[0-9]|\b)(?![p+])""") to "AC-3",
            Regex("""(?i)\bdts[ ._-]?hd(?:[ ._-]?ma)?\b""") to "DTS-HD",
            Regex("""(?i)\bdts[: ._-]?x\b""") to "DTS:X",
            Regex("""(?i)\bdts\b(?![ ._-]?(?:hd|x))""") to "DTS",
            Regex("""(?i)\baac\b""") to "AAC", Regex("""(?i)\bflac\b""") to "FLAC",
            Regex("""(?i)\bopus\b""") to "Opus", Regex("""(?i)\bl?pcm\b""") to "PCM",
            Regex("""(?i)\bmp3\b""") to "MP3",
        )
        private fun audioCodecs(text: String): List<String> = AUDIO_CODECS
            .filter { it.first.containsMatchIn(text) }.map { it.second }

        private val CONTAINER = Regex("""(?i)\.(mkv|mp4|m4v|mov|avi|webm|m2ts|ts|vob)(?:\s|$)""")
        private val BIT_DEPTH = Regex("""(?i)\b(8|10|12|16)[ ._-]?bit\b""")
        private val SOURCE_SERVER = Regex("""(?im)🛰️?\s*Source:[^\n]*?•\s*([^\n]+)""")

        private val SUBTITLE_LANGUAGES = Regex("""(?im)(?:subtitles?|subs?)\s*[:=]\s*([^\n]+)|(?:💬|📜)\s*([^\n]+)""")
        private fun subtitleLanguages(text: String): List<String> =
            SUBTITLE_LANGUAGES.findAll(text).flatMap { match ->
                    OriginalAudioLanguage.reported("Audio: " + match.groupValues.drop(1).firstOrNull(String::isNotBlank).orEmpty()).asSequence()
                }.distinct().toList()

        fun sourceDate(value: String?): Long? {
            val raw = value?.trim()?.takeIf(String::isNotBlank) ?: return null
            raw.toLongOrNull()?.takeIf { it > 100_000_000L }?.let { number ->
                return if (number < 100_000_000_000L) number.takeIf { it <= Long.MAX_VALUE / 1000 }?.times(1000) else number
            }
            return runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
                ?: runCatching { LocalDate.parse(raw).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
        }

        private fun number(token: String): Double? = token
            .replace(',', '.')
            .filter { it.isDigit() || it == '.' }
            .toDoubleOrNull()
    }
}

/**
 * Original-language admission for add-on Sources and Play. The iOS language normalization and
 * technical-tail rule are retained; its inferred-original and never-empty fallbacks are not.
 * These are add-on reports, not a probe of the media's actual audio tracks.
 */
object OriginalAudioLanguage {
    private val unknownLabels = setOf("", "und", "unknown", "root", "none", "null", "n/a", "mul", "mis", "zxx", "xx")
    private val aliases: Map<String, String> = buildMap {
        Locale.getISOLanguages().sorted().forEach { code ->
            val locale = Locale.forLanguageTag(code)
            // BCP-47 keeps modern codes even on Android versions whose getLanguage returns iw/in/ji.
            val canonical = locale.toLanguageTag().substringBefore('-')
            if (canonical in unknownLabels || canonical.length !in 2..3) return@forEach
            put(code, canonical)
            locale.getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT)
                .takeIf(String::isNotBlank)?.let { put(it, canonical) }
            runCatching { locale.getISO3Language() }.getOrNull()
                ?.takeIf { it.isNotBlank() && it !in unknownLabels }?.let { put(it, canonical) }
        }
        putAll(mapOf("fre" to "fr", "ger" to "de", "dut" to "nl", "chi" to "zh",
            "cze" to "cs", "gre" to "el", "rum" to "ro", "slo" to "sk",
            "telg" to "te", "tlg" to "te", "jap" to "ja", "pun" to "pa",
            "mandarin" to "zh", "latino" to "es", "castellano" to "es",
            "cn" to "cn", "yue" to "cn", "cantonese" to "cn"))
    }
    private val token = Regex("\\b(?:" + aliases.keys.sortedByDescending(String::length)
        .joinToString("|") { Regex.escape(it) } + ")\\b", RegexOption.IGNORE_CASE)
    private val technicalAnchor = Regex("(?<![0-9])(?:(?:19|20)[0-9]{2}|[0-9]{3,4}[pi]|4k|2160|1080|720)(?![0-9])", RegexOption.IGNORE_CASE)
    private val audioMarker = Regex("🎧|🔊|🔈|\\b(?:audio(?:\\s+languages?)?|languages?)\\s*[:=]", RegexOption.IGNORE_CASE)
    private val subtitleMarker = Regex("💬|📜|\\b(?:subtitles?|subs?|hardsubs?|softsubs?|korsub|vostfr|sdh|cc)\\b", RegexOption.IGNORE_CASE)
    private val negative = Regex("\\b(?:not|without|missing|unavailable|none|unknown)\\b|\\bno\\s+[a-z]{3,}\\b", RegexOption.IGNORE_CASE)
    private val ambiguousCodes = setOf("am", "an", "as", "be", "he", "in", "is", "it", "my", "no", "or", "so", "to")
    private val regionalTag = Regex("\\b([A-Za-z]{2,3})[-_]([A-Z]{2}|[A-Z][a-z]{3})\\b")
    private val bracket = Regex("\\[([^]\\n]+)]")
    private val indexerNoise = Regex("1?tamilmv|tamilblasters|tamilrockers|tamilyogi|tamilgun|tamildbox|tamilprint|tamil mv|teluguflix|telugupalaka|movierulz|ibomma|jiorockers|kuttymovies|isaimini|moviesda|masstamilan", RegexOption.IGNORE_CASE)

    fun normalize(value: String?): String? {
        val label = value?.trim()?.lowercase(Locale.ROOT) ?: return null
        if (label in unknownLabels) return null
        return aliases[label] ?: aliases[label.substringBefore('-').substringBefore('_')]
    }

    fun displayName(value: String): String = normalize(value)?.let {
        if (it == "cn") "Cantonese" else Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH)
            .takeIf(String::isNotBlank) ?: it.uppercase(Locale.ROOT)
    } ?: value

    /** Audio lanes or release technical tails only: a title such as The English Patient is not audio evidence. */
    fun reported(text: String): List<String> {
        val found = linkedSetOf<String>()
        text.lineSequence().forEach { raw ->
            val line = regionalTag.replace(indexerNoise.replace(raw, " "), "$1")
            val audio = audioMarker.find(line)
            val anchor = technicalAnchor.find(line)
            val candidates = when {
                audio != null -> listOf(line.substring(audio.range.last + 1))
                anchor != null -> listOf(line.substring(anchor.range.first))
                else -> bracket.findAll(line).map { it.groupValues[1] }.toList() + listOf(line)
            }
            candidates.forEach { candidate ->
                // A language immediately before a subtitle marker belongs to subtitles, not audio.
                var lane = candidate
                subtitleMarker.find(lane)?.let { marker ->
                    lane = lane.substring(0, marker.range.first)
                    val last = token.findAll(lane).lastOrNull()
                    if (last != null && Regex("[\\s._\\-\\]]*").matches(lane.substring(last.range.last + 1))) {
                        lane = lane.substring(0, last.range.first)
                    }
                }
                val matches = token.findAll(lane).toList()
                val labelledOrTail = audio != null || anchor != null
                val onlyLanguages = token.replace(lane, "").all { !it.isLetterOrDigit() }
                if (!negative.containsMatchIn(lane) && (labelledOrTail || onlyLanguages)) {
                    matches.forEach { match ->
                        val ordinaryWord = match.value in ambiguousCodes && lane.trim() != match.value
                        // DV and SD normally name video formats in release filenames. Only an
                        // explicit audio-language list can use them as Divehi/Sindhi short codes.
                        val mediaCode = match.value.lowercase(Locale.ROOT) in setOf("dv", "sd") &&
                            (audio == null || !onlyLanguages)
                        if (!ordinaryWord && !mediaCode) normalize(match.value)?.let(found::add)
                    }
                }
            }
        }
        return found.map(::displayName).sorted()
    }

    fun matches(row: StreamRow, originalLanguage: String?): Boolean {
        val original = normalize(originalLanguage) ?: return false
        return row.facts.reportedAudioLanguages.any { normalize(it) == original }
    }

    fun sources(rows: List<StreamRow>, originalLanguage: String?): List<StreamRow> =
        rows.filter { !it.theatricalRelease && matches(it, originalLanguage) }
}
