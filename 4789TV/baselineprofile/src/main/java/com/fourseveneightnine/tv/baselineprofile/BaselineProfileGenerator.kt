package com.fourseveneightnine.tv.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import android.os.SystemClock
import android.os.Build
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the baseline profile plan §9 requires.
 *
 * Without it every Compose path on this box is interpreted or JIT-ed on the first run, which is
 * the worst case for both the cold start and the first row sweep — the two numbers plan §9 gates.
 *
 * Walks a prepared local-fixture Home row, Detail and Sources. Each destination and the
 * fixture source must appear; an empty launch or a settings screen cannot produce a good profile.
 * This collects compilation coverage, not startup/frame latency measurements.
 *
 * Select one disposable physical receiver with ANDROID_SERIAL, then pass
 * -PtvProfileDisposableDevice=true -PtvProfileFixtureTitle=<card-title-fragment>
 * -PtvProfileStreamLabel=<source-label-fragment> to :app:generateSideloadReleaseBaselineProfile.
 * Keep that receiver's prepared fixture server alive through the whole collection.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Before
    fun requirePreparedDisposableReceiver() {
        check(Build.HARDWARE != "ranchu" && Build.HARDWARE != "goldfish") {
            "TV profile collection requires a disposable physical receiver"
        }
        check(argument("disposable") == "true") { "Explicit disposable receiver selection is required" }
        argument("profileFixtureTitle")
        argument("profileStreamLabel")
    }

    @Test
    fun startup() = rule.collect(
        packageName = targetAppId(),
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        check(device.wait(Until.hasObject(By.desc("Home")), SCREEN_TIMEOUT_MILLIS)) { "Home did not appear" }
        check(device.wait(Until.hasObject(By.descContains(argument("profileFixtureTitle"))), SCREEN_TIMEOUT_MILLIS)) {
            "Prepared fixture cards did not appear on Home"
        }
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
        check(device.wait(Until.hasObject(By.desc("Home")), SCREEN_TIMEOUT_MILLIS)) { "Home did not appear" }
        device.focusFixtureCard(argument("profileFixtureTitle"))

        // Home: sweep one row. Twelve steps is more than the composed window, so the LazyRow
        // recycles and the card, artwork and focus paths all run.
        repeat(ROW_SWEEP_PRESSES) {
            device.pressDPadRight()
            device.waitForIdle(STEP_IDLE_MILLIS)
        }

        repeat(ROW_SWEEP_PRESSES) {
            device.pressDPadLeft()
            device.waitForIdle(STEP_IDLE_MILLIS)
        }
        device.focusFixtureCard(argument("profileFixtureTitle"))

        // Detail.
        device.pressDPadCenter()
        device.waitForIdle(IDLE_TIMEOUT_MILLIS)
        check(device.wait(Until.hasObject(By.desc("Play")), SCREEN_TIMEOUT_MILLIS)) { "Detail Play did not appear" }

        // Sources always opens the list, including when Play would select its only row.
        device.pressDPadRight()
        device.waitForIdle(STEP_IDLE_MILLIS)
        val sourcesButton = requireNotNull(device.findObject(By.desc("Sources")))
        check(device.findObject(By.focused(true))?.visibleBounds?.contains(sourcesButton.visibleBounds) == true) {
            "Remote focus did not reach Detail Sources"
        }
        device.pressDPadCenter()
        device.waitForIdle(IDLE_TIMEOUT_MILLIS)
        check(device.wait(Until.hasObject(By.text("Sources")), SCREEN_TIMEOUT_MILLIS)) { "Sources did not appear" }
        val source = By.descContains(argument("profileStreamLabel"))
        check(device.wait(Until.hasObject(source), SCREEN_TIMEOUT_MILLIS)) { "Prepared fixture source did not appear" }

        // Walk the list, then leave both screens the way a viewer does.
        repeat(STREAM_LIST_PRESSES) {
            device.pressDPadDown()
            device.waitForIdle(STEP_IDLE_MILLIS)
        }
        device.pressBack()
        check(device.wait(Until.gone(source), SCREEN_TIMEOUT_MILLIS)) { "Sources did not close" }
        device.pressBack()
        check(device.wait(Until.hasObject(By.desc("Home")), SCREEN_TIMEOUT_MILLIS)) { "Home did not return" }
        device.focusFixtureCard(argument("profileFixtureTitle"))
    }

    private fun UiDevice.focusFixtureCard(title: String) {
        val deadline = SystemClock.uptimeMillis() + SCREEN_TIMEOUT_MILLIS
        do {
            val card = wait(Until.findObject(By.descContains(title).clickable(true)), STEP_IDLE_MILLIS)
            val focus = findObject(By.focused(true))?.visibleBounds
            val target = card?.visibleBounds
            if (target != null && focus?.contains(target) == true) return
            when {
                target == null || focus == null -> pressDPadDown()
                focus.bottom <= target.top -> pressDPadDown()
                focus.top >= target.bottom -> pressDPadUp()
                focus.centerX() < target.centerX() -> pressDPadRight()
                else -> pressDPadLeft()
            }
            waitForIdle(STEP_IDLE_MILLIS)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Remote focus did not reach fixture card: $title")
    }

    private fun argument(name: String): String = InstrumentationRegistry.getArguments().getString(name)
        ?.takeIf { it.isNotBlank() } ?: error("Missing instrumentation argument: $name")

    private fun targetAppId(): String = InstrumentationRegistry.getArguments().getString("targetAppId")
        ?: error("targetAppId was not passed as an instrumentation runner argument")

    private companion object {
        const val ROW_SWEEP_PRESSES = 12
        const val STREAM_LIST_PRESSES = 4
        const val IDLE_TIMEOUT_MILLIS = 3_000L
        const val STEP_IDLE_MILLIS = 500L

        const val SCREEN_TIMEOUT_MILLIS = 20_000L
    }
}
