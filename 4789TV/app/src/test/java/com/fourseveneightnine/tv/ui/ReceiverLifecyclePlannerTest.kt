package com.fourseveneightnine.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverLifecyclePlannerTest {
    @Test
    fun retiredHostCannotReattachOrRestartAfterReplacementClaimsReceiver() {
        val retired = ReceiverLifecycleState(
            activityStarted = true,
            surfaceReady = true,
            transportRunning = false,
            advertisingRequested = false,
            surfaceAvailable = true,
            ownsReceiver = false,
        )
        assertFalse(ReceiverLifecyclePlanner.shouldAttachSurface(retired))
        assertEquals(ReceiverLifecycleAction.Idle, ReceiverLifecyclePlanner.next(retired))
        assertEquals(ReceiverLifecycleAction.Idle,
            ReceiverLifecyclePlanner.next(retired.copy(surfaceReady = false, transportRunning = true)))
        val replacement = retired.copy(ownsReceiver = true)
        assertTrue(ReceiverLifecyclePlanner.shouldAttachSurface(replacement))
        assertEquals(ReceiverLifecycleAction.StartTransport, ReceiverLifecyclePlanner.next(replacement))
    }

    @Test
    fun startsTransportBeforeDiscoveryWhenActivityAndSurfaceAreReady() {
        assertEquals(
            ReceiverLifecycleAction.StartTransport,
            ReceiverLifecyclePlanner.next(
                ReceiverLifecycleState(
                    activityStarted = true,
                    surfaceReady = true,
                    transportRunning = false,
                    advertisingRequested = false,
                ),
            ),
        )
    }

    @Test
    fun waitsForReadySurfaceBeforeStartingTransport() {
        assertEquals(
            ReceiverLifecycleAction.Idle,
            ReceiverLifecyclePlanner.next(
                ReceiverLifecycleState(
                    activityStarted = true,
                    surfaceReady = false,
                    transportRunning = false,
                    advertisingRequested = false,
                ),
            ),
        )
    }

    @Test
    fun advertisesOnlyWithStartedActivityAndReadySurface() {
        assertEquals(
            ReceiverLifecycleAction.StartAdvertising,
            ReceiverLifecyclePlanner.next(
                ReceiverLifecycleState(
                    activityStarted = true,
                    surfaceReady = true,
                    transportRunning = true,
                    advertisingRequested = false,
                ),
            ),
        )
    }

    @Test
    fun surfaceLossTearsDownDiscoveryAndTransport() {
        assertEquals(
            ReceiverLifecycleAction.StopAll,
            ReceiverLifecyclePlanner.next(
                ReceiverLifecycleState(
                    activityStarted = true,
                    surfaceReady = false,
                    transportRunning = true,
                    advertisingRequested = true,
                ),
            ),
        )
    }

    @Test
    fun surfaceLossTearsDownAnOwnedTransportWithoutAdvertisingOrPlaybackState() {
        assertEquals(
            ReceiverLifecycleAction.StopAll,
            ReceiverLifecyclePlanner.next(
                ReceiverLifecycleState(
                    activityStarted = true,
                    surfaceReady = false,
                    transportRunning = true,
                    advertisingRequested = false,
                ),
            ),
        )
    }

    @Test
    fun stoppingActivityTearsDownDiscoveryAndTransport() {
        assertEquals(
            ReceiverLifecycleAction.StopAll,
            ReceiverLifecyclePlanner.next(
                ReceiverLifecycleState(
                    activityStarted = false,
                    surfaceReady = true,
                    transportRunning = true,
                    advertisingRequested = true,
                ),
            ),
        )
    }

    @Test
    fun startedActivityReattachesAStillValidHolderAfterLateTeardown() {
        val state = ReceiverLifecycleState(
            activityStarted = true,
            surfaceReady = false,
            transportRunning = false,
            advertisingRequested = false,
            surfaceAttached = false,
            surfaceAvailable = true,
        )

        assertTrue(ReceiverLifecyclePlanner.shouldAttachSurface(state))
    }
}
