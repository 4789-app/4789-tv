package com.fourseveneightnine.tv.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
internal data class StoredTVSettings(
    val rawJson: String,
    val revision: Long,
    val savedAtMillis: Long,
    val syncKeyBase64: String? = null,
)

internal sealed interface StoredTVSettingsState {
    data object Missing : StoredTVSettingsState
    data class Available(val document: StoredTVSettings) : StoredTVSettingsState
    data class Unavailable(val reason: String) : StoredTVSettingsState
}

internal interface TVSettingsPersistence {
    fun load(): StoredTVSettingsState
    fun save(settings: ValidatedTVSettings, revision: Long, syncKey: ByteArray?): Boolean
    fun clearSync(): Boolean
    fun clearAll(): Boolean
}

/** Encrypts the complete settings export and optional sync credential with Android Keystore. */
internal class EncryptedTVSettingsStore(
    context: Context,
    preferenceName: String = "receiver-settings-v1",
    private val keyAlias: String = "com.fourseveneightnine.tv.settings.v1",
) : TVSettingsPersistence {
    private val preferences = context.applicationContext.getSharedPreferences(
        preferenceName,
        Context.MODE_PRIVATE,
    )
    private val json = Json { ignoreUnknownKeys = false }

    override fun load(): StoredTVSettingsState {
        val encoded = preferences.getString(CIPHERTEXT_KEY, null) ?: return StoredTVSettingsState.Missing
        return runCatching {
            val envelope = Base64.decode(encoded, Base64.NO_WRAP)
            require(envelope.size > IV_BYTES)
            val iv = envelope.copyOfRange(0, IV_BYTES)
            val ciphertext = envelope.copyOfRange(IV_BYTES, envelope.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, existingKey(), GCMParameterSpec(TAG_BITS, iv))
            json.decodeFromString(StoredTVSettings.serializer(), cryptInChunks(cipher, ciphertext).decodeToString())
        }.fold(
            onSuccess = StoredTVSettingsState::Available,
            onFailure = { StoredTVSettingsState.Unavailable(it.javaClass.simpleName) },
        )
    }

    override fun save(settings: ValidatedTVSettings, revision: Long, syncKey: ByteArray?): Boolean {
        val document = StoredTVSettings(
            rawJson = settings.rawJson,
            revision = revision,
            savedAtMillis = System.currentTimeMillis(),
            syncKeyBase64 = syncKey?.let(SettingsPairingCrypto::encodeBase64URL),
        )
        return saveDocument(document)
    }

    override fun clearSync(): Boolean {
        val available = load() as? StoredTVSettingsState.Available ?: return true
        return saveDocument(available.document.copy(syncKeyBase64 = null))
    }

    override fun clearAll(): Boolean {
        val removed = preferences.edit().remove(CIPHERTEXT_KEY).commit()
        return removed && !preferences.contains(CIPHERTEXT_KEY)
    }

    private fun saveDocument(document: StoredTVSettings): Boolean {
        val prior = preferences.getString(CIPHERTEXT_KEY, null)
        val encoded = runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, existingOrNewKey())
            val ciphertext = cryptInChunks(cipher, json.encodeToString(document).encodeToByteArray())
            Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
        }.onFailure {
            ReceiverDiagnostics.record("settings.store.save.failure", it.javaClass.simpleName)
        }.getOrNull() ?: return false

        if (!preferences.edit().putString(CIPHERTEXT_KEY, encoded).commit()) {
            ReceiverDiagnostics.record("settings.store.save.failure", "commit")
            return false
        }
        val verified = (load() as? StoredTVSettingsState.Available)?.document == document
        if (!verified) {
            ReceiverDiagnostics.record("settings.store.save.failure", "verify")
            val editor = preferences.edit()
            if (prior == null) editor.remove(CIPHERTEXT_KEY) else editor.putString(CIPHERTEXT_KEY, prior)
            editor.commit()
        }
        return verified
    }

    /** Android Keystore AES-GCM must not receive a document-sized Binder operation on Fire OS. */
    private fun cryptInChunks(cipher: Cipher, input: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(input.size + TAG_BITS / Byte.SIZE_BITS)
        var offset = 0
        while (offset < input.size) {
            val count = minOf(CIPHER_CHUNK_BYTES, input.size - offset)
            cipher.update(input, offset, count)?.let(output::write)
            offset += count
        }
        cipher.doFinal()?.let(output::write)
        return output.toByteArray()
    }

    private fun existingKey(): SecretKey = keyStore().getKey(keyAlias, null) as? SecretKey
        ?: error("missing_keystore_key")

    private fun existingOrNewKey(): SecretKey = runCatching { existingKey() }.getOrElse {
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val CIPHERTEXT_KEY = "settings-ciphertext"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val CIPHER_CHUNK_BYTES = 16 * 1024
    }
}
