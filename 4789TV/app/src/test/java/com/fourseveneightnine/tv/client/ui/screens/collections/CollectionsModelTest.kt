package com.fourseveneightnine.tv.client.ui.screens.collections

import com.fourseveneightnine.tv.catalog.LetterboxdListInformation
import com.fourseveneightnine.tv.client.OfflineLetterboxd
import com.fourseveneightnine.tv.client.playback.tamilMVEpisodeFiles
import com.fourseveneightnine.tv.catalog.TVLetterboxdShelf
import com.fourseveneightnine.tv.client.data.addons.AddonRegistry
import com.fourseveneightnine.tv.client.data.addons.StremioClient
import com.fourseveneightnine.tv.client.data.settings.SettingsDocument
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import com.fourseveneightnine.tv.catalog.TVTamilMVCatalogItem
import com.fourseveneightnine.tv.catalog.TVTamilMVCatalogSnapshot
import com.fourseveneightnine.tv.client.toPhoneLetterboxdCatalog
import com.fourseveneightnine.tv.client.mergeCollectionShelves
import com.fourseveneightnine.tv.client.data.library.CollectionDetail
import com.fourseveneightnine.tv.client.data.library.CollectionItem
import com.fourseveneightnine.tv.client.data.library.CollectionKind
import com.fourseveneightnine.tv.client.data.library.CollectionSummary
import com.fourseveneightnine.tv.client.data.library.SystemCollections
import com.fourseveneightnine.tv.client.data.catalog.CatalogItem
import com.fourseveneightnine.tv.client.data.catalog.CatalogPlaybackSource
import com.fourseveneightnine.tv.client.data.catalog.Shelf
import com.fourseveneightnine.tv.client.data.catalog.ShelfKind
import com.fourseveneightnine.tv.client.data.streams.debrid.DebridFile
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.screens.player.playerEpisodeList
import com.fourseveneightnine.tv.client.data.meta.Episode
import com.fourseveneightnine.tv.client.data.meta.Meta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every rule the Collections screens follow, checked without a television. */
class CollectionsModelTest {
    @Test fun researchViewsUseActualSavedTimeAndLikedOrder() {
        fun shelf(key: String, generation: String) =
            Shelf(OfflineLetterboxd.PREFIX + key, key, ShelfKind.LETTERBOXD, emptyList(), generation, 0L)
        val curated = shelf("ed/curated", "bundled")
        val newestLike = shelf("ed/newest", "bundled")
        val local = shelf("cineorgasm/phone", "imported-letterboxd")
        val unknown = shelf("ed/unknown", "bundled")
        val rows = listOf(curated, newestLike, local, unknown)
        val info = mapOf(
            "ed/curated" to LetterboxdListInformation(likedRank = 1, syncedAtMillis = 50),
            "ed/newest" to LetterboxdListInformation(likedRank = 0, syncedAtMillis = 60),
            "cineorgasm/phone" to LetterboxdListInformation(syncedAtMillis = 100),
        )
        assertEquals(rows, SyncedLists.filtered(rows, CollectionsView.All, info, emptyMap()))
        assertEquals(listOf(local, newestLike, curated), SyncedLists.filtered(rows, CollectionsView.RecentlySynced, info, emptyMap()))
        assertEquals(listOf(newestLike, curated), SyncedLists.filtered(rows, CollectionsView.RecentlyLiked, info, emptyMap()))
        assertEquals(listOf(local), SyncedLists.filtered(rows, CollectionsView.Imported, info, emptyMap()))
    }


    @Test fun `watchlist sorts save dates independently of years and manual position`() {
        val rows = listOf(
            CollectionItem("unknown", "movie", "Unknown (2027)", null, 0, 0),
            CollectionItem("new-b", "series", "B (1990)", null, 2, 500),
            CollectionItem("old", "movie", "Old (2026)", null, 3, 200),
            CollectionItem("new-a", "anime", "A (1980)", null, 1, 500),
        )
        fun ids(sort: String) = sortWatchlist(rows, sort).map(CollectionItem::canonicalId)

        assertEquals(listOf("new-a", "new-b", "old", "unknown"), ids(LATEST_WATCHLIST_SORT))
        assertEquals(listOf("old", "new-a", "new-b", "unknown"), ids(OLDEST_WATCHLIST_SORT))
        assertEquals(listOf("unknown", "new-a", "new-b", "old"), ids("Original"))
        assertEquals(listOf(WatchlistType.Movies, WatchlistType.Series, WatchlistType.Series),
            listOf("movie", "series", "anime").map(WatchlistType::of))
        assertEquals(WatchlistType.Other, WatchlistType.of("legacy"))
        assertEquals("amelie", watchlistSearchKey(" Amélie  "))
    }

    @Test fun `watchlist date ranges start at the profile device local midnight`() {
        val zone = java.time.ZoneId.of("America/Chicago")
        val today = java.time.LocalDate.of(2026, 11, 5)
        val first = today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
        val tomorrow = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        assertTrue(WatchlistDate.Week.matches(first, today, zone))
        assertFalse(WatchlistDate.Week.matches(first - 1, today, zone))
        assertFalse(WatchlistDate.Week.matches(tomorrow, today, zone))
        assertFalse(WatchlistDate.Month.matches(0, today, zone))
        assertTrue(WatchlistDate.Any.matches(0, today, zone))
    }

