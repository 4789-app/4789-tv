package com.fourseveneightnine.tv.startup

import com.fourseveneightnine.tv.BuildConfig
import com.fourseveneightnine.tv.client.data.streams.StreamRow
import com.fourseveneightnine.tv.client.data.streams.StreamExpectation
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Response

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
    private const val BENCHMARK_FILE_NAME = "receiver-benchmark.log"
    private const val BENCHMARK_MAX_BYTES = 4 * 1024 * 1024
    private const val BENCHMARK_PENDING_LIMIT = 1024
    internal const val PLAYBACK_FAILURE_FILE_NAME = "receiver-playback-failures.log"
    private const val PLAYBACK_FAILURE_MAX_BYTES = 256 * 1024
    const val CRASH_FILE_NAME = "receiver-last-crash.log"
    const val EXIT_FILE_NAME = "receiver-last-exit.log"

    internal const val MAX_BYTES = 512 * 1024
    private const val COMPACT_TO_BYTES = 384 * 1024
    internal const val MAX_PENDING_LINES = 256
    private const val MAX_TRACE_CHARS = 32 * 1024
    private val session = UUID.randomUUID().toString().take(8)
    private val requestSequence = AtomicLong()
    @Volatile private var sessionMetadata = "build metadata unavailable"

    @Volatile
    private var applicationContext: Context? = null

    @Volatile
    private var previousUncaughtExceptionHandler: Thread.UncaughtExceptionHandler? = null
    private val pendingLines = ArrayDeque<String>()
    private var droppedLines = 0L
    private data class BenchmarkEvent(val timeNs: Long, val stage: String, val detail: String)
    private var benchmarkEvents = ArrayDeque<BenchmarkEvent>()
    private var droppedBenchmarkEvents = 0L
    private var benchmarkSequence = 0L
    private val writeScheduled = AtomicBoolean(false)
    private val timestampFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
    }
    /** Serializes enqueueing with the writer's drain/finally window for crash-flush correctness. */
    private val writeLock = Any()
    @Volatile private var writerThread: Thread? = null
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "4789-diagnostics-writer").apply {
            isDaemon = true
            writerThread = this
        }
    }

    @Synchronized
    fun install(context: Context) {
        if (applicationContext != null) return

        applicationContext = context.applicationContext
        previousUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val detail = buildCrashDetail(thread, throwable)
                // Fatal storage is independent of the event queue and survives later log noise.
                runCatching {
                    File(context.filesDir, CRASH_FILE_NAME).writeText(
                        buildLine("uncaughtException", "retained fatal report $sessionMetadata") +
                            diagnosticSafeText(detail, MAX_TRACE_CHARS) + "\n",
                    )
                }
                record("uncaughtException", detail)
                flushBlocking()
            } finally {
                previousUncaughtExceptionHandler?.uncaughtException(thread, throwable)
            }
        }
        writer.execute {
            runCatching {
                @Suppress("DEPRECATION")
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                sessionMetadata = "app=${context.packageName} version=${info.versionName} " +
                    "build=${info.longVersionCode} sdk=${Build.VERSION.SDK_INT} model=${Build.MODEL} " +
                    "abis=${Build.SUPPORTED_ABIS.joinToString(",")} benchmarkLogging=${BuildConfig.BENCHMARK_LOGGING}"
                record("diagnostics.session", sessionMetadata)
                benchmark("session") { sessionMetadata }
                recordPreviousExits(context)
            }.onFailure { record("diagnostics.metadata.failed", it.javaClass.simpleName) }
        }
    }

    fun record(stage: String, detail: String? = null) {
        val context = applicationContext ?: return
        val line = buildLine(stage, detail)
        if (stage.startsWith("source.") || stage.startsWith("streams.search.") || stage.startsWith("nextup.")) {
            android.util.Log.i("4789Source", line.trimEnd())
        } else if ((stage.startsWith("http.") && detail?.contains("component=playback") == true) ||
            stage.startsWith("pip.") || stage.startsWith("afr.") || stage.startsWith("catalog.bulk.") ||
            stage == "subtitle.auto.loaded" ||
            stage in setOf("exo.bandwidth", "exoplayer.error", "exo.stateChanged", "exo.firstFrameRendered",
                "exo.video.framesDropped", "exo.audio.underrun", "exo.upscale", "playback.open")) {
            android.util.Log.i("4789Playback", line.trimEnd())
        }
        synchronized(writeLock) {
            if (pendingLines.size == MAX_PENDING_LINES) {
                pendingLines.removeFirst()
                droppedLines++
            }
            pendingLines.addLast(line)
            scheduleWrite(context)
        }
    }

    internal inline fun benchmark(stage: String, detail: () -> String) {
        if (BuildConfig.BENCHMARK_LOGGING) recordBenchmark(stage, detail())
    }

    /** Only pass structured facts. Titles, URLs, typed text and raw provider responses stay out. */
    internal fun recordBenchmark(stage: String, detail: String) {
        if (!BuildConfig.BENCHMARK_LOGGING) return
        val context = applicationContext ?: return
        val event = BenchmarkEvent(SystemClock.elapsedRealtimeNanos(), stage, detail)
        synchronized(writeLock) {
            if (benchmarkEvents.size == BENCHMARK_PENDING_LIMIT) {
                benchmarkEvents.removeFirst()
                droppedBenchmarkEvents++
            }
            benchmarkEvents.addLast(event)
            scheduleWrite(context)
        }
    }

    /** The regular HTTP/event queues cannot evict these playback incidents. */
    internal fun playbackFailure(
        stage: String,
        title: String?,
        source: String?,
        positionMs: Long,
        attempt: Long,
        code: String,
        reason: String,
        httpStatus: Int? = null,
        causeClass: String? = null,
        afterFirstFrame: Boolean? = null,
    ) {
        val context = applicationContext ?: return
        val details = playbackFailureDetails(title, source, positionMs, attempt, code, reason, httpStatus,
            causeClass, afterFirstFrame)
        val line = buildLine("playback.failure.$stage", details)
        writer.execute {
            runCatching {
                val file = File(context.filesDir, PLAYBACK_FAILURE_FILE_NAME)
                val incoming = line.toByteArray(Charsets.UTF_8)
                if (file.length() + incoming.size <= PLAYBACK_FAILURE_MAX_BYTES) {
                    file.appendBytes(incoming)
                } else {
                    val tail = readDiagnosticTail(file, PLAYBACK_FAILURE_MAX_BYTES * 3 / 4 - incoming.size)
                    val journal = android.util.AtomicFile(file)
                    val output = journal.startWrite()
                    try {
                        output.write(tail)
                        output.write(incoming)
                        journal.finishWrite(output)
                    } catch (failed: Exception) {
                        journal.failWrite(output)
                        throw failed
                    }
                }
            }.onFailure { record("diagnostics.playback_failure_write_failed", it.javaClass.simpleName) }
        }
    }

    /** Allocate stack details only for errors, never for ordinary input or player ticks. */
    fun failure(stage: String, error: Throwable, detail: String? = null) {
        record(stage, "${detail.orEmpty()} type=${error.javaClass.simpleName}; ${error.stackTraceToString()}")
    }

    /** Status and latency only. Request paths, query strings, headers and bodies are never read. */
    fun networkEvents(component: String): EventListener.Factory = EventListener.Factory { call ->
        if (applicationContext == null) return@Factory EventListener.NONE
        object : EventListener() {
            private val id = requestSequence.incrementAndGet()
            private var startedAt = 0L
            private var status = 0
            private val label = "request=$id component=$component method=${call.request().method} " +
                "host=${call.request().url.host}"

            override fun callStart(call: Call) {
                startedAt = SystemClock.elapsedRealtime()
                record("http.start", label)
            }

            override fun responseHeadersEnd(call: Call, response: Response) {
                status = response.code
            }

            override fun callEnd(call: Call) {
                record("http.complete", "$label status=$status elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
            }

            override fun callFailed(call: Call, ioe: IOException) {
                val detail = "$label status=$status elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
                if (call.isCanceled()) record("http.cancelled", detail)
                else failure("http.failed", ioe, detail)
            }
        }
    }

    fun read(): String {
        val context = applicationContext ?: return "Diagnostics are not initialized.\n"
        return runCatching {
            writer.submit<String> {
                writePending(context)
                if (BuildConfig.BENCHMARK_LOGGING) writeBenchmark(context)
                buildString {
                    append("4789 TV diagnostic report | session=$session\n")
                    append(diagnosticSafeText(sessionMetadata))
                    append('\n')
                    for (name in listOf(FILE_NAME, CRASH_FILE_NAME, EXIT_FILE_NAME)) {
                        val file = File(context.filesDir, name)
                        if (file.isFile) {
                            append("\n--- $name ---\n")
                            // Older installed versions may have recorded unredacted entries.
                            append(diagnosticSafeText(readDiagnosticTail(file, MAX_BYTES).toString(Charsets.UTF_8), MAX_BYTES))
                        }
                    }
                    val failures = File(context.filesDir, PLAYBACK_FAILURE_FILE_NAME)
                    if (failures.isFile) {
                        append("\n--- $PLAYBACK_FAILURE_FILE_NAME ---\n")
                        append(readDiagnosticTail(failures, PLAYBACK_FAILURE_MAX_BYTES).toString(Charsets.UTF_8))
                    }
                    if (BuildConfig.BENCHMARK_LOGGING) {
                        val file = File(context.filesDir, BENCHMARK_FILE_NAME)
                        if (file.isFile) {
                            append("\n--- $BENCHMARK_FILE_NAME ---\n")
                            append(readDiagnosticTail(file, BENCHMARK_MAX_BYTES).toString(Charsets.UTF_8))
                        }
                    }
                }
            }.get(3, TimeUnit.SECONDS)
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
        append(" | session=")
        append(session)
        append(" | uptimeMs=")
        append(SystemClock.elapsedRealtime())
        append(" | thread=")
        append(diagnosticSafeText(Thread.currentThread().name, 120))
        append(" | ")
        append(diagnosticSafeText(stage, 120).replace('\n', ' ').replace('\r', ' '))
        if (!detail.isNullOrBlank()) {
            append(" | ")
            append(diagnosticSafeText(detail).replace('\n', ' ').replace('\r', ' '))
        }
        append('\n')
    }

    private fun scheduleWrite(context: Context) {
        if (!writeScheduled.compareAndSet(false, true)) return
        writer.execute {
            try {
                writePending(context)
                if (BuildConfig.BENCHMARK_LOGGING) writeBenchmark(context)
            } finally {
                synchronized(writeLock) {
                    // Yield between batches so an export/crash barrier cannot be starved by
                    // continuous native warnings. Enqueue and reschedule share this lock.
                    writeScheduled.set(false)
                    if (pendingLines.isNotEmpty() || benchmarkEvents.isNotEmpty()) scheduleWrite(context)
                }
            }
        }
    }

    private fun flushBlocking() {
        val context = applicationContext ?: return
        if (Thread.currentThread() === writerThread) {
            writePending(context)
            if (BuildConfig.BENCHMARK_LOGGING) writeBenchmark(context)
            return
        }
        runCatching {
            val barrier = synchronized(writeLock) {
                scheduleWrite(context)
                // Queue the barrier after the scheduled writer. Never wait while holding
                // writeLock: the writer must be able to drain and release it first.
                writer.submit {
                    writePending(context)
                    if (BuildConfig.BENCHMARK_LOGGING) writeBenchmark(context)
                }
            }
            barrier.get(2, TimeUnit.SECONDS)
        }
    }

    private fun writePending(context: Context) {
        val lines = synchronized(writeLock) {
            buildString {
                while (true) {
                    val line = pendingLines.pollFirst() ?: break
                    append(line)
                }
                if (droppedLines > 0) {
                    append(buildLine("diagnostics.dropped", "count=$droppedLines"))
                    droppedLines = 0
                }
            }
        }
        if (lines.isEmpty()) return
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            val incoming = diagnosticUtf8Tail(lines.toByteArray(Charsets.UTF_8), MAX_BYTES)
            if (file.length() + incoming.size > MAX_BYTES) {
                // Leave room for appends. Keeping a full file made each small write recopy the tail.
                val existing = readDiagnosticTail(file, COMPACT_TO_BYTES - incoming.size)
                file.outputStream().use { output ->
                    output.write(existing)
                    output.write(incoming)
                }
            } else {
                // Normal batches append only new bytes, rather than rereading and rewriting
                // the complete breadcrumb file for every playback callback.
                file.appendBytes(incoming)
            }
        }
    }

    private fun writeBenchmark(context: Context) {
        val (events, dropped) = synchronized(writeLock) {
            if (benchmarkEvents.isEmpty() && droppedBenchmarkEvents == 0L) return
            val drained = benchmarkEvents
            val count = droppedBenchmarkEvents
            benchmarkEvents = ArrayDeque()
            droppedBenchmarkEvents = 0
            drained to count
        }
        fun line(timeNs: Long, stage: String, detail: String): String {
            val safe = diagnosticSafeText(detail, 512).replace('\n', ' ').replace('\r', ' ')
            return "session=$session seq=${++benchmarkSequence} elapsedNs=$timeNs stage=$stage $safe\n"
        }
        runCatching {
            val file = File(context.filesDir, BENCHMARK_FILE_NAME)
            val lines = buildString {
                if (dropped > 0) {
                    append(line(events.firstOrNull()?.timeNs ?: SystemClock.elapsedRealtimeNanos(),
                        "diagnostics.dropped", "count=$dropped"))
                }
                for (event in events) append(line(event.timeNs, event.stage, event.detail))
            }
            val incoming = lines.toByteArray(Charsets.UTF_8)
            if (file.length() + incoming.size > BENCHMARK_MAX_BYTES) {
                val keep = BENCHMARK_MAX_BYTES * 3 / 4 - incoming.size
                val tail = readDiagnosticTail(file, keep)
                file.outputStream().use { output ->
                    output.write(tail)
                    output.write(incoming)
                }
            } else file.appendBytes(incoming)
        }
    }

    /** Android records native crashes, ANRs and OS kills even when no Java handler ran. */
    private fun recordPreviousExits(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        val preferences = context.getSharedPreferences("receiver_diagnostics", Context.MODE_PRIVATE)
        val seen = preferences.getLong("last_exit_timestamp", 0L)
        val exits = manager.getHistoricalProcessExitReasons(context.packageName, 0, 5)
            .filter { it.timestamp > seen }.sortedBy { it.timestamp }
        for (exit in exits) {
            val detail = "timestamp=${exit.timestamp} pid=${exit.pid} reason=${exit.reason} " +
                "status=${exit.status} importance=${exit.importance} pssKb=${exit.pss} rssKb=${exit.rss} " +
                "description=${exit.description.orEmpty()}"
            record("process.exit", detail)
            if (exit.reason == android.app.ApplicationExitInfo.REASON_ANR ||
                exit.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE) {
                // Android 12+ native tombstones are protobuf, not UTF-8 text. Keep the exit
                // context readable; the OS crash log supplies the native stack for ADB reports.
                val trace = if (exit.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE) {
                    "Native crash: collect the Android crash log (adb logcat -b crash).\n"
                } else runCatching {
                    exit.traceInputStream?.reader(Charsets.UTF_8)?.use { input ->
                        val buffer = CharArray(2048)
                        buildString {
                            while (length < MAX_TRACE_CHARS) {
                                val count = input.read(buffer, 0, minOf(buffer.size, MAX_TRACE_CHARS - length))
                                if (count < 0) break
                                append(buffer, 0, count)
                            }
                        }
                    }.orEmpty()
                }.getOrElse { "Trace unavailable: ${it.javaClass.simpleName}" }
                File(context.filesDir, EXIT_FILE_NAME).writeText(
                    buildLine("process.exit.retained", detail) +
                        diagnosticSafeText(trace, MAX_TRACE_CHARS) + "\n",
                )
            }
        }
        exits.lastOrNull()?.let { preferences.edit().putLong("last_exit_timestamp", it.timestamp).commit() }
    }

    private fun buildCrashDetail(thread: Thread, throwable: Throwable): String {
        val stackTrace = StringWriter().also { writer ->
            throwable.printStackTrace(PrintWriter(writer))
        }.toString()
        return "thread=${thread.name} sdk=${Build.VERSION.SDK_INT} model=${Build.MODEL}; $stackTrace"
    }

    private fun timestamp(): String =
        checkNotNull(timestampFormat.get()).format(Date())
}

