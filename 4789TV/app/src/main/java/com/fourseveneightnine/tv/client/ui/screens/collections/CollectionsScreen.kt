@file:Suppress("OPT_IN_USAGE")
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.fourseveneightnine.tv.client.ui.screens.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.client.ui.LocalShellState
import com.fourseveneightnine.tv.client.ui.components.EmptyState
import com.fourseveneightnine.tv.client.ui.components.Skeleton
import com.fourseveneightnine.tv.client.ui.components.accessibilityLabel
import com.fourseveneightnine.tv.client.ui.nav.ClientNav
import com.fourseveneightnine.tv.client.ui.screens.TopLevelScaffold
import com.fourseveneightnine.tv.client.data.catalog.Shelf
import com.fourseveneightnine.tv.client.ui.screens.discover.DiscoverPlan
import com.fourseveneightnine.tv.client.ui.screens.discover.PosterGrid
import com.fourseveneightnine.tv.client.ui.screens.home.HomeCard
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvGeom
import com.fourseveneightnine.tv.client.ui.theme.TvShape
import com.fourseveneightnine.tv.client.ui.theme.TvSpace
import com.fourseveneightnine.tv.client.ui.theme.TvType
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The Collections folder grid, spec §5.
 *
 * Four columns of 380 x 214 cards: the system folders first, then yours by sort index, then "New
 * collection", which is always last so its place never moves as the list grows.
 */
