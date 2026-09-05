package com.fourseveneightnine.tv.settings

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TVSettingsFileReaderTest {
    @Test
    fun readsDocumentWithinBound() {
        val bytes = "{\"format\":\"4789-settings\"}".encodeToByteArray()
        assertArrayEquals(bytes, TVSettingsFileReader.read(ByteArrayInputStream(bytes), bytes.size))
    }

    @Test
    fun rejectsBeforeRetainingMoreThanBound() {
        assertThrows(IllegalArgumentException::class.java) {
            TVSettingsFileReader.read(ByteArrayInputStream(ByteArray(65)), maxBytes = 64)
        }
    }
}
