package com.fourseveneightnine.tv.ui

import android.content.Context

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.fourseveneightnine.tv.R
import com.fourseveneightnine.tv.player.upscale.UpscaleMode
import kotlin.math.roundToInt

/**
 * The on-TV player UI: title, a hairline scrubber, and a short row of controls. Built
 * programmatically like the rest of this Activity's views, so there is no layout XML to keep in
 * sync.
 *
 * Three rules drive the look.
 *
 * Nothing is a solid slab: every surface is a wash over the picture, so the film stays the
 * brightest thing on screen. The focused control INVERTS — solid accent, dark text — rather than
 * merely brightening, because a lighter shade of grey is not a focus indicator at television
 * viewing distance.
 *
 * And every control SAYS WHAT IT IS AND WHAT IT IS SET TO. A row of bare glyphs cannot answer
 * "what speed am I on" without changing the speed to find out, so each control carries its own
 * value: `Audio · ENG 5.1`, `Subs · Off`, `More · 1x · Fit`. Only four controls sit on the bar;
 * speed, picture size, upscaling and hand-off live one press deeper behind `More`, whose face
 * still prints their current values. Nothing became unreachable, and nothing stayed invisible.
 *
 * Scrubbing is the part that has to be got right on a remote: LEFT/RIGHT move a *pending* position
 * that only the bar knows about, accelerating while the key is held, and the seek is committed when
 * the viewer lets go (or presses CENTER). That is what keeps one scrub from becoming a dozen seeks
 * — the receiver-side [com.fourseveneightnine.tv.player.SeekCoalescingPolicy] is the second net
 * under the same problem. The bar STAYS ON SCREEN throughout: a scrub is exactly the moment a
 * viewer needs to see where the jump lands against the whole film, and an earlier build hid the
 * panel and offered a bare signed number instead.
 */