    @Test fun `catalog collections resolve cold required genres and reject unsupported browse extras`() = runTest {
        val requests = java.util.concurrent.CopyOnWriteArrayList<okhttp3.HttpUrl>()
        val manifest = """{"id":"provider","name":"Provider","resources":["catalog"],"catalogs":[
            {"type":"movie","id":"genre","name":"Genres","extra":[{"name":"genre","isRequired":true,"options":["Drama","Comedy"]}]},
            {"type":"movie","id":"plain","name":"Plain"},
            {"type":"movie","id":"search","name":"Search","extra":[{"name":"search","isRequired":true}]},
            {"type":"movie","id":"missing-options","name":"Missing options","extra":[{"name":"genre","isRequired":true}]},
            {"type":"movie","id":"unknown-extra","name":"Unknown extra","extra":[{"name":"country","isRequired":true}]}]}"""
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request.url)
            val body = when {
                "/catalog/" in request.url.encodedPath -> """{"metas":[{"id":"tt7","type":"movie","name":"Film","releaseInfo":"2024"}]}"""
                request.url.host == "collections.example" -> manifest
                else -> """{"id":"empty","name":"Cinemeta","resources":["catalog"],"catalogs":[]}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        try {
            val url = "https://collections.example/manifest.json"
            val client = StremioClient(http)
            val registry = AddonRegistry(SettingsDocument.parse("""{"sources":[{"name":"Provider","url":"$url"}]}"""), client)
            val ref = SourceRef.catalog(url, "movie", "genre")
            // Exercise the saved-source cold-open path before any registry refresh has completed.
            val cold = CollectionSources.catalogItems(registry, client, ref)
            assertEquals(listOf("tt7"), cold?.map(CollectionItem::canonicalId))
            assertEquals("Film (2024)", cold?.single()?.title)
            assertEquals("genre=Drama.json", requests.last().pathSegments.last())
            assertEquals(2, requests.size)
            assertEquals(ref, SourceRef.catalog(requireNotNull(SourceRef.parseCatalog(ref)).manifestUrl, "movie", "genre"))

            registry.refresh()
            requests.clear()
            assertEquals(listOf("genre", "plain"), registry.catalogs().filter { it.second.canBrowse }.map { it.second.id })
            for (id in listOf("search", "missing-options", "unknown-extra", "not-declared")) {
                assertNull(CollectionSources.catalogItems(registry, client, SourceRef.catalog(url, "movie", id)))
            }
            assertTrue(requests.isEmpty())
            assertEquals(1, CollectionSources.catalogItems(registry, client, SourceRef.catalog(url, "movie", "plain"))?.size)
            assertEquals(1, CollectionSources.catalogItems(registry, client, ref)?.size)
            // The genre page from the cold open is shared with Collections until refresh.
            assertEquals(listOf("plain.json"), requests.map { it.pathSegments.last() })
            registry.refresh(force = true)
            requests.clear()
            assertEquals(1, CollectionSources.catalogItems(registry, client, ref)?.size)
            assertEquals(listOf("genre=Drama.json"), requests.map { it.pathSegments.last() })
        } finally {
            http.dispatcher.cancelAll()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdownNow()
        }
    }
    @Test fun `player lists TamilMV episodes without external show metadata and keeps exact files`() {
        val item = CatalogItem("saved:tamilmv:part-timers", "series", "Part-Timers", season = 1,
            playbackSources = listOf(CatalogPlaybackSource("pack", "Part-Timers S01", season = 1)))
        val files = tamilMVEpisodeFiles(item, mapOf("pack" to listOf(
            DebridFile(31, "Part-Timers S01E03.mkv", 100),
            DebridFile(11, "Part-Timers S01E01.mkv", 100))))
        val episodes = playerEpisodeList(item.canonicalId, null, files)
        assertEquals(listOf(1, 3), episodes.map { it.episode })
        assertEquals(listOf(11, 31), episodes.map { it.inlineStreams?.single()?.torboxFileID })
        assertEquals(listOf("pack", "pack"), episodes.map { it.inlineStreams?.single()?.infoHash })
    }

    @Test fun `player shows declared TamilMV episodes without claiming unconfirmed files ready`() {
        val item = CatalogItem("saved:tamilmv:part-timers", "series", "Part-Timers", season = 1,
            playbackSources = listOf(CatalogPlaybackSource("pack", "Part-Timers S01", season = 1, episodes = listOf(1, 2, 3))))
        val files = tamilMVEpisodeFiles(item, mapOf("pack" to listOf(DebridFile(31, "Part-Timers S01E03.mkv", 100))), includeDeclared = true)
        val episodes = playerEpisodeList(item.canonicalId, null, files)
        assertEquals(listOf(1, 2, 3), episodes.map { it.episode })
        assertNull(episodes[0].inlineStreams)
        assertNull(episodes[1].inlineStreams)
        assertEquals(31, episodes[2].inlineStreams?.single()?.torboxFileID)
    }

    @Test fun `episode metadata enriches only matching release files`() {
        val item = CatalogItem("saved:tamilmv:show", "series", "Show", season = 2,
            playbackSources = listOf(CatalogPlaybackSource("pack", "Show S02", season = 2)))
        val files = tamilMVEpisodeFiles(item, mapOf("pack" to listOf(DebridFile(7, "Show S02E01.mkv", 100))))
        val meta = Meta("tt-known", "series", "Show", videos = listOf(
            Episode(1, 1, "Different season", id = "tt-known:1:1"),
            Episode(2, 1, "Known episode title", id = "tt-known:2:1"),
            Episode(2, 2, "Not in this release", id = "tt-known:2:2")))
        val episodes = playerEpisodeList(item.canonicalId, meta, files)
        assertEquals(listOf("Known episode title"), episodes.map { it.title })
        assertEquals(7, episodes.single().inlineStreams?.single()?.torboxFileID)
    }

    @Test fun `regular metadata episodes remain unchanged without saved release files`() {
        val known = listOf(Episode(1, 1, "Known title", id = "tt-known:1:1"))
        assertEquals(known, playerEpisodeList("tt-known", Meta("tt-known", "series", "Show", videos = known), emptyList()))
    }

    @Test fun `unidentified saved files never invent episode numbers`() {
        val item = CatalogItem("saved:tamilmv:show", "series", "Show",
            playbackSources = listOf(CatalogPlaybackSource("pack", "Show")))
        val files = tamilMVEpisodeFiles(item, mapOf("pack" to listOf(DebridFile(7, "Unidentified video.mkv", 100))))
        assertTrue(playerEpisodeList(item.canonicalId, null, files).isEmpty())
    }

    @Test fun `Collections replaces a later one-title phone preview with the complete source list`() {
        val likedID = "letterboxd:liked:owner/list"
        val preview = Shelf(likedID, "Liked list", ShelfKind.LETTERBOXD,
            listOf(CatalogItem("tmdb:1", "movie", "Film 1")), "iphone-3", 3)
        val other = Shelf("other", "Other list", ShelfKind.LETTERBOXD, emptyList(), "preview", 1)
        val fullItems = (1..1_305).map { TVTamilMVCatalogItem("tmdb:$it", "movie", "Film $it") }
        val mirror = TVTamilMVCatalogSnapshot(
            generation = "mirror", generatedAtMillis = 2, cachedAtMillis = 2,
            popular = emptyList(), recent = emptyList(),
            letterboxdShelves = listOf(
                TVLetterboxdShelf(likedID, "Liked list", fullItems),
                TVLetterboxdShelf("letterboxd:liked:owner/another", "Another list", fullItems.take(2)),
            ),
        )

        val merged = mergeCollectionShelves(listOf(preview, other), mirror.toPhoneLetterboxdCatalog())
        assertEquals(listOf(likedID, "other", "letterboxd:liked:owner/another"), merged.map(Shelf::id))
        val visible = SyncedLists.visible(merged).first { it.id == likedID }
        assertEquals("1305 titles", SyncedLists.countLabel(visible))
        assertEquals(fullItems.map { it.id }, visible.items.map { it.canonicalId })
        assertEquals(other, merged[1])
        assertEquals(listOf(preview, other), mergeCollectionShelves(listOf(preview, other), null))
    }

    @Test fun `TamilMV displays one poster per title and year while leaving remakes separate`() {
        val film = CatalogItem("saved:tamilmv:topic:1", "movie", "Film", year = 2026)
        val duplicate = film.copy(canonicalId = "saved:tamilmv:topic:2", title = "FILM")
        val remake = film.copy(canonicalId = "saved:tamilmv:topic:3", year = 2020)
        val show = film.copy(canonicalId = "saved:tamilmv:topic:4", mediaType = "series", season = 1, episode = 2)
        val next = show.copy(canonicalId = "saved:tamilmv:topic:5", episode = 3)
        val shelf = Shelf("test", "Tamil MV", ShelfKind.TAMILMV_RECENT, listOf(film, duplicate, remake, show, next), "test", 1)
        assertEquals(listOf(film, remake, show), SyncedLists.visible(listOf(shelf)).single().items)
    }

    @Test fun `TamilMV episode choices use the selected cached hash and exact file`() {
        val item = CatalogItem("saved:tamilmv:show", "series", "Show", season = 2, playbackSources = listOf(
            CatalogPlaybackSource("preferred", "Show S02", season = 2),
            CatalogPlaybackSource("alternate", "Show S02 alternate print", season = 2),
            CatalogPlaybackSource("episode3", "Show S02E03", season = 2, episodes = listOf(3)),
        ))
        val episodes = tamilMVEpisodeFiles(item, mapOf(
            "preferred" to listOf(
                DebridFile(12, "Show S02 EP02.mkv", 100),
                DebridFile(11, "Show 2x01.mkv", 100),
                DebridFile(13, "Show S03E03.mkv", 100),
            ),
            "alternate" to listOf(DebridFile(99, "Show S02E01.mkv", 100), DebridFile(100, "Show S02E04.mkv", 100)),
            "episode3" to listOf(DebridFile(21, "Show S02E03.mkv", 100)),
        ))
        assertEquals(listOf(1, 2, 3, 4), episodes.map { it.episode })
        assertEquals(listOf(11, 12, 21, 100), episodes.map { it.stream.torboxFileID })
        assertEquals(listOf("preferred", "preferred", "episode3", "alternate"), episodes.map { it.stream.infoHash })
        assertTrue(episodes.all { it.stream.exactSource })
    }

    @Test fun `TamilMV uses the next ranked print only when TorBox confirms a file`() {
        val item = CatalogItem("saved:tamilmv:show", "series", "Show", playbackSources = listOf(
            CatalogPlaybackSource("preferred", "Show S01", season = 1),
            CatalogPlaybackSource("alternate", "Show S01 alternate print", season = 1),
        ))
        assertTrue(tamilMVEpisodeFiles(item, emptyMap()).isEmpty())
        val episodes = tamilMVEpisodeFiles(item, mapOf("alternate" to listOf(DebridFile(1, "Show S01E01.mkv", 100))))
        assertEquals("alternate", episodes.single().stream.infoHash)
    }

    @Test fun `TamilMV shows declared pack episodes and only marks exact TorBox files cached`() {
        val item = CatalogItem("saved:tamilmv:show", "series", "Campus Drivers", season = 1,
            playbackSources = listOf(CatalogPlaybackSource("pack", "Campus Drivers S01 EP (01-06)",
                season = 1, episodes = (1..6).toList())))
        val files = mapOf("pack" to listOf(DebridFile(7, "Season 1/Episode 03.mkv", 100)))
        val episodes = tamilMVEpisodeFiles(item, files, includeDeclared = true)
        assertEquals((1..6).toList(), episodes.map { it.episode })
        assertEquals(3, episodes.single { it.cached }.episode)
        assertEquals(7, episodes.single { it.cached }.stream.torboxFileID)
        assertTrue(tamilMVEpisodeFiles(item, emptyMap()).isEmpty())
    }
    @Test fun `saved lists are offered before custom folders and derived system lists stay excluded`() {
        val rows = Checklist.rows(listOf(
            system(1, SystemCollections.CONTINUE_WATCHING_NAME),
            system(2, SystemCollections.MY_CLOUD_NAME),
            system(3, SystemCollections.WATCHLIST_NAME),
            system(4, SystemCollections.FAVORITES_NAME),
            user(5, "My list", count = 1)), containing = setOf(4L))
        assertEquals(listOf(4L, 3L, 5L), rows.map { it.id })
        assertEquals(ChecklistAction.Remove, Checklist.action(rows[0]))
        assertEquals(ChecklistAction.Add, Checklist.action(rows[1]))
        assertEquals(FolderRole.Favorites, FolderGrid.roleOf(SystemCollections.FAVORITES_NAME, CollectionKind.SYSTEM))
    }


    // ---------------------------------------------------------------- folder order

    @Test
    fun `system folders come first and keep the order the repository gave`() {
        val cards = FolderGrid.cards(
            listOf(
                system(1, SystemCollections.CONTINUE_WATCHING_NAME),
                system(2, SystemCollections.MY_CLOUD_NAME),
                system(3, SystemCollections.WATCHLIST_NAME),
                user(4, "Sunday night"),
                user(5, "Rewatch pile"),
            ),
            hasDebridKey = true,
        )

        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), cards.map(FolderCardModel::id))
        assertEquals(
            listOf(
                FolderRole.ContinueWatching, FolderRole.MyCloud, FolderRole.Watchlist,
                FolderRole.User, FolderRole.User,
            ),
            cards.map(FolderCardModel::role),
        )
    }

    @Test
    fun `My Cloud is removed, not disabled, when no debrid key is present`() {
        val summaries = listOf(
            system(1, SystemCollections.CONTINUE_WATCHING_NAME),
            system(2, SystemCollections.MY_CLOUD_NAME),
            user(3, "Sunday night"),
        )

        assertEquals(listOf(1L, 2L, 3L), FolderGrid.cards(summaries, true).map(FolderCardModel::id))
        assertEquals(listOf(1L, 3L), FolderGrid.cards(summaries, false).map(FolderCardModel::id))
    }

    @Test
    fun `an empty folder says Empty and My Cloud counts files`() {
        assertEquals("Empty", FolderGrid.card(user(1, "Sunday night", count = 0)).countLabel)
        assertEquals("1 title", FolderGrid.card(user(1, "Sunday night", count = 1)).countLabel)
        assertEquals("24 titles", FolderGrid.card(user(1, "Sunday night", count = 24)).countLabel)
        assertEquals(
            "8 items",
            FolderGrid.card(system(2, SystemCollections.MY_CLOUD_NAME, count = 8)).countLabel,
        )
    }

    @Test
    fun `the empty state is only for bare system folders`() {
        val bare = FolderGrid.cards(listOf(system(1, SystemCollections.CONTINUE_WATCHING_NAME)), false)
        assertTrue(FolderGrid.isEmpty(bare))

        val withOwn = FolderGrid.cards(
            listOf(system(1, SystemCollections.CONTINUE_WATCHING_NAME), user(2, "Sunday night")),
            false,
        )
        assertFalse(FolderGrid.isEmpty(withOwn))
    }

    @Test
    fun `the count line agrees with the cards drawn`() {
        val cards = FolderGrid.cards(
            listOf(system(1, SystemCollections.MY_CLOUD_NAME), user(2, "Sunday night")),
            hasDebridKey = false,
        )
        assertEquals("1 collection", FolderGrid.countLine(cards))
    }

    @Test
    fun `account library can show its real count without a duplicate copied phone folder`() {
        val account = FolderGrid.card(system(7, SystemCollections.MY_CLOUD_NAME)).copy(count = 123)
        assertEquals("My Library", account.name)
        assertEquals("123 items", account.countLabel)
        assertEquals("Loading…", account.copy(count = 0, countNotice = "Loading…").countLabel)
        val phone = Shelf("library:phone", "Library", ShelfKind.LIBRARY, emptyList(), "phone", 1)
        assertFalse(SyncedLists.visible(listOf(phone)).any { it.kind == ShelfKind.LIBRARY })
    }

    // ---------------------------------------------------------------- card geometry

    @Test
    fun `directory stacks skip missing artwork and retain saved fallbacks`() {
        val saved = "https://image.tmdb.org/t/p/w342/saved.jpg"
        val direct = "https://a.ltrbxd.com/resized/film-poster/heart.jpg"
        val cached = "https://a.ltrbxd.com/resized/film-poster/elsewhere.jpg"
        val missing = (1..10).map { CatalogItem("tmdb:$it", "movie", "Missing $it") }
        val items = missing + CatalogItem("tmdb:11", "movie", "Heart", posterUrl = saved, year = 1990)
        val shelf = Shelf("letterboxd:liked:owner/heart", "Heart", ShelfKind.LETTERBOXD, items, "iphone-synced", 1)
        val preview = listOf(
            com.fourseveneightnine.tv.catalog.TVLetterboxdFilm("absent", "Absent", null),
            com.fourseveneightnine.tv.catalog.TVLetterboxdFilm("heart", "Heart (1990)", direct),
            com.fourseveneightnine.tv.catalog.TVLetterboxdFilm("elsewhere", "Elsewhere", "https://letterboxd.com/film/elsewhere/image-150/"),
            com.fourseveneightnine.tv.catalog.TVLetterboxdFilm("blocked", "Blocked", "https://letterboxd.com/film/blocked/image-150/"),
        )
        val stamp = com.fourseveneightnine.tv.catalog.TVLetterboxdListRefresh.Stamp("owner/heart", 2, 25, preview)
        val covers = SyncedLists.directoryPosters(shelf, stamp) { if (it == "elsewhere") cached else null }
        assertEquals(listOf(direct, cached), covers.map { it.posterUrl })
        assertEquals(saved, covers.first().posterFallbackUrl)
        assertTrue(covers.all { it.posterLookupId == null })
        assertEquals(listOf(saved), SyncedLists.directoryPosters(shelf, null) { null }.map { it.posterUrl })
        assertTrue(SyncedLists.directoryPosters(shelf.copy(items = missing), null) { null }.isEmpty())
    }

    @Test
    fun `public list cards retain unambiguous saved artwork and metadata`() {
        val film = com.fourseveneightnine.tv.catalog.TVLetterboxdFilm(
            "matched", "Matched (1990)", "https://letterboxd.com/film/matched/image-150/",
        )
        val saved = com.fourseveneightnine.tv.client.ui.screens.home.HomeCard(
            "tmdb:42", "series", "Matched", posterUrl = "https://image.tmdb.org/t/p/w342/poster.jpg",
            year = 1990, posterLookupId = "tmdb:42", tmdbID = 42,
        )
        val matched = SyncedLists.publicCards(listOf(film), listOf(saved)).single()
        assertEquals(saved.posterUrl, matched.posterUrl)
        assertEquals("tmdb:42", matched.posterLookupId)
        assertEquals("letterboxd-film:matched", matched.id)
        assertEquals("series", matched.type)
        val missing = SyncedLists.publicCards(listOf(film), listOf(saved.copy(posterUrl = null))).single()
        assertNull(missing.posterUrl)
        assertEquals("tmdb:42", missing.posterLookupId)
        val ambiguous = SyncedLists.publicCards(listOf(film), listOf(saved, saved.copy(id = "tmdb:43"))).single()
        assertEquals(film.poster, ambiguous.posterUrl)
        assertEquals("letterboxd-film:matched", ambiguous.posterLookupId)
        assertEquals(1990, ambiguous.year)

        val direct = "https://a.ltrbxd.com/resized/film-poster/matched.jpg"
        val fromPage = SyncedLists.publicCards(listOf(film.copy(poster = direct)), listOf(saved)).single()
        assertEquals(direct, fromPage.posterUrl)
        assertEquals(saved.posterUrl, fromPage.posterFallbackUrl)
        assertEquals(fromPage, SyncedLists.publicCards(listOf(film), listOf(saved)) { direct }.single())
        val unsafe = SyncedLists.publicCards(
            listOf(film.copy(poster = "https://a.ltrbxd.com.example.org/matched.jpg")), listOf(saved),
        ).single()
        assertEquals(saved.posterUrl, unsafe.posterUrl)
        assertNull(unsafe.posterFallbackUrl)
    }

    @Test
    fun `only Letterboxd slots reorder by film count with stable ties`() {
        fun shelf(id: String, kind: ShelfKind, films: Int, shows: Int = 0) = Shelf(
            id, id, kind,
            (1..films).map { CatalogItem("$id-film-$it", "movie", "Film $it") } +
                (1..shows).map { CatalogItem("$id-show-$it", "series", "Show $it") },
            "generation", 1L,
        )
        val smaller = shelf("small", ShelfKind.LETTERBOXD, films = 1, shows = 6)
        val largest = shelf("largest", ShelfKind.LETTERBOXD, films = 2)
        val equal = shelf("equal", ShelfKind.LETTERBOXD, films = 1)
        val tmdb = shelf("tmdb", ShelfKind.TMDB_MOVIES, films = 8)
        val friends = shelf("friends", ShelfKind.LETTERBOXD_FRIENDS, films = 9)
        val ordered = SyncedLists.visible(listOf(smaller, tmdb, largest, friends, equal))

        assertEquals(listOf("tamilmv:collection", "largest", "tmdb", "small", "friends", "equal"),
            ordered.map(Shelf::id))
        assertEquals("1 film · 6 shows", SyncedLists.directoryCountLabel(smaller))
        assertEquals("8 titles", SyncedLists.directoryCountLabel(tmdb))
    }

    @Test
    fun `the collection grid band is six cards and five gaps, not the whole screen`() {
        assertEquals(1516, CollectionGrid.BAND_WIDTH)
        // Every column lands on the spec's x values, measured from the 220 px gutter.
        val columns = (0 until CollectionGrid.COLUMNS).map {
            220 + it * (CollectionGrid.CARD_WIDTH + CollectionGrid.COLUMN_GAP)
        }
        assertEquals(listOf(220, 476, 732, 988, 1244, 1500), columns)
    }

    @Test
    fun `Collections exposes every synced shelf name without a display cap`() {
        val shelves = (0 until 140).map { index ->
            Shelf(
                id = "list-$index",
                title = "List $index",
                kind = ShelfKind.LETTERBOXD,
                items = listOf(CatalogItem("tt$index", "movie", "Title $index")),
                generation = "g1",
                generatedAtMillis = 1L,
            )
        } + Shelf(
            id = "empty",
            title = "Empty",
            kind = ShelfKind.LETTERBOXD,
            items = emptyList(),
            generation = "g1",
            generatedAtMillis = 1L,
        )

        val visible = SyncedLists.visible(shelves)

        assertEquals(142, visible.size)
        assertEquals("tamilmv:collection", visible.first().id)
        assertEquals("TamilMV Collection", visible.first().title)
        assertEquals("list-0", visible[1].id)
        assertEquals("empty", visible.last().id)
        assertEquals("1 title", SyncedLists.countLabel(visible[1]))
        assertEquals("4 collections · 142 lists", SyncedLists.directoryLabel(4, visible.size))
        assertEquals("Letterboxd", SyncedLists.sourceLabel(visible[1]))
    }

    // ---------------------------------------------------------------- accents

    @Test
    fun `a card's accent is parsed once and matches the palette`() {
        val card = FolderGrid.card(user(1, "Sunday night").copy(accent = Accents.HEXES[2]))
        assertEquals(Accents.PALETTE[2], card.accent)
        assertEquals(Accents.PALETTE[2], ChecklistRow(1, "x", Accents.HEXES[2], 0, false, false).accent)
    }

    @Test
    fun `an unreadable accent falls back rather than throwing`() {
        assertEquals(TvColor.TextMuted, accentColor("not a colour"))
        assertEquals(TvColor.TextMuted, accentColor("#GGGGGG"))
        assertEquals(Accents.PALETTE.first(), accentColor(Accents.DEFAULT))
    }

    // ---------------------------------------------------------------- stored sort

    @Test
    fun `the sort choice is stored under the collection's own key`() {
        assertEquals("collection_sort_7", CollectionSort.key(7L))
        assertEquals("collection_sort_12", CollectionSort.key(12L))
    }

    @Test
    fun `a stored sort choice reads back, and an unknown one reads as Added`() {
        SortMode.entries.forEach { mode ->
            assertEquals(mode, CollectionSort.read(CollectionSort.write(mode)))
        }
        assertEquals(SortMode.Added, CollectionSort.read(null))
        assertEquals(SortMode.Added, CollectionSort.read(""))
        assertEquals(SortMode.Added, CollectionSort.read("Sideways"))
    }

    // ---------------------------------------------------------------- move mode

    @Test
    fun `an arrow travels one place sideways and a whole row up or down`() {
        assertEquals(-1, MoveMode.delta(MoveDirection.Left, 4))
        assertEquals(1, MoveMode.delta(MoveDirection.Right, 4))
        assertEquals(-4, MoveMode.delta(MoveDirection.Up, 4))
        assertEquals(4, MoveMode.delta(MoveDirection.Down, 4))
        assertEquals(6, MoveMode.delta(MoveDirection.Down, 6))
    }

    @Test
    fun `a swap moves the picked item and shuffles the rest along`() {
        val order = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "a", "c", "d"), MoveMode.swapped(order, 0, 1))
        assertEquals(listOf("a", "c", "b", "d"), MoveMode.swapped(order, 2, -1))
        assertEquals(listOf("b", "c", "d", "a"), MoveMode.swapped(order, 0, 3))
    }

    @Test
    fun `a swap past either end changes nothing`() {
        val order = listOf("a", "b", "c")
        assertEquals(order, MoveMode.swapped(order, 0, -1))
        assertEquals(order, MoveMode.swapped(order, 2, 1))
        assertEquals(order, MoveMode.swapped(order, 1, 0))
        assertEquals(order, MoveMode.swapped(order, 9, 1))
    }

    @Test
    fun `BACK walks the whole journey back in one step`() {
        assertEquals(0, MoveMode.undoDelta(emptyList()))
        assertEquals(-1, MoveMode.undoDelta(listOf(1)))
        assertEquals(-5, MoveMode.undoDelta(listOf(1, 4)))
        assertEquals(0, MoveMode.undoDelta(listOf(4, -4)))
    }

    @Test
    fun `undoing a run of swaps lands on the order it started from`() {
        val start = listOf("a", "b", "c", "d", "e", "f")
        val applied = listOf(1, 1, -1)
        var order = start
        var index = 0
        applied.forEach { delta ->
            order = MoveMode.swapped(order, index, delta)
            index += delta
        }
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), MoveMode.swapped(order, index, MoveMode.undoDelta(applied)))
    }

    // ---------------------------------------------------------------- sort

    @Test
    fun `Added sort is the stored order, which is what move mode writes`() {
        val items = listOf(
            item("c", "Zodiac (2007)", sortIndex = 2),
            item("a", "Arrival (2016)", sortIndex = 0),
            item("b", "Dune Part Two (2024)", sortIndex = 1),
        )
        assertEquals(listOf("a", "b", "c"), CollectionGrid.sorted(items, SortMode.Added).ids())
    }

    @Test
    fun `Title sort ignores case and ignores the year in the stored title`() {
        val items = listOf(
            item("z", "zodiac (2007)", sortIndex = 0),
            item("a", "Arrival (2016)", sortIndex = 1),
            item("d", "Dune Part Two (2024)", sortIndex = 2),
        )
        assertEquals(listOf("a", "d", "z"), CollectionGrid.sorted(items, SortMode.Title).ids())
    }

    @Test
    fun `Year sort is newest first and parks unknown years at the end in added order`() {
        val items = listOf(
            item("old", "Zodiac (2007)", sortIndex = 0),
            item("none", "A Title With No Year", sortIndex = 1),
            item("new", "Dune Part Two (2024)", sortIndex = 2),
            item("none2", "Another Unknown", sortIndex = 3),
        )
        assertEquals(
            listOf("new", "old", "none", "none2"),
            CollectionGrid.sorted(items, SortMode.Year).ids(),
        )
    }

    @Test
    fun `a cell shows the title without its year and the year on its own`() {
        val cells = CollectionGrid.cells(listOf(item("a", "Dune Part Two (2024)")), SortMode.Added)
        assertEquals("Dune Part Two", cells.single().title)
        assertEquals(2024, cells.single().year)
    }

    @Test
    fun `a title that only looks like it has a year keeps it`() {
        assertNull(yearIn("Blade Runner 2049"))
        assertNull(yearIn("Se7en (1899)"))
        assertEquals(1999, yearIn("The Matrix (1999)"))
        assertEquals("The Matrix", titleWithoutYear("The Matrix (1999)"))
    }

    @Test
    fun `the meta line drops the stale half when there is nothing to say`() {
        assertEquals("24 titles", CollectionGrid.metaLine(24, null))
        assertEquals("1 title", CollectionGrid.metaLine(1, ""))
        assertEquals("24 titles · updated 2h ago", CollectionGrid.metaLine(24, "updated 2h ago"))
    }

    @Test
    fun `only a sourced folder gets a source line`() {
        assertNull(CollectionGrid.sourceLine(CollectionKind.MANUAL, null, null))
        assertNull(CollectionGrid.sourceLine(CollectionKind.SYSTEM, "system:my-cloud", null))
        assertEquals(
            "From Letterboxd · saran",
            CollectionGrid.sourceLine(CollectionKind.LETTERBOXD, "lb:saran", "saran"),
        )
        assertEquals(
            "From add-on · Cinemeta Trending",
            CollectionGrid.sourceLine(CollectionKind.CATALOG, "url|movie|top", "Cinemeta Trending"),
        )
    }

    // ---------------------------------------------------------------- checklist

    @Test
    fun `the checklist offers your folders only, with a tick where the title already is`() {
        val rows = Checklist.rows(
            listOf(
                system(1, SystemCollections.CONTINUE_WATCHING_NAME),
                user(2, "Sunday night", count = 12),
                user(3, "Rewatch pile", count = 31),
            ),
            containing = setOf(2L),
        )

        assertEquals(listOf(2L, 3L), rows.map(ChecklistRow::id))
        assertTrue(rows.first().checked)
        assertFalse(rows.last().checked)
    }

    @Test
    fun `OK adds an unchecked folder, removes a checked one, and does nothing to a sourced one`() {
        val rows = Checklist.rows(
            listOf(
                user(2, "Sunday night", count = 12),
                user(3, "Rewatch pile", count = 31),
                sourced(4, "Best of 2024", count = 50),
            ),
            containing = setOf(2L),
        )

        assertEquals(ChecklistAction.Remove, Checklist.action(rows[0]))
        assertEquals(ChecklistAction.Add, Checklist.action(rows[1]))
        assertEquals(ChecklistAction.Ignored, Checklist.action(rows[2]))
        assertEquals("sourced", rows[2].countLabel)
        assertEquals("31", rows[1].countLabel)
    }

    @Test
    fun `focus lands on the first row that can still take this title`() {
        val rows = Checklist.rows(
            listOf(sourced(1, "Best of 2024"), user(2, "Sunday night"), user(3, "Rewatch pile")),
            containing = setOf(2L),
        )
        assertEquals(2, Checklist.initialIndex(rows))

        val allChecked = Checklist.rows(
            listOf(user(2, "Sunday night"), user(3, "Rewatch pile")),
            containing = setOf(2L, 3L),
        )
        assertEquals(0, Checklist.initialIndex(allChecked))
    }

    // ---------------------------------------------------------------- sourced refresh

    @Test
    fun `a refresh replaces a sourced folder's titles and renumbers them`() {
        val detail = detail(
            CollectionKind.LETTERBOXD,
            items = listOf(item("old1", "Old One", sortIndex = 0), item("old2", "Old Two", sortIndex = 1)),
        )
        val incoming = listOf(item("new1", "New One", sortIndex = 7), item("new2", "New Two", sortIndex = 9))

        val written = SourcedRefresh.itemsFor(detail, incoming)

        assertEquals(listOf("new1", "new2"), written?.ids())
        assertEquals(listOf(0, 1), written?.map(CollectionItem::sortIndex))
    }

    @Test
    fun `a refresh never touches a manual folder or a system folder`() {
        val manual = detail(CollectionKind.MANUAL, items = listOf(item("mine", "Mine")))
        val builtIn = detail(CollectionKind.SYSTEM, items = listOf(item("mine", "Mine")))
        val incoming = listOf(item("theirs", "Theirs"))

        assertNull(SourcedRefresh.itemsFor(manual, incoming))
        assertNull(SourcedRefresh.itemsFor(builtIn, incoming))
        // The manual folder is untouched: the caller has nothing to write.
        assertEquals(listOf("mine"), manual.items.ids())
    }

    @Test
    fun `a source that repeats a title writes it once`() {
        val detail = detail(CollectionKind.CATALOG, items = emptyList())
        val incoming = listOf(item("a", "A"), item("a", "A again"), item("b", "B"))

        assertEquals(listOf("a", "b"), SourcedRefresh.itemsFor(detail, incoming)?.ids())
    }

    @Test
    fun `an unchanged source skips the write`() {
        val stored = listOf(item("a", "A", sortIndex = 0), item("b", "B", sortIndex = 1))
        assertTrue(SourcedRefresh.unchanged(stored, stored))
        assertFalse(SourcedRefresh.unchanged(stored, stored.reversed()))
        assertFalse(SourcedRefresh.unchanged(stored, stored + item("c", "C")))
    }

    @Test
    fun `source display changes refresh while storage timestamps do not`() {
        val stored = item("a", "Original (2020)").copy(posterUrl = "old-poster", addedAtMillis = 123L)
        val incoming = stored.copy(addedAtMillis = 0L, sortIndex = 9)
        assertTrue(SourcedRefresh.unchanged(listOf(stored), listOf(incoming)))
        for (changed in listOf(
            incoming.copy(title = "Corrected (2024)"),
            incoming.copy(mediaType = "series"),
            incoming.copy(posterUrl = "new-poster"),
        )) {
            assertFalse(SourcedRefresh.unchanged(listOf(stored), listOf(changed)))
        }
    }

    // ---------------------------------------------------------------- source refs and accents

    @Test
    fun `a catalog ref survives the round trip, pipes in the catalog id included`() {
        val ref = SourceRef.catalog("https://cinemeta.strem.io/manifest.json", "movie", "top|10")
        val parsed = SourceRef.parseCatalog(ref)

        assertEquals("https://cinemeta.strem.io/manifest.json", parsed?.manifestUrl)
        assertEquals("movie", parsed?.type)
        assertEquals("top|10", parsed?.id)
        assertNull(SourceRef.parseCatalog(null))
        assertNull(SourceRef.parseCatalog("nonsense"))
    }

    @Test
    fun `the eight accents round trip through hex`() {
        assertEquals(8, Accents.PALETTE.size)
        Accents.HEXES.forEachIndexed { index, hex ->
            assertEquals(Accents.PALETTE[index], accentColor(hex))
            assertEquals(index, Accents.indexOf(hex))
        }
        assertEquals(-1, Accents.indexOf("#123456"))
    }

    @Test
    fun `an unreadable accent falls back instead of crashing`() {
        assertEquals(com.fourseveneightnine.tv.client.ui.theme.TvColor.TextMuted, accentColor("nope"))
        assertEquals(com.fourseveneightnine.tv.client.ui.theme.TvColor.TextMuted, accentColor("#GGGGGG"))
    }

    @Test
    fun `a long name is clipped on one line, never wrapped`() {
        assertEquals("Sunday night", clip("Sunday night", 22))
        assertEquals("A very long collection…", clip("A very long collection name here", 24))
    }

    @Test
    fun `a stored title carries the year the library row cannot`() {
        assertEquals("Dune Part Two (2024)", storedTitle("Dune Part Two", 2024))
        assertEquals("Dune Part Two (2024)", storedTitle("Dune Part Two (2024)", 2024))
        assertEquals("Dune Part Two", storedTitle("Dune Part Two", null))
    }

    // ---------------------------------------------------------------- fixtures

    private fun List<CollectionItem>.ids(): List<String> = map(CollectionItem::canonicalId)

    private fun system(id: Long, name: String, count: Int = 0) = CollectionSummary(
        id = id,
        name = name,
        accent = SystemCollections.SYSTEM_ACCENT,
        kind = CollectionKind.SYSTEM,
        count = count,
        pinnedHome = false,
        previewPosters = emptyList(),
    )

    private fun user(id: Long, name: String, count: Int = 3) = CollectionSummary(
        id = id,
        name = name,
        accent = Accents.DEFAULT,
        kind = CollectionKind.MANUAL,
        count = count,
        pinnedHome = false,
        previewPosters = emptyList(),
    )

    private fun sourced(id: Long, name: String, count: Int = 3) =
        user(id, name, count).copy(kind = CollectionKind.LETTERBOXD)

    private fun item(id: String, title: String, sortIndex: Int = 0) = CollectionItem(
        canonicalId = id,
        mediaType = "movie",
        title = title,
        posterUrl = null,
        sortIndex = sortIndex,
        addedAtMillis = 0L,
    )

    private fun detail(kind: CollectionKind, items: List<CollectionItem>) = CollectionDetail(
        id = 1L,
        name = "Sunday night",
        accent = Accents.DEFAULT,
        kind = kind,
        sourceRef = "ref",
        pinnedHome = false,
        sortIndex = 0,
        updatedAtMillis = 0L,
        items = items,
    )
}