@Composable
internal fun CollectionsScreen(nav: ClientNav) {
    val context = LocalContext.current
    val graph = remember(context) { context.clientGraph }
    val shell = LocalShellState.current
    val scope = rememberCoroutineScope()

    val summaries by graph.activeLibrary.collections().collectAsState(initial = null)
    val shelves by graph.snapshots.shelves().collectAsState()
    val lists = remember(shelves) { SyncedLists.visible(shelves) }
    val services by graph.services.collectAsState()
    val hasDebridKey = remember(services) {
        val document = services?.document
        !document?.torboxAPIKey.isNullOrBlank() || !document?.realDebridAPIKey.isNullOrBlank()
    }

    // Safe to call every time; a second call writes nothing (API-B, library).
    LaunchedEffect(Unit) { runCatching { graph.activeLibrary.ensureSystemCollections() } }

    val gridFocus = remember { FocusRequester() }
    var moving by remember { mutableStateOf<MoveState?>(null) }
    var naming by remember { mutableStateOf(false) }
    var openedShelfId by rememberSaveable { mutableStateOf<String?>(null) }

    val cards = remember(summaries, hasDebridKey) {
        summaries?.let { FolderGrid.cards(it, hasDebridKey) }
    }

    // The rail borrows focus and must be able to give it back (spec §1.5). The target follows what
    // is drawn: on the empty screen the grid does not exist, and handing focus to a node that is
    // not there strands the ring in a rail that has just closed.
    DisposableEffect(Unit) {
        val restore = { runCatching { gridFocus.requestFocus() }; Unit }
        shell.restoreContentFocus = restore
        onDispose { if (shell.restoreContentFocus === restore) shell.restoreContentFocus = null }
    }

    TopLevelScaffold(title = "Collections", onOpenRail = nav::openRail) {
        // The scaffold ends its title at y 102 and rests 36 px, so its content starts at y 138.
        // Spec §5.2 puts the count line at y 112 and the grid band at y 160. The scaffold is shared
        // with five other screens and is not this group's to change, so the shift is taken here.
        Column(Modifier.fillMaxSize().offset(y = -SCAFFOLD_GAP_TRIM)) {
            when {
                cards == null -> LoadingGrid()
                else -> LoadedGrid(
                    cards = cards,
                    syncedLists = lists,
                    moving = moving,
                    gridFocus = gridFocus,
                    onOpen = { nav.openCollection(it.id.toString()) },
                    onOpenSynced = { openedShelfId = it.id },
                    onEdit = { nav.openCollectionEditor(it.id.toString()) },
                    onNew = { naming = true },
                    onStartMove = { card ->
                        if (!card.isSystem) {
                            moving = MoveState(card.id)
                            nav.toast("Move mode. Arrows move the folder. OK drops it.")
                        }
                    },
                    onMove = { direction ->
                        val state = moving ?: return@LoadedGrid
                        val delta = MoveMode.delta(direction, FolderGrid.COLUMNS)
                        scope.launch {
                            if (graph.activeLibrary.moveCollection(state.id, delta)) {
                                moving = state.copy(applied = state.applied + delta)
                            }
                        }
                    },
                    onCommit = {
                        moving = null
                        nav.toast("Order saved.")
                    },
                    onCancelMove = {
                        val state = moving ?: return@LoadedGrid
                        moving = null
                        val undo = MoveMode.undoDelta(state.applied)
                        if (undo != 0) scope.launch { graph.activeLibrary.moveCollection(state.id, undo) }
                    },
                )
            }
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
                    nav.openCollectionEditor(id.toString())
                }
            },
        )
    }

    lists.firstOrNull { it.id == openedShelfId }?.let { shelf ->
        SnapshotShelfGridOverlay(
            shelf = shelf,
            onDismiss = { openedShelfId = null },
            onOpen = { card ->
                nav.openDetail(card.type, card.id)
            },
        )
    }

    // Initial focus on a node that is always composed: the grid itself, never a lazy child
    // (plan §7.4 rule 1 and 2). Keyed on Unit and latched, so it places the ring once on entry and
    // never again. Keyed on the data it would fire a second time when the rows arrive, and a viewer
    // who opened the rail while the screen loaded would have the ring pulled out of the rail
    // (plan §7.4 rule 7).
    LaunchedEffect(Unit) {
        snapshotFlow { cards }.filterNotNull().first()
        // The node is composed on the frame after the rows land, so the first attempt can meet an
        // unattached requester. Retry for a few frames rather than leaving the screen ringless.
        repeat(FOCUS_ATTEMPTS) {
            if (runCatching { gridFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            withFrameNanos { }
        }
    }
}

/** Full, lazy six-column view of a synced phone/server list. */
@Composable
private fun SnapshotShelfGridOverlay(
    shelf: Shelf,
    onDismiss: () -> Unit,
    onOpen: (HomeCard) -> Unit,
) {
    val shell = LocalShellState.current
    val cards = remember(shelf.id, shelf.generation) {
        shelf.items.map { item ->
            HomeCard(
                id = item.canonicalId,
                type = item.mediaType,
                title = item.title,
                posterUrl = item.posterUrl,
                backdropUrl = item.backdropUrl,
                year = item.year,
                overview = item.overview,
            )
        }
    }
    val state = rememberLazyGridState()
    val first = remember { FocusRequester() }
    var focusedIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(shelf.id) { runCatching { first.requestFocus() } }
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    DisposableEffect(Unit) {
        shell.overlayVisible = true
        onDispose { shell.overlayVisible = false }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(TvColor.Canvas)
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup()
            .onPreviewKeyEvent { event ->
                event.type == KeyEventType.KeyDown &&
                    event.key == Key.DirectionLeft &&
                    focusedIndex % DiscoverPlan.COLUMNS == 0
            }
            .padding(start = TvGeom.ContentLeft, top = 54.dp, end = 96.dp),
    ) {
        Column {
            Text(
                shelf.title,
                style = TvType.ScreenTitle,
                color = TvColor.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                SyncedLists.countLabel(shelf),
                style = TvType.data(20),
                color = TvColor.TextMuted,
                maxLines = 1,
            )
        }
        if (cards.isEmpty()) {
            EmptyState(
                headline = "List synced",
                line = "Its name is available, but this transfer did not include titles. Refresh the phone catalog to fill it.",
                actionLabel = "Back to lists",
                onAction = onDismiss,
                actionModifier = Modifier.focusRequester(first),
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            PosterGrid(
                items = cards,
                gridState = state,
                onFocusedIndex = { focusedIndex = it },
                onClick = onOpen,
                onLongClick = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .height(DiscoverPlan.gridHeightDp(collapsed = false).dp)
                    .offset(y = DiscoverPlan.gridTopDp(collapsed = false).dp)
                    .focusRequester(first),
            )
        }
    }
}

private const val FOCUS_ATTEMPTS = 10

/**
 * How far the grid is pulled up from where [TopLevelScaffold] leaves it.
 *
 * The scaffold rests `TvSpace.L` (36) under its title; spec §5.2 rests 10.
 */
private val SCAFFOLD_GAP_TRIM = 26.dp

/** Spec §5.3: 24 SemiBold, in a block exactly 24 px tall so the cell measures 270, not 280. */
private val FolderNameStyle =
    TvType.ControlLabel.copy(fontWeight = FontWeight.SemiBold, lineHeight = 24.sp)

/** Spec §5.3: Space Mono 20, in a 20 px block. */
private val FolderCountStyle = TvType.data(20).copy(lineHeight = 20.sp)

/** Spec §5.2: the count line under the screen title is mono 20 in a 22 px block, not `Meta`. */
private val CountLineStyle = TvType.data(20).copy(lineHeight = 22.sp)

/** What move mode is holding, and how far it has travelled so BACK can put it back. */
private data class MoveState(val id: Long, val applied: List<Int> = emptyList())

@Composable
private fun LoadedGrid(
    cards: List<FolderCardModel>,
    syncedLists: List<Shelf>,
    moving: MoveState?,
    gridFocus: FocusRequester,
    onOpen: (FolderCardModel) -> Unit,
    onOpenSynced: (Shelf) -> Unit,
    onEdit: (FolderCardModel) -> Unit,
    onNew: () -> Unit,
    onStartMove: (FolderCardModel) -> Unit,
    onMove: (MoveDirection) -> Unit,
    onCommit: () -> Unit,
    onCancelMove: () -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    val state = rememberLazyGridState()
    val focusManager = LocalFocusManager.current
    var focusedIndex by remember { mutableIntStateOf(0) }
    Column(modifier) {
        Text(
            SyncedLists.directoryLabel(cards.size, syncedLists.size),
            style = CountLineStyle,
            color = TvColor.TextSecondary,
            maxLines = 1,
        )
        // 112 + 22 = 134, and the grid band starts at 160.
        Spacer(Modifier.height(26.dp))
        if (moving != null) {
            MoveBanner("Move mode. Arrows move the folder. OK drops it. BACK cancels.")
            Spacer(Modifier.height(TvSpace.S))
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(FolderGrid.COLUMNS),
            state = state,
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp),
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(gridFocus)
                .focusRestorer()
                // Move mode owns the arrows. It must consume them on the DOWN event, before the
                // focus search runs, or the ring leaves the card the viewer is carrying.
                .onPreviewKeyEvent { event ->
                    if (moving == null) {
                        // LEFT means "previous card" everywhere but column 1, where it means the
                        // rail. The screen frame claims LEFT from an ancestor and Compose moves
                        // focus last of all, so the move has to happen here or the rail always
                        // wins and a four-column grid can only be walked rightwards.
                        val atColumnOne = focusedIndex % FolderGrid.COLUMNS == 0
                        val leftPress = event.type == KeyEventType.KeyDown &&
                            event.key == Key.DirectionLeft
                        if (leftPress && !atColumnOne) {
                            focusManager.moveFocus(FocusDirection.Left)
                            return@onPreviewKeyEvent true
                        }
                        return@onPreviewKeyEvent false
                    }
                    if (event.type != KeyEventType.KeyDown) {
                        return@onPreviewKeyEvent event.key in MOVE_KEYS
                    }
                    when (event.key) {
                        Key.DirectionLeft -> { onMove(MoveDirection.Left); true }
                        Key.DirectionRight -> { onMove(MoveDirection.Right); true }
                        Key.DirectionUp -> { onMove(MoveDirection.Up); true }
                        Key.DirectionDown -> { onMove(MoveDirection.Down); true }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { onCommit(); true }
                        Key.Back -> { onCancelMove(); true }
                        else -> false
                    }
                },
        ) {
            itemsIndexed(cards, key = { _, card -> card.id }) { index, card ->
                FolderTile(
                    card = card,
                    dimmed = moving != null && moving.id != card.id,
                    picked = moving?.id == card.id,
                    onFocused = { focusedIndex = index },
                    onClick = { onOpen(card) },
                    onEdit = { onEdit(card) },
                    onStartMove = { onStartMove(card) },
                )
            }
            item(key = NEW_CARD_KEY) {
                NewCollectionTile(
                    onFocused = { focusedIndex = cards.size },
                    onClick = onNew,
                )
            }
            items(
                count = syncedLists.size,
                key = { index -> "snapshot:${syncedLists[index].id}" },
            ) { listIndex ->
                val shelf = syncedLists[listIndex]
                SyncedListTile(
                    shelf = shelf,
                    dimmed = moving != null,
                    onFocused = { focusedIndex = cards.size + 1 + listIndex },
                    onClick = { onOpenSynced(shelf) },
                )
            }
        }
    }
}

private val MOVE_KEYS = setOf(
    Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown,
    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Back,
)

private const val NEW_CARD_KEY = "new-collection"

/**
 * One folder card, spec §5.3. The accent is a 6 px bar at the left edge, not a card tint: eight
 * tinted cards would fight the focus ring and look like a toy (spec §5.8.1).
 */
@Composable
private fun FolderTile(
    card: FolderCardModel,
    dimmed: Boolean,
    picked: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onStartMove: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(TvGeom.FolderCardWidth)
            .moveModeDim(dimmed),
    ) {
        LongPressFocusable(
            onClick = onClick,
            // Spec §5.4 maps long-OK to the editor. It used to start move mode and put the editor
            // on MENU, and MENU is not on every remote in this fleet. Move mode keeps MENU, which
            // is the rarer of the two jobs. A system folder is neither edited nor moved (§5.8.8).
            onLongClick = if (card.isSystem) null else onEdit,
            onMenu = if (card.isSystem) null else onStartMove,
            accessibleLabel = accessibilityLabel(
                card.name,
                card.countLabel,
                if (card.pinned) "Pinned to Home" else null,
            ),
            clickLabel = "Open collection",
            longClickLabel = "Edit collection",
            customActions = if (card.isSystem) emptyList() else listOf(
                CustomAccessibilityAction("Move collection") { onStartMove(); true },
            ),
            selected = picked,
            cornerRadius = 12.dp,
            onFocusedChange = { if (it) onFocused() },
        ) { focused ->
            Box(
                modifier = Modifier
                    .size(TvGeom.FolderCardWidth, TvGeom.FolderCardHeight)
                    .clip(TvShape.Card)
                    .background(if (picked) TvColor.Focus.copy(alpha = 0.06f) else TvColor.Elevated),
            ) {
                Box(Modifier.size(6.dp, TvGeom.FolderCardHeight).background(card.accent))
                if (card.posters.isEmpty()) {
                    SystemGlyph(card, Modifier.align(Alignment.Center))
                } else {
                    PosterSlivers(
                        posters = card.posters,
                        modifier = Modifier.padding(start = 24.dp, top = 40.dp),
                    )
                }
                if (card.pinned) {
                    // "Pinned to Home": a pin, not an unlabelled grey square.
                    androidx.compose.foundation.Image(
                        imageVector = PinGlyph,
                        contentDescription = null,
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(TvColor.TextSecondary),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = 14.dp, top = 12.dp)
                            .size(24.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(TvGeom.FocusLabelGap))
        Text(
            clip(card.name, 22),
            style = FolderNameStyle,
            color = TvColor.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(card.countLabel, style = FolderCountStyle, color = TvColor.TextMuted, maxLines = 1)
    }
}

/** A virtual synced shelf: light directory card here, full-resolution lazy grid after opening. */
@Composable
private fun SyncedListTile(
    shelf: Shelf,
    dimmed: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    val posters = remember(shelf.id, shelf.generation) {
        shelf.items.asSequence().mapNotNull { it.posterUrl }.distinct().take(FolderGrid.SLIVERS).toList()
    }
    val count = SyncedLists.countLabel(shelf)
    val source = SyncedLists.sourceLabel(shelf)
    Column(Modifier.width(TvGeom.FolderCardWidth).moveModeDim(dimmed)) {
        LongPressFocusable(
            onClick = onClick,
            enabled = !dimmed,
            accessibleLabel = accessibilityLabel(shelf.title, count, source),
            clickLabel = "Open synced list",
            cornerRadius = 12.dp,
            onFocusedChange = { if (it) onFocused() },
        ) { _ ->
            Box(
                modifier = Modifier
                    .size(TvGeom.FolderCardWidth, TvGeom.FolderCardHeight)
                    .clip(TvShape.Card)
                    .background(TvColor.Elevated),
            ) {
                Box(Modifier.size(6.dp, TvGeom.FolderCardHeight).background(TvColor.Focus.copy(alpha = 0.55f)))
                PosterSlivers(
                    posters = posters,
                    modifier = Modifier.padding(start = 24.dp, top = 36.dp),
                )
                Text(
                    text = source,
                    style = TvType.data(16),
                    color = TvColor.TextSecondary,
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 16.dp),
                )
            }
        }
        Spacer(Modifier.height(TvGeom.FocusLabelGap))
        Text(
            text = shelf.title,
            style = FolderNameStyle,
            color = TvColor.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(count, style = FolderCountStyle, color = TvColor.TextMuted, maxLines = 1)
    }
}

/**
 * A 72 px block stands in for the clock, bookmark and cloud glyphs until the flat marks are
 * drawn. It is honest: a system folder with nothing in it looks different from one with posters.
 */
@Composable
private fun SystemGlyph(card: FolderCardModel, modifier: Modifier = Modifier) {
    // Spec §5.3: a system folder's mark is `textMuted`. My Cloud used the green `cached` token,
    // which spec §0.5 reserves for "this stream is already on the debrid box".
    val tint: Color = when (card.role) {
        FolderRole.ContinueWatching -> TvColor.TextSecondary
        FolderRole.Watchlist, FolderRole.MyCloud, FolderRole.User -> TvColor.TextMuted
    }
    // A real glyph per folder. The old mark was an empty rounded square, which read as a
    // poster that failed to load.
    if (card.role == FolderRole.User) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(com.fourseveneightnine.tv.R.drawable.ic_rail_collections),
            contentDescription = null,
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint),
            modifier = modifier.size(56.dp),
        )
    } else {
        androidx.compose.foundation.Image(
            imageVector = folderGlyph(card.role),
            contentDescription = null,
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint),
            modifier = modifier.size(56.dp),
        )
    }
}

