package com.fourseveneightnine.tv.client.data.streams

import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** The order the Streams list opens in. Mirrors iOS `SourceDisplaySort`. */
enum class StreamSort { BEST, READY, SIZE, BITRATE, NEWEST }

/** What OK on a row does. Mirrors iOS `SourceTapAction`. */
enum class SourceTapAction { PLAY, DOWNLOAD, ASK }

/** Which debrid account is asked first. Preference, not availability. */
enum class DebridService { TORBOX, REAL_DEBRID }

/**
 * The owner's persisted picking policy, ported from
 * `4789iOS/Sources/FourSevenEightNineCore/PlaybackRules.swift`.
 *
 * Every field defaults to today's behaviour, so a box whose phone never wrote these rules behaves
 * exactly as it does now. [enabled] is the master switch: off means the Streams screen opens
 * ready-first and nothing is filtered, whatever the other fields say. One switch back to stock is
 * worth more than nine toggles the owner has to remember setting.
 *
 * This type says WHICH ROWS ARE ELIGIBLE and IN WHAT ORDER. It does not score — `StreamRanker`
 * does — so a rule the list obeys and the auto-pick ignores cannot happen.
 */
data class PlaybackRules(
    val enabled: Boolean = false,
    /** Resolution FLOOR: 5 = 4K, 3 = 1080p, 2 = 720p, 0 = any. A floor, not an equality. */
    val minQualityRank: Int = 0,
    /** Hard ceiling in GB. A row with no stated size is KEPT — see [eligible]. */
    val maxSizeGB: Double? = null,
    /** Only rows that play immediately: a direct URL, or a cached hint from the add-on. */
    val readyOnly: Boolean = true,
    val hdrOnly: Boolean = false,
    val hevcOnly: Boolean = false,
    val atmosOnly: Boolean = false,
    /** Minimum swarm for a row that still has to be acquired. A ready row is exempt. */
    val minSeeders: Int = 0,
    /** Release-text substrings that disqualify a row. Short ones match tokens — see [excluded]. */
    val excludeKeywords: List<String> = emptyList(),
    /** Audio language the owner prefers, e.g. "English". Never a filter; a ranking boost. */
    val preferredAudioLanguages: List<String> = emptyList(),
    val sort: StreamSort = StreamSort.BEST,
    val tapAction: SourceTapAction = SourceTapAction.PLAY,
    val debridPriority: List<DebridService> = listOf(DebridService.TORBOX, DebridService.REAL_DEBRID),
    /** Play the best eligible row instead of showing the list (`TV_DESIGN_SPEC.md` §10.5). */
    val autoPlay: Boolean = false,
    /**
     * How many eligible rows must be in hand before auto-play commits. Guards against the first
     * add-on to answer winning by default. 1 means "play the first eligible thing that lands".
     */
    val autoPlayMinSources: Int = 3,
) {

    /** Lower-cased, trimmed, de-duplicated. Done once, so the row filter does no string work. */
    val normalizedKeywords: List<String> by lazy {
        excludeKeywords.asSequence()
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter(String::isNotEmpty)
            .distinct()
            .take(200)
            .toList()
    }

    /**
     * The rows these rules allow.
     *
     * **Never strands the viewer.** If the rules would empty the field, the ORIGINAL field comes
     * back. A preference is a preference, and "1080p or better" was never a request to refuse to
     * play the only 720p copy that exists.
     */
    fun eligible(rows: List<StreamRow>): List<StreamRow> {
        if (!enabled) return rows
        val kept = rows.filter(::allows)
        return kept.ifEmpty { rows }
    }

    /** Does one row pass every rule? Used by [eligible] and, for the reasons, by `StreamRanker`. */
    fun allows(row: StreamRow): Boolean {
        val ready = row.cachedHint.cached
        if (readyOnly && !ready) return false
        if (minSeeders > 0 && !ready) {
            val seeders = row.seeders
            if (seeders != null && seeders < minSeeders) return false
        }
        if (normalizedKeywords.isNotEmpty() && excluded(row.releaseText(), normalizedKeywords)) return false
        if (minQualityRank > 0 && row.facts.qualityRank < minQualityRank) return false
        if (hdrOnly && row.hdr == HdrFormat.NONE) return false
        if (hevcOnly && !row.facts.isHEVC) return false
        if (atmosOnly && !row.facts.isObjectAudio) return false
        val cap = maxSizeGB
        // A row with no stated size is KEPT: a cap excludes what is KNOWN to be too big, it does
        // not hide everything an indexer failed to measure.
        if (cap != null && (row.sizeGB ?: 0.0) > cap) return false
        return true
    }

    /** The sort the list opens in — ready-first while the rules are off. */
    fun openingSort(): StreamSort = if (enabled) sort else StreamSort.READY

    /** The tap action to honour — plain Play while the rules are off. */
    fun effectiveTapAction(): SourceTapAction = if (enabled) tapAction else SourceTapAction.PLAY

    /**
     * Cache-check order, filtered to the services that are actually configured. A configured
     * service the owner never ordered is appended, so adding a backend later cannot make it
     * silently unreachable.
     */
    fun checkOrder(configured: Set<DebridService>): List<DebridService> {
        val out = mutableListOf<DebridService>()
        debridPriority.forEach { if (it in configured && it !in out) out += it }
        DebridService.entries.forEach { if (it in configured && it !in out) out += it }
        return out
    }

    /** May auto-play commit on a field of this size? */
    fun mayAutoPlay(eligibleCount: Int): Boolean =
        enabled && autoPlay && eligibleCount >= maxOf(1, autoPlayMinSources)

    /** One line for the Settings row, so a rule you forgot is not "the add-ons are broken". */
    fun summary(): String {
        if (!enabled) return "Off"
        val parts = buildList {
            when {
                minQualityRank >= 5 -> add("4K+")
                minQualityRank >= 3 -> add("1080p+")
                minQualityRank >= 2 -> add("720p+")
            }
            if (readyOnly) add("ready only")
            if (hdrOnly) add("HDR")
            if (atmosOnly) add("Atmos")
            if (hevcOnly) add("HEVC")
            maxSizeGB?.let { add("<= ${sizeText(it)}") }
            if (minSeeders > 0) add("$minSeeders+ seeds")
            if (normalizedKeywords.isNotEmpty()) add("${normalizedKeywords.size} blocked")
            if (autoPlay) add("auto-play")
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ") ?: "On"
    }

    companion object {
        val DEFAULT: PlaybackRules = PlaybackRules()

        /**
         * The junk-release family nearly everyone wants gone. A one-tap preset, never a default:
         * silently hiding rows an indexer returned is the owner's decision to make.
         */
        val JUNK_KEYWORDS: List<String> = listOf(
            "cam", "camrip", "hdcam", "ts", "telesync", "hdts", "tc", "telecine", "screener", "hdtc",
        )

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Field-by-field tolerant decode, hand-written on purpose.
         *
         * A synthesised decoder treats every non-optional key as required, so the day a rule is
         * added the next launch would throw on every existing blob and silently reset the owner's
         * whole policy to stock. Reading key by key means a blob keeps what it stated and inherits
         * the default for anything it has never heard of.
         */
        fun from(element: JsonElement?): PlaybackRules {
            val root = when (element) {
                is JsonObject -> element
                is JsonPrimitive -> element.contentOrNull
                    ?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
                else -> null
            } ?: return DEFAULT
            val d = DEFAULT
            return PlaybackRules(
                enabled = root.bool("enabled") ?: d.enabled,
                minQualityRank = root.int("minQualityRank")?.coerceIn(0, 5) ?: d.minQualityRank,
                maxSizeGB = root.double("maxSizeGB")?.takeIf { it > 0 },
                readyOnly = root.bool("readyOnly") ?: d.readyOnly,
                hdrOnly = root.bool("hdrOnly") ?: d.hdrOnly,
                hevcOnly = root.bool("hevcOnly") ?: d.hevcOnly,
                atmosOnly = root.bool("atmosOnly") ?: d.atmosOnly,
                minSeeders = root.int("minSeeders")?.coerceAtLeast(0) ?: d.minSeeders,
                excludeKeywords = root.strings("excludeKeywords"),
                preferredAudioLanguages = root.strings("preferredAudioLanguages") +
                    root.strings("audioLanguages"),
                sort = sort(root.text("sort")) ?: d.sort,
                tapAction = tapAction(root.text("tapAction")) ?: d.tapAction,
                debridPriority = root.strings("debridPriority").mapNotNull(::debridService)
                    .ifEmpty { d.debridPriority },
                autoPlay = root.bool("autoPlay") ?: d.autoPlay,
                autoPlayMinSources = root.int("autoPlayMinSources")?.coerceAtLeast(1)
                    ?: d.autoPlayMinSources,
            )
        }

        /** Decode from a raw JSON string. A corrupt blob gives [DEFAULT], never an exception. */
        fun parse(rawJson: String?): PlaybackRules {
            val text = rawJson?.takeIf(String::isNotBlank) ?: return DEFAULT
            val element = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return DEFAULT
            return from(element)
        }

        /**
         * Does `text` trip any of the (already lower-cased) exclusion keywords?
         *
         * **Short keywords match tokens, not substrings.** The junk preset holds `ts` and `tc`, and
         * a naive `contains` would delete *Ghosts of War*, *Hits* and every `.mkv` whose group
         * happens to spell one. Anything up to three characters therefore has to BE a whole token.
         * Longer keywords keep substring behaviour, which is what makes a group name useful
         * against `Movie.2024.1080p.WEB-DL-SOMEGROUP`.
         */
        fun excluded(text: String, keywords: List<String>): Boolean {
            if (keywords.isEmpty()) return false
            val lower = text.lowercase(Locale.ROOT)
            val short = keywords.filter { it.length <= 3 }
            val long = keywords.filter { it.length > 3 }
            if (long.any(lower::contains)) return true
            if (short.isEmpty()) return false
            val tokens = lower.split(*SEPARATORS).filter(String::isNotEmpty).toSet()
            return short.any(tokens::contains)
        }

        /** The keyword that fired, for the "Name holds a word you avoid: CAM" reason line. */
        fun firstExcludedKeyword(text: String, keywords: List<String>): String? =
            keywords.firstOrNull { excluded(text, listOf(it)) }

        /** "12 GB" / "7.5 GB" — a trailing `.0` on a cap reads like a measurement, not a choice. */
        fun sizeText(gb: Double): String =
            if (gb == Math.rint(gb)) "${gb.toInt()} GB" else String.format(Locale.US, "%.1f GB", gb)

        private val SEPARATORS: Array<String> = (
            " .-_()[]{}+,/\\|:;'\"!?*@#$%^&=~`<>\n\t".map(Char::toString)
            ).toTypedArray()

        private fun sort(value: String?): StreamSort? = when (value?.lowercase(Locale.ROOT)) {
            "best" -> StreamSort.BEST
            "ready" -> StreamSort.READY
            "size" -> StreamSort.SIZE
            "bitrate" -> StreamSort.BITRATE
            "newest" -> StreamSort.NEWEST
            else -> null
        }

        private fun tapAction(value: String?): SourceTapAction? = when (value?.lowercase(Locale.ROOT)) {
            "play" -> SourceTapAction.PLAY
            "download" -> SourceTapAction.DOWNLOAD
            "ask" -> SourceTapAction.ASK
            else -> null
        }

        private fun debridService(value: String): DebridService? =
            when (value.lowercase(Locale.ROOT).replace("-", "").replace("_", "")) {
                "torbox" -> DebridService.TORBOX
                "realdebrid" -> DebridService.REAL_DEBRID
                else -> null
            }

        private fun JsonObject.text(key: String): String? =
            (get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)

        private fun JsonObject.bool(key: String): Boolean? =
            (get(key) as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }

        private fun JsonObject.int(key: String): Int? =
            (get(key) as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toInt() }

        private fun JsonObject.double(key: String): Double? =
            (get(key) as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }

        private fun JsonObject.strings(key: String): List<String> =
            (get(key) as? JsonArray).orEmpty().mapNotNull {
                (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
            }
    }
}

/** Everything a keyword rule is matched against: the release name plus the add-on's own text. */
fun StreamRow.releaseText(): String = listOf(releaseName, title).joinToString(" ")
