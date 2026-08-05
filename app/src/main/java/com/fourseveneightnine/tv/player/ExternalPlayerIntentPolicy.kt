package com.fourseveneightnine.tv.player

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.fourseveneightnine.tv.protocol.InstalledExternalPlayer

object ExternalPlayerIntentPolicy {
    const val PACKAGE_TIVIMATE = "ar.tvplayer.tv"
    const val PACKAGE_TIVIMATE_PREMIUM = "ar.tvplayer.tv.premium"
    const val PACKAGE_JUST_PLAYER = "com.brouken.player"
    const val PACKAGE_KODI = "org.xbmc.kodi"
    const val PACKAGE_VLC = "org.videolan.vlc"
    const val PACKAGE_NEXT_PLAYER = "dev.anilbeesetti.nextplayer"
    const val PACKAGE_MPV = "is.xyz.mpv"

    // Preference order for the error-overlay fallback: the players that accept the most handoff
    // context first, so the viewer resumes rather than restarts. Just Player and Next Player take
    // position + sideloaded subtitle + headers; VLC takes position and a subtitle path; Kodi and
    // TiviMate take the URL alone.
    private val KNOWN_PLAYERS = listOf(
        "Just Player" to listOf(PACKAGE_JUST_PLAYER),
        "Next Player" to listOf(PACKAGE_NEXT_PLAYER),
        "VLC" to listOf(PACKAGE_VLC),
        "mpv" to listOf(PACKAGE_MPV),
        "Kodi" to listOf(PACKAGE_KODI),
        "TiviMate" to listOf(PACKAGE_TIVIMATE, PACKAGE_TIVIMATE_PREMIUM),
    )

    /**
     * How much of a handoff a given player can actually accept. Sending MX-style extras to a
     * player that does not read them is harmless, but sending nothing where a player DOES read
     * them costs the viewer their position and subtitle — so the mapping is explicit rather than
     * "throw every extra at everyone and hope".
     */
    enum class HandoffDialect {
        /** MX-Player extras: position (int ms), subs/subs.name/subs.enable, headers[]. */
        MxStyle,

        /** VLC reads extra_position (long ms), extra_duration, and subtitles_location. */
        Vlc,

        /** No documented resume/subtitle intent API — the URL and a title are all it takes. */
        UrlOnly,
    }

    fun dialect(targetPackage: String?): HandoffDialect = when (targetPackage) {
        PACKAGE_JUST_PLAYER, PACKAGE_NEXT_PLAYER, null, "" -> HandoffDialect.MxStyle
        PACKAGE_VLC -> HandoffDialect.Vlc
        else -> HandoffDialect.UrlOnly
    }

    data class PlayerTargetInfo(
        val label: String,
        val packageName: String,
        val isInstalled: Boolean,
    )

    fun installedPlayers(context: Context): List<InstalledExternalPlayer> =
        KNOWN_PLAYERS.mapNotNull { (label, packages) ->
            packages.firstOrNull { context.packageManager.isInstalled(it) }
                ?.let { InstalledExternalPlayer(label = label, packageName = it) }
        }

    fun allPlayers(context: Context): List<PlayerTargetInfo> =
        KNOWN_PLAYERS.map { (label, packages) ->
            val installedPkg = packages.firstOrNull { context.packageManager.isInstalled(it) }
            val pkg = installedPkg ?: packages.first()
            PlayerTargetInfo(label = label, packageName = pkg, isInstalled = installedPkg != null)
        }

    private fun PackageManager.isInstalled(packageName: String): Boolean =
        try {
            getPackageInfo(packageName, 0)
            true
        } catch (_: Exception) {
            false
        }

    /**
     * Everything the receiver knows about the running playback, so the external player can resume
     * it instead of restarting it. These are the MX-Player-style extras Just Player and VLC read.
     */
    data class HandoffContext(
        val positionMillis: Long = 0,
        val subtitleURL: String? = null,
        val subtitleName: String? = null,
        val headers: Map<String, String> = emptyMap(),
    )

    /** The MX-style extras a handoff carries, resolved without touching Android types. */
    data class HandoffExtras(
        val positionMillis: Int? = null,
        val subtitleURL: String? = null,
        val subtitleName: String = "Subtitle",
        val headerPairs: Array<String>? = null,
    ) {
        override fun equals(other: Any?): Boolean =
            other is HandoffExtras &&
                positionMillis == other.positionMillis &&
                subtitleURL == other.subtitleURL &&
                subtitleName == other.subtitleName &&
                headerPairs?.toList() == other.headerPairs?.toList()

        override fun hashCode(): Int =
            listOf(positionMillis, subtitleURL, subtitleName, headerPairs?.toList()).hashCode()
    }

    /**
     * Resume where the viewer is, keep the subtitle they chose, keep the credentials the link
     * needs. Position is omitted at the head of a file — the external player's own resume logic
     * is better than an explicit 0. Subtitles are sent pre-enabled because re-picking a
     * remote-fetched track inside the other app is the slowest part of a handoff.
     */
    fun handoffExtras(context: HandoffContext): HandoffExtras = HandoffExtras(
        positionMillis = context.positionMillis.takeIf { it > 0 }?.toInt(),
        subtitleURL = context.subtitleURL?.takeIf { it.isNotBlank() },
        subtitleName = context.subtitleName?.takeIf { it.isNotBlank() } ?: "Subtitle",
        headerPairs = context.headers
            .takeIf { it.isNotEmpty() }
            ?.flatMap { (name, value) -> listOf(name, value) }
            ?.toTypedArray(),
    )

    fun buildIntent(
        url: String,
        title: String? = null,
        targetPackage: String? = null,
        context: HandoffContext = HandoffContext(),
    ): Intent {
        val uri = Uri.parse(url)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (!title.isNullOrEmpty()) {
                putExtra("title", title)
                putExtra("android.intent.extra.TITLE", title)
            }
            val extras = handoffExtras(context)
            when (dialect(targetPackage)) {
                HandoffDialect.MxStyle -> {
                    extras.positionMillis?.let { putExtra("position", it) }
                    extras.subtitleURL?.let { subtitle ->
                        val subUri = Uri.parse(subtitle)
                        putExtra("subs", arrayOf(subUri))
                        putExtra("subs.name", arrayOf(extras.subtitleName))
                        putExtra("subs.enable", arrayOf(subUri))
                    }
                    extras.headerPairs?.let { putExtra("headers", it) }
                }

                HandoffDialect.Vlc -> {
                    // VLC uses its own names and a 64-bit position; it ignores the MX set.
                    extras.positionMillis?.let { putExtra("extra_position", it.toLong()) }
                    extras.subtitleURL?.let { putExtra("subtitles_location", it) }
                }

                // Kodi/TiviMate and anything unrecognised: the chooser and the URL, nothing to add.
                HandoffDialect.UrlOnly -> Unit
            }
        }
        if (!targetPackage.isNullOrEmpty()) {
            intent.setPackage(targetPackage)
        }
        return intent
    }

    fun launch(
        context: Context,
        url: String,
        title: String? = null,
        targetPackage: String? = null,
        handoff: HandoffContext = HandoffContext(),
    ): Boolean {
        return try {
            val intent = buildIntent(url, title, targetPackage, handoff)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun parseResultPosition(data: Intent?): Long? {
        if (data == null) return null
        val posInt = data.getIntExtra("position", -1)
        if (posInt > 0) return posInt.toLong()
        val posLong = data.getLongExtra("position", -1L)
        if (posLong > 0) return posLong
        val extraPos = data.getLongExtra("extra_position", -1L)
        if (extraPos > 0) return extraPos
        return null
    }
}
