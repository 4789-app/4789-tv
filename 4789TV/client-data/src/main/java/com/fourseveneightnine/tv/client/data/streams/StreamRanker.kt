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
    /** Cache evidence cannot outrank a format the box is known not to decode. */
    val decodeCompatible: Boolean = true,
) {
    val cautions: List<RankReason> get() = reasons.filter(RankReason::caution)
}

/**
 * Orders the sources list and says why.
 *
 * Two engines meet here. `phone/StreamRankingPolicy.kt` knows that the best pick is not the
 * biggest file — start time tracks bitrate and size, so quality that starts fast beats quality
 * that stalls. iOS `PlaybackRules` knows what the owner actually asked for. The rules decide who
 * is eligible; cache evidence and resolution lead, then the score breaks ties.
 *
 * **Two guarantees, both pinned by tests.**
 *
 *  1. A non-empty admitted pool stays available. Theater prints are always excluded. If preferences empty the field, admitted rows
 *     comes back marked `eligible = false` and sorted, because a Play button that silently does
 *     nothing is worse than a row the owner would rather not have seen.
 *  2. Equal scores break ties by release, add-on, then row id, so signed-link churn does not
 *     reorder otherwise distinct releases.
 *
 * **The Dolby Vision rule.** A profile 5 release carries no fallback layer: a box without a DV
 * decoder plays it as a green, washed-out picture, not as plain HDR. So a DV row is demoted hard
 * when `hardwareVideoCodecs` does not hold `dolbyvision`, and the pane says why. It is demoted,
 * never removed — on a title where DV is the only copy, a bad picture beats no picture.
 */
object StreamRanker {

    /** Read-only cache proof precedes provider preference; a label never becomes live proof. */
    fun deliveryPriority(row: StreamRow): Int {
        if (!row.cachedDebrid) return if (row.url.isNullOrBlank()) 7 else 6
        val provider = when (row.deliveryService?.lowercase(Locale.ROOT)) {
            "torbox" -> 0
            "real-debrid", "realdebrid", "real debrid" -> 1
            else -> 2
        }
        return (if (row.cachedHint.verified) 0 else 3) + provider
    }

    fun deliveryLabel(row: StreamRow): String = when {
        row.directHttp -> "Direct"
        row.cachedDebrid -> "Cached Debrid"
        else -> "Cache unavailable"
    }

    /** The bitrate a TV box starts fastest at. Past this, start cost grows faster than quality. */
    private const val BITRATE_SWEET_SPOT_MBPS = 18.0

    /** Big enough that no amount of quality can outrank a picture the box cannot decode. */
    private const val DOLBY_VISION_PENALTY = 120.0

    /** Ready to play beats everything else a rule did not already decide. */
    private const val READY_BONUS = 60.0

    fun rank(
        rows: List<StreamRow>,
        rules: PlaybackRules = PlaybackRules.DEFAULT,
        hardwareVideoCodecs: Set<String> = emptySet(),
    ): List<RankedRow> {
        if (rows.isEmpty()) return emptyList()
        val codecs = hardwareVideoCodecs.map { it.lowercase(Locale.ROOT) }.toSet()
        // Rule failures are marked for manual review; theatrical releases are excluded below.
        // Auto-pick can refuse rows that remain listed but do not match its rules.
        val eligibleRows = (if (rules.enabled) rows.filter(rules::allows) else rows).toSet()

        return rows
            .filterNot(StreamRow::theatricalRelease)
            .map { row ->
                val reasons = mutableListOf<RankReason>()
                val score = score(row, rules, codecs, reasons)
                if (row.identityVerified == false) reasons.add(0, RankReason("Name not verified for this title", caution = true))
                RankedRow(
                    row = row,
                    score = score,
                    reasons = reasons.take(MAX_REASONS),
                    eligible = row in eligibleRows && row.identityVerified != false,
                    decodeCompatible = row.hdr != HdrFormat.DOLBY_VISION || "dolbyvision" in codecs,
                )
            }
            .sortedWith(comparator(rules.openingSort()))
    }

    /** All means all in the manual picker. Show a caution for a known original-audio mismatch. */
    fun rankForSourceList(
        rows: List<StreamRow>,
        rules: PlaybackRules,
        hardwareVideoCodecs: Set<String>,
        originalLanguage: String?,
    ): List<RankedRow> = rank(rows, rules, hardwareVideoCodecs).map { ranked ->
        // Autoplay preferences are warnings in the explicit manual picker. Identity stays a gate.
        var manual = ranked.copy(eligible = ranked.row.identityVerified != false)
        if (!ranked.eligible && manual.eligible) manual = manual.copy(reasons = manual.reasons +
            RankReason("Outside autoplay preferences", caution = true))
        if (OriginalAudioLanguage.normalize(originalLanguage) != null &&
            manual.row.facts.reportedAudioLanguages.isNotEmpty() &&
            !OriginalAudioLanguage.matches(manual.row, originalLanguage)) {
            manual = manual.copy(reasons = manual.reasons + RankReason("Different audio language", caution = true))
        }
        manual
    }.sortedWith(comparator(rules.openingSort()))

