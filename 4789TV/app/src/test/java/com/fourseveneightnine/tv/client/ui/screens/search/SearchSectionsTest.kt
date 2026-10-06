package com.fourseveneightnine.tv.client.ui.screens.search

import com.fourseveneightnine.contract.DiscoverItem
import com.fourseveneightnine.tv.client.data.catalog.CatalogItem
import com.fourseveneightnine.tv.client.data.catalog.Shelf
import com.fourseveneightnine.tv.client.data.catalog.ShelfKind
import com.fourseveneightnine.tv.client.search.SystemSearchCommand
import com.fourseveneightnine.tv.client.search.SystemSearchIntentParser
import com.fourseveneightnine.tv.client.search.SystemSearchPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import com.fourseveneightnine.tv.client.iptv.*

class SearchSectionsTest {

    @Test fun `cached results appear while add-on response is still waiting`() = runBlocking {
        val remote = CompletableDeferred<List<List<DiscoverItem>>>()
        val first = CompletableDeferred<List<SearchSection>>()
        val published = mutableListOf<List<SearchSection>>()
        val local = listOf(SearchResult("ch1", "channel", "Telugu channel", null, null))
        val job = async {
            SearchSections.progressiveResults(local, remote = { remote.await() }) {
                published += it
                first.complete(it)
            }
        }
        assertEquals("ch1", withTimeout(1_000) { first.await() }.first().items.first().id)
        assertFalse(job.isCompleted)
        remote.complete(listOf(listOf(item("film", "movie"))))
        assertTrue(job.await())
        assertEquals(setOf("channel", "movie"), published.last().map { it.type }.toSet())
    }

    @Test fun `IPTV search includes separate types and categories without disabled or locked content`() {
        val active = IptvSource("s", "Active", IptvSourceKind.M3U)
        val disabled = active.copy(id = "off", enabled = false)
        val channel = IptvChannel("s:1", "s", "Telugu One", "Telugu sports", streamRef = "one")
        val rows = (1..30).map { IptvVod("s:m$it", "s", "movie", "Telugu movie $it", "Telugu movies") } +
            IptvVod("s:series", "s", "series", "Telugu series", "Telugu drama") +
            IptvVod("off:vod", "off", "movie", "Telugu disabled", "Telugu movies") +
            IptvVod("s:locked", "s", "movie", "Telugu locked", "Adult")
        val state = IptvState(accounts = IptvAccounts(sources = listOf(active, disabled), lockedGroups = setOf("Adult")),
            catalog = IptvCatalog(channels = listOf(channel, channel.copy(id = "s:hidden", group = "Adult")),
                vod = rows, programs = listOf(IptvProgram(channel.id, "Telugu cricket", 100, 200))))
        val result = SearchSections.iptv(state, "Telugu", now = 150)
        assertTrue(result.any { it.type == "iptv-channel-category" && it.id == "Telugu sports" })
        assertTrue(result.any { it.type == "iptv-movie-category" && it.id == "Telugu movies" })
        assertTrue(result.any { it.type == "iptv-series-category" && it.id == "Telugu drama" })
        assertTrue(result.any { it.type == "iptv-program" })
        assertTrue(result.any { it.type == "iptv-series" })
        assertEquals(24, result.count { it.type == "iptv-movie" })
        assertFalse(result.any { it.id == "off:vod" || it.id == "s:locked" || it.id == "s:hidden" })
    }

    private fun item(id: String, type: String, title: String = id, year: Int? = null) =
        DiscoverItem(id = id, type = type, title = title, year = year)

    @Test
    fun `a query under two characters is not sent`() {
        assertFalse(SearchSections.shouldSearch(""))
        assertFalse(SearchSections.shouldSearch("d"))
        assertFalse(SearchSections.shouldSearch("  d  "))
        assertTrue(SearchSections.shouldSearch("du"))
        assertTrue(SearchSections.shouldSearch("dune"))
    }

    @Test
    fun `sections come back in the spec order and empty ones are removed`() {
        val sections = SearchSections.merge(
            listOf(
                listOf(item("tt2", "series"), item("tt3", "anime")),
                listOf(item("tt1", "movie")),
            ),
        )
        assertEquals(listOf("Movies", "Series", "Anime"), sections.map { it.title })
    }

