package com.fourseveneightnine.tv.discovery

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.util.Locale

/** Hardware identity advertised to the phone; none of these values are used as stable identity. */
data class ReceiverDeviceIdentity(
    val manufacturer: String,
    val model: String,
    val deviceName: String?,
    val kind: String,
) {
    val displayName: String
        get() {
            val personal = clean(deviceName).takeIf { candidate ->
                candidate != null && !candidate.equals("Android TV", ignoreCase = true)
                    && !candidate.equals("4789 TV", ignoreCase = true)
            }
            val hardware = clean(model)
            val family = when (kind) {
                KIND_FIRE_TV -> "Fire TV"
                KIND_GOOGLE_TV -> "Google TV"
                KIND_ANDROID_BOX -> "Android box"
                else -> "Android TV"
            }
            val detail = hardware?.takeUnless { candidate ->
                candidate.equals("Android TV", ignoreCase = true) ||
                    candidate.equals(family, ignoreCase = true)
            }
            val evidence = listOfNotNull(personal, detail).joinToString(" ").lowercase(Locale.ROOT)
            val familyDetail = family.takeUnless { it.lowercase(Locale.ROOT) in evidence }
            return listOfNotNull(personal, detail, familyDetail)
                .distinctBy { it.lowercase(Locale.ROOT) }
                .joinToString(" · ")
        }

    companion object {
        const val KIND_FIRE_TV = "fireTV"
        const val KIND_GOOGLE_TV = "googleTV"
        const val KIND_ANDROID_TV = "androidTV"
        const val KIND_ANDROID_BOX = "androidBox"

        fun current(context: Context): ReceiverDeviceIdentity = fromHardware(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            deviceName = runCatching {
                Settings.Global.getString(context.contentResolver, "device_name")
            }.getOrNull(),
        )

        fun fromHardware(
            manufacturer: String,
            model: String,
            deviceName: String? = null,
        ): ReceiverDeviceIdentity {
            val maker = manufacturer.trim()
            val hardware = model.trim()
            val evidence = "$maker $hardware".lowercase(Locale.ROOT)
            val kind = when {
                "amazon" in evidence || hardware.startsWith("AFT", ignoreCase = true) -> KIND_FIRE_TV
                "google" in evidence || "chromecast" in evidence || "google tv" in evidence -> KIND_GOOGLE_TV
                listOf("box", "stick", "shield", "onn.", "onn ", "mi box").any { it in evidence } -> KIND_ANDROID_BOX
                else -> KIND_ANDROID_TV
            }
            return ReceiverDeviceIdentity(maker, hardware, deviceName, kind)
        }

        private fun clean(value: String?): String? {
            val cleaned = value
                ?.replace(Regex("(?i)^\\s*4789(?:\\s*[·:_-])?\\s*"), "")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            return cleaned
        }
    }
}
