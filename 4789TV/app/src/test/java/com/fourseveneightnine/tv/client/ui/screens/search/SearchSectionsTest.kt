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
import kotlinx.coroutines.flow.flow
import com.fourseveneightnine.tv.client.iptv.*

class SearchSectionsTest {

    @Test fun `keyboard suggestions rank only matching movie and series names`() {
        val sections = listOf(SearchSection("iptv", "IPTV", listOf(
            SearchResult("channel", "channel", "Seven News", null, null),
            SearchResult("program", "iptv-program", "Seven News at Six", null, null),
            SearchResult("category", "iptv-movie-category", "Seven movies", null, null),
            SearchResult("vod", "iptv-movie", "Seven", 1995, null),
        )), SearchSection("movie", "Movies", listOf(
            SearchResult("seven", "movie", "Seven", 1995, null),
            SearchResult("magnificent", "movie", "The Magnificent Seven", 1960, null),
            SearchResult("edge", "movie", "The Edge of Seventeen", 2016, null),
            SearchResult("guess", "movie", "Unrelated provider guess", 2020, null),
        )), SearchSection("series", "Series", listOf(
            SearchResult("seconds", "series", "Seven Seconds", 2018, null),
        )))
        val suggestions = SearchSections.titleSuggestions(sections, "sev")
        assertEquals(listOf("Seven", "Seven Seconds", "The Magnificent Seven", "The Edge of Seventeen"),
            suggestions.map { it.title })
        assertTrue(SearchSections.titleSuggestions(sections, "s").isEmpty())
        assertTrue(SearchSections.titleSuggestions(sections, "absent").isEmpty())
        assertEquals(listOf("Seven"), SearchSections.titleSuggestions(sections, "Seven 1995").map { it.title })
    }

    @Test fun `keyboard suggestions are capped and keep remakes distinct`() {
        val movies = (1..12).map { SearchResult("m$it", "movie", "Seven", 1990 + it, null) }
        val result = SearchSections.titleSuggestions(listOf(SearchSection("movie", "Movies", movies)), "seven")
        assertEquals(6, result.size)
        assertEquals((2002 downTo 1997).toList(), result.map { it.year })
    }

    @Test fun `title relevance ranks chart entries and newer namesakes first`() = runTest {
        val old = CatalogItem("old", "movie", "Heart of a Beast", year = 1930)
        val current = CatalogItem("current", "movie", "Heart of the Beast", year = 2026)
        val different = CatalogItem("different", "series", "Heart of the Beast Returns", year = 2027)
        val shelves = listOf(
            Shelf("library", "Library", ShelfKind.LIBRARY, listOf(old, current, different), "saved", 0L),
            Shelf("movies", "Trending", ShelfKind.TMDB_MOVIES, listOf(current), "public", 0L),
        )
        val query = "Heart of a Beast"
        val index = SearchSections.indexCatalog(shelves)
        val local = SearchSections.searchCatalog(index, query)
        val remote = listOf(listOf(item("old", "movie", old.title, old.year),
            item("current", "movie", current.title)))
        val sections = SearchSections.merge(remote, local, query)
        val rails = SearchSections.resultRails(sections, query)

        assertEquals(listOf("current", "old"), rails.first().items.map { it.id })
        assertEquals(2026, rails.first().items.first().year)
        assertEquals("current", SearchSections.titleSuggestions(rails, query).first().id)
        assertEquals("old", SearchSections.resultRails(sections, "$query 1930").single().items.single().id)
        assertTrue(SearchSections.resultRails(sections, "$query 2000").isEmpty())
        val withoutChart = rails.first().copy(items = rails.first().items.reversed().map { it.copy(chartPosition = null) })
        assertEquals("current", SearchSections.titleSuggestions(listOf(withoutChart), query).first().id)
        val withOlderChart = withoutChart.copy(items = withoutChart.items.map {
            if (it.id == "old") it.copy(chartPosition = 0) else it
        })
        assertEquals("old", SearchSections.titleSuggestions(listOf(withOlderChart), query).first().id)
    }

