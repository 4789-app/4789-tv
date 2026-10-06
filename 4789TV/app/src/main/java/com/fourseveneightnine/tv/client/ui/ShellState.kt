package com.fourseveneightnine.tv.client.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.fourseveneightnine.tv.ui.TvRemoteCommand

/**
 * What the Activity needs to know about what the Compose tree has actually drawn.
 *
 * `playbackOwnsDpad` is derived from this and from nothing else. The old shell answered the same
 * question from a flag fed by one event stream, so any ending that failed to deliver a final event
 * left the flag stuck at `true`: on the home screen UP and DOWN raised player controls for a film
 * that was not playing and LEFT and RIGHT issued blind seeks. The remote looked broken and the
 * only cure a viewer found was force-quitting the app.
 *
 * A state derived from what is drawn cannot get stuck. If the player is not on screen, the D-pad
 * belongs to the screen that is, whatever any flag says.
 */
internal class ShellState {
    /** True while the player route is the current destination. */
    var playerVisible by mutableStateOf(false)

    /** True while the control bar is up. The bar owns the D-pad when it is. */
    var chromeVisible by mutableStateOf(false)

    /** A side panel, a plate or a dialog is up, and it owns the remote. */
    var overlayVisible by mutableStateOf(false)

    /** Set by the player route. Returns true when the command was consumed. */
    var playerCommands: ((TvRemoteCommand) -> Boolean)? = null
    var heldSeekDeltaMillis: Long? = null
    var holdLivePicture: (suspend () -> Boolean)? = null
    var releaseLivePicture: (() -> Unit)? = null
    var holdingLivePicture by mutableStateOf(false)

    /** Set by the shell. Opens the navigation rail, which is also the focus rescue target. */
    var openRail: (() -> Unit)? = null

    /**
     * Set by the current top-level screen. Puts focus back where the viewer left it when the rail
     * closes (spec §1.5). Without it, RIGHT collapses the rail and strands focus on a rail item
     * that is no longer drawn, which is a dead remote by another route.
     */
    var restoreContentFocus: (() -> Unit)? = null

    val playbackOwnsDpad: Boolean get() = playerVisible && !overlayVisible
}

internal val LocalShellState = staticCompositionLocalOf<ShellState> {
    error("ShellState was not provided")
}
