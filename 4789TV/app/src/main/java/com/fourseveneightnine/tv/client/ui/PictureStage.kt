package com.fourseveneightnine.tv.client.ui

import android.graphics.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import android.view.SurfaceHolder
import android.view.View
import android.widget.FrameLayout
import com.fourseveneightnine.tv.client.appGraph
import com.fourseveneightnine.tv.client.playback.PictureInPicture
import com.fourseveneightnine.tv.ui.VideoResizeMode

internal val GuidePictureRadius = 32.dp

private fun cornerPictureBounds(width: Int, height: Int, corner: Int, fraction: Float): Rect {
    val w = (width * fraction).toInt()
    val h = w * 9 / 16
    val margin = width / 32
    val x = if (corner in 1..2) margin else width - w - margin
    val y = if (corner >= 2) height - h - margin else margin
    return Rect(x, y, x + w, y + h)
}

/** Cut the same rounded opening as the Live TV guide into the catalog above the native video. */
internal fun Modifier.cornerPictureCutout(corner: Int, fraction: Float): Modifier = drawWithCache {
    val b = cornerPictureBounds(size.width.roundToInt(), size.height.roundToInt(), corner, fraction)
    val mask = Path().apply {
        fillType = PathFillType.EvenOdd
        addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
        addRoundRect(RoundRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
            CornerRadius(GuidePictureRadius.toPx())))
    }
    onDrawWithContent { clipPath(mask) { this@onDrawWithContent.drawContent() } }
}

/** Android composes both native video surfaces. The upper plane stays in the corner,
 * including when the viewer exchanges which decoder draws there. */
internal class PictureStage(
    private val root: FrameLayout,
    private val large: VideoStage,
    private val controls: View,
    private val pip: PictureInPicture,
) {
    private val small = VideoStage(root.context).apply {
        surfaceView.setZOrderMediaOverlay(true)
        setPlayerVisible(true)
        setWidgetFrame(true)
        visibility = View.GONE
    }
    private val session get() = root.context.appGraph.playback
    private var playing = false
    private var started = false
    private var movieRatio = 0f
    private var movieFit = VideoResizeMode.Fit
    private var guideBounds: Rect? = null

    private var routedMovie: SurfaceHolder? = null
    private var switchingOutput = false
    private var liveTarget: SurfaceHolder? = null
    private val swapped get() = playing && pip.liveLarge && pip.channel != null

    init {
        small.surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = updateSurfaces()
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = updateSurfaces()
            override fun surfaceDestroyed(holder: SurfaceHolder) = surfaceLost(holder)
        })
        root.addView(small)
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) layout(playing)
        }
    }

    fun started(value: Boolean) {
        started = value
        if (value) updateSurfaces() else {
            routedMovie = null
            liveTarget = null
            pip.setScreen(null)
        }
    }

    fun movieHolder(normal: SurfaceHolder): SurfaceHolder =
        if (swapped && small.surfaceView.holder.surface.isValid) small.surfaceView.holder else normal

    fun surfaceLost(holder: SurfaceHolder) {
        if (liveTarget === holder) { liveTarget = null; pip.setScreen(null) }
        if (routedMovie === holder) routedMovie = null
    }

    fun updateSurfaces() {
        if (!started || switchingOutput) return
        val full = large.surfaceView.holder
        val corner = small.surfaceView.holder
        if (!full.surface.isValid) return
        val target = movieHolder(full)
        if (target !== routedMovie) {
            // A Surface accepts exactly one producer. Retire the live output before giving
            // that holder to the movie. The movie controller applies the change on Main.
            if (liveTarget === target || target === corner || routedMovie === corner) {
                pip.setScreen(null)
                liveTarget = null
            }
            routedMovie = target
            switchingOutput = true
            session.attachSurfaceHolder(target, target.surfaceFrame.width(), target.surfaceFrame.height())
            root.post {
                switchingOutput = false
                updateSurfaces()
            }
            return
        }
        if (pip.nativeBrowse) return
        val live = if (swapped) full else corner
        if (pip.channel != null && live.surface.isValid && liveTarget !== live) {
            liveTarget = live
            pip.setScreen(live.surface)
        }
    }

    fun guideViewport(bounds: Rect?) {
        guideBounds = bounds
        if (!pip.nativeBrowse) large.setViewport(if (swapped) null else bounds)
    }

    fun setRatio(ratio: Float) { movieRatio = ratio; updateAspect() }
    fun setFit(mode: VideoResizeMode) { movieFit = mode; updateAspect() }
    private fun updateAspect() {
        large.setVideoAspectRatio(if (swapped) pip.aspectRatio else movieRatio)
        large.applyResizeMode(if (swapped) VideoResizeMode.Fit else movieFit)
        small.setVideoAspectRatio(if (swapped) movieRatio else pip.aspectRatio)
        small.applyResizeMode(if (swapped) movieFit else VideoResizeMode.Fit)
    }

    fun updateCues() {
        val cues = session.cues.value
        large.setCues(if (swapped) emptyList() else cues)
        small.setCues(if (swapped) cues else emptyList())
    }
    fun updateSubtitleStyle() {
        large.applySubtitleStyle(session.subtitleStyle.value)
        small.applySubtitleStyle(session.subtitleStyle.value)
    }
    fun setControlBarVisible(visible: Boolean) {
        large.setControlBarVisible(visible && !swapped)
        small.setControlBarVisible(false)
    }

    fun layout(playerVisible: Boolean) {
        playing = playerVisible
        controls.translationZ = if (playing && pip.channel != null && !pip.nativeBrowse) small.elevation + 1f else 0f
        large.setWidgetFrame(pip.nativeBrowse)
        if (!pip.nativeBrowse) large.setViewport(if (swapped) null else guideBounds)
        if ((!pip.nativeBrowse && pip.channel == null) || root.width == 0) {
            small.visibility = View.GONE
            root.bringChildToFront(controls)
            updateSurfaces()
            updateAspect()
            updateCues()
            return
        }
        val cornerBounds = cornerPictureBounds(root.width, root.height, pip.corner, pip.size.widthFraction)
        if (pip.nativeBrowse) {
            small.visibility = View.GONE
            large.setViewport(cornerBounds)
            large.setPlayerVisible(true)
            updateSurfaces()
            root.bringChildToFront(controls)
            return
        }
        small.setViewport(cornerBounds)
        small.visibility = View.VISIBLE
        root.bringChildToFront(small)
        root.bringChildToFront(controls)
        updateAspect()
        updateCues()
        updateSurfaces()
    }
}