    @Test fun `an exact series leads a loosely matching movie`() {
        val sections = listOf(
            SearchSection("movie", "Movies", listOf(SearchResult("film", "movie", "Silo Stories", 2026, null, 0))),
            SearchSection("series", "Series", listOf(SearchResult("show", "series", "Silo", 2023, null))),
        )
        assertEquals("series", SearchSections.resultRails(sections, "Silo").first().type)
        assertEquals("show", SearchSections.titleSuggestions(sections, "Silo").first().id)
    }

    @Test fun `typing keeps matching catalog titles through a typo and an empty provider response`() = runTest {
        val current = SearchResult("current", "movie", "Heart of the Beast", 2026, null, 0)
        val prior = listOf(
            SearchSection("movie", "Movies", listOf(current, SearchResult("other", "movie", "Heart of Stone", 2023, null))),
            SearchSection("iptv-movie", "IPTV", listOf(current.copy(type = "iptv-movie"))),
        )
        val query = "heart of a baes"
        val retained = SearchSections.refineTitles(prior, query)
        assertEquals(listOf(current), retained.single().items)
        assertEquals(listOf(current), SearchSections.titleSuggestions(retained, query))
        assertTrue(SearchSections.refineTitles(prior, "squid game").isEmpty())
        val next = mutableListOf<List<SearchSection>>()
        assertTrue(SearchSections.progressiveResults(query, local = { emptyList() }, remote = flow {
            emit(IndexedValue(0, emptyList()))
        }, retained = retained.flatMap { it.items }) { next += it })
        assertEquals(listOf(current), next.single().single().items)
    }

    @Test fun `a better title takes the lead without waiting for the last add-on`() = runTest {
        val betterTitle = CompletableDeferred<Unit>()
        val lastAddon = CompletableDeferred<Unit>()
        val leaders = mutableListOf<String>()
        val job = async {
            SearchSections.progressiveResults("Heart of a Beast", local = {
                listOf(SearchResult("old", "movie", "Heart of a Beast", 1930, null))
            }, remote = flow {
                betterTitle.await()
                emit(IndexedValue(0, listOf(item("current", "movie", "Heart of the Beast", 2026))))
                lastAddon.await()
            }) { leaders += it.single().items.first().id }
        }
        testScheduler.runCurrent()
        assertEquals(listOf("old"), leaders)
        betterTitle.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(listOf("old", "current"), leaders)
        assertFalse(job.isCompleted)
        lastAddon.complete(Unit)
        assertTrue(job.await())
        assertEquals(listOf("old", "current"), leaders)
    }

    @Test fun `search keeps the series and skips the Other copy even when it arrives first`() = runTest {
        val title = "The Witch Farm"
        val query = "the witch f"
        val poster = "https://example.test/the-witch-farm.jpg"
        val series = item("tt41492493", "series", title, 2026).copy(posterURL = poster)
        val genericCopy = series.copy(id = "addon:witch-farm", type = "other")
        val shelf = Shelf("other", "Cached list", ShelfKind.LETTERBOXD,
            listOf(CatalogItem(genericCopy.id, "Other", title, year = 2026, posterUrl = poster)), "cached", 0L)
        val indexed = SearchSections.indexCatalog(listOf(shelf))
        assertTrue(SearchSections.searchCatalog(indexed, query).isEmpty())

        val releaseSeries = CompletableDeferred<Unit>()
        val published = mutableListOf<List<SearchSection>>()
        val job = async {
            SearchSections.progressiveResults(query, local = {
                listOf(SearchResult(genericCopy.id, "Other", title, 2026, poster))
            }, remote = flow {
                emit(IndexedValue(0, listOf(genericCopy)))
                releaseSeries.await()
                emit(IndexedValue(1, listOf(series)))
            }) { published += it }
        }
        testScheduler.runCurrent()
        assertTrue(published.isEmpty())
        releaseSeries.complete(Unit)
        assertTrue(job.await())

        val rail = SearchSections.resultRails(published.single(), query).single()
        assertEquals("Series", rail.title)
        assertEquals(SearchResult(series.id, "series", title, 2026, poster), rail.items.single())
    }

