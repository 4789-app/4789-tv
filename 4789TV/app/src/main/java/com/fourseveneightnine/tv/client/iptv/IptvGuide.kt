package com.fourseveneightnine.tv.client.iptv

import android.util.Xml
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.io.InputStream
import java.util.TimeZone
import okhttp3.OkHttpClient
import okhttp3.Request
import com.fourseveneightnine.tv.player.FastPlaybackDns
import java.util.concurrent.TimeUnit
import org.xmlpull.v1.XmlPullParser

/** One XMLTV request per source, filtered while parsing to channels the TV actually has. */
internal class IptvGuide(private val http: OkHttpClient) {
    suspend fun load(url: String, channels: List<IptvChannel>, now: Long = System.currentTimeMillis()): List<IptvProgram> =
        withContext(Dispatchers.IO) {
            val byEpgId = channels.filter { !it.epgId.isNullOrBlank() }.groupBy { it.epgId!! }
            if (byEpgId.isEmpty() || !IptvM3u.httpUrl(url)) return@withContext emptyList()
            val owner = currentCoroutineContext()
            val request = Request.Builder().url(url).build()
            http.newBuilder().dns(FastPlaybackDns(request.url.host, request.url.port))
                .connectTimeout(3, TimeUnit.SECONDS).build().readIptvResponse(request) { response ->
                if (!response.isSuccessful) error("Guide HTTP ${response.code}")
                val rawInput = response.iptvInput(MAX_GUIDE_BYTES)
                val input = object : InputStream() {
                    private var tokenBytes = 0
                    fun checkpoint() { tokenBytes = 0 }
                    override fun read(): Int = rawInput.read().also {
                        if (it >= 0 && ++tokenBytes > MAX_TOKEN_BYTES) throw IOException("Guide token is too large")
                    }
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (length == 0) return 0
                        val allowed = minOf(length, MAX_TOKEN_BYTES - tokenBytes + 1).coerceAtLeast(1)
                        val count = rawInput.read(buffer, offset, allowed)
                        if (count > 0) {
                            tokenBytes += count
                            if (tokenBytes > MAX_TOKEN_BYTES) throw IOException("Guide token is too large")
                        }
                        return count
                    }
                    override fun close() = rawInput.close()
                }
                val parser = Xml.newPullParser().apply { setInput(input, null) }
                fun advance(): Int {
                    owner.ensureActive()
                    // Bound reads before the tokenizer materializes an attribute/text/DTD token.
                    // Parser lookahead may already hold a small chunk from the previous token.
                    input.checkpoint()
                    val event = parser.nextToken()
                    if (parser.depth > MAX_DEPTH) throw IOException("Guide nesting is too deep")
                    return event
                }
                fun field(maximum: Int): String {
                    val depth = parser.depth
                    val value = StringBuilder(maximum)
                    val slice = IntArray(2)
                    var count = 0
                    var event = advance()
                    while (event != XmlPullParser.END_DOCUMENT &&
                        !(event == XmlPullParser.END_TAG && parser.depth == depth)) {
                        owner.ensureActive()
                        if (event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT || event == XmlPullParser.ENTITY_REF) {
                            val text = parser.getTextCharacters(slice)
                            val available = if (text == null) 0 else slice[1]
                            count += available
                            if (count > MAX_FIELD_CHARACTERS) throw IOException("Guide field is too large")
                            val length = minOf(available, maximum - value.length)
                            if (length > 0 && text != null) value.append(text, slice[0], length)
                        }
                        event = advance()
                    }
                    return value.toString().trim()
                }
                val results = ArrayList<IptvProgram>()
                var channel = ""
                var start = 0L
                var end = 0L
                var title = ""
                var description: String? = null
                val categories = ArrayList<String>(3)
                var tags = 0
                while (parser.eventType != XmlPullParser.END_DOCUMENT && tags < MAX_PROGRAMS && results.size < MAX_PROGRAMS) {
                    owner.ensureActive()
                    when (parser.eventType) {
                        XmlPullParser.START_TAG -> when (parser.name) {
                            "programme" -> {
                                tags++
                                channel = parser.getAttributeValue(null, "channel").orEmpty()
                                start = parseTime(parser.getAttributeValue(null, "start"))
                                end = parseTime(parser.getAttributeValue(null, "stop"))
                                title = ""
                                description = null
                                categories.clear()
                            }
                            "title" -> if (channel in byEpgId) title = field(500)
                            "desc" -> if (channel in byEpgId) description = field(500)
                            "category" -> if (channel in byEpgId && categories.size < 4) {
                                field(100).takeIf(String::isNotBlank)?.let(categories::add)
                            }
                        }
                        XmlPullParser.END_TAG -> if (parser.name == "programme" &&
                            channel in byEpgId && title.isNotBlank() && start > 0 && end > start && end > now - 7 * DAY && start < now + 7 * DAY
                        ) {
                            byEpgId[channel].orEmpty().forEach { item ->
                                if (results.size < MAX_PROGRAMS)
                                    results += IptvProgram(item.id, title, start, end, description, categories.toList())
                            }
                        }
                    }
                    advance()
                }
                results
            }
        }

    private fun parseTime(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val compact = raw.trim()
        val digits = compact.substringBefore(' ').length
        val base = if (digits == 12) "yyyyMMddHHmm" else "yyyyMMddHHmmss"
        val format = base + if (compact.contains(' ')) " Z" else ""
        return runCatching { SimpleDateFormat(format, Locale.US).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }
            .parse(compact)?.time ?: 0L }.getOrDefault(0L)
    }

    private companion object {
        const val DAY = 86_400_000L
        const val MAX_GUIDE_BYTES = 128 * 1024 * 1024
        const val MAX_PROGRAMS = 200_000
        const val MAX_FIELD_CHARACTERS = 16 * 1024
        const val MAX_TOKEN_BYTES = 64 * 1024
        const val MAX_DEPTH = 64
    }
}
