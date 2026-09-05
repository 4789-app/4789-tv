package com.fourseveneightnine.tv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import androidx.compose.ui.graphics.toArgb

/**
 * A hairline scrubber. Android's stock ProgressBar/SeekBar drag a whole themed drawable with them —
 * inset padding, a chunky thumb, a tint that fights the picture — so this draws the three things a
 * seek bar actually is: a track, an elapsed fill, and a handle that only appears when the remote is
 * on it.
 *
 * It owns no state beyond what it paints; scrub arithmetic lives in [PlayerControlsPolicy].
 */
internal class SlimSeekBar(context: Context) : View(context) {

    /** 0f-1f. */
    var progress: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (field != clamped) {
                field = clamped
                invalidate()
            }
        }

    /** 0f-1f. How much of the stream is already downloaded — the middle shade, ahead of play. */
    var buffered: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (field != clamped) {
                field = clamped
                invalidate()
            }
        }

    /** Thicker bar + visible handle while the remote is on it, invisible-thin when it is not. */
    var active: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** A scrub in flight paints the accent; a resting bar stays a quiet white. */
    var scrubbing: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /**
     * 0f-1f, or null. Where the film actually is while a scrub is in flight.
     *
     * A scrub moves the thumb away from the playhead, and a bar that only shows the destination
     * gives a viewer nothing to measure the jump against. This tick is the thing left behind.
     */
    var ghost: Float? = null
        set(value) {
            val clamped = value?.coerceIn(0f, 1f)
            if (field != clamped) {
                field = clamped
                invalidate()
            }
        }

    /**
     * Chapter boundaries as 0-1 fractions of the runtime. Empty for a file that declares none,
     * which is most of them — the rail then draws exactly as it always did.
     */
    var chapters: List<Float> = emptyList()
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TvTokens.Color.Text.copy(alpha = 0.14f).toArgb()
    }
    private val bufferedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TvTokens.Color.Secondary.copy(alpha = 0.40f).toArgb()
    }
    private val elapsedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TvTokens.Color.BrandOrange.toArgb()
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TvTokens.Color.Text.toArgb()
    }

    /** The playhead left behind by a scrub — a hairline, not a second handle competing for the eye. */
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TvTokens.Color.Secondary.toArgb()
    }

    /**
     * Chapter notches are cut OUT of the rail rather than drawn on top of it: a bright mark on a
     * dim track reads as content, and a chapter boundary is not content.
     */
    private val chapterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TvTokens.Color.Text.copy(alpha = 0.50f).toArgb()
    }
    private val bounds = RectF()
    private var elapsedShader: LinearGradient? = null
    private var shaderLeft = Float.NaN
    private var shaderRight = Float.NaN

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            dp(HEIGHT_DP),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val height = (if (active) dp(BAR_ACTIVE_DP) else dp(BAR_IDLE_DP)).toFloat()
        val radius = height / 2f
        val centerY = this.height / 2f
        val handleRadius = if (active) dp(HANDLE_DP).toFloat() else 0f
        // Keep the handle inside the view at both ends instead of clipping it in half.
        val left = handleRadius
        val right = width - handleRadius

        updateElapsedShader(left, right)

        bounds.set(left, centerY - radius, right, centerY + radius)
        canvas.drawRoundRect(bounds, radius, radius, trackPaint)

        val elapsed = left + (right - left) * progress
        var bufferedEnd = left + (right - left) * maxOf(buffered, progress)
        if (buffered > progress && (bufferedEnd - elapsed) < dp(16)) {
            bufferedEnd = (elapsed + dp(16)).coerceAtMost(right)
        }
        if (bufferedEnd > left) {
            bounds.set(left, centerY - radius, bufferedEnd, centerY + radius)
            canvas.drawRoundRect(bounds, radius, radius, bufferedPaint)
        }

        if (elapsed > left) {
            bounds.set(left, centerY - radius, elapsed, centerY + radius)
            canvas.drawRoundRect(bounds, radius, radius, elapsedPaint)
        }

        // Chapter notches sit above the fills so a boundary stays visible in watched territory.
        if (chapters.isNotEmpty()) {
            val notchHeight = dp(CHAPTER_HEIGHT_DP).toFloat()
            val notchWidth = dp(CHAPTER_WIDTH_DP).toFloat()
            chapters.forEach { fraction ->
                val x = left + (right - left) * fraction.coerceIn(0f, 1f)
                bounds.set(x - notchWidth / 2f, centerY - notchHeight, x + notchWidth / 2f, centerY + notchHeight)
                canvas.drawRect(bounds, chapterPaint)
            }
        }

        ghost?.let { fraction ->
            val x = left + (right - left) * fraction
            val tick = dp(GHOST_HEIGHT_DP).toFloat()
            val tickWidth = dp(GHOST_WIDTH_DP).toFloat()
            bounds.set(x - tickWidth / 2f, centerY - tick, x + tickWidth / 2f, centerY + tick)
            canvas.drawRect(bounds, ghostPaint)
        }

        if (handleRadius > 0f) {
            handlePaint.color = if (scrubbing) {
                TvTokens.Color.BrandOrange.toArgb()
            } else {
                TvTokens.Color.Text.toArgb()
            }
            canvas.drawCircle(elapsed, centerY, handleRadius, handlePaint)
        }
    }

    /** Cache the fill's token gradient; progress updates never allocate a shader or drawable. */
    private fun updateElapsedShader(left: Float, right: Float) {
        val safeRight = right.coerceAtLeast(left + 1f)
        if (elapsedShader == null || shaderLeft != left || shaderRight != safeRight) {
            shaderLeft = left
            shaderRight = safeRight
            elapsedShader = LinearGradient(
                left,
                0f,
                safeRight,
                0f,
                TvTokens.Color.BrandOrange.toArgb(),
                TvTokens.Color.BrandGreen.toArgb(),
                Shader.TileMode.CLAMP,
            )
            elapsedPaint.shader = elapsedShader
        }
    }

    private fun dp(value: Int): Int = TvTokens.designUnit(context, value)

    private companion object {
        /** Row height is fixed so the layout does not jump when the bar thickens on focus. */
        const val HEIGHT_DP = 16
        const val BAR_IDLE_DP = 3
        const val BAR_ACTIVE_DP = 8
        const val HANDLE_DP = 11

        /** Reference ticks span 14dp over the 8dp rail so chapter boundaries stay legible. */
        const val CHAPTER_HEIGHT_DP = 7
        const val CHAPTER_WIDTH_DP = 2
        const val GHOST_HEIGHT_DP = 5
        const val GHOST_WIDTH_DP = 2
    }
}
