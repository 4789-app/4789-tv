package com.fourseveneightnine.tv.startup

import android.content.Context
import com.fourseveneightnine.tv.player.ResumePoint

/**
 * One-slot persistence for [ResumePoint]. SharedPreferences, because this is a single small record
 * whose loss costs nothing — and `commit()` rather than `apply()`, because the moment it is written
 * is usually the moment the Activity is going away, and Fire OS SIGKILLs a stopped process before
 * an async write flushes (the same trap the engine override hit in 0.1.16).
 */
internal class ResumePointStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): ResumePoint? = ResumePoint.decode(prefs.getString(KEY, null))

    fun save(point: ResumePoint) {
        prefs.edit().putString(KEY, point.encode()).commit()
    }

    fun clear() {
        prefs.edit().remove(KEY).commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "receiver-resume"
        const val KEY = "point"
    }
}
