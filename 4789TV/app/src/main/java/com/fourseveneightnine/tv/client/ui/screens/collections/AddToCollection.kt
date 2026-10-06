@file:Suppress("OPT_IN_USAGE")

package com.fourseveneightnine.tv.client.ui.screens.collections

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.ui.components.EmptyState
import com.fourseveneightnine.tv.client.ui.components.SidePanel
import com.fourseveneightnine.tv.client.ui.components.accessibilityLabel
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvShape
import com.fourseveneightnine.tv.client.ui.theme.TvSpace
import com.fourseveneightnine.tv.client.ui.theme.TvType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A title as the library stores it. Enough to add it to a collection from any screen. */
internal data class CollectionCandidate(
    val canonicalId: String,
    val mediaType: String,
    val title: String,
    val posterUrl: String?,
)

/**
 * The Add-to-collection checklist, spec §8. Opened from Detail's action row and from long-OK on
 * any poster.
 *
 * There is no Done button. Every OK writes, and the box filling plus the count changing is the
 * receipt — a toast per press would cover the list it is reporting on (spec §8.6.1).
 */
@Composable
internal fun AddToCollectionSheet(candidate: CollectionCandidate, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val graph = remember(context) { context.clientGraph }
    val scope = rememberCoroutineScope()

    val summaries by graph.activeLibrary.collections().collectAsState(initial = emptyList())
    val containing by graph.activeLibrary
        .collectionsContaining(candidate.canonicalId)
        .collectAsState(initial = emptySet())

    var naming by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    val rows = remember(summaries, containing) { Checklist.rows(summaries, containing) }
    val firstRowFocus = remember { FocusRequester() }
    val newRowFocus = remember { FocusRequester() }

    SidePanel(header = "Add to collection", onClose = onDismiss) {
        // The footer sits at y 1000..1026 and says the panel has no Done button (spec §8.2, §8.6.6).
        // The list used to take every pixel, so with ten collections the footer was laid out past
        // the bottom edge of the panel and never drawn.
        Column(Modifier.fillMaxSize().padding(bottom = 54.dp)) {
            Text(
                clip(candidate.title, 34),
                style = TvType.Body,
                color = TvColor.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(TvSpace.M))
            if (rows.isEmpty()) {
                EmptyState(
                    headline = "You have no collections yet",
                    line = "Make one and this title goes straight into it.",
                )
                Spacer(Modifier.height(TvSpace.M))
            }
            val focusIndex = remember(rows) { Checklist.initialIndex(rows) }
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rows.forEachIndexed { index, row ->
                    ChecklistRowView(
                        row = row,
                        modifier = if (index == focusIndex) {
                            Modifier.focusRequester(firstRowFocus)
                        } else {
                            Modifier
                        },
                        onToggle = {
                            when (Checklist.action(row)) {
                                ChecklistAction.Add -> scope.launch {
                                    runCatching {
                                        graph.activeLibrary.addItem(
                                            collectionId = row.id,
                                            canonicalId = candidate.canonicalId,
                                            mediaType = candidate.mediaType,
                                            title = candidate.title,
                                            posterUrl = candidate.posterUrl,
                                        )
                                    }.onFailure { failure = "Couldn't save. Try again." }
                                }
                                ChecklistAction.Remove -> scope.launch {
                                    runCatching {
                                        graph.activeLibrary.removeItem(row.id, candidate.canonicalId)
                                    }.onFailure { failure = "Couldn't save. Try again." }
                                }
                                // A sourced folder takes no manual add (spec §8.6.4).
                                ChecklistAction.Ignored -> Unit
                            }
                        },
                    )
                }
                NewCollectionRow(
                    modifier = if (rows.isEmpty()) Modifier.focusRequester(newRowFocus) else Modifier,
                    onClick = { naming = true },
                )
            }
            Spacer(Modifier.height(TvSpace.M))
            Text(
                failure ?: "Changes save as you press OK.",
                style = TvType.Meta,
                color = if (failure == null) TvColor.TextMuted else TvColor.Error,
                maxLines = 1,
            )
        }
    }

    if (naming) {
        KeyboardOverlay(
            reason = "Name this collection",
            initial = "",
            onCancel = { naming = false },
            onDone = { name ->
                naming = false
                scope.launch {
                    val id = graph.activeLibrary.create(name, Accents.DEFAULT)
                    graph.activeLibrary.addItem(
                        collectionId = id,
                        canonicalId = candidate.canonicalId,
                        mediaType = candidate.mediaType,
                        title = candidate.title,
                        posterUrl = candidate.posterUrl,
                    )
                }
            },
        )
    }

    BackHandler(enabled = !naming) { onDismiss() }

    // Initial focus: the first unchecked row, else the first row; with no rows at all, the one row
    // that exists (spec §8.3). Placed once, on entry, never again because data arrived — keyed on
    // the row list it fired twice, and the second one moved the ring out from under the viewer.
    LaunchedEffect(Unit) {
        // `collections()` starts empty, so give the database a moment to answer before deciding
        // the viewer has none. It is a local read; this is a bound, not a wait.
        withTimeoutOrNull(FIRST_ROWS_GRACE_MILLIS) {
            snapshotFlow { rows.isNotEmpty() }.first { it }
        }
        repeat(FOCUS_ATTEMPTS) {
            val target = if (rows.isEmpty()) newRowFocus else firstRowFocus
            if (runCatching { target.requestFocus() }.isSuccess) return@LaunchedEffect
            withFrameNanos { }
        }
    }
}

