package com.fourseveneightnine.phone

import android.content.Context
import androidx.core.content.edit
import androidx.work.NetworkType

/** Which connections a background download is allowed to use. */
internal enum class DownloadNetworkRule { UnmeteredOnly, AnyConnection }

/**
 * The rules behind the settings the viewer can change.
 *
 * Kept apart from the store so each one can be read and tested without a device.
 */
internal object PhoneSettingsPolicy {
    /**
     * Wi-Fi only is the default, and it is also where an unreadable value lands. A download is a
     * large file that runs later, out of sight, so the safe failure is the one that cannot spend
     * the viewer's mobile data.
     */
    val DEFAULT_DOWNLOAD_NETWORK = DownloadNetworkRule.UnmeteredOnly

    fun encode(rule: DownloadNetworkRule): String = rule.name

    fun decodeDownloadNetwork(stored: String?): DownloadNetworkRule =
        DownloadNetworkRule.entries.firstOrNull { it.name == stored } ?: DEFAULT_DOWNLOAD_NETWORK

    /** The WorkManager constraint this rule asks for. */
    fun networkType(rule: DownloadNetworkRule): NetworkType = when (rule) {
        DownloadNetworkRule.UnmeteredOnly -> NetworkType.UNMETERED
        DownloadNetworkRule.AnyConnection -> NetworkType.CONNECTED
    }

    /** What the setting does now, in one line. */
    fun downloadNetworkLine(rule: DownloadNetworkRule): String = when (rule) {
        DownloadNetworkRule.UnmeteredOnly -> "Downloads wait for Wi-Fi."
        DownloadNetworkRule.AnyConnection -> "Downloads may use mobile data."
    }

    /** What the button does when it is pressed. It names the other rule, never the current one. */
    fun downloadNetworkAction(rule: DownloadNetworkRule): String = when (rule) {
        DownloadNetworkRule.UnmeteredOnly -> "Allow downloads on mobile data"
        DownloadNetworkRule.AnyConnection -> "Download on Wi-Fi only"
    }

    fun toggled(rule: DownloadNetworkRule): DownloadNetworkRule = when (rule) {
        DownloadNetworkRule.UnmeteredOnly -> DownloadNetworkRule.AnyConnection
        DownloadNetworkRule.AnyConnection -> DownloadNetworkRule.UnmeteredOnly
    }

    /** One line above the queue list. It counts jobs and never names a title. */
    fun queueSummary(jobCount: Int): String = when (jobCount) {
        0 -> "No downloads are waiting."
        1 -> "1 download in the queue."
        else -> "$jobCount downloads in the queue."
    }
}

/**
 * Where the viewer's own app settings live between app runs.
 *
 * Plain SharedPreferences on purpose, for the same reason the download recipes are plain: none of
 * these values grants access to anything. The secure store holds the manifest, which is the
 * credential.
 */
internal class PhoneSettingsStore(
    context: Context,
    preferenceName: String = "phone-settings-v1",
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        preferenceName,
        Context.MODE_PRIVATE,
    )

    fun downloadNetworkRule(): DownloadNetworkRule = PhoneSettingsPolicy.decodeDownloadNetwork(
        preferences.getString(KEY_DOWNLOAD_NETWORK, null),
    )

    fun setDownloadNetworkRule(rule: DownloadNetworkRule): Boolean {
        preferences.edit(commit = true) {
            putString(KEY_DOWNLOAD_NETWORK, PhoneSettingsPolicy.encode(rule))
        }
        return downloadNetworkRule() == rule
    }

    private companion object {
        const val KEY_DOWNLOAD_NETWORK = "download-network"
    }
}
