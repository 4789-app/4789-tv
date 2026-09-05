package com.fourseveneightnine.tv.settings

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class TVSettingsBackupPolicyTest {
    @Test
    fun receiptGroupsConfiguredFieldsWithoutReturningValues() {
        val raw = """
            {
              "format": "4789-settings",
              "version": 1,
              "tmdbAPIKey": "tmdb-live-secret",
              "torboxAPIKey": "torbox-live-secret",
              "sources": [{"name":"Private addon","url":"https://secret.example/manifest.json"}],
              "uncachedDailyMax": 3
            }
        """.trimIndent()

        val validated = TVSettingsBackupPolicy.validate(raw.encodeToByteArray())

        assertEquals(4, validated.receipt.totalFields)
        assertEquals(
            listOf("Addons & Catalogs", "Debrid & API Keys", "Metadata & AI", "Playback"),
            validated.receipt.categories.map { it.name },
        )
        val receiptJson = Json.encodeToString(validated.receipt)
        assertFalse(receiptJson.contains("tmdb-live-secret"))
        assertFalse(receiptJson.contains("torbox-live-secret"))
        assertFalse(receiptJson.contains("secret.example"))
    }

    @Test
    fun rejectsWrongFormatVersionAndOversizeDocuments() {
        assertThrows(IllegalArgumentException::class.java) {
            TVSettingsBackupPolicy.validate("{\"format\":\"other\",\"version\":1}".encodeToByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            TVSettingsBackupPolicy.validate("{\"format\":\"4789-settings\",\"version\":2}".encodeToByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            TVSettingsBackupPolicy.validate(ByteArray(TVSettingsBackupPolicy.MAX_BYTES + 1))
        }
    }

    @Test
    fun phoneOnlyAndUnknownFieldsAreNotRetainedByTheReceiver() {
        val raw = """
            {
              "format": "4789-settings",
              "version": 1,
              "tmdbAPIKey": "tv-compatible",
              "phoneAppearance": "private-phone-choice",
              "notificationsEnabled": true,
              "futureUnknownSecret": "must-not-persist"
            }
        """.trimIndent()

        val validated = TVSettingsBackupPolicy.validate(raw.encodeToByteArray())

        assertEquals(1, validated.receipt.totalFields)
        assertTrue(validated.rawJson.contains("tv-compatible"))
        assertFalse(validated.rawJson.contains("phoneAppearance"))
        assertFalse(validated.rawJson.contains("notificationsEnabled"))
        assertFalse(validated.rawJson.contains("must-not-persist"))
    }
}