/** Context for a source attempt. Never include raw IDs, URLs, headers, hashes or provider file IDs. */
internal fun sourceDiagnosticDetails(
    row: StreamRow?, expected: StreamExpectation?, detail: String, addonName: String? = null,
): String {
    fun field(value: String?): String = diagnosticSafeText(value.orEmpty(), 240)
        .replace('\n', ' ').replace('\r', ' ').replace('"', '\'')
    return "title=\"${field(expected?.title)}\" season=${expected?.season ?: "none"} " +
        "episode=${expected?.episode ?: "none"} addon=\"${field(addonName ?: row?.addonName)}\" " +
        "label=\"${field(row?.title)}\" quality=\"${field(row?.quality)}\" service=\"${field(row?.cachedHint?.service)}\" " +
        "source=${row?.playbackKey()?.take(12) ?: "none"} " + diagnosticSafeText(detail, 1000)
}

internal fun playbackFailureDetails(
    title: String?, source: String?, positionMs: Long, attempt: Long, code: String, reason: String,
    httpStatus: Int?, causeClass: String?, afterFirstFrame: Boolean?,
): String {
    fun field(value: String?, length: Int): String =
        diagnosticSafeText(value.orEmpty(), length).replace('\n', ' ').replace('\r', ' ').replace('"', '\'')
    return "attempt=$attempt positionMs=${positionMs.coerceAtLeast(0)} code=${field(code, 84)} " +
        "http=${httpStatus ?: "-"} cause=${field(causeClass, 72)} afterFirstFrame=${afterFirstFrame ?: "-"} " +
        "title=\"${field(title, 180)}\" source=\"${field(source, 120)}\" reason=\"${field(reason, 320)}\""
}

