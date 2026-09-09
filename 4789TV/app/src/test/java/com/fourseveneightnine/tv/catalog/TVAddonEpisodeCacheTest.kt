package com.fourseveneightnine.tv.catalog

import com.fourseveneightnine.tv.settings.StoredTVSettings
import com.fourseveneightnine.tv.settings.StoredTVSettingsState
import com.fourseveneightnine.tv.settings.TVSettingsPersistence
import com.fourseveneightnine.tv.settings.ValidatedTVSettings
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The episode list is the same for everyone and changes when a season airs, not between two
 * presses of the remote. Before this cache existed, backing out of a series and re-entering it
 * re-asked every configured addon from scratch — the wait the viewer felt on the second visit.
 */
class TVAddonEpisodeCacheTest {

    private val settingsJson = """
        {
          "format": "4789-settings",
          "sources": [
            {"name": "Addon A", "url": "https://a.example.com/manifest.json", "enabled": true}
          ]
        }
    """.trimIndent()

    private class FakeSettings(private val raw: String) : TVSettingsPersistence {
        override fun load(): StoredTVSettingsState = StoredTVSettingsState.Available(
            StoredTVSettings(rawJson = raw, revision = 1, savedAtMillis = 0L, syncKeyBase64 = null),
        )
        override fun save(settings: ValidatedTVSettings, revision: Long, syncKey: ByteArray?) = true
        override fun clearSync() = true
        override fun clearAll() = true
    }

    /** Counts every HTTP call and answers meta requests with two episodes. */
    private class CountingHTTP(private val delayMillis: Long = 0) : TVAddonHTTPClient {
        val metaCalls = AtomicInteger(0)
        val streamCalls = AtomicInteger(0)

        override suspend fun get(url: String, maximumBytes: Int): ByteArray {
            if (delayMillis > 0) delay(delayMillis)
            return if (url.contains("/meta/")) {
                metaCalls.incrementAndGet()
                """
                {"meta":{"videos":[
                  {"season":1,"episode":1,"name":"One"},
                  {"season":1,"episode":2,"name":"Two"}
                ]}}
                """.trimIndent().toByteArray()
            } else {
                streamCalls.incrementAndGet()
                """{"streams":[]}""".toByteArray()
            }
        }
    }

    private fun series(id: String = "tt1234567") = TVTamilMVCatalogItem(
        id = id,
        mediaType = "series",
        title = "A Show",
        imdbID = id,
        posterURL = null,
    )

    @Test
    fun `a repeat visit does not re-ask the addons for episodes`() = runTest {
        val http = CountingHTTP()
        val repository = TVAddonSourceRepository(FakeSettings(settingsJson), http)

        val first = repository.find(series())
        assertEquals(2, first.episodes.size)
        val afterFirst = http.metaCalls.get()
        assertTrue("expected the first visit to fetch", afterFirst > 0)

        val second = repository.find(series())
        assertEquals(2, second.episodes.size)
        assertEquals("second visit must be served from cache", afterFirst, http.metaCalls.get())
    }

    @Test
    fun `two callers asking together issue one fan-out`() = runTest {
        val http = CountingHTTP(delayMillis = 50)
        val repository = TVAddonSourceRepository(FakeSettings(settingsJson), http)

        val both = coroutineScope {
            val a = async { repository.find(series()) }
            val b = async { repository.find(series()) }
            listOf(a.await(), b.await())
        }

        both.forEach { assertEquals(2, it.episodes.size) }
        assertEquals("concurrent callers must collapse to one meta fetch", 1, http.metaCalls.get())
    }

    /** A different title must not be served another title's episodes. */
    @Test
    fun `the cache is keyed per title`() = runTest {
        val http = CountingHTTP()
        val repository = TVAddonSourceRepository(FakeSettings(settingsJson), http)

        repository.find(series("tt1111111"))
        val afterFirst = http.metaCalls.get()
        repository.find(series("tt2222222"))

        assertTrue("a different title must fetch its own episodes", http.metaCalls.get() > afterFirst)
    }
}