    @Test fun `movies and series rank in their own rails before IPTV`() {
        val sections = listOf(
            SearchSection("channel", "Channels", listOf(SearchResult("news", "channel", "Dune", null, null))),
            SearchSection("movie", "Movies", listOf(
                SearchResult("remake", "movie", "Dune", 2021, null),
                SearchResult("old", "movie", "Dune", 1984, null),
            )),
            SearchSection("series", "Series", listOf(
                SearchResult("prophecy-no-art", "series", "Dune Prophecy", 2024, null),
                SearchResult("prophecy", "series", "Dune: Prophecy", 2024, "https://example.test/prophecy.jpg"),
            )),
            SearchSection("iptv-movie", "IPTV movies", listOf(
                SearchResult("duplicate", "iptv-movie", "Dune", 2021, null),
            )),
        )
        val rails = SearchSections.resultRails(sections, "Dune")
        assertEquals(listOf("Movies", "Series", "IPTV"), rails.map { it.title })
        assertEquals(listOf("remake", "old"), rails[0].items.map { it.id })
        assertEquals(listOf("prophecy"), rails[1].items.map { it.id })
        assertEquals(listOf("news", "duplicate"), rails[2].items.map { it.id })
        assertEquals(
            SearchSections.catalogDisplayKey(sections[2].items.first()),
            SearchSections.catalogDisplayKey(rails[1].items.single()),
        )
        assertEquals("remake", SearchSections.resultRails(sections, "Dune 2021").first().items.first().id)
        assertEquals("IPTV", SearchSections.resultRails(sections.take(1), "Dune").single().title)
        assertEquals("Series", SearchSections.resultRails(listOf(sections[2]), "Dune").single().title)
    }

    @Test fun `strong title matches replace typo and provider guesses`() {
        val section = SearchSection("movie", "Movies", listOf(
            SearchResult("new-year", "movie", "Happy New Year", 2014, null),
            SearchResult("news", "movie", "News of the World", 2020, null),
            SearchResult("loose", "movie", "Faits divers", 1983, null),
            SearchResult("dune", "movie", "Dune", 2021, null),
        ))
        fun ids(query: String) = SearchSections.resultRails(listOf(section), query)
            .single().items.map { it.id }
        assertEquals(listOf("news"), ids("News"))
        assertEquals(listOf("dune"), ids("Duen"))
        assertEquals(listOf("new-year", "news", "loose", "dune"), ids("Tom Hanks"))
    }

    @Test fun `provider guesses wait until a title match or the search completes`() = runTest {
        val releaseSlowSource = CompletableDeferred<Unit>()
        val published = mutableListOf<List<SearchSection>>()
        val job = async {
            SearchSections.progressiveResults("News", local = { emptyList() }, remote = flow {
                emit(IndexedValue(0, listOf(item("new-year", "movie", "Happy New Year"))))
                releaseSlowSource.await()
                emit(IndexedValue(1, listOf(item("news", "movie", "News of the World"))))
            }) { published += it }
        }
        testScheduler.runCurrent()
        assertTrue(published.isEmpty())
        releaseSlowSource.complete(Unit)
        assertTrue(job.await())
        assertEquals("news", published.first().single().items.first().id)
    }

    @Test fun `trending alternates public movie and series charts`() {
        val movies = Shelf("movies", "Movies", ShelfKind.TMDB_MOVIES,
            listOf(CatalogItem("m1", "movie", "First film"), CatalogItem("m2", "movie", "Second film")),
            "public", 0L)
        val series = Shelf("series", "Series", ShelfKind.TMDB_SERIES,
            listOf(CatalogItem("s1", "series", "First show")), "public", 0L)
        val privateList = Shelf("list", "Private list", ShelfKind.LETTERBOXD,
            listOf(CatalogItem("m3", "movie", "Private title")), "private", 0L)
        val row = SearchSections.trending(listOf(privateList, movies, series)).single()
        assertEquals("Trending", row.title)
        assertEquals(listOf("m1", "s1", "m2"), row.items.map { it.id })
        assertTrue(SearchSections.trending(listOf(privateList)).isEmpty())
    }

