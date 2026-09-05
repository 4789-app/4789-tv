package com.fourseveneightnine.phone

import android.content.Context
import androidx.core.content.edit
import kotlin.math.roundToLong

internal data class LocalPlaybackProgress(val positionMillis: Long, val durationMillis: Long)

internal object LocalPlaybackProgressPolicy {
    private const val MINIMUM_RESUME_MILLIS = 5_000L
    private const val WATCHED_FRACTION = 0.92

    fun resumable(positionMillis: Long, durationMillis: Long): LocalPlaybackProgress? {
        if (positionMillis < MINIMUM_RESUME_MILLIS || durationMillis <= 0) return null
        val safePosition = positionMillis.coerceIn(0, durationMillis)
        if (safePosition.toDouble() / durationMillis >= WATCHED_FRACTION) return null
        return LocalPlaybackProgress(safePosition, durationMillis)
    }

    fun progressPercent(progress: LocalPlaybackProgress): Int =
        (progress.positionMillis.toDouble() / progress.durationMillis * 100).roundToLong().toInt()
}

internal class LocalPlaybackProgressStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "local-playback-progress-v1",
        Context.MODE_PRIVATE,
    )

    fun load(titleID: String): LocalPlaybackProgress? =
        LocalPlaybackProgressPolicy.resumable(
            positionMillis = preferences.getLong(positionKey(titleID), 0),
            durationMillis = preferences.getLong(durationKey(titleID), 0),
        )

    fun record(titleID: String, positionMillis: Long, durationMillis: Long) {
        // Media3 reports TIME_UNSET while preparing. That is not evidence the previous valid resume
        // point should be erased.
        if (durationMillis <= 0) return
        val progress = LocalPlaybackProgressPolicy.resumable(positionMillis, durationMillis)
        preferences.edit {
            if (progress == null) {
                remove(positionKey(titleID))
                remove(durationKey(titleID))
            } else {
                putLong(positionKey(titleID), progress.positionMillis)
                putLong(durationKey(titleID), progress.durationMillis)
            }
        }
    }

    fun clearAll(): Boolean {
        preferences.edit(commit = true) { clear() }
        return preferences.all.isEmpty()
    }

    private fun positionKey(titleID: String) = "position:$titleID"
    private fun durationKey(titleID: String) = "duration:$titleID"
}
