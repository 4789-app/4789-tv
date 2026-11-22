package com.fourseveneightnine.tv.client.iptv

import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Large-cache envelope: Keystore protects a per-file key; JCE handles the bulk AES-GCM work. */
internal object IptvFastEnvelope {
    private val magic = byteArrayOf(0x34, 0x37, 0x38, 0x39, 0x02) // 4789, format 2
    private val compressedMagic = magic.clone().also { it[4] = 3 }
    private const val IV_BYTES = 12
    private const val KEY_BYTES = 32
    private const val TAG_BYTES = 16
    private const val WRAPPED_KEY_BYTES = KEY_BYTES + TAG_BYTES
    private const val HEADER_BYTES = 5 + IV_BYTES + WRAPPED_KEY_BYTES + IV_BYTES

    fun matches(bytes: ByteArray): Boolean = bytes.size >= magic.size &&
        (magic.indices.all { bytes[it] == magic[it] } ||
            compressedMagic.indices.all { bytes[it] == compressedMagic[it] })

    fun seal(plain: ByteArray, wrappingKey: SecretKey): ByteArray {
        val output = ByteArrayOutputStream(HEADER_BYTES + plain.size + TAG_BYTES)
        seal(output, wrappingKey, compressed = false) { it.write(plain) }
        return output.toByteArray()
    }

    /** Format 3 compresses JSON before Android's AES-GCM provider buffers it. */
    fun seal(output: OutputStream, wrappingKey: SecretKey, writePlain: (OutputStream) -> Unit) =
        seal(output, wrappingKey, compressed = true, writePlain)

    private fun seal(output: OutputStream, wrappingKey: SecretKey, compressed: Boolean,
                     writePlain: (OutputStream) -> Unit) {
        val dataKey = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)
        try {
            val wrap = Cipher.getInstance("AES/GCM/NoPadding")
            wrap.init(Cipher.ENCRYPT_MODE, wrappingKey)
            val wrapped = wrap.doFinal(dataKey)
            require(wrap.iv.size == IV_BYTES && wrapped.size == WRAPPED_KEY_BYTES)
            val content = Cipher.getInstance("AES/GCM/NoPadding")
            content.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dataKey, "AES"))
            require(content.iv.size == IV_BYTES)
            for (part in arrayOf(if (compressed) compressedMagic else magic, wrap.iv, wrapped, content.iv)) {
                output.write(part)
            }
            // Finalize explicitly: the AtomicFile caller must retain its open stream for fsync,
            // and encryption/serialization failures must reach its rollback path.
            val encrypted = object : OutputStream() {
                override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    if (offset < 0 || length < 0 || offset > bytes.size - length) {
                        throw IndexOutOfBoundsException()
                    }
                    var next = offset
                    val end = offset + length
                    while (next < end) {
                        val count = minOf(16 * 1024, end - next)
                        content.update(bytes, next, count)?.let(output::write)
                        next += count
                    }
                }
            }
            if (compressed) GZIPOutputStream(encrypted).use(writePlain) else writePlain(encrypted)
            output.write(content.doFinal())
        } finally {
            dataKey.fill(0)
        }
    }

    /** Authenticate first, then decompress lazily so a catalog-sized JSON string is never needed. */
    fun openStream(envelope: ByteArray, wrappingKey: SecretKey): InputStream {
        val plain = ByteArrayInputStream(open(envelope, wrappingKey))
        return if (envelope[4] == 3.toByte()) GZIPInputStream(plain) else plain
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
