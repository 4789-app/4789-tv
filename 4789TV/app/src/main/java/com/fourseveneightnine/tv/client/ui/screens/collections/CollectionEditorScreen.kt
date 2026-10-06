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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.client.ClientGraph
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.data.library.CollectionDetail
import com.fourseveneightnine.tv.client.data.library.CollectionKind
import com.fourseveneightnine.tv.client.ui.components.EmptyState
import com.fourseveneightnine.tv.client.ui.components.SidePanel
import com.fourseveneightnine.tv.client.ui.components.SidePanelRow
import com.fourseveneightnine.tv.client.ui.components.TvDialog
import com.fourseveneightnine.tv.client.ui.components.accessibilityLabel
import com.fourseveneightnine.tv.client.ui.nav.ClientNav
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvShape
import com.fourseveneightnine.tv.client.ui.theme.TvSpace
import com.fourseveneightnine.tv.client.ui.theme.TvType
import kotlinx.coroutines.launch

/**
 * The collection editor, spec §7.
 *
 * A left list of six rows and a right pane that reads out whatever the list is pointing at. Every
 * change saves the moment it is made: there is no Save button and no unsaved state to lose.
 */
@Composable
internal fun CollectionEditorScreen(id: String?, nav: ClientNav) {
    val context = LocalContext.current
    val graph = remember(context) { context.clientGraph }
    val scope = rememberCoroutineScope()

    var createdId by remember { mutableStateOf<Long?>(null) }
    val collectionId = remember(id, createdId) { createdId ?: id?.toLongOrNull() }

    // A new collection is named first. Cancelling the keyboard leaves without writing anything.
    if (collectionId == null) {
        KeyboardOverlay(
            reason = "Name this collection",
            initial = "",
            onCancel = nav::back,
            onDone = { name ->
                scope.launch { createdId = graph.activeLibrary.create(name, Accents.DEFAULT) }
            },
        )
        return
    }

    val detail by graph.activeLibrary.collection(collectionId).collectAsState(initial = null)
    val current = detail ?: run {
        MissingCollectionEditor(nav)
        return
    }

    Editor(
        graph = graph,
        detail = current,
        nav = nav,
        scope = scope,
        onReplaced = { createdId = it },
    )
}

/** Which left row the ring is on. The right pane is a readout of exactly this. */
private enum class EditorRow(val label: String) {
    Rename("Rename"),
    Accent("Accent"),
    Pin("Pin to Home"),
    Source("Add source"),
    Reorder("Reorder items"),
    Delete("Delete"),
}

private sealed interface EditorOverlay {
    data object Rename : EditorOverlay
    data object Delete : EditorOverlay
    data object CatalogPicker : EditorOverlay
    data object LetterboxdPicker : EditorOverlay
    data class ConfirmSource(val kind: CollectionKind, val ref: String, val name: String) : EditorOverlay
}

