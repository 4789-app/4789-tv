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
class HttpDiskCache(private val directory: File) {

    data class Entry(val body: String, val etag: String?, val storedAtMillis: Long)

    fun read(url: String): Entry? {
        val file = fileFor(url)
        if (!file.isFile) return null
        return runCatching {
            val text = file.readText()
            val firstBreak = text.indexOf('\n')
            val secondBreak = text.indexOf('\n', firstBreak + 1)
            if (firstBreak < 0 || secondBreak < 0) return null
            Entry(
                body = text.substring(secondBreak + 1),
                etag = text.substring(0, firstBreak).takeIf(String::isNotEmpty),
                storedAtMillis = text.substring(firstBreak + 1, secondBreak).toLongOrNull() ?: 0L,
            )
        }.getOrNull()
    }

    fun write(url: String, body: String, etag: String?, storedAtMillis: Long) {
        runCatching {
            directory.mkdirs()
            val file = fileFor(url)
            // Write beside, then rename. A half-written cache file that parses is worse than none.
            val temp = File(directory, "${file.name}.tmp")
            temp.writeText("${etag.orEmpty()}\n$storedAtMillis\n$body")
            if (!temp.renameTo(file)) {
                file.writeText("${etag.orEmpty()}\n$storedAtMillis\n$body")
                temp.delete()
            }
        }
    }

    /** A 304 means the body is unchanged, so only the age resets. */
    fun touch(url: String, storedAtMillis: Long) {
        val entry = read(url) ?: return
        write(url, entry.body, entry.etag, storedAtMillis)
    }

    fun clear() {
        runCatching { directory.listFiles()?.forEach(File::delete) }
    }

    private fun fileFor(url: String): File = File(directory, sha256(url))

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
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
                        val output = ByteArrayOutputStream(minOf(maximumBytes, 64 * 1_024))
                        body.byteStream().use { input ->
                            val buffer = ByteArray(16 * 1_024)
                            var total = 0
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > maximumBytes) throw IllegalArgumentException("response_too_large")
                                output.write(buffer, 0, read)
                            }
                        }
                        if (continuation.isActive) {
                            continuation.resumeWith(
                                Result.success(
                                    HttpBody(output.toByteArray().decodeToString(), it.header("ETag")),
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
data class HttpTextResponse(val code: Int, val body: String) {
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
                        val body = it.body
                        val output = ByteArrayOutputStream(minOf(maximumBytes, 32 * 1_024))
                        body?.byteStream()?.use { input ->
                            val buffer = ByteArray(16 * 1_024)
                            var total = 0
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > maximumBytes) throw IllegalArgumentException("response_too_large")
                                output.write(buffer, 0, read)
                            }
                        }
                        if (continuation.isActive) {
                            continuation.resumeWith(
                                Result.success(HttpTextResponse(it.code, output.toByteArray().decodeToString())),
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

/** The client the TV uses when nothing else is injected. */
fun defaultAddonHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(12, TimeUnit.SECONDS)
    .callTimeout(15, TimeUnit.SECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

/** A short, value-free label for a failure, safe to store in add-on health. */
fun Throwable.healthLabel(): String = when (this) {
    is HttpStatusException -> "http_$code"
    else -> this::class.java.simpleName
}
