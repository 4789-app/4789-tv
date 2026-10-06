package com.fourseveneightnine.tv.client.data.streams

import com.fourseveneightnine.tv.client.data.addons.Addon
import com.fourseveneightnine.tv.client.data.addons.AddonEndpoint
import com.fourseveneightnine.tv.client.data.addons.AddonHealthStore
import com.fourseveneightnine.tv.client.data.addons.AddonRegistry
import com.fourseveneightnine.tv.client.data.addons.StremioClient
import com.fourseveneightnine.tv.client.data.addons.healthLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the Streams screen knows right now (`TV_DESIGN_SPEC.md` §10.7).
 *
 * [rows] only ever grows, and never reorders. The count chip reads straight off the other four
 * fields: "Searching · 0 of 4 add-ons", "7 sources · 2 add-ons pending", "12 sources · 1 add-on
 * failed".
 */
data class StreamSearchState(
    val rows: List<StreamRow> = emptyList(),
    val attempted: Int = 0,
    val pending: Int = 0,
    val failed: Int = 0,
    val done: Boolean = false,
)

/**
 * Fans out to every stream add-on and publishes rows as they land.
 *
 * Three rules, all of them paid for once already:
 *
 *  - **Per-add-on timeout AND a set deadline.** One dead endpoint used to cost the viewer the
 *    whole per-call timeout, because every endpoint was joined at once: thirty results sat
 *    finished and invisible while the slowest ran out its clock. Showing eleven sources now beats
 *    showing fourteen in fifteen seconds.
 *  - **Isolation.** One add-on throwing never cancels its siblings, and never fails the flow.
 *  - **Insertion order is final.** A row that has been published never moves in this list. The
 *    ranker re-sorts a copy; the screen decides when to apply it (§10.7), so a row cannot slide
 *    out from under the D-pad.
 */
class StreamSearch(
    private val registry: AddonRegistry,
    private val client: StremioClient,
    private val health: AddonHealthStore? = null,
    private val perAddonTimeoutMillis: Long = PER_ADDON_TIMEOUT_MILLIS,
    private val setDeadlineMillis: Long = SET_DEADLINE_MILLIS,
) {

    fun search(
        type: String,
        id: String,
        season: Int? = null,
        episode: Int? = null,
    ): Flow<StreamSearchState> = channelFlow {
        val addons = registry.streamAddons()
        val streamID = AddonEndpoint.episodeIdentifier(id, season, episode)
        if (addons.isEmpty()) {
            send(StreamSearchState(done = true))
            return@channelFlow
        }

        send(StreamSearchState(attempted = addons.size, pending = addons.size))

        // One channel, one collector. Every published state is built on this coroutine, which is
        // what makes "insertion order is final" true rather than hopeful.
        val answers = Channel<Answer>(capacity = Channel.UNLIMITED)
        addons.forEach { addon ->
            launch {
                // UNLIMITED, so this never suspends and never races the set deadline.
                answers.trySend(fetch(addon, type, streamID))
            }
        }

        val rows = mutableListOf<StreamRow>()
        val seen = mutableSetOf<String>()
        var pending = addons.size
        var failed = 0

        // Creating the work and awaiting it share this one scope. A deadline started in its own
        // scope can only fire after the work it was timing has already finished.
        withTimeoutOrNull(setDeadlineMillis) {
            repeat(addons.size) {
                val answer = answers.receive()
                pending -= 1
                if (answer.failed) failed += 1
                answer.rows.forEach { row ->
                    if (seen.add(row.dedupeKey())) rows += row
                }
                send(
                    StreamSearchState(
                        rows = rows.toList(),
                        attempted = addons.size,
                        pending = pending,
                        failed = failed,
                        done = pending == 0,
                    ),
                )
            }
        }

        // The deadline fired with add-ons still out. Whatever answered is what the viewer gets.
        if (pending > 0) {
            send(
                StreamSearchState(
                    rows = rows.toList(),
                    attempted = addons.size,
                    pending = 0,
                    failed = failed + pending,
                    done = true,
                ),
            )
        }
        // Deliberately not closed. A straggler still inside `fetch` would otherwise send into a
        // closed channel and take the whole flow down with it; leaving the scope cancels it
        // cleanly instead.
    }

    private data class Answer(val rows: List<StreamRow>, val failed: Boolean)

    private suspend fun fetch(addon: Addon, type: String, streamID: String): Answer = try {
        val rows = withTimeoutOrNull(perAddonTimeoutMillis) {
            client.streams(addon, type, streamID)
        }
        if (rows == null) {
            health?.markFail(addon.manifestURL, "timeout")
            Answer(emptyList(), failed = true)
        } else {
            health?.markOk(addon.manifestURL)
            Answer(rows.filter(StreamRow::playable), failed = false)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        // The label is a class name or a status code. An add-on URL can carry a credential, so the
        // URL itself never reaches health, a log or a crash report.
        health?.markFail(addon.manifestURL, failure.healthLabel())
        Answer(emptyList(), failed = true)
    }

    /** Two add-ons offering the same file is one row. A hash wins over a URL as the identity. */
    private fun StreamRow.dedupeKey(): String = infoHash?.lowercase() ?: url.orEmpty()

    companion object {
        /** §5.2: per add-on 8 s. */
        const val PER_ADDON_TIMEOUT_MILLIS: Long = 8_000L

        /** §5.2: the whole set 12 s. */
        const val SET_DEADLINE_MILLIS: Long = 12_000L
    }
}
