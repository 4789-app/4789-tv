package com.fourseveneightnine.tv.client.data.catalog

import com.fourseveneightnine.contract.CatalogArtifact
import com.fourseveneightnine.contract.CatalogFacetIndex
import com.fourseveneightnine.contract.CatalogManifestPayload
import com.fourseveneightnine.contract.CatalogSnapshotContract

/**
 * Ed25519 signature, SHA-256 digest and byte-count checks.
 *
 * A seam, not a policy: [ContractSnapshotVerifier] is the only production implementation and it
 * delegates straight to the frozen contract. Tests replace it so a fake HTTP layer can serve plain
 * unsigned JSON.
 */
public interface SnapshotVerifier {
    public fun manifest(bytes: ByteArray, source: SnapshotSource, ownerId: String): CatalogManifestPayload
    public fun artifact(bytes: ByteArray, artifact: CatalogArtifact): CatalogFacetIndex
}

/** The real one. Every byte is checked again on every read, cached or fresh. */
public object ContractSnapshotVerifier : SnapshotVerifier {
    override fun manifest(
        bytes: ByteArray,
        source: SnapshotSource,
        ownerId: String,
    ): CatalogManifestPayload = when (source) {
        SnapshotSource.PUBLIC_TMDB -> CatalogSnapshotContract.decodeAndVerifyManifest(bytes)
        SnapshotSource.PRIVATE_CATALOG ->
            CatalogSnapshotContract.decodeAndVerifyPrivateManifest(bytes, ownerId)
    }

    override fun artifact(bytes: ByteArray, artifact: CatalogArtifact): CatalogFacetIndex =
        CatalogSnapshotContract.decodeAndVerifyArtifact(bytes, artifact)
}
