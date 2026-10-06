package com.fourseveneightnine.tv.client.data.catalog

import java.io.ByteArrayOutputStream
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** What one catalog request answered. */
public sealed interface CatalogHttpResult {
    public class Body(public val bytes: ByteArray, public val etag: String?) : CatalogHttpResult

    /** The manifest has not changed since [etag]. Nothing was downloaded. */
    public data object NotModified : CatalogHttpResult
}

/** A non-200, non-304 answer from the catalog server. */
public class CatalogHttpException(public val statusCode: Int) : Exception("catalog_http_$statusCode")

/**
 * The one network seam the snapshot store uses. Tests replace it wholesale; nothing else in the
 * store touches OkHttp.
 */
public interface CatalogHttp {
    /**
     * @param token bearer token for the private routes, null for the public ones.
     * @param ifNoneMatch the last ETag, so an unchanged manifest costs one header exchange.
     * @param maximumBytes hard ceiling; a larger body fails rather than being read.
     */
    public suspend fun get(
        url: String,
        token: String?,
        ifNoneMatch: String?,
        maximumBytes: Int,
    ): CatalogHttpResult
}

/**
 * OkHttp against `api.4789library.com`, with the headers the existing receiver repository sends:
 * `Accept: application/json`, `Authorization: Bearer <token>` and `X-4789-Owner: owner` on the
 * private routes.
 */
public class OkHttpCatalogHttp(
    private val client: OkHttpClient,
    private val ownerId: String = OWNER_ID,
) : CatalogHttp {
    override suspend fun get(
        url: String,
        token: String?,
        ifNoneMatch: String?,
        maximumBytes: Int,
    ): CatalogHttpResult = withContext(Dispatchers.IO) {
        require(maximumBytes > 0) { "maximum_bytes" }
        val parsed = URI(url)
        require(parsed.scheme == "https" && parsed.host == CATALOG_HOST && parsed.userInfo == null) {
            "catalog_host"
        }
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .apply {
                if (token != null) {
                    header("Authorization", "Bearer $token")
                    header("X-4789-Owner", ownerId)
                }
                if (ifNoneMatch != null) header("If-None-Match", ifNoneMatch)
            }
            .build()
        client.newCall(request).execute().use { response ->
            when (response.code) {
                304 -> CatalogHttpResult.NotModified
                200 -> {
                    val declared = response.body?.contentLength() ?: -1L
                    require(declared == -1L || declared in 1..maximumBytes.toLong()) { "content_length" }
                    val stream = response.body?.byteStream() ?: throw CatalogHttpException(200)
                    val bytes = readBounded(stream, maximumBytes)
                    CatalogHttpResult.Body(bytes, response.header("ETag"))
                }
                else -> throw CatalogHttpException(response.code)
            }
        }
    }

    private fun readBounded(input: java.io.InputStream, maximumBytes: Int): ByteArray = input.use { stream ->
        val output = ByteArrayOutputStream(minOf(maximumBytes, BUFFER_BYTES))
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            require(output.size() + count <= maximumBytes) { "response_size" }
            output.write(buffer, 0, count)
        }
        output.toByteArray().also { require(it.isNotEmpty()) { "empty_response" } }
    }

    public companion object {
        public const val CATALOG_HOST: String = "api.4789library.com"
        public const val OWNER_ID: String = "owner"
        private const val BUFFER_BYTES = 16 * 1024
    }
}
