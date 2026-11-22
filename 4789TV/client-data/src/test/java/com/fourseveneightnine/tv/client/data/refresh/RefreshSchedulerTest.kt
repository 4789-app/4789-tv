package com.fourseveneightnine.tv.client.data.refresh

import com.fourseveneightnine.contract.CatalogSnapshotContract
import com.fourseveneightnine.tv.client.data.catalog.CatalogHttpException
import com.fourseveneightnine.tv.client.data.catalog.FakeCatalogHttp
import com.fourseveneightnine.tv.client.data.catalog.FakeSnapshotVerifier
import com.fourseveneightnine.tv.client.data.catalog.PrivateGeneration
import com.fourseveneightnine.tv.client.data.catalog.PublicGeneration
import com.fourseveneightnine.tv.client.data.catalog.SnapshotStore
import com.fourseveneightnine.tv.client.data.catalog.SnapshotSource
import com.fourseveneightnine.tv.client.data.catalog.SnapshotState
import com.fourseveneightnine.tv.client.data.library.JobState
import com.fourseveneightnine.tv.client.data.library.JobStatus
import com.fourseveneightnine.tv.client.data.library.LibraryDatabase
import com.fourseveneightnine.tv.client.data.library.LibraryRepository
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Every job row the scheduler wrote, in order. */
private class RecordingJobSink : JobSink {
    val rows: MutableList<Triple<String, JobState, String?>> = mutableListOf()

    override suspend fun update(name: String, state: JobState, message: String?) {
        rows += Triple(name, state, message)
    }

    fun states(name: String): List<JobState> = rows.filter { it.first == name }.map { it.second }

