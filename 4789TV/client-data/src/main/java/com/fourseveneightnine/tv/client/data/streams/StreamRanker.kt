package com.fourseveneightnine.tv.client.data.streams

import java.util.Locale
import kotlin.math.max

/** Why a row sits where it sits. The Streams right pane prints these verbatim (§10.4). */
data class RankReason(
    val text: String,
    /** A check in `cached` for a reason the row won, a caution in `warning` for a demotion. */
    val caution: Boolean = false,
)

/** One ranked row, with the score and the reasons behind it. */
data class RankedRow(
    val row: StreamRow,
    val score: Double,
    val reasons: List<RankReason>,
    /** False when the rules excluded this row and it only survived the never-empty guard. */
    val eligible: Boolean = true,
) {
    val cautions: List<RankReason> get() = reasons.filter(RankReason::caution)
}

/**
 * Orders the sources list and says why.
 *
 * Two engines meet here. `phone/StreamRankingPolicy.kt` knows that the best pick is not the
 * biggest file — start time tracks bitrate and size, so quality that starts fast beats quality
 * that stalls. iOS `PlaybackRules` knows what the owner actually asked for. The rules decide who
 * is eligible; the score decides the order inside that set.
 *
 * **Two guarantees, both pinned by tests.**
 *
 *  1. A non-empty input never gives an empty output. If the rules would empty the field, every row
 *     comes back marked `eligible = false` and sorted, because a Play button that silently does
 *     nothing is worse than a row the owner would rather not have seen.
 *  2. The order is deterministic. Two equal rows break the tie on their id, so the same field
 *     ranks the same way twice and focus does not jump between two runs.
 *
 * **The Dolby Vision rule.** A profile 5 release carries no fallback layer: a box without a DV
 * decoder plays it as a green, washed-out picture, not as plain HDR. So a DV row is demoted hard
 * when `hardwareVideoCodecs` does not hold `dolbyvision`, and the pane says why. It is demoted,
 * never removed — on a title where DV is the only copy, a bad picture beats no picture.
 */
object StreamRanker {

    /** The bitrate a TV box starts fastest at. Past this, start cost grows faster than quality. */
    private const val BITRATE_SWEET_SPOT_MBPS = 18.0

    /** Big enough that no amount of quality can outrank a picture the box cannot decode. */
    private const val DOLBY_VISION_PENALTY = 120.0

    /** Ready to play beats everything else a rule did not already decide. */
    private const val CACHED_BONUS = 60.0

    fun rank(
        rows: List<StreamRow>,
        rules: PlaybackRules = PlaybackRules.DEFAULT,
        hardwareVideoCodecs: Set<String> = emptySet(),
    ): List<RankedRow> {
        if (rows.isEmpty()) return emptyList()
        val codecs = hardwareVideoCodecs.map { it.lowercase(Locale.ROOT) }.toSet()
        // The never-empty guard: every row still ranks and every row is still listed. What the
        // rules rejected is MARKED rather than promoted, so the screen can say "nothing matched
        // your rules" over a list that still plays, and auto-pick can refuse to commit.
        val eligibleRows = (if (rules.enabled) rows.filter(rules::allows) else rows).toSet()

        return rows
            .map { row ->
                val reasons = mutableListOf<RankReason>()
                val score = score(row, rules, codecs, reasons)
                RankedRow(
                    row = row,
                    score = score,
                    reasons = reasons.take(MAX_REASONS),
                    eligible = row in eligibleRows,
                )
            }
            .sortedWith(comparator(rules.openingSort()))
    }

    /** The facts pane's label/value pairs, in the fixed order §10.4 gives. Missing pairs drop. */
    fun facts(row: StreamRow): List<Pair<String, String>> = buildList {
        row.facts.resolutionText?.let { add("Resolution" to it) }
        val video = listOfNotNull(
            row.facts.codec,
            when (row.hdr) {
                HdrFormat.DOLBY_VISION -> row.facts.dolbyVisionProfile
                    ?.let { "Dolby Vision profile $it" } ?: "Dolby Vision"
                HdrFormat.HDR10 -> "HDR10"
                HdrFormat.NONE -> null
            },
        )
        if (video.isNotEmpty()) add("Video" to video.joinToString(" "))
        row.facts.audioLine?.let { add("Audio" to it) }
        row.sizeGB?.let { add("Size" to String.format(Locale.US, "%.2f GB", it)) }
        row.seeders?.let { add("Seeders" to it.toString()) }
        add("Add-on" to row.addonName)
        row.url?.let { url ->
            runCatching { java.net.URI(url).host }.getOrNull()?.let { add("Host" to it) }
        }
    }

