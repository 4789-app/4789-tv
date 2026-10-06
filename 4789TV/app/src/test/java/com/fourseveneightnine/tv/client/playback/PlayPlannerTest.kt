package com.fourseveneightnine.tv.client.playback

import com.fourseveneightnine.tv.client.data.streams.CachedHint
import com.fourseveneightnine.tv.client.data.streams.PlaybackRules
import com.fourseveneightnine.tv.client.data.streams.StreamFacts
import com.fourseveneightnine.tv.client.data.streams.StreamRow
import com.fourseveneightnine.tv.client.data.streams.StreamSearchState
import com.fourseveneightnine.tv.client.data.streams.debrid.DebridOutcome
import com.fourseveneightnine.tv.client.data.streams.debrid.Resolved
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayPlannerTest {

    private val autoPlayRules = PlaybackRules(
        enabled = true,
        autoPlay = true,
        autoPlayMinSources = 2,
        readyOnly = false,
    )

    @Test
    fun `commits to the best row once enough sources are in hand`() = runTest {
        val planner = PlayPlanner(autoPlayRules, setOf("hevc"))

        val choice = planner.choose(flowOf(StreamSearchState(rows = rows(3), done = true)))

        assertNotNull(choice)
        assertTrue(choice!!.eligible)
    }

    @Test
    fun `does not commit while the rules say show the list`() = runTest {
        val planner = PlayPlanner(PlaybackRules.DEFAULT, emptySet())

        val choice = planner.choose(flowOf(StreamSearchState(rows = rows(3), done = true)))

        assertNull(choice)
    }

    @Test
    fun `waits for more sources before the search finishes`() = runTest {
        val planner = PlayPlanner(autoPlayRules.copy(autoPlayMinSources = 5), setOf("hevc"))

        val choice = planner.choose(flowOf(StreamSearchState(rows = rows(3), pending = 2)))

        assertNull(choice)
    }

    @Test
    fun `plays the best available source when search finishes below the threshold`() = runTest {
        val planner = PlayPlanner(autoPlayRules.copy(autoPlayMinSources = 5), setOf("hevc"))

        val choice = planner.choose(flowOf(StreamSearchState(rows = rows(2), done = true)))

        assertNotNull(choice)
        assertEquals("row-1", choice!!.row.id)
    }

    @Test
    fun `commits on the first state that is decidable, without waiting for done`() = runTest {
        val planner = PlayPlanner(autoPlayRules, setOf("hevc"))
        var statesCollected = 0

        val choice = planner.choose(
            flow {
                statesCollected++
                emit(StreamSearchState(rows = rows(1), pending = 2))
                statesCollected++
                emit(StreamSearchState(rows = rows(3), pending = 1))
                statesCollected++
                emit(StreamSearchState(rows = rows(4), done = true))
            },
        )

        assertNotNull(choice)
        // The third state is never asked for: the search is cancelled as soon as one row wins.
        assertEquals(2, statesCollected)
    }

    @Test
    fun `an empty search commits to nothing`() = runTest {
        val planner = PlayPlanner(autoPlayRules, emptySet())

        assertNull(planner.choose(flowOf(StreamSearchState(done = true))))
    }

    @Test
    fun `every debrid outcome has the sentence API-A gives it`() {
        assertNull(PlayPlanner.failure(DebridOutcome.Success(Resolved(url = "https://example/one"))))
        assertEquals(
            "Add a debrid key in Settings.",
            PlayPlanner.failure(DebridOutcome.MissingApiKey)?.message,
        )
        assertEquals(
            "That copy is not on your debrid account yet.",
            PlayPlanner.failure(DebridOutcome.NotCached)?.message,
        )
        assertEquals(
            "That source did not answer. Try another.",
            PlayPlanner.failure(DebridOutcome.Stale)?.message,
        )
        assertEquals(
            "That source did not answer. Try another.",
            PlayPlanner.failure(DebridOutcome.Error)?.message,
        )
    }

    private fun rows(count: Int): List<StreamRow> = (1..count).map { index ->
        StreamRow(
            id = "row-$index",
            addonName = "AIOStreams",
            title = "1080p",
            releaseName = "The.Bear.S02E04.1080p.WEB-DL-FLUX",
            url = "https://example/$index",
            quality = "1080p",
            sizeBytes = 4_000_000_000L,
            codecs = listOf("HEVC"),
            cachedHint = CachedHint(cached = true, service = "Real-Debrid"),
            facts = StreamFacts(quality = "1080p", sizeBytes = 4_000_000_000L, codecs = listOf("HEVC")),
        )
    }
}
