package com.fourseveneightnine.phone

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

internal object OfflineDownloadQueuePolicy {
    const val MAXIMUM_ATTEMPTS = 5
    const val BACKOFF_SECONDS = 30L

    fun shouldRetry(attempts: Int): Boolean = attempts in 1 until MAXIMUM_ATTEMPTS

    /**
     * The unique work name. It is the same opaque digest the media file uses, so WorkManager's own
     * database learns nothing about what the viewer is watching.
     */
    fun workName(titleID: String): String = "offline-download:" + OfflineMediaPolicy.safeName(titleID)

    /** One short line for the viewer. It never quotes a link. */
    fun statusLine(job: OfflineDownloadJob): String = when (job.state) {
        OfflineDownloadState.Queued -> job.progressPercent
            ?.takeIf { it > 0 }
            ?.let { "Waiting to continue at $it%" }
            ?: "Waiting to download"
        OfflineDownloadState.Running -> job.progressPercent
            ?.let { "Downloading $it%" }
            ?: "Downloading"
        OfflineDownloadState.Failed -> job.detail ?: "The download stopped."
        OfflineDownloadState.Completed -> "Saved for offline"
    }
}

/**
 * The queue of background downloads.
 *
 * WorkManager owns the scheduling; this class owns the durable recipe beside it. The two are kept
 * in step by the title id, which is the only thing handed to the worker.
 */
internal class OfflineDownloadQueue(context: Context) {
    private val appContext = context.applicationContext
    private val store = OfflineDownloadRecipeStore(appContext)
    private val settings = PhoneSettingsStore(appContext)

    fun enqueue(recipe: DurableStreamRecipe): Boolean {
        val existing = store.load(recipe.titleID)
        // Same file as before: keep the bytes already fetched. A different file: start clean.
        val job = if (existing != null && existing.recipe == recipe) {
            existing.copy(state = OfflineDownloadState.Queued, attempts = 0, detail = null)
        } else {
            OfflineDownloadJob(recipe = recipe, state = OfflineDownloadState.Queued)
        }
        if (!store.save(job)) return false
        val request = OneTimeWorkRequest.Builder(OfflineDownloadWorker::class.java)
            .setInputData(
                Data.Builder().putString(OfflineDownloadWorker.KEY_TITLE_ID, recipe.titleID).build(),
            )
            .setConstraints(
                Constraints.Builder()
                    // Read at enqueue time, so a rule the viewer changes applies to the next
                    // download instead of to work WorkManager has already accepted.
                    .setRequiredNetworkType(
                        PhoneSettingsPolicy.networkType(settings.downloadNetworkRule()),
                    )
                    .build(),
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                OfflineDownloadQueuePolicy.BACKOFF_SECONDS,
                TimeUnit.SECONDS,
            )
            .build()
        return runCatching {
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                OfflineDownloadQueuePolicy.workName(recipe.titleID),
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }.isSuccess
    }

    fun job(titleID: String): OfflineDownloadJob? = store.load(titleID)

    /**
     * Every download this app still knows about, in a stable order.
     *
     * SharedPreferences keys come back in whatever order the map holds them, so the list is sorted
     * to stop the settings screen reshuffling itself between reads.
     */
    fun jobs(): List<OfflineDownloadJob> = store.all()
        .sortedWith(compareBy({ it.recipe.label }, { it.recipe.titleID }))

    fun cancel(titleID: String): Boolean {
        runCatching {
            WorkManager.getInstance(appContext)
                .cancelUniqueWork(OfflineDownloadQueuePolicy.workName(titleID))
        }
        return store.remove(titleID)
    }

    /**
     * Stop every queued download and forget its recipe.
     *
     * Used when the viewer removes all offline copies. Without it a worker would wake later and
     * quietly fetch back a file the viewer just asked this app to get rid of.
     */
    fun cancelAll(): Boolean {
        val manager = runCatching { WorkManager.getInstance(appContext) }.getOrNull()
        store.all().forEach { job ->
            runCatching {
                manager?.cancelUniqueWork(
                    OfflineDownloadQueuePolicy.workName(job.recipe.titleID),
                )
            }
        }
        return store.clearAll()
    }

    /** Titles whose half-written file must survive a startup clean-up. */
    fun unfinishedTitleIDs(): Set<String> = store.all()
        .filter { it.state != OfflineDownloadState.Completed }
        .map { it.recipe.titleID }
        .toSet()
}
