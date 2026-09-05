package com.fourseveneightnine.tv.protocol

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One row of the phone's Continue-Watching list, mirrored to the box over `X4789.SetRecents`.
 *
 * WHY THE BOX WANTS THIS: the TV's own rail can only ever show what the TV played. A box the user
 * just set up has an empty home screen even though their phone knows exactly what they are halfway
 * through — so the first thing they see is a blank shelf and a remote. Mirroring the phone's list
 * (posters and resume positions included) means the rail is useful before the box has played
 * anything at all.
 *
 * A protocol-layer type, not a UI one: it crosses the wire, so it is validated here — at the edge —
 * and the Activity receives rows that are already known-good.
 */
data class PhoneRecentEntry(
    val url: String,
    val title: String,
    val subtitle: String?,
    val posterUrl: String?,
    val positionMillis: Long,
    val durationMillis: Long,
    val timestamp: Long,
    val landscapeUrl: String? = null,
    val overview: String? = null,
)

/**
 * The phone's mirrored list; the dispatcher writes it, the Activity collects it.
 *
 * Same shape and same reasoning as [NowPlayingArtwork]: no engine needs this, so threading it
 * through [ReceiverController] would only move it further from the screen that draws it.
 */
object PhoneRecents {
    private val _entries = MutableStateFlow<List<PhoneRecentEntry>?>(null)

    /** null until a phone has pushed at least once — distinct from "a phone pushed an empty list". */
    val entries: StateFlow<List<PhoneRecentEntry>?> = _entries.asStateFlow()

    fun publish(entries: List<PhoneRecentEntry>) {
        _entries.value = entries.take(MAX_ENTRIES)
    }

    /**
     * Every field is bounded here rather than at the screen. The box renders whatever this says and
     * FETCHES whatever `posterUrl` points at, so an unbounded title is a layout weapon and a
     * `file://` poster is a request to read the box's own storage.
     *
     * Returns null for a row that cannot be trusted; the caller drops it and keeps the rest — one
     * malformed row must not cost the user their whole rail.
     */
    fun sanitize(
        url: String?,
        title: String?,
        subtitle: String?,
        posterUrl: String?,
        positionMillis: Long,
        durationMillis: Long,
        timestamp: Long,
        now: Long = System.currentTimeMillis(),
        landscapeUrl: String? = null,
        overview: String? = null,
    ): PhoneRecentEntry? {
        val safeTitle = title?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_TEXT) ?: return null
        val safeUrl = url?.trim().orEmpty().takeIf { it.length <= MAX_URL } ?: return null
        // A row with no playable URL is still worth showing — it is a poster the user can look at
        // and then cast from their phone. What it must never be is a link to the box's filesystem.
        if (safeUrl.isNotEmpty() && !safeUrl.startsWith("http://") && !safeUrl.startsWith("https://")) {
            return null
        }
        val position = positionMillis.coerceAtLeast(0)
        val duration = durationMillis.coerceAtLeast(0)
        return PhoneRecentEntry(
            url = safeUrl,
            title = safeTitle,
            subtitle = subtitle?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_TEXT),
            posterUrl = NowPlayingArt.sanitize(posterUrl),
            landscapeUrl = NowPlayingArt.sanitize(landscapeUrl),
            overview = overview?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_OVERVIEW),
            // A position past the end is a phone-side rounding artefact, not a request to draw a
            // progress bar wider than its track.
            positionMillis = if (duration > 0) position.coerceAtMost(duration) else position,
            durationMillis = duration,
            // A future timestamp would pin the row to the head of the rail forever, and the two
            // clocks are not synchronised. Anything ahead of the box's own clock becomes "now".
            timestamp = if (timestamp in 1..now) timestamp else now,
        )
    }

    const val MAX_ENTRIES = 20
    const val MAX_TEXT = 200
    const val MAX_OVERVIEW = 1_000
    const val MAX_URL = 4_096
}
