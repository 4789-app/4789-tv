package com.fourseveneightnine.tv.catalog

import com.fourseveneightnine.tv.client.data.settings.SettingsDocument
import com.fourseveneightnine.tv.client.data.streams.StreamRow
import java.net.URI

internal enum class EinthusanLanguage(val iso: String, val label: String) {
    Telugu("te", "Telugu"), Tamil("ta", "Tamil"), Hindi("hi", "Hindi"),
    Malayalam("ml", "Malayalam"), Kannada("kn", "Kannada");

    val key: String get() = label.lowercase()

    companion object {
        fun from(value: String?): EinthusanLanguage? =
            entries.firstOrNull { it.iso.equals(value, true) || it.key.equals(value, true) }
    }
}

internal class EinthusanAccount(
    val email: String,
    val password: String,
    val enabled: Boolean,
    val language: EinthusanLanguage,
) {
    val hasCredentials: Boolean get() = email.isNotBlank() && password.isNotBlank()
    val configured: Boolean get() = enabled && hasCredentials

    companion object {
        fun from(document: SettingsDocument?): EinthusanAccount = EinthusanAccount(
            email = document?.einthusanEmail.orEmpty(),
            password = document?.einthusanPassword.orEmpty(),
            enabled = document?.einthusanEnabled != false,
            language = EinthusanLanguage.from(document?.einthusanLanguage) ?: EinthusanLanguage.Telugu,
        )
    }
}

internal data class EinthusanSource(
    val id: String,
    val language: EinthusanLanguage,
    val subtitles: Boolean,
    val ultraHd: Boolean,
) {
    val reference: String get() = "einthusan://${language.key}/$id?cc=${if (subtitles) 1 else 0}&uhd=${if (ultraHd) 1 else 0}"

    fun row(title: String): StreamRow = StreamRow(
        id = reference, addonName = "Einthusan", title = title, releaseName = title,
        url = reference, quality = "1080p", audioLanguages = listOf(language.label), identityVerified = true,
    )

    companion object {
        fun parse(reference: String?): EinthusanSource? {
            if (reference?.startsWith("einthusan://") != true) return null
            val uri = runCatching { URI(reference) }.getOrNull() ?: return null
            val language = EinthusanLanguage.from(uri.host) ?: return null
            val id = uri.path?.removePrefix("/")?.takeIf { it.matches(Regex("[A-Za-z0-9]+")) } ?: return null
            val flags = uri.rawQuery.orEmpty().split('&')
            return EinthusanSource(id, language, "cc=1" in flags, "uhd=1" in flags)
        }
    }
}

internal class EinthusanFailure(message: String) : Exception(message)
