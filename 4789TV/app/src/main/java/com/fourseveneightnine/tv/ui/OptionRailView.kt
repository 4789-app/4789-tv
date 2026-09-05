package com.fourseveneightnine.tv.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import com.fourseveneightnine.tv.R

/**
 * A choice list on the right-hand edge of the television.
 *
 * It replaces the `AlertDialog` the track and hand-off pickers used to raise. A platform dialog on
 * a television is a phone-sized card in the platform's own theme, centred over the picture, with
 * rows sized for a thumb at thirty centimetres — unreadable from a sofa and visually unrelated to
 * everything else the receiver draws. This rail is in the receiver's own tokens, sized for the
 * room, and it leaves most of the frame visible so a subtitle or a picture-size choice can be
 * judged against the film it is being applied to.
 *
 * It owns the D-pad while it is up: UP/DOWN walk rows, LEFT/RIGHT step setting rows, and CENTER
 * either applies a terminal picker choice or intentionally closes a setting row.
 * placed on the row that is already selected, so a viewer who opened it to check rather than to
 * change can read the answer and press BACK.
 */
internal class OptionRailView(context: Context) : FrameLayout(context) {

    /** One choosable line. [detail] is the quiet half — a codec, a channel count, a source. */
    data class Row(
        val label: String,
        val detail: String? = null,
        val selected: Boolean = false,
        val onPick: (() -> Unit)? = null,
        val valueProvider: (() -> String)? = null,
        val onStep: ((Int) -> Unit)? = null,
    )

    /** A group of rows under an optional heading, e.g. subtitles the phone found online. */
    data class Section(
        val heading: String? = null,
        val rows: List<Row>,
    )

    private val scrim: View
    private val panel: LinearLayout
    private val titleView: TextView
    private val rowsView: LinearLayout
    private val scroller: ScrollView

    private var onDismiss: (() -> Unit)? = null
    /** Guards the reverse animation so CENTER/BACK cannot pick or dismiss twice. */
    private var dismissInProgress = false
    /** Invalidates an old end-action when a new show/hide wins the race. */
    private var presentationGeneration = 0L

    /** Any interaction: the caller restarts its auto-hide countdown. */
    var onInteraction: (() -> Unit)? = null

    val isShowing: Boolean
        get() = visibility == VISIBLE

    private val displayTypeface = TvTokens.Type.display(context)
    private val uiTypeface = TvTokens.Type.ui(context)
    private val uiMediumTypeface = TvTokens.Type.uiMedium(context)

