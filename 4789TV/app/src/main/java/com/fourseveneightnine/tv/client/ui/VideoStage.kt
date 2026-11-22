package com.fourseveneightnine.tv.client.ui

import androidx.compose.ui.graphics.toArgb
import com.fourseveneightnine.tv.ui.TvTokens
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.widget.ImageView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.fourseveneightnine.tv.R
import com.fourseveneightnine.tv.player.MpvSubtitleFontPolicy
import com.fourseveneightnine.tv.player.ReceiverSubtitleStyle
import com.fourseveneightnine.tv.ui.VideoResizeMode
import kotlin.math.roundToInt

/**
 * The picture layer: pure black, the decoded frame letterboxed to its own ratio, and the cue
 * renderer above it.
 *
 * **It is created once by the Activity and never reparented.** Reparenting a `SurfaceView`
 * destroys its Surface, and the receiver advertises itself only while a visible Surface is ready —
 * so a Surface owned by the player route would leave the phone unable to find this television from
 * any other screen. The Compose tree draws over this layer instead and asks it for the two things
 * a player screen legitimately controls: the picture's fit, and where the subtitles sit.
 *
 * The media3-ui pieces here (AspectRatioFrameLayout, SubtitleView, CaptionStyleCompat) are all
 * `@UnstableApi`; `ExoReceiverController` opts in the same way.
 */
@OptIn(UnstableApi::class)
@SuppressLint("ViewConstructor")
internal class VideoStage(context: Context, pictureView: View? = null) : FrameLayout(context) {

    /**
     * Pitch black behind the picture. `videoFrame` letterboxes the SurfaceView to the decoded
     * aspect, and whatever sits behind shows in the gap: a scope film has to sit in black, not in
     * the app's furniture. `tv_black` is a blue-black and is not black enough for this.
     */
    private val letterboxBacking = View(context).apply {
        setBackgroundColor(Color.BLACK)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        // Hidden until the player is on screen. On a browse screen this was one more full
        // 1920 x 1080 fill under an opaque Compose canvas, every frame (overdraw showed 4x).
        visibility = GONE
    }

    /** The black matte behind the picture exists only while the player screen is up. */
    fun setPlayerVisible(visible: Boolean) {
        letterboxBacking.visibility = if (visible) VISIBLE else GONE
    }

    val surfaceView by lazy {
        SurfaceView(context).apply {
            isFocusable = false
            keepScreenOn = false
            // An opaque layer keeps the fitted picture clear of the canvas on Fire OS.
            holder.setFormat(PixelFormat.OPAQUE)
        }
    }

