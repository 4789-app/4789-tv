package com.fourseveneightnine.tv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
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
    private val chapterView: TextView
    private val remainingView: TextView
    private val seekBar: SlimSeekBar
    private val scrubberRow: LinearLayout
    private val playPausePill: TextView
    private val audioPill: TextView
    private val subtitlePill: TextView
    private val morePill: TextView
    private val controlsRow: LinearLayout

    // Compact seek readout. It is text-only and sits in the safe top-right corner so the picture
    // is never covered by a fake preview card.
    private val seekPreviewView: LinearLayout
    private val seekTargetView: TextView
    private val seekDeltaView: TextView
    private val seekChapterView: TextView

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

    /** Non-null while a scrub is in progress; the bar shows this instead of the live playhead. */
    private var pendingScrubMillis: Long? = null
    /** When this hold last actually moved the playhead — the throttle's other half. */
    private var lastScrubTickAt = 0L

    // v2: time-window coalescing — Fire TV remotes send separate DOWN/UP pairs, not repeats.
    // A rapid succession of presses is one gesture; the commit fires after the window expires.
    private val scrubHandler = Handler(Looper.getMainLooper())
    private var scrubGestureStartedAt = 0L
    private var scrubCommitPending = false
    private var lastSeekCommittedAtUptime = 0L
    private var optimisticSeekTargetMillis: Long? = null
    private var mediaIdentity: String? = null
    private var chapterFractions: List<Float> = emptyList()

    private val panel: LinearLayout

    private val topHeaderView: LinearLayout

    private var resizeMode = VideoResizeMode.Fit
    private var speed = 1.0f
    private var upscaleMode = UpscaleMode.OFF
    private var audioSummary: String? = null
    private var subtitleSummary: String? = null
    private var afrSummary = "AFR AUTO"

    private val displayTypeface = TvTokens.Type.display(context)
    private val uiTypeface = TvTokens.Type.ui(context)
    private val uiMediumTypeface = TvTokens.Type.uiMedium(context)
    private val dataTypeface = TvTokens.Type.data(context)
    private val playPauseGlyph = PlayPauseGlyphDrawable(dp(20), TvTokens.Color.OnAccent.toArgb())

    init {
        val safeInset = safeAreaInsetPx()

        // A cinematic edge treatment, not a floating card. The gradient preserves the picture
        // above the controls and gives every label a predictable contrast floor below it.
        panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(safeInset, dp(62), safeInset, dp(46))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                orientation = GradientDrawable.Orientation.BOTTOM_TOP
                colors = intArrayOf(
                    TvTokens.Color.Black.copy(alpha = 0.98f).toArgb(),
                    TvTokens.Color.Black.copy(alpha = 0.92f).toArgb(),
                    TvTokens.Color.Slate.copy(alpha = 0.72f).toArgb(),
                    TvTokens.Color.Black.copy(alpha = 0.0f).toArgb(),
                )
            }
        }
        val panelParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        )
        addView(panel, panelParams)

        // Title and playback facts belong to the same reading path as the timeline. Splitting the
        // title into the top-left corner made it feel detached and undersized from a sofa.
        topHeaderView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            background = null
        }
        panel.addView(
            topHeaderView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        topHeaderView.addView(
            label(EYEBROW_SP, R.color.tv_accent).apply {
                text = "NOW PLAYING"
                setTypeface(uiMediumTypeface)
                letterSpacing = 0.18f
            },
        )

        titleView = label(TITLE_SP, R.color.tv_text_primary).apply {
            setTypeface(displayTypeface)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            letterSpacing = -0.01f
            setShadowLayer(8f, 0f, 2f, TvTokens.Color.Black.copy(alpha = 0.82f).toArgb())
        }
        topHeaderView.addView(
            titleView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(7) },
        )

        val factsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topHeaderView.addView(factsRow, rowParams(topMargin = dp(10)))

        metaView = label(META_SP, R.color.tv_text_secondary).apply {
            setTypeface(uiTypeface)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            letterSpacing = 0.01f
            setShadowLayer(3f, 0f, 1f, TvTokens.Color.Black.copy(alpha = 0.63f).toArgb())
        }
        factsRow.addView(
            metaView,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            ),
        )
        qualityBadgeView = label(QUALITY_SP, R.color.tv_text_primary).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(uiMediumTypeface)
            letterSpacing = 0.08f
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(TvTokens.Color.Text.copy(alpha = 0.08f).toArgb())
                setStroke(dp(1), TvTokens.Color.Text.copy(alpha = 0.16f).toArgb())
            }
        }
        factsRow.addView(
            qualityBadgeView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dp(18) },
        )

        // ================= TOP-RIGHT SEEK READOUT =================
        seekPreviewView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = false
            visibility = GONE
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(TvTokens.Color.Black.copy(alpha = 0.88f).toArgb())
                setStroke(dp(1), TvTokens.Color.Text.copy(alpha = 0.14f).toArgb())
            }
            elevation = dp(16).toFloat()
        }

        val seekTimingRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        seekTargetView = label(SEEK_TARGET_SP, R.color.tv_text_primary).apply {
            setTypeface(dataTypeface)
            letterSpacing = -0.02f
        }
        seekTimingRow.addView(seekTargetView)
        seekDeltaView = label(SEEK_DELTA_SP, R.color.tv_accent).apply {
            setTypeface(dataTypeface)
            letterSpacing = 0.02f
        }
        seekTimingRow.addView(
            seekDeltaView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dp(16) },
        )
        seekPreviewView.addView(seekTimingRow)

        seekChapterView = label(SEEK_CHAPTER_SP, R.color.tv_text_secondary).apply {
            setTypeface(dataTypeface)
            letterSpacing = 0.10f
            gravity = Gravity.CENTER
        }
        seekPreviewView.addView(
            seekChapterView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dp(12) },
        )
        addView(
            seekPreviewView,
            LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply { topMargin = safeInset; rightMargin = safeInset },
        )

        // The scrubber owns focus; its labels are display-only so a remote reaches the bar in one
        // press rather than tabbing through timecodes.
        scrubberRow = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            setOnFocusChangeListener { _, hasFocus -> renderScrubberFocus(hasFocus) }
            setOnKeyListener { _, keyCode, event -> handleScrubberKey(keyCode, event) }
        }
        panel.addView(scrubberRow, rowParams(topMargin = dp(26)))

        val scrubTrackRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        scrubberRow.addView(scrubTrackRow, rowParams())

        positionView = timeLabel(Gravity.START)
        seekBar = SlimSeekBar(context)
        scrubTrackRow.addView(
            seekBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        // Time still to run, not the runtime. "How much is left" is the question a viewer actually
        // asks mid-film; the runtime is on the detail screen and never changes.
        remainingView = timeLabel(Gravity.END)
        val timelineMetaRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        timelineMetaRow.addView(
            positionView,
            LinearLayout.LayoutParams(dp(TIME_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        chapterView = label(SEEK_CHAPTER_SP, R.color.tv_text_tertiary).apply {
            setTypeface(dataTypeface)
            letterSpacing = 0.10f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        timelineMetaRow.addView(
            chapterView,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(12)
                rightMargin = dp(12)
            },
        )
        timelineMetaRow.addView(
            remainingView,
            LinearLayout.LayoutParams(dp(TIME_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        scrubberRow.addView(
            timelineMetaRow,
            rowParams(topMargin = dp(10)),
        )

        controlsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        panel.addView(controlsRow, rowParams(topMargin = dp(24)))

        playPausePill = pill(primary = true) { listener.onPlayPauseRequested() }
        audioPill = pill { listener.onAudioTracksRequested() }
        subtitlePill = pill { listener.onSubtitleTracksRequested() }
        morePill = pill { listener.onMoreRequested() }

        controlsRow.addView(playPausePill, pillParams())
        controlsRow.addView(audioPill, pillParams())
        controlsRow.addView(subtitlePill, pillParams())
        // A weighted gap, so `More` sits at the far end of the bar and the three controls a viewer
        // reaches for stay together under the left hand of the D-pad.
        controlsRow.addView(
            View(context),
            LinearLayout.LayoutParams(0, 1, 1f),
        )
        controlsRow.addView(morePill, pillParams().apply { rightMargin = 0 })

        isFocusable = false
        renderPills()
        renderTimes()
    }

    /** Called by the Activity whenever it has fresh playback state. */
    fun bind(
        mediaIdentity: String?,
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
        val now = SystemClock.uptimeMillis()
        val mediaChanged = SeekPresentationPolicy.mediaChanged(this.mediaIdentity, mediaIdentity)
        if (mediaChanged) {
            cancelScrub()
            this.mediaIdentity = mediaIdentity
            optimisticSeekTargetMillis = null
            lastSeekCommittedAtUptime = 0L
            chapterFractions = emptyList()
            seekBar.chapters = emptyList()
            this.positionMillis = positionMillis.coerceAtLeast(0L)
            this.durationMillis = durationMillis.coerceAtLeast(0L)
            positionSampledAtUptime = now
        } else {
            this.durationMillis = SeekPresentationPolicy.resolveDuration(
                currentMillis = this.durationMillis,
                incomingMillis = durationMillis,
            )
            val resolved = SeekPresentationPolicy.resolvePosition(
                currentMillis = livePositionMillis(),
                incomingMillis = positionMillis,
                optimisticTargetMillis = optimisticSeekTargetMillis,
                elapsedSinceSeekMillis = now - lastSeekCommittedAtUptime,
            )
            this.positionMillis = resolved.millis
            positionSampledAtUptime = now
            if (resolved.seekLanded) optimisticSeekTargetMillis = null
        }
        if (chapterFractions.isEmpty() && this.durationMillis > 0L) {
            chapterFractions = TVReceiverPresentationPolicy.chapterFractionsForDuration(
                this.durationMillis / 1_000.0,
            )
            seekBar.chapters = chapterFractions
        }
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

    fun setAfrPresentation(summary: String) {
        afrSummary = summary
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

    /** Chapter boundaries as 0-1 fractions of the runtime; empty means derive from duration. */
    fun setChapters(fractions: List<Float>) {
        val supplied = fractions
            .asSequence()
            .map { it.coerceIn(0f, 1f) }
            .filter { it > 0f && it < 1f }
            .distinct()
            .sorted()
            .toList()
        chapterFractions = if (supplied.isNotEmpty() || durationMillis <= 0L) {
            supplied
        } else {
            TVReceiverPresentationPolicy.chapterFractionsForDuration(durationMillis / 1_000.0)
        }
        seekBar.chapters = chapterFractions
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

    /** Seek feedback preserves whether the viewer explicitly opened the controls. */
    fun showSeekFeedback(fullControlsWereVisible: Boolean) {
        if (pendingScrubMillis == null) {
            val chrome = SeekPresentationPolicy.chromeDuringSeek(fullControlsWereVisible)
            topHeaderView.visibility = if (chrome.fullControlsVisible) VISIBLE else GONE
            controlsRow.visibility = if (chrome.fullControlsVisible) VISIBLE else GONE
            panel.visibility = if (chrome.bottomTimelineVisible) VISIBLE else GONE
            seekPreviewView.visibility = if (chrome.topRightPreviewVisible) VISIBLE else GONE
            visibility = VISIBLE
        }
    }

    fun hideSeekPreview() {
        seekPreviewView.visibility = GONE
        if (panel.visibility != VISIBLE) visibility = GONE
    }

    /** Restores the bottom panel when controls are deliberately raised. */
    fun showFullPanel() {
        topHeaderView.visibility = VISIBLE
        controlsRow.visibility = VISIBLE
        panel.visibility = VISIBLE
        visibility = VISIBLE
    }

    /** Whether the bottom control panel is visible. */
    fun isPanelVisible(): Boolean = panel.visibility == VISIBLE

    /** Drops any half-finished scrub; used when the controls hide or the title changes. */
    fun cancelScrub() {
        scrubHandler.removeCallbacks(scrubCommitRunnable)
        scrubCommitPending = false
        pendingScrubMillis = null
        scrubGestureStartedAt = 0L
        seekBar.scrubbing = false
        seekBar.ghost = null
        seekPreviewView.visibility = GONE
        renderTimes()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postDelayed(ticker, TICK_MILLIS)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        scrubHandler.removeCallbacks(scrubCommitRunnable)
        playPausePill.animate().cancel()
        audioPill.animate().cancel()
        subtitlePill.animate().cancel()
        morePill.animate().cancel()
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
            scrubGestureStartedAt = 0L
            val pending = pendingScrubMillis
            if (pending == null) return true
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

    /** Starts a scrub from outside the full controls; the caller shows only the top-right readout. */
    fun nudgeScrub(stepMillis: Long) {
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
        if (pendingScrubMillis == null || scrubGestureStartedAt == 0L) {
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
        val target = PlayerControlsPolicy.scrubTarget(
            from ?: livePositionMillis(),
            stepMillis,
            durationMillis,
        )
        pendingScrubMillis = target
        seekBar.scrubbing = true
        seekBar.ghost = PlayerControlsPolicy.progressPermille(positionMillis, durationMillis) / 1000f

        // The full panel may already be visible because the viewer explicitly opened it. A hidden
        // D-pad seek must not raise it; only the existing top-right preview is common to both paths.
        seekPreviewView.visibility = VISIBLE
        visibility = VISIBLE

        val originPos = optimisticSeekTargetMillis ?: positionMillis
        val delta = target - originPos
        seekTargetView.text = PlayerControlsPolicy.formatTime(target)
        seekDeltaView.text = PlayerControlsPolicy.formatSignedDelta(delta)
        seekChapterView.text = chapterLabel(target)

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
        scrubGestureStartedAt = 0L
        positionMillis = targetMillis
        positionSampledAtUptime = SystemClock.uptimeMillis()
        lastSeekCommittedAtUptime = SystemClock.uptimeMillis()
        optimisticSeekTargetMillis = targetMillis
        seekBar.scrubbing = false
        seekBar.ghost = null
        // Keep the preview up until the 1.4s visual-dismiss window expires; the receiver seek is
        // already coalesced and committed independently above.
        listener.onSeekRequested(targetMillis)
    }

    /** Phone/RPC seeks use the same optimistic timeline without opening a second visual language. */
    fun noteExternalSeek(targetMillis: Long, offsetMillis: Long) {
        val target = targetMillis.coerceIn(0L, durationMillis.coerceAtLeast(targetMillis))
        val now = SystemClock.uptimeMillis()
        pendingScrubMillis = null
        optimisticSeekTargetMillis = target
        lastSeekCommittedAtUptime = now
        positionMillis = target
        positionSampledAtUptime = now
        seekBar.scrubbing = false
        seekBar.ghost = null
        seekTargetView.text = PlayerControlsPolicy.formatTime(target)
        seekDeltaView.text = PlayerControlsPolicy.formatSignedDelta(offsetMillis)
        seekChapterView.text = chapterLabel(target)
        seekPreviewView.visibility = VISIBLE
        renderTimes()
    }

    /** A rejected player seek releases the optimistic hold; the next snapshot becomes authoritative. */
    fun rejectOptimisticSeek() {
        optimisticSeekTargetMillis = null
        lastSeekCommittedAtUptime = 0L
        cancelScrub()
    }

    /** A stop is a hard media boundary, even when the same URL is opened again for replay. */
    fun resetTimeline() {
        cancelScrub()
        optimisticSeekTargetMillis = null
        lastSeekCommittedAtUptime = 0L
        mediaIdentity = null
        positionMillis = 0L
        durationMillis = 0L
        bufferedMillis = 0L
        positionSampledAtUptime = 0L
        chapterFractions = emptyList()
        seekBar.chapters = emptyList()
        seekPreviewView.visibility = GONE
        renderTimes()
    }

    private fun renderTimes() {
        val pending = pendingScrubMillis
        val shown = pending ?: livePositionMillis()
        positionView.text = PlayerControlsPolicy.formatTime(shown)
        chapterView.text = chapterLabel(shown)
        val buffFrac = if (durationMillis > 0) (bufferedMillis.toFloat() / durationMillis.toFloat()).coerceIn(0f, 1f) else 0f
        val buffPct = (buffFrac * 100).toInt()
        remainingView.text = if (durationMillis > 0) {
            context.getString(
                R.string.controls_remaining,
                PlayerControlsPolicy.formatTime((durationMillis - shown).coerceAtLeast(0L)),
            ) + " · $buffPct%"
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
    }

    /** Numbered chapters are derived from runtime marks; no release-specific names are invented. */
    private fun chapterLabel(positionMillis: Long): String {
        if (durationMillis <= 0L || chapterFractions.isEmpty()) return ""
        val progress = PlayerControlsPolicy.progressPermille(positionMillis, durationMillis) / 1000f
        val number = chapterFractions.count { progress >= it } + 1
        return "CHAPTER $number OF ${chapterFractions.size + 1}"
    }

    private fun renderScrubberFocus(hasFocus: Boolean) {
        seekBar.active = hasFocus
        positionView.alpha = if (hasFocus) 1f else 0.75f
        chapterView.alpha = if (hasFocus) 1f else 0.75f
        remainingView.alpha = if (hasFocus) 1f else 0.75f
        if (!hasFocus) cancelScrub()
    }

    /** Every control, with its current value on its face. */
    private fun renderPills() {
        playPausePill.text = if (isPlaying) {
            context.getString(R.string.controls_pause)
        } else {
            context.getString(R.string.controls_resume)
        }
        playPauseGlyph.isPlaying = isPlaying
        playPausePill.setCompoundDrawablesRelative(playPauseGlyph, null, null, null)
        playPausePill.compoundDrawablePadding = dp(12)
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
        parts += afrSummary
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
    private fun pill(primary: Boolean = false, onClick: () -> Unit): TextView =
        TextView(context).apply {
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            minWidth = dp(if (primary) PRIMARY_MIN_WIDTH_DP else PILL_MIN_WIDTH_DP)
            minHeight = dp(if (primary) PRIMARY_HEIGHT_DP else PILL_HEIGHT_DP)
            maxWidth = dp(if (primary) PRIMARY_MAX_WIDTH_DP else PILL_MAX_WIDTH_DP)
            setPadding(
                dp(if (primary) PRIMARY_PADDING_H_DP else PILL_PADDING_H_DP),
                dp(8),
                dp(if (primary) PRIMARY_PADDING_H_DP else PILL_PADDING_H_DP),
                dp(8),
            )
            setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                TvTokens.designTextPx(context, if (primary) PRIMARY_SP else PILL_SP),
            )
            setTypeface(uiMediumTypeface)
            background = pillBackground(focused = false, primary = primary)
            setTextColor(
                if (primary) TvTokens.Color.OnAccent.toArgb() else context.getColor(R.color.tv_control_text),
            )
            setOnFocusChangeListener { view, hasFocus ->
                renderPillFocus(view as TextView, hasFocus, primary)
            }
            setOnKeyListener { view, keyCode, event ->
                if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER && keyCode != KeyEvent.KEYCODE_ENTER) {
                    return@setOnKeyListener false
                }
                val button = view as TextView
                button.animate().cancel()
                if (event.action == KeyEvent.ACTION_DOWN) {
                    button.scaleX = PILL_PRESS_SCALE
                    button.scaleY = PILL_PRESS_SCALE
                } else if (event.action == KeyEvent.ACTION_UP) {
                    button.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(PILL_RELEASE_MILLIS)
                        .setInterpolator(android.view.animation.PathInterpolator(0.23f, 1f, 0.32f, 1f))
                        .start()
                }
                false
            }
            setOnClickListener {
                listener.onInteraction()
                onClick()
            }
        }

    private fun renderPillFocus(pill: TextView, hasFocus: Boolean, primary: Boolean) {
        // D-pad focus moves dozens of times per session. Paint it on the same frame as the key
        // event; motion here makes the remote feel laggy. Press and panel motion carry the polish.
        pill.animate().cancel()
        pill.scaleX = 1f
        pill.scaleY = 1f
        pill.background = pillBackground(hasFocus, primary)
        pill.elevation = if (hasFocus) dp(14).toFloat() else 0f
        pill.setTextColor(
            if (hasFocus || primary) {
                context.getColor(R.color.tv_control_text_focused)
            } else {
                context.getColor(R.color.tv_control_text)
            },
        )
    }

    private fun pillBackground(focused: Boolean, primary: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(if (primary) PRIMARY_HEIGHT_DP / 2 else PILL_HEIGHT_DP / 2).toFloat()
        setColor(
            when {
                focused -> context.getColor(R.color.tv_accent)
                primary -> TvTokens.Color.Text.copy(alpha = 0.94f).toArgb()
                else -> TvTokens.Color.Text.copy(alpha = 0.08f).toArgb()
            },
        )
        setStroke(
            dp(if (focused) 3 else 1),
            when {
                focused -> TvTokens.Color.Text.copy(alpha = 0.92f).toArgb()
                primary -> TvTokens.Color.Text.copy(alpha = 0.94f).toArgb()
                else -> TvTokens.Color.Text.copy(alpha = 0.16f).toArgb()
            },
        )
    }

    private fun timeLabel(gravityValue: Int): TextView =
        label(TIME_SP, R.color.tv_text_primary).apply {
            gravity = gravityValue
            setTypeface(dataTypeface)
            letterSpacing = 0.02f
        }

    private fun label(sizeSp: Float, colorRes: Int): TextView =
        TextView(context).apply {
            setTextColor(context.getColor(colorRes))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, TvTokens.designTextPx(context, sizeSp))
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
        ).apply { rightMargin = dp(12) }

    /** 5% of the shorter edge: the title-safe margin every television overscan spec agrees on. */
    private fun safeAreaInsetPx(): Int {
        val metrics = context.resources.displayMetrics
        return (minOf(metrics.widthPixels, metrics.heightPixels) * SAFE_AREA_FRACTION).roundToInt()
    }

    private fun dp(value: Int): Int = TvTokens.designUnit(context, value)

    private companion object {
        const val EYEBROW_SP = 13f
        const val TITLE_SP = 62f
        const val META_SP = 19f
        const val QUALITY_SP = 13f
        const val TIME_SP = 18f
        const val PILL_SP = 19f
        const val PRIMARY_SP = 21f
        const val SEEK_TARGET_SP = 46f
        const val SEEK_DELTA_SP = 19f
        const val SEEK_CHAPTER_SP = 14f
        const val TIME_WIDTH_DP = 154
        const val PILL_HEIGHT_DP = 56
        const val PILL_MIN_WIDTH_DP = 104
        const val PILL_MAX_WIDTH_DP = 340
        const val PILL_PADDING_H_DP = 18
        const val PRIMARY_HEIGHT_DP = 64
        const val PRIMARY_MIN_WIDTH_DP = 174
        const val PRIMARY_MAX_WIDTH_DP = 218
        const val PRIMARY_PADDING_H_DP = 24
        const val PILL_PRESS_SCALE = 0.97f
        const val PILL_RELEASE_MILLIS = 120L

        /**
         * Title-safe margin, as a fraction of the shorter screen edge.
         *
         * A television crops the edges of what it is sent — overscan — and how much is the panel's
         * business, not ours. 4.5% was inside the amount a real set actually cropped: reported live
         * on the onn 4K Pro (2026-08-28), where the seek pill in the top-right corner was cut off.
         * At the 1920x1080 this box reports, 4.5% is 49px; the common panel crop is 5% per edge, so
         * the pill sat exactly on the line and lost.
         *
         * 6% clears a 5% crop with something left over, and costs 1.5% of the shorter edge on a set
         * that crops nothing. That is the right way round: chrome slightly inset on a well-behaved
         * television is invisible, chrome sliced in half is not.
         */
        const val SAFE_AREA_FRACTION = 0.06f

        /** How often the bar re-paints itself between player polls. */
        const val TICK_MILLIS = 250L

        /** v2: ms after last scrubbing UP before commit fires. Cancelled if another DOWN arrives. */
        const val COALESCE_WINDOW_MS = 1200L
    }
}

/** Monochrome player glyph that cannot fall through to a vendor's colored emoji font. */
private class PlayPauseGlyphDrawable(
    private val sizePx: Int,
    color: Int,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }

    var isPlaying: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidateSelf()
            }
        }

    init {
        setBounds(0, 0, sizePx, sizePx)
    }

    override fun draw(canvas: Canvas) {
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        if (isPlaying) {
            val barWidth = width * 0.24f
            canvas.drawRoundRect(0f, 0f, barWidth, height, barWidth * 0.28f, barWidth * 0.28f, paint)
            canvas.drawRoundRect(
                width - barWidth,
                0f,
                width,
                height,
                barWidth * 0.28f,
                barWidth * 0.28f,
                paint,
            )
        } else {
            val path = android.graphics.Path().apply {
                moveTo(width * 0.18f, 0f)
                lineTo(width, height / 2f)
                lineTo(width * 0.18f, height)
                close()
            }
            canvas.drawPath(path, paint)
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = sizePx
    override fun getIntrinsicHeight(): Int = sizePx
}
