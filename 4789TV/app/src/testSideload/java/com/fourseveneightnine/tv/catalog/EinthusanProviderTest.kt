package com.fourseveneightnine.tv.catalog

import com.fourseveneightnine.tv.client.data.settings.SettingsDocument
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder
import java.util.Base64

class EinthusanProviderTest {
    private val account = EinthusanAccount("test@example.invalid", "test+ & password", true, EinthusanLanguage.Tamil)

    @Test
    fun lookupIsAnonymousAndReturnsOneRestorableTicket() = runBlocking {
        var requests = 0
        val provider = provider { request ->
            requests++
            assertEquals("/movie/results/", request.url.encodedPath)
            assertEquals("telugu", request.url.queryParameter("lang"))
            assertEquals("Alphabets", request.url.queryParameter("find"))
            assertNull(request.header("Cookie"))
            answer(request, body = listing)
        }
        val source = provider.find("Hanu Man", 2024, "tt15427062", "te", account)
        assertEquals(EinthusanSource("9xQz", EinthusanLanguage.Telugu, true, true), source)
        assertEquals(source, EinthusanSource.parse(source?.row("Hanu Man")?.url))
        assertEquals(source, provider.find("Hanu Man", 2024, "tt15427062", "te", account))
        assertEquals(1, requests)
    }

    @Test
    fun parserUsesSubtitleClassTokenAndKeepsNestedRowsIntact() {
        val hits = EinthusanProvider.searchHits(listing)
        assertEquals(2, hits.size)
        assertEquals("tt15427062", hits[0].imdbId)
        assertTrue(hits[0].uhd)
        assertTrue(hits[0].cc)
        assertEquals("Hanuman & Co", hits[1].title)
        assertFalse(hits[1].cc)
        assertFalse(hits[1].uhd)
    }

    @Test
    fun hdOmissionAndCdnFallbackPreserveTheSignedQuery() = runBlocking {
        val cdns = mutableListOf<String>()
        val provider = provider { request ->
            if (request.url.host.endsWith(".io")) {
                cdns += request.url.host
                assertEquals("bytes=0-0", request.header("Range"))
                assertEquals("signed+part", request.url.queryParameter("md5"))
                answer(request, if (request.url.host.startsWith("cdn2")) 404 else 206, body = "x")
            } else when (request.url.encodedPath) {
                "/login/" -> answer(request, body = """<html data-pageid="TOK&#43;ABC==">""", cookie = "csrf=token; Path=/; Secure")
                "/ajax/login/" -> {
                    assertEquals("TOK+ABC==", form(request, "gorilla.csrf.Token"))
                    assertEquals("TOK+ABC==48", form(request, "tabID"))
                    assertTrue(form(request, "xJson")!!.contains(account.password))
                    answer(request, body = """{"Event":"redirect"}""", cookie = "session=test; Path=/; Secure")
                }
                "/movie/watch/1798/" -> {
                    assertNull(request.url.queryParameter("uhd"))
                    answer(request, body = """<html data-pageid="token"><div data-ejpingables="valid"></div>""")
                }
                "/ajax/movie/watch/1798/" -> {
                    assertNull(request.url.queryParameter("uhd"))
                    assertEquals("UIVideoPlayer.PingOutcome", form(request, "xEvent"))
                    answer(request, body = """{"Event":"redirect","Data":"/premium/movie/watch/1798/?lang=malayalam&uhd="}""")
                }
                "/premium/movie/watch/1798/" -> {
                    assertNull(request.url.queryParameter("uhd"))
                    answer(request, body = """<div data-mp4-link="https://192.0.2.1/etv/content/D1798.mp4?e=1&amp;md5=signed%2Bpart"></div>""")
                }
                else -> error("unexpected fixture request")
            }
        }
        val source = EinthusanSource("1798", EinthusanLanguage.Malayalam, false, false)
        val media = provider.resolve(source, account)
        assertEquals(listOf("cdn2.einthusan.io", "cdn1.einthusan.io"), cdns)
        assertEquals("https://cdn1.einthusan.io/etv/content/D1798.mp4?e=1&md5=signed%2Bpart", media.url)
        assertFalse(media.headers.containsKey("Cookie"))
        assertFalse(media.headers["User-Agent"].orEmpty().contains(','))
        assertFalse(source.reference.contains("md5"))
    }

