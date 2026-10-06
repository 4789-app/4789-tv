package com.fourseveneightnine.tv.client.data.settings

import com.fourseveneightnine.tv.client.data.addons.AddonEndpoint
import com.fourseveneightnine.tv.client.data.streams.PlaybackRules
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** One stream or catalog add-on named by the phone's settings export. */
data class AddonSource(
    val name: String,
    val url: String,
    val enabled: Boolean,
)

/** One subtitle add-on named by the phone's settings export. */
data class SubtitleSource(
    val name: String,
    val url: String,
    val enabled: Boolean,
)

/**
 * Counts only. This is the ONLY shape a settings document may appear in on a screen, in a log
 * line or in a crash report: every field this document holds is either a credential or a URL that
 * carries one.
 */
data class SettingsReceipt(
    val totalFields: Int,
    val addonSources: Int,
    val subtitleSources: Int,
    val credentials: Int,
    val catalogOrder: Int,
    val letterboxdUsernames: Int,
    val hasPlaybackRules: Boolean,
)

/**
 * A typed, read-only view over the phone's `4789-settings` export.
 *
 * The export is already reduced to an allowlist before it reaches the TV
 * (`TVSettingsBackupPolicy.kt` in `:app`), so this type only has to read what survived. It never
 * mutates, never writes and never prints a value: [toString] prints [redacted] and nothing else,
 * because the single worst way to leak an AIOStreams URL is a helpful debug line.
 */
class SettingsDocument private constructor(private val root: JsonObject) {

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** A document with nothing in it. Every getter answers empty or null. */
        val empty: SettingsDocument = SettingsDocument(JsonObject(emptyMap()))

        /**
         * Parse the export. A body that is not a JSON object, or is not parseable at all, gives
         * [empty] rather than throwing: a corrupt settings blob must never be the reason the TV
         * refuses to start.
         */
        fun parse(rawJson: String): SettingsDocument {
            val parsed = runCatching { json.parseToJsonElement(rawJson) as? JsonObject }.getOrNull()
            return SettingsDocument(parsed ?: JsonObject(emptyMap()))
        }

