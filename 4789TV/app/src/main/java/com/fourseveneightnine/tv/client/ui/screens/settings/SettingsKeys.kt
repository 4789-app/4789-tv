package com.fourseveneightnine.tv.client.ui.screens.settings

import android.content.SharedPreferences
import com.fourseveneightnine.tv.client.data.addons.AddonEndpoint
import com.fourseveneightnine.tv.client.data.addons.AddonRegistryOverrides
import com.fourseveneightnine.tv.client.data.addons.AddonRegistryStore
import com.fourseveneightnine.tv.client.data.settings.AddonSource
import com.fourseveneightnine.tv.settings.EncryptedTVSettingsStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Everything this TV remembers about how it looks and behaves, spec §14.7 and §14.8.
 *
 * All of it lives in one `SharedPreferences` file — `AppGraph.presentationPreferences`, named
 * `receiver_presentation` — beside the two keys the receiver already kept there
 * (`auto_frame_rate_enabled` and the engine override, which has its own file).
 *
 * These are TV-local choices. Nothing here is a setting the phone owns: the settings document is
 * read-only on this box, so a value that belongs to the phone is shown and never written.
 */
internal object SettingsKeys {

    /** Discover draws rows instead of the grid. Read by the Discover screen. */
    const val DISCOVER_ROWS = "discover_rows"

    /** `slate` (the default) or `black`. */
    const val CANVAS = "canvas"
    const val CANVAS_SLATE = "slate"
    const val CANVAS_BLACK = "black"

    /** `normal` (the default) or `compact`. */
    const val POSTER_SIZE = "poster_size"
    const val POSTER_NORMAL = "normal"
    const val POSTER_COMPACT = "compact"

    /** Every duration in the tree becomes zero when this is on. */
    const val REDUCE_MOTION = "reduce_motion"

    /** Screen fit, spec §19.5: 3% to 7%, step 0.5, default 5. */
    const val SCREEN_FIT_PERCENT = "screen_fit_percent"
    const val SCREEN_FIT_DEFAULT = 5.0f
    const val SCREEN_FIT_MIN = 3.0f
    const val SCREEN_FIT_MAX = 7.0f
    const val SCREEN_FIT_STEP = 0.5f

    /** Play the next episode when one ends. */
    const val AUTO_NEXT = "auto_next"

    /** Preferred audio language, as a display name ("English"). Empty means "whatever the file has". */
    const val AUDIO_LANGUAGE = "audio_language"

    /** A JSON array of catalog uids. Empty or absent means the phone's order. Home may read it. */
    const val CATALOG_ORDER_OVERRIDE = "catalog_order_override"

    /** A JSON array of `{"name":…,"url":…}` objects added on the TV by URL. */
    const val EXTRA_ADDONS = "extra_addons"

    /** Normalised endpoint keys explicitly switched on, off, or removed on this receiver. */
    const val ADDONS_ENABLED = "addons_enabled"
    const val ADDONS_DISABLED = "addons_disabled"
    const val ADDONS_REMOVED = "addons_removed"
    const val ADDON_ORDER = "addon_order"

    /** A JSON array of query strings, newest first, capped at five (spec §12.7.6). */
    const val RECENT_SEARCHES = "recent_searches"

    /** The languages the Playback page offers. The phone owns the real list; this is the picker. */
    val AUDIO_LANGUAGES = listOf(
        "Original", "English", "Tamil", "Telugu", "Hindi", "Malayalam",
        "Kannada", "Japanese", "Korean", "Spanish", "French", "German",
    )

    fun screenFit(prefs: SharedPreferences): Float =
        prefs.getFloat(SCREEN_FIT_PERCENT, SCREEN_FIT_DEFAULT).coerceIn(SCREEN_FIT_MIN, SCREEN_FIT_MAX)

    /** Steps the slider and clamps it. Returns the value to store. */
    fun nudgeScreenFit(current: Float, steps: Int): Float =
        (current + steps * SCREEN_FIT_STEP).coerceIn(SCREEN_FIT_MIN, SCREEN_FIT_MAX)

    fun screenFitLabel(value: Float): String =
        if (value == value.toInt().toFloat()) "${value.toInt()}%" else "$value%"

    // ------------------------------------------------------------------ JSON lists
    //
    // kotlinx.serialization, not `org.json`: the Android JSON classes are stubs that throw on a
    // plain JVM, and these two shapes are what the settings tests are about.

    private val json = Json { ignoreUnknownKeys = true }

    private val stringListSerializer = ListSerializer(String.serializer())
    private val extraAddonSerializer = ListSerializer(ExtraAddon.serializer())

    fun readStringList(prefs: SharedPreferences, key: String): List<String> =
        parseStringList(prefs.getString(key, null))

