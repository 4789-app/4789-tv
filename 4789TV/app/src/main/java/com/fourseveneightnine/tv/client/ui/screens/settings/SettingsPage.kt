package com.fourseveneightnine.tv.client.ui.screens.settings

import androidx.compose.ui.graphics.Color
import com.fourseveneightnine.tv.R

/**
 * Settings categories in sidebar order.
 *
 * [slug] is the `page` argument of the `settings/{page}` route, so the rail, the cast chip and a
 * screen that wants to send a viewer somewhere specific can all name a page by string.
 */
internal enum class SettingsPage(
    val slug: String,
    val title: String,
    val blurb: String,
    val icon: Int,
    val tint: Color,
) {
    Pair("pair", "Setup", "Send your 4789 setup from an iPhone or set up profiles for this TV.",
        R.drawable.ic_rail_cast, Color(0xFF93D5FF)),
    Accounts("accounts", "Accounts & keys", "Connect services for playback, artwork and ratings.",
        R.drawable.ic_settings_key, Color(0xFFB7A9FF)),
    Addons("addons", "Add-ons", "Bring in streaming sources and choose which ones appear on this TV.",
        R.drawable.ic_detail_layers, Color(0xFFFFBC81)),
    LiveTv("live-tv", "Live TV", "Connect an IPTV provider or playlist to watch live channels.",
        R.drawable.ic_rail_live_tv, Color(0xFFFFA9C1)),
    Playback("playback", "Playback", "Choose your audio language, picture options and remote controls.",
        R.drawable.ic_detail_play, Color(0xFF91E0CF)),
    Look("look", "Appearance", "Change the background and motion on this TV.",
        R.drawable.ic_player_appearance, Color(0xFFD3B2FC)),
    Jobs("jobs", "Discover & catalogs", "Choose a layout, add Letterboxd lists and refresh your catalogs.",
        R.drawable.ic_rail_discover, Color(0xFFBEE798)),
    About("about", "About", "Find the app version, device details, licences and diagnostics.",
        R.drawable.ic_settings_info, Color(0xFFB8C5D7)),
    ;

    companion object {
        val DEFAULT = Pair

        fun fromSlug(slug: String?): SettingsPage =
            when (slug?.trim()?.lowercase()) {
                "profiles" -> Pair
                "letterboxd" -> Jobs
                else -> entries.firstOrNull { it.slug.equals(slug?.trim(), ignoreCase = true) } ?: DEFAULT
            }
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
        addonsFailing: Int,
        addonsSlow: Int,
        jobsFailed: Int,
    ): Map<SettingsPage, PageDot> {
        val dots = mutableMapOf<SettingsPage, PageDot>()
        when {
            addonsFailing > 0 -> dots[SettingsPage.Addons] = PageDot.Error
            addonsSlow > 0 -> dots[SettingsPage.Addons] = PageDot.Warning
        }
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
