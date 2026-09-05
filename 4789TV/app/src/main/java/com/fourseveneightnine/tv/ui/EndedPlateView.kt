package com.fourseveneightnine.tv.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import com.fourseveneightnine.tv.R
import kotlin.math.roundToInt

/**
 * What the television shows when a film runs to its own end.
 *
 * Reaching the end used to fall through to the generic "ready for your iPhone" screen — the same
 * thing the receiver shows when nothing has ever played. Two hours of a film ended in a screen that
 * acknowledged none of it, and offered neither a replay nor the next episode.
 *
 * The next episode is only ever offered when the phone said what it is: the receiver has no
 * catalogue and no season order, so it never guesses. With nothing to advance to, the plate is a
 * replay and a way out, which is still an answer.
 *
 * The countdown is cancellable and says so. Auto-advance that cannot be stopped is the single most
 * complained-about behaviour in this whole category of screen, so ANY key press stops it — the
 * viewer does not have to find the right one.
 */
internal class EndedPlateView(context: Context) : FrameLayout(context) {

    private val panel: LinearLayout
    private val posterView: ImageView
    private val eyebrowView: TextView
    private val titleView: TextView
    private val metaView: TextView
    private val buttonRow: LinearLayout
    private val countdownRing: CountdownRingView

    private var countdown: ValueAnimator? = null
    private var onCountdownFinished: (() -> Unit)? = null

    val isShowing: Boolean
        get() = visibility == VISIBLE

    private val displayTypeface = TvTokens.Type.display(context)
    private val uiTypeface = TvTokens.Type.ui(context)
    private val uiMediumTypeface = TvTokens.Type.uiMedium(context)