    private val videoFrame = AspectRatioFrameLayout(context).apply {
        setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)
        setAspectRatio(0f)
    }

    /** ExoPlayer decodes cues but renders nothing on its own. Without this the receiver reports */
    /** subtitle tracks to the phone while the television shows none. */
    private val subtitleView = SubtitleView(context).apply {
        setUserDefaultStyle()
        setUserDefaultTextSize()
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private val heldPicture = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        visibility = GONE
        isFocusable = false
    }
    private var heldBitmap: Bitmap? = null
    private var holdGeneration = 0L

    suspend fun holdCurrentPicture(): Boolean = withTimeoutOrNull(200) {
        releaseHeldPicture()
        val generation = holdGeneration
        if (!surfaceView.holder.surface.isValid || surfaceView.width <= 0 || surfaceView.height <= 0) return@withTimeoutOrNull false
        val width = minOf(surfaceView.width, 1280)
        val bitmap = Bitmap.createBitmap(width, (width.toLong() * surfaceView.height / surfaceView.width).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        suspendCancellableCoroutine { continuation ->
            try {
                PixelCopy.request(surfaceView, bitmap, { result ->
                    if (result == PixelCopy.SUCCESS && continuation.isActive && generation == holdGeneration) {
                        heldBitmap = bitmap
                        heldPicture.setImageBitmap(bitmap)
                        heldPicture.visibility = VISIBLE
                        continuation.resume(true)
                    } else {
                        bitmap.recycle()
                        if (continuation.isActive) continuation.resume(false)
                    }
                }, Handler(Looper.getMainLooper()))
            } catch (_: IllegalArgumentException) {
                bitmap.recycle()
                if (continuation.isActive) continuation.resume(false)
            }
        }
    } ?: false

    fun releaseHeldPicture() {
        holdGeneration++
        heldPicture.visibility = GONE
        heldPicture.setImageDrawable(null)
        // The render thread can still reference the previous drawable until its next frame.
        heldBitmap = null
    }

    private var widgetFrame = false
    private var widgetFocused = false
    private val pictureRadius get() = GuidePictureRadius.value * TvTokens.designScale(context)

    fun setWidgetFrame(enabled: Boolean, focused: Boolean = false) {
        if (widgetFrame == enabled && widgetFocused == focused) return
        widgetFrame = enabled
        widgetFocused = focused
        foreground = if (enabled) PictureCorners(pictureRadius, focused) else null
    }

    private var previewViewport: Rect? = null

    /** Resize the existing stage. Keep its SurfaceView attached to the same parent and decoder. */
    fun setViewport(bounds: Rect?) {
        if (previewViewport == bounds) return
        previewViewport = bounds?.let(::Rect)
        layoutParams = if (bounds == null) {
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        } else {
            LayoutParams(bounds.width().coerceAtLeast(1), bounds.height().coerceAtLeast(1)).apply {
                leftMargin = bounds.left
                topMargin = bounds.top
            }
        }
        applyResizeMode(resizeMode)
    }

    private var videoRatio = 0f
    private var resizeMode = VideoResizeMode.Fit

    init {
        isFocusable = false
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        addView(letterboxBacking, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        videoFrame.addView(pictureView ?: surfaceView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(videoFrame, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        addView(subtitleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(heldPicture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private val mattePaint = android.graphics.Paint().apply { color = Color.BLACK }

    override fun drawChild(canvas: android.graphics.Canvas, child: View, drawingTime: Long): Boolean {
        val drawn = super.drawChild(canvas, child, drawingTime)
        if (child === videoFrame && letterboxBacking.visibility == VISIBLE &&
            resizeMode == VideoResizeMode.Fit && videoFrame.top > 0
        ) {
            // Cover the decoded edge where fitted video meets the top letterbox.
            val edge = maxOf(1f, 2f * TvTokens.designScale(context))
            canvas.drawRect(videoFrame.left.toFloat(), videoFrame.top.toFloat(),
                videoFrame.right.toFloat(), videoFrame.top + edge, mattePaint)
        }
        return drawn
    }

    private var currentCues: List<Cue> = emptyList()

    fun setCues(cues: List<Cue>) {
        currentCues = cues
        subtitleView.setCues(if (controlsVisible) cues.map { cue ->
            val usesBottomLine = when (cue.lineType) {
                Cue.LINE_TYPE_FRACTION -> cue.line >= 1f - RAISED_SUBTITLE_PADDING_FRACTION
                Cue.LINE_TYPE_NUMBER -> cue.line in -3f..-1f
                else -> false
            }
            // A bottom-positioned cue can bypass SubtitleView's bottom padding.
            if (cue.text != null && usesBottomLine) cue.buildUpon().setLine(Cue.DIMEN_UNSET, Cue.TYPE_UNSET).build()
            else cue
        } else cues)
    }

    fun setVideoAspectRatio(ratio: Float) {
        videoRatio = ratio
        applyResizeMode(resizeMode)
    }

    fun applyResizeMode(mode: VideoResizeMode) {
        resizeMode = mode
        val visibleMode = if (previewViewport != null) VideoResizeMode.Fit else mode
        val ratio = if (videoRatio > 0f) videoRatio else 16f / 9f
        val panelRatio = if (width > 0 && height > 0) width.toFloat() / height else 16f / 9f
        // Measure a larger, centered fit area so the video surface reaches beyond the screen.
        val zoom = visibleMode.zoomFactor(ratio, panelRatio)
        val params = videoFrame.layoutParams as LayoutParams
        params.width = if (zoom > 1f && width > 0) (width * zoom).roundToInt() else LayoutParams.MATCH_PARENT
        params.height = if (zoom > 1f && height > 0) (height * zoom).roundToInt() else LayoutParams.MATCH_PARENT
        videoFrame.scaleX = 1f
        videoFrame.scaleY = 1f
        when (visibleMode) {
            VideoResizeMode.Crop -> {
                // Media3 measures the surface at cover size; scaling a SurfaceView only scales
                // its view layer and left the hardware video crop unchanged on some boxes.
                videoFrame.clipChildren = true
                videoFrame.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM)
                videoFrame.setAspectRatio(ratio)
            }
            VideoResizeMode.Stretch -> {
                // Keep the decoder's native fitted surface, then stretch the containing frame
                // to the panel. RESIZE_MODE_FILL alone can leave SurfaceView showing the previous
                // crop transform until the codec supplies a new surface buffer.
                videoFrame.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)
                videoFrame.setAspectRatio(ratio)
                videoFrame.scaleX = maxOf(1f, panelRatio / ratio)
                videoFrame.scaleY = maxOf(1f, ratio / panelRatio)
                videoFrame.clipChildren = false
            }
            else -> {
                videoFrame.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)
                videoFrame.setAspectRatio(ratio)
                videoFrame.clipChildren = true
            }
        }
        videoFrame.requestLayout()
        videoFrame.invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyResizeMode(resizeMode)
    }

    /** Applies the phone's subtitle appearance (`X4789.SubtitleStyle`) to our own cue renderer. */
    fun applySubtitleStyle(style: ReceiverSubtitleStyle) {
        // Embedded ASS styling would override the viewer's choices, so cues are drawn with ours.
        subtitleView.setApplyEmbeddedStyles(false)
        val typeface = MpvSubtitleFontPolicy.resolvedTypeface(context, style.family)
        subtitleView.setStyle(
            CaptionStyleCompat(
                style.foregroundColor(),
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                if (style.shadow) {
                    CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
                } else {
                    CaptionStyleCompat.EDGE_TYPE_NONE
                },
                context.getColor(R.color.tv_black),
                if (style.bold) Typeface.create(typeface, Typeface.BOLD) else typeface,
            ),
        )
        subtitleView.setFractionalTextSize(style.fractionalTextSize)
        baseBottomPaddingFraction = style.bottomPaddingFraction
        subtitleView.setBottomPaddingFraction(subtitleBottomPaddingFraction)
    }

    private var baseBottomPaddingFraction = 0.08f
    private var controlsVisible = false
    private val subtitleBottomPaddingFraction: Float
        get() = if (controlsVisible) maxOf(baseBottomPaddingFraction, RAISED_SUBTITLE_PADDING_FRACTION)
        else baseBottomPaddingFraction

    /** Lift bottom-aligned subtitles above the episode context while the controls are visible. */
    fun setControlBarVisible(visible: Boolean) {
        if (controlsVisible == visible) return
        controlsVisible = visible
        subtitleView.setBottomPaddingFraction(subtitleBottomPaddingFraction)
        setCues(currentCues)
    }

    private companion object {
        const val RAISED_SUBTITLE_PADDING_FRACTION = 0.33f
    }
}

/** Mask only the corners of the native picture, using the guide's contour and canvas color. */
private class PictureCorners(private val radius: Float, private val focused: Boolean) : android.graphics.drawable.Drawable() {
    private var opacity = 255
    private val corners = android.graphics.Path()
    private val edge = android.graphics.Path()
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    override fun onBoundsChange(bounds: Rect) {
        val frame = android.graphics.RectF(bounds)
        corners.reset()
        corners.fillType = android.graphics.Path.FillType.EVEN_ODD
        corners.addRect(frame, android.graphics.Path.Direction.CW)
        corners.addRoundRect(frame, radius, radius, android.graphics.Path.Direction.CW)
        edge.reset()
        frame.inset(1f, 1f)
        edge.addRoundRect(frame, radius - 1f, radius - 1f, android.graphics.Path.Direction.CW)
    }
    override fun draw(canvas: android.graphics.Canvas) {
        paint.style = android.graphics.Paint.Style.FILL
        paint.color = TvTokens.Color.Black.toArgb()
        paint.alpha = opacity
        canvas.drawPath(corners, paint)
        if (focused) {
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = android.graphics.Color.WHITE
            paint.alpha = opacity
            canvas.drawPath(edge, paint)
        }
    }
    override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
    override fun setColorFilter(filter: android.graphics.ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Deprecated("Deprecated in Android") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
