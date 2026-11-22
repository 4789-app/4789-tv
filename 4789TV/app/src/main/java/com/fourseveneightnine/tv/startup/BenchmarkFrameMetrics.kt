package com.fourseveneightnine.tv.startup

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window

/** Diagnostic-build window timings. Media3 and SurfaceFlinger still own video and presentation. */
internal class BenchmarkFrameMetrics(private val window: Window) : AutoCloseable {
    private val thread = HandlerThread("4789-frame-metrics").apply { start() }
    private val handler = Handler(thread.looper)
    private val durations = IntArray(701)
    private var windowStartNs = 0L
    private var frames = 0
    private var samplesWithDeadline = 0
    private var missed = 0
    private var frozen = 0
    private var droppedReports = 0
    private var worstMs = -1
    private var worstVsyncNs = 0L
    private var worstAnimationNs = 0L
    private var worstLayoutNs = 0L
    private var worstDrawNs = 0L
    private var worstUiDelayNs = 0L

    private val listener = Window.OnFrameMetricsAvailableListener { _, frame, dropped ->
        droppedReports += dropped
        val duration = frame.getMetric(FrameMetrics.TOTAL_DURATION)
        if (duration >= 0) {
            val vsync = frame.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
            if (frames > 0 && vsync - windowStartNs >= 1_000_000_000L) publish()
            if (frames == 0) windowStartNs = vsync
            val ms = (duration / 1_000_000L).toInt()
            frames++
            durations[ms.coerceAtMost(durations.lastIndex)]++
            if (duration >= 700_000_000L) frozen++
            val deadline = if (Build.VERSION.SDK_INT >= 31) frame.getMetric(FrameMetrics.DEADLINE) else -1L
            val late = deadline > 0L && duration >= deadline
            if (deadline > 0L) samplesWithDeadline++
            if (late) missed++
            if (late || ms >= 32) {
                val actualVsync = frame.getMetric(FrameMetrics.VSYNC_TIMESTAMP)
                ReceiverDiagnostics.recordBenchmark("frames.slow",
                    "intendedVsyncNs=$vsync actualVsyncNs=$actualVsync durationNs=$duration deadlineNs=$deadline missed=$late")
            }
            if (ms > worstMs) {
                worstMs = ms
                worstVsyncNs = vsync
                worstAnimationNs = frame.getMetric(FrameMetrics.ANIMATION_DURATION)
                worstLayoutNs = frame.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION)
                worstDrawNs = frame.getMetric(FrameMetrics.DRAW_DURATION)
                worstUiDelayNs = frame.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION)
            }
            if (frame.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) {
                ReceiverDiagnostics.recordBenchmark("frames.first_draw", "vsyncNs=$vsync durationNs=$duration")
            }
        }
    }

    init {
        window.addOnFrameMetricsAvailableListener(listener, handler)
    }

    private fun publish() {
        if (frames == 0) return
        ReceiverDiagnostics.benchmark("frames.window") {
            "firstVsyncNs=$windowStartNs frames=$frames deadlineSamples=$samplesWithDeadline missed=$missed " +
                "frozen=$frozen droppedReports=$droppedReports p50Ms=${percentile(50)} p95Ms=${percentile(95)} " +
                "worstMs=$worstMs worstVsyncNs=$worstVsyncNs animationNs=$worstAnimationNs " +
                "layoutNs=$worstLayoutNs drawNs=$worstDrawNs uiDelayNs=$worstUiDelayNs"
        }
        durations.fill(0)
        frames = 0
        samplesWithDeadline = 0
        missed = 0
        frozen = 0
        droppedReports = 0
        worstMs = -1
    }

    private fun percentile(percent: Int): Int {
        val rank = (frames * percent + 99) / 100
        var count = 0
        for (ms in durations.indices) {
            count += durations[ms]
            if (count >= rank) return ms
        }
        return durations.lastIndex
    }

    override fun close() {
        window.removeOnFrameMetricsAvailableListener(listener)
        handler.post {
            publish()
            thread.quitSafely()
        }
    }
}
