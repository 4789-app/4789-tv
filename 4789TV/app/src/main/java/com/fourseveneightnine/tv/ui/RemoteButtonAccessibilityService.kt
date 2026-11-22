package com.fourseveneightnine.tv.ui

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.fourseveneightnine.tv.client.appGraph
import com.fourseveneightnine.tv.client.ui.MainActivity
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Opt-in shortcuts for the receiver and its floating video. No window content is retrieved. */
class RemoteButtonAccessibilityService : AccessibilityService() {
    private val consumed = mutableSetOf<Pair<Int, Int>>()
    private val floatingControlPresses = mutableSetOf<Pair<Int, Int>>()

    override fun onServiceConnected() {
        connectedState.value = true
        android.util.Log.i("4789Remote", "accessibility connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        connectedState.value = false
        consumed.clear()
        floatingControlPresses.clear()
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean = filterKeyEvent(event)

    internal fun filterKeyEvent(event: KeyEvent): Boolean {
        if (!RemoteButtonMappings.isReservedKey(event.keyCode)) return false
        val press = event.deviceId to event.keyCode
        val guide = event.keyCode == KeyEvent.KEYCODE_GUIDE
        if (guide || event.keyCode == KeyEvent.KEYCODE_PROFILE_SWITCH) {
            if (press in floatingControlPresses) {
                if (event.action == KeyEvent.ACTION_UP) floatingControlPresses.remove(press)
                return true
            }
            if (connected.value && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val pip = appGraph.pictureInPicture
                val handled = if (guide) pip.toggleCrossAppControls() else pip.toggleCrossAppVisibility()
                if (handled) {
                    floatingControlPresses.add(press)
                    android.util.Log.i("4789Remote", "floating picture shortcut handled: ${event.keyCode}")
                    return true
                }
            }
        }
        if (press in consumed) {
            foregroundActivity?.get()?.handleReservedRemoteButton(event)
            if (event.action == KeyEvent.ACTION_UP) consumed.remove(press)
            // Keep the matching release consumed even if the app lost focus during the press.
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return false
        val activity = foregroundActivity?.get() ?: return false
        val handled = activity.handleReservedRemoteButton(event)
        if (handled) consumed.add(press)
        if (activity.hasWindowFocus()) android.util.Log.i("4789Remote",
            "accessibility code=${event.keyCode} scan=${event.scanCode} device=${event.deviceId} handled=$handled")
        return handled
    }

    companion object {
        private var foregroundActivity: WeakReference<MainActivity>? = null
        private val connectedState = MutableStateFlow(false)
        internal val connected = connectedState.asStateFlow()

        internal fun attach(activity: MainActivity) { foregroundActivity = WeakReference(activity) }
        internal fun detach(activity: MainActivity) {
            if (foregroundActivity?.get() === activity) foregroundActivity = null
        }
    }
}