    @Test
    fun `a type with no results never makes a row`() {
        val sections = SearchSections.merge(listOf(listOf(item("tt1", "movie"))))
        assertEquals(1, sections.size)
        assertEquals("Movies", sections.first().title)
    }

    @Test
    fun `the first add-on to answer keeps the card and a duplicate does not reorder`() {
        val sections = SearchSections.merge(
            listOf(
                listOf(item("tt1", "movie", title = "Dune"), item("tt9", "movie", title = "Dune Two")),
                listOf(item("tt1", "movie", title = "DUNE (remux)")),
            ),
        )
        val movies = sections.single().items
        assertEquals(listOf("Dune", "Dune Two"), movies.map { it.title })
    }

    @Test
    fun `tv and show land on the series row`() {
        val sections = SearchSections.merge(
            listOf(listOf(item("a", "tv"), item("b", "show"), item("c", "series"))),
        )
        assertEquals(1, sections.size)
        assertEquals("Series", sections.single().title)
        assertEquals(3, sections.single().items.size)
    }

    @Test
    fun `live channels are surfaced ahead of catalog titles`() {
        val sections = SearchSections.merge(
            listOf(listOf(item("a", "channel"), item("b", "movie"))),
        )
        assertEquals(listOf("Channels", "Movies"), sections.map { it.title })
    }

    @Test
    fun `an item with no id or no title is dropped`() {
        val sections = SearchSections.merge(
            listOf(listOf(item("", "movie"), item("tt1", "movie", title = ""), item("tt2", "movie"))),
        )
        assertEquals(1, SearchSections.resultCount(sections))
    }

    @Test
    fun `the count line reads singular, plural, or nothing`() {
        assertNull(SearchSections.countLabel(emptyList()))
        assertEquals("1 result", SearchSections.countLabel(SearchSections.merge(listOf(listOf(item("a", "movie"))))))
        assertEquals(
            "2 results",
            SearchSections.countLabel(SearchSections.merge(listOf(listOf(item("a", "movie"), item("b", "series"))))),
        )
    }

    @Test
    fun `recent searches are newest first, deduplicated and capped at five`() {
        var recents = emptyList<String>()
        listOf("dune", "alien", "heat", "dune", "jaws", "up", "coco").forEach {
            recents = SearchSections.recordRecent(recents, it)
        }
        assertEquals(listOf("coco", "up", "jaws", "dune", "heat"), recents)
    }

    @Test
    fun `a recent entry matches case-insensitively`() {
        val recents = SearchSections.recordRecent(listOf("Dune"), "dune")
        assertEquals(listOf("dune"), recents)
    }

    @Test
    fun `a query too short is never recorded`() {
        assertEquals(listOf("dune"), SearchSections.recordRecent(listOf("dune"), "d"))
    }

    @Test
    fun `a row never grows past the cap`() {
        val page = (1..40).map { item("tt$it", "movie") }
        val sections = SearchSections.merge(listOf(page))
        assertEquals(SearchSections.MAX_PER_SECTION, sections.single().items.size)
    }

    @Test
    fun `local search scans titles beyond the Home shelf cap and deduplicates repeated items`() = runTest {
        val shelves = (0 until 120).map { shelfIndex ->
            Shelf(
                id = "list-$shelfIndex",
                title = "List $shelfIndex",
                kind = ShelfKind.LETTERBOXD,
                items = listOf(
                    CatalogItem(
                        canonicalId = if (shelfIndex == 119) "tmdb:movie:999" else "tmdb:movie:$shelfIndex",
                        mediaType = "movie",
                        title = if (shelfIndex == 119) "Deep Shelf Treasure" else "Other $shelfIndex",
                    ),
                    CatalogItem("tmdb:movie:1", "movie", "Repeated title"),
                ),
                generation = "g1",
                generatedAtMillis = 1L,
            )
        }

        val results = SearchSections.local(shelves, "deep shelf")

        assertEquals(listOf("Deep Shelf Treasure"), results.map(SearchResult::title))
    }

