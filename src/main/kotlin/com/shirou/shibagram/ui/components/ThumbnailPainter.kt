package com.shirou.shibagram.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import com.shirou.shibagram.data.local.ThumbnailCache
import com.shirou.shibagram.data.remote.TelegramClientService

/**
 * Loads an image (thumbnail / channel photo) through [ThumbnailCache].
 * When the file isn't local yet ([path] is null) but its TDLib [fileId] is known, it is downloaded
 * first, so cards fill in instead of staying on the placeholder until the list is reloaded.
 * Keyed on both, so a recycled list slot never keeps showing the previous item's image.
 */
@Composable
fun rememberThumbnailPainter(path: String?, fileId: Int? = null): Painter? {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path, fileId) {
        val localPath = path ?: fileId?.takeIf { it != 0 }?.let {
            TelegramClientService.getInstance().downloadSmallFile(it)
        }
        value = localPath?.let { ThumbnailCache.getOrLoad(it) }
    }
    return remember(bitmap) { bitmap?.let(::BitmapPainter) }
}