    @Test fun `movies series and IPTV publish independently of slower sources`() = runTest {
        val releaseIptv = CompletableDeferred<Unit>()
        val releaseSeries = CompletableDeferred<Unit>()
        val releaseRemote = CompletableDeferred<Unit>()
        val published = mutableListOf<List<SearchSection>>()
        val job = async {
            SearchSections.progressiveResults(
                query = "News",
                local = { listOf(SearchResult("film", "movie", "News of the World", 2020, null)) },
                iptv = {
                    releaseIptv.await()
                    listOf(SearchResult("live", "channel", "News Live", null, null))
                },
                remote = flow {
                    releaseSeries.await()
                    emit(IndexedValue(0, listOf(item("show", "series", "News Live"))))
                    releaseRemote.await()
                },
                publish = { published += it },
            )
        }
        testScheduler.runCurrent()
        assertEquals(listOf("movie"), published.single().map { it.type })
        releaseSeries.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(setOf("movie", "series"), published.last().map { it.type }.toSet())
        releaseIptv.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(setOf("movie", "series", "channel"), published.last().map { it.type }.toSet())
        assertFalse(job.isCompleted)
        releaseRemote.complete(Unit)
        assertTrue(job.await())
    }

    @Test fun `cached results appear while add-on response is still waiting`() = runBlocking {
        val remote = CompletableDeferred<List<List<DiscoverItem>>>()
        val first = CompletableDeferred<List<SearchSection>>()
        val published = mutableListOf<List<SearchSection>>()
        val local = listOf(SearchResult("ch1", "channel", "Telugu channel", null, null))
        val job = async {
            SearchSections.progressiveResults("Telugu", local = { local }, remote = flow {
                remote.await().forEachIndexed { index, page -> emit(IndexedValue(index, page)) }
            }) {
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

    @Test fun `IPTV search includes separate types and categories without disabled or locked content`() = runTest {
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

        val results = SearchSections.searchCatalog(SearchSections.indexCatalog(shelves), "deep shelf")

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
    fun `distinct submitted queries can share a prefix`() {
        val recents = SearchSections.recordRecent(listOf("Dune", "Heat"), "Dune 2021")
        assertEquals(listOf("Dune 2021", "Dune", "Heat"), recents)
        assertEquals(listOf("Dune", "Heat"), SearchSections.cleanRecents(listOf("  Dune  ", "dune", "", "Heat")))
    }

    @Test
    fun `the first remote page does not wait for slower catalogs or the local scan`() = runTest {
        val rest = CompletableDeferred<Unit>()
        val published = mutableListOf<List<SearchSection>>()
        val first = CompletableDeferred<Unit>()
        val job = async {
            SearchSections.progressiveResults("Dune", local = { rest.await(); emptyList() }, remote = flow {
                emit(IndexedValue(1, listOf(item("late", "movie", "Dune Drifter"))))
                rest.await()
                emit(IndexedValue(0, listOf(item("exact", "movie", "Dune"))))
            }) {
                published += it
                first.complete(Unit)
            }
        }
        first.await()
        assertEquals("late", published.single().single().items.single().id)
        assertFalse(job.isCompleted)
        rest.complete(Unit)
        assertTrue(job.await())
        assertEquals(listOf("exact", "late"), published.last().single().items.map { it.id })
    }

    @Test
    fun `cached titles handle accents punctuation years and a single typo`() = runTest {
        val shelf = Shelf(
            id = "search", title = "Search", kind = ShelfKind.LETTERBOXD, generation = "g1",
            generatedAtMillis = 1L,
            items = listOf(
                CatalogItem("old", "movie", "Dune", year = 1984),
                CatalogItem("new", "movie", "Dune", year = 2021),
                CatalogItem("alien", "movie", "Alien: Romulus", year = 2024),
                CatalogItem("amelie", "movie", "Amélie"),
                CatalogItem("tamil", "movie", "காதல்"),
            ),
        )
        val index = SearchSections.indexCatalog(listOf(shelf))
        suspend fun ids(query: String) = SearchSections.searchCatalog(index, query).map { it.id }
        assertEquals(listOf("new"), ids("Dune (2021)"))
        assertEquals(listOf("alien"), ids("romulus alien"))
        assertEquals(listOf("alien"), ids("alien-romulus"))
        assertEquals(listOf("amelie"), ids("amelie"))
        assertEquals(listOf("amelie"), ids("amleie"))
        assertEquals(listOf("tamil"), ids("காதல்"))
        assertEquals(emptyList<String>(), ids("amxxie"))
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