    private fun score(
        row: StreamRow,
        rules: PlaybackRules,
        hardwareVideoCodecs: Set<String>,
        reasons: MutableList<RankReason>,
    ): Double {
        val facts = row.facts
        var score = 0.0

        // Cached first. The design calls it out as the one fact a viewer can act on (§10.11.2).
        if (row.cachedHint.cached) {
            score += CACHED_BONUS
            reasons += RankReason(
                row.cachedHint.service?.let { "Cached on $it" } ?: "Cached",
            )
        }

        val bitrate = facts.bitrateMbps
        if (bitrate != null && bitrate > 0) {
            score += if (bitrate <= BITRATE_SWEET_SPOT_MBPS) {
                45 * (bitrate / BITRATE_SWEET_SPOT_MBPS)
            } else {
                45 * max(0.25, BITRATE_SWEET_SPOT_MBPS / bitrate)
            }
        }

        score += when (facts.quality) {
            "4K" -> 24.0
            "1440p" -> 23.0
            "1080p" -> 22.0
            "720p" -> 12.0
            "480p" -> 4.0
            else -> 0.0
        }
        if (rules.enabled && rules.minQualityRank > 0 && facts.qualityRank >= rules.minQualityRank) {
            reasons += RankReason("Matches your ${StreamFacts.qualityLabel(rules.minQualityRank)} rule")
        }

        if (facts.isHEVC || facts.codecs.contains("AV1")) score += 6

        when (row.hdr) {
            HdrFormat.DOLBY_VISION -> {
                if (hardwareVideoCodecs.contains("dolbyvision")) {
                    score += 5
                } else {
                    score -= DOLBY_VISION_PENALTY
                    val profile = facts.dolbyVisionProfile
                    reasons += RankReason(
                        text = if (profile != null) {
                            "Dolby Vision profile $profile, and this box has no DV decoder"
                        } else {
                            "Dolby Vision, and this box has no DV decoder"
                        },
                        caution = true,
                    )
                }
            }
            HdrFormat.HDR10 -> score += 4
            HdrFormat.NONE -> Unit
        }

        val sizeGB = row.sizeGB
        val cap = rules.maxSizeGB
        if (sizeGB != null) {
            if (sizeGB > 25) score -= 12
            if (sizeGB > 45) score -= 12
            if (rules.enabled && cap != null) {
                if (sizeGB > cap) {
                    reasons += RankReason("Bigger than your ${PlaybackRules.sizeText(cap)} cap", caution = true)
                } else {
                    reasons += RankReason(
                        "${String.format(Locale.US, "%.2f GB", sizeGB)}, under your " +
                            "${PlaybackRules.sizeText(cap)} cap",
                    )
                }
            }
        }

        val preferred = rules.preferredAudioLanguages.map { it.lowercase(Locale.ROOT) }
        if (preferred.isNotEmpty()) {
            val hit = row.audioLanguages.firstOrNull { it.lowercase(Locale.ROOT) in preferred }
            if (hit != null) {
                score += 15
                reasons += RankReason("$hit audio")
            }
        }

        if (rules.enabled && rules.normalizedKeywords.isNotEmpty()) {
            PlaybackRules.firstExcludedKeyword(row.releaseText(), rules.normalizedKeywords)?.let { word ->
                score -= 200
                reasons += RankReason(
                    "Name holds a word you avoid: ${word.uppercase(Locale.ROOT)}",
                    caution = true,
                )
            }
        }

        if (rules.enabled && rules.minSeeders > 0 && !row.cachedHint.cached) {
            val seeders = row.seeders
            if (seeders != null && seeders < rules.minSeeders) {
                score -= 30
                reasons += RankReason(
                    "$seeders seeders, under your ${rules.minSeeders} floor",
                    caution = true,
                )
            }
        }

        // A row that still has to be acquired is a slower start, whatever else it has going for it.
        if (row.needsResolve && !row.cachedHint.cached) score -= 20

        return score
    }

    private fun comparator(sort: StreamSort): Comparator<RankedRow> = when (sort) {
        StreamSort.BEST ->
            compareByDescending<RankedRow> { it.eligible }
                .thenByDescending { it.score }
        StreamSort.READY ->
            compareByDescending<RankedRow> { it.eligible }
                .thenByDescending { it.row.cachedHint.cached }
                .thenByDescending { it.score }
        StreamSort.SIZE ->
            compareByDescending<RankedRow> { it.eligible }
                .thenBy { it.row.sizeGB ?: Double.MAX_VALUE }
        StreamSort.BITRATE ->
            compareByDescending<RankedRow> { it.eligible }
                .thenByDescending { it.row.facts.bitrateMbps ?: -1.0 }
        StreamSort.NEWEST ->
            compareByDescending<RankedRow> { it.eligible }
                .thenByDescending { it.row.seeders ?: -1 }
    }.thenBy { it.row.id }

    private const val MAX_REASONS = 6
}
