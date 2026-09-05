package com.fourseveneightnine.tv.ui

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * Makes a quiet 16:9 extension for a portrait poster. The poster itself remains untouched and is
 * drawn with FIT_CENTER above this bitmap, so faces, titles, and credits are never enlarged into
 * the crop. The small intermediate is intentional: it is a backdrop colour field, not a second
 * high-resolution poster competing for Fire TV memory.
 */
internal fun Bitmap.blurredExtension(width: Int = 96, height: Int = 54): Bitmap {
    val targetAspect = width.toFloat() / height.toFloat()
    val sourceAspect = this.width.toFloat() / this.height.toFloat()
    val cropWidth: Int
    val cropHeight: Int
    val cropLeft: Int
    val cropTop: Int
    if (sourceAspect > targetAspect) {
        cropHeight = this.height
        cropWidth = max(1, (this.height * targetAspect).toInt())
        cropLeft = (this.width - cropWidth) / 2
        cropTop = 0
    } else {
        cropWidth = this.width
        cropHeight = max(1, (this.width / targetAspect).toInt())
        cropLeft = 0
        cropTop = (this.height - cropHeight) / 2
    }

    val cropped = Bitmap.createBitmap(this, cropLeft, cropTop, cropWidth, cropHeight)
    val small = Bitmap.createScaledBitmap(cropped, width, height, true)
    if (small !== cropped) cropped.recycle()

    val pixels = IntArray(width * height)
    small.getPixels(pixels, 0, width, 0, 0, width, height)
    if (small !== cropped) small.recycle()

    repeat(3) {
        val source = pixels.copyOf()
        for (y in 0 until height) {
            for (x in 0 until width) {
                var red = 0
                var green = 0
                var blue = 0
                var count = 0
                for (dy in -2..2) {
                    for (dx in -2..2) {
                        val sampleX = min(width - 1, max(0, x + dx))
                        val sampleY = min(height - 1, max(0, y + dy))
                        val color = source[sampleY * width + sampleX]
                        red += (color shr 16) and 0xFF
                        green += (color shr 8) and 0xFF
                        blue += color and 0xFF
                        count += 1
                    }
                }
                pixels[y * width + x] = (0xFF shl 24) or
                    ((red / count) shl 16) or
                    ((green / count) shl 8) or
                    (blue / count)
            }
        }
    }

    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}
