package com.fourseveneightnine.phone

import com.fourseveneightnine.contract.StreamEntry
import java.util.Locale
import kotlin.math.max

/** How the sources list is ordered. The order is always deterministic. */
internal enum class StreamSortOrder(val label: String) {
    Recommended("Recommended"),
    HighestQuality("Highest quality"),
    SmallestFile("Smallest file"),
    MostSeeders("Most seeders"),
}

/** An upper limit on resolution. A source that never stated one is never removed by this. */
internal enum class StreamQualityCeiling(val label: String, val maximumRank: Int) {
    Any("Any quality", Int.MAX_VALUE),
    UpTo1080p("Up to 1080p", 3),
    UpTo720p("Up to 720p", 2),
}

/**
 * A floor on the swarm size a source claims.
 *
 * A source that never stated a seeder count is never removed by this, so silence is not read as a
 * small swarm.
 */
internal enum class StreamSeederFloor(val label: String, val minimum: Int) {
    Any("Any swarm", 0),
    AtLeast10("10+ seeders", 10),
    AtLeast50("50+ seeders", 50),
    AtLeast200("200+ seeders", 200),
}

/**
 * What the viewer chose to hide.
 *
 * Unknown is never treated as failure. A source that stated no seeder count survives a seeder
 * floor, because silence is not evidence of a small swarm.
 */
internal data class StreamFilter(
    val ceiling: StreamQualityCeiling = StreamQualityCeiling.Any,
    val requireKnownSize: Boolean = false,
    val minimumSeeders: Int = 0,
)

/** One playable source with its parsed facts, its score, and whether it is the single pick. */
internal data class RankedStream(
    val stream: StreamEntry,
    val facts: StreamFacts,
    val startScore: Double,
    val isRecommended: Boolean,
)

/**
 * Sorts and labels remote sources. Pure Kotlin: no Compose, no Android, so plain unit tests cover
 * every rule here.
 *
 * This is the iOS `StreamMetric` ranking without the AI leg. Nothing is guessed: a score only ever
 * uses facts the source actually stated, and a missing fact adds nothing rather than a default.
 */
internal object StreamRankingPolicy {
    /** The bitrate a phone starts fastest at. Past this, start cost grows faster than quality. */
    private const val BITRATE_SWEET_SPOT_MBPS = 18.0

    /**
     * Higher is a better default pick. Measured on device: start time tracks file size and bitrate,
     * so the best pick for a phone is not the biggest file. It is the best quality that starts
     * quickly. A source with no stated bitrate gets no bitrate points at all.
     */
    fun startScore(facts: StreamFacts): Double {
        var score = 0.0
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
        if (facts.codec == "HEVC" || facts.codec == "AV1") score += 6
        if (facts.dolbyVision) score += 5 else if (facts.hdr) score += 4
        val sizeGB = facts.sizeGB
        if (sizeGB != null) {
            if (sizeGB > 25) score -= 12
            if (sizeGB > 45) score -= 12
        }
        return score
    }

    /**
     * Parse, filter, then order. Exactly one row carries the recommended star, and it is the same
     * row whichever sort order is showing.
     */
    fun rank(
        streams: List<StreamEntry>,
        order: StreamSortOrder = StreamSortOrder.Recommended,
        filter: StreamFilter = StreamFilter(),
    ): List<RankedStream> {
        val kept = streams
            .map { stream -> stream to StreamFacts.from(stream) }
            .filter { (_, facts) -> keeps(facts, filter) }
        val bestIndex = kept.indices.minWithOrNull(
            compareByDescending<Int> { startScore(kept[it].second) }
                .thenBy { tieKey(kept[it].first) },
        )
        return kept
            .mapIndexed { index, (stream, facts) ->
                RankedStream(
                    stream = stream,
                    facts = facts,
                    startScore = startScore(facts),
                    isRecommended = index == bestIndex,
                )
            }
            .sortedWith(comparator(order))
    }

    /** The chips a row may print. Only facts the source actually stated appear here. */
    fun chips(facts: StreamFacts): List<String> = buildList {
        facts.quality?.let(::add)
        facts.codec?.let(::add)
        if (facts.dolbyVision) add("Dolby Vision") else if (facts.hdr) add("HDR")
        facts.audio?.let(::add)
        facts.provider?.let(::add)
        facts.seeders?.let { count -> add(if (count == 1) "1 seeder" else "$count seeders") }
        facts.ageText?.let { add("$it old") }
    }

    /** "13.1 GB · 33.0 Mbps", or just the half that was stated, or null when neither was. */
    fun sizeLine(facts: StreamFacts): String? {
        val parts = buildList {
            facts.sizeGB?.let { add(String.format(Locale.ROOT, "%.1f GB", it)) }
            facts.bitrateMbps?.let { add(String.format(Locale.ROOT, "%.1f Mbps", it)) }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private fun keeps(facts: StreamFacts, filter: StreamFilter): Boolean {
        if (facts.qualityRank > filter.ceiling.maximumRank) return false
        if (filter.requireKnownSize && facts.sizeGB == null) return false
        val seeders = facts.seeders
        if (seeders != null && seeders < filter.minimumSeeders) return false
        return true
    }

    private fun comparator(order: StreamSortOrder): Comparator<RankedStream> = when (order) {
        StreamSortOrder.Recommended ->
            compareByDescending<RankedStream> { it.startScore }
        StreamSortOrder.HighestQuality ->
            compareByDescending<RankedStream> { it.facts.qualityRank }
                .thenByDescending { it.startScore }
        StreamSortOrder.SmallestFile ->
            compareBy<RankedStream> { it.facts.sizeGB ?: Double.MAX_VALUE }
        StreamSortOrder.MostSeeders ->
            compareByDescending<RankedStream> { it.facts.seeders ?: -1 }
    }.thenBy { tieKey(it.stream) }

    /** A stable key so two equal rows never swap places between two runs. */
    private fun tieKey(stream: StreamEntry): String = listOfNotNull(
        stream.url,
        stream.name,
        stream.title,
        stream.filename,
    ).firstOrNull(String::isNotBlank).orEmpty()
}
