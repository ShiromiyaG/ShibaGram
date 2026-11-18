package com.shirou.shibagram.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import com.shirou.shibagram.data.local.ThumbnailCache

/**
 * Loads an image file (thumbnail / channel photo) through [ThumbnailCache].
 * Keyed on [path], so a recycled list slot never keeps showing the previous item's image.
 */
@Composable
fun rememberThumbnailPainter(path: String?): Painter? {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
        value = path?.let { ThumbnailCache.getOrLoad(it) }
    }
    return remember(bitmap) { bitmap?.let(::BitmapPainter) }
}
