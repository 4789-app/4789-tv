package com.fourseveneightnine.tv.client.data.addons

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer

/** A non-2xx answer. The status code is the only part that is safe to record. */
class HttpStatusException(val code: Int) : Exception("http_$code")

/** What one fetch came back with. [notModified] means the cached copy is still current. */
data class HttpBody(
    val body: String,
    val etag: String?,
    val notModified: Boolean = false,
)

/**
 * Dumb file cache: one file per URL, ETag on the first line, the fetch time on the second, the
 * body after that. Same approach as `TVEnrichmentDiskCache` in `:app` — a database for a dozen
 * JSON blobs would be a database to migrate later.
 *
 * The file is named by a SHA-256 of the URL, so an add-on URL carrying a credential never appears
 * as a filename.
 */
class HttpDiskCache(
    private val directory: File,
    private val maximumEntries: Int = 512,
    private val maximumBytes: Long = 64L * 1_024 * 1_024,
) {
    init {
        require(maximumEntries > 0 && maximumBytes > 0)
        synchronized(diskLock) { runCatching { trim() } }
    }

    data class Entry(val body: String, val etag: String?, val storedAtMillis: Long)

    fun read(url: String): Entry? = synchronized(diskLock) {
        runCatching {
            val file = fileFor(url)
            if (!file.isFile) return@runCatching null
            val text = file.readText()
            val firstBreak = text.indexOf('\n')
            val secondBreak = text.indexOf('\n', firstBreak + 1)
            if (firstBreak < 0 || secondBreak < 0) return@runCatching null
            val entry = Entry(
                body = text.substring(secondBreak + 1),
                etag = text.substring(0, firstBreak).takeIf(String::isNotEmpty),
                storedAtMillis = text.substring(firstBreak + 1, secondBreak).toLongOrNull() ?: 0L,
            )
            // LRU recency is separate from the fetch age used by the 24-hour TTL.
            runCatching { file.setLastModified(System.currentTimeMillis()) }
            entry
        }.getOrNull()
    }

    fun write(url: String, body: String, etag: String?, storedAtMillis: Long) {
        synchronized(diskLock) {
            runCatching {
                val bytes = "${etag.orEmpty()}\n$storedAtMillis\n$body".toByteArray()
                if (bytes.size.toLong() > maximumBytes) return@runCatching
                directory.mkdirs()
                val file = fileFor(url)
                // Write beside, then rename. A half-written cache file that parses is worse than none.
                val temp = File(directory, "${file.name}.tmp")
                try {
                    temp.writeBytes(bytes)
                    if (!temp.renameTo(file)) return@runCatching
                } finally {
                    temp.delete()
                }
                trim(file)
            }
        }
    }

    /** A 304 means the body is unchanged, so only the age resets. */
    fun touch(url: String, storedAtMillis: Long) {
        synchronized(diskLock) {
            val entry = read(url) ?: return
            write(url, entry.body, entry.etag, storedAtMillis)
        }
    }

    fun clear() {
        synchronized(diskLock) {
            runCatching { directory.listFiles()?.forEach(File::delete) }
        }
    }

    /** Called under the shared lock, before a write starts or after its staging file is closed. */
    private fun trim(protectedFile: File? = null) {
        val files = directory.listFiles()?.filter(File::isFile).orEmpty()
        // All cache instances share the lock, so no writer can own one of these staging files.
        files.filter { STAGING_FILE_NAME.matches(it.name) }.forEach(File::delete)
        val entries = files.filter { CACHE_FILE_NAME.matches(it.name) }
        var count = entries.size
        var bytes = entries.sumOf(File::length)
        if (count <= maximumEntries && bytes <= maximumBytes) return
        for (file in entries.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })) {
            if (count <= maximumEntries && bytes <= maximumBytes) break
            if (file == protectedFile) continue
            val size = file.length()
            if (file.delete()) {
                count--
                bytes -= size
            }
        }
    }

    private fun fileFor(url: String): File = File(directory, sha256(url))

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        // Settings reloads can leave an older StremioClient using the same cache directory.
        val diskLock = Any()
        val CACHE_FILE_NAME = Regex("[0-9a-f]{64}")
        val STAGING_FILE_NAME = Regex("[0-9a-f]{64}\\.tmp")
    }
}

/**
 * One cancellable GET with a hard byte ceiling.
 *
 * Redirects are off. An add-on route can carry a credential in its query, and a redirect would
 * hand that query to whatever host the first hop names.
 */
suspend fun OkHttpClient.getText(
    url: String,
    maximumBytes: Int,
    etag: String? = null,
    headers: Map<String, String> = emptyMap(),
): HttpBody {
    require(maximumBytes > 0) { "maximum_bytes" }
    val request = Request.Builder()
        .url(url)
        .get()
        .header("Accept", "application/json")
        .apply {
            etag?.takeIf(String::isNotBlank)?.let { header("If-None-Match", it) }
            headers.forEach { (name, value) -> header(name, value) }
        }
        .build()
    return suspendCancellableCoroutine { continuation ->
        val call = newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (it.code == 304) {
                            if (continuation.isActive) {
                                continuation.resumeWith(
                                    Result.success(HttpBody("", it.header("ETag"), notModified = true)),
                                )
                            }
                            return
                        }
                        if (it.code !in 200..299) throw HttpStatusException(it.code)
                        val body = it.body ?: throw IllegalArgumentException("empty_body")
                        if (body.contentLength() > maximumBytes) throw IllegalArgumentException("response_too_large")
                        val text = body.readBoundedUtf8(maximumBytes)
                        if (continuation.isActive) {
                            continuation.resumeWith(
                                Result.success(
                                    HttpBody(text, it.header("ETag")),
                                ),
                            )
                        }
                    }
                } catch (failure: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        })
    }
}

