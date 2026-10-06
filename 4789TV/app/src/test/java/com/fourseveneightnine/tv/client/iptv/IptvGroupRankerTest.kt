package com.fourseveneightnine.tv.client.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

class IptvGroupRankerTest {
    @Test fun `late group ranking retains current favorites PIN sources and manual ordering`() {
        val source = IptvSource("one", "TV", IptvSourceKind.M3U, enabled = false)
        val current = IptvAccounts(sources = listOf(source), favoriteIds = setOf("one:1"),
            favoriteLists = mapOf("Sports" to setOf("one:1")), recentIds = listOf("one:2"),
            parentalPinHash = "current-pin", groupOrder = listOf("Sports", "Telugu"), manualGroupOrder = true)
        val decision = IptvGroupDecision("TELUGU", 0, 0.9, "jev-test")
        val stale = IptvAccounts(groupOrder = listOf("Telugu", "Sports"), groupDecisions = mapOf("Telugu" to decision),
            decisionPolicyVersion = 2)
        val merged = mergeIptvGroupRanking(current, stale)
        assertEquals(current.favoriteIds, merged.favoriteIds)
        assertEquals(current.favoriteLists, merged.favoriteLists)
        assertEquals(current.recentIds, merged.recentIds)
        assertEquals(current.parentalPinHash, merged.parentalPinHash)
        assertEquals(current.sources, merged.sources)
        assertEquals(current.groupOrder, merged.groupOrder)
        assertEquals(decision, merged.groupDecisions["Telugu"])
        assertEquals(stale.groupOrder, mergeIptvGroupRanking(current.copy(manualGroupOrder = false), stale).groupOrder)
    }

    @Test fun `Telugu then cricket and sports then Hindi and Tamil then English`() {
        val groups = listOf("US movies", "Tamil TV", "Hindi TV", "Cricket", "Telugu TV", "Sports")
        val priorities = mapOf("Telugu TV" to 0, "Cricket" to 1, "Sports" to 2,
            "Hindi TV" to 3, "Tamil TV" to 4, "US movies" to 8)
        val decisions = priorities.mapValues { (_, value) -> IptvGroupDecision("test", value, 1.0, "jev-test") }
        assertEquals(listOf("Telugu TV", "Cricket", "Sports", "Hindi TV", "Tamil TV", "US movies"),
            IptvGroupRanker.stableOrder(groups, emptyList(), decisions))
    }

    @Test fun `provider refresh cannot reorder existing groups within a priority`() {
        val decision = IptvGroupDecision("SPORTS", 2, 0.9, "jev-test")
        val groups = listOf("Golf", "Football", "Cricket")
        val decisions = groups.associateWith { decision }
        assertEquals(listOf("Cricket", "Football", "Golf"),
            IptvGroupRanker.stableOrder(groups, listOf("Cricket", "Football", "Golf"), decisions))
    }
    @Test fun `category fallback never labels an unverified language as originals`() {
        assertEquals("TELUGU_MIXED", IptvGroupRanker.vodFallback("Telugu 2026").bucket)
        assertEquals("TELUGU_DUBBED", IptvGroupRanker.vodFallback("Telugu dubbed").bucket)
        assertEquals("UNKNOWN_MIXED", IptvGroupRanker.vodFallback("Actor collection").bucket)
        org.junit.Assert.assertTrue(IptvGroupRanker.vodFallback("Telugu").priority < IptvGroupRanker.vodFallback("Hindi").priority)
        org.junit.Assert.assertTrue(IptvGroupRanker.vodFallback("Hindi").priority < IptvGroupRanker.vodFallback("Tamil").priority)
        org.junit.Assert.assertTrue(IptvGroupRanker.vodDecisionKey("one", "movie", "Telugu") != IptvGroupRanker.vodDecisionKey("two", "movie", "Telugu"))
        assertEquals("one|movie|telugu", IptvGroupRanker.vodDecisionKey("one", "movie", " TELUGU "))
    }

}