private const val FIRST_ROWS_GRACE_MILLIS = 600L
private const val FOCUS_ATTEMPTS = 10

@Composable
private fun ChecklistRowView(row: ChecklistRow, modifier: Modifier, onToggle: () -> Unit) {
    LongPressFocusable(
        onClick = onToggle,
        modifier = modifier.moveModeDim(row.sourced),
        enabled = !row.sourced,
        accessibleLabel = accessibilityLabel(row.name, row.countLabel),
        accessibleRole = Role.Checkbox,
        checked = row.checked,
        clickLabel = if (row.checked) "Remove from collection" else "Add to collection",
        cornerRadius = 10.dp,
        focusScale = 1f,
    ) { focused ->
        Row(
            modifier = Modifier
                .width(560.dp)
                .height(72.dp)
                .clip(TvShape.Control)
                .background(if (focused) TvColor.Elevated2 else Color.Transparent)
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CheckBox(row.checked)
            Spacer(Modifier.width(20.dp))
            AccentDot(row.accent)
            Spacer(Modifier.width(12.dp))
            Text(
                clip(row.name, 22),
                style = TvType.ControlLabel,
                color = TvColor.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(row.countLabel, style = TvType.data(20), color = TvColor.TextMuted, maxLines = 1)
        }
    }
}

@Composable
private fun CheckBox(checked: Boolean) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(TvShape.Chip)
            .background(if (checked) TvColor.Focus else Color.Transparent)
            .border(
                if (checked) 0.dp else 2.dp,
                Color.White.copy(alpha = 0.24f),
                TvShape.Chip,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Text("✓", style = TvType.CardTitle, color = TvColor.Canvas, maxLines = 1)
    }
}

@Composable
private fun NewCollectionRow(modifier: Modifier, onClick: () -> Unit) {
    LongPressFocusable(
        onClick = onClick,
        modifier = modifier,
        accessibleLabel = "New collection",
        cornerRadius = 10.dp,
        focusScale = 1f,
    ) { focused ->
        Row(
            modifier = Modifier
                .width(560.dp)
                .height(72.dp)
                .clip(TvShape.Control)
                .background(if (focused) TvColor.Elevated2 else Color.Transparent)
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                Text("+", style = TvType.Key, color = TvColor.TextSecondary, maxLines = 1)
            }
            Spacer(Modifier.width(32.dp))
            Text(
                "New collection",
                style = TvType.ControlLabel,
                color = TvColor.TextPrimary,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
