package com.fourseveneightnine.tv.client.iptv

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fourseveneightnine.tv.R
import com.fourseveneightnine.tv.client.clientGraph
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import okhttp3.OkHttpClient
import okhttp3.Request

/** Android alarm survives the app process; the encrypted TV account supplies the URL at start. */
internal object IptvRecordingScheduler {
    const val EXTRA_ID = "recording_id"

    fun schedule(context: Context, recording: IptvRecording) {
        if (recording.status != IptvRecordingStatus.SCHEDULED) return
        if (recording.startsAtMillis <= System.currentTimeMillis()) {
            start(context, recording.id)
            return
        }
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, IptvRecordingAlarmReceiver::class.java).putExtra(EXTRA_ID, recording.id)
        val pending = PendingIntent.getBroadcast(context, recording.id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (Build.VERSION.SDK_INT >= 31 && alarm.canScheduleExactAlarms()) {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, recording.startsAtMillis, pending)
        } else {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, recording.startsAtMillis, pending)
        }
    }

    fun start(context: Context, id: String) {
        ContextCompat.startForegroundService(context,
            Intent(context, IptvRecordingService::class.java).putExtra(EXTRA_ID, id))
    }

    fun cancel(context: Context, id: String) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, IptvRecordingAlarmReceiver::class.java).putExtra(EXTRA_ID, id)
        val pending = PendingIntent.getBroadcast(context, id.hashCode(), intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (pending != null) { alarm?.cancel(pending); pending.cancel() }
        runCatching { context.startService(Intent(context, IptvRecordingService::class.java)
            .setAction(IptvRecordingService.ACTION_STOP).putExtra(EXTRA_ID, id)) }
    }
}

internal class IptvRecordingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra(IptvRecordingScheduler.EXTRA_ID)?.let { id ->
            IptvRecordingScheduler.start(context, id)
        }
    }
}

/** Records continuous MPEG-TS to the TV's app-owned Movies directory. */
internal class IptvRecordingService : Service() {
    companion object { const val ACTION_STOP = "com.fourseveneightnine.tv.iptv.STOP_RECORDING" }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val http = OkHttpClient.Builder().callTimeout(0, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(IptvRecordingScheduler.EXTRA_ID) ?: return START_NOT_STICKY
        if (intent.action == ACTION_STOP) {
            jobs.remove(id)?.cancel()
            if (jobs.isEmpty()) stopSelf()
            return START_NOT_STICKY
        }
        if (jobs.containsKey(id)) return START_REDELIVER_INTENT
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("iptv-recording", "TV recordings",
            NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(this, "iptv-recording")
            .setSmallIcon(R.drawable.mark_4789)
            .setContentTitle("Recording live TV")
            .setContentText("Saving a channel on this TV")
            .setOngoing(true).build()
        startForeground(4_789, notification)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            record(id)
            jobs.remove(id)
            if (jobs.isEmpty()) stopSelf()
        }
        jobs[id] = job
        job.start()
        return START_REDELIVER_INTENT
    }

    private suspend fun record(id: String) {
        val repo = applicationContext.clientGraph.iptv
        val recording = repo.recording(id) ?: return
        if (recording.status !in setOf(IptvRecordingStatus.SCHEDULED, IptvRecordingStatus.RECORDING)) return
        val channel = repo.state.value.catalog.channels.firstOrNull { it.id == recording.channelId }
        if (channel == null) {
            repo.updateRecording(id, IptvRecordingStatus.FAILED, error = "Channel is no longer available")
            return
        }
        val directory = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        if (directory == null || !directory.exists() && !directory.mkdirs()) {
            repo.updateRecording(id, IptvRecordingStatus.FAILED, error = "Recording storage unavailable")
            return
        }
        var file = File(directory, "$id.ts")
        repo.updateRecording(id, IptvRecordingStatus.RECORDING)
        try {
            val url = repo.playUrl(channel)
            val request = Request.Builder().url(url).apply {
                channel.headers.forEach { (key, value) -> header(key, value) }
            }.build()
            var isHls = false
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Stream HTTP ${response.code}")
                if (url.substringBefore('?').endsWith(".m3u8", ignoreCase = true) ||
                    response.header("Content-Type").orEmpty().contains("mpegurl", ignoreCase = true)) {
                    isHls = true
                } else {
                    val input = response.body?.byteStream() ?: error("Empty stream")
                    java.io.FileOutputStream(file, recording.status == IptvRecordingStatus.RECORDING)
                        .buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var written = 0L
                        while (System.currentTimeMillis() < recording.endsAtMillis) {
                            val size = input.read(buffer)
                            if (size < 0) break
                            output.write(buffer, 0, size)
                            written += size
                            if (written % (16L * 1024 * 1024) < size && StatFs(directory.path).availableBytes < 100L * 1024 * 1024) {
                                error("TV storage is nearly full")
                            }
                        }
                    }
                }
            }
            if (isHls) file = IptvHlsRecorder(http).record(url, channel.headers, directory, id,
                recording.endsAtMillis)
            if (file.length() == 0L) error("The stream returned no video")
            repo.updateRecording(id, IptvRecordingStatus.DONE, fileName = file.name)
        } catch (error: Exception) {
            file.delete()
            File(directory, "$id.mp4").delete()
            val message = error.message?.takeIf { it in setOf("TV storage is nearly full",
                "The stream returned no video", "No new HLS segments arrived") }
                ?: "Recording stopped (${error.javaClass.simpleName})"
            repo.updateRecording(id, IptvRecordingStatus.FAILED, error = message)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
