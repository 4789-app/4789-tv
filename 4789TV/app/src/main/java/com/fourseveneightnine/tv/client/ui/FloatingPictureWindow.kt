package com.fourseveneightnine.tv.client.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

@android.annotation.SuppressLint("ViewConstructor")
@android.annotation.TargetApi(34)
internal class FloatingPictureWindow(
    context: Context,
    corner: Int,
    private var size: PictureSize,
    private val onResize: (PictureSize) -> Unit,
    private val onMove: (Int) -> Unit,
    onGoToVideo: () -> Unit,
    onSurfaceAvailable: (FloatingPictureWindow, Surface) -> Unit,
    onSurfaceLost: (FloatingPictureWindow) -> Unit,
) : FrameLayout(context) {
    // Composite the video with this window's controls. A SurfaceView punches a separate
    // hole through the translucent overlay, which can expose the app beneath on TV hardware.
    private val texture = TextureView(context)
    val stage = VideoStage(context, pictureView = texture).apply { setPlayerVisible(true) }
    var videoSurface: Surface? = null
        private set
    val videoWidth: Int get() = texture.width
    val videoHeight: Int get() = texture.height
    private val manager = context.getSystemService(WindowManager::class.java)
    private val params = WindowManager.LayoutParams(0, 0, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        HIDDEN_FLAGS, PixelFormat.TRANSLUCENT).apply {
        gravity = pictureGravity(corner)
        alpha = 0f
        title = "4789 floating video"
    }
    private var pictureReady = false
    private var pictureHidden = false
    private var controlsFocused = false
    private var shown = false
    private var positionBeforeMove: Int? = null
    private val moveHint = TextView(context).apply {
        text = "Arrows move · OK saves"
        textSize = 16f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = shape(Color.rgb(22, 24, 28), radius = dp(8))
        isFocusableInTouchMode = true
        visibility = GONE
    }
    private val menu = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(6), dp(6), dp(6), dp(6))
        background = shape(Color.rgb(22, 24, 28), radius = dp(10))
        visibility = GONE
    }
    private val sizeButtons = PictureSize.entries.associateWith { option ->
        menuButton(option.label) {
            size = option
            onResize(option)
            applySize()
            manager.updateViewLayout(this, params)
            showMenu()
        }.also(menu::addView)
    }

    init {
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                val output = Surface(texture)
                videoSurface = output
                onSurfaceAvailable(this@FloatingPictureWindow, output)
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                val output = videoSurface
                videoSurface = null
                onSurfaceLost(this@FloatingPictureWindow)
                // The player detaches on Main before we retire its output and texture.
                Handler(Looper.getMainLooper()).post {
                    output?.release()
                    texture.release()
                }
                return false
            }
        }
        addView(stage, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        menu.addView(menuButton("Move picture", ::startMove))
        menu.addView(menuButton("Go to video", onGoToVideo))
        addView(menu, LayoutParams(dp(166), 0, Gravity.END or Gravity.BOTTOM).apply {
            setMargins(dp(8), dp(8), dp(8), dp(8))
        })
        addView(moveHint, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(10) })
    }

    fun show() {
        applySize()
        manager.addView(this, params)
        shown = true
    }

    fun remove() {
        if (!shown) return
        shown = false
        manager.removeViewImmediate(this)
    }

    fun revealPicture() {
        if (!shown || pictureReady) return
        pictureReady = true
        updateWindowVisibility()
    }

    fun setPictureHidden(hidden: Boolean) {
        if (pictureHidden == hidden) return
        pictureHidden = hidden
        if (hidden && controlsFocused) setControlsFocused(false)
        else updateWindowVisibility()
    }

    fun toggleControls(): Boolean {
        if (!pictureReady) return false
        setControlsFocused(!controlsFocused)
        return true
    }

    private fun setControlsFocused(focused: Boolean) {
        controlsFocused = focused
        if (focused) showMenu() else {
            if (positionBeforeMove != null) finishMove(save = false)
            menu.visibility = GONE
            clearFocus()
        }
        updateWindowVisibility()
    }

    private fun updateWindowVisibility() {
        val visible = pictureReady && !pictureHidden
        params.alpha = if (visible) 1f else 0f
        params.flags = when {
            !visible -> HIDDEN_FLAGS
            controlsFocused -> FOCUSED_FLAGS
            else -> PASSIVE_FLAGS
        }
        if (shown) manager.updateViewLayout(this, params)
    }

    // A visible overlay owns touches on the area it covers. Remote keys can still reach the app beneath it.
    override fun onTouchEvent(event: MotionEvent): Boolean =
        !controlsFocused || super.onTouchEvent(event)

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus && controlsFocused && shown && isAttachedToWindow) setControlsFocused(false)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (positionBeforeMove != null && handleMoveKey(event)) return true
        if (controlsFocused && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                setControlsFocused(false)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun startMove() {
        positionBeforeMove = params.gravity
        menu.visibility = GONE
        moveHint.visibility = VISIBLE
        moveHint.requestFocus()
    }

    private fun handleMoveKey(event: KeyEvent): Boolean {
        val current = params.gravity
        val next = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN ->
                pictureGravity(pictureCornerAfterKey(pictureCorner(current), event.keyCode))
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BACK -> {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                    finishMove(save = event.keyCode != KeyEvent.KEYCODE_BACK)
                }
                return true
            }
            else -> return false
        }
        if (event.action == KeyEvent.ACTION_DOWN && next != current) {
            params.gravity = next
            manager.updateViewLayout(this, params)
        }
        return true
    }

    private fun finishMove(save: Boolean) {
        if (save) onMove(pictureCorner(params.gravity))
        else {
            params.gravity = requireNotNull(positionBeforeMove)
            manager.updateViewLayout(this, params)
        }
        positionBeforeMove = null
        moveHint.visibility = GONE
        if (controlsFocused) showMenu()
    }

    private fun showMenu() {
        menu.visibility = VISIBLE
        sizeButtons.forEach { (option, button) ->
            button.isSelected = option == size
            button.text = if (option == size) "${option.label}  ✓" else option.label
        }
        sizeButtons.getValue(size).requestFocus()
    }

    private fun applySize() {
        val bounds = manager.currentWindowMetrics.bounds
        val margin = bounds.width() / 32
        params.width = minOf(bounds.width() * size.widthPercent / 100, (bounds.height() - margin * 2) * 16 / 9)
        params.height = params.width * 9 / 16
        params.x = margin
        params.y = margin
        val menuParams = menu.layoutParams
        menuParams.height = minOf(dp(192), (params.height - dp(16)).coerceAtLeast(1))
        menu.layoutParams = menuParams
    }

    private fun menuButton(label: String, action: () -> Unit) = Button(context).apply {
        text = label
        textSize = 16f
        isAllCaps = false
        isFocusableInTouchMode = true
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        minHeight = 0
        minimumHeight = 0
        layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        setPadding(dp(10), 0, dp(4), 0)
        setTextColor(Color.WHITE)
        background = buttonBackground()
        setOnClickListener { action() }
    }

    private fun buttonBackground() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), shape(Color.rgb(76, 81, 91), Color.WHITE, dp(7)))
        addState(intArrayOf(), shape(Color.rgb(32, 35, 41), radius = dp(7)))
    }

    private fun shape(color: Int, stroke: Int? = null, radius: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
        if (stroke != null) setStroke(dp(2), stroke)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val FOCUSED_FLAGS =
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        const val PASSIVE_FLAGS =
            FOCUSED_FLAGS or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        const val HIDDEN_FLAGS =
            PASSIVE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    }
}

internal enum class PictureSize(val label: String, val widthPercent: Int) {
    Small("Small", 33),
    Large("Large", 42),
    ExtraLarge("Extra large", 52);

    val widthFraction: Float get() = widthPercent / 100f
}

internal fun pictureGravity(corner: Int): Int =
    (if (corner >= 2) Gravity.BOTTOM else Gravity.TOP) or
        (if (corner in 1..2) Gravity.LEFT else Gravity.RIGHT)

internal fun pictureCorner(gravity: Int): Int = when (gravity) {
    Gravity.TOP or Gravity.LEFT -> 1
    Gravity.BOTTOM or Gravity.LEFT -> 2
    Gravity.BOTTOM or Gravity.RIGHT -> 3
    else -> 0
}

internal fun pictureCornerAfterKey(corner: Int, keyCode: Int): Int = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_LEFT -> if (corner >= 2) 2 else 1
    KeyEvent.KEYCODE_DPAD_RIGHT -> if (corner >= 2) 3 else 0
    KeyEvent.KEYCODE_DPAD_UP -> if (corner in 1..2) 1 else 0
    KeyEvent.KEYCODE_DPAD_DOWN -> if (corner in 1..2) 2 else 3
    else -> corner
}
