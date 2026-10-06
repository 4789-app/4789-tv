package com.fourseveneightnine.tv.client.data.catalog

import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json

/**
 * Generation-keyed snapshot files in app-private storage.
 *
 * ```text
 * <filesDir>/catalog-snapshots/<source>/current        pointer, written atomically last
 * <filesDir>/catalog-snapshots/<source>/etag           last manifest ETag
 * <filesDir>/catalog-snapshots/<source>/gen-<hash>/head.json
 * <filesDir>/catalog-snapshots/<source>/gen-<hash>/full.json
 * ```
 *
 * Promotion is the `current` write and nothing else. A generation whose payload failed halfway is
 * never pointed at, so the previous complete generation keeps being served. Only complete
 * generations are written at all: a partial paint lives in memory and never reaches this class.
 *
 * The head file exists because the full private document is dominated by Letterboxd. A real
 * receiver's file was 9.18 MB and took 6,875 ms to decode, against 700 KB and 1,518 ms for the
 * Tamil MV shelves alone. Painting waits for the head, not the whole document.
 */
internal class SnapshotDiskCache(
    filesDir: File,
    private val json: Json,
) {
    private val root = File(filesDir, ROOT_DIRECTORY)

    private fun sourceDir(source: SnapshotSource): File = File(root, source.wire)

    private fun currentFile(source: SnapshotSource) = AtomicFile(File(sourceDir(source), CURRENT_FILE))

    private fun etagFile(source: SnapshotSource) = AtomicFile(File(sourceDir(source), ETAG_FILE))

    fun currentDirectoryName(source: SnapshotSource): String? = readText(currentFile(source))
        ?.takeIf { it.isNotBlank() }

    fun etag(source: SnapshotSource): String? = readText(etagFile(source))?.takeIf { it.isNotBlank() }

    fun storeEtag(source: SnapshotSource, etag: String?) {
        if (etag == null) {
            etagFile(source).delete()
            return
        }
        runCatching { writeText(etagFile(source), etag) }
    }

    /** The Tamil MV shelves alone, for first paint. Null when nothing has been promoted yet. */
    fun readHead(source: SnapshotSource): StoredSnapshot? = read(source, HEAD_FILE)

    /** Every shelf of the promoted generation. */
    fun readFull(source: SnapshotSource): StoredSnapshot? = read(source, FULL_FILE)

    private fun read(source: SnapshotSource, name: String): StoredSnapshot? {
        val generation = currentDirectoryName(source) ?: return null
        val file = File(File(sourceDir(source), generation), name)
        if (!file.isFile || file.length() !in 1..MAX_SNAPSHOT_BYTES) return null
        return runCatching { json.decodeFromString<StoredSnapshot>(file.readText()) }
            .getOrNull()
            ?.takeIf { it.schemaVersion == 1 }
    }

    /**
     * Writes both payloads, then flips `current`. [headShelves] is the subset painted first.
     *
     * @return the directory name of the promoted generation.
     */
    fun promote(
        source: SnapshotSource,
        snapshot: StoredSnapshot,
        headShelves: (Shelf) -> Boolean,
    ): String {
        val directoryName = directoryName(snapshot.generation)
        val directory = File(sourceDir(source), directoryName)
        require(directory.isDirectory || directory.mkdirs()) { "snapshot_directory" }
        val head = snapshot.copy(shelves = snapshot.shelves.filter(headShelves))
        File(directory, FULL_FILE).writeText(json.encodeToString(snapshot))
        File(directory, HEAD_FILE).writeText(json.encodeToString(head))
        writeText(currentFile(source), directoryName)
        return directoryName
    }

    /**
     * Deletes old generations, oldest first, until the whole cache fits [budgetBytes]. The
     * generation `current` points at is never a candidate: it is the last complete one and the only
     * thing standing between a failed refresh and an empty screen.
     */
    fun evict(budgetBytes: Long) {
        val protectedDirs = SnapshotSource.entries.mapNotNull { source ->
            currentDirectoryName(source)?.let { File(sourceDir(source), it) }
        }.toSet()
        var total = sizeOf(root)
        if (total <= budgetBytes) return
        val candidates = SnapshotSource.entries
            .flatMap { source ->
                sourceDir(source).listFiles().orEmpty()
                    .filter { it.isDirectory && it.name.startsWith(GENERATION_PREFIX) }
            }
            .filterNot { it in protectedDirs }
            .sortedBy(File::lastModified)
        for (directory in candidates) {
            if (total <= budgetBytes) return
            val size = sizeOf(directory)
            if (directory.deleteRecursively()) total -= size
        }
    }

    fun clear(source: SnapshotSource) {
        sourceDir(source).deleteRecursively()
    }

    private fun sizeOf(file: File): Long = when {
        !file.exists() -> 0L
        file.isFile -> file.length()
        else -> file.listFiles().orEmpty().sumOf(::sizeOf)
    }

    private fun readText(file: AtomicFile): String? =
        if (!file.baseFile.isFile) null else runCatching { file.readFully().decodeToString().trim() }.getOrNull()

    private fun writeText(file: AtomicFile, value: String) {
        file.baseFile.parentFile?.mkdirs()
        var output: java.io.FileOutputStream? = null
        try {
            output = file.startWrite()
            output.write(value.encodeToByteArray())
            file.finishWrite(output)
        } catch (failure: Throwable) {
            output?.let(file::failWrite)
            throw failure
        }
    }

    /** A generation string is server-controlled, so it is hashed rather than used as a path. */
    private fun directoryName(generation: String): String = GENERATION_PREFIX + MessageDigest
        .getInstance("SHA-256")
        .digest(generation.encodeToByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(24)

    companion object {
        const val ROOT_DIRECTORY = "catalog-snapshots"
        const val CURRENT_FILE = "current"
        const val ETAG_FILE = "etag"
        const val HEAD_FILE = "head.json"
        const val FULL_FILE = "full.json"
        const val GENERATION_PREFIX = "gen-"
        const val MAX_SNAPSHOT_BYTES = 32L * 1024 * 1024
    }
}
