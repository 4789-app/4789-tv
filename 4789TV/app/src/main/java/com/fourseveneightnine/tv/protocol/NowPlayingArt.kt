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
 * Artwork is optional. Named titles keep their own name while a title logo is unavailable.
 */
data class NowPlayingArt(
    /** 16:9 backdrop — what the buffering screen wants. */
    val landscapeURL: String? = null,

    /** Portrait poster, used only when there is no backdrop. */
    val posterURL: String? = null,
    val logoURL: String? = null,
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
    private val staged = LinkedHashMap<String?, NowPlayingArt?>()
    private var currentArt: NowPlayingArt? = null
    private var currentOwner: String? = null
    private var visibleOwner: String? = null
    private var playbackGeneration: Long? = null

    @Synchronized
    fun stage(landscapeURL: String?, posterURL: String?, logoURL: String? = null, owner: String? = null) {
        val landscape = NowPlayingArt.sanitize(landscapeURL)
        val poster = NowPlayingArt.sanitize(posterURL)
        val logo = NowPlayingArt.sanitize(logoURL)
        val next = if (landscape == null && poster == null && logo == null) null
            else NowPlayingArt(landscapeURL = landscape, posterURL = poster, logoURL = logo)
        // An older Open can reach Main after the next cast stages its artwork. Keep both owners.
        // The small bound retires abandoned preparations without retaining an unbounded history.
        if (staged.size >= 4 && !staged.containsKey(owner)) staged.remove(staged.keys.first())
        staged[owner] = next
        visibleOwner = owner
        _art.value = next
    }

    /** Consume only this open's artwork; a newer pending cast keeps its own staged artwork. */
    @Synchronized
    fun beginPlayback(generation: Long, useStaged: Boolean = true, owner: String? = null) {
        val next = staged.remove(owner)
        currentArt = next.takeIf { useStaged }
        currentOwner = owner
        visibleOwner = owner
        playbackGeneration = generation
        _art.value = currentArt
    }

    @Synchronized
    fun forPlayback(generation: Long): NowPlayingArt? = currentArt.takeIf { playbackGeneration == generation }

    /** Partial status updates may reuse art only from the same visible incoming owner. */
    @Synchronized
    fun forOwner(owner: String?): NowPlayingArt? {
        if (visibleOwner != owner) return null
        return if (staged.containsKey(owner)) staged[owner] else currentArt.takeIf { currentOwner == owner }
    }

    /** Metadata cannot replace a newer playback generation or an announced replacement cast. */
    @Synchronized
    fun update(generation: Long, landscapeURL: String?, posterURL: String?, logoURL: String?): Boolean {
        if (playbackGeneration != generation || visibleOwner != currentOwner || staged.containsKey(currentOwner)) return false
        stage(landscapeURL, posterURL, logoURL, owner = currentOwner)
        currentArt = _art.value
        staged.remove(currentOwner)
        return true
    }

    @Synchronized
    fun clear() {
        staged.clear()
        playbackGeneration = null
        currentOwner = null
        visibleOwner = null
        currentArt = null
        _art.value = null
    }
}
