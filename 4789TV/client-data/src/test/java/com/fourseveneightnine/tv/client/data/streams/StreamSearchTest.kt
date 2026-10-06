package com.fourseveneightnine.tv.client.data.streams

import com.fourseveneightnine.tv.client.data.FakeAnswer
import com.fourseveneightnine.tv.client.data.FakeHttp
import com.fourseveneightnine.tv.client.data.addons.AddonRegistry
import com.fourseveneightnine.tv.client.data.addons.InMemoryAddonHealthStore
import com.fourseveneightnine.tv.client.data.addons.StremioClient
import com.fourseveneightnine.tv.client.data.fixture
import com.fourseveneightnine.tv.client.data.settings.SettingsDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamSearchTest {

    /** Three stream add-ons: fast, slow, and one that falls over. */
    private val document = SettingsDocument.parse(
        """
        {
          "sources": [
            { "name": "Fast", "url": "https://fast.example.com/manifest.json", "enabled": true },
            { "name": "Slow", "url": "https://slow.example.com/manifest.json", "enabled": true },
            { "name": "Broken", "url": "https://broken.example.com/manifest.json", "enabled": true }
          ]
        }
        """.trimIndent(),
    )

    private fun streamBody(name: String, hash: String) = """
        {"streams":[{"name":"$name","title":"Movie.2024.1080p.WEB-DL.x265-$name",
        "infoHash":"$hash"}]}
    """.trimIndent()

    private suspend fun search(
        slowDelayMillis: Long,
        setDeadlineMillis: Long = 12_000L,
        perAddonTimeoutMillis: Long = 8_000L,
    ): List<StreamSearchState> = withContext(Dispatchers.IO) {
        val http = FakeHttp { request ->
            when {
                // Cinemeta is always in the registry, and its real manifest declares no `stream`
                // resource, so it must not count as a fourth stream add-on here either.
                request.url.host.contains("cinemeta") -> FakeAnswer(body = fixture("manifest-cinemeta.json"))
                request.url.toString().contains("/manifest.json") ->
                    FakeAnswer(body = fixture("manifest-aiostreams.json"))
                request.url.host.startsWith("fast") ->
                    FakeAnswer(body = streamBody("Fast", "a".repeat(40)))
                request.url.host.startsWith("slow") ->
                    FakeAnswer(body = streamBody("Slow", "b".repeat(40)), delayMillis = slowDelayMillis)
                else -> FakeAnswer(code = 500, body = "down")
            }
        }
        val client = StremioClient(http.client)
        val registry = AddonRegistry(document, client, InMemoryAddonHealthStore())
        registry.refresh()
        StreamSearch(
            registry = registry,
            client = client,
            perAddonTimeoutMillis = perAddonTimeoutMillis,
            setDeadlineMillis = setDeadlineMillis,
        ).search("movie", "tt1375666").toList()
    }

    @Test
    fun `the fast add-on publishes before the slow one finishes`() = runTest {
        val states = search(slowDelayMillis = 400)
        // The first state is the "searching" one, before anything answered.
        assertEquals(0, states.first().rows.size)
        assertEquals(3, states.first().attempted)
        assertEquals(3, states.first().pending)

        val firstWithRows = states.first { it.rows.isNotEmpty() }
        assertEquals(1, firstWithRows.rows.size)
        assertEquals("Fast", firstWithRows.rows.single().title)
        assertTrue(firstWithRows.pending > 0)
    }

    @Test
    fun `a row that has been published never moves`() = runTest {
        val states = search(slowDelayMillis = 300).filter { it.rows.isNotEmpty() }
        val firstIDs = states.first().rows.map(StreamRow::id)
        states.forEach { state ->
            assertEquals(
                "a published row moved",
                firstIDs,
                state.rows.take(firstIDs.size).map(StreamRow::id),
            )
        }
    }

    @Test
    fun `the last state is done and counts the add-on that failed`() = runTest {
        val last = search(slowDelayMillis = 200).last()
        assertTrue(last.done)
        assertEquals(0, last.pending)
        assertEquals(3, last.attempted)
        assertEquals(1, last.failed)
        assertEquals(2, last.rows.size)
    }

    @Test
    fun `the set deadline ends the wait and keeps what answered`() = runTest {
        val last = search(slowDelayMillis = 3_000, setDeadlineMillis = 500).last()
        assertTrue(last.done)
        assertEquals(1, last.rows.size)
        assertEquals("Fast", last.rows.single().title)
        // The straggler and the broken one both count as failures in the chip.
        assertEquals(2, last.failed)
    }

    @Test
    fun `a per-add-on timeout is counted as a failure, not as an empty answer`() = runTest {
        val last = search(slowDelayMillis = 1_500, perAddonTimeoutMillis = 300).last()
        assertTrue(last.done)
        assertEquals(1, last.rows.size)
        assertEquals(2, last.failed)
    }

    @Test
    fun `no stream add-ons gives one done state`() = runTest {
        val http = FakeHttp { FakeAnswer(body = """{"id":"x","name":"X","resources":["meta"]}""") }
        val client = StremioClient(http.client)
        val registry = AddonRegistry(SettingsDocument.empty, client, InMemoryAddonHealthStore())
        registry.refresh()
        val states = StreamSearch(registry, client).search("movie", "tt1375666").toList()
        assertEquals(1, states.size)
        assertTrue(states.single().done)
        assertTrue(states.single().rows.isEmpty())
    }

    @Test
    fun `two add-ons offering the same hash is one row`() = runTest {
        val states = withContext(Dispatchers.IO) {
            val http = FakeHttp { request ->
                when {
                    request.url.host.contains("cinemeta") ->
                        FakeAnswer(body = fixture("manifest-cinemeta.json"))
                    request.url.toString().contains("/manifest.json") ->
                        FakeAnswer(body = fixture("manifest-aiostreams.json"))
                    else -> FakeAnswer(body = streamBody("Same", "f".repeat(40)))
                }
            }
            val client = StremioClient(http.client)
            val registry = AddonRegistry(document, client, InMemoryAddonHealthStore())
            registry.refresh()
            StreamSearch(registry, client).search("movie", "tt1375666").toList()
        }
        assertEquals(1, states.last().rows.size)
    }
}