private val glyphCache = HashMap<String, androidx.compose.ui.graphics.vector.ImageVector>()

/** Material filled paths, 24 x 24. Built once and kept. */
private fun glyph(name: String, path: String): androidx.compose.ui.graphics.vector.ImageVector =
    glyphCache.getOrPut(name) {
        androidx.compose.ui.graphics.vector.ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = androidx.compose.ui.graphics.vector.PathParser().parsePathString(path).toNodes(),
            fill = androidx.compose.ui.graphics.SolidColor(Color.White),
        ).build()
    }

private fun folderGlyph(role: FolderRole): androidx.compose.ui.graphics.vector.ImageVector = when (role) {
    FolderRole.ContinueWatching -> glyph(
        "history",
        "M13,3c-4.97,0 -9,4.03 -9,9L1,12l3.89,3.89 0.07,0.14L9,12L6,12c0,-3.87 3.13,-7 7,-7s7,3.13 7,7 -3.13,7 -7,7c-1.93,0 -3.68,-0.79 -4.94,-2.06l-1.42,1.42C8.27,19.99 10.51,21 13,21c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9zM12,8v5l4.28,2.54 0.72,-1.21 -3.5,-2.08L13.5,8L12,8z",
    )
    FolderRole.MyCloud -> glyph(
        "cloud",
        "M19.35,10.04C18.67,6.59 15.64,4 12,4 9.11,4 6.6,5.64 5.35,8.04 2.34,8.36 0,10.91 0,14c0,3.31 2.69,6 6,6h13c2.76,0 5,-2.24 5,-5 0,-2.64 -2.05,-4.78 -4.65,-4.96z",
    )
    FolderRole.Watchlist, FolderRole.User -> glyph(
        "bookmark",
        "M17,3H7c-1.1,0 -1.99,0.9 -1.99,2L5,21l7,-3 7,3V5c0,-1.1 -0.9,-2 -2,-2z",
    )
}