    fun lastMessage(name: String): String? = rows.last { it.first == name }.third
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RefreshSchedulerTest {
    private lateinit var filesDir: File
    private lateinit var http: FakeCatalogHttp

    @Before
    fun setUp() {
        filesDir = File(RuntimeEnvironment.getApplication().filesDir, "refresh-test-${System.nanoTime()}")
        filesDir.mkdirs()
        http = FakeCatalogHttp()
    }

    @After
    fun tearDown() {
        AppLifecycleFlag.onLeaveForeground()
    }

    private fun TestScope.snapshotStore(token: String? = "a-token") = SnapshotStore(
        filesDir = filesDir,
        http = http,
        verifier = FakeSnapshotVerifier(),
        tokenProvider = { token },
        letterboxdUsernames = { emptyList() },
        ioDispatcher = StandardTestDispatcher(testScheduler),
        now = { currentTime },
    )

    private fun TestScope.scheduler(
        jobs: JobSink = JobSink.None,
        addonCatalogRefresher: suspend (Boolean) -> Unit = { },
    ) = RefreshScheduler(
        scope = backgroundScope,
        snapshotStore = snapshotStore(),
        addonCatalogRefresher = addonCatalogRefresher,
        clock = { currentTime },
        jobs = jobs,
    )

    /** Both sources answer, so a green refresh really is green. */
    private fun publishOneGeneration() {
        PrivateGeneration(http, "g1")
            .shelf("private/v1/owner/popular.json", "tamilmv:main:popular", listOf("One", "Two"))
            .publish()
        PublicGeneration.standard(http)
    }

    @Test
    fun `a refresh writes running then ok for both jobs`() = runTest {
        publishOneGeneration()
        val sink = RecordingJobSink()

        assertTrue(scheduler(jobs = sink).refreshNow())

        assertEquals(
            listOf(JobState.RUNNING, JobState.OK),
            sink.states(RefreshScheduler.JOB_CATALOG_SNAPSHOTS),
        )
        assertEquals(
            listOf(JobState.RUNNING, JobState.OK),
            sink.states(RefreshScheduler.JOB_ADDON_CATALOGS),
        )
    }

    @Test
    fun `catalog refresh and Home publication wait for playback and never overlap`() = runTest {
        publishOneGeneration()
        val busy = MutableStateFlow(true)
        val sink = RecordingJobSink()
        val starts = mutableListOf<String>()
        val scheduler = RefreshScheduler(
            scope = backgroundScope, snapshotStore = snapshotStore(),
            addonCatalogRefresher = { }, jobs = sink,
            playbackBusy = busy,
            diagnostic = { name, stage -> if (stage == "started" || stage == "completed") starts += "$name:$stage" },
        )
        val refresh = scheduler.manual()
        val home = launch { scheduler.runBulkWork("home") { delay(500) } }

        delay(100)
        assertTrue(starts.isEmpty())
        assertTrue(sink.rows.isEmpty())

        busy.value = false
        home.join()
        refresh.join()
        assertEquals(setOf("snapshots", "addons", "receiver", "home"),
            starts.chunked(2).map { pair ->
                val name = pair.first().substringBefore(':')
                assertEquals(listOf("$name:started", "$name:completed"), pair)
                name
            }.toSet())
    }

    @Test
    fun `playback starting during a job interrupts only that stage and retries it once idle`() = runTest {
        publishOneGeneration()
        val busy = MutableStateFlow(false)
        val sink = RecordingJobSink()
        var addonAttempts = 0
        var receiverRefreshes = 0
        val scheduler = RefreshScheduler(
            scope = backgroundScope, snapshotStore = snapshotStore(), jobs = sink,
            addonCatalogRefresher = { if (++addonAttempts == 1) awaitCancellation() },
            receiverCatalogRefresher = { receiverRefreshes++ },
            playbackBusy = busy,
        )

        val refresh = scheduler.manual()
        delay(100)
        assertEquals(1, addonAttempts)

        busy.value = true
        delay(100)
        assertEquals(listOf(JobState.RUNNING, JobState.IDLE), sink.states(RefreshScheduler.JOB_ADDON_CATALOGS))
        assertEquals(1, addonAttempts)
        assertEquals(0, receiverRefreshes)
        assertFalse(refresh.isCompleted)

        busy.value = false
        refresh.join()
        assertEquals(listOf(JobState.RUNNING, JobState.OK), sink.states(RefreshScheduler.JOB_CATALOG_SNAPSHOTS))
        assertEquals(listOf(JobState.RUNNING, JobState.IDLE, JobState.RUNNING, JobState.OK),
            sink.states(RefreshScheduler.JOB_ADDON_CATALOGS))
        assertEquals(2, addonAttempts)
        assertEquals(1, receiverRefreshes)
    }

    @Test
    fun `a failed source marks the snapshots job failed and keeps its message`() = runTest {
        // Nothing published, so both catalog sources answer 404.
        val sink = RecordingJobSink()

        scheduler(jobs = sink).refreshNow()

        assertEquals(
            listOf(JobState.RUNNING, JobState.FAILED),
            sink.states(RefreshScheduler.JOB_CATALOG_SNAPSHOTS),
        )
        assertTrue(sink.lastMessage(RefreshScheduler.JOB_CATALOG_SNAPSHOTS).orEmpty().isNotBlank())
    }

    @Test
    fun `foreground phone sync and manual refresh succeed without the optional private token`() = runTest {
        PublicGeneration.standard(http)
        val sink = RecordingJobSink()
        val snapshots = snapshotStore(token = null)
        var receiverRefreshes = 0
        val scheduler = RefreshScheduler(
            scope = backgroundScope, snapshotStore = snapshots,
            addonCatalogRefresher = {}, jobs = sink,
            receiverCatalogRefresher = { receiverRefreshes++ },
        )

        scheduler.onForeground().join()
        scheduler.onTrustedSync().join()
        scheduler.manual().join()

        assertEquals(List(3) { listOf(JobState.RUNNING, JobState.OK) }.flatten(),
            sink.states(RefreshScheduler.JOB_CATALOG_SNAPSHOTS))
        assertEquals(List(3) { listOf(JobState.RUNNING, JobState.OK) }.flatten(),
            sink.states(RefreshScheduler.JOB_ADDON_CATALOGS))
        assertEquals(SnapshotState.READY, snapshots.status().value.of(SnapshotSource.PUBLIC_TMDB)?.state)
        assertEquals(SnapshotState.NOT_CONFIGURED, snapshots.status().value.of(SnapshotSource.PRIVATE_CATALOG)?.state)
        assertTrue(http.privateRequests().isEmpty())
        assertEquals(3, receiverRefreshes)
    }

    @Test
    fun `a configured private server that rejects its token still fails the combined job`() = runTest {
        publishOneGeneration()
        http.failures[CatalogSnapshotContract.privateManifestURL] = CatalogHttpException(401)
        val sink = RecordingJobSink()

        scheduler(jobs = sink).refreshNow()

        assertEquals(listOf(JobState.RUNNING, JobState.FAILED),
            sink.states(RefreshScheduler.JOB_CATALOG_SNAPSHOTS))
        assertTrue(sink.lastMessage(RefreshScheduler.JOB_CATALOG_SNAPSHOTS).orEmpty().contains("401"))
        assertEquals(listOf(JobState.RUNNING, JobState.OK), sink.states(RefreshScheduler.JOB_ADDON_CATALOGS))
    }

    @Test
    fun `an unconfigured private server does not hide a failed public refresh`() = runTest {
        val sink = RecordingJobSink()
        RefreshScheduler(
            scope = backgroundScope, snapshotStore = snapshotStore(token = null),
            addonCatalogRefresher = {}, jobs = sink,
        ).refreshNow()

        assertEquals(listOf(JobState.RUNNING, JobState.FAILED),
            sink.states(RefreshScheduler.JOB_CATALOG_SNAPSHOTS))
        assertTrue(sink.lastMessage(RefreshScheduler.JOB_CATALOG_SNAPSHOTS).orEmpty().isNotBlank())
    }

    @Test
    fun `manual refresh forces add-on revalidation`() = runTest {
        publishOneGeneration()
        val modes = mutableListOf<Boolean>()
        val scheduler = scheduler(addonCatalogRefresher = { modes.add(it) })
        scheduler.refreshNow()
        scheduler.refreshNow(force = true)
        assertEquals(listOf(false, true), modes)
    }

    @Test
    fun `a failing add-on refresher fails only its own row`() = runTest {
        publishOneGeneration()
        val sink = RecordingJobSink()

        scheduler(jobs = sink, addonCatalogRefresher = { error("add-on registry is empty") }).refreshNow()

        assertEquals(
            listOf(JobState.RUNNING, JobState.OK),
            sink.states(RefreshScheduler.JOB_CATALOG_SNAPSHOTS),
        )
        assertEquals(
            listOf(JobState.RUNNING, JobState.FAILED),
            sink.states(RefreshScheduler.JOB_ADDON_CATALOGS),
        )
        assertEquals("add-on registry is empty", sink.lastMessage(RefreshScheduler.JOB_ADDON_CATALOGS))
    }

    @Test
    fun `bulk receiver refresh waits for snapshots and addons for both force modes`() = runTest {
        publishOneGeneration()
        http.delaysMillis[CatalogSnapshotContract.privateManifestURL] = 500
        val snapshots = snapshotStore()
        val phases = mutableListOf<String>()
        val receiverModes = mutableListOf<Boolean>()
        val scheduler = RefreshScheduler(
            scope = backgroundScope, snapshotStore = snapshots,
            addonCatalogRefresher = {
                assertEquals(SnapshotState.READY, snapshots.status().value.of(SnapshotSource.PUBLIC_TMDB)?.state)
                phases += "addons"
                delay(500)
                phases += "addons complete"
            },
            receiverCatalogRefresher = {
                assertEquals("addons complete", phases.lastOrNull())
                assertEquals(SnapshotState.READY, snapshots.status().value.of(SnapshotSource.PRIVATE_CATALOG)?.state)
                receiverModes += it
                phases += "receiver"
            },
        )
        scheduler.refreshNow()
        scheduler.refreshNow(force = true)
        assertEquals(listOf(false, true), receiverModes)
        assertEquals(List(2) { listOf("addons", "addons complete", "receiver") }.flatten(), phases)
    }

    @Test
    fun `an overlapping refresh is dropped and counted, not queued`() = runTest {
        publishOneGeneration()
        val scheduler = scheduler(addonCatalogRefresher = { delay(5_000) })

        backgroundScope.launch { scheduler.refreshNow() }
        testScheduler.advanceTimeBy(100)

        assertFalse("the second refresh should be coalesced away", scheduler.refreshNow())
        assertEquals(1, scheduler.coalescedCount)

        delay(10_000)
        assertTrue("the gate should be free once the first refresh ends", scheduler.refreshNow())
        assertEquals(1, scheduler.coalescedCount)
    }

    @Test
    fun `every trigger runs one refresh`() = runTest {
        publishOneGeneration()
        var runs = 0
        val scheduler = scheduler(addonCatalogRefresher = { runs++ })

        scheduler.onForeground().join()
        scheduler.onTrustedSync().join()
        scheduler.manual().join()

        assertEquals(3, runs)
        assertEquals(0, scheduler.coalescedCount)
        assertNotNull(scheduler.lastRunAtMillis)
    }

    @Test
    fun `coming to the foreground flips the flag the periodic worker reads`() = runTest {
        AppLifecycleFlag.onLeaveForeground()
        assertFalse(AppLifecycleFlag.isForeground)

        scheduler().onForeground().join()

        assertTrue(AppLifecycleFlag.isForeground)
    }

    @Test
    fun `job rows land in the library database`() = runTest {
        publishOneGeneration()
        val database = LibraryDatabase.createInMemory(RuntimeEnvironment.getApplication())
        try {
            val library = LibraryRepository(database) { currentTime }
            scheduler(jobs = library.asJobSink()).refreshNow()

            val rows = library.jobs().first().associateBy(JobStatus::name)
            assertEquals(JobState.OK, rows[RefreshScheduler.JOB_CATALOG_SNAPSHOTS]?.state)
            assertEquals(JobState.OK, rows[RefreshScheduler.JOB_ADDON_CATALOGS]?.state)
            assertNotNull(rows[RefreshScheduler.JOB_CATALOG_SNAPSHOTS]?.startedAtMillis)
            assertNotNull(rows[RefreshScheduler.JOB_CATALOG_SNAPSHOTS]?.finishedAtMillis)
        } finally {
            database.close()
        }
    }
}
