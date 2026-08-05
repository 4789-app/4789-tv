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
import com.fourseveneightnine.tv.R
import kotlin.math.roundToInt

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
 * It owns the D-pad while it is up: UP/DOWN walk the rows, CENTER picks, BACK dismisses. Focus is
 * placed on the row that is already selected, so a viewer who opened it to check rather than to
 * change can read the answer and press BACK.
 */
internal class OptionRailView(context: Context) : FrameLayout(context) {

    /** One choosable line. [detail] is the quiet half — a codec, a channel count, a source. */
    data class Row(
        val label: String,
        val detail: String? = null,
        val selected: Boolean = false,
        val onPick: () -> Unit,
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

    /** Any interaction: the caller restarts its auto-hide countdown. */
    var onInteraction: (() -> Unit)? = null

    val isShowing: Boolean
        get() = visibility == VISIBLE

    private val jostMedium: android.graphics.Typeface =
        android.graphics.Typeface.Builder(context.assets, "Jost-Variable.ttf")
            .setFontVariationSettings("'wght' 500")
            .build()
    private val jostRegular: android.graphics.Typeface =
        android.graphics.Typeface.Builder(context.assets, "Jost-Variable.ttf")
            .setFontVariationSettings("'wght' 400")
            .build()

    init {
        visibility = GONE
        isFocusable = false

        // The film keeps the left of the frame. The scrim only darkens what the rail sits over,
        // so the picture is dimmed rather than replaced.
        scrim = View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.TRANSPARENT, Color.argb(150, 15, 21, 27)),
            )
        }
        addView(
            scrim,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(36), dp(28), dp(36))
            background = GradientDrawable().apply {
                setColor(Color.argb(240, 20, 24, 28))
            }
        }
        val width = (context.resources.displayMetrics.widthPixels * PANEL_WIDTH_FRACTION).roundToInt()
        addView(
            panel,
            LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END),
        )

        titleView = TextView(context).apply {
            setTextColor(context.getColor(R.color.tv_text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SP)
            setTypeface(jostMedium)
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
        // Post it: a freshly-added view cannot take focus until it has been laid out.
        post { (selectedRow ?: firstRow)?.requestFocus() }
    }

    fun hide() {
        if (!isShowing) return
        visibility = GONE
        rowsView.removeAllViews()
        onDismiss = null
    }

    /**
     * BACK closes the rail rather than reaching the Activity, where it would stop playback. Every
     * other key is fed to the focused row.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!isShowing) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) onInteraction?.invoke()
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            dismiss()
            return true
        }
        // Swallow the DOWN half too, or the Activity sees a BACK it did not get the UP for.
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.dispatchKeyEvent(event)
    }

    private fun dismiss() {
        val callback = onDismiss
        hide()
        callback?.invoke()
    }

    private fun headingView(text: String): TextView = TextView(context).apply {
        this.text = text.uppercase()
        setTextColor(context.getColor(R.color.tv_text_tertiary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, HEADING_SP)
        setTypeface(jostMedium)
        letterSpacing = 0.09f
        includeFontPadding = false
    }

    private fun rowView(row: Row): TextView = TextView(context).apply {
        text = buildString {
            append(if (row.selected) "✓  " else "    ")
            append(row.label)
            row.detail?.takeIf(String::isNotBlank)?.let { append("  ·  ").append(it) }
        }
        setTextColor(context.getColor(R.color.tv_control_text))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ROW_SP)
        setTypeface(if (row.selected) jostMedium else jostRegular)
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
            val callback = onDismiss
            hide()
            row.onPick()
            callback?.invoke()
        }
    }

    private fun rowBackground(focused: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(
            if (focused) context.getColor(R.color.tv_accent) else Color.argb(10, 255, 255, 255),
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val PANEL_WIDTH_FRACTION = 0.34f
        const val TITLE_SP = 21f
        const val HEADING_SP = 12f
        const val ROW_SP = 17f
        const val ROW_HEIGHT_DP = 48
    }
}
