package com.fourseveneightnine.tv.client.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.widget.ImageView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import android.graphics.Color
import android.graphics.PixelFormat
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
internal class VideoStage(context: Context) : FrameLayout(context) {

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

    val surfaceView = SurfaceView(context).apply {
        isFocusable = false
        keepScreenOn = false
        // An opaque layer. A translucent SurfaceView exposes the canvas through the narrow fit
        // gaps as grey strips on Fire OS.
        holder.setFormat(PixelFormat.OPAQUE)
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

    private var videoRatio = 0f
    private var resizeMode = VideoResizeMode.Fit

    init {
        isFocusable = false
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        addView(letterboxBacking, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        videoFrame.addView(surfaceView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(videoFrame, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        addView(subtitleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(heldPicture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun setCues(cues: List<Cue>) = subtitleView.setCues(cues)

    fun setVideoAspectRatio(ratio: Float) {
        videoRatio = ratio
        applyResizeMode(resizeMode)
    }

    fun applyResizeMode(mode: VideoResizeMode) {
        resizeMode = mode
        val ratio = if (videoRatio > 0f) videoRatio else 16f / 9f
        videoFrame.scaleX = 1f
        videoFrame.scaleY = 1f
        when (mode) {
            VideoResizeMode.Fit -> {
                videoFrame.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)
                videoFrame.setAspectRatio(ratio)
                videoFrame.clipChildren = true
            }
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
                val panelRatio = if (height > 0) width.toFloat() / height else 16f / 9f
                videoFrame.scaleX = maxOf(1f, panelRatio / ratio)
                videoFrame.scaleY = maxOf(1f, ratio / panelRatio)
                videoFrame.clipChildren = false
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
        subtitleView.setStyle(
            CaptionStyleCompat(
                style.foregroundColor(),
                if (style.backgroundEnabled) SUBTITLE_BACKGROUND else Color.TRANSPARENT,
                Color.TRANSPARENT,
                if (style.outlineEnabled) {
                    CaptionStyleCompat.EDGE_TYPE_OUTLINE
                } else {
                    CaptionStyleCompat.EDGE_TYPE_NONE
                },
                context.getColor(R.color.tv_black),
                MpvSubtitleFontPolicy.resolvedTypeface(context, style.family),
            ),
        )
        subtitleView.setFractionalTextSize(style.fractionalTextSize)
        baseBottomPaddingFraction = style.bottomPaddingFraction
        subtitleView.setBottomPaddingFraction(
            if (controlsVisible) RAISED_SUBTITLE_PADDING_FRACTION else baseBottomPaddingFraction,
        )
    }

    private var baseBottomPaddingFraction = 0.08f
    private var controlsVisible = false

    /**
     * Subtitles lift while the control bar is up and drop back when it hides (spec §11.17.2).
     * They are never covered.
     */
    fun setControlBarVisible(visible: Boolean) {
        controlsVisible = visible
        subtitleView.setBottomPaddingFraction(
            if (visible) RAISED_SUBTITLE_PADDING_FRACTION else baseBottomPaddingFraction,
        )
    }

    private companion object {
        val SUBTITLE_BACKGROUND: Int = Color.argb(184, 0, 0, 0)

        /** The bar's scrim starts at y 620 of 1080; a 0.35 baseline clears it. */
        const val RAISED_SUBTITLE_PADDING_FRACTION = 0.28f
    }
}
