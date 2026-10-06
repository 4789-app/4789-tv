package com.fourseveneightnine.tv.client.iptv

import android.util.Xml
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
            val request = Request.Builder().url(url).build()
            http.newBuilder().dns(FastPlaybackDns(request.url.host, request.url.port))
                .connectTimeout(3, TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val input = response.body?.byteStream() ?: return@use emptyList()
                val parser = Xml.newPullParser().apply { setInput(input, null) }
                val results = ArrayList<IptvProgram>()
                var channel = ""
                var start = 0L
                var end = 0L
                var title = ""
                var description: String? = null
                val categories = ArrayList<String>(3)
                var tags = 0
                while (parser.eventType != XmlPullParser.END_DOCUMENT && tags < 200_000) {
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
                            "title" -> if (channel in byEpgId) title = parser.nextText().trim()
                            "desc" -> if (channel in byEpgId) description = parser.nextText().trim().take(500)
                            "category" -> if (channel in byEpgId && categories.size < 4) {
                                parser.nextText().trim().takeIf(String::isNotBlank)?.let(categories::add)
                            }
                        }
                        XmlPullParser.END_TAG -> if (parser.name == "programme" &&
                            channel in byEpgId && title.isNotBlank() && end > now - 7 * DAY && start < now + 7 * DAY
                        ) {
                            byEpgId[channel].orEmpty().forEach { item ->
                                results += IptvProgram(item.id, title, start, end, description, categories.toList())
                            }
                        }
                    }
                    parser.next()
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
        return runCatching { SimpleDateFormat(format, Locale.US).apply { isLenient = false }
            .parse(compact)?.time ?: 0L }.getOrDefault(0L)
    }

    private companion object { const val DAY = 86_400_000L }
}
