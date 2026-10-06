package com.fourseveneightnine.tv.client.home

import com.fourseveneightnine.tv.client.data.catalog.CatalogItem
import com.fourseveneightnine.tv.client.search.SystemSearchCommand
import com.fourseveneightnine.tv.client.search.SystemSearchIntentParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvHomeChannelPolicyTest {
    private val authority = "com.example.search"

    @Test
    fun `programs are stable deduplicated capped and use validated detail intents`() {
        val items = (1..50).map { index ->
            CatalogItem(
                canonicalId = "tmdb:movie:$index",
                mediaType = "movie",
                title = "Movie $index",
                posterUrl = "https://image.tmdb.org/$index.jpg",
            )
        } + CatalogItem("tmdb:movie:1", "movie", "Duplicate")

        val programs = TvHomeChannelPolicy.programs(items, authority)

        assertEquals(TvHomeChannelPolicy.MAX_PROGRAMS, programs.size)
        assertEquals(programs.size, programs.map(TvHomeProgram::providerId).distinct().size)
        programs.forEach { program ->
            assertEquals(
                SystemSearchCommand.Detail(program.item.mediaType, program.item.canonicalId),
                SystemSearchIntentParser.parse(
                    action = "android.intent.action.VIEW",
                    query = null,
                    dataUri = program.detailUri,
                    expectedAuthority = authority,
                ),
            )
        }
    }

    @Test
    fun `invalid and suppressed rows never become programs`() {
        val public = CatalogItem("tmdb:series:10", "series", "Public")
        val suppressedId = "4789:series:${public.canonicalId}"
        val rows = listOf(
            public,
            CatalogItem("invalid/source/url", "movie", "Invalid"),
            CatalogItem("tmdb:episode:1", "episode", "Episode"),
            CatalogItem("tmdb:movie:2", "movie", ""),
        )

        assertTrue(TvHomeChannelPolicy.programs(rows, authority, setOf(suppressedId)).isEmpty())
        assertFalse(TvHomeChannelPolicy.CHANNEL_PROVIDER_ID.contains("http"))
    }

    @Test
    fun `stable ids do not change when artwork or display metadata changes`() {
        val before = CatalogItem("tmdb:movie:42", "movie", "Old", posterUrl = "https://image.tmdb.org/a.jpg")
        val after = before.copy(title = "New", posterUrl = "https://image.tmdb.org/b.jpg")

        assertEquals(
            TvHomeChannelPolicy.programs(listOf(before), authority).single().providerId,
            TvHomeChannelPolicy.programs(listOf(after), authority).single().providerId,
        )
    }

    @Test
    fun `removal broadcasts can act only on this apps provider rows`() {
        assertTrue(TvHomeChannelPolicy.ownsProgram("com.example", "com.example", "4789:movie:42"))
        assertFalse(TvHomeChannelPolicy.ownsProgram("attacker", "com.example", "4789:movie:42"))
        assertFalse(TvHomeChannelPolicy.ownsProgram("com.example", "com.example", "attacker:movie:42"))
        assertFalse(TvHomeChannelPolicy.ownsProgram("com.example", "com.example", null))
    }
}
