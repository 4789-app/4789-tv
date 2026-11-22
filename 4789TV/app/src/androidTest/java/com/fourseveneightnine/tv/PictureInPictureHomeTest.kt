package com.fourseveneightnine.tv

import android.content.Intent
import android.os.Build
import java.io.File
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.fourseveneightnine.tv.client.appGraph
import com.fourseveneightnine.tv.client.clientGraph
import com.fourseveneightnine.tv.player.ReceiverPlaybackPhase
import com.fourseveneightnine.tv.protocol.OpenMediaRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run after EmulatorFixtureSetupTest with Den TV Home selected as the disposable emulator's Home. */
@RunWith(AndroidJUnit4::class)
class PictureInPictureHomeTest {
    private fun holdKey(keyCode: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val start = android.os.SystemClock.uptimeMillis()
        fun send(action: Int) = instrumentation.uiAutomation.injectInputEvent(android.view.KeyEvent(
            start, android.os.SystemClock.uptimeMillis(), action, keyCode, 0,
            0, android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, android.view.InputDevice.SOURCE_DPAD), true)
        check(send(android.view.KeyEvent.ACTION_DOWN))
        Thread.sleep(800)
        check(send(android.view.KeyEvent.ACTION_UP))
    }

    private fun pressGuideInReceiver() {
        // The floating-video cases exercise the actual Consumer Page input filter. The paired
        // and episode cases start at the receiver because Google TV can reserve injected Guide.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                .filterIsInstance<com.fourseveneightnine.tv.client.ui.MainActivity>()
                .single()
            val now = android.os.SystemClock.uptimeMillis()
            for (action in listOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP)) {
                assertTrue(activity.dispatchKeyEvent(android.view.KeyEvent(
                    now, now, action, android.view.KeyEvent.KEYCODE_GUIDE, 0,
                )))
            }
        }
    }

    private fun awaitLabel(device: UiDevice, selector: androidx.test.uiautomator.BySelector, present: Boolean = true) {
        val end = android.os.SystemClock.elapsedRealtime() + 8_000
        do {
            if (Build.VERSION.SDK_INT >= 34) InstrumentationRegistry.getInstrumentation()
                .getUiAutomation(androidx.test.uiautomator.Configurator.getInstance().uiAutomationFlags).clearCache()
            if (device.hasObject(selector) == present) return
            Thread.sleep(100)
        } while (android.os.SystemClock.elapsedRealtime() < end)
        fail("Expected $selector present=$present")
    }

    private suspend fun choosePictureOption(device: UiDevice, label: String) {
        val row = By.text(label)
        awaitLabel(device, row)
        val focused = By.focused(true).hasDescendant(row)
        withTimeout(8_000) {
            while (true) {
                if (Build.VERSION.SDK_INT >= 34) InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation(androidx.test.uiautomator.Configurator.getInstance().uiAutomationFlags).clearCache()
                if (device.hasObject(focused)) break
                device.pressDPadDown()
                delay(120)
            }
        }
        device.pressDPadCenter()
    }

    @Test fun floatingVideoKeepsTheSameSessionAcrossXAndYouTube() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("crossAppPicture") == "true")
        check(args.getString("disposable") == "true" && Build.HARDWARE in setOf("ranchu", "goldfish"))
        val base = requireNotNull(args.getString("fixtureBase"))
        check(base.startsWith("http://127.0.0.1:") || base.startsWith("http://10.0.2.2:"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val livePicture = args.getString("livePicture") == "true"
        val returnLabel = if (livePicture) "Return to live TV" else "Return to player"
        val device = UiDevice.getInstance(instrumentation)
        val graph = context.appGraph
        val session = graph.playback
        val pip = graph.pictureInPicture
        val config = androidx.test.uiautomator.Configurator.getInstance()
        val previousIdle = config.waitForIdleTimeout
        val previousAutomationFlags = config.uiAutomationFlags
        config.waitForIdleTimeout = 150
        config.uiAutomationFlags = android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        val start = requireNotNull(context.packageManager.getLeanbackLaunchIntentForPackage(context.packageName))
        val youtube = "com.google.android.youtube.tv"
        val options = By.text("Go to video")
        val preferences = graph.presentationPreferences
        val sizeKey = "floating_picture_size"
        val positionKey = "floating_picture_position"
        val mappings = com.fourseveneightnine.tv.ui.RemoteButtonMappings
        val previousBindings = preferences.getString(mappings.PREFERENCE_KEY, null)
        val previousSize = preferences.getString(sizeKey, null)
        val previousPosition = preferences.getInt(positionKey, 0).takeIf { preferences.contains(positionKey) }
        preferences.edit().remove(sizeKey).remove(positionKey).commit()
        val remoteService = "${context.packageName}/com.fourseveneightnine.tv.ui.RemoteButtonAccessibilityService"
        val previousServices = device.executeShellCommand("settings get secure enabled_accessibility_services").trim()
        val previousEnabled = device.executeShellCommand("settings get secure accessibility_enabled").trim()
        val service = com.fourseveneightnine.tv.ui.RemoteButtonAccessibilityService

        suspend fun browseXWithPinnedPicture() = withContext(Dispatchers.Main) {
            val resumed = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                .filterIsInstance<com.fourseveneightnine.tv.client.ui.MainActivity>()
                .single()
            resumed.browseXWithPinnedPicture()
        }

        fun capture(label: String) {
            val directory = context.getExternalFilesDir(null)
            assertTrue(device.takeScreenshot(File(directory, "$label.png")))
        }

        fun pressShortcut(usage: Int, holdMillis: Int = 120) {
            // Shell-injected KeyEvents bypass the accessibility input filter. Replay the
            // Consumer Page usage from a virtual Bluetooth input device.
            val reports = """
                {"id":1,"command":"register","name":"4789 test remote","vid":2391,"pid":49,"bus":"bluetooth","descriptor":[5,12,9,1,161,1,21,0,38,255,3,25,0,42,255,3,117,16,149,1,129,0,192]}
                {"id":1,"command":"delay","duration":1000}
                {"id":1,"command":"report","report":[${usage and 255},${usage shr 8}]}
                {"id":1,"command":"delay","duration":$holdMillis}
                {"id":1,"command":"report","report":[0,0]}
                {"id":1,"command":"delay","duration":400}
            """.trimIndent()
            val nonInterceptingFlags = config.uiAutomationFlags or android.app.UiAutomation.FLAG_DONT_USE_ACCESSIBILITY
            val (output, input, errors) = instrumentation.getUiAutomation(nonInterceptingFlags)
                .executeShellCommandRwe("hid -")
            android.os.ParcelFileDescriptor.AutoCloseOutputStream(input).bufferedWriter().use {
                it.write(reports)
                it.newLine()
            }
            android.os.ParcelFileDescriptor.AutoCloseInputStream(output).bufferedReader().use { it.readText() }
            val error = android.os.ParcelFileDescriptor.AutoCloseInputStream(errors).bufferedReader().use { it.readText() }
            assertTrue(error, error.isBlank())
        }

        fun pressGuide() = pressShortcut(0x008D)
        fun pressProfile() = pressShortcut(0x019C)

        suspend fun awaitFocusAt(bounds: android.graphics.Rect) = withTimeout(8_000) {
            while (device.findObject(By.focused(true))?.visibleBounds != bounds) delay(100)
        }

        suspend fun awaitPicture(widthPercent: Int, left: Boolean = false, top: Boolean = true) {
            val frame = Regex("""4789 floating video, frame=\[(\d+),(\d+),(\d+),(\d+)\]""")
            val width = device.displayWidth * widthPercent / 100
            val margin = device.displayWidth / 32
            val expectedLeft = if (left) margin else device.displayWidth - margin - width
            val expectedTop = if (top) margin else device.displayHeight - margin - width * 9 / 16
            withTimeout(8_000) {
                while (true) {
                    val dump = device.executeShellCommand("dumpsys window windows")
                    val match = frame.find(dump)
                    if (match != null && match.groupValues[1].toInt() == expectedLeft &&
                        match.groupValues[2].toInt() == expectedTop &&
                        match.groupValues[3].toInt() == expectedLeft + width) break
                    delay(100)
                }
            }
        }

        suspend fun awaitFloatingOpacity(expected: Float) = withTimeout(8_000) {
            val header = Regex("""(?m)^\s*Window #\d+ Window\{[^\n]* 4789 floating video\}:""")
            val alpha = Regex("""\balpha=([\d.]+)""")
            while (true) {
                val dump = device.executeShellCommand("dumpsys window windows")
                val block = header.find(dump)?.let { dump.substring(it.range.last + 1) }
                val attrs = block?.lineSequence()?.firstOrNull { it.trimStart().startsWith("mAttrs=") }
                if (attrs != null && (alpha.find(attrs)?.groupValues?.get(1)?.toFloatOrNull() ?: 1f) == expected) break
                delay(100)
            }
        }

        suspend fun pinPicture() {
            pressGuide()
            withTimeout(5_000) { androidx.compose.runtime.snapshotFlow { pip.browseOnly }.first { it } }
            awaitLabel(device, By.text(returnLabel), present = false)
            awaitLabel(device, By.text("PiP options"), present = false)
        }

        fun assertFixturePicture(fileName: String) {
            val file = File(context.getExternalFilesDir(null), "$fileName.png")
            val bitmap = requireNotNull(android.graphics.BitmapFactory.decodeFile(file.path))
            try {
                val width = bitmap.width * 42 / 100
                val height = width * 9 / 16
                val margin = bitmap.width / 32
                val left = bitmap.width - width - margin
                // Sample away from the bottom-right options control and fitted matte.
                val colorSamples = (1..7).sumOf { row ->
                    (1..7).count { col ->
                        val pixel = bitmap.getPixel(left + width * col / 9, margin + height * row / 10)
                        val r = android.graphics.Color.red(pixel)
                        val g = android.graphics.Color.green(pixel)
                        val b = android.graphics.Color.blue(pixel)
                        maxOf(r, g, b) - minOf(r, g, b) > 85
                    }
                }
                assertTrue("The floating options drew without the fixture video ($colorSamples color samples)", colorSamples >= 12)
            } finally {
                bitmap.recycle()
            }
        }

        runBlocking {
            check(!android.provider.Settings.canDrawOverlays(context))
            context.startActivity(start)
            try {
                val enabledServices = listOfNotNull(previousServices.takeUnless { it == "null" || it.isEmpty() },
                    remoteService).joinToString(":")
                device.executeShellCommand("cmd appops set ${context.packageName} ACCESS_RESTRICTED_SETTINGS allow")
                device.executeShellCommand("settings put secure enabled_accessibility_services $enabledServices")
                device.executeShellCommand("settings put secure accessibility_enabled 1")
                withTimeout(10_000) { service.connected.first { it } }
                device.executeShellCommand("cmd appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
                val launcher = device.executeShellCommand(
                    "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME",
                ).trim().lineSequence().last().substringBefore('/')
                withTimeout(15_000) { session.surfaceReadyState.first { it } }
                withContext(Dispatchers.Main) {
                    mappings.save(preferences, android.view.KeyEvent.KEYCODE_GUIDE, true,
                        com.fourseveneightnine.tv.ui.RemoteButtonAction.Guide)
                }
                if (livePicture) {
                    val repo = context.clientGraph.iptv
                    repo.awaitLoaded()
                    if (repo.state.value.sources.none { it.name == "PiP fixture" }) {
                        check(repo.addLink("PiP fixture", "$base/fixture.m3u"))
                    } else repo.refresh()
                    val match = withTimeout(20_000) {
                        repo.state.first { state -> state.channels.any { it.name == "Match fixture" } }
                    }.channels.first { it.name == "Match fixture" }
                    session.openLiveChannel(OpenMediaRequest(repo.playUrl(match), title = match.name, isLive = true),
                        com.fourseveneightnine.tv.client.playback.PlaybackSession.LiveChannel(match.id, match.sourceId,
                            match.group)).getOrThrow()
                } else session.open(OpenMediaRequest("$base/clip.mp4", title = "Floating video fixture"),
                    com.fourseveneightnine.tv.client.playback.PlaybackSession.Origin.Local).getOrThrow()
                withTimeout(15_000) { session.phase.first { it == ReceiverPlaybackPhase.Playing } }
                val generation = session.generation
                pinPicture()
                val browseFocus = requireNotNull(device.findObject(By.focused(true))).visibleBounds
                val movePicture = By.text("Move picture")
                pressShortcut(0x008D, holdMillis = 800)
                awaitLabel(device, movePicture)
                val large = By.focused(true).hasDescendant(By.text("Large"))
                awaitLabel(device, large)
                assertTrue("The Guide popup should be compact",
                    device.findObject(large).visibleBounds.width() <= device.displayWidth / 4)
                awaitLabel(device, By.text(returnLabel), present = false)
                awaitLabel(device, By.text("PiP options"), present = false)
                capture("guide-in-app-options")
                choosePictureOption(device, "Extra large")
                withTimeout(5_000) { androidx.compose.runtime.snapshotFlow { pip.size.widthPercent }.first { it == 52 } }
                device.pressDPadUp()
                awaitLabel(device, large)
                device.pressDPadCenter()
                withTimeout(5_000) { androidx.compose.runtime.snapshotFlow { pip.size.widthPercent }.first { it == 42 } }
                pressGuide()
                awaitLabel(device, movePicture, present = false)
                awaitFocusAt(browseFocus)

                pressGuide()
                awaitLabel(device, movePicture)
                pressProfile()
                awaitLabel(device, movePicture, present = false)
                assertTrue(withContext(Dispatchers.Main) { pip.pictureHidden })
                awaitFocusAt(browseFocus)
                pressProfile()
                awaitLabel(device, options, present = false)
                assertFalse(withContext(Dispatchers.Main) { pip.pictureHidden })
                awaitFocusAt(browseFocus)

                pressGuide()
                choosePictureOption(device, "Go to video")
                withTimeout(5_000) { androidx.compose.runtime.snapshotFlow { pip.browseOnly }.first { !it } }
                assertEquals(generation, session.generation)
                pinPicture()
                capture("unified-pip-in-app")
                browseXWithPinnedPicture()
                awaitLabel(device, By.text("X timeline"))
                awaitLabel(device, By.text("Search posts"))
                withTimeout(8_000) {
                    while (!withContext(Dispatchers.Main) { pip.soloOnHome }) delay(100)
                }
                awaitPicture(42)
                awaitFloatingOpacity(1f)
                val positiveButton = By.res("android", "button1")
                delay(700)
                val xFocus = requireNotNull(device.findObject(By.focused(true))).visibleBounds
                pressGuide()
                awaitLabel(device, By.textStartsWith("Large").focused(true))
                awaitFloatingOpacity(1f)
                capture("floating-focused")
                if (args.getString("checkFixturePixels") == "true") assertFixturePicture("floating-focused")
                pressGuide()
                awaitLabel(device, options, present = false)
                awaitFocusAt(xFocus)
                awaitFloatingOpacity(1f)
                pressProfile()
                assertTrue(withContext(Dispatchers.Main) { pip.pictureHidden })
                awaitFloatingOpacity(0f)
                awaitFocusAt(xFocus)
                capture("profile-pip-hidden")
                pressGuide()
                awaitLabel(device, options, present = false)
                pressProfile()
                assertFalse(withContext(Dispatchers.Main) { pip.pictureHidden })
                awaitFloatingOpacity(1f)
                awaitFocusAt(xFocus)
                assertEquals(generation, session.generation)
                device.pressDPadDown()
                pressGuide()
                awaitLabel(device, By.text("Go to video"))
                capture("floating-size-menu")
                if (args.getString("checkFixturePixels") == "true") assertFixturePicture("floating-size-menu")
                device.pressDPadUp()
                device.pressDPadCenter()
                awaitPicture(33)
                device.pressDPadDown()
                device.pressDPadDown()
                device.pressDPadCenter()
                awaitPicture(52)
                capture("floating-extra-large")

                device.pressDPadDown()
                awaitLabel(device, By.text("Move picture").focused(true))
                device.pressDPadCenter()
                awaitLabel(device, By.text("Arrows move · OK saves"))
                device.pressDPadLeft()
                awaitPicture(52, left = true)
                device.pressDPadDown()
                awaitPicture(52, left = true, top = false)
                device.pressDPadRight()
                awaitPicture(52, top = false)
                device.pressDPadUp()
                awaitPicture(52)
                device.pressDPadLeft()
                awaitPicture(52, left = true)
                device.pressDPadDown()
                awaitPicture(52, left = true, top = false)
                capture("floating-moved")
                device.pressDPadCenter()
                awaitLabel(device, By.textStartsWith("Extra large").focused(true))
                device.pressBack()
                awaitLabel(device, options, present = false)
                val beforeX = session.refreshSnapshot().positionSeconds
                delay(1_500)
                capture("floating-x-first")
                device.findObject(By.text("Search posts")).click()
                awaitLabel(device, By.text("Search posts on X"))
                device.executeShellCommand("input text Android%sTV")
                capture("floating-x-search")
                device.pressBack() // Keyboard
                awaitLabel(device, positiveButton)
                device.findObject(positiveButton).click()
                awaitLabel(device, By.text("X timeline"))
                device.pressDPadDown()
                device.pressDPadDown()
                delay(700)
                assertTrue(session.refreshSnapshot().positionSeconds > beforeX + 1)
                capture("floating-x-second")
                device.findObject(By.text("Full screen")).click()
                withTimeout(8_000) {
                    while (withContext(Dispatchers.Main) { pip.soloOnHome }) delay(100)
                }
                assertEquals(generation, session.generation)

                awaitLabel(device, By.text(returnLabel), present = false)
                pinPicture()
                device.pressHome()
                withTimeout(12_000) {
                    while (device.currentPackageName != launcher ||
                        !withContext(Dispatchers.Main) { pip.soloOnHome }) delay(100)
                }
                awaitPicture(52, left = true, top = false)
                delay(700)
                pressGuide()
                awaitLabel(device, By.textStartsWith("Extra large").focused(true))
                pressGuide()
                awaitLabel(device, options, present = false)
                assertEquals(launcher, device.currentPackageName)
                capture("unified-pip-home")
                val frameLog = File(context.filesDir, "receiver-diagnostics.log").readLines()
                    .last { it.contains("exo.picture.handoff.frame") }
                assertTrue(frameLog, frameLog.contains("gain=1.0"))
                assertTrue(device.executeShellCommand("am start -W -a android.intent.action.MAIN " +
                    "-c android.intent.category.LEANBACK_LAUNCHER -p $youtube").contains("Status: ok"))
                withTimeout(12_000) {
                    while (device.currentPackageName != youtube ||
                        !withContext(Dispatchers.Main) { pip.soloOnHome }) delay(100)
                }
                val beforeYoutube = session.refreshSnapshot().positionSeconds
                capture("floating-youtube-first")
                device.pressDPadRight()
                delay(2_500)
                capture("floating-youtube-second")
                assertEquals(youtube, device.currentPackageName)
                assertEquals(generation, session.generation)
                assertTrue(session.refreshSnapshot().positionSeconds > beforeYoutube + 1)
                awaitPicture(52, left = true, top = false)
                pressGuide()
                awaitLabel(device, By.textStartsWith("Extra large").focused(true))
                pressGuide()
                awaitLabel(device, options, present = false)
                withTimeout(8_000) {
                    while (device.currentPackageName != youtube) delay(100)
                }
                pressGuide()
                awaitLabel(device, By.text("Go to video"))
                device.pressDPadDown()
                device.pressDPadDown()
                awaitLabel(device, By.text("Go to video").focused(true))
                device.pressDPadCenter()
                withTimeout(8_000) {
                    while (device.currentPackageName != context.packageName ||
                        withContext(Dispatchers.Main) { pip.soloOnHome }) delay(100)
                }
                assertEquals(generation, session.generation)
                assertFalse(device.executeShellCommand("dumpsys window windows")
                    .contains("4789 floating video"))
            } catch (failure: Throwable) {
                capture("floating-test-failure")
                device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "floating-test-failure.xml"))
                throw failure
            } finally {
                config.waitForIdleTimeout = previousIdle
                withContext(Dispatchers.Main) { pip.clear() }
                session.stop()
                device.executeShellCommand("cmd appops set ${context.packageName} SYSTEM_ALERT_WINDOW default")
                device.executeShellCommand("settings put secure enabled_accessibility_services $previousServices")
                device.executeShellCommand("settings put secure accessibility_enabled $previousEnabled")
                device.executeShellCommand("cmd appops set ${context.packageName} ACCESS_RESTRICTED_SETTINGS default")
                preferences.edit().putString(sizeKey, previousSize).putString(mappings.PREFERENCE_KEY, previousBindings).apply {
                    if (previousPosition == null) remove(positionKey) else putInt(positionKey, previousPosition)
                }.commit()
                config.uiAutomationFlags = previousAutomationFlags
            }
        }
    }

    @Test fun movieCornerAndMoreChannelPickerKeepTheirOwnActions() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("inAppPip") == "true")
        check(args.getString("disposable") == "true" && Build.HARDWARE in setOf("ranchu", "goldfish"))
        val base = requireNotNull(args.getString("fixtureBase"))
        check(base.startsWith("http://127.0.0.1:") || base.startsWith("http://10.0.2.2:"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val config = androidx.test.uiautomator.Configurator.getInstance()
        val previousIdle = config.waitForIdleTimeout
        config.waitForIdleTimeout = 100
        val session = context.appGraph.playback
        val pip = context.appGraph.pictureInPicture
        context.startActivity(requireNotNull(context.packageManager.getLeanbackLaunchIntentForPackage(context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        runBlocking {
            try {
                val repo = context.clientGraph.iptv
                repo.awaitLoaded()
                if (repo.state.value.sources.none { it.name == "PiP fixture" }) {
                    check(repo.addLink("PiP fixture", "$base/fixture.m3u"))
                } else repo.refresh()
                val catalog = withTimeout(30_000) {
                    repo.state.first { state -> state.channels.any { it.name == "Match fixture" } &&
                        state.catalog.vod.any { it.title == "Movie fixture" } }
                }
                val movie = catalog.catalog.vod.first { it.title == "Movie fixture" }
                withTimeout(15_000) { session.surfaceReadyState.first { it } }
                session.openIptvVod(OpenMediaRequest(requireNotNull(repo.vodUrl(movie)), title = movie.title), movie).getOrThrow()
                withTimeout(15_000) { session.phase.first { it is ReceiverPlaybackPhase.Playing } }
                withTimeout(12_000) {
                    while (!device.hasObject(By.desc("PiP"))) {
                        device.pressDPadUp()
                        delay(350)
                    }
                }
                device.pressDPadLeft()
                device.pressDPadCenter()
                withTimeout(5_000) { androidx.compose.runtime.snapshotFlow { pip.movieInCorner }.first { it } }
                val generation = session.generation
                val before = session.refreshSnapshot().positionSeconds
                delay(2_500)
                assertTrue(session.refreshSnapshot().positionSeconds - before > 1)
                assertTrue(pip.movieInCorner && pip.channel == null)
                val evidence = File(context.getExternalFilesDir(null), "movie-corner.png")
                device.takeScreenshot(evidence)
                device.pressKeyCode(android.view.KeyEvent.KEYCODE_CHANNEL_UP)
                awaitLabel(device, By.text("Return to player"), false)
                assertTrue(pip.movieInCorner)
                pressGuideInReceiver()
                choosePictureOption(device, "Go to video")
                withTimeout(5_000) { androidx.compose.runtime.snapshotFlow { pip.movieInCorner }.first { !it } }
                assertEquals(generation, session.generation)
                // The movie icon pins the movie. More still provides the live-channel picker.
                withTimeout(10_000) {
                    while (!device.hasObject(By.desc("More"))) {
                        if (Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
                        device.pressDPadUp(); delay(300)
                    }
                }
                fun focused(label: String) = device.hasObject(By.focused(true).hasDescendant(By.desc(label))) ||
                    device.hasObject(By.focused(true).hasDescendant(By.text(label))) ||
                    device.hasObject(By.desc(label).focused(true))
                withTimeout(15_000) {
                    while (!focused("More")) {
                        if (Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
                        when {
                            !device.hasObject(By.desc("More")) -> device.pressDPadUp()
                            !device.hasObject(By.focused(true)) || focused("Playback position") -> device.pressDPadDown()
                            else -> device.pressDPadRight()
                        }
                        delay(130)
                    }
                }
                device.pressDPadCenter()
                awaitLabel(device, By.text("Add live channel"))
                withTimeout(8_000) {
                    while (!focused("Add live channel")) {
                        if (Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
                        device.pressDPadUp(); delay(150)
                    }
                }
                device.pressDPadCenter()
                awaitLabel(device, By.text("Favourites"))
                device.pressDPadCenter()
                awaitLabel(device, By.text("No favourite channels yet."))
                device.pressBack()
                awaitLabel(device, By.text("Add live channel"))
                device.pressDPadDown()
                device.pressDPadDown()
                device.pressDPadCenter()
                awaitLabel(device, By.text("Match fixture"))
                device.pressDPadCenter()
                withTimeout(5_000) { androidx.compose.runtime.snapshotFlow { pip.channel?.name }.first { it == "Match fixture" } }

                device.waitForIdle()
                pressGuideInReceiver()
                awaitLabel(device, By.text("Swap pictures"))
                awaitLabel(device, By.text("Switch sound to match"))
                awaitLabel(device, By.text("Move small picture"))
                awaitLabel(device, By.text("Close match"))
                assertEquals(generation, session.generation)
                device.takeScreenshot(File(context.getExternalFilesDir(null), "guide-paired-options.png"))
                pressGuideInReceiver()
                awaitLabel(device, By.text("Swap pictures"), present = false)
                assertEquals(generation, session.generation)
                device.takeScreenshot(File(context.getExternalFilesDir(null), "pip-movie-match.png"))
                device.pressBack()
                withTimeout(5_000) { androidx.compose.runtime.snapshotFlow { pip.browseOnly }.first { it } }
                awaitLabel(device, By.text("Return to live TV"), false)
                withContext(Dispatchers.Main) {
                    assertTrue(pip.browseOnly && pip.liveSound)
                    assertEquals(null, session.origin.value)
                }
                session.stop().getOrThrow()
                withContext(Dispatchers.Main) { assertNull(pip.channel) }
            } catch (failure: Throwable) {
                device.takeScreenshot(java.io.File(context.getExternalFilesDir(null), "pip-picker-failure.png"))
                device.dumpWindowHierarchy(java.io.File(context.getExternalFilesDir(null), "pip-picker-failure.xml"))
                throw failure
            } finally {
                config.waitForIdleTimeout = previousIdle
                withContext(Dispatchers.Main) { pip.clear() }
                session.stop()
            }
        }
    }

    @Test fun channelShortcutPinsSeriesEpisodeAndReturnsWithoutReopening() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("inAppPip") == "true")
        check(args.getString("disposable") == "true" && Build.HARDWARE in setOf("ranchu", "goldfish"))
        val base = requireNotNull(args.getString("fixtureBase"))
        check(base.startsWith("http://127.0.0.1:") || base.startsWith("http://10.0.2.2:"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val config = androidx.test.uiautomator.Configurator.getInstance()
        val previousIdle = config.waitForIdleTimeout
        config.waitForIdleTimeout = 100
        val session = context.appGraph.playback
        val pip = context.appGraph.pictureInPicture
        val prefs = context.appGraph.presentationPreferences
        val original = prefs.getString(com.fourseveneightnine.tv.ui.RemoteButtonMappings.PREFERENCE_KEY, null)
        context.startActivity(requireNotNull(context.packageManager.getLeanbackLaunchIntentForPackage(context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        suspend fun expandWithGuide() {
            pressGuideInReceiver()
            choosePictureOption(device, "Go to video")
            awaitLabel(device, By.text("Return to player"), present = false)
            withTimeout(5_000) { while (pip.movieInCorner) delay(100) }
        }

        runBlocking {
            try {
                withTimeout(15_000) { session.surfaceReadyState.first { it } }
                val episode = com.fourseveneightnine.tv.client.playback.PlayRequest("series", "fixture:corner",
                    "Corner fixture", season = 1, episode = 2)
                session.open(OpenMediaRequest("$base/movie.mp4", title = "Corner fixture · S1 E2", startPositionMs = 12_000),
                    com.fourseveneightnine.tv.client.playback.PlaybackSession.Origin.Local, catalogRequest = episode).getOrThrow()
                withTimeout(15_000) { session.phase.first { it is ReceiverPlaybackPhase.Playing } }
                val gen = session.generation
                withTimeout(8_000) {
                    while (!pip.movieInCorner) { device.pressKeyCode(android.view.KeyEvent.KEYCODE_CHANNEL_UP); delay(250) }
                }
                withTimeout(5_000) { androidx.compose.runtime.snapshotFlow { pip.movieInCorner }.first { it } }
                val before = session.refreshSnapshot().positionSeconds
                delay(2_500)
                assertTrue(session.refreshSnapshot().positionSeconds - before > 1)
                device.takeScreenshot(File(context.getExternalFilesDir(null), "episode-corner.png"))
                if (args.getString("pipPlaylist") == "true") {
                    device.executeShellCommand("input keyevent KEYCODE_HOME")
                    awaitLabel(device, By.desc("Return to 4789 playback"))
                    withTimeout(8_000) { while (!pip.soloOnHome) delay(100) }
                    delay(2_000)
                    device.takeScreenshot(File(context.getExternalFilesDir(null), "episode-launcher-corner.png"))
                    device.findObject(By.desc("Return to 4789 playback")).click()
                    withTimeout(5_000) { androidx.compose.runtime.snapshotFlow { pip.movieInCorner }.first { it } }
                }
                expandWithGuide()
                assertEquals(gen, session.generation)
                assertTrue(session.refreshSnapshot().positionSeconds >= before)
                withContext(Dispatchers.Main) {
                    com.fourseveneightnine.tv.ui.RemoteButtonMappings.save(prefs, android.view.KeyEvent.KEYCODE_CHANNEL_UP,
                        true, com.fourseveneightnine.tv.ui.RemoteButtonAction.Controls)
                }
                withTimeout(8_000) {
                    while (!pip.movieInCorner) { device.pressKeyCode(android.view.KeyEvent.KEYCODE_CHANNEL_DOWN); delay(250) }
                }
                device.pressKeyCode(android.view.KeyEvent.KEYCODE_CHANNEL_DOWN)
                assertTrue(pip.movieInCorner)
                expandWithGuide()
                withContext(Dispatchers.Main) {
                    // No free channel key remains: a tap must still run the user's Controls action.
                    com.fourseveneightnine.tv.ui.RemoteButtonMappings.save(prefs, android.view.KeyEvent.KEYCODE_CHANNEL_DOWN,
                        true, com.fourseveneightnine.tv.ui.RemoteButtonAction.SeekBackward)
                }
                delay(450)
                device.pressKeyCode(android.view.KeyEvent.KEYCODE_CHANNEL_UP)
                awaitLabel(device, By.desc("More"))
                assertFalse(pip.movieInCorner)
                pressGuideInReceiver()
                withTimeout(5_000) { androidx.compose.runtime.snapshotFlow { pip.movieInCorner }.first { it } }
                assertEquals(gen, session.generation)
                expandWithGuide()
                holdKey(android.view.KeyEvent.KEYCODE_CHANNEL_UP)
                withTimeout(5_000) { androidx.compose.runtime.snapshotFlow { pip.movieInCorner }.first { it } }
                pressGuideInReceiver()
                choosePictureOption(device, "Close picture")
                withTimeout(8_000) {
                    session.origin.first { it == null }
                    session.phase.first { it == ReceiverPlaybackPhase.Stopped || it == ReceiverPlaybackPhase.Idle }
                }
                assertTrue(session.generation > gen)
                assertFalse(pip.movieInCorner)
            } catch (failure: Throwable) {
                device.takeScreenshot(File(context.getExternalFilesDir(null), "episode-corner-failure.png"))
                device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "episode-corner-failure.xml"))
                throw AssertionError("phase=${session.phase.value} origin=${session.origin.value} corner=${pip.movieInCorner}", failure)
            } finally {
                config.waitForIdleTimeout = previousIdle
                prefs.edit().putString(com.fourseveneightnine.tv.ui.RemoteButtonMappings.PREFERENCE_KEY, original).apply()
                withContext(Dispatchers.Main) { pip.clear() }
                session.stop()
            }
        }
    }

    @Test fun selectingLiveFromPinnedMovieKeepsTheMovieInTheCorner() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("inAppPip") == "true")
        check(args.getString("disposable") == "true" && Build.HARDWARE in setOf("ranchu", "goldfish"))
        val base = requireNotNull(args.getString("fixtureBase"))
        check(base.startsWith("http://127.0.0.1:") || base.startsWith("http://10.0.2.2:"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val session = context.appGraph.playback
        val pip = context.appGraph.pictureInPicture
        context.startActivity(requireNotNull(context.packageManager.getLeanbackLaunchIntentForPackage(context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        runBlocking {
            try {
                val repo = context.clientGraph.iptv
                repo.awaitLoaded()
                if (repo.state.value.sources.none { it.name == "PiP fixture" }) check(repo.addLink("PiP fixture", "$base/fixture.m3u"))
                val state = withTimeout(30_000) { repo.state.first { it.channels.any { c -> c.name == "Match fixture" } } }
                val match = state.channels.first { it.name == "Match fixture" }
                withTimeout(15_000) { session.surfaceReadyState.first { it } }
                session.open(OpenMediaRequest("$base/movie.mp4", title = "Corner movie"),
                    com.fourseveneightnine.tv.client.playback.PlaybackSession.Origin.Local).getOrThrow()
                withTimeout(15_000) { session.phase.first { it == ReceiverPlaybackPhase.Playing } }
                val gen = session.generation
                withTimeout(8_000) {
                    while (!pip.movieInCorner) { device.pressKeyCode(android.view.KeyEvent.KEYCODE_CHANNEL_UP); delay(250) }
                }
                context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(
                    "content://${context.packageName}.search/launcher-live-guide")).setPackage(context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                awaitLabel(device, By.text("Channels"))
                withContext(Dispatchers.Main) {
                    com.fourseveneightnine.tv.client.ui.screens.live.LiveTvLaunch.stage("channel", match.id)
                }
                awaitLabel(device, By.text("Play live"))
                delay(350)
                device.pressDPadCenter()
                withTimeout(8_000) { while (pip.channel?.id != match.id) delay(100) }
                assertTrue(pip.liveLarge && pip.liveSound && !pip.browseOnly)
                assertEquals(gen, session.generation)
                device.executeShellCommand("dumpsys SurfaceFlinger --timestats -clear -enable")
                try {
                    delay(5_000)
                    val stats = device.executeShellCommand("dumpsys SurfaceFlinger --timestats -dump")
                    val moving = stats.split("layerName = ").count {
                        it.startsWith("SurfaceView[${context.packageName}/") &&
                            (Regex("totalFrames = (\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0) > 5
                    }
                    assertEquals("Movie and selected match must both advance", 2, moving)
                    device.takeScreenshot(File(context.getExternalFilesDir(null), "movie-small-match-large.png"))
                } finally { device.executeShellCommand("dumpsys SurfaceFlinger --timestats -disable") }
            } finally { withContext(Dispatchers.Main) { pip.clear() }; session.stop() }
        }
    }

    @Test fun fourLiveChannelsResizeAndKeepSoundWhenTilesChange() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("multiviewFixture") != null)
        val base = requireNotNull(args.getString("multiviewFixture"))
        check(args.getString("disposable") == "true" && Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(base.startsWith("http://127.0.0.1:") || base.startsWith("http://10.0.2.2:"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val session = context.appGraph.playback
        val repo = context.clientGraph.iptv
        val output = File(context.getExternalFilesDir(null), "remote-checks").apply { mkdirs() }
        val config = androidx.test.uiautomator.Configurator.getInstance()
        val previousIdle = config.waitForIdleTimeout
        config.waitForIdleTimeout = 100
        fun refresh() {
            if (Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
        }
        fun waitFor(selector: androidx.test.uiautomator.BySelector, present: Boolean = true) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 12_000
            do {
                refresh()
                if (device.hasObject(selector) == present) return
                Thread.sleep(100)
            } while (android.os.SystemClock.elapsedRealtime() < deadline)
            error("Expected $selector present=$present")
        }
        fun focus(label: String) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 12_000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                try {
                    refresh()
                    val current = device.findObject(By.focused(true))?.visibleBounds
                    val target = (device.findObject(By.desc(label)) ?: device.findObject(By.descStartsWith("$label, "))
                        ?: device.findObject(By.text(label)))?.visibleBounds
                    if (target != null && current != null) {
                        if (current.contains(target.centerX(), target.centerY())) return
                        when {
                            current.bottom <= target.top -> device.pressDPadDown()
                            current.top >= target.bottom -> device.pressDPadUp()
                            current.centerX() < target.centerX() -> device.pressDPadRight()
                            else -> device.pressDPadLeft()
                        }
                    }
                } catch (_: androidx.test.uiautomator.StaleObjectException) {
                    // Compose can replace an accessibility node while opening a side panel.
                }
                Thread.sleep(120)
            }
            error("D-pad could not reach $label")
        }
        fun choose(label: String) {
            focus(label)
            Thread.sleep(120)
            device.pressDPadCenter()
            Thread.sleep(350)
            refresh()
        }
        fun tile(name: String, audible: Boolean) = By.descStartsWith("$name, ${if (audible) "sound on" else "muted"}")
        fun verifyLayout(count: Int) {
            val ids = (1..count).map { i ->
                device.wait(Until.findObject(By.descStartsWith("Court $i, ")), 8_000)?.visibleBounds
                    ?: error("Court $i missing from $count")
            }
            assertTrue(ids.all { it.width() > 0 && it.height() > 0 })
            assertTrue(ids.indices.all { i -> (i + 1 until ids.size).all { j -> !android.graphics.Rect.intersects(ids[i], ids[j]) } })
            if (count == 3) {
                assertTrue(ids[0].width() > ids[1].width())
                assertTrue(ids[1].left == ids[2].left && ids[1].bottom < ids[2].top)
            } else {
                assertTrue(kotlin.math.abs(ids[0].width() - ids[1].width()) <= 1 && ids[0].top == ids[1].top)
                if (count == 4) assertTrue(ids[0].left == ids[2].left && ids[2].top == ids[3].top)
            }
            assertEquals(1, device.findObjects(By.descContains(", sound on,")).size)
            runBlocking {
                withTimeout(20_000) {
                    while (device.hasObject(By.textStartsWith("Opening Court"))) delay(150)
                }
            }
            assertFalse(device.hasObject(By.textContains("could not play")))
            device.executeShellCommand("dumpsys SurfaceFlinger --timestats -clear -enable")
            try {
                Thread.sleep(5_000)
                device.takeScreenshot(File(output, "multiview-$count.png"))
                val frames = device.executeShellCommand("dumpsys SurfaceFlinger --timestats -dump")
                File(output, "multiview-$count-frames.txt").writeText(frames)
                val surfaces = frames.split("layerName = ").filter { it.startsWith("SurfaceView[${context.packageName}/") }
                assertEquals("Each channel needs its own video surface", count, surfaces.size)
                assertTrue("Every surface must present frames", surfaces.all {
                    (Regex("totalFrames = (\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0) > 1
                })
                File(output, "multiview-$count-memory.txt").writeText(device.executeShellCommand("dumpsys meminfo ${context.packageName}"))
            } finally { device.executeShellCommand("dumpsys SurfaceFlinger --timestats -disable") }
        }
        context.startActivity(requireNotNull(context.packageManager.getLeanbackLaunchIntentForPackage(context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            runBlocking {
                withTimeout(15_000) { session.surfaceReadyState.first { it } }
                session.stop().getOrThrow()
                repo.awaitLoaded()
                if (repo.state.value.sources.none { it.host == base }) check(repo.addXtream("Four courts", base, "qa", "qa"))
                val sourceId = repo.state.value.sources.first { it.host == base }.id
                val state = withTimeout(30_000) { repo.state.first { snapshot ->
                    snapshot.channels.count { it.sourceId == sourceId && it.name.startsWith("Court ") } == 4
                } }
                val channel = state.channels.first { it.sourceId == sourceId && it.name == "Court 1" }
                if (args.getString("multiviewFromPlayer") == "true") {
                    session.openLiveChannel(OpenMediaRequest(repo.playUrl(channel), title = channel.name, isLive = true),
                        com.fourseveneightnine.tv.client.playback.PlaybackSession.LiveChannel(channel.id, channel.sourceId, channel.group)).getOrThrow()
                    withTimeout(20_000) { session.phase.first { it is ReceiverPlaybackPhase.Playing } }
                    withTimeout(12_000) {
                        while (true) {
                            refresh()
                            if (device.hasObject(By.desc("More")) && device.hasObject(By.focused(true))) break
                            if (device.hasObject(By.desc("Full screen"))) choose("Full screen") else device.pressDPadDown()
                            delay(200)
                        }
                    }
                    choose("More")
                } else {
                    context.startActivity(Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse("content://${context.packageName}.search/launcher-live-guide"))
                        .setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    waitFor(By.text("Channels"))
                    withContext(Dispatchers.Main) {
                        com.fourseveneightnine.tv.client.ui.screens.live.LiveTvLaunch.stage("channel", channel.id)
                    }
                }
            }
            waitFor(By.text("Open in Multiview"))
            Thread.sleep(350)
            choose("Open in Multiview")
            waitFor(tile("Court 1", true))
            for (count in 2..4) {
                choose("Add channel")
                waitFor(By.text("Court $count"))
                choose("Court $count")
                verifyLayout(count)
                if (count == 2) {
                    focus("Court 1")
                    device.pressDPadRight()
                    choose("Court 2")
                    val sound = device.wait(Until.findObject(By.text("● Sound")), 5_000).visibleCenter
                    assertTrue("Sound indicator must sit under Court 2",
                        device.findObject(By.descStartsWith("Court 2, ")).visibleBounds.contains(sound.x, sound.y))
                }
            }
            assertFalse(device.hasObject(By.text("Add channel")))
            focus("Court 4")
            holdKey(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor(By.descStartsWith("Court 4, "), false)
            choose("Enlarge sound tile")
            val large = device.findObject(tile("Court 2", true)).visibleBounds
            assertTrue(large.width() > device.findObject(tile("Court 1", false)).visibleBounds.width())
            focus("Court 2")
            holdKey(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor(tile("Court 1", true))
            device.pressBack()
            waitFor(By.text("Multiview"), false)
        } catch (failure: Throwable) {
            device.takeScreenshot(File(output, "multiview-failure.png"))
            device.dumpWindowHierarchy(File(output, "multiview-failure.xml"))
            throw failure
        } finally { config.waitForIdleTimeout = previousIdle; runBlocking { session.stop() } }
    }

    @Test fun liveSurvivesHomeAndMovieResumesWithPreviousLayoutAndSound() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("pipPlaylist") == "true")
        check(args.getString("disposable") == "true" && Build.HARDWARE in setOf("ranchu", "goldfish"))
        val base = requireNotNull(args.getString("fixtureBase"))
        check(base.startsWith("http://127.0.0.1:") || base.startsWith("http://10.0.2.2:"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val repo = context.clientGraph.iptv
        val graph = context.appGraph
        val launch = requireNotNull(context.packageManager.getLeanbackLaunchIntentForPackage(context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        runBlocking {
            try {
                repo.awaitLoaded()
                if (repo.state.value.sources.none { it.name == "PiP fixture" })
                    check(repo.addLink("PiP fixture", "$base/fixture.m3u"))
                val catalog = withTimeout(30_000) {
                    repo.state.first { it.channels.any { c -> c.name == "Match fixture" } &&
                        it.catalog.vod.any { v -> v.title == "Movie fixture" } }
                }
                val movie = catalog.catalog.vod.first { it.title == "Movie fixture" }
                val match = catalog.channels.first { it.name == "Match fixture" }
                withTimeout(15_000) { graph.playback.surfaceReadyState.first { it } }
                graph.playback.openLiveChannel(OpenMediaRequest(repo.playUrl(match), title = match.name, isLive = true),
                    com.fourseveneightnine.tv.client.playback.PlaybackSession.LiveChannel(match.id, match.sourceId, match.group)).getOrThrow()
                withTimeout(15_000) { graph.playback.phase.first { it is ReceiverPlaybackPhase.Playing } }
                val liveGeneration = graph.playback.generation
                withContext(Dispatchers.Main) {
                    var didBrowse = false
                    graph.pictureInPicture.pin(match, true) { didBrowse = true }
                    assertTrue(didBrowse && graph.pictureInPicture.nativeBrowse)
                    assertEquals(liveGeneration, graph.playback.generation)
                    var didReturn = false
                    graph.pictureInPicture.expandBrowse { didReturn = true }
                    assertTrue(didReturn)
                    assertEquals(liveGeneration, graph.playback.generation)
                    assertEquals(match.id, graph.playback.liveChannel.value?.id)
                }
                graph.playback.openIptvVod(OpenMediaRequest(requireNotNull(repo.vodUrl(movie)),
                    title = movie.title, startPositionMs = 30_000), movie).getOrThrow()
                withTimeout(15_000) { graph.playback.phase.first { it is ReceiverPlaybackPhase.Playing } }
                val pinned = CompletableDeferred<Unit>()
                withContext(Dispatchers.Main) {
                    graph.pictureInPicture.pin(match, false) { pinned.complete(Unit) }
                }
                withTimeout(10_000) { pinned.await() }
                withContext(Dispatchers.Main) { graph.pictureInPicture.swap(); graph.pictureInPicture.switchSound() }
                val before = graph.playback.refreshSnapshot().positionSeconds
                device.executeShellCommand("input keyevent KEYCODE_HOME")
                assertTrue(device.wait(Until.hasObject(By.desc("Return to 4789 playback")), 10_000))
                withTimeout(10_000) { graph.playback.origin.first { it == null } }
                val saved = requireNotNull(graph.playback.pendingResume(context.clientGraph.activeProfileId().toString()))
                assertTrue(kotlin.math.abs(saved.positionMillis / 1000.0 - before) < 3.0)
                delay(4_000)
                device.takeScreenshot(File(context.getExternalFilesDir(null), "pip-home.png"))
                device.pressHome()
                assertTrue(device.wait(Until.hasObject(By.desc("Return to 4789 playback")), 5_000))
                // Den Home's tile can be selected like any other launcher widget.
                val tile = device.findObject(By.desc("Return to 4789 playback"))
                assertNotNull(tile)
                tile.click()
                withTimeout(15_000) { graph.playback.phase.first { it is ReceiverPlaybackPhase.Playing } }
                val after = graph.playback.refreshSnapshot().positionSeconds
                assertTrue("Resume position $after, saved ${saved.positionMillis}",
                    kotlin.math.abs(after - saved.positionMillis / 1000.0) < 3.0)
                withContext(Dispatchers.Main) {
                    assertEquals(match.id, graph.pictureInPicture.channel?.id)
                    assertTrue(graph.pictureInPicture.liveLarge && graph.pictureInPicture.liveSound)
                }
            } finally {
                withContext(Dispatchers.Main) { graph.pictureInPicture.clear() }
                graph.playback.stop()
            }
        }
    }
}
