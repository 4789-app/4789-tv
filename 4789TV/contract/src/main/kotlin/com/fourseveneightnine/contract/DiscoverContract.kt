package com.fourseveneightnine.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CatalogEnvelope(
    val success: Boolean = true,
    val catalogs: List<CatalogDefinition> = emptyList(),
)

@Serializable
data class CatalogDefinition(
    val uid: String,
    val id: String,
    val type: String,
    val name: String,
    val source: String,
    @SerialName("media_type") val mediaType: String? = null,
    @SerialName("page_size") val pageSize: Int? = null,
    @SerialName("show_in_home") val showInHome: Boolean? = null,
    @SerialName("requires_genre") val requiresGenre: Boolean? = null,
    @SerialName("supports_genre") val supportsGenre: Boolean? = null,
    @SerialName("supports_search") val supportsSearch: Boolean? = null,
    @SerialName("supports_year") val supportsYear: Boolean? = null,
    @SerialName("supports_language") val supportsLanguage: Boolean? = null,
    @SerialName("supports_country") val supportsCountry: Boolean? = null,
    @SerialName("default_genre") val defaultGenre: String? = null,
    @SerialName("genre_options") val genreOptions: List<String> = emptyList(),
)

@Serializable
data class DiscoverEnvelope(
    val success: Boolean = true,
    val items: List<DiscoverItem> = emptyList(),
    val count: Int? = null,
    val page: Int? = null,
    val pages: Int? = null,
    val limit: Int? = null,
    @SerialName("fetched_pages") val fetchedPages: Int? = null,
    @SerialName("has_more") val hasMore: Boolean? = null,
    val exhausted: Boolean? = null,
)

@Serializable
data class DiscoverItem(
    val id: String,
    val type: String,
    val title: String,
    @SerialName("poster_url") val posterURL: String? = null,
    @SerialName("backdrop_url") val backdropURL: String? = null,
    val description: String? = null,
    val runtime: String? = null,
    val year: Int? = null,
    val rating: Double? = null,
    val genres: List<String> = emptyList(),
    @SerialName("catalog_id") val catalogID: String? = null,
)

@Serializable
data class StreamEnvelope(
    val streams: List<StreamEntry> = emptyList(),
    val success: Boolean = true,
    val error: String? = null,
)

@Serializable
data class StreamEntry(
    val url: String? = null,
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val quality: String? = null,
    val filename: String? = null,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
)

/** Raw stream URLs are play-now capabilities. They are never durable Android state. */
fun StreamEntry.durableURL(): Nothing? = null

object CatalogPresentationPolicy {
    fun discover(items: List<DiscoverItem>, limit: Int = 20): List<DiscoverItem> =
        items
            .asSequence()
            .distinctBy { it.id }
            .take(limit.coerceAtLeast(0))
            .toList()

    fun wall(items: List<DiscoverItem>, limit: Int = 60): List<DiscoverItem> =
        items
            .asSequence()
            .filter { !it.posterURL.isNullOrBlank() }
            .distinctBy { it.id }
            .toList()
            .let { unique ->
                if (unique.any { it.rating != null }) {
                    unique.sortedWith(
                        compareByDescending<DiscoverItem> { it.rating ?: Double.NEGATIVE_INFINITY }
                            .thenByDescending { it.year ?: Int.MIN_VALUE }
                            .thenBy { it.id },
                    )
                } else {
                    unique
                }
            }
            .take(limit.coerceAtLeast(0))
}
