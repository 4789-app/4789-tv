// Derived from NuvioTV (GPL-3.0)
//   app/src/main/java/com/nuvio/tv/core/debrid/RealDebridDirectDebridResolver.kt
//   app/src/main/java/com/nuvio/tv/core/debrid/RealDebridFileSelector.kt
//
// The five-step flow is theirs and is kept: addMagnet, read the file list, select one file, read
// the torrent again for the link, unrestrict it — and delete the torrent again on any failure, so
// a resolve that went wrong does not leave rubbish in the owner's account. Their Retrofit
// interface and Moshi DTOs are replaced by OkHttp calls and kotlinx-serialization parsing.
package com.fourseveneightnine.tv.client.data.streams.debrid

import com.fourseveneightnine.tv.client.data.addons.send
import com.fourseveneightnine.tv.client.data.streams.StreamRow
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Turns an info hash into a playable Real-Debrid URL.
 *
 * **No availability probe.** Real-Debrid removed `torrents/instantAvailability` in 2024, so there
 * is nothing left to ask. Whether a hash is cached is a claim the add-on printed in its own title,
 * and this resolver simply tries: a hash the account already holds comes back as `downloaded` in
 * one round trip, and one it does not comes back without a link and is deleted again.
 */
class RealDebridResolver(
    private val apiKey: String,
    private val okHttp: OkHttpClient,
    private val baseURL: String = BASE_URL,
) {

    suspend fun resolve(row: StreamRow, season: Int?, episode: Int?): DebridOutcome {
        if (apiKey.isBlank()) return DebridOutcome.MissingApiKey
        val magnet = DebridMagnetBuilder.fromRow(row) ?: return DebridOutcome.Stale
        val authorization = "Bearer $apiKey"
        var torrentID: String? = null
        var resolved = false

        return try {
            val add = okHttp.send(
                Request.Builder()
                    .url("$baseURL/torrents/addMagnet")
                    .header("Authorization", authorization)
                    .post(FormBody.Builder().add("magnet", magnet).build())
                    .build(),
                MAX_BYTES,
            )
            if (!add.successful) {
                return when (add.code) {
                    401, 403 -> DebridOutcome.MissingApiKey
                    else -> DebridOutcome.Stale
                }
            }
            val id = add.body.obj()?.string("id")?.takeIf(String::isNotBlank)
                ?: return DebridOutcome.Stale
            torrentID = id

            val before = info(authorization, id) ?: return DebridOutcome.Stale
            val file = DebridFileSelection.select(
                files = before.files,
                hint = DebridSelectionHint(
                    filename = row.releaseName.takeIf(String::isNotBlank),
                    torrentName = before.filename,
                    fileIdx = row.fileIdx,
                    season = season,
                    episode = episode,
                ),
            ) ?: return DebridOutcome.Stale
            val fileID = file.id ?: return DebridOutcome.Stale

            val select = okHttp.send(
                Request.Builder()
                    .url("$baseURL/torrents/selectFiles/$id")
                    .header("Authorization", authorization)
                    .post(FormBody.Builder().add("files", fileID.toString()).build())
                    .build(),
                MAX_BYTES,
            )
            // 202 means "accepted, already selected". Real-Debrid uses it routinely.
            if (!select.successful && select.code != 202) return DebridOutcome.Stale

            val after = info(authorization, id) ?: return DebridOutcome.Stale
            // A torrent that is not "downloaded" is not cached, whatever the add-on's badge said.
            if (!after.status.equals("downloaded", ignoreCase = true)) return DebridOutcome.NotCached
            val link = after.links.firstOrNull(String::isNotBlank) ?: return DebridOutcome.NotCached

            val unrestrict = okHttp.send(
                Request.Builder()
                    .url("$baseURL/unrestrict/link")
                    .header("Authorization", authorization)
                    .post(FormBody.Builder().add("link", link).build())
                    .build(),
                MAX_BYTES,
            )
            if (!unrestrict.successful) return DebridOutcome.Stale
            val body = unrestrict.body.obj() ?: return DebridOutcome.Stale
            val url = body.string("download")?.takeIf(String::isNotBlank) ?: return DebridOutcome.Stale
            resolved = true
            DebridOutcome.Success(
                Resolved(
                    url = url,
                    headers = emptyMap(),
                    filename = body.string("filename")?.takeIf(String::isNotBlank) ?: file.name,
                    sizeBytes = body.long("filesize") ?: file.sizeBytes,
                    service = "Real-Debrid",
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            DebridOutcome.Error
        } finally {
            if (!resolved) {
                val id = torrentID
                if (id != null) {
                    runCatching {
                        okHttp.send(
                            Request.Builder()
                                .url("$baseURL/torrents/delete/$id")
                                .header("Authorization", "Bearer $apiKey")
                                .delete()
                                .build(),
                            MAX_BYTES,
                        )
                    }
                }
            }
        }
    }

    private data class TorrentInfo(
        val filename: String?,
        val status: String,
        val files: List<DebridFile>,
        val links: List<String>,
    )

    private suspend fun info(authorization: String, id: String): TorrentInfo? {
        val response = okHttp.send(
            Request.Builder()
                .url("$baseURL/torrents/info/$id")
                .header("Authorization", authorization)
                .get()
                .build(),
            MAX_BYTES,
        )
        if (!response.successful) return null
        val root = response.body.obj() ?: return null
        return TorrentInfo(
            filename = root.string("filename"),
            status = root.string("status").orEmpty(),
            files = (root["files"] as? JsonArray).orEmpty().mapNotNull { element ->
                val entry = element as? JsonObject ?: return@mapNotNull null
                val path = entry.string("path").orEmpty()
                DebridFile(
                    id = entry.string("id")?.toIntOrNull(),
                    name = path.substringAfterLast('/').ifBlank { path },
                    sizeBytes = entry.long("bytes"),
                )
            },
            links = (root["links"] as? JsonArray).orEmpty().mapNotNull {
                (it as? JsonPrimitive)?.contentOrNull
            },
        )
    }

    private fun String.obj(): JsonObject? =
        runCatching { json.parseToJsonElement(this) as? JsonObject }.getOrNull()

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)

    private fun JsonObject.long(key: String): Long? =
        (get(key) as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() }

    companion object {
        const val BASE_URL: String = "https://api.real-debrid.com/rest/1.0"
        private const val MAX_BYTES = 1 * 1_024 * 1_024
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
