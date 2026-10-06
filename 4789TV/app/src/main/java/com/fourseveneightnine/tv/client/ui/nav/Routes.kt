package com.fourseveneightnine.tv.client.ui.nav

/**
 * Every place the TV client can be, spec §1 and §18.6.
 *
 * Six of them are top level and carry the navigation rail: Search, Home, Discover, Collections,
 * Calendar and Settings. Everything else is a drill-in — Detail, Streams, the Player, a Collection
 * and the editor — and leaves by BACK, with no rail strip drawn at all.
 *
 * The iOS two-door invariant is a phone rule. It does not apply here (plan §13).
 */
internal sealed interface Route {
    val path: String

    data object Home : Route { override val path = "home" }
    data object LiveTv : Route { override val path = "live-tv" }
    data object Discover : Route { override val path = "discover" }
    data object Collections : Route { override val path = "collections" }
    data object Search : Route { override val path = "search" }
    data object Calendar : Route { override val path = "calendar" }

    data class Settings(val page: String = DEFAULT_PAGE) : Route {
        override val path = "settings/$page"

        companion object {
            const val DEFAULT_PAGE = "pair"
            const val PATTERN = "settings/{page}"
        }
    }

    data class CollectionDetail(val id: String) : Route {
        override val path = "collection/$id"

        companion object { const val PATTERN = "collection/{id}" }
    }

    data class CollectionEditor(val id: String?) : Route {
        override val path = "collection-editor/${id ?: NEW}"

        companion object {
            const val NEW = "new"
            const val PATTERN = "collection-editor/{id}"
        }
    }

    data class Detail(val type: String, val id: String) : Route {
        override val path = "detail/$type/$id"

        companion object { const val PATTERN = "detail/{type}/{id}" }
    }

    data class Streams(
        val type: String,
        val id: String,
        val season: Int? = null,
        val episode: Int? = null,
    ) : Route {
        override val path = "streams/$type/$id?season=${season ?: -1}&episode=${episode ?: -1}"

        companion object { const val PATTERN = "streams/{type}/{id}?season={season}&episode={episode}" }
    }

    data object Player : Route { override val path = "player" }

    data class LiveMultiview(val channelId: String) : Route {
        override val path = "live-multiview/${android.net.Uri.encode(channelId)}"
        companion object { const val PATTERN = "live-multiview/{id}" }
    }

    /** First run. An unconfigured receiver shows this and nothing else. */
    data object PairSync : Route { override val path = "pair-sync" }
}

/** The six rail destinations, in rail order (spec §1.2). */
internal enum class TopLevel(val route: Route, val label: String) {
    Search(Route.Search, "Search"),
    Home(Route.Home, "Home"),
    LiveTv(Route.LiveTv, "Live TV"),
    Discover(Route.Discover, "Discover"),
    Collections(Route.Collections, "Collections"),
    Calendar(Route.Calendar, "Calendar"),
    Settings(Route.Settings(), "Settings"),
    ;

    companion object {
        /** Which rail item owns this NavHost route, or null on a drill-in. */
        fun forPath(path: String?): TopLevel? = when {
            path == null -> null
            path == Route.Home.path -> Home
            path == Route.LiveTv.path -> LiveTv
            path == Route.Discover.path -> Discover
            path == Route.Collections.path -> Collections
            path == Route.Search.path -> Search
            path == Route.Calendar.path -> Calendar
            path.startsWith("settings/") -> Settings
            else -> null
        }

        /** The rail is drawn on top-level screens only; drill-ins use BACK. */
        fun showsRail(path: String?): Boolean = forPath(path) != null
    }
}
