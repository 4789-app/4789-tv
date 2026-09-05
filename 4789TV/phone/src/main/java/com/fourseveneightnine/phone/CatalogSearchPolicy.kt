package com.fourseveneightnine.phone

import com.fourseveneightnine.contract.DiscoverItem
import java.text.Normalizer
import java.util.Locale

internal enum class CatalogMediaFilter {
    All,
    Movies,
    Series,
}

internal object CatalogSearchPolicy {
    private const val MAXIMUM_QUERY_CHARACTERS = 120

    fun boundedQuery(rawQuery: String): String = rawQuery.take(MAXIMUM_QUERY_CHARACTERS)

    fun filter(
        items: List<DiscoverItem>,
        rawQuery: String,
        mediaFilter: CatalogMediaFilter = CatalogMediaFilter.All,
    ): List<DiscoverItem> {
        val tokens = normalize(boundedQuery(rawQuery))
            .split(Regex("\\s+"))
            .filter(String::isNotEmpty)

        return items.filter { item ->
            val matchesMedia = when (mediaFilter) {
                CatalogMediaFilter.All -> true
                CatalogMediaFilter.Movies -> normalize(item.type) == "movie"
                CatalogMediaFilter.Series -> normalize(item.type) in setOf("series", "show", "tv")
            }
            if (!matchesMedia) return@filter false
            if (tokens.isEmpty()) return@filter true

            val searchable = normalize(
                buildString {
                    append(item.title)
                    append(' ')
                    append(item.type)
                    append(' ')
                    append(item.year ?: "")
                    append(' ')
                    append(item.genres.joinToString(" "))
                    append(' ')
                    append(item.description.orEmpty())
                },
            )
            tokens.all(searchable::contains)
        }
    }

    private fun normalize(value: String): String = Normalizer
        .normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase(Locale.ROOT)
        .trim()
}
