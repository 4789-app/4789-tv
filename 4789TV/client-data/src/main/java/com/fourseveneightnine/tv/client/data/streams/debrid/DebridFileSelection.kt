// Derived from NuvioTV (GPL-3.0) app/src/main/java/com/nuvio/tv/core/debrid/DebridFileSelection.kt
//
// The selection LOGIC is theirs and is kept: normalise a filename, try the names the add-on
// actually stated, then the season/episode pattern, then the file index the add-on gave, then an
// unambiguous playable file. The Retrofit types are gone — this module speaks OkHttp and its own DTOs.
package com.fourseveneightnine.tv.client.data.streams.debrid

import java.util.Locale
import com.fourseveneightnine.tv.client.data.streams.StreamExpectation
import com.fourseveneightnine.tv.client.data.streams.StreamRow
import com.fourseveneightnine.tv.client.data.streams.StreamFacts

/** One file inside a torrent, as either provider describes it. */
data class DebridFile(
    val id: Int?,
    val name: String,
    val sizeBytes: Long?,
    val mimeType: String? = null,
    val torrentIndex: Int? = null,
) {
    fun isPlayableVideo(): Boolean {
        if (name.substringBefore("|^|").lowercase(Locale.ROOT).substringAfterLast('.') in setOf("zip", "rar", "7z", "iso")) return false
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
    val expectation: StreamExpectation? = null,
)

/**
 * Pick the file to play out of a torrent.
 *
 * The order matters and each step exists because the one before it is not always available:
 * an add-on that named the file is trusted first; a season pack has to be matched on the episode
 * pattern; `fileIdx` is what Stremio offers when neither is stated; and ambiguous files
 * are rejected.
 */
object DebridFileSelection {

    fun select(files: List<DebridFile>, hint: DebridSelectionHint): DebridFile? {
        if (StreamFacts.isTheatricalRelease(hint.torrentName.orEmpty())) return null
        val season = hint.season
        val episode = hint.episode
        fun compatible(file: DebridFile): Boolean {
            if (!file.isPlayableVideo() || StreamFacts.isTheatricalRelease(file.name)) return false
            val leaf = file.name.replace('\\', '/').substringAfterLast('/')
            val tail = TECH.find(leaf)?.range?.first?.let { leaf.substring(it) } ?: leaf
            if (EXTRA.containsMatchIn(tail)) return false
            if (COMPOSITE.containsMatchIn(leaf)) return false
            if (hint.expectation?.isMovie == true && !StreamExpectation.genericFilename(leaf)) {
                val candidate = StreamRow("file", "provider", leaf, leaf, filename = leaf)
                if (!hint.expectation.matches(candidate)) return false
            }
            if (season == null || episode == null) return true
            val folder = seasonLabel.findAll(file.name.replace('\\', '/').substringBeforeLast('/', "")).lastOrNull()
                ?.groupValues?.get(1)?.toIntOrNull()
            if (folder != null && folder != season) return false
            val labels = episodeLabel.findAll(leaf).toList()
            if (labels.size > 1) return false
            val coordinate = coordinates(file.name, hint)
            val named = !StreamExpectation.genericFilename(leaf) && !MARKER_LED.containsMatchIn(leaf)
            if (named && hint.expectation != null && !hint.expectation.matches(
                StreamRow("file", "provider", leaf, leaf, filename = leaf))) return false
            return coordinate == null || coordinate == (season to episode)
        }
        val playable = files.filter(::compatible)
        if (playable.isEmpty()) return null

        val patterns = episodePatterns(hint.season, hint.episode)
        val names = specificNames(hint, patterns)
        val named = if (names.isNotEmpty()) playable.firstNameMatch(names) else null
        hint.fileIdx?.let { index ->
            val selected = if (files.any { it.torrentIndex != null }) files.firstOrNull { it.torrentIndex == index }
                else files.getOrNull(index)
            if (named != null && named != selected) return null
            return selected?.takeIf(::compatible)
        }
        if (named != null) return named
        if (patterns.isNotEmpty()) {
            return playable.filter { coordinates(it.name, hint) == (season to episode) }.singleOrNull()
        }

        // A known episode cannot be replaced by the largest unrelated file in the pack.
        if (patterns.isNotEmpty()) return null
        val expected = hint.expectation
        if (expected != null && expected.isMovie) {
            val named = playable.filter { expected.matches(StreamRow("file", "provider", it.name, it.name, filename = it.name)) }
            if (named.isNotEmpty()) return named.maxByOrNull { it.sizeBytes ?: 0L }
        }
        return playable.singleOrNull()
    }

    private fun coordinates(path: String, hint: DebridSelectionHint): Pair<Int, Int>? {
        val name = path.replace('\\', '/').substringAfterLast('/')
        val explicit = episodeLabel.findAll(name).toList()
        if (explicit.size == 1) {
            val m = explicit.single().groupValues
            val s = m[1].ifEmpty { m[3] }.toIntOrNull() ?: return null
            val e = m[2].ifEmpty { m[4] }.toIntOrNull() ?: return null
            return s to e
        }
        val folder = seasonLabel.findAll(path.replace('\\', '/').substringBeforeLast('/', "")).lastOrNull()
            ?.groupValues?.get(1)?.toIntOrNull()
        val parent = hint.torrentName?.let { seasonLabel.findAll(it).lastOrNull()?.groupValues?.get(1)?.toIntOrNull() }
        val season = folder ?: parent ?: hint.season?.takeIf { it <= 1 } ?: return null
        val e = EPISODE_ONLY.find(name)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull() ?: return null
        return season to e
    }
    private val seasonLabel = Regex("(?i)(?:^|[^a-z0-9])s(?:eason)?[ ._-]*0*([0-9]{1,3})(?![0-9])")
    private val EPISODE_ONLY = Regex("(?i)^(?:e(?:p|pisode)?[ ._-]*([0-9]{1,3})|([0-9]{1,3}))(?=[ ._-]|$)")
    private val MARKER_LED = Regex("(?i)^(?:s[0-9]+|[0-9]+x[0-9]+|e(?:p|pisode)?[ ._-]*[0-9]+|[0-9]{1,3}[ ._-])")
    private val TECH = Regex("(?i)(?:19|20)\\d{2}|\\d{3,4}[pi]")
    private val EXTRA = Regex("(?i)(?:^|[ ._-])(?:sample|trailer|teaser|featurette|extras)(?=[ ._-]|$)")
    private val COMPOSITE = Regex("(?i)(?:e|x)\\d+\\s*(?:[-–+]|e)\\s*(?:e|s\\d+e)?\\d+")
    private val episodeLabel = Regex("""(?i)(?<![0-9])(?:s([0-9]{1,3})[ ._-]*ep?[ ._-]*([0-9]{1,4})|([0-9]{1,2})x([0-9]{1,3}))(?![0-9])""")

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
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
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

    private fun List<DebridFile>.firstNameMatch(names: List<String>): DebridFile? {
        val exact = filter { normalizedName(it.name) in names }
        if (exact.size == 1) return exact.single()
        return filter { file ->
            val fileName = normalizedName(file.name)
            names.any { name -> " $fileName ".contains(" $name ") || " $name ".contains(" $fileName ") }
        }.singleOrNull()
    }
}

internal fun String.hasDebridVideoExtension(): Boolean = DEBRID_VIDEO_EXTENSIONS.any(::endsWith)

private val DEBRID_VIDEO_EXTENSIONS = setOf(
    ".mp4", ".mkv", ".webm", ".avi", ".mov", ".m4v", ".ts", ".m2ts", ".wmv", ".flv",
)
