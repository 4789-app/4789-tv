package com.fourseveneightnine.tv.client.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** F77, spec §14.9: the "Next run" column reads "in 4h". */
class JobScheduleTest {

    private val hour = 60L * 60L * 1000L

    @Test
    fun `a job that has never run has no next run`() {
        assertEquals(JobSchedule.UNKNOWN, JobSchedule.nextRunLabel(null, nowMillis = 10 * hour))
        assertEquals(JobSchedule.UNKNOWN, JobSchedule.nextRunLabel(0L, nowMillis = 10 * hour))
    }

    @Test
    fun `two hours after a run the next one is four hours away`() {
        assertEquals("in 4h", JobSchedule.nextRunLabel(8 * hour, nowMillis = 10 * hour))
    }

    @Test
    fun `under an hour reads in minutes`() {
        assertEquals("in 30m", JobSchedule.nextRunLabel(8 * hour, nowMillis = 13 * hour + 30 * 60 * 1000L))
    }

    @Test
    fun `an overdue run reads soon`() {
        assertEquals("soon", JobSchedule.nextRunLabel(hour, nowMillis = 20 * hour))
    }

    @Test
    fun `the period is the six hours the scheduler uses`() {
        assertEquals(6 * hour, JobSchedule.PERIOD_MILLIS)
    }
}
