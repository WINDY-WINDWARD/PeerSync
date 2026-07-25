package com.peersync.app.ui.util

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Generate a QR code bitmap from the given content.
 * Platform-specific implementation handles the actual encoding.
 */
expect fun generateQrBitmap(content: String, size: Int = 512): ImageBitmap?
