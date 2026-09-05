package com.fourseveneightnine.phone

import com.fourseveneightnine.contract.StreamEntry
import com.fourseveneightnine.contract.StreamEnvelope
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlinx.coroutines.suspendCancellableCoroutine

internal object RemoteStreamPolicy {
    const val MAXIMUM_RESPONSE_BYTES = 2 * 1024 * 1024

    fun isPlayableURL(value: String): Boolean {
        if (value.length !in 1..4_096) return false
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() && uri.rawUserInfo == null
    }

    fun playable(entries: List<StreamEntry>): List<StreamEntry> = entries
        .asSequence()
        .filter { entry ->
            val value = entry.url ?: return@filter false
            isPlayableURL(value)
        }
        .distinctBy(StreamEntry::url)
        .take(100)
        .toList()
}

internal class RemoteSourceRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
    private val fetch: (suspend (String) -> ByteArray)? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(manifestURL: String, mediaType: String, titleID: String): List<StreamEntry> {
        val url = AddonConfigurationPolicy.streamURL(manifestURL, mediaType, titleID)
            ?: error("invalid_stream_request")
        val bytes = fetch?.invoke(url) ?: execute(
            Request.Builder().url(url).header("Accept", "application/json").get().build(),
        )
        require(bytes.size in 1..RemoteStreamPolicy.MAXIMUM_RESPONSE_BYTES) { "response_size" }
        val envelope = json.decodeFromString<StreamEnvelope>(bytes.decodeToString())
        require(envelope.success && envelope.error.isNullOrBlank()) { "source_error" }
        return RemoteStreamPolicy.playable(envelope.streams)
    }

    /**
     * Ask the addon for this title's streams again and hand back the link for the one entry the
     * recipe named.
     *
     * Throws when the addon could not be reached, because that is worth another attempt later.
     * Returns null when the addon answered and the file is simply no longer offered — that is not
     * worth retrying, and substituting a different file would be worse than stopping.
     */
    suspend fun resolveOne(manifestURL: String, recipe: DurableStreamRecipe): String? {
        val entries = load(manifestURL, recipe.mediaType, recipe.titleID)
        return DurableStreamRecipePolicy.match(recipe, entries)?.url
    }

    private suspend fun execute(request: Request): ByteArray = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val result = runCatching {
                        require(it.code == 200) { "http_${it.code}" }
                        val body = requireNotNull(it.body) { "missing_body" }
                        val declared = body.contentLength()
                        require(
                            declared == -1L || declared in 1..RemoteStreamPolicy.MAXIMUM_RESPONSE_BYTES.toLong(),
                        ) {
                            "content_length"
                        }
                        body.byteStream().use { input ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(output.size() + count <= RemoteStreamPolicy.MAXIMUM_RESPONSE_BYTES) {
                                    "response_size"
                                }
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                    }
                    if (continuation.isActive) continuation.resumeWith(result)
                }
            }
        })
    }
}
