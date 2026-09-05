package com.fourseveneightnine.tv.settings

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Deterministic, high-contrast QR rendering for the short-lived local pairing invitation. */
internal object PairingQRCode {
    fun render(payload: String, sizePixels: Int = 640): Bitmap {
        require(payload.isNotBlank())
        require(sizePixels in 256..1_024)
        val matrix = QRCodeWriter().encode(
            payload,
            BarcodeFormat.QR_CODE,
            sizePixels,
            sizePixels,
            mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.Q,
                EncodeHintType.MARGIN to 2,
            ),
        )
        val pixels = IntArray(sizePixels * sizePixels)
        for (y in 0 until sizePixels) {
            for (x in 0 until sizePixels) {
                pixels[y * sizePixels + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, sizePixels, sizePixels, Bitmap.Config.ARGB_8888)
    }
}