internal class PlayerControlsView(
    context: Context,
    private val listener: Listener,
) : FrameLayout(context) {

    interface Listener {
        fun onPlayPauseRequested()
        fun onSeekRequested(positionMillis: Long)
        fun onAudioTracksRequested()
        fun onSubtitleTracksRequested()
        fun onResizeModeChanged(mode: VideoResizeMode)
        fun onSpeedChanged(speed: Float)

        /** The receiver-level SGSR upscale mode; persisted by the caller. */
        fun onUpscaleModeChanged(mode: UpscaleMode)

        /**
         * Open the secondary options rail: speed, picture size, upscaling and hand-off.
         *
         * These used to be four more glyphs on the bar. They are things a viewer sets once and
         * then forgets, so they cost the bar four targets to serve a rare press.
         */
        fun onMoreRequested()

        /**
         * Hand this stream to another player app on this television.
         *
         * Until the bar carried it, the only way to reach a handoff was to WAIT FOR PLAYBACK TO
         * FAIL — the buttons live on the error overlay. A viewer who simply wants Dolby Vision
         * handled by a player that can do it had no way to ask. It now lives on the `More` rail.
         */
        fun onHandoffRequested()

        /** Any interaction: the caller restarts its auto-hide countdown. */
        fun onInteraction()
    }

    private val titleView: TextView
    private val metaView: TextView
    private val qualityBadgeView: TextView
    private val positionView: TextView
    private val remainingView: TextView
    private val seekBar: SlimSeekBar
    private val scrubberRow: LinearLayout
    private val playPausePill: TextView
    private val audioPill: TextView
    private val subtitlePill: TextView
    private val morePill: TextView

    private var durationMillis = 0L
    private var positionMillis = 0L
    private var bufferedMillis = 0L
    private var isPlaying = false

    /**
     * When [positionMillis] was last read from the player, on the monotonic clock.
     *
     * The player is polled on a slow cadence — a snapshot is a hop to the player thread, and on the
     * mpv path a socket round trip. Painting that reading raw makes the clock advance in visible
     * steps. Between polls the bar advances the playhead itself from this stamp and the known rate,
     * so the clock ticks like a clock and the poll only ever corrects it.
     */
    private var positionSampledAtUptime = 0L

    /** Where the current scrub started, so the HUD can show the jump instead of the destination. */
    private var scrubAnchorMillis = 0L

    /** Non-null while a scrub is in progress; the bar shows this instead of the live playhead. */
    private var pendingScrubMillis: Long? = null
    /** When this hold last actually moved the playhead — the throttle's other half. */
    private var lastScrubTickAt = 0L

    // v2: time-window coalescing — Fire TV remotes send separate DOWN/UP pairs, not repeats.
    // A rapid succession of presses is one gesture; the commit fires after the window expires.
    private val scrubHandler = Handler(Looper.getMainLooper())
    private var scrubGestureStartedAt = 0L
    private var scrubCommitPending = false

    private val panel: LinearLayout

    private val topHeaderView: LinearLayout
    private val scrubDeltaView: TextView

    private var resizeMode = VideoResizeMode.Fit
    private var speed = 1.0f
    private var upscaleMode = UpscaleMode.OFF
    private var audioSummary: String? = null
    private var subtitleSummary: String? = null

    // v2: Jost Variable — cinema display voice, single font family throughout
    private val jostTypeface: android.graphics.Typeface =
        android.graphics.Typeface.Builder(context.assets, "Jost-Variable.ttf")
            .setFontVariationSettings("'wght' 400")
            .build()
    private val jostMedium: android.graphics.Typeface =
        android.graphics.Typeface.Builder(context.assets, "Jost-Variable.ttf")
            .setFontVariationSettings("'wght' 500")
            .build()

    init {
        panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(22), dp(28), dp(22))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                // v2: luminance-elevated card — no stroke, warm #1a1f24 at ~78% alpha
                setColor(Color.argb(200, 26, 31, 36))
            }
        }
        // The bar spans the title-safe area rather than a fixed fraction of the panel. A rail two
        // thirds of the screen wide cannot resolve a single minute of a two-hour film; this one
        // can, and it puts the times where the eye already expects to find them.
        val safeInset = safeAreaInsetPx()
        val panelParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ).apply {
            leftMargin = safeInset
            rightMargin = safeInset
            bottomMargin = safeInset
        }
        addView(panel, panelParams)

        topHeaderView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(dp(24), dp(14), dp(24), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                // v2: elevated card, no stroke
                setColor(Color.argb(195, 26, 31, 36))
            }
        }
        addView(
            topHeaderView,
            LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ).apply {
                topMargin = safeInset
                leftMargin = safeInset
            },
        )

        titleView = label(TITLE_SP, R.color.tv_text_primary).apply {
            // v2: Jost Medium (wght 500) — cinema display voice
            setTypeface(jostMedium)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            letterSpacing = -0.01f
        }
        topHeaderView.addView(
            titleView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        metaView = label(META_SP, R.color.tv_text_secondary).apply {
            setTypeface(jostTypeface)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            letterSpacing = 0.02f
        }
        topHeaderView.addView(
            metaView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) },
        )
        qualityBadgeView = label(11f, R.color.tv_accent).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            // v2: Jost Medium
            setTypeface(jostMedium)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(6).toFloat()
                // v2: glass badge — warm canvas bg, accent-muted stroke (0.22 alpha)
                setColor(Color.argb(140, 20, 24, 28))
                setStroke(dp(1), Color.argb(56, 0, 200, 232))
            }
            setTextColor(context.getColor(R.color.tv_accent))
        }
        topHeaderView.addView(
            qualityBadgeView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )

        // The bar is focusable; the times either side are not. A remote should reach the scrubber
        // in one press, not tab through labels.
        scrubberRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            setOnFocusChangeListener { _, hasFocus -> renderScrubberFocus(hasFocus) }
            setOnKeyListener { _, keyCode, event -> handleScrubberKey(keyCode, event) }
        }
        panel.addView(scrubberRow, rowParams(topMargin = 0))

        positionView = timeLabel(Gravity.START)
        scrubberRow.addView(
            positionView,
            LinearLayout.LayoutParams(dp(TIME_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        seekBar = SlimSeekBar(context)
        scrubberRow.addView(
            seekBar,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(18)
                rightMargin = dp(18)
            },
        )

        // Time still to run, not the runtime. "How much is left" is the question a viewer actually
        // asks mid-film; the runtime is on the detail screen and never changes.
        remainingView = timeLabel(Gravity.END)
        scrubberRow.addView(
            remainingView,
            LinearLayout.LayoutParams(dp(TIME_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        val pills = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        panel.addView(pills, rowParams(topMargin = dp(18)))

        playPausePill = pill { listener.onPlayPauseRequested() }
        audioPill = pill { listener.onAudioTracksRequested() }
        subtitlePill = pill { listener.onSubtitleTracksRequested() }
        morePill = pill { listener.onMoreRequested() }

        pills.addView(playPausePill, pillParams())
        pills.addView(audioPill, pillParams())
        pills.addView(subtitlePill, pillParams())
        // A weighted gap, so `More` sits at the far end of the bar and the three controls a viewer
        // reaches for stay together under the left hand of the D-pad.
        pills.addView(
            View(context),
            LinearLayout.LayoutParams(0, 1, 1f),
        )
        pills.addView(morePill, pillParams().apply { rightMargin = 0 })

        // The jump built up so far, printed under the thumb it belongs to. A delta alone answers
        // "how far", never "where" — the thumb, the accent fill and the ghost tick answer "where".
        scrubDeltaView = label(HUD_DELTA_SP, R.color.tv_accent).apply {
            setTypeface(jostMedium)
            gravity = Gravity.CENTER
            visibility = GONE
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(HUD_RADIUS_DP).toFloat()
                setColor(Color.argb(210, 20, 24, 28))
                setStroke(dp(1), context.getColor(R.color.tv_control_stroke))
            }
        }
        addView(
            scrubDeltaView,
            LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START,
            ),
        )

        isFocusable = false
        renderPills()
        renderTimes()
    }

    /** Called by the Activity whenever it has fresh playback state. */
    fun bind(
        title: String?,
        subtitle: String?,
        positionMillis: Long,
        durationMillis: Long,
        bufferedMillis: Long,
        isPlaying: Boolean,
    ) {
        titleView.text = title?.takeIf(String::isNotBlank) ?: context.getString(R.string.app_name)
        metaView.text = subtitle.orEmpty()
        metaView.visibility = if (metaView.text.isNullOrBlank()) GONE else VISIBLE
        this.durationMillis = durationMillis
        this.positionMillis = positionMillis
        this.positionSampledAtUptime = SystemClock.uptimeMillis()
        this.bufferedMillis = bufferedMillis
        this.isPlaying = isPlaying
        renderPills()
        renderTimes()
    }

    /**
     * The playhead as it stands right now: the last reading from the player, carried forward at the
     * current rate. Never runs past the end of the media, and never moves while paused.
     */
    fun livePositionMillis(): Long = PlayerControlsPolicy.interpolatedPosition(
        sampleMillis = positionMillis,
        sampledAtUptime = positionSampledAtUptime,
        nowUptime = SystemClock.uptimeMillis(),
        rate = if (isPlaying) speed else 0f,
        durationMillis = durationMillis,
    )

    /** The current audio/subtitle choices, so the controls say what is on without being opened. */
    fun bindTrackSummary(audioLabel: String?, subtitleLabel: String?) {
        audioSummary = audioLabel?.takeIf(String::isNotBlank)
        subtitleSummary = subtitleLabel?.takeIf(String::isNotBlank)
        renderPills()
    }

    /** The receiver's persisted choice, pushed in whenever the bar is raised. */
    fun setUpscaleMode(mode: UpscaleMode) {
        if (upscaleMode == mode) return
        upscaleMode = mode
        renderPills()
    }

    /** Set from the options rail, so the `More` summary and the local scrub rate stay honest. */
    fun setResizeMode(mode: VideoResizeMode) {
        if (resizeMode == mode) return
        resizeMode = mode
        renderPills()
    }

    fun setSpeed(value: Float) {
        if (speed == value) return
        // The interpolated playhead runs at this rate; re-stamp so the change does not retroactively
        // rewrite the seconds already counted at the old one.
        positionMillis = livePositionMillis()
        positionSampledAtUptime = SystemClock.uptimeMillis()
        speed = value
        renderPills()
    }

    fun resizeMode(): VideoResizeMode = resizeMode

    fun speed(): Float = speed

    fun upscaleMode(): UpscaleMode = upscaleMode

    /** Chapter boundaries as 0-1 fractions of the runtime; empty when the file declares none. */
    fun setChapters(fractions: List<Float>) {
        seekBar.chapters = fractions
    }

    fun setStreamQualityBadge(qualityText: String?) {
        if (qualityText.isNullOrBlank()) {
            qualityBadgeView.visibility = GONE
            return
        }
        qualityBadgeView.text = qualityText
        qualityBadgeView.visibility = VISIBLE
    }

    /** Focus lands on the scrubber, so the first LEFT/RIGHT after opening the bar scrubs. */
    fun takeFocus() {
        scrubberRow.requestFocus()
    }

    /** Drops any half-finished scrub; used when the controls hide or the title changes. */
    fun cancelScrub() {
        scrubHandler.removeCallbacks(scrubCommitRunnable)
        scrubCommitPending = false
        pendingScrubMillis = null
        scrubAnchorMillis = 0L
        scrubGestureStartedAt = 0L
        seekBar.scrubbing = false
        seekBar.ghost = null
        scrubDeltaView.visibility = GONE
        renderTimes()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postDelayed(ticker, TICK_MILLIS)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        scrubHandler.removeCallbacks(scrubCommitRunnable)
        super.onDetachedFromWindow()
    }

    /**
     * Advances the clock and the rail between player polls. Cheap on purpose: no player hop, no
     * allocation beyond the formatted strings, and it does nothing at all while the bar is down or
     * a scrub is holding the display.
     */
    private val ticker = object : Runnable {
        override fun run() {
            if (visibility == VISIBLE && pendingScrubMillis == null && isPlaying) renderTimes()
            postDelayed(this, TICK_MILLIS)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) listener.onInteraction()
        return super.dispatchKeyEvent(event)
    }

    /**
     * Keys reach the FOCUSED view, and a parent ViewGroup's own `onKeyDown` never runs while a
     * child holds focus — so the scrubber's remote handling lives on the scrubber itself.
     *
     * v2: time-window coalescing. Fire TV remotes send separate ACTION_DOWN/ACTION_UP pairs
     * when a D-pad key is held — repeatCount is always 0. Without coalescing, every UP commits
     * a separate seek, flooding the receiver. Instead, we treat rapid DOWN sequences as one
     * gesture: scrub on each DOWN, schedule a deferred commit on UP, and cancel it if another
     * scrubbing DOWN arrives within the coalescing window.
     */
    private fun handleScrubberKey(keyCode: Int, event: KeyEvent): Boolean {
        val isScrubKey = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_MEDIA_REWIND ||
            keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD

        if (event.action == KeyEvent.ACTION_UP) {
            if (!isScrubKey) return false
            val pending = pendingScrubMillis
            if (pending == null) return true
            // Don't commit yet — schedule it. If another scrubbing DOWN arrives
            // within the window, the pending commit is cancelled and the gesture continues.
            scheduleScrubCommit()
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                scrubGestureKey(event, forward = false)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                scrubGestureKey(event, forward = true)
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val pending = pendingScrubMillis
                if (pending != null) commitScrubNow(pending) else listener.onPlayPauseRequested()
                true
            }
            else -> false
        }
    }

    /**
     * Starts a scrub from outside the bar — the blind ±10s keys, which now raise the bar and move
     * the same pending position rather than firing a separate un-coalesced seek. One gesture model
     * for one pair of keys, whether the bar was already up or not.
     */
    fun nudgeScrub(stepMillis: Long) {
        if (!scrubberRow.isFocused) scrubberRow.requestFocus()
        if (scrubCommitPending) {
            scrubHandler.removeCallbacks(scrubCommitRunnable)
            scrubCommitPending = false
        }
        applyScrub(stepMillis)
        scheduleScrubCommit()
    }

    /**
     * v2: time-based scrubbing. Instead of relying on repeatCount (which Fire TV remotes don't
     * populate), we measure elapsed time since the gesture began. Each tick is clamped to the
     * 100ms cadence, and the rate accelerates the longer the key stays down.
     */
    private fun scrubGestureKey(event: KeyEvent, forward: Boolean): Boolean {
        val sign = if (forward) 1 else -1
        val now = event.eventTime

        // Cancel any pending commit — this DOWN continues the gesture.
        if (scrubCommitPending) {
            scrubHandler.removeCallbacks(scrubCommitRunnable)
            scrubCommitPending = false
        }

        // First DOWN of this gesture: record start and do the base step.
        if (pendingScrubMillis == null) {
            scrubGestureStartedAt = now
            lastScrubTickAt = now
            return applyScrub(sign * PlayerControlsPolicy.BASE_SCRUB_MILLIS)
        }

        // Subsequent DOWN: apply acceleration based on elapsed gesture time.
        val sinceLastTick = now - lastScrubTickAt
        if (sinceLastTick < PlayerControlsPolicy.SCRUB_TICK_MILLIS) return true
        lastScrubTickAt = now
        val gestureElapsed = now - scrubGestureStartedAt
        return applyScrub(sign * PlayerControlsPolicy.scrubTickMillis(gestureElapsed, sinceLastTick))
    }

    private fun applyScrub(stepMillis: Long): Boolean {
        val from = pendingScrubMillis
        if (from == null) scrubAnchorMillis = livePositionMillis()
        pendingScrubMillis = PlayerControlsPolicy.scrubTarget(
            from ?: livePositionMillis(),
            stepMillis,
            durationMillis,
        )
        seekBar.scrubbing = true
        // Where the film actually is, kept on the rail so the jump reads as a distance rather than
        // as a new absolute position with nothing to measure it against.
        seekBar.ghost = PlayerControlsPolicy.progressPermille(positionMillis, durationMillis) / 1000f
        renderTimes()
        return true
    }

    /** Schedule a deferred commit. Cancelled if another scrubbing DOWN arrives in time. */
    private fun scheduleScrubCommit() {
        if (scrubCommitPending) return
        scrubCommitPending = true
        scrubHandler.postDelayed(scrubCommitRunnable, COALESCE_WINDOW_MS)
    }

    private val scrubCommitRunnable = Runnable {
        scrubCommitPending = false
        val target = pendingScrubMillis ?: return@Runnable
        commitScrubNow(target)
    }

    private fun commitScrubNow(targetMillis: Long) {
        scrubHandler.removeCallbacks(scrubCommitRunnable)
        scrubCommitPending = false
        pendingScrubMillis = null
        scrubAnchorMillis = 0L
        scrubGestureStartedAt = 0L
        positionMillis = targetMillis
        positionSampledAtUptime = SystemClock.uptimeMillis()
        seekBar.scrubbing = false
        seekBar.ghost = null
        scrubDeltaView.visibility = GONE
        listener.onSeekRequested(targetMillis)
    }

    /** Called when the phone or an RPC drives a seek, so the bar previews it the same way. */
    fun showScrubPreview(targetMillis: Long) {
        if (scrubAnchorMillis == 0L) {
            scrubAnchorMillis = livePositionMillis()
        }
        pendingScrubMillis = targetMillis.coerceIn(0L, durationMillis.coerceAtLeast(targetMillis))
        seekBar.scrubbing = true
        seekBar.ghost = PlayerControlsPolicy.progressPermille(positionMillis, durationMillis) / 1000f
        renderTimes()
    }

    fun finishScrubPreview(targetMillis: Long) {
        pendingScrubMillis = null
        scrubAnchorMillis = 0L
        positionMillis = targetMillis
        positionSampledAtUptime = SystemClock.uptimeMillis()
        seekBar.scrubbing = false
        seekBar.ghost = null
        scrubDeltaView.visibility = GONE
        renderTimes()
    }

    /** Reports the CLAMPED jump, so hitting the end of the film stops the number running away. */
    private fun renderScrubDelta(target: Long, fraction: Float) {
        scrubDeltaView.text = PlayerControlsPolicy.formatSignedDelta(target - scrubAnchorMillis)
        // Sits directly above the thumb it describes. The seek bar is two levels down from this
        // FrameLayout, so its left edge is the sum of the offsets on the way — measured after
        // layout. On the frame where the bar has not been laid out yet there is no honest place to
        // put it, so it stays hidden rather than flashing at the left edge for one frame.
        val barWidth = seekBar.width
        if (barWidth <= 0 || panel.height <= 0) {
            scrubDeltaView.visibility = GONE
            return
        }
        scrubDeltaView.visibility = VISIBLE
        val barLeft = panel.x + scrubberRow.x + seekBar.x
        scrubDeltaView.measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        val half = scrubDeltaView.measuredWidth / 2f
        val wanted = barLeft + barWidth * fraction - half
        scrubDeltaView.translationX = wanted.coerceIn(0f, (width - scrubDeltaView.measuredWidth).toFloat())
        scrubDeltaView.translationY = -(height - panel.y) - dp(HUD_GAP_DP).toFloat()
    }

    private fun renderTimes() {
        val pending = pendingScrubMillis
        val shown = pending ?: livePositionMillis()
        positionView.text = PlayerControlsPolicy.formatTime(shown)
        remainingView.text = if (durationMillis > 0) {
            context.getString(
                R.string.controls_remaining,
                PlayerControlsPolicy.formatTime((durationMillis - shown).coerceAtLeast(0L)),
            )
        } else {
            context.getString(R.string.controls_live)
        }
        val fraction = PlayerControlsPolicy.progressPermille(shown, durationMillis) / 1000f
        seekBar.progress = fraction
        seekBar.buffered =
            PlayerControlsPolicy.progressPermille(bufferedMillis, durationMillis) / 1000f
        // A scrub in flight is not where the film is; say so rather than pretending it landed.
        positionView.setTextColor(
            context.getColor(if (pending != null) R.color.tv_accent else R.color.tv_text_primary),
        )
        if (pending != null) renderScrubDelta(pending, fraction) else scrubDeltaView.visibility = GONE
    }

    private fun renderScrubberFocus(hasFocus: Boolean) {
        seekBar.active = hasFocus
        positionView.alpha = if (hasFocus) 1f else 0.75f
        remainingView.alpha = if (hasFocus) 1f else 0.75f
        if (!hasFocus) cancelScrub()
    }

    /** Every control, with its current value on its face. */
    private fun renderPills() {
        playPausePill.text = if (isPlaying) {
            "⏸  " + context.getString(R.string.controls_pause)
        } else {
            "▶  " + context.getString(R.string.controls_resume)
        }
        audioPill.text = valueText(R.string.controls_audio, audioSummary)
        subtitlePill.text = valueText(R.string.controls_subtitles_short, subtitleSummary)
        morePill.text = valueText(R.string.controls_more, moreSummary())
    }

    /**
     * What `More` is hiding, printed on its face. Speed and picture size always; upscaling only
     * when it is doing something, because "Enhance Off" on every title is noise.
     */
    private fun moreSummary(): String {
        val parts = mutableListOf(
            PlayerControlsPolicy.formatSpeed(speed),
            context.getString(resizeMode.labelRes()),
        )
        if (upscaleMode != UpscaleMode.OFF) parts += context.getString(R.string.controls_enhance)
        return parts.joinToString(" · ")
    }

    /** `Subs` alone when nothing is chosen, `Subs · ENG` when something is. */
    private fun valueText(labelRes: Int, value: String?): CharSequence {
        val label = context.getString(labelRes)
        val trimmed = value?.trim().orEmpty()
        return if (trimmed.isEmpty()) label else "$label · $trimmed"
    }

    /**
     * A pill: rounded, translucent, hairline-stroked. Focus swaps it to solid accent with dark
     * text — a TextView, not an AppCompatButton, because the platform button drags a themed
     * rectangle and its own ripple/elevation along with it.
     */
    private fun pill(onClick: () -> Unit): TextView =
        TextView(context).apply {
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            minWidth = dp(PILL_MIN_WIDTH_DP)
            minHeight = dp(PILL_HEIGHT_DP)
            maxWidth = dp(PILL_MAX_WIDTH_DP)
            setPadding(dp(PILL_PADDING_H_DP), dp(10), dp(PILL_PADDING_H_DP), dp(10))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, PILL_SP)
            // v2: Jost Medium for pill labels
            setTypeface(jostMedium)
            background = pillBackground(focused = false)
            setTextColor(context.getColor(R.color.tv_control_text))
            setOnFocusChangeListener { view, hasFocus -> renderPillFocus(view as TextView, hasFocus) }
            setOnClickListener {
                listener.onInteraction()
                onClick()
            }
        }

    private fun renderPillFocus(pill: TextView, hasFocus: Boolean) {
        pill.background = pillBackground(hasFocus)
        pill.setTextColor(
            if (hasFocus) {
                context.getColor(R.color.tv_control_text_focused)
            } else {
                context.getColor(R.color.tv_control_text)
            },
        )
        // v2: 1.07× scale, 150ms — cinema calm focus, not cartoonish
        pill.animate()
            .scaleX(if (hasFocus) PILL_FOCUS_SCALE else 1.0f)
            .scaleY(if (hasFocus) PILL_FOCUS_SCALE else 1.0f)
            .setDuration(PILL_FOCUS_MILLIS)
            .start()
    }

    private fun pillBackground(focused: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(22).toFloat()
        setColor(
            // v2: unfocused — elevated warm at ~0.05 white; focused — solid accent
            if (focused) context.getColor(R.color.tv_accent) else Color.argb(13, 255, 255, 255)
        )
        setStroke(
            dp(if (focused) 2 else 1),
            // v2: unfocused border is subtle (0.06); focused uses accent
            if (focused) context.getColor(R.color.tv_accent) else Color.argb(15, 255, 255, 255)
        )
    }

    private fun timeLabel(gravityValue: Int): TextView =
        label(TIME_SP, R.color.tv_text_primary).apply {
            gravity = gravityValue
            // v2: Jost with tabular nums — no monospace needed
            setTypeface(jostTypeface)
            letterSpacing = 0.02f
        }

    private fun label(sizeSp: Float, colorRes: Int): TextView =
        TextView(context).apply {
            setTextColor(context.getColor(colorRes))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            includeFontPadding = false
        }

    private fun rowParams(topMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { this.topMargin = topMargin }

    private fun pillParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { rightMargin = dp(10) }

    /** 5% of the shorter edge: the title-safe margin every television overscan spec agrees on. */
    private fun safeAreaInsetPx(): Int {
        val metrics = context.resources.displayMetrics
        return (minOf(metrics.widthPixels, metrics.heightPixels) * SAFE_AREA_FRACTION).roundToInt()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val TITLE_SP = 23f
        const val META_SP = 14f
        const val TIME_SP = 14f
        const val PILL_SP = 15f
        const val TIME_WIDTH_DP = 84
        const val PILL_HEIGHT_DP = 44
        const val PILL_MIN_WIDTH_DP = 96

        /**
         * The widest a single control may grow. Roughly a fifth of a 960dp-wide television panel,
         * so even the longest track name leaves room for the controls that follow it.
         */
        const val PILL_MAX_WIDTH_DP = 240
        const val PILL_PADDING_H_DP = 20
        const val PILL_FOCUS_SCALE = 1.07f
        const val PILL_FOCUS_MILLIS = 150L

        const val HUD_DELTA_SP = 22f
        const val HUD_RADIUS_DP = 10
        /** Gap between the top of the bar and the delta chip riding above the thumb. */
        const val HUD_GAP_DP = 10

        const val SAFE_AREA_FRACTION = 0.05f

        /** How often the bar re-paints itself between player polls. */
        const val TICK_MILLIS = 250L

        /** v2: ms after last scrubbing UP before commit fires. Cancelled if another DOWN arrives. */
        const val COALESCE_WINDOW_MS = 280L
    }
}
