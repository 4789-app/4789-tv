package com.fourseveneightnine.tv.client.iptv

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Large-cache envelope: Keystore protects a per-file key; JCE handles the bulk AES-GCM work. */
internal object IptvFastEnvelope {
    private val magic = byteArrayOf(0x34, 0x37, 0x38, 0x39, 0x02) // 4789, format 2
    private const val IV_BYTES = 12
    private const val KEY_BYTES = 32
    private const val TAG_BYTES = 16
    private const val WRAPPED_KEY_BYTES = KEY_BYTES + TAG_BYTES
    private const val HEADER_BYTES = 5 + IV_BYTES + WRAPPED_KEY_BYTES + IV_BYTES

    fun matches(bytes: ByteArray): Boolean = bytes.size >= magic.size &&
        magic.indices.all { bytes[it] == magic[it] }

    fun seal(plain: ByteArray, wrappingKey: SecretKey): ByteArray {
        val dataKey = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)
        try {
            val wrap = Cipher.getInstance("AES/GCM/NoPadding")
            wrap.init(Cipher.ENCRYPT_MODE, wrappingKey)
            val wrapped = wrap.doFinal(dataKey)
            require(wrap.iv.size == IV_BYTES && wrapped.size == WRAPPED_KEY_BYTES)
            val content = Cipher.getInstance("AES/GCM/NoPadding")
            content.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dataKey, "AES"))
            require(content.iv.size == IV_BYTES)
            val encrypted = content.doFinal(plain)
            return ByteArray(HEADER_BYTES + encrypted.size).also { result ->
                var offset = 0
                for (part in arrayOf(magic, wrap.iv, wrapped, content.iv, encrypted)) {
                    part.copyInto(result, offset)
                    offset += part.size
                }
            }
        } finally {
            dataKey.fill(0)
        }
    }

    fun open(envelope: ByteArray, wrappingKey: SecretKey): ByteArray {
        require(matches(envelope) && envelope.size >= HEADER_BYTES + TAG_BYTES)
        val wrappedAt = magic.size + IV_BYTES
        val dataIvAt = wrappedAt + WRAPPED_KEY_BYTES
        val wrap = Cipher.getInstance("AES/GCM/NoPadding")
        wrap.init(Cipher.DECRYPT_MODE, wrappingKey,
            GCMParameterSpec(128, envelope.copyOfRange(magic.size, wrappedAt)))
        val dataKey = wrap.doFinal(envelope, wrappedAt, WRAPPED_KEY_BYTES)
        try {
            require(dataKey.size == KEY_BYTES)
            val content = Cipher.getInstance("AES/GCM/NoPadding")
            content.init(Cipher.DECRYPT_MODE, SecretKeySpec(dataKey, "AES"),
                GCMParameterSpec(128, envelope.copyOfRange(dataIvAt, HEADER_BYTES)))
            return content.doFinal(envelope, HEADER_BYTES, envelope.size - HEADER_BYTES)
        } finally {
            dataKey.fill(0)
        }
    }
}