    @Test
    fun `local cached results fill add-on sections without duplicating the add-on winner`() {
        val local = listOf(
            SearchResult("tt1", "movie", "Cached Dune", 2021, null),
            SearchResult("tt2", "series", "Cached Series", 2024, null),
        )
        val sections = SearchSections.merge(
            pages = listOf(listOf(item("tt1", "movie", "Add-on Dune"))),
            local = local,
        )

        assertEquals(listOf("Add-on Dune"), sections.first().items.map(SearchResult::title))
        assertEquals("Cached Series", sections.last().items.single().title)
    }

    @Test
    fun `a prefix of the new search is dropped`() {
        // F83: the box showed "DUNE", "DUN" and "DU" as three separate recent searches.
        var recents = SearchSections.recordRecent(emptyList(), "du")
        recents = SearchSections.recordRecent(recents, "dun")
        recents = SearchSections.recordRecent(recents, "dune")
        assertEquals(listOf("dune"), recents)
    }

    @Test
    fun `a prefix is dropped whatever its case`() {
        val recents = SearchSections.recordRecent(listOf("DU", "heat"), "Dune")
        assertEquals(listOf("Dune", "heat"), recents)
    }

    @Test
    fun `an unrelated search keeps the older one`() {
        val recents = SearchSections.recordRecent(listOf("dune"), "heat")
        assertEquals(listOf("heat", "dune"), recents)
    }

    @Test
    fun `system queries are trimmed bounded and reject non-search intents`() {
        assertEquals(
            SystemSearchCommand.Query("Dune"),
            SystemSearchIntentParser.parse(
                action = "android.intent.action.SEARCH",
                query = "  Dune  ",
                dataUri = null,
                expectedAuthority = "com.example.search",
            ),
        )
        assertEquals(
            SystemSearchPolicy.MAX_QUERY_CHARACTERS,
            (SystemSearchIntentParser.parse(
                action = "android.intent.action.SEARCH",
                query = "x".repeat(SystemSearchPolicy.MAX_QUERY_CHARACTERS + 50),
                dataUri = null,
                expectedAuthority = "com.example.search",
            ) as SystemSearchCommand.Query).value.length,
        )
        assertNull(SystemSearchIntentParser.parse("android.intent.action.SEARCH", "x", null, "com.example.search"))
        assertNull(SystemSearchIntentParser.parse("android.intent.action.SEND", "Dune", null, "com.example.search"))
    }

    @Test
    fun `opaque detail intents round trip and reject authority or path changes`() {
        val authority = "com.example.search"
        val uri = requireNotNull(SystemSearchIntentParser.detailUri(authority, "movie", "tmdb:movie:438631"))
        assertEquals(
            SystemSearchCommand.Detail("movie", "tmdb:movie:438631"),
            SystemSearchIntentParser.parse("android.intent.action.VIEW", null, uri, authority),
        )
        assertNull(SystemSearchIntentParser.parse("android.intent.action.VIEW", null, uri, "other.search"))
        assertNull(SystemSearchIntentParser.parse("android.intent.action.VIEW", null, "$uri?extra=1", authority))
        assertNull(SystemSearchIntentParser.parse("android.intent.action.VIEW", null, "$uri/extra", authority))
        assertNull(SystemSearchIntentParser.detailUri(authority, "episode", "tt1"))
        assertNull(SystemSearchIntentParser.detailUri(authority, "movie", "../../private"))
    }

    @Test
    fun `system suggestions are relevant deduplicated and capped`() {
        val items = (1..30).map { index ->
            CatalogItem(
                canonicalId = "tmdb:movie:$index",
                mediaType = "movie",
                title = "Dune $index",
                year = 2000 + index,
            )
        } + CatalogItem(
            canonicalId = "tmdb:movie:1",
            mediaType = "movie",
            title = "Duplicate Dune",
        ) + CatalogItem(
            canonicalId = "tmdb:series:99",
            mediaType = "series",
            title = "Unrelated",
        )

        val results = SystemSearchPolicy.suggestions(items, "dune", limit = 100)

        assertEquals(SystemSearchPolicy.MAX_RESULTS, results.size)
        assertEquals(results.size, results.map { "${it.mediaType}/${it.canonicalId}" }.distinct().size)
        assertTrue(results.all { it.title.contains("Dune") })
    }
}