    /** The facts pane's label/value pairs, in the fixed order §10.4 gives. Missing pairs drop. */
    fun facts(row: StreamRow): List<Pair<String, String>> = buildList {
        val facts = row.facts
        facts.resolutionText?.let { add("Resolution" to it) }
        val video = listOfNotNull(facts.codecs.joinToString(" / ").takeIf(String::isNotBlank),
            when (row.hdr) {
                HdrFormat.DOLBY_VISION -> facts.dolbyVisionProfile?.let { "Dolby Vision profile $it" } ?: "Dolby Vision"
                HdrFormat.HDR10 -> "HDR10"
                HdrFormat.NONE -> null
            })
        if (video.isNotEmpty()) add("Video" to video.joinToString(" · "))
        facts.audioLine?.let { add("Audio" to it) }
        if (facts.audioCodecs.isNotEmpty()) add("Audio codecs" to facts.audioCodecs.joinToString(" / "))
        row.sizeGB?.let { add("Size" to String.format(Locale.US, "%.2f GB", it)) }
        row.audioLanguageNames.takeIf { it.isNotEmpty() }?.let { add("Audio languages" to it.joinToString(", ")) }
        facts.bitrateMbps?.let { add("Bitrate" to String.format(Locale.US, "%.1f Mbps", it)) }
        if (facts.subtitleLanguages.isNotEmpty()) add("Subtitle languages" to facts.subtitleLanguages.joinToString(", "))
        facts.durationSeconds?.let { seconds -> add("Duration" to
            (if (seconds >= 3600) "${seconds / 3600}h " else "") + "${seconds % 3600 / 60}m ${seconds % 60}s") }
        facts.sourceDates.forEach { (label, millis) -> add(label to
            java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())
                .withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(millis))) }
        facts.ageText?.let { add("File age" to it) }
        facts.container?.let { add("Container" to it) }
        facts.bitDepth?.let { add("Bit depth" to "$it-bit") }
        facts.frameRate?.let { add("Frame rate" to String.format(Locale.US, "%.3f fps", it).replace(".000 fps", " fps")) }
        facts.videoHash?.let { add("File fingerprint" to it) }
        facts.folderSizeBytes?.takeIf { it != row.sizeBytes }?.let { add("Pack size" to String.format(Locale.US, "%.2f GB", it / StreamRow.BYTES_PER_GB)) }
        facts.folderName?.let { add("Pack" to it) }
        row.seeders?.let { add("Seeders" to it.toString()) }
        facts.provider?.let { add("Provider" to it) }
        facts.server?.let { add("Server" to it) }
        facts.releaseGroup?.let { add("Release group" to it) }
        row.cachedHint.service?.let { add("Service" to it) }
        row.linkCheckedAtMillis?.let { add("Link check" to "Passed") }
        add("Add-on" to row.addonName)
        row.url?.let { url -> runCatching { java.net.URI(url).host }.getOrNull()?.let { add("Host" to it) } }
    }

    private fun score(
        row: StreamRow,
        rules: PlaybackRules,
        hardwareVideoCodecs: Set<String>,
        reasons: MutableList<RankReason>,
    ): Double {
        val facts = row.facts
        var score = 0.0

        // Direct URLs need no acquisition; cache remains a separate add-on claim.
        if (row.readyHint) {
            score += READY_BONUS
            reasons += RankReason(
                if (row.cachedDebrid) row.deliveryService?.let { "Cached on $it" } ?: "Cached"
                else "Stream URL available",
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

        if (rules.enabled && rules.minSeeders > 0 && !row.readyHint) {
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
        if (row.needsResolve && !row.cachedDebrid) score -= 20

        return score
    }

    private fun comparator(sort: StreamSort): Comparator<RankedRow> = when (sort) {
        StreamSort.BEST ->
            compareByDescending<RankedRow> { it.eligible }
                .thenByDescending { it.decodeCompatible }
                .thenBy { deliveryPriority(it.row) }
                .thenByDescending { it.row.facts.qualityRank }
                .thenByDescending { it.score }
        StreamSort.READY ->
            compareByDescending<RankedRow> { it.eligible }
                .thenByDescending { it.decodeCompatible }
                .thenBy { deliveryPriority(it.row) }
                .thenByDescending { it.row.facts.qualityRank }
                .thenByDescending { it.row.readyHint }
                .thenByDescending { it.score }
        StreamSort.SIZE ->
            compareByDescending<RankedRow> { it.eligible }
                .thenBy { it.row.sizeGB ?: Double.MAX_VALUE }
        StreamSort.BITRATE ->
            compareByDescending<RankedRow> { it.eligible }
                .thenByDescending { it.row.facts.bitrateMbps ?: -1.0 }
        StreamSort.NEWEST ->
            compareByDescending<RankedRow> { it.eligible }
                .thenBy { it.row.facts.ageMinutes ?: Double.POSITIVE_INFINITY }
    }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.row.releaseName }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.row.addonName }
        .thenBy { it.row.id }

    private const val MAX_REASONS = 6
}
