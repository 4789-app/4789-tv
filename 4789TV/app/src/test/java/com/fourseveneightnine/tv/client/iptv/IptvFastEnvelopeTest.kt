package com.fourseveneightnine.tv.client.iptv

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import javax.crypto.KeyGenerator
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IptvFastEnvelopeTest {
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun largePayloadRoundTrips() {
        val plain = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        val wrapped = IptvFastEnvelope.seal(plain, keyForTest)
        assertTrue(IptvFastEnvelope.matches(wrapped))
        assertArrayEquals(plain, IptvFastEnvelope.open(wrapped, keyForTest))
    }

    private val keyForTest by lazy { key() }

    @Test fun compressedCatalogAndExistingFormatRemainReadableAndLeaveAtomicFileOpen() {
        var closed = false
        val output = object : ByteArrayOutputStream() {
            override fun close() { closed = true }
        }
        val plain = "{\"title\":\"A film · தமிழ் 🎬\"}".encodeToByteArray()
        IptvFastEnvelope.seal(output, keyForTest) { stream ->
            plain.forEach { stream.write(it.toInt()) }
        }
        assertFalse(closed)
        assertArrayEquals(plain, IptvFastEnvelope.openStream(output.toByteArray(), keyForTest).use { it.readBytes() })
        val legacy = IptvFastEnvelope.seal(plain, keyForTest)
        assertArrayEquals(plain, IptvFastEnvelope.openStream(legacy, keyForTest).use { it.readBytes() })
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test fun catalogLargerThanTheTestHeapIsWrittenInSmallChunks() {
        var written = 0L
        var plainBytes = 0L
        var largestWrite = 0
        val output = object : OutputStream() {
            override fun write(value: Int) { written++ }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                written += length
                largestWrite = maxOf(largestWrite, length)
            }
        }
        val movie = IptvVod("movie", "provider", "movie", "Film", "Movies",
            description = "x".repeat(2_048))
        val catalog = IptvCatalog(vod = List(65_536) { movie })
        IptvFastEnvelope.seal(output, keyForTest) { encrypted ->
            Json.encodeToStream(catalog, object : OutputStream() {
                override fun write(value: Int) { plainBytes++; encrypted.write(value) }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    plainBytes += length
                    encrypted.write(bytes, offset, length)
                }
            })
        }
        assertTrue(plainBytes > 128L * 1024 * 1024)
        assertTrue("Compressed catalog: $written", written < 1024 * 1024)
        assertTrue("Largest encrypted write: $largestWrite", largestWrite <= 16 * 1024 + 16)
    }

    @Test fun failedCatalogWriteReachesTheAtomicFileRollbackCaller() {
        val failure = IOException("storage write failed")
        val output = object : OutputStream() {
            override fun write(value: Int) { throw failure }
        }
        assertEquals(failure, assertThrows(IOException::class.java) {
            IptvFastEnvelope.seal(output, keyForTest) { it.write(byteArrayOf(1, 2, 3)) }
        })
        assertEquals(failure, assertThrows(IOException::class.java) {
            IptvFastEnvelope.seal(ByteArrayOutputStream(), keyForTest) { throw failure }
        })
    }

    @Test fun tamperingAndTruncationAreRejected() {
        val envelope = IptvFastEnvelope.seal("catalog".encodeToByteArray(), keyForTest)
        val changedContent = envelope.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val changedKey = envelope.clone().also { it[20] = (it[20].toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { IptvFastEnvelope.open(changedContent, keyForTest) }
        assertThrows(Exception::class.java) { IptvFastEnvelope.open(changedKey, keyForTest) }
        assertThrows(Exception::class.java) { IptvFastEnvelope.open(envelope.copyOf(12), keyForTest) }
        assertFalse(IptvFastEnvelope.matches(ByteArray(12)))
        val compressed = ByteArrayOutputStream().also { output ->
            IptvFastEnvelope.seal(output, keyForTest) { it.write("catalog".encodeToByteArray()) }
        }.toByteArray()
        val changed = compressed.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { IptvFastEnvelope.openStream(changed, keyForTest) }
        assertThrows(Exception::class.java) { IptvFastEnvelope.openStream(compressed.copyOf(compressed.size - 1), keyForTest) }
    }
}
