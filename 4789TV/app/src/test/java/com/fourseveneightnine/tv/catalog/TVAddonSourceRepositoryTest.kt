package com.fourseveneightnine.tv.catalog

import com.fourseveneightnine.tv.settings.SettingsPairingCrypto
import com.fourseveneightnine.tv.settings.StoredTVSettings
import com.fourseveneightnine.tv.settings.StoredTVSettingsState
import com.fourseveneightnine.tv.settings.TVSettingsPersistence
import com.fourseveneightnine.tv.settings.ValidatedTVSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TVAddonSourceRepositoryTest {
    @Test
    fun settingsPolicyMergesEnabledAddonRoutesWithoutDisplayingCredentialHosts() {
        val settings = persistence(
            """
            {
              "format":"4789-settings","version":1,
              "aioStreamsURLText":"https://aio.example/secret/manifest.json",
              "mediaFusionURLText":"https://mf.example/config/manifest.json",
              "mediaFusionEnabled":false,
              "primaryManifestURLText":"https://aio.example/secret/manifest.json",
              "secondaryManifestURLText":"https://secondary.example/manifest.json",
              "sources":[
                {"id":"one","name":"Owner Addon","url":"https://owner.example/key/manifest.json","enabled":true,"isBuiltIn":false},
                {"id":"off","name":"Disabled","url":"https://off.example/manifest.json","enabled":false,"isBuiltIn":false}
              ]
            }
            """.trimIndent(),
        )

        val endpoints = TVAddonSettingsPolicy.endpoints(settings.load())

        assertEquals(listOf("AIOStreams", "Owner Addon", "Secondary addon"), endpoints.map { it.name })
        assertFalse(endpoints.map { it.name }.any { "example" in it })
    }

    @Test
    fun routePolicyPrefersIMDbPreservesConfiguredQueryAndRejectsPathInjection() {
        val item = TVTamilMVCatalogItem(
            id = "catalog::tmdb:99",
            imdbID = "tt1234567",
            tmdbID = 99,
            mediaType = "movie",
            title = "One",
        )
        val endpoint = TVAddonEndpoint("AIOStreams", "https://addon.example/config/abc/manifest.json?token=owner")

        assertEquals(listOf("tt1234567", "tmdb:99"), TVAddonRoutePolicy.identifiers(item))
        assertEquals(
            "https://addon.example/config/abc/stream/movie/tt1234567.json?token=owner",
            TVAddonRoutePolicy.streamURL(endpoint, item, "tt1234567"),
        )
        assertNull(TVAddonRoutePolicy.streamURL(endpoint, item, "../../private"))
        assertNull(TVAddonRoutePolicy.playableURL("file:///data/private.mp4"))
        assertNull(TVAddonRoutePolicy.playableURL("https://user:secret@cdn.example/movie.mkv"))
    }

    @Test
    fun routePolicyAddsCompactTMDBFallbackForFacetIDs() {
        val item = TVTamilMVCatalogItem(
            id = "tmdb:movie:743563",
            mediaType = "movie",
            title = "Vikram",
        )

        assertEquals(listOf("tmdb:movie:743563", "tmdb:743563"), TVAddonRoutePolicy.identifiers(item))
    }

    @Test
    fun seriesRouteIncludesExactSeasonEpisodeAndUsesStableEpisodeIdentity() {
        val item = TVTamilMVCatalogItem(
            id = "tmdb:series:1399",
            mediaType = "series",
            title = "Dark",
            tmdbID = 1399,
            season = 2,
            episode = 7,
        )
        val endpoint = TVAddonEndpoint("Episodes", "https://addon.example/manifest.json?token=owner")

        assertEquals(
            "https://addon.example/stream/series/tmdb:1399:2:7.json?token=owner",
            TVAddonRoutePolicy.streamURL(endpoint, item, "tmdb:1399"),
        )
        assertEquals(
            "https://addon.example/meta/series/tmdb:1399.json?token=owner",
            TVAddonRoutePolicy.metaURL(endpoint, item, "tmdb:1399"),
        )
        assertEquals("tmdb:series:1399:s2:e7", item.sourceKey)
        assertTrue(item.sourceKey != item.copy(season = 1, episode = 1).sourceKey)
    }

    @Test
    fun seriesWithoutEpisodeLoadsMetadataWithoutAmbiguousShowStream() = runTest {
        val settings = persistence(
            """{"format":"4789-settings","version":1,"sources":[
              {"id":"episodes","name":"Episodes","url":"https://addon.example/manifest.json","enabled":true,"isBuiltIn":false}
            ]}""",
        )
        val requested = mutableListOf<String>()
        val http = TVAddonHTTPClient { url, _ ->
            requested += url
            assertTrue("/meta/series/" in url)
            """{"meta":{"videos":[
              {"id":"tmdb:1399:1:1","season":1,"episode":1,"title":"Beginnings"},
              {"id":"tmdb:1399:1:2","season":1,"episode":2,"title":"Secrets"}
            ]}}""".encodeToByteArray()
        }

        val result = TVAddonSourceRepository(settings, http).find(
            TVTamilMVCatalogItem(id = "tmdb:series:1399", mediaType = "series", title = "Dark", tmdbID = 1399),
        )

        assertEquals(2, result.episodes.size)
        assertTrue(result.sources.isEmpty())
        assertEquals(1, requested.size)
        assertTrue(requested.single().contains("/meta/series/tmdb:series:1399.json"))
    }

    @Test
    fun selectedSeriesEpisodeQueriesOnlyExactStreamRoute() = runTest {
        val settings = persistence(
            """{"format":"4789-settings","version":1,"sources":[
              {"id":"episodes","name":"Episodes","url":"https://addon.example/manifest.json","enabled":true,"isBuiltIn":false}
            ]}""",
        )
        val requested = mutableListOf<String>()
        val http = TVAddonHTTPClient { url, _ ->
            requested += url
            if ("/meta/series/" in url) {
                """{"meta":{"videos":[{"id":"tmdb:1399:2:7","season":2,"episode":7,"title":"The White Devil"}]}}""".encodeToByteArray()
            } else {
                """{"streams":[{"url":"https://cdn.example/dark-s02e07.mkv","name":"1080p"}]}""".encodeToByteArray()
            }
        }

        val result = TVAddonSourceRepository(settings, http).find(
            TVTamilMVCatalogItem(id = "tmdb:series:1399", mediaType = "series", title = "Dark", tmdbID = 1399, season = 2, episode = 7),
        )

        assertEquals(1, result.sources.size)
        assertTrue(requested.any { "/stream/series/tmdb:series:1399:2:7.json" in it })
        assertTrue(requested.none { "/stream/series/tmdb:series:1399.json" in it })
    }

    @Test
    fun oneFailingAddonDoesNotHideDirectRowsFromAnotherAndTorrentRowsStayNonPlayable() = runTest {
        val item = TVTamilMVCatalogItem(
            id = "tt1234567",
            imdbID = "tt1234567",
            mediaType = "movie",
            title = "One",
        )
        val settings = persistence(
            """
            {"format":"4789-settings","version":1,"sources":[
              {"id":"bad","name":"Offline","url":"https://offline.example/manifest.json","enabled":true,"isBuiltIn":false},
              {"id":"good","name":"Owner Addon","url":"https://good.example/manifest.json","enabled":true,"isBuiltIn":false}
            ]}
            """.trimIndent(),
        )
        val http = TVAddonHTTPClient { url, _ ->
            if ("offline.example" in url) error("offline")
            """
            {"streams":[
              {"name":"Torrent only 4K","infoHash":"abc"},
              {"url":"https://cdn.example/signed/movie.mkv?token=secret","name":"Owner 1080p","description":"HEVC HDR","behaviorHints":{"filename":"One.1080p.mkv","videoSize":1073741824}},
              {"url":"javascript:bad","name":"Bad"}
            ]}
            """.trimIndent().encodeToByteArray()
        }
        val result = TVAddonSourceRepository(settings, http).find(item)

        assertEquals(2, result.attemptedAddons)
        assertEquals(1, result.failedAddons)
        assertEquals(1, result.torrentOnlyCount)
        assertEquals(1, result.sources.size)
        assertEquals("Owner Addon", result.sources.single().addonName)
        assertEquals("1080p", result.sources.single().quality)
        assertTrue(result.sources.single().url.startsWith("https://cdn.example/"))
    }

    @Test
    fun successfulAddonPlaceholderIsNotPresentedAsPlayable() = runTest {
        val settings = persistence(
            """{"format":"4789-settings","version":1,"sources":[
              {"id":"yuki","name":"Yukistreams","url":"https://yuki.example/manifest.json","enabled":true,"isBuiltIn":false}
            ]}""",
        )
        val http = TVAddonHTTPClient { _, _ ->
            """{"streams":[
              {"url":"https://yuki.example/assets/stream-errors/no-stream-found.mp4","title":"No playable streams found","behaviorHints":{"notWebReady":true}},
              {"url":"https://cdn.example/vikram.mkv","name":"1080p","behaviorHints":{"filename":"Vikram.1080p.mkv"}}
            ]}""".encodeToByteArray()
        }

        val result = TVAddonSourceRepository(settings, http).find(
            TVTamilMVCatalogItem(id = "tmdb:movie:743563", tmdbID = 743563, mediaType = "movie", title = "Vikram"),
        )

        assertEquals(1, result.sources.size)
        assertEquals("https://cdn.example/vikram.mkv", result.sources.single().url)
    }

    @Test
    fun cancellingSearchCancelsTheActiveAddonRequestAndLateGenerationIsRejected() = runTest {
        val entered = CompletableDeferred<Unit>()
        val never = CompletableDeferred<ByteArray>()
        val settings = persistence(
            """{"format":"4789-settings","version":1,"aioStreamsURLText":"https://addon.example/manifest.json"}""",
        )
        val item = TVTamilMVCatalogItem(
            id = "tt1234567",
            mediaType = "movie",
            title = "One",
        )
        val repository = TVAddonSourceRepository(
            settings = settings,
            http = TVAddonHTTPClient { _, _ -> entered.complete(Unit); never.await() },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        val job = async { repository.find(item) }
        testScheduler.runCurrent()
        entered.await()

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertTrue(TVAddonSourceLifecyclePolicy.accepts(2, 2, item.id, item.id))
        assertFalse(TVAddonSourceLifecyclePolicy.accepts(1, 2, item.id, item.id))
        assertFalse(TVAddonSourceLifecyclePolicy.accepts(2, 2, item.id, "other"))
    }

    private fun persistence(raw: String): TVSettingsPersistence = object : TVSettingsPersistence {
        override fun load(): StoredTVSettingsState = StoredTVSettingsState.Available(
            StoredTVSettings(raw, 1, 1, SettingsPairingCrypto.encodeBase64URL(ByteArray(32))),
        )
        override fun save(settings: ValidatedTVSettings, revision: Long, syncKey: ByteArray?) = true
        override fun clearSync() = true
        override fun clearAll() = true
    }
}
