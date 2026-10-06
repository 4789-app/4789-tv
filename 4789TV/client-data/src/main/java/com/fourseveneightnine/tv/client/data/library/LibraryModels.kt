package com.fourseveneightnine.tv.client.data.library

/** What a collection is made of, and who is allowed to change its items. */
public enum class CollectionKind(public val wire: String) {
    /** Only holds what the viewer put in it. */
    MANUAL("manual"),

    /** Follows an add-on catalog. Items are replaced by the refresh, never reordered by hand. */
    CATALOG("catalog"),

    /** Follows one Letterboxd list. */
    LETTERBOXD("letterboxd"),

    /** Follows one public MDBList list. */
    MDBLIST("mdblist"),

    /** Built in. Cannot be deleted or reordered. */
    SYSTEM("system"),
    ;

    /** True when the items come from a source refresh rather than from the viewer. */
    public val isSourced: Boolean get() = this == CATALOG || this == LETTERBOXD || this == MDBLIST

    public companion object {
        public fun fromWire(value: String?): CollectionKind =
            entries.firstOrNull { it.wire == value } ?: MANUAL
    }
}

/** One row on the Home "Continue watching" shelf. */
public data class ContinueItem(
    public val canonicalId: String,
    public val mediaType: String,
    public val title: String,
    public val posterUrl: String?,
    public val backdropUrl: String?,
    public val season: Int?,
    public val episode: Int?,
    public val positionMs: Long,
    public val durationMs: Long,
    /** Newest of the progress write and the recent row, in epoch milliseconds. */
    public val lastActivityMillis: Long,
    public val origin: RowOrigin,
) {
    /** 0f when the duration is unknown. */
    public val progressFraction: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    public val remainingMs: Long get() = (durationMs - positionMs).coerceAtLeast(0L)
}

/** One row the phone pushed with `X4789.SetRecents`. */
public data class PhoneRecentRow(
    public val canonicalId: String,
    public val mediaType: String,
    public val title: String,
    public val posterUrl: String? = null,
    public val backdropUrl: String? = null,
    public val season: Int? = null,
    public val episode: Int? = null,
    public val positionMs: Long = 0L,
    public val durationMs: Long = 0L,
    public val lastPlayedAt: Long,
)

/** A folder card on the Collections grid and on Home. */
public data class CollectionSummary(
    public val id: Long,
    public val name: String,
    public val accent: String,
    public val kind: CollectionKind,
    public val count: Int,
    public val pinnedHome: Boolean,
    /** Up to three poster URLs, in item order, for the card's slivers. */
    public val previewPosters: List<String>,
)

/** One title inside a collection. */
public data class CollectionItem(
    public val canonicalId: String,
    public val mediaType: String,
    public val title: String,
    public val posterUrl: String?,
    public val sortIndex: Int,
    public val addedAtMillis: Long,
)

/** The Collection screen's whole payload. */
public data class CollectionDetail(
    public val id: Long,
    public val name: String,
    public val accent: String,
    public val kind: CollectionKind,
    public val sourceRef: String?,
    public val pinnedHome: Boolean,
    public val sortIndex: Int,
    public val updatedAtMillis: Long,
    public val items: List<CollectionItem>,
)

/** Where a refresh job stands. Drawn in the Result column of Settings → Jobs. */
public enum class JobState(public val wire: String) {
    IDLE("idle"),
    RUNNING("running"),
    OK("ok"),
    FAILED("failed"),
    ;

    public companion object {
        public fun fromWire(value: String?): JobState =
            entries.firstOrNull { it.wire == value } ?: IDLE
    }
}

/** One row of Settings → Jobs. */
public data class JobStatus(
    public val name: String,
    public val state: JobState,
    public val startedAtMillis: Long?,
    public val finishedAtMillis: Long?,
    public val message: String?,
)

/** The two built-in folders. Created on first open of the Collections screen. */
public object SystemCollections {
    public const val CONTINUE_WATCHING_REF: String = "system:continue-watching"
    public const val MY_CLOUD_REF: String = "system:my-cloud"
    public const val WATCHLIST_REF: String = "system:watchlist"
    public const val WATCHLIST_NAME: String = "Watchlist"
    public const val CONTINUE_WATCHING_NAME: String = "Continue Watching"
    public const val MY_CLOUD_NAME: String = "My Cloud"

    /** `textMuted` from the TV design spec. System folders never take a collection accent. */
    public const val SYSTEM_ACCENT: String = "#8A93A6"
}
