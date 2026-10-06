package com.fourseveneightnine.tv.client.data.catalog

import com.fourseveneightnine.contract.CatalogArtifact
import com.fourseveneightnine.contract.CatalogFacetIndex
import com.fourseveneightnine.contract.CatalogFacetRow
import com.fourseveneightnine.contract.CatalogManifestPayload
import com.fourseveneightnine.contract.CatalogSnapshotContract
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json

/**
 * A catalog server made of maps.
 *
 * It answers real URLs with real payloads, so the store's URL building, ETag handling and bounded
 * concurrency are all exercised. Signatures are not: [FakeSnapshotVerifier] takes the place of
 * Ed25519 so a test can hand-write a manifest.
 */
class FakeCatalogHttp : CatalogHttp {
    val bodies: MutableMap<String, ByteArray> = mutableMapOf()
    val etags: MutableMap<String, String> = mutableMapOf()
    val failures: MutableMap<String, Throwable> = mutableMapOf()
    val delaysMillis: MutableMap<String, Long> = mutableMapOf()
    val requests: MutableList<String> = mutableListOf()

    override suspend fun get(
        url: String,
        token: String?,
        ifNoneMatch: String?,
        maximumBytes: Int,
    ): CatalogHttpResult {
        synchronized(requests) { requests += url }
        delaysMillis[url]?.let { delay(it) }
        failures[url]?.let { throw it }
        if (ifNoneMatch != null && ifNoneMatch == etags[url]) return CatalogHttpResult.NotModified
        val bytes = bodies[url] ?: throw CatalogHttpException(404)
        require(bytes.size <= maximumBytes) { "fake_response_size" }
        return CatalogHttpResult.Body(bytes, etags[url])
    }

    fun privateRequests(): List<String> = synchronized(requests) { requests.filter { "/private/" in it } }
}

/** Decodes the plain payloads the fake server serves. Optionally refuses, like a bad signature. */
class FakeSnapshotVerifier : SnapshotVerifier {
    private val json = Json { ignoreUnknownKeys = true }

    var failManifest: Boolean = false

    override fun manifest(
        bytes: ByteArray,
        source: SnapshotSource,
        ownerId: String,
    ): CatalogManifestPayload {
        require(!failManifest) { "manifest_signature" }
        return json.decodeFromString(bytes.decodeToString())
    }

    override fun artifact(bytes: ByteArray, artifact: CatalogArtifact): CatalogFacetIndex =
        json.decodeFromString(bytes.decodeToString())
}

/** Builds one public TMDB generation and registers it on [http]. */
class PublicGeneration(
    private val http: FakeCatalogHttp,
    val generation: String,
    val createdAt: String = "2026-09-20T10:00:00Z",
) {
    private val json = Json { encodeDefaults = true }
    private val artifacts = mutableListOf<CatalogArtifact>()

    fun shelf(key: String, catalogId: String, role: String, titles: List<String>): PublicGeneration {
        val index = CatalogFacetIndex(
            schemaVersion = 1,
            generation = generation,
            generatedAt = createdAt,
            catalogID = catalogId,
            complete = true,
            itemCount = titles.size,
            rows = titles.mapIndexed { position, title ->
                CatalogFacetRow(
                    canonicalID = "$catalogId::tt${2000000 + position}",
                    mediaType = if (role.contains("-tv-")) "series" else "movie",
                    title = title,
                    year = 2026,
                )
            },
        )
        val bytes = json.encodeToString(index).encodeToByteArray()
        artifacts += CatalogArtifact(
            key = key,
            sha256 = "0".repeat(64),
            byteCount = bytes.size,
            contentType = "application/json",
            role = role,
            catalogID = catalogId,
        )
        http.bodies[CatalogSnapshotContract.artifactBaseURL + CatalogSnapshotContract.artifactPath(key)] = bytes
        return this
    }

    fun publish(): PublicGeneration {
        val payload = CatalogManifestPayload(
            schemaVersion = 1,
            generation = generation,
            createdAt = createdAt,
            scope = "public",
            artifacts = artifacts.toList(),
        )
        http.bodies[CatalogSnapshotContract.manifestURL] = json.encodeToString(payload).encodeToByteArray()
        return this
    }

    companion object {
        /** The two public shelves Home draws, with one title each. */
        fun standard(http: FakeCatalogHttp, generation: String = "p1"): PublicGeneration =
            PublicGeneration(http, generation)
                .shelf(
                    "catalog/v1/popular-movies.json",
                    "4789:popular-movies:popular",
                    "popular-movies-popular",
                    listOf("Popular movie"),
                )
                .shelf(
                    "catalog/v1/popular-tv.json",
                    "4789:popular-tv:popular",
                    "popular-tv-popular",
                    listOf("Popular series"),
                )
                .publish()
    }
}

/** Builds one private generation and registers it on [http]. */
class PrivateGeneration(
    private val http: FakeCatalogHttp,
    val generation: String,
    val createdAt: String = "2026-09-20T10:00:00Z",
) {
    private val json = Json { encodeDefaults = true }
    private val artifacts = mutableListOf<CatalogArtifact>()

    fun shelf(
        key: String,
        catalogId: String,
        titles: List<String>,
        delayMillis: Long = 0L,
        fail: Throwable? = null,
    ): PrivateGeneration {
        val index = CatalogFacetIndex(
            schemaVersion = 1,
            generation = generation,
            generatedAt = createdAt,
            catalogID = catalogId,
            complete = true,
            itemCount = titles.size,
            rows = titles.mapIndexed { position, title ->
                CatalogFacetRow(
                    canonicalID = "$catalogId::tt${1000000 + position}",
                    mediaType = "movie",
                    title = title,
                    posterURL = "https://image.tmdb.org/t/p/original/$position.jpg",
                    year = 2026,
                )
            },
        )
        val bytes = json.encodeToString(index).encodeToByteArray()
        val artifact = CatalogArtifact(
            key = key,
            sha256 = "0".repeat(64),
            byteCount = bytes.size,
            contentType = "application/json",
            role = "private-catalog",
            catalogID = catalogId,
        )
        artifacts += artifact
        val url = CatalogSnapshotContract.privateArtifactBaseURL +
            CatalogSnapshotContract.privateArtifactPath(key, "owner")
        http.bodies[url] = bytes
        if (delayMillis > 0L) http.delaysMillis[url] = delayMillis
        fail?.let { http.failures[url] = it }
        return this
    }

    /** Publishes the manifest, optionally with an ETag the store can revalidate against. */
    fun publish(etag: String? = null): PrivateGeneration {
        val payload = CatalogManifestPayload(
            schemaVersion = 1,
            generation = generation,
            createdAt = createdAt,
            scope = "private",
            ownerID = "owner",
            artifacts = artifacts.toList(),
        )
        http.bodies[CatalogSnapshotContract.privateManifestURL] =
            json.encodeToString(payload).encodeToByteArray()
        if (etag != null) {
            http.etags[CatalogSnapshotContract.privateManifestURL] = etag
        } else {
            http.etags.remove(CatalogSnapshotContract.privateManifestURL)
        }
        return this
    }
}
