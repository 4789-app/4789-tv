package com.fourseveneightnine.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fourseveneightnine.contract.StreamEntry

/**
 * One remote source, drawn from the facts it actually stated.
 *
 * The chip strip prints parsed facts only. A source that never named its codec shows no codec chip
 * rather than a guess. The star marks the single recommended row and does not move when the sort
 * order changes.
 *
 * "Keep offline" hands the whole entry up, not its link. The queue stores a recipe that can find
 * this same file again later, because the link itself expires.
 */
@Composable
internal fun SourceRankingRow(
    ranked: RankedStream,
    canCast: Boolean,
    queueStatus: String?,
    onPlay: (String) -> Unit,
    onKeepOffline: (StreamEntry) -> Unit,
    onCast: (String) -> Unit,
) {
    val stream = ranked.stream
    val label = listOfNotNull(stream.name, stream.title, stream.filename)
        .firstOrNull(String::isNotBlank)
        ?: "Remote source"
    val heading = if (ranked.isRecommended) "★ $label" else label
    val description = if (ranked.isRecommended) "Recommended. Play $label" else "Play $label"
    Card(
        onClick = { stream.url?.let(onPlay) },
        modifier = Modifier
            .fillMaxWidth()
            .testTag("source-row")
            .semantics { contentDescription = description },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(heading, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            SourceFactChips(StreamRankingPolicy.chips(ranked.facts))
            StreamRankingPolicy.sizeLine(ranked.facts)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            stream.filename?.takeIf(String::isNotBlank)?.takeIf { it != label }?.let {
                Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = { onKeepOffline(stream) },
                enabled = queueStatus == null,
                modifier = Modifier.testTag("keep-offline"),
            ) { Text(queueStatus ?: "Keep offline") }
            if (canCast) {
                Button(onClick = { stream.url?.let(onCast) }) { Text("Play on 4789 TV") }
            }
        }
    }
}

@Composable
private fun SourceFactChips(chips: List<String>) {
    if (chips.isEmpty()) return
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(end = 6.dp),
    ) {
        items(chips) { chip ->
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    chip,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}
