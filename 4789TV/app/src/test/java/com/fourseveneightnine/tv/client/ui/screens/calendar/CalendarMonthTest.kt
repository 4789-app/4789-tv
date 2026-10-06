package com.fourseveneightnine.tv.client.ui.screens.calendar

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarMonthTest {

    private fun episode(
        show: String,
        date: LocalDate,
        season: Int = 1,
        episode: Int = 1,
        title: String? = null,
    ) = CalendarEpisode(
        canonicalId = "tt$show",
        mediaType = "series",
        showTitle = show,
        posterURL = null,
        season = season,
        episode = episode,
        episodeTitle = title,
        date = date,
    )

    @Test
    fun `the grid is always six rows of seven`() {
        listOf(YearMonth.of(2026, 2), YearMonth.of(2026, 3), YearMonth.of(2026, 8)).forEach { month ->
            assertEquals(42, CalendarMonth.grid(month, emptyList()).size)
        }
    }

    @Test
    fun `the week starts on Monday`() {
        assertEquals("Mon", CalendarMonth.WEEKDAYS.first())
        assertEquals("Sun", CalendarMonth.WEEKDAYS.last())
        // 1 March 2026 is a Sunday, so the first row carries six days of February.
        val cells = CalendarMonth.grid(YearMonth.of(2026, 3), emptyList())
        assertEquals(LocalDate.of(2026, 2, 23), cells.first().date)
        assertEquals(6, cells.take(7).count { !it.inMonth })
        assertEquals(LocalDate.of(2026, 3, 1), cells[6].date)
        assertTrue(cells[6].inMonth)
    }

    @Test
    fun `days outside the month are not focusable`() {
        val cells = CalendarMonth.grid(YearMonth.of(2026, 3), emptyList())
        assertFalse(cells.first().focusable)
        assertTrue(cells[6].focusable)
    }

    @Test
    fun `episodes land on their air date`() {
        val day = LocalDate.of(2026, 3, 19)
        val cells = CalendarMonth.grid(YearMonth.of(2026, 3), listOf(episode("The Bear", day)))
        val cell = cells.single { it.date == day }
        assertEquals(1, cell.episodes.size)
        assertEquals(0, cells.filter { it.date != day }.sumOf { it.episodes.size })
    }

    @Test
    fun `a day panel lists episodes in air order`() {
        val day = LocalDate.of(2026, 3, 19)
        val cells = CalendarMonth.grid(
            YearMonth.of(2026, 3),
            listOf(
                episode("The Bear", day, season = 2, episode = 7),
                episode("The Bear", day, season = 2, episode = 5),
                episode("Andor", day, season = 1, episode = 2),
            ),
        )
        val cell = cells.single { it.date == day }
        assertEquals(listOf("Andor", "The Bear", "The Bear"), cell.episodes.map { it.showTitle })
        assertEquals(listOf(2, 5, 7), cell.episodes.map { it.episode })
    }

    @Test
    fun `more than three items shows a plus count`() {
        assertNull(CalendarMonth.overflowLabel(0))
        assertNull(CalendarMonth.overflowLabel(3))
        assertEquals("+1", CalendarMonth.overflowLabel(4))
        assertEquals("+2", CalendarMonth.overflowLabel(5))
    }

    @Test
    fun `the episode line falls back to the number when there is no title`() {
        assertEquals("S2 E5", episode("x", LocalDate.of(2026, 3, 1), 2, 5).episodeLine)
        assertEquals(
            "S2 E5 · Pot au Feu",
            episode("x", LocalDate.of(2026, 3, 1), 2, 5, "Pot au Feu").episodeLine,
        )
    }

    @Test
    fun `initial focus is today, then the first day with items, then day one`() {
        val month = YearMonth.of(2026, 3)
        val empty = CalendarMonth.grid(month, emptyList())
        val today = LocalDate.of(2026, 3, 19)

        assertEquals(today, empty[CalendarMonth.initialFocusIndex(empty, today)].date)

        val other = LocalDate.of(2025, 1, 1)
        assertEquals(LocalDate.of(2026, 3, 1), empty[CalendarMonth.initialFocusIndex(empty, other)].date)

        val filled = CalendarMonth.grid(month, listOf(episode("The Bear", LocalDate.of(2026, 3, 12))))
        assertEquals(
            LocalDate.of(2026, 3, 12),
            filled[CalendarMonth.initialFocusIndex(filled, other)].date,
        )
    }

    @Test
    fun `changing month keeps the column and the nearest row that exists`() {
        val march = CalendarMonth.grid(YearMonth.of(2026, 3), emptyList())
        // Row 5, column 3 in March.
        val index = 5 * 7 + 3
        val april = CalendarMonth.grid(YearMonth.of(2026, 4), emptyList())
        val kept = CalendarMonth.keepColumn(april, index)
        assertEquals(3, kept % 7)
        assertTrue(april[kept].inMonth)
        assertTrue(march[index].date.dayOfWeek == april[kept].date.dayOfWeek)
    }

    @Test
    fun `the month and day labels read as the spec writes them`() {
        assertEquals("March 2026", CalendarMonth.monthLabel(YearMonth.of(2026, 3)))
        assertEquals("Thu 19 March", CalendarMonth.dayLabel(LocalDate.of(2026, 3, 19)))
    }

    // ------------------------------------------------------------------ layout, spec §13.2

    @Test
    fun `the weekday header and the grid sit where the spec puts them`() {
        assertEquals(132, CalendarMonth.Layout.WEEKDAY_HEADER_TOP)
        assertEquals(180, CalendarMonth.Layout.GRID_TOP)
    }

    @Test
    fun `calendar row 6 ends above the safe bottom`() {
        assertEquals(1012, CalendarMonth.Layout.GRID_BOTTOM)
        assertTrue(CalendarMonth.Layout.fitsSafeArea())
    }

    @Test
    fun `the status block never paints over the weekday header`() {
        assertTrue(CalendarMonth.Layout.statusClearsHeader())
    }

    @Test
    fun `six rows of cells fit the 832 px the spec gives the grid`() {
        val height = CalendarMonth.Layout.GRID_BOTTOM - CalendarMonth.Layout.GRID_TOP
        assertEquals(832, height)
    }
}
