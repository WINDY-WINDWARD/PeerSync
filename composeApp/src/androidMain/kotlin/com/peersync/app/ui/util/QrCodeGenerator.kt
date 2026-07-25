package com.peersync.app.ui.util

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix

/**
 * Android implementation: Generate a QR code bitmap from the given content.
 * Uses ZXing library for QR encoding.
 */
actual fun generateQrBitmap(content: String, size: Int): ImageBitmap? {
    return try {
        val writer = MultiFormatWriter()
        val bitMatrix: BitMatrix = writer.encode(content, com.google.zxing.BarcodeFormat.QR_CODE, size, size)
        val bitmap = createBitmapFromBitMatrix(bitMatrix)
        bitmap?.asImageBitmap()
    } catch (e: Exception) {
        null
    }
}

/**
 * Convert a ZXing BitMatrix to an Android Bitmap.
 */
private fun createBitmapFromBitMatrix(bitMatrix: BitMatrix): Bitmap? {
    return try {
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        
        for (x in 0 until width) {
            for (y in 0 until height) {
                val color = if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                bitmap.setPixel(x, y, color)
            }
        }
        
        bitmap
    } catch (e: Exception) {
        null
    }
}
