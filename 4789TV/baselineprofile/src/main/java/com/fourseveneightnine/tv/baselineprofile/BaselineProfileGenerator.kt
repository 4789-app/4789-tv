package com.fourseveneightnine.tv.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the baseline profile plan §9 requires.
 *
 * Without it every Compose path on this box is interpreted or JIT-ed on the first run, which is
 * the worst case for both the cold start and the first row sweep — the two numbers plan §9 gates.
 *
 * The journey is the one a viewer actually takes on a cold box: Home paints, the ring sweeps a
 * row, OK opens Detail, OK again opens the stream list. Nothing here asserts anything. A macro
 * benchmark measures; this only walks the screens so the classes and methods behind them are
 * written into the profile.
 *
 * Run it from the orchestrator with the box awake and attached:
 *
 * ```
 * ./gradlew :app:generateSideloadReleaseBaselineProfile
 * ```
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() = rule.collect(
        packageName = targetAppId(),
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        device.waitForIdle(IDLE_TIMEOUT_MILLIS)
    }

    @Test
    fun generate() = rule.collect(
        // The producer module passes the flavor's app ID explicitly. Its instrumentation
        // targetContext is the benchmark package, not the app being profiled.
        packageName = targetAppId(),
        includeInStartupProfile = false,
    ) {
        pressHome()
        startActivityAndWait()
        device.waitForIdle(IDLE_TIMEOUT_MILLIS)

        // Home: sweep one row. Twelve steps is more than the composed window, so the LazyRow
        // recycles and the card, artwork and focus paths all run.
        repeat(ROW_SWEEP_PRESSES) {
            device.pressDPadRight()
            device.waitForIdle(STEP_IDLE_MILLIS)
        }

        // Cross one row boundary: the hero swap and the row pivot are their own code.
        device.pressDPadDown()
        device.waitForIdle(IDLE_TIMEOUT_MILLIS)
        device.pressDPadRight()
        device.waitForIdle(STEP_IDLE_MILLIS)

        // Detail.
        device.pressDPadCenter()
        device.waitForIdle(IDLE_TIMEOUT_MILLIS)
        Thread.sleep(SCREEN_SETTLE_MILLIS)

        // Streams: the first action on Detail is Play, which resolves and lists the sources.
        device.pressDPadCenter()
        device.waitForIdle(IDLE_TIMEOUT_MILLIS)
        Thread.sleep(SCREEN_SETTLE_MILLIS)

        // Walk the list, then leave both screens the way a viewer does.
        repeat(STREAM_LIST_PRESSES) {
            device.pressDPadDown()
            device.waitForIdle(STEP_IDLE_MILLIS)
        }
        device.pressBack()
        device.waitForIdle(IDLE_TIMEOUT_MILLIS)
        device.pressBack()
        device.waitForIdle(IDLE_TIMEOUT_MILLIS)
    }

    private fun targetAppId(): String = InstrumentationRegistry.getArguments().getString("targetAppId")
        ?: error("targetAppId was not passed as an instrumentation runner argument")

    private companion object {
        const val ROW_SWEEP_PRESSES = 12
        const val STREAM_LIST_PRESSES = 4
        const val IDLE_TIMEOUT_MILLIS = 3_000L
        const val STEP_IDLE_MILLIS = 500L

        /** A drill-in resolves over the network. Idle alone returns before the rows land. */
        const val SCREEN_SETTLE_MILLIS = 4_000L
    }
}
