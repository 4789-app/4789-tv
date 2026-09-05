package com.fourseveneightnine.phone

import android.content.Context
import androidx.core.content.edit
import java.util.Base64

internal enum class OfflineDownloadState { Queued, Running, Failed, Completed }

/**
 * One queued download, exactly as it survives on disk.
 *
 * [fingerprint] is the ETag or Last-Modified the server gave for the bytes already written. It is
 * the only evidence that a half-finished file may be continued. No signed link is ever stored, so
 * this record is not a secret and lives in plain SharedPreferences.
 */
internal data class OfflineDownloadJob(
    val recipe: DurableStreamRecipe,
    val state: OfflineDownloadState,
    val bytesDone: Long = 0,
    val declaredBytes: Long = 0,
    val attempts: Int = 0,
    val fingerprint: String? = null,
    val detail: String? = null,
) {
    /** How far this download got, or null when the server never declared a size. */
    val progressPercent: Int?
        get() = if (declaredBytes > 0) {
            (bytesDone.coerceIn(0, declaredBytes) * 100 / declaredBytes).toInt()
        } else {
            null
        }
}

/**
 * The on-disk form of an [OfflineDownloadJob].
 *
 * Every field is Base64 of its own UTF-8 bytes, joined with `|`. A title, a filename, or a server
 * ETag can then hold any character at all without one field bleeding into the next.
 */
internal object OfflineDownloadRecordFormat {
    private const val VERSION = "1"
    private const val SEPARATOR = "|"
    private const val ABSENT = "~"
    private const val PRESENT = "="
    private const val FIELD_COUNT = 13

    fun encode(job: OfflineDownloadJob): String = listOf(
        VERSION,
        field(job.recipe.titleID),
        field(job.recipe.mediaType),
        field(job.recipe.selectorHash),
        field(job.recipe.label),
        field(job.recipe.filename),
        field(job.recipe.sizeBytes?.toString()),
        field(job.state.name),
        field(job.bytesDone.toString()),
        field(job.declaredBytes.toString()),
        field(job.attempts.toString()),
        field(job.fingerprint),
        field(job.detail),
    ).joinToString(SEPARATOR)

    fun decode(value: String): OfflineDownloadJob? = runCatching {
        val parts = value.split(SEPARATOR)
        require(parts.size == FIELD_COUNT) { "field_count" }
        require(parts[0] == VERSION) { "version" }
        val titleID = requireNotNull(text(parts[1]))
        val mediaType = requireNotNull(text(parts[2]))
        require(AddonConfigurationPolicy.isTitleID(titleID)) { "title_id" }
        require(AddonConfigurationPolicy.normalizedMediaType(mediaType) == mediaType) { "media_type" }
        OfflineDownloadJob(
            recipe = DurableStreamRecipe(
                titleID = titleID,
                mediaType = mediaType,
                selectorHash = requireNotNull(text(parts[3])),
                label = requireNotNull(text(parts[4])),
                filename = text(parts[5]),
                sizeBytes = text(parts[6])?.toLong(),
            ),
            state = OfflineDownloadState.valueOf(requireNotNull(text(parts[7]))),
            bytesDone = requireNotNull(text(parts[8])).toLong(),
            declaredBytes = requireNotNull(text(parts[9])).toLong(),
            attempts = requireNotNull(text(parts[10])).toInt(),
            fingerprint = text(parts[11]),
            detail = text(parts[12]),
        )
    }.getOrNull()

    private fun field(value: String?): String = if (value == null) {
        ABSENT
    } else {
        PRESENT + Base64.getUrlEncoder().withoutPadding().encodeToString(value.encodeToByteArray())
    }

    private fun text(value: String): String? {
        if (value == ABSENT) return null
        require(value.startsWith(PRESENT)) { "field_marker" }
        return Base64.getUrlDecoder().decode(value.removePrefix(PRESENT)).decodeToString()
    }
}

/**
 * Where queued downloads live between app runs.
 *
 * Plain SharedPreferences on purpose. The secure store holds the manifest, which is the credential.
 * A recipe grants nothing on its own, so encrypting it would only hide a fault from the viewer.
 */
internal class OfflineDownloadRecipeStore(
    context: Context,
    preferenceName: String = "offline-download-recipes-v1",
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        preferenceName,
        Context.MODE_PRIVATE,
    )

    fun load(titleID: String): OfflineDownloadJob? = preferences
        .getString(key(titleID), null)
        ?.let(OfflineDownloadRecordFormat::decode)

    fun save(job: OfflineDownloadJob): Boolean {
        preferences.edit(commit = true) {
            putString(key(job.recipe.titleID), OfflineDownloadRecordFormat.encode(job))
        }
        return load(job.recipe.titleID) == job
    }

    fun remove(titleID: String): Boolean {
        preferences.edit(commit = true) { remove(key(titleID)) }
        return !preferences.contains(key(titleID))
    }

    fun all(): List<OfflineDownloadJob> = preferences.all.keys
        .filter { it.startsWith(KEY_PREFIX) }
        .mapNotNull { stored -> preferences.getString(stored, null) }
        .mapNotNull(OfflineDownloadRecordFormat::decode)

    fun clearAll(): Boolean {
        preferences.edit(commit = true) { clear() }
        return preferences.all.isEmpty()
    }

    private fun key(titleID: String) = KEY_PREFIX + titleID

    private companion object {
        const val KEY_PREFIX = "job:"
    }
}
