package com.fourseveneightnine.phone

import com.fourseveneightnine.contract.DiscoverItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneRoutePolicyTest {
    @Test
    fun restoredSelectionMustStillExistInRefreshedCatalog() {
        val items = listOf(DiscoverItem(id = "tt1", type = "movie", title = "One"))

        assertTrue(PhoneRoutePolicy.hasTitle(items, null))
        assertTrue(PhoneRoutePolicy.hasTitle(items, "tt1"))
        assertFalse(PhoneRoutePolicy.hasTitle(items, "tt-retired"))
    }
}
