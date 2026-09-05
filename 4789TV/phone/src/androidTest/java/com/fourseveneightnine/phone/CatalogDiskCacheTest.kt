package com.fourseveneightnine.phone

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The catalog must paint from disk on a cold start instead of paying for the network again.
 */
class CatalogDiskCacheTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        cacheDirectory().deleteRecursively()
    }

    @After
    fun tearDown() {
        cacheDirectory().deleteRecursively()
    }

    @Test
    fun aStoredEntryComesBackWithoutTheNetwork() {
        val cache = CatalogDiskCache(context)
        cache.store(URL, PAYLOAD)

        assertArrayEquals(PAYLOAD, cache.fresh(URL))
    }

    @Test
    fun anEntryPastItsLifetimeIsNotFreshButIsStillAvailableStale() {
        val cache = CatalogDiskCache(context)
        cache.store(URL, PAYLOAD)

        // Read it as though half a day and one second had passed. Twelve hours is the iOS TTL.
        val justPastTtl = System.currentTimeMillis() + CatalogDiskCache.DEFAULT_TTL_MILLIS + 1_000

        assertNull(cache.fresh(URL, justPastTtl))
        assertArrayEquals(PAYLOAD, cache.stale(URL))
    }

    @Test
    fun anUnknownAddressHasNothingToReturn() {
        val cache = CatalogDiskCache(context)

        assertNull(cache.fresh(URL))
        assertNull(cache.stale(URL))
    }

    @Test
    fun twoAddressesDoNotShareAnEntry() {
        val cache = CatalogDiskCache(context)
        val other = "https://api.4789library.com/v1/artifacts/other.json"
        cache.store(URL, PAYLOAD)
        cache.store(other, OTHER_PAYLOAD)

        assertArrayEquals(PAYLOAD, cache.fresh(URL))
        assertArrayEquals(OTHER_PAYLOAD, cache.fresh(other))
    }

    @Test
    fun theCacheHoldsRealCatalogBytesAndKeepsThemByteForByte() {
        // A catalog artifact is about 46 KB of JSON. Round-tripping something that size proves the
        // store is not quietly truncating or re-encoding what it was handed.
        val big = ByteArray(46_129) { (it % 251).toByte() }
        val cache = CatalogDiskCache(context)
        cache.store(URL, big)

        val read = cache.fresh(URL)

        assertEquals(big.size, read?.size)
        assertArrayEquals(big, read)
        assertTrue(cacheDirectory().listFiles().orEmpty().isNotEmpty())
    }

    private fun cacheDirectory() = File(context.cacheDir, "catalog-cache-v1")

    private companion object {
        const val URL = "https://api.4789library.com/v1/catalog-manifest"
        val PAYLOAD = """{"schemaVersion":1,"payload":"abc"}""".encodeToByteArray()
        val OTHER_PAYLOAD = """{"schemaVersion":1,"payload":"xyz"}""".encodeToByteArray()
    }
}
