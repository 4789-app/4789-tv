package com.fourseveneightnine.tv.client.data.refresh

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The stale-label table from the TV design spec §15.6. It never says "stale". */
class FreshnessTest {
    private val zone: ZoneId = ZoneId.of("UTC")
    private val locale: Locale = Locale.UK
    private val now = ZonedDateTime.of(2026, 3, 19, 12, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun label(ageMillis: Long): String? =
        Freshness.label(now - ageMillis, now, zone, locale)

    @Test
    fun `says nothing under thirty minutes`() {
        assertNull(label(0))
        assertNull(label(minutes(1)))
        assertNull(label(minutes(29)))
    }

    @Test
    fun `minutes between thirty and fifty nine`() {
        assertEquals("updated 30m ago", label(minutes(30)))
        assertEquals("updated 42m ago", label(minutes(42)))
        assertEquals("updated 59m ago", label(minutes(59)))
    }

    @Test
    fun `hours up to a day`() {
        assertEquals("updated 1h ago", label(hours(1)))
        assertEquals("updated 2h ago", label(hours(2)))
        assertEquals("updated 23h ago", label(hours(23)))
    }

    @Test
    fun `yesterday covers twenty four to forty seven hours`() {
        assertEquals("updated yesterday", label(hours(24)))
        assertEquals("updated yesterday", label(hours(47)))
    }

    @Test
    fun `days between two and seven`() {
        assertEquals("updated 2 days ago", label(days(2)))
        assertEquals("updated 3 days ago", label(days(3)))
        assertEquals("updated 7 days ago", label(days(7)))
    }

    @Test
    fun `over a week names the date`() {
        assertEquals("updated 12 March", label(days(7) + hours(1)))
    }

    @Test
    fun `a missing or future timestamp says nothing`() {
        assertNull(Freshness.label(0L, now, zone, locale))
        assertNull(Freshness.label(now + hours(5), now, zone, locale))
    }

    @Test
    fun `the word stale never appears`() {
        val samples = listOf(minutes(30), hours(2), hours(30), days(3), days(40))
        for (age in samples) {
            val text = label(age).orEmpty()
            assert(!text.contains("stale", ignoreCase = true)) { "said stale for $age" }
        }
    }

    private fun minutes(value: Long) = value * 60_000L
    private fun hours(value: Long) = value * 3_600_000L
    private fun days(value: Long) = value * 86_400_000L
}
