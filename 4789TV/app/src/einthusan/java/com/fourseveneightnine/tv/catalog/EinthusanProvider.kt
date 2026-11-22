package com.fourseveneightnine.tv.catalog

import com.fourseveneightnine.tv.client.data.addons.send
import com.fourseveneightnine.tv.client.data.streams.debrid.Resolved
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.text.Normalizer
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.math.abs

internal class EinthusanProvider(http: OkHttpClient) {
    private class SessionCookies : CookieJar {
        private val values = mutableListOf<Cookie>()

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (cookie in cookies) {
                values.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                if (cookie.expiresAt > System.currentTimeMillis()) values += cookie
            }
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            values.removeAll { it.expiresAt <= System.currentTimeMillis() }
            return values.filter { it.matches(url) }
        }

        @Synchronized
        fun clear() = values.clear()
    }
    private val cookies = SessionCookies()
    private val publicHttp = http.newBuilder().cache(null).cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false).followSslRedirects(false).callTimeout(12, TimeUnit.SECONDS).build()
    private val sessionHttp = publicHttp.newBuilder().cookieJar(cookies).build()
    private val session = Mutex()
    private var signedIn: EinthusanAccount? = null
    private var signedInAt = 0L
    private val hitCache = TimedCache<SearchKey, EinthusanSource?>(256)
    private val alphaCache = TimedCache<HttpUrl, List<ListingHit>>(256)

    suspend fun find(title: String, year: Int?, imdbId: String?, originalLanguage: String?, account: EinthusanAccount): EinthusanSource? {
        if (!account.configured || title.isBlank()) return null
        val language = EinthusanLanguage.from(originalLanguage) ?: account.language
        val query = SearchKey(title.trim(), year, imdbId?.lowercase(), language)
        hitCache[query]?.let { return it.value }
        return withTimeoutOrNull(22_000) {
            val alpha = query.title.first().takeIf { it in 'A'..'Z' || it in 'a'..'z' }
            val exact = alpha?.let { findInAlphabet(query, it.uppercaseChar()) }
            val keyword = if (exact == null) {
                session.withLock {
                    try {
                        login(account, language)
                        listing(url("movie/results/", language, "query" to query.title), sessionHttp, 2_500)
                    } catch (_: EinthusanFailure) { null }
                }
            } else null
            val hit = exact ?: if (keyword.isNullOrEmpty()) {
                listOfNotNull(year, year?.minus(1), year?.plus(1)).firstNotNullOfOrNull { candidate ->
                    listing(url("movie/results/", language, "find" to "Year", "year" to "$candidate"), publicHttp, 2_500)
                        ?.let { bestHit(it, query) }
                }
            } else bestHit(keyword, query)
            val source = hit?.let { EinthusanSource(it.id, language, it.cc, it.uhd) }
            if (hit != null || !keyword.isNullOrEmpty()) hitCache.put(query, source)
            source
        }
    }

    suspend fun verify(account: EinthusanAccount) = session.withLock {
        login(account, account.language, force = true)
        val html = page(url("account/", account.language), sessionHttp)
        if ("Go Premium" in html) fail("Signed in, but this plan does not include UHD.")
    }

    suspend fun resolve(source: EinthusanSource, account: EinthusanAccount): Resolved = session.withLock {
        if (!account.configured) fail("Einthusan is off or needs an account. Check Accounts & keys.")
        login(account, source.language)
        try {
            resolveAsset(source)
        } catch (_: ExpiredSession) {
            login(account, source.language, force = true)
            try {
                resolveAsset(source)
            } catch (expired: ExpiredSession) {
                if (expired.premiumRequired) fail("Einthusan requires a premium plan for this stream.")
                fail("Einthusan could not start playback. Try again or verify your login in Settings.")
            }
        }
    }

    private suspend fun login(account: EinthusanAccount, language: EinthusanLanguage, force: Boolean = false) {
        if (!account.hasCredentials) fail("Enter your Einthusan email and password in Settings.")
        val existing = signedIn
        if (!force && existing?.email == account.email && existing.password == account.password &&
            System.nanoTime() - signedInAt < TimeUnit.HOURS.toNanos(6)) return

        signedIn = null
        cookies.clear()
        val login = url("login/", language)
        val csrf = attribute(page(login, sessionHttp), "data-pageid") ?: fail("Einthusan could not load the sign-in form.")
        val payload = buildJsonObject { put("Email", account.email); put("Password", account.password) }
        val outcome = post(url("ajax/login/", language), login, "Login", payload, csrf, login = true)
        if (outcome.string("Event") != "redirect") fail("Einthusan did not accept that email and password.")
        signedIn = account
        signedInAt = System.nanoTime()
    }

    private suspend fun resolveAsset(source: EinthusanSource): Resolved {
        val watch = watchUrl("movie/watch/", source)
        val answer = response(sessionHttp, request(watch).header("Origin", ORIGIN).build())
        if (answer.code in setOf(301, 302, 303, 307, 308)) {
            val premium = premiumUrl(answer.location, source)
                ?: fail("Einthusan returned an unexpected playback redirect.")
            return premiumAsset(premium, watch)
                ?: browserHdAsset(source)
                ?: fail("Einthusan has no playable file for this title.")
        }
        if (!answer.successful) fail("Einthusan returned HTTP ${answer.code}. Try again.")
        val html = answer.body
        if ("Go Premium" in html) throw ExpiredSession(premiumRequired = true)
        if ("Our servers are almost maxed" in html) fail("Einthusan is at capacity. Try again in a minute.")
        val csrf = attribute(html, "data-pageid") ?: fail("Einthusan could not load the player.")
        val extras = attribute(html, "data-ejpingables") ?: throw ExpiredSession()
        val payload = buildJsonObject { put("EJOutcomes", extras); put("NativeHLS", false) }
        val ping = post(watchUrl("ajax/movie/watch/", source), watch, "UIVideoPlayer.PingOutcome", payload, csrf)
        val premium = premiumUrl(ping.string("Data")?.takeIf { ping.string("Event") == "redirect" }, source)
        if (premium != null) {
            premiumAsset(premium, watch)?.let { return it }
        }
        return asset(attribute(html, "data-mp4-link"), watch, allowUnverified = true)
            ?: fail("Einthusan has no playable file for this title.")
    }

    private suspend fun premiumAsset(premium: HttpUrl, watch: HttpUrl): Resolved? {
        val html = try { page(premium, sessionHttp, watch) } catch (_: EinthusanFailure) { return null }
        if ("Go Premium" in html) throw ExpiredSession(premiumRequired = true)
        return asset(attribute(html, "data-mp4-link"), premium, allowUnverified = false)
    }

    /** Requests a fresh HTML5 link through the provider's public HD player. */
    private suspend fun browserHdAsset(source: EinthusanSource): Resolved? {
        if (source.ultraHd) return null
        return withTimeoutOrNull(10_000) {
            // Keep the browser's CSRF cookie separate from the signed-in session.
            val browser = publicHttp.newBuilder().cookieJar(SessionCookies()).build()
            val watch = url("movie/watch/${source.id}/", source.language, "html5" to "true")
            try {
                val html = page(watch, browser)
                val csrf = attribute(html, "data-pageid") ?: return@withTimeoutOrNull null
                val candidates = attribute(html, "data-ejpingables")?.let(::decodePlayerData) as? JsonArray
                    ?: return@withTimeoutOrNull null
                val ranked = candidates.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toHttpUrlOrNull() }
                    .filter { it.isHttps && it.port == 443 && it.host in BROWSER_CDN_HOSTS &&
                        it.encodedPath == "/ping.m4v" && it.username.isEmpty() && it.password.isEmpty() }
                    .distinctBy { it.host }.sortedBy { BROWSER_CDN_HOSTS.indexOf(it.host) }
                if (ranked.isEmpty()) return@withTimeoutOrNull null
                val outcomes = encodePlayerData(JsonArray(ranked.map { JsonPrimitive(it.toString()) }))
                val payload = buildJsonObject { put("EJOutcomes", outcomes); put("NativeHLS", false) }
                val ajax = url("ajax/movie/watch/${source.id}/", source.language, "html5" to "true")
                val result = post(ajax, watch, "UIVideoPlayer.PingOutcome", payload, csrf, http = browser)
                val target = result.string("Data")?.takeIf { result.string("Event") == "redirect" }
                    ?.let(BASE::resolve)?.takeIf {
                        it.isEinthusan() && it.encodedPath.trimEnd('/') == "/movie/watch/${source.id}"
                    }
                val links = target?.queryParameter("el")?.let(::decodePlayerData) as? JsonObject
                val media = links?.string("MP4Link")?.toHttpUrlOrNull()?.takeIf {
                    it.host in BROWSER_CDN_HOSTS && it.encodedPath == "/d/etv/content/D${source.id}.mp4" &&
                        it.username.isEmpty() && it.password.isEmpty() && it.port == (if (it.isHttps) 443 else 80)
                }?.newBuilder()?.scheme("https")?.port(443)?.build() ?: return@withTimeoutOrNull null
                if (reachable(media, watch)) Resolved(media.toString(), playbackHeaders(watch)) else null
            } catch (_: EinthusanFailure) {
                null
            } catch (_: ExpiredSession) {
                null
            }
        }
    }

    private suspend fun asset(raw: String?, referer: HttpUrl, allowUnverified: Boolean): Resolved? {
        val link = raw?.toHttpUrlOrNull()?.takeIf { it.encodedPath.endsWith(".mp4", true) } ?: return null
        val hosts = when (link.host) {
            in CDN_HOSTS -> CDN_HOSTS
            else -> if (IPV4.matches(link.host)) CDN_HOSTS else return null
        }
        val headers = playbackHeaders(referer)
        val urls = hosts.map { link.newBuilder().scheme("https").host(it).port(443).build() }
        for (candidate in urls) {
            if (reachable(candidate, referer)) return Resolved(candidate.toString(), headers)
        }
        return if (allowUnverified) Resolved(urls.first().toString(), headers) else null
    }

    private suspend fun reachable(candidate: HttpUrl, referer: HttpUrl): Boolean {
        val request = request(candidate).header("Range", "bytes=0-0").header("Referer", referer.toString()).build()
        return withTimeoutOrNull(6_000) {
            try { publicHttp.send(request, 1).successful }
            catch (_: IOException) { false }
            catch (_: IllegalArgumentException) { false }
        } == true
    }

    private suspend fun findInAlphabet(query: SearchKey, letter: Char): ListingHit? {
        var budget = 14
        val fetched = mutableMapOf<Int, List<ListingHit>?>()
        suspend fun hits(page: Int): List<ListingHit>? {
            if (page in fetched) return fetched[page]
            val address = url("movie/results/", query.language, "find" to "Alphabets", "alpha" to "$letter", "page" to "$page")
            val result = alphaCache[address]?.value ?: if (budget-- > 0) {
                listing(address, publicHttp, 3_000)?.also { alphaCache.put(address, it) }
            } else null
            fetched[page] = result
            return result
        }
        val first = hits(1)
        if (first.isNullOrEmpty()) return null
        bestHit(first, query)?.let { return it }

        val wanted = sortKey(query.title)
        var low = 1
        var probe = 2
        var high = 1
        while (true) {
            val rows = hits(probe) ?: break
            bestHit(rows, query)?.let { return it }
            if (rows.firstOrNull()?.let { sortKey(it.title) <= wanted } != true) {
                high = probe - 1
                break
            }
            low = probe
            probe *= 2
        }
        while (low < high) {
            val middle = (low + high + 1) / 2
            val rows = hits(middle)
            rows?.let { bestHit(it, query) }?.let { return it }
            if (rows?.firstOrNull()?.let { sortKey(it.title) <= wanted } == true) low = middle else high = middle - 1
        }
        return listOf(low, low + 1, low - 1).filter { it > 0 }.firstNotNullOfOrNull { page ->
            hits(page)?.let { bestHit(it, query) }
        }
    }

    private suspend fun listing(url: HttpUrl, http: OkHttpClient, timeout: Long): List<ListingHit>? =
        withTimeoutOrNull(timeout) {
            try { searchHits(page(url, http)) } catch (_: EinthusanFailure) { null }
        }

    private suspend fun page(url: HttpUrl, http: OkHttpClient, referer: HttpUrl? = null): String {
        val answer = response(http, request(url).apply {
            if (referer != null) header("Referer", referer.toString()).header("Origin", ORIGIN)
        }.build())
        if (!answer.successful) fail("Einthusan returned HTTP ${answer.code}. Try again.")
        return answer.body
    }

    private suspend fun post(url: HttpUrl, referer: HttpUrl, event: String, payload: JsonObject, csrf: String,
                             login: Boolean = false, http: OkHttpClient = sessionHttp): JsonObject {
        val form = FormBody.Builder().add("xEvent", event).add("xJson", payload.toString())
            .add("arcVersion", "3").add("gorilla.csrf.Token", csrf).apply {
                if (login) add("appVersion", "59").add("tabID", "${csrf}48")
            }.build()
        val answer = response(http, request(url).post(form)
            .header("X-Requested-With", "XMLHttpRequest").header("Origin", ORIGIN)
            .header("Referer", referer.toString()).build())
        if (!login && answer.code in setOf(401, 403, 405)) throw ExpiredSession()
        if (!answer.successful) fail("Einthusan returned HTTP ${answer.code}. Try again.")
        return runCatching { Json.parseToJsonElement(answer.body) as? JsonObject }.getOrNull()
            ?: fail("Einthusan returned an unreadable response.")
    }

    private suspend fun response(http: OkHttpClient, request: Request) = try {
        http.send(request, 2 * 1024 * 1024)
    } catch (_: IOException) {
        fail("Could not reach Einthusan. Check your connection and try again.")
    } catch (_: IllegalArgumentException) {
        fail("Einthusan returned an unreadable response.")
    }

    private data class SearchKey(val title: String, val year: Int?, val imdbId: String?, val language: EinthusanLanguage)
    private class ExpiredSession(val premiumRequired: Boolean = false) : Exception()

    private class TimedCache<K, V>(private val limit: Int) {
        data class Entry<V>(val value: V, val time: Long = System.nanoTime())
        private val entries = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)

        @Synchronized
        operator fun get(key: K): Entry<V>? = entries[key]?.takeIf {
            System.nanoTime() - it.time < TimeUnit.HOURS.toNanos(24)
        }

        @Synchronized
        fun put(key: K, value: V) {
            entries[key] = Entry(value)
            if (entries.size > limit) entries.remove(entries.keys.first())
        }
    }

    internal data class ListingHit(val id: String, val title: String, val year: Int?, val imdbId: String?, val cc: Boolean, val uhd: Boolean)

    companion object {
        const val available = true
        private const val ORIGIN = "https://einthusan.tv"
        private const val USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML like Gecko) Chrome/139.0.0.0 Safari/537.36"
        private val BASE = ORIGIN.toHttpUrl()
        private val CDN_HOSTS = listOf("cdn2.einthusan.io", "cdn1.einthusan.io")
        private val BROWSER_CDN_HOSTS = CDN_HOSTS + "cdn4.einthusan.io"
        private val IPV4 = Regex("""(?:\d{1,3}\.){3}\d{1,3}""")
        private val HTML = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        private val SUMMARY = Regex("""<section\b[^>]*\bid\s*=\s*["']UIMovieSummary["'][^>]*>(.*?)</section>""", HTML)
        private val ROW = Regex("""<li\b[^>]*>\s*<div\b[^>]*\bclass\s*=\s*["']block1["']""", HTML)
        private val WATCH_ID = Regex("""/movie/watch/([A-Za-z0-9]+)(?=[/?'"]|$)""")
        private val TITLE = Regex("""<h3\b[^>]*>(.*?)</h3>""", HTML)
        private val INFO = Regex("""<div\b[^>]*\bclass\s*=\s*["']info["'][^>]*>(.*?)</div>""", HTML)
        private val YEAR = Regex("""<p\b[^>]*>\s*(\d{4})""", HTML)
        private val EXTRAS = Regex("""<div\b[^>]*\bclass\s*=\s*["']extras["'][^>]*>(.*?)</div>""", HTML)
        private val IMDB = Regex("""\btt\d+\b""", RegexOption.IGNORE_CASE)
        private val PARAGRAPH = Regex("""<p\b[^>]*>""", HTML)
        private val CLASS = Regex("""\bclass\s*=\s*(["'])(.*?)\1""", HTML)
        private val TAG = Regex("""<[^>]+>""")
        private val ENTITY = Regex("""&(#x[\da-fA-F]+|#\d+|amp|quot|apos|lt|gt|nbsp);""")
        private val ACCENT = Regex("""\p{M}+""")
        private val NON_WORD = Regex("""[^a-z0-9]+""")
        private val ARTICLE = Regex("""^(the|an|a) """)
        private val SPACE = Regex("""\s+""")

        internal fun searchHits(html: String): List<ListingHit> {
            val content = SUMMARY.find(html)?.groupValues?.get(1) ?: return emptyList()
            return ROW.split(content).drop(1).mapNotNull { row ->
                val id = WATCH_ID.find(row)?.groupValues?.get(1) ?: return@mapNotNull null
                val title = TITLE.find(row)?.groupValues?.get(1)?.let {
                    decode(it.replace(TAG, " ")).replace(SPACE, " ").trim()
                }?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val info = INFO.find(row)?.groupValues?.get(1).orEmpty()
                val extras = EXTRAS.find(row)?.groupValues?.get(1).orEmpty()
                ListingHit(id, title, YEAR.find(info)?.groupValues?.get(1)?.toIntOrNull(),
                    IMDB.find(extras)?.value?.lowercase(),
                    PARAGRAPH.findAll(info).any { hasClass(it.value, "cc") },
                    hasClass(row, "ultrahd"))
            }
        }

        private fun hasClass(html: String, name: String): Boolean =
            CLASS.findAll(html).any { match -> match.groupValues[2].split(SPACE).any { it.equals(name, true) } }

        private fun bestHit(hits: List<ListingHit>, query: SearchKey): ListingHit? {
            query.imdbId?.let { id -> hits.firstOrNull { it.imdbId == id }?.let { return it } }
            val title = normalize(query.title)
            if (title.isEmpty()) return null
            return hits.asSequence().filter { query.year == null || it.year == null || abs(query.year - it.year) <= 1 }
                .map { it to titleScore(normalize(it.title), title) }.filter { it.second >= 0.86 }
                .maxByOrNull { it.second }?.first
        }

        private fun titleScore(a: String, b: String): Double {
            if (a.isEmpty()) return 0.0
            val left = a.split(' ').toSet()
            val right = b.split(' ').toSet()
            var previous = IntArray(b.length + 1) { it }
            var current = IntArray(b.length + 1)
            for (i in a.indices) {
                current[0] = i + 1
                for (j in b.indices) {
                    current[j + 1] = minOf(previous[j + 1] + 1, current[j] + 1,
                        previous[j] + if (a[i] == b[j]) 0 else 1)
                }
                val swap = previous
                previous = current
                current = swap
            }
            return maxOf(1.0 - previous.last().toDouble() / maxOf(a.length, b.length),
                left.intersect(right).size.toDouble() / left.union(right).size)
        }

        private fun normalize(title: String): String = Normalizer.normalize(title.lowercase(), Normalizer.Form.NFD)
            .replace(ACCENT, "").replace(NON_WORD, " ").trim().replace(ARTICLE, "")
        private fun sortKey(title: String): String = title.lowercase().dropWhile { !it.isLetterOrDigit() }

        private fun attribute(html: String, name: String): String? =
            Regex("""\b$name\s*=\s*(["'])(.*?)\1""", HTML).find(html)?.groupValues?.get(2)
                ?.takeIf(String::isNotBlank)?.let(::decode)

        private fun decode(value: String): String = ENTITY.replace(value) { match ->
            val entity = match.groupValues[1]
            when (entity) {
                "amp" -> "&"
                "quot" -> "\""
                "apos" -> "'"
                "lt" -> "<"
                "gt" -> ">"
                "nbsp" -> " "
                else -> (if (entity.startsWith("#x")) entity.drop(2).toIntOrNull(16) else entity.drop(1).toIntOrNull())
                    ?.takeIf { it in 1..0x10FFFF && it !in 0xD800..0xDFFF }?.let { String(Character.toChars(it)) }
                    ?: match.value
            }
        }

        private fun url(path: String, language: EinthusanLanguage, vararg parameters: Pair<String, String>): HttpUrl =
            BASE.newBuilder().addPathSegments(path).addQueryParameter("lang", language.key).apply {
                for ((name, value) in parameters) addQueryParameter(name, value)
            }.build()

        private fun HttpUrl.isEinthusan(): Boolean = isHttps && host == BASE.host && port == BASE.port &&
            username.isEmpty() && password.isEmpty()

        private fun decodePlayerData(value: String): JsonElement? {
            if (value.length !in 13..16_384) return null
            val base64 = value.take(10) + value.last() + value.substring(12, value.lastIndex)
            return runCatching {
                Json.parseToJsonElement(Base64.getDecoder().decode(base64).decodeToString())
            }.getOrNull()
        }

        private fun encodePlayerData(value: JsonArray): String {
            val base64 = Base64.getEncoder().encodeToString(value.toString().encodeToByteArray())
            return base64.take(10) + "ZZ" + base64.substring(11) + base64[10]
        }

        private fun playbackHeaders(referer: HttpUrl): Map<String, String> =
            mapOf("Referer" to referer.toString(), "User-Agent" to USER_AGENT)

        private fun premiumUrl(raw: String?, source: EinthusanSource): HttpUrl? =
            raw?.let(BASE::resolve)?.takeIf {
                it.isEinthusan() && it.encodedPath.trimEnd('/') == "/premium/movie/watch/${source.id}"
            }?.newBuilder()?.removeAllQueryParameters("uhd")?.apply {
                if (source.ultraHd) addQueryParameter("uhd", "true")
            }?.build()

        private fun watchUrl(path: String, source: EinthusanSource): HttpUrl =
            url("$path${source.id}/", source.language).newBuilder().apply {
                if (source.ultraHd) addQueryParameter("uhd", "true")
            }.build()

        private fun request(url: HttpUrl) = Request.Builder().url(url).header("User-Agent", USER_AGENT)
        private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
        private fun fail(message: String): Nothing = throw EinthusanFailure(message)
    }
}
