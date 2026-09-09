package com.fourseveneightnine.tv.catalog

import com.fourseveneightnine.tv.startup.ReceiverDiagnostics
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * A tiny string-to-string store that survives the process.
 *
 * Ratings and artwork were held in memory only, which on a television means they are held until
 * the viewer goes back to the launcher — so every cold start paid for the same lookups again and
 * the first few seconds of every session looked unfinished. This is the same lesson the poster
 * cache already learned; the enrichment work repeated it.
 *
 * Deliberately dumb: one small JSON file, read once, rewritten at most every [WRITE_EVERY] new
 * entries. It holds identifiers and urls, never a credential, so it lives in plain files rather
 * than the Keystore. A corrupt or unreadable file is treated as an empty cache — this is an
 * optimisation and must never be the reason something fails to load.
 */
internal class TVEnrichmentDiskCache(
    private val file: File?,
    private val ttlMillis: Long,
    private val maxEntries: Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Row(val value: String, val storedAtMillis: Long)

    private val entries = java.util.concurrent.ConcurrentHashMap<String, Row>()
    private val writesSinceFlush = AtomicInteger(0)
    @Volatile private var loaded = false

    @Synchronized
    private fun loadIfNeeded() {
        if (loaded) return
        loaded = true
        val source = file ?: return
        if (!source.exists()) return
        runCatching {
            val root = json.parseToJsonElement(source.readText()).jsonObject
            val now = clock()
            root.forEach { (key, element) ->
                val row = element as? JsonObject ?: return@forEach
                val value = (row["v"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
                val at = (row["t"] as? JsonPrimitive)?.longOrNull ?: return@forEach
                if (now - at < ttlMillis) entries[key] = Row(value, at)
            }
        }.onFailure {
            ReceiverDiagnostics.record("enrich.cache.unreadable", it::class.java.simpleName)
            entries.clear()
        }
        ReceiverDiagnostics.record("enrich.cache.loaded", "entries=${entries.size}")
    }

    fun get(key: String): String? {
        loadIfNeeded()
        val row = entries[key] ?: return null
        if (clock() - row.storedAtMillis >= ttlMillis) {
            entries.remove(key, row)
            return null
        }
        return row.value
    }

    fun put(key: String, value: String) {
        loadIfNeeded()
        if (entries.size >= maxEntries) entries.clear()
        entries[key] = Row(value, clock())
        if (writesSinceFlush.incrementAndGet() >= WRITE_EVERY) {
            writesSinceFlush.set(0)
            flush()
        }
    }

    @Synchronized
    fun flush() {
        val target = file ?: return
        runCatching {
            target.parentFile?.mkdirs()
            val body = entries.entries.joinToString(",") { (key, row) ->
                "${json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), JsonPrimitive(key))}:" +
                    "{\"v\":${json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), JsonPrimitive(row.value))}," +
                    "\"t\":${row.storedAtMillis}}"
            }
            // Staging rename, so a process death mid-write cannot leave half a file that every
            // later run would fail to parse.
            val staging = File(target.parentFile, "${target.name}.tmp")
            staging.writeText("{$body}")
            if (!staging.renameTo(target)) staging.delete()
        }.onFailure {
            ReceiverDiagnostics.record("enrich.cache.writeFailed", it::class.java.simpleName)
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        const val WRITE_EVERY = 8
    }
}
