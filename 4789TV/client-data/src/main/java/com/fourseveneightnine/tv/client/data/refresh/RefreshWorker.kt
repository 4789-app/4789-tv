package com.fourseveneightnine.tv.client.data.refresh

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * The 6-hourly refresh.
 *
 * It does nothing unless the app is alive and in the foreground. A TV box that wakes its radio
 * every six hours to refresh shelves nobody is looking at costs standby power for no gain, and the
 * plan says this trigger fires "every 6 h via WorkManager while the app is open".
 */
public class RefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (!AppLifecycleFlag.isForeground) return Result.success()
        val scheduler = RefreshScheduler.currentProcessInstance() ?: return Result.success()
        scheduler.refreshNow(force = false)
        return Result.success()
    }
}
