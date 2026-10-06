package com.fourseveneightnine.tv.client.ui.screens.settings

/**
 * The eight Settings pages, in list order.
 *
 * [slug] is the `page` argument of the `settings/{page}` route, so the rail, the cast chip and a
 * screen that wants to send a viewer somewhere specific can all name a page by string.
 */
internal enum class SettingsPage(val slug: String, val title: String, val blurb: String) {
    Pair("pair", "Pair & Sync", "Send add-ons and keys from your phone."),
    Profiles("profiles", "Profiles", "People, PINs and separate libraries."),
    Addons("addons", "Add-ons", "Catalogs and source order."),
    Accounts("accounts", "Accounts", "Keys and connected services."),
    Playback("playback", "Playback", "Quality, sound, player and picture."),
    Look("look", "Look", "Canvas, layout and motion."),
    Jobs("jobs", "Jobs", "Catalog refresh status."),
    About("about", "About", "Version, licences and a copy of the log."),
    ;

    companion object {
        val DEFAULT = Pair

        fun fromSlug(slug: String?): SettingsPage =
            entries.firstOrNull { it.slug.equals(slug?.trim(), ignoreCase = true) } ?: DEFAULT
    }
}

/** The 12 px dot beside a left row when that page needs attention (spec §14.2). */
internal enum class PageDot { None, Warning, Error }

/**
 * Which pages are asking for attention. Pure, so the rule is testable without a receiver.
 *
 * A dot in the left list tells you which page needs a look before you open it (spec §14.13.2).
 */
internal object SettingsPageList {

    fun dots(
        paired: Boolean,
        addonsFailing: Int,
        addonsSlow: Int,
        jobsFailed: Int,
        keysMissing: Boolean,
    ): Map<SettingsPage, PageDot> {
        val dots = mutableMapOf<SettingsPage, PageDot>()
        if (!paired) dots[SettingsPage.Pair] = PageDot.Warning
        when {
            addonsFailing > 0 -> dots[SettingsPage.Addons] = PageDot.Error
            addonsSlow > 0 -> dots[SettingsPage.Addons] = PageDot.Warning
        }
        if (keysMissing) dots[SettingsPage.Accounts] = PageDot.Warning
        if (jobsFailed > 0) dots[SettingsPage.Jobs] = PageDot.Error
        return dots
    }
}

/**
 * The "Next run" column of Settings → Jobs, spec §14.9. Pure, so the arithmetic is testable.
 *
 * The refresh is a 6-hourly periodic job, so the next run is the last run plus that period. A
 * source that has never run, or whose next run is already due, reads "soon" rather than a
 * negative number.
 */
internal object JobSchedule {

    /** `RefreshScheduler.PERIOD_HOURS`. Restated here so the pure rule has no Android import. */
    const val PERIOD_MILLIS: Long = 6L * 60L * 60L * 1000L

    const val UNKNOWN: String = "\u2014"

    fun nextRunLabel(
        lastRunMillis: Long?,
        nowMillis: Long,
        periodMillis: Long = PERIOD_MILLIS,
    ): String {
        if (lastRunMillis == null || lastRunMillis <= 0L || periodMillis <= 0L) return UNKNOWN
        val remaining = lastRunMillis + periodMillis - nowMillis
        if (remaining <= 0L) return "soon"
        val minutes = remaining / 60_000L
        if (minutes < 60L) return "in ${minutes.coerceAtLeast(1L)}m"
        return "in ${minutes / 60L}h"
    }
}
