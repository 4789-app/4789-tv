package com.fourseveneightnine.tv.startup

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Small startup/crash breadcrumb log for sideloaded TV diagnostics.
 *
 * The file is intentionally kept in the app's private storage so it is not exposed to other
 * apps. A debug APK owner can retrieve it with `adb shell run-as` after reproducing a crash. Native
 * warning callbacks can arrive many times per second, so normal records are queued and written on
 * a daemon thread; callbacks only hold the queue lock and never wait for private-storage I/O.
 */
object ReceiverDiagnostics {
    const val FILE_NAME = "receiver-diagnostics.log"

    private const val MAX_BYTES = 96 * 1024L
    private const val RETAIN_BYTES_AFTER_TRIM = 64 * 1024

    @Volatile
    private var applicationContext: Context? = null

    @Volatile
    private var previousUncaughtExceptionHandler: Thread.UncaughtExceptionHandler? = null
    private val pendingLines = ConcurrentLinkedQueue<String>()
    private val writeScheduled = AtomicBoolean(false)
    /** Serializes enqueueing with the writer's drain/finally window for crash-flush correctness. */
    private val writeLock = Any()
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "4789-diagnostics-writer").apply { isDaemon = true }
    }

    @Synchronized
    fun install(context: Context) {
        if (applicationContext != null) return

        applicationContext = context.applicationContext
        previousUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            record("uncaughtException", buildCrashDetail(thread, throwable))
            flushBlocking()
            previousUncaughtExceptionHandler?.uncaughtException(thread, throwable)
        }
    }

    fun record(stage: String, detail: String? = null) {
        val context = applicationContext ?: return
        synchronized(writeLock) {
            pendingLines.add(buildLine(stage, detail))
            scheduleWrite(context)
        }
    }

    fun read(): String {
        val context = applicationContext ?: return "Diagnostics are not initialized.\n"
        flushBlocking()
        return runCatching {
            File(context.filesDir, FILE_NAME).takeIf { it.exists() }?.readText()
                ?: "No diagnostic entries have been recorded.\n"
        }.getOrElse { error ->
            "Unable to read diagnostics: ${error::class.java.simpleName}\n"
        }
    }

    /** Synchronously drains the pending queue so a native SIGABRT cannot lose breadcrumbs. */
    fun flush() {
        val context = applicationContext ?: return
        flushBlocking()
    }

    private fun buildLine(stage: String, detail: String?): String = buildString {
        append(timestamp())
        append(" | ")
        append("pid=")
        append(android.os.Process.myPid())
        append(" | thread=")
        append(Thread.currentThread().name)
        append(" | ")
        append(stage.replace('\n', ' ').replace('\r', ' '))
        if (!detail.isNullOrBlank()) {
            append(" | ")
            append(detail.replace('\n', ' ').replace('\r', ' '))
        }
        append('\n')
    }

    private fun scheduleWrite(context: Context) {
        if (!writeScheduled.compareAndSet(false, true)) return
        writer.execute {
            var done = false
            while (!done) {
                writePending(context)
                synchronized(writeLock) {
                    if (pendingLines.isEmpty()) {
                        writeScheduled.set(false)
                        // A record may arrive immediately after the first empty check. Recheck
                        // while holding the queue lock so it is either drained here or schedules
                        // a later writer after this one has released the lock.
                        done = pendingLines.isEmpty()
                        if (!done) writeScheduled.set(true)
                    }
                }
            }
        }
    }

    private fun flushBlocking() {
        val context = applicationContext ?: return
        runCatching {
            val barrier = synchronized(writeLock) {
                scheduleWrite(context)
                // Queue the barrier after the scheduled writer. Never wait while holding
                // writeLock: the writer must be able to drain and release it first.
                writer.submit {
                    writePending(context)
                }
            }
            barrier.get(2, TimeUnit.SECONDS)
        }
    }

    private fun writePending(context: Context) {
        val lines = synchronized(writeLock) {
            buildString {
                while (true) {
                    val line = pendingLines.poll() ?: break
                    append(line)
                }
            }
        }
        if (lines.isEmpty()) return
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            val existing = if (file.length() > MAX_BYTES) {
                file.readText().takeLast(RETAIN_BYTES_AFTER_TRIM)
            } else {
                file.takeIf { it.exists() }?.readText().orEmpty()
            }
            file.writeText(existing + lines)
        }
    }

    private fun buildCrashDetail(thread: Thread, throwable: Throwable): String {
        val stackTrace = StringWriter().also { writer ->
            throwable.printStackTrace(PrintWriter(writer))
        }.toString()
        return "thread=${thread.name} sdk=${Build.VERSION.SDK_INT} model=${Build.MODEL}; $stackTrace"
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date())
}
