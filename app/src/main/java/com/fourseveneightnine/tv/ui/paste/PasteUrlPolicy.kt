package com.fourseveneightnine.tv.ui.paste

/**
 * What the receiver should do with a pasted URL.
 *
 * The remote's on-screen keyboard is slow — every keypress is a D-pad walk over an alphabet grid —
 * so a paste surface has to be forgiving. That's this policy's job: recognise the two shapes that
 * play direct on Exo (`.mp4`, `.m3u8`, anything HTTPS whose `Content-Type` starts with `video/` at fetch time)
 * versus a watch-page URL that needs a resolver first, and reject the rest with a specific reason
 * the viewer can act on.
 */
internal enum class PasteVerdict {
    /** Direct media — hand straight to the player. Covers explicit MP4/HLS/DASH extensions and
     *  the pre-resolved Einthusan CDN link (`cdn2.einthusan.io/d/...` or `.../h/...`). */
    DirectMedia,

    /** A page URL a resolver could handle. Today only Einthusan's watch page, and it needs the
     *  iOS app's credentialed session — the receiver has none. Report and instruct. */
    EinthusanWatchPage,

    /** Not a URL, or not one this receiver can play. Report and re-prompt. */
    Unsupported,
}

internal data class PasteDecision(val verdict: PasteVerdict, val playableUrl: String? = null)

internal object PasteUrlPolicy {

    fun decide(rawInput: String): PasteDecision {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return PasteDecision(PasteVerdict.Unsupported)
        // Fire TV remote keyboard sometimes appends a space when it thinks two words were spoken.
        val normalized = trimmed.replace(WHITESPACE, "")
        // Downgrade http→https on hosts we know serve https, but don't fight the user: the CDN
        // itself signs http links, so we cannot force https universally.
        if (!looksLikeHttp(normalized)) return PasteDecision(PasteVerdict.Unsupported)
        if (isDirectMedia(normalized)) return PasteDecision(PasteVerdict.DirectMedia, normalized)
        if (isEinthusanWatchPage(normalized)) return PasteDecision(PasteVerdict.EinthusanWatchPage)
        return PasteDecision(PasteVerdict.Unsupported)
    }

    private fun looksLikeHttp(s: String): Boolean =
        s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true)

    /**
     * A URL is "direct media" if the path ends in a known extension OR if it is an Einthusan CDN
     * link — those have query strings after the extension (`.mp4?e=…&md5=…`), and the extension
     * check alone misses them.
     */
    private fun isDirectMedia(s: String): Boolean {
        val path = pathOf(s).lowercase()
        if (DIRECT_EXTENSIONS.any(path::endsWith)) return true
        val host = hostOf(s).lowercase()
        if (host.endsWith("einthusan.io") || host.endsWith("einthusan.tv")) {
            // .../d/... = signed MP4, .../h/... = HLS manifest — both directly playable.
            if (path.startsWith("/d/") || path.startsWith("/h/")) return true
        }
        return false
    }

    private fun isEinthusanWatchPage(s: String): Boolean {
        val host = hostOf(s).lowercase()
        if (!host.endsWith("einthusan.tv") && !host.endsWith("einthusan.io")) return false
        val path = pathOf(s)
        return WATCH_PATH.containsMatchIn(path)
    }

    private fun hostOf(url: String): String =
        HOST.find(url)?.groupValues?.getOrElse(1) { "" }.orEmpty()

    private fun pathOf(url: String): String {
        val afterScheme = url.substringAfter("://", "")
        val afterHost = afterScheme.substringAfter('/', "")
        return "/" + afterHost.substringBefore('?').substringBefore('#')
    }

    private val WHITESPACE = Regex("""\s+""")
    private val HOST = Regex("""^https?://([^/?#]+)""", RegexOption.IGNORE_CASE)
    private val WATCH_PATH = Regex("""/(?:premium/)?movie/watch/[A-Za-z0-9]+""")
    private val DIRECT_EXTENSIONS = listOf(".mp4", ".m4v", ".mov", ".mkv", ".webm", ".m3u8", ".mpd", ".ts")
}
