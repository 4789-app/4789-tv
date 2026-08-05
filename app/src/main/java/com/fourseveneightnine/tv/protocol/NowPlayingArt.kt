package com.fourseveneightnine.tv.protocol

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Artwork for the title the phone is about to cast, pushed on the `X4789.NowPlaying` sidechannel
 * alongside the name.
 *
 * It is deliberately NOT part of [ReceiverController]: no engine needs it, and threading a poster
 * through the player only to hand it back to the Activity buys nothing. This is a process-level
 * letterbox between the RPC layer and the screen, which is what it actually is.
 *
 * Both fields are optional. A phone that sends neither, or an older phone that does not know the
 * field exists, leaves the overlay exactly as it was: a title on black.
 */
data class NowPlayingArt(
    /** 16:9 backdrop — what the buffering screen wants. */
    val landscapeURL: String? = null,

    /** Portrait poster, used only when there is no backdrop. */
    val posterURL: String? = null,
) {
    val best: String? get() = landscapeURL ?: posterURL
    val isPortrait: Boolean get() = landscapeURL == null && posterURL != null

    companion object {
        /**
         * Only http(s), only a sane length. The receiver fetches whatever this says, so a `file://`
         * or `content://` here would be a request to read the box's own storage.
         */
        fun sanitize(value: String?): String? = value
            ?.trim()
            ?.takeIf { it.length <= MAX_URL_LENGTH }
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

        const val MAX_URL_LENGTH = 2_048
    }
}

/** The current title's artwork; the Activity collects it, the dispatcher writes it. */
object NowPlayingArtwork {
    private val _art = MutableStateFlow<NowPlayingArt?>(null)
    val art: StateFlow<NowPlayingArt?> = _art.asStateFlow()

    fun stage(landscapeURL: String?, posterURL: String?) {
        val landscape = NowPlayingArt.sanitize(landscapeURL)
        val poster = NowPlayingArt.sanitize(posterURL)
        _art.value = if (landscape == null && poster == null) {
            null
        } else {
            NowPlayingArt(landscapeURL = landscape, posterURL = poster)
        }
    }

    fun clear() {
        _art.value = null
    }
}
