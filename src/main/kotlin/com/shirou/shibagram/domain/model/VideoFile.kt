package com.shirou.shibagram.domain.model

/**
 * Represents a video file in a media message.
 * Ported from Android ShibaGram app.
 */
data class VideoFile(
    val id: Int,
    val size: Long,
    val downloadedSize: Long = 0,
    val localPath: String? = null,
    val isDownloading: Boolean = false,
    val isDownloaded: Boolean = false
) {
    val downloadProgress: Float
        get() = if (size > 0) downloadedSize.toFloat() / size.toFloat() else 0f
}
