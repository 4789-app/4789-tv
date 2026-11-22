package com.fourseveneightnine.tv.client.data.refresh

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.fourseveneightnine.tv.client.data.catalog.SnapshotState
import com.fourseveneightnine.tv.client.data.catalog.SnapshotStore
import com.fourseveneightnine.tv.client.data.library.JobState
import com.fourseveneightnine.tv.client.data.library.LibraryRepository
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Where a refresh row is written. [LibraryRepository] is the real one. */
public fun interface JobSink {
    public suspend fun update(name: String, state: JobState, message: String?)

    public companion object {
        /** Drops every row. For tests and for a scheduler with no database behind it. */
        public val None: JobSink = JobSink { _, _, _ -> }
    }
}

/** Writes job rows into the library database. */
public fun LibraryRepository.asJobSink(): JobSink =
    JobSink { name, state, message -> updateJob(name, state, message) }

/**
 * Whether this process is in the foreground.
 *
 * The 6-hourly WorkManager job only runs while the app is alive and showing, because a background
 * refresh on a TV box wakes the radio and the panel for shelves nobody is looking at. The `:tv`
 * Application sets this from its lifecycle observer.
 */
public object AppLifecycleFlag {
    @Volatile
    private var foreground: Boolean = false

    public val isForeground: Boolean get() = foreground

    public fun onEnterForeground() {
        foreground = true
    }

    public fun onLeaveForeground() {
        foreground = false
    }
}

/**
 * The four triggers from the rebuild plan §5.4: the app comes to the foreground, a trusted sync
 * arrives from the phone, the 6-hourly WorkManager job fires while the app is open, and "Refresh
 * now" in Settings → Jobs.
 *
 * Overlapping refreshes are coalesced, not queued. Two triggers a second apart would ask the same
 * server for the same generation, so the second is dropped and counted.
 */
public class RefreshScheduler(
    private val scope: CoroutineScope,
    private val snapshotStore: SnapshotStore,
    private val addonCatalogRefresher: suspend (Boolean) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val jobs: JobSink = JobSink.None,
    private val receiverCatalogRefresher: suspend (Boolean) -> Unit = {},
    private val playbackBusy: Flow<Boolean> = flowOf(false),
    private val diagnostic: (String, String) -> Unit = { _, _ -> },
) {
    private val gate = Mutex()
    private val bulkWorkGate = Mutex()
    private val coalesced = AtomicInteger(0)

    @Volatile
    private var lastRunMillis: Long? = null

    /** Refreshes dropped because one was already running. */
    public val coalescedCount: Int get() = coalesced.get()

    /** When the last refresh finished, in epoch milliseconds. */
    public val lastRunAtMillis: Long? get() = lastRunMillis

    /** The app came back to the front. */
    public fun onForeground(): Job {
        AppLifecycleFlag.onEnterForeground()
        return trigger(force = false)
    }

    /** The phone pushed new settings over `/x4789/sync/{receiverID}`. */
    public fun onTrustedSync(): Job = trigger(force = false)

    /** "Refresh now" in Settings → Jobs. Runs every source, not the focused one. */
    public fun manual(): Job = trigger(force = true)

    private fun trigger(force: Boolean): Job = scope.launch { refreshNow(force) }

    /** Keep only one bulk operation in memory. If playback starts, cancel and retry when idle. */
    @OptIn(ExperimentalCoroutinesApi::class)
    public suspend fun runBulkWork(name: String, work: suspend () -> Unit) {
        playbackBusy.distinctUntilChanged().transformLatest { busy ->
            if (busy) {
                diagnostic(name, "deferred")
            } else {
                bulkWorkGate.withLock {
                    diagnostic(name, "started")
                    try {
                        work()
                        diagnostic(name, "completed")
                    } catch (cancelled: CancellationException) {
                        diagnostic(name, "interrupted")
                        throw cancelled
                    }
                }
                emit(Unit)
            }
        }.first()
    }

    /**
     * Runs one refresh and records its job rows.
     *
     * @return false when another refresh was already running and this one was coalesced away.
     */
    public suspend fun refreshNow(force: Boolean = false): Boolean {
        if (!gate.tryLock()) {
            coalesced.incrementAndGet()
            return false
        }
        try {
            runBulkWork("snapshots") {
                refreshJob(JOB_CATALOG_SNAPSHOTS) {
                    snapshotStore.refresh(force)
                    val failures = snapshotStore.status().value.sources.filter { it.state == SnapshotState.FAILED }
                    if (failures.isEmpty()) JobState.OK to null
                    else JobState.FAILED to failures.firstNotNullOfOrNull { it.message }
                }
            }
            runBulkWork("addons") {
                refreshJob(JOB_ADDON_CATALOGS) {
                    addonCatalogRefresher(force)
                    JobState.OK to null
                }
            }
            runBulkWork("receiver") { receiverCatalogRefresher(force) }
            lastRunMillis = clock()
            return true
        } finally {
            gate.unlock()
        }
    }

    public suspend fun refreshJob(name: String, work: suspend () -> Pair<JobState, String?>) {
        jobs.update(name, JobState.RUNNING, null)
        val (state, message) = try {
            work()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { jobs.update(name, JobState.IDLE, null) }
            throw cancelled
        } catch (failure: Exception) {
            JobState.FAILED to failure.message
        }
        jobs.update(name, state, message)
    }

    /**
     * Enqueues the 6-hourly job. Unique by name, so calling this on every launch replaces nothing
     * and schedules nothing twice.
     */
    public fun schedulePeriodicRefresh(context: Context) {
        installProcessInstance(this)
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RefreshWorker>(PERIOD_HOURS, TimeUnit.HOURS).build(),
        )
    }

    public companion object {
        public const val PERIODIC_WORK_NAME: String = "4789-catalog-refresh"
        public const val PERIOD_HOURS: Long = 6L

        /** Jobs page row for the signed snapshots. */
        public const val JOB_CATALOG_SNAPSHOTS: String = "Catalog snapshots"

        /** Jobs page row for the add-on catalogs. */
        public const val JOB_ADDON_CATALOGS: String = "Add-on catalogs"

        @Volatile
        private var processInstance: RefreshScheduler? = null

        internal fun installProcessInstance(scheduler: RefreshScheduler) {
            processInstance = scheduler
        }

        internal fun currentProcessInstance(): RefreshScheduler? = processInstance
    }
}
