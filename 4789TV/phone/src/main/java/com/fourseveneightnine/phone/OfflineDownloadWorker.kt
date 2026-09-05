package com.fourseveneightnine.phone

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * One background attempt at a queued download.
 *
 * The order matters. Read the recipe, decrypt the manifest, ask the addon for the current stream
 * list, find the one entry the recipe named, and only then hold a link — for the length of one HTTP
 * call, in memory, on this stack. Nothing durable ever sees it.
 *
 * The class is public because WorkManager builds it by reflection.
 */
class OfflineDownloadWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val titleID = inputData.getString(KEY_TITLE_ID) ?: return Result.failure()
        val store = OfflineDownloadRecipeStore(applicationContext)
        val queued = store.load(titleID) ?: return Result.failure()
        if (queued.state == OfflineDownloadState.Completed) return Result.success()

        val job = queued.copy(
            state = OfflineDownloadState.Running,
            attempts = queued.attempts + 1,
            detail = null,
        )
        store.save(job)

        val manifestURL = when (val config = SecureAddonConfigStore(applicationContext).load()) {
            is SecureAddonConfigState.Available -> config.manifestURL
            SecureAddonConfigState.Missing -> return stop(
                store,
                job,
                "Add your source again in Source configuration, then queue this download once more.",
            )
            is SecureAddonConfigState.Unavailable -> return retryOrStop(
                store,
                job,
                "Secure source configuration could not be read.",
            )
        }

        val resolved = runCatching { RemoteSourceRepository().resolveOne(manifestURL, job.recipe) }
        if (resolved.isFailure) {
            return retryOrStop(store, job, "The source list could not be reached. Waiting to try again.")
        }
        val url = resolved.getOrNull() ?: return stop(
            store,
            job,
            "This source is no longer offered. Pick another source for ${job.recipe.label}.",
        )

        val outcome = OfflineMediaStore(applicationContext).downloadRemote(
            titleID = titleID,
            url = url,
            knownFingerprint = job.fingerprint,
            knownTotalBytes = job.declaredBytes,
        )
        val progressed = job.copy(
            bytesDone = outcome.bytesDone,
            declaredBytes = outcome.declaredBytes,
            fingerprint = outcome.fingerprint,
        )
        if (outcome.completed) {
            store.save(
                progressed.copy(state = OfflineDownloadState.Completed, detail = "Download finished."),
            )
            return Result.success()
        }
        return retryOrStop(
            store,
            progressed,
            "The download stopped part way. It starts again from where it reached.",
        )
    }

    /** A permanent stop. Nothing about waiting longer would change the answer. */
    private fun stop(
        store: OfflineDownloadRecipeStore,
        job: OfflineDownloadJob,
        detail: String,
    ): Result {
        store.save(job.copy(state = OfflineDownloadState.Failed, detail = detail))
        return Result.failure()
    }

    private fun retryOrStop(
        store: OfflineDownloadRecipeStore,
        job: OfflineDownloadJob,
        detail: String,
    ): Result {
        if (!OfflineDownloadQueuePolicy.shouldRetry(job.attempts)) {
            return stop(store, job, "$detail It has now been tried too many times.")
        }
        store.save(job.copy(state = OfflineDownloadState.Queued, detail = detail))
        return Result.retry()
    }

    companion object {
        const val KEY_TITLE_ID = "title-id"
    }
}
