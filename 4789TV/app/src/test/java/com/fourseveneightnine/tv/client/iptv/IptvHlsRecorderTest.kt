package com.fourseveneightnine.tv.client.iptv

import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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


    @Test fun `encrypted init keeps its declared key and IV after media keys change`() = withServer { server, base ->
        val initKey = ByteArray(16) { it.toByte() }
        val mediaKey = ByteArray(16) { (it + 32).toByte() }
        val initIv = ByteArray(16).also { it[15] = 9 }
        val mediaIv = ByteArray(16).also { it[15] = 2 }
        val init = "init-section".encodeToByteArray()
        val first = "encrypted-fragment".encodeToByteArray()
        val second = "clear-fragment".encodeToByteArray()
        server.route("/init-key", initKey)
        server.route("/media-key", mediaKey)
        server.route("/init.mp4", encrypted(init, initKey, initIv))
        server.route("/first.m4s", encrypted(first, mediaKey, mediaIv))
        server.route("/second.m4s", second)
        server.route("/live.m3u8", """
            #EXTM3U
            #EXT-X-VERSION:6
            #EXT-X-TARGETDURATION:2
            #EXT-X-MEDIA-SEQUENCE:41
            #EXT-X-KEY:METHOD=AES-128,URI="init-key",IV=0x00000000000000000000000000000009
            #EXT-X-MAP:URI="init.mp4"
            #EXT-X-KEY:METHOD=AES-128,URI="media-key",IV=0x00000000000000000000000000000002
            #EXTINF:2.0,
            first.m4s
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:2.0,
            second.m4s
            #EXT-X-ENDLIST
        """.trimIndent().encodeToByteArray())
        assertArrayEquals(init + first + second, recordFixture(base))
        assertEquals(1, server.requests("/init-key"))
        assertEquals(1, server.requests("/media-key"))
    }

    @Test fun `encrypted init range and sequence-IV media keep request headers`() = withServer { server, base ->
        val key = ByteArray(16) { (it + 1).toByte() }
        val initIv = ByteArray(16).also { it[15] = 9 }
        val mediaIv = ByteArray(16).also { it[15] = 7 }
        val init = "ranged-init".encodeToByteArray()
        val media = "ranged-media".encodeToByteArray()
        val encryptedInit = encrypted(init, key, initIv)
        val encryptedMedia = encrypted(media, key, mediaIv)
        val offset = 5
        val mediaOffset = offset + encryptedInit.size
        server.route("/key", key)
        server.route("/bundle.mp4", ByteArray(offset) + encryptedInit + encryptedMedia)
        server.route("/live.m3u8", """
            #EXTM3U
            #EXT-X-VERSION:6
            #EXT-X-TARGETDURATION:2
            #EXT-X-MEDIA-SEQUENCE:7
            #EXT-X-KEY:METHOD=AES-128,URI="key",IV=0x00000000000000000000000000000009
            #EXT-X-MAP:URI="bundle.mp4",BYTERANGE="${encryptedInit.size}@$offset"
            #EXT-X-KEY:METHOD=AES-128,URI="key"
            #EXTINF:2.0,
            #EXT-X-BYTERANGE:${encryptedMedia.size}@$mediaOffset
            bundle.mp4
            #EXT-X-ENDLIST
        """.trimIndent().encodeToByteArray())
        assertArrayEquals(init + media, recordFixture(base, mapOf("X-Fixture" to "recording")))
        assertEquals(listOf("bytes=$offset-${mediaOffset - 1}",
            "bytes=$mediaOffset-${mediaOffset + encryptedMedia.size - 1}"),
            server.requestHeaders("/bundle.mp4").map { it["range"] })
        for (path in listOf("/live.m3u8", "/key", "/bundle.mp4"))
            assertTrue(server.requestHeaders(path).all { it["x-fixture"] == "recording" })
    }

    @Test fun `clear init stays clear when only following media is encrypted`() = withServer { server, base ->
        val key = ByteArray(16) { (it + 7).toByte() }
        val iv = ByteArray(16).also { it[15] = 1 }
        val init = "clear-init".encodeToByteArray()
        val media = "encrypted-media".encodeToByteArray()
        server.route("/key", key)
        server.route("/init.mp4", init)
        server.route("/one.m4s", encrypted(media, key, iv))
        server.route("/live.m3u8", """
            #EXTM3U
            #EXT-X-VERSION:6
            #EXT-X-TARGETDURATION:2
            #EXT-X-MAP:URI="init.mp4"
            #EXT-X-KEY:METHOD=AES-128,URI="key",IV=0x00000000000000000000000000000001
            #EXTINF:2.0,
            one.m4s
            #EXT-X-ENDLIST
        """.trimIndent().encodeToByteArray())
        assertArrayEquals(init + media, recordFixture(base))
    }

    @Test fun `encrypted init without explicit IV fails before media or key fetch`() = withServer { server, base ->
        server.route("/live.m3u8", """
            #EXTM3U
            #EXT-X-VERSION:6
            #EXT-X-TARGETDURATION:2
            #EXT-X-MEDIA-SEQUENCE:99
            #EXT-X-KEY:METHOD=AES-128,URI="key"
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:2.0,
            one.m4s
            #EXT-X-ENDLIST
        """.trimIndent().encodeToByteArray())
        val failure = assertThrows(IllegalStateException::class.java) { recordFixture(base) }
        assertEquals("Missing HLS init IV", failure.message)
        for (path in listOf("/init.mp4", "/key", "/one.m4s")) assertEquals(0, server.requests(path))
    }

    @Test fun `unsupported encryption still fails before init fetch`() = withServer { server, base ->
        server.route("/live.m3u8", """
            #EXTM3U
            #EXT-X-VERSION:6
            #EXT-X-TARGETDURATION:2
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="key"
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:2.0,
            one.m4s
            #EXT-X-ENDLIST
        """.trimIndent().encodeToByteArray())
        val failure = assertThrows(IllegalStateException::class.java) { recordFixture(base) }
        assertEquals("Unsupported HLS encryption", failure.message)
        assertEquals(0, server.requests("/init.mp4"))
    }

    @Test fun `external default audio fails before video or audio playlist fetch`() = withServer { server, base ->
        // Both child playlists are valid: the old recorder could finish from video bytes alone.
        server.route("/video.ts", byteArrayOf(0x47, 1, 2))
        server.route("/audio.aac", "separate-audio".encodeToByteArray())
        server.route("/video.m3u8", "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2.0,\nvideo.ts\n#EXT-X-ENDLIST\n".encodeToByteArray())
        server.route("/audio.m3u8", "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2.0,\naudio.aac\n#EXT-X-ENDLIST\n".encodeToByteArray())
        server.route("/live.m3u8", """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Default",DEFAULT=YES,AUTOSELECT=YES,URI="audio.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="In-band",DEFAULT=NO
            #EXT-X-STREAM-INF:BANDWIDTH=1000,AUDIO="audio"
            video.m3u8
        """.trimIndent().encodeToByteArray())
        val failure = assertThrows(IllegalStateException::class.java) { recordFixture(base) }
        assertEquals("Unsupported HLS separate audio", failure.message)
        assertEquals(0, server.requests("/video.m3u8"))
        assertEquals(0, server.requests("/audio.m3u8"))
    }

    @Test fun `all external audio with no default also fails clearly`() = withServer { server, base ->
        server.route("/live.m3u8", """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="External",AUTOSELECT=YES,URI="audio.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=1000,AUDIO="audio"
            video.m3u8
        """.trimIndent().encodeToByteArray())
        val failure = assertThrows(IllegalStateException::class.java) { recordFixture(base) }
        assertEquals("Unsupported HLS separate audio", failure.message)
    }

    @Test fun `in-band defaults and unselected external audio remain supported`() {
        val groups = listOf(
            // A non-default external track is not a reason to reject the default in-band audio.
            """#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Extra",DEFAULT=NO,URI="extra.m3u8"
                |#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Main",DEFAULT=YES,AUTOSELECT=YES""".trimMargin(),
            // Without a default, the recorder can choose the offered in-band rendition.
            """#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Extra",AUTOSELECT=YES,URI="extra.m3u8"
                |#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Main""".trimMargin(),
            // External audio in another group does not belong to this variant.
            """#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="other",NAME="Other",DEFAULT=YES,AUTOSELECT=YES,URI="extra.m3u8"
                |#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Main",DEFAULT=YES,AUTOSELECT=YES""".trimMargin()
        )
        for (group in groups) withServer { server, base ->
            val media = byteArrayOf(0x47, 1, 2)
            server.route("/one.ts", media)
            server.route("/video.m3u8", "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2.0,\none.ts\n#EXT-X-ENDLIST\n".encodeToByteArray())
            server.route("/live.m3u8", "#EXTM3U\n$group\n#EXT-X-STREAM-INF:BANDWIDTH=1000,AUDIO=\"audio\"\nvideo.m3u8\n".encodeToByteArray())
            assertArrayEquals(media, recordFixture(base))
            assertEquals(0, server.requests("/extra.m3u8"))
        }
    }

    @Test fun `unreferenced external group does not block a muxed master`() = withServer { server, base ->
        val media = byteArrayOf(0x47, 3, 4)
        server.route("/one.ts", media)
        server.route("/video.m3u8", "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2.0,\none.ts\n#EXT-X-ENDLIST\n".encodeToByteArray())
        server.route("/live.m3u8", """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="other",NAME="Other",DEFAULT=YES,AUTOSELECT=YES,URI="extra.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=1000
            video.m3u8
        """.trimIndent().encodeToByteArray())
        assertArrayEquals(media, recordFixture(base))
        assertEquals(0, server.requests("/extra.m3u8"))
    }

    @Test fun `default audio already in the selected playlist does not need a separate fetch`() = withServer { server, base ->
        val media = byteArrayOf(0x47, 5, 6)
        server.route("/one.ts", media)
        server.route("/video.m3u8", "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2.0,\none.ts\n#EXT-X-ENDLIST\n".encodeToByteArray())
        server.route("/live.m3u8", """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Main",DEFAULT=YES,AUTOSELECT=YES,URI="video.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=1000,AUDIO="audio"
            video.m3u8
        """.trimIndent().encodeToByteArray())
        assertArrayEquals(media, recordFixture(base))
        assertEquals(1, server.requests("/video.m3u8"))
    }

    @Test fun `cancelling a held encrypted init read does not reach media or completion`() = withServer { server, base ->
        val key = ByteArray(16) { it.toByte() }
        val iv = ByteArray(16).also { it[15] = 1 }
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.route("/key", key)
        server.route("/init.mp4", encrypted("held-init".encodeToByteArray(), key, iv))
        server.holdBody("/init.mp4", started, release)
        server.route("/live.m3u8", """
            #EXTM3U
            #EXT-X-VERSION:6
            #EXT-X-TARGETDURATION:2
            #EXT-X-KEY:METHOD=AES-128,URI="key",IV=0x00000000000000000000000000000001
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:2.0,
            one.m4s
            #EXT-X-ENDLIST
        """.trimIndent().encodeToByteArray())
        val directory = Files.createTempDirectory("iptv-init-cancel").toFile()
        try {
            runBlocking {
                val recording = async(Dispatchers.IO) {
                    IptvHlsRecorder(OkHttpClient()).record("$base/live.m3u8", emptyMap(), directory,
                        "sample", System.currentTimeMillis() + 10_000)
                }
                try {
                    assertTrue("Init request did not arrive", started.await(3, TimeUnit.SECONDS))
                    withTimeout(2_000) { recording.cancelAndJoin() }
                    assertTrue(recording.isCancelled)
                    assertEquals(0, server.requests("/one.m4s"))
                    assertEquals(0, server.requests("/key"))
                    // The service owns deletion; the recorder must not write partial init bytes.
                    assertEquals(0L, java.io.File(directory, "sample.mp4").length())
                } finally { recording.cancel(); release.countDown() }
            }
        } finally { release.countDown(); directory.deleteRecursively() }
    }

    private fun encrypted(plain: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }.doFinal(plain)

    private fun recordFixture(base: String, headers: Map<String, String> = emptyMap()): ByteArray {
        val directory = Files.createTempDirectory("iptv-hls-fixture").toFile()
        try {
            return runBlocking { IptvHlsRecorder(OkHttpClient()).record("$base/live.m3u8", headers,
                directory, "sample", System.currentTimeMillis() + 10_000).readBytes() }
        } finally { directory.deleteRecursively() }
    }

    private fun withServer(block: (TestServer, String) -> Unit) {
        TestServer().use { server -> block(server, "http://127.0.0.1:${server.port}") }
    }

    private class TestServer : AutoCloseable {
        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val routes = ConcurrentHashMap<String, ByteArray>()
        private val received = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
        private val bodyGates = ConcurrentHashMap<String, Pair<CountDownLatch, CountDownLatch>>()
        val port: Int get() = socket.localPort
        private val thread = Thread {
            while (!socket.isClosed) try {
                socket.accept().use { peer ->
                    peer.soTimeout = 5_000
                    val reader = peer.getInputStream().bufferedReader()
                    val path = reader.readLine()?.split(' ')?.getOrNull(1)?.substringBefore('?') ?: "/"
                    var range: IntRange? = null
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val header = reader.readLine() ?: break
                        if (header.isEmpty()) break
                        headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                        if (header.startsWith("Range: bytes=")) {
                            val text = header.substringAfter("Range: bytes=")
                            val from = text.substringBefore('-').toIntOrNull()
                            val to = text.substringAfter('-').toIntOrNull()
                            if (from != null && to != null) range = from..to
                        }
                    }
                    received += path to headers.toMap()
                    val bytes = routes[path] ?: ByteArray(0)
                    val selected = range?.let { bytes.copyOfRange(it.first, it.last + 1) } ?: bytes
                    peer.getOutputStream().apply {
                        write("HTTP/1.1 ${if (range == null) "200 OK" else "206 Partial Content"}\r\n".encodeToByteArray())
                        write("Content-Length: ${selected.size}\r\nConnection: close\r\n\r\n".encodeToByteArray())
                        flush()
                        bodyGates[path]?.let { (started, release) ->
                            started.countDown()
                            release.await(5, TimeUnit.SECONDS)
                        }
                        write(selected)
                        flush()
                    }
                }
            } catch (_: Exception) { if (!socket.isClosed) break }
        }
        init { thread.isDaemon = true; thread.start() }
        fun route(path: String, bytes: ByteArray) { routes[path] = bytes }
        fun requests(path: String): Int = received.count { it.first == path }
        fun requestHeaders(path: String): List<Map<String, String>> = received.filter { it.first == path }.map { it.second }
        fun holdBody(path: String, started: CountDownLatch, release: CountDownLatch) {
            bodyGates[path] = started to release
        }
        override fun close() { socket.close(); thread.join(1_000) }
    }
}
