package com.fourseveneightnine.tv.ui

/**
 * Android-free lifecycle policy for the Activity-owned TV receiver.
 *
 * A receiver session is valid only while the Activity is visible and its video Surface is ready.
 * A phone must never be able to reach a TV that cannot display the requested video.
 */
internal data class ReceiverLifecycleState(
    val activityStarted: Boolean,
    val surfaceReady: Boolean,
    val transportRunning: Boolean,
    val advertisingRequested: Boolean,
    val surfaceAttached: Boolean = false,
    val surfaceAvailable: Boolean = false,
)

internal enum class ReceiverLifecycleAction {
    StartTransport,
    StartAdvertising,
    StopAll,
    Idle,
}

internal object ReceiverLifecyclePlanner {
    /**
     * A stopped session can finish after a new onStart. Reattaching the still-valid holder here
     * keeps that late detach from stranding the Activity without another Surface callback.
     */
    fun shouldAttachSurface(state: ReceiverLifecycleState): Boolean =
        state.activityStarted && state.surfaceAvailable && !state.surfaceAttached

    fun next(state: ReceiverLifecycleState): ReceiverLifecycleAction {
        val visibleSession = state.activityStarted && state.surfaceReady
        return when {
            !visibleSession && (state.transportRunning || state.advertisingRequested) -> {
                ReceiverLifecycleAction.StopAll
            }

            !visibleSession -> ReceiverLifecycleAction.Idle
            !state.transportRunning -> ReceiverLifecycleAction.StartTransport
            !state.advertisingRequested -> ReceiverLifecycleAction.StartAdvertising
            else -> ReceiverLifecycleAction.Idle
        }
    }
}
