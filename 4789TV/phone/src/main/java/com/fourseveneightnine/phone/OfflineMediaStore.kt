package com.fourseveneightnine.phone

import android.content.Context
import android.net.Uri
import android.os.storage.StorageManager
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.coroutineContext

internal object OfflineMediaPolicy {
    const val RESERVE_BYTES = 64L * 1024 * 1024
    const val MAXIMUM_BYTES = 50L * 1024 * 1024 * 1024

    fun safeName(titleID: String): String = MessageDigest.getInstance("SHA-256")
        .digest(titleID.encodeToByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(32) + ".media"

    fun canStart(declaredBytes: Long, usableBytes: Long): Boolean =
        declaredBytes in 1..MAXIMUM_BYTES && usableBytes - RESERVE_BYTES >= declaredBytes

    /**
     * Where an interrupted download may start again, or 0 to start over.
     *
     * Resuming is only allowed on positive evidence that the bytes on the server are still the ones
     * already written. A missing fingerprint on either side, a changed fingerprint, or a partial
     * file that is somehow not smaller than the whole file all mean: start from zero. Appending to
     * bytes that moved would produce a file that passes its size check and plays as rubbish.
     */
    fun resumeOffset(
        partialBytes: Long,
        declaredTotalBytes: Long,
        storedFingerprint: String?,
        currentFingerprint: String?,
    ): Long {
        if (partialBytes <= 0 || declaredTotalBytes <= 0) return 0
        if (partialBytes >= declaredTotalBytes) return 0
        if (storedFingerprint.isNullOrBlank() || currentFingerprint.isNullOrBlank()) return 0
        if (storedFingerprint != currentFingerprint) return 0
        return partialBytes
    }

    /** The first byte offset a `Content-Range: bytes 100-199/500` header describes, or null. */
    fun contentRangeStart(header: String?): Long? {
        val value = header?.trim() ?: return null
        if (!value.startsWith("bytes ")) return null
        return value.removePrefix("bytes ").substringBefore('-').trim().toLongOrNull()
    }

    /** The total size a `Content-Range: bytes 100-199/500` header declares, or null for `*`. */
    fun contentRangeTotal(header: String?): Long? {
        val value = header?.trim() ?: return null
        if (!value.startsWith("bytes ")) return null
        return value.substringAfter('/', "").trim().toLongOrNull()
    }

    fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024))
        else -> String.format(Locale.ROOT, "%.1f GiB", bytes / (1024.0 * 1024 * 1024))
    }
}

internal data class OfflineMediaSummary(val count: Int, val bytes: Long)

/**
 * What one attempt at a background download achieved.
 *
 * [bytesDone] and [fingerprint] are what makes the next attempt cheap: they say where to start
 * again and how to prove the bytes did not move. Neither is a link.
 */
internal data class OfflineDownloadOutcome(
    val completed: Boolean,
    val bytesDone: Long,
    val declaredBytes: Long,
    val fingerprint: String?,
    val detail: String,
)

