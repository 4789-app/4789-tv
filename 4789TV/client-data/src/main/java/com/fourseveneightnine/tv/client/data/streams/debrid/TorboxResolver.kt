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

import com.fourseveneightnine.tv.client.data.addons.HttpTextResponse
import com.fourseveneightnine.tv.client.data.addons.send
import com.fourseveneightnine.tv.client.data.streams.StreamRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant

data class TorboxLibraryTorrent(val id: Int, val infoHash: String, val name: String,
    val createdAt: String?, val ready: Boolean, val files: List<DebridFile>)

/** Turns an info hash into a playable TorBox URL, without ever adding an uncached torrent. */
class TorboxResolver(
    private val apiKey: String,
    private val okHttp: OkHttpClient,
    private val baseURL: String = BASE_URL,
) {
    /** User-opened account browse. Paginate to a short page; never mistake page one for the account. */
    suspend fun library(pageSize: Int = 1_000): List<TorboxLibraryTorrent> {
        require(apiKey.isNotBlank()) { "Add your TorBox key in TV Settings." }
        val all = linkedMapOf<Int, TorboxLibraryTorrent>()
        var offset = 0
        while (true) {
            val url = "$baseURL/v1/api/torrents/mylist".toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("limit", pageSize.toString())?.addQueryParameter("offset", offset.toString())
                ?.addQueryParameter("bypass_cache", "true")?.build() ?: error("Invalid TorBox endpoint")
            val response = okHttp.send(Request.Builder().url(url).header("Authorization", "Bearer $apiKey").build(), 16 * MAX_BYTES)
            if (!response.successful) throw IOException("TorBox Library could not load (HTTP ${response.code}).")
            val root = response.body.obj() ?: throw IOException("TorBox returned an unreadable library.")
            if (root.bool("success") != true) throw IOException("TorBox Library is unavailable. Check your key and retry.")
            val data = root["data"]
            val entries = when (data) {
                is JsonArray -> data.mapNotNull { it as? JsonObject }
                is JsonObject -> listOf(data)
                else -> throw IOException("TorBox returned no library data.")
            }
            var added = 0
            entries.forEach { row ->
                val id = row.int("id") ?: return@forEach
                val hash = row.string("hash").orEmpty()
                val torrent = TorboxLibraryTorrent(id, hash, row.string("name") ?: "Torrent $id", row.string("created_at"),
                    hash.isNotEmpty() && (row.bool("cached") == true || row.bool("download_present") == true || row.bool("download_finished") == true),
                    row.videoFiles())
                if (all.put(id, torrent) == null) added++
            }
            if (entries.size < pageSize) break
            if (added == 0) throw IOException("TorBox repeated a library page. Refresh to try again.")
            offset += entries.size
        }
        return all.values.sortedByDescending { torrent ->
            runCatching { Instant.parse(torrent.createdAt).toEpochMilli() }.getOrDefault(Long.MIN_VALUE)
        }
    }

    /** Read-only cache inspection for a user-selected pack. Does not add or acquire a torrent. */
    suspend fun cachedTorrents(hashes: List<String>): Map<String, List<DebridFile>> =
        if (apiKey.isBlank()) emptyMap() else cachedFiles(hashes)

    suspend fun cachedFiles(infoHash: String): List<DebridFile> =
        cachedFiles(listOf(infoHash))[infoHash.lowercase()].orEmpty()

    suspend fun cachedFiles(
        infoHashes: List<String>,
        v2ByHash: Map<String, String> = emptyMap(),
    ): Map<String, List<DebridFile>> =
        cacheEntries(infoHashes, listFiles = true, v2ByHash).mapValues { (_, entry) ->
            entry.videoFiles().filter(DebridFile::isPlayableVideo)
        }

    suspend fun isCached(infoHash: String, v2Infohash: String? = null): Boolean =
        infoHash.lowercase() in cacheEntries(
            listOf(infoHash), listFiles = false,
            v2ByHash = v2Infohash?.let { mapOf(infoHash.lowercase() to it) }.orEmpty(),
        )

    private suspend fun cacheEntries(
        infoHashes: List<String>,
        listFiles: Boolean,
        v2ByHash: Map<String, String>,
    ): Map<String, JsonObject> {
        require(apiKey.isNotBlank()) { "Add your TorBox key in TV Settings." }
        val endpoint = "$baseURL/v1/api/torrents/checkcached".toHttpUrlOrNull()
            ?: throw IOException("TorBox cache URL is unavailable.")
        val cached = mutableMapOf<String, JsonObject>()
        for (hashes in infoHashes.map(String::lowercase).distinct().chunked(16)) {
            val url = endpoint.newBuilder().addQueryParameter("hash", hashes.joinToString(","))
                .addQueryParameter("format", "object")
                .apply { if (listFiles) addQueryParameter("list_files", "true") }.build()
            val response = okHttp.send(Request.Builder().url(url).header("Authorization", "Bearer $apiKey").build(), 4 * MAX_BYTES)
            if (!response.successful) throw IOException("TorBox files could not load (HTTP ${response.code}).")
            val root = response.body.obj()
            if (root?.bool("success") != true) throw IOException("TorBox cache could not be checked. Check your key and retry.")
            val payload = root["data"]
            if (hashes.size == 1) {
                val entries = (payload as? JsonObject)?.entries?.take(4)?.joinToString { (key, value) ->
                    val entry = value as? JsonObject
                    val reported = entry?.string("hash").orEmpty()
                    "${key.safeHashPrefix()}/${reported.safeHashPrefix()}:${(entry?.get("files") as? JsonArray)?.size ?: 0}"
                }.orEmpty()
                android.util.Log.d("4789TamilMV", "TorBox reply ${hashes.first().safeHashPrefix()} " +
                    "listFiles=$listFiles status=${response.code} data=${payload?.javaClass?.simpleName} " +
                    "entries=${(payload as? JsonObject)?.size ?: (payload as? JsonArray)?.size ?: 0} [$entries]")
            }
            val data = payload as? JsonObject ?: continue
            for ((key, entry) in data) {
                if (entry !is JsonObject) continue
                val reported = entry.string("hash") ?: key
                // Match a v2 alias only if it was in the same magnet as the requested v1 hash.
                val hash = hashes.firstOrNull { DebridMagnetBuilder.matchesHash(reported, it, v2ByHash[it]) }
                    ?: continue
                cached[hash] = entry
            }
        }
        return cached
    }

    private fun String.safeHashPrefix(): String = takeIf(HEX_HASH::matches)?.take(8) ?: "-"

    private fun JsonObject.videoFiles(): List<DebridFile> = (get("files") as? JsonArray).orEmpty().mapIndexedNotNull { index, value ->
        val file = value as? JsonObject ?: return@mapIndexedNotNull null
        val id = file.int("id") ?: return@mapIndexedNotNull null
        val name = file.string("name") ?: file.string("short_name") ?: return@mapIndexedNotNull null
        DebridFile(id, name, file.long("size"), file.string("mimetype"), torrentIndex = index)
    }

    suspend fun resolve(
        row: StreamRow,
        season: Int?,
        episode: Int?,
        selectLargestVideo: Boolean = false,
        trace: (String) -> Unit = {},
    ): DebridOutcome {
        if (apiKey.isBlank()) return DebridOutcome.MissingApiKey
        val authorization = "Bearer $apiKey"
        val newExactEntry = row.exactSource && row.torboxTorrentID == null &&
            (row.torboxLibraryFile || row.expectation?.canonicalID?.startsWith("saved:tamilmv:") == true)

        return try {
            val savedTorrentID = row.torboxTorrentID
            val savedFileID = row.torboxFileID
            if (row.exactSource && row.torboxLibraryFile && savedTorrentID != null && savedFileID != null) {
                return download(savedTorrentID, DebridFile(savedFileID, row.filename ?: row.releaseName,
                    row.sizeBytes, torrentIndex = row.fileIdx), authorization, trace = trace)
            }
            val torrentID = row.torboxTorrentID ?: run {
                val magnet = DebridMagnetBuilder.fromRow(row) ?: return DebridOutcome.Stale
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
                trace("stage=create status=${create.code}")
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
                data.int("torrent_id") ?: data.int("id") ?: return DebridOutcome.Stale
            }

            val listURL = "$baseURL/v1/api/torrents/mylist".toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("id", torrentID.toString())
                ?.addQueryParameter("bypass_cache", "true")
                ?.build()
                ?: return DebridOutcome.Stale
            val list = playbackResponse(
                Request.Builder().url(listURL).header("Authorization", authorization).build(),
                "mylist", newExactEntry, trace,
            ) ?: return DebridOutcome.TorboxPreparing
            if (list.code == 401 || list.code == 403) return DebridOutcome.MissingApiKey
            if (!list.successful) return DebridOutcome.Stale
            val listData = (list.body.obj()?.get("data")) as? JsonObject ?: return DebridOutcome.Stale
            if (com.fourseveneightnine.tv.client.data.streams.StreamFacts.isTheatricalRelease(listData.string("name").orEmpty())) return DebridOutcome.Stale
            val files = (listData["files"] as? JsonArray).orEmpty().mapIndexedNotNull { index, element ->
                val entry = element as? JsonObject ?: return@mapIndexedNotNull null
                // Season folders can be the only identity for numbered episode files.
                val names = listOfNotNull(
                    entry.string("short_name"),
                    entry.string("name"),
                    entry.string("absolute_path"),
                ).filter(String::isNotBlank)
                val name = names.firstOrNull { '/' in it || '\\' in it } ?: names.firstOrNull().orEmpty()
                DebridFile(
                    id = entry.int("id"),
                    name = name,
                    sizeBytes = entry.long("size"),
                    mimeType = entry.string("mimetype"),
                    torrentIndex = index,
                )
            }
            if (row.exactSource &&
                !DebridMagnetBuilder.matchesHash(listData.string("hash"), row.infoHash.orEmpty(), row.v2Infohash)
            ) {
                trace("stage=mylist reason=hash_mismatch")
                return DebridOutcome.Stale
            }
            val safeFiles = files.filterNot { com.fourseveneightnine.tv.client.data.streams.StreamFacts.isTheatricalRelease(it.name) }
            val hint = DebridSelectionHint(
                filename = row.filename ?: row.releaseName.takeIf { !row.exactSource && it.isNotBlank() },
                torrentName = listData.string("name"), fileIdx = row.fileIdx,
                season = season, episode = episode, expectation = row.expectation,
            )
            if (row.exactSource && row.torboxFileID == null && season == null && episode == null &&
                !selectLargestVideo && row.filename == null && row.fileIdx == null && safeFiles.size != 1) return DebridOutcome.Stale
            val file = if (row.torboxFileID != null) {
                val selected = safeFiles.firstOrNull { it.id == row.torboxFileID } ?: run {
                    trace("stage=mylist reason=file_missing")
                    return DebridOutcome.Stale
                }
                DebridFileSelection.select(listOf(selected), hint.copy(fileIdx = null))
            } else DebridFileSelection.select(safeFiles, hint)
            file ?: return DebridOutcome.Stale

            download(torrentID, file, authorization, newExactEntry, trace)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            DebridOutcome.Error
        }
    }

    private suspend fun playbackResponse(
        request: Request,
        stage: String,
        retryNewEntry: Boolean,
        trace: (String) -> Unit,
    ): HttpTextResponse? {
        suspend fun read(attempt: Int): HttpTextResponse =
            okHttp.send(request, MAX_BYTES).also { trace("stage=$stage attempt=$attempt status=${it.code}") }

        fun pending(response: HttpTextResponse): Boolean {
            val reply = response.body.obj()
            if (response.code == 404 || response.code == 409) return reply?.string("error") != "ENDPOINT_NOT_FOUND"
            if (!response.successful || reply == null || reply.bool("success") == false) return false
            return when (val data = reply["data"]) {
                null, JsonNull -> true
                is JsonObject -> stage == "mylist" &&
                    (data.string("hash") == null || (data["files"] as? JsonArray).isNullOrEmpty())
                else -> false
            }
        }

        val first = read(1)
        if (!retryNewEntry || !pending(first)) return first
        return withTimeoutOrNull(2_500L) {
            delay(250)
            val second = read(2)
            if (!pending(second)) return@withTimeoutOrNull second
            delay(500)
            read(3).takeUnless(::pending)
        }
    }

    private suspend fun download(
        torrentID: Int,
        file: DebridFile,
        authorization: String,
        retryNewEntry: Boolean = false,
        trace: (String) -> Unit = {},
    ): DebridOutcome {
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
        val link = playbackResponse(
            Request.Builder().url(linkURL).header("Authorization", authorization).get().build(),
            "requestdl", retryNewEntry, trace,
        ) ?: return DebridOutcome.TorboxPreparing
        if (link.code == 401 || link.code == 403) return DebridOutcome.MissingApiKey
        if (!link.successful) return DebridOutcome.Stale
        val root = link.body.obj() ?: return DebridOutcome.Stale
        if (root.bool("success") == false) return DebridOutcome.Stale
        val url = root.string("data")?.takeIf { it.toHttpUrlOrNull() != null } ?: return DebridOutcome.Stale
        return DebridOutcome.Success(
            Resolved(
                url = url,
                headers = emptyMap(),
                filename = file.name.replace('\\', '/').substringAfterLast('/').takeIf(String::isNotBlank),
                sizeBytes = file.sizeBytes,
                service = "TorBox",
                torrentIndex = file.torrentIndex,
                torboxFileId = fileID,
            ),
        )
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
        private val HEX_HASH = Regex("[a-fA-F0-9]{40,68}")
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
