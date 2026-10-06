// Derived from NuvioTV (GPL-3.0) app/src/main/java/com/nuvio/tv/core/debrid/DebridFileSelection.kt
//
// The selection LOGIC is theirs and is kept: normalise a filename, try the names the add-on
// actually stated, then the season/episode pattern, then the file index the add-on gave, then the
// biggest playable file. The Retrofit types are gone — this module speaks OkHttp and its own DTOs.
package com.fourseveneightnine.tv.client.data.streams.debrid

import java.util.Locale

/** One file inside a torrent, as either provider describes it. */
data class DebridFile(
    val id: Int?,
    val name: String,
    val sizeBytes: Long?,
    val mimeType: String? = null,
) {
    fun isPlayableVideo(): Boolean {
        if (mimeType.orEmpty().lowercase(Locale.ROOT).startsWith("video/")) return true
        return name.lowercase(Locale.ROOT).hasDebridVideoExtension()
    }
}

/** What the add-on told us about the file it meant. */
data class DebridSelectionHint(
    val filename: String? = null,
    val torrentName: String? = null,
    val fileIdx: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

/**
 * Pick the file to play out of a torrent.
 *
 * The order matters and each step exists because the one before it is not always available:
 * an add-on that named the file is trusted first; a season pack has to be matched on the episode
 * pattern; `fileIdx` is what Stremio offers when neither is stated; and the biggest playable file
 * is the last honest guess.
 */
object DebridFileSelection {

    fun select(files: List<DebridFile>, hint: DebridSelectionHint): DebridFile? {
        val playable = files.filter(DebridFile::isPlayableVideo)
        if (playable.isEmpty()) return null

        val patterns = episodePatterns(hint.season, hint.episode)
        val names = specificNames(hint, patterns)
        if (names.isNotEmpty()) {
            playable.firstNameMatch(names)?.let { return it }
        }

        if (patterns.isNotEmpty()) {
            playable.firstOrNull { file ->
                val name = file.name.lowercase(Locale.ROOT)
                patterns.any(name::contains)
            }?.let { return it }
        }

        hint.fileIdx?.let { index ->
            files.getOrNull(index)?.takeIf(DebridFile::isPlayableVideo)?.let { return it }
            // Several add-ons write a 1-based index. Trying index - 1 costs nothing and rescues a
            // whole family of them.
            if (index > 0) {
                files.getOrNull(index - 1)?.takeIf(DebridFile::isPlayableVideo)?.let { return it }
            }
            playable.firstOrNull { it.id == index }?.let { return it }
        }

        return playable.maxByOrNull { it.sizeBytes ?: 0L }
    }

    /** "s02e04", "2x04", "2x4" — the three forms release names actually use. */
    fun episodePatterns(season: Int?, episode: Int?): List<String> {
        if (season == null || episode == null) return emptyList()
        val seasonTwo = season.toString().padStart(2, '0')
        val episodeTwo = episode.toString().padStart(2, '0')
        return listOf("s${seasonTwo}e$episodeTwo", "${season}x$episodeTwo", "${season}x$episode")
    }

    /** Strip the path, the extension and the punctuation, so two spellings compare equal. */
    fun normalizedName(value: String): String = value
        .substringAfterLast('/')
        .substringBeforeLast('.')
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    private fun specificNames(hint: DebridSelectionHint, patterns: List<String>): List<String> =
        listOfNotNull(
            hint.filename,
            hint.torrentName?.takeIf { it.looksSpecific(patterns) },
        )
            .map(::normalizedName)
            .filter(String::isNotBlank)
            .distinct()

    private fun String.looksSpecific(patterns: List<String>): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.hasDebridVideoExtension() || patterns.any(lower::contains)
    }

    private fun List<DebridFile>.firstNameMatch(names: List<String>): DebridFile? = firstOrNull { file ->
        val fileName = normalizedName(file.name)
        names.any { name -> fileName.contains(name) || name.contains(fileName) }
    }
}

internal fun String.hasDebridVideoExtension(): Boolean = DEBRID_VIDEO_EXTENSIONS.any(::endsWith)

private val DEBRID_VIDEO_EXTENSIONS = setOf(
    ".mp4", ".mkv", ".webm", ".avi", ".mov", ".m4v", ".ts", ".m2ts", ".wmv", ".flv",
)
