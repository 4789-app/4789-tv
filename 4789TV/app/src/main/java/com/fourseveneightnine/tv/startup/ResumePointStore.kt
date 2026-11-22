package com.fourseveneightnine.tv.startup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.fourseveneightnine.tv.player.ResumePoint
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Interrupted sessions and bounded per-title checkpoints. Links and headers stay encrypted. */
internal class ResumePointStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("receiver-resume", Context.MODE_PRIVATE)

    fun load(): ResumePoint? {
        // The old point was written for a different, manual offer and may belong to a finished title.
        if (prefs.contains("point")) prefs.edit().remove("point").apply()
        return decrypt(prefs.getString(KEY, null))?.let(ResumePoint::decode)
    }

    @Synchronized
    fun loadTitle(profileId: String?, type: String, id: String, season: Int?, episode: Int?): ResumePoint? =
        titles().firstOrNull { it.profileId == profileId && it.catalogType == type && it.catalogId == id &&
            it.season == season && it.episode == episode }

    /** Only a checkpoint from verified playback enters this history; failed opens cannot replace it. */
    @Synchronized
    fun saveTitle(point: ResumePoint): Boolean {
        if (point.origin != "local" || point.isLive || point.catalogId == null || point.catalogType == null) return true
        val stored = ResumePoint.decode(point.encode()) ?: return false
        val retained = listOf(stored) + titles().filterNot { it.profileId == point.profileId &&
            it.catalogType == point.catalogType && it.catalogId == point.catalogId &&
            it.season == point.season && it.episode == point.episode }
        return encrypt(Json.encodeToString(retained.take(MAX_TITLES)))?.let {
            prefs.edit().putString(TITLES_KEY, it).commit()
        } ?: false
    }

    private fun titles(): List<ResumePoint> = decrypt(prefs.getString(TITLES_KEY, null))?.let {
        runCatching { Json.decodeFromString<List<ResumePoint>>(it) }.getOrNull()
    }.orEmpty()

    private fun decrypt(encoded: String?): String? = encoded?.let {
        runCatching {
            val envelope = Base64.decode(encoded, Base64.NO_WRAP)
            require(envelope.size > IV_BYTES)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(create = false),
                GCMParameterSpec(128, envelope.copyOfRange(0, IV_BYTES)))
            cipher.doFinal(envelope, IV_BYTES, envelope.size - IV_BYTES).decodeToString()
        }.getOrNull()
    }

    private fun encrypt(value: String): String? = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        val envelope = cipher.iv + cipher.doFinal(value.encodeToByteArray())
        Base64.encodeToString(envelope, Base64.NO_WRAP)
    }.getOrNull()

    fun save(point: ResumePoint): Boolean = encrypt(point.encode())?.let {
        prefs.edit().remove("point").putString(KEY, it).commit()
    } ?: false

    fun clear() { prefs.edit().remove("point").remove(KEY).commit() }

    fun clearTitles() { prefs.edit().remove(TITLES_KEY).commit() }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(create)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }

    private companion object {
        const val KEY = "encrypted-session-v1"
        const val TITLES_KEY = "encrypted-titles-v1"
        const val MAX_TITLES = 100
        const val ALIAS = "com.fourseveneightnine.tv.resume.v1"
        const val IV_BYTES = 12
    }
}
