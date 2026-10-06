package com.fourseveneightnine.tv.client.iptv

import android.os.StatFs
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request

/** Copies live HLS media segments into one TS or fragmented MP4 file. */
internal class IptvHlsRecorder(private val http: OkHttpClient) {
    private data class Segment(val url: String, val sequence: Long, val keyUrl: String?,
                               val iv: ByteArray?, val range: LongRange?)
    private data class Playlist(val url: String, val segments: List<Segment>, val targetSeconds: Int,
                                val mapUrl: String?, val mapRange: LongRange?, val ended: Boolean)

    suspend fun record(url: String, headers: Map<String, String>, directory: File,
                       id: String, endAtMillis: Long): File {
        var playlist = mediaPlaylist(url, headers)
        val file = File(directory, "$id.${if (playlist.mapUrl != null) "mp4" else "ts"}")
        val seen = LinkedHashSet<String>()
        val keys = HashMap<String, ByteArray>()
        var bytesWritten = 0L
        var lastSegmentAt = System.currentTimeMillis()
        file.outputStream().buffered().use { output ->
            playlist.mapUrl?.let { map ->
                val init = get(map, headers, 16 * 1024 * 1024, playlist.mapRange)
                output.write(init)
                bytesWritten += init.size
            }
            while (System.currentTimeMillis() < endAtMillis) {
                for (segment in playlist.segments) {
                    if (System.currentTimeMillis() >= endAtMillis) break
                    val identity = "${segment.url}#${segment.range}"
                    if (!seen.add(identity)) continue
                    while (seen.size > 1_024) seen.remove(seen.first())
                    val encoded = get(segment.url, headers, 40 * 1024 * 1024, segment.range)
                    val bytes = segment.keyUrl?.let { keyUrl ->
                        val key = keys.getOrPut(keyUrl) { get(keyUrl, headers, 128).also { require(it.size == 16) } }
                        val iv = segment.iv ?: ByteBuffer.allocate(16).putLong(0).putLong(segment.sequence).array()
                        Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                        }.doFinal(encoded)
                    } ?: encoded
                    output.write(bytes)
                    bytesWritten += bytes.size
                    lastSegmentAt = System.currentTimeMillis()
                    if (bytesWritten % (16L * 1024 * 1024) < bytes.size &&
                        StatFs(directory.path).availableBytes < 100L * 1024 * 1024) error("TV storage is nearly full")
                }
                if (playlist.ended) break
                if (System.currentTimeMillis() - lastSegmentAt > 90_000) error("No new HLS segments arrived")
                delay((playlist.targetSeconds * 500L).coerceIn(1_500L, 6_000L))
                val next = mediaPlaylist(playlist.url, headers)
                if (next.mapUrl != playlist.mapUrl || next.mapRange != playlist.mapRange) {
                    error("HLS stream changed its initialization segment")
                }
                playlist = next
            }
        }
        if (bytesWritten == 0L) error("The stream returned no video")
        return file
    }

    private fun mediaPlaylist(startUrl: String, headers: Map<String, String>): Playlist {
        var url = startUrl
        repeat(3) {
            val text = get(url, headers, 2 * 1024 * 1024).decodeToString()
            val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            if (!lines.firstOrNull().orEmpty().startsWith("#EXTM3U")) error("Invalid HLS playlist")
            val variant = lines.withIndex().firstOrNull { it.value.startsWith("#EXT-X-STREAM-INF") }
            if (variant != null) {
                val next = lines.drop(variant.index + 1).firstOrNull { !it.startsWith('#') }
                    ?: error("Empty HLS master playlist")
                url = resolve(url, next)
            } else return parseMedia(url, lines)
        }
        error("Too many HLS master playlists")
    }

    private fun parseMedia(url: String, lines: List<String>): Playlist {
        var sequence = lines.firstOrNull { it.startsWith("#EXT-X-MEDIA-SEQUENCE:") }
            ?.substringAfter(':')?.toLongOrNull() ?: 0L
        val target = lines.firstOrNull { it.startsWith("#EXT-X-TARGETDURATION:") }
            ?.substringAfter(':')?.toIntOrNull()?.coerceIn(1, 30) ?: 6
        var keyUrl: String? = null
        var iv: ByteArray? = null
        var mapUrl: String? = null
        var mapRange: LongRange? = null
        var pendingRange: LongRange? = null
        var previousRangeEnd = -1L
        val segments = ArrayList<Segment>()
        for (line in lines) {
            when {
                line.startsWith("#EXT-X-MAP:") -> {
                    val map = attribute(line, "URI") ?: error("Invalid HLS init segment")
                    mapUrl = resolve(url, map)
                    mapRange = attribute(line, "BYTERANGE")?.let { parseRange(it, -1L) }
                }
                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    pendingRange = parseRange(line.substringAfter(':'), previousRangeEnd)
                    previousRangeEnd = pendingRange.last
                }
                line.startsWith("#EXT-X-KEY:") -> {
                    when (attribute(line, "METHOD")) {
                        "NONE" -> { keyUrl = null; iv = null }
                        "AES-128" -> {
                            keyUrl = resolve(url, attribute(line, "URI") ?: error("Missing HLS key"))
                            iv = attribute(line, "IV")?.removePrefix("0x")?.padStart(32, '0')
                                ?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()
                            if (iv != null && iv.size != 16) error("Invalid HLS IV")
                        }
                        else -> error("Unsupported HLS encryption")
                    }
                }
                line.startsWith('#') -> Unit
                else -> {
                    segments += Segment(resolve(url, line), sequence, keyUrl, iv, pendingRange)
                    pendingRange = null
                    sequence++
                }
            }
        }
        if (segments.isEmpty()) error("No HLS media segments")
        return Playlist(url, segments, target, mapUrl, mapRange,
            lines.any { it.startsWith("#EXT-X-ENDLIST") })
    }

    private fun parseRange(value: String, previousEnd: Long): LongRange {
        val length = value.substringBefore('@').toLongOrNull()?.takeIf { it > 0 }
            ?: error("Invalid HLS byte range")
        val start = value.substringAfter('@', "").toLongOrNull() ?: previousEnd + 1
        require(start >= 0 && start <= Long.MAX_VALUE - length)
        return start..(start + length - 1)
    }

    private fun attribute(line: String, key: String): String? {
        val match = Regex("(?:^|[:,])$key=(?:\"([^\"]*)\"|([^,]*))").find(line) ?: return null
        return match.groupValues[1].ifBlank { match.groupValues[2] }.trim()
    }

    private fun resolve(base: String, relative: String): String {
        val candidate = URI(base).resolve(relative).toString()
        if (!IptvM3u.httpUrl(candidate)) error("Invalid HLS media URL")
        return candidate
    }

    private fun get(url: String, headers: Map<String, String>, maxBytes: Int,
                    range: LongRange? = null): ByteArray {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (key, value) -> header(key, value) }
            if (range != null) header("Range", "bytes=${range.first}-${range.last}")
        }.build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HLS HTTP ${response.code}")
            if (range != null && response.code != 206) error("HLS server ignored byte range")
            val stream = response.body?.byteStream() ?: error("Empty HLS response")
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.size() + count > maxBytes) error("HLS response is too large")
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }
}
