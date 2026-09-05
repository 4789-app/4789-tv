package com.fourseveneightnine.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineMediaPolicyTest {
    @Test
    fun namesAreOpaqueStableAndPathSafe() {
        val first = OfflineMediaPolicy.safeName("tt26657236")
        assertEquals(first, OfflineMediaPolicy.safeName("tt26657236"))
        assertTrue(first.matches(Regex("[0-9a-f]{32}\\.media")))
        assertFalse(first.contains("tt26657236"))
        assertFalse(OfflineMediaPolicy.safeName("../outside").contains(".."))
    }

    @Test
    fun importRequiresKnownBoundedSizeAndDiskReserve() {
        val reserve = OfflineMediaPolicy.RESERVE_BYTES
        assertTrue(OfflineMediaPolicy.canStart(1_000, reserve + 1_000))
        assertFalse(OfflineMediaPolicy.canStart(-1, Long.MAX_VALUE))
        assertFalse(OfflineMediaPolicy.canStart(1_001, reserve + 1_000))
        assertFalse(
            OfflineMediaPolicy.canStart(OfflineMediaPolicy.MAXIMUM_BYTES + 1, Long.MAX_VALUE),
        )
    }

    @Test
    fun anInterruptedDownloadOnlyContinuesOnPositiveEvidence() {
        assertEquals(400L, OfflineMediaPolicy.resumeOffset(400, 1_000, "\"v1\"", "\"v1\""))
        // The bytes moved, so appending would build a file that passes its size check and is junk.
        assertEquals(0L, OfflineMediaPolicy.resumeOffset(400, 1_000, "\"v1\"", "\"v2\""))
        assertEquals(0L, OfflineMediaPolicy.resumeOffset(400, 1_000, null, "\"v1\""))
        assertEquals(0L, OfflineMediaPolicy.resumeOffset(400, 1_000, "\"v1\"", null))
        assertEquals(0L, OfflineMediaPolicy.resumeOffset(400, 1_000, "\"v1\"", " "))
        assertEquals(0L, OfflineMediaPolicy.resumeOffset(0, 1_000, "\"v1\"", "\"v1\""))
        assertEquals(0L, OfflineMediaPolicy.resumeOffset(1_000, 1_000, "\"v1\"", "\"v1\""))
        assertEquals(0L, OfflineMediaPolicy.resumeOffset(400, -1, "\"v1\"", "\"v1\""))
    }

    @Test
    fun contentRangeIsReadForItsStartAndItsTotal() {
        assertEquals(400L, OfflineMediaPolicy.contentRangeStart("bytes 400-999/1000"))
        assertEquals(1_000L, OfflineMediaPolicy.contentRangeTotal("bytes 400-999/1000"))
        assertNull(OfflineMediaPolicy.contentRangeTotal("bytes 400-999/*"))
        assertNull(OfflineMediaPolicy.contentRangeStart(null))
        assertNull(OfflineMediaPolicy.contentRangeStart("pages 1-2/3"))
    }

    @Test
    fun byteCountsUseDeterministicBinaryUnits() {
        assertEquals("0 B", OfflineMediaPolicy.formatBytes(0))
        assertEquals("1.5 KiB", OfflineMediaPolicy.formatBytes(1_536))
        assertEquals("2.0 MiB", OfflineMediaPolicy.formatBytes(2L * 1024 * 1024))
        assertEquals("3.0 GiB", OfflineMediaPolicy.formatBytes(3L * 1024 * 1024 * 1024))
    }
}
