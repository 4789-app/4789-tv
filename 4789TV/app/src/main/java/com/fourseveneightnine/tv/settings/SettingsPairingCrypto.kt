package com.fourseveneightnine.tv.settings

import com.fourseveneightnine.tv.catalog.TVTamilMVCatalogImportPolicy
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Serializable
internal data class SettingsCipherEnvelope(
    val version: Int = 1,
    val nonce: String,
    val ciphertext: String,
    val revision: Long = 0L,
)

/** Cross-platform AES-256-GCM/HKDF wire used only by first-party settings routes. */
internal object SettingsPairingCrypto {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private const val ENVELOPE_METADATA_BYTES = 256
    private const val NONCE_BYTES = 12
    private const val KEY_BYTES = 32
    private const val HMAC = "HmacSHA256"
    private const val PAIR_INFO = "4789-settings-pair-v1"
    private const val CATALOG_PAIR_INFO = "4789-catalog-pair-v1"
    private const val SYNC_INFO = "4789-settings-sync-v1"
    private val random = SecureRandom()
    private val json = Json { ignoreUnknownKeys = false }

    // The 512 KiB policy applies to plaintext. AES-GCM adds a 16-byte tag and Base64URL expands
    // ciphertext by 4/3; the fixed JSON/nonce/revision allowance keeps the wire bounded too.
    const val MAX_ENVELOPE_BYTES =
        ((TVSettingsBackupPolicy.MAX_BYTES + TAG_BITS / 8 + 2) / 3) * 4 + ENVELOPE_METADATA_BYTES
    const val MAX_CATALOG_ENVELOPE_BYTES =
        ((TVTamilMVCatalogImportPolicy.MAX_PLAINTEXT_BYTES + TAG_BITS / 8 + 2) / 3) * 4 + ENVELOPE_METADATA_BYTES

    fun randomBytes(count: Int): ByteArray = ByteArray(count).also(random::nextBytes)

    fun randomID(): String = encodeBase64URL(randomBytes(16))

    fun derivePairKey(invitationSecret: ByteArray, pairingID: String): ByteArray = hkdf(
        inputKeyMaterial = invitationSecret,
        salt = pairingID.encodeToByteArray(),
        info = PAIR_INFO.encodeToByteArray(),
    )

    fun deriveCatalogPairKey(invitationSecret: ByteArray, pairingID: String): ByteArray = hkdf(
        inputKeyMaterial = invitationSecret,
        salt = pairingID.encodeToByteArray(),
        info = CATALOG_PAIR_INFO.encodeToByteArray(),
    )

    fun deriveSyncKey(invitationSecret: ByteArray, receiverID: String): ByteArray = hkdf(
        inputKeyMaterial = invitationSecret,
        salt = receiverID.encodeToByteArray(),
        info = SYNC_INFO.encodeToByteArray(),
    )

    fun decryptPair(body: String, invitationSecret: ByteArray, pairingID: String): ByteArray =
        decrypt(body, derivePairKey(invitationSecret, pairingID), "$PAIR_INFO|$pairingID", MAX_ENVELOPE_BYTES)

    fun decryptCatalogPair(body: String, invitationSecret: ByteArray, pairingID: String): ByteArray =
        decrypt(
            body,
            deriveCatalogPairKey(invitationSecret, pairingID),
            "$CATALOG_PAIR_INFO|$pairingID",
            MAX_CATALOG_ENVELOPE_BYTES,
        )

    fun decryptSync(body: String, syncKey: ByteArray, receiverID: String): Pair<ByteArray, Long> {
        val envelope = decodeEnvelope(body, MAX_ENVELOPE_BYTES)
        return decrypt(envelope, syncKey, "$SYNC_INFO|$receiverID") to envelope.revision
    }

    internal fun encryptForTest(
        plaintext: ByteArray,
        key: ByteArray,
        associatedData: String,
        revision: Long = 0L,
    ): String {
        val nonce = randomBytes(NONCE_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, nonce),
        )
        cipher.updateAAD(associatedData.encodeToByteArray())
        val ciphertext = cipher.doFinal(plaintext)
        return json.encodeToString(
            SettingsCipherEnvelope(
                nonce = encodeBase64URL(nonce),
                ciphertext = encodeBase64URL(ciphertext),
                revision = revision,
            ),
        )
    }

    private fun decrypt(
        body: String,
        key: ByteArray,
        associatedData: String,
        maximumEnvelopeBytes: Int,
    ): ByteArray = decrypt(decodeEnvelope(body, maximumEnvelopeBytes), key, associatedData)

    private fun decrypt(
        envelope: SettingsCipherEnvelope,
        key: ByteArray,
        associatedData: String,
    ): ByteArray {
        require(envelope.version == 1) { "unsupported_cipher_version" }
        require(key.size == KEY_BYTES) { "invalid_key_length" }
        val nonce = decodeBase64URL(envelope.nonce)
        val ciphertext = decodeBase64URL(envelope.ciphertext)
        require(nonce.size == NONCE_BYTES) { "invalid_nonce" }
        require(ciphertext.size >= 16) { "invalid_ciphertext" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, nonce),
        )
        cipher.updateAAD(associatedData.encodeToByteArray())
        return cipher.doFinal(ciphertext)
    }

    private fun decodeEnvelope(body: String, maximumEnvelopeBytes: Int): SettingsCipherEnvelope {
        require(body.encodeToByteArray().size <= maximumEnvelopeBytes) {
            "ciphertext_too_large"
        }
        return json.decodeFromString(SettingsCipherEnvelope.serializer(), body)
    }

    private fun hkdf(inputKeyMaterial: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val extract = Mac.getInstance(HMAC)
        extract.init(SecretKeySpec(salt, HMAC))
        val pseudoRandomKey = extract.doFinal(inputKeyMaterial)
        val expand = Mac.getInstance(HMAC)
        expand.init(SecretKeySpec(pseudoRandomKey, HMAC))
        val output = expand.doFinal(info + byteArrayOf(1))
        return output.copyOf(KEY_BYTES)
    }

    fun encodeBase64URL(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun decodeBase64URL(value: String): ByteArray =
        Base64.getUrlDecoder().decode(value)
}
