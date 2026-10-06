package com.fourseveneightnine.tv.client.iptv

import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class IptvHlsRecorderTest {
    @Test fun `relative live segments are concatenated once`() = withServer { server, base ->
        val first = byteArrayOf(0x47, 1, 2)
        val second = byteArrayOf(0x47, 3, 4)
        server.route("/one.ts", first)
        server.route("/two.ts", second)
        server.route("/live.m3u8", """
            #EXTM3U
            #EXT-X-TARGETDURATION:2
            #EXT-X-MEDIA-SEQUENCE:7
            #EXTINF:2.0,
            one.ts
            #EXTINF:2.0,
            two.ts
            #EXT-X-ENDLIST
        """.trimIndent().encodeToByteArray())
        val directory = Files.createTempDirectory("iptv-hls").toFile()
        try {
            val file = runBlocking { IptvHlsRecorder(OkHttpClient()).record(
                "$base/live.m3u8", emptyMap(), directory, "sample", System.currentTimeMillis() + 10_000) }
            assertArrayEquals(first + second, file.readBytes())
        } finally { directory.deleteRecursively() }
    }

    @Test fun `AES128 segment uses the explicit IV`() = withServer { server, base ->
        val key = ByteArray(16) { it.toByte() }
        val iv = ByteArray(16).also { it[15] = 1 }
        val plain = byteArrayOf(0x47, 5, 6, 7)
        val encrypted = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }.doFinal(plain)
        server.route("/key", key)
        server.route("/encrypted.ts", encrypted)
        server.route("/live.m3u8", """
            #EXTM3U
            #EXT-X-TARGETDURATION:2
            #EXT-X-MEDIA-SEQUENCE:1
            #EXT-X-KEY:METHOD=AES-128,URI="key",IV=0x00000000000000000000000000000001
            #EXTINF:2.0,
            encrypted.ts
            #EXT-X-ENDLIST
        """.trimIndent().encodeToByteArray())
        val directory = Files.createTempDirectory("iptv-aes").toFile()
        try {
            val file = runBlocking { IptvHlsRecorder(OkHttpClient()).record(
                "$base/live.m3u8", emptyMap(), directory, "sample", System.currentTimeMillis() + 10_000) }
            assertArrayEquals(plain, file.readBytes())
        } finally { directory.deleteRecursively() }
    }

    @Test fun `byte ranges from a shared segment URL are each written once`() = withServer { server, base ->
        val all = byteArrayOf(0x47, 1, 2, 0x47, 3, 4)
        server.route("/segments.ts", all)
        server.route("/live.m3u8", """
            #EXTM3U
            #EXT-X-TARGETDURATION:2
            #EXT-X-BYTERANGE:3@0
            segments.ts
            #EXT-X-BYTERANGE:3@3
            segments.ts
            #EXT-X-ENDLIST
        """.trimIndent().encodeToByteArray())
        val directory = Files.createTempDirectory("iptv-range").toFile()
        try {
            val file = runBlocking { IptvHlsRecorder(OkHttpClient()).record(
                "$base/live.m3u8", emptyMap(), directory, "sample", System.currentTimeMillis() + 10_000) }
            assertArrayEquals(all, file.readBytes())
        } finally { directory.deleteRecursively() }
    }

    private fun withServer(block: (TestServer, String) -> Unit) {
        TestServer().use { server -> block(server, "http://127.0.0.1:${server.port}") }
    }

    private class TestServer : AutoCloseable {
        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val routes = ConcurrentHashMap<String, ByteArray>()
        val port: Int get() = socket.localPort
        private val thread = Thread {
            while (!socket.isClosed) try {
                socket.accept().use { peer ->
                    peer.soTimeout = 5_000
                    val reader = peer.getInputStream().bufferedReader()
                    val path = reader.readLine()?.split(' ')?.getOrNull(1)?.substringBefore('?') ?: "/"
                    var range: IntRange? = null
                    while (true) {
                        val header = reader.readLine() ?: break
                        if (header.isEmpty()) break
                        if (header.startsWith("Range: bytes=")) {
                            val text = header.substringAfter("Range: bytes=")
                            val from = text.substringBefore('-').toIntOrNull()
                            val to = text.substringAfter('-').toIntOrNull()
                            if (from != null && to != null) range = from..to
                        }
                    }
                    val bytes = routes[path] ?: ByteArray(0)
                    val selected = range?.let { bytes.copyOfRange(it.first, it.last + 1) } ?: bytes
                    peer.getOutputStream().apply {
                        write("HTTP/1.1 ${if (range == null) "200 OK" else "206 Partial Content"}\r\n".encodeToByteArray())
                        write("Content-Length: ${selected.size}\r\nConnection: close\r\n\r\n".encodeToByteArray())
                        write(selected)
                        flush()
                    }
                }
            } catch (_: Exception) { if (!socket.isClosed) break }
        }
        init { thread.isDaemon = true; thread.start() }
        fun route(path: String, bytes: ByteArray) { routes[path] = bytes }
        override fun close() { socket.close(); thread.join(1_000) }
    }
}
