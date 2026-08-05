package com.fourseveneightnine.tv.startup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fourseveneightnine.tv.ui.MainActivity

/**
 * Best-effort Android TV auto-start after a full boot or an in-place APK update.
 *
 * Android 10+ may still block background activity launches on individual vendor builds. The
 * receiver therefore remains a convenience, not a promise that a powered-off television can be
 * awakened. When Android permits the launch, opening the Activity gives libmpv the real Surface it
 * needs and makes the receiver discoverable without a remote-control visit to the launcher.
 */
class ReceiverBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ReceiverBootPolicy.shouldLaunch(intent.action)) return

        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        runCatching { context.startActivity(launch) }
    }
}

internal object ReceiverBootPolicy {
    fun shouldLaunch(action: String?): Boolean = action in supportedActions

    private val supportedActions = setOf(
        Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_MY_PACKAGE_REPLACED,
    )
}