@Composable
private fun Editor(
    graph: ClientGraph,
    detail: CollectionDetail,
    nav: ClientNav,
    scope: kotlinx.coroutines.CoroutineScope,
    onReplaced: (Long) -> Unit,
) {
    val system = detail.kind == CollectionKind.SYSTEM
    val rows = remember(system) {
        // Renaming, sourcing and deleting a built-in folder are not the viewer's to do, so those
        // rows are missing rather than present and dead (spec §7.8.8).
        if (system) listOf(EditorRow.Pin) else EditorRow.entries.toList()
    }

    var focusedRow by remember(detail.id) { mutableStateOf(rows.first()) }
    var overlay by remember(detail.id) { mutableStateOf<EditorOverlay?>(null) }
    val firstRowFocus = remember { FocusRequester() }
    // Picking an accent sends the ring back to the Accent row, spec §7.4. It used to send it to
    // `firstRowFocus`, which is row 0 — Rename.
    val accentRowFocus = remember { FocusRequester() }
    val swatchFocus = remember { FocusRequester() }
    val sourceKindFocus = remember { FocusRequester() }
    val accent = remember(detail.accent) { accentColor(detail.accent) }

    DrillInFrame {
        Column(Modifier.fillMaxSize()) {
            Text(
                if (system) "Collection settings" else "Edit collection",
                style = TvType.ScreenTitle,
                color = TvColor.TextPrimary,
                maxLines = 1,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                clip(detail.name, 30),
                style = TvType.Body,
                color = TvColor.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(TvSpace.L))
            Row {
                Column(
                    modifier = Modifier.width(520.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    rows.forEachIndexed { index, row ->
                        EditorListRow(
                            row = row,
                            value = valueFor(row, detail),
                            accent = if (row == EditorRow.Accent) accent else null,
                            enabled = row != EditorRow.Reorder || detail.items.isNotEmpty(),
                            modifier = Modifier
                                .then(if (index == 0) Modifier.focusRequester(firstRowFocus) else Modifier)
                                .then(
                                    if (row == EditorRow.Accent) {
                                        Modifier.focusRequester(accentRowFocus)
                                    } else {
                                        Modifier
                                    },
                                )
                                .focusProperties {
                                    // RIGHT enters the pane only for the two rows that hold one,
                                    // and only once the pane that holds the destination is drawn.
                                    // The pane follows `focusedRow`, so naming a requester that
                                    // the pane has not composed yet throws out of Compose's own
                                    // focus search, where no `runCatching` of ours can reach it.
                                    when (row) {
                                        EditorRow.Accent ->
                                            if (focusedRow == EditorRow.Accent) right = swatchFocus
                                        EditorRow.Source ->
                                            if (focusedRow == EditorRow.Source) right = sourceKindFocus
                                        else -> Unit
                                    }
                                },
                            onFocused = { focusedRow = row },
                            onClick = {
                                when (row) {
                                    EditorRow.Rename -> overlay = EditorOverlay.Rename
                                    EditorRow.Accent -> runCatching { swatchFocus.requestFocus() }
                                    EditorRow.Pin -> scope.launch {
                                        graph.activeLibrary.setPinned(detail.id, !detail.pinnedHome)
                                        nav.toast(if (detail.pinnedHome) "Unpinned." else "Pinned to Home.")
                                    }
                                    EditorRow.Source -> runCatching { sourceKindFocus.requestFocus() }
                                    EditorRow.Reorder -> if (detail.items.isNotEmpty()) {
                                        PendingMoveMode.request(detail.id)
                                        nav.openCollection(detail.id.toString())
                                    }
                                    EditorRow.Delete -> overlay = EditorOverlay.Delete
                                }
                            },
                        )
                    }
                }
                Spacer(Modifier.width(60.dp))
                RightPane(
                    row = focusedRow,
                    detail = detail,
                    swatchFocus = swatchFocus,
                    sourceKindFocus = sourceKindFocus,
                    onAccent = { hex ->
                        scope.launch {
                            graph.activeLibrary.setAccent(detail.id, hex)
                            nav.toast("Accent changed.")
                        }
                        runCatching { accentRowFocus.requestFocus() }
                    },
                    onPickKind = { kind ->
                        when (kind) {
                            CollectionKind.MANUAL -> overlay = applySource(
                                detail, CollectionKind.MANUAL, null, "Manual only",
                                scope, graph, nav, onReplaced,
                            )
                            CollectionKind.CATALOG -> overlay = EditorOverlay.CatalogPicker
                            CollectionKind.LETTERBOXD -> overlay = EditorOverlay.LetterboxdPicker
                            else -> Unit
                        }
                    },
                )
            }
        }
    }

    when (val open = overlay) {
        EditorOverlay.Rename -> KeyboardOverlay(
            reason = "Rename this collection",
            initial = detail.name,
            onCancel = { overlay = null },
            onDone = { name ->
                overlay = null
                scope.launch {
                    graph.activeLibrary.rename(detail.id, name)
                    nav.toast("Name saved.")
                }
            },
        )
        EditorOverlay.Delete -> TvDialog(
            title = "Delete ${clip(detail.name, 24)}?",
            body = "This removes the collection. The ${detail.items.size} " +
                "${plural(detail.items.size, "title")} stay where else they are saved.",
            safeLabel = "Cancel",
            destructiveLabel = "Delete",
            onSafe = { overlay = null },
            onDestructive = {
                overlay = null
                scope.launch {
                    graph.activeLibrary.delete(detail.id)
                    nav.back()
                }
            },
        )
        EditorOverlay.CatalogPicker -> CatalogPicker(
            graph = graph,
            onClose = { overlay = null },
            onPick = { ref, name ->
                overlay = applySource(
                    detail, CollectionKind.CATALOG, ref, name, scope, graph, nav, onReplaced,
                )
            },
        )
        EditorOverlay.LetterboxdPicker -> LetterboxdPicker(
            graph = graph,
            onClose = { overlay = null },
            onPick = { ref, name ->
                overlay = applySource(
                    detail, CollectionKind.LETTERBOXD, ref, name, scope, graph, nav, onReplaced,
                )
            },
        )
        is EditorOverlay.ConfirmSource -> TvDialog(
            title = "Replace the ${detail.items.size} " +
                "${plural(detail.items.size, "title")} with this list?",
            body = "${open.name} takes over this collection. What is in it now is removed.",
            safeLabel = "Cancel",
            destructiveLabel = "Replace",
            onSafe = { overlay = null },
            onDestructive = {
                overlay = null
                scope.launch { writeSource(graph, detail, open.kind, open.ref, nav, onReplaced) }
            },
        )
        null -> Unit
    }

    BackHandler(enabled = overlay == null) { nav.back() }

    LaunchedEffect(detail.id) { runCatching { firstRowFocus.requestFocus() } }
}

/**
 * Switching a folder that already holds titles asks first, because the list wins and the titles go
 * (spec §7.8.6). An empty folder has nothing to lose, so it just changes.
 */
private fun applySource(
    detail: CollectionDetail,
    kind: CollectionKind,
    ref: String?,
    name: String,
    scope: kotlinx.coroutines.CoroutineScope,
    graph: ClientGraph,
    nav: ClientNav,
    onReplaced: (Long) -> Unit,
): EditorOverlay? {
    if (kind == detail.kind && ref == detail.sourceRef) return null
    if (detail.items.isNotEmpty()) return EditorOverlay.ConfirmSource(kind, ref.orEmpty(), name)
    scope.launch { writeSource(graph, detail, kind, ref, nav, onReplaced) }
    return null
}

/**
 * Writes a folder's source by making the row again with the new kind.
 *
 * `LibraryRepository` has `create(name, accent, kind, sourceRef)` and no way to change a kind
 * afterwards, so the row is replaced rather than edited. Name, accent and the pin carry over; the
 * folder lands at the end of the grid order, which is the one visible cost. A `setSource(id, kind,
 * sourceRef)` on the repository would remove it.
 */
private suspend fun writeSource(
    graph: ClientGraph,
    detail: CollectionDetail,
    kind: CollectionKind,
    ref: String?,
    nav: ClientNav,
    onReplaced: (Long) -> Unit,
) {
    val replacement = graph.activeLibrary.create(
        name = detail.name,
        accent = detail.accent,
        kind = kind,
        sourceRef = ref?.takeIf { it.isNotBlank() },
    )
    if (detail.pinnedHome) graph.activeLibrary.setPinned(replacement, true)
    graph.activeLibrary.delete(detail.id)
    onReplaced(replacement)
    nav.toast("Source saved.")
}

private fun valueFor(row: EditorRow, detail: CollectionDetail): String = when (row) {
    EditorRow.Rename -> clip(detail.name, 18)
    EditorRow.Accent -> ""
    EditorRow.Pin -> if (detail.pinnedHome) "On" else "Off"
    EditorRow.Source -> when (detail.kind) {
        CollectionKind.CATALOG -> "Add-on catalog"
        CollectionKind.LETTERBOXD -> "Letterboxd"
        CollectionKind.MDBLIST -> "MDBList"
        else -> "Manual"
    }
    EditorRow.Reorder -> if (detail.items.isEmpty()) {
        "No titles yet"
    } else {
        "${detail.items.size} ${plural(detail.items.size, "title")}"
    }
    EditorRow.Delete -> ""
}

@Composable
private fun EditorListRow(
    row: EditorRow,
    value: String,
    accent: Color?,
    enabled: Boolean,
    modifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    LongPressFocusable(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        accessibleLabel = accessibilityLabel(row.label, value),
        cornerRadius = 10.dp,
        focusScale = 1f,
        // Written in the frame the ring moves, not the frame after. A `LaunchedEffect` dispatched
        // a frame late, so DOWN then RIGHT inside one frame — easy at a 60 ms repeat — read the
        // row the ring had just left and sent RIGHT at a pane that was not drawn.
        onFocusedChange = { if (it) onFocused() },
    ) { focused ->
        Row(
            modifier = Modifier
                .width(520.dp)
                .height(72.dp)
                .clip(TvShape.Control)
                .background(if (focused) TvColor.Elevated2 else TvColor.Elevated)
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                row.label,
                style = TvType.ControlLabel,
                color = when {
                    row == EditorRow.Delete -> TvColor.Error
                    !enabled -> TvColor.TextMuted
                    else -> TvColor.TextPrimary
                },
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (accent != null) {
                Box(Modifier.size(24.dp).clip(CircleShape).background(accent))
            } else {
                Text(value, style = TvType.Body, color = TvColor.TextSecondary, maxLines = 1)
            }
        }
    }
}

// ------------------------------------------------------------------ the right pane

@Composable
private fun RightPane(
    row: EditorRow,
    detail: CollectionDetail,
    swatchFocus: FocusRequester,
    sourceKindFocus: FocusRequester,
    onAccent: (String) -> Unit,
    onPickKind: (CollectionKind) -> Unit,
) {
    Box(
        modifier = Modifier
            .width(1024.dp)
            .height(866.dp)
            .clip(TvShape.Panel)
            .background(TvColor.Elevated)
            .padding(48.dp),
    ) {
        when (row) {
            EditorRow.Rename -> PaneText(detail.name, "Press OK to type a new name.")
            EditorRow.Accent -> AccentPane(detail.accent, swatchFocus, onAccent)
            EditorRow.Pin -> PaneText(
                if (detail.pinnedHome) "Pinned" else "Not pinned",
                "Pinned collections show as row 2 on Home.",
            )
            EditorRow.Source -> SourcePane(detail, sourceKindFocus, onPickKind)
            EditorRow.Reorder -> ReorderPane(detail)
            EditorRow.Delete -> PaneText(
                "Delete",
                "Deleting removes the collection. The titles stay in your other collections.",
            )
        }
    }
}

@Composable
private fun PaneText(headline: String, line: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            clip(headline, 34),
            style = TvType.ScreenTitle,
            color = TvColor.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(TvSpace.S))
        Text(line, style = TvType.Body, color = TvColor.TextSecondary, maxLines = 2)
    }
}

