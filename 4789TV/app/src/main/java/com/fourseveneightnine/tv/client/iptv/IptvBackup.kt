package com.fourseveneightnine.tv.client.iptv

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Portable, passphrase-encrypted TV IPTV settings backup. Video files are not included. */
internal object IptvBackup {
    private val magic = "4789IPTV1".encodeToByteArray()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun export(accounts: IptvAccounts, passphrase: CharArray): ByteArray {
        require(passphrase.size >= 8)
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(passphrase, salt), GCMParameterSpec(128, iv))
        // A recording's file remains on the original TV; copying its metadata would create a broken row.
        val portable = accounts.copy(recordings = emptyList())
        return magic + salt + iv + cipher.doFinal(json.encodeToString(portable).encodeToByteArray())
    }

    fun import(bytes: ByteArray, passphrase: CharArray): IptvAccounts {
        require(bytes.size in (magic.size + 16 + 12 + 17)..4_000_000)
        require(bytes.copyOfRange(0, magic.size).contentEquals(magic))
        val saltStart = magic.size
        val ivStart = saltStart + 16
        val payloadStart = ivStart + 12
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(passphrase, bytes.copyOfRange(saltStart, ivStart)),
            GCMParameterSpec(128, bytes.copyOfRange(ivStart, payloadStart)))
        val plain = cipher.doFinal(bytes.copyOfRange(payloadStart, bytes.size))
        return json.decodeFromString<IptvAccounts>(plain.decodeToString()).copy(recordings = emptyList())
    }

    private fun key(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, 200_000, 256)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
