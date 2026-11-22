package com.fourseveneightnine.tv.client.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.fourseveneightnine.tv.client.appGraph

/** A remote-friendly window onto the real X site. WebView owns cookies and sign-in. */
internal class XBrowseActivity : AppCompatActivity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled") // X renders its timeline, search and sign-in in JavaScript.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val toolbar = LinearLayout(this)
        val margin = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(margin, margin, margin, 0)
            setBackgroundColor(Color.rgb(15, 17, 21))
            addView(toolbar)
        }
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
            toolbar.addView(this)
        }

        val timeline = button("X timeline") { web.loadUrl("$X_ORIGIN/home") }
        val search = button("Search posts", ::searchPosts)
        button("PiP controls") { appGraph.pictureInPicture.toggleCrossAppControls() }
        button("Full screen") { appGraph.pictureInPicture.goToVideo() }
        web = object : WebView(this) {
            override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP && scrollY == 0 && !isInTouchMode) {
                    return search.requestFocus()
                }
                return super.onKeyDown(keyCode, event)
            }
        }.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url
                    return request.isForMainFrame &&
                        (url.scheme != "https" || url.host !in setOf("x.com", "www.x.com"))
                }
            }
        }
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        timeline.requestFocus()
        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null)
            web.loadUrl("$X_ORIGIN/home")
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })
    }

    private fun searchPosts() {
        val input = EditText(this).apply {
            hint = "Search posts on X"
            isSingleLine = true
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Search posts")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Search") { _, _ ->
                val query = input.text.toString().trim()
                if (query.isNotEmpty()) {
                    val url = Uri.parse("$X_ORIGIN/search").buildUpon()
                        .appendQueryParameter("q", query).appendQueryParameter("src", "typed_query").build()
                    web.loadUrl(url.toString())
                    web.requestFocus()
                }
            }.create()
        input.requestFocus()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        web.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    private companion object { const val X_ORIGIN = "https://x.com" }
}
