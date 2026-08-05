package com.fourseveneightnine.tv.ui.paste

import android.app.Activity
import android.content.Context
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * The "paste a URL, play it" surface on the TV receiver. Built with plain views (no XML) to match
 * the rest of the Activity, and dp-sized for 10-foot legibility. Not tested through JUnit — this
 * file is view construction only; the logic is in [PasteUrlPolicy], which has its own tests.
 */
internal object PasteUrlDialog {

    fun show(
        activity: Activity,
        onPlay: (playableUrl: String) -> Unit,
        onNeedsResolver: () -> Unit,
    ) {
        val ctx: Context = activity
        val density = ctx.resources.displayMetrics.density

        val input = EditText(ctx).apply {
            hint = "Paste a URL…"
            setHintTextColor(ctx.getColor(android.R.color.tertiary_text_dark))
            setTextColor(ctx.getColor(android.R.color.primary_text_dark))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            // The Fire TV clipboard paste UI reads focus-name and content-desc.
            contentDescription = "Video URL"
            isSingleLine = true
        }

        val hint = TextView(ctx).apply {
            text = "Direct MP4, M3U8, or an Einthusan link."
            setTextColor(ctx.getColor(android.R.color.secondary_text_dark))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (16 * density).toInt(), (24 * density).toInt(), 0)
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(
                input,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                hint,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = (8 * density).toInt() },
            )
        }

        val dialog = AlertDialog.Builder(activity, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("Paste URL to play")
            .setView(container)
            .setPositiveButton("Play") { _, _ ->
                val decision = PasteUrlPolicy.decide(input.text?.toString().orEmpty())
                when (decision.verdict) {
                    PasteVerdict.DirectMedia -> decision.playableUrl?.let(onPlay)
                    PasteVerdict.EinthusanWatchPage -> onNeedsResolver()
                    PasteVerdict.Unsupported -> Unit
                }
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener { input.requestFocus() }
        dialog.show()
    }
}