internal class OfflineMediaStore(
    context: Context,
    private val remoteClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, "offline-media-v1")

    suspend fun import(titleID: String, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val declared = appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            ?: return@withContext false
        val input = appContext.contentResolver.openInputStream(uri) ?: return@withContext false
        input.use { copyVerified(titleID, declared, it) { coroutineContext.ensureActive(); true } }
    }

    suspend fun importRemote(titleID: String, url: String): Boolean {
        if (!RemoteStreamPolicy.isPlayableURL(url)) return false
        return suspendCancellableCoroutine { continuation ->
            val call = remoteClient.newCall(Request.Builder().url(url).get().build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(false)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val result = runCatching {
                            if (it.code != 200) return@runCatching false
                            val body = requireNotNull(it.body) { "missing_body" }
                            val declared = body.contentLength()
                            copyVerified(titleID, declared, body.byteStream()) { continuation.isActive }
                        }.getOrDefault(false)
                        if (continuation.isActive) continuation.resume(result)
                    }
                }
            })
        }
    }

    /**
     * One attempt at a background download, continuing an earlier one when that is provably safe.
     *
     * The url arrives as an argument and leaves with this call. It is never written down, never
     * logged, and never handed back to the caller.
     */
    suspend fun downloadRemote(
        titleID: String,
        url: String,
        knownFingerprint: String?,
        knownTotalBytes: Long,
    ): OfflineDownloadOutcome = withContext(Dispatchers.IO) {
        val alreadyWritten = partialBytes(titleID)
        if (!RemoteStreamPolicy.isPlayableURL(url)) {
            return@withContext failedAttempt(alreadyWritten, knownTotalBytes, knownFingerprint, "unusable_link")
        }
        val wantsResume = OfflineMediaPolicy.resumeOffset(
            partialBytes = alreadyWritten,
            declaredTotalBytes = knownTotalBytes,
            storedFingerprint = knownFingerprint,
            currentFingerprint = knownFingerprint,
        ) > 0
        val builder = Request.Builder().url(url).get()
        if (wantsResume) {
            builder.header("Range", "bytes=$alreadyWritten-")
            builder.header("If-Range", requireNotNull(knownFingerprint))
        }
        val request = builder.build()
        suspendCancellableCoroutine { continuation ->
            val call = remoteClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resume(
                            failedAttempt(
                                partialBytes(titleID),
                                knownTotalBytes,
                                knownFingerprint,
                                "network_interrupted",
                            ),
                        )
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val outcome = runCatching {
                            consume(titleID, it, alreadyWritten, knownFingerprint) {
                                continuation.isActive
                            }
                        }.getOrElse { failure ->
                            failedAttempt(
                                partialBytes(titleID),
                                knownTotalBytes,
                                knownFingerprint,
                                failure.message ?: "download_failed",
                            )
                        }
                        if (continuation.isActive) continuation.resume(outcome)
                    }
                }
            })
        }
    }

    /** How many bytes of an interrupted download are already on disk. */
    fun partialBytes(titleID: String): Long = partialFile(titleID)
        .takeIf(File::isFile)
        ?.length()
        ?: 0

    fun playbackUri(titleID: String): Uri? = file(titleID)
        .takeIf { it.isFile && it.length() > 0 }
        ?.let(Uri::fromFile)

    fun remove(titleID: String): Boolean {
        val file = file(titleID)
        return !file.exists() || file.delete()
    }

    /**
     * Drop half-written files left by a torn-down import.
     *
     * A title in [keepTitleIDs] still has a live queue entry, so its half-written file is not
     * rubbish — it is the head start the next attempt resumes from, and deleting it would throw
     * away work the viewer already waited for.
     */
    fun cleanInterruptedImports(keepTitleIDs: Set<String> = emptySet()): Int {
        if (!directory.isDirectory) return 0
        val protectedNames = keepTitleIDs.map { OfflineMediaPolicy.safeName(it) + ".partial" }.toSet()
        var removed = 0
        directory.listFiles()?.forEach { candidate ->
            val disposable = candidate.name.endsWith(".partial") && candidate.name !in protectedNames
            if (disposable && candidate.delete()) removed += 1
        }
        return removed
    }

    fun summary(): OfflineMediaSummary {
        val copies = managedFiles(".media")
        return OfflineMediaSummary(copies.size, copies.sumOf(File::length))
    }

    fun removeAll(): Boolean {
        val candidates = managedFiles(".media", ".partial")
        var removedEveryCandidate = true
        candidates.forEach { candidate ->
            if (candidate.exists() && !candidate.delete()) removedEveryCandidate = false
        }
        return removedEveryCandidate && managedFiles(".media", ".partial").isEmpty()
    }

    private fun file(titleID: String): File = File(directory, OfflineMediaPolicy.safeName(titleID))

    private fun partialFile(titleID: String): File =
        File(directory, OfflineMediaPolicy.safeName(titleID) + ".partial")

    private fun failedAttempt(
        bytesDone: Long,
        declaredBytes: Long,
        fingerprint: String?,
        detail: String,
    ) = OfflineDownloadOutcome(
        completed = false,
        bytesDone = bytesDone.coerceAtLeast(0),
        declaredBytes = declaredBytes.coerceAtLeast(0),
        fingerprint = fingerprint,
        detail = detail,
    )

    private fun consume(
        titleID: String,
        response: Response,
        alreadyWritten: Long,
        knownFingerprint: String?,
        isActive: () -> Boolean,
    ): OfflineDownloadOutcome {
        val fingerprint = response.header("ETag") ?: response.header("Last-Modified")
        val body = requireNotNull(response.body) { "missing_body" }
        val plan: Pair<Long, Long> = when (response.code) {
            // The server ignored the range, or its If-Range check said the bytes moved. Start over.
            200 -> 0L to body.contentLength()
            206 -> {
                val header = response.header("Content-Range")
                val start = OfflineMediaPolicy.contentRangeStart(header) ?: -1L
                val total = OfflineMediaPolicy.contentRangeTotal(header) ?: -1L
                val offset = OfflineMediaPolicy.resumeOffset(
                    partialBytes = alreadyWritten,
                    declaredTotalBytes = total,
                    storedFingerprint = knownFingerprint,
                    currentFingerprint = fingerprint,
                )
                if (offset <= 0 || start != offset) {
                    partialFile(titleID).delete()
                    return failedAttempt(0, total, fingerprint, "range_rejected")
                }
                offset to total
            }
            else -> return failedAttempt(alreadyWritten, 0, knownFingerprint, "http_${response.code}")
        }
        val (startOffset, declaredTotal) = plan
        val completed = copyVerified(
            titleID = titleID,
            declaredBytes = declaredTotal,
            input = body.byteStream(),
            startOffset = startOffset,
            keepPartialOnFailure = true,
            isActive = isActive,
        )
        return OfflineDownloadOutcome(
            completed = completed,
            bytesDone = if (completed) declaredTotal else partialBytes(titleID),
            declaredBytes = declaredTotal.coerceAtLeast(0),
            fingerprint = fingerprint,
            detail = if (completed) "complete" else "interrupted",
        )
    }

    private fun managedFiles(vararg suffixes: String): List<File> = directory
        .listFiles()
        .orEmpty()
        .filter { candidate -> candidate.isFile && suffixes.any(candidate.name::endsWith) }

    /**
     * Write [declaredBytes] bytes and only then publish the file.
     *
     * [startOffset] continues a file that already holds exactly that many bytes; the size check
     * still covers the whole file end to end. [keepPartialOnFailure] is what makes a background
     * download restartable — a torn-down attempt leaves its bytes behind on purpose.
     */
    private fun copyVerified(
        titleID: String,
        declaredBytes: Long,
        input: InputStream,
        startOffset: Long = 0,
        keepPartialOnFailure: Boolean = false,
        isActive: () -> Boolean,
    ): Boolean {
        directory.mkdirs()
        if (declaredBytes !in 1..OfflineMediaPolicy.MAXIMUM_BYTES) return false
        if (startOffset < 0 || startOffset >= declaredBytes) return false
        val storage = appContext.getSystemService(StorageManager::class.java)
        val allocatable = runCatching {
            storage.getAllocatableBytes(storage.getUuidForPath(directory))
        }.getOrNull() ?: return false
        if (!OfflineMediaPolicy.canStart(declaredBytes - startOffset, allocatable)) return false
        val finalFile = file(titleID)
        val partial = partialFile(titleID)
        if (startOffset == 0L) {
            partial.delete()
        } else if (partial.length() != startOffset) {
            return false
        }
        val result = runCatching {
            input.use { source ->
                FileOutputStream(partial, startOffset > 0).buffered().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var total = startOffset
                    while (true) {
                        require(isActive()) { "cancelled" }
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= declaredBytes && total <= OfflineMediaPolicy.MAXIMUM_BYTES)
                        output.write(buffer, 0, count)
                    }
                    output.flush()
                    require(total == declaredBytes)
                }
            }
            require(isActive()) { "cancelled" }
            Files.move(
                partial.toPath(),
                finalFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            true
        }.getOrDefault(false)
        if (!result && !keepPartialOnFailure) partial.delete()
        return result
    }
}