    @Test
    fun uhdRestoresRedirectFlagAndRetriesAnExpiredSessionOnce() = runBlocking {
        var logins = 0
        var pings = 0
        val provider = provider { request ->
            when (request.url.encodedPath) {
                "/login/" -> answer(request, body = """<html data-pageid="token">""")
                "/ajax/login/" -> {
                    logins++
                    answer(request, body = """{"Event":"redirect"}""")
                }
                "/movie/watch/9xQz/" -> {
                    assertEquals("true", request.url.queryParameter("uhd"))
                    answer(request, body = """<html data-pageid="token"><div data-ejpingables="valid"></div>""")
                }
                "/ajax/movie/watch/9xQz/" -> {
                    assertEquals("true", request.url.queryParameter("uhd"))
                    if (++pings == 1) answer(request, 405) else answer(request,
                        body = """{"Event":"redirect","Data":"/premium/movie/watch/9xQz/?lang=telugu&uhd="}""")
                }
                "/premium/movie/watch/9xQz/" -> {
                    assertEquals("true", request.url.queryParameter("uhd"))
                    answer(request, body = """<div data-mp4-link="https://192.0.2.1/etv/content/B9xQz.mp4?e=1"></div>""")
                }
                "/etv/content/B9xQz.mp4" -> answer(request, 206, "x")
                else -> error("unexpected fixture request")
            }
        }
        assertTrue(provider.resolve(EinthusanSource("9xQz", EinthusanLanguage.Telugu, true, true), account)
            .url.startsWith("https://cdn2.einthusan.io/"))
        assertEquals(2, logins)
        assertEquals(2, pings)
    }

    @Test
    fun authenticatedWatchRedirectOpensThePremiumAssetWithoutPinging() = runBlocking {
        val provider = provider { request ->
            when (request.url.encodedPath) {
                "/login/" -> answer(request, body = """<html data-pageid="token">""")
                "/ajax/login/" -> answer(request, body = """{"Event":"redirect"}""")
                "/movie/watch/1798/" -> answer(request, 307).newBuilder()
                    .header("Location", "/premium/movie/watch/1798/?lang=malayalam&uhd=").build()
                "/premium/movie/watch/1798/" -> {
                    assertNull(request.url.queryParameter("uhd"))
                    answer(request, body = """<div data-mp4-link="https://192.0.2.1/etv/content/D1798.mp4?e=1"></div>""")
                }
                "/etv/content/D1798.mp4" -> answer(request, 206, "x")
                else -> error("unexpected fixture request")
            }
        }
        val media = provider.resolve(EinthusanSource("1798", EinthusanLanguage.Malayalam, true, false), account)
        assertEquals("https://cdn2.einthusan.io/etv/content/D1798.mp4?e=1", media.url)
        assertTrue(media.headers.getValue("Referer").contains("/premium/movie/watch/1798/"))
    }

