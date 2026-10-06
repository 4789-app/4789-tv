package com.fourseveneightnine.tv.client.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.fourseveneightnine.tv.client.ui.LocalShellState
import com.fourseveneightnine.tv.client.ui.components.EmptyState
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvGeom
import com.fourseveneightnine.tv.client.ui.theme.TvSpace
import com.fourseveneightnine.tv.client.ui.theme.TvType

/**
 * The frame every top-level screen sits in, spec §0.1 and §0.2.
 *
 * Content starts at x 220 — 36 px of rest after the rail's 184 px strip — and the screen title
 * sits on the safe line at y 54. LEFT from the leftmost thing on the screen opens the rail, and so
 * does MENU; that is the rail's only entry (spec §1.1).
 */
@Composable
internal fun TopLevelScaffold(
    title: String,
    onOpenRail: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            // Opaque: the Activity keeps a black picture layer underneath every screen, and a
            // browse screen must cover it rather than let the letterbox matte read as the canvas.
            .background(TvColor.Canvas)
            // KEY DOWN, not key up. Compose runs its directional focus search on the down
            // event, so a handler that waits for the up event has already lost: focus jumps
            // into the rail's nearest item by geometry and the rail never opens. Consuming the
            // down event is what makes LEFT mean "open the rail" rather than "move left".
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft, Key.Menu -> { onOpenRail(); true }
                    else -> false
                }
            }
            .padding(start = TvGeom.ContentLeft, top = TvGeom.SafeTop, end = 96.dp),
    ) {
        Text(title, style = TvType.ScreenTitle, color = TvColor.TextPrimary, maxLines = 1)
        Spacer(Modifier.height(TvSpace.L))
        content()
    }
}

/**
 * Wave 0 leaves the six shelves to the next wave. Until then every top-level screen states what
 * would fill it and offers the one action that works today: pairing.
 */
@Composable
internal fun PlaceholderScreen(
    title: String,
    headline: String,
    line: String,
    onOpenRail: () -> Unit,
    onOpenPairing: (() -> Unit)? = null,
) {
    val focus = remember { FocusRequester() }
    val shell = LocalShellState.current
    // The rail borrows focus and must be able to give it back.
    DisposableEffect(Unit) {
        val restore = { runCatching { focus.requestFocus() }; Unit }
        shell.restoreContentFocus = restore
        onDispose { if (shell.restoreContentFocus === restore) shell.restoreContentFocus = null }
    }
    TopLevelScaffold(title = title, onOpenRail = onOpenRail) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                headline = headline,
                line = line,
                actionLabel = onOpenPairing?.let { "Open Pair and Sync" },
                onAction = onOpenPairing,
                actionModifier = Modifier.focusRequester(focus),
            )
        }
    }
    // Initial focus on a node that is always composed (plan §7.4 rule 2). Focus is placed once,
    // on entry — never again because data arrived.
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
