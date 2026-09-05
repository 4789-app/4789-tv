package com.fourseveneightnine.phone

import com.fourseveneightnine.contract.DiscoverItem

internal object PhoneRoutePolicy {
    fun hasTitle(items: List<DiscoverItem>, titleID: String?): Boolean =
        titleID == null || items.any { it.id == titleID }
}