    init {
        visibility = GONE
        setBackgroundColor(TvTokens.Color.Black.copy(alpha = 0.92f).toArgb())

        panel = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(34), dp(30), dp(34), dp(30))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(TvTokens.Color.Slate.copy(alpha = 0.82f).toArgb())
            }
        }
        val inset = (context.resources.displayMetrics.heightPixels * SAFE_AREA_FRACTION).roundToInt()
        addView(
            panel,
            LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END,
            ).apply {
                rightMargin = inset
                bottomMargin = inset
            },
        )

        posterView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(TvTokens.Color.Elevated.toArgb())
            }
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(10).toFloat())
                }
            }
        }
        panel.addView(
            posterView,
            LinearLayout.LayoutParams(dp(POSTER_WIDTH_DP), dp(POSTER_HEIGHT_DP)).apply {
                rightMargin = dp(20)
            },
        )

        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(
            column,
            LinearLayout.LayoutParams(dp(COLUMN_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        eyebrowView = TextView(context).apply {
            setTextColor(context.getColor(R.color.tv_text_tertiary))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, TvTokens.designTextPx(context, EYEBROW_SP))
            setTypeface(uiMediumTypeface)
            letterSpacing = 0.1f
            includeFontPadding = false
        }
        column.addView(eyebrowView, columnParams())

        titleView = TextView(context).apply {
            setTextColor(context.getColor(R.color.tv_text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, TvTokens.designTextPx(context, TITLE_SP))
            setTypeface(displayTypeface)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            letterSpacing = -0.01f
            includeFontPadding = false
        }
        column.addView(titleView, columnParams(topMargin = dp(6)))

        metaView = TextView(context).apply {
            setTextColor(context.getColor(R.color.tv_text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, TvTokens.designTextPx(context, META_SP))
            setTypeface(uiTypeface)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        column.addView(metaView, columnParams(topMargin = dp(4)))

        buttonRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        column.addView(buttonRow, columnParams(topMargin = dp(16)))

        // The handoff calls for a cancellable eight-second ring rather than a second progress
        // line. The custom view holds its paints and oval so animator ticks allocate nothing.
        countdownRing = CountdownRingView(context).apply { visibility = GONE }
        column.addView(
            countdownRing,
            LinearLayout.LayoutParams(
                dp(COUNTDOWN_RING_SIZE_DP),
                dp(COUNTDOWN_RING_SIZE_DP),
            ).apply { topMargin = dp(16) },
        )
    }

    /**
     * @param onNext null when the phone named nothing to follow this title. There is then no
     *   countdown either — nothing should start on its own when there is nothing to start.
     */
    fun show(
        nextTitle: String?,
        nextMeta: String?,
        poster: Bitmap?,
        onReplay: () -> Unit,
        onNext: (() -> Unit)?,
        onDone: () -> Unit,
    ) {
        cancelCountdown()
        buttonRow.removeAllViews()

        val hasNext = onNext != null
        eyebrowView.text = getContextString(
            if (hasNext) R.string.ended_up_next else R.string.ended_heading,
        )
        titleView.text = nextTitle?.takeIf(String::isNotBlank)
            ?: getContextString(R.string.ended_no_next_title)
        metaView.text = nextMeta.orEmpty()
        metaView.visibility = if (metaView.text.isNullOrBlank()) GONE else VISIBLE

        setPoster(poster)

        var firstButton: View? = null
        if (onNext != null) {
            val play = button(getContextString(R.string.ended_play_next), primary = true) {
                cancelCountdown()
                hide()
                onNext()
            }
            buttonRow.addView(play, buttonParams())
            firstButton = play
        }
        val replay = button(getContextString(R.string.ended_replay), primary = !hasNext) {
            cancelCountdown()
            hide()
            onReplay()
        }
        buttonRow.addView(replay, buttonParams())
        if (firstButton == null) firstButton = replay

        val done = button(getContextString(R.string.ended_done), primary = false) {
            cancelCountdown()
            hide()
            onDone()
        }
        buttonRow.addView(done, buttonParams())

        visibility = VISIBLE
        post { firstButton.requestFocus() }

        if (onNext != null) startCountdown { onNext() }
    }

    /** Artwork arrives over the network, so it lands after the plate is already up — or never. */
    fun setPoster(poster: Bitmap?) {
        posterView.visibility = if (poster != null) VISIBLE else GONE
        poster?.let(posterView::setImageBitmap)
    }

    fun hide() {
        cancelCountdown()
        visibility = GONE
    }

    /** Any key stops the countdown. A viewer reaching for the remote has already said "wait". */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isShowing && event.action == KeyEvent.ACTION_DOWN) cancelCountdown()
        return super.dispatchKeyEvent(event)
    }

    private fun startCountdown(onFinished: () -> Unit) {
        onCountdownFinished = onFinished
        countdownRing.visibility = VISIBLE
        countdownRing.progress = 0f
        countdown = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = COUNTDOWN_MILLIS
            addUpdateListener { animator ->
                countdownRing.progress = animator.animatedValue as Float
            }
            addListener(
                object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        val callback = onCountdownFinished ?: return
                        onCountdownFinished = null
                        hide()
                        callback()
                    }
                },
            )
            start()
        }
    }

    private fun cancelCountdown() {
        onCountdownFinished = null
        countdown?.cancel()
        countdown = null
        countdownRing.progress = 0f
        countdownRing.visibility = GONE
    }

    private fun button(text: String, primary: Boolean, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_PX, TvTokens.designTextPx(context, BUTTON_SP))
            setTypeface(uiMediumTypeface)
            includeFontPadding = false
            minHeight = dp(BUTTON_HEIGHT_DP)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            applyButtonStyle(this, primary = primary, focused = false)
            setOnFocusChangeListener { view, hasFocus ->
                applyButtonStyle(view as TextView, primary = primary, focused = hasFocus)
            }
            setOnClickListener { onClick() }
        }

    /**
     * Focus is a SOLID FILL; the recommended action is an OUTLINE.
     *
     * Making the recommended button already look focused would leave the viewer unable to see where
     * the D-pad actually is — the one thing a television control can never be ambiguous about.
     */
    private fun applyButtonStyle(view: TextView, primary: Boolean, focused: Boolean) {
        view.background = GradientDrawable().apply {
            cornerRadius = dp(22).toFloat()
            setColor(
                when {
                    focused -> context.getColor(R.color.tv_accent)
                    primary -> TvTokens.Color.BrandOrange.copy(alpha = 0.16f).toArgb()
                    else -> TvTokens.Color.Text.copy(alpha = 0.06f).toArgb()
                },
            )
            setStroke(
                dp(if (focused || primary) 2 else 1),
                when {
                    focused -> context.getColor(R.color.tv_accent)
                    primary -> TvTokens.Color.BrandOrange.copy(alpha = 0.48f).toArgb()
                    else -> TvTokens.Color.Text.copy(alpha = 0.12f).toArgb()
                },
            )
        }
        view.setTextColor(
            context.getColor(
                if (focused) R.color.tv_control_text_focused else R.color.tv_control_text,
            ),
        )
    }

    private fun buttonParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { rightMargin = dp(10) }

    private fun columnParams(topMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { this.topMargin = topMargin }

    private fun getContextString(resId: Int): String = context.getString(resId)

    private fun dp(value: Int): Int = TvTokens.designUnit(context, value)

    private companion object {
        const val SAFE_AREA_FRACTION = 0.06f
        const val POSTER_WIDTH_DP = 96
        const val POSTER_HEIGHT_DP = 144
        const val COLUMN_WIDTH_DP = 340
        const val EYEBROW_SP = 12f
        const val TITLE_SP = 64f
        const val META_SP = 15f
        const val BUTTON_SP = 15f
        const val BUTTON_HEIGHT_DP = 44
        const val COUNTDOWN_RING_SIZE_DP = 58

        /** Long enough to read the title and decide; short enough not to feel like a hostage. */
        const val COUNTDOWN_MILLIS = 8_000L
    }

    /** A single cached-paint ring, invalidated only by the active eight-second countdown. */
    private class CountdownRingView(context: Context) : View(context) {
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = TvTokens.Color.Text.copy(alpha = 0.18f).toArgb()
        }
        private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = TvTokens.Color.BrandOrange.toArgb()
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TvTokens.Color.Text.toArgb()
            textAlign = Paint.Align.CENTER
            typeface = TvTokens.Type.data(context)
        }
        private val oval = RectF()
        var progress: Float = 0f
            set(value) {
                field = value.coerceIn(0f, 1f)
                invalidate()
            }

        override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
            val stroke = TvTokens.designUnit(context, 3).toFloat()
            trackPaint.strokeWidth = stroke
            progressPaint.strokeWidth = stroke
            val inset = stroke / 2f
            oval.set(inset, inset, width - inset, height - inset)
            labelPaint.textSize = TvTokens.designTextPx(context, 12f)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawArc(oval, -90f, 360f, false, trackPaint)
            canvas.drawArc(oval, -90f, progress * 360f, false, progressPaint)
            val seconds = ((1f - progress) * 8f).toInt().coerceAtLeast(0)
            val baseline = height / 2f - (labelPaint.ascent() + labelPaint.descent()) / 2f
            canvas.drawText("$seconds", width / 2f, baseline, labelPaint)
        }
    }
}
