package com.fourseveneightnine.tv.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NsdAdvertisementPlannerTest {
    @Test
    fun startDuringPendingUnregistrationRestartsOnlyAfterOldRegistrationIsGone() {
        val registered = NsdAdvertisementState(
            advertisingDesired = true,
            registration = NsdRegistrationState(registered = true),
        )

        val stopping = NsdAdvertisementPlanner.stop(registered)
        assertEquals(NsdAdvertisementCommand.Unregister, stopping.command)
        assertFalse(stopping.state.advertisingDesired)

        val restartRequested = NsdAdvertisementPlanner.start(stopping.state)
        assertEquals(NsdAdvertisementCommand.None, restartRequested.command)
        assertTrue(restartRequested.state.advertisingDesired)
        assertTrue(restartRequested.state.registration?.stopRequested == true)
        assertTrue(restartRequested.state.registration?.unregistrationRequested == true)

        val unregistered = NsdAdvertisementPlanner.serviceUnregistered(restartRequested.state)
        assertEquals(NsdAdvertisementCommand.Register, unregistered.command)
        assertTrue(unregistered.state.advertisingDesired)
        assertNotNull(unregistered.state.registration)
        assertFalse(unregistered.state.registration?.registered == true)
    }

    @Test
    fun unregistrationFailureSchedulesAnEligibleRetry() {
        val stopping = NsdAdvertisementState(
            registration = NsdRegistrationState(
                registered = true,
                stopRequested = true,
                unregistrationRequested = true,
            ),
        )

        val failed = NsdAdvertisementPlanner.unregistrationFailed(stopping)
        assertEquals(NsdAdvertisementCommand.ScheduleUnregisterRetry, failed.command)
        assertEquals(250L, failed.retryDelayMillis)
        assertNotNull(failed.state.registration)
        assertTrue(failed.state.registration?.stopRequested == true)
        assertFalse(failed.state.registration?.unregistrationRequested == true)
        assertTrue(failed.state.registration?.unregistrationRetryScheduled == true)
    }

    @Test
    fun retryPreservesAdvertisingDesiredAndRestoresUnregisterCommand() {
        val stopping = NsdAdvertisementState(
            advertisingDesired = true,
            registration = NsdRegistrationState(
                registered = true,
                stopRequested = true,
                unregistrationRequested = true,
            ),
        )

        val failed = NsdAdvertisementPlanner.unregistrationFailed(stopping)
        val retry = NsdAdvertisementPlanner.unregistrationRetry(failed.state)

        assertEquals(NsdAdvertisementCommand.Unregister, retry.command)
        assertTrue(retry.state.advertisingDesired)
        assertTrue(retry.state.registration?.unregistrationRequested == true)
        assertFalse(retry.state.registration?.unregistrationRetryScheduled == true)
    }

    @Test
    fun exhaustionBoundsAutomaticAttemptsAndRetainsRegistrationForLaterRetry() {
        var state = NsdAdvertisementState(
            registration = NsdRegistrationState(
                registered = true,
                stopRequested = true,
                unregistrationRequested = true,
            ),
        )

        NsdAdvertisementPlanner.unregistrationRetryDelaysMillis.forEachIndexed { index, delay ->
            val failed = NsdAdvertisementPlanner.unregistrationFailed(state)
            assertEquals(NsdAdvertisementCommand.ScheduleUnregisterRetry, failed.command)
            assertEquals(delay, failed.retryDelayMillis)
            assertEquals(index + 1, failed.state.registration?.unregistrationRetryAttempt)
            state = NsdAdvertisementPlanner.unregistrationRetry(failed.state).state
        }

        val exhausted = NsdAdvertisementPlanner.unregistrationFailed(state)
        assertEquals(NsdAdvertisementCommand.None, exhausted.command)
        assertNull(exhausted.retryDelayMillis)
        assertEquals(
            NsdAdvertisementPlanner.unregistrationRetryDelaysMillis.size + 1,
            exhausted.state.registration?.unregistrationRetryAttempt,
        )
        assertFalse(exhausted.state.registration?.unregistrationRetryScheduled == true)
        assertNotNull(exhausted.state.registration)

        val laterRetry = NsdAdvertisementPlanner.stop(exhausted.state)
        assertEquals(NsdAdvertisementCommand.Unregister, laterRetry.command)
        assertEquals(0, laterRetry.state.registration?.unregistrationRetryAttempt)
    }
}
