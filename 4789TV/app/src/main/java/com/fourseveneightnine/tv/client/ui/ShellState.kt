package com.fourseveneightnine.tv.client.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.fourseveneightnine.tv.client.ui.components.TvGlassArtwork
import com.fourseveneightnine.tv.client.ui.theme.LocalReduceMotion

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
    var remoteButtonCapture: ((android.view.KeyEvent) -> Boolean)? = null
    var remoteShortcut: ((com.fourseveneightnine.tv.ui.RemoteButtonAction) -> Unit)? = null
    var remoteShortcutsAllowed = false
    /** True while the player route is the current destination. */
    var playerVisible by mutableStateOf(false)
    var returnToPinnedVideo: (() -> Unit)? = null
    var togglePinnedControls: (() -> Boolean)? = null

    /** True while the control bar is up. The bar owns the D-pad when it is. */
    var chromeVisible by mutableStateOf(false)

    /** A side panel, a plate or a dialog is up, and it owns the remote. */
    var overlayVisible by mutableStateOf(false)

    /** Set by the player route. Returns true when the command was consumed. */
    var playerCommands: ((TvRemoteCommand) -> Boolean)? = null
    var heldSeekDeltaMillis: Long? = null
    /** Physical window coordinates for the one live player; null restores full screen. */
    var livePreviewViewport: ((android.graphics.Rect?) -> Unit)? = null
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

    var selectedPoster: PosterOrigin? = null
    var posterHandoff by mutableStateOf<PosterHandoff?>(null)
    var homeGlassArtwork by mutableStateOf<TvGlassArtwork?>(null)

    suspend fun restorePosterFocus(key: String): Boolean {
        val focus = posterHandoff?.origin?.takeIf { it.key == key }?.focus ?: return false
        withFrameNanos { }
        return runCatching { focus.requestFocus() }.getOrDefault(false)
    }

    val playbackOwnsDpad: Boolean get() = playerVisible && !overlayVisible
}

internal val LocalShellState = staticCompositionLocalOf<ShellState> {
    error("ShellState was not provided")
}

/** The exact artwork and position the remote selected, in the shell's pixel space. */
internal data class PosterOrigin(
    val key: String,
    val bitmap: Bitmap,
    val bounds: Rect,
    val focus: FocusRequester,
)

internal class PosterHandoff(
    val origin: PosterOrigin,
    val shelfEntry: String,
    val detailEntry: String,
    val progress: Animatable<Float, AnimationVector1D> = Animatable(0f),
) {
    var visible by mutableStateOf(true)
}

@Composable
internal fun rememberPosterSource(key: String, artworkUrl: String?): PosterSource? {
    val shell = LocalShellState.current
    return if (LocalReduceMotion.current || artworkUrl == null) null
    else remember(shell, key, artworkUrl) { PosterSource(shell, key) }
}

internal class PosterSource(private val shell: ShellState, private val key: String) {
    private val focus = FocusRequester()
    val focusModifier = Modifier.focusRequester(focus)
    private var coordinates: LayoutCoordinates? = null
    private var bitmap: Bitmap? = null

    fun artwork(value: Bitmap?) { bitmap = value }

    fun select() {
        val layout = coordinates?.takeIf { it.isAttached }
        val bounds = layout?.boundsInRoot()
        val image = bitmap
        shell.selectedPoster = if (image != null && bounds != null &&
            bounds.width >= layout.size.width * 0.9f && bounds.height >= layout.size.height * 0.9f
        ) PosterOrigin(key, image, bounds, focus) else null
    }

    fun modifier(): Modifier = Modifier.onGloballyPositioned { coordinates = it }
        .drawWithContent {
            val flight = shell.posterHandoff
            if (flight != null && flight.visible && flight.origin.key == key && flight.progress.value > 0.015f) {
                val bounds = coordinates?.takeIf { it.isAttached }?.boundsInRoot()
                if (bounds != null && (flight.origin.bounds.center - bounds.center).getDistance() < 12.dp.toPx())
                    return@drawWithContent
            }
            drawContent()
        }
}
