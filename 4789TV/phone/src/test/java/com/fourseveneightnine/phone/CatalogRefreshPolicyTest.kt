package com.fourseveneightnine.phone

import com.fourseveneightnine.contract.DiscoverItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogRefreshPolicyTest {
    @Test
    fun liveRefreshKeepsBundledSelectionsAndPrefersLiveDuplicate() {
        val bundled = listOf(
            DiscoverItem(id = "tt1", type = "movie", title = "Bundled one"),
            DiscoverItem(id = "tt2", type = "movie", title = "Bundled two"),
        )
        val remote = listOf(
            DiscoverItem(id = "tt1", type = "movie", title = "Live one"),
            DiscoverItem(id = "tt3", type = "movie", title = "Live three"),
        )

        assertEquals(
            listOf("tt1:Live one", "tt3:Live three", "tt2:Bundled two"),
            CatalogSnapshotRepository.mergeRemoteWithBundled(remote, bundled)
                .map { "${it.id}:${it.title}" },
        )
    }

    @Test
    fun verifiedRemoteCacheIsBoundedAndMonotonic() {
        val stored = 10_000_000_000L
        assertTrue(CatalogRefreshPolicy.isFresh(stored, stored))
        assertTrue(CatalogRefreshPolicy.isFresh(stored + 299_999_999_999L, stored))
        assertFalse(CatalogRefreshPolicy.isFresh(stored + 300_000_000_000L, stored))
        assertFalse(CatalogRefreshPolicy.isFresh(stored - 1, stored))
    }
}
