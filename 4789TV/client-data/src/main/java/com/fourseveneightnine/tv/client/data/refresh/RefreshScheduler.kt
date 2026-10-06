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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

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
    private val addonCatalogRefresher: suspend () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val jobs: JobSink = JobSink.None,
) {
    private val gate = Mutex()
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
            jobs.update(JOB_CATALOG_SNAPSHOTS, JobState.RUNNING, null)
            runCatching { snapshotStore.refresh(force) }.fold(
                onSuccess = {
                    val failures = snapshotStore.status().value.sources
                        .filter { it.state == SnapshotState.FAILED }
                    if (failures.isEmpty()) {
                        jobs.update(JOB_CATALOG_SNAPSHOTS, JobState.OK, null)
                    } else {
                        jobs.update(
                            JOB_CATALOG_SNAPSHOTS,
                            JobState.FAILED,
                            failures.firstNotNullOfOrNull { it.message },
                        )
                    }
                },
                onFailure = { jobs.update(JOB_CATALOG_SNAPSHOTS, JobState.FAILED, it.message) },
            )

            jobs.update(JOB_ADDON_CATALOGS, JobState.RUNNING, null)
            runCatching { addonCatalogRefresher() }.fold(
                onSuccess = { jobs.update(JOB_ADDON_CATALOGS, JobState.OK, null) },
                onFailure = { jobs.update(JOB_ADDON_CATALOGS, JobState.FAILED, it.message) },
            )

            lastRunMillis = clock()
            return true
        } finally {
            gate.unlock()
        }
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