    @Test
    fun hdBrowserPlayerSuppliesFreshMediaWhenPremiumServersReturn404() = runBlocking {
        val source = EinthusanSource("2czB", EinthusanLanguage.Malayalam, true, false)
        val pingOptions = listOf(
            "https://cdn1.einthusan.io/ping.m4v",
            "https://cdn4.einthusan.io/ping.m4v",
            "https://cdn2.einthusan.io/ping.m4v",
            "https://untrusted.example/ping.m4v",
        )
        val metadata = playerData(pingOptions.joinToString(prefix = "[", postfix = "]") { "\"$it\"" })
        val browserLink = playerData("""{"MP4Link":"http://cdn4.einthusan.io/d/etv/content/D2czB.mp4?e=1&md5=fresh"}""")
        val browserRequests = mutableListOf<String>()
        val provider = provider { request ->
            when (request.url.encodedPath) {
                "/login/" -> answer(request, body = """<html data-pageid="token">""")
                "/ajax/login/" -> answer(request, body = """{"Event":"redirect"}""")
                "/movie/watch/2czB/" -> if (request.url.queryParameter("html5") == "true") {
                    browserRequests += "watch"
                    answer(request, body = """<html data-pageid="browser"><div data-ejpingables="$metadata"></div>""")
                } else answer(request, 307).newBuilder()
                    .header("Location", "/premium/movie/watch/2czB/?lang=malayalam").build()
                "/premium/movie/watch/2czB/" -> answer(request,
                    body = """<div data-mp4-link="https://192.0.2.1/etv/content/D2czB.mp4?e=1"></div>""")
                "/etv/content/D2czB.mp4" -> answer(request, 404)
                "/ajax/movie/watch/2czB/" -> {
                    browserRequests += "handshake"
                    assertEquals("true", request.url.queryParameter("html5"))
                    assertEquals("browser", form(request, "gorilla.csrf.Token"))
                    val payload = Json.parseToJsonElement(requireNotNull(form(request, "xJson"))).jsonObject
                    val ranked = pingOptions.take(3).let { listOf(it[2], it[0], it[1]) }
                    assertEquals(playerData(ranked.joinToString(prefix = "[", postfix = "]", separator = ",") { "\"$it\"" }),
                        payload.getValue("EJOutcomes").jsonPrimitive.content)
                    val el = URLEncoder.encode(browserLink, Charsets.UTF_8)
                    answer(request, body = """{"Event":"redirect","Data":"/movie/watch/2czB/?lang=malayalam&html5=true&el=$el"}""")
                }
                "/d/etv/content/D2czB.mp4" -> {
                    browserRequests += "asset"
                    assertTrue(request.url.isHttps)
                    assertEquals("cdn4.einthusan.io", request.url.host)
                    assertEquals("fresh", request.url.queryParameter("md5"))
                    assertEquals("bytes=0-0", request.header("Range"))
                    answer(request, 206, "x")
                }
                else -> error("unexpected fixture request")
            }
        }
        val media = provider.resolve(source, account)
        assertEquals(listOf("watch", "handshake", "asset"), browserRequests)
        assertEquals("https://cdn4.einthusan.io/d/etv/content/D2czB.mp4?e=1&md5=fresh", media.url)
        assertFalse(media.headers.getValue("Referer").contains("el="))
    }

    @Test
    fun settingsPreservePasswordAndRespectDisableSwitch() = runBlocking {
        val document = SettingsDocument.parse("""{
            "einthusanEmail":" test@example.invalid ", "einthusanPassword":" pass + ",
            "einthusanEnabled":false, "einthusanLanguage":"kannada"
        }""")
        val imported = EinthusanAccount.from(document)
        assertEquals("test@example.invalid", imported.email)
        assertEquals(" pass + ", imported.password)
        assertEquals(EinthusanLanguage.Kannada, imported.language)
        assertTrue(imported.hasCredentials)
        assertFalse(imported.configured)
        assertNull(provider { error("disabled accounts must not send a request") }
            .find("Title", null, null, null, imported))
    }

    private fun provider(answer: (Request) -> Response): EinthusanProvider =
        EinthusanProvider(OkHttpClient.Builder().addInterceptor { answer(it.request()) }.build())

    private fun form(request: Request, name: String): String? {
        val body = request.body as FormBody
        return (0 until body.size).firstOrNull { body.name(it) == name }?.let(body::value)
    }

    private fun playerData(json: String): String {
        val raw = Base64.getEncoder().encodeToString(json.toByteArray())
        return raw.substring(0, 10) + "ZZ" + raw.substring(11) + raw[10]
    }

    private fun answer(request: Request, code: Int = 200, body: String = "", cookie: String? = null): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .body(body.toResponseBody()).apply { cookie?.let { addHeader("Set-Cookie", it) } }.build()

    private companion object {
        val listing = """
            <section id="UIMovieSummary"><ul>
            <li><div class="block1"><a href="/movie/watch/9xQz/?lang=telugu"></a></div>
            <div class="info"><p>2024<i class="icon ultrahd"></i></p><p class="cc">Subtitle</p></div>
            <a class="title"><h3>Hanu-Man</h3></a><ul><li>Cast member</li></ul>
            <div class="extras"><a href="https://www.imdb.com/title/tt15427062/">IMDb</a></div>
            </li>
            <li><div class="block1"><a href="/movie/watch/7bWm/?lang=telugu"></a></div>
            <div class="info"><p>1998</p><p class="no-cc cc-extra">Subtitle unavailable</p></div>
            <a class="title"><h3>Hanuman &amp; Co</h3></a></li>
            </ul></section>
        """.trimIndent()
    }
}
