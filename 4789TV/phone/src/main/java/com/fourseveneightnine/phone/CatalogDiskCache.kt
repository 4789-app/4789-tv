package com.fourseveneightnine.phone

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * App-owned disk cache for the catalog manifest and artifact JSON.
 *
 * This mirrors `App/FourSevenEightNine/Shared/CatalogDiskCache.swift`, which exists on iOS for the
 * same reason: the catalog is fetched over two sequential network round trips before any live
 * content can appear, and without a cache every cold start pays for both again.
 *
 * Measured against api.4789library.com on a fast connection the two requests cost about 0.6s
 * total. That is the good case. The client allows 3s to connect and 5s to read per request, so a
 * poor mobile network can spend far longer, and until it finishes the viewer sees only the catalog
 * bundled into the APK at build time.
 *
 * The rules, matching iOS:
 *   - disk first: a fresh entry is returned without touching the network;
 *   - network only when the entry is stale or missing;
 *   - stale fallback: if the network fails, an expired entry is still better than nothing.
 *
 * Caching the bytes is safe because neither payload is trusted on the strength of having been
 * cached. `CatalogSnapshotContract.decodeAndVerifyManifest` checks the manifest signature and
 * `decodeAndVerifyArtifact` checks the artifact against the SHA-256 the manifest declares. Both
 * run again on every read, cached or not, so a tampered cache file fails exactly like a tampered
 * download.
 */
internal class CatalogDiskCache(
    context: Context,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
) {
    private val directory: File? = runCatching {
        File(context.cacheDir, DIRECTORY_NAME).apply { mkdirs() }
    }.getOrNull()?.takeIf { it.isDirectory }

    /** The cache file for [url], or null when no cache directory could be created. */
    private fun fileFor(url: String): File? {
        val dir = directory ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(url.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$digest.json")
    }

    /** Bytes for [url] when a cache entry exists and is younger than the TTL. */
    fun fresh(url: String, nowMillis: Long = System.currentTimeMillis()): ByteArray? {
        val file = fileFor(url)?.takeIf(File::isFile) ?: return null
        val age = nowMillis - file.lastModified()
        if (age < 0 || age >= ttlMillis) return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    /**
     * Bytes for [url] whatever their age.
     *
     * Only for the network-failed path. A month-old catalog is a worse answer than a fresh one and
     * a far better answer than an empty screen.
     */
    fun stale(url: String): ByteArray? =
        fileFor(url)?.takeIf(File::isFile)?.let { file -> runCatching { file.readBytes() }.getOrNull() }

    /** Stores [bytes] for [url]. A failure here is not worth failing the load over. */
    fun store(url: String, bytes: ByteArray) {
        val file = fileFor(url) ?: return
        runCatching {
            file.writeBytes(bytes)
            trim()
        }
    }

    /**
     * Keeps the cache under [MAX_BYTES] by deleting the oldest entries first.
     *
     * The catalog artifact is about 46 KB, so the cap is generous by design; it is a backstop
     * against an unbounded directory rather than a tight budget.
     */
    private fun trim() {
        val dir = directory ?: return
        val files = dir.listFiles()?.filter(File::isFile) ?: return
        var total = files.sumOf(File::length)
        if (total <= MAX_BYTES) return
        for (file in files.sortedBy(File::lastModified)) {
            if (total <= MAX_BYTES) return
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    internal companion object {
        private const val DIRECTORY_NAME = "catalog-cache-v1"

        /** 12 hours, the same TTL the iOS cache uses. */
        const val DEFAULT_TTL_MILLIS = 12L * 60 * 60 * 1_000

        private const val MAX_BYTES = 25L * 1024 * 1024
    }
}
