package com.fourseveneightnine.tv.settings

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Reads a Storage Access Framework selection without allowing an unbounded allocation. */
internal object TVSettingsFileReader {
    fun read(input: InputStream, maxBytes: Int = TVSettingsBackupPolicy.MAX_BYTES): ByteArray {
        require(maxBytes > 0)
        val output = ByteArrayOutputStream(minOf(maxBytes, 32 * 1_024))
        val buffer = ByteArray(8 * 1_024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) {
                val single = input.read()
                if (single < 0) break
                total += 1
                require(total <= maxBytes) { "settings_too_large" }
                output.write(single)
                continue
            }
            total += count
            require(total <= maxBytes) { "settings_too_large" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