/** Eight 96 px circles, two rows of four. The hex under each is a nod to the owner (§7.8.7). */
@Composable
private fun AccentPane(current: String, swatchFocus: FocusRequester, onPick: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Colours come from the palette already parsed, so no hex is read at draw time.
        val swatches = remember { Accents.HEXES.zip(Accents.PALETTE).chunked(4) }
        swatches.forEachIndexed { rowIndex, chunk ->
            Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                chunk.forEachIndexed { index, (hex, colour) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LongPressFocusable(
                            onClick = { onPick(hex) },
                            accessibleLabel = "Accent colour $hex",
                            accessibleRole = Role.RadioButton,
                            selected = hex.equals(current, ignoreCase = true),
                            modifier = if (rowIndex == 0 && index == 0) {
                                Modifier.focusRequester(swatchFocus)
                            } else {
                                Modifier
                            },
                            cornerRadius = 48.dp,
                        ) { _ ->
                            Box(
                                modifier = Modifier
                                    .size(96.dp)
                                    .clip(CircleShape)
                                    .background(colour)
                                    .border(
                                        if (hex.equals(current, ignoreCase = true)) 4.dp else 0.dp,
                                        TvColor.TextPrimary,
                                        CircleShape,
                                    ),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(hex, style = TvType.data(20), color = TvColor.TextMuted, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

/**
 * Three kinds, not four. Nothing on this box fetches an MDBList list, so that row is missing
 * rather than present and dead.
 */
@Composable
private fun SourcePane(
    detail: CollectionDetail,
    sourceKindFocus: FocusRequester,
    onPick: (CollectionKind) -> Unit,
) {
    val kinds = listOf(
        Triple(
            CollectionKind.CATALOG,
            "Add-on list",
            "The whole catalog, not one film. It refreshes with the add-on.",
        ),
        Triple(
            CollectionKind.LETTERBOXD,
            "Letterboxd list",
            "Follows one of your lists. Refreshes with the catalog snapshot.",
        ),
        Triple(CollectionKind.MANUAL, "Films you pick", "Only the titles you add yourself."),
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        kinds.forEachIndexed { index, (kind, label, line) ->
            LongPressFocusable(
                onClick = { onPick(kind) },
                modifier = if (index == 0) Modifier.focusRequester(sourceKindFocus) else Modifier,
                accessibleLabel = accessibilityLabel(label, line),
                accessibleRole = Role.RadioButton,
                selected = detail.kind == kind,
                cornerRadius = 10.dp,
                focusScale = 1f,
            ) { focused ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(TvShape.Control)
                        .background(if (focused) TvColor.Elevated2 else Color.Transparent)
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            label,
                            style = TvType.ControlLabel,
                            color = TvColor.TextPrimary,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        if (detail.kind == kind) {
                            Text("✓", style = TvType.ControlLabel, color = TvColor.Focus)
                        }
                    }
                    Text(line, style = TvType.Meta, color = TvColor.TextSecondary, maxLines = 1)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Text(
            "MDBList is not on this box yet.",
            style = TvType.Meta,
            color = TvColor.TextMuted,
            maxLines = 1,
        )
    }
}

@Composable
private fun ReorderPane(detail: CollectionDetail) {
    Column(Modifier.fillMaxSize()) {
        Text(
            if (detail.items.isEmpty()) "No titles yet" else "Press OK to start move mode.",
            style = TvType.Body,
            color = TvColor.TextSecondary,
            maxLines = 1,
        )
        Spacer(Modifier.height(TvSpace.M))
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            detail.items.take(18).chunked(6).forEach { chunk ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    chunk.forEach { item ->
                        Box(
                            modifier = Modifier
                                .size(120.dp, 180.dp)
                                .clip(TvShape.Tile)
                                .background(TvColor.PosterPlaceholder),
                        ) {
                            Poster(item.posterUrl, Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ source pickers

@Composable
private fun CatalogPicker(graph: ClientGraph, onClose: () -> Unit, onPick: (String, String) -> Unit) {
    val focus = remember { FocusRequester() }
    val catalogs = remember { graph.services.value?.registry?.catalogs().orEmpty() }
    SidePanel(header = "Add-on catalog", onClose = onClose) {
        if (catalogs.isEmpty()) {
            EmptyState(
                headline = "No add-on catalogs",
                line = "Add an add-on on the phone, then sync this box.",
                actionLabel = "Close",
                onAction = onClose,
                actionModifier = Modifier.focusRequester(focus),
            )
        } else {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(TvSpace.XS),
            ) {
                catalogs.forEachIndexed { index, (addon, catalog) ->
                    SidePanelRow(
                        label = clip("${addon.displayName} · ${catalog.name}", 30),
                        selected = false,
                        onClick = {
                            onPick(
                                SourceRef.catalog(addon.key, catalog.type, catalog.id),
                                "${addon.displayName} ${catalog.name}",
                            )
                        },
                        modifier = if (index == 0) Modifier.focusRequester(focus) else Modifier,
                    )
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BackHandler(enabled = true) { onClose() }
}

@Composable
private fun LetterboxdPicker(graph: ClientGraph, onClose: () -> Unit, onPick: (String, String) -> Unit) {
    val focus = remember { FocusRequester() }
    val shelves = remember { CollectionSources.letterboxdShelves(graph) }
    SidePanel(header = "Letterboxd list", onClose = onClose) {
        if (shelves.isEmpty()) {
            EmptyState(
                headline = "No Letterboxd lists found",
                line = "Add your username on the phone.",
                actionLabel = "Close",
                onAction = onClose,
                actionModifier = Modifier.focusRequester(focus),
            )
        } else {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(TvSpace.XS),
            ) {
                shelves.forEachIndexed { index, shelf ->
                    SidePanelRow(
                        label = clip(shelf.title, 30),
                        selected = false,
                        onClick = { onPick(shelf.id, shelf.title) },
                        modifier = if (index == 0) Modifier.focusRequester(focus) else Modifier,
                    )
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BackHandler(enabled = true) { onClose() }
}

@Composable
private fun MissingCollectionEditor(nav: ClientNav) {
    val focus = remember { FocusRequester() }
    DrillInFrame {
        Column {
            Text("Edit collection", style = TvType.ScreenTitle, color = TvColor.TextPrimary, maxLines = 1)
            Spacer(Modifier.height(TvSpace.L))
            EmptyState(
                headline = "That collection is gone",
                line = "It was deleted, or it never existed on this box.",
                actionLabel = "Back to collections",
                onAction = nav::back,
                actionModifier = Modifier.focusRequester(focus),
            )
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
