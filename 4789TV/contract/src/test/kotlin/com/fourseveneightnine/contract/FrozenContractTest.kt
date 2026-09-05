package com.fourseveneightnine.contract

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrozenContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun frozenCatalogFixtureDecodesWithoutInventingDoors() {
        val fixture = resource("/discover_catalogs.json")
        val decoded = json.decodeFromString<CatalogEnvelope>(fixture)

        assertEquals(111, decoded.catalogs.size)
        assertEquals(setOf(PhoneDoor.Discover, PhoneDoor.Wall), PhoneNavigationPolicy.topLevelDoors)
    }

    @Test
    fun frozenMovieCatalogRetainsPaginationAndIdentity() {
        val fixture = resource("/discover_catalog_movie.json")
        val decoded = json.decodeFromString<DiscoverEnvelope>(fixture)

        assertTrue(decoded.success)
        assertTrue(decoded.items.isNotEmpty())
        assertEquals(decoded.items.size, decoded.count)
        assertTrue(decoded.items.all { it.id.isNotBlank() && it.title.isNotBlank() })
        assertEquals(decoded.exhausted?.not(), decoded.hasMore)
    }

    @Test
    fun streamURLsRemainEphemeral() {
        val fixture = resource("/movies_direct_stream.json")
        val decoded = json.decodeFromString<StreamEnvelope>(fixture)

        assertTrue(decoded.success)
        assertFalse(decoded.streams.isEmpty())
        assertNull(decoded.streams.first().durableURL())
    }

    @Test
    fun doorSwitchDropsDrillInStack() {
        val stack = PhoneNavigationPolicy.backStackAfterDoorSwitch(PhoneDoor.Wall)
        assertEquals(listOf(PhoneRoute.Door(PhoneDoor.Wall)), stack)
    }

    @Test
    fun catalogPresentationIsBoundedStableAndDeduplicated() {
        val fixture = resource("/discover_catalog_movie.json")
        val decoded = json.decodeFromString<DiscoverEnvelope>(fixture)
        val duplicate = decoded.items.first()
        val input = decoded.items + duplicate.copy(title = "duplicate")

        val discover = CatalogPresentationPolicy.discover(input, limit = 10)
        val wall = CatalogPresentationPolicy.wall(input, limit = 12)

        assertEquals(10, discover.size)
        assertEquals(discover.map { it.id }.distinct(), discover.map { it.id })
        assertTrue(wall.size <= 12)
        assertEquals(wall.map { it.id }.distinct(), wall.map { it.id })
        assertTrue(wall.all { !it.posterURL.isNullOrBlank() })
        assertEquals(
            wall.sortedWith(
                compareByDescending<DiscoverItem> { it.rating ?: Double.NEGATIVE_INFINITY }
                    .thenByDescending { it.year ?: Int.MIN_VALUE }
                    .thenBy { it.id },
            ),
            wall,
        )
    }

    private fun resource(path: String): String =
        checkNotNull(javaClass.getResource(path)) { "Missing frozen fixture $path" }.readText()
}
