package com.fourseveneightnine.tv.client.ui.screens.discover

import com.fourseveneightnine.tv.catalog.TVLetterboxdClient
import com.fourseveneightnine.tv.catalog.TVLetterboxdIdentity
import com.fourseveneightnine.tv.catalog.TVLetterboxdFilm
import com.fourseveneightnine.tv.catalog.TVLetterboxdFilmDetails
import com.fourseveneightnine.tv.catalog.TVLetterboxdListRefresh
import com.fourseveneightnine.tv.catalog.TVLetterboxdPage
import com.fourseveneightnine.tv.client.data.addons.Addon
import com.fourseveneightnine.tv.client.data.library.CollectionDetail
import com.fourseveneightnine.tv.client.data.library.CollectionItem
import com.fourseveneightnine.tv.client.data.library.CollectionKind
import com.fourseveneightnine.tv.client.data.library.SystemCollections
import com.fourseveneightnine.tv.client.data.addons.AddonCatalog
import com.fourseveneightnine.tv.client.data.addons.AddonManifest
import com.fourseveneightnine.tv.client.data.addons.HttpStatusException
import com.fourseveneightnine.tv.client.data.catalog.CatalogItem
import com.fourseveneightnine.tv.client.data.catalog.Shelf
import com.fourseveneightnine.tv.client.data.catalog.ShelfKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverPlanTest {

    @Test fun `interrupting a Letterboxd bulk refresh keeps completed lists for the retry`() = kotlinx.coroutines.runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("public-list-playback").toFile()
        try {
            val visits = mutableListOf<String>()
            val secondStarted = CompletableDeferred<Unit>()
            val store = TVLetterboxdListRefresh(directory, this, { list, _ ->
                visits += list.slug
                if (list.slug == "second" && secondStarted.complete(Unit)) awaitCancellation()
                TVLetterboxdPage(listOf(TVLetterboxdFilm("film", "Film", null)), false, 1)
            }, runCatalogWork = { work ->
                coroutineScope {
                    val attempt = launch { work() }
                    secondStarted.await()
                    attempt.cancelAndJoin()
                }
                work()
            })
            val shelves = listOf("first", "second").map {
                Shelf("letterboxd:liked:owner/$it", it, ShelfKind.LETTERBOXD, emptyList(), "iphone", 1)
            }
            requireNotNull(store.refresh(shelves, bulk = true)).join()
            assertEquals(listOf("first", "second", "second"), visits)
            assertEquals(2, store.state.value.stamps.size)
            assertFalse(store.state.value.pending)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun card(id: String, year: Int? = null, genres: List<String> = emptyList(), rating: Double? = null,
        runtime: Int? = null) = com.fourseveneightnine.tv.client.ui.screens.home.HomeCard(
        id, "movie", id, year = year, genres = genres, rating = rating, runtimeMinutes = runtime)

    @Test fun `catalog filters combine year decade and multiple included and rejected genres`() {
        val thriller = card("thriller", 1999, listOf("Action", "Thriller"))
        val horror = card("horror", 1999, listOf("Action", "Horror"))
        val drama = card("drama", 2001, listOf("Drama"))
        val unknown = card("unknown")
        val filters = CatalogFilters(decade = 1990, includedGenres = setOf("Action", "Thriller"), excludedGenres = setOf("Horror"))
        assertEquals(listOf(thriller), filters.apply(listOf(thriller, horror, drama, unknown)))
        assertTrue(filters.copy(year = 2000).apply(listOf(thriller)).isEmpty())
        assertEquals(listOf(thriller, drama), filters.copy(decade = null, includedGenres = setOf("Thriller", "Drama"), matchAllGenres = false)
            .apply(listOf(thriller, horror, drama, unknown)))
        assertFalse(CatalogFilters(excludedGenres = setOf("Horror")).matches(unknown))
    }

    @Test fun `genre toggles prevent contradictions and normalize TV and movie aliases`() {
        val filters = CatalogFilters().include("Horror").exclude("Horror")
        assertTrue(filters.includedGenres.isEmpty())
        assertEquals(setOf("Horror"), filters.excludedGenres)
        assertTrue(filters.exclude("Horror").excludedGenres.isEmpty())
        assertTrue(CatalogFilters(includedGenres = setOf("Science Fiction", "Fantasy")).matches(
            card("series", genres = listOf("Sci-Fi & Fantasy"))))
        assertFalse(CatalogFilters(excludedGenres = setOf("Science Fiction")).matches(
            card("movie", genres = listOf("Sci-Fi"))))
    }

    @Test fun `sort puts missing data last and retains catalog order for ties`() {
        val unknown = card("Unknown")
        val older = card("beta", 1999, rating = 8.5, runtime = 120)
        val newer = card("Alpha", 2024, rating = 9.0, runtime = 90)
        val sameYear = card("same", 2024)
        val cards = listOf(unknown, older, newer, sameYear)
        assertEquals(cards, CatalogFilters().apply(cards))
        assertEquals(listOf(newer, sameYear, older, unknown), CatalogFilters(sort = CatalogSort.Newest).apply(cards))
        assertEquals(listOf(older, newer, sameYear, unknown), CatalogFilters(sort = CatalogSort.Oldest).apply(cards))
        assertEquals(listOf(newer, older, unknown, sameYear), CatalogFilters(sort = CatalogSort.Rating).apply(cards))
        assertEquals(listOf(newer, older, unknown, sameYear), CatalogFilters(sort = CatalogSort.Shortest).apply(cards))
        assertEquals(listOf(newer, older), CatalogFilters(minimumRating = 8, sort = CatalogSort.Title).apply(cards))
        assertEquals(listOf(older), CatalogFilters(runtime = CatalogRuntime.Medium, minimumRating = 8).apply(listOf(older, unknown)))
    }

    @Test fun `metadata retains provider identity and enables country language year and all genres`() {
        val saved = card("saved:provider:42", genres = listOf("Drama"))
        val meta = com.fourseveneightnine.tv.client.data.meta.Meta("tt12345", "movie", "Known title", year = 1999,
            genres = listOf("Thriller", "Horror"), originalLanguage = "ta", countries = listOf("IN"), imdbRating = 8.0)
        val hydrated = saved.withCatalogMetadata(meta)
        assertEquals(saved.id, hydrated.id)
        assertEquals(listOf("Drama", "Thriller", "Horror"), hydrated.genres)
        assertTrue(CatalogFilters(year = 1999, language = "ta", country = "India", minimumRating = 8).matches(hydrated))
        assertFalse(CatalogFilters(excludedGenres = setOf("Horror")).matches(hydrated))
        assertFalse(CatalogFilters(language = "en").matches(hydrated))
        assertEquals("United States", catalogCountry("USA"))
    }

    @Test fun `expanded facets and numeric ranges match together and reject unknown facts`() {
        val movie = card("movie", 1999, listOf("Drama"), 8.0, 120).copy(
            castNames = listOf("Actor"), directors = listOf("Director"), studios = listOf("Studio"), keywords = listOf("time travel"),
            spokenLanguages = listOf("ta", "en"), originalLanguage = "ta", countries = listOf("India"),
            certification = "PG-13", releaseStatus = "Released", releaseDate = "1999-10-01", tmdbVotes = 1000,
            popularity = 42.0, imdbRating = 8.0, tmdbRating = 7.0, hasTrailer = true, collectionIds = setOf(7L),
            hasPlaybackHistory = true, finishedMovie = true)
        val filters = CatalogFilters(yearFrom = 1990, yearTo = 2000, minimumRating = 7, maximumRating = 9,
            runtimeFrom = 100, runtimeTo = 150, mediaType = "movie", certification = "PG-13", actor = "Actor",
            director = "Director", studio = "Studio", keyword = "time travel", spokenLanguage = "en",
            language = "ta", country = "India", releaseStatus = "Released", minimumVotes = 500,
            ratingSource = CatalogRatingSource.IMDb, withTrailer = true, collectionId = 7, history = CatalogHistory.Finished)
        assertEquals(listOf(movie), filters.apply(listOf(card("unknown"), movie)))
        listOf(filters.copy(actor = "Other"), filters.copy(yearFrom = 2000), filters.copy(yearTo = 1998),
            filters.copy(minimumRating = 9), filters.copy(maximumRating = 7), filters.copy(runtimeFrom = 121),
            filters.copy(runtimeTo = 119), filters.copy(mediaType = "series"), filters.copy(minimumVotes = 1001),
            filters.copy(certification = "R"), filters.copy(spokenLanguage = "fr"), filters.copy(studio = "Other"),
            filters.copy(director = "Other"), filters.copy(keyword = "Other"), filters.copy(releaseStatus = "Planned"),
            filters.copy(collectionId = 8), filters.copy(history = CatalogHistory.Unplayed)).forEach { query ->
            assertTrue(query.apply(listOf(movie)).isEmpty())
        }
        assertEquals(listOf(movie), filters.copy(ratingSource = CatalogRatingSource.TMDB).apply(listOf(movie)))
        assertTrue(filters.copy(minimumRating = 8, ratingSource = CatalogRatingSource.TMDB).apply(listOf(movie)).isEmpty())
        assertEquals(listOf(movie), CatalogFilters(history = CatalogHistory.Finished).apply(listOf(movie)))
        assertTrue(CatalogFilters(history = CatalogHistory.Continuing).apply(listOf(movie)).isEmpty())
    }

    @Test fun `indexed queries stay correct across rapid changes without mutating shared postings`() {
        val cards = listOf(card("a", 1999, listOf("Action", "Horror")), card("b", 2001, listOf("Drama")), card("unknown"))
        val index = CatalogFacetIndex(cards)
        repeat(100) {
            assertEquals(listOf(cards[1]), index.query(CatalogFilters(excludedGenres = setOf("Horror"))))
            assertEquals(cards, index.query(CatalogFilters()))
            assertEquals(listOf(cards[0]), index.query(CatalogFilters(includedGenres = setOf("Action"))))
            assertEquals(listOf(cards[0], cards[1]), index.query(CatalogFilters(includedGenres = setOf("Action", "Drama"), matchAllGenres = false)))
            assertEquals(cards, index.query(CatalogFilters(sort = CatalogSort.Title)))
        }
    }

    @Test fun `saved source bookmarks use their catalog ID only for metadata`() {
        val source = com.fourseveneightnine.tv.client.playback.WatchlistSource("tt1375666", "Inception", null, null,
            torrentHash = "a".repeat(40), fileIndex = 0)
        val id = source.cardId("movie")
        val stored = kotlinx.serialization.json.Json.encodeToString(
            com.fourseveneightnine.tv.client.playback.WatchlistSource.serializer(), source)
        assertEquals("tt1375666", catalogMetadataId(id, stored))
        assertEquals(id, catalogMetadataId(id, null))
        assertEquals(id, catalogMetadataId(id, "invalid"))
        assertEquals("tt-other", catalogMetadataId("tt-other", stored))
        val card = card(id).withCatalogMetadata(com.fourseveneightnine.tv.client.data.meta.Meta(
            "tt1375666", "movie", "Inception", year = 2010, genres = listOf("Science Fiction")))
        assertEquals(id, card.id)
        assertTrue(CatalogFilters(year = 2010, includedGenres = setOf("Science Fiction")).matches(card))
    }

    @Test fun `filter restoration keeps extended facets without provider credentials`() {
        val query = CatalogFilters(yearFrom = 1990, yearTo = 2020, includedGenres = setOf("Drama"),
            excludedGenres = setOf("Horror"), director = "Director", studio = "Studio", minimumVotes = 1000,
            spokenLanguage = "ta", collectionId = 42, history = CatalogHistory.Unplayed, sort = CatalogSort.Popularity)
        val json = kotlinx.serialization.json.Json
        val saved = json.encodeToString(CatalogFilters.serializer(), query)
        assertEquals(query, json.decodeFromString(CatalogFilters.serializer(), saved))
        assertFalse(saved.contains("manifest"))
        assertFalse(CatalogFilters(matchAllGenres = false).active)
    }

    @Test fun `large catalog queries reuse cached sorting with sparse people postings`() {
        val cards = (0 until 20000).map { i ->
            card("Title ${i.toString().padStart(5, '0')}", 1980 + i % 45,
                listOf(if (i % 2 == 0) "Action" else "Drama", "Thriller", if (i % 10 == 0) "Horror" else "Crime"),
                rating = (i % 10).toDouble(), runtime = 60 + i % 120).copy(
                castNames = listOf("Unique actor $i", "Actor ${i % 500}"), studios = listOf("Studio ${i % 20}"),
                tmdbVotes = i % 10000, popularity = (i % 100).toDouble())
        }
        val query = CatalogFilters(includedGenres = setOf("Action", "Thriller"), excludedGenres = setOf("Horror"),
            yearFrom = 1990, yearTo = 2010, minimumRating = 7, maximumRating = 9, runtimeFrom = 90, runtimeTo = 150,
            minimumVotes = 500, sort = CatalogSort.Rating)
        val started = System.nanoTime()
        val index = CatalogFacetIndex(cards)
        val built = System.nanoTime()
        val result = index.query(query)
        val cold = System.nanoTime()
        val expected = cards.filter { it.year in 1990..2010 && "Action" in it.genres && "Horror" !in it.genres &&
            requireNotNull(it.rating) in 7.0..9.0 && requireNotNull(it.runtimeMinutes) in 90..150 && requireNotNull(it.tmdbVotes) >= 500 }
            .sortedByDescending { it.rating }
        assertEquals(expected, result)
        assertEquals(listOf(cards[1234]), index.query(CatalogFilters(actor = "Unique actor 1234")))
        repeat(30) { index.query(query) }
        val samples = (0 until 100).map {
            val before = System.nanoTime()
            assertEquals(result, index.query(query))
            (System.nanoTime() - before) / 1_000_000.0
        }.sorted()
        println("CATALOG_FILTER_BENCH size=20000 build_ms=${(built-started)/1_000_000.0} cold_ms=${(cold-built)/1_000_000.0} warm_median_ms=${samples[50]} warm_p95_ms=${samples[95]}")
    }

    @Test fun `liked list pages preserve order and distinguish live next links from the last page`() {
        val html = """
            <h2 class="name prettify"><a href="/alice/list/new-releases/">New Releases</a></h2>
            <h2 class="name prettify"><a href="/bob/list/essentials/">Essentials</a></h2>
            <h2 class="name prettify"><a href="/alice/list/new-releases/">New Releases</a></h2>
            <a class="next" href="/viewer/likes/lists/page/2/">Next</a>
        """.trimIndent()
        val page = TVLetterboxdClient.parseLikedLists(html)
        assertEquals(listOf("alice/new-releases", "bob/essentials"), page.entries.map { it.key })
        assertEquals(listOf("New Releases", "Essentials"), page.entries.map { it.title })
        assertTrue(page.hasNext)
        assertFalse(TVLetterboxdClient.parseLikedLists(html.substringBefore("<a class=")).hasNext)
        assertFalse(TVLetterboxdClient.validUsername("../another"))
    }

    @Test fun `Letterboxd film pages use grid items and verify the metadata media type`() {
        val html = """
            <div data-item-slug="unrelated" data-item-name="Unrelated"></div>
            <ul class="poster-list -grid">
                <div data-item-slug="harakiri" data-item-name="Harakiri (1962)"
                     data-poster-url="/film/harakiri/image-150/"></div>
                <img data-film-slug="older-item" alt="Older Item">
            </ul>
            <span class="next">Next</span>
        """.trimIndent()
        val page = TVLetterboxdClient.parseFilms(html)
        assertEquals(listOf("harakiri", "older-item"), page.entries.map { it.slug })
        assertEquals("https://letterboxd.com/film/harakiri/image-150/", page.entries.first().poster)
        assertFalse(page.hasNext)
        assertEquals(TVLetterboxdIdentity("series", "tmdb:123"),
            TVLetterboxdClient.parseIdentity("""<body data-tmdb-type="tv" data-tmdb-id="123">"""))
        assertNull(TVLetterboxdClient.parseIdentity("""<body><a href="https://imdb.com/title/tt999/">Other</a>"""))
    }

    @Test fun `film artwork uses the linked portrait instead of a social backdrop`() {
        val portrait = "https://a.ltrbxd.com/resized/sm/upload/portrait-600-900-crop.jpg?v=2"
        val html = """
            <meta property="og:image" content="https://a.ltrbxd.com/backdrop.jpg">
            <script type="application/ld+json">
            /* <![CDATA[ */
            {"@type":"Movie","image":"$portrait"}
            /* ]]> */
            </script>
        """.trimIndent()
        assertEquals(portrait, TVLetterboxdClient.parsePortrait(html))
        assertNull(TVLetterboxdClient.parsePortrait(html.substringBefore("<script")))
        assertNull(TVLetterboxdClient.parsePortrait(html.replace("https://a.ltrbxd.com/", "https://example.org/")))
    }

    @Test fun `Letterboxd resolves a series from the title TMDB button when the body ID is empty`() {
        val html = """
            <body data-tmdb-type="movie" data-tmdb-id="">
            <a href="https://www.themoviedb.org/tv/335981/" class="micro-button track-event"
                data-track-action="TMDB" target="_blank">TMDB</a>
        """.trimIndent()
        assertEquals(TVLetterboxdIdentity("series", "tmdb:335981"), TVLetterboxdClient.parseIdentity(html))
        assertEquals(TVLetterboxdIdentity("movie", "tmdb:42"),
            TVLetterboxdClient.parseIdentity(html.replace("data-tmdb-id=\"\"", "data-tmdb-id=\"42\"")))
        assertNull(TVLetterboxdClient.parseIdentity(html.replace("www.themoviedb.org", "example.org")))
        assertNull(TVLetterboxdClient.parseIdentity(html.replace("data-track-action=\"TMDB\"", "")))
        assertNull(TVLetterboxdClient.parseIdentity(html + html.substringAfter('\n').replace("335981", "273970")))
    }

    @Test fun `an IMDb-only Letterboxd page reuses a saved identifier only when the IDs agree`() {
        val html = """<a href="http://www.imdb.com/title/tt47021035/maindetails"
            data-track-action="IMDb">IMDb</a>"""
        val details = TVLetterboxdFilmDetails(null, null, TVLetterboxdClient.imdbIdentity(html))
        assertEquals(TVLetterboxdIdentity("series", "tt47021035"), details.resolveIdentity("series", "tt47021035"))
        assertNull(details.resolveIdentity("movie", "tt29870301"))
        assertNull(details.resolveIdentity("movie", null))
    }

    @Test fun `Letterboxd series appear under the resolved media type and keep their grid key`() {
        val unresolved = card("letterboxd-film:cunk-on-cinema")
        val resolved = unresolved.copy(letterboxdIdentity = TVLetterboxdIdentity("series", "tmdb:335981"))
        val movie = card("tmdb:42")
        assertEquals(unresolved.key, resolved.key)
        assertEquals(listOf(resolved), CatalogFilters(mediaType = "series").apply(listOf(resolved, movie)))
        assertEquals(listOf(movie), CatalogFilters(mediaType = "movie").apply(listOf(resolved, unresolved, movie)))
    }

    @Test fun `a selected list can show page one while another list refreshes`() = kotlinx.coroutines.runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("public-list-preview").toFile()
        val firstStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val finishFirst = kotlinx.coroutines.CompletableDeferred<Unit>()
        try {
            fun shelf(slug: String) = Shelf("letterboxd:liked:owner/$slug", slug, ShelfKind.LETTERBOXD,
                emptyList(), "iphone-letterboxd-2", 0)
            val selected = shelf("selected")
            val store = TVLetterboxdListRefresh(directory, this, { list, number ->
                if (list.slug == "other") {
                    firstStarted.complete(Unit)
                    finishFirst.await()
                }
                TVLetterboxdPage(listOf(TVLetterboxdFilm("${list.slug}-$number", "Film $number", null)),
                    list.slug == "selected" && number == 1, if (list.slug == "selected") 2 else 1)
            })
            val refresh = requireNotNull(store.refresh(listOf(shelf("other")), bulk = true))
            kotlinx.coroutines.withTimeout(3_000) {
                firstStarted.await()
                store.hydrate()
                val list = requireNotNull(TVLetterboxdClient.savedList(selected))
                val preview = store.firstPage(list)
                assertEquals(listOf("selected-1"), preview.entries.map { it.slug })
                assertNull(store.films(list))
                finishFirst.complete(Unit)
                refresh.join()

                // The completed fetch continues from page two and retains the preview's rows.
                val changed = preview.copy(entries = listOf(TVLetterboxdFilm("preview-first", "Film 1", null)))
                requireNotNull(store.refresh(listOf(selected), bulk = false, firstPage = changed)).join()
                assertEquals(listOf("preview-first", "selected-2"), store.films(list)?.map { it.slug })
            }
        } finally {
            finishFirst.complete(Unit)
            directory.deleteRecursively()
        }
    }

    @Test fun `public list refresh checkpoints successes and retries only missing lists after restarting`() = kotlinx.coroutines.runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("public-list-refresh").toFile()
        try {
            fun shelf(slug: String) = Shelf("letterboxd:liked:viewer:owner/$slug", slug, ShelfKind.LETTERBOXD, emptyList(), "iphone-letterboxd-2", 0)
            val good = shelf("good")
            val changed = shelf("changed")
            val after = shelf("after")
            val lists = listOf(good, changed, after)
            val visits = mutableListOf<String>()
            var broken = false
            var time = 10L
            fun store() = TVLetterboxdListRefresh(directory, this, { list, page ->
                visits.add("${list.slug}:$page")
                val film = TVLetterboxdFilm("film-$page", "Film $page", null)
                val more = list.slug == "changed" && page == 1
                TVLetterboxdPage(listOf(film), more, if (list.slug == "changed") if (broken) 3 else 2 else 1)
            }, { time++ })
            val first = store()
            first.refresh(lists, bulk = true)?.join()
            val list = requireNotNull(TVLetterboxdClient.savedList(changed))
            assertEquals(2, first.films(list)?.size)
            val firstDate = first.state.value.stamps.getValue(list.key).checkedAt
            visits.clear()
            broken = true
            first.refresh(lists, bulk = true)?.join()
            assertEquals(listOf("good:1", "changed:1", "changed:2", "after:1"), visits)
            assertEquals(firstDate, first.state.value.stamps.getValue(list.key).checkedAt)
            assertEquals(2, first.films(list)?.size)
            assertTrue(first.state.value.message.orEmpty().contains("1 need retry"))
            visits.clear()
            broken = false
            val reopened = store()
            reopened.refresh(lists, bulk = true, resume = true)?.join()
            assertEquals(listOf("changed:1", "changed:2"), visits)
            assertEquals(3, reopened.state.value.stamps.size)
            assertEquals("Refreshed 3 of 3 lists.", reopened.state.value.message)
            reopened.clear()
            assertNull(reopened.films(list))
        } finally { directory.deleteRecursively() }
    }

    @Test fun `existing public lists load ten folder covers from the saved films`() = kotlinx.coroutines.runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("public-list-covers").toFile()
        try {
            val films = List(14) { TVLetterboxdFilm("film-$it", "Film $it", "https://cdn.example/$it.jpg") }
            val stamp = TVLetterboxdListRefresh.Stamp("owner/covers", 1L, films.size, films.take(4))
            val file = directory.resolve("owner/covers.list")
            check(file.parentFile.mkdirs())
            file.writeText(Json.encodeToString(stamp) + "\n" + Json.encodeToString(films) + "\n")
            val store = TVLetterboxdListRefresh(directory, this, { _, _ -> error("Unexpected network request") })
            store.hydrate()
            assertEquals(films.take(10), store.state.value.stamps.getValue(stamp.key).preview)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `two denied lists do not block the next public list`() = kotlinx.coroutines.runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("public-list-denied").toFile()
        try {
            val store = TVLetterboxdListRefresh(directory, this, { list, _ ->
                if (list.slug != "readable") throw HttpStatusException(403)
                TVLetterboxdPage(listOf(TVLetterboxdFilm("film", "Film", null)), false, 1)
            })
            val shelves = listOf("first", "second", "readable").map {
                Shelf("letterboxd:liked:owner/$it", it, ShelfKind.LETTERBOXD, emptyList(), "iphone", 1)
            }
            store.refresh(shelves, bulk = true)?.join()
            assertEquals(setOf("owner/first", "owner/second"), store.state.value.failedKeys)
            assertEquals(1, store.state.value.stamps.getValue("owner/readable").count)
            assertTrue(store.state.value.pending)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `film counts distinguish empty lists from unreadable film grids`() {
        val script = """<script src="/cdn-cgi/challenge-platform/scripts/jsd/main.js"></script>"""
        assertFalse(TVLetterboxdClient.isVerificationPage("""<link href="https://s.ltrbxd.com/static/site.css">""" + script))
        assertTrue(TVLetterboxdClient.isVerificationPage("<title>Just a moment</title>" + script))
        val empty = TVLetterboxdClient.parseFilms("""<meta name="description" content="A list of 0 films compiled on Letterboxd">""")
        assertEquals(0, empty.total)
        assertTrue(empty.entries.isEmpty())
        assertTrue(runCatching { TVLetterboxdClient.parseFilms("""<ul class="poster-list"><li>unexpected</li></ul>""") }.isFailure)
    }

    @Test fun `saved lists offer each media type without replacing the default remote catalog`() {
        val detail = CollectionDetail(9L, SystemCollections.FAVORITES_NAME, "#888888", CollectionKind.SYSTEM,
            SystemCollections.FAVORITES_REF, false, 0, 0L, emptyList())
        val saved = DiscoverPlan.savedCatalogs(listOf(detail))
        assertEquals(DiscoverType.entries.map { it.wire }, saved.map { it.type })
        assertTrue(saved.all { it.collectionId == 9L && it.addonName == "Your saved titles" })
        assertEquals(DiscoverPlan.defaultCatalogKey(choices, DiscoverType.Movies),
            DiscoverPlan.defaultCatalogKey(saved + choices, DiscoverType.Movies))
        assertEquals(saved, DiscoverPlan.filterCatalogs(saved, "favorites"))
    }

    @Test fun `catalog search matches partial names and addons without changing order`() {
        assertEquals(listOf("Top movies", "Top series"),
            DiscoverPlan.filterCatalogs(choices, " NETFLIX top ").map { it.catalogName })
        assertEquals(listOf("Trending"), DiscoverPlan.filterCatalogs(choices, "trend").map { it.catalogName })
        assertTrue(DiscoverPlan.filterCatalogs(choices, "no-such-catalog").isEmpty())
        assertEquals(choices, DiscoverPlan.filterCatalogs(choices, "   "))
        val large = (0..341).map { choices.first().copy(key = "catalog-$it", catalogName = "Catalog $it") }
        for (index in listOf(0, 170, 341)) {
            assertTrue(DiscoverPlan.filterCatalogs(large, "Catalog $index").any { it.key == "catalog-$index" })
        }
    }

    private fun addon(name: String, url: String, vararg catalogs: AddonCatalog) = Addon(
        name = name,
        manifestURL = url,
        manifest = AddonManifest(id = name, name = name, catalogs = catalogs.toList()),
    )

    private val netflix = addon(
        "Netflix",
        "https://one.example/manifest.json",
        AddonCatalog("movie", "top", "Top movies", genres = listOf("Thriller", "Comedy")),
        AddonCatalog("series", "top", "Top series"),
    )
    private val anime = addon(
        "Kitsu",
        "https://two.example/manifest.json",
        AddonCatalog("anime", "trending", "Trending"),
    )

    private val choices = DiscoverPlan.choices(
        listOf(
            netflix to netflix.manifest!!.catalogs[0],
            netflix to netflix.manifest!!.catalogs[1],
            anime to anime.manifest!!.catalogs[0],
            // a duplicate the manifest declared twice
            netflix to netflix.manifest!!.catalogs[0],
        ),
    )

    @Test fun `Letterboxd snapshots and Stremio catalogs filter independently`() {
        val shelf = Shelf("list-1", "Festival picks", ShelfKind.LETTERBOXD, listOf(
            CatalogItem("m1", "movie", "Film"),
            CatalogItem("s1", "series", "Series"),
        ), "generation", 1L)
        val local = DiscoverPlan.letterboxdChoices(listOf(shelf, shelf.copy(id = "other", kind = ShelfKind.TMDB_MOVIES)))
        assertEquals(listOf("movie", "series"), local.map { it.type })
        assertTrue(local.all { it.shelfId == "list-1" && it.isLocal })
        assertEquals(local, DiscoverPlan.filterCatalogs(local + choices, "festival", CatalogSource.Letterboxd))
        assertEquals(choices, DiscoverPlan.filterCatalogs(local + choices, "", CatalogSource.Stremio))
        assertEquals(listOf(CatalogSource.Letterboxd, CatalogSource.Stremio),
            DiscoverPlan.panelRows(choices + local).filterIsInstance<CatalogPanelRow.Section>().map { it.source })
        assertEquals(choices.first().key, DiscoverPlan.defaultCatalogKey(local + choices, DiscoverType.Movies))
    }

    @Test fun `Letterboxd shows movies and names-only phone lists while browsing series`() {
        val lists = DiscoverPlan.letterboxdChoices(listOf(
            Shelf("letterboxd:filled", "Latest Digital Releases", ShelfKind.LETTERBOXD,
                listOf(CatalogItem("m1", "movie", "Film")), "generation", 1L),
            Shelf("letterboxd:pending", "Festival picks", ShelfKind.LETTERBOXD,
                emptyList(), "generation", 1L),
        ))
        val available = lists + choices
        val letterboxdPanel = DiscoverPlan.catalogPanelChoices(available, DiscoverType.Series,
            CatalogSource.Letterboxd, allTypes = false)
        assertEquals(listOf("Latest Digital Releases", "Festival picks"),
            DiscoverPlan.filterCatalogs(letterboxdPanel, "", CatalogSource.Letterboxd).map { it.catalogName })
        assertEquals("letterboxd:pending", lists.last().shelfId)
        val stremioPanel = DiscoverPlan.catalogPanelChoices(available, DiscoverType.Series,
            CatalogSource.Stremio, allTypes = false)
        assertEquals(listOf("Top series"), DiscoverPlan.filterCatalogs(stremioPanel, "", CatalogSource.Stremio)
            .map { it.catalogName })
    }

    @Test fun `browse supplies a required genre and omits search-only catalogs`() {
        val requiredGenre = AddonCatalog("movie", "genre", "Genres",
            extraRequired = listOf("genre"), genres = listOf("Drama", "Comedy"))
        val searchOnly = AddonCatalog("movie", "search", "Search", extraRequired = listOf("search"))
        val missingOptions = requiredGenre.copy(id = "broken", genres = emptyList())
        val provider = addon("Provider", "https://catalog.example/manifest.json",
            requiredGenre, searchOnly, missingOptions)
        val choice = DiscoverPlan.choices(provider.manifest!!.catalogs.map { provider to it }).single()
        assertEquals("Drama", choice.requiredGenre)
        assertEquals("Drama", DiscoverPlan.genreFor(choice, null))
        assertEquals("Comedy", DiscoverPlan.genreFor(choice, "comedy"))
        assertTrue(DiscoverPlan.catalogError(HttpStatusException(403)).contains("refused access"))
    }

    @Test
    fun `choices keep registry order and drop duplicates`() {
        assertEquals(3, choices.size)
        assertEquals(listOf("Top movies", "Top series", "Trending"), choices.map { it.catalogName })
        assertEquals(listOf("Netflix", "Netflix", "Kitsu"), choices.map { it.addonName })
    }

    @Test
    fun `a type chip shows only the catalogs of that type`() {
        assertEquals(listOf("Top movies"), DiscoverPlan.forType(choices, DiscoverType.Movies).map { it.catalogName })
        assertEquals(listOf("Top series"), DiscoverPlan.forType(choices, DiscoverType.Series).map { it.catalogName })
        assertEquals(listOf("Trending"), DiscoverPlan.forType(choices, DiscoverType.Anime).map { it.catalogName })
    }

    @Test
    fun `the type chip opens on the first catalog it has, or none`() {
        assertEquals(choices[0].key, DiscoverPlan.defaultCatalogKey(choices, DiscoverType.Movies))
        assertNull(DiscoverPlan.defaultCatalogKey(emptyList(), DiscoverType.Movies))
    }

    @Test
    fun `the genre is kept when the new catalog has it and cleared when it does not`() {
        assertEquals("Thriller", DiscoverPlan.retainGenre("Thriller", listOf("Thriller", "Comedy")))
        assertEquals("thriller", DiscoverPlan.retainGenre("Thriller", listOf("thriller")))
        assertNull(DiscoverPlan.retainGenre("Thriller", listOf("Drama")))
        assertNull(DiscoverPlan.retainGenre("Thriller", emptyList()))
        assertNull(DiscoverPlan.retainGenre(null, listOf("Thriller")))
    }

    // ------------------------------------------------------------------ paging

    @Test
    fun `the next page is asked for once the focused row passes seventy percent`() {
        // 60 items is 15 rows of four. Prefetch starts in row 11.
        assertFalse(DiscoverPlan.shouldLoadMore(focusedIndex = 0, loadedCount = 60)) // row 0
        assertFalse(DiscoverPlan.shouldLoadMore(focusedIndex = 39, loadedCount = 60))
        assertTrue(DiscoverPlan.shouldLoadMore(focusedIndex = 40, loadedCount = 60))
        assertTrue(DiscoverPlan.shouldLoadMore(focusedIndex = 59, loadedCount = 60))
    }

    @Test
    fun `an empty grid never asks for a page`() {
        assertFalse(DiscoverPlan.shouldLoadMore(focusedIndex = 0, loadedCount = 0))
        assertFalse(DiscoverPlan.shouldLoadMore(focusedIndex = -1, loadedCount = 60))
    }

    @Test
    fun `a partly filled last row still counts as a row`() {
        // 7 items is 2 rows. Row 1 is already past 70% of 2 rows.
        assertTrue(DiscoverPlan.shouldLoadMore(focusedIndex = 4, loadedCount = 7))
        assertFalse(DiscoverPlan.shouldLoadMore(focusedIndex = 0, loadedCount = 7))
    }

    @Test
    fun `paging waits while the first page is still on the wire`() {
        // 12 items fills three rows. Prefetch starts in the third row.
        assertTrue(DiscoverPlan.shouldLoadMore(focusedIndex = 8, loadedCount = 12))
        assertFalse(DiscoverPlan.shouldLoadMore(focusedIndex = 7, loadedCount = 12))
    }

    // ------------------------------------------------------------------ the request window

    @Test
    fun `only visible cells and one row of look-ahead may fetch`() {
        // Three visible rows contain 12 cells. Prefetch adds the next four.
        assertEquals(0..15, DiscoverPlan.requestWindow(firstVisible = 0, lastVisible = 11, itemCount = 60))
        // Scrolled down: nothing above the window is asked for again.
        assertEquals(12..27, DiscoverPlan.requestWindow(firstVisible = 12, lastVisible = 23, itemCount = 60))
    }

    @Test
    fun `the window never runs past the last cell or below the first`() {
        assertEquals(54..59, DiscoverPlan.requestWindow(firstVisible = 54, lastVisible = 59, itemCount = 60))
        assertEquals(0..5, DiscoverPlan.requestWindow(firstVisible = -4, lastVisible = 3, itemCount = 6))
        assertTrue(DiscoverPlan.requestWindow(firstVisible = 0, lastVisible = 5, itemCount = 0).isEmpty())
    }

    @Test
    fun `a cell past the look-ahead does not fetch`() {
        val window = DiscoverPlan.requestWindow(firstVisible = 0, lastVisible = 11, itemCount = 120)
        assertTrue(0 in window)
        assertTrue(15 in window)
        assertFalse(16 in window)
    }

    // ------------------------------------------------------------------ geometry

    @Test
    fun `the skeleton and the real cell sit at the same row pitch`() {
        // The grid used to jump 20 px when the first page landed, because the two were written by
        // hand in two files. Both now come from these numbers.
        assertEquals(4, DiscoverPlan.COLUMNS)
        assertEquals(622, DiscoverPlan.CELL_BLOCK_HEIGHT_DP)
        assertEquals(650, DiscoverPlan.ROW_PITCH_DP)
        assertEquals(
            DiscoverPlan.ROW_PITCH_DP,
            DiscoverPlan.POSTER_HEIGHT_DP + DiscoverPlan.SKELETON_ROW_SPACER_DP,
        )
    }

    @Test
    fun `the grid ends at the safe bottom, collapsed or not`() {
        listOf(true, false).forEach { collapsed ->
            assertEquals(
                DiscoverPlan.CONTENT_HEIGHT_DP,
                DiscoverPlan.gridTopDp(collapsed) + DiscoverPlan.gridHeightDp(collapsed),
            )
        }
        assertEquals(162, DiscoverPlan.gridTopDp(collapsed = false))
        assertEquals(72, DiscoverPlan.gridTopDp(collapsed = true))
        // Collapsing the band gives the grid the 90 px the band gave up, and nothing more.
        assertEquals(90, DiscoverPlan.gridHeightDp(true) - DiscoverPlan.gridHeightDp(false))
    }

    // ------------------------------------------------------------------ the count line

    @Test
    fun `the count line says what is known and nothing else`() {
        assertEquals("Loading", DiscoverPlan.countLine(loading = true, itemCount = 0, hasMore = false))
        assertEquals("1,284 titles", DiscoverPlan.countLine(loading = false, itemCount = 1284, hasMore = false))
        assertEquals("1 title", DiscoverPlan.countLine(loading = false, itemCount = 1, hasMore = false))
        assertEquals("847 titles", DiscoverPlan.countLine(loading = false, itemCount = 847, hasMore = false))
        // A catalog with another page has no total to show, so the line is dropped, not guessed.
        assertNull(DiscoverPlan.countLine(loading = false, itemCount = 100, hasMore = true))
        assertNull(DiscoverPlan.countLine(loading = false, itemCount = 0, hasMore = false))
    }

    @Test
    fun `an empty filtered catalog gives its recovery action focus`() {
        assertTrue(
            DiscoverPlan.shouldFocusEmptyAction(
                settingsLoaded = true,
                loading = false,
                failed = false,
                itemCount = 0,
                hasGenre = true,
            ),
        )
    }

    @Test
    fun `empty action never steals focus before it is the useful recovery`() {
        assertFalse(DiscoverPlan.shouldFocusEmptyAction(false, false, false, 0, true))
        assertFalse(DiscoverPlan.shouldFocusEmptyAction(true, true, false, 0, true))
        assertFalse(DiscoverPlan.shouldFocusEmptyAction(true, false, true, 0, true))
        assertFalse(DiscoverPlan.shouldFocusEmptyAction(true, false, false, 1, true))
        assertFalse(DiscoverPlan.shouldFocusEmptyAction(true, false, false, 0, false))
        assertTrue(DiscoverPlan.shouldFocusEmptyAction(true, false, false, 0, false, savedList = true))
        assertFalse(DiscoverPlan.shouldFocusEmptyAction(true, true, false, 0, false, savedList = true))
    }

    // ------------------------------------------------------------------ the Catalog panel

    @Test
    fun `the panel lists one group header per add-on`() {
        val rows = DiscoverPlan.panelRows(choices)
        assertEquals(
            listOf("section:Stremio", "group:${choices[0].key}", "choice:${choices[0].key}", "choice:${choices[1].key}", "group:${choices[2].key}", "choice:${choices[2].key}"),
            rows.map { it.key },
        )
        assertEquals(listOf("Netflix", "Kitsu"), rows.filterIsInstance<CatalogPanelRow.Group>().map { it.addonName })
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
    }

    @Test
    fun `same-named add-ons retain distinct panel keys`() {
        val sameNames = listOf(choices[0], choices[2].copy(addonName = choices[0].addonName))
        val rows = DiscoverPlan.panelRows(sameNames)
        assertEquals(listOf("Netflix", "Netflix"), rows.filterIsInstance<CatalogPanelRow.Group>().map { it.addonName })
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
    }

    @Test
    fun `the panel opens on the current choice, or on the first row`() {
        val rows = DiscoverPlan.panelRows(choices)
        assertEquals(3, DiscoverPlan.panelRowIndex(rows, choices[1].key))
        assertEquals(0, DiscoverPlan.panelRowIndex(rows, "not-a-catalog"))
        assertEquals(0, DiscoverPlan.panelRowIndex(rows, null))
    }

    // ------------------------------------------------------------------ the collapsed strip

    @Test
    fun `the collapsed strip names every chosen value and drops the empty ones`() {
        assertEquals(
            "Discover · Movies · Netflix · Thrillers",
            DiscoverPlan.summaryStrip(DiscoverType.Movies, "Netflix", "Thrillers"),
        )
        assertEquals(
            "Discover · Series · Top series",
            DiscoverPlan.summaryStrip(DiscoverType.Series, "Top series", null),
        )
        assertEquals("Discover · Anime", DiscoverPlan.summaryStrip(DiscoverType.Anime, null, null))
    }

    @Test
    fun `the strip draws values, words and separators in three inks`() {
        val parts = DiscoverPlan.summaryParts(DiscoverType.Movies, "Netflix", "Thrillers", "1,284 titles")
        assertEquals(
            listOf("Discover", " · ", "Movies", " · ", "Netflix", " · ", "Thrillers", " · ", "1,284 titles"),
            parts.map { it.text },
        )
        assertEquals(StripInk.Word, parts.first().ink)
        assertEquals(listOf(StripInk.Separator, StripInk.Value), parts.drop(1).take(2).map { it.ink })
        assertEquals(StripInk.Value, parts.last().ink)
    }

    @Test
    fun `the strip drops what was never chosen`() {
        val parts = DiscoverPlan.summaryParts(DiscoverType.Anime, null, null, null)
        assertEquals(listOf("Discover", " · ", "Anime"), parts.map { it.text })
    }

    @Test
    fun `chip labels truncate where the spec says`() {
        assertEquals("Netflix", truncate("Netflix", 28))
        assertTrue(truncate("A genre name far too long for one chip", 20).length <= 20)
        assertTrue(truncate("A genre name far too long for one chip", 20).endsWith("…"))
    }
}
