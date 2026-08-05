package com.fourseveneightnine.tv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.fourseveneightnine.tv.R
import kotlin.math.roundToInt

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
        color = context.getColor(R.color.tv_track)
    }
    private val bufferedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.tv_track_buffered)
    }
    private val elapsedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.tv_track_elapsed)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.tv_text_primary)
    }

    /** The playhead left behind by a scrub — a hairline, not a second handle competing for the eye. */
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.tv_text_secondary)
    }

    /**
     * Chapter notches are cut OUT of the rail rather than drawn on top of it: a bright mark on a
     * dim track reads as content, and a chapter boundary is not content.
     */
    private val chapterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.tv_elevated)
    }
    private val bounds = RectF()

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

        bounds.set(left, centerY - radius, right, centerY + radius)
        canvas.drawRoundRect(bounds, radius, radius, trackPaint)

        // Downloaded-but-not-yet-watched, drawn before the elapsed fill so the brighter bar wins
        // wherever they overlap.
        val bufferedEnd = left + (right - left) * maxOf(buffered, progress)
        if (bufferedEnd > left) {
            bounds.set(left, centerY - radius, bufferedEnd, centerY + radius)
            canvas.drawRoundRect(bounds, radius, radius, bufferedPaint)
        }

        val elapsed = left + (right - left) * progress
        if (elapsed > left) {
            elapsedPaint.color = context.getColor(
                if (scrubbing) R.color.tv_accent else R.color.tv_track_elapsed,
            )
            bounds.set(left, centerY - radius, elapsed, centerY + radius)
            canvas.drawRoundRect(bounds, radius, radius, elapsedPaint)
        }

        // Chapter notches sit above the fills so a boundary stays visible in watched territory.
        if (chapters.isNotEmpty()) {
            val notchHeight = height * CHAPTER_HEIGHT_SCALE
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
            handlePaint.color = context.getColor(
                if (scrubbing) R.color.tv_accent else R.color.tv_text_primary,
            )
            canvas.drawCircle(elapsed, centerY, handleRadius, handlePaint)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        /** Row height is fixed so the layout does not jump when the bar thickens on focus. */
        const val HEIGHT_DP = 16
        const val BAR_IDLE_DP = 2
        const val BAR_ACTIVE_DP = 4
        const val HANDLE_DP = 6

        /** Half-height of a chapter notch, as a fraction of the row. Deliberately small. */
        const val CHAPTER_HEIGHT_SCALE = 0.16f
        const val CHAPTER_WIDTH_DP = 2
        const val GHOST_HEIGHT_DP = 5
        const val GHOST_WIDTH_DP = 2
    }
}