private val PinGlyph: androidx.compose.ui.graphics.vector.ImageVector
    get() = glyph(
        "pin",
        "M16,9V4l1,0c0.55,0 1,-0.45 1,-1v0c0,-0.55 -0.45,-1 -1,-1H7C6.45,2 6,2.45 6,3v0c0,0.55 0.45,1 1,1l1,0v5c0,1.66 -1.34,3 -3,3h0v2h5.97v7l1,1l1,-1v-7H19v-2h0C17.34,12 16,10.66 16,9z",
    )

/** The dashed "New collection" card, always the last one (spec §5.8.5). */
@Composable
private fun NewCollectionTile(onFocused: () -> Unit, onClick: () -> Unit) {
    Column(Modifier.width(TvGeom.FolderCardWidth)) {
        LongPressFocusable(
            onClick = onClick,
            accessibleLabel = "New collection",
            cornerRadius = 12.dp,
            onFocusedChange = { if (it) onFocused() },
        ) { focused ->
            Box(
                modifier = Modifier
                    .size(TvGeom.FolderCardWidth, TvGeom.FolderCardHeight)
                    .clip(TvShape.Card)
                    .border(2.dp, Color.White.copy(alpha = 0.14f), TvShape.Card),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("+", style = TvType.HeroTitle, color = TvColor.TextSecondary, maxLines = 1)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "New collection",
                        style = FolderNameStyle,
                        color = TvColor.TextSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
        // The same 46 px of text block every other card carries, so the rows line up.
        Spacer(Modifier.height(78.dp))
    }
}

/** Eight skeleton cells; the sweep runs on the first row only (spec §15.4). */
@Composable
private fun LoadingGrid() {
    Column(Modifier.fillMaxSize()) {
        Skeleton(Modifier.size(300.dp, 22.dp), shape = TvShape.Badge)
        Spacer(Modifier.height(26.dp))
        FolderSkeletonRow(sweep = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(32.dp))
        FolderSkeletonRow(sweep = false, modifier = Modifier.fillMaxWidth())
    }
}
