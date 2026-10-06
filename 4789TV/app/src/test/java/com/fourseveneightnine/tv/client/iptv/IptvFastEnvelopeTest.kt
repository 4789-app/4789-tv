package com.fourseveneightnine.tv.client.iptv

import javax.crypto.KeyGenerator
import org.junit.Assert.assertArrayEquals
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

    @Test fun tamperingAndTruncationAreRejected() {
        val envelope = IptvFastEnvelope.seal("catalog".encodeToByteArray(), keyForTest)
        val changedContent = envelope.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val changedKey = envelope.clone().also { it[20] = (it[20].toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { IptvFastEnvelope.open(changedContent, keyForTest) }
        assertThrows(Exception::class.java) { IptvFastEnvelope.open(changedKey, keyForTest) }
        assertThrows(Exception::class.java) { IptvFastEnvelope.open(envelope.copyOf(12), keyForTest) }
        assertFalse(IptvFastEnvelope.matches(ByteArray(12)))
    }
}
