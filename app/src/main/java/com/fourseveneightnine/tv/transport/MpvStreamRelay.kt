package com.fourseveneightnine.tv.transport

import java.net.URI
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import okhttp3.OkHttpClient

/**
 * On-box HTTP relay for the mpv engine. ffmpeg's native DNS resolution hangs indefinitely on
 * this Fire OS build: a raw-IP URL reaches fileLoaded in ~100ms while ANY hostname URL — http or
 * https alike — never produces a byte and dies on the 60s open watchdog (proven live,
 * 2026-08-04). ExoPlayer is unaffected because okhttp resolves through the Java stack. So mpv
 * never dials out itself: it opens 127.0.0.1 and this relay streams the real URL through okhttp,
 * which also sidesteps ffmpeg's TLS entirely.
 *
 * Registry not persistence: tokens die with the process. `lastOpenMedia()` keeps the ORIGINAL
 * URL, so a retry/failover after restart re-registers instead of chasing a dead token.
 */
object MpvStreamRelay {
    data class Upstream(val url: String, val headers: Map<String, String>)

    private val entries = ConcurrentHashMap<String, Upstream>()
    private val order = ConcurrentLinkedQueue<String>()

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(20))
            .readTimeout(Duration.ofSeconds(30))
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun register(url: String, headers: Map<String, String>): String {
        val token = UUID.randomUUID().toString().replace("-", "")
        entries[token] = Upstream(url, headers)
        order.add(token)
        while (order.size > MAX_ENTRIES) order.poll()?.let(entries::remove)
        return token
    }

    fun lookup(token: String): Upstream? = entries[token]

    fun relayURL(token: String, httpPort: Int): String = "http://127.0.0.1:$httpPort$PATH/$token"

    /** Hostname URLs must relay; literal-IP URLs (the phone byte bridge) go direct — no DNS. */
    fun needsRelay(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host ?: return false
        return !isIpLiteral(host)
    }

    fun isIpLiteral(host: String): Boolean {
        val bare = host.removePrefix("[").removeSuffix("]")
        if (bare.contains(':')) return true // IPv6 literal
        val parts = bare.split('.')
        return parts.size == 4 && parts.all { (it.toIntOrNull() ?: -1) in 0..255 && it.isNotEmpty() }
    }

    const val PATH = "/mpv-relay"
    private const val MAX_ENTRIES = 8
}
