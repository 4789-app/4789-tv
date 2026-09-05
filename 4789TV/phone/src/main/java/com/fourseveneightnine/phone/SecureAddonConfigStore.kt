package com.fourseveneightnine.phone

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal sealed interface SecureAddonConfigState {
    data object Missing : SecureAddonConfigState
    data class Available(val manifestURL: String) : SecureAddonConfigState
    data class Unavailable(val reason: String) : SecureAddonConfigState
}

internal class SecureAddonConfigStore(
    context: Context,
    preferenceName: String = "secure-addon-config-v1",
    private val keyAlias: String = "com.fourseveneightnine.phone.addon-config.v1",
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        preferenceName,
        Context.MODE_PRIVATE,
    )

    fun load(): SecureAddonConfigState {
        val encoded = preferences.getString(CIPHERTEXT_KEY, null) ?: return SecureAddonConfigState.Missing
        return runCatching {
            val envelope = Base64.decode(encoded, Base64.NO_WRAP)
            require(envelope.size > IV_LENGTH_BYTES)
            val iv = envelope.copyOfRange(0, IV_LENGTH_BYTES)
            val ciphertext = envelope.copyOfRange(IV_LENGTH_BYTES, envelope.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, existingKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            val manifest = cipher.doFinal(ciphertext).decodeToString()
            AddonConfigurationPolicy.normalizedManifestURL(manifest)
                ?: error("invalid_decrypted_manifest")
        }.fold(
            onSuccess = SecureAddonConfigState::Available,
            onFailure = { SecureAddonConfigState.Unavailable(it.javaClass.simpleName) },
        )
    }

    fun save(manifestURL: String): Boolean {
        val normalized = AddonConfigurationPolicy.normalizedManifestURL(manifestURL) ?: return false
        val prior = preferences.getString(CIPHERTEXT_KEY, null)
        val encoded = runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, existingOrNewKey())
            val ciphertext = cipher.doFinal(normalized.encodeToByteArray())
            Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
        }.getOrNull() ?: return false

        preferences.edit(commit = true) { putString(CIPHERTEXT_KEY, encoded) }
        val verified = load() == SecureAddonConfigState.Available(normalized)
        if (!verified) {
            preferences.edit(commit = true) {
                if (prior == null) remove(CIPHERTEXT_KEY) else putString(CIPHERTEXT_KEY, prior)
            }
        }
        return verified
    }

    fun clear(): Boolean {
        preferences.edit(commit = true) { remove(CIPHERTEXT_KEY) }
        return !preferences.contains(CIPHERTEXT_KEY)
    }

    internal fun destroyForTesting() {
        clear()
        runCatching {
            keyStore().apply { deleteEntry(keyAlias) }
        }
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
        const val CIPHERTEXT_KEY = "manifest-ciphertext"
        const val IV_LENGTH_BYTES = 12
        const val TAG_LENGTH_BITS = 128
    }
}
