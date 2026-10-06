package com.fourseveneightnine.tv.client.data.addons

import com.fourseveneightnine.tv.client.data.settings.AddonSource
import java.net.URI
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** A one-use Stremio device link. The code can authorize this TV, so never log it. */
class StremioLink internal constructor(val code: String, val url: String) {
    override fun toString(): String = "StremioLink(<redacted>)"
}

/** Only add-on links are retained after the temporary Stremio session is closed. */
class StremioImportCollection internal constructor(
    val addons: List<AddonSource>,
    val skipped: Int,
) {
    override fun toString(): String = "StremioImportCollection(addons=${addons.size}, skipped=$skipped)"
}

/**
 * Reads Stremio's device-link and add-on collection endpoints without storing a password or auth
 * key. The link is opened on the user's phone; only this TV polls it. All requests are bounded and
 * non-redirecting. An auth key stays in memory just long enough to read the collection and log out.
 */
class StremioAccountImport(private val http: OkHttpClient = defaultAddonHttpClient()) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun createLink(): StremioLink {
        val result = result(http.getText("$LINK_API/create", LINK_RESPONSE_BYTES).body)
        val code = result.string("code")?.takeIf { CODE.matches(it) }
            ?: throw IllegalArgumentException("invalid_link_code")
        val link = result.string("link")?.takeIf { value ->
            runCatching { URI(value) }.getOrNull()?.let { uri ->
                uri.scheme == "https" && uri.host == "link.stremio.com" && uri.userInfo == null
            } == true
        } ?: throw IllegalArgumentException("invalid_link_url")
        return StremioLink(code, link)
    }

    /** Null means the user has not finished the link yet. */
    suspend fun readAuthKey(link: StremioLink): String? {
        val url = "$LINK_API/read?code=${link.code}"
        val root = root(http.getText(url, LINK_RESPONSE_BYTES).body)
        val error = root["error"] as? JsonObject
        if (error != null) {
            if ((error["code"] as? JsonPrimitive)?.intOrNull == LINK_PENDING_CODE) return null
            throw IllegalStateException("stremio_link_failed")
        }
        return (root["result"] as? JsonObject)?.string("authKey")
            ?.takeIf { it.length in 8..512 && it.none(Char::isWhitespace) }
            ?: throw IllegalArgumentException("invalid_auth_response")
    }

    /** Always attempts to close the Stremio session, including when collection parsing fails. */
    suspend fun collectAndLogout(authKey: String): StremioImportCollection = try {
        val response = post("addonCollectionGet", "{\"authKey\":${JsonPrimitive(authKey)},\"update\":false}")
        parseCollection(response)
    } finally {
        withContext(NonCancellable) {
            runCatching { post("logout", "{\"authKey\":${JsonPrimitive(authKey)}}") }
        }
    }

    /** Accepts Stremio's API response or its portable descriptor-array JSON export. */
    fun parseCollection(raw: String): StremioImportCollection {
        val parsed: JsonElement = runCatching { json.parseToJsonElement(raw) }.getOrNull()
            ?: throw IllegalArgumentException("invalid_addon_collection")
        val descriptors = when (parsed) {
            is JsonArray -> parsed
            is JsonObject -> {
                val body = (parsed["result"] as? JsonObject) ?: parsed
                body["addons"] as? JsonArray
            }
            else -> null
        }
            ?: throw IllegalArgumentException("invalid_addon_collection")
        require(descriptors.size <= MAX_DESCRIPTORS) { "addon_collection_too_large" }
        val seen = mutableSetOf<String>()
        var skipped = 0
        val addons = descriptors.mapNotNull { element ->
            val descriptor = element as? JsonObject
            val rawUrl = descriptor?.string("transportUrl")
            val url = rawUrl?.let(AddonEndpoint::manifestURL)
            val uri = url?.let { runCatching { URI(it) }.getOrNull() }
            val key = url?.let(AddonEndpoint::normalize)
            if (uri?.scheme?.lowercase(Locale.US) != "https" ||
                uri.host.isNullOrBlank() || uri.host.equals("localhost", true) ||
                uri.host.startsWith("127.") || uri.host == "::1" || key == null ||
                key == AddonEndpoint.normalize(StremioClient.CINEMETA_MANIFEST) || !seen.add(key)
            ) {
                skipped++
                null
            } else {
                val manifest = descriptor["manifest"] as? JsonObject
                AddonSource(
                    name = manifest?.string("name")?.take(80)?.ifBlank { "Add-on" } ?: "Add-on",
                    url = url,
                    enabled = true,
                )
            }
        }
        return StremioImportCollection(addons, skipped)
    }

    private suspend fun post(method: String, body: String): String {
        val request = Request.Builder()
            .url("$ACCOUNT_API/$method")
            .header("Accept", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val response = http.send(request, COLLECTION_RESPONSE_BYTES)
        if (!response.successful) throw HttpStatusException(response.code)
        return response.body
    }

    private fun result(raw: String): JsonObject = result(root(raw))

    private fun result(root: JsonObject): JsonObject {
        if (root["error"] != null) throw IllegalStateException("stremio_api_failed")
        return root["result"] as? JsonObject ?: throw IllegalArgumentException("invalid_stremio_response")
    }

    private fun root(raw: String): JsonObject =
        runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: throw IllegalArgumentException("invalid_stremio_response")

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)

    private companion object {
        const val LINK_API = "https://link.stremio.com/api/v2"
        const val ACCOUNT_API = "https://api.strem.io/api"
        const val LINK_RESPONSE_BYTES = 16 * 1024
        const val COLLECTION_RESPONSE_BYTES = 4 * 1024 * 1024
        const val MAX_DESCRIPTORS = 512
        const val LINK_PENDING_CODE = 101
        val CODE = Regex("[A-Za-z0-9]{4,64}")
    }
}