    init {
        visibility = GONE
        isFocusable = false

        // The film keeps the left of the frame. The scrim only darkens what the rail sits over,
        // so the picture is dimmed rather than replaced.
        scrim = View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.TRANSPARENT, TvTokens.Color.Black.copy(alpha = 0.32f).toArgb()),
            )
        }
        addView(
            scrim,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(42), dp(24), dp(42))
            background = GradientDrawable().apply {
                setColor(TvTokens.Color.Slate.copy(alpha = 0.88f).toArgb())
                setStroke(dp(1), TvTokens.Color.Text.copy(alpha = 0.12f).toArgb())
            }
        }
        val width = dp(TvTokens.Geometry.MoreRailWidthDp)
        addView(
            panel,
            LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END),
        )

        titleView = TextView(context).apply {
            setTextColor(context.getColor(R.color.tv_text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, TvTokens.designTextPx(context, TITLE_SP))
            setTypeface(displayTypeface)
            includeFontPadding = false
        }
        panel.addView(
            titleView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(20) },
        )

        rowsView = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scroller = ScrollView(context).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            addView(
                rowsView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        panel.addView(
            scroller,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
    }

    /**
     * Raises the rail. [onDismiss] runs on BACK and after a pick, so the caller can put focus back
     * where it belongs — a rail that closes onto nothing leaves the next D-pad press landing
     * nowhere at all.
     */
    fun show(title: String, sections: List<Section>, onDismiss: () -> Unit) {
        presentationGeneration += 1L
        dismissInProgress = false
        panel.animate().cancel()
        scrim.animate().cancel()
        this.onDismiss = onDismiss
        titleView.text = title
        rowsView.removeAllViews()

        var firstRow: View? = null
        var selectedRow: View? = null

        sections.forEach { section ->
            if (section.rows.isEmpty()) return@forEach
            section.heading?.let { heading ->
                rowsView.addView(
                    headingView(heading),
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(18); bottomMargin = dp(6) },
                )
            }
            section.rows.forEach { row ->
                val view = rowView(row)
                rowsView.addView(
                    view,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = dp(4) },
                )
                if (firstRow == null) firstRow = view
                if (row.selected && selectedRow == null) selectedRow = view
            }
        }

        visibility = VISIBLE
        if (transitionsDisabled()) {
            panel.translationX = 0f
            scrim.alpha = 1f
        } else {
            panel.translationX = panelWidth().toFloat()
            scrim.alpha = 0f
            panel.animate().translationX(0f).setDuration(TvTokens.Motion.MoreRailMillis).start()
            scrim.animate().alpha(1f).setDuration(TvTokens.Motion.MoreRailMillis).start()
        }
        // Post it: a freshly-added view cannot take focus until it has been laid out.
        post { (selectedRow ?: firstRow)?.requestFocus() }
    }

    fun hide() {
        presentationGeneration += 1L
        dismissInProgress = false
        panel.animate().cancel()
        scrim.animate().cancel()
        if (!isShowing) return
        visibility = GONE
        rowsView.removeAllViews()
        onDismiss = null
        panel.translationX = 0f
        scrim.alpha = 1f
    }

    /**
     * BACK closes the rail rather than reaching the Activity, where it would stop playback. Every
     * other key is fed to the focused row.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!isShowing) return super.dispatchKeyEvent(event)
        if (dismissInProgress) return true
        if (event.action == KeyEvent.ACTION_DOWN) onInteraction?.invoke()
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            dismiss()
            return true
        }
        // Swallow the DOWN half too, or the Activity sees a BACK it did not get the UP for.
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return true
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
        ) {
            val focused = findFocus() as? TextView
            val row = focused?.tag as? Row
            val direction = if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
            if (row?.onStep != null) {
                row.onStep.invoke(direction)
                focused.text = rowText(row)
                focused.contentDescription = focused.text
                onInteraction?.invoke()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Closes with the same reverse transition for BACK and CENTER. Picker activation deliberately
     * runs after the rail is gone, so a launched hand-off cannot race a still-focusable rail.
     */
    private fun dismiss(activation: (() -> Unit)? = null) {
        if (!isShowing || dismissInProgress) return
        dismissInProgress = true
        val callback = onDismiss
        val generation = ++presentationGeneration
        val finish = {
            if (isShowing && presentationGeneration == generation && dismissInProgress) {
                hide()
                activation?.invoke()
                callback?.invoke()
            }
        }
        if (transitionsDisabled()) {
            finish()
            return
        }
        panel.animate().cancel()
        scrim.animate().cancel()
        panel.animate()
            .translationX(panelWidth().toFloat())
            .setDuration(TvTokens.Motion.MoreRailMillis)
            .withEndAction {
                finish()
            }
            .start()
        scrim.animate().alpha(0f).setDuration(TvTokens.Motion.MoreRailMillis).start()
    }

    private fun headingView(text: String): TextView = TextView(context).apply {
        this.text = text.uppercase()
        setTextColor(context.getColor(R.color.tv_text_tertiary))
        setTextSize(TypedValue.COMPLEX_UNIT_PX, TvTokens.designTextPx(context, HEADING_SP))
        setTypeface(uiMediumTypeface)
        letterSpacing = 0.09f
        includeFontPadding = false
    }

    private fun rowView(row: Row): TextView = TextView(context).apply {
        text = rowText(row)
        contentDescription = text
        tag = row
        setTextColor(context.getColor(R.color.tv_control_text))
        setTextSize(TypedValue.COMPLEX_UNIT_PX, TvTokens.designTextPx(context, ROW_SP))
        setTypeface(if (row.selected) uiMediumTypeface else uiTypeface)
        includeFontPadding = false
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(ROW_HEIGHT_DP)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        background = rowBackground(focused = false)
        setOnFocusChangeListener { view, hasFocus ->
            view.background = rowBackground(hasFocus)
            (view as TextView).setTextColor(
                context.getColor(
                    if (hasFocus) R.color.tv_control_text_focused else R.color.tv_control_text,
                ),
            )
        }
        setOnClickListener {
            onInteraction?.invoke()
            dismiss(row.onPick)
        }
    }

    private fun rowText(row: Row): String = buildString {
        append(if (row.selected) "✓  " else "    ")
        append(row.label)
        (row.valueProvider?.invoke() ?: row.detail)?.takeIf(String::isNotBlank)?.let { append("  ·  ").append(it) }
    }

    private fun rowBackground(focused: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(
            if (focused) context.getColor(R.color.tv_accent) else TvTokens.Color.Text.copy(alpha = 0.04f).toArgb(),
        )
    }

    private fun dp(value: Int): Int = TvTokens.designUnit(context, value)

    private fun panelWidth(): Int = panel.width.takeIf { it > 0 } ?: panel.layoutParams.width

    private fun transitionsDisabled(): Boolean =
        android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.TRANSITION_ANIMATION_SCALE,
            1f,
        ) == 0f

    private companion object {
        const val TITLE_SP = 48f
        const val HEADING_SP = 11f
        const val ROW_SP = 20f
        const val ROW_HEIGHT_DP = 68
    }
}