    fun parseStringList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(stringListSerializer, raw) }
            .getOrDefault(emptyList())
            .filter { it.isNotBlank() }
    }

    fun encodeStringList(values: List<String>): String =
        json.encodeToString(stringListSerializer, values)

    fun writeStringList(prefs: SharedPreferences, key: String, values: List<String>) {
        prefs.edit().putString(key, encodeStringList(values)).apply()
    }

    /** One add-on a viewer typed in on the TV. */
    @Serializable
    data class ExtraAddon(val name: String, val url: String)

    fun readExtraAddons(prefs: SharedPreferences): List<ExtraAddon> =
        parseExtraAddons(prefs.getString(EXTRA_ADDONS, null))

    fun parseExtraAddons(raw: String?): List<ExtraAddon> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(extraAddonSerializer, raw) }
            .getOrDefault(emptyList())
            .mapNotNull { addon ->
                val route = AddonEndpoint.manifestURL(addon.url) ?: return@mapNotNull null
                ExtraAddon(addon.name.trim().take(80).ifBlank { "Add-on" }, route)
            }
            .distinctBy { AddonEndpoint.normalize(it.url) }
            .take(256)
    }

    fun encodeExtraAddons(addons: List<ExtraAddon>): String =
        json.encodeToString(extraAddonSerializer, addons)

    fun writeExtraAddons(prefs: SharedPreferences, addons: List<ExtraAddon>) {
        prefs.edit().putString(EXTRA_ADDONS, encodeExtraAddons(addons)).apply()
    }

    /**
     * Moves one id within the stated order and returns the new order, or null when the move runs
     * off an end. The order is seeded from the registry so the first move writes a full list.
     */
    fun moved(order: List<String>, id: String, delta: Int): List<String>? {
        val from = order.indexOf(id).takeIf { it >= 0 } ?: return null
        val to = from + delta
        if (to !in order.indices) return null
        val next = order.toMutableList()
        next.add(to, next.removeAt(from))
        return next
    }

    /**
     * Moves one add-on's catalogs as a block and returns the flat catalog order to store.
     *
     * Add-on order is the shelf order on Home (spec §14.13.4) and one add-on owns several
     * catalogs, so a move has to carry all of them or the shelves interleave. [blocks] is one
     * entry per add-on, in the order shown. Returns null at either end.
     */
    fun movedBlocks(blocks: List<List<String>>, blockIndex: Int, delta: Int): List<String>? {
        if (blockIndex !in blocks.indices || delta == 0) return null
        val to = blockIndex + delta
        if (to !in blocks.indices) return null
        val next = blocks.toMutableList()
        next.add(to, next.removeAt(blockIndex))
        return next.flatten()
    }
}

/** Encrypted TV-local choices implementing the registry's Android-free persistence seam. */
internal class PreferenceAddonRegistryStore(
    private val preferences: SharedPreferences,
    private val vault: EncryptedTVSettingsStore,
) : AddonRegistryStore {
    @Serializable
    private data class StoredOverrides(
        val installed: List<SettingsKeys.ExtraAddon> = emptyList(),
        val enabled: Map<String, Boolean> = emptyMap(),
        val removed: Set<String> = emptySet(),
        val catalogOrder: List<String> = emptyList(),
        val addonOrder: List<String> = emptyList(),
    ) {
        fun overrides(): AddonRegistryOverrides = AddonRegistryOverrides(
            installed = installed.map { AddonSource(it.name, it.url, enabled = true) },
            enabled = enabled,
            removed = removed,
            catalogOrder = catalogOrder,
            addonOrder = addonOrder,
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    override fun load(): AddonRegistryOverrides {
        if (vault.hasAddonOverrides()) {
            val decoded = vault.loadAddonOverrides()?.let { raw ->
                runCatching { json.decodeFromString<StoredOverrides>(raw).overrides() }.getOrNull()
            } ?: return AddonRegistryOverrides()
            clearLegacy()
            return decoded
        }
        val legacy = loadLegacy()
        if (LEGACY_KEYS.any(preferences::contains)) runCatching { save(legacy) }
        return legacy
    }

    private fun loadLegacy(): AddonRegistryOverrides {
        val enabled = SettingsKeys.readStringList(preferences, SettingsKeys.ADDONS_ENABLED)
            .mapNotNull(AddonEndpoint::normalize)
            .associateWith { true }
        val disabled = SettingsKeys.readStringList(preferences, SettingsKeys.ADDONS_DISABLED)
            .mapNotNull(AddonEndpoint::normalize)
            .associateWith { false }
        return AddonRegistryOverrides(
            installed = SettingsKeys.readExtraAddons(preferences).map {
                AddonSource(it.name, it.url, enabled = true)
            },
            enabled = enabled + disabled,
            removed = SettingsKeys.readStringList(preferences, SettingsKeys.ADDONS_REMOVED)
                .mapNotNull(AddonEndpoint::normalize)
                .toSet(),
            catalogOrder = SettingsKeys.readStringList(preferences, SettingsKeys.CATALOG_ORDER_OVERRIDE),
            addonOrder = SettingsKeys.readStringList(preferences, SettingsKeys.ADDON_ORDER),
        )
    }

    override fun save(overrides: AddonRegistryOverrides) {
        val stored = StoredOverrides(
            installed = overrides.installed.map { SettingsKeys.ExtraAddon(it.name, it.url) },
            enabled = overrides.enabled,
            removed = overrides.removed,
            catalogOrder = overrides.catalogOrder,
            addonOrder = overrides.addonOrder,
        )
        check(vault.saveAddonOverrides(json.encodeToString(StoredOverrides.serializer(), stored))) {
            "addon_overrides_not_saved"
        }
        clearLegacy()
    }

    private fun clearLegacy() {
        if (LEGACY_KEYS.none(preferences::contains)) return
        val editor = preferences.edit()
        LEGACY_KEYS.forEach(editor::remove)
        editor.commit()
    }

    private companion object {
        val LEGACY_KEYS = listOf(
            SettingsKeys.EXTRA_ADDONS, SettingsKeys.ADDONS_ENABLED, SettingsKeys.ADDONS_DISABLED,
            SettingsKeys.ADDONS_REMOVED, SettingsKeys.CATALOG_ORDER_OVERRIDE, SettingsKeys.ADDON_ORDER,
        )
    }
}
