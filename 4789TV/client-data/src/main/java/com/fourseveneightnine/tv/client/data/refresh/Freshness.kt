package com.fourseveneightnine.tv.client.data.refresh

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The muted label that sits 16 px after a shelf header: `updated 2h ago`.
 *
 * Rules from the TV design spec §15.6. It never says "stale", it never uses `warning`, and it is
 * never right-aligned. It is a fact, not a fault.
 *
 * Note for callers: the spec also says the label is hidden while that source is refreshing and is
 * removed the moment fresh data lands. That is the shelf's business, not this function's — this
 * only turns an age into words.
 */
public object Freshness {
    /** Nothing is said about data younger than this. */
    public const val MINIMUM_AGE_MILLIS: Long = 30L * 60 * 1_000

    private const val HOUR_MILLIS = 60L * 60 * 1_000
    private const val DAY_MILLIS = 24L * HOUR_MILLIS

    /**
     * @return null when the row is fresh enough to say nothing about, otherwise the exact label.
     */
    public fun label(
        generatedAtMillis: Long,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String? {
        if (generatedAtMillis <= 0L) return null
        val age = now - generatedAtMillis
        // A clock that has run backwards is not a reason to call fresh data old.
        if (age < MINIMUM_AGE_MILLIS) return null
        return when {
            age < HOUR_MILLIS -> "updated ${age / (60L * 1_000)}m ago"
            age < DAY_MILLIS -> "updated ${age / HOUR_MILLIS}h ago"
            age < 2 * DAY_MILLIS -> "updated yesterday"
            age <= 7 * DAY_MILLIS -> "updated ${age / DAY_MILLIS} days ago"
            else -> "updated " + DateTimeFormatter.ofPattern("d MMMM", locale)
                .withZone(zone)
                .format(Instant.ofEpochMilli(generatedAtMillis))
        }
    }
}