private val diagnosticUrls = Regex("(?i)\\b(?:https?|rtsp|file|content|stremio)(?:://|%3a%2f%2f)[^\\s<>\\\"']+")
private val diagnosticHeaders = Regex("(?im)\\b(authorization|cookie|set-cookie)\\b[\\\"']?\\s*[:=]\\s*[^\\r\\n]+")
private val diagnosticSecrets = Regex(
    "(?i)\\b(password|passwd|api[_-]?key|access[_-]?token|refresh[_-]?token|token|secret|pin)\\b[\\\"']?\\s*[:=]\\s*(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s;,]+)",
)
private val diagnosticBearer = Regex("(?i)\\bBearer\\s+[^\\s;,]+")

/** Apply redaction before the output ceiling, including URLs with credentials in their paths. */
internal fun diagnosticSafeText(value: String, maximumChars: Int = 8 * 1024): String {
    val bounded = value.take(maximumChars * 2)
    val withoutUrls = diagnosticUrls.replace(bounded, "<url>")
    val withoutBearer = diagnosticBearer.replace(withoutUrls, "Bearer <redacted>")
    val withoutHeaders = diagnosticHeaders.replace(withoutBearer, "$1=<redacted>")
    return diagnosticSecrets.replace(withoutHeaders, "$1=<redacted>").take(maximumChars)
}

/** Keep complete newest UTF-8 lines; never start halfway through a multibyte character. */
internal fun diagnosticUtf8Tail(bytes: ByteArray, maximumBytes: Int): ByteArray {
    if (maximumBytes <= 0) return ByteArray(0)
    if (bytes.size <= maximumBytes) return bytes
    var start = bytes.size - maximumBytes
    while (start < bytes.size && bytes[start] != '\n'.code.toByte()) start++
    return bytes.copyOfRange(minOf(start + 1, bytes.size), bytes.size)
}

private fun readDiagnosticTail(file: File, maximumBytes: Int): ByteArray {
    if (!file.isFile || maximumBytes <= 0) return ByteArray(0)
    return RandomAccessFile(file, "r").use { input ->
        // Include one extra byte so the tail helper knows whether the first line was cut.
        val count = minOf(input.length(), maximumBytes.toLong() + 1).toInt()
        input.seek(input.length() - count)
        val bytes = ByteArray(count)
        input.readFully(bytes)
        diagnosticUtf8Tail(bytes, maximumBytes)
    }
}