/** A raw answer: the status code and the body, with nothing thrown. */
data class HttpTextResponse(val code: Int, val body: String, val location: String? = null) {
    val successful: Boolean get() = code in 200..299
}

/**
 * Send any request and read the body, whatever the status.
 *
 * Unlike [getText] this never throws on a non-2xx, because a debrid API answers in status codes
 * the caller has to read: TorBox says "not cached" with a 409, and Real-Debrid says "bad token"
 * with a 401. Turning those into exceptions would throw away the only thing they said.
 */
suspend fun OkHttpClient.send(request: Request, maximumBytes: Int): HttpTextResponse {
    require(maximumBytes > 0) { "maximum_bytes" }
    return suspendCancellableCoroutine { continuation ->
        val call = newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        val text = it.body?.readBoundedUtf8(maximumBytes).orEmpty()
                        if (continuation.isActive) {
                            continuation.resumeWith(
                                Result.success(HttpTextResponse(it.code, text, it.header("Location"))),
                            )
                        }
                    }
                } catch (failure: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        })
    }
}

private fun ResponseBody.readBoundedUtf8(maximumBytes: Int): String {
    val source = source()
    val bytes = Buffer()
    try {
        while (source.read(bytes, minOf(8_192L, maximumBytes.toLong() + 1 - bytes.size)) != -1L) {
            require(bytes.size <= maximumBytes) { "response_too_large" }
        }
        return bytes.readUtf8()
    } finally {
        bytes.clear()
    }
}

/** The client the TV uses when nothing else is injected. */
fun defaultAddonHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .callTimeout(24, TimeUnit.SECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

/** A short, value-free label for a failure, safe to store in add-on health. */
fun Throwable.healthLabel(): String = when (this) {
    is HttpStatusException -> "http_$code"
    else -> this::class.java.simpleName
}

/** A bounded playback response. Kept in memory only; URLs and headers are never logged. */
internal data class MediaPrefix(
    val code: Int,
    val type: String,
    val totalBytes: Long?,
    val filename: String?,
    val bytes: ByteArray,
    val finalURL: String,
    val requestHeaders: Map<String, String>,
    val rangeStart: Long?,
)

internal suspend fun OkHttpClient.mediaPrefix(url: String, headers: Map<String, String>, range: Boolean = true): MediaPrefix =
    suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url).get().header("User-Agent", "Mozilla/5.0")
            .apply { if (range) header("Range", "bytes=0-65535") }
            .apply { headers.forEach { (name, value) -> header(name, value) } }.build()
        val call = newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        val type = it.header("Content-Type").orEmpty().lowercase(java.util.Locale.ROOT)
                        val output = ByteArrayOutputStream()
                        if (it.code in 200..299 && !type.contains("html") && !type.contains("json")) {
                            it.body?.byteStream()?.let { input -> try {
                                val buffer = ByteArray(4_096)
                                while (output.size() < 65_536) {
                                    val read = input.read(buffer, 0, minOf(buffer.size, 65_536 - output.size()))
                                    if (read < 0) break
                                    output.write(buffer, 0, read)
                                }
                            } finally { call.cancel(); runCatching { input.close() } } }
                        }
                        val total = it.header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()
                            ?: if (it.code == 200) it.header("Content-Length")?.toLongOrNull() else null
                        val disposition = it.header("Content-Disposition").orEmpty()
                        val encoded = Regex("(?i)(?:^|;)\\s*filename\\*\\s*=\\s*([^;]+)").find(disposition)?.groupValues?.get(1)
                        val filename = if (encoded != null) {
                            val parts = encoded.trim().trim('"').split('\'', limit = 3)
                            runCatching { java.net.URLDecoder.decode(parts.last().replace("+", "%2B"), parts.first().ifBlank { "UTF-8" }) }.getOrNull()
                        } else Regex("(?i)(?:^|;)\\s*filename\\s*=\\s*(?:\"([^\"]*)\"|([^;]*))")
                            .find(disposition)?.let { found -> found.groupValues[1].ifEmpty { found.groupValues[2].trim() } }
                        val accepted = (headers.keys + "User-Agent").mapNotNull { name ->
                            it.request.header(name)?.let { value -> name to value }
                        }.toMap()
                        val start = it.header("Content-Range")?.substringAfter("bytes ", "")?.substringBefore('-')?.trim()?.toLongOrNull()
                        val result = MediaPrefix(it.code, type, total, filename, output.toByteArray(), it.request.url.toString(), accepted, start)
                        // Stop this one call before closing, rather than draining a range-ignoring movie.
                        call.cancel()
                        if (continuation.isActive) continuation.resumeWith(Result.success(result))
                    }
                } catch (failure: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        })
    }
