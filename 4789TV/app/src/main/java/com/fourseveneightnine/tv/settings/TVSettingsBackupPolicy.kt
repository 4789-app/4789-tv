package com.fourseveneightnine.tv.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

@Serializable
internal data class TVSettingsReceiptCategory(
    val name: String,
    val count: Int,
)

@Serializable
internal data class TVSettingsReceipt(
    val totalFields: Int,
    val categories: List<TVSettingsReceiptCategory>,
)

internal data class ValidatedTVSettings(
    val rawJson: String,
    val receipt: TVSettingsReceipt,
)

/** Validates the existing iOS `4789-settings` export without ever echoing secret values. */
internal object TVSettingsBackupPolicy {
    const val MAX_BYTES = 512 * 1024
    const val CURRENT_VERSION = 1

    private val json = Json { ignoreUnknownKeys = true }

    private val categoryFields = linkedMapOf(
        "Addons & Catalogs" to setOf(
            "primaryManifestURLText", "secondaryManifestURLText", "aioStreamsURLText",
            "aioSubtitlesURLText", "mediaFusionURLText", "mediaFusionEnabled", "watcherBaseURL",
            "letterboxdUsername", "letterboxdUsernames", "catalogOrder", "sources", "subtitleSources",
        ),
        "Debrid & API Keys" to setOf(
            "sharedSecret", "catalogServerToken", "catalogBackupKey", "torboxAPIKey",
            "realDebridAPIKey", "bitsearchAPIKey", "einthusanEmail", "einthusanPassword",
            "einthusanEnabled", "einthusanLanguage", "googleDriveClientID",
        ),
        "Metadata & AI" to setOf(
            "tmdbAPIKey", "mdbListAPIKey", "traktClientID", "traktClientSecret",
            "deepseekAPIKey", "openRouterAPIKey",
        ),
        "Playback" to setOf("uncachedDailyMax", "uncachedSlotOverride"),
    )

    fun validate(bytes: ByteArray): ValidatedTVSettings {
        require(bytes.isNotEmpty()) { "empty_settings" }
        require(bytes.size <= MAX_BYTES) { "settings_too_large" }
        val raw = bytes.decodeToString()
        val root = json.parseToJsonElement(raw) as? JsonObject ?: error("settings_not_object")
        require(root["format"]?.asString() == "4789-settings") { "wrong_settings_format" }
        require(root["version"]?.asInt() == CURRENT_VERSION) { "unsupported_settings_version" }

        val compatibleFields = categoryFields.values.flatten().toSet()
        val present = root.filter { (key, value) ->
            key in compatibleFields && value.isMeaningful()
        }.keys
        val sanitized = JsonObject(root.filter { (key, value) ->
            (key in compatibleFields || key in setOf("format", "version", "exportedAt")) && value.isMeaningful()
        })
        val categories = categoryFields.mapNotNull { (name, fields) ->
            val count = present.count(fields::contains)
            count.takeIf { it > 0 }?.let { TVSettingsReceiptCategory(name, it) }
        }
        return ValidatedTVSettings(
            rawJson = sanitized.toString(),
            receipt = TVSettingsReceipt(present.size, categories),
        )
    }

    private fun JsonElement.asString(): String? = (this as? JsonPrimitive)?.contentOrNull
    private fun JsonElement.asInt(): Int? = (this as? JsonPrimitive)?.intOrNull

    private fun JsonElement.isMeaningful(): Boolean = when (this) {
        JsonNull -> false
        is JsonPrimitive -> !isString || content.isNotBlank()
        is JsonArray -> isNotEmpty()
        is JsonObject -> isNotEmpty()
    }
}
