package com.fourseveneightnine.tv.client.iptv

import android.content.Context
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-bound encrypted accounts and catalog cache. Stream URLs can contain provider tokens. */
internal class IptvVault(context: Context) {
    private val accountsFile = AtomicFile(File(context.filesDir, "iptv-accounts-v1.enc"))
    private val catalogFile = AtomicFile(File(context.filesDir, "iptv-catalog-v1.enc"))
    private val previewFile = AtomicFile(File(context.filesDir, "iptv-live-preview-v1.enc"))
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val alias = "com.fourseveneightnine.tv.iptv.v1"

    fun loadAccounts(): Result<IptvAccounts> = if (!accountsFile.baseFile.exists()) {
        Result.success(IptvAccounts())
    } else runCatching {
        json.decodeFromString<IptvAccounts>(decrypt(accountsFile.readFully()).decodeToString())
    }

    fun loadCatalog(): IptvCatalog = loadCache(catalogFile, "catalog")

    fun loadPreview(): IptvCatalog = loadCache(previewFile, "preview")

    // A first-read migration must finish before a concurrent provider refresh writes this file.
    @Synchronized
    private fun loadCache(file: AtomicFile, label: String): IptvCatalog {
        if (!file.baseFile.exists()) return IptvCatalog()
        return runCatching {
            val startedAt = SystemClock.elapsedRealtime()
            val encrypted = file.readFully()
            val readMs = SystemClock.elapsedRealtime() - startedAt
            val fast = IptvFastEnvelope.matches(encrypted)
            val plain = if (fast) IptvFastEnvelope.open(encrypted, key()) else decrypt(encrypted)
            val decryptMs = SystemClock.elapsedRealtime() - startedAt - readMs
            val catalog = json.decodeFromString<IptvCatalog>(plain.decodeToString())
            val parseMs = SystemClock.elapsedRealtime() - startedAt - readMs - decryptMs
            if (!fast) {
                Log.i("4789IptvPerf", "$label migrated=${writeFast(file, plain)}")
            }
            Log.i("4789IptvPerf", "$label format=${if (fast) 2 else 1} bytes=${encrypted.size} readMs=$readMs " +
                "decryptMs=$decryptMs parseMs=$parseMs channels=${catalog.channels.size} vod=${catalog.vod.size}")
            catalog
        }.onFailure { error ->
            Log.e("4789IptvPerf", "$label load failed: ${error.javaClass.simpleName}")
        }.getOrDefault(IptvCatalog())
    }

    fun saveAccounts(value: IptvAccounts): Boolean = write(accountsFile, json.encodeToString(value).encodeToByteArray())
    @Synchronized
    fun saveCatalog(value: IptvCatalog): Boolean {
        val saved = writeFast(catalogFile, json.encodeToString(value).encodeToByteArray())
        if (saved) savePreview(value)
        return saved
    }

    @Synchronized
    fun savePreview(value: IptvCatalog): Boolean {
        val now = System.currentTimeMillis()
        val categories = value.vod.groupingBy { Triple(it.sourceId, it.kind, it.category) }
            .eachCount().map { (key, count) ->
                IptvVodCategory(key.first, key.second, key.third, count)
            }
        val preview = IptvCatalog(channels = value.channels,
            programs = value.programs.filter { it.endMillis > now - 3_600_000L &&
                it.startMillis < now + 12L * 3_600_000L },
            refreshedAtMillis = value.refreshedAtMillis, vodCategories = categories)
        return writeFast(previewFile, json.encodeToString(preview).encodeToByteArray())
    }

    private fun write(file: AtomicFile, plain: ByteArray): Boolean = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        writePayload(file, cipher.iv + cipher.doFinal(plain))
    }.getOrDefault(false)

    private fun writeFast(file: AtomicFile, plain: ByteArray): Boolean = runCatching {
        writePayload(file, IptvFastEnvelope.seal(plain, key()))
    }.getOrDefault(false)

    private fun writePayload(file: AtomicFile, payload: ByteArray): Boolean {
        val stream = file.startWrite()
        try {
            stream.write(payload)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
        return true
    }

    private fun decrypt(envelope: ByteArray): ByteArray {
        require(envelope.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, envelope.copyOfRange(0, 12)))
        return cipher.doFinal(envelope.copyOfRange(12, envelope.size))
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
