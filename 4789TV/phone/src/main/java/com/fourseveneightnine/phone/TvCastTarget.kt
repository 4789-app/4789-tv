package com.fourseveneightnine.phone

import android.content.Context
import androidx.core.content.edit
import java.net.InetAddress

internal data class TvCastTarget(val host: String, val port: Int = PORT) {
    companion object {
        const val PORT = 8_791
    }
}

internal object TvCastTargetPolicy {
    fun parse(input: String): TvCastTarget? {
        val value = input.trim()
        if (value.length !in 7..15 || value.any { it !in '0'..'9' && it != '.' }) return null
        val bytes = value.split('.').map { it.toIntOrNull() ?: return null }
        if (bytes.size != 4 || bytes.any { it !in 0..255 }) return null
        val address = runCatching { InetAddress.getByAddress(bytes.map(Int::toByte).toByteArray()) }
            .getOrNull() ?: return null
        if (!address.isSiteLocalAddress || address.isLoopbackAddress || address.isAnyLocalAddress) return null
        return TvCastTarget(value)
    }
}

internal class TvCastTargetStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "tv-cast-target-v1",
        Context.MODE_PRIVATE,
    )

    fun load(): TvCastTarget? = preferences.getString(KEY, null)?.let(TvCastTargetPolicy::parse)

    fun save(input: String): Boolean {
        val target = TvCastTargetPolicy.parse(input) ?: return false
        preferences.edit(commit = true) { putString(KEY, target.host) }
        return load() == target
    }

    fun clear(): Boolean {
        preferences.edit(commit = true) { remove(KEY) }
        return load() == null
    }

    private companion object {
        const val KEY = "host"
    }
}
