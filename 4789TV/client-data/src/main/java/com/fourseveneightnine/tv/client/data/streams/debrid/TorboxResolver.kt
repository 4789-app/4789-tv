// Derived from NuvioTV (GPL-3.0)
//   app/src/main/java/com/nuvio/tv/core/debrid/TorboxDirectDebridResolver.kt
//   app/src/main/java/com/nuvio/tv/core/debrid/TorboxFileSelector.kt
//
// Their three-step flow is kept: createtorrent with `add_only_if_cached=true`, read the file list
// from mylist, then requestdl for the chosen file. The 409-means-not-cached reading is theirs too,
// and it is the whole reason this path is cheap — an uncached hash costs one request and is never
// added to the owner's account. Retrofit multipart and Moshi are replaced by OkHttp and
// kotlinx-serialization.
package com.fourseveneightnine.tv.client.data.streams.debrid

import com.fourseveneightnine.tv.client.data.addons.send
import com.fourseveneightnine.tv.client.data.streams.StreamRow
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request

/** Turns an info hash into a playable TorBox URL, without ever adding an uncached torrent. */
class TorboxResolver(
    private val apiKey: String,
    private val okHttp: OkHttpClient,
    private val baseURL: String = BASE_URL,
) {

    suspend fun resolve(row: StreamRow, season: Int?, episode: Int?): DebridOutcome {
        if (apiKey.isBlank()) return DebridOutcome.MissingApiKey
        val magnet = DebridMagnetBuilder.fromRow(row) ?: return DebridOutcome.Stale
        val authorization = "Bearer $apiKey"

        return try {
            val create = okHttp.send(
                Request.Builder()
                    .url("$baseURL/v1/api/torrents/createtorrent")
                    .header("Authorization", authorization)
                    .post(
                        MultipartBody.Builder().setType(MultipartBody.FORM)
                            .addFormDataPart("magnet", magnet)
                            // The one flag that makes this safe to try on every row: TorBox
                            // refuses with a 409 rather than starting a download.
                            .addFormDataPart("add_only_if_cached", "true")
                            .addFormDataPart("allow_zip", "false")
                            .build(),
                    )
                    .build(),
                MAX_BYTES,
            )
            if (!create.successful) {
                return when (create.code) {
                    401, 403 -> DebridOutcome.MissingApiKey
                    409 -> DebridOutcome.NotCached
                    else -> DebridOutcome.Stale
                }
            }
            val createBody = create.body.obj() ?: return DebridOutcome.Stale
            if (createBody.bool("success") == false) return DebridOutcome.NotCached
            val data = createBody["data"] as? JsonObject ?: return DebridOutcome.Stale
            val torrentID = data.int("torrent_id") ?: data.int("id") ?: return DebridOutcome.Stale

            val listURL = "$baseURL/v1/api/torrents/mylist".toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("id", torrentID.toString())
                ?.addQueryParameter("bypass_cache", "true")
                ?.build()
                ?: return DebridOutcome.Stale
            val list = okHttp.send(
                Request.Builder().url(listURL).header("Authorization", authorization).get().build(),
                MAX_BYTES,
            )
            if (!list.successful) return DebridOutcome.Stale
            val listData = (list.body.obj()?.get("data")) as? JsonObject ?: return DebridOutcome.Stale
            val files = (listData["files"] as? JsonArray).orEmpty().mapNotNull { element ->
                val entry = element as? JsonObject ?: return@mapNotNull null
                val name = listOfNotNull(
                    entry.string("short_name"),
                    entry.string("name")?.substringAfterLast('/'),
                    entry.string("absolute_path")?.substringAfterLast('/'),
                ).firstOrNull(String::isNotBlank).orEmpty()
                DebridFile(
                    id = entry.int("id"),
                    name = name,
                    sizeBytes = entry.long("size"),
                    mimeType = entry.string("mimetype"),
                )
            }
            val file = DebridFileSelection.select(
                files = files,
                hint = DebridSelectionHint(
                    filename = row.releaseName.takeIf(String::isNotBlank),
                    torrentName = listData.string("name"),
                    fileIdx = row.fileIdx,
                    season = season,
                    episode = episode,
                ),
            ) ?: return DebridOutcome.Stale
            val fileID = file.id ?: return DebridOutcome.Stale

            val linkURL = "$baseURL/v1/api/torrents/requestdl".toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("token", apiKey)
                ?.addQueryParameter("torrent_id", torrentID.toString())
                ?.addQueryParameter("file_id", fileID.toString())
                ?.addQueryParameter("zip_link", "false")
                ?.addQueryParameter("redirect", "false")
                ?.addQueryParameter("append_name", "false")
                ?.build()
                ?: return DebridOutcome.Stale
            val link = okHttp.send(
                Request.Builder().url(linkURL).header("Authorization", authorization).get().build(),
                MAX_BYTES,
            )
            if (!link.successful) return DebridOutcome.Stale
            val url = (link.body.obj()?.get("data") as? JsonPrimitive)?.contentOrNull
                ?.takeIf(String::isNotBlank)
                ?: return DebridOutcome.Stale

            DebridOutcome.Success(
                Resolved(
                    url = url,
                    headers = emptyMap(),
                    filename = file.name.takeIf(String::isNotBlank),
                    sizeBytes = file.sizeBytes,
                    service = "TorBox",
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            DebridOutcome.Error
        }
    }

    private fun String.obj(): JsonObject? =
        runCatching { json.parseToJsonElement(this) as? JsonObject }.getOrNull()

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)

    private fun JsonObject.int(key: String): Int? =
        (get(key) as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toInt() }

    private fun JsonObject.long(key: String): Long? =
        (get(key) as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() }

    private fun JsonObject.bool(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull

    companion object {
        const val BASE_URL: String = "https://api.torbox.app"
        private const val MAX_BYTES = 1 * 1_024 * 1_024
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
