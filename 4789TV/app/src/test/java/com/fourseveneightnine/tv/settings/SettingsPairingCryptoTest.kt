package com.fourseveneightnine.tv.settings

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPairingCryptoTest {
    @Test
    fun hkdfMatchesPhoneKnownVectors() {
        val pair = SettingsPairingCrypto.derivePairKey(ByteArray(32) { it.toByte() }, "pairing-id")
        val catalog = SettingsPairingCrypto.deriveCatalogPairKey(ByteArray(32) { it.toByte() }, "pairing-id")
        val sync = SettingsPairingCrypto.deriveSyncKey(ByteArray(32) { (31 - it).toByte() }, "receiver-id")
        val encoder = Base64.getUrlEncoder().withoutPadding()

        assertEquals("_K_MzakSBmvk3u4eN1m3y3J0OdpMfJn4KlCylFljRPY", encoder.encodeToString(pair))
        assertEquals("Bf04PE-mRxD9JrfIOErtA7AdUBjWULAS0jlyDaOZrYY", encoder.encodeToString(catalog))
        assertEquals("-HOtqjkTR9yY7eMx6fxtAHFCBu5qwOlEGU5FYRAQPBE", encoder.encodeToString(sync))
    }

    @Test
    fun pairEnvelopeRoundTripsOnlyForItsInvitation() {
        val secret = ByteArray(32) { it.toByte() }
        val pairingID = "pairing-id"
        val plaintext = "{\"format\":\"4789-settings\",\"version\":1}".encodeToByteArray()
        val key = SettingsPairingCrypto.derivePairKey(secret, pairingID)
        val body = SettingsPairingCrypto.encryptForTest(
            plaintext,
            key,
            "4789-settings-pair-v1|$pairingID",
        )

        assertArrayEquals(plaintext, SettingsPairingCrypto.decryptPair(body, secret, pairingID))
        assertThrows(Exception::class.java) {
            SettingsPairingCrypto.decryptPair(body, secret, "different-invitation")
        }
    }

    @Test
    fun syncEnvelopeAuthenticatesReceiverAndRevision() {
        val receiverID = "receiver-id"
        val key = SettingsPairingCrypto.deriveSyncKey(ByteArray(32) { (31 - it).toByte() }, receiverID)
        val plaintext = "{\"action\":\"disconnect\"}".encodeToByteArray()
        val body = SettingsPairingCrypto.encryptForTest(
            plaintext,
            key,
            "4789-settings-sync-v1|$receiverID",
            revision = 42,
        )

        val (decoded, revision) = SettingsPairingCrypto.decryptSync(body, key, receiverID)
        assertArrayEquals(plaintext, decoded)
        assertEquals(42L, revision)
        assertThrows(Exception::class.java) {
            SettingsPairingCrypto.decryptSync(body, key, "other-receiver")
        }
    }

    @Test
    fun maximumPlaintextFitsBoundedWireEnvelopeAndOversizeEnvelopeFails() {
        val secret = ByteArray(32) { it.toByte() }
        val pairingID = "maximum-pairing"
        val plaintext = ByteArray(TVSettingsBackupPolicy.MAX_BYTES) { (it % 251).toByte() }
        val body = SettingsPairingCrypto.encryptForTest(
            plaintext,
            SettingsPairingCrypto.derivePairKey(secret, pairingID),
            "4789-settings-pair-v1|$pairingID",
        )

        assertTrue(body.encodeToByteArray().size <= SettingsPairingCrypto.MAX_ENVELOPE_BYTES)
        assertArrayEquals(plaintext, SettingsPairingCrypto.decryptPair(body, secret, pairingID))

        val excess = SettingsPairingCrypto.MAX_ENVELOPE_BYTES - body.encodeToByteArray().size + 1
        assertThrows(Exception::class.java) {
            SettingsPairingCrypto.decryptPair(body + " ".repeat(excess), secret, pairingID)
        }
    }
}
