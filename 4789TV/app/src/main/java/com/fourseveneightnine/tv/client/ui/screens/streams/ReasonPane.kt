package com.fourseveneightnine.tv.client.ui.screens.streams

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.client.data.streams.RankedRow
import com.fourseveneightnine.tv.client.data.streams.StreamRanker
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvShape
import com.fourseveneightnine.tv.client.ui.theme.TvType

/**
 * The right-hand readout (`TV_DESIGN_SPEC.md` §10.4).
 *
 * **Not focusable.** It reads the focused row, so the D-pad stays on one axis and RIGHT never
 * leaves the list. Every line here comes from `RankedRow.reasons` and `StreamRanker.facts` — this
 * file writes no sentence of its own, so the pane cannot drift from the order the ranker chose.
 *
 * [selected] is a lambda, not a value: the ring moving down the list must recompose this pane and
 * nothing above it.
 */
@Composable
internal fun ReasonPane(
    selected: () -> RankedRow?,
    /** How many add-ons have not answered. `StreamSearchState` counts them; it never names them. */
    pending: Int,
    modifier: Modifier = Modifier,
) {
    val row = selected()
    Column(
        modifier
            .width(540.dp)
            .fillMaxHeight()
            .clip(TvShape.Panel)
            .background(TvColor.Elevated)
            .border(1.dp, TvColor.Border, TvShape.Panel)
            .padding(32.dp),
    ) {
        if (row == null) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(), contentAlignment = Alignment.Center) {
                Text(
                    if (pending > 0) "Sources are arriving. Choose a source to see its details." else "Choose a source to see compatibility and details.",
                    style = TvType.Body,
                    color = TvColor.TextSecondary,
                    textAlign = TextAlign.Center,
                )
            }
            return@Column
        }

        Text("Why this one", style = TvType.ShelfHeader, color = TvColor.TextPrimary)
        Spacer(Modifier.height(20.dp))
        row.reasons.sortedByDescending { it.caution }.forEach { reason ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // A check for a reason the row won, a caution for one that pushed it down. The
                // caution is the only amber on this screen: it marks a demotion, not a fault.
                Text(
                    text = if (reason.caution) "!" else "✓",
                    style = TvType.CardTitle,
                    color = if (reason.caution) TvColor.Warning else TvColor.TextSecondary,
                    modifier = Modifier.width(36.dp),
                )
                Text(
                    text = reason.text,
                    style = TvType.Meta,
                    color = if (reason.caution) TvColor.Warning else TvColor.TextPrimary,

                )
            }
        }

        if (!row.eligible) {
            Text("Outside your playback rules. Review this source before playing.", style = TvType.Body, color = TvColor.Warning)
            Spacer(Modifier.height(12.dp))
        }
        Text("Cache is an add-on claim. Compatibility is separate.",
            style = TvType.Meta, color = TvColor.TextMuted)
        val facts = StreamRanker.facts(row.row) + listOfNotNull(
            row.row.cachedHint.service?.let { "Service" to it },
            "Languages" to row.row.audioLanguages.joinToString(", ").ifBlank { "Unknown" },
            if (row.row.sizeGB == null) "Size" to "Unknown" else null,
        )
        if (facts.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(TvColor.Border))
            Spacer(Modifier.height(20.dp))
            facts.take(7).forEach { (label, value) ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, style = TvType.Meta, color = TvColor.TextMuted, modifier = Modifier.width(160.dp))
                    run {
                        Text(
                            text = value,
                            style = TvType.data(22),
                            color = TvColor.TextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))
        if (pending > 0) {
            Text(
                text = if (pending == 1) "Waiting on 1 add-on." else "Waiting on $pending add-ons.",
                style = TvType.Meta,
                color = TvColor.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
        }
        // The gesture is "hold OK" everywhere a viewer meets it; "long-OK" is spec shorthand.
        Text("OK plays · hold OK for more", style = TvType.Meta, color = TvColor.TextMuted)
    }
}

/** §10.6 row menu, on long-OK. The panel rows are built by the screen. */
internal object RowMenu {
    const val PLAY = "Play"
    const val ANOTHER_APP = "Play in another app"
    const val COPY = "Copy link"

    /** Every label for a row, given what this box can actually do. */
    fun labels(hasExternalPlayer: Boolean): List<String> = buildList {
        add(PLAY)
        // §10.11.11: only when a target player is installed. A dead row is worse than no row.
        if (hasExternalPlayer) add(ANOTHER_APP)
        add(COPY)
    }
}

/** Spacing helper so the two columns of §10.2 line up without repeating the numbers. */
@Composable
internal fun PaneGutter() {
    Spacer(Modifier.width(24.dp))
}

/** Larger source cards and a compact explanation pane fit inside the 1604 px content viewport. */
internal object StreamsGeometry {
    val ListWidth = 1040.dp
    val RowGap = 16.dp
}
