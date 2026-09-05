package com.fourseveneightnine.phone

import com.fourseveneightnine.contract.DiscoverItem
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogSearchPolicyTest {
    private val items = listOf(
        DiscoverItem(
            id = "one",
            type = "movie",
            title = "Amelie",
            description = "A whimsical Paris story",
            year = 2001,
            genres = listOf("Romance", "Comedy"),
        ),
        DiscoverItem(
            id = "two",
            type = "series",
            title = "Dark",
            description = "A time travel mystery",
            year = 2017,
            genres = listOf("Drama", "Sci-Fi"),
        ),
    )

    @Test
    fun blankQueryPreservesCatalogOrder() {
        assertEquals(items, CatalogSearchPolicy.filter(items, "   "))
    }

    @Test
    fun allNormalizedTokensMustMatchAcrossMetadata() {
        assertEquals(listOf("one"), CatalogSearchPolicy.filter(items, "AMELIE  romance").map { it.id })
        assertEquals(listOf("two"), CatalogSearchPolicy.filter(items, "2017 time").map { it.id })
        assertEquals(emptyList<DiscoverItem>(), CatalogSearchPolicy.filter(items, "Dark comedy"))
    }

    @Test
    fun diacriticsDoNotPreventAUserMatch() {
        val accented = items[0].copy(title = "Amélie")
        assertEquals(listOf(accented), CatalogSearchPolicy.filter(listOf(accented), "amelie"))
    }

    @Test
    fun mediaFilterComposesWithTextTokens() {
        assertEquals(listOf("one"), CatalogSearchPolicy.filter(items, "", CatalogMediaFilter.Movies).map { it.id })
        assertEquals(listOf("two"), CatalogSearchPolicy.filter(items, "time", CatalogMediaFilter.Series).map { it.id })
        assertEquals(emptyList<DiscoverItem>(), CatalogSearchPolicy.filter(items, "Dark", CatalogMediaFilter.Movies))
    }

    @Test
    fun excessivelyLongInputIsBounded() {
        val longQuery = "Amelie" + " ".repeat(200)
        assertEquals(120, CatalogSearchPolicy.boundedQuery(longQuery).length)
        assertEquals(listOf(items[0]), CatalogSearchPolicy.filter(items, longQuery))
    }
}
