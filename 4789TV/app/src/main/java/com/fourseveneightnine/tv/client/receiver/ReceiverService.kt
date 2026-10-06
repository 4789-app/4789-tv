package com.fourseveneightnine.tv.client.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fourseveneightnine.tv.R
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics

/**
 * Keeps the Kodi transport's bound ports alive while the receiver is usable.
 *
 * It does not decide *whether* the receiver runs — [com.fourseveneightnine.tv.client.playback.ReceiverHost]
 * still owns that, and the rule is unchanged: a session is valid only while the Activity is
 * visible with a ready Surface. This service is started when the transport binds and stopped when
 * it unbinds, so the television cannot quietly reclaim the process from under a phone that is
 * mid-cast.
 */
internal class ReceiverService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        manager.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.receiver_service_running))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        ReceiverDiagnostics.record("service.started")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ReceiverDiagnostics.record("service.stopped")
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "receiver"
        private const val NOTIFICATION_ID = 4789

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, ReceiverService::class.java))
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ReceiverService::class.java)) }
        }
    }
}
