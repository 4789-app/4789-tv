package com.fourseveneightnine.phone

import com.fourseveneightnine.contract.StreamEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamRankingPolicyTest {
    private val leanFourK = StreamEntry(
        url = "https://a/lean-4k",
        name = "2160p WEB-DL",
        description = "🎞️ HEVC 🌗 HDR\n📁 10.0 GB · 18 Mbps\n🌱 400 · 2d",
    )
    private val bloatedRemux = StreamEntry(
        url = "https://a/remux-4k",
        name = "2160p REMUX",
        description = "🎞️ HEVC\n📁 68.0 GB · 82 Mbps\n🌱 1200 · 5d",
    )
    private val smallSevenTwenty = StreamEntry(
        url = "https://a/720",
        name = "720p WEB",
        description = "📁 1.4 GB · 4 Mbps\n🌱 30 · 1w",
    )
    private val unknownMirror = StreamEntry(url = "https://a/mirror", name = "Mirror one")
    private val all = listOf(bloatedRemux, smallSevenTwenty, unknownMirror, leanFourK)

    @Test
    fun theLeanFourKOutscoresTheBloatedRemuxOfTheSameResolution() {
        val lean = StreamRankingPolicy.startScore(StreamFacts.from(leanFourK))
        val bloated = StreamRankingPolicy.startScore(StreamFacts.from(bloatedRemux))

        assertTrue("$lean must beat $bloated", lean > bloated)
    }

    @Test
    fun recommendedOrderIsDeterministicAndStarsExactlyOneRow() {
        val ranked = StreamRankingPolicy.rank(all)

        assertEquals(
            listOf("https://a/lean-4k", "https://a/720", "https://a/remux-4k", "https://a/mirror"),
            ranked.map { it.stream.url },
        )
        assertEquals(1, ranked.count { it.isRecommended })
        assertEquals("https://a/lean-4k", ranked.first { it.isRecommended }.stream.url)
        assertEquals(ranked, StreamRankingPolicy.rank(all.reversed()))
    }

    @Test
    fun theSameRowKeepsTheStarWhenTheSortOrderChanges() {
        val bySize = StreamRankingPolicy.rank(all, StreamSortOrder.SmallestFile)

        assertEquals("https://a/lean-4k", bySize.first { it.isRecommended }.stream.url)
    }

    @Test
    fun everySortOrderPutsUnknownFactsLastRatherThanInventingThem() {
        val bySize = StreamRankingPolicy.rank(all, StreamSortOrder.SmallestFile).map { it.stream.url }
        val bySeeders = StreamRankingPolicy.rank(all, StreamSortOrder.MostSeeders).map { it.stream.url }
        val byQuality = StreamRankingPolicy.rank(all, StreamSortOrder.HighestQuality).map { it.stream.url }

        assertEquals(listOf("https://a/720", "https://a/lean-4k", "https://a/remux-4k", "https://a/mirror"), bySize)
        assertEquals(listOf("https://a/remux-4k", "https://a/lean-4k", "https://a/720", "https://a/mirror"), bySeeders)
        assertEquals(listOf("https://a/lean-4k", "https://a/remux-4k", "https://a/720", "https://a/mirror"), byQuality)
    }

    @Test
    fun aQualityLimitHidesHigherResolutionsButKeepsUnstatedOnes() {
        val limited = StreamRankingPolicy.rank(
            all,
            filter = StreamFilter(ceiling = StreamQualityCeiling.UpTo1080p),
        ).map { it.stream.url }

        assertEquals(listOf("https://a/720", "https://a/mirror"), limited)
    }

    @Test
    fun unknownIsNeverTreatedAsFailure() {
        val needsSize = StreamRankingPolicy.rank(all, filter = StreamFilter(requireKnownSize = true))
        val needsSeeders = StreamRankingPolicy.rank(all, filter = StreamFilter(minimumSeeders = 100))

        assertEquals(3, needsSize.size)
        assertTrue(needsSize.none { it.stream.url == "https://a/mirror" })
        assertEquals(
            listOf("https://a/lean-4k", "https://a/remux-4k", "https://a/mirror"),
            needsSeeders.map { it.stream.url },
        )
    }

    @Test
    fun theSeederFloorChoicesRiseAndTheFirstOneHidesNothing() {
        assertEquals(0, StreamSeederFloor.Any.minimum)
        assertEquals(
            StreamSeederFloor.entries.map { it.minimum }.sorted(),
            StreamSeederFloor.entries.map { it.minimum },
        )
        val unfiltered = StreamRankingPolicy.rank(all)
        val anySwarm = StreamRankingPolicy.rank(
            all,
            filter = StreamFilter(minimumSeeders = StreamSeederFloor.Any.minimum),
        )

        assertEquals(unfiltered, anySwarm)
    }

    @Test
    fun aSeederFloorHidesASmallStatedSwarmAndKeepsASilentOne() {
        val floored = StreamRankingPolicy.rank(
            all,
            filter = StreamFilter(minimumSeeders = StreamSeederFloor.AtLeast50.minimum),
        ).map { it.stream.url }

        assertEquals(listOf("https://a/lean-4k", "https://a/remux-4k", "https://a/mirror"), floored)
    }

    @Test
    fun theThreeFiltersCombineAndTheStarMovesToTheBestRowStillShowing() {
        val combined = StreamRankingPolicy.rank(
            all,
            filter = StreamFilter(
                ceiling = StreamQualityCeiling.UpTo1080p,
                requireKnownSize = true,
                minimumSeeders = StreamSeederFloor.AtLeast10.minimum,
            ),
        )

        assertEquals(listOf("https://a/720"), combined.map { it.stream.url })
        assertEquals(1, combined.count { it.isRecommended })
        assertEquals("https://a/720", combined.first { it.isRecommended }.stream.url)
    }

    @Test
    fun theStarStaysOnOneRowUnderEveryFilterAndSortOrderPair() {
        val filter = StreamFilter(
            ceiling = StreamQualityCeiling.Any,
            requireKnownSize = true,
            minimumSeeders = StreamSeederFloor.AtLeast10.minimum,
        )
        val starred = StreamSortOrder.entries.map { order ->
            val ranked = StreamRankingPolicy.rank(all, order, filter)
            assertEquals(1, ranked.count { it.isRecommended })
            ranked.first { it.isRecommended }.stream.url
        }

        assertEquals(List(StreamSortOrder.entries.size) { "https://a/lean-4k" }, starred)
    }

    @Test
    fun aFilterThatRemovesEverythingStarsNothing() {
        val none = StreamRankingPolicy.rank(
            all,
            filter = StreamFilter(
                ceiling = StreamQualityCeiling.UpTo720p,
                requireKnownSize = true,
                minimumSeeders = StreamSeederFloor.AtLeast200.minimum,
            ),
        )

        assertEquals(emptyList<RankedStream>(), none)
    }

    @Test
    fun aRowOnlyPrintsChipsForFactsThatWereStated() {
        assertEquals(
            listOf("4K", "HEVC", "HDR", "400 seeders", "2d old"),
            StreamRankingPolicy.chips(StreamFacts.from(leanFourK)),
        )
        assertEquals(emptyList<String>(), StreamRankingPolicy.chips(StreamFacts.from(unknownMirror)))
    }

    @Test
    fun theSizeLineOnlyShowsTheHalvesThatExist() {
        assertEquals("10.0 GB · 18.0 Mbps", StreamRankingPolicy.sizeLine(StreamFacts.from(leanFourK)))
        assertEquals(
            "4.0 GB",
            StreamRankingPolicy.sizeLine(
                StreamFacts.from(StreamEntry(url = "https://a/x", description = "📁 4 GB")),
            ),
        )
        assertNull(StreamRankingPolicy.sizeLine(StreamFacts.from(unknownMirror)))
    }

    @Test
    fun anEmptySourceListRanksToNothingAndStarsNothing() {
        assertEquals(emptyList<RankedStream>(), StreamRankingPolicy.rank(emptyList()))
    }
}
