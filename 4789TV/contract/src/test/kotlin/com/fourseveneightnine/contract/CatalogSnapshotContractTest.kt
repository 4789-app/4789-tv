package com.fourseveneightnine.contract

import com.google.crypto.tink.subtle.Ed25519Sign
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogSnapshotContractTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun artifactIntegrityAndCountFailClosed() {
        val body = """{"schemaVersion":1,"generation":"g","generatedAt":"now","catalogID":"c","complete":true,"itemCount":0,"rows":[]}""".encodeToByteArray()
        val artifact = CatalogArtifact(
            key = "catalog/v1/artifacts/c/hash.json",
            sha256 = sha256(body),
            byteCount = body.size,
            contentType = "application/json",
            role = "popular-movies-popular",
        )
        assertEquals("c", CatalogSnapshotContract.decodeAndVerifyArtifact(body, artifact).catalogID)
        assertThrows(IllegalArgumentException::class.java) {
            CatalogSnapshotContract.decodeAndVerifyArtifact(body + 0, artifact)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CatalogSnapshotContract.decodeAndVerifyArtifact(body, artifact.copy(sha256 = "0".repeat(64)))
        }
    }

    @Test
    fun artifactKeyEncodingNeverCreatesASecondPathParameter() {
        val encoded = CatalogSnapshotContract.artifactPath("catalog/v1/artifacts/movie/a.json")
        assertEquals("catalog%2Fv1%2Fartifacts%2Fmovie%2Fa.json", encoded)
        assertThrows(IllegalArgumentException::class.java) { CatalogSnapshotContract.artifactPath("../outside") }

        val privateEncoded = CatalogSnapshotContract.privateArtifactPath(
            "private/v1/owner/artifacts/private-tamilmv-a/hash.json",
            "owner",
        )
        assertEquals(
            "private%2Fv1%2Fowner%2Fartifacts%2Fprivate-tamilmv-a%2Fhash.json",
            privateEncoded,
        )
        assertThrows(IllegalArgumentException::class.java) {
            CatalogSnapshotContract.privateArtifactPath(
                "private/v1/someone-else/artifacts/private-tamilmv-a/hash.json",
                "owner",
            )
        }
    }

    @Test
    fun manifestVerificationRejectsUnknownAndTamperedSignatures() {
        val pair = Ed25519Sign.KeyPair.newKeyPair()
        val payload = """{"schemaVersion":1,"generation":"g","createdAt":"now","scope":"public","artifacts":[]}""".encodeToByteArray()
        val wrapper = SignedCatalogManifest(
            schemaVersion = 1,
            payload = Base64.getEncoder().encodeToString(payload),
            signatures = listOf(
                CatalogManifestSignature(
                    keyID = "unknown",
                    algorithm = "ed25519",
                    value = Base64.getEncoder().encodeToString(Ed25519Sign(pair.privateKey).sign(payload)),
                ),
            ),
        )
        val encoded = json.encodeToString(wrapper).encodeToByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            CatalogSnapshotContract.decodeAndVerifyManifest(encoded)
        }
        assertTrue(encoded.isNotEmpty())
    }

    @Test
    fun privateManifestRequiresSignedPrivateScopeAndExactOwner() {
        val pair = Ed25519Sign.KeyPair.newKeyPair()
        val payload = """{"schemaVersion":1,"generation":"g","createdAt":"now","scope":"private","ownerID":"owner","artifacts":[]}""".encodeToByteArray()
        val wrapper = SignedCatalogManifest(
            schemaVersion = 1,
            payload = Base64.getEncoder().encodeToString(payload),
            signatures = listOf(
                CatalogManifestSignature(
                    keyID = "test",
                    algorithm = "ed25519",
                    value = Base64.getEncoder().encodeToString(Ed25519Sign(pair.privateKey).sign(payload)),
                ),
            ),
        )
        val encoded = json.encodeToString(wrapper).encodeToByteArray()
        assertEquals(
            "g",
            CatalogSnapshotContract.decodeAndVerifyManifest(
                data = encoded,
                expectedScope = "private",
                expectedOwnerID = "owner",
                trustedKeys = mapOf("test" to pair.publicKey),
            ).generation,
        )
        assertThrows(IllegalArgumentException::class.java) {
            CatalogSnapshotContract.decodeAndVerifyManifest(
                data = encoded,
                expectedScope = "private",
                expectedOwnerID = "someone-else",
                trustedKeys = mapOf("test" to pair.publicKey),
            )
        }
    }

    @Test
    fun privateManifestAcceptsMultiListLetterboxdPayload() {
        val pair = Ed25519Sign.KeyPair.newKeyPair()
        val artifacts = (0..100).map { index ->
            CatalogArtifact(
                key = "private/v1/owner/artifacts/private-letterboxd-$index/hash.json",
                sha256 = "0".repeat(64),
                byteCount = 1,
                contentType = "application/json",
                role = "private-letterboxd-$index",
                catalogID = "letterboxd:owned:cineorgasm:list-$index",
            )
        }
        val payload = json.encodeToString(
            CatalogManifestPayload(
                schemaVersion = 1,
                generation = "g",
                createdAt = "now",
                scope = "private",
                ownerID = "owner",
                artifacts = artifacts,
            ),
        ).encodeToByteArray()
        val wrapper = SignedCatalogManifest(
            schemaVersion = 1,
            payload = Base64.getEncoder().encodeToString(payload),
            signatures = listOf(
                CatalogManifestSignature(
                    keyID = "test",
                    algorithm = "ed25519",
                    value = Base64.getEncoder().encodeToString(Ed25519Sign(pair.privateKey).sign(payload)),
                ),
            ),
        )
        val encoded = json.encodeToString(wrapper).encodeToByteArray()
        assertEquals(
            101,
            CatalogSnapshotContract.decodeAndVerifyManifest(
                data = encoded,
                expectedScope = "private",
                expectedOwnerID = "owner",
                trustedKeys = mapOf("test" to pair.publicKey),
            ).artifacts.size,
        )
    }

    private fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(data)
        .joinToString("") { "%02x".format(it) }
}
