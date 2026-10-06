package com.fourseveneightnine.tv.client.ui.screens.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** One episode on one day. Built from `Meta.videos[].released` — no worker call (spec §13.8.7). */
internal data class CalendarEpisode(
    val canonicalId: String,
    val mediaType: String,
    val showTitle: String,
    val posterURL: String?,
    val season: Int,
    val episode: Int,
    val episodeTitle: String?,
    val date: LocalDate,
) {
    /** "S2 E5 · Pot au Feu", or "S2 E5" when the episode has no title (spec §13.7). */
    val episodeLine: String
        get() = buildString {
            append("S").append(season).append(" E").append(episode)
            if (!episodeTitle.isNullOrBlank()) append(" · ").append(episodeTitle)
        }

    val key: String get() = "$canonicalId:$season:$episode"
}

/** One square of the month grid. */
internal data class CalendarCell(
    val date: LocalDate,
    val inMonth: Boolean,
    val episodes: List<CalendarEpisode>,
) {
    val dayOfMonth: Int get() = date.dayOfMonth

    /** A day outside the month is drawn muted and takes no focus (spec §13.2). */
    val focusable: Boolean get() = inMonth
}

/**
 * The month grid, spec §13.2. Pure: no Compose, no Android, no clock of its own.
 *
 * The week starts on Monday, matching the phone (spec §13.8.1). The grid is always six rows of
 * seven, so the screen never reflows between a 28-day and a 31-day month and the focused column
 * survives a month change.
 */
internal object CalendarMonth {

    const val COLUMNS = 7
    const val ROWS = 6
    const val CELLS = COLUMNS * ROWS

    /** Up to three poster thumbs fit in a 216 × 132 cell; the rest are counted (spec §13.2). */
    const val MAX_THUMBS = 3

    /**
     * The screen's vertical measure, spec §13.2, in px down the 1080 canvas.
     *
     * Every band is a fixed height, so the grid never moves when the status line changes what it
     * says. The numbers live here rather than in the composable so the column can be added up in a
     * test: the grid used to run to y 1098 on a 1080 canvas and rows 5 and 6 fell off the screen.
     */
    object Layout {
        const val SAFE_TOP = 54
        const val SAFE_BOTTOM = 1026

        /** The title, the month label and the Prev/Next buttons: y 54..120. */
        const val TITLE_BAND_HEIGHT = 66

        /** The rest the spec leaves under the title: y 120..132. */
        const val TITLE_TO_HEADER_GAP = 12

        const val WEEKDAY_HEADER_HEIGHT = 38
        const val HEADER_TO_GRID_GAP = 10

        const val CELL_WIDTH = 216
        const val CELL_HEIGHT = 132
        const val COLUMN_PITCH = 228
        const val ROW_PITCH = 140

        /** The loading line sits between the title text and the weekday header. */
        const val STATUS_LINE_TOP = 104

        /** The empty and error blocks carry a 60 px button, so they are drawn over the grid. */
        const val STATUS_BLOCK_TOP = 300

        const val WEEKDAY_HEADER_TOP = SAFE_TOP + TITLE_BAND_HEIGHT + TITLE_TO_HEADER_GAP
        const val GRID_TOP = WEEKDAY_HEADER_TOP + WEEKDAY_HEADER_HEIGHT + HEADER_TO_GRID_GAP

        /** The bottom edge of calendar row 6: the last row's top plus one cell, not one pitch. */
        const val GRID_BOTTOM = GRID_TOP + (ROWS - 1) * ROW_PITCH + CELL_HEIGHT

        /** True when every row of the grid ends above the safe bottom. */
        fun fitsSafeArea(): Boolean = GRID_BOTTOM <= SAFE_BOTTOM

        /** True when the status block starts below the weekday header, so it cannot paint over it. */
        fun statusClearsHeader(): Boolean =
            STATUS_LINE_TOP + 28 <= WEEKDAY_HEADER_TOP &&
                STATUS_BLOCK_TOP >= WEEKDAY_HEADER_TOP + WEEKDAY_HEADER_HEIGHT
    }

    val WEEKDAYS: List<String> = (0 until COLUMNS).map { index ->
        java.time.DayOfWeek.MONDAY.plus(index.toLong())
            .getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
    }

    /**
     * The air date an add-on wrote, as a local day.
     *
     * Add-ons write `released` three ways: a full instant (`2026-03-19T01:00:00.000Z`), a date
     * (`2026-03-19`), or nothing. An episode with no usable date is dropped with no message,
     * because a date is the only reason it would be on this screen (spec §13.7).
     */
    fun parseReleased(raw: String?, zone: ZoneId = ZoneId.systemDefault()): LocalDate? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        runCatching { return Instant.parse(text).atZone(zone).toLocalDate() }
        runCatching { return LocalDate.parse(text.take(10)) }
        return null
    }

    /**
     * Every cell of [month], Monday first, with each episode filed under its air date and sorted
     * by season and episode so the day panel reads in air order (spec §13.8.6).
     */
    fun grid(month: YearMonth, episodes: List<CalendarEpisode>): List<CalendarCell> {
        val byDate = episodes.groupBy { it.date }
        val first = month.atDay(1)
        // DayOfWeek.value is Monday = 1, so this is how many trailing days of the month before.
        val lead = first.dayOfWeek.value - java.time.DayOfWeek.MONDAY.value
        val start = first.minusDays(lead.toLong())
        return (0 until CELLS).map { index ->
            val date = start.plusDays(index.toLong())
            CalendarCell(
                date = date,
                inMonth = YearMonth.from(date) == month,
                episodes = byDate[date].orEmpty().sortedWith(
                    compareBy({ it.showTitle }, { it.season }, { it.episode }),
                ),
            )
        }
    }

    /** "+2" when a day holds more than the three thumbs it can draw, otherwise null. */
    fun overflowLabel(count: Int): String? =
        if (count > MAX_THUMBS) "+${count - MAX_THUMBS}" else null

    /** "March 2026". */
    fun monthLabel(month: YearMonth): String =
        "${month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${month.year}"

    /** "Thu 19 March", the day panel's header (spec §13.3). */
    fun dayLabel(date: LocalDate): String = buildString {
        append(date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH))
        append(" ").append(date.dayOfMonth).append(" ")
        append(date.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH))
    }

    /**
     * Where focus lands on entry, spec §13.4: today, else the first cell that has items, else the
     * first day of the month. Returns an index into [cells].
     */
    fun initialFocusIndex(cells: List<CalendarCell>, today: LocalDate): Int {
        cells.indexOfFirst { it.inMonth && it.date == today }.takeIf { it >= 0 }?.let { return it }
        cells.indexOfFirst { it.inMonth && it.episodes.isNotEmpty() }.takeIf { it >= 0 }?.let { return it }
        return cells.indexOfFirst { it.inMonth }.coerceAtLeast(0)
    }

    /**
     * Changing month keeps the focused column, and the nearest row that exists (spec §13.4).
     * "Exists" means a cell inside the new month, so January's leading blanks never take focus.
     */
    fun keepColumn(cells: List<CalendarCell>, previousIndex: Int): Int {
        val column = previousIndex % COLUMNS
        val row = previousIndex / COLUMNS
        val candidates = (0 until ROWS)
            .sortedBy { kotlin.math.abs(it - row) }
            .map { it * COLUMNS + column }
        return candidates.firstOrNull { cells.getOrNull(it)?.inMonth == true }
            ?: cells.indexOfFirst { it.inMonth }.coerceAtLeast(0)
    }
}