        private const val MAX_ADDONS = 256
        private const val DISPLAY_NAME_LIMIT = 80
        private const val MAX_CREDENTIAL_LENGTH = 512
        private val CREDENTIAL_FIELDS = listOf(
            "torboxAPIKey", "realDebridAPIKey", "tmdbAPIKey", "mdbListAPIKey", "catalogServerToken",
        )
    }

    /**
     * Every add-on the phone knows about, in the order the registry should try them.
     *
     * `sources[]` first in stored order, then the two legacy manifest fields, then AIOStreams, then
     * MediaFusion. Duplicates are dropped by normalised URL, so an add-on listed both as a row and
     * as `primaryManifestURLText` appears once, under the row's name.
     */
    val addonSources: List<AddonSource> by lazy {
        val candidates = buildList {
            (root["sources"] as? JsonArray).orEmpty().forEachIndexed { index, element ->
                val source = element as? JsonObject ?: return@forEachIndexed
                val url = source.string("url") ?: return@forEachIndexed
                add(
                    AddonSource(
                        name = source.string("name")?.take(DISPLAY_NAME_LIMIT) ?: "Add-on ${index + 1}",
                        url = url,
                        enabled = source.boolean("enabled") != false,
                    ),
                )
            }
            root.string("primaryManifestURLText")?.let { add(AddonSource("Primary add-on", it, true)) }
            root.string("secondaryManifestURLText")?.let { add(AddonSource("Secondary add-on", it, true)) }
            root.string("aioStreamsURLText")?.let { add(AddonSource("AIOStreams", it, true)) }
            root.string("mediaFusionURLText")?.let {
                add(AddonSource("MediaFusion", it, root.boolean("mediaFusionEnabled") != false))
            }
        }
        candidates.dedupedByEndpoint().take(MAX_ADDONS)
    }

    /** TV-only imports live inside the encrypted settings document, never presentation preferences. */
    val tvImportedSources: List<AddonSource> by lazy {
        (root["tvImportedSources"] as? JsonArray).orEmpty().mapNotNull { element ->
            val source = element as? JsonObject ?: return@mapNotNull null
            val url = source.string("url") ?: return@mapNotNull null
            AddonSource(
                name = source.string("name")?.take(DISPLAY_NAME_LIMIT) ?: "Add-on",
                url = url,
                enabled = source.boolean("enabled") != false,
            )
        }.dedupedByEndpoint().take(MAX_ADDONS)
    }

    /** Replace hides phone and receiver-local add-ons until the viewer changes import mode. */
    val tvReplaceSources: Boolean get() = root.boolean("tvReplaceSources") == true

    /** Subtitle add-ons, `subtitleSources[]` then the dedicated AIO subtitles field. */
    val subtitleSources: List<SubtitleSource> by lazy {
        val candidates = buildList {
            (root["subtitleSources"] as? JsonArray).orEmpty().forEachIndexed { index, element ->
                val source = element as? JsonObject ?: return@forEachIndexed
                val url = source.string("url") ?: return@forEachIndexed
                add(
                    SubtitleSource(
                        name = source.string("name")?.take(DISPLAY_NAME_LIMIT) ?: "Subtitles ${index + 1}",
                        url = url,
                        enabled = source.boolean("enabled") != false,
                    ),
                )
            }
            aioSubtitlesURL?.let { add(SubtitleSource("AIO Subtitles", it, true)) }
        }
        val seen = mutableSetOf<String>()
        candidates.mapNotNull { source ->
            val normalized = AddonEndpoint.normalize(source.url) ?: return@mapNotNull null
            if (!seen.add(normalized)) null else source.copy(url = source.url.trim())
        }.take(MAX_ADDONS)
    }

    val aioSubtitlesURL: String? get() = root.string("aioSubtitlesURLText")

    /** Home shelf order, by catalog uid. An empty list means "the registry's own order". */
    val catalogOrder: List<String> by lazy { root.stringArray("catalogOrder").take(200) }

    val torboxAPIKey: String? get() = root.credential("torboxAPIKey")
    val realDebridAPIKey: String? get() = root.credential("realDebridAPIKey")
    val tmdbAPIKey: String? get() = root.credential("tmdbAPIKey")
    val mdbListAPIKey: String? get() = root.credential("mdbListAPIKey")
    val catalogServerToken: String? get() = root.credential("catalogServerToken")

    /** Both the plural field and the older singular one, deduplicated. */
    val letterboxdUsernames: List<String> by lazy {
        (root.stringArray("letterboxdUsernames") + listOfNotNull(root.string("letterboxdUsername")))
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .take(20)
    }

    /** How many uncached grabs a day the rules allow. Null means the phone never stated one. */
    val uncachedDailyMax: Int? get() = root.int("uncachedDailyMax")?.takeIf { it >= 0 }

    /** A one-off override of [uncachedDailyMax]. Null means no override. */
    val uncachedSlotOverride: Int? get() = root.int("uncachedSlotOverride")?.takeIf { it >= 0 }

    /** The phone's picking rules, or [PlaybackRules.DEFAULT] when the export never carried them. */
    val playbackRules: PlaybackRules by lazy {
        PlaybackRules.from(root["playbackRules"] ?: root["playbackRulesJSON"])
    }

    /** Counts, never values. Safe to print, log and show on the Settings screen. */
    fun redacted(): SettingsReceipt = SettingsReceipt(
        totalFields = root.size,
        addonSources = addonSources.size,
        subtitleSources = subtitleSources.size,
        credentials = CREDENTIAL_FIELDS.count { root.credential(it) != null },
        catalogOrder = catalogOrder.size,
        letterboxdUsernames = letterboxdUsernames.size,
        hasPlaybackRules = root["playbackRules"] != null || root["playbackRulesJSON"] != null,
    )

    override fun toString(): String = "SettingsDocument(${redacted()})"

    private fun List<AddonSource>.dedupedByEndpoint(): List<AddonSource> {
        val seen = mutableSetOf<String>()
        return mapNotNull { source ->
            val normalized = AddonEndpoint.normalize(source.url) ?: return@mapNotNull null
            if (!seen.add(normalized)) null else source.copy(url = source.url.trim())
        }
    }

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.boolean(key: String): Boolean? =
        (get(key) as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.int(key: String): Int? =
        (get(key) as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }

    private fun JsonObject.stringArray(key: String): List<String> =
        (get(key) as? JsonArray).orEmpty().mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
        }

    /**
     * A credential has to look like one. A blank, a whitespace run or a 4 KB blob is a broken
     * export, and sending it to a provider only turns a settings problem into a network problem.
     */
    private fun JsonObject.credential(key: String): String? =
        string(key)?.takeIf { it.length in 4..MAX_CREDENTIAL_LENGTH && it.none(Char::isWhitespace) }
}
