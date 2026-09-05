package com.fourseveneightnine.contract

import com.google.crypto.tink.subtle.Ed25519Verify
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SignedCatalogManifest(
    val schemaVersion: Int,
    val payload: String,
    val signatures: List<CatalogManifestSignature>,
)

@Serializable
data class CatalogManifestSignature(
    val keyID: String,
    val algorithm: String,
    val value: String,
)

@Serializable
data class CatalogManifestPayload(
    val schemaVersion: Int,
    val generation: String,
    val createdAt: String,
    val scope: String,
    val ownerID: String? = null,
    val artifacts: List<CatalogArtifact>,
)

@Serializable
data class CatalogArtifact(
    val key: String,
    val sha256: String,
    val byteCount: Int,
    val contentType: String,
    val role: String,
    val catalogID: String? = null,
    val displayName: String? = null,
)

@Serializable
data class CatalogFacetIndex(
    val schemaVersion: Int,
    val generation: String,
    val generatedAt: String,
    val catalogID: String,
    val complete: Boolean,
    val itemCount: Int,
    val rows: List<CatalogFacetRow>,
)

@Serializable
data class CatalogFacetRow(
    val canonicalID: String,
    val mediaType: String,
    val title: String,
    val posterURL: String? = null,
    val backdropURL: String? = null,
    val overview: String? = null,
    val exactReleaseDate: String? = null,
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    val originalLanguage: String? = null,
    val listPosition: Int? = null,
    val source: String? = null,
)

object CatalogSnapshotContract {
    const val manifestURL = "https://api.4789library.com/v1/catalog-manifest"
    const val artifactBaseURL = "https://api.4789library.com/v1/artifacts/"
    const val privateManifestURL = "https://api.4789library.com/v1/private/catalog-manifest"
    const val privateArtifactBaseURL = "https://api.4789library.com/v1/private/artifacts/"
    const val maximumManifestBytes = 128 * 1024
    const val maximumManifestArtifacts = 256
    const val maximumCatalogBytes = 5 * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true }
    private val publicKeys = mapOf(
        "release-v1" to "gnqMUGk86hOjpj+o0zXsaTxJTgYQAYf+8W5CFV15eUA=",
        "automation-v1" to "EFleJpQkToLRobsQeYsGtWM/jh3ez6XXdHgqe9/AhgY=",
    ).mapValues { Base64.getDecoder().decode(it.value) }

    fun decodeAndVerifyManifest(data: ByteArray): CatalogManifestPayload {
        return decodeAndVerifyManifest(
            data = data,
            expectedScope = "public",
            expectedOwnerID = null,
            trustedKeys = publicKeys,
        )
    }

    fun decodeAndVerifyPrivateManifest(
        data: ByteArray,
        expectedOwnerID: String,
    ): CatalogManifestPayload {
        require(expectedOwnerID.matches(Regex("^[a-zA-Z0-9_-]{1,100}$"))) { "manifest_owner" }
        return decodeAndVerifyManifest(
            data = data,
            expectedScope = "private",
            expectedOwnerID = expectedOwnerID,
            trustedKeys = publicKeys,
        )
    }

    internal fun decodeAndVerifyManifest(
        data: ByteArray,
        expectedScope: String,
        expectedOwnerID: String?,
        trustedKeys: Map<String, ByteArray>,
    ): CatalogManifestPayload {
        require(data.size in 1..maximumManifestBytes) { "manifest_size" }
        val wrapper = json.decodeFromString<SignedCatalogManifest>(data.decodeToString())
        require(wrapper.schemaVersion == 1) { "manifest_schema" }
        val payloadBytes = Base64.getDecoder().decode(wrapper.payload)
        val verified = wrapper.signatures.any { signature ->
            signature.algorithm == "ed25519" && trustedKeys[signature.keyID]?.let { key ->
                runCatching {
                    Ed25519Verify(key).verify(Base64.getDecoder().decode(signature.value), payloadBytes)
                }.isSuccess
            } == true
        }
        require(verified) { "manifest_signature" }
        return json.decodeFromString<CatalogManifestPayload>(payloadBytes.decodeToString()).also {
            require(it.schemaVersion == 1 && it.scope == expectedScope) { "manifest_payload" }
            require(expectedOwnerID == null || it.ownerID == expectedOwnerID) { "manifest_owner" }
            require(it.generation.isNotBlank() && it.createdAt.isNotBlank()) { "manifest_identity" }
            // Private imports may contain one artifact per Letterboxd list. Keep this
            // comfortably above the current multi-list account size while still bounding
            // manifest traversal and response work on the receiver.
            require(it.artifacts.size <= maximumManifestArtifacts) { "manifest_artifacts" }
        }
    }

    fun decodeAndVerifyArtifact(data: ByteArray, artifact: CatalogArtifact): CatalogFacetIndex {
        require(artifact.byteCount in 1..maximumCatalogBytes) { "artifact_size" }
        require(data.size == artifact.byteCount) { "artifact_length" }
        require(artifact.sha256.matches(Regex("^[0-9a-f]{64}$"))) { "artifact_sha" }
        require(sha256(data) == artifact.sha256) { "artifact_integrity" }
        return json.decodeFromString<CatalogFacetIndex>(data.decodeToString()).also { index ->
            require(index.schemaVersion == 1 && index.complete) { "artifact_schema" }
            require(index.itemCount == index.rows.size) { "artifact_count" }
            require(index.rows.all { it.canonicalID.isNotBlank() && it.title.isNotBlank() }) { "artifact_rows" }
        }
    }

    fun artifactPath(key: String): String {
        require(key.matches(Regex("^catalog/v1/[a-zA-Z0-9._/-]{1,500}$"))) { "artifact_key" }
        require(!key.contains("..") && !key.contains("//")) { "artifact_key" }
        return percentEncodePathParameter(key)
    }

    fun privateArtifactPath(key: String, expectedOwnerID: String): String {
        require(expectedOwnerID.matches(Regex("^[a-zA-Z0-9_-]{1,100}$"))) { "artifact_owner" }
        require(key.matches(Regex("^private/v1/[a-zA-Z0-9_-]{1,100}/[a-zA-Z0-9._/-]{1,500}$"))) { "artifact_key" }
        require(key.startsWith("private/v1/$expectedOwnerID/")) { "artifact_owner" }
        require(!key.contains("..") && !key.contains("//")) { "artifact_key" }
        return percentEncodePathParameter(key)
    }

    fun rowsAsDiscoverItems(index: CatalogFacetIndex): List<DiscoverItem> = index.rows.map { row ->
        DiscoverItem(
            id = row.canonicalID,
            type = row.mediaType,
            title = row.title,
            posterURL = row.posterURL,
            backdropURL = row.backdropURL,
            description = row.overview,
            year = row.year,
            genres = row.genres,
            catalogID = index.catalogID,
        )
    }

    private fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(data)
        .joinToString("") { "%02x".format(it) }

    private fun percentEncodePathParameter(value: String): String = value
        .toByteArray()
        .joinToString("") { byte ->
            val unsigned = byte.toInt() and 0xff
            val char = unsigned.toChar()
            if (char.isLetterOrDigit() || char in "-._~") {
                char.toString()
            } else {
                "%${unsigned.toString(16).uppercase().padStart(2, '0')}"
            }
        }
}
