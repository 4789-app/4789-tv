package com.fourseveneightnine.tv

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import com.fourseveneightnine.tv.catalog.TVLetterboxdFilmDetails
import com.fourseveneightnine.tv.catalog.TVLetterboxdIdentity
import com.fourseveneightnine.tv.catalog.TVLetterboxdList
import com.fourseveneightnine.tv.catalog.TVLetterboxdPage
import com.fourseveneightnine.tv.client.OfflineLetterboxd
import com.fourseveneightnine.tv.client.OfflineLetterboxdImporter
import com.fourseveneightnine.tv.client.data.meta.Meta
import com.fourseveneightnine.tv.client.data.meta.Episode
import androidx.navigation.compose.composable
import androidx.compose.ui.test.performImeAction
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collect
import com.fourseveneightnine.tv.player.ExoReceiverController
import com.fourseveneightnine.tv.protocol.ReceiverEvent
import com.fourseveneightnine.tv.protocol.ReceiverSnapshot
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import com.fourseveneightnine.tv.client.ui.components.PaintedArtwork
import com.fourseveneightnine.tv.client.ui.screens.collections.CollectionCover
import com.fourseveneightnine.tv.client.ui.screens.home.HOME_BROWSE_TAG
import com.fourseveneightnine.tv.client.ui.screens.collections.FolderGrid
import com.fourseveneightnine.tv.client.ui.screens.home.HomeCard
import com.fourseveneightnine.tv.client.ui.screens.home.HomeHero

import android.view.SurfaceHolder
import android.view.SurfaceView
import com.fourseveneightnine.tv.player.ReceiverEngine
import com.fourseveneightnine.tv.player.SwappableReceiverController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import java.io.IOException
import com.fourseveneightnine.tv.client.ui.screens.settings.SettingsKeys
import com.fourseveneightnine.tv.protocol.SeekCommand
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics
import android.app.Activity
import android.app.Instrumentation
import com.fourseveneightnine.tv.player.ExternalPlayerIntentPolicy
import com.fourseveneightnine.tv.protocol.InstalledExternalPlayer
import com.fourseveneightnine.tv.protocol.SubtitleSelection
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.subrip.SubripParser
import com.fourseveneightnine.tv.player.ExternalSubtitleFormatPolicy
import java.nio.charset.StandardCharsets
import android.content.pm.ApplicationInfo
import com.fourseveneightnine.tv.client.ui.screens.settings.StremioImportPanel
import com.fourseveneightnine.tv.client.ui.screens.live.LiveBackupPanel
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.focusGroup
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.BackHandler
import android.content.ActivityNotFoundException
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import com.fourseveneightnine.tv.client.ui.screens.calendar.CalendarMonth
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ComposeView
import android.view.ViewGroup
import androidx.room.withTransaction
import com.fourseveneightnine.tv.client.data.library.LibraryDatabase
import com.fourseveneightnine.tv.client.data.library.LibraryRepository
import com.fourseveneightnine.tv.client.data.streams.StreamRow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import kotlinx.coroutines.delay
import android.graphics.BitmapFactory
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.client.appGraph
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.playback.PlaybackSession
import com.fourseveneightnine.tv.client.playback.PlayRequest
import com.fourseveneightnine.tv.client.data.catalog.CatalogItem
import com.fourseveneightnine.tv.client.data.catalog.CatalogPlaybackSource
import com.fourseveneightnine.tv.client.data.catalog.Shelf
import com.fourseveneightnine.tv.client.data.catalog.ShelfKind
import com.fourseveneightnine.tv.catalog.TVLetterboxdFilm
import com.fourseveneightnine.tv.catalog.TVLetterboxdListRefresh
import com.fourseveneightnine.tv.client.ui.screens.collections.CollectionsScreen
import com.fourseveneightnine.tv.client.ui.MainActivity
import com.fourseveneightnine.tv.client.search.SystemSearchIntentParser
import com.fourseveneightnine.tv.client.profiles.ProfileSessionState
import com.fourseveneightnine.tv.client.ui.components.TvFocusable
import com.fourseveneightnine.tv.client.ui.components.TvFocusableWithMenu
import com.fourseveneightnine.tv.client.ui.screens.player.PlaybackLoadingSurface
import com.fourseveneightnine.tv.client.ui.screens.player.PlayerBrowsePanel
import com.fourseveneightnine.tv.client.ui.screens.home.toHomeContinueCard
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvTheme
import com.fourseveneightnine.tv.player.ReceiverPlaybackPhase
import com.fourseveneightnine.tv.protocol.NowPlayingArt
import com.fourseveneightnine.tv.protocol.NowPlayingArtwork
import com.fourseveneightnine.tv.protocol.OpenMediaRequest
import com.fourseveneightnine.tv.settings.TVSettingsBackupPolicy
import java.io.File
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
private val fixtureBase get() = requireNotNull(InstrumentationRegistry.getArguments().getString("fixtureBase"))

private fun requireDisposableEmulator() {
    check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish") { "A local emulator is required" }
    check(InstrumentationRegistry.getArguments().getString("disposable") == "true") {
        "Explicit disposable-emulator selection is required"
    }
    check(fixtureBase.startsWith("http://10.0.2.2:") || fixtureBase.startsWith("http://127.0.0.1:"))
}

private fun prepareFixture(manifestPath: String = "/manifest.json") = runBlocking {
    requireDisposableEmulator()
    val context = instrumentation.targetContext
    val name = if (manifestPath.startsWith("/dmm/")) "DMM Cast fixture" else "Local fixture"
    val raw = """{"format":"4789-settings","version":1,"sources":[{"name":"$name","url":"$fixtureBase$manifestPath","enabled":true}]}"""
    check(context.appGraph.settingsStore.save(TVSettingsBackupPolicy.validate(raw.encodeToByteArray()), 1L, null))
    instrumentation.runOnMainSync { context.clientGraph.start() }
    context.clientGraph.reloadSettingsNow()
    val catalog = withTimeout(15_000) { context.clientGraph.profiles.filterNotNull().first() }
    instrumentation.runOnMainSync { context.clientGraph.selectProfile(catalog.profiles.first().id) }
}

/** Seeds only the disposable emulator. It also supports profiling an unchanged Release APK. */
@RunWith(AndroidJUnit4::class)
class EmulatorFixtureSetupTest {
    @Test fun localSettingsAndProfile() = prepareFixture()
}

/** Run alone in a fresh app process before other native tests. */
@RunWith(AndroidJUnit4::class)
class StartupProfileIoTest {
    @Test fun coldOwnerBootstrapAvoidsMainProfileDisk() {
        requireDisposableEmulator()
        val context = instrumentation.targetContext
        val client = context.clientGraph
        assertNull("Run this class before the first ClientGraph.start", client.profiles.value)
        assertTrue("The fresh profile session must still be loading",
            client.profileSession.state.value is ProfileSessionState.Loading)
        // Only the disposable fixture's encrypted profile metadata is reset. Clearing this
        // SharedPreferences object also clears the instance already held by ProfileStore.
        check(context.getSharedPreferences("household-profiles-v1", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit())
        val violations = java.util.concurrent.ConcurrentLinkedQueue<String>()
        var previousPolicy: android.os.StrictMode.ThreadPolicy? = null
        try {
            instrumentation.runOnMainSync {
                previousPolicy = android.os.StrictMode.getThreadPolicy()
                android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .penaltyListener(java.util.concurrent.Executor { it.run() }) { violation ->
                        if (violation.stackTrace.any {
                                it.className.startsWith("com.fourseveneightnine.tv.client.profiles.ProfileStore")
                            }) {
                            violations.add(violation.javaClass.simpleName + "\n" +
                                violation.stackTrace.joinToString("\n"))
                        }
                    }.build())
            }
            ActivityScenario.launch(MainActivity::class.java).use {
                val catalog = runBlocking {
                    withTimeout(15_000) { client.profiles.filterNotNull().first() }
                }
                val unlocked = runBlocking {
                    withTimeout(15_000) {
                        client.profileSession.state.first { it is ProfileSessionState.Unlocked }
                    }
                } as ProfileSessionState.Unlocked
                assertEquals("The cleared fixture must recreate only Owner", 1, catalog.profiles.size)
                assertEquals(catalog.selectedProfileId, unlocked.profileId)
                instrumentation.runOnMainSync {
                    assertEquals(catalog.selectedProfileId, client.activeProfileId())
                    org.junit.Assert.assertSame("Unlocked Owner must expose its active library",
                        client.globalLibrary, client.activeLibrary)
                }
                instrumentation.waitForIdleSync()
                assertTrue("Profile bootstrap performed disk work on Main:\n" + violations.joinToString("\n"),
                    violations.isEmpty())
            }
        } finally {
            previousPolicy?.let { policy ->
                instrumentation.runOnMainSync { android.os.StrictMode.setThreadPolicy(policy) }
            }
        }
    }
}

/** Measures the real cache reader with the same 128 MiB media-buffer budget as the onn box. */
@RunWith(AndroidJUnit4::class)
class CatalogCacheReadTest {
    @Before fun guard() = requireDisposableEmulator()

    @Test fun bundledLetterboxdCardsSupplyEveryFilterWithoutNetwork() = runBlocking {
        val app = instrumentation.targetContext.applicationContext as android.app.Application
        org.junit.Assume.assumeTrue(app.assets.list("letterboxd-offline").orEmpty().contains("catalog.sqlite"))
        val owner = SupervisorJob()
        val scope = CoroutineScope(owner + Dispatchers.IO)
        val client = okhttp3.OkHttpClient.Builder().addInterceptor {
            error("The bundled catalog must not fetch metadata")
        }.build()
        try {
            val offline = com.fourseveneightnine.tv.client.OfflineLetterboxd(app, scope, client)
            val directory = offline.directory()
            val shelf = directory.shelves.firstOrNull { it.title == "Offline Filters Check" }
            org.junit.Assume.assumeNotNull(shelf)
            requireNotNull(shelf)
            val cards = offline.cards(shelf.id)
            assertEquals(2, directory.counts[shelf.id])
            assertEquals(listOf("tmdb:10001", "tmdb:10002"), cards.map { it.id })
            assertTrue(cards.all { !it.metadataLookupAllowed })
            assertTrue(cards[0].posterUrl.orEmpty().startsWith("file:///android_asset/"))
            assertTrue(app.assets.open(cards[0].posterUrl.orEmpty().substringAfter("android_asset/")).use {
                BitmapFactory.decodeStream(it) != null
            })
            val filters = com.fourseveneightnine.tv.client.ui.screens.discover.CatalogFilters(
                year = 2024, decade = 2020, includedGenres = setOf("Drama"), excludedGenres = setOf("Comedy"),
                language = "en", country = "United States", minimumRating = 7, maximumRating = 10,
                runtimeFrom = 80, runtimeTo = 150, mediaType = "movie", certification = "PG-13",
                actor = "Test Actor", director = "Test Director", studio = "Test Studio", keyword = "fixture",
                spokenLanguage = "en", releaseStatus = "Released", minimumVotes = 100, withTrailer = true,
                ratingSource = com.fourseveneightnine.tv.client.ui.screens.discover.CatalogRatingSource.IMDb,
            )
            assertEquals(listOf("tmdb:10001"), filters.apply(cards).map { it.id })
        } finally {
            owner.cancel()
        }
    }

    @Test fun optionalLetterboxdPostersPersistOnlyAfterDownload() = runBlocking {
        val app = instrumentation.targetContext.applicationContext as android.app.Application
        org.junit.Assume.assumeTrue(app.assets.list("letterboxd-offline/posters").orEmpty().contains("OfflineFixture.webp"))
        val bytes = app.assets.open("letterboxd-offline/posters/OfflineFixture.webp").use { it.readBytes() }
        val requestCount = java.util.concurrent.atomic.AtomicInteger()
        val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("/t/p/w185/UnpackagedFixture.jpg", chain.request().url.encodedPath)
            requestCount.incrementAndGet()
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body(bytes.toResponseBody("image/webp".toMediaType())).build()
        }.build()
        val owner = SupervisorJob()
        val scope = CoroutineScope(owner + Dispatchers.IO)
        val old = File(app.filesDir, "letterboxd-offline-posters/UnpackagedFixture.img")
        old.delete()
        try {
            val offline = com.fourseveneightnine.tv.client.OfflineLetterboxd(app, scope, client)
            val shelf = offline.directory().shelves.first { it.title == "Offline Filters Check" }
            assertTrue(offline.cards(shelf.id)[1].posterUrl.orEmpty().startsWith("https://"))
            assertEquals(0, requestCount.get())
            offline.toggleDownload()
            val result = kotlinx.coroutines.withTimeout(5_000) {
                offline.download.first { !it.running && it.message != null }
            }
            assertEquals(2, result.done)
            assertEquals(1, requestCount.get())
            val reopened = com.fourseveneightnine.tv.client.OfflineLetterboxd(app, scope, client)
            assertTrue(reopened.cards(shelf.id)[1].posterUrl.orEmpty().startsWith("file:///"))
            assertEquals(1, requestCount.get())
        } finally {
            owner.cancel()
            old.delete()
        }
    }

    @Test fun letterboxdImportReportsMatchesAndRetainsSavedListOnIncompleteRetry() = runBlocking {
        val app = instrumentation.targetContext.applicationContext as android.app.Application
        val owner = SupervisorJob()
        val scope = CoroutineScope(owner + Dispatchers.IO)
        val network = okhttp3.OkHttpClient.Builder().addInterceptor {
            error("The fixture uses injected metadata and needs no network")
        }.build()
        val offline = OfflineLetterboxd(app, scope, network)
        val choice = TVLetterboxdList("import-fixture", "local-audit", "local-audit")
        val continueReading = CompletableDeferred<Unit>()
        val staged = listOf(
            TVLetterboxdFilm("import-local-verified", "Verified Fixture (2024)", null),
            TVLetterboxdFilm("import-local-conflict", "Conflicting Fixture (2023)", null),
            TVLetterboxdFilm("import-local-unverified", "Unverified Fixture", null),
            TVLetterboxdFilm("import-local-limited", "Limited Fixture", null),
        )
        var incomplete = false
        val importer = OfflineLetterboxdImporter(scope, offline, { _, number ->
            val result = when {
                incomplete -> TVLetterboxdPage(staged.take(1), false, 4)
                number == 1 -> TVLetterboxdPage(staged.take(1), true, 4)
                else -> {
                    continueReading.await()
                    TVLetterboxdPage(staged.drop(1), false)
                }
            }
            (if (number == 1) "Local Audit" else null) to result
        }, { slug ->
            val id = when (slug) {
                "import-local-verified" -> "tmdb:900001"
                "import-local-conflict" -> "tmdb:900002"
                "import-local-limited" -> "tmdb:900003"
                else -> null
            }
            TVLetterboxdFilmDetails(id?.let { TVLetterboxdIdentity("movie", it) }, null)
        }) { type, id, tmdb ->
            when (tmdb) {
                900001 -> Meta(id, type, "Verified Fixture", year = 2024, genres = listOf("Drama"),
                    description = "Saved synopsis", tmdbID = tmdb, hasInlineStreams = true,
                    videos = listOf(Episode(1, 1, "Episode", id = "https://example.invalid/private-stream")))
                900002 -> Meta(id, type, "Different title", tmdbID = 111)
                else -> null
            }
        }
        try {
            kotlinx.coroutines.withTimeout(10_000) {
                while (!importer.start(choice)) delay(20)
                importer.status.first { it.running && it.fetched == 1 }
            }
            continueReading.complete(Unit)
            val result = kotlinx.coroutines.withTimeout(10_000) { importer.status.first { !it.running && it.saved == 4 } }
            assertEquals(2, result.verified)
            assertEquals(1, result.limited)
            assertEquals(1, result.unverified)
            assertEquals(1, result.conflicts)
            assertTrue(result.log.any { it.contains("Checked 4 of 4") })
            val shelfId = OfflineLetterboxd.PREFIX + choice.key
            val reopened = OfflineLetterboxd(app, scope, network)
            val audit = reopened.audits().single { it.key == choice.key }
            assertEquals(4, audit.saved)
            assertEquals(1, audit.conflicts)
            assertEquals(2, reopened.issues(audit).size)
            val cards = reopened.cards(shelfId)
            assertEquals(4, cards.size)
            assertEquals("Drama", cards.first().genre)
            assertTrue(cards.all { !it.metadataLookupAllowed })
            val meta = requireNotNull(reopened.importedDetail("movie", "tmdb:900001"))
            assertEquals("Saved synopsis", meta.description)
            assertTrue(meta.videos.isEmpty())
            assertFalse(meta.hasInlineStreams)
            incomplete = true
            kotlinx.coroutines.withTimeout(10_000) {
                while (!importer.start(choice)) delay(20)
                importer.status.first { !it.running && it.stage == "Import needs attention" }
            }
            assertEquals(4, reopened.cards(shelfId).size)
        } finally {
            owner.cancel()
        }
    }

    @Test fun largeLetterboxdCacheLoadsAlongsideVideoBuffer() {
        val directory = File(instrumentation.targetContext.cacheDir, "catalog-read-memory-fixture")
        directory.mkdirs()
        val context = object : android.content.ContextWrapper(instrumentation.targetContext) {
            override fun getFilesDir() = directory
            override fun getApplicationContext(): android.content.Context = this
        }
        val file = File(directory, "phone-letterboxd-mirror-v1.json")
        try {
            file.bufferedWriter().use { output ->
                output.write("4789-private-catalog-v2\n")
                output.write("""{"snapshot":{"generation":"iphone-letterboxd-memory","generatedAtMillis":100,"cachedAtMillis":100,"popular":[],"recent":[],"letterboxdShelves":[""")
                repeat(105) { shelf ->
                    if (shelf > 0) output.write(",")
                    output.write("""{"id":"letterboxd:liked:fixture/list-$shelf","title":"List $shelf","items":[""")
                    repeat(1_000) { item ->
                        if (item > 0) output.write(",")
                        val id = shelf * 1_000 + item
                        output.write("""{"id":"tmdb:$id","mediaType":"movie","title":"Film $id","year":2026,"posterURL":"https://images.example/poster-$id.jpg","overview":"A catalog plot synopsis kept for this list entry."}""")
                    }
                    output.write("]}")
                }
                output.write(""" ]},"syncOwner":null,"syncRevision":0,"syncTag":null}""")
            }
            val videoBuffer = Array(2_048) { index -> ByteArray(65_536).also { it[0] = index.toByte() } }
            Runtime.getRuntime().gc()
            val runtime = Runtime.getRuntime()
            val heapBefore = runtime.totalMemory() - runtime.freeMemory()
            val peak = java.util.concurrent.atomic.AtomicLong(heapBefore)
            val sampling = java.util.concurrent.atomic.AtomicBoolean(true)
            val sampler = Thread {
                while (sampling.get()) {
                    peak.accumulateAndGet(runtime.totalMemory() - runtime.freeMemory(), ::maxOf)
                    Thread.sleep(5)
                }
            }
            val allocatedBefore = android.os.Debug.getRuntimeStat("art.gc.bytes-allocated").toLong()
            val gcBefore = android.os.Debug.getRuntimeStat("art.gc.gc-time").toLong()
            val started = SystemClock.elapsedRealtime()
            sampler.start()
            val loaded = try {
                com.fourseveneightnine.tv.catalog.AtomicTVTamilMVCatalogCache(context, letterboxdOnly = true).load()
            } finally {
                sampling.set(false)
                sampler.join()
            }
            val allocated = android.os.Debug.getRuntimeStat("art.gc.bytes-allocated").toLong() - allocatedBefore
            val gcMillis = android.os.Debug.getRuntimeStat("art.gc.gc-time").toLong() - gcBefore
            val elapsed = SystemClock.elapsedRealtime() - started
            android.util.Log.i("4789CatalogMemory", "bytes=${file.length()} allocated=$allocated " +
                "peakDelta=${peak.get() - heapBefore} gcMs=$gcMillis elapsedMs=$elapsed " +
                "heapLimit=${runtime.maxMemory()} buffer=${videoBuffer.sumOf { it.size }}")
            assertEquals(105_000, loaded?.letterboxdShelves?.sumOf { it.items.size })
            assertEquals("Film 104999", loaded?.letterboxdShelves?.last()?.items?.last()?.title)
            assertEquals(127.toByte(), videoBuffer[127][0])
            assertTrue("Catalog read allocated $allocated bytes alongside the video buffer",
                allocated < 256L * 1024 * 1024)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun recordReaderPreservesEncodingReplayFloorAndAtomicBackup() {
        val directory = File(instrumentation.targetContext.cacheDir, "catalog-read-format-fixture")
        directory.mkdirs()
        val context = object : android.content.ContextWrapper(instrumentation.targetContext) {
            override fun getFilesDir() = directory
            override fun getApplicationContext(): android.content.Context = this
        }
        val file = File(directory, "phone-letterboxd-mirror-v1.json")
        fun cache() = com.fourseveneightnine.tv.catalog.AtomicTVTamilMVCatalogCache(context, letterboxdOnly = true)
        val title = "தமிழ் 🎬"
        val snapshot = com.fourseveneightnine.tv.catalog.TVTamilMVCatalogSnapshot(
            generation = "iphone-letterboxd-fixture", generatedAtMillis = 100, cachedAtMillis = 100,
            popular = emptyList(), recent = emptyList(),
            letterboxdShelves = listOf(com.fourseveneightnine.tv.catalog.TVLetterboxdShelf(
                "letterboxd:liked:fixture/list", "List", listOf(
                    com.fourseveneightnine.tv.catalog.TVTamilMVCatalogItem("tmdb:1", "movie", title))))
        )
        val key = ByteArray(32) { it.toByte() }
        try {
            cache().storeSynced(snapshot, "fixture", 7, key)
            val record = file.readText()
            assertEquals(snapshot, cache().load())
            assertEquals(7L, cache().syncRevision("fixture", key))
            assertEquals(0L, cache().syncRevision("another-receiver", key))
            cache().clear(discardSyncState = false)
            assertNull(cache().load())
            assertEquals(7L, cache().syncRevision("fixture", key))

            check(file.delete())
            File(file.path + ".bak").writeText(record)
            assertEquals(snapshot, cache().load())
            assertEquals(7L, cache().syncRevision("fixture", key))

            file.writeText(record.replace("\"syncRevision\":7", "\"syncRevision\":8"))
            org.junit.Assert.assertThrows(IllegalStateException::class.java) { cache().syncRevision("fixture", key) }
            file.writeText(record.replace("\"syncRevision\":7,", ""))
            assertNull(cache().load())
            org.junit.Assert.assertThrows(Exception::class.java) { cache().syncRevision("fixture", key) }
            file.writeText(record.replace("iphone-letterboxd-fixture", ""))
            assertNull(cache().load())

            val parts = record.replace(title, "ENCODING").split("ENCODING", limit = 2)
            file.writeBytes(parts[0].encodeToByteArray() + byteArrayOf(0xC3.toByte(), 0x28) + parts[1].encodeToByteArray())
            assertNull("Malformed UTF-8 must not be replaced with a valid title", cache().load())
            file.writeText(record + "{}")
            assertNull("Trailing JSON must remain invalid", cache().load())

            file.writeText(kotlinx.serialization.json.Json.encodeToString(
                com.fourseveneightnine.tv.catalog.TVTamilMVCatalogSnapshot.serializer(), snapshot))
            assertEquals(snapshot, cache().load())
            assertEquals(0L, cache().syncRevision("fixture", key))
            java.io.RandomAccessFile(file, "rw").use { it.setLength(32L * 1024 * 1024 + 2_049) }
            assertNull("Oversized records must remain invalid", cache().load())
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { cache().syncRevision("fixture", key) }
            check(file.delete())
            assertNull(cache().load())
            assertEquals(0L, cache().syncRevision("fixture", key))
        } finally {
            directory.deleteRecursively()
        }
    }
}

/** Native storage proof; uses only the already-required disposable emulator fixture. */
@RunWith(AndroidJUnit4::class)
class LegacyRecentsPurgeTest {
    @Before fun guard() = requireDisposableEmulator()


    @Test fun purgesOnlyLegacyRecentsWhileKeepingEncryptedResumeAndRoomProgress() = runBlocking {
        val context = instrumentation.targetContext
        val resume = com.fourseveneightnine.tv.startup.ResumePointStore(context)
        val previousResume = resume.load()
        val database = androidx.room.Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val library = LibraryRepository(database, now = { 1_000L })
        val signed = "https://fixture.example/live/fixture-user/fixture-password/7.ts?token=fixture-signed-token"
        val point = com.fourseveneightnine.tv.player.ResumePoint(url = signed, title = "Purge control",
            headers = mapOf("Authorization" to "Bearer fixture-header"), positionMillis = 120_000L,
            durationMillis = 900_000L, savedAtMillis = 1_000L, origin = "local")
        try {
            check(resume.save(point))
            library.recordLocalPlay("tt987654321", "movie", "Purge control")
            library.writeProgress("tt987654321", "movie", positionMs = 120_000L, durationMs = 900_000L)
            val history = library.playbackHistory().first()
            assertEquals(1, history.size)
            val legacy = context.getSharedPreferences("tv_recents_v1", android.content.Context.MODE_PRIVATE)
            check(legacy.edit().putString("items", """[{"url":"$signed","title":"Legacy control"}]""").commit())
            assertTrue(legacy.getString("items", null).orEmpty().contains("fixture-password"))

            assertTrue(com.fourseveneightnine.tv.client.data.purgeLegacyRecents(context))
            // Reacquire after native delete; a previously returned SharedPreferences can retain memory.
            assertFalse(context.getSharedPreferences("tv_recents_v1", android.content.Context.MODE_PRIVATE).contains("items"))
            assertEquals(point, resume.load())
            assertEquals(history, library.playbackHistory().first())
            assertTrue(com.fourseveneightnine.tv.client.data.purgeLegacyRecents(context))
        } finally {
            database.close()
            if (previousResume == null) resume.clear() else check(resume.save(previousResume))
            context.deleteSharedPreferences("tv_recents_v1")
        }
    }
}

/** Local fixtures exercise the real Continue Watching row, player, Room, and encrypted restart state. */
@RunWith(AndroidJUnit4::class)
class ContinueWatchingRecoveryUiTest {
    @get:Rule val compose = createComposeRule()
    @Before fun guard() = requireDisposableEmulator()

    @Test fun encryptedTitleHistoryIsBoundedAndSeparatesProfilesAndEpisodes() {
        val context = instrumentation.targetContext
        val store = com.fourseveneightnine.tv.startup.ResumePointStore(context)
        store.clearTitles()
        try {
            val original = com.fourseveneightnine.tv.player.ResumePoint(
                "https://fixture.invalid/secret-signed-link", title = "Film", origin = "local", profileId = "owner",
                catalogType = "series", catalogId = "fixture:history", season = 1, episode = 2,
                positionMillis = 123_456, savedAtMillis = 1_000, sourceRecipe = "exact-file-7")
            check(store.saveTitle(original))
            check(store.saveTitle(original.copy(profileId = "guest", positionMillis = 999)))
            check(store.saveTitle(original.copy(episode = 3, positionMillis = 456)))
            store.clear() // Clearing the last interrupted session must not erase title history.
            val fresh = com.fourseveneightnine.tv.startup.ResumePointStore(context)
            assertEquals(original, fresh.loadTitle("owner", "series", "fixture:history", 1, 2))
            assertEquals(999L, fresh.loadTitle("guest", "series", "fixture:history", 1, 2)?.positionMillis)
            assertNull(fresh.loadTitle("owner", "movie", "fixture:history", null, null))
            assertNull(fresh.loadTitle("owner", "series", "fixture:history", 2, 2))
            val ciphertext = context.getSharedPreferences("receiver-resume", android.content.Context.MODE_PRIVATE)
                .getString("encrypted-titles-v1", "").orEmpty()
            assertFalse(ciphertext.contains("secret-signed-link") || ciphertext.contains("exact-file-7"))
            repeat(101) { check(fresh.saveTitle(original.copy(catalogId = "fixture:bounded-$it"))) }
            assertNull(fresh.loadTitle("owner", "series", "fixture:bounded-0", 1, 2))
            assertEquals("fixture:bounded-100", fresh.loadTitle("owner", "series", "fixture:bounded-100", 1, 2)?.catalogId)
        } finally { store.clearTitles() }
    }

    /** Host runs this once, kills the app process, then runs resumeTitleFromHomeAfterProcessRestart. */
    @Test fun seedTwoPlayedTitlesAndStop() {
        prepareFixture()
        val context = instrumentation.targetContext
        val client = context.clientGraph
        val session = context.appGraph.playback
        val store = com.fourseveneightnine.tv.startup.ResumePointStore(context)
        store.clearTitles()
        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking(Dispatchers.Main) {
                withTimeout(15_000) { session.surfaceReadyState.first { it } }
                for ((suffix, position) in listOf("a" to 90_000L, "b" to 120_000L)) {
                    val request = PlayRequest("movie", "fixture:cw-$suffix", "Continue Fixture ${suffix.uppercase()}")
                    val services = client.services.filterNotNull().first()
                    val rows = services.search.search("movie", listOf(request.id)).first { it.done }.rows
                    val selected = rows.single { it.releaseName.contains("-Original") }
                    assertEquals(com.fourseveneightnine.tv.client.playback.PlayResult.Opened, client.playFlow.playRow(selected, request))
                    withTimeout(15_000) { session.phase.first { it == ReceiverPlaybackPhase.Playing } }
                    session.seekTo(position).getOrThrow()
                    withTimeout(10_000) { while (session.refreshSnapshot().positionSeconds < position / 1_000.0 - 1) delay(100) }
                    client.playFlow.captureStopProgress()
                }
                session.stop().getOrThrow()
                client.playFlow.clearCurrent()
                val profile = client.activeProfileId()?.toString()
                val a = requireNotNull(store.loadTitle(profile, "movie", "fixture:cw-a", null, null))
                val b = requireNotNull(store.loadTitle(profile, "movie", "fixture:cw-b", null, null))
                assertTrue(a.url.contains("original.mp4") && b.url.contains("original.mp4"))
                assertTrue(a.positionMillis in 89_000L..99_000L)
                assertTrue(b.positionMillis in 119_000L..129_000L)
                assertTrue(!a.sourceRecipe.isNullOrBlank())
            }
        }
    }

    @Test fun resumeTitleFromHomeAfterProcessRestart() = resumeFromHome(renewed = false)
    @Test fun expiredLinkRenewsTheSameSourceFromHome() = resumeFromHome(renewed = true)
    /** The host arms an engine-only failure after seeding; its prefix check still succeeds. */
    @Test fun acceptedResumeStartupFailureRenewsTheSameSourceFromHome() = resumeFromHome(renewed = true)
    /** The host delays a valid first media response; it never expires or replaces the source. */
    @Test fun slowValidSavedResumeStartsWithoutSwitchingSource() = resumeFromHome(renewed = false)

    @Test fun failedAcceptedHomeResumeCannotReplaceNewerPhonePlayback() {
        prepareFixture()
        val context = instrumentation.targetContext
        val session = context.appGraph.playback
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                val card = compose.onNodeWithContentDescription("Continue Fixture A", substring = true)
                compose.waitUntil(15_000) {
                    compose.onAllNodesWithContentDescription("Continue Fixture A", substring = true).fetchSemanticsNodes().isNotEmpty()
                }
                card.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                compose.waitUntil(10_000) {
                    val connection = (java.net.URL("$fixtureBase/control/state").openConnection() as java.net.HttpURLConnection)
                        .apply { connectTimeout = 1_000; readTimeout = 1_000 }
                    try {
                        connection.inputStream.bufferedReader().use { org.json.JSONObject(it.readText()).getBoolean("expired") }
                    } finally { connection.disconnect() }
                }
                runBlocking(Dispatchers.Main) {
                    val phone = OpenMediaRequest("$fixtureBase/clip.mp4?phone-takeover=1", title = "Newer phone fixture")
                    session.open(phone, PlaybackSession.Origin.Phone).getOrThrow()
                    withTimeout(15_000) { session.phase.first { it == ReceiverPlaybackPhase.Playing } }
                    val owner = session.generation
                    delay(21_000)
                    assertEquals(owner, session.generation)
                    assertEquals(PlaybackSession.Origin.Phone, session.origin.value)
                    assertEquals(phone.url, session.lastOpenMedia()?.url)
                    assertTrue(session.refreshSnapshot().positionSeconds > 15)
                }
                capture("continue-failed-open-keeps-newer-phone.png")
            } finally { runBlocking { session.stop(); context.clientGraph.playFlow.clearCurrent() } }
        }
    }

    private fun resumeFromHome(renewed: Boolean) {
        prepareFixture()
        val context = instrumentation.targetContext
        val client = context.clientGraph
        val session = context.appGraph.playback
        val saved = requireNotNull(com.fourseveneightnine.tv.startup.ResumePointStore(context)
            .loadTitle(client.activeProfileId()?.toString(), "movie", "fixture:cw-a", null, null))
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                compose.waitUntil(15_000) {
                    compose.onAllNodesWithContentDescription("Continue Fixture A", substring = true).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithContentDescription("Continue Fixture A", substring = true)
                    .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
                compose.waitUntil(5_000) {
                    runCatching { compose.onNodeWithContentDescription("Continue Fixture A", substring = true).assertIsFocused() }.isSuccess
                }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                compose.waitUntil(35_000) {
                    runBlocking(Dispatchers.Main) { session.phase.value == ReceiverPlaybackPhase.Playing &&
                        session.lastOpenMedia()?.url?.contains("original.mp4") == true }
                }
                runBlocking(Dispatchers.Main) {
                    assertEquals(saved.positionMillis, session.lastOpenMedia()?.startPositionMs)
                    val seconds = session.refreshSnapshot().positionSeconds
                    assertTrue("Resume must reach the saved playhead: $seconds", seconds >= saved.positionMillis / 1_000.0 - 1)
                    assertTrue(seconds < saved.positionMillis / 1_000.0 + 8)
                    assertEquals(renewed, requireNotNull(session.lastOpenMedia()).url.contains("epoch=2"))
                    assertTrue(requireNotNull(session.lastOpenMedia()).url.contains("title=fixture:cw-a"))
                    if (!renewed) assertEquals(saved.url, session.lastOpenMedia()?.url)
                    assertFalse(requireNotNull(session.lastOpenMedia()).url.contains("alternative.mp4"))
                }
                capture(if (renewed) "continue-renewed-same-source.png" else "continue-restart-same-source.png")
            } finally { runBlocking { session.stop(); client.playFlow.clearCurrent() } }
        }
    }

    @Test fun unavailableSavedSourceCannotAutoPickTheAlternative() {
        prepareFixture()
        val context = instrumentation.targetContext
        val client = context.clientGraph
        val session = context.appGraph.playback
        val store = com.fourseveneightnine.tv.startup.ResumePointStore(context)
        val profile = client.activeProfileId()?.toString()
        val saved = requireNotNull(store.loadTitle(profile, "movie", "fixture:cw-a", null, null))
        check(store.saveTitle(saved.copy(savedAtMillis = System.currentTimeMillis() - 3 * 60 * 60_000L)))
        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking(Dispatchers.Main) {
                val result = withTimeout(35_000) { client.playFlow.play(PlayRequest("movie", "fixture:cw-a",
                    "Continue Fixture A", resumeFromMs = saved.positionMillis)) }
                assertTrue(result is com.fourseveneightnine.tv.client.playback.PlayResult.Failed)
                assertFalse(session.lastOpenMedia()?.url?.contains("alternative.mp4") == true)
                assertEquals(saved.positionMillis, store.loadTitle(profile, "movie", "fixture:cw-a", null, null)?.positionMillis)
            }
        }
    }

    @Test fun resumeWithoutRecordedSourceRequiresAnExplicitChoice() {
        prepareFixture()
        val client = instrumentation.targetContext.clientGraph
        runBlocking(Dispatchers.Main) {
            assertEquals(com.fourseveneightnine.tv.client.playback.PlayResult.ShowList,
                client.playFlow.play(PlayRequest("movie", "fixture:cw-unrecorded", "Continue Fixture UNRECORDED",
                    resumeFromMs = 90_000)))
            assertNull(client.playFlow.activeSource.value)
        }
    }

    @Test fun missingAndBrokenPostersRecoverOnTheContinueRowAndPersist() {
        prepareFixture()
        val client = instrumentation.targetContext.clientGraph
        val library = client.activeLibrary
        val before = runBlocking {
            for (suffix in listOf("missing", "broken")) {
                val id = "fixture:cw-$suffix"
                library.recordLocalPlay(id, "movie", "Continue Fixture ${suffix.uppercase()}",
                    posterUrl = if (suffix == "broken") "$fixtureBase/broken.jpg" else null)
                library.writeProgress(id, "movie", positionMs = 90_000, durationMs = 360_000)
            }
            library.continueWatching().first().filter { it.canonicalId in setOf("fixture:cw-missing", "fixture:cw-broken") }
        }
        compose.setContent { TvTheme(reduceMotion = true) {
            val rows by androidx.compose.runtime.produceState(before, library) {
                library.continueWatching().collect { value = it.filter { row -> row.canonicalId in before.map { it.canonicalId } } }
            }
            Row(Modifier.fillMaxSize()) {
                rows.forEach { row ->
                    com.fourseveneightnine.tv.client.ui.screens.home.HomeContinueCardView(row.toHomeContinueCard(),
                        onClick = {}, onLongClick = {}, onFocused = {}, modifier = Modifier.testTag(row.canonicalId))
                }
            }
        } }
        for (row in before) compose.waitUntil(20_000) {
            runCatching {
                val pixels = compose.onNodeWithTag(row.canonicalId).captureToImage().toPixelMap()
                val center = pixels[pixels.width / 2, pixels.height / 2]
                center.blue > 0.7f && center.red < 0.2f
            }.getOrDefault(false)
        }
        val after = runBlocking { withTimeout(10_000) { library.continueWatching().first { rows ->
            before.all { previous -> rows.any { it.canonicalId == previous.canonicalId && it.posterUrl?.contains("/new.png") == true } }
        } } }
        before.forEach { previous -> assertEquals(previous.copy(posterUrl = after.single { it.canonicalId == previous.canonicalId }.posterUrl),
            after.single { it.canonicalId == previous.canonicalId }) }
        capture("continue-missing-and-broken-posters.png")
    }

    @Test fun metadataBackfillReachesBeyondSixCardsAndRetriesAnInitialFailure() {
        prepareFixture()
        val client = instrumentation.targetContext.clientGraph
        val library = client.activeLibrary
        val ids = (1..10).map { "fixture:cw-backfill-$it-${SystemClock.uptimeMillis()}" }
        runBlocking {
            ids.forEach { id ->
                library.recordLocalPlay(id, "movie", id)
                library.writeProgress(id, "movie", positionMs = 90_000, durationMs = 360_000)
            }
        }
        val holder = androidx.lifecycle.ViewModelStore()
        val viewModel = com.fourseveneightnine.tv.client.ui.screens.home.HomeViewModel(client)
        holder.put("continue-recovery", viewModel)
        try {
            viewModel.start()
            runBlocking { withTimeout(30_000) { library.continueWatching().first { rows ->
                ids.all { id -> rows.any { it.canonicalId == id && it.posterUrl?.contains("/new.png") == true } }
            } } }
        } finally { instrumentation.runOnMainSync { holder.clear() } }
    }

    private fun capture(name: String) {
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "remote-checks").apply { mkdirs() }
        check(UiDevice.getInstance(instrumentation).takeScreenshot(File(folder, name)))
    }
}

/** Opens the actual on-disk schema shipped before saved Watchlist recipes. */
@RunWith(AndroidJUnit4::class)
class LibraryUpgradeRegressionTest {
    @Before fun guard() = requireDisposableEmulator()

    @Test fun versionOneLibraryKeepsExistingRowsAndAcceptsSavedRecipes() = runBlocking {
        val context = instrumentation.targetContext
        val name = LibraryDatabase.profileName(java.util.UUID.randomUUID())
        val file = context.getDatabasePath(name)
        check(requireNotNull(file.parentFile).mkdirs() || file.parentFile.isDirectory)
        val schema = instrumentation.context.assets.open(
            "com.fourseveneightnine.tv.client.data.library.LibraryDatabase/1.json",
        ).bufferedReader().use { org.json.JSONObject(it.readText()).getJSONObject("database") }
        var migrated: LibraryDatabase? = null
        try {
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                val entities = schema.getJSONArray("entities")
                for (index in 0 until entities.length()) {
                    val entity = entities.getJSONObject(index)
                    val table = entity.getString("tableName")
                    db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices")
                    if (indices != null) for (item in 0 until indices.length()) {
                        db.execSQL(indices.getJSONObject(item).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (index in 0 until setup.length()) db.execSQL(setup.getString(index))
                db.version = 1
                db.execSQL("INSERT INTO collection VALUES(41,'Upgrade controls','#123456','user',NULL,3,1,1010)")
                db.execSQL("INSERT INTO collection_item VALUES(41,'tt-migration','movie','Upgrade item',NULL,5,1020)")
                db.execSQL("INSERT INTO watch_progress VALUES('tt-migration','movie',NULL,NULL,45000,600000,1030,'local')")
                db.execSQL("INSERT INTO recent VALUES('tt-migration','Upgrade item',NULL,NULL,'movie',NULL,NULL,1040,'local')")
                db.execSQL("INSERT INTO addon_health VALUES('https://fixture.invalid/manifest.json',1050,2,'retained')")
                db.execSQL("INSERT INTO job VALUES('upgrade-control','queued',1060,NULL,'retained')")
            }
            val database = LibraryDatabase.create(context, name)
            migrated = database
            assertEquals(45_000L, database.watchProgressDao().find("tt-migration")?.positionMs)
            assertEquals(3, database.openHelper.readableDatabase.version)
            assertNull(database.watchProgressDao().find("tt-migration")?.nextEpisode)
            assertEquals("Upgrade item", database.recentDao().find("tt-migration")?.title)
            val collection = requireNotNull(database.collectionDao().find(41))
            assertEquals("Upgrade controls", collection.name)
            assertTrue(collection.pinnedHome)
            val item = database.collectionDao().items(41).single()
            assertEquals("tt-migration", item.canonicalId)
            assertEquals(5, item.sortIndex)
            assertNull(item.savedSource)
            assertEquals(2, database.addonHealthDao().find("https://fixture.invalid/manifest.json")?.failCount)
            assertEquals("retained", database.jobDao().find("upgrade-control")?.message)
            database.collectionDao().insertItem(item.copy(savedSource = "{\"recipe\":\"retained\"}"))
            database.close()
            val reopened = LibraryDatabase.create(context, name)
            migrated = reopened
            assertEquals("{\"recipe\":\"retained\"}", reopened.collectionDao().savedSource("tt-migration"))
            assertEquals(45_000L, reopened.watchProgressDao().find("tt-migration")?.positionMs)
        } finally {
            migrated?.close()
            context.deleteDatabase(name)
        }
    }
}

@RunWith(AndroidJUnit4::class)
class RemoteResponseUiTest {
    @get:Rule val compose = createComposeRule()
    @Before fun guard() = requireDisposableEmulator()

    @Test fun catalogIdentifiersKeepTheirValueAcrossDetailAndSourcesNavigation() {
        lateinit var controller: androidx.navigation.NavHostController
        compose.setContent {
            controller = androidx.navigation.compose.rememberNavController()
            androidx.navigation.compose.NavHost(controller, startDestination = "home") {
                composable("home") { }
                composable(
                    com.fourseveneightnine.tv.client.ui.nav.Route.Detail.PATTERN,
                    arguments = listOf(
                        androidx.navigation.navArgument("type") { type = androidx.navigation.NavType.StringType },
                        androidx.navigation.navArgument("id") { type = androidx.navigation.NavType.StringType },
                    ),
                ) { }
                composable(
                    com.fourseveneightnine.tv.client.ui.nav.Route.Streams.PATTERN,
                    arguments = listOf(
                        androidx.navigation.navArgument("type") { type = androidx.navigation.NavType.StringType },
                        androidx.navigation.navArgument("id") { type = androidx.navigation.NavType.StringType },
                        androidx.navigation.navArgument("season") { type = androidx.navigation.NavType.IntType },
                        androidx.navigation.navArgument("episode") { type = androidx.navigation.NavType.IntType },
                    ),
                ) { }
            }
        }
        for (id in listOf("tt0114369", "fixture:part/one", "fixture:part?one=1", "fixture:part#one", "fixture:%2F")) {
            compose.runOnIdle {
                controller.navigate(com.fourseveneightnine.tv.client.ui.nav.Route.Detail("movie", id).path)
            }
            compose.runOnIdle { assertEquals(id, controller.currentBackStackEntry?.arguments?.getString("id")) }
            compose.runOnIdle {
                controller.navigate(com.fourseveneightnine.tv.client.ui.nav.Route.Streams("series", id, 2, 3).path)
            }
            compose.runOnIdle {
                val arguments = controller.currentBackStackEntry?.arguments
                assertEquals(id, arguments?.getString("id"))
                assertEquals(2, arguments?.getInt("season"))
                assertEquals(3, arguments?.getInt("episode"))
            }
        }
    }

    @Test fun reservedKeyFilterLearnsShortcutsAndPassesOutsideTheApp() {
        prepareFixture()
        val prefs = instrumentation.targetContext.appGraph.presentationPreferences
        val mappings = com.fourseveneightnine.tv.ui.RemoteButtonMappings
        val previous = prefs.getString(mappings.PREFERENCE_KEY, null)
        val filter = com.fourseveneightnine.tv.ui.RemoteButtonAccessibilityService()
        fun event(action: Int, code: Int) = KeyEvent(1_000L, 1_000L, action, code, 0)
        prefs.edit().remove(mappings.PREFERENCE_KEY).commit()
        mappings.save(prefs, KeyEvent.KEYCODE_MACRO_1, false, com.fourseveneightnine.tv.ui.RemoteButtonAction.Settings)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitUntil(15_000) {
                    var focused = false
                    scenario.onActivity { focused = it.hasWindowFocus() }
                    focused && runCatching {
                        compose.onNodeWithTag(HOME_BROWSE_TAG).assertIsDisplayed()
                    }.isSuccess
                }
                scenario.onActivity {
                    assertFalse(filter.filterKeyEvent(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME)))
                    assertFalse(filter.filterKeyEvent(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_4)))
                    assertFalse(filter.filterKeyEvent(event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_4)))
                    assertTrue(filter.filterKeyEvent(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MACRO_1)))
                    assertTrue(filter.filterKeyEvent(event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MACRO_1)))
                }
                compose.waitUntil(5_000) { compose.onAllNodesWithText("Remote buttons").fetchSemanticsNodes().isNotEmpty() }
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                compose.onNodeWithText("Remote buttons").assertIsFocused()
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                compose.onNodeWithText("Test buttons").assertIsFocused()
                compose.onNodeWithText("Extra buttons: Off").assertExists()
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                scenario.onActivity {
                    assertTrue(filter.filterKeyEvent(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_4)))
                    assertTrue(filter.filterKeyEvent(event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_4)))
                }
                assertTrue(compose.onAllNodesWithText("Received: Netflix shortcut", substring = true).fetchSemanticsNodes().isNotEmpty())
                screenshot("reserved-remote-capture.png")
                scenario.onActivity {
                    assertTrue(filter.filterKeyEvent(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_3)))
                }
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
                scenario.onActivity {
                    assertTrue(filter.filterKeyEvent(event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_3)))
                    assertFalse(filter.filterKeyEvent(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_3)))
                    assertFalse(filter.filterKeyEvent(event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_3)))
                }
            }
        } finally { prefs.edit().putString(mappings.PREFERENCE_KEY, previous).commit() }
    }

    @Test fun customRemoteButtonsReachSettingsPersistAndRestoreDefaults() {
        prepareFixture()
        val prefs = instrumentation.targetContext.appGraph.presentationPreferences
        val mappings = com.fourseveneightnine.tv.ui.RemoteButtonMappings
        prefs.edit().remove(mappings.PREFERENCE_KEY).commit()
        mappings.save(prefs, KeyEvent.KEYCODE_CHANNEL_UP, false, com.fourseveneightnine.tv.ui.RemoteButtonAction.Settings)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitUntil(15_000) {
                    var focused = false
                    scenario.onActivity { focused = it.hasWindowFocus() }
                    focused && runCatching {
                        compose.onNodeWithTag(HOME_BROWSE_TAG).assertIsDisplayed()
                    }.isSuccess
                }
                key(KeyEvent.KEYCODE_CHANNEL_UP)
                compose.waitUntil(5_000) { compose.onAllNodesWithText("Remote buttons").fetchSemanticsNodes().isNotEmpty() }
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                compose.onNodeWithText("Remote buttons").assertIsFocused()
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                compose.onNodeWithText("Test buttons").assertIsFocused()
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                key(KeyEvent.KEYCODE_CHANNEL_DOWN)
                compose.onNodeWithText("When: Playing video").assertIsFocused()
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                compose.onNodeWithText("Play / pause").assertIsFocused()
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                assertEquals(com.fourseveneightnine.tv.ui.RemoteButtonAction.PlayPause,
                    mappings.read(prefs).first { it.keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN }.action)
                screenshot("remote-buttons-assigned.png")
                scenario.recreate()
                compose.waitForIdle()
                assertEquals(2, mappings.read(prefs).size)
                // Settings ignores the browse override, so normal D-pad/OK and Back remain usable.
                compose.waitUntil(10_000) {
                    var focused = false
                    scenario.onActivity { focused = it.hasWindowFocus() }
                    focused && compose.onAllNodesWithText("Remote buttons").fetchSemanticsNodes().isNotEmpty()
                }
                screenshot("remote-buttons-recreated.png")
                if (runCatching { compose.onNodeWithText("Remote buttons").assertIsFocused() }.isFailure)
                    key(KeyEvent.KEYCODE_DPAD_RIGHT)
                compose.onNodeWithText("Remote buttons").assertIsFocused()
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                compose.onNodeWithText("Test buttons").assertIsFocused()
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                key(KeyEvent.KEYCODE_DPAD_LEFT)
                assertTrue(compose.onAllNodesWithText("Received: Left", substring = true).fetchSemanticsNodes().isNotEmpty())
                screenshot("remote-buttons-capture.png")
                key(KeyEvent.KEYCODE_BACK)
                compose.onNodeWithText("Test buttons").assertIsFocused()
                // Stop on the reset row regardless of which buttons the capture list contains.
                repeat(6) {
                    if (runCatching { compose.onNodeWithText("Restore default buttons").assertIsFocused() }.isFailure)
                        key(KeyEvent.KEYCODE_DPAD_DOWN)
                }
                compose.onNodeWithText("Restore default buttons").assertIsFocused()
                screenshot("remote-buttons-reset-focus.png")
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                assertTrue(mappings.read(prefs).isEmpty())
                key(KeyEvent.KEYCODE_BACK)
                compose.onNodeWithText("Remote buttons").assertIsFocused()
            }
        } finally {
            prefs.edit().remove(mappings.PREFERENCE_KEY).commit()
        }
    }

    @Test fun searchKeyboardShowsTitleCompletionsAndOpensTheSelectedSeries() = searchSuggestionFixture { device, opened ->
        val field = compose.onNodeWithContentDescription("Search query")
        field.performTextInput("sev")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Seven · 1995").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        val imePackage = android.provider.Settings.Secure.getString(instrumentation.targetContext.contentResolver,
            android.provider.Settings.Secure.DEFAULT_INPUT_METHOD).substringBefore('/')
        val movie = androidx.test.uiautomator.By.pkg(imePackage).textContains("Seven · 1995")
        val series = androidx.test.uiautomator.By.pkg(imePackage).textContains("Seven Seconds")
        val hasMovie = device.wait(androidx.test.uiautomator.Until.hasObject(movie), 10_000)
        screenshot("search-keyboard-candidates-inspected.png")
        device.dumpWindowHierarchy(File(instrumentation.targetContext.getExternalFilesDir(null), "remote-checks/search-keyboard-candidates.xml"))
        assertTrue(hasMovie)
        assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(series), 10_000))
        screenshot("search-keyboard-title-completions.png")
        field.performTextClearance()
        field.performTextInput("unmatchedzz")
        compose.waitForIdle()
        assertTrue(device.wait(androidx.test.uiautomator.Until.gone(series), 5_000))
        assertTrue(device.wait(androidx.test.uiautomator.Until.gone(movie), 5_000))
        field.performTextClearance()
        field.performTextInput("sev")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Seven · 1995").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(series), 10_000))
        device.pressDPadUp()
        device.pressDPadRight()
        screenshot("search-keyboard-candidate-focus.png")
        device.pressDPadCenter()
        compose.waitForIdle()
        screenshot("search-keyboard-after-native-pick.png")
        compose.waitUntil(5_000) { opened.get() != null }
        assertEquals("series" to "fixture:seven-seconds", opened.get())
        assertTrue(com.fourseveneightnine.tv.client.ui.screens.search.SearchSections.cleanRecents(
            SettingsKeys.readStringList(instrumentation.targetContext.appGraph.presentationPreferences,
                SettingsKeys.RECENT_SEARCHES)).contains("Seven Seconds"))
        screenshot("search-keyboard-title-picked.png")
    }

    @Test fun searchPostersRecoverMissingAndFailedCatalogImages() = searchSuggestionFixture { _, _ ->
        val client = instrumentation.targetContext.clientGraph
        val names = listOf("Poster recovery missing", "Poster recovery broken")
        runBlocking {
            val services = requireNotNull(client.services.value)
            val cards = names.mapIndexed { index, name ->
                val id = "fixture:ownership-search-poster-$index"
                requireNotNull(services.meta.meta("movie", id))
                assertTrue(requireNotNull(services.meta.peek("movie", id)).posterCandidates.isNotEmpty())
                com.fourseveneightnine.tv.client.data.catalog.CatalogItem(id, "movie", name, year = 2024,
                    posterUrl = if (index == 0) null else "$fixtureBase/missing-search-poster.png")
            }
            client.snapshots.setReceiverTamilMV(cards, System.currentTimeMillis())
        }
        compose.onNodeWithContentDescription("Search query").performTextInput("poster recovery")
        compose.onNodeWithContentDescription("Search query").performImeAction()
        for (name in names) {
            val card = compose.onNodeWithContentDescription("$name, 2024, Movies")
            compose.waitUntil(10_000) {
                runCatching {
                    val pixels = card.captureToImage().toPixelMap()
                    pixels[pixels.width / 2, pixels.height / 4].let { it.blue > 0.8f && it.red < 0.2f }
                }.getOrDefault(false)
            }
        }
        screenshot("search-poster-missing-and-http-fallback.png")
    }

    @Test fun searchTitleSuggestionsRemainSelectableWithTheRemote() = searchSuggestionFixture { device, opened ->
        compose.onNodeWithContentDescription("Search query").performTextInput("sev")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Suggestions").fetchSemanticsNodes().isNotEmpty()
        }
        val imePackage = android.provider.Settings.Secure.getString(instrumentation.targetContext.contentResolver,
            android.provider.Settings.Secure.DEFAULT_INPUT_METHOD).substringBefore('/')
        assertTrue(device.wait(Until.hasObject(By.pkg(imePackage)), 10_000))
        device.pressBack()
        assertTrue(device.wait(Until.gone(By.pkg(imePackage)), 10_000))
        compose.waitForIdle()
        device.pressDPadDown()
        val selected = compose.onNode(isFocused() and hasText(" · ", substring = true))
        val title = selected.fetchSemanticsNode().config[SemanticsProperties.Text].single().text.substringBeforeLast(" · ")
        screenshot("search-remote-title-focus.png")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { opened.get() != null }
        assertTrue(opened.get()?.first in setOf("movie", "series"))
        assertTrue(SettingsKeys.readStringList(instrumentation.targetContext.appGraph.presentationPreferences,
            SettingsKeys.RECENT_SEARCHES).contains(title))
    }

    @Test fun searchKeepsTheCurrentTitleWhileTypingWithATypo() = searchSuggestionFixture { device, _ ->
        val field = compose.onNodeWithContentDescription("Search query")
        field.performTextInput("heart of a be")
        val suggestion = compose.onNodeWithText("Heart of the Beast · 2026")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Heart of the Beast · 2026").fetchSemanticsNodes().isNotEmpty()
        }
        val imePackage = android.provider.Settings.Secure.getString(instrumentation.targetContext.contentResolver,
            android.provider.Settings.Secure.DEFAULT_INPUT_METHOD).substringBefore('/')
        val completion = By.pkg(imePackage).textContains("Heart of the Beast · 2026")
        assertTrue(device.wait(Until.hasObject(completion), 10_000))

        field.performTextInput("az")
        suggestion.assertExists()
        assertTrue(device.wait(Until.hasObject(completion), 10_000))
        screenshot("search-keyboard-prefix-typo.png")
    }

    private fun searchSuggestionFixture(check: (UiDevice, AtomicReference<Pair<String, String>?>) -> Unit) {
        prepareFixture()
        val context = instrumentation.targetContext
        val client = context.clientGraph
        val previous = client.snapshots.shelves().value.filter {
            it.id == "tamilmv:receiver"
        }.flatMap { it.items }
        val opened = AtomicReference<Pair<String, String>?>(null)
        val nav = java.lang.reflect.Proxy.newProxyInstance(
            com.fourseveneightnine.tv.client.ui.nav.ClientNav::class.java.classLoader,
            arrayOf(com.fourseveneightnine.tv.client.ui.nav.ClientNav::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "SearchSuggestionNav"
                "openDetail" -> { opened.set(requireNotNull(args)[0] as String to args[1] as String); null }
                else -> null
            }
        } as com.fourseveneightnine.tv.client.ui.nav.ClientNav
        try {
            runBlocking { client.snapshots.setReceiverTamilMV(listOf(
                com.fourseveneightnine.tv.client.data.catalog.CatalogItem("fixture:seven", "movie", "Seven", year = 1995),
                com.fourseveneightnine.tv.client.data.catalog.CatalogItem("fixture:magnificent-seven", "movie", "The Magnificent Seven", year = 1960),
                com.fourseveneightnine.tv.client.data.catalog.CatalogItem("fixture:seven-seconds", "series", "Seven Seconds", year = 2018),
                com.fourseveneightnine.tv.client.data.catalog.CatalogItem("fixture:beast-old", "movie", "Heart of a Beast", year = 1930),
                com.fourseveneightnine.tv.client.data.catalog.CatalogItem("fixture:beast-current", "movie", "Heart of the Beast", year = 2026),
            ), System.currentTimeMillis()) }
            compose.setContent { TvTheme(reduceMotion = true) {
                CompositionLocalProvider(com.fourseveneightnine.tv.client.ui.LocalShellState provides
                    androidx.compose.runtime.remember { com.fourseveneightnine.tv.client.ui.ShellState() }) {
                    com.fourseveneightnine.tv.client.ui.screens.search.SearchScreen(nav, editOnEntry = true)
                }
            } }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription("Search query").fetchSemanticsNodes().isNotEmpty()
            }
            check(UiDevice.getInstance(instrumentation), opened)
        } finally {
            runBlocking { client.snapshots.setReceiverTamilMV(previous, System.currentTimeMillis()) }
        }
    }

    @Test fun detailPosterRecoversAfterAnImageHttpFailure() {
        val suffix = SystemClock.uptimeMillis()
        compose.setContent { TvTheme(reduceMotion = true) {
            com.fourseveneightnine.tv.client.ui.screens.detail.DetailArtwork(
                url = "$fixtureBase/missing-poster-$suffix.png",
                fallbackUrls = listOf("$fixtureBase/new.png?poster=$suffix"),
                targetWidth = 300, fallback = "Poster fallback fixture",
                modifier = Modifier.width(200.dp).height(300.dp))
        } }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Poster fallback fixture").fetchSemanticsNodes().isEmpty()
        }
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        assertTrue("The fallback bitmap must paint blue pixels", (0 until pixels.height).any { y ->
            pixels[pixels.width / 2, y].let { it.blue > 0.8f && it.red < 0.2f }
        })
        screenshot("detail-poster-http-fallback.png")
    }

    @Test fun detailPosterAcceptsArtworkThatArrivesAfterThePrimaryFailed() {
        val suffix = SystemClock.uptimeMillis()
        var alternatives by mutableStateOf(emptyList<String>())
        compose.setContent { TvTheme(reduceMotion = true) {
            com.fourseveneightnine.tv.client.ui.screens.detail.DetailArtwork(
                url = "$fixtureBase/missing-late-poster-$suffix.png", fallbackUrls = alternatives,
                targetWidth = 300, fallback = "Late artwork fixture",
                modifier = Modifier.width(200.dp).height(300.dp))
        } }
        compose.onNodeWithText("Late artwork fixture").assertIsDisplayed()
        compose.runOnIdle { alternatives = listOf("$fixtureBase/new.png?late=$suffix") }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Late artwork fixture").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test fun detailBackdropRecoversAfterHttpAndAspectFailures() {
        val suffix = SystemClock.uptimeMillis()
        val wide = "$fixtureBase/detail-backdrop.png?fallback=$suffix"
        val square = "$fixtureBase/old.png?wrong-aspect=$suffix"
        var meta by mutableStateOf(com.fourseveneightnine.tv.client.data.meta.Meta(
            "fixture:art-$suffix", "movie", "Artwork fixture",
            backdrop = "$fixtureBase/missing-wide-$suffix.png", backdropFallbacks = listOf(square, wide)))
        var ready = false
        var glass: com.fourseveneightnine.tv.client.ui.components.TvGlassArtwork? = null
        compose.setContent { TvTheme(reduceMotion = true) {
            Box(Modifier.fillMaxSize().background(TvColor.Background)) {
                com.fourseveneightnine.tv.client.ui.screens.detail.Backdrop(meta,
                    onLandscapeReady = { ready = true }, onGlassArtwork = { glass = it }, dimmed = { false })
            }
        } }
        compose.waitUntil(10_000) { compose.runOnIdle { ready && glass != null } }
        assertTrue(PaintedArtwork.wasPainted(wide))
        assertFalse(PaintedArtwork.wasPainted(square))
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        assertTrue("The recovered landscape must paint green pixels", (pixels.width / 2 until pixels.width).any { x ->
            pixels[x, pixels.height / 4].let { it.green > it.red * 1.5f && it.green > 0.1f }
        })
        val displayedGlass = glass
        compose.runOnIdle {
            meta = meta.copy(backdrop = "$fixtureBase/missing-revision-$suffix.png", backdropFallbacks = emptyList())
        }
        compose.waitForIdle()
        assertTrue("A later metadata revision must keep the painted backdrop", glass === displayedGlass)
        val revised = compose.onRoot().captureToImage().toPixelMap()
        assertEquals(pixels[pixels.width * 3 / 4, pixels.height / 4], revised[revised.width * 3 / 4, revised.height / 4])
        screenshot("detail-backdrop-http-aspect-fallback.png")
    }

    @Test fun seriesSourcesWaitForEpisodeMetadataBeforeOpening() {
        val play = FocusRequester()
        val actions = FocusRequester()
        val opened = java.util.concurrent.atomic.AtomicInteger()
        var episodesReady by mutableStateOf(false)
        val meta = com.fourseveneightnine.tv.client.data.meta.Meta("fixture:partial-series", "series", "Known series",
            description = "Known series description", seasons = listOf(1))
        compose.setContent { TvTheme(reduceMotion = true) {
            com.fourseveneightnine.tv.client.ui.screens.detail.Hero(meta,
                ratings = com.fourseveneightnine.tv.client.data.meta.Ratings.NONE,
                loading = !episodesReady,
                directorFocus = androidx.compose.runtime.remember { FocusRequester() }, onCredit = { _, _, _ -> },
                inWatchlist = false, watchlistSaving = false, inFavorites = false, favoriteSaving = false,
                playLabel = if (episodesReady) "Play S1 E1" else "Loading episodes", playEnabled = episodesReady,
                sourcesEnabled = episodesReady, showTrailer = false,
                playModifier = Modifier.focusRequester(play), sourcesModifier = Modifier,
                synopsisFocus = androidx.compose.runtime.remember { FocusRequester() }, actionsFocus = actions,
                actionsModifier = Modifier.focusRequester(actions).focusGroup(), actionsDownFocus = null,
                onPlay = {}, onSources = { opened.incrementAndGet() }, onWatchlist = {}, onTrailer = {},
                onLove = {}, onSynopsis = {}, modifier = Modifier.padding(start = 96.dp, top = 54.dp))
            LaunchedEffect(Unit) { play.requestFocus() }
        } }
        compose.onNodeWithText("Known series description").assertIsDisplayed()
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithContentDescription("Sources").assertIsFocused().assertIsNotEnabled()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(0, opened.get())
        compose.runOnIdle { episodesReady = true }
        compose.onNodeWithContentDescription("Sources").assertIsFocused().assertIsEnabled()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, opened.get())
    }

    @Test fun cinematicMovieDockKeepsFullSynopsisAndAccessibleFocus() = cinematicDetails("short")
    @Test fun cinematicLongSynopsisReaderReturnsToItsLink() = cinematicDetails("long")
    @Test fun cinematicMissingSynopsisKeepsActionsReachable() = cinematicDetails("missing")
    @Test fun cinematicResumeKeepsItsAccessibleName() = cinematicDetails("resume")

    @Test fun cinematicDirectorOpensCreditsAndRestoresItsFocus() {
        prepareFixture()
        val context = instrumentation.targetContext
        val id = "fixture:cinematic-credits-${SystemClock.uptimeMillis()}"
        val uri = requireNotNull(SystemSearchIntentParser.detailUri(context.packageName + ".search", "movie", id))
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW).setData(Uri.parse(uri))).use {
            compose.waitUntil(15_000) {
                runCatching { compose.onNodeWithContentDescription("Play").assertIsFocused() }.isSuccess &&
                    runCatching { compose.onNodeWithContentDescription("Directed by Selvamani Selvaraj").assertIsDisplayed() }.isSuccess
            }
            key(KeyEvent.KEYCODE_DPAD_UP)
            compose.onNodeWithContentDescription("Directed by Selvamani Selvaraj").assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(5_000) {
                runCatching { compose.onNodeWithText("Selvamani Selvaraj").assertIsDisplayed() }.isSuccess &&
                    runCatching { compose.onNodeWithContentDescription("Kaantha", substring = true).assertIsFocused() }.isSuccess
            }
            compose.onNodeWithText("More credits are unavailable.").assertIsDisplayed()
            screenshot("detail-director-credits.png")
            key(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(5_000) {
                runCatching { compose.onNodeWithContentDescription("Directed by Selvamani Selvaraj").assertIsFocused() }.isSuccess
            }
            screenshot("detail-director-return.png")
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.onNodeWithContentDescription("Play").assertIsFocused()
        }
    }

    @Test fun castCreditOpensPersonPageAndReturnsToTheSameActor() {
        prepareFixture()
        val context = instrumentation.targetContext
        val id = "fixture:cinematic-short-${SystemClock.uptimeMillis()}"
        val uri = requireNotNull(SystemSearchIntentParser.detailUri(context.packageName + ".search", "movie", id))
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW).setData(Uri.parse(uri))).use {
            compose.waitUntil(15_000) { runCatching { compose.onNodeWithContentDescription("Play").assertIsFocused() }.isSuccess }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            val castFocus = listOf("Dulquer Salmaan", "Bhagyashri Borse", "Samuthirakani")
            fun focusedActor() = castFocus.firstOrNull { name ->
                runCatching { compose.onNodeWithContentDescription("$name, View credits").assertIsFocused() }.isSuccess
            }
            compose.waitUntil(5_000) { focusedActor() != null }
            val actor = requireNotNull(focusedActor())
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(10_000) {
                runCatching { compose.onNodeWithText(actor).assertIsDisplayed() }.isSuccess &&
                    runCatching { compose.onNodeWithContentDescription("Kaantha", substring = true).assertIsFocused() }.isSuccess
            }
            compose.onNodeWithText("More credits are unavailable.").assertIsDisplayed()
            compose.onNodeWithText("Search").assertIsNotDisplayed()
            screenshot("detail-actor-credits.png")
            key(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(5_000) { focusedActor() == actor }
            screenshot("detail-actor-return.png")
        }
    }

    @Test fun fanartKeyIsMaskedAndEncryptedOnTheReceiver() {
        val context = instrumentation.targetContext
        val fixtureKey = "fixture-fanart-local"
        val vault = context.appGraph.settingsStore
        val client = context.clientGraph
        val submitted = AtomicReference<String?>()
        try {
            compose.setContent { TvTheme(reduceMotion = true) {
                com.fourseveneightnine.tv.client.ui.screens.settings.ModalKeyboard(
                    header = "Fanart.tv personal key",
                    hint = "Enter your personal key from Fanart.tv.",
                    initialText = fixtureKey,
                    usernameInput = false,
                    secret = true,
                    onDone = { submitted.set(it) },
                    onCancel = {},
                )
            } }
            compose.onNodeWithText(fixtureKey).assertDoesNotExist()
            compose.onNodeWithText("•".repeat(fixtureKey.length)).assertIsDisplayed()
            val save = compose.onNodeWithContentDescription("Save")
            save.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
            save.assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(5_000) { submitted.get() != null }
            assertEquals("The masked keyboard should submit the entered key", fixtureKey, submitted.get())
            assertTrue("The normal receiver settings store should save the key",
                runBlocking { client.setFanartKey(submitted.get()) })
            assertEquals(fixtureKey, vault.loadFanartKey())
            val raw = context.getSharedPreferences("receiver-settings-v1", android.content.Context.MODE_PRIVATE)
                .getString("fanart-key-ciphertext", null)
            assertTrue("Fanart key ciphertext should be present", !raw.isNullOrBlank())
            assertFalse(raw.orEmpty().contains(fixtureKey))
        } finally {
            assertTrue(runBlocking { client.setFanartKey(null) })
        }
    }

    @Test fun darkTitleLogoStaysVisibleOnTheDetailBackground() {
        var logoUrl by mutableStateOf("$fixtureBase/episode-still-dark-logo.png")
        compose.setContent { TvTheme(reduceMotion = true) {
            Box(Modifier.fillMaxSize().background(TvColor.Background)) {
                com.fourseveneightnine.tv.client.ui.screens.detail.DetailArtwork(
                    url = logoUrl, logo = true, targetWidth = 1280, fallback = "Logo fallback fixture",
                    modifier = Modifier.padding(64.dp).width(620.dp).height(208.dp).testTag("contrast-logo"),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit)
            }
        } }
        compose.waitUntil(10_000) {
            val pixels = compose.onNodeWithTag("contrast-logo").captureToImage().toPixelMap()
            var light = 0
            for (y in 0 until pixels.height step 8) for (x in 0 until pixels.width step 8) {
                val p = pixels[x, y]
                if (p.red > 0.8f && p.green > 0.8f && p.blue > 0.8f) light++
            }
            light > 100 && compose.onAllNodesWithText("Logo fallback fixture").fetchSemanticsNodes().isEmpty()
        }
        compose.runOnIdle { logoUrl = "$fixtureBase/missing-title-logo-revision.png" }
        compose.onNodeWithText("Logo fallback fixture").assertDoesNotExist()
        screenshot("detail-dark-logo-contrast.png")
    }

    @Test fun seriesDockRevealsEpisodesAndRestoresTheirSourceFocus() {
        prepareFixture()
        val context = instrumentation.targetContext
        val today = java.time.LocalDate.now().toString()
        val id = "fixture:calendar-$today-${SystemClock.uptimeMillis()}"
        val uri = requireNotNull(SystemSearchIntentParser.detailUri(context.packageName + ".search", "series", id))
        val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(uri))
        ActivityScenario.launch<MainActivity>(intent).use {
            compose.waitUntil(15_000) {
                runCatching { compose.onNodeWithContentDescription("Play S1 E1").assertIsFocused() }.isSuccess
            }
            compose.onNodeWithText("Old progress").assertIsNotDisplayed()
            screenshot("detail-series-panorama-dock.png")
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.waitUntil(5_000) {
                runCatching { compose.onNodeWithContentDescription("Season 1, episode 1", substring = true).assertIsFocused() }.isSuccess
            }
            compose.onNodeWithText("Synopsis for the first episode.").assertIsDisplayed()
            compose.onNodeWithText("Typical 10m").assertIsDisplayed()
            compose.onNodeWithText("Play\nS1 E1").assertIsNotDisplayed()
            val firstSummary = compose.onNodeWithText("Synopsis for the first episode.").fetchSemanticsNode().boundsInRoot
            assertTrue("The focused episode summary uses the space directly below the title strip",
                firstSummary.top < UiDevice.getInstance(instrumentation).displayHeight * 0.25f)
            screenshot("detail-series-first-screen.png")
            key(KeyEvent.KEYCODE_DPAD_UP)
            try {
                compose.waitUntil(5_000) {
                    runCatching { compose.onNodeWithContentDescription("Season 1").assertIsFocused() }.isSuccess
                }
            } catch (failure: Throwable) {
                screenshot("detail-series-up-focus-failure.png")
                failure.addSuppressed(AssertionError(compose.onAllNodes(androidx.compose.ui.test.isFocused(), useUnmergedTree = true)
                    .fetchSemanticsNodes().joinToString("\n") { node -> "${node.boundsInRoot} ${node.config}" }))
                throw failure
            }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            compose.onNodeWithContentDescription("Season 3").assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(5_000) {
                runCatching { compose.onNodeWithText("Earlier row").assertIsDisplayed() }.isSuccess
            }
            compose.onNodeWithContentDescription("Season 3").assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.onNodeWithContentDescription("Season 3, episode 4", substring = true).assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            compose.onNodeWithContentDescription("Season 3, episode 5", substring = true).assertIsFocused()
            compose.onNodeWithText("Synopsis for the selected episode.").assertIsDisplayed()
            val summary = compose.onNodeWithText("Synopsis for the selected episode.").fetchSemanticsNode().boundsInRoot
            val controls = compose.onNodeWithContentDescription("Season 3").fetchSemanticsNode().boundsInRoot
            assertTrue("The focused synopsis stays above the rail controls", summary.bottom < controls.top)
            val card = compose.onNodeWithContentDescription("Season 3, episode 5", substring = true).fetchSemanticsNode().boundsInRoot
            val caption = compose.onNodeWithText("Selected row").fetchSemanticsNode().boundsInRoot
            assertTrue("The complete episode title stays in the viewport", caption.bottom < UiDevice.getInstance(instrumentation).displayHeight - 24f)
            assertEquals(16f / 9f, card.width / card.height, 0.02f)
            screenshot("detail-series-selected-episode.png")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("S3 E5 · Selected row ·", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            key(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(5_000) {
                runCatching { compose.onNodeWithContentDescription("Season 3, episode 5", substring = true).assertIsFocused() }.isSuccess
            }
            compose.onNodeWithText("Synopsis for the selected episode.").assertIsDisplayed()
            screenshot("detail-series-source-return.png")
        }
    }

    @Test fun singleSeasonEpisodeBrowserKeepsItsTitleAndFocusedSynopsis() {
        prepareFixture()
        val context = instrumentation.targetContext
        val id = "fixture:nextup-drawer-type-${SystemClock.uptimeMillis()}"
        val uri = requireNotNull(SystemSearchIntentParser.detailUri(context.packageName + ".search", "series", id))
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(uri))).use {
            compose.waitUntil(15_000) {
                runCatching { compose.onNodeWithContentDescription("Play S1 E1").assertIsFocused() }.isSuccess
            }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            repeat(5) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
            compose.waitUntil(5_000) {
                runCatching { compose.onNodeWithContentDescription("Season 1, episode 6", substring = true).assertIsFocused() }.isSuccess
            }
            compose.onNodeWithText("Sample synopsis for episode six.").assertIsDisplayed()
            assertTrue("The episode browser retains its title strip", compose.onAllNodesWithText("Carrie").fetchSemanticsNodes().isNotEmpty())
            try {
                compose.waitUntil(5_000) {
                    runCatching { compose.onNodeWithText("Home Economics").assertIsDisplayed() }.isSuccess
                }
            } catch (failure: Throwable) {
                screenshot("detail-series-horizontal-failure.png")
                throw failure
            }
            screenshot("detail-series-horizontal-browse.png")
            key(KeyEvent.KEYCODE_DPAD_UP)
            val oldestOrder = compose.onNodeWithText("Order: Oldest episode ▾")
            oldestOrder.assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            val oldest = compose.onNodeWithContentDescription("Oldest episode")
            compose.waitUntil(5_000) { runCatching { oldest.assertIsFocused() }.isSuccess }
            val pane = compose.onNode(androidx.compose.ui.test.SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.PaneTitle, "Episode order"))
                .fetchSemanticsNode().boundsInRoot
            val display = UiDevice.getInstance(instrumentation)
            screenshot("detail-series-compact-order-picker.png")
            assertTrue("Three choices need a compact picker. Bounds: $pane", pane.width < display.displayWidth * 0.30f &&
                pane.height < display.displayHeight * 0.38f)
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.onNodeWithContentDescription("Latest episode").assertIsFocused()
            key(KeyEvent.KEYCODE_BACK)
            oldestOrder.assertIsFocused()

            key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(5_000) { runCatching { oldest.assertIsFocused() }.isSuccess }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNodeWithText("Order: Latest episode ▾").assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_UP)
            compose.onNodeWithContentDescription("Play S1 E1").assertIsFocused()
        }
    }

    @Test fun posterLongOkLovesDirectlyWithoutACollectionPanel() {
        prepareFixture()
        val context = instrumentation.targetContext
        val id = "fixture:poster-love-${SystemClock.uptimeMillis()}"
        val focus = FocusRequester()
        var opened = 0
        val notices = mutableListOf<String>()
        val nav = java.lang.reflect.Proxy.newProxyInstance(
            com.fourseveneightnine.tv.client.ui.nav.ClientNav::class.java.classLoader,
            arrayOf(com.fourseveneightnine.tv.client.ui.nav.ClientNav::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "PosterLoveNav"
                "toast" -> { notices.add(args?.firstOrNull() as String); null }
                else -> null
            }
        } as com.fourseveneightnine.tv.client.ui.nav.ClientNav
        compose.setContent { TvTheme(reduceMotion = true) {
            CompositionLocalProvider(com.fourseveneightnine.tv.client.ui.LocalShellState provides androidx.compose.runtime.remember { com.fourseveneightnine.tv.client.ui.ShellState() }) {
            val love = com.fourseveneightnine.tv.client.ui.screens.collections.rememberLoveTitle(nav)
            com.fourseveneightnine.tv.client.ui.screens.home.HomePosterCard(
                card = HomeCard(id = id, type = "movie", title = "Poster love fixture", posterUrl = "$fixtureBase/new.png"),
                onClick = { opened++ }, onLongClick = {
                    love(com.fourseveneightnine.tv.client.ui.screens.collections.CollectionCandidate(id, "movie", "Poster love fixture", "$fixtureBase/new.png"))
                }, onFocused = {}, modifier = Modifier.padding(64.dp).focusRequester(focus))
            LaunchedEffect(Unit) { focus.requestFocus() }
            }
        } }
        compose.waitForIdle()
        repeat(2) {
            val down = SystemClock.uptimeMillis()
            event(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
            SystemClock.sleep(700)
            event(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 1)
            event(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(5_000) { notices.size == it + 1 }
        }
        assertEquals(0, opened)
        compose.onNodeWithContentDescription("Poster love fixture, movie").assertIsFocused()
        compose.onNodeWithText("Save to collections").assertDoesNotExist()
        runBlocking {
            val lists = context.clientGraph.activeLibrary.savedCollections().first()
            assertEquals(1, lists.first { it.sourceRef == com.fourseveneightnine.tv.client.data.library.SystemCollections.FAVORITES_REF }.items.count { it.canonicalId == id })
            assertTrue(lists.first { it.sourceRef == com.fourseveneightnine.tv.client.data.library.SystemCollections.WATCHLIST_REF }.items.none { it.canonicalId == id })
        }
        screenshot("poster-long-ok-loved.png")
    }

    @Test fun cinematicProviderLogosAndSolidFocusKeepTheirGeometry() {
        val play = FocusRequester()
        val sources = FocusRequester()
        val actions = FocusRequester()
        val backdropReady = java.util.concurrent.atomic.AtomicBoolean(false)
        val logoReady = java.util.concurrent.atomic.AtomicBoolean(false)
        val meta = com.fourseveneightnine.tv.client.data.meta.Meta("fixture:glass", "series", "The Lord of the Rings: The Rings of Power",
            year = 2022, seasons = listOf(1, 2, 3), genres = listOf("Fantasy", "Adventure"), runtimeMinutes = 68, logo = "$fixtureBase/detail-logo.png",
            description = "Epic drama set thousands of years before the events of J.R.R. Tolkien's 'The Hobbit' and 'The Lord of the Rings' follows an ensemble cast of characters, both familiar and new, as they confront the long-feared re-emergence of evil ...",
            directors = emptyList(),
            backdrop = "$fixtureBase/detail-backdrop.png",
            creators = listOf("J. D. Payne", "Patrick McKay"),
            streamingProviders = listOf(
                com.fourseveneightnine.tv.client.data.meta.StreamingProvider(9, "Amazon Prime Video", "$fixtureBase/new.png"),
                com.fourseveneightnine.tv.client.data.meta.StreamingProvider(2100, "Amazon Prime Video with Ads", "$fixtureBase/new.png")))
        compose.setContent { TvTheme(reduceMotion = false) {
            Box(Modifier.fillMaxSize().background(TvColor.Background)) {
                com.fourseveneightnine.tv.client.ui.screens.detail.Backdrop(meta, onLandscapeReady = { backdropReady.set(true) }) { false }
                com.fourseveneightnine.tv.client.ui.screens.detail.Hero(meta,
                    ratings = com.fourseveneightnine.tv.client.data.meta.Ratings(6.8, 71, 72, null),
                    loading = false, onLogoSettled = { logoReady.set(true) },
                    directorFocus = androidx.compose.runtime.remember { FocusRequester() }, onCredit = { _, _, _ -> },
                    inWatchlist = true, watchlistSaving = false, inFavorites = true, favoriteSaving = false, playLabel = "Play S1 E1", playEnabled = true, showTrailer = true,
                    playModifier = Modifier.focusRequester(play), sourcesModifier = Modifier.focusRequester(sources),
                    synopsisFocus = androidx.compose.runtime.remember { FocusRequester() }, actionsFocus = actions,
                    actionsModifier = Modifier.focusRequester(actions).focusGroup(), actionsDownFocus = null,
                    onPlay = {}, onSources = {}, onWatchlist = {}, onTrailer = {}, onLove = {}, onSynopsis = {},
                    modifier = Modifier.padding(start = 96.dp, top = 54.dp))
            }
            LaunchedEffect(Unit) { play.requestFocus() }
        } }
        compose.waitUntil(15_000) { backdropReady.get() && logoReady.get() }
        compose.onNodeWithContentDescription("Streaming service: Prime Video").assertIsDisplayed()
        compose.onNodeWithContentDescription("Streaming service: Netflix").assertDoesNotExist()
        compose.onNodeWithText("Where to watch").assertDoesNotExist()
        compose.onNodeWithText("Subscription").assertDoesNotExist()
        compose.onNodeWithText("via JustWatch").assertDoesNotExist()
        compose.onNodeWithText("View options").assertDoesNotExist()
        compose.onNodeWithText("Prime Video").assertDoesNotExist()
        val provider = compose.onNodeWithContentDescription("Streaming service: Prime Video").fetchSemanticsNode().boundsInRoot
        val rating = compose.onNodeWithText("72%").fetchSemanticsNode().boundsInRoot
        assertTrue("The single service logo sits beside ratings", provider.left > rating.right)
        assertTrue("Services are readable at TV distance", provider.height >= UiDevice.getInstance(instrumentation).displayWidth / 1920f * 63f)
        assertEquals(1, compose.onAllNodesWithContentDescription("Streaming service: Prime Video").fetchSemanticsNodes().size)
        val creator = compose.onNodeWithContentDescription("Created by J. D. Payne").fetchSemanticsNode().boundsInRoot
        assertTrue("Created by belongs at the top", creator.top < UiDevice.getInstance(instrumentation).displayHeight * 0.15f)
        val playIcon = compose.onNodeWithContentDescription("Play S1 E1").captureToImage().toPixelMap()
        assertEquals(TvColor.Accent.toArgb(), playIcon[playIcon.width / 2, playIcon.height / 2].toArgb())
        assertTrue("Play uses the icon fill as its focus indicator", (1..6).none { inset ->
            playIcon[playIcon.width / 2, playIcon.height - inset].toArgb() == TvColor.Accent.toArgb()
        })
        screenshot("detail-panorama-provider-groups.png")
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        key(KeyEvent.KEYCODE_DPAD_UP)
        compose.onNodeWithContentDescription("Streaming service: Prime Video").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText("Amazon Prime Video with Ads").assertIsDisplayed()
        key(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithContentDescription("Streaming service: Prime Video").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithContentDescription("Sources").assertIsFocused()
        val initial = compose.onNodeWithContentDescription("Sources").fetchSemanticsNode().boundsInRoot
        repeat(4) { key(KeyEvent.KEYCODE_DPAD_LEFT); key(KeyEvent.KEYCODE_DPAD_RIGHT) }
        compose.onNodeWithContentDescription("Sources").assertIsFocused()
        val settled = compose.onNodeWithContentDescription("Sources").fetchSemanticsNode().boundsInRoot
        assertEquals(initial, settled)
        screenshot("detail-solid-icons-providers.png")
        repeat(3) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
        compose.onNodeWithContentDescription("Trailer").assertIsFocused()
        screenshot("detail-solid-trailer-focus.png")
    }

    private fun cinematicDetails(kind: String) {
        prepareFixture()
        val context = instrumentation.targetContext
        val id = "fixture:cinematic-$kind-${SystemClock.uptimeMillis()}"
        val original = "In 1950s Madras, tension between a legendary director, an egotistical leading man, and a young starlet lead to murder on the film set."
        val description = if (kind == "long") List(6) { original }.joinToString(" ") else original
        val playLabel = if (kind == "resume") "Resume 3m in" else "Play"
        if (kind == "resume") runBlocking {
            context.clientGraph.activeLibrary.recordLocalPlay(id, "movie", "Kaantha")
            context.clientGraph.activeLibrary.writeProgress(id, "movie", positionMs = 180_000, durationMs = 9_000_000)
        }
        val uri = requireNotNull(SystemSearchIntentParser.detailUri(context.packageName + ".search", "movie", id))
        val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(uri))
        ActivityScenario.launch<MainActivity>(intent).use {
            compose.waitUntil(15_000) {
                runCatching { compose.onNodeWithContentDescription(playLabel).assertIsFocused() }.isSuccess &&
                    if (kind == "missing") compose.onAllNodesWithText("2025 · 2h 30m").fetchSemanticsNodes().isNotEmpty()
                    else compose.onAllNodesWithText(description).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Cast").assertIsNotDisplayed()
            compose.onNodeWithText("Play").assertDoesNotExist()
            compose.onNodeWithText("Resume\n3m in").assertDoesNotExist()
            compose.onNodeWithContentDescription(playLabel).assertIsDisplayed()
            for (label in listOf("Sources", "Watchlist", "Love")) {
                compose.onNodeWithText(label).assertDoesNotExist()
                compose.onNodeWithContentDescription(label).assertIsDisplayed()
            }
            if (kind != "missing") {
                val bounds = compose.onNodeWithText(description).fetchSemanticsNode().boundsInRoot
                assertTrue("Synopsis must remain separate from the service badge", bounds.right < UiDevice.getInstance(instrumentation).displayWidth * 0.75f)
            }
            if (kind == "long") {
                compose.onNodeWithText("Read more").assertIsDisplayed()
                key(KeyEvent.KEYCODE_DPAD_UP)
                compose.onNodeWithContentDescription("Read full synopsis").assertIsFocused()
                screenshot("detail-long-preview.png")
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                compose.waitUntil(5_000) {
                    runCatching { compose.onNodeWithText("Back to details").assertIsDisplayed() }.isSuccess
                }
                compose.onAllNodesWithText(description).fetchSemanticsNodes().let { nodes ->
                    assertTrue("The reader must keep the full source synopsis", nodes.isNotEmpty())
                }
                screenshot("detail-long-reader.png")
                key(KeyEvent.KEYCODE_BACK)
                compose.waitUntil(5_000) {
                    runCatching { compose.onNodeWithContentDescription("Read full synopsis").assertIsFocused() }.isSuccess
                }
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                compose.onNodeWithContentDescription("Play").assertIsFocused()
            } else compose.onNodeWithText("Read more").assertDoesNotExist()
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            compose.onNodeWithContentDescription("Sources").assertIsFocused()
            val button = compose.onNodeWithContentDescription("Sources").fetchSemanticsNode().boundsInRoot
            assertEquals("The focus target keeps its fixed dimensions", button.width, button.height, 2f)
            assertTrue("TV icon controls must remain large", button.width >= UiDevice.getInstance(instrumentation).displayWidth * 0.045f)
            if (kind == "short") {
                val descriptionBottom = compose.onNodeWithText(description).fetchSemanticsNode().boundsInRoot.bottom
                assertTrue("Actions must stay close to a short synopsis", button.top - descriptionBottom <
                    UiDevice.getInstance(instrumentation).displayWidth * 0.045f)
            }
            screenshot("detail-$kind-sources-focus.png")
            if (kind == "short") {
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                compose.waitUntil(5_000) {
                    runCatching { compose.onNodeWithText("Sources").assertIsDisplayed() }.isSuccess
                }
                screenshot("detail-sources-glass-drawer.png")
                key(KeyEvent.KEYCODE_BACK)
                compose.waitUntil(5_000) {
                    runCatching { compose.onNodeWithContentDescription("Sources").assertIsFocused() }.isSuccess
                }
            }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            compose.onNodeWithContentDescription("Watchlist").assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(5_000) {
                runCatching { compose.onNodeWithContentDescription("Watchlist").assertIsOn() }.isSuccess
            }
            screenshot("detail-$kind-watchlist-saved.png")
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            val love = compose.onNodeWithContentDescription("Love")
            love.assertIsFocused().assertIsOff()
            if (kind == "short") {
                val heart = love.captureToImage().toPixelMap()
                assertEquals(TvColor.Accent.toArgb(), heart[heart.width / 2, heart.height / 2].toArgb())
            }
            compose.onNodeWithText("More").assertDoesNotExist()
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(5_000) {
                runCatching { love.assertIsOn() }.isSuccess
            }
            runBlocking {
                val lists = context.clientGraph.activeLibrary.savedCollections().first()
                assertTrue(lists.any { it.sourceRef == com.fourseveneightnine.tv.client.data.library.SystemCollections.FAVORITES_REF && it.items.any { row -> row.canonicalId == id } })
                assertTrue(lists.any { it.sourceRef == com.fourseveneightnine.tv.client.data.library.SystemCollections.WATCHLIST_REF && it.items.any { row -> row.canonicalId == id } })
            }
            if (kind == "short") {
                val heart = love.captureToImage().toPixelMap()
                assertEquals(TvColor.Success.toArgb(), heart[heart.width / 2, heart.height / 2].toArgb())
            }
            screenshot("detail-$kind-love-saved.png")
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            val castLabels = listOf("Dulquer Salmaan", "Bhagyashri Borse", "Samuthirakani")
                .map { "$it, View credits" }
            compose.waitUntil(5_000) {
                castLabels.any { label -> runCatching { compose.onNodeWithContentDescription(label).assertIsFocused() }.isSuccess }
            }
            screenshot("detail-$kind-cast-focus.png")
            key(KeyEvent.KEYCODE_DPAD_UP)
            compose.waitUntil(5_000) {
                runCatching { love.assertIsFocused() }.isSuccess
            }
            if (kind == "short") {
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                compose.waitUntil(5_000) { runCatching { love.assertIsOff() }.isSuccess }
                val heart = love.captureToImage().toPixelMap()
                assertEquals(TvColor.Accent.toArgb(), heart[heart.width / 2, heart.height / 2].toArgb())
                screenshot("detail-short-love-removed.png")
            }
        }
    }

    @Test fun catalogFiltersRefreshAsMetadataArrivesForTheSameCards() {
        prepareFixture()
        val graph = instrumentation.targetContext.clientGraph
        val cards = (0 until 5).map { HomeCard("fixture:filter-refresh-$it", "movie", "Title $it") }
        var enabled by mutableStateOf(false)
        var filters by mutableStateOf(com.fourseveneightnine.tv.client.ui.screens.discover.CatalogFilters(
            includedGenres = setOf("Action"), year = 2024))
        val current = AtomicReference<com.fourseveneightnine.tv.client.ui.screens.discover.CatalogBrowseResult>()
        compose.setContent { TvTheme(reduceMotion = true) {
            val browse = com.fourseveneightnine.tv.client.ui.screens.discover.rememberCatalogBrowse(
                cards, graph, filters, enabled, scopeKey = "metadata-refresh-fixture")
            SideEffect { current.set(browse) }
            Text(browse.status())
        } }
        compose.waitUntil(10_000) { current.get()?.index != null && current.get()?.updating == false }
        val initialIndex = requireNotNull(current.get().index)
        assertTrue(current.get().items.isEmpty())
        compose.runOnIdle { enabled = true }
        compose.waitUntil(15_000) {
            current.get()?.let { it.pending == 0 && !it.updating && it.items.size == 3 } == true
        }
        val hydrated = current.get()
        assertTrue(initialIndex !== hydrated.index)
        assertEquals(listOf(cards[0].id, cards[2].id, cards[4].id), hydrated.items.map { it.id })
        assertEquals(listOf("Action", "Drama"), hydrated.facets["genre"])
        assertEquals(5, hydrated.all.size)
        assertTrue(cards.all { it.genres.isEmpty() && it.year == null })
        compose.runOnIdle {
            filters = filters.copy(includedGenres = emptySet(), excludedGenres = setOf("Action"))
        }
        compose.waitUntil(5_000) { current.get()?.items?.size == 2 && current.get()?.updating == false }
        assertEquals(listOf(cards[1].id, cards[3].id), current.get().items.map { it.id })
    }

    @Test fun catalogIndexAndQuerySurviveLeavingForDetail() {
        prepareFixture()
        val graph = instrumentation.targetContext.clientGraph
        val actionFocus = FocusRequester()
        var cards by mutableStateOf((0 until 2000).map { HomeCard("fixture:$it", "movie", "Title $it",
            genres = listOf(if (it % 2 == 0) "Action" else "Drama"), metadataLookupAllowed = false) })
        var visible by mutableStateOf(true)
        var catalogKey by mutableStateOf("cache-fixture")
        var returned = false
        val current = AtomicReference<com.fourseveneightnine.tv.client.ui.screens.discover.CatalogBrowseResult>()
        val restoredIndex = AtomicReference<com.fourseveneightnine.tv.client.ui.screens.discover.CatalogFacetIndex>()
        val restoredQuery = AtomicReference<com.fourseveneightnine.tv.client.ui.screens.discover.CatalogFilters>()
        val appendedWithoutMatches = java.util.concurrent.atomic.AtomicBoolean(false)
        compose.setContent { TvTheme(reduceMotion = true) {
            val holder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
            if (visible) holder.SaveableStateProvider("catalog") {
                var query by androidx.compose.runtime.saveable.rememberSaveable(
                    stateSaver = com.fourseveneightnine.tv.client.ui.screens.discover.CatalogFiltersSaver) {
                    mutableStateOf(com.fourseveneightnine.tv.client.ui.screens.discover.CatalogFilters())
                }
                val browse = com.fourseveneightnine.tv.client.ui.screens.discover.rememberCatalogBrowse(
                    cards, graph, query, enabled = false, scopeKey = catalogKey)
                SideEffect {
                    current.set(browse)
                    if (cards.size > 2000 && browse.items.isEmpty()) appendedWithoutMatches.set(true)
                    if (returned && browse.index != null && restoredIndex.get() == null) {
                        restoredIndex.set(browse.index); restoredQuery.set(query)
                    }
                }
                com.fourseveneightnine.tv.client.ui.components.TvButton("Filter action", { query = query.include("Action") },
                    modifier = Modifier.focusRequester(actionFocus))
                androidx.compose.runtime.LaunchedEffect(Unit) { androidx.compose.runtime.withFrameNanos { }; actionFocus.requestFocus() }
            } else Text("Detail fixture")
        } }
        compose.waitUntil(10_000) { current.get()?.index != null && current.get()?.updating == false }
        compose.onNodeWithContentDescription("Filter action").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        compose.waitUntil(10_000) { current.get()?.items?.size == 1000 && current.get()?.updating == false }
        val index = requireNotNull(current.get().index)
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.runOnIdle { returned = true; visible = true }
        compose.waitUntil(5_000) { restoredIndex.get() != null }
        assertTrue(index === restoredIndex.get())
        assertEquals(setOf("Action"), restoredQuery.get().includedGenres)
        compose.runOnIdle { catalogKey = "another-catalog-with-the-same-cards" }
        compose.waitUntil(5_000) { current.get()?.index != null && current.get()?.index !== index && current.get()?.updating == false }
        assertEquals(1000, current.get().items.size)
        compose.runOnIdle { cards = cards + cards.first().copy(id = "fixture:next-page") }
        compose.waitUntil(5_000) { current.get()?.items?.size == 1001 && current.get()?.updating == false }
        assertFalse("An appended page must leave the current filtered cards on screen", appendedWithoutMatches.get())
    }

    @Test fun savedSetupStillOpensDetailsAfterAnUnfinishedPairing() {
        prepareFixture()
        val context = instrumentation.targetContext
        val graph = context.appGraph
        val title = "Saved setup fixture"
        runBlocking {
            context.clientGraph.activeLibrary.saveToFavorites("fixture:cinematic-short-setup", "movie", title, null)
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val poster = compose.onNodeWithContentDescription(title, substring = true)
            compose.waitUntil(15_000) { runCatching { poster.assertIsFocused() }.isSuccess }
            runBlocking { graph.pairing.beginPairing("192.168.1.2") }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            compose.waitUntil(15_000) { runCatching { poster.assertIsFocused() }.isSuccess }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNodeWithText("Add a source in Settings → Setup to open this title.").assertDoesNotExist()
            compose.waitUntil(10_000) {
                runCatching { compose.onNodeWithContentDescription("Sources").assertIsDisplayed() }.isSuccess
            }
            key(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(5_000) { runCatching { poster.assertIsFocused() }.isSuccess }
        }
    }

    @Test fun navigationRailOpensOnDemandAndKeepsTheHomePosterInPlace() {
        prepareFixture()
        val context = instrumentation.targetContext
        val library = context.clientGraph.activeLibrary
        val titles = (1..5).map { "Rail fixture poster $it" }
        val now = System.currentTimeMillis()
        runBlocking {
            titles.asReversed().forEachIndexed { index, title ->
                val id = "fixture:rail-poster-$index"
                library.recordLocalPlay(id, "movie", title, posterUrl = "$fixtureBase/new.png",
                    playedAtMillis = now - (titles.lastIndex - index) * 60_000L)
                library.writeProgress(id, "movie", positionMs = 60_000, durationMs = 600_000)
            }
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            val poster = compose.onNodeWithContentDescription(titles.first(), substring = true)
            val endPoster = compose.onNodeWithContentDescription(titles.last(), substring = true)
            val railHome = compose.onNodeWithContentDescription("Home")
            compose.waitUntil(15_000) {
                runCatching { poster.assertIsFocused() }.isSuccess
            }
            railHome.assertDoesNotExist()
            val closed = poster.fetchSemanticsNode().boundsInRoot
            val viewport = compose.onRoot().fetchSemanticsNode().boundsInRoot
            assertTrue("Home posters should use the reclaimed left space", closed.left < viewport.width * 0.09f)
            val clippedPoster = endPoster.fetchSemanticsNode().boundsInRoot
            assertTrue("The next poster should reach the right edge", viewport.right - clippedPoster.right <= 2f)
            assertTrue("The next poster should still be partially visible", clippedPoster.width < closed.width)
            screenshot("rail-closed-wide-home.png")

            key(KeyEvent.KEYCODE_MENU)
            railHome.assertIsFocused()
            assertEquals(closed, poster.fetchSemanticsNode().boundsInRoot)
            screenshot("rail-open-wide-home.png")

            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            poster.assertIsFocused()
            railHome.assertDoesNotExist()

            key(KeyEvent.KEYCODE_DPAD_LEFT)
            railHome.assertIsFocused()
            key(KeyEvent.KEYCODE_BACK)
            poster.assertIsFocused()
            railHome.assertDoesNotExist()
            assertEquals(closed, poster.fetchSemanticsNode().boundsInRoot)

            for (title in titles.drop(1)) {
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                compose.onNodeWithContentDescription(title, substring = true).assertIsFocused()
            }
            compose.waitUntil(5_000) {
                runCatching { endPoster.fetchSemanticsNode().boundsInRoot.width >= closed.width - 2f }.getOrDefault(false)
            }
            screenshot("rail-end-poster-in-view.png")
        }
    }

    @Test fun homeCatalogFiltersRestoreOpenerAfterNoMatchReset() {
        prepareFixture()
        val gridFocus = FocusRequester()
        val state = com.fourseveneightnine.tv.client.ui.screens.home.HomeCatalogState(
            com.fourseveneightnine.tv.client.ui.screens.home.HomeCatalogRef("https://fixture.invalid/manifest.json", "movie", "fixture", "Filter fixture"),
            items = listOf(HomeCard("fixture:old", "movie", "Old movie", year = 1999, metadataLookupAllowed = false)),
            loading = false, exhausted = true)
        compose.setContent { TvTheme(reduceMotion = true) {
            CompositionLocalProvider(com.fourseveneightnine.tv.client.ui.LocalShellState provides
                androidx.compose.runtime.remember { com.fourseveneightnine.tv.client.ui.ShellState() }) {
            com.fourseveneightnine.tv.client.ui.screens.home.CatalogGridOverlay(state,
                returnCardKey = null, onReturnFinished = {}, onLoadMore = {}, onClose = {}, gridFocus = gridFocus,
                savePanelOpen = false, onOpen = {}, onLongOpen = {})
            }
        } }
        key(KeyEvent.KEYCODE_DPAD_UP)
        compose.onNode(hasContentDescription("Filters & sort") and hasText("Filters & sort")).assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        repeat(3) { key(KeyEvent.KEYCODE_DPAD_DOWN); compose.waitForIdle() }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        repeat(java.time.Year.now().value + 3 - 2024 + 1) { key(KeyEvent.KEYCODE_DPAD_DOWN); compose.waitForIdle() }
        compose.onNodeWithContentDescription("2024").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        key(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        key(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("2024").assertIsFocused()
        compose.onNodeWithText("No matching titles").assertExists()
        screenshot("catalog-filters-empty-focus.png")
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithContentDescription("Reset filters").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        compose.onNode(hasContentDescription("Filters & sort") and hasText("Filters & sort")).assertIsFocused()
        screenshot("catalog-filters-reset-focus.png")
    }

    @Test fun longCollectionKeepsFocusAndFiltersThroughRemoteBack() {
        prepareFixture()
        val context = instrumentation.targetContext
        val library = context.clientGraph.activeLibrary
        val folderName = "Long catalog fixture"
        val folder = runBlocking {
            library.ensureSystemCollections()
            library.create(folderName, "#E38C45").also { id ->
                repeat(108) { index ->
                    val number = index.toString().padStart(3, '0')
                    library.addItem(id, "fixture:long-catalog-$number", "movie", "Catalog title $number (2024)")
                }
            }
        }
        val folders = runBlocking { library.collections().first() }
        val folderIndex = folders.indexOfFirst { it.id == folder }
        fun awaitFocus(label: String, substring: Boolean = false) {
            val target = compose.onNode(hasContentDescription(label, substring = substring) and isFocused())
            try {
                compose.waitUntil(10_000) { runCatching { target.assertIsFocused() }.isSuccess }
            } catch (_: androidx.compose.ui.test.ComposeTimeoutException) {
                screenshot("collection-focus-failure.png")
                target.assertIsFocused()
            }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                runBlocking {
                    withTimeout(15_000) { requireNotNull(context.appGraph.receiverHost).settingsConfigured.first { it } }
                }
                compose.waitUntil(10_000) {
                    var ready = false
                    scenario.onActivity { ready = it.hasWindowFocus() }
                    ready && compose.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty()
                }
                key(KeyEvent.KEYCODE_MENU)
                awaitFocus("Home")
                repeat(3) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
                awaitFocus("Collections")
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                awaitFocus(folders.first().name, substring = true)
                repeat(folderIndex / FolderGrid.COLUMNS) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
                repeat(folderIndex % FolderGrid.COLUMNS) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
                awaitFocus(folderName, substring = true)
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                awaitFocus("Catalog title 000, 2024, movie")

                val down = SystemClock.uptimeMillis()
                event(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)
                compose.mainClock.advanceTimeByFrame()
                repeat(60) { index ->
                    SystemClock.sleep(40)
                    event(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, index + 1)
                    compose.mainClock.advanceTimeByFrame()
                }
                event(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN)
                val deepPoster = compose.onAllNodes(isFocused()).fetchSemanticsNodes().single()
                    .config[SemanticsProperties.ContentDescription].single()
                val focusedIndex = deepPoster.substringAfter("Catalog title ").substringBefore(',').toInt()
                screenshot("collection-held-down-focus.png")
                assertTrue("Held Down should advance at least 16 rows: $deepPoster", focusedIndex >= 96)

                key(KeyEvent.KEYCODE_DPAD_LEFT)
                awaitFocus("Filters & sort")
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                awaitFocus("Show results")
                screenshot("collection-deep-filter-shortcut.png")
                key(KeyEvent.KEYCODE_BACK)
                awaitFocus("Filters & sort")
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                awaitFocus(deepPoster)

                key(KeyEvent.KEYCODE_DPAD_LEFT)
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                awaitFocus("Show results")
                repeat(4) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
                awaitFocus("Decade ▸")
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                awaitFocus("All decades")
                repeat((java.time.Year.now().value + 3) / 10 - 201) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
                awaitFocus("2020s")
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                key(KeyEvent.KEYCODE_BACK)
                awaitFocus("Show results")
                key(KeyEvent.KEYCODE_BACK)
                awaitFocus("2020s")
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                awaitFocus(deepPoster)
                val poster = compose.onNodeWithContentDescription(deepPoster)
                val bounds = poster.fetchSemanticsNode().boundsInRoot
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Sources").fetchSemanticsNodes().isNotEmpty() }
                key(KeyEvent.KEYCODE_BACK)
                awaitFocus(deepPoster)
                assertEquals(bounds, poster.fetchSemanticsNode().boundsInRoot)
                compose.onNodeWithContentDescription("2020s").assertExists()
                key(KeyEvent.KEYCODE_BACK)
                awaitFocus(folderName, substring = true)
                screenshot("collection-back-origin-focus.png")
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                compose.waitUntil(10_000) { compose.onAllNodesWithText("2020s").fetchSemanticsNodes().isNotEmpty() }
            }
        } finally {
            runBlocking { library.delete(folder) }
        }
    }

    @Test fun syncedCollectionBackRestoresTheSamePosterAndViewport() {
        prepareFixture()
        val context = instrumentation.targetContext
        val graph = context.clientGraph
        val now = System.currentTimeMillis()
        val generation = "collection-return-$now"
        val shelf = Shelf("fixture:synced-return", "Synced return fixture", ShelfKind.LETTERBOXD_FRIENDS,
            List(40) { index ->
                CatalogItem("fixture:synced-return-$index", "movie", "Synced movie $index", year = 2024,
                    posterUrl = "$fixtureBase/new.png?collection-return=$index")
            }, generation, now)
        runBlocking {
            assertTrue(graph.snapshots.importPrivateSnapshot(generation, now, listOf(shelf)))
            withTimeout(10_000) { graph.collectionShelves.first { shelves -> shelves.any { it.id == shelf.id } } }
        }
        fun awaitFocus(target: androidx.compose.ui.test.SemanticsNodeInteraction) {
            compose.waitUntil(10_000) { runCatching { target.assertIsFocused() }.isSuccess }
        }
        fun poster(index: Int) = compose.onNodeWithContentDescription("Synced movie $index,", substring = true)
        ActivityScenario.launch(MainActivity::class.java).use {
            val home = compose.onNodeWithContentDescription("Home")
            val collections = compose.onNodeWithContentDescription("Collections")
            compose.waitUntil(10_000) { compose.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty() }
            key(KeyEvent.KEYCODE_MENU)
            awaitFocus(home)
            repeat(3) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
            awaitFocus(collections)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            val tileLabel = hasContentDescription(shelf.title + ",", substring = true)
            val tile = compose.onNode(tileLabel)
            compose.waitUntil(10_000) { compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().size == 1 }
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(tileLabel)
            tile.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
            awaitFocus(tile)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitFocus(poster(0))
            repeat(5) { row ->
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                awaitFocus(poster((row + 1) * 4))
            }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            val selected = poster(21)
            awaitFocus(selected)
            val bounds = selected.fetchSemanticsNode().boundsInRoot
            val neighborBounds = poster(20).fetchSemanticsNode().boundsInRoot
            screenshot("synced-collection-before-detail.png")
            val preferences = context.appGraph.presentationPreferences
            val motionKey = com.fourseveneightnine.tv.client.ui.screens.settings.SettingsKeys.REDUCE_MOTION
            val originalMotion = preferences.getBoolean(motionKey, false)
            try {
                for (reduceMotion in listOf(false, true)) {
                    compose.runOnIdle { preferences.edit().putBoolean(motionKey, reduceMotion).apply() }
                    key(KeyEvent.KEYCODE_DPAD_CENTER)
                    val detail = compose.onNodeWithContentDescription("Sources")
                    compose.waitUntil(10_000) { runCatching { detail.assertExists() }.isSuccess }
                    compose.mainClock.autoAdvance = false
                    try {
                        key(KeyEvent.KEYCODE_BACK)
                        var revealed = false
                        repeat(30) {
                            compose.mainClock.advanceTimeByFrame()
                            val returned = runCatching { selected.fetchSemanticsNode() }.getOrNull()
                            if (returned != null) {
                                revealed = true
                                selected.assertIsFocused()
                                assertEquals(bounds, returned.boundsInRoot)
                            } else detail.assertExists()
                        }
                        assertTrue("Back should reveal the restored catalog within the sampled frames", revealed)
                    } finally {
                        compose.mainClock.autoAdvance = true
                    }
                    awaitFocus(selected)
                    assertEquals(bounds, selected.fetchSemanticsNode().boundsInRoot)
                    assertEquals(neighborBounds, poster(20).fetchSemanticsNode().boundsInRoot)
                }
            } finally {
                compose.runOnIdle { preferences.edit().putBoolean(motionKey, originalMotion).apply() }
            }
            screenshot("synced-collection-after-detail.png")
            key(KeyEvent.KEYCODE_BACK)
            awaitFocus(tile)
        }
    }

    @Test fun watchlistBackRestoresTheSamePosterAndViewport() {
        prepareFixture()
        val context = instrumentation.targetContext
        val library = context.clientGraph.activeLibrary
        val titles = (0 until 24).associate { index ->
            "fixture:watchlist-return-$index" to "Watchlist return $index"
        }
        val watchlist = runBlocking {
            for ((id, title) in titles) {
                library.saveToWatchlist(id, "movie", title, "$fixtureBase/new.png")
            }
            library.savedCollections().first().first {
                it.sourceRef == com.fourseveneightnine.tv.client.data.library.SystemCollections.WATCHLIST_REF
            }.id
        }
        fun awaitFocus(target: androidx.compose.ui.test.SemanticsNodeInteraction) {
            compose.waitUntil(10_000) { runCatching { target.assertIsFocused() }.isSuccess }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitUntil(10_000) { compose.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty() }
                key(KeyEvent.KEYCODE_MENU)
                awaitFocus(compose.onNodeWithContentDescription("Home"))
                repeat(3) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
                awaitFocus(compose.onNodeWithContentDescription("Collections"))
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                val tileLabel = hasContentDescription("Watchlist,", substring = true)
                val tile = compose.onNode(tileLabel)
                compose.waitUntil(10_000) { compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().size == 1 }
                tile.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
                awaitFocus(tile)
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                compose.waitUntil(10_000) { compose.onAllNodesWithText("24 saved items").fetchSemanticsNodes().isNotEmpty() }
                val focusedTitle = compose.onNode(isFocused() and hasContentDescription("Watchlist return ", substring = true))
                if (!runCatching { focusedTitle.assertIsFocused() }.isSuccess) key(KeyEvent.KEYCODE_DPAD_DOWN)
                awaitFocus(focusedTitle)
                fun label() = focusedTitle.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
                repeat(2) {
                    val previous = label()
                    key(KeyEvent.KEYCODE_DPAD_DOWN)
                    compose.waitUntil(5_000) { runCatching { label() != previous }.getOrDefault(false) }
                }
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                val selected = compose.onNodeWithContentDescription(label())
                awaitFocus(selected)
                val bounds = selected.fetchSemanticsNode().boundsInRoot
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Sources").fetchSemanticsNodes().isNotEmpty() }
                key(KeyEvent.KEYCODE_BACK)
                awaitFocus(selected)
                assertEquals(bounds, selected.fetchSemanticsNode().boundsInRoot)
                key(KeyEvent.KEYCODE_BACK)
                awaitFocus(tile)
            }
        } finally {
            runBlocking { library.removeWatchlistItems(watchlist, titles.keys) }
        }
    }

    @Test fun collectionSearchFiltersFolderNamesAndTitlesWithTheRemote() {
        prepareFixture()
        val context = instrumentation.targetContext
        val library = context.clientGraph.activeLibrary
        val folders = runBlocking {
            library.ensureSystemCollections()
            val match = library.create("Quiz night", "#E38C45")
            library.addItem(match, "fixture:quiz", "movie", "Quiz film (2024)")
            library.addItem(match, "fixture:other", "movie", "Summer film (2024)")
            listOf(match, library.create("Summer picks", "#7755DD"))
        }
        fun focused(label: String, substring: Boolean = false) {
            val node = compose.onNode(isFocused() and hasContentDescription(label, substring = substring))
            try {
                compose.waitUntil(10_000) { runCatching { node.assertIsFocused() }.isSuccess }
            } catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
                screenshot("collection-search-focus-failure.png")
                node.assertIsFocused()
                throw error
            }
        }
        val device = UiDevice.getInstance(instrumentation)
        val imePackage = android.provider.Settings.Secure.getString(context.contentResolver,
            android.provider.Settings.Secure.DEFAULT_INPUT_METHOD).substringBefore('/')
        val keyboard = By.pkg(imePackage)
        fun searchForQ(label: String, excluded: String) {
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            focused(label)
            assertTrue(device.wait(Until.hasObject(keyboard), 5_000))
            compose.onNode(isFocused() and hasContentDescription(label)).performTextInput("Q")
            compose.onNodeWithContentDescription(excluded, substring = true).assertDoesNotExist()
            assertTrue(device.hasObject(keyboard))
            screenshot("collection-inline-search-live-$label.png")
            device.pressBack()
            assertTrue(device.wait(Until.gone(keyboard), 5_000))
            focused(label)
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitUntil(15_000) {
                    var ready = false
                    scenario.onActivity { ready = it.hasWindowFocus() }
                    ready && compose.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty()
                }
                key(KeyEvent.KEYCODE_MENU)
                focused("Home")
                repeat(3) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
                focused("Collections")
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                focused("Continue Watching", substring = true)
                repeat(3) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
                focused("Search collections")
                key(KeyEvent.KEYCODE_DPAD_LEFT)
                focused("Watchlist", substring = true)
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                focused("Search collections")
                searchForQ("Search collections", "Summer picks")
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                focused("Quiz night", substring = true)
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                focused("Quiz film", substring = true)
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                focused("Summer film", substring = true)
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                focused("Search titles")
                key(KeyEvent.KEYCODE_DPAD_LEFT)
                focused("Summer film", substring = true)
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                focused("Search titles")
                searchForQ("Search titles", "Summer film")
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                focused("Clear")
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                focused("Search titles")
                compose.onNodeWithContentDescription("Summer film", substring = true).assertExists()
                key(KeyEvent.KEYCODE_BACK)
                focused("Search titles")
                if (InstrumentationRegistry.getArguments().getString("quietPin") == "true") {
                    repeat(2) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
                    focused("Add to Den TV Home")
                    val launcher = com.fourseveneightnine.tv.client.home.LauncherRails
                    val row = requireNotNull(launcher.collectionUri(context, folders.first()))
                    try {
                        key(KeyEvent.KEYCODE_DPAD_CENTER)
                        compose.waitUntil(8_000) { launcher.pinnedRows(context, row).contains(row) }
                        compose.waitUntil(4_000) { compose.onAllNodesWithText("Added to Den TV Home.").fetchSemanticsNodes().isNotEmpty() }
                        focused("Add to Den TV Home")
                        val device = UiDevice.getInstance(instrumentation)
                        assertEquals(context.packageName, device.currentPackageName)
                    } finally {
                        launcher.remove(context, listOf(row))
                    }
                    screenshot("collection-actions-right-rail.png")
                }
                key(KeyEvent.KEYCODE_DPAD_LEFT)
                focused("Summer film", substring = true)
                key(KeyEvent.KEYCODE_BACK)
                focused("Quiz night", substring = true)
                compose.onNodeWithContentDescription("Summer picks", substring = true).assertDoesNotExist()
            }
        } finally {
            runBlocking { folders.forEach { library.delete(it) } }
        }
    }

    @Test fun discoverRightRailReturnsToTheOriginAfterFiltersAndRefresh() {
        prepareFixture()
        fun focused(label: String, partial: Boolean = false) {
            val match = isFocused() and hasContentDescription(label, substring = partial)
            try {
                compose.waitUntil(12_000) { compose.onAllNodes(match).fetchSemanticsNodes().isNotEmpty() }
            } catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
                screenshot("discover-catalog-rail-failure.png")
                throw error
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitUntil(15_000) {
                var ready = false
                scenario.onActivity { ready = it.hasWindowFocus() }
                ready && compose.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty()
            }
            key(KeyEvent.KEYCODE_MENU)
            focused("Home")
            repeat(2) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
            focused("Discover")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            focused("Movies", partial = true)
            repeat(3) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
            focused("Catalog:", partial = true)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(12_000) {
                compose.onAllNodesWithContentDescription("Search catalogs").fetchSemanticsNodes().isNotEmpty()
            }
            val fixtureRow = isFocused() and hasContentDescription("Fixture movies")
            repeat(30) {
                if (compose.onAllNodes(fixtureRow).fetchSemanticsNodes().isEmpty()) key(KeyEvent.KEYCODE_DPAD_DOWN)
            }
            focused("Fixture movies")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(15_000) {
                compose.onAllNodesWithContentDescription("Fixture movie 0", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.waitUntil(10_000) {
                compose.onAllNodes(isFocused() and hasContentDescription("Fixture movie", substring = true))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            repeat(4) {
                if (compose.onAllNodes(isFocused() and hasContentDescription("Search titles")).fetchSemanticsNodes().isEmpty()) {
                    key(KeyEvent.KEYCODE_DPAD_RIGHT)
                }
            }
            focused("Search titles")
            screenshot("discover-catalog-right-rail.png")
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            focused("Fixture movie 3", partial = true)
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            focused("Filters & sort")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            focused("Show results")
            key(KeyEvent.KEYCODE_BACK)
            focused("Filters & sort")
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            focused("Fixture movie 3", partial = true)
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            repeat(3) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
            focused("Refresh catalog")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(15_000) {
                compose.onAllNodes(isFocused() and hasContentDescription("Refresh catalog")).fetchSemanticsNodes().isNotEmpty()
            }
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            focused("Fixture movie 3", partial = true)
        }
    }

    @Test fun myLibraryPlaysTheSelectedFilesFromTheThreeColumnDirectory() {
        prepareFixture()
        val context = instrumentation.targetContext
        val client = context.clientGraph
        val session = context.appGraph.playback
        val library = client.activeLibrary
        val field = client.javaClass.getDeclaredField("mutableServices").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val services = field.get(client) as kotlinx.coroutines.flow.MutableStateFlow<com.fourseveneightnine.tv.client.ClientGraph.DataServices?>
        val original = runBlocking { withTimeout(15_000) { services.filterNotNull().first() } }
        val document = com.fourseveneightnine.tv.client.data.settings.SettingsDocument.parse("""{"torboxAPIKey":"local-fixture"}""")
        val debrid = com.fourseveneightnine.tv.client.data.streams.debrid.DebridResolver(
            "local-fixture", null, client.okHttp, torboxBaseURL = "$fixtureBase/library")
        val artwork = List(FolderGrid.POSTER_COUNT) { index -> "$fixtureBase/old.png?directoryPoster=$index" }
        val folders = runBlocking {
            library.ensureSystemCollections()
            // Fill one row with small lists whose titles exercise different line lengths.
            listOf("After Dark", "Sunday Matinee and the Best of World Cinema", "Family Night").map { name ->
                library.create(name, "#E38C45").also { id ->
                    artwork.forEachIndexed { index, art ->
                        library.addItem(id, "fixture:directory-$id-$index", "movie", "Test film $index", art)
                    }
                }
            }
        }
        fun awaitFocus(label: String) {
            val node = compose.onNodeWithContentDescription(label, substring = true)
            compose.waitUntil(10_000) { runCatching { node.assertIsFocused() }.isSuccess }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitUntil(15_000) {
                    var focused = false
                    scenario.onActivity { focused = it.hasWindowFocus() }
                    focused && compose.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty()
                }
                runBlocking {
                    client.reloadSettingsNow()
                    debrid.refreshTorboxLibrary()
                }
                assertEquals(debrid.libraryState.value.message, 2, debrid.libraryState.value.rows.size)
                services.value = com.fourseveneightnine.tv.client.ClientGraph.DataServices(document, original.health,
                    original.client, original.registry, original.meta, original.ratings, original.search, debrid)
                key(KeyEvent.KEYCODE_MENU)
                awaitFocus("Home")
                repeat(3) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
                awaitFocus("Collections")
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                val summaries = runBlocking { library.collections().first() }
                val cards = FolderGrid.cards(summaries, true)
                awaitFocus(cards.first().name)
                val index = cards.indexOfFirst { it.name == "My Library" }
                check(index >= 0)
                repeat(index / FolderGrid.COLUMNS) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
                repeat(index % FolderGrid.COLUMNS) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
                awaitFocus("My Library")
                compose.waitUntil(10_000) {
                    artwork.all(PaintedArtwork::wasPainted)
                }
                if (cards.getOrNull(index + FolderGrid.COLUMNS)?.name == "After Dark") {
                    key(KeyEvent.KEYCODE_DPAD_DOWN)
                    awaitFocus("After Dark")
                    screenshot("collections-three-poster-stacks.png")
                    key(KeyEvent.KEYCODE_DPAD_UP)
                    awaitFocus("My Library")
                } else screenshot("collections-three-poster-stacks.png")

                key(KeyEvent.KEYCODE_DPAD_CENTER)
                fun awaitRow(name: String, focused: Boolean = false) {
                    val match = hasText(name, substring = true)
                    try {
                        compose.waitUntil(10_000) {
                            compose.onAllNodes(if (focused) match and isFocused() else match)
                                .fetchSemanticsNodes().isNotEmpty()
                        }
                    } catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
                        screenshot("my-library-focus-failure.png")
                        throw error
                    }
                }
                awaitRow("Feature archive", focused = true)
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                runBlocking {
                    withTimeout(15_000) { session.phase.first { it == ReceiverPlaybackPhase.Playing } }
                    assertTrue(client.playFlow.current.value?.id.orEmpty().endsWith(":0:74"))
                }
                repeat(2) {
                    if (compose.onAllNodesWithText("Feature archive", substring = true).fetchSemanticsNodes().isEmpty()) {
                        key(KeyEvent.KEYCODE_BACK)
                        compose.waitForIdle()
                    }
                }
                awaitRow("Feature archive", focused = true)
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                awaitRow("Season archive", focused = true)
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                awaitRow("Choose a file")
                awaitFocus("Show.S02E03.mp4")
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                awaitFocus("Show.S02E04.mp4")
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                runBlocking {
                    withTimeout(15_000) { session.phase.first { it == ReceiverPlaybackPhase.Playing } }
                    assertTrue(client.playFlow.current.value?.id.orEmpty().endsWith(":17:73"))
                }
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithText("Choose a file", substring = true).fetchSemanticsNodes().isEmpty()
                }
            }
        } finally {
            runBlocking {
                session.stop()
                folders.forEach { library.delete(it) }
            }
            services.value = original
        }
    }

    @Test fun extendedCatalogFiltersSearchPeopleAndKeepNestedRemoteBack() {
        val target = HomeCard("fixture:target", "movie", "Target", studios = listOf("Studio A"), castNames = listOf("Zed actor"))
        val cards = listOf(target) + (0 until 100).map { HomeCard("fixture:$it", "movie", "Other $it", studios = listOf("Studio B"), castNames = listOf("Person $it")) }
        val index = com.fourseveneightnine.tv.client.ui.screens.discover.CatalogFacetIndex(cards)
        var filters by mutableStateOf(com.fourseveneightnine.tv.client.ui.screens.discover.CatalogFilters())
        var matches by mutableStateOf(cards)
        var closed = false
        compose.setContent { TvTheme(reduceMotion = true) {
            Box(Modifier.fillMaxSize().background(TvColor.Canvas)) {
                com.fourseveneightnine.tv.client.ui.screens.discover.CatalogFilterPanel(filters,
                    com.fourseveneightnine.tv.client.ui.screens.discover.CatalogBrowseResult(cards, matches, 0, facets = index.options, index = index),
                    onChange = { filters = it; matches = index.query(it) }, onClose = { closed = true })
            }
        } }
        fun down(count: Int) { repeat(count) { key(KeyEvent.KEYCODE_DPAD_DOWN); compose.waitForIdle() } }
        fun select() { key(KeyEvent.KEYCODE_DPAD_CENTER); compose.waitForIdle() }
        fun back() { key(KeyEvent.KEYCODE_BACK); compose.waitForIdle() }
        down(8); select() // More filters
        down(1); select() // People & studios
        down(2); select() // Studio
        down(1); select() // Studio A
        assertEquals("Studio A", filters.studio)
        assertEquals(listOf(target), matches)
        screenshot("catalog-filters-studio.png")
        back(); back(); back()
        assertFalse(closed)
        down(8); select(); down(1); select(); select() // Cast
        compose.onNodeWithContentDescription("Search cast").assertIsFocused()
        select()
        val device = UiDevice.getInstance(instrumentation)
        val imePackage = android.provider.Settings.Secure.getString(instrumentation.targetContext.contentResolver,
            android.provider.Settings.Secure.DEFAULT_INPUT_METHOD).substringBefore('/')
        val keyboard = By.pkg(imePackage)
        assertTrue(device.wait(Until.hasObject(keyboard), 5_000))
        compose.onNodeWithContentDescription("Search cast").performTextInput("Zed")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("Zed actor").fetchSemanticsNodes().size == 1 &&
                compose.onAllNodesWithContentDescription("Person 0").fetchSemanticsNodes().isEmpty()
        }
        assertTrue(device.hasObject(keyboard))
        screenshot("catalog-filters-cast-keyboard.png")
        device.pressBack()
        assertTrue(device.wait(Until.gone(keyboard), 5_000))
        down(2); select()
        assertEquals("Zed actor", filters.actor)
        assertEquals(listOf(target), matches)
        screenshot("catalog-filters-cast-search.png")
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        compose.waitForIdle()
        back(); back(); back()
        assertTrue(closed)
    }


    @Test fun catalogFiltersSupportMultipleGenresExclusionsDecadesAndRemoteBack() {
        val items = listOf(
            HomeCard("fixture:match", "movie", "Matching thriller", year = 1999, genres = listOf("Action", "Thriller")),
            HomeCard("fixture:reject", "movie", "Rejected horror", year = 1999, genres = listOf("Action", "Thriller", "Horror")),
            HomeCard("fixture:year", "movie", "Modern thriller", year = 2024, genres = listOf("Action", "Thriller")),
        )
        var filters by mutableStateOf(com.fourseveneightnine.tv.client.ui.screens.discover.CatalogFilters())
        var closed by mutableStateOf(false)
        compose.setContent { TvTheme(reduceMotion = true) {
            Box(Modifier.fillMaxSize().background(TvColor.Canvas)) {
                if (!closed) com.fourseveneightnine.tv.client.ui.screens.discover.CatalogFilterPanel(
                    filters, com.fourseveneightnine.tv.client.ui.screens.discover.CatalogBrowseResult(items, filters.apply(items), 0),
                    onChange = { filters = it }, onClose = { closed = true })
            }
        } }
        fun down(count: Int) { repeat(count) { key(KeyEvent.KEYCODE_DPAD_DOWN); compose.waitForIdle() } }
        compose.onNodeWithContentDescription("Show results").assertIsFocused()
        down(5)
        compose.onNodeWithContentDescription("Include genres ▸").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        down(1)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        down(15)
        compose.onNodeWithContentDescription("Thriller").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        assertEquals(setOf("Action", "Thriller"), filters.includedGenres)
        screenshot("catalog-filters-include-genres.png")
        key(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        down(6)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        down(11)
        compose.onNodeWithContentDescription("Horror").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        assertEquals(setOf("Horror"), filters.excludedGenres)
        screenshot("catalog-filters-exclude-genres.png")
        key(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        down(4)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        down(4)
        compose.onNodeWithContentDescription("1990s").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        assertEquals(listOf(items.first()), filters.apply(items))
        screenshot("catalog-filters-decade.png")
        key(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        assertFalse(closed)
        key(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        assertTrue(closed)
    }


    @Test fun failedCompactSourceKeepsItsRowReasonAndRemoteFocus() {
        prepareFixture()
        val client = instrumentation.targetContext.clientGraph
        val request = PlayRequest("movie", "fixture:source-deck", "Source Deck")
        val owner = SupervisorJob()
        val model = com.fourseveneightnine.tv.client.ui.screens.streams.StreamsViewModel(
            request.type, request.id, null, null, client, CoroutineScope(owner + Dispatchers.Main.immediate),
            initialFilters = com.fourseveneightnine.tv.client.ui.screens.streams.StreamFilters.DrawerDefault)
        val failure = CompletableDeferred<String>()
        try {
            val expired = runBlocking {
                withTimeout(20_000) {
                    model.state.first { state -> state.search.done && state.displayed.any {
                        it.row.url?.contains("/source-deck-expired.mp4") == true
                    } }
                }
            }.displayed.single { it.row.url?.contains("/source-deck-expired.mp4") == true }.row
            // Another source-deck case may have rejected this exact local URL already.
            requireNotNull(client.services.value).debrid.forget(expired, null, null)
            instrumentation.runOnMainSync { model.start() }
            runBlocking {
                withTimeout(20_000) {
                    model.state.first { state -> state.search.done && state.displayed.any {
                        it.row.id == expired.id && it.eligible
                    } }
                }
            }
            compose.setContent { TvTheme(reduceMotion = true) {
                com.fourseveneightnine.tv.client.ui.screens.streams.StreamsDrawer(
                    request.type, request.id, null, null, request.title, posterUrl = null, backdropUrl = null,
                    prefetched = model, onDismiss = {}, onSettings = {}, onToast = { failure.complete(it) },
                )
            } }
            // The expired embedded4K copy is the only TorBox row in the local deck.
            val selected = compose.onNodeWithContentDescription("TorBox", substring = true)
            compose.waitUntil(20_000) { runCatching { selected.assertIsFocused() }.isSuccess }
            screenshot("source-deck-expired-before-selection.png")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            val message = runBlocking { withTimeout(40_000) { failure.await() } }
            compose.waitForIdle()
            screenshot("source-deck-expired-after-failure.png")
            assertTrue("The real selection must report failure", message.isNotBlank())
            val failed = model.state.value.displayed.single { it.row.id == expired.id }
            assertFalse("The failed copy must be marked unavailable in view state", failed.eligible)
            selected.assertIsDisplayed().assertIsFocused()
            compose.onNodeWithText("Link unavailable").assertIsDisplayed()
        } finally {
            model.stop()
            owner.cancel()
        }
    }

    @Test fun repeatedPlaylistEntriesRemainBrowsableWithTheRemote() {
        prepareFixture()
        val repeated = "#EXTINF:-1 group-title=\"Live\",News One\nhttps://fixture.example/live/one.ts\n"
        val parsed = com.fourseveneightnine.tv.client.iptv.IptvM3u.parse("fixture", "#EXTM3U\n" + repeated + repeated +
            "#EXTINF:-1 group-title=\"Live\",News Two\nhttps://fixture.example/live/two.ts\n")
        val state = com.fourseveneightnine.tv.client.iptv.IptvState(catalog =
            com.fourseveneightnine.tv.client.iptv.IptvCatalog(channels = parsed.channels))
        val focus = FocusRequester()
        var picked: String? = null
        compose.setContent { TvTheme(reduceMotion = true) {
            com.fourseveneightnine.tv.client.ui.screens.live.LiveChannelList(parsed.channels, state,
                onPlay = { picked = it.name }, onMenu = {}, onChannelFocus = { _, _ -> },
                onFavorite = {}, contentFocus = focus)
            androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus() }
        } }
        compose.onNodeWithText("1  News One").assertIsDisplayed()
        compose.onNodeWithText("2  News Two").assertIsDisplayed()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithContentDescription("News One").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithContentDescription("News Two").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        assertEquals("News Two", picked)
        screenshot("repeated-playlist-browsable.png")
    }

    @Test fun pendingContinueCardNamesTheEpisodeAndPlaysWithOk() {
        prepareFixture()
        val focus = FocusRequester()
        val row = com.fourseveneightnine.tv.client.data.library.ContinueItem(
            "fixture:next", "series", "Part-Timers", "$fixtureBase/new.png", null, 1, 5, 0, 0, 1,
            com.fourseveneightnine.tv.client.data.library.RowOrigin.LOCAL, pendingNextEpisode = true)
        var played = false
        compose.setContent { TvTheme(reduceMotion = true) {
            Box(Modifier.fillMaxSize().background(TvColor.Canvas), contentAlignment = androidx.compose.ui.Alignment.Center) {
                com.fourseveneightnine.tv.client.ui.screens.home.HomeContinueCardView(row.toHomeContinueCard(),
                    onClick = { played = true }, onLongClick = {}, onFocused = {}, modifier = Modifier.focusRequester(focus))
            }
            androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus() }
        } }
        compose.onNodeWithText("Up next · S1 E5").assertIsDisplayed()
        compose.onNodeWithContentDescription("0 percent watched", substring = true).assertDoesNotExist()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        assertTrue(played)
    }

    @Test fun sourceDrawerStartsCachedAndFocusesAResult() {
        prepareFixture()
        val context = instrumentation.targetContext
        val id = "fixture:source-deck"
        val uri = requireNotNull(SystemSearchIntentParser.detailUri(context.packageName + ".search", "movie", id))
        val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(uri))
        ActivityScenario.launch<MainActivity>(intent).use {
            compose.waitUntil(15_000) {
                runCatching { compose.onNodeWithContentDescription("Play").assertIsFocused() }.isSuccess
            }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            compose.onNodeWithContentDescription("Sources").assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(20_000) {
                compose.onAllNodesWithContentDescription("4K,", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Delivery: Cached Debrid").assertIsDisplayed()
            compose.onNodeWithText("Show more").assertDoesNotExist()
            val cachedSource = compose.onNodeWithContentDescription("Real-Debrid", substring = true)
            compose.waitUntil(15_000) {
                runCatching { cachedSource.assertIsDisplayed() }.isSuccess
            }
            repeat(3) {
                if (runCatching { cachedSource.assertIsFocused() }.isFailure) key(KeyEvent.KEYCODE_DPAD_DOWN)
            }
            cachedSource.assertIsFocused()
            screenshot("source-deck-cached-focus.png")
        }
    }

    @androidx.compose.runtime.Composable
    private fun drawerFixtureBackdrop() {
        com.fourseveneightnine.tv.client.ui.components.TvArtwork(
            com.fourseveneightnine.tv.client.data.images.PosterRequest.backdrop("$fixtureBase/old.png"), "",
            Modifier.fillMaxSize(), showTitleWhenMissing = false, contentScale = androidx.compose.ui.layout.ContentScale.Crop)
    }

    @Test fun playerSourcesExpandAndCollapseWithTheRemote() {
        prepareFixture()
        val request = PlayRequest("movie", "fixture:source-deck", "Source Deck")
        val session = instrumentation.targetContext.appGraph.playback
        compose.setContent { TvTheme(reduceMotion = true) {
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.CenterEnd) {
                drawerFixtureBackdrop()
                PlayerBrowsePanel(request, episodes = false, session, onPreparing = {}, onClose = {}, onSources = {})
            }
        } }
        val more = compose.onNodeWithText("Show more")
        compose.waitUntil(20_000) { runCatching { more.assertIsDisplayed() }.isSuccess }
        screenshot("source-player-compact.png")
        fun focusToggle(label: String) {
            repeat(12) {
                if (runCatching { compose.onNodeWithText(label).assertIsFocused() }.isSuccess) return
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                compose.waitForIdle()
            }
            compose.onNodeWithText(label).assertIsFocused()
        }
        focusToggle("Show more")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText("Show less").assertIsDisplayed()
        screenshot("source-player-expanded.png")
        focusToggle("Show less")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        more.assertIsDisplayed()
    }

    @Test fun calendarEpisodeOpensFreshSourcesPlaysAndReturnsToTheSameDayRow() {
        prepareFixture()
        val context = instrumentation.targetContext
        val client = context.clientGraph
        val today = java.time.LocalDate.now()
        val id = "fixture:calendar-$today-${SystemClock.uptimeMillis()}"
        val title = "Calendar target fixture"
        val day = CalendarMonth.dayLabel(today)
        val dayDescription = "$day, 2 episodes, today"
        runBlocking {
            context.appGraph.playback.stop().getOrThrow()
            instrumentation.runOnMainSync { client.playFlow.clearCurrent() }
            withTimeout(15_000) { client.profileSession.state.first { it is ProfileSessionState.Unlocked } }
        }
        val library = client.activeLibrary
        runBlocking {
            library.saveToWatchlist(id, "series", title, "$fixtureBase/new.png")
            library.recordLocalPlay(id, "series", title, posterUrl = "$fixtureBase/new.png", season = 1, episode = 1)
            library.writeProgress(id, "series", season = 1, episode = 1, positionMs = 60_000, durationMs = 600_000)
        }
        assertEquals(0, nativeUiFixture("/calendar/state?id=$id").getJSONArray("requests").length())
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                runBlocking {
                    withTimeout(15_000) { requireNotNull(context.appGraph.receiverHost).settingsConfigured.first { it } }
                    withTimeout(15_000) { client.services.filterNotNull().first() }
                }
                instrumentation.waitForIdleSync()
                compose.waitUntil(5_000) {
                    var windowReady = false
                    scenario.onActivity { windowReady = it.hasWindowFocus() }
                    windowReady && compose.onAllNodes(androidx.compose.ui.test.isFocused())
                        .fetchSemanticsNodes().isNotEmpty()
                }
                UiDevice.getInstance(instrumentation).waitForIdle()
                key(KeyEvent.KEYCODE_MENU)
                try {
                    compose.waitUntil(5_000) {
                        runCatching {
                            compose.onNode(androidx.compose.ui.test.SemanticsMatcher.expectValue(
                                androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Current screen")
                                and androidx.compose.ui.test.isFocused()).assertExists()
                        }.isSuccess
                    }
                } catch (failure: Throwable) {
                    val focused = runCatching {
                        compose.onAllNodes(androidx.compose.ui.test.isFocused(), useUnmergedTree = true)
                            .fetchSemanticsNodes().joinToString("\n") { "${it.boundsInRoot} ${it.config}" }
                    }.getOrDefault("Focused semantics unavailable")
                    runCatching {
                        screenshot("calendar-rail-failure.png")
                        val folder = File(context.getExternalFilesDir(null), "remote-checks").apply { mkdirs() }
                        UiDevice.getInstance(instrumentation).dumpWindowHierarchy(File(folder, "calendar-rail-failure.xml"))
                    }
                    var windowState = "unavailable"
                    runCatching { scenario.onActivity { windowState = "windowFocused=${it.hasWindowFocus()} lifecycle=${it.lifecycle.currentState}" } }
                    failure.addSuppressed(AssertionError("Calendar Menu $windowState focus:\n$focused"))
                    throw failure
                }
                // Reach the first rail item, then Calendar in the existing seven-item order.
                repeat(8) { key(KeyEvent.KEYCODE_DPAD_UP) }
                repeat(5) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
                compose.onNodeWithContentDescription("Calendar").assertIsFocused()
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                compose.waitUntil(15_000) {
                    compose.onAllNodesWithText("Building your calendar").fetchSemanticsNodes().isEmpty() &&
                        runCatching { compose.onNodeWithContentDescription(dayDescription).assertIsFocused() }.isSuccess
                }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                compose.waitUntil(5_000) {
                    runCatching { compose.onNodeWithText("S3 E4 · Earlier row").assertIsFocused() }.isSuccess
                }
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                compose.onNodeWithText("S3 E5 · Selected row").assertIsFocused()
                compose.onNodeWithText("Synopsis for the selected episode.").assertIsDisplayed()
                compose.onAllNodesWithText("Series started 2020-01-01")[1].assertIsDisplayed()
                screenshot("calendar-episode-cards.png")
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText("S3 E5 · Selected row ·", substring = true).fetchSemanticsNodes().isNotEmpty() &&
                        nativeUiFixture("/calendar/state?id=$id").getJSONArray("requests").length() > 0
                }
                compose.onNodeWithText("Calendar").assertIsDisplayed()
                compose.onNodeWithText("Sources").assertIsDisplayed()
                compose.onNodeWithContentDescription("Play S3 E5").assertDoesNotExist()
                val requests = nativeUiFixture("/calendar/state?id=$id").getJSONArray("requests")
                assertTrue((0 until requests.length()).all {
                    val request = requests.getJSONObject(it)
                    request.getString("method") == "GET" &&
                        request.getString("path") == "/stream/series/$id:3:5.json"
                })
                val stored = runBlocking { library.playbackHistory().first().first { row -> row.canonicalId == id } }
                assertEquals(1, stored.season)
                assertEquals(1, stored.episode)
                assertEquals("Sources must not overwrite old progress", 60_000L, stored.positionMs)
                val clearFilters = compose.onNodeWithContentDescription("Clear filters")
                compose.waitUntil(15_000) { runCatching { clearFilters.assertIsDisplayed() }.isSuccess }
                clearFilters.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
                val firstSource = compose.onNodeWithContentDescription("720p", substring = true)
                compose.waitUntil(15_000) { runCatching { firstSource.assertIsDisplayed() }.isSuccess }
                if (runCatching { firstSource.assertIsFocused() }.isFailure) key(KeyEvent.KEYCODE_DPAD_DOWN)
                compose.waitForIdle()
                UiDevice.getInstance(instrumentation).waitForIdle()
                screenshot("calendar-selected-s3e5-sources.png")
                compose.waitUntil(15_000) {
                    runCatching { compose.onNodeWithContentDescription("720p", substring = true).assertIsFocused() }.isSuccess
                }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                runBlocking {
                    withTimeout(20_000) { context.appGraph.playback.phase.first { it == ReceiverPlaybackPhase.Playing } }
                }
                assertEquals(id, client.playFlow.current.value?.id)
                assertEquals(3, client.playFlow.current.value?.season)
                assertEquals(5, client.playFlow.current.value?.episode)
                screenshot("calendar-selected-s3e5-player.png")
                key(KeyEvent.KEYCODE_BACK)
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithText("S3 E5 · Selected row ·", substring = true).fetchSemanticsNodes().isNotEmpty()
                }
                key(KeyEvent.KEYCODE_BACK)
                compose.waitUntil(5_000) {
                    runCatching { compose.onNodeWithText("S3 E5 · Selected row").assertIsFocused() }.isSuccess
                }
                compose.onNodeWithText(day).assertExists()
                screenshot("calendar-returned-selected-row.png")
                key(KeyEvent.KEYCODE_BACK)
                compose.waitUntil(5_000) {
                    runCatching { compose.onNodeWithContentDescription(dayDescription).assertIsFocused() }.isSuccess
                }
            }
        } finally {
            runBlocking {
                context.appGraph.playback.stop()
                instrumentation.runOnMainSync { client.playFlow.clearCurrent() }
                library.markWatched(id, "series", season = 3, episode = 5)
                library.collectionsContaining(id).first().forEach { library.removeItem(it, id) }
                // Only this synthetic title is changed; do not leave it in Continue Watching.
                library.markWatched(id, "series", season = 1, episode = 1)
            }
        }
    }

    private fun nativeUiFixture(path: String): org.json.JSONObject {
        check(path.startsWith("/calendar/") || path.startsWith("/picker-proxy/") || path.startsWith("/live-focus/"))
        val connection = java.net.URL("$fixtureBase$path").openConnection().apply {
            connectTimeout = 2_000
            readTimeout = 2_000
            useCaches = false
        }
        return connection.getInputStream().bufferedReader().use { org.json.JSONObject(it.readText()) }
    }


    @Test fun backupImportAndExportKeepThePanelUsableWhenNoPickerExists() {
        prepareFixture()
        val context = instrumentation.targetContext
        val repo = context.clientGraph.iptv
        runBlocking { withTimeout(15_000) { repo.awaitLoaded() } }
        val accounts = repo.state.value.accounts
        check(accounts.sources.isEmpty() && accounts.recordings.isEmpty()) {
            "A fresh disposable IPTV vault is required"
        }
        val launches = java.util.concurrent.CopyOnWriteArrayList<String>()
        val registry = rejectingPickerRegistry(launches)
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        var open by mutableStateOf(true)
        compose.setContent { TvTheme(reduceMotion = true) {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                if (open) {
                    BackHandler { open = false }
                    LiveBackupPanel(repo, onClose = { open = false }, onMessage = {})
                } else Text("Backup fixture closed")
            }
        } }
        val field = compose.onNodeWithContentDescription("Backup passphrase (8 characters or more)")
        field.performTextInput("fixture-passphrase")
        closeSoftKeyboard()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("Export encrypted setup").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("Replace from encrypted backup").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { launches.size == 1 }
        compose.onNodeWithText("This TV has no file picker. Press Back to return.").assertExists()
        compose.onNodeWithText("Export encrypted setup").assertIsNotEnabled()
        compose.onNodeWithText("Replace from encrypted backup").assertIsNotEnabled()
        // A second action must be possible after recovery, with a newly entered passphrase.
        key(KeyEvent.KEYCODE_DPAD_UP)
        key(KeyEvent.KEYCODE_DPAD_UP)
        field.assertIsFocused()
        field.performTextInput("another-fixture-passphrase")
        closeSoftKeyboard()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("Export encrypted setup").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(15_000) { launches.size == 2 }
        compose.onNodeWithText("This TV has no file picker. Press Back to return.").assertExists()
        compose.onNodeWithText("Export encrypted setup").assertIsNotEnabled()
        compose.onNodeWithText("Replace from encrypted backup").assertIsNotEnabled()
        assertEquals(listOf(Intent.ACTION_OPEN_DOCUMENT, Intent.ACTION_CREATE_DOCUMENT), launches.toList())
        assertEquals(accounts.sources, repo.state.value.accounts.sources)
        assertEquals(accounts.favoriteIds, repo.state.value.accounts.favoriteIds)
        assertEquals(accounts.favoriteLists, repo.state.value.accounts.favoriteLists)
        assertEquals(accounts.recordings, repo.state.value.accounts.recordings)
        screenshot("backup-missing-file-picker.png")
        key(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText("Backup fixture closed").assertExists()
    }

    @Test fun stremioConnectingFileActionKeepsRecoveryAndCancelWhenNoPickerExists() {
        prepareFixture()
        nativeUiFixture("/picker-proxy/reset")
        val previous = java.net.ProxySelector.getDefault()
        val base = java.net.URI(fixtureBase)
        val localProxy = java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(base.host, base.port))
        // The new importer captures this selector. The fixture refuses the tunnel, so no
        // Stremio TLS connection, device link, login or account request leaves the emulator.
        val isolated = object : java.net.ProxySelector() {
            override fun select(uri: java.net.URI): MutableList<java.net.Proxy> =
                if (uri.host in setOf("link.stremio.com", "api.strem.io")) mutableListOf(localProxy)
                else previous?.select(uri) ?: mutableListOf(java.net.Proxy.NO_PROXY)
            override fun connectFailed(uri: java.net.URI, address: java.net.SocketAddress, failure: java.io.IOException) = Unit
        }
        val launches = java.util.concurrent.CopyOnWriteArrayList<String>()
        val context = instrumentation.targetContext
        val fixture = File(context.cacheDir, "stremio-settings-review.json").apply {
            writeText("""[{"transportUrl":"https://addon.example/manifest.json","manifest":{"name":"Review fixture"}}]""")
        }
        val fixtureUri = androidx.core.content.FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", fixture,
        )
        var returnFile = false
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>,
                input: I, options: ActivityOptionsCompat?) {
                launches += contract.createIntent(context, input).action.orEmpty()
                if (!returnFile) throw ActivityNotFoundException("No picker in the disposable fixture")
                android.os.Handler(context.mainLooper).post {
                    dispatchResult(requestCode, android.app.Activity.RESULT_OK, Intent().setData(fixtureUri))
                }
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        var open by mutableStateOf(true)
        java.net.ProxySelector.setDefault(isolated)
        try {
            compose.setContent { TvTheme(reduceMotion = true) {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    if (open) StremioImportPanel { open = false } else Text("Stremio fixture closed")
                }
            } }
            compose.waitUntil(5_000) { nativeUiFixture("/picker-proxy/state").optBoolean("waiting") }
            compose.onNodeWithText("Connect Stremio").assertExists()
            compose.onNodeWithText("Requesting a Stremio code…").assertExists()
            compose.onNodeWithText("Use a JSON file").assertIsFocused()
            repeat(2) {
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                compose.waitUntil(5_000) { launches.size == it + 1 }
                compose.onNodeWithText("This TV has no file picker. Link Stremio with your phone.").assertExists()
                compose.onNodeWithText("Use a JSON file").assertIsEnabled()
                compose.onNodeWithText("Use a JSON file").assertIsFocused()
                compose.onNodeWithText("Connect Stremio").assertExists()
            }
            assertEquals(listOf(Intent.ACTION_OPEN_DOCUMENT, Intent.ACTION_OPEN_DOCUMENT), launches.toList())
            screenshot("stremio-connecting-missing-picker.png")
            compose.runOnIdle { returnFile = true }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Review Stremio add-ons").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Add & update").assertIsFocused()
            screenshot("stremio-review.png")
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            compose.onNodeWithText("Replace add-ons").assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNodeWithText("Keep current add-ons").assertIsFocused()
            screenshot("stremio-replace-warning.png")
            key(KeyEvent.KEYCODE_BACK)
            compose.onNodeWithText("Review Stremio add-ons").assertExists()
            assertTrue(context.clientGraph.services.value?.document?.tvImportedSources.orEmpty().isEmpty())
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            compose.onNodeWithText("Cancel").assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNodeWithText("Stremio fixture closed").assertExists()
        } finally {
            try { compose.runOnIdle { open = false } }
            finally {
                java.net.ProxySelector.setDefault(previous)
                fixture.delete()
                nativeUiFixture("/picker-proxy/release")
            }
        }
    }

    private fun rejectingPickerRegistry(launches: MutableList<String>): ActivityResultRegistry =
        object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>,
                input: I, options: ActivityOptionsCompat?) {
                launches += contract.createIntent(instrumentation.targetContext, input).action.orEmpty()
                throw ActivityNotFoundException("No picker in the disposable fixture")
            }
        }


    @Test fun liveSeriesGridAndSearchReturnToTheActualScrolledOpener() {
        prepareFixture()
        val context = instrumentation.targetContext
        val client = context.clientGraph
        val repo = client.iptv
        runBlocking {
            withTimeout(15_000) { repo.awaitLoaded() }
            context.appGraph.playback.stop().getOrThrow()
            instrumentation.runOnMainSync { client.playFlow.clearCurrent() }
        }
        check(repo.state.value.sources.isEmpty() && repo.state.value.accounts.recordings.isEmpty() &&
            repo.state.value.accounts.decisionApiKey == null) {
            "A fresh disposable IPTV vault is required"
        }
        val order = context.getSharedPreferences("tv_episode_order", 0).getString("order", "Latest episode")
            ?: "Latest episode"
        val orderLabel = "Order: $order"
        val firstCard = "Focus fixture series 01, TV series"
        val targetCard = "Focus fixture series 13, TV series"
        val firstResult = "Series · Focus fixture series 01"
        val targetResult = "Series · Focus fixture series 13"
        val query = "Focus fixture"
        nativeUiFixture("/live-focus/reset")
        var sourceId: String? = null
        try {
            runBlocking {
                assertTrue(repo.addXtream("Live focus fixture", fixtureBase, "focus-fixture-user", "focus-fixture-pass"))
                sourceId = repo.state.value.sources.single { it.name == "Live focus fixture" }.id
                withTimeout(20_000) {
                    repo.state.first { state -> !state.refreshing && !state.catalogLoading &&
                        state.catalog.vod.count { it.sourceId == sourceId && it.kind == "series" } == 20 }
                }
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                runBlocking {
                    withTimeout(15_000) { requireNotNull(context.appGraph.receiverHost).settingsConfigured.first { it } }
                    withTimeout(15_000) { client.profileSession.state.first { it is ProfileSessionState.Unlocked } }
                }
                compose.waitUntil(10_000) {
                    var windowReady = false
                    scenario.onActivity { windowReady = it.hasWindowFocus() }
                    windowReady && runCatching { compose.onNodeWithTag(HOME_BROWSE_TAG).assertIsDisplayed() }.isSuccess &&
                        compose.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().isNotEmpty()
                }
                key(KeyEvent.KEYCODE_MENU)
                compose.waitUntil(5_000) {
                    runCatching { compose.onNodeWithContentDescription("Home").assertIsFocused() }.isSuccess ||
                        runCatching { compose.onNodeWithContentDescription("Live TV").assertIsFocused() }.isSuccess
                }
                repeat(8) { key(KeyEvent.KEYCODE_DPAD_UP) }
                repeat(2) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
                compose.onNodeWithContentDescription("Live TV").assertIsFocused()
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                compose.waitUntil(5_000) {
                    runCatching { compose.onNodeWithText("Favorites").assertIsFocused() }.isSuccess
                }
                repeat(5) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
                compose.onNodeWithText("Series").assertIsFocused()
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                compose.waitUntil(5_000) {
                    runCatching { compose.onNodeWithText("All series").assertIsFocused() }.isSuccess
                }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                compose.waitUntil(5_000) {
                    runCatching { compose.onNodeWithContentDescription(firstCard).assertIsFocused() }.isSuccess
                }
                fun moveToCard(code: Int, expected: String) {
                    key(code)
                    try {
                        compose.waitUntil(5_000) {
                            runCatching { compose.onNodeWithContentDescription(expected).assertIsFocused() }.isSuccess
                        }
                        UiDevice.getInstance(instrumentation).waitForIdle()
                        compose.waitForIdle()
                        compose.onNodeWithContentDescription(expected).assertIsFocused()
                    } catch (failure: Throwable) {
                        val focused = runCatching {
                            compose.onAllNodes(androidx.compose.ui.test.isFocused(), useUnmergedTree = true)
                                .fetchSemanticsNodes().joinToString("\n") {
                                    "id=${it.id} bounds=${it.boundsInRoot} ${it.config}"
                                }
                        }.getOrDefault("Focused semantics unavailable")
                        runCatching { screenshot("live-focus-grid-failure.png") }
                        failure.addSuppressed(AssertionError(
                            "After ${KeyEvent.keyCodeToString(code)} expected $expected; focused nodes:\n$focused"))
                        throw failure
                    }
                }
                moveToCard(KeyEvent.KEYCODE_DPAD_RIGHT, "Focus fixture series 02, TV series")
                moveToCard(KeyEvent.KEYCODE_DPAD_RIGHT, "Focus fixture series 03, TV series")
                moveToCard(KeyEvent.KEYCODE_DPAD_DOWN, "Focus fixture series 08, TV series")
                // The baseline grid enters the first item when revealing this virtualized row.
                moveToCard(KeyEvent.KEYCODE_DPAD_DOWN, "Focus fixture series 11, TV series")
                moveToCard(KeyEvent.KEYCODE_DPAD_RIGHT, "Focus fixture series 12, TV series")
                moveToCard(KeyEvent.KEYCODE_DPAD_RIGHT, targetCard)
                compose.onNodeWithContentDescription(targetCard).assertIsFocused().assertIsDisplayed()
                compose.onNodeWithContentDescription(firstCard).assertIsNotDisplayed()
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                compose.waitUntil(5_000) { nativeUiFixture("/live-focus/state").optBoolean("waiting") }
                compose.onNodeWithText("Loading episodes…").assertExists()
                compose.onNodeWithText(orderLabel).assertIsFocused()
                nativeUiFixture("/live-focus/release")
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithText("No episodes returned by this source.").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText(orderLabel).assertIsFocused()
                val details = nativeUiFixture("/live-focus/state").getJSONArray("details")
                assertEquals(1, details.length())
                assertEquals("13", details.getString(0))
                compose.waitForIdle()
                UiDevice.getInstance(instrumentation).waitForIdle()
                compose.onNodeWithText(orderLabel).assertIsFocused()
                screenshot("live-focus-grid-empty.png")
                moveToCard(KeyEvent.KEYCODE_BACK, targetCard)
                compose.onNodeWithContentDescription(targetCard).assertIsDisplayed()
                compose.onNodeWithContentDescription(firstCard).assertIsNotDisplayed()
                compose.waitForIdle()
                UiDevice.getInstance(instrumentation).waitForIdle()
                compose.onNodeWithContentDescription(targetCard).assertIsFocused()
                screenshot("live-focus-grid-return.png")

                // The existing rail hand-back returns to the remembered Series tab.
                key(KeyEvent.KEYCODE_MENU)
                compose.waitUntil(5_000) {
                    runCatching { compose.onNodeWithContentDescription("Live TV").assertIsFocused() }.isSuccess
                }
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                compose.waitUntil(5_000) {
                    runCatching { compose.onNodeWithText("Series").assertIsFocused() }.isSuccess
                }
                key(KeyEvent.KEYCODE_DPAD_UP)
                if (runCatching { compose.onNodeWithText("Refresh").assertIsFocused() }.isSuccess)
                    key(KeyEvent.KEYCODE_DPAD_LEFT)
                compose.onNodeWithText("Search").assertIsFocused()
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                val field = compose.onNodeWithContentDescription("Channels, programs, movies, series")
                field.performTextInput(query)
                closeSoftKeyboard()
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithText("Searching saved TV library…").fetchSemanticsNodes().isEmpty() &&
                        compose.onAllNodesWithText(firstResult).fetchSemanticsNodes().isNotEmpty()
                }
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                compose.waitUntil(5_000) {
                    runCatching { compose.onNodeWithText(firstResult).assertIsFocused() }.isSuccess
                }
                repeat(12) { key(KeyEvent.KEYCODE_DPAD_DOWN); compose.waitForIdle() }
                compose.onNodeWithText(targetResult).assertIsFocused().assertIsDisplayed()
                compose.onNodeWithText(firstResult).assertIsNotDisplayed()
                field.assertTextEquals(query)
                compose.waitForIdle()
                UiDevice.getInstance(instrumentation).waitForIdle()
                compose.onNodeWithText(targetResult).assertIsFocused()
                screenshot("live-focus-search-before.png")
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithText("No episodes returned by this source.").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText(orderLabel).assertIsFocused()
                key(KeyEvent.KEYCODE_BACK)
                compose.waitUntil(5_000) {
                    runCatching { compose.onNodeWithText(targetResult).assertIsFocused() }.isSuccess
                }
                field.assertTextEquals(query)
                compose.onNodeWithText(targetResult).assertIsDisplayed()
                compose.onNodeWithText(firstResult).assertIsNotDisplayed()
                compose.waitForIdle()
                UiDevice.getInstance(instrumentation).waitForIdle()
                compose.onNodeWithText(targetResult).assertIsFocused()
                screenshot("live-focus-search-return.png")
                // Same source/item uses the repository's ordinary ten-minute empty-details cache.
                assertEquals(1, nativeUiFixture("/live-focus/state").getJSONArray("details").length())
                key(KeyEvent.KEYCODE_BACK)
            }
        } finally {
            try { nativeUiFixture("/live-focus/release") }
            finally { runBlocking { sourceId?.let { repo.removeSource(it) } } }
        }
    }

    private fun seedTamilMvCatalog(items: List<CatalogItem>, updatedAt: Long) = runBlocking {
        val context = instrumentation.targetContext
        val saved = File(context.filesDir, "receiver-tamilmv/catalog.json")
        saved.parentFile?.mkdirs()
        saved.writeText("""{"updatedAtMillis":$updatedAt,"items":${Json.encodeToString(items)}}""")
        context.clientGraph.tamilCatalog.hydrate()
        context.clientGraph.snapshots.setReceiverTamilMV(items, updatedAt)
    }

    @Test fun tamilMvPosterGridMovesTheTitleAndKeepsFocusAfterRefresh() {
        prepareFixture()
        val context = instrumentation.targetContext
        val graph = context.clientGraph
        // The same journey can run against Den TV's pre-change build for a timing baseline.
        val baseline = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode == 168L
        val now = System.currentTimeMillis()
        val titles = (1..8).map { index ->
            CatalogItem("saved:tamilmv:fixture-$index", "movie", "Tamil fixture film $index", year = 2026,
                posterUrl = "$fixtureBase/new.png?tamil=$now-$index")
        }
        val previous = graph.snapshots.shelves().value.firstOrNull { it.id == "tamilmv:receiver" }?.items.orEmpty()
        val nav = java.lang.reflect.Proxy.newProxyInstance(
            com.fourseveneightnine.tv.client.ui.nav.ClientNav::class.java.classLoader,
            arrayOf(com.fourseveneightnine.tv.client.ui.nav.ClientNav::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "TamilMVFixtureNav"
                else -> null
            }
        } as com.fourseveneightnine.tv.client.ui.nav.ClientNav
        fun poster(item: CatalogItem) = compose.onNodeWithContentDescription(item.title + ",", substring = true)
        fun header(item: CatalogItem) = compose.onNodeWithText("${item.title} · ${item.year}")
        // Seed the receiver repository so automatic refresh keeps this disposable fixture.
        try {
            seedTamilMvCatalog(titles, now)
            runBlocking {
                withTimeout(5_000) { graph.collectionShelves.first { shelves -> shelves.any { it.items == titles } } }
            }
            compose.setContent {
                TvTheme(reduceMotion = true) {
                    CompositionLocalProvider(com.fourseveneightnine.tv.client.ui.LocalShellState provides
                        androidx.compose.runtime.remember { com.fourseveneightnine.tv.client.ui.ShellState() }) {
                        CollectionsScreen(nav, active = true)
                    }
                }
            }
            val collection = hasContentDescription("TamilMV Collection,", substring = true)
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(collection)
            compose.onNode(collection).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
            compose.onNode(collection).assertIsFocused()
            val start = SystemClock.elapsedRealtime()
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            try {
                compose.waitUntil(8_000) { runCatching { poster(titles[0]).assertIsFocused() }.isSuccess }
                compose.waitUntil(5_000) { PaintedArtwork.wasPainted(titles[0].posterUrl) }
            } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
                screenshot("tamilmv-poster-focus-failure.png")
                poster(titles[0]).assertIsFocused()
                throw timeout
            }
            val firstPosterMillis = SystemClock.elapsedRealtime() - start
            val firstHeader = if (baseline) null else header(titles[0]).fetchSemanticsNode().boundsInRoot
            if (firstHeader != null) {
                val firstPoster = poster(titles[0]).fetchSemanticsNode().boundsInRoot
                assertTrue("Focused title appears above the first poster", firstHeader.bottom < firstPoster.top)
                assertEquals(firstPoster.left, firstHeader.left, 8f)
            }
            val moveTimes = mutableListOf<Long>()
            repeat(24) { index ->
                val movingRight = index % 2 == 0
                val started = SystemClock.elapsedRealtime()
                key(if (movingRight) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
                val selected = titles[if (movingRight) 1 else 0]
                poster(selected).assertIsFocused()
                moveTimes += SystemClock.elapsedRealtime() - started
                if (firstHeader != null)
                    assertEquals(firstHeader.top, header(selected).fetchSemanticsNode().boundsInRoot.top, 1f)
            }
            val sorted = moveTimes.sorted()
            android.util.Log.i("4789TamilMVTest", "baseline=$baseline firstPosterMs=$firstPosterMillis " +
                "focusDpadMedianMs=${sorted[sorted.size / 2]} focusDpadP95Ms=${sorted[22]}")
            if (!baseline) {
                assertTrue(compose.onAllNodesWithText(titles[1].title).fetchSemanticsNodes().isEmpty())
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                poster(titles[1]).assertIsFocused()
                val replacement = titles[1].copy(canonicalId = "saved:tamilmv:fixture-new-edition")
                runBlocking { graph.snapshots.setReceiverTamilMV(listOf(replacement) + titles.filterNot { it == titles[1] }, now + 1) }
                compose.waitUntil(5_000) {
                    runCatching {
                        poster(replacement).assertIsFocused()
                        header(replacement).assertExists()
                    }.isSuccess
                }
            }
            screenshot("tamilmv-poster-only-focus.png")
        } finally {
            runBlocking { graph.snapshots.setReceiverTamilMV(previous, now) }
        }
    }

    @Test fun tamilMvMovieBundlesOpenTwoOrThreeFilesAndUseTheSelectedTorBoxId() {
        prepareFixture()
        val context = instrumentation.targetContext
        val client = context.clientGraph
        val field = client.javaClass.getDeclaredField("mutableServices").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val services = field.get(client) as kotlinx.coroutines.flow.MutableStateFlow<com.fourseveneightnine.tv.client.ClientGraph.DataServices?>
        val original = requireNotNull(services.value)
        val duologyHash = "a".repeat(40)
        val trilogyHash = "b".repeat(40)
        fun files(count: Int) = org.json.JSONArray().apply {
            for (part in (1..count).reversed()) put(org.json.JSONObject().put("id", part + 6)
                .put("name", "Fixture Film $part (${2010 + part}).mp4").put("size", 8_000_000L))
            put(org.json.JSONObject().put("id", 27).put("name", "Extras/Trailer.mp4").put("size", 1000L))
        }
        fun torrent(hash: String, count: Int) = org.json.JSONObject().put("id", 71)
            .put("hash", hash).put("name", "Fixture bundle BluRay").put("files", files(count))
        val downloads = java.util.concurrent.CopyOnWriteArrayList<Pair<String?, String?>>()
        val accountReads = java.util.concurrent.atomic.AtomicInteger()
        val transport = client.okHttp.newBuilder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.pathSegments.last()
            if (path == "mylist" && accountReads.incrementAndGet() == 1) {
                val pending = """{"success":false,"error":"ITEM_NOT_FOUND"}""".toResponseBody("application/json".toMediaType())
                return@addInterceptor okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(404).message("Pending").body(pending).build()
            }
            val result: Any = when (path) {
                "checkcached" -> org.json.JSONObject().apply {
                    val hashes = request.url.queryParameter("hash").orEmpty().split(',')
                    if (duologyHash in hashes) put(duologyHash, torrent(duologyHash, 2))
                    if (trilogyHash in hashes) put(trilogyHash, torrent(trilogyHash, 3))
                }
                "createtorrent" -> {
                    val body = request.body as okhttp3.MultipartBody
                    assertTrue(body.parts.any { it.headers?.get("Content-Disposition") == "form-data; name=\"add_only_if_cached\"" })
                    org.json.JSONObject().put("torrent_id", 71)
                }
                "mylist" -> torrent(trilogyHash, 3)
                "requestdl" -> {
                    downloads += request.url.queryParameter("torrent_id") to request.url.queryParameter("file_id")
                    "$fixtureBase/clip.mp4"
                }
                else -> error("Unexpected torrent fixture call $path")
            }
            val jsonBody = org.json.JSONObject().put("success", true).put("data", result).toString()
            val body = jsonBody.toResponseBody("application/json".toMediaType())
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body(body).build()
        }.build()
        val debrid = com.fourseveneightnine.tv.client.data.streams.debrid.DebridResolver(
            "fixture", null, transport, torboxBaseURL = "$fixtureBase/movie-bundle")
        fun bundle(name: String, hash: String) =
            CatalogItem("saved:tamilmv:$hash", "movie", name, year = 2011, posterUrl = "$fixtureBase/new.png?bundle=$hash",
                playbackSources = listOf(CatalogPlaybackSource(hash, "$name BluRay")))
        val duology = bundle("Fixture Duology", duologyHash)
        val trilogy = bundle("Fixture Trilogy", trilogyHash)
        val navType = com.fourseveneightnine.tv.client.ui.nav.ClientNav::class.java
        val nav = java.lang.reflect.Proxy.newProxyInstance(navType.classLoader, arrayOf(navType)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "TamilMVBundleFixtureNav"
                else -> null
            }
        } as com.fourseveneightnine.tv.client.ui.nav.ClientNav
        fun part(number: Int) = compose.onNodeWithContentDescription(
            "Part $number · Fixture Film $number (${2010 + number}).mp4")
        try {
            services.value = com.fourseveneightnine.tv.client.ClientGraph.DataServices(original.document,
                original.health, original.client, original.registry, original.meta, original.ratings, original.search, debrid)
            seedTamilMvCatalog(listOf(duology, trilogy), System.currentTimeMillis())
            runBlocking { withTimeout(5_000) { client.collectionShelves.first { shelves -> shelves.any { it.items == listOf(duology, trilogy) } } } }
            compose.setContent {
                TvTheme(reduceMotion = true) {
                    CompositionLocalProvider(com.fourseveneightnine.tv.client.ui.LocalShellState provides
                        androidx.compose.runtime.remember { com.fourseveneightnine.tv.client.ui.ShellState() }) {
                        CollectionsScreen(nav, active = true)
                    }
                }
            }
            val collection = hasContentDescription("TamilMV Collection,", substring = true)
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(collection)
            compose.onNode(collection).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(5_000) { runCatching { compose.onNodeWithContentDescription("Fixture Duology,", substring = true).assertIsFocused() }.isSuccess }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(8_000) { runCatching { part(1).assertIsFocused() }.isSuccess }
            compose.onNodeWithText("Movies · Fixture Duology").assertExists()
            part(2).assertExists()
            part(3).assertDoesNotExist()
            assertTrue(downloads.isEmpty())
            screenshot("tamilmv-duology-files.png")

            key(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(5_000) { runCatching { compose.onNodeWithContentDescription("Fixture Duology,", substring = true).assertIsFocused() }.isSuccess }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            compose.onNodeWithContentDescription("Fixture Trilogy,", substring = true).assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(8_000) { runCatching { part(1).assertIsFocused() }.isSuccess }
            compose.onNodeWithText("Movies · Fixture Trilogy").assertExists()
            part(2).assertExists()
            part(3).assertExists()
            part(4).assertDoesNotExist()
            screenshot("tamilmv-trilogy-files.png")
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            part(3).assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(10_000) { downloads.isNotEmpty() }
            assertEquals(listOf("71" to "9"), downloads.toList())
            assertEquals(2, accountReads.get())
        } finally {
            services.value = original
        }
    }

    @Test fun tamilMvEpisodesRemainAvailableWithoutExternalEpisodeMetadata() {
        prepareFixture()
        val context = instrumentation.targetContext
        val release = CatalogItem("saved:tamilmv:episode-fixture", "series", "Part-Timers fixture", season = 1,
            playbackSources = listOf(CatalogPlaybackSource("a".repeat(40), "Part-Timers S01", season = 1,
                episodes = listOf(1, 2, 3, 4))))
        seedTamilMvCatalog(listOf(release), System.currentTimeMillis())
        var showEpisodes by mutableStateOf(false)
        compose.setContent { TvTheme(reduceMotion = true) {
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.CenterEnd) {
                drawerFixtureBackdrop()
            PlayerBrowsePanel(PlayRequest("series", release.canonicalId, release.title, season = 1, episode = 3),
                episodes = showEpisodes, session = context.appGraph.playback, onPreparing = {}, onClose = {}, onSources = {})
            }
        } }
        compose.onNodeWithText("Sources").assertExists()
        compose.runOnIdle { showEpisodes = true }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Episode 4").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Episode 1").assertExists()
        compose.onNodeWithText("Episode 2").assertExists()
        compose.onNodeWithText("Episode 3").assertExists()
        compose.onNodeWithText("No episodes were supplied.").assertDoesNotExist()
        screenshot("tamilmv-player-episodes.png")
    }

    @Test fun rapidDirectionsKeepFocusAndDoNotActivateRows() {
        val first = FocusRequester()
        var clicks = 0
        compose.setContent {
            TvTheme(reduceMotion = true) {
                Column(Modifier.fillMaxSize().background(TvColor.Canvas)) {
                    repeat(6) { index ->
                        TvFocusable(onClick = { clicks++ }, accessibleLabel = "Row $index",
                            modifier = Modifier.width(700.dp).height(80.dp)
                                .then(if (index == 0) Modifier.focusRequester(first) else Modifier)) { focused ->
                            Text("Row $index", color = if (focused) TvColor.Focus else TvColor.TextPrimary)
                        }
                    }
                }
            }
        }
        compose.runOnIdle { first.requestFocus() }
        repeat(20) {
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            key(KeyEvent.KEYCODE_DPAD_UP)
        }
        compose.onNodeWithContentDescription("Row 0").assertIsFocused()
        screenshot("focus-row-0.png")
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithContentDescription("Row 1").assertIsFocused()
        screenshot("focus-row-1.png")
        key(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(0, clicks)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        assertEquals(1, clicks)
    }

    @Test fun heldOkFiresOneMenuAndLosingFocusCancelsThePress() {
        val first = FocusRequester()
        var clicks = 0
        var menus = 0
        compose.setContent {
            TvTheme(reduceMotion = true) {
                Column {
                    repeat(2) { index ->
                        TvFocusableWithMenu(onClick = { clicks++ }, onLongClick = { menus++ },
                            accessibleLabel = "Menu $index", modifier = Modifier.width(700.dp).height(90.dp)
                                .then(if (index == 0) Modifier.focusRequester(first) else Modifier)) {
                            Text("Menu $index")
                        }
                    }
                }
            }
        }
        compose.runOnIdle { first.requestFocus() }
        val down = SystemClock.uptimeMillis()
        event(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
        SystemClock.sleep(550)
        event(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 1)
        event(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 2)
        event(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        assertEquals(1, menus)
        assertEquals(0, clicks)
        val next = SystemClock.uptimeMillis()
        event(next, next, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        event(next, next + 100, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithContentDescription("Menu 1").assertIsFocused()
        assertEquals(0, clicks)
        assertEquals(1, menus)
    }

    @Test fun disabledFocusableRowCannotPlayAnything() {
        val focus = FocusRequester()
        var clicks = 0
        compose.setContent {
            TvTheme { TvFocusable(onClick = { clicks++ }, enabled = false, focusWhenDisabled = true,
                accessibleLabel = "Unavailable", modifier = Modifier.width(700.dp).height(90.dp).focusRequester(focus)) {
                Text("Unavailable")
            } }
        }
        compose.runOnIdle { focus.requestFocus() }
        compose.onNodeWithContentDescription("Unavailable").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        assertEquals(0, clicks)
    }

    @Test fun refreshedLetterboxdCollectionPaintsSavedPostersAcrossThreeRows() {
        prepareFixture()
        val context = instrumentation.targetContext
        val graph = context.clientGraph
        val now = System.currentTimeMillis()
        val owner = "fixture$now"
        val generation = "iphone-letterboxd-$now"
        val shelves = (1..12).map { list ->
            Shelf("letterboxd:liked:$owner/list-$list", "Poster collection $list", ShelfKind.LETTERBOXD,
                (1..12).map { film ->
                    CatalogItem("tmdb:$film", "movie", "Fixture poster $film", year = 2001,
                        posterUrl = "$fixtureBase/new.png?film=$owner-$list-$film")
                }, generation, now)
        }
        val selected = shelves.last()
        val publicOrder = selected.items.reversed()
        val publicFilms = publicOrder.map { film ->
            TVLetterboxdFilm(film.canonicalId.replace(':', '-'), "${film.title} (2001)", "$fixtureBase/old.png?public=$owner")
        }
        val stamp = TVLetterboxdListRefresh.Stamp("$owner/list-12", now + 1, publicFilms.size, publicFilms.take(4))
        val file = File(context.filesDir, "letterboxd-public-lists/$owner/list-12.list")
        try {
            file.parentFile?.mkdirs()
            file.writeText(Json.encodeToString(stamp) + "\n" + Json.encodeToString(publicFilms) + "\n")
            runBlocking {
                assertTrue(graph.snapshots.importPrivateSnapshot(generation, now, shelves))
                graph.publicLists.hydrate()
                withTimeout(5_000) { graph.collectionShelves.first { lists -> lists.any { it.id == selected.id } } }
            }
            val opened = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, String>>()
            val nav = java.lang.reflect.Proxy.newProxyInstance(
                com.fourseveneightnine.tv.client.ui.nav.ClientNav::class.java.classLoader,
                arrayOf(com.fourseveneightnine.tv.client.ui.nav.ClientNav::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "equals" -> proxy === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "LetterboxdFixtureNav"
                    "openDetail" -> { opened.add(args[0] as String to args[1] as String); null }
                    else -> null
                }
            } as com.fourseveneightnine.tv.client.ui.nav.ClientNav
            compose.setContent {
                TvTheme(reduceMotion = true) {
                    CompositionLocalProvider(com.fourseveneightnine.tv.client.ui.LocalShellState provides
                        androidx.compose.runtime.remember { com.fourseveneightnine.tv.client.ui.ShellState() }) {
                        CollectionsScreen(nav, active = true)
                    }
                }
            }
            val label = hasContentDescription(selected.title + ",", substring = true)
            compose.waitUntil(5_000) { compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().size == 1 }
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(label)
            compose.onNode(label).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
            compose.onNode(label).assertIsFocused()
            compose.waitUntil(5_000) {
                selected.items.take(FolderGrid.POSTER_COUNT).all { PaintedArtwork.wasPainted(it.posterUrl) }
            }
            screenshot("letterboxd-directory-compact-stacks.png")
            val start = SystemClock.elapsedRealtime()
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            for ((row, posters) in publicOrder.chunked(4).withIndex()) {
                if (row > 0) key(KeyEvent.KEYCODE_DPAD_DOWN)
                val first = compose.onNodeWithContentDescription(posters.first().title + ",", substring = true)
                try {
                    compose.waitUntil(5_000) {
                        runCatching { first.assertIsFocused() }.isSuccess &&
                            posters.all { PaintedArtwork.wasPainted(it.posterUrl) }
                    }
                } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
                    screenshot("letterboxd-collection-focus-failure.png")
                    val focused = compose.onAllNodes(isFocused()).fetchSemanticsNodes().map {
                        it.config.getOrNull(SemanticsProperties.ContentDescription)
                    }
                    throw AssertionError("Poster row ${row + 1}: focused=$focused, " +
                        "painted=${posters.count { PaintedArtwork.wasPainted(it.posterUrl) }}/${posters.size}", timeout)
                }
                if (row == 0) println("Letterboxd first four saved posters painted in ${SystemClock.elapsedRealtime() - start} ms")
            }
            screenshot("letterboxd-collection-saved-posters.png")
            assertFalse(PaintedArtwork.wasPainted(publicFilms.first().poster))

            val expected = publicOrder[8]
            val chosen = compose.onNodeWithContentDescription(expected.title + ",", substring = true)
            fun selectEvent(action: Int, downAt: Long) = instrumentation.sendKeySync(
                KeyEvent(downAt, downAt + if (action == KeyEvent.ACTION_DOWN) 0 else 80, action, KeyEvent.KEYCODE_DPAD_CENTER, 0),
            )
            val downAt = SystemClock.uptimeMillis()
            selectEvent(KeyEvent.ACTION_DOWN, downAt)
            key(KeyEvent.KEYCODE_DPAD_UP)
            chosen.assertIsFocused()
            assertTrue(opened.isEmpty())
            screenshot("letterboxd-collection-ok-pressed.png")
            selectEvent(KeyEvent.ACTION_UP, downAt)
            compose.waitForIdle()
            assertEquals(listOf("movie" to "letterboxd-film:" + publicFilms[8].slug), opened.toList())
        } finally {
            file.delete()
        }
    }

    @Test fun artworkUsesTheSavedPosterWhenThePreferredImageFails() {
        val suffix = SystemClock.uptimeMillis()
        val first = "$fixtureBase/missing-poster-$suffix.png"
        val fallback = "$fixtureBase/new.png?fallback=$suffix"
        compose.setContent {
            TvTheme(reduceMotion = true) {
                com.fourseveneightnine.tv.client.ui.components.TvArtwork(
                    request = com.fourseveneightnine.tv.client.data.images.PosterRequest.poster(first),
                    title = "Poster fallback fixture",
                    modifier = Modifier.size(236.dp, 354.dp),
                    posterFallbackUrl = fallback,
                )
            }
        }
        compose.waitUntil(5_000) { PaintedArtwork.wasPainted(fallback) }
        assertFalse(PaintedArtwork.wasPainted(first))
    }

    @Test fun collectionCoverFollowsNativeFocusAndReduceMotion() {
        compose.mainClock.autoAdvance = false
        val second = FocusRequester()
        var reduceMotion by mutableStateOf(false)
        val evidenceName = "collection-stack-${SystemClock.uptimeMillis()}"
        val posters = List(FolderGrid.POSTER_COUNT) { index ->
            val image = listOf("old.png", "logo.png", "series-logo.png").getOrElse(index) { "new.png" }
            val url = "$fixtureBase/$image?stack=$evidenceName-$index"
            HomeCard("stack-$index", "movie", "Poster $index",
                posterUrl = if (index == 0) "$fixtureBase/missing.png?stack=$evidenceName" else url,
                posterFallbackUrl = url.takeIf { index == 0 })
        }
        compose.setContent {
            TvTheme(reduceMotion = reduceMotion) {
                Row(Modifier.fillMaxSize().background(TvColor.Canvas).padding(48.dp),
                    horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.Top) {
                    TvFocusable(onClick = {}, accessibleLabel = "Stack collection", focusScale = 1f) { focused ->
                        Column(Modifier.padding(16.dp)) {
                            CollectionCover(TvColor.Accent, posters, focused = focused,
                                modifier = Modifier.width(480.dp).testTag("collection-stack-cover")) {}
                            Text("Collection stack caption", color = TvColor.TextPrimary,
                                modifier = Modifier.testTag("collection-stack-caption"))
                        }
                    }
                    TvFocusable(onClick = {}, accessibleLabel = "Other collection", focusScale = 1f,
                        modifier = Modifier.focusRequester(second)) {
                        Box(Modifier.width(380.dp).height(260.dp), contentAlignment = Alignment.Center) {
                            Text("Other collection", color = TvColor.TextPrimary)
                        }
                    }
                }
            }
        }
        compose.runOnIdle { second.requestFocus() }
        // Unique URLs ensure these receipts belong to the ten mounted posters.
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle { posters.all { PaintedArtwork.wasPainted(it.posterFallbackUrl ?: it.posterUrl) } }
        }
        fun settle() {
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle() // Android drawing follows Choreographer, not the test clock.
        }
        settle()
        compose.onNodeWithContentDescription("Other collection").assertIsFocused()
        val cover = compose.onNodeWithTag("collection-stack-cover", useUnmergedTree = true)
        val caption = compose.onNodeWithTag("collection-stack-caption", useUnmergedTree = true)
        val coverBounds = cover.fetchSemanticsNode().boundsInRoot
        val captionBounds = caption.fetchSemanticsNode().boundsInRoot
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "remote-checks").apply { mkdirs() }
        fun capture(stage: String): ImageBitmap {
            compose.waitForIdle()
            assertEquals("Cover bounds must stay fixed", coverBounds, cover.fetchSemanticsNode().boundsInRoot)
            assertEquals("Caption bounds must stay fixed", captionBounds, caption.fetchSemanticsNode().boundsInRoot)
            val image = cover.captureToImage()
            File(folder, "$evidenceName-$stage.png").outputStream().use {
                check(image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            return image
        }
        fun changedFraction(before: ImageBitmap, after: ImageBitmap, ignoreFront: Boolean = false): Float {
            assertEquals(before.width, after.width)
            assertEquals(before.height, after.height)
            val a = before.toPixelMap()
            val b = after.toPixelMap()
            var changed = 0
            var sampled = 0
            for (y in 0 until before.height step 2) for (x in 0 until before.width step 2) {
                if (ignoreFront && x in before.width * 28 / 100..before.width * 72 / 100) continue
                sampled++
                if (kotlin.math.abs(a[x, y].red - b[x, y].red) > 0.02f ||
                    kotlin.math.abs(a[x, y].green - b[x, y].green) > 0.02f ||
                    kotlin.math.abs(a[x, y].blue - b[x, y].blue) > 0.02f) changed++
            }
            return changed.toFloat() / sampled
        }
        fun paintedWidth(image: ImageBitmap): Int {
            val pixels = image.toPixelMap()
            var first = image.width
            var last = -1
            for (x in 0 until image.width step 2) {
                for (y in 0 until image.height step 2) {
                    val color = pixels[x, y]
                    if (maxOf(color.red, color.green, color.blue) - minOf(color.red, color.green, color.blue) > 0.55f) {
                        first = minOf(first, x)
                        last = x
                        break
                    }
                }
            }
            return (last - first).coerceAtLeast(0)
        }
        val packed = capture("normal-packed")
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        compose.mainClock.advanceTimeBy(90)
        val glance = capture("quick-glance")
        compose.mainClock.advanceTimeBy(120)
        val opening = capture("normal-opening")
        settle()
        compose.onNodeWithContentDescription("Stack collection").assertIsFocused()
        val fanned = capture("normal-fanned")
        assertTrue("Loaded fixture posters must remain visible", cyan(fanned) > 30 && magenta(fanned) > 30)
        assertTrue("Native focus must unfold the posters", changedFraction(packed, fanned) > 0.02f)
        val packedWidth = paintedWidth(packed)
        val openingWidth = paintedWidth(opening)
        val fannedWidth = paintedWidth(fanned)
        assertTrue("Moving past a stack must keep the rear covers packed",
            kotlin.math.abs(paintedWidth(glance) - packedWidth) <= 4)
        assertTrue("Focused art must open wider: $packedWidth → $fannedWidth", fannedWidth >= packedWidth * 1.30f)
        assertTrue("The stack must paint an intermediate width: $packedWidth → $openingWidth → $fannedWidth",
            openingWidth in packedWidth + 1 until fannedWidth)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        settle()
        compose.onNodeWithContentDescription("Other collection").assertIsFocused()
        val folded = capture("normal-folded")
        assertTrue("Leaving focus must fold the posters", changedFraction(fanned, folded) > 0.02f)
        assertTrue("The folded cover must return to its packed pixels", changedFraction(packed, folded) < 0.005f)
        compose.runOnIdle { reduceMotion = true }
        settle()
        val still = capture("reduce-packed")
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        settle()
        compose.onNodeWithContentDescription("Stack collection").assertIsFocused()
        assertTrue("Reduce Motion must keep the side covers fixed when the focused poster gains its ring",
            changedFraction(still, capture("reduce-focused"), ignoreFront = true) < 0.005f)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        settle()
        compose.onNodeWithContentDescription("Other collection").assertIsFocused()
        assertTrue("Reduce Motion must keep the cover still after focus leaves",
            changedFraction(still, capture("reduce-folded")) < 0.005f)
    }

    @Test fun homeHeroKeepsItsTitleUntilTheLogoPaints() {
        val title = "Pending hero fixture"
        val logo = "$fixtureBase/slow-logo.png?hero=${SystemClock.uptimeMillis()}"
        val card = HomeCard(id = "fixture:hero", type = "movie", title = title, logoUrl = logo)
        compose.setContent { TvTheme(reduceMotion = true) { HomeHero(card = { card }) } }

        compose.onNodeWithText(title).assertIsDisplayed()
        compose.waitUntil(10_000) { cyan(compose.onRoot().captureToImage()) > 30 }
        compose.onNodeWithText(title).assertDoesNotExist()
        screenshot("home-hero-logo.png")
    }

    @Test fun missingLogoKeepsTheSelectedTitleWithoutAppBrandingOrAnOpeningCard() {
        var title by mutableStateOf<String?>(null)
        compose.setContent { TvTheme(reduceMotion = true) { PlaybackLoadingSurface(null, title) } }
        val unnamed = colors(compose.onRoot().captureToImage())
        assertTrue("The detector must see the unnamed app mark", unnamed.first > 30 && unnamed.second > 30)
        compose.runOnIdle { title = "Cast fixture" }
        compose.onNodeWithContentDescription("Cast fixture").assertExists()
        compose.onNodeWithText("Cast fixture").assertExists()
        compose.onNodeWithText("Opening source", substring = true).assertDoesNotExist()
        val colors = colors(compose.onRoot().captureToImage())
        assertTrue("Named titles must remove the app mark", colors.first < 30 && colors.second < 30)
        screenshot("loading-title-fallback.png")
    }

    @Test fun coldMovieAndMappedTamilMvSeriesLoadTheirOwnLogos() {
        prepareFixture()
        val client = instrumentation.targetContext.clientGraph
        val release = CatalogItem("saved:tamilmv:logo-fixture", "series", "Series logo fixture", imdbId = "tt7654321")
        runBlocking { client.snapshots.setReceiverTamilMV(listOf(release), System.currentTimeMillis()) }
        var target by mutableStateOf(PlayRequest("movie", "fixture:logo-cold", "Movie logo fixture"))
        compose.setContent { TvTheme(reduceMotion = true) {
            PlaybackLoadingSurface(null, target.title, request = target)
        } }
        compose.waitUntil(10_000) { cyan(compose.onRoot().captureToImage()) > 30 }
        compose.onNodeWithText("Movie logo fixture").assertDoesNotExist()
        screenshot("loading-cold-movie-logo.png")
        compose.runOnIdle { target = PlayRequest("series", release.canonicalId, release.title) }
        compose.waitUntil(10_000) { magenta(compose.onRoot().captureToImage()) > 30 }
        assertEquals(0, cyan(compose.onRoot().captureToImage()))
        compose.onNodeWithText("Series logo fixture").assertDoesNotExist()
        screenshot("loading-tamilmv-series-logo.png")
    }

    @Test fun failedTitleLogoKeepsTheTitleInsteadOfTheAppMark() {
        compose.setContent { TvTheme(reduceMotion = true) {
            PlaybackLoadingSurface(NowPlayingArt(logoURL = "$fixtureBase/missing-logo.png"), "Missing logo fixture")
        } }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Missing logo fixture").fetchSemanticsNodes().isNotEmpty()
        }
        val mark = colors(compose.onRoot().captureToImage())
        screenshot("loading-failed-logo-title.png")
        // LCD text edges can contain isolated coloured pixels; the actual mark has broad fills.
        assertTrue("The app mark must not replace a failed title logo: $mark", mark.first < 30 && mark.second < 30)
    }

    @Test fun loadingLogoNeverFlashesPlainTextDuringFetchOrPlayerHandoff() {
        val url = "$fixtureBase/slow-logo.png?handoff=${SystemClock.uptimeMillis()}"
        var stage by mutableStateOf(0)
        compose.setContent { TvTheme(reduceMotion = true) {
            // Model the separate Detail, shell and Player compositions over outgoing artwork.
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Red)) {
                androidx.compose.runtime.key(stage) {
                    PlaybackLoadingSurface(NowPlayingArt(logoURL = url), "Handoff fixture")
                }
            }
        } }
        compose.onNodeWithText("Handoff fixture").assertDoesNotExist()
        val pending = compose.onRoot().captureToImage().toPixelMap()
        assertEquals("The outgoing artwork must be covered immediately", TvColor.Canvas.toArgb(), pending[4, 4].toArgb())
        compose.waitUntil(10_000) { cyan(compose.onRoot().captureToImage()) > 30 }
        repeat(2) {
            compose.runOnIdle { stage++ }
            compose.onNodeWithText("Handoff fixture").assertDoesNotExist()
            assertTrue("A cached logo must draw in the replacement loading surface",
                cyan(compose.onRoot().captureToImage()) > 30)
        }
        screenshot("loading-logo-handoff.png")
    }

    @Test fun aNewLiveAttemptCannotKeepTheMovieArtwork() {
        NowPlayingArtwork.stage("$fixtureBase/old.png", null, "$fixtureBase/logo.png", owner = "movie")
        NowPlayingArtwork.beginPlayback(1, owner = "movie")
        var title by mutableStateOf("Movie fixture")
        compose.setContent {
            val art by NowPlayingArtwork.art.collectAsState()
            TvTheme(reduceMotion = true) { PlaybackLoadingSurface(art, title) }
        }
        compose.waitUntil(10_000) { cyan(compose.onRoot().captureToImage()) > 30 }
        screenshot("loading-current-title-logo.png")
        compose.runOnIdle {
            title = "Live fixture"
            NowPlayingArtwork.beginPlayback(2, useStaged = false, owner = "live")
        }
        compose.onNodeWithContentDescription("Live fixture").assertExists()
        compose.onNodeWithText("Movie fixture").assertDoesNotExist()
        val image = compose.onRoot().captureToImage()
        assertEquals(0, cyan(image))
        val pixels = image.toPixelMap()
        val expected = TvColor.Canvas.toArgb()
        assertEquals(expected, pixels[4, 4].toArgb())
        compose.onNodeWithText("Live fixture").assertExists()
        screenshot("loading-live-title.png")
        NowPlayingArtwork.clear()
    }

    @Test fun loadingLogoBreathesInFourSecondsWithoutMoving() {
        compose.mainClock.autoAdvance = false
        compose.setContent { TvTheme {
            PlaybackLoadingSurface(NowPlayingArt(logoURL = "$fixtureBase/logo.png"), "Pulse fixture")
        } }
        repeat(2) { compose.mainClock.advanceTimeByFrame() }
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeByFrame()
            cyan(compose.onRoot().captureToImage()) > 30
        }
        compose.onNodeWithText("Pulse fixture").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(4_000 - compose.mainClock.currentTime % 4_000)
        val peakImage = compose.onRoot().captureToImage()
        val peak = brightness(peakImage)
        compose.mainClock.advanceTimeBy(2_000)
        val troughImage = compose.onRoot().captureToImage()
        val trough = brightness(troughImage)
        compose.mainClock.advanceTimeBy(2_000)
        val returned = brightness(compose.onRoot().captureToImage())
        assertTrue("The logo pulse must remain visible at TV viewing distance", trough < peak * 0.65f)
        assertTrue("One breath must return after four seconds", returned > peak * 0.95f)
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "remote-checks").apply { mkdirs() }
        for ((name, image) in listOf("bright" to peakImage, "dim" to troughImage)) {
            File(folder, "loading-logo-pulse-$name.png").outputStream().use {
                check(image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        }
    }

    @Test fun reducedMotionKeepsTheLoadingLogoStill() {
        compose.mainClock.autoAdvance = false
        compose.setContent { TvTheme(reduceMotion = true) {
            PlaybackLoadingSurface(NowPlayingArt(logoURL = "$fixtureBase/logo.png"), "Still fixture")
        } }
        repeat(2) { compose.mainClock.advanceTimeByFrame() }
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeByFrame()
            cyan(compose.onRoot().captureToImage()) > 30 &&
                compose.onAllNodesWithText("Still fixture").fetchSemanticsNodes().isEmpty()
        }
        compose.mainClock.advanceTimeBy(4_000 - compose.mainClock.currentTime % 4_000)
        val before = brightness(compose.onRoot().captureToImage())
        compose.mainClock.advanceTimeBy(2_000)
        assertEquals(before, brightness(compose.onRoot().captureToImage()), 0.005f)
    }

    private fun brightness(image: ImageBitmap): Float {
        val pixels = image.toPixelMap()
        var brightest = 0f
        for (y in 0 until image.height step 4) for (x in 0 until image.width step 4)
            brightest = maxOf(brightest, pixels[x, y].green)
        return brightest
    }

    private fun closeSoftKeyboard() {
        compose.waitForIdle()
        instrumentation.runOnMainSync {
            val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).single()
            val view = activity.currentFocus ?: activity.window.decorView
            activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                .hideSoftInputFromWindow(view.windowToken, 0)
        }
        instrumentation.waitForIdleSync()
        compose.waitForIdle()
    }

    private fun key(code: Int) = instrumentation.sendKeyDownUpSync(code)
    private fun screenshot(name: String) {
        compose.waitForIdle()
        UiDevice.getInstance(instrumentation).waitForIdle()
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "remote-checks").apply { mkdirs() }
        check(UiDevice.getInstance(instrumentation).takeScreenshot(File(folder, name)))
    }
    private fun event(down: Long, at: Long, action: Int, code: Int, repeat: Int = 0) =
        instrumentation.sendKeySync(KeyEvent(down, at, action, code, repeat))

    private fun colors(image: ImageBitmap): Pair<Int, Int> {
        val pixels = image.toPixelMap()
        var orange = 0
        var green = 0
        for (y in 0 until image.height step 4) for (x in 0 until image.width step 4) {
            val p = pixels[x, y]
            if (p.red > 0.75f && p.green in 0.2f.. 0.75f && p.blue < 0.5f) orange++
            if (p.green > 0.65f && p.red < 0.75f && p.blue < 0.7f) green++
        }
        return orange to green
    }

    private fun cyan(image: ImageBitmap): Int {
        val pixels = image.toPixelMap()
        var count = 0
        for (y in 0 until image.height step 4) for (x in 0 until image.width step 4) {
            val p = pixels[x, y]
            if (p.blue > 0.75f && p.green > 0.65f && p.red < 0.25f) count++
        }
        return count
    }

    private fun magenta(image: ImageBitmap): Int {
        val pixels = image.toPixelMap()
        var count = 0
        for (y in 0 until image.height step 4) for (x in 0 until image.width step 4) {
            val p = pixels[x, y]
            if (p.blue > 0.75f && p.red > 0.65f && p.green < 0.25f) count++
        }
        return count
    }
}

/** Exercises the real controller, persistent video surface and error plate in both directions. */
@RunWith(AndroidJUnit4::class)
class WatchlistRemoteUiTest {
    @Before fun guard() = requireDisposableEmulator()

    @Test fun watchlistOpensFromHomeAndDiscoverAndReturnsFocus() {
        prepareFixture()
        val context = instrumentation.targetContext
        val library = context.clientGraph.activeLibrary
        val movie = "fixture:watchlist-entry-movie"
        val series = "fixture:watchlist-entry-series"
        val watchlist = runBlocking {
            library.saveToWatchlist(movie, "movie", "Watchlist entry film", "$fixtureBase/new.png")
            library.saveToWatchlist(series, "series", "Watchlist entry series", "$fixtureBase/new.png")
            library.savedCollections().first().first {
                it.sourceRef == com.fourseveneightnine.tv.client.data.library.SystemCollections.WATCHLIST_REF
            }.id
        }
        val device = UiDevice.getInstance(instrumentation)
        fun hasFocus(label: String): Boolean {
            if (Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
            return device.findObjects(By.focused(true)).any { node ->
                node.contentDescription.orEmpty().contains(label) || node.text.orEmpty().contains(label) ||
                    node.hasObject(By.descContains(label))
            }
        }
        fun focus(label: String) {
            val deadline = SystemClock.elapsedRealtime() + 8_000
            while (SystemClock.elapsedRealtime() < deadline) {
                if (hasFocus(label)) return
                SystemClock.sleep(100)
            }
            val folder = File(context.getExternalFilesDir(null), "remote-checks").apply { mkdirs() }
            device.takeScreenshot(File(folder, "watchlist-entry-focus-failure.png"))
            error("Expected focus $label")
        }
        fun step(code: Int) {
            check(device.pressKeyCode(code)) { "Could not send remote key $code" }
            SystemClock.sleep(80)
        }
        val down = KeyEvent.KEYCODE_DPAD_DOWN
        val right = KeyEvent.KEYCODE_DPAD_RIGHT
        val ok = KeyEvent.KEYCODE_DPAD_CENTER
        val back = KeyEvent.KEYCODE_BACK
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                check(device.wait(Until.hasObject(By.descContains("Watchlist entry film")), 15_000))
                var windowFocused = false
                val deadline = SystemClock.elapsedRealtime() + 10_000
                while (!windowFocused && SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { windowFocused = it.hasWindowFocus() }
                    if (!windowFocused) SystemClock.sleep(50)
                }
                check(windowFocused)
                repeat(3) {
                    if (!hasFocus("Watchlist entry film") && !hasFocus("Watchlist entry series")) step(down)
                }
                repeat(3) { if (!hasFocus("See all")) step(right) }
                focus("See all")
                step(ok)
                focus("Watchlist entry film")
                step(back)
                focus("See all")

                step(KeyEvent.KEYCODE_MENU)
                focus("Home")
                repeat(2) { step(down) }
                focus("Discover")
                step(ok)
                focus("Movies")
                repeat(3) { step(right) }
                focus("Catalog")
                step(ok)
                check(device.wait(Until.hasObject(By.desc("Watchlist")), 10_000))
                repeat(12) { if (!hasFocus("Watchlist")) step(KeyEvent.KEYCODE_DPAD_UP) }
                repeat(12) { if (!hasFocus("Watchlist")) step(down) }
                focus("Watchlist")
                step(ok)
                focus("Watchlist entry film")
                check(device.wait(Until.hasObject(By.text("1 of 2 saved items")), 10_000))
                step(back)
                focus("Watchlist")
            }
        } finally {
            runBlocking { library.removeWatchlistItems(watchlist, setOf(movie, series)) }
        }
    }

    @Test fun watchlistGroupsSortsAndRemovesWithTheRemote() {
        prepareFixture()
        val context = instrumentation.targetContext
        val library = context.clientGraph.activeLibrary
        val entries = listOf(
            Triple("tt1375666", "movie", "Older movie (2024)"),
            Triple("fixture:watchlist-series", "series", "Saved series (2022)"),
            Triple("fixture:watchlist-new", "movie", "Newer movie (1994)"),
        )
        val folder = runBlocking {
            library.ensureSystemCollections()
            for ((id, type, title) in entries) {
                library.saveToWatchlist(id, type, title, "$fixtureBase/new.png")
                delay(20)
            }
            library.savedCollections().first().first {
                it.sourceRef == com.fourseveneightnine.tv.client.data.library.SystemCollections.WATCHLIST_REF
            }.id
        }
        val folders = runBlocking { library.collections().first() }
        val folderIndex = folders.indexOfFirst { it.id == folder }
        val device = UiDevice.getInstance(instrumentation)
        fun screenshot(name: String) {
            val folder = File(context.getExternalFilesDir(null), "remote-checks").apply { mkdirs() }
            check(device.takeScreenshot(File(folder, name)))
        }
        fun focused(label: String) {
            val deadline = SystemClock.elapsedRealtime() + 8_000
            do {
                if (Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
                val nodes = device.findObjects(By.focused(true))
                if (nodes.any { node ->
                    node.contentDescription.orEmpty().contains(label) || node.text.orEmpty().contains(label) ||
                        node.hasObject(By.descContains(label))
                }) return
                SystemClock.sleep(80)
            } while (SystemClock.elapsedRealtime() < deadline)
            screenshot("watchlist-focus-failure.png")
            device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "remote-checks/watchlist-focus-failure.xml"))
            val actual = device.findObjects(By.focused(true)).map { it.contentDescription ?: it.text ?: it.className }
            error("Expected focus $label, found $actual")
        }
        fun press(code: Int) {
            check(device.pressKeyCode(code)) { "Could not send remote key $code" }
            SystemClock.sleep(80)
        }
        val up = KeyEvent.KEYCODE_DPAD_UP
        val down = KeyEvent.KEYCODE_DPAD_DOWN
        val left = KeyEvent.KEYCODE_DPAD_LEFT
        val right = KeyEvent.KEYCODE_DPAD_RIGHT
        val ok = KeyEvent.KEYCODE_DPAD_CENTER
        val back = KeyEvent.KEYCODE_BACK
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val deadline = SystemClock.elapsedRealtime() + 15_000
                var ready = false
                while (!ready && SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { ready = it.hasWindowFocus() }
                    if (!ready) SystemClock.sleep(50)
                }
                check(ready)
                check(device.wait(Until.hasObject(By.focused(true)), 10_000))
                press(KeyEvent.KEYCODE_MENU)
                focused("Home")
                repeat(3) { press(down) }
                focused("Collections")
                press(ok)
                focused(folders.first().name)
                repeat(folderIndex / FolderGrid.COLUMNS) { press(down) }
                repeat(folderIndex % FolderGrid.COLUMNS) { press(right) }
                focused("Watchlist")
                press(ok)
                focused("Newer movie")
                screenshot("watchlist-movies.png")
                press(down)
                focused("Saved series")
                screenshot("watchlist-series-focus.png")
                press(up)
                focused("Newer movie")
                press(up)
                focused("All")
                repeat(3) { press(right) }
                focused("Latest added")
                press(ok)
                focused("Latest added")
                press(down)
                focused("Oldest added")
                press(ok)
                focused("Oldest added")
                screenshot("watchlist-oldest-added.png")
                press(down)
                focused("Newer movie")
                press(left)
                focused("Older movie")
                press(ok)
                check(device.wait(Until.hasObject(By.desc("Sources")), 10_000))
                press(back)
                focused("Older movie")
                press(up)
                repeat(2) { press(right) }
                focused("Series")
                press(ok)
                press(down)
                focused("Saved series")
                press(left)
                focused("Filters")
                press(ok)
                focused("Show results")
                repeat(2) { press(down) }
                focused("Date added")
                press(ok)
                focused("Any time")
                press(down)
                focused("Last 7 days")
                press(ok)
                press(back)
                focused("Show results")
                press(back)
                focused("Filters (1)")
                press(right)
                focused("Search")
                press(ok)
                focused("A")
                press(ok)
                repeat(4) { press(right) }
                repeat(6) { press(down) }
                focused("Done")
                press(ok)
                focused("Search")
                press(right)
                focused("Clear search")
                press(ok)
                focused("Search")
                press(right)
                focused("More")
                press(ok)
                focused("Pick for me")
                press(ok)
                focused("Saved series")
                press(up)
                repeat(4) { press(right) }
                focused("More")
                press(ok)
                press(down)
                focused("Select items")
                press(ok)
                focused("Saved series")
                press(ok)
                press(up)
                focused("Done")
                press(right)
                focused("Remove selected")
                press(ok)
                focused("Cancel")
                press(down)
                press(ok)
                focused("Show all")
                screenshot("watchlist-empty-series.png")
                press(ok)
                focused("All")
                press(down)
                focused("Older movie")
                press(back)
                focused("Watchlist")
            }
        } finally {
            runBlocking { library.removeWatchlistItems(folder, entries.map { it.first }.toSet()) }
            val preferences = context.appGraph.presentationPreferences
            preferences.edit().apply {
                preferences.all.keys.filter { it.startsWith("watchlist_v1_") }.forEach { remove(it) }
            }.apply()
        }
    }

}

@RunWith(AndroidJUnit4::class)
class PlaybackFlowRegressionTest {
    @Before fun setup() = prepareFixture()

    // Use the actual Android frame clock. A Compose rule would advance a simulated clock
    // while the production loader initializes from System.nanoTime().
    @Test fun newlyRefreshedTamilMvMoviePlaysOnTheFirstRequestAfterTorBoxDelays() {
        val context = instrumentation.targetContext
        val graph = context.clientGraph
        val session = context.appGraph.playback
        val hash = "c".repeat(40)
        val filename = "Saayavanam.2026.1080p.WEB-DL.mp4"
        val card = CatalogItem("saved:tamilmv:first-play-fixture", "movie", "Saayavanam",
            year = 2026, metadataYear = 2025, playbackSources = listOf(CatalogPlaybackSource(hash, filename)))
        val files = org.json.JSONArray().put(org.json.JSONObject().put("id", 0)
            .put("name", filename).put("size", 8_000_000L).put("mimetype", "video/mp4"))
        val torrent = org.json.JSONObject().put("id", 14).put("hash", hash).put("name", filename).put("files", files)
        val creates = java.util.concurrent.atomic.AtomicInteger()
        val accountReads = java.util.concurrent.atomic.AtomicInteger()
        val linkReads = java.util.concurrent.atomic.AtomicInteger()
        val transport = graph.okHttp.newBuilder().addInterceptor { chain ->
            val request = chain.request()
            var code = 200
            val result: Any = when (request.url.pathSegments.last()) {
                "checkcached" -> org.json.JSONObject().put(hash, torrent)
                "createtorrent" -> {
                    creates.incrementAndGet()
                    org.json.JSONObject().put("torrent_id", 14)
                }
                "mylist" -> if (accountReads.incrementAndGet() == 1) {
                    code = 404
                    org.json.JSONObject.NULL
                } else torrent
                "requestdl" -> {
                    check(request.url.queryParameter("torrent_id") == "14")
                    check(request.url.queryParameter("file_id") == "0")
                    if (linkReads.incrementAndGet() == 1) org.json.JSONObject.NULL else "$fixtureBase/clip.mp4"
                }
                else -> error("Unexpected torrent fixture call")
            }
            val body = org.json.JSONObject().put("success", code == 200).put("data", result).toString()
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(code).message("Fixture").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking {
                withTimeout(15_000) { session.surfaceReadyState.first { it } }
                withTimeout(15_000) { graph.profileSession.state.first { it is ProfileSessionState.Unlocked } }
                val field = graph.javaClass.getDeclaredField("mutableServices").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                val services = field.get(graph) as kotlinx.coroutines.flow.MutableStateFlow<com.fourseveneightnine.tv.client.ClientGraph.DataServices?>
                val original = requireNotNull(services.value)
                val debrid = com.fourseveneightnine.tv.client.data.streams.debrid.DebridResolver(
                    "fixture", null, transport, torboxBaseURL = "$fixtureBase/first-play")
                try {
                    services.value = com.fourseveneightnine.tv.client.ClientGraph.DataServices(original.document,
                        original.health, original.client, original.registry, original.meta, original.ratings, original.search, debrid)
                    graph.snapshots.setReceiverTamilMV(listOf(card), System.currentTimeMillis())
                    assertEquals(com.fourseveneightnine.tv.client.playback.PlayResult.Opened,
                        graph.playFlow.play(PlayRequest("movie", card.canonicalId, card.title)))
                    withTimeout(20_000) { session.snapshot.first { it.positionSeconds >= 0.25 } }
                    assertEquals(1, creates.get())
                    assertEquals(2, accountReads.get())
                    assertEquals(2, linkReads.get())
                } finally {
                    services.value = original
                }
            }
        }
    }

    @Test fun loadingLogoKeepsItsPulsePhaseAcrossSurfaceReplacement() {
        requireDisposableEmulator()
        val stage = androidx.compose.runtime.mutableIntStateOf(0)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var view: ComposeView? = null
            scenario.onActivity { activity ->
                view = ComposeView(activity).also { overlay ->
                    activity.addContentView(overlay, ViewGroup.LayoutParams(-1, -1))
                    overlay.setContent { TvTheme {
                        androidx.compose.runtime.key(stage.intValue) {
                            PlaybackLoadingSurface(NowPlayingArt(logoURL = "$fixtureBase/logo.png"), "Clock fixture")
                        }
                    } }
                }
            }
            fun brightness(): Float {
                val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    var peak = 0f
                    for (y in 0 until bitmap.height step 4) for (x in 0 until bitmap.width step 4) {
                        val pixel = bitmap.getPixel(x, y)
                        val red = android.graphics.Color.red(pixel) / 255f
                        val green = android.graphics.Color.green(pixel) / 255f
                        val blue = android.graphics.Color.blue(pixel) / 255f
                        if (red < 0.25f && blue > green && green > 0.3f) peak = maxOf(peak, green)
                    }
                    return peak
                } finally { bitmap.recycle() }
            }
            try {
                val deadline = SystemClock.elapsedRealtime() + 10_000
                while (brightness() < 0.3f && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
                assertTrue("The cyan fixture logo must be visible", brightness() > 0.3f)
                repeat(3) { trial ->
                    val phaseDeadline = SystemClock.elapsedRealtime() + 4_000
                    while (System.nanoTime() % 4_000_000_000L !in 1_800_000_000L..1_900_000_000L) {
                        check(SystemClock.elapsedRealtime() < phaseDeadline) { "The four-second pulse phase did not arrive" }
                        Thread.sleep(5)
                    }
                    val before = brightness()
                    val started = SystemClock.elapsedRealtime()
                    scenario.onActivity { stage.intValue++ }
                    instrumentation.waitForIdleSync()
                    Thread.sleep(40)
                    val after = brightness()
                    android.util.Log.i("4789ClockAudit", "trial=$trial before=$before after=$after elapsedMs=${SystemClock.elapsedRealtime()-started}")
                    assertEquals("Real-frame-clock replacement must retain the trough phase", before, after, 0.04f)
                }
            } finally {
                scenario.onActivity { (view?.parent as? ViewGroup)?.removeView(view) }
            }
        }
    }

    @Test fun subtitleAddonSearchLoadsEnglishOnlyFromTheRemoteWithoutRestartingVideo() {
        val context = instrumentation.targetContext
        val client = context.clientGraph
        val session = context.appGraph.playback
        val device = UiDevice.getInstance(instrumentation)
        val raw = """{"format":"4789-settings","version":1,
            "sources":[{"name":"Local fixture","url":"$fixtureBase/manifest.json","enabled":true}],
            "subtitleSources":[{"name":"Subtitle fixture","url":"$fixtureBase/subtitle-search/manifest.json","enabled":true}]}"""
        runBlocking {
            check(context.appGraph.settingsStore.save(TVSettingsBackupPolicy.validate(raw.encodeToByteArray()), 1L, null))
            client.reloadSettingsNow()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking {
                try {
                    withTimeout(15_000) { session.surfaceReadyState.first { it } }
                    withTimeout(15_000) { client.profileSession.state.first { it is ProfileSessionState.Unlocked } }
                    val id = "fixture:ownership-subtitle-${SystemClock.uptimeMillis()}"
                    val filename = "Subtitle.Search.2024.720p.ENG.mp4"
                    val row = StreamRow("subtitle-search", "Local fixture", filename, filename,
                        url = "$fixtureBase/clip.mp4", filename = filename,
                        sizeBytes = ownershipFixture("state").getLong("bytes"))
                    assertEquals(com.fourseveneightnine.tv.client.playback.PlayResult.Opened,
                        client.playFlow.playRow(row, PlayRequest("movie", id, "Subtitle Search")))
                    withTimeout(15_000) { session.phase.first { it == ReceiverPlaybackPhase.Playing } }
                    withTimeout(20_000) {
                        while (session.refreshTracks().currentSubtitle == null) delay(100)
                    }
                    val owner = session.generation
                    val position = session.refreshSnapshot().positionSeconds
                    fun visible(selector: androidx.test.uiautomator.BySelector, timeout: Long): Boolean {
                        val deadline = SystemClock.uptimeMillis() + timeout
                        do {
                            if (Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
                            if (device.hasObject(selector)) return true
                            SystemClock.sleep(100)
                        } while (SystemClock.uptimeMillis() < deadline)
                        return false
                    }
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MENU)
                    assertTrue(visible(By.descStartsWith("Subtitles,"), 5_000))
                    fun focused(selector: androidx.test.uiautomator.BySelector): Boolean {
                        val focus = device.findObject(By.focused(true))?.visibleBounds ?: return false
                        val label = device.findObject(selector)?.visibleBounds ?: return false
                        return focus.contains(label)
                    }
                    fun moveTo(selector: androidx.test.uiautomator.BySelector, key: Int, attempts: Int = 10) {
                        repeat(attempts) {
                            if (Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
                            if (focused(selector)) {
                                SystemClock.sleep(350)
                                if (Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
                                if (focused(selector)) return
                            }
                            instrumentation.sendKeyDownUpSync(key)
                            SystemClock.sleep(100)
                        }
                        assertTrue("Remote must reach $selector", focused(selector))
                    }
                    moveTo(By.descStartsWith("Subtitles,"), KeyEvent.KEYCODE_DPAD_RIGHT)
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                    assertTrue(visible(By.descStartsWith("English · Subtitle fixture"), 10_000))
                    val folder = File(context.getExternalFilesDir(null), "remote-checks").apply { mkdirs() }
                    check(device.takeScreenshot(File(folder, "subtitle-search-results.png")))
                    assertFalse(device.hasObject(By.descStartsWith("Tamil · Subtitle fixture")))
                    assertFalse(device.hasObject(By.descStartsWith("Add-on language")))
                    moveTo(By.desc("Search subtitles"), KeyEvent.KEYCODE_DPAD_UP)
                    check(device.takeScreenshot(File(folder, "subtitle-search-focus.png")))
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                    assertTrue(visible(By.descStartsWith("English · Subtitle fixture"), 5_000))
                    assertFalse(device.hasObject(By.descStartsWith("Tamil · Subtitle fixture")))
                    moveTo(By.descStartsWith("English · Subtitle fixture"), KeyEvent.KEYCODE_DPAD_DOWN)
                    check(device.takeScreenshot(File(folder, "subtitle-result-focus.png")))
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                    assertTrue(visible(By.text("Subtitle loaded"), 10_000))
                    assertTrue(session.refreshTracks().currentSubtitle != null)
                    assertEquals(owner, session.generation)
                    assertTrue(session.refreshSnapshot().positionSeconds > position)
                    assertTrue(session.phase.value is ReceiverPlaybackPhase.Playing)
                    check(device.takeScreenshot(File(folder, "subtitle-loaded.png")))
                    assertTrue(device.pressBack())
                    moveTo(By.descStartsWith("Subtitles,"), KeyEvent.KEYCODE_DPAD_RIGHT)
                } finally { session.stop(); client.playFlow.clearCurrent() }
            }
        }
    }

    @Test fun loadingPulseReachesTheDisplayCompositor() {
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val originalScale = device.executeShellCommand("settings get global animator_duration_scale").trim()
        try {
            for (scale in listOf("0", "1")) {
                device.executeShellCommand("settings put global animator_duration_scale $scale")
                assertEquals(scale, device.executeShellCommand("settings get global animator_duration_scale").trim())
                ActivityScenario.launch(MainActivity::class.java).use {
                    runBlocking {
                        val session = context.appGraph.playback
                        withTimeout(15_000) { session.surfaceReadyState.first { it } }
                        val title = "Display pulse fixture"
                        NowPlayingArtwork.stage(null, null, "$fixtureBase/logo.png", owner = title)
                        session.open(OpenMediaRequest("$fixtureBase/slow.mp4?pulse=${SystemClock.uptimeMillis()}", title = title),
                            PlaybackSession.Origin.Phone).getOrThrow()
                        try {
                            assertTrue(device.wait(Until.hasObject(By.desc(title)), 5_000))
                            val folder = File(context.getExternalFilesDir(null), "remote-checks").apply { mkdirs() }
                            val samples = List(8) { index ->
                                SystemClock.sleep(400)
                                val file = File(folder, "loading-display-pulse-$scale-$index.png")
                                check(device.takeScreenshot(file))
                                val bitmap = requireNotNull(BitmapFactory.decodeFile(file.path))
                                val green = android.graphics.Color.green(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
                                bitmap.recycle()
                                green
                            }
                            assertTrue("All samples must show the loading logo: $samples", samples.min() > 100)
                            assertTrue("The displayed logo must visibly dim and brighten: $samples",
                                samples.min() < samples.max() * 0.7f)
                        } finally { session.stop() }
                    }
                }
            }
        } finally {
            device.executeShellCommand(if (originalScale == "null") "settings delete global animator_duration_scale"
                else "settings put global animator_duration_scale $originalScale")
        }
    }

    @Test fun mainPlayKeepsLatestPreparationAndHonorsBothCancellationPaths() {
        val context = instrumentation.targetContext
        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking {
                val client = context.clientGraph
                val session = context.appGraph.playback
                withTimeout(15_000) { session.surfaceReadyState.first { it } }
                withTimeout(15_000) { client.profileSession.state.first { it is ProfileSessionState.Unlocked } }
                val services = client.services.filterNotNull().first()
                val suffix = SystemClock.uptimeMillis()
                val first = PlayRequest("movie", "fixture:ownership-blocked-main-a-$suffix", "Ownership fixture")
                val second = first.copy(id = "fixture:ownership-blocked-main-b-$suffix")
                requireNotNull(services.meta.meta("movie", first.id))
                requireNotNull(services.meta.meta("movie", second.id))
                withContext(Dispatchers.IO) { java.net.URL("$fixtureBase/ownership/reset").readText() }
                val generation = session.generation
                val callers = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate)
                try {
                    lateinit var older: Job
                    lateinit var newer: Job
                    withContext(Dispatchers.Main.immediate) {
                        older = callers.launch(start = CoroutineStart.UNDISPATCHED) { client.playFlow.play(first) }
                        assertEquals("First preparation registers before worker dispatch", first, client.playFlow.preparing.value)
                        newer = callers.launch(start = CoroutineStart.UNDISPATCHED) { client.playFlow.play(second) }
                        assertEquals("Latest Main request owns preparation", second, client.playFlow.preparing.value)
                    }
                    withTimeout(5_000) { older.join() }
                    assertTrue("Newer request promptly cancels the older caller", older.isCancelled)
                    assertEquals("Old completion must not clear newer preparation", second, client.playFlow.preparing.value)
                    withContext(Dispatchers.Main.immediate) { client.playFlow.cancelPreparation() }
                    withTimeout(5_000) { newer.join() }
                    assertTrue("cancelPreparation cancels the caller", newer.isCancelled)
                    assertNull(client.playFlow.preparing.value)
                    lateinit var cancelledCaller: Job
                    withContext(Dispatchers.Main.immediate) {
                        cancelledCaller = callers.launch(start = CoroutineStart.UNDISPATCHED) { client.playFlow.play(first) }
                        assertEquals(first, client.playFlow.preparing.value)
                        cancelledCaller.cancel()
                    }
                    withTimeout(5_000) { cancelledCaller.join() }
                    assertTrue(cancelledCaller.isCancelled)
                    assertNull(client.playFlow.preparing.value)
                    assertEquals("Cancelled requests cannot open the player", generation, session.generation)
                } finally {
                    callers.coroutineContext[Job]?.cancel()
                    client.playFlow.cancelPreparation()
                    withContext(Dispatchers.IO) { java.net.URL("$fixtureBase/ownership/release").readText() }
                    session.stop()
                }
            }
        }
    }

    @Test fun localPlayFlowPulsesDuringSourceFindingAndPlayerBuffering() {
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking {
                val client = context.clientGraph
                val session = context.appGraph.playback
                withTimeout(15_000) { session.surfaceReadyState.first { it } }
                withTimeout(15_000) { client.profileSession.state.first { it is ProfileSessionState.Unlocked } }
                val id = "fixture:ownership-loading-clock-${SystemClock.uptimeMillis()}"
                val services = withTimeout(15_000) { client.services.filterNotNull().first() }
                // Detail has already loaded this metadata before its Play control is available.
                val meta = requireNotNull(services.meta.meta("movie", id))
                val request = PlayRequest("movie", id, "Ownership fixture", posterUrl = meta.poster, backdropUrl = meta.backdrop)
                val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                val maximumMainGap = java.util.concurrent.atomic.AtomicLong(0)
                var lastHeartbeat = SystemClock.elapsedRealtime()
                val heartbeat = object : Runnable {
                    override fun run() {
                        val now = SystemClock.elapsedRealtime()
                        maximumMainGap.updateAndGet { maxOf(it, now - lastHeartbeat) }
                        lastHeartbeat = now
                        mainHandler.postDelayed(this, 16)
                    }
                }
                withContext(Dispatchers.IO) { java.net.URL("$fixtureBase/ownership/reset").readText() }
                session.stop()
                mainHandler.post(heartbeat)
                val opening = async(Dispatchers.Main.immediate) { client.playFlow.play(request) }
                try {
                    assertTrue(device.wait(Until.hasObject(By.desc(request.title)), 5_000))
                    val folder = File(context.getExternalFilesDir(null), "remote-checks").apply { mkdirs() }
                    fun captureSamples(phase: String): List<Int> = List(8) { index ->
                        SystemClock.sleep(350)
                        val file = File(folder, "loading-local-flow-$phase-$index.png")
                        check(device.takeScreenshot(file))
                        val bitmap = requireNotNull(BitmapFactory.decodeFile(file.path))
                        val green = android.graphics.Color.green(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
                        bitmap.recycle()
                        green
                    }
                    assertNull("Hold source search until preparation is sampled", session.origin.value)
                    val preparationSamples = captureSamples("preparation")
                    withContext(Dispatchers.IO) { java.net.URL("$fixtureBase/ownership/release").readText() }
                    withTimeout(20_000) { session.origin.first { it == PlaybackSession.Origin.Local } }
                    val playerSamples = captureSamples("player")
                    val samples = preparationSamples + playerSamples
                    assertTrue("The title logo must remain visible across the handoff: $samples", samples.min() > 100)
                    assertTrue("The logo must pulse throughout the local Play flow: $samples", samples.min() < samples.max() * 0.7f)
                    assertEquals(com.fourseveneightnine.tv.client.playback.PlayResult.Opened, withTimeout(15_000) { opening.await() })
                    withContext(Dispatchers.Main.immediate) { delay(32) }
                    assertTrue("UI heartbeat stalled during Play: ${maximumMainGap.get()}ms", maximumMainGap.get() < 1_000)
                } finally {
                    mainHandler.removeCallbacks(heartbeat)
                    opening.cancel()
                    withContext(Dispatchers.IO) { java.net.URL("$fixtureBase/ownership/release").readText() }
                    session.stop()
                }
            }
        }
    }

    @Test fun expiredCheckedDmmSelectionRefreshesAndChecksItsNewRoute() {
        prepareFixture("/dmm/manifest.json")
        val context = instrumentation.targetContext
        val client = context.clientGraph
        val session = context.appGraph.playback
        val id = "fixture:ownership-bookmark-" + SystemClock.uptimeMillis()
        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking {
                withTimeout(15_000) { session.surfaceReadyState.first { it } }
                withTimeout(15_000) { client.profileSession.state.first { it is ProfileSessionState.Unlocked } }
                val services = client.services.filterNotNull().first()
                val meta = requireNotNull(services.meta.meta("movie", id))
                val addon = services.registry.addons.value.single { it.manifestURL == fixtureBase + "/dmm/manifest.json" }
                val raw = services.client.streams(addon, "movie", id).single()
                assertFalse(raw.readyHint)
                val expected = com.fourseveneightnine.tv.client.data.streams.StreamExpectation(meta.title, meta.year, null, null, true)
                val checked = requireNotNull(services.debrid.verifiedSource(raw, expected))
                val expiredUrl = requireNotNull(checked.url)
                val generation = requireNotNull(android.net.Uri.parse(expiredUrl).getQueryParameter("generation"))
                try {
                    ownershipFixture("reset?expire=" + android.net.Uri.encode(generation))
                    ownershipFixture("release")
                    val result = withTimeout(30_000) {
                        client.playFlow.playRow(checked, PlayRequest("movie", id, "Ownership fixture"))
                    }
                    assertEquals(com.fourseveneightnine.tv.client.playback.PlayResult.Opened, result)
                    assertTrue(expiredUrl != requireNotNull(session.lastOpenMedia()).url)
                    withTimeout(15_000) { session.phase.first { it is ReceiverPlaybackPhase.Playing } }
                } finally {
                    ownershipFixture("release")
                    session.stop()
                    instrumentation.runOnMainSync { client.playFlow.clearCurrent() }
                }
            }
        }
    }

    @Test fun manualMovieSourceLoadsMetadataAfterMemoryMiss() {
        val context = instrumentation.targetContext
        val client = context.clientGraph
        val session = context.appGraph.playback
        val id = "fixture:ownership-manual-${SystemClock.uptimeMillis()}"
        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking {
                withTimeout(15_000) { session.surfaceReadyState.first { it } }
                withTimeout(15_000) { client.profileSession.state.first { it is ProfileSessionState.Unlocked } }
                val services = client.services.filterNotNull().first()
                assertNull(services.meta.peek("movie", id))
                val file = "Ownership.Fixture.2024.720p.ENG.mp4"
                val row = StreamRow("manual-$id", "MediaFusion fixture", "Ownership fixture", file,
                    url = "$fixtureBase/ownership.mp4", filename = file, sizeBytes = ownershipFixture("state").getLong("bytes"))
                try {
                    val result = withTimeout(30_000) {
                        client.playFlow.playRow(row, PlayRequest("movie", id, "Ownership fixture"))
                    }
                    assertEquals(com.fourseveneightnine.tv.client.playback.PlayResult.Opened, result)
                    assertEquals(2024, services.meta.peek("movie", id)?.year)
                    withTimeout(15_000) { session.phase.first { it is ReceiverPlaybackPhase.Playing } }
                    assertEquals(id, client.playFlow.current.value?.id)
                    assertEquals(file, client.playFlow.activeSource.value?.filename)
                } finally {
                    session.stop()
                    instrumentation.runOnMainSync { client.playFlow.clearCurrent() }
                }
            }
        }
    }

    @Test fun titlePlayFallsBackFromAnExpiredEmbedded4KSource() {
        val context = instrumentation.targetContext
        val session = context.appGraph.playback
        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking {
                withTimeout(15_000) { session.surfaceReadyState.first { it } }
                try {
                    val result = withTimeout(40_000) {
                        context.clientGraph.playFlow.play(PlayRequest("movie", "fixture:source-deck", "Source Deck"))
                    }
                    assertEquals(com.fourseveneightnine.tv.client.playback.PlayResult.Opened, result)
                    withTimeout(15_000) { session.phase.first { it is ReceiverPlaybackPhase.Playing } }
                    assertTrue(requireNotNull(session.lastOpenMedia()).url.contains("/clip.mp4?source-pick="))
                } finally { session.stop() }
            }
        }
    }

    @Test fun playerSourcesFocusThePlayingDirectFileAboveCachedAlternatives() {
        val context = instrumentation.targetContext
        val client = context.clientGraph
        val session = context.appGraph.playback
        val request = PlayRequest("movie", "fixture:source-deck", "Source Deck")
        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking {
                withTimeout(15_000) { session.surfaceReadyState.first { it } }
                try {
                    val direct = withContext(Dispatchers.Default) {
                        val services = withTimeout(15_000) { client.services.filterNotNull().first() }
                        services.meta.meta(request.type, request.id)
                        val addon = services.registry.addons.value.single { it.manifestURL == "$fixtureBase/manifest.json" }
                        services.client.streams(addon, request.type, request.id).single { it.directHttp && it.quality == "720p" }
                    }
                    assertEquals(com.fourseveneightnine.tv.client.playback.PlayResult.Opened, client.playFlow.playRow(direct, request))
                    withTimeout(15_000) { session.phase.first { it is ReceiverPlaybackPhase.Playing } }
                    val device = UiDevice.getInstance(instrumentation)
                    val folder = File(context.getExternalFilesDir(null), "remote-checks").apply { mkdirs() }
                    fun focused(label: String): Boolean {
                        if (Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
                        val node = device.findObject(By.focused(true)) ?: return false
                        return node.contentDescription.orEmpty().contains(label) || node.hasObject(By.descContains(label))
                    }
                    val button = dockBounds(device, "Sources", KeyEvent.KEYCODE_MENU)
                    for (attempt in 1..8) {
                        if (focused("Sources")) break
                        val from = device.findObject(By.focused(true))?.visibleBounds
                        when {
                            from == null || from.bottom <= button.top -> device.pressDPadDown()
                            from.top >= button.bottom -> device.pressDPadUp()
                            from.centerX() > button.centerX() -> device.pressDPadLeft()
                            else -> device.pressDPadRight()
                        }
                        device.waitForIdle()
                    }
                    if (!focused("Sources")) device.dumpWindowHierarchy(File(folder, "source-dock-focus.xml"))
                    assertTrue("Remote focus must reach the Sources button", focused("Sources"))
                    device.pressDPadCenter()
                    val cachedResult = device.wait(Until.findObject(By.text("Cached Debrid")), 15_000)
                    if (cachedResult == null) {
                        device.dumpWindowHierarchy(File(folder, "source-pinned-missing-header.xml"))
                        device.takeScreenshot(File(folder, "source-pinned-missing-header.png"))
                    }
                    val cached = requireNotNull(cachedResult)
                    val sourceRows = requireNotNull(device.findObject(
                        By.scrollable(true).hasDescendant(By.text("Cached Debrid"))))
                    val pinned = requireNotNull(sourceRows.findObject(By.descContains("720p")))
                    if (!focused("720p")) device.dumpWindowHierarchy(File(folder, "source-pinned-focus.xml"))
                    assertTrue("Playing 720p file must own initial focus", focused("720p"))
                    val pinnedBounds = pinned.visibleBounds
                    val cachedBounds = cached.visibleBounds
                    File(folder, "source-pinned-bounds.txt").writeText(
                        "selected=$pinnedBounds cached=$cachedBounds\n" +
                            device.findObjects(By.descContains("720p")).joinToString("\n") {
                                "description=${it.contentDescription} bounds=${it.visibleBounds}"
                            })
                    device.dumpWindowHierarchy(File(folder, "source-pinned-before-assertion.xml"))
                    device.takeScreenshot(File(folder, "source-pinned-before-assertion.png"))
                    assertTrue("Pinned source $pinnedBounds must precede cached header $cachedBounds",
                        pinnedBounds.bottom <= cachedBounds.top)
                    assertEquals(1, sourceRows.findObjects(By.descContains("720p")).size)
                    assertTrue(device.takeScreenshot(File(folder, "source-player-pinned-direct.png")))
                    device.pressDPadDown()
                    device.waitForIdle()
                    assertTrue("First alternative should be cached 4K", focused("4K"))
                    device.pressDPadUp()
                    device.waitForIdle()
                    assertTrue(focused("720p"))
                    device.pressDPadCenter()
                    assertTrue(device.wait(Until.gone(By.text("Cached Debrid")), 3_000))
                    assertTrue(requireNotNull(session.lastOpenMedia()).url.contains("/clip.mp4?source-pick=5"))
                } finally { session.stop() }
            }
        }
    }

    @Test fun movieToFailedNativeLiveHasAnOpaqueCurrentError() = failedSwitch(liveFirst = false)
    @Test fun nativeLiveToFailedMovieHasAnOpaqueCurrentError() = failedSwitch(liveFirst = true)

    @OptIn(UnstableApi::class)
    @Test fun queuedErrorCannotPublishAgainstAReplacementPublicOpen() {
        val context = instrumentation.targetContext
        check((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            "Private controller reflection requires the debuggable fixture APK"
        }
        val session = context.appGraph.playback
        val suffix = SystemClock.uptimeMillis()
        val retiredMarker = "retired-error-$suffix"
        val currentMarker = "current-error-$suffix"
        runBlocking { session.stop().getOrThrow() }
        instrumentation.runOnMainSync { context.clientGraph.playFlow.clearCurrent() }
        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking {
                var collector: Job? = null
                try {
                    withTimeout(15_000) { session.surfaceReadyState.first { it } }
                    session.open(OpenMediaRequest("$fixtureBase/clip.mp4?error-owner=A",
                        title = "Error owner A", startPositionMs = 60_000)).getOrThrow()
                    withTimeout(15_000) { session.phase.first { phase -> phase is ReceiverPlaybackPhase.Playing } }
                    assertTrue(session.resumePositionMillis() >= 60_000)
                    val innerField = SwappableReceiverController::class.java.getDeclaredField("inner").apply { isAccessible = true }
                    val exo = innerField.get(context.appGraph.controller) as ExoReceiverController
                    val scopeField = ExoReceiverController::class.java.getDeclaredField("scope").apply { isAccessible = true }
                    val emitterScope = scopeField.get(exo) as CoroutineScope
                    val emit = ExoReceiverController::class.java.getDeclaredMethod("emitPlaybackError",
                        String::class.java, String::class.java, String::class.java,
                        java.lang.Boolean.TYPE, ReceiverSnapshot::class.java).apply { isAccessible = true }
                    val seen = mutableListOf<ReceiverEvent>()
                    collector = launch(Dispatchers.Main, start = CoroutineStart.UNDISPATCHED) {
                        exo.events.collect { event -> seen += event }
                    }
                    // Called only inside a Main turn. Capture the existing helper's exact child,
                    // rather than sleeping or occupying any Dispatchers.Default worker.
                    fun queueError(marker: String): Job {
                        val parent = checkNotNull(emitterScope.coroutineContext[Job])
                        val before = parent.children.toSet()
                        emit.invoke(exo, "network", null, marker, true, null)
                        return parent.children.single { child -> child !in before }
                    }
                    val retiredEmission = withContext(Dispatchers.Main) {
                        val queued = queueError(retiredMarker)
                        // This component-level open deliberately stays in the same Main context.
                        // The Default emitter must queue snapshotOnMain behind this entire turn.
                        exo.open(OpenMediaRequest("$fixtureBase/clip.mp4?error-owner=B",
                            title = "Error owner B", startPositionMs = 180_000)).getOrThrow()
                        queued
                    }
                    withTimeout(5_000) { retiredEmission.join() }
                    instrumentation.waitForIdleSync()
                    withTimeout(15_000) { exo.playbackPhase.first { phase -> phase is ReceiverPlaybackPhase.Playing } }
                    assertEquals("Error owner B", exo.lastOpenMedia()?.title)
                    assertTrue(exo.snapshot().positionSeconds >= 178.0)
                    val stale = withContext(Dispatchers.Main) {
                        seen.filterIsInstance<ReceiverEvent.Error>().firstOrNull { event -> event.humanText == retiredMarker }
                    }
                    assertNull("A's queued error reached B; observed position=${stale?.snapshot?.positionSeconds}", stale)

                    // Positive control: the same helper and active collector must still deliver
                    // an error that belongs to the current public open.
                    val currentEmission = withContext(Dispatchers.Main) { queueError(currentMarker) }
                    withTimeout(5_000) { currentEmission.join() }
                    instrumentation.waitForIdleSync()
                    val accepted = withContext(Dispatchers.Main) {
                        seen.filterIsInstance<ReceiverEvent.Error>().firstOrNull { event -> event.humanText == currentMarker }
                    }
                    assertTrue("The current owner's error must still be published", accepted != null)
                    assertTrue(checkNotNull(accepted).snapshot.positionSeconds >= 178.0)

                    val stoppedMarker = "stopped-error-$suffix"
                    val stoppedEmission = withContext(Dispatchers.Main) {
                        val queued = queueError(stoppedMarker)
                        // Stop can yield while it creates its terminal snapshot. Its ownership
                        // invalidation must already apply before that queued emitter can run.
                        exo.stop().getOrThrow()
                        queued
                    }
                    withTimeout(5_000) { stoppedEmission.join() }
                    instrumentation.waitForIdleSync()
                    assertFalse(exo.snapshot().active)
                    val afterStop = withContext(Dispatchers.Main) {
                        seen.filterIsInstance<ReceiverEvent.Error>().firstOrNull { event -> event.humanText == stoppedMarker }
                    }
                    assertNull("A queued error survived explicit Stop; phase=${exo.playbackPhase.value}", afterStop)
                } finally {
                    withContext(NonCancellable) {
                        collector?.cancelAndJoin()
                        session.stop()
                        instrumentation.runOnMainSync { context.clientGraph.playFlow.clearCurrent() }
                    }
                }
            }
        }
    }

    @Test fun mediaSessionPublishesTheExactLocalRateAndZeroWhenPaused() {
        check(Build.VERSION.SDK_INT >= 29) { "MediaSession permission adoption requires API29+" }
        val context = instrumentation.targetContext
        val session = context.appGraph.playback
        runBlocking { session.stop().getOrThrow() }
        instrumentation.runOnMainSync { context.clientGraph.playFlow.clearCurrent() }
        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking {
                var shellIdentity = false
                try {
                    withTimeout(15_000) { session.surfaceReadyState.first { it } }
                    session.open(OpenMediaRequest("$fixtureBase/clip.mp4", title = "Rate fixture")).getOrThrow()
                    withTimeout(15_000) { session.phase.first { it is ReceiverPlaybackPhase.Playing } }
                    instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.MEDIA_CONTENT_CONTROL")
                    shellIdentity = true
                    val manager = context.getSystemService(MediaSessionManager::class.java)
                    val mediaController = manager.getActiveSessions(null).first { it.packageName == context.packageName }
                    for ((rate, wireSpeed) in listOf(0.75f to 1, 1.25f to 1, 1.5f to 1, 2f to 2)) {
                        assertEquals(rate, session.setSpeedMultiplier(rate).getOrThrow(), 0f)
                        withTimeout(5_000) {
                            while (mediaController.playbackState?.let {
                                it.state == PlaybackState.STATE_PLAYING && it.playbackSpeed == rate
                            } != true) delay(20)
                        }
                        val snapshot = session.refreshSnapshot()
                        assertEquals(rate, snapshot.speedMultiplier, 0f)
                        assertEquals("Kodi's integer speed contract stays unchanged", wireSpeed, snapshot.speed)
                        assertEquals(rate, requireNotNull(mediaController.playbackState).playbackSpeed, 0f)
                    }
                    session.setSpeed(0).getOrThrow()
                    withTimeout(5_000) {
                        while (mediaController.playbackState?.let {
                            it.state == PlaybackState.STATE_PAUSED && it.playbackSpeed == 0f
                        } != true) delay(20)
                    }
                    assertEquals(0, session.refreshSnapshot().speed)
                    assertEquals(2f, session.snapshot.value.speedMultiplier, 0f)
                } finally {
                    if (shellIdentity) instrumentation.uiAutomation.dropShellPermissionIdentity()
                    session.stop()
                    instrumentation.runOnMainSync { context.clientGraph.playFlow.clearCurrent() }
                }
            }
        }
    }

    private fun dockBounds(device: UiDevice, label: String, openKey: Int): android.graphics.Rect {
        val selector = By.desc(label)
        instrumentation.waitForIdleSync()
        repeat(12) {
            if (Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
            device.findObject(selector)?.let { return it.visibleBounds }
            instrumentation.sendKeyDownUpSync(openKey)
            device.wait(Until.findObject(selector), 600)?.let { return it.visibleBounds }
        }
        error("The player did not expose $label")
    }

    private fun assertDockIconColor(device: UiDevice, bounds: android.graphics.Rect, expected: Int, evidence: File) {
        // The accessibility label covers the control. Sample the symbol above its caption.
        val deadline = SystemClock.elapsedRealtime() + 3_000
        do {
            check(device.takeScreenshot(evidence))
            val image = requireNotNull(BitmapFactory.decodeFile(evidence.path))
            try {
                var matching = 0
                for (y in bounds.top until bounds.centerY()) {
                    for (x in bounds.left until bounds.right) {
                        if (image.getPixel(x, y) == expected && ++matching >= 24) return
                    }
                }
            } finally { image.recycle() }
            SystemClock.sleep(80)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Player icon did not reach the expected color. See ${evidence.name}")
    }

    @Test fun liveFavoriteControlReflectsBothRemoteToggles() {
        val context = instrumentation.targetContext
        val client = context.clientGraph
        val session = context.appGraph.playback
        val channelId = "fixture-favorite-${SystemClock.uptimeMillis()}"
        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking {
                withTimeout(15_000) { session.surfaceReadyState.first { it } }
                withTimeout(15_000) { client.profileSession.state.first { it is ProfileSessionState.Unlocked } }
                val device = UiDevice.getInstance(instrumentation)
                try {
                    session.openLiveChannel(OpenMediaRequest("$fixtureBase/clip.mp4", title = "Live favorite", isLive = true),
                        PlaybackSession.LiveChannel(channelId, "fixture", "Fixture")).getOrThrow()
                    withTimeout(15_000) { session.phase.first { it is ReceiverPlaybackPhase.Playing } }
                    val bounds = dockBounds(device, "Favorite", KeyEvent.KEYCODE_DPAD_DOWN)
                    val evidence = File(context.getExternalFilesDir(null), "remote-checks").apply { mkdirs() }
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BOOKMARK)
                    withTimeout(5_000) { client.iptv.state.first { channelId in it.accounts.favoriteIds } }
                    assertDockIconColor(device, bounds, TvColor.Success.toArgb(), File(evidence, "live-favorite-selected.png"))
                    if (Build.VERSION.SDK_INT >= 34) {
                        instrumentation.uiAutomation.clearCache()
                        assertTrue(device.hasObject(By.checked(true).hasDescendant(By.desc("Saved"))))
                    }
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BOOKMARK)
                    withTimeout(5_000) { client.iptv.state.first { channelId !in it.accounts.favoriteIds } }
                    assertDockIconColor(device, bounds, TvColor.TextPrimary.toArgb(), File(evidence, "live-favorite-off.png"))
                } finally {
                    session.stop()
                    if (channelId in client.iptv.state.value.accounts.favoriteIds) client.iptv.toggleFavorite(channelId)
                }
            }
        }
    }

    @Test fun watchlistReopensTheSelectedReleaseAfterItsUrlChanges() = watchlistReopensAfterUrlChange(checkedDmm = false)

    @Test fun checkedDmmWatchlistReopensWithoutProviderVideoSize() = watchlistReopensAfterUrlChange(checkedDmm = true)

    @Test fun automaticDmmWatchlistReopensWithoutProviderVideoSize() =
        watchlistReopensAfterUrlChange(checkedDmm = true, automaticDmm = true)

    @Test fun manualWatchlistRetryOwnsDecodedPreparation() =
        watchlistReopensAfterUrlChange(checkedDmm = false, retryAutomatic = false)

    @Test fun automaticWatchlistRetryKeepsSingleRecoveryBudget() =
        watchlistReopensAfterUrlChange(checkedDmm = false, retryAutomatic = true)

    private fun watchlistReopensAfterUrlChange(
        checkedDmm: Boolean, automaticDmm: Boolean = false, retryAutomatic: Boolean? = null,
    ) {
        if (checkedDmm) prepareFixture("/dmm/manifest.json")
        val context = instrumentation.targetContext
        val client = context.clientGraph
        val session = context.appGraph.playback
        val id = "fixture:ownership-bookmark-${SystemClock.uptimeMillis()}"
        fun mediaStatus(url: String): Int {
            val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 2_000
                readTimeout = 2_000
                instanceFollowRedirects = false
                setRequestProperty("Range", "bytes=0-63")
            }
            return try { connection.responseCode } finally { connection.disconnect() }
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking {
                withTimeout(15_000) { session.surfaceReadyState.first { it } }
                withTimeout(15_000) { client.profileSession.state.first { it is ProfileSessionState.Unlocked } }
                val library = client.activeLibrary
                var savedCard: Pair<Long, String>? = null
                var replay: kotlinx.coroutines.Deferred<com.fourseveneightnine.tv.client.playback.PlayResult>? = null
                try {
                    val request = PlayRequest("movie", id, "Ownership fixture")
                    var checkedRow: StreamRow? = null
                    val opened = if (checkedDmm) withContext(Dispatchers.Default) {
                        val services = withTimeout(15_000) { client.services.filterNotNull().first() }
                        // The real Sources screen warms metadata before handing a checked row to Play.
                        val meta = requireNotNull(services.meta.meta("movie", id))
                        assertEquals(2024, meta.year)
                        val addon = services.registry.addons.value.single {
                            it.manifestURL == "$fixtureBase/dmm/manifest.json"
                        }
                        val raw = services.client.streams(addon, "movie", id).single()
                        assertTrue(raw.requiresDmmValidation)
                        assertNull(raw.sizeBytes) // Provider JSON deliberately omits videoSize.
                        val expected = com.fourseveneightnine.tv.client.data.streams.StreamExpectation(
                            meta.title, meta.year, null, null, true, canonicalID = id)
                        val admitted = withTimeout(15_000) {
                            services.search.validatedForDisplay(
                                services.search.search("movie", listOf(id), expectation = expected), expected,
                            ).first { it.done }
                        }
                        val checked = admitted.rows.single { it.requiresDmmValidation }
                        assertEquals("Ownership.Fixture.2024.720p.ENG.mp4", checked.filename)
                        assertEquals(24L * 1024 * 1024, checked.sizeBytes)
                        assertTrue(checked.identityVerified == true && checked.validationSourceKey != null)
                        val recipe = requireNotNull(com.fourseveneightnine.tv.client.playback.WatchlistSource.from(request, checked))
                        assertTrue(recipe.matches(checked))
                        assertFalse(recipe.matches(raw)) // Replaying raw rows cannot find this checked recipe.
                        checkedRow = checked
                        if (automaticDmm) client.playFlow.play(request) else client.playFlow.playRow(checked, request)
                    } else client.playFlow.play(request)
                    assertEquals(com.fourseveneightnine.tv.client.playback.PlayResult.Opened, opened)
                    withTimeout(15_000) { session.phase.first { it is ReceiverPlaybackPhase.Playing } }
                    session.seek(SeekCommand.AbsoluteSeconds(60.0)).getOrThrow()
                    withTimeout(5_000) { while (session.refreshSnapshot().positionSeconds < 59) delay(25) }
                    val oldUrl = requireNotNull(session.lastOpenMedia()).url
                    val device = UiDevice.getInstance(instrumentation)
                    val bounds = dockBounds(device, "Save source", KeyEvent.KEYCODE_MENU)
                    val evidence = File(context.getExternalFilesDir(null), "remote-checks").apply { mkdirs() }
                    val evidencePrefix = if (automaticDmm) "watchlist-dmm-auto" else if (checkedDmm) "watchlist-dmm" else "watchlist"
                    assertTrue(device.takeScreenshot(File(evidence, "$evidencePrefix-save-focus.png")))
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                    val folder = withTimeout(5_000) {
                        library.savedCollections().first { folders -> folders.any { folder ->
                            folder.items.any { row -> library.savedSource(row.canonicalId)?.contains(id) == true }
                        } }
                    }.first { it.sourceRef == "system:watchlist" }
                    val card = folder.items.first { row ->
                        library.savedSource(row.canonicalId)?.contains(id) == true
                    }
                    savedCard = folder.id to card.canonicalId
                    withTimeout(5_000) { client.playFlow.playingSourceSaved.first { it } }
                    assertDockIconColor(device, bounds, TvColor.Success.toArgb(), File(evidence, "$evidencePrefix-saved-focus.png"))
                    if (Build.VERSION.SDK_INT >= 34) {
                        instrumentation.uiAutomation.clearCache()
                        assertTrue(device.hasObject(By.checked(true).hasDescendant(By.desc("Source saved"))))
                    }
                    val bookmark = requireNotNull(library.savedSource(card.canonicalId))
                    assertFalse(oldUrl in bookmark || fixtureBase in bookmark)
                    checkedRow?.let { checked ->
                        val recipe = kotlinx.serialization.json.Json.decodeFromString<com.fourseveneightnine.tv.client.playback.WatchlistSource>(bookmark)
                        assertTrue(recipe.matches(checked))
                    }
                    session.stop().getOrThrow()
                    val position = requireNotNull(library.resumePosition(id, "movie", null, null))
                    assertTrue(position >= 59_000)
                    val oldNonce = requireNotNull(Uri.parse(oldUrl).getQueryParameter("generation"))
                    ownershipFixture("reset?expire=$oldNonce")
                    assertEquals("The selected old capability must be expired before replay", 410, mediaStatus(oldUrl))
                    val pendingReplay = async(Dispatchers.Default) {
                        // AppRoot starts saved cards with this generic label before decoding the recipe.
                        client.playFlow.play(PlayRequest(card.mediaType, card.canonicalId, "Saved source"))
                    }
                    replay = pendingReplay
                    withTimeout(15_000) {
                        while (!ownershipFixture("state").optBoolean("waiting")) delay(25)
                    }
                    assertFalse(pendingReplay.isCompleted)
                    val preparing = requireNotNull(client.playFlow.preparing.value)
                    assertEquals(id, preparing.id)
                    assertEquals("Ownership fixture", preparing.title)
                    assertEquals(card.canonicalId, preparing.watchlistId)
                    ownershipFixture("release")
                    val result = withTimeout(15_000) { pendingReplay.await() }
                    assertEquals(com.fourseveneightnine.tv.client.playback.PlayResult.Opened, result)
                    withTimeout(15_000) { session.phase.first { it is ReceiverPlaybackPhase.Playing } }
                    val reopened = requireNotNull(session.lastOpenMedia())
                    val mediaPath = if (checkedDmm) "/ownership-large.mp4?" else "/ownership.mp4?"
                    assertTrue(oldUrl != reopened.url && reopened.url.contains(mediaPath))
                    assertEquals("The refreshed capability must serve real ranged media", 206, mediaStatus(reopened.url))
                    assertEquals(position, reopened.startPositionMs)
                    assertEquals(id, client.playFlow.current.value?.id)
                    withTimeout(5_000) { client.playFlow.playingSourceSaved.first { it } }
                    if (retryAutomatic != null) {
                        assertNull(client.playFlow.preparing.value)
                        assertTrue(client.playFlow.canRecoverSource)
                        ownershipFixture("reset")
                        val retry = async(Dispatchers.Main.immediate) {
                            client.playFlow.retryCurrent(180_000, automatic = retryAutomatic)
                        }
                        replay = retry
                        withTimeout(15_000) {
                            while (!ownershipFixture("state").optBoolean("waiting")) {
                                if (retry.isCompleted) error("Retry finished before its held lookup: ${retry.await()}")
                                delay(25)
                            }
                        }
                        assertFalse(retry.isCompleted)
                        val retryRequest = requireNotNull(client.playFlow.preparing.value)
                        assertEquals(id, retryRequest.id)
                        assertEquals("Ownership fixture", retryRequest.title)
                        assertEquals(card.canonicalId, retryRequest.watchlistId)
                        ownershipFixture("release")
                        assertEquals(com.fourseveneightnine.tv.client.playback.PlayResult.Opened,
                            withTimeout(15_000) { retry.await() })
                        val retried = requireNotNull(session.lastOpenMedia())
                        assertTrue(retried.url != reopened.url && retried.url.contains(mediaPath))
                        assertEquals(180_000L, retried.startPositionMs)
                        assertEquals(card.canonicalId, client.playFlow.current.value?.watchlistId)
                        assertFalse(client.playFlow.canRecoverSource)
                        val generation = session.generation
                        assertEquals(com.fourseveneightnine.tv.client.playback.PlayResult.ShowList,
                            client.playFlow.retryCurrent(180_000, automatic = true))
                        assertEquals(generation, session.generation)
                        assertEquals(retried.url, session.lastOpenMedia()?.url)
                        if (!retryAutomatic) {
                            ownershipFixture("reset")
                            val takeover = async(Dispatchers.Default) {
                                try {
                                    client.playFlow.retryCurrent(210_000, automatic = false)
                                    error("Held Watchlist retry completed before phone takeover")
                                } catch (cancelled: CancellationException) {
                                    // Cancelling preparation must leave this caller able to continue.
                                    coroutineContext.ensureActive()
                                    com.fourseveneightnine.tv.client.playback.PlayResult.ShowList
                                }
                            }
                            replay = takeover
                            withTimeout(15_000) {
                                while (!ownershipFixture("state").optBoolean("waiting")) {
                                    if (takeover.isCompleted) error("Takeover retry ended before lookup: ${takeover.await()}")
                                    delay(25)
                                }
                            }
                            assertFalse(takeover.isCompleted)
                            val phoneTitle = "Watchlist retry phone takeover"
                            val phoneUrl = "$fixtureBase/clip.mp4"
                            session.open(OpenMediaRequest(phoneUrl, title = phoneTitle,
                                castId = "watchlist-retry-phone-${SystemClock.uptimeMillis()}")).getOrThrow()
                            val phoneGeneration = session.generation
                            ownershipFixture("release")
                            assertEquals(com.fourseveneightnine.tv.client.playback.PlayResult.ShowList,
                                withTimeout(15_000) { takeover.await() })
                            withTimeout(5_000) {
                                while (!ownershipFixture("state").optBoolean("released")) delay(25)
                            }
                            assertNull(client.playFlow.preparing.value)
                            assertEquals(phoneGeneration, session.generation)
                            assertEquals(phoneTitle, session.lastOpenMedia()?.title)
                            assertEquals(phoneUrl, session.lastOpenMedia()?.url)
                            assertEquals(PlaybackSession.Origin.Phone, session.origin.value)
                        }
                    }
                    library.removeItem(folder.id, card.canonicalId)
                    withTimeout(5_000) { client.playFlow.playingSourceSaved.first { !it } }
                } finally {
                    // Release the held HTTP route before joining a cancelled replay.
                    runCatching { ownershipFixture("release") }
                    replay?.cancelAndJoin()
                    session.stop()
                    savedCard?.let { (folder, card) -> library.removeItem(folder, card) }
                }
            }
        }
    }

    @Test fun latestPhoneOpenWinsWhileAnOlderPhoneCheckpointWaits() =
        heldCheckpointKeepsLatestIntent(olderStop = false, newerStop = false)

    @Test fun latestPhoneOpenWinsWhileAnOlderStopCheckpointWaits() =
        heldCheckpointKeepsLatestIntent(olderStop = true, newerStop = false)

    @Test fun latestStopWinsWhileAnOlderPhoneCheckpointWaits() =
        heldCheckpointKeepsLatestIntent(olderStop = false, newerStop = true)

    private fun heldCheckpointKeepsLatestIntent(olderStop: Boolean, newerStop: Boolean) {
        val entered = Channel<Unit>(Channel.UNLIMITED)
        val release = CompletableDeferred<Unit>()
        withNativeCheckpointSession(checkpoint = { entered.send(Unit); release.await() }) { session ->
            try {
                val originalGeneration = session.generation
                val older = async(Dispatchers.Main.immediate) {
                    try {
                        if (olderStop) session.stop()
                        else session.open(OpenMediaRequest("$fixtureBase/clip.mp4?intent=older", title = "Older phone"))
                    } catch (cancelled: CancellationException) { Result.failure<Unit>(cancelled) }
                }
                withTimeout(5_000) { entered.receive() }
                val newer = async(Dispatchers.Main.immediate) {
                    try {
                        if (newerStop) session.stop()
                        else session.open(OpenMediaRequest("$fixtureBase/clip.mp4?intent=newer", title = "Newest phone"))
                    } catch (cancelled: CancellationException) { Result.failure<Unit>(cancelled) }
                }
                withTimeout(5_000) { entered.receive() }
                assertEquals("Checkpoint capture must retain the old accepted generation",
                    originalGeneration, session.generation)
                release.complete(Unit)
                val newestResult = withTimeout(15_000) { newer.await() }
                newestResult.getOrThrow()
                val olderResult = withTimeout(15_000) { older.await() }
                if (!olderStop) {
                    assertTrue("The older phone intent must retire", olderResult.isFailure)
                    assertTrue(olderResult.exceptionOrNull() is CancellationException)
                }
                assertEquals(originalGeneration + 1, session.generation)
                if (newerStop) {
                    assertNull(session.origin.value)
                    assertFalse(session.refreshSnapshot().active)
                    assertEquals("Checkpoint fixture", session.lastOpenMedia()?.title)
                } else {
                    withTimeout(15_000) { session.phase.first { it is ReceiverPlaybackPhase.Playing } }
                    assertEquals(PlaybackSession.Origin.Phone, session.origin.value)
                    assertEquals("Newest phone", session.lastOpenMedia()?.title)
                    assertEquals("$fixtureBase/clip.mp4?intent=newer", session.lastOpenMedia()?.url)
                }
            } finally {
                release.complete(Unit)
                entered.close()
            }
        }
    }

    @Test fun failedLocalCheckpointDoesNotRejectPhonePlayback() {
        withNativeCheckpointSession(checkpoint = { throw IOException("synthetic checkpoint failure") }) { session ->
            val originalGeneration = session.generation
            session.open(OpenMediaRequest("$fixtureBase/clip.mp4?intent=after-failure",
                title = "Phone after checkpoint failure")).getOrThrow()
            withTimeout(15_000) { session.phase.first { it is ReceiverPlaybackPhase.Playing } }
            assertEquals(originalGeneration + 1, session.generation)
            assertEquals(PlaybackSession.Origin.Phone, session.origin.value)
            assertEquals("Phone after checkpoint failure", session.lastOpenMedia()?.title)
        }
    }

    @Test fun failedNativeStopKeepsOwnedErrorArtworkAndResumeUntilRecovery() {
        check((instrumentation.targetContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            "Private controller replacement requires the debuggable fixture APK"
        }
        withNativeCheckpointSession(checkpoint = {}) { session ->
            val controllerField = PlaybackSession::class.java.getDeclaredField("controller").apply { isAccessible = true }
            val proxy = controllerField.get(session) as SwappableReceiverController
            val innerField = SwappableReceiverController::class.java.getDeclaredField("inner").apply { isAccessible = true }
            val original = innerField.get(proxy) as com.fourseveneightnine.tv.protocol.ReceiverController
            val failing = object : com.fourseveneightnine.tv.protocol.ReceiverController by original {
                override suspend fun stop(): Result<Unit> = Result.failure(IOException("synthetic native Stop failure"))
            }
            try {
                val generation = session.generation
                session.seekTo(60_000).getOrThrow()
                withTimeout(10_000) { while (session.refreshSnapshot().positionSeconds < 58.0) delay(50) }
                assertTrue(NowPlayingArtwork.update(generation, "$fixtureBase/new.png", "$fixtureBase/old.png",
                    "$fixtureBase/series-logo.png"))
                val artwork = checkNotNull(NowPlayingArtwork.forPlayback(generation))
                assertEquals(artwork, session.nowPlayingArt.value)
                session.checkpointResume()
                val resume = checkNotNull(session.pendingResume(null))
                assertEquals("Checkpoint fixture", resume.title)
                assertTrue(resume.positionMillis >= 58_000)
                assertEquals("$fixtureBase/series-logo.png", resume.logoURL)
                assertEquals(PlaybackSession.Origin.Local, session.origin.value)
                assertTrue(session.refreshSnapshot().active)
                withContext(Dispatchers.Main.immediate) { innerField.set(proxy, failing) }

                assertTrue(session.discardUnverifiedLocalOpen(generation).isFailure)
                assertEquals(generation, session.generation)
                assertEquals(PlaybackSession.Origin.Local, session.origin.value)
                assertEquals(artwork, session.nowPlayingArt.value)
                assertEquals(resume.url, checkNotNull(session.pendingResume(null)).url)
                assertEquals(resume.title, session.resumeTitle)
                assertTrue(session.phase.value is ReceiverPlaybackPhase.Error)
                assertTrue(session.refreshSnapshot().active)
                // Delegation leaves the real native player/bridges intact. Only Stop fails.
                session.setSpeed(0).getOrThrow()
                withTimeout(5_000) { proxy.playbackPhase.first { phase -> phase == ReceiverPlaybackPhase.Paused } }
                instrumentation.waitForIdleSync()
                assertEquals(0, session.refreshSnapshot().speed)
                assertTrue("A real native Pause must not erase the owned Stop error", session.phase.value is ReceiverPlaybackPhase.Error)
                session.setSpeed(1).getOrThrow()
                withTimeout(5_000) { proxy.playbackPhase.first { phase -> phase == ReceiverPlaybackPhase.Playing } }
                instrumentation.waitForIdleSync()
                assertTrue(session.refreshSnapshot().speed > 0)
                assertTrue("A real native Play must not erase the owned Stop error", session.phase.value is ReceiverPlaybackPhase.Error)

                assertTrue(session.stop().isFailure)
                val failedStopOwner = session.generation
                assertEquals("Public Stop still invalidates older preparations", generation + 1, failedStopOwner)
                assertEquals(PlaybackSession.Origin.Local, session.origin.value)
                assertEquals(artwork, session.nowPlayingArt.value)
                assertEquals(resume.url, checkNotNull(session.pendingResume(null)).url)
                assertEquals(resume.title, session.resumeTitle)
                assertTrue(session.phase.value is ReceiverPlaybackPhase.Error)
                session.setSpeed(0).getOrThrow()
                withTimeout(5_000) { proxy.playbackPhase.first { phase -> phase == ReceiverPlaybackPhase.Paused } }
                instrumentation.waitForIdleSync()
                assertTrue("The failed public Stop must latch its new owner epoch", session.phase.value is ReceiverPlaybackPhase.Error)
                session.setSpeed(1).getOrThrow()
                withTimeout(5_000) { proxy.playbackPhase.first { phase -> phase == ReceiverPlaybackPhase.Playing } }
                instrumentation.waitForIdleSync()
                assertTrue(session.phase.value is ReceiverPlaybackPhase.Error)
                assertEquals(failedStopOwner, session.generation)

                withContext(Dispatchers.Main.immediate) { innerField.set(proxy, original) }
                session.stop().getOrThrow()
                val stoppedOwner = session.generation
                assertEquals(failedStopOwner + 1, stoppedOwner)
                assertNull(session.origin.value)
                assertNull(session.nowPlayingArt.value)
                assertNull(session.pendingResume(null))
                assertFalse(session.refreshSnapshot().active)
                withTimeout(5_000) { session.phase.first { phase ->
                    phase == ReceiverPlaybackPhase.Stopped || phase == ReceiverPlaybackPhase.Idle
                } }
                val title = "Native Stop recovery fixture"
                NowPlayingArtwork.stage(landscapeURL = "$fixtureBase/new.png", posterURL = "$fixtureBase/old.png",
                    logoURL = "$fixtureBase/series-logo.png", owner = title)
                session.open(OpenMediaRequest("$fixtureBase/clip.mp4?stop-recovered=1", title = title,
                    startPositionMs = 30_000), PlaybackSession.Origin.Local).getOrThrow()
                withTimeout(15_000) { session.phase.first { phase -> phase == ReceiverPlaybackPhase.Playing } }
                assertEquals(stoppedOwner + 1, session.generation)
                assertEquals(PlaybackSession.Origin.Local, session.origin.value)
                assertEquals(title, session.lastOpenMedia()?.title)
                assertEquals(title, checkNotNull(session.pendingResume(null)).title)
                assertEquals("$fixtureBase/series-logo.png", NowPlayingArtwork.forPlayback(session.generation)?.logoURL)
                assertTrue(session.refreshSnapshot().active)
            } finally {
                // Restore before the existing helper's real Stop/release cleanup, even on failure.
                withContext(NonCancellable + Dispatchers.Main.immediate) { innerField.set(proxy, original) }
            }
        }
    }

    @Test fun realLiveHlsRecoversFrom503AndPhoneRetiresHeldRenewal() {
        val renewals = java.util.concurrent.atomic.AtomicInteger()
        val holdRenewal = java.util.concurrent.atomic.AtomicBoolean(false)
        val firstRenewal = CompletableDeferred<Unit>()
        val heldEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val heldExited = CompletableDeferred<Unit>()
        val playlist = "$fixtureBase/live-fault/index.m3u8"
        val evidence = org.json.JSONObject()
        withNativeCheckpointSession(checkpoint = {}, renewLiveRequest = { _, request ->
            val attempt = renewals.incrementAndGet()
            if (holdRenewal.get()) {
                heldEntered.complete(Unit)
                try {
                    // Model a renewal response that arrives after cancellation. This suspends;
                    // it does not occupy a Default worker or block the Android Main thread.
                    withContext(NonCancellable) { release.await() }
                } finally { heldExited.complete(Unit) }
            } else firstRenewal.complete(Unit)
            request.copy(url = "$playlist?renew=$attempt")
        }) { session ->
            suspend fun waitState(predicate: (org.json.JSONObject) -> Boolean): org.json.JSONObject =
                withTimeout(35_000) {
                    var state = liveFaultFixture("state")
                    while (!predicate(state)) { delay(100); state = liveFaultFixture("state") }
                    state
                }
            suspend fun requireProgress() = withTimeout(10_000) {
                var previous = session.refreshSnapshot().positionSeconds
                var forward = 0.0
                while (forward < 0.5) {
                    delay(100)
                    val current = session.refreshSnapshot()
                    val delta = current.positionSeconds - previous
                    // Live-window shifts can move position backwards. Count only small,
                    // positive clock steps while the real player reports active playback.
                    if (current.active && current.speed > 0 && delta > 0.0 && delta < 1.0) forward += delta
                    previous = current.positionSeconds
                }
            }
            suspend fun openHealthyLive(suffix: String) {
                session.openLiveChannel(OpenMediaRequest("$playlist?phase=$suffix",
                    title = "Live HLS fault fixture", isLive = true),
                    PlaybackSession.LiveChannel("live-fault", "local-fixture", "Fixture")).getOrThrow()
                withTimeout(20_000) { session.phase.first { it is ReceiverPlaybackPhase.Playing } }
                requireProgress()
            }
            try {
                val reset = liveFaultFixture("reset")
                assertTrue(reset.getInt("segments") >= 30)
                assertEquals(2, reset.getInt("targetDuration"))
                openHealthyLive("recovery")
                val healthy = waitState { it.getInt("segment200") >= 2 }
                evidence.put("healthy", healthy)
                liveFaultFixture("cut")
                val failed = waitState { it.getInt("segment503") > healthy.getInt("segment503") }
                evidence.put("firstActual503", failed)
                withTimeout(35_000) { firstRenewal.await() }
                assertTrue("Automatic renewal must respect the existing three-attempt budget", renewals.get() in 1..3)
                val restored = liveFaultFixture("restore")
                withTimeout(25_000) { session.phase.first { it is ReceiverPlaybackPhase.Playing } }
                requireProgress()
                val recovered = waitState { it.getInt("segment200") > restored.getInt("segment200") }
                assertTrue(renewals.get() in 1..3)
                evidence.put("recovered", recovered).put("recoveryRenewals", renewals.get())

                // A normal public live open starts a new, independently bounded retry budget.
                // This keeps the takeover proof separate from the30-second reset policy.
                openHealthyLive("takeover")
                val beforeSecondCut = liveFaultFixture("state")
                val callsBeforeSecondCut = renewals.get()
                holdRenewal.set(true)
                liveFaultFixture("cut")
                evidence.put("secondActual503", waitState {
                    it.getInt("segment503") > beforeSecondCut.getInt("segment503")
                })
                withTimeout(35_000) { heldEntered.await() }
                assertTrue(renewals.get() - callsBeforeSecondCut in 1..3)
                val liveOwner = session.generation
                val phone = OpenMediaRequest("$fixtureBase/clip.mp4?network-phone=owned",
                    title = "Phone after live network failure", startPositionMs = 20_000)
                session.open(phone).getOrThrow()
                withTimeout(15_000) { session.phase.first { it is ReceiverPlaybackPhase.Playing } }
                requireProgress()
                val phoneOwner = session.generation
                val callsAtPhone = renewals.get()
                assertTrue(phoneOwner > liveOwner)
                assertFalse("The noncooperative renewal must still be held", heldExited.isCompleted)
                release.complete(Unit)
                withTimeout(5_000) { heldExited.await() }
                delay(1_200)
                assertEquals(phoneOwner, session.generation)
                assertEquals(PlaybackSession.Origin.Phone, session.origin.value)
                assertNull(session.liveChannel.value)
                assertEquals(phone.title, session.lastOpenMedia()?.title)
                assertEquals(phone.url, session.lastOpenMedia()?.url)
                assertEquals(callsAtPhone, renewals.get())
                requireProgress()
                evidence.put("phoneOwner", phoneOwner).put("retiredLiveOwner", liveOwner)
                    .put("renewalsAtPhone", callsAtPhone).put("passed", true)
            } finally {
                release.complete(Unit)
                withContext(NonCancellable) {
                    runCatching { liveFaultFixture("restore") }.onSuccess { evidence.put("finalServer", it) }
                    withContext(Dispatchers.IO) {
                        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "remote-checks").apply { mkdirs() }
                        File(folder, "live-hls-network-recovery-${SystemClock.uptimeMillis()}.json")
                            .writeText(evidence.toString(2))
                    }
                }
            }
        }
    }

    private suspend fun liveFaultFixture(action: String): org.json.JSONObject = withContext(Dispatchers.IO) {
        requireDisposableEmulator()
        check(action in setOf("reset", "cut", "restore", "state"))
        val connection = java.net.URL("$fixtureBase/live-fault/$action").openConnection() as java.net.HttpURLConnection
        try {
            connection.connectTimeout = 2_000
            connection.readTimeout = if (action == "reset") 35_000 else 3_000
            connection.useCaches = false
            connection.setRequestProperty("X-4789-Live-Fault", "disposable")
            connection.inputStream.bufferedReader().use { org.json.JSONObject(it.readText()) }
        } finally { connection.disconnect() }
    }

    /** A real native engine/surface; existing checkpoint and renewal callbacks can be controlled. */
    private fun withNativeCheckpointSession(
        checkpoint: suspend () -> Unit,
        renewLiveRequest: suspend (PlaybackSession.LiveChannel, OpenMediaRequest) -> OpenMediaRequest = { _, request -> request },
        exercise: suspend CoroutineScope.(PlaybackSession) -> Unit,
    ) {
        val context = instrumentation.targetContext
        runBlocking { context.appGraph.playback.stop().getOrThrow() }
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var controller: SwappableReceiverController? = null
        var surface: SurfaceView? = null
        var callback: SurfaceHolder.Callback? = null
        var ownedSession: PlaybackSession? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            runBlocking {
                try {
                    scenario.onActivity { activity ->
                        val native = SwappableReceiverController(context, ReceiverEngine.Exo)
                        controller = native
                        ownedSession = PlaybackSession(
                            controller = native, appContext = context, scope = ownerScope,
                            renewLiveRequest = renewLiveRequest, recordRecentChannel = { _ -> },
                            resumeStore = com.fourseveneightnine.tv.startup.ResumePointStore(context),
                            beforePhoneOpen = {}, beforeLocalStop = checkpoint,
                        )
                        val listener = object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) = native.attachSurfaceHolder(holder, 320, 180)
                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
                                native.attachSurfaceHolder(holder, width, height)
                            override fun surfaceDestroyed(holder: SurfaceHolder) = native.detachSurface()
                        }
                        callback = listener
                        surface = SurfaceView(activity).also {
                            it.holder.addCallback(listener)
                            activity.addContentView(it, ViewGroup.LayoutParams(320, 180))
                        }
                    }
                    val session = checkNotNull(ownedSession)
                    withTimeout(15_000) { session.surfaceReadyState.first { it } }
                    session.open(OpenMediaRequest("$fixtureBase/clip.mp4", title = "Checkpoint fixture"),
                        PlaybackSession.Origin.Local).getOrThrow()
                    withTimeout(15_000) { session.phase.first { it is ReceiverPlaybackPhase.Playing } }
                    exercise(session)
                } finally {
                    withContext(NonCancellable) {
                        // Cleanup bypasses the deliberately held/throwing checkpoint hook.
                        controller?.stop()
                        ownerScope.coroutineContext[Job]?.cancelAndJoin()
                        instrumentation.runOnMainSync {
                            callback?.let { surface?.holder?.removeCallback(it) }
                            controller?.detachSurface()
                            controller?.close()
                            (surface?.parent as? ViewGroup)?.removeView(surface)
                        }
                        com.fourseveneightnine.tv.startup.ResumePointStore(context).clear()
                    }
                }
            }
        }
    }

    @Test fun liveRecentsRequireDecodedPlaybackAndCannotCreditANewerPhoneCast() {
        val context = instrumentation.targetContext
        val session = context.appGraph.playback
        val repo = context.clientGraph.iptv
        val suffix = SystemClock.uptimeMillis()
        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking {
                repo.awaitLoaded()
                withTimeout(15_000) { session.surfaceReadyState.first { it } }
                val first = "fixture:recent-start-$suffix"
                val replaced = "fixture:recent-replaced-$suffix"
                try {
                    ownershipFixture("nextup-player-reset")
                    session.openLiveChannel(OpenMediaRequest("$fixtureBase/nextup-2.mp4", isLive = true),
                        PlaybackSession.LiveChannel(first, "fixture", "Live")).getOrThrow()
                    withTimeout(5_000) {
                        while (!ownershipFixture("state").optBoolean("nextupPlayerWaiting")) delay(25)
                    }
                    assertFalse("An accepted but undecoded stream is not recent",
                        first in repo.state.value.accounts.recentIds)
                    ownershipFixture("nextup-player-release")
                    withTimeout(15_000) { session.phase.first { phase -> phase == ReceiverPlaybackPhase.Playing } }
                    withTimeout(5_000) { repo.state.first { state -> first in state.accounts.recentIds } }
                    session.stop().getOrThrow()
                    ownershipFixture("nextup-player-reset")
                    session.openLiveChannel(OpenMediaRequest("$fixtureBase/nextup-2.mp4", isLive = true),
                        PlaybackSession.LiveChannel(replaced, "fixture", "Live")).getOrThrow()
                    withTimeout(5_000) {
                        while (!ownershipFixture("state").optBoolean("nextupPlayerWaiting")) delay(25)
                    }
                    session.open(OpenMediaRequest("$fixtureBase/clip.mp4", title = "Phone owns this frame")).getOrThrow()
                    ownershipFixture("nextup-player-release")
                    withTimeout(15_000) { session.phase.first { phase -> phase == ReceiverPlaybackPhase.Playing } }
                    delay(500)
                    assertEquals(PlaybackSession.Origin.Phone, session.origin.value)
                    assertFalse("A phone frame must not credit the replaced channel",
                        replaced in repo.state.value.accounts.recentIds)
                } finally {
                    ownershipFixture("nextup-player-release")
                    session.stop()
                }
            }
        }
    }

    @Test fun mediaSessionStopPersistsTheOriginalProfileBeforeTheFirstProgressTick() {
        check(Build.VERSION.SDK_INT >= 29) { "MediaSession permission adoption requires an API29+ emulator" }
        val context = instrumentation.targetContext
        val client = context.clientGraph
        val session = context.appGraph.playback
        val id = "fixture:ownership-progress-${SystemClock.uptimeMillis()}"
        runBlocking { session.stop().getOrThrow() }
        instrumentation.runOnMainSync { client.playFlow.clearCurrent() }
        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking {
                var shellIdentity = false
                try {
                    withTimeout(15_000) { session.surfaceReadyState.first { it } }
                    withTimeout(15_000) { client.profileSession.state.first { it is ProfileSessionState.Unlocked } }
                    val originalLibrary = client.activeLibrary
                    val originalProfile = client.activeProfileId()
                    originalLibrary.recordLocalPlay(id, "movie", "Ownership fixture")
                    originalLibrary.writeProgress(id, "movie", positionMs = 1_000, durationMs = 600_000)
                    val opened = client.playFlow.playRow(selectedOwnershipRow(id), PlayRequest("movie", id, "Ownership fixture",
                        resumeFromMs = 120_000))
                    assertEquals(com.fourseveneightnine.tv.client.playback.PlayResult.Opened, opened)
                    withTimeout(10_000) { session.phase.first { it is ReceiverPlaybackPhase.Playing } }
                    val before = session.refreshSnapshot()
                    val beforeMillis = (before.positionSeconds * 1_000).toLong()
                    assertTrue("The fixture must start at its explicit resume position", beforeMillis >= 118_000)
                    val previous = originalLibrary.playbackHistory().first().first { it.canonicalId == id }
                    assertEquals("Progress must still be unchanged before system Stop", 1_000L, previous.positionMs)

                    instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.MEDIA_CONTENT_CONTROL")
                    shellIdentity = true
                    val manager = context.getSystemService(MediaSessionManager::class.java)
                    val mediaController = manager.getActiveSessions(null).first { it.packageName == context.packageName }
                    mediaController.transportControls.stop()
                    withTimeout(5_000) { session.origin.first { it == null } }
                    val stored = withTimeout(5_000) {
                        originalLibrary.playbackHistory().first { rows ->
                            rows.any { it.canonicalId == id && it.positionMs >= beforeMillis - 1_000 }
                        }.first { it.canonicalId == id }
                    }
                    assertTrue(kotlin.math.abs(stored.positionMs - beforeMillis) <= 5_000)
                    assertTrue(stored.durationMs > 550_000)
                    assertEquals(originalProfile, client.activeProfileId())
                } finally {
                    if (shellIdentity) instrumentation.uiAutomation.dropShellPermissionIdentity()
                    client.playFlow.cancelPreparation()
                    session.stop()
                    instrumentation.runOnMainSync { client.playFlow.clearCurrent() }
                }
            }
        }
    }

    @Test fun executeActionStopCheckpointsLocalPlaybackAndRetiresHeldPreparation() {
        val context = instrumentation.targetContext
        val client = context.clientGraph
        val flow = client.playFlow
        val session = context.appGraph.playback
        val suffix = SystemClock.uptimeMillis()
        val playingId = "fixture:ownership-execute-stop-$suffix"
        val blockedId = "fixture:ownership-blocked-execute-stop-$suffix"
        runBlocking { session.stop().getOrThrow() }
        instrumentation.runOnMainSync { flow.clearCurrent() }
        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking {
                var pending: Job? = null
                try {
                    withTimeout(15_000) { session.surfaceReadyState.first { it } }
                    withTimeout(15_000) { client.profileSession.state.first { it is ProfileSessionState.Unlocked } }
                    val profile = requireNotNull(client.activeProfileId())
                    val library = client.activeLibrary
                    val services = withTimeout(15_000) { client.services.filterNotNull().first() }
                    checkNotNull(services.meta.meta("movie", blockedId))
                    library.recordLocalPlay(playingId, "movie", "Ownership fixture")
                    library.writeProgress(playingId, "movie", positionMs = 1_000, durationMs = 600_000)
                    assertEquals(com.fourseveneightnine.tv.client.playback.PlayResult.Opened,
                        flow.playRow(selectedOwnershipRow(playingId), PlayRequest("movie", playingId, "Ownership fixture", resumeFromMs = 120_000)))
                    withTimeout(10_000) { session.phase.first { it is ReceiverPlaybackPhase.Playing } }
                    val generation = session.generation
                    assertEquals(PlaybackSession.Origin.Local, session.origin.value)

                    ownershipFixture("reset")
                    pending = launch(Dispatchers.Default) {
                        flow.play(PlayRequest("movie", blockedId, "Ownership fixture"))
                    }
                    withTimeout(10_000) {
                        flow.preparing.first { it?.id == blockedId }
                        while (!ownershipFixture("state").optBoolean("waiting")) delay(25)
                    }
                    val beforeStop = (session.refreshSnapshot().positionSeconds * 1_000).toLong()
                    assertTrue(beforeStop >= 118_000)
                    assertEquals("The central Stop capture must cause the next write", 1_000L,
                        library.playbackHistory().first().first { row -> row.canonicalId == playingId }.positionMs)
                    val dispatcher = com.fourseveneightnine.tv.protocol.RpcDispatcher(session)
                    val response = org.json.JSONObject(dispatcher.dispatch(
                        """{"jsonrpc":"2.0","id":9201,"method":"Input.ExecuteAction","params":{"action":"stop"}}"""))
                    assertFalse(response.has("error"))
                    assertEquals("OK", response.getString("result"))
                    assertEquals(generation + 1, session.generation)
                    assertNull(session.origin.value)
                    assertNull(session.nowPlayingArt.value)
                    assertFalse(session.refreshSnapshot().active)
                    val checkpoint = withTimeout(5_000) {
                        library.playbackHistory().first { rows -> rows.any { row ->
                            row.canonicalId == playingId && row.positionMs >= beforeStop - 1_000
                        } }.first { row -> row.canonicalId == playingId }
                    }
                    assertTrue(kotlin.math.abs(checkpoint.positionMs - beforeStop) <= 5_000)
                    assertTrue(checkpoint.durationMs > 550_000)
                    assertEquals(profile, client.activeProfileId())

                    ownershipFixture("release")
                    withTimeout(25_000) {
                        checkNotNull(pending).join()
                        while (!ownershipFixture("state").optBoolean("released")) delay(25)
                        flow.preparing.first { it == null }
                    }
                    assertTrue("The held local request must retire after the wire Stop", checkNotNull(pending).isCancelled)
                    assertEquals(generation + 1, session.generation)
                    assertNull(session.origin.value)
                    assertFalse(session.refreshSnapshot().active)
                    assertEquals("The held source must not replace the stopped request",
                        "Ownership fixture", session.lastOpenMedia()?.title)
                    assertEquals(playingId, flow.current.value?.id)
                } finally {
                    runCatching { ownershipFixture("release") }
                    withContext(NonCancellable) {
                        withTimeout(10_000) { pending?.cancelAndJoin() }
                        session.stop()
                        instrumentation.runOnMainSync { flow.clearCurrent() }
                    }
                }
            }
        }
    }

    @Test fun phoneTakeoverRetiresDelayedLauncherResumeAndKeepsItsCollectorAlive() {
        val context = instrumentation.targetContext
        val client = context.clientGraph
        val session = context.appGraph.playback
        val suffix = SystemClock.uptimeMillis()
        val controlId = "fixture:ownership-control-$suffix"
        val blockedId = "fixture:ownership-blocked-$suffix"
        runBlocking { session.stop().getOrThrow() }
        instrumentation.runOnMainSync { client.playFlow.clearCurrent() }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var scenarioIntent: Intent
            scenario.onActivity { scenarioIntent = Intent(it.intent) }
            runBlocking {
                try {
                    withTimeout(15_000) { session.surfaceReadyState.first { it } }
                    withTimeout(15_000) {
                        requireNotNull(context.appGraph.receiverHost).settingsConfigured.first { it }
                    }
                    withTimeout(15_000) { client.profileSession.state.first { it is ProfileSessionState.Unlocked } }
                    instrumentation.waitForIdleSync()
                    val profile = requireNotNull(client.activeProfileId())
                    for (id in listOf(controlId, blockedId)) {
                        client.activeLibrary.recordLocalPlay(id, "movie", "Ownership fixture",
                            posterUrl = "$fixtureBase/new.png", backdropUrl = "$fixtureBase/old.png")
                        client.activeLibrary.writeProgress(id, "movie", positionMs = 60_000, durationMs = 600_000)
                        val request = PlayRequest("movie", id, "Ownership fixture")
                        val previous = selectedOwnershipRow(id)
                        val source = requireNotNull(com.fourseveneightnine.tv.client.playback.WatchlistSource.from(
                            request, previous))
                        check(com.fourseveneightnine.tv.startup.ResumePointStore(context).saveTitle(
                            com.fourseveneightnine.tv.player.ResumePoint(requireNotNull(previous.url), title = request.title,
                                origin = "local", profileId = profile.toString(), catalogId = id, catalogType = "movie",
                                positionMillis = 60_000, durationMillis = 600_000, savedAtMillis = 0,
                                sourceRecipe = kotlinx.serialization.json.Json.encodeToString(source))))
                    }
                    val resume: (String) -> Unit = { id ->
                        val uri = requireNotNull(SystemSearchIntentParser.resumeUri(
                            "${context.packageName}.search", profile, "movie", id))
                        scenario.onActivity { activity ->
                            activity.startActivity(Intent(activity, MainActivity::class.java)
                                .setAction(Intent.ACTION_VIEW).setData(Uri.parse(uri)))
                        }
                    }
                    // Prove that the exact fixture/admission/launcher path can open real media.
                    resume(controlId)
                    withTimeout(20_000) {
                        client.playFlow.current.first { it?.id == controlId }
                        client.playFlow.preparing.first { it == null }
                        session.phase.first { it is ReceiverPlaybackPhase.Playing }
                    }
                    assertEquals(PlaybackSession.Origin.Local, session.origin.value)
                    assertEquals("Ownership fixture", session.lastOpenMedia()?.title)
                    assertTrue(client.playFlow.activeSource.value != null)
                    session.stop().getOrThrow()
                    instrumentation.runOnMainSync { client.playFlow.clearCurrent() }

                    ownershipFixture("reset")
                    resume(blockedId)
                    withTimeout(10_000) {
                        client.playFlow.preparing.first { it?.id == blockedId }
                        while (!ownershipFixture("state").optBoolean("waiting")) delay(25)
                    }
                    val phoneTitle = "Phone takeover fixture"
                    val phoneCastId = "phone-ownership-$suffix"
                    NowPlayingArtwork.stage("$fixtureBase/new.png", null,
                        "$fixtureBase/series-logo.png", owner = phoneCastId)
                    // This is the same default open overload RpcDispatcher uses for Origin.Phone.
                    session.open(OpenMediaRequest("$fixtureBase/clip.mp4", title = phoneTitle,
                        castId = phoneCastId)).getOrThrow()
                    val phoneGeneration = session.generation
                    assertEquals(PlaybackSession.Origin.Phone, session.origin.value)
                    assertEquals(phoneTitle, session.lastOpenMedia()?.title)
                    ownershipFixture("release")
                    withTimeout(20_000) {
                        while (!ownershipFixture("state").optBoolean("released")) delay(25)
                        client.playFlow.preparing.first { it == null }
                        session.phase.first { it is ReceiverPlaybackPhase.Playing }
                    }
                    assertEquals(phoneGeneration, session.generation)
                    assertEquals(PlaybackSession.Origin.Phone, session.origin.value)
                    assertEquals(phoneTitle, session.lastOpenMedia()?.title)
                    assertEquals("$fixtureBase/clip.mp4", session.lastOpenMedia()?.url)
                    assertEquals("$fixtureBase/series-logo.png",
                        NowPlayingArtwork.forPlayback(phoneGeneration)?.logoURL)
                    assertNull("A retired local request cannot publish an active source", client.playFlow.activeSource.value)
                    assertNull("A retired local request cannot become current", client.playFlow.current.value)
                    assertTrue(session.refreshSnapshot().active)

                    // The superseded request must not have killed systemSearchCommands.collect.
                    session.stop().getOrThrow()
                    resume(controlId)
                    withTimeout(20_000) {
                        client.playFlow.current.first { it?.id == controlId }
                        client.playFlow.preparing.first { it == null }
                        session.phase.first { it is ReceiverPlaybackPhase.Playing }
                    }
                    assertEquals(PlaybackSession.Origin.Local, session.origin.value)
                    assertEquals("Ownership fixture", session.lastOpenMedia()?.title)
                } finally {
                    runCatching { ownershipFixture("release") }
                    client.playFlow.cancelPreparation()
                    session.stop()
                    instrumentation.runOnMainSync { client.playFlow.clearCurrent() }
                    // ActivityScenario tracks lifecycle by its original launch intent.
                    scenario.onActivity { it.intent = scenarioIntent }
                }
            }
        }
    }

    /** These tests select a first source explicitly; no saved source exists yet. */
    private suspend fun selectedOwnershipRow(id: String): StreamRow {
        val services = withTimeout(15_000) { instrumentation.targetContext.clientGraph.services.filterNotNull().first() }
        val addon = services.registry.streamAddons().first { it.manifestURL == "$fixtureBase/manifest.json" }
        return services.client.streams(addon, "movie", id).single()
    }

    private fun ownershipFixture(action: String): org.json.JSONObject {
        val connection = java.net.URL("$fixtureBase/ownership/$action").openConnection().apply {
            connectTimeout = 2_000
            readTimeout = 2_000
            useCaches = false
        }
        return connection.getInputStream().bufferedReader().use { org.json.JSONObject(it.readText()) }
    }

    @Test fun cancelledLocalCheckpointKeepsTheCurrentRunsPeriodicProgress() {
        check((instrumentation.targetContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            "Private Room and follower reflection require the debuggable fixture APK"
        }
        val context = instrumentation.targetContext
        val client = context.clientGraph
        val flow = client.playFlow
        val session = context.appGraph.playback
        val id = "fixture:ownership-cancel-progress-${SystemClock.uptimeMillis()}"
        runBlocking { session.stop().getOrThrow() }
        instrumentation.runOnMainSync { flow.clearCurrent() }
        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking {
                withTimeout(15_000) { session.surfaceReadyState.first { it } }
                withTimeout(15_000) { client.profileSession.state.first { it is ProfileSessionState.Unlocked } }
                val originalProfile = client.activeProfileId()
                val originalLibrary = client.activeLibrary
                originalLibrary.recordLocalPlay(id, "movie", "Ownership fixture")
                originalLibrary.writeProgress(id, "movie", positionMs = 1_000, durationMs = 600_000)
                val release = CompletableDeferred<Unit>()
                var holder: Job? = null
                var capture: Job? = null
                try {
                    val opened = flow.playRow(selectedOwnershipRow(id), PlayRequest("movie", id, "Ownership fixture", resumeFromMs = 60_000))
                    assertEquals(com.fourseveneightnine.tv.client.playback.PlayResult.Opened, opened)
                    withTimeout(10_000) { session.phase.first { it is ReceiverPlaybackPhase.Playing } }
                    val generation = session.generation
                    val evidence = File(context.getExternalFilesDir(null), "remote-checks").apply { mkdirs() }
                    fun checkpointStage(stage: String) {
                        File(evidence, "checkpoint-cancel-stage.json").writeText(org.json.JSONObject()
                            .put("stage", stage).put("id", id).put("generation", generation)
                            .put("uptimeMillis", SystemClock.uptimeMillis()).toString())
                    }
                    checkpointStage("local-playing")
                    assertEquals(PlaybackSession.Origin.Local, session.origin.value)
                    assertTrue(session.resumePositionMillis() >= 60_000)
                    // These are the active production objects; do not open a second database.
                    val databaseField = LibraryRepository::class.java.getDeclaredField("database").apply { isAccessible = true }
                    val database = databaseField.get(originalLibrary) as LibraryDatabase
                    val followerField = flow.javaClass.getDeclaredField("progressJob").apply { isAccessible = true }
                    val originalFollower = withContext(Dispatchers.Main.immediate) {
                        checkNotNull(followerField.get(flow) as Job?).also { assertTrue(it.isActive) }
                    }
                    assertEquals(1_000L, database.watchProgressDao().find(id)?.positionMs)
                    val entered = CompletableDeferred<Unit>()
                    holder = launch(Dispatchers.IO) {
                        database.withTransaction {
                            database.recentDao().upsert(checkNotNull(database.recentDao().find(id)))
                            entered.complete(Unit)
                            withTimeout(20_000) { release.await() }
                        }
                    }
                    checkpointStage("await-writer")
                    withTimeout(5_000) { entered.await() }
                    capture = launch(Dispatchers.Main.immediate) { flow.captureStopProgress() }
                    // The checkpoint cancelled and joined the real follower before its Room write.
                    checkpointStage("await-original-follower")
                    withTimeout(5_000) { originalFollower.join() }
                    instrumentation.waitForIdleSync()
                    assertFalse(originalFollower.isActive)
                    assertTrue("The checkpoint must still wait on the held writer", checkNotNull(capture).isActive)
                    assertFalse(release.isCompleted)
                    checkpointStage("cancel-checkpoint")
                    checkNotNull(capture).cancel()
                    assertTrue(checkNotNull(capture).isCancelled)
                    release.complete(Unit)
                    checkpointStage("join-after-release")
                    withTimeout(5_000) { checkNotNull(holder).join(); checkNotNull(capture).join() }
                    // Room may finish the cancelled checkpoint. Require progress beyond both that
                    // write and the refreshed live playhead, so it cannot masquerade as a new tick.
                    val savedAfterCancel = database.watchProgressDao().find(id)?.positionMs ?: 0L
                    val liveAfterCancel = (session.refreshSnapshot().positionSeconds * 1_000).toLong()
                    val minimumPeriodicPosition = maxOf(savedAfterCancel, liveAfterCancel) + 1_000
                    assertEquals(generation, session.generation)
                    assertEquals(PlaybackSession.Origin.Local, session.origin.value)
                    assertEquals(id, flow.current.value?.id)
                    assertEquals(originalProfile, client.activeProfileId())

                    // No Stop, Pause, replacement open, or direct recorder call occurs before this write.
                    checkpointStage("await-periodic")
                    val stored = withTimeout(20_000) {
                        originalLibrary.playbackHistory().first { rows ->
                            rows.any { it.canonicalId == id && it.positionMs >= minimumPeriodicPosition }
                        }.first { it.canonicalId == id }
                    }
                    assertTrue(stored.durationMs > 550_000)
                    assertEquals(generation, session.generation)
                    assertEquals(PlaybackSession.Origin.Local, session.origin.value)
                    assertTrue(session.phase.value is ReceiverPlaybackPhase.Playing)
                    assertTrue(session.refreshSnapshot().active)
                    assertEquals(id, flow.current.value?.id)
                    val resumedFollower = withContext(Dispatchers.Main.immediate) { followerField.get(flow) as Job? }
                    assertTrue("The accepted run must have a new active follower", resumedFollower?.isActive == true)
                    assertTrue(resumedFollower !== originalFollower)
                    checkpointStage("passed-periodic")
                } finally {
                    release.complete(Unit)
                    withContext(NonCancellable) {
                        withTimeout(10_000) { capture?.cancelAndJoin(); holder?.cancelAndJoin() }
                        session.stop()
                        instrumentation.runOnMainSync { flow.clearCurrent() }
                    }
                }
            }
        }
    }

    @Test fun acceptedLocalRunFinishesAfterItsComposeCallerIsDisposed() =
        acceptedRunFinalization(phoneTakeover = false)

    @Test fun acceptedLocalFinalizationCannotReplaceANewerPhoneOwner() =
        acceptedRunFinalization(phoneTakeover = true)

    private fun acceptedRunFinalization(phoneTakeover: Boolean) {
        check((instrumentation.targetContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            "Private Room reflection is allowed only in the debuggable fixture APK"
        }
        val context = instrumentation.targetContext
        val client = context.clientGraph
        val flow = client.playFlow
        val session = context.appGraph.playback
        val id = "fixture:ownership-post-${SystemClock.uptimeMillis()}"
        runBlocking { session.stop().getOrThrow() }
        instrumentation.runOnMainSync { flow.clearCurrent() }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            runBlocking {
                withTimeout(15_000) { session.surfaceReadyState.first { it } }
                withTimeout(15_000) { client.profileSession.state.first { it is ProfileSessionState.Unlocked } }
                val originalProfile = client.activeProfileId()
                val originalLibrary = client.activeLibrary
                originalLibrary.recordLocalPlay(id, "movie", "Before accepted run")
                originalLibrary.writeProgress(id, "movie", positionMs = 1_000, durationMs = 600_000)
                val services = withTimeout(15_000) { client.services.filterNotNull().first() }
                checkNotNull(services.meta.meta("movie", id))
                val fixtureSize = ownershipFixture("state").getLong("bytes")
                check(fixtureSize > 0)
                // No public accessor exposes the same active-profile Room instance.
                // Do not open a second database or replace the production repository.
                val field = LibraryRepository::class.java.getDeclaredField("database").apply { isAccessible = true }
                val database = field.get(originalLibrary) as LibraryDatabase
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val holder = launch(Dispatchers.IO) {
                    database.withTransaction {
                        database.recentDao().upsert(checkNotNull(database.recentDao().find(id)))
                        entered.complete(Unit)
                        withTimeout(20_000) { release.await() }
                    }
                }
                val callerScope = AtomicReference<CoroutineScope?>()
                var callerView: ComposeView? = null
                var localJob: Job? = null
                try {
                    withTimeout(5_000) { entered.await() }
                    scenario.onActivity { activity ->
                        callerView = ComposeView(activity).also { view ->
                            activity.addContentView(view, ViewGroup.LayoutParams(1, 1))
                            view.setContent {
                                val scope = rememberCoroutineScope()
                                SideEffect { callerScope.set(scope) }
                            }
                        }
                    }
                    instrumentation.waitForIdleSync()
                    withTimeout(5_000) { while (callerScope.get() == null) delay(20) }
                    val startedAt = SystemClock.elapsedRealtime()
                    instrumentation.runOnMainSync {
                        localJob = checkNotNull(callerScope.get()).launch {
                            flow.playRow(StreamRow("post-source-$id", "Ownership fixture", "Ownership fixture",
                                "Ownership.Fixture.2024.720p.ENG.mp4", url = "$fixtureBase/ownership.mp4",
                                sizeBytes = fixtureSize),
                                PlayRequest("movie", id, "Ownership fixture", resumeFromMs = 120_000))
                        }
                    }
                    withTimeout(10_000) { flow.activeSource.filterNotNull().first() }
                    withTimeout(10_000) { session.phase.first { it is ReceiverPlaybackPhase.Playing } }
                    assertTrue("The accepted request must still be blocked in Room", checkNotNull(localJob).isActive)
                    assertFalse(release.isCompleted)
                    val localGeneration = session.generation
                    assertEquals(PlaybackSession.Origin.Local, session.origin.value)
                    instrumentation.runOnMainSync { checkNotNull(callerView).disposeComposition() }
                    assertTrue("Disposing Compose must cancel its request job", checkNotNull(localJob).isCancelled)
                    var phoneGeneration: Long? = null
                    val phoneTitle = "Phone owns post-acceptance fixture"
                    if (phoneTakeover) {
                        NowPlayingArtwork.stage(landscapeURL = "$fixtureBase/new.png", posterURL = null,
                            logoURL = "$fixtureBase/series-logo.png", owner = "post-phone")
                        session.open(OpenMediaRequest("$fixtureBase/clip.mp4", title = phoneTitle,
                            castId = "post-phone", startPositionMs = 30_000)).getOrThrow()
                        phoneGeneration = session.generation
                        assertTrue(checkNotNull(phoneGeneration) > localGeneration)
                    }
                    release.complete(Unit)
                    withTimeout(5_000) { holder.join(); checkNotNull(localJob).join() }
                    assertTrue(checkNotNull(localJob).isCancelled)
                    assertEquals(originalProfile, client.activeProfileId())
                    assertEquals("Ownership fixture", database.recentDao().find(id)?.title)
                    if (phoneTakeover) {
                        withTimeout(10_000) { session.phase.first { it is ReceiverPlaybackPhase.Playing } }
                        assertEquals(phoneGeneration, session.generation)
                        assertEquals(PlaybackSession.Origin.Phone, session.origin.value)
                        assertEquals(phoneTitle, session.lastOpenMedia()?.title)
                        assertEquals("$fixtureBase/clip.mp4", session.lastOpenMedia()?.url)
                        assertEquals("$fixtureBase/series-logo.png",
                            NowPlayingArtwork.forPlayback(checkNotNull(phoneGeneration))?.logoURL)
                        session.stop().getOrThrow()
                        assertEquals("A superseded Local finalizer must not persist the phone playhead",
                            1_000L, database.watchProgressDao().find(id)?.positionMs)
                    } else {
                        assertEquals(localGeneration, session.generation)
                        assertEquals(PlaybackSession.Origin.Local, session.origin.value)
                        val beforeStop = (session.refreshSnapshot().positionSeconds * 1_000).toLong()
                        assertTrue(beforeStop >= 118_000)
                        assertEquals(1_000L, database.watchProgressDao().find(id)?.positionMs)
                        assertTrue("The original marker must be tested before the first 15s tick",
                            SystemClock.elapsedRealtime() - startedAt < 15_000)
                        // PLAY-3's central owner hook must see the recorder established by finalization.
                        session.stop().getOrThrow()
                        val stored = withTimeout(5_000) {
                            originalLibrary.playbackHistory().first { rows ->
                                rows.any { it.canonicalId == id && it.positionMs >= beforeStop - 1_000 }
                            }.first { it.canonicalId == id }
                        }
                        assertTrue(kotlin.math.abs(stored.positionMs - beforeStop) <= 5_000)
                        assertTrue(stored.durationMs > 550_000)
                    }
                } finally {
                    release.complete(Unit)
                    instrumentation.runOnMainSync {
                        callerView?.disposeComposition()
                        (callerView?.parent as? ViewGroup)?.removeView(callerView)
                    }
                    withContext(NonCancellable) {
                        withTimeout(10_000) { holder.cancelAndJoin(); localJob?.cancelAndJoin() }
                        session.stop()
                        instrumentation.runOnMainSync { flow.clearCurrent() }
                    }
                }
            }
        }
    }

    @OptIn(UnstableApi::class)
    @Test fun bomDeclaredSubRipSurvivesDetectionNormalizationAndThePinnedParser() {
        val caption = "Café தமிழ் 🎬"
        fun parse(bytes: ByteArray): CuesWithTiming {
            val parsed = mutableListOf<CuesWithTiming>()
            SubripParser().parse(bytes, SubtitleParser.OutputOptions.allCues()) { parsed += it }
            assertEquals(1, parsed.size)
            return parsed.single()
        }
        val encodings = listOf(
            Triple("UTF8", StandardCharsets.UTF_8, byteArrayOf()),
            Triple("UTF8 BOM", StandardCharsets.UTF_8, byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())),
            Triple("UTF16LE BOM", StandardCharsets.UTF_16LE, byteArrayOf(0xFF.toByte(), 0xFE.toByte())),
            Triple("UTF16BE BOM", StandardCharsets.UTF_16BE, byteArrayOf(0xFE.toByte(), 0xFF.toByte())),
        )
        for ((name, charset, bom) in encodings) {
            for (separator in listOf(',', '.')) {
                val raw = bom + "1\n00:00:01${separator}250 --> 00:00:02${separator}500\n$caption\n\n".toByteArray(charset)
                if (separator == ',') {
                    // Confirm the pinned parser already accepts each original BOM encoding.
                    assertEquals(name, caption, parse(raw).cues.single().text.toString())
                }
                val mime = ExternalSubtitleFormatPolicy.detect(
                    "$fixtureBase/download?id=bom-fixture", "application/octet-stream", raw)
                assertEquals(name, MimeTypes.APPLICATION_SUBRIP, mime)
                val window = parse(ExternalSubtitleFormatPolicy.normalize(raw, mime))
                assertEquals(name, 1_250_000L, window.startTimeUs)
                assertEquals(name, 1_250_000L, window.durationUs)
                assertEquals(name, caption, window.cues.single().text.toString())
            }
        }
    }

    @Test fun externalHandoffCarriesTheChosenSidecarAndHonorsOff() {
        val context = instrumentation.targetContext
        val session = context.appGraph.playback
        val target = InstalledExternalPlayer("Intercepted fixture", ExternalPlayerIntentPolicy.PACKAGE_JUST_PLAYER)
        runBlocking { session.stop().getOrThrow() }
        instrumentation.runOnMainSync { context.clientGraph.playFlow.clearCurrent() }
        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking {
                try {
                    withTimeout(15_000) { session.surfaceReadyState.first { it } }
                    for (choice in listOf("first", "latest", "off", "disabled")) {
                        session.open(OpenMediaRequest("$fixtureBase/clip.mp4", title = "Sidecar fixture",
                            headers = mapOf("X-Fixture" to "handoff"), startPositionMs = 60_000)).getOrThrow()
                        withTimeout(15_000) { session.phase.first { it is ReceiverPlaybackPhase.Playing } }
                        session.addSubtitle("$fixtureBase/handoff-a.srt").getOrThrow()
                        session.addSubtitle("$fixtureBase/handoff-b.srt").getOrThrow()
                        val first = session.tracks().getOrThrow().subtitles.first { it.name == "Cast subtitle 1" }
                        when (choice) {
                            "first" -> session.selectSubtitle(SubtitleSelection.Index(first.index)).getOrThrow()
                            "latest" -> session.selectSubtitle(SubtitleSelection.Latest).getOrThrow()
                            "off" -> session.selectSubtitle(SubtitleSelection.Off).getOrThrow()
                            "disabled" -> session.selectSubtitle(SubtitleSelection.Index(first.index, enable = false)).getOrThrow()
                        }
                        val before = (session.refreshSnapshot().positionSeconds * 1_000).toLong()
                        assertTrue("The resume fixture must be active", before >= 58_000)
                        val captured = AtomicReference<Intent?>()
                        val monitor = object : Instrumentation.ActivityMonitor() {
                            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                                if (intent.`package` != target.packageName) return null
                                captured.set(Intent(intent))
                                return Instrumentation.ActivityResult(Activity.RESULT_OK, null)
                            }
                        }
                        instrumentation.addMonitor(monitor)
                        try {
                            assertTrue(session.externalHandoff(target))
                        } finally {
                            instrumentation.removeMonitor(monitor)
                        }
                        val sent = requireNotNull(captured.get()) { "The real handoff Intent must be intercepted" }
                        assertEquals("$fixtureBase/clip.mp4", sent.dataString)
                        assertTrue(kotlin.math.abs(sent.getIntExtra("position", -1).toLong() - before) <= 5_000)
                        assertEquals(listOf("X-Fixture", "handoff"), sent.getStringArrayExtra("headers")?.toList())
                        if (choice == "off" || choice == "disabled") {
                            assertFalse(sent.hasExtra("subs"))
                            assertFalse(sent.hasExtra("subs.enable"))
                            assertFalse(sent.hasExtra("subs.name"))
                        } else {
                            val suffix = if (choice == "first") "a" else "b"
                            @Suppress("DEPRECATION")
                            val urls = sent.getParcelableArrayExtra("subs")?.map { (it as Uri).toString() }
                            @Suppress("DEPRECATION")
                            val enabled = sent.getParcelableArrayExtra("subs.enable")?.map { (it as Uri).toString() }
                            assertEquals(listOf("$fixtureBase/handoff-$suffix.srt"), urls)
                            assertEquals(urls, enabled)
                            assertEquals(listOf(if (choice == "first") "Cast subtitle 1" else "Cast subtitle 2"),
                                sent.getStringArrayExtra("subs.name")?.toList())
                        }
                        assertNull(session.origin.value)
                    }
                } finally {
                    session.stop()
                    instrumentation.runOnMainSync { context.clientGraph.playFlow.clearCurrent() }
                }
            }
        }
    }

    @Test fun episodeDrawerKeepsPlayingSeparateFromRemoteFocusAndFormatsTitlesAndDates() {
        val context = instrumentation.targetContext
        val client = context.clientGraph
        val session = context.appGraph.playback
        val device = UiDevice.getInstance(instrumentation)
        val id = "fixture:nextup-drawer-type-${SystemClock.uptimeMillis()}"
        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking {
                try {
                    withTimeout(15_000) { session.surfaceReadyState.first { it } }
                    withTimeout(15_000) { client.profileSession.state.first { it is ProfileSessionState.Unlocked } }
                    val services = client.services.filterNotNull().first()
                    val meta = requireNotNull(services.meta.meta("series", id))
                    assertEquals("AP PHYSICS", meta.videos.last().title)
                    val file = "Carrie.S01E08.720p.ENG.mp4"
                    val row = StreamRow("drawer-episode-8", "Local fixture", file, file, filename = file,
                        url = "$fixtureBase/clip.mp4", sizeBytes = ownershipFixture("state").getLong("bytes"))
                    assertEquals(com.fourseveneightnine.tv.client.playback.PlayResult.Opened,
                        client.playFlow.playRow(row, PlayRequest("series", id, "Carrie", 1, 8)))
                    withTimeout(15_000) { session.phase.first { it == ReceiverPlaybackPhase.Playing } }
                    dockBounds(device, "All episodes", KeyEvent.KEYCODE_MENU)
                    val timeline = requireNotNull(device.wait(Until.findObject(By.desc("Playback position")), 5_000)).visibleBounds
                    val contextLabel = requireNotNull(device.findObject(By.textStartsWith("S1 E8"))).visibleBounds
                    val lastAction = requireNotNull(device.findObject(By.desc("More"))).visibleBounds
                    assertEquals(1244f / 1920f, timeline.width().toFloat() / device.displayWidth, 0.01f)
                    assertEquals(timeline.left + 22f * device.displayWidth / 1920f, contextLabel.left.toFloat(), 2f)
                    assertTrue("The seek track must span the action row", lastAction.right < timeline.right)
                    val dock = By.focused(true).hasDescendant(By.desc("All episodes"))
                    repeat(3) {
                        if (Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
                        if (!device.hasObject(dock)) {
                            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
                            delay(100)
                        }
                    }
                    assertTrue("All episodes must receive remote focus", device.hasObject(dock))
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                    val output = File(context.getExternalFilesDir(null), "remote-checks").apply { mkdirs() }
                    fun waitForEpisodeFocus(title: String) {
                        val deadline = SystemClock.uptimeMillis() + 5_000
                        do {
                            if (Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
                            val focus = device.findObject(By.focused(true))?.visibleBounds
                            val text = device.findObject(By.text(title))?.visibleBounds
                            if (focus != null && text != null && focus.contains(text)) return
                            SystemClock.sleep(80)
                        } while (SystemClock.uptimeMillis() < deadline)
                        device.dumpWindowHierarchy(File(output, "episode-focus-failure.xml"))
                        device.takeScreenshot(File(output, "episode-focus-failure.png"))
                        error("Expected remote focus on $title")
                    }
                    waitForEpisodeFocus("AP Physics")
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
                    waitForEpisodeFocus("Romantic Literature")
                    assertTrue(device.hasObject(By.text("7th October 2026")))
                    assertTrue(device.hasObject(By.text("Playing")))
                    assertFalse(device.hasObject(By.textContains("AP PHYSICS")))
                    assertFalse(device.hasObject(By.text("2026-10-07")))
                    assertEquals(8, client.playFlow.current.value?.episode)
                    check(device.takeScreenshot(File(output, "episode-drawer-carrie.png")))
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
                    waitForEpisodeFocus("Home Economics")
                    assertTrue(device.wait(Until.hasObject(By.text("Sample synopsis for episode six.")), 3_000))
                    assertEquals(8, client.playFlow.current.value?.episode)
                    device.pressBack()
                    val drawerGone = device.wait(Until.gone(By.text("Romantic Literature")), 5_000)
                    if (!drawerGone) {
                        device.takeScreenshot(File(output, "episode-drawer-after-back-failure.png"))
                        if (Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
                        File(output, "episode-drawer-after-back-cache.txt").writeText(
                            "originalGone=$drawerGone freshTextPresent=${device.hasObject(By.text("Romantic Literature"))}")
                        device.dumpWindowHierarchy(File(output, "episode-drawer-after-back-failure.xml"))
                    }
                    assertTrue(drawerGone)
                } finally { session.stop(); client.playFlow.clearCurrent() }
            }
        }
    }

    @Test fun aDismissedPreviewAndDisabledAutoplayBothLeaveTheNextEpisodeManual() {
        val context = instrumentation.targetContext
        val client = context.clientGraph
        val session = context.appGraph.playback
        val prefs = context.appGraph.presentationPreferences
        val previous = prefs.getBoolean(SettingsKeys.AUTO_NEXT, true)
        val device = UiDevice.getInstance(instrumentation)
        try {
            for (auto in listOf(true, false)) {
                check(prefs.edit().putBoolean(SettingsKeys.AUTO_NEXT, auto).commit())
                val id = "fixture:nextup-binge-${SystemClock.uptimeMillis()}"
                ActivityScenario.launch(MainActivity::class.java).use {
                    runBlocking {
                        try {
                            withTimeout(15_000) { session.surfaceReadyState.first { it } }
                            withTimeout(15_000) { client.profileSession.state.first { it is ProfileSessionState.Unlocked } }
                            val services = client.services.filterNotNull().first()
                            val addon = services.registry.streamAddons().first { it.manifestURL.startsWith(fixtureBase) }
                            val row = services.client.streams(addon, "series", "$id:1:1").single()
                            assertEquals(com.fourseveneightnine.tv.client.playback.PlayResult.Opened,
                                client.playFlow.playRow(row, PlayRequest("series", id, "Next Up fixture", 1, 1)))
                            withTimeout(15_000) { session.phase.first { it == ReceiverPlaybackPhase.Playing } }
                            withTimeout(10_000) { client.playFlow.nextUp.first { it?.episode == 2 } }
                            val duration = session.refreshSnapshot().durationSeconds
                            session.seek(SeekCommand.AbsoluteSeconds(duration - 11)).getOrThrow()
                            assertTrue(device.wait(Until.hasObject(By.text("Keep watching")), 8_000))
                            if (auto) assertTrue(device.pressBack())
                            session.seek(SeekCommand.AbsoluteSeconds(duration - 0.5)).getOrThrow()
                            withTimeout(10_000) { session.phase.first { it == ReceiverPlaybackPhase.Ended } }
                            val owner = session.generation
                            delay(11_000)
                            assertEquals(owner, session.generation)
                            assertEquals(1, client.playFlow.current.value?.episode)
                            assertEquals(ReceiverPlaybackPhase.Ended, session.phase.value)
                        } finally { session.stop(); client.playFlow.clearCurrent() }
                    }
                }
            }
        } finally { prefs.edit().putBoolean(SettingsKeys.AUTO_NEXT, previous).commit() }
    }

    @Test fun expiredEpisodeResumeRenewsAtTheCheckpointAndNextStartsBeforeAnUnrelatedCheckEnds() {
        val context = instrumentation.targetContext
        val client = context.clientGraph
        val session = context.appGraph.playback
        val id = "fixture:nextup-fast-${SystemClock.uptimeMillis()}"
        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking {
                withTimeout(15_000) { session.surfaceReadyState.first { it } }
                withTimeout(15_000) { client.profileSession.state.first { it is ProfileSessionState.Unlocked } }
                val services = client.services.filterNotNull().first()
                val addon = services.registry.streamAddons().first { it.manifestURL == "$fixtureBase/manifest.json" }
                val previous = services.client.streams(addon, "series", "$id:1:1").single()
                val source = requireNotNull(com.fourseveneightnine.tv.client.playback.WatchlistSource.from(
                    PlayRequest("series", id, "Next Up fixture", 1, 1), previous))
                val point = com.fourseveneightnine.tv.player.ResumePoint(
                    url = "$fixtureBase/source-deck-expired.mp4", catalogId = id, catalogType = "series", title = "Next Up fixture",
                    origin = "local", profileId = client.activeProfileId()?.toString(), season = 1, episode = 1,
                    positionMillis = 90_000L, durationMillis = 600_000L, savedAtMillis = System.currentTimeMillis(),
                    sourceRecipe = kotlinx.serialization.json.Json.encodeToString(source))
                try {
                    assertEquals(com.fourseveneightnine.tv.client.playback.PlayResult.Opened, withTimeout(25_000) { client.playFlow.restore(point) })
                    assertEquals(90_000L, requireNotNull(session.lastOpenMedia()).startPositionMs)
                    assertTrue(requireNotNull(session.lastOpenMedia()).url.endsWith("fast-episode=1"))
                    withTimeout(12_000) { session.phase.first { it == ReceiverPlaybackPhase.Playing } }
                    // This is the path the Home card invokes when its source checkpoint has survived.
                    assertEquals(com.fourseveneightnine.tv.client.playback.PlayResult.Opened,
                        client.playFlow.play(PlayRequest("series", id, "Next Up fixture", 1, 1, resumeFromMs = 91_000)))
                    assertEquals(91_000L, requireNotNull(session.lastOpenMedia()).startPositionMs)
                    assertEquals("Last played", client.playFlow.activeSource.value?.addonName)
                    withTimeout(12_000) { session.phase.first { it == ReceiverPlaybackPhase.Playing } }
                    withTimeout(10_000) { client.playFlow.nextUp.first { it?.episode == 2 } }
                    val tracks = session.refreshTracks()
                    val tamil = tracks.audio.single { com.fourseveneightnine.tv.client.playback.canonicalTrackLanguage(it.language) == "ta" }
                    val spanish = tracks.subtitles.single { com.fourseveneightnine.tv.client.playback.canonicalTrackLanguage(it.language) == "es" }
                    session.selectAudioTrack(tamil.index).getOrThrow()
                    session.selectSubtitleTrack(SubtitleSelection.Index(spanish.index, enable = true)).getOrThrow()
                    suspend fun matchingTracks() = withTimeout(8_000) {
                        var current = session.refreshTracks()
                        while (com.fourseveneightnine.tv.client.playback.canonicalTrackLanguage(current.currentAudio?.language) != "ta" ||
                            com.fourseveneightnine.tv.client.playback.canonicalTrackLanguage(current.currentSubtitle?.language) != "es") {
                            delay(100)
                            current = session.refreshTracks()
                        }
                        current
                    }
                    matchingTracks()
                    withContext(Dispatchers.IO) { java.net.URL("$fixtureBase/handoff-check/reset").readText() }
                    val start = SystemClock.elapsedRealtime()
                    assertEquals(com.fourseveneightnine.tv.client.playback.PlayResult.Opened,
                        withTimeout(14_000) { client.playFlow.playNextUp() })
                    android.util.Log.i("4789SourceTest", "verified1080pHandoffMs=${SystemClock.elapsedRealtime() - start}")
                    val check = withContext(Dispatchers.IO) { org.json.JSONObject(java.net.URL("$fixtureBase/handoff-check/state").readText()) }
                    assertTrue(check.getBoolean("waiting") && !check.getBoolean("released"))
                    assertEquals(2, client.playFlow.current.value?.episode)
                    assertEquals("1080p", client.playFlow.activeSource.value?.quality)
                    val inherited = matchingTracks()
                    assertTrue(tamil.index != inherited.currentAudio?.index)
                    assertTrue(spanish.index != inherited.currentSubtitle?.index)
                    assertTrue(requireNotNull(session.lastOpenMedia()).url.endsWith("fast-episode=2"))
                } finally {
                    withContext(Dispatchers.IO) { java.net.URL("$fixtureBase/handoff-check/release").readText() }
                    session.stop(); client.playFlow.clearCurrent()
                }
            }
        }
    }

    @Test fun localNextEpisodeUsesAnAlreadySharedPhoneOnlyEpisodeSeven() {
        val context = instrumentation.targetContext
        val graph = context.appGraph
        val client = context.clientGraph
        val session = graph.playback
        val key = ByteArray(32) { 37 }
        val receiver = graph.advertiser.receiverUuid
        val id = "fixture:nextup-phone-${SystemClock.uptimeMillis()}"
        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking {
                withTimeout(15_000) { session.surfaceReadyState.first { it } }
                val raw = """{"format":"4789-settings","version":1,"sources":[{"name":"Fixture","url":"$fixtureBase/manifest.json","enabled":true}]}"""
                val settings = TVSettingsBackupPolicy.validate(raw.encodeToByteArray())
                check(graph.settingsStore.save(settings, 1, key))
                suspend fun publish(episode: Int, revision: Long) {
                    val bytes = withContext(Dispatchers.IO) { org.json.JSONObject(java.net.URL("$fixtureBase/ownership/state").readText()).getLong("bytes") }
                    val batch = com.fourseveneightnine.tv.settings.TVPhoneSources(type = "series", imdbID = "tt9999997",
                        title = "Next Up fixture", season = 1, episode = episode,
                        rows = listOf(com.fourseveneightnine.tv.settings.TVPhoneSource("Phone fixture", "Next.Up.Fixture.S01E0$episode.1080p.ENG.mp4",
                            url = "$fixtureBase/clip.mp4?phone-episode=$episode", quality = "1080p", sizeBytes = bytes, cached = true, service = "TorBox")))
                    val json = kotlinx.serialization.json.Json { encodeDefaults = true }
                    val body = com.fourseveneightnine.tv.settings.SettingsPairingCrypto.encryptForTest(
                        json.encodeToString(com.fourseveneightnine.tv.settings.TVPhoneSources.serializer(), batch).encodeToByteArray(),
                        key, "4789-settings-sync-v2|$receiver|$revision", revision, version = 2)
                    assertEquals(200, graph.pairing.applySync(receiver, body).statusCode)
                }
                try {
                    val services = client.services.filterNotNull().first()
                    val addon = services.registry.streamAddons().first { it.manifestURL.startsWith(fixtureBase) }
                    val first = services.client.streams(addon, "series", "$id:1:6").single()
                    assertEquals(com.fourseveneightnine.tv.client.playback.PlayResult.Opened,
                        client.playFlow.playRow(first, PlayRequest("series", id, "Next Up fixture", 1, 6)))
                    withTimeout(15_000) { session.phase.first { it == ReceiverPlaybackPhase.Playing } }
                    withTimeout(15_000) { client.playFlow.nextUp.first { it?.episode == 7 } }
                    publish(6, 2)
                    check(services.client.streams(addon, "series", "$id:1:7").isEmpty())
                    assertEquals(com.fourseveneightnine.tv.client.playback.PlayResult.ShowList, client.playFlow.playNextUp())
                    assertEquals(6, client.playFlow.current.value?.episode)
                    publish(7, 3)
                    assertEquals(com.fourseveneightnine.tv.client.playback.PlayResult.Opened, client.playFlow.playNextUp())
                    assertEquals("Phone fixture", client.playFlow.activeSource.value?.addonName)
                    assertEquals(7, client.playFlow.current.value?.episode)
                    assertTrue(requireNotNull(session.lastOpenMedia()).url.endsWith("phone-episode=7"))
                } finally {
                    session.stop(); client.playFlow.clearCurrent()
                    graph.settingsStore.save(settings, 1, null)
                }
            }
        }
    }

    @Test fun missing4KMatchFallsBackToVerified720pAfterThreeSeconds() = exerciseHandoffBudget(lowerCopy = true)
    @Test fun noPlayableEpisodeOpensSourcesAtTheThirtySecondDeadline() = exerciseHandoffBudget(lowerCopy = false)

    private fun exerciseHandoffBudget(lowerCopy: Boolean) {
        val context = instrumentation.targetContext
        val client = context.clientGraph
        val session = context.appGraph.playback
        val id = "fixture:nextup-fast-${if (lowerCopy) "lower" else "timeout"}-${SystemClock.uptimeMillis()}"
        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking {
                withTimeout(15_000) { session.surfaceReadyState.first { it } }
                try {
                    val services = client.services.filterNotNull().first()
                    val addon = services.registry.streamAddons().first { it.manifestURL.startsWith(fixtureBase) }
                    val row = services.client.streams(addon, "series", "$id:1:1").single()
                    assertEquals(com.fourseveneightnine.tv.client.playback.PlayResult.Opened,
                        client.playFlow.playRow(row, PlayRequest("series", id, "Next Up fixture", 1, 1)))
                    withTimeout(15_000) { session.phase.first { it == ReceiverPlaybackPhase.Playing } }
                    if (lowerCopy) {
                        withTimeout(10_000) { session.diagnosticsState.first { it.width == 3840 && it.height == 2160 } }
                    }
                    withTimeout(10_000) { client.playFlow.nextUp.first { it?.episode == 2 } }
                    withContext(Dispatchers.IO) { java.net.URL("$fixtureBase/handoff-check/reset").readText() }
                    val start = SystemClock.elapsedRealtime()
                    val result = withTimeout(34_000) { client.playFlow.playNextUp() }
                    val elapsed = SystemClock.elapsedRealtime() - start
                    android.util.Log.i("4789SourceTest", "lowerCopy=$lowerCopy elapsedMs=$elapsed")
                    if (lowerCopy) {
                        assertEquals(com.fourseveneightnine.tv.client.playback.PlayResult.Opened, result)
                        assertEquals("720p", client.playFlow.activeSource.value?.quality)
                        assertTrue("Fallback took $elapsed ms", elapsed in 2_800..13_000)
                    } else {
                        assertEquals(com.fourseveneightnine.tv.client.playback.PlayResult.ShowList, result)
                        assertEquals(2, client.playFlow.sourcesRequest.value?.episode)
                        assertTrue("Timeout took $elapsed ms", elapsed in 28_500..33_000)
                        assertTrue(requireNotNull(session.lastOpenMedia()).url.endsWith("fast-episode=1"))
                    }
                } finally {
                    withContext(Dispatchers.IO) { java.net.URL("$fixtureBase/handoff-check/release").readText() }
                    session.stop(); client.playFlow.clearCurrent()
                }
            }
        }
    }

    @Test fun backAfterNextEpisodeAcceptanceLeavesAStoppedOrTrackedRun() =
        acceptedNextEpisodeVerification("back")

    @Test fun cancelledAcceptedNextEpisodeCannotTakeOverANewerPhone() =
        acceptedNextEpisodeVerification("phone")

    @Test fun pausingTheAcceptedNextEpisodePreservesItsSourceAndProgressOwner() =
        acceptedNextEpisodeVerification("pause")

    @Test fun timedOutNextEpisodeCannotBecomeAnUntrackedLatePlayback() =
        acceptedNextEpisodeVerification("timeout")

    @Test fun timedOutCancelledNextEpisodePreservesANewerLocalPreparation() =
        acceptedNextEpisodeVerification("new-local")

    private fun acceptedNextEpisodeVerification(mode: String) {
        val context = instrumentation.targetContext
        val client = context.clientGraph
        val flow = client.playFlow
        val session = context.appGraph.playback
        val prefs = context.appGraph.presentationPreferences
        val hadAuto = prefs.contains(SettingsKeys.AUTO_NEXT)
        val oldAuto = prefs.getBoolean(SettingsKeys.AUTO_NEXT, true)
        check(prefs.edit().putBoolean(SettingsKeys.AUTO_NEXT, false).commit())
        val device = UiDevice.getInstance(instrumentation)
        val suffix = SystemClock.uptimeMillis()
        val id = "fixture:nextup-verification-$mode-$suffix"
        val newerId = "fixture:ownership-blocked-after-nextup-$suffix"
        try {
            runBlocking { session.stop().getOrThrow() }
            instrumentation.runOnMainSync { flow.clearCurrent() }
            ActivityScenario.launch(MainActivity::class.java).use {
                runBlocking {
                    var acceptedCaller: Job? = null
                    var pauseObserver: Job? = null
                    var newerLocal: kotlinx.coroutines.Deferred<com.fourseveneightnine.tv.client.playback.PlayResult>? = null
                    try {
                        withTimeout(15_000) { session.surfaceReadyState.first { ready -> ready } }
                        withTimeout(15_000) { client.profileSession.state.first { state -> state is ProfileSessionState.Unlocked } }
                        val profile = client.activeProfileId()
                        val library = client.activeLibrary
                        val services = withTimeout(15_000) { client.services.filterNotNull().first() }
                        checkNotNull(services.meta.meta("series", id))
                        if (mode == "new-local") checkNotNull(services.meta.meta("movie", newerId))
                        ownershipFixture("release")
                        ownershipFixture("nextup-player-reset")
                        val size = ownershipFixture("state").getLong("bytes")
                        assertEquals(com.fourseveneightnine.tv.client.playback.PlayResult.Opened,
                            flow.playRow(StreamRow("verification-first-$suffix", "Next Up fixture", "Next Up fixture",
                                "Next.Up.Fixture.S01E01.720p.ENG.mp4", url = "$fixtureBase/nextup-1.mp4", sizeBytes = size),
                                PlayRequest("series", id, "Next Up fixture", 1, 1)))
                        withTimeout(15_000) { session.phase.first { phase -> phase == ReceiverPlaybackPhase.Playing } }
                        withTimeout(10_000) { flow.nextUp.first { plan -> plan?.episode == 2 } }
                        session.seek(SeekCommand.AbsoluteSeconds(session.refreshSnapshot().durationSeconds - 0.5)).getOrThrow()
                        withTimeout(10_000) { session.phase.first { phase -> phase == ReceiverPlaybackPhase.Ended } }
                        val previousGeneration = session.generation
                        val existingChildren = withContext(Dispatchers.Main) {
                            checkNotNull(session.uiScope.coroutineContext[Job]).children.toSet()
                        }
                        assertTrue("The real Next Up Play now control must own focus",
                            device.wait(Until.hasObject(By.focused(true).hasDescendant(By.desc("Play now"))), 5_000) ||
                                device.hasObject(By.focused(true).desc("Play now")))
                        val playPressedAt = SystemClock.elapsedRealtime()
                        assertTrue(device.pressDPadCenter())
                        // Admission HEAD/Range GETs pass. Only the real player's unbounded GET waits.
                        withTimeout(10_000) {
                            while (session.generation == previousGeneration ||
                                !ownershipFixture("state").optBoolean("nextupPlayerWaiting")) delay(25)
                        }
                        val acceptedGeneration = session.generation
                        assertEquals(previousGeneration + 1, acceptedGeneration)
                        assertEquals(2, flow.current.value?.episode)
                        assertNull("No accepted-run source is published before verification", flow.activeSource.value)
                        assertEquals(PlaybackSession.Origin.Local, session.origin.value)
                        assertTrue(requireNotNull(session.lastOpenMedia()).url.endsWith("/nextup-2.mp4"))
                        acceptedCaller = withContext(Dispatchers.Main) {
                            checkNotNull(session.uiScope.coroutineContext[Job]).children.single { child -> child !in existingChildren }
                        }
                        assertFalse(checkNotNull(acceptedCaller).isCompleted)

                        if (mode == "timeout" || mode == "new-local") {
                            val heldAt = SystemClock.elapsedRealtime()
                            if (mode == "new-local") {
                                // Start this lookup late enough to keep its network deadline
                                // separate from the remaining verification wait.
                                delay(8_000)
                                assertFalse(checkNotNull(acceptedCaller).isCompleted)
                                ownershipFixture("reset")
                                newerLocal = async(Dispatchers.Default) {
                                    flow.play(PlayRequest("movie", newerId, "Ownership fixture"))
                                }
                                withTimeout(10_000) {
                                    while (!ownershipFixture("state").optBoolean("waiting") ||
                                        flow.preparing.value?.id != newerId) delay(25)
                                }
                                assertFalse(checkNotNull(newerLocal).isCompleted)
                                assertEquals(acceptedGeneration, session.generation)
                                // Cancel only the captured E2 UI caller. Native Back here would
                                // correctly belong to the newer request's Loading surface.
                                checkNotNull(acceptedCaller).cancel()
                                assertTrue(checkNotNull(acceptedCaller).isCancelled)
                            }
                            // The ten-second attempt includes resolution before the player's GET is held.
                            while (SystemClock.elapsedRealtime() - playPressedAt < 10_000) {
                                if (mode == "timeout") {
                                    assertFalse("The healthy held source must reach the verification timeout, not fail early",
                                        checkNotNull(acceptedCaller).isCompleted)
                                } else assertFalse(checkNotNull(newerLocal).isCompleted)
                                assertFalse(ownershipFixture("state").optBoolean("nextupPlayerReleased"))
                                delay(100)
                            }
                            // Keep the GET held beyond the real15s verifier bound. A cancelled
                            // accepted tail may still be finishing in its NonCancellable region.
                            while (SystemClock.elapsedRealtime() - heldAt < 16_500) {
                                if (mode == "new-local") {
                                    assertFalse(checkNotNull(newerLocal).isCompleted)
                                    assertEquals("Discarding E2 must not invalidate the newer prepared intent",
                                        acceptedGeneration, session.generation)
                                }
                                assertFalse(ownershipFixture("state").optBoolean("nextupPlayerReleased"))
                                delay(100)
                            }
                            withTimeout(5_000) { checkNotNull(acceptedCaller).join() }
                            assertFalse(ownershipFixture("state").optBoolean("nextupPlayerReleased"))
                            ownershipFixture("nextup-player-release")
                            if (mode == "new-local") {
                                assertEquals(acceptedGeneration, session.generation)
                                assertFalse(checkNotNull(newerLocal).isCompleted)
                                assertEquals(newerId, flow.preparing.value?.id)
                                ownershipFixture("release")
                                assertEquals(com.fourseveneightnine.tv.client.playback.PlayResult.Opened,
                                    withTimeout(25_000) { checkNotNull(newerLocal).await() })
                                withTimeout(15_000) { session.phase.first { phase -> phase == ReceiverPlaybackPhase.Playing } }
                                assertEquals(acceptedGeneration + 1, session.generation)
                                assertEquals(PlaybackSession.Origin.Local, session.origin.value)
                                assertEquals(newerId, flow.current.value?.id)
                                assertEquals("Ownership fixture", session.lastOpenMedia()?.title)
                                assertTrue(requireNotNull(session.lastOpenMedia()).url.contains("/ownership.mp4"))
                                assertTrue(flow.activeSource.value != null)
                            } else if (session.refreshSnapshot().active) {
                                withTimeout(15_000) { session.phase.first { phase -> phase == ReceiverPlaybackPhase.Playing } }
                                withTimeout(20_000) { library.playbackHistory().first { rows ->
                                    rows.any { row -> row.canonicalId == id && row.episode == 2 && row.positionMs > 0 }
                                } }
                                assertEquals(acceptedGeneration, session.generation)
                                assertEquals(PlaybackSession.Origin.Local, session.origin.value)
                            } else {
                                assertNull("A timed-out untracked candidate must be retired before late delivery", session.origin.value)
                                assertTrue(session.phase.value == ReceiverPlaybackPhase.Stopped || session.phase.value == ReceiverPlaybackPhase.Idle)
                            }
                            assertEquals(profile, client.activeProfileId())
                        } else if (mode == "pause") {
                            pauseObserver = launch(Dispatchers.Main.immediate, start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                                session.phase.first { phase -> session.generation == acceptedGeneration && phase == ReceiverPlaybackPhase.Playing }
                                assertNull("Pause must enter before the verification publishes a source", flow.activeSource.value)
                                session.setSpeed(0).getOrThrow()
                                session.phase.first { phase -> phase == ReceiverPlaybackPhase.Paused }
                            }
                            ownershipFixture("nextup-player-release")
                            withTimeout(10_000) { checkNotNull(pauseObserver).join() }
                            val pausedAt = SystemClock.elapsedRealtime()
                            withTimeout(20_000) { checkNotNull(acceptedCaller).join() }
                            assertEquals(acceptedGeneration, session.generation)
                            assertEquals(2, flow.current.value?.episode)
                            assertTrue("A healthy paused accepted episode must finish bookkeeping", flow.activeSource.value != null)
                            while (SystemClock.elapsedRealtime() - pausedAt < 16_000) {
                                assertEquals(acceptedGeneration, session.generation)
                                assertEquals(ReceiverPlaybackPhase.Paused, session.phase.value)
                                assertEquals(0, session.refreshSnapshot().speed)
                                delay(250)
                            }
                            // Explicit Play, then a real periodic Room write; no direct recorder call or Stop.
                            session.setSpeed(1).getOrThrow()
                            withTimeout(5_000) { session.phase.first { phase -> phase == ReceiverPlaybackPhase.Playing } }
                            withTimeout(20_000) { library.playbackHistory().first { rows ->
                                rows.any { row -> row.canonicalId == id && row.episode == 2 && row.positionMs > 0 }
                            } }
                            assertEquals(profile, client.activeProfileId())
                        } else {
                            assertTrue("The accepted episode's Loading surface must own Back",
                                device.wait(Until.hasObject(By.focused(true).hasDescendant(By.desc("Cancel"))), 5_000))
                            val marker = "test.nextup.accepted.back.$suffix"
                            ReceiverDiagnostics.record(marker)
                            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                            withTimeout(3_000) { while (!checkNotNull(acceptedCaller).isCancelled) delay(25) }
                            assertFalse("Exercise Loading Back, not the outer stop-and-leave handler",
                                ReceiverDiagnostics.read().substringAfterLast(marker).contains("player.back"))
                            var phoneGeneration: Long? = null
                            val phoneTitle = "Phone owns accepted Next Up"
                            val phoneCast = "nextup-accepted-phone-$suffix"
                            if (mode == "phone") {
                                NowPlayingArtwork.stage(landscapeURL = "$fixtureBase/new.png", posterURL = null,
                                    logoURL = "$fixtureBase/series-logo.png", owner = phoneCast)
                                session.open(OpenMediaRequest("$fixtureBase/clip.mp4", title = phoneTitle,
                                    castId = phoneCast, startPositionMs = 30_000)).getOrThrow()
                                phoneGeneration = session.generation
                                assertTrue(checkNotNull(phoneGeneration) > acceptedGeneration)
                            }
                            ownershipFixture("nextup-player-release")
                            withTimeout(25_000) { checkNotNull(acceptedCaller).join() }
                            if (mode == "phone") {
                                withTimeout(15_000) { session.phase.first { phase -> phase == ReceiverPlaybackPhase.Playing } }
                                assertEquals(phoneGeneration, session.generation)
                                assertEquals(PlaybackSession.Origin.Phone, session.origin.value)
                                assertEquals(phoneTitle, session.lastOpenMedia()?.title)
                                assertEquals("$fixtureBase/clip.mp4", session.lastOpenMedia()?.url)
                                assertEquals("$fixtureBase/series-logo.png", NowPlayingArtwork.forPlayback(checkNotNull(phoneGeneration))?.logoURL)
                                assertFalse(library.playbackHistory().first().any { row -> row.canonicalId == id && row.episode == 2 && row.positionMs > 0 })
                            } else if (session.refreshSnapshot().active) {
                                withTimeout(15_000) { session.phase.first { phase -> phase == ReceiverPlaybackPhase.Playing } }
                                assertEquals(acceptedGeneration, session.generation)
                                assertEquals(PlaybackSession.Origin.Local, session.origin.value)
                                withTimeout(20_000) { library.playbackHistory().first { rows ->
                                    rows.any { row -> row.canonicalId == id && row.episode == 2 && row.positionMs > 0 }
                                } }
                                assertEquals(profile, client.activeProfileId())
                            } else {
                                assertNull("An untracked cancelled candidate must actually be stopped", session.origin.value)
                                assertTrue(session.phase.value == ReceiverPlaybackPhase.Stopped || session.phase.value == ReceiverPlaybackPhase.Idle)
                            }
                        }
                    } finally {
                        runCatching { ownershipFixture("nextup-player-release") }
                        runCatching { ownershipFixture("release") }
                        withContext(NonCancellable) {
                            pauseObserver?.cancelAndJoin()
                            withTimeout(25_000) { acceptedCaller?.cancelAndJoin(); newerLocal?.cancelAndJoin() }
                            session.stop()
                            instrumentation.runOnMainSync { flow.clearCurrent() }
                        }
                    }
                }
            }
        } finally {
            val edit = prefs.edit()
            if (hadAuto) edit.putBoolean(SettingsKeys.AUTO_NEXT, oldAuto) else edit.remove(SettingsKeys.AUTO_NEXT)
            check(edit.commit())
        }
    }

    @Test fun nextEpisodeMatchesThePlayingAddonGroupAndSkipsAnExpiredDelivery() {
        val context = instrumentation.targetContext
        val client = context.clientGraph
        val session = context.appGraph.playback
        val id = "fixture:nextup-binge-${SystemClock.uptimeMillis()}"
        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking {
                try {
                    withTimeout(15_000) { session.surfaceReadyState.first { it } }
                    withTimeout(15_000) { client.profileSession.state.first { it is ProfileSessionState.Unlocked } }
                    val services = client.services.filterNotNull().first()
                    val addon = services.registry.streamAddons().first { it.manifestURL.startsWith(fixtureBase) }
                    val first = services.client.streams(addon, "series", "$id:1:1").single()
                    assertEquals("preferred", first.bingeGroup)
                    assertEquals(com.fourseveneightnine.tv.client.playback.PlayResult.Opened,
                        client.playFlow.playRow(first, PlayRequest("series", id, "Next Up fixture", 1, 1)))
                    withTimeout(15_000) { session.phase.first { it == ReceiverPlaybackPhase.Playing } }
                    withTimeout(10_000) { client.playFlow.nextUp.first { it?.episode == 2 } }
                    session.seek(SeekCommand.AbsoluteSeconds(575.0)).getOrThrow()
                    withTimeout(5_000) { while (session.refreshSnapshot().positionSeconds < 574) delay(100) }
                    session.setSpeed(0).getOrThrow()
                    val library = client.activeLibrary
                    withTimeout(8_000) { library.continueWatching().first { rows ->
                        rows.any { it.canonicalId == id && it.episode == 2 && it.positionMs == 0L }
                    } }
                    assertEquals(com.fourseveneightnine.tv.client.playback.PlayResult.Opened,
                        withTimeout(45_000) { client.playFlow.playNextUp() })
                    assertEquals("preferred", client.playFlow.activeSource.value?.bingeGroup)
                    assertTrue(requireNotNull(session.lastOpenMedia()).url.endsWith("route=matched"))
                    assertEquals(2, client.playFlow.current.value?.episode)
                    assertTrue(session.refreshSnapshot().positionSeconds > 0.0)
                    val selectedUrl = requireNotNull(session.lastOpenMedia()).url
                    session.stop().getOrThrow()
                    client.playFlow.clearCurrent()
                    // A fresh playback flow has no previous row or prepared URL in memory.
                    val fresh = com.fourseveneightnine.tv.client.playback.DefaultPlayFlow(client, context.appGraph)
                    try {
                        val card = library.continueWatching().first().single { it.canonicalId == id }
                        assertEquals(2, card.episode)
                        assertEquals(com.fourseveneightnine.tv.client.playback.PlayResult.Opened,
                            fresh.play(PlayRequest(card.mediaType, id, card.title, card.season, card.episode,
                                resumeFromMs = card.positionMs)))
                        assertEquals("Last played", fresh.activeSource.value?.addonName)
                        assertEquals("Resume must retain the exact selected delivery", selectedUrl,
                            requireNotNull(session.lastOpenMedia()).url)
                        assertTrue(requireNotNull(session.lastOpenMedia()).url.endsWith("route=matched"))
                    } finally { fresh.clearCurrent() }
                } finally { session.stop(); client.playFlow.clearCurrent() }
            }
        }
    }

    @Test fun tamilMvAutoplayUsesTheManualEpisodeFiveFileAndPreservesThePendingCard() {
        val context = instrumentation.targetContext
        val client = context.clientGraph
        val session = context.appGraph.playback
        val device = UiDevice.getInstance(instrumentation)
        val packHash = "a".repeat(40)
        val id = "saved:tamilmv:part-timers-test-${SystemClock.uptimeMillis()}"
        val pack = CatalogItem(id, "series", "Part-Timers", season = 1, playbackSources = listOf(
            CatalogPlaybackSource(packHash, "Part-Timers S01", torrentID = 73, season = 1)))
        val preferences = context.appGraph.presentationPreferences
        val previousAuto = preferences.getBoolean(SettingsKeys.AUTO_NEXT, true)
        check(preferences.edit().putBoolean(SettingsKeys.AUTO_NEXT, true).commit())
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                runBlocking {
                    withTimeout(15_000) { session.surfaceReadyState.first { it } }
                    withTimeout(15_000) { client.profileSession.state.first { it is ProfileSessionState.Unlocked } }
                    val original = requireNotNull(client.services.filterNotNull().first())
                    // Test-only provider injection. No account or external torrent service is contacted.
                    val field = client.javaClass.getDeclaredField("mutableServices").apply { isAccessible = true }
                    @Suppress("UNCHECKED_CAST")
                    val services = field.get(client) as kotlinx.coroutines.flow.MutableStateFlow<com.fourseveneightnine.tv.client.ClientGraph.DataServices?>
                    val debrid = com.fourseveneightnine.tv.client.data.streams.debrid.DebridResolver(
                        "fixture", null, client.okHttp, torboxBaseURL = "$fixtureBase/pack")
                    val oldItems = client.snapshots.shelves().value.firstOrNull { it.id == "tamilmv:receiver" }?.items.orEmpty()
                    try {
                        services.value = com.fourseveneightnine.tv.client.ClientGraph.DataServices(original.document,
                            original.health, original.client, original.registry, original.meta, original.ratings, original.search, debrid)
                        client.snapshots.setReceiverTamilMV(listOf(pack), System.currentTimeMillis())
                        val inventory = debrid.torboxFiles(listOf(packHash))
                        val manual = com.fourseveneightnine.tv.client.playback.tamilMVEpisodeFiles(pack, inventory)
                        val fourth = manual.single { it.episode == 4 }.stream
                        val fifth = manual.single { it.episode == 5 }.stream
                        val request = PlayRequest("series", id, pack.title, 1, 4, backdropUrl = "$fixtureBase/old.png", resumeFromMs = 580_000)
                        assertEquals(com.fourseveneightnine.tv.client.playback.PlayResult.Opened, client.playFlow.playRow(fourth, request))
                        withTimeout(15_000) { session.phase.first { it == ReceiverPlaybackPhase.Playing } }
                        withTimeout(15_000) { client.playFlow.nextUp.first { it?.episode == 5 } }
                        // The visible catalog can refresh while the viewer is still watching E04.
                        client.snapshots.setReceiverTamilMV(emptyList(), System.currentTimeMillis())
                        val duration = session.refreshSnapshot().durationSeconds
                        assertTrue(duration > 590)
                        session.seek(SeekCommand.AbsoluteSeconds(duration - 11)).getOrThrow()
                        assertTrue("The prompt appears with hidden controls before EOF",
                            device.wait(Until.hasObject(By.text("Keep watching")), 8_000))
                        session.setSpeed(0).getOrThrow()
                        session.seek(SeekCommand.AbsoluteSeconds(duration - 8)).getOrThrow()
                        withTimeout(6_000) {
                            do {
                                delay(200)
                                if (Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
                            } while (!device.hasObject(By.descStartsWith("Next episode in ")))
                        }
                        val library = client.activeLibrary
                        val pending = withTimeout(6_000) {
                            library.continueWatching().first { rows -> rows.any { row -> row.canonicalId == id && row.episode == 5 } }
                        }.single { it.canonicalId == id }
                        assertEquals(0L, pending.positionMs)
                        assertEquals(4, client.playFlow.current.value?.episode)
                        val shots = File(context.getExternalFilesDir(null), "remote-checks").apply { mkdirs() }
                        assertTrue(device.takeScreenshot(File(shots, "part-timers-next-up.png")))
                        session.seek(SeekCommand.AbsoluteSeconds(duration - 40)).getOrThrow()
                        withTimeout(6_000) { while (session.refreshSnapshot().positionSeconds >= duration * 0.98) delay(100) }
                        withTimeout(6_000) {
                            do {
                                delay(200)
                                if (Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
                            } while (device.hasObject(By.text("Keep watching")))
                        }
                        session.seek(SeekCommand.AbsoluteSeconds(duration - 6)).getOrThrow()
                        session.setSpeed(1).getOrThrow()
                        withTimeout(30_000) { client.playFlow.activeSource.first {
                            it?.infoHash == fifth.infoHash && it?.torboxFileID == fifth.torboxFileID
                        } }
                        assertEquals(5, client.playFlow.current.value?.episode)
                        assertEquals(17, fifth.torboxFileID)
                        assertTrue(requireNotNull(session.lastOpenMedia()).url.endsWith("pack-file=17"))
                        withTimeout(5_000) { client.playFlow.nextUp.first { it?.episode == 6 } }
                        assertTrue(session.refreshSnapshot().positionSeconds > 0)
                        session.stop().getOrThrow()
                        val resumed = library.continueWatching().first().single { it.canonicalId == id }
                        assertEquals(5, resumed.episode)
                        assertTrue(resumed.positionMs > 0)
                    } finally {
                        session.stop()
                        client.playFlow.clearCurrent()
                        client.snapshots.setReceiverTamilMV(oldItems, System.currentTimeMillis())
                        services.value = original
                    }
                }
            }
        } finally { preferences.edit().putBoolean(SettingsKeys.AUTO_NEXT, previousAuto).commit() }
    }

    @Test fun backCancelsExpiredNextUpWithoutStartingAnotherSourceLookup() {
        val context = instrumentation.targetContext
        val client = context.clientGraph
        val session = context.appGraph.playback
        val preferences = context.appGraph.presentationPreferences
        val previous = preferences.getBoolean(SettingsKeys.AUTO_NEXT, true)
        val hadPreference = preferences.contains(SettingsKeys.AUTO_NEXT)
        check(preferences.edit().putBoolean(SettingsKeys.AUTO_NEXT, true).commit())
        val device = UiDevice.getInstance(instrumentation)
        val suffix = SystemClock.uptimeMillis()
        val id = "fixture:nextup-$suffix"
        try {
            runBlocking { session.stop().getOrThrow() }
            instrumentation.runOnMainSync { client.playFlow.clearCurrent() }
            ActivityScenario.launch(MainActivity::class.java).use {
                runBlocking {
                    try {
                        withTimeout(15_000) { session.surfaceReadyState.first { it } }
                        withTimeout(15_000) { client.profileSession.state.first { it is ProfileSessionState.Unlocked } }
                        val services = withTimeout(15_000) { client.services.filterNotNull().first() }
                        val meta = checkNotNull(services.meta.meta("series", id))
                        assertEquals(listOf(1, 2), meta.videos.map { it.episode })
                        ownershipFixture("reset")
                        val size = ownershipFixture("state").getLong("bytes")
                        assertEquals(com.fourseveneightnine.tv.client.playback.PlayResult.Opened,
                            client.playFlow.playRow(StreamRow("nextup-first-$suffix", "Next Up fixture",
                                "Next Up fixture", "Next.Up.Fixture.S01E01.720p.ENG.mp4",
                                url = "$fixtureBase/nextup-1.mp4", sizeBytes = size),
                                PlayRequest("series", id, "Next Up fixture", season = 1, episode = 1,
                                    resumeFromMs = 120_000)))
                        withTimeout(15_000) { session.phase.first { it is ReceiverPlaybackPhase.Playing } }
                        withTimeout(5_000) { client.playFlow.nextUp.first { it?.episode == 2 } }
                        val duration = session.refreshSnapshot().durationSeconds
                        assertTrue(duration > 550.0)
                        assertEquals(0, ownershipFixture("state").getInt("nextupRequests"))
                        session.seek(SeekCommand.AbsoluteSeconds(duration - 0.5)).getOrThrow()
                        withTimeout(10_000) { session.phase.first { it is ReceiverPlaybackPhase.Ended } }
                        val endedGeneration = session.generation
                        // No key input: let the real NextUpPlate countdown expire by itself.
                        withTimeout(20_000) {
                            while (ownershipFixture("state").getInt("nextupRequests") != 1) delay(25)
                        }
                        assertTrue(ownershipFixture("state").getBoolean("waiting"))
                        assertTrue("Cancel must own focus while the next episode is preparing",
                            device.wait(Until.hasObject(By.focused(true).hasDescendant(By.desc("Cancel"))), 5_000))
                        val evidence = File(context.getExternalFilesDir(null), "remote-checks").apply { mkdirs() }
                        assertTrue(device.takeScreenshot(File(evidence, "episode-preparing.png")))
                        val marker = "test.nextup.cancel.$suffix"
                        ReceiverDiagnostics.record(marker)
                        assertTrue("Native Back key injection must succeed", device.pressBack())
                        device.waitForIdle()
                        if (Build.VERSION.SDK_INT >= 34) {
                            assertTrue("Accessibility cache refresh must succeed", instrumentation.uiAutomation.clearCache())
                        }
                        val cancelled = device.wait(Until.hasObject(By.text("Source selection cancelled.")), 5_000)
                        if (!cancelled) {
                            val folder = File(context.getExternalFilesDir(null), "remote-checks").apply { mkdirs() }
                            device.takeScreenshot(File(folder, "nextup-cancel-failure.png"))
                            device.dumpWindowHierarchy(File(folder, "nextup-cancel-failure.xml"))
                        }
                        assertTrue("The loading Back handler must show cancellation", cancelled)
                        assertTrue(device.wait(Until.hasObject(By.text("Try again")), 5_000))
                        assertTrue(device.takeScreenshot(File(evidence, "episode-retry.png")))
                        val report = ReceiverDiagnostics.read()
                        assertTrue(report.contains(marker))
                        assertFalse("Exercise the loading Back handler, not the outer fallback",
                            report.substringAfterLast(marker).contains("player.back"))
                        val quietUntil = SystemClock.elapsedRealtime() + 1_500
                        while (SystemClock.elapsedRealtime() < quietUntil) {
                            assertEquals("A zero-second countdown must stay cancelled", 1,
                                ownershipFixture("state").getInt("nextupRequests"))
                            assertEquals(endedGeneration, session.generation)
                            delay(50)
                        }
                        ownershipFixture("release")
                        withTimeout(5_000) {
                            while (!ownershipFixture("state").getBoolean("released")) delay(25)
                        }
                        delay(250)
                        assertEquals(1, ownershipFixture("state").getInt("nextupRequests"))
                        assertEquals(endedGeneration, session.generation)
                        assertEquals(PlaybackSession.Origin.Local, session.origin.value)
                        assertEquals(1, client.playFlow.current.value?.episode)
                        assertTrue(session.phase.value is ReceiverPlaybackPhase.Ended)
                        assertTrue(device.hasObject(By.text("Try again")))
                    } catch (failure: Throwable) {
                        runCatching {
                            val folder = File(context.getExternalFilesDir(null), "remote-checks").apply { mkdirs() }
                            device.takeScreenshot(File(folder, "nextup-state-failure.png"))
                            device.dumpWindowHierarchy(File(folder, "nextup-state-failure.xml"))
                        }
                        throw failure
                    } finally {
                        runCatching { ownershipFixture("release") }
                        client.playFlow.cancelPreparation()
                        session.stop()
                        instrumentation.runOnMainSync { client.playFlow.clearCurrent() }
                    }
                }
            }
        } finally {
            val edit = preferences.edit()
            if (hadPreference) edit.putBoolean(SettingsKeys.AUTO_NEXT, previous)
            else edit.remove(SettingsKeys.AUTO_NEXT)
            check(edit.commit())
        }
    }

    @Test fun backCancelsLoadingAndLateResponsesCannotRestartIt() {
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking {
                val session = context.appGraph.playback
                withTimeout(15_000) { session.surfaceReadyState.first { ready -> ready } }
                session.open(OpenMediaRequest("$fixtureBase/slow.mp4", title = "Waiting for fixture"),
                    PlaybackSession.Origin.Phone).getOrThrow()
                assertTrue(device.wait(Until.hasObject(By.desc("Waiting for fixture")), 5_000))
                device.pressBack()
                withTimeout(5_000) { session.origin.first { origin -> origin == null } }
                val unexpected = withTimeoutOrNull(7_000) {
                    session.phase.first { phase -> phase is ReceiverPlaybackPhase.Playing }
                }
                assertNull("A cancelled load must not restart when its response arrives", unexpected)
            }
        }
    }

    private fun failedSwitch(liveFirst: Boolean) {
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking {
                val session = context.appGraph.playback
                withTimeout(15_000) { session.surfaceReadyState.first { ready -> ready } }
                val playing = OpenMediaRequest("$fixtureBase/clip.mp4", title = "Previous fixture", isLive = liveFirst)
                if (liveFirst) session.openLiveChannel(playing, PlaybackSession.LiveChannel("old-live", "fixture", "Fixture")).getOrThrow()
                else session.open(playing, PlaybackSession.Origin.Local).getOrThrow()
                withTimeout(15_000) { session.phase.first { phase -> phase is ReceiverPlaybackPhase.Playing } }
                val failureTitle = if (liveFirst) "Current movie failure" else "Current live failure"
                val broken = OpenMediaRequest("$fixtureBase/missing.mp4", title = failureTitle, isLive = !liveFirst)
                if (liveFirst) session.open(broken, PlaybackSession.Origin.Local).getOrThrow()
                else session.openLiveChannel(broken, PlaybackSession.LiveChannel("new-live", "fixture", "Fixture")).getOrThrow()
                withTimeout(20_000) { session.phase.first { phase -> phase is ReceiverPlaybackPhase.Error } }
                assertTrue(device.wait(Until.hasObject(By.text(failureTitle)), 5_000))
                assertFalse(device.hasObject(By.text("Previous fixture")))
                val export = ReceiverDiagnostics.read()
                val journal = export.substringAfter("--- receiver-playback-failures.log ---", "").substringBefore("\n--- ")
                assertTrue("Engine error must survive in the separate failure export",
                    journal.lineSequence().any { line ->
                        "playback.failure.engine" in line && failureTitle in line &&
                            "ERROR_CODE_IO_BAD_HTTP_STATUS" in line && "http=404" in line &&
                            "$fixtureBase/missing.mp4" !in line
                    })
                val folder = File(context.getExternalFilesDir(null), "remote-checks").apply { mkdirs() }
                val screenshot = File(folder, if (liveFirst) "live-to-movie-error.png" else "movie-to-live-error.png")
                check(device.takeScreenshot(screenshot))
                val image = requireNotNull(BitmapFactory.decodeFile(screenshot.path))
                val expected = TvColor.Canvas.toArgb()
                for ((x, y) in listOf(0.03f to 0.03f, 0.97f to 0.03f, 0.03f to 0.97f, 0.97f to 0.97f)) {
                    assertEquals("Previous video must not bleed through", expected,
                        image.getPixel((image.width * x).toInt(), (image.height * y).toInt()))
                }
                session.stop()
            }
        }
    }
}


/** The Hugo screenshot case: metadata is present while image requests fail transiently. */
@RunWith(AndroidJUnit4::class)
class ArtworkRecoveryUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun hugoPosterAndBackdropRecoverAfterTheirFirstImageRequestFails() {
        requireDisposableEmulator()
        val server = java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val counts = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
        fun png(width: Int, height: Int, color: Int): ByteArray {
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            return java.io.ByteArrayOutputStream().use { output ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
                bitmap.recycle()
                output.toByteArray()
            }
        }
        val landscape = png(640, 360, android.graphics.Color.rgb(30, 90, 130))
        val portrait = png(160, 240, android.graphics.Color.rgb(180, 130, 40))
        val worker = kotlin.concurrent.thread(isDaemon = true) {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        val route = reader.readLine()?.split(' ')?.getOrNull(1).orEmpty()
                        while (!reader.readLine().isNullOrEmpty()) { }
                        val attempt = counts.computeIfAbsent(route) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
                        val body = if (attempt == 1) byteArrayOf() else if (route.contains("backdrop")) landscape else portrait
                        val status = if (attempt == 1) "404 Not Found" else "200 OK"
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 $status\r\nContent-Type: image/png\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(body)
                            flush()
                        }
                    }
                } catch (_: java.io.IOException) { if (!server.isClosed) throw AssertionError("Fixture image server failed") }
            }
        }
        try {
            val base = "http://127.0.0.1:${server.localPort}"
            val meta = com.fourseveneightnine.tv.client.data.meta.Meta("tt0970179", "movie", "Hugo",
                poster = "$base/poster.png", backdrop = "$base/backdrop.png")
            val ready = java.util.concurrent.atomic.AtomicBoolean()
            compose.setContent { TvTheme(reduceMotion = true) {
                Box(Modifier.fillMaxSize().background(TvColor.Background)) {
                    com.fourseveneightnine.tv.client.ui.screens.detail.Backdrop(meta,
                        onLandscapeReady = { ready.set(true) }, onGlassArtwork = {}, dimmed = { false })
                    com.fourseveneightnine.tv.client.ui.screens.detail.DetailArtwork(
                        url = meta.poster, targetWidth = 342, fallback = "Hugo",
                        modifier = Modifier.width(160.dp).height(240.dp))
                }
            } }
            compose.waitUntil(15_000) {
                ready.get() && compose.onAllNodesWithText("Hugo").fetchSemanticsNodes().isEmpty()
            }
            assertEquals(2, counts["/poster.png"]?.get())
            assertEquals(2, counts["/backdrop.png"]?.get())
            val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "remote-checks").apply { mkdirs() }
            check(UiDevice.getInstance(instrumentation).takeScreenshot(File(folder, "hugo-artwork-recovery.png")))
        } finally {
            server.close()
            worker.join(2_000)
        }
    }
}

/** Runs private local audio fixtures only; never selects or interrupts a physical receiver. */
@RunWith(AndroidJUnit4::class)
class DtsAudioDecoderPlaybackTest {
    @Before fun setup() = requireDisposableEmulator()

    @Test fun dtsHdTracksUseFfmpegAcrossSelectionSeekAndReopen() = runBlocking {
        val context = instrumentation.targetContext
        val controller = withContext(Dispatchers.Main) { ExoReceiverController(context) }
        val marker = "test.dts.audio.${SystemClock.uptimeMillis()}"
        ReceiverDiagnostics.record(marker)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var surface: SurfaceView? = null
            val listener = object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) = controller.attachSurfaceHolder(holder)
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
                    controller.attachSurfaceHolder(holder)
                override fun surfaceDestroyed(holder: SurfaceHolder) = controller.detachSurface()
            }
            scenario.onActivity { activity ->
                surface = SurfaceView(activity).also {
                    it.holder.addCallback(listener)
                    activity.addContentView(it, ViewGroup.LayoutParams(320, 180))
                }
            }
            try {
                withTimeout(15_000) { controller.surfaceReady.first { it } }
                controller.applyAudioProfile(com.fourseveneightnine.tv.protocol.ReceiverAudioProfile.STEREO).getOrThrow()
                val request = OpenMediaRequest("$fixtureBase/dts-hd-tracks.mkv", title = "DTS-HD sample fixture")
                controller.open(request).getOrThrow()
                withTimeout(15_000) { controller.playbackPhase.first { it is ReceiverPlaybackPhase.Playing } }
                assertEquals(listOf(6, 6, 8), controller.tracks().getOrThrow().audio.map { it.channels })
                for (index in listOf(0, 1, 2, 0)) {
                    controller.selectAudio(index).getOrThrow()
                    withTimeout(10_000) {
                        while (controller.tracks().getOrThrow().currentAudio?.index != index) delay(25)
                    }
                    controller.seek(SeekCommand.AbsoluteSeconds(4.0)).getOrThrow()
                    withTimeout(10_000) {
                        while (controller.snapshot().positionSeconds < 4.5) delay(25)
                    }
                    assertEquals(index, controller.tracks().getOrThrow().currentAudio?.index)
                }
                // Same-title public reopen must retain the selected track and still decode it.
                controller.open(request.copy(startPositionMs = 6_000)).getOrThrow()
                withTimeout(10_000) {
                    while (controller.snapshot().positionSeconds < 6.5) delay(25)
                }
                assertEquals(0, controller.tracks().getOrThrow().currentAudio?.index)
                val report = ReceiverDiagnostics.read().substringAfter(marker)
                assertTrue("The native FFmpeg DTS decoder must initialize: $report",
                    Regex("exo\\.decoder\\.audio \\| ffmpeg[^\\n]*dca").containsMatchIn(report))
                assertFalse(report.contains("exoplayer.error"))
                assertFalse(report.contains("exo.audio.sinkError"))
                val folder = File(context.getExternalFilesDir(null), "audio-checks").apply { mkdirs() }
                File(folder, "dts-hd-decoder.log").writeText(report)
            } finally {
                controller.stop()
                withContext(Dispatchers.Main) {
                    surface?.holder?.removeCallback(listener)
                    controller.detachSurface()
                    controller.close()
                    (surface?.parent as? ViewGroup)?.removeView(surface)
                }
            }
        }
    }

    @Test fun aacKeepsThePlatformDecoderPriority() = runBlocking {
        val context = instrumentation.targetContext
        val controller = withContext(Dispatchers.Main) { ExoReceiverController(context) }
        val marker = "test.aac.audio.${SystemClock.uptimeMillis()}"
        ReceiverDiagnostics.record(marker)
        try {
            controller.open(OpenMediaRequest("$fixtureBase/aac-control.m4a", title = "AAC control fixture")).getOrThrow()
            withTimeout(15_000) {
                while (controller.snapshot().positionSeconds < 0.5) delay(25)
            }
            val report = ReceiverDiagnostics.read().substringAfter(marker)
            assertTrue("The emulator platform AAC decoder must retain priority: $report",
                Regex("exo\\.decoder\\.audio \\| (c2\\.android\\.aac|OMX\\.google\\.aac)").containsMatchIn(report))
            assertFalse(report.contains("exoplayer.error"))
        } finally {
            controller.stop()
            withContext(Dispatchers.Main) { controller.close() }
        }
    }
}


/** Detail shows its title before artwork and keeps Back available while loading. */
@RunWith(AndroidJUnit4::class)
class DetailArrivalUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun movieShowsTitleBeforeMetadataAndArtwork() = arrival("movie")
    @Test fun seriesDoesNotWaitForOffscreenEpisodeArtwork() = arrival("series")
    @Test fun slowHeroLogoCannotKeepUsableDetailsCovered() = arrival("movie", slowLogo = true)
    @Test fun failedArtworkRevealsOneStableFallback() = arrival("movie", broken = true)
    @Test fun backDuringArtworkLoadingReturnsHome() = arrival("movie", cancel = true)
    @Test fun portraitOnlyDetailKeepsTheCornerClear() = arrival("movie", portraitOnly = true)

    private fun arrival(
        type: String,
        broken: Boolean = false,
        cancel: Boolean = false,
        slowLogo: Boolean = false,
        portraitOnly: Boolean = false,
    ) {
        prepareFixture()
        DetailFixture(type, broken, portraitOnly).use { fixture ->
            val context = instrumentation.targetContext
            val raw = """{"format":"4789-settings","version":1,"sources":[{"name":"Detail arrival fixture","url":"${fixture.base}/manifest.json","enabled":true}]}"""
            runBlocking {
                check(context.appGraph.settingsStore.save(TVSettingsBackupPolicy.validate(raw.encodeToByteArray()), 1L, null))
                context.clientGraph.reloadSettingsNow()
            }
            instrumentation.runOnMainSync {
                com.fourseveneightnine.tv.client.ui.screens.detail.DetailPreview.stage(
                    HomeCard(fixture.id, type, "Catalog preview", posterUrl = "${fixture.base}/poster.png"))
            }
            val uri = requireNotNull(SystemSearchIntentParser.detailUri(context.packageName + ".search", type, fixture.id))
            val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(uri))
            ActivityScenario.launch<MainActivity>(intent).use {
                compose.waitUntil(15_000) { fixture.metadataRequested.get() }
                assertLoading()
                if (!portraitOnly) capture("detail-$type-before-metadata.png")
                fixture.metadata.countDown()
                compose.waitUntil(15_000) { fixture.artRequested.get() }
                if (portraitOnly) {
                    fixture.artwork.countDown()
                    compose.waitUntil(10_000) {
                        runCatching { compose.onNodeWithContentDescription("Play").assertIsFocused() }.isSuccess
                    }
                    compose.mainClock.advanceTimeBy(500)
                    compose.waitForIdle()
                    val pixels = compose.onRoot().captureToImage().toPixelMap()
                    val corner = pixels[pixels.width * 9 / 10, pixels.height * 3 / 20]
                    assertTrue("Details must leave the upper-right corner free of portrait artwork",
                        corner.blue < 0.25f)
                    capture("detail-portrait-only-clear-corner.png")
                    return
                }
                val play = if (type == "series") "Play S1 E1" else "Play"
                compose.waitUntil(5_000) {
                    runCatching { compose.onNodeWithContentDescription(play).assertIsFocused() }.isSuccess
                }
                compose.onNodeWithText("The final detail description.").assertIsDisplayed()
                if (type == "series") capture("detail-series-while-offscreen-still-held.png")
                if (slowLogo) capture("detail-movie-while-logo-held.png")
                capture("detail-$type-before-artwork.png")
                if (cancel) {
                    UiDevice.getInstance(instrumentation).pressBack()
                    compose.waitUntil(10_000) {
                        runCatching { compose.onNodeWithTag(HOME_BROWSE_TAG).assertIsDisplayed() }.isSuccess
                    }
                    fixture.artwork.countDown()
                    compose.waitForIdle()
                    compose.onNodeWithContentDescription("Home").assertDoesNotExist()
                    compose.onAllNodesWithContentDescription("Play").fetchSemanticsNodes().also { assertTrue(it.isEmpty()) }
                    capture("detail-loading-back-home.png")
                    return
                }
                fixture.artwork.countDown()
                compose.waitUntil(20_000) {
                    runCatching { compose.onNodeWithContentDescription(play).assertIsFocused() }.isSuccess
                }
                compose.onAllNodesWithText("Catalog preview").fetchSemanticsNodes().also { assertTrue(it.isEmpty()) }
                compose.onNodeWithContentDescription(play).assertIsDisplayed()
                // Native screenshots can still contain the prior Surface frame after focus is
                // published. Verify the rendered page, including the finished fade, first.
                compose.waitUntil(5_000) {
                    val pixels = compose.onRoot().captureToImage().toPixelMap()
                    if (broken) {
                        val title = compose.onAllNodesWithText("Final detail").fetchSemanticsNodes()
                            .firstOrNull { it.boundsInRoot.left < pixels.width / 2f }?.boundsInRoot
                        title != null && (title.left.toInt() until title.right.toInt() step 4).sumOf { x ->
                            (title.top.toInt() until title.bottom.toInt() step 4).count { y ->
                                val pixel = pixels[x, y]
                                pixel.red > 0.7f && pixel.green > 0.7f && pixel.blue > 0.7f
                            }
                        } > 20
                    } else {
                        val pixel = pixels[pixels.width * 5 / 6, pixels.height / 5]
                        pixel.blue > 0.4f && pixel.blue > pixel.red * 2
                    }
                }
                capture("detail-$type-${if (broken) "fallback" else "final"}.png")
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
                compose.onNodeWithContentDescription("Sources").assertIsFocused()
                UiDevice.getInstance(instrumentation).pressBack()
                compose.waitUntil(10_000) {
                    runCatching { compose.onNodeWithTag(HOME_BROWSE_TAG).assertIsDisplayed() }.isSuccess
                }
                compose.onNodeWithContentDescription("Home").assertDoesNotExist()
                capture("detail-$type-back-home.png")
            }
        }
    }

    private fun assertLoading() {
        compose.onNode(androidx.compose.ui.test.SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Loading title")).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithContentDescription("Play").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithContentDescription("Play S1 E1").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Catalog preview").assertIsDisplayed()
    }

    private fun capture(name: String) {
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "remote-checks").apply { mkdirs() }
        check(UiDevice.getInstance(instrumentation).takeScreenshot(File(folder, name)))
    }

    private class DetailFixture(
        val type: String,
        val broken: Boolean,
        val portraitOnly: Boolean,
    ) : java.io.Closeable {
        private val server = java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val base = "http://127.0.0.1:${server.localPort}"
        val id = "fixture:soft-$type-${SystemClock.uptimeMillis()}"
        val metadata = java.util.concurrent.CountDownLatch(1)
        val artwork = java.util.concurrent.CountDownLatch(1)
        val metadataRequested = java.util.concurrent.atomic.AtomicBoolean()
        val artRequested = java.util.concurrent.atomic.AtomicBoolean()
        private val landscape = png(640, 360, android.graphics.Color.rgb(15, 48, 110))
        private val portrait = png(160, 240, android.graphics.Color.BLUE)
        private val logo = png(400, 100, android.graphics.Color.WHITE)
        private val worker = kotlin.concurrent.thread(isDaemon = true) {
            while (!server.isClosed) {
                try {
                    val socket = server.accept()
                    kotlin.concurrent.thread(isDaemon = true) { socket.use { respond(it) } }
                } catch (_: java.io.IOException) { if (!server.isClosed) throw AssertionError("Detail fixture failed") }
            }
        }

        private fun respond(socket: java.net.Socket) {
            val reader = socket.getInputStream().bufferedReader()
            val path = java.net.URLDecoder.decode(reader.readLine()?.split(' ')?.getOrNull(1).orEmpty(), "UTF-8").substringBefore('?')
            while (!reader.readLine().isNullOrEmpty()) { }
            var status = "200 OK"
            var mime = "application/json"
            val data = when {
                path == "/manifest.json" -> """{"id":"org.4789.soft","version":"1.0.0","name":"Detail arrival fixture","types":["movie","series"],"resources":["catalog","meta","stream"],"catalogs":[{"type":"$type","id":"soft","name":"Soft landing"}]}""".toByteArray()
                path.startsWith("/catalog/") -> """{"metas":[{"id":"$id","type":"$type","name":"Final detail","poster":"$base/poster.png"}]}""".toByteArray()
                path.startsWith("/meta/") -> {
                    metadataRequested.set(true)
                    metadata.await(20, java.util.concurrent.TimeUnit.SECONDS)
                    val episodes = if (type == "series") """, "videos":[{"id":"$id:1:1","season":1,"episode":1,"title":"Final episode","released":"2020-01-01","thumbnail":"$base/still.png"}]""" else ""
                    val background = if (portraitOnly) "" else ""","background":"$base/backdrop.png""""
                    """{"meta":{"id":"$id","type":"$type","name":"Final detail","poster":"$base/poster.png"$background,"logo":"$base/logo.png","description":"The final detail description."$episodes}}""".toByteArray()
                }
                path.endsWith(".png") -> {
                    mime = "image/png"
                    if ((type == "movie" && path == "/logo.png") || path == "/still.png") {
                        artRequested.set(true)
                        artwork.await(20, java.util.concurrent.TimeUnit.SECONDS)
                    }
                    if (broken) { status = "404 Not Found"; byteArrayOf() }
                    else when (path) { "/backdrop.png", "/still.png" -> landscape; "/logo.png" -> logo; else -> portrait }
                }
                else -> """{"streams":[]}""".toByteArray()
            }
            try {
                socket.getOutputStream().apply {
                    write("HTTP/1.1 $status\r\nContent-Type: $mime\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(data); flush()
                }
            } catch (_: java.io.IOException) { /* Back can cancel the held request. */ }
        }

        override fun close() { metadata.countDown(); artwork.countDown(); server.close(); worker.join(2_000) }

        private fun png(width: Int, height: Int, color: Int): ByteArray {
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            return java.io.ByteArrayOutputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                bitmap.recycle()
                it.toByteArray()
            }
        }
    }
}
